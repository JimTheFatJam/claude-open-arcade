package com.openarcade.games.blockfit

import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import com.openarcade.audio.Music
import com.openarcade.audio.Sfx
import com.openarcade.games.AchievementDef
import com.openarcade.games.Category
import com.openarcade.games.GameDef
import com.openarcade.games.GameHost
import com.openarcade.games.GameSession
import com.openarcade.games.HudItem
import com.openarcade.games.ScoreKind
import com.openarcade.games.TutorialDemo
import com.openarcade.games.TutorialPage
import com.openarcade.games.drawBoardFrame
import com.openarcade.games.toIntArray
import com.openarcade.games.toJsonArray
import com.openarcade.gfx.Align
import com.openarcade.gfx.Gfx
import com.openarcade.gfx.Pal
import com.openarcade.gfx.PixelArt
import com.openarcade.gfx.PixelFont
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.random.Random

object FitArt {
    val COLORS = intArrayOf(Pal.RED, Pal.ORANGE, Pal.YELLOW, Pal.GREEN, Pal.CYAN, Pal.BLUE, Pal.PURPLE, Pal.PINK)
    fun colorFor(shape: Shape) = 1 + shape.id % COLORS.size

    fun block(c: Canvas, l: Float, t: Float, size: Float, colorIndex: Int, alpha: Int = 255) {
        val g = maxOf(1f, floor(size * 0.05f))
        val col = Pal.withAlpha(COLORS[(colorIndex - 1).coerceIn(0, COLORS.size - 1)], alpha)
        val r = RectF(l + g, t + g, l + size - g, t + size - g)
        Gfx.bevel(c, r, col, maxOf(1f, floor(size / 9f)))
        val s = maxOf(1f, floor(size / 8f))
        Gfx.rect(c, r.left + s * 1.5f, r.top + s * 1.5f, r.left + s * 3f, r.top + s * 3f, Pal.withAlpha(Pal.WHITE, alpha * 2 / 3))
    }

    fun emptyCell(c: Canvas, l: Float, t: Float, size: Float) {
        val g = maxOf(1f, floor(size * 0.05f))
        Gfx.rect(c, l + g, t + g, l + size - g, t + size - g, Pal.mix(Pal.BG1, Pal.BG2, 0.35f))
    }

    fun shape(c: Canvas, s: Shape, l: Float, t: Float, size: Float, color: Int, alpha: Int = 255) {
        for (cell in s.cells) block(c, l + cell[0] * size, t + cell[1] * size, size, color, alpha)
    }
}

class BlockFitSession(host: GameHost) : GameSession(host) {
    private val n = FitBoard.N
    private var board = FitBoard()
    private val tray = arrayOfNulls<Shape>(3)
    private val dealer = Dealer(Random(host.rng.nextLong()), host.difficulty.ordinal)
    private var score = 0
    private var lines = 0
    private var combo = 0
    private var placedCount = 0
    private var t = 0f

    // layout
    private var cell = 0f
    private var ox = 0f
    private var oy = 0f
    private val slots = Array(3) { RectF() }
    private var trayCell = 0f

    // input
    private var dragSlot = -1
    private var dragX = 0f
    private var dragY = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragMoved = false
    private var selected = -1

    // animation
    private val pop = FloatArray(n * n) { 9f }
    private class Burst(val cell: Int, val color: Int, var t: Float = 0f)
    private val bursts = ArrayList<Burst>()
    private class Popup(val text: String, val x: Float, val y: Float, val color: Int, var t: Float = 0f)
    private val popups = ArrayList<Popup>()
    private val dealT = FloatArray(3) { 9f }
    private var overT = -1f

    // ---- hooks for automated tests
    internal val boardForTest get() = board
    internal val trayForTest get() = tray
    internal val scoreForTest get() = score
    internal fun placeForTest(slot: Int, x: Int, y: Int): Boolean = tryPlace(slot, x, y)

