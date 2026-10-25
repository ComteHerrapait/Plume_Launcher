package dev.plume

import android.content.SharedPreferences
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RemapTest {
    /** Only what Remap uses: one string in, one string out. */
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

    @Before fun fresh() = Remap.reset() // Remap keeps its keys in memory between calls

    @Test fun putThenAllGivesBackTheSameEntries() {
        val p = FakePrefs()
        Remap.put(p, "k183", "app:com.netflix.ninja", "sys:back")
        Remap.put(p, "s4001", "media:playpause", "")
        fun check(read: Map<String, Remap.Entry>) {
            assertEquals(listOf("k183", "s4001"), read.keys.toList())
            assertEquals("app:com.netflix.ninja", read["k183"]!!.short)
            assertEquals("sys:back", read["k183"]!!.long)
            assertEquals("media:playpause", read["s4001"]!!.short)
            assertEquals("", read["s4001"]!!.long)
        }
        check(Remap.all(p)) // from memory
        Remap.reset()
        check(Remap.all(p)) // from the saved string
    }

    @Test fun anUnnamedKeyWithNoActionsStaysNotSet() {
        val p = FakePrefs()
        Remap.put(p, "k183", "app:com.netflix.ninja", "")
        Remap.put(p, "k184", "none", "")
        Remap.put(p, "k183", "", "")
        val keys = Remap.all(p)
        assertEquals(listOf("k183", "k184"), keys.keys.toList())
        assertEquals("", keys["k183"]!!.short)
        assertEquals("", keys["k183"]!!.long)
        assertEquals("none", keys["k184"]!!.short)
        Remap.reset()
        val saved = Remap.all(p)
        assertEquals(listOf("k183", "k184"), saved.keys.toList())
        assertEquals("", saved["k183"]!!.short)
        assertEquals("", saved["k183"]!!.long)
        assertEquals("none", saved["k184"]!!.short)
    }

    @Test fun aHoldSurvivesClearingPressAndTheKeyStaysWhenCleared() {
        val p = FakePrefs()
        Remap.put(p, "k188", "app:com.netflix.ninja", "sys:back")
        Remap.put(p, "k188", "", "sys:back") // clearing Press keeps the saved Hold
        val kept = Remap.all(p)["k188"]!!
        assertEquals("", kept.short)
        assertEquals("sys:back", kept.long)
        Remap.put(p, "k188", "", "") // clearing the Hold leaves an empty key
        val empty = Remap.all(p)["k188"]!!
        assertEquals("", empty.short)
        assertEquals("", empty.long)
        Remap.reset()
        val saved = Remap.all(p)["k188"]!!
        assertEquals("", saved.short)
        assertEquals("", saved.long)
    }

    @Test fun clearingAnAbsentKeyDoesNothing() {
        val p = FakePrefs()
        Remap.put(p, "k184", "none", "")
        Remap.put(p, "k188", "", "")
        assertEquals(listOf("k184"), Remap.all(p).keys.toList())
        Remap.reset()
        assertEquals(listOf("k184"), Remap.all(p).keys.toList())
    }

    @Test fun aCustomNameOverridesTheAutomaticOneAndCanBeCleared() {
        val p = FakePrefs()
        Remap.rename(p, "k188", "  UP replacement  ")
        assertEquals("UP replacement", Remap.customName(p, "k188"))
        Remap.rename(p, "k188", "   ")
        assertEquals(null, Remap.customName(p, "k188"))
    }

    @Test fun removingAKeyForgetsItsName() {
        val p = FakePrefs()
        Remap.put(p, "k188", "nav:up", "")
        Remap.rename(p, "k188", "UP replacement")
        Remap.remove(p, "k188")
        assertEquals(null, Remap.customName(p, "k188"))
        assertTrue(Remap.all(p).isEmpty())
    }

    @Test fun aNamedKeySurvivesClearingBothActions() {
        val p = FakePrefs()
        Remap.put(p, "k188", "app:com.netflix.ninja", "sys:back")
        Remap.rename(p, "k188", "My button")
        Remap.put(p, "k188", "", "sys:back") // clear Press
        Remap.put(p, "k188", "", "") // clear Hold
        val kept = Remap.all(p)["k188"]!!
        assertEquals("", kept.short)
        assertEquals("", kept.long)
        assertEquals("My button", Remap.customName(p, "k188"))
        Remap.reset()
        val saved = Remap.all(p)["k188"]!!
        assertEquals("", saved.short)
        assertEquals("", saved.long)
        assertEquals("My button", Remap.customName(p, "k188"))
    }

    @Test fun aCustomNameIsCapped() {
        val p = FakePrefs()
        Remap.rename(p, "k188", "x".repeat(40))
        assertEquals(24, Remap.customName(p, "k188")!!.length)
    }

    @Test fun actionsThatDoNotWorkOnTvAreNotOffered() {
        assertFalse("recents" in Remap.SYS)
        assertFalse("quick" in Remap.SYS)
        assertFalse("stop" in Remap.MEDIA)
    }

    @Test fun idFallsBackToTheScanCode() {
        assertEquals("k183", Remap.id(KeyEvent.KEYCODE_PROG_RED, 398))
        assertEquals("s398", Remap.id(KeyEvent.KEYCODE_UNKNOWN, 398))
    }

    @Test fun keptRefusesKeysThatMustNotChange() {
        assertTrue(Remap.kept(KeyEvent.KEYCODE_BACK, 158))
        assertTrue(Remap.kept(KeyEvent.KEYCODE_POWER, 116))
        assertTrue(Remap.kept(KeyEvent.KEYCODE_VOLUME_UP, 115))
        assertTrue(Remap.kept(KeyEvent.KEYCODE_VOLUME_DOWN, 114))
        assertFalse(Remap.kept(KeyEvent.KEYCODE_VOLUME_MUTE, 113)) // mute can be given a new job
        assertTrue(Remap.kept(KeyEvent.KEYCODE_UNKNOWN, 0)) // no code at all: every such key would share one identity
        assertFalse(Remap.kept(KeyEvent.KEYCODE_UNKNOWN, 398)) // a scan code is enough to tell it apart
        assertFalse(Remap.kept(KeyEvent.KEYCODE_PROG_RED, 398))
    }
}
