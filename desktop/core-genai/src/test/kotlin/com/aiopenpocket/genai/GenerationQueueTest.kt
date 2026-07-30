package com.aiopenpocket.genai

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// advanceUntilIdle всё ещё @ExperimentalCoroutinesApi — единственная причина opt-in здесь.
@OptIn(ExperimentalCoroutinesApi::class)
class GenerationQueueTest {

  // Очередь получает scope самого теста, а НЕ backgroundScope: advanceUntilIdle() сознательно не
  // ждёт фоновые корутины (иначе вечный фон вешал бы тест), и задания оставались бы в QUEUED.

  private val imageRequest =
    GenerationRequest(GenerationModality.IMAGE, "кот в скафандре", "black-forest-labs/flux.2-pro")

  private class FakeProvider(
    private val script: List<GenerationProgress> = emptyList(),
    private val onResume: List<GenerationProgress> = emptyList(),
  ) : GenerationProvider {
    override val id = "fake"
    val resumedUrls = mutableListOf<String>()

    override fun supports(modality: GenerationModality) = true
    override fun generate(request: GenerationRequest): Flow<GenerationProgress> = script.asFlow()
    override fun resume(pollingUrl: String): Flow<GenerationProgress> {
      resumedUrls += pollingUrl
      return onResume.asFlow()
    }
  }

  private fun fixture(home: Path): Pair<AppPaths, ArtifactStore> {
    val paths = AppPaths(home = home, env = { null })
    // Байтовые результаты не качаются по сети — движок здесь только чтобы собрать ArtifactStore.
    val engine = MockEngine { _ ->
      respond(content = ByteReadChannel(byteArrayOf(1, 2, 3)), status = HttpStatusCode.OK)
    }
    return paths to ArtifactStore(engine = engine, apiKey = { "sk-test" }, paths = paths)
  }

  @Test
  fun `finished image lands on disk, in DONE status and in history`() = runTest {
    val home = createTempDirectory("genai-queue")
    val (paths, artifacts) = fixture(home)
    val store = JobStore(paths)
    val provider = FakeProvider(
      listOf(
        GenerationProgress.Accepted(),
        GenerationProgress.Completed(
          GenerationResult(
            outputs = listOf(GenerationOutput.Bytes("картинка".toByteArray(), "image/png")),
            costUsd = 0.01,
          ),
        ),
      ),
    )
    val queue = GenerationQueue(
      provider = provider,
      artifacts = artifacts,
      store = store,
      scope = this@runTest,
      clock = { 1_000L },
      newId = { "job1" },
    )

    queue.submit(imageRequest)
    advanceUntilIdle()

    val job = queue.jobs.value.single()
    assertEquals(JobStatus.DONE, job.status)
    assertEquals(0.01, job.costUsd)
    assertNull(job.error)
    val file = Path.of(job.artifacts.single())
    assertTrue(Files.isRegularFile(file), "файл результата не создан: $file")
    assertEquals("картинка", Files.readString(file))
    assertTrue(file.toString().endsWith(".png"), file.toString())
    // История переживает перезапуск: она уже на диске, а не только в памяти.
    assertEquals(JobStatus.DONE, store.load().single().status)
  }

  @Test
  fun `provider refusal is not reported as success`() = runTest {
    val home = createTempDirectory("genai-queue")
    val (paths, artifacts) = fixture(home)
    val provider = FakeProvider(listOf(GenerationProgress.Failed("лимит запросов", code = "429")))
    val queue = GenerationQueue(
      provider = provider,
      artifacts = artifacts,
      store = JobStore(paths),
      scope = this@runTest,
      newId = { "job1" },
    )

    queue.submit(imageRequest)
    advanceUntilIdle()

    val job = queue.jobs.value.single()
    assertEquals(JobStatus.FAILED, job.status)
    assertEquals("лимит запросов", job.error)
    assertTrue(job.artifacts.isEmpty())
  }

