package dev.plume

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Google TV's launcher has to stay enabled (disabling it boot-loops the TV), so we take the Home key before
 * the system sees it and open our home directly; Google TV never flashes up. Holding Home still opens the
 * Google TV dashboard. Covering the TV's own home whenever it shows stays as the fallback for anything else
 * that opens it (e.g. Back out of the last app); its dashboard, setup and onboarding screens stay visible.
 * On Fire TV that cover is the only way: Fire OS keeps Home to itself.
 */
class HomeOverride : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())

    /** Home-key holds, sleep/screensaver guard and remapped keys; kept out of this file for easier upstream merges. */
    private val keys = KeyRouter(this) { goHome() }

    override fun onServiceConnected() {
        running = this
        applyKeyFilter()
    }

    /**
     * Seeing keys costs volume its hold on TVs: Android acts on Volume before any service sees it and the key never
     * repeats. So Smooth volume turns key filtering off, unless remote keys need it (set, or one being learned).
     * Home is then caught by the window watcher below, which shows the TV's home for about a second.
     * Fire OS never passes remote keys to us, so there filtering is always off.
     */
    fun applyKeyFilter() {
        val filter = !fireTv(this) && (!getSharedPreferences("home", MODE_PRIVATE).getBoolean("smooth_volume", false) ||
            RemoteKeys.active(this) || Remap.learn != null)
        val flag = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        serviceInfo = serviceInfo.apply { flags = if (filter) flags or flag else flags and flag.inv() }
    }

    /** Turned off, from Plume's switch or Android's Accessibility settings: the app mustn't turn it back on. */
    override fun onUnbind(intent: Intent?): Boolean {
        keys.cancelAll()
        running = null
        handler.removeCallbacks(jump)
        getSharedPreferences("home", MODE_PRIVATE).edit().putBoolean("home_off", true).apply()
        return super.onUnbind(intent)
    }

    override fun onKeyEvent(e: KeyEvent): Boolean = keys.onKeyEvent(e)

    private val watch = HomeWatchExt()
    private val jump = Runnable { goHome() }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        // Screensavers can change the window without us seeing a Home key first.
        keys.onWindow(pkg, e.className)
        handler.removeCallbacks(jump) // any other window opening means Home wasn't where we were going
        watch.opened(pkg, e.className?.toString(), SystemClock.uptimeMillis())?.let { handler.postDelayed(jump, it) }
    }

    // Marked as Home so the setup check can tell it from Plume being opened from the apps list.
    private fun goHome() = startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    override fun onInterrupt() {}

    companion object {
        /** The bound service, so Plume's switch can turn it off without any permission. */
        var running: HomeOverride? = null
            private set

        /** Fire OS keeps Amazon's home and ignores the home role; Plume can only cover it (see [HomeWatch]). */
        fun fireTv(c: Context) = c.packageManager.hasSystemFeature("amazon.hardware.fire_tv")
    }
}
