plugins {
  kotlin("jvm") version "2.1.0"
  id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
  id("org.jetbrains.compose") version "1.7.3"
  application
}

kotlin { jvmToolchain(21) }

dependencies {
  implementation(project(":core-genai"))
  implementation(compose.desktop.currentOs)
  // compose.desktop.currentOs даёт ядро, окно и skiko, но НЕ Material 3 — он отдельным артефактом.
  implementation(compose.material3)
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

application {
  mainClass.set("com.aiopenpocket.desktop.MainKt")
}
