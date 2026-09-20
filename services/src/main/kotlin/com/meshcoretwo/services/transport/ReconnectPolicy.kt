// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.transport

import android.bluetooth.BluetoothGatt

/**
 * The reconnect policy: classifies BLE link failures as transient (retry) or escalating (tear
 * down toward guided re-pair). Owns the failure tallies, the discovery-extension budget, and
 * bond-verification recency, while [BleStateMachine] keeps the GATT choreography that executes
 * the decisions.
 *
 * Ported from `ReconnectPolicy.swift`. Its bond-verification grace-window subsystem — previously
 * dropped here as "unconsumed, speculative infrastructure" while `ConnectionManager` (Phase 3)
 * was unported — is now restored: `ConnectionManager` is the next slice, and it depends on this
 * exact mechanism (see PLAN.md's "ServiceContainer"/connection-infrastructure slices).
 *
 * The grace window shields a *recently-verified* bond from being torn down on an *ambiguous*
 * exhausted-retry-budget failure signature. iOS's signal for "ambiguous" is a majority of
 * `CBError.encryptionTimedOut` failures in the episode — a specific CoreBluetooth quirk with no
 * Android equivalent. Android's nearest analogue is [GATT_ERROR] (133): an undocumented,
 * catch-all status that is extremely common on real devices (stale GATT cache, a connect racing
 * a previous `close()`, OEM stack flakiness under load) and — like a lone `encryptionTimedOut` —
 * usually transient rather than a sign of a dead bond. [reconnectAmbiguousFailures] tracks it the
 * same way Swift tracks `encryptionTimedOutConnectFailures`, and [resolveConnectFailure] applies
 * the same majority-plus-recently-verified gate before granting the grace hold.
 *
 * Unlike the Swift `struct` (a value type with `mutating func`s, copied by the actor's
 * `var reconnectPolicy` field), this is a plain mutable class: it is owned exclusively by
 * [BleStateMachine], which already serializes all access to it, so no additional isolation is
 * needed — the same reasoning as [com.meshcoretwo.protocol.ContactManager] in `protocol`.
 */
class ReconnectPolicy {
    /**
     * Consecutive connect failures in the current reconnect episode. Reset when a link is
     * re-established and when the episode ends.
     */
    var reconnectConnectFailures = 0
        private set

    /**
     * How many of [reconnectConnectFailures] carried Android's ambiguous [GATT_ERROR] status. A
     * majority is the transient-looking failure signature that a recently-verified bond can
     * shield from escalation — see class doc.
     */
    var reconnectAmbiguousFailures = 0
        private set

    /**
     * Number of times a discovery watchdog has deferred teardown within the current generation
     * because the device was already connected. Reset by [generationAdvanced].
     */
    var discoveryTimeoutExtensions = 0
        private set

    /**
     * When each device's bond last completed a verified encrypted session, keyed by MAC address
     * (this class's owner, [BleStateMachine], only ever knows devices by address — translating a
     * `ConnectionManager`-level device UUID to/from a MAC address is that not-yet-ported layer's
     * job, the same way it already resolves a UUID to an address before calling
     * [BleStateMachine.connect]). Seeded from persistence at wiring time so a verification from a
     * previous launch still shields, refreshed on every verified session, cleared when the
     * device's pairing is forgotten.
     */
    val bondVerificationDates: MutableMap<String, Long> = mutableMapOf()

    /** The link re-established mid-episode, breaking the failure streak. */
    fun linkReestablished() {
        resetFailureTallies()
    }

    /** A new reconnect episode started (unexpected disconnect). */
    fun episodeBegan() {
        resetFailureTallies()
    }

    /** A new connection generation started; the extension budget resets. */
    fun generationAdvanced() {
        discoveryTimeoutExtensions = 0
    }

    private fun resetFailureTallies() {
        reconnectConnectFailures = 0
        reconnectAmbiguousFailures = 0
    }

    /** The device's bond completed a verified encrypted session at [atMillis]. */
    fun recordBondVerification(deviceAddress: String, atMillis: Long) {
        bondVerificationDates[deviceAddress] = atMillis
    }

    /**
     * Refreshes an existing verification's timestamp; never creates one. Only a completed
     * app-layer handshake is evidence that a bond verified, so a forgotten pairing has no entry
     * and a keepalive tick cannot re-shield it, whichever order the clear and the tick reach the
     * mutex-guarded state in.
     *
     * @return `true` when an existing stamp was updated.
     */
    fun refreshBondVerification(deviceAddress: String, atMillis: Long): Boolean {
        if (!bondVerificationDates.containsKey(deviceAddress)) return false
        bondVerificationDates[deviceAddress] = atMillis
        return true
    }

    /** The device's pairing was forgotten; its verification must stop shielding. */
    fun clearBondVerification(deviceAddress: String) {
        bondVerificationDates.remove(deviceAddress)
    }

