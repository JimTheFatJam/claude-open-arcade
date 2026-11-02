package com.openarcade.games.maze

import android.graphics.Canvas
import android.graphics.Path
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
import com.openarcade.gfx.PixelFont
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.round

/** The original Open Arcade maze. '#' wall, '.' pellet, 'o' power pellet, ' ' empty, '-' door, 'G' ghost house. */
object MazeMap {
    private val LEFT = listOf(
        "##########",
        "#o.......#",
        "#.##.###.#",
        "#.##.#....",
        "#....#.###",
        "##.#...#..",
        "##.###.#.#",
        "#......#.#",
        "#.####....",
        "#.#### ##-",
        "       #GG",
        "#.#### ###",
        "#.####....",
        "#......#.#",
        "#.##.#.#.#",
        "#o.#.#....",
        "##.#.#.#.#",
        "#....#...#",
        "#.######.#",
        "#.........",
        "##########",
    )
    /** Full map, mirrored left/right around the centre column. */
    val ROWS: List<String> = LEFT.map { it + it.substring(0, 9).reversed() }
    val W = ROWS[0].length
    val H = ROWS.size
    const val TUNNEL_ROW = 10
    const val DOOR_X = 9
    const val DOOR_Y = 9
    const val START_X = 9
    const val START_Y = 15
    const val HOME_X = 9
    const val HOME_Y = 10
    const val EXIT_Y = 8
    const val FRUIT_Y = 12

    fun at(x: Int, y: Int): Char {
        if (y !in 0 until H) return '#'
        val xx = ((x % W) + W) % W
        return ROWS[y][xx]
    }
}

class MazeMuncherSession(host: GameHost) : GameSession(host) {
    override val realtime = true

    private class Actor(var x: Float, var y: Float, var dir: Dir?) {
        val tx get() = round(x).toInt()
        val ty get() = round(y).toInt()
    }

    private enum class GState { HOME, EXITING, ACTIVE, FRIGHT, EATEN }

    private val diff = host.difficulty
    private val grid = GridFit(MazeMap.W, MazeMap.H)
    private val pellets = BooleanArray(MazeMap.W * MazeMap.H)
    private var pelletsLeft = 0
    private var score = 0
    private var lives = 3
    private var level = 1
    private var extraLifeGiven = false

    private val player = Actor(MazeMap.START_X.toFloat(), MazeMap.START_Y.toFloat(), null)
    private var wanted: Dir? = null
    private var facing = Dir.LEFT
    private val ghosts = Array(4) { Actor(0f, 0f, Dir.LEFT) }
    private val gstate = Array(4) { GState.HOME }
    private val releaseAt = floatArrayOf(0f, 1.5f, 5f, 9f)
    private val GHOST_COLORS = intArrayOf(Pal.RED, Pal.PINK, Pal.CYAN, Pal.ORANGE)

    private var lifeT = 0f
    private var modeT = 0f
    private var modeIndex = 0
    private val modeDur = floatArrayOf(7f, 20f, 7f, 20f, 5f, Float.MAX_VALUE)
    private var frightT = 0f
    private var chain = 0
    private var dyingT = -1f
    private var clearT = -1f
    private var readyT = 2f
    private var fruitT = 0f
    private var fruitsSpawned = 0
    private var t = 0f
    private var mouth = 0f
    private val popups = ArrayList<Triple<String, Float, FloatArray>>() // text, age, (x, y)
    private val gestures = Gestures { px * 3 }
    private val path = Path()

    init {
        gestures.continuousSwipe = true
        gestures.onSwipe = { wanted = it }
    }

    override fun start(saved: JSONObject?) {
        if (saved != null) {
            level = saved.getInt("level"); score = saved.getInt("score"); lives = saved.getInt("lives")
            extraLifeGiven = saved.optBoolean("extra")
            val pl = saved.getJSONArray("pellets").toIntArray()
            for (i in pellets.indices) pellets[i] = pl[i] == 1
            pelletsLeft = pellets.count { it }
            if (pelletsLeft == 0) resetPellets()
        } else resetPellets()
        resetActors()
    }

    override fun save(): JSONObject = JSONObject().put("level", level).put("score", score).put("lives", lives)
        .put("extra", extraLifeGiven).put("pellets", IntArray(pellets.size) { if (pellets[it]) 1 else 0 }.toJsonArray())

