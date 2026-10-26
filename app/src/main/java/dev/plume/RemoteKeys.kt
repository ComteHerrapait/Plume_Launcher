package dev.plume

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.tv.TvInputManager
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast

/**
 * The fork's remote-key layer on top of upstream [Remap].
 *
 * Upstream keeps the key storage and the action execution. This file owns the fork-only pieces:
 * custom names, "Not set" keys, the extra actions and the availability checks. Keeping them here
 * means future upstream changes to [Remap] do not collide with the fork's remote-key work.
 */
object RemoteKeys {
    val EMPTY = Remap.Entry("", "")

    val MEDIA = linkedMapOf(
        "playpause" to (KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to R.string.act_play_pause),
        "next" to (KeyEvent.KEYCODE_MEDIA_NEXT to R.string.act_next),
        "previous" to (KeyEvent.KEYCODE_MEDIA_PREVIOUS to R.string.act_previous),
        "rewind" to (KeyEvent.KEYCODE_MEDIA_REWIND to R.string.act_rewind),
        "forward" to (KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to R.string.act_forward),
    )

    val SYS = linkedMapOf(
        "back" to (AccessibilityService.GLOBAL_ACTION_BACK to R.string.act_back),
        "notifications" to (AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS to R.string.act_dashboard),
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

    /** Set while the settings page waits for a key to change; the service must see keys meanwhile. */
    var learn: ((KeyEvent) -> Unit)?
        get() = Remap.learn
        set(value) {
            Remap.learn = value
            HomeOverride.running?.applyKeyFilter()
        }

    /** The key whose press was just taken to be learned: its repeats and its release are swallowed too. */
    var pending: String?
        get() = Remap.pending
        set(value) { Remap.pending = value }

    fun id(e: KeyEvent) = Remap.id(e)

    internal fun id(keyCode: Int, scanCode: Int) = Remap.id(keyCode, scanCode)

    /** Upstream keeps Mute protected; the fork lets the user give it a job. */
    fun kept(e: KeyEvent) = kept(e.keyCode, e.scanCode)

    internal fun kept(keyCode: Int, scanCode: Int) =
        Remap.kept(keyCode, scanCode) && keyCode != KeyEvent.KEYCODE_VOLUME_MUTE

    fun all(c: Context) = all(store(c))

    /** Upstream's active keys plus the fork's "Not set" keys, in the order the user added them. */
    internal fun all(p: SharedPreferences): LinkedHashMap<String, Remap.Entry> {
        val active = Remap.all(p)
        val off = disabled(p)
        // Older fork builds kept "Not set" keys in upstream's map. Move them to the disabled list.
        for ((id, e) in active.entries.toList()) {
            if (e.short.isEmpty() && e.long.isEmpty()) {
                Remap.put(p, id, "", "")
                off += id
            }
        }
        if (off != disabled(p)) setDisabled(p, off)
        val ids = order(p)
        val out = LinkedHashMap<String, Remap.Entry>()
        for (id in ids) {
            val entry = active[id]
            if (entry != null) out[id] = entry else if (id in off) out[id] = Remap.Entry("", "")
        }
        // Keys saved before the fork's order list existed: append them rather than hide them.
        for ((id, e) in active) if (id !in out) { out[id] = e; ids += id }
        for (id in off) if (id !in out) { out[id] = Remap.Entry("", ""); ids += id }
        if (ids != order(p)) setOrder(p, ids)
        return out
    }

    /** Saves a key's actions. Clearing both keeps the key as "Not set"; only [remove] deletes it. */
    fun put(c: Context, id: String, short: String, long: String) {
        put(store(c), id, short, long)
        HomeOverride.running?.applyKeyFilter()
    }

    internal fun put(p: SharedPreferences, id: String, short: String, long: String) {
        val m = all(p)
        if (short.isEmpty() && long.isEmpty()) {
            if (id !in m) return
            Remap.put(p, id, "", "") // upstream drops empty keys; the fork keeps them separately
            setDisabled(p, disabled(p) + id)
        } else {
            Remap.put(p, id, short, long)
            setDisabled(p, disabled(p) - id)
        }
        val ids = order(p)
        if (id !in ids) { ids += id; setOrder(p, ids) }
    }

    /** Removes a key and its custom name. */
    fun remove(c: Context, id: String) = remove(store(c), id)

    internal fun remove(p: SharedPreferences, id: String) {
        Remap.put(p, id, "", "")
        setDisabled(p, disabled(p) - id)
        val ids = order(p)
        ids -= id
        setOrder(p, ids)
        p.edit().remove("keyname_$id").apply()
        HomeOverride.running?.applyKeyFilter()
    }

    fun reset() = Remap.reset()

    /** Whether any key has a real action; "Not set" keys do not need key filtering. */
    fun active(c: Context): Boolean {
        all(c) // migrates old "Not set" keys
        return Remap.all(c).isNotEmpty()
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

    fun run(s: AccessibilityService, action: String) {
        if (action == "none") return
        if (!available(s, action)) return showToast(s, R.string.action_unavailable)
        val arg = action.substringAfter(':', "")
        try {
            when (action.substringBefore(':')) {
                // Upstream owns these: app launch, Fire TV input switch, media keys and global actions.
                "app", "hdmi", "media", "sys" -> Remap.run(s, action)
                "nav" -> if (Build.VERSION.SDK_INT >= 33) NAV[arg]?.let { (code, _) ->
                    if (!s.performGlobalAction(code)) showToast(s, R.string.action_unavailable)
                } else showToast(s, R.string.action_unavailable)
                "open" -> OPEN[arg]?.let { (intent, _) ->
                    s.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                "panel" -> if (arg == "home") s.startActivity(
                    Intent(SMART_HOME).setPackage(SMART_HOME_PACKAGE).addCategory(Intent.CATEGORY_DEFAULT)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("trigger_source", SMART_HOME_SOURCE)
                ) else showToast(s, R.string.action_unavailable)
            }
        } catch (e: Exception) {
            showToast(s, R.string.action_failed)
        }
    }

    /** What a key is called in the list: its custom name, else upstream's automatic name. */
    fun name(c: Context, id: String): String = customName(c, id) ?: Remap.name(c, id)

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
            "media" -> MEDIA[arg]?.let { c.getString(it.second) } ?: c.getString(R.string.action_unavailable)
            "sys" -> SYS[arg]?.let { c.getString(it.second) } ?: c.getString(R.string.action_unavailable)
            "nav" -> NAV[arg]?.let { c.getString(it.second) } ?: c.getString(R.string.action_unavailable)
            "open" -> OPEN[arg]?.let { c.getString(it.second) } ?: c.getString(R.string.action_unavailable)
            "panel" -> c.getString(R.string.act_home_panel)
            else -> Remap.describe(c, action)
        }
    }

    /** Whether this device has the Google TV smart-home panel. */
    fun panelAvailable(c: Context) = c.packageManager.queryIntentActivities(
        Intent(SMART_HOME).setPackage(SMART_HOME_PACKAGE).addCategory(Intent.CATEGORY_DEFAULT), 0
    ).isNotEmpty()

    private fun launchIntent(c: Context, pkg: String) =
        c.packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: c.packageManager.getLaunchIntentForPackage(pkg)

    private fun showToast(c: Context, message: Int) {
        Toast.makeText(c, message, Toast.LENGTH_SHORT).show()
    }

    private fun store(c: Context) = c.getSharedPreferences("home", Context.MODE_PRIVATE)

    private fun order(p: SharedPreferences): LinkedHashSet<String> =
        LinkedHashSet(p.getString("keymap_order", "")!!.lines().filter { it.isNotEmpty() })

    private fun setOrder(p: SharedPreferences, ids: Set<String>) {
        p.edit().putString("keymap_order", ids.joinToString("\n")).apply()
    }

    private fun disabled(p: SharedPreferences): LinkedHashSet<String> =
        LinkedHashSet(p.getString("keymap_disabled", "")!!.lines().filter { it.isNotEmpty() })

    private fun setDisabled(p: SharedPreferences, ids: Set<String>) {
        p.edit().putString("keymap_disabled", ids.joinToString("\n")).apply()
    }

    private const val SMART_HOME = "com.google.android.libraries.tv.smarthome.intent.action.OPEN_SMART_HOME"
    private const val SMART_HOME_PACKAGE = "com.google.android.apps.tv.dreamx"
    private const val SMART_HOME_SOURCE = 5 // the remote's Google Home button
    private const val NAME_MAX = 24
}
