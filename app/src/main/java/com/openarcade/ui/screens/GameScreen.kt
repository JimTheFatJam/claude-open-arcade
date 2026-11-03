package com.openarcade.ui.screens

import android.graphics.Canvas
import android.graphics.RectF
import android.view.KeyEvent
import android.view.MotionEvent
import com.openarcade.audio.Sfx
import com.openarcade.audio.Song
import com.openarcade.core.ArcadeApp
import com.openarcade.core.RecordInfo
import com.openarcade.games.Difficulty
import com.openarcade.games.GameDef
import com.openarcade.games.GameHost
import com.openarcade.games.GameResult
import com.openarcade.games.GameSession
import com.openarcade.games.ScoreKind
import com.openarcade.games.formatTime
import com.openarcade.gfx.Align
import com.openarcade.gfx.Gfx
import com.openarcade.gfx.Icons
import com.openarcade.gfx.Pal
import com.openarcade.gfx.PixelFont
import com.openarcade.ui.BarButton
import com.openarcade.ui.Button
import com.openarcade.ui.ButtonSet
import com.openarcade.ui.Screen
import com.openarcade.ui.Scroller
import kotlin.math.sin
import kotlin.random.Random

/**
 * Hosts one game: its start menu (difficulty, continue, tutorial, scores), the running session
 * with HUD and action bar, the pause menu and the results panel.
 */
class GameScreen(app: ArcadeApp, override val def: GameDef) : Screen(app), GameHost {
    private enum class State { MENU, PLAYING, PAUSED, OVER }

    override val title get() = def.title
    override val music: Song get() = def.music

    private var state = State.MENU
    private var session: GameSession? = null
    private var _difficulty = Difficulty.values()[app.storage.difficulty(def.id).coerceIn(0, 2)]
    private var _playTime = 0f
    private var unsavedTime = 0f
    private var result: GameResult? = null
    private var record: RecordInfo? = null
    private var overT = 0f
    private var t = 0f

    private val menuButtons = ButtonSet(app)
    private val menuScroll = Scroller(app)
    private val overlayButtons = ButtonSet(app)
    private val actionButtons = ButtonSet(app)
    private val hudBox = RectF()
    private val actionBox = RectF()
    private val playArea = RectF()
    private val overlayBox = RectF()
    private var menuContentH = 0f
    private var menuLayout = MenuLayout()

    private class MenuLayout {
        val header = RectF()
        var statsY = 0f
        var diffY = 0f
        var descY = 0f
        var scoresY = 0f
        var achY = 0f
        var colLeft = 0f
        var colRight = 0f
        // second column (description, scores, awards); equals the first on narrow screens
        var c2Left = 0f
        var c2Right = 0f
    }

    private val confetti = ArrayList<FloatArray>() // x, y, vx, vy, color

    // ------------------------------------------------------------------ GameHost
    override val difficulty: Difficulty get() = _difficulty
    override val rng: Random = Random(System.nanoTime())
    override val px: Float get() = app.p
    override val playTime: Float get() = _playTime
    override fun sfx(s: Sfx) = app.audio.play(s)
    override fun vibrate(ms: Long) = app.vibrate(ms)
    override fun unlock(localAchievementId: String) = app.progress.unlock(def.achievementId(localAchievementId))
    override fun toast(msg: String) = app.toast(msg)
    override fun bestScore(): Int = app.storage.stats(def.id).best
    override fun bestTime(): Int = app.storage.stats(def.id).bestTime
    override fun getInt(key: String, def: Int): Int = app.storage.gameInt(this.def.id, key, def)
    override fun putInt(key: String, v: Int) = app.storage.setGameInt(def.id, key, v)

    override fun gameOver(result: GameResult) {
        if (state != State.PLAYING) return
        flushTime()
        this.result = result
        record = app.progress.record(def, result)
        app.storage.putSave(def.id, null)
        app.audio.play(if (result.won) Sfx.WIN else Sfx.LOSE)
        app.vibrate(if (result.won) 40 else 80)
        state = State.OVER
        overT = 0f
        if (result.won) spawnConfetti()
        layoutOverlay()
    }

