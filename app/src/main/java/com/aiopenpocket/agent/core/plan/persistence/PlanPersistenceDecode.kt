package com.aiopenpocket.agent.core.plan.persistence

// @trace structured-plan-engine-907066

import android.util.Log
import kotlinx.serialization.json.Json

/**
 * Shared resilient-decode helpers for the plan tables' entity -> domain mapping. Schema can drift with
 * NO Room migration (the DB baseline is a single v1 destructive baseline, but renaming an enum constant
 * or adding a required field to a `@Serializable` model changes no SQLite schema, so old rows stay put
 * and simply stop decoding). Two defenses:
 *
 * - [PlanPersistenceJson] tolerates unknown keys, so a row written by a NEWER app build (an added field)
 *   still reads on an OLDER build instead of throwing.
 * - [decodeQuarantining] drops a row that STILL will not decode (a renamed enum, a removed/renamed
 *   required field) rather than letting one bad row throw out of a whole list read — a Flow that
 *   `com.aiopenpocket.ui.chat.ChatViewModel` / `com.aiopenpocket.ui.plan.PlanListViewModel` /
 *   `com.aiopenpocket.ui.plan.PlanDetailViewModel` collect must degrade to a missing row, never crash
 *   the screen on every open.
 */
internal val PlanPersistenceJson = Json { ignoreUnknownKeys = true }

/**
 * Maps each row to its domain type, dropping (quarantining) any row whose [decode] throws. [decode] must
 * be a pure, non-suspending mapping (an entity's `toDomain()`), so wrapping it in `runCatching` can only
 * ever catch a real decode failure — never a coroutine `CancellationException` from suspending work.
 *
 * Каждая отброшенная строка ЛОГИРУЕТСЯ. Отбрасывать — правильно (иначе экран крэш-лупит на каждом
 * открытии), но делать это молча — нет: снаружи это выглядит как «плана просто нет» или «у плана
 * пропали шаги», и диагностировать оказывается нечего. В лог идёт сообщение исключения (оно называет
 * конкретную причину — например, нераспознанное значение enum), а не сама строка: в ней могут быть
 * пользовательские данные, которым в логах не место.
 */
internal inline fun <E, D> List<E>.decodeQuarantining(decode: (E) -> D): List<D> =
  mapNotNull { row ->
    runCatching { decode(row) }
      .onFailure { Log.w(QUARANTINE_TAG, "dropping an undecodable row: ${it.message}") }
      .getOrNull()
  }

@PublishedApi
internal const val QUARANTINE_TAG: String = "PlanPersistence"
