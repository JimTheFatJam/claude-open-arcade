package com.openarcade.games.nonogram

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
import com.openarcade.games.HudItem
import com.openarcade.games.ScoreKind
import com.openarcade.games.TutorialPage
import com.openarcade.games.drawBoardFrame
import com.openarcade.games.formatTime
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
import kotlin.math.floor

/** A picture puzzle: rows of characters, '.' empty, any other char a filled cell of that colour. */
class NonoPicture(val name: String, val rows: List<String>) {
    val size = rows.size
    fun filled(c: Int, r: Int) = rows[r][c] != '.'
}

object NonoLibrary {
    val SMALL = listOf(
        NonoPicture("HEART", listOf(".R.R.", "RRRRR", "RRRRR", ".RRR.", "..R..")),
        NonoPicture("HOUSE", listOf("..R..", ".RRR.", "RRRRR", ".WMW.", ".WMW.")),
        NonoPicture("ARROW", listOf("..W..", ".WWW.", "WWWWW", "..W..", "..W..")),
        NonoPicture("TREE", listOf("..G..", ".GGG.", "GGGGG", "..M..", "..M..")),
        NonoPicture("STAR", listOf("..Y..", "YYYYY", ".YYY.", ".Y.Y.", "Y...Y")),
        NonoPicture("SMILE", listOf(".YYY.", "Y.Y.Y", "YYYYY", "Y...Y", ".YYY.")),
        NonoPicture("MUSHROOM", listOf(".RRR.", "RWRWR", "RRRRR", ".WWW.", ".W.W.")),
        NonoPicture("GHOST", listOf(".PPP.", "P.P.P", "PPPPP", "PPPPP", "P.P.P")),
    )
    val MEDIUM = listOf(
        NonoPicture("CAT", listOf(
            "O........O", "OO......OO", "OOO....OOO", "OOOOOOOOOO", "OO.OOOO.OO",
            "OOOOOOOOOO", "OOOOppOOOO", "OOO.OO.OOO", ".OOOOOOOO.", "..OOOOOO..")),
        NonoPicture("ROCKET", listOf(
            "....RR....", "...RWWR...", "...WWWW...", "...WCCW...", "...WCCW...",
            "...WWWW...", "..RWWWWR..", ".RRWWWWRR.", ".R..OO..R.", "....OO....")),
        NonoPicture("MUSHROOM", listOf(
            "...RRRR...", ".RRWRRWRR.", "RRWWRRWWRR", "RRRRRRRRRR", "RWRRRRRRWR",
            ".RRRRRRRR.", "...tttt...", "..tttttt..", "..tttttt..", "...tttt...")),
        NonoPicture("GHOST", listOf(
            "...PPPP...", "..PPPPPP..", ".PPPPPPPP.", ".PWWPPWWP.", ".PWBPPWBP.",
            "PPPPPPPPPP", "PPPPPPPPPP", "PPPPPPPPPP", "PPPPPPPPPP", "P.PP..PP.P")),
        NonoPicture("PINE TREE", listOf(
            "....GG....", "...GGGG...", "..GGGGGG..", "...GGGG...", "..GGGGGG..",
            ".GGGGGGGG.", "GGGGGGGGGG", "....MM....", "....MM....", "..MMMMMM..")),
        NonoPicture("MUSIC NOTE", listOf(
            "...BBBBBBB", "...BBBBBBB", "...B.....B", "...B.....B", "...B.....B",
            "...B.....B", ".BBB...BBB", "BBBB..BBBB", "BBBB..BBBB", ".BB....BB.")),
    )
    val LARGE = listOf(
        NonoPicture("FISH", listOf(
            "...............", "......BBBB.....", "....BBBBBBBB...", "...BBBBBBBBBB.B", "..BBWKBBBBBBBBB",
            ".BBBWWBBBBBBBBB", "BBBBBBBBBBBBBB.", "BBBBBBBBBBBBB..", ".BBBBBBBBBBBBB.", "..BBBBBBBBBBBBB",
            "...BBBBBBBBBB.B", "....BBBBBBBB...", "......BBBB.....", ".C.....C.......", "C.C............")),
        NonoPicture("CASTLE", listOf(
            "R.R.........R.R", "RRR.........RRR", "lll..l.l.l..lll", "lll..lllll..lll", "lll..lllll..lll",
            "lllllllllllllll", "lllllllllllllll", "ll.lllllllll.ll", "ll.lllllllll.ll", "lllllllllllllll",
            "llllll...llllll", "lllll.....lllll", "lllll.....lllll", "lllll.....lllll", "GGGGG.....GGGGG")),
        NonoPicture("SAILBOAT", listOf(
            ".......W.......", ".......WW......", ".......WWW.....", ".......WWWW....", "......RWWWWW...",
            ".....RRWWWWWW..", "....RRRWWWWWWW.", "...RRRRW.......", "..RRRRRW.......", ".......M.......",
            "MMMMMMMMMMMMMMM", ".MMMMMMMMMMMMM.", "..MMMMMMMMMMM..", "BBBBBBBBBBBBBBB", ".B.B.B.B.B.B.B.")),
    )

