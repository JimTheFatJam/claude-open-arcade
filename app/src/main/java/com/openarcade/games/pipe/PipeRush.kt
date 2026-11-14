package com.openarcade.games.pipe

import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
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
import com.openarcade.games.LevelTrack
import com.openarcade.games.ScoreKind
import com.openarcade.games.TutorialDemo
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
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sin

/** Pipe, water and tile drawing shared by the game and its tutorial demo. */
object PipeArt {
    val WATER = 0xFF3AB8FF.toInt()

    fun tile(c: Canvas, r: RectF, cell: Float) {
        val g = maxOf(1f, floor(cell * 0.03f))
        Gfx.rect(c, r.left + g, r.top + g, r.right - g, r.bottom - g, Pal.mix(Pal.BG1, Pal.BG2, 0.4f))
    }

    private fun arm(c: Canvas, r: RectF, d: Int, half: Float, len: Float, color: Int) {
        // a bar from the centre towards side d, [len] = 0..1 of the way to the edge
        val cx = r.centerX(); val cy = r.centerY(); val ext = r.width() / 2 * len
        when (d) {
            0 -> Gfx.rect(c, cx - half, cy - ext, cx + half, cy + half, color)
            1 -> Gfx.rect(c, cx - half, cy - half, cx + ext, cy + half, color)
            2 -> Gfx.rect(c, cx - half, cy - half, cx + half, cy + ext, color)
            else -> Gfx.rect(c, cx - ext, cy - half, cx + half, cy + half, color)
        }
    }

    /** Draws a pipe with openings [mask]. [fixed] pipes are darker and bolted down. */
    fun pipe(c: Canvas, r: RectF, mask: Int, cell: Float, fixed: Boolean, alpha: Int = 255) {
        val outer = floor(cell * 0.19f)
        val inner = floor(cell * 0.12f)
        val metal = Pal.withAlpha(if (fixed) Pal.GREY else Pal.LGREY, alpha)
        val edge = Pal.withAlpha(Pal.BLACK, alpha)
        for (d in 0 until 4) if (mask and (1 shl d) != 0) arm(c, r, d, outer + maxOf(1f, cell / 24f), 1f, edge)
        for (d in 0 until 4) if (mask and (1 shl d) != 0) arm(c, r, d, outer, 1f, metal)
        for (d in 0 until 4) if (mask and (1 shl d) != 0) arm(c, r, d, inner, 1f, Pal.withAlpha(Pal.DGREY, alpha))
        // flanges at the openings
        val fl = floor(cell * 0.08f)
        for (d in 0 until 4) if (mask and (1 shl d) != 0) {
            val cx = r.centerX(); val cy = r.centerY(); val w = outer + fl
            when (d) {
                0 -> Gfx.rect(c, cx - w, r.top, cx + w, r.top + fl, metal)
                1 -> Gfx.rect(c, r.right - fl, cy - w, r.right, cy + w, metal)
                2 -> Gfx.rect(c, cx - w, r.bottom - fl, cx + w, r.bottom, metal)
                else -> Gfx.rect(c, r.left, cy - w, r.left + fl, cy + w, metal)
            }
        }
        if (fixed) {
            val b = maxOf(1f, floor(cell / 12f))
            for ((bx, by) in listOf(0.18f to 0.18f, 0.82f to 0.18f, 0.18f to 0.82f, 0.82f to 0.82f))
                Gfx.rect(c, r.left + r.width() * bx - b, r.top + r.height() * by - b, r.left + r.width() * bx + b, r.top + r.height() * by + b, Pal.DGREY)
        }
    }

    /** Water in a pipe: whole arms for [arms], plus a partial front entering at [entry] with [progress]. */
    fun water(c: Canvas, r: RectF, arms: Int, cell: Float) {
        val inner = floor(cell * 0.12f)
        for (d in 0 until 4) if (arms and (1 shl d) != 0) arm(c, r, d, inner, 1f, WATER)
    }

