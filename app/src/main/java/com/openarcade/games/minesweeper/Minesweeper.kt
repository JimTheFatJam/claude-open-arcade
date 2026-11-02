package com.openarcade.games.minesweeper

import android.graphics.Canvas
import android.graphics.RectF
import android.view.KeyEvent
import android.view.MotionEvent
import com.openarcade.audio.Music
import com.openarcade.audio.Sfx
import com.openarcade.core.Gestures
import com.openarcade.games.Category
import com.openarcade.games.AchievementDef
import com.openarcade.games.Difficulty
import com.openarcade.games.GameAction
import com.openarcade.games.GameDef
import com.openarcade.games.GameHost
import com.openarcade.games.GameSession
import com.openarcade.games.GridFit
import com.openarcade.games.HudItem
import com.openarcade.games.ScoreKind
import com.openarcade.games.TutorialPage
import com.openarcade.games.drawBoardFrame
import com.openarcade.games.formatTime
import com.openarcade.games.toIntArray
import com.openarcade.games.toJsonArray
import com.openarcade.gfx.Gfx
import com.openarcade.gfx.Icons
import com.openarcade.gfx.Pal
import com.openarcade.gfx.PixelArt
import com.openarcade.gfx.PixelFont
import org.json.JSONObject

/** Pure Minesweeper rules, independent of rendering (unit tested). */
class MineBoard(val cols: Int, val rows: Int, val mineCount: Int) {
    val n = cols * rows
    var mines = BooleanArray(n); private set
    val revealed = BooleanArray(n)
    val flagged = BooleanArray(n)
    var placed = false; private set
    var exploded = -1

    fun neighbors(i: Int): List<Int> {
        val c = i % cols
        val r = i / cols
        val out = ArrayList<Int>(8)
        for (dr in -1..1) for (dc in -1..1) {
            if (dr == 0 && dc == 0) continue
            val cc = c + dc
            val rr = r + dr
            if (cc in 0 until cols && rr in 0 until rows) out.add(cc + rr * cols)
        }
        return out
    }

    fun count(i: Int) = neighbors(i).count { mines[it] }

    /** Places mines avoiding [safe] and its neighbours so the first click always opens an area. */
    fun place(safe: Int, rnd: kotlin.random.Random) {
        val forbidden = HashSet<Int>().apply { add(safe); addAll(neighbors(safe)) }
        val candidates = (0 until n).filter { it !in forbidden }.shuffled(rnd)
        val k = minOf(mineCount, candidates.size)
        mines = BooleanArray(n)
        for (j in 0 until k) mines[candidates[j]] = true
        placed = true
    }

    fun setMines(m: BooleanArray) { mines = m; placed = true }

    /** Reveals a cell (flood-filling zeros). Returns the list of newly revealed cells. */
    fun reveal(i: Int): List<Int> {
        if (revealed[i] || flagged[i]) return emptyList()
        val out = ArrayList<Int>()
        if (mines[i]) { revealed[i] = true; exploded = i; out.add(i); return out }
        val stack = ArrayDeque<Int>()
        stack.add(i)
        while (stack.isNotEmpty()) {
            val k = stack.removeLast()
            if (revealed[k] || flagged[k]) continue
            revealed[k] = true
            out.add(k)
            if (count(k) == 0) for (nb in neighbors(k)) if (!revealed[nb] && !mines[nb]) stack.add(nb)
        }
        return out
    }

    /** "Chording": on a revealed number with all its flags placed, reveal the other neighbours. */
    fun chord(i: Int): List<Int> {
        if (!revealed[i] || mines[i]) return emptyList()
        val nbs = neighbors(i)
        if (nbs.count { flagged[it] } != count(i)) return emptyList()
        val out = ArrayList<Int>()
        for (nb in nbs) if (!flagged[nb] && !revealed[nb]) out.addAll(reveal(nb))
        return out
    }

    fun won() = placed && exploded < 0 && (0 until n).all { mines[it] || revealed[it] }
    fun flagsLeft() = mineCount - flagged.count { it }
}

class MinesweeperSession(host: GameHost) : GameSession(host) {
    private val dims = when (host.difficulty) {
        Difficulty.EASY -> Triple(9, 9, 10)
        Difficulty.NORMAL -> Triple(10, 14, 22)
        Difficulty.HARD -> Triple(14, 18, 45)
    }
    private var board = MineBoard(dims.first, dims.second, dims.third)
    private val grid = GridFit(dims.first, dims.second)
    private var flagMode = false
    private var cursor = -1
    private var t = 0f
    private val revealAnim = HashMap<Int, Float>()
    private val gestures = Gestures { px * 3 }

    init {
        gestures.onTap = { x, y -> val i = grid.cellAt(x, y); if (i >= 0) { cursor = i; if (flagMode) toggleFlag(i) else open(i) } }
        gestures.onLongPress = { x, y -> val i = grid.cellAt(x, y); if (i >= 0) { cursor = i; if (flagMode) open(i) else toggleFlag(i) } }
    }

