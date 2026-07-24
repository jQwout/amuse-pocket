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

tasks.named<JavaExec>("run") {
  // -Dlessons.dir из командной строки Gradle НЕ долетает до форкнутой JVM сам по себе:
  // без этой строки опция молча не работает. Рабочий каталог — каталог проекта, от него
  // и отсчитывается путь по умолчанию в LessonsDir.
  System.getProperty("lessons.dir")?.let { systemProperty("lessons.dir", it) }
}

tasks.test { useJUnit() }
