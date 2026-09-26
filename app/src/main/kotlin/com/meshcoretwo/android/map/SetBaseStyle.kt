// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.map

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

private val styleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/** Used when a custom vector style has no literal `text-font` to copy. */
private val FALLBACK_LABEL_FONT = arrayOf("Noto Sans Regular")

/**
 * Loads the basemap chosen in Settings → Maps → Map source ([MapTiles.resolve]) and then calls
 * [onLoaded] with the style and the font stack overlay labels must use on it. Every map screen
 * goes through here instead of a hard-coded style URL.
 *
 * Runs synchronously once the choice is cached. Only the first map of a process in
 * [MapTileProviderId.AUTO] mode waits for the network probe.
 */
fun MapLibreMap.setBaseStyle(onLoaded: (style: Style, labelFont: Array<String>) -> Unit = { _, _ -> }) {
    styleScope.launch {
        val base = MapTiles.resolve()
        // The map may have been destroyed while the probe ran; MapLibre then logs and ignores
        // the call, but guard against an exception from the native side all the same.
        runCatching {
            setStyle(base.styleBuilder()) { style ->
                val font = base.labelFont ?: MapBaseStyle.firstTextFont(style.json) ?: FALLBACK_LABEL_FONT
                onLoaded(style, font)
            }
        }
    }
}
