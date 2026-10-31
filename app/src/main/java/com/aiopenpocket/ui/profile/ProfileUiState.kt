package com.aiopenpocket.ui.profile

// @trace profile-screen-7354a7

import com.aiopenpocket.agent.core.memory.model.Fact
import com.aiopenpocket.agent.core.memory.model.UserProfile
import com.aiopenpocket.agent.core.memory.model.UserProfileKeys
import com.aiopenpocket.ui.settings.UsageSummary

/** The editable "about me" fields, in the order the screen shows them. */
enum class ProfileField { NAME, ADDRESS, AGE, GENDER, RELIGION, ABOUT }

/**
 * How the remembered facts are grouped on screen — simply, by the category the fact was saved under, not by
 * a taxonomy of our own: `profile` is what the person told about themselves, `preference` is how they like
 * things done, everything else is the rest.
 */
enum class FactGroup { ABOUT_YOU, PREFERENCES, OTHER }

data class FactSection(val group: FactGroup, val facts: List<Fact>)

data class ProfileUiState(
  /** False until the saved profile has been read: the fields must not flash "not set" for a person who has a name. */
  val loaded: Boolean = false,
  /** What is in the fields right now (untrimmed — trimming happens on save). */
  val draft: UserProfile = EMPTY_PROFILE,
  /** What is stored, trimmed. [dirty] is the difference between the two. */
  val persisted: UserProfile = EMPTY_PROFILE,
  /**
   * Whether the religion field is shown. Decided once at load and kept: the field is for a person who set
   * it, and it must not vanish from under their cursor when they clear the text to retype it.
   */
  val showReligion: Boolean = false,
  /** "Saved" confirmation; cleared by the next edit. */
  val justSaved: Boolean = false,
  val factSections: List<FactSection> = emptyList(),
  /** A fact the person asked to forget and has not confirmed yet. */
  val pendingDelete: Fact? = null,
  /** Spend over the last week; `null` — nothing read yet or nothing to report. */
  val usage: UsageSummary? = null,
) {
  val dirty: Boolean get() = draft.trimmed() != persisted
}

internal val EMPTY_PROFILE = UserProfile(name = "", preferredAddress = "")

internal fun UserProfile.trimmed() = UserProfile(
  name = name.trim(),
  preferredAddress = preferredAddress.trim(),
  age = age.trim(),
  gender = gender.trim(),
  religion = religion.trim(),
  extraInfo = extraInfo.trim(),
)

internal fun UserProfile.valueOf(field: ProfileField): String = when (field) {
  ProfileField.NAME -> name
  ProfileField.ADDRESS -> preferredAddress
  ProfileField.AGE -> age
  ProfileField.GENDER -> gender
  ProfileField.RELIGION -> religion
  ProfileField.ABOUT -> extraInfo
}

internal fun UserProfile.with(field: ProfileField, value: String): UserProfile = when (field) {
  ProfileField.NAME -> copy(name = value)
  ProfileField.ADDRESS -> copy(preferredAddress = value)
  ProfileField.AGE -> copy(age = value)
  ProfileField.GENDER -> copy(gender = value)
  ProfileField.RELIGION -> copy(religion = value)
  ProfileField.ABOUT -> copy(extraInfo = value)
}

/**
 * The facts to list under "What the assistant remembers".
 *
 * [shared] already excludes the agents' private namespaces (`agent:{id}:`) — that is the repository's cut,
 * the same one the plain chat's prompt makes. Here the six rows of the profile itself are dropped too (they
 * are the editable fields above, not "facts"), and what is left is grouped by category.
 */
internal fun groupUserFacts(shared: List<Fact>): List<FactSection> {
  val facts = shared.filterNot { it.key in UserProfileKeys.ALL }
  return FactGroup.entries.mapNotNull { group ->
    val inGroup = facts.filter { groupOf(it.category) == group }
    if (inGroup.isEmpty()) null else FactSection(group, inGroup)
  }
}

private fun groupOf(category: String): FactGroup = when (category) {
  UserProfileKeys.CATEGORY -> FactGroup.ABOUT_YOU
  "preference" -> FactGroup.PREFERENCES
  else -> FactGroup.OTHER
}

/**
 * A fact's key as a caption under its value: the model writes keys like `user.favorite_food` or
 * `family.sister`, which read as code. The `user.` prefix says nothing here (every fact on the screen is about
 * the user), and dots/underscores become spaces.
 */
internal fun factLabel(key: String): String =
  key.removePrefix("user.").replace('.', ' ').replace('_', ' ').trim().ifEmpty { key }
