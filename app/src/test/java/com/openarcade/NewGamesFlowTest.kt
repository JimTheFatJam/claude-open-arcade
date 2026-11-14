package com.openarcade

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import com.openarcade.core.ArcadeApp
import com.openarcade.games.Difficulty
import com.openarcade.games.GameRegistry
import com.openarcade.games.blockfit.BlockFitSession
import com.openarcade.games.blockfit.Shape
import com.openarcade.games.crate.CrateLevels
import com.openarcade.games.crate.CrateMove
import com.openarcade.games.crate.CrateSession
import com.openarcade.games.crate.CrateState
import com.openarcade.games.pipe.PipeSession
import com.openarcade.games.rollblock.RollLevels
import com.openarcade.games.rollblock.RollSession
import com.openarcade.ui.screens.GameScreen
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** End-to-end checks of the four newer games through the real console screens and storage. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NewGamesFlowTest {
    private val ctx: android.content.Context get() = org.robolectric.RuntimeEnvironment.getApplication()
    private val bmp = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888)
    private val c = Canvas(bmp)

    private var downTime = 0L
    private fun ev(action: Int, x: Float, y: Float): MotionEvent {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        return MotionEvent.obtain(downTime, now, action, x, y, 0)
    }

    private fun freshApp(): ArcadeApp {
        val app = ArcadeApp(ctx) {}
        app.storage.resetAll()
        app.resize(1080, 2340)
        return app
    }

    private fun open(app: ArcadeApp, id: String, d: Difficulty): GameScreen {
        val def = GameRegistry.byId(id)!!
        app.storage.setTutorialSeen(def.id)
        val gs = GameScreen(app, def)
        app.replaceAll(gs, animate = false)
        gs.selectDifficultyForTest(d)
        gs.startRoundForTest()
        repeat(5) { app.frame(c) }
        return gs
    }

    /** Runs frames in real time (the console measures frame time with the clock): ~12 ms each. */
    private fun frames(app: ArcadeApp, n: Int) = repeat(n) { app.frame(c); Thread.sleep(12) }

    @Test fun newGamesAreRegisteredAlongsideTheOriginals() {
        val ids = GameRegistry.all.map { it.id }
        assertEquals(20, ids.size)
        assertEquals(ids.size, ids.toSet().size)
        // the original sixteen are all still there, in their original order
        assertEquals(listOf("minesweeper", "2048", "sudoku", "meowdoku", "nonogram", "flowlink", "unblock", "lightsout", "tetromino", "snake",
            "mazemuncher", "brickbreaker", "paddleball", "alieninvasion", "chess", "checkers"), ids.take(16))
        assertEquals(listOf("rollblock", "blockfit", "cratepush", "piperush"), ids.takeLast(4))
        for (id in ids.takeLast(4)) {
            val def = GameRegistry.byId(id)!!
            assertTrue(def.tutorial.size >= 5)
            assertTrue(def.tutorial.any { it.demo != null })
            assertTrue(def.achievements.isNotEmpty())
        }
    }

    @Test fun rollBlockFallRetryWinAndProgressSurviveRestart() {
        val app = freshApp()
        val gs = open(app, "rollblock", Difficulty.EASY)
        val s = gs.sessionForTest as RollSession
        val lv = s.levelForTest
        assertEquals(RollLevels.packs[0][0], lv)
        // rolling left off the start pad falls; the level restarts by itself
        s.rollForTest(1)
        frames(app, 90)
        assertEquals(lv.start, s.stateForTest.pos)
        assertEquals("PLAYING", gs.stateForTest)
        // play the optimal solution
        for (d in lv.solve()!!) s.rollForTest(d)
        frames(app, 160)
        assertEquals("OVER", gs.stateForTest)
        assertEquals(1, app.storage.stats("rollblock").wins)
        assertEquals(2, app.storage.gameInt("rollblock", "unlocked_0"))
        assertTrue(app.storage.isUnlocked("rollblock.first_win"))
        assertTrue(app.storage.isUnlocked("rollblock.perfect"))
        app.destroy()
        // "reopen the app": a new console instance continues at level 2
        val app2 = ArcadeApp(ctx) {}
        app2.resize(1080, 2340)
        val gs2 = open(app2, "rollblock", Difficulty.EASY)
        assertEquals(RollLevels.packs[0][1], (gs2.sessionForTest as RollSession).levelForTest)
        assertEquals(1, app2.storage.stats("rollblock").wins)
        app2.destroy()
    }

    /** Breadth-first search over player + crate positions using the game's own move rules. */
    private fun crateSolution(level: com.openarcade.games.crate.CrateLevel): List<Int> {
        val start = CrateState(level)
        data class K(val p: Int, val b: List<Int>)
        val prev = HashMap<K, Pair<K, Int>?>()
        val k0 = K(start.player, start.boxes.toList())
        prev[k0] = null
        val q = ArrayDeque<K>(); q.add(k0)
        while (q.isNotEmpty()) {
            val k = q.removeFirst()
            for (d in 0 until 4) {
                val st = CrateState(level); st.restore(k.p, k.b.toIntArray(), 0, 0)
                if (st.move(d) == CrateMove.BLOCKED) continue
                val nk = K(st.player, st.boxes.toList())
                if (prev.containsKey(nk)) continue
                prev[nk] = k to d
                if (st.solved) {
                    val out = ArrayList<Int>(); var cur: K? = nk
                    while (true) { val pr = prev[cur] ?: break; out.add(pr.second); cur = pr.first }
                    return out.reversed()
                }
                q.add(nk)
            }
        }
        error("no solution")
    }

    @Test fun cratePushUndoWinAndBestMovesPersist() {
        val app = freshApp()
        val gs = open(app, "cratepush", Difficulty.EASY)
        val s = gs.sessionForTest as CrateSession
        val level = CrateLevels.packs[0][0]
        val sol = crateSolution(level)
        // a wrong step can always be undone
        s.moveForTest(sol[0])
        s.undoForTest()
        assertEquals(level.startPlayer, s.stateForTest.player)
        for (d in sol) s.moveForTest(d)
        frames(app, 120)
        assertEquals("OVER", gs.stateForTest)
        assertEquals(sol.size, app.storage.gameInt("cratepush", "best_0_0"))
        assertEquals(1, app.storage.gameInt("cratepush", "level_0"))
        app.destroy()
        val app2 = ArcadeApp(ctx) {}
        app2.resize(1080, 2340)
        assertEquals(sol.size, app2.storage.gameInt("cratepush", "best_0_0"))
        app2.destroy()
    }

    @Test fun cratePushSaveAndContinue() {
        val app = freshApp()
        val gs = open(app, "cratepush", Difficulty.NORMAL)
        val s = gs.sessionForTest as CrateSession
        val sol = crateSolution(CrateLevels.packs[1][0])
        for (d in sol.take(3)) s.moveForTest(d)
        val player = s.stateForTest.player
        gs.quitToMenuForTest()
        assertTrue(app.storage.hasSave("cratepush"))
        gs.continueForTest()
        val s2 = gs.sessionForTest as CrateSession
        assertEquals(player, s2.stateForTest.player)
        assertEquals(3, s2.stateForTest.moves)
        app.destroy()
    }

    @Test fun blockFitClearsLinesScoresAndEndsWhenNothingFits() {
        val host = RenderFuzzTest.FakeHost(GameRegistry.byId("blockfit")!!, Difficulty.NORMAL, 6f)
        val area = RectF(18f, 250f, 1062f, 2180f)
        val dot = Shape.ALL.first { it.size == 1 }
        // top row filled except the last square; three single squares in the tray
        val cells = IntArray(64).also { for (x in 0 until 7) it[x] = 3 }
        val saved = JSONObject().put("board", JSONArray(cells.toList())).put("tray", JSONArray(listOf(dot.id, dot.id, dot.id))).put("score", 0)
        val s = BlockFitSession(host)
        s.layout(area); s.start(saved); s.layout(area)
        assertTrue(s.placeForTest(0, 7, 0))
        assertTrue("row cleared", (0 until 8).all { s.boardForTest.get(it, 0) == 0 })
        assertEquals(1 + 10, s.scoreForTest)
        // blocks can't overlap: square (1,1) is taken
        s.boardForTest.cells[9] = 1
        assertFalse(s.placeForTest(1, 1, 1))
        assertTrue(s.placeForTest(1, 2, 1))
        // a full board with only big pieces left: game over
        val full = IntArray(64) { if (it == 0 || it == 27) 0 else 2 }
        val big = Shape.ALL.first { it.w == 3 && it.h == 3 }
        val s2 = BlockFitSession(host)
        s2.layout(area)
        s2.start(JSONObject().put("board", JSONArray(full.toList())).put("tray", JSONArray(listOf(big.id, -1, -1))).put("score", 420))
        repeat(200) { s2.step(1 / 60f); s2.draw(c) }
        assertTrue(s2.finished)
        assertNotNull(host.result)
        assertFalse(host.result!!.won)
        assertEquals(420, host.result!!.score)
    }

    @Test fun blockFitHighScoreSurvivesRestart() {
        val app = freshApp()
        val def = GameRegistry.byId("blockfit")!!
        // play a few real placements first
        val gs = open(app, "blockfit", Difficulty.NORMAL)
        val s = gs.sessionForTest as BlockFitSession
        repeat(4) {
            val slot = (0 until 3).firstOrNull { s.trayForTest[it] != null } ?: return@repeat
            val sh = s.trayForTest[slot]!!
            val spot = (0 until 64).first { s.boardForTest.fits(sh, it % 8, it / 8) }
            assertTrue(s.placeForTest(slot, spot % 8, spot / 8))
            app.frame(c)
        }
        assertTrue(s.scoreForTest > 0)
        gs.quitToMenuForTest()
        assertTrue(app.storage.hasSave(def.id))
        // continue a crowded round where the last block can't fit anywhere: it ends and the score is recorded
        val full = IntArray(64) { if (it == 0 || it == 27) 0 else 2 }
        val big = Shape.ALL.first { it.w == 3 && it.h == 3 }
        app.storage.putSave(def.id, JSONObject().put("board", JSONArray(full.toList())).put("tray", JSONArray(listOf(big.id, -1, -1)))
            .put("score", 777).put("__difficulty", Difficulty.NORMAL.ordinal))
        gs.continueForTest()
        frames(app, 200)
        assertEquals("OVER", gs.stateForTest)
        assertEquals(777, app.storage.stats(def.id).best)
        assertFalse(app.storage.hasSave(def.id))
        app.destroy()
        val app2 = ArcadeApp(ctx) {}
        assertEquals(777, app2.storage.stats(def.id).best)
        app2.destroy()
    }

    @Test fun pipeRushBuiltRouteDrainsAndOpenEndsLeak() {
        val def = GameRegistry.byId("piperush")!!
        val area = RectF(18f, 250f, 1062f, 2180f)
        for (d in Difficulty.values()) {
            val host = RenderFuzzTest.FakeHost(def, d, 6f)
            val s = PipeSession(host)
            s.layout(area); s.start(null); s.layout(area)
            val lv = s.levelForTest
            for ((i, cell) in lv.route.withIndex()) if (!lv.fixed[cell]) assertTrue(s.placeForTest(cell, lv.routeMasks[i]))
            s.startFlowForTest()
            var k = 0
            while (!s.finished && k++ < 60 * 400) { s.step(1 / 60f); if (k % 300 == 0) s.draw(c) }
            assertTrue("$d drained", host.result?.won == true)
            assertTrue(host.ints["unlocked_${d.ordinal}"] == 2)
            // nothing built: the water spills out of the tank's opening
            val h2 = RenderFuzzTest.FakeHost(def, d, 6f)
            val s2 = PipeSession(h2)
            s2.layout(area); s2.start(null); s2.startFlowForTest()
            k = 0
            while (!s2.finished && k++ < 60 * 60) s2.step(1 / 60f)
            assertTrue(h2.result?.won == false)
            assertEquals("LEAK!", h2.result?.title)
        }
    }

    @Test fun tutorialDemosCanBeCompleted() {
        val box = RectF(100f, 600f, 980f, 1300f)
        fun tap(demo: com.openarcade.games.TutorialDemo, x: Float, y: Float) {
            demo.onTouch(ev(MotionEvent.ACTION_DOWN, x, y), box); demo.onTouch(ev(MotionEvent.ACTION_UP, x, y), box)
            repeat(30) { demo.update(1 / 60f) }
            demo.draw(c, box, 6f, 1f)
        }
        fun demoOf(id: String) = GameRegistry.byId(id)!!.tutorial.first { it.demo != null }.demo!!.invoke().also { it.draw(c, box, 6f, 0f) }

        val roll = demoOf("rollblock")
        tap(roll, box.right - 2, box.centerY()); tap(roll, box.right - 2, box.centerY())
        assertTrue("roll demo", roll.done)

        val crate = demoOf("cratepush")
        repeat(3) { tap(crate, box.right - 2, box.centerY()) }
        assertTrue("crate demo", crate.done)

        val pipe = demoOf("piperush")
        val cell = minOf(box.width() / 4, box.height())
        val ox = kotlin.math.floor(box.centerX() - cell * 2)
        tap(pipe, ox + cell * 1.5f, box.centerY()); tap(pipe, ox + cell * 2.5f, box.centerY())
        assertTrue("pipe demo", pipe.done)

        // every page of every new tutorial renders, with taps and drags on its demo
        for (id in listOf("rollblock", "blockfit", "cratepush", "piperush")) for (page in GameRegistry.byId(id)!!.tutorial) {
            val d = page.demo?.invoke() ?: continue
            d.draw(c, box, 6f, 0f)
            for (k in 0 until 20) {
                val x = box.left + (k * 97 % 100) / 100f * box.width(); val y = box.top + (k * 53 % 100) / 100f * box.height()
                d.onTouch(ev(MotionEvent.ACTION_DOWN, x, y), box)
                d.onTouch(ev(MotionEvent.ACTION_MOVE, x + 40, y + 10), box)
                d.onTouch(ev(MotionEvent.ACTION_UP, x + 40, y + 10), box)
                d.update(0.1f); d.draw(c, box, 6f, k * 0.1f)
            }
        }
    }

    @Test fun audioSettingsStayGlobal() {
        // the new games use the console's one audio engine: the global toggles apply inside them
        val app = freshApp()
        if (app.audio.musicEnabled) app.toggleMusic()
        for (id in listOf("rollblock", "blockfit", "cratepush", "piperush")) {
            val gs = open(app, id, Difficulty.EASY)
            frames(app, 10)
            assertFalse(app.audio.musicEnabled)
            assertTrue(app.audio.sfxEnabled)
            gs.quitToMenuForTest()
        }
        assertFalse(ArcadeApp(ctx) {}.also { it.destroy() }.storage.musicOn)
        app.toggleMusic()
        assertTrue(app.audio.musicEnabled && app.storage.musicOn)
        app.destroy()
    }
}
