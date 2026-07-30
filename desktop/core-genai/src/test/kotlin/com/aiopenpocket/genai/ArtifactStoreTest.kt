package com.aiopenpocket.genai

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArtifactStoreTest {

  private fun fixture(body: ByteArray = byteArrayOf(1, 2, 3)): Pair<ArtifactStore, AppPaths> {
    val paths = AppPaths(home = createTempDirectory("genai-artifacts"), env = { null })
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

    assertEquals(listOf("01.png", "02.mp3"), saved.map { it.fileName.toString() })
    assertEquals("картинка", Files.readString(saved.first()))
  }

  @Test
  fun `remote results are downloaded, not left as links`() = runTest {
    val (store, _) = fixture(body = byteArrayOf(7, 7, 7))
    val saved = store.save("job2", listOf(GenerationOutput.RemoteUrl("https://x/v.mp4", "video/mp4")))

    val file = saved.single()
    assertTrue(file.fileName.toString().endsWith(".mp4"))
    assertEquals(3, Files.size(file).toInt())
  }

  @Test
  fun `a failed download is an error, not a zero-byte file`() = runTest {
    val paths = AppPaths(home = createTempDirectory("genai-artifacts"), env = { null })
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

    val file = saved.single().toAbsolutePath().normalize()
    assertTrue(
      file.startsWith(paths.outputDir.toAbsolutePath().normalize()),
      "запись ушла за пределы папки результатов: $file",
    )
  }

  @Test
  fun `an unknown type still gets saved, just without guessing`() = runTest {
    val (store, _) = fixture()
    val saved = store.save("job4", listOf(GenerationOutput.Bytes(byteArrayOf(1), "application/weird")))

    assertTrue(saved.single().fileName.toString().endsWith(".bin"))
  }
}
