package com.openarcade.ui.screens

import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import com.openarcade.audio.Sfx
import com.openarcade.audio.Song
import com.openarcade.core.ArcadeApp
import com.openarcade.core.Dir
import com.openarcade.core.Gestures
import com.openarcade.games.GameDef
import com.openarcade.games.TutorialDemo
import com.openarcade.gfx.Align
import com.openarcade.gfx.Gfx
import com.openarcade.gfx.Icons
import com.openarcade.gfx.Pal
import com.openarcade.gfx.PixelFont
import com.openarcade.ui.Button
import com.openarcade.ui.ButtonSet
import com.openarcade.ui.Screen
import kotlin.math.sin

/**
 * Illustrated, paged "how to play" shown before the first round and on demand. Swipe or use the
 * arrows to move between pages.
 */
class TutorialScreen(app: ArcadeApp, private val def: GameDef, private val onDone: () -> Unit) : Screen(app) {
    override val isOverlay = true
    override val title get() = "HOW TO PLAY"
    override val music: Song get() = def.music
    override val showBack = true

    private var page = 0
    private var t = 0f
    private var pageT = 0f
    private val buttons = ButtonSet(app)
    private val box = RectF()
    private val gestures = Gestures { app.p * 4 }

    init {
        gestures.onSwipe = { d ->
            if (d == Dir.LEFT) next() else if (d == Dir.RIGHT) prev()
        }
    }

    private val pages get() = def.tutorial
    private var demo: TutorialDemo? = null
    private var demoPage = -1
    private val demoBox = RectF()
    private var demoTouch = false

    /** Wide layout: text on the left, illustration/demo on the right. */
    private val wide get() = content.width() > content.height() * 1.15f && content.width() >= app.p * 240

    override fun layout() = rebuild()

    private fun rebuild() {
        val p = app.p
        buttons.clear()
        val maxW = if (wide) p * 330 else p * 210
        val w = minOf(content.width() - p * 8, maxW)
        val maxH = if (wide) minOf(content.height() - p * 10, p * 260) else minOf(content.height() - p * 10, neededHeight(w))
        val top = content.top + (content.height() - maxH) / 2
        box.set((app.w - w) / 2, top, (app.w + w) / 2, top + maxH)
        val bh = p * 18
        val y = box.bottom - bh - p * 5
        val gap = p * 3
        val bw = minOf((box.width() - p * 10 - gap * 2) / 3, p * 70)
        val rowW = bw * 3 + gap * 2
        val x0 = box.centerX() - rowW / 2
        val last = page == pages.size - 1
        buttons.add(Button("SKIP", null, Pal.BG3, Sfx.BACK) { finish() }).also { it.visible = !last }
            .set(x0, y, x0 + bw, y + bh)
        buttons.add(Button("", Icons.BACK, Pal.BG3, Sfx.CLICK) { prev() }).also { it.enabled = page > 0 }
            .set(x0 + bw + gap, y, x0 + bw * 2 + gap, y + bh)
        val nb = if (last) Button("PLAY!", Icons.PLAY, Pal.GREEN, Sfx.START) { finish() }.also { it.textColor = Pal.BLACK }
        else Button("NEXT", null, Pal.BLUE, Sfx.CLICK) { next() }
        buttons.add(nb).set(x0 + (bw + gap) * 2, y, x0 + rowW, y + bh)
        // create this page's interactive demo once
        if (demoPage != page) {
            demoPage = page
            demo = pages[page].demo?.invoke()?.also { d -> d.sound = { app.audio.play(it) } }
        }
    }

    /** Portrait panel height that fits the current page's title, visual, text and buttons. */
    private fun neededHeight(w: Float): Float {
        val p = app.p
        val pg = pages[page]
        val ts = Gfx.fitSize(pg.title, w - p * 12, app.tBody * 1.5f)
        val textH = Gfx.paragraphHeight(pg.text, w - p * 14, app.tSmall)
        val hasDemo = pg.demo != null
        val visual = if (hasDemo || pg.art != null) minOf(w - p * 14, if (hasDemo) p * 150 else p * 90) + p * 6 else 0f
        val taskH = if (hasDemo) PixelFont.LINE * app.tSmall * 2 + p * 4 else 0f
        return p * 20 + PixelFont.height(ts) + visual + taskH + textH + p * 8 + p * 34
    }

    private fun next() {
        if (page < pages.size - 1) { page++; pageT = 0f; app.audio.play(Sfx.MOVE); rebuild() }
    }

    private fun prev() {
        if (page > 0) { page--; pageT = 0f; app.audio.play(Sfx.MOVE); rebuild() }
    }

    private fun finish() {
        app.storage.setTutorialSeen(def.id)
        if (page == pages.size - 1) app.progress.unlock("arcade.tutor")
        app.popOverlay(this)
        onDone()
    }

    override fun back(): Boolean {
        // leaving with back marks the tutorial as seen but does not start the game
        app.storage.setTutorialSeen(def.id)
        app.popOverlay(this)
        return true
    }

