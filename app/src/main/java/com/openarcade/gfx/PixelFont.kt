package com.openarcade.gfx

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF

/**
 * A hand-drawn 5x7 monospaced bitmap font. Glyphs are rendered from a tiny atlas bitmap with
 * filtering disabled, so text stays perfectly pixel-crisp at any scale.
 *
 * Special glyphs: \u0001 heart, \u0002 star, \u0003 left, \u0004 right, \u0005 up, \u0006 down.
 */
object PixelFont {
    const val GLYPH_W = 5
    const val GLYPH_H = 7
    const val ADVANCE = 6
    const val LINE = 10

    const val HEART = '\u0001'
    const val STAR = '\u0002'
    const val LEFT = '\u0003'
    const val RIGHT = '\u0004'
    const val UP = '\u0005'
    const val DOWN = '\u0006'

    private val glyphs: Map<Char, String> = mapOf(
        'A' to ".###.#...##...#######...##...##...#",
        'B' to "####.#...##...#####.#...##...#####.",
        'C' to ".###.#...##....#....#....#...#.###.",
        'D' to "####.#...##...##...##...##...#####.",
        'E' to "######....#....####.#....#....#####",
        'F' to "######....#....####.#....#....#....",
        'G' to ".###.#...##....#.####...##...#.####",
        'H' to "#...##...##...#######...##...##...#",
        'I' to ".###...#....#....#....#....#...###.",
        'J' to "..###...#....#....#....##..#..##...",
        'K' to "#...##..#.#.#..##...#.#..#..#.#...#",
        'L' to "#....#....#....#....#....#....#####",
        'M' to "#...###.###.#.##.#.##...##...##...#",
        'N' to "#...##...###..##.#.##..###...##...#",
        'O' to ".###.#...##...##...##...##...#.###.",
        'P' to "####.#...##...#####.#....#....#....",
        'Q' to ".###.#...##...##...##.#.##..#..##.#",
        'R' to "####.#...##...#####.#.#..#..#.#...#",
        'S' to ".#####....#.....###.....#....#####.",
        'T' to "#####..#....#....#....#....#....#..",
        'U' to "#...##...##...##...##...##...#.###.",
        'V' to "#...##...##...##...##...#.#.#...#..",
        'W' to "#...##...##...##.#.##.#.##.#.#.#.#.",
        'X' to "#...##...#.#.#...#...#.#.#...##...#",
        'Y' to "#...##...#.#.#...#....#....#....#..",
        'Z' to "#####....#...#...#...#...#....#####",
        '0' to ".###.#...##..###.#.###..##...#.###.",
        '1' to "..#...##....#....#....#....#...###.",
        '2' to ".###.#...#....#...#...#...#...#####",
        '3' to "#####...#...#.....#.....##...#.###.",
        '4' to "...#...##..#.#.#..#.#####...#....#.",
        '5' to "######....####.....#....##...#.###.",
        '6' to "..##..#...#....####.#...##...#.###.",
        '7' to "#####....#...#...#...#....#....#...",
        '8' to ".###.#...##...#.###.#...##...#.###.",
        '9' to ".###.#...##...#.####....#...#..##..",
        '.' to "..........................##...##..",
        ',' to "....................##....#...#....",
        '!' to "..#....#....#....#....#.........#..",
        '?' to ".###.#...#....#...#...#.........#..",
        ':' to ".....##...##.........##...##.......",
        ';' to ".....##...##.........##....#...#...",
        '\'' to "..#....#...#.......................",
        '"' to ".#.#..#.#..........................",
        '-' to "...............###.................",
        '+' to ".......#....#..#####..#....#.......",
        '/' to "....#....#...#...#...#...#....#....",
        '(' to "...#...#...#....#....#.....#.....#.",
        ')' to ".#.....#.....#....#....#...#...#...",
        '%' to "##..###..#...#...#...#...#..###..##",
        '#' to ".#.#..#.#.#####.#.#.#####.#.#..#.#.",
        '*' to ".....#.#.#.###.#####.###.#.#.#.....",
        '<' to "...#...#...#...#.....#.....#.....#.",
        '>' to ".#.....#.....#.....#...#...#...#...",
        '=' to "..........#####.....#####..........",
        '_' to "..............................#####",
        '&' to ".##..#..#.#.#...#...#.#.##..#..##.#",
        '[' to ".###..#....#....#....#....#....###.",
        ']' to ".###....#....#....#....#....#..###.",
        '|' to "..#....#....#....#....#....#....#..",
        '$' to "..#...####.#.#..###...#.#.####...#.",
        '@' to ".###.#...##.###.#.##.###.....#.###.",
        HEART to "......#.#.##########.###...#.......",
        STAR to "..#....#..#####.###..###..#.#.#...#",
        LEFT to "...#...##..###.####..###...##....#.",
        RIGHT to ".#....##...###..####.###..##...#...",
        UP to ".......#...###.##########..........",
        DOWN to "..........##########.###...#.......",
    )

