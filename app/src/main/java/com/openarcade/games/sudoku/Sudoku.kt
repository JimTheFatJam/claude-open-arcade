package com.openarcade.games.sudoku

import android.graphics.Canvas
import android.graphics.RectF
import android.view.KeyEvent
import android.view.MotionEvent
import com.openarcade.audio.Music
import com.openarcade.audio.Sfx
import com.openarcade.games.AchievementDef
import com.openarcade.games.Category
import com.openarcade.games.Difficulty
import com.openarcade.games.GameAction
import com.openarcade.games.GameDef
import com.openarcade.games.GameHost
import com.openarcade.games.GameSession
import com.openarcade.games.HudItem
import com.openarcade.games.ScoreKind
import com.openarcade.games.TutorialPage
import com.openarcade.games.Worker
import com.openarcade.games.drawBoardFrame
import com.openarcade.games.formatTime
import com.openarcade.games.toIntArray
import com.openarcade.games.toJsonArray
import com.openarcade.gfx.Align
import com.openarcade.gfx.Gfx
import com.openarcade.gfx.Icons
import com.openarcade.gfx.Pal
import com.openarcade.gfx.PixelArt
import com.openarcade.gfx.PixelFont
import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.sin

/** Classic 9x9 Sudoku. Clue counts per difficulty. */
object SudokuStyle {
    fun dims(): Triple<Int, Int, Int> = Triple(9, 3, 3)

    fun clues(d: Difficulty): Int = when (d) {
        Difficulty.EASY -> 38
        Difficulty.NORMAL -> 30
        Difficulty.HARD -> 24
    }
}

class SudokuSession(host: GameHost) : GameSession(host) {
    private val style = SudokuStyle
    private val dims = style.dims()
    private val n = dims.first
    private val core = SudokuCore(n, dims.second, dims.third)
    private var given = IntArray(n * n)
    private var solution = IntArray(n * n)
    private var cells = IntArray(n * n)
    private var notes = IntArray(n * n)
    private var selected = -1
    private var notesMode = false
    private var mistakes = 0
    private var hints = 0
    private val history = ArrayList<IntArray>() // [index, oldValue, oldNotes]
    private val worker = Worker<Pair<IntArray, IntArray>>()
    private var ready = false
    private var t = 0f
    private val flash = HashMap<Int, Float>()     // cell -> time since a unit was completed

    // layout
    private var cell = 0f
    private var ox = 0f
    private var oy = 0f
    private val padRects = ArrayList<RectF>()

    override fun start(saved: JSONObject?) {
        if (saved != null) {
            given = saved.getJSONArray("given").toIntArray()
            solution = saved.getJSONArray("solution").toIntArray()
            cells = saved.getJSONArray("cells").toIntArray()
            notes = saved.getJSONArray("notes").toIntArray()
            mistakes = saved.optInt("mistakes"); hints = saved.optInt("hints")
            ready = true
        } else {
            val seed = host.rng.nextLong()
            val clues = style.clues(host.difficulty)
            worker.run {
                val rnd = kotlin.random.Random(seed)
                val sol = core.randomSolution(rnd)
                sol to core.makePuzzle(sol, clues, rnd)
            }
        }
    }

    override fun save(): JSONObject? = if (!ready) null else JSONObject()
        .put("given", given.toJsonArray()).put("solution", solution.toJsonArray())
        .put("cells", cells.toJsonArray()).put("notes", notes.toJsonArray())
        .put("mistakes", mistakes).put("hints", hints)

    override fun onLayout() {
        val p = px
        val side = area.width() > area.height() * 1.15f
        padRects.clear()
        if (side) {
            val padCols = if (n == 9) 3 else 2
            val padRows = (n + padCols - 1) / padCols
            // board takes the height; pad to its right
            cell = floor(minOf((area.height() - p * 4) / n, (area.width() - p * 10) / (n + padCols * 1.1f)))
            val boardW = cell * n
            val padCell = cell * 1.05f
            val total = boardW + p * 6 + padCols * padCell
            ox = floor(area.centerX() - total / 2)
            oy = floor(area.centerY() - boardW / 2)
            val px0 = ox + boardW + p * 6
            val py0 = area.centerY() - padRows * padCell / 2
            for (v in 0 until n) {
                val c = v % padCols; val r = v / padCols
                padRects.add(RectF(px0 + c * padCell + p, py0 + r * padCell + p, px0 + (c + 1) * padCell - p, py0 + (r + 1) * padCell - p))
            }
        } else {
            val padRowsCount = if (n == 9) 1 else 1
            cell = floor(minOf((area.width() - p * 4) / n, (area.height() - p * 10) / (n + 1.4f * padRowsCount)))
            val boardW = cell * n
            val padCell = minOf(cell * 1.2f, (area.width() - p * 4) / n)
            val total = boardW + p * 6 + padCell
            ox = floor(area.centerX() - boardW / 2)
            oy = floor(area.centerY() - total / 2)
            val py0 = oy + boardW + p * 6
            val px0 = area.centerX() - padCell * n / 2
            for (v in 0 until n) padRects.add(RectF(px0 + v * padCell + p, py0, px0 + (v + 1) * padCell - p, py0 + padCell - p))
        }
    }

