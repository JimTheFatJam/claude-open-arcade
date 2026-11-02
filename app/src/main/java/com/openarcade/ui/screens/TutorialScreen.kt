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

    override fun layout() = rebuild()

    private fun rebuild() {
        val p = app.p
        buttons.clear()
        val w = minOf(content.width() - p * 8, p * 200)
        box.set((app.w - w) / 2, content.top + p * 5, (app.w + w) / 2, content.bottom - p * 5)
        val bh = p * 18
        val y = box.bottom - bh - p * 5
        val gap = p * 3
        val bw = (box.width() - p * 10 - gap * 2) / 3
        val last = page == pages.size - 1
        buttons.add(Button("SKIP", null, Pal.BG3, Sfx.BACK) { finish() }).also { it.visible = !last }
            .set(box.left + p * 5, y, box.left + p * 5 + bw, y + bh)
        buttons.add(Button("", Icons.BACK, Pal.BG3, Sfx.CLICK) { prev() }).also { it.enabled = page > 0 }
            .set(box.left + p * 5 + bw + gap, y, box.left + p * 5 + bw * 2 + gap, y + bh)
        val nb = if (last) Button("PLAY!", Icons.PLAY, Pal.GREEN, Sfx.START) { finish() }.also { it.textColor = Pal.BLACK }
        else Button("NEXT", null, Pal.BLUE, Sfx.CLICK) { next() }
        buttons.add(nb).set(box.right - p * 5 - bw, y, box.right - p * 5, y + bh)
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

    override fun update(dt: Float) { t += dt; pageT += dt }

    override fun draw(c: Canvas) {
        val p = app.p
        Gfx.panel(c, box, Pal.BG1, def.accent, p)
        val pg = pages[page]
        // header
        Gfx.text(c, def.title, box.left + p * 6, box.top + p * 6, app.tSmall, def.accent)
        Gfx.text(c, "${page + 1}/${pages.size}", box.right - p * 6, box.top + p * 6, app.tSmall, Pal.GREY, Align.RIGHT)
        val ts = Gfx.fitSize(pg.title, box.width() - p * 12, app.tBody * 1.5f)
        Gfx.text(c, pg.title, box.centerX(), box.top + p * 15, ts, Pal.YELLOW, Align.CENTER)

        val textTop = box.top + p * 20 + PixelFont.height(ts)
        val bottomLimit = box.bottom - p * 34
        val textW = box.width() - p * 14
        val textH = Gfx.paragraphHeight(pg.text, textW, app.tSmall)
        val artSpace = bottomLimit - textTop - textH - p * 8
        val art = pg.art
        var y = textTop
        if (art != null && artSpace > p * 16) {
            val s = minOf(artSpace, box.width() * 0.55f, p * 90)
            val appear = (pageT / 0.25f).coerceIn(0f, 1f)
            val bob = sin(t * 2.2f) * p
            val frame = RectF(box.centerX() - s / 2, y, box.centerX() + s / 2, y + s)
            Gfx.rect(c, frame, Pal.BG0)
            Gfx.frame(c, frame, p, Pal.BG3)
            val inset = p * 4
            art.drawFit(c, RectF(frame.left + inset, frame.top + inset + bob, frame.right - inset, frame.bottom - inset + bob), (255 * appear).toInt())
            y += s + p * 8
        }
        Gfx.paragraph(c, pg.text, box.left + p * 7, y, textW, app.tSmall, Pal.WHITE)

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

    override fun touch(e: MotionEvent): Boolean {
        if (buttons.touch(e)) return true
        gestures.handle(e)
        return true
    }
}
