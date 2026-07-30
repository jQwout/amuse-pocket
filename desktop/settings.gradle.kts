pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
  }
}

dependencyResolutionManagement {
  repositories {
    mavenCentral()
    // Compose Desktop транзитивно тянет androidx.lifecycle:* — они на Google Maven, не на Central.
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
  }
}

// Самостоятельная сборка, как tools/lesson-viewer: корневая сборка — Android, для неё нужен SDK,
// а десктопу он не нужен. Модули :core-genai/:app-desktop переедут в корневой settings.gradle.kts
// в фазе 4, когда общий код начнёт делиться с :app (см. desktop_genai_plan.md).
rootProject.name = "aiopenpocket-desktop"

include(":core-genai")
include(":app-desktop")
