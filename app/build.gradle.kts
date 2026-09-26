// Android application module — the analogue of the iOS "MC1" app target.
// Jetpack Compose UI, MapLibre for maps (no Google Maps / GMS dependency),
// no Play Billing (monetization is explicitly out of scope for this port).
import java.util.Properties
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing is opt-in via a local, gitignored `keystore.properties`
// (see keystore.properties.example) — without it `assembleRelease` still
// succeeds but produces an unsigned APK that Android refuses to install
// ("no certificates"). Not present in a fresh checkout or CI by design:
// each person distributing a sideload build owns their own release key.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.meshcoretwo.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.meshcoretwo.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 22
        versionName = "1.1.19"
        // The UI is translated into exactly these languages (see AppLanguage.kt / locales_config.xml);
        // library-supplied resources for other locales are dropped from the APK.
        resourceConfigurations += listOf("en", "ru", "fr", "de", "zh-rCN", "tr", "fi", "sv")
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // A key present in values/ but missing from a values-XX/ is a shipped English hole.
        error += listOf("MissingTranslation", "ExtraTranslation", "StringFormatInvalid", "StringFormatMatches")
    }

    buildFeatures {
        compose = true
        // BuildConfig.VERSION_NAME/VERSION_CODE for the About screen.
        buildConfig = true
    }
}

/**
 * Copies the license texts from the repository root into generated assets under `licenses/`, so the
 * About screen can show them from the installed APK without network access (PLAN.md Л1). The
 * repository files stay the single source of truth.
 */
abstract class GenerateLicenseAssets : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val licenseFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val noticesFile: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val licenseTextsDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        val licenses = output.resolve("licenses")
        licenseFile.get().asFile.copyTo(licenses.resolve("LICENSE"))
        noticesFile.get().asFile.copyTo(licenses.resolve("THIRD_PARTY_NOTICES.md"))
        licenseTextsDirectory.get().asFile.copyRecursively(licenses.resolve("LICENSES"))
    }
}

androidComponents {
    onVariants { variant ->
        val generateLicenseAssets = tasks.register<GenerateLicenseAssets>(
            "generate${variant.name.replaceFirstChar { it.uppercase() }}LicenseAssets",
        ) {
            licenseFile.set(rootProject.layout.projectDirectory.file("LICENSE"))
            noticesFile.set(rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md"))
            licenseTextsDirectory.set(rootProject.layout.projectDirectory.dir("LICENSES"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(generateLicenseAssets, GenerateLicenseAssets::outputDirectory)
    }
}

/** Passes a variant's generated license assets to `LicenseAssetsTest`; as a task input it also makes the unit tests run the copy first. */
abstract class LicenseAssetsDirectoryArgument : CommandLineArgumentProvider {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val directory: DirectoryProperty

    override fun asArguments(): Iterable<String> = listOf("-DlicenseAssetsDir=${directory.get().asFile.absolutePath}")
}

tasks.withType<Test>().configureEach {
    // testDebugUnitTest reads the assets of generateDebugLicenseAssets, and so on per variant.
    val variantName = name.removePrefix("test").removeSuffix("UnitTest")
    val generateLicenseAssets = tasks.named<GenerateLicenseAssets>("generate${variantName}LicenseAssets")
    jvmArgumentProviders.add(
        objects.newInstance<LicenseAssetsDirectoryArgument>().apply {
            directory.set(generateLicenseAssets.flatMap { it.outputDirectory })
        },
    )
}

/**
 * Dumps the `group:artifact` coordinate of every module in [rootComponent]'s resolved dependency
 * graph, one per line — the machine-readable half of PLAN.md's Л5 ("automated license-diff check").
 * `RuntimeModuleListTest` diffs this against the checked-in `LICENSES/runtime-modules.txt`, so a
 * new dependency landing without a matching `THIRD_PARTY_NOTICES.md`/`LICENSES` update fails the
 * build instead of silently shipping unlicensed. [ResolvedComponentResult]/[ResolvedDependencyResult]
 * are Gradle's configuration-cache-safe way to consume a resolved configuration from a task (unlike
 * the legacy `ResolvedConfiguration` API), so this works under `--configuration-cache` too.
 */
abstract class GenerateRuntimeModuleList : DefaultTask() {
    @get:Input
    abstract val rootComponent: Property<ResolvedComponentResult>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val modules = sortedSetOf<String>()
        val visited = mutableSetOf<ComponentIdentifier>()
        fun visit(component: ResolvedComponentResult) {
            if (!visited.add(component.id)) return
            // Project components (this build's own :app/:services/:protocol — note Gradle still
            // gives these a `moduleVersion`, defaulting group to the root project's name, so this
            // must check `id`, not just null-check `moduleVersion`) recurse into their dependencies
            // (which do carry real third-party coordinates) without recording the project itself.
            if (component.id !is ProjectComponentIdentifier) {
                component.moduleVersion?.let { id -> modules += "${id.group}:${id.name}" }
            }
            component.dependencies.forEach { dependency ->
                if (dependency is ResolvedDependencyResult) visit(dependency.selected)
            }
        }
        visit(rootComponent.get())
        outputFile.get().asFile.writeText(modules.joinToString("\n") + "\n")
    }
}

val generateRuntimeModuleList = tasks.register<GenerateRuntimeModuleList>("generateRuntimeModuleList") {
    rootComponent.set(configurations.named("releaseRuntimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent })
    outputFile.set(layout.buildDirectory.file("runtimeModules/runtime-modules.txt"))
}

/** Passes the generated runtime module list to `RuntimeModuleListTest`; as a task input it also makes the unit test run the dump first. */
abstract class RuntimeModuleListFileArgument : CommandLineArgumentProvider {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val file: RegularFileProperty

    override fun asArguments(): Iterable<String> = listOf("-DruntimeModuleListFile=${file.get().asFile.absolutePath}")
}

// Scoped to release only, matching THIRD_PARTY_NOTICES.md's own stated scope ("every module of
// :app:releaseRuntimeClasspath") — debug pulls in test-only tooling that was never meant to be
// inventoried here. `withType().configureEach`, not `tasks.named("testReleaseUnitTest")` — AGP
// registers that task later than this script's own evaluation, so a direct `named()` lookup here
// fails with "Task with name ... not found".
tasks.withType<Test>().configureEach {
    if (name == "testReleaseUnitTest") {
        jvmArgumentProviders.add(
            objects.newInstance<RuntimeModuleListFileArgument>().apply {
                file.set(generateRuntimeModuleList.flatMap { it.outputFile })
            },
        )
    }
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":services"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    // MapLibre — offline vector-tile packs live in map/OfflineMapService.kt.
    // See note on artifact coordinates in libs.versions.toml.
    implementation(libs.maplibre.android.sdk)

    // QR generation (contact/device-identity sharing) + scanning (add-contact flow).
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
