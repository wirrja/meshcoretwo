// SPDX-License-Identifier: GPL-3.0-only

@file:Suppress("MissingPermission")

package com.meshcoretwo.services.transport

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Internal GATT callback handlers for [BleStateMachine], mirroring
 * `BLEStateMachine+CallbackHandlers.swift`.
 *
 * Android's `BluetoothGattCallback.onConnectionStateChange` combines what CoreBluetooth splits
 * into three delegate methods (`didConnect`, `didFailToConnect`, `didDisconnectPeripheral`) into
 * one callback carrying `(status, newState)`; [handleConnectionStateChange] branches on
 * `newState` and the current phase to recover the same three cases Swift handles separately.
 *
 * A private key extension function to another file cannot see another file's `private` members
 * in Kotlin (unlike Swift's file-scoped `private` still being visible module-wide via
 * `internal`), so the shared mutable state these functions touch is declared `internal` on
 * [BleStateMachine] — the same convention already established for `protocol`'s
 * `MeshCoreSession*.kt` split.
 */
private const val TAG = "BleStateMachine"

internal suspend fun BleStateMachine.handleAdapterStateChange(state: Int) {
    onBluetoothStateChange?.invoke(state)
    when (state) {
        BluetoothAdapter.STATE_ON -> {
            withStateLock {
                val waiting = phase as? BlePhase.WaitingForBluetooth
                if (waiting != null) {
                    transitionLocked(BlePhase.Idle)
                    waiting.continuation.complete(Unit)
                }
            }
            if (pendingScanRequest) startScanning()
            onBluetoothPoweredOn?.invoke()
        }

        BluetoothAdapter.STATE_OFF -> {
            if (isCurrentlyScanning) {
                // Bluetooth going off already tears the scan down at the OS level (and
                // adapter.bluetoothLeScanner turns null), so stopScanning()'s own stopScan()
                // call is a no-op here; this just resets our bookkeeping and remembers to
                // resume once Bluetooth is back on.
                stopScanning()
                pendingScanRequest = true
            }
            withStateLock {
                if (phase !is BlePhase.WaitingForBluetooth) {
                    val deviceAddress = phase.deviceAddress
                    cancelCurrentOperationLocked(BleError.BluetoothPoweredOff)
                    if (deviceAddress != null) onDisconnection?.invoke(deviceAddress, null)
                }
            }
        }

        else -> {}
    }
}

internal suspend fun BleStateMachine.handleConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
    when (newState) {
        BluetoothProfile.STATE_CONNECTED -> handleDeviceConnected(gatt)
        BluetoothProfile.STATE_DISCONNECTED -> handleDeviceDisconnected(gatt, status)
        else -> {}
    }
}

private suspend fun BleStateMachine.handleDeviceConnected(gatt: BluetoothGatt) {
    withStateLock {
        val autoReconnecting = phase as? BlePhase.AutoReconnecting
        if (autoReconnecting != null && autoReconnecting.device.address == gatt.device.address) {
            reconnectPolicy.linkReestablished()
            armReconnectDiscoveryTimeout(gatt.device, connectionGeneration)
            gatt.discoverServices()
            return@withStateLock
        }

        val connecting = phase as? BlePhase.Connecting
        if (connecting == null || connecting.gatt.device.address != gatt.device.address) {
            Log.w(TAG, "Unexpected onConnectionStateChange(connected) for ${gatt.device.address}")
            gatt.disconnect()
            gatt.close()
            return@withStateLock
        }

        connecting.timeoutJob.cancel()
        armServiceDiscoveryTimeout(gatt)
        transitionLocked(BlePhase.DiscoveringServices(gatt, connecting.continuation))
        gatt.discoverServices()
    }
}

private suspend fun BleStateMachine.handleDeviceDisconnected(gatt: BluetoothGatt, status: Int) {
    withStateLock {
        val autoReconnecting = phase as? BlePhase.AutoReconnecting
        if (autoReconnecting != null && autoReconnecting.device.address == gatt.device.address) {
            val decision = reconnectPolicy.resolveConnectFailure(gatt.device.address, status, System.currentTimeMillis(), isAppActive)
            when (decision) {
                is ReconnectPolicy.ConnectFailureDecision.RetryPendingConnect -> {
                    Log.i(TAG, "Reconnect attempt ${decision.failureCount}/${decision.budget} failed for ${gatt.device.address}; retrying")
                    reissueReconnect(gatt.device)
                }
                is ReconnectPolicy.ConnectFailureDecision.ContinueEpisodeAfterBudget -> {
                    Log.i(TAG, "Reconnect budget spent for ${gatt.device.address}, holding: ${decision.reason}")
                    reissueReconnect(gatt.device)
                }
                is ReconnectPolicy.ConnectFailureDecision.TearDown -> {
                    Log.w(TAG, "Reconnect abandoned for ${gatt.device.address}: ${decision.reason}")
                    gatt.close()
                    transitionLocked(BlePhase.Idle)
                    onDisconnection?.invoke(gatt.device.address, decision.error)
                }
            }
            return@withStateLock
        }

        when (val current = phase) {
            is BlePhase.Disconnecting -> {
                // Expected disconnection; disconnect() itself owns the phase transition.
            }

            is BlePhase.Connected -> {
                if (current.gatt.device.address != gatt.device.address) return@withStateLock
                val device = gatt.device
                cancelCurrentOperationLocked(BleError.NotConnected)
                gatt.close()
                beginReconnectEpisode(device, tx = null, rx = null)
            }

            is BlePhase.Connecting, is BlePhase.DiscoveringServices, is BlePhase.NegotiatingMtu,
            is BlePhase.SubscribingToNotifications,
            -> {
                if (current.associatedGatt?.device?.address != gatt.device.address) return@withStateLock
                val error = ReconnectPolicy.makeConnectionError(status, "Disconnected during setup")
                cancelCurrentOperationLocked(error)
                gatt.close()
            }

            is BlePhase.DiscoveryComplete -> {
                if (current.gatt.device.address != gatt.device.address) return@withStateLock
                gatt.close()
                transitionLocked(BlePhase.Idle)
            }

            else -> {}
        }
    }
}

