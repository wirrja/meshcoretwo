// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

/** The result of [ChannelSessionOps.getChannels]: channels that answered and indices that did not. */
data class ChannelsFetchResult(val received: List<ChannelInfo>, val missing: List<UByte>)

/** Session operations for reading and configuring channel slots. */
interface ChannelSessionOps {
    /**
     * Retrieves information about a channel.
     *
     * @param index The channel index (0-7).
     * @return A [ChannelInfo] including the name and secret.
     * @throws MeshCoreError if the channel query fails.
     */
    suspend fun getChannel(index: UByte): ChannelInfo

    /**
     * Reads multiple channels in a single pipelined exchange where the transport supports it.
     *
     * @param indices The channel indexes to read.
     * @return `received` channels that answered, and the `missing` indexes whose request was
     *   dropped and must be reconciled with acknowledged reads.
     * @throws MeshCoreError on a hard send failure.
     */
    suspend fun getChannels(indices: List<UByte>): ChannelsFetchResult

    /**
     * Configures a channel's settings.
     *
     * @param index The channel index (0-7).
     * @param name The channel name.
     * @param secret The 16-byte channel secret.
     * @throws MeshCoreError if the channel configuration fails.
     */
    suspend fun setChannel(index: UByte, name: String, secret: ByteArray)
}