    override fun start(saved: JSONObject?) {
        if (saved != null) {
            val cells = saved.getJSONArray("board").toIntArray()
            require(cells.size == n * n)
            board = FitBoard(cells)
            val ids = saved.getJSONArray("tray").toIntArray()
            require(ids.size == 3)
            for (k in 0 until 3) tray[k] = Shape.ALL.getOrNull(ids[k])
            score = saved.getInt("score"); lines = saved.optInt("lines"); combo = saved.optInt("combo"); placedCount = saved.optInt("placed")
            if (tray.all { it == null }) deal()
            checkGameOver()
        } else deal()
    }

    override fun save(): JSONObject? {
        if (overT >= 0f) return null
        return JSONObject().put("board", board.cells.toJsonArray())
            .put("tray", IntArray(3) { tray[it]?.id ?: -1 }.toJsonArray())
            .put("score", score).put("lines", lines).put("combo", combo).put("placed", placedCount)
    }

    private fun deal() {
        val set = dealer.deal(board)
        for (k in 0 until 3) { tray[k] = set[k]; dealT[k] = -k * 0.07f }
    }

    override fun onLayout() {
        val p = px
        val side = area.width() > area.height() * 1.2f
        if (side) {
            // board on the left, the three pieces stacked on the right
            val trayW = minOf(area.width() * 0.32f, area.height() / 3f)
            cell = floor(minOf((area.width() - trayW - p * 8) / n, (area.height() - p * 6) / n))
            val total = cell * n + p * 6 + trayW
            ox = floor(area.centerX() - total / 2 + p * 3); oy = floor(area.centerY() - cell * n / 2)
            val tx = ox + cell * n + p * 6
            val sh = (cell * n) / 3f
            for (k in 0 until 3) slots[k].set(tx, oy + k * sh, tx + trayW, oy + (k + 1) * sh)
            trayCell = floor(minOf(trayW, sh) / 5.6f)
        } else {
            val trayH = minOf(area.height() * 0.26f, area.width() / 3f)
            cell = floor(minOf((area.width() - p * 6) / n, (area.height() - trayH - p * 8) / n))
            val total = cell * n + p * 6 + trayH
            ox = floor(area.centerX() - cell * n / 2); oy = floor(area.centerY() - total / 2 + p * 3)
            val ty = oy + cell * n + p * 6
            val sw = (cell * n) / 3f
            for (k in 0 until 3) slots[k].set(ox + k * sw, ty, ox + (k + 1) * sw, ty + trayH)
            trayCell = floor(minOf(sw, trayH) / 5.6f)
        }
        cell = cell.coerceAtLeast(2f); trayCell = trayCell.coerceAtLeast(1f)
    }

    /** Board corner (cell) the dragged piece would snap to, or null when it's over nothing useful. */
    private fun dragTarget(s: Shape): IntArray? {
        val l = dragX - s.w * cell / 2; val top = dragY - s.h * cell / 2 - cell * 1.6f
        val gx = ((l - ox) / cell).roundToInt(); val gy = ((top - oy) / cell).roundToInt()
        if (gx < -1 || gy < -1 || gx > n || gy > n) return null
        return intArrayOf(gx, gy)
    }

