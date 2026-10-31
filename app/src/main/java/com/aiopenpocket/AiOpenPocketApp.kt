package com.aiopenpocket

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
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
        val processStartedAt = System.currentTimeMillis()
        appGraph = createGraphFactory<AppGraph.Factory>().create(this)
        mirrorInterfaceLanguage()
        recoverPlans(processStartedAt)
    }

    /**
     * The plan engine's startup sweep: a step the previous process died in stays EXECUTING, and both
     * step selectors skip that status, so without this the plan never moved again on its own. See
     * PlanRecovery — it never re-runs a step, it only completes it on evidence or blocks it for review.
     * Best-effort: a failure here must not take the app start down with it.
     */
    private fun recoverPlans(processStartedAt: Long) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { appGraph.planRecovery.recover(startedBefore = processStartedAt) }
                .onFailure { Log.w("AiOpenPocketApp", "plan recovery failed", it) }
        }
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
     * The app's single Coil singleton: the SVG decoder and a SHARED image cache. Every AsyncImage
     * without an explicit imageLoader takes it via context.imageLoader — the cache survives a feed item
     * leaving the viewport (chat cards used to build their own ImageLoader per instance, and scrolling
     * back reloaded the image).
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(SvgDecoder.Factory()) }
        .build()
}
