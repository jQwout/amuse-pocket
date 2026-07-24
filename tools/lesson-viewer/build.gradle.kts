plugins {
  kotlin("jvm") version "2.1.0"
  kotlin("plugin.serialization") version "2.1.0"
  id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
  id("org.jetbrains.compose") version "1.7.3"
  application
}

kotlin { jvmToolchain(17) }

dependencies {
  implementation(compose.desktop.currentOs)
  // compose.desktop.currentOs даёт ядро, окно и skiko, но НЕ Material 3 — он отдельным артефактом.
  implementation(compose.material3)
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
  testImplementation(kotlin("test"))
  testImplementation("junit:junit:4.13.2")
}

application {
  mainClass.set("lessonviewer.MainKt")
}

tasks.test { useJUnit() }
