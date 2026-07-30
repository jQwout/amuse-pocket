package com.aiopenpocket.desktop

// @trace desktop-genai-client-4c63b6

import com.aiopenpocket.genai.AccessCheck
import com.aiopenpocket.genai.ApiKeyStore
import com.aiopenpocket.genai.AppPaths
import com.aiopenpocket.genai.GenaiModelCatalog
import com.aiopenpocket.genai.GenaiRuntime
import com.aiopenpocket.genai.GenerationModality
import com.aiopenpocket.genai.GenerationQueue
import com.aiopenpocket.genai.JobStore
import com.aiopenpocket.genai.PromptLibrary
import com.aiopenpocket.genai.PromptPreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * Ручная сборка графа зависимостей. Metro (как в :app) здесь не подключён сознательно: на два
 * экрана и одну очередь он даёт больше конфигурации, чем пользы, а при переезде в общий модуль
 * (фаза 4) граф всё равно будет собираться на стороне :app.
 */
class AppContainer(private val scope: CoroutineScope) : AutoCloseable {

  private val paths = AppPaths()
  private val keyStore = ApiKeyStore(paths)

  private val _apiKey = MutableStateFlow(keyStore.read())
  val apiKey: StateFlow<String> = _apiKey.asStateFlow()

  val catalog: GenaiModelCatalog = GenaiModelCatalog.load(paths.modelsOverrideFile)

  // Ключ читается лямбдой, а не значением: пользователь вводит его в настройках уже после того,
  // как рантайм собран, и пересобирать граф на каждое сохранение не нужно.
  private val runtime = GenaiRuntime(apiKey = { _apiKey.value }, paths = paths)

  val queue = GenerationQueue(
    provider = runtime.provider,
    artifacts = runtime.artifacts,
    store = JobStore(paths),
    scope = scope,
  )

  private val _prompts = MutableStateFlow(PromptLibrary.load(paths.promptsUserFile))
  val prompts: StateFlow<PromptLibrary> = _prompts.asStateFlow()

  /**
   * Момент запуска — граница «за сессию» в сводке расходов. Берётся один раз здесь, а не в
   * экране: пересоздание экрана не должно обнулять счётчик потраченного.
   */
  val sessionStartedAtEpochMs: Long = System.currentTimeMillis()

  val outputDir: Path get() = paths.outputDir
  val configDir: Path get() = paths.configDir
  val modelsOverrideFile: Path get() = paths.modelsOverrideFile
  val promptsUserFile: Path get() = paths.promptsUserFile

  init {
    // Видео-задание, отправленное в прошлый запуск, продолжает опрашиваться; прерванные синхронные
    // задания помечаются упавшими, чтобы история не врала про «в работе».
    queue.resumeUnfinished()
  }

  fun saveApiKey(key: String) {
    keyStore.write(key)
    _apiKey.value = key.trim()
  }

  /** Сохраняет текущий промпт как заготовку. Возвращает false, если запись не удалась. */
  fun savePreset(title: String, prompt: String, modality: GenerationModality): Boolean =
    runCatching {
      _prompts.value = PromptLibrary.saveUserPreset(
        paths.promptsUserFile,
        PromptPreset(
          id = PromptLibrary.idFor(title),
          title = title.trim(),
          prompt = prompt,
          modality = modality,
        ),
      )
    }.isSuccess

  fun deletePreset(id: String): Boolean =
    runCatching { _prompts.value = PromptLibrary.deleteUserPreset(paths.promptsUserFile, id) }.isSuccess

  /**
   * Проверка связи и ключа — закрывает вопрос «дойдёт ли вообще запрос до OpenRouter» до первой
   * платной генерации. null в потоке означает «ещё не проверяли», [checkingAccess] — «идёт».
   */
  private val _accessCheck = MutableStateFlow<AccessCheck?>(null)
  val accessCheck: StateFlow<AccessCheck?> = _accessCheck.asStateFlow()

  private val _checkingAccess = MutableStateFlow(false)
  val checkingAccess: StateFlow<Boolean> = _checkingAccess.asStateFlow()

  fun checkAccess() {
    if (_checkingAccess.value) return
    _checkingAccess.value = true
    scope.launch {
      _accessCheck.value = runtime.provider.checkAccess()
      _checkingAccess.value = false
    }
  }

  /**
   * Закрытие окна. Незавершённые задания при этом гасятся, но их статус уже на диске: видео с
   * `polling_url` догонится при следующем запуске, остальные встретят пользователя как прерванные.
   */
  override fun close() {
    // Очередь первой: она дописывает историю синхронно, иначе последний переход остался бы
    // только в памяти.
    runCatching { queue.close() }
    runCatching { runtime.close() }
  }
}
