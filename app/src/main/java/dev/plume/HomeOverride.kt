package dev.plume

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Google TV's launcher has to stay enabled (disabling it boot-loops the TV), so we take the Home key before
 * the system sees it and open our home directly; Google TV never flashes up. Holding Home still opens the
 * Google TV dashboard. Covering the Google TV home screen when it shows stays as the fallback for anything
 * else that opens it (e.g. Back out of the last app); its dashboard and setup screens stay visible.
 */
class HomeOverride : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())

    /** A key being held, whose long action runs when its timer fires. */
    private class Hold(val long: () -> Unit) {
        var timer: Runnable? = null
        var fired = false
    }

    private val holds = HashMap<String, Hold>()
    private var dreaming = false

    override fun onServiceConnected() {
        running = this
    }

    /** Turned off, from Plume's switch or Android's Accessibility settings: the app mustn't turn it back on. */
    override fun onUnbind(intent: Intent?): Boolean {
        cancelHolds()
        running = null
        getSharedPreferences("home", MODE_PRIVATE).edit().putBoolean("home_off", true).apply()
        return super.onUnbind(intent)
    }

    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode != KeyEvent.KEYCODE_HOME) return remap(e)
        // Off: let the system wake the TV and run its own Home/CEC path.
        if (!getSystemService(PowerManager::class.java).isInteractive) return false
        // Dreaming: let the system stop the screensaver and handle Home.
        if (isDreaming()) return false
        // Timed from the press, so a hold opens the dashboard while the key is still down; a tap goes home
        // on release. This TV's remote doesn't reliably send key repeats, so a timer is the only clock.
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
            hold(HOME_KEY) { performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) }
        } else if (e.action == KeyEvent.ACTION_UP && !end(HOME_KEY) && !e.isCanceled) {
            goHome()
        }
        return true
    }

    /** Keys the user changed in Plume settings (see [Remap]); any other key goes on to the system untouched. */
    private fun remap(e: KeyEvent): Boolean {
        if (Remap.kept(e)) return false
        val id = Remap.id(e)
        if (id == Remap.pending) { // the key just learned: its repeats and release never reach anyone
            if (e.action == KeyEvent.ACTION_UP) Remap.pending = null
            return true
        }
        Remap.learn?.let { learn ->
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                Remap.pending = id
                learn(e)
            }
            return true
        }
        val m = Remap.all(this)[id] ?: return false
        // A hold needs a press. Without one, leave the whole key to its normal job: no listener, no timer.
        if (m.short.isEmpty()) { cancel(id); return false }
        // The long action runs on its timer while the key is held; a short press runs on release. This TV's
        // remote doesn't reliably send key repeats, so a timer is the only clock.
        if (e.action == KeyEvent.ACTION_DOWN) {
            if (e.repeatCount == 0) {
                if (m.long.isNotEmpty()) hold(id) { Remap.run(this, m.long) } else cancel(id)
            }
        } else if (e.action == KeyEvent.ACTION_UP && !end(id) && !e.isCanceled) {
            Remap.run(this, m.short)
        }
        return true
    }

    /** Starts [id]'s long-action timer, replacing any timer already waiting for that key. */
    private fun hold(id: String, long: () -> Unit) {
        cancel(id)
        val h = Hold(long)
        h.timer = Runnable {
            h.fired = true
            long()
        }.also { handler.postDelayed(it, LONG_PRESS_MS) }
        holds[id] = h
    }

    /** Stops [id]'s timer; true if its long action already ran while the key was held. */
    private fun end(id: String): Boolean {
        val h = holds.remove(id) ?: return false
        h.timer?.let(handler::removeCallbacks)
        return h.fired
    }

    private fun cancel(id: String) {
        holds.remove(id)?.timer?.let(handler::removeCallbacks)
    }

    private fun cancelHolds() {
        holds.values.forEach { it.timer?.let(handler::removeCallbacks) }
        holds.clear()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // Screensavers can change the window without us seeing a Home key first.
        dreaming = isDream(e.packageName, e.className)
        // Only the stock home screen: the dashboard, setup and onboarding screens must show.
        if (!dreaming && e.packageName == GOOGLE_TV &&
            e.className?.contains("HomeActivity", ignoreCase = true) == true
        ) goHome()
    }

    private fun isDream(pkg: CharSequence?, cls: CharSequence?): Boolean =
        pkg?.contains("dream", ignoreCase = true) == true ||
        cls?.contains("dream", ignoreCase = true) == true

    /** The dream event may be missed; look at the current windows as a fallback. */
    @Suppress("DEPRECATION")
    private fun isDreaming(): Boolean {
        if (dreaming) return true
        return try {
            windows.any { window ->
                val root = window.root ?: return@any false
                try {
                    isDream(root.packageName, root.className)
                } finally {
                    root.recycle()
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    // Marked as Home so the setup check can tell it from Plume being opened from the apps list.
    private fun goHome() = startActivity(Intent(this, MainActivity::class.java).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    override fun onInterrupt() {}

    companion object {
        /** The bound service, so Plume's switch can turn it off without any permission. */
        var running: HomeOverride? = null
            private set
        private const val GOOGLE_TV = "com.google.android.apps.tv.launcherx"
        private const val HOME_KEY = "home" // the Home key's hold, alongside the remapped keys' ids
        private const val LONG_PRESS_MS = 500L
    }
}
