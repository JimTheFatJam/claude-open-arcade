package com.openarcade.ui.screens

import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import com.openarcade.audio.Sfx
import com.openarcade.core.ArcadeApp
import com.openarcade.gfx.Align
import com.openarcade.gfx.Gfx
import com.openarcade.gfx.Icons
import com.openarcade.gfx.Pal
import com.openarcade.gfx.PixelFont
import com.openarcade.ui.Button
import com.openarcade.ui.ButtonSet
import com.openarcade.ui.Screen
import com.openarcade.update.ApkInstaller
import com.openarcade.update.UpdateManager
import com.openarcade.update.UpdateManager.Phase
import kotlin.math.floor

/**
 * The update dialog, drawn in the console's pixel style. It mirrors [UpdateManager.state]: offer,
 * download progress, verification, install instructions and errors.
 */
class UpdateScreen(app: ArcadeApp) : Screen(app) {
    override val isOverlay = true
    override val title = "UPDATE"

    private val buttons = ButtonSet(app)
    private val box = RectF()
    private val notesBox = RectF()
    private var notesScroll = 0f
    private var notesMax = 0f
    private var lastPhase: Phase? = null
    private var lastCanInstall = true
    private var lastWifi = true
    private var recheckT = 0f
    private var t = 0f
    private var dragY = -1f

    private val st get() = UpdateManager.state

    override fun layout() = rebuild()

    override fun update(dt: Float) {
        t += dt
        recheckT += dt
        if (recheckT > 1f) {
            recheckT = 0f
            val ci = ApkInstaller.canRequestInstalls(app.context)
            val wifi = UpdateManager.isOnWifi()
            if (ci != lastCanInstall || wifi != lastWifi) { lastCanInstall = ci; lastWifi = wifi; rebuild() }
        }
        if (st.phase == Phase.IDLE) { app.popOverlay(this); return }
        if (st.phase != lastPhase) rebuild()
    }

    private fun close() {
        UpdateManager.dismiss()
        app.popOverlay(this)
    }

    override fun back(): Boolean { close(); return true }

    /** Body text for the current state. */
    private fun bodyText(): Pair<String, Int> {
        val s = st
        val rel = s.release
        return when (s.phase) {
            Phase.CHECKING -> "CHECKING GITHUB FOR A NEW VERSION..." to Pal.LGREY
            Phase.UP_TO_DATE -> "YOU HAVE THE LATEST VERSION. ENJOY!" to Pal.GREEN
            Phase.NO_WIFI -> "UPDATES ARE ONLY CHECKED AND DOWNLOADED ON WI-FI, NEVER ON MOBILE DATA. CONNECT TO WI-FI AND TRY AGAIN." to Pal.LGREY
            Phase.AVAILABLE -> (if (!lastWifi) "CONNECT TO WI-FI TO DOWNLOAD THIS UPDATE." else
                "DOWNLOAD SIZE ${sizeText(rel?.apkSize ?: 0)}. YOUR SCORES, SAVES AND SETTINGS ARE KEPT.") to Pal.LGREY
            Phase.DOWNLOADING -> "DOWNLOADING OVER WI-FI. YOU CAN HIDE THIS AND KEEP PLAYING." to Pal.LGREY
            Phase.VERIFYING -> "CHECKING THE DOWNLOAD: CHECKSUM AND DEVELOPER SIGNATURE..." to Pal.CYAN
            Phase.READY -> if (!lastCanInstall)
                "ONE-TIME STEP: ANDROID NEEDS YOUR PERMISSION. TAP ALLOW, TURN ON \"ALLOW FROM THIS SOURCE\", THEN COME BACK HERE." to Pal.YELLOW
            else ((if (s.message.isNotEmpty()) s.message + "\n" else "") +
                "VERIFIED: CHECKSUM AND DEVELOPER SIGNATURE OK. TAP INSTALL - ANDROID WILL ASK YOU TO CONFIRM THE UPDATE.") to Pal.GREEN
            Phase.INSTALLING -> "OPENING ANDROID'S INSTALLER..." to Pal.LGREY
            Phase.CONFIRM -> "CONFIRM THE UPDATE ON ANDROID'S SCREEN. THE GAME CLOSES WHILE IT UPDATES - OPEN IT AGAIN AFTERWARDS." to Pal.YELLOW
            Phase.ERROR -> s.message.ifEmpty { "SOMETHING WENT WRONG" } to Pal.lighter(Pal.RED, 0.3f)
            Phase.IDLE -> "" to Pal.LGREY
        }
    }

    private fun sizeText(bytes: Long): String = when {
        bytes <= 0 -> "UNKNOWN"
        bytes < 1024 * 1024 -> "${(bytes + 1023) / 1024} KB"
        else -> "%.1f MB".format(bytes / 1048576.0)
    }

