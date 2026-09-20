// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

/**
 * Command/argument completion for [NodeCLIScreen]'s suggestion chips. Ported from
 * `CLICompletionEngine.swift`, trimmed to the remote-node-only subset [NodeCLIViewModel] actually
 * needs: Swift's `isLocal` parameter always arrives `false` and `includeSessionCommands` always
 * `false` for the node CLI (a repeater/room admin session is never the app's own local serial CLI,
 * which the firmware-facing `session`/`login`/`logout` commands manage and which this port hasn't
 * built yet — see class doc there). Both the `isLocal: true` command vocabulary (`floodadv`,
 * local `get`/`set` keys, custom-var dump/learn) and the `session`/`login` completion branches are
 * therefore dropped rather than carried as dead parameters; port them back in when a local CLI Tool
 * screen exists to call this engine too.
 */
object CLICompletionEngine {
    private val builtInCommands = listOf("help", "clear")

    /** Per MeshCore CLI Reference — commands available via a remote (repeater/room admin) session. */
    private val repeaterCommands = listOf(
        "ver", "board", "clock", "clkreboot",
        "neighbors", "get", "set", "sensor", "password",
        "log", "reboot", "advert", "advert.zerohop", "setperm", "tempradio", "neighbor.remove",
        "region", "gps", "powersaving", "clear", "discover.neighbors",
        "start",
    )

    private val sensorSubcommands = listOf("get", "list", "set")
    private val logSubcommands = listOf("start", "stop", "erase")
    private val clearSubcommands = listOf("stats")
    private val clockSubcommands = listOf("sync")

    /** Per MeshCore CLI Reference — region subcommands. */
    private val regionSubcommands = listOf("load", "get", "put", "remove", "allowf", "denyf", "home", "default", "save", "list")

    /** Per MeshCore CLI Reference — gps subcommands. */
    private val gpsSubcommands = listOf("on", "off", "sync", "setloc", "advert")
    private val gpsAdvertValues = listOf("none", "share", "prefs")
    private val startSubcommands = listOf("ota")
    private val regionListValues = listOf("allowed", "denied")
    private val onOffValues = listOf("on", "off")
    private val multiAcksValues = listOf("0", "1")
    private val bridgeSourceValues = listOf("tx", "rx")

    /** Per MeshCore CLI Reference — all get/set parameters. */
    private val getSetParams = listOf(
        "acl", "name", "radio", "tx", "repeat", "lat", "lon",
        "af", "dutycycle", "flood.max", "flood.max.advert", "flood.max.unscoped",
        "int.thresh", "agc.reset.interval",
        "multi.acks", "advert.interval", "flood.advert.interval",
        "guest.password", "allow.read.only",
        "rxdelay", "txdelay", "direct.txdelay",
        "bridge.enabled", "bridge.delay", "bridge.source",
        "bridge.baud", "bridge.secret", "bridge.type",
        "adc.multiplier", "public.key", "prv.key", "role", "freq",
        "path.hash.mode", "loop.detect", "bootloader.ver",
        "owner.info", "radio.rxgain", "bridge.channel",
        "pwrmgt.support", "pwrmgt.source", "pwrmgt.bootreason", "pwrmgt.bootmv",
    )

    // Serial-only params excluded from remote session completions.
    private val serialOnlyGetParams = setOf("prv.key", "acl")
    private val serialOnlySetParams = setOf("freq")

    private val pathHashModeValues = listOf("0", "1", "2")
    private val loopDetectValues = listOf("off", "minimal", "moderate", "strict")

    fun completions(input: String): List<String> {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return availableCommands().sorted()

        val parts = trimmed.split(" ")
        val command = parts[0].lowercase()

        if (parts.size == 1 && !input.endsWith(" ")) {
            return availableCommands().filter { it.startsWith(command) }.sorted()
        }

        val argPrefix = if (parts.size > 1) parts[1].lowercase() else ""
        val endsWithSpace = input.endsWith(" ")
        return completeArguments(command, parts, argPrefix, endsWithSpace)
    }

    private fun completeArguments(command: String, parts: List<String>, prefix: String, endsWithSpace: Boolean): List<String> {
        val argPosition = if (endsWithSpace) parts.size else parts.size - 1

        return when (command) {
            "log", "powersaving", "clear", "clock", "start" -> {
                if (argPosition != 1) emptyList() else completeFirstArg(command, prefix)
            }
            "get" -> {
                if (argPosition != 1) return emptyList()
                getSetParams.filter { it !in serialOnlyGetParams }.filter { it.startsWith(prefix) }.sorted()
            }
            "set" -> {
                when {
                    argPosition == 1 -> getSetParams.filter { it !in serialOnlySetParams }.filter { it.startsWith(prefix) }.sorted()
                    argPosition == 2 && parts.size >= 2 -> completeSetValue(parts[1].lowercase(), if (parts.size > 2) parts[2].lowercase() else "")
                    else -> emptyList()
                }
            }
            "sensor" -> if (argPosition == 1) sensorSubcommands.filter { it.startsWith(prefix) }.sorted() else emptyList()
            "gps" -> completeGpsArgs(argPosition, parts, prefix)
            "region" -> completeRegionArgs(argPosition, parts, prefix)
            else -> emptyList()
        }
    }

    private fun completeFirstArg(command: String, prefix: String): List<String> = when (command) {
        "log" -> logSubcommands.filter { it.startsWith(prefix) }.sorted()
        "powersaving" -> onOffValues.filter { it.startsWith(prefix) }.sorted()
        "clear" -> clearSubcommands.filter { it.startsWith(prefix) }.sorted()
        "clock" -> clockSubcommands.filter { it.startsWith(prefix) }.sorted()
        "start" -> startSubcommands.filter { it.startsWith(prefix) }.sorted()
        else -> emptyList()
    }

    private fun availableCommands(): List<String> = builtInCommands + repeaterCommands

    private fun completeSetValue(param: String, prefix: String): List<String> = when (param) {
        "path.hash.mode" -> pathHashModeValues.filter { it.startsWith(prefix) }.sorted()
        "loop.detect" -> loopDetectValues.filter { it.startsWith(prefix) }.sorted()
        "repeat", "allow.read.only", "bridge.enabled", "radio.rxgain" -> onOffValues.filter { it.startsWith(prefix) }.sorted()
        "multi.acks" -> multiAcksValues.filter { it.startsWith(prefix) }.sorted()
        "bridge.source" -> bridgeSourceValues.filter { it.startsWith(prefix) }.sorted()
        else -> emptyList()
    }

    private fun completeRegionArgs(argPosition: Int, parts: List<String>, prefix: String): List<String> = when {
        argPosition == 1 -> regionSubcommands.filter { it.startsWith(prefix) }.sorted()
        argPosition == 2 && parts.size >= 2 && parts[1].lowercase() == "list" -> {
            val valuePrefix = if (parts.size > 2) parts[2].lowercase() else ""
            regionListValues.filter { it.startsWith(valuePrefix) }.sorted()
        }
        else -> emptyList()
    }

    private fun completeGpsArgs(argPosition: Int, parts: List<String>, prefix: String): List<String> = when {
        argPosition == 1 -> gpsSubcommands.filter { it.startsWith(prefix) }.sorted()
        argPosition == 2 && parts.size >= 2 && parts[1].lowercase() == "advert" -> {
            val valuePrefix = if (parts.size > 2) parts[2].lowercase() else ""
            gpsAdvertValues.filter { it.startsWith(valuePrefix) }.sorted()
        }
        else -> emptyList()
    }
}
