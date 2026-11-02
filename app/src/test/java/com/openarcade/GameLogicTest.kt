package com.openarcade

import com.openarcade.audio.Music
import com.openarcade.core.Dir
import com.openarcade.games.checkers.CheckersEngine
import com.openarcade.games.chess.ChessEngine
import com.openarcade.games.flow.FlowGenerator
import com.openarcade.games.g2048.Board2048
import com.openarcade.games.lightsout.LightsBoard
import com.openarcade.games.maze.MazeMap
import com.openarcade.games.minesweeper.MineBoard
import com.openarcade.games.nonogram.NonoLibrary
import com.openarcade.games.sudoku.SudokuCore
import com.openarcade.games.tetromino.Pieces
import com.openarcade.games.tetromino.TetrisBoard
import com.openarcade.games.unblock.UnblockPuzzle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ChessTest {
    @Test fun perftStartPosition() {
        val e = ChessEngine()
        assertEquals(20L, e.perft(1))
        assertEquals(400L, e.perft(2))
        assertEquals(8902L, e.perft(3))
        assertEquals(197281L, e.perft(4))
    }

    @Test fun perftKiwipete() {
        // castling, en passant, promotions and pins all exercised
        val e = ChessEngine()
        e.loadFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1")
        assertEquals(48L, e.perft(1))
        assertEquals(2039L, e.perft(2))
        assertEquals(97862L, e.perft(3))
    }

    @Test fun perftEndgamePosition() {
        val e = ChessEngine()
        e.loadFen("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1")
        assertEquals(14L, e.perft(1))
        assertEquals(191L, e.perft(2))
        assertEquals(2812L, e.perft(3))
        assertEquals(43238L, e.perft(4))
    }

    @Test fun perftPromotionPosition() {
        val e = ChessEngine()
        e.loadFen("r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1")
        assertEquals(6L, e.perft(1))
        assertEquals(264L, e.perft(2))
        assertEquals(9467L, e.perft(3))
    }

    @Test fun foolsMateIsCheckmate() {
        val e = ChessEngine()
        e.loadFen("rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3")
        assertEquals(ChessEngine.Status.CHECKMATE, e.status())
    }

    @Test fun aiFindsMateInOne() {
        val e = ChessEngine()
        e.loadFen("6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1")
        val m = e.bestMove(2, 5000, 0, Random(1))
        e.play(m)
        assertEquals(ChessEngine.Status.CHECKMATE, e.status())
    }

    @Test fun stalemateDetected() {
        val e = ChessEngine()
        e.loadFen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1")
        assertEquals(ChessEngine.Status.STALEMATE, e.status())
    }
}

class SudokuTest {
    @Test fun generatedPuzzlesHaveUniqueSolutions() {
        for ((n, br, bc, clues) in listOf(listOf(4, 2, 2, 6), listOf(6, 2, 3, 14), listOf(9, 3, 3, 30), listOf(9, 3, 3, 24))) {
            val core = SudokuCore(n, br, bc)
            repeat(3) { seed ->
                val rnd = Random(seed * 31 + n)
                val sol = core.randomSolution(rnd)
                // solution is valid
                for (i in sol.indices) { assertTrue(sol[i] in 1..n); assertTrue(core.peersOf(i).none { sol[it] == sol[i] }) }
                val puzzle = core.makePuzzle(sol, clues, rnd)
                assertEquals(1, core.countSolutions(puzzle, 2))
                val out = IntArray(n * n)
                core.countSolutions(puzzle, 1, out)
                assertTrue(out.contentEquals(sol))
            }
        }
    }
}

class PuzzleGeneratorTest {
    @Test fun flowPuzzlesCoverBoardWithValidPaths() {
        for (n in listOf(5, 7, 9)) repeat(5) { seed ->
            val segs = FlowGenerator.generate(n, Random(seed + n * 100))
            val cells = segs.flatten()
            assertEquals(n * n, cells.size)
            assertEquals(n * n, cells.toSet().size)
            assertTrue(segs.size <= FlowGenerator.COLORS.size)
            for (s in segs) {
                assertTrue(s.size >= 3)
                for (k in 0 until s.size - 1) assertTrue(FlowGenerator.adjacent(s[k], s[k + 1], n))
            }
        }
    }

    @Test fun unblockPuzzlesAreSolvableAtRequestedDepth() {
        val rnd = Random(7)
        repeat(3) {
            val (p, start) = UnblockPuzzle.generate(4, 7, rnd)
            val d = p.minMoves(start)
            assertTrue("depth $d", d in 4..7)
            assertFalse(p.solved(start))
        }
        val (hp, hs) = UnblockPuzzle.generate(16, 99, Random(3))
        assertTrue(hp.minMoves(hs) >= 8)
    }

    @Test fun lightsOutPressesCancel() {
        val b = LightsBoard(5)
        b.press(12); b.press(7); b.press(12); b.press(7)
        assertTrue(b.solved())
        // pressing every cell in toSolve solves the board
        val rnd = Random(4)
        for (i in (0 until 25).shuffled(rnd).take(9)) b.press(i)
        for (i in 0 until 25) if (b.toSolve[i]) b.press(i)
        assertTrue(b.solved())
    }

