package com.openarcade.games.meowdoku

import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
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
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/** Pixel art shared by the game and its interactive tutorial. */
object MeowArt {
    private val CAT_TEMPLATE = listOf(
        "KK.......KK",
        "KpK.....KpK",
        "KooKKKKKooK",
        "KoosososooK",
        "KoooooooooK",
        "KoEEoooEEoK",
        "KoEKoooEKoK",
        "KooooNooooK",
        "KoooKoKoooK",
        ".KoooooooK.",
        "..KKKKKKK..",
    )

    private fun cat(fur: Int, stripe: Int, eye: Int) = PixelArt(
        CAT_TEMPLATE,
        mapOf('K' to Pal.BLACK, 'p' to Pal.PINK, 'o' to fur, 's' to stripe, 'E' to eye, 'N' to Pal.PINK),
    )

    /** One cat breed per region, so every region's cat looks different. */
    val CATS = listOf(
        cat(Pal.ORANGE, Pal.darker(Pal.ORANGE, 0.35f), Pal.GREEN),   // ginger
        cat(Pal.WHITE, Pal.LGREY, Pal.CYAN),                          // snow
        cat(0xFF3A3A58.toInt(), 0xFF3A3A58.toInt(), Pal.YELLOW),      // shadow
        cat(Pal.GREY, Pal.DGREY, Pal.ORANGE),                         // smoky
        cat(Pal.WHITE, Pal.ORANGE, Pal.GREEN),                        // calico
        cat(Pal.PEACH, Pal.DBROWN, Pal.BLUE),                         // siamese
        cat(0xFF2A2A40.toInt(), Pal.WHITE, Pal.GREEN),                // tuxedo
        cat(Pal.BROWN, Pal.DBROWN, Pal.YELLOW),                       // cocoa
        cat(Pal.PURPLE, Pal.PINK, Pal.YELLOW),                        // cosmo
    )

    /** Region fill colours: bright arcade hues darkened so cats and marks stay readable. */
    val REGION_COLORS = intArrayOf(Pal.RED, Pal.ORANGE, Pal.YELLOW, Pal.GREEN, Pal.CYAN, Pal.BLUE, Pal.PURPLE, Pal.PINK, Pal.TAN)

    fun regionFill(g: Int, lit: Boolean = false): Int =
        Pal.mix(REGION_COLORS[g % REGION_COLORS.size], Pal.BG0, if (lit) 0.35f else 0.55f)

