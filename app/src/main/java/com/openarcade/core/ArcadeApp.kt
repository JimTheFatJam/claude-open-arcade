package com.openarcade.core

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import com.openarcade.audio.AudioEngine
import com.openarcade.audio.Sfx
import com.openarcade.data.Storage
import com.openarcade.gfx.Align
import com.openarcade.gfx.Gfx
import com.openarcade.gfx.Icons
import com.openarcade.gfx.Pal
import com.openarcade.gfx.PixelArt
import com.openarcade.gfx.PixelFont
import com.openarcade.ui.Button
import com.openarcade.ui.ButtonSet
import com.openarcade.ui.Screen
import kotlin.math.floor
import kotlin.math.sin

/**
 * The console itself: owns storage and audio, the screen stack, the global top bar with the music
 * and sound toggles, dialogs, toasts, the pixel dissolve transition and the CRT overlay.
 */
class ArcadeApp(val context: Context, private val invalidate: () -> Unit) {
    val storage = Storage(context)
    val audio = AudioEngine()
    val progress = Progress(this)

    var w = 0f; private set
    var h = 0f; private set
    /** Size of one art pixel on screen. All UI is laid out in multiples of it. */
    var p = 4f; private set
    var tSmall = 3f; private set
    var tBody = 4f; private set
    var tTitle = 8f; private set
    var barH = 0f; private set
    val landscape get() = w > h

    private val stack = ArrayList<Screen>()
    val top: Screen? get() = stack.lastOrNull()
    private val bg = Starfield()
    var time = 0f; private set
    private var lastNanos = 0L

    // transition
    private var transT = 0f
    private var transDir = 0       // 1 covering, -1 revealing, 0 idle
    private var pendingAction: (() -> Unit)? = null

    // top bar
    private val barButtons = ButtonSet(this)
    private var barTouching = false

    // dialog & toasts
    private var dialog: Dialog? = null
    private class Toast(val text: String, val icon: PixelArt?, var t: Float = 0f)
    private val toasts = ArrayList<Toast>()

    private var scanShader: BitmapShader? = null
    private val scanPaint = Paint()

    init {
        audio.musicEnabled = storage.musicOn
        audio.sfxEnabled = storage.sfxOn
        audio.musicVolume = storage.musicVolume / 10f
        audio.sfxVolume = storage.sfxVolume / 10f
        audio.start()
    }

    // ------------------------------------------------------------------ sizing
    fun resize(width: Int, height: Int) {
        w = width.toFloat(); h = height.toFloat()
        val density = context.resources.displayMetrics.density
        val cap = maxOf(2f, kotlin.math.round(density * 3f))
        p = floor(minOf(w, h) / 170f).coerceIn(2f, cap)
        tBody = p
        tSmall = maxOf(2f, floor(p * 0.7f)).let { if (it < 3f && density >= 2f) 3f else it }
        tTitle = p * 2
        barH = p * 18
        scanShader = null
        for (s in stack) layoutScreen(s)
        dialog?.layout()
    }

    private fun layoutScreen(s: Screen) {
        s.content.set(0f, if (s.showBar) barH else 0f, w, h)
        s.layout()
    }

    // ------------------------------------------------------------------ navigation
    fun push(s: Screen, animate: Boolean = true) = navigate(animate) {
        top?.exit()
        stack.add(s)
        layoutScreen(s)
        s.enter()
    }

    fun pop(animate: Boolean = true) = navigate(animate) {
        if (stack.size <= 1) return@navigate
        stack.removeAt(stack.size - 1).exit()
        top?.resumed()
    }

    fun replaceAll(s: Screen, animate: Boolean = true) = navigate(animate) {
        while (stack.isNotEmpty()) stack.removeAt(stack.size - 1).exit()
        stack.add(s)
        layoutScreen(s)
        s.enter()
    }

    /** Push an overlay immediately (no dissolve), e.g. the tutorial over a game. */
    fun pushOverlay(s: Screen) {
        stack.add(s); layoutScreen(s); s.enter(); onTopChanged()
    }

    fun popOverlay(s: Screen) {
        if (top === s) { stack.removeAt(stack.size - 1); s.exit(); top?.resumed(); onTopChanged() }
    }

