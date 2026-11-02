package com.openarcade.games.brick

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
import com.openarcade.games.toIntArray
import com.openarcade.games.toJsonArray
import com.openarcade.gfx.Align
import com.openarcade.gfx.Gfx
import com.openarcade.gfx.Icons
import com.openarcade.gfx.Pal
import com.openarcade.gfx.PixelArt
import com.openarcade.gfx.PixelFont
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object BrickLevels {
    /** '.' empty, '1'..'3' hit points, 'S' steel (unbreakable), 'P' power-up brick. */
    val LEVELS = listOf(
        listOf("..........", "1111111111", "1111111111", "1111111111", "1111111111", "11P1111P11"),
        listOf("....11....", "...1111...", "..11P111..", ".11222211.", "1122222211", "1111111111"),
        listOf("1.1.1.1.1.", ".2.2.2.2.2", "1.1.P.1.1.", "SSS....SSS", "2222222222", "1111111111"),
        listOf("..222222..", ".21111112.", "2113113112", "2111P11112", "2131111312", ".21333312.", "..222222.."),
        listOf("S........S", "S33333333S", "S222P2222S", "S11111111S", "S........S", "SSSS..SSSS"),
        listOf("....33....", "...3223...", "..321123..", ".321PP123.", "..321123..", "...3223...", "....33...."),
        listOf("3333333333", "..........", "2222P22222", "S.S.S.S.S.", "1111111111", "11111P1111"),
        listOf("SS333333SS", "S32222223S", "S21111112S", "S21SSSS12S", "S211P1112S", "S32222223S", "SSSS..SSSS"),
    )
}

class BrickBreakerSession(host: GameHost) : GameSession(host) {
    override val realtime = true
    private val FW = 240f
    private val FH = 330f
    private val COLS = 10
    private val BW = 24f
    private val BH = 10f
    private val TOP = 36f
    private val PADDLE_Y = 305f

    private val baseSpeed = when (host.difficulty) { Difficulty.EASY -> 140f; Difficulty.NORMAL -> 170f; Difficulty.HARD -> 200f }
    private val basePaddle = when (host.difficulty) { Difficulty.EASY -> 46f; Difficulty.NORMAL -> 38f; Difficulty.HARD -> 32f }

    private var level = 0
    private var score = 0
    private var lives = 3
    private var bricks = IntArray(0)   // hp; -1 steel; 0 empty
    private var power = BooleanArray(0)
    private var rows = 0

    private class Ball(var x: Float, var y: Float, var vx: Float, var vy: Float)
    private val balls = ArrayList<Ball>()
    private var stuck = true
    private var paddleX = 120f
    private var wideT = 0f
    private var slowT = 0f
    private var speedMul = 1f
    private class Capsule(var x: Float, var y: Float, val kind: Int)
    private val capsules = ArrayList<Capsule>()
    private val particles = ArrayList<FloatArray>()   // x, y, vx, vy, life, color
    private var clearT = -1f
    private var t = 0f
    private var lastTouchX = 0f

    // screen mapping
    private var sc = 1f
    private var ox = 0f
    private var oy = 0f

    override fun start(saved: JSONObject?) {
        if (saved != null) {
            level = saved.getInt("level"); score = saved.getInt("score"); lives = saved.getInt("lives")
            loadLevel(level)
            val b = saved.getJSONArray("bricks").toIntArray()
            if (b.size == bricks.size) bricks = b
        } else loadLevel(0)
        resetBall()
    }

    override fun save(): JSONObject = JSONObject().put("level", level).put("score", score).put("lives", lives)
        .put("bricks", bricks.toJsonArray())