    // ------------------------------------------------------------------ lifecycle
    override fun layout() {
        layoutPlay()
        layoutMenu()
        layoutOverlay()
    }

    override fun resumed() {
        layoutMenu()
        layoutOverlay()
    }

    override fun barButtons(): List<BarButton> = when (state) {
        State.MENU -> listOf(BarButton({ if (app.storage.isFavorite(def.id)) Icons.STAR else Icons.STAR_EMPTY }, { toggleFavorite() }))
        State.PLAYING -> listOf(
            BarButton({ Icons.HELP }, { pause(); openTutorial(null) }),
            BarButton({ Icons.PAUSE }, { pause() }, { Pal.ORANGE }),
        )
        else -> listOf(BarButton({ Icons.HELP }, { openTutorial(null) }))
    }

    private fun toggleFavorite() {
        val now = app.storage.toggleFavorite(def.id)
        app.toast(if (now) "ADDED TO FAVORITES" else "REMOVED FROM FAVORITES", if (now) Icons.STAR else Icons.STAR_EMPTY)
        if (now) app.progress.unlock("arcade.fav")
    }

    override fun appPaused() {
        if (state == State.PLAYING) pause(silent = true)
        saveProgress()
    }

    override fun exit() {
        // leaving for another screen (e.g. settings from the top bar) pauses the round first
        if (state == State.PLAYING) pause(silent = true)
        saveProgress()
    }

    override fun back(): Boolean = when (state) {
        State.PLAYING -> { pause(); true }
        State.PAUSED -> { resume(); true }
        State.OVER -> { toMenu(); true }
        State.MENU -> false
    }

    // ------------------------------------------------------------------ flow
    private fun openTutorial(after: (() -> Unit)?) {
        app.pushOverlay(TutorialScreen(app, def) { after?.invoke() })
    }

    private fun play(continueSave: Boolean) {
        if (!continueSave && !app.storage.tutorialSeen(def.id)) {
            openTutorial { startRound(false) }
            return
        }
        startRound(continueSave)
    }

    /** Starts a fresh round immediately, skipping the menu (used by automated UI tests). */
    internal fun startRoundForTest() = startRound(false)

    internal val sessionForTest: GameSession? get() = session

    private fun startRound(continueSave: Boolean) {
        val saved = if (continueSave) app.storage.savedGame(def.id) else null
        if (!continueSave) app.storage.putSave(def.id, null)
        val s = def.factory(this)
        session = s
        _playTime = saved?.optDouble("__time", 0.0)?.toFloat() ?: 0f
        unsavedTime = 0f
        result = null
        confetti.clear()
        state = State.PLAYING
        layoutPlay()
        try {
            s.start(saved)
        } catch (e: Exception) {
            // a corrupt save should never brick a game: fall back to a fresh round
            app.storage.putSave(def.id, null)
            val fresh = def.factory(this)
            session = fresh
            layoutPlay()
            fresh.start(null)
            _playTime = 0f
        }
        session?.layout(playArea)
        if (saved == null) app.progress.roundStarted(def)
        app.audio.play(Sfx.START)
        buildActions()
    }

    private fun pause(silent: Boolean = false) {
        if (state != State.PLAYING) return
        state = State.PAUSED
        flushTime()
        if (!silent) app.audio.play(Sfx.CLICK)
        layoutOverlay()
    }

    private fun resume() {
        if (state != State.PAUSED) return
        state = State.PLAYING
        app.audio.play(Sfx.SELECT)
    }

    private fun restart() {
        val s = session
        if (s != null && state != State.OVER && _playTime > 20f) {
            app.showDialog("RESTART?", "Your current progress in this round will be lost.", listOf(
                "CANCEL" to {},
                "RESTART" to { startRound(false) },
            ))
        } else startRound(false)
    }

    private fun toMenu() {
        saveProgress()
        session = null
        state = State.MENU
        confetti.clear()
        layoutMenu()
    }

    private fun flushTime() {
        if (unsavedTime > 0f) { app.progress.addTime(def, unsavedTime); unsavedTime = 0f }
    }

