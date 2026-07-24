package com.aiopenpocket.data

// @trace app-settings-beb470

import com.aiopenpocket.data.repository.SettingsRepository
import com.aiopenpocket.di.DatabaseBindings
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правила бэкапа ссылаются на имена файлов ЛИТЕРАЛАМИ — XML не умеет читать Kotlin-константу. Значит
 * переименование БД или файла настроек молча выключит исключение, и никто этого не заметит до
 * первого восстановления на новом устройстве. Этот тест и есть недостающая связь.
 *
 * Что именно должно быть исключено и почему — в комментариях самих XML. Коротко: `secure_settings`
 * зашифрован ключом из Keystore, который не бэкапится (восстановленная копия = гарантированный краш
 * при чтении API-ключа), а в базе лежит вся переписка и память агента открытым текстом.
 *
 * Оба файла проверяются вместе: minSdk 24, поэтому работает и `backup_rules.xml` (API < 31), и
 * `data_extraction_rules.xml` (31+) — расхождение между ними так же опасно, как их отсутствие.
 */
class BackupRulesTest {

  private fun rules(name: String): String {
    val file = File("src/main/res/xml/$name.xml")
    assertTrue("не найден $name.xml (ожидался по пути ${file.absolutePath})", file.exists())
    return file.readText()
  }

  private val legacyRules by lazy { rules("backup_rules") }
  private val modernRules by lazy { rules("data_extraction_rules") }

  @Test
  fun `encrypted settings are excluded on API 31 plus`() {
    assertTrue(
      "${SettingsRepository.PREFS_NAME} не исключён из data_extraction_rules",
      modernRules.contains("""path="${SettingsRepository.PREFS_NAME}.xml""""),
    )
  }

  @Test
  fun `encrypted settings are excluded below API 31 too`() {
    assertTrue(
      "${SettingsRepository.PREFS_NAME} не исключён из backup_rules",
      legacyRules.contains("""path="${SettingsRepository.PREFS_NAME}.xml""""),
    )
  }

  @Test
  fun `the database is excluded from cloud backup on API 31 plus`() {
    val cloudSection = modernRules.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")
    assertTrue(
      "${DatabaseBindings.DATABASE_NAME} не исключена из облачного бэкапа",
      cloudSection.contains("""path="${DatabaseBindings.DATABASE_NAME}""""),
    )
  }

  @Test
  fun `the database is excluded below API 31 too`() {
    assertTrue(
      "${DatabaseBindings.DATABASE_NAME} не исключена в backup_rules",
      legacyRules.contains("""path="${DatabaseBindings.DATABASE_NAME}""""),
    )
  }

  @Test
  fun `the encrypted settings exclusion also covers device transfer`() {
    // Keystore не переносится и при прямой миграции устройство-в-устройство, поэтому одного
    // cloud-backup мало: без этой ветки переезд на новый телефон давал бы тот же краш.
    val transferSection = modernRules.substringAfter("<device-transfer>").substringBefore("</device-transfer>")
    assertTrue(
      "${SettingsRepository.PREFS_NAME} не исключён из device-transfer",
      transferSection.contains("""path="${SettingsRepository.PREFS_NAME}.xml""""),
    )
  }
}
