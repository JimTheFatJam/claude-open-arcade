package com.openarcade.ui.screens

import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import com.openarcade.core.ArcadeApp
import com.openarcade.games.GameRegistry
import com.openarcade.gfx.Align
import com.openarcade.gfx.Gfx
import com.openarcade.gfx.Pal
import com.openarcade.gfx.PixelFont
import com.openarcade.ui.ButtonSet
import com.openarcade.ui.GameCard
import com.openarcade.ui.Scroller
import com.openarcade.ui.Screen
import com.openarcade.ui.bestText

/** Overall play statistics plus a row per game. Tapping a row opens that game. */
class StatsScreen(app: ArcadeApp) : Screen(app) {
    override val title = "STATISTICS"
    private val scroller = Scroller(app)
    private val rows = ButtonSet(app)
    private var summaryY = 0f
    private var listY = 0f
    private var contentH = 0f
    private var left = 0f
    private var right = 0f
    private var rowW = 0f

    override fun layout() = rebuild()
    override fun resumed() = rebuild()

    private fun rebuild() {
        val p = app.p
        rows.clear()
        val wide = app.wideLayout
        val colW = minOf(content.width() - p * 8, if (wide) p * 470 else p * 220)
        left = (app.w - colW) / 2
        right = left + colW
        var y = content.top + p * 6
        summaryY = y
        y += p * 46
        listY = y
        y += PixelFont.height(app.tBody) + p * 6
        val rh = p * 24
        // per-game rows: two columns on wide screens
        val cols = if (wide) 2 else 1
        val gap = p * 8
        rowW = (colW - gap * (cols - 1)) / cols
        GameRegistry.all.forEachIndexed { i, d ->
            val x = left + (i % cols) * (rowW + gap)
            val yy = y + (i / cols) * (rh + p * 3)
            rows.add(GameCard(d, showStats = false) { app.push(GameScreen(app, d)) }).set(x, yy, x + rh, yy + rh)
        }
        y += ((GameRegistry.all.size + cols - 1) / cols) * (rh + p * 3)
        contentH = y + p * 6 - content.top
        scroller.setContentHeight(contentH, content.height())
    }

    override fun update(dt: Float) = scroller.update(dt)

    override fun draw(c: Canvas) {
        val p = app.p
        c.save()
        c.clipRect(content)
        c.translate(0f, -scroller.offset)
        val all = GameRegistry.all.map { app.storage.stats(it.id) }
        val played = all.sumOf { it.played }
        val wins = all.sumOf { it.wins }
        val secs = all.sumOf { it.totalTime }
        val tried = all.count { it.played > 0 }
        val cells = listOf(
            "ROUNDS" to played.toString(),
            "WINS" to wins.toString(),
            "PLAY TIME" to (if (secs >= 3600) "${secs / 3600}H ${(secs % 3600) / 60}M" else "${secs / 60}M ${secs % 60}S"),
            "GAMES TRIED" to "$tried/${GameRegistry.all.size}",
            "AWARDS" to "${app.storage.unlockedCount()}/${app.progress.allAchievements().size}",
            "FAVORITES" to app.storage.favorites().size.toString(),
        )
        val cols = 3
        val cw = (right - left) / cols
        cells.forEachIndexed { i, (l, v) ->
            val col = i % cols
            val row = i / cols
            val box = RectF(left + col * cw + p, summaryY + row * p * 21, left + (col + 1) * cw - p, summaryY + row * p * 21 + p * 19)
            Gfx.rect(c, box, Pal.withAlpha(Pal.BG1, 220))
            Gfx.frame(c, box, p * 0.5f, Pal.BG3)
            Gfx.text(c, l, box.centerX(), box.top + p * 3, Gfx.fitSize(l, box.width() - p * 2, app.tSmall * 0.8f), Pal.GREY, Align.CENTER, null)
            Gfx.text(c, v, box.centerX(), box.top + p * 10, Gfx.fitSize(v, box.width() - p * 4, app.tBody), Pal.YELLOW, Align.CENTER)
        }
        Gfx.text(c, "PER GAME", left, listY, app.tBody, Pal.CYAN)
        rows.draw(c)
        for (b in rows.buttons) {
            val card = b as GameCard
            val s = app.storage.stats(card.def.id)
            val x = b.rect.right + p * 5
            val r = b.rect
            val right = r.left + rowW
            Gfx.rect(c, RectF(r.right + p * 2, r.top, right, r.bottom), Pal.withAlpha(Pal.BG1, 180))
            Gfx.text(c, card.def.title, x, r.top + p * 4, app.tSmall, Pal.WHITE)
            val line = "PLAYED ${s.played}  WINS ${s.wins}"
            Gfx.text(c, line, x, r.top + p * 7 + PixelFont.height(app.tSmall), Gfx.fitSize(line, right - x - p * 2, app.tSmall * 0.8f), Pal.GREY, shadow = null)
            val bt = bestText(app, card.def)
            Gfx.text(c, bt, right - p * 3, r.top + p * 4, app.tSmall, if (bt == "NEW!") Pal.GREEN else Pal.YELLOW, Align.RIGHT)
        }
        c.restore()
        scroller.drawBar(c, content)
    }

    override fun touch(e: MotionEvent): Boolean {
        if (scroller.touch(e)) { rows.cancel(); return true }
        return rows.touch(e, scroller.offset)
    }
}
