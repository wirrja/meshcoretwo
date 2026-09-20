// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.nodeconfig

import com.meshcoretwo.protocol.ContactFlags
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.MeshContact
import com.meshcoretwo.protocol.PacketBuilder
import com.meshcoretwo.protocol.PathEncoding
import com.meshcoretwo.protocol.decodeHex
import com.meshcoretwo.protocol.encodePathLen
import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.protocol.utf8Prefix
import com.meshcoretwo.services.channels.ChannelService
import java.time.Instant

// MARK: - Device Channel Slot

/**
 * A snapshot of one channel slot read from the device during the import read phase. Consumed by
 * [planConfigImport] so slot planning stays a pure, testable function. Ported from
 * `NodeConfigImportPlanner.swift`'s `DeviceChannelSlot`.
 */
internal data class DeviceChannelSlot(val index: UByte, val name: String, val secret: ByteArray, val isConfigured: Boolean) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is DeviceChannelSlot && index == other.index && name == other.name &&
                secret.contentEquals(other.secret) && isConfigured == other.isConfigured
            )

    override fun hashCode(): Int {
        var result = index.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + secret.contentHashCode()
        result = 31 * result + isConfigured.hashCode()
        return result
    }
}

// MARK: - Config Import Plan

/**
 * A fully-resolved, validated set of writes produced from a [MeshCoreNodeConfig] *before* any
 * destructive device/database write begins.
 *
 * Building a plan throws [NodeConfigServiceError] on any structural problem, so a malformed or
 * poison config is rejected up front and nothing is half-applied. The execute phase then performs
 * only writes that have already been proven applyable. Ported from `NodeConfigImportPlanner.swift`.
 */
internal data class ConfigImportPlan(
    /** Validated 64-byte private key to push, when identity is selected and present. */
    val importPrivateKey: ByteArray?,
    /** Node name to set, when identity is selected and present. */
    val nodeName: String?,
    /** Validated, in-range position to set. */
    val position: Coordinate?,
    /** Other-settings to merge at execute time (passed through verbatim, raw bytes preserved). */
    val otherSettings: MeshCoreNodeConfig.OtherSettings?,
    /** Validated radio parameters to write, when the radio section is selected and present. */
    val radioSettings: MeshCoreNodeConfig.RadioSettings?,
    /** Resolved channel writes (deduplicated; intra-import duplicates folded onto one slot). */
    val channelWrites: List<ChannelWrite>,
    /**
     * True when any channel write replaces an already-configured slot whose name/secret differs
     * — i.e. the channels section is not purely additive for this config.
     */
    val channelsOverwriteExisting: Boolean,
    /** Validated, deduplicated contact records ready to write (raw type byte preserved). */
    val contactRecords: List<MeshContact>,
) {
    data class Coordinate(val latitude: Double, val longitude: Double)

    data class ChannelWrite(val index: UByte, val name: String, val secret: ByteArray) {
        override fun equals(other: Any?): Boolean =
            this === other || (other is ChannelWrite && index == other.index && name == other.name && secret.contentEquals(other.secret))

        override fun hashCode(): Int = 31 * (31 * index.hashCode() + name.hashCode()) + secret.contentHashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConfigImportPlan) return false
        val privateKeysEqual = when {
            importPrivateKey == null || other.importPrivateKey == null -> importPrivateKey == other.importPrivateKey
            else -> importPrivateKey.contentEquals(other.importPrivateKey)
        }
        return privateKeysEqual &&
            nodeName == other.nodeName && position == other.position && otherSettings == other.otherSettings &&
            radioSettings == other.radioSettings && channelWrites == other.channelWrites &&
            channelsOverwriteExisting == other.channelsOverwriteExisting && contactRecords == other.contactRecords
    }

    override fun hashCode(): Int {
        var result = importPrivateKey?.contentHashCode() ?: 0
        result = 31 * result + (nodeName?.hashCode() ?: 0)
        result = 31 * result + (position?.hashCode() ?: 0)
        result = 31 * result + (otherSettings?.hashCode() ?: 0)
        result = 31 * result + (radioSettings?.hashCode() ?: 0)
        result = 31 * result + channelWrites.hashCode()
        result = 31 * result + channelsOverwriteExisting.hashCode()
        result = 31 * result + contactRecords.hashCode()
        return result
    }
}

// MARK: - Planner

