// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The app's Room database. Ported from SwiftData's `ModelContainer` (see
 * `PersistenceStore.swift`). [ContactEntity]/[DeviceEntity]/[ChannelEntity]/[MessageEntity]/
 * [ReactionEntity]/[RxLogEntity]/[MessageRepeatEntity]/[RemoteNodeSessionEntity]/
 * [RoomMessageEntity]/[BlockedChannelSenderEntity]/[PendingSendEntity]/[TracePathEntity]/
 * [TracePathRunEntity]/[DiscoveredNodeEntity]/[DebugLogEntity]/[NodeStatusSnapshotEntity] are
 * registered so far, following the same vertical-slice approach this work started with, rather
 * than standing up the whole schema upfront.
 *
 * Bumped to [version] 24 for [DeviceEntity]'s new `appliedRadioPresetID` column (Phase 35, radio
 * preset name fix); the previous bump, to 23, was for [MessageEntity]/[RxLogEntity]'s new `regionScope`/
 * `regionScopeMatches` columns ([RxLogEntity] also gains `payloadTypeBits`) — the "Incoming
 * Region" chat-footer slice, PLAN.md Фаза 33.5's follow-up (the previous bump, to 22, was for
 * [RemoteNodeSessionEntity]'s new `isFavorite` column). No app build has ever shipped,
 * so there's no installed schema to preserve — rather than hand-write a `Migration` that can't be
 * verified against Room's exact schema hash in this sandbox (a subtly wrong one fails schema
 * validation on next open, which is *worse* than a clean rebuild), [create] uses
 * [androidx.room.RoomDatabase.Builder.fallbackToDestructiveMigration]. Replace this with a real
 * `Migration` once the schema is stable and the app has real installs to preserve.
 */
@Database(
    entities = [ContactEntity::class, DeviceEntity::class, ChannelEntity::class, MessageEntity::class, ReactionEntity::class, RxLogEntity::class, MessageRepeatEntity::class, RemoteNodeSessionEntity::class, RoomMessageEntity::class, BlockedChannelSenderEntity::class, PendingSendEntity::class, TracePathEntity::class, TracePathRunEntity::class, DiscoveredNodeEntity::class, DebugLogEntity::class, NodeStatusSnapshotEntity::class],
    version = 24,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class MeshCoreDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao

    abstract fun deviceDao(): DeviceDao

    abstract fun channelDao(): ChannelDao

    abstract fun messageDao(): MessageDao

    abstract fun reactionDao(): ReactionDao

    abstract fun rxLogDao(): RxLogDao

    abstract fun messageRepeatDao(): MessageRepeatDao

    abstract fun remoteNodeSessionDao(): RemoteNodeSessionDao

    abstract fun roomMessageDao(): RoomMessageDao

    abstract fun blockedChannelSenderDao(): BlockedChannelSenderDao

    abstract fun pendingSendDao(): PendingSendDao

    abstract fun tracePathDao(): TracePathDao

    abstract fun tracePathRunDao(): TracePathRunDao

    abstract fun discoveredNodeDao(): DiscoveredNodeDao

    abstract fun debugLogDao(): DebugLogDao

    abstract fun nodeStatusSnapshotDao(): NodeStatusSnapshotDao

    companion object {
        private const val DATABASE_NAME = "meshcoretwo.db"

        /**
         * Builds the production database. Not a singleton itself — callers (e.g. a DI module in
         * `app`) own the single long-lived instance; this just knows how to construct one.
         */
        fun create(context: Context): MeshCoreDatabase =
            Room.databaseBuilder(context.applicationContext, MeshCoreDatabase::class.java, DATABASE_NAME)
                .fallbackToDestructiveMigration()
                .build()
    }
}
