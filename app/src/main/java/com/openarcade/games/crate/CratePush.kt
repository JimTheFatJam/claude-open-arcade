package com.openarcade.games.crate

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
import com.openarcade.games.GridFit
import com.openarcade.games.HudItem
import com.openarcade.games.LevelTrack
import com.openarcade.games.ScoreKind
import com.openarcade.games.TutorialDemo
import com.openarcade.games.TutorialPage
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
import kotlin.math.floor

/** Drawing for walls, floor, goals, crates and the worker; shared by the game and the tutorial demo. */
object CrateArt {
    private val WORKER = PixelArt(
        "..YYYY..",
        ".YYYYYY.",
        "..eeee..",
        "..eKeK..",
        "..eeee..",
        ".BBBBBB.",
        "eBBBBBBe",
        "..BBBB..",
        "..b..b..",
        ".mm..mm.",
    )

    fun drawLevel(c: Canvas, lv: CrateLevel, g: GridFit, p: Float, t: Float) {
        val cell = g.cell
        val r = RectF()
        for (i in 0 until lv.w * lv.h) {
            val x = i % lv.w; val y = i / lv.w
            g.rect(x, y, r)
            if (lv.wall[i]) {
                // only draw walls that touch the inside, so the outline hugs the level
                if ((0 until 4).none { d -> val n = lv.step(i, d); n >= 0 && lv.inside[n] && !lv.wall[n] } &&
                    !diagonalInside(lv, i)) continue
                Gfx.rect(c, r, Pal.DBROWN)
                val bh = floor(cell / 2)
                val mortar = Pal.darker(Pal.DBROWN, 0.35f)
                val k = maxOf(1f, floor(cell / 14f))
                Gfx.rect(c, r.left, r.top + bh - k / 2, r.right, r.top + bh + k / 2, mortar)
                Gfx.rect(c, r.left + cell * 0.5f, r.top, r.left + cell * 0.5f + k, r.top + bh, mortar)
                Gfx.rect(c, r.left + cell * 0.2f, r.top + bh, r.left + cell * 0.2f + k, r.bottom, mortar)
                Gfx.rect(c, r.left, r.top, r.right, r.top + k, Pal.BROWN)
            } else if (lv.inside[i]) {
                Gfx.rect(c, r, if ((x + y) % 2 == 0) Pal.BG1 else Pal.mix(Pal.BG1, Pal.BG2, 0.35f))
                if (lv.goal[i]) {
                    val d = cell * 0.22f
                    val k = maxOf(1f, floor(cell / 12f))
                    val pulse = (kotlin.math.sin(t * 3f + i) + 1f) / 2f
                    val col = Pal.mix(Pal.ORANGE, Pal.YELLOW, pulse)
                    Gfx.frame(c, RectF(r.left + d, r.top + d, r.right - d, r.bottom - d), k, col)
                    Gfx.rect(c, r.centerX() - k, r.centerY() - k, r.centerX() + k, r.centerY() + k, col)
                }
            }
        }
    }

    private fun diagonalInside(lv: CrateLevel, i: Int): Boolean {
        val x = i % lv.w; val y = i / lv.w
        for (dy in -1..1) for (dx in -1..1) {
            val nx = x + dx; val ny = y + dy
            if (nx in 0 until lv.w && ny in 0 until lv.h && lv.inside[ny * lv.w + nx] && !lv.wall[ny * lv.w + nx]) return true
        }
        return false
    }

    fun drawCrate(c: Canvas, r: RectF, onGoal: Boolean, stuck: Boolean, cell: Float) {
        val inset = maxOf(1f, floor(cell * 0.08f))
        val b = RectF(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset)
        val col = when { onGoal -> Pal.DGREEN; stuck -> Pal.DRED; else -> Pal.BROWN }
        val k = maxOf(1f, floor(cell / 12f))
        Gfx.rect(c, b.left + k, b.top + k, b.right + k, b.bottom + k, Pal.withAlpha(Pal.BLACK, 120))
        Gfx.bevel(c, b, col, k)
        val plank = Pal.darker(col, 0.3f)
        Gfx.frame(c, RectF(b.left + k, b.top + k, b.right - k, b.bottom - k), k, plank)
        Gfx.line(c, b.left + k * 2, b.top + k * 2, b.right - k * 2, b.bottom - k * 2, k * 1.5f, plank)
        Gfx.line(c, b.right - k * 2, b.top + k * 2, b.left + k * 2, b.bottom - k * 2, k * 1.5f, plank)
        if (onGoal) Gfx.rect(c, b.centerX() - k, b.centerY() - k, b.centerX() + k, b.centerY() + k, Pal.YELLOW)
    }

