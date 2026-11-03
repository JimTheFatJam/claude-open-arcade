package com.openarcade

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import com.openarcade.audio.Sfx
import com.openarcade.core.ArcadeApp
import com.openarcade.games.Difficulty
import com.openarcade.games.GameDef
import com.openarcade.games.GameHost
import com.openarcade.games.GameRegistry
import com.openarcade.games.GameResult
import com.openarcade.games.GameSession
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderFuzzTest {
    private val out = File(System.getProperty("screens.dir") ?: "build/screens").also { it.mkdirs() }

    private fun save(bmp: Bitmap, name: String) {
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    class FakeHost(override val def: GameDef, override val difficulty: Difficulty, override val px: Float) : GameHost {
        override val rng = Random(42)
        var time = 0f
        var result: GameResult? = null
        val ints = HashMap<String, Int>()
        override val playTime get() = time
        override fun sfx(s: Sfx) {}
        override fun vibrate(ms: Long) {}
        override fun unlock(localAchievementId: String) {
            require(def.achievements.any { it.id == localAchievementId }) { "unknown achievement $localAchievementId in ${def.id}" }
        }
        override fun toast(msg: String) {}
        override fun gameOver(result: GameResult) { this.result = result }
        override fun bestScore() = 0
        override fun bestTime() = 0
        override fun getInt(key: String, def: Int) = ints[key] ?: def
        override fun putInt(key: String, v: Int) { ints[key] = v }
    }

    private var downTime = 0L
    private fun ev(action: Int, x: Float, y: Float): MotionEvent {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        return MotionEvent.obtain(downTime, now, action, x, y, 0)
    }

    private fun drive(s: GameSession, host: FakeHost, area: RectF, frames: Int, rnd: Random, c: Canvas) {
        var i = 0
        while (i < frames && !s.finished) {
            when (rnd.nextInt(6)) {
                0 -> { // tap
                    val x = area.left + rnd.nextFloat() * area.width(); val y = area.top + rnd.nextFloat() * area.height()
                    s.onTouch(ev(MotionEvent.ACTION_DOWN, x, y)); s.step(1 / 60f); s.onTouch(ev(MotionEvent.ACTION_UP, x, y))
                }
                1 -> { // swipe/drag
                    val x = area.left + rnd.nextFloat() * area.width(); val y = area.top + rnd.nextFloat() * area.height()
                    val dx = (rnd.nextFloat() - 0.5f) * area.width() * 0.6f; val dy = (rnd.nextFloat() - 0.5f) * area.height() * 0.6f
                    s.onTouch(ev(MotionEvent.ACTION_DOWN, x, y))
                    for (k in 1..6) { s.onTouch(ev(MotionEvent.ACTION_MOVE, x + dx * k / 6, y + dy * k / 6)); s.step(1 / 60f); host.time += 1 / 60f }
                    s.onTouch(ev(MotionEvent.ACTION_UP, x + dx, y + dy))
                }
                2 -> s.actions().takeIf { it.isNotEmpty() }?.let { a -> val act = a[rnd.nextInt(a.size)]; if (act.isEnabled()) act.onClick() }
                else -> {}
            }
            s.step(1 / 60f); host.time += 1 / 60f
            s.hud()
            if (i % 50 == 0) s.draw(c)
            i++
        }
    }

    @Test fun allGamesSurviveFuzzing() {
        val failures = ArrayList<String>()
        for (def in GameRegistry.all) for (d in Difficulty.values()) for ((w, h) in listOf(1080 to 2280, 2280 to 1080)) {
            val tag = "${def.id}-${d.name.lowercase()}-${if (w < h) "port" else "land"}"
            try {
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                val host = FakeHost(def, d, 6f)
                val area = RectF(18f, 250f, w - 18f, h - 160f)
                val s = def.factory(host)
                s.layout(area); s.start(null); s.layout(area)
                // let worker threads (puzzle generation / AI) finish
                val rnd = Random(tag.hashCode())
                for (k in 0 until 400) { s.step(1 / 60f); if (k % 40 == 0) Thread.sleep(25) }
                Thread.sleep(300)
                s.step(1 / 60f)
                c.drawColor(0xFF0F0F23.toInt()); s.draw(c)
                if (d == Difficulty.NORMAL) save(bmp, "game-$tag-start")
                drive(s, host, area, 900, rnd, c)
                // save / restore round trip
                val saved = s.save()
                if (saved != null && !s.finished) {
                    val s2 = def.factory(host)
                    s2.layout(area); s2.start(org.json.JSONObject(saved.toString())); s2.layout(area)
                    s2.step(1 / 60f); s2.draw(c)
                }
                for (k in 0 until 200) { s.step(1 / 60f); if (k % 40 == 0) Thread.sleep(20) }
                c.drawColor(0xFF0F0F23.toInt()); s.draw(c)
                if (d == Difficulty.NORMAL) save(bmp, "game-$tag-played")
                println("OK $tag finished=${s.finished} result=${host.result?.won}")
            } catch (t: Throwable) {
                failures.add("$tag: $t\n" + t.stackTrace.take(6).joinToString("\n") { "    at $it" })
            }
        }
        failures.forEach { println("FAIL $it") }
        assertTrue("${failures.size} failures", failures.isEmpty())
    }

    @Test fun consoleScreens() {
        val ctx: android.content.Context = org.robolectric.RuntimeEnvironment.getApplication()
        // phones and tablets, portrait and landscape, at realistic densities
        val devices = listOf(
            Triple("phone-port", 1080 to 2340, "420dpi"),
            Triple("phone-land", 2340 to 1080, "420dpi"),
            Triple("small-port", 720 to 1280, "xhdpi"),
            Triple("tablet-port", 1600 to 2560, "xhdpi"),
            Triple("tablet-land", 2560 to 1600, "xhdpi"),
            Triple("tablet7-land", 1920 to 1200, "tvdpi"),
        )
        for ((tag, size, dpi) in devices) {
            val (w, h) = size
            org.robolectric.RuntimeEnvironment.setQualifiers("+$dpi")
            val app = ArcadeApp(ctx) {}
            app.storage.resetTutorials()
            app.resize(w, h)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            fun shot(name: String, frames: Int = 3) { repeat(frames) { app.frame(c) }; Thread.sleep(20); app.frame(c); save(bmp, "screen-$tag-$name") }
            app.replaceAll(com.openarcade.ui.screens.SplashScreen(app), animate = false); shot("splash", 30)
            app.replaceAll(com.openarcade.ui.screens.HomeScreen(app), animate = false); shot("home")
            app.push(com.openarcade.ui.screens.LibraryScreen(app), animate = false); shot("library"); app.pop(false)
            app.push(com.openarcade.ui.screens.SettingsScreen(app), animate = false); shot("settings"); app.pop(false)
            app.push(com.openarcade.ui.screens.StatsScreen(app), animate = false); shot("stats"); app.pop(false)
            app.push(com.openarcade.ui.screens.AchievementsScreen(app), animate = false); shot("awards"); app.pop(false)
            for (id in listOf("meowdoku", "sudoku", "chess", "tetromino", "minesweeper", "2048", "flowlink", "mazemuncher", "nonogram")) {
                val gs = com.openarcade.ui.screens.GameScreen(app, GameRegistry.byId(id)!!)
                app.push(gs, animate = false); shot("$id-menu")
                if (id == "meowdoku") {
                    for (k in gs.def.tutorial.indices) {
                        val ts = com.openarcade.ui.screens.TutorialScreen(app, gs.def) {}
                        app.pushOverlay(ts)
                        repeat(k) { ts.javaClass.getDeclaredMethod("next").apply { isAccessible = true }.invoke(ts) }
                        shot("meowdoku-tutorial-$k", 60)
                        app.popOverlay(ts)
                    }
                }
                app.storage.setTutorialSeen(id)
                gs.startRoundForTest()
                repeat(40) { app.frame(c); Thread.sleep(10) }
                if (id == "meowdoku") {
                    // play a little: mark some cells, then ask for a hint
                    val s = gs.sessionForTest!!
                    val a = s.area
                    for (k in 0 until 6) {
                        val x = a.left + a.width() * (0.2f + 0.12f * k); val y = a.top + a.height() * 0.3f
                        s.onTouch(ev(MotionEvent.ACTION_DOWN, x, y)); s.onTouch(ev(MotionEvent.ACTION_UP, x, y))
                    }
                    s.actions().first { it.label == "HINT" }.onClick()
                }
                shot("$id-play", 10)
                if (id == "meowdoku") {
                    gs.key(android.view.KeyEvent.KEYCODE_P); shot("meowdoku-paused", 5)
                    gs.key(android.view.KeyEvent.KEYCODE_P)
                    gs.gameOver(GameResult(true, null, 110, null, "LEVEL 1  6X6  HEARTS \u0001\u0001\u0001"))
                    shot("meowdoku-win", 40)
                }
                app.pop(false)
            }
            app.destroy()
        }
    }

    // ------------------------------------------------------------------ Meowdoku gameplay
    private fun newMeow(d: Difficulty = Difficulty.EASY): Pair<com.openarcade.games.meowdoku.MeowdokuSession, FakeHost> {
        val def = GameRegistry.byId("meowdoku")!!
        val host = FakeHost(def, d, 6f)
        val s = def.factory(host) as com.openarcade.games.meowdoku.MeowdokuSession
        val area = RectF(0f, 0f, 1080f, 1700f)
        s.layout(area); s.start(null)
        var k = 0
        while (s.puzzleForTest == null && k++ < 400) { Thread.sleep(10); s.step(0.016f) }
        s.layout(area)
        return s to host
    }

    @Test fun meowdokuWrongCatsCostHeartsAndCanLose() {
        val (s, host) = newMeow()
        val p = s.puzzleForTest!!
        val wrong = (0 until p.n * p.n).filter { !p.isSolutionCat(it) }
        s.setMarkForTest(wrong[0], com.openarcade.games.meowdoku.Mark.CAT)
        assertTrue(s.heartsForTest == 2)
        // a wrong cat is rejected and the cell is shown as impossible
        assertTrue(s.marksForTest[wrong[0]] == com.openarcade.games.meowdoku.Mark.X)
        s.setMarkForTest(wrong[1], com.openarcade.games.meowdoku.Mark.CAT)
        s.setMarkForTest(wrong[2], com.openarcade.games.meowdoku.Mark.CAT)
        assertTrue(s.heartsForTest == 0)
        assertTrue(s.finished)
        repeat(100) { s.step(0.02f) }
        assertTrue(host.result?.won == false)
    }

    @Test fun meowdokuSolvingWinsAndBoardObeysAllRules() {
        for (d in Difficulty.values()) {
            val (s, host) = newMeow(d)
            val p = s.puzzleForTest!!
            for (r in 0 until p.n) s.setMarkForTest(r * p.n + p.solution[r], com.openarcade.games.meowdoku.Mark.CAT)
            assertTrue(s.finished)
            repeat(120) { s.step(0.02f) }
            assertTrue("won on $d", host.result?.won == true)
            val cats = s.marksForTest.indices.filter { s.marksForTest[it] == com.openarcade.games.meowdoku.Mark.CAT }
            assertTrue(cats.size == p.n)
            val cols = IntArray(p.n) { r -> cats.first { it / p.n == r } % p.n }
            assertTrue(com.openarcade.games.meowdoku.MeowLogic.isValidSolution(p.n, p.regions, cols))
            assertTrue(host.ints["lvl_${d.ordinal}"] == 1)   // progression advanced
        }
    }

    @Test fun meowdokuHintsAloneSolveThePuzzle() {
        val (s, host) = newMeow(Difficulty.HARD)
        val hint = s.actions().first { it.label == "HINT" }
        var guard = 0
        while (!s.finished && guard++ < 500) { hint.onClick(); hint.onClick() }   // show, then apply
        assertTrue(s.finished)
        repeat(120) { s.step(0.02f) }
        assertTrue(host.result?.won == true)
        assertTrue(s.heartsForTest == 3)
    }

    @Test fun meowdokuSaveRestoreAndUndo() {
        val (s, host) = newMeow(Difficulty.NORMAL)
        val p = s.puzzleForTest!!
        val cat = p.solution[0]
        s.setMarkForTest(cat, com.openarcade.games.meowdoku.Mark.CAT)
        val wrong = (0 until p.n * p.n).first { !p.isSolutionCat(it) && s.marksForTest[it] == 0 }
        s.setMarkForTest(wrong, com.openarcade.games.meowdoku.Mark.CAT)   // -1 heart
        val x = (0 until p.n * p.n).first { s.marksForTest[it] == 0 }
        s.setMarkForTest(x, com.openarcade.games.meowdoku.Mark.X)
        val saved = org.json.JSONObject(s.save()!!.toString())
        val s2 = GameRegistry.byId("meowdoku")!!.factory(host) as com.openarcade.games.meowdoku.MeowdokuSession
        s2.layout(RectF(0f, 0f, 1080f, 1700f)); s2.start(saved)
        assertTrue(s2.marksForTest.contentEquals(s.marksForTest))
        assertTrue(s2.heartsForTest == 2)
        assertTrue(s2.puzzleForTest!!.regions.contentEquals(p.regions))
        // undo removes the last X but never refunds a heart
        s.undoForTest()
        assertTrue(s.marksForTest[x] == 0)
        assertTrue(s.heartsForTest == 2)
    }

    @Test fun musicToggleWorksAndPersists() {
        val ctx: android.content.Context = org.robolectric.RuntimeEnvironment.getApplication()
        val app = ArcadeApp(ctx) {}
        val before = app.audio.musicEnabled
        app.toggleMusic()
        assertTrue(app.audio.musicEnabled == !before)
        assertTrue(app.storage.musicOn == !before)
        // a fresh app instance (e.g. after restart) picks up the saved preference
        val app2 = ArcadeApp(ctx) {}
        assertTrue(app2.audio.musicEnabled == !before)
        app.toggleMusic()
        assertTrue(app.storage.musicOn == before)
        app.destroy(); app2.destroy()
    }

    // ------------------------------------------------------------------ saved games vs difficulty
    private fun playSome(gs: com.openarcade.ui.screens.GameScreen, app: ArcadeApp, c: Canvas, rnd: Random) {
        val s = gs.sessionForTest ?: return
        repeat(120) { k ->
            if (k % 3 == 0) {
                val a = s.area
                val x = a.left + rnd.nextFloat() * a.width(); val y = a.top + rnd.nextFloat() * a.height()
                val dx = (rnd.nextFloat() - 0.5f) * a.width() * 0.5f; val dy = (rnd.nextFloat() - 0.5f) * a.height() * 0.5f
                gs.touch(ev(MotionEvent.ACTION_DOWN, x, y))
                gs.touch(ev(MotionEvent.ACTION_MOVE, x + dx / 2, y + dy / 2))
                gs.touch(ev(MotionEvent.ACTION_MOVE, x + dx, y + dy))
                gs.touch(ev(MotionEvent.ACTION_UP, x + dx, y + dy))
            }
            app.frame(c)
            if (k % 30 == 0) Thread.sleep(15)
        }
    }

    /** The reported bug: 2048 played at 3x3, quit, switch to 4x4, continue -> crash (twice). */
    @Test fun game2048ContinueAfterChangingBoardSize() {
        val ctx: android.content.Context = org.robolectric.RuntimeEnvironment.getApplication()
        val app = ArcadeApp(ctx) {}
        app.resize(1080, 2340)
        val c = Canvas(Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888))
        val def = GameRegistry.byId("2048")!!
        app.storage.setTutorialSeen(def.id)
        val gs = com.openarcade.ui.screens.GameScreen(app, def)
        app.replaceAll(gs, animate = false)
        gs.selectDifficultyForTest(Difficulty.HARD)          // 3x3
        gs.startRoundForTest()
        playSome(gs, app, c, Random(1))
        gs.quitToMenuForTest()
        assertTrue(app.storage.hasSave(def.id))
        gs.selectDifficultyForTest(Difficulty.NORMAL)        // 4x4
        gs.continueForTest()
        playSome(gs, app, c, Random(2))
        assertTrue(gs.crashesForTest == 0)
        assertTrue(gs.stateForTest == "PLAYING")
        assertTrue("continues the 3x3 round", gs.difficultyForTest == Difficulty.HARD)
        // re-opening the app and continuing again also works
        gs.quitToMenuForTest()
        val gs2 = com.openarcade.ui.screens.GameScreen(app, def)
        app.replaceAll(gs2, animate = false)
        gs2.continueForTest(); playSome(gs2, app, c, Random(3))
        assertTrue(gs2.crashesForTest == 0 && gs2.stateForTest == "PLAYING")
        app.destroy()
    }

    /** Every game: save at difficulty A, switch the menu to B, continue. Must resume at A without errors. */
    @Test fun everyGameContinuesSafelyAcrossDifficulties() {
        val ctx: android.content.Context = org.robolectric.RuntimeEnvironment.getApplication()
        val app = ArcadeApp(ctx) {}
        app.resize(1080, 2340)
        val c = Canvas(Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888))
        val failures = ArrayList<String>()
        for (def in GameRegistry.all) {
            app.storage.setTutorialSeen(def.id)
            for (a in Difficulty.values()) for (b in Difficulty.values()) {
                if (a == b) continue
                val gs = com.openarcade.ui.screens.GameScreen(app, def)
                app.replaceAll(gs, animate = false)
                gs.selectDifficultyForTest(a)
                gs.startRoundForTest()
                repeat(30) { app.frame(c); Thread.sleep(5) }    // let generators / AI finish
                playSome(gs, app, c, Random(def.id.hashCode() + a.ordinal))
                if (gs.stateForTest != "PLAYING") { app.storage.putSave(def.id, null); continue }   // round ended
                gs.quitToMenuForTest()
                val hadSave = app.storage.hasSave(def.id)
                gs.selectDifficultyForTest(b)
                if (hadSave) {
                    gs.continueForTest()
                    playSome(gs, app, c, Random(7))
                    if (gs.difficultyForTest != a) failures.add("${def.id} $a->$b resumed at ${gs.difficultyForTest}")
                }
                if (gs.crashesForTest > 0) failures.add("${def.id} $a->$b: game threw and had to be reset")
                app.storage.putSave(def.id, null)
            }
        }
        failures.forEach { println("FAIL $it") }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        app.destroy()
    }

    /** Saves written by older versions have no difficulty tag: they must never crash a game either. */
    @Test fun legacySavesWithoutDifficultyNeverCrash() {
        val failures = ArrayList<String>()
        for (def in GameRegistry.all) for (a in Difficulty.values()) {
            val host = FakeHost(def, a, 6f)
            val s = def.factory(host)
            val area = RectF(18f, 250f, 1062f, 2180f)
            s.layout(area); s.start(null); s.layout(area)
            repeat(200) { s.step(1 / 60f); if (it % 40 == 0) Thread.sleep(20) }
            val saved = s.save() ?: continue
            for (b in Difficulty.values()) {
                val h2 = FakeHost(def, b, 6f)
                val s2 = def.factory(h2)
                s2.layout(area)
                val restored = try { s2.start(org.json.JSONObject(saved.toString())); true } catch (e: Exception) { false }
                if (!restored) continue      // rejected at start: the host falls back to a fresh round
                try {
                    s2.layout(area)
                    val bmp = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888)
                    drive(s2, h2, area, 200, Random(3), Canvas(bmp))
                    s2.draw(Canvas(bmp)); s2.hud(); s2.save()
                } catch (e: Throwable) {
                    failures.add("${def.id} saved at $a, loaded at $b: $e")
                }
            }
        }
        failures.forEach { println("FAIL $it") }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    // ------------------------------------------------------------------ updater
    private fun updaterRelease(tag: String, digest: String?, withSumFile: Boolean = true) = """
        {"tag_name":"$tag","name":"Open Arcade $tag","draft":false,"prerelease":false,"html_url":"https://github.com/x/y",
         "body":"- New automatic updates\n- Better chess pieces\n\n---\nInstall stuff",
         "assets":[{"name":"OpenArcade-$tag.apk","size":4,"browser_download_url":"https://dl/OpenArcade-$tag.apk"${if (digest != null) ""","digest":"sha256:$digest"""" else ""}}
         ${if (withSumFile) """,{"name":"OpenArcade-$tag.apk.sha256","size":88,"browser_download_url":"https://dl/OpenArcade-$tag.apk.sha256"}""" else ""}]}
    """.trimIndent()

    private fun waitIdle() {
        var k = 0
        val busy = setOf(com.openarcade.update.UpdateManager.Phase.CHECKING, com.openarcade.update.UpdateManager.Phase.DOWNLOADING,
            com.openarcade.update.UpdateManager.Phase.VERIFYING, com.openarcade.update.UpdateManager.Phase.INSTALLING)
        Thread.sleep(50)
        while (com.openarcade.update.UpdateManager.state.phase in busy && k++ < 200) Thread.sleep(25)
        Thread.sleep(50)
    }

    @Test fun updaterChecksOnlyOnWifi() {
        val um = com.openarcade.update.UpdateManager
        val ctx: android.content.Context = org.robolectric.RuntimeEnvironment.getApplication()
        val cfg = com.openarcade.update.AppUpdateConfig.CONFIG
        val http = FakeHttp(mapOf(cfg.latestReleaseUrl to { _ -> FakeHttp.json(updaterRelease("v9.0.0", "a".repeat(64))) }))
        var wifi = false
        um.init(ctx, cfg, http) { wifi }
        um.installedVersionOverride = "1.2.0"
        um.checkOnLaunch(); waitIdle()
        assertTrue("no network use off Wi-Fi", http.requests.isEmpty())
        um.check(manual = true)
        assertTrue(um.state.phase == com.openarcade.update.UpdateManager.Phase.NO_WIFI && http.requests.isEmpty())
        wifi = true
        um.checkOnLaunch(); waitIdle()
        assertTrue(um.state.phase == com.openarcade.update.UpdateManager.Phase.AVAILABLE)
        assertTrue(um.autoPopupPending)
        assertTrue(um.state.release!!.notes == "- New automatic updates\n- Better chess pieces")
        // skipping silences automatic offers of that version
        um.skip(); um.autoPopupPending = false
        um.checkOnLaunch(); waitIdle()
        assertTrue(um.state.phase == com.openarcade.update.UpdateManager.Phase.IDLE && !um.autoPopupPending)
        // ...but a manual check still shows it
        um.check(manual = true); waitIdle()
        assertTrue(um.state.phase == com.openarcade.update.UpdateManager.Phase.AVAILABLE)
        // up to date
        um.installedVersionOverride = "9.0.0"
        um.check(manual = true); waitIdle()
        assertTrue(um.state.phase == com.openarcade.update.UpdateManager.Phase.UP_TO_DATE)
        um.installedVersionOverride = null
    }

    @Test fun updaterBacksOffWhenRateLimited() {
        val um = com.openarcade.update.UpdateManager
        val ctx: android.content.Context = org.robolectric.RuntimeEnvironment.getApplication()
        val cfg = com.openarcade.update.AppUpdateConfig.CONFIG
        val reset = System.currentTimeMillis() / 1000 + 3600
        val http = FakeHttp(mapOf(cfg.latestReleaseUrl to { _ ->
            FakeHttp.ok(ByteArray(0), 403, mapOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to reset.toString())) }))
        um.init(ctx, cfg, http) { true }
        um.checkOnLaunch(); waitIdle()
        assertTrue(um.state.phase == com.openarcade.update.UpdateManager.Phase.IDLE)   // automatic: silent
        um.checkOnLaunch(); waitIdle()
        assertTrue("second launch waits for the rate-limit reset", http.requests.size == 1)
        um.check(manual = true); waitIdle()
        assertTrue(um.state.phase == com.openarcade.update.UpdateManager.Phase.ERROR && um.state.message.contains("BUSY"))
        ctx.getSharedPreferences("app_updates", 0).edit().clear().apply()
    }

    @Test fun updaterRejectsBadDownloads() {
        val um = com.openarcade.update.UpdateManager
        val ctx: android.content.Context = org.robolectric.RuntimeEnvironment.getApplication()
        val cfg = com.openarcade.update.AppUpdateConfig.CONFIG
        val apk = byteArrayOf(1, 2, 3, 4)   // not a real APK
        val good = java.security.MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
        fun run(json: String, extra: Map<String, (Map<String, String>) -> com.openarcade.update.HttpClient.Response> = emptyMap()): com.openarcade.update.UpdateManager.State {
            val http = FakeHttp(mapOf(cfg.latestReleaseUrl to { _: Map<String, String> -> FakeHttp.json(json) },
                "https://dl/OpenArcade-v9.0.0.apk" to FakeHttp.ranged(apk)) + extra)
            um.init(ctx, cfg, http) { true }
            um.installedVersionOverride = "1.2.0"
            um.check(manual = true); waitIdle()
            um.download(); waitIdle()
            return um.state
        }
        // wrong checksum -> rejected, file removed
        var st = run(updaterRelease("v9.0.0", "b".repeat(64)))
        assertTrue(st.phase == com.openarcade.update.UpdateManager.Phase.ERROR && st.message.contains("CHECKSUM"))
        assertTrue(!java.io.File(um.updatesDir, "OpenArcade-v9.0.0.apk").exists())
        // right checksum but not a genuine signed APK of this app -> rejected, file removed
        st = run(updaterRelease("v9.0.0", good))
        assertTrue(st.phase == com.openarcade.update.UpdateManager.Phase.ERROR)
        println("invalid apk rejected with: ${st.message}")
        assertTrue(!java.io.File(um.updatesDir, "OpenArcade-v9.0.0.apk").exists())
        // no checksum published anywhere -> refuse to download
        st = run(updaterRelease("v9.0.0", null, withSumFile = false))
        assertTrue(st.phase == com.openarcade.update.UpdateManager.Phase.ERROR && st.message.contains("CHECKSUM"))
        // checksum from the .sha256 asset is used when GitHub has no digest
        st = run(updaterRelease("v9.0.0", null), mapOf("https://dl/OpenArcade-v9.0.0.apk.sha256" to { _ -> FakeHttp.json("$good  OpenArcade-v9.0.0.apk") }))
        assertTrue(st.phase == com.openarcade.update.UpdateManager.Phase.ERROR && !st.message.contains("CHECKSUM"))
        um.installedVersionOverride = null
    }

    @Test fun updaterCleansUpOldDownloads() {
        val um = com.openarcade.update.UpdateManager
        val ctx: android.content.Context = org.robolectric.RuntimeEnvironment.getApplication()
        val dir = java.io.File(ctx.cacheDir, "updates").apply { mkdirs() }
        java.io.File(dir, "OpenArcade-v1.0.0.apk").writeText("old")
        java.io.File(dir, "OpenArcade-v1.1.0.apk.part").writeText("old part")
        java.io.File(dir, "OpenArcade-v99.0.0.apk").writeText("future")
        um.init(ctx, com.openarcade.update.AppUpdateConfig.CONFIG, FakeHttp(emptyMap())) { false }
        assertTrue(!java.io.File(dir, "OpenArcade-v1.0.0.apk").exists())
        assertTrue(!java.io.File(dir, "OpenArcade-v1.1.0.apk.part").exists())
        assertTrue(java.io.File(dir, "OpenArcade-v99.0.0.apk").exists())
        dir.deleteRecursively()
    }

    @Test fun updatePopupOnlyOnHomeScreenAndScreenshots() {
        val um = com.openarcade.update.UpdateManager
        val ctx: android.content.Context = org.robolectric.RuntimeEnvironment.getApplication()
        val cfg = com.openarcade.update.AppUpdateConfig.CONFIG
        um.init(ctx, cfg, FakeHttp(emptyMap())) { true }
        val rel = com.openarcade.update.GitHubReleases.parseRelease(updaterRelease("v1.3.0", "a".repeat(64)).replace("\"size\":4", "\"size\":215040"), cfg)!!
        val app = ArcadeApp(ctx) {}
        app.resize(1080, 2340)
        val bmp = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        app.storage.setTutorialSeen("snake")
        val gs = com.openarcade.ui.screens.GameScreen(app, GameRegistry.byId("snake")!!)
        app.replaceAll(gs, animate = false)
        gs.startRoundForTest()
        um.setStateForTest(com.openarcade.update.UpdateManager.State(com.openarcade.update.UpdateManager.Phase.AVAILABLE, rel))
        um.autoPopupPending = true
        repeat(5) { app.frame(c) }
        assertTrue("never interrupts gameplay", app.top === gs && um.autoPopupPending)
        app.replaceAll(com.openarcade.ui.screens.HomeScreen(app), animate = false)
        repeat(5) { app.frame(c) }
        assertTrue("offered on the home screen", app.top is com.openarcade.ui.screens.UpdateScreen && !um.autoPopupPending)
        app.destroy()
        // screenshots of each dialog state on phone and tablet
        val states = listOf(
            "available" to com.openarcade.update.UpdateManager.State(com.openarcade.update.UpdateManager.Phase.AVAILABLE, rel),
            "downloading" to com.openarcade.update.UpdateManager.State(com.openarcade.update.UpdateManager.Phase.DOWNLOADING, rel, 131072, 215040),
            "ready" to com.openarcade.update.UpdateManager.State(com.openarcade.update.UpdateManager.Phase.READY, rel),
            "error" to com.openarcade.update.UpdateManager.State(com.openarcade.update.UpdateManager.Phase.ERROR, rel, message = "CHECKSUM MISMATCH - THE DOWNLOAD IS CORRUPTED"),
            "nowifi" to com.openarcade.update.UpdateManager.State(com.openarcade.update.UpdateManager.Phase.NO_WIFI),
        )
        for ((tag, size, dpi) in listOf(Triple("phone-port", 1080 to 2340, "420dpi"), Triple("tablet-land", 2560 to 1600, "xhdpi"))) {
            org.robolectric.RuntimeEnvironment.setQualifiers("+$dpi")
            val a2 = ArcadeApp(ctx) {}
            a2.resize(size.first, size.second)
            val b2 = Bitmap.createBitmap(size.first, size.second, Bitmap.Config.ARGB_8888)
            val c2 = Canvas(b2)
            a2.replaceAll(com.openarcade.ui.screens.HomeScreen(a2), animate = false)
            for ((name, st) in states) {
                um.setStateForTest(st)
                val us = com.openarcade.ui.screens.UpdateScreen(a2)
                a2.pushOverlay(us)
                repeat(3) { a2.frame(c2) }
                save(b2, "screen-$tag-update-$name")
                a2.popOverlay(us)
            }
            a2.push(com.openarcade.ui.screens.SettingsScreen(a2), animate = false)
            repeat(3) { a2.frame(c2) }
            save(b2, "screen-$tag-settings-updates")
            a2.destroy()
        }
        um.setStateForTest(com.openarcade.update.UpdateManager.State(com.openarcade.update.UpdateManager.Phase.IDLE))
    }
}
