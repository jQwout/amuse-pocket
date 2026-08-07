package com.aiopenpocket.genai

// @trace desktop-genai-client-4c63b6

/**
 * Отбор, порядок и категории в окне заготовок промптов.
 *
 * Живёт в ядре по той же причине, что [ModelPicking]: у `:app-desktop` тестов нет вовсе, а здесь
 * склоняются числительные, считаются категории и решается, что заготовка делает с уже набранным
 * текстом. В экране остаётся Compose и ничего больше.
 */

/** Строка списка: заготовка плюс то, что мы знаем о ней со стороны. */
data class PromptCard(
  val preset: PromptPreset,
  /** Сколько раз вставляли. Считается с нуля, см. [PromptUsage]. */
  val uses: Int,
  val favorite: Boolean,
  /** Заготовка из файла пользователя, а не встроенная, — раздел «Мои». */
  val own: Boolean,
) {
  val id: String get() = preset.id
  val title: String get() = preset.title
  val category: String get() = preset.category.ifBlank { OTHER_CATEGORY }
}

/** Раздел для заготовок без категории. Не «прочее» словом в UI, а одно значение на всё приложение. */
const val OTHER_CATEGORY = "Разное"

/**
 * Псевдоразделы поверх настоящих категорий. Живут отдельно, потому что отвечают на другой вопрос:
 * не «о чём заготовка», а «откуда она у меня».
 */
enum class PromptScope(val label: String) {
  ALL("Все"),
  FAVORITES("Избранное"),
  MINE("Мои"),
}

enum class PromptSort(val label: String) {
  POPULAR("Часто используемые"),
  ALPHABET("По алфавиту"),
  MINE_FIRST("Свои сверху"),
}

fun buildPromptCards(
  presets: List<PromptPreset>,
  usage: Map<String, Int>,
  favorites: Set<String>,
  ownIds: Set<String>,
): List<PromptCard> = presets.map { preset ->
  PromptCard(
    preset = preset,
    uses = usage[preset.id] ?: 0,
    favorite = preset.id in favorites,
    own = preset.id in ownIds,
  )
}

/**
 * Категории с количеством — ровно те, что есть в библиотеке, и в порядке первого появления.
 *
 * Порядок из библиотеки, а не по алфавиту: файл заготовок писан руками и разложен по смыслу
 * (сперва предметка, приписки в конце), и алфавит перемешал бы это без всякой пользы.
 */
fun promptCategories(cards: List<PromptCard>): List<Pair<String, Int>> =
  cards.groupBy { it.category }.map { (name, group) -> name to group.size }

fun scopeCount(cards: List<PromptCard>, scope: PromptScope): Int = cards.count { it.inScope(scope) }

fun PromptCard.inScope(scope: PromptScope): Boolean = when (scope) {
  PromptScope.ALL -> true
  PromptScope.FAVORITES -> favorite
  PromptScope.MINE -> own
}

/**
 * Поиск по названию, тексту и тегам. Текст — обязательно: половина заготовок отличается друг от
 * друга не заголовком, а формулировкой внутри, и человек ищет как раз по ней.
 */
fun PromptCard.matches(query: String): Boolean {
  if (query.isBlank()) return true
  return preset.title.contains(query, ignoreCase = true) ||
    preset.prompt.contains(query, ignoreCase = true) ||
    preset.tags.any { it.contains(query, ignoreCase = true) } ||
    category.contains(query, ignoreCase = true)
}

fun sortPromptCards(cards: List<PromptCard>, sort: PromptSort): List<PromptCard> = when (sort) {
  // При равном числе использований — по алфавиту, иначе порядок у ни разу не вставленных заготовок
  // (а поначалу это все) зависел бы от того, как их сложили в файл.
  PromptSort.POPULAR -> cards.sortedWith(compareByDescending<PromptCard> { it.uses }.thenBy { it.title })
  PromptSort.ALPHABET -> cards.sortedBy { it.title }
  PromptSort.MINE_FIRST -> cards.sortedWith(compareByDescending<PromptCard> { it.own }.thenBy { it.title })
}

/** «12 исп.» под карточкой. Пусто, если ни разу: ноль в углу карточки — шум, а не сведения. */
fun usesLabel(uses: Int): String = if (uses == 0) "" else "$uses исп."

/**
 * Подсказка под заготовкой: чем она является и что в ней надо заполнить.
 *
 * Собирается из фактов, а не пишется редактором: категория, число вставок и напоминание про
 * фигурные скобки — но напоминание только там, где скобки реально есть.
 */
fun promptSummary(card: PromptCard): String = buildList {
  add(card.category)
  if (card.uses > 0) {
    add("использован ${card.uses} ${pluralRu(card.uses, "раз", "раза", "раз")}")
  }
  if (card.preset.placeholders.isNotEmpty()) {
    add("в фигурных скобках — то, что подставляется под кадр")
  }
  if (PromptPreset.PLACEHOLDER in card.preset.prompt) {
    add("приписка: оборачивает уже набранное")
  }
}.joinToString(" · ")
