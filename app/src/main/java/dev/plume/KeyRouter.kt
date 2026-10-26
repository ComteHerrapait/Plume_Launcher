package dev.plume

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.KeyEvent

/**
 * Plume's Home-key and remapped-key handling.
 *
 * Kept out of [HomeOverride] so upstream's accessibility-service changes only need the small calls
 * in that file. This owns the timer-based holds, the sleep/screensaver guard and the remapped keys.
 */
class KeyRouter(
    private val service: AccessibilityService,
    private val goHome: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())

    /** A key being held, whose long action runs when its timer fires. */
    private class Hold(val long: () -> Unit) {
        var timer: Runnable? = null
        var fired = false
    }

    private val holds = HashMap<String, Hold>()
    private var dreaming = false

    /** Called for every window change; the screensaver can appear without a Home key first. */
    fun onWindow(pkg: CharSequence?, cls: CharSequence?) {
        dreaming = isDream(pkg, cls)
    }

    fun onKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode != KeyEvent.KEYCODE_HOME) return remap(e)
        // Off: let the system wake the TV and run its own Home/CEC path.
        if (!service.getSystemService(PowerManager::class.java).isInteractive) return false
        // Dreaming: let the system stop the screensaver and handle Home.
        if (isDreaming()) return false
        // Timed from the press, so a hold opens the dashboard while the key is still down; a tap goes home
        // on release. This TV's remote doesn't reliably send key repeats, so a timer is the only clock.
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
            hold(HOME_KEY) { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS) }
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
        val m = Remap.all(service)[id] ?: return false
        // A hold needs a press. Without one, leave the whole key to its normal job: no listener, no timer.
        if (m.short.isEmpty()) { cancel(id); return false }
        // The long action runs on its timer while the key is held; a short press runs on release. This TV's
        // remote doesn't reliably send key repeats, so a timer is the only clock.
        if (e.action == KeyEvent.ACTION_DOWN) {
            if (e.repeatCount == 0) {
                if (m.long.isNotEmpty()) hold(id) { Remap.run(service, m.long) } else cancel(id)
            }
        } else if (e.action == KeyEvent.ACTION_UP && !end(id) && !e.isCanceled) {
            Remap.run(service, m.short)
        }
        return true
    }

    fun cancelAll() {
        holds.values.forEach { it.timer?.let(handler::removeCallbacks) }
        holds.clear()
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

    private fun isDream(pkg: CharSequence?, cls: CharSequence?): Boolean =
        pkg?.contains("dream", ignoreCase = true) == true ||
        cls?.contains("dream", ignoreCase = true) == true

    /** The dream event may be missed; look at the current windows as a fallback. */
    @Suppress("DEPRECATION")
    private fun isDreaming(): Boolean {
        if (dreaming) return true
        return try {
            service.windows.any { window ->
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

    companion object {
        private const val HOME_KEY = "home" // the Home key's hold, alongside the remapped keys' ids
        private const val LONG_PRESS_MS = 500L
    }
}