    private fun loadLevel(l: Int) {
        val map = BrickLevels.LEVELS[l % BrickLevels.LEVELS.size]
        rows = map.size
        bricks = IntArray(rows * COLS)
        power = BooleanArray(rows * COLS)
        for (r in 0 until rows) for (c in 0 until COLS) {
            val ch = map[r][c]
            bricks[r * COLS + c] = when (ch) { '1' -> 1; '2' -> 2; '3' -> 3; 'S' -> -1; 'P' -> 1; else -> 0 }
            power[r * COLS + c] = ch == 'P'
        }
        speedMul = 1f + (l / BrickLevels.LEVELS.size) * 0.12f
        capsules.clear()
    }

    private fun resetBall() {
        balls.clear()
        balls.add(Ball(paddleX, PADDLE_Y - 5f, 0f, 0f))
        stuck = true
        wideT = 0f; slowT = 0f
    }

    override fun onLayout() {
        sc = minOf(area.width() / FW, area.height() / FH)
        ox = area.centerX() - FW * sc / 2
        oy = area.centerY() - FH * sc / 2
    }

    private fun paddleW() = basePaddle * (if (wideT > 0f) 1.5f else 1f)
    private fun ballSpeed() = baseSpeed * speedMul * (if (slowT > 0f) 0.7f else 1f)

    private fun launch() {
        if (!stuck || clearT >= 0f) return
        stuck = false
        val ang = Math.toRadians((-20 + host.rng.nextInt(41)).toDouble())
        val b = balls[0]
        b.vx = (ballSpeed() * sin(ang)).toFloat(); b.vy = (-ballSpeed() * cos(ang)).toFloat()
        host.sfx(Sfx.SHOOT)
    }

    override fun update(dt: Float) {
        t += dt
        updateParticles(dt)
        if (finished) return
        if (clearT >= 0f) {
            clearT += dt
            if (clearT > 1.8f) { clearT = -1f; level++; loadLevel(level); resetBall(); host.sfx(Sfx.START) }
            return
        }
        if (wideT > 0f) wideT -= dt
        if (slowT > 0f) { slowT -= dt; if (slowT <= 0f) rescaleBalls() }
        val half = paddleW() / 2
        paddleX = paddleX.coerceIn(half, FW - half)
        if (stuck) { balls[0].x = paddleX; balls[0].y = PADDLE_Y - 5f; return }

        // physics in small substeps
        val steps = maxOf(1, (ballSpeed() * dt / 2f).toInt() + 1)
        val sdt = dt / steps
        repeat(steps) { for (b in balls.toList()) stepBall(b, sdt) }
        balls.removeAll { it.y > FH + 10f }
        if (balls.isEmpty()) {
            lives--
            host.sfx(Sfx.LOSE)
            host.vibrate(100)
            if (lives <= 0) { lose(score, "LEVEL ${level + 1}", 0.8f); return }
            resetBall()
        }
        // capsules
        val it = capsules.iterator()
        while (it.hasNext()) {
            val cp = it.next()
            cp.y += 70f * dt
            if (cp.y > PADDLE_Y - 4f && cp.y < PADDLE_Y + 8f && abs(cp.x - paddleX) < paddleW() / 2 + 6f) {
                applyPower(cp.kind); it.remove()
            } else if (cp.y > FH) it.remove()
        }
    }

    private fun rescaleBalls() {
        for (b in balls) {
            val s = sqrt(b.vx * b.vx + b.vy * b.vy)
            if (s > 0f) { val k = ballSpeed() / s; b.vx *= k; b.vy *= k }
        }
    }

