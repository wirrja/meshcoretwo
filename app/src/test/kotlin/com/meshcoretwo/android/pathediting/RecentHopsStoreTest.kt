// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.pathediting

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Exercises [RecentHopsStore] against [FakeSharedPreferences], a hand-written in-memory test
 * double — this store's own logic never calls into any Android framework method body (only the
 * [SharedPreferences]/[SharedPreferences.Editor] interfaces it's given), so a plain JUnit test
 * works without Robolectric.
 */
class RecentHopsStoreTest {
    private lateinit var prefs: FakeSharedPreferences
    private lateinit var store: RecentHopsStore
    private val radioID = UUID.randomUUID()

    @Before
    fun setUp() {
        prefs = FakeSharedPreferences()
        store = RecentHopsStore(prefs)
    }

    private fun key(vararg bytes: Int) = bytes.map { it.toByte() }.toByteArray()

    @Test
    fun `load returns empty for a radio with no recorded recents`() {
        assertTrue(store.load(radioID).isEmpty())
    }

    @Test
    fun `record then load round-trips a single key`() {
        val recorded = store.record(key(0xAA, 0xBB), current = emptyList(), radioID = radioID)

        assertEquals(1, recorded.size)
        assertTrue(recorded[0].contentEquals(key(0xAA, 0xBB)))
        assertEquals(1, store.load(radioID).size)
        assertTrue(store.load(radioID)[0].contentEquals(key(0xAA, 0xBB)))
    }

    @Test
    fun `record inserts newest first`() {
        var current = store.record(key(0x01), emptyList(), radioID)
        current = store.record(key(0x02), current, radioID)

        assertEquals(listOf(key(0x02).toList(), key(0x01).toList()), current.map { it.toList() })
    }

    @Test
    fun `re-recording an existing key moves it to the front instead of duplicating`() {
        var current = store.record(key(0x01), emptyList(), radioID)
        current = store.record(key(0x02), current, radioID)
        current = store.record(key(0x01), current, radioID)

        assertEquals(listOf(key(0x01).toList(), key(0x02).toList()), current.map { it.toList() })
    }

    @Test
    fun `recording past the limit trims the oldest entries`() {
        var current = emptyList<ByteArray>()
        for (i in 0 until RecentHopsStore.LIMIT + 3) {
            current = store.record(key(i), current, radioID)
        }

        assertEquals(RecentHopsStore.LIMIT, current.size)
        assertEquals(key(RecentHopsStore.LIMIT + 2).toList(), current.first().toList())
    }

    @Test
    fun `recents for different radios don't leak into each other`() {
        val otherRadio = UUID.randomUUID()
        store.record(key(0xAA), emptyList(), radioID)
        store.record(key(0xBB), emptyList(), otherRadio)

        assertTrue(store.load(radioID).single().contentEquals(key(0xAA)))
        assertTrue(store.load(otherRadio).single().contentEquals(key(0xBB)))
    }
}

/** Minimal in-memory [SharedPreferences] fake — only the string get/put path [RecentHopsStore] uses is implemented. */
private class FakeSharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, String>()

    override fun getString(key: String?, defValue: String?): String? = values[key] ?: defValue

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, String?>()

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { pending[key!!] = value }

        override fun apply() {
            pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?) = error("not used by this test")
        override fun putInt(key: String?, value: Int) = error("not used by this test")
        override fun putLong(key: String?, value: Long) = error("not used by this test")
        override fun putFloat(key: String?, value: Float) = error("not used by this test")
        override fun putBoolean(key: String?, value: Boolean) = error("not used by this test")
        override fun remove(key: String?) = apply { pending[key!!] = null }
        override fun clear(): SharedPreferences.Editor = error("not used by this test")
    }

    override fun getAll(): MutableMap<String, *> = error("not used by this test")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?) = error("not used by this test")
    override fun getInt(key: String?, defValue: Int) = error("not used by this test")
    override fun getLong(key: String?, defValue: Long) = error("not used by this test")
    override fun getFloat(key: String?, defValue: Float) = error("not used by this test")
    override fun getBoolean(key: String?, defValue: Boolean) = error("not used by this test")
    override fun contains(key: String?) = error("not used by this test")
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = error("not used by this test")
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = error("not used by this test")
}
