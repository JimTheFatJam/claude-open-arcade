package com.openarcade.games.invaders

import android.graphics.Canvas
import android.graphics.RectF
import android.view.KeyEvent
import android.view.MotionEvent
import com.openarcade.audio.Music
import com.openarcade.audio.Sfx
import com.openarcade.games.AchievementDef
import com.openarcade.games.Category
import com.openarcade.games.Difficulty
import com.openarcade.games.GameDef
import com.openarcade.games.GameHost
import com.openarcade.games.GameSession
import com.openarcade.games.HudItem
import com.openarcade.games.ScoreKind
import com.openarcade.games.TutorialPage
import com.openarcade.games.drawBoardFrame
import com.openarcade.gfx.Align
import com.openarcade.gfx.Gfx
import com.openarcade.gfx.Icons
import com.openarcade.gfx.Pal
import com.openarcade.gfx.PixelArt
import com.openarcade.gfx.PixelFont
import org.json.JSONObject
import kotlin.math.abs

class AlienInvasionSession(host: GameHost) : GameSession(host) {
    override val realtime = true
    private val FW = 224f
    private val FH = 256f
    private val COLS = 9
    private val ROWS = 5
    private val PLAYER_Y = 232f
    private val BUNKER_Y = 196f

    private val fireRate = when (host.difficulty) { Difficulty.EASY -> 0.55f; Difficulty.NORMAL -> 0.9f; Difficulty.HARD -> 1.35f }
    private val marchMul = when (host.difficulty) { Difficulty.EASY -> 1.2f; Difficulty.NORMAL -> 1f; Difficulty.HARD -> 0.8f }

    private var wave = 1
    private var score = 0
    private var lives = 3
    private val alive = BooleanArray(COLS * ROWS)
    private var gx = 20f
    private var gy = 40f
    private var gdir = 1
    private var marchT = 0f
    private var frame = 0
    private var marchNote = 0
    private var playerX = FW / 2
    private var targetX = -1f
    private var firing = false
    private var shot: FloatArray? = null              // x, y
    private val bombs = ArrayList<FloatArray>()      // x, y, type
    private var bombT = 1f
    private var saucerX = -100f
    private var saucerDir = 1
    private var saucerT = 15f
    private var deadT = -1f
    private var clearT = -1f
    private var t = 0f
    private val booms = ArrayList<FloatArray>()      // x, y, age, color
    private val popups = ArrayList<Triple<String, Float, FloatArray>>()
    private val bunkers = Array(4) { BooleanArray(22 * 16) }

    private var sc = 1f
    private var ox = 0f
    private var oy = 0f

    override fun start(saved: JSONObject?) {
        if (saved != null) { wave = saved.getInt("wave"); score = saved.getInt("score"); lives = saved.getInt("lives") }
        setupWave()
        buildBunkers()
    }

    override fun save(): JSONObject = JSONObject().put("wave", wave).put("score", score).put("lives", lives)

    private fun setupWave() {
        alive.fill(true)
        gx = 20f; gy = 40f + minOf(5, wave - 1) * 8f; gdir = 1
        marchT = 0f
        shot = null; bombs.clear()
        saucerX = -100f; saucerT = 12f + host.rng.nextFloat() * 10f
    }

    private fun buildBunkers() {
        for (b in bunkers) for (y in 0 until 16) for (x in 0 until 22) {
            var on = true
            if (y < 4 && (x < 4 - y || x > 17 + y)) on = false
            if (y >= 10 && x in 6..15) {
                val dy = y - 10
                if (x in (6 + maxOf(0, 2 - dy))..(15 - maxOf(0, 2 - dy))) on = false
            }
            b[y * 22 + x] = on
        }
    }

    private fun bunkerX(i: Int) = 22f + i * 50f

    override fun onLayout() {
        sc = minOf(area.width() / FW, area.height() / FH)
        ox = area.centerX() - FW * sc / 2
        oy = area.centerY() - FH * sc / 2
    }

    private fun alienX(c: Int) = gx + c * 16f
    private fun alienY(r: Int) = gy + r * 14f
    private fun remaining() = alive.count { it }
    private fun points(r: Int) = when (r) { 0 -> 30; 1, 2 -> 20; else -> 10 }

