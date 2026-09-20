// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat.emoji

import androidx.annotation.StringRes
import com.meshcoretwo.android.R

/** One emoji entry: the character itself, its display label, and a search shortcode. */
data class Emoji(val unicode: String, val label: String, val shortcode: String)

/** The eight standard Emojibase categories `EmojiProvider.swift` iterates, in the same order. */
enum class EmojiCategory(@StringRes val displayNameRes: Int) {
    PEOPLE(R.string.emoji_cat_people),
    NATURE(R.string.emoji_cat_nature),
    FOODS(R.string.emoji_cat_foods),
    ACTIVITY(R.string.emoji_cat_activity),
    PLACES(R.string.emoji_cat_places),
    OBJECTS(R.string.emoji_cat_objects),
    SYMBOLS(R.string.emoji_cat_symbols),
    FLAGS(R.string.emoji_cat_flags),
}

/** A titled group of [Emoji] to render as one section of the picker grid. */
data class EmojiSection(@StringRes val titleRes: Int, val emoji: List<Emoji>)

/**
 * A small hand-curated emoji dataset (~170 entries), standing in for `EmojiProvider.swift`'s
 * `EmojibaseStore` — there's no Kotlin/Compose binding for the `Emojibase` Swift package MeshCore
 * One depends on, so labels/shortcodes for the most common emoji in each of the eight standard
 * categories are reused directly from [emojibase-data](https://github.com/milesj/emojibase) (MIT;
 * see `THIRD_PARTY_NOTICES.md`'s "Bundled emoji data" section) instead of the full ~3600-entry set.
 * Country flags are excluded entirely (that would mean bundling the full ISO region list) — the
 * [EmojiCategory.FLAGS] category is limited to the handful of non-country flag emoji. Search only
 * matches [Emoji.label]/[Emoji.shortcode] substrings, not Emojibase's additional `tags` field,
 * which this dataset doesn't carry.
 */
