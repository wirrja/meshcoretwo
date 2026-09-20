// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.advertisement

/**
 * Result of one advert-driven contact delta exchange, reported by the handler installed via
 * [AdvertisementService.setDeltaSyncHandler]. Ported from `AdvertContactSyncOutcome.swift`.
 *
 * [BUSY] is not [FAILED]: a collision with another sync never reaches the radio, so it must not
 * spend the failure budget that drops drained keys. [NOT_READY] is permanent until a full contact
 * fetch succeeds elsewhere: neither requeued nor counted against the failure budget.
 */
enum class AdvertContactSyncOutcome {
    SYNCED,
    BUSY,
    FAILED,
    NOT_READY,
}
