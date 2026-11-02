package com.openarcade.games.meowdoku

import kotlin.random.Random

/**
 * A Meowdoku puzzle: an n x n board split into n coloured regions. The (unique) solution places
 * exactly one cat in every row, column and region, and no two cats touch — not even diagonally.
 *
 * Cells are indexed `row * n + col`. [regions] maps each cell to its region id (0 until n) and
 * [solution] holds the solution's cat column for every row.
 */
class MeowPuzzle(val n: Int, val regions: IntArray, val solution: IntArray) {
    fun isSolutionCat(cell: Int) = solution[cell / n] == cell % n
    fun regionCells(r: Int) = (0 until n * n).filter { regions[it] == r }
}

/** What the player (or the solver) knows about a cell. */
object Mark {
    const val EMPTY = 0
    const val X = 1
    const val CAT = 2
}

/** A single logical deduction, used both to rate puzzles and to give hints. */
class Step(
    val rule: Int,
    val place: List<Int>,      // cells that must hold a cat
    val cross: List<Int>,      // cells that can't hold a cat
    val focus: List<Int>,      // cells to highlight when explaining the step
    val text: String,
)

object MeowLogic {
    // Deduction rules, from simplest to hardest. Puzzles are rated by the hardest rule needed.
    const val R_BLOCK = 0      // a cat blocks its row, column, region and 8 neighbours
    const val R_SINGLE = 1     // a row / column / region with one free cell must hold a cat there
    const val R_CONFINE = 2    // a region confined to one row/column claims it (and vice versa)
    const val R_LOOKAHEAD = 3  // a cat here would leave some row/column/region with no space
    const val R_SETS = 4       // k regions confined to k rows/columns claim them all

    val RULE_NAMES = listOf("BLOCKING", "LAST SPACE", "CONFINEMENT", "LOOKAHEAD", "GROUPS")

    fun neighbours8(n: Int, cell: Int): List<Int> {
        val r = cell / n; val c = cell % n
        val out = ArrayList<Int>(8)
        for (dr in -1..1) for (dc in -1..1) {
            if (dr == 0 && dc == 0) continue
            val rr = r + dr; val cc = c + dc
            if (rr in 0 until n && cc in 0 until n) out.add(rr * n + cc)
        }
        return out
    }

    /** Cells a cat at [cell] rules out: its row, column, region and the 8 touching cells. */
    fun blockedBy(n: Int, regions: IntArray, cell: Int): List<Int> {
        val r = cell / n; val c = cell % n
        val out = LinkedHashSet<Int>()
        for (k in 0 until n) { out.add(r * n + k); out.add(k * n + c) }
        for (i in 0 until n * n) if (regions[i] == regions[cell]) out.add(i)
        out.addAll(neighbours8(n, cell))
        out.remove(cell)
        return out.toList()
    }

    // ------------------------------------------------------------------ brute-force counting
    /**
     * Counts solutions (up to [limit]) by backtracking row by row. The first solutions found are
     * written into [found] as row -> column arrays.
     */
    fun countSolutions(n: Int, regions: IntArray, limit: Int = 2, found: MutableList<IntArray>? = null): Int {
        val cols = IntArray(n) { -1 }
        var count = 0
        fun rec(row: Int, colMask: Int, regMask: Int): Boolean {
            if (row == n) {
                count++
                found?.add(cols.copyOf())
                return count >= limit
            }
            val prev = if (row > 0) cols[row - 1] else -10
            for (c in 0 until n) {
                if (colMask and (1 shl c) != 0) continue
                if (kotlin.math.abs(c - prev) <= 1) continue
                val reg = regions[row * n + c]
                if (regMask and (1 shl reg) != 0) continue
                cols[row] = c
                if (rec(row + 1, colMask or (1 shl c), regMask or (1 shl reg))) return true
            }
            return false
        }
        rec(0, 0, 0)
        return count
    }

