package com.openarcade.games.pong

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
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Paddle Ball. Physics run in field coordinates: u runs along the long axis (CPU at u=0, player at
 * u=L), v along the short axis. The field is drawn vertically in portrait and horizontally in
 * landscape.
 */
class PaddleBallSession(host: GameHost) : GameSession(host) {
    override val realtime = true
    private val L = 300f
    private val S = 200f
    private val PAD_LEN = 40f
    private val PAD_T = 6f
    private val PLAYER_U = L - 14f
    private val CPU_U = 14f
    private val TARGET = 7

    private val cpuSpeed = when (host.difficulty) { Difficulty.EASY -> 105f; Difficulty.NORMAL -> 165f; Difficulty.HARD -> 235f }
    private val cpuErr = when (host.difficulty) { Difficulty.EASY -> 30f; Difficulty.NORMAL -> 14f; Difficulty.HARD -> 5f }

    private var bu = L / 2; private var bv = S / 2
    private var du = 0f; private var dv = 0f
    private var speed = 150f
    private var playerV = S / 2
    private var cpuV = S / 2
    private var cpuAim = 0f
    private var playerScore = 0
    private var cpuScore = 0
    private var serveT = 1.2f
    private var serveToPlayer = true
    private var hits = 0
    private var flashT = 0f
    private var t = 0f
    private val trail = ArrayList<FloatArray>()

    // mapping
    private var vertical = true
    private var sc = 1f
    private var ox = 0f
    private var oy = 0f

    override fun start(saved: JSONObject?) {
        if (saved != null) { playerScore = saved.getInt("p"); cpuScore = saved.getInt("c") }
        serve(true)
    }

    override fun save(): JSONObject = JSONObject().put("p", playerScore).put("c", cpuScore)

    override fun onLayout() {
        vertical = area.height() >= area.width()
        val fw = if (vertical) S else L
        val fh = if (vertical) L else S
        sc = minOf(area.width() / fw, area.height() / fh)
        ox = area.centerX() - fw * sc / 2
        oy = area.centerY() - fh * sc / 2
    }

    /** Field (u, v) → screen x. */
    private fun sx(u: Float, v: Float) = if (vertical) ox + v * sc else ox + (L - u) * sc
    private fun sy(u: Float, v: Float) = if (vertical) oy + u * sc else oy + v * sc

    private fun serve(toPlayer: Boolean) {
        bu = L / 2; bv = S / 2; du = 0f; dv = 0f
        speed = 150f
        serveT = 1.2f
        serveToPlayer = toPlayer
        trail.clear()
    }

    override fun update(dt: Float) {
        t += dt
        if (flashT > 0f) flashT -= dt
        if (finished) return
        // CPU
        val target = if (du < 0f) predictV(CPU_U) + cpuAim else S / 2
        val diff = target - cpuV
        cpuV += diff.coerceIn(-cpuSpeed * dt, cpuSpeed * dt)
        cpuV = cpuV.coerceIn(PAD_LEN / 2, S - PAD_LEN / 2)
        playerV = playerV.coerceIn(PAD_LEN / 2, S - PAD_LEN / 2)

        if (serveT > 0f) {
            serveT -= dt
            if (serveT <= 0f) {
                val ang = (host.rng.nextFloat() - 0.5f) * 0.9f
                du = (if (serveToPlayer) 1f else -1f) * speed * cos(ang)
                dv = speed * sin(ang)
                host.sfx(Sfx.SHOOT)
            }
            return
        }
        val steps = maxOf(1, (speed * dt / 3f).toInt() + 1)
        val sdt = dt / steps
        repeat(steps) { if (physicsStep(sdt)) return }
        trail.add(floatArrayOf(bu, bv))
        while (trail.size > 6) trail.removeAt(0)
    }

    /** Where the ball will cross line u (accounting for wall bounces). */
    private fun predictV(u: Float): Float {
        if (du == 0f) return S / 2
        val tt = (u - bu) / du
        if (tt < 0f) return S / 2
        var v = bv + dv * tt
        val period = 2 * S
        v = ((v % period) + period) % period
        if (v > S) v = period - v
        return v
    }

