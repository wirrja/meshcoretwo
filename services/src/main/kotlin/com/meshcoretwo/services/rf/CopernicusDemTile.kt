// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.rf

import java.util.zip.DataFormatException
import java.util.zip.Inflater
import kotlin.math.ceil
import kotlin.math.floor

/**
 * One decoded 1°×1° tile of the Copernicus DEM (GLO-90), distributed as a Cloud Optimized GeoTIFF:
 * a little-endian classic TIFF, 32-bit float samples, DEFLATE compression with the floating-point
 * predictor (TIFF Technical Note 3, `PREDICTOR=3`) and a tile size that is at least the image size,
 * so a whole tile is one block. Only the full-resolution image (the first IFD) is read; the
 * overview IFDs that follow are ignored. No third-party TIFF/GDAL library: the format subset the
 * dataset uses is small enough to decode with `java.util.zip.Inflater`.
 *
 * Georeferencing: the dataset uses raster type *PixelIsPoint* — the tile name's corner is the
 * centre of the top-left column/bottom-left row, see the bucket's readme — so pixel centres sit at
 * integer (column, row) positions relative to the tie point.
 */
class CopernicusDemTile private constructor(
    private val width: Int,
    private val height: Int,
    private val originLongitude: Double,
    private val originLatitude: Double,
    private val pixelWidthDegrees: Double,
    private val pixelHeightDegrees: Double,
    private val samples: FloatArray,
) {
    /**
     * Bilinearly interpolated elevation in meters at [latitude]/[longitude]. Positions past the
     * tile's last row/column (the neighbouring tile owns them) are clamped to the edge sample —
     * at most one pixel (~90 m) of position error.
     */
    fun elevationAt(latitude: Double, longitude: Double): Double {
        val column = ((longitude - originLongitude) / pixelWidthDegrees).coerceIn(0.0, (width - 1).toDouble())
        val row = ((originLatitude - latitude) / pixelHeightDegrees).coerceIn(0.0, (height - 1).toDouble())
        val column0 = floor(column).toInt()
        val row0 = floor(row).toInt()
        val column1 = minOf(column0 + 1, width - 1)
        val row1 = minOf(row0 + 1, height - 1)
        val fx = column - column0
        val fy = row - row0
        val top = sample(column0, row0) * (1 - fx) + sample(column1, row0) * fx
        val bottom = sample(column0, row1) * (1 - fx) + sample(column1, row1) * fx
        return top * (1 - fy) + bottom * fy
    }

    private fun sample(column: Int, row: Int): Double = samples[row * width + column].toDouble()

    companion object {
        /** @throws ElevationServiceError.InvalidResponse if [bytes] isn't a tile in the layout described above. */
        fun parse(bytes: ByteArray): CopernicusDemTile = try {
            decode(bytes)
        } catch (error: ElevationServiceError) {
            throw error
        } catch (error: Exception) {
            // Truncated or corrupt downloads surface as index/format errors deep in the decoder.
            throw ElevationServiceError.InvalidResponse
        }

        private const val TAG_WIDTH = 256
        private const val TAG_HEIGHT = 257
        private const val TAG_BITS_PER_SAMPLE = 258
        private const val TAG_COMPRESSION = 259
        private const val TAG_PREDICTOR = 317
        private const val TAG_TILE_WIDTH = 322
        private const val TAG_TILE_HEIGHT = 323
        private const val TAG_TILE_OFFSETS = 324
        private const val TAG_TILE_BYTE_COUNTS = 325
        private const val TAG_SAMPLE_FORMAT = 339
        private const val TAG_PIXEL_SCALE = 33550
        private const val TAG_TIE_POINT = 33922

        private const val COMPRESSION_DEFLATE = 8
        private const val COMPRESSION_DEFLATE_ADOBE = 32946
        private const val PREDICTOR_FLOATING_POINT = 3
        private const val SAMPLE_FORMAT_FLOAT = 3
        private const val TYPE_SHORT = 3
        private const val TYPE_LONG = 4
        private const val TYPE_DOUBLE = 12

        private fun decode(bytes: ByteArray): CopernicusDemTile {
            // "II" + 42: classic little-endian TIFF. BigTIFF and big-endian files aren't used by the dataset.
            if (bytes.size < 8 || bytes[0] != 'I'.code.toByte() || bytes[1] != 'I'.code.toByte() || u16(bytes, 2) != 42) {
                throw ElevationServiceError.InvalidResponse
            }
            val ifd = u32(bytes, 4).toInt()
            val entryCount = u16(bytes, ifd)
            val tags = HashMap<Int, DoubleArray>()
            for (index in 0 until entryCount) {
                val entry = ifd + 2 + index * 12
                val tag = u16(bytes, entry)
                tags[tag] = readValues(bytes, entry) ?: continue
            }

            fun one(tag: Int): Int? = tags[tag]?.firstOrNull()?.toInt()
            val width = one(TAG_WIDTH) ?: throw ElevationServiceError.InvalidResponse
            val height = one(TAG_HEIGHT) ?: throw ElevationServiceError.InvalidResponse
            val tileWidth = one(TAG_TILE_WIDTH) ?: throw ElevationServiceError.InvalidResponse
            val tileHeight = one(TAG_TILE_HEIGHT) ?: throw ElevationServiceError.InvalidResponse
            val compression = one(TAG_COMPRESSION)
            if (one(TAG_BITS_PER_SAMPLE) != 32 || one(TAG_SAMPLE_FORMAT) != SAMPLE_FORMAT_FLOAT ||
                (compression != COMPRESSION_DEFLATE && compression != COMPRESSION_DEFLATE_ADOBE) ||
                one(TAG_PREDICTOR) != PREDICTOR_FLOATING_POINT
            ) {
                throw ElevationServiceError.InvalidResponse
            }
            val offsets = tags[TAG_TILE_OFFSETS] ?: throw ElevationServiceError.InvalidResponse
            val counts = tags[TAG_TILE_BYTE_COUNTS] ?: throw ElevationServiceError.InvalidResponse
            val scale = tags[TAG_PIXEL_SCALE]?.takeIf { it.size >= 2 } ?: throw ElevationServiceError.InvalidResponse
            val tie = tags[TAG_TIE_POINT]?.takeIf { it.size >= 6 } ?: throw ElevationServiceError.InvalidResponse

            val tilesAcross = ceil(width.toDouble() / tileWidth).toInt()
            val tilesDown = ceil(height.toDouble() / tileHeight).toInt()
            if (offsets.size < tilesAcross * tilesDown || counts.size < tilesAcross * tilesDown) {
                throw ElevationServiceError.InvalidResponse
            }

            val samples = FloatArray(width * height)
            for (tileRow in 0 until tilesDown) {
                for (tileColumn in 0 until tilesAcross) {
                    val tileIndex = tileRow * tilesAcross + tileColumn
                    val tile = decodeTile(bytes, offsets[tileIndex].toInt(), counts[tileIndex].toInt(), tileWidth, tileHeight)
                    val columns = minOf(tileWidth, width - tileColumn * tileWidth)
                    val rows = minOf(tileHeight, height - tileRow * tileHeight)
                    for (row in 0 until rows) {
                        System.arraycopy(
                            tile, row * tileWidth,
                            samples, (tileRow * tileHeight + row) * width + tileColumn * tileWidth,
                            columns,
                        )
                    }
                }
            }

            // Tie point is (I, J, K, X, Y, Z): raster (I, J) sits at map (X, Y).
            return CopernicusDemTile(
                width = width,
                height = height,
                originLongitude = tie[3] - tie[0] * scale[0],
                originLatitude = tie[4] + tie[1] * scale[1],
                pixelWidthDegrees = scale[0],
                pixelHeightDegrees = scale[1],
                samples = samples,
            )
        }

        /** Inflates one block and undoes the floating-point predictor. Returns `tileWidth * tileHeight` samples. */
        private fun decodeTile(bytes: ByteArray, offset: Int, length: Int, tileWidth: Int, tileHeight: Int): FloatArray {
            val expected = tileWidth * tileHeight * 4
            val raw = ByteArray(expected)
            val inflater = Inflater()
            try {
                inflater.setInput(bytes, offset, length)
                var filled = 0
                while (filled < expected && !inflater.finished()) {
                    val read = try {
                        inflater.inflate(raw, filled, expected - filled)
                    } catch (error: DataFormatException) {
                        throw ElevationServiceError.InvalidResponse
                    }
                    if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    filled += read
                }
                if (filled != expected) throw ElevationServiceError.InvalidResponse
            } finally {
                inflater.end()
            }

            val out = FloatArray(tileWidth * tileHeight)
            val rowBytes = tileWidth * 4
            for (row in 0 until tileHeight) {
                val base = row * rowBytes
                // Byte-wise horizontal differencing over the row, then the rows are stored as four
                // byte planes, most significant byte first.
                for (index in 1 until rowBytes) {
                    raw[base + index] = (raw[base + index] + raw[base + index - 1]).toByte()
                }
                for (column in 0 until tileWidth) {
                    val bits = (raw[base + column].toInt() and 0xFF shl 24) or
                        (raw[base + tileWidth + column].toInt() and 0xFF shl 16) or
                        (raw[base + 2 * tileWidth + column].toInt() and 0xFF shl 8) or
                        (raw[base + 3 * tileWidth + column].toInt() and 0xFF)
                    out[row * tileWidth + column] = Float.fromBits(bits)
                }
            }
            return out
        }

        /** Reads an IFD entry's values as doubles; `null` for a type the decoder doesn't need. */
        private fun readValues(bytes: ByteArray, entry: Int): DoubleArray? {
            val type = u16(bytes, entry + 2)
            val count = u32(bytes, entry + 4).toInt()
            val size = when (type) {
                TYPE_SHORT -> 2
                TYPE_LONG -> 4
                TYPE_DOUBLE -> 8
                else -> return null
            }
            // Values that fit in four bytes live in the entry itself, larger ones at an offset.
            val start = if (size * count <= 4) entry + 8 else u32(bytes, entry + 8).toInt()
            return DoubleArray(count) { index ->
                val at = start + index * size
                when (type) {
                    TYPE_SHORT -> u16(bytes, at).toDouble()
                    TYPE_LONG -> u32(bytes, at).toDouble()
                    else -> java.lang.Double.longBitsToDouble(u32(bytes, at) or (u32(bytes, at + 4) shl 32))
                }
            }
        }

        private fun u16(bytes: ByteArray, at: Int): Int = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

        private fun u32(bytes: ByteArray, at: Int): Long = (u16(bytes, at).toLong()) or (u16(bytes, at + 2).toLong() shl 16)
    }
}
