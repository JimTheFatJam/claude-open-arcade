package com.openarcade.games.flow

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
import com.openarcade.games.drawBoardFrame
import com.openarcade.games.formatTime
import com.openarcade.games.toIntArray
import com.openarcade.games.toJsonArray
import com.openarcade.gfx.Gfx
import com.openarcade.gfx.Icons
import com.openarcade.gfx.Pal
import com.openarcade.gfx.PixelArt
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.random.Random

/**
 * Generates Flow puzzles: a random Hamiltonian path over the grid (serpentine + many "backbite"
 * moves) is cut into segments; each segment's two ends become a coloured pair. The segments
 * themselves are a valid full-coverage solution, so every puzzle is solvable.
 */
object FlowGenerator {
    fun hamiltonian(n: Int, rnd: Random): MutableList<Int> {
        val path = ArrayList<Int>(n * n)
        for (r in 0 until n) for (k in 0 until n) path.add(r * n + if (r % 2 == 0) k else n - 1 - k)
        repeat(n * n * 30) { backbite(path, n, rnd) }
        return path
    }

    private fun neighbors(i: Int, n: Int): List<Int> {
        val c = i % n; val r = i / n
        val out = ArrayList<Int>(4)
        if (c > 0) out.add(i - 1); if (c < n - 1) out.add(i + 1)
        if (r > 0) out.add(i - n); if (r < n - 1) out.add(i + n)
        return out
    }

    private fun backbite(path: MutableList<Int>, n: Int, rnd: Random) {
        val atStart = rnd.nextBoolean()
        val e = if (atStart) path[0] else path[path.size - 1]
        val nb = neighbors(e, n)
        val v = nb[rnd.nextInt(nb.size)]
        val j = path.indexOf(v)
        if (atStart) {
            if (j <= 1) return
            path.subList(0, j).reverse()
        } else {
            if (j >= path.size - 2) return
            path.subList(j + 1, path.size).reverse()
        }
    }

    /** Returns the solution paths (each a list of cells, ends are the endpoints). */
    fun generate(n: Int, rnd: Random): List<List<Int>> {
        var best: List<List<Int>>? = null
        repeat(40) {
            val path = hamiltonian(n, rnd)
            val segs = ArrayList<List<Int>>()
            var i = 0
            val maxLen = n + n / 2 + 1
            while (i < path.size) {
                var len = 3 + rnd.nextInt(maxOf(1, maxLen - 2))
                if (path.size - (i + len) in 1..2) len = path.size - i
                if (i + len > path.size) len = path.size - i
                segs.add(path.subList(i, i + len).toList())
                i += len
            }
            if (segs.last().size < 3 && segs.size > 1) {
                val last = segs.removeAt(segs.size - 1)
                segs[segs.size - 1] = segs.last() + last
            }
            val target = n + 1
            val ok = segs.size in (n - 1)..(n + 4) && segs.size <= COLORS.size &&
                segs.none { s -> adjacent(s.first(), s.last(), n) }
            if (ok && abs(segs.size - target) <= 2) return segs
            if (ok && best == null) best = segs
        }
        return best ?: run {
            // fall back: simple even split (always valid)
            val path = hamiltonian(n, rnd)
            path.chunked(maxOf(3, path.size / n)).let { ch ->
                val l = ch.toMutableList()
                if (l.last().size < 3) { val last = l.removeAt(l.size - 1); l[l.size - 1] = l.last() + last }
                l
            }
        }
    }

    fun adjacent(a: Int, b: Int, n: Int) = (abs(a % n - b % n) + abs(a / n - b / n)) == 1

    val COLORS = intArrayOf(
        Pal.RED, Pal.BLUE, Pal.GREEN, Pal.YELLOW, Pal.ORANGE, Pal.CYAN, Pal.PINK,
        Pal.PURPLE, Pal.BROWN, Pal.WHITE, Pal.DGREEN, Pal.TAN, Pal.GREY, Pal.DRED,
    )
}

