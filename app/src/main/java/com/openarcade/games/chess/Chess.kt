package com.openarcade.games.chess

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
import com.openarcade.games.Worker
import com.openarcade.games.drawBoardFrame
import com.openarcade.games.toIntArray
import com.openarcade.games.toJsonArray
import com.openarcade.gfx.Align
import com.openarcade.gfx.Gfx
import com.openarcade.gfx.Icons
import com.openarcade.gfx.Pal
import com.openarcade.gfx.PixelArt
import org.json.JSONObject
import kotlin.math.abs

object ChessArt {
    private val SHAPES = mapOf(
        ChessEngine.P to listOf(
            "............", ".....OO.....", "....OXXO....", "....OXXO....", ".....OO.....", "....OXXO....",
            "....OXXO....", "...OXXXXO...", "..OXXXXXXO..", "..OXXXXXXO..", "..OOOOOOOO..", "............"),
        ChessEngine.R to listOf(
            "............", ".OO.OOOO.OO.", ".OXOOXXOOXO.", ".OXXXXXXXXO.", "..OXXXXXXO..", "..OXXXXXXO..",
            "..OXXXXXXO..", "..OXXXXXXO..", ".OXXXXXXXXO.", ".OXXXXXXXXO.", ".OOOOOOOOOO.", "............"),
        ChessEngine.N to listOf(
            "............", "....OOO.....", "...OXXXOO...", "..OXXDXXXO..", "..OXXXXXXXO.", ".OXXXOXXXXO.",
            ".OOOO.OXXXO.", ".....OXXXO..", "....OXXXXO..", "...OXXXXXXO.", "...OOOOOOOO.", "............"),
        ChessEngine.B to listOf(
            "............", ".....OO.....", "....OXXO....", "....OXDO....", "...OXXDXO...", "...OXDXXO...",
            "...OXXXXO...", "....OXXO....", "...OXXXXO...", "..OXXXXXXO..", "..OOOOOOOO..", "............"),
        ChessEngine.Q to listOf(
            "............", "O...O..O...O", "OO.OXOOXO.OO", "OXOXXXXXXOXO", "OXXXXXXXXXXO", ".OXXXXXXXXO.",
            "..OXXXXXXO..", "..OXXXXXXO..", ".OXXXXXXXXO.", ".OXXXXXXXXO.", ".OOOOOOOOOO.", "............"),
        ChessEngine.K to listOf(
            "............", ".....OO.....", "....OXXO....", "....OXXO....", ".OOOOXXOOOO.", "OXXXXXXXXXXO",
            "OXXXXXXXXXXO", ".OXXXXXXXXO.", "..OXXXXXXO..", ".OXXXXXXXXO.", ".OOOOOOOOOO.", "............"),
    )
    private val whiteMap = mapOf('O' to Pal.BLACK, 'X' to Pal.WHITE, 'D' to Pal.BLACK)
    private val blackMap = mapOf('O' to Pal.LGREY, 'X' to 0xFF30304A.toInt(), 'D' to Pal.LGREY)
    private val cache = HashMap<Int, PixelArt>()

    fun piece(v: Int): PixelArt = cache.getOrPut(v) {
        PixelArt(SHAPES[abs(v)]!!, if (v > 0) whiteMap else blackMap)
    }
}

class ChessSession(host: GameHost) : GameSession(host) {
    private val engine = ChessEngine()
    private var playerSide = 1
    private val grid = GridFit(8, 8)
    private var selected = -1
    private var legal: List<Int> = emptyList()
    private var promoChoices: List<Int>? = null
    private val ai = Worker<Int>()
    private var thinking = false
    private var hintMove = 0
    private val hintWorker = Worker<Int>()
    private var anim = 1f
    private var animMove = 0
    private var animPiece = 0
    private var t = 0f
    private var statusText = ""

    private val depth = when (host.difficulty) { Difficulty.EASY -> 1; Difficulty.NORMAL -> 3; Difficulty.HARD -> 4 }
    private val noise = when (host.difficulty) { Difficulty.EASY -> 120; Difficulty.NORMAL -> 15; Difficulty.HARD -> 0 }
    private val timeMs = when (host.difficulty) { Difficulty.EASY -> 600L; Difficulty.NORMAL -> 1500L; Difficulty.HARD -> 3000L }

    override fun start(saved: JSONObject?) {
        engine.reset()
        if (saved != null) {
            playerSide = saved.getInt("side")
            for (m in saved.getJSONArray("moves").toIntArray()) engine.play(m)
        } else {
            // alternate colours between games
            playerSide = if (host.getInt("lastSide", -1) == 1) -1 else 1
            host.putInt("lastSide", playerSide)
        }
        legal = engine.legalMoves()
        updateStatus()
        if (engine.side != playerSide) startAi()
    }

