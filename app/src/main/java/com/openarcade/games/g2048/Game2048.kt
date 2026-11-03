package com.openarcade.games.g2048

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
import com.openarcade.games.ScoreKind
import com.openarcade.games.TutorialPage
import com.openarcade.games.drawBoardFrame
import com.openarcade.games.toIntArray
import com.openarcade.games.toJsonArray
import com.openarcade.gfx.Gfx
import com.openarcade.gfx.Icons
import com.openarcade.gfx.Pal
import com.openarcade.gfx.PixelArt
import com.openarcade.gfx.PixelFont
import org.json.JSONObject
import kotlin.random.Random

/** Pure 2048 rules on an n x n grid of tile values (0 = empty). */
class Board2048(val size: Int) {
    var cells = IntArray(size * size)
    var score = 0

    /** Move result: for every destination index, the source indices that slid into it. */
    class Move(val moved: Boolean, val gained: Int, val sources: Map<Int, List<Int>>, val merged: Set<Int>)

    fun move(d: Dir): Move {
        val sources = HashMap<Int, List<Int>>()
        val merged = HashSet<Int>()
        var moved = false
        var gained = 0
        val next = IntArray(size * size)
        for (line in 0 until size) {
            // indices of this line ordered from the edge we are moving toward
            val idx = IntArray(size) { k ->
                when (d) {
                    Dir.LEFT -> line * size + k
                    Dir.RIGHT -> line * size + (size - 1 - k)
                    Dir.UP -> k * size + line
                    Dir.DOWN -> (size - 1 - k) * size + line
                }
            }
            var out = 0
            var k = 0
            val tiles = idx.filter { cells[it] != 0 }
            while (k < tiles.size) {
                val a = tiles[k]
                if (k + 1 < tiles.size && cells[tiles[k + 1]] == cells[a]) {
                    val v = cells[a] * 2
                    next[idx[out]] = v
                    sources[idx[out]] = listOf(a, tiles[k + 1])
                    merged.add(idx[out])
                    gained += v
                    k += 2
                } else {
                    next[idx[out]] = cells[a]
                    sources[idx[out]] = listOf(a)
                    k += 1
                }
                out++
            }
        }
        if (!next.contentEquals(cells)) moved = true
        if (moved) { cells = next; score += gained }
        return Move(moved, gained, sources, merged)
    }

    fun spawn(rnd: Random): Int {
        val empty = cells.indices.filter { cells[it] == 0 }
        if (empty.isEmpty()) return -1
        val i = empty[rnd.nextInt(empty.size)]
        cells[i] = if (rnd.nextFloat() < 0.9f) 2 else 4
        return i
    }

    fun canMove(): Boolean {
        for (i in cells.indices) {
            if (cells[i] == 0) return true
            val c = i % size
            val r = i / size
            if (c < size - 1 && cells[i] == cells[i + 1]) return true
            if (r < size - 1 && cells[i] == cells[i + size]) return true
        }
        return false
    }

    fun maxTile() = cells.maxOrNull() ?: 0
}

class Game2048Session(host: GameHost) : GameSession(host) {
    private val size = when (host.difficulty) { Difficulty.EASY -> 5; Difficulty.NORMAL -> 4; Difficulty.HARD -> 3 }
    private val target = if (size == 3) 256 else 2048
    private val board = Board2048(size)
    private val grid = GridFit(size, size)
    private var undo: Pair<IntArray, Int>? = null
    private var undosLeft = 3
    private var reached = false
    private val gestures = Gestures { px * 3 }

    // animation: per destination cell, where its tile(s) came from
    private var animT = 1f
    private var lastSources: Map<Int, List<Int>> = emptyMap()
    private var lastMerged: Set<Int> = emptySet()
    private var spawned = -1
    private var prevCells = IntArray(size * size)

    init {
        gestures.onSwipe = { slide(it) }
    }

    override fun start(saved: JSONObject?) {
        if (saved != null) {
            board.cells = saved.getJSONArray("cells").toIntArray()
            board.score = saved.getInt("score")
            undosLeft = saved.optInt("undos", 3)
            reached = saved.optBoolean("reached")
        } else {
            board.spawn(host.rng); board.spawn(host.rng)
        }
        prevCells = board.cells.copyOf()
    }

