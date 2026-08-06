package com.aiopenpocket.desktop

// @trace desktop-genai-client-4c63b6

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aiopenpocket.genai.AttachmentRole
import com.aiopenpocket.genai.InputAttachment
import com.aiopenpocket.genai.GenerationJob
import com.aiopenpocket.genai.GenerationModality
import com.aiopenpocket.genai.ImageCapabilities
import com.aiopenpocket.genai.aspectRatioValue
import com.aiopenpocket.genai.megapixels
import com.aiopenpocket.genai.JobStatus
import com.aiopenpocket.genai.ModelPreviews
import com.aiopenpocket.genai.PromptPreset
import com.aiopenpocket.genai.VideoCapabilities
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Surface
import androidx.compose.runtime.produceState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI

/**
 * Блок формы: подпись и содержимое в карточке.
 *
 * Экран был одной плоской колонкой, где модальность, модель, промпт и запуск сливались в общий
 * список полей — глазом не найти, где кончается одно и начинается другое.
 */
@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      content()
    }
  }
}

/**
 * Плоская секция формы: жирная подпись и приглушённое пояснение в одну строку.
 *
 * Пришла на смену карточкам: по макету форма — это сплошная колонка с заголовками, а рамка вокруг
 * каждого поля дробила её на коробки и съедала ширину, которой на половине окна и так немного.
 * Карточки остались там, где они содержат объект (файл, результат), а не поле ввода.
 */
@Composable
fun FieldGroup(
  title: String,
  hint: String? = null,
  /** Действие над всем блоком — прижимается к правому краю строки заголовка. */
  action: @Composable (() -> Unit)? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(
      Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.Bottom,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
      hint?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      action?.let {
        Spacer(Modifier.weight(1f))
        it()
      }
    }
    content()
  }
}

/**
 * Формат сторон: чипы с нарисованной пропорцией.
 *
 * Значок не украшение. «2.39:1» и «16:9» словами различаются плохо, а глазом — мгновенно, и выбирают
 * тут именно форму кадра. Пропорция рисуется из самой строки, поэтому значок не может разойтись с
 * подписью; строку, которую разобрать не вышло (`auto` и всё, что провайдер придумает завтра),
 * показываем текстом без значка, а не с квадратом наугад.
 *
 * Список приходит от модели: 37 из 40 заявляют свои соотношения, и они разные.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AspectRatioField(
  options: List<String>,
  selected: String?,
  estimated: Pair<Int, Int>?,
  onSelect: (String?) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      for (option in options) {
        val chosen = selected == option
        Surface(
          shape = ButtonShape,
          color = if (chosen) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
          border = BorderStroke(
            1.dp,
            if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
          ),
          modifier = Modifier.clickable { onSelect(if (chosen) null else option) },
        ) {
          Row(
            Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
          ) {
            RatioGlyph(option, chosen)
            Text(
              option,
              style = MaterialTheme.typography.labelLarge,
              color = if (chosen) {
                MaterialTheme.colorScheme.onPrimaryContainer
              } else {
                MaterialTheme.colorScheme.onSurface
              },
            )
          }
        }
      }
    }
    estimated?.let { size ->
      val (w, h) = size
      Text(
        // Со знаком приблизительности намеренно: тир задаётся строкой, и во сколько пикселей он
        // развернётся, провайдер в каталоге не обещает (см. estimatedPixels).
        "≈ $w × $h · ${megapixels(size)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** Прямоугольник в пропорции кадра. Неразобранная строка — пустое место, а не квадрат наугад. */
@Composable
private fun RatioGlyph(ratio: String, chosen: Boolean) {
  val value = aspectRatioValue(ratio) ?: return
  val box = 18.dp
  val width = if (value >= 1.0) box else box * value.toFloat()
  val height = if (value >= 1.0) box / value.toFloat() else box

  Box(Modifier.size(box), contentAlignment = Alignment.Center) {
    Box(
      Modifier
        .size(width = width, height = height)
        .border(
          1.5.dp,
          if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
          RoundedCornerShape(2.dp),
        ),
    )
  }
}

