package com.openarcade

import com.openarcade.games.meowdoku.Mark
import com.openarcade.games.meowdoku.MeowLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MeowdokuTest {
    private fun contiguous(n: Int, regions: IntArray, g: Int): Boolean {
        val cells = (0 until n * n).filter { regions[it] == g }
        val seen = hashSetOf(cells[0]); val st = ArrayDeque(listOf(cells[0]))
        while (st.isNotEmpty()) {
            val x = st.removeLast(); val r = x / n; val c = x % n
            for ((dr, dc) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val rr = r + dr; val cc = c + dc
                if (rr in 0 until n && cc in 0 until n && regions[rr * n + cc] == g && seen.add(rr * n + cc)) st.add(rr * n + cc)
            }
        }
        return seen.size == cells.size
    }

    @Test fun generatedPuzzlesAreValidUniqueAndLogical() {
        for ((n, maxRule, minRule) in listOf(Triple(5, 2, 0), Triple(6, 2, 0), Triple(7, 3, 2), Triple(8, 4, 3), Triple(9, 4, 3))) {
            repeat(4) { seed ->
                val t0 = System.currentTimeMillis()
                val p = MeowLogic.generate(n, Random(seed * 97 + n), maxRule, minRule)
                val ms = System.currentTimeMillis() - t0
                // n regions, each contiguous and non-empty
                assertEquals((0 until n).toSet(), p.regions.toSet())
                for (g in 0 until n) assertTrue("region $g contiguous", contiguous(n, p.regions, g))
                // the stored solution obeys all four rules and is the only one
                assertTrue(MeowLogic.isValidSolution(n, p.regions, p.solution))
                val found = ArrayList<IntArray>()
                assertEquals(1, MeowLogic.countSolutions(n, p.regions, 2, found))
                assertTrue(found[0].contentEquals(p.solution))
                // solvable by deduction alone, reaching exactly the solution
                val rating = MeowLogic.rate(n, p.regions, maxRule)
                assertTrue("n=$n seed=$seed logic-solvable", rating.solved)
                println("n=$n seed=$seed hardest=${MeowLogic.RULE_NAMES[rating.hardest]} steps=${rating.steps} ${ms}ms")
            }
        }
    }

    @Test fun deductionsNeverContradictTheSolution() {
        val p = MeowLogic.generate(8, Random(5), MeowLogic.R_SETS, MeowLogic.R_LOOKAHEAD)
        val state = IntArray(64)
        while (true) {
            val s = MeowLogic.nextStep(8, p.regions, state) ?: break
            for (c in s.place) assertTrue(p.isSolutionCat(c))
            for (c in s.cross) assertFalse(p.isSolutionCat(c))
            MeowLogic.apply(state, s)
        }
        assertEquals(8, state.count { it == Mark.CAT })
    }

    @Test fun touchingCatsAreInvalid() {
        val regions = IntArray(16) { it % 4 }  // columns as regions
        assertFalse(MeowLogic.isValidSolution(4, regions, intArrayOf(0, 1, 2, 3)))   // diagonal touch
        assertTrue(MeowLogic.isValidSolution(4, regions, intArrayOf(1, 3, 0, 2)))
        assertEquals(8, MeowLogic.neighbours8(5, 12).size)
        assertEquals(3, MeowLogic.neighbours8(5, 0).size)
    }
}