    private fun saveProgress() {
        flushTime()
        val s = session ?: return
        if (state == State.PLAYING || state == State.PAUSED) {
            val o = try { s.save() } catch (e: Exception) { null }
            o?.put("__time", _playTime.toDouble())
            app.storage.putSave(def.id, o)
        }
    }

    // ------------------------------------------------------------------ layout
    private fun layoutPlay() {
        val p = app.p
        val s = session
        val hasActions = s?.actions()?.isNotEmpty() == true
        if (app.landscape) {
            val side = minOf(p * 62, app.w * 0.28f)
            hudBox.set(content.left + p * 2, content.top + p * 3, content.left + side, content.bottom - p * 3)
            actionBox.set(hudBox)
            playArea.set(content.left + side + p * 4, content.top + p * 3, content.right - p * 4, content.bottom - p * 3)
        } else {
            // keep HUD and controls a sensible width on tablets instead of stretching edge to edge
            val barW = minOf(content.width() - p * 6, p * 260)
            val bl = content.centerX() - barW / 2
            hudBox.set(bl, content.top + p * 3, bl + barW, content.top + p * 19)
            val ah = if (hasActions) p * 22 else 0f
            actionBox.set(bl, content.bottom - ah - p * 3, bl + barW, content.bottom - p * 3)
            playArea.set(content.left + p * 3, hudBox.bottom + p * 4, content.right - p * 3, (if (hasActions) actionBox.top else content.bottom) - p * 4)
        }
        s?.layout(playArea)
        buildActions()
    }

    private fun buildActions() {
        actionButtons.clear()
        val s = session ?: return
        val acts = s.actions()
        if (acts.isEmpty()) return
        val p = app.p
        val gap = p * 3
        if (app.landscape) {
            val hudItems = maxOf(3, s.hud().size)
            var y = hudBox.top + hudItems * (PixelFont.height(app.tSmall) * 2 + p * 8) + p * 6
            val bh = p * 18
            for (a in acts) {
                val b = Button(a.label, a.icon, Pal.BG3, Sfx.CLICK) { a.onClick() }
                b.textSize = app.tSmall
                actionButtons.add(b).set(actionBox.left, y, actionBox.right, y + bh)
                y += bh + gap
            }
        } else {
            val n = acts.size
            val bw = (actionBox.width() - gap * (n - 1)) / n
            acts.forEachIndexed { i, a ->
                val x = actionBox.left + i * (bw + gap)
                val b = Button(a.label, a.icon, Pal.BG3, Sfx.CLICK) { a.onClick() }
                b.textSize = app.tSmall
                actionButtons.add(b).set(x, actionBox.top, x + bw, actionBox.bottom)
            }
        }
    }