    /** True if [cols] (row -> column) satisfies all four rules. */
    fun isValidSolution(n: Int, regions: IntArray, cols: IntArray): Boolean {
        if (cols.size != n || cols.toSet().size != n || cols.any { it !in 0 until n }) return false
        if ((0 until n).map { regions[it * n + cols[it]] }.toSet().size != n) return false
        for (r in 1 until n) if (kotlin.math.abs(cols[r] - cols[r - 1]) <= 1) return false
        return true
    }

    // ------------------------------------------------------------------ logical solving
    private fun units(n: Int, regions: IntArray): List<Pair<String, List<Int>>> {
        val out = ArrayList<Pair<String, List<Int>>>()
        for (r in 0 until n) out.add("ROW ${r + 1}" to (0 until n).map { r * n + it })
        for (c in 0 until n) out.add("COLUMN ${c + 1}" to (0 until n).map { it * n + c })
        for (g in 0 until n) out.add("THIS REGION" to (0 until n * n).filter { regions[it] == g })
        return out
    }

    /**
     * Finds the simplest available deduction for [state] (values from [Mark]), or null if stuck.
     * The state must be consistent with the puzzle's solution.
     */
    fun nextStep(n: Int, regions: IntArray, state: IntArray, maxRule: Int = R_SETS): Step? {
        val us = units(n, regions)
        // R_BLOCK: cross out everything a placed cat rules out
        for (i in state.indices) if (state[i] == Mark.CAT) {
            val cross = blockedBy(n, regions, i).filter { state[it] == Mark.EMPTY }
            if (cross.isNotEmpty()) return Step(R_BLOCK, emptyList(), cross, listOf(i),
                "A cat blocks its whole row, column and region, and the 8 cells around it.")
        }
        // R_SINGLE: a unit without a cat that has exactly one free cell
        for ((name, cells) in us) {
            if (cells.any { state[it] == Mark.CAT }) continue
            val free = cells.filter { state[it] == Mark.EMPTY }
            if (free.size == 1) return Step(R_SINGLE, free, emptyList(), cells,
                "$name has only one free cell left - the cat must go there!")
        }
        if (maxRule < R_CONFINE) return null
        // R_CONFINE: a region whose free cells all sit in one row/column claims that line;
        // a row/column whose free cells all sit in one region claims that region.
        for (g in 0 until n) {
            val cells = (0 until n * n).filter { regions[it] == g }
            if (cells.any { state[it] == Mark.CAT }) continue
            val free = cells.filter { state[it] == Mark.EMPTY }
            if (free.isEmpty()) continue
            val rows = free.map { it / n }.toSet()
            if (rows.size == 1) {
                val r = rows.first()
                val cross = (0 until n).map { r * n + it }.filter { state[it] == Mark.EMPTY && regions[it] != g }
                if (cross.isNotEmpty()) return Step(R_CONFINE, emptyList(), cross, free,
                    "This region only has space in ROW ${r + 1}, so its cat will use that row. The rest of the row is blocked.")
            }
            val cols = free.map { it % n }.toSet()
            if (cols.size == 1) {
                val c = cols.first()
                val cross = (0 until n).map { it * n + c }.filter { state[it] == Mark.EMPTY && regions[it] != g }
                if (cross.isNotEmpty()) return Step(R_CONFINE, emptyList(), cross, free,
                    "This region only has space in COLUMN ${c + 1}, so its cat will use that column. The rest of the column is blocked.")
            }
        }
        for ((name, cells) in us.take(2 * n)) {
            if (cells.any { state[it] == Mark.CAT }) continue
            val free = cells.filter { state[it] == Mark.EMPTY }
            val regs = free.map { regions[it] }.toSet()
            if (regs.size == 1) {
                val g = regs.first()
                val cross = (0 until n * n).filter { regions[it] == g && state[it] == Mark.EMPTY && it !in cells }
                if (cross.isNotEmpty()) return Step(R_CONFINE, emptyList(), cross, free,
                    "$name only has space inside one region, so that region's cat must be in $name. The rest of the region is blocked.")
            }
        }
        if (maxRule < R_LOOKAHEAD) return null
        // R_LOOKAHEAD: a cat here would wipe out every free cell of some unit
        for (i in state.indices) {
            if (state[i] != Mark.EMPTY) continue
            val blocked = blockedBy(n, regions, i).toHashSet()
            blocked.add(i)
            for ((name, cells) in us) {
                if (cells.any { state[it] == Mark.CAT }) continue
                if (i in cells) continue
                val free = cells.filter { state[it] == Mark.EMPTY }
                if (free.isNotEmpty() && free.all { it in blocked }) {
                    val label = if (name == "THIS REGION") "a region" else name
                    return Step(R_LOOKAHEAD, emptyList(), listOf(i), free,
                        "A cat here would block every free cell of $label, leaving it no cat. So this cell is impossible.")
                }
            }
        }
        if (maxRule < R_SETS) return null
        // R_SETS: k regions whose free cells all lie in k rows (or columns) claim those lines
        val openRegions = (0 until n).filter { g -> (0 until n * n).none { regions[it] == g && state[it] == Mark.CAT } }
        val freeOf = openRegions.associateWith { g -> (0 until n * n).filter { regions[it] == g && state[it] == Mark.EMPTY } }
        val m = openRegions.size
        for (mask in 1 until (1 shl m)) {
            val k = Integer.bitCount(mask)
            if (k < 2 || k >= m) continue
            val chosen = (0 until m).filter { mask and (1 shl it) != 0 }.map { openRegions[it] }
            val cells = chosen.flatMap { freeOf[it]!! }
            for (byRow in listOf(true, false)) {
                val lines = cells.map { if (byRow) it / n else it % n }.toSet()
                if (lines.size != k) continue
                val cross = (0 until n * n).filter { cell ->
                    state[cell] == Mark.EMPTY && regions[cell] !in chosen && (if (byRow) cell / n else cell % n) in lines
                }
                if (cross.isNotEmpty()) {
                    val what = if (byRow) "rows" else "columns"
                    return Step(R_SETS, emptyList(), cross, cells,
                        "These $k regions fit only in the same $k $what, so they use up all of those $what. Other cells there are blocked.")
                }
            }
        }
        return null
    }

