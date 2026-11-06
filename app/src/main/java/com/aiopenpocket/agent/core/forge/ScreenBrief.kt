package com.aiopenpocket.agent.core.forge

// @trace screen-brief-and-mockup-before-the-forge-builds-82cfb2

import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.displayDescription
import com.aiopenpocket.agent.core.agent.displayName
import com.aiopenpocket.i18n.tr

/**
 * What the person wants the agent's screen to be, said before the forge writes a line: their own words,
 * the building blocks they ticked and the overall layout. The forge used to get one sentence the app made
 * up from the agent's name and description and guessed the rest; a brief is what it builds from now, and
 * what the Muse Image mockup is drawn from first.
 */
data class ScreenBrief(
  val text: String,
  val blocks: Set<ScreenBlock>,
  val layout: ScreenLayout,
) {
  companion object {
    /** The longest brief: the sheet's field stops here, and the forge request carries it whole. */
    const val TEXT_MAX = 1_200
  }
}

/**
 * The building blocks a forged screen can be made of; [forForge] is how the forge is told about each.
 * The order is top to bottom: the request lists the ticked ones "in this order", so the total that sits
 * "at the top" comes first.
 */
enum class ScreenBlock(val forForge: String) {
  TOTAL("a big total or counter at the top"),
  PROGRESS("a progress bar toward a goal the person sets"),
  ADD_FORM("a small form to add a record (text fields and an Add button)"),
  LIST("a list of the saved records, newest first"),
  PHOTO("a photo slot per record, using PhotoPicker"),
  CHART("simple bars comparing the records (drawn with Box widths, no chart library)"),
  ;

  val label: String
    get() = when (this) {
      LIST -> tr("Список записей", "List of records")
      ADD_FORM -> tr("Форма добавления", "Add form")
      TOTAL -> tr("Итог или счётчик", "Total or counter")
      PROGRESS -> tr("Прогресс к цели", "Progress to a goal")
      PHOTO -> tr("Фото", "Photos")
      CHART -> tr("Диаграмма", "Chart")
    }
}

enum class ScreenLayout(val forForge: String) {
  LIST("one vertical list, compact rows"),
  CARDS("a column of rounded cards, one per section"),
  DASHBOARD("a dashboard: the key number big on top, sections in cards below"),
  ;

  val label: String
    get() = when (this) {
      LIST -> tr("Список", "List")
      CARDS -> tr("Карточки", "Cards")
      DASHBOARD -> tr("Дашборд", "Dashboard")
    }
}

object ScreenBriefs {
  /** The starting point of the sheet: what the agent is for, as a sentence the person edits. */
  fun draft(definition: AgentDefinition): ScreenBrief {
    val purpose = definition.displayDescription.trim().ifBlank { definition.displayName }
    return ScreenBrief(
      text = tr("Экран для «${definition.displayName}»: $purpose", "A screen for \"${definition.displayName}\": $purpose"),
      blocks = setOf(ScreenBlock.LIST, ScreenBlock.ADD_FORM),
      layout = ScreenLayout.CARDS,
    )
  }
}