    private fun layoutMenu() {
        val p = app.p
        menuButtons.clear()
        val m = menuLayout
        val wide = app.wideLayout
        val colGap = p * 10
        val colW = if (wide) minOf((content.width() - p * 8 - colGap) / 2, p * 220) else minOf(content.width() - p * 8, p * 210)
        if (wide) {
            m.colLeft = app.w / 2 - colGap / 2 - colW; m.colRight = m.colLeft + colW
            m.c2Left = app.w / 2 + colGap / 2; m.c2Right = m.c2Left + colW
        } else {
            m.colLeft = (app.w - colW) / 2; m.colRight = m.colLeft + colW
            m.c2Left = m.colLeft; m.c2Right = m.colRight
        }
        var y = content.top + p * 5
        // header grows to fit the tagline so long text never spills out of the panel
        val iconS = p * 36
        val tw = colW - p * 16 - iconS
        val ts = Gfx.fitSize(def.title, tw, app.tBody * 2f)
        val textH = p * 10 + PixelFont.height(ts) + PixelFont.height(app.tSmall) + p * 4 +
            Gfx.paragraphHeight(def.tagline, tw, app.tSmall) + p * 2
        val hh = maxOf(p * 46, textH)
        m.header.set(m.colLeft, y, m.colRight, y + hh)
        y += hh + p * 6
        m.statsY = y
        y += p * 22
        val gap = p * 3
        if (def.hasDifficulty) {
            m.diffY = y
            y += PixelFont.height(app.tSmall) + p * 4
            val bw = (colW - gap * 2) / 3
            Difficulty.values().forEachIndexed { i, d ->
                val x = m.colLeft + i * (bw + gap)
                val b = Button(def.difficultyName(d), null, Pal.BG2, Sfx.CLICK) {
                    _difficulty = d
                    app.storage.setDifficulty(def.id, d.ordinal)
                    layoutMenu()
                }
                b.active = d == _difficulty
                b.color = if (b.active) Pal.darker(def.accent, 0.35f) else Pal.BG2
                b.textColor = Pal.WHITE
                b.textSize = app.tSmall
                menuButtons.add(b).set(x, y, x + bw, y + p * 15)
            }
            y += p * 15 + p * 7
        }
        val bh = p * 20
        if (app.storage.hasSave(def.id)) {
            menuButtons.add(Button("CONTINUE", Icons.PLAY, Pal.DGREEN) { play(true) })
                .set(m.colLeft, y, m.colRight, y + bh)
            y += bh + gap
            menuButtons.add(Button("NEW GAME", Icons.RESTART, Pal.BLUE) { play(false) }).set(m.colLeft, y, m.colRight, y + bh)
        } else {
            menuButtons.add(Button("PLAY", Icons.PLAY, Pal.DGREEN) { play(false) })
                .set(m.colLeft, y, m.colRight, y + bh)
        }
        y += bh + gap
        menuButtons.add(Button("HOW TO PLAY", Icons.HELP, Pal.PURPLE) { openTutorial(null) }).set(m.colLeft, y, m.colRight, y + bh)
        y += bh + p * 8
        val endA = y
        if (wide) y = content.top + p * 5 + PixelFont.height(app.tSmall)
        m.descY = y
        y += Gfx.paragraphHeight(def.description, colW - p * 8, app.tSmall) + p * 14
        m.scoresY = y
        val top = app.storage.stats(def.id).topScores
        y += PixelFont.height(app.tBody) + p * 6 + maxOf(1, top.size) * (PixelFont.LINE * app.tSmall + p) + p * 8
        m.achY = y
        y += PixelFont.height(app.tBody) + p * 6 + def.achievements.size * p * 22 + p * 6
        y = maxOf(y, endA)
        menuContentH = y - content.top
        menuScroll.setContentHeight(menuContentH, content.height())
    }

    private fun layoutOverlay() {
        val p = app.p
        overlayButtons.clear()
        val w = minOf(app.w - p * 12, p * 150)
        val bh = p * 18
        val gap = p * 3
        when (state) {
            State.PAUSED -> {
                val labels = ArrayList<Triple<String, com.openarcade.gfx.PixelArt?, () -> Unit>>()
                labels.add(Triple("RESUME", Icons.PLAY) { resume() })
                labels.add(Triple("RESTART", Icons.RESTART) { restart() })
                labels.add(Triple("HOW TO PLAY", Icons.HELP) { openTutorial(null) })
                labels.add(Triple("SETTINGS", Icons.GEAR) { app.push(SettingsScreen(app)) })
                labels.add(Triple("QUIT TO MENU", Icons.HOME) { toMenu() })
                val total = p * 22 + labels.size * (bh + gap) + p * 4
                overlayBox.set((app.w - w) / 2, content.centerY() - total / 2, (app.w + w) / 2, content.centerY() + total / 2)
                var y = overlayBox.top + p * 20
                labels.forEachIndexed { i, (l, ic, fn) ->
                    val b = Button(l, ic, if (i == 0) Pal.DGREEN else Pal.BG3) { fn() }
                                        overlayButtons.add(b).set(overlayBox.left + p * 6, y, overlayBox.right - p * 6, y + bh)
                    y += bh + gap
                }
            }
            State.OVER -> {
                // height = title + every text line + "new best" + buttons, so nothing overlaps
                val r = result
                val title = r?.title ?: if (r?.won == true) "YOU WIN!" else "GAME OVER"
                val ts = Gfx.fitSize(title, w - p * 10, app.tBody * 2f)
                var textH = p * 14 + PixelFont.height(ts)
                for ((txt, _) in overLines()) textH += PixelFont.height(Gfx.fitSize(txt, w - p * 10, app.tBody)) + p * 5
                if (record?.newBest == true) textH += PixelFont.height(app.tSmall) + p * 5
                val total = textH + p * 4 + bh + p * 6
                overlayBox.set((app.w - w) / 2, content.centerY() - total / 2, (app.w + w) / 2, content.centerY() + total / 2)
                val bw = (overlayBox.width() - p * 12 - gap) / 2
                val y = overlayBox.bottom - p * 6 - bh
                overlayButtons.add(Button("AGAIN", Icons.RESTART, Pal.DGREEN) { startRound(false) })
                    .set(overlayBox.left + p * 6, y, overlayBox.left + p * 6 + bw, y + bh)
                overlayButtons.add(Button("MENU", Icons.HOME, Pal.BG3) { toMenu() })
                    .set(overlayBox.right - p * 6 - bw, y, overlayBox.right - p * 6, y + bh)
            }
            else -> {}
        }
    }

