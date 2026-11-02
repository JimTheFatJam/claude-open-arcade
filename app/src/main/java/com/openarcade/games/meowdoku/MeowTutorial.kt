package com.openarcade.games.meowdoku

import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import com.openarcade.audio.Sfx
import com.openarcade.games.TutorialDemo
import com.openarcade.games.TutorialPage
import com.openarcade.gfx.Icons
import com.openarcade.gfx.PixelArt
import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random

/** The interactive Meowdoku tutorial: every rule is tried out on a small real puzzle. */
object MeowTutorial {
    /** A fixed 5x5 example plus a mid-solve position where one region has a single free cell. */
    private class Example(val puzzle: MeowPuzzle, val forcedState: IntArray, val forcedCell: Int, val forcedRegion: Int)

    private val example: Example by lazy {
        var seed = 7L
        while (true) {
            val p = MeowLogic.generate(5, Random(seed++), MeowLogic.R_CONFINE, 0, attempts = 60)
            val state = IntArray(25)
            var guard = 0
            while (guard++ < 60) {
                val step = MeowLogic.nextStep(5, p.regions, state) ?: break
                // look for a "last free cell in a region" moment with a little progress made
                if (step.rule == MeowLogic.R_SINGLE && state.count { it == Mark.CAT } >= 1 &&
                    step.focus.map { p.regions[it] }.toSet().size == 1 && step.focus.size > 1
                ) {
                    return@lazy Example(p, state.copyOf(), step.place[0], p.regions[step.place[0]])
                }
                MeowLogic.apply(state, step)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    /** Base for demos that show a board scaled into the demo box. */
    private abstract class BoardDemo(val n: Int, val regions: IntArray) : TutorialDemo() {
        val marks = IntArray(n * n)
        var cell = 0f; var ox = 0f; var oy = 0f
        var highlight: Collection<Int> = emptyList()

        fun fit(box: RectF) {
            cell = floor(minOf(box.width(), box.height()) / n)
            ox = floor(box.centerX() - cell * n / 2); oy = floor(box.centerY() - cell * n / 2)
        }

        fun cellAt(x: Float, y: Float): Int {
            val c = floor((x - ox) / cell).toInt(); val r = floor((y - oy) / cell).toInt()
            return if (c in 0 until n && r in 0 until n) r * n + c else -1
        }

        override fun draw(c: Canvas, box: RectF, p: Float, t: Float) {
            fit(box)
            MeowArt.drawBoard(c, n, regions, marks, ox, oy, cell, p, t, highlight = highlight, dance = done)
        }

        fun touching(a: Int, b: Int) = a != b && abs(a / n - b / n) <= 1 && abs(a % n - b % n) <= 1
    }

    /** 1. The goal: cats appear one by one on a solved board. */
    private class GoalDemo : BoardDemo(5, example.puzzle.regions) {
        override val task = "WATCH THE 5 CATS TAKE THEIR PLACES. TAP TO REPLAY."
        private var at = 0f
        override fun update(dt: Float) {
            at += dt
            val shown = (at / 0.6f).toInt().coerceAtMost(n)
            for (r in 0 until n) marks[r * n + example.puzzle.solution[r]] = if (r < shown) Mark.CAT else Mark.EMPTY
            if (shown == n && !done) complete("ONE CAT IN EVERY ROW, COLUMN AND REGION - AND NONE TOUCH!")
        }
        override fun onTouch(e: MotionEvent, box: RectF) {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) { at = 0f; sound(Sfx.CLICK) }
        }
    }

    /** 2. Regions: tap each coloured area to outline it. */
    private class RegionDemo : BoardDemo(5, example.puzzle.regions) {
        override val task = "TAP EACH COLORED REGION TO OUTLINE IT."
        private val seen = HashSet<Int>()
        override fun onTouch(e: MotionEvent, box: RectF) {
            if (e.actionMasked != MotionEvent.ACTION_DOWN) return
            val i = cellAt(e.x, e.y); if (i < 0) return
            val g = regions[i]
            highlight = regions.indices.filter { regions[it] == g }
            seen.add(g)
            if (seen.size == n) complete("ALL 5 REGIONS FOUND! EACH ONE NEEDS EXACTLY ONE CAT.")
            else good("REGION ${seen.size} OF $n: ${highlight.size} CELLS. THICK BLACK LINES ARE REGION BORDERS.", Sfx.TICK)
        }
    }

    /** 3. What a cat blocks: row & column, then region, then its 8 neighbours. */
    private class BlockDemo : BoardDemo(5, example.puzzle.regions) {
        private val cat = 2 * n + example.puzzle.solution[2]
        private var stage = 0
        override val task = "TAP THE CAT TO SEE WHICH CELLS IT BLOCKS."
        init { marks[cat] = Mark.CAT; highlight = listOf(cat) }
        override fun onTouch(e: MotionEvent, box: RectF) {
            if (e.actionMasked != MotionEvent.ACTION_DOWN) return
            if (cellAt(e.x, e.y) != cat) { bad("TAP THE CAT ITSELF!"); return }
            stage = (stage % 3) + 1
            for (i in marks.indices) if (i != cat) marks[i] = Mark.EMPTY
            val r = cat / n; val c = cat % n
            val blocked = when (stage) {
                1 -> marks.indices.filter { it / n == r || it % n == c }
                2 -> marks.indices.filter { it / n == r || it % n == c || regions[it] == regions[cat] }
                else -> MeowLogic.blockedBy(n, regions, cat)
            }
            for (b in blocked) if (b != cat) marks[b] = Mark.X
            when (stage) {
                1 -> good("RULES 1 & 2: NO OTHER CAT IN ITS ROW OR COLUMN. TAP AGAIN!", Sfx.TICK)
                2 -> good("RULE 3: NO OTHER CAT IN ITS REGION EITHER. TAP AGAIN!", Sfx.TICK)
                else -> complete("RULE 4: CATS NEVER TOUCH - SO ITS 8 NEIGHBOURS ARE BLOCKED TOO.")
            }
        }
    }

    /** 4. Diagonal adjacency: mark all 8 cells touching the cat. */
    private class TouchDemo : BoardDemo(5, example.puzzle.regions) {
        private val cat = 12
        override val task = "TAP ALL 8 CELLS TOUCHING THE CAT TO MARK THEM WITH X."
        init { marks[cat] = Mark.CAT }
        override fun onTouch(e: MotionEvent, box: RectF) {
            if (e.actionMasked != MotionEvent.ACTION_DOWN || done) return
            val i = cellAt(e.x, e.y); if (i < 0 || i == cat) return
            if (!touching(i, cat)) { bad("THAT CELL DOESN'T TOUCH THE CAT."); return }
            marks[i] = if (marks[i] == Mark.X) Mark.EMPTY else Mark.X
            val diagonal = i / n != cat / n && i % n != cat % n
            val left = 8 - marks.count { it == Mark.X }
            when {
                left == 0 -> complete("PERFECT! CORNER-TO-CORNER COUNTS AS TOUCHING.")
                marks[i] == Mark.X && diagonal -> good("YES - DIAGONAL CELLS TOUCH TOO! $left LEFT.", Sfx.TICK)
                marks[i] == Mark.X -> good("BLOCKED! $left LEFT - DON'T FORGET THE DIAGONALS.", Sfx.TICK)
                else -> good("UNMARKED. $left LEFT.", Sfx.CLICK)
            }
        }
    }

    /** 5. Marking impossible cells quickly: drag along a row. */
    private class MarkDemo : BoardDemo(5, example.puzzle.regions) {
        private val cat = example.puzzle.solution[0]
        override val task = "ROW 1 HAS ITS CAT. DRAG ACROSS ROW 1 TO CROSS OUT THE REST."
        init { marks[cat] = Mark.CAT }
        override fun onTouch(e: MotionEvent, box: RectF) {
            if (done) return
            if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
                val i = cellAt(e.x, e.y); if (i < 0 || marks[i] != Mark.EMPTY) return
                if (i / n != 0) { if (e.actionMasked == MotionEvent.ACTION_DOWN) bad("STAY IN ROW 1 - THE TOP ROW."); return }
                marks[i] = Mark.X
                sound(Sfx.TICK)
                if ((0 until n).all { marks[it] != Mark.EMPTY }) complete("DONE! X MEANS \"NO CAT CAN GO HERE\". MARKS ARE FREE - USE THEM A LOT.")
            }
        }
    }

    /** 6. Forced placement: a region with a single free cell. */
    private class ForcedDemo : BoardDemo(5, example.puzzle.regions) {
        override val task = "ONE REGION HAS ONLY ONE FREE CELL LEFT. TAP IT TO PLACE ITS CAT."
        private var misses = 0
        init { example.forcedState.copyInto(marks) }
        override fun onTouch(e: MotionEvent, box: RectF) {
            if (e.actionMasked != MotionEvent.ACTION_DOWN || done) return
            val i = cellAt(e.x, e.y); if (i < 0) return
            if (marks[i] != Mark.EMPTY) { bad("THAT CELL IS ALREADY DECIDED - PICK AN EMPTY ONE."); return }
            if (i == example.forcedCell) {
                marks[i] = Mark.CAT
                sound(Sfx.MEOW)
                highlight = emptyList()
                complete("MEOW! WHEN A ROW, COLUMN OR REGION HAS ONE FREE CELL, ITS CAT IS FORCED THERE.")
            } else {
                misses++
                if (misses >= 2) highlight = regions.indices.filter { regions[it] == example.forcedRegion }
                bad(if (misses >= 2) "LOOK AT THE OUTLINED REGION - ONLY ONE OF ITS CELLS IS STILL EMPTY." else "NOT THAT ONE. FIND A REGION WITH JUST ONE EMPTY CELL.")
            }
        }
    }

    private val HEART = PixelArt(
        ".RR...RR.",
        "RRRR.RRRR",
        "RWRRRRRRR",
        "RRRRRRRRR",
        ".RRRRRRR.",
        "..RRRRR..",
        "...RRR...",
        "....R....",
    )

    fun pages(): List<TutorialPage> = listOf(
        TutorialPage("THE GOAL",
            "Place cats so that every ROW, every COLUMN and every COLORED REGION has exactly one cat. " +
                "A 5x5 board needs 5 cats in 5 regions; a 9x9 board needs 9.",
            demo = { GoalDemo() }),
        TutorialPage("COLORED REGIONS",
            "The board is split into colored regions with irregular shapes. Thick black lines show where one region ends and the next begins.",
            demo = { RegionDemo() }),
        TutorialPage("THE FOUR RULES",
            "1. One cat per row.  2. One cat per column.  3. One cat per region.  4. Cats never touch - not even diagonally.",
            demo = { BlockDemo() }),
        TutorialPage("NO TOUCHING!",
            "Each cat blocks all 8 cells around it, including the 4 diagonal corners. Cats need their personal space!",
            demo = { TouchDemo() }),
        TutorialPage("MARK IMPOSSIBLE CELLS",
            "TAP a cell once to mark it with X - a cell where no cat can go. DRAG across cells to mark many at once. Tap an X again to place a cat there.",
            demo = { MarkDemo() }),
        TutorialPage("FORCED PLACEMENTS",
            "The key trick: when a row, column or region has only ONE cell that isn't crossed out, its cat must go there. Every puzzle can be solved by logic like this - no guessing needed.",
            demo = { ForcedDemo() }),
        TutorialPage("HEARTS, HINTS & TOOLS",
            "TAP: X, TAP AGAIN: cat, HOLD: cat. A wrong cat costs one of your 3 hearts. HINT shows the next logical step and explains it - press it again to apply. AUTO-X crosses out blocked cells for you. UNDO and CLEAR are always there.",
            HEART),
        TutorialPage("TIPS",
            "Start with small regions and short rows. If a region sits entirely inside one row, that row's other cells are blocked. Puzzles grow from 5x5 kittens to 9x9 lions as you win.",
            Icons.BULB),
    )
}
