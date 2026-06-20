package com.aiopenpocket.agent.core.memory.model

data class UserProfile(
  val name: String,
  val preferredAddress: String,
  val age: String = "",
  val gender: String = "",
  val religion: String = "",
  val extraInfo: String = "",
)
