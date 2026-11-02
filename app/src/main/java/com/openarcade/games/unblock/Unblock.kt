package com.openarcade.games.unblock

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
import com.openarcade.gfx.Align
import com.openarcade.gfx.Gfx
import com.openarcade.gfx.Icons
import com.openarcade.gfx.Pal
import com.openarcade.gfx.PixelArt
import org.json.JSONObject
import kotlin.math.roundToInt
import kotlin.random.Random

/** A sliding block: fixed lane (row for horizontal, column for vertical), variable position. */
class Block(val horizontal: Boolean, val lane: Int, val len: Int)

/**
 * Rules + solver for the 6x6 sliding block puzzle. Block 0 is the key block on row 2 that must
 * reach the exit on the right edge. A move slides one block any distance.
 */
class UnblockPuzzle(val blocks: List<Block>) {
    companion object {
        const val N = 6
        const val EXIT_ROW = 2
        const val SOLVED_POS = N - 2

        /** Generates a puzzle whose optimal solution length is close to the requested range. */
        fun generate(minMoves: Int, maxMoves: Int, rnd: Random, attempts: Int = 120): Pair<UnblockPuzzle, IntArray> {
            var best: Triple<UnblockPuzzle, IntArray, Int>? = null
            repeat(attempts) {
                val gen = randomLayout(rnd) ?: return@repeat
                val (puz, start) = gen
                val dist = puz.distanceMap(start) ?: return@repeat
                if (dist.isEmpty()) return@repeat
                val maxD = dist.values.maxOrNull() ?: 0
                val want = if (maxMoves >= 99) maxD else minOf(maxD, minMoves + rnd.nextInt(maxOf(1, maxMoves - minMoves + 1)))
                val candidates = dist.filter { it.value == want }.keys.toList()
                if (candidates.isNotEmpty()) {
                    val pick = candidates[rnd.nextInt(candidates.size)]
                    val pos = puz.decode(pick)
                    if (want in minMoves..maxMoves) return puz to pos
                    if (best == null || kotlin.math.abs(want - minMoves) < kotlin.math.abs(best!!.third - minMoves)) best = Triple(puz, pos, want)
                }
            }
            val b = best ?: return generate(minMoves, maxMoves, rnd, attempts)
            return b.first to b.second
        }

        private fun randomLayout(rnd: Random): Pair<UnblockPuzzle, IntArray>? {
            val occ = Array(N) { BooleanArray(N) }
            val blocks = ArrayList<Block>()
            val pos = ArrayList<Int>()
            val keyPos = rnd.nextInt(0, 3)
            blocks.add(Block(true, EXIT_ROW, 2)); pos.add(keyPos)
            occ[EXIT_ROW][keyPos] = true; occ[EXIT_ROW][keyPos + 1] = true
            val count = 9 + rnd.nextInt(5)
            var tries = 0
            while (blocks.size < count && tries < 300) {
                tries++
                val h = rnd.nextBoolean()
                val len = if (rnd.nextFloat() < 0.25f) 3 else 2
                val lane = rnd.nextInt(N)
                if (h && lane == EXIT_ROW) continue
                val p = rnd.nextInt(N - len + 1)
                var free = true
                for (k in 0 until len) { val r = if (h) lane else p + k; val c = if (h) p + k else lane; if (occ[r][c]) free = false }
                if (!free) continue
                for (k in 0 until len) { val r = if (h) lane else p + k; val c = if (h) p + k else lane; occ[r][c] = true }
                blocks.add(Block(h, lane, len)); pos.add(p)
            }
            return UnblockPuzzle(blocks) to pos.toIntArray()
        }
    }

    fun encode(pos: IntArray): Long { var k = 0L; for (p in pos) k = k * 8 + p; return k }

    fun decode(key: Long): IntArray {
        val out = IntArray(blocks.size)
        var k = key
        for (i in blocks.indices.reversed()) { out[i] = (k % 8).toInt(); k /= 8 }
        return out
    }

    fun occupancy(pos: IntArray, skip: Int = -1): Array<BooleanArray> {
        val occ = Array(N) { BooleanArray(N) }
        for (i in blocks.indices) {
            if (i == skip) continue
            val b = blocks[i]
            for (k in 0 until b.len) {
                if (b.horizontal) occ[b.lane][pos[i] + k] = true else occ[pos[i] + k][b.lane] = true
            }
        }
        return occ
    }

