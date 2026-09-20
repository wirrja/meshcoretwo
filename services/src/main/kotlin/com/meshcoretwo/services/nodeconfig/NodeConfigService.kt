// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.nodeconfig

import com.meshcoretwo.protocol.MeshContact
import com.meshcoretwo.protocol.MeshCoreError
import com.meshcoretwo.protocol.NodeConfigSessionOps
import com.meshcoretwo.protocol.PacketBuilder
import com.meshcoretwo.protocol.SelfInfo
import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.protocol.utf8Prefix
import com.meshcoretwo.services.channels.ChannelService
import com.meshcoretwo.services.settings.SettingsService
import com.meshcoretwo.services.settings.SettingsServiceError
import com.meshcoretwo.services.settings.TelemetryModes
import com.meshcoretwo.services.sync.SyncCoordinator
import com.meshcoretwo.services.utilities.VContactIdentity
import java.util.UUID
import kotlin.math.roundToLong

// MARK: - Node Config Service Errors

/** Identifies which radio parameter failed range validation. */
enum class RadioField { FREQUENCY, BANDWIDTH, SPREADING_FACTOR, CODING_RATE, TX_POWER }

/** Identifies which coordinate failed range validation, and on which record. */
sealed class CoordinateField {
    data object PositionLatitude : CoordinateField()
    data object PositionLongitude : CoordinateField()
    data class ContactLatitude(val name: String) : CoordinateField()
    data class ContactLongitude(val name: String) : CoordinateField()
}

/**
 * Errors from [NodeConfigService]'s export/import/preview. Kept `L10n`-free so the service layer
 * carries no localization — the app layer maps each case to a localized message when the
 * export/import UI is built (see [NodeConfigServiceError]'s Swift counterpart,
 * `NodeConfigServiceError+UserFacingMessage.swift`, not yet ported for the same reason the UI
 * itself isn't — see this file's class doc).
 */
sealed class NodeConfigServiceError(message: String) : Exception(message) {
    data class InvalidChannelSecret(val index: Int, val hexLength: Int) :
        NodeConfigServiceError("Channel $index has invalid secret ($hexLength hex chars, expected 32)")

    data class InvalidContactPublicKey(val name: String) :
        NodeConfigServiceError("Contact \"$name\" has an invalid public key")

    data class InvalidPathHashMode(val name: String, val mode: UByte) :
        NodeConfigServiceError("Contact \"$name\" has unsupported path hash mode $mode (expected 0, 1, or 2)")

    data class InvalidPrivateKey(val hexLength: Int) :
        NodeConfigServiceError("Invalid private key ($hexLength hex chars, expected 128)")

    data class InvalidRadioSettings(val field: RadioField) :
        NodeConfigServiceError("Radio parameter is outside the supported range")

    data class NoAvailableChannelSlot(val name: String) :
        NodeConfigServiceError("No empty channel slot available for \"$name\"")

    data class InvalidCoordinate(val field: CoordinateField) :
        NodeConfigServiceError("Coordinate is invalid or out of range")

    data class InvalidOutPath(val name: String) :
        NodeConfigServiceError("Contact \"$name\" has an invalid routing path")

    data class ContactCapacityExceeded(val needed: Int, val available: Int) :
        NodeConfigServiceError("Import needs $needed free contact slot(s) but only $available remain on the device")
}

// MARK: - Import Progress

/** Identifies which destructive write an [ImportProgress] update precedes. */
sealed class ImportStep {
    data object Position : ImportStep()
    data object OtherParameters : ImportStep()
    data object PrivateKey : ImportStep()
    data object NodeName : ImportStep()
    data object RadioParameters : ImportStep()
    data object TxPower : ImportStep()
    data class Channel(val name: String) : ImportStep()
    data class Contact(val name: String) : ImportStep()
}

/** Reports import progress to the UI. */
data class ImportProgress(val step: ImportStep, val current: Int, val total: Int)

// MARK: - Import Preview

/**
 * A non-destructive summary of what an import would do, used to drive the confirmation UI before
 * any write. Computed by running the same planner the import uses.
 */
data class ImportPreview(
    /**
     * True when at least one channel write would replace an already-configured slot whose name or
     * secret differs — i.e. the channels section is not purely additive.
     */
    val channelsOverwriteExisting: Boolean,
)

// MARK: - Node Config Service