    override fun update(dt: Float) {
        t += dt
        if (!ready) {
            val r = worker.take()
            if (r != null) {
                solution = r.first; given = r.second; cells = given.copyOf(); notes = IntArray(n * n)
                ready = true
            }
        }
        if (flash.isNotEmpty()) {
            val it = flash.entries.iterator()
            while (it.hasNext()) { val e = it.next(); e.setValue(e.value + dt); if (e.value > 0.6f) it.remove() }
        }
    }

    private fun place(v: Int) {
        val i = selected
        if (!ready || finished || i < 0 || given[i] != 0) { if (i >= 0 && given[i] != 0) host.sfx(Sfx.ERROR); return }
        if (notesMode && v != 0) {
            if (cells[i] != 0) return
            history.add(intArrayOf(i, cells[i], notes[i]))
            notes[i] = notes[i] xor (1 shl (v - 1))
            host.sfx(Sfx.TICK)
            return
        }
        if (cells[i] == v) return
        history.add(intArrayOf(i, cells[i], notes[i]))
        cells[i] = v
        notes[i] = 0
        if (v == 0) { host.sfx(Sfx.BACK); return }
        if (v != solution[i]) {
            mistakes++
            host.sfx(Sfx.ERROR)
            host.vibrate(40)
            return
        }
        // correct: clear this value from peer notes
        for (pe in core.peersOf(i)) notes[pe] = notes[pe] and (1 shl (v - 1)).inv()
        host.sfx(Sfx.PLACE)
        checkUnits(i)
        if (cells.contentEquals(solution)) {
            host.unlock("first_win")
            if (host.difficulty == Difficulty.HARD) host.unlock("hard_win")
            if (hints == 0 && mistakes == 0) host.unlock("purist")
            win(detail = "MISTAKES $mistakes  HINTS $hints", delay = 1.4f)
            for (k in cells.indices) flash[k] = -(k % n + k / n) * 0.04f
        }
    }

    private fun checkUnits(i: Int) {
        val r = i / n; val c = i % n; val b = core.box(i)
        val units = listOf(
            (0 until n).map { r * n + it },
            (0 until n).map { it * n + c },
            (0 until n * n).filter { core.box(it) == b },
        )
        var any = false
        for (u in units) if (u.all { cells[it] == solution[it] }) { any = true; u.forEachIndexed { k, idx -> flash[idx] = -k * 0.04f } }
        if (any) host.sfx(Sfx.LINE)
    }

    private fun hint() {
        if (!ready || finished) return
        var i = selected
        if (i < 0 || cells[i] == solution[i]) {
            val wrongOrEmpty = cells.indices.filter { cells[it] != solution[it] }
            if (wrongOrEmpty.isEmpty()) return
            i = wrongOrEmpty[host.rng.nextInt(wrongOrEmpty.size)]
            selected = i
        }
        hints++
        val wasNotes = notesMode
        notesMode = false
        place(solution[i])
        notesMode = wasNotes
    }

    private fun undo() {
        val h = history.removeLastOrNull() ?: return
        cells[h[0]] = h[1]; notes[h[0]] = h[2]
        selected = h[0]
        host.sfx(Sfx.BACK)
    }

    override fun onTouch(e: MotionEvent) {
        if (e.actionMasked != MotionEvent.ACTION_DOWN || !ready) return
        val x = e.x; val y = e.y
        if (x >= ox && y >= oy && x < ox + cell * n && y < oy + cell * n) {
            selected = ((y - oy) / cell).toInt() * n + ((x - ox) / cell).toInt()
            host.sfx(Sfx.CLICK)
            return
        }
        padRects.forEachIndexed { k, r -> if (r.contains(x, y)) { place(k + 1); return } }
    }

