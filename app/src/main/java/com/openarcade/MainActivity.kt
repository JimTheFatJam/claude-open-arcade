package com.openarcade

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import com.openarcade.ui.screens.SplashScreen

/**
 * Open Arcade's only activity. Everything is drawn by [ArcadeView]; the activity just wires up the
 * window (immersive full screen), lifecycle and hardware keys.
 */
class MainActivity : Activity() {
    private lateinit var view: ArcadeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = ArcadeView(this)
        setContentView(view)
        view.app.applyOrientation()
        view.app.replaceAll(SplashScreen(view.app), animate = false)
        view.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        view.app.appResumed()
        view.setRunning(true)
    }

    override fun onPause() {
        view.app.appPaused()
        view.setRunning(false)
        super.onPause()
    }

    override fun onDestroy() {
        view.app.destroy()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!view.app.handleBack()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_BACK && event.repeatCount < 8 && view.app.key(keyCode)) return true
        return super.onKeyDown(keyCode, event)
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)
        }
    }
}
