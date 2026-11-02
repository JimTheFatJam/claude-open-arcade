package com.openarcade.ui.screens

import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import com.openarcade.audio.Sfx
import com.openarcade.core.ArcadeApp
import com.openarcade.gfx.Align
import com.openarcade.gfx.Gfx
import com.openarcade.gfx.Icons
import com.openarcade.gfx.Pal
import com.openarcade.gfx.PixelFont
import com.openarcade.ui.Button
import com.openarcade.ui.ButtonSet
import com.openarcade.ui.Screen
import com.openarcade.ui.Scroller

/** Audio, display and data settings. Changes apply immediately and persist. */
class SettingsScreen(app: ArcadeApp) : Screen(app) {
    override val title = "SETTINGS"
    private val buttons = ButtonSet(app)
    private val scroller = Scroller(app)
    private class Row(val label: String, val y: Float, val value: () -> String)
    private val rows = ArrayList<Row>()
    private val headers = ArrayList<Pair<String, Float>>()
    private var contentH = 0f

    override fun layout() = rebuild()
    override fun resumed() = rebuild()

    private fun rebuild() {
        val p = app.p
        buttons.clear(); rows.clear(); headers.clear()
        val colW = minOf(content.width() - p * 8, p * 200)
        val left = (app.w - colW) / 2
        val right = left + colW
        val rh = p * 20
        val bw = p * 16
        var y = content.top + p * 6
        val st = app.storage

        fun header(t: String) { headers.add(t to y); y += PixelFont.height(app.tBody) + p * 6 }
        fun toggle(label: String, get: () -> Boolean, set: (Boolean) -> Unit) {
            rows.add(Row(label, y) { "" })
            val on = get()
            val b = Button(if (on) "ON" else "OFF", null, if (on) Pal.GREEN else Pal.DGREY, Sfx.CLICK) { set(!get()); rebuild() }
            b.textColor = if (on) Pal.BLACK else Pal.WHITE
            buttons.add(b).set(right - p * 36, y + p * 2, right, y + rh - p * 2)
            y += rh + p * 3
        }
        fun stepper(label: String, get: () -> Int, max: Int = 10, set: (Int) -> Unit) {
            rows.add(Row(label, y) { get().toString() })
            buttons.add(Button("", Icons.PLUS, Pal.BG3, Sfx.TICK) { set((get() + 1).coerceAtMost(max)); rebuild() })
                .set(right - bw, y + p * 2, right, y + rh - p * 2)
            buttons.add(Button("", Icons.MINUS, Pal.BG3, Sfx.TICK) { set((get() - 1).coerceAtLeast(0)); rebuild() })
                .set(right - bw * 2 - p * 18, y + p * 2, right - bw - p * 18, y + rh - p * 2)
            y += rh + p * 3
        }

        header("AUDIO")
        toggle("MUSIC", { app.audio.musicEnabled }) { if (it != app.audio.musicEnabled) app.toggleMusic() }
        stepper("MUSIC VOLUME", { st.musicVolume }) { st.musicVolume = it; app.audio.musicVolume = it / 10f }
        toggle("SOUND FX", { app.audio.sfxEnabled }) { if (it != app.audio.sfxEnabled) app.toggleSfx() }
        stepper("SFX VOLUME", { st.sfxVolume }) { st.sfxVolume = it; app.audio.sfxVolume = it / 10f; app.audio.play(Sfx.COIN) }
        y += p * 4
        header("DISPLAY")
        toggle("CRT SCANLINES", { st.scanlines }) { st.scanlines = it }
        toggle("VIBRATION", { st.vibration }) { st.vibration = it; if (it) app.vibrate(30) }
        rows.add(Row("ORIENTATION", y) { "" })
        val names = listOf("AUTO", "PORT", "LAND")
        val ow = p * 26
        names.forEachIndexed { i, n ->
            val x = right - (3 - i) * ow - (2 - i) * p * 2
            val b = Button(n, null, Pal.BG2, Sfx.CLICK) { st.orientation = i; app.applyOrientation(); rebuild() }
            b.active = st.orientation == i
            b.color = if (b.active) Pal.BLUE else Pal.BG2
            b.textSize = app.tSmall
            buttons.add(b).set(x, y + p * 2, x + ow, y + rh - p * 2)
        }
        y += rh + p * 3
        y += p * 4
        header("DATA")
        buttons.add(Button("REPLAY ALL TUTORIALS", Icons.HELP, Pal.PURPLE) {
            st.resetTutorials(); app.toast("TUTORIALS WILL SHOW AGAIN", Icons.HELP)
        }).set(left, y, right, y + rh)
        y += rh + p * 4
        buttons.add(Button("RESET ALL PROGRESS", Icons.CLOSE, Pal.DRED, Sfx.ERROR) {
            app.showDialog("RESET EVERYTHING?", "This erases all scores, stats, awards, favorites and saved games. This cannot be undone.", listOf(
                "CANCEL" to {},
                "ERASE" to {
                    st.resetAll()
                    app.audio.musicEnabled = st.musicOn; app.audio.sfxEnabled = st.sfxOn
                    app.audio.musicVolume = st.musicVolume / 10f; app.audio.sfxVolume = st.sfxVolume / 10f
                    app.applyOrientation()
                    app.toast("ALL DATA RESET")
                    rebuild()
                },
            ))
        }).set(left, y, right, y + rh)
        y += rh + p * 10
        headers.add("ABOUT" to y)
        y += PixelFont.height(app.tBody) + p * 6
        aboutY = y
        y += Gfx.paragraphHeight(ABOUT, colW, app.tSmall) + p * 10
        contentH = y - content.top
        scroller.setContentHeight(contentH, content.height())
        this.left = left; this.right = right
    }