    /** The range [min, max] block [i] can slide to given the other blocks. */
    fun range(pos: IntArray, i: Int): IntRange {
        val occ = occupancy(pos, i)
        val b = blocks[i]
        fun free(p: Int) = (0 until b.len).all { k -> if (b.horizontal) !occ[b.lane][p + k] else !occ[p + k][b.lane] }
        var lo = pos[i]
        while (lo - 1 >= 0 && free(lo - 1)) lo--
        var hi = pos[i]
        while (hi + 1 + b.len - 1 < N && free(hi + 1)) hi++
        return lo..hi
    }

    fun solved(pos: IntArray) = pos[0] == SOLVED_POS

    fun neighbors(pos: IntArray): List<IntArray> {
        val out = ArrayList<IntArray>()
        for (i in blocks.indices) {
            val r = range(pos, i)
            for (p in r) if (p != pos[i]) out.add(pos.copyOf().also { it[i] = p })
        }
        return out
    }

    /** Distance-to-solution for every state reachable from [start]; null if unsolvable. */
    fun distanceMap(start: IntArray, cap: Int = 40000): Map<Long, Int>? {
        val seen = HashMap<Long, IntArray>()
        val queue = ArrayDeque<IntArray>()
        seen[encode(start)] = start
        queue.add(start)
        while (queue.isNotEmpty() && seen.size < cap) {
            val s = queue.removeFirst()
            for (nb in neighbors(s)) {
                val k = encode(nb)
                if (!seen.containsKey(k)) { seen[k] = nb; queue.add(nb) }
            }
        }
        if (seen.size >= cap) return null
        val dist = HashMap<Long, Int>()
        val q2 = ArrayDeque<Long>()
        for ((k, s) in seen) if (solved(s)) { dist[k] = 0; q2.add(k) }
        if (q2.isEmpty()) return null
        while (q2.isNotEmpty()) {
            val k = q2.removeFirst()
            val d = dist[k]!!
            for (nb in neighbors(seen[k]!!)) {
                val nk = encode(nb)
                if (!dist.containsKey(nk)) { dist[nk] = d + 1; q2.add(nk) }
            }
        }
        return dist
    }

    fun minMoves(start: IntArray): Int = distanceMap(start)?.get(encode(start)) ?: -1
}

class UnblockSession(host: GameHost) : GameSession(host) {
    private val range = when (host.difficulty) {
        Difficulty.EASY -> 4 to 7
        Difficulty.NORMAL -> 9 to 14
        Difficulty.HARD -> 16 to 99
    }
    private var puzzle: UnblockPuzzle? = null
    private var start = IntArray(0)
    private var pos = IntArray(0)
    private var optimal = 0
    private var moves = 0
    private val history = ArrayList<IntArray>()
    private val worker = Worker<Triple<UnblockPuzzle, IntArray, Int>>()
    private val grid = GridFit(6, 6)
    private var t = 0f
    private var exitAnim = -1f

    // drag
    private var dragIdx = -1
    private var dragRange = 0..0
    private var dragOffset = 0f
    private var dragPos = 0f

    override fun start(saved: JSONObject?) {
        if (saved != null) {
            val lanes = saved.getJSONArray("lanes").toIntArray()
            val hs = saved.getJSONArray("h").toIntArray()
            val lens = saved.getJSONArray("lens").toIntArray()
            puzzle = UnblockPuzzle(lanes.indices.map { Block(hs[it] == 1, lanes[it], lens[it]) })
            start = saved.getJSONArray("start").toIntArray()
            pos = saved.getJSONArray("pos").toIntArray()
            optimal = saved.getInt("optimal")
            moves = saved.getInt("moves")
        } else {
            val seed = host.rng.nextLong()
            worker.run {
                val (p, s) = UnblockPuzzle.generate(range.first, range.second, Random(seed))
                Triple(p, s, p.minMoves(s))
            }
        }
    }

    override fun save(): JSONObject? {
        val p = puzzle ?: return null
        return JSONObject()
            .put("lanes", p.blocks.map { it.lane }.toIntArray().toJsonArray())
            .put("h", p.blocks.map { if (it.horizontal) 1 else 0 }.toIntArray().toJsonArray())
            .put("lens", p.blocks.map { it.len }.toIntArray().toJsonArray())
            .put("start", start.toJsonArray()).put("pos", pos.toJsonArray())
            .put("optimal", optimal).put("moves", moves)
    }

    override fun onLayout() {
        val p = px
        val r = RectF(area)
        r.right -= p * 10 // room for the exit gate
        grid.fit(r, p * 4)
    }

    override fun update(dt: Float) {
        t += dt
        if (puzzle == null) {
            worker.take()?.let { (p, s, opt) -> puzzle = p; start = s; pos = s.copyOf(); optimal = opt }
        }
        if (exitAnim >= 0f) exitAnim += dt
    }

