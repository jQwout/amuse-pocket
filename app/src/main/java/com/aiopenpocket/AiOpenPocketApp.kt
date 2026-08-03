package com.aiopenpocket

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.di.AppGraph
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
    }

    /**
     * Обновление каталога моделей OpenRouter на старте процесса (внутри — TTL в сутки, так что это
     * максимум один запрос в день).
     *
     * Раньше единственным местом, где каталог вообще обновлялся, был экран Настроек: тот, кто выбрал
     * OpenRouter и больше туда не заходил, навсегда оставался на списке той минуты — то есть ровно на
     * вшитом списке, от которого живой каталог и должен был увести.
     *
     * ЦЕЛИКОМ внутри корутины на IO, включая чтение самой настройки: провайдер лежит в
     * EncryptedSharedPreferences, а первое обращение к ним поднимает Android Keystore — на главном
     * потоке это и заметная задержка старта, и, там где Keystore недоступен, исключение прямо в
     * onCreate, то есть краш при запуске вместо одной неудачной настройки. runCatching по той же
     * причине: приложение не должно падать из-за списка моделей.
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
     * Единый Coil-синглтон приложения: SVG-декодер и ОБЩИЙ кэш картинок. Все AsyncImage без
     * явного imageLoader берут его через context.imageLoader — кэш переживает уход итема ленты
     * из вьюпорта (раньше карточки чата собирали свой ImageLoader на каждый экземпляр, и при
     * скролле назад картинка грузилась заново).
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(SvgDecoder.Factory()) }
        .build()
}
