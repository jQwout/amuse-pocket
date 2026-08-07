package com.aiopenpocket.desktop

// @trace desktop-genai-client-4c63b6

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiopenpocket.genai.GenerationJob
import com.aiopenpocket.genai.GenerationModality
import com.aiopenpocket.genai.GenerationRequest
import com.aiopenpocket.genai.JobRating
import com.aiopenpocket.genai.JobStatus
import com.aiopenpocket.genai.PickerContext
import com.aiopenpocket.genai.buildCards
import com.aiopenpocket.genai.buildPromptCards
import com.aiopenpocket.genai.costEstimate
import com.aiopenpocket.genai.estimatedPixels
import com.aiopenpocket.genai.humanModelName
import com.aiopenpocket.genai.rejectionReason
import com.aiopenpocket.genai.runEstimateLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Высота раскрытого поля промпта. Число, а не «вся колонка»: форма живёт внутри вертикальной
 * прокрутки, где верхней границы высоты нет вовсе, и `fillMaxHeight` там растягивает в бесконечность.
 * 560 dp — это примерно высота окна за вычетом строки запуска, то есть «весь экран» на практике.
 */
private val EXPANDED_PROMPT_HEIGHT = 560.dp

/**
 * Сколько последних заданий показать в панели, если в этой сессии ещё не запускали. Не «все»:
 * панель — рабочий стол, а вся история живёт на своём экране и там сгруппирована по пачкам.
 */
private const val RESULTS_FROM_HISTORY = 8

// Выбора «сколько вариантов разом» на экране больше нет — по просьбе пользователя. Пачки живы в
// очереди (`GenerationQueue.submitBatch` под тестами), но кнопки для них нет, и запуск всегда даёт
// один файл.

/**
 * Экран генерирует ТОЛЬКО картинки.
 *
 * Переключателя модальности здесь больше нет: ряд чипов «Картинка / Видео / Аудио» стоял над
 * промптом, в макете его нет вовсе, и убран он по прямой просьбе. Следствие, которое нельзя
 * замалчивать: **видео и озвучка из интерфейса недостижимы вовсе**. Ядро их умеет, каталог знает
 * 21 видео-модель, тесты на них есть — а нажать негде. Последний вход, кнопка «сделать видео» на
 * карточке результата, убран вместе с остальными иконками ряда; `AppContainer.animate` цел, но
 * вызывать его неоткуда.
 *
 * Строительные блоки для них не удалены и ждут в `GenerateControls` (`VoiceField`,
 * `VideoParamsFields`): вернуть модальность — это вернуть выбор в рельсу, как в макете, а не писать
 * их заново. Список не исчерпывающий: в том же файле лежат и `FramePicker`, `InputImageField`,
 * `SectionCard`, `JobRow` — они осиротели раньше и к этому решению отношения не имеют.
 */
private val MODALITY = GenerationModality.IMAGE

/**
 * Две панели: слева форма, справа результаты последнего запуска.
 *
 * Раньше результат жил только на вкладке «История», и после нажатия «Сгенерировать» экран не менялся
 * вовсе — приходилось угадывать, случилось ли что-нибудь, и уходить смотреть в другое место.
 *
 * [initialPrompt] существует ради offscreen-снимков и по умолчанию пуст. С пустым промптом кнопка
 * запуска выключена, а выключенная кнопка серая — то есть на снимке не видно ни акцента, ни сметы
 * рядом с ней, ровно того, что этим снимком и проверяют. Причина та же, по которой харнесс
 * подкладывает поддельную историю и ключ: пустой экран не показывает того, ради чего он снят.
 */
