package com.aiopenpocket.genai

// @trace desktop-genai-client-4c63b6

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.get
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Пример картинки для каждой модели — та самая витрина, что на сайте OpenRouter.
 *
 * **Это единственное место в приложении, которое ходит не в API, а в вёрстку.** Так вышло не от
 * хорошей жизни: в `/api/v1/models` картинок нет вовсе, `links` содержит только служебный
 * `/endpoints`, а адрес примера собрать из полей ответа нельзя — в пути стоит uuid, которого в API
 * нет ни в каком виде. Он есть только в HTML страницы модели.
 *
 * Отсюда все решения ниже:
 *
 * - **Кеш навсегда, а не на сутки.** Пример у модели не меняется, а каждый промах — это поход на
 *   чужой сайт за 350 КБ разметки ради одной ссылки. Скачали раз — больше не ходим.
 * - **Промах тоже запоминается**, но только в памяти процесса. Иначе список из девяти моделей
 *   дёргал бы сайт на каждой перерисовке, а записать «картинки нет» на диск навсегда нельзя: её
 *   может добавить и завтра.
 * - **Ошибка — это `null`, а не исключение и не заглушка.** Вёрстка чужая и поменяется без
 *   предупреждения; когда это случится, приложение обязано просто перестать показывать превью, а
 *   не сломаться и не подсунуть картинку от другой модели.
 *
 * Ходит ТОЛЬКО по требованию UI и только за теми моделями, которые видны на экране: обход всего
 * каталога заранее — это 338 запросов к сайту, которого мы не спрашивали.
 */
class ModelPreviews(
  engine: HttpClientEngine,
  private val paths: AppPaths = AppPaths(),
  private val siteUrl: String = SITE_URL,
) : AutoCloseable {

  private val httpClient = HttpClient(engine) {
    install(HttpTimeout) {
      connectTimeoutMillis = CONNECT_TIMEOUT_MS
      requestTimeoutMillis = REQUEST_TIMEOUT_MS
    }
    install(UserAgent) {
      // Представляемся по имени. Ходим не в API, а на сайт, и анонимный запрос от библиотеки —
      // это то, что владелец сайта справедливо блокирует, не имея возможности спросить.
      agent = USER_AGENT
    }
  }

  /**
   * Модели, у которых примера НЕТ. Сюда попадает только честный промах: страница отдалась, а
   * примера в ней нет. Отказ сети, 503 и таймаут сюда НЕ идут — иначе одна моргнувшая сеть
   * выключала бы превью до перезапуска приложения, и даже появившийся позже файл кеша не спас бы:
   * проверка промаха стоит раньше проверки кеша.
   */
  private val misses = ConcurrentHashMap.newKeySet<String>()

  /**
   * Загрузки, идущие прямо сейчас. Без этой карты одна модель качалась бы дважды: строки списка
   * составляются все разом, а фильтр в поиске выкидывает строку и возвращает её через секунду.
   *
   * Две параллельные загрузки одного примера — это ещё и гонка за один и тот же `.tmp`, ровно та,
   * что описана в `JobStore.save` и в `GenaiCatalogService.writeCache`: второй писатель не находит
   * своего временного файла, уходит в ветку «снести и переименовать» и уносит уже сохранённое.
   * Здесь она закрыта тем же способом, что и там, — единственностью писателя.
   */
  private val inFlight = ConcurrentHashMap<String, Deferred<File?>>()

  /**
   * Одновременных походов на сайт не больше трёх. Список из девяти моделей составляется целиком и
   * разом, то есть без этого ограничения открытие диалога — это девять параллельных запросов по
   * ~350 КБ разметки на чужой хост.
   */
  private val gate = Semaphore(MAX_PARALLEL_FETCHES)

  /**
   * Файл с примером для модели или `null`, если его нет. Готовый файл отдаётся сразу, новый
   * скачивается и кладётся в кеш.
   */
  suspend fun preview(modelId: String): File? {
    if (modelId.isBlank() || modelId in misses) return null

    val cached = cacheFileFor(modelId)
    if (cached.isFile && cached.length() > 0) return cached

    return coroutineScope {
      // computeIfAbsent, а не «проверил — положил»: соседние строки списка входят сюда
      // одновременно, и обе создали бы по своей загрузке.
      //
      // Запись снимается по завершению САМОЙ ЗАГРУЗКИ, а не в finally вокруг await. Разница
      // принципиальная: await отваливается по отмене ожидающего — а это штатный случай, строка
      // ушла с экрана из-за фильтра в поиске. Снимая запись там, мы выбрасывали из карты живую
      // загрузку, и возврат строки через секунду запускал ВТОРУЮ — то есть ровно ту гонку двух
      // писателей за один .tmp, которую карта и заведена закрывать. Кончалось это хуже, чем
      // ничего: первый переименовывал tmp в кеш, второй своим finally стирал tmp первого, не
      // находил его при переименовании, сносил уже готовый файл кеша — и не оставалось ни одного.
      val job = inFlight.computeIfAbsent(modelId) {
        async(Dispatchers.IO + NonCancellable) { fetch(modelId, cached) }
          .also { started -> started.invokeOnCompletion { inFlight.remove(modelId, started) } }
      }
      job.await()
    }
  }

  /**
   * `NonCancellable` у загрузки не прихоть: отмена приходит от Compose, когда строка ушла с экрана
   * (например, отфильтровалась поиском), а `writeBytes` всё равно доработает — блокирующий вызов
   * отмену не слышит. Дать корутине умереть между записью и переименованием значит оставить `.tmp`
   * в кеше навсегда. Дешевле дописать до конца: файл маленький, а результат ляжет в кеш и
   * пригодится, когда строка вернётся.
   */
  private suspend fun fetch(modelId: String, cached: File): File? = try {
    gate.withPermit {
      val page = httpClient.get("$siteUrl/$modelId")
      if (!page.status.isSuccess()) {
        null // Сеть, а не отсутствие примера: в misses не пишем, попробуем при следующем открытии.
      } else {
        val assetUrl = findPreviewUrl(page.body<String>(), modelId)
        if (assetUrl == null) {
          miss(modelId)
        } else {
          val asset = httpClient.get(assetUrl)
          // Размер смотрим ДО чтения тела: `body<ByteArray>()` буферизует целиком, и на гигантском
          // ассете это OutOfMemoryError — Error, а не Exception, он проходит мимо catch ниже.
          // Актуально не теоретически: если `thumb` на странице не нашёлся, берётся `original`,
          // а он бывает в мегабайты — и всё это ради коробки 56×34.
          val declared = asset.contentLength()
          when {
            !asset.status.isSuccess() -> null
            declared != null && declared > MAX_ASSET_BYTES -> null
            else -> {
              val bytes = asset.body<ByteArray>()
              if (bytes.isEmpty() || bytes.size > MAX_ASSET_BYTES) null else writeCache(cached, bytes)
            }
          }
        }
      }
    }
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    null
  }

  /** Пишем рядом и переименовываем: прямая запись оставила бы в вечном кеше вечный обрубок. */
  private fun writeCache(cached: File, bytes: ByteArray): File? {
    cached.parentFile?.mkdirs()
    val tmp = File(cached.parentFile, cached.name + ".tmp")
    return try {
      tmp.writeBytes(bytes)
      if (tmp.renameTo(cached)) {
        cached
      } else {
        cached.delete()
        if (tmp.renameTo(cached)) cached else null
      }
    } catch (e: Exception) {
      null
    } finally {
      runCatching { if (tmp.exists()) tmp.delete() }
    }
  }

  private fun miss(modelId: String): File? {
    misses += modelId
    return null
  }

  /**
   * Имя файла из слага. Слэш в имени файла не годится, но просто заменить его подчёркиванием
   * нельзя: `vendor/a_b` и `vendor_a/b` сошлись бы в один файл, и модель показала бы чужой пример.
   * Хвост из хеша исходного слага восстанавливает однозначность.
   */
  internal fun cacheFileFor(modelId: String): File =
    File(paths.modelPreviewsDir, UNSAFE_CHARS.replace(modelId, "_") + "_" + modelId.hashCode() + ".img")

  override fun close() {
    httpClient.close()
  }

  private companion object {
    const val SITE_URL = "https://openrouter.ai"
    const val CONNECT_TIMEOUT_MS = 10_000L
    const val REQUEST_TIMEOUT_MS = 20_000L
    const val MAX_PARALLEL_FETCHES = 3
    const val MAX_ASSET_BYTES = 4 * 1024 * 1024
    const val USER_AGENT = "AiOpenPocket-desktop (model preview fetcher)"
    val UNSAFE_CHARS = Regex("[^A-Za-z0-9._-]")
  }
}

