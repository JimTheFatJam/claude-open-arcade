package com.openarcade.games.rollblock

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
import com.openarcade.games.GameAction
import com.openarcade.games.GameDef
import com.openarcade.games.GameHost
import com.openarcade.games.GameSession
import com.openarcade.games.HudItem
import com.openarcade.games.LevelTrack
import com.openarcade.games.ScoreKind
import com.openarcade.games.TutorialDemo
import com.openarcade.games.TutorialPage
import com.openarcade.gfx.Align
import com.openarcade.gfx.Gfx
import com.openarcade.gfx.Icons
import com.openarcade.gfx.Pal
import com.openarcade.gfx.PixelArt
import com.openarcade.gfx.PixelFont
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/** Board geometry plus all Roll Block drawing, shared by the game and its tutorial demo. */
class RollView {
    var cell = 0f; private set
    var ox = 0f; private set
    var oy = 0f; private set
    val board = RectF()

    /** Fits [lv] into [box], leaving one cell of head room so an upright block never clips. */
    fun fit(lv: RollLevel, box: RectF) {
        cell = floor(minOf(box.width() / lv.w, box.height() / (lv.h + 1.2f))).coerceAtLeast(2f)
        ox = floor(box.centerX() - cell * lv.w / 2)
        oy = floor(box.centerY() - cell * lv.h / 2 + cell * 0.5f)
        board.set(ox, oy, ox + cell * lv.w, oy + cell * lv.h)
    }

    /** Footprint of a block position on screen. */
    fun footprint(p: BlockPos, out: RectF) {
        val w = if (p.o == Orient.LIE_X) 2 else 1
        val h = if (p.o == Orient.LIE_Y) 2 else 1
        out.set(ox + p.x * cell, oy + p.y * cell, ox + (p.x + w) * cell, oy + (p.y + h) * cell)
    }

    fun height(o: Orient) = if (o == Orient.STAND) cell * 0.95f else cell * 0.48f

    fun drawTiles(c: Canvas, lv: RollLevel, bridges: Int, p: Float, t: Float) {
        val lip = floor(cell * 0.16f).coerceAtLeast(1f)
        val gap = maxOf(1f, floor(cell * 0.04f))
        for (y in 0 until lv.h) for (x in 0 until lv.w) {
            val tile = lv.tile(x, y)
            if (tile == Tile.VOID) continue
            val l = ox + x * cell + gap; val tp = oy + y * cell + gap
            val r = ox + (x + 1) * cell - gap; val b = oy + (y + 1) * cell - gap
            val g = lv.groupAt(x, y)
            val gc = if (g >= 0) GROUP_COLORS[g % GROUP_COLORS.size] else Pal.CYAN
            val belowOpen = !lv.solid(x, y + 1, bridges)
            if (tile == Tile.BRIDGE && (bridges shr g) and 1 == 0) {
                // closed bridge: a dashed outline over the gap
                val dash = maxOf(1f, floor(cell / 8f))
                var k = l
                val col = Pal.withAlpha(gc, 150)
                while (k < r) {
                    Gfx.rect(c, k, tp, minOf(r, k + dash), tp + p * 0.5f + 1, col)
                    Gfx.rect(c, k, b - p * 0.5f - 1, minOf(r, k + dash), b, col)
                    k += dash * 2
                }
                continue
            }
            val top = when (tile) {
                Tile.FRAGILE -> Pal.ORANGE
                Tile.BRIDGE -> Pal.BROWN
                Tile.GOAL -> Pal.DGREY
                else -> if ((x + y) % 2 == 0) Pal.mix(Pal.GREY, Pal.LGREY, 0.25f) else Pal.GREY
            }
            if (belowOpen) Gfx.rect(c, l, b, r, b + lip, Pal.darker(top, 0.5f))
            Gfx.bevel(c, RectF(l, tp, r, b), top, maxOf(1f, floor(cell / 14f)))
            when (tile) {
                Tile.GOAL -> {
                    val inset = cell * 0.16f
                    val pulse = (sin(t * 4f) + 1f) / 2f
                    Gfx.rect(c, l + inset, tp + inset, r - inset, b - inset, Pal.BLACK)
                    Gfx.frame(c, RectF(l + inset, tp + inset, r - inset, b - inset), maxOf(1f, floor(cell / 14f)), Pal.mix(Pal.DGREEN, Pal.GREEN, pulse))
                }
                Tile.FRAGILE -> {
                    val k = maxOf(1f, floor(cell / 12f))
                    Gfx.rect(c, l + cell * 0.25f, tp + cell * 0.3f, l + cell * 0.5f, tp + cell * 0.3f + k, Pal.darker(top, 0.35f))
                    Gfx.rect(c, l + cell * 0.45f, tp + cell * 0.3f, l + cell * 0.45f + k, tp + cell * 0.65f, Pal.darker(top, 0.35f))
                }
                Tile.BRIDGE -> {
                    val k = maxOf(1f, floor(cell / 12f))
                    for (j in 1..2) Gfx.rect(c, l, tp + (b - tp) * j / 3f, r, tp + (b - tp) * j / 3f + k, Pal.DBROWN)
                    Gfx.rect(c, l, tp, l + k * 2, b, gc)
                    Gfx.rect(c, r - k * 2, tp, r, b, gc)
                }
                Tile.SOFT -> {
                    Gfx.disc(c, (l + r) / 2, (tp + b) / 2, cell * 0.28f, Pal.darker(gc, 0.4f), maxOf(1f, cell / 12f))
                    Gfx.disc(c, (l + r) / 2, (tp + b) / 2 - cell * 0.04f, cell * 0.22f, gc, maxOf(1f, cell / 12f))
                }
                Tile.HEAVY -> {
                    val d = cell * 0.24f
                    val th = maxOf(2f, floor(cell / 7f))
                    val cx = (l + r) / 2; val cy = (tp + b) / 2
                    Gfx.line(c, cx - d, cy - d, cx + d, cy + d, th, gc)
                    Gfx.line(c, cx + d, cy - d, cx - d, cy + d, th, gc)
                }
            }
        }
    }

