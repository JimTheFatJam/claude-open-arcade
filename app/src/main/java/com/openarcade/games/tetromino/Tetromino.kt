package com.openarcade.games.tetromino

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
import com.openarcade.games.drawBoardFrame
import com.openarcade.games.toIntArray
import com.openarcade.games.toJsonArray
import com.openarcade.gfx.Align
import com.openarcade.gfx.Gfx
import com.openarcade.gfx.Icons
import com.openarcade.gfx.Pal
import com.openarcade.gfx.PixelArt
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/** Tetromino shapes, rotation and SRS wall kicks. */
object Pieces {
    const val I = 0; const val O = 1; const val T = 2; const val S = 3; const val Z = 4; const val J = 5; const val L = 6
    val COLORS = intArrayOf(Pal.CYAN, Pal.YELLOW, Pal.PURPLE, Pal.GREEN, Pal.RED, Pal.BLUE, Pal.ORANGE)
    private val SIZE = intArrayOf(4, 2, 3, 3, 3, 3, 3)
    private val BASE = arrayOf(
        intArrayOf(0, 1, 1, 1, 2, 1, 3, 1),
        intArrayOf(0, 0, 1, 0, 0, 1, 1, 1),
        intArrayOf(1, 0, 0, 1, 1, 1, 2, 1),
        intArrayOf(1, 0, 2, 0, 0, 1, 1, 1),
        intArrayOf(0, 0, 1, 0, 1, 1, 2, 1),
        intArrayOf(0, 0, 0, 1, 1, 1, 2, 1),
        intArrayOf(2, 0, 0, 1, 1, 1, 2, 1),
    )

    /** cells[type][rotation] = 8 ints (x0,y0,...x3,y3) relative to the piece origin. */
    val cells: Array<Array<IntArray>> = Array(7) { t ->
        Array(4) { rot ->
            val out = BASE[t].copyOf()
            repeat(rot) {
                for (k in 0 until 4) {
                    val x = out[k * 2]; val y = out[k * 2 + 1]
                    out[k * 2] = SIZE[t] - 1 - y; out[k * 2 + 1] = x
                }
            }
            out
        }
    }

    // SRS kicks with y pointing UP as in the guideline; converted when applied.
    private val JLSTZ = mapOf(
        "01" to intArrayOf(0, 0, -1, 0, -1, 1, 0, -2, -1, -2), "10" to intArrayOf(0, 0, 1, 0, 1, -1, 0, 2, 1, 2),
        "12" to intArrayOf(0, 0, 1, 0, 1, -1, 0, 2, 1, 2), "21" to intArrayOf(0, 0, -1, 0, -1, 1, 0, -2, -1, -2),
        "23" to intArrayOf(0, 0, 1, 0, 1, 1, 0, -2, 1, -2), "32" to intArrayOf(0, 0, -1, 0, -1, -1, 0, 2, -1, 2),
        "30" to intArrayOf(0, 0, -1, 0, -1, -1, 0, 2, -1, 2), "03" to intArrayOf(0, 0, 1, 0, 1, 1, 0, -2, 1, -2),
    )
    private val IK = mapOf(
        "01" to intArrayOf(0, 0, -2, 0, 1, 0, -2, -1, 1, 2), "10" to intArrayOf(0, 0, 2, 0, -1, 0, 2, 1, -1, -2),
        "12" to intArrayOf(0, 0, -1, 0, 2, 0, -1, 2, 2, -1), "21" to intArrayOf(0, 0, 1, 0, -2, 0, 1, -2, -2, 1),
        "23" to intArrayOf(0, 0, 2, 0, -1, 0, 2, 1, -1, -2), "32" to intArrayOf(0, 0, -2, 0, 1, 0, -2, -1, 1, 2),
        "30" to intArrayOf(0, 0, 1, 0, -2, 0, 1, -2, -2, 1), "03" to intArrayOf(0, 0, -1, 0, 2, 0, -1, 2, 2, -1),
    )

    fun kicks(type: Int, from: Int, to: Int): IntArray = when (type) {
        O -> intArrayOf(0, 0)
        I -> IK["$from$to"]!!
        else -> JLSTZ["$from$to"]!!
    }
}