    /** Whether [deviceAddress] currently has a bond-verification stamp. */
    fun hasBondVerification(deviceAddress: String): Boolean = bondVerificationDates.containsKey(deviceAddress)

    // MARK: - Connect-failure classification

    sealed class ConnectFailureDecision {
        /** Re-issue the pending connect; the episode continues. */
        data class RetryPendingConnect(val failureCount: Int, val budget: Int) : ConnectFailureDecision()

        /**
         * Budget spent: re-issue the pending connect and keep the episode going anyway, because
         * [reason] says tearing down now would be wrong (recently-verified bond, or app
         * backgrounded).
         */
        data class ContinueEpisodeAfterBudget(val reason: BudgetHoldReason) : ConnectFailureDecision()

        /** End the episode, surfacing [error] through the disconnection handler. */
        data class TearDown(val error: BleError, val reason: TeardownReason) : ConnectFailureDecision()
    }

    /** Why the episode continued after the connect-failure budget was spent. */
    sealed class BudgetHoldReason {
        /** [verifiedAgeMillis] is time since the bond's last verified session, null if never. */
        data class AmbiguousFailureGraced(val verifiedAgeMillis: Long?) : BudgetHoldReason()
        object BackgroundHold : BudgetHoldReason()
    }

    /** Why a connect-failure [ConnectFailureDecision.TearDown] was chosen. */
    sealed class TeardownReason {
        object DefinitiveBondFailure : TeardownReason()

        /** [verifiedAgeMillis] is time since the bond's last verified session, null if never. */
        data class BondSuspect(val verifiedAgeMillis: Long?) : TeardownReason()
        object RetryBudgetExhausted : TeardownReason()
    }

    /**
     * A connect attempt failed while reconnecting. A definitive bond failure tears down at once;
     * otherwise re-issue until the budget, then hold (recently verified, or app inactive) or tear
     * down (active and unshielded).
     */
    fun resolveConnectFailure(deviceAddress: String, status: Int, nowMillis: Long, appActive: Boolean): ConnectFailureDecision {
        if (isDefinitiveAuthFailure(status)) {
            resetFailureTallies()
            return ConnectFailureDecision.TearDown(BleError.AuthenticationFailed, TeardownReason.DefinitiveBondFailure)
        }

        reconnectConnectFailures++
        if (status == GATT_ERROR) reconnectAmbiguousFailures++

        if (reconnectConnectFailures < MAX_RECONNECT_CONNECT_FAILURES) {
            return ConnectFailureDecision.RetryPendingConnect(reconnectConnectFailures, MAX_RECONNECT_CONNECT_FAILURES)
        }

        // An ambiguous-status majority is both a dead-bond signature and fringe-range noise.
        // Keep the pending connect when recently verified or inactive; escalate to bond-suspect
        // only when the app is active and grace has elapsed.
        val majorityAmbiguous = reconnectAmbiguousFailures * 2 > reconnectConnectFailures
        resetFailureTallies()

        val lastVerified = bondVerificationDates[deviceAddress]
        val verifiedAge = lastVerified?.let { nowMillis - it }

        if (majorityAmbiguous && isBondRecentlyVerified(lastVerified, nowMillis)) {
            return ConnectFailureDecision.ContinueEpisodeAfterBudget(BudgetHoldReason.AmbiguousFailureGraced(verifiedAge))
        }
        if (!appActive) {
            return ConnectFailureDecision.ContinueEpisodeAfterBudget(BudgetHoldReason.BackgroundHold)
        }
        if (majorityAmbiguous) {
            return ConnectFailureDecision.TearDown(BleError.AuthenticationFailed, TeardownReason.BondSuspect(verifiedAge))
        }
        return ConnectFailureDecision.TearDown(makeConnectionError(status), TeardownReason.RetryBudgetExhausted)
    }

    // MARK: - Discovery-stall classification

    sealed class ServiceDiscoveryStallDecision {
        /** Give discovery another window instead of tearing down a live link. */
        data class ExtendDiscoveryWindow(val extensionCount: Int, val budget: Int) : ServiceDiscoveryStallDecision()

        /** Cancel the connection and fail the in-flight connect with [error]. */
        data class TearDown(val error: BleError) : ServiceDiscoveryStallDecision()
    }

    sealed class AutoReconnectStallDecision {
        /** Keep the pending connect armed and re-arm the watchdog. */
        object WaitForPendingConnect : AutoReconnectStallDecision()

        /** Give discovery another window instead of tearing down a live link. */
        data class ExtendDiscoveryWindow(val extensionCount: Int, val budget: Int) : AutoReconnectStallDecision()

        /** End the episode, surfacing [error] through the disconnection handler. */
        data class TearDown(val error: BleError) : AutoReconnectStallDecision()
    }