class FlowSession(host: GameHost) : GameSession(host) {
    private val n = when (host.difficulty) { Difficulty.EASY -> 5; Difficulty.NORMAL -> 7; Difficulty.HARD -> 9 }
    private val grid = GridFit(n, n)
    private var ends = Array(0) { IntArray(2) }        // per colour: the two endpoint cells
    private var paths = ArrayList<ArrayList<Int>>()    // per colour: the player's path
    private val endColor = IntArray(n * n) { -1 }
    private var active = -1
    private var moves = 0
    private var t = 0f
    private var lastConnected = 0

    override fun start(saved: JSONObject?) {
        if (saved != null) {
            val e = saved.getJSONArray("ends")
            ends = Array(e.length()) { e.getJSONArray(it).toIntArray() }
            val p = saved.getJSONArray("paths")
            paths = ArrayList((0 until p.length()).map { ArrayList(p.getJSONArray(it).toIntArray().toList()) })
            require(paths.size == ends.size && ends.all { e -> e.size == 2 && e.all { it in 0 until n * n } } &&
                paths.all { pa -> pa.all { it in 0 until n * n } }) { "save is for a different board size" }
            moves = saved.optInt("moves")
        } else {
            val sol = FlowGenerator.generate(n, host.rng)
            ends = Array(sol.size) { intArrayOf(sol[it].first(), sol[it].last()) }
            paths = ArrayList(sol.indices.map { ArrayList<Int>() })
        }
        for (k in ends.indices) { endColor[ends[k][0]] = k; endColor[ends[k][1]] = k }
        lastConnected = connectedCount()
    }

    override fun save(): JSONObject = JSONObject()
        .put("ends", JSONArray().also { a -> ends.forEach { a.put(it.toJsonArray()) } })
        .put("paths", JSONArray().also { a -> paths.forEach { a.put(it.toIntArray().toJsonArray()) } })
        .put("moves", moves)

    override fun onLayout() = grid.fit(area, px * 4)

    private fun owner(cell: Int): Int {
        for (k in paths.indices) if (paths[k].contains(cell)) return k
        return -1
    }

    private fun isConnected(k: Int): Boolean {
        val p = paths[k]
        if (p.size < 2) return false
        val a = ends[k][0]; val b = ends[k][1]
        return (p.first() == a && p.last() == b) || (p.first() == b && p.last() == a)
    }

    private fun connectedCount() = paths.indices.count { isConnected(it) }

    private fun coverage(): Int {
        val used = HashSet<Int>()
        for (p in paths) used.addAll(p)
        for (e in ends) { used.add(e[0]); used.add(e[1]) }
        return used.size * 100 / (n * n)
    }

