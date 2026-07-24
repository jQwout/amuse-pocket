package com.aiopenpocket.agent.core.mcp

// @trace mcp-tools-f77d9b

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import dev.zacsweers.metro.Inject

@Inject
class ToolExecutor(
  private val registry: ToolRegistry,
) {
  suspend fun execute(name: String, arguments: Map<String, JsonElement>): ToolResult {
    val tool = registry.get(name)
      ?: return ToolResult.Error("Tool '$name' not found", recoverable = false)
    // Validate against the declared schema once here, so tools don't each re-check required/enum.
    SchemaValidator.validate(tool.schema, arguments)?.let { return it }
    return try {
      // Потолок по времени на КАЖДЫЙ вызов инструмента. Тулы ходят в ContentResolver, Room и
      // системные сервисы — залипший вызов держал бы весь ход агента: стрим не закрывается, статус
      // остаётся Running, спасает только внешняя отмена хода. Модель должна получить ошибку и иметь
      // возможность пойти дальше.
      //
      // withTimeoutOrNull, а не withTimeout: последний бросает TimeoutCancellationException, которая
      // сама является CancellationException — её поймала бы ветка ниже и пробросила наружу как
      // «отмену хода», то есть таймаут одного тула убил бы весь ход вместо того, чтобы вернуть Error.
      withTimeoutOrNull(TOOL_TIMEOUT_MS) { tool.execute(arguments) }
        ?: ToolResult.Error(
          "Tool '$name' timed out after ${TOOL_TIMEOUT_MS / 1000}s and was aborted.",
          recoverable = true,
        )
    } catch (e: CancellationException) {
      // Отмена запроса (юзер нажал Stop) — не глотаем, иначе цикл агента поедет дальше.
      throw e
    } catch (e: Exception) {
      ToolResult.Error("Execution failed: ${e.message}", recoverable = true)
    }
  }

  companion object {
    /**
     * Все инструменты здесь локальные (БД, ContentResolver, системные сервисы) — секунды им нужны,
     * а не десятки секунд. Потолок выбран так, чтобы заведомо не резать честный медленный вызов, но
     * и не превращать зависший в бесконечное ожидание с точки зрения пользователя.
     *
     * ОСТРЫЙ УГОЛ В ТЕСТАХ. `withTimeoutOrNull` считает по часам корутинного контекста, а `runTest`
     * даёт ВИРТУАЛЬНЫЕ часы. Если тул внутри теста уходит работать на чужой поток (типичный случай —
     * suspend-DAO Room на своём исполнителе), тестовый диспетчер остаётся без работы и промотывает
     * виртуальное время вперёд: тул, отрабатывающий за микросекунды, получает таймаут. Лечится НЕ
     * правкой этого числа, а посадкой Room на прямой исполнитель в самом тесте:
     * `.setQueryExecutor(Runnable::run).setTransactionExecutor(Runnable::run)` — см.
     * `CompositeSkillRegistryTest`, который на это и напоролся.
     */
    const val TOOL_TIMEOUT_MS = 30_000L
  }
}
