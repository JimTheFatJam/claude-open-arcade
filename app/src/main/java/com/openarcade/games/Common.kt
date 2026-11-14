package com.openarcade.games

import android.graphics.Canvas
import android.graphics.RectF
import com.openarcade.gfx.Gfx
import com.openarcade.gfx.Pal
import kotlin.math.floor

/** Fits a cols x rows grid of square cells into a rectangle, centred, with whole-pixel cells. */
class GridFit(var cols: Int, var rows: Int) {
    var cell = 0f; private set
    var ox = 0f; private set
    var oy = 0f; private set
    val bounds = RectF()

    fun fit(area: RectF, margin: Float = 0f, maxCell: Float = Float.MAX_VALUE) {
        val aw = area.width() - margin * 2
        val ah = area.height() - margin * 2
        cell = floor(minOf(aw / cols, ah / rows, maxCell)).coerceAtLeast(1f)
        ox = floor(area.centerX() - cell * cols / 2)
        oy = floor(area.centerY() - cell * rows / 2)
        bounds.set(ox, oy, ox + cell * cols, oy + cell * rows)
    }

    fun x(col: Int) = ox + col * cell
    fun y(row: Int) = oy + row * cell
    fun cx(col: Float) = ox + (col + 0.5f) * cell
    fun cy(row: Float) = oy + (row + 0.5f) * cell

    fun rect(col: Int, row: Int, out: RectF = tmp, inset: Float = 0f): RectF {
        out.set(x(col) + inset, y(row) + inset, x(col) + cell - inset, y(row) + cell - inset)
        return out
    }

    /** Returns col,row packed as col + row * cols, or -1 when outside. */
    fun cellAt(px: Float, py: Float): Int {
        if (px < ox || py < oy) return -1
        val c = ((px - ox) / cell).toInt()
        val r = ((py - oy) / cell).toInt()
        if (c !in 0 until cols || r !in 0 until rows) return -1
        return c + r * cols
    }

    companion object { private val tmp = RectF() }
}

/** Runs a computation (AI search, puzzle generation) off the UI thread. */
class Worker<T> {
    @Volatile private var result: Any? = NONE
    @Volatile var busy = false; private set
    private var thread: Thread? = null
    private var generation = 0

    fun run(task: () -> T) {
        val gen = ++generation
        busy = true
        result = NONE
        thread = Thread {
            val r = try { task() } catch (e: Throwable) { null }
            if (gen == generation) { result = r; busy = false }
        }.apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1; start() }
    }

    /** Returns the finished result once (then clears it), or null while busy / idle. */
    @Suppress("UNCHECKED_CAST")
    fun take(): T? {
        val r = result
        if (r === NONE || busy) return null
        result = NONE
        return r as T?
    }

    fun cancel() { generation++; busy = false; result = NONE }

    companion object { private val NONE = Any() }
}

/** A recessed board frame drawn around game boards for a consistent console look. */
fun drawBoardFrame(c: Canvas, r: RectF, p: Float, accent: Int) {
    Gfx.rect(c, r.left - p * 3, r.top - p * 3, r.right + p * 3, r.bottom + p * 3, Pal.BLACK)
    Gfx.rect(c, r.left - p * 2, r.top - p * 2, r.right + p * 2, r.bottom + p * 2, Pal.darker(accent, 0.45f))
    Gfx.rect(c, r.left - p, r.top - p, r.right + p, r.bottom + p, Pal.BLACK)
}

fun IntArray.toJsonArray(): org.json.JSONArray = org.json.JSONArray().also { a -> forEach { a.put(it) } }

fun org.json.JSONArray.toIntArray(): IntArray = IntArray(length()) { optInt(it) }

/**
 * Level-pack progress for level-based games, kept per difficulty in the game's own storage: the
 * level to play next, how many levels are unlocked, and the best result for every level.
 */
class LevelTrack(private val host: GameHost, val count: Int) {
    private val d = host.difficulty.ordinal

    /** Levels the player may choose from (always at least the first). */
    val unlocked: Int get() = host.getInt("unlocked_$d", 1).coerceIn(1, count)

    /** The level a new round starts on. */
    var current: Int
        get() = host.getInt("level_$d", 0).coerceIn(0, unlocked - 1)
        set(v) = host.putInt("level_$d", v.coerceIn(0, unlocked - 1))

    /** Number of different levels completed at least once. */
    val cleared: Int get() = (0 until count).count { best(it) > 0 }

    /** Best (lowest) move count for [level], or 0 when it was never completed. */
    fun best(level: Int): Int = host.getInt("best_${d}_$level", 0)

    /**
     * Records a completed level: keeps the best move count, unlocks the next level and makes it the
     * current one (wrapping to the first after the last). Returns true for a new best.
     */
    fun complete(level: Int, moves: Int): Boolean {
        val old = best(level)
        val newBest = old == 0 || moves < old
        if (newBest) host.putInt("best_${d}_$level", maxOf(1, moves))
        if (level + 2 > unlocked) host.putInt("unlocked_$d", minOf(count, level + 2))
        current = if (level + 1 < count) level + 1 else 0
        return newBest
    }
}
