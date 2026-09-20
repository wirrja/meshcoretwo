// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.sync

import com.meshcoretwo.services.advertisement.AdvertisementService
import com.meshcoretwo.services.channels.ChannelService
import com.meshcoretwo.services.contacts.ContactService
import com.meshcoretwo.services.messages.IncomingMessageService
import com.meshcoretwo.services.notifications.NotificationService
import com.meshcoretwo.services.persistence.ChannelStore
import com.meshcoretwo.services.persistence.ContactStore
import com.meshcoretwo.services.persistence.DeviceStore
import com.meshcoretwo.services.rxlog.RxLogService
import java.util.UUID

/**
 * The narrow dependency surface [SyncCoordinator] needs to run a sync cycle. Ported from
 * `SyncDependencies.swift`, with two structural differences (both explained in
 * [SyncCoordinator]'s class doc):
 *
 * - No `dataStore: any PersistenceStoreProtocol` — this port has no monolithic persistence
 *   façade; [deviceStore]/[contactStore]/[channelStore] are passed directly instead.
 * - No `reactionService`/`advertisementService`-as-handler-source/`roomServerService`/
 *   `roomAdminService`/`repeaterAdminService` fields for *wiring* — Swift's `wireMessageHandlers`
 *   rewires [IncomingMessageService]'s Swift counterpart's closures on every connection; this
 *   port's [IncomingMessageService] takes its handlers ([com.meshcoretwo.services.messages.ReactionHandling],
 *   [com.meshcoretwo.services.messages.RxLogCorrelating], [com.meshcoretwo.services.messages.IncomingMessageNotifying],
 *   ...) once, at construction, so there is nothing left to rewire per connection. CLI
 *   (`RoomAdminService`/`RepeaterAdminService`) and signed-room (`RoomServerService`) message
 *   routing stay deferred regardless (see [IncomingMessageService]'s class doc) — not a
 *   consequence of this dependency-surface difference.
 */
data class SyncDependencies(
    val deviceStore: DeviceStore,
    val contactStore: ContactStore,
    val channelStore: ChannelStore,
    val contactService: ContactService,
    val channelService: ChannelService,
    val incomingMessageService: IncomingMessageService,
    val notificationService: NotificationService,
    val advertisementService: AdvertisementService,
    val rxLogService: RxLogService,
    /** `null` (the default) means always-foreground — matches Swift's `appStateProvider == nil` behavior. */
    val appStateProvider: (suspend () -> Boolean)? = null,
    /** Starts every service's event monitoring for the connected radio. A composition-root concern — see `ServiceContainer.startEventMonitoring` in Swift for what this replaces. */
    val startEventMonitoring: suspend (radioID: UUID, enableAutoFetch: Boolean) -> Unit,
    /** Exports the device private key for direct-message decryption. */
    val exportPrivateKey: suspend () -> ByteArray,
)