    private val tmp = RectF()

    /**
     * Draws the block. [from]/[to] with [k] in 0..1 animate a roll; [drop] lowers it (falling) and
     * [sink] pushes it into the goal hole.
     */
    fun drawBlock(c: Canvas, from: BlockPos, to: BlockPos, k: Float, p: Float, drop: Float = 0f, sink: Float = 0f, alpha: Int = 255) {
        val a = RectF(); val b = RectF()
        footprint(from, a); footprint(to, b)
        val e = k * k * (3 - 2 * k)
        tmp.set(a.left + (b.left - a.left) * e, a.top + (b.top - a.top) * e, a.right + (b.right - a.right) * e, a.bottom + (b.bottom - a.bottom) * e)
        var hh = height(from.o) + (height(to.o) - height(from.o)) * e
        // lift a little in the middle of a roll, like tipping over an edge
        hh += sin(e * Math.PI).toFloat() * cell * 0.12f
        val inset = maxOf(1f, floor(cell * 0.08f))
        tmp.inset(inset, inset)
        val dy = drop * cell * 3f
        val sunk = sink * hh
        val face = RectF(tmp.left, tmp.top - hh + dy + sunk, tmp.right, tmp.bottom - hh + dy + sunk)
        val front = RectF(tmp.left, face.bottom, tmp.right, tmp.bottom + dy)
        val col = Pal.withAlpha(BLOCK, alpha)
        if (drop == 0f && sink == 0f) Gfx.rect(c, tmp.left + p, tmp.top + p, tmp.right + p, tmp.bottom + p, Pal.withAlpha(Pal.BLACK, 110))
        if (front.bottom > front.top) {
            Gfx.rect(c, front, Pal.withAlpha(Pal.darker(BLOCK, 0.45f), alpha))
            Gfx.rect(c, front.left, front.top, front.left + maxOf(1f, cell / 14f), front.bottom, Pal.withAlpha(Pal.darker(BLOCK, 0.25f), alpha))
        }
        Gfx.bevel(c, face, col, maxOf(1f, floor(cell / 12f)))
        val s = maxOf(1f, floor(cell / 10f))
        Gfx.rect(c, face.left + s * 2, face.top + s * 2, face.left + s * 4, face.top + s * 4, Pal.withAlpha(Pal.WHITE, alpha * 3 / 4))
    }