    override fun save(): JSONObject = JSONObject().put("side", playerSide).put("moves", engine.history.toIntArray().toJsonArray())

    override fun onLayout() = grid.fit(area, px * 6)

    private fun flipped() = playerSide < 0
    private fun viewSq(sq: Int) = if (flipped()) 63 - sq else sq

    private fun startAi() {
        if (thinking || finished) return
        thinking = true
        val copy = engine.copy()
        val seed = host.rng.nextLong()
        ai.run { copy.bestMove(depth, timeMs, noise, kotlin.random.Random(seed)) }
    }

    override fun update(dt: Float) {
        t += dt
        if (anim < 1f) anim = minOf(1f, anim + dt / 0.18f)
        if (thinking && anim >= 1f && !ai.busy) {
            val m = ai.take() ?: legal.firstOrNull() ?: 0
            thinking = false
            if (m != 0 && !finished) doMove(m)
        }
        hintWorker.take()?.let { hintMove = it }
    }

    private fun doMove(m: Int) {
        val capture = ChessEngine.flags(m) and ChessEngine.F_CAPTURE != 0
        animPiece = engine.board[ChessEngine.fromSq(m)]
        if (ChessEngine.promo(m) != 0) animPiece = ChessEngine.promo(m) * engine.side
        engine.play(m)
        animMove = m; anim = 0f
        selected = -1; hintMove = 0
        legal = engine.legalMoves()
        host.sfx(if (capture) Sfx.CAPTURE else Sfx.PLACE)
        checkEnd()
        if (!finished && engine.inCheck()) host.sfx(Sfx.CHECK)
        if (!finished && engine.side != playerSide) startAi()
    }

    private fun updateStatus() {
        statusText = when {
            thinking -> "THINKING"
            engine.side == playerSide -> if (engine.inCheck()) "CHECK!" else "YOUR MOVE"
            else -> "CPU MOVE"
        }
    }

    private fun checkEnd() {
        when (engine.status()) {
            ChessEngine.Status.CHECKMATE -> {
                if (engine.side != playerSide) {
                    host.unlock("first_win")
                    if (host.difficulty == Difficulty.HARD) host.unlock("hard_win")
                    if (engine.history.size <= 50) host.unlock("quick")
                    win(detail = "CHECKMATE IN ${(engine.history.size + 1) / 2} MOVES", delay = 1.5f)
                } else lose(detail = "CHECKMATE", delay = 1.5f)
            }
            ChessEngine.Status.STALEMATE -> draw("STALEMATE")
            ChessEngine.Status.DRAW_50 -> draw("50-MOVE RULE")
            ChessEngine.Status.DRAW_REPETITION -> draw("THREEFOLD REPETITION")
            ChessEngine.Status.DRAW_MATERIAL -> draw("INSUFFICIENT MATERIAL")
            else -> {}
        }
    }

    private fun draw(reason: String) {
        host.unlock("draw")
        lose(detail = reason, delay = 1.5f, title = "DRAW")
    }

    override fun onTouch(e: MotionEvent) {
        if (e.actionMasked != MotionEvent.ACTION_DOWN) return
        promoChoices?.let { choices ->
            // promotion picker overlays the board centre
            val r = promoRect()
            if (r.contains(e.x, e.y)) {
                val k = ((e.x - r.left) / (r.width() / 4)).toInt().coerceIn(0, 3)
                promoChoices = null
                doMove(choices[k])
            } else promoChoices = null
            return
        }
        if (thinking || engine.side != playerSide || anim < 1f) return
        val vi = grid.cellAt(e.x, e.y)
        if (vi < 0) return
        val sq = viewSq(vi)
        val v = engine.board[sq]
        if (selected >= 0) {
            val moves = legal.filter { ChessEngine.fromSq(it) == selected && ChessEngine.toSq(it) == sq }
            if (moves.isNotEmpty()) {
                if (moves.size > 1) { promoChoices = moves.sortedBy { listOf(ChessEngine.Q, ChessEngine.R, ChessEngine.B, ChessEngine.N).indexOf(ChessEngine.promo(it)) }; return }
                doMove(moves[0]); return
            }
        }
        if (v != 0 && (v > 0) == (playerSide > 0)) {
            selected = if (selected == sq) -1 else sq
            host.sfx(Sfx.CLICK)
        } else {
            if (selected >= 0) host.sfx(Sfx.ERROR)
            selected = -1
        }
    }

    private fun promoRect(): RectF {
        val cs = grid.cell
        return RectF(grid.bounds.centerX() - cs * 2, grid.bounds.centerY() - cs * 0.6f, grid.bounds.centerX() + cs * 2, grid.bounds.centerY() + cs * 0.6f)
    }

