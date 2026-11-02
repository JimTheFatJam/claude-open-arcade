package com.openarcade.ui

import android.graphics.Canvas
import android.graphics.RectF
import com.openarcade.core.ArcadeApp
import com.openarcade.games.GameDef
import com.openarcade.games.ScoreKind
import com.openarcade.games.formatTime
import com.openarcade.gfx.Align
import com.openarcade.gfx.Gfx
import com.openarcade.gfx.Icons
import com.openarcade.gfx.Pal
import com.openarcade.gfx.PixelFont

/** Short text describing a game's best result, e.g. "BEST 1200" or "BEST 2:31". */
fun bestText(app: ArcadeApp, def: GameDef): String {
    val s = app.storage.stats(def.id)
    return when (def.scoreKind) {
        ScoreKind.HIGH_SCORE -> if (s.best > 0) "BEST ${s.best}" else "NEW!"
        ScoreKind.BEST_TIME -> if (s.bestTime > 0) "BEST ${formatTime(s.bestTime)}" else "NEW!"
        ScoreKind.WINS -> if (s.wins > 0) "WINS ${s.wins}" else "NEW!"
    }
}

/** A cartridge-style card for a game: icon, title and best score. */
class GameCard(val def: GameDef, private val showStats: Boolean = true, onClick: () -> Unit) :
    Button(def.title, def.icon, def.accent, onClick = onClick) {

    override fun draw(c: Canvas, app: ArcadeApp) {
        if (!visible) return
        val p = app.p
        val sink = if (pressed) p else 0f
        val r = RectF(rect.left, rect.top + sink, rect.right, rect.bottom + sink)
        Gfx.panel(c, r, if (pressed) Pal.BG3 else Pal.BG2, Pal.darker(def.accent, 0.1f), p, shadow = !pressed)
        // cartridge label stripe
        Gfx.rect(c, r.left + p * 2, r.top + p * 2, r.right - p * 2, r.top + p * 4, def.accent)
        // very small cards (e.g. stats rows) show only the icon: the title sits beside them
        if (r.height() < p * 32) {
            def.icon.drawFit(c, RectF(r.left + p * 3, r.top + p * 5, r.right - p * 3, r.bottom - p * 3))
            return
        }
        val textArea = if (showStats) PixelFont.height(app.tSmall) * 2 + p * 6 else PixelFont.height(app.tSmall) + p * 4
        val iconBox = RectF(r.left + p * 4, r.top + p * 6, r.right - p * 4, r.bottom - textArea - p)
        def.icon.drawFit(c, iconBox)
        val tsize = Gfx.fitSize(def.title, r.width() - p * 4, app.tSmall)
        var ty = r.bottom - textArea + p
        Gfx.text(c, def.title, r.centerX(), ty, tsize, Pal.WHITE, Align.CENTER)
        if (showStats) {
            ty += PixelFont.height(app.tSmall) + p * 2
            val bt = bestText(app, def)
            val bs = Gfx.fitSize(bt, r.width() - p * 4, app.tSmall * 0.8f)
            Gfx.text(c, bt, r.centerX(), ty, bs, if (bt == "NEW!") Pal.GREEN else Pal.YELLOW, Align.CENTER)
        }
        if (app.storage.isFavorite(def.id)) {
            val s = p * 9
            Icons.STAR.drawFit(c, RectF(r.right - s - p * 2, r.top + p * 5, r.right - p * 2, r.top + p * 5 + s))
        }
        if (app.storage.hasSave(def.id)) {
            val s = p * 7
            Icons.PLAY.drawFit(c, RectF(r.left + p * 3, r.top + p * 5, r.left + p * 3 + s, r.top + p * 5 + s), tint = Pal.GREEN)
        }
    }
}