    override fun onKey(keyCode: Int): Boolean {
        if (keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
            val v = keyCode - KeyEvent.KEYCODE_0
            if (v <= n) place(v)
            return true
        }
        if (selected < 0) selected = 0
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (selected % n > 0) selected--
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (selected % n < n - 1) selected++
            KeyEvent.KEYCODE_DPAD_UP -> if (selected >= n) selected -= n
            KeyEvent.KEYCODE_DPAD_DOWN -> if (selected < n * n - n) selected += n
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_0 -> place(0)
            KeyEvent.KEYCODE_N -> notesMode = !notesMode
            else -> return false
        }
        return true
    }

    override fun hud() = listOf(
        HudItem("TIME", formatTime(host.playTime.toInt())),
        HudItem("MISTAKES", mistakes.toString(), if (mistakes > 0) Pal.RED else Pal.WHITE),
        HudItem("HINTS", hints.toString(), Pal.YELLOW),
    )

    override fun actions() = listOf(
        GameAction("NOTES", Icons.PENCIL, isActive = { notesMode }) { notesMode = !notesMode },
        GameAction("ERASE", Icons.CLOSE) { place(0) },
        GameAction("HINT", Icons.HINT) { hint() },
        GameAction("UNDO", Icons.UNDO, isEnabled = { history.isNotEmpty() }) { undo() },
    )

    override fun draw(c: Canvas) {
        val p = px
        if (!ready) {
            val dots = ".".repeat(1 + (t * 3).toInt() % 3)
            Gfx.text(c, "GENERATING$dots", area.centerX(), area.centerY(), host.px, Pal.YELLOW, Align.CENTER)
            return
        }
        val boardR = RectF(ox, oy, ox + cell * n, oy + cell * n)
        val accent = Pal.CYAN
        drawBoardFrame(c, boardR, p, accent)
        val sel = selected
        val selVal = if (sel >= 0) cells[sel] else 0
        val r = RectF()
        for (i in 0 until n * n) {
            val cx = i % n; val cy = i / n
            r.set(ox + cx * cell, oy + cy * cell, ox + (cx + 1) * cell, oy + (cy + 1) * cell)
            val boxShade = core.box(i) % 2 == 0
            var bg = if (boxShade) Pal.BG1 else Pal.BG0
            if (sel >= 0) {
                if (i == sel) bg = Pal.mix(accent, Pal.BG1, 0.45f)
                else if (cx == sel % n || cy == sel / n || core.box(i) == core.box(sel)) bg = Pal.mix(bg, accent, 0.18f)
                else if (selVal != 0 && cells[i] == selVal) bg = Pal.mix(bg, Pal.YELLOW, 0.25f)
            }
            flash[i]?.let { f -> if (f >= 0f) bg = Pal.mix(Pal.YELLOW, bg, (f / 0.6f).coerceIn(0f, 1f)) }
            Gfx.rect(c, r, bg)
            val v = cells[i]
            if (v != 0) {
                val wrong = v != solution[i]
                val size = (cell * 0.6f / PixelFont.GLYPH_H)
                val col = when { given[i] != 0 -> Pal.WHITE; wrong -> Pal.RED; else -> Pal.CYAN }
                Gfx.textMid(c, v.toString(), r.centerX(), r.centerY(), size, col)
            } else if (notes[i] != 0) {
                val sub = if (n == 4) 2 else 3
                val sc = cell / sub
                for (k in 0 until n) {
                    if (notes[i] and (1 shl k) == 0) continue
                    val nx = r.left + (k % sub) * sc
                    val ny = r.top + (k / sub) * sc
                    val s = maxOf(1f, sc * 0.55f / PixelFont.GLYPH_H)
                    Gfx.textMid(c, (k + 1).toString(), nx + sc / 2, ny + sc / 2, s, Pal.GREY, shadow = null)
                }
            }
        }
        // grid lines
        val thin = maxOf(1f, p / 3)
        for (k in 1 until n) {
            val thickV = k % dims.third == 0
            val thickH = k % dims.second == 0
            Gfx.rect(c, ox + k * cell - (if (thickV) p / 2 else thin / 2), oy, ox + k * cell + (if (thickV) p / 2 else thin / 2), oy + cell * n, if (thickV) Pal.darker(accent, 0.2f) else Pal.BG3)
            Gfx.rect(c, ox, oy + k * cell - (if (thickH) p / 2 else thin / 2), ox + cell * n, oy + k * cell + (if (thickH) p / 2 else thin / 2), if (thickH) Pal.darker(accent, 0.2f) else Pal.BG3)
        }
        if (sel >= 0) Gfx.frame(c, RectF(ox + (sel % n) * cell, oy + (sel / n) * cell, ox + (sel % n + 1) * cell, oy + (sel / n + 1) * cell), maxOf(1f, p * 0.7f), Pal.YELLOW)

        // number pad
        for ((k, pr) in padRects.withIndex()) {
            val v = k + 1
            val remaining = n - cells.indices.count { cells[it] == v && solution[it] == v }
            val done = remaining == 0
            Gfx.bevel(c, pr, if (done) Pal.BG1 else if (notesMode) Pal.DGREY else Pal.BG3, maxOf(1f, p / 2))
            val s = pr.height() * 0.5f / PixelFont.GLYPH_H
            Gfx.textMid(c, v.toString(), pr.centerX(), pr.centerY(), s, if (done) Pal.DGREY else if (notesMode) Pal.LGREY else Pal.WHITE)
            if (!done && remaining < n) {
                val s = maxOf(1f, pr.height() * 0.16f / PixelFont.GLYPH_H)
                Gfx.text(c, remaining.toString(), pr.right - p, pr.top + p, s, Pal.YELLOW, Align.RIGHT, null)
            }
        }
    }

}