/** Board + active piece logic (no rendering), unit tested. */
class TetrisBoard {
    companion object { const val W = 10; const val H = 22; const val HIDDEN = 2 }
    var cells = IntArray(W * H)  // 0 empty, else piece type + 1
    var type = 0; var rot = 0; var px = 3; var py = 0

    fun fits(t: Int, r: Int, x: Int, y: Int): Boolean {
        val c = Pieces.cells[t][r]
        for (k in 0 until 4) {
            val cx = x + c[k * 2]; val cy = y + c[k * 2 + 1]
            if (cx < 0 || cx >= W || cy >= H) return false
            if (cy >= 0 && cells[cy * W + cx] != 0) return false
        }
        return true
    }

    fun spawn(t: Int): Boolean {
        type = t; rot = 0; px = if (t == Pieces.O) 4 else 3; py = 0
        return fits(type, rot, px, py)
    }

    fun move(dx: Int, dy: Int): Boolean {
        if (!fits(type, rot, px + dx, py + dy)) return false
        px += dx; py += dy
        return true
    }

    fun rotate(dir: Int): Boolean {
        val to = (rot + dir + 4) % 4
        val k = Pieces.kicks(type, rot, to)
        for (j in 0 until k.size / 2) {
            val dx = k[j * 2]; val dy = -k[j * 2 + 1]
            if (fits(type, to, px + dx, py + dy)) { px += dx; py += dy; rot = to; return true }
        }
        return false
    }

    fun ghostY(): Int { var y = py; while (fits(type, rot, px, y + 1)) y++; return y }

    /** Writes the piece into the board. Returns false if it locked entirely above the visible area. */
    fun lock(): Boolean {
        val c = Pieces.cells[type][rot]
        var visible = false
        for (k in 0 until 4) {
            val cx = px + c[k * 2]; val cy = py + c[k * 2 + 1]
            if (cy >= 0) cells[cy * W + cx] = type + 1
            if (cy >= HIDDEN) visible = true
        }
        return visible
    }

    fun fullRows(): List<Int> = (0 until H).filter { r -> (0 until W).all { cells[r * W + it] != 0 } }

    fun clearRows(rows: List<Int>) {
        if (rows.isEmpty()) return
        val next = IntArray(W * H)
        var dst = H - 1
        for (r in H - 1 downTo 0) {
            if (r in rows) continue
            for (c in 0 until W) next[dst * W + c] = cells[r * W + c]
            dst--
        }
        cells = next
    }
}

class TetrominoSession(host: GameHost) : GameSession(host) {
    override val realtime = true
    private val b = TetrisBoard()
    private var bag = ArrayList<Int>()
    private val next = ArrayList<Int>()
    private var hold = -1
    private var holdUsed = false
    private var score = 0
    private var lines = 0
    private var level = when (host.difficulty) { Difficulty.EASY -> 1; Difficulty.NORMAL -> 4; Difficulty.HARD -> 8 }
    private val startLevel = level
    private var fallT = 0f
    private var lockT = 0f
    private var lockResets = 0
    private var clearing: List<Int> = emptyList()
    private var clearT = 0f
    private var t = 0f
    private var popText = ""
    private var popT = 9f

    // layout
    private var cell = 0f
    private var bx = 0f
    private var by = 0f
    private var sideX = 0f

    // touch
    private var tDownX = 0f; private var tDownY = 0f; private var tLastX = 0f; private var tLastY = 0f
    private var tDownTime = 0L; private var accX = 0f; private var accY = 0f; private var tMoved = false
    private var tDown = false

    override fun start(saved: JSONObject?) {
        if (saved != null) {
            b.cells = saved.getJSONArray("cells").toIntArray()
            bag = ArrayList(saved.getJSONArray("bag").toIntArray().toList())
            next.clear(); next.addAll(saved.getJSONArray("next").toIntArray().toList())
            hold = saved.getInt("hold"); score = saved.getInt("score"); lines = saved.getInt("lines"); level = saved.getInt("level")
            b.spawn(saved.getInt("type"))
        } else {
            while (next.size < 4) next.add(fromBag())
            b.spawn(next.removeAt(0)); next.add(fromBag())
        }
    }

