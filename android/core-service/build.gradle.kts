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
    api(project(":core-store"))
    implementation(libs.okhttp)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
tasks.test {
    useJUnitPlatform()
    // The stores the live tests make (LiveTestSupport) are removed when their JVM ends; one that
    // was killed leaves them, so each run starts by clearing whatever is there.
    doFirst { delete(layout.buildDirectory.dir("tmp/live-tests")) }
    // Gate B (InteropWithIosTest): the username the iOS InteropEchoTests registered —
    // `-PinteropUser=<name>` or the PYRO_INTEROP_USER environment variable.
    (project.findProperty("interopUser") as String? ?: System.getenv("PYRO_INTEROP_USER"))
        ?.takeIf { it.isNotBlank() }
        ?.let { systemProperty("pyro.interop.user", it) }
    // The live matrix of docs/SEALED-SENDER.md §9 (X2): `-PinteropLegacy=true` or PYRO_INTEROP_LEGACY
    // makes the Android side of Gate B a build from before sealed sender.
    (project.findProperty("interopLegacy") as String? ?: System.getenv("PYRO_INTEROP_LEGACY"))
        ?.takeIf { it.isNotBlank() }
        ?.let { systemProperty("pyro.interop.legacy", it) }
    // Groups gate (GroupInteropWithIosTest): the file the iOS GroupInteropTests publishes its two
    // usernames to — `-PgroupInteropNames=<file>` or the PYRO_GROUP_INTEROP_NAMES environment variable.
    (project.findProperty("groupInteropNames") as String? ?: System.getenv("PYRO_GROUP_INTEROP_NAMES"))
        ?.takeIf { it.isNotBlank() }
        ?.let { systemProperty("pyro.group.interop.names", it) }
    // Update-banner live gate (VersionsLiveTest): a relay pre-set to build 99 — `-PversionsRelay=host:port`
    // or the PYRO_VERSIONS_RELAY environment variable, plus its admin password (`-PversionsAdminPw` / PYRO_VERSIONS_ADMIN_PW).
    (project.findProperty("versionsRelay") as String? ?: System.getenv("PYRO_VERSIONS_RELAY"))
        ?.takeIf { it.isNotBlank() }
        ?.let { systemProperty("pyro.versions.relay", it) }
    (project.findProperty("versionsAdminPw") as String? ?: System.getenv("PYRO_VERSIONS_ADMIN_PW"))
        ?.takeIf { it.isNotBlank() }
        ?.let { systemProperty("pyro.versions.admin.pw", it) }
}