/**
 * Validates a [MeshCoreNodeConfig] against the device's capabilities and current channel state,
 * returning a ready-to-execute [ConfigImportPlan] or throwing the first problem.
 *
 * Pure and synchronous so it is unit-testable without a live session — mirrors the
 * `resolveEffectiveRadioID` seam pattern. Only sections present in [sections] are planned.
 */
internal fun planConfigImport(
    config: MeshCoreNodeConfig,
    sections: ConfigSections,
    maxChannels: UByte,
    maxContacts: Int,
    maxTxPower: Byte,
    existingChannels: List<DeviceChannelSlot>,
    existingContacts: Map<String, MeshContact>,
): ConfigImportPlan {
    var importPrivateKey: ByteArray? = null
    var nodeName: String? = null
    var position: ConfigImportPlan.Coordinate? = null
    var otherSettings: MeshCoreNodeConfig.OtherSettings? = null
    var radioSettings: MeshCoreNodeConfig.RadioSettings? = null
    var channelWrites: List<ConfigImportPlan.ChannelWrite> = emptyList()
    var channelsOverwriteExisting = false
    var contactRecords: List<MeshContact> = emptyList()

    if (sections.nodeIdentity) {
        importPrivateKey = planPrivateKey(config)
        nodeName = config.name
    }

    if (sections.positionSettings) {
        config.positionSettings?.let { pos ->
            val lat = validatedCoordinate(pos.latitude, CoordinateField.PositionLatitude, PacketBuilder.LATITUDE_RANGE)
            val lon = validatedCoordinate(pos.longitude, CoordinateField.PositionLongitude, PacketBuilder.LONGITUDE_RANGE)
            position = ConfigImportPlan.Coordinate(lat, lon)
        }
    }

    if (sections.otherSettings) {
        otherSettings = config.otherSettings
    }

    if (sections.radioSettings) {
        config.radioSettings?.let { radioSettings = planRadioSettings(it, maxTxPower) }
    }

    if (sections.channels) {
        config.channels?.let { channels ->
            val (writes, overwrite) = planChannelWrites(channels, maxChannels, existingChannels)
            channelWrites = writes
            channelsOverwriteExisting = overwrite
        }
    }

    if (sections.contacts) {
        config.contacts?.let { contacts ->
            contactRecords = planContactRecords(contacts, maxContacts, existingContacts)
        }
    }

    return ConfigImportPlan(
        importPrivateKey = importPrivateKey,
        nodeName = nodeName,
        position = position,
        otherSettings = otherSettings,
        radioSettings = radioSettings,
        channelWrites = channelWrites,
        channelsOverwriteExisting = channelsOverwriteExisting,
        contactRecords = contactRecords,
    )
}

// MARK: - Identity

private const val PRIVATE_KEY_SIZE = 64

private fun String.isAllHexDigits(): Boolean = all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }

/**
 * A present-but-unparseable key must be rejected, not silently skipped. The key is the 64-byte
 * expanded Ed25519 secret (`clamp(SHA512(seed))`). The public key is derivable from this scalar
 * (firmware re-derives and validates it on import), but this port cannot cheaply re-derive and
 * cross-check it here, and takes the pairing on trust — same as Swift (see class doc there).
 * Firmware additionally rejects all-00/FF-prefix keys and the known test keypair; that content
 * check is intentionally deferred to the device and is the one identity failure that can surface
 * at execute time, but firmware checks it before saving the identity, so it still cannot leave a
 * half-rotated identity.
 */
private fun planPrivateKey(config: MeshCoreNodeConfig): ByteArray? {
    val privateKeyHex = config.privateKey ?: return null
    val privateKeyData = if (privateKeyHex.isAllHexDigits()) privateKeyHex.decodeHex() else null
    if (privateKeyData == null || privateKeyData.size != PRIVATE_KEY_SIZE) {
        throw NodeConfigServiceError.InvalidPrivateKey(hexLength = privateKeyHex.length)
    }
    return privateKeyData
}

// MARK: - Coordinates

private fun validatedCoordinate(raw: String, field: CoordinateField, range: ClosedRange<Double>): Double {
    val value = raw.toDoubleOrNull()
    if (value == null || !value.isFinite() || value !in range) {
        throw NodeConfigServiceError.InvalidCoordinate(field)
    }
    return value
}

// MARK: - Radio

