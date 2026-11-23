pluginManagement {
    repositories {
        // Google's mirror of Maven Central first (fewer rate limits), then the canonical hosts.
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        mavenCentral()
    }
}
rootProject.name = "Strata"
include(":app")
