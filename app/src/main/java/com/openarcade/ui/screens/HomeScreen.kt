package com.openarcade.ui.screens

import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import com.openarcade.core.ArcadeApp
import com.openarcade.games.GameDef
import com.openarcade.games.GameRegistry
import com.openarcade.gfx.Align
import com.openarcade.gfx.Gfx
import com.openarcade.gfx.Icons
import com.openarcade.gfx.Pal
import com.openarcade.gfx.PixelFont
import com.openarcade.ui.Button
import com.openarcade.ui.ButtonSet
import com.openarcade.ui.GameCard
import com.openarcade.ui.Screen
import com.openarcade.ui.Scroller
import java.util.Calendar
import kotlin.math.floor
import kotlin.math.sin

/**
 * Main menu: logo, featured games, continue/recent, favourites and navigation. Phones get one
 * scrolling column; tablets and landscape screens get two columns side by side.
 */
class HomeScreen(app: ArcadeApp) : Screen(app) {
    override val title = ""
    override val showBack = false
    private val buttons = ButtonSet(app)
    private val scroller = Scroller(app)
    private var t = 0f
    private val featured: List<GameDef>
    private var featIndex = 0
    private val featBox = RectF()
    private val logoBox = RectF()
    private class Header(val text: String, val x: Float, val y: Float, val w: Float)
    private class Hint(val text: String, val box: RectF)
    private val headers = ArrayList<Header>()
    private val hints = ArrayList<Hint>()
    private var contentH = 0f
    private var footerX = 0f

    init {
        // three featured games that rotate daily, deterministic per day
        val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        val all = GameRegistry.all
        featured = (0 until minOf(3, all.size)).map { all[(day * 5 + it * 7) % all.size] }.distinct()
    }

    override fun layout() = rebuild()
    override fun resumed() = rebuild()

    private fun rebuild() {
        val p = app.p
        buttons.clear(); headers.clear(); hints.clear()
        val gap = p * 4
        val wide = app.wideLayout
        val maxCol = if (wide) p * 250 else p * 260
        val total = minOf(app.w - p * 8, if (wide) maxCol * 2 + p * 10 else maxCol)
        val left = floor((app.w - total) / 2)
        val colGap = p * 10
        val colW = if (wide) (total - colGap) / 2 else total
        val rightX = if (wide) left + colW + colGap else left

        // ---- column A: logo, featured, navigation
        var y = content.top + p * 6
        val logoSize = logoSize(colW)
        val logoH = (PixelFont.height(logoSize) + logoSize * 3) * 2
        y += logoSize * 2   // room for the letters' wave so they never clip under the top bar
        logoBox.set(left, y, left + colW, y + logoH)
        y += logoH + p * 4

        val fh = p * 64
        featBox.set(left, y, left + colW, y + fh)
        val f = featured[featIndex]
        val bw = minOf(p * 44, colW * 0.4f)
        buttons.add(Button("PLAY", Icons.PLAY, Pal.GREEN) { openGame(f) }.also { it.textColor = Pal.BLACK })
            .set(featBox.right - bw - p * 5, featBox.bottom - p * 22, featBox.right - p * 5, featBox.bottom - p * 6)
        if (featured.size > 1) {
            val ab = p * 14
            buttons.add(Button("", Icons.BACK, Pal.BG3) { featIndex = (featIndex + featured.size - 1) % featured.size; rebuild() })
                .set(featBox.left + p * 5, featBox.bottom - p * 21, featBox.left + p * 5 + ab, featBox.bottom - p * 7)
            buttons.add(Button("", Icons.PLAY, Pal.BG3) { featIndex = (featIndex + 1) % featured.size; rebuild() })
                .set(featBox.left + p * 8 + ab, featBox.bottom - p * 21, featBox.left + p * 8 + ab * 2, featBox.bottom - p * 7)
        }
        y = featBox.bottom + p * 8

        val navCols = 2
        val nbw = (colW - gap * (navCols - 1)) / navCols
        val nbh = p * 20
        val nav = listOf(
            Triple("LIBRARY", Icons.LIBRARY, Pal.BLUE) to { app.push(LibraryScreen(app)) },
            Triple("AWARDS", Icons.TROPHY, Pal.ORANGE) to { app.push(AchievementsScreen(app)) },
            Triple("STATS", Icons.CHART, Pal.PURPLE) to { app.push(StatsScreen(app)) },
            Triple("SETTINGS", Icons.GEAR, Pal.DGREY) to { app.push(SettingsScreen(app)) },
        )
        nav.forEachIndexed { i, (info, fn) ->
            val x = left + (i % navCols) * (nbw + gap)
            val yy = y + (i / navCols) * (nbh + gap)
            buttons.add(Button(info.first, info.second, info.third) { fn() }).set(x, yy, x + nbw, yy + nbh)
        }
        y += ((nav.size + navCols - 1) / navCols) * (nbh + gap) + p * 6
        val endA = y

        // ---- column B (or continued column on phones): recent + favourites
        if (wide) y = content.top + p * 6
        val cols = floor((colW + gap) / (p * 50 + gap)).toInt().coerceIn(3, 6)
        val cw = (colW - gap * (cols - 1)) / cols
        val ch = cw * 1.2f
        fun row(header: String, ids: List<String>, empty: String, maxRows: Int) {
            headers.add(Header(header, rightX, y, colW))
            y += PixelFont.height(app.tBody) + p * 5
            val defs = ids.mapNotNull { GameRegistry.byId(it) }.take(cols * maxRows)
            if (defs.isEmpty()) {
                val hh = Gfx.paragraphHeight(empty, colW - p * 8, app.tSmall) + p * 8
                hints.add(Hint(empty, RectF(rightX, y, rightX + colW, y + hh)))
                y += hh + p * 6
            } else {
                defs.forEachIndexed { i, d ->
                    val x = rightX + (i % cols) * (cw + gap)
                    val yy = y + (i / cols) * (ch + gap)
                    buttons.add(GameCard(d) { openGame(d) }).set(x, yy, x + cw, yy + ch)
                }
                y += ((defs.size + cols - 1) / cols) * (ch + gap) + p * 4
            }
        }
        val rows = if (wide) 2 else 1
        row("CONTINUE PLAYING", app.storage.recents(), "Games you play will show up here. Open the library to pick your first game!", rows)
        row("FAVORITES", app.storage.favorites(), "Tap the star on a game's menu to add it to your favorites.", rows)
        y = maxOf(y, endA) + p * 4

        // ---- the full game shelf, spanning the whole content width
        headers.add(Header("ALL GAMES", left, y, total))
        y += PixelFont.height(app.tBody) + p * 5
        val allCols = floor((total + gap) / (p * 50 + gap)).toInt().coerceIn(3, 10)
        val aw = (total - gap * (allCols - 1)) / allCols
        val ah = aw * 1.2f
        GameRegistry.all.forEachIndexed { i, d ->
            val x = left + (i % allCols) * (aw + gap)
            val yy = y + (i / allCols) * (ah + gap)
            buttons.add(GameCard(d) { openGame(d) }).set(x, yy, x + aw, yy + ah)
        }
        y += ((GameRegistry.all.size + allCols - 1) / allCols) * (ah + gap) + p * 4
        footerX = left + total / 2
        contentH = y + p * 10 - content.top
        scroller.setContentHeight(contentH, content.height())
    }

