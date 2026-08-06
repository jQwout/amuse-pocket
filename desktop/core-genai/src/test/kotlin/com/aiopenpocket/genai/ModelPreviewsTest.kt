package com.aiopenpocket.genai

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Разбор чужой вёрстки и кеш примеров.
 *
 * Ценность этих тестов в том, что они переживут смену страницы OpenRouter: когда разметка поменяется,
 * упадёт [`findPreviewUrl is anchored to the asset host`], а не пользователь, увидевший пустые
 * карточки и не понявший почему.
 */
class ModelPreviewsTest {

  private val realPageFragment = """
    <div class="sc-x1y2"><img alt="example"
      src="https://model-assets.openrouter.ai/model-examples/google/gemini-3.1-flash-image-20260528/eb1b93d2-f9c1-4650-92ba-2b4b1d080c20/thumb-0.webp"/>
    </div>
    <link rel="preload" as="image"
      href="https://model-assets.openrouter.ai/model-examples/google/gemini-3.1-flash-image-20260528/eb1b93d2-f9c1-4650-92ba-2b4b1d080c20/original-0.jpg"/>
  """.trimIndent()

  private val modelId = "google/gemini-3.1-flash-image"

  @Test
  fun `findPreviewUrl is anchored to the asset host, not to markup structure`() {
    val url = findPreviewUrl(realPageFragment, modelId)

    assertNotNull(url)
    // Именно thumb: в строке списка нужен он, а оригинал бывает в мегабайты.
    assertTrue(url.endsWith("thumb-0.webp"), url)
  }

  @Test
  fun `original is taken when there is no thumbnail`() {
    val html = """<img src="https://model-assets.openrouter.ai/model-examples/x/y/z/original-0.jpg">"""
    assertEquals(
      "https://model-assets.openrouter.ai/model-examples/x/y/z/original-0.jpg",
      findPreviewUrl(html, "x/y"),
    )
  }

  @Test
  fun `a page without examples yields nothing, not a guess`() {
    assertNull(findPreviewUrl("<html><body>никаких картинок</body></html>", modelId))
    // Чужие картинки с других хостов брать нельзя: это был бы пример не от этой модели.
    assertNull(findPreviewUrl("""<img src="https://example.com/model-examples/a/b/thumb-0.webp">""", "a/b"))
  }

  @Test
  fun `an example belonging to another model is not taken`() {
    // На странице однажды появится блок «похожие модели» — и без проверки слага в кеш этой модели
    // навсегда лёг бы чужой пример: кеш не протухает и не перепроверяется.
    val foreign = """
      <img src="https://model-assets.openrouter.ai/model-examples/openai/gpt-5-image/uuid/thumb-0.webp">
    """.trimIndent()

    assertNull(findPreviewUrl(foreign, modelId))
    assertNotNull(findPreviewUrl(foreign, "openai/gpt-5-image"))
  }

  @Test
  fun `a sibling model's example is not taken either`() {
    // Опасен именно СОСЕД, а не чужой вендор: половина картиночного каталога — это
    // gemini-3.1-flash-image против gemini-3.1-flash-image-preview. Голый contains пускал соседа.
    val sibling = """
      <img src="https://model-assets.openrouter.ai/model-examples/google/gemini-3.1-flash-image-preview-20260528/u/thumb-0.webp">
    """.trimIndent()

    assertNull(findPreviewUrl(sibling, "google/gemini-3.1-flash-image"))
    assertNotNull(findPreviewUrl(sibling, "google/gemini-3.1-flash-image-preview"))
    // При этом канонический хвост с датой у СВОЕЙ модели по-прежнему принимается.
    assertNotNull(findPreviewUrl(realPageFragment, "google/gemini-3.1-flash-image"))
  }

  @Test
  fun `a cancelled awaiter does not spawn a second download`() = runTest {
    // Штатный случай: строка ушла с экрана из-за фильтра в поиске и вернулась через секунду. Если
    // запись о загрузке снимать по уходу ОЖИДАЮЩЕГО, вторая загрузка подерётся с первой за один
    // .tmp — и обе останутся ни с чем.
    val home = createTempDirectory("previews").toFile()
    var pageHits = 0
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val engine = MockEngine { request ->
      if (request.url.host == "model-assets.openrouter.ai") {
        respond(ByteReadChannel(byteArrayOf(9, 9, 9)), HttpStatusCode.OK)
      } else {
        pageHits++
        started.complete(Unit)
        release.await()
        respond(
          ByteReadChannel(realPageFragment.toByteArray()),
          HttpStatusCode.OK,
          headersOf(HttpHeaders.ContentType, "text/html"),
        )
      }
    }
    val previews = ModelPreviews(engine, AppPaths(home = home, env = { null }))

    val abandoned = launch { previews.preview(modelId) }
    started.await()
    abandoned.cancelAndJoin()

    release.complete(Unit)
    val file = previews.preview(modelId)

    assertNotNull(file, "загрузка, начатая брошенным ожидающим, обязана дойти до кеша")
    assertEquals(1, pageHits, "вторая загрузка той же модели не должна стартовать")
  }

