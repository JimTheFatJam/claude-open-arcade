package com.openarcade

import com.openarcade.games.blockfit.Dealer
import com.openarcade.games.blockfit.FitBoard
import com.openarcade.games.blockfit.FitScore
import com.openarcade.games.blockfit.Shape
import com.openarcade.games.crate.CrateLevel
import com.openarcade.games.crate.CrateLevels
import com.openarcade.games.crate.CrateMove
import com.openarcade.games.crate.CrateState
import com.openarcade.games.pipe.Cell
import com.openarcade.games.pipe.FlowEvent
import com.openarcade.games.pipe.PipeBoard
import com.openarcade.games.pipe.PipeLevel
import com.openarcade.games.pipe.Pipes
import com.openarcade.games.rollblock.BlockPos
import com.openarcade.games.rollblock.Orient
import com.openarcade.games.rollblock.RollEvent
import com.openarcade.games.rollblock.RollLevel
import com.openarcade.games.rollblock.RollLevels
import com.openarcade.games.rollblock.RollState
import com.openarcade.games.rollblock.Tile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RollBlockTest {
    @Test fun rollingGeometry() {
        val s = BlockPos(5, 5, Orient.STAND)
        assertEquals(BlockPos(6, 5, Orient.LIE_X), s.roll(1, 0))
        assertEquals(BlockPos(3, 5, Orient.LIE_X), s.roll(-1, 0))
        assertEquals(BlockPos(5, 6, Orient.LIE_Y), s.roll(0, 1))
        assertEquals(BlockPos(5, 3, Orient.LIE_Y), s.roll(0, -1))
        val lx = BlockPos(5, 5, Orient.LIE_X)          // covers (5,5) and (6,5)
        assertEquals(BlockPos(7, 5, Orient.STAND), lx.roll(1, 0))
        assertEquals(BlockPos(4, 5, Orient.STAND), lx.roll(-1, 0))
        assertEquals(BlockPos(5, 6, Orient.LIE_X), lx.roll(0, 1))
        val ly = BlockPos(5, 5, Orient.LIE_Y)          // covers (5,5) and (5,6)
        assertEquals(BlockPos(5, 7, Orient.STAND), ly.roll(0, 1))
        assertEquals(BlockPos(5, 4, Orient.STAND), ly.roll(0, -1))
        assertEquals(BlockPos(6, 5, Orient.LIE_Y), ly.roll(1, 0))
        // rolling there and back always returns to the same place and orientation
        for (o in Orient.values()) for (d in 0 until 4) {
            val p = BlockPos(10, 10, o)
            val q = p.roll(RollLevel.DX[d], RollLevel.DY[d]).roll(-RollLevel.DX[d], -RollLevel.DY[d])
            assertEquals(p, q)
        }
        assertEquals(2, lx.cells().size)
        assertEquals(1, s.cells().size)
    }

    @Test fun fallingFragileAndSwitches() {
        // . void, # floor, = fragile, a switch, A bridge, G goal
        val lv = RollLevel(listOf("#S#=##", "###=##", "a#AA#G"))
        val st = lv.startState
        // off the left edge: lying (-1..0) has one half in the void
        assertEquals(RollEvent.FELL, lv.move(st, -1, 0).second)
        // lying across the fragile column is fine, standing on it is not
        val lie = lv.move(st, 1, 0)                              // covers (2,0),(3,0)
        assertEquals(RollEvent.MOVED, lie.second)
        val ontoFragile = RollState(BlockPos(3, 0, Orient.LIE_Y), 0)
        assertTrue(lv.supported(ontoFragile.pos, 0))
        assertFalse(lv.supported(BlockPos(3, 0, Orient.STAND), 0))
        // the bridge is closed until a switch press opens it
        assertFalse(lv.supported(BlockPos(2, 2, Orient.LIE_X), 0))
        val press = lv.move(RollState(BlockPos(0, 0, Orient.LIE_Y), 0), 0, 1)   // stands on (0,2) = switch a
        assertEquals(RollEvent.MOVED, press.second)
        assertEquals(1, press.first.bridges)
        assertTrue(lv.supported(BlockPos(2, 2, Orient.LIE_X), press.first.bridges))
        assertEquals(Tile.GOAL, lv.tile(5, 2))
    }

    @Test fun everyLevelIsSolvableAndPacksProgress() {
        assertEquals(3, RollLevels.packs.size)
        val expected = listOf(
            listOf(6, 7, 11, 12, 13, 14, 14, 24),
            listOf(11, 13, 16, 16, 17, 20, 21, 22),
            listOf(15, 16, 40, 35, 39, 39, 44, 56),
        )
        for ((pi, pack) in RollLevels.packs.withIndex()) {
            assertEquals(8, pack.size)
            for ((li, lv) in pack.withIndex()) {
                val sol = lv.solve()
                assertNotNull("pack $pi level $li unsolvable", sol)
                assertEquals("pack $pi level $li par", expected[pi][li], sol!!.size)
                // replaying the solution with the game rules reaches the goal without falling
                var s = lv.startState
                for ((k, d) in sol.withIndex()) {
                    val (ns, ev) = lv.move(s, RollLevel.DX[d], RollLevel.DY[d])
                    if (k < sol.size - 1) assertEquals(RollEvent.MOVED, ev) else assertEquals(RollEvent.WON, ev)
                    s = ns
                }
                assertTrue(lv.w <= 14 && lv.h <= 12)
            }
        }
        // EASY only teaches rolling; NORMAL adds fragile tiles and round switches; HARD adds X switches
        assertTrue(RollLevels.packs[0].none { it.usesFragile || it.usesSwitches })
        assertTrue(RollLevels.packs[1].any { it.usesFragile } && RollLevels.packs[1].any { it.usesSwitches })
        assertTrue(RollLevels.packs[1].none { lv -> lv.tiles.any { it == Tile.HEAVY } })
        assertTrue(RollLevels.packs[2].any { lv -> lv.tiles.any { it == Tile.HEAVY } })
    }

    @Test fun switchLevelsReallyNeedTheirSwitches() {
        for (pack in RollLevels.packs) for (lv in pack.filter { it.usesSwitches }) {
            // same layout with the switches replaced by plain floor: the bridges never open
            val rows = lv.rows.map { r -> r.map { ch -> if (ch in 'a'..'e' || ch in '1'..'5') '#' else ch }.joinToString("") }
            assertEquals("level needs a switch: ${lv.rows}", null, RollLevel(rows).solve())
        }
    }
}

