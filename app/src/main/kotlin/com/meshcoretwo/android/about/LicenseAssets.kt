// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.about

/**
 * Asset paths of the license texts that `app/build.gradle.kts`'s `GenerateLicenseAssets` task copies
 * into the APK from the repository root, so they're readable offline (PLAN.md Л1). The repository
 * files stay the single source of truth; `LicenseAssetsTest` checks the copy.
 */
object LicenseAssets {
    const val APP_LICENSE = "licenses/LICENSE"
    const val THIRD_PARTY_NOTICES = "licenses/THIRD_PARTY_NOTICES.md"
    const val LICENSE_TEXTS_DIRECTORY = "licenses/LICENSES"

    fun isMarkdown(assetPath: String): Boolean = assetPath.endsWith(".md")

    /** The file name without extension, e.g. `licenses/LICENSES/MapLibre-Native-core.md` → `MapLibre-Native-core`. */
    fun title(assetPath: String): String = assetPath.substringAfterLast('/').substringBeforeLast('.')
}

/** External links shown under Settings › About. */
object AppLinks {
    /**
     * Root of this port's public repository, e.g. `https://codeberg.org/<owner>/<repo>`. `null` until
     * the repository is published (PLAN.md Л2): until then the About screen hides "Source code" and
     * "Report a problem". Issues are opened at `<root>/issues`, which works for GitHub and Codeberg.
     */
    val SOURCE_REPOSITORY_URL: String? = "https://github.com/wirrja/meshcoretwo"

    const val MESHCORE_WEBSITE = "https://meshcore.io"
    const val MESHCORE_ONLINE_MAP = "https://map.meshcore.io/"
    const val OPENSTREETMAP_COPYRIGHT = "https://www.openstreetmap.org/copyright"
    const val COPERNICUS_DEM =
        "https://dataspace.copernicus.eu/explore-data/data-collections/copernicus-contributing-missions/collections-description/COP-DEM"
}
