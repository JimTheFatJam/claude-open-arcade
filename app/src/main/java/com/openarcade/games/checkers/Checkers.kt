package com.openarcade.games.checkers

import android.graphics.Canvas
import android.graphics.RectF
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
import com.openarcade.games.GridFit
import com.openarcade.games.HudItem
import com.openarcade.games.ScoreKind
import com.openarcade.games.TutorialPage
import com.openarcade.games.Worker
import com.openarcade.games.drawBoardFrame
import com.openarcade.games.toIntArray
import com.openarcade.games.toJsonArray
import com.openarcade.gfx.Gfx
import com.openarcade.gfx.Icons
import com.openarcade.gfx.Pal
import com.openarcade.gfx.PixelArt
import org.json.JSONObject
import kotlin.math.abs
import kotlin.random.Random

/** A checkers move: the squares visited (from first) and the squares captured. */
class CMove(val path: IntArray, val captured: IntArray) {
    val from get() = path[0]
    val to get() = path[path.size - 1]
}

/**
 * American checkers rules. Board index = row * 8 + col; side 1 (player, red) starts at the bottom
 * and moves toward row 0; side -1 (computer) moves toward row 7. Values: ±1 man, ±2 king.
 */
class CheckersEngine {
    val board = IntArray(64)
    var side = 1
    var quietPlies = 0   // plies since the last capture or man move (draw at 80)

    init { reset() }

    fun reset() {
        board.fill(0)
        for (r in 0 until 8) for (c in 0 until 8) if ((r + c) % 2 == 1) {
            if (r < 3) board[r * 8 + c] = -1
            if (r > 4) board[r * 8 + c] = 1
        }
        side = 1; quietPlies = 0
    }

    fun copy(): CheckersEngine { val e = CheckersEngine(); board.copyInto(e.board); e.side = side; e.quietPlies = quietPlies; return e }

    private fun dirs(v: Int): List<Int> = if (abs(v) == 2) listOf(-1, 1) else listOf(if (v > 0) -1 else 1)

    fun moves(): List<CMove> {
        val caps = ArrayList<CMove>()
        for (sq in 0 until 64) {
            val v = board[sq]
            if (v == 0 || (v > 0) != (side > 0)) continue
            jumps(sq, v, intArrayOf(sq), IntArray(0), caps)
        }
        if (caps.isNotEmpty()) return caps
        val out = ArrayList<CMove>()
        for (sq in 0 until 64) {
            val v = board[sq]
            if (v == 0 || (v > 0) != (side > 0)) continue
            val r = sq / 8; val c = sq % 8
            for (dr in dirs(v)) for (dc in intArrayOf(-1, 1)) {
                val rr = r + dr; val cc = c + dc
                if (rr in 0..7 && cc in 0..7 && board[rr * 8 + cc] == 0) out.add(CMove(intArrayOf(sq, rr * 8 + cc), IntArray(0)))
            }
        }
        return out
    }

    private fun jumps(sq: Int, v: Int, path: IntArray, caps: IntArray, out: ArrayList<CMove>) {
        val r = sq / 8; val c = sq % 8
        var extended = false
        for (dr in dirs(v)) for (dc in intArrayOf(-1, 1)) {
            val mr = r + dr; val mc = c + dc
            val lr = r + dr * 2; val lc = c + dc * 2
            if (lr !in 0..7 || lc !in 0..7) continue
            val mid = mr * 8 + mc; val land = lr * 8 + lc
            val mv = board[mid]
            if (mv == 0 || (mv > 0) == (v > 0) || caps.contains(mid)) continue
            if (board[land] != 0 && land != path[0]) continue
            extended = true
            val newPath = path + land
            val newCaps = caps + mid
            val crowned = abs(v) == 1 && ((v > 0 && lr == 0) || (v < 0 && lr == 7))
            if (crowned) out.add(CMove(newPath, newCaps))
            else {
                // temporarily lift the piece so it can pass back over its start square
                val saved = board[sq]
                board[sq] = 0
                jumps(land, v, newPath, newCaps, out)
                board[sq] = saved
            }
        }
        if (!extended && caps.isNotEmpty()) out.add(CMove(path, caps))
    }