    fun front(c: Canvas, r: RectF, mask: Int, entry: Int, progress: Float, cell: Float) {
        val inner = floor(cell * 0.12f)
        if (entry >= 0) {
            // entry half: edge -> centre
            val k = (progress / 0.5f).coerceIn(0f, 1f)
            val cx = r.centerX(); val cy = r.centerY(); val half = r.width() / 2
            when (entry) {
                0 -> Gfx.rect(c, cx - inner, r.top, cx + inner, r.top + half * k, WATER)
                1 -> Gfx.rect(c, r.right - half * k, cy - inner, r.right, cy + inner, WATER)
                2 -> Gfx.rect(c, cx - inner, r.bottom - half * k, cx + inner, r.bottom, WATER)
                else -> Gfx.rect(c, r.left, cy - inner, r.left + half * k, cy + inner, WATER)
            }
        }
        if (progress > 0.5f) {
            val k = ((progress - 0.5f) / 0.5f).coerceIn(0f, 1f)
            val exits = if (entry < 0) mask else if (Integer.bitCount(mask) == 4) 1 shl Pipes.opposite(entry) else mask and (1 shl entry).inv()
            for (d in 0 until 4) if (exits and (1 shl d) != 0) arm(c, r, d, inner, k, WATER)
        }
    }

    fun rock(c: Canvas, r: RectF, cell: Float) {
        val p = maxOf(1f, cell / 12f)
        Gfx.disc(c, r.centerX(), r.centerY() + cell * 0.05f, cell * 0.36f, Pal.DGREY, p)
        Gfx.disc(c, r.centerX() - cell * 0.04f, r.centerY(), cell * 0.3f, Pal.GREY, p)
        Gfx.rect(c, r.centerX() - cell * 0.18f, r.centerY() - cell * 0.16f, r.centerX() - cell * 0.06f, r.centerY() - cell * 0.08f, Pal.LGREY)
    }

    /** The water tank (source) or the drain, each with its single opening. */
    fun endpoint(c: Canvas, r: RectF, mask: Int, cell: Float, source: Boolean, full: Boolean, t: Float) {
        pipe(c, r, mask, cell, true)
        val inset = cell * 0.16f
        val box = RectF(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset)
        val col = if (source) Pal.DBLUE else Pal.DGREEN
        Gfx.bevel(c, box, col, maxOf(1f, floor(cell / 12f)))
        if (source || full) {
            val pulse = (sin(t * 5f) + 1f) / 2f
            val w = RectF(box.left + cell * 0.1f, box.top + cell * 0.1f, box.right - cell * 0.1f, box.bottom - cell * 0.1f)
            Gfx.rect(c, w, Pal.mix(WATER, Pal.CYAN, if (full) pulse else 0f))
        } else {
            val k = maxOf(1f, floor(cell / 12f))
            for (j in 1..3) Gfx.rect(c, box.left + k * 2, box.top + box.height() * j / 4f, box.right - k * 2, box.top + box.height() * j / 4f + k, Pal.BLACK)
        }
        // label sized to sit inside the tank / drain box
        val label = if (source) "IN" else "OUT"
        val ts = floor(Gfx.fitSize(label, box.width() * 0.8f, cell / 14f))
        if (ts >= 1f) Gfx.textMid(c, label, r.centerX(), r.centerY(), ts, Pal.WHITE)
    }

    fun queuePiece(c: Canvas, r: RectF, kind: Int, rot: Int, cell: Float) {
        tile(c, r, cell)
        pipe(c, r, Pipes.mask(kind, rot), cell, false)
    }
}

class PipeSession(host: GameHost) : GameSession(host) {
    override val realtime = true
    private val levels = 12
    private val track = LevelTrack(host, levels)
    private var levelIndex = track.current
    private var level = PipeLevel.generate(host.difficulty.ordinal, levelIndex)
    private var board = PipeBoard(level)
    private var countdown = level.countdown
    private var queueIndex = 0
    private var nextRot = 0
    private var fast = false
    private var score = 0
    private var message = ""
    private var t = 0f
    private var leakT = -1f
    private var drainT = -1f

    private val grid = GridFit(level.cols, level.rows)
    private val queueBox = RectF()
    private val queueCells = Array(QUEUE) { RectF() }
    private var downCell = -1
    private var downAt = 0L
    private var longFired = false