    private fun resetPellets() {
        for (y in 0 until MazeMap.H) for (x in 0 until MazeMap.W) {
            val ch = MazeMap.ROWS[y][x]
            pellets[y * MazeMap.W + x] = ch == '.' || ch == 'o'
        }
        pelletsLeft = pellets.count { it }
        fruitsSpawned = 0
    }

    private fun resetActors() {
        player.x = MazeMap.START_X.toFloat(); player.y = MazeMap.START_Y.toFloat(); player.dir = null
        wanted = null; facing = Dir.LEFT
        ghosts[0].apply { x = MazeMap.DOOR_X.toFloat(); y = MazeMap.EXIT_Y.toFloat(); dir = Dir.LEFT }
        gstate[0] = GState.ACTIVE
        for (k in 1..3) {
            ghosts[k].x = (MazeMap.HOME_X + (k - 2)).toFloat(); ghosts[k].y = MazeMap.HOME_Y.toFloat(); ghosts[k].dir = Dir.UP
            gstate[k] = GState.HOME
        }
        lifeT = 0f; modeT = 0f; modeIndex = 0; frightT = 0f; dyingT = -1f; readyT = 2f
    }

    override fun onLayout() = grid.fit(area, px * 3)

    // ------------------------------------------------------------------ movement
    private fun open(x: Int, y: Int, ghost: Boolean, throughDoor: Boolean = false): Boolean {
        val ch = MazeMap.at(x, y)
        return when (ch) {
            '#' -> false
            '-' -> throughDoor
            'G' -> ghost && throughDoor
            else -> true
        }
    }

    /** Moves [a] along its direction by [dist] tiles; [decide] is called at each tile centre. */
    private inline fun advance(a: Actor, dist: Float, decide: (Actor) -> Dir?) {
        var remaining = dist
        var guard = 0
        while (remaining > 1e-5f && guard++ < 8) {
            val atCenter = abs(a.x - a.tx) < 1e-3f && abs(a.y - a.ty) < 1e-3f
            if (atCenter) {
                a.x = a.tx.toFloat(); a.y = a.ty.toFloat()
                a.dir = decide(a)
                if (a.dir == null) return
            }
            val d = a.dir ?: return
            // distance to the next centre in the direction of travel
            val toNext = if (d.dx != 0) {
                val target = if (atCenter) a.tx + d.dx else (if (d.dx > 0) kotlin.math.ceil(a.x) else kotlin.math.floor(a.x)).toInt()
                abs(target - a.x)
            } else {
                val target = if (atCenter) a.ty + d.dy else (if (d.dy > 0) kotlin.math.ceil(a.y) else kotlin.math.floor(a.y)).toInt()
                abs(target - a.y)
            }
            val step = minOf(remaining, toNext)
            a.x += d.dx * step; a.y += d.dy * step
            remaining -= step
            // tunnel wrap
            if (a.x < -0.5f) a.x += MazeMap.W
            if (a.x > MazeMap.W - 0.5f) a.x -= MazeMap.W
        }
    }

    private fun playerSpeed() = (if (diff == Difficulty.HARD) 8f else 7.5f) * (1f + minOf(0.15f, (level - 1) * 0.03f))
    private fun ghostSpeed() = when (diff) { Difficulty.EASY -> 5.8f; Difficulty.NORMAL -> 6.9f; Difficulty.HARD -> 7.7f } * (1f + minOf(0.2f, (level - 1) * 0.04f))
    private fun frightDuration() = maxOf(2f, when (diff) { Difficulty.EASY -> 8f; Difficulty.NORMAL -> 6f; Difficulty.HARD -> 4.5f } - (level - 1) * 0.5f)

