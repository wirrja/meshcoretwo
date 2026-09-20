// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.remotenode

import com.meshcoretwo.protocol.ContactMessage
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.ErrorCode
import com.meshcoretwo.protocol.EventFilter
import com.meshcoretwo.protocol.MeshCoreError
import com.meshcoretwo.protocol.MeshEvent
import com.meshcoretwo.protocol.MessageSentInfo
import com.meshcoretwo.protocol.OwnerInfoResponse
import com.meshcoretwo.protocol.PacketBuilder
import com.meshcoretwo.protocol.RemoteNodeSessionOps
import com.meshcoretwo.protocol.StatusResponse
import com.meshcoretwo.protocol.TelemetryResponse
import com.meshcoretwo.protocol.TextType
import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.protocol.prefixBytes
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.ContactStore
import com.meshcoretwo.services.persistence.NotificationLevel
import com.meshcoretwo.services.persistence.RemoteNodeRole
import com.meshcoretwo.services.persistence.RemoteNodeSessionDto
import com.meshcoretwo.services.persistence.RemoteNodeSessionStore
import com.meshcoretwo.services.persistence.RoomPermissionLevel
import com.meshcoretwo.services.persistence.toFloodedMeshContact
import com.meshcoretwo.services.security.KeychainService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.util.UUID
import kotlin.math.ceil

/**
 * Shared service for remote node operations: login, keep-alive, status/telemetry, CLI commands,
 * and BLE reconnection handling, for both room servers and repeater admin connections. Ported
 * from `RemoteNodeService.swift` plus its `+Login`/`+Reconnection`/`+Telemetry`/`+CLI`/
 * `+PathRecovery` extensions.
 *
 * **Deferred, not yet ported:**
 * - `CommandAuditLogger` — pure logging (confirmed: every method just formats and logs a string,
 *   never gates behavior), dropped along with every `await auditLogger.log*` call site, matching
 *   this codebase's no-logging-in-business-services convention (see `ChannelService`'s class doc).
 * - `RepeaterAdminService`/`RoomAdminService`/`RoomServerService` — thin wrappers over this class,
 *   a natural next slice.
 *
 * The Swift constructor also takes `dataStore: any PersistenceStoreProtocol`; narrowed here to the
 * two stores this class's ported methods actually call ([RemoteNodeSessionStore]/[ContactStore]),
 * the same narrowing precedent `MessageService`/`AdvertisementService` established.
 *
 * **`pendingLogins`/`pendingLoginTimeoutTasks`/`keepAliveTasks`/`keepAliveIntervals`/
 * `isReauthenticating` are `Mutex`-guarded**, matching the same actor-isolation-replacement
 * pattern established for `MessageService.pendingAcks`. `pendingLogins` maps a 6-byte public-key
 * prefix (as [ByteArray.hexString], since Kotlin `ByteArray` has reference — not content —
 * equality/hashing, unlike Swift's `Data`) to a [PendingLogin], replacing Swift's
 * `CheckedContinuation` map (upstream `e410eb8d` turned that into a `PendingLogin` struct too, for
 * the same reason): [login] registers a fresh entry before sending (avoiding the same race with a
 * push `loginSuccess` event Swift's comment calls out), and [handleLoginResult] (from the event
 * listener) or the retransmit loop's own timeout each complete it — whichever fires first, since a
 * completed [CompletableDeferred] silently ignores a second completion, mirroring Swift's
 * "guard let continuation = pendingLogins.removeValue(...)" pattern of removing before resuming.
 * [PendingLogin.sessionID] lets [handleLoginResult] fetch the session by its local id instead of
 * re-deriving it from the wire prefix — a prefix can't disambiguate which radio's session a login
 * push belongs to, but the [login] call that registered this entry already knows. `pendingCLIRequests`/
 * `cliSlotBusy`/`cliSlotWaiters`/`cliPrefixCounter` are guarded by the same [mutex] and use the
 * identical register-before-send/complete-once pattern for the CLI command/reply correlation and
 * the per-node CLI slot FIFO queue.
 */