    override fun save(): JSONObject = JSONObject()
        .put("cells", b.cells.toJsonArray()).put("bag", bag.toIntArray().toJsonArray())
        .put("next", next.toIntArray().toJsonArray()).put("hold", hold)
        .put("score", score).put("lines", lines).put("level", level).put("type", b.type)

    private fun fromBag(): Int {
        if (bag.isEmpty()) { bag = ArrayList((0 until 7).toList()); bag.shuffle(host.rng) }
        return bag.removeAt(bag.size - 1)
    }

    override fun onLayout() {
        val p = px
        val visRows = TetrisBoard.H - TetrisBoard.HIDDEN
        cell = floor(minOf((area.height() - p * 4) / visRows, (area.width() - p * 8) / (TetrisBoard.W + 5.5f)))
        val total = cell * (TetrisBoard.W + 5.5f)
        bx = floor(area.centerX() - total / 2)
        by = floor(area.centerY() - cell * visRows / 2)
        sideX = bx + cell * TetrisBoard.W + cell * 0.8f
    }

    private fun gravity(): Float = (0.8f - (level - 1) * 0.007f).toDouble().pow((level - 1).toDouble()).toFloat().coerceAtLeast(0.02f)

    override fun update(dt: Float) {
        t += dt
        popT += dt
        if (finished) return
        if (clearing.isNotEmpty()) {
            clearT += dt
            if (clearT > 0.3f) {
                b.clearRows(clearing)
                clearing = emptyList()
                spawnNext()
            }
            return
        }
        fallT += dt
        val g = gravity()
        while (fallT >= g) {
            fallT -= g
            if (!b.move(0, 1)) break else lockT = 0f
        }
        if (!b.fits(b.type, b.rot, b.px, b.py + 1)) {
            lockT += dt
            if (lockT >= 0.5f) lockPiece()
        }
    }

    private fun onGroundReset() {
        if (!b.fits(b.type, b.rot, b.px, b.py + 1) && lockResets < 15) { lockT = 0f; lockResets++ }
    }

    private fun lockPiece() {
        val visible = b.lock()
        host.sfx(Sfx.PLACE)
        holdUsed = false
        lockT = 0f; lockResets = 0; fallT = 0f
        if (!visible) { gameOver(); return }
        val full = b.fullRows()
        if (full.isNotEmpty()) {
            clearing = full; clearT = 0f
            val n = full.size
            val pts = intArrayOf(0, 100, 300, 500, 800)[n] * level
            score += pts
            lines += n
            popText = when (n) { 1 -> "SINGLE"; 2 -> "DOUBLE"; 3 -> "TRIPLE"; else -> "QUAD!" }
            popT = 0f
            host.sfx(if (n == 4) Sfx.POWER else Sfx.LINE)
            if (n == 4) { host.unlock("quad"); host.vibrate(60) }
            val newLevel = maxOf(startLevel, startLevel + lines / 10)
            if (newLevel > level) { level = newLevel; host.sfx(Sfx.LEVELUP); popText = "LEVEL $level"; popT = 0f }
            if (lines >= 100) host.unlock("lines100")
            if (score >= 20000) host.unlock("score20k")
        } else spawnNext()
    }

    private fun spawnNext() {
        if (!b.spawn(next.removeAt(0))) { next.add(0, b.type); gameOver(); return }
        next.add(fromBag())
    }

    private fun gameOver() {
        host.sfx(Sfx.EXPLODE)
        lose(score, "LINES $lines  LEVEL $level", 1.2f)
    }

    private fun doHold() {
        if (holdUsed || clearing.isNotEmpty() || finished) return
        val cur = b.type
        if (hold < 0) { hold = cur; spawnNext() } else { val h = hold; hold = cur; b.spawn(h) }
        holdUsed = true
        lockT = 0f; lockResets = 0
        host.sfx(Sfx.SELECT)
    }

