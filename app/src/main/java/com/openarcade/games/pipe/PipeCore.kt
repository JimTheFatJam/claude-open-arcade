package com.openarcade.games.pipe

import kotlin.random.Random

/**
 * Directions are 0 north, 1 east, 2 south, 3 west. A pipe's openings are a 4-bit mask with bit d set
 * when the pipe opens towards direction d.
 */
object Pipes {
    val DX = intArrayOf(0, 1, 0, -1)
    val DY = intArrayOf(-1, 0, 1, 0)
    fun opposite(d: Int) = (d + 2) and 3
    fun bit(d: Int) = 1 shl d
    /** Rotates a mask a quarter turn clockwise. */
    fun rotate(mask: Int): Int = ((mask shl 1) or (mask shr 3)) and 15

    const val STRAIGHT = 0
    const val CORNER = 1
    const val TEE = 2
    const val CROSS = 3
    /** Base masks for each kind at rotation 0: straight N-S, corner N-E, tee E-S-W, cross. */
    val BASE = intArrayOf(1 or 4, 1 or 2, 2 or 4 or 8, 15)

    fun mask(kind: Int, rot: Int): Int { var m = BASE[kind]; repeat(rot and 3) { m = rotate(m) }; return m }

    fun kindOf(mask: Int): Int = when (Integer.bitCount(mask)) {
        4 -> CROSS
        3 -> TEE
        else -> if (mask == 5 || mask == 10) STRAIGHT else CORNER
    }
}

/** What a board cell holds. */
object Cell {
    const val EMPTY = 0
    const val ROCK = 1
    const val SOURCE = 2
    const val DRAIN = 3
    const val PIPE = 4
}

