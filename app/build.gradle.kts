plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  alias(libs.plugins.metro)
}

android {
  namespace = "com.aiopenpocket"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.aistudio.aiopenpocket.hzwpqy"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Куда Room кладёт JSON-снимки схемы (@Database(exportSchema = true)). Снимки коммитятся: первую
  // настоящую Migration придётся писать ОТ схемы релиза, и взять её будет неоткуда, если не копить.
  ksp { arg("room.schemaLocation", "${projectDir}/schemas") }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    // debug.keystore лежит в .gitignore, поэтому на свежем клоне его просто нет — а конфиг ссылался
    // на него безусловно, и debug-сборка падала на подписи. Заводим конфиг только когда файл реально
    // есть; иначе ниже остаётся штатный debug-ключ, который AGP генерирует сам.
    if (file("${rootDir}/debug.keystore").exists()) {
      create("debugConfig") {
        storeFile = file("${rootDir}/debug.keystore")
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // findByName, а не getByName: конфиг заводится выше только при наличии debug.keystore.
      signingConfig = signingConfigs.findByName("debugConfig") ?: signingConfigs.getByName("debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }
  kotlin {
    compilerOptions {
      jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  // В BuildConfig не едут ключи, которые приложение не читает: плагин по умолчанию кладёт туда каждую
  // строку .env, и отладочный APK, отданный кому-то, уносил бы давно не нужные ключи (NVIDIA, Gemini
  // времён AI Studio). Явный список, а не «всё, кроме OpenRouter» через lookahead: плагин сверяет
  // шаблон containsMatchIn, и такой шаблон на деле выкидывал и сам OPENROUTER_API_KEY.
  ignoreList.add("^NVIDIA_API_KEY$")
  ignoreList.add("^GEMINI_API_KEY$")
}

// Начала живых ключей провайдеров: sk-or-v1- и sk- (OpenRouter/OpenAI), nvapi- (NVIDIA NIM), AIza
// (Google). NIM из приложения выпилен, но секретный плагин кладёт в BuildConfig ЛЮБУЮ строку .env,
// так что старый ключ, оставшийся в файле, уехал бы в релиз и без кода, который его читает.
// Плейсхолдер из .env.example (MY_OPENROUTER_API_KEY) ни под один префикс не подходит.
val liveKeyPrefixes = listOf("nvapi-", "sk-or-v1-", "sk-", "AIza")

/**
 * Не дать магазинной сборке унести живой ключ провайдера.
 *
 * Значения из `.env` секретный плагин кладёт в BuildConfig, а BuildConfig едет ВНУТРИ артефакта:
 * достать оттуда строку — вопрос одной распаковки AAB. То есть ключ, собранный в релиз, это не
 * «утечка когда-нибудь», а раздача своей квоты всем, кто скачал приложение, с первого же дня.
 * Проверено на живом артефакте 2026-09-06: `bundleRelease` унёс рабочий `nvapi-…` ключ.
 *
 * Сторож висит ТОЛЬКО на release: debug собирается с рабочим `.env` постоянно, и падать на нём
 * значило бы сломать обычную разработку ради инварианта, который к debug не относится — тот
 * артефакт никуда не уезжает.
 */
val checkReleaseSecrets = tasks.register("checkReleaseSecrets") {
  description = "Fails the release build when .env carries a live provider key (it would ship inside BuildConfig)."
  val envFile = rootProject.file(".env")
  val prefixes = liveKeyPrefixes
  // Файла может не быть вовсе (свежий клон без ключей) — это не ошибка: тогда в BuildConfig едут
  // плейсхолдеры из .env.example, ровно то, что релизу и нужно.
  inputs.files(files(envFile)).withPropertyName("envFile")
  doLast {
    if (!envFile.exists()) return@doLast
    val offenders = envFile.readLines()
      .map { it.trim() }
      .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
      .mapNotNull { line ->
        val name = line.substringBefore('=').trim()
        val value = line.substringAfter('=').trim().trim('"', '\'')
        name.takeIf { prefixes.any(value::startsWith) }
      }
    if (offenders.isNotEmpty()) {
      // Текст сообщения — ASCII сознательно: консоль Windows отдаёт вывод Gradle в cp866/cp1251, и
      // кириллица в GradleException приезжает кракозябрами именно там, где её обязаны прочитать.
      throw GradleException(
        "Release build stopped: .env holds live provider key(s) (${offenders.joinToString()}). " +
          "They are copied into BuildConfig and ship inside the AAB, where anyone can unpack them. " +
          "Put placeholders (see .env.example) in .env before building a release; " +
          "real keys for the paid path belong on the server, not in the artifact.",
      )
    }
  }
}

// generateReleaseBuildConfig — момент, когда ключ физически попадает в код сборки; проверка перед
// ним валит билд до компиляции, а не после четырёх минут работы. Дубль на самих артефактных
// задачах — на случай, если BuildConfig окажется UP-TO-DATE и не выполнится вовсе.
tasks.matching {
  it.name in setOf("generateReleaseBuildConfig", "bundleRelease", "assembleRelease")
}.configureEach {
  dependsOn(checkReleaseSecrets)
}

// LessonAssetsValidationTest читает assets/lessons/*.json (+ картинки) напрямую через File() в
// рантайме теста, минуя мёрж ресурсов. Gradle не видит их как входы задачи → при правках ТОЛЬКО
// уроков testDebugUnitTest помечается UP-TO-DATE и валидатор НЕ запускается (битый урок мог пройти
// CI). Объявляем каталог входом всех Test-задач: правка урока инвалидирует кэш и тест перезапускается.
tasks.withType<Test>().configureEach {
  inputs.dir(layout.projectDirectory.dir("src/main/assets/lessons"))
    .withPropertyName("lessonAssets")
    .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.livedata.ktx)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  implementation(libs.coil.svg)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  // implementation(libs.play.services.location)
  implementation(libs.metrox.viewmodel)
  implementation(libs.metrox.viewmodel.compose)
  implementation(libs.androidx.security.crypto)
  implementation(libs.androidx.work.runtime)
  implementation(libs.androidx.documentfile)
  // Ktor Client Dependencies
  implementation("io.ktor:ktor-client-core:2.3.11")
  implementation("io.ktor:ktor-client-android:2.3.11")
  implementation("io.ktor:ktor-client-content-negotiation:2.3.11")
  implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.11")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
  // Markdown rendering (mikepenz multiplatform-markdown-renderer, Material 3 flavor)
  implementation("com.mikepenz:multiplatform-markdown-renderer:0.41.0")
  implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.41.0")
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  // Подменяет транспорт Ktor в тестах OpenAiCompatClient (фолбэк по цепочке моделей) — без сети.
  testImplementation("io.ktor:ktor-client-mock:2.3.11")
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.work.testing)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  ksp(libs.androidx.room.compiler)
}