/**
 * Качество: тир разрешения, уровень качества и формат файла — всё, что модель заявила о себе.
 *
 * Тот же принцип, что у [VideoParamsFields]: не заявлено — не показано. Разрешение объявляют 16
 * моделей из сорока, качество — 6, формат файла — 6. Нарисовать «4K» там, где модель о нём молчала,
 * значит предложить значение, на котором запрос вернётся отказом уже после нажатия «Сгенерировать»,
 * то есть за деньги.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImageQualityFields(
  caps: ImageCapabilities,
  resolution: String?,
  onResolution: (String?) -> Unit,
  quality: String?,
  onQuality: (String?) -> Unit,
  outputFormat: String?,
  onOutputFormat: (String?) -> Unit,
) {
  if (caps.resolutions.isNotEmpty()) {
    ParamRow("Разрешение", onReset = { onResolution(null) }, isSet = resolution != null) {
      for (option in caps.resolutions) {
        FilterChip(
          selected = resolution == option,
          onClick = { onResolution(if (resolution == option) null else option) },
          label = { Text(option) },
        )
      }
    }
  }
  if (caps.qualities.isNotEmpty()) {
    ParamRow("Уровень", onReset = { onQuality(null) }, isSet = quality != null) {
      for (option in caps.qualities) {
        FilterChip(
          selected = quality == option,
          onClick = { onQuality(if (quality == option) null else option) },
          label = { Text(option) },
        )
      }
    }
  }
  if (caps.outputFormats.isNotEmpty()) {
    ParamRow("Файл", onReset = { onOutputFormat(null) }, isSet = outputFormat != null) {
      for (option in caps.outputFormats) {
        FilterChip(
          selected = outputFormat == option,
          onClick = { onOutputFormat(if (outputFormat == option) null else option) },
          label = { Text(option.uppercase()) },
        )
      }
    }
  }
}

/**
 * Кадр-основа для видео. Отдельный компонент от [InputImageField], потому что кадров два и у них
 * разный смысл: первый задаёт, с чего начать, последний — чем закончить.
 */
@Composable
fun FramePicker(label: String, picked: File?, onPick: () -> Unit, onClear: () -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    OutlinedButton(onClick = onPick, shape = ButtonShape) { Text(label) }
    if (picked == null) {
      Text(
        "не выбран",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      Text(picked.name, style = MaterialTheme.typography.bodySmall)
      TextButton(onClick = onClear) { Text("убрать") }
    }
  }
}

/**
 * Параметры видео — строго по тому, что модель заявила о себе в `/api/v1/videos/models`.
 *
 * Ни одного контрола «на всякий случай»: длительности, разрешения и соотношения у моделей разные,
 * а у части их нет вовсе (поля приходят `null`). Показать ползунок «3–12 секунд» там, где модель
 * принимает только 5 и 10, значит предложить значение, на котором запрос вернётся отказом — уже после
 * того, как пользователь нажал «Сгенерировать».
 *
 * `null` в каждом поле означает «не задавать»: провайдер применит своё умолчание, и это честнее,
 * чем выбрать за него.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VideoParamsFields(
  caps: VideoCapabilities?,
  duration: Int?,
  onDuration: (Int?) -> Unit,
  resolution: String?,
  onResolution: (String?) -> Unit,
  aspectRatio: String?,
  onAspectRatio: (String?) -> Unit,
) {
  if (caps == null || !caps.hasAnyControl) {
    Text(
      "Эта модель не сообщила, что умеет настраивать — отправим только промпт, " +
        "остальное провайдер выберет сам.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    return
  }

  if (caps.durations.isNotEmpty()) {
    ParamRow("Длительность", onReset = { onDuration(null) }, isSet = duration != null) {
      for (option in caps.durations) {
        FilterChip(
          selected = duration == option,
          onClick = { onDuration(if (duration == option) null else option) },
          label = { Text("$option с") },
        )
      }
    }
  }

  if (caps.resolutions.isNotEmpty()) {
    ParamRow("Разрешение", onReset = { onResolution(null) }, isSet = resolution != null) {
      for (option in caps.resolutions) {
        FilterChip(
          selected = resolution == option,
          onClick = { onResolution(if (resolution == option) null else option) },
          label = { Text(option) },
        )
      }
    }
  }

  if (caps.aspectRatios.isNotEmpty()) {
    ParamRow("Соотношение", onReset = { onAspectRatio(null) }, isSet = aspectRatio != null) {
      for (option in caps.aspectRatios) {
        FilterChip(
          selected = aspectRatio == option,
          onClick = { onAspectRatio(if (aspectRatio == option) null else option) },
          label = { Text(option) },
        )
      }
    }
  }

  if (caps.generatesAudio) {
    Text(
      "Модель озвучивает видео сама.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParamRow(
  title: String,
  onReset: () -> Unit,
  isSet: Boolean,
  chips: @Composable FlowRowScope.() -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(title, style = MaterialTheme.typography.bodyMedium)
      // Сбросить = вернуться к умолчанию провайдера. Без этой кнопки снять выбор было бы нечем.
      if (isSet) TextButton(onClick = onReset) { Text("сбросить") }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), content = chips)
  }
}

/**
 * Файлы на входе: несколько, у каждого роль и теги.
 *
 * Честность про роли вынесена в подпись, а не спрятана в KDoc. У видео роль — настоящее поле API
 * (`frame_images`/`input_references`), у картинок такого поля нет вовсе, и роль с тегами уезжают
 * словами в промпт. Разница пользователю видна, иначе «Фон» выглядит как параметр, который
 * провайдер обязался соблюсти.
 */
