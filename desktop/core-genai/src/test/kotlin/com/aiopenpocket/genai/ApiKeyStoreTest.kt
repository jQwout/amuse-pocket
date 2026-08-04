package com.aiopenpocket.genai

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Права проверяются через `java.nio` — это ТЕСТ, он живёт только на JVM. Сам `ApiKeyStore`
 * сознательно обходится `java.io.File`: модуль едет в общий код с `:app`, где minSdk 24.
 */
class ApiKeyStoreTest {

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
