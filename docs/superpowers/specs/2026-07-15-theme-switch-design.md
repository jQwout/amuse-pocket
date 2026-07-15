# Смена темы (Светлая/Тёмная/Системная) + переезд на палитру lime/ink/paper

**Дата:** 2026-07-15
**Ветка реализации:** `feature/user-agent-builder` (текущая; отдельной ветки и облака не заводим — решение юзера)
**Статус:** дизайн утверждён, ждёт плана реализации

## Цель

Две связанные задачи в одной фиче:

1. **Переезд на указанную палитру** — заменить текущую фиолетовую Material-3-палитру на
   lime/ink/paper (палитра и обе схемы заданы юзером дословно).
2. **Смена тем** — рабочий переключатель Светлая/Тёмная/Системная с персистентностью;
   дефолт — Системная.

Меняем **только цвет и механику темы**. Структура экранов, тексты, навигация и
типографика (Lora) не трогаются. Glass-слой сохраняется и получает минимальную
theme-awareness — без редизайна стекла.

## Исходное состояние

- **`Theme.kt`** жёстко залочен в тёмную (`darkTheme: Boolean = true`), а
  `LightColorScheme = DarkColorScheme` — то есть **настоящей светлой темы нет**.
  `dynamicColor` уже выключен.
- **`Color.kt`** — 18 фиолетовых токенов.
- **139 вхождений старых токенов в 17 UI-файлах** (импорты + использования): экраны
  импортируют сырые токены (`import com.aiopenpocket.ui.theme.PurpleAccent`) и используют
  их как compile-time-значения: `tint = PurpleAccent`, `.background(CardDark)`,
  `containerColor = PurpleAccent`. Это **top-level `val`, а не чтение
  `MaterialTheme.colorScheme`** → они физически не могут реагировать на смену темы.
- Ещё **~49 инлайновых `Color(0x…)`** в 12 экранах (PlanCard, DrivenUiEngine и др.).
- **`GlassTokens`** — статический `object`; в его же доке сказано: «Тема всегда тёмная …
  значения статичны, CompositionLocal не нужен». Тинты — белые поверх тёмного;
  аврора — на `PurpleAccent`/`PurpleAccentDark`.
- **`auroraBackground`** — **не composable**: обычный `Modifier`-extension, читающий
  `GlassTokens.AuroraSpots` внутри `drawWithCache` (draw-скоуп).
- Все **~31 вызов `MyApplicationTheme`** — без аргументов.
- Все **5 продакшн-вызовов `auroraBackground()`** — без аргументов, из composable-скоупа.
- **`SettingsRepository`** — `EncryptedSharedPreferences` (файл `secure_settings`),
  синхронный и нереактивный; предназначен для секретов (API-ключи, выбор модели).
- **DI — Metro**: `AppGraph` (`@DependencyGraph(AppScope::class)`) отдаёт
  `userPreferencesRepository`, `heartbeatRunner`. `MainActivity` берёт граф через
  `(application as AiOpenPocketApp).appGraph`.

> **Ключевое следствие.** Без ренейма 139 сайтов переключатель был бы почти невидимым:
> палитра сменилась бы, а тумблер не двигал бы почти ничего. Фича вышла бы фиктивной.
> Поэтому ренейм — не «уборка», а обязательная часть работы.

## Принятые решения

| Вопрос | Решение |
|---|---|
| Глубина | Тема + переключатель, **dark-first**: glass не редизайним, инлайновые `Color(0x…)` не вычищаем |
| Режимы | Светлая / Тёмная / Системная, **дефолт Системная** |
| Аврора на светлой | **Отключается** — чистый `PaperBg`. Тёмная сохраняет аврору полностью |
| 139 хардкодов | **Полный ренейм сразу** на `MaterialTheme.colorScheme.*`; старые имена удаляем |
| Палитра | Файл юзера дословно |
| `error`/`onError` | Схемы юзера дословно → падают на дефолты M3; текущие `CancelRed`/`CancelRedBg` уходят |
| Где | Текущая ветка `feature/user-agent-builder`, без облака и пуша в origin |
| WIP | Коммитим первым; тема — отдельный чистый коммит с трейлером `Trace:` |
| `dynamicColor` | Удаляем: перебивал бы заданную палитру (и так был выключен) |