    fun drawWorker(c: Canvas, r: RectF, bob: Float) {
        val box = RectF(r.left, r.top + bob, r.right, r.bottom + bob)
        WORKER.drawFit(c, box)
    }
}

class CrateSession(host: GameHost) : GameSession(host) {
    private val pack = CrateLevels.packs[host.difficulty.ordinal]
    private val track = LevelTrack(host, pack.size)
    private var levelIndex = track.current
    private var level = pack[levelIndex]
    private var state = CrateState(level)
    private var undos = 0
    private var message = ""
    private var stuck: Set<Int> = emptySet()
    private var t = 0f

    // animation: player (and pushed crate) slide from their previous cell
    private var fromPlayer = state.player
    private var movedBox = -1
    private var fromBox = -1
    private var animK = 1f
    private val queue = ArrayDeque<Int>()

    private val grid = GridFit(level.w, level.h)
    private val boardBox = RectF()
    private val gestures = Gestures { px * 3 }.apply {
        continuousSwipe = true
        onSwipe = { d -> queue.clear(); queue.add(dirIndex(d)) }
        onTap = { x, y -> tap(x, y) }
    }

    // ---- hooks for automated tests
    internal val stateForTest get() = state
    internal fun moveForTest(d: Int) { val r = state.move(d); afterMove(r); animK = 1f }
    internal fun undoForTest() = undo()

    override fun start(saved: JSONObject?) {
        if (saved != null) {
            val li = saved.getInt("level")
            require(li in pack.indices)
            load(li)
            val boxes = saved.getJSONArray("boxes").toIntArray()
            val player = saved.getInt("player")
            require(boxes.size == level.startBoxes.size && boxes.all { level.floor(it) } && level.floor(player) && player !in boxes)
            state.restore(player, boxes, saved.getInt("moves"), saved.getInt("pushes"))
            undos = saved.optInt("undos")
            fromPlayer = state.player
            stuck = state.stuckCrates()
        } else load(levelIndex)
        message = if (stuck.isEmpty()) "PUSH EVERY CRATE ONTO A GOAL." else stuckText()
    }

    private fun load(i: Int) {
        levelIndex = i; level = pack[i]
        state = CrateState(level); undos = 0; stuck = emptySet()
        fromPlayer = state.player; movedBox = -1; animK = 1f; queue.clear()
        grid.cols = level.w; grid.rows = level.h
        onLayout()
    }

    override fun save(): JSONObject = JSONObject().put("level", levelIndex).put("player", state.player)
        .put("boxes", state.boxes.toJsonArray()).put("moves", state.moves).put("pushes", state.pushes).put("undos", undos)

    override fun onLayout() {
        val textH = PixelFont.LINE * maxOf(2f, floor(px * 0.7f)) * 2 + px * 4
        boardBox.set(area.left, area.top, area.right, area.bottom - textH)
        grid.fit(boardBox, px * 2)
    }

    private fun dirIndex(d: Dir) = when (d) { Dir.RIGHT -> 0; Dir.LEFT -> 1; Dir.DOWN -> 2; Dir.UP -> 3 }

    private fun tap(x: Float, y: Float) {
        if (finished) return
        val cell = grid.cellAt(x, y)
        if (cell < 0) return
        queue.clear()
        val pd = (0 until 4).firstOrNull { level.step(state.player, it) == cell }
        if (pd != null) { queue.add(pd); return }
        val path = state.pathTo(cell)
        if (path == null) { host.sfx(Sfx.ERROR); return }
        queue.addAll(path)
    }