    override fun onTouch(e: MotionEvent) {
        if (finished || overT >= 0f) return
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; dragMoved = false
                dragSlot = slots.indices.firstOrNull { slots[it].contains(e.x, e.y) && tray[it] != null } ?: -1
                dragX = e.x; dragY = e.y
                if (dragSlot >= 0) host.sfx(Sfx.CLICK)
            }
            MotionEvent.ACTION_MOVE -> {
                dragX = e.x; dragY = e.y
                if (abs(e.x - downX) + abs(e.y - downY) > px * 4) dragMoved = true
            }
            MotionEvent.ACTION_UP -> {
                val slot = dragSlot
                dragSlot = -1
                if (slot >= 0 && !dragMoved) {
                    // tap a piece to select it, then tap the board to place it
                    selected = if (selected == slot) -1 else slot
                    return
                }
                if (slot >= 0) {
                    val s = tray[slot] ?: return
                    val tgt = dragTarget(s)
                    if (tgt != null && board.fits(s, tgt[0], tgt[1])) tryPlace(slot, tgt[0], tgt[1])
                    else if (tgt != null && insideBoard(e.x, e.y - cell * 1.6f)) host.sfx(Sfx.ERROR)
                    return
                }
                val sel = selected
                val s = if (sel >= 0) tray[sel] else null
                if (s != null && insideBoard(e.x, e.y)) {
                    val cx = floor((e.x - ox) / cell).toInt(); val cy = floor((e.y - oy) / cell).toInt()
                    val gx = cx - (s.w - 1) / 2; val gy = cy - (s.h - 1) / 2
                    if (!tryPlace(sel, gx, gy)) host.sfx(Sfx.ERROR)
                }
            }
            MotionEvent.ACTION_CANCEL -> dragSlot = -1
        }
    }

    private fun insideBoard(x: Float, y: Float) = x >= ox && y >= oy && x < ox + cell * n && y < oy + cell * n

    private fun tryPlace(slot: Int, x: Int, y: Int): Boolean {
        val s = tray[slot] ?: return false
        if (!board.fits(s, x, y)) return false
        val color = FitArt.colorFor(s)
        val before = board.cells.copyOf()
        val clear = board.place(s, x, y, color)
        tray[slot] = null
        if (selected == slot) selected = -1
        placedCount++
        for (c in s.cells) pop[(y + c[1]) * n + x + c[0]] = 0f
        var gained = s.size
        val cx = ox + (x + s.w / 2f) * cell; val cy = oy + (y + s.h / 2f) * cell
        if (clear.count > 0) {
            combo++
            lines += clear.count
            gained += FitScore.lines(clear.count, combo - 1)
            for (i in before.indices) if (clear.covers(i)) {
                val col = if (before[i] != 0) before[i] else color
                bursts.add(Burst(i, col))
            }
            host.sfx(if (clear.count >= 2) Sfx.LEVELUP else Sfx.LINE)
            host.vibrate(30)
            host.unlock("first_clear")
            if (clear.count >= 3) host.unlock("multi")
            val label = when (clear.count) { 1 -> "LINE!"; 2 -> "DOUBLE!"; 3 -> "TRIPLE!"; else -> "AMAZING!" }
            popups.add(Popup(label + if (combo > 1) " COMBO X$combo" else "", ox + cell * n / 2, cy - cell, Pal.YELLOW))
            if (board.empty) { gained += FitScore.BOARD_CLEAR; host.unlock("board_clear"); popups.add(Popup("BOARD CLEAR!", ox + cell * n / 2, oy + cell * n / 2, Pal.CYAN)) }
        } else {
            combo = 0
            host.sfx(Sfx.PLACE)
        }
        score += gained
        popups.add(Popup("+$gained", cx, cy, Pal.WHITE))
        if (score >= 1000) host.unlock("score_1000")
        if (score >= 5000) host.unlock("score_5000")
        if (tray.all { it == null }) deal()
        checkGameOver()
        return true
    }

    private fun checkGameOver() {
        if (overT >= 0f) return
        if (tray.filterNotNull().none { board.fitsAnywhere(it) }) {
            overT = 0f
            selected = -1
            host.sfx(Sfx.LOSE)
            lose(score = score, detail = "LINES $lines  PIECES $placedCount", title = "NO MORE ROOM", delay = 1.6f)
        }
    }

    override fun update(dt: Float) {
        t += dt
        for (i in pop.indices) if (pop[i] < 9f) pop[i] += dt
        for (k in 0 until 3) if (dealT[k] < 9f) dealT[k] += dt
        bursts.forEach { it.t += dt }; bursts.removeAll { it.t > 0.45f }
        popups.forEach { it.t += dt }; popups.removeAll { it.t > 1.1f }
        if (overT >= 0f) overT += dt
    }

    override fun hud() = listOf(
        HudItem("SCORE", score.toString()),
        HudItem("BEST", maxOf(host.bestScore(), score).toString(), Pal.YELLOW),
        HudItem(if (combo > 1) "COMBO" else "LINES", if (combo > 1) "X$combo" else lines.toString(), if (combo > 1) Pal.ORANGE else Pal.CYAN),
    )

    override fun draw(c: Canvas) {
        val p = px
        val bounds = RectF(ox, oy, ox + cell * n, oy + cell * n)
        drawBoardFrame(c, bounds, p, Pal.CYAN)
        Gfx.rect(c, bounds, Pal.BG0)
        // preview of the dragged piece: where it lands and which lines it would clear
        var ghost: IntArray? = null
        var ghostShape: Shape? = null
        var preview: FitBoard.Clear? = null
        val ds = if (dragSlot >= 0 && dragMoved) tray[dragSlot] else null
        if (ds != null) {
            val tgt = dragTarget(ds)
            if (tgt != null && board.fits(ds, tgt[0], tgt[1])) {
                ghost = tgt; ghostShape = ds
                val b = board.copy(); for (cc in ds.cells) b.cells[(tgt[1] + cc[1]) * n + tgt[0] + cc[0]] = 1
                preview = b.fullLines().takeIf { it.count > 0 }
            }
        }
        for (i in 0 until n * n) {
            val x = ox + (i % n) * cell; val y = oy + (i / n) * cell
            val v = board.cells[i]
            val over = overT >= 0f && (i / n) < overT * 10f
            if (v == 0) FitArt.emptyCell(c, x, y, cell)
            else {
                val k = pop[i]
                val sc = if (k < 0.16f) 1f + 0.18f * (1f - k / 0.16f) else 1f
                val sz = cell * sc
                if (over) {
                    val g = maxOf(1f, floor(cell * 0.05f))
                    Gfx.bevel(c, RectF(x + g, y + g, x + cell - g, y + cell - g), Pal.DGREY, maxOf(1f, floor(cell / 9f)))
                } else FitArt.block(c, x - (sz - cell) / 2, y - (sz - cell) / 2, sz, if (preview?.covers(i) == true) colorOfShape(ghostShape) else v)
            }
            if (preview?.covers(i) == true) Gfx.rect(c, x, y, x + cell, y + cell, Pal.withAlpha(Pal.WHITE, (60 + 40 * kotlin.math.sin(t * 10f)).toInt()))
        }
        if (ghost != null && ghostShape != null) FitArt.shape(c, ghostShape, ox + ghost[0] * cell, oy + ghost[1] * cell, cell, FitArt.colorFor(ghostShape), 90)
        for (b in bursts) {
            val k = b.t / 0.45f
            val x = ox + (b.cell % n) * cell; val y = oy + (b.cell / n) * cell
            val s = cell * (1f - k)
            if (k < 0.25f) Gfx.rect(c, x, y, x + cell, y + cell, Pal.withAlpha(Pal.WHITE, (220 * (1 - k * 4)).toInt()))
            FitArt.block(c, x + (cell - s) / 2, y + (cell - s) / 2, s, b.color, (255 * (1 - k)).toInt())
            // sparks flying out
            val sp = maxOf(1f, p * 0.8f)
            val dist = cell * k * 1.2f
            val col = Pal.withAlpha(FitArt.COLORS[(b.color - 1).coerceIn(0, 7)], (255 * (1 - k)).toInt())
            Gfx.rect(c, x + cell / 2 - dist, y + cell / 2, x + cell / 2 - dist + sp, y + cell / 2 + sp, col)
            Gfx.rect(c, x + cell / 2 + dist, y + cell / 2, x + cell / 2 + dist + sp, y + cell / 2 + sp, col)
            Gfx.rect(c, x + cell / 2, y + cell / 2 - dist, x + cell / 2 + sp, y + cell / 2 - dist + sp, col)
        }
        // tray
        for (k in 0 until 3) {
            val r = slots[k]
            if (selected == k) Gfx.frame(c, RectF(r.left + p, r.top + p, r.right - p, r.bottom - p), maxOf(1f, p / 2), Pal.YELLOW)
            val s = tray[k] ?: continue
            if (k == dragSlot && dragMoved) continue
            val dt = dealT[k]
            val sc = if (dt < 0f) 0f else if (dt < 0.2f) dt / 0.2f else 1f
            if (sc <= 0f) continue
            val tc = trayCell * sc * (if (selected == k) 1.12f else 1f)
            val fits = board.fitsAnywhere(s)
            FitArt.shape(c, s, floor(r.centerX() - s.w * tc / 2), floor(r.centerY() - s.h * tc / 2), tc, FitArt.colorFor(s), if (fits) 255 else 80)
        }
        if (ds != null) {
            FitArt.shape(c, ds, dragX - ds.w * cell / 2, dragY - ds.h * cell / 2 - cell * 1.6f, cell, FitArt.colorFor(ds), 235)
        }
        for (pu in popups) {
            val k = pu.t / 1.1f
            val size = if (pu.text.startsWith("+")) maxOf(2f, floor(p * 0.9f)) else maxOf(2f, floor(p * 1.2f))
            Gfx.text(c, pu.text, pu.x, pu.y - k * cell * 1.2f, size, Pal.withAlpha(pu.color, (255 * (1 - k * k)).toInt()), Align.CENTER)
        }
        if (overT >= 0f && overT > 0.8f) {
            Gfx.textMid(c, "NO MORE ROOM!", bounds.centerX(), bounds.centerY(), maxOf(2f, floor(p * 1.4f)), Pal.RED)
        }
    }

    private fun colorOfShape(s: Shape?) = if (s == null) 1 else FitArt.colorFor(s)
}

