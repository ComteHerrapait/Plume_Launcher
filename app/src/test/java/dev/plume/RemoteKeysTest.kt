package dev.plume

import android.content.SharedPreferences
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RemoteKeysTest {
    /** Only what RemoteKeys uses: one string in, one string out. */
    private class FakePrefs : SharedPreferences {
        val strings = HashMap<String, String>()
        override fun getString(key: String?, defValue: String?) = strings[key] ?: defValue
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor = also { strings[key!!] = value!! }
            override fun apply() {}
            override fun commit() = true
            override fun putStringSet(key: String?, values: MutableSet<String>?) = throw UnsupportedOperationException()
            override fun putInt(key: String?, value: Int) = throw UnsupportedOperationException()
            override fun putLong(key: String?, value: Long) = throw UnsupportedOperationException()
            override fun putFloat(key: String?, value: Float) = throw UnsupportedOperationException()
            override fun putBoolean(key: String?, value: Boolean) = throw UnsupportedOperationException()
            override fun remove(key: String?) = also { strings.remove(key) }
            override fun clear() = throw UnsupportedOperationException()
        }
        override fun getAll() = throw UnsupportedOperationException()
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = throw UnsupportedOperationException()
        override fun getInt(key: String?, defValue: Int) = throw UnsupportedOperationException()
        override fun getLong(key: String?, defValue: Long) = throw UnsupportedOperationException()
        override fun getFloat(key: String?, defValue: Float) = throw UnsupportedOperationException()
        override fun getBoolean(key: String?, defValue: Boolean) = throw UnsupportedOperationException()
        override fun contains(key: String?) = throw UnsupportedOperationException()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    @Before fun fresh() = RemoteKeys.reset() // Remap keeps its keys in memory between calls

    @Test fun putThenAllGivesBackTheSameEntries() {
        val p = FakePrefs()
        RemoteKeys.put(p, "k183", "app:com.netflix.ninja", "sys:back")
        RemoteKeys.put(p, "s4001", "media:playpause", "")
        fun check(read: Map<String, Remap.Entry>) {
            assertEquals(listOf("k183", "s4001"), read.keys.toList())
            assertEquals("app:com.netflix.ninja", read["k183"]!!.short)
            assertEquals("sys:back", read["k183"]!!.long)
            assertEquals("media:playpause", read["s4001"]!!.short)
            assertEquals("", read["s4001"]!!.long)
        }
        check(RemoteKeys.all(p)) // from memory
        RemoteKeys.reset()
        check(RemoteKeys.all(p)) // from the saved string
    }

    @Test fun anUnnamedKeyWithNoActionsStaysNotSet() {
        val p = FakePrefs()
        RemoteKeys.put(p, "k183", "app:com.netflix.ninja", "")
        RemoteKeys.put(p, "k184", "none", "")
        RemoteKeys.put(p, "k183", "", "")
        val keys = RemoteKeys.all(p)
        assertEquals(listOf("k183", "k184"), keys.keys.toList())
        assertEquals("", keys["k183"]!!.short)
        assertEquals("", keys["k183"]!!.long)
        assertEquals("none", keys["k184"]!!.short)
        RemoteKeys.reset()
        val saved = RemoteKeys.all(p)
        assertEquals(listOf("k183", "k184"), saved.keys.toList())
        assertEquals("", saved["k183"]!!.short)
        assertEquals("", saved["k183"]!!.long)
        assertEquals("none", saved["k184"]!!.short)
    }

    @Test fun aHoldSurvivesClearingPressAndTheKeyStaysWhenCleared() {
        val p = FakePrefs()
        RemoteKeys.put(p, "k188", "app:com.netflix.ninja", "sys:back")
        RemoteKeys.put(p, "k188", "", "sys:back") // clearing Press keeps the saved Hold
        val kept = RemoteKeys.all(p)["k188"]!!
        assertEquals("", kept.short)
        assertEquals("sys:back", kept.long)
        RemoteKeys.put(p, "k188", "", "") // clearing the Hold leaves an empty key
        val empty = RemoteKeys.all(p)["k188"]!!
        assertEquals("", empty.short)
        assertEquals("", empty.long)
        RemoteKeys.reset()
        val saved = RemoteKeys.all(p)["k188"]!!
        assertEquals("", saved.short)
        assertEquals("", saved.long)
    }

    @Test fun anOldNotSetKeyMovesToTheDisabledList() {
        val p = FakePrefs()
        // Older fork builds saved an empty entry in upstream's keymap.
        p.edit().putString("keymap", "k188\t\t").apply()
        Remap.reset()
        val keys = RemoteKeys.all(p)
        assertEquals("", keys["k188"]!!.short)
        assertEquals("", keys["k188"]!!.long)
        assertEquals("k188", p.getString("keymap_disabled", ""))
    }

    @Test fun clearingAnAbsentKeyDoesNothing() {
        val p = FakePrefs()
        RemoteKeys.put(p, "k184", "none", "")
        RemoteKeys.put(p, "k188", "", "")
        assertEquals(listOf("k184"), RemoteKeys.all(p).keys.toList())
        RemoteKeys.reset()
        assertEquals(listOf("k184"), RemoteKeys.all(p).keys.toList())
    }

    @Test fun aCustomNameOverridesTheAutomaticOneAndCanBeCleared() {
        val p = FakePrefs()
        RemoteKeys.rename(p, "k188", "  UP replacement  ")
        assertEquals("UP replacement", RemoteKeys.customName(p, "k188"))
        RemoteKeys.rename(p, "k188", "   ")
        assertEquals(null, RemoteKeys.customName(p, "k188"))
    }

    @Test fun removingAKeyForgetsItsName() {
        val p = FakePrefs()
        RemoteKeys.put(p, "k188", "nav:up", "")
        RemoteKeys.rename(p, "k188", "UP replacement")
        RemoteKeys.remove(p, "k188")
        assertEquals(null, RemoteKeys.customName(p, "k188"))
        assertTrue(RemoteKeys.all(p).isEmpty())
    }

    @Test fun aNamedKeySurvivesClearingBothActions() {
        val p = FakePrefs()
        RemoteKeys.put(p, "k188", "app:com.netflix.ninja", "sys:back")
        RemoteKeys.rename(p, "k188", "My button")
        RemoteKeys.put(p, "k188", "", "sys:back") // clear Press
        RemoteKeys.put(p, "k188", "", "") // clear Hold
        val kept = RemoteKeys.all(p)["k188"]!!
        assertEquals("", kept.short)
        assertEquals("", kept.long)
        assertEquals("My button", RemoteKeys.customName(p, "k188"))
        RemoteKeys.reset()
        val saved = RemoteKeys.all(p)["k188"]!!
        assertEquals("", saved.short)
        assertEquals("", saved.long)
        assertEquals("My button", RemoteKeys.customName(p, "k188"))
    }

    @Test fun aCustomNameIsCapped() {
        val p = FakePrefs()
        RemoteKeys.rename(p, "k188", "x".repeat(40))
        assertEquals(24, RemoteKeys.customName(p, "k188")!!.length)
    }

    @Test fun actionsThatDoNotWorkOnTvAreNotOffered() {
        assertFalse("recents" in RemoteKeys.SYS)
        assertFalse("quick" in RemoteKeys.SYS)
        assertFalse("stop" in RemoteKeys.MEDIA)
    }

    @Test fun idFallsBackToTheScanCode() {
        assertEquals("k183", RemoteKeys.id(KeyEvent.KEYCODE_PROG_RED, 398))
        assertEquals("s398", RemoteKeys.id(KeyEvent.KEYCODE_UNKNOWN, 398))
    }

    @Test fun keptRefusesKeysThatMustNotChange() {
        assertTrue(RemoteKeys.kept(KeyEvent.KEYCODE_BACK, 158))
        assertTrue(RemoteKeys.kept(KeyEvent.KEYCODE_POWER, 116))
        assertTrue(RemoteKeys.kept(KeyEvent.KEYCODE_VOLUME_UP, 115))
        assertTrue(RemoteKeys.kept(KeyEvent.KEYCODE_VOLUME_DOWN, 114))
        assertFalse(RemoteKeys.kept(KeyEvent.KEYCODE_VOLUME_MUTE, 113)) // mute can be given a new job
        assertTrue(RemoteKeys.kept(KeyEvent.KEYCODE_UNKNOWN, 0)) // no code at all: every such key would share one identity
        assertFalse(RemoteKeys.kept(KeyEvent.KEYCODE_UNKNOWN, 398)) // a scan code is enough to tell it apart
        assertFalse(RemoteKeys.kept(KeyEvent.KEYCODE_PROG_RED, 398))
    }
}
