// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.transport

/**
 * Test double for [BleStateMachineOps], the seam the not-yet-ported `ConnectionManager` will use
 * so its tests don't need real BLE hardware — the same role `MockBLEStateMachine` (a Swift actor)
 * plays for `ConnectionManager`'s tests on iOS.
 *
 * Unlike the Swift mock, this needs no actor-isolation setter dance: tests just assign the public
 * `stubbed*`/`*CallCount`/`*Calls` fields directly (matching this codebase's established `Fake*Ops`
 * convention, e.g. `FakeConfigurationSessionOps` in `SettingsServiceTest.kt`).
 *
 * [bondVerificationDates] and [appSessionLiveAddress] are *not* mere stubs — like the Swift mock's
 * `recordedBondVerifications`/`appSessionLiveDeviceID`, they re-implement the real semantics (map
 * presence + address match) so a test exercising `ConnectionManager`'s persistence-gating logic
 * (`shouldPersistBondRefresh`, etc.) sees faithful behavior, not a canned answer.
 */
class FakeBleStateMachine : BleStateMachineOps {
    // MARK: - Stubbed read-only state

    var stubbedIsConnected: Boolean = false
    var stubbedIsAutoReconnecting: Boolean = false
    var stubbedConnectedDeviceAddress: String? = null
    var stubbedLinkDiagnostics: BleLinkDiagnostics = BleLinkDiagnostics(
        adapterState = "STATE_ON",
        phase = BlePhaseKind.IDLE,
        deviceState = null,
    )
    var stubbedIsBluetoothPoweredOff: Boolean = false

    override val isConnected: Boolean get() = stubbedIsConnected
    override val isAutoReconnecting: Boolean get() = stubbedIsAutoReconnecting
    override val connectedDeviceAddress: String? get() = stubbedConnectedDeviceAddress
    override val linkDiagnostics: BleLinkDiagnostics get() = stubbedLinkDiagnostics
    override val isBluetoothPoweredOff: Boolean get() = stubbedIsBluetoothPoweredOff

    // MARK: - System-connected-peripheral stubs + call recording

    var stubbedIsDeviceConnectedToSystem: Boolean = false

    /** Per-call override, for tests needing a dynamic answer instead of one static stub. */
    var isDeviceConnectedToSystemHandler: ((String) -> Boolean)? = null
    val isDeviceConnectedToSystemCalls: MutableList<String> = mutableListOf()

    var stubbedSystemConnectedDeviceAddresses: List<String> = emptyList()

    var stubbedDidStartAdoptingSystemConnectedPeripheral: Boolean = false
    val startAdoptingSystemConnectedPeripheralCalls: MutableList<String> = mutableListOf()

    override fun isDeviceConnectedToSystem(deviceAddress: String): Boolean {
        isDeviceConnectedToSystemCalls.add(deviceAddress)
        return isDeviceConnectedToSystemHandler?.invoke(deviceAddress) ?: stubbedIsDeviceConnectedToSystem
    }

    override fun systemConnectedDeviceAddresses(): List<String> = stubbedSystemConnectedDeviceAddresses

    override fun startAdoptingSystemConnectedPeripheral(deviceAddress: String): Boolean {
        startAdoptingSystemConnectedPeripheralCalls.add(deviceAddress)
        return stubbedDidStartAdoptingSystemConnectedPeripheral
    }

    // MARK: - Call counters

    var activateCallCount: Int = 0
    var startScanningCallCount: Int = 0
    var stopScanningCallCount: Int = 0
    var shutdownCallCount: Int = 0
    var appDidEnterBackgroundCallCount: Int = 0
    var appDidBecomeActiveCallCount: Int = 0
    var isScanning: Boolean = false

    override fun activate() {
        activateCallCount++
    }

    override fun startScanning() {
        startScanningCallCount++
        isScanning = true
    }

    override fun stopScanning() {
        stopScanningCallCount++
        isScanning = false
    }

    override suspend fun shutdown() {
        shutdownCallCount++
    }

    override suspend fun appDidEnterBackground() {
        appDidEnterBackgroundCallCount++
    }

    override suspend fun appDidBecomeActive() {
        appDidBecomeActiveCallCount++
    }

    // MARK: - Captured handlers + simulation helpers