    companion object {
        val BLOCK = 0xFF5A8CFF.toInt()
        val GROUP_COLORS = intArrayOf(Pal.CYAN, Pal.PINK, Pal.GREEN, Pal.YELLOW, Pal.PURPLE)
    }
}

class RollSession(host: GameHost) : GameSession(host) {
    private val pack = RollLevels.packs[host.difficulty.ordinal]
    private val track = LevelTrack(host, pack.size)
    private var levelIndex = track.current
    private var level = pack[levelIndex]
    private var par = level.par()
    private var state = level.startState
    private var moves = 0
    private var falls = 0
    private var message = ""
    private var t = 0f

    // animation
    private var animFrom = state.pos
    private var animK = 1f
    private var falling = -1f
    private var winning = -1f
    private var pending: Dir? = null

    private val view = RollView()
    private val boardBox = RectF()
    private val gestures = Gestures { px * 3 }.apply {
        onSwipe = { d -> input(d) }
        onTap = { x, y -> tapToward(x, y) }
    }

    // ---- hooks for automated tests
    internal val stateForTest get() = state
    internal val levelForTest get() = level
    internal fun rollForTest(d: Int) { animK = 1f; falling = -1f; doMove(RollLevel.DX[d], RollLevel.DY[d]); animK = 1f }

    override fun start(saved: JSONObject?) {
        if (saved != null) {
            val li = saved.getInt("level")
            require(li in pack.indices)
            load(li)
            val p = BlockPos(saved.getInt("x"), saved.getInt("y"), Orient.values()[saved.getInt("o")])
            require(level.supported(p, saved.getInt("br")))
            state = RollState(p, saved.getInt("br"))
            moves = saved.getInt("moves"); falls = saved.optInt("falls")
            animFrom = state.pos
        } else load(levelIndex)
        message = intro()
    }

    private fun load(i: Int) {
        levelIndex = i; level = pack[i]; par = level.par()
        state = level.startState; animFrom = state.pos; animK = 1f
        moves = 0; falls = 0; falling = -1f; winning = -1f; pending = null
        onLayout()
    }

    private fun intro(): String = when {
        level.usesSwitches && level.tiles.any { it == Tile.HEAVY } -> "X SWITCHES ONLY WORK WHEN THE BLOCK STANDS ON THEM."
        level.usesSwitches -> "ROUND SWITCHES OPEN AND CLOSE THE BRIDGE OF THE SAME COLOR."
        level.usesFragile -> "ORANGE TILES BREAK UNDER AN UPRIGHT BLOCK. LIE FLAT ON THEM!"
        else -> "SWIPE OR TAP TO ROLL. STAND UPRIGHT ON THE HOLE."
    }

    override fun save(): JSONObject {
        // mid-fall the round resumes from the start of the level, as it would after the fall
        val st = if (falling >= 0f) level.startState else state
        return JSONObject().put("level", levelIndex).put("x", st.pos.x).put("y", st.pos.y).put("o", st.pos.o.ordinal)
            .put("br", st.bridges).put("moves", if (falling >= 0f) 0 else moves).put("falls", falls)
    }

    override fun onLayout() {
        val textH = PixelFont.LINE * maxOf(2f, floor(px * 0.7f)) * 2 + px * 4
        boardBox.set(area.left + px * 2, area.top + px * 2, area.right - px * 2, area.bottom - textH)
        view.fit(level, boardBox)
    }

    private fun input(d: Dir) {
        if (finished || winning >= 0f) return
        if (animK < 1f || falling >= 0f) { if (falling < 0f) pending = d; return }
        doMove(d.dx, d.dy)
    }

    private fun tapToward(x: Float, y: Float) {
        val f = RectF(); view.footprint(state.pos, f)
        val dx = x - f.centerX(); val dy = y - (f.centerY() - view.height(state.pos.o) / 2)
        if (abs(dx) < view.cell * 0.3f && abs(dy) < view.cell * 0.3f) return
        input(if (abs(dx) > abs(dy)) (if (dx > 0) Dir.RIGHT else Dir.LEFT) else (if (dy > 0) Dir.DOWN else Dir.UP))
    }