    override fun update(dt: Float) {
        t += dt
        updateFx(dt)
        if (finished) return
        if (clearT >= 0f) {
            clearT += dt
            if (clearT > 2f) { clearT = -1f; wave++; setupWave(); if (wave % 3 == 1) buildBunkers(); host.sfx(Sfx.START) }
            return
        }
        if (deadT >= 0f) {
            deadT += dt
            if (deadT > 1.5f) {
                if (lives <= 0) { lose(score, "WAVE $wave", 0.2f); return }
                deadT = -1f; playerX = FW / 2; bombs.clear()
            }
            return
        }
        // player movement toward finger
        if (targetX >= 0f) {
            val d = targetX - playerX
            playerX += d.coerceIn(-130f * dt, 130f * dt)
        }
        playerX = playerX.coerceIn(10f, FW - 10f)
        if (firing && shot == null) { shot = floatArrayOf(playerX, PLAYER_Y - 6f); host.sfx(Sfx.SHOOT) }

        // player shot
        shot?.let { s ->
            s[1] -= 260f * dt
            if (s[1] < 10f) { shot = null; return@let }
            if (hitBunker(s[0], s[1], true)) { shot = null; return@let }
            // saucer
            if (saucerX > -20f && abs(s[0] - saucerX) < 9f && abs(s[1] - 24f) < 5f) {
                val pts = listOf(50, 100, 150, 300)[host.rng.nextInt(4)]
                score += pts
                booms.add(floatArrayOf(saucerX, 24f, 0f, Pal.RED.toFloat()))
                popups.add(Triple(pts.toString(), 0f, floatArrayOf(saucerX, 24f)))
                saucerX = -100f; saucerT = 15f + host.rng.nextFloat() * 10f
                shot = null
                host.sfx(Sfx.EXPLODE)
                host.unlock("saucer")
                return@let
            }
            for (r in 0 until ROWS) for (c in 0 until COLS) {
                if (!alive[r * COLS + c]) continue
                if (abs(s[0] - (alienX(c) + 6f)) < 6.5f && abs(s[1] - (alienY(r) + 4f)) < 5f) {
                    alive[r * COLS + c] = false
                    score += points(r)
                    booms.add(floatArrayOf(alienX(c) + 6f, alienY(r) + 4f, 0f, rowColor(r).toFloat()))
                    shot = null
                    host.sfx(Sfx.HIT)
                    if (remaining() == 0) { clearT = 0f; host.sfx(Sfx.LEVELUP); host.unlock("wave1"); if (wave >= 5) host.unlock("wave5") }
                    if (score >= 5000) host.unlock("score5k")
                    return@let
                }
            }
        }

        // march
        val rem = remaining()
        if (rem > 0) {
            val interval = maxOf(0.035f, 0.55f * rem / (COLS * ROWS) * marchMul * (1f - minOf(0.4f, (wave - 1) * 0.06f)))
            marchT += dt
            if (marchT >= interval) {
                marchT = 0f
                frame = 1 - frame
                host.sfx(Sfx.TICK)
                var minC = COLS; var maxC = -1
                for (i in alive.indices) if (alive[i]) { minC = minOf(minC, i % COLS); maxC = maxOf(maxC, i % COLS) }
                val left = alienX(minC); val right = alienX(maxC) + 12f
                if ((gdir > 0 && right + 3f > FW - 4f) || (gdir < 0 && left - 3f < 4f)) {
                    gy += 8f; gdir = -gdir
                } else gx += 3f * gdir
                // reached the bunkers / ground?
                var maxR = -1
                for (i in alive.indices) if (alive[i]) maxR = maxOf(maxR, i / COLS)
                val bottom = alienY(maxR) + 8f
                if (bottom >= BUNKER_Y) eraseBunkersAbove(bottom)
                if (bottom >= PLAYER_Y - 4f) { lives = 0; killPlayer(); return }
            }
        }

        // alien bombs
        bombT -= dt * fireRate * (1f + (wave - 1) * 0.1f)
        if (bombT <= 0f && bombs.size < 3 && rem > 0) {
            bombT = 0.6f + host.rng.nextFloat() * 1.2f
            val cols = (0 until COLS).filter { c -> (0 until ROWS).any { alive[it * COLS + c] } }
            // prefer columns near the player
            val c = cols.minByOrNull { abs(alienX(it) + 6f - playerX) + host.rng.nextFloat() * 80f } ?: cols.first()
            val r = (ROWS - 1 downTo 0).first { alive[it * COLS + c] }
            bombs.add(floatArrayOf(alienX(c) + 6f, alienY(r) + 9f, host.rng.nextInt(2).toFloat()))
        }
        val it = bombs.iterator()
        while (it.hasNext()) {
            val b = it.next()
            b[1] += 95f * dt
            if (b[1] > FH - 8f) { it.remove(); continue }
            if (hitBunker(b[0], b[1], false)) { it.remove(); continue }
            if (abs(b[0] - playerX) < 7f && abs(b[1] - PLAYER_Y) < 5f) { it.remove(); lives--; killPlayer(); return }
            val s = shot
            if (s != null && abs(s[0] - b[0]) < 3f && abs(s[1] - b[1]) < 5f) { it.remove(); shot = null; booms.add(floatArrayOf(b[0], b[1], 0f, Pal.WHITE.toFloat())) }
        }

        // saucer
        if (saucerX > -20f) {
            saucerX += saucerDir * 45f * dt
            if (saucerX > FW + 20f || saucerX < -21f) { saucerX = -100f; saucerT = 15f + host.rng.nextFloat() * 10f }
        } else {
            saucerT -= dt
            if (saucerT <= 0f && rem > 8) {
                saucerDir = if (host.rng.nextBoolean()) 1 else -1
                saucerX = if (saucerDir > 0) -18f else FW + 18f
                host.sfx(Sfx.COIN)
            }
        }
    }

