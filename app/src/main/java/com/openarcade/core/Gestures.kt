package com.openarcade.core

import android.os.SystemClock
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.hypot

enum class Dir(val dx: Int, val dy: Int) {
    UP(0, -1), DOWN(0, 1), LEFT(-1, 0), RIGHT(1, 0);

    val opposite: Dir get() = when (this) { UP -> DOWN; DOWN -> UP; LEFT -> RIGHT; RIGHT -> LEFT }
}

/**
 * Single-pointer gesture helper used by games: taps, swipes (optionally repeated while the finger
 * keeps moving), drags and long presses.
 */
class Gestures(private val threshold: () -> Float) {
    var onTap: ((Float, Float) -> Unit)? = null
    var onSwipe: ((Dir) -> Unit)? = null
    var onDown: ((Float, Float) -> Unit)? = null
    var onDrag: ((Float, Float) -> Unit)? = null
    var onUp: ((Float, Float) -> Unit)? = null
    var onLongPress: ((Float, Float) -> Unit)? = null

    /** When true, a swipe fires as soon as the threshold is crossed and can fire again. */
    var continuousSwipe = false
    var longPressMs = 380L

    var isDown = false; private set
    var x = 0f; private set
    var y = 0f; private set
    private var sx = 0f
    private var sy = 0f
    private var downAt = 0L
    private var moved = false
    private var swiped = false
    private var longFired = false

    fun handle(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDown = true; moved = false; swiped = false; longFired = false
                sx = e.x; sy = e.y; x = e.x; y = e.y
                downAt = SystemClock.uptimeMillis()
                onDown?.invoke(x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDown) return
                x = e.x; y = e.y
                val dx = x - sx
                val dy = y - sy
                if (hypot(dx, dy) > threshold()) moved = true
                onDrag?.invoke(x, y)
                if (onSwipe != null && (!swiped || continuousSwipe) && !longFired) {
                    val t = threshold() * 2f
                    if (abs(dx) > t || abs(dy) > t) {
                        val d = if (abs(dx) > abs(dy)) (if (dx > 0) Dir.RIGHT else Dir.LEFT) else (if (dy > 0) Dir.DOWN else Dir.UP)
                        swiped = true
                        sx = x; sy = y
                        onSwipe?.invoke(d)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!isDown) return
                isDown = false
                x = e.x; y = e.y
                onUp?.invoke(x, y)
                if (!moved && !swiped && !longFired) onTap?.invoke(x, y)
            }
            MotionEvent.ACTION_CANCEL -> isDown = false
        }
    }

    /** Call every frame so long presses can fire while the finger is held still. */
    fun update() {
        if (isDown && !moved && !longFired && onLongPress != null &&
            SystemClock.uptimeMillis() - downAt >= longPressMs
        ) {
            longFired = true
            onLongPress?.invoke(x, y)
        }
    }
}
