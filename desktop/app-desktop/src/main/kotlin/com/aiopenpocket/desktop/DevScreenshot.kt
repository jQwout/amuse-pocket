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
import androidx.compose.foundation.layout.width
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
 * туда попадает чужой экран. Offscreen-рендер убирает и то и другое: кадр без окна, без фокуса и
 * без чужих пикселей.
 *
 * Насколько кадр воспроизводим: сеть отключена, дом временный и с постоянным именем, ключ
 * поддельный — от машины и от настроений OpenRouter снимок не зависит. Но пауза перед вторым
 * кадром (см. [shoot]) — это ожидание по времени, а не по событию: под нагрузкой превью может не
 * успеть декодироваться. Называть результат строго детерминированным поэтому нельзя.
 *
 * Это инструмент разработки, а не часть приложения: своя main-функция, запускается отдельной
 * gradle-задачей `shots`, в готовый образ не попадает никак, кроме размера класса.
 *
 * Дом подсовывается временный: харнесс не должен читать настоящий ключ и настоящую историю.
 */
object DevScreenshot {

  /** Промпт для снимка: с пустым полем кнопка запуска выключена — см. `GenerateScreen.initialPrompt`. */
  private const val SAMPLE_PROMPT =
    "Мокрый асфальт, фигура в плаще спиной к камере, фары дальнобойщика в тумане сзади, " +
      "низкая точка съёмки"

