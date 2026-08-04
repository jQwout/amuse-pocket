package com.aiopenpocket.genai

// @trace desktop-genai-client-4c63b6

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Куда приложение пишет на диск. XDG-переменные учитываются, потому что иначе на настроенной
 * машине конфиг лёг бы не туда, где его ищет пользователь.
 *
 * Везде [File], а НЕ `java.nio.file.Path`: этот модуль по плану переезжает в общий код с `:app`,
 * а `java.nio.file` появился на Android только в API 26 — при minSdk 24 и без десугаринга это
 * `NoClassDefFoundError`, то есть `Error` мимо всех catch. Ровно на этом уже обожглись в `:app`
 * (см. `OpenRouterCatalogCache`), и повторять в общем ядре тем более незачем.
 */
class AppPaths(
  private val home: File = File(System.getProperty("user.home")),
  private val env: (String) -> String? = System::getenv,
) {
  val configDir: File get() = File(env("XDG_CONFIG_HOME")?.let(::File) ?: File(home, ".config"), APP_DIR)
  val dataDir: File get() = File(env("XDG_DATA_HOME")?.let(::File) ?: File(home, ".local/share"), APP_DIR)

  /** Готовые файлы кладём на видное место: пользователь ищет их файловым менеджером, а не в ~/.local. */
  val outputDir: File get() = File(File(home, "AiOpenPocket"), "generations")

  val apiKeyFile: File get() = File(configDir, "openrouter.key")
  val jobsFile: File get() = File(dataDir, "jobs.json")
  val modelsOverrideFile: File get() = File(configDir, "genai-models.json")

  /**
   * Кеш живого каталога моделей. В [dataDir], а не в [configDir]: это не настройка пользователя,
   * а скачанное — руками его никто не правит, и потеря файла ничего не стоит.
   */
  val modelsCacheFile: File get() = File(dataDir, "genai-models-cache.json")

  /** Свои заготовки промптов. В отличие от моделей, сливаются со встроенными, а не заменяют их. */
  val promptsUserFile: File get() = File(configDir, "genai-prompts.json")

  private companion object {
    const val APP_DIR = "aiopenpocket"
  }
}

/**
 * Ключ OpenRouter в файле, доступном только владельцу. EncryptedSharedPreferences из :app здесь
 * нет, а кидать ключ в переменную окружения или в git — хуже: файл под правами владельца — минимум,
 * который не врёт пользователю про «шифрование».
 *
 * Оговорка про Windows: сужение прав там — best-effort и по факту не работает. `setReadable(false,
 * false)` на NTFS возвращает `false` и не делает ничего (POSIX-прав у неё нет вовсе), поэтому «600»
 * получается только на POSIX-системах. На Windows ключ защищён ровно настолько, насколько защищён
 * профиль пользователя: `%USERPROFILE%` по умолчанию закрыт ACL'ом от других учёток, но не от
 * администратора и не от SYSTEM. Обещать здесь «доступен только владельцу» кросс-платформенно было
 * бы враньём, поэтому обещание сужено до платформ, где оно выполняется.
 */
class ApiKeyStore(private val paths: AppPaths = AppPaths()) {

  fun read(): String = runCatching { paths.apiKeyFile.readText().trim() }.getOrDefault("")

  /**
   * Пишем во временный файл, у которого права сузили ДО записи ключа, и только потом переносим
   * на место. Прямая запись в целевой файл создавала бы его под umask, и до сужения прав ключ был
   * бы читаем всем на машине.
   *
   * Права через [File.setReadable]/[File.setWritable], а не `Files.setPosixFilePermissions`: те
   * из `java.nio.file` и на Android живут с API 26, а эти есть везде.
   *
   * Возвращает `true`, если права реально сузились до владельца, и `false`, если система этого не
   * умеет (Windows). Ключ в обоих случаях сохранён — вызывающий сам решает, предупреждать ли.
   */
  fun write(key: String): Boolean {
    val file = paths.apiKeyFile
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, file.name + ".tmp")
    runCatching { tmp.delete() }
    tmp.createNewFile()
    restrictToOwner(tmp)
    tmp.writeText(key.trim())
    if (!tmp.renameTo(file)) {
      // renameTo не перезаписывает существующий файл на части систем — сносим и повторяем.
      file.delete()
      if (!tmp.renameTo(file)) {
        tmp.delete()
        error("не удалось сохранить ключ в $file")
      }
    }
    return restrictToOwner(file)
  }

  fun clear() {
    runCatching { paths.apiKeyFile.delete() }
  }

  /**
   * Сужает права до владельца там, где файловая система это умеет. Возвращает `false`, если
   * платформа отказалась снимать чужой доступ — на Windows это норма, а не сбой, и поэтому запись
   * ключа из-за этого не падает: альтернатива «не сохранить ключ вообще» пользователю хуже.
   */
  private fun restrictToOwner(file: File): Boolean {
    // Сначала снимаем у всех, потом возвращаем владельцу: иначе групповое/чужое чтение осталось бы.
    // Первый вызов и есть показатель: на POSIX он true, на NTFS — false, и сузить права там нечем.
    val strippedFromOthers = file.setReadable(false, false)
    file.setWritable(false, false)
    file.setExecutable(false, false)
    file.setReadable(true, true)
    file.setWritable(true, true)
    return strippedFromOthers
  }
}

/**
 * История заданий на диске. Лежит в JSON, а не в памяти, ровно по той же причине, что статус хода
 * в :app лежит в БД: очередь должна переживать перезапуск, иначе оплаченное видео-задание теряется
 * вместе с окном.
 */
class JobStore(private val paths: AppPaths = AppPaths()) {

  private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
  }

  @Synchronized
  fun load(): List<GenerationJob> {
    val file = paths.jobsFile
    if (!file.isFile) return emptyList()
    return runCatching { json.decodeFromString<List<GenerationJob>>(file.readText()) }
      .getOrDefault(emptyList())
  }

  /**
   * [Synchronized] обязателен, а не «на всякий случай»: очередь пишет историю из своего потока
   * записи, а `GenerationQueue.close()` дописывает её синхронно из потока закрытия окна. Две
   * записи через один временный файл — и вторая не находит своего tmp, идёт в ветку
   * «удалить и переименовать» и сносит уже сохранённую историю. Ровно это и поймали тесты.
   */
  @Synchronized
  fun save(jobs: List<GenerationJob>) {
    val file = paths.jobsFile
    file.parentFile?.mkdirs()
    // Пишем рядом и переименовываем: прямая запись сначала обрезает файл до нуля, и читатель,
    // попавший в это окно, увидел бы обрубок вместо истории.
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.writeText(json.encodeToString(jobs))
    if (!tmp.renameTo(file)) {
      // renameTo не перезаписывает существующий файл на части систем (Windows). Вторая ветка
      // безопасна только потому, что сюда не может войти второй писатель.
      file.delete()
      if (!tmp.renameTo(file)) tmp.delete()
    }
  }
}