/**
 * Exports device configuration to [MeshCoreNodeConfig] and imports it back, handling section
 * filtering, other-params merging, and safe import ordering. Ported from `NodeConfigService.swift`
 * (683 lines) — first landed as its own vertical slice deliberately deferred out of
 * [com.meshcoretwo.services.ServiceContainer]'s A2 composition-root slice, then wired into
 * [com.meshcoretwo.services.ServiceContainer] and the `app`-layer export/import screens
 * (`com.meshcoretwo.android.settings.NodeConfigExportScreen`/`NodeConfigImportScreen` +
 * their ViewModels) in a follow-up slice — see [com.meshcoretwo.services.ServiceContainer]'s doc
 * for what is still not wired ([setOnPostIdentityImport]'s callback).
 *
 * Unlike Swift's `actor`, this is a plain class with no dispatcher confinement: every method is a
 * single top-to-bottom `suspend` call chain with no shared mutable state besides
 * [onPostIdentityImport] (set once, read once, no concurrent-mutation hazard for a single-caller
 * export/import UI flow) — the same reasoning already applied to [ChannelService]/[SettingsService].
 */
class NodeConfigService(
    private val session: NodeConfigSessionOps,
    private val settingsService: SettingsService,
    private val channelService: ChannelService,
    private val syncCoordinator: SyncCoordinator? = null,
) {
    /**
     * Called after a config import restores a private key, so the connection layer can reconcile
     * device identity. Installed by the future `ConnectionManager`-equivalent wiring, mirroring
     * Swift's `buildServicesAndSaveDevice`.
     */
    private var onPostIdentityImport: (suspend () -> UUID?)? = null

    /** Wires a late-bound callback that fires after `importConfig` restores identity. */
    fun setOnPostIdentityImport(callback: (suspend () -> UUID?)?) {
        onPostIdentityImport = callback
    }

    // MARK: - Export

    /** Reads the device state and builds a [MeshCoreNodeConfig]. */
    suspend fun exportConfig(sections: ConfigSections): MeshCoreNodeConfig {
        val selfInfo = settingsService.getSelfInfo()

        val config = MeshCoreNodeConfig()

        if (sections.nodeIdentity) {
            config.name = selfInfo.name
            config.publicKey = selfInfo.publicKey.hexString
            // Hardened firmware disables private-key export (FeatureDisabled). Emit name + public
            // key in that case rather than failing the whole export. Any other failure (transient
            // BLE timeout, device error, cancellation) propagates, so the user sees the export
            // fail and retries instead of saving an identity backup missing its key.
            try {
                config.privateKey = settingsService.exportPrivateKey().hexString
            } catch (e: SettingsServiceError.SessionError) {
                if (e.error != MeshCoreError.FeatureDisabled) throw e
            }
        }

        if (sections.radioSettings) {
            config.radioSettings = buildRadioSettings(selfInfo)
        }

        if (sections.positionSettings) {
            config.positionSettings = MeshCoreNodeConfig.PositionSettings(
                latitude = selfInfo.latitude.toString(),
                longitude = selfInfo.longitude.toString(),
            )
        }

        if (sections.otherSettings) {
            config.otherSettings = buildOtherSettings(selfInfo)
        }

        if (sections.channels) {
            val capabilities = settingsService.queryDevice()
            config.channels = exportChannels(capabilities.maxChannels.toUByte())
        }

        if (sections.contacts) {
            val meshContacts = session.getContacts(since = null)
            config.contacts = meshContacts.map { buildContactConfig(it) }
        }

        return config
    }

    // MARK: - Import

    /**
     * Writes a [MeshCoreNodeConfig] to the device in safe order (radio last).
     *
     * Runs in three phases: a non-destructive **read** of device capabilities and current
     * channels, a pure **validate/plan** pass ([planConfigImport]) that rejects a malformed config
     * before any write, then **execute**. Because everything that can be validated is validated up
     * front, no validation error can land after the identity has already been rotated.
     */
    suspend fun importConfig(
        config: MeshCoreNodeConfig,
        sections: ConfigSections,
        radioID: UUID,
        onProgress: ((ImportProgress) -> Unit)? = null,
    ) {
        // Read + validate/plan phase (non-destructive): rejects a poison/malformed config before any write.
        val plan = buildImportPlan(config, sections)

        val coordinator = syncCoordinator
        executeConfigImport(
            plan = plan,
            sections = sections,
            radioID = radioID,
            writers = makeWriters(),
            onProgress = onProgress,
            notifyContactsChanged = { coordinator?.notifyContactsChanged() },
        )
    }

    /**
     * Builds the concrete write closures [executeConfigImport] calls, capturing this service's
     * dependencies. Each closure performs exactly one device (and, for contacts, database) write.
     */
    private fun makeWriters(): ConfigImportWriters {
        val callback = onPostIdentityImport
        return ConfigImportWriters(
            getSelfInfo = { settingsService.getSelfInfo() },
            importPrivateKey = { settingsService.importPrivateKey(it) },
            setNodeName = { settingsService.setNodeName(it) },
            setLocation = { lat, lon -> settingsService.setLocation(lat, lon) },
            setOtherParams = { importOtherParams(it) },
            resolveEffectiveRadioID = { original, didImportPrivateKey ->
                resolveEffectiveRadioID(original, didImportPrivateKey, callback)
            },
            setRadioParams = { radio ->
                // bandwidthKHz parameter actually takes Hz (misnomer); pass directly.
                settingsService.setRadioParams(
                    frequencyKHz = radio.frequency,
                    bandwidthKHz = radio.bandwidth,
                    spreadingFactor = radio.spreadingFactor,
                    codingRate = radio.codingRate,
                )
            },
            setTxPower = { settingsService.setTxPower(it) },
            setChannel = { radioID, write ->
                channelService.setChannelWithSecret(radioID = radioID, index = write.index, name = write.name, secret = write.secret)
            },
            addContact = { radioID, contact ->
                session.addContact(contact)
                // The device add is the irreversible change. The local row is an idempotent
                // (radioID, publicKey) upsert that the next contacts sync reconciles, so a save
                // failure here must not mask that the contact already landed on the device.
                // (No local contact-row save here: this port has no unified `dataStore.saveContact`
                // seam at this layer — the next AdvertisementService/ContactService sync reconciles it.)
            },
        )
    }

    /**
     * Non-destructively summarizes what an import would do, so the confirmation UI can warn about
     * channel overwrites before any write. Runs the same planner as [importConfig], so it also
     * surfaces validation errors early. The selection UI shows raw per-section counts; the
     * planner's post-dedup counts are not surfaced here, only the overwrite flag.
     */
    suspend fun previewImport(config: MeshCoreNodeConfig, sections: ConfigSections): ImportPreview {
        val plan = buildImportPlan(config, sections)
        return ImportPreview(channelsOverwriteExisting = plan.channelsOverwriteExisting)
    }

    // MARK: - Internal Helpers

    /**
     * Read phase shared by [importConfig] and [previewImport]: gathers device capabilities and
     * current state (channel slots, existing contact keys, max TX power) needed to validate the
     * config, then runs the pure planner. Non-destructive. [importConfig] re-runs this at execute
     * time for TOCTOU freshness, so the device reads happen twice across a preview-then-apply
     * cycle; that is an accepted cost on this cold, user-initiated path.
     */
    private suspend fun buildImportPlan(config: MeshCoreNodeConfig, sections: ConfigSections): ConfigImportPlan {
        val capabilities = settingsService.queryDevice()
        val maxChannels = capabilities.maxChannels.toUByte()
        val maxContacts = capabilities.maxContacts

        val existingChannels = if (sections.channels) readExistingChannels(maxChannels) else emptyList()

        val existingContacts: MutableMap<String, MeshContact> = if (sections.contacts) {
            session.getContacts(since = null).associateByTo(LinkedHashMap()) { it.publicKey.hexString }
        } else {
            mutableMapOf()
        }

        // Self-info is shared by V-contact capacity filtering and max TX power.
        val selfInfo = if (sections.contacts || sections.radioSettings) settingsService.getSelfInfo() else null

        var planConfig = config
        if (sections.contacts) {
            val selfPublicKey = selfInfo?.publicKey
            val vKey = selfPublicKey?.let { VContactIdentity.publicKey(it) }
            if (vKey != null) {
                val vKeyHex = vKey.hexString
                existingContacts.remove(vKeyHex)
                planConfig = planConfig.copy(contacts = planConfig.contacts?.filter { it.publicKey.lowercase() != vKeyHex.lowercase() })
            }
        }

        // The txPower upper bound is hardware/build-specific, so read the device's max rather than
        // assuming a fixed maximum. Only needed when the radio section is selected.
        val maxTxPower: Byte = if (sections.radioSettings) selfInfo?.maxTxPower ?: 0 else 0

        return planConfigImport(
            config = planConfig,
            sections = sections,
            maxChannels = maxChannels,
            maxContacts = maxContacts,
            maxTxPower = maxTxPower,
            existingChannels = existingChannels,
            existingContacts = existingContacts,
        )
    }

    /** Reads every channel slot from the device and classifies it as configured or empty. */
    private suspend fun readExistingChannels(maxChannels: UByte): List<DeviceChannelSlot> {
        val slots = mutableListOf<DeviceChannelSlot>()
        for (i in 0 until maxChannels.toInt()) {
            val index = i.toUByte()
            val info = session.getChannel(index)
            slots.add(DeviceChannelSlot(index, info.name, info.secret, ChannelService.isChannelConfigured(info.name, info.secret)))
        }
        return slots
    }

    /** Reads all configured channels from the device. */
    private suspend fun exportChannels(maxChannels: UByte): List<MeshCoreNodeConfig.ChannelConfig> {
        val channels = mutableListOf<MeshCoreNodeConfig.ChannelConfig>()
        for (i in 0 until maxChannels.toInt()) {
            val info = session.getChannel(i.toUByte())
            if (ChannelService.isChannelConfigured(info.name, info.secret)) {
                channels.add(MeshCoreNodeConfig.ChannelConfig(name = info.name, secret = info.secret.hexString))
            }
        }
        return channels
    }

    /**
     * Merges imported other-settings with current device values for fields not in the import.
     * `advertisementType` is neither exported nor imported here (firmware-managed); `setOtherParams`
     * does not accept it.
     */
    internal suspend fun importOtherParams(imported: MeshCoreNodeConfig.OtherSettings) {
        val current = settingsService.getSelfInfo()

        val currentManualAdd: UByte = if (current.manualAddContacts) 1u else 0u
        val manualAdd = imported.manualAddContacts ?: currentManualAdd
        val advertPolicy = imported.advertLocationPolicy ?: current.advertisementLocationPolicy
        val telBase = imported.telemetryModeBase ?: current.telemetryModeBase
        val telLocation = imported.telemetryModeLocation ?: current.telemetryModeLocation
        val telEnvironment = imported.telemetryModeEnvironment ?: current.telemetryModeEnvironment
        val multiAcks = imported.multiAcks ?: current.multiAcks

        // Skip the write entirely when the merged result matches every current value:
        // setOtherParams commits the whole /new_prefs blob synchronously, so a no-op merge is a
        // vulnerable flash write for nothing.
        if (manualAdd == currentManualAdd && advertPolicy == current.advertisementLocationPolicy &&
            telBase == current.telemetryModeBase && telLocation == current.telemetryModeLocation &&
            telEnvironment == current.telemetryModeEnvironment && multiAcks == current.multiAcks
        ) {
            return
        }

        // Pass the policy as a raw byte rather than a typed enum so a value the app doesn't model
        // is sent verbatim instead of coerced. The firmware stores this byte without clamping and
        // only special-cases the zero value, so any non-zero policy the app doesn't yet model is
        // persisted as-is and behaves as "share location".
        settingsService.setOtherParams(
            autoAddContacts = manualAdd == 0u.toUByte(),
            telemetryModes = TelemetryModes(base = telBase, location = telLocation, environment = telEnvironment),
            advertLocationPolicyRaw = advertPolicy,
            multiAcks = multiAcks,
        )
    }

    companion object {
        /** Total destructive steps for progress reporting. See [stepCount] in `executeConfigImport`'s file for the mirroring rule. */
        internal fun stepCount(plan: ConfigImportPlan): Int {
            var count = 0
            if (plan.importPrivateKey != null) count++
            if (plan.nodeName != null) count++
            if (plan.position != null) count++
            if (plan.otherSettings != null) count++
            count += plan.channelWrites.size
            count += plan.contactRecords.size
            if (plan.radioSettings != null) count += 2 // radio params + tx power
            return count
        }

        /** Builds radio settings from [SelfInfo]. */
        fun buildRadioSettings(info: SelfInfo): MeshCoreNodeConfig.RadioSettings = MeshCoreNodeConfig.RadioSettings(
            frequency = (info.radioFrequency * 1000).roundToLong().toUInt(),
            bandwidth = (info.radioBandwidth * 1000).roundToLong().toUInt(),
            spreadingFactor = info.radioSpreadingFactor,
            codingRate = info.radioCodingRate,
            txPower = info.txPower,
        )

        /** Builds other settings from [SelfInfo], matching official companion app format. */
        fun buildOtherSettings(info: SelfInfo): MeshCoreNodeConfig.OtherSettings = MeshCoreNodeConfig.OtherSettings(
            manualAddContacts = if (info.manualAddContacts) 1u else 0u,
            advertLocationPolicy = info.advertisementLocationPolicy,
        )

        /** Builds a contact config from a [MeshContact]. */
        fun buildContactConfig(contact: MeshContact): MeshCoreNodeConfig.ContactConfig {
            val outPath: String? = when {
                contact.isFloodPath -> null
                contact.pathByteLength > 0 && contact.outPath.isNotEmpty() -> contact.outPath.copyOfRange(0, contact.pathByteLength).hexString
                else -> ""
            }

            // Extract hash mode from encoded outPathLength (upper 2 bits)
            val pathHashMode: UByte? = if (contact.isFloodPath) null else (contact.outPathLength.toInt() shr 6).toUByte()

            return MeshCoreNodeConfig.ContactConfig(
                type = contact.typeRawValue,
                name = contact.advertisedName,
                publicKey = contact.publicKey.hexString,
                flags = contact.flags.rawValue,
                latitude = contact.latitude.toString(),
                longitude = contact.longitude.toString(),
                lastAdvert = contact.lastAdvertisement.epochSecond.toUInt(),
                lastModified = contact.lastModified.epochSecond.toUInt(),
                outPath = outPath,
                pathHashMode = pathHashMode,
            )
        }
    }
}