    fun forSize(n: Int) = when (n) { 5 -> SMALL; 10 -> MEDIUM; else -> LARGE }

    /** A random but tidy-looking picture used once the hand-made ones are all solved. */
    fun random(n: Int, rnd: kotlin.random.Random): NonoPicture {
        val g = Array(n) { BooleanArray(n) { rnd.nextFloat() < 0.55f } }
        // one smoothing pass to make blobs instead of noise
        val s = Array(n) { r -> BooleanArray(n) { c ->
            var k = 0; var t = 0
            for (dr in -1..1) for (dc in -1..1) { val rr = r + dr; val cc = c + dc; if (rr in 0 until n && cc in 0 until n) { t++; if (g[rr][cc]) k++ } }
            k * 2 > t || (k * 2 == t && g[r][c])
        } }
        val color = "RGBYOCPp"[rnd.nextInt(8)]
        return NonoPicture("MYSTERY", List(n) { r -> String(CharArray(n) { c -> if (s[r][c]) color else '.' }) })
    }

    fun clues(line: List<Boolean>): List<Int> {
        val out = ArrayList<Int>()
        var run = 0
        for (b in line) { if (b) run++ else if (run > 0) { out.add(run); run = 0 } }
        if (run > 0) out.add(run)
        return out
    }
}

class NonogramSession(host: GameHost) : GameSession(host) {
    private val n = when (host.difficulty) { Difficulty.EASY -> 5; Difficulty.NORMAL -> 10; Difficulty.HARD -> 15 }
    private lateinit var pic: NonoPicture
    private var level = 0
    private var marks = IntArray(n * n)  // 0 empty, 1 filled, 2 crossed
    private var hints = 0
    private var crossMode = false
    private lateinit var rowClues: List<List<Int>>
    private lateinit var colClues: List<List<Int>>
    private var t = 0f
    private var solvedT = -1f

    // layout
    private var cell = 0f
    private var ox = 0f
    private var oy = 0f
    private var clueCell = 0f

    // drag
    private var dragTarget = -1
    private var dragStart = -1
    private var dragAxis = 0   // 0 none, 1 row, 2 col

    override fun start(saved: JSONObject?) {
        if (saved != null) {
            level = saved.getInt("level")
            val rows = saved.getJSONArray("pic")
            pic = NonoPicture(saved.getString("name"), List(rows.length()) { rows.getString(it) })
            marks = saved.getJSONArray("marks").toIntArray()
            require(pic.size == n && pic.rows.all { it.length == n } && marks.size == n * n) { "save is for a different board size" }
            hints = saved.optInt("hints")
        } else {
            level = host.getInt("lvl_$n")
            val lib = NonoLibrary.forSize(n)
            pic = if (level < lib.size) lib[level] else NonoLibrary.random(n, host.rng)
        }
        rowClues = List(n) { r -> NonoLibrary.clues(List(n) { c -> pic.filled(c, r) }) }
        colClues = List(n) { c -> NonoLibrary.clues(List(n) { r -> pic.filled(c, r) }) }
    }