class CratePushTest {
    @Test fun pushRules() {
        val lv = CrateLevel(listOf("#######", "#@\$\$ .#", "#  \$ .#", "#    .#", "#######"))
        val s = CrateState(lv)
        // two crates in a row can't be pushed
        assertEquals(CrateMove.BLOCKED, s.move(0))
        assertEquals(0, s.moves)
        // walk down, push the lower crate right
        assertEquals(CrateMove.WALKED, s.move(2))
        assertEquals(CrateMove.WALKED, s.move(0))
        assertEquals(CrateMove.PUSHED, s.move(0))
        assertEquals(1, s.pushes)
        assertTrue(s.boxAt(2 * 7 + 4) >= 0)
        // into the wall: blocked
        assertEquals(CrateMove.PUSHED, s.move(0))
        assertEquals(CrateMove.BLOCKED, s.move(0))
        // undo restores crates, player and counters exactly
        val before = s.boxes.copyOf()
        assertTrue(s.undo())
        assertFalse(before.contentEquals(s.boxes))
        assertEquals(1, s.pushes)
        s.reset()
        assertTrue(s.boxes.contentEquals(lv.startBoxes))
        assertEquals(0, s.moves)
        assertFalse(s.canUndo)
    }

    @Test fun deadCornersAreDetected() {
        val lv = CrateLevel(listOf("######", "#@   #", "# \$ .#", "#    #", "######"))
        val corner = 1 * 6 + 4      // top-right inner corner, not a goal
        assertTrue(lv.dead[corner])
        assertFalse(lv.dead[2 * 6 + 4])  // the goal itself
        assertFalse(lv.dead[2 * 6 + 2])  // the crate's start
        val s = CrateState(lv)
        assertTrue(s.stuckCrates().isEmpty())
        s.restore(lv.startPlayer, intArrayOf(corner), 0, 0)
        assertEquals(setOf(corner), s.stuckCrates())
        // walking routes go around crates, never through them
        s.restore(1 * 6 + 1, intArrayOf(2 * 6 + 2), 0, 0)
        val path = s.pathTo(3 * 6 + 3)!!
        var p = s.player
        for (d in path) { p = lv.step(p, d); assertTrue(p != 2 * 6 + 2) }
        assertEquals(3 * 6 + 3, p)
        assertEquals(null, s.pathTo(0))
    }

