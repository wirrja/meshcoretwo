// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.map

import androidx.annotation.StringRes
import com.meshcoretwo.android.R
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.maps.Style

/**
 * A basemap the app can draw under its own markers. Not a port: iOS has one fixed style
 * (`MapTileURLs.openFreeMapLiberty`). Several exist here because in some networks a single host
 * does not load. Since June 2025 Russian ISPs cut connections to Cloudflare, Hetzner, OVH and
 * others after the first ~16 KB, and `tiles.openfreemap.org` sits behind Cloudflare. That is the
 * "timeout on most tiles, a few 200s" pattern from PLAN.md Phase 33.1.
 *
 * [AUTO] is not a provider itself. [MapTiles] resolves it to the first built-in provider that
 * [MapTileProbe] reaches from the current network.
 */
enum class MapTileProviderId(@StringRes val labelRes: Int, @StringRes val descriptionRes: Int) {
    AUTO(R.string.map_source_auto, R.string.map_source_auto_desc),
    OPENFREEMAP(R.string.map_source_openfreemap, R.string.map_source_openfreemap_desc),
    VERSATILES(R.string.map_source_versatiles, R.string.map_source_versatiles_desc),
    OSM(R.string.map_source_osm, R.string.map_source_osm_desc),
    CUSTOM(R.string.map_source_custom, R.string.map_source_custom_desc),
    ;

    companion object {
        /** The order [AUTO] tries built-in providers in; the first reachable one wins. */
        val autoCandidates = listOf(OPENFREEMAP, VERSATILES, OSM)

        fun fromName(name: String?): MapTileProviderId? = entries.firstOrNull { it.name == name }
    }
}

/**
 * A concrete basemap: how to build its MapLibre style, which font stack overlay labels must use,
 * and whether offline packs may be downloaded from it.
 *
 * [labelFont] must be a font stack the style's glyph server hosts. A stack the server lacks 404s,
 * and unloaded glyphs stall every layer of the same GeoJSON source, so markers never draw. `null`
 * means unknown (a custom vector style): [setBaseStyle] then takes the first `text-font` the
 * loaded style itself uses.
 */