/** Tutorial exercise: drop one piece to complete a row and watch it clear. */
private class FitDemo : TutorialDemo() {
    private val cols = 6
    private val rows = 3
    private val cells = IntArray(cols * rows).also { for (x in 0 until cols) if (x !in 2..3) it[2 * cols + x] = 6; it[cols + 0] = 4; it[cols + 5] = 4 }
    private val piece = Shape.ALL.first { it.w == 2 && it.h == 1 && it.size == 2 }
    private var placed = false
    private var flash = -1f
    private var dragging = false
    private var dx = 0f
    private var dy = 0f
    private var cell = 0f
    private var ox = 0f
    private var oy = 0f
    private val trayBox = RectF()
    override val task = "TRY IT: DRAG THE PIECE INTO THE GAP TO COMPLETE THE BOTTOM ROW."

    override fun update(dt: Float) { if (flash >= 0f) flash += dt }

    override fun draw(c: Canvas, box: RectF, p: Float, t: Float) {
        cell = floor(minOf(box.width() / cols, box.height() / (rows + 2.5f)))
        ox = floor(box.centerX() - cell * cols / 2); oy = box.top
        for (i in cells.indices) {
            val x = ox + (i % cols) * cell; val y = oy + (i / cols) * cell
            if (cells[i] == 0) FitArt.emptyCell(c, x, y, cell) else FitArt.block(c, x, y, cell, cells[i])
        }
        if (flash in 0f..0.45f) {
            val k = flash / 0.45f
            Gfx.rect(c, ox, oy + 2 * cell, ox + cols * cell, oy + 3 * cell, Pal.withAlpha(Pal.WHITE, (200 * (1 - k)).toInt()))
        }
        trayBox.set(ox, oy + rows * cell + cell * 0.5f, ox + cols * cell, oy + rows * cell + cell * 2.5f)
        if (!placed) {
            if (dragging) FitArt.shape(c, piece, dx - cell, dy - cell * 1.6f - cell / 2, cell, 2)
            else FitArt.shape(c, piece, trayBox.centerX() - cell * 0.7f, trayBox.centerY() - cell * 0.35f, cell * 0.7f, 2)
        }
    }