class RemoteNodeService(
    private val session: RemoteNodeSessionOps,
    private val sessionStore: RemoteNodeSessionStore,
    private val contactStore: ContactStore,
    private val keychainService: KeychainService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    /** Pending login completions keyed by 6-byte public key prefix hex. */
    private val pendingLogins = mutableMapOf<String, PendingLogin>()

    /** A parked login, keyed by public-key prefix — see class doc for why [sessionID] rides along. */
    private class PendingLogin(val sessionID: UUID, val deferred: CompletableDeferred<LoginResult>)

    /** Retransmit/timeout loops for pending logins, keyed the same way. Cancelled once the login resolves. */
    private val pendingLoginTimeoutTasks = mutableMapOf<String, Job>()

    private val keepAliveTasks = mutableMapOf<UUID, Job>()

    /** Keep-alive intervals per session (from login response), in milliseconds. */
    private val keepAliveIntervals = mutableMapOf<UUID, Long>()

    /** Reentrancy guard for BLE reconnection handling. */
    private var isReauthenticating = false

    /** The single in-flight CLI request per node, keyed by 6-byte public key prefix hex. */
    private val pendingCLIRequests = mutableMapOf<String, PendingCLIRequest>()

    /** Cycling counter for CLI wire prefixes ("00\|" through "FF\|"). */
    private var cliPrefixCounter: UByte = 0u

    /** Nodes (by public key prefix hex) whose CLI slot is held by an in-flight command. */
    private val cliSlotBusy = mutableSetOf<String>()

    /** FIFO waiters for a node's CLI slot, keyed the same way. */
    private val cliSlotWaiters = mutableMapOf<String, MutableList<CLISlotWaiter>>()

    private var eventMonitorJob: Job? = null

    /** Multicast broadcaster for session connection-state events; every subscriber sees every event. */
    private val eventsFlow = MutableSharedFlow<RemoteNodeEvent>(extraBufferCapacity = 64)

    /** A fresh stream of remote-node session events. */
    fun events(): Flow<RemoteNodeEvent> = eventsFlow

    // MARK: - Event Monitoring

    /** Starts monitoring MeshCore events for login results and CLI responses. */
    fun startEventMonitoring() {
        eventMonitorJob?.cancel()
        eventMonitorJob = scope.launch {
            val filter = EventFilter { event ->
                when (event) {
                    is MeshEvent.LoginSuccess, is MeshEvent.LoginFailed -> true
                    is MeshEvent.ContactMessageReceived -> event.message.textType == CLI_RESPONSE_TEXT_TYPE
                    else -> false
                }
            }
            session.events(filter).collect { event -> handleEvent(event) }
        }
    }

    /** Stops monitoring events. */
    fun stopEventMonitoring() {
        eventMonitorJob?.cancel()
        eventMonitorJob = null
    }

    private suspend fun handleEvent(event: MeshEvent) {
        when (event) {
            is MeshEvent.LoginSuccess -> {
                val info = event.info
                val result = LoginResult(
                    success = true,
                    isAdmin = info.isAdmin,
                    aclPermissions = info.permissions,
                    publicKeyPrefix = info.publicKeyPrefix,
                    serverTime = info.serverTime,
                )
                handleLoginResult(result, info.publicKeyPrefix)
            }
            is MeshEvent.LoginFailed -> {
                val prefix = event.publicKeyPrefix
                if (prefix != null) {
                    handleLoginResult(
                        LoginResult(success = false, isAdmin = false, aclPermissions = null, publicKeyPrefix = prefix),
                        prefix,
                    )
                }
            }
            is MeshEvent.ContactMessageReceived -> {
                if (event.message.textType == CLI_RESPONSE_TEXT_TYPE) handleCLIResponse(event.message)
            }
            else -> {}
        }
    }

    // MARK: - Session Management

    /** Creates a new session for a remote node, reusing an existing one for the same public key to avoid duplicates. */
    suspend fun createSession(radioID: UUID, contact: ContactDto): RemoteNodeSessionDto {
        val role = RemoteNodeRole.fromContactType(contact.type) ?: throw RemoteNodeError.InvalidResponse

        if (contact.publicKey.size != PacketBuilder.PUBLIC_KEY_SIZE) {
            throw RemoteNodeError.LoginFailed(
                "Invalid public key length: expected ${PacketBuilder.PUBLIC_KEY_SIZE} bytes, got ${contact.publicKey.size}",
            )
        }

        val existing = try {
            sessionStore.fetchSession(radioID, contact.publicKey)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            null
        }

        val dto = RemoteNodeSessionDto(
            id = existing?.id ?: UUID.randomUUID(),
            radioID = radioID,
            publicKey = contact.publicKey,
            name = contact.displayName,
            role = role,
            latitude = contact.latitude,
            longitude = contact.longitude,
            isConnected = false,
            permissionLevel = existing?.permissionLevel ?: RoomPermissionLevel.GUEST,
            lastConnectedDate = existing?.lastConnectedDate,
            lastBatteryMillivolts = existing?.lastBatteryMillivolts,
            lastUptimeSeconds = existing?.lastUptimeSeconds,
            lastNoiseFloor = existing?.lastNoiseFloor,
            unreadCount = existing?.unreadCount ?: 0,
            notificationLevel = existing?.notificationLevel ?: NotificationLevel.ALL,
            lastRxAirtimeSeconds = existing?.lastRxAirtimeSeconds,
            neighborCount = existing?.neighborCount ?: 0,
            lastSyncTimestamp = existing?.lastSyncTimestamp ?: 0,
            lastMessageDate = existing?.lastMessageDate,
        )

        sessionStore.saveSession(dto)
        sessionStore.cleanupDuplicateSessions(contact.publicKey, dto.id)

        return sessionStore.fetchSession(dto.id) ?: throw RemoteNodeError.SessionNotFound
    }

    /** Removes a session and its stored password. */
    suspend fun removeSession(id: UUID, publicKey: ByteArray) {
        stopKeepAlive(id)
        keychainService.deletePassword(publicKey)
        sessionStore.deleteSession(id)
    }

    /**
     * Fetches a session by its local id, for a UI layer that only carries a session id across
     * navigation (e.g. `RoomStatusScreen`'s nav route arg, following `NodeAuthScreen`'s
     * `contactId`-arg convention).
     */
    suspend fun fetchSession(sessionID: UUID): RemoteNodeSessionDto? = sessionStore.fetchSession(sessionID)

    /** Whether a password is stored for a contact's public key. */
    suspend fun hasPassword(contact: ContactDto): Boolean = keychainService.hasPassword(contact.publicKey)

    /** Retrieves the stored password for a contact's public key, or `null` if none/on failure. */
    suspend fun retrievePassword(contact: ContactDto): String? = try {
        keychainService.retrievePassword(contact.publicKey)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        null
    }

    /** Stores a password for a remote node. Call after a successful login to save correct passwords only. */
    suspend fun storePassword(password: String, publicKey: ByteArray) = keychainService.storePassword(password, publicKey)

    /** Deletes the stored password for a contact's public key. */
    suspend fun deletePassword(contact: ContactDto) = keychainService.deletePassword(contact.publicKey)

    // MARK: - Disconnect

    /** Marks a session as disconnected without sending logout. */
    suspend fun disconnect(sessionID: UUID) {
        stopKeepAlive(sessionID)
        markDisconnectedAndNotify(sessionID)
    }

    // MARK: - Cleanup

    /** Stops all keep-alive timers and resolves any parked login continuations. Call on app termination. */
    suspend fun stopAllKeepAlives() {
        val tasks = mutex.withLock {
            val snapshot = keepAliveTasks.values.toList()
            keepAliveTasks.clear()
            snapshot
        }
        tasks.forEach { it.cancel() }

        val prefixes = mutex.withLock { pendingLogins.keys.toList() }
        for (prefix in prefixes) cancelPendingLogin(prefix)
    }

    /** The number of login completions currently parked. Exposed for teardown tests. */
    suspend fun pendingLoginCount(): Int = mutex.withLock { pendingLogins.size }

    private suspend fun cancelPendingLogin(prefix: String) {
        val (task, pending) = mutex.withLock {
            pendingLoginTimeoutTasks.remove(prefix) to pendingLogins.remove(prefix)
        }
        task?.cancel()
        pending?.deferred?.completeExceptionally(RemoteNodeError.Cancelled)
    }

    private suspend fun markDisconnectedAndNotify(sessionID: UUID) {
        try {
            sessionStore.markDisconnected(sessionID)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Best-effort persistence; Swift logs and continues too.
        }
        eventsFlow.emit(RemoteNodeEvent.SessionStateChanged(sessionID, false))
    }

    // MARK: - Login

    /**
     * Logs in to a remote node. Works for both room servers and repeaters.
     *
     * @param pathLength Path length hint for timeout calculation.
     * @param onTimeoutKnown Optional callback invoked with the timeout in seconds once firmware responds.
     */
    suspend fun login(
        sessionID: UUID,
        password: String? = null,
        pathLength: UByte = 0u,
        onTimeoutKnown: (suspend (Int) -> Unit)? = null,
    ): LoginResult {
        val remoteSession = sessionStore.fetchSession(sessionID) ?: throw RemoteNodeError.SessionNotFound

        val pwd = password
            ?: keychainService.retrievePassword(remoteSession.publicKey)
            ?: throw RemoteNodeError.PasswordNotFound

        val prefix = remoteSession.publicKey.prefixBytes(6).hexString
        val deferred = CompletableDeferred<LoginResult>()

        mutex.withLock {
            // Cancel any existing pending login for this prefix before registering the new one.
            pendingLoginTimeoutTasks.remove(prefix)?.cancel()
            pendingLogins.remove(prefix)?.deferred?.completeExceptionally(RemoteNodeError.Cancelled)
            pendingLogins[prefix] = PendingLogin(sessionID, deferred)
        }

        val timeoutJob = scope.launch { runLoginRetransmitLoop(remoteSession, pwd, prefix, pathLength, onTimeoutKnown) }
        mutex.withLock { pendingLoginTimeoutTasks[prefix] = timeoutJob }

        return try {
            deferred.await()
        } catch (error: CancellationException) {
            mutex.withLock {
                pendingLoginTimeoutTasks.remove(prefix)?.cancel()
                pendingLogins.remove(prefix)
            }
            deferred.cancel()
            throw error
        }
    }

    /**
     * Sends the login, retransmitting at least once per firmware-suggested RTT until firmware
     * responds or [RemoteOperationTimeoutPolicy.loginTimeoutMs] elapses, then resolves [prefix]'s
     * pending completion with a timeout if nothing else already did.
     */
    private suspend fun runLoginRetransmitLoop(
        remoteSession: RemoteNodeSessionDto,
        password: String,
        prefix: String,
        pathLength: UByte,
        onTimeoutKnown: (suspend (Int) -> Unit)?,
    ) {
        val sentInfo: MessageSentInfo
        try {
            sentInfo = sendLoginHealingIfNeeded(remoteSession.publicKey, remoteSession.radioID, password)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // Cancellation means this coroutine no longer owns the completion: whoever cancelled it
            // (an overlapping login for this prefix, or login()'s own cancellation handler) already
            // completed and removed it.
            val owned = mutex.withLock {
                pendingLoginTimeoutTasks.remove(prefix)
                pendingLogins.remove(prefix)
            }
            val rnError = error as? RemoteNodeError
                ?: RemoteNodeError.SessionError(error as? MeshCoreError ?: MeshCoreError.ConnectionLost(error))
            owned?.deferred?.completeExceptionally(rnError)
            return
        }

        val timeoutMs = RemoteOperationTimeoutPolicy.loginTimeoutMs(sentInfo, pathLength)
        if (onTimeoutKnown != null) {
            val timeoutSeconds = maxOf(1, ceil(timeoutMs / 1000.0).toInt())
            onTimeoutKnown(timeoutSeconds)
        }

        // Retransmit while waiting for loginSuccess, spaced at least one firmware-suggested RTT so
        // multi-hop paths are not flooded mid-flight.
        val deadline = System.currentTimeMillis() + timeoutMs
        val retransmitIntervalMs = maxOf(RemoteOperationTimeoutPolicy.LOGIN_RETRANSMIT_INTERVAL_MS, sentInfo.suggestedTimeoutMs.toLong())

        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            delay(minOf(remaining, retransmitIntervalMs))

            val stillPending = mutex.withLock { pendingLogins.containsKey(prefix) }
            if (!stillPending) return // Completion consumed elsewhere; stop retransmitting.
            if (System.currentTimeMillis() >= deadline) break

            try {
                session.sendLogin(remoteSession.publicKey, password)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Transient retransmit failure; the loop retries at the next interval.
            }
        }

        val timedOut = mutex.withLock {
            pendingLoginTimeoutTasks.remove(prefix)
            pendingLogins.remove(prefix)
        }
        timedOut?.deferred?.completeExceptionally(RemoteNodeError.Timeout)
    }

    /**
     * Sends the login; if the radio reports the contact is missing from its table, pushes the local
     * copy (flood-routed) and retries once. A contact can be in the app database but absent on the
     * radio after a backup restore or a radio swap, which the firmware answers with notFound (0x02).
     *
     * `internal` (not `private`) so tests can exercise the healing path directly, matching Swift's
     * own `internal` visibility for the same reason.
     */
    internal suspend fun sendLoginHealingIfNeeded(publicKey: ByteArray, radioID: UUID, password: String): MessageSentInfo =
        try {
            session.sendLogin(publicKey, password)
        } catch (error: MeshCoreError) {
            val deviceError = error as? MeshCoreError.DeviceError
            if (deviceError == null || deviceError.code != ErrorCode.NOT_FOUND.value) throw error
            addLocalContactToRadio(publicKey, radioID)
            // Bounded to a single retry: a second notFound after a successful add is a firmware
            // inconsistency, left to propagate rather than loop.
            session.sendLogin(publicKey, password)
        }

    /**
     * Pushes the local contact to the radio's contact table with flood routing, then reconciles the
     * local row so keep-alive routing agrees with the radio.
     */
    private suspend fun addLocalContactToRadio(publicKey: ByteArray, radioID: UUID) {
        val contact = contactStore.fetchContact(radioID, publicKey) ?: throw RemoteNodeError.ContactNotFound
        val frame = contact.toFloodedMeshContact(Instant.now())
        try {
            session.addContact(frame)
        } catch (error: MeshCoreError) {
            val deviceError = error as? MeshCoreError.DeviceError
            if (deviceError == null || deviceError.code != ErrorCode.TABLE_FULL.value) throw error
            throw RemoteNodeError.RadioContactsFull
        }
        // The radio is healed; failing to sync the local row is a bookkeeping issue that must not
        // abort the login retry. Keep-alive routing self-corrects on the next contact refresh.
        try {
            contactStore.saveContact(radioID, frame)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Best-effort local sync only; see doc above.
        }
    }

    /** Handles a login result push from the device. */
    private suspend fun handleLoginResult(result: LoginResult, fromPublicKeyPrefix: ByteArray) {
        if (fromPublicKeyPrefix.size < 6) return

        val prefixBytes = fromPublicKeyPrefix.prefixBytes(6)
        val prefix = prefixBytes.hexString
        val pending = mutex.withLock {
            pendingLoginTimeoutTasks.remove(prefix)?.cancel()
            pendingLogins.remove(prefix)
        } ?: return
        val deferred = pending.deferred

        if (result.success) {
            try {
                // By session id, not prefix: a prefix can't disambiguate which radio's session
                // this push belongs to, but the login() call that parked this entry already knows.
                val remoteSession = sessionStore.fetchSession(pending.sessionID)
                if (remoteSession == null) {
                    deferred.complete(result)
                    return
                }
                sessionStore.updateConnection(remoteSession.id, isConnected = true, permissionLevel = result.permissionLevel)
                eventsFlow.emit(RemoteNodeEvent.SessionStateChanged(remoteSession.id, true))
                mutex.withLock { keepAliveIntervals[remoteSession.id] = DEFAULT_KEEP_ALIVE_INTERVAL_MS }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Best-effort bookkeeping; the login itself already succeeded on the wire.
            }
            deferred.complete(result)
        } else {
            deferred.completeExceptionally(RemoteNodeError.LoginFailed("authentication failed"))
        }
    }

    // MARK: - Keep-Alive (Room Servers)

    /**
     * Starts periodic keep-alive for a room server session. Sends an immediate keep-alive on start
     * (connectivity check + sync_since update), then continues at the configured interval. Transient
     * failures are retried up to [KeepAliveRetryPolicy.MAX_CONSECUTIVE_FAILURES] times before disconnecting.
     */
    private suspend fun startKeepAlive(sessionID: UUID, publicKey: ByteArray) {
        stopKeepAlive(sessionID)

        val intervalMs = mutex.withLock { keepAliveIntervals[sessionID] } ?: DEFAULT_KEEP_ALIVE_INTERVAL_MS

        val task = scope.launch {
            var consecutiveFailures = 0
            while (isActive) {
                val tick = performKeepAliveTick(sessionID, publicKey, consecutiveFailures)
                if (!tick.shouldContinue) return@launch
                consecutiveFailures = tick.consecutiveFailures
                delay(intervalMs)
            }
        }

        mutex.withLock { keepAliveTasks[sessionID] = task }
    }

    private class KeepAliveTickResult(val shouldContinue: Boolean, val consecutiveFailures: Int)

    /**
     * Runs one keep-alive attempt for [startKeepAlive]'s loop. A [CancellationException] flows
     * through [KeepAliveRetryPolicy.evaluate] like any other error (matching Swift's own
     * `KeepAliveRetryPolicy.evaluate`, which special-cases `CancellationError` into a quiet `.stop`
     * rather than letting it propagate) — safe here because the `STOP` branch performs no further
     * suspension, so the cancelled coroutine unwinds on its very next suspension point regardless.
     */
    private suspend fun performKeepAliveTick(sessionID: UUID, publicKey: ByteArray, consecutiveFailures: Int): KeepAliveTickResult {
        var failures = consecutiveFailures
        return try {
            sendKeepAliveIfDirectRouted(sessionID, publicKey)
            KeepAliveTickResult(shouldContinue = true, consecutiveFailures = 0)
        } catch (error: Throwable) {
            val (action, updatedFailures) = KeepAliveRetryPolicy.evaluate(error, failures)
            failures = updatedFailures
            if (action == KeepAliveRetryPolicy.Action.DISCONNECT || action == KeepAliveRetryPolicy.Action.DISCONNECT_NOW) {
                markDisconnectedAndNotify(sessionID)
            }
            KeepAliveTickResult(shouldContinue = !action.shouldExitLoop, consecutiveFailures = failures)
        }
    }

    /** Stops keep-alive for a session. */
    private suspend fun stopKeepAlive(sessionID: UUID) {
        val task = mutex.withLock { keepAliveTasks.remove(sessionID) }
        task?.cancel()
    }

    /** Sends keep-alive only if the session has a direct routing path. */
    private suspend fun sendKeepAliveIfDirectRouted(sessionID: UUID, publicKey: ByteArray) {
        val remoteSession = sessionStore.fetchSession(sessionID) ?: throw RemoteNodeError.SessionNotFound
        val contact = contactStore.fetchContact(remoteSession.radioID, publicKey) ?: throw RemoteNodeError.ContactNotFound

        if (contact.isFloodRouted) throw RemoteNodeError.FloodRouted

        try {
            session.sendKeepAlive(publicKey, remoteSession.lastSyncTimestamp.toUInt())
        } catch (error: MeshCoreError) {
            throw RemoteNodeError.SessionError(error)
        }
    }

    /** Sends keep-alive on demand (for manual refresh). */
    suspend fun sendKeepAlive(sessionID: UUID) {
        val remoteSession = sessionStore.fetchSession(sessionID) ?: throw RemoteNodeError.SessionNotFound
        sendKeepAliveIfDirectRouted(sessionID, remoteSession.publicKey)
    }

    /** Starts keep-alive for a room session (call when the room view appears). */
    suspend fun startSessionKeepAlive(sessionID: UUID, publicKey: ByteArray) = startKeepAlive(sessionID, publicKey)

    /** Stops keep-alive for a room session (call when the room view disappears). */
    suspend fun stopSessionKeepAlive(sessionID: UUID) = stopKeepAlive(sessionID)

    // MARK: - History Sync

    /** Requests message history from a room server. */
    suspend fun requestHistorySync(sessionID: UUID) {
        val remoteSession = sessionStore.fetchSession(sessionID) ?: throw RemoteNodeError.SessionNotFound
        if (!remoteSession.isRoom) throw RemoteNodeError.InvalidResponse

        val contact = contactStore.fetchContact(remoteSession.radioID, remoteSession.publicKey) ?: throw RemoteNodeError.ContactNotFound
        if (contact.isFloodRouted) throw RemoteNodeError.FloodRouted

        try {
            val contactType = if (remoteSession.isRoom) ContactType.ROOM else ContactType.REPEATER
            session.requestStatus(remoteSession.publicKey, contactType)
        } catch (error: MeshCoreError) {
            throw RemoteNodeError.SessionError(error)
        }
    }

    // MARK: - Logout

    /** Explicitly logs out from a remote node. */
    suspend fun logout(sessionID: UUID) {
        val remoteSession = sessionStore.fetchSession(sessionID) ?: throw RemoteNodeError.SessionNotFound

        stopKeepAlive(sessionID)

        try {
            session.sendLogout(remoteSession.publicKey)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Ignore errors — we're disconnecting anyway.
        }

        sessionStore.updateConnection(sessionID, isConnected = false, permissionLevel = RoomPermissionLevel.GUEST)
        eventsFlow.emit(RemoteNodeEvent.SessionStateChanged(sessionID, false))
    }

    // MARK: - BLE Disconnection / Reconnection

    /**
     * Marks every currently-connected session disconnected and stops their keep-alive timers.
     * Returns the set of session ids that were connected, for re-auth on reconnect.
     */
    suspend fun handleBLEDisconnection(): Set<UUID> {
        val connectedSessions = try {
            sessionStore.fetchConnectedSessions()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return emptySet()
        }
        if (connectedSessions.isEmpty()) return emptySet()

        val sessionIDs = mutableSetOf<UUID>()
        for (remoteSession in connectedSessions) {
            sessionIDs.add(remoteSession.id)
            stopKeepAlive(remoteSession.id)
            markDisconnectedAndNotify(remoteSession.id)
        }
        return sessionIDs
    }

    /**
     * Re-authenticates sessions that were connected before a BLE loss.
     *
     * @param sessionIDs Session ids from [handleBLEDisconnection]. If empty (e.g. after an app
     *   restart), no sessions are re-authenticated; the user can manually reconnect.
     */
    suspend fun handleBLEReconnection(sessionIDs: Set<UUID>) {
        if (mutex.withLock { isReauthenticating }) return
        if (sessionIDs.isEmpty()) return

        val sessionsToReauth = sessionIDs.mapNotNull { id ->
            try {
                sessionStore.fetchSession(id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                null
            }
        }
        if (sessionsToReauth.isEmpty()) return

        mutex.withLock { isReauthenticating = true }
        try {
            coroutineScope {
                for (remoteSession in sessionsToReauth) {
                    launch {
                        val previousPermission = remoteSession.permissionLevel
                        try {
                            val result = login(remoteSession.id)
                            if (result.permissionLevel < previousPermission) {
                                markDisconnectedAndNotify(remoteSession.id)
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            markDisconnectedAndNotify(remoteSession.id)
                        }
                    }
                }
            }
        } finally {
            mutex.withLock { isReauthenticating = false }
        }
    }

    // MARK: - Status / Telemetry / Owner Info

    /** Requests status from a remote node. */
    suspend fun requestStatus(sessionID: UUID, timeoutMs: Long? = null): StatusResponse {
        val remoteSession = sessionStore.fetchSession(sessionID) ?: throw RemoteNodeError.SessionNotFound
        return runWithBinaryTimeout(timeoutMs) {
            val contactType = if (remoteSession.isRoom) ContactType.ROOM else ContactType.REPEATER
            session.requestStatus(remoteSession.publicKey, contactType)
        }
    }

    /** Requests telemetry from a remote node. */
    suspend fun requestTelemetry(sessionID: UUID, timeoutMs: Long? = null): TelemetryResponse {
        val remoteSession = sessionStore.fetchSession(sessionID) ?: throw RemoteNodeError.SessionNotFound
        return runWithBinaryTimeout(timeoutMs) { session.requestTelemetry(remoteSession.publicKey) }
    }

    /** Requests owner info from a repeater using the binary protocol. */
    suspend fun requestOwnerInfo(sessionID: UUID, timeoutMs: Long? = null): OwnerInfoResponse {
        val remoteSession = sessionStore.fetchSession(sessionID) ?: throw RemoteNodeError.SessionNotFound
        return runWithBinaryTimeout(timeoutMs) { session.requestOwnerInfo(remoteSession.publicKey) }
    }

    /**
     * Races [operation] against [timeoutMs] (default [RemoteOperationTimeoutPolicy.BINARY_MAXIMUM_MS]),
     * using `kotlinx.coroutines.withTimeout` — a direct replacement for Swift's hand-written
     * `withTimeout`/`raceAgainstDeadline` (`TimeoutUtility.swift`), which exists there only to give
     * Swift's structured concurrency a "race a task against a deadline, cancel the loser" primitive
     * that `kotlinx.coroutines.withTimeout` already provides natively.
     */
    private suspend fun <T> runWithBinaryTimeout(timeoutMs: Long?, operation: suspend () -> T): T = try {
        withTimeout(timeoutMs ?: RemoteOperationTimeoutPolicy.BINARY_MAXIMUM_MS) { operation() }
    } catch (error: TimeoutCancellationException) {
        throw RemoteNodeError.Timeout
    } catch (error: MeshCoreError) {
        if (error is MeshCoreError.Timeout) throw RemoteNodeError.Timeout
        throw RemoteNodeError.SessionError(error)
    }

    // MARK: - CLI Commands

    /**
     * Sends a CLI command to a remote node and waits for its response (admin only). Replies to
     * structured `get` queries must parse to their expected shape; anything else waiting in the
     * mesh is dropped instead of misattributed.
     */
    suspend fun sendCLICommand(sessionID: UUID, command: String, timeoutMs: Long = 10_000L): String =
        performCLICommand(sessionID, command, timeoutMs, acceptsAnyResponse = false)

    /**
     * Sends a raw CLI command to a remote node (admin only). The next reply from the node is
     * delivered verbatim without shape validation. Used by CLI terminals and commands whose reply
     * format is free-form.
     */
    suspend fun sendRawCLICommand(sessionID: UUID, command: String, timeoutMs: Long = 10_000L): String {
        val response = performCLICommand(sessionID, command, timeoutMs, acceptsAnyResponse = true)
        handlePasswordChangeIfNeeded(command, sessionID)
        return response
    }

    /** Shared send path: acquires the node's CLI slot so exactly one command is in flight per node, then performs the exchange. */
    private suspend fun performCLICommand(sessionID: UUID, command: String, timeoutMs: Long, acceptsAnyResponse: Boolean): String {
        val remoteSession = sessionStore.fetchSession(sessionID) ?: throw RemoteNodeError.SessionNotFound
        if (!remoteSession.isAdmin) throw RemoteNodeError.PermissionDenied

        val rewritten = RemoteCLICommandRewriter.rewrite(command)
        val destinationPrefix = remoteSession.publicKey.prefixBytes(6).hexString
        val requestID = UUID.randomUUID()

        acquireCLISlot(destinationPrefix, requestID)
        try {
            // Reboot has no reply; path-reset+resend would fire a second reboot.
            if (isFireAndForgetCLI(rewritten)) {
                return performCLIExchange(remoteSession.publicKey, destinationPrefix, rewritten, acceptsAnyResponse, timeoutMs, requestID)
            }
            return performWithDirectPathFloodRecovery(remoteSession.radioID, remoteSession.publicKey) {
                performCLIExchange(remoteSession.publicKey, destinationPrefix, rewritten, acceptsAnyResponse, timeoutMs, requestID)
            }
        } finally {
            releaseCLISlot(destinationPrefix)
        }
    }

    /** Commands that intentionally get no reply (or treat timeout as success). */
    private fun isFireAndForgetCLI(command: String): Boolean {
        val lower = command.lowercase().trim()
        return lower == "reboot" || lower.startsWith("reboot ")
    }

    /** Registers the pending request, sends the command, and polls the device for the reply until it arrives or the effective timeout elapses. */
    private suspend fun performCLIExchange(
        publicKey: ByteArray,
        destinationPrefix: String,
        command: String,
        acceptsAnyResponse: Boolean,
        timeoutMs: Long,
        requestID: UUID,
    ): String {
        val wirePrefix = makeCLIWirePrefix()
        val deferred = CompletableDeferred<String>()
        mutex.withLock {
            pendingCLIRequests[destinationPrefix] = PendingCLIRequest(requestID, command, wirePrefix, acceptsAnyResponse, deferred)
        }

        val job = scope.launch {
            val sentInfo: MessageSentInfo
            try {
                sentInfo = session.sendCommand(publicKey, wirePrefix + command)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val failed = takePendingCLIRequest(destinationPrefix, requestID)
                val meshError = error as? MeshCoreError ?: MeshCoreError.ConnectionLost(error)
                failed?.deferred?.completeExceptionally(RemoteNodeError.SessionError(meshError))
                return@launch
            }

            val effectiveTimeoutMs = RemoteOperationTimeoutPolicy.cliTimeoutMs(sentInfo, timeoutMs)
            val deadline = System.currentTimeMillis() + effectiveTimeoutMs
            while (System.currentTimeMillis() < deadline) {
                val stillOwned = mutex.withLock { pendingCLIRequests[destinationPrefix]?.id == requestID }
                if (!stillOwned) return@launch // Request was resumed by handleCLIResponse or cancelled.

                val remaining = deadline - System.currentTimeMillis()
                val pollMs = minOf(RemoteOperationTimeoutPolicy.POLL_INTERVAL_MS, remaining)
                try {
                    session.getMessage(maxOf(0.1, pollMs / 1000.0))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // Poll failure; the loop retries until the deadline.
                }
            }

            val timedOut = takePendingCLIRequest(destinationPrefix, requestID)
            timedOut?.deferred?.completeExceptionally(RemoteNodeError.Timeout)
        }

        return try {
            deferred.await()
        } catch (error: CancellationException) {
            cancelPendingCLIRequest(destinationPrefix, requestID)
            job.cancel()
            throw error
        }
    }

    /** Removes and returns the pending request if it is still the given one. */
    private suspend fun takePendingCLIRequest(prefix: String, requestID: UUID): PendingCLIRequest? = mutex.withLock {
        val pending = pendingCLIRequests[prefix]
        if (pending?.id == requestID) pendingCLIRequests.remove(prefix) else null
    }

    private suspend fun cancelPendingCLIRequest(prefix: String, requestID: UUID) {
        takePendingCLIRequest(prefix, requestID)?.deferred?.cancel()
    }

    /**
     * Handles a CLI response from a contact message. The wire prefix echoed by firmware attributes
     * a reply deterministically; an unprefixed reply falls back to single-flight shape validation
     * for older firmware, and a reply echoing a different prefix belongs to an earlier command and
     * is dropped.
     */
    private suspend fun handleCLIResponse(message: ContactMessage) {
        val prefix = message.senderPublicKeyPrefix.prefixBytes(6).hexString
        val resolved = mutex.withLock {
            val pending = pendingCLIRequests[prefix] ?: return@withLock null
            val echoed = CLIResponse.splitEchoedPrefix(message.text)
            val responseText = if (echoed != null) {
                if (echoed.first != pending.wirePrefix) return@withLock null
                echoed.second
            } else {
                // Firmware without prefix echo: fall back to shape validation.
                if (!pending.acceptsAnyResponse && !CLIResponse.isPlausibleResponse(message.text, pending.command)) return@withLock null
                message.text
            }
            pendingCLIRequests.remove(prefix)
            pending to responseText
        } ?: return
        resolved.first.deferred.complete(resolved.second)
    }

    /** Returns the next cycling CLI wire prefix ("00\|" through "FF\|"). */
    private suspend fun makeCLIWirePrefix(): String = mutex.withLock {
        val prefix = "%02X".format(cliPrefixCounter.toInt()) + CLIResponse.ECHO_PREFIX_SEPARATOR
        cliPrefixCounter = (cliPrefixCounter + 1u).toUByte()
        prefix
    }

    /** Clears the stored password if [command] is an admin password change. */
    private suspend fun handlePasswordChangeIfNeeded(command: String, sessionID: UUID) {
        val lower = command.lowercase().trim()
        // Only admin password changes, not guest.
        if (!lower.startsWith("password ") || lower.contains("guest.password")) return

        val remoteSession = try {
            sessionStore.fetchSession(sessionID)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            null
        } ?: return

        try {
            keychainService.deletePassword(remoteSession.publicKey)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Next login fails naturally — user re-enters password, overwrites stale credential.
        }
    }

    // MARK: - CLI Slot

    /** Waits for the node's CLI slot (FIFO). Throws [CancellationException] if the calling coroutine is cancelled while waiting. */
    private suspend fun acquireCLISlot(prefix: String, waiterID: UUID) {
        val waiterDeferred = mutex.withLock {
            if (cliSlotBusy.add(prefix)) {
                null
            } else {
                val deferred = CompletableDeferred<Unit>()
                cliSlotWaiters.getOrPut(prefix) { mutableListOf() }.add(CLISlotWaiter(waiterID, deferred))
                deferred
            }
        } ?: return

        try {
            waiterDeferred.await()
        } catch (error: CancellationException) {
            cancelCLISlotWaiter(prefix, waiterID)
            throw error
        }
    }

    /** Hands the slot to the next waiter, or frees it when none are queued. */
    private suspend fun releaseCLISlot(prefix: String) {
        val next = mutex.withLock {
            val waiters = cliSlotWaiters[prefix]
            if (waiters != null && waiters.isNotEmpty()) {
                val head = waiters.removeAt(0)
                if (waiters.isEmpty()) cliSlotWaiters.remove(prefix)
                head
            } else {
                cliSlotBusy.remove(prefix)
                null
            }
        }
        next?.deferred?.complete(Unit)
    }

    /** Cancels a queued slot waiter when its calling coroutine is cancelled. */
    private suspend fun cancelCLISlotWaiter(prefix: String, waiterID: UUID) {
        val cancelled = mutex.withLock {
            val waiters = cliSlotWaiters[prefix] ?: return@withLock null
            val index = waiters.indexOfFirst { it.id == waiterID }
            if (index < 0) return@withLock null
            val removed = waiters.removeAt(index)
            if (waiters.isEmpty()) cliSlotWaiters.remove(prefix)
            removed
        } ?: return
        cancelled.deferred.cancel()
    }

    // MARK: - Direct-path flood recovery

    /**
     * CLI path recovery: runs [operation], and on a direct-path mesh timeout resets the contact
     * path to flood and runs once more. Already-flood contacts skip the reset. A failed reset
     * rethrows the original timeout. Binary admin waits ([requestStatus]/[requestTelemetry]/
     * [requestOwnerInfo]) use [runWithBinaryTimeout] instead — see `RemoteNodePathRecoveryTests`'s
     * doc comment for why those never reset the path.
     */
    private suspend fun <T> performWithDirectPathFloodRecovery(radioID: UUID, publicKey: ByteArray, operation: suspend () -> T): T {
        try {
            return operation()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (!isMeshTimeout(error) || !isDirectRouted(radioID, publicKey)) throw error

            try {
                resetContactPathToFlood(radioID, publicKey)
            } catch (resetError: CancellationException) {
                throw resetError
            } catch (resetError: Exception) {
                throw error // Reset failed; the original timeout is what's thrown.
            }

            return operation()
        }
    }

    /** True when [error] is a mesh wait timeout (session or wrapped). */
    private fun isMeshTimeout(error: Throwable): Boolean = when {
        error is RemoteNodeError.Timeout -> true
        error is RemoteNodeError.SessionError && error.error is MeshCoreError.Timeout -> true
        error is MeshCoreError.Timeout -> true
        else -> false
    }

    /** True when the local contact is not flood-routed. Missing contacts count as direct so [session.resetPath] can still clear a radio-side path. */
    private suspend fun isDirectRouted(radioID: UUID, publicKey: ByteArray): Boolean {
        val contact = try {
            contactStore.fetchContact(radioID, publicKey)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            null
        } ?: return true
        return !contact.isFloodRouted
    }

    /** Clears the companion out-path via [session.resetPath] and mirrors flood on the local contact. */
    private suspend fun resetContactPathToFlood(radioID: UUID, publicKey: ByteArray) {
        session.resetPath(publicKey)

        val contact = contactStore.fetchContact(radioID, publicKey) ?: return
        val frame = contact.toFloodedMeshContact(Instant.now())
        try {
            contactStore.saveContact(radioID, frame)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Radio path is already flood; a local save failure must not abort the retry.
        }
    }

    private class PendingCLIRequest(
        val id: UUID,
        val command: String,
        val wirePrefix: String,
        val acceptsAnyResponse: Boolean,
        val deferred: CompletableDeferred<String>,
    )

    private class CLISlotWaiter(val id: UUID, val deferred: CompletableDeferred<Unit>)

    companion object {
        /** Default keep-alive interval, in milliseconds, when a login response didn't specify one. */
        private const val DEFAULT_KEEP_ALIVE_INTERVAL_MS = 90_000L

        /** Wire value `0x01` — firmware `TXT_TYPE_CLI_DATA`. */
        private val CLI_RESPONSE_TEXT_TYPE = TextType.CLI_DATA.value
    }
}