    private fun navigate(animate: Boolean, action: () -> Unit) {
        if (transDir != 0) {
            // a transition is running: run the action at its midpoint instead of stacking
            val prev = pendingAction
            pendingAction = { prev?.invoke(); action() }
            return
        }
        if (!animate || w == 0f) { action(); onTopChanged(); return }
        pendingAction = action
        transDir = 1
        transT = 0f
    }

    private fun onTopChanged() {
        val t = top ?: return
        audio.playSong(t.music)
        barButtons.cancel()
        invalidate()
    }

    fun stackSize() = stack.size

    // ------------------------------------------------------------------ frame
    fun frame(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1e9f).coerceAtMost(0.05f)
        lastNanos = now
        time += dt
        update(dt)
        draw(c)
    }

    private fun update(dt: Float) {
        if (transDir != 0) {
            transT += dt / 0.16f
            if (transDir == 1 && transT >= 1f) {
                pendingAction?.invoke(); pendingAction = null
                onTopChanged()
                transDir = -1; transT = 0f
            } else if (transDir == -1 && transT >= 1f) {
                transDir = 0; transT = 0f
            }
        }
        bg.update(dt)
        val t = top
        if (dialog == null) t?.update(dt)
        val it = toasts.iterator()
        while (it.hasNext()) { val ts = it.next(); ts.t += dt; if (ts.t > 2.6f) it.remove() }
    }

    private fun draw(c: Canvas) {
        Gfx.rect(c, 0f, 0f, w, h, Pal.BG0)
        bg.draw(c, w, h, p)
        // draw the top-most non-overlay screen and every overlay above it
        var first = stack.size - 1
        while (first > 0 && stack[first].isOverlay) first--
        for (i in maxOf(0, first) until stack.size) {
            val s = stack[i]
            if (i > first) Gfx.rect(c, 0f, 0f, w, h, Pal.withAlpha(Pal.BLACK, 170))
            c.save()
            s.draw(c)
            c.restore()
        }
        top?.let { if (it.showBar) drawBar(c, it) }
        dialog?.draw(c)
        drawToasts(c)
        if (storage.scanlines) drawScanlines(c)
        if (transDir != 0) drawTransition(c)
    }

    // ------------------------------------------------------------------ top bar
    private fun drawBar(c: Canvas, s: Screen) {
        Gfx.rect(c, 0f, 0f, w, barH, Pal.BG1)
        Gfx.rect(c, 0f, barH - p, w, barH, Pal.BLACK)
        // rainbow stripe
        val seg = w / Pal.RAINBOW.size
        for (i in Pal.RAINBOW.indices) Gfx.rect(c, i * seg, barH - p * 2, (i + 1) * seg, barH - p, Pal.RAINBOW[i])

        barButtons.clear()
        val bs = barH - p * 6
        val y0 = p * 2
        var left = p * 2
        if (s.showBack && stack.size > 1) {
            barButtons.add(Button("", Icons.BACK, Pal.BG2, Sfx.BACK) { handleBack() }).set(left, y0, left + bs, y0 + bs)
            left += bs + p * 2
        }
        var right = w - p * 2
        val sfxIcon = if (audio.sfxEnabled) Icons.SOUND else Icons.SOUND_OFF
        barButtons.add(Button("", sfxIcon, if (audio.sfxEnabled) Pal.DBLUE else Pal.DGREY, Sfx.CLICK) { toggleSfx() })
            .set(right - bs, y0, right, y0 + bs)
        right -= bs + p * 2
        val musicIcon = if (audio.musicEnabled) Icons.MUSIC else Icons.MUSIC_OFF
        barButtons.add(Button("", musicIcon, if (audio.musicEnabled) Pal.DBLUE else Pal.DGREY, Sfx.CLICK) { toggleMusic() })
            .set(right - bs, y0, right, y0 + bs)
        right -= bs + p * 2
        for (bb in s.barButtons().reversed()) {
            val b = Button("", bb.icon(), bb.tint() ?: Pal.BG2, Sfx.CLICK) { bb.onClick() }
            barButtons.add(b).set(right - bs, y0, right, y0 + bs)
            right -= bs + p * 2
        }
        barButtons.draw(c)
        val title = s.title
        if (title.isNotEmpty()) {
            val avail = right - left - p * 2
            val size = Gfx.fitSize(title, avail, tTitle * 0.75f)
            Gfx.textMid(c, title, left + p, y0 + bs / 2, size, Pal.YELLOW, Align.LEFT)
        }
    }

    fun toggleMusic() {
        val v = !audio.musicEnabled
        audio.musicEnabled = v
        storage.musicOn = v
        toast(if (v) "MUSIC ON" else "MUSIC OFF", if (v) Icons.MUSIC else Icons.MUSIC_OFF)
    }

    fun toggleSfx() {
        val v = !audio.sfxEnabled
        audio.sfxEnabled = v
        storage.sfxOn = v
        if (v) audio.play(Sfx.CLICK)
        toast(if (v) "SOUND FX ON" else "SOUND FX OFF", if (v) Icons.SOUND else Icons.SOUND_OFF)
    }

    // ------------------------------------------------------------------ input
    fun touch(e: MotionEvent): Boolean {
        if (transDir != 0) return true
        dialog?.let { it.touch(e); return true }
        val s = top ?: return false
        if (s.showBar) {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) barTouching = e.y < barH
            if (barTouching) {
                barButtons.touch(e)
                if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) barTouching = false
                invalidate()
                return true
            }
        }
        return s.touch(e)
    }

    /** System back. Returns false when the app should close. */
    fun handleBack(): Boolean {
        if (transDir != 0) return true
        dialog?.let { if (it.cancelable) dismissDialog(); return true }
        val s = top ?: return false
        if (s.back()) return true
        if (s.isOverlay) { popOverlay(s); return true }
        if (stack.size > 1) { pop(); return true }
        return false
    }

    fun key(code: Int): Boolean = if (dialog != null) false else top?.key(code) ?: false

    fun appPaused() {
        audio.setActive(false)
        top?.appPaused()
    }

    fun appResumed() {
        lastNanos = 0L
        audio.setActive(true)
        top?.appResumed()
    }

    fun destroy() = audio.shutdown()

    // ------------------------------------------------------------------ dialogs & toasts
    fun showDialog(title: String, message: String, buttons: List<Pair<String, () -> Unit>>, cancelable: Boolean = true) {
        dialog = Dialog(this, title, message, buttons, cancelable).also { it.layout() }
    }

    fun dismissDialog() { dialog = null }

    fun toast(text: String, icon: PixelArt? = null) {
        toasts.removeAll { it.text == text }
        toasts.add(Toast(text, icon))
        while (toasts.size > 3) toasts.removeAt(0)
    }

    private fun drawToasts(c: Canvas) {
        var y = barH + p * 3
        for (t in toasts) {
            val slide = when {
                t.t < 0.15f -> t.t / 0.15f
                t.t > 2.4f -> 1f - (t.t - 2.4f) / 0.2f
                else -> 1f
            }.coerceIn(0f, 1f)
            val size = tSmall
            val iconW = if (t.icon != null) p * 9 else 0f
            val tw = PixelFont.width(t.text, size) + iconW + p * 8
            val bh = p * 13
            val x = (w - tw) / 2
            val yy = y - (1f - slide) * (bh + barH)
            val r = RectF(x, yy, x + tw, yy + bh)
            Gfx.panel(c, r, Pal.BG2, Pal.YELLOW, p)
            var tx = x + p * 4
            t.icon?.let { it.drawFit(c, RectF(tx, yy + p * 2.5f, tx + p * 8, yy + bh - p * 2.5f)); tx += iconW }
            Gfx.textMid(c, t.text, tx, yy + bh / 2, size, Pal.WHITE, Align.LEFT)
            y += (bh + p * 2) * slide
        }
    }

    private fun drawScanlines(c: Canvas) {
        val step = maxOf(2, (p / 2).toInt() * 2)
        if (scanShader == null) {
            val b = Bitmap.createBitmap(1, step, Bitmap.Config.ARGB_8888)
            for (i in 0 until step) b.setPixel(0, i, if (i >= step / 2) Pal.withAlpha(Pal.BLACK, 38) else 0)
            scanShader = BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            scanPaint.shader = scanShader
        }
        c.drawRect(0f, 0f, w, h, scanPaint)
    }

    private fun drawTransition(c: Canvas) {
        // ordered-dither pixel dissolve
        val k = if (transDir == 1) transT else 1f - transT
        val block = p * 10
        val bayer = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
        Gfx.paint.color = Pal.BLACK
        var yi = 0
        var y = 0f
        while (y < h) {
            var xi = 0
            var x = 0f
            while (x < w) {
                val th = (bayer[(yi and 3) * 4 + (xi and 3)] + 0.5f) / 16f
                if (k > th) c.drawRect(x, y, x + block, y + block, Gfx.paint)
                x += block; xi++
            }
            y += block; yi++
        }
    }

    // ------------------------------------------------------------------ misc services
    fun vibrate(ms: Long) {
        if (!storage.vibration) return
        val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") v.vibrate(ms)
        } catch (e: Exception) { /* ignore */ }
    }

    fun applyOrientation() {
        val a = context as? Activity ?: return
        a.requestedOrientation = when (storage.orientation) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
    }

    /** A blinking factor in 0..1 used for "press start" style effects. */
    fun blink(rate: Float = 2f) = (sin(time * rate * Math.PI * 2).toFloat() + 1f) / 2f
}