    /** Applies [m]; returns undo info. */
    fun make(m: CMove): IntArray {
        val v = board[m.from]
        val undo = IntArray(4 + m.captured.size)
        undo[0] = v; undo[1] = quietPlies; undo[2] = side
        board[m.from] = 0
        for ((k, cs) in m.captured.withIndex()) { undo[4 + k] = board[cs]; board[cs] = 0 }
        var nv = v
        val tr = m.to / 8
        if (abs(v) == 1 && ((v > 0 && tr == 0) || (v < 0 && tr == 7))) nv = v * 2
        board[m.to] = nv
        undo[3] = nv
        quietPlies = if (m.captured.isNotEmpty() || abs(v) == 1) 0 else quietPlies + 1
        side = -side
        return undo
    }

    fun unmake(m: CMove, undo: IntArray) {
        board[m.to] = 0
        board[m.from] = undo[0]
        for ((k, cs) in m.captured.withIndex()) board[cs] = undo[4 + k]
        quietPlies = undo[1]; side = undo[2]
    }

    fun count(s: Int) = board.count { it != 0 && (it > 0) == (s > 0) }

    fun evaluate(): Int {
        var score = 0
        for (sq in 0 until 64) {
            val v = board[sq]
            if (v == 0) continue
            val r = sq / 8; val c = sq % 8
            var s = if (abs(v) == 2) 165 else 100
            if (abs(v) == 1) s += if (v > 0) (7 - r) * 4 else r * 4      // advancement
            if (c in 2..5 && r in 2..5) s += 6                              // centre
            if (abs(v) == 1 && ((v > 0 && r == 7) || (v < 0 && r == 0))) s += 8 // back-row guard
            score += if (v > 0) s else -s
        }
        return score * side
    }

    private var deadline = 0L

    private fun search(depth: Int, alpha0: Int, beta: Int, ply: Int): Int {
        val ms = moves()
        if (ms.isEmpty()) return -100000 + ply
        if (quietPlies >= 80) return 0
        if (depth <= 0 && ms[0].captured.isEmpty()) return evaluate()
        if (System.currentTimeMillis() > deadline) return evaluate()
        var alpha = alpha0
        for (m in ms) {
            val u = make(m)
            val score = -search(depth - 1, -beta, -alpha, ply + 1)
            unmake(m, u)
            if (score >= beta) return beta
            if (score > alpha) alpha = score
        }
        return alpha
    }

    fun bestMove(depth: Int, timeMs: Long, noise: Int, rnd: Random): CMove? {
        val ms = moves()
        if (ms.isEmpty()) return null
        if (ms.size == 1) return ms[0]
        deadline = System.currentTimeMillis() + timeMs
        var best = ms[0]
        for (d in 1..depth) {
            var bestScore = Int.MIN_VALUE
            var bestThis = ms[0]
            for (m in ms.shuffled(rnd)) {
                val u = make(m)
                var score = -search(d - 1, -1_000_000, 1_000_000, 1)
                unmake(m, u)
                if (noise > 0) score += rnd.nextInt(-noise, noise + 1)
                if (score > bestScore) { bestScore = score; bestThis = m }
            }
            if (System.currentTimeMillis() > deadline && d > 1) break
            best = bestThis
        }
        return best
    }
}

class CheckersSession(host: GameHost) : GameSession(host) {
    private val engine = CheckersEngine()
    private val grid = GridFit(8, 8)
    private var legal: List<CMove> = emptyList()
    private var selected = -1
    private var partial = ArrayList<Int>()
    private val ai = Worker<CMove?>()
    private var thinking = false
    private val history = ArrayList<Pair<CMove, IntArray>>()
    private var lastMove: CMove? = null
    private var anim = 1f
    private var t = 0f
    private var playerKings = 0

    private val depth = when (host.difficulty) { Difficulty.EASY -> 2; Difficulty.NORMAL -> 5; Difficulty.HARD -> 8 }
    private val noise = when (host.difficulty) { Difficulty.EASY -> 60; Difficulty.NORMAL -> 8; Difficulty.HARD -> 0 }