    @Test fun nonogramPicturesAreSquare() {
        for (lib in listOf(NonoLibrary.SMALL, NonoLibrary.MEDIUM, NonoLibrary.LARGE)) for (pic in lib) {
            for (row in pic.rows) assertEquals(pic.name, pic.size, row.length)
        }
        assertEquals(listOf(3, 1), NonoLibrary.clues(listOf(true, true, true, false, true)))
        assertEquals(emptyList<Int>(), NonoLibrary.clues(listOf(false, false)))
    }
}

class ArcadeLogicTest {
    @Test fun mineFirstClickIsSafeAndFloods() {
        val b = MineBoard(9, 9, 10)
        b.place(40, Random(1))
        assertFalse(b.mines[40])
        assertEquals(10, b.mines.count { it })
        val opened = b.reveal(40)
        assertTrue(opened.size >= 9)
        assertEquals(0, b.count(40))
    }

    @Test fun merge2048() {
        val b = Board2048(4)
        b.cells = intArrayOf(2, 2, 2, 2, 4, 0, 4, 8, 0, 0, 0, 0, 2, 0, 0, 2)
        val m = b.move(Dir.LEFT)
        assertTrue(m.moved)
        assertEquals(listOf(4, 4, 0, 0, 8, 8, 0, 0, 0, 0, 0, 0, 4, 0, 0, 0), b.cells.toList())
        assertEquals(4 + 4 + 8 + 4, b.score)
    }

    @Test fun tetrisRotationAndLineClear() {
        val b = TetrisBoard()
        assertTrue(b.spawn(Pieces.T))
        assertTrue(b.rotate(1))
        assertTrue(b.rotate(1))
        assertTrue(b.rotate(1))
        assertTrue(b.rotate(1))
        assertEquals(0, b.rot)
        // fill the bottom row except two cells and drop an O piece into the gap
        for (c in 0 until TetrisBoard.W) if (c != 4 && c != 5) {
            b.cells[(TetrisBoard.H - 1) * TetrisBoard.W + c] = 1
            b.cells[(TetrisBoard.H - 2) * TetrisBoard.W + c] = 1
        }
        b.spawn(Pieces.O)
        b.py = b.ghostY()
        b.lock()
        assertEquals(listOf(TetrisBoard.H - 2, TetrisBoard.H - 1), b.fullRows())
        b.clearRows(b.fullRows())
        assertTrue(b.cells.all { it == 0 })
    }

    @Test fun mazeIsFullyConnected() {
        val w = MazeMap.W; val h = MazeMap.H
        val start = MazeMap.START_Y * w + MazeMap.START_X
        val seen = HashSet<Int>().apply { add(start) }
        val stack = ArrayDeque<Int>().apply { add(start) }
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            val x = i % w; val y = i / w
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val nx = (x + dx + w) % w; val ny = y + dy
                if (ny !in 0 until h) continue
                if (MazeMap.ROWS[ny][nx] in ".o " && seen.add(ny * w + nx)) stack.add(ny * w + nx)
            }
        }
        for (y in 0 until h) for (x in 0 until w) if (MazeMap.ROWS[y][x] in ".o") assertTrue("pellet $x,$y", (y * w + x) in seen)
        for (row in MazeMap.ROWS) assertEquals(w, row.length)
    }

    @Test fun checkersRules() {
        val e = CheckersEngine()
        assertEquals(7, e.moves().size)
        // forced capture: set up a red man that can jump
        e.board.fill(0)
        e.board[5 * 8 + 2] = 1
        e.board[4 * 8 + 3] = -1
        e.board[0 * 8 + 1] = -1
        e.side = 1
        val ms = e.moves()
        assertEquals(1, ms.size)
        assertEquals(1, ms[0].captured.size)
        // double jump
        e.board[2 * 8 + 3] = -1
        e.board[0 * 8 + 1] = 0
        val dj = e.moves()
        assertEquals(1, dj.size)
        assertEquals(2, dj[0].captured.size)
        val before = e.board.copyOf()
        val u = e.make(dj[0])
        assertEquals(1, e.board[dj[0].to])           // lands on row 1: not crowned
        assertTrue(dj[0].captured.all { e.board[it] == 0 })
        e.unmake(dj[0], u)
        assertTrue(before.contentEquals(e.board))
        // a man reaching the far row is crowned
        e.board.fill(0); e.board[1 * 8 + 2] = 1; e.side = 1
        e.make(e.moves().first())
        assertTrue(e.board.any { it == 2 })
    }
}

class MusicTest {
    @Test fun songsAreWellFormed() {
        for (s in Music.ALL) {
            assertEquals(s.title, s.steps, Music.leadSteps(s))
            assertEquals(s.title, 16, Music.patternSteps(s.bassPattern))
            assertEquals(s.title, 16, Music.patternSteps(s.arpPattern))
            assertEquals(s.title, 16, Music.patternSteps(s.drumPattern))
            assertEquals(s.title, 16, Music.patternSteps(s.drumFill))
            val buf = Music.render(s)
            assertTrue(buf.isNotEmpty())
            assertTrue(buf.all { it in -1f..1f })
        }
    }
}
