pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "pixeltown"

include(":sim")

// The Android app requires the Android SDK and Google's Maven repository. On a JVM-only machine
// (a CI runner or a sandbox without dl.google.com) build with `-Ppixeltown.simOnly=true`, or set
// it in gradle.properties, to work on the simulation alone.
val simOnly = (providers.gradleProperty("pixeltown.simOnly").orNull ?: "false").toBoolean()
if (!simOnly) {
    include(":app")
} else {
    logger.lifecycle("pixeltown.simOnly=true — :app is excluded from this build.")
}
