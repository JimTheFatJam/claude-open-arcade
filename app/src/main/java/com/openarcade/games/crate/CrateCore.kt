package com.openarcade.games.crate

/** A crate-pushing level parsed from text (see [CrateLevels]). Holds only the static layout. */
class CrateLevel(val rows: List<String>) {
    val w = rows.maxOf { it.length }
    val h = rows.size
    val wall = BooleanArray(w * h)
    val goal = BooleanArray(w * h)
    /** Floor reachable from the player's start: everything else is outside the level. */
    val inside = BooleanArray(w * h)
    val startBoxes: IntArray
    val startPlayer: Int
    /** Floor cells from which a crate can never reach any goal (corners, dead walls). */
    val dead = BooleanArray(w * h)

    init {
        val boxes = ArrayList<Int>()
        var player = -1
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            when (rows[y].getOrElse(x) { ' ' }) {
                '#' -> wall[i] = true
                '.' -> goal[i] = true
                '$' -> boxes.add(i)
                '*' -> { boxes.add(i); goal[i] = true }
                '@' -> player = i
                '+' -> { player = i; goal[i] = true }
            }
        }
        require(player >= 0) { "level has no player" }
        startBoxes = boxes.sorted().toIntArray()
        startPlayer = player
        val stack = ArrayList<Int>(); stack.add(player); inside[player] = true
        while (stack.isNotEmpty()) {
            val c = stack.removeAt(stack.size - 1)
            for (d in 0 until 4) {
                val n = step(c, d)
                if (n >= 0 && !wall[n] && !inside[n]) { inside[n] = true; stack.add(n) }
            }
        }
        require(startBoxes.size == goal.count { it }) { "crates and goals differ" }
        // live squares: pull a lone crate backwards from every goal
        val live = BooleanArray(w * h)
        val q = ArrayDeque<Int>()
        for (i in goal.indices) if (goal[i]) { live[i] = true; q.add(i) }
        while (q.isNotEmpty()) {
            val b = q.removeFirst()
            for (d in 0 until 4) {
                val to = step(b, d); val pl = if (to >= 0) step(to, d) else -1
                if (to >= 0 && pl >= 0 && floor(to) && floor(pl) && !live[to]) { live[to] = true; q.add(to) }
            }
        }
        for (i in 0 until w * h) dead[i] = inside[i] && !live[i]
    }

    fun floor(i: Int) = i >= 0 && inside[i] && !wall[i]

    /** Neighbour of cell [i] in direction [d] (0 right, 1 left, 2 down, 3 up) or -1 off the map. */
    fun step(i: Int, d: Int): Int {
        val x = i % w + DX[d]; val y = i / w + DY[d]
        return if (x in 0 until w && y in 0 until h) y * w + x else -1
    }

    /** Fewest pushes to solve (breadth-first over crate layouts), or -1 when unsolvable within [cap] states. */
    fun minPushes(cap: Int = 400_000): Int {
        data class K(val boxes: List<Int>, val reach: Int)
        fun key(boxes: IntArray, player: Int): K {
            val r = reachable(boxes, player)
            return K(boxes.toList(), r.indices.first { r[it] })
        }
        val start = startBoxes.copyOf()
        val seen = HashSet<K>(); val q = ArrayDeque<Triple<IntArray, Int, Int>>()
        seen.add(key(start, startPlayer)); q.add(Triple(start, startPlayer, 0))
        while (q.isNotEmpty()) {
            val (bx, pl, n) = q.removeFirst()
            if (bx.all { goal[it] }) return n
            val r = reachable(bx, pl)
            for ((k, b) in bx.withIndex()) for (d in 0 until 4) {
                val from = step(b, d xor 1); val to = step(b, d)
                if (from < 0 || !r[from] || !floor(to) || dead[to] || to in bx) continue
                val nb = bx.copyOf().also { it[k] = to; it.sort() }
                if (seen.add(key(nb, b))) q.add(Triple(nb, b, n + 1))
                if (seen.size > cap) return -1
            }
        }
        return -1
    }

    /** Cells the player can walk to without pushing anything. */
    fun reachable(boxes: IntArray, player: Int): BooleanArray {
        val r = BooleanArray(w * h)
        val st = ArrayList<Int>(); st.add(player); r[player] = true
        while (st.isNotEmpty()) {
            val c = st.removeAt(st.size - 1)
            for (d in 0 until 4) {
                val n = step(c, d)
                if (floor(n) && !r[n] && n !in boxes) { r[n] = true; st.add(n) }
            }
        }
        return r
    }

    companion object {
        val DX = intArrayOf(1, -1, 0, 0)
        val DY = intArrayOf(0, 0, 1, -1)
    }
}