object EmojiCatalog {
    val categories: List<Pair<EmojiCategory, List<Emoji>>> = listOf(
        EmojiCategory.PEOPLE to listOf(
            Emoji("😀", "grinning face", "grinning"),
            Emoji("😂", "face with tears of joy", "joy"),
            Emoji("🙂", "slightly smiling face", "slight_smile"),
            Emoji("😊", "smiling face with smiling eyes", "blush"),
            Emoji("😍", "smiling face with heart-eyes", "heart_eyes"),
            Emoji("😘", "face blowing a kiss", "kissing_heart"),
            Emoji("😜", "winking face with tongue", "stuck_out_tongue_winking_eye"),
            Emoji("🤔", "thinking face", "thinking"),
            Emoji("😎", "smiling face with sunglasses", "sunglasses"),
            Emoji("😢", "crying face", "cry"),
            Emoji("😭", "loudly crying face", "sob"),
            Emoji("😡", "pouting face", "rage"),
            Emoji("😱", "face screaming in fear", "scream"),
            Emoji("🥳", "partying face", "partying_face"),
            Emoji("🤗", "hugging face", "hugs"),
            Emoji("👍", "thumbs up", "+1"),
            Emoji("👎", "thumbs down", "-1"),
            Emoji("👏", "clapping hands", "clap"),
            Emoji("🙏", "folded hands", "pray"),
            Emoji("💪", "flexed biceps", "muscle"),
            Emoji("🤝", "handshake", "handshake"),
            Emoji("👋", "waving hand", "wave"),
            Emoji("✌️", "victory hand", "v"),
            Emoji("👌", "OK hand", "ok_hand"),
        ),
        EmojiCategory.NATURE to listOf(
            Emoji("🐶", "dog face", "dog"),
            Emoji("🐱", "cat face", "cat"),
            Emoji("🐭", "mouse face", "mouse"),
            Emoji("🐹", "hamster", "hamster"),
            Emoji("🐰", "rabbit face", "rabbit"),
            Emoji("🦊", "fox", "fox_face"),
            Emoji("🐻", "bear", "bear"),
            Emoji("🐼", "panda", "panda_face"),
            Emoji("🐨", "koala", "koala"),
            Emoji("🐯", "tiger face", "tiger"),
            Emoji("🦁", "lion", "lion"),
            Emoji("🐮", "cow face", "cow"),
            Emoji("🐷", "pig face", "pig"),
            Emoji("🐸", "frog", "frog"),
            Emoji("🐵", "monkey face", "monkey_face"),
            Emoji("🦉", "owl", "owl"),
            Emoji("🐺", "wolf", "wolf"),
            Emoji("🐴", "horse face", "horse"),
            Emoji("🌲", "evergreen tree", "evergreen_tree"),
            Emoji("🌸", "cherry blossom", "cherry_blossom"),
            Emoji("🌞", "sun with face", "sun_with_face"),
            Emoji("🌙", "crescent moon", "crescent_moon"),
            Emoji("⭐", "star", "star"),
            Emoji("🔥", "fire", "fire"),
        ),
        EmojiCategory.FOODS to listOf(
            Emoji("🍎", "red apple", "apple"),
            Emoji("🍌", "banana", "banana"),
            Emoji("🍕", "pizza", "pizza"),
            Emoji("🍔", "hamburger", "hamburger"),
            Emoji("🍟", "french fries", "fries"),
            Emoji("🌮", "taco", "taco"),
            Emoji("🍣", "sushi", "sushi"),
            Emoji("🍩", "doughnut", "doughnut"),
            Emoji("🍦", "soft ice cream", "icecream"),
            Emoji("🍰", "shortcake", "cake"),
            Emoji("🍫", "chocolate bar", "chocolate_bar"),
            Emoji("🍺", "beer mug", "beer"),
            Emoji("🍷", "wine glass", "wine_glass"),
            Emoji("☕", "hot beverage", "coffee"),
            Emoji("🍉", "watermelon", "watermelon"),
            Emoji("🍇", "grapes", "grapes"),
            Emoji("🥑", "avocado", "avocado"),
            Emoji("🥕", "carrot", "carrot"),
            Emoji("🍪", "cookie", "cookie"),
            Emoji("🧀", "cheese wedge", "cheese"),
        ),
        EmojiCategory.ACTIVITY to listOf(
            Emoji("⚽", "soccer ball", "soccer"),
            Emoji("🏀", "basketball", "basketball"),
            Emoji("🏈", "american football", "football"),
            Emoji("⚾", "baseball", "baseball"),
            Emoji("🎾", "tennis", "tennis"),
            Emoji("🏐", "volleyball", "volleyball"),
            Emoji("🎳", "bowling", "bowling"),
            Emoji("🏓", "ping pong", "ping_pong"),
            Emoji("🚴", "person biking", "bike"),
            Emoji("🏃", "person running", "runner"),
            Emoji("🏊", "person swimming", "swimmer"),
            Emoji("🎮", "video game", "video_game"),
            Emoji("🎸", "guitar", "guitar"),
            Emoji("🎲", "game die", "game_die"),
            Emoji("🎯", "direct hit", "dart"),
            Emoji("🏆", "trophy", "trophy"),
            Emoji("🥇", "first place medal", "first_place"),
            Emoji("🎨", "artist palette", "art"),
            Emoji("🎤", "microphone", "microphone"),
            Emoji("🎧", "headphone", "headphones"),
        ),
        EmojiCategory.PLACES to listOf(
            Emoji("🚗", "car", "car"),
            Emoji("🚕", "taxi", "taxi"),
            Emoji("🚌", "bus", "bus"),
            Emoji("🚀", "rocket", "rocket"),
            Emoji("✈️", "airplane", "airplane"),
            Emoji("🚁", "helicopter", "helicopter"),
            Emoji("🚂", "locomotive", "steam_locomotive"),
            Emoji("⛵", "sailboat", "boat"),
            Emoji("🏠", "house", "house"),
            Emoji("🏢", "office building", "office"),
            Emoji("🗼", "Tokyo tower", "tokyo_tower"),
            Emoji("🗻", "Mount Fuji", "mount_fuji"),
            Emoji("🏔️", "snow-capped mountain", "mountain_snow"),
            Emoji("🏝️", "desert island", "desert_island"),
            Emoji("🌋", "volcano", "volcano"),
            Emoji("🌉", "bridge at night", "bridge_at_night"),
            Emoji("🎡", "ferris wheel", "ferris_wheel"),
            Emoji("⛺", "tent", "tent"),
            Emoji("🌍", "globe showing Europe-Africa", "earth_africa"),
            Emoji("🗺️", "world map", "world_map"),
        ),
        EmojiCategory.OBJECTS to listOf(
            Emoji("📱", "mobile phone", "iphone"),
            Emoji("💻", "laptop", "computer"),
            Emoji("⌚", "watch", "watch"),
            Emoji("📷", "camera", "camera"),
            Emoji("🔋", "battery", "battery"),
            Emoji("💡", "light bulb", "bulb"),
            Emoji("🔦", "flashlight", "flashlight"),
            Emoji("🕯️", "candle", "candle"),
            Emoji("📡", "satellite antenna", "satellite"),
            Emoji("🔑", "key", "key"),
            Emoji("🔒", "locked", "lock"),
            Emoji("🔓", "unlocked", "unlock"),
            Emoji("📦", "package", "package"),
            Emoji("📖", "open book", "book"),
            Emoji("✏️", "pencil", "pencil2"),
            Emoji("📌", "pushpin", "pushpin"),
            Emoji("🔧", "wrench", "wrench"),
            Emoji("🔨", "hammer", "hammer"),
            Emoji("🧭", "compass", "compass"),
            Emoji("⏰", "alarm clock", "alarm_clock"),
        ),
        EmojiCategory.SYMBOLS to listOf(
            Emoji("❤️", "red heart", "heart"),
            Emoji("💙", "blue heart", "blue_heart"),
            Emoji("💚", "green heart", "green_heart"),
            Emoji("💛", "yellow heart", "yellow_heart"),
            Emoji("💜", "purple heart", "purple_heart"),
            Emoji("🧡", "orange heart", "orange_heart"),
            Emoji("🖤", "black heart", "black_heart"),
            Emoji("💯", "hundred points", "100"),
            Emoji("✅", "check mark button", "white_check_mark"),
            Emoji("❌", "cross mark", "x"),
            Emoji("❗", "exclamation mark", "exclamation"),
            Emoji("❓", "question mark", "question"),
            Emoji("⚠️", "warning", "warning"),
            Emoji("🔴", "red circle", "red_circle"),
            Emoji("🟢", "green circle", "green_circle"),
            Emoji("🔵", "blue circle", "blue_circle"),
            Emoji("♻️", "recycling symbol", "recycle"),
            Emoji("➡️", "right arrow", "arrow_right"),
            Emoji("🔄", "counterclockwise arrows button", "arrows_counterclockwise"),
            Emoji("#️⃣", "keycap: #", "hash"),
        ),
        EmojiCategory.FLAGS to listOf(
            Emoji("🏳️", "white flag", "white_flag"),
            Emoji("🏴", "black flag", "black_flag"),
            Emoji("🏁", "chequered flag", "checkered_flag"),
            Emoji("🚩", "triangular flag", "triangular_flag_on_post"),
            Emoji("🏳️‍🌈", "rainbow flag", "rainbow_flag"),
        ),
    )