  @Test
  fun `preview downloads once and is served from cache afterwards`() = runTest {
    val home = createTempDirectory("previews").toFile()
    var pageHits = 0
    val engine = MockEngine { request ->
      if (request.url.host == "model-assets.openrouter.ai") {
        respond(ByteReadChannel(byteArrayOf(1, 2, 3, 4)), HttpStatusCode.OK)
      } else {
        pageHits++
        respond(
          ByteReadChannel(realPageFragment.toByteArray()),
          HttpStatusCode.OK,
          headersOf(HttpHeaders.ContentType, "text/html"),
        )
      }
    }
    val previews = ModelPreviews(engine, AppPaths(home = home, env = { null }))

    val first = previews.preview("google/gemini-3.1-flash-image")
    val second = previews.preview("google/gemini-3.1-flash-image")

    assertNotNull(first)
    assertEquals(first, second)
    assertTrue(first.isFile && first.length() == 4L)
    assertEquals(1, pageHits, "второй вызов обязан брать файл из кеша, а не идти на сайт снова")
  }

  @Test
  fun `a broken page is a null, and it is not retried on every repaint`() = runTest {
    val home = createTempDirectory("previews").toFile()
    var hits = 0
    val engine = MockEngine {
      hits++
      respond(ByteReadChannel("<html>вёрстка поменялась</html>".toByteArray()), HttpStatusCode.OK)
    }
    val previews = ModelPreviews(engine, AppPaths(home = home, env = { null }))

    assertNull(previews.preview("vendor/model"))
    assertNull(previews.preview("vendor/model"))

    assertEquals(1, hits, "промах помнится в памяти: список из девяти моделей иначе долбил бы сайт")
  }

  @Test
  fun `site errors degrade to no preview instead of throwing`() = runTest {
    val home = createTempDirectory("previews").toFile()
    val previews = ModelPreviews(
      MockEngine { respondError(HttpStatusCode.ServiceUnavailable) },
      AppPaths(home = home, env = { null }),
    )

    assertNull(previews.preview("vendor/model"))
  }

  @Test
  fun `a transient failure is not remembered as a permanent miss`() = runTest {
    // Разница между «примера нет» и «сеть моргнула» принципиальна: промах помнится до конца работы
    // приложения, и записать туда 503 значит выключить превью этой модели до перезапуска.
    val home = createTempDirectory("previews").toFile()
    var attempt = 0
    val engine = MockEngine { request ->
      when {
        request.url.host == "model-assets.openrouter.ai" ->
          respond(ByteReadChannel(byteArrayOf(7, 7, 7)), HttpStatusCode.OK)
        attempt++ == 0 -> respondError(HttpStatusCode.ServiceUnavailable)
        else -> respond(
          ByteReadChannel(realPageFragment.toByteArray()),
          HttpStatusCode.OK,
          headersOf(HttpHeaders.ContentType, "text/html"),
        )
      }
    }
    val previews = ModelPreviews(engine, AppPaths(home = home, env = { null }))

    assertNull(previews.preview(modelId), "первый заход упёрся в 503")
    assertNotNull(previews.preview(modelId), "второй обязан попробовать снова, а не молчать до перезапуска")
  }

  @Test
  fun `cache file name survives a slug with slashes`() {
    val home = createTempDirectory("previews").toFile()
    val previews = ModelPreviews(MockEngine { respondError(HttpStatusCode.NotFound) }, AppPaths(home, { null }))

    val file = previews.cacheFileFor("black-forest-labs/flux.2-pro")

    assertTrue('/' !in file.name && '\\' !in file.name, file.name)
    // Просто выкинуть разделитель было бы нельзя: `openai/gpt-5` и `openai-gpt/5` сошлись бы в один
    // файл, и модель показывала бы чужой пример.
    assertTrue(
      previews.cacheFileFor("openai/gpt-5") != previews.cacheFileFor("openai-gpt/5"),
      "разные слаги не должны сходиться в один файл кеша",
    )
    assertEquals(File(home, ".local/share/aiopenpocket/model-previews").absolutePath, file.parentFile.absolutePath)
  }
}