    /** Returns true if a point was scored. */
    private fun physicsStep(dt: Float): Boolean {
        val r = 3f
        bu += du * dt; bv += dv * dt
        if (bv < r) { bv = r; dv = abs(dv); host.sfx(Sfx.WALL) }
        if (bv > S - r) { bv = S - r; dv = -abs(dv); host.sfx(Sfx.WALL) }
        // player paddle
        if (du > 0 && bu + r >= PLAYER_U - PAD_T / 2 && bu < PLAYER_U + PAD_T / 2 && abs(bv - playerV) <= PAD_LEN / 2 + r) {
            bounce(playerV, -1f); bu = PLAYER_U - PAD_T / 2 - r
            hits++
            host.vibrate(15)
        }
        if (du < 0 && bu - r <= CPU_U + PAD_T / 2 && bu > CPU_U - PAD_T / 2 && abs(bv - cpuV) <= PAD_LEN / 2 + r) {
            bounce(cpuV, 1f); bu = CPU_U + PAD_T / 2 + r
            cpuAim = (host.rng.nextFloat() * 2f - 1f) * cpuErr
        }
        if (bu > L + 8f) { point(false); return true }
        if (bu < -8f) { point(true); return true }
        return false
    }

    private fun bounce(padV: Float, dir: Float) {
        speed = minOf(380f, speed * 1.06f)
        val off = ((bv - padV) / (PAD_LEN / 2)).coerceIn(-1f, 1f)
        val ang = off * Math.toRadians(55.0).toFloat()
        du = dir * speed * cos(ang)
        dv = speed * sin(ang)
        host.sfx(Sfx.BOUNCE)
    }

    private fun point(player: Boolean) {
        flashT = 0.4f
        if (player) { playerScore++; host.sfx(Sfx.COIN) } else { cpuScore++; host.sfx(Sfx.ERROR) }
        if (playerScore >= TARGET) {
            host.unlock("first_win")
            if (host.difficulty == Difficulty.HARD) host.unlock("hard_win")
            if (cpuScore == 0) host.unlock("shutout")
            win(detail = "$playerScore - $cpuScore", delay = 0.8f)
            return
        }
        if (cpuScore >= TARGET) { lose(detail = "$playerScore - $cpuScore", delay = 0.8f); return }
        serve(!player)
    }