  @JvmStatic
  fun main(args: Array<String>) {
    val outDir = File(args.firstOrNull() ?: "build/shots").apply { mkdirs() }
    // Имя дома ПОСТОЯННОЕ, а не с nanoTime: «Настройки» печатают пути к конфигу и результатам, и с
    // уникальным именем settings.png менялся на каждом прогоне — снимок, который всегда отличается,
    // сравнивать не с чем. Сносим перед прогоном, чтобы прошлое состояние не протекало в новый кадр,
    // и после — чтобы каждый запуск не оставлял за собой каталог в temp.
    val home = File(System.getProperty("java.io.tmpdir"), "genai-shots").apply {
      deleteRecursively()
      mkdirs()
    }

    // История с заданиями во всех интересных состояниях: пустой экран не показывает ни сетку,
    // ни статусы, ни ряд действий — то есть ровно то, ради чего снимок и делается.
    File(home, ".local/share/aiopenpocket").mkdirs()
    // Поддельный ключ во временном доме: без ключа под кнопкой запуска стоит «Сначала укажи ключ»,
    // и смета — то, что мы этим снимком и проверяем — не рисуется вовсе. Настоящий ключ харнесс не
    // читает: дом временный, а этот файл к OpenRouter отношения не имеет.
    File(home, ".config/aiopenpocket").mkdirs()
    File(home, ".config/aiopenpocket/openrouter.key").writeText("sk-or-v1-поддельный-ключ-для-снимков")
    // Готовому заданию нужен настоящий файл: без него карточка рисует заглушку, а ряд действий
    // (повтор / доработать / видео) не появляется вовсе — то есть не видно ровно того, что снимаем.
    val sample = File(home, "sample.png").also { writeSamplePng(it) }
    // Свой каталог с заявленными параметрами: у сида их нет, и блоки «Формат сторон» / «Качество»
    // в кадр бы не попали — то есть снимок не показывал бы ровно то, ради чего он делается.
    File(home, ".config/aiopenpocket/genai-models.json").writeText(SHOT_CATALOG)
    File(home, ".local/share/aiopenpocket/jobs.json")
      .writeText(FAKE_JOBS.replace("__ART__", sample.absolutePath.replace("\\", "\\\\")))

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val container = AppContainer(
      scope,
      com.aiopenpocket.genai.AppPaths(home = home, env = { null }),
      // Без сети: снимок должен зависеть от репозитория, а не от того, какую модель OpenRouter
      // сегодня считает первой. См. AppContainer.refreshCatalogOnStart.
      refreshCatalogOnStart = false,
    )

    shoot(outDir, "generate", 1400, 950) {
      GenerateScreen(container, onOpenSettings = {}, initialPrompt = SAMPLE_PROMPT)
    }
    // Палитры одним прогоном: выбирать фон по описанию словами — то же гадание, от которого ушли.
    for ((suffix, colors) in listOf("ink" to InkScheme, "violet" to VioletScheme, "graphite" to GraphiteScheme)) {
      shoot(outDir, "palette-$suffix", 1400, 950, colors) {
        GenerateScreen(container, onOpenSettings = {}, initialPrompt = SAMPLE_PROMPT)
      }
    }

    shoot(outDir, "files", 620, 560) {
      androidx.compose.foundation.layout.Column(Modifier.width(520.dp)) {
        FieldGroup("Файлы", "каждый со своей ролью и тегами") {
          AttachmentsField(
            attachments = listOf(
              com.aiopenpocket.genai.InputAttachment(
                "/x/trassa_fog_11.jpg",
                com.aiopenpocket.genai.AttachmentRole.BACKGROUND,
                listOf("ночь", "туман"),
              ),
              com.aiopenpocket.genai.InputAttachment(
                "/x/plash_back_02.png",
                com.aiopenpocket.genai.AttachmentRole.CHARACTER,
                listOf("спина", "плащ"),
              ),
              com.aiopenpocket.genai.InputAttachment(
                "/x/film_grain_ref.jpg",
                com.aiopenpocket.genai.AttachmentRole.STYLE,
                listOf("плёнка"),
              ),
            ),
            modality = com.aiopenpocket.genai.GenerationModality.IMAGE,
            onAdd = {}, onFilesDropped = {}, onChange = { _, _ -> }, onRemove = {},
          )
        }
      }
    }
    // Строка модели в форме — своим кадром: в `generate` она стоит внизу колонки, под сгибом, и
    // проверить по общему снимку её нельзя.
    shoot(outDir, "model-row", 620, 190) {
      androidx.compose.foundation.layout.Column(Modifier.width(520.dp)) {
        FieldGroup("Модель") {
          ModelChoiceRow(
            modelId = "black-forest-labs/flux.2-pro",
            card = com.aiopenpocket.genai.buildCards(
              entries = SHOT_MODELS,
              jobs = SHOT_MODEL_JOBS,
              favorites = emptySet(),
              context = SHOT_PICKER_CONTEXT,
            ).first(),
            tier = "1K",
            previews = null,
            onOpen = {},
          )
        }
      }
    }
    // Выбор модели отдельным кадром: он живёт в модальном диалоге, а диалог на десктопе — своё
    // окно, и в offscreen-сцену главного экрана оно не попадает вовсе. Снимаем тело панели.
    //
    // Кадр по размеру НАСТОЯЩЕГО окна (1280×880 из Main) плюс рельса и отступы корня, которые
    // добавляет [shoot]. Панель берёт долю доступного места с потолком 1120×760, поэтому в тесной
    // сцене она выходит меньше, чем у пользователя, и снимок резал бы низ — цену и кнопку
    // «Выбрать», то есть ровно то, ради чего в панель смотрят.
    shoot(outDir, "models", 1360, 920) {
      // Список СОБРАН ЗДЕСЬ, а не взят из каталога контейнера. Каталог в харнессе — встроенный сид
      // (сеть выключена), а у сида нет ни имён от вендора, ни описаний, ни цен: снимок показывал бы
      // пустые строки, то есть ровно не то, что мы им проверяем.
      //
      // Набор подобран так, чтобы в кадр попали ВСЕ ЧЕТЫРЕ вида цены — за кадр, за кадр по тирам,
      // за мегапиксель, по токенам и без прайса, — и обе группы: `krea` берёт один референс, а в
      // запросе их два, значит она уезжает в «Остальные» с объяснением.
      ModelPickerPanel(
        cards = com.aiopenpocket.genai.buildCards(
          entries = SHOT_MODELS,
          jobs = SHOT_MODEL_JOBS,
          favorites = setOf("bytedance-seed/seedream-4.5"),
          context = SHOT_PICKER_CONTEXT,
        ),
        current = "black-forest-labs/flux.2-pro",
        context = SHOT_PICKER_CONTEXT,
      )
    }
    // Навигацию передаём заглушкой: без неё кнопка «В генерацию» не рисуется вовсе, и снимок
    // показывал бы экран без неё — то есть не тот, что видит пользователь.
    shoot(outDir, "history", 1400, 950) { HistoryScreen(container, onOpenGenerate = {}) }
    shoot(outDir, "settings", 1400, 950) { SettingsScreen(container) }

    container.close()
    scope.cancel()
    home.deleteRecursively()
    println("saved to ${outDir.absolutePath}")
  }