    override fun onTouch(e: MotionEvent) {
        val cell = grid.cellAt(e.x, e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (cell < 0) return
                val ec = endColor[cell]
                if (ec >= 0) {
                    active = ec
                    paths[ec] = arrayListOf(cell)
                    host.sfx(Sfx.SELECT)
                } else {
                    val o = owner(cell)
                    if (o >= 0) {
                        active = o
                        val p = paths[o]
                        val idx = p.indexOf(cell)
                        while (p.size > idx + 1) p.removeAt(p.size - 1)
                        host.sfx(Sfx.CLICK)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (active < 0) return
                // walk one step at a time toward the finger so fast drags never skip cells
                var guard = 0
                while (guard++ < n * 2) {
                    val p = paths[active]
                    val head = p.last()
                    val tc = ((e.x - grid.ox) / grid.cell).toInt().coerceIn(0, n - 1)
                    val tr = ((e.y - grid.oy) / grid.cell).toInt().coerceIn(0, n - 1)
                    val hc = head % n; val hr = head / n
                    if (tc == hc && tr == hr) break
                    val next = if (abs(tc - hc) >= abs(tr - hr)) head + (if (tc > hc) 1 else -1) else head + (if (tr > hr) n else -n)
                    if (!step(next)) break
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (active >= 0) {
                    moves++
                    val k = active
                    active = -1
                    if (paths[k].size == 1) paths[k].clear()
                    afterChange()
                }
            }
        }
    }

    /** Tries to extend the active path into [next]. Returns false if blocked. */
    private fun step(next: Int): Boolean {
        val k = active
        val p = paths[k]
        val idx = p.indexOf(next)
        if (idx >= 0) { while (p.size > idx + 1) p.removeAt(p.size - 1); host.sfx(Sfx.TICK); return true }
        if (isConnected(k)) return false
        val ec = endColor[next]
        if (ec >= 0 && ec != k) return false
        if (ec == k && next == p.first()) return false
        // cut through another colour's pipe
        val o = owner(next)
        if (o >= 0 && o != k) {
            val op = paths[o]
            val oi = op.indexOf(next)
            while (op.size > oi) op.removeAt(op.size - 1)
            if (op.size == 1) op.clear()
        }
        p.add(next)
        if (ec == k) { host.sfx(Sfx.COIN); host.vibrate(20) } else host.sfx(Sfx.MOVE)
        return true
    }

    private fun afterChange() {
        val conn = connectedCount()
        lastConnected = conn
        if (conn == ends.size) {
            if (coverage() >= 100) {
                host.unlock("first_win")
                if (n == 9) host.unlock("hard_win")
                val total = host.getInt("solved") + 1
                host.putInt("solved", total)
                if (total >= 10) host.unlock("ten")
                win(detail = "ALL FLOWS CONNECTED!", delay = 1.0f)
            } else {
                host.toast("FILL EVERY SQUARE!")
            }
        }
    }

    private fun reset() {
        for (p in paths) p.clear()
        host.sfx(Sfx.BACK)
    }

    override fun update(dt: Float) { t += dt }

    override fun hud() = listOf(
        HudItem("FLOWS", "${connectedCount()}/${ends.size}", Pal.CYAN),
        HudItem("FILLED", "${coverage()}%"),
        HudItem("TIME", formatTime(host.playTime.toInt())),
    )

    override fun actions() = listOf(GameAction("CLEAR ALL", Icons.RESTART) { reset() })

    override fun draw(c: Canvas) {
        val p = px
        val cs = grid.cell
        drawBoardFrame(c, grid.bounds, p, Pal.PURPLE)
        Gfx.rect(c, grid.bounds, Pal.BG0)
        val r = RectF()
        // tinted cells under pipes
        for (k in paths.indices) for (cell in paths[k]) {
            grid.rect(cell % n, cell / n, r)
            Gfx.rect(c, r, Pal.withAlpha(FlowGenerator.COLORS[k], if (isConnected(k)) 70 else 40))
        }
        // grid lines
        for (k in 0..n) {
            Gfx.rect(c, grid.x(k) - maxOf(1f, p / 4), grid.oy, grid.x(k) + maxOf(1f, p / 4), grid.bounds.bottom, Pal.BG2)
            Gfx.rect(c, grid.ox, grid.y(k) - maxOf(1f, p / 4), grid.bounds.right, grid.y(k) + maxOf(1f, p / 4), Pal.BG2)
        }
        // pipes
        val thick = (cs * 0.32f).toInt().toFloat().coerceAtLeast(2f)
        for (k in paths.indices) {
            val path = paths[k]
            val col = FlowGenerator.COLORS[k]
            for (j in 0 until path.size - 1) {
                val a = path[j]; val b = path[j + 1]
                val ax = grid.cx((a % n).toFloat()); val ay = grid.cy((a / n).toFloat())
                val bx = grid.cx((b % n).toFloat()); val by = grid.cy((b / n).toFloat())
                Gfx.rect(c, minOf(ax, bx) - thick / 2, minOf(ay, by) - thick / 2, maxOf(ax, bx) + thick / 2, maxOf(ay, by) + thick / 2, col)
            }
            if (path.size == 1) {
                val a = path[0]
                Gfx.rect(c, grid.cx((a % n).toFloat()) - thick / 2, grid.cy((a / n).toFloat()) - thick / 2,
                    grid.cx((a % n).toFloat()) + thick / 2, grid.cy((a / n).toFloat()) + thick / 2, col)
            }
        }
        // endpoints
        for (k in ends.indices) {
            val col = FlowGenerator.COLORS[k]
            val pulse = if (isConnected(k)) 1f else 0.92f + 0.08f * kotlin.math.sin(t * 4f + k)
            for (e in ends[k]) {
                val cx = grid.cx((e % n).toFloat()); val cy = grid.cy((e / n).toFloat())
                val rad = cs * 0.36f * pulse
                Gfx.disc(c, cx + p / 2, cy + p / 2, rad, Pal.BLACK, maxOf(1f, cs / 14f))
                Gfx.disc(c, cx, cy, rad, col, maxOf(1f, cs / 14f))
                Gfx.disc(c, cx - rad * 0.3f, cy - rad * 0.3f, rad * 0.28f, Pal.lighter(col, 0.6f), maxOf(1f, cs / 14f))
            }
        }
        if (active >= 0) {
            val head = paths[active].lastOrNull()
            if (head != null) {
                grid.rect(head % n, head / n, r)
                Gfx.frame(c, r, maxOf(1f, p / 2), Pal.withAlpha(Pal.WHITE, 180))
            }
        }
    }
}

val FlowLinkGame = GameDef(
    id = "flowlink",
    title = "FLOW LINK",
    category = Category.PUZZLE,
    tagline = "Connect matching colors with pipes and fill the whole board.",
    description = "Draw a pipe between each pair of matching dots. Pipes can't cross or branch, and a perfect solution covers every single square. " +
        "Every puzzle is generated on your device, so there's always a fresh one.",
    icon = PixelArt(
        ".RR..........BB.",
        "RWRRRRRRRRR.BWBB",
        "RRRRRRRRRRR.BBBB",
        ".RR......RR..BB.",
        ".GG..YY..RR..BB.",
        "GWGGYWYYRWRR.BB.",
        "GGGGYYYYRRRR.BB.",
        ".GG..YY..RR..BB.",
        ".GG..YY..YY..BB.",
        ".GG..YYYYWYY.BB.",
        ".GG..YYYYYYY.BB.",
        ".GG......YY..BB.",
        ".GG......GG..BB.",
        ".GGGGGGGGWGGBWBB",
        ".GGGGGGGGGGGBBBB",
        ".........GG..BB.",
    ),
    accent = Pal.PURPLE,
    music = Music.PUZZLE,
    scoreKind = ScoreKind.BEST_TIME,
    difficultyLabels = listOf("5X5", "7X7", "9X9"),
    tutorial = listOf(
        TutorialPage("OBJECTIVE", "Connect every pair of same-colored dots with a pipe. To complete the puzzle, all pairs must be connected AND every square of the board must be filled.",
            PixelArt(
                "RRRRB",
                "kkkRB",
                "GGkRB",
                "GkkRB",
                "GGRRB",
            ).let { it }),
        TutorialPage("CONTROLS", "DRAG from a dot to draw its pipe. Drag back over your pipe to shorten it. Start dragging from the middle of an existing pipe to reroute it.", Icons.DRAG),
        TutorialPage("RULES", "Pipes can't branch and can't pass through dots of another color. Drawing across another pipe cuts it - you'll have to redraw that one.", PixelArt(
            "..B..",
            "..B..",
            "RRRRR",
            "..B..",
            "..B..",
        )),
        TutorialPage("TIPS", "Dots near edges and corners usually hug the wall. Connect the shortest, most obvious pairs first, then let the long pipes snake around them to fill the gaps.", Icons.BULB),
    ),
    achievements = listOf(
        AchievementDef("first_win", "PLUMBER", "Solve a Flow Link puzzle."),
        AchievementDef("hard_win", "PIPE DREAM", "Solve a 9x9 puzzle."),
        AchievementDef("ten", "FREE FLOWING", "Solve 10 puzzles."),
    ),
    factory = { FlowSession(it) },
)
