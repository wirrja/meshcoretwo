// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LoginSuccessParserTest {
    /** Builds the 13-byte payload as [LoginSuccessParser.parse] sees it (after PacketParser strips the 0x85 opcode). */
    private fun extendedPayload(
        isAdminByte: Byte,
        pubkeyPrefix: ByteArray = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte()),
        timestamp: UInt = 0u,
        aclPermissions: Byte,
        firmwareVersion: Byte = 2,
    ): ByteArray {
        var data = byteArrayOf(isAdminByte)
        data += pubkeyPrefix
        data += timestamp.toLittleEndianBytes()
        data += aclPermissions
        data += firmwareVersion
        return data
    }

    private fun extractLogin(event: MeshEvent): LoginInfo? =
        (event as? MeshEvent.LoginSuccess)?.info

    // MARK: - Official C++ admin encoding

    @Test
    fun `C++ admin login (byte0=1, ACL=0x03) parses as admin on both repeater and room-server`() {
        val payload = extendedPayload(isAdminByte = 1, aclPermissions = 0x03)
        val info = extractLogin(LoginSuccessParser.parse(payload))
        assertEquals(true, info?.isAdmin)
        assertEquals(0x02u.toUByte(), info?.permissions)
    }

    // MARK: - Official C++ repeater non-admin encodings

    @Test
    fun `C++ repeater guest login (byte0=0, ACL=0x00) parses as guest`() {
        val payload = extendedPayload(isAdminByte = 0, aclPermissions = 0x00)
        val info = extractLogin(LoginSuccessParser.parse(payload))
        assertEquals(false, info?.isAdmin)
        assertEquals(0x00u.toUByte(), info?.permissions)
    }

    @Test
    fun `C++ repeater read-write login (byte0=0, ACL=0x02) parses as read-write, not admin`() {
        val payload = extendedPayload(isAdminByte = 0, aclPermissions = 0x02)
        val info = extractLogin(LoginSuccessParser.parse(payload))
        assertEquals(false, info?.isAdmin)
        assertEquals(0x01u.toUByte(), info?.permissions)
    }

    // MARK: - Official C++ room-server non-admin encodings

    @Test
    fun `C++ room-server guest login (byte0=2, ACL=0x00) parses as non-admin guest`() {
        val payload = extendedPayload(isAdminByte = 2, aclPermissions = 0x00)
        val info = extractLogin(LoginSuccessParser.parse(payload))
        assertEquals(false, info?.isAdmin)
        assertEquals(0x00u.toUByte(), info?.permissions)
    }

    @Test
    fun `C++ room-server read-only login (byte0=0, ACL=0x01) parses as non-posting guest`() {
        val payload = extendedPayload(isAdminByte = 0, aclPermissions = 0x01)
        val info = extractLogin(LoginSuccessParser.parse(payload))
        assertEquals(false, info?.isAdmin)
        assertEquals(0x00u.toUByte(), info?.permissions)
    }

    // MARK: - pyMC encodings

    @Test
    fun `pyMC admin login (ACL=0x02) parses as admin`() {
        val payload = extendedPayload(isAdminByte = 1, aclPermissions = 0x02)
        val info = extractLogin(LoginSuccessParser.parse(payload))
        assertEquals(true, info?.isAdmin)
        assertEquals(0x02u.toUByte(), info?.permissions)
    }

    @Test
    fun `pyMC guest login (ACL=0x01) parses as non-admin guest`() {
        val payload = extendedPayload(isAdminByte = 0, aclPermissions = 0x01)
        val info = extractLogin(LoginSuccessParser.parse(payload))
        assertEquals(false, info?.isAdmin)
        assertEquals(0x00u.toUByte(), info?.permissions)
    }

    // MARK: - Legacy 7-byte path

    @Test
    fun `Legacy 7-byte payload with companion-radio hardcoded 0 parses as non-admin`() {
        val payload = byteArrayOf(0x00, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte())
        val info = extractLogin(LoginSuccessParser.parse(payload))
        assertEquals(false, info?.isAdmin)
        assertEquals(0x01u.toUByte(), info?.permissions)
    }

    // MARK: - Short-payload guard

    @Test
    fun `Payload shorter than 7 bytes returns zero LoginInfo fallback`() {
        val payload = byteArrayOf(0x01, 0x02)
        val info = extractLogin(LoginSuccessParser.parse(payload))
        assertEquals(false, info?.isAdmin)
        assertEquals(0u.toUByte(), info?.permissions)
        assertTrue(info?.publicKeyPrefix?.isEmpty() == true)
    }

    // MARK: - Server time

    @Test
    fun `Extended-format server timestamp parses as serverTime`() {
        val epoch = 1_784_300_000u
        val payload = extendedPayload(isAdminByte = 1, timestamp = epoch, aclPermissions = 0x03)
        val info = extractLogin(LoginSuccessParser.parse(payload))
        assertEquals(Instant.ofEpochSecond(epoch.toLong()), info?.serverTime)
    }

    @Test
    fun `Zero server timestamp yields null serverTime`() {
        val payload = extendedPayload(isAdminByte = 1, timestamp = 0u, aclPermissions = 0x03)
        val info = extractLogin(LoginSuccessParser.parse(payload))
        assertNull(info?.serverTime)
    }

    @Test
    fun `Legacy 7-byte payload yields null serverTime`() {
        val payload = byteArrayOf(0x00, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte())
        val info = extractLogin(LoginSuccessParser.parse(payload))
        assertNull(info?.serverTime)
    }

    // MARK: - Pubkey prefix propagation

    @Test
    fun `Extended-format pubkey prefix is extracted from bytes 1 until 7`() {
        val prefix = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
        val payload = extendedPayload(isAdminByte = 1, pubkeyPrefix = prefix, aclPermissions = 0x03)
        val info = extractLogin(LoginSuccessParser.parse(payload))
        assertArrayEquals(prefix, info?.publicKeyPrefix)
    }
}
