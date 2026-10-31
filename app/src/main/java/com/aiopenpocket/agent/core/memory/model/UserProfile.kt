package com.aiopenpocket.agent.core.memory.model

// @trace agent-memory-05e545

data class UserProfile(
  val name: String,
  val preferredAddress: String,
  val age: String = "",
  val gender: String = "",
  val religion: String = "",
  val extraInfo: String = "",
)

/**
 * The category and keys [com.aiopenpocket.agent.core.memory.repository.MemoryRepository.saveUserProfile]
 * stores the profile under — one place for the writer and its readers (onboarding, the Profile screen),
 * so a renamed key cannot make the screen read back a different row than the one onboarding wrote.
 */
object UserProfileKeys {
  const val CATEGORY = "profile"
  const val NAME = "user.name"
  const val ADDRESS = "user.preferred_address"
  const val AGE = "user.age"
  const val GENDER = "user.gender"
  const val RELIGION = "user.religion"
  const val EXTRA_INFO = "user.extra_info"

  /** The six rows of the profile — the Profile screen shows them as fields, not as "remembered facts". */
  val ALL = setOf(NAME, ADDRESS, AGE, GENDER, RELIGION, EXTRA_INFO)
}
