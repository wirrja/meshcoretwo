// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import java.time.Instant

/**
 * How an incoming message reached the app, used to decide its persisted sort order. Ported
 * from `DeliveryContext.swift`.
 *
 * This value is ephemeral — it is never stored on [MessageEntity]/[MessageDto]. It only flows
 * through the message-handler pipeline so the sort date can be derived per the delivery path:
 * [Live] keeps just-arrived messages at the bottom of the transcript (sort by receive time);
 * [InitialSync] positions a drained backlog batch as one contiguous block at the drain time
 * carried in [InitialSync.anchor], so reconnect history lands together near the bottom rather
 * than scattered through scrollback by send time.
 */
sealed class DeliveryContext {
    /** Pushed in real time while connected. */
    object Live : DeliveryContext()

    /**
     * Drained from the device's stored backlog during initial connect or resync. [anchor] is
     * captured once per drain so every message in the batch shares a sort date and forms a
     * contiguous block positioned at delivery time, send-ordered within.
     */
    data class InitialSync(val anchor: Instant) : DeliveryContext()
}