    override fun update(dt: Float) {
        t += dt
        if (finished) return
        popups.replaceAll { Triple(it.first, it.second + dt, it.third) }
        popups.removeAll { it.second > 1.2f }
        if (clearT >= 0f) {
            clearT += dt
            if (clearT > 2.2f) { clearT = -1f; level++; resetPellets(); resetActors(); host.sfx(Sfx.START) }
            return
        }
        if (dyingT >= 0f) {
            dyingT += dt
            if (dyingT > 1.6f) {
                if (lives <= 0) { lose(score, "LEVEL $level", 0.2f); return }
                resetActors()
            }
            return
        }
        if (readyT > 0f) { readyT -= dt; return }
        lifeT += dt

        // scatter / chase schedule (paused while frightened)
        if (frightT > 0f) {
            frightT -= dt
            if (frightT <= 0f) for (k in 0..3) if (gstate[k] == GState.FRIGHT) gstate[k] = GState.ACTIVE
        } else {
            modeT += dt
            if (modeT > modeDur[modeIndex] && modeIndex < modeDur.size - 1) {
                modeT = 0f; modeIndex++
                for (k in 0..3) if (gstate[k] == GState.ACTIVE) ghosts[k].dir = ghosts[k].dir?.opposite
            }
        }

        // player
        mouth += dt * 14f
        advance(player, playerSpeed() * dt) { a ->
            val w = wanted
            if (w != null && open(a.tx + w.dx, a.ty + w.dy, false)) { facing = w; w }
            else {
                val d = a.dir
                if (d != null && open(a.tx + d.dx, a.ty + d.dy, false)) d else null
            }
        }
        // reverse instantly between tiles
        val w = wanted
        if (w != null && player.dir != null && w == player.dir?.opposite) { player.dir = w; facing = w }
        if (player.dir != null) facing = player.dir!!
        eatAt(player.tx, player.ty)

        // ghosts
        for (k in 0..3) updateGhost(k, dt)
        collide()

        if (fruitT > 0f) fruitT -= dt
        val eaten = totalPellets() - pelletsLeft
        if ((eaten >= 60 && fruitsSpawned == 0) || (eaten >= 130 && fruitsSpawned == 1)) { fruitsSpawned++; fruitT = 9f }
        if (fruitT > 0f && player.tx == MazeMap.START_X && player.ty == MazeMap.FRUIT_Y) {
            val pts = 100 * level
            score += pts; fruitT = 0f
            host.sfx(Sfx.COIN)
            popups.add(Triple(pts.toString(), 0f, floatArrayOf(player.x, player.y)))
        }
        if (!extraLifeGiven && score >= 10000) { extraLifeGiven = true; lives++; host.sfx(Sfx.LEVELUP); host.toast("EXTRA LIFE!") }
        if (score >= 10000) host.unlock("score10k")
    }

    private val totalCache by lazy { MazeMap.ROWS.sumOf { r -> r.count { it == '.' || it == 'o' } } }
    private fun totalPellets() = totalCache

    private fun eatAt(x: Int, y: Int) {
        if (x !in 0 until MazeMap.W) return
        val i = y * MazeMap.W + x
        if (!pellets[i]) return
        pellets[i] = false
        pelletsLeft--
        if (MazeMap.ROWS[y][x] == 'o') {
            score += 50
            frightT = frightDuration(); chain = 0
            for (k in 0..3) if (gstate[k] == GState.ACTIVE) { gstate[k] = GState.FRIGHT; ghosts[k].dir = ghosts[k].dir?.opposite }
            host.sfx(Sfx.POWER)
        } else {
            score += 10
            if (pelletsLeft % 2 == 0) host.sfx(Sfx.TICK)
        }
        if (pelletsLeft == 0) {
            clearT = 0f
            host.sfx(Sfx.LEVELUP)
            host.unlock("level")
            if (level >= 3) host.unlock("level3")
        }
    }

    private fun scatterTarget(k: Int) = when (k) { 0 -> MazeMap.W - 2 to -2; 1 -> 1 to -2; 2 -> MazeMap.W - 1 to MazeMap.H + 1; else -> 0 to MazeMap.H + 1 }

    private fun chaseTarget(k: Int): Pair<Int, Int> {
        val px = player.tx; val py = player.ty
        return when (k) {
            0 -> px to py
            1 -> (px + facing.dx * 4) to (py + facing.dy * 4)
            2 -> {
                val ax = px + facing.dx * 2; val ay = py + facing.dy * 2
                (ax * 2 - ghosts[0].tx) to (ay * 2 - ghosts[0].ty)
            }
            else -> if (hypot((ghosts[3].tx - px).toFloat(), (ghosts[3].ty - py).toFloat()) > 7f) px to py else scatterTarget(3)
        }
    }