    private fun stepBall(b: Ball, dt: Float) {
        val r = 3f
        b.x += b.vx * dt; b.y += b.vy * dt
        if (b.x < r) { b.x = r; b.vx = abs(b.vx); host.sfx(Sfx.WALL) }
        if (b.x > FW - r) { b.x = FW - r; b.vx = -abs(b.vx); host.sfx(Sfx.WALL) }
        if (b.y < r) { b.y = r; b.vy = abs(b.vy); host.sfx(Sfx.WALL) }
        // paddle
        val pw = paddleW()
        if (b.vy > 0 && b.y + r >= PADDLE_Y && b.y + r <= PADDLE_Y + 7f && abs(b.x - paddleX) <= pw / 2 + r) {
            val off = ((b.x - paddleX) / (pw / 2)).coerceIn(-1f, 1f)
            val ang = off * Math.toRadians(62.0).toFloat()
            val s = ballSpeed()
            b.vx = s * sin(ang); b.vy = -s * cos(ang)
            b.y = PADDLE_Y - r
            host.sfx(Sfx.BOUNCE)
            return
        }
        // bricks
        val c0 = ((b.x - r) / BW).toInt().coerceIn(0, COLS - 1)
        val c1 = ((b.x + r) / BW).toInt().coerceIn(0, COLS - 1)
        val r0 = ((b.y - r - TOP) / BH).toInt()
        val r1 = ((b.y + r - TOP) / BH).toInt()
        for (row in r0..r1) {
            if (row !in 0 until rows) continue
            for (col in c0..c1) {
                val i = row * COLS + col
                if (bricks[i] == 0) continue
                val bl = col * BW; val bt = TOP + row * BH
                val overlapX = minOf(b.x + r - bl, bl + BW - (b.x - r))
                val overlapY = minOf(b.y + r - bt, bt + BH - (b.y - r))
                if (overlapX <= 0f || overlapY <= 0f) continue
                if (overlapX < overlapY) { b.vx = -b.vx; b.x += if (b.x < bl + BW / 2) -overlapX else overlapX }
                else { b.vy = -b.vy; b.y += if (b.y < bt + BH / 2) -overlapY else overlapY }
                hitBrick(i, bl + BW / 2, bt + BH / 2)
                return
            }
        }
    }

    private fun hitBrick(i: Int, cx: Float, cy: Float) {
        if (bricks[i] < 0) { host.sfx(Sfx.WALL); return }
        bricks[i]--
        score += 10
        if (bricks[i] == 0) {
            score += 20 * (level + 1)
            host.sfx(Sfx.HIT)
            burst(cx, cy, brickColor(i / COLS, 1))
            if (power[i] || host.rng.nextFloat() < 0.08f) capsules.add(Capsule(cx, cy, host.rng.nextInt(4)))
            if (bricks.none { it > 0 }) levelClear()
        } else host.sfx(Sfx.BOUNCE)
        if (score >= 5000) host.unlock("score5k")
    }

    private fun levelClear() {
        clearT = 0f
        capsules.clear()
        host.sfx(Sfx.LEVELUP)
        host.unlock("level1")
        if (level + 1 >= 5) host.unlock("level5")
        score += 500
    }

    private fun applyPower(kind: Int) {
        host.sfx(Sfx.POWER)
        when (kind) {
            0 -> { wideT = 15f; host.toast("WIDE PADDLE!") }
            1 -> {
                val src = balls.firstOrNull() ?: return
                for (k in 0 until 2) {
                    val ang = Math.toRadians((-50 + k * 100).toDouble())
                    val s = ballSpeed()
                    balls.add(Ball(src.x, src.y, (s * sin(ang)).toFloat(), (-s * cos(ang)).toFloat()))
                }
                host.toast("MULTI BALL!")
            }
            2 -> { slowT = 10f; rescaleBalls(); host.toast("SLOW BALL!") }
            else -> { lives++; host.toast("EXTRA LIFE!") }
        }
    }

    private fun burst(x: Float, y: Float, color: Int) {
        repeat(8) {
            val a = host.rng.nextFloat() * 6.28f
            val s = 30f + host.rng.nextFloat() * 60f
            particles.add(floatArrayOf(x, y, cos(a) * s, sin(a) * s, 0.5f, color.toFloat()))
        }
    }

