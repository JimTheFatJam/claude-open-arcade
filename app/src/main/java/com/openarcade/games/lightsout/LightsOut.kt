package com.openarcade.games.lightsout

import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import com.openarcade.audio.Music
import com.openarcade.audio.Sfx
import com.openarcade.games.AchievementDef
import com.openarcade.games.Category
import com.openarcade.games.Difficulty
import com.openarcade.games.GameAction
import com.openarcade.games.GameDef
import com.openarcade.games.GameHost
import com.openarcade.games.GameSession
import com.openarcade.games.GridFit
import com.openarcade.games.HudItem
import com.openarcade.games.ScoreKind
import com.openarcade.games.TutorialPage
import com.openarcade.games.drawBoardFrame
import com.openarcade.games.toIntArray
import com.openarcade.games.toJsonArray
import com.openarcade.gfx.Gfx
import com.openarcade.gfx.Icons
import com.openarcade.gfx.Pal
import com.openarcade.gfx.PixelArt
import org.json.JSONObject
import kotlin.math.sin

/**
 * Lights Out. Puzzles are created by pressing random cells on a dark board, so they are always
 * solvable. Because presses commute and cancel in pairs, (generator presses XOR player presses)
 * is always an exact solution — that's what the HINT button uses.
 */
class LightsBoard(val n: Int) {
    val on = BooleanArray(n * n)
    val toSolve = BooleanArray(n * n)   // cells that must be pressed (an odd number of times) to solve

    fun press(i: Int) {
        val c = i % n; val r = i / n
        flip(i)
        if (c > 0) flip(i - 1)
        if (c < n - 1) flip(i + 1)
        if (r > 0) flip(i - n)
        if (r < n - 1) flip(i + n)
        toSolve[i] = !toSolve[i]
    }

    private fun flip(i: Int) { on[i] = !on[i] }
    fun solved() = on.none { it }
    fun lit() = on.count { it }
    fun par() = toSolve.count { it }
}

class LightsOutSession(host: GameHost) : GameSession(host) {
    private val n = if (host.difficulty == Difficulty.HARD) 6 else 5
    private val presses = when (host.difficulty) { Difficulty.EASY -> 4; Difficulty.NORMAL -> 9; Difficulty.HARD -> 16 }
    private val board = LightsBoard(n)
    private val grid = GridFit(n, n)
    private var moves = 0
    private var par = 0
    private var hintCell = -1
    private var hints = 0
    private val pulse = FloatArray(n * n) { 1f }
    private var t = 0f

    override fun start(saved: JSONObject?) {
        if (saved != null) {
            val on = saved.getJSONArray("on").toIntArray()
            val ts = saved.getJSONArray("ts").toIntArray()
            require(on.size == n * n && ts.size == n * n) { "save is for a different board size" }
            for (i in 0 until n * n) { board.on[i] = on[i] == 1; board.toSolve[i] = ts[i] == 1 }
            moves = saved.getInt("moves"); par = saved.getInt("par"); hints = saved.optInt("hints")
        } else {
            do {
                for (i in 0 until n * n) { board.on[i] = false; board.toSolve[i] = false }
                val cells = (0 until n * n).shuffled(host.rng).take(presses)
                for (cl in cells) board.press(cl)
            } while (board.solved() || board.lit() < 3)
            par = board.par()
        }
    }

    override fun save(): JSONObject = JSONObject()
        .put("on", IntArray(n * n) { if (board.on[it]) 1 else 0 }.toJsonArray())
        .put("ts", IntArray(n * n) { if (board.toSolve[it]) 1 else 0 }.toJsonArray())
        .put("moves", moves).put("par", par).put("hints", hints)

    override fun onLayout() = grid.fit(area, px * 5)

    override fun onTouch(e: MotionEvent) {
        if (e.actionMasked != MotionEvent.ACTION_DOWN) return
        val i = grid.cellAt(e.x, e.y)
        if (i < 0) return
        board.press(i)
        moves++
        hintCell = -1
        for (k in listOf(i, i - 1, i + 1, i - n, i + n)) if (k in 0 until n * n && (k == i || adjacent(i, k))) pulse[k] = 0f
        host.sfx(Sfx.CLICK)
        host.vibrate(12)
        if (board.solved()) {
            host.unlock("first_win")
            if (moves <= par) host.unlock("efficient")
            if (host.difficulty == Difficulty.HARD) host.unlock("hard_win")
            win(detail = "MOVES $moves  PAR $par", delay = 1.2f)
        }
    }

    private fun adjacent(a: Int, b: Int) = (kotlin.math.abs(a % n - b % n) + kotlin.math.abs(a / n - b / n)) == 1

    private fun hint() {
        if (finished) return
        val cand = (0 until n * n).filter { board.toSolve[it] }
        if (cand.isEmpty()) return
        hintCell = cand[host.rng.nextInt(cand.size)]
        hints++
        host.sfx(Sfx.POWER)
    }

