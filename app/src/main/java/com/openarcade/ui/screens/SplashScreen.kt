package com.openarcade.ui.screens

import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import com.openarcade.audio.Sfx
import com.openarcade.core.ArcadeApp
import com.openarcade.games.GameRegistry
import com.openarcade.gfx.Align
import com.openarcade.gfx.Gfx
import com.openarcade.gfx.Pal
import com.openarcade.gfx.PixelFont
import com.openarcade.ui.Screen
import kotlin.math.sin

/** Boot screen: the logo drops in letter by letter, then "PRESS START". */
class SplashScreen(app: ArcadeApp) : Screen(app) {
    override val showBar = false
    private var t = 0f
    private var leaving = false

    override fun update(dt: Float) {
        t += dt
        if (t > 6f) start()
    }

    private fun start() {
        if (leaving) return
        leaving = true
        app.audio.play(Sfx.COIN)
        app.replaceAll(HomeScreen(app))
    }

    override fun touch(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP && t > 0.4f) start()
        return true
    }

    override fun draw(c: Canvas) {
        val p = app.p
        drawLogo(c, app, app.w / 2, app.h * 0.36f, t)
        val icons = GameRegistry.all
        // parade of game icons marching along the bottom
        val size = p * 16
        val gap = p * 6
        val total = icons.size * (size + gap)
        val off = (t * p * 20) % total
        val y = app.h * 0.62f
        var x = -off
        while (x < app.w + total) {
            for (g in icons) {
                if (x > -size && x < app.w) {
                    val bob = sin((x / app.w) * 6.28f + t * 4f) * p * 2
                    g.icon.drawFit(c, RectF(x, y + bob, x + size, y + size + bob))
                }
                x += size + gap
            }
        }
        if (t > 1.2f && app.blink(1.2f) > 0.3f) {
            Gfx.text(c, "TAP TO START", app.w / 2, app.h * 0.78f, app.tBody, Pal.YELLOW, Align.CENTER)
        }
        Gfx.text(c, "${icons.size} GAMES  -  100% OFFLINE", app.w / 2, app.h - p * 14, app.tSmall, Pal.GREY, Align.CENTER)
    }

    companion object {
        /** The rainbow "OPEN ARCADE" logo with letters dropping in. Shared with the home screen. */
        fun drawLogo(c: Canvas, app: ArcadeApp, cx: Float, cy: Float, t: Float, maxW: Float = app.w * 0.9f) {
            val words = listOf("OPEN", "ARCADE")
            val size = Gfx.fitSize("ARCADE", maxW, app.p * 4)
            val lineH = PixelFont.height(size) + size * 3
            var idx = 0
            words.forEachIndexed { wi, word ->
                val y0 = cy - lineH + wi * lineH
                val w = PixelFont.width(word, size)
                var x = cx - w / 2
                for (ch in word) {
                    val appear = (t * 8f - idx).coerceIn(0f, 1f)
                    val drop = (1f - appear) * app.h * 0.3f
                    val wave = sin(t * 3f + idx * 0.6f) * size * 0.6f
                    val color = Pal.RAINBOW[(idx + (t * 4).toInt()) % Pal.RAINBOW.size]
                    if (appear > 0f) {
                        val s = ch.toString()
                        PixelFont.draw(c, s, x + size, y0 - drop + wave + size, size, Pal.BLACK)
                        PixelFont.draw(c, s, x + size * 0.5f, y0 - drop + wave + size * 0.5f, size, Pal.darker(color, 0.5f))
                        PixelFont.draw(c, s, x, y0 - drop + wave, size, color)
                    }
                    x += PixelFont.ADVANCE * size
                    idx++
                }
            }
        }
    }
}