    /**
     * Draws a Meowdoku board: coloured regions with thick borders, X marks and cats.
     * [highlight] cells get a pulsing outline; [ghostCats]/[ghostX] show hinted moves.
     */
    fun drawBoard(
        c: Canvas, n: Int, regions: IntArray, marks: IntArray, ox: Float, oy: Float, cell: Float, p: Float, t: Float,
        highlight: Collection<Int> = emptyList(), ghostCats: Collection<Int> = emptyList(), ghostX: Collection<Int> = emptyList(),
        wrongCell: Int = -1, wrongT: Float = 9f, dance: Boolean = false, litRegions: Set<Int> = emptySet(),
    ) {
        val r = RectF()
        for (i in 0 until n * n) {
            val cx = i % n; val cy = i / n
            r.set(ox + cx * cell, oy + cy * cell, ox + (cx + 1) * cell, oy + (cy + 1) * cell)
            var fill = regionFill(regions[i], regions[i] in litRegions)
            if (i == wrongCell && wrongT < 0.6f && ((wrongT * 10).toInt() % 2 == 0)) fill = Pal.RED
            Gfx.rect(c, r, fill)
            // thin cell grid
            Gfx.rect(c, r.right - maxOf(1f, p / 4), r.top, r.right, r.bottom, Pal.withAlpha(Pal.BLACK, 70))
            Gfx.rect(c, r.left, r.bottom - maxOf(1f, p / 4), r.right, r.bottom, Pal.withAlpha(Pal.BLACK, 70))
        }
        // thick region borders
        val bw = maxOf(2f, floor(cell / 12f))
        for (i in 0 until n * n) {
            val cx = i % n; val cy = i / n
            val x = ox + cx * cell; val y = oy + cy * cell
            if (cx < n - 1 && regions[i + 1] != regions[i]) Gfx.rect(c, x + cell - bw / 2, y - bw / 2, x + cell + bw / 2, y + cell + bw / 2, Pal.BLACK)
            if (cy < n - 1 && regions[i + n] != regions[i]) Gfx.rect(c, x - bw / 2, y + cell - bw / 2, x + cell + bw / 2, y + cell + bw / 2, Pal.BLACK)
        }
        Gfx.frame(c, RectF(ox - bw / 2, oy - bw / 2, ox + n * cell + bw / 2, oy + n * cell + bw / 2), bw, Pal.BLACK)
        // marks
        for (i in 0 until n * n) {
            val cx = i % n; val cy = i / n
            r.set(ox + cx * cell, oy + cy * cell, ox + (cx + 1) * cell, oy + (cy + 1) * cell)
            when (marks[i]) {
                Mark.X -> drawX(c, r, cell, Pal.withAlpha(Pal.BLACK, 200))
                Mark.CAT -> {
                    val bob = if (dance) sin(t * 8f + i) * cell * 0.08f else sin(t * 2.5f + i) * cell * 0.02f
                    val inset = cell * 0.1f
                    CATS[regions[i] % CATS.size].draw(c, RectF(r.left + inset, r.top + inset + bob, r.right - inset, r.bottom - inset + bob))
                }
            }
            if (i in ghostX && marks[i] == Mark.EMPTY && (t * 3).toInt() % 2 == 0) drawX(c, r, cell, Pal.CYAN)
            if (i in ghostCats && marks[i] != Mark.CAT) {
                val inset = cell * 0.18f
                CATS[regions[i] % CATS.size].draw(c, RectF(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset), if ((t * 3).toInt() % 2 == 0) 220 else 90)
            }
        }
        for (i in highlight) {
            val cx = i % n; val cy = i / n
            r.set(ox + cx * cell, oy + cy * cell, ox + (cx + 1) * cell, oy + (cy + 1) * cell)
            val a = (150 + 100 * sin(t * 6f)).toInt().coerceIn(40, 255)
            Gfx.frame(c, RectF(r.left + bw / 2, r.top + bw / 2, r.right - bw / 2, r.bottom - bw / 2), maxOf(2f, bw * 0.8f), Pal.withAlpha(Pal.CYAN, a))
        }
    }

    fun drawX(c: Canvas, r: RectF, cell: Float, color: Int) {
        val d = cell * 0.32f
        val t = maxOf(2f, floor(cell / 10f))
        Gfx.line(c, r.left + d, r.top + d, r.right - d, r.bottom - d, t, color)
        Gfx.line(c, r.right - d, r.top + d, r.left + d, r.bottom - d, t, color)
    }
}

class MeowdokuSession(host: GameHost) : GameSession(host) {
    private val diffKey = "lvl_${host.difficulty.ordinal}"
    private var level = host.getInt(diffKey)
    private var puzzle: MeowPuzzle? = null
    private var marks = IntArray(0)
    private var hearts = 3
    private var hints = 0
    private var mistakes = 0
    private var autoX = host.getInt("autoX", if (host.difficulty == Difficulty.EASY) 1 else 0) == 1
    private val worker = Worker<MeowPuzzle>()
    private val history = ArrayList<Pair<IntArray, IntArray>>()   // cells, previous marks
    private var hint: Step? = null
    private var message = ""
    private var wrongCell = -1
    private var wrongT = 9f
    private var solvedT = -1f
    private var t = 0f

    // layout
    private var n = 5
    private var cell = 0f
    private var ox = 0f
    private var oy = 0f
    private val textBox = RectF()

    // touch
    private var downCell = -1
    private var downAt = 0L
    private var dragging = false
    private var dragTarget = Mark.X
    private var dragFrom = Mark.EMPTY
    private var longFired = false
    private var dragCells = ArrayList<Int>()
    private var dragOld = ArrayList<Int>()

