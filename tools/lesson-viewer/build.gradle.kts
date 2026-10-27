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
  // compose.desktop.currentOs gives the core, the window and skiko, but NOT Material 3 — that is a separate artifact.
  implementation(compose.material3)
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
  testImplementation(kotlin("test"))
  testImplementation("junit:junit:4.13.2")
}

application {
  mainClass.set("lessonviewer.MainKt")
}

tasks.named<JavaExec>("run") {
  // -Dlessons.dir from the Gradle command line does NOT reach the forked JVM by itself:
  // without this line the option silently does nothing. The working directory is the project directory,
  // and the default path in LessonsDir is counted from it.
  System.getProperty("lessons.dir")?.let { systemProperty("lessons.dir", it) }
}

tasks.test {
  useJUnit()
  // The tests read files OUTSIDE this project: the lessons and RoadmapLevels.kt. Gradle does not know about
  // them, so without declaring the inputs explicitly, editing a lesson or the lesson order leaves the task
  // UP-TO-DATE — and the drift check silently does not run (checked: it caught a change only with
  // --rerun-tasks). The same trick as in app/build.gradle.kts for the lessons directory.
  inputs.dir(layout.projectDirectory.dir("../../app/src/main/assets/lessons"))
    .withPropertyName("lessonAssets")
    .withPathSensitivity(PathSensitivity.RELATIVE)
  inputs.file(layout.projectDirectory.file("../../app/src/main/java/com/aiopenpocket/ui/roadmap/RoadmapLevels.kt"))
    .withPropertyName("roadmapSource")
    .withPathSensitivity(PathSensitivity.RELATIVE)
}
