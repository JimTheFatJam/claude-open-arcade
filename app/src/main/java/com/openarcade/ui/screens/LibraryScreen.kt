package com.openarcade.ui.screens

import android.graphics.Canvas
import android.graphics.RectF
import android.view.KeyEvent
import android.view.MotionEvent
import com.openarcade.audio.Sfx
import com.openarcade.core.ArcadeApp
import com.openarcade.games.Category
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
import kotlin.math.floor

/** Searchable, filterable grid of every game. Search uses an on-screen pixel keyboard. */
class LibraryScreen(app: ArcadeApp) : Screen(app) {
    override val title = "GAME LIBRARY"

    private var query = ""
    /** null = all, "FAV" = favourites, else a [Category] name. */
    private var filter: String? = null
    private var keyboardOpen = false

    private val header = ButtonSet(app)
    private val grid = ButtonSet(app)
    private val keys = ButtonSet(app)
    private val scroller = Scroller(app)
    private val searchBox = RectF()
    private val gridView = RectF()
    private val keyboardBox = RectF()
    private var results: List<GameDef> = emptyList()
    private var gridH = 0f
    private var gridLeft = 0f

    override fun layout() = rebuild()
    override fun resumed() = rebuild()

    private fun rebuild() {
        val p = app.p
        header.clear(); grid.clear(); keys.clear()
        // header (search + chips) stays a comfortable width; the card grid may use more room
        val headW = minOf(app.w - p * 8, p * 380)
        val left = floor((app.w - headW) / 2)
        val right = left + headW
        val gridW = minOf(app.w - p * 8, p * 600)
        val gLeft = floor((app.w - gridW) / 2)
        val gRight = gLeft + gridW
        var y = content.top + p * 4

        // search field
        val sh = p * 16
        searchBox.set(left, y, right - (if (query.isNotEmpty()) sh + p * 3 else 0f), y + sh)
        header.add(Button("", null, Pal.BG1, Sfx.CLICK) { keyboardOpen = !keyboardOpen; rebuild() }).set(searchBox.left, searchBox.top, searchBox.right, searchBox.bottom)
        if (query.isNotEmpty()) {
            header.add(Button("", Icons.CLOSE, Pal.RED, Sfx.BACK) { query = ""; rebuild() }).set(right - sh, y, right, y + sh)
        }
        y += sh + p * 4

        // category chips
        val chips = ArrayList<Pair<String, String?>>()
        chips.add("ALL" to null)
        for (cat in Category.values()) chips.add(cat.label to cat.name)
        chips.add("${PixelFont.STAR}FAV" to "FAV")
        val gap = p * 2
        val cw = (right - left - gap * (chips.size - 1)) / chips.size
        val chH = p * 14
        chips.forEachIndexed { i, (label, key) ->
            val x = left + i * (cw + gap)
            val b = Button(label, null, Pal.BG2, Sfx.CLICK) { filter = key; scroller.offset = 0f; rebuild() }
            b.active = filter == key
            b.color = if (b.active) Pal.BLUE else Pal.BG2
            b.textSize = app.tSmall
            header.add(b).set(x, y, x + cw, y + chH)
        }
        y += chH + p * 4

        // keyboard
        val kbH = if (keyboardOpen) keyboardHeight() else 0f
        keyboardBox.set(0f, app.h - kbH, app.w, app.h)
        gridView.set(0f, y, app.w, app.h - kbH)

        // results
        val q = query.trim().lowercase()
        results = GameRegistry.all.filter { d ->
            val okFilter = when (filter) {
                null -> true
                "FAV" -> app.storage.isFavorite(d.id)
                else -> d.category.name == filter
            }
            okFilter && (q.isEmpty() || d.title.lowercase().contains(q) || d.tagline.lowercase().contains(q) ||
                d.category.label.lowercase().contains(q))
        }
        val cols = floor((gRight - gLeft) / (p * 50)).toInt().coerceIn(3, 10)
        val ggap = p * 4
        val cardW = (gRight - gLeft - ggap * (cols - 1)) / cols
        val cardH = cardW * 1.22f
        var gy = gridView.top + p * 12
        results.forEachIndexed { i, d ->
            val col = i % cols
            val row = i / cols
            val x = gLeft + col * (cardW + ggap)
            val yy = gy + row * (cardH + ggap)
            grid.add(GameCard(d) { app.push(GameScreen(app, d)) }).set(x, yy, x + cardW, yy + cardH)
        }
        val rows = (results.size + cols - 1) / cols
        gy += rows * (cardH + ggap) + p * 20
        gridH = gy - gridView.top
        scroller.setContentHeight(gridH, gridView.height())
        gridLeft = gLeft

        if (keyboardOpen) buildKeyboard()
    }

    private fun keyboardHeight(): Float {
        val rows = 5
        val kh = if (app.landscape) app.p * 12 else app.p * 16
        return rows * (kh + app.p * 2) + app.p * 6
    }