    private fun killPlayer() {
        deadT = 0f
        booms.add(floatArrayOf(playerX, PLAYER_Y, 0f, Pal.CYAN.toFloat()))
        host.sfx(Sfx.EXPLODE)
        host.vibrate(150)
        shot = null
    }

    private fun hitBunker(x: Float, y: Float, fromBelow: Boolean): Boolean {
        for (i in 0 until 4) {
            val bx = bunkerX(i)
            val lx = (x - bx).toInt(); val ly = (y - BUNKER_Y).toInt()
            if (lx !in 0 until 22 || ly !in 0 until 16) continue
            val b = bunkers[i]
            if (!b[ly * 22 + lx]) continue
            // erode a ragged blob
            for (dy in -2..2) for (dx in -2..2) {
                val xx = lx + dx; val yy = ly + dy + (if (fromBelow) -1 else 1)
                if (xx in 0 until 22 && yy in 0 until 16 && (abs(dx) + abs(dy) <= 2 || host.rng.nextFloat() < 0.3f)) b[yy * 22 + xx] = false
            }
            return true
        }
        return false
    }

    private fun eraseBunkersAbove(y: Float) {
        val ly = (y - BUNKER_Y).toInt().coerceAtMost(16)
        for (b in bunkers) for (yy in 0 until ly) for (x in 0 until 22) b[yy * 22 + x] = false
    }

    private fun updateFx(dt: Float) {
        booms.forEach { it[2] += dt }
        booms.removeAll { it[2] > 0.5f }
        popups.replaceAll { Triple(it.first, it.second + dt, it.third) }
        popups.removeAll { it.second > 1f }
    }

