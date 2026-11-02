package com.openarcade.ui

import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import com.openarcade.audio.Music
import com.openarcade.audio.Song
import com.openarcade.core.ArcadeApp
import com.openarcade.gfx.PixelArt

/** A button shown in the global top bar for the current screen (e.g. pause, help, favourite). */
class BarButton(val icon: () -> PixelArt, val onClick: () -> Unit, val tint: () -> Int? = { null })

/**
 * Base class for every full screen of the console. The app draws the shared background and the
 * global top bar (back, title, music & sound toggles) around it.
 */
abstract class Screen(val app: ArcadeApp) {
    open val title: String = ""
    open val music: Song? = Music.MENU
    open val showBar: Boolean = true
    /** Overlay screens are drawn on top of the screen beneath them. */
    open val isOverlay: Boolean = false
    open val showBack: Boolean = true

    /** Area below the top bar available to the screen. */
    val content = RectF()

    open fun barButtons(): List<BarButton> = emptyList()

    /** Called whenever the screen size or [content] changes. */
    open fun layout() {}
    open fun update(dt: Float) {}
    abstract fun draw(c: Canvas)
    open fun touch(e: MotionEvent): Boolean = false
    /** Return true if the back press was consumed; otherwise the screen is popped. */
    open fun back(): Boolean = false
    open fun key(keyCode: Int): Boolean = false
    open fun enter() {}
    open fun exit() {}
    /** The screen became top-most again after another screen above it was popped. */
    open fun resumed() {}
    open fun appPaused() {}
    open fun appResumed() {}
}
