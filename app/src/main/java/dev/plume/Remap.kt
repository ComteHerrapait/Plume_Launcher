package dev.plume

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast

/**
 * Remote key remapping. Plume's Home key service already sees every key before any app does, so a key mapped
 * here is handled there: [Entry.short] runs on a press and [Entry.long] on a hold ("" when unset). An action is
 * "none" (swallow the key), "app:<package>", "hdmi:<input id>", "media:<name>", "sys:<name>", "nav:<name>",
 * "open:<place>" (Android's Settings) or "panel:home" (Google TV's smart home panel).
 * Kept in one preference string ("home"/"keymap", a line per key) and in memory only once a key is pressed.
 * A custom display name is kept per key in "home"/"keyname_<id>".
 */
object Remap {
    class Entry(val short: String, val long: String)

    /** Set while the settings page waits for a key to change. */
    var learn: ((KeyEvent) -> Unit)? = null

    /** The key whose press was just taken to be learned: its repeats and its release are swallowed too. */
    var pending: String? = null

    private var map: LinkedHashMap<String, Entry>? = null

    // Keys that move around Plume or leave it: never learned, never changed, so a remote can't be locked out.
    // Mute is not here: a user can give that button a job and still keep volume up/down.
    private val KEEP = intArrayOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_TAB,
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_SELECT,
    )

    val MEDIA = linkedMapOf(
        "playpause" to (KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to R.string.act_play_pause),
        "next" to (KeyEvent.KEYCODE_MEDIA_NEXT to R.string.act_next),
        "previous" to (KeyEvent.KEYCODE_MEDIA_PREVIOUS to R.string.act_previous),
        "rewind" to (KeyEvent.KEYCODE_MEDIA_REWIND to R.string.act_rewind),
        "forward" to (KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to R.string.act_forward),
    )

    val SYS = linkedMapOf(
        "back" to (AccessibilityService.GLOBAL_ACTION_BACK to R.string.act_back),
        "notifications" to (AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS to R.string.act_notifications),
        "power" to (AccessibilityService.GLOBAL_ACTION_POWER_DIALOG to R.string.act_power),
    )

    /** Real D-pad events, so a working key can replace a broken arrow. These actions need Android 13 (API 33). */
    val NAV = linkedMapOf(
        "up" to (AccessibilityService.GLOBAL_ACTION_DPAD_UP to R.string.act_up),
        "down" to (AccessibilityService.GLOBAL_ACTION_DPAD_DOWN to R.string.act_down),
        "left" to (AccessibilityService.GLOBAL_ACTION_DPAD_LEFT to R.string.act_left),
        "right" to (AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT to R.string.act_right),
        "center" to (AccessibilityService.GLOBAL_ACTION_DPAD_CENTER to R.string.act_select),
    )

    /** Places outside the apps list: Android settings, network settings and the voice assistant. */
    val OPEN = linkedMapOf(
        "settings" to (Settings.ACTION_SETTINGS to R.string.act_settings),
        "network" to (Settings.ACTION_WIFI_SETTINGS to R.string.act_network),
        "assistant" to (Intent.ACTION_ASSIST to R.string.act_assistant),
    )

    /** A key's identity: its Android code, or its scan code for keys Android has no code for. */
    fun id(e: KeyEvent) = id(e.keyCode, e.scanCode)

    /** Also keys with no code at all, which would all share one identity. */
    fun kept(e: KeyEvent) = kept(e.keyCode, e.scanCode)

    // The same rules on plain numbers and a plain SharedPreferences, so the unit tests need no Android framework.
    internal fun id(keyCode: Int, scanCode: Int) = if (keyCode != KeyEvent.KEYCODE_UNKNOWN) "k$keyCode" else "s$scanCode"

    internal fun kept(keyCode: Int, scanCode: Int) = keyCode in KEEP || (keyCode == KeyEvent.KEYCODE_UNKNOWN && scanCode == 0)

    /** Whether this device has the Google TV smart-home panel. */
    fun panelAvailable(c: Context) = c.packageManager.queryIntentActivities(
        Intent(SMART_HOME).setPackage(SMART_HOME_PACKAGE).addCategory(Intent.CATEGORY_DEFAULT), 0
    ).isNotEmpty()

    private fun store(c: Context) = c.getSharedPreferences("home", Context.MODE_PRIVATE)

    fun all(c: Context) = all(store(c))

    internal fun all(p: SharedPreferences): LinkedHashMap<String, Entry> = map ?: LinkedHashMap<String, Entry>().also { m ->
        for (line in p.getString("keymap", "")!!.lines()) {
            val f = line.split('\t')
            if (f.size == 3) m[f[0]] = Entry(f[1], f[2])
        }
        map = m
    }

    /** Forgets the keys held in memory, so the next [all] reads the preferences again. */
    internal fun reset() {
        map = null
    }

    /** Saves a key's actions; a key left with none is forgotten and goes back to its usual job. */
    fun put(c: Context, id: String, short: String, long: String) = put(store(c), id, short, long)

    internal fun put(p: SharedPreferences, id: String, short: String, long: String) {
        val m = all(p)
        val editor = p.edit()
        if (short.isEmpty() && long.isEmpty()) {
            m.remove(id)
            editor.remove("keyname_$id") // the name belongs to the key: do not keep it as an orphan
        } else {
            m[id] = Entry(short, long)
        }
        editor.putString("keymap", m.entries.joinToString("\n") { "${it.key}\t${it.value.short}\t${it.value.long}" }).apply()
    }

    /** Whether this action can run on this device now. Media and the power menu stay best-effort. */
    fun available(c: Context, action: String): Boolean {
        val arg = action.substringAfter(':', "")
        return try {
            when (action.substringBefore(':')) {
                "" -> false
                "none" -> true
                "app" -> launchIntent(c, arg) != null
                "hdmi" -> c.getSystemService(TvInputManager::class.java).getTvInputInfo(arg) != null
                "media" -> arg in MEDIA
                "sys" -> arg in SYS
                "nav" -> Build.VERSION.SDK_INT >= 33 && arg in NAV
                "open" -> OPEN[arg]?.let { c.packageManager.resolveActivity(Intent(it.first), 0) != null } ?: false
                "panel" -> arg == "home" && panelAvailable(c)
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun launchIntent(c: Context, pkg: String) =
        c.packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: c.packageManager.getLaunchIntentForPackage(pkg)

    fun run(s: AccessibilityService, action: String) {
        if (action == "none") return
        if (!available(s, action)) return showToast(s, R.string.action_unavailable)
        val arg = action.substringAfter(':', "")
        try {
            when (action.substringBefore(':')) {
                "app" -> launchIntent(s, arg)?.let { s.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    ?: showToast(s, R.string.action_unavailable)
                "hdmi" -> s.startActivity(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(arg))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "media" -> MEDIA[arg]?.let { (code, _) ->
                    val audio = s.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                    audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                }
                "sys" -> SYS[arg]?.let { (code, _) ->
                    if (!s.performGlobalAction(code)) showToast(s, R.string.action_unavailable)
                }
                "nav" -> if (Build.VERSION.SDK_INT >= 33) NAV[arg]?.let { (code, _) ->
                    if (!s.performGlobalAction(code)) showToast(s, R.string.action_unavailable)
                } else showToast(s, R.string.action_unavailable)
                "open" -> OPEN[arg]?.let { (action, _) -> s.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                "panel" -> if (arg == "home") s.startActivity(
                    Intent(SMART_HOME).setPackage(SMART_HOME_PACKAGE).addCategory(Intent.CATEGORY_DEFAULT)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("trigger_source", SMART_HOME_SOURCE)
                ) else showToast(s, R.string.action_unavailable)
            }
        } catch (e: Exception) {
            // An app that's gone, or an input that's unplugged: tell the user instead of doing nothing.
            showToast(s, R.string.action_failed)
        }
    }

    private fun showToast(c: Context, message: Int) {
        Toast.makeText(c, message, Toast.LENGTH_SHORT).show()
    }

    /** What a key is called in the list: its custom name, else "Media play pause" for Android's own codes, "Key 4001" for the rest. */
    fun name(c: Context, id: String): String {
        customName(c, id)?.let { return it }
        val n = id.substring(1).toInt()
        val s = if (id[0] == 'k') KeyEvent.keyCodeToString(n) else ""
        return if (s.startsWith("KEYCODE_")) s.substring(8).lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
        else c.getString(R.string.key_number, n)
    }

    /** The user's own name for a key, or null when it uses its automatic name. */
    fun customName(c: Context, id: String) = customName(store(c), id)

    internal fun customName(p: SharedPreferences, id: String): String? =
        p.getString("keyname_$id", null)?.takeIf { it.isNotEmpty() }

    /** Saves a custom name; a blank name restores the automatic one. */
    fun rename(c: Context, id: String, name: String) = rename(store(c), id, name)

    internal fun rename(p: SharedPreferences, id: String, name: String) {
        val clean = name.trim().replace('\n', ' ').replace('\t', ' ').take(NAME_MAX).trim()
        p.edit().apply {
            if (clean.isEmpty()) remove("keyname_$id") else putString("keyname_$id", clean)
        }.apply()
    }

    fun describe(c: Context, action: String): String {
        if (action.isEmpty()) return c.getString(R.string.key_unset)
        if (action == "none") return c.getString(R.string.key_block)
        if (!available(c, action)) return c.getString(R.string.action_unavailable)
        val arg = action.substringAfter(':', "")
        return when (action.substringBefore(':')) {
            "app" -> try {
                c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(arg, 0)).toString()
            } catch (e: Exception) {
                c.getString(R.string.action_unavailable)
            }
            "hdmi" -> try {
                c.getSystemService(TvInputManager::class.java).getTvInputInfo(arg)!!.loadLabel(c).toString()
            } catch (e: Exception) {
                c.getString(R.string.action_unavailable)
            }
            "media" -> MEDIA[arg]?.let { c.getString(it.second) } ?: c.getString(R.string.action_unavailable)
            "sys" -> SYS[arg]?.let { c.getString(it.second) } ?: c.getString(R.string.action_unavailable)
            "nav" -> NAV[arg]?.let { c.getString(it.second) } ?: c.getString(R.string.action_unavailable)
            "open" -> OPEN[arg]?.let { c.getString(it.second) } ?: c.getString(R.string.action_unavailable)
            "panel" -> c.getString(R.string.act_home_panel)
            else -> c.getString(R.string.action_unavailable)
        }
    }

    private const val SMART_HOME = "com.google.android.libraries.tv.smarthome.intent.action.OPEN_SMART_HOME"
    private const val SMART_HOME_PACKAGE = "com.google.android.apps.tv.dreamx"
    private const val SMART_HOME_SOURCE = 5 // the remote's Google Home button

    private const val NAME_MAX = 24
}