    override fun save(): JSONObject = JSONObject().put("level", level).put("name", pic.name)
        .put("pic", JSONArray(pic.rows)).put("marks", marks.toJsonArray()).put("hints", hints)

    override fun onLayout() {
        if (!::rowClues.isInitialized) return  // laid out again once the puzzle is loaded
        val p = px
        val k = maxOf(rowClues.maxOf { maxOf(1, it.size) }, colClues.maxOf { maxOf(1, it.size) })
        val ratio = 0.62f
        cell = floor(minOf((area.width() - p * 4) / (n + k * ratio), (area.height() - p * 4) / (n + k * ratio)))
        clueCell = floor(cell * ratio)
        val total = cell * n + clueCell * k
        ox = floor(area.centerX() - total / 2 + clueCell * k)
        oy = floor(area.centerY() - total / 2 + clueCell * k)
    }

    private fun lineDone(isRow: Boolean, idx: Int): Boolean {
        val line = List(n) { j -> if (isRow) marks[idx * n + j] == 1 else marks[j * n + idx] == 1 }
        return NonoLibrary.clues(line) == (if (isRow) rowClues[idx] else colClues[idx])
    }

    private fun solved() = (0 until n).all { lineDone(true, it) && lineDone(false, it) }

    private fun setCell(i: Int, v: Int) {
        if (marks[i] == v) return
        marks[i] = v
        host.sfx(if (v == 1) Sfx.PLACE else if (v == 2) Sfx.TICK else Sfx.CLICK)
        checkSolved()
    }

    private fun checkSolved() {
        if (!finished && solved()) {
            solvedT = 0f
            for (i in marks.indices) if (marks[i] == 2) marks[i] = 0
            host.putInt("lvl_$n", level + 1)
            host.unlock("first_win")
            if (n == 15) host.unlock("large")
            val total = host.getInt("solved") + 1
            host.putInt("solved", total)
            if (total >= 10) host.unlock("ten")
            win(detail = "IT'S A ${pic.name}!", delay = 2.2f)
        }
    }

    private fun hint() {
        if (finished) return
        val wrong = marks.indices.filter { (marks[it] == 1) != pic.filled(it % n, it / n) }
        if (wrong.isEmpty()) return
        val i = wrong.firstOrNull { marks[it] == 1 } ?: wrong[host.rng.nextInt(wrong.size)]
        hints++
        marks[i] = if (pic.filled(i % n, i / n)) 1 else 2
        host.sfx(Sfx.POWER)
        checkSolved()
    }