/** Outcome of one step. */
enum class CrateMove { BLOCKED, WALKED, PUSHED }

/** The changing part of a round: player and crate positions, with full undo. */
class CrateState(val level: CrateLevel) {
    var player = level.startPlayer; private set
    var boxes = level.startBoxes.copyOf(); private set
    var moves = 0; private set
    var pushes = 0; private set
    /** Facing direction of the player sprite. */
    var facing = 2
    private val history = ArrayList<IntArray>()   // [player, moves, pushes, facing, boxes...]

    val canUndo get() = history.isNotEmpty()
    val solved get() = boxes.all { level.goal[it] }
    fun boxAt(i: Int) = boxes.indexOf(i)

    /** One step in direction [d]; pushes a single crate if it is free to move. Two crates in a row never move. */
    fun move(d: Int): CrateMove {
        facing = d
        val n = level.step(player, d)
        if (!level.floor(n)) return CrateMove.BLOCKED
        val b = boxAt(n)
        if (b >= 0) {
            val beyond = level.step(n, d)
            if (!level.floor(beyond) || boxAt(beyond) >= 0) return CrateMove.BLOCKED
            snapshot()
            boxes[b] = beyond
            player = n; moves++; pushes++
            return CrateMove.PUSHED
        }
        snapshot()
        player = n; moves++
        return CrateMove.WALKED
    }

    private fun snapshot() { history.add(intArrayOf(player, moves, pushes, facing) + boxes) }

    fun undo(): Boolean {
        val s = history.removeLastOrNull() ?: return false
        player = s[0]; moves = s[1]; pushes = s[2]; facing = s[3]
        boxes = s.copyOfRange(4, s.size)
        return true
    }

    fun reset() {
        player = level.startPlayer; boxes = level.startBoxes.copyOf(); moves = 0; pushes = 0; facing = 2; history.clear()
    }

    /** Crates that can provably never reach a goal any more: on a dead square, or frozen in a 2x2 jam off goal. */
    fun stuckCrates(): Set<Int> {
        val out = HashSet<Int>()
        for (b in boxes) if (level.dead[b]) out.add(b)
        val w = level.w
        for (b in boxes) {
            // every 2x2 square containing b made only of walls and crates is frozen
            for ((ox, oy) in listOf(0 to 0, -1 to 0, 0 to -1, -1 to -1)) {
                val x0 = b % w + ox; val y0 = b / w + oy
                if (x0 < 0 || y0 < 0 || x0 + 1 >= w || y0 + 1 >= level.h) continue
                val cells = listOf(y0 * w + x0, y0 * w + x0 + 1, (y0 + 1) * w + x0, (y0 + 1) * w + x0 + 1)
                if (cells.all { level.wall[it] || boxAt(it) >= 0 }) {
                    val jam = cells.filter { boxAt(it) >= 0 }
                    if (jam.any { !level.goal[it] }) out.addAll(jam.filter { !level.goal[it] })
                }
            }
        }
        return out
    }

    /** Shortest walking route (no pushes) to [target], as directions; null when unreachable. */
    fun pathTo(target: Int): List<Int>? {
        if (!level.floor(target) || boxAt(target) >= 0) return null
        val prev = IntArray(level.w * level.h) { -2 }
        val q = ArrayDeque<Int>(); q.add(player); prev[player] = -1
        while (q.isNotEmpty()) {
            val c = q.removeFirst()
            if (c == target) break
            for (d in 0 until 4) {
                val n = level.step(c, d)
                if (level.floor(n) && prev[n] == -2 && boxAt(n) < 0) { prev[n] = c; q.add(n) }
            }
        }
        if (prev[target] == -2) return null
        val path = ArrayList<Int>()
        var c = target
        while (c != player) {
            val p = prev[c]
            path.add((0 until 4).first { level.step(p, it) == c })
            c = p
        }
        return path.reversed()
    }

    fun restore(player: Int, boxes: IntArray, moves: Int, pushes: Int) {
        this.player = player; this.boxes = boxes.copyOf(); this.moves = moves; this.pushes = pushes; history.clear()
    }
}