    override fun start(saved: JSONObject?) {
        engine.reset()
        if (saved != null) {
            saved.getJSONArray("board").toIntArray().copyInto(engine.board)
            engine.side = saved.getInt("side"); engine.quietPlies = saved.optInt("quiet")
        }
        legal = engine.moves()
        if (engine.side < 0) startAi()
    }

    override fun save(): JSONObject = JSONObject().put("board", engine.board.toJsonArray()).put("side", engine.side).put("quiet", engine.quietPlies)

    override fun onLayout() = grid.fit(area, px * 6)

    private fun startAi() {
        if (thinking || finished) return
        thinking = true
        val copy = engine.copy()
        val seed = host.rng.nextLong()
        ai.run { copy.bestMove(depth, 2500L, noise, Random(seed)) }
    }

    override fun update(dt: Float) {
        t += dt
        if (anim < 1f) anim = minOf(1f, anim + dt / 0.25f)
        if (thinking && anim >= 1f && !ai.busy) {
            thinking = false
            val m = ai.take() ?: legal.firstOrNull()
            if (m != null && !finished) {
                // map onto our own legal move list (same path)
                val mine = legal.firstOrNull { it.path.contentEquals(m.path) } ?: m
                apply(mine)
            }
        }
    }

    private fun apply(m: CMove) {
        val wasMan = abs(engine.board[m.from]) == 1
        val u = engine.make(m)
        history.add(m to u)
        lastMove = m
        anim = 0f
        selected = -1; partial.clear()
        host.sfx(if (m.captured.isNotEmpty()) Sfx.CAPTURE else Sfx.PLACE)
        if (wasMan && abs(engine.board[m.to]) == 2) {
            host.sfx(Sfx.LEVELUP)
            if (engine.board[m.to] > 0) { playerKings++; host.unlock("king") }
        }
        if (m.captured.size >= 3 && engine.board[m.to] > 0) host.unlock("triple")
        legal = engine.moves()
        when {
            legal.isEmpty() -> {
                if (engine.side < 0) {
                    host.unlock("first_win")
                    if (host.difficulty == Difficulty.HARD) host.unlock("hard_win")
                    win(detail = if (engine.count(-1) == 0) "ALL PIECES CAPTURED" else "COMPUTER IS BLOCKED", delay = 1.4f)
                } else lose(detail = if (engine.count(1) == 0) "ALL YOUR PIECES ARE GONE" else "YOU HAVE NO MOVES", delay = 1.4f)
            }
            engine.quietPlies >= 80 -> lose(detail = "40 MOVES WITHOUT PROGRESS", delay = 1.2f, title = "DRAW")
            engine.side < 0 -> startAi()
        }
    }

    override fun onTouch(e: MotionEvent) {
        if (e.actionMasked != MotionEvent.ACTION_DOWN || thinking || engine.side < 0 || anim < 1f) return
        val sq = grid.cellAt(e.x, e.y)
        if (sq < 0) return
        val v = engine.board[sq]
        if (v > 0 && partial.isEmpty()) {
            if (legal.any { it.from == sq }) { selected = sq; partial.clear(); partial.add(sq); host.sfx(Sfx.CLICK) }
            else { host.sfx(Sfx.ERROR); if (legal.isNotEmpty() && legal[0].captured.isNotEmpty()) host.toast("YOU MUST CAPTURE!") }
            return
        }
        if (selected < 0) return
        val prefixLen = partial.size
        val cands = legal.filter { it.path.size > prefixLen && it.path.take(prefixLen) == partial && it.path[prefixLen] == sq }
        if (cands.isEmpty()) {
            if (v > 0 && legal.any { it.from == sq } && partial.size == 1) { selected = sq; partial.clear(); partial.add(sq); host.sfx(Sfx.CLICK) }
            return
        }
        partial.add(sq)
        val done = cands.firstOrNull { it.path.size == partial.size }
        if (done != null && cands.size == 1) apply(done)
        else if (done != null && cands.all { it.path.size == partial.size }) apply(done)
        else host.sfx(Sfx.MOVE)
    }

