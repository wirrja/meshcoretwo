// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.transport

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Bridges Android's [android.bluetooth.BluetoothGattCallback] to [BleStateMachine].
 *
 * Ported from `BLEStateMachine+CBDelegate.swift`'s `BLEDelegateHandler`. Android delivers GATT
 * callbacks on a Binder thread, not a coroutine, so (mirroring Swift's unstructured `Task {}`
 * per callback) each control callback launches a coroutine on the state machine's own scope.
 * Callback ordering across *different* callback types is not guaranteed FIFO on the state
 * machine this way, which is safe because every handler validates the expected phase before
 * proceeding — an out-of-order callback fails the phase guard and is ignored; the timeout
 * mechanism then retries.
 *
 * [onCharacteristicChanged] is the exception: data is sent directly to the phase's channel
 * ([kotlinx.coroutines.channels.Channel.trySend] is thread-safe and non-suspending) rather than
 * via a launched coroutine, preserving the order Android's own callback dispatch already
 * guarantees instead of risking reordering across independently-scheduled coroutines.
 */
internal class BleGattCallback(private val stateMachine: BleStateMachine) : android.bluetooth.BluetoothGattCallback() {
    /**
     * FIFO of sequence numbers for writes issued but not yet acknowledged. Android delivers
     * exactly one `onCharacteristicWrite` per write request, in issue order, so popping the
     * head tags each callback with the sequence of the write that produced it. Tagging at write
     * time (via [BleStateMachine.recordIssuedWriteSequence]) lets a callback that arrives after
     * its write already timed out be recognized as the old write instead of being mistaken for
     * the current one.
     */
    private val issuedWriteSequences = ConcurrentLinkedQueue<Long>()

    fun recordIssuedWriteSequence(sequence: Long) {
        issuedWriteSequences.add(sequence)
    }

    fun clearIssuedWriteSequences() {
        issuedWriteSequences.clear()
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        stateMachine.scope.launch { stateMachine.handleConnectionStateChange(gatt, status, newState) }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        stateMachine.scope.launch { stateMachine.handleServicesDiscovered(gatt, status) }
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        stateMachine.scope.launch { stateMachine.handleMtuChanged(gatt, mtu, status) }
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        stateMachine.scope.launch { stateMachine.handleDescriptorWrite(gatt, descriptor, status) }
    }

    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        // Pop the oldest unacknowledged write's sequence (callbacks arrive in issue order) so
        // the callback is tagged with the write that produced it, not whichever write is
        // current by delivery time.
        val sequence = issuedWriteSequences.poll() ?: return
        stateMachine.scope.launch { stateMachine.handleCharacteristicWrite(gatt, status, sequence) }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        // The deprecated (value-less) overload is still called on API 33+ for apps that don't
        // override the byte[]-carrying one; characteristic.value is populated either way, so a
        // single override targeting minSdk 26 covers every supported API level.
        if (characteristic.uuid != BleServiceUuid.RX_CHARACTERISTIC) return
        val value = characteristic.value ?: return
        if (value.isEmpty()) return
        stateMachine.deliverReceivedData(value)
    }

    override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
        stateMachine.scope.launch { stateMachine.handleReadRemoteRssi(gatt, status) }
    }
}
