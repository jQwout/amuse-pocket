// The forge: a Gradle build of its own, deliberately separate from the app.
// The app is on Kotlin 2.3 and does not carry the Ketoy compiler; this build pins the
// Kotlin the Ketoy Gradle plugin was released against, so neither side limits the other.
pluginManagement {
    repositories {
        gradlePluginPortal() // dev.ketoy.compiler and its implementation jar live here
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "amuse-forge"

include(":screens")  // Android library the coding agent writes screens into; Ketoy compiles it to .ktx
include(":contract") // DTOs of the gateway protocol, shared by :gateway and :worker (and mirrored in the app)
include(":gateway")  // Ktor service: job queue + bundle store, runs on Fly.io
include(":worker")   // JVM app: the Muse-powered coding agent + Gradle runner, runs where the Android SDK is