data class MapBaseStyle(
    val provider: MapTileProviderId,
    /** A MapLibre style JSON URL; `null` for a raster source, see [rasterTileUrl]. */
    val styleUrl: String?,
    /** An XYZ raster template, wrapped into a one-layer style by [styleBuilder]. */
    val rasterTileUrl: String?,
    val rasterAttribution: String? = null,
    val labelFont: Array<String>?,
    /** Style URL offline packs are created against; `null` when the provider forbids bulk download. */
    val offlineStyleUrl: String?,
    /** A resource on the same host that is larger than 16 KB, fetched by [MapTileProbe]. */
    val probeUrl: String,
) {
    fun styleBuilder(): Style.Builder = when (rasterTileUrl) {
        null -> Style.Builder().fromUri(checkNotNull(styleUrl))
        else -> Style.Builder().fromJson(rasterStyleJson(rasterTileUrl, maxZoom = 19, attribution = rasterAttribution))
    }

    // Arrays compare by identity in a generated equals; the font follows from the source anyway.
    override fun equals(other: Any?): Boolean =
        other is MapBaseStyle && provider == other.provider && styleUrl == other.styleUrl && rasterTileUrl == other.rasterTileUrl

    override fun hashCode(): Int = 31 * provider.hashCode() + (styleUrl ?: rasterTileUrl).hashCode()

    companion object {
        /** Font stack of the glyphs bundled in `assets/glyphs/` (Noto Sans, OFL-1.1). */
        private val BUNDLED_FONT = arrayOf("noto_sans_regular")
        private const val BUNDLED_GLYPHS = "asset://glyphs/{fontstack}/{range}.pbf"

        const val OPENFREEMAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        const val VERSATILES_STYLE_URL = "https://tiles.versatiles.org/assets/styles/colorful/style.json"
        private const val OSM_TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"

        /** A dense z12 tile over central Moscow, reliably larger than the 16 KB cut-off. */
        private const val OSM_PROBE_URL = "https://tile.openstreetmap.org/12/2476/1280.png"

        val openFreeMap = MapBaseStyle(
            provider = MapTileProviderId.OPENFREEMAP,
            styleUrl = OPENFREEMAP_STYLE_URL,
            rasterTileUrl = null,
            labelFont = arrayOf("Noto Sans Regular"),
            offlineStyleUrl = OPENFREEMAP_STYLE_URL,
            probeUrl = OPENFREEMAP_STYLE_URL,
        )

        val versaTiles = MapBaseStyle(
            provider = MapTileProviderId.VERSATILES,
            styleUrl = VERSATILES_STYLE_URL,
            rasterTileUrl = null,
            labelFont = arrayOf("noto_sans_regular"),
            offlineStyleUrl = VERSATILES_STYLE_URL,
            probeUrl = VERSATILES_STYLE_URL,
        )

        /**
         * The OpenStreetMap Foundation's raster tiles. Their tile usage policy forbids offline
         * bulk download, so [offlineStyleUrl] is `null`. MapLibre's default User-Agent already
         * names this app's package, as the policy requires.
         */
        val openStreetMap = MapBaseStyle(
            provider = MapTileProviderId.OSM,
            styleUrl = null,
            rasterTileUrl = OSM_TILE_URL,
            rasterAttribution = OSM_ATTRIBUTION,
            labelFont = BUNDLED_FONT,
            offlineStyleUrl = null,
            probeUrl = OSM_PROBE_URL,
        )

        private const val OSM_ATTRIBUTION =
            "<a href=\"https://www.openstreetmap.org/copyright\">&copy; OpenStreetMap contributors</a>"

        fun builtIn(id: MapTileProviderId): MapBaseStyle? = when (id) {
            MapTileProviderId.OPENFREEMAP -> openFreeMap
            MapTileProviderId.VERSATILES -> versaTiles
            MapTileProviderId.OSM -> openStreetMap
            MapTileProviderId.AUTO, MapTileProviderId.CUSTOM -> null
        }

        /**
         * A user-supplied source: a raster XYZ template when [url] contains `{z}`, otherwise a
         * MapLibre style JSON URL. `null` for a blank or non-http(s) URL.
         */
        fun custom(url: String): MapBaseStyle? {
            val trimmed = url.trim()
            if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) return null
            return if (trimmed.contains("{z}")) {
                MapBaseStyle(
                    provider = MapTileProviderId.CUSTOM,
                    styleUrl = null,
                    rasterTileUrl = trimmed,
                    labelFont = BUNDLED_FONT,
                    offlineStyleUrl = null,
                    probeUrl = trimmed.replace("{z}", "12").replace("{x}", "2476").replace("{y}", "1280"),
                )
            } else {
                MapBaseStyle(
                    provider = MapTileProviderId.CUSTOM,
                    styleUrl = trimmed,
                    rasterTileUrl = null,
                    labelFont = null,
                    offlineStyleUrl = trimmed,
                    probeUrl = trimmed,
                )
            }
        }

        /** A one-layer raster style with the bundled glyphs, so overlay labels still render. */
        internal fun rasterStyleJson(tileUrl: String, maxZoom: Int, attribution: String?): String {
            val source = JSONObject()
                .put("type", "raster")
                .put("tiles", JSONArray().put(tileUrl))
                .put("tileSize", 256)
                .put("maxzoom", maxZoom)
            attribution?.let { source.put("attribution", it) }
            return JSONObject()
                .put("version", 8)
                .put("glyphs", BUNDLED_GLYPHS)
                .put("sources", JSONObject().put("raster", source))
                .put(
                    "layers",
                    JSONArray()
                        .put(JSONObject().put("id", "background").put("type", "background").put("paint", JSONObject().put("background-color", "#F2EFE9")))
                        .put(JSONObject().put("id", "raster").put("type", "raster").put("source", "raster")),
                )
                .toString()
        }

        /** The first literal `text-font` stack a style's symbol layers use, or `null`. */
        internal fun firstTextFont(styleJson: String): Array<String>? = runCatching {
            val layers = JSONObject(styleJson).optJSONArray("layers") ?: return null
            for (i in 0 until layers.length()) {
                val fonts = layers.optJSONObject(i)?.optJSONObject("layout")?.optJSONArray("text-font") ?: continue
                // A literal stack is an array of strings; expressions start with an operator array.
                if (fonts.length() > 0 && (0 until fonts.length()).all { fonts.opt(it) is String }) {
                    return Array(fonts.length()) { fonts.getString(it) }
                }
            }
            null
        }.getOrNull()
    }
}
