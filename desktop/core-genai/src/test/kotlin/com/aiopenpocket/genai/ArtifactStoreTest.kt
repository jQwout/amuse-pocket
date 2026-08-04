package com.aiopenpocket.genai

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtifactStoreTest {

  private fun fixture(body: ByteArray = byteArrayOf(1, 2, 3)): Pair<ArtifactStore, AppPaths> {
    val paths = AppPaths(home = createTempDirectory("genai-artifacts").toFile(), env = { null })
    val engine = MockEngine { _ -> respond(content = ByteReadChannel(body), status = HttpStatusCode.OK) }
    return ArtifactStore(engine = engine, apiKey = { "sk-test" }, paths = paths) to paths
  }

  @Test
  fun `bytes are written with an extension matching their type`() = runTest {
    val (store, _) = fixture()
    val saved = store.save(
      "job1",
      listOf(
        GenerationOutput.Bytes("картинка".toByteArray(), "image/png"),
        GenerationOutput.Bytes(byteArrayOf(9), "audio/mpeg"),
      ),
    )

    assertEquals(listOf("01.png", "02.mp3"), saved.map { it.name })
    assertEquals("картинка", saved.first().readText())
  }

  @Test
  fun `remote results are downloaded, not left as links`() = runTest {
    val (store, _) = fixture(body = byteArrayOf(7, 7, 7))
    val saved = store.save("job2", listOf(GenerationOutput.RemoteUrl("https://x/v.mp4", "video/mp4")))

    val file = saved.single()
    assertTrue(file.name.endsWith(".mp4"))
    assertEquals(3, file.length().toInt())
  }

  @Test
  fun `a failed download is an error, not a zero-byte file`() = runTest {
    val paths = AppPaths(home = createTempDirectory("genai-artifacts").toFile(), env = { null })
    val engine = MockEngine { _ -> respondError(HttpStatusCode.Forbidden) }
    val store = ArtifactStore(engine = engine, apiKey = { "sk-test" }, paths = paths)

    assertFailsWith<OpenRouterHttpException> {
      store.save("job3", listOf(GenerationOutput.RemoteUrl("https://x/v.mp4", "video/mp4")))
    }
  }

  @Test
  fun `a hand-edited job id cannot write outside the results folder`() = runTest {
    val (store, paths) = fixture()
    // История правится руками — `..` в id не должен уводить запись за пределы папки результатов.
    val saved = store.save("../../escaped", listOf(GenerationOutput.Bytes(byteArrayOf(1), "image/png")))

    val file = saved.single().canonicalFile
    assertTrue(
      file.path.startsWith(paths.outputDir.canonicalFile.path),
      "запись ушла за пределы папки результатов: $file",
    )
  }

  @Test
  fun `the api key is not sent to hosts outside OpenRouter`() = runTest {
    // Ссылки на результат приходят из ответа провайдера. Слать Bearer-ключ на любой хост, который
    // назвали в JSON, — это отдавать ключ тому, кого мы не выбирали.
    val paths = AppPaths(home = createTempDirectory("genai-artifacts").toFile(), env = { null })
    val engine = MockEngine { respond(content = ByteReadChannel(byteArrayOf(1)), status = HttpStatusCode.OK) }
    val store = ArtifactStore(engine = engine, apiKey = { "sk-secret" }, paths = paths)

    store.save("job5", listOf(GenerationOutput.RemoteUrl("https://evil.example/v.mp4", "video/mp4")))

    val sent = engine.requestHistory.single().headers["Authorization"]
    assertNull(sent, "ключ уехал на чужой хост: $sent")
  }

  @Test
  fun `the api key is still sent to OpenRouter itself`() = runTest {
    // Content-ссылки OpenRouter авторизации требуют — сузить нужно ровно до чужих хостов.
    val paths = AppPaths(home = createTempDirectory("genai-artifacts").toFile(), env = { null })
    val engine = MockEngine { respond(content = ByteReadChannel(byteArrayOf(1)), status = HttpStatusCode.OK) }
    val store = ArtifactStore(engine = engine, apiKey = { "sk-secret" }, paths = paths)

    store.save("job6", listOf(GenerationOutput.RemoteUrl("https://openrouter.ai/v/1.mp4", "video/mp4")))

    assertEquals("Bearer sk-secret", engine.requestHistory.single().headers["Authorization"])
  }

  @Test
  fun `an unknown type still gets saved, just without guessing`() = runTest {
    val (store, _) = fixture()
    val saved = store.save("job4", listOf(GenerationOutput.Bytes(byteArrayOf(1), "application/weird")))

    assertTrue(saved.single().name.endsWith(".bin"))
  }
}
