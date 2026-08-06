package com.aiopenpocket.genai

// @trace desktop-genai-client-4c63b6

import java.net.URI

internal const val OPENROUTER_HOST = "openrouter.ai"

/**
 * Свой ли это хост — единственный вопрос, который решает, уйдёт ли туда ключ.
 *
 * Жила эта проверка приватным методом в [ArtifactStore] и стерегла ровно один адрес — ссылку на
 * готовый файл из `unsigned_urls`. А ровно та же беда была у `polling_url`: он тоже приходит из
 * JSON провайдера, но вдобавок **сохраняется в `jobs.json` и переигрывается при следующем запуске**
 * (`resumeUnfinished`). То есть любой адрес, однажды попавший в историю — из битого ответа или из
 * файла, поправленного руками, — получал бы `Bearer sk-or-…` при каждом старте приложения.
 *
 * `java.net.URI`, а не разбор строки руками: `https://openrouter.ai@evil.example/x` для наивной
 * проверки «содержит openrouter.ai» выглядит своим, а ведёт на чужой хост.
 */
internal fun isOpenRouterHost(url: String): Boolean {
  val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return false
  return host == OPENROUTER_HOST || host.endsWith(".$OPENROUTER_HOST")
}
