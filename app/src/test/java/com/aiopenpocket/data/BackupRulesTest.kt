package com.aiopenpocket.data

// @trace app-settings-beb470

import com.aiopenpocket.agent.tools.sandboxRoot
import com.aiopenpocket.data.repository.SettingsRepository
import com.aiopenpocket.di.DatabaseBindings
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backup rules refer to file names as LITERALS — XML cannot read a Kotlin constant. So renaming the
 * database or the settings file silently turns the exclusion off, and nobody notices until the first
 * restore on a new device. This test is the missing link.
 *
 * What exactly must be excluded and why is in the comments of the XML files themselves. In short:
 * `secure_settings` is encrypted with a Keystore key that is not backed up (a restored copy = a guaranteed
 * crash on reading the API key), and the database holds all the conversations and the agent's memory in
 * plain text.
 *
 * Both files are checked together: minSdk is 24, so both `backup_rules.xml` (API < 31) and
 * `data_extraction_rules.xml` (31+) are in effect — a mismatch between them is as dangerous as their absence.
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

  /**
   * The file_tool sandboxes and the lesson images ("Draw it for me" is drawn from "about me") are personal
   * data just like the database. The agent folder names are taken from [sandboxRoot] so that a rename does
   * not silently turn the exclusion off; `lesson_images` is a private constant of the generator, hence a
   * literal.
   */
  @Test
  fun `agent files and lesson images are excluded from cloud backup on every API`() {
    val sandboxDirs = listOf(
      sandboxRoot(File("files"), agentId = null).name,
      sandboxRoot(File("files"), agentId = "any").parentFile!!.name,
    )
    val cloudSection = modernRules.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")
    for (dir in sandboxDirs + "lesson_images") {
      val rule = """<exclude domain="file" path="$dir"/>"""
      assertTrue("$dir не исключена из облачного бэкапа (API 31+)", cloudSection.contains(rule))
      assertTrue("$dir не исключена в backup_rules (API < 31)", legacyRules.contains(rule))
    }
  }

  @Test
  fun `the encrypted settings exclusion also covers device transfer`() {
    // The Keystore is not transferred even in a direct device-to-device migration, so cloud-backup alone is
    // not enough: without this branch moving to a new phone would give the same crash.
    val transferSection = modernRules.substringAfter("<device-transfer>").substringBefore("</device-transfer>")
    assertTrue(
      "${SettingsRepository.PREFS_NAME} не исключён из device-transfer",
      transferSection.contains("""path="${SettingsRepository.PREFS_NAME}.xml""""),
    )
  }
}