/**
 * Validates radio parameters against the firmware-accepted ranges so an out-of-range value from a
 * hand-edited backup is rejected up front rather than throwing at execute time, after the identity
 * has already been rotated. The txPower upper bound is the device-reported `maxTxPower`, since it
 * is hardware/build-specific; the other ranges are fixed firmware limits in [PacketBuilder].
 */
private fun planRadioSettings(radio: MeshCoreNodeConfig.RadioSettings, maxTxPower: Byte): MeshCoreNodeConfig.RadioSettings {
    if (radio.frequency !in PacketBuilder.FREQUENCY_RANGE_KHZ) throw NodeConfigServiceError.InvalidRadioSettings(RadioField.FREQUENCY)
    if (radio.bandwidth !in PacketBuilder.BANDWIDTH_RANGE_HZ) throw NodeConfigServiceError.InvalidRadioSettings(RadioField.BANDWIDTH)
    if (radio.spreadingFactor.toInt() !in PacketBuilder.SPREADING_FACTOR_RANGE) {
        throw NodeConfigServiceError.InvalidRadioSettings(RadioField.SPREADING_FACTOR)
    }
    if (radio.codingRate.toInt() !in PacketBuilder.CODING_RATE_RANGE) throw NodeConfigServiceError.InvalidRadioSettings(RadioField.CODING_RATE)
    if (radio.txPower < PacketBuilder.TX_POWER_FLOOR || radio.txPower > maxTxPower) {
        throw NodeConfigServiceError.InvalidRadioSettings(RadioField.TX_POWER)
    }
    return radio
}

// MARK: - Channels

/**
 * Plans channel slot assignment with merge semantics, folding intra-import duplicates — same
 * hashtag name or secret — onto one slot so a config never consumes two slots for the same
 * channel, and flagging overwrites of already-configured slots.
 */
private fun planChannelWrites(
    channels: List<MeshCoreNodeConfig.ChannelConfig>,
    maxChannels: UByte,
    existingChannels: List<DeviceChannelSlot>,
): Pair<List<ConfigImportPlan.ChannelWrite>, Boolean> {
    val hashtagNameToIndex = mutableMapOf<String, UByte>()
    val secretToIndex = mutableMapOf<String, UByte>()
    val emptyIndices = ArrayDeque<UByte>()
    val existingByIndex = mutableMapOf<UByte, Pair<String, ByteArray>>()

    for (slot in existingChannels) {
        if (slot.index >= maxChannels) continue
        if (slot.isConfigured) {
            existingByIndex[slot.index] = slot.name to slot.secret
            secretToIndex[slot.secret.hexString] = slot.index
            if (slot.name.startsWith("#")) {
                hashtagNameToIndex[slot.name.utf8Prefix(MAX_USABLE_NAME_BYTES)] = slot.index
            }
        } else {
            emptyIndices.addLast(slot.index)
        }
    }

    val writes = mutableListOf<ConfigImportPlan.ChannelWrite>()
    var overwrite = false
    // The value each slot will hold given the writes planned so far, seeded from the device's
    // configured slots. The no-op skip compares against this, not the frozen existingByIndex, so
    // a later duplicate that restores a slot an earlier write changed is not dropped.
    val plannedByIndex = existingByIndex.toMutableMap()

    for ((i, channel) in channels.withIndex()) {
        if (!channel.secret.isAllHexDigits()) throw NodeConfigServiceError.InvalidChannelSecret(i, channel.secret.length)
        val secretData = channel.secret.decodeHex()
        if (secretData == null || !ChannelService.validateSecret(secretData)) {
            throw NodeConfigServiceError.InvalidChannelSecret(i, channel.secret.length)
        }
        // Key on the re-hexed parsed bytes (canonical) so a config secret with non-canonical
        // casing still dedups against the device's canonically-keyed slots.
        val secretKey = secretData.hexString
        // The device stores names truncated to the firmware field width, so dedup and overwrite
        // comparison must use the truncated form — otherwise a long hashtag name misses its slot.
        val lookupName = channel.name.utf8Prefix(MAX_USABLE_NAME_BYTES)

        // The secret is firmware's channel-match key (findChannelIdx memcmp), so it must stay
        // single-homed. Resolve any slot the secret already occupies first; a hashtag-name match
        // must defer to it, otherwise a "#name"+new-secret import would duplicate that secret onto
        // the name's slot while its original slot still holds it, mis-attributing mesh traffic.
        val secretSlot = secretToIndex[secretKey]

        val targetIndex: UByte = when {
            secretSlot != null -> secretSlot
            channel.name.startsWith("#") && hashtagNameToIndex[lookupName] != null -> hashtagNameToIndex.getValue(lookupName)
            emptyIndices.isNotEmpty() -> emptyIndices.removeFirst()
            else -> throw NodeConfigServiceError.NoAvailableChannelSlot(channel.name)
        }

        // Skip the write when the slot's effective value already matches, comparing the truncated
        // name the device actually stores. Writing it would re-commit /channels2 for no change.
        val planned = plannedByIndex[targetIndex]
        if (planned != null && planned.first == lookupName && planned.second.contentEquals(secretData)) {
            if (channel.name.startsWith("#")) hashtagNameToIndex[lookupName] = targetIndex
            secretToIndex[secretKey] = targetIndex
            continue
        }

        // An overwrite is a change to a slot the device itself had configured, so it keys off the
        // original device state rather than the in-progress planned state.
        val original = existingByIndex[targetIndex]
        if (original != null && (original.first != lookupName || !original.second.contentEquals(secretData))) {
            overwrite = true
        }

        if (channel.name.startsWith("#")) hashtagNameToIndex[lookupName] = targetIndex
        secretToIndex[secretKey] = targetIndex
        plannedByIndex[targetIndex] = lookupName to secretData

        writes.add(ConfigImportPlan.ChannelWrite(targetIndex, channel.name, secretData))
    }

    return writes to overwrite
}