    private fun openGame(d: GameDef) = app.push(GameScreen(app, d))

    /** Logo pixel size: smaller in landscape so the logo doesn't eat a short screen. */
    private fun logoSize(colW: Float) = Gfx.fitSize("ARCADE", colW * 0.75f, if (app.landscape) app.p * 2 else app.p * 3)

    override fun update(dt: Float) {
        t += dt
        scroller.update(dt)
    }

    override fun draw(c: Canvas) {
        val p = app.p
        c.save()
        c.clipRect(content)
        c.translate(0f, -scroller.offset)
        val logoSize = logoSize(logoBox.width())
        val lineH = PixelFont.height(logoSize) + logoSize * 3
        SplashScreen.drawLogo(c, app, logoBox.centerX(), logoBox.top + lineH, 10f + t, logoBox.width() * 0.75f, logoSize)

        // featured panel
        val f = featured[featIndex]
        Gfx.panel(c, featBox, Pal.BG1, f.accent, p)
        val pulse = (sin(t * 3f) + 1f) / 2f
        Gfx.text(c, "${PixelFont.STAR} FEATURED TODAY", featBox.left + p * 6, featBox.top + p * 6, app.tSmall, Pal.mix(Pal.YELLOW, Pal.ORANGE, pulse))
        val iconS = minOf(featBox.height() - p * 40, featBox.width() * 0.35f)
        val iconBox = RectF(featBox.left + p * 6, featBox.top + p * 14, featBox.left + p * 6 + iconS, featBox.top + p * 14 + iconS)
        Gfx.rect(c, iconBox.left - p, iconBox.top - p, iconBox.right + p, iconBox.bottom + p, Pal.BG0)
        f.icon.drawFit(c, iconBox)
        val tx = iconBox.right + p * 6
        val tw = featBox.right - tx - p * 6
        val ts = Gfx.fitSize(f.title, tw, app.tBody * 1.5f)
        Gfx.text(c, f.title, tx, featBox.top + p * 15, ts, Pal.WHITE)
        Gfx.text(c, f.category.label, tx, featBox.top + p * 17 + PixelFont.height(ts), app.tSmall, f.accent)
        // keep the tagline clear of the PLAY button
        Gfx.paragraphClamped(c, f.tagline, tx, featBox.top + p * 21 + PixelFont.height(ts) + PixelFont.height(app.tSmall), tw, app.tSmall,
            Pal.LGREY, featBox.bottom - p * 24)

        for (h in headers) {
            Gfx.text(c, h.text, h.x, h.y, app.tBody, Pal.CYAN)
            Gfx.rect(c, h.x, h.y + PixelFont.height(app.tBody) + p * 2, h.x + h.w, h.y + PixelFont.height(app.tBody) + p * 3, Pal.BG2)
        }
        for (h in hints) {
            Gfx.rect(c, h.box, Pal.withAlpha(Pal.BG1, 200))
            Gfx.frame(c, h.box, p * 0.5f, Pal.BG3)
            Gfx.paragraph(c, h.text, h.box.left + p * 4, h.box.top + p * 4, h.box.width() - p * 8, app.tSmall, Pal.GREY)
        }
        buttons.draw(c)
        Gfx.text(c, "V${com.openarcade.BuildInfo.VERSION}  ${GameRegistry.all.size} GAMES  ${PixelFont.HEART} MORE COMING SOON",
            footerX, content.top + contentH - p * 8, app.tSmall * 0.8f, Pal.DGREY, Align.CENTER, null)
        c.restore()
        scroller.drawBar(c, content)
    }

    override fun touch(e: MotionEvent): Boolean {
        val dragging = scroller.touch(e)
        if (dragging) { buttons.cancel(); return true }
        return buttons.touch(e, scroller.offset)
    }
}
