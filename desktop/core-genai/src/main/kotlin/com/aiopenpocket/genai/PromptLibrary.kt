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
  /**
   * Раздел в окне заготовок: «Предметка», «Портрет», «Приписки». Пусто — «Разное».
   *
   * Обычное поле, а не перечисление, потому что список категорий строится ИЗ библиотеки, а не задан
   * кодом: пользователь заводит свои заготовки в `genai-prompts.json` и вправе завести свою
   * категорию, не пересобирая приложение. Захардкоженный enum сделал бы её невидимой.
   */
  val category: String = "",
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

  /**
   * Приклеить к уже набранному, не затирая его, — кнопка «Дописать».
   *
   * У приписки (с [PLACEHOLDER]) это то же самое, что [applyTo]: она по своей природе оборачивает
   * набранное, и склеивать её через запятую значило бы получить текст с «{основной промпт}» внутри.
   * У самостоятельной заготовки — склейка через запятую, как пишут промпты руками.
   */
  fun appendTo(current: String): String = when {
    PLACEHOLDER in prompt -> applyTo(current)
    current.isBlank() -> prompt
    else -> current.trimEnd().trimEnd(',') + ", " + prompt
  }

  /**
   * Что в заготовке подставляется под кадр: `{объект}`, `{цвет фона}`. Без дубликатов и в порядке
   * появления — панель показывает их подсказкой «в фигурных скобках — то, что подставляется».
   *
   * [PLACEHOLDER] сюда не попадает: это не поле для заполнения, а место для уже набранного промпта,
   * и предлагать его заполнить руками — сбивать с толку.
   */
  val placeholders: List<String>
    get() = PLACEHOLDER_PATTERN.findAll(prompt)
      .map { it.value }
      .filterNot { it == PLACEHOLDER }
      .distinct()
      .toList()

  companion object {
    const val PLACEHOLDER = "{основной промпт}"

    /** Фигурные скобки без вложенности и без переводов строк — как их пишут в заготовках. */
    val PLACEHOLDER_PATTERN = Regex("""\{[^{}\n]+}""")
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
     *
     * Бросает, если файл существует, но не разбирается: см. [requireReadable].
     */
    fun saveUserPreset(userFile: File, preset: PromptPreset): PromptLibrary {
      val existing = requireReadable(userFile).presets.filterNot { it.id == preset.id }
      writeFile(userFile, PromptLibrary(existing + preset))
      return load(userFile)
    }

    fun deleteUserPreset(userFile: File, id: String): PromptLibrary {
      val remaining = requireReadable(userFile).presets.filterNot { it.id == id }
      writeFile(userFile, PromptLibrary(remaining))
      return load(userFile)
    }

    /**
     * Пресеты для ПЕРЕЗАПИСИ файла — с отказом, если файл есть, но не читается.
     *
     * Разница между «файла нет» и «файл сломан» здесь стоит всей библиотеки. Обе ситуации давали
     * пустой список, а сохранение пишет `существующие + новый` — то есть одна лишняя запятая в
     * `genai-prompts.json` превращала следующее «Сохранить» в стирание всех накопленных промптов,
     * молча и с видом успеха. У каталога моделей эта развилка разведена давно и осознанно
     * (`GenaiModelCatalog.load`: битый файл откатывается на встроенный список, а не на пустоту),
     * здесь её просто не было.
     */
    private fun requireReadable(userFile: File): PromptLibrary {
      if (!userFile.isFile) return PromptLibrary()
      return readFile(userFile)
        ?: error("файл заготовок $userFile не разбирается — правка перезаписала бы его пустым")
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