    private fun blockRect(i: Int, out: RectF, posOverride: Float? = null) {
        val b = puzzle!!.blocks[i]
        val pp = posOverride ?: pos[i].toFloat()
        val inset = maxOf(1f, grid.cell * 0.06f)
        if (b.horizontal) out.set(grid.ox + pp * grid.cell + inset, grid.y(b.lane) + inset,
            grid.ox + (pp + b.len) * grid.cell - inset, grid.y(b.lane) + grid.cell - inset)
        else out.set(grid.x(b.lane) + inset, grid.oy + pp * grid.cell + inset,
            grid.x(b.lane) + grid.cell - inset, grid.oy + (pp + b.len) * grid.cell - inset)
    }

    override fun onTouch(e: MotionEvent) {
        val puz = puzzle ?: return
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val r = RectF()
                for (i in puz.blocks.indices) {
                    blockRect(i, r)
                    if (r.contains(e.x, e.y)) {
                        dragIdx = i
                        dragRange = puz.range(pos, i)
                        val b = puz.blocks[i]
                        val fingerCells = if (b.horizontal) (e.x - grid.ox) / grid.cell else (e.y - grid.oy) / grid.cell
                        dragOffset = fingerCells - pos[i]
                        dragPos = pos[i].toFloat()
                        host.sfx(Sfx.CLICK)
                        return
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragIdx < 0) return
                val b = puz.blocks[dragIdx]
                val fingerCells = if (b.horizontal) (e.x - grid.ox) / grid.cell else (e.y - grid.oy) / grid.cell
                dragPos = (fingerCells - dragOffset).coerceIn(dragRange.first.toFloat(), dragRange.last.toFloat())
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragIdx < 0) return
                val np = dragPos.roundToInt().coerceIn(dragRange.first, dragRange.last)
                if (np != pos[dragIdx]) {
                    history.add(pos.copyOf())
                    pos[dragIdx] = np
                    moves++
                    host.sfx(Sfx.PLACE)
                    if (puz.solved(pos)) solvedNow()
                }
                dragIdx = -1
            }
        }
    }

    private fun solvedNow() {
        exitAnim = 0f
        host.sfx(Sfx.POWER)
        host.unlock("first_win")
        if (moves <= optimal) host.unlock("perfect")
        if (host.difficulty == Difficulty.HARD) host.unlock("hard_win")
        val stars = when { moves <= optimal -> 3; moves <= optimal + optimal / 2 + 2 -> 2; else -> 1 }
        val s = "\u0002".repeat(stars)
        win(detail = "MOVES $moves  PAR $optimal  $s", delay = 1.3f)
    }

    private fun undo() {
        val h = history.removeLastOrNull() ?: return
        pos = h; moves++
        host.sfx(Sfx.BACK)
    }

    private fun reset() {
        if (pos.contentEquals(start)) return
        history.add(pos.copyOf())
        pos = start.copyOf(); moves = 0; history.clear()
        host.sfx(Sfx.BACK)
    }

    override fun hud() = listOf(
        HudItem("MOVES", moves.toString(), if (moves <= optimal) Pal.WHITE else Pal.ORANGE),
        HudItem("PAR", if (puzzle == null) "-" else optimal.toString(), Pal.YELLOW),
        HudItem("SOLVED", host.bestScore().toString()),
    )

    override fun actions() = listOf(
        GameAction("UNDO", Icons.UNDO, isEnabled = { history.isNotEmpty() && !finished }) { undo() },
        GameAction("RESET", Icons.RESTART, isEnabled = { !finished }) { reset() },
    )

    override fun draw(c: Canvas) {
        val p = px
        val puz = puzzle
        if (puz == null) {
            Gfx.text(c, "BUILDING PUZZLE" + ".".repeat(1 + (t * 3).toInt() % 3), area.centerX(), area.centerY(), px, Pal.YELLOW, Align.CENTER)
            return
        }
        val b = grid.bounds
        drawBoardFrame(c, b, p, Pal.BROWN)
        Gfx.rect(c, b, Pal.DBROWN)
        for (i in 0 until 36) {
            val r = grid.rect(i % 6, i / 6, RectF(), maxOf(1f, grid.cell * 0.03f))
            Gfx.rect(c, r, Pal.mix(Pal.DBROWN, Pal.BLACK, 0.25f))
        }
        // exit gate
        val ey0 = grid.y(UnblockPuzzle.EXIT_ROW)
        Gfx.rect(c, b.right - p, ey0, b.right + p * 3, ey0 + grid.cell, Pal.mix(Pal.DBROWN, Pal.BLACK, 0.25f))
        val blinkOn = ((t * 2).toInt() % 2) == 0
        for (k in 0..2) {
            val ax = b.right + p * 4 + k * p * 2
            Gfx.rect(c, ax, ey0 + grid.cell / 2 - p, ax + p, ey0 + grid.cell / 2 + p, if (blinkOn) Pal.GREEN else Pal.DGREEN)
        }
        val r = RectF()
        for (i in puz.blocks.indices) {
            val bl = puz.blocks[i]
            var pp: Float? = if (i == dragIdx) dragPos else null
            if (i == 0 && exitAnim >= 0f) pp = pos[0] + exitAnim * exitAnim * 14f
            blockRect(i, r, pp)
            val col = if (i == 0) Pal.RED else when ((bl.lane + bl.len + if (bl.horizontal) 0 else 3) % 4) {
                0 -> Pal.TAN; 1 -> Pal.ORANGE; 2 -> Pal.mix(Pal.TAN, Pal.BROWN, 0.4f); else -> Pal.PEACH
            }
            Gfx.rect(c, r.left + p, r.top + p, r.right + p, r.bottom + p, Pal.withAlpha(Pal.BLACK, 120))
            Gfx.bevel(c, r, col, maxOf(1f, grid.cell * 0.08f))
            // wood grain
            val gc = Pal.darker(col, 0.15f)
            if (bl.horizontal) {
                Gfx.rect(c, r.left + grid.cell * 0.25f, r.centerY() - p / 2, r.right - grid.cell * 0.25f, r.centerY() + p / 2, gc)
            } else {
                Gfx.rect(c, r.centerX() - p / 2, r.top + grid.cell * 0.25f, r.centerX() + p / 2, r.bottom - grid.cell * 0.25f, gc)
            }
            if (i == 0) {
                // key block face
                val s = grid.cell * 0.12f
                Gfx.rect(c, r.right - grid.cell * 0.45f, r.centerY() - s, r.right - grid.cell * 0.45f + s * 1.5f, r.centerY() + s, Pal.WHITE)
            }
            if (i == dragIdx) Gfx.frame(c, r, maxOf(1f, p / 2), Pal.YELLOW)
        }
    }
}

