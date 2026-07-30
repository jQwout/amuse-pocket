package com.aiopenpocket.genai

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Единственный тест, который гоняет очередь на НАСТОЯЩИХ потоках (Dispatchers.Default), а не на
 * тестовом диспетчере. Остальные тесты однопоточные и гонку в `_jobs` увидеть не могут: там
 * «прочитал — изменил — записал» всегда успевает целиком.
 */
class GenerationQueueConcurrencyTest {

  private class InstantProvider : GenerationProvider {
    override val id = "instant"
    override fun supports(modality: GenerationModality) = true
    override suspend fun checkAccess(): AccessCheck = AccessCheck.Ok()
    override fun generate(request: GenerationRequest): Flow<GenerationProgress> = listOf(
      GenerationProgress.Accepted(),
      GenerationProgress.Status("шаг"),
      GenerationProgress.Completed(
        GenerationResult(outputs = listOf(GenerationOutput.Bytes(byteArrayOf(7), "image/png"))),
      ),
    ).asFlow()

    override fun resume(pollingUrl: String): Flow<GenerationProgress> = emptyList<GenerationProgress>().asFlow()
  }

  // Явный Unit обязателен: без него runBlocking делает возвращаемый тип Unit?, и JUnit 5 такой
  // метод молча не берёт в запуск — тест «проходит», ни разу не выполнившись.
  @Test
  fun `parallel jobs do not lose each other's status updates`(): Unit = runBlocking {
    val home = createTempDirectory("genai-concurrency")
    val paths = AppPaths(home = home, env = { null })
    val engine = MockEngine { _ ->
      respond(content = ByteReadChannel(byteArrayOf(1)), status = HttpStatusCode.OK)
    }
    val store = JobStore(paths)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val queue = GenerationQueue(
      provider = InstantProvider(),
      artifacts = ArtifactStore(engine = engine, apiKey = { "sk-test" }, paths = paths),
      store = store,
      scope = scope,
      // Выше дефолта специально: цель теста — столкнуть обновления статуса лбами.
      maxConcurrent = 16,
    )

    val count = 300
    repeat(count) { queue.submit(GenerationRequest(GenerationModality.IMAGE, "промпт $it", "модель")) }

    withTimeout(60_000) {
      queue.jobs.first { jobs -> jobs.size == count && jobs.all { it.status.isTerminal } }
    }

    val jobs = queue.jobs.value
    assertEquals(count, jobs.size, "часть заданий потерялась при параллельных обновлениях")
    assertEquals(count, jobs.map { it.id }.distinct().size, "id продублировались")
    assertTrue(jobs.all { it.status == JobStatus.DONE }, "не все задания дошли до DONE")
    // История на диске должна сойтись с памятью, а не остаться на промежуточном снимке.
    assertEquals(count, store.load().size, "файл истории отстал от состояния очереди")
    assertTrue(store.load().all { it.status == JobStatus.DONE })

    scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
  }

  /**
   * Страж инварианта: задание, дошедшее до терминального статуса, из него больше не выходит,
   * и DONE всегда несёт свои файлы.
   *
   * Честно про границы: конкретную гонку старого `cancel()` (прочитал снимок → записал поверх)
   * этот тест НЕ ловит — там между чтением и записью два соседних оператора, и попасть в окно
   * извне нечем. Проверено: на прежнем коде проходит 3 прогона из 3. Тест стоит здесь ради
   * другого — он поймает регрессию, где безусловная запись статуса вернётся в поток прогресса
   * (например, поздний Completed поверх отменённого задания), а такое окно уже широкое.
   */
  @Test
  fun `cancel never drags a finished job back out of its terminal state`(): Unit = runBlocking {
    val home = createTempDirectory("genai-cancel-race")
    val paths = AppPaths(home = home, env = { null })
    val engine = MockEngine { _ ->
      respond(content = ByteReadChannel(byteArrayOf(1)), status = HttpStatusCode.OK)
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val queue = GenerationQueue(
      provider = InstantProvider(),
      artifacts = ArtifactStore(engine = engine, apiKey = { "sk-test" }, paths = paths),
      store = JobStore(paths),
      scope = scope,
      maxConcurrent = 16,
    )

    // Пишем ВСЮ историю статусов каждого задания, а не только итог: откат виден только в переходах.
    val seen = ConcurrentHashMap<String, MutableList<JobStatus>>()
    val watcher = scope.launch {
      queue.jobs.collect { jobs ->
        for (job in jobs) {
          val track = seen.getOrPut(job.id) { Collections.synchronizedList(mutableListOf()) }
          synchronized(track) { if (track.lastOrNull() != job.status) track += job.status }
        }
      }
    }

    val count = 300
    val ids = List(count) { queue.submit(GenerationRequest(GenerationModality.IMAGE, "промпт $it", "модель")) }
    // Отменяем из отдельных корутин, чтобы отмена сталкивалась с завершением, а не шла заведомо
    // после него: последовательный вызов из этого потока почти всегда опаздывает.
    ids.map { id -> scope.launch { queue.cancel(id) } }.forEach { it.join() }

    withTimeout(60_000) {
      queue.jobs.first { jobs -> jobs.size == count && jobs.all { it.status.isTerminal } }
    }
    watcher.cancel()

    val reverted = seen.entries.mapNotNull { (id, track) ->
      val statuses = synchronized(track) { track.toList() }
      val firstTerminal = statuses.indexOfFirst { it.isTerminal }
      if (firstTerminal >= 0 && statuses.drop(firstTerminal).distinct().size > 1) "$id: $statuses" else null
    }
    assertTrue(reverted.isEmpty(), "терминальный статус откатился у ${reverted.size} заданий: ${reverted.take(3)}")

    // И согласованность итога: «готово» без файлов — тоже потеря результата.
    val done = queue.jobs.value.filter { it.status == JobStatus.DONE }
    assertTrue(done.all { it.artifacts.isNotEmpty() }, "есть DONE без артефактов")

    scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
  }
}