  @Test
  fun `video job remembers polling url and is resumed after restart`() = runTest {
    val home = createTempDirectory("genai-queue")
    val (paths, artifacts) = fixture(home)
    val store = JobStore(paths)
    val pollingUrl = "https://openrouter.ai/api/v1/videos/abc"

    // Первый запуск: задание принято провайдером и осталось в POLLING (окно закрыли).
    store.save(
      listOf(
        GenerationJob(
          id = "vid1",
          request = GenerationRequest(GenerationModality.VIDEO, "пляж", "google/veo-3.1-fast"),
          createdAtEpochMs = 1_000L,
          status = JobStatus.POLLING,
          providerJobId = "abc",
          pollingUrl = pollingUrl,
        ),
      ),
    )

    val provider = FakeProvider(
      onResume = listOf(
        GenerationProgress.Completed(
          GenerationResult(outputs = listOf(GenerationOutput.Bytes(byteArrayOf(9), "video/mp4"))),
        ),
      ),
    )
    val queue = GenerationQueue(
      provider = provider,
      artifacts = artifacts,
      store = store,
      scope = this@runTest,
    )

    queue.resumeUnfinished()
    advanceUntilIdle()

    assertEquals(listOf(pollingUrl), provider.resumedUrls)
    val job = queue.jobs.value.single()
    assertEquals(JobStatus.DONE, job.status)
    assertTrue(job.artifacts.single().endsWith(".mp4"), job.artifacts.single())
  }

  @Test
  fun `interrupted sync job is not restarted on its own`() = runTest {
    val home = createTempDirectory("genai-queue")
    val (paths, artifacts) = fixture(home)
    val store = JobStore(paths)
    store.save(
      listOf(
        GenerationJob(
          id = "img1",
          request = imageRequest,
          createdAtEpochMs = 1_000L,
          status = JobStatus.RUNNING,
        ),
      ),
    )
    val provider = FakeProvider(listOf(GenerationProgress.Completed(GenerationResult(emptyList()))))
    val queue = GenerationQueue(
      provider = provider,
      artifacts = artifacts,
      store = store,
      scope = this@runTest,
    )

    queue.resumeUnfinished()
    advanceUntilIdle()

    val job = queue.jobs.value.single()
    // Повторный запрос стоит денег второй раз — решение за пользователем, не за очередью.
    assertEquals(JobStatus.FAILED, job.status)
    assertTrue(job.error!!.contains("Повторить"), job.error!!)
    assertTrue(provider.resumedUrls.isEmpty())
  }

  @Test
  fun `status update does not reshuffle history`() = runTest {
    val home = createTempDirectory("genai-queue")
    val (paths, artifacts) = fixture(home)
    var next = 0
    val provider = FakeProvider(
      listOf(
        GenerationProgress.Accepted(),
        GenerationProgress.Completed(
          GenerationResult(outputs = listOf(GenerationOutput.Bytes(byteArrayOf(1), "image/png"))),
        ),
      ),
    )
    val queue = GenerationQueue(
      provider = provider,
      artifacts = artifacts,
      store = JobStore(paths),
      scope = this@runTest,
      newId = { "job${next++}" },
    )

    queue.submit(imageRequest)
    queue.submit(imageRequest)
    advanceUntilIdle()

    assertEquals(listOf("job0", "job1"), queue.jobs.value.map { it.id })
  }

  @Test
  fun `retry creates a new job instead of overwriting the old one`() = runTest {
    val home = createTempDirectory("genai-queue")
    val (paths, artifacts) = fixture(home)
    var next = 0
    val provider = FakeProvider(listOf(GenerationProgress.Failed("лимит")))
    val queue = GenerationQueue(
      provider = provider,
      artifacts = artifacts,
      store = JobStore(paths),
      scope = this@runTest,
      newId = { "job${next++}" },
    )

    val first = queue.submit(imageRequest)
    advanceUntilIdle()
    val second = queue.retry(first)
    advanceUntilIdle()

    assertEquals("job1", second)
    assertEquals(2, queue.jobs.value.size)
    assertEquals(imageRequest.prompt, queue.jobs.value.last().request.prompt)
  }
}
