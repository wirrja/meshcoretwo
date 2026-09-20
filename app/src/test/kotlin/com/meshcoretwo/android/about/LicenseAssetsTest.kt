// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.about

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the license files against `THIRD_PARTY_NOTICES.md` and against the assets that
 * `GenerateLicenseAssets` (`app/build.gradle.kts`) copies into the APK. Gradle passes that task's
 * output as the `licenseAssetsDir` system property and runs the task first.
 */
class LicenseAssetsTest {
    /** Gradle runs `app`'s unit tests with the module directory as the working directory. */
    private val repositoryRoot = File("..").canonicalFile

    private val assetsDirectory: File by lazy {
        File(checkNotNull(System.getProperty("licenseAssetsDir")) { "licenseAssetsDir is not set; run the tests through Gradle" })
    }

    private val notices: String by lazy { File(repositoryRoot, "THIRD_PARTY_NOTICES.md").readText() }

    /** Relative link targets in the notices, e.g. `LICENSES/Apache-2.0.txt`, `LICENSE`, `README.md`. */
    private val relativeLinkTargets: List<String> by lazy {
        Regex("""]\(([^)]+)\)""").findAll(notices)
            .map { it.groupValues[1] }
            .filterNot { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
            .toList()
    }

    @Test
    fun `every file linked from the notices exists in the repository`() {
        assertTrue(relativeLinkTargets.isNotEmpty())
        relativeLinkTargets.forEach { target ->
            assertTrue("THIRD_PARTY_NOTICES.md links to missing $target", File(repositoryRoot, target).exists())
        }
    }

    @Test
    fun `every file in LICENSES is linked from the notices`() {
        val files = File(repositoryRoot, "LICENSES").listFiles().orEmpty().map { "LICENSES/${it.name}" }
        assertTrue(files.isNotEmpty())
        files.forEach { file ->
            assertTrue("$file is not referenced from THIRD_PARTY_NOTICES.md", file in relativeLinkTargets)
        }
    }

    @Test
    fun `assets carry the app license and the notices verbatim`() {
        assertEquals(File(repositoryRoot, "LICENSE").readText(), File(assetsDirectory, LicenseAssets.APP_LICENSE).readText())
        assertEquals(notices, File(assetsDirectory, LicenseAssets.THIRD_PARTY_NOTICES).readText())
    }

    @Test
    fun `assets carry every license text linked from the notices`() {
        relativeLinkTargets.filter { it.startsWith("LICENSES/") }.forEach { target ->
            val asset = File(assetsDirectory, "${LicenseAssets.LICENSE_TEXTS_DIRECTORY}/${target.removePrefix("LICENSES/")}")
            assertTrue("$target is missing from the generated assets", asset.isFile)
        }
    }

    @Test
    fun `every bundled document parses into displayable blocks`() {
        val licenseTexts = File(assetsDirectory, LicenseAssets.LICENSE_TEXTS_DIRECTORY).listFiles().orEmpty()
            .map { "${LicenseAssets.LICENSE_TEXTS_DIRECTORY}/${it.name}" }
        (listOf(LicenseAssets.APP_LICENSE, LicenseAssets.THIRD_PARTY_NOTICES) + licenseTexts).forEach { path ->
            val blocks = LicenseDocument.parse(File(assetsDirectory, path).readText(), LicenseAssets.isMarkdown(path))
            assertTrue("$path produced no blocks", blocks.isNotEmpty())
        }
    }
}