    private var autoReconnectingHandler: ((String, String) -> Unit)? = null
    private var bluetoothPoweredOnHandler: (() -> Unit)? = null
    private var bluetoothStateChangeHandler: ((Int) -> Unit)? = null
    private var deviceDiscoveredHandler: ((DiscoveredDevice) -> Unit)? = null
    private var bondRefreshedHandler: ((String) -> Unit)? = null

    val hasAutoReconnectingHandler: Boolean get() = autoReconnectingHandler != null

    override fun setAutoReconnectingHandler(handler: (String, String) -> Unit) {
        autoReconnectingHandler = handler
    }

    override fun setBluetoothPoweredOnHandler(handler: () -> Unit) {
        bluetoothPoweredOnHandler = handler
    }

    override fun setBluetoothStateChangeHandler(handler: (Int) -> Unit) {
        bluetoothStateChangeHandler = handler
    }

    override fun setDeviceDiscoveredHandler(handler: (DiscoveredDevice) -> Unit) {
        deviceDiscoveredHandler = handler
    }

    override fun setBondRefreshedHandler(handler: ((String) -> Unit)?) {
        bondRefreshedHandler = handler
    }

    fun simulateAutoReconnecting(deviceAddress: String, errorInfo: String) {
        autoReconnectingHandler?.invoke(deviceAddress, errorInfo)
    }

    fun simulateBluetoothPoweredOn() {
        bluetoothPoweredOnHandler?.invoke()
    }

    fun simulateBluetoothStateChange(state: Int) {
        bluetoothStateChangeHandler?.invoke(state)
    }

    fun simulateDiscoveredDevice(device: DiscoveredDevice) {
        deviceDiscoveredHandler?.invoke(device)
    }

    /** Fires the registered bond-refreshed handler, as the real state machine would on an RSSI success. */
    fun simulateBondRefreshed(deviceAddress: String) {
        bondRefreshedHandler?.invoke(deviceAddress)
    }

    // MARK: - Write pacing

    var lastWritePacingDelayMs: Long? = null

    override fun setWritePacingDelay(delayMs: Long) {
        lastWritePacingDelayMs = delayMs
    }

    // MARK: - Bond verification / app-session-live (real semantics, not stubs — see class doc)

    val bondVerificationDates: MutableMap<String, Long> = mutableMapOf()
    var appSessionLiveAddress: String? = null
        private set

    override suspend fun recordBondVerification(deviceAddress: String, atMillis: Long) {
        bondVerificationDates[deviceAddress] = atMillis
    }

    override suspend fun clearBondVerification(deviceAddress: String) {
        bondVerificationDates.remove(deviceAddress)
    }

    override suspend fun setAppSessionLive(deviceAddress: String?) {
        appSessionLiveAddress = deviceAddress
    }

    override suspend fun hasBondVerification(deviceAddress: String): Boolean = bondVerificationDates.containsKey(deviceAddress)

    override suspend fun isAppSessionLive(deviceAddress: String): Boolean = appSessionLiveAddress == deviceAddress

    override suspend fun shouldPersistBondRefresh(deviceAddress: String): Boolean =
        bondVerificationDates.containsKey(deviceAddress) && appSessionLiveAddress == deviceAddress

    /** Resets every stub, counter, captured handler, and tracked state to its default. */
    fun reset() {
        stubbedIsConnected = false
        stubbedIsAutoReconnecting = false
        stubbedConnectedDeviceAddress = null
        stubbedLinkDiagnostics = BleLinkDiagnostics(adapterState = "STATE_ON", phase = BlePhaseKind.IDLE, deviceState = null)
        stubbedIsBluetoothPoweredOff = false

        stubbedIsDeviceConnectedToSystem = false
        isDeviceConnectedToSystemHandler = null
        isDeviceConnectedToSystemCalls.clear()
        stubbedSystemConnectedDeviceAddresses = emptyList()
        stubbedDidStartAdoptingSystemConnectedPeripheral = false
        startAdoptingSystemConnectedPeripheralCalls.clear()

        activateCallCount = 0
        startScanningCallCount = 0
        stopScanningCallCount = 0
        shutdownCallCount = 0
        appDidEnterBackgroundCallCount = 0
        appDidBecomeActiveCallCount = 0
        isScanning = false

        autoReconnectingHandler = null
        bluetoothPoweredOnHandler = null
        bluetoothStateChangeHandler = null
        deviceDiscoveredHandler = null
        bondRefreshedHandler = null

        lastWritePacingDelayMs = null

        bondVerificationDates.clear()
        appSessionLiveAddress = null
    }
}
