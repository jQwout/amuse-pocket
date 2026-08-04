package com.aiopenpocket.desktop

// @trace desktop-genai-client-4c63b6

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Тёмная тема: инструмент для разглядывания картинок и видео, светлый фон вокруг результата мешает
 * оценивать сам результат.
 *
 * Главный урок первой версии — дело не в оттенке, а в **расстоянии между слоями**. Фон был
 * `0F1117`, карточки `161922`: семь единиц яркости, и на глаз всё сливалось в одну муть, где не
 * видно, где кончается карточка. Ниже разрыв фон→поверхность заметно шире, а `surfaceVariant`
 * (заглушки, пилюли) отделён ещё на ступень.
 *
 * Акцент фиолетовый — по макету.
 */
private fun scheme(
  background: Color,
  surface: Color,
  surfaceVariant: Color,
  outline: Color,
) = darkColorScheme(
  primary = Color(0xFF7C6CF0),
  onPrimary = Color(0xFFFFFFFF),
  // Контейнер темнее primary: им залит выбранный пункт рельсы и активные пилюли, и на нём должен
  // читаться светлый текст, а не спорить с ним яркостью.
  primaryContainer = Color(0xFF2A2560),
  onPrimaryContainer = Color(0xFFCFC7FF),
  secondary = Color(0xFFB4B0D6),
  background = background,
  onBackground = Color(0xFFE8E9F0),
  surface = surface,
  onSurface = Color(0xFFE8E9F0),
  surfaceVariant = surfaceVariant,
  onSurfaceVariant = Color(0xFF9CA3B8),
  outline = outline,
  error = Color(0xFFFF8A80),
  onError = Color(0xFF3A0906),
)

/**
 * Три палитры разведены НАМЕРЕННО далеко друг от друга.
 *
 * Первая попытка предложить выбор провалилась: варианты отличались на два-три пункта яркости и на
 * рендере были неразличимы — выбирать было не из чего. Здесь разница видна с одного взгляда:
 * холодная синева, откровенно фиолетовый и нейтральный светлый графит.
 */

/** Чернильный: холодная синева, глубокий фон. Ближе всего к тому, что было. */
internal val InkScheme = scheme(
  background = Color(0xFF0B0D14),
  surface = Color(0xFF171A24),
  surfaceVariant = Color(0xFF212533),
  outline = Color(0xFF2C3142),
)

/** Фиолетовый: фон откровенно уходит в тон акцента, а не намекает на него. */
internal val VioletScheme = scheme(
  background = Color(0xFF150F26),
  surface = Color(0xFF221A38),
  surfaceVariant = Color(0xFF2E2449),
  outline = Color(0xFF3D3160),
)

/** Графитовый: нейтральный серый без синевы и заметно светлее — меньше ощущения подвала. */
internal val GraphiteScheme = scheme(
  background = Color(0xFF17181C),
  surface = Color(0xFF212328),
  surfaceVariant = Color(0xFF2B2E35),
  outline = Color(0xFF3A3E47),
)

/** Скругления крупнее дефолтных Material: по макету карточки заметно круглее. */
private val AppShapes = Shapes(
  extraSmall = RoundedCornerShape(6.dp),
  small = RoundedCornerShape(8.dp),
  medium = RoundedCornerShape(12.dp),
  large = RoundedCornerShape(14.dp),
  extraLarge = RoundedCornerShape(20.dp),
)

/**
 * Схема параметром, чтобы харнесс снимал варианты одним прогоном: выбирать палитру по описанию
 * словами — то же самое гадание, от которого мы и уходили.
 */
@Composable
fun AppTheme(colors: ColorScheme = GraphiteScheme, content: @Composable () -> Unit) =
  MaterialTheme(colorScheme = colors, shapes = AppShapes, content = content)
