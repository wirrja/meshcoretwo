// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

/**
 * A contact fetch paired with the device's reported contact total.
 *
 * The device sends the total in the `contactsStart` header at the start of a `GET_CONTACTS`
 * reply. On a full fetch (`since == null`) the total is the device's complete contact count, so a
 * caller that prunes local rows can compare it to `contacts.size` and skip the prune on a
 * truncated stream.
 *
 * [reportedTotal] is `null` when the reply completed without a `contactsStart` header. The total
 * is then unknown, so a prune caller must not treat the received set as complete.
 */
data class ContactFetchResult(
    /** The contacts received in the reply. */
    val contacts: List<MeshContact>,
    /** The contact total the device reported in the `contactsStart` header, or `null` if the header never arrived. */
    val reportedTotal: Int?,
)
