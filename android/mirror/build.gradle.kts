plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    api(project(":core-protocol"))
    api(project(":core-crypto"))
    // X25519 + HKDF-SHA256 for the pair channel (docs/MIRROR-PROTOCOL.md §3); same version as core-crypto
    implementation(libs.bouncycastle)
    implementation(libs.okhttp)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
tasks.test {
    useJUnitPlatform()
    // live tests print which side sent what — keep that evidence in the console
    testLogging { showStandardStreams = true; events("passed", "skipped", "failed") }
}
