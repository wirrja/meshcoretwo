// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers [NodeCLIViewModel]'s pure command-parsing helpers. */
class NodeCLIViewModelCompanionTest {
    @Test
    fun `isRebootCommand matches reboot and reboot now, case-insensitively`() {
        assertTrue(NodeCLIViewModel.isRebootCommand("reboot"))
        assertTrue(NodeCLIViewModel.isRebootCommand("Reboot"))
        assertTrue(NodeCLIViewModel.isRebootCommand("reboot now"))
        assertTrue(NodeCLIViewModel.isRebootCommand("  REBOOT NOW  "))
    }

    @Test
    fun `isRebootCommand rejects other commands`() {
        assertFalse(NodeCLIViewModel.isRebootCommand("reboot later"))
        assertFalse(NodeCLIViewModel.isRebootCommand("help"))
        assertFalse(NodeCLIViewModel.isRebootCommand(""))
    }

    @Test
    fun `splitCommand lowercases only the command word`() {
        val (cmd, args) = NodeCLIViewModel.splitCommand("SET Name MyNode")
        assertEquals("set", cmd)
        assertEquals("Name MyNode", args)
    }

    @Test
    fun `splitCommand returns empty args when there is only a command word`() {
        val (cmd, args) = NodeCLIViewModel.splitCommand("help")
        assertEquals("help", cmd)
        assertEquals("", args)
    }

    @Test
    fun `applyCompletion replaces a single-word command entirely`() {
        assertEquals("help ", NodeCLIViewModel.applyCompletion("h", "help"))
        assertEquals("help ", NodeCLIViewModel.applyCompletion("", "help"))
    }

    @Test
    fun `applyCompletion replaces only the last token of a multi-word command`() {
        assertEquals("get name ", NodeCLIViewModel.applyCompletion("get na", "name"))
        assertEquals("set loop.detect strict ", NodeCLIViewModel.applyCompletion("set loop.detect st", "strict"))
    }

    @Test
    fun `responseBlock strips the prompt prefix from a command line`() {
        val command = CLIOutputLine(text = "@Node> get name", type = CLIOutputType.COMMAND)
        assertEquals("get name", NodeCLIViewModel.responseBlock(listOf(command), command))
    }

    @Test
    fun `responseBlock returns a single response line stripped of its echoed prefix`() {
        val command = CLIOutputLine(text = "@Node> get name", type = CLIOutputType.COMMAND)
        val response = CLIOutputLine(text = "> Repeater1", type = CLIOutputType.RESPONSE)
        assertEquals("Repeater1", NodeCLIViewModel.responseBlock(listOf(command, response), response))
    }

    @Test
    fun `responseBlock joins a multi-line block bounded by surrounding commands`() {
        val lines = listOf(
            CLIOutputLine(text = "@Node> get name", type = CLIOutputType.COMMAND),
            CLIOutputLine(text = "> line1", type = CLIOutputType.RESPONSE),
            CLIOutputLine(text = "> line2", type = CLIOutputType.RESPONSE),
            CLIOutputLine(text = "@Node> help", type = CLIOutputType.COMMAND),
        )
        assertEquals("line1\nline2", NodeCLIViewModel.responseBlock(lines, lines[1]))
        assertEquals("line1\nline2", NodeCLIViewModel.responseBlock(lines, lines[2]))
    }

    @Test
    fun `responseBlock falls back to the bare line text when it is no longer present`() {
        val line = CLIOutputLine(text = "orphaned", type = CLIOutputType.RESPONSE)
        assertEquals("orphaned", NodeCLIViewModel.responseBlock(emptyList(), line))
    }
}
