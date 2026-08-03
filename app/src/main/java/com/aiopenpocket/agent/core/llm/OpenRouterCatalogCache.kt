package com.aiopenpocket.agent.core.llm

// @trace streaming-llm-chat-6595a1
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

import android.app.Application
import android.util.Log
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import java.io.File

/** Каталог, скачанный с OpenRouter, вместе со временем скачивания (для TTL). */
@Serializable
data class CachedModelCatalog(
  val fetchedAt: Long,
  val catalog: ModelCatalog,
)

/**
 * Кеш каталога моделей OpenRouter — ОДИН JSON-файл в `filesDir`, а не таблица в Room.
 *
 * Это документ, который заменяется целиком и никогда не запрашивается по частям: реляционного в нём
 * нет ни одного признака. Плюс цена ошибки: пока приложение до релиза, схема Room живёт на
 * `fallbackToDestructiveMigration`, и добавление таблицы РАДИ КЕША стоило бы пользователю всех
 * диалогов и агентов при обновлении. Файл такой цены не имеет.
 *
 * `filesDir`, а не `cacheDir`: систему никто не просил чистить это в середине сессии, а объём —
 * десятки килобайт. Потеря файла не страшна в любом случае — [ModelCatalogLoader] откатится на сид
 * из ассетов.
 */
@SingleIn(AppScope::class)
@Inject
class OpenRouterCatalogCache(
  private val application: Application,
) {

  @OptIn(ExperimentalSerializationApi::class)
  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    namingStrategy = JsonNamingStrategy.SnakeCase
  }

  private val file: File get() = File(application.filesDir, FILE_NAME)

  /** Кешированный каталог или null: файла нет, он битый или пустой. Битый — молча удаляем. */
  fun read(): CachedModelCatalog? {
    val f = file
    if (!f.exists()) return null
    return try {
      json.decodeFromString<CachedModelCatalog>(f.readText())
        .takeIf { it.catalog.models.isNotEmpty() }
    } catch (e: Exception) {
      // Битый кеш не должен превращаться в вечную ошибку: удаляем, чтобы следующий refresh переписал.
      Log.w(TAG, "Cached OpenRouter catalog is unreadable, dropping: ${e.message}")
      runCatching { f.delete() }
      null
    }
  }

  fun write(catalog: ModelCatalog, fetchedAt: Long) {
    try {
      file.writeText(json.encodeToString(CachedModelCatalog(fetchedAt, catalog)))
    } catch (e: Exception) {
      // Не смогли записать — не повод ронять ход: в следующий раз просто сходим в сеть снова.
      Log.w(TAG, "Failed to cache OpenRouter catalog: ${e.message}")
    }
  }

  private companion object {
    const val TAG = "OpenRouterCatalogCache"
    const val FILE_NAME = "openrouter_models.json"
  }
}
