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
 * Продовый делегат `preferencesDataStore` держит один экземпляр на процесс поверх одного файла, а
 * Robolectric даёт каждому тест-классу свой загрузчик классов: два класса создают по «своему» стору
 * над ОДНИМ файлом, не зная друг о друге, и упираются в блокировку. Проявляется это редкой
 * `UncompletedCoroutinesError` только в полном прогоне — то есть ровно там, где отладка дороже
 * всего.
 */
fun testDataStore(context: Context): DataStore<Preferences> =
  PreferenceDataStoreFactory.create(
    scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    produceFile = { File(context.filesDir, "test_prefs_${counter.incrementAndGet()}.preferences_pb") },
  )