val SudokuGame = GameDef(
    id = "sudoku",
    title = "SUDOKU",
    category = Category.PUZZLE,
    tagline = "Fill the grid so every row, column and box has 1 to 9.",
    description = "The timeless number-placement puzzle. Every puzzle is freshly generated on your device and has exactly one solution. " +
        "Use notes to track candidates, and hints if you get stuck.",
    icon = PixelArt(
        "................",
        ".CCCCCCCCCCCCCC.",
        ".CkkkkCkkkkCkkC.",
        ".CkWkkCkkkkCkWC.",
        ".CkkkkCkWWkCkkC.",
        ".CCCCCCCCCCCCCC.",
        ".CkkkkCkkkkCkkC.",
        ".CkkWkCkkkkCkkC.",
        ".CkkkkCkkYkCWkC.",
        ".CCCCCCCCCCCCCC.",
        ".CkWkkCkkkkCkkC.",
        ".CkkkkCkWkkCkkC.",
        ".CkkkkCkkkkCkWC.",
        ".CCCCCCCCCCCCCC.",
        "................",
        "................",
    ),
    accent = Pal.CYAN,
    music = Music.PUZZLE,
    scoreKind = ScoreKind.BEST_TIME,
    tutorial = listOf(
        TutorialPage("OBJECTIVE", "Fill every empty square with a number from 1 to 9. Each ROW, each COLUMN and each 3x3 BOX must contain every number exactly once.",
            PixelArt(
                "CCCCCCCCC",
                "CkkCkkCkC",
                "CkWCkkCkC",
                "CCCCCCCCC",
                "CkkCYkCkC",
                "CkkCkkCWC",
                "CCCCCCCCC",
            )),
        TutorialPage("CONTROLS", "TAP a square to select it, then tap a number on the pad to place it. ERASE clears a square and UNDO takes back your last change.", Icons.TAP),
        TutorialPage("NOTES", "Turn on NOTES to pencil in small candidate numbers. When you place a correct number, matching notes in its row, column and box are cleared for you.", Icons.PENCIL),
        TutorialPage("MISTAKES & HINTS", "A wrong number turns red and counts as a mistake. HINT fills in the selected square (or a random one). Highlighted squares show the row, column and box of your selection.", Icons.HINT),
        TutorialPage("TIPS", "Look for a number that can only go in one place in a box. Scan rows and columns that already have many numbers. The small yellow digit on the pad shows how many of each number are still missing.", Icons.BULB),
    ),
    achievements = listOf(
        AchievementDef("first_win", "NUMBER CRUNCHER", "Solve a Sudoku."),
        AchievementDef("hard_win", "GRAND SOLVER", "Solve a HARD Sudoku."),
        AchievementDef("purist", "PURIST", "Solve without hints or mistakes."),
    ),
    factory = { SudokuSession(it) },
)