val UnblockGame = GameDef(
    id = "unblock",
    title = "UNBLOCK",
    category = Category.PUZZLE,
    tagline = "Slide the wooden blocks to free the red block.",
    description = "A sliding block puzzle on a 6x6 board. Blocks only slide along their length. Clear a path and slide the red block out through the exit. " +
        "Every puzzle is solved by the computer first, so you always know the minimum number of moves (PAR).",
    icon = PixelArt(
        "................",
        ".MMMMMMMMMMMMMM.",
        ".MttttttOOOOkkM.",
        ".MttttttOOOOkkM.",
        ".MkkOOkkkkeekkM.",
        ".MkkOOkkkkeekkM.",
        ".MRRRRRRkkeekk..",
        ".MRRRRRRkkeekk..",
        ".MkkOOkkkkeekkM.",
        ".MkkOOkkkkkkkkM.",
        ".MkkttttttttkkM.",
        ".MkkttttttttkkM.",
        ".MkkkkkkkkkkkkM.",
        ".MMMMMMMMMMMMMM.",
        "................",
        "................",
    ),
    accent = Pal.TAN,
    music = Music.PUZZLE,
    scoreKind = ScoreKind.WINS,
    tutorial = listOf(
        TutorialPage("OBJECTIVE", "Get the RED block out through the exit on the right side of the board. Other blocks are in the way - slide them aside to clear a path.",
            PixelArt(
                "MMMMMMMM",
                "MkktttkM",
                "MkkOkkkM",
                "MRROkkk.",
                "MkkOkekM",
                "MkkkkekM",
                "MMMMMMMM",
            )),
        TutorialPage("CONTROLS", "DRAG a block to slide it. Horizontal blocks move left and right, vertical blocks move up and down. Blocks can't jump over each other.", Icons.DRAG),
        TutorialPage("MOVES & PAR", "Each slide counts as one move, however far it goes. PAR is the fewest moves possible. Solve in PAR moves for 3 stars! UNDO and RESET are always available.", Icons.STAR),
        TutorialPage("TIPS", "Work backwards: which block is directly blocking the red one? What is blocking THAT block? Long 3-squares blocks are the trickiest - deal with them early.", Icons.BULB),
    ),
    achievements = listOf(
        AchievementDef("first_win", "ESCAPE ARTIST", "Solve an Unblock puzzle."),
        AchievementDef("perfect", "PERFECT PAR", "Solve a puzzle in the minimum number of moves."),
        AchievementDef("hard_win", "GRIDLOCK BREAKER", "Solve a HARD puzzle."),
    ),
    factory = { UnblockSession(it) },
)