    fun apply(state: IntArray, s: Step) {
        for (c in s.cross) if (state[c] == Mark.EMPTY) state[c] = Mark.X
        for (c in s.place) state[c] = Mark.CAT
    }

    class Rating(val solved: Boolean, val hardest: Int, val steps: Int)

    /** Solves using only deductions up to [maxRule]. */
    fun rate(n: Int, regions: IntArray, maxRule: Int = R_SETS): Rating {
        val state = IntArray(n * n)
        var hardest = 0
        var steps = 0
        while (state.count { it == Mark.CAT } < n) {
            val s = nextStep(n, regions, state, maxRule) ?: return Rating(false, hardest, steps)
            hardest = maxOf(hardest, s.rule)
            apply(state, s)
            steps++
            if (steps > n * n * 4) return Rating(false, hardest, steps)
        }
        return Rating(true, hardest, steps)
    }

    // ------------------------------------------------------------------ generation
    /** A random cat layout: one per row and column, never touching. */
    fun randomLayout(n: Int, rnd: Random): IntArray? {
        val cols = IntArray(n)
        fun rec(row: Int, used: Int): Boolean {
            if (row == n) return true
            for (c in (0 until n).shuffled(rnd)) {
                if (used and (1 shl c) != 0) continue
                if (row > 0 && kotlin.math.abs(c - cols[row - 1]) <= 1) continue
                cols[row] = c
                if (rec(row + 1, used or (1 shl c))) return true
            }
            return false
        }
        return if (rec(0, 0)) cols else null
    }

