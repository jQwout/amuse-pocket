package com.aiopenpocket.ui.components.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Мягкая подложка ровно по высоте системного статус-бара: цвет фона темы вверху, прозрачность внизу.
 *
 * Зачем. Приложение рисует edge-to-edge, то есть контент уезжает прямо под часы, батарею и сеть. Даже
 * при верно выбранном цвете иконок (его чинит [com.aiopenpocket.ui.theme.MyApplicationTheme], синхронизируя
 * `isAppearanceLightStatusBars` с темой ПРИЛОЖЕНИЯ, а не системы) на пёстром месте — светлая карточка,
 * картинка, яркое пятно ауроры — они всё равно теряются.
 *
 * Почему градиент, а не полоса. Сплошная плашка вернула бы визуальный «статус-бар» и убила бы саму
 * идею «контент занимает всё пространство»: экран снова начинался бы с чужой панели. Градиент в
 * прозрачность даёт контраст там, где он нужен (у самых иконок), и растворяется к контенту.
 *
 * Ничего не перехватывает: это просто фон без обработчиков, тапы проходят насквозь.
 *
 * @trace glass-design-system-21a3fe
 */
@Composable
fun StatusBarScrim(modifier: Modifier = Modifier) {
  val base = MaterialTheme.colorScheme.background
  Box(
    modifier = modifier
      .fillMaxWidth()
      .windowInsetsTopHeight(WindowInsets.statusBars)
      .background(
        Brush.verticalGradient(
          colors = listOf(base.copy(alpha = SCRIM_TOP_ALPHA), Color.Transparent),
        ),
      ),
  )
}

/**
 * Плотность у самой кромки экрана. Ручка вкуса: выше — надёжнее читаются часы на пёстром, ниже —
 * незаметнее переход. 0.7 подобрано как «видно всегда, но полосой не читается».
 */
private const val SCRIM_TOP_ALPHA = 0.7f
