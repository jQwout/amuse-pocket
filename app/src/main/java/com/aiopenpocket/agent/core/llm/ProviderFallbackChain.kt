package com.aiopenpocket.agent.core.llm

// @trace streaming-llm-chat-6595a1
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

// Экземпляр предоставляется через LlmModule.provideProviderFallbackChain (единственный binding).
// Каждый провайдер даёт СПИСОК конфигов: для NIM это модели из assets/config/models.json,
// отсортированные по priority. OpenAiCompatClient перебирает их по очереди с фоллбэком при ошибке.
class ProviderFallbackChain(
  // Функция, а не готовая мапа: каталог OpenRouter обновляется в рантайме (OpenRouterCatalogService),
  // и зафиксированный при сборке графа список жил бы до перезапуска процесса.
  private val configsFor: (LlmProvider) -> List<ProviderConfig>,
  // Лямбда (не значение) — как apiKeyResolver у ProviderConfig: читается заново на каждый resolve(),
  // поэтому смена модели в Настройках подхватывается со следующего же хода, без пересборки DI-графа.
  private val preferredModel: () -> String? = { null },
) {
  /** Статичная мапа — для тестов и для провайдеров, чей список моделей не меняется в рантайме. */
  constructor(
    configs: Map<LlmProvider, List<ProviderConfig>>,
    preferredModel: () -> String? = { null },
  ) : this({ configs[it].orEmpty() }, preferredModel)

  /**
   * Конфиги ТОЛЬКО запрошенного провайдера, в порядке попыток: выбранная пользователем модель
   * первой, остальные — фолбэком по priority.
   *
   * Кросс-провайдерной ветки здесь больше нет (была `AUTO`): провайдер выбирается явно, и молча
   * уехать к другому вендору запрос не может. Фолбэк между МОДЕЛЯМИ одного провайдера остался —
   * он не меняет ни адресата данных, ни прайс.
   */
  fun resolve(requested: LlmProvider): List<ProviderConfig> {
    val base = configsFor(requested)
    // "AUTO" — сентинел настроек «не выбирал вручную, идти по priority», а не имя модели. Режима
    // LlmProvider.AUTO больше нет, но эта строка живёт в SettingsRepository как дефолт, и без явной
    // проверки она молча искалась бы среди моделей (нашла бы ноль — безвредно, но по случайности).
    val preferred = preferredModel()?.takeIf { it.isNotBlank() && it != "AUTO" }
      ?: return base.take(MAX_ATTEMPTS)
    // Сравниваем ТОЛЬКО по имени модели: base уже сужен до одного провайдера, и прежняя проверка
    // `it.name == LlmProvider.NIM` теперь не просто лишняя — она бы отбрасывала выбор пользователя
    // на любом другом провайдере, молча возвращая его к дефолтной модели. Обрезаем ПОСЛЕ выбора:
    // выбранная модель может стоять в каталоге двухсотой.
    val (match, rest) = base.partition { it.model == preferred }
    return (match + rest).take(MAX_ATTEMPTS)
  }

  private companion object {
    /**
     * Сколько моделей пробуем максимум за один ход.
     *
     * Фолбэк лечит ОДИН класс проблем: у конкретной модели прямо сейчас rate-limit или она прилегла.
     * После двух-трёх отказов подряд дело не в модели, а в ключе, сети или самом провайдере — и
     * перебирать дальше значит греть телефон и жечь минуты пользователя. У NIM каталог и так
     * короткий, а у OpenRouter их триста: без потолка один неверный ключ означал бы триста
     * подряд 401.
     */
    const val MAX_ATTEMPTS = 5
  }
}