    override fun onTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> { targetX = (e.x - ox) / sc; firing = true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { targetX = -1f; firing = false }
        }
    }

    override fun onKey(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> playerX -= 8f
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> playerX += 8f
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_UP ->
                if (shot == null && deadT < 0f) { shot = floatArrayOf(playerX, PLAYER_Y - 6f); host.sfx(Sfx.SHOOT) }
            else -> return false
        }
        return true
    }

    override fun hud() = listOf(
        HudItem("SCORE", score.toString(), Pal.YELLOW),
        HudItem("WAVE", wave.toString(), Pal.CYAN),
        HudItem("LIVES", PixelFont.HEART.toString().repeat(lives.coerceIn(0, 6)).ifEmpty { "-" }, Pal.RED),
    )

    private fun rowColor(r: Int) = when (r) { 0 -> Pal.GREEN; 1, 2 -> Pal.PINK; else -> Pal.YELLOW }

    private fun sx(x: Float) = ox + x * sc
    private fun sy(y: Float) = oy + y * sc

    override fun draw(c: Canvas) {
        val p = px
        val field = RectF(ox, oy, ox + FW * sc, oy + FH * sc)
        drawBoardFrame(c, field, p, Pal.GREEN)
        Gfx.rect(c, field, Pal.BLACK)
        // ground
        Gfx.rect(c, sx(0f), sy(FH - 6f), sx(FW), sy(FH - 5f), Pal.GREEN)
        // bunkers
        for (i in 0 until 4) {
            val b = bunkers[i]; val bx = bunkerX(i)
            for (y in 0 until 16) for (x in 0 until 22) if (b[y * 22 + x]) {
                Gfx.rect(c, sx(bx + x), sy(BUNKER_Y + y), sx(bx + x + 1) + 0.5f, sy(BUNKER_Y + y + 1) + 0.5f, Pal.GREEN)
            }
        }
        // aliens
        for (r in 0 until ROWS) for (cc in 0 until COLS) {
            if (!alive[r * COLS + cc]) continue
            val art = when (r) { 0 -> if (frame == 0) A1 else A2; 1, 2 -> if (frame == 0) B1 else B2; else -> if (frame == 0) C1 else C2 }
            art.draw(c, RectF(sx(alienX(cc)), sy(alienY(r)), sx(alienX(cc) + 12f), sy(alienY(r) + 8f)))
        }
        if (saucerX > -20f) SAUCER.draw(c, RectF(sx(saucerX - 8f), sy(20f), sx(saucerX + 8f), sy(27f)))
        // player
        if (deadT < 0f || (deadT * 10).toInt() % 2 == 0 && deadT < 0.6f) {
            SHIP.draw(c, RectF(sx(playerX - 6.5f), sy(PLAYER_Y - 4f), sx(playerX + 6.5f), sy(PLAYER_Y + 4f)))
        }
        shot?.let { Gfx.rect(c, sx(it[0] - 0.5f), sy(it[1] - 3f), sx(it[0] + 0.5f), sy(it[1] + 3f), Pal.WHITE) }
        for (b in bombs) {
            val zig = if (b[2] == 0f) (((t * 12).toInt() % 2) * 2 - 1).toFloat() else 0f
            Gfx.rect(c, sx(b[0] - 0.5f + zig * 0.5f), sy(b[1] - 3f), sx(b[0] + 0.5f + zig * 0.5f), sy(b[1] + 3f), if (b[2] == 0f) Pal.ORANGE else Pal.WHITE)
        }
        for (bm in booms) {
            val k = bm[2] / 0.5f
            val col = Pal.withAlpha(bm[3].toInt(), (255 * (1f - k)).toInt())
            val rr = 2f + k * 6f
            for (a in 0 until 8) {
                val ang = a * 0.785f
                val x = bm[0] + kotlin.math.cos(ang) * rr; val y = bm[1] + kotlin.math.sin(ang) * rr
                Gfx.rect(c, sx(x - 0.7f), sy(y - 0.7f), sx(x + 0.7f), sy(y + 0.7f), col)
            }
        }
        for ((txt, age, pos) in popups) Gfx.text(c, txt, sx(pos[0]), sy(pos[1] - age * 10f), maxOf(1f, sc), Pal.RED, Align.CENTER)
        if (clearT >= 0f) Gfx.textMid(c, "WAVE $wave CLEARED!", field.centerX(), field.centerY(), maxOf(1f, sc * 1.4f), Pal.YELLOW)
        if (t < 2.5f && !finished) Gfx.textMid(c, "HOLD & DRAG TO MOVE AND FIRE", field.centerX(), sy(170f), maxOf(1f, sc * 0.8f), Pal.LGREY)
    }

    companion object {
        val A1 = PixelArt(
            "....GGGG....",
            "..GGGGGGGG..",
            ".GGKGGGGKGG.",
            ".GGGGGGGGGG.",
            "...GG..GG...",
            "..GG.GG.GG..",
            ".G........G.",
            "............",
        )
        val A2 = PixelArt(
            "....GGGG....",
            "..GGGGGGGG..",
            ".GGKGGGGKGG.",
            ".GGGGGGGGGG.",
            "...GG..GG...",
            "....G..G....",
            "...G....G...",
            "............",
        )
        val B1 = PixelArt(
            "..p......p..",
            "...p....p...",
            "..pppppppp..",
            ".ppWKppWKpp.",
            ".pppppppppp.",
            "..pppppppp..",
            ".p.p....p.p.",
            "p..........p",
        )
        val B2 = PixelArt(
            "..p......p..",
            "...p....p...",
            "..pppppppp..",
            ".ppKWppKWpp.",
            ".pppppppppp.",
            "..pppppppp..",
            "..p.p..p.p..",
            "..p......p..",
        )
        val C1 = PixelArt(
            "...YYYYYY...",
            ".YYYYYYYYYY.",
            "YYYKKYYKKYYY",
            "YYYYYYYYYYYY",
            "YYYY.YY.YYYY",
            ".Y.Y.YY.Y.Y.",
            "Y..Y....Y..Y",
            "............",
        )
        val C2 = PixelArt(
            "...YYYYYY...",
            ".YYYYYYYYYY.",
            "YYYKKYYKKYYY",
            "YYYYYYYYYYYY",
            "YYYY.YY.YYYY",
            "Y.Y.Y..Y.Y.Y",
            ".Y..Y..Y..Y.",
            "............",
        )
        val SHIP = PixelArt(
            "......W......",
            ".....WWW.....",
            ".....WCW.....",
            ".CCCCCCCCCCC.",
            "CCCCCCCCCCCCC",
            "CCBCCCCCCCBCC",
            "CCCCCCCCCCCCC",
            ".R.........R.",
        )
        val SAUCER = PixelArt(
            ".....RRRRRR.....",
            "...RRRRRRRRRR...",
            "..RRRRRRRRRRRR..",
            ".RRYRRYRRYRRYRR.",
            "RRRRRRRRRRRRRRRR",
            "..RRR..RR..RRR..",
            "...R........R...",
        )
    }
}

