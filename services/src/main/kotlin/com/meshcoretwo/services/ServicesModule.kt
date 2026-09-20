// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services

/**
 * Placeholder marking the services module as wired up. Port targets here
 * (mirroring MC1Services): ConnectionManager + BLE transport, Room entities
 * for Device/Contact/Message/Channel/RemoteNodeSession/RoomMessage, the
 * SyncCoordinator, MessageService/ContactService/ChannelService/etc.,
 * KeychainService (-> Android Keystore / EncryptedSharedPreferences), and
 * NotificationService (local notifications only — no push/FCM). See
 * PLAN.md, Phases 2-4.
 */
internal object ServicesModule {
    const val NAME = "meshcore-services"
}
