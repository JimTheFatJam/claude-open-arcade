package com.openarcade.games.chess

import kotlin.random.Random

/**
 * Complete chess rules and a small alpha-beta AI.
 *
 * Squares: index = row * 8 + col, row 0 is Black's back rank (rank 8), row 7 is White's (rank 1).
 * Pieces: 0 empty, 1 pawn, 2 knight, 3 bishop, 4 rook, 5 queen, 6 king; positive = White, negative = Black.
 * Moves are packed ints: from | to << 6 | promo << 12 | flags << 16.
 */
class ChessEngine {
    companion object {
        const val P = 1; const val N = 2; const val B = 3; const val R = 4; const val Q = 5; const val K = 6
        const val F_CAPTURE = 1; const val F_EP = 2; const val F_CASTLE = 4; const val F_DOUBLE = 8

        fun move(from: Int, to: Int, promo: Int = 0, flags: Int = 0) = from or (to shl 6) or (promo shl 12) or (flags shl 16)
        fun fromSq(m: Int) = m and 63
        fun toSq(m: Int) = (m shr 6) and 63
        fun promo(m: Int) = (m shr 12) and 7
        fun flags(m: Int) = (m shr 16) and 15

        val VALUE = intArrayOf(0, 100, 320, 330, 500, 900, 20000)

        private val KNIGHT = intArrayOf(-17, -15, -10, -6, 6, 10, 15, 17)
        private val KNIGHT_DC = intArrayOf(-1, 1, -2, 2, -2, 2, -1, 1)
        private val KING_D = intArrayOf(-9, -8, -7, -1, 1, 7, 8, 9)
        private val KING_DC = intArrayOf(-1, 0, 1, -1, 1, -1, 0, 1)
        private val DIAG = arrayOf(intArrayOf(-1, -1), intArrayOf(-1, 1), intArrayOf(1, -1), intArrayOf(1, 1))
        private val ORTHO = arrayOf(intArrayOf(-1, 0), intArrayOf(1, 0), intArrayOf(0, -1), intArrayOf(0, 1))

        // Piece-square tables from White's point of view (row 0 = rank 8).
        private val PST_P = intArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0, 50, 50, 50, 50, 50, 50, 50, 50, 10, 10, 20, 30, 30, 20, 10, 10, 5, 5, 10, 25, 25, 10, 5, 5,
            0, 0, 0, 20, 20, 0, 0, 0, 5, -5, -10, 0, 0, -10, -5, 5, 5, 10, 10, -20, -20, 10, 10, 5, 0, 0, 0, 0, 0, 0, 0, 0)
        private val PST_N = intArrayOf(
            -50, -40, -30, -30, -30, -30, -40, -50, -40, -20, 0, 0, 0, 0, -20, -40, -30, 0, 10, 15, 15, 10, 0, -30, -30, 5, 15, 20, 20, 15, 5, -30,
            -30, 0, 15, 20, 20, 15, 0, -30, -30, 5, 10, 15, 15, 10, 5, -30, -40, -20, 0, 5, 5, 0, -20, -40, -50, -40, -30, -30, -30, -30, -40, -50)
        private val PST_B = intArrayOf(
            -20, -10, -10, -10, -10, -10, -10, -20, -10, 0, 0, 0, 0, 0, 0, -10, -10, 0, 5, 10, 10, 5, 0, -10, -10, 5, 5, 10, 10, 5, 5, -10,
            -10, 0, 10, 10, 10, 10, 0, -10, -10, 10, 10, 10, 10, 10, 10, -10, -10, 5, 0, 0, 0, 0, 5, -10, -20, -10, -10, -10, -10, -10, -10, -20)
        private val PST_R = intArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0, 5, 10, 10, 10, 10, 10, 10, 5, -5, 0, 0, 0, 0, 0, 0, -5, -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5, -5, 0, 0, 0, 0, 0, 0, -5, -5, 0, 0, 0, 0, 0, 0, -5, 0, 0, 0, 5, 5, 0, 0, 0)
        private val PST_Q = intArrayOf(
            -20, -10, -10, -5, -5, -10, -10, -20, -10, 0, 0, 0, 0, 0, 0, -10, -10, 0, 5, 5, 5, 5, 0, -10, -5, 0, 5, 5, 5, 5, 0, -5,
            0, 0, 5, 5, 5, 5, 0, -5, -10, 5, 5, 5, 5, 5, 0, -10, -10, 0, 5, 0, 0, 0, 0, -10, -20, -10, -10, -5, -5, -10, -10, -20)
        private val PST_K = intArrayOf(
            -30, -40, -40, -50, -50, -40, -40, -30, -30, -40, -40, -50, -50, -40, -40, -30, -30, -40, -40, -50, -50, -40, -40, -30, -30, -40, -40, -50, -50, -40, -40, -30,
            -20, -30, -30, -40, -40, -30, -30, -20, -10, -20, -20, -20, -20, -20, -20, -10, 20, 20, 0, 0, 0, 0, 20, 20, 20, 30, 10, 0, 0, 10, 30, 20)
        private val PST_K_END = intArrayOf(
            -50, -40, -30, -20, -20, -30, -40, -50, -30, -20, -10, 0, 0, -10, -20, -30, -30, -10, 20, 30, 30, 20, -10, -30, -30, -10, 30, 40, 40, 30, -10, -30,
            -30, -10, 30, 40, 40, 30, -10, -30, -30, -10, 20, 30, 30, 20, -10, -30, -30, -30, 0, 0, 0, 0, -30, -30, -50, -30, -30, -30, -30, -30, -30, -50)
        private val PST = arrayOf(IntArray(64), PST_P, PST_N, PST_B, PST_R, PST_Q, PST_K)
    }

    val board = IntArray(64)
    var side = 1
    var castling = 15        // 1 white-king-side, 2 white-queen-side, 4 black-king-side, 8 black-queen-side
    var ep = -1
    var halfmove = 0
    var fullmove = 1
    private val undo = ArrayList<IntArray>()
    val history = ArrayList<Int>()
    val positions = ArrayList<String>()

    init { reset() }

    fun reset() {
        val back = intArrayOf(R, N, B, Q, K, B, N, R)
        board.fill(0)
        for (c in 0 until 8) {
            board[c] = -back[c]; board[8 + c] = -P
            board[48 + c] = P; board[56 + c] = back[c]
        }
        side = 1; castling = 15; ep = -1; halfmove = 0; fullmove = 1
        undo.clear(); history.clear(); positions.clear()
        positions.add(key())
    }

    /** Loads a position from FEN (used by tests and for future puzzle modes). */
    fun loadFen(fen: String) {
        val parts = fen.trim().split(Regex("\\s+"))
        board.fill(0)
        var r = 0; var c = 0
        for (ch in parts[0]) {
            when {
                ch == '/' -> { r++; c = 0 }
                ch.isDigit() -> c += ch - '0'
                else -> {
                    val type = when (ch.lowercaseChar()) { 'p' -> P; 'n' -> N; 'b' -> B; 'r' -> R; 'q' -> Q; else -> K }
                    board[r * 8 + c] = if (ch.isUpperCase()) type else -type
                    c++
                }
            }
        }
        side = if (parts.getOrElse(1) { "w" } == "w") 1 else -1
        castling = 0
        val cs = parts.getOrElse(2) { "-" }
        if ('K' in cs) castling = castling or 1
        if ('Q' in cs) castling = castling or 2
        if ('k' in cs) castling = castling or 4
        if ('q' in cs) castling = castling or 8
        val e = parts.getOrElse(3) { "-" }
        ep = if (e == "-") -1 else (8 - (e[1] - '0')) * 8 + (e[0] - 'a')
        halfmove = parts.getOrElse(4) { "0" }.toIntOrNull() ?: 0
        fullmove = parts.getOrElse(5) { "1" }.toIntOrNull() ?: 1
        undo.clear(); history.clear(); positions.clear(); positions.add(key())
    }

    /** Counts leaf nodes of the legal move tree (standard move generator correctness test). */
    fun perft(depth: Int): Long {
        if (depth == 0) return 1
        var n = 0L
        val s = side
        for (m in pseudoMoves()) {
            make(m)
            if (!inCheck(s)) n += perft(depth - 1)
            unmake()
        }
        return n
    }

    fun copy(): ChessEngine {
        val e = ChessEngine()
        board.copyInto(e.board)
        e.side = side; e.castling = castling; e.ep = ep; e.halfmove = halfmove; e.fullmove = fullmove
        e.positions.clear(); e.positions.addAll(positions)
        return e
    }

    fun key(): String = buildString {
        for (v in board) append(('a' + v + 6))
        append(side).append(castling).append(ep)
    }

    fun kingSq(s: Int): Int { for (i in 0 until 64) if (board[i] == K * s) return i; return -1 }

    /** Is [sq] attacked by side [by]? */
    fun attacked(sq: Int, by: Int): Boolean {
        val r = sq / 8; val c = sq % 8
        // pawns
        val pr = r + by  // white pawns attack upward (toward row-1), so a white attacker sits at row+1
        if (pr in 0..7) {
            if (c > 0 && board[pr * 8 + c - 1] == P * by) return true
            if (c < 7 && board[pr * 8 + c + 1] == P * by) return true
        }
        for (k in 0 until 8) {
            val t = sq + KNIGHT[k]; val tc = c + KNIGHT_DC[k]
            if (t in 0..63 && tc in 0..7 && kotlin.math.abs(t / 8 - r) <= 2 && board[t] == N * by) return true
        }
        for (k in 0 until 8) {
            val t = sq + KING_D[k]; val tc = c + KING_DC[k]
            if (t in 0..63 && tc in 0..7 && board[t] == K * by) return true
        }
        for (d in DIAG) {
            var rr = r + d[0]; var cc = c + d[1]
            while (rr in 0..7 && cc in 0..7) {
                val v = board[rr * 8 + cc]
                if (v != 0) { if (v == B * by || v == Q * by) return true; break }
                rr += d[0]; cc += d[1]
            }
        }
        for (d in ORTHO) {
            var rr = r + d[0]; var cc = c + d[1]
            while (rr in 0..7 && cc in 0..7) {
                val v = board[rr * 8 + cc]
                if (v != 0) { if (v == R * by || v == Q * by) return true; break }
                rr += d[0]; cc += d[1]
            }
        }
        return false
    }

    fun inCheck(s: Int = side): Boolean { val k = kingSq(s); return k >= 0 && attacked(k, -s) }

    fun pseudoMoves(capturesOnly: Boolean = false): ArrayList<Int> {
        val out = ArrayList<Int>(48)
        val s = side
        for (sq in 0 until 64) {
            val v = board[sq]
            if (v == 0 || (v > 0) != (s > 0)) continue
            val r = sq / 8; val c = sq % 8
            when (kotlin.math.abs(v)) {
                P -> {
                    val dir = -s
                    val r1 = r + dir
                    val promoRow = if (s > 0) 0 else 7
                    if (r1 in 0..7) {
                        if (!capturesOnly && board[r1 * 8 + c] == 0) {
                            addPawn(out, sq, r1 * 8 + c, 0, r1 == promoRow)
                            val startRow = if (s > 0) 6 else 1
                            val r2 = r + dir * 2
                            if (r == startRow && board[r2 * 8 + c] == 0) out.add(move(sq, r2 * 8 + c, 0, F_DOUBLE))
                        }
                        for (dc in intArrayOf(-1, 1)) {
                            val cc = c + dc
                            if (cc !in 0..7) continue
                            val t = r1 * 8 + cc
                            if (board[t] != 0 && (board[t] > 0) != (s > 0)) addPawn(out, sq, t, F_CAPTURE, r1 == promoRow)
                            else if (t == ep) out.add(move(sq, t, 0, F_CAPTURE or F_EP))
                        }
                    }
                }
                N -> for (k in 0 until 8) {
                    val t = sq + KNIGHT[k]; val tc = c + KNIGHT_DC[k]
                    if (t !in 0..63 || tc !in 0..7 || kotlin.math.abs(t / 8 - r) > 2) continue
                    addIf(out, sq, t, capturesOnly)
                }
                B -> slide(out, sq, DIAG, capturesOnly)
                R -> slide(out, sq, ORTHO, capturesOnly)
                Q -> { slide(out, sq, DIAG, capturesOnly); slide(out, sq, ORTHO, capturesOnly) }
                K -> {
                    for (k in 0 until 8) {
                        val t = sq + KING_D[k]; val tc = c + KING_DC[k]
                        if (t !in 0..63 || tc !in 0..7) continue
                        addIf(out, sq, t, capturesOnly)
                    }
                    if (!capturesOnly) castles(out, sq)
                }
            }
        }
        return out
    }

    private fun addPawn(out: ArrayList<Int>, f: Int, t: Int, flags: Int, promote: Boolean) {
        if (promote) for (pr in intArrayOf(Q, N, R, B)) out.add(move(f, t, pr, flags)) else out.add(move(f, t, 0, flags))
    }

    private fun addIf(out: ArrayList<Int>, f: Int, t: Int, capturesOnly: Boolean) {
        val v = board[t]
        if (v == 0) { if (!capturesOnly) out.add(move(f, t)) }
        else if ((v > 0) != (side > 0)) out.add(move(f, t, 0, F_CAPTURE))
    }

    private fun slide(out: ArrayList<Int>, sq: Int, dirs: Array<IntArray>, capturesOnly: Boolean) {
        val r = sq / 8; val c = sq % 8
        for (d in dirs) {
            var rr = r + d[0]; var cc = c + d[1]
            while (rr in 0..7 && cc in 0..7) {
                val t = rr * 8 + cc
                val v = board[t]
                if (v == 0) { if (!capturesOnly) out.add(move(sq, t)) }
                else { if ((v > 0) != (side > 0)) out.add(move(sq, t, 0, F_CAPTURE)); break }
                rr += d[0]; cc += d[1]
            }
        }
    }

    private fun castles(out: ArrayList<Int>, sq: Int) {
        val s = side
        val home = if (s > 0) 60 else 4
        if (sq != home || inCheck(s)) return
        val kBit = if (s > 0) 1 else 4
        val qBit = if (s > 0) 2 else 8
        if (castling and kBit != 0 && board[home + 1] == 0 && board[home + 2] == 0 && board[home + 3] == R * s &&
            !attacked(home + 1, -s) && !attacked(home + 2, -s)) out.add(move(home, home + 2, 0, F_CASTLE))
        if (castling and qBit != 0 && board[home - 1] == 0 && board[home - 2] == 0 && board[home - 3] == 0 && board[home - 4] == R * s &&
            !attacked(home - 1, -s) && !attacked(home - 2, -s)) out.add(move(home, home - 2, 0, F_CASTLE))
    }

    fun make(m: Int) {
        val f = fromSq(m); val t = toSq(m); val fl = flags(m)
        val piece = board[f]
        var captured = board[t]
        var capSq = t
        if (fl and F_EP != 0) { capSq = t + side * 8; captured = board[capSq]; board[capSq] = 0 }
        undo.add(intArrayOf(m, captured, castling, ep, halfmove, capSq))
        board[t] = piece; board[f] = 0
        val pr = promo(m)
        if (pr != 0) board[t] = pr * side
        if (fl and F_CASTLE != 0) {
            if (t > f) { board[t - 1] = board[t + 1]; board[t + 1] = 0 } else { board[t + 1] = board[t - 2]; board[t - 2] = 0 }
        }
        // castling rights
        if (kotlin.math.abs(piece) == K) castling = castling and (if (side > 0) 3.inv() else 12.inv())
        for (sq in intArrayOf(f, t)) when (sq) {
            63 -> castling = castling and 1.inv(); 56 -> castling = castling and 2.inv()
            7 -> castling = castling and 4.inv(); 0 -> castling = castling and 8.inv()
        }
        ep = if (fl and F_DOUBLE != 0) (f + t) / 2 else -1
        halfmove = if (kotlin.math.abs(piece) == P || captured != 0) 0 else halfmove + 1
        if (side < 0) fullmove++
        side = -side
    }

    fun unmake() {
        val u = undo.removeAt(undo.size - 1)
        val m = u[0]
        side = -side
        if (side < 0) fullmove--
        val f = fromSq(m); val t = toSq(m); val fl = flags(m)
        var piece = board[t]
        if (promo(m) != 0) piece = P * side
        board[f] = piece
        board[t] = 0
        board[u[5]] = u[1]
        if (fl and F_CASTLE != 0) {
            if (t > f) { board[t + 1] = board[t - 1]; board[t - 1] = 0 } else { board[t - 2] = board[t + 1]; board[t + 1] = 0 }
        }
        castling = u[2]; ep = u[3]; halfmove = u[4]
    }

    fun legalMoves(): List<Int> {
        val out = ArrayList<Int>()
        val s = side
        for (m in pseudoMoves()) {
            make(m)
            if (!inCheck(s)) out.add(m)
            unmake()
        }
        return out
    }

    /** Plays a move on the real game (records history & repetition). */
    fun play(m: Int) {
        make(m)
        history.add(m)
        positions.add(key())
    }

    fun takeBack() {
        if (history.isEmpty()) return
        unmake()
        history.removeAt(history.size - 1)
        positions.removeAt(positions.size - 1)
    }

    fun insufficientMaterial(): Boolean {
        var minors = 0
        for (v in board) {
            when (kotlin.math.abs(v)) {
                P, R, Q -> return false
                N, B -> minors++
            }
        }
        return minors <= 1
    }

    fun repetitions() = positions.count { it == positions.last() }

    enum class Status { PLAYING, CHECKMATE, STALEMATE, DRAW_50, DRAW_REPETITION, DRAW_MATERIAL }

    fun status(): Status {
        if (legalMoves().isEmpty()) return if (inCheck()) Status.CHECKMATE else Status.STALEMATE
        if (halfmove >= 100) return Status.DRAW_50
        if (repetitions() >= 3) return Status.DRAW_REPETITION
        if (insufficientMaterial()) return Status.DRAW_MATERIAL
        return Status.PLAYING
    }

    // ------------------------------------------------------------------ AI
    private fun endgame(): Boolean {
        var mat = 0
        for (v in board) if (kotlin.math.abs(v) in 2..5) mat += VALUE[kotlin.math.abs(v)]
        return mat <= 1400
    }

    /** Static evaluation from the side to move's perspective. */
    fun evaluate(): Int {
        val eg = endgame()
        var score = 0
        for (sq in 0 until 64) {
            val v = board[sq]
            if (v == 0) continue
            val a = kotlin.math.abs(v)
            val idx = if (v > 0) sq else (7 - sq / 8) * 8 + sq % 8
            val pst = if (a == K && eg) PST_K_END[idx] else PST[a][idx]
            val s = VALUE[a] + pst
            score += if (v > 0) s else -s
        }
        return score * side
    }

    var nodes = 0
    private var deadline = 0L

    private fun order(moves: List<Int>): List<Int> = moves.sortedByDescending { m ->
        var s = 0
        if (flags(m) and F_CAPTURE != 0) s += 10 * VALUE[kotlin.math.abs(board[toSq(m)]).coerceAtLeast(1)] - VALUE[kotlin.math.abs(board[fromSq(m)])] / 10
        if (promo(m) != 0) s += VALUE[promo(m)]
        s
    }

    private fun quiesce(alpha0: Int, beta: Int, depth: Int): Int {
        nodes++
        var alpha = alpha0
        val stand = evaluate()
        if (stand >= beta) return beta
        if (stand > alpha) alpha = stand
        if (depth <= 0) return alpha
        val s = side
        for (m in order(pseudoMoves(capturesOnly = true))) {
            make(m)
            if (inCheck(s)) { unmake(); continue }
            val score = -quiesce(-beta, -alpha, depth - 1)
            unmake()
            if (score >= beta) return beta
            if (score > alpha) alpha = score
        }
        return alpha
    }

    private fun negamax(depth: Int, alpha0: Int, beta: Int, ply: Int): Int {
        if (System.currentTimeMillis() > deadline) return 0
        if (depth <= 0) return quiesce(alpha0, beta, 4)
        nodes++
        var alpha = alpha0
        val s = side
        var legal = 0
        for (m in order(pseudoMoves())) {
            make(m)
            if (inCheck(s)) { unmake(); continue }
            legal++
            val score = -negamax(depth - 1, -beta, -alpha, ply + 1)
            unmake()
            if (score >= beta) return beta
            if (score > alpha) alpha = score
        }
        if (legal == 0) return if (inCheck(s)) -100000 + ply else 0
        if (halfmove >= 100) return 0
        return alpha
    }

    /**
     * Picks a move for the side to move. [depth] is the maximum search depth, [noise] adds random
     * centipawns to root scores to make weaker levels play more humanly.
     */
    fun bestMove(depth: Int, timeMs: Long, noise: Int, rnd: Random): Int {
        val root = legalMoves()
        if (root.isEmpty()) return 0
        if (root.size == 1) return root[0]
        deadline = System.currentTimeMillis() + timeMs
        nodes = 0
        var best = root[0]
        var ordered = order(root)
        for (d in 1..depth) {
            var bestScore = Int.MIN_VALUE
            var bestThis = ordered[0]
            val scores = HashMap<Int, Int>()
            var alpha = -1_000_000
            for (m in ordered) {
                make(m)
                // avoid walking into a threefold repetition when ahead
                val rep = positions.count { it == key() } >= 2
                var score = if (rep) 0 else -negamax(d - 1, -1_000_000, -alpha + (if (noise > 0) noise else 0), 1)
                unmake()
                if (noise > 0) score += rnd.nextInt(-noise, noise + 1)
                scores[m] = score
                if (score > bestScore) { bestScore = score; bestThis = m }
                if (noise == 0 && score > alpha) alpha = score
                if (System.currentTimeMillis() > deadline) break
            }
            if (System.currentTimeMillis() > deadline && d > 1) break
            best = bestThis
            ordered = ordered.sortedByDescending { scores[it] ?: Int.MIN_VALUE }
            if (bestScore > 90000) break
        }
        return best
    }

    /** Standard algebraic-ish notation for display, e.g. "Nf3", "exd5", "O-O", "e8=Q". */
    fun notation(m: Int): String {
        val f = fromSq(m); val t = toSq(m)
        val piece = kotlin.math.abs(board[f])
        val sqName = { s: Int -> "${'a' + s % 8}${8 - s / 8}" }
        if (flags(m) and F_CASTLE != 0) return if (t > f) "O-O" else "O-O-O"
        val cap = flags(m) and F_CAPTURE != 0
        val sb = StringBuilder()
        if (piece == P) { if (cap) sb.append('a' + f % 8).append('x') }
        else { sb.append(" PNBRQK"[piece]); if (cap) sb.append('x') }
        sb.append(sqName(t))
        if (promo(m) != 0) sb.append('=').append(" PNBRQK"[promo(m)])
        return sb.toString()
    }
}