    private fun afterMove(r: CrateMove) {
        when (r) {
            CrateMove.BLOCKED -> { host.sfx(Sfx.WALL); queue.clear(); return }
            CrateMove.WALKED -> host.sfx(Sfx.TICK)
            CrateMove.PUSHED -> host.sfx(Sfx.MOVE)
        }
        val was = stuck
        stuck = state.stuckCrates()
        if (r == CrateMove.PUSHED) {
            val b = state.boxes[movedBox.coerceAtLeast(0)]
            if (level.goal[b]) host.sfx(Sfx.PLACE)
        }
        message = when {
            stuck.isNotEmpty() -> stuckText()
            was.isNotEmpty() -> "FREE AGAIN!"
            else -> "${state.boxes.count { level.goal[it] }} OF ${state.boxes.size} CRATES ON GOALS."
        }
        if (stuck.isNotEmpty() && was.isEmpty()) { host.sfx(Sfx.ERROR); host.vibrate(40) }
        if (state.solved) completed()
    }

    private fun stuckText() = "A CRATE IS STUCK AND CAN NEVER REACH A GOAL. TAP UNDO!"

    private fun step(d: Int) {
        val before = state.boxes.copyOf()
        fromPlayer = state.player
        val r = state.move(d)
        movedBox = if (r == CrateMove.PUSHED) before.indices.first { before[it] != state.boxes[it] } else -1
        fromBox = if (movedBox >= 0) before[movedBox] else -1
        if (r != CrateMove.BLOCKED) animK = 0f
        afterMove(r)
    }

    private fun completed() {
        queue.clear()
        val newBest = track.complete(levelIndex, state.moves)
        host.unlock("first_win")
        if (host.difficulty == Difficulty.HARD) host.unlock("hard_win")
        if (undos == 0) host.unlock("clean")
        if (track.cleared == pack.size) host.unlock("pack_${host.difficulty.name.lowercase()}")
        message = "ALL CRATES ARE HOME!"
        win(detail = "LEVEL ${levelIndex + 1}  MOVES ${state.moves}  PUSHES ${state.pushes}" + (if (newBest) "  BEST!" else ""),
            title = "LEVEL CLEAR!", delay = 1.1f)
    }

    private fun undo() {
        queue.clear()
        if (!state.undo()) return
        undos++
        animK = 1f; fromPlayer = state.player; movedBox = -1
        stuck = state.stuckCrates()
        message = if (stuck.isEmpty()) "UNDONE. ${state.boxes.count { level.goal[it] }} OF ${state.boxes.size} ON GOALS." else stuckText()
        host.sfx(Sfx.BACK)
    }

    private fun reset() {
        queue.clear(); state.reset(); undos++
        animK = 1f; fromPlayer = state.player; movedBox = -1; stuck = emptySet()
        message = "LEVEL RESET."
        host.sfx(Sfx.BACK)
    }

    private fun switchLevel(i: Int) {
        if (i !in 0 until track.unlocked || i == levelIndex) return
        track.current = i
        load(i)
        message = "LEVEL ${i + 1}: PUSH EVERY CRATE ONTO A GOAL."
        host.sfx(Sfx.SELECT)
    }

    override fun update(dt: Float) {
        t += dt
        gestures.update()
        if (animK < 1f) animK = minOf(1f, animK + dt / 0.085f)
        if (animK >= 1f && !finished) queue.removeFirstOrNull()?.let { step(it) }
    }

    override fun onTouch(e: MotionEvent) = gestures.handle(e)

