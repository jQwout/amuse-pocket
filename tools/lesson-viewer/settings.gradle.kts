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

rootProject.name = "lesson-viewer"