    override fun start(saved: JSONObject?) {
        if (saved != null) {
            board = MineBoard(saved.getInt("cols"), saved.getInt("rows"), saved.getInt("mines"))
            grid.cols = board.cols; grid.rows = board.rows
            if (saved.optBoolean("placed")) {
                val m = saved.getJSONArray("m").toIntArray()
                board.setMines(BooleanArray(board.n) { m[it] == 1 })
            }
            val r = saved.getJSONArray("r").toIntArray()
            val f = saved.getJSONArray("f").toIntArray()
            for (i in 0 until board.n) { board.revealed[i] = r[i] == 1; board.flagged[i] = f[i] == 1 }
            flagMode = saved.optBoolean("flagMode")
        }
    }

    override fun save(): JSONObject = JSONObject()
        .put("cols", board.cols).put("rows", board.rows).put("mines", board.mineCount)
        .put("placed", board.placed)
        .put("m", IntArray(board.n) { if (board.mines[it]) 1 else 0 }.toJsonArray())
        .put("r", IntArray(board.n) { if (board.revealed[it]) 1 else 0 }.toJsonArray())
        .put("f", IntArray(board.n) { if (board.flagged[it]) 1 else 0 }.toJsonArray())
        .put("flagMode", flagMode)

    override fun onLayout() = grid.fit(area, px * 3, px * 22)

    private fun open(i: Int) {
        if (finished) return
        if (!board.placed) board.place(i, host.rng)
        val opened = if (board.revealed[i]) board.chord(i) else board.reveal(i)
        if (opened.isEmpty()) { if (board.flagged[i]) host.sfx(Sfx.ERROR); return }
        val (cc, cr) = i % board.cols to i / board.cols
        for (k in opened) {
            val d = kotlin.math.abs(k % board.cols - cc) + kotlin.math.abs(k / board.cols - cr)
            revealAnim[k] = -d * 0.025f
        }
        if (board.exploded >= 0) {
            host.sfx(Sfx.EXPLODE)
            host.vibrate(120)
            lose(detail = "YOU HIT A MINE!", delay = 1.6f)
            return
        }
        host.sfx(if (opened.size > 1) Sfx.REVEAL else Sfx.CLICK)
        if (board.won()) {
            for (k in 0 until board.n) if (board.mines[k]) board.flagged[k] = true
            host.unlock("first_win")
            if (host.difficulty == Difficulty.HARD) host.unlock("hard_win")
            if (host.difficulty == Difficulty.EASY && host.playTime < 60f) host.unlock("speed")
            win(detail = "FIELD CLEARED!", delay = 1.2f)
        }
    }

    private fun toggleFlag(i: Int) {
        if (finished || board.revealed[i]) return
        board.flagged[i] = !board.flagged[i]
        host.sfx(Sfx.FLAG)
        host.vibrate(15)
    }

    override fun update(dt: Float) {
        t += dt
        gestures.update()
        if (revealAnim.isNotEmpty()) {
            val it = revealAnim.entries.iterator()
            while (it.hasNext()) { val e = it.next(); e.setValue(e.value + dt); if (e.value > 0.2f) it.remove() }
        }
    }

    override fun onTouch(e: MotionEvent) = gestures.handle(e)

