package com.aiopenpocket.agent.core.profile

// @trace agent-memory-05e545

import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.LlmClient
import com.aiopenpocket.agent.core.llm.SelectedLlmProvider
import com.aiopenpocket.agent.core.llm.StreamEvent
import com.aiopenpocket.agent.core.memory.model.UserProfile
import com.aiopenpocket.i18n.tr
import dev.zacsweers.metro.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * An onboarding domain operation: extracts a structured [UserProfile] from the user's free-form story
 * about themselves using the LLM. Moved out of OnboardingViewModel so presentation knows nothing about
 * the prompt, the model's output format and the fragility of parsing it (markdown fences, garbage
 * around the JSON).
 *
 * Contract:
 * - throws [IllegalStateException] if the model's stream ended with an error and no text arrived;
 * - returns `null` if no JSON object could be found/parsed in the answer (the profile was not
 *   extracted);
 * - otherwise a profile with already trimmed fields.
 */
@Inject
class ExtractUserProfileUseCase(
  private val llmClient: LlmClient,
  // The story about oneself is the most personal text in the app, and it too must go to the chosen
  // vendor, not to whatever was the default in LlmClient.streamChat.
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

    // Fields are read as a primitive's literal string: the model often returns age as a number (30),
    // although the profile stores it as a string. JsonPrimitive.content gives "30" for both a number
    // and a string — a strict decode into a data class would fail here and silently lose the whole
    // profile.
    return UserProfile(
      name = obj.field("name"),
      preferredAddress = obj.field("preferredAddress"),
      age = obj.field("age").let(::validAgeOrBlank),
      // lowercase: the model sometimes returns "Мужской" (Male) capitalized — a case-insensitive match does
      // not lose a valid gender (the set stores lowercase).
      gender = obj.field("gender").lowercase().let { if (it in VALID_GENDERS) it else "" },
      religion = obj.field("religion"),
      extraInfo = obj.field("extraInfo"),
    )
  }

  private fun JsonObject.field(key: String): String =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.trim().orEmpty()

  /**
   * The model sometimes mixes up the age (of a third person mentioned in the story) or hallucinates a
   * number — implausible values are dropped rather than saved into a profile that later goes into
   * every future system prompt with no further check.
   */
  private fun validAgeOrBlank(raw: String): String {
    val n = raw.toIntOrNull() ?: return raw
    return if (n in MIN_PLAUSIBLE_AGE..MAX_PLAUSIBLE_AGE) raw else ""
  }

  /** Streams the model's answer and glues the text together. If there is no text but there was an error, it is rethrown. */
  private suspend fun collectExtractionText(userText: String): String {
    val buffer = StringBuilder()
    var errorMessage: String? = null
    llmClient.streamChat(
      messages = listOf(
        ChatMessage(role = "system", content = extractionSystemPrompt()),
        ChatMessage(role = "user", content = userText),
      ),
      tools = emptyList(),
      provider = selectedProvider(),
    ).collect { event ->
      when (event) {
        // The model's thinking is not a profile: a draft JSON in it would parse as a finished answer.
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
 * Cuts one JSON object out of the model's "dirty" answer. Models rarely return bare JSON: there may be a
 * markdown fence (```json … ```) or chatter around the object. A lenient parser does not save that, so we
 * clean up to the curly braces here. `internal` so it can be covered by unit tests.
 */
internal fun stripToJsonObject(raw: String): String? {
  val s = raw.trim()
  val fenced = FENCED_JSON.find(s)
  if (fenced != null) return fenced.groupValues[1].trim()
  return firstBalancedObject(s)
}

/**
 * Returns the first balanced {…} object, starting at the first `{`. Unlike the naive
 * indexOf('{')..lastIndexOf('}') it does not catch trailing chatter with braces ("…I hope {this}").
 * Braces inside string literals are ignored.
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

// Profile values are in the interface language: a person sees them (the profile, the preprompt), not only the model.
private fun extractionSystemPrompt(): String =
  EXTRACTION_SYSTEM_PROMPT.replace("%LANG%", tr("на русском", "на английском (in English)"))

private val EXTRACTION_SYSTEM_PROMPT = """
  Ты извлекаешь данные профиля пользователя из его свободного рассказа о себе.
  Верни СТРОГО один JSON-объект %LANG%, без пояснений и без markdown-обёрток.

  Поля (все строки; пустая строка, если пользователь не упомянул):
  - name: имя
  - preferredAddress: как к нему обращаться (имя, "ты", "вы")
  - age: возраст
  - gender: "мужской" | "женский" | ""
  - religion: религиозные предпочтения
  - extraInfo: одна строка с прочим контекстом — занятие, интересы, особенности

  Не выдумывай. Если поле не упомянуто — пустая строка. Ключи именно в camelCase.
""".trimIndent()