    private fun doMove(dx: Int, dy: Int) {
        val (ns, ev) = level.move(state, dx, dy)
        animFrom = state.pos; animK = 0f
        val switched = ns.bridges != state.bridges
        state = ns
        moves++
        when (ev) {
            RollEvent.FELL -> {
                falling = 0f
                falls++
                host.sfx(Sfx.LOSE); host.vibrate(60)
                message = if (ns.pos.o == Orient.STAND && ns.pos.cells().all { level.tile(it[0], it[1]) == Tile.FRAGILE })
                    "CRACK! THE ORANGE TILE BROKE. TRY AGAIN." else "YOU FELL OFF! TRY AGAIN."
            }
            RollEvent.WON -> {
                winning = 0f
                host.sfx(Sfx.POWER)
                completed()
            }
            RollEvent.MOVED -> {
                host.sfx(if (switched) Sfx.FLAG else Sfx.MOVE)
                if (switched) message = "CLICK! A BRIDGE MOVED."
            }
        }
    }

    private fun completed() {
        val newBest = track.complete(levelIndex, moves)
        host.unlock("first_win")
        if (moves <= par) host.unlock("perfect")
        if (host.difficulty == Difficulty.HARD) host.unlock("hard_win")
        if (track.cleared == pack.size) host.unlock("pack_${host.difficulty.name.lowercase()}")
        val stars = when { moves <= par -> 3; moves <= par + par / 2 + 2 -> 2; else -> 1 }
        message = "IN THE HOLE!"
        win(
            detail = "LEVEL ${levelIndex + 1}  MOVES $moves  PAR $par  " + PixelFont.STAR.toString().repeat(stars) + (if (newBest) "  BEST!" else ""),
            title = "LEVEL CLEAR!", delay = 1.3f,
        )
    }

    private fun restartLevel(msg: String) {
        state = level.startState; animFrom = state.pos; animK = 1f; moves = 0; pending = null
        message = msg
    }

    private fun switchLevel(i: Int) {
        if (i !in 0 until track.unlocked || i == levelIndex) return
        track.current = i
        load(i)
        message = intro()
        host.sfx(Sfx.SELECT)
    }

    override fun update(dt: Float) {
        t += dt
        gestures.update()
        if (animK < 1f) {
            animK = minOf(1f, animK + dt / 0.13f)
            if (animK >= 1f && falling < 0f && winning < 0f) pending?.let { pending = null; doMove(it.dx, it.dy) }
        }
        if (falling >= 0f && animK >= 1f) {
            falling += dt
            if (falling > 0.75f) { falling = -1f; restartLevel(message) }
        }
        if (winning >= 0f && animK >= 1f) winning = minOf(1f, winning + dt * 1.6f)
    }

    override fun onTouch(e: MotionEvent) = gestures.handle(e)

    override fun onKey(keyCode: Int): Boolean {
        val d = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> Dir.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> Dir.RIGHT
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W -> Dir.UP
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S -> Dir.DOWN
            else -> return false
        }
        input(d); return true
    }

    override fun hud() = listOf(
        HudItem("LEVEL", "${levelIndex + 1}/${pack.size}", Pal.CYAN),
        HudItem("MOVES", moves.toString(), if (moves <= par) Pal.WHITE else Pal.ORANGE),
        HudItem("PAR", par.toString(), Pal.YELLOW),
    )

    override fun actions() = listOf(
        GameAction("PREV", Icons.BACK, isEnabled = { !finished && levelIndex > 0 }) { switchLevel(levelIndex - 1) },
        GameAction("RESET", Icons.RESTART, isEnabled = { !finished && falling < 0f && moves > 0 }) {
            restartLevel("BACK TO THE START."); host.sfx(Sfx.BACK)
        },
        GameAction("NEXT", Icons.FORWARD, isEnabled = { !finished && levelIndex + 1 < track.unlocked }) { switchLevel(levelIndex + 1) },
    )

    override fun draw(c: Canvas) {
        val p = px
        view.drawTiles(c, level, state.bridges, p, t)
        when {
            falling >= 0f -> {
                val f = if (animK < 1f) 0f else falling / 0.75f
                view.drawBlock(c, animFrom, state.pos, animK, p, drop = f * f, alpha = (255 * (1f - f)).toInt().coerceIn(0, 255))
            }
            winning >= 0f -> view.drawBlock(c, animFrom, state.pos, animK, p, sink = winning, alpha = (255 * (1f - winning * 0.9f)).toInt())
            else -> view.drawBlock(c, animFrom, state.pos, animK, p)
        }
        val ts = maxOf(2f, floor(p * 0.7f))
        Gfx.paragraph(c, message, boardBox.left, boardBox.bottom + p * 3, boardBox.width(), ts,
            if (falling >= 0f) Pal.lighter(Pal.RED, 0.3f) else Pal.LGREY, Align.CENTER)
    }
}