    // ------------------------------------------------------------------ update & draw
    override fun update(dt: Float) {
        t += dt
        when (state) {
            State.PLAYING -> {
                _playTime += dt
                unsavedTime += dt
                session?.step(dt)
            }
            State.PAUSED -> if (session?.realtime == false) session?.step(0f)
            State.OVER -> { overT += dt }
            State.MENU -> menuScroll.update(dt)
        }
        if (confetti.isNotEmpty()) {
            val g = app.p * 120
            val it = confetti.iterator()
            while (it.hasNext()) {
                val f = it.next()
                f[0] += f[2] * dt; f[1] += f[3] * dt; f[3] += g * dt
                if (f[1] > app.h + app.p * 10) it.remove()
            }
        }
    }

    private fun spawnConfetti() {
        val p = app.p
        repeat(90) {
            confetti.add(floatArrayOf(
                app.w / 2 + (rng.nextFloat() - 0.5f) * app.w * 0.3f, content.centerY() - p * 30,
                (rng.nextFloat() - 0.5f) * p * 220, -p * (60 + rng.nextFloat() * 140),
                Pal.RAINBOW[rng.nextInt(Pal.RAINBOW.size)].toFloat(),
            ))
        }
    }

    override fun draw(c: Canvas) {
        when (state) {
            State.MENU -> drawMenu(c)
            else -> {
                drawPlay(c)
                if (state == State.PAUSED) drawPaused(c)
                if (state == State.OVER) drawOver(c)
            }
        }
        val p = app.p
        for (f in confetti) Gfx.rect(c, f[0], f[1], f[0] + p * 2, f[1] + p * 2, f[4].toInt())
    }

