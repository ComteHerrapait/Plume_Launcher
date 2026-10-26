package dev.plume

import android.content.Context
import android.os.Build
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import kotlin.math.roundToInt

/**
 * The remote-key settings pages.
 *
 * Kept out of [MainActivity] so upstream's settings changes only need the small [Host] block in that file.
 * MainActivity supplies the row/header/text-field widgets and the page stack.
 */
class RemoteKeysUi(private val host: Host) {
    interface Host {
        val context: Context
        val chosen: String
        fun showRows(title: String, rows: List<Triple<String, String, () -> Unit>>, focus: Int = 0, back: () -> Unit)
        fun showContent(title: String, content: View, back: () -> Unit, onExit: (() -> Unit)? = null)
        fun row(title: String, value: String, action: () -> Unit): View
        fun header(text: String): View
        fun hint(text: String): View
        fun mute(row: View)
        fun textField(initial: String, hint: String, done: (String) -> Unit): EditText
        fun toast(res: Int)
        fun toastLong(res: Int)
        fun post(action: () -> Unit)
        fun appsAndInputs(): List<Pair<String, String>>
        fun backToSettings()
    }

    fun summary() = Remap.all(host.context).size.let {
        host.context.resources.getQuantityString(R.plurals.n_keys, it, it)
    }

    fun pickKeys(focus: Int = 0) {
        val c = host.context
        val keys = Remap.all(c).entries.toList()
        val rows = listOf(page(c.getString(R.string.key_add), "") { learnKey() }) + keys.map { (id, e) ->
            val summary = if (e.short.isEmpty() && e.long.isNotEmpty())
                "${Remap.describe(c, e.long)} · ${c.getString(R.string.inactive)}"
            else Remap.describe(c, e.short.ifEmpty { e.long })
            page(Remap.name(c, id), summary) { keyPage(id) }
        }
        host.showRows(c.getString(R.string.section_keys), rows, focus) { host.backToSettings() }
    }

    /** Waits for the next key press (Back and the arrows are left alone so the page can always be left). */
    private fun learnKey() {
        if (HomeOverride.running == null) return host.toastLong(R.string.home_on_hint)
        val c = host.context
        Remap.learn = { e ->
            val id = Remap.id(e) // read now: the framework recycles the event as soon as the service returns
            host.post { Remap.learn = null; keyPage(id) }
        }
        host.showContent(c.getString(R.string.key_add), host.hint(c.getString(R.string.key_press_prompt)), back = {
            Remap.learn = null
            pickKeys()
        }, onExit = { Remap.learn = null })
    }

    private fun keyPage(id: String, focus: Int = 0) {
        val c = host.context
        val e = Remap.all(c)[id] ?: Remap.Entry("", "")
        val pressSet = e.short.isNotEmpty()
        val holdSaved = e.long.isNotEmpty()
        val holdValue = when {
            pressSet -> Remap.describe(c, e.long)
            holdSaved -> "${Remap.describe(c, e.long)} · ${c.getString(R.string.inactive)}"
            else -> c.getString(R.string.key_hold_needs_press)
        }
        showCustom(Remap.name(c, id), listOf(
            Item.Row(c.getString(R.string.key_short), Remap.describe(c, e.short)) { pickAction(id, false) },
            Item.Row(c.getString(R.string.key_long), holdValue, muted = !pressSet) {
                if (pressSet || holdSaved) pickAction(id, true) else host.toast(R.string.key_hold_needs_press)
            },
            Item.Row(c.getString(R.string.key_name), Remap.customName(c, id) ?: c.getString(R.string.automatic)) { renameKey(id) },
            Item.Row(c.getString(R.string.key_remove), "") { Remap.remove(c, id); pickKeys() },
        ), focus, back = { pickKeys() })
    }

    /** Renames a key. An empty name, or Automatic, brings back the name Android gives it ("Button 4"). */
    private fun renameKey(id: String) {
        val c = host.context
        val custom = Remap.customName(c, id)
        val field = host.textField(custom.orEmpty(), Remap.name(c, id)) { name ->
            Remap.rename(c, id, name)
            keyPage(id, 2)
        }
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        box.addView(field.parent as View, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val auto = host.row(c.getString(R.string.automatic), if (custom == null) host.chosen else "") {
            Remap.rename(c, id, "")
            keyPage(id, 2)
        }
        box.addView(auto, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(4) })
        host.showContent(c.getString(R.string.key_name), box, back = { keyPage(id, 2) })
        auto.nextFocusUpId = field.id
        field.requestFocus()
    }

