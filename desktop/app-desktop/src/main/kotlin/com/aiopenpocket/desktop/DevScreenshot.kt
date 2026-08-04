package com.aiopenpocket.desktop

// @trace desktop-genai-client-4c63b6

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Снимает экраны приложения в PNG **без окна**.
 *
 * Зачем это вообще есть: UI здесь правится вслепую. Снять живое окно на Windows не выходит —
 * система не отдаёт передний план фоновому процессу, а снимать «что окажется поверх» нельзя,
 * туда попадает чужой экран. Offscreen-рендер убирает и то и другое: картинка получается
 * детерминированной, без окна, без фокуса и без чужих пикселей.
 *
 * Это инструмент разработки, а не часть приложения: своя main-функция, запускается отдельной
 * gradle-задачей `shots`, в готовый образ не попадает никак, кроме размера класса.
 *
 * Дом подсовывается временный: харнесс не должен читать настоящий ключ и настоящую историю.
 */
object DevScreenshot {

  @JvmStatic
  fun main(args: Array<String>) {
    val outDir = File(args.firstOrNull() ?: "build/shots").apply { mkdirs() }
    val home = File(System.getProperty("java.io.tmpdir"), "genai-shots-${System.nanoTime()}")
      .apply { mkdirs() }

    // История с заданиями во всех интересных состояниях: пустой экран не показывает ни сетку,
    // ни статусы, ни ряд действий — то есть ровно то, ради чего снимок и делается.
    File(home, ".local/share/aiopenpocket").mkdirs()
    // Готовому заданию нужен настоящий файл: без него карточка рисует заглушку, а ряд действий
    // (повтор / доработать / видео) не появляется вовсе — то есть не видно ровно того, что снимаем.
    val sample = File(home, "sample.png").also { writeSamplePng(it) }
    File(home, ".local/share/aiopenpocket/jobs.json")
      .writeText(FAKE_JOBS.replace("__ART__", sample.absolutePath.replace("\\", "\\\\")))

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val container = AppContainer(scope, com.aiopenpocket.genai.AppPaths(home = home, env = { null }))

    shoot(outDir, "generate", 1400, 950) {
      GenerateScreen(container, onOpenSettings = {})
    }
    shoot(outDir, "history", 1400, 950) { HistoryScreen(container) }
    shoot(outDir, "settings", 1400, 950) { SettingsScreen(container) }

    container.close()
    scope.cancel()
    println("saved to ${outDir.absolutePath}")
  }

  /**
   * Рисует так же, как настоящее окно: та же тема, та же рельса, те же отступы корня. Иначе снимок
   * показывал бы не то приложение, которое видит пользователь.
   */
  @OptIn(ExperimentalComposeUiApi::class)
  private fun shoot(dir: File, name: String, width: Int, height: Int, content: @Composable () -> Unit) {
    val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
      AppTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          Row(Modifier.fillMaxSize()) {
            IconRail(current = Screen.GENERATE, keyMissing = false, onSelect = {})
            VerticalDivider()
            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(20.dp)) {
              content()
            }
          }
        }
      }
    }
    // Два кадра, а не один: превью грузится через produceState на IO, и к первому кадру картинки
    // ещё нет — в снимке вместо неё оказывалась заглушка. Первый кадр запускает загрузку, пауза
    // даёт ей закончиться, второй кадр её показывает.
    scene.render()
    Thread.sleep(600)
    val image = scene.render()
    val bytes = image.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("не удалось закодировать PNG")
    File(dir, "$name.png").writeBytes(bytes)
    scene.close()
  }

  /** Ровный градиент вместо картинки: снимку нужен реальный файл, а не красота. */
  private fun writeSamplePng(file: File) {
    val w = 640
    val h = 360
    val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
    for (y in 0 until h) {
      for (x in 0 until w) {
        val r = 40 + x * 60 / w
        val g = 35 + y * 40 / h
        val b = 90 + x * 120 / w
        img.setRGB(x, y, (r shl 16) or (g shl 8) or b)
      }
    }
    javax.imageio.ImageIO.write(img, "png", file)
  }

  private val FAKE_JOBS = """
    [
      {"id":"a1","request":{"modality":"IMAGE","prompt":"Мокрый асфальт, фигура в плаще спиной к камере, фары дальнобойщика в тумане сзади","model":"google/gemini-3-pro-image"},"createdAtEpochMs":1,"status":"DONE","artifacts":["__ART__"],"costUsd":0.0042},
      {"id":"a2","request":{"modality":"IMAGE","prompt":"Мокрый асфальт","model":"google/gemini-3-pro-image"},"createdAtEpochMs":2,"status":"DONE","artifacts":["__ART__"],"costUsd":0.0039},
      {"id":"a3","request":{"modality":"IMAGE","prompt":"Мокрый асфальт","model":"google/gemini-3-pro-image"},"createdAtEpochMs":3,"status":"DONE","artifacts":["__ART__"],"costUsd":0.0041},
      {"id":"a4","request":{"modality":"IMAGE","prompt":"Мокрый асфальт","model":"google/gemini-3-pro-image"},"createdAtEpochMs":4,"status":"FAILED","error":"Модель не вернула картинку"}
    ]
  """.trimIndent()
}
