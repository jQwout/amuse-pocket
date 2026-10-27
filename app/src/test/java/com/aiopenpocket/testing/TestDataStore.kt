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
 * A DataStore with ITS OWN file on every call — for tests.
 *
 * The production `preferencesDataStore` delegate keeps one instance per process, bound to the first
 * `Context` that accesses it; Robolectric meanwhile gives every test its own `filesDir`. The result is a
 * channel between tests shared across the whole run: one test's writes are visible to another, and the
 * behavior depends on the order. Hence failures that happen only in the full run (see the KDoc of
 * `UserPreferencesRepository`).
 */
fun testDataStore(context: Context): DataStore<Preferences> {
  // The name is computed RIGHT AWAY, not inside produceFile: DataStore calls produceFile lazily and may call
  // it again — then the "isolated" store would quietly move to another file.
  val file = File(context.filesDir, "test_prefs_${counter.incrementAndGet()}.preferences_pb")
  return PreferenceDataStoreFactory.create(
    scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    produceFile = { file },
  )
}
