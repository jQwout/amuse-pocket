package com.aiopenpocket.genai

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Очередь во всех тестах получает scope самого теста, а НЕ backgroundScope: advanceUntilIdle()
// сознательно не ждёт фоновые корутины (иначе вечный фон вешал бы тест), и задания так и
// оставались бы в QUEUED.
//
// advanceUntilIdle всё ещё @ExperimentalCoroutinesApi — единственная причина opt-in здесь.
@OptIn(ExperimentalCoroutinesApi::class)
class GenerationQueueTest {

  private val imageRequest =
    GenerationRequest(GenerationModality.IMAGE, "кот в скафандре", "black-forest-labs/flux.2-pro")

  private class FakeProvider(
    private val script: List<GenerationProgress> = emptyList(),
    private val onResume: List<GenerationProgress> = emptyList(),
  ) : GenerationProvider {
    override val id = "fake"
    val resumedUrls = mutableListOf<String>()

    override fun supports(modality: GenerationModality) = true
    override suspend fun checkAccess(): AccessCheck = AccessCheck.Ok()
    override fun generate(request: GenerationRequest): Flow<GenerationProgress> = script.asFlow()
    override fun resume(pollingUrl: String): Flow<GenerationProgress> {
      resumedUrls += pollingUrl
      return onResume.asFlow()
    }
  }

  private fun fixture(home: File): Pair<AppPaths, ArtifactStore> {
    val paths = AppPaths(home = home, env = { null })
    // Байтовые результаты не качаются по сети — движок здесь только чтобы собрать ArtifactStore.
    val engine = MockEngine { _ ->
      respond(content = ByteReadChannel(byteArrayOf(1, 2, 3)), status = HttpStatusCode.OK)
    }
    return paths to ArtifactStore(engine = engine, apiKey = { "sk-test" }, paths = paths)
  }

  @Test
  fun `finished image lands on disk, in DONE status and in history`() = runTest {
    val home = createTempDirectory("genai-queue").toFile()
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
    val file = File(job.artifacts.single())
    assertTrue(file.isFile, "файл результата не создан: $file")
    assertEquals("картинка", file.readText())
    assertTrue(file.toString().endsWith(".png"), file.toString())
    // История переживает перезапуск: она уже на диске, а не только в памяти. Запись асинхронная,
    // поэтому дожимаем её close() — так же, как это делает приложение при закрытии окна.
    queue.close()
    assertEquals(JobStatus.DONE, store.load().single().status)
  }