    override fun onKey(keyCode: Int): Boolean {
        if (cursor < 0) cursor = board.n / 2
        val c = cursor % board.cols
        val r = cursor / board.cols
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (c > 0) cursor--
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (c < board.cols - 1) cursor++
            KeyEvent.KEYCODE_DPAD_UP -> if (r > 0) cursor -= board.cols
            KeyEvent.KEYCODE_DPAD_DOWN -> if (r < board.rows - 1) cursor += board.cols
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> open(cursor)
            KeyEvent.KEYCODE_F -> toggleFlag(cursor)
            else -> return false
        }
        return true
    }

    override fun hud() = listOf(
        HudItem("MINES", board.flagsLeft().toString(), Pal.RED),
        HudItem("TIME", formatTime(host.playTime.toInt())),
        HudItem("MODE", if (flagMode) "FLAG" else "DIG", if (flagMode) Pal.ORANGE else Pal.GREEN),
    )

    override fun actions() = listOf(
        GameAction("DIG", Icons.SHOVEL, isActive = { !flagMode }) { flagMode = false },
        GameAction("FLAG", Icons.FLAG, isActive = { flagMode }) { flagMode = true },
    )

    override fun draw(c: Canvas) {
        val p = px
        val cell = grid.cell
        drawBoardFrame(c, grid.bounds, p, Pal.GREY)
        val r = RectF()
        val bevel = maxOf(1f, (cell / 8f).toInt().toFloat())
        val lost = board.exploded >= 0
        for (i in 0 until board.n) {
            grid.rect(i % board.cols, i / board.cols, r)
            val anim = revealAnim[i]
            val showRevealed = board.revealed[i] && (anim == null || anim >= 0f)
            if (!showRevealed) {
                Gfx.bevel(c, r, if ((i % board.cols + i / board.cols) % 2 == 0) Pal.BG3 else Pal.mix(Pal.BG3, Pal.BG2, 0.35f), bevel)
                if (board.flagged[i]) {
                    if (lost && !board.mines[i]) {
                        FLAG.drawFit(c, inset(r, cell * 0.18f))
                        Icons.CLOSE.drawFit(c, inset(r, cell * 0.1f), tint = Pal.RED)
                    } else FLAG.drawFit(c, inset(r, cell * 0.18f))
                } else if (lost && board.mines[i]) {
                    MINE.drawFit(c, inset(r, cell * 0.18f))
                }
            } else {
                val pop = if (anim != null) 1f - anim / 0.2f else 0f
                Gfx.rect(c, r, Pal.BG1)
                Gfx.rect(c, r.left, r.top, r.right, r.top + maxOf(1f, bevel / 2), Pal.BG0)
                Gfx.rect(c, r.left, r.top, r.left + maxOf(1f, bevel / 2), r.bottom, Pal.BG0)
                if (board.mines[i]) {
                    if (i == board.exploded) Gfx.rect(c, r, if (((t * 8).toInt() % 2) == 0) Pal.RED else Pal.DRED)
                    MINE.drawFit(c, inset(r, cell * 0.15f))
                } else {
                    val n = board.count(i)
                    if (n > 0) {
                        val size = Gfx.fitSize("8", cell * 0.6f, (cell * 0.65f / PixelFont.GLYPH_H).coerceAtLeast(1f))
                        val s = (size * (1f + pop * 0.3f))
                        Gfx.textMid(c, n.toString(), r.centerX(), r.centerY(), s, NUM_COLORS[n], shadow = Pal.BLACK)
                    }
                }
            }
            if (i == cursor && !finished) Gfx.frame(c, r, maxOf(1f, bevel), Pal.withAlpha(Pal.YELLOW, 200))
        }
    }

    private fun inset(r: RectF, d: Float) = RectF(r.left + d, r.top + d, r.right - d, r.bottom - d)

    companion object {
        val NUM_COLORS = intArrayOf(0, Pal.CYAN, Pal.GREEN, Pal.RED, Pal.PURPLE, Pal.ORANGE, Pal.PINK, Pal.YELLOW, Pal.WHITE)
        val MINE = PixelArt(
            "....g....",
            ".g.ddd.g.",
            "..ddddd..",
            ".ddWdddd.",
            "gdWddddDg".replace('D', 'd'),
            ".ddddddd.",
            "..ddddd..",
            ".g.ddd.g.",
            "....g....",
        )
        val FLAG = Icons.FLAG
    }
}

val MinesweeperGame = GameDef(
    id = "minesweeper",
    title = "MINESWEEPER",
    category = Category.PUZZLE,
    tagline = "Clear the field without detonating a single mine.",
    description = "A classic logic puzzle. Every number tells you how many mines touch that square. " +
        "Use deduction to uncover every safe square and flag the dangers. Your first dig is always safe.",
    icon = PixelArt(
        "................",
        ".......gg.......",
        "...g...dd...g...",
        "....g.dddd.g....",
        ".....dddddd.....",
        "....ddWWdddd....",
        "...dddWddddddd..",
        ".ggddddddddddgg.",
        ".ggddddddddddgg.",
        "...dddddddddd...",
        "....dddddddd....",
        ".....dddddd.....",
        "....g.dddd.g....",
        "...g...dd...g...",
        ".......gg.......",
        "................",
    ),
    accent = Pal.RED,
    music = Music.PUZZLE,
    scoreKind = ScoreKind.BEST_TIME,
    difficultyLabels = listOf("EASY 9X9", "NORMAL", "HARD"),
    tutorial = listOf(
        TutorialPage("OBJECTIVE", "Reveal every square that does NOT hide a mine. Uncover all safe squares to win. Dig up a mine and it's game over!",
            PixelArt(
                "BBBBBBBB",
                "BkkkkkBB",
                "Bk1k2kBB".replace('1', 'C').replace('2', 'G'),
                "BkkkkkRB",
                "BBkCkBBB",
                "BBBBBBBB",
            )),
        TutorialPage("CONTROLS", "TAP a square to dig it.\nLONG-PRESS a square to plant or remove a flag.\nOr switch between DIG and FLAG mode with the buttons under the board.", com.openarcade.gfx.Icons.TAP),
        TutorialPage("NUMBERS", "A number shows how many mines touch that square, including diagonals. A blank square has no mines nearby and opens its neighbours automatically.",
            PixelArt(
                "dddddd",
                "dkkkkd",
                "dkCkkd",
                "dkkGkd",
                "dkkkRd",
                "dddddd",
            )),
        TutorialPage("CHORDING", "When a number already has all of its mines flagged, tap it to dig all its other neighbours at once. Great for speed!", Icons.FLAG),
        TutorialPage("TIPS", "Start in the corners of open areas. If a 1 touches only one hidden square, that square is a mine. Never guess until you have to!", Icons.BULB),
    ),
    achievements = listOf(
        AchievementDef("first_win", "SWEEPER", "Clear a minefield."),
        AchievementDef("hard_win", "BOMB SQUAD", "Clear a HARD minefield."),
        AchievementDef("speed", "QUICK FINGERS", "Clear an EASY field in under 60 seconds."),
    ),
    factory = { MinesweeperSession(it) },
)