    companion object { const val QUEUE = 5 }

    // ---- hooks for automated tests
    internal val boardForTest get() = board
    internal val levelForTest get() = level
    internal fun placeForTest(cell: Int, mask: Int) = board.place(cell, mask)
    internal fun startFlowForTest() { countdown = 0f }

    override fun start(saved: JSONObject?) {
        if (saved != null) {
            val li = saved.getInt("level")
            require(li in 0 until levels)
            load(li)
            val fr = saved.getJSONArray("fronts")
            val fronts = (0 until fr.length()).map { val a = fr.getJSONArray(it); PipeBoard.Front(a.getInt(0), a.getInt(1), a.getDouble(2).toFloat()) }
            board.restore(saved.getJSONArray("kind").toIntArray(), saved.getJSONArray("mask").toIntArray(), saved.getJSONArray("fill").toIntArray(),
                fronts, saved.getBoolean("started"), saved.getInt("filled"), saved.optInt("cross"))
            countdown = saved.getDouble("countdown").toFloat()
            queueIndex = saved.getInt("queue"); nextRot = saved.optInt("rot"); score = saved.getInt("score"); fast = saved.optBoolean("fast")
        } else load(levelIndex)
        message = if (board.started) "THE WATER IS FLOWING!" else "BUILD A PIPE FROM IN TO OUT BEFORE THE WATER STARTS."
    }

    private fun load(i: Int) {
        levelIndex = i
        level = PipeLevel.generate(host.difficulty.ordinal, i)
        board = PipeBoard(level)
        countdown = level.countdown; queueIndex = 0; nextRot = 0; fast = false; score = 0; leakT = -1f; drainT = -1f
        grid.cols = level.cols; grid.rows = level.rows
        onLayout()
    }

    override fun save(): JSONObject? {
        if (leakT >= 0f || drainT >= 0f) return null
        val fr = JSONArray()
        for (f in board.fronts) fr.put(JSONArray().put(f.cell).put(f.entry).put(f.progress.toDouble()))
        return JSONObject().put("level", levelIndex).put("kind", board.kind.toJsonArray()).put("mask", board.mask.toJsonArray())
            .put("fill", board.fill.toJsonArray()).put("fronts", fr).put("started", board.started).put("filled", board.filledCells)
            .put("cross", board.crossBonus).put("countdown", countdown.toDouble()).put("queue", queueIndex).put("rot", nextRot)
            .put("score", score).put("fast", fast)
    }

    override fun onLayout() {
        val p = px
        val textH = PixelFont.LINE * maxOf(2f, floor(p * 0.7f)) * 2 + p * 3
        val side = area.width() > area.height() * 1.15f
        if (side) {
            val qw = minOf(area.width() * 0.2f, p * 34)
            val boardBox = RectF(area.left, area.top, area.right - qw - p * 4, area.bottom - textH)
            grid.fit(boardBox, p * 3)
            queueBox.set(grid.bounds.right + p * 6, grid.bounds.top, grid.bounds.right + p * 6 + qw, grid.bounds.bottom)
            val qc = floor(minOf(qw - p * 4, (queueBox.height() - p * 30) / (1.3f + 0.9f * (QUEUE - 1))))
            var y = queueBox.top + p * 8
            for (k in 0 until QUEUE) {
                val s = if (k == 0) qc * 1.3f else qc * 0.9f
                queueCells[k].set(floor(queueBox.centerX() - s / 2), floor(y), floor(queueBox.centerX() + s / 2), floor(y + s))
                y += s + p * 5
            }
        } else {
            val qh = minOf(area.height() * 0.14f, p * 30)
            queueBox.set(area.left + p * 2, area.top, area.right - p * 2, area.top + qh)
            val boardBox = RectF(area.left, queueBox.bottom + p * 2, area.right, area.bottom - textH)
            grid.fit(boardBox, p * 3)
            queueBox.left = grid.bounds.left; queueBox.right = grid.bounds.right
            val qc = floor(minOf((qh - p * 6) / 1.25f, (queueBox.width() - p * 46) / (1.25f + 0.85f * (QUEUE - 1))))
            var x = queueBox.left + p * 24
            for (k in 0 until QUEUE) {
                val s = if (k == 0) qc * 1.25f else qc * 0.85f
                queueCells[k].set(floor(x), floor(queueBox.bottom - p * 2 - s), floor(x + s), floor(queueBox.bottom - p * 2))
                x += s + p * 5
            }
        }
    }