    override fun onTouch(e: MotionEvent, box: RectF) {
        if (placed) return
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragging = trayBox.contains(e.x, e.y); dx = e.x; dy = e.y }
            MotionEvent.ACTION_MOVE -> { dx = e.x; dy = e.y }
            MotionEvent.ACTION_UP -> {
                if (!dragging) return
                dragging = false
                val gx = ((dx - cell - ox) / cell).roundToInt(); val gy = ((dy - cell * 1.6f - cell / 2 - oy) / cell).roundToInt()
                val free = gx in 0..cols - 2 && gy in 0 until rows && cells[gy * cols + gx] == 0 && cells[gy * cols + gx + 1] == 0
                if (!free) { bad("IT MUST FIT ON EMPTY SQUARES. TRY AGAIN."); return }
                cells[gy * cols + gx] = 2; cells[gy * cols + gx + 1] = 2
                placed = true
                if ((0 until cols).all { cells[gy * cols + it] != 0 }) {
                    for (x in 0 until cols) cells[gy * cols + x] = 0
                    flash = 0f
                    complete("ROW COMPLETE - IT CLEARS AND SCORES BONUS POINTS!")
                } else {
                    good("PLACED - BUT THAT ROW ISN'T FULL. LET'S TRY AGAIN.")
                    cells[gy * cols + gx] = 0; cells[gy * cols + gx + 1] = 0; placed = false
                }
            }
        }
    }
}

