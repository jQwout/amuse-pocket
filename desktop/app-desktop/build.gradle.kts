plugins {
  kotlin("jvm") version "2.1.0"
  id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
  id("org.jetbrains.compose") version "1.7.3"
  // Плагина `application` здесь нет намеренно: он регистрирует задачу `run`, и её же регистрирует
  // compose-плагин — сборка падает с «Cannot add task 'run'». Нужен именно compose-овский вариант:
  // вместе с `run` он даёт createDistributable, то есть самостоятельный образ с вшитой JVM.
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

/**
 * Самостоятельный образ приложения — тот самый `.exe`, на который вешается ярлык.
 *
 * Нужен, потому что до сих пор единственным способом запуска был `gradlew run`: он требует
 * репозитория, JDK и полминуты на демон, то есть приложением нельзя было просто пользоваться.
 *
 * `createDistributable` (образ с вшитой JVM) — а не `packageMsi`: установщик MSI требует WiX,
 * которого на машине может не быть вовсе, и ставит приложение в систему. Здесь же нужен запуск,
 * а не дистрибуция.
 *
 * Образ лежит в `build/`, то есть `gradlew clean` его сносит вместе с ярлыком — это осознанный
 * размен: держать двухсотмегабайтную папку вне `build/` значит тащить её в git-статус и в бэкапы.
 */
compose.desktop {
  application {
    mainClass = "com.aiopenpocket.desktop.MainKt"
    nativeDistributions {
      // Без пробела: имя пакета становится именем exe и папки, а пробелы в путях ломают
      // ровно то, что запускается из ярлыка.
      packageName = "AiOpenPocket"
      packageVersion = "1.0.0"
      description = "Генерация картинок через OpenRouter"
    }
  }
}

/** Снимки экранов без окна: см. DevScreenshot. Инструмент разработки, из образа не исключается. */
tasks.register<JavaExec>("shots") {
  group = "verification"
  description = "Рендерит экраны в PNG (build/shots) без окна и без фокуса."
  mainClass.set("com.aiopenpocket.desktop.DevScreenshot")
  classpath = sourceSets["main"].runtimeClasspath
}