    companion object {
        /** Board size for a difficulty and level: puzzles grow as you progress. */
        fun sizeFor(d: Difficulty, level: Int) = when (d) {
            Difficulty.EASY -> if (level < 3) 5 else 6
            Difficulty.NORMAL -> if (level < 3) 6 else 7
            Difficulty.HARD -> if (level < 3) 8 else 9
        }

        /** Hardest deduction allowed and the minimum one the puzzle should require. */
        fun rulesFor(d: Difficulty, level: Int): Pair<Int, Int> = when (d) {
            Difficulty.EASY -> MeowLogic.R_CONFINE to (if (level < 2) MeowLogic.R_BLOCK else MeowLogic.R_CONFINE)
            Difficulty.NORMAL -> MeowLogic.R_LOOKAHEAD to (if (level < 2) MeowLogic.R_CONFINE else MeowLogic.R_LOOKAHEAD)
            Difficulty.HARD -> MeowLogic.R_SETS to (if (level < 2) MeowLogic.R_LOOKAHEAD else MeowLogic.R_SETS)
        }
    }

    override fun start(saved: JSONObject?) {
        if (saved != null) {
            val sn = saved.getInt("n")
            val p = MeowPuzzle(sn, saved.getJSONArray("regions").toIntArray(), saved.getJSONArray("solution").toIntArray())
            require(p.regions.size == sn * sn && p.solution.size == sn)
            puzzle = p; n = sn
            marks = saved.getJSONArray("marks").toIntArray()
            require(marks.size == sn * sn)
            hearts = saved.optInt("hearts", 3); hints = saved.optInt("hints"); mistakes = saved.optInt("mistakes")
            level = saved.optInt("level", level)
        } else {
            n = sizeFor(host.difficulty, level)
            val (maxRule, minRule) = rulesFor(host.difficulty, level)
            val seed = host.rng.nextLong()
            val size = n
            worker.run { MeowLogic.generate(size, Random(seed), maxRule, minRule) }
        }
        message = "TAP: MARK X   TAP AGAIN: CAT\nHOLD: PLACE CAT   DRAG: MARK MANY X"
    }

    override fun save(): JSONObject? {
        val p = puzzle ?: return null
        return JSONObject().put("n", n).put("regions", p.regions.toJsonArray()).put("solution", p.solution.toJsonArray())
            .put("marks", marks.toJsonArray()).put("hearts", hearts).put("hints", hints).put("mistakes", mistakes).put("level", level)
    }

    override fun onLayout() {
        val p = px
        val textH = PixelFont.LINE * maxOf(2f, floor(p * 0.7f)) * 3 + p * 6
        val side = area.width() > area.height() * 1.25f
        if (side) {
            // landscape: board on the left, explanation panel on the right
            cell = floor(minOf((area.height() - p * 6) / n, (area.width() * 0.66f - p * 6) / n))
            val boardW = cell * n
            val panelW = minOf(area.width() - boardW - p * 12, p * 90)
            val total = boardW + p * 6 + panelW
            ox = floor(area.centerX() - total / 2)
            oy = floor(area.centerY() - boardW / 2)
            textBox.set(ox + boardW + p * 6, oy, ox + boardW + p * 6 + panelW, oy + boardW)
        } else {
            cell = floor(minOf((area.width() - p * 6) / n, (area.height() - textH - p * 8) / n))
            val boardW = cell * n
            ox = floor(area.centerX() - boardW / 2)
            oy = floor(area.centerY() - (boardW + p * 4 + textH) / 2)
            textBox.set(ox, oy + boardW + p * 5, ox + boardW, oy + boardW + p * 5 + textH)
        }
    }

    override fun update(dt: Float) {
        t += dt
        if (wrongT < 9f) wrongT += dt
        if (solvedT >= 0f) solvedT += dt
        if (puzzle == null) {
            worker.take()?.let { p ->
                puzzle = p; marks = IntArray(n * n); onLayout()
            }
        }
        // long press places a cat directly
        if (downCell >= 0 && !dragging && !longFired && SystemClock.uptimeMillis() - downAt > 380) {
            longFired = true
            setMark(downCell, Mark.CAT)
        }
    }

    private fun cellAt(x: Float, y: Float): Int {
        if (x < ox || y < oy) return -1
        val c = floor((x - ox) / cell).toInt(); val r = floor((y - oy) / cell).toInt()
        return if (c in 0 until n && r in 0 until n) r * n + c else -1
    }