// MARK: - Contacts

/**
 * Validates and deduplicates the contacts array, returning ready-to-write records. Dedups by
 * public key (newest by `last_modified` wins), enforces device capacity, drops records the device
 * already stores byte-for-byte, and rejects invalid keys, path modes, coordinates, and routing
 * paths before any write.
 */
private fun planContactRecords(
    contacts: List<MeshCoreNodeConfig.ContactConfig>,
    maxContacts: Int,
    existingContacts: Map<String, MeshContact>,
): List<MeshContact> {
    val byKey = mutableMapOf<String, Pair<MeshCoreNodeConfig.ContactConfig, ByteArray>>()
    val order = mutableListOf<String>()

    for (contact in contacts) {
        val publicKey = if (contact.publicKey.isAllHexDigits()) contact.publicKey.decodeHex() else null
        if (publicKey == null || publicKey.size != PacketBuilder.PUBLIC_KEY_SIZE) {
            throw NodeConfigServiceError.InvalidContactPublicKey(contact.name)
        }
        val key = publicKey.hexString
        val existing = byKey[key]
        if (existing != null) {
            if (contact.lastModified >= existing.first.lastModified) byKey[key] = contact to publicKey
        } else {
            byKey[key] = contact to publicKey
            order.add(key)
        }
    }

    // A firmware update of a key already on the device consumes no slot, so only keys not already
    // present count against free capacity. Checking remaining slots (not the absolute table size)
    // lets the non-destructive preview reject an overflow up front instead of failing partway
    // through the contact writes with TABLE_FULL, after identity and channels have committed.
    val newKeyCount = order.count { it !in existingContacts }
    val availableSlots = maxContacts - existingContacts.size
    if (newKeyCount > availableSlots) throw NodeConfigServiceError.ContactCapacityExceeded(newKeyCount, availableSlots)

    // Drop a record the device already stores byte-for-byte: re-adding it would arm a
    // /contacts3 rewrite for no change. A dropped record was an existing key, so it never counted
    // against free capacity above.
    return order.mapNotNull { key ->
        val (config, publicKey) = byKey.getValue(key)
        val record = buildContactRecord(config, publicKey, key)
        val existing = existingContacts[key]
        if (existing != null && persistedContactFieldsMatch(existing, record)) null else record
    }
}

/**
 * True when [existing] (a device-resident contact) already stores exactly what [record] would
 * write, so the contact-add would re-commit `/contacts3` for nothing. Compares only the fields the
 * add frame carries and the firmware persists; `lastAdvertisement` is excluded (volatile,
 * advert-driven). A genuine edit always bumps `lastModified`, so a changed contact is never
 * skipped. Names compare at the firmware field width, and coordinates by the integer the device
 * actually stores, so a difference the device cannot represent is treated as equal.
 */
