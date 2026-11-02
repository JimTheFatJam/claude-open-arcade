package com.openarcade.gfx

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * A sprite described as rows of characters. Each character maps to a palette colour; '.' and ' '
 * are transparent. Sprites are rasterised once to a bitmap and drawn without filtering.
 */
class PixelArt(rows: List<String>, private val colors: Map<Char, Int> = DEFAULT_COLORS) {
    val rows: List<String> = rows
    val w: Int = rows.maxOf { it.length }
    val h: Int = rows.size
    private var bmp: Bitmap? = null

    constructor(vararg rows: String) : this(rows.toList())

    fun bitmap(): Bitmap {
        bmp?.let { return it }
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            val row = rows[y]
            for (x in row.indices) {
                val c = colors[row[x]] ?: continue
                b.setPixel(x, y, c)
            }
        }
        bmp = b
        return b
    }

    fun draw(c: Canvas, dst: RectF, alpha: Int = 255, tint: Int? = null) {
        paint.alpha = alpha
        paint.colorFilter = if (tint == null) null else tints.getOrPut(tint) {
            android.graphics.PorterDuffColorFilter(tint, android.graphics.PorterDuff.Mode.MULTIPLY)
        }
        c.drawBitmap(bitmap(), null, dst, paint)
    }

    /** Draws the sprite centred inside [box], scaled by the largest whole factor that fits. */
    fun drawFit(c: Canvas, box: RectF, alpha: Int = 255, tint: Int? = null) {
        val s = maxOf(1f, kotlin.math.floor(minOf(box.width() / w, box.height() / h)))
        val fs = if (s * w > box.width() || s * h > box.height()) minOf(box.width() / w, box.height() / h) else s
        val dw = w * fs
        val dh = h * fs
        tmp.set(box.centerX() - dw / 2, box.centerY() - dh / 2, box.centerX() + dw / 2, box.centerY() + dh / 2)
        draw(c, tmp, alpha, tint)
    }

    fun drawAt(c: Canvas, x: Float, y: Float, scale: Float, alpha: Int = 255) {
        tmp.set(x, y, x + w * scale, y + h * scale)
        draw(c, tmp, alpha)
    }

    companion object {
        private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
        private val tmp = RectF()
        private val tints = HashMap<Int, android.graphics.PorterDuffColorFilter>()

        /** Shared character → colour mapping used by most sprites. */
        val DEFAULT_COLORS: Map<Char, Int> = mapOf(
            'K' to Pal.BLACK, 'k' to Pal.BG0, 'n' to Pal.BG1, 'N' to Pal.BG2,
            'W' to Pal.WHITE, 'l' to Pal.LGREY, 'g' to Pal.GREY, 'd' to Pal.DGREY,
            'R' to Pal.RED, 'r' to Pal.DRED, 'O' to Pal.ORANGE, 'Y' to Pal.YELLOW,
            'G' to Pal.GREEN, 'D' to Pal.DGREEN, 'C' to Pal.CYAN, 'B' to Pal.BLUE,
            'b' to Pal.DBLUE, 'P' to Pal.PURPLE, 'p' to Pal.PINK, 'M' to Pal.BROWN,
            'm' to Pal.DBROWN, 'e' to Pal.PEACH, 't' to Pal.TAN,
        )
    }
}
