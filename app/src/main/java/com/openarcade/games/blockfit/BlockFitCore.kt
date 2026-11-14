package com.openarcade.games.blockfit

import kotlin.random.Random

/** A fixed (never rotated) block shape: cells as (x, y) offsets from its top-left corner. */
class Shape(val id: Int, val cells: List<IntArray>) {
    val w = cells.maxOf { it[0] } + 1
    val h = cells.maxOf { it[1] } + 1
    val size get() = cells.size

    companion object {
        private fun of(vararg rows: String): List<IntArray> {
            val out = ArrayList<IntArray>()
            for ((y, r) in rows.withIndex()) for ((x, c) in r.withIndex()) if (c == '#') out.add(intArrayOf(x, y))
            return out
        }

        /** Every shape with the tier it belongs to: 0 small, 1 medium, 2 large. */
        private val defs: List<Pair<Int, List<IntArray>>> = listOf(
            0 to of("#"),
            0 to of("##"), 0 to of("#", "#"),
            0 to of("###"), 0 to of("#", "#", "#"),
            0 to of("##", "#."), 0 to of("##", ".#"), 0 to of("#.", "##"), 0 to of(".#", "##"),
            1 to of("##", "##"),
            1 to of("####"), 1 to of("#", "#", "#", "#"),
            1 to of("###", ".#."), 1 to of(".#.", "###"), 1 to of("#.", "##", "#."), 1 to of(".#", "##", ".#"),
            1 to of("##.", ".##"), 1 to of(".##", "##."), 1 to of("#.", "##", ".#"), 1 to of(".#", "##", "#."),
            1 to of("#..", "###"), 1 to of("..#", "###"), 1 to of("###", "#.."), 1 to of("###", "..#"),
            1 to of("##", "#.", "#."), 1 to of("##", ".#", ".#"), 1 to of("#.", "#.", "##"), 1 to of(".#", ".#", "##"),
            2 to of("#####"), 2 to of("#", "#", "#", "#", "#"),
            2 to of("###", "###"), 2 to of("##", "##", "##"),
            2 to of("###", "###", "###"),
            2 to of("###", "#..", "#.."), 2 to of("###", "..#", "..#"), 2 to of("#..", "#..", "###"), 2 to of("..#", "..#", "###"),
        )
        val ALL: List<Shape> = defs.mapIndexed { i, d -> Shape(i, d.second) }
        val TIER: IntArray = defs.map { it.first }.toIntArray()
    }
}

/** The 8x8 board: 0 = empty, otherwise a colour index (1..7). */
class FitBoard(val cells: IntArray = IntArray(N * N)) {
    companion object { const val N = 8 }

    fun copy() = FitBoard(cells.copyOf())
    fun get(x: Int, y: Int) = cells[y * N + x]

    /** True when the shape lies entirely on the board over empty cells, with its corner at (x, y). */
    fun fits(s: Shape, x: Int, y: Int): Boolean {
        if (x < 0 || y < 0 || x + s.w > N || y + s.h > N) return false
        for (c in s.cells) if (cells[(y + c[1]) * N + x + c[0]] != 0) return false
        return true
    }

    fun fitsAnywhere(s: Shape): Boolean {
        for (y in 0..N - s.h) for (x in 0..N - s.w) if (fits(s, x, y)) return true
        return false
    }

    /** Rows and columns that would be complete. */
    class Clear(val rows: List<Int>, val cols: List<Int>) {
        val count get() = rows.size + cols.size
        fun covers(i: Int) = (i / N) in rows || (i % N) in cols
    }

    fun fullLines(): Clear {
        val rows = (0 until N).filter { y -> (0 until N).all { x -> cells[y * N + x] != 0 } }
        val cols = (0 until N).filter { x -> (0 until N).all { y -> cells[y * N + x] != 0 } }
        return Clear(rows, cols)
    }