    private fun undo() {
        if (finished) return
        ai.cancel(); thinking = false
        promoChoices = null
        if (engine.history.isEmpty()) return
        engine.takeBack()
        if (engine.side != playerSide && engine.history.isNotEmpty()) engine.takeBack()
        if (engine.side != playerSide) startAi()
        legal = engine.legalMoves()
        selected = -1; hintMove = 0; anim = 1f; animMove = 0
        host.sfx(Sfx.BACK)
    }

    private fun hint() {
        if (thinking || finished || engine.side != playerSide || hintWorker.busy) return
        val copy = engine.copy()
        val seed = host.rng.nextLong()
        hintWorker.run { copy.bestMove(3, 1500L, 0, kotlin.random.Random(seed)) }
        host.sfx(Sfx.POWER)
    }

    private fun resign() {
        if (finished) return
        host.sfx(Sfx.LOSE)
        lose(detail = "YOU RESIGNED", delay = 0.3f)
    }

    override fun hud(): List<HudItem> {
        updateStatus()
        return listOf(
            HudItem("YOU", if (playerSide > 0) "WHITE" else "BLACK", if (playerSide > 0) Pal.WHITE else Pal.GREY),
            HudItem("MOVE", ((engine.history.size / 2) + 1).toString()),
            HudItem("STATUS", if (thinking) "THINKING" + ".".repeat(1 + (t * 3).toInt() % 3) else statusText,
                if (statusText == "CHECK!") Pal.RED else Pal.YELLOW),
        )
    }

    override fun actions() = listOf(
        GameAction("UNDO", Icons.UNDO, isEnabled = { engine.history.isNotEmpty() && !finished }) { undo() },
        GameAction("HINT", Icons.HINT, isEnabled = { !thinking && !finished }) { hint() },
        GameAction("RESIGN", Icons.FLAG, isEnabled = { !finished }) { resign() },
    )

    override fun draw(c: Canvas) {
        val p = px
        val cs = grid.cell
        drawBoardFrame(c, grid.bounds, p, Pal.BROWN)
        val r = RectF()
        val last = engine.history.lastOrNull()
        val kingInCheck = if (engine.inCheck()) engine.kingSq(engine.side) else -1
        val targets = if (selected >= 0) legal.filter { ChessEngine.fromSq(it) == selected }.map { ChessEngine.toSq(it) }.toSet() else emptySet()
        for (vi in 0 until 64) {
            val sq = viewSq(vi)
            grid.rect(vi % 8, vi / 8, r)
            val light = (vi % 8 + vi / 8) % 2 == 0
            var col = if (light) Pal.TAN else Pal.BROWN
            if (last != null && (sq == ChessEngine.fromSq(last) || sq == ChessEngine.toSq(last))) col = Pal.mix(col, Pal.YELLOW, 0.35f)
            if (sq == selected) col = Pal.mix(col, Pal.GREEN, 0.5f)
            if (hintMove != 0 && (sq == ChessEngine.fromSq(hintMove) || sq == ChessEngine.toSq(hintMove)) && (t * 3).toInt() % 2 == 0) col = Pal.mix(col, Pal.CYAN, 0.6f)
            if (sq == kingInCheck) col = Pal.mix(col, Pal.RED, 0.6f)
            Gfx.rect(c, r, col)
        }
        // coordinates
        val ts = maxOf(1f, cs / 30f)
        for (k in 0 until 8) {
            val file = if (flipped()) 'h' - k else 'a' + k
            val rank = if (flipped()) k + 1 else 8 - k
            Gfx.text(c, file.toString(), grid.x(k) + cs - ts * 6, grid.bounds.bottom - ts * 8, ts, Pal.withAlpha(Pal.BLACK, 140), shadow = null)
            Gfx.text(c, rank.toString(), grid.x(0) + ts, grid.y(k) + ts, ts, Pal.withAlpha(Pal.BLACK, 140), shadow = null)
        }
        val animTo = if (anim < 1f && animMove != 0) ChessEngine.toSq(animMove) else -1
        for (vi in 0 until 64) {
            val sq = viewSq(vi)
            val v = engine.board[sq]
            if (v == 0 || sq == animTo) continue
            grid.rect(vi % 8, vi / 8, r, cs * 0.06f)
            ChessArt.piece(v).draw(c, r)
        }
        if (animTo >= 0) {
            val fv = viewSq(ChessEngine.fromSq(animMove)); val tv = viewSq(animTo)
            val e = 1f - (1f - anim) * (1f - anim)
            val x = grid.x(fv % 8) + (grid.x(tv % 8) - grid.x(fv % 8)) * e
            val y = grid.y(fv / 8) + (grid.y(tv / 8) - grid.y(fv / 8)) * e
            val inset = cs * 0.06f
            ChessArt.piece(engine.board[animTo].takeIf { it != 0 } ?: animPiece).draw(c, RectF(x + inset, y + inset - cs * 0.1f * (1f - abs(e * 2 - 1)), x + cs - inset, y + cs - inset))
        }
        // legal move markers
        for (sq in targets) {
            val vi = viewSq(sq)
            val cx = grid.cx((vi % 8).toFloat()); val cy = grid.cy((vi / 8).toFloat())
            if (engine.board[sq] != 0) {
                grid.rect(vi % 8, vi / 8, r)
                Gfx.frame(c, r, maxOf(1f, cs / 12f), Pal.withAlpha(Pal.RED, 200))
            } else Gfx.disc(c, cx, cy, cs * 0.14f, Pal.withAlpha(Pal.DGREEN, 200), maxOf(1f, cs / 16f))
        }
        promoChoices?.let { choices ->
            val pr = promoRect()
            Gfx.panel(c, RectF(pr.left - p * 2, pr.top - p * 6 - cs * 0.3f, pr.right + p * 2, pr.bottom + p * 2), Pal.BG1, Pal.YELLOW, p)
            Gfx.text(c, "PROMOTE TO", pr.centerX(), pr.top - p * 3 - cs * 0.3f, maxOf(1f, cs / 25f), Pal.YELLOW, Align.CENTER)
            choices.forEachIndexed { k, m ->
                val w = pr.width() / 4
                val box = RectF(pr.left + k * w, pr.top, pr.left + (k + 1) * w, pr.bottom)
                ChessArt.piece(ChessEngine.promo(m) * playerSide).drawFit(c, RectF(box.left + p, box.top + p, box.right - p, box.bottom - p))
            }
        }
    }
}