// MARK: - Post-Identity Resolution (testable seam)

/**
 * Resolves the `radioID` that subsequent `importConfig` steps should use, given whether
 * `importIdentity` actually pushed a private key to the radio and the late-bound reconciliation
 * callback.
 *
 * Extracted as a free function so it can be unit-tested without constructing a real
 * [NodeConfigService] (which would require a live session).
 */
internal suspend fun resolveEffectiveRadioID(original: UUID, didImportPrivateKey: Boolean, callback: (suspend () -> UUID?)?): UUID {
    if (!didImportPrivateKey || callback == null) return original
    return callback() ?: original
}

// MARK: - Execute Orchestration (testable seam)

/**
 * The concrete device/database write operations [executeConfigImport] performs, one per closure.
 * [NodeConfigService] builds these from its services; tests build spies, so the destructive-path
 * sequencing can be exercised without a live session.
 */
internal data class ConfigImportWriters(
    val getSelfInfo: suspend () -> SelfInfo,
    val importPrivateKey: suspend (ByteArray) -> Unit,
    val setNodeName: suspend (String) -> Unit,
    val setLocation: suspend (Double, Double) -> Unit,
    val setOtherParams: suspend (MeshCoreNodeConfig.OtherSettings) -> Unit,
    val resolveEffectiveRadioID: suspend (UUID, Boolean) -> UUID,
    val setRadioParams: suspend (MeshCoreNodeConfig.RadioSettings) -> Unit,
    val setTxPower: suspend (Byte) -> Unit,
    val setChannel: suspend (UUID, ConfigImportPlan.ChannelWrite) -> Unit,
    val addContact: suspend (UUID, MeshContact) -> Unit,
)