    private fun buildKeyboard() {
        val p = app.p
        val rows = listOf("1234567890", "QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM")
        val kh = if (app.landscape) p * 12 else p * 16
        val gap = p * 2
        val maxW = minOf(app.w - p * 8, p * 220)
        val kw = (maxW - gap * 9) / 10
        var y = keyboardBox.top + p * 4
        for (row in rows) {
            val rw = row.length * kw + (row.length - 1) * gap
            var x = (app.w - rw) / 2
            for (ch in row) {
                val b = Button(ch.toString(), null, Pal.BG3, Sfx.CLICK) { type(ch) }
                b.textSize = app.tBody
                keys.add(b).set(x, y, x + kw, y + kh)
                x += kw + gap
            }
            y += kh + gap
        }
        val x0 = (app.w - maxW) / 2
        val bw3 = (maxW - gap * 2) / 3
        keys.add(Button("DEL", Icons.BACK, Pal.RED, Sfx.BACK) { if (query.isNotEmpty()) { query = query.dropLast(1); rebuild() } })
            .set(x0, y, x0 + bw3, y + kh)
        keys.add(Button("SPACE", null, Pal.BG3, Sfx.CLICK) { type(' ') }).set(x0 + bw3 + gap, y, x0 + bw3 * 2 + gap, y + kh)
        keys.add(Button("DONE", Icons.CHECK, Pal.GREEN) { keyboardOpen = false; rebuild() })
            .set(x0 + (bw3 + gap) * 2, y, x0 + maxW, y + kh)
    }

    private fun type(ch: Char) {
        if (query.length >= 16) return
        query += ch
        scroller.offset = 0f
        rebuild()
    }

    override fun update(dt: Float) = scroller.update(dt)

    override fun draw(c: Canvas) {
        val p = app.p
        // grid
        c.save()
        c.clipRect(gridView)
        c.translate(0f, -scroller.offset)
        val countText = when {
            results.isEmpty() -> ""
            else -> "${results.size} GAME" + (if (results.size == 1) "" else "S")
        }
        Gfx.text(c, countText, gridLeft, gridView.top + p * 3, app.tSmall, Pal.GREY)
        grid.draw(c)
        if (results.isEmpty()) {
            val msg = if (filter == "FAV" && query.isEmpty()) "No favorites yet. Open a game and tap its star to add it here."
            else "No games match \"$query\"."
            Gfx.paragraph(c, msg, p * 10, gridView.top + p * 20, app.w - p * 20, app.tSmall, Pal.LGREY, Align.CENTER)
        } else {
            Gfx.text(c, "MORE GAMES COMING SOON!", app.w / 2, gridView.top + gridH - p * 14, app.tSmall, Pal.DGREY, Align.CENTER, null)
        }
        c.restore()
        scroller.drawBar(c, gridView)

        header.draw(c)
        // search text drawn over the search button
        val ic = p * 9
        Icons.SEARCH.drawFit(c, RectF(searchBox.left + p * 4, searchBox.centerY() - ic / 2, searchBox.left + p * 4 + ic, searchBox.centerY() + ic / 2), tint = Pal.GREY)
        val tx = searchBox.left + p * 6 + ic
        if (query.isEmpty()) Gfx.textMid(c, "SEARCH GAMES...", tx, searchBox.centerY(), app.tSmall, Pal.GREY, Align.LEFT, null)
        else Gfx.textMid(c, query, tx, searchBox.centerY(), app.tBody, Pal.WHITE, Align.LEFT)
        if (keyboardOpen && app.blink(1.5f) > 0.5f) {
            val cx = tx + (if (query.isEmpty()) 0f else PixelFont.width(query, app.tBody) + p * 2)
            Gfx.rect(c, cx, searchBox.centerY() - p * 4, cx + p * 2, searchBox.centerY() + p * 4, Pal.YELLOW)
        }

        if (keyboardOpen) {
            Gfx.rect(c, keyboardBox, Pal.BG1)
            Gfx.rect(c, keyboardBox.left, keyboardBox.top, keyboardBox.right, keyboardBox.top + p, Pal.CYAN)
            keys.draw(c)
        }
    }

    override fun touch(e: MotionEvent): Boolean {
        if (keyboardOpen && (keyboardBox.contains(e.x, e.y) || e.actionMasked != MotionEvent.ACTION_DOWN && keys.buttons.any { it.pressed })) {
            return keys.touch(e)
        }
        if (header.touch(e)) return true
        if (e.y >= gridView.top || e.actionMasked != MotionEvent.ACTION_DOWN) {
            if (scroller.touch(e)) { grid.cancel(); return true }
            return grid.touch(e, scroller.offset)
        }
        return false
    }

    override fun back(): Boolean {
        if (keyboardOpen) { keyboardOpen = false; rebuild(); return true }
        return false
    }

    override fun key(keyCode: Int): Boolean {
        if (keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) { type('A' + (keyCode - KeyEvent.KEYCODE_A)); return true }
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) { type('0' + (keyCode - KeyEvent.KEYCODE_0)); return true }
        if (keyCode == KeyEvent.KEYCODE_SPACE) { type(' '); return true }
        if (keyCode == KeyEvent.KEYCODE_DEL && query.isNotEmpty()) { query = query.dropLast(1); rebuild(); return true }
        return false
    }
}