    private val active get() = !finished && leakT < 0f && drainT < 0f

    override fun onTouch(e: MotionEvent) {
        if (!active) return
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downCell = grid.cellAt(e.x, e.y); downAt = SystemClock.uptimeMillis(); longFired = false
                if (downCell < 0 && queueCells[0].contains(e.x, e.y)) rotateNext()
            }
            MotionEvent.ACTION_UP -> {
                val c = downCell
                downCell = -1
                if (c < 0 || longFired || grid.cellAt(e.x, e.y) != c) return
                tapCell(c)
            }
            MotionEvent.ACTION_CANCEL -> downCell = -1
        }
    }

    private fun tapCell(c: Int) {
        when {
            board.kind[c] == Cell.EMPTY -> {
                board.place(c, Pipes.mask(level.piece(queueIndex), nextRot))
                queueIndex++; nextRot = 0
                host.sfx(Sfx.PLACE)
            }
            board.isPipe(c) && !board.locked(c) -> { board.rotate(c); host.sfx(Sfx.ROTATE) }
            board.isPipe(c) -> { host.sfx(Sfx.ERROR); message = if (level.fixed[c]) "BOLTED PIPES CAN'T BE TURNED." else "WATER IS ALREADY IN THIS PIPE." }
            else -> host.sfx(Sfx.ERROR)
        }
    }

    private fun rotateNext() { nextRot = (nextRot + 1) and 3; host.sfx(Sfx.ROTATE) }

    private fun skip() { queueIndex++; nextRot = 0; host.sfx(Sfx.BACK) }

    private fun go() {
        if (!board.started) {
            score += ceil(countdown).toInt() * 5
            countdown = 0f
            message = "HERE COMES THE WATER!"
        } else if (!fast) { fast = true; message = "FAST FORWARD! THE WATER RUSHES ON." }
        host.sfx(Sfx.SELECT)
    }

    override fun update(dt: Float) {
        t += dt
        if (downCell >= 0 && !longFired && SystemClock.uptimeMillis() - downAt > 450) {
            longFired = true
            if (board.remove(downCell)) { host.sfx(Sfx.BACK); host.vibrate(20) }
        }
        if (leakT >= 0f) { leakT += dt; return }
        if (drainT >= 0f) { drainT += dt; return }
        if (finished) return
        if (!board.started) {
            countdown -= dt
            if (countdown <= 3f && floor(countdown + dt) != floor(countdown) && countdown > 0f) host.sfx(Sfx.TICK)
            if (countdown <= 0f) { countdown = 0f; board.startFlow(); host.sfx(Sfx.START); message = "THE WATER IS FLOWING!" }
            return
        }
        val before = board.filledCells
        val ev = board.advance(dt / level.secPerCell * (if (fast) 5f else 1f))
        if (board.filledCells > before) { score += (board.filledCells - before) * 10; host.sfx(Sfx.TICK) }
        when (ev) {
            FlowEvent.LEAK -> {
                leakT = 0f
                host.sfx(Sfx.LOSE); host.vibrate(80)
                message = if (board.leakCell < 0) "THE WATER IS TRAPPED IN A LOOP!" else "LEAK! THE WATER SPILLED OUT."
                lose(score = score, title = if (board.leakCell < 0) "TRAPPED!" else "LEAK!",
                    detail = "LEVEL ${levelIndex + 1}  PIPES FILLED ${board.filledCells}", delay = 1.6f)
            }
            FlowEvent.DRAINED -> {
                drainT = 0f
                val bonus = 100 + levelIndex * 20 + board.crossBonus * 50
                score += bonus
                track.complete(levelIndex, 1)
                host.unlock("first_win")
                if (board.crossBonus > 0) host.unlock("cross")
                if (host.difficulty == Difficulty.HARD) host.unlock("hard_win")
                if (track.cleared == levels) host.unlock("all_${host.difficulty.name.lowercase()}")
                message = "THE WATER REACHED THE DRAIN!"
                win(score = score, title = "LEVEL CLEAR!", detail = "LEVEL ${levelIndex + 1}  PIPES ${board.filledCells}  BONUS $bonus", delay = 1.4f)
            }
            else -> {}
        }
    }

    override fun hud() = listOf(
        HudItem("LEVEL", "${levelIndex + 1}/$levels", Pal.CYAN),
        HudItem("SCORE", score.toString()),
        if (board.started) HudItem("WATER", if (fast) "FAST" else "FLOWING", Pal.lighter(PipeArt.WATER, 0.2f))
        else HudItem("WATER IN", ceil(countdown).toInt().toString(), if (countdown <= 5f) Pal.RED else Pal.YELLOW),
    )

    override fun actions() = listOf(
        GameAction("ROTATE", Icons.RESTART, isEnabled = { active }) { rotateNext() },
        GameAction("SKIP", Icons.CLOSE, isEnabled = { active }) { skip() },
        GameAction("GO!", Icons.PLAY, isActive = { fast }, isEnabled = { active && !fast }) { go() },
    )

    override fun draw(c: Canvas) {
        val p = px
        val cell = grid.cell
        drawBoardFrame(c, grid.bounds, p, PipeArt.WATER)
        Gfx.rect(c, grid.bounds, Pal.BG0)
        val r = RectF()
        for (i in 0 until level.cols * level.rows) {
            grid.rect(i % level.cols, i / level.cols, r)
            PipeArt.tile(c, r, cell)
            when (board.kind[i]) {
                Cell.ROCK -> PipeArt.rock(c, r, cell)
                Cell.SOURCE -> PipeArt.endpoint(c, r, board.mask[i], cell, true, true, t)
                Cell.DRAIN -> PipeArt.endpoint(c, r, board.mask[i], cell, false, board.fill[i] != 0, t)
                Cell.PIPE -> {
                    PipeArt.pipe(c, r, board.mask[i], cell, level.fixed[i])
                    val f = board.fill[i]
                    val m = board.mask[i]
                    if (f and 1 != 0) {
                        // water fully through, except the part of the front still flowing in this cell
                        val fr = board.fronts.firstOrNull { it.cell == i }
                        if (fr == null) PipeArt.water(c, r, m, cell)
                    }
                    if (f and 2 != 0 && board.fronts.none { it.cell == i && (it.entry == 1 || it.entry == 3) }) PipeArt.water(c, r, m and 10, cell)
                    if (f and 4 != 0 && board.fronts.none { it.cell == i && (it.entry == 0 || it.entry == 2) }) PipeArt.water(c, r, m and 5, cell)
                    if (downCell == i && !board.locked(i) && SystemClock.uptimeMillis() - downAt > 150) {
                        val k = ((SystemClock.uptimeMillis() - downAt - 150) / 300f).coerceIn(0f, 1f)
                        Gfx.frame(c, r, maxOf(1f, p / 2), Pal.withAlpha(Pal.RED, (255 * k).toInt()))
                    }
                }
            }
        }
        for (f in board.fronts) {
            if (board.kind[f.cell] == Cell.SOURCE) continue
            grid.rect(f.cell % level.cols, f.cell / level.cols, r)
            PipeArt.front(c, r, board.mask[f.cell], f.entry, f.progress, cell)
        }
        // leak splash
        if (leakT >= 0f && board.leakCell >= 0) {
            grid.rect(board.leakCell % level.cols, board.leakCell / level.cols, r)
            val d = board.leakDir
            val ex = r.centerX() + Pipes.DX[d] * cell / 2; val ey = r.centerY() + Pipes.DY[d] * cell / 2
            for (k in 0 until 10) {
                val a = k * 0.63f + t * 2f
                val dist = cell * (0.2f + 0.5f * ((leakT * 1.5f + k * 0.13f) % 1f))
                Gfx.rect(c, ex + kotlin.math.cos(a) * dist, ey + sin(a) * dist, ex + kotlin.math.cos(a) * dist + p, ey + sin(a) * dist + p, PipeArt.WATER)
            }
        }
        // queue of upcoming pieces
        val ts = maxOf(2f, floor(p * 0.7f))
        if (queueBox.width() > queueBox.height()) Gfx.textMid(c, "NEXT", queueBox.left, queueCells[0].centerY(), ts, Pal.LGREY, Align.LEFT)
        else Gfx.text(c, "NEXT", queueBox.centerX(), queueBox.top, ts, Pal.LGREY, Align.CENTER)
        for (k in 0 until QUEUE) {
            val qr = queueCells[k]
            PipeArt.queuePiece(c, qr, level.piece(queueIndex + k), if (k == 0) nextRot else 0, qr.width())
            if (k == 0) Gfx.frame(c, RectF(qr.left - p, qr.top - p, qr.right + p, qr.bottom + p), maxOf(1f, p / 2), Pal.YELLOW)
        }
        // countdown / flow indicator under the board
        val barY = grid.bounds.bottom + p * 4
        if (!board.started) {
            val k = (countdown / level.countdown).coerceIn(0f, 1f)
            Gfx.rect(c, grid.bounds.left, barY, grid.bounds.right, barY + p * 2, Pal.BG2)
            Gfx.rect(c, grid.bounds.left, barY, grid.bounds.left + grid.bounds.width() * k, barY + p * 2, if (countdown <= 5f) Pal.RED else Pal.YELLOW)
        }
        Gfx.paragraph(c, message, area.left + p * 2, barY + p * 4, area.width() - p * 4, ts,
            if (leakT >= 0f) Pal.lighter(Pal.RED, 0.3f) else Pal.LGREY, Align.CENTER)
    }
}

