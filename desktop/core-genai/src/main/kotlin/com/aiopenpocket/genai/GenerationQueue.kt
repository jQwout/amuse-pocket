package com.aiopenpocket.genai

// @trace desktop-genai-client-4c63b6

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Единая очередь на все модальности: синхронная картинка — это просто задание без фазы
 * [JobStatus.POLLING], поэтому отдельного пути для неё нет.
 *
 * Состояние каждого задания пишется в [JobStore] на каждый переход, а не в конце: иначе закрытое
 * окно унесло бы с собой ссылку на уже оплаченное видео-задание.
 */
class GenerationQueue(
  private val provider: GenerationProvider,
  private val artifacts: ArtifactStore,
  private val store: JobStore,
  private val scope: CoroutineScope,
  maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
  maxPolling: Int = DEFAULT_MAX_POLLING,
  private val maxHistory: Int = MAX_HISTORY,
  private val clock: () -> Long = System::currentTimeMillis,
  private val newId: () -> String = { UUID.randomUUID().toString().take(8) },
  // Отдельный генератор, а не тот же newId: история группируется по `batchId ?: id`, и совпадение
  // id пачки с id чужого задания склеило бы в одну карточку несвязанные результаты.
  private val newBatchId: () -> String = { "batch-" + UUID.randomUUID().toString().take(8) },
) {

  private val semaphore = Semaphore(maxConcurrent)

  /**
   * Отдельный, более щедрый лимит на задания, которые уже только опрашивают провайдера. Без него
   * полсотни видео в очереди дают десяток запросов в секунду и упираются в rate-limit ключа.
   */
  private val pollSemaphore = Semaphore(maxPolling)

  // ConcurrentHashMap, а не mutableMapOf: submit зовётся из UI-потока, а снятие записи —
  // из finally корутины на Dispatchers.Default. Обычная HashMap на этом ломается молча.
  private val running = ConcurrentHashMap<String, Job>()

  /**
   * Запись истории — блокирующий I/O, и делать его в пуле Dispatchers.Default (там же считается
   * очередь, потоков там по числу ядер) значит воровать у себя же. Один поток: записи заодно
   * выстраиваются в очередь, и лок не нужен.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

  private val _jobs = MutableStateFlow(store.load())
  val jobs: StateFlow<List<GenerationJob>> = _jobs.asStateFlow()

  /**
   * Файл истории не читался и отложен в сторону (см. [JobStore.load]). Пробрасывается наружу, потому
   * что «история вдруг пустая» пользователь обязан увидеть объяснённой, а не гадать.
   */
  val historyFileWasBroken: Boolean = store.lastLoadFailed
  val brokenHistoryFile: File get() = store.brokenFile

  fun submit(request: GenerationRequest): String = submitOne(request, batchId = null)

  /**
   * Несколько вариантов одного промпта. Модели генеративны, и разброс между прогонами — часть
   * работы: проще отобрать из четырёх, чем жать «Повторить» четыре раза и терять предыдущие.
   */
  fun submitBatch(request: GenerationRequest, count: Int): List<String> {
    require(count >= 1) { "count must be >= 1, was $count" }
    if (count == 1) return listOf(submit(request))
    val batchId = newBatchId()
    return List(count) { submitOne(request, batchId) }
  }

  /**
   * Один промпт по нескольким моделям — сравнение бок о бок. Пустой список моделей означал бы
   * «ничего не запускать», а не «взять модель из запроса», поэтому это ошибка вызова.
   */
  fun submitAcrossModels(request: GenerationRequest, models: List<String>): List<String> {
    require(models.isNotEmpty()) { "models must not be empty" }
    if (models.size == 1) return listOf(submit(request.copy(model = models.single())))
    val batchId = newBatchId()
    return models.map { model -> submitOne(request.copy(model = model), batchId) }
  }

  private fun submitOne(request: GenerationRequest, batchId: String?): String {
    val job = GenerationJob(
      id = newId(),
      request = request,
      createdAtEpochMs = clock(),
      batchId = batchId,
    )
    upsert(job)
    launchJob(job.id) { provider.generate(request) }
    return job.id
  }

  /** Повтор задания тем же промптом — новое задание, чтобы история не переписывалась поверх старой. */
  fun retry(jobId: String): String? {
    val source = _jobs.value.firstOrNull { it.id == jobId } ?: return null
    return submit(source.request)
  }

  fun cancel(jobId: String) {
    running.remove(jobId)?.cancel()
    // Через mutate, а не «прочитал снимок — записал»: задание может завершиться прямо сейчас, и
    // запись по устаревшему снимку затёрла бы DONE вместе с путями к файлам и стоимостью.
    mutate(jobId) { if (it.status.isTerminal) null else it.copy(status = JobStatus.CANCELLED, note = null) }
  }

  /**
   * Ставит или снимает оценку. Повторное нажатие той же кнопки снимает — иначе передумать нельзя.
   *
   * Через `mutate`, а не «прочитал — записал»: задание может в этот момент дописывать свой статус из
   * потока прогресса, и снимок затёр бы его.
   */
  fun setRating(jobId: String, rating: JobRating) {
    mutate(jobId) { it.copy(rating = if (it.rating == rating) JobRating.NONE else rating) }
  }

  /**
   * Убирает задание из истории и стирает его файлы с диска.
   *
   * Одной операцией, а не двумя, потому что половинчатый результат хуже обоих целых: запись без
   * файлов показывает карточку с вечной заглушкой, файлы без записи копятся в папке безымянным
   * мусором, который уже не связать с промптом.
   *
   * Незавершённое задание сначала отменяется: удалить запись у работающей корутины — значит
   * получить её обратно первым же `mutate` из потока прогресса.
   *
   * Возвращает пути, которые стереть НЕ удалось (файл занят, прав нет). Пустой список — всё чисто.
   * Молча проглатывать отказ нельзя: пользователь нажал «удалить», а файл остался на диске, и без
   * ответа он об этом не узнает.
   *
   * Запись уходит из истории ДАЖЕ если файл стереть не вышло. Это сознательный выбор в пользу
   * намерения пользователя: он просил убрать результат с глаз, и оставить карточку висеть из-за
   * заблокированного антивирусом файла значит не выполнить просьбу. Цена решения названа вслух —
   * невытертый файл возвращается вызывающему, и показать его обязан он.
   *
   * **Про деньги.** Удалённое задание исчезает и из сводки расходов: она считается по живому списку.
   * Потраченного это не отменяет, поэтому UI обязан предупредить об этом в подтверждении. Отдельного
   * счётчика «потрачено на удалённое» здесь нет намеренно: он потребовал бы менять формат файла
   * истории ради строки в одном экране.
   *
   * `suspend`, потому что удаление файла — блокирующий вызов к диску: на заблокированном или сетевом
   * пути он подвешивает поток, а зовут его с UI-потока по нажатию в диалоге.
   *
   * Удаление необратимо и в корзину не кладётся — вызывающий обязан спросить подтверждение.
   */
  suspend fun delete(jobId: String): List<String> {
    if (_jobs.value.none { it.id == jobId }) return emptyList()

    // cancelAndJoin, а не cancel: отмена асинхронна, а запись файла блокирующая и отмену не слышит.
    // Без ожидания задание успевало дописать свои артефакты ПОСЛЕ того, как мы прочитали пустой
    // список путей, — и файлы оставались в папке уже без записи в истории.
    // С потолком: join ограничен собственными таймаутами задания, а у скачивания артефакта это
    // десять минут. Без ограничения удаление видео, качающегося прямо сейчас, молча висело бы всё
    // это время. Не дождались — идём стирать по тому, что записано; хуже от этого не будет, а
    // каталог задания всё равно сносится целиком.
    withTimeoutOrNull(CANCEL_WAIT_MS) { running.remove(jobId)?.cancelAndJoin() }

    // Список путей читается ПОСЛЕ join: до него он мог быть ещё пустым.
    val job = _jobs.value.firstOrNull { it.id == jobId } ?: return emptyList()

    val failed = withContext(Dispatchers.IO + NonCancellable) {
      val stuck = job.artifacts.filter { path ->
        val file = File(path)
        // Файла уже нет — это успех удаления, а не отказ: цель достигнута.
        file.exists() && !runCatching { file.delete() }.getOrDefault(false)
      }
      // Каталог задания сносим ЦЕЛИКОМ и по id, а не по списку путей.
      //
      // Список путей записывается только после того, как ArtifactStore.save отработал полностью.
      // Отменённое на середине задание оставляет на диске уже записанные файлы, а в job.artifacts
      // при этом пусто — и удаление по списку не находило ничего. Получались «файлы без записи,
      // безымянным мусором», ровно то, что KDoc этого метода объявляет недопустимым. Плюс от
      // упавшего задания оставался пустой каталог.
      //
      // Имя каталога выводится из id тем же способом, что при записи, так что задеть чужие файлы
      // эта строка не может.
      runCatching { artifacts.dirFor(jobId).deleteRecursively() }
      stuck
    }

    // update, а не «прочитал снимок — записал»: задание из этой же пачки может завершиться прямо
    // сейчас в потоке прогресса, и запись по устаревшему списку потеряла бы его DONE вместе с путём
    // к уже оплаченному файлу и стоимостью. Ровно та же причина, что у cancel() выше.
    //
    // Снятие записи идёт СРАЗУ за удалением файлов и вместе с ним защищено от отмены: между этими
    // двумя шагами и живёт то самое «полдела» — файлов нет, карточка есть.
    withContext(Dispatchers.IO + NonCancellable) {
      _jobs.update { list -> list.filterNot { it.id == jobId } }
      // Пишем СИНХРОННО, а не через persist(): тот кидает задачу в persistScope, а close() при
      // закрытии окна успевает сохранить ещё дореальный список и погасить этот scope. NonCancellable
      // защищает нас, но не его — запись просто терялась, и на следующем запуске оставалась карточка
      // со ссылками на уже стёртые файлы. store.save под @Synchronized, так что гонки с потоком
      // записи здесь нет — но и порядка @Synchronized не даёт: задача, прочитавшая список до нашего
      // update, может выиграть монитор после нас и вернуть удалённое. Окно микроскопическое и
      // лечится любым следующим persist(); полностью закрывать его снимком внутри монитора здесь
      // дороже, чем оно стоит.
      runCatching { store.save(_jobs.value) }
    }
    return failed
  }

  /**
   * Догоняет задания из прошлого запуска. Асинхронное видео с сохранённым `pollingUrl` продолжаем
   * опрашивать; синхронное задание, прерванное на полпути, помечаем упавшим и НЕ перезапускаем сами —
   * повторный запрос стоил бы денег второй раз, это решение пользователя (кнопка «Повторить»).
   */
  fun resumeUnfinished() {
    for (job in _jobs.value) {
      if (job.status.isTerminal) continue
      val pollingUrl = job.pollingUrl
      if (pollingUrl != null) {
        mutate(job.id) { it.copy(status = JobStatus.POLLING, note = "догоняем задание после перезапуска") }
        launchJob(job.id) { provider.resume(pollingUrl) }
      } else {
        mutate(job.id) {
          it.copy(
            status = JobStatus.FAILED,
            error = "прервано закрытием приложения — нажми «Повторить»",
            note = null,
          )
        }
      }
    }
  }

  private fun launchJob(jobId: String, source: () -> kotlinx.coroutines.flow.Flow<GenerationProgress>) {
    // LAZY + явный start(): при обычном launch корутина стартует раньше, чем Job попадёт в running,
    // и cancel() в этом окне не нашёл бы, что отменять — задание помечалось бы отменённым, продолжая
    // работать и в итоге переписывая свой же статус на DONE.
    val job = scope.launch(start = CoroutineStart.LAZY) {
      // Пропуск держим только пока задание реально работает. Видео уходит в ожидание на минуты, и
      // withPermit на весь поток означал бы, что два видео-задания запирают очередь целиком: пока
      // они опрашивают провайдера раз в пять секунд, ни одна картинка не сгенерируется.
      var permitHeld = false
      var pollPermitHeld = false
      try {
        semaphore.acquire()
        permitHeld = true
        // Догоняемое видео уже в POLLING и с нотой «догоняем после перезапуска» — не сбиваем
        // его на RUNNING, иначе экран первые секунды врёт про то, что происходит.
        mutate(jobId) {
          if (it.status.isTerminal || it.status == JobStatus.POLLING) null
          else it.copy(status = JobStatus.RUNNING, note = null)
        }
        source().collect { progress ->
          apply(jobId, progress)
          if (permitHeld && progress.isWaitingOnProvider) {
            // Дальше только опросы — работу продолжает провайдер, не мы. Уступаем рабочий слот и
            // пересаживаемся на слот опроса: без пределов совсем толпа видео забила бы rate-limit.
            permitHeld = false
            semaphore.release()
            pollSemaphore.acquire()
            pollPermitHeld = true
          }
        }
      } catch (e: CancellationException) {
        // Отмена — это действие пользователя (или закрытие приложения), а не отказ провайдера:
        // статус уже выставлен в cancel(), перетирать его ошибкой нельзя.
        throw e
      } catch (e: Exception) {
        mutate(jobId) {
          if (it.status.isTerminal) null
          else it.copy(status = JobStatus.FAILED, error = e.message ?: "неизвестная ошибка", note = null)
        }
      } finally {
        if (permitHeld) semaphore.release()
        if (pollPermitHeld) pollSemaphore.release()
        running.remove(jobId)
      }
    }
    running[jobId] = job
    job.start()
  }

  /**
   * Все переходы идут через [mutate], который читает и пишет задание в одной атомарной операции.
   * Отменённое задание больше не оживает: терминальный статус побеждает любой поздний прогресс.
   */
  private suspend fun apply(jobId: String, progress: GenerationProgress) {
    when (progress) {
      is GenerationProgress.Accepted -> mutate(jobId) { job ->
        if (job.status.isTerminal) return@mutate null
        job.copy(
          status = if (progress.pollingUrl != null) JobStatus.POLLING else JobStatus.RUNNING,
          providerJobId = progress.providerJobId ?: job.providerJobId,
          pollingUrl = progress.pollingUrl ?: job.pollingUrl,
        )
      }

      is GenerationProgress.Status -> mutate(jobId) { job ->
        if (job.status.isTerminal) null else job.copy(status = JobStatus.POLLING, note = progress.note)
      }

      is GenerationProgress.Completed -> {
        mutate(jobId) { if (it.status.isTerminal) null else it.copy(note = "сохраняем файлы") }
        val saved = runCatching { artifacts.save(jobId, progress.result.outputs) }
        mutate(jobId) { job ->
          // Пользователь мог отменить задание, пока писались файлы: воскрешать его в DONE нельзя.
          if (job.status.isTerminal) return@mutate null
          saved.fold(
            onSuccess = { pathList ->
              job.copy(
                status = JobStatus.DONE,
                artifacts = pathList.map { it.toString() },
                costUsd = progress.result.costUsd,
                note = null,
                error = null,
              )
            },
            onFailure = { e ->
              // Генерация удалась, а запись на диск нет — это разные беды, и врать «готово» нельзя.
              job.copy(
                status = JobStatus.FAILED,
                error = "результат получен, но не сохранён: ${e.message}",
                note = null,
              )
            },
          )
        }
      }

      is GenerationProgress.Failed -> mutate(jobId) { job ->
        if (job.status.isTerminal) null else job.copy(status = JobStatus.FAILED, error = progress.message, note = null)
      }
    }
  }

  /**
   * Атомарно меняет одно задание: [transform] получает АКТУАЛЬНОЕ состояние внутри CAS-цикла,
   * а не снимок, прочитанный вызывающим. Возврат null означает «ничего не менять» — так переходы,
   * опоздавшие к терминальному статусу, отбрасываются, а не затирают результат.
   */
  private fun mutate(jobId: String, transform: (GenerationJob) -> GenerationJob?) {
    var applied = false
    _jobs.update { existing ->
      // Сбрасываем на каждой итерации: при проигранном CAS lambda выполняется заново, и значение
      // от неудачной попытки не должно решать, писать ли историю на диск.
      applied = false
      val index = existing.indexOfFirst { it.id == jobId }
      if (index < 0) return@update existing
      val updated = transform(existing[index]) ?: return@update existing
      applied = true
      // Подрезаем и здесь: на момент ДОБАВЛЕНИЯ задания завершённых ещё нет вовсе (все свежие
      // в QUEUED), так что обрезка только на вставке никогда бы не срабатывала.
      trimHistory(existing.toMutableList().also { it[index] = updated })
    }
    if (applied) persist()
  }

  private fun upsert(job: GenerationJob) {
    // update, а не присваивание _jobs.value: при maxConcurrent > 1 два задания обновляют статус
    // одновременно, и «прочитал — изменил — записал» терял бы одно из обновлений.
    // Заменяем на месте, а не «выкинуть и добавить в конец»: иначе задание прыгало бы в конец
    // списка на каждом обновлении статуса, и история перетасовывалась бы на глазах у пользователя.
    _jobs.update { existing ->
      if (existing.any { it.id == job.id }) {
        existing.map { if (it.id == job.id) job else it }
      } else {
        trimHistory(existing + job)
      }
    }
    persist()
  }

  /**
   * Держит историю в разумных рамках: файл читается и переписывается целиком, поэтому расти без
   * предела он не может. Выкидываются только самые старые ЗАВЕРШЁННЫЕ задания — незавершённые
   * нельзя, среди них может быть видео с `polling_url`, которое ещё предстоит догнать.
   * Файлы результатов при этом остаются на диске: удалять чужие картинки за спиной приложение
   * не вправе.
   */
  private fun trimHistory(list: List<GenerationJob>): List<GenerationJob> {
    if (list.size <= maxHistory) return list
    val unfinishedCount = list.count { !it.status.isTerminal }
    val keepFinished = (maxHistory - unfinishedCount).coerceAtLeast(0)
    val finished = list.filter { it.status.isTerminal }
    if (finished.size <= keepFinished) return list
    val keptIds = finished.takeLast(keepFinished).mapTo(mutableSetOf()) { it.id }
    return list.filter { !it.status.isTerminal || it.id in keptIds }
  }

  private fun persist() {
    // Пишем именно текущее значение, а не снимок на момент вызова: записи выстроены в один поток,
    // и поздний писатель со старым снимком иначе откатил бы файл назад.
    persistScope.launch { runCatching { store.save(_jobs.value) } }
  }

  /**
   * Дописывает историю синхронно и гасит поток записи. Вызывать при закрытии приложения: `persist`
   * асинхронный, и без этого последний переход мог бы не долететь до диска.
   */
  fun close() {
    runCatching { store.save(_jobs.value) }
    persistScope.cancel()
  }

  private companion object {
    /** Больше двух параллельных генераций упираются в rate-limit провайдера, а не в скорость машины. */
    const val DEFAULT_MAX_CONCURRENT = 2

    /** Опрос дешевле генерации, поэтому предел выше — но он есть: раз в 5 с × 8 это меньше 2 rps. */
    const val DEFAULT_MAX_POLLING = 8

    /**
     * Сколько заданий держим в истории. Файл читается и переписывается целиком, поэтому расти
     * без предела он не может. Обрезаются только завершённые и самые старые; ФАЙЛЫ на диске
     * остаются — удаляет их пользователь сам.
     */
    const val MAX_HISTORY = 500

    /** Сколько ждём отмены работающего задания при удалении. */
    const val CANCEL_WAIT_MS = 3_000L
  }
}
