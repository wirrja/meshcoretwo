// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.channels

import com.meshcoretwo.android.ui.i18n.UiText
import com.meshcoretwo.android.ui.i18n.toUiText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meshcoretwo.android.R
import com.meshcoretwo.protocol.decodeHex
import com.meshcoretwo.services.channels.ChannelInvite
import com.meshcoretwo.services.channels.ChannelService
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.channelService
import com.meshcoretwo.services.connection.connectedDeviceRecord
import com.meshcoretwo.services.persistence.ChannelDto
import com.meshcoretwo.services.persistence.ChannelFloodScope
import com.meshcoretwo.services.utilities.HashtagUtilities
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class AddChannelOutcome {
    data class Joined(val index: UByte) : AddChannelOutcome()
    data class Failed(val message: UiText) : AddChannelOutcome()
}

/**
 * Backs the add/join-channel screen (`AddChannelScreen.kt`) — a single-screen collapse of iOS's
 * four `ChannelOptionsSheet` launcher destinations (`JoinPublicChannelView`/
 * `JoinHashtagChannelView`/`CreatePrivateChannelView`/`JoinPrivateChannelView`) plus a fifth mode,
 * "join via pasted link", that substitutes for `ScanChannelQRView` (still deferred — needs a
 * camera+QR library, same reasoning as contact QR sharing, see PLAN.md's Phase 5 contacts slice)
 * and, since the "Deep-link for meshcore://" slice, doubles as the landing spot for a
 * `meshcore://channel/add` link the OS hands the app from outside (`MainActivity`'s intent-filter
 * pre-fills this mode via `AddChannelScreen`'s `initialLink`, see its doc) — both paths do the
 * same [ChannelService.parseChannelURI] decode [joinViaLink] already had. Since "Message text
 * linkify", a tap on such a link *inside* a message bubble reaches this same [joinViaLink] path
 * too (`ChatConversationScreen`'s `onOpenChannelLink`), and a tapped `#hashtag` with no local
 * match yet reaches [joinHashtag] via `AddChannelScreen`'s `initialHashtag` — see that class's doc.
 *

 * **Free-slot selection**: iOS has no service-level "find a free index" method either — every
 * join/create view fetches all channels and filters locally (index 0 reserved for the public
 * channel). Ported the same way here: [findFreeChannelIndex] runs against a fresh
 * [ChannelService.getChannels] fetch on every attempt, not a cached list, so two rapid submits
 * (or another surface writing a channel concurrently) can't both claim the same index from a
 * stale view — worst case a submit that loses the race gets a "no free slots" or overwrites a
 * slot written a moment earlier, both of which are also true of the iOS source this mirrors.
 *
 * Join-flow idempotency (hashtag/private-join/link) matches iOS's own naive check: if a channel
 * with the exact same [ChannelDto.name] already exists locally, treat it as already joined and
 * report that slot rather than write a duplicate — **not** run for [createPrivate], which must
 * always claim a fresh slot even if the display name collides with an existing channel (a
 * name-only match there could otherwise silently keep the old secret instead of the newly
 * generated one).
 *
 * [joinViaLink] additionally applies a link's `region_scope` (if present) as the joined channel's
 * local flood-scope preference — see [applyJoinedFloodScope] (`ChannelJoinFloodScopeApplier` on
 * iOS). Best-effort and local-only: it doesn't push a session flood-scope change, which happens
 * when the conversation is opened instead.
 */
class AddChannelViewModel(private val connectionManager: ConnectionManager) : ViewModel() {
    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

    fun joinPublic(onResult: (AddChannelOutcome) -> Unit) = submit(onResult) { channelService, radioID, _ ->
        channelService.setupPublicChannel(radioID)
        AddChannelOutcome.Joined(0u)
    }

    fun joinHashtag(rawName: String, onResult: (AddChannelOutcome) -> Unit) = submit(onResult) { channelService, radioID, channels ->
        val name = "#" + HashtagUtilities.sanitizeHashtagNameInput(rawName)
        if (name == "#") return@submit AddChannelOutcome.Failed(UiText.of(R.string.add_channel_err_hashtag_name))
        writeChannel(channelService, radioID, channels, name, ChannelService.hashSecret(name), checkExisting = true)
    }

