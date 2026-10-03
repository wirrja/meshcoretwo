// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.rf

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/** Round-trips Terrarium pixels through a real lossless WebP, the format the tile host serves. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class BitmapTerrariumDecoderTest {
    @Test
    fun `lossless webp decodes to exact terrarium elevations`() {
        val size = 4
        val elevations = floatArrayOf(
            0f, 146.5f, -432f, 5413.25f,
            1f, 2f, 3f, 4f,
            100f, 200f, 300f, 400f,
            -1f, -10f, -100f, 8848f,
        )
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        elevations.forEachIndexed { index, meters ->
            val encoded = ((meters + 32768f) * 256f).toInt()
            val argb = (0xFF shl 24) or (encoded and 0xFFFFFF)
            bitmap.setPixel(index % size, index / size, argb)
        }
        val webp = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, it) }.toByteArray()

        val tile = BitmapTerrariumDecoder(webp)

        assertEquals(size, tile.size)
        elevations.forEachIndexed { index, meters ->
            assertEquals(meters, tile.elevationAt(index % size, index / size), 0f)
        }
    }
}