    override fun onTouch(e: MotionEvent) {
        val c = floor((e.x - ox) / cell).toInt()
        val r = floor((e.y - oy) / cell).toInt()
        val inside = c in 0 until n && r in 0 until n
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!inside) return
                val i = r * n + c
                dragStart = i; dragAxis = 0
                dragTarget = if (crossMode) (if (marks[i] == 2) 0 else 2) else (if (marks[i] == 1) 0 else 1)
                setCell(i, dragTarget)
                host.vibrate(10)
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragStart < 0 || finished) return
                val sc = dragStart % n; val sr = dragStart / n
                val cc = c.coerceIn(0, n - 1); val rr = r.coerceIn(0, n - 1)
                if (dragAxis == 0 && (cc != sc || rr != sr)) dragAxis = if (kotlin.math.abs(cc - sc) >= kotlin.math.abs(rr - sr)) 1 else 2
                if (dragAxis == 1) {
                    for (x in minOf(sc, cc)..maxOf(sc, cc)) { val i = sr * n + x; if (canPaint(i)) setCell(i, dragTarget) }
                } else if (dragAxis == 2) {
                    for (y in minOf(sr, rr)..maxOf(sr, rr)) { val i = y * n + sc; if (canPaint(i)) setCell(i, dragTarget) }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragStart = -1
        }
    }

    /** While dragging, only paint over cells that are empty or in the state we're clearing. */
    private fun canPaint(i: Int) = if (dragTarget == 0) marks[i] == (if (crossMode) 2 else 1) else marks[i] == 0

    override fun update(dt: Float) { t += dt; if (solvedT >= 0f) solvedT += dt }

    override fun hud() = listOf(
        HudItem("PUZZLE", "#${level + 1}"),
        HudItem("TIME", formatTime(host.playTime.toInt())),
        HudItem("HINTS", hints.toString(), Pal.YELLOW),
    )

    override fun actions() = listOf(
        GameAction("FILL", null, isActive = { !crossMode }) { crossMode = false },
        GameAction("CROSS", Icons.CLOSE, isActive = { crossMode }) { crossMode = true },
        GameAction("HINT", Icons.HINT) { hint() },
    )

    override fun draw(c: Canvas) {
        val p = px
        val board = RectF(ox, oy, ox + cell * n, oy + cell * n)
        drawBoardFrame(c, board, p, Pal.GREEN)
        val r = RectF()
        val reveal = solvedT >= 0f
        for (i in 0 until n * n) {
            val cx = i % n; val cy = i / n
            r.set(ox + cx * cell, oy + cy * cell, ox + (cx + 1) * cell, oy + (cy + 1) * cell)
            val shade = ((cx / 5) + (cy / 5)) % 2 == 0
            Gfx.rect(c, r, if (shade) Pal.BG1 else Pal.mix(Pal.BG1, Pal.BG2, 0.5f))
            when (marks[i]) {
                1 -> {
                    val delay = (cx + cy) * 0.04f
                    val col = if (reveal && solvedT > delay) (PixelArt.DEFAULT_COLORS[pic.rows[cy][cx]] ?: Pal.WHITE) else Pal.LGREY
                    val inset = if (reveal) 0f else maxOf(1f, cell * 0.06f)
                    r.inset(inset, inset)
                    if (reveal) Gfx.rect(c, r, col) else Gfx.bevel(c, r, col, maxOf(1f, cell / 10f))
                }
                2 -> {
                    val d = cell * 0.28f
                    Gfx.line(c, r.left + d, r.top + d, r.right - d, r.bottom - d, maxOf(1f, cell / 9f), Pal.RED)
                    Gfx.line(c, r.right - d, r.top + d, r.left + d, r.bottom - d, maxOf(1f, cell / 9f), Pal.RED)
                }
            }
        }
        if (!reveal) {
            val thin = maxOf(1f, p / 4)
            for (k in 1 until n) {
                val thick = k % 5 == 0
                val w = if (thick) maxOf(2f, p / 2) else thin
                val col = if (thick) Pal.GREEN else Pal.BG3
                Gfx.rect(c, ox + k * cell - w / 2, oy, ox + k * cell + w / 2, oy + cell * n, col)
                Gfx.rect(c, ox, oy + k * cell - w / 2, ox + cell * n, oy + k * cell + w / 2, col)
            }
        }
        // clues
        val ts = maxOf(1f, clueCell * 0.62f / PixelFont.GLYPH_H)
        for (rIdx in 0 until n) {
            val done = lineDone(true, rIdx)
            val clues = rowClues[rIdx].ifEmpty { listOf(0) }
            val y = oy + rIdx * cell + cell / 2
            if (rIdx % 2 == 0) Gfx.rect(c, ox - clueCell * clues.size - p, oy + rIdx * cell, ox - p, oy + (rIdx + 1) * cell, Pal.withAlpha(Pal.BG2, 120))
            clues.forEachIndexed { k, v ->
                val x = ox - p * 2 - (clues.size - 1 - k) * clueCell - clueCell / 2
                Gfx.textMid(c, v.toString(), x, y, if (v >= 10) ts * 0.7f else ts, if (done) Pal.DGREY else Pal.WHITE, shadow = null)
            }
        }
        for (cIdx in 0 until n) {
            val done = lineDone(false, cIdx)
            val clues = colClues[cIdx].ifEmpty { listOf(0) }
            val x = ox + cIdx * cell + cell / 2
            if (cIdx % 2 == 0) Gfx.rect(c, ox + cIdx * cell, oy - clueCell * clues.size - p, ox + (cIdx + 1) * cell, oy - p, Pal.withAlpha(Pal.BG2, 120))
            clues.forEachIndexed { k, v ->
                val y = oy - p * 2 - (clues.size - 1 - k) * clueCell - clueCell / 2
                Gfx.textMid(c, v.toString(), x, y, if (v >= 10) ts * 0.7f else ts, if (done) Pal.DGREY else Pal.WHITE, shadow = null)
            }
        }
        if (reveal) {
            val s = Gfx.fitSize(pic.name, cell * n, px * 1.5f)
            Gfx.text(c, pic.name, board.centerX(), board.bottom + p * 4, s, Pal.YELLOW, Align.CENTER)
        }
    }
}