/**
 * Достаёт из HTML страницы модели адрес ЕЁ примера.
 *
 * Отдельной чистой функцией — чтобы разбор чужой вёрстки был под тестом без сети: именно он сломается
 * первым, когда OpenRouter поменяет страницу.
 *
 * Ищем по хосту ассетов, а не по классам и не по структуре разметки: имена классов у сайта
 * генерируются сборщиком и меняются от релиза к релизу, а `model-assets.openrouter.ai` — это адрес
 * хранилища, он живёт дольше.
 *
 * **Слаг модели обязан быть в пути ассета.** Без этой проверки годился бы любой пример с этой
 * страницы — а на ней однажды появится блок «похожие модели» или карусель сравнения, и в кеш ляжет
 * чужая картинка. Кеш здесь вечный, то есть ошибка запеклась бы на диске навсегда. В пути стоит
 * канонический слаг с датой (`google/gemini-3.1-flash-image-20260528`), поэтому сравнение по
 * префиксу, а не на равенство.
 *
 * `thumb` предпочитается `original`: в строке списка нужен он, а оригинал бывает в мегабайты.
 */
internal fun findPreviewUrl(html: String, modelId: String): String? {
  // Слаг обязан кончиться там же, где кончается наш: дальше допускается только «/» или
  // канонический хвост с датой («-20260528/»). Голый contains пускал соседа —
  // `google/gemini-3.1-flash-image` ловил `…-image-preview-…`, а это половина картиночного
  // каталога, и в вечный кеш легла бы чужая картинка.
  val mine = PREVIEW_URL.findAll(html)
    .map { it.value }
    .distinct()
    .filter { Regex("/model-examples/" + Regex.escape(modelId) + """(-\d{6,8})?/""") in it }
    .toList()
  if (mine.isEmpty()) return null
  return mine.firstOrNull { it.contains("/thumb-") } ?: mine.first()
}

private val PREVIEW_URL =
  Regex("""https://model-assets\.openrouter\.ai/[A-Za-z0-9._/-]+\.(?:webp|jpe?g|png)""")