/** A level: board size, rocks, source and drain (each with one opening), locked pipes and timings. */
class PipeLevel(
    val cols: Int,
    val rows: Int,
    val kind: IntArray,          // Cell.* per cell
    val mask: IntArray,          // openings for SOURCE / DRAIN / PIPE cells
    val fixed: BooleanArray,     // locked pipes that cannot be rotated or removed
    val countdown: Float,        // seconds before the water starts
    val secPerCell: Float,       // seconds the water needs to cross one pipe
    val queueSeed: Long,
    val plan: List<Int>,         // pipe kinds needed for the planned route, in order (fed into the queue)
    val route: List<Int> = emptyList(),      // the planned route's cells, source side first
    val routeMasks: List<Int> = emptyList(), // the pipe openings each route cell needs
) {
    val source = kind.indexOf(Cell.SOURCE)
    val drain = kind.indexOf(Cell.DRAIN)

    companion object {
        /**
         * Builds a level and guarantees it is solvable: a route from the source to the drain is
         * planned first (avoiding rocks), and the pieces it needs are fed into the queue early.
         */
        fun generate(difficulty: Int, level: Int): PipeLevel {
            var seed = 1_000_003L * (difficulty + 1) + level * 7919L
            while (true) {
                generateOnce(difficulty, level, Random(seed))?.let { return it }
                seed += 101
            }
        }

        private fun generateOnce(difficulty: Int, level: Int, rnd: Random): PipeLevel? {
            val l = level.coerceAtLeast(0)
            val size = when (difficulty) { 0 -> 6 + l / 5; 1 -> 7 + l / 5; else -> 8 + l / 6 }.coerceAtMost(9)
            val cols = size; val rows = size
            val n = cols * rows
            val kind = IntArray(n); val mask = IntArray(n); val fixed = BooleanArray(n)
            val rocks = when (difficulty) { 0 -> 1 + l / 2; 1 -> 3 + l / 2; else -> 5 + l / 2 }.coerceAtMost(n / 5)
            fun inner(i: Int) = i % cols in 1 until cols - 1 && i / cols in 1 until rows - 1
            // source and drain sit away from each other, opening towards the inside of the board
            val src = rnd.nextInt(n)
            var dst = -1
            val minDist = size / 2 + difficulty + l / 4
            for (k in 0 until 200) {
                val c = rnd.nextInt(n)
                val d = kotlin.math.abs(c % cols - src % cols) + kotlin.math.abs(c / cols - src / cols)
                if (c != src && d >= minDist) { dst = c; break }
            }
            if (dst < 0) return null
            fun openingFor(c: Int): Int? {
                val opts = (0 until 4).filter { d ->
                    val x = c % cols + Pipes.DX[d]; val y = c / cols + Pipes.DY[d]
                    x in 0 until cols && y in 0 until rows
                }.filter { d -> inner(c) || run {
                    // prefer pointing inwards on the edges
                    val x = c % cols + Pipes.DX[d] * 2; val y = c / cols + Pipes.DY[d] * 2
                    x in 0 until cols && y in 0 until rows
                } }
                return if (opts.isEmpty()) null else opts[rnd.nextInt(opts.size)]
            }
            val sd = openingFor(src) ?: return null
            val dd = openingFor(dst) ?: return null
            kind[src] = Cell.SOURCE; mask[src] = Pipes.bit(sd)
            kind[dst] = Cell.DRAIN; mask[dst] = Pipes.bit(dd)
            val first = src + Pipes.DX[sd] + Pipes.DY[sd] * cols
            val last = dst + Pipes.DX[dd] + Pipes.DY[dd] * cols
            if (kind[first] != Cell.EMPTY || kind[last] != Cell.EMPTY || first == last) return null
            var placed = 0
            while (placed < rocks) {
                val c = rnd.nextInt(n)
                if (kind[c] != Cell.EMPTY || c == first || c == last) continue
                kind[c] = Cell.ROCK; placed++
            }
            // random-tie breadth-first route from the first pipe cell to the last
            val prev = IntArray(n) { -2 }
            val q = ArrayDeque<Int>(); q.add(first); prev[first] = -1
            while (q.isNotEmpty()) {
                val c = q.removeFirst()
                if (c == last) break
                for (d in (0 until 4).shuffled(rnd)) {
                    val x = c % cols + Pipes.DX[d]; val y = c / cols + Pipes.DY[d]
                    if (x !in 0 until cols || y !in 0 until rows) continue
                    val nb = y * cols + x
                    if (prev[nb] != -2 || kind[nb] != Cell.EMPTY) continue
                    prev[nb] = c; q.add(nb)
                }
            }
            if (prev[last] == -2) return null
            val route = ArrayList<Int>()
            var c = last
            while (c != -1) { route.add(c); c = prev[c] }
            route.reverse()
            if (route.size < minDist) return null
            // the pipe needed in every route cell
            val need = IntArray(route.size)
            for (i in route.indices) {
                val from = if (i == 0) src else route[i - 1]
                val to = if (i == route.size - 1) dst else route[i + 1]
                need[i] = Pipes.bit(dirBetween(route[i], from, cols)) or Pipes.bit(dirBetween(route[i], to, cols))
            }
            // later levels lock a few route pipes in place
            val locks = when (difficulty) { 0 -> if (l >= 4) 1 else 0; 1 -> 1 + l / 4; else -> 2 + l / 3 }
            val lockable = route.indices.filter { it in 1 until route.size - 1 }.shuffled(rnd).take(minOf(locks, route.size / 3))
            for (i in lockable) { kind[route[i]] = Cell.PIPE; mask[route[i]] = need[i]; fixed[route[i]] = true }
            val plan = route.indices.filter { it !in lockable }.map { Pipes.kindOf(need[it]) }
            val countdown = when (difficulty) { 0 -> 26f - l; 1 -> 20f - l * 0.8f; else -> 16f - l * 0.6f }.coerceAtLeast(9f)
            val speed = when (difficulty) { 0 -> 3.2f - l * 0.08f; 1 -> 2.5f - l * 0.07f; else -> 2.0f - l * 0.05f }.coerceAtLeast(1.2f)
            return PipeLevel(cols, rows, kind, mask, fixed, countdown, speed, rnd.nextLong(), plan, route, need.toList())
        }

        /** Direction from cell [a] to the orthogonally adjacent cell [b]. */
        fun dirBetween(a: Int, b: Int, cols: Int): Int {
            val dx = b % cols - a % cols; val dy = b / cols - a / cols
            return when { dy < 0 -> 0; dx > 0 -> 1; dy > 0 -> 2; else -> 3 }
        }
    }

    /**
     * The upcoming pieces. The first part interleaves the planned route's pieces with random extras
     * (so the route is always buildable); after that pieces are random. Deterministic per level.
     */
    fun piece(index: Int): Int {
        if (index < planned.size) return planned[index]
        val r = Random(queueSeed + index * 31L)
        val x = r.nextInt(100)
        return when { x < 36 -> Pipes.STRAIGHT; x < 80 -> Pipes.CORNER; x < 91 -> Pipes.TEE; else -> Pipes.CROSS }
    }

    private val planned: List<Int> by lazy {
        val r = Random(queueSeed)
        val out = ArrayList<Int>()
        for (k in plan) {
            if (r.nextInt(100) < 35) out.add(listOf(Pipes.STRAIGHT, Pipes.CORNER, Pipes.CORNER, Pipes.TEE, Pipes.CROSS)[r.nextInt(5)])
            out.add(k)
        }
        out
    }
}

/** Where the water ended up after an update. */
enum class FlowEvent { NONE, ENTERED, LEAK, DRAINED }

