package com.openarcade.ui

import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import com.openarcade.audio.Sfx
import com.openarcade.core.ArcadeApp
import com.openarcade.gfx.Align
import com.openarcade.gfx.Gfx
import com.openarcade.gfx.Pal
import com.openarcade.gfx.PixelArt
import kotlin.math.abs

/** A chunky pixel button. Text and/or an icon, bevelled, with a pressed state. */
open class Button(
    var label: String,
    var icon: PixelArt? = null,
    var color: Int = Pal.BLUE,
    var sfx: Sfx = Sfx.SELECT,
    var onClick: () -> Unit,
) {
    val rect = RectF()
    var pressed = false
    var enabled = true
    var visible = true
    var active = false        // toggled look (e.g. selected chip)
    var textColor = Pal.WHITE
    var textSize = 0f         // 0 = automatic

    fun set(l: Float, t: Float, r: Float, b: Float): Button { rect.set(l, t, r, b); return this }

    fun hit(x: Float, y: Float) = visible && enabled && rect.contains(x, y)

    open fun draw(c: Canvas, app: ArcadeApp) {
        if (!visible) return
        val p = app.p
        val base = when {
            !enabled -> Pal.DGREY
            active -> Pal.lighter(color, 0.15f)
            else -> color
        }
        val sink = if (pressed) p else 0f
        val r = Gfx.tmpRect(rect.left, rect.top + sink, rect.right, rect.bottom + sink)
        // drop shadow
        if (!pressed) Gfx.rect(c, rect.left + p, rect.bottom, rect.right + p, rect.bottom + p, Pal.withAlpha(Pal.BLACK, 170))
        Gfx.rect(c, r.left, r.top, r.right, r.bottom, Pal.BLACK)
        val inner = RectF(r.left + p, r.top + p, r.right - p, r.bottom - p)
        Gfx.bevel(c, inner, base, p, pressed)
        if (active) Gfx.frame(c, RectF(r.left, r.top, r.right, r.bottom), p, Pal.YELLOW)

        val ic = icon
        val ts = if (textSize > 0f) textSize else app.tBody
        val tc = if (enabled) textColor else Pal.GREY
        if (ic != null && label.isEmpty()) {
            val s = minOf(inner.width(), inner.height()) * 0.66f
            ic.drawFit(c, RectF(inner.centerX() - s / 2, inner.centerY() - s / 2, inner.centerX() + s / 2, inner.centerY() + s / 2), if (enabled) 255 else 110)
        } else if (ic != null) {
            val s = inner.height() * 0.56f
            val size = Gfx.fitSize(label, inner.width() - s - p * 6, ts)
            val tw = com.openarcade.gfx.PixelFont.width(label, size)
            val total = s + p * 2 + tw
            val sx = inner.centerX() - total / 2
            ic.drawFit(c, RectF(sx, inner.centerY() - s / 2, sx + s, inner.centerY() + s / 2), if (enabled) 255 else 110)
            Gfx.textMid(c, label, sx + s + p * 2, inner.centerY(), size, tc, Align.LEFT)
        } else {
            val size = Gfx.fitSize(label, inner.width() - p * 4, ts)
            Gfx.textMid(c, label, inner.centerX(), inner.centerY(), size, tc)
        }
    }
}

/**
 * Handles press/release for a group of buttons, optionally inside a vertically scrolled region.
 * A press that turns into a drag is cancelled so lists can scroll without accidental clicks.
 */
class ButtonSet(private val app: ArcadeApp) {
    val buttons = ArrayList<Button>()
    private var pressed: Button? = null
    private var downX = 0f
    private var downY = 0f

    fun clear() = buttons.clear()
    fun add(b: Button): Button { buttons.add(b); return b }

    fun draw(c: Canvas) { for (b in buttons) b.draw(c, app) }

    /** [scrollY] is the current scroll offset of the region (content y = screen y + scrollY). */
    fun touch(e: MotionEvent, scrollY: Float = 0f): Boolean {
        val x = e.x
        val y = e.y + scrollY
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                pressed = buttons.lastOrNull { it.hit(x, y) }
                pressed?.pressed = true
                return pressed != null
            }
            MotionEvent.ACTION_MOVE -> {
                val b = pressed ?: return false
                if (abs(e.x - downX) > app.p * 4 || abs(e.y - downY) > app.p * 4 || !b.rect.contains(x, y)) {
                    b.pressed = false; pressed = null
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val b = pressed ?: return false
                b.pressed = false; pressed = null
                if (b.hit(x, y)) {
                    app.audio.play(b.sfx)
                    b.onClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { pressed?.pressed = false; pressed = null }
        }
        return false
    }

    fun cancel() { pressed?.pressed = false; pressed = null }
}

/** Vertical drag-to-scroll with fling, for lists longer than the screen. */
class Scroller(private val app: ArcadeApp) {
    var offset = 0f
    var maxOffset = 0f
    private var vel = 0f
    private var dragging = false
    private var lastY = 0f
    private var downY = 0f
    var isDragging = false; private set

    fun setContentHeight(content: Float, viewport: Float) {
        maxOffset = maxOf(0f, content - viewport)
        offset = offset.coerceIn(0f, maxOffset)
    }

    /** Returns true if this event is part of a scroll drag (so taps should be cancelled). */
    fun touch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragging = true; isDragging = false; lastY = e.y; downY = e.y; vel = 0f }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                val dy = e.y - lastY
                if (!isDragging && abs(e.y - downY) > app.p * 4) isDragging = true
                if (isDragging) {
                    offset = (offset - dy).coerceIn(0f, maxOffset)
                    vel = -dy * 60f
                }
                lastY = e.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = false }
        }
        return isDragging
    }

    fun update(dt: Float) {
        if (!dragging && abs(vel) > 1f) {
            offset = (offset + vel * dt).coerceIn(0f, maxOffset)
            vel *= (1f - minOf(1f, dt * 4f))
            if (offset <= 0f || offset >= maxOffset) vel = 0f
        }
    }

    fun drawBar(c: Canvas, view: RectF) {
        if (maxOffset <= 0f) return
        val p = app.p
        val total = view.height() + maxOffset
        val h = maxOf(p * 6, view.height() * view.height() / total)
        val y = view.top + (view.height() - h) * (offset / maxOffset)
        Gfx.rect(c, view.right - p * 2, y, view.right - p, y + h, Pal.withAlpha(Pal.LGREY, 120))
    }
}
