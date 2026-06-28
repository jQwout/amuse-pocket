package com.aiopenpocket.agent.core.profile

// @trace agent-memory-05e545

import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.LlmClient
import com.aiopenpocket.agent.core.llm.StreamEvent
import com.aiopenpocket.agent.core.memory.model.UserProfile
import dev.zacsweers.metro.Inject
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
class ExtractUserProfileUseCase @Inject constructor(
  private val llmClient: LlmClient,
) {

  suspend operator fun invoke(aboutMe: String): UserProfile? {
    val raw = collectExtractionText(aboutMe)
    val jsonText = stripToJsonObject(raw) ?: return null
    val extracted = try {
      extractionJson.decodeFromString(ExtractedProfile.serializer(), jsonText)
    } catch (e: Exception) {
      return null
    }
    return UserProfile(
      name = extracted.name.trim(),
      preferredAddress = extracted.preferredAddress.trim(),
      age = extracted.age.trim(),
      gender = extracted.gender.trim(),
      religion = extracted.religion.trim(),
      extraInfo = extracted.extraInfo.trim(),
    )
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
    ).collect { event ->
      when (event) {
        is StreamEvent.TextDelta -> buffer.append(event.content)
        is StreamEvent.Error -> errorMessage = event.message
        else -> Unit
      }
    }
    if (buffer.isBlank() && errorMessage != null) {
      throw IllegalStateException(errorMessage)
    }
    return buffer.toString()
  }

  @Serializable
  private data class ExtractedProfile(
    val name: String = "",
    val preferredAddress: String = "",
    val age: String = "",
    val gender: String = "",
    val religion: String = "",
    val extraInfo: String = "",
  )
}

private val extractionJson = Json {
  ignoreUnknownKeys = true
  isLenient = true
}

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
  val start = s.indexOf('{')
  val end = s.lastIndexOf('}')
  if (start >= 0 && end > start) return s.substring(start, end + 1)
  return null
}

private val EXTRACTION_SYSTEM_PROMPT = """
  Ты извлекаешь данные профиля пользователя из его свободного рассказа о себе.
  Верни СТРОГО один JSON-объект на русском, без пояснений и без markdown-обёрток.

  Поля (все строки; пустая строка, если пользователь не упомянул):
  - name: имя
  - preferredAddress: как к нему обращаться (имя, "ты", "вы")
  - age: возраст числом
  - gender: "мужской" | "женский" | ""
  - religion: религиозные предпочтения
  - extraInfo: одна строка с прочим контекстом — занятие, интересы, особенности

  Не выдумывай. Если поле не упомянуто — пустая строка. Ключи именно в camelCase.
""".trimIndent()
