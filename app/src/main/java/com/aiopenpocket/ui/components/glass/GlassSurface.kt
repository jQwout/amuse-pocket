package com.aiopenpocket.ui.components.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.aiopenpocket.ui.theme.GlassTokens

/**
 * Обвязка-«плашка» для плавающих баров и островов.
 *
 * ## Здесь БОЛЬШЕ НЕТ backdrop-blur (и библиотеки Haze)
 *
 * Раньше это было настоящее стекло: `hazeEffect` размывал контент под баром (API 31+), а экраны
 * помечали свои слои источниками через [glassSource]. Эффект убран по решению владельца продукта —
 * «переливание» не нравилось в работе. Вместо него ровно то, что и просили: полупрозрачная плашка
 * цветом фона темы. По сути это та же ветка, что раньше жила здесь фолбэком для API<31, — теперь она
 * единственная.
 *
 * Что это даёт, кроме внешнего вида: пропал per-frame RenderEffect под скроллящейся лентой (главный
 * кандидат в источники джанка, из-за которого чат гасил блюр на время прокрутки), и пропала
 * зависимость от версии Android — вид одинаков на всех API.
 *
 * ## Почему [GlassState]/[glassSource] остались
 *
 * Это был единственный файл, импортировавший Haze (инвариант, зафиксированный в дизайн-системе), и он
 * себя оправдал: снятие эффекта — правка одного файла. Токен связи «источник ↔ плашка» сохранён
 * пустышкой, чтобы ~10 вызывающих экранов не переписывать; когда/если решим, что возврата к блюру не
 * будет, его можно вычистить отдельным механическим проходом.
 */

/**
 * Токен связи «источник ↔ плашка». Раньше — `HazeState`, теперь пустышка: плашка не сэмплит фон,
 * поэтому источник ей не нужен. Оставлен, чтобы не переписывать все экраны сразу.
 * @trace glass-design-system-21a3fe
 */
class GlassState internal constructor()

@Composable
fun rememberGlassState(): GlassState = remember { GlassState() }

/** Раньше помечал слой источником для блюра. Сейчас no-op — блюра нет. */
@Suppress("UNUSED_PARAMETER")
fun Modifier.glassSource(state: GlassState): Modifier = this

/**
 * Плашка для неподвижной обвязки: топбары, инпут-бары, нижняя навигация, плавающие острова.
 *
 * Слои снизу вверх: цвет фона темы с альфой (отделяет бар от контента, но пропускает его оттенок) →
 * [tint]-плёнка → опциональный рим-блик по грани. Для edge-to-edge поверхностей передавайте
 * `border = null`, иначе прямая линия режет во всю ширину.
 */
@Composable
fun GlassSurface(
  state: GlassState,
  modifier: Modifier = Modifier,
  shape: Shape = RectangleShape,
  tint: Color = GlassTokens.LightTint,
  border: Brush? = GlassTokens.BorderBrush,
  /**
   * Плотность подложки. По умолчанию [PLATE_ALPHA] — общий вид обвязки. Поднимать до 1f стоит там,
   * где просвечивание мешает ПО ДЕЛУ, а не эстетически: поле ввода — именно такой случай, сквозь
   * него читались сообщения ленты.
   */
  plateAlpha: Float = PLATE_ALPHA,
  content: @Composable BoxScope.() -> Unit,
) {
  val backgroundColor = MaterialTheme.colorScheme.background
  Box(
    modifier = modifier
      .clip(shape)
      // Тот же цвет, что у фона экрана, но с альфой: плашка читается как «своя», а не как чужая
      // серая панель, и при этом текст на ней остаётся контрастным поверх ауроры.
      .background(backgroundColor.copy(alpha = plateAlpha))
      .background(tint)
      .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier),
    content = content,
  )
}

/**
 * Плотность плашки — ручка вкуса, крутить здесь (одно место на все бары и острова).
 *
 * 0.86 (первая попытка, как в старом фолбэке) оказалась слишком прозрачной: сквозь топбар читались
 * заголовки карточек из-под него — тот же дефект, из-за которого «Чат»/«Рефлексия» в чате сливались с
 * лентой. Раньше это скрывал блюр: он размывал подложку, а не просто просвечивал ею. Без блюра нужна
 * плотность выше — оттенок фона плашка всё равно берёт, а текст на ней остаётся читаемым.
 */
private const val PLATE_ALPHA = 0.94f

@Composable
fun GlassBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
