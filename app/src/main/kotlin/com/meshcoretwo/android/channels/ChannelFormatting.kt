// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.channels

import androidx.annotation.StringRes
import com.meshcoretwo.android.R
import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.services.persistence.ChannelDto

/** "Public" (slot 0) / "Hashtag" (`#name`, secret derivable from the name alone) / "Private". */
@StringRes
fun ChannelDto.typeLabelRes(): Int = when {
    isPublicChannel -> R.string.channel_type_public
    isEncryptedChannel -> R.string.common_private
    else -> R.string.channel_type_hashtag
}

/** Uppercase hex of the channel secret, spaced every 4 characters for readability. */
fun ChannelDto.secretHex(): String = secret.hexString.uppercase().chunked(4).joinToString(" ")