    private fun drawPlay(c: Canvas) {
        val s = session ?: return
        val p = app.p
        // HUD
        val items = s.hud()
        if (app.landscape) {
            Gfx.panel(c, hudBox, Pal.withAlpha(Pal.BG1, 230), Pal.BG3, p, shadow = false)
            var y = hudBox.top + p * 6
            for (it in items) {
                Gfx.text(c, it.label, hudBox.left + p * 5, y, app.tSmall, Pal.GREY, shadow = null)
                y += PixelFont.height(app.tSmall) + p * 2
                val vs = Gfx.fitSize(it.value, hudBox.width() - p * 10, app.tBody)
                Gfx.text(c, it.value, hudBox.left + p * 5, y, vs, it.color ?: Pal.WHITE)
                y += PixelFont.height(app.tSmall) + p * 6
            }
        } else if (items.isNotEmpty()) {
            Gfx.panel(c, hudBox, Pal.withAlpha(Pal.BG1, 230), Pal.BG3, p, shadow = false)
            val n = items.size
            val cw = hudBox.width() / n
            items.forEachIndexed { i, it ->
                val cx = hudBox.left + cw * i + cw / 2
                Gfx.text(c, it.label, cx, hudBox.top + p * 3, app.tSmall * 0.8f, Pal.GREY, Align.CENTER, null)
                val vs = Gfx.fitSize(it.value, cw - p * 4, app.tSmall)
                Gfx.text(c, it.value, cx, hudBox.top + p * 4 + PixelFont.height(app.tSmall * 0.8f) + p * 2, vs, it.color ?: Pal.WHITE, Align.CENTER)
            }
        }
        // game
        c.save()
        c.clipRect(playArea.left - p * 2, playArea.top - p * 2, playArea.right + p * 2, playArea.bottom + p * 2)
        s.draw(c)
        c.restore()
        // actions
        val acts = s.actions()
        if (acts.size == actionButtons.buttons.size) {
            acts.forEachIndexed { i, a ->
                val b = actionButtons.buttons[i]
                b.active = a.isActive()
                b.enabled = a.isEnabled()
                b.color = if (b.active) Pal.darker(def.accent, 0.35f) else Pal.BG3
                b.textColor = Pal.WHITE
            }
        } else buildActions()
        actionButtons.draw(c)
    }

    private fun drawPaused(c: Canvas) {
        val p = app.p
        Gfx.rect(c, 0f, content.top, app.w, app.h, Pal.withAlpha(Pal.BLACK, 170))
        Gfx.panel(c, overlayBox, Pal.BG1, Pal.ORANGE, p)
        Gfx.text(c, "PAUSED", overlayBox.centerX(), overlayBox.top + p * 7, app.tBody * 1.5f, Pal.ORANGE, Align.CENTER)
        overlayButtons.draw(c)
        Gfx.text(c, "${def.difficultyName(_difficulty)}  ${formatTime(_playTime.toInt())}", overlayBox.centerX(),
            overlayBox.bottom + p * 5, app.tSmall, Pal.GREY, Align.CENTER)
    }

    /** The text lines of the results panel (shared by layout and drawing so they always agree). */
    private fun overLines(): List<Pair<String, Int>> {
        val r = result ?: return emptyList()
        val lines = ArrayList<Pair<String, Int>>()
        r.detail?.let { lines.add(it to Pal.LGREY) }
        val rec = record
        when (def.scoreKind) {
            ScoreKind.HIGH_SCORE -> {
                r.score?.let { lines.add("SCORE  $it" to Pal.WHITE) }
                rec?.let { lines.add("BEST  ${it.best}" to Pal.YELLOW) }
            }
            ScoreKind.BEST_TIME -> {
                r.timeSec?.let { lines.add("TIME  ${formatTime(it)}" to Pal.WHITE) }
                rec?.let { if (it.bestTime > 0) lines.add("BEST  ${formatTime(it.bestTime)}" to Pal.YELLOW) }
            }
            ScoreKind.WINS -> {
                val st = app.storage.stats(def.id)
                lines.add("WINS ${st.wins}  LOSSES ${st.losses}" to Pal.WHITE)
                if (st.streak > 1) lines.add("WIN STREAK  ${st.streak}" to Pal.YELLOW)
            }
        }
        return lines
    }

    private fun drawOver(c: Canvas) {
        val r = result ?: return
        val p = app.p
        val k = (overT / 0.3f).coerceIn(0f, 1f)
        Gfx.rect(c, 0f, content.top, app.w, app.h, Pal.withAlpha(Pal.BLACK, (150 * k).toInt()))
        val drop = (1f - k) * p * 40
        c.save()
        c.translate(0f, -drop)
        val color = if (r.won) Pal.GREEN else Pal.RED
        Gfx.panel(c, overlayBox, Pal.BG1, color, p)
        val title = r.title ?: if (r.won) "YOU WIN!" else "GAME OVER"
        val ts = Gfx.fitSize(title, overlayBox.width() - p * 10, app.tBody * 2f)
        val wave = sin(t * 5f) * p
        Gfx.text(c, title, overlayBox.centerX(), overlayBox.top + p * 8 + wave, ts, color, Align.CENTER)
        var y = overlayBox.top + p * 14 + PixelFont.height(ts)
        val rec = record
        for ((txt, col) in overLines()) {
            val sz = Gfx.fitSize(txt, overlayBox.width() - p * 10, app.tBody)
            Gfx.text(c, txt, overlayBox.centerX(), y, sz, col, Align.CENTER)
            y += PixelFont.height(sz) + p * 5
        }
        if (rec?.newBest == true && app.blink(2f) > 0.3f) {
            Gfx.text(c, "${PixelFont.STAR} NEW BEST! ${PixelFont.STAR}", overlayBox.centerX(), y + p, app.tSmall, Pal.YELLOW, Align.CENTER)
        }
        c.restore()
        if (k >= 1f) overlayButtons.draw(c)
    }

