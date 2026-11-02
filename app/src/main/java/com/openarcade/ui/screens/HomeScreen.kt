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

/** Main menu: logo, featured games, continue/recent, favourites and navigation. */
class HomeScreen(app: ArcadeApp) : Screen(app) {
    override val title = ""
    override val showBack = false
    private val buttons = ButtonSet(app)
    private val scroller = Scroller(app)
    private var t = 0f
    private var logoY = 0f
    private val featured: List<GameDef>
    private var featIndex = 0
    private val featBox = RectF()
    private var sections = ArrayList<Pair<String, Float>>()   // header text, y
    private var hints = ArrayList<Triple<String, Float, Float>>() // text, y, height
    private var contentH = 0f

    init {
        // three featured games that rotate daily, deterministic per day
        val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        val all = GameRegistry.all
        featured = (0 until minOf(3, all.size)).map { all[(day * 5 + it * 7) % all.size] }.distinct()
    }

    override fun layout() = rebuild()
    override fun resumed() = rebuild()

    private fun cardsPerRow(): Int = floor((app.w - app.p * 8) / (app.p * 50)).toInt().coerceIn(3, 8)

    private fun rebuild() {
        val p = app.p
        buttons.clear(); sections.clear(); hints.clear()
        val left = p * 4
        val right = app.w - p * 4
        val width = right - left
        var y = content.top + p * 6
        logoY = y
        val logoSize = Gfx.fitSize("ARCADE", width * 0.7f, p * 3)
        y += (PixelFont.height(logoSize) + logoSize * 3) * 2 + p * 4

        // featured
        val fh = if (app.landscape) p * 58 else p * 66
        featBox.set(left, y, right, y + fh)
        val f = featured[featIndex]
        val bw = p * 44
        buttons.add(Button("PLAY", Icons.PLAY, Pal.GREEN) { openGame(f) }.also { it.textColor = Pal.BLACK })
            .set(right - bw - p * 5, featBox.bottom - p * 22, right - p * 5, featBox.bottom - p * 6)
        if (featured.size > 1) {
            val ab = p * 14
            buttons.add(Button("", Icons.BACK, Pal.BG3) { featIndex = (featIndex + featured.size - 1) % featured.size; rebuild() })
                .set(featBox.left + p * 5, featBox.bottom - p * 21, featBox.left + p * 5 + ab, featBox.bottom - p * 7)
            buttons.add(Button("", Icons.PLAY, Pal.BG3) { featIndex = (featIndex + 1) % featured.size; rebuild() })
                .set(featBox.left + p * 8 + ab, featBox.bottom - p * 21, featBox.left + p * 8 + ab * 2, featBox.bottom - p * 7)
        }
        y = featBox.bottom + p * 8

        // navigation
        val gap = p * 4
        val navCols = if (app.landscape) 4 else 2
        val nbw = (width - gap * (navCols - 1)) / navCols
        val nbh = p * 20
        val nav = listOf(
            Triple("LIBRARY", Icons.LIBRARY, Pal.BLUE) to { app.push(LibraryScreen(app)) },
            Triple("AWARDS", Icons.TROPHY, Pal.ORANGE) to { app.push(AchievementsScreen(app)) },
            Triple("STATS", Icons.CHART, Pal.PURPLE) to { app.push(StatsScreen(app)) },
            Triple("SETTINGS", Icons.GEAR, Pal.DGREY) to { app.push(SettingsScreen(app)) },
        )
        nav.forEachIndexed { i, (info, fn) ->
            val col = i % navCols
            val row = i / navCols
            val x = left + col * (nbw + gap)
            val yy = y + row * (nbh + gap)
            buttons.add(Button(info.first, info.second, info.third) { fn() }).set(x, yy, x + nbw, yy + nbh)
        }
        y += ((nav.size + navCols - 1) / navCols) * (nbh + gap) + p * 6

        // recent + favourites rows
        val cols = cardsPerRow()
        val cw = (width - gap * (cols - 1)) / cols
        val ch = cw * 1.2f
        fun row(header: String, ids: List<String>, empty: String) {
            sections.add(header to y)
            y += PixelFont.height(app.tBody) + p * 5
            val defs = ids.mapNotNull { GameRegistry.byId(it) }.take(cols)
            if (defs.isEmpty()) {
                val hh = Gfx.paragraphHeight(empty, width - p * 8, app.tSmall) + p * 8
                hints.add(Triple(empty, y, hh))
                y += hh + p * 6
            } else {
                defs.forEachIndexed { i, d ->
                    val x = left + i * (cw + gap)
                    buttons.add(GameCard(d) { openGame(d) }).set(x, y, x + cw, y + ch)
                }
                y += ch + p * 8
            }
        }
        row("CONTINUE PLAYING", app.storage.recents(), "Games you play will show up here. Open the library to pick your first game!")
        row("FAVORITES", app.storage.favorites(), "Tap the star on a game's menu to add it to your favorites.")
        y += p * 4
        contentH = y - content.top
        scroller.setContentHeight(contentH, content.height())
    }

