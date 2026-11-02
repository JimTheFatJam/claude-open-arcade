package com.openarcade.games.snake

import android.graphics.Canvas
import android.graphics.RectF
import android.view.KeyEvent
import android.view.MotionEvent
import com.openarcade.audio.Music
import com.openarcade.audio.Sfx
import com.openarcade.core.Dir
import com.openarcade.core.Gestures
import com.openarcade.games.AchievementDef
import com.openarcade.games.Category
import com.openarcade.games.Difficulty
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
import com.openarcade.gfx.Align
import com.openarcade.gfx.Gfx
import com.openarcade.gfx.Icons
import com.openarcade.gfx.Pal
import com.openarcade.gfx.PixelArt
import org.json.JSONObject

class SnakeSession(host: GameHost) : GameSession(host) {
    override val realtime = true
    private var cols = 18
    private var rows = 24
    private val wrap = host.difficulty == Difficulty.EASY
    private val baseSpeed = when (host.difficulty) { Difficulty.EASY -> 6f; Difficulty.NORMAL -> 8.5f; Difficulty.HARD -> 11f }
    private val body = ArrayDeque<Int>()     // head first
    private var dir = Dir.RIGHT
    private val queue = ArrayDeque<Dir>()
    private var food = -1
    private var bonus = -1
    private var bonusT = 0f
    private var grow = 0
    private var score = 0
    private var eaten = 0
    private var stepT = 0f
    private var t = 0f
    private var started = false
    private var deadT = -1f
    private lateinit var grid: GridFit
    private val gestures = Gestures { px * 3 }

    init {
        gestures.continuousSwipe = true
        gestures.onSwipe = { turn(it) }
        gestures.onTap = { _, _ -> started = true }
    }

    override fun start(saved: JSONObject?) {
        if (saved != null) {
            cols = saved.getInt("cols"); rows = saved.getInt("rows")
            body.clear(); body.addAll(saved.getJSONArray("body").toIntArray().toList())
            dir = Dir.valueOf(saved.getString("dir"))
            food = saved.getInt("food"); score = saved.getInt("score"); eaten = saved.getInt("eaten")
            grow = saved.optInt("grow")
        } else {
            // pick a board shape that suits the current screen
            val landscape = area.width() > area.height()
            if (landscape) { cols = 28; rows = 16 } else { cols = 18; rows = 26 }
            val sr = rows / 2
            body.clear()
            for (k in 0 until 4) body.addLast(sr * cols + (5 - k))
            dir = Dir.RIGHT
            placeFood()
        }
        grid = GridFit(cols, rows)
        onLayout()
    }

    override fun save(): JSONObject = JSONObject().put("cols", cols).put("rows", rows)
        .put("body", body.toIntArray().toJsonArray()).put("dir", dir.name)
        .put("food", food).put("score", score).put("eaten", eaten).put("grow", grow)

    override fun onLayout() { if (::grid.isInitialized) grid.fit(area, px * 3) }

    private fun placeFood() {
        val occupied = body.toHashSet()
        val free = (0 until cols * rows).filter { it !in occupied && it != bonus }
        food = if (free.isEmpty()) -1 else free[host.rng.nextInt(free.size)]
    }

    private fun turn(d: Dir) {
        started = true
        val last = queue.lastOrNull() ?: dir
        if (d == last || d == last.opposite) return
        if (queue.size < 2) queue.addLast(d)
    }

    private fun speed() = baseSpeed + minOf(6f, eaten * 0.12f)

    override fun update(dt: Float) {
        t += dt
        if (deadT >= 0f) { deadT += dt; return }
        if (!started || finished) return
        if (bonus >= 0) { bonusT -= dt; if (bonusT <= 0f) bonus = -1 }
        stepT += dt
        val interval = 1f / speed()
        while (stepT >= interval && deadT < 0f) { stepT -= interval; step() }
    }