/** Tutorial exercise: roll the block twice to stand it in the hole. */
private class RollDemo : TutorialDemo() {
    private val level = RollLevel(listOf("###...", "#S##G.", "###..."))
    private var state = level.startState
    private var from = state.pos
    private var k = 1f
    private var fall = -1f
    private val view = RollView()
    private val box = RectF()
    private var downX = 0f
    private var downY = 0f
    override val task = "TRY IT: SWIPE OR TAP TO ROLL. STAND THE BLOCK UP IN THE HOLE."

    override fun update(dt: Float) {
        if (k < 1f) k = minOf(1f, k + dt / 0.13f)
        if (fall >= 0f && k >= 1f) { fall += dt; if (fall > 0.7f) { fall = -1f; state = level.startState; from = state.pos } }
    }

    override fun draw(c: Canvas, box: RectF, p: Float, t: Float) {
        this.box.set(box)
        view.fit(level, box)
        view.drawTiles(c, level, state.bridges, p, t)
        val f = if (fall >= 0f && k >= 1f) fall / 0.7f else 0f
        view.drawBlock(c, from, state.pos, k, p, drop = f * f, sink = if (done) 0.7f else 0f, alpha = if (done) 90 else (255 * (1 - f)).toInt().coerceIn(0, 255))
    }

    override fun onTouch(e: MotionEvent, box: RectF) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
            MotionEvent.ACTION_UP -> {
                if (done || fall >= 0f || k < 1f) return
                var dx = e.x - downX; var dy = e.y - downY
                if (abs(dx) < view.cell * 0.25f && abs(dy) < view.cell * 0.25f) {
                    val f = RectF(); view.footprint(state.pos, f)
                    dx = e.x - f.centerX(); dy = e.y - f.centerY()
                }
                val d = if (abs(dx) > abs(dy)) (if (dx > 0) 0 else 1) else (if (dy > 0) 2 else 3)
                val (ns, ev) = level.move(state, RollLevel.DX[d], RollLevel.DY[d])
                from = state.pos; state = ns; k = 0f
                when (ev) {
                    RollEvent.FELL -> { fall = 0f; bad("IT FELL OFF THE EDGE - TRY AGAIN.") }
                    RollEvent.WON -> complete("PERFECT! UPRIGHT ON THE HOLE WINS THE LEVEL.")
                    RollEvent.MOVED -> good(if (ns.pos.o == Orient.STAND) "STANDING UP AGAIN." else "NOW IT LIES ACROSS TWO TILES.", Sfx.MOVE)
                }
            }
        }
    }
}

private val ART_GOAL = PixelArt(
    "..........",
    "...BBB....",
    "...bbb....",
    "...bbb....",
    "...bbb....",
    "...bbb....",
    "gggKKKgggg",
    "ggKKKKKggg",
    "gggggggggg",
    "dddddddddd",
)
private val ART_ROLL = PixelArt(
    "............",
    "BBB.........",
    "bbb.....BBBB",
    "bbb..C..bbbb",
    "bbb.CCC.bbbb",
    "bbb..C......",
    "bbb.........",
    "gggggggggggg",
    "dddddddddddd",
)
private val ART_FRAGILE = PixelArt(
    "..........",
    "..BBBBBB..",
    "..bbbbbb..",
    "OOOOOOOOOO",
    "OOrOOOOrOO",
    "OOOrOOOOOO",
    "rrrrrrrrrr",
)
private val ART_SWITCH = PixelArt(
    "............",
    "....CCC.....",
    "...CCCCC....",
    "...CCCCC....",
    "....CCC.....",
    "............",
    "MMMMCMMMMCMM",
    "mmmmCmmmmCmm",
    "MMMMCMMMMCMM",
    "............",
)
private val ART_HEAVY = PixelArt(
    "...BBB...",
    "...bbb...",
    "...bbb...",
    "...bbb...",
    "ggPgggPgg",
    "gggPgPggg",
    "ggggPgggg",
    "gggPgPggg",
    "ggPgggPgg",
)

