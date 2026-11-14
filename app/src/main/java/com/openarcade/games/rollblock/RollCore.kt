package com.openarcade.games.rollblock

/** How the block rests: upright on one tile, or lying across two tiles along X or Y. */
enum class Orient { STAND, LIE_X, LIE_Y }

/**
 * Block position: (x, y) is the top-left tile it covers. Lying along X it covers (x, y) and
 * (x + 1, y); lying along Y it covers (x, y) and (x, y + 1).
 */
data class BlockPos(val x: Int, val y: Int, val o: Orient) {
    /** Tiles covered, as (x, y) pairs packed into a list of two-element int arrays. */
    fun cells(): List<IntArray> = when (o) {
        Orient.STAND -> listOf(intArrayOf(x, y))
        Orient.LIE_X -> listOf(intArrayOf(x, y), intArrayOf(x + 1, y))
        Orient.LIE_Y -> listOf(intArrayOf(x, y), intArrayOf(x, y + 1))
    }

    /** Rolls the block one quarter turn over the edge in direction (dx, dy). */
    fun roll(dx: Int, dy: Int): BlockPos = when (o) {
        Orient.STAND -> when {
            dx > 0 -> BlockPos(x + 1, y, Orient.LIE_X)
            dx < 0 -> BlockPos(x - 2, y, Orient.LIE_X)
            dy > 0 -> BlockPos(x, y + 1, Orient.LIE_Y)
            else -> BlockPos(x, y - 2, Orient.LIE_Y)
        }
        Orient.LIE_X -> when {
            dx > 0 -> BlockPos(x + 2, y, Orient.STAND)
            dx < 0 -> BlockPos(x - 1, y, Orient.STAND)
            else -> BlockPos(x, y + dy, Orient.LIE_X)
        }
        Orient.LIE_Y -> when {
            dy > 0 -> BlockPos(x, y + 2, Orient.STAND)
            dy < 0 -> BlockPos(x, y - 1, Orient.STAND)
            else -> BlockPos(x + dx, y, Orient.LIE_Y)
        }
    }
}

/** Tile kinds. Bridges belong to a group (0..4) that switches open and close. */
object Tile {
    const val VOID = 0
    const val FLOOR = 1
    const val GOAL = 2
    const val FRAGILE = 3
    const val SOFT = 4      // round switch: pressed by any part of the block
    const val HEAVY = 5     // X switch: pressed only by the block standing upright on it
    const val BRIDGE = 6
}

/** Result of one roll. */
enum class RollEvent { MOVED, FELL, WON }

/** Full game state: block position plus a bitmask of the open bridge groups. */
data class RollState(val pos: BlockPos, val bridges: Int)

/**
 * One level. Text format (one string per row):
 *  '.' void, '#' floor, 'S' start (floor, block upright), 'G' goal hole, '=' fragile tile,
 *  'A'..'E' bridge of group A..E (starts closed), 'a'..'e' round switch toggling that group,
 *  '1'..'5' X switch toggling group A..E (only when the block stands on it).
 */
class RollLevel(val rows: List<String>) {
    val w = rows.maxOf { it.length }
    val h = rows.size
    val tiles = IntArray(w * h)
    /** Group index for bridges and switches, -1 otherwise. */
    val group = IntArray(w * h) { -1 }
    val start: BlockPos

    init {
        var s: BlockPos? = null
        for (y in 0 until h) for (x in 0 until w) {
            val ch = rows[y].getOrElse(x) { '.' }
            val i = y * w + x
            tiles[i] = when (ch) {
                '#' -> Tile.FLOOR
                'S' -> { s = BlockPos(x, y, Orient.STAND); Tile.FLOOR }
                'G' -> Tile.GOAL
                '=' -> Tile.FRAGILE
                in 'A'..'E' -> { group[i] = ch - 'A'; Tile.BRIDGE }
                in 'a'..'e' -> { group[i] = ch - 'a'; Tile.SOFT }
                in '1'..'5' -> { group[i] = ch - '1'; Tile.HEAVY }
                else -> Tile.VOID
            }
        }
        start = requireNotNull(s) { "level has no start" }
    }

    val startState get() = RollState(start, 0)

    fun tile(x: Int, y: Int) = if (x in 0 until w && y in 0 until h) tiles[y * w + x] else Tile.VOID
    fun groupAt(x: Int, y: Int) = if (x in 0 until w && y in 0 until h) group[y * w + x] else -1
    val usesFragile get() = tiles.any { it == Tile.FRAGILE }
    val usesSwitches get() = tiles.any { it == Tile.SOFT || it == Tile.HEAVY }

    /** Is the tile solid ground under the given bridge state? */
    fun solid(x: Int, y: Int, bridges: Int): Boolean = when (tile(x, y)) {
        Tile.VOID -> false
        Tile.BRIDGE -> (bridges shr groupAt(x, y)) and 1 == 1
        else -> true
    }

    /** Would the block stay on the board? Every covered tile must be solid; fragile tiles break under an upright block. */
    fun supported(p: BlockPos, bridges: Int): Boolean {
        for (c in p.cells()) {
            if (!solid(c[0], c[1], bridges)) return false
            if (p.o == Orient.STAND && tile(c[0], c[1]) == Tile.FRAGILE) return false
        }
        return true
    }

    /** Applies one roll. Returns the new state (the fall position when it falls) and what happened. */
    fun move(s: RollState, dx: Int, dy: Int): Pair<RollState, RollEvent> {
        val np = s.pos.roll(dx, dy)
        if (!supported(np, s.bridges)) return RollState(np, s.bridges) to RollEvent.FELL
        var br = s.bridges
        for (c in np.cells()) {
            val t = tile(c[0], c[1])
            if (t == Tile.SOFT || (t == Tile.HEAVY && np.o == Orient.STAND)) br = br xor (1 shl groupAt(c[0], c[1]))
        }
        val ns = RollState(np, br)
        // a switch can close a bridge the other half of the block is resting on
        if (!supported(np, br)) return ns to RollEvent.FELL
        if (np.o == Orient.STAND && tile(np.x, np.y) == Tile.GOAL) return ns to RollEvent.WON
        return ns to RollEvent.MOVED
    }

    /** Fewest rolls to the goal (breadth-first search over position and bridge states), or -1. */
    fun par(): Int = solve()?.size ?: -1

    /** Shortest solution as a list of directions (0 right, 1 left, 2 down, 3 up), or null. */
    fun solve(): List<Int>? {
        val prev = HashMap<RollState, Pair<RollState, Int>?>()
        val q = ArrayDeque<RollState>()
        prev[startState] = null; q.add(startState)
        while (q.isNotEmpty()) {
            val u = q.removeFirst()
            for (d in 0 until 4) {
                val (v, ev) = move(u, DX[d], DY[d])
                if (ev == RollEvent.FELL || prev.containsKey(v)) continue
                prev[v] = u to d
                if (ev == RollEvent.WON) {
                    val path = ArrayList<Int>()
                    var k: RollState? = v
                    while (true) { val pr = prev[k] ?: break; path.add(pr.second); k = pr.first }
                    return path.reversed()
                }
                q.add(v)
            }
        }
        return null
    }

    companion object {
        val DX = intArrayOf(1, -1, 0, 0)
        val DY = intArrayOf(0, 0, 1, -1)
    }
}
