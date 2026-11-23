package app.strata.psd.ui

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/** Edge-to-edge activity base with sheet-aware (predictive) back handling. */
abstract class StrataActivity : Activity() {

    class Insets(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private val sheets = ArrayList<Sheet>()
    private var callback: Any? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.init(this)
        val w = window
        @Suppress("DEPRECATION")
        run {
            w.statusBarColor = Color.TRANSPARENT
            w.navigationBarColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= 29) { w.isNavigationBarContrastEnforced = false; w.isStatusBarContrastEnforced = false }
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or (if (!Theme.c.dark) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        }
        w.decorView.setBackgroundColor(Theme.c.surface)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 30) {
            val light = if (Theme.c.dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(light,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        }
    }

    fun onInsets(view: View, block: (Insets) -> Unit) {
        view.setOnApplyWindowInsetsListener { _, insets ->
            val i = if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                Insets(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                Insets(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            block(i)
            insets
        }
        view.requestApplyInsets()
    }

    fun setSystemBarsVisible(visible: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController ?: return
            if (visible) c.show(WindowInsets.Type.systemBars()) else {
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            val base = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                (if (!Theme.c.dark) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (visible) base else base or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
        }
    }

    // ------------------------------------------------------------------ sheets & back

    fun showSheet(sheet: Sheet) {
        if (sheets.contains(sheet)) return
        sheets.add(sheet)
        val prev = sheet.onDismissed
        sheet.onDismissed = { sheets.remove(sheet); updateBackCallback(); prev?.invoke(); sheet.onDismissed = prev }
        sheet.show()
        updateBackCallback()
    }

    private fun updateBackCallback() {
        if (Build.VERSION.SDK_INT < 33) return
        val d = onBackInvokedDispatcher
        if (sheets.isNotEmpty() && callback == null) {
            val cb: OnBackInvokedCallback = if (Build.VERSION.SDK_INT >= 34) object : OnBackAnimationCallback {
                override fun onBackStarted(backEvent: BackEvent) {}
                override fun onBackProgressed(backEvent: BackEvent) { sheets.lastOrNull()?.backProgress(backEvent.progress) }
                override fun onBackCancelled() { sheets.lastOrNull()?.backCancelled() }
                override fun onBackInvoked() { closeTopSheet() }
            } else OnBackInvokedCallback { closeTopSheet() }
            d.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            callback = cb
        } else if (sheets.isEmpty() && callback != null) {
            d.unregisterOnBackInvokedCallback(callback as OnBackInvokedCallback)
            callback = null
        }
    }

    private fun closeTopSheet(): Boolean {
        val s = sheets.lastOrNull() ?: return false
        s.dismiss()
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (closeTopSheet()) return
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
}