@Composable
fun AttachmentsField(
  attachments: List<InputAttachment>,
  modality: GenerationModality,
  onAdd: () -> Unit,
  onFilesDropped: (List<File>) -> Unit,
  onChange: (Int, InputAttachment) -> Unit,
  onRemove: (Int) -> Unit,
) {
  val roles = AttachmentRole.entries.filter { modality == GenerationModality.VIDEO || !it.forVideoOnly }

  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    for ((index, attachment) in attachments.withIndex()) {
      AttachmentCard(
        attachment = attachment,
        roles = roles,
        onChange = { onChange(index, it) },
        onRemove = { onRemove(index) },
      )
    }

    FileDropZone(onAdd = onAdd, onFilesDropped = onFilesDropped)

    Text(
      if (modality == GenerationModality.VIDEO) {
        "Кадры и референсы уходят отдельными полями запроса — роль здесь настоящая."
      } else {
        "У картинок в API нет поля для роли: роль и теги уйдут словами в промпт, как подсказка."
      },
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/**
 * Пунктирная зона: одновременно кнопка и приёмник перетаскивания.
 *
 * Пунктир здесь не оформление, а обещание: рамка такого вида везде означает «сюда можно бросить
 * файл». Поэтому под ней настоящий `dragAndDropTarget` — нарисовать пунктир и не принять drop
 * значило бы соврать рамкой. Сплошная кнопка «+ Добавить файл», стоявшая здесь раньше, наоборот,
 * ничего не обещала и про перетаскивание молчала, хотя оно на десктопе — первое, что пробуют.
 *
 * Подсветка на входе курсора нужна не для красоты: у окна несколько мест, куда файл логично
 * бросить, и пользователь должен видеть, какое из них поймает.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun FileDropZone(onAdd: () -> Unit, onFilesDropped: (List<File>) -> Unit) {
  var dragOver by remember { mutableStateOf(false) }

  // Колбэк захватывает текущий список вложений, то есть на каждый добавленный файл приезжает новая
  // лямбда. Ключ по ней означал бы, что remember пересоздаёт приёмник ровно тогда, когда он занят
  // делом, — то есть не помнит ничего. rememberUpdatedState разводит две вещи: узел живёт долго,
  // а зовёт всегда свежий колбэк.
  val latestOnFiles by rememberUpdatedState(onFilesDropped)

  val target = remember {
    object : DragAndDropTarget {
      override fun onEntered(event: DragAndDropEvent) { dragOver = true }
      override fun onExited(event: DragAndDropEvent) { dragOver = false }
      override fun onEnded(event: DragAndDropEvent) { dragOver = false }

      override fun onDrop(event: DragAndDropEvent): Boolean {
        dragOver = false
        val list = event.dragData() as? DragData.FilesList ?: return false
        // readFiles отдаёт URI («file:/C:/…»), а не пути: File(String) на таком превратился бы
        // в относительный путь с двоеточием и молча не нашёлся бы.
        val files = list.readFiles().mapNotNull { runCatching { File(URI(it)) }.getOrNull() }
        if (files.isEmpty()) return false
        latestOnFiles(files)
        return true
      }
    }
  }

  val shape = MaterialTheme.shapes.medium
  val stroke = if (dragOver) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
  val label = if (dragOver) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

  Box(
    Modifier
      .fillMaxWidth()
      .height(56.dp)
      .clip(shape)
      .background(
        if (dragOver) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent,
      )
      .drawBehind {
        drawRoundRect(
          color = stroke,
          cornerRadius = CornerRadius(12.dp.toPx()),
          style = Stroke(
            width = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
          ),
        )
      }
      .clickable(onClick = onAdd)
      .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = target),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      if (dragOver) "Отпустите — добавим в список" else "+   Перетащите файлы или добавьте",
      style = MaterialTheme.typography.bodyMedium,
      color = label,
    )
  }
}

/**
 * Одна строка списка: миниатюра, имя, роль и теги.
 *
 * Всё в один ряд, а не в два: файлов бывает пять, и каждая лишняя строка выталкивает форму за
 * экран. Имя обрезается посередине — у выгруженных файлов различается хвост, а не начало, и
 * «83d7c5b2eddaa5e08388693ea993167dcc74f5df.jpg» целиком не читается вообще.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttachmentCard(
  attachment: InputAttachment,
  roles: List<AttachmentRole>,
  onChange: (InputAttachment) -> Unit,
  onRemove: () -> Unit,
) {
  Card(Modifier.fillMaxWidth()) {
    Row(
      Modifier.padding(10.dp).fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      AttachmentThumb(attachment.path)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
          attachment.name,
          style = MaterialTheme.typography.bodyMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          RolePicker(attachment.role, roles) { onChange(attachment.copy(role = it)) }
          for (tag in attachment.tags) {
            TagPill(tag) { onChange(attachment.copy(tags = attachment.tags - tag)) }
          }
          TagAdder { tag -> onChange(attachment.copy(tags = attachment.tags + tag)) }
        }
      }
      Text(
        "×",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clickable(onClick = onRemove).padding(horizontal = 4.dp),
      )
    }
  }
}

/**
 * Уже разобранные превью. Кеш на диске избавляет от похода в сеть, но не от чтения файла и
 * декодирования: без этой карты каждое открытие списка снова читало бы девять webp с диска и гоняло
 * бы Skia — и строки заметно «проявлялись» вместо того, чтобы просто быть.
 *
 * Держится в памяти процесса и не чистится: девять картинок 56×34 — это десятки килобайт, а список
 * моделей конечен и невелик. Карта потокобезопасная: заполняется из корутин `produceState`, которые
 * стартуют для всех видимых строк разом.
 */
private val decodedPreviews = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()

/**
 * Пример картинки у модели — витрина, как на сайте OpenRouter.
 *
 * Грузится лениво и только для строк, которые Compose реально составил: пример у модели лежит на
 * чужом сайте, и тянуть все девять заранее ради списка, который могут и не открыть, незачем.
 *
 * Пока не пришло — полосатая заглушка, как у вложений; не пришло вовсе — она же и остаётся. Место
 * под превью занято всегда, иначе строки списка прыгали бы по мере загрузки.
 */
@Composable
internal fun ModelPreviewThumb(
  modelId: String,
  previews: ModelPreviews,
  /** Размер параметром: в строке списка это миниатюра, в панели деталей — витрина во всю ширину. */
  width: Dp = 56.dp,
  height: Dp = 34.dp,
) {
  val bitmap by produceState<ImageBitmap?>(initialValue = decodedPreviews[modelId], modelId) {
    // produceState НЕ сбрасывает значение при смене ключа: внутри у него remember без ключей, и
    // перезапускается только эффект. Без этой строки на месте новой модели секунду висела бы
    // картинка предыдущей.
    value = decodedPreviews[modelId]
    if (value == null) {
      val file = previews.preview(modelId)
      val decoded = file?.let { withContext(Dispatchers.IO) { loadImage(it) } }
      if (decoded != null) decodedPreviews[modelId] = decoded
      value = decoded
    }
  }

  Box(
    Modifier.size(width = width, height = height)
      .clip(MaterialTheme.shapes.extraSmall)
      .background(MaterialTheme.colorScheme.surfaceVariant),
  ) {
    bitmap?.let {
      Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    } ?: StripePlaceholder(width = width, height = height)
  }
}

/** Полосатая заглушка на месте не загруженной картинки — общая для вложений и витрины моделей. */
@Composable
internal fun StripePlaceholder(width: Dp, height: Dp) {
  Canvas(Modifier.size(width, height)) {
    var x = -size.height
    while (x < size.width) {
      drawLine(
        color = StripeColor,
        start = Offset(x, size.height),
        end = Offset(x + size.height, 0f),
        strokeWidth = 1.5f,
      )
      x += 8f
    }
  }
}

/** Миниатюра файла. Не загрузилась — полосатая заглушка, как в макете, а не пустой прямоугольник. */
@Composable
private fun AttachmentThumb(path: String) {
  val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
    value = withContext(Dispatchers.IO) { loadImage(File(path)) }
  }
  Box(
    Modifier.size(44.dp)
      .clip(MaterialTheme.shapes.small)
      .background(MaterialTheme.colorScheme.surfaceVariant),
  ) {
    val ready = bitmap
    if (ready != null) {
      Image(
        ready,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(44.dp),
      )
    } else {
      StripePlaceholder(width = 44.dp, height = 44.dp)
    }
  }
}

private val StripeColor = Color(0x22FFFFFF)

@Composable
private fun RolePicker(current: AttachmentRole, options: List<AttachmentRole>, onPick: (AttachmentRole) -> Unit) {
  var open by remember { mutableStateOf(false) }
  Box {
    Surface(
      shape = MaterialTheme.shapes.small,
      color = MaterialTheme.colorScheme.primaryContainer,
      modifier = Modifier.clickable { open = true },
    ) {
      Text(
        current.label + "  \u25BE",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
      )
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      for (role in options) {
        DropdownMenuItem(
          text = { Text(role.label) },
          onClick = {
            onPick(role)
            open = false
          },
        )
      }
    }
  }
}

/** Тег — компактная пилюля; клик убирает. Полноценный чип Material здесь вдвое выше строки. */
@Composable
private fun TagPill(tag: String, onRemove: () -> Unit) {
  Surface(
    shape = MaterialTheme.shapes.small,
    color = MaterialTheme.colorScheme.surfaceVariant,
    modifier = Modifier.clickable(onClick = onRemove),
  ) {
    Text(
      tag,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
    )
  }
}

/**
 * «+ тег» — приглушённая ссылка, и только по клику превращается в поле.
 *
 * Постоянно открытое поле ввода занимало целую строку в каждой карточке и перетягивало внимание
 * на себя, хотя теги — необязательная приписка.
 */
@Composable
private fun TagAdder(onAdd: (String) -> Unit) {
  var editing by remember { mutableStateOf(false) }
  var text by remember { mutableStateOf("") }

  if (!editing) {
    Text(
      "+ тег",
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.clickable { editing = true }.padding(horizontal = 6.dp, vertical = 6.dp),
    )
    return
  }

  BasicTextField(
    value = text,
    onValueChange = { raw ->
      // Запятая или перевод строки завершают тег: ловить Enter отдельно дороже, чем оно стоит.
      if (raw.endsWith(",") || raw.endsWith("\n")) {
        val tag = raw.dropLast(1).trim()
        if (tag.isNotEmpty()) onAdd(tag)
        text = ""
        editing = false
      } else {
        text = raw
      }
    },
    singleLine = true,
    textStyle = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurface),
    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
    modifier = Modifier.width(96.dp),
    decorationBox = { inner ->
      Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
        Box(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) { inner() }
      }
    },
  )
}

