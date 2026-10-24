package com.aiopenpocket.agent.core.lesson

// @trace protsedurnye-animatsii-v-urokah-68b2bf

/**
 * Сцены шага "animation", которые приложение умеет рисовать. Анимация не видеофайл, а код на
 * Compose: несколько килобайт вместо мегабайт в APK, работает без сети, подстраивается под экран и
 * может быть интерактивной (ползунок температуры).
 *
 * Один список на рендер и на проверку уроков: шаг с неизвестной сценой показал бы заглушку
 * вместо урока, и LessonAssetsValidationTest ловит это до сборки.
 */
val LESSON_ANIMATION_SCENES: Set<String> = setOf("tokens", "temperature")
