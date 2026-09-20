// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.theme

import androidx.annotation.DrawableRes
import com.meshcoretwo.android.R
import com.meshcoretwo.protocol.ContactType

/**
 * The three avatar categories that get one fixed color per theme (no per-entity variation):
 * channels in the Chats list, and repeater / room-server nodes in the Nodes list.
 *
 * Ported from `AvatarCategory.swift`. [iconRes] has no counterpart there — `NodeAvatar.swift`/
 * `ChannelAvatar.swift` each pick their SF Symbol locally — folded in here since Compose's
 * [com.meshcoretwo.android.ui.components.InitialsAvatar] is the one shared avatar view for every
 * category, unlike iOS's separate `NodeAvatar`/`ChannelAvatar` views.
 */
enum class AvatarCategory {
    CHANNEL,
    REPEATER,
    ROOM,
    ;

    /**
     * Stable seed fed to [IdentityGamut] so each category resolves to a distinct, on-theme color.
     * Prefixed to keep it from colliding with a real contact or channel name.
     */
    val gamutSeed: String
        get() = when (this) {
            CHANNEL -> "__avatar_category_channel__"
            REPEATER -> "__avatar_category_repeater__"
            ROOM -> "__avatar_category_room__"
        }

    /**
     * Glyph icon for this category's avatar. Ported from `NodeAvatar.swift`'s `iconName`
     * (`antenna.radiowaves.left.and.right` / `door.left.hand.closed`) and `ChannelAvatar.swift`'s
     * conditional (`globe` for the public broadcast channel, `number` for a `#`-named channel,
     * `lock` otherwise) onto bundled Material Symbols. [isPublicChannel]/[channelName] only matter
     * for [CHANNEL] and default harmlessly for the other two categories.
     */
    @DrawableRes
    fun iconRes(isPublicChannel: Boolean = false, channelName: String = ""): Int = when (this) {
        REPEATER -> R.drawable.ic_cell_tower
        ROOM -> R.drawable.ic_meeting_room
        CHANNEL -> when {
            isPublicChannel -> R.drawable.ic_public
            channelName.startsWith("#") -> R.drawable.ic_numbers
            else -> R.drawable.ic_lock
        }
    }

    companion object {
        /**
         * Order the category avatars claim gamut anchors in. On a collision the later category
         * steps to the next free anchor, so earlier categories keep their preferred color. A
         * channel and a room can share a Chats list, so their colors must stay distinct.
         */
        val anchorPriority = listOf(CHANNEL, REPEATER, ROOM)

        /** `null` for [ContactType.CHAT], which has no avatar category — a regular per-name identity color instead. */
        fun fromContactType(type: ContactType): AvatarCategory? = when (type) {
            ContactType.CHAT -> null
            ContactType.REPEATER -> REPEATER
            ContactType.ROOM -> ROOM
        }
    }
}