    private fun step() {
        queue.removeFirstOrNull()?.let { dir = it }
        val head = body.first()
        var c = head % cols + dir.dx
        var r = head / cols + dir.dy
        if (wrap) { c = (c + cols) % cols; r = (r + rows) % rows }
        else if (c !in 0 until cols || r !in 0 until rows) { die(); return }
        val nh = r * cols + c
        // the tail moves out of the way this step unless we're growing
        val tailFree = grow == 0
        if (body.contains(nh) && !(tailFree && nh == body.last())) { die(); return }
        body.addFirst(nh)
        if (grow > 0) grow-- else body.removeLast()
        if (nh == food) {
            grow += 2; score += 10; eaten++
            host.sfx(Sfx.EAT)
            placeFood()
            if (bonus < 0 && eaten % 5 == 0) spawnBonus()
        } else if (nh == bonus) {
            grow += 4; score += 50
            bonus = -1
            host.sfx(Sfx.POWER)
        }
        if (body.size >= 20) host.unlock("len20")
        if (body.size >= 50) host.unlock("len50")
        if (score >= 500) host.unlock("score500")
        if (food < 0) win(score, "THE SNAKE FILLS THE WHOLE BOARD!")
    }

    private fun spawnBonus() {
        val occupied = body.toHashSet()
        val free = (0 until cols * rows).filter { it !in occupied && it != food }
        if (free.isEmpty()) return
        bonus = free[host.rng.nextInt(free.size)]
        bonusT = 6f
    }

    private fun die() {
        deadT = 0f
        host.sfx(Sfx.HIT)
        host.vibrate(100)
        lose(score, "LENGTH ${body.size}", 1.3f)
    }

    override fun onTouch(e: MotionEvent) = gestures.handle(e)

    override fun onKey(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> turn(Dir.LEFT)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> turn(Dir.RIGHT)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W -> turn(Dir.UP)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S -> turn(Dir.DOWN)
            else -> return false
        }
        return true
    }

    override fun hud() = listOf(
        HudItem("SCORE", score.toString(), Pal.YELLOW),
        HudItem("LENGTH", body.size.toString(), Pal.GREEN),
        HudItem("BEST", maxOf(score, host.bestScore()).toString()),
    )

    override fun draw(c: Canvas) {
        val p = px
        val cs = grid.cell
        drawBoardFrame(c, grid.bounds, p, if (wrap) Pal.CYAN else Pal.GREEN)
        Gfx.rect(c, grid.bounds, Pal.mix(Pal.BG0, Pal.DGREEN, 0.12f))
        for (i in 0 until cols * rows) if ((i % cols + i / cols) % 2 == 0) {
            Gfx.rect(c, grid.rect(i % cols, i / cols), Pal.mix(Pal.BG0, Pal.DGREEN, 0.2f))
        }
        if (wrap) {
            val dash = ((t * 6).toInt() % 2 == 0)
            if (dash) Gfx.frame(c, grid.bounds, maxOf(1f, p / 2), Pal.withAlpha(Pal.CYAN, 120))
        }
        val r = RectF()
        if (food >= 0) {
            val bob = kotlin.math.sin(t * 6f) * cs * 0.06f
            grid.rect(food % cols, food / cols, r)
            r.offset(0f, bob)
            APPLE.draw(c, r)
        }
        if (bonus >= 0 && (bonusT > 2f || (t * 8).toInt() % 2 == 0)) {
            grid.rect(bonus % cols, bonus / cols, r)
            STAR.draw(c, r)
        }
        val dead = deadT >= 0f
        val n = body.size
        body.forEachIndexed { k, cell ->
            grid.rect(cell % cols, cell / cols, r, if (k == 0) 0f else cs * 0.08f)
            val col = when {
                dead && ((deadT * 10).toInt() % 2 == 0) -> Pal.WHITE
                k == 0 -> Pal.GREEN
                else -> Pal.mix(Pal.GREEN, Pal.DGREEN, k / n.toFloat())
            }
            Gfx.bevel(c, r, col, maxOf(1f, cs / 8f))
            if (k == 0) {
                // eyes looking in the direction of travel
                val e = cs * 0.18f
                val ex = r.centerX() + dir.dx * cs * 0.15f
                val ey = r.centerY() + dir.dy * cs * 0.15f
                val ox = if (dir.dx == 0) cs * 0.2f else 0f
                val oy = if (dir.dy == 0) cs * 0.2f else 0f
                Gfx.rect(c, ex - ox - e / 2, ey - oy - e / 2, ex - ox + e / 2, ey - oy + e / 2, Pal.BLACK)
                Gfx.rect(c, ex + ox - e / 2, ey + oy - e / 2, ex + ox + e / 2, ey + oy + e / 2, Pal.BLACK)
            }
        }
        if (!started && !dead) {
            if ((t * 2).toInt() % 2 == 0) Gfx.text(c, "SWIPE TO START", grid.bounds.centerX(), grid.bounds.top + grid.bounds.height() * 0.3f, p, Pal.YELLOW, Align.CENTER)
        }
    }

    companion object {
        val APPLE = PixelArt(
            "....m...",
            "...mG...",
            ".RRRRRR.",
            "RRWRRRRR",
            "RWRRRRRR",
            "RRRRRRRR",
            ".RRRRRR.",
            "..RRRR..",
        )
        val STAR = PixelArt(
            "...YY...",
            "...YY...",
            "YYYYYYYY",
            ".YYYYYY.",
            "..YYYY..",
            ".YYYYYY.",
            ".YY..YY.",
            "Y......Y",
        )
    }
}

