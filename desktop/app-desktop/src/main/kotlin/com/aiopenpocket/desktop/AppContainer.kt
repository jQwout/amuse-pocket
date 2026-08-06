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
 * Незавершённая работа на экране генерации — всё, что пользователь набрал, но ещё не отправил.
 *
 * Отдельный тип, а не три поля в контейнере: так видно, что это одно целое, и так его нельзя забыть
 * восстановить по частям.
 */
data class GenerationDraft(
  val prompt: String = "",
  val attachments: List<com.aiopenpocket.genai.InputAttachment> = emptyList(),
  /** Пусто означает «ещё не выбрана» — подставится модель по умолчанию из каталога. */
  val model: String = "",
  val promptExpanded: Boolean = false,
  /** Параметры картинки. null — «не задавать», параметр не уйдёт в запрос вовсе. */
  val resolution: String? = null,
  val aspectRatio: String? = null,
  val outputFormat: String? = null,
  val quality: String? = null,
)

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
  /**
   * Тянуть ли живой каталог из сети при старте. Выключается только offscreen-харнессом: он обещает
   * детерминированный снимок, а живой каталог это обещание ломает — приоритетная модель у
   * OpenRouter меняется сама по себе, и в форме на снимке оказывается то один слаг, то другой,
   * без единой правки в коде. Со встроенным каталогом снимок зависит только от репозитория.
   */
  refreshCatalogOnStart: Boolean = true,
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

  private val favoriteModels = com.aiopenpocket.genai.FavoriteModels(paths.favoritesFile)

  /** Отмеченные сердцем модели. Читаются с диска один раз при сборке графа: файл маленький и свой. */
  private val _favorites = MutableStateFlow(favoriteModels.read())
  val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

  fun toggleFavorite(modelId: String) {
    _favorites.value = favoriteModels.toggle(modelId)
  }

  /**
   * Момент запуска — граница «за сессию» в сводке расходов. Берётся один раз здесь, а не в
   * экране: пересоздание экрана не должно обнулять счётчик потраченного.
   */
  val sessionStartedAtEpochMs: Long = System.currentTimeMillis()

  /**
   * Удаление результата запускается на scope ПРИЛОЖЕНИЯ, а не экрана.
   *
   * `rememberCoroutineScope()` умирает вместе с композицией: уход на «Историю» сразу после
   * подтверждения обрывал бы удаление на середине. Внутри оно защищено `NonCancellable`, но саму
   * корутину надо ещё и не убить снаружи, иначе она не дойдёт даже до первого шага.
   */
  fun delete(jobId: String) {
    // Из панели id убираем сразу: иначе удаление единственного результата сессии оставляло панель
    // пустой при полной истории — «запусков не было» рядом с двумя десятками готовых кадров.
    _lastSubmitted.value = _lastSubmitted.value - jobId
    scope.launch { _deleteFailures.value = queue.delete(jobId) }
  }

  /**
   * Повтор через контейнер, а не напрямую в очередь: новое задание обязано попасть в панель
   * результатов. Иначе повторённый кадр учитывался как «ещё N в работе», а по завершении исчезал.
   */
  fun retry(jobId: String) {
    queue.retry(jobId)?.let { _lastSubmitted.value = listOf(it) + _lastSubmitted.value }
  }

  /**
   * Файлы, которые стереть не удалось. Живёт в контейнере, а не в состоянии экрана, ровно по той же
   * причине, по которой само удаление ушло на scope приложения: типичный сценарий — подтвердил и
   * тут же ушёл на «Историю». Состояние экрана к этому моменту уничтожено, и отчёт о неудаче было
   * бы некому получить — то есть в единственном случае, ради которого всё это писалось.
   */
  private val _deleteFailures = MutableStateFlow<List<String>>(emptyList())
  val deleteFailures: StateFlow<List<String>> = _deleteFailures.asStateFlow()

  fun clearDeleteFailures() {
    _deleteFailures.value = emptyList()
  }

  fun setRating(jobId: String, rating: com.aiopenpocket.genai.JobRating) = queue.setRating(jobId, rating)

  /** Витрина примеров у моделей. Единственное, что ходит на сайт OpenRouter, а не в его API. */
  val modelPreviews: com.aiopenpocket.genai.ModelPreviews get() = runtime.modelPreviews

  val outputDir: File get() = paths.outputDir
  val apiKeyFile: File get() = paths.apiKeyFile

  /** История не читалась и отложена в сторону — экран настроек обязан сказать это вслух. */
  val historyFileWasBroken: Boolean get() = queue.historyFileWasBroken
  val brokenHistoryFile: File get() = queue.brokenHistoryFile
  val configDir: File get() = paths.configDir
  val modelsOverrideFile: File get() = paths.modelsOverrideFile
  val promptsUserFile: File get() = paths.promptsUserFile

  init {
    // Видео-задание, отправленное в прошлый запуск, продолжает опрашиваться; прерванные синхронные
    // задания помечаются упавшими, чтобы история не врала про «в работе».
    queue.resumeUnfinished()
    // Каталог подтягивается в фоне: ключ для этого не нужен, эндпоинт публичный.
    //
    // `force` — то есть в сеть на КАЖДЫЙ запуск, минуя суточный TTL. Кеш при этом не лишний, а
    // меняет роль: по нему рисуется первый кадр, пока сеть отвечает, и он же остаётся единственным
    // списком, когда сети нет. Цена решения — 43 запроса вместо трёх (общий каталог, картинки,
    // видео и по одной ручке цены на каждую картиночную модель), все в фоне и под гейтом.
    if (refreshCatalogOnStart) {
      scope.launch { _catalog.value = runtime.catalogService.refreshIfStale(force = true) }
    }
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
    // Накапливаем, а не заменяем. Раньше здесь было присваивание, и каждый новый запуск стирал с
    // панели всё, что нагенерено до него: чтобы посмотреть на предыдущий кадр, приходилось уходить
    // в «Историю» — а это другой экран с другой раскладкой. Панель результатов должна быть рабочим
    // столом за эту сессию, а не окошком в последнюю пачку.
    _lastSubmitted.value = queue.submitBatch(request, count) + _lastSubmitted.value
  }

  /**
   * Черновик формы: то, что набрано, но ещё не отправлено.
   *
   * Живёт в контейнере, а не в `remember` экрана, потому что экраны переключаются рельсой, а
   * `GenerateScreen` при уходе на «Историю» уничтожается целиком. Вместе с ним пропадали набранный
   * промпт и — что хуже — список прикреплённых файлов: их приходилось выбирать заново, уже не
   * помня, какие теги и роли были проставлены.
   */
  private val _draft = MutableStateFlow(GenerationDraft())
  val draft: StateFlow<GenerationDraft> = _draft.asStateFlow()

  fun updateDraft(transform: (GenerationDraft) -> GenerationDraft) {
    _draft.value = transform(_draft.value)
  }

  /**
   * Переносит прошлое задание обратно в форму: промпт, файлы с ролями и тегами, модель, параметры.
   *
   * Смысл не в повторе — для точного повтора есть «Повторить», он ничего не спрашивает. Здесь
   * наоборот: взять удачный кадр за основу и что-то в нём поменять. Раньше для этого приходилось
   * перенабирать промпт руками, глядя в другую вкладку, и заново выбирать файлы, вспоминая роли.
   *
   * Файлы, которых больше нет на диске, НЕ подставляются: путь ведёт в никуда, а вложение с
   * несуществующим путём доедет до платного запроса и вернёт отказ. Их имена возвращаются, чтобы
   * пользователь узнал, чего не хватает, а не гадал, почему файлов стало меньше.
   */
  fun loadDraftFrom(job: GenerationJob) {
    val (alive, missing) = job.request.attachments.partition { File(it.path).isFile }
    _draft.value = GenerationDraft(
      prompt = job.request.prompt,
      attachments = alive,
      model = job.request.model,
      resolution = job.request.resolution,
      aspectRatio = job.request.aspectRatio,
      outputFormat = job.request.outputFormat,
      quality = job.request.quality,
    )
    _draftNotice.value = missing
      .takeIf { it.isNotEmpty() }
      ?.let { "Не нашли на диске: " + it.joinToString(", ") { a -> a.name } }
  }

  /** Что стоит знать о только что перенесённом черновике. null — переносить было нечего или всё цело. */
  private val _draftNotice = MutableStateFlow<String?>(null)
  val draftNotice: StateFlow<String?> = _draftNotice.asStateFlow()

  fun clearDraftNotice() {
    _draftNotice.value = null
  }

  // Обёртки submitAcrossModels здесь больше нет: режим сравнения моделей убран из UI, и вызывать её
  // стало неоткуда. В ядре `GenerationQueue.submitAcrossModels` остаётся — она под тестами и это
  // возможность очереди, а не экрана.

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
      try {
        val fresh = runtime.catalogService.refresh()
        if (fresh != null) {
          _catalog.value = GenaiCatalogSnapshot(fresh, authoritative = true)
        } else {
          _catalogRefreshFailed.value = true
        }
      } finally {
        // В finally: без него отмена корутины оставляла бы кнопку «Обновить каталог» выключенной
        // навсегда — и снаружи это выглядит как зависшее обновление.
        _refreshingCatalog.value = false
      }
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
  /**
   * Возвращает причину отказа или null при успехе.
   *
   * Именно причину, а не голый `false`: с недавних пор `PromptLibrary` отказывается писать поверх
   * неразобравшегося файла и объясняет, почему. Свернуть это обратно в булев флаг значило бы
   * показать «не удалось сохранить» и на битом JSON, и на отказе в правах — а действия у них
   * разные, и пользователь без подсказки будет жать «Сохранить» до бесконечности.
   */
  fun savePresetOrReason(title: String, prompt: String, modality: GenerationModality): String? =
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
    }.exceptionOrNull()?.let { it.message ?: it.toString() }

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
      // finally по той же причине, что у refreshCatalog: без него отмена оставляет кнопку
      // «Проверить связь» выключенной до конца работы приложения.
      try {
        _accessCheck.value = runtime.provider.checkAccess()
      } finally {
        _checkingAccess.value = false
      }
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
