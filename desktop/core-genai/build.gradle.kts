plugins {
  kotlin("jvm") version "2.1.0"
  kotlin("plugin.serialization") version "2.1.0"
}

// В окружении есть только JDK 21 (корневая сборка тоже на 21). lesson-viewer пинит 17 — здесь не копируем.
kotlin { jvmToolchain(21) }

dependencies {
  // Версии Ktor/сериализации — те же, что в :app (io.ktor 2.3.11), чтобы код переезжал в общий
  // модуль без смены API. Движок на JVM — CIO вместо android.
  implementation("io.ktor:ktor-client-core:2.3.11")
  implementation("io.ktor:ktor-client-cio:2.3.11")
  implementation("io.ktor:ktor-client-content-negotiation:2.3.11")
  implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.11")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

  // Подменяет транспорт Ktor в тестах провайдера — ровно тот же приём, что в тестах OpenAiCompatClient.
  testImplementation("io.ktor:ktor-client-mock:2.3.11")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
  testImplementation(kotlin("test"))
}

tasks.test {
  useJUnitPlatform()
  // AndroidApiLevelTest читает исходники модуля как ДАННЫЕ. Gradle об этом не знает, и без явного
  // объявления входа задача остаётся UP-TO-DATE после правки кода — сторож молча не запускается.
  // Тот же приём, что в tools/lesson-viewer для каталога уроков.
  inputs.dir(layout.projectDirectory.dir("src/main/kotlin"))
    .withPropertyName("coreSources")
    .withPathSensitivity(PathSensitivity.RELATIVE)
}