@Composable
fun PresetPicker(presets: List<PromptPreset>, onApply: (PromptPreset) -> Unit, onSaveCurrent: () -> Unit) {
  var expanded by remember { mutableStateOf(false) }
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Box {
      OutlinedButton(onClick = { expanded = true }, shape = ButtonShape) { Text("Заготовки") }
      DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        if (presets.isEmpty()) {
          DropdownMenuItem(text = { Text("заготовок нет") }, onClick = {})
        }
        for (preset in presets) {
          DropdownMenuItem(
            text = {
              Column {
                Text(preset.title)
                Text(
                  preset.prompt.take(80),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            },
            onClick = {
              onApply(preset)
              expanded = false
            },
          )
        }
      }
    }
    TextButton(onClick = onSaveCurrent) { Text("Сохранить промпт") }
  }
}

@Composable
fun SavePresetDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
  var title by remember { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Сохранить как заготовку") },
    text = {
      Column {
        OutlinedTextField(
          value = title,
          onValueChange = { title = it },
          label = { Text("Название") },
          singleLine = true,
          shape = ButtonShape,
        )
        Spacer(Modifier.height(8.dp))
        Text(
          "Заготовка ляжет в твой файл промптов. Название с тем же смыслом перезапишет старую " +
            "запись, а не создаст дубль.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    },
    confirmButton = {
      TextButton(enabled = title.isNotBlank(), onClick = { onConfirm(title) }) { Text("Сохранить") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
  )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceField(voice: String, suggestions: List<String>, onVoiceChange: (String) -> Unit) {
  Column {
    OutlinedTextField(
      value = voice,
      onValueChange = onVoiceChange,
      label = { Text("Голос (можно оставить пустым)") },
      singleLine = true,
      modifier = Modifier.width(320.dp),
    )
    if (suggestions.isNotEmpty()) {
      Spacer(Modifier.height(8.dp))
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (option in suggestions.take(6)) {
          FilterChip(
            selected = voice == option,
            onClick = { onVoiceChange(if (voice == option) "" else option) },
            label = { Text(option) },
          )
        }
      }
    }
  }
}

@Composable
fun InputImageField(
  picked: File?,
  modality: GenerationModality,
  onPick: () -> Unit,
  onClear: () -> Unit,
) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    OutlinedButton(onClick = onPick, shape = ButtonShape) {
      Text(if (modality == GenerationModality.VIDEO) "Картинка → видео" else "Картинка-основа")
    }
    if (picked == null) {
      Text(
        "не выбрана — будет генерация только по тексту",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      Text(picked.name, style = MaterialTheme.typography.bodyMedium)
      TextButton(onClick = onClear) { Text("убрать") }
    }
  }
}

@Composable
fun JobRow(job: GenerationJob, onCancel: () -> Unit, onRetry: () -> Unit) {
  Card(Modifier.fillMaxWidth()) {
    Row(
      Modifier.padding(12.dp).fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (!job.status.isTerminal) {
        CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
      }
      Column(Modifier.weight(1f)) {
        Text(
          buildString {
            append(job.request.modality.label)
            append(" · ")
            append(statusLabel(job.status))
            if (job.batchId != null) append(" · в пачке")
          },
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Medium,
          color = if (job.status == JobStatus.FAILED) {
            MaterialTheme.colorScheme.error
          } else {
            MaterialTheme.colorScheme.onSurface
          },
        )
        Text(
          job.request.model,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        (job.error ?: job.note)?.let {
          Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
      }
      Text(formatCost(job.costUsd), style = MaterialTheme.typography.bodySmall)
      if (job.status.isTerminal) {
        TextButton(onClick = onRetry) { Text("Повторить") }
      } else {
        TextButton(onClick = onCancel) { Text("Отменить") }
      }
    }
  }
}

internal fun statusLabel(status: JobStatus): String = when (status) {
  JobStatus.QUEUED -> "в очереди"
  JobStatus.RUNNING -> "генерируется"
  JobStatus.POLLING -> "ждём провайдера"
  JobStatus.DONE -> "готово"
  JobStatus.FAILED -> "ошибка"
  JobStatus.CANCELLED -> "отменено"
}