val AlienInvasionGame = GameDef(
    id = "alieninvasion",
    title = "ALIEN INVASION",
    category = Category.ARCADE,
    tagline = "Hold the line against waves of marching aliens.",
    description = "Rows of aliens march back and forth, creeping closer with every pass and speeding up as their numbers fall. " +
        "Shoot them down from behind your crumbling shields, and blast the mystery saucer for bonus points.",
    icon = PixelArt(
        "................",
        "..p..........p..",
        "...p........p...",
        "..pppppppppppp..",
        ".ppWKppppppWKpp.",
        ".pppppppppppppp.",
        "..pppppppppppp..",
        ".p.p........p.p.",
        "................",
        ".......W........",
        ".......W........",
        "................",
        ".......W........",
        "......WWW.......",
        "..CCCCCCCCCCC...",
        ".CCCCCCCCCCCCC..",
    ),
    accent = Pal.GREEN,
    music = Music.ARCADE,
    scoreKind = ScoreKind.HIGH_SCORE,
    tutorial = listOf(
        TutorialPage("OBJECTIVE", "Destroy every alien in the wave before they reach the ground. Each cleared wave brings a faster one. You have 3 ships.",
            PixelArt(
                ".G.G.G.G.",
                ".p.p.p.p.",
                ".Y.Y.Y.Y.",
                ".........",
                "GG..GG..G",
                "....C....",
            )),
        TutorialPage("CONTROLS", "TOUCH AND HOLD anywhere: your ship slides toward your finger and fires automatically. Only one of your shots can be on screen at a time, so make it count!", Icons.HOLD),
        TutorialPage("SCORING", "Yellow aliens are worth 10, pink 20 and green 30. The red mystery saucer that flies across the top is worth up to 300 points!", Icons.STAR),
        TutorialPage("SHIELDS", "The green bunkers block shots from both sides and crumble as they are hit. They're rebuilt every three waves.", Icons.HELP),
        TutorialPage("TIPS", "Clear the edge columns first - it slows the march down because the aliens must travel further before turning. Fewer aliens means faster aliens, so stay alert near the end of a wave.", Icons.BULB),
    ),
    achievements = listOf(
        AchievementDef("wave1", "DEFENDER", "Clear a wave."),
        AchievementDef("wave5", "PLANETARY HERO", "Clear wave 5."),
        AchievementDef("saucer", "UFO HUNTER", "Shoot down the mystery saucer."),
        AchievementDef("score5k", "SHARPSHOOTER", "Score 5,000 points."),
    ),
    factory = { AlienInvasionSession(it) },
)