val SnakeGame = GameDef(
    id = "snake",
    title = "SNAKE",
    category = Category.ARCADE,
    tagline = "Eat, grow longer, and don't bite your own tail.",
    description = "Guide the hungry snake around the board. Every apple makes it longer and faster. " +
        "Golden stars are worth big bonus points but vanish quickly. On EASY the walls wrap around.",
    icon = PixelArt(
        "................",
        "................",
        "..GGGGGGGG......",
        "..GDDDDDDG......",
        "..GD....DG......",
        "..GD....DGGGGG..",
        "..GD....DDDDDG..",
        "..GD........DG..",
        "..GD........DG..",
        ".GGGG.......DG..",
        ".GKGK.......DG..",
        ".GGGG.......GG..",
        "..R.............",
        ".........mG.....",
        "........RRRR....",
        ".........RR.....",
    ),
    accent = Pal.GREEN,
    music = Music.ARCADE,
    scoreKind = ScoreKind.HIGH_SCORE,
    difficultyLabels = listOf("EASY WRAP", "NORMAL", "HARD"),
    tutorial = listOf(
        TutorialPage("OBJECTIVE", "Eat apples to grow and score points. The snake speeds up as it grows. Crash into a wall or your own body and the game is over.",
            PixelArt(
                "..........",
                ".GGGGG....",
                ".G...G....",
                ".G...GGGK.",
                "..........",
                "......R...",
            )),
        TutorialPage("CONTROLS", "SWIPE in any direction to turn. You can swipe twice quickly to queue two turns - great for tight U-turns! Arrow keys also work.", Icons.SWIPE),
        TutorialPage("SCORING", "Apples are worth 10 points and add two segments. Every fifth apple spawns a golden STAR worth 50 points - grab it before it disappears!", Icons.STAR),
        TutorialPage("WALLS", "On EASY the board wraps: leave one side and appear on the other. On NORMAL and HARD the walls are deadly.", Icons.HELP),
        TutorialPage("TIPS", "Stay near the edges and leave the center open so you always have room to turn. Plan your route to an apple before you go for it.", Icons.BULB),
    ),
    achievements = listOf(
        AchievementDef("len20", "GROWING UP", "Reach a length of 20."),
        AchievementDef("len50", "ANACONDA", "Reach a length of 50."),
        AchievementDef("score500", "HUNGRY HUNGRY", "Score 500 points in one game."),
    ),
    factory = { SnakeSession(it) },
)