## 1. Палитра — `ui/theme/Color.kt`

Файл переписывается целиком палитрой юзера: бренд-акцент (`LimeSignal`, `LimeButton`,
`LimeOnDark`, `OliveAccent`, `OliveOnLight`), тёмные нейтрали (`InkBg`, `InkSurface`,
`InkSurface2`, `InkText`, `InkTextSub`, `InkTextDim`, `InkOutline`) и светлые нейтрали
(`PaperBg`, `PaperSurface`, `PaperSurface2`, `PaperText`, `PaperTextSub`, `PaperTextDim`,
`PaperOutline`).

Удаляются все 18 старых токенов: `DarkBg`, `DarkOnBg`, `PurpleAccent`, `PurpleAccentDark`,
`CardDark`, `PersonaCardBg`, `PersonaQuoteBg`, `BorderDark`, `UserBubbleBg`, `UserBubbleFg`,
`CancelRed`, `CancelRedBg`, `Purple80`, `PurpleGrey80`, `Pink80`, `Purple40`, `PurpleGrey40`,
`Pink40`.

Package остаётся `com.aiopenpocket.ui.theme`. В репозитории путь на диске (`com/example/…`)
не совпадает с package (`com.aiopenpocket.…`) — это существующая особенность, её не трогаем.

## 2. Тема — `ui/theme/Theme.kt`

Схемы `AppDarkColors` / `AppLightColors` берутся из файла юзера дословно.

Добавляется режим и резолв:

```kotlin
enum class ThemeMode { LIGHT, DARK, SYSTEM }

@Composable
fun MyApplicationTheme(
  mode: ThemeMode = ThemeMode.SYSTEM,
  content: @Composable () -> Unit,
) {
  val dark = when (mode) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
  }
  MaterialTheme(
    colorScheme = if (dark) AppDarkColors else AppLightColors,
    typography = Typography,
    content = content,
  )
}
```

Параметры `darkTheme` и `dynamicColor` удаляются вместе с импортами
`dynamicDarkColorScheme`/`dynamicLightColorScheme`.

**Обратная совместимость достаётся бесплатно:** все ~31 существующий вызов идут как
`MyApplicationTheme { … }` без аргументов, поэтому дефолт `mode = SYSTEM` сохраняет их
компилируемость — ни одно превью и ни один тест править не нужно.

## 3. Ренейм 139 вхождений

Маппинг старых токенов на роли схемы (везде через `MaterialTheme.colorScheme.*`):

| Было | Стало |
|---|---|
| `PurpleAccent` | `primary` |
| `PurpleAccentDark` | `onPrimary` |
| `DarkBg` | `background` |
| `DarkOnBg` | `onBackground` |
| `CardDark` | `surfaceVariant` |
| `BorderDark` | `outline` |
| `UserBubbleBg` | `primaryContainer` |
| `UserBubbleFg` | `onPrimaryContainer` |
| `PersonaCardBg` | `surface` |
| `PersonaQuoteBg` | `surfaceVariant` |
| `CancelRed` | `error` |
| `CancelRedBg` | `onError` |

Затронутые файлы (17): `SettingsScreen` (26), `HomeScreen` (20), `RoadmapScreen` (18),
`AgentDetailScreen` (15), `AgentsScreen` (12), `PersonaCard` (9), `LibraryScreen` (7),
`PersonaSelectionStep` (5), `PlanListScreen` (4), `AcademyBottomNav` (4), `PlanDetailScreen` (4),
`PlanCard` (3), `ChatScreen` (3), `IdentityStep` (3), `GlassSurface` (2),
`AuroraBackground` (2), `LanguageSelectionStep` (2).

**Компилятор — наш чек-лист.** Раз старые имена удалены, сборка перечислит все 139 мест;
молча пропустить сайт невозможно. Это главная причина, почему удаление имён (а не
deprecated-алиасы) даёт надёжный результат.

## 4. Хранилище режима — новый `ThemeRepository`