    /**
     * The service-discovery watchdog elapsed on an established link. When the device is already
     * connected, the BLE link is up and a discovery callback is in flight or merely slow;
     * tearing it down kills a working connection, so the window extends a bounded number of
     * times. A link that reached "connected" yet never completed discovery across the full
     * budget is the strongest in-app signal of a silently invalidated bond, so it surfaces as
     * [BleError.AuthenticationFailed] and routes into guided re-pair instead of a generic
     * timeout retry loop. A link that never reached "connected" is a plain connection timeout.
     */
    fun resolveServiceDiscoveryStall(deviceConnected: Boolean): ServiceDiscoveryStallDecision {
        if (!deviceConnected) return ServiceDiscoveryStallDecision.TearDown(BleError.ConnectionTimeout)
        val extended = consumeDiscoveryExtension()
            ?: return ServiceDiscoveryStallDecision.TearDown(BleError.AuthenticationFailed)
        return ServiceDiscoveryStallDecision.ExtendDiscoveryWindow(extended, MAX_DISCOVERY_TIMEOUT_EXTENSIONS)
    }

    /**
     * The reconnect discovery watchdog elapsed. Same connected-stall handling as service
     * discovery, except a link that is not connected here is backed by a pending connect that
     * may still complete once the radio is back in range; the watchdog waits without consuming
     * extension budget.
     */
    fun resolveAutoReconnectStall(deviceConnected: Boolean): AutoReconnectStallDecision {
        if (!deviceConnected) return AutoReconnectStallDecision.WaitForPendingConnect
        val extended = consumeDiscoveryExtension()
            ?: return AutoReconnectStallDecision.TearDown(BleError.AuthenticationFailed)
        return AutoReconnectStallDecision.ExtendDiscoveryWindow(extended, MAX_DISCOVERY_TIMEOUT_EXTENSIONS)
    }

    /** Consumes one discovery-window extension, or returns null when the budget is spent. */
    private fun consumeDiscoveryExtension(): Int? {
        if (discoveryTimeoutExtensions >= MAX_DISCOVERY_TIMEOUT_EXTENSIONS) return null
        discoveryTimeoutExtensions++
        return discoveryTimeoutExtensions
    }

    companion object {
        /**
         * Max times a discovery watchdog defers teardown while the device is already connected,
         * before forcing a reconnect. Bounds recovery so a genuinely wedged-but-connected link
         * still tears down eventually.
         */
        const val MAX_DISCOVERY_TIMEOUT_EXTENSIONS = 2

        /**
         * Consecutive connect failures in one reconnect episode before [resolveConnectFailure]
         * classifies retry versus tear-down.
         */
        const val MAX_RECONNECT_CONNECT_FAILURES = 5

        /**
         * Android's generic, undocumented "GATT_ERROR" status. Extremely common on real devices
         * (stale GATT cache, a connect racing a previous `close()`, some OEM stacks under load)
         * and usually transient — unlike iOS, where CoreBluetooth's own connection machinery
         * absorbs most of this class of failure before it ever reaches app code. No public SDK
         * constant names it; the value is a long-standing, widely documented AOSP constant.
         */
        const val GATT_ERROR = 133

        /**
         * After a verified encrypted session, an exhausted ambiguous-failure majority returns
         * [ConnectFailureDecision.ContinueEpisodeAfterBudget] rather than a bond-suspect
         * tear-down. Outside this window a dead bond escalates only while the app is active.
         * 6 hours, matching `ReconnectPolicy.swift`'s `bondVerificationGraceInterval`.
         */
        const val BOND_VERIFICATION_GRACE_MILLIS = 6L * 60 * 60 * 1000

        /**
         * Maps an Android GATT status code to a typed [BleError]. The auth/encryption family is
         * a definitive bond failure mapped to [BleError.AuthenticationFailed] so detection
         * survives OEM-specific status code quirks for the two well-documented codes; everything
         * else (including the ubiquitous [GATT_ERROR]) stays [BleError.ConnectionFailed] so it
         * is retried rather than escalated to guided re-pair.
         */
        fun makeConnectionError(status: Int, fallback: String = "GATT status $status"): BleError =
            if (isDefinitiveAuthFailure(status)) BleError.AuthenticationFailed else BleError.ConnectionFailed(fallback)

        /** Whether a GATT status code is a definitive bond failure that must not be retried. */
        fun isDefinitiveAuthFailure(status: Int): Boolean =
            status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION || status == BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION

        /**
         * Whether a bond verification is recent enough to shield an exhausted ambiguous-failure
         * budget from bond-suspect escalation. A missing timestamp (never verified) gives no
         * shield. A future timestamp (clock set backward) counts as recent — the
         * non-destructive direction.
         */
        fun isBondRecentlyVerified(lastVerifiedMillis: Long?, nowMillis: Long, graceMillis: Long = BOND_VERIFICATION_GRACE_MILLIS): Boolean {
            if (lastVerifiedMillis == null) return false
            return nowMillis - lastVerifiedMillis < graceMillis
        }
    }
}