    private fun rebuild() {
        val p = app.p
        val s = st
        lastPhase = s.phase
        lastCanInstall = ApkInstaller.canRequestInstalls(app.context)
        lastWifi = UpdateManager.isOnWifi()
        buttons.clear()
        val w = minOf(content.width() - p * 8, if (app.wideLayout) p * 260 else p * 200)
        val textW = w - p * 12
        val showNotes = s.release != null && s.phase in setOf(Phase.AVAILABLE, Phase.ERROR, Phase.READY, Phase.DOWNLOADING, Phase.VERIFYING)
        val bodyH = Gfx.paragraphHeight(bodyText().first, textW, app.tSmall)
        val notesH = if (showNotes) minOf(p * 90, content.height() * 0.4f) else 0f
        val progressH = if (s.phase == Phase.DOWNLOADING || s.phase == Phase.VERIFYING) p * 22 else 0f
        val h = p * 14 + PixelFont.height(app.tBody) + p * 8 +          // title
            (if (s.release != null) PixelFont.height(app.tSmall) * 2 + p * 12 else PixelFont.height(app.tSmall) + p * 8) +  // versions
            (if (showNotes) notesH + PixelFont.height(app.tSmall) + p * 8 else 0f) +
            progressH + bodyH + p * 8 + p * 18 + p * 6
        val boxH = minOf(h, content.height() - p * 8)
        box.set((app.w - w) / 2, content.centerY() - boxH / 2, (app.w + w) / 2, content.centerY() + boxH / 2)
        if (showNotes) {
            val top = box.top + p * 14 + PixelFont.height(app.tBody) + p * 8 + PixelFont.height(app.tSmall) * 2 + p * 12 + PixelFont.height(app.tSmall) + p * 4
            notesBox.set(box.left + p * 6, top, box.right - p * 6, top + notesH)
        } else notesBox.setEmpty()

        // buttons
        val acts = ArrayList<Button>()
        fun b(label: String, icon: com.openarcade.gfx.PixelArt?, color: Int, sfx: Sfx = Sfx.SELECT, enabled: Boolean = true, fn: () -> Unit) =
            acts.add(Button(label, icon, color, sfx) { fn() }.also { it.enabled = enabled; it.textSize = app.tSmall })
        when (s.phase) {
            Phase.CHECKING -> b("CLOSE", null, Pal.BG3, Sfx.BACK) { close() }
            Phase.UP_TO_DATE, Phase.NO_WIFI -> b("OK", Icons.CHECK, Pal.BLUE) { close() }
            Phase.AVAILABLE -> {
                b("SKIP", null, Pal.BG3, Sfx.BACK) { UpdateManager.skip(); app.popOverlay(this) }
                b("LATER", null, Pal.BG3, Sfx.BACK) { close() }
                b("DOWNLOAD", Icons.DOWNLOAD, Pal.DGREEN, enabled = lastWifi) { UpdateManager.download() }
            }
            Phase.DOWNLOADING -> {
                b("HIDE", null, Pal.BG3, Sfx.BACK) { app.popOverlay(this) }
                b("CANCEL", Icons.CLOSE, Pal.DRED, Sfx.BACK) { UpdateManager.cancelDownload() }
            }
            Phase.VERIFYING, Phase.INSTALLING -> b("HIDE", null, Pal.BG3, Sfx.BACK) { app.popOverlay(this) }
            Phase.READY -> {
                b("LATER", null, Pal.BG3, Sfx.BACK) { close() }
                if (!lastCanInstall) b("ALLOW", Icons.GEAR, Pal.BLUE) { UpdateManager.install(app.context) }
                else b("INSTALL", Icons.DOWNLOAD, Pal.DGREEN) { UpdateManager.install(app.context) }
            }
            Phase.CONFIRM -> {
                b("HIDE", null, Pal.BG3, Sfx.BACK) { app.popOverlay(this) }
                b("OPEN INSTALLER", null, Pal.DGREEN, enabled = UpdateManager.confirmIntent != null) { UpdateManager.openConfirmScreen(app.context) }
            }
            Phase.ERROR -> {
                b("CLOSE", null, Pal.BG3, Sfx.BACK) { close() }
                b("RETRY", Icons.RESTART, Pal.BLUE, enabled = lastWifi) {
                    if (s.release != null) UpdateManager.download() else UpdateManager.check(manual = true)
                }
            }
            Phase.IDLE -> {}
        }
        val gap = p * 3
        val bh = p * 18
        val bw = (box.width() - p * 12 - gap * (acts.size - 1)) / maxOf(1, acts.size)
        acts.forEachIndexed { i, btn ->
            val x = box.left + p * 6 + i * (bw + gap)
            buttons.add(btn).set(x, box.bottom - p * 6 - bh, x + bw, box.bottom - p * 6)
        }
    }