    private fun updateParticles(dt: Float) {
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p[0] += p[2] * dt; p[1] += p[3] * dt; p[3] += 200f * dt; p[4] -= dt
            if (p[4] <= 0f) it.remove()
        }
    }

    override fun onTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> lastTouchX = e.x
            MotionEvent.ACTION_MOVE -> {
                paddleX += (e.x - lastTouchX) / sc * 1.25f
                lastTouchX = e.x
            }
            MotionEvent.ACTION_UP -> launch()
        }
    }

    override fun onKey(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> paddleX -= 14f
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> paddleX += 14f
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_UP -> launch()
            else -> return false
        }
        return true
    }

    override fun hud() = listOf(
        HudItem("SCORE", score.toString(), Pal.YELLOW),
        HudItem("LEVEL", (level + 1).toString(), Pal.CYAN),
        HudItem("LIVES", PixelFont.HEART.toString().repeat(lives.coerceIn(0, 6)).ifEmpty { "-" }, Pal.RED),
    )

    private fun brickColor(row: Int, hp: Int): Int = when (hp) {
        3 -> Pal.PURPLE
        2 -> Pal.BLUE
        else -> intArrayOf(Pal.RED, Pal.ORANGE, Pal.YELLOW, Pal.GREEN, Pal.CYAN, Pal.PINK, Pal.RED)[row % 7]
    }

    private fun sx(x: Float) = ox + x * sc
    private fun sy(y: Float) = oy + y * sc

    override fun draw(c: Canvas) {
        val p = px
        val field = RectF(ox, oy, ox + FW * sc, oy + FH * sc)
        drawBoardFrame(c, field, p, Pal.PINK)
        Gfx.rect(c, field, Pal.BLACK)
        // subtle backdrop grid
        var gy = 0f
        while (gy < FH) { Gfx.rect(c, field.left, sy(gy), field.right, sy(gy) + 1, Pal.withAlpha(Pal.BG2, 60)); gy += 20f }
        val bev = maxOf(1f, (BH * sc / 5f).toInt().toFloat())
        val flash = clearT >= 0f && ((clearT * 8).toInt() % 2 == 0)
        for (i in bricks.indices) {
            val hp = bricks[i]
            if (hp == 0) continue
            val col = i % COLS; val row = i / COLS
            val r = RectF(sx(col * BW) + 1, sy(TOP + row * BH) + 1, sx((col + 1) * BW) - 1, sy(TOP + (row + 1) * BH) - 1)
            if (hp < 0) {
                Gfx.bevel(c, r, if (flash) Pal.WHITE else Pal.GREY, bev)
                val rv = maxOf(1f, bev)
                Gfx.rect(c, r.left + bev * 2, r.centerY() - rv / 2, r.left + bev * 2 + rv, r.centerY() + rv / 2, Pal.DGREY)
                Gfx.rect(c, r.right - bev * 2 - rv, r.centerY() - rv / 2, r.right - bev * 2, r.centerY() + rv / 2, Pal.DGREY)
            } else {
                Gfx.bevel(c, r, brickColor(row, hp), bev)
                if (power[i]) Gfx.rect(c, r.centerX() - bev, r.centerY() - bev, r.centerX() + bev, r.centerY() + bev, Pal.WHITE)
            }
        }
        // capsules
        for (cp in capsules) {
            val r = RectF(sx(cp.x - 8f), sy(cp.y - 4f), sx(cp.x + 8f), sy(cp.y + 4f))
            val col = intArrayOf(Pal.GREEN, Pal.CYAN, Pal.ORANGE, Pal.RED)[cp.kind]
            Gfx.bevel(c, r, col, maxOf(1f, sc))
            val l = "WMSL"[cp.kind].toString()
            Gfx.textMid(c, l, r.centerX(), r.centerY(), maxOf(1f, sc * 0.9f), Pal.BLACK, shadow = null)
        }
        // paddle
        val pw = paddleW()
        val pr = RectF(sx(paddleX - pw / 2), sy(PADDLE_Y), sx(paddleX + pw / 2), sy(PADDLE_Y + 6f))
        Gfx.bevel(c, pr, if (wideT > 0f) Pal.GREEN else Pal.LGREY, maxOf(1f, sc))
        Gfx.rect(c, pr.left, pr.top, pr.left + 4 * sc, pr.bottom, Pal.RED)
        Gfx.rect(c, pr.right - 4 * sc, pr.top, pr.right, pr.bottom, Pal.RED)
        // balls
        for (b in balls) {
            Gfx.disc(c, sx(b.x), sy(b.y), 3f * sc, if (slowT > 0f) Pal.ORANGE else Pal.WHITE, maxOf(1f, sc))
        }
        for (pt in particles) {
            Gfx.rect(c, sx(pt[0]), sy(pt[1]), sx(pt[0]) + sc * 2, sy(pt[1]) + sc * 2, pt[5].toInt())
        }
        if (stuck && clearT < 0f && !finished && (t * 2).toInt() % 2 == 0) {
            Gfx.text(c, "TAP TO LAUNCH", field.centerX(), sy(250f), maxOf(1f, sc * 1.2f), Pal.YELLOW, Align.CENTER)
        }
        if (clearT >= 0f) Gfx.textMid(c, "LEVEL CLEAR!", field.centerX(), field.centerY(), maxOf(1f, sc * 2f), Pal.YELLOW)
    }
}

