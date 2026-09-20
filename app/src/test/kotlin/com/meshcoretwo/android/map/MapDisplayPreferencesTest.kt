// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.map

import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [MapDisplayPreferences] against [FakeSharedPreferences], a hand-written in-memory test
 * double — the object only ever touches the [SharedPreferences] interface it's handed, so no
 * Robolectric is needed (same shape as `RecentHopsStoreTest`).
 */
class MapDisplayPreferencesTest {
    private val prefs = FakeSharedPreferences()

    @Test
    fun `clustering is on before anything is stored`() {
        assertTrue(MapDisplayPreferences.isClusteringEnabled(prefs))
    }

    @Test
    fun `toggling off round-trips`() {
        MapDisplayPreferences.setClusteringEnabled(prefs, false)

        assertFalse(MapDisplayPreferences.isClusteringEnabled(prefs))
    }

    @Test
    fun `toggling back on round-trips`() {
        MapDisplayPreferences.setClusteringEnabled(prefs, false)
        MapDisplayPreferences.setClusteringEnabled(prefs, true)

        assertTrue(MapDisplayPreferences.isClusteringEnabled(prefs))
    }
}

internal class FakeSharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Boolean>()

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] ?: defValue

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Boolean>()

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { pending[key!!] = value }

        override fun apply() {
            values.putAll(pending)
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun putString(key: String?, value: String?) = error("not used by this test")
        override fun putStringSet(key: String?, values: MutableSet<String>?) = error("not used by this test")
        override fun putInt(key: String?, value: Int) = error("not used by this test")
        override fun putLong(key: String?, value: Long) = error("not used by this test")
        override fun putFloat(key: String?, value: Float) = error("not used by this test")
        override fun remove(key: String?) = error("not used by this test")
        override fun clear(): SharedPreferences.Editor = error("not used by this test")
    }

    override fun getAll(): MutableMap<String, *> = error("not used by this test")
    override fun getString(key: String?, defValue: String?) = error("not used by this test")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?) = error("not used by this test")
    override fun getInt(key: String?, defValue: Int) = error("not used by this test")
    override fun getLong(key: String?, defValue: Long) = error("not used by this test")
    override fun getFloat(key: String?, defValue: Float) = error("not used by this test")
    override fun contains(key: String?) = error("not used by this test")
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = error("not used by this test")
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = error("not used by this test")
}