    // ------------------------------------------------------------------ actions
    private fun record(cells: List<Int>, old: List<Int>) {
        if (cells.isNotEmpty()) history.add(cells.toIntArray() to old.toIntArray())
        if (history.size > 300) history.removeAt(0)
    }

    /** Applies a mark to one cell with full rule checking (cats are verified against the solution). */
    private fun setMark(i: Int, v: Int) {
        val p = puzzle ?: return
        if (finished || marks[i] == v) return
        hint = null
        if (v == Mark.CAT) {
            if (!p.isSolutionCat(i)) {
                // wrong cat: costs a heart; the cell is revealed as impossible
                hearts--; mistakes++
                wrongCell = i; wrongT = 0f
                record(listOf(i), listOf(marks[i]))
                marks[i] = Mark.X
                host.sfx(Sfx.ERROR); host.vibrate(60)
                message = explainWrong(i)
                if (hearts <= 0) { host.sfx(Sfx.LOSE); lose(detail = "OUT OF HEARTS", delay = 1.2f) }
                return
            }
            val cells = arrayListOf(i); val old = arrayListOf(marks[i])
            marks[i] = Mark.CAT
            if (autoX) for (b in MeowLogic.blockedBy(n, p.regions, i)) if (marks[b] == Mark.EMPTY) { cells.add(b); old.add(Mark.EMPTY); marks[b] = Mark.X }
            record(cells, old)
            host.sfx(Sfx.MEOW); host.vibrate(20)
            message = "PURR-FECT! ${marks.count { it == Mark.CAT }} OF $n CATS PLACED."
            checkSolved()
        } else {
            record(listOf(i), listOf(marks[i]))
            marks[i] = v
            host.sfx(if (v == Mark.X) Sfx.TICK else Sfx.CLICK)
        }
    }

    /** Tells the player which rule a wrong cat broke, or that it simply isn't where a cat belongs. */
    private fun explainWrong(i: Int): String {
        val p = puzzle ?: return ""
        val r = i / n; val c = i % n
        val cats = marks.indices.filter { marks[it] == Mark.CAT }
        return when {
            cats.any { it / n == r } -> "OOPS! ROW ${r + 1} ALREADY HAS A CAT. -1 HEART"
            cats.any { it % n == c } -> "OOPS! COLUMN ${c + 1} ALREADY HAS A CAT. -1 HEART"
            cats.any { p.regions[it] == p.regions[i] } -> "OOPS! THIS REGION ALREADY HAS A CAT. -1 HEART"
            cats.any { abs(it / n - r) <= 1 && abs(it % n - c) <= 1 } -> "OOPS! CATS CAN'T TOUCH, NOT EVEN DIAGONALLY. -1 HEART"
            else -> "NO CAT CAN GO HERE - IT WOULD BREAK THE PUZZLE. -1 HEART"
        }
    }

    private fun checkSolved() {
        val p = puzzle ?: return
        if ((0 until n).all { marks[it * n + p.solution[it]] == Mark.CAT }) {
            for (k in marks.indices) if (marks[k] == Mark.EMPTY) marks[k] = Mark.X
            solvedT = 0f
            host.putInt(diffKey, level + 1)
            host.unlock("first_win")
            if (host.difficulty == Difficulty.HARD) host.unlock("hard_win")
            if (hints == 0 && mistakes == 0) host.unlock("purist")
            message = "ALL CATS ARE HAPPY!"
            win(detail = "LEVEL ${level + 1}  ${n}X$n  HEARTS ${PixelFont.HEART.toString().repeat(hearts)}", delay = 1.6f)
        }
    }

    private fun undo() {
        val (cells, old) = history.removeLastOrNull() ?: return
        for (k in cells.indices) marks[cells[k]] = old[k]
        hint = null
        host.sfx(Sfx.BACK)
    }

    private fun restart() {
        if (puzzle == null || finished) return
        record(marks.indices.toList(), marks.toList())
        marks = IntArray(n * n)
        hint = null
        message = "BOARD CLEARED. HEARTS STAY AS THEY ARE."
        host.sfx(Sfx.BACK)
    }