    private fun hardDrop() {
        if (clearing.isNotEmpty() || finished) return
        val gy = b.ghostY()
        score += (gy - b.py) * 2
        b.py = gy
        host.sfx(Sfx.DROP)
        host.vibrate(25)
        lockPiece()
    }

    private fun shift(dx: Int) { if (b.move(dx, 0)) { host.sfx(Sfx.MOVE); onGroundReset() } }
    private fun rotate(dir: Int) { if (b.rotate(dir)) { host.sfx(Sfx.ROTATE); onGroundReset() } }
    private fun softDrop() { if (b.move(0, 1)) { score += 1; fallT = 0f } }

    override fun onTouch(e: MotionEvent) {
        if (clearing.isNotEmpty()) return
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tDown = true; tMoved = false
                tDownX = e.x; tDownY = e.y; tLastX = e.x; tLastY = e.y
                accX = 0f; accY = 0f; tDownTime = e.eventTime
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tDown) return
                accX += e.x - tLastX
                accY += e.y - tLastY
                tLastX = e.x; tLastY = e.y
                val step = cell * 0.9f
                while (accX > step) { shift(1); accX -= step; tMoved = true }
                while (accX < -step) { shift(-1); accX += step; tMoved = true }
                if (abs(e.y - tDownY) > abs(e.x - tDownX)) {
                    while (accY > step) { softDrop(); accY -= step; tMoved = true }
                }
                if (abs(e.x - tDownX) > px * 4 || abs(e.y - tDownY) > px * 4) tMoved = true
            }
            MotionEvent.ACTION_UP -> {
                if (!tDown) return
                tDown = false
                val dtMs = (e.eventTime - tDownTime).coerceAtLeast(1)
                val dy = e.y - tDownY
                val dx = e.x - tDownX
                val vy = dy / dtMs // px per ms
                if (dy > cell * 2 && abs(dy) > abs(dx) * 1.5f && vy > cell / 60f) hardDrop()
                else if (dy < -cell * 2 && abs(dy) > abs(dx) * 1.5f) doHold()
                else if (!tMoved) rotate(if (e.x < area.left + area.width() * 0.2f) -1 else 1)
            }
            MotionEvent.ACTION_CANCEL -> tDown = false
        }
    }

    override fun onKey(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> shift(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> shift(1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S -> softDrop()
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_W -> rotate(1)
            KeyEvent.KEYCODE_Z -> rotate(-1)
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DPAD_CENTER -> hardDrop()
            KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_SHIFT_LEFT -> doHold()
            else -> return false
        }
        return true
    }

    override fun hud() = listOf(
        HudItem("SCORE", score.toString(), Pal.YELLOW),
        HudItem("LINES", lines.toString()),
        HudItem("LEVEL", level.toString(), Pal.CYAN),
    )

    override fun actions() = listOf(
        GameAction("HOLD", null) { doHold() },
        GameAction("ROTATE", Icons.RESTART) { rotate(1) },
        GameAction("DROP", null) { hardDrop() },
    )

    private fun drawBlock(c: Canvas, x: Float, y: Float, s: Float, type: Int, alpha: Int = 255) {
        val col = Pieces.COLORS[type]
        val r = RectF(x, y, x + s, y + s)
        if (alpha < 255) {
            Gfx.frame(c, r, maxOf(1f, s / 8f), Pal.withAlpha(col, alpha))
            return
        }
        Gfx.bevel(c, r, col, maxOf(1f, (s / 7f).toInt().toFloat()))
        val hs = s * 0.2f
        Gfx.rect(c, x + hs, y + hs, x + hs * 2, y + hs * 2, Pal.lighter(col, 0.6f))
    }

    private fun drawMini(c: Canvas, type: Int, cx: Float, cy: Float, s: Float, alpha: Int = 255) {
        val cells = Pieces.cells[type][0]
        var minX = 9; var maxX = -9; var minY = 9; var maxY = -9
        for (k in 0 until 4) { minX = minOf(minX, cells[k * 2]); maxX = maxOf(maxX, cells[k * 2]); minY = minOf(minY, cells[k * 2 + 1]); maxY = maxOf(maxY, cells[k * 2 + 1]) }
        val w = (maxX - minX + 1) * s; val h = (maxY - minY + 1) * s
        for (k in 0 until 4) {
            val x = cx - w / 2 + (cells[k * 2] - minX) * s
            val y = cy - h / 2 + (cells[k * 2 + 1] - minY) * s
            if (alpha < 255) Gfx.rect(c, x, y, x + s, y + s, Pal.withAlpha(Pieces.COLORS[type], alpha)) else drawBlock(c, x, y, s, type)
        }
    }

    override fun draw(c: Canvas) {
        val p = px
        val visRows = TetrisBoard.H - TetrisBoard.HIDDEN
        val board = RectF(bx, by, bx + cell * TetrisBoard.W, by + cell * visRows)
        drawBoardFrame(c, board, p, Pal.CYAN)
        Gfx.rect(c, board, Pal.BLACK)
        for (x in 1 until TetrisBoard.W) Gfx.rect(c, bx + x * cell, by, bx + x * cell + 1, board.bottom, Pal.withAlpha(Pal.BG2, 140))
        for (y in 1 until visRows) Gfx.rect(c, bx, by + y * cell, board.right, by + y * cell + 1, Pal.withAlpha(Pal.BG2, 140))
        // settled blocks
        val flashOn = clearing.isNotEmpty() && ((clearT * 16).toInt() % 2 == 0)
        for (r in TetrisBoard.HIDDEN until TetrisBoard.H) {
            val y = by + (r - TetrisBoard.HIDDEN) * cell
            val isClearing = r in clearing
            for (cc in 0 until TetrisBoard.W) {
                val v = b.cells[r * TetrisBoard.W + cc]
                if (v == 0) continue
                if (isClearing) {
                    val k = (clearT / 0.3f).coerceIn(0f, 1f)
                    val shrink = cell * k * 0.5f
                    Gfx.rect(c, bx + cc * cell + shrink, y + shrink, bx + (cc + 1) * cell - shrink, y + cell - shrink, if (flashOn) Pal.WHITE else Pieces.COLORS[v - 1])
                } else if (finished) {
                    Gfx.bevel(c, RectF(bx + cc * cell, y, bx + (cc + 1) * cell, y + cell), Pal.DGREY, maxOf(1f, cell / 7f))
                } else drawBlock(c, bx + cc * cell, y, cell, v - 1)
            }
        }
        // ghost + active
        if (clearing.isEmpty() && !finished) {
            val pc = Pieces.cells[b.type][b.rot]
            val gy = b.ghostY()
            for (k in 0 until 4) {
                val cx = b.px + pc[k * 2]; val cy = gy + pc[k * 2 + 1]
                if (cy >= TetrisBoard.HIDDEN) drawBlock(c, bx + cx * cell, by + (cy - TetrisBoard.HIDDEN) * cell, cell, b.type, 110)
            }
            val lockFlash = lockT > 0f && ((t * 12).toInt() % 2 == 0)
            for (k in 0 until 4) {
                val cx = b.px + pc[k * 2]; val cy = b.py + pc[k * 2 + 1]
                if (cy >= TetrisBoard.HIDDEN) {
                    drawBlock(c, bx + cx * cell, by + (cy - TetrisBoard.HIDDEN) * cell, cell, b.type)
                    if (lockFlash) Gfx.rect(c, bx + cx * cell, by + (cy - TetrisBoard.HIDDEN) * cell, bx + (cx + 1) * cell, by + (cy - TetrisBoard.HIDDEN + 1) * cell, Pal.withAlpha(Pal.WHITE, 60))
                }
            }
        }
        // side panel: hold + next
        val sw = cell * 4.4f
        val ts = maxOf(1f, cell / 9f)
        Gfx.text(c, "HOLD", sideX + sw / 2, by, ts, Pal.GREY, Align.CENTER)
        val holdBox = RectF(sideX, by + ts * 10, sideX + sw, by + ts * 10 + cell * 3)
        Gfx.rect(c, holdBox, Pal.withAlpha(Pal.BLACK, 200)); Gfx.frame(c, holdBox, maxOf(1f, p / 2), Pal.BG3)
        if (hold >= 0) drawMini(c, hold, holdBox.centerX(), holdBox.centerY(), cell * 0.7f, if (holdUsed) 90 else 255)
        var ny = holdBox.bottom + cell
        Gfx.text(c, "NEXT", sideX + sw / 2, ny, ts, Pal.GREY, Align.CENTER)
        ny += ts * 10
        val nextBox = RectF(sideX, ny, sideX + sw, ny + cell * 8.5f)
        Gfx.rect(c, nextBox, Pal.withAlpha(Pal.BLACK, 200)); Gfx.frame(c, nextBox, maxOf(1f, p / 2), Pal.BG3)
        for (k in 0 until 3) {
            val s = if (k == 0) cell * 0.75f else cell * 0.55f
            drawMini(c, next[k], nextBox.centerX(), nextBox.top + cell * 1.4f + k * cell * 2.8f, s)
        }
        if (popT < 1.2f) {
            val a = (255 * (1f - popT / 1.2f)).toInt()
            Gfx.text(c, popText, board.centerX(), board.centerY() - popT * cell * 2, maxOf(1f, cell / 5f), Pal.withAlpha(Pal.YELLOW, a), Align.CENTER, Pal.withAlpha(Pal.BLACK, a))
        }
    }
}