    private fun drawMenu(c: Canvas) {
        val p = app.p
        val m = menuLayout
        c.save()
        c.clipRect(content)
        c.translate(0f, -menuScroll.offset)
        // header
        Gfx.panel(c, m.header, Pal.BG1, def.accent, p)
        val iconS = p * 36
        val ib = RectF(m.header.left + p * 5, m.header.top + p * 5, m.header.left + p * 5 + iconS, m.header.top + p * 5 + iconS)
        Gfx.rect(c, ib, Pal.BG0)
        val bob = sin(t * 2.5f) * p
        def.icon.drawFit(c, RectF(ib.left + p * 2, ib.top + p * 2 + bob, ib.right - p * 2, ib.bottom - p * 2 + bob))
        val tx = ib.right + p * 6
        val tw = m.header.right - tx - p * 5
        val ts = Gfx.fitSize(def.title, tw, app.tBody * 2f)
        Gfx.text(c, def.title, tx, m.header.top + p * 7, ts, Pal.WHITE)
        var ty = m.header.top + p * 10 + PixelFont.height(ts)
        Gfx.text(c, def.category.label, tx, ty, app.tSmall, def.accent)
        ty += PixelFont.height(app.tSmall) + p * 4
        Gfx.paragraph(c, def.tagline, tx, ty, tw, app.tSmall, Pal.LGREY)

        // stats strip
        val st = app.storage.stats(def.id)
        val cells = listOf(
            "PLAYED" to st.played.toString(),
            "WINS" to st.wins.toString(),
            when (def.scoreKind) {
                ScoreKind.HIGH_SCORE -> "BEST" to (if (st.best > 0) st.best.toString() else "-")
                ScoreKind.BEST_TIME -> "BEST TIME" to (if (st.bestTime > 0) formatTime(st.bestTime) else "-")
                ScoreKind.WINS -> "STREAK" to st.bestStreak.toString()
            },
        )
        val cw = (m.colRight - m.colLeft) / cells.size
        cells.forEachIndexed { i, (label, value) ->
            val box = RectF(m.colLeft + i * cw + p, m.statsY, m.colLeft + (i + 1) * cw - p, m.statsY + p * 18)
            Gfx.rect(c, box, Pal.withAlpha(Pal.BG1, 220))
            Gfx.frame(c, box, p * 0.5f, Pal.BG3)
            Gfx.text(c, label, box.centerX(), box.top + p * 3, app.tSmall * 0.8f, Pal.GREY, Align.CENTER, null)
            val vs = Gfx.fitSize(value, box.width() - p * 4, app.tBody)
            Gfx.text(c, value, box.centerX(), box.top + p * 9, vs, Pal.YELLOW, Align.CENTER)
        }
        if (def.hasDifficulty) Gfx.text(c, "DIFFICULTY", m.colLeft, m.diffY, app.tSmall, Pal.CYAN)
        menuButtons.draw(c)

        // description
        Gfx.text(c, "ABOUT", m.c2Left, m.descY - p * 1, app.tSmall, Pal.CYAN)
        Gfx.paragraph(c, def.description, m.c2Left + p * 4, m.descY + p * 7, m.c2Right - m.c2Left - p * 8, app.tSmall, Pal.LGREY)

        // top scores
        Gfx.text(c, if (def.scoreKind == ScoreKind.BEST_TIME) "BEST TIMES" else "HIGH SCORES", m.c2Left, m.scoresY, app.tBody, Pal.CYAN)
        var y = m.scoresY + PixelFont.height(app.tBody) + p * 6
        if (st.topScores.isEmpty() || def.scoreKind == ScoreKind.WINS) {
            val txt = if (def.scoreKind == ScoreKind.WINS) "WINS ${st.wins}  -  LOSSES ${st.losses}" else "NO SCORES YET - BE THE FIRST!"
            Gfx.text(c, txt, m.c2Left + p * 4, y, app.tSmall, Pal.GREY)
        } else {
            st.topScores.forEachIndexed { i, v ->
                val txt = if (def.scoreKind == ScoreKind.BEST_TIME) formatTime(v) else v.toString()
                val col = when (i) { 0 -> Pal.YELLOW; 1 -> Pal.LGREY; 2 -> Pal.ORANGE; else -> Pal.GREY }
                Gfx.text(c, "${i + 1}.", m.c2Left + p * 4, y, app.tSmall, col)
                Gfx.text(c, txt, m.c2Right - p * 4, y, app.tSmall, col, Align.RIGHT)
                Gfx.rect(c, m.c2Left + p * 20, y + PixelFont.height(app.tSmall) - p, m.c2Right - p * 4 - PixelFont.width(txt, app.tSmall) - p * 4,
                    y + PixelFont.height(app.tSmall), Pal.BG2)
                y += PixelFont.LINE * app.tSmall + p
            }
        }

        // achievements
        Gfx.text(c, "AWARDS", m.c2Left, m.achY, app.tBody, Pal.CYAN)
        y = m.achY + PixelFont.height(app.tBody) + p * 6
        for (a in def.achievements) {
            val got = app.storage.isUnlocked(def.achievementId(a.id))
            val box = RectF(m.c2Left, y, m.c2Right, y + p * 19)
            Gfx.rect(c, box, if (got) Pal.withAlpha(Pal.DGREEN, 120) else Pal.withAlpha(Pal.BG1, 200))
            Gfx.frame(c, box, p * 0.5f, if (got) Pal.GREEN else Pal.BG3)
            val ic = if (got) Icons.TROPHY else Icons.LOCK
            ic.drawFit(c, RectF(box.left + p * 4, box.top + p * 4, box.left + p * 15, box.bottom - p * 4), tint = if (got) null else Pal.DGREY)
            Gfx.text(c, a.title, box.left + p * 19, box.top + p * 3, app.tSmall, if (got) Pal.YELLOW else Pal.LGREY)
            val ds = Gfx.fitSize(a.desc, box.width() - p * 23, app.tSmall * 0.8f)
            Gfx.text(c, a.desc, box.left + p * 19, box.top + p * 5 + PixelFont.height(app.tSmall), ds, Pal.GREY, shadow = null)
            y += p * 22
        }
        c.restore()
        menuScroll.drawBar(c, content)
    }

    // ------------------------------------------------------------------ input
    override fun touch(e: MotionEvent): Boolean {
        when (state) {
            State.MENU -> {
                if (menuScroll.touch(e)) { menuButtons.cancel(); return true }
                return menuButtons.touch(e, menuScroll.offset)
            }
            State.PLAYING -> {
                if (actionButtons.touch(e)) return true
                val s = session ?: return false
                if (!s.finished) s.onTouch(e)
                return true
            }
            State.PAUSED, State.OVER -> {
                if (state == State.OVER && overT < 0.4f) return true
                return overlayButtons.touch(e)
            }
        }
    }

    override fun key(keyCode: Int): Boolean {
        if (state == State.PLAYING) {
            if (keyCode == KeyEvent.KEYCODE_P || keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_BUTTON_START) { pause(); return true }
            return session?.onKey(keyCode) ?: false
        }
        if (state == State.PAUSED && (keyCode == KeyEvent.KEYCODE_P || keyCode == KeyEvent.KEYCODE_BUTTON_START)) { resume(); return true }
        return false
    }
}
