# Регулируемый размер шрифта в чате

Дата: 2026-07-16. Статус: дизайн согласован в чате, ожидает review.

## 1. Цель

Дать пользователю выбрать размер шрифта сообщений в чате из четырёх пресетов
(Мелкий / Обычный / Крупный / Очень крупный) через экран Настроек. Выбор
сохраняется между запусками и применяется ко всем чатам.

Это надстройка НАД системным масштабом шрифта Android (accessibility): `sp`-кегли
уже реагируют на системную настройку; наш множитель применяется поверх неё,
только к тексту сообщений и только в приложении.

## 2. Зафиксированные решения

1. Контрол — 4 фиксированных пресета (не плавный слайдер).
2. Масштабируется только рендер текста пузыря (`MarkdownText`); поле ввода,
   тул-трейс, плашки, интерактивные driven-UI виджеты — без изменений.
3. Хранение — по имени enum (не по индексу), через тот же DataStore
   (`UserPreferencesRepository`), что и «лимит витков гоал-мода».
4. Дефолт — `MEDIUM` (×1.0). Неизвестное/устаревшее сохранённое значение
   разрешается в `MEDIUM`.
5. `MarkdownText` используется только в чате — доставка множителя параметром,
   без CompositionLocal.

## 3. Доменная модель

Новый файл `app/src/main/java/com/aiopenpocket/ui/settings/ChatFontScale.kt`:

```kotlin
enum class ChatFontScale(val scale: Float, val label: String) {
  SMALL(0.85f, "Мелкий"),
  MEDIUM(1.0f, "Обычный"),
  LARGE(1.15f, "Крупный"),
  XLARGE(1.30f, "Очень крупный");

  companion object {
    val DEFAULT = MEDIUM
    /** Устойчивый разбор сохранённого имени: неизвестное/старое значение → DEFAULT, не throw. */
    fun fromName(name: String?): ChatFontScale =
      entries.firstOrNull { it.name == name } ?: DEFAULT
  }
}
```

Множители подобраны так, чтобы XLARGE оставался читаемым в пузыре, не ломая
верстку: базовый `bodySmall` (~14sp) даёт диапазон ≈12–18sp.

## 4. Персистентность

`UserPreferencesRepository` (DataStore Preferences) получает — по образцу
существующих `goalModeMaxIterations`/`language`:

```kotlin
private val CHAT_FONT_SCALE = stringPreferencesKey("chat_font_scale")

val chatFontScale: Flow<ChatFontScale> =
  context.userPreferencesDataStore.data.map { ChatFontScale.fromName(it[CHAT_FONT_SCALE]) }

suspend fun getChatFontScale(): ChatFontScale =
  ChatFontScale.fromName(context.userPreferencesDataStore.data.first()[CHAT_FONT_SCALE])

suspend fun setChatFontScale(value: ChatFontScale) {
  context.userPreferencesDataStore.edit { it[CHAT_FONT_SCALE] = value.name }
}
```

Хранение строкой (`value.name`) — как enum-значения в остальном коде; переупорядочивание
пресетов не ломает уже сохранённый выбор.

## 5. Настройки (UI)

- `SettingsUiState` получает поле `chatFontScale: ChatFontScale = ChatFontScale.DEFAULT`.
- `SettingsViewModel`:
  - в `load()` читает `userPreferencesRepository.getChatFontScale()` (рядом с
    `getGoalModeMaxIterations()`);
  - `fun updateChatFontScale(value: ChatFontScale)` обновляет state и сбрасывает
    `saved = false`;
  - `save()` вызывает `setChatFontScale(current.chatFontScale)`;
  - dirty-чек в `save()` дополняется сравнением `chatFontScale`.
- `SettingsScreen`: секция «Размер шрифта в чате» — ряд из 4 выбираемых чипов
  (по одному на пресет), визуально как существующие чипы выбора модели/персоны;
  выделен активный. Каждый чип показывает `label`.

## 6. Доставка в чат