/** Starts (or continues into) a reconnect episode for [device], preserving any resolved characteristics. */
private fun BleStateMachine.beginReconnectEpisode(
    device: BluetoothDevice,
    tx: BluetoothGattCharacteristic?,
    rx: BluetoothGattCharacteristic?,
) {
    advanceConnectionGenerationLocked()
    reconnectPolicy.episodeBegan()
    transitionLocked(BlePhase.AutoReconnecting(device, gatt = null, tx = tx, rx = rx))
    armReconnectDiscoveryTimeout(device, connectionGeneration)
    onAutoReconnecting?.invoke(device.address, "disconnected")
    reissueReconnect(device)
}

/** Issues a fresh `connectGatt` for a reconnect attempt, replacing the phase's stale GATT reference. */
private fun BleStateMachine.reissueReconnect(device: BluetoothDevice) {
    scope.launch {
        val gatt = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        withStateLock {
            val current = phase as? BlePhase.AutoReconnecting ?: run {
                gatt.close()
                return@withStateLock
            }
            if (current.device.address != device.address) {
                gatt.close()
                return@withStateLock
            }
            transitionLocked(BlePhase.AutoReconnecting(device, gatt, current.tx, current.rx))
        }
    }
}

internal suspend fun BleStateMachine.handleServicesDiscovered(gatt: BluetoothGatt, status: Int) {
    withStateLock {
        val autoReconnecting = phase as? BlePhase.AutoReconnecting
        if (autoReconnecting != null && autoReconnecting.device.address == gatt.device.address) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Reconnect service discovery failed for ${gatt.device.address}: status=$status")
                gatt.close()
                transitionLocked(BlePhase.Idle)
                onDisconnection?.invoke(gatt.device.address, ReconnectPolicy.makeConnectionError(status))
                return@withStateLock
            }
            val service = gatt.getService(BleServiceUuid.NORDIC_UART)
            if (service == null) {
                Log.w(TAG, "Reconnect: Nordic UART service not found; holding reconnect")
                return@withStateLock
            }
            gatt.requestMtu(requestedMtu)
            return@withStateLock
        }

        val discovering = phase as? BlePhase.DiscoveringServices
        if (discovering == null || discovering.gatt.device.address != gatt.device.address) {
            Log.w(TAG, "Unexpected onServicesDiscovered for ${gatt.device.address}")
            return@withStateLock
        }

        if (status != BluetoothGatt.GATT_SUCCESS) {
            transitionLocked(BlePhase.Idle)
            discovering.continuation.completeExceptionally(ReconnectPolicy.makeConnectionError(status))
            return@withStateLock
        }

        val service = gatt.getService(BleServiceUuid.NORDIC_UART)
        if (service == null) {
            transitionLocked(BlePhase.Idle)
            discovering.continuation.completeExceptionally(BleError.CharacteristicNotFound)
            return@withStateLock
        }

        transitionLocked(BlePhase.NegotiatingMtu(gatt, discovering.continuation))
        gatt.requestMtu(requestedMtu)
    }
}

/**
 * Enables notifications on [rx] by writing its Client Characteristic Configuration Descriptor.
 * Completion arrives asynchronously via [BleGattCallback.onDescriptorWrite] /
 * [BleStateMachine.handleDescriptorWrite]; a `false` return here means the write could not even
 * be issued (no CCCD present, or the local write call itself failed).
 */
@Suppress("DEPRECATION")
private fun BluetoothGatt.subscribeToNotifications(rx: BluetoothGattCharacteristic): Boolean {
    if (!setCharacteristicNotification(rx, true)) return false
    val descriptor = rx.getDescriptor(BleServiceUuid.CLIENT_CHARACTERISTIC_CONFIG) ?: return false
    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
    return writeDescriptor(descriptor)
}