// MARK: - Pref/radio diff gates (testable seam)

private const val MAX_USABLE_NAME_BYTES = 31

/**
 * Whether each pref/radio write in a plan actually differs from the device's current [SelfInfo].
 * Re-importing an unchanged config would otherwise fire an immediate, full-`/new_prefs` commit per
 * pref; these four gates (node name, position, radio params, TX power) elide the ones the device
 * already holds. The fifth pref commit, other-params, gates itself inside `importOtherParams`,
 * where the imported-`??`-current merge it depends on lives. Computed once per import from a
 * freshly-read [SelfInfo] so a value the user changed on the device since export still writes.
 */
internal fun nodeNameNeedsWrite(name: String, current: SelfInfo): Boolean =
    name.utf8Prefix(MAX_USABLE_NAME_BYTES) != current.name

internal fun locationNeedsWrite(position: ConfigImportPlan.Coordinate, current: SelfInfo): Boolean =
    PacketBuilder.scaledCoordinate(position.latitude, PacketBuilder.LATITUDE_RANGE) !=
        PacketBuilder.scaledCoordinate(current.latitude, PacketBuilder.LATITUDE_RANGE) ||
        PacketBuilder.scaledCoordinate(position.longitude, PacketBuilder.LONGITUDE_RANGE) !=
        PacketBuilder.scaledCoordinate(current.longitude, PacketBuilder.LONGITUDE_RANGE)

