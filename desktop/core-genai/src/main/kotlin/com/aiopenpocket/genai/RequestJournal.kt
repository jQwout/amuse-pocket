package com.aiopenpocket.genai

// @trace desktop-genai-client-4c63b6

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Одна запись журнала: что ушло к провайдеру и что он ответил.
 *
 * Тела здесь уже безопасные — прошли через [redactSecrets] и [elideBase64] на входе в журнал, а не
 * на выходе в UI. Разница принципиальная: чистить при показе значит держать в памяти сырое тело с
 * ключом и мегабайтами base64 и надеяться, что никто не покажет его мимо чистилки.
 */
data class JournalEntry(
  val id: String,
  val atEpochMs: Long,
  val modality: GenerationModality,
  val model: String,
  val method: String,
  val url: String,
  /** Тело запроса как оно ушло — с вырезанным ключом и свёрнутым base64. */
  val requestBody: String,
  /** HTTP-код. null — до провайдера не дошли вовсе (сеть, таймаут). */
  val status: Int? = null,
  /**
   * Тело ответа. У озвучки вместо него отметка о размере: ответ там — сырой mp3, и показывать его
   * текстом нечего.
   */
  val responseBody: String = "",
  val durationMs: Long = 0,
  val costUsd: Double? = null,
  /** Что пошло не так. null — запрос отработал. */
  val error: String? = null,
) {
  val failed: Boolean get() = error != null || (status != null && status !in 200..299)
}

/**
 * Журнал запросов — то, чем «в каком виде ушёл запрос» отвечается без гадания.
 *
 * Пишет САМ провайдер в четырёх местах, где ходит в сеть, а не плагин на транспорте. Причина
 * предметная: плагину пришлось бы читать все тела ответов, а у озвучки ответ — сырой mp3 на
 * мегабайты, и журнал буферизовал бы его целиком. Провайдер же держит разобранный ответ в руках и
 * вдобавок знает то, чего транспорт не знает: модальность, модель и списанную цену.
 */
interface RequestJournal {

  /** Последние записи, новые первыми. */
  val entries: StateFlow<List<JournalEntry>>

  fun record(entry: JournalEntry)

  fun clear()

  /**
   * Журнал, которого нет. Значение по умолчанию у провайдера и то, с чем идут тесты: писать
   * некуда, читать нечего.
   */
  object Off : RequestJournal {
    private val empty = MutableStateFlow<List<JournalEntry>>(emptyList())
    override val entries: StateFlow<List<JournalEntry>> = empty.asStateFlow()
    override fun record(entry: JournalEntry) = Unit
    override fun clear() = Unit
  }
}

/**
 * Журнал в памяти процесса — и только в ней.
 *
 * На диск не пишется намеренно: тела запросов это все промпты пользователя, и класть их вторым
 * экземпляром рядом с файлом ключа ради отладки не стоит. Закрыл окно — журнал чист.
 *
 * [enabled] выключает запись целиком и вычищает накопленное: выключенный журнал не должен хранить
 * ни строки, иначе «выключено» означало бы всего лишь «не показываем».
 *
 * Кольцо на [limit] записей: у пользователя нет причин листать позавчерашние запросы, а расти
 * бесконечно журнал не вправе — одно тело с описанием референсов это килобайты.
 */
class InMemoryRequestJournal(private val limit: Int = DEFAULT_LIMIT) : RequestJournal {

  private val _entries = MutableStateFlow<List<JournalEntry>>(emptyList())
  override val entries: StateFlow<List<JournalEntry>> = _entries.asStateFlow()

  @Volatile
  var enabled: Boolean = false
    set(value) {
      field = value
      if (!value) clear()
    }

  override fun record(entry: JournalEntry) {
    if (!enabled) return
    // Новые сверху: журнал открывают, чтобы посмотреть на последний запрос, а не на первый.
    _entries.value = (listOf(entry) + _entries.value).take(limit)
  }

  override fun clear() {
    _entries.value = emptyList()
  }

  private companion object {
    const val DEFAULT_LIMIT = 200
  }
}

/**
 * Вырезает ключ из текста.
 *
 * Единственное место, где журнал может проговориться, поэтому режем по подстроке `Bearer …`, а не
 * по известному значению ключа: значение приходит лямбдой и может смениться между запросом и
 * показом, а формат заголовка — нет. Плюс так же вычищается ключ, случайно попавший в тело ответа
 * (провайдеры любят возвращать эхо запроса в ошибках).
 */
fun redactSecrets(text: String): String = BEARER.replace(text, "Bearer ***")

/**
 * Сворачивает base64 в data:-URL до отметки о размере.
 *
 * Без этого одна запись с двумя референсами весит больше, чем вся остальная история: картинка на
 * 6 МБ превращается в 8 МБ base64 прямо в теле запроса. Читать такое невозможно, а держать в
 * памяти двести таких записей — тем более.
 *
 * Размер считаем по длине base64 (каждые 4 символа это 3 байта), а не по исходному файлу: в теле
 * запроса лежит именно закодированное, и честно назвать можно только его вес.
 */
fun elideBase64(text: String): String = DATA_URL.replace(text) { match ->
  val payload = match.groupValues[2]
  "data:${match.groupValues[1]};base64, ${humanBytes(payload.length.toLong() * 3 / 4)} свёрнуто"
}

/** Байты словами: 340 КБ, 2.4 МБ. */
fun humanBytes(bytes: Long): String = when {
  bytes >= 1024L * 1024 -> "%.1f МБ".format(java.util.Locale.ROOT, bytes / 1024.0 / 1024.0)
  bytes >= 1024 -> "${bytes / 1024} КБ"
  else -> "$bytes Б"
}

/** Готовит тело к попаданию в журнал: сперва ключ, потом base64. */
fun journalSafe(text: String): String = elideBase64(redactSecrets(text))

/**
 * JSON с отступами — для показа, а не для хранения.
 *
 * Провайдер шлёт и присылает одну длинную строку; в таком виде тело читается только горизонтальной
 * прокруткой, и всё, что не влезло в ширину, приходится искать вслепую — в том числе поле с
 * референсами, ради которого в журнал чаще всего и лезут.
 *
 * Не-JSON возвращается как есть: отметка про бинарный ответ, пустая строка и обрывок тела ошибки —
 * тоже содержимое журнала, и подменять их сообщением о неудачном разборе значило бы потерять
 * единственное, что провайдер сказал.
 */
fun prettyJson(text: String): String = runCatching {
  PRETTY.encodeToString(
    kotlinx.serialization.json.JsonElement.serializer(),
    PRETTY.parseToJsonElement(text),
  )
}.getOrDefault(text)

private val PRETTY = Json { prettyPrint = true }

private val BEARER = Regex("""Bearer\s+[A-Za-z0-9._\-]+""")

/**
 * `data:<mime>;base64,<payload>`. Payload ограничен алфавитом base64, поэтому совпадение кончится
 * на первой кавычке — то есть ровно на границе значения в JSON.
 */
private val DATA_URL = Regex("""data:([\w/+.\-]+);base64,([A-Za-z0-9+/=]+)""")