    private fun undo() {
        if (finished || thinking) { if (thinking) { ai.cancel(); thinking = false } else return }
        // undo the computer's reply and the player's move
        var undone = 0
        while (history.isNotEmpty() && (undone == 0 || engine.side < 0)) {
            val (m, u) = history.removeAt(history.size - 1)
            engine.unmake(m, u)
            undone++
            if (engine.side > 0) break
        }
        legal = engine.moves()
        selected = -1; partial.clear(); lastMove = null; anim = 1f
        host.sfx(Sfx.BACK)
        if (engine.side < 0) startAi()
    }

    override fun hud() = listOf(
        HudItem("YOU", engine.count(1).toString(), Pal.RED),
        HudItem("TURN", if (thinking) "CPU" + ".".repeat(1 + (t * 3).toInt() % 3) else if (engine.side > 0) "YOU" else "CPU", Pal.YELLOW),
        HudItem("CPU", engine.count(-1).toString(), Pal.PEACH),
    )

    override fun actions() = listOf(
        GameAction("UNDO", Icons.UNDO, isEnabled = { history.isNotEmpty() && !finished }) { undo() },
        GameAction("RESIGN", Icons.FLAG, isEnabled = { !finished }) { host.sfx(Sfx.LOSE); lose(detail = "YOU RESIGNED", delay = 0.3f) },
    )

    override fun draw(c: Canvas) {
        val p = px
        val cs = grid.cell
        drawBoardFrame(c, grid.bounds, p, Pal.RED)
        val r = RectF()
        val lm = lastMove
        val movable = if (engine.side > 0 && !thinking) legal.map { it.from }.toSet() else emptySet()
        val prefixLen = partial.size
        val nextSteps = if (selected >= 0) legal.filter { it.path.size > prefixLen && it.path.take(prefixLen) == partial }.map { it.path[prefixLen] }.toSet() else emptySet()
        for (sq in 0 until 64) {
            grid.rect(sq % 8, sq / 8, r)
            val dark = (sq % 8 + sq / 8) % 2 == 1
            var col = if (dark) Pal.mix(Pal.BG2, Pal.DGREEN, 0.4f) else Pal.mix(Pal.PEACH, Pal.TAN, 0.4f)
            if (lm != null && sq in lm.path) col = Pal.mix(col, Pal.YELLOW, 0.3f)
            if (sq in partial) col = Pal.mix(col, Pal.GREEN, 0.4f)
            Gfx.rect(c, r, col)
            if (sq in nextSteps) Gfx.disc(c, r.centerX(), r.centerY(), cs * 0.15f, Pal.GREEN, maxOf(1f, cs / 16f))
        }
        val animTo = if (anim < 1f && lm != null) lm.to else -1
        for (sq in 0 until 64) {
            val v = engine.board[sq]
            if (v == 0 || sq == animTo) continue
            val cx = grid.cx((sq % 8).toFloat()); val cy = grid.cy((sq / 8).toFloat())
            val lift = if (sq == selected) cs * 0.06f else 0f
            drawPiece(c, cx, cy - lift, cs, v, sq in movable && selected < 0 && (t * 2).toInt() % 2 == 0)
        }
        if (animTo >= 0 && lm != null) {
            // move along the path segment by segment
            val segs = lm.path.size - 1
            val pos = anim * segs
            val k = minOf(segs - 1, pos.toInt())
            val f = pos - k
            val a = lm.path[k]; val b = lm.path[k + 1]
            val x = grid.cx((a % 8).toFloat()) + (grid.cx((b % 8).toFloat()) - grid.cx((a % 8).toFloat())) * f
            val y = grid.cy((a / 8).toFloat()) + (grid.cy((b / 8).toFloat()) - grid.cy((a / 8).toFloat())) * f
            drawPiece(c, x, y - cs * 0.15f * (1f - abs(f * 2 - 1)), cs, engine.board[animTo], false)
        }
    }