    private fun orth(n: Int, cell: Int): List<Int> {
        val r = cell / n; val c = cell % n
        val out = ArrayList<Int>(4)
        if (r > 0) out.add(cell - n); if (r < n - 1) out.add(cell + n)
        if (c > 0) out.add(cell - 1); if (c < n - 1) out.add(cell + 1)
        return out
    }

    /** Grows n irregular regions outward from the solution's cats. */
    fun growRegions(n: Int, cats: IntArray, rnd: Random): IntArray {
        val owner = IntArray(n * n) { -1 }
        val size = IntArray(n)
        for (r in 0 until n) { owner[r * n + cats[r]] = r; size[r] = 1 }
        var left = n * n - n
        while (left > 0) {
            // favour small regions a little, but keep plenty of randomness for odd shapes
            val order = (0 until n).sortedBy { size[it] + rnd.nextInt(n + 2) }
            var grown = false
            for (g in order) {
                val frontier = (0 until n * n).filter { owner[it] == -1 && orth(n, it).any { nb -> owner[nb] == g } }
                if (frontier.isEmpty()) continue
                val cell = frontier[rnd.nextInt(frontier.size)]
                owner[cell] = g; size[g]++; left--; grown = true
                break
            }
            if (!grown) break
        }
        return owner
    }

    private fun connectedWithout(n: Int, regions: IntArray, g: Int, removed: Int): Boolean {
        val cells = (0 until n * n).filter { regions[it] == g && it != removed }
        if (cells.isEmpty()) return false
        val seen = HashSet<Int>()
        val stack = ArrayDeque<Int>().apply { add(cells[0]) }
        seen.add(cells[0])
        while (stack.isNotEmpty()) {
            val x = stack.removeLast()
            for (nb in orth(n, x)) if (nb != removed && regions[nb] == g && seen.add(nb)) stack.add(nb)
        }
        return seen.size == cells.size
    }

    /**
     * Reshapes regions until [cats] is the only solution: each alternative solution is broken by
     * moving one of its cats' cells into a neighbouring region (which then holds two of its cats).
     */
    fun makeUnique(n: Int, regions: IntArray, cats: IntArray, rnd: Random): Boolean {
        val catCells = (0 until n).map { it * n + cats[it] }.toSet()
        repeat(n * n * 3) {
            val found = ArrayList<IntArray>()
            val count = countSolutions(n, regions, 2, found)
            if (count == 1) return true
            val other = found.firstOrNull { !it.contentEquals(cats) } ?: return false
            val moves = ArrayList<Pair<Int, Int>>()
            for (r in 0 until n) {
                val cell = r * n + other[r]
                if (cell in catCells) continue
                val from = regions[cell]
                for (nb in orth(n, cell)) {
                    val to = regions[nb]
                    if (to != from && connectedWithout(n, regions, from, cell)) moves.add(cell to to)
                }
            }
            if (moves.isEmpty()) return false
            val (cell, to) = moves[rnd.nextInt(moves.size)]
            regions[cell] = to
        }
        return countSolutions(n, regions, 2) == 1
    }

    /**
     * Generates a puzzle with a unique solution that can be solved by deduction alone using rules
     * up to [maxRule], preferring puzzles that actually need at least [minRule].
     */
    fun generate(n: Int, rnd: Random, maxRule: Int, minRule: Int = 0, attempts: Int = 400): MeowPuzzle {
        var fallback: MeowPuzzle? = null
        repeat(attempts) {
            val cats = randomLayout(n, rnd) ?: return@repeat
            val regions = growRegions(n, cats, rnd)
            if (regions.any { it < 0 }) return@repeat
            if (!makeUnique(n, regions, cats, rnd)) return@repeat
            val rating = rate(n, regions, maxRule)
            if (!rating.solved) return@repeat
            val p = MeowPuzzle(n, regions, cats)
            if (rating.hardest >= minRule) return p
            if (fallback == null) fallback = p
        }
        return fallback ?: generate(n, rnd, R_SETS, 0, attempts)
    }
}