    private fun updateGhost(k: Int, dt: Float) {
        val g = ghosts[k]
        when (gstate[k]) {
            GState.HOME -> {
                // bob up and down until released
                val dy = if (((t * 2).toInt() + k) % 2 == 0) 1 else -1
                g.y = MazeMap.HOME_Y + dy * 0.15f
                val dotsRule = (totalPellets() - pelletsLeft) > k * 15
                if (lifeT >= releaseAt[k] || (dotsRule && lifeT > 1f)) { gstate[k] = GState.EXITING; g.y = MazeMap.HOME_Y.toFloat() }
            }
            GState.EXITING -> {
                val sp = 4f * dt
                if (abs(g.x - MazeMap.DOOR_X) > 0.01f) {
                    g.x += (if (g.x < MazeMap.DOOR_X) 1 else -1) * minOf(sp, abs(g.x - MazeMap.DOOR_X))
                } else {
                    g.x = MazeMap.DOOR_X.toFloat()
                    g.y -= sp
                    if (g.y <= MazeMap.EXIT_Y) { g.y = MazeMap.EXIT_Y.toFloat(); g.dir = Dir.LEFT; gstate[k] = GState.ACTIVE }
                }
            }
            GState.EATEN -> {
                if (g.tx == MazeMap.DOOR_X && g.ty == MazeMap.EXIT_Y && abs(g.x - g.tx) < 0.05f && abs(g.y - g.ty) < 0.05f) {
                    // enter the house
                    g.x = MazeMap.DOOR_X.toFloat()
                    g.y += 8f * dt
                    if (g.y >= MazeMap.HOME_Y) { g.y = MazeMap.HOME_Y.toFloat(); gstate[k] = GState.EXITING }
                    return
                }
                if (g.y > MazeMap.EXIT_Y + 0.05f && abs(g.x - MazeMap.DOOR_X) < 0.05f && g.y <= MazeMap.HOME_Y) {
                    g.y += 8f * dt
                    if (g.y >= MazeMap.HOME_Y) { g.y = MazeMap.HOME_Y.toFloat(); gstate[k] = GState.EXITING }
                    return
                }
                advance(g, 14f * dt) { a -> chooseDir(a, MazeMap.DOOR_X to MazeMap.EXIT_Y, false) }
            }
            GState.ACTIVE, GState.FRIGHT -> {
                val inTunnel = g.ty == MazeMap.TUNNEL_ROW && (g.tx < 5 || g.tx > MazeMap.W - 6)
                var sp = if (gstate[k] == GState.FRIGHT) 4.2f else ghostSpeed()
                if (inTunnel) sp *= 0.55f
                val frightened = gstate[k] == GState.FRIGHT
                advance(g, sp * dt) { a ->
                    val target = if (modeIndex % 2 == 0) scatterTarget(k) else chaseTarget(k)
                    chooseDir(a, target, frightened)
                }
            }
        }
    }

    /** Picks the open direction (never reversing) that gets closest to [target], or random when frightened. */
    private fun chooseDir(a: Actor, target: Pair<Int, Int>, random: Boolean): Dir? {
        val back = a.dir?.opposite
        val options = Dir.values().filter { it != back && open(a.tx + it.dx, a.ty + it.dy, true) }
        if (options.isEmpty()) return back
        if (random) return options[host.rng.nextInt(options.size)]
        // tie-break order up, left, down, right
        val order = listOf(Dir.UP, Dir.LEFT, Dir.DOWN, Dir.RIGHT)
        return options.minWithOrNull(compareBy<Dir>({
            val nx = a.tx + it.dx; val ny = a.ty + it.dy
            ((nx - target.first) * (nx - target.first) + (ny - target.second) * (ny - target.second))
        }, { order.indexOf(it) }))
    }

    private fun collide() {
        for (k in 0..3) {
            val g = ghosts[k]
            var dx = abs(g.x - player.x)
            if (dx > MazeMap.W / 2f) dx = MazeMap.W - dx
            if (dx < 0.6f && abs(g.y - player.y) < 0.6f) {
                when (gstate[k]) {
                    GState.FRIGHT -> {
                        chain++
                        val pts = 200 * (1 shl (chain - 1))
                        score += pts
                        gstate[k] = GState.EATEN
                        host.sfx(Sfx.CAPTURE)
                        host.vibrate(30)
                        popups.add(Triple(pts.toString(), 0f, floatArrayOf(g.x, g.y)))
                        if (chain >= 4) host.unlock("ghosts4")
                    }
                    GState.ACTIVE -> {
                        lives--
                        dyingT = 0f
                        host.sfx(Sfx.LOSE)
                        host.vibrate(150)
                        return
                    }
                    else -> {}
                }
            }
        }
    }

