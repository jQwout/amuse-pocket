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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
}