val TetrominoGame = GameDef(
    id = "tetromino",
    title = "TETROMINO",
    category = Category.ARCADE,
    tagline = "Rotate and drop falling blocks to clear lines.",
    description = "The legendary falling-block puzzle. Fit the seven shapes together to complete horizontal lines and clear them. " +
        "Features hold, a 3-piece preview, ghost piece and modern rotation. How long can you last as the speed climbs?",
    icon = PixelArt(
        "................",
        "......PP........",
        ".....PPPP.......",
        "................",
        "................",
        "..............C.",
        "..............C.",
        "..............C.",
        "YY............C.",
        "YY..RR......BBB.",
        "GG.RRBB..OOOBBB.",
        "GGGRRBBOOOOGGBB.",
        "GGGYYBBOOCCGGBB.",
        "PPPYYRRRCCCCGGG.",
        "PPPPPRRRCCCCGGG.",
        "................",
    ),
    accent = Pal.CYAN,
    music = Music.ARCADE,
    scoreKind = ScoreKind.HIGH_SCORE,
    tutorial = listOf(
        TutorialPage("OBJECTIVE", "Blocks fall from the top. Arrange them to fill complete horizontal lines - full lines disappear and score points. If the stack reaches the top, it's game over!",
            PixelArt(
                "....PPP...",
                ".....P....",
                "..........",
                "Y.........",
                "YY..RR..BB",
                "YCCCCRRBBB",
                "GGGOOOOGGB",
            )),
        TutorialPage("MOVING", "DRAG left or right to move the falling piece. DRAG down slowly to soft drop. FLICK down quickly to hard drop it instantly.", Icons.DRAG),
        TutorialPage("ROTATE & HOLD", "TAP to rotate clockwise (tap the far left edge to rotate counter-clockwise). SWIPE UP to put the piece in HOLD and swap it out later. Buttons below do the same.", Icons.TAP),
        TutorialPage("SCORING", "Clear several lines at once for big points: 1 line = 100, 2 = 300, 3 = 500, 4 (a QUAD) = 800, all times your level. Every 10 lines the level - and the speed - goes up.", Icons.STAR),
        TutorialPage("TIPS", "Keep the stack flat and leave one column open for the long I-piece to score QUADs. Use the ghost outline to see where a piece will land, and HOLD the I-piece for emergencies.", Icons.BULB),
    ),
    achievements = listOf(
        AchievementDef("quad", "QUAD CLEAR", "Clear 4 lines with one piece."),
        AchievementDef("lines100", "CENTURY", "Clear 100 lines in one game."),
        AchievementDef("score20k", "HIGH STACKER", "Score 20,000 points in one game."),
    ),
    factory = { TetrominoSession(it) },
)