  @Test
  fun `provider refusal is not reported as success`() = runTest {
    val home = createTempDirectory("genai-queue").toFile()
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
    val home = createTempDirectory("genai-queue").toFile()
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
    val home = createTempDirectory("genai-queue").toFile()
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
    val home = createTempDirectory("genai-queue").toFile()
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
    val home = createTempDirectory("genai-queue").toFile()
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

  /**
   * Видео ждёт провайдера минутами. Если очередь держит пропуск на всё это время, два видео-задания
   * при `maxConcurrent = 2` запирают её целиком — картинки не идут вообще.
   */
  @Test
  fun `jobs waiting on the provider do not hold up the queue`() = runTest {
    val home = createTempDirectory("genai-queue").toFile()
    val (paths, artifacts) = fixture(home)

    val videoRequest = GenerationRequest(GenerationModality.VIDEO, "пляж", "google/veo-3.1-fast")
    val provider = object : GenerationProvider {
      override val id = "waiting"
      override fun supports(modality: GenerationModality) = true
      override suspend fun checkAccess(): AccessCheck = AccessCheck.Ok()
      override fun resume(pollingUrl: String): Flow<GenerationProgress> = emptyFlow()

      override fun generate(request: GenerationRequest): Flow<GenerationProgress> =
        if (request.modality == GenerationModality.VIDEO) {
          flow {
            emit(GenerationProgress.Accepted(providerJobId = "v", pollingUrl = "https://x/v"))
            emit(GenerationProgress.Status("ждём провайдера"))
            // Провайдер «думает» бесконечно: задание живо, но нашей работы в нём нет.
            awaitCancellation()
          }
        } else {
          flowOf(
            GenerationProgress.Completed(
              GenerationResult(outputs = listOf(GenerationOutput.Bytes(byteArrayOf(1), "image/png"))),
            ),
          )
        }
    }

    val queue = GenerationQueue(
      provider = provider,
      artifacts = artifacts,
      store = JobStore(paths),
      scope = this@runTest,
      maxConcurrent = 2,
    )

    // Оба слота заняты «висящими» видео...
    queue.submitBatch(videoRequest, count = 2)
    advanceUntilIdle()
    // ...и картинка обязана всё равно пройти.
    val imageId = queue.submit(imageRequest)
    advanceUntilIdle()

    val image = queue.jobs.value.single { it.id == imageId }
    assertEquals(JobStatus.DONE, image.status, "картинка не прошла — видео-задания заперли очередь")
    assertTrue(queue.jobs.value.filter { it.request.modality == GenerationModality.VIDEO }
      .all { it.status == JobStatus.POLLING })

    queue.jobs.value.forEach { queue.cancel(it.id) }
  }

  @Test
  fun `history is capped, and only finished jobs get dropped`() = runTest {
    val home = createTempDirectory("genai-queue").toFile()
    val (paths, artifacts) = fixture(home)
    val store = JobStore(paths)

    // Незавершённое видео с polling_url — его выбрасывать нельзя ни при каком переполнении.
    store.save(
      listOf(
        GenerationJob(
          id = "vid-alive",
          request = GenerationRequest(GenerationModality.VIDEO, "пляж", "veo"),
          createdAtEpochMs = 1L,
          status = JobStatus.POLLING,
          pollingUrl = "https://x/v",
        ),
      ),
    )

    var next = 0
    val queue = GenerationQueue(
      provider = FakeProvider(listOf(GenerationProgress.Failed("неважно"))),
      artifacts = artifacts,
      store = store,
      scope = this@runTest,
      newId = { "job${next++}" },
      maxHistory = 5,
    )

    repeat(10) { queue.submit(imageRequest) }
    advanceUntilIdle()

    val jobs = queue.jobs.value
    assertTrue(jobs.size <= 6, "история не подрезалась: ${jobs.size}")
    assertTrue(jobs.any { it.id == "vid-alive" }, "выброшено незавершённое видео — потеряли polling_url")
    // Остались самые свежие завершённые, а не самые старые.
    assertTrue(jobs.any { it.id == "job9" }, "свежие задания не сохранились")
    assertTrue(jobs.none { it.id == "job0" }, "самое старое завершённое должно было уйти")
  }

  @Test
  fun `batch runs one job per variant under a shared batch id`() = runTest {
    val home = createTempDirectory("genai-queue").toFile()
    val (paths, artifacts) = fixture(home)
    var next = 0
    val provider = FakeProvider(
      listOf(
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
      newId = { "id${next++}" },
    )

    val ids = queue.submitBatch(imageRequest, count = 4)
    advanceUntilIdle()

    assertEquals(4, ids.size)
    val jobs = queue.jobs.value
    assertEquals(4, jobs.size)
    assertEquals(1, jobs.mapNotNull { it.batchId }.distinct().size, "вся пачка должна делить один batchId")
    assertTrue(jobs.all { it.status == JobStatus.DONE })
    // Файлы лежат по своим папкам — четыре варианта не должны затирать друг друга.
    assertEquals(4, jobs.flatMap { it.artifacts }.distinct().size)
  }

  @Test
  fun `single-variant batch stays an ordinary job without a batch id`() = runTest {
    val home = createTempDirectory("genai-queue").toFile()
    val (paths, artifacts) = fixture(home)
    val provider = FakeProvider(listOf(GenerationProgress.Failed("неважно")))
    val queue = GenerationQueue(
      provider = provider,
      artifacts = artifacts,
      store = JobStore(paths),
      scope = this@runTest,
    )

    queue.submitBatch(imageRequest, count = 1)
    advanceUntilIdle()

    // Пачка из одного — это не пачка: иначе история группировала бы одиночные задания.
    assertNull(queue.jobs.value.single().batchId)
  }

  @Test
  fun `comparison sends the same prompt to every model`() = runTest {
    val home = createTempDirectory("genai-queue").toFile()
    val (paths, artifacts) = fixture(home)
    val provider = FakeProvider(listOf(GenerationProgress.Failed("неважно")))
    val queue = GenerationQueue(
      provider = provider,
      artifacts = artifacts,
      store = JobStore(paths),
      scope = this@runTest,
    )

    queue.submitAcrossModels(imageRequest, listOf("model-a", "model-b", "model-c"))
    advanceUntilIdle()

    val jobs = queue.jobs.value
    assertEquals(listOf("model-a", "model-b", "model-c"), jobs.map { it.request.model })
    assertTrue(jobs.all { it.request.prompt == imageRequest.prompt })
    assertEquals(1, jobs.mapNotNull { it.batchId }.distinct().size)
  }

  @Test
  fun `empty model list is a caller error, not a silent no-op`() = runTest {
    val home = createTempDirectory("genai-queue").toFile()
    val (paths, artifacts) = fixture(home)
    val queue = GenerationQueue(
      provider = FakeProvider(),
      artifacts = artifacts,
      store = JobStore(paths),
      scope = this@runTest,
    )

    assertFailsWith<IllegalArgumentException> { queue.submitAcrossModels(imageRequest, emptyList()) }
    assertFailsWith<IllegalArgumentException> { queue.submitBatch(imageRequest, count = 0) }
    assertTrue(queue.jobs.value.isEmpty())
  }
}
