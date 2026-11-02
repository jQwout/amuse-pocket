// Wire types of the forge protocol. Pure Kotlin, no Ktor: the gateway serves them, the worker
// speaks them, and the app keeps a hand-written mirror (it is a different Gradle build).
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin { jvmToolchain(17) }

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
