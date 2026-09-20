// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.about

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * PLAN.md's Л5 ("automated license-diff check"): fails the build the moment `:app`'s actual
 * `releaseRuntimeClasspath` gains or loses a module without a matching update to the checked-in
 * `runtime-modules.txt` — the manual "diff the dependency tree against THIRD_PARTY_NOTICES.md"
 * step of the pre-release checklist (PLAN.md's "Перед каждым релизом") becomes this test instead.
 * `app/build.gradle.kts`'s `generateRuntimeModuleList` task dumps the live graph; Gradle passes its
 * output file as the `runtimeModuleListFile` system property and runs that task first (see
 * `RuntimeModuleListFileArgument`) — wired only onto `testReleaseUnitTest`, matching
 * `THIRD_PARTY_NOTICES.md`'s own release-only scope, so [assumeTrue] skips this class entirely
 * under `testDebugUnitTest` (and under a bare IDE/`java` run with no Gradle-supplied property)
 * rather than failing for a variant it was never meant to check.
 *
 * This only catches drift in the *set of modules* — it does not verify `THIRD_PARTY_NOTICES.md`'s
 * prose rows describe each one accurately (that stays a human judgment call when this test sends
 * someone to update `runtime-modules.txt`).
 */
class RuntimeModuleListTest {
    /** Gradle runs `app`'s unit tests with the module directory as the working directory. */
    private val repositoryRoot = File("..").canonicalFile

    private val checkedInModules: List<String> by lazy {
        File(repositoryRoot, "runtime-modules.txt").readLines().filter { it.isNotBlank() }
    }

    private val actualModules: List<String> by lazy {
        File(System.getProperty("runtimeModuleListFile")!!).readLines().filter { it.isNotBlank() }
    }

    @Before
    fun assumeReleaseVariant() {
        assumeTrue(System.getProperty("runtimeModuleListFile") != null)
    }

    @Test
    fun `the checked-in module list is non-empty and sorted with no duplicates`() {
        assertTrue(checkedInModules.isNotEmpty())
        assertEquals(checkedInModules.distinct(), checkedInModules)
        assertEquals(checkedInModules.sorted(), checkedInModules)
    }

    @Test
    fun `the actual release runtime classpath matches the checked-in module list`() {
        val added = actualModules - checkedInModules.toSet()
        val removed = checkedInModules - actualModules.toSet()
        assertTrue(
            "releaseRuntimeClasspath no longer matches runtime-modules.txt.\n" +
                "New modules (add to THIRD_PARTY_NOTICES.md and runtime-modules.txt): $added\n" +
                "Removed modules (safe to delete from runtime-modules.txt, and from THIRD_PARTY_NOTICES.md if nothing else covers them): $removed",
            added.isEmpty() && removed.isEmpty(),
        )
    }
}