    fun createPrivate(name: String, onResult: (AddChannelOutcome) -> Unit) = submit(onResult) { channelService, radioID, channels ->
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@submit AddChannelOutcome.Failed(UiText.of(R.string.add_channel_err_channel_name))
        val secret = ByteArray(16).also { SecureRandom().nextBytes(it) }
        writeChannel(channelService, radioID, channels, trimmed, secret, checkExisting = false)
    }

    fun joinPrivate(name: String, secretHex: String, onResult: (AddChannelOutcome) -> Unit) = submit(onResult) { channelService, radioID, channels ->
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@submit AddChannelOutcome.Failed(UiText.of(R.string.add_channel_err_channel_name))
        val secret = secretHex.trim().decodeHex()?.takeIf { ChannelService.validateSecret(it) }
            ?: return@submit AddChannelOutcome.Failed(UiText.of(R.string.add_channel_err_secret))
        writeChannel(channelService, radioID, channels, trimmed, secret, checkExisting = true)
    }

    fun joinViaLink(link: String, onResult: (AddChannelOutcome) -> Unit) = submit(onResult) { channelService, radioID, channels ->
        val invite: ChannelInvite = ChannelService.parseChannelURI(link.trim())
            ?: return@submit AddChannelOutcome.Failed(UiText.of(R.string.add_channel_err_link))
        writeChannel(channelService, radioID, channels, invite.name, invite.secret, checkExisting = true, regionScope = invite.regionScope)
    }

    private fun submit(
        onResult: (AddChannelOutcome) -> Unit,
        action: suspend (ChannelService, java.util.UUID, List<ChannelDto>) -> AddChannelOutcome,
    ) {
        viewModelScope.launch {
            _isSubmitting.value = true
            val outcome = try {
                val channelService = connectionManager.channelService
                val radioID = connectionManager.lastConnectedRadioID
                if (channelService == null || radioID == null) {
                    AddChannelOutcome.Failed(UiText.of(R.string.add_channel_err_not_connected))
                } else {
                    action(channelService, radioID, channelService.getChannels(radioID))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AddChannelOutcome.Failed(error.toUiText(UiText.of(R.string.add_channel_err_failed)))
            }
            _isSubmitting.value = false
            onResult(outcome)
        }
    }

    private suspend fun writeChannel(
        channelService: ChannelService,
        radioID: java.util.UUID,
        existingChannels: List<ChannelDto>,
        name: String,
        secret: ByteArray,
        checkExisting: Boolean,
        regionScope: String? = null,
    ): AddChannelOutcome {
        if (checkExisting) {
            existingChannels.firstOrNull { it.name == name }?.let { return AddChannelOutcome.Joined(it.index) }
        }
        val maxChannels = connectionManager.connectedDeviceRecord?.maxChannels
            ?: return AddChannelOutcome.Failed(UiText.of(R.string.add_channel_err_capacity))
        val index = findFreeChannelIndex(existingChannels, maxChannels)
            ?: return AddChannelOutcome.Failed(UiText.of(R.string.add_channel_err_no_slots))
        channelService.setChannelWithSecret(radioID, index, name, secret)
        if (regionScope != null) applyJoinedFloodScope(channelService, radioID, index, regionScope)
        return AddChannelOutcome.Joined(index)
    }

    /**
     * Best-effort local flood-scope preference after a channel join from a link — ported from
     * `ChannelJoinFloodScopeApplier.applyIfNeeded`. Does not touch the session-global flood scope;
     * that's applied when the conversation loads (see `ConversationViewModel.syncFloodScope`). On
     * failure to persist or push, the join itself still stands (local preference just stays
     * `.inherit`) — matches Swift's log-and-continue.
     */
    private suspend fun applyJoinedFloodScope(channelService: ChannelService, radioID: java.util.UUID, index: UByte, regionScope: String) {
        try {
            val channel = channelService.getChannel(radioID, index) ?: return
            channelService.setChannelFloodScope(channel.id, ChannelFloodScope.Region(regionScope))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Best-effort — see this function's doc.
        }
    }

    class Factory(private val connectionManager: ConnectionManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AddChannelViewModel(connectionManager) as T
    }
}

/** Slot 0 is reserved for the public channel; the first unused index above it wins. */
fun findFreeChannelIndex(channels: List<ChannelDto>, maxChannels: UByte): UByte? {
    val used = channels.map { it.index }.toSet()
    for (candidate in 1 until maxChannels.toInt()) {
        val index = candidate.toUByte()
        if (index !in used) return index
    }
    return null
}