    override fun onKey(keyCode: Int): Boolean {
        val d = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> 0
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> 1
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S -> 2
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W -> 3
            KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_DEL -> { undo(); return true }
            else -> return false
        }
        if (!finished) { queue.clear(); queue.add(d) }
        return true
    }

    override fun hud() = listOf(
        HudItem("LEVEL", "${levelIndex + 1}/${pack.size}", Pal.ORANGE),
        HudItem("MOVES", state.moves.toString()),
        HudItem("PUSHES", state.pushes.toString(), Pal.YELLOW),
    )

    override fun actions() = listOf(
        GameAction("UNDO", Icons.UNDO, isEnabled = { !finished && state.canUndo }) { undo() },
        GameAction("RESET", Icons.RESTART, isEnabled = { !finished && state.moves > 0 }) { reset() },
        GameAction("PREV", Icons.BACK, isEnabled = { !finished && levelIndex > 0 }) { switchLevel(levelIndex - 1) },
        GameAction("NEXT", Icons.FORWARD, isEnabled = { !finished && levelIndex + 1 < track.unlocked }) { switchLevel(levelIndex + 1) },
    )

    private fun lerpRect(from: Int, to: Int, k: Float, out: RectF) {
        val e = k * (2 - k)
        val fx = (from % level.w).toFloat(); val fy = (from / level.w).toFloat()
        val tx = (to % level.w).toFloat(); val ty = (to / level.w).toFloat()
        val x = fx + (tx - fx) * e; val y = fy + (ty - fy) * e
        out.set(grid.ox + x * grid.cell, grid.oy + y * grid.cell, grid.ox + (x + 1) * grid.cell, grid.oy + (y + 1) * grid.cell)
    }

    override fun draw(c: Canvas) {
        val p = px
        CrateArt.drawLevel(c, level, grid, p, t)
        val r = RectF()
        for ((k, b) in state.boxes.withIndex()) {
            if (k == movedBox && animK < 1f) lerpRect(fromBox, b, animK, r) else grid.rect(b % level.w, b / level.w, r)
            CrateArt.drawCrate(c, RectF(r), level.goal[b] && (k != movedBox || animK >= 1f), b in stuck, grid.cell)
        }
        lerpRect(if (animK < 1f) fromPlayer else state.player, state.player, animK, r)
        val bob = if (animK < 1f) -grid.cell * 0.06f else 0f
        CrateArt.drawWorker(c, RectF(r), bob)
        val ts = maxOf(2f, floor(p * 0.7f))
        Gfx.paragraph(c, message, area.left + p * 2, grid.bounds.bottom + p * 4, area.width() - p * 4, ts,
            if (stuck.isNotEmpty()) Pal.lighter(Pal.RED, 0.3f) else Pal.LGREY, Align.CENTER)
    }
}

/** Tutorial exercise: push one crate onto its goal - and see that two crates in a row won't move. */
private class CrateDemo : TutorialDemo() {
    private val level = CrateLevel(listOf("#######", "#@ \$ .#", "#######"))
    private val state = CrateState(level)
    private val grid = GridFit(level.w, level.h)
    private var downX = 0f
    private var downY = 0f
    override val task = "TRY IT: SWIPE RIGHT (OR TAP) TO WALK AND PUSH THE CRATE ONTO THE GOAL."

    override fun draw(c: Canvas, box: RectF, p: Float, t: Float) {
        grid.fit(box, p)
        CrateArt.drawLevel(c, level, grid, p, t)
        val r = RectF()
        for (b in state.boxes) { grid.rect(b % level.w, b / level.w, r); CrateArt.drawCrate(c, RectF(r), level.goal[b], false, grid.cell) }
        grid.rect(state.player % level.w, state.player / level.w, r)
        CrateArt.drawWorker(c, RectF(r), 0f)
    }

    override fun onTouch(e: MotionEvent, box: RectF) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
            MotionEvent.ACTION_UP -> {
                if (done) return
                var dx = e.x - downX; var dy = e.y - downY
                if (abs(dx) < grid.cell * 0.3f && abs(dy) < grid.cell * 0.3f) {
                    dx = e.x - grid.cx((state.player % level.w).toFloat()); dy = e.y - grid.cy((state.player / level.w).toFloat())
                }
                val d = if (abs(dx) > abs(dy)) (if (dx > 0) 0 else 1) else (if (dy > 0) 2 else 3)
                when (state.move(d)) {
                    CrateMove.BLOCKED -> bad("A WALL IS IN THE WAY.")
                    CrateMove.WALKED -> good("STEP. WALK INTO THE CRATE TO PUSH IT.", Sfx.TICK)
                    CrateMove.PUSHED -> if (state.solved) complete("DONE! A CRATE ON A GOAL TURNS GREEN.") else good("PUSHED! KEEP GOING.", Sfx.MOVE)
                }
            }
        }
    }
}