@Composable
fun GenerateScreen(container: AppContainer, onOpenSettings: () -> Unit, initialPrompt: String = "") {
  val apiKey by container.apiKey.collectAsState()
  val prompts by container.prompts.collectAsState()
  // История нужна форме, а не только панели результатов: по ней считается смета под кнопкой запуска.
  val jobs by container.queue.jobs.collectAsState()

  var lastError by remember { mutableStateOf<String?>(null) }

  // Промпт, вложения, модель и раскрытость поля живут в контейнере, а не здесь. Переключение
  // вкладки рельсой уничтожает этот экран целиком, и вместе с ним раньше пропадало всё набранное —
  // включая список прикреплённых файлов с уже расставленными ролями и тегами.
  val draft by container.draft.collectAsState()
  // Что стоит знать о перенесённом из истории черновике: например, что часть файлов не нашлась.
  val draftNotice by container.draftNotice.collectAsState()
  val prompt = draft.prompt
  val attachments = draft.attachments
  val promptExpanded = draft.promptExpanded

  val catalogSnapshot by container.catalog.collectAsState()
  val catalog = catalogSnapshot.catalog

  val model = draft.model
  val modelsForModality = catalog.forModality(MODALITY)

  // Строки окна выбора собираются в ядре из четырёх источников: каталог, история запусков,
  // избранное и то, что уже набрано в форме. Здесь только склейка — считать в композиции нечего.
  val favorites by container.favorites.collectAsState()
  val pickerContext = PickerContext(
    attachments = attachments.size,
    aspectRatio = draft.aspectRatio,
    resolution = draft.resolution,
  )
  val modelCards = remember(modelsForModality, jobs, favorites, pickerContext) {
    buildCards(modelsForModality, jobs, favorites, pickerContext)
  }
  var pickingModel by remember { mutableStateOf(false) }

  // Заготовки собираются так же, как модели: библиотека, счётчик вставок, избранное и признак «своя».
  val promptFavorites by container.promptFavorites.collectAsState()
  val promptUsage by container.promptUsage.collectAsState()
  val ownPromptIds by container.ownPromptIds.collectAsState()
  val promptCards = remember(prompts, promptUsage, promptFavorites, ownPromptIds) {
    buildPromptCards(prompts.forModality(MODALITY), promptUsage, promptFavorites, ownPromptIds)
  }
  var pickingPreset by remember { mutableStateOf(false) }

  // Промпт из харнесса подставляем один раз: снимку нужен непустой экран, но затирать им набранное
  // при каждой перерисовке нельзя.
  LaunchedEffect(Unit) {
    if (initialPrompt.isNotBlank() && draft.prompt.isBlank()) {
      container.updateDraft { it.copy(prompt = initialPrompt) }
    }
  }

  // Смена модели чистит параметры, которых новая модель не заявляет.
  //
  // Иначе выбранное у прежней модели молча уезжает в запрос: контрол с экрана исчезает, а значение
  // остаётся в черновике. Живой API отвечает на это HTTP 400 «No provider for … supports the
  // requested …» — то есть отказ за деньги ровно там, где вся конструкция «показываем только
  // заявленное» была должна его предотвратить.
  LaunchedEffect(model, catalogSnapshot) {
    val caps = catalog.forModality(MODALITY).firstOrNull { it.id == model }?.image
    container.updateDraft { d ->
      d.copy(
        resolution = d.resolution?.takeIf { caps != null && it in caps.resolutions },
        aspectRatio = d.aspectRatio?.takeIf { caps != null && it in caps.aspectRatios },
        outputFormat = d.outputFormat?.takeIf { caps != null && it in caps.outputFormats },
        quality = d.quality?.takeIf { caps != null && it in caps.qualities },
      )
    }
  }

  // Живой каталог приезжает уже после первой отрисовки. Дефолт подставляем только в ПУСТОЕ поле:
  // затирать им модель, которую пользователь успел ввести руками, нельзя.
  LaunchedEffect(catalogSnapshot) {
    if (draft.model.isBlank()) {
      catalog.defaultFor(MODALITY)?.id?.let { id -> container.updateDraft { it.copy(model = id) } }
    }
  }
  val readyToSubmit = prompt.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

  /**
   * Берёт файлы во вложения, отсеивая негодные с ВИДИМОЙ причиной.
   *
   * Молча проигнорировать брошенный файл нельзя: пользователь решит, что перетаскивание не работает.
   * Молча взять — тоже: `.mp4` доедет до платного запроса как картинка и вернёт отказ за деньги
   * (см. `rejectionReason`).
   */
  fun takeFiles(files: List<File>) {
    // Один проход, а не два: rejectionReason ходит на диск (exists/length), и считать его дважды
    // на каждый файл значит удваивать эту работу прямо в обработчике нажатия.
    val (accepted, rejected) = files
      .map { file -> file to rejectionReason(file) }
      .partition { (_, reason) -> reason == null }

    container.clearDraftNotice()
    if (accepted.isNotEmpty()) {
      container.updateDraft { d ->
        d.copy(
          attachments = d.attachments +
            accepted.map { (file, _) -> com.aiopenpocket.genai.InputAttachment(file.toString()) },
        )
      }
    }
    lastError = rejected.map { (file, reason) -> file.name to reason.orEmpty() }
      .takeIf { it.isNotEmpty() }
      ?.joinToString("; ") { (name, reason) -> "$name — $reason" }
      ?.let { "Не взяли: $it" }
  }

  // Пропорция из макета: форма ~36%, результаты ~64%. Раньше было 42/58, и картинки — то, ради
  // чего экран открыт — оказывались уже, чем поля ввода, которые заполняют один раз.
  Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
    Column(Modifier.weight(0.36f).fillMaxHeight()) {
      // Форма прокручивается, а кнопка запуска — нет: в макете она прижата вниз и видна всегда,
      // иначе на длинной форме приходится доскроллить до неё, чтобы просто нажать.
      Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp),
      ) {
        // Промпт первым: это то, ради чего экран открыт. Модель — настройка, и в макете её в
        // форме нет вовсе, она подписана в шапке окна.
        FieldGroup(
          "Опишите кадр",
          "своими словами",
          action = {
            IconAction(
              if (promptExpanded) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull,
              if (promptExpanded) "Свернуть поле" else "Расширить поле",
            ) { container.updateDraft { it.copy(promptExpanded = !it.promptExpanded) } }
          },
        ) {
          OutlinedTextField(
            value = prompt,
            onValueChange = { text -> container.updateDraft { it.copy(prompt = text) } },
            label = null,
            placeholder = { Text("Мокрый асфальт, фигура в плаще спиной к камере…") },
            shape = ButtonShape,
            // Высота ТОЧНАЯ, а не минимальная, и это не косметика.
            //
            // Было `heightIn(min = …)`: сверху потолка нет, а родитель — прокручиваемая колонка,
            // то есть места «сколько угодно». Поле росло вниз с каждой новой строкой, контейнер за
            // курсором не ехал, и каретка уползала под нижний край окна. Снаружи это выглядело так,
            // будто поле не принимает пробел и Enter: именно они переносят строку и уводят курсор
            // из виду, а буквы «работали», потому что каретку вниз не двигают. Символы при этом
            // вставлялись все до одного — проверено логом ввода.
            //
            // С точной высотой поле не растёт, а прокручивается внутри себя и само держит каретку
            // на виду. Раскрытие — просто другая высота.
            modifier = Modifier
              .fillMaxWidth()
              .height(if (promptExpanded) EXPANDED_PROMPT_HEIGHT else 210.dp),
            minLines = 7,
          )

          OutlinedButton(onClick = { pickingPreset = true }, shape = ButtonShape) {
            Text("Заготовки · ${promptCards.size}")
          }
        }

        FieldGroup("Файлы", draftNotice ?: "каждый со своей ролью и тегами") {
          AttachmentsField(
            attachments = attachments,
            modality = MODALITY,
            // Оба пути добавления проходят через одну проверку. Диалог выбора не спасает: его
            // setFilenameFilter на Windows не работает вовсе, и «выбрать .mp4» там ровно так же
            // возможно, как бросить его в окно.
            onAdd = { pickImageFile()?.let { file -> takeFiles(listOf(file)) } },
            onFilesDropped = { files -> takeFiles(files) },
            onChange = { i, item ->
              container.updateDraft { d -> d.copy(attachments = d.attachments.toMutableList().also { it[i] = item }) }
            },
            onRemove = { i ->
              container.updateDraft { d -> d.copy(attachments = d.attachments.toMutableList().also { it.removeAt(i) }) }
            },
          )
        }

        // Возможности берутся у ВЫБРАННОЙ модели: наборы у них разные, общего не бывает.
        val caps = modelsForModality.firstOrNull { it.id == model }?.image

        if (caps != null && caps.hasAspect) {
          FieldGroup(
            "Формат сторон",
            draft.aspectRatio ?: "не выбран — решит провайдер",
          ) {
            AspectRatioField(
              options = caps.aspectRatios,
              selected = draft.aspectRatio,
              estimated = estimatedPixels(draft.resolution, draft.aspectRatio),
              onSelect = { v -> container.updateDraft { it.copy(aspectRatio = v) } },
            )
          }
        }

        if (caps != null && !caps.hasAnyControl) {
          Text(
            "Эта модель не сообщила, что умеет настраивать — отправим только промпт, " +
              "остальное провайдер выберет сам.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        if (caps != null && caps.hasQuality) {
          // Подпись описывает СОСТОЯНИЕ, а не общее правило. Раньше здесь висело неизменное
          // «не выбрано — на усмотрение провайдера»: оно оставалось на месте и после выбора, то есть
          // прямо противоречило чипам под собой.
          val chosen = listOfNotNull(draft.resolution, draft.quality, draft.outputFormat?.uppercase())
          FieldGroup(
            "Качество",
            if (chosen.isEmpty()) "не выбрано — решит провайдер" else chosen.joinToString(" · "),
          ) {
            ImageQualityFields(
              caps = caps,
              resolution = draft.resolution,
              onResolution = { v -> container.updateDraft { it.copy(resolution = v) } },
              quality = draft.quality,
              onQuality = { v -> container.updateDraft { it.copy(quality = v) } },
              outputFormat = draft.outputFormat,
              onOutputFormat = { v -> container.updateDraft { it.copy(outputFormat = v) } },
            )
          }
        }

        FieldGroup("Модель") {
          ModelChoiceRow(
            modelId = model,
            card = modelCards.firstOrNull { it.id == model },
            tier = draft.resolution,
            previews = container.modelPreviews,
            onOpen = { pickingModel = true },
          )
        }

      }

      HorizontalDivider()
      Spacer(Modifier.height(12.dp))

      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
          enabled = readyToSubmit,
          onClick = {
            // В задание кладём ПУТЬ, а не содержимое: чтение файла и base64 на десятки мегабайт
            // подвесили бы обработчик клика, а сам data-URL раздул бы историю на диске. Разворачивает
            // его провайдер на IO-диспетчере, уже перед самим запросом.
            lastError = null
            val request = GenerationRequest(
              modality = MODALITY,
              prompt = prompt.trim(),
              model = model.trim(),
              attachments = attachments,
              resolution = draft.resolution,
              aspectRatio = draft.aspectRatio,
              outputFormat = draft.outputFormat,
              quality = draft.quality,
            )
            container.submitBatch(request, 1)
          },
          // Крупнее дефолтной: по макету запуск — самый весомый элемент экрана, а не такая же
          // кнопка, как «Из списка» рядом с полем модели.
          contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
          shape = ButtonShape,
        ) {
          Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(10.dp))
          Text("Сгенерировать")
        }

        // Рядом с кнопкой стоит только смета: она короткая и предсказуемой длины. Предупреждение
        // про ключ и текст ошибки уезжают строкой ниже — в колонке 36% ширины они не влезали рядом
        // с кнопкой и переносились по слогам прямо в неё. Поймано на живом окне: в снимке ключ
        // подставлен, и эта ветка туда не попадает вовсе.
        if (apiKey.isNotBlank()) {
          Text(
            runEstimateLine(jobs.costEstimate(model.trim()), 1),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      if (apiKey.isBlank()) {
        TextButton(onClick = onOpenSettings) { Text("Сначала укажи ключ OpenRouter") }
      }
      lastError?.let {
        Text(
          it,
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.padding(top = 6.dp),
        )
      }
    }

    VerticalDivider()

    ResultsPane(container, Modifier.weight(0.64f).fillMaxHeight())
  }

  if (pickingPreset) {
    PromptPickerDialog(
      cards = promptCards,
      currentPrompt = prompt,
      onDismiss = { pickingPreset = false },
      // Окно не закрываем: заготовку часто вставляют, смотрят и берут следующую или дописывают
      // вторую поверх — закрытие после каждой вставки заставляло бы открывать его снова.
      onApply = { preset, append -> container.applyPreset(preset, append) },
      onToggleFavorite = container::togglePromptFavorite,
      onSave = { preset ->
        container.savePresetOrReason(preset)?.let { reason ->
          // Причину показываем дословно: «битый JSON» и «нет прав» требуют разных действий, а
          // общее «не удалось» заставляет жать «Сохранить» снова с тем же исходом.
          lastError = "Не удалось сохранить заготовку в ${container.promptsUserFile}: $reason"
        }
      },
      onDelete = { id ->
        if (!container.deletePreset(id)) {
          lastError = "Не удалось удалить заготовку из ${container.promptsUserFile}"
        }
      },
    )
  }

  if (pickingModel) {
    ModelPickerDialog(
      cards = modelCards,
      current = model,
      context = pickerContext,
      previews = container.modelPreviews,
      onDismiss = { pickingModel = false },
      onPick = { picked ->
        container.updateDraft { it.copy(model = picked) }
        pickingModel = false
      },
      onToggleFavorite = container::toggleFavorite,
    )
  }
}