    /** Places the shape (must fit) and clears every completed row and column at the same time. */
    fun place(s: Shape, x: Int, y: Int, color: Int): Clear {
        require(fits(s, x, y))
        for (c in s.cells) cells[(y + c[1]) * N + x + c[0]] = color
        val clear = fullLines()
        for (i in cells.indices) if (clear.covers(i)) cells[i] = 0
        return clear
    }

    val empty get() = cells.all { it == 0 }
}

/** Scoring: one point per placed cell, line bonuses that grow with simultaneous lines and with a combo streak. */
object FitScore {
    fun lines(count: Int, combo: Int): Int = if (count == 0) 0 else count * 10 * count + combo * 10 * count
    const val BOARD_CLEAR = 300
}

/**
 * Deals three shapes at a time. Every deal is checked against the current board: it must be
 * possible to place all three in some order (with line clears in between), so the game never ends
 * because of an unlucky deal alone. When no such deal exists the dealer still prefers shapes that
 * fit somewhere, and only a board with no room for any shape can end the game.
 */
class Dealer(private val rnd: Random, private val difficulty: Int) {
    private fun weights(): DoubleArray = DoubleArray(Shape.ALL.size) { i ->
        when (Shape.TIER[i]) {
            0 -> doubleArrayOf(1.4, 1.0, 0.8)[difficulty]
            1 -> doubleArrayOf(1.0, 1.0, 1.0)[difficulty]
            else -> doubleArrayOf(0.35, 0.6, 0.9)[difficulty]
        }
    }

    private fun pick(w: DoubleArray, filter: (Shape) -> Boolean = { true }): Shape {
        val cand = Shape.ALL.filter(filter)
        if (cand.isEmpty()) return Shape.ALL[0]
        val total = cand.sumOf { w[it.id] }
        var r = rnd.nextDouble() * total
        for (s in cand) { r -= w[s.id]; if (r <= 0) return s }
        return cand.last()
    }

    fun deal(board: FitBoard): List<Shape> {
        val w = weights()
        repeat(40) {
            val set = List(3) { pick(w) }
            if (allPlaceable(board, set)) return set
        }
        // a crowded board: build the set one shape at a time from shapes that still fit
        repeat(60) {
            val set = ArrayList<Shape>()
            val b = board.copy()
            for (k in 0 until 3) {
                val s = pick(w) { b.fitsAnywhere(it) }
                if (!b.fitsAnywhere(s)) break
                set.add(s); placeSomewhere(b, s)
            }
            if (set.size == 3 && allPlaceable(board, set)) return set
        }
        return List(3) { pick(w) { board.fitsAnywhere(it) } }
    }

    private fun placeSomewhere(b: FitBoard, s: Shape) {
        // greedy: the spot that clears most lines, else the first that fits
        var best: IntArray? = null; var bestClear = -1
        for (y in 0..FitBoard.N - s.h) for (x in 0..FitBoard.N - s.w) if (b.fits(s, x, y)) {
            val t = b.copy(); val c = t.place(s, x, y, 1).count
            if (c > bestClear) { bestClear = c; best = intArrayOf(x, y) }
        }
        best?.let { b.place(s, it[0], it[1], 1) }
    }

    companion object {
        /** Can every shape in [set] be placed, in some order and at some positions? (Depth-first search.) */
        fun allPlaceable(board: FitBoard, set: List<Shape>, budget: IntArray = intArrayOf(4000)): Boolean {
            if (set.isEmpty()) return true
            for (k in set.indices) {
                val s = set[k]
                val rest = set.filterIndexed { i, _ -> i != k }
                for (y in 0..FitBoard.N - s.h) for (x in 0..FitBoard.N - s.w) {
                    if (!board.fits(s, x, y)) continue
                    if (--budget[0] < 0) return false
                    if (rest.isEmpty()) return true
                    val b = board.copy(); b.place(s, x, y, 1)
                    if (allPlaceable(b, rest, budget)) return true
                }
            }
            return false
        }
    }
}