/**
 * The board during play: placed pipes, which channels are filled, and the water fronts. Water
 * enters a pipe through one opening and leaves through every other opening (a cross passes straight
 * through, keeping its two channels separate). Water leaving an opening must enter a matching
 * opening next door; anything else - an empty cell, a rock, the edge or a pipe facing the other
 * way - is a leak.
 */
class PipeBoard(val level: PipeLevel) {
    val cols = level.cols
    val rows = level.rows
    val kind = level.kind.copyOf()
    val mask = level.mask.copyOf()
    /** Fill bits: 1 = whole pipe (or source), 2 = cross horizontal channel, 4 = cross vertical channel. */
    val fill = IntArray(cols * rows)

    class Front(val cell: Int, val entry: Int, var progress: Float)
    val fronts = ArrayList<Front>()
    var started = false; private set
    var filledCells = 0; private set
    var crossBonus = 0; private set
    /** Where water spilled (cell and direction), when it leaked. */
    var leakCell = -1; private set
    var leakDir = -1; private set

    fun isPipe(i: Int) = kind[i] == Cell.PIPE
    fun locked(i: Int) = level.fixed[i] || fill[i] != 0 || fronts.any { it.cell == i }

    /** Places a new pipe on an empty cell. */
    fun place(i: Int, pipeMask: Int): Boolean {
        if (kind[i] != Cell.EMPTY) return false
        kind[i] = Cell.PIPE; mask[i] = pipeMask
        return true
    }

    fun rotate(i: Int): Boolean {
        if (!isPipe(i) || locked(i)) return false
        mask[i] = Pipes.rotate(mask[i])
        return true
    }

    fun remove(i: Int): Boolean {
        if (!isPipe(i) || locked(i)) return false
        kind[i] = Cell.EMPTY; mask[i] = 0
        return true
    }

    /** Restores a saved round (arrays are copied in). */
    fun restore(kinds: IntArray, masks: IntArray, fills: IntArray, saved: List<Front>, wasStarted: Boolean, filled: Int, crosses: Int) {
        require(kinds.size == kind.size && masks.size == mask.size && fills.size == fill.size)
        kinds.copyInto(kind); masks.copyInto(mask); fills.copyInto(fill)
        fronts.clear(); fronts.addAll(saved)
        started = wasStarted; filledCells = filled; crossBonus = crosses
    }

    fun startFlow() {
        if (started) return
        started = true
        fill[level.source] = 1
        fronts.add(Front(level.source, -1, 0.5f))
    }

    private fun channelBit(entry: Int) = if (entry == 1 || entry == 3) 2 else 4

    /** Advances the water by [cells] (fraction of a pipe). */
    fun advance(cells: Float): FlowEvent {
        if (!started) return FlowEvent.NONE
        var ev = FlowEvent.NONE
        for (f in fronts) f.progress += cells
        val done = fronts.filter { it.progress >= 1f }
        if (done.isEmpty()) return ev
        fronts.removeAll(done)
        var drained = false
        for (f in done) {
            val m = mask[f.cell]
            val exits = when {
                f.entry < 0 -> listOf((0 until 4).first { m and Pipes.bit(it) != 0 })
                Integer.bitCount(m) == 4 -> listOf(Pipes.opposite(f.entry))
                else -> (0 until 4).filter { it != f.entry && m and Pipes.bit(it) != 0 }
            }
            for (e in exits) {
                val x = f.cell % cols + Pipes.DX[e]; val y = f.cell / cols + Pipes.DY[e]
                val nb = if (x in 0 until cols && y in 0 until rows) y * cols + x else -1
                val back = Pipes.opposite(e)
                if (nb < 0 || mask[nb] and Pipes.bit(back) == 0 || (kind[nb] != Cell.PIPE && kind[nb] != Cell.DRAIN)) {
                    leakCell = f.cell; leakDir = e
                    return FlowEvent.LEAK
                }
                if (kind[nb] == Cell.DRAIN) { fill[nb] = 1; drained = true; continue }
                val cross = Integer.bitCount(mask[nb]) == 4
                val bitNeeded = if (cross) channelBit(back) else 1
                if (fill[nb] and bitNeeded != 0) continue          // meets water already there
                if (cross && fill[nb] != 0) crossBonus++            // second pass through a cross
                fill[nb] = fill[nb] or bitNeeded
                filledCells++
                fronts.add(Front(nb, back, f.progress - 1f))
                ev = FlowEvent.ENTERED
            }
        }
        // a leak in the same instant as reaching the drain still counts as a leak (checked above)
        if (drained) return FlowEvent.DRAINED
        if (started && fronts.isEmpty()) {
            // every branch met existing water without reaching the drain: the water is trapped
            leakCell = -1; leakDir = -1
            return FlowEvent.LEAK
        }
        return ev
    }
}