    override fun draw(c: Canvas) {
        val p = app.p
        val s = st
        val accent = when (s.phase) { Phase.ERROR -> Pal.RED; Phase.READY, Phase.UP_TO_DATE -> Pal.GREEN; else -> Pal.CYAN }
        Gfx.panel(c, box, Pal.BG1, accent, p)
        // title with icon
        val title = when (s.phase) {
            Phase.CHECKING -> "CHECKING..."
            Phase.UP_TO_DATE -> "UP TO DATE"
            Phase.NO_WIFI -> "WI-FI NEEDED"
            Phase.DOWNLOADING -> "DOWNLOADING"
            Phase.VERIFYING -> "VERIFYING"
            Phase.READY -> "READY TO INSTALL"
            Phase.INSTALLING, Phase.CONFIRM -> "INSTALLING"
            Phase.ERROR -> "UPDATE PROBLEM"
            else -> "UPDATE AVAILABLE"
        }
        val ic = p * 10
        val ts = Gfx.fitSize(title, box.width() - p * 16 - ic, app.tBody)
        val tw = PixelFont.width(title, ts) + ic + p * 3
        val tx = box.centerX() - tw / 2
        var y = box.top + p * 8
        Icons.DOWNLOAD.drawFit(c, RectF(tx, y, tx + ic, y + ic), tint = accent)
        Gfx.textMid(c, title, tx + ic + p * 3, y + ic / 2, ts, Pal.YELLOW, Align.LEFT)
        y += PixelFont.height(app.tBody) + p * 14

        // versions
        val left = box.left + p * 6
        val right = box.right - p * 6
        Gfx.text(c, "INSTALLED", left, y, app.tSmall, Pal.GREY)
        Gfx.text(c, "V${UpdateManager.installedVersion}", right, y, app.tSmall, Pal.WHITE, Align.RIGHT)
        y += PixelFont.height(app.tSmall) + p * 4
        val rel = s.release
        if (rel != null) {
            Gfx.text(c, "LATEST", left, y, app.tSmall, Pal.GREY)
            Gfx.text(c, "V${rel.version}", right, y, app.tSmall, Pal.GREEN, Align.RIGHT)
            y += PixelFont.height(app.tSmall) + p * 8
        } else y += p * 4

        // changelog
        if (!notesBox.isEmpty && rel != null) {
            Gfx.text(c, "WHAT'S NEW", left, y, app.tSmall, Pal.CYAN)
            Gfx.rect(c, notesBox, Pal.BG0)
            Gfx.frame(c, notesBox, maxOf(1f, p / 2), Pal.BG3)
            val notes = rel.notes.ifBlank { "NO RELEASE NOTES." }
            val innerW = notesBox.width() - p * 8
            val fullH = Gfx.paragraphHeight(notes, innerW, app.tSmall)
            notesMax = maxOf(0f, fullH - notesBox.height() + p * 8)
            notesScroll = notesScroll.coerceIn(0f, notesMax)
            c.save()
            c.clipRect(notesBox.left + p, notesBox.top + p, notesBox.right - p, notesBox.bottom - p)
            Gfx.paragraph(c, notes, notesBox.left + p * 4, notesBox.top + p * 4 - notesScroll, innerW, app.tSmall, Pal.WHITE)
            c.restore()
            if (notesMax > 0f) {
                val bar = notesBox.height() * notesBox.height() / (notesBox.height() + notesMax)
                val by = notesBox.top + (notesBox.height() - bar) * (notesScroll / notesMax)
                Gfx.rect(c, notesBox.right - p * 2, by, notesBox.right - p, by + bar, Pal.withAlpha(Pal.LGREY, 140))
            }
            y = notesBox.bottom + p * 8
        }

        // progress
        if (s.phase == Phase.DOWNLOADING || s.phase == Phase.VERIFYING) {
            val frac = if (s.total > 0) (s.done.toFloat() / s.total).coerceIn(0f, 1f) else 0f
            val bar = RectF(left, y, right, y + p * 9)
            Gfx.rect(c, bar, Pal.BLACK)
            val segs = 20
            val segW = (bar.width() - p * 2) / segs
            val lit = floor(frac * segs).toInt()
            for (k in 0 until segs) {
                val sx = bar.left + p + k * segW
                val col = if (k < lit) (if (s.phase == Phase.VERIFYING) Pal.CYAN else Pal.GREEN) else Pal.BG2
                Gfx.rect(c, sx + maxOf(1f, p / 3), bar.top + p, sx + segW - maxOf(1f, p / 3), bar.bottom - p, col)
            }
            val label = if (s.phase == Phase.VERIFYING) "VERIFYING" else
                "${sizeText(s.done)} / ${sizeText(s.total)}  ${(frac * 100).toInt()}%"
            Gfx.text(c, label, box.centerX(), bar.bottom + p * 3, app.tSmall, Pal.WHITE, Align.CENTER)
            y += p * 22
        }

        // body text
        val (body, col) = bodyText()
        val bottomLimit = box.bottom - p * 28
        if (body.isNotEmpty()) Gfx.paragraphClamped(c, body, left, y, right - left, app.tSmall, col, bottomLimit)
        buttons.draw(c)
    }

    override fun touch(e: MotionEvent): Boolean {
        if (buttons.touch(e)) return true
        // drag to scroll the changelog
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> dragY = if (notesBox.contains(e.x, e.y)) e.y else -1f
            MotionEvent.ACTION_MOVE -> if (dragY >= 0f) { notesScroll = (notesScroll - (e.y - dragY)).coerceIn(0f, notesMax); dragY = e.y }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragY = -1f
        }
        return true
    }
}
