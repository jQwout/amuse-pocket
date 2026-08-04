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
  // Расширенный набор иконок: в core-наборе нет ни картинки, ни видео, ни галереи, а рельса без
  // подписей держится ровно на том, что иконка узнаётся с первого взгляда.
  implementation(compose.materialIconsExtended)
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

application {
  mainClass.set("com.aiopenpocket.desktop.MainKt")
}

/** Снимки экранов без окна: см. DevScreenshot. Инструмент разработки, из образа не исключается. */
tasks.register<JavaExec>("shots") {
  group = "verification"
  description = "Рендерит экраны в PNG (build/shots) без окна и без фокуса."
  mainClass.set("com.aiopenpocket.desktop.DevScreenshot")
  classpath = sourceSets["main"].runtimeClasspath
}