    /** Saves [action] as what [id] does on a press (or a hold) and returns to the key's page. */
    private fun setAction(id: String, hold: Boolean, action: String) {
        val c = host.context
        val e = Remap.all(c)[id] ?: Remap.Entry("", "")
        if (hold && e.short.isEmpty() && action.isNotEmpty()) return host.toast(R.string.key_hold_needs_press)
        if (hold) Remap.put(c, id, e.short, action) else Remap.put(c, id, action, e.long)
        if (Remap.all(c).containsKey(id)) keyPage(id, if (hold) 1 else 0) else pickKeys()
    }

    private fun pickAction(id: String, hold: Boolean) {
        val c = host.context
        val e = Remap.all(c)[id] ?: Remap.Entry("", "")
        if (hold && e.short.isEmpty()) {
            host.showRows(c.getString(R.string.key_long), listOf(
                page(c.getString(R.string.key_unset), if (e.long.isEmpty()) host.chosen else "") { setAction(id, true, "") },
            )) { keyPage(id, 1) }
            return
        }
        val now = if (hold) e.long else e.short
        val items = ArrayList<Item>()
        fun header(res: Int) { items += Item.Header(c.getString(res)) }
        fun actionRow(action: String, label: Int) {
            items += Item.Row(c.getString(label), if (action == now) host.chosen else "") { setAction(id, hold, action) }
        }

        header(R.string.group_apps)
        items += Item.Row(c.getString(R.string.key_open_app), if (now.startsWith("app:") || now.startsWith("hdmi:")) Remap.describe(c, now) else "") {
            pickApp(id, hold, now)
        }

        header(R.string.group_system)
        val panel = Remap.panelAvailable(c)
        Remap.SYS.forEach { (k, v) ->
            actionRow("sys:$k", v.second)
            if (k == "notifications" && panel) actionRow("panel:home", R.string.act_home_panel)
        }
        Remap.OPEN.forEach { (k, v) -> if (Remap.available(c, "open:$k")) actionRow("open:$k", v.second) }

        header(R.string.group_media)
        Remap.MEDIA.forEach { (k, v) -> actionRow("media:$k", v.second) }

        if (Build.VERSION.SDK_INT >= 33) {
            header(R.string.group_nav)
            Remap.NAV.forEach { (k, v) -> actionRow("nav:$k", v.second) }
        }

        header(R.string.group_other)
        actionRow("", R.string.key_unset)
        actionRow("none", R.string.key_block)

        showCustom(c.getString(if (hold) R.string.key_long else R.string.key_short), items, 0, back = { keyPage(id, if (hold) 1 else 0) })
    }

    private fun pickApp(id: String, hold: Boolean, now: String) {
        val c = host.context
        val rows = host.appsAndInputs().map { (label, action) ->
            page(label, if (action == now) host.chosen else "") { setAction(id, hold, action) }
        }
        host.showRows(c.getString(R.string.key_open_app), rows) { pickAction(id, hold) }
    }

    private sealed class Item {
        class Header(val text: String) : Item()
        class Row(val title: String, val value: String, val muted: Boolean = false, val action: () -> Unit) : Item()
    }

    /** A page with group headings or a muted row, built as one block under the normal page title. */
    private fun showCustom(title: String, items: List<Item>, focus: Int, back: () -> Unit, onExit: (() -> Unit)? = null) {
        val box = LinearLayout(host.context).apply { orientation = LinearLayout.VERTICAL }
        val rows = ArrayList<View>()
        for (item in items) when (item) {
            is Item.Header -> box.addView(host.header(item.text))
            is Item.Row -> {
                val row = host.row(item.title, item.value, item.action)
                if (item.muted) host.mute(row)
                box.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(4) })
                rows += row
            }
        }
        if (rows.isNotEmpty()) {
            rows.first().nextFocusUpId = rows.last().id
            rows.last().nextFocusDownId = rows.first().id
        }
        host.showContent(title, box, back, onExit)
        rows.getOrNull(focus)?.requestFocus()
    }

    private fun page(title: String, value: String, action: () -> Unit) = Triple(title, value, action)

    private fun dp(value: Int) = (value * host.context.resources.displayMetrics.density).roundToInt()
}
