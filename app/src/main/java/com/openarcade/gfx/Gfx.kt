package com.openarcade.gfx

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

enum class Align { LEFT, CENTER, RIGHT }

/** Low level pixel-style drawing helpers shared by the UI and every game. */
object Gfx {
    val paint = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }
    private val r = RectF()

    fun rect(c: Canvas, l: Float, t: Float, rr: Float, b: Float, color: Int) {
        paint.color = color
        c.drawRect(l, t, rr, b, paint)
    }

    fun rect(c: Canvas, box: RectF, color: Int) {
        paint.color = color
        c.drawRect(box, paint)
    }

    /** Outline made of solid bars, [t] thick, drawn inside [box]. */
    fun frame(c: Canvas, box: RectF, t: Float, color: Int) {
        rect(c, box.left, box.top, box.right, box.top + t, color)
        rect(c, box.left, box.bottom - t, box.right, box.bottom, color)
        rect(c, box.left, box.top, box.left + t, box.bottom, color)
        rect(c, box.right - t, box.top, box.right, box.bottom, color)
    }

    /**
     * Classic 8-bit panel: notched corners, a coloured border and a drop shadow. [p] is the size of
     * one "art pixel" on screen.
     */
    fun panel(c: Canvas, box: RectF, fill: Int, border: Int, p: Float, shadow: Boolean = true) {
        val l = box.left; val t = box.top; val rr = box.right; val b = box.bottom
        if (shadow) {
            rect(c, l + p * 2, b, rr + p, b + p, Pal.withAlpha(Pal.BLACK, 160))
            rect(c, rr, t + p * 2, rr + p, b, Pal.withAlpha(Pal.BLACK, 160))
        }
        // border (notched)
        rect(c, l + p, t, rr - p, b, border)
        rect(c, l, t + p, rr, b - p, border)
        // fill
        rect(c, l + p, t + p * 2, rr - p, b - p * 2, fill)
        rect(c, l + p * 2, t + p, rr - p * 2, b - p, fill)
    }

    /** Bevelled block, used for buttons, tiles and pieces. */
    fun bevel(c: Canvas, box: RectF, fill: Int, p: Float, pressed: Boolean = false) {
        val hi = if (pressed) Pal.darker(fill, 0.3f) else Pal.lighter(fill, 0.35f)
        val lo = if (pressed) Pal.lighter(fill, 0.15f) else Pal.darker(fill, 0.4f)
        rect(c, box, fill)
        rect(c, box.left, box.top, box.right, box.top + p, hi)
        rect(c, box.left, box.top, box.left + p, box.bottom, hi)
        rect(c, box.left, box.bottom - p, box.right, box.bottom, lo)
        rect(c, box.right - p, box.top, box.right, box.bottom, lo)
    }

    fun text(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
        align: Align = Align.LEFT, shadow: Int? = Pal.BLACK,
    ) {
        val w = PixelFont.width(s, size)
        val lx = when (align) {
            Align.LEFT -> x
            Align.CENTER -> x - w / 2
            Align.RIGHT -> x - w
        }
        // dark text never gets a black drop shadow (it just looks smudged)
        if (shadow != null && luminance(color) > 0.25f) PixelFont.draw(c, s, lx + size, y + size, size, shadow)
        PixelFont.draw(c, s, lx, y, size, color)
    }

    private fun luminance(c: Int): Float =
        (0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)) / 255f

    /** Text vertically centred on [cy]. */
    fun textMid(c: Canvas, s: String, x: Float, cy: Float, size: Float, color: Int, align: Align = Align.CENTER, shadow: Int? = Pal.BLACK) =
        text(c, s, x, cy - PixelFont.height(size) / 2, size, color, align, shadow)

    /** Largest text size (a multiple of 0.5) such that [s] fits into [maxW]. */
    fun fitSize(s: String, maxW: Float, preferred: Float): Float {
        var size = preferred
        while (size > 1f && PixelFont.width(s, size) > maxW) size -= 0.5f
        return size
    }

    /** Wrapped paragraph; returns the y just below the last line. */
    fun paragraph(c: Canvas, s: String, x: Float, y: Float, maxW: Float, size: Float, color: Int, align: Align = Align.LEFT): Float {
        val chars = (maxW / (PixelFont.ADVANCE * size)).toInt()
        var yy = y
        for (line in PixelFont.wrap(s, chars)) {
            val lx = when (align) { Align.LEFT -> x; Align.CENTER -> x + maxW / 2; Align.RIGHT -> x + maxW }
            text(c, line, lx, yy, size, color, align, Pal.withAlpha(Pal.BLACK, 200))
            yy += PixelFont.LINE * size
        }
        return yy
    }

    /** Like [paragraph] but stops before [maxY], ending with "..." if text was cut. */
    fun paragraphClamped(c: Canvas, s: String, x: Float, y: Float, maxW: Float, size: Float, color: Int, maxY: Float) {
        val chars = (maxW / (PixelFont.ADVANCE * size)).toInt()
        val lines = PixelFont.wrap(s, chars)
        val step = PixelFont.LINE * size
        val fit = maxOf(1, ((maxY - y + (step - PixelFont.height(size))) / step).toInt())
        for ((i, line0) in lines.take(fit).withIndex()) {
            val line = if (i == fit - 1 && lines.size > fit) line0.take(maxOf(0, chars - 3)).trimEnd() + "..." else line0
            text(c, line, x, y + i * step, size, color, Align.LEFT, Pal.withAlpha(Pal.BLACK, 200))
        }
    }

    fun paragraphHeight(s: String, maxW: Float, size: Float): Float {
        val chars = (maxW / (PixelFont.ADVANCE * size)).toInt()
        return PixelFont.wrap(s, chars).size * PixelFont.LINE * size
    }

    /** Filled pixel circle approximated with horizontal spans (keeps the retro look). */
    fun disc(c: Canvas, cx: Float, cy: Float, radius: Float, color: Int, p: Float) {
        paint.color = color
        val steps = maxOf(2, (radius / p).toInt())
        for (i in -steps until steps) {
            val yy = (i + 0.5f) * p
            val half = kotlin.math.sqrt(maxOf(0f, radius * radius - yy * yy))
            val hs = kotlin.math.round(half / p) * p
            if (hs <= 0f) continue
            c.drawRect(cx - hs, cy + i * p, cx + hs, cy + (i + 1) * p, paint)
        }
    }

    fun line(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, t: Float, color: Int) {
        paint.color = color
        paint.strokeWidth = t
        c.drawLine(x0, y0, x1, y1, paint)
    }

    fun tmpRect(l: Float, t: Float, rr: Float, b: Float): RectF { r.set(l, t, rr, b); return r }
}