internal suspend fun BleStateMachine.handleMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
    withStateLock {
        val autoReconnecting = phase as? BlePhase.AutoReconnecting
        if (autoReconnecting != null && autoReconnecting.device.address == gatt.device.address) {
            // MTU failures are non-fatal — the link works at the default 23-byte MTU, just
            // slower. Proceed to characteristic resolution regardless of `status`.
            val service = gatt.getService(BleServiceUuid.NORDIC_UART)
            if (service == null) {
                Log.w(TAG, "Reconnect: Nordic UART service vanished after MTU negotiation; holding reconnect")
                return@withStateLock
            }
            val tx = service.getCharacteristic(BleServiceUuid.TX_CHARACTERISTIC)
            val rx = service.getCharacteristic(BleServiceUuid.RX_CHARACTERISTIC)
            if (tx == null || rx == null) {
                Log.w(TAG, "Reconnect: TX/RX characteristic missing; holding reconnect")
                return@withStateLock
            }
            if (!gatt.subscribeToNotifications(rx)) {
                Log.w(TAG, "Reconnect: failed to issue notification subscription; holding reconnect")
                return@withStateLock
            }
            transitionLocked(BlePhase.AutoReconnecting(autoReconnecting.device, gatt, tx, rx))
            return@withStateLock
        }

        val negotiating = phase as? BlePhase.NegotiatingMtu
        if (negotiating == null || negotiating.gatt.device.address != gatt.device.address) return@withStateLock

        val service = gatt.getService(BleServiceUuid.NORDIC_UART)
        if (service == null) {
            transitionLocked(BlePhase.Idle)
            negotiating.continuation.completeExceptionally(BleError.CharacteristicNotFound)
            return@withStateLock
        }

        val tx = service.getCharacteristic(BleServiceUuid.TX_CHARACTERISTIC)
        val rx = service.getCharacteristic(BleServiceUuid.RX_CHARACTERISTIC)
        if (tx == null || rx == null) {
            transitionLocked(BlePhase.Idle)
            negotiating.continuation.completeExceptionally(BleError.CharacteristicNotFound)
            return@withStateLock
        }

        if (!gatt.subscribeToNotifications(rx)) {
            transitionLocked(BlePhase.Idle)
            negotiating.continuation.completeExceptionally(BleError.WriteError("Failed to enable notifications"))
            return@withStateLock
        }

        transitionLocked(BlePhase.SubscribingToNotifications(gatt, tx, rx, negotiating.continuation))
    }
}

internal suspend fun BleStateMachine.handleDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
    if (descriptor.characteristic?.uuid != BleServiceUuid.RX_CHARACTERISTIC) return

    withStateLock {
        val autoReconnecting = phase as? BlePhase.AutoReconnecting
        if (autoReconnecting != null && autoReconnecting.device.address == gatt.device.address) {
            val tx = autoReconnecting.tx
            val rx = autoReconnecting.rx
            if (status != BluetoothGatt.GATT_SUCCESS || tx == null || rx == null) {
                Log.w(TAG, "Reconnect: notification subscription failed for ${gatt.device.address}: status=$status")
                return@withStateLock
            }
            val channel = Channel<ByteArray>(capacity = 512)
            activeDataChannel = channel
            transitionLocked(BlePhase.Connected(gatt, tx, rx, channel))
            startRssiKeepalive()
            onReconnection?.invoke(gatt.device.address, channel.receiveAsFlow())
            return@withStateLock
        }

        val subscribing = phase as? BlePhase.SubscribingToNotifications
        if (subscribing == null || subscribing.gatt.device.address != gatt.device.address) return@withStateLock

        if (status != BluetoothGatt.GATT_SUCCESS) {
            gatt.disconnect()
            gatt.close()
            transitionLocked(BlePhase.Idle)
            subscribing.continuation.completeExceptionally(BleError.WriteError("Failed to enable notifications: status=$status"))
            return@withStateLock
        }

        transitionLocked(BlePhase.DiscoveryComplete(gatt, subscribing.tx, subscribing.rx))
        subscribing.continuation.complete(Unit)
    }
}

/**
 * On a successful RSSI keepalive read, refreshes the device's bond-verification stamp if a live
 * app session is present — mirroring `handleDidReadRSSI` in `BLEStateMachine+CallbackHandlers.swift`.
 * Only refreshes an *existing* stamp (see [ReconnectPolicy.refreshBondVerification]); never
 * creates one, since only a completed app-layer handshake is evidence a bond verified.
 */
internal suspend fun BleStateMachine.handleReadRemoteRssi(gatt: BluetoothGatt, status: Int) {
    if (status != BluetoothGatt.GATT_SUCCESS) {
        Log.w(TAG, "readRemoteRssi failed: status=$status")
        return
    }
    val deviceAddress = gatt.device.address
    if (refreshBondVerificationIfSessionLive(deviceAddress)) {
        onBondRefreshed?.invoke(deviceAddress)
    }
}