    @Test fun everyLevelIsSolvable() {
        val expected = listOf(listOf(2, 3, 3, 3, 4, 5, 6, 6), listOf(7, 7, 8, 8, 9, 10, 10, 10), listOf(12, 13, 14, 16, 23, 28, 30, 36))
        assertEquals(3, CrateLevels.packs.size)
        for ((pi, pack) in CrateLevels.packs.withIndex()) {
            assertEquals(8, pack.size)
            for ((li, lv) in pack.withIndex()) {
                assertEquals("pack $pi level $li", expected[pi][li], lv.minPushes())
                assertFalse("starts solved", CrateState(lv).solved)
                assertTrue("no crate starts stuck", CrateState(lv).stuckCrates().isEmpty())
            }
        }
    }
}

class BlockFitTest {
    @Test fun shapesNeverRotateAndFitRules() {
        assertTrue(Shape.ALL.size >= 30)
        assertEquals(Shape.ALL.size, Shape.ALL.map { s -> s.cells.joinToString { "${it[0]},${it[1]}" } }.toSet().size)
        val b = FitBoard()
        val bar = Shape.ALL.first { it.w == 5 && it.h == 1 }
        assertTrue(b.fits(bar, 3, 0))
        assertFalse(b.fits(bar, 4, 0))      // would stick out of the 8x8 board
        b.place(bar, 0, 0, 1)
        assertFalse(b.fits(bar, 0, 0))      // overlap
        assertTrue(b.fits(bar, 3, 1))
    }

    @Test fun rowsAndColumnsClearTogether() {
        val b = FitBoard()
        // fill row 7 and column 7 except their shared corner
        for (i in 0 until 7) { b.cells[7 * 8 + i] = 2; b.cells[i * 8 + 7] = 3 }
        val dot = Shape.ALL.first { it.size == 1 }
        val clear = b.place(dot, 7, 7, 4)
        assertEquals(listOf(7), clear.rows)
        assertEquals(listOf(7), clear.cols)
        assertTrue(b.empty)
        assertEquals(2 * 10 * 2, FitScore.lines(2, 0))
        assertTrue(FitScore.lines(1, 3) > FitScore.lines(1, 0))
    }

    @Test fun dealsAreAlwaysPlaceableWhenPossible() {
        for (d in 0..2) {
            val rnd = Random(d * 17 + 3)
            val dealer = Dealer(rnd, d)
            var board = FitBoard()
            var placed = 0
            var deals = 0
            while (deals < 120) {
                val set = dealer.deal(board)
                deals++
                if (!Dealer.allPlaceable(board, set)) {
                    // only allowed when no set of the small shapes fits either
                    val tiny = Shape.ALL.first { it.size == 1 }
                    assertFalse("board ${board.cells.count { it != 0 }} cells: unfair deal", Dealer.allPlaceable(board, listOf(tiny, tiny, tiny)) && board.cells.count { it != 0 } < 30)
                    board = FitBoard(); continue
                }
                // play the set randomly but legally
                for (s in set.shuffled(rnd)) {
                    val spots = (0 until 64).filter { board.fits(s, it % 8, it / 8) }
                    if (spots.isEmpty()) break
                    val sp = spots[rnd.nextInt(spots.size)]
                    board.place(s, sp % 8, sp / 8, 1); placed++
                }
                if (Shape.ALL.none { board.fitsAnywhere(it) }) board = FitBoard()
            }
            println("blockfit difficulty $d: $placed pieces placed over $deals deals")
            assertTrue("placed $placed", placed > 100)
        }
    }
}

class PipeRushTest {
    @Test fun masksAndRotation() {
        assertEquals(2, Pipes.rotate(1))          // N -> E
        assertEquals(1, Pipes.rotate(8))          // W -> N
        assertEquals(10, Pipes.mask(Pipes.STRAIGHT, 1))
        assertEquals(Pipes.STRAIGHT, Pipes.kindOf(10))
        assertEquals(Pipes.CORNER, Pipes.kindOf(Pipes.mask(Pipes.CORNER, 3)))
        assertEquals(Pipes.TEE, Pipes.kindOf(7))
        assertEquals(Pipes.CROSS, Pipes.kindOf(15))
        for (k in 0..3) { var m = Pipes.BASE[k]; repeat(4) { m = Pipes.rotate(m) }; assertEquals(Pipes.BASE[k], m) }
    }

    private fun line(vararg cells: Pair<Int, Int>, cols: Int = 5): PipeLevel {
        val kind = IntArray(cols); val mask = IntArray(cols)
        for ((i, pair) in cells.withIndex()) { kind[i] = pair.first; mask[i] = pair.second }
        return PipeLevel(cols, 1, kind, mask, BooleanArray(cols), 5f, 1f, 1L, emptyList())
    }

