// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.remotenode

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** Ported from `RemoteCLICommandRewriterTests.swift`. */
class RemoteCLICommandRewriterTest {
    private val now = Instant.ofEpochSecond(1_786_722_487)
    private val expected = "time 1786722487"

    @Test
    fun `clock sync becomes time with the host epoch`() {
        assertEquals(expected, RemoteCLICommandRewriter.rewrite("clock sync", now))
    }

    @Test
    fun `clock sync match is case and whitespace insensitive`() {
        for (command in listOf("CLOCK SYNC", "  clock   sync  ", "Clock Sync")) {
            assertEquals(expected, RemoteCLICommandRewriter.rewrite(command, now))
        }
    }

    @Test
    fun `other commands are left unchanged`() {
        for (command in listOf("clock", "time 123", "clock sync extra", "sync_time", "st")) {
            assertEquals(command, RemoteCLICommandRewriter.rewrite(command, now))
        }
    }

    @Test
    fun `pre epoch dates saturate to zero`() {
        val preEpoch = Instant.ofEpochSecond(-1000)
        assertEquals("time 0", RemoteCLICommandRewriter.rewrite("clock sync", preEpoch))
    }
}