    // ------------------------------------------------------------------ input
    override fun onTouch(e: MotionEvent) = gestures.handle(e)

    override fun onKey(keyCode: Int): Boolean {
        wanted = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> Dir.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> Dir.RIGHT
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W -> Dir.UP
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S -> Dir.DOWN
            else -> return false
        }
        return true
    }

    override fun hud() = listOf(
        HudItem("SCORE", score.toString(), Pal.YELLOW),
        HudItem("LEVEL", level.toString(), Pal.CYAN),
        HudItem("LIVES", PixelFont.HEART.toString().repeat(maxOf(0, lives)).ifEmpty { "-" }, Pal.RED),
    )

    // ------------------------------------------------------------------ drawing
    override fun draw(c: Canvas) {
        val p = px
        val cs = grid.cell
        drawBoardFrame(c, grid.bounds, p, Pal.BLUE)
        Gfx.rect(c, grid.bounds, Pal.BLACK)
        val flash = clearT >= 0f && ((clearT * 5).toInt() % 2 == 0)
        val wallFill = if (flash) Pal.WHITE else Pal.mix(Pal.DBLUE, Pal.BLACK, 0.55f)
        val wallEdge = if (flash) Pal.WHITE else Pal.BLUE
        val e = maxOf(1f, (cs / 6f).toInt().toFloat())
        for (y in 0 until MazeMap.H) for (x in 0 until MazeMap.W) {
            val ch = MazeMap.ROWS[y][x]
            val l = grid.x(x); val tt = grid.y(y)
            when (ch) {
                '#' -> {
                    Gfx.rect(c, l, tt, l + cs, tt + cs, wallFill)
                    if (MazeMap.at(x, y - 1) != '#' && y > 0) Gfx.rect(c, l, tt, l + cs, tt + e, wallEdge)
                    if (MazeMap.at(x, y + 1) != '#' && y < MazeMap.H - 1) Gfx.rect(c, l, tt + cs - e, l + cs, tt + cs, wallEdge)
                    if (x > 0 && MazeMap.at(x - 1, y) != '#') Gfx.rect(c, l, tt, l + e, tt + cs, wallEdge)
                    if (x < MazeMap.W - 1 && MazeMap.at(x + 1, y) != '#') Gfx.rect(c, l + cs - e, tt, l + cs, tt + cs, wallEdge)
                }
                '-' -> Gfx.rect(c, l, tt + cs * 0.4f, l + cs, tt + cs * 0.6f, Pal.PINK)
            }
            if (pellets[y * MazeMap.W + x]) {
                if (ch == 'o') {
                    if ((t * 4).toInt() % 2 == 0) Gfx.disc(c, l + cs / 2, tt + cs / 2, cs * 0.32f, Pal.PEACH, maxOf(1f, cs / 8f))
                } else {
                    val s = maxOf(2f, (cs * 0.18f).toInt().toFloat())
                    Gfx.rect(c, l + cs / 2 - s / 2, tt + cs / 2 - s / 2, l + cs / 2 + s / 2, tt + cs / 2 + s / 2, Pal.PEACH)
                }
            }
        }
        if (fruitT > 0f) {
            val fx = grid.x(MazeMap.START_X); val fy = grid.y(MazeMap.FRUIT_Y)
            CHERRY.draw(c, RectF(fx - cs * 0.1f, fy - cs * 0.1f, fx + cs * 1.1f, fy + cs * 1.1f))
        }
        // ghosts
        if (dyingT < 0f || dyingT < 0.3f) for (k in 0..3) drawGhost(c, k)
        drawPlayer(c)
        for ((txt, age, pos) in popups) {
            Gfx.text(c, txt, grid.cx(pos[0]), grid.cy(pos[1]) - age * cs, maxOf(1f, cs / 9f), Pal.CYAN, Align.CENTER)
        }
        if (readyT > 0f && dyingT < 0f && clearT < 0f) {
            Gfx.textMid(c, "READY!", grid.cx(MazeMap.DOOR_X.toFloat()), grid.cy(12f), maxOf(1f, cs / 6f), Pal.YELLOW)
        }
        if (clearT >= 0f) Gfx.textMid(c, "LEVEL CLEAR!", grid.cx(MazeMap.DOOR_X.toFloat()), grid.cy(12f), maxOf(1f, cs / 6f), Pal.YELLOW)
    }

    private fun drawPlayer(c: Canvas) {
        val cs = grid.cell
        val cx = grid.cx(player.x); val cy = grid.cy(player.y)
        val r = cs * 0.62f
        if (dyingT >= 0f) {
            // shrink into a dot
            val k = (dyingT / 1.2f).coerceIn(0f, 1f)
            if (k < 1f) {
                Gfx.disc(c, cx, cy, r * (1f - k), Pal.YELLOW, maxOf(1f, cs / 8f))
                val open = 180f * k
                wedge(c, cx, cy, r, 270f, open)
            }
            return
        }
        Gfx.disc(c, cx, cy, r, Pal.YELLOW, maxOf(1f, cs / 8f))
        val open = if (player.dir == null) 20f else 10f + 40f * abs(kotlin.math.sin(mouth))
        val ang = when (facing) { Dir.RIGHT -> 0f; Dir.DOWN -> 90f; Dir.LEFT -> 180f; Dir.UP -> 270f }
        wedge(c, cx, cy, r + 2, ang, open)
    }

    private fun wedge(c: Canvas, cx: Float, cy: Float, r: Float, angleDeg: Float, openDeg: Float) {
        if (openDeg <= 0f) return
        path.reset()
        path.moveTo(cx, cy)
        val a1 = Math.toRadians((angleDeg - openDeg).toDouble())
        val a2 = Math.toRadians((angleDeg + openDeg).toDouble())
        path.lineTo(cx + (r * 1.2f * kotlin.math.cos(a1)).toFloat(), cy + (r * 1.2f * kotlin.math.sin(a1)).toFloat())
        path.lineTo(cx + (r * 1.2f * kotlin.math.cos(a2)).toFloat(), cy + (r * 1.2f * kotlin.math.sin(a2)).toFloat())
        path.close()
        Gfx.paint.color = Pal.BLACK
        c.drawPath(path, Gfx.paint)
    }

    private fun drawGhost(c: Canvas, k: Int) {
        val g = ghosts[k]
        val cs = grid.cell
        val cx = grid.cx(g.x); val cy = grid.cy(g.y)
        val size = cs * 1.3f
        val box = RectF(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
        val st = gstate[k]
        val frame = ((t * 6).toInt() % 2)
        if (st != GState.EATEN) {
            val col = if (st == GState.FRIGHT) {
                if (frightT < 2f && ((t * 6).toInt() % 2 == 0)) Pal.WHITE else Pal.BLUE
            } else GHOST_COLORS[k]
            val body = if (frame == 0) GHOST_A else GHOST_B
            body.draw(c, box, tint = col)
        }
        if (st == GState.FRIGHT) {
            // scared face
            val px2 = size / 14f
            Gfx.rect(c, box.left + px2 * 4, box.top + px2 * 5, box.left + px2 * 6, box.top + px2 * 7, Pal.PEACH)
            Gfx.rect(c, box.left + px2 * 8, box.top + px2 * 5, box.left + px2 * 10, box.top + px2 * 7, Pal.PEACH)
            for (i in 0 until 5) Gfx.rect(c, box.left + px2 * (3 + i * 2), box.top + px2 * (9 + i % 2), box.left + px2 * (4 + i * 2), box.top + px2 * (10 + i % 2), Pal.PEACH)
        } else {
            val d = g.dir ?: Dir.LEFT
            val px2 = size / 14f
            for (ex in intArrayOf(3, 8)) {
                Gfx.rect(c, box.left + px2 * ex, box.top + px2 * 4, box.left + px2 * (ex + 3), box.top + px2 * 8, Pal.WHITE)
                val pxo = box.left + px2 * (ex + 1 + d.dx * 0.9f)
                val pyo = box.top + px2 * (5 + 1 + d.dy * 1.2f)
                Gfx.rect(c, pxo, pyo, pxo + px2 * 1.5f, pyo + px2 * 1.5f, Pal.DBLUE)
            }
        }
    }

    companion object {
        private val GHOST_A = PixelArt(
            ".....WWWW.....",
            "...WWWWWWWW...",
            "..WWWWWWWWWW..",
            ".WWWWWWWWWWWW.",
            ".WWWWWWWWWWWW.",
            "WWWWWWWWWWWWWW",
            "WWWWWWWWWWWWWW",
            "WWWWWWWWWWWWWW",
            "WWWWWWWWWWWWWW",
            "WWWWWWWWWWWWWW",
            "WWWWWWWWWWWWWW",
            "WWWWWWWWWWWWWW",
            "WW.WWW..WWW.WW",
            "W...WW..WW...W",
        )
        private val GHOST_B = PixelArt(
            ".....WWWW.....",
            "...WWWWWWWW...",
            "..WWWWWWWWWW..",
            ".WWWWWWWWWWWW.",
            ".WWWWWWWWWWWW.",
            "WWWWWWWWWWWWWW",
            "WWWWWWWWWWWWWW",
            "WWWWWWWWWWWWWW",
            "WWWWWWWWWWWWWW",
            "WWWWWWWWWWWWWW",
            "WWWWWWWWWWWWWW",
            "WWWWWWWWWWWWWW",
            "WWWW.WWWW.WWWW",
            ".WW...WW...WW.",
        )
        private val CHERRY = PixelArt(
            "......DD",
            ".....D..",
            "....D.D.",
            "...D...D",
            ".RR...RR",
            "RRWR.RWRR".substring(0, 8),
            "RRRR.RRR",
            ".RR...RR",
        )
    }
}