val RollBlockGame = GameDef(
    id = "rollblock",
    title = "ROLL BLOCK",
    category = Category.PUZZLE,
    tagline = "Tip a tall block across floating tiles and drop it into the hole.",
    description = "A rolling block puzzle. The block is two tiles tall: roll it over its edges to move. Standing up it covers one tile, " +
        "lying down it covers two. Reach the hole standing upright - and don't let any part of it roll off the edge! " +
        "24 hand-made levels in three packs add fragile tiles, switches and bridges.",
    icon = PixelArt(
        "................",
        "......BBBB......",
        "......BBBB......",
        "......bbbb......",
        "......bbbb......",
        "......bbbb......",
        "......bbbb......",
        "......bbbb......",
        "..lllllllllll...",
        ".lggggggggggggl.",
        ".lgggggKKKgggg..",
        ".lggggKKKKKggg..",
        ".lgggggggggggg..",
        ".ddddddddddddd..",
        "................",
        "................",
    ),
    accent = RollView.BLOCK,
    music = Music.PUZZLE,
    scoreKind = ScoreKind.WINS,
    difficultyLabels = listOf("EASY", "NORMAL", "HARD"),
    tutorial = listOf(
        TutorialPage("OBJECTIVE", "Your block is twice as tall as it is wide. Roll it across the floating tiles and drop it into the dark HOLE. " +
            "It only drops in when it STANDS UPRIGHT exactly on the hole.", ART_GOAL),
        TutorialPage("ROLLING", "SWIPE in a direction, or TAP the side of the board you want to roll towards. The block tips over one edge each move. " +
            "An upright block falls flat onto the next two tiles; a flat block either stands back up or rolls sideways.", ART_ROLL,
            demo = { RollDemo() }),
        TutorialPage("DON'T FALL", "Every tile the block covers must be solid. If any part hangs over empty space the block falls and the level starts again. " +
            "Your moves are counted; PAR is the fewest moves possible.", Icons.CLOSE),
        TutorialPage("FRAGILE TILES", "ORANGE tiles are thin. The block can lie on them, but standing upright on an orange tile breaks it and the block falls through.", ART_FRAGILE),
        TutorialPage("SWITCHES & BRIDGES", "A ROUND switch is pressed when any part of the block lands on it. Each press opens - or closes again - every BRIDGE of the same color. " +
            "Closed bridges show as dashed outlines: you can't roll onto them.", ART_SWITCH),
        TutorialPage("X SWITCHES", "An X switch is heavier: it only clicks when the block STANDS UPRIGHT on it. Lying across it does nothing. " +
            "Watch out - a switch can close the bridge you are standing on!", ART_HEAVY),
        TutorialPage("LEVELS", "Clear a level to unlock the next. PREV and NEXT replay any unlocked level, RESET restarts the current one. " +
            "EASY teaches rolling, NORMAL adds orange tiles and round switches, HARD adds X switches.", Icons.STAR),
    ),
    achievements = listOf(
        AchievementDef("first_win", "HOLE IN ONE", "Clear a Roll Block level."),
        AchievementDef("perfect", "ON PAR", "Clear a level in the fewest possible moves."),
        AchievementDef("hard_win", "TIPPING POINT", "Clear a HARD level."),
        AchievementDef("pack_easy", "ROLLING START", "Clear every EASY level."),
        AchievementDef("pack_normal", "BRIDGE BUILDER", "Clear every NORMAL level."),
        AchievementDef("pack_hard", "MASTER ROLLER", "Clear every HARD level."),
    ),
    factory = { RollSession(it) },
)