/**
 * Результаты последнего запуска — вся пачка целиком, в каком бы статусе ни были задания.
 *
 * Задания берутся по id из контейнера и сопоставляются с ЖИВЫМ списком очереди, а не хранятся
 * копией: статус, стоимость и пути к файлам меняются по ходу дела.
 */
@Composable
private fun ResultsPane(container: AppContainer, modifier: Modifier = Modifier) {
  val jobs by container.queue.jobs.collectAsState()
  val lastIds by container.lastSubmitted.collectAsState()

  // Задание, которое собираются удалить. Спрашиваем подтверждение всегда: файл стирается насовсем,
  // мимо корзины, и «Отменить» после этого не существует.
  var deleting by remember { mutableStateOf<GenerationJob?>(null) }
  // Неудачи удаления приходят из контейнера, а не из состояния экрана: экран может быть уже закрыт.
  val deleteFailures by container.deleteFailures.collectAsState()

  deleting?.let { job ->
    DeleteResultDialog(
      job = job,
      onDismiss = { deleting = null },
      onConfirm = {
        deleting = null
        container.clearDeleteFailures()
        // Через контейнер, а не через rememberCoroutineScope: тот умирает вместе с экраном, и уход
        // на «Историю» сразу после подтверждения обрывал бы удаление на середине.
        container.delete(job.id)
      },
    )
  }

  // Панель — рабочий стол за сессию, а не окошко в последнюю пачку. Копится всё запущенное с
  // момента открытия окна, новое сверху: раньше каждый запуск стирал предыдущий, и чтобы взглянуть
  // на кадр, сделанный пять минут назад, приходилось уходить на другой экран.
  // Сначала разрешаем id в задания, потом решаем, чья это ветка. Обратный порядок означал, что
  // id удалённого задания держит панель в режиме «эта сессия» с пустым списком — то есть «запусков
  // не было» рядом с полной историей.
  val resolved = lastIds.mapNotNull { id -> jobs.firstOrNull { it.id == id } }
  val fromThisSession = resolved.isNotEmpty()
  val batch = if (fromThisSession) {
    resolved
  } else {
    // После перезапуска запусков ещё не было, но история есть: пустая панель рядом с полной
    // историей выглядела бы как «всё пропало». Показываем последнее сделанное, новое сверху.
    jobs.takeLast(RESULTS_FROM_HISTORY).reversed()
  }
  // Задания вне текущей пачки иначе пропали бы с глаз, а среди них может быть оплаченное видео.
  val otherActive = jobs.count { !it.status.isTerminal && it.id !in lastIds }

  Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(
      Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // Заголовок и мета в одну строку, как в макете: подзаголовок второй строкой поднимал шапку
      // на две высоты и отжимал сетку вниз ровно там, где нужны картинки.
      Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          "Результаты",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold,
        )
        val models = batch.map { it.request.model }.distinct()
        val subtitle = when {
          batch.isEmpty() -> "запусков пока не было"
          // Моделей в панели теперь бывает несколько — она копится за всю сессию. Подписывать
          // пачку из трёх моделей именем первой значило бы врать заголовком.
          models.size > 1 -> "${batch.size} шт. · ${models.size} модели"
          fromThisSession -> "${batch.size} шт. · " + humanModelName(models.first())
          else -> "последние ${batch.size} · " + humanModelName(models.first())
        }
        Text(
          subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(bottom = 3.dp),
        )
      }
      // В макете здесь «Скачать все», но скачивать нечего: файлы уже лежат на диске к моменту, когда
      // карточка стала готовой. Форму кнопки берём из макета, смысл оставляем свой.
      OutlinedButton(onClick = { openInSystem(container.outputDir) }, shape = ButtonShape) {
        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text("Открыть папку")
      }
    }

    if (otherActive > 0) {
      Text(
        "ещё $otherActive задание(й) в работе вне этой пачки",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    if (deleteFailures.isNotEmpty()) {
      Text(
        "Не удалось стереть: " + deleteFailures.joinToString(", ") { File(it).name },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )
    }

    if (batch.isEmpty()) {
      Text(
        "Запусти генерацию — результаты появятся здесь, рядом с формой.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      return@Column
    }

    LazyVerticalGrid(
      // Adaptive, а не фиксированные две колонки: на узком окне карточки не должны схлопываться
      // в нечитаемые полоски.
      //
      // 300, а не 240: после расширения панели до 64% ширины прежний порог давал три колонки вместо
      // двух — карточки становились уже, чем были до расширения, то есть места прибавилось, а
      // картинки уменьшились. В макете колонок две, и по делу: разглядывать результат в узкой
      // плитке нельзя, ради этого экран и открывают.
      columns = GridCells.Adaptive(minSize = 300.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      items(batch, key = { it.id }) { job ->
        ResultCard(
          job = job,
          onCancel = { container.queue.cancel(job.id) },
          // Через контейнер: иначе повтор не попадает в накопительную панель и исчезает с глаз,
          // едва завершившись.
          onRetry = { container.retry(job.id) },
          onDelete = { deleting = job },
          onRate = { container.setRating(job.id, it) },
        )
      }
    }
  }
}

/**
 * Карточка результата: оценка слева, стоимость и удаление справа, открытие — кликом по кадру.
 *
 * Раньше в ряду стояли повтор, «доработать» (image-to-image с этим же кадром) и «сделать видео».
 * Убраны по просьбе: пять иконок подряд читались ребусом. Ядро всё это по-прежнему умеет
 * (`AppContainer.refine`, `AppContainer.animate`), входа в UI у них теперь нет.
 */
@Composable
private fun ResultCard(
  job: GenerationJob,
  onCancel: () -> Unit,
  onRetry: () -> Unit,
  onDelete: () -> Unit,
  onRate: (JobRating) -> Unit,
) {
  val done = job.status == JobStatus.DONE && job.artifacts.isNotEmpty()

  // Рамка по наведению вместо статичного «выбрано» из макета. Выделение в макете нарисовано, но
  // потреблять его нечему: доработка и видео и так висят на самой карточке, и «выбранная» никуда
  // больше не уходит. Подсветка под курсором — то же самое разделение карточек, но без выдуманного
  // состояния, которое ни на что не влияет.
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()

  Card(
    Modifier.fillMaxWidth().hoverable(interaction),
    border = if (hovered) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
  ) {
    Column {
      Box(
        Modifier
          .fillMaxWidth()
          .height(190.dp)
          .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
          // Открытие файла живёт здесь, а не иконкой в ряду: рука тянется к самому кадру. Иконку
          // «Открыть» из ряда убрали, и без этой строки открыть результат стало бы нечем вовсе.
          .clickable(enabled = done) {
            job.artifacts.firstOrNull()?.let { openInSystem(File(it)) }
          },
        contentAlignment = Alignment.Center,
      ) {
        Preview(job)
      }
      Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (done) {
          // Слева — оценка кадра, а не действия над ним. Повтор, доработка и «сделать видео» отсюда
          // убраны по просьбе: ряд из пяти иконок читался ребусом, а нужен он был раз в сто кадров.
          // Открытие файла тоже ушло — оно теперь на самой картинке, куда и тянется рука.
          IconAction(
            if (job.rating == JobRating.LIKED) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            if (job.rating == JobRating.LIKED) "Снять отметку" else "Нравится",
            tint = if (job.rating == JobRating.LIKED) MaterialTheme.colorScheme.primary else null,
          ) { onRate(JobRating.LIKED) }
          IconAction(
            Icons.Default.ThumbDown,
            if (job.rating == JobRating.DISLIKED) "Снять отметку" else "Не нравится",
            tint = if (job.rating == JobRating.DISLIKED) MaterialTheme.colorScheme.error else null,
          ) { onRate(JobRating.DISLIKED) }
          Spacer(Modifier.weight(1f))
          Text(
            formatCost(job.costUsd),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          IconAction(Icons.Default.DeleteOutline, "Удалить кадр и файл", onClick = onDelete)
        } else {
          if (job.status != JobStatus.FAILED) {
            Text(
              statusLabel(job.status),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(start = 6.dp),
            )
          }
          Spacer(Modifier.weight(1f))
          if (!job.status.isTerminal) {
            TextButton(onClick = onCancel) { Text("Отменить") }
          } else {
            TextButton(onClick = onRetry) { Text("Повторить") }
            // Убрать неудачную карточку тоже надо: иначе пачка из четырёх с двумя отказами так и
            // висит наполовину пустой, и «Открыть папку» не помогает — файлов там нет.
            IconAction(Icons.Default.DeleteOutline, "Убрать из истории", onClick = onDelete)
          }
        }
      }
      // Текста ошибки под рядом действий больше нет: он висел ниже карточки, ломал её выравнивание
      // в сетке и оставлял над собой пустой прямоугольник в высоту картинки. Теперь причина стоит
      // на месте самой картинки — там, где её и ищут глазами.
    }
  }
}

/**
 * Превью только для картинок. Кадр из видео потребовал бы декодера, и рисовать честную заглушку
 * с подписью «видео» правильнее, чем притворяться, что превью есть.
 */
@Composable
private fun Preview(job: GenerationJob) {
  val first = job.artifacts.firstOrNull()
  val isImage = job.request.modality == GenerationModality.IMAGE
  // produceState + IO: чтение и декодирование файла прямо в композиции подвешивают окно.
  val bitmap by produceState<ImageBitmap?>(initialValue = null, first, isImage) {
    value = if (isImage && first != null) withContext(Dispatchers.IO) { loadImage(File(first)) } else null
  }

  val ready = bitmap
  when {
    ready != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Image(
        bitmap = ready,
        contentDescription = job.request.prompt,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
      )
      // Размер кадра — по макету. Берётся у уже загруженного битмапа, а не читается с диска второй
      // раз, и поэтому не врёт: это ровно то, что показано выше.
      //
      // Плашка чёрная, а не из палитры: под ней произвольная картинка, и единственный цвет,
      // на котором подпись читается всегда, — тёмная подложка со светлым текстом.
      Surface(
        color = ScrimColor,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
      ) {
        Text(
          "${ready.width} × ${ready.height}",
          style = MaterialTheme.typography.labelSmall,
          color = Color(0xFFE8E9F0),
          modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
        )
      }
    }

    !job.status.isTerminal -> Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
      Text(
        job.note ?: statusLabel(job.status),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    // Провалившееся задание показывает ПРИЧИНУ, а не слово «ошибка»: слово занимало ровно столько
    // же места, а знать, почему не вышло, приходилось из отдельной строчки под карточкой.
    else -> FailureText(job)
  }
}

/**
 * Причина отказа на месте картинки.
 *
 * В карточку влезает четыре строки, а провайдер присылает до 300 символов — и отказ контент-фильтра
 * обычно занимает все триста. Обрезать причину и не дать её дочитать хуже, чем прежнее «ошибка»:
 * там хотя бы не возникало ощущения, что текст показан целиком. Больше её взять негде — «История»
 * показывает только успешные задания. Поэтому полный текст висит подсказкой при наведении, тем же
 * приёмом, что и у иконок-действий.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FailureText(job: GenerationJob) {
  val failed = job.status == JobStatus.FAILED
  val text = when (job.status) {
    JobStatus.DONE -> job.request.modality.label
    JobStatus.FAILED -> job.error ?: statusLabel(job.status)
    else -> statusLabel(job.status)
  }

  val body = @Composable {
    Text(
      text,
      style = MaterialTheme.typography.bodyMedium,
      color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      maxLines = 4,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(horizontal = 16.dp),
    )
  }

  // Подсказку вешаем только на отказ: у «готово» и «в очереди» текст короткий и не обрезается.
  if (!failed) {
    body()
    return
  }
  TooltipArea(tooltip = {
    Surface(
      shape = MaterialTheme.shapes.small,
      color = MaterialTheme.colorScheme.surfaceVariant,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
      Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.widthIn(max = 420.dp).padding(12.dp, 8.dp),
      )
    }
  }) {
    body()
  }
}

/** Подложка подписи поверх картинки: см. [Preview] — на произвольном кадре палитре доверять нельзя. */
private val ScrimColor = Color(0xB3000000)

/**
 * Подтверждение удаления.
 *
 * Спрашиваем всегда, даже у одной картинки: файл стирается насовсем, мимо корзины, и отменить
 * это нечем. В тексте — имя файла и кусок промпта, потому что в пачке из четырёх карточки
 * различаются только картинкой, а её в диалоге не видно.
 */
@Composable
private fun DeleteResultDialog(job: GenerationJob, onDismiss: () -> Unit, onConfirm: () -> Unit) {
  val files = job.artifacts.map { File(it).name }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (files.isEmpty()) "Убрать из истории?" else "Удалить файл?") },
    text = {
      Column {
        Text(
          if (files.isEmpty()) {
            "У этого задания файлов нет — исчезнет только запись в истории."
          } else {
            "Файл(ы) сотрутся с диска насовсем, не в корзину: " + files.joinToString(", ")
          },
          style = MaterialTheme.typography.bodyMedium,
        )
        // Про деньги предупреждаем прямо: сводка расходов считается по живому списку заданий, и
        // удалённое из неё исчезнет. Потраченного это не возвращает, а промолчать здесь значит
        // тихо занизить «Всего» — ровно то, от чего бережёт CostSummary.partial.
        job.costUsd?.let {
          Spacer(Modifier.height(8.dp))
          Text(
            "Из сводки расходов уйдёт ${formatCost(it)} — но списаны они уже были.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Spacer(Modifier.height(8.dp))
        Text(
          job.request.prompt.take(120).ifBlank { "без промпта" },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    },
    confirmButton = { TextButton(onClick = onConfirm) { Text("Удалить") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
  )
}

/** Иконка-действие с подсказкой: без подписи ряд иконок иначе читается как ребус. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun IconAction(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  hint: String,
  /** Цвет активной отметки. null — обычная приглушённая иконка действия. */
  tint: Color? = null,
  onClick: () -> Unit,
) {
  TooltipArea(tooltip = {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
      Text(hint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp, 6.dp))
    }
  }) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
      Icon(
        icon,
        contentDescription = hint,
        tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(18.dp),
      )
    }
  }
}