val MazeMuncherGame = GameDef(
    id = "mazemuncher",
    title = "MAZE MUNCHER",
    category = Category.ARCADE,
    tagline = "Gobble every pellet in the maze while four ghosts give chase.",
    description = "A maze-chase classic in the spirit of the golden age arcades, with an all-new maze. " +
        "Eat the pellets, grab a power pellet to turn the tables, and chomp the ghosts for big points. Each ghost hunts you in its own way!",
    icon = PixelArt(
        "................",
        ".....YYYYY......",
        "...YYYYYYYYY....",
        "..YYYYYYYYYY....",
        "..YYYYYYYY......",
        ".YYYYYYY........",
        ".YYYYY.....ee.ee",
        ".YYYYYYY........",
        "..YYYYYYYY......",
        "..YYYYYYYYYY....",
        "...YYYYYYYYY....",
        ".....YYYYY......",
        "................",
        "..RRRR....pppp..",
        ".RWRWRR..pWpWpp.",
        ".RRRRRR..pppppp.",
    ),
    accent = Pal.YELLOW,
    music = Music.ARCADE,
    scoreKind = ScoreKind.HIGH_SCORE,
    tutorial = listOf(
        TutorialPage("OBJECTIVE", "Eat every pellet in the maze to clear the level. Four ghosts roam the maze - if one touches you, you lose a life. Lose all three and it's game over.",
            PixelArt(
                "BBBBBBBBBB",
                "B........B",
                "B.YY.ee..B",
                "B.YY..RR.B",
                "B.....RR.B",
                "BBBBBBBBBB",
            )),
        TutorialPage("CONTROLS", "SWIPE to steer. You can swipe BEFORE reaching a corner - the muncher remembers your next turn and takes it as soon as it can. Arrow keys work too.", Icons.SWIPE),
        TutorialPage("POWER PELLETS", "The big flashing pellets turn the ghosts blue for a few seconds. Blue ghosts can be eaten: 200, 400, 800 and 1600 points in a row! When they flash white, run.", PixelArt(
            "..BBBB..",
            ".BBBBBB.",
            "BBeBBeBB",
            "BBBBBBBB",
            "BeBeBeBB",
            "BBBBBBBB",
            "B.BB.BB.",
        )),
        TutorialPage("GHOSTS", "RED chases you directly. PINK aims ahead of you. CYAN works with Red to trap you. ORANGE chases until it gets close, then wanders off. The side tunnels slow ghosts down.", Icons.HELP),
        TutorialPage("TIPS", "Save power pellets until ghosts are close. Use the tunnel to escape. A bonus cherry appears twice per level below the ghost house - and 10,000 points earns an extra life!", Icons.BULB),
    ),
    achievements = listOf(
        AchievementDef("level", "MAZE RUNNER", "Clear a level."),
        AchievementDef("ghosts4", "GHOSTBUSTER", "Eat all four ghosts with one power pellet."),
        AchievementDef("level3", "ENDURANCE", "Clear level 3."),
        AchievementDef("score10k", "PELLET KING", "Score 10,000 points."),
    ),
    factory = { MazeMuncherSession(it) },
)