  /**
   * Рисует так же, как настоящее окно: та же тема, та же рельса, те же отступы корня. Иначе снимок
   * показывал бы не то приложение, которое видит пользователь.
   */
  @OptIn(ExperimentalComposeUiApi::class)
  private fun shoot(
    dir: File,
    name: String,
    width: Int,
    height: Int,
    colors: androidx.compose.material3.ColorScheme = GraphiteScheme,
    content: @Composable () -> Unit,
  ) {
    val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
      AppTheme(colors) {
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

  /**
   * Модель здесь — ДЕФОЛТНАЯ из каталога, а не произвольная: смета под кнопкой считается по прошлым
   * запускам той модели, что стоит в форме, и с чужим слагом в истории строка молча осталась бы без
   * цены — то есть снимок показывал бы не то, что увидит пользователь.
   *
   * Ошибка в последнем задании длинная намеренно: короткое «ошибка» умещалось где угодно, а
   * проверять надо перенос настоящего текста провайдера внутри карточки.
   */
  /** Что уже набрано в форме, когда открывают окно выбора: два файла, формат 4:3, тир 1K. */
  private val SHOT_PICKER_CONTEXT = com.aiopenpocket.genai.PickerContext(
    attachments = 2,
    aspectRatio = "4:3",
    resolution = "1K",
  )

  /**
   * Модели для снимка окна выбора — с настоящих ответов OpenRouter (2026-08-06), чтобы в кадре были
   * все виды цены сразу: за кадр, за кадр по тирам, за мегапиксель, по токенам и без прайса вовсе.
   */
  private val SHOT_MODELS = listOf(
    com.aiopenpocket.genai.GenaiModelEntry(
      id = "black-forest-labs/flux.2-pro",
      modality = com.aiopenpocket.genai.GenerationModality.IMAGE,
      priority = 1,
      label = "FLUX.2 Pro",
      description = "FLUX.2 [pro] delivers clean light, obedient prompting and predictable pricing " +
        "for production work.",
      created = 1768429228,
      pricing = listOf(com.aiopenpocket.genai.ImagePrice("megapixel", 0.03)),
      image = com.aiopenpocket.genai.ImageCapabilities(
        resolutions = listOf("1K", "2K", "4K"),
        aspectRatios = listOf("1:1", "4:3", "16:9", "21:9"),
        outputFormats = listOf("png", "jpeg"),
        maxReferences = 8,
      ),
    ),
    com.aiopenpocket.genai.GenaiModelEntry(
      id = "bytedance-seed/seedream-4.5",
      modality = com.aiopenpocket.genai.GenerationModality.IMAGE,
      priority = 2,
      vendorName = "ByteDance: Seedream 4.5",
      description = "Photoreal people: skin, light and portrait optics.",
      created = 1769000000,
      pricing = listOf(com.aiopenpocket.genai.ImagePrice("image", 0.04)),
      image = com.aiopenpocket.genai.ImageCapabilities(
        resolutions = listOf("1K", "2K", "4K"),
        aspectRatios = listOf("1:1", "4:3", "16:9"),
        maxReferences = 6,
      ),
    ),
    com.aiopenpocket.genai.GenaiModelEntry(
      id = "google/gemini-3-pro-image",
      modality = com.aiopenpocket.genai.GenerationModality.IMAGE,
      priority = 3,
      vendorName = "Google: Nano Banana Pro (Gemini 3 Pro Image)",
      description = "Nano Banana Pro is Google's most advanced image-generation and editing model, " +
        "built on Gemini 3 Pro.",
      created = 1767000000,
      pricing = listOf(com.aiopenpocket.genai.ImagePrice("token", 0.00012)),
      image = com.aiopenpocket.genai.ImageCapabilities(
        resolutions = listOf("1K", "2K", "4K"),
        aspectRatios = listOf("1:1", "4:3", "16:9", "21:9"),
        maxReferences = 14,
      ),
    ),
    com.aiopenpocket.genai.GenaiModelEntry(
      id = "sourceful/riverflow-v2.5-pro",
      modality = com.aiopenpocket.genai.GenerationModality.IMAGE,
      priority = 4,
      vendorName = "Sourceful: Riverflow v2.5 Pro",
      description = "Design-oriented model with strong typography handling.",
      created = 1768000000,
      pricing = listOf(
        com.aiopenpocket.genai.ImagePrice("image", 0.13),
        com.aiopenpocket.genai.ImagePrice("image", 0.15, "2k"),
        com.aiopenpocket.genai.ImagePrice("image", 0.17, "4k"),
      ),
      image = com.aiopenpocket.genai.ImageCapabilities(
        resolutions = listOf("1K", "2K", "4K"),
        aspectRatios = listOf("1:1", "4:3", "16:9"),
        maxReferences = 6,
      ),
    ),
    com.aiopenpocket.genai.GenaiModelEntry(
      id = "krea/krea-2-large",
      modality = com.aiopenpocket.genai.GenerationModality.IMAGE,
      priority = 5,
      vendorName = "Krea: Krea 2 Large",
      description = "Krea 2 Large focuses on aesthetic control and film-like grain.",
      created = 1766000000,
      image = com.aiopenpocket.genai.ImageCapabilities(
        resolutions = listOf("1K"),
        aspectRatios = listOf("1:1", "16:9"),
        maxReferences = 1,
      ),
    ),
  )

  /** История для снимка: «вы запускали N раз» и средняя цена должны быть видны в кадре. */
  private val SHOT_MODEL_JOBS = buildList {
    repeat(22) { index ->
      add(shotJob("j-flux-$index", "black-forest-labs/flux.2-pro", 0.0041))
    }
    repeat(5) { index -> add(shotJob("j-seed-$index", "bytedance-seed/seedream-4.5", 0.04)) }
  }

  private fun shotJob(id: String, model: String, costUsd: Double) = com.aiopenpocket.genai.GenerationJob(
    id = id,
    request = com.aiopenpocket.genai.GenerationRequest(
      modality = com.aiopenpocket.genai.GenerationModality.IMAGE,
      prompt = SAMPLE_PROMPT,
      model = model,
    ),
    createdAtEpochMs = 1,
    status = com.aiopenpocket.genai.JobStatus.DONE,
    costUsd = costUsd,
  )

  /** Каталог для снимка: одна картиночная модель, зато со всеми тремя наборами параметров. */
  private val SHOT_CATALOG = """
    {"models":[
      {"id":"black-forest-labs/flux.2-pro","modality":"IMAGE","priority":1,"label":"FLUX.2 Pro",
       "image":{"resolutions":["1K","2K","4K"],
                "aspect_ratios":["1:1","3:2","2:3","16:9","9:16","2.39:1"],
                "output_formats":["png","jpeg"]}}
    ]}
  """.trimIndent()

  private val FAKE_JOBS = """
    [
      {"id":"a1","request":{"modality":"IMAGE","prompt":"Мокрый асфальт, фигура в плаще спиной к камере, фары дальнобойщика в тумане сзади","model":"black-forest-labs/flux.2-pro"},"createdAtEpochMs":1,"status":"DONE","artifacts":["__ART__"],"costUsd":0.0042},
      {"id":"a2","request":{"modality":"IMAGE","prompt":"Мокрый асфальт","model":"black-forest-labs/flux.2-pro"},"createdAtEpochMs":2,"status":"DONE","artifacts":["__ART__"],"costUsd":0.0039},
      {"id":"a3","request":{"modality":"IMAGE","prompt":"Мокрый асфальт","model":"black-forest-labs/flux.2-pro"},"createdAtEpochMs":3,"status":"DONE","artifacts":["__ART__"],"costUsd":0.0041},
      {"id":"a4","request":{"modality":"IMAGE","prompt":"Мокрый асфальт","model":"black-forest-labs/flux.2-pro"},"createdAtEpochMs":4,"status":"FAILED","error":"Модель вернула ответ без картинки: content-filter отклонил кадр"}
    ]
  """.trimIndent()
}
