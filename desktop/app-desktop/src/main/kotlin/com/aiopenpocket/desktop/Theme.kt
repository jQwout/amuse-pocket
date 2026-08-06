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
  // Выбранный FilterChip красится в secondaryContainer, а не в primaryContainer. Дефолт тёмной
  // схемы — тёмно-серый, и на снимке выбранная «Картинка» читалась как чуть более светлый
  // прямоугольник, а не как сделанный выбор. Тот же цвет, что у выбранного пункта рельсы: выбор
  // в приложении должен выглядеть одинаково, где бы он ни стоял.
  secondaryContainer = Color(0xFF2A2560),
  onSecondaryContainer = Color(0xFFCFC7FF),
  background = background,
  onBackground = Color(0xFFE8E9F0),
  surface = surface,
  onSurface = Color(0xFFE8E9F0),
  surfaceVariant = surfaceVariant,
  onSurfaceVariant = Color(0xFF9CA3B8),
  // Material 3 красит Card НЕ в surface, а в surfaceContainerHighest (FilledCardTokens.ContainerColor
  // — проверено по байткоду). Без этих трёх строк карточки во всех палитрах брали дефолтный
  // Neutral22 тёмной схемы: одинаковый серый с фиолетинкой, не зависящий от того, какую surface мы
  // передали. То есть весь разговор про «разрыв фон→поверхность» до карточек — а их на экране
  // большинство — просто не доезжал, и три палитры на снимках отличались меньше, чем обещано.
  surfaceContainerLowest = background,
  surfaceContainerLow = surface,
  surfaceContainer = surface,
  surfaceContainerHigh = surfaceVariant,
  surfaceContainerHighest = surfaceVariant,
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
 * Форма кнопок: прямоугольник со скруглением, как в макете, а не «пилюля».
 *
 * Экран был из двух языков сразу: поля, карточки и дроп-зона — мягкие прямоугольники, а кнопки
 * поверх них — стадионы во всю высоту. Рядом это читается не как акцент, а как две разные темы,
 * случайно оказавшиеся в одном окне.
 *
 * **Передавать руками в каждый вызов.** Material 3 не берёт форму кнопок из [Shapes]: токен
 * `CornerFull` захардкожен в `CircleShape` внутри `Shapes.fromToken`, и подменить его через тему
 * нечем. Забыть `shape =` у новой кнопки — значит вернуть в окно пилюлю.
 *
 * Чипов это НЕ касается, вопреки первому впечатлению: `FilterChipTokens.ContainerShape` — это
 * `CornerSmall`, то есть [Shapes.small], то есть наши 8 dp. Отдельный токен под них заводился и был
 * убран — он ничего не менял, только делал вид.
 */
val ButtonShape = RoundedCornerShape(10.dp)

/**
 * Схема параметром, чтобы харнесс снимал варианты одним прогоном: выбирать палитру по описанию
 * словами — то же самое гадание, от которого мы и уходили.
 */
@Composable
fun AppTheme(colors: ColorScheme = GraphiteScheme, content: @Composable () -> Unit) =
  MaterialTheme(colorScheme = colors, shapes = AppShapes, content = content)