    override fun save(): JSONObject = JSONObject().put("cells", board.cells.toJsonArray()).put("score", board.score)
        .put("undos", undosLeft).put("reached", reached)

    override fun onLayout() = grid.fit(area, px * 4)

    private fun slide(d: Dir) {
        if (finished) return
        val before = board.cells.copyOf()
        val beforeScore = board.score
        val m = board.move(d)
        if (!m.moved) { host.sfx(Sfx.WALL); return }
        undo = before to beforeScore
        prevCells = before
        lastSources = m.sources
        lastMerged = m.merged
        spawned = board.spawn(host.rng)
        animT = 0f
        host.sfx(if (m.merged.isNotEmpty()) Sfx.MERGE else Sfx.MOVE)
        val mx = board.maxTile()
        if (mx >= 512) host.unlock("tile_512")
        if (mx >= 2048) host.unlock("tile_2048")
        if (board.score >= 10000) host.unlock("score_10k")
        if (!reached && mx >= target) {
            reached = true
            host.sfx(Sfx.LEVELUP)
            host.toast("$target REACHED! KEEP GOING!")
        }
        if (!board.canMove()) {
            if (reached) win(board.score, "NO MORE MOVES - TOP TILE ${board.maxTile()}", 1.2f, "WELL DONE!")
            else lose(board.score, "NO MORE MOVES - TOP TILE ${board.maxTile()}", 1.2f)
        }
    }

    private fun doUndo() {
        val u = undo ?: return
        if (undosLeft <= 0 || finished) return
        board.cells = u.first.copyOf(); board.score = u.second
        undo = null; undosLeft--
        animT = 1f; spawned = -1
        host.sfx(Sfx.BACK)
    }

    override fun update(dt: Float) { if (animT < 1f) animT = minOf(1f, animT + dt / 0.12f) }

    override fun onTouch(e: MotionEvent) = gestures.handle(e)

    override fun onKey(keyCode: Int): Boolean {
        val d = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> Dir.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> Dir.RIGHT
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W -> Dir.UP
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S -> Dir.DOWN
            else -> return false
        }
        slide(d); return true
    }

    override fun hud() = listOf(
        HudItem("SCORE", board.score.toString(), Pal.YELLOW),
        HudItem("BEST", maxOf(board.score, host.bestScore()).toString()),
        HudItem("GOAL", target.toString(), if (reached) Pal.GREEN else Pal.LGREY),
    )

    override fun actions() = listOf(
        GameAction("UNDO", Icons.UNDO, isEnabled = { undo != null && undosLeft > 0 && !finished }) { doUndo() },
    )

    override fun draw(c: Canvas) {
        val p = px
        val cell = grid.cell
        val gap = maxOf(p, (cell * 0.08f).toInt().toFloat())
        drawBoardFrame(c, grid.bounds, p, Pal.ORANGE)
        Gfx.rect(c, grid.bounds, Pal.BG1)
        val r = RectF()
        for (i in 0 until size * size) {
            grid.rect(i % size, i / size, r, gap / 2)
            Gfx.rect(c, r, Pal.BG2)
        }
        val e = 1f - (1f - animT) * (1f - animT)
        for (i in 0 until size * size) {
            val v = board.cells[i]
            if (v == 0) continue
            val srcs = lastSources[i]
            if (animT < 1f && srcs != null) {
                // slide each source tile toward its destination
                for (s in srcs) {
                    val sx = grid.x(s % size) + (grid.x(i % size) - grid.x(s % size)) * e
                    val sy = grid.y(s / size) + (grid.y(i / size) - grid.y(s / size)) * e
                    r.set(sx + gap / 2, sy + gap / 2, sx + cell - gap / 2, sy + cell - gap / 2)
                    drawTile(c, r, prevCells[s], cell)
                }
                continue
            }
            grid.rect(i % size, i / size, r, gap / 2)
            var scale = 1f
            if (i == spawned && animT < 1f) scale = 0f
            else if (i == spawned && animT >= 1f) scale = 1f
            if (i in lastMerged && animT >= 1f) scale = 1f
            if (scale < 1f) continue
            drawTile(c, r, v, cell)
        }
    }

    private fun drawTile(c: Canvas, r: RectF, v: Int, cell: Float) {
        val col = tileColor(v)
        Gfx.bevel(c, r, col, maxOf(1f, (cell / 14f).toInt().toFloat()))
        val txt = v.toString()
        val maxSize = (cell * 0.42f / PixelFont.GLYPH_H)
        val s = Gfx.fitSize(txt, r.width() * 0.8f, maxSize)
        Gfx.textMid(c, txt, r.centerX(), r.centerY(), s, if (v <= 4) Pal.WHITE else Pal.BLACK, shadow = if (v <= 4) Pal.BLACK else Pal.withAlpha(Pal.WHITE, 90))
    }

    private fun tileColor(v: Int): Int = when (v) {
        2 -> Pal.DGREY
        4 -> Pal.GREY
        8 -> Pal.CYAN
        16 -> Pal.GREEN
        32 -> Pal.YELLOW
        64 -> Pal.ORANGE
        128 -> Pal.RED
        256 -> Pal.PINK
        512 -> Pal.PURPLE
        1024 -> Pal.lighter(Pal.BLUE, 0.2f)
        2048 -> Pal.lighter(Pal.YELLOW, 0.5f)
        else -> Pal.WHITE
    }
}