/** Tutorial exercise: turn two pipes so the water can get from IN to OUT. */
private class PipeDemo : TutorialDemo() {
    private val cols = 4
    private val kinds = intArrayOf(Cell.SOURCE, Cell.PIPE, Cell.PIPE, Cell.DRAIN)
    private val masks = intArrayOf(2, 5, 5, 8)
    private var flow = -1f
    private val box = RectF()
    private var cell = 0f
    private var ox = 0f
    private var oy = 0f
    override val task = "TRY IT: TAP THE PIPES TO TURN THEM UNTIL IN AND OUT ARE CONNECTED."

    override fun update(dt: Float) { if (flow >= 0f) flow += dt }

    override fun draw(c: Canvas, box: RectF, p: Float, t: Float) {
        this.box.set(box)
        cell = floor(minOf(box.width() / cols, box.height()))
        ox = floor(box.centerX() - cell * cols / 2); oy = floor(box.centerY() - cell / 2)
        for (i in 0 until cols) {
            val r = RectF(ox + i * cell, oy, ox + (i + 1) * cell, oy + cell)
            PipeArt.tile(c, r, cell)
            when (kinds[i]) {
                Cell.SOURCE -> PipeArt.endpoint(c, r, masks[i], cell, true, true, t)
                Cell.DRAIN -> PipeArt.endpoint(c, r, masks[i], cell, false, flow > 2.2f, t)
                else -> {
                    PipeArt.pipe(c, r, masks[i], cell, false)
                    if (flow >= 0f) {
                        val k = (flow - (i - 1) * 1.0f).coerceIn(0f, 1f)
                        if (k > 0f) PipeArt.front(c, r, masks[i], 3, k, cell)
                    }
                }
            }
        }
    }

