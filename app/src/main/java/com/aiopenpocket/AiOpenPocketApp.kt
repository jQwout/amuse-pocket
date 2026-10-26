package com.aiopenpocket

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.di.AppGraph
import com.aiopenpocket.i18n.AppLanguage
import dev.zacsweers.metro.createGraphFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AiOpenPocketApp : Application(), ImageLoaderFactory {

    lateinit var appGraph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        appGraph = createGraphFactory<AppGraph.Factory>().create(this)
        refreshOpenRouterCatalog()
        mirrorInterfaceLanguage()
    }

    /**
     * The interface language for the whole process (see [AppLanguage]). Needed even without an
     * activity: a process started by WorkManager for a reminder writes the notification and tool
     * captions in the chosen language.
     */
    private fun mirrorInterfaceLanguage() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            appGraph.userPreferencesRepository.language.collect { AppLanguage.code = it }
        }
    }

    /**
     * Refreshes the OpenRouter model catalog when the process starts (with a one-day TTL inside, so it
     * is at most one request a day).
     *
     * The Settings screen used to be the only place where the catalog was refreshed at all: someone who
     * chose OpenRouter and never went back there stayed forever on the list of that minute — i.e.
     * exactly on the bundled list the live catalog was supposed to lead away from.
     *
     * ENTIRELY inside a coroutine on IO, including reading the setting itself: the provider lives in
     * EncryptedSharedPreferences, and the first access to them brings up the Android Keystore — on the
     * main thread that is both a noticeable startup delay and, where the Keystore is unavailable, an
     * exception right in onCreate, i.e. a crash on launch instead of one failed setting. runCatching
     * for the same reason: the app must not crash because of a model list.
     */
    private fun refreshOpenRouterCatalog() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                if (appGraph.selectedLlmProvider() == LlmProvider.OPENROUTER) {
                    appGraph.openRouterCatalogService.refreshIfStale()
                }
            }.onFailure { Log.w("AiOpenPocketApp", "Не удалось обновить каталог OpenRouter на старте", it) }
        }
    }

    /**
     * The app's single Coil singleton: the SVG decoder and a SHARED image cache. Every AsyncImage
     * without an explicit imageLoader takes it via context.imageLoader — the cache survives a feed item
     * leaving the viewport (chat cards used to build their own ImageLoader per instance, and scrolling
     * back reloaded the image).
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(SvgDecoder.Factory()) }
        .build()
}
