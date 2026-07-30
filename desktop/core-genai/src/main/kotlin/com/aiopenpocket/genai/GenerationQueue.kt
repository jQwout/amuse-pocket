package com.aiopenpocket.genai

// @trace desktop-genai-client-4c63b6

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
  private val clock: () -> Long = System::currentTimeMillis,
  private val newId: () -> String = { UUID.randomUUID().toString().take(8) },
) {

  private val semaphore = Semaphore(maxConcurrent)

  // ConcurrentHashMap, а не mutableMapOf: submit зовётся из UI-потока, а снятие записи —
  // из finally корутины на Dispatchers.Default. Обычная HashMap на этом ломается молча.
  private val running = ConcurrentHashMap<String, Job>()

  /** Записи в файл истории сериализуются: параллельные задания иначе пишут поверх друг друга. */
  private val storeLock = Any()

  private val _jobs = MutableStateFlow(store.load())
  val jobs: StateFlow<List<GenerationJob>> = _jobs.asStateFlow()

  fun submit(request: GenerationRequest): String = submitOne(request, batchId = null)

  /**
   * Несколько вариантов одного промпта. Модели генеративны, и разброс между прогонами — часть
   * работы: проще отобрать из четырёх, чем жать «Повторить» четыре раза и терять предыдущие.
   */
  fun submitBatch(request: GenerationRequest, count: Int): List<String> {
    require(count >= 1) { "count must be >= 1, was $count" }
    if (count == 1) return listOf(submit(request))
    val batchId = newId()
    return List(count) { submitOne(request, batchId) }
  }

  /**
   * Один промпт по нескольким моделям — сравнение бок о бок. Пустой список моделей означал бы
   * «ничего не запускать», а не «взять модель из запроса», поэтому это ошибка вызова.
   */
  fun submitAcrossModels(request: GenerationRequest, models: List<String>): List<String> {
    require(models.isNotEmpty()) { "models must not be empty" }
    if (models.size == 1) return listOf(submit(request.copy(model = models.single())))
    val batchId = newId()
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
    val job = _jobs.value.firstOrNull { it.id == jobId } ?: return
    if (!job.status.isTerminal) {
      upsert(job.copy(status = JobStatus.CANCELLED, note = null))
    }
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
        upsert(job.copy(status = JobStatus.POLLING, note = "догоняем задание после перезапуска"))
        launchJob(job.id) { provider.resume(pollingUrl) }
      } else {
        upsert(
          job.copy(
            status = JobStatus.FAILED,
            error = "прервано закрытием приложения — нажми «Повторить»",
            note = null,
          ),
        )
      }
    }
  }

  private fun launchJob(jobId: String, source: () -> kotlinx.coroutines.flow.Flow<GenerationProgress>) {
    running[jobId] = scope.launch {
      try {
        semaphore.withPermit {
          current(jobId)?.let { upsert(it.copy(status = JobStatus.RUNNING, note = null)) }
          source().collect { progress -> apply(jobId, progress) }
        }
      } catch (e: CancellationException) {
        // Отмена — это действие пользователя (или закрытие приложения), а не отказ провайдера:
        // статус уже выставлен в cancel(), перетирать его ошибкой нельзя.
        throw e
      } catch (e: Exception) {
        current(jobId)?.let {
          upsert(it.copy(status = JobStatus.FAILED, error = e.message ?: "неизвестная ошибка", note = null))
        }
      } finally {
        running.remove(jobId)
      }
    }
  }

  private suspend fun apply(jobId: String, progress: GenerationProgress) {
    val job = current(jobId) ?: return
    when (progress) {
      is GenerationProgress.Accepted -> upsert(
        job.copy(
          status = if (progress.pollingUrl != null) JobStatus.POLLING else JobStatus.RUNNING,
          providerJobId = progress.providerJobId ?: job.providerJobId,
          pollingUrl = progress.pollingUrl ?: job.pollingUrl,
        ),
      )

      is GenerationProgress.Status -> upsert(job.copy(status = JobStatus.POLLING, note = progress.note))

      is GenerationProgress.Completed -> {
        upsert(job.copy(note = "сохраняем файлы"))
        val saved = runCatching { artifacts.save(jobId, progress.result.outputs) }
        val fresh = current(jobId) ?: return
        upsert(
          saved.fold(
            onSuccess = { pathList ->
              fresh.copy(
                status = JobStatus.DONE,
                artifacts = pathList.map { it.toString() },
                costUsd = progress.result.costUsd,
                note = null,
                error = null,
              )
            },
            onFailure = { e ->
              // Генерация удалась, а запись на диск нет — это разные беды, и врать «готово» нельзя.
              fresh.copy(
                status = JobStatus.FAILED,
                error = "результат получен, но не сохранён: ${e.message}",
                note = null,
              )
            },
          ),
        )
      }

      is GenerationProgress.Failed -> upsert(
        job.copy(status = JobStatus.FAILED, error = progress.message, note = null),
      )
    }
  }

  private fun current(jobId: String): GenerationJob? = _jobs.value.firstOrNull { it.id == jobId }

  private fun upsert(job: GenerationJob) {
    // update, а не присваивание _jobs.value: при maxConcurrent > 1 два задания обновляют статус
    // одновременно, и «прочитал — изменил — записал» терял бы одно из обновлений.
    // Заменяем на месте, а не «выкинуть и добавить в конец»: иначе задание прыгало бы в конец
    // списка на каждом обновлении статуса, и история перетасовывалась бы на глазах у пользователя.
    _jobs.update { existing ->
      if (existing.any { it.id == job.id }) {
        existing.map { if (it.id == job.id) job else it }
      } else {
        existing + job
      }
    }
    persist()
  }

  private fun persist() {
    // Пишем именно текущее значение под локом, а не снимок на момент вызова: иначе поздний
    // писатель со старым снимком откатил бы файл назад.
    synchronized(storeLock) { runCatching { store.save(_jobs.value) } }
  }

  private companion object {
    /** Больше двух параллельных генераций упираются в rate-limit провайдера, а не в скорость машины. */
    const val DEFAULT_MAX_CONCURRENT = 2
  }
}