    override fun onTouch(e: MotionEvent, box: RectF) {
        if (e.actionMasked != MotionEvent.ACTION_UP || done) return
        val i = floor((e.x - ox) / cell).toInt()
        if (i !in 1..2 || e.y < oy || e.y > oy + cell) return
        masks[i] = Pipes.rotate(masks[i])
        sound(Sfx.ROTATE)
        val ok = masks[1] and 10 == 10 && masks[2] and 10 == 10
        if (ok) { flow = 0f; complete("CONNECTED! THE WATER FLOWS STRAIGHT THROUGH TO THE DRAIN.") }
        else good(if (masks[i] == 10) "THIS PIPE LINES UP. NOW THE OTHER ONE." else "KEEP TURNING - OPENINGS MUST MEET.", Sfx.ROTATE)
    }
}

private val ART_PIPES = PixelArt(
    "..ll.....ll.....",
    "..ll.....ll.....",
    "..lllll..llllll.",
    "..lllll..llllll.",
    "...........ll...",
    "llllll.....ll...",
    "llllll..........",
)
private val ART_FLOW = PixelArt(
    "............",
    "bbbllllllll.",
    "bCbCCCCCCll.",
    "bbbllllllll.",
    "........ll..",
    "........ll..",
)
private val ART_LEAK = PixelArt(
    "..........",
    "llllll.C..",
    "CCCCCC..C.",
    "llllll.C.C",
    "........C.",
)