    private fun run(b: PipeBoard): FlowEvent {
        b.startFlow()
        repeat(200) { val e = b.advance(0.25f); if (e == FlowEvent.LEAK || e == FlowEvent.DRAINED) return e }
        return FlowEvent.NONE
    }

    @Test fun flowFollowsRealConnections() {
        // IN -> straight -> straight -> OUT
        val lv = line(Cell.SOURCE to 2, Cell.EMPTY to 0, Cell.EMPTY to 0, Cell.DRAIN to 8, Cell.ROCK to 0)
        val ok = PipeBoard(lv)
        ok.place(1, 10); ok.place(2, 10)
        assertEquals(FlowEvent.DRAINED, run(ok))
        assertEquals(2, ok.filledCells)
        // a pipe turned the wrong way is a leak, even though it "touches"
        val bad = PipeBoard(lv)
        bad.place(1, 10); bad.place(2, 5)
        assertEquals(FlowEvent.LEAK, run(bad))
        assertEquals(1, bad.leakCell)
        // an empty square is a leak
        val gap = PipeBoard(lv)
        gap.place(1, 10)
        assertEquals(FlowEvent.LEAK, run(gap))
        // water inside a pipe locks it
        val lk = PipeBoard(lv); lk.place(1, 10); lk.place(2, 10); lk.startFlow(); lk.advance(0.7f)
        assertTrue(lk.locked(1)); assertFalse(lk.rotate(1)); assertFalse(lk.remove(1))
        assertTrue(lk.rotate(2) && lk.rotate(2))
    }

    @Test fun teeSendsWaterBothWaysAndCrossKeepsChannels() {
        // 3x3: source top-middle pointing down into a tee (N, E, W); drain on the right opening west
        val kind = IntArray(9); val mask = IntArray(9)
        kind[1] = Cell.SOURCE; mask[1] = 4
        kind[5] = Cell.DRAIN; mask[5] = 8
        val lv = PipeLevel(3, 3, kind, mask, BooleanArray(9), 5f, 1f, 1L, emptyList())
        // the west arm opens onto an empty square: water spills there at the same moment it reaches the drain
        val b = PipeBoard(lv)
        b.place(4, 1 or 2 or 8)
        assertEquals(FlowEvent.LEAK, run(b))
        assertEquals(4, b.leakCell); assertEquals(3, b.leakDir)
        // a straight tee-free route drains
        val ok = PipeBoard(lv)
        ok.place(4, Pipes.mask(Pipes.CORNER, 0))   // N-E corner
        assertEquals(FlowEvent.DRAINED, run(ok))
        // cross: water passes straight through and fills only that channel
        val k2 = IntArray(9); val m2 = IntArray(9)
        k2[3] = Cell.SOURCE; m2[3] = 2
        k2[5] = Cell.DRAIN; m2[5] = 8
        val lv2 = PipeLevel(3, 3, k2, m2, BooleanArray(9), 5f, 1f, 1L, emptyList())
        val c = PipeBoard(lv2)
        c.place(4, 15)
        assertEquals(FlowEvent.DRAINED, run(c))
        assertEquals(2, c.fill[4])
        assertEquals(0, c.crossBonus)
    }

    @Test fun everyLevelHasABuildableRoute() {
        for (d in 0..2) for (l in 0 until 12) {
            val lv = PipeLevel.generate(d, l)
            assertEquals(lv.route.size, lv.routeMasks.size)
            assertTrue(lv.source >= 0 && lv.drain >= 0)
            val b = PipeBoard(lv)
            for ((i, c) in lv.route.withIndex()) {
                if (lv.fixed[c]) { assertEquals(lv.routeMasks[i], b.mask[c]); continue }
                assertTrue("d$d l$l route cell free", b.place(c, lv.routeMasks[i]))
            }
            assertEquals("d$d l$l", FlowEvent.DRAINED, run(b))
            // the planned pieces arrive in the queue in route order
            var qi = 0
            for (k in lv.plan) { while (lv.piece(qi) != k) qi++; qi++ }
            assertTrue(qi < lv.plan.size * 2 + 2)
            // and there is enough time to place them: roughly a second per piece before the water arrives
            assertTrue(lv.countdown + lv.secPerCell * 2 >= lv.plan.size * 0.6f)
        }
        // later levels are bigger or faster
        assertTrue(PipeLevel.generate(2, 11).secPerCell < PipeLevel.generate(0, 0).secPerCell)
        assertTrue(PipeLevel.generate(2, 11).cols > PipeLevel.generate(0, 0).cols)
    }
}