- `ChatViewModel` собирает `userPreferencesRepository.chatFontScale` в
  `ChatUiState.chatFontScale: ChatFontScale = ChatFontScale.DEFAULT` — буквально
  копия ветки, которой уже собирается `goalModeMaxIterations`.
- `ChatScreen` при отрисовке `MessageBubble` передаёт `fontScale =
  state.chatFontScale.scale` дальше в оба вызова `MarkdownText` внутри пузыря
  (обычный текст сообщения и markdown-развёртка неинтерактивной карточки).

## 7. Масштабирование в MarkdownText

`MarkdownText` получает новый параметр `fontScale: Float = 1f` (дефолт 1 —
существующие не-чатовые вызовы, если появятся, не меняются). Весь набор стилей,
который компонент строит из `MaterialTheme.typography` (тело, заголовки, списки,
инлайн-код, таблица), масштабируется чистым хелпером:

```kotlin
/** Умножает fontSize и lineHeight на scale; при scale == 1f возвращает исходный стиль без изменений. */
internal fun TextStyle.scaledBy(scale: Float): TextStyle =
  if (scale == 1f) this
  else copy(
    fontSize = fontSize * scale,
    lineHeight = if (lineHeight.isSpecified) lineHeight * scale else lineHeight,
  )
```

`fontSize * scale` для `TextUnit` уже определён в Compose. `lineHeight`
масштабируется вместе с кеглем, чтобы межстрочный интервал не разъезжался;
`Unspecified` не трогаем. Хелпер применяется к каждому стилю в
`buildMarkdownTypography` и к body-стилю обычного текста.

## 8. Граница охвата

Масштабируется: весь текст, отрендеренный `MarkdownText` внутри пузыря сообщения
— пользовательские и ассистентские сообщения, а также неинтерактивная карточка,
развёрнутая в markdown (это тоже текст пузыря).

НЕ масштабируется: поле ввода, тул-трейс-панель, заголовки/плашки экрана,
бейджи, интерактивные driven-UI виджеты (рендерятся мимо `MarkdownText`).

## 9. Тестирование

- **Pure unit** — `ChatFontScaleTest`: `fromName` для каждого имени, для `null`,
  для мусора → `DEFAULT`; `scale` значения пресетов.
- **Pure unit** — `MarkdownScaleTest`: `TextStyle.scaledBy(1f)` возвращает тот же
  стиль; `scaledBy(1.3f)` умножает fontSize и заданный lineHeight; `Unspecified`
  lineHeight не падает.
- **Repository (Robolectric/DataStore)** — round-trip: `setChatFontScale(XLARGE)`
  → `getChatFontScale()` == XLARGE; пустой store → `DEFAULT`.
- **SettingsViewModel** — загрузка отражает сохранённое; `updateChatFontScale` +
  `save()` персистит; dirty-чек реагирует на изменение шрифта.
- **ChatViewModel** — `chatFontScale` из Flow доезжает в `ChatUiState`.
- **Compose (MarkdownText)** — один и тот же текст при MEDIUM и XLARGE даёт разный
  эффективный fontSize (или проверка через тег на применённый стиль).

## 10. Вне scope

- Плавный слайдер и произвольный процент.
- Масштаб driven-UI карточек, кода-блоков как отдельная настройка, поля ввода.
- Пер-чатовый (а не глобальный) размер.
- Живой предпросмотр в Настройках (пресет применяется сразу в чате — этого
  достаточно).
- Изменение системного `fontScale` устройства.

## 11. Definition of Done

- В Настройках есть выбор из 4 пресетов размера шрифта чата; выбор виден и
  переживает перезапуск приложения.
- Текст сообщений в чате рендерится в выбранном размере; поле ввода, тул-трейс и
  интерактивные виджеты не меняются.
- Неизвестное сохранённое значение не роняет приложение (→ MEDIUM).
- Юнит/Compose тесты, `:app:assembleDebug`, `traxer check` — проходят.