    private val byUnicode: Map<String, Emoji> = categories.flatMap { it.second }.associateBy { it.unicode }

    /**
     * Builds the picker's sections for [query] (blank = every category unfiltered, plus a
     * "Frequently Used" section from [recentUnicode] when non-empty) — ported from
     * `EmojiProvider.categories(searchQuery:)`, minus its `tags` matching (see class doc) and its
     * `AppStorage`-backed frequent list (callers pass [recentUnicode] instead, sourced from
     * [com.meshcoretwo.android.chat.RecentEmojiStore] — this port's merge of Swift's two separate
     * "recent" lists, see that store's doc).
     */
    fun sections(query: String, recentUnicode: List<String>): List<EmojiSection> {
        val trimmed = query.trim()
        val result = mutableListOf<EmojiSection>()

        if (trimmed.isEmpty() && recentUnicode.isNotEmpty()) {
            val recentEmoji = recentUnicode.mapNotNull { byUnicode[it] }
            if (recentEmoji.isNotEmpty()) result.add(EmojiSection(R.string.emoji_frequently_used, recentEmoji))
        }

        for ((category, emoji) in categories) {
            val filtered = if (trimmed.isEmpty()) emoji else emoji.filter { it.matches(trimmed) }
            if (filtered.isNotEmpty()) result.add(EmojiSection(category.displayNameRes, filtered))
        }
        return result
    }

    private fun Emoji.matches(query: String): Boolean =
        label.contains(query, ignoreCase = true) || shortcode.contains(query, ignoreCase = true)
}