val BrickBreakerGame = GameDef(
    id = "brickbreaker",
    title = "BRICK BREAKER",
    category = Category.ARCADE,
    tagline = "Bounce the ball, smash every brick, catch the power-ups.",
    description = "Keep the ball in play with your paddle and destroy all the bricks to advance. " +
        "Tougher bricks take several hits and steel can't be broken at all. Eight hand-built stages loop with increasing speed.",
    icon = PixelArt(
        "................",
        ".RRRR.OOOO.YYYY.",
        ".RRRR.OOOO.YYYY.",
        "................",
        ".GGGG.CCCC......",
        ".GGGG.CCCC......",
        "................",
        "...........WW...",
        "...........WW...",
        "................",
        "................",
        "................",
        "................",
        ".....RlllllR....",
        ".....RlllllR....",
        "................",
    ),
    accent = Pal.PINK,
    music = Music.ARCADE,
    scoreKind = ScoreKind.HIGH_SCORE,
    tutorial = listOf(
        TutorialPage("OBJECTIVE", "Destroy every breakable brick to clear the stage. Don't let the ball fall past your paddle - you have 3 lives.",
            PixelArt(
                "RRROOOYYY",
                "GGGCCC.BB",
                ".........",
                "....W....",
                ".........",
                "...lll...",
            )),
        TutorialPage("CONTROLS", "DRAG anywhere left or right to move the paddle. TAP to launch the ball. Where the ball hits the paddle controls its angle - the edges send it off sideways.", Icons.DRAG),
        TutorialPage("BRICKS", "Colored bricks break in one hit. BLUE bricks take 2 hits, PURPLE take 3. GREY steel bricks can't be destroyed. Bricks with a white dot always drop a power-up.", PixelArt(
            "RRRR.BBBB",
            "RRRR.BBBB",
            ".........",
            "PPPP.gggg",
            "PPPP.gggg",
        )),
        TutorialPage("POWER-UPS", "Catch falling capsules with your paddle: W = wide paddle, M = multi-ball, S = slow ball, L = extra life.", Icons.STAR),
        TutorialPage("TIPS", "Aim for gaps to get the ball behind the bricks - it will bounce around and do the work for you! Keep the paddle under the ball rather than chasing it at the last second.", Icons.BULB),
    ),
    achievements = listOf(
        AchievementDef("level1", "DEMOLITION", "Clear a stage."),
        AchievementDef("level5", "WRECKING CREW", "Clear stage 5."),
        AchievementDef("score5k", "BRICK BUSTER", "Score 5,000 points."),
    ),
    factory = { BrickBreakerSession(it) },
)