    private val order: String = glyphs.keys.joinToString("")
    private val index = HashMap<Char, Int>().apply { order.forEachIndexed { i, ch -> put(ch, i) } }
    private var atlas: Bitmap? = null
    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val filters = HashMap<Int, PorterDuffColorFilter>()
    private val src = Rect()
    private val dst = RectF()

    private fun atlas(): Bitmap {
        atlas?.let { return it }
        val bmp = Bitmap.createBitmap(order.length * GLYPH_W, GLYPH_H, Bitmap.Config.ARGB_8888)
        order.forEachIndexed { gi, ch ->
            val data = glyphs[ch]!!
            for (i in 0 until GLYPH_W * GLYPH_H) {
                if (i < data.length && data[i] == '#') {
                    bmp.setPixel(gi * GLYPH_W + i % GLYPH_W, i / GLYPH_W, 0xFFFFFFFF.toInt())
                }
            }
        }
        atlas = bmp
        return bmp
    }

    private fun filter(color: Int): PorterDuffColorFilter =
        filters.getOrPut(color) { PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN) }

    private fun normalize(ch: Char): Char {
        val up = ch.uppercaseChar()
        return if (index.containsKey(up)) up else if (ch == ' ') ' ' else '?'
    }

    /** Width in screen pixels of [text] drawn at [size] (screen pixels per font pixel). */
    fun width(text: String, size: Float): Float =
        if (text.isEmpty()) 0f else (text.length * ADVANCE - 1) * size

    fun height(size: Float): Float = GLYPH_H * size

    /** Draws [text] with its top-left at (x, y). */
    fun draw(c: Canvas, text: String, x: Float, y: Float, size: Float, color: Int) {
        val bmp = atlas()
        // opaque filter colour; transparency comes from the paint alpha so it isn't applied twice
        paint.colorFilter = filter(color or 0xFF000000.toInt())
        paint.alpha = (color ushr 24)
        var cx = x
        for (raw in text) {
            val ch = normalize(raw)
            if (ch != ' ') {
                val gi = index[ch] ?: index['?']!!
                src.set(gi * GLYPH_W, 0, gi * GLYPH_W + GLYPH_W, GLYPH_H)
                dst.set(cx, y, cx + GLYPH_W * size, y + GLYPH_H * size)
                c.drawBitmap(bmp, src, dst, paint)
            }
            cx += ADVANCE * size
        }
    }

    /** Greedy word wrap to at most [maxChars] characters per line; honours explicit '\n'. */
    fun wrap(text: String, maxChars: Int): List<String> {
        val out = ArrayList<String>()
        val limit = maxChars.coerceAtLeast(4)
        for (para in text.split('\n')) {
            if (para.isEmpty()) { out.add(""); continue }
            var line = StringBuilder()
            for (word in para.split(' ')) {
                if (word.isEmpty()) continue
                var w = word
                while (w.length > limit) {
                    if (line.isNotEmpty()) { out.add(line.toString()); line = StringBuilder() }
                    out.add(w.substring(0, limit)); w = w.substring(limit)
                }
                if (line.isEmpty()) line.append(w)
                else if (line.length + 1 + w.length <= limit) line.append(' ').append(w)
                else { out.add(line.toString()); line = StringBuilder(w) }
            }
            out.add(line.toString())
        }
        return out
    }
}