    private var left = 0f
    private var right = 0f
    private var aboutY = 0f

    override fun update(dt: Float) = scroller.update(dt)

    override fun draw(c: Canvas) {
        val p = app.p
        c.save()
        c.clipRect(content)
        c.translate(0f, -scroller.offset)
        for ((h, y) in headers) {
            Gfx.text(c, h, left, y, app.tBody, Pal.CYAN)
            Gfx.rect(c, left, y + PixelFont.height(app.tBody) + p * 2, right, y + PixelFont.height(app.tBody) + p * 3, Pal.BG2)
        }
        val rh = p * 20
        for (r in rows) {
            Gfx.rect(c, RectF(left, r.y, right, r.y + rh), Pal.withAlpha(Pal.BG1, 200))
            Gfx.textMid(c, r.label, left + p * 4, r.y + rh / 2, app.tSmall, Pal.WHITE, Align.LEFT)
            val v = r.value()
            if (v.isNotEmpty()) {
                val bw = p * 16
                val cx = right - bw - p * 9
                Gfx.textMid(c, v, cx, r.y + rh / 2, app.tBody, Pal.YELLOW)
                // volume bar
                val n = v.toIntOrNull() ?: 0
                val bx = right - bw * 2 - p * 22
                for (i in 0 until 10) {
                    val x = bx - (10 - i) * p * 3
                    val hh = p * (2 + i * 0.8f)
                    Gfx.rect(c, x, r.y + rh - p * 4 - hh, x + p * 2, r.y + rh - p * 4, if (i < n) Pal.GREEN else Pal.BG3)
                }
            }
        }
        buttons.draw(c)
        Gfx.paragraph(c, ABOUT, left, aboutY, right - left, app.tSmall, Pal.GREY)
        c.restore()
        scroller.drawBar(c, content)
    }

    override fun touch(e: MotionEvent): Boolean {
        if (scroller.touch(e)) { buttons.cancel(); return true }
        return buttons.touch(e, scroller.offset)
    }

    companion object {
        val ABOUT = "OPEN ARCADE V${com.openarcade.BuildInfo.VERSION}\nA pocket retro console of original-style classic games.\n" +
            "No ads, no accounts, no internet. All music and sound is synthesised on your device. " +
            "All progress is stored only on this device."
    }
}
