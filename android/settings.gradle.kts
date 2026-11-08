pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        // libsignal 0.102.2 is only published on Signal's own repository (Maven Central stops at 0.86.x)
        maven("https://build-artifacts.signal.org/libraries/maven/") { content { includeGroup("org.signal") } }
    }
}
rootProject.name = "PyroChat"
include(":app", ":core-protocol", ":core-crypto", ":core-store", ":core-service", ":mirror", ":kdf-native")
