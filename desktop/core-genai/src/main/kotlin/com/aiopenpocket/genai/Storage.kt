package com.aiopenpocket.genai

// @trace desktop-genai-client-4c63b6

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories

/**
 * Куда приложение пишет на диск. XDG-переменные учитываются, потому что иначе на настроенной
 * машине конфиг лёг бы не туда, где его ищет пользователь.
 */
class AppPaths(
  private val home: Path = Path.of(System.getProperty("user.home")),
  private val env: (String) -> String? = System::getenv,
) {
  val configDir: Path get() = (env("XDG_CONFIG_HOME")?.let(Path::of) ?: home.resolve(".config")).resolve(APP_DIR)
  val dataDir: Path get() = (env("XDG_DATA_HOME")?.let(Path::of) ?: home.resolve(".local/share")).resolve(APP_DIR)

  /** Готовые файлы кладём на видное место: пользователь ищет их файловым менеджером, а не в ~/.local. */
  val outputDir: Path get() = home.resolve("AiOpenPocket").resolve("generations")

  val apiKeyFile: Path get() = configDir.resolve("openrouter.key")
  val jobsFile: Path get() = dataDir.resolve("jobs.json")
  val modelsOverrideFile: Path get() = configDir.resolve("genai-models.json")

  /** Свои заготовки промптов. В отличие от моделей, сливаются со встроенными, а не заменяют их. */
  val promptsUserFile: Path get() = configDir.resolve("genai-prompts.json")

  private companion object {
    const val APP_DIR = "aiopenpocket"
  }
}

/**
 * Ключ OpenRouter в файле с правами 600. EncryptedSharedPreferences из :app здесь нет, а кидать
 * ключ в переменную окружения или в git — хуже: файл под правами владельца — минимум, который
 * не врёт пользователю про «шифрование».
 */
class ApiKeyStore(private val paths: AppPaths = AppPaths()) {

  fun read(): String =
    runCatching { Files.readString(paths.apiKeyFile).trim() }.getOrDefault("")

  /**
   * Пишем во временный файл, СОЗДАННЫЙ сразу с правами 600, и только потом переносим на место.
   * Прямая запись в целевой файл создавала бы его под umask, и до `chmod` ключ был бы читаем
   * всем на машине — окно короткое, но совершенно ненужное.
   */
  fun write(key: String) {
    val file = paths.apiKeyFile
    file.parent.createDirectories()
    val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
    runCatching { Files.deleteIfExists(tmp) }
    // На не-POSIX ФС (Windows) прав в этом виде нет — там создаём как получится.
    val restricted = runCatching {
      Files.createFile(tmp, PosixFilePermissions.asFileAttribute(OWNER_ONLY))
    }.isSuccess
    if (!restricted) Files.createFile(tmp)
    Files.writeString(tmp, key.trim())
    // move сохраняет права источника, поэтому целевой файл получается уже закрытым.
    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
    restrictToOwner(file)
  }

  fun clear() {
    runCatching { Files.deleteIfExists(paths.apiKeyFile) }
  }

  private fun restrictToOwner(file: Path) {
    // На Windows POSIX-прав нет — тогда просто пропускаем, а не падаем.
    runCatching { Files.setPosixFilePermissions(file, OWNER_ONLY) }
  }

  private companion object {
    val OWNER_ONLY = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
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

  fun load(): List<GenerationJob> {
    val file = paths.jobsFile
    if (!Files.isRegularFile(file)) return emptyList()
    return runCatching { json.decodeFromString<List<GenerationJob>>(Files.readString(file)) }
      .getOrDefault(emptyList())
  }

  fun save(jobs: List<GenerationJob>) {
    val file = paths.jobsFile
    file.parent.createDirectories()
    // Пишем через временный файл: обрыв записи на середине не должен превращать историю в мусор.
    val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
    Files.writeString(tmp, json.encodeToString(jobs))
    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
  }
}