internal fun radioParamsNeedWrite(radio: MeshCoreNodeConfig.RadioSettings, current: SelfInfo): Boolean {
    val expected = NodeConfigService.buildRadioSettings(current).copy(txPower = radio.txPower)
    return radio != expected
}

internal fun txPowerNeedsWrite(radio: MeshCoreNodeConfig.RadioSettings, current: SelfInfo): Boolean = radio.txPower != current.txPower

/**
 * Executes a validated [ConfigImportPlan] in safe order: identity (so a private-key import can
 * reassign the radioID before channels/contacts are keyed by it), then position, other params,
 * channels, contacts, and radio last. Each `onProgress` call is emitted only after its write
 * succeeds, so "no progress reported" reliably means "nothing was written" — a first-write failure
 * surfaces as a clean failure, not a partial one.
 *
 * Extracted as a free function taking write closures (mirroring [resolveEffectiveRadioID]) so the
 * ordering and progress behavior are unit-testable without a live session. Unlike Swift, this port
 * has no `Task.checkCancellation()` equivalent gate between steps — Kotlin coroutine cancellation
 * is cooperative via `suspend` call points, and every writer here already suspends, so a cancelled
 * caller's coroutine unwinds at the next writer call without an explicit check.
 */
internal suspend fun executeConfigImport(
    plan: ConfigImportPlan,
    sections: ConfigSections,
    radioID: UUID,
    writers: ConfigImportWriters,
    onProgress: ((ImportProgress) -> Unit)?,
    notifyContactsChanged: suspend () -> Unit = {},
) {
    val totalSteps = NodeConfigService.stepCount(plan)
    var currentStep = 0
    fun progress(step: ImportStep) {
        currentStep += 1
        onProgress?.invoke(ImportProgress(step, currentStep, totalSteps))
    }

    // Read current device state once so the pref/radio writes below can be diff-gated against it.
    // Only fetched when a section that carries a savePrefs commit is present; otherParams gates
    // itself inside importOtherParams (where the merge lives), so it is not a trigger here.
    val needsPrefState = plan.nodeName != null || plan.position != null || plan.radioSettings != null
    val prefState = if (needsPrefState) writers.getSelfInfo() else null

    var effectiveRadioID = radioID
    if (plan.importPrivateKey != null) {
        writers.importPrivateKey(plan.importPrivateKey)
        progress(ImportStep.PrivateKey)
    }
    if (plan.nodeName != null) {
        if (prefState?.let { nodeNameNeedsWrite(plan.nodeName, it) } ?: true) {
            writers.setNodeName(plan.nodeName)
        }
        progress(ImportStep.NodeName)
    }
    if (sections.nodeIdentity) {
        effectiveRadioID = writers.resolveEffectiveRadioID(radioID, plan.importPrivateKey != null)
    }

    if (plan.position != null) {
        if (prefState?.let { locationNeedsWrite(plan.position, it) } ?: true) {
            writers.setLocation(plan.position.latitude, plan.position.longitude)
        }
        progress(ImportStep.Position)
    }

    if (plan.otherSettings != null) {
        writers.setOtherParams(plan.otherSettings)
        progress(ImportStep.OtherParameters)
    }

    for (write in plan.channelWrites) {
        writers.setChannel(effectiveRadioID, write)
        progress(ImportStep.Channel(write.name))
    }

    for (contact in plan.contactRecords) {
        writers.addContact(effectiveRadioID, contact)
        progress(ImportStep.Contact(contact.advertisedName))
    }
    if (sections.contacts) {
        // Refresh contacts as soon as they are written, before the radio step, so a later radio
        // failure does not suppress the UI update for contacts that already landed.
        notifyContactsChanged()
    }

    // Radio goes last (minimizes mesh isolation on BLE disconnect). Params and TX power are gated
    // independently: a change to only one re-commits only the pref it touched.
    if (plan.radioSettings != null) {
        if (prefState?.let { radioParamsNeedWrite(plan.radioSettings, it) } ?: true) {
            writers.setRadioParams(plan.radioSettings)
        }
        progress(ImportStep.RadioParameters)

        if (prefState?.let { txPowerNeedsWrite(plan.radioSettings, it) } ?: true) {
            writers.setTxPower(plan.radioSettings.txPower)
        }
        progress(ImportStep.TxPower)
    }
}
