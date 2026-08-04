package com.aiopenpocket.genai

// @trace desktop-genai-client-4c63b6

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import java.io.File

@Serializable
data class PromptPreset(
  val id: String,
  val title: String,
  val prompt: String,
  /** null — пресет годится для любой модальности (стилевые приписки вроде «кинематографично»). */
  val modality: GenerationModality? = null,
  val tags: List<String> = emptyList(),
) {
  /**
   * Что подставить в поле промпта. Пресет с плейсхолдером [PLACEHOLDER] — это приписка: он
   * оборачивает уже набранный текст, а не затирает его. Без плейсхолдера это самостоятельная
   * заготовка и она заменяет содержимое.
   *
   * Раньше применялось всегда как замена, и встроенная «Приписка: чего избегать» стирала промпт,
   * хотя её же текст обещал обратное.
   */
  fun applyTo(current: String): String =
    if (PLACEHOLDER in prompt) prompt.replace(PLACEHOLDER, current.trim()) else prompt

  companion object {
    const val PLACEHOLDER = "{основной промпт}"
  }
}

/**
 * Заготовки промптов. В отличие от каталога моделей, пользовательский файл здесь НЕ заменяет
 * встроенный, а сливается с ним: встроенные пресеты — примеры формулировок, и терять их при
 * сохранении первого своего промпта незачем. Совпадение по `id` выигрывает у пользователя —
 * так встроенный пресет можно переопределить, не редактируя jar.
 */
@Serializable
data class PromptLibrary(val presets: List<PromptPreset> = emptyList()) {

  fun forModality(modality: GenerationModality): List<PromptPreset> =
    presets.filter { it.modality == null || it.modality == modality }

  companion object {
    private const val RESOURCE_PATH = "/genai/prompts.json"

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
      ignoreUnknownKeys = true
      isLenient = true
      prettyPrint = true
      encodeDefaults = true
      explicitNulls = false
      namingStrategy = JsonNamingStrategy.SnakeCase
    }

    fun load(userFile: File? = null): PromptLibrary {
      val builtIn = readResource()
      val user = userFile?.let(::readFile) ?: PromptLibrary()
      val userIds = user.presets.mapTo(mutableSetOf()) { it.id }
      return PromptLibrary(builtIn.presets.filterNot { it.id in userIds } + user.presets)
    }

    /** Только пользовательские пресеты — их и переписываем при сохранении. */
    fun loadUser(userFile: File): PromptLibrary = readFile(userFile) ?: PromptLibrary()

    /**
     * Добавляет пресет в пользовательский файл и возвращает объединённую библиотеку.
     * Пресет с таким же `id` перезаписывается — сохранение «того же самого» не плодит дубли.
     */
    fun saveUserPreset(userFile: File, preset: PromptPreset): PromptLibrary {
      val existing = loadUser(userFile).presets.filterNot { it.id == preset.id }
      writeFile(userFile, PromptLibrary(existing + preset))
      return load(userFile)
    }

    fun deleteUserPreset(userFile: File, id: String): PromptLibrary {
      val remaining = loadUser(userFile).presets.filterNot { it.id == id }
      writeFile(userFile, PromptLibrary(remaining))
      return load(userFile)
    }

    /**
     * Стабильный id из заголовка: сохранение пресета не должно зависеть от часов или счётчика,
     * иначе один и тот же промпт, сохранённый дважды, ляжет двумя записями.
     */
    fun idFor(title: String): String {
      val slug = title.trim().lowercase()
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .trim('-')
        .replace(Regex("-+"), "-")
        .take(40)
      return slug.ifBlank { "preset-" + title.hashCode().toUInt().toString(16) }
    }

    private fun readResource(): PromptLibrary {
      val text = PromptLibrary::class.java.getResourceAsStream(RESOURCE_PATH)
        ?.bufferedReader()?.use { it.readText() } ?: return PromptLibrary()
      return runCatching { json.decodeFromString<PromptLibrary>(text) }.getOrDefault(PromptLibrary())
    }

    private fun readFile(file: File): PromptLibrary? {
      if (!file.isFile) return null
      return runCatching { json.decodeFromString<PromptLibrary>(file.readText()) }.getOrNull()
    }

    private fun writeFile(file: File, library: PromptLibrary) {
      file.parentFile?.mkdirs()
      // Через временный файл — как история заданий: обрыв записи не должен съедать библиотеку.
      val tmp = File(file.parentFile, file.name + ".tmp")
      tmp.writeText(json.encodeToString(library))
      if (!tmp.renameTo(file)) {
        file.delete()
        if (!tmp.renameTo(file)) tmp.delete()
      }
    }
  }
}
