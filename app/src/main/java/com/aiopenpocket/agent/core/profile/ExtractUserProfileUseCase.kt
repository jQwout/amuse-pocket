package com.aiopenpocket.agent.core.profile

// @trace agent-memory-05e545

import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.LlmClient
import com.aiopenpocket.agent.core.llm.SelectedLlmProvider
import com.aiopenpocket.agent.core.llm.StreamEvent
import com.aiopenpocket.agent.core.memory.model.UserProfile
import dev.zacsweers.metro.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Доменная операция онбординга: из свободного рассказа пользователя о себе извлекает структурный
 * [UserProfile] силами LLM. Вынесена из OnboardingViewModel, чтобы презентация не знала про промпт,
 * формат вывода модели и хрупкости его парсинга (markdown-заборы, мусор вокруг JSON).
 *
 * Контракт:
 * - бросает [IllegalStateException], если стрим модели завершился ошибкой и текста не пришло;
 * - возвращает `null`, если в ответе не удалось найти/разобрать JSON-объект (профиль не извлёкся);
 * - иначе — профиль с уже обрезанными (trim) полями.
 */
@Inject
class ExtractUserProfileUseCase(
  private val llmClient: LlmClient,
  // Рассказ о себе — самый личный текст в приложении, и он тоже обязан уходить выбранному
  // вендору, а не тому, что стоял дефолтом в LlmClient.streamChat.
  private val selectedProvider: SelectedLlmProvider,
) {

  suspend operator fun invoke(aboutMe: String): UserProfile? {
    val raw = collectExtractionText(aboutMe)
    val jsonText = stripToJsonObject(raw) ?: return null
    val obj = try {
      extractionJson.parseToJsonElement(jsonText) as? JsonObject
    } catch (e: Exception) {
      return null
    } ?: return null

    // Поля читаем как строку-литерал примитива: модель часто возвращает age числом (30), хотя
    // профиль хранит его строкой. JsonPrimitive.content даёт "30" и для числа, и для строки —
    // строгий decode в data class здесь падал бы и молча терял весь профиль.
    return UserProfile(
      name = obj.field("name"),
      preferredAddress = obj.field("preferredAddress"),
      age = obj.field("age").let(::validAgeOrBlank),
      // lowercase: модель иногда отдаёт «Мужской» с заглавной — регистронезависимый матч не теряет
      // валидный пол (множество хранит нижний регистр).
      gender = obj.field("gender").lowercase().let { if (it in VALID_GENDERS) it else "" },
      religion = obj.field("religion"),
      extraInfo = obj.field("extraInfo"),
    )
  }

  private fun JsonObject.field(key: String): String =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.trim().orEmpty()

  /**
   * Модель иногда путает возраст (упомянутого в рассказе третьего лица) или галлюцинирует число —
   * отбрасываем неправдоподобные значения, а не сохраняем их в профиль, который потом попадает в
   * каждый будущий системный промпт без дальнейшей проверки.
   */
  private fun validAgeOrBlank(raw: String): String {
    val n = raw.toIntOrNull() ?: return raw
    return if (n in MIN_PLAUSIBLE_AGE..MAX_PLAUSIBLE_AGE) raw else ""
  }

  /** Стримит ответ модели и склеивает текст. Если текста нет, но был error — пробрасываем его. */
  private suspend fun collectExtractionText(userText: String): String {
    val buffer = StringBuilder()
    var errorMessage: String? = null
    llmClient.streamChat(
      messages = listOf(
        ChatMessage(role = "system", content = EXTRACTION_SYSTEM_PROMPT),
        ChatMessage(role = "user", content = userText),
      ),
      tools = emptyList(),
      provider = selectedProvider(),
    ).collect { event ->
      when (event) {
        // Размышление модели — не профиль: черновой JSON в нём разобрался бы как готовый ответ.
        is StreamEvent.TextDelta -> if (!event.reasoning) buffer.append(event.content)
        is StreamEvent.Error -> errorMessage = event.message
        else -> Unit
      }
    }
    if (buffer.isBlank() && errorMessage != null) {
      throw IllegalStateException(errorMessage)
    }
    return buffer.toString()
  }
}

private val extractionJson = Json {
  ignoreUnknownKeys = true
  isLenient = true
}

private const val MIN_PLAUSIBLE_AGE = 1
private const val MAX_PLAUSIBLE_AGE = 119
private val VALID_GENDERS = setOf("", "мужской", "женский")

private val FENCED_JSON = Regex("""```(?:json)?\s*([\s\S]+?)\s*```""")

/**
 * Вырезает из «грязного» ответа модели один JSON-объект. Модели редко возвращают голый JSON:
 * бывает markdown-забор (```json … ```) или болтовня вокруг объекта. lenient-парсер это не спасает,
 * поэтому чистим до фигурных скобок здесь. `internal` — чтобы покрыть юнит-тестами.
 */
internal fun stripToJsonObject(raw: String): String? {
  val s = raw.trim()
  val fenced = FENCED_JSON.find(s)
  if (fenced != null) return fenced.groupValues[1].trim()
  return firstBalancedObject(s)
}

/**
 * Возвращает первый сбалансированный {…}-объект, начиная с первой `{`. В отличие от наивного
 * indexOf('{')..lastIndexOf('}') не цепляет хвостовую болтовню со скобками («…Надеюсь {это}»).
 * Скобки внутри строковых литералов игнорируются.
 */
private fun firstBalancedObject(s: String): String? {
  val start = s.indexOf('{')
  if (start < 0) return null
  var depth = 0
  var inString = false
  var escaped = false
  for (i in start until s.length) {
    val c = s[i]
    if (inString) {
      when {
        escaped -> escaped = false
        c == '\\' -> escaped = true
        c == '"' -> inString = false
      }
      continue
    }
    when (c) {
      '"' -> inString = true
      '{' -> depth++
      '}' -> {
        depth--
        if (depth == 0) return s.substring(start, i + 1)
      }
    }
  }
  return null
}

private val EXTRACTION_SYSTEM_PROMPT = """
  Ты извлекаешь данные профиля пользователя из его свободного рассказа о себе.
  Верни СТРОГО один JSON-объект на русском, без пояснений и без markdown-обёрток.

  Поля (все строки; пустая строка, если пользователь не упомянул):
  - name: имя
  - preferredAddress: как к нему обращаться (имя, "ты", "вы")
  - age: возраст
  - gender: "мужской" | "женский" | ""
  - religion: религиозные предпочтения
  - extraInfo: одна строка с прочим контекстом — занятие, интересы, особенности

  Не выдумывай. Если поле не упомянуто — пустая строка. Ключи именно в camelCase.
""".trimIndent()