    override fun update(dt: Float) { t += dt; pageT += dt; demo?.update(dt) }

    override fun draw(c: Canvas) {
        val p = app.p
        Gfx.panel(c, box, Pal.BG1, def.accent, p)
        val pg = pages[page]
        // header
        Gfx.text(c, def.title, box.left + p * 6, box.top + p * 6, app.tSmall, def.accent)
        Gfx.text(c, "${page + 1}/${pages.size}", box.right - p * 6, box.top + p * 6, app.tSmall, Pal.GREY, Align.RIGHT)
        val ts = Gfx.fitSize(pg.title, box.width() - p * 12, app.tBody * 1.5f)
        Gfx.text(c, pg.title, box.centerX(), box.top + p * 15, ts, Pal.YELLOW, Align.CENTER)

        val contentTop = box.top + p * 20 + PixelFont.height(ts)
        val bottomLimit = box.bottom - p * 34
        val d = demo
        val taskH = if (d != null) PixelFont.LINE * app.tSmall * 2 + p * 4 else 0f
        val textSize = app.tSmall
        if (wide) {
            // two columns: explanation left, picture or playable demo right
            val colGap = p * 8
            val leftW = (box.width() - p * 14 - colGap) * 0.48f
            val textX = box.left + p * 7
            Gfx.paragraph(c, pg.text, textX, contentTop + p * 2, leftW, textSize, Pal.WHITE)
            val rx = textX + leftW + colGap
            val rw = box.right - p * 7 - rx
            val avail = bottomLimit - contentTop - taskH
            val s = minOf(rw, avail)
            drawVisual(c, pg, d, RectF(rx + (rw - s) / 2, contentTop, rx + (rw + s) / 2, contentTop + s))
        } else {
            val textW = box.width() - p * 14
            val textH = Gfx.paragraphHeight(pg.text, textW, textSize)
            val space = bottomLimit - contentTop - textH - p * 8 - taskH
            var y = contentTop
            if ((pg.art != null || d != null) && space > p * 16) {
                val s = minOf(space, box.width() - p * 14, if (d != null) p * 150 else p * 90)
                drawVisual(c, pg, d, RectF(box.centerX() - s / 2, y, box.centerX() + s / 2, y + s))
                y += s + p * 6 + taskH
            } else demoBox.setEmpty()
            Gfx.paragraph(c, pg.text, box.left + p * 7, y, textW, textSize, Pal.WHITE)
        }

        // page dots
        val dotY = box.bottom - p * 29
        val n = pages.size
        val dw = p * 4
        val total = n * dw + (n - 1) * p * 3
        var dx = box.centerX() - total / 2
        for (i in 0 until n) {
            Gfx.rect(c, dx, dotY, dx + dw, dotY + dw, if (i == page) Pal.YELLOW else Pal.DGREY)
            dx += dw + p * 3
        }
        buttons.draw(c)
    }

    /** Draws the page's demo (with its instruction line below) or its static art in [frame]. */
    private fun drawVisual(c: Canvas, pg: com.openarcade.games.TutorialPage, d: TutorialDemo?, frame: RectF) {
        val p = app.p
        Gfx.rect(c, frame, Pal.BG0)
        Gfx.frame(c, frame, p, if (d?.done == true) Pal.GREEN else Pal.BG3)
        if (d != null) {
            demoBox.set(frame.left + p * 3, frame.top + p * 3, frame.right - p * 3, frame.bottom - p * 3)
            d.draw(c, demoBox, p, t)
            val msg = d.feedback.ifEmpty { d.task }
            val col = when {
                d.done -> Pal.GREEN
                d.feedback.isNotEmpty() && !d.feedbackGood -> Pal.lighter(Pal.RED, 0.3f)
                d.feedback.isNotEmpty() -> Pal.CYAN
                else -> Pal.YELLOW
            }
            val prefix = if (d.done) "${PixelFont.STAR} " else ""
            Gfx.paragraph(c, prefix + msg, frame.left, frame.bottom + p * 3, frame.width(), app.tSmall, col, Align.CENTER)
        } else {
            demoBox.setEmpty()
            val art = pg.art ?: return
            val appear = (pageT / 0.25f).coerceIn(0f, 1f)
            val bob = sin(t * 2.2f) * p
            val inset = p * 4
            art.drawFit(c, RectF(frame.left + inset, frame.top + inset + bob, frame.right - inset, frame.bottom - inset + bob), (255 * appear).toInt())
        }
    }

    override fun touch(e: MotionEvent): Boolean {
        if (buttons.touch(e)) return true
        val d = demo
        if (e.actionMasked == MotionEvent.ACTION_DOWN) demoTouch = d != null && demoBox.contains(e.x, e.y)
        if (demoTouch && d != null) {
            d.onTouch(e, demoBox)
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) demoTouch = false
            return true
        }
        gestures.handle(e)
        return true
    }
}
