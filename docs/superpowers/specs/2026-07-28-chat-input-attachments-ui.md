# Композер чата: переработка ввода + вложения (UI-спека)

Дата: 2026-07-28
Статус: **входные данные от владельца продукта, НЕ реализовано.** Это фаза C трёхчастной работы
(см. «Место в общей картине»). Записано дословно, чтобы не потерялось в переписке.

## Место в общей картине

Запрос «переработка ввода + файлы и фото» разложился на три проекта с разной ценой и разной
пользой по отдельности:

- **A. OpenRouter как провайдер** — разрешающий шаг. В каталоге `models.json` сегодня десять моделей
  NIM и ни одной вижн-способной; понятия модальности в коде нет вообще. Без этого фото отправить
  некуда. Ценен сам по себе. **Делается первым — решение владельца.**
- **B. Мультимодальный формат сообщения** — `ChatMessage.content` сейчас `String?`, нужен массив
  частей (`[{type:"text"}, {type:"image_url"}]`) плюс поле вложения в `Message`. Трогает ядро
  LLM-слоя, которое сегодня работает.
- **C. Вложения в UI** — эта спека. Пикер, разрешения, превью, и переработка самого композера.

Порядок не случаен: B трогает ядро, и если он поедет вместе с новым UI, разбираться, что именно
сломалось, будет дороже.

## Кнопка отправки/микрофона (морф)

> Дословно от владельца продукта:

```
Compose: кнопка-морф в тулбаре инпута.

Состояние: val hasText = text.isNotBlank()

Контейнер: Box 38.dp, CircleShape, clickable.
  background = animateColorAsState(
    if (hasText) accent else surfaceVariant,
    tween(280, easing = FastOutSlowInEasing)
  )

Внутри — два слоя иконок, оба в Box(fill), центрированы,
анимируются одновременно (не AnimatedContent, а два AnimatedVisibility/
graphicsLayer, чтобы был кроссфейд без "прыжка"):

  progress = animateFloatAsState(if (hasText) 1f else 0f,
             tween(280, easing = FastOutSlowInEasing))

  Mic:   alpha = 1 - progress
         translationY = progress * 10.dp.toPx()
         scale = lerp(1f, 0.6f, progress)
         rotationZ = lerp(0f, -25f, progress)
         tint = onSurfaceVariant

  ArrowUp: alpha = progress
         translationY = (1 - progress) * -10.dp.toPx()
         scale = lerp(0.6f, 1f, progress)
         rotationZ = lerp(25f, 0f, progress)
         tint = onAccent

Клик: hasText -> onSend(), иначе onVoiceInput().
```

**Требование, выделенное отдельно:** обе иконки лежат в ОДНОМ `Box` друг поверх друга и
анимируются ОДНИМ общим прогрессом. Не `AnimatedContent` — тот меняет размер контейнера при
переключении, и появляется дёрганье. Это прямое указание, а не рекомендация.

## Лист выбора файла/фото

> Дословно от владельца продукта:

```
Compose: ModalBottomSheet (M3) на клик по скрепке/фото в тулбаре.

rememberModalBottomSheetState(skipPartiallyExpanded = true)
containerColor = surfaceContainerHigh, shape = RoundedCornerShape(
  topStart = 28.dp, topEnd = 28.dp), drag handle по умолчанию.

Контент — Column(padding 8.dp bottom = navigationBarsPadding + 16.dp):
  заголовок "Добавить" (titleMedium, padding 20.dp)
  список строк, каждая = Row height 56.dp, clickable, ripple,
    Icon 24.dp (onSurfaceVariant) + Spacer 16.dp + Text bodyLarge:
      - Камера            -> TakePicture
      - Фото из галереи   -> PickVisualMedia(ImageOnly)
      - Файл              -> OpenDocument(arrayOf("*/*"))

Лончеры: rememberLauncherForActivityResult(...)
Перед запуском камеры — разрешение CAMERA через
rememberLauncherForActivityResult(RequestPermission()).
После выбора: scope.launch { sheetState.hide() }.invokeOnCompletion {
  showSheet = false; onAttach(uri) }
```

## Замечания реализатора (не меняют спеку, но всплывут при работе)

Записаны здесь, чтобы не открывать их заново на этапе C:

- **Лист выбора и существующий паттерн.** В настройках уже есть `SelectorSheet`/`SelectorSheetContent`
  с тем же жестом. Строки «Камера/Галерея/Файл» — другой контент, но выносить содержимое листа
  отдельным composable стоит по той же причине: `ModalBottomSheet` — popup-окно, `onRoot()` в
  скриншот-тесте его не захватывает, и вёрстка листа осталась бы без эталона.
- **Кнопка-морф и голосовой ввод.** Сейчас микрофон живёт отдельной кнопкой `MicButton` со своим
  состоянием (`VoiceInputState`: idle/listening/...). Морф-кнопка совмещает микрофон и отправку в
  одном месте, значит состояние записи надо будет чем-то показывать — либо третьим слоем в том же
  контейнере, либо панелью записи, которая уже подменяет поле ввода целиком.
- **Куда класть вложение.** Файл-песочница агента уже существует (`filesDir/agent`, `file_tool`), и
  `AgentFile` есть в модели. Скорее всего вложения лягут туда же, а не в новое хранилище.
- **`Message` придётся расширять.** Сегодня `Message(id, conversationId, role, content, toolCallsJson,
  cardSpec, createdAt)` — вложению места нет. Это фаза B, но в UI всплывёт сразу: превью в ленте
  нечем рисовать.
- **Разрешения.** Камера требует `CAMERA`. `PickVisualMedia` (Photo Picker) разрешений НЕ требует —
  это осознанно выбранный контракт, менять его на `GetContent` не нужно.
