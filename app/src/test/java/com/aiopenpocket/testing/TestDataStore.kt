package com.aiopenpocket.testing

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

private val counter = AtomicInteger(0)

/**
 * DataStore со СВОИМ файлом на каждый вызов — для тестов.
 *
 * Продовый делегат `preferencesDataStore` держит один экземпляр на процесс, привязанный к первому
 * обратившемуся `Context`; Robolectric при этом выдаёт каждому тесту свой `filesDir`. Получается
 * общий на весь прогон канал между тестами: записи одного видны другому, а поведение зависит от
 * порядка. Отсюда — падения, которые есть только в полном прогоне (см. KDoc
 * `UserPreferencesRepository`).
 */
fun testDataStore(context: Context): DataStore<Preferences> {
  // Имя считаем СРАЗУ, а не внутри produceFile: DataStore зовёт produceFile лениво и может позвать
  // повторно — тогда «изолированный» стор незаметно переехал бы на другой файл.
  val file = File(context.filesDir, "test_prefs_${counter.incrementAndGet()}.preferences_pb")
  return PreferenceDataStoreFactory.create(
    scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    produceFile = { file },
  )
}