    override fun update(dt: Float) {
        t += dt
        for (k in pulse.indices) if (pulse[k] < 1f) pulse[k] = minOf(1f, pulse[k] + dt / 0.25f)
    }

    override fun hud() = listOf(
        HudItem("LIT", board.lit().toString(), Pal.YELLOW),
        HudItem("MOVES", moves.toString()),
        HudItem("PAR", par.toString(), Pal.GREEN),
    )

    override fun actions() = listOf(GameAction("HINT", Icons.HINT, isEnabled = { !finished }) { hint() })

    override fun draw(c: Canvas) {
        val p = px
        drawBoardFrame(c, grid.bounds, p, Pal.YELLOW)
        Gfx.rect(c, grid.bounds, Pal.BLACK)
        val r = RectF()
        val gap = maxOf(p, grid.cell * 0.08f)
        for (i in 0 until n * n) {
            grid.rect(i % n, i / n, r, gap / 2)
            val lit = board.on[i]
            val k = pulse[i]
            val pop = (1f - k) * gap * 0.6f
            r.inset(-pop, -pop)
            if (lit) {
                val glow = 0.85f + 0.15f * sin(t * 3f + i)
                Gfx.rect(c, r.left - gap / 3, r.top - gap / 3, r.right + gap / 3, r.bottom + gap / 3, Pal.withAlpha(Pal.YELLOW, (70 * glow).toInt()))
                Gfx.bevel(c, r, Pal.mix(Pal.ORANGE, Pal.YELLOW, glow), maxOf(1f, grid.cell / 10f))
                // bulb highlight
                val hs = grid.cell * 0.14f
                Gfx.rect(c, r.left + hs, r.top + hs, r.left + hs * 2.2f, r.top + hs * 2.2f, Pal.WHITE)
            } else {
                Gfx.bevel(c, r, Pal.BG2, maxOf(1f, grid.cell / 10f))
                Gfx.rect(c, r.centerX() - grid.cell * 0.12f, r.centerY() - grid.cell * 0.12f, r.centerX() + grid.cell * 0.12f, r.centerY() + grid.cell * 0.12f, Pal.BG1)
            }
            if (i == hintCell && ((t * 4).toInt() % 2 == 0)) Gfx.frame(c, r, maxOf(1f, p), Pal.CYAN)
        }
    }
}

val LightsOutGame = GameDef(
    id = "lightsout",
    title = "LIGHTS OUT",
    category = Category.PUZZLE,
    tagline = "Every press flips a light and its neighbours. Turn them all off!",
    description = "Pressing a light toggles it and the lights directly above, below, left and right of it. " +
        "Your goal is to switch every light off. Every board is guaranteed solvable.",
    icon = PixelArt(
        "................",
        ".dddd.WYYY.dddd.",
        ".dddd.YYYY.dddd.",
        ".dddd.YYYO.dddd.",
        ".dddd.YYOO.dddd.",
        "................",
        ".WYYY.WYYY.WYYY.",
        ".YYYY.YYYY.YYYY.",
        ".YYYO.YYYO.YYYO.",
        ".YYOO.YYOO.YYOO.",
        "................",
        ".dddd.WYYY.dddd.",
        ".dddd.YYYY.dddd.",
        ".dddd.YYYO.dddd.",
        ".dddd.YYOO.dddd.",
        "................",
    ),
    accent = Pal.YELLOW,
    music = Music.PUZZLE,
    scoreKind = ScoreKind.WINS,
    difficultyLabels = listOf("EASY", "NORMAL", "HARD 6X6"),
    tutorial = listOf(
        TutorialPage("OBJECTIVE", "Switch OFF every light on the board. When the whole grid is dark, you win!",
            PixelArt(
                "ddddd",
                "dYddd",
                "YYYdd",
                "dYddd",
                "ddddd",
            )),
        TutorialPage("CONTROLS", "TAP a light to press it. That light AND its neighbours above, below, left and right all flip: on becomes off and off becomes on.", Icons.TAP),
        TutorialPage("PAR & HINTS", "PAR is the number of presses used to scramble the board - match it for a perfect solve. HINT flashes a light that is part of a solution.", Icons.HINT),
        TutorialPage("TIPS", "Pressing the same light twice does nothing, and the order of presses doesn't matter. Try \"chasing\" lights downward: clear each row by pressing the lights directly below the lit ones.", Icons.BULB),
    ),
    achievements = listOf(
        AchievementDef("first_win", "LIGHTS OUT!", "Solve a board."),
        AchievementDef("efficient", "ENERGY SAVER", "Solve a board at or under par."),
        AchievementDef("hard_win", "BLACKOUT", "Solve a HARD 6x6 board."),
    ),
    factory = { LightsOutSession(it) },
)
