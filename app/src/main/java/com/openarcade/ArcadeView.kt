package com.openarcade

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import com.openarcade.core.ArcadeApp

/** The single view that hosts the whole console. It renders continuously while visible. */
class ArcadeView(context: Context) : View(context) {
    val app = ArcadeApp(context) { postInvalidateOnAnimation() }
    private var running = true

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        app.resize(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        app.frame(canvas)
        if (running) postInvalidateOnAnimation()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        app.touch(event)
        return true
    }

    fun setRunning(r: Boolean) {
        running = r
        if (r) postInvalidateOnAnimation()
    }
}
