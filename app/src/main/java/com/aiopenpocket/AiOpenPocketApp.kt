package com.aiopenpocket

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import com.aiopenpocket.di.AppGraph
import dev.zacsweers.metro.createGraphFactory

class AiOpenPocketApp : Application(), ImageLoaderFactory {

    lateinit var appGraph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        appGraph = createGraphFactory<AppGraph.Factory>().create(this)
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