private fun persistedContactFieldsMatch(existing: MeshContact, record: MeshContact): Boolean {
    if (existing.typeRawValue != record.typeRawValue || existing.flags != record.flags ||
        existing.outPathLength != record.outPathLength ||
        existing.advertisedName.utf8Prefix(MAX_USABLE_NAME_BYTES) != record.advertisedName.utf8Prefix(MAX_USABLE_NAME_BYTES) ||
        !existing.outPath.copyOfRange(0, existing.pathByteLength).contentEquals(record.outPath.copyOfRange(0, record.pathByteLength)) ||
        existing.lastModified.epochSecond != record.lastModified.epochSecond
    ) {
        return false
    }
    return PacketBuilder.scaledCoordinate(existing.latitude, PacketBuilder.LATITUDE_RANGE) ==
        PacketBuilder.scaledCoordinate(record.latitude, PacketBuilder.LATITUDE_RANGE) &&
        PacketBuilder.scaledCoordinate(existing.longitude, PacketBuilder.LONGITUDE_RANGE) ==
        PacketBuilder.scaledCoordinate(record.longitude, PacketBuilder.LONGITUDE_RANGE)
}

/**
 * Builds one validated contact record. [publicKey] and [hexKey] are the already-decoded key and
 * its lowercase hex from [planContactRecords], so the key is parsed exactly once.
 */
private fun buildContactRecord(contact: MeshCoreNodeConfig.ContactConfig, publicKey: ByteArray, hexKey: String): MeshContact {
    val (outPath, outPathLength) = resolveOutPath(contact)

    val lat = validatedCoordinate(contact.latitude, CoordinateField.ContactLatitude(contact.name), PacketBuilder.LATITUDE_RANGE)
    val lon = validatedCoordinate(contact.longitude, CoordinateField.ContactLongitude(contact.name), PacketBuilder.LONGITUDE_RANGE)

    return MeshContact(
        id = hexKey,
        publicKey = publicKey,
        type = ContactType.fromValue(contact.type) ?: ContactType.CHAT,
        typeRawValue = contact.type,
        flags = ContactFlags(contact.flags),
        outPathLength = outPathLength,
        outPath = outPath,
        advertisedName = contact.name,
        lastAdvertisement = Instant.ofEpochSecond(contact.lastAdvert.toLong()),
        latitude = lat,
        longitude = lon,
        lastModified = Instant.ofEpochSecond(contact.lastModified.toLong()),
    )
}

/**
 * Resolves the encoded out-path for a contact, rejecting malformed routing data instead of
 * silently downgrading a routed contact to direct.
 */
private fun resolveOutPath(contact: MeshCoreNodeConfig.ContactConfig): Pair<ByteArray, UByte> {
    val pathHex = contact.outPath ?: return ByteArray(0) to PacketBuilder.FLOOD_PATH_SENTINEL
    if (pathHex.isEmpty()) return ByteArray(0) to 0u.toUByte()
    // decodeHex() silently drops non-hex characters, so a routed path like "zzz" would parse to
    // empty and masquerade as direct. Require contiguous, even-length hex up front.
    val pathData = if (pathHex.length % 2 == 0 && pathHex.isAllHexDigits()) pathHex.decodeHex() else null
    if (pathData == null || pathData.isEmpty()) throw NodeConfigServiceError.InvalidOutPath(contact.name)
    val mode = contact.pathHashMode ?: 0u.toUByte()
    if (mode.toInt() > PathEncoding.MAX_PATH_HASH_MODE) throw NodeConfigServiceError.InvalidPathHashMode(contact.name, mode)
    val hashSize = mode.toInt() + 1
    // Reject paths that would silently truncate (non-multiple length, hop count past the 6-bit
    // field) or exceed the firmware out-path buffer (firmware isValidPathLen).
    if (pathData.size % hashSize != 0 || pathData.size / hashSize > PathEncoding.MAX_HOP_COUNT || pathData.size > PathEncoding.MAX_PATH_BYTES) {
        throw NodeConfigServiceError.InvalidOutPath(contact.name)
    }
    val hopCount = pathData.size / hashSize
    return pathData to encodePathLen(hashSize, hopCount)
}

/** Maximum usable bytes for names (firmware `char[32]` minus null terminator). Kept local, matching this codebase's per-file convention (see [ChannelService]/`SettingsService`). */
private const val MAX_USABLE_NAME_BYTES = 31