Режим темы **не** кладём в `SettingsRepository`: тот построен на
`EncryptedSharedPreferences` и предназначен для секретов; тема секретом не является, а
инициализация `MasterKey` — лишняя работа на старте. Держим `SettingsRepository`
сфокусированным на своей роли.

Новый файл `data/repository/ThemeRepository.kt`:

```kotlin
@SingleIn(AppScope::class)
class ThemeRepository @Inject constructor(context: Context) {
  private val prefs = context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)
  private val _mode = MutableStateFlow(readMode())
  val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

  fun setMode(value: ThemeMode) {
    prefs.edit().putString("theme_mode", value.name).apply()
    _mode.value = value
  }

  // Строка → enum; неизвестное или отсутствующее значение → SYSTEM.
  private fun readMode(): ThemeMode =
    prefs.getString("theme_mode", null)
      ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
      ?: ThemeMode.SYSTEM
}
```

Контракт: единственная ответственность — хранить и отдавать `ThemeMode` реактивно.
Неизвестное или отсутствующее значение деградирует в `SYSTEM` (дефолт).

В `AppGraph` добавляется `val themeRepository: ThemeRepository` рядом с
`userPreferencesRepository`.

## 5. Проброс — `MainActivity`

Режим читается **выше** вызова темы (сейчас это строка 90) и передаётся внутрь:

```kotlin
val themeRepo = remember { appGraph.themeRepository }
val mode by themeRepo.mode.collectAsState()
MyApplicationTheme(mode = mode) { … }
```

Существующее чтение `appGraph.userPreferencesRepository` внутри темы остаётся как есть.

## 6. Settings — секция «ТЕМА»

В `SettingsScreen` добавляется секция с тремя вариантами (Светлая / Тёмная / Системная),
построенная по образцу существующего пикера «МОДЕЛЬ». `SettingsUiState` получает
`themeMode`, `SettingsViewModel` — обработчик смены, который пишет в `ThemeRepository`.
Переключение применяется мгновенно (StateFlow → recomposition), рестарт не требуется.

## 7. Glass — минимальная theme-awareness

Объём намеренно минимальный: **свап цветов, не редизайн стекла.**

**Почему нельзя просто инвертировать цвета.** Glass-слой закодирован в тёмной идиоме —
«светлая плёнка + свечение поверх тёмного холста». Механизмы перестают означать то, ради
чего вводились:

- тинты `White.copy(0.10f/0.06f)` поверх `InkBg` (#0A0A0A) *приподнимают* поверхность;
  поверх `PaperBg` (#FAFAF8) 10% белого по почти-белому — панель исчезает;
- `BorderBrush` (белый градиент 25%→5%) — это **блик** на грани, а не бордер; на светлой
  он либо невидим, либо превращается в обычную обводку;
- `AuroraSpots` — это **свечения**, они работают только потому, что светлее почти-чёрного
  холста. Синее пятно `#1B2A4A` при alpha 0.55 поверх #FAFAF8 — не свечение, а серо-синяя
  клякса; акцент при 0.45 — мутное пятно.

**Принятые решения по слоям:**

- **`GlassTokens`**: `StrongTint`, `LightTint`, `BorderBrush`, `AuroraSpots` становятся
  `@Composable`-геттерами (читают тему). На светлой тинты и бордер флипаются на тёмные
  (`Color.Black.copy(…)`): это даёт корректную, слегка утопленную карточку с честной
  волосяной обводкой — не «стекло», но и не дефект. `BlurRadius` и `CardShape` остаются
  статикой: они от темы не зависят.
- **Аврора на светлой отключается** (`AuroraSpots` → пустой список, фон = чистый `PaperBg`).
  Причина: тинты и бордер деградируют приемлемо, а аврора — единственный слой, который
  на светлой выглядит именно *плохо*, а не просто «не-стеклянно». Свечению нужен тёмный
  холст; на светлом тот же приём даёт грязь. Пустой `PaperBg` читается как намеренный
  минимализм. Тёмная тема сохраняет аврору без изменений.
- **`auroraBackground`** становится `@Composable`, с дефолтами из темы:
  ```kotlin
  @Composable
  fun Modifier.auroraBackground(
    baseColor: Color = MaterialTheme.colorScheme.background,
    spots: List<AuroraSpot> = GlassTokens.AuroraSpots,
  ): Modifier = this.drawWithCache { … }
  ```
  Споты вычисляются в composable-скоупе и захватываются в `drawWithCache`. **Все 5
  продакшн-вызовов зовут функцию без аргументов из composable — их править не нужно.**
  Отключение авроры на светлой не требует спец-кейса: при пустом списке `spots.forEach`
  не делает ничего, и остаётся `drawRect(baseColor)` — то есть чистый `PaperBg`.
- **`GlassSurface` / `GlassCard`**: только `backgroundColor = DarkBg` →
  `MaterialTheme.colorScheme.background`. Дефолты вида `tint = GlassTokens.LightTint`
  продолжают работать: composable-дефолты в `@Composable`-функциях легальны.

**Принятый компромисс:** светлая тема получает свой характер — плоский минимализм на
чистой бумаге — вместо плохой копии тёмного эффекта. Стекло на ней корректно и читаемо,
но это не «настоящее» стекло: блик стал обводкой, матовость — лёгкой утопленностью.
Полноценное светлое стекло (перебалансировка альф, тени вместо свечений, возможно
собственная светлая аврора) — отдельная фича, сознательно вынесенная за скоуп.

## 8. Тестирование

- **Roborazzi**: смена палитры сдвинет ~19 бейзлайнов → перезапись
  (`recordRoborazziDebug`) с визуальной проверкой глазами, а не слепым принятием.
- **Новый светлый скриншот**: один тест рендерит glass-компоненты в `ThemeMode.LIGHT` —
  это исполняемое доказательство, что переключение действительно работает, а не только
  компилируется.
- **`GlassTokensTest`** читает `AuroraSpots` напрямую → адаптируется под
  composable-геттеры.
- **`ThemeRepository`**: юнит-тест на дефолт (`SYSTEM` при пустых prefs), round-trip
  записи/чтения и деградацию неизвестного значения в `SYSTEM`.
- **Сборка** — сама по себе проверка ренейма: пока остаётся хоть один старый токен,
  проект не собирается.

## 9. Traceability (по CLAUDE.md)

- `traxer id` → новая фича «Смена темы + палитра lime/ink/paper»; запись в
  `traceability.yaml` с rationale (**почему**: пользователю нужен выбор темы, а тёмная
  залочка и фиолетовая палитра были временными).
- Маркеры `@trace <id>` в `Theme.kt` и `ThemeRepository.kt`.
- Трейлер `Trace: <id>` в коммите; `traxer check` — до коммита.
- **Отдельно:** rationale фичи `glass-design-system-21a3fe` утверждает «Тема всегда
  тёмная» — после этой работы это станет неправдой. Формулировку нужно обновить, а не
  blessить вслепую.

## Риски и границы

- **Светлая тема на дефолте.** Дефолт `SYSTEM` означает, что юзеры со светлой системной
  темой сразу попадают на светлую. Худшее (мутная аврора) снято её отключением, но
  светлая всё равно останется плоской и менее проработанной, чем тёмная. Это осознанный
  компромисс dark-first; при желании лечится сменой дефолта на `DARK` — одна строка.
- **Инлайновые `Color(0x…)` (~49 в 12 файлах) остаются theme-insensitive** — по
  выбранному варианту B их не чистим. На светлой теме отдельные пятна (например, карточки
  плана, driven-ui) останутся с фиксированными цветами. Кандидат в отдельную задачу.
- **WIP в рабочем дереве.** `RoadmapScreen.kt` (18 цветовых ссылок) и `DrivenUiEngine.kt`
  сейчас изменены и не закоммичены. Поэтому WIP коммитится **до** начала работы —
  иначе тема и чужие правки слипнутся в один диф, а трейс-коммит выйдет грязным.
- **Вне скоупа:** полировка светлого стекла, чистка инлайновых литералов, редизайн
  glass, изменение типографики/структуры экранов, dynamic color.
