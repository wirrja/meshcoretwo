// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Exercises [RecentEmojiStore] against [FakeSharedPreferences] — same in-memory double and
 * rationale as `RecentHopsStoreTest`, this store's own logic never calls into any Android
 * framework method body either.
 */
class RecentEmojiStoreTest {
    private lateinit var prefs: FakeSharedPreferences
    private lateinit var store: RecentEmojiStore

    @Before
    fun setUp() {
        prefs = FakeSharedPreferences()
        store = RecentEmojiStore(prefs)
    }

    @Test
    fun `load falls back to defaults when nothing recorded yet`() {
        assertEquals(RecentEmojiStore.DEFAULTS, store.load())
    }

    @Test
    fun `record then load round-trips a single emoji`() {
        val recorded = store.recordUsage("🎉", current = emptyList())

        assertEquals(listOf("🎉"), recorded)
        assertEquals(listOf("🎉"), store.load())
    }

    @Test
    fun `record inserts newest first`() {
        var current = store.recordUsage("🎉", emptyList())
        current = store.recordUsage("🔥", current)

        assertEquals(listOf("🔥", "🎉"), current)
    }

    @Test
    fun `re-recording an existing emoji moves it to the front instead of duplicating`() {
        var current = store.recordUsage("🎉", emptyList())
        current = store.recordUsage("🔥", current)
        current = store.recordUsage("🎉", current)

        assertEquals(listOf("🎉", "🔥"), current)
    }

    @Test
    fun `recording past the limit trims the oldest entries`() {
        var current = emptyList<String>()
        val emoji = ('a'..'z').take(RecentEmojiStore.LIMIT + 3).map { it.toString() }
        for (e in emoji) current = store.recordUsage(e, current)

        assertEquals(RecentEmojiStore.LIMIT, current.size)
        assertEquals(emoji.last(), current.first())
    }
}

/** Minimal in-memory [SharedPreferences] fake — only the string get/put path these stores use is implemented. */
internal class FakeSharedPreferences : SharedPreferences {
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