/** Modal pixel dialog with a title, message and up to three buttons. */
class Dialog(
    private val app: ArcadeApp,
    private val title: String,
    private val message: String,
    private val actions: List<Pair<String, () -> Unit>>,
    val cancelable: Boolean,
) {
    private val buttons = ButtonSet(app)
    private val box = RectF()

    fun layout() {
        val p = app.p
        val bw = minOf(app.w - p * 12, p * 150)
        val textH = Gfx.paragraphHeight(message, bw - p * 12, app.tSmall)
        val bh = p * 16 + PixelFont.height(app.tBody) + p * 6 + textH + p * 6 + p * 16 + p * 6
        box.set((app.w - bw) / 2, (app.h - bh) / 2, (app.w + bw) / 2, (app.h + bh) / 2)
        buttons.clear()
        val n = actions.size
        val gap = p * 3
        val btnW = (bw - p * 12 - gap * (n - 1)) / n
        actions.forEachIndexed { i, (label, fn) ->
            val x = box.left + p * 6 + i * (btnW + gap)
            buttons.add(Button(label, null, if (i == n - 1) Pal.BLUE else Pal.BG3) { app.dismissDialog(); fn() })
                .set(x, box.bottom - p * 22, x + btnW, box.bottom - p * 6)
        }
    }

    fun draw(c: Canvas) {
        val p = app.p
        Gfx.rect(c, 0f, 0f, app.w, app.h, Pal.withAlpha(Pal.BLACK, 190))
        Gfx.panel(c, box, Pal.BG1, Pal.CYAN, p)
        Gfx.text(c, title, box.centerX(), box.top + p * 8, app.tBody, Pal.YELLOW, Align.CENTER)
        Gfx.paragraph(c, message, box.left + p * 6, box.top + p * 8 + PixelFont.height(app.tBody) + p * 6,
            box.width() - p * 12, app.tSmall, Pal.WHITE, Align.CENTER)
        buttons.draw(c)
    }

    fun touch(e: MotionEvent) {
        if (!buttons.touch(e) && e.actionMasked == MotionEvent.ACTION_UP && cancelable && !box.contains(e.x, e.y)) {
            app.dismissDialog()
        }
    }
}