    override fun onTouch(e: MotionEvent) {
        if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
            playerV = if (vertical) (e.x - ox) / sc else (e.y - oy) / sc
        }
    }

    override fun onKey(keyCode: Int): Boolean {
        val dec = if (vertical) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_UP
        val inc = if (vertical) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_DOWN
        when (keyCode) {
            dec -> playerV -= 14f
            inc -> playerV += 14f
            else -> return false
        }
        return true
    }

    override fun hud() = listOf(
        HudItem("YOU", playerScore.toString(), Pal.GREEN),
        HudItem("FIRST TO", TARGET.toString()),
        HudItem("CPU", cpuScore.toString(), Pal.RED),
    )

    private fun fieldRect(u0: Float, v0: Float, u1: Float, v1: Float): RectF {
        val x0 = sx(u0, v0); val y0 = sy(u0, v0); val x1 = sx(u1, v1); val y1 = sy(u1, v1)
        return RectF(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1))
    }

    override fun draw(c: Canvas) {
        val p = px
        val field = fieldRect(0f, 0f, L, S)
        drawBoardFrame(c, field, p, Pal.GREEN)
        Gfx.rect(c, field, if (flashT > 0f) Pal.mix(Pal.BLACK, Pal.WHITE, flashT * 0.3f) else Pal.mix(Pal.BLACK, Pal.DGREEN, 0.15f))
        // centre line (dashed)
        var v = 0f
        while (v < S) {
            Gfx.rect(c, fieldRect(L / 2 - 1f, v, L / 2 + 1f, v + 6f), Pal.withAlpha(Pal.WHITE, 120))
            v += 12f
        }
        // big scores in the background
        val ss = sc * 7f
        Gfx.textMid(c, cpuScore.toString(), sx(L * 0.3f, S / 2), sy(L * 0.3f, S / 2), ss, Pal.withAlpha(Pal.RED, 90), Align.CENTER, null)
        Gfx.textMid(c, playerScore.toString(), sx(L * 0.7f, S / 2), sy(L * 0.7f, S / 2), ss, Pal.withAlpha(Pal.GREEN, 90), Align.CENTER, null)
        // paddles
        Gfx.bevel(c, fieldRect(CPU_U - PAD_T / 2, cpuV - PAD_LEN / 2, CPU_U + PAD_T / 2, cpuV + PAD_LEN / 2), Pal.RED, maxOf(1f, sc))
        Gfx.bevel(c, fieldRect(PLAYER_U - PAD_T / 2, playerV - PAD_LEN / 2, PLAYER_U + PAD_T / 2, playerV + PAD_LEN / 2), Pal.GREEN, maxOf(1f, sc))
        // trail + ball
        trail.forEachIndexed { i, tp ->
            val a = 30 + i * 25
            val r = fieldRect(tp[0] - 2.5f, tp[1] - 2.5f, tp[0] + 2.5f, tp[1] + 2.5f)
            Gfx.rect(c, r, Pal.withAlpha(Pal.YELLOW, a))
        }
        if (serveT <= 0f || (t * 6).toInt() % 2 == 0) {
            Gfx.rect(c, fieldRect(bu - 3f, bv - 3f, bu + 3f, bv + 3f), Pal.WHITE)
        }
        if (serveT > 0f && !finished) {
            val msg = if (playerScore == 0 && cpuScore == 0) "DRAG TO MOVE" else if (serveToPlayer) "INCOMING!" else "SERVE"
            Gfx.textMid(c, msg, field.centerX(), if (vertical) sy(L * 0.62f, 0f) else field.centerY() + sc * 30f, maxOf(1f, sc * 1.3f), Pal.YELLOW)
        }
    }
}

val PaddleBallGame = GameDef(
    id = "paddleball",
    title = "PADDLE BALL",
    category = Category.ARCADE,
    tagline = "The original electronic table tennis. First to 7 wins!",
    description = "Slide your paddle to return the ball past the computer. Hitting the ball with the edge of your paddle sends it at a steep angle, " +
        "and the rally speeds up with every return. Plays vertically in portrait and horizontally in landscape.",
    icon = PixelArt(
        "................",
        "....RRRRRR......",
        "................",
        ".......W........",
        "................",
        ".......W........",
        "................",
        ".WWW.WWW.WWW.WW.",
        "................",
        "..........W.....",
        "................",
        "...........YY...",
        "...........YY...",
        "................",
        ".......GGGGGG...",
        "................",
    ),
    accent = Pal.GREEN,
    music = Music.ARCADE,
    scoreKind = ScoreKind.WINS,
    tutorial = listOf(
        TutorialPage("OBJECTIVE", "Hit the ball past the computer's paddle to score. Miss the ball and the computer scores. The first player to 7 points wins the match.",
            PixelArt(
                "..RRR...",
                "........",
                "WW.WW.WW",
                ".....Y..",
                "...GGG..",
            )),
        TutorialPage("CONTROLS", "DRAG anywhere to move your green paddle - it follows your finger. In portrait your paddle is at the bottom; in landscape it's on the left.", Icons.DRAG),
        TutorialPage("ANGLES", "The ball bounces off at an angle depending on where it hits your paddle. Hit it with the middle to send it straight, or the edges for a sharp angle. Each return makes the ball faster.", Icons.HELP),
        TutorialPage("TIPS", "Steep angles off the side walls are hard for the computer to reach. Move back toward the middle after each hit. On HARD the computer rarely misses - make it chase!", Icons.BULB),
    ),
    achievements = listOf(
        AchievementDef("first_win", "TABLE TENNIS", "Win a match."),
        AchievementDef("hard_win", "PRO PLAYER", "Win a match on HARD."),
        AchievementDef("shutout", "SHUTOUT", "Win a match 7-0."),
    ),
    factory = { PaddleBallSession(it) },
)