val PipeRushGame = GameDef(
    id = "piperush",
    title = "PIPE RUSH",
    category = Category.PUZZLE,
    tagline = "Lay pipes from IN to OUT before the water starts flowing!",
    description = "Place pipe pieces from the queue and turn them to build one continuous pipe from the water tank (IN) to the drain (OUT). " +
        "When the timer runs out the water starts flowing - if it reaches an open end, it leaks! 12 levels per difficulty, " +
        "with bigger boards, more rocks, bolted pipes and less time as you go.",
    icon = PixelArt(
        "................",
        ".bbbb...........",
        ".bCCbllllllll...",
        ".bCCbCCCCCCll...",
        ".bbbbllllllll...",
        "..........ll....",
        "..........ll....",
        "..........ll....",
        "..........ll....",
        "...llllllllll...",
        "...llCCCCCCll...",
        "...ll.llllll....",
        "...ll...........",
        "..DDDD..........",
        "..DkkD..........",
        "..DDDD..........",
    ),
    accent = PipeArt.WATER,
    music = Music.ARCADE,
    scoreKind = ScoreKind.HIGH_SCORE,
    tutorial = listOf(
        TutorialPage("OBJECTIVE", "Connect the water tank marked IN to the drain marked OUT with one continuous pipe. When the countdown ends, " +
            "the water starts flowing. Get it to the drain to clear the level.", ART_FLOW),
        TutorialPage("PLACING PIPES", "The NEXT queue shows the coming pieces: straights, corners, T-junctions and crosses. TAP an empty square to place the " +
            "highlighted piece. ROTATE (or tapping that piece) turns it first; SKIP throws it away.", ART_PIPES),
        TutorialPage("TURNING PIPES", "TAP a pipe on the board to turn it a quarter turn. HOLD a pipe to remove it. Once water is inside a pipe it can't be changed, " +
            "and bolted gray pipes never move.", ART_PIPES, demo = { PipeDemo() }),
        TutorialPage("CONNECTIONS", "Water only passes where two openings meet face to face. An opening facing an empty square, a rock, the edge " +
            "or the side of another pipe is a LEAK - and the level is lost.", ART_LEAK),
        TutorialPage("JUNCTIONS", "A CROSS lets water straight through - it can be crossed twice for a bonus. A T-JUNCTION sends water out of BOTH other openings at once: " +
            "if either branch reaches an open end before the water gets to the drain, it leaks.", ART_PIPES),
        TutorialPage("TIME & SCORE", "The bar under the board shows the time left before the water starts. Press GO! to start it early for a bonus; " +
            "press GO! again to make the water flow faster. Every filled pipe scores 10 points.", Icons.STAR),
    ),
    achievements = listOf(
        AchievementDef("first_win", "PLUMBER", "Clear a Pipe Rush level."),
        AchievementDef("cross", "CROSSROADS", "Send water through a cross twice."),
        AchievementDef("hard_win", "PRESSURE", "Clear a HARD level."),
        AchievementDef("all_easy", "APPRENTICE", "Clear all 12 EASY levels."),
        AchievementDef("all_normal", "JOURNEYMAN", "Clear all 12 NORMAL levels."),
        AchievementDef("all_hard", "MASTER PLUMBER", "Clear all 12 HARD levels."),
    ),
    factory = { PipeSession(it) },
)