val NonogramGame = GameDef(
    id = "nonogram",
    title = "NONOGRAM",
    category = Category.PUZZLE,
    tagline = "Use the number clues to paint a hidden pixel picture.",
    description = "Also known as Picross or Griddlers. The numbers beside each row and column tell you the lengths of filled runs in that line. " +
        "Solve them all to reveal a pixel-art picture. Hand-drawn pictures first, then endless mystery puzzles.",
    icon = PixelArt(
        "................",
        ".......W.W.W....",
        ".....W.W.W.W.W..",
        "................",
        "....DDDDDDDDDDDD",
        "W.W.DnnGGnnGGnnD",
        "....DnnGGnnGGnnD",
        "..W.DGGGGGGGGGGD",
        "....DGGGGGGGGGGD",
        "..W.DGGGGGGGGGGD",
        "....DGGGGGGGGGGD",
        "..W.DnnGGGGGGnnD",
        "....DnnGGGGGGnnD",
        "..W.DnnnnGGnnnnD",
        "....DnnnnGGnnnnD",
        "....DDDDDDDDDDDD",
    ),
    accent = Pal.GREEN,
    music = Music.PUZZLE,
    scoreKind = ScoreKind.BEST_TIME,
    difficultyLabels = listOf("5X5", "10X10", "15X15"),
    tutorial = listOf(
        TutorialPage("OBJECTIVE", "Fill in the correct squares to reveal a hidden picture. Every row and column has clues telling you how the filled squares are grouped.",
            PixelArt(
                ".R.R.",
                "RRRRR",
                "RRRRR",
                ".RRR.",
                "..R..",
            )),
        TutorialPage("READING CLUES", "A clue of \"3 1\" means: somewhere in this line there is a run of 3 filled squares, then at least one gap, then a run of 1. The order of the numbers is the order of the runs.",
            PixelArt(
                "WWW.W",
                ".....",
                "GGG.G",
            )),
        TutorialPage("CONTROLS", "TAP a square to fill it. DRAG along a row or column to fill several at once. Switch to CROSS mode to mark squares you know are empty with an X.", Icons.DRAG),
        TutorialPage("RULES", "A clue turns grey when its line matches. The puzzle is solved when every clue is satisfied - then the picture appears in full color! HINT fixes one square for you.", Icons.HINT),
        TutorialPage("TIPS", "Start with big clues: a 4 in a 5-wide row must cover the middle 3 squares. Lines with a 0 are completely empty. Use crosses generously - they make the next deduction easier.", Icons.BULB),
    ),
    achievements = listOf(
        AchievementDef("first_win", "PIXEL PAINTER", "Solve a Nonogram."),
        AchievementDef("large", "MASTERPIECE", "Solve a 15x15 Nonogram."),
        AchievementDef("ten", "GALLERY", "Solve 10 Nonograms."),
    ),
    factory = { NonogramSession(it) },
)
