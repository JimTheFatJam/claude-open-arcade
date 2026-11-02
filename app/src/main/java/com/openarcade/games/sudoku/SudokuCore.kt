package com.openarcade.games.sudoku

import kotlin.random.Random

/**
 * Sudoku generator & solver for n x n grids with br x bc boxes (4x4, 6x6 and 9x9 are used).
 * Values are 1..n, 0 means empty. Generated puzzles always have exactly one solution.
 */
class SudokuCore(val n: Int, val br: Int, val bc: Int) {
    init { require(br * bc == n) }
    private val all = (1 shl n) - 1

    fun box(i: Int): Int {
        val r = i / n
        val c = i % n
        return (r / br) * (n / bc) + (c / bc)
    }

    /** Indices sharing a row, column or box with [i] (excluding i). */
    fun peers(i: Int): IntArray {
        val r = i / n; val c = i % n; val b = box(i)
        return (0 until n * n).filter { it != i && (it / n == r || it % n == c || box(it) == b) }.toIntArray()
    }

    private val peerCache = Array(n * n) { peers(it) }
    fun peersOf(i: Int) = peerCache[i]

    /** Counts solutions up to [limit]. When [out] is given, the first solution is written there. */
    fun countSolutions(grid: IntArray, limit: Int = 2, out: IntArray? = null, rnd: Random? = null): Int {
        val g = grid.copyOf()
        val rows = IntArray(n); val cols = IntArray(n); val boxes = IntArray(n)
        for (i in g.indices) {
            val v = g[i]
            if (v != 0) {
                val bit = 1 shl (v - 1)
                if (rows[i / n] and bit != 0 || cols[i % n] and bit != 0 || boxes[box(i)] and bit != 0) return 0
                rows[i / n] = rows[i / n] or bit; cols[i % n] = cols[i % n] or bit; boxes[box(i)] = boxes[box(i)] or bit
            }
        }
        var count = 0
        fun rec(): Boolean {
            // choose the empty cell with the fewest candidates
            var best = -1
            var bestMask = 0
            var bestCount = 99
            for (i in g.indices) {
                if (g[i] != 0) continue
                val mask = all and (rows[i / n] or cols[i % n] or boxes[box(i)]).inv()
                val cnt = Integer.bitCount(mask)
                if (cnt < bestCount) { best = i; bestMask = mask; bestCount = cnt; if (cnt <= 1) break }
            }
            if (best < 0) {
                count++
                if (count == 1 && out != null) g.copyInto(out)
                return count >= limit
            }
            if (bestCount == 0) return false
            val values = ArrayList<Int>(bestCount)
            for (v in 1..n) if (bestMask and (1 shl (v - 1)) != 0) values.add(v)
            if (rnd != null) values.shuffle(rnd)
            val r = best / n; val c = best % n; val b = box(best)
            for (v in values) {
                val bit = 1 shl (v - 1)
                g[best] = v
                rows[r] = rows[r] or bit; cols[c] = cols[c] or bit; boxes[b] = boxes[b] or bit
                if (rec()) return true
                rows[r] = rows[r] and bit.inv(); cols[c] = cols[c] and bit.inv(); boxes[b] = boxes[b] and bit.inv()
            }
            g[best] = 0
            return false
        }
        rec()
        return count
    }

    fun randomSolution(rnd: Random): IntArray {
        val out = IntArray(n * n)
        countSolutions(IntArray(n * n), 1, out, rnd)
        return out
    }

    /** Removes clues while the solution stays unique, stopping at [targetClues]. */
    fun makePuzzle(solution: IntArray, targetClues: Int, rnd: Random): IntArray {
        val p = solution.copyOf()
        var clues = n * n
        for (i in (0 until n * n).shuffled(rnd)) {
            if (clues <= targetClues) break
            val keep = p[i]
            p[i] = 0
            if (countSolutions(p, 2) != 1) p[i] = keep else clues--
        }
        return p
    }

    /** True if [v] at [i] doesn't clash with any filled peer. */
    fun fits(grid: IntArray, i: Int, v: Int) = peersOf(i).none { grid[it] == v }
}