private val ART_LINES = PixelArt(
    "WWWWWWWW",
    "kkkkCkkk",
    "kRkkCkkk",
    "kRkkCkGk",
    "WWWWWWWW",
    "kkkkCkGk",
    "kkkkCkkk",
)
private val ART_TRAY = PixelArt(
    "..............",
    ".RR...Y...BBB.",
    ".RR...Y.....B.",
    "......YY....B.",
    "..............",
)

val BlockFitGame = GameDef(
    id = "blockfit",
    title = "BLOCK FIT",
    category = Category.PUZZLE,
    tagline = "Fit blocks on the 8x8 grid and blast full rows and columns.",
    description = "Drag blocks from your tray onto an 8x8 grid. Fill a whole row or column and it clears for points - clear several at once, " +
        "or keep clearing turn after turn, for big combo bonuses. Blocks never rotate. The game ends when none of your blocks fit.",
    icon = PixelArt(
        "................",
        ".kkkkkkkkkkkkkk.",
        ".kRRkkkkkkkkBBk.",
        ".kRRkkkkkkkkBBk.",
        ".kkkkkYYkkkkkkk.",
        ".kkkkkYYkkkkkkk.",
        ".kWWWWWWWWWWWWk.",
        ".kWWWWWWWWWWWWk.",
        ".kkkkkkkkGGkkkk.",
        ".kkkkkkkkGGkkkk.",
        ".kkPPkkkkGGGGkk.",
        ".kkPPkkkkGGGGkk.",
        ".kkkkkkkkkkkkkk.",
        "................",
        "..OOOO...CC.CC..",
        "................",
    ),
    accent = Pal.CYAN,
    music = Music.ARCADE,
    scoreKind = ScoreKind.HIGH_SCORE,
    difficultyLabels = listOf("RELAXED", "NORMAL", "TOUGH"),
    tutorial = listOf(
        TutorialPage("OBJECTIVE", "Place blocks on the 8x8 grid to score. Every completed ROW or COLUMN is cleared instantly, making room for more. " +
            "Play as long as you can!", ART_LINES),
        TutorialPage("PLACING BLOCKS", "DRAG a block from the tray below the board and let go over the grid - a ghost shows where it lands and lines that will clear flash. " +
            "Or TAP a block to select it and TAP the board where its center should go.", ART_TRAY, demo = { FitDemo() }),
        TutorialPage("THE RULES", "Blocks must fit completely on EMPTY squares. They can't overlap other blocks and they can never be rotated. " +
            "After all three blocks in the tray are placed you get three new ones.", ART_TRAY),
        TutorialPage("SCORING", "1 point for every square placed. Clearing lines scores much more: several rows or columns cleared together multiply the bonus, " +
            "and clearing again on the next move builds a COMBO. Empty the whole board for +300.", ART_LINES),
        TutorialPage("GAME OVER", "When none of your remaining blocks fits anywhere, the game ends. Blocks that can't fit are shown faded in the tray. " +
            "New sets are dealt so that all three can be placed whenever the board still allows it - plan ahead to keep room!", com.openarcade.gfx.Icons.CLOSE),
    ),
    achievements = listOf(
        AchievementDef("first_clear", "CLEAN SWEEP", "Clear your first line."),
        AchievementDef("multi", "TRIPLE THREAT", "Clear 3 or more lines with one block."),
        AchievementDef("board_clear", "SPOTLESS", "Empty the whole board."),
        AchievementDef("score_1000", "BLOCK STAR", "Score 1,000 points in one game."),
        AchievementDef("score_5000", "BLOCK LEGEND", "Score 5,000 points in one game."),
    ),
    factory = { BlockFitSession(it) },
)
