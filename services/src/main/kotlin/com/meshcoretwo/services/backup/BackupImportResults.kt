// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.backup

import java.util.UUID

/** Outcome of [com.meshcoretwo.services.persistence.ContactStore.batchInsertContacts]. Ported from `batchInsertContacts`'s return tuple. */
data class ContactBatchInsertResult(
    val counts: PerTypeCounts,
    /** `BackupDedupKeys.contactKey(radioID, publicKey)` -> the local (post-merge) contact id, for every backup contact. */
    val contactIdsByKey: Map<String, UUID>,
)

/**
 * Outcome of [com.meshcoretwo.services.persistence.ChannelStore.batchInsertChannels]. Ported from
 * `ChannelBatchInsertResult`. [channelIndexRemap] maps `radioID -> [backupIndex: localIndex]` for
 * every channel whose placement differs from its backup slot; [droppedChannelIndices] lists the
 * `(radioID, backupIndex)` slots that had no free local placement. [insertedLocalIndices] lists
 * the local slots a foreign backup channel newly occupied — see the Swift doc this is ported from
 * for why merge-by-secret slots are excluded from it.
 */
data class ChannelBatchInsertResult(
    val counts: PerTypeCounts,
    val channelIndexRemap: Map<UUID, Map<UByte, UByte>>,
    val droppedChannelIndices: Map<UUID, Set<UByte>>,
    val insertedLocalIndices: Map<UUID, Set<UByte>>,
)

/** Outcome of [com.meshcoretwo.services.persistence.RemoteNodeSessionStore.batchInsertRemoteNodeSessions]. */
data class RemoteNodeSessionBatchInsertResult(
    val counts: PerTypeCounts,
    /** `BackupDedupKeys.remoteNodeSessionKey(radioID, publicKey)` -> the local (post-merge) session id. */
    val sessionIdsByKey: Map<String, UUID>,
)

/**
 * Outcome of [com.meshcoretwo.services.persistence.MessageStore.batchInsertMessages]. Ported from
 * `batchInsertMessages`'s return tuple — [messageIdByBackupId] carries only the entries where a
 * backup message was skipped in favor of an existing local row (identity mappings are omitted, as
 * with every other ID-mapping in this epic).
 */
data class MessageBatchInsertResult(
    val counts: PerTypeCounts,
    val messageIdByBackupId: Map<UUID, UUID>,
)

/** Outcome of a parent-scoped batch insert (repeats/reactions/room messages) that also reports which parents received a new child row. */
data class ParentScopedBatchInsertResult<ParentKey>(
    val counts: PerTypeCounts,
    val affectedParentIds: Set<ParentKey>,
)