    private fun showHint() {
        val p = puzzle ?: return
        if (finished) return
        val current = hint
        if (current != null) {
            // second press applies the hint shown
            val cells = ArrayList<Int>(); val old = ArrayList<Int>()
            for (c in current.cross) if (marks[c] == Mark.EMPTY) { cells.add(c); old.add(marks[c]); marks[c] = Mark.X }
            for (c in current.place) if (marks[c] != Mark.CAT) { cells.add(c); old.add(marks[c]); marks[c] = Mark.CAT }
            for (c in current.place) if (autoX) for (b in MeowLogic.blockedBy(n, p.regions, c)) if (marks[b] == Mark.EMPTY) { cells.add(b); old.add(Mark.EMPTY); marks[b] = Mark.X }
            record(cells, old)
            hint = null
            host.sfx(if (current.place.isNotEmpty()) Sfx.MEOW else Sfx.TICK)
            message = "HINT APPLIED."
            checkSolved()
            return
        }
        hints++
        host.sfx(Sfx.POWER)
        // an X sitting on a cat's cell would make every deduction unreliable: point it out first
        val badX = marks.indices.firstOrNull { marks[it] == Mark.X && p.isSolutionCat(it) }
        if (badX != null) {
            hint = Step(-1, emptyList(), emptyList(), listOf(badX), "")
            message = "THIS X IS COVERING A CELL THAT NEEDS A CAT! TAP IT TWICE TO CLEAR IT."
            return
        }
        val step = MeowLogic.nextStep(n, p.regions, marks)
        hint = step ?: run {
            val free = (0 until n).map { it * n + p.solution[it] }.first { marks[it] != Mark.CAT }
            Step(MeowLogic.R_SINGLE, listOf(free), emptyList(), listOf(free), "A CAT BELONGS HERE.")
        }
        message = hint!!.text.uppercase() + "\nTAP HINT AGAIN TO APPLY IT."
    }

    private fun toggleAutoX() {
        autoX = !autoX
        host.putInt("autoX", if (autoX) 1 else 0)
        message = if (autoX) "AUTO-X ON: PLACED CATS CROSS OUT BLOCKED CELLS." else "AUTO-X OFF: MARK BLOCKED CELLS YOURSELF."
    }

    // ------------------------------------------------------------------ input
    override fun onTouch(e: MotionEvent) {
        if (puzzle == null) return
        val i = cellAt(e.x, e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downCell = i; downAt = SystemClock.uptimeMillis(); dragging = false; longFired = false
                dragCells.clear(); dragOld.clear()
                if (i >= 0) {
                    dragFrom = marks[i]
                    dragTarget = if (marks[i] == Mark.EMPTY) Mark.X else Mark.EMPTY
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (downCell < 0 || longFired) return
                if (!dragging && i >= 0 && i != downCell && dragFrom != Mark.CAT) {
                    dragging = true
                    paint(downCell)
                }
                if (dragging && i >= 0) paint(i)
            }
            MotionEvent.ACTION_UP -> {
                if (downCell >= 0 && !dragging && !longFired && i == downCell) {
                    // tap cycles: empty -> X -> cat -> empty
                    when (marks[i]) {
                        Mark.EMPTY -> setMark(i, Mark.X)
                        Mark.X -> setMark(i, Mark.CAT)
                        else -> setMark(i, Mark.EMPTY)
                    }
                }
                if (dragging) { record(dragCells, dragOld); host.sfx(Sfx.TICK) }
                downCell = -1; dragging = false
            }
            MotionEvent.ACTION_CANCEL -> { downCell = -1; dragging = false }
        }
    }

    /** Drag painting: only touches cells in the same state the drag started from. */
    private fun paint(i: Int) {
        if (marks[i] != dragFrom || marks[i] == Mark.CAT || i in dragCells) return
        dragCells.add(i); dragOld.add(marks[i])
        marks[i] = dragTarget
        hint = null
    }

    override fun hud() = listOf(
        HudItem("LEVEL", "${level + 1}  ${n}X$n", Pal.PINK),
        HudItem("HEARTS", PixelFont.HEART.toString().repeat(hearts.coerceAtLeast(0)).ifEmpty { "-" }, Pal.RED),
        HudItem("TIME", formatTime(host.playTime.toInt())),
    )