private val ART_PUSH = PixelArt(
    "............",
    "..YY........",
    ".eeee.MMMM..",
    ".eKeK.MmmM..",
    ".BBBBBMmmM..",
    "eBBBBBMMMM..",
    ".b..b.......",
    "..........Y.",
    "........YYYY",
)
private val ART_TWO = PixelArt(
    "..............",
    "..YY..........",
    ".eeee.MMMMMMMM",
    ".eKeK.MmmMMmmM",
    ".BBBBBMmmMMmmM",
    "eBBBBBMMMMMMMM",
    ".b..b...RR.RR.",
    "........RR.RR.",
)
private val ART_CORNER = PixelArt(
    "rrrrrrrr",
    "rrrrrrrr",
    "rrrRRRRR",
    "rrrRrrrR",
    "rrrRrrrR",
    "rrrRRRRR",
    "rrr.....",
    "rrr.....",
)

val CratePushGame = GameDef(
    id = "cratepush",
    title = "CRATE PUSH",
    category = Category.PUZZLE,
    tagline = "Push every crate onto a goal - but you can never pull!",
    description = "A classic warehouse puzzle. Walk around the room and push the crates onto the glowing goals. You can only push, " +
        "one crate at a time, so think before you shove one into a corner. Unlimited UNDO, 24 levels in three packs.",
    icon = PixelArt(
        "................",
        ".mmmmmmmmmmmmmm.",
        ".m............m.",
        ".m..YY........m.",
        ".m.eeee.......m.",
        ".m.eKeK.MMMMM.m.",
        ".m.BBBBBMmmmM.m.",
        ".m.eBBBBMmmmM.m.",
        ".m..b.b.MmmmM.m.",
        ".m......MMMMM.m.",
        ".m............m.",
        ".m......OOOO..m.",
        ".m......O..O..m.",
        ".m......OOOO..m.",
        ".mmmmmmmmmmmmmm.",
        "................",
    ),
    accent = Pal.ORANGE,
    music = Music.PUZZLE,
    scoreKind = ScoreKind.WINS,
    tutorial = listOf(
        TutorialPage("OBJECTIVE", "Push every crate onto a glowing GOAL square. The level is complete the moment every goal holds a crate - " +
            "crates on goals turn green.", ART_PUSH),
        TutorialPage("MOVING", "SWIPE to take a step (keep your finger moving to keep walking), or TAP any floor square to walk there. " +
            "Walking into a crate pushes it one square.", ART_PUSH, demo = { CrateDemo() }),
        TutorialPage("PUSH, NEVER PULL", "You can only PUSH. A crate can't move into a wall or into another crate, and you are not strong enough " +
            "to push two crates in a row.", ART_TWO),
        TutorialPage("STUCK CRATES", "A crate pushed into a corner can never come out again. When a crate can no longer reach any goal it turns RED - " +
            "tap UNDO to take moves back. Undo is unlimited.", ART_CORNER),
        TutorialPage("LEVELS", "MOVES counts every step, PUSHES counts crate pushes. Clear a level to unlock the next; PREV and NEXT replay unlocked levels. " +
            "Your best move count for each level is saved.", Icons.STAR),
    ),
    achievements = listOf(
        AchievementDef("first_win", "WAREHOUSE WORKER", "Clear a Crate Push level."),
        AchievementDef("clean", "NO REGRETS", "Clear a level without UNDO or RESET."),
        AchievementDef("hard_win", "HEAVY LIFTER", "Clear a HARD level."),
        AchievementDef("pack_easy", "STOCK BOY", "Clear every EASY level."),
        AchievementDef("pack_normal", "FORKLIFT", "Clear every NORMAL level."),
        AchievementDef("pack_hard", "WAREHOUSE KING", "Clear every HARD level."),
    ),
    factory = { CrateSession(it) },
)