/** Twinkling pixel starfield drifting slowly behind every screen. */
class Starfield {
    private val n = 70
    private val xs = FloatArray(n) { Math.random().toFloat() }
    private val ys = FloatArray(n) { Math.random().toFloat() }
    private val sp = FloatArray(n) { 0.004f + Math.random().toFloat() * 0.02f }
    private val ph = FloatArray(n) { Math.random().toFloat() * 6.28f }
    private var t = 0f

    fun update(dt: Float) {
        t += dt
        for (i in 0 until n) {
            ys[i] += sp[i] * dt
            if (ys[i] > 1f) { ys[i] -= 1f; xs[i] = Math.random().toFloat() }
        }
    }

    fun draw(c: Canvas, w: Float, h: Float, p: Float) {
        for (i in 0 until n) {
            val tw = (sin(t * 2f + ph[i]) + 1f) / 2f
            val col = when (i % 4) { 0 -> Pal.CYAN; 1 -> Pal.PINK; else -> Pal.LGREY }
            val a = (40 + 120 * tw).toInt()
            val s = if (sp[i] > 0.016f) p else p * 0.5f
            val x = floor(xs[i] * w / p) * p
            val y = floor(ys[i] * h / p) * p
            Gfx.rect(c, x, y, x + s, y + s, Pal.withAlpha(col, a))
        }
    }
}
