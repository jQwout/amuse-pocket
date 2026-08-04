package com.aiopenpocket.genai

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Права проверяются через `java.nio` — это ТЕСТ, он живёт только на JVM. Сам `ApiKeyStore`
 * сознательно обходится `java.io.File`: модуль едет в общий код с `:app`, где minSdk 24.
 *
 * Проверки прав идут только там, где у файловой системы есть POSIX-права. На NTFS их нет вовсе
 * (`supportedFileAttributeViews` = owner/dos/acl/basic/user), и `Files.getPosixFilePermissions`
 * там кидает `UnsupportedOperationException` — не «права разошлись», а «спрашивать нечего».
 * Гонять эти два теста на Windows означало бы красный прогон на ровном месте; пропуск с внятной
 * причиной честнее, чем ослабленное утверждение, которое пройдёт где угодно и не поймает ничего.
 * Сужение прав на Windows не работает и в проде — см. `ApiKeyStore.restrictToOwner`.
 */
class ApiKeyStoreTest {

  private val posixSupported: Boolean =
    "posix" in FileSystems.getDefault().supportedFileAttributeViews()

  private fun store(): Pair<ApiKeyStore, AppPaths> {
    val paths = AppPaths(home = createTempDirectory("genai-key").toFile(), env = { null })
    return ApiKeyStore(paths) to paths
  }

  private fun permissionsOf(paths: AppPaths): Set<PosixFilePermission> =
    Files.getPosixFilePermissions(paths.apiKeyFile.toPath())

  @Test
  fun `key survives a write-read round trip and is trimmed`() {
    val (keys, _) = store()
    keys.write("  sk-or-secret  ")
    assertEquals("sk-or-secret", keys.read())
  }

  @Test
  fun `key file is readable by its owner only`() {
    assumeTrue(posixSupported, "у файловой системы нет POSIX-прав — проверять нечего")
    val (keys, paths) = store()
    keys.write("sk-or-secret")

    val perms = permissionsOf(paths)
    assertEquals(
      setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
      perms,
      "ключ доступен не только владельцу: $perms",
    )
  }

  @Test
  fun `overwriting an existing key leaves no loosened permissions behind`() {
    assumeTrue(posixSupported, "у файловой системы нет POSIX-прав — проверять нечего")
    val (keys, paths) = store()
    keys.write("первый")
    // Кто-то (или прошлая версия приложения) распустил права — перезапись обязана их вернуть.
    paths.apiKeyFile.setReadable(true, false)

    keys.write("второй")

    assertEquals("второй", keys.read())
    val perms = permissionsOf(paths)
    assertTrue(PosixFilePermission.OTHERS_READ !in perms, "чужие всё ещё могут читать ключ: $perms")
    assertTrue(PosixFilePermission.GROUP_READ !in perms, "группа всё ещё может читать ключ: $perms")
  }

  @Test
  fun `no temp file is left next to the key`() {
    val (keys, paths) = store()
    keys.write("sk-or-secret")

    val leftovers = paths.configDir.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }
    assertTrue(leftovers.isEmpty(), "остался временный файл с ключом: $leftovers")
  }

  @Test
  fun `clear removes the key`() {
    val (keys, paths) = store()
    keys.write("sk-or-secret")
    keys.clear()

    assertEquals("", keys.read())
    assertTrue(!paths.apiKeyFile.exists())
  }
}
