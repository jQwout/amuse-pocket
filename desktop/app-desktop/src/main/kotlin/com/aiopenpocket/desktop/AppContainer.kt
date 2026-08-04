package com.aiopenpocket.desktop

// @trace desktop-genai-client-4c63b6

import com.aiopenpocket.genai.AccessCheck
import com.aiopenpocket.genai.ApiKeyStore
import com.aiopenpocket.genai.AppPaths
import com.aiopenpocket.genai.GenaiCatalogSnapshot
import com.aiopenpocket.genai.GenaiModelCatalog
import com.aiopenpocket.genai.GenaiRuntime
import com.aiopenpocket.genai.GenerationJob
import com.aiopenpocket.genai.GenerationModality
import com.aiopenpocket.genai.GenerationQueue
import com.aiopenpocket.genai.GenerationRequest
import com.aiopenpocket.genai.JobStore
import com.aiopenpocket.genai.PromptLibrary
import com.aiopenpocket.genai.PromptPreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Ручная сборка графа зависимостей. Metro (как в :app) здесь не подключён сознательно: на два
 * экрана и одну очередь он даёт больше конфигурации, чем пользы, а при переезде в общий модуль
 * (фаза 4) граф всё равно будет собираться на стороне :app.
 */
class AppContainer(
  private val scope: CoroutineScope,
  /**
   * Куда писать. Инъектируется ради offscreen-рендера экранов в PNG: тому нужен свой временный
   * дом, иначе харнесс полез бы в настоящий ключ и настоящую историю пользователя.
   */
  private val paths: AppPaths = AppPaths(),
) : AutoCloseable {

  private val keyStore = ApiKeyStore(paths)

  private val _apiKey = MutableStateFlow(keyStore.read())
  val apiKey: StateFlow<String> = _apiKey.asStateFlow()

  /**
   * Каталог моделей реактивный, а не значение: живой список приезжает из сети уже после того, как
   * окно нарисовано. До ответа показываем встроенные подсказки, честно помечая их неавторитетными.
   */
  private val _catalog = MutableStateFlow(
    GenaiCatalogSnapshot(GenaiModelCatalog.load(paths.modelsOverrideFile), authoritative = false),
  )
  val catalog: StateFlow<GenaiCatalogSnapshot> = _catalog.asStateFlow()

  private val _refreshingCatalog = MutableStateFlow(false)
  val refreshingCatalog: StateFlow<Boolean> = _refreshingCatalog.asStateFlow()

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

  val outputDir: File get() = paths.outputDir
  val configDir: File get() = paths.configDir
  val modelsOverrideFile: File get() = paths.modelsOverrideFile
  val promptsUserFile: File get() = paths.promptsUserFile

  init {
    // Видео-задание, отправленное в прошлый запуск, продолжает опрашиваться; прерванные синхронные
    // задания помечаются упавшими, чтобы история не врала про «в работе».
    queue.resumeUnfinished()
    // Каталог подтягивается в фоне: ключ для этого не нужен, эндпоинт публичный.
    scope.launch { _catalog.value = runtime.catalogService.refreshIfStale() }
  }

  /**
   * Неудачное обновление каталога. Без этого признака кнопка просто переставала крутиться, и «сеть
   * отвалилась» было не отличить от «список не изменился».
   */
  private val _catalogRefreshFailed = MutableStateFlow(false)
  val catalogRefreshFailed: StateFlow<Boolean> = _catalogRefreshFailed.asStateFlow()

  /**
   * Задания последнего запуска — то, что показывает панель результатов.
   *
   * Живёт в контейнере, а не в состоянии экрана: уход на «Историю» и обратно не должен стирать
   * пачку, которая ещё считается. Хранятся id, а не сами задания: статус и пути к файлам меняются,
   * и держать их копию значило бы показывать вчерашний снимок.
   */
  private val _lastSubmitted = MutableStateFlow<List<String>>(emptyList())
  val lastSubmitted: StateFlow<List<String>> = _lastSubmitted.asStateFlow()

  fun submitBatch(request: GenerationRequest, count: Int) {
    _lastSubmitted.value = queue.submitBatch(request, count)
  }

  fun submitAcrossModels(request: GenerationRequest, models: List<String>) {
    _lastSubmitted.value = queue.submitAcrossModels(request, models)
  }

  /**
   * Доработать результат: тот же промпт и та же модель, но готовая картинка становится основой.
   * Это обычный image-to-image, просто вход берётся не с диска, а из уже полученного результата.
   */
  fun refine(job: GenerationJob) {
    val source = job.artifacts.firstOrNull() ?: return
    submitBatch(job.request.copy(inputImage = source, attachments = emptyList()), 1)
  }

  /**
   * Оживить результат: картинка уходит первым кадром в видео-модель.
   *
   * Модель берётся первая из каталога видео — своя у пользователя может быть не выбрана вовсе, а
   * гадать нечего: видео и картинки живут в разных каталогах. Если видео-моделей нет, ничего не
   * запускаем: лучше не сделать, чем послать запрос с пустым слагом и получить 400 за деньги.
   */
  fun animate(job: GenerationJob) {
    val source = job.artifacts.firstOrNull() ?: return
    val videoModel = _catalog.value.catalog.defaultFor(GenerationModality.VIDEO)?.id ?: return
    submitBatch(
      GenerationRequest(
        modality = GenerationModality.VIDEO,
        prompt = job.request.prompt,
        model = videoModel,
        inputImage = source,
      ),
      count = 1,
    )
  }

  /** Есть ли куда «оживлять»: без видео-моделей кнопку показывать незачем. */
  val canAnimate: Boolean
    get() = _catalog.value.catalog.defaultFor(GenerationModality.VIDEO) != null

  /** Обновление каталога по кнопке — в обход суточного TTL. Неудача оставляет прежний список. */
  fun refreshCatalog() {
    if (_refreshingCatalog.value) return
    _refreshingCatalog.value = true
    _catalogRefreshFailed.value = false
    scope.launch {
      val fresh = runtime.catalogService.refresh()
      if (fresh != null) {
        _catalog.value = GenaiCatalogSnapshot(fresh, authoritative = true)
      } else {
        _catalogRefreshFailed.value = true
      }
      _refreshingCatalog.value = false
    }
  }

  /**
   * Удалось ли сузить права на файле ключа при последнем сохранении. null — ещё не сохраняли.
   * На Windows это `false` штатно, и Настройки говорят об этом прямо, а не рисуют «права 600».
   */
  private val _keyRestrictedToOwner = MutableStateFlow<Boolean?>(null)
  val keyRestrictedToOwner: StateFlow<Boolean?> = _keyRestrictedToOwner.asStateFlow()

  fun saveApiKey(key: String) {
    _keyRestrictedToOwner.value = keyStore.write(key)
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