    private fun drawPiece(c: Canvas, cx: Float, cy: Float, cs: Float, v: Int, glow: Boolean) {
        val rad = cs * 0.38f
        val pp = maxOf(1f, cs / 16f)
        val base = if (v > 0) Pal.RED else Pal.PEACH
        if (glow) Gfx.disc(c, cx, cy, rad + pp * 1.5f, Pal.withAlpha(Pal.YELLOW, 160), pp)
        Gfx.disc(c, cx, cy + pp * 1.5f, rad, Pal.darker(base, 0.55f), pp)
        Gfx.disc(c, cx, cy, rad, base, pp)
        Gfx.disc(c, cx, cy, rad * 0.7f, Pal.darker(base, 0.15f), pp)
        Gfx.disc(c, cx, cy, rad * 0.55f, base, pp)
        if (abs(v) == 2) CROWN.drawFit(c, RectF(cx - rad * 0.6f, cy - rad * 0.5f, cx + rad * 0.6f, cy + rad * 0.5f))
    }

    companion object {
        val CROWN = PixelArt(
            "Y..Y..Y",
            "YY.Y.YY",
            "YYYYYYY",
            "YOYOYOY".replace('O', 'R'),
            "YYYYYYY",
        )
    }
}

val CheckersGame = GameDef(
    id = "checkers",
    title = "CHECKERS",
    category = Category.BOARD,
    tagline = "Jump and capture every enemy piece. Crown your kings!",
    description = "Classic American checkers (draughts) against the computer. Pieces move diagonally forward; captures are compulsory and " +
        "multi-jumps are allowed. Reach the far row to crown a king that can move backward too.",
    icon = PixelArt(
        "ttttttttmmmmmmmm",
        "ttttttttmmRRRRmm",
        "ttttttttmRWRRRRm",
        "ttttttttmRRRRRRm",
        "ttttttttmRRRRRRm",
        "ttttttttmrRRRRrm",
        "ttttttttmmrrrrmm",
        "ttttttttmmmmmmmm",
        "mmmmmmmmtttttttt",
        "mmeeeemmtttttttt",
        "meWeeeemtttttttt",
        "meeeeeemtttttttt",
        "meeeeeemtttttttt",
        "mteeeetmtttttttt",
        "mmttttmmtttttttt",
        "mmmmmmmmtttttttt",
    ),
    accent = Pal.RED,
    music = Music.BOARD,
    scoreKind = ScoreKind.WINS,
    tutorial = listOf(
        TutorialPage("OBJECTIVE", "Capture all of the computer's pieces, or leave it with no legal move. You play RED and move first, from the bottom of the board.",
            PixelArt(
                ".e.e.e.e",
                "e.e.e.e.",
                "........",
                "........",
                ".R.R.R.R",
                "R.R.R.R.",
            )),
        TutorialPage("CONTROLS", "TAP one of your pieces (movable ones blink), then TAP a green dot to move. For a multi-jump, tap each landing square in turn.", Icons.TAP),
        TutorialPage("RULES", "Pieces move one square diagonally forward. Jump over an enemy piece to capture it. If a capture is available you MUST take it, and you must keep jumping while you can.", PixelArt(
            "......",
            "....R.",
            "......",
            "..e...",
            ".R....",
            "......",
        )),
        TutorialPage("KINGS", "When a piece reaches the far row it is crowned KING. Kings can move and capture diagonally backward as well as forward. Crowning ends that move.", CheckersSession.CROWN),
        TutorialPage("TIPS", "Keep your back row filled to stop enemy kings. Trade pieces when you're ahead. Watch out for forced captures that pull your piece into a double-jump trap!", Icons.BULB),
    ),
    achievements = listOf(
        AchievementDef("first_win", "KING ME", "Beat the computer."),
        AchievementDef("hard_win", "DRAUGHTS MASTER", "Beat the computer on HARD."),
        AchievementDef("king", "CORONATION", "Crown a king."),
        AchievementDef("triple", "TRIPLE JUMP", "Capture 3 pieces in one move."),
    ),
    factory = { CheckersSession(it) },
)