val ChessGame = GameDef(
    id = "chess",
    title = "CHESS",
    category = Category.BOARD,
    tagline = "The royal game. Outwit the computer and checkmate its king.",
    description = "Full chess rules including castling, en passant, promotion, and draws by stalemate, repetition and the 50-move rule. " +
        "Play against a built-in computer opponent with three strengths. You alternate between White and Black each game.",
    icon = PixelArt(
        "................",
        ".......KK.......",
        "......KWWK......",
        "......KWWK......",
        "...KKKKWWKKKK...",
        "..KWWWWWWWWWWK..",
        "..KWWWWWWWWWWK..",
        "...KWWWWWWWWK...",
        "....KWWWWWWK....",
        "...KWWWWWWWWK...",
        "...KKKKKKKKKK...",
        "................",
        ".tMtMtMtMtMtMtM.",
        ".MtMtMtMtMtMtMt.",
        "................",
        "................",
    ),
    accent = Pal.TAN,
    music = Music.BOARD,
    scoreKind = ScoreKind.WINS,
    tutorial = listOf(
        TutorialPage("OBJECTIVE", "Checkmate the enemy king: attack it so that it cannot escape. If a player has no legal move but is not in check, the game is a draw (stalemate).",
            PixelArt(
                "tMtMtMtM",
                "MtMtMtMt",
                "tMtWtMtM",
                "MtMtMtMt",
                "tMtMRMtM",
                "MtMtMtMt",
            )),
        TutorialPage("CONTROLS", "TAP one of your pieces to select it - green dots show where it can go, red frames show captures. TAP a highlighted square to move there.", Icons.TAP),
        TutorialPage("HOW PIECES MOVE", "PAWN: forward 1 (2 on its first move), captures diagonally. KNIGHT: L-shape, jumps over pieces. BISHOP: diagonals. ROOK: straight lines. QUEEN: any line. KING: 1 square any way.", ChessArt.piece(ChessEngine.N)),
        TutorialPage("SPECIAL MOVES", "CASTLING: move the king two squares toward a rook (tap the destination) if neither has moved and the path is safe. EN PASSANT: capture a pawn that just jumped two squares past yours. PROMOTION: a pawn reaching the far side becomes any piece.", ChessArt.piece(ChessEngine.K)),
        TutorialPage("TIPS", "Control the center, develop knights and bishops early, and castle to keep your king safe. Before every move, check what your opponent is threatening. Use HINT if you're stuck and UNDO to take back a blunder.", Icons.BULB),
    ),
    achievements = listOf(
        AchievementDef("first_win", "CHECKMATE!", "Beat the computer."),
        AchievementDef("hard_win", "GRANDMASTER", "Beat the computer on HARD."),
        AchievementDef("quick", "BLITZ", "Win in 25 moves or fewer."),
        AchievementDef("draw", "STALEMATE", "Draw a game."),
    ),
    factory = { ChessSession(it) },
)