    override fun actions() = listOf(
        GameAction("UNDO", Icons.UNDO, isEnabled = { history.isNotEmpty() && !finished }) { undo() },
        GameAction("HINT", Icons.HINT, isActive = { hint != null }, isEnabled = { puzzle != null && !finished }) { showHint() },
        GameAction("AUTO-X", Icons.CLOSE, isActive = { autoX }) { toggleAutoX() },
        GameAction("CLEAR", Icons.RESTART, isEnabled = { puzzle != null && !finished }) { restart() },
    )

    override fun draw(c: Canvas) {
        val p = px
        val pz = puzzle
        if (pz == null) {
            Gfx.text(c, "HERDING CATS" + ".".repeat(1 + (t * 3).toInt() % 3), area.centerX(), area.centerY(), px, Pal.YELLOW, Align.CENTER)
            return
        }
        val boardR = RectF(ox, oy, ox + cell * n, oy + cell * n)
        drawBoardFrame(c, boardR, p, Pal.PINK)
        val h = hint
        val lit = pz.regions.indices.filter { marks[it] == Mark.CAT }.map { pz.regions[it] }.toSet()
        MeowArt.drawBoard(
            c, n, pz.regions, marks, ox, oy, cell, p, t,
            highlight = h?.focus ?: emptyList(), ghostCats = h?.place ?: emptyList(), ghostX = h?.cross ?: emptyList(),
            wrongCell = wrongCell, wrongT = wrongT, dance = solvedT >= 0f, litRegions = lit,
        )
        // message panel
        Gfx.rect(c, textBox, Pal.withAlpha(Pal.BG1, 220))
        Gfx.frame(c, textBox, maxOf(1f, p / 2), if (h != null) Pal.CYAN else Pal.BG3)
        val ts = maxOf(2f, floor(p * 0.7f))
        Gfx.paragraph(c, message, textBox.left + p * 3, textBox.top + p * 3, textBox.width() - p * 6, ts,
            if (wrongT < 2f) Pal.lighter(Pal.RED, 0.3f) else if (h != null) Pal.CYAN else Pal.LGREY)
    }
}

val MeowdokuGame = GameDef(
    id = "meowdoku",
    title = "MEOWDOKU",
    category = Category.PUZZLE,
    tagline = "One cat per row, column and colored region - and cats never touch!",
    description = "A logic puzzle full of pixel cats. Place exactly one cat in every row, every column and every colored region, " +
        "without any two cats touching - not even diagonally. Every puzzle has exactly one solution you can reach by pure logic. " +
        "Boards grow from 5x5 kittens to 9x9 lions as you progress.",
    icon = PixelArt(
        "................",
        ".rrrrrrOOOOOOOO.",
        ".rKK..KKOOOOOOO.",
        ".rKpKKpKOOkOOkO.",
        ".rKoooKrOOOkkOO.",
        ".rKGoGKrOOOkkOO.",
        ".rrKKKrrOOkOOkO.",
        ".bbbbbbGGGGGGGG.",
        ".bbkbbbGKK..KKG.",
        ".bbbkbkGKpKKpKG.",
        ".bbbbkbGKoooKGG.",
        ".bbbkbkGKGoGKGG.",
        ".bbkbbbGGKKKGGG.",
        ".bbbbbbGGGGGGGG.",
        "................",
        "................",
    ).let { PixelArt(it.rows, PixelArt.DEFAULT_COLORS + mapOf('o' to Pal.ORANGE)) },
    accent = Pal.PINK,
    music = Music.CAT,
    scoreKind = ScoreKind.BEST_TIME,
    difficultyLabels = listOf("KITTEN", "CAT", "LION"),
    tutorial = MeowTutorial.pages(),
    achievements = listOf(
        AchievementDef("first_win", "CAT HERDER", "Solve a Meowdoku."),
        AchievementDef("hard_win", "PRIDE OF LIONS", "Solve a LION Meowdoku."),
        AchievementDef("purist", "PURR-FECT", "Solve without hints or mistakes."),
    ),
    factory = { MeowdokuSession(it) },
)
