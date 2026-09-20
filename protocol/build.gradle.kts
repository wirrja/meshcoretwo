// Pure Kotlin/JVM module — the analogue of the iOS "MeshCore" package.
// Deliberately has NO Android dependency: binary packet parsing, crypto
// (Ed25519/X25519, channel + DM ciphers), LPP telemetry and the MeshTransport
// abstraction all belong here so they can be unit-tested on the plain JVM
// without an emulator, exactly like MeshCoreTests does on iOS.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.tink.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