val Game2048 = GameDef(
    id = "2048",
    title = "2048",
    category = Category.PUZZLE,
    tagline = "Slide and merge matching tiles to build the 2048 tile.",
    description = "Swipe to slide every tile on the board. Two tiles with the same number merge into one with their sum. " +
        "Each swipe adds a new tile. Reach the goal tile and keep going for a high score before the board fills up.",
    icon = PixelArt(
        "................",
        ".CCCCCCCGGGGGGG.",
        ".CCkkkCCGGkkkGG.",
        ".CCCCkCCGGkGkGG.",
        ".CCkkkCCGGkGkGG.",
        ".CCkCCCCGGkGkGG.",
        ".CCkkkCCGGkkkGG.",
        ".CCCCCCCGGGGGGG.",
        ".YYYYYYYOOOOOOO.",
        ".YYkYkYYOOkkkOO.",
        ".YYkYkYYOOkOkOO.",
        ".YYkkkYYOOkkkOO.",
        ".YYYYkYYOOkOkOO.",
        ".YYYYkYYOOkkkOO.",
        ".YYYYYYYOOOOOOO.",
        "................",
    ),
    accent = Pal.ORANGE,
    music = Music.PUZZLE,
    scoreKind = ScoreKind.HIGH_SCORE,
    difficultyLabels = listOf("5X5 EASY", "4X4", "3X3 HARD"),
    tutorial = listOf(
        TutorialPage("OBJECTIVE", "Merge tiles to build the goal tile: 2048 (or 256 on the tiny 3x3 board). After reaching it you can keep playing for a higher score!",
            PixelArt(
                "dddddddddd",
                "dkkkkkkkkd",
                "dkCCkkYYkd",
                "dkCCkkYYkd",
                "dkkkkkkkkd",
                "dkGGkkOOkd",
                "dkGGkkOOkd",
                "dkkkkkkkkd",
                "dddddddddd",
            )),
        TutorialPage("CONTROLS", "SWIPE up, down, left or right. Every tile slides as far as it can in that direction. Keyboard arrows work too.", Icons.SWIPE),
        TutorialPage("MERGING", "When two tiles with the same number collide they merge: 2+2 makes 4, 4+4 makes 8 and so on. The merged value is added to your score. A new 2 or 4 appears after every move.",
            PixelArt(
                "...........",
                ".CC.CC..GG.",
                ".CC.CC..GG.",
                "......W....",
                ".....WWW...",
                "......W....",
                "...........",
            )),
        TutorialPage("GAME OVER", "The game ends when the board is full and no merges are possible. You have 3 UNDOs per game - use them wisely!", Icons.UNDO),
        TutorialPage("TIPS", "Keep your biggest tile in a corner and build a chain of decreasing tiles next to it. Avoid swiping in the direction that pulls it out of the corner.", Icons.BULB),
    ),
    achievements = listOf(
        AchievementDef("tile_512", "HALFWAY THERE", "Create a 512 tile."),
        AchievementDef("tile_2048", "2048!", "Create a 2048 tile."),
        AchievementDef("score_10k", "BIG NUMBERS", "Score 10,000 points in one game."),
    ),
    factory = { Game2048Session(it) },
)