    private fun openGame(d: GameDef) = app.push(GameScreen(app, d))

    override fun update(dt: Float) {
        t += dt
        scroller.update(dt)
    }

    override fun draw(c: Canvas) {
        val p = app.p
        c.save()
        c.clipRect(content)
        c.translate(0f, -scroller.offset)
        val logoW = (app.w - p * 8) * 0.7f
        val logoSize = Gfx.fitSize("ARCADE", logoW, p * 3)
        val lineH = PixelFont.height(logoSize) + logoSize * 3
        SplashScreen.drawLogo(c, app, app.w / 2, logoY + lineH, 10f + t, logoW)

        // featured panel
        val f = featured[featIndex]
        Gfx.panel(c, featBox, Pal.BG1, f.accent, p)
        val pulse = (sin(t * 3f) + 1f) / 2f
        Gfx.text(c, "${com.openarcade.gfx.PixelFont.STAR} FEATURED TODAY", featBox.left + p * 6, featBox.top + p * 6, app.tSmall, Pal.mix(Pal.YELLOW, Pal.ORANGE, pulse))
        val iconS = featBox.height() - p * 40
        val iconBox = RectF(featBox.left + p * 6, featBox.top + p * 14, featBox.left + p * 6 + iconS, featBox.top + p * 14 + iconS)
        Gfx.rect(c, iconBox.left - p, iconBox.top - p, iconBox.right + p, iconBox.bottom + p, Pal.BG0)
        f.icon.drawFit(c, iconBox)
        val tx = iconBox.right + p * 6
        val tw = featBox.right - tx - p * 6
        val ts = Gfx.fitSize(f.title, tw, app.tBody * 1.5f)
        Gfx.text(c, f.title, tx, featBox.top + p * 15, ts, Pal.WHITE)
        Gfx.text(c, f.category.label, tx, featBox.top + p * 17 + PixelFont.height(ts), app.tSmall, f.accent)
        Gfx.paragraph(c, f.tagline, tx, featBox.top + p * 21 + PixelFont.height(ts) + PixelFont.height(app.tSmall), tw, app.tSmall, Pal.LGREY)

        for ((text, y) in sections) {
            Gfx.text(c, text, p * 4, y, app.tBody, Pal.CYAN)
            Gfx.rect(c, p * 4, y + PixelFont.height(app.tBody) + p * 2, app.w - p * 4, y + PixelFont.height(app.tBody) + p * 3, Pal.BG2)
        }
        for ((text, y, hh) in hints) {
            val box = RectF(p * 4, y, app.w - p * 4, y + hh)
            Gfx.rect(c, box, Pal.withAlpha(Pal.BG1, 200))
            Gfx.frame(c, box, p * 0.5f, Pal.BG3)
            Gfx.paragraph(c, text, box.left + p * 4, box.top + p * 4, box.width() - p * 8, app.tSmall, Pal.GREY)
        }
        buttons.draw(c)
        Gfx.text(c, "V1.0  ${GameRegistry.all.size} GAMES  ${com.openarcade.gfx.PixelFont.HEART} MORE COMING SOON",
            app.w / 2, content.top + contentH - p * 6, app.tSmall * 0.8f, Pal.DGREY, Align.CENTER, null)
        c.restore()
        scroller.drawBar(c, content)
    }

    override fun touch(e: MotionEvent): Boolean {
        val dragging = scroller.touch(e)
        if (dragging) { buttons.cancel(); return true }
        return buttons.touch(e, scroller.offset)
    }
}
