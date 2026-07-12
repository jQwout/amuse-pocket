package com.aiopenpocket.agent.core.agent

// @trace user-agent-builder-848153

import androidx.room.TypeConverter
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Single Room [TypeConverter] pair — `List<String>` <-> JSON array text — reused for every
 * collection column on [UserAgentEntity] (toolNames/skillIds/allowedModes/allowedWidgetTemplates)
 * instead of one bespoke converter per field. All four columns are declared as `List<String>` on
 * the entity itself precisely so they can share this one conversion rule: enum-valued collections
 * (allowedModes) already travel as their [Enum.name] strings by the time they reach this converter
 * — see [UserAgentEntity.fromDefinition]/[UserAgentEntity.toDefinition] for that enum<->name edge.
 */
class AgentRoomConverters {
  @TypeConverter
  fun fromStringList(value: List<String>): String = Json.encodeToString(value)

  @TypeConverter
  fun toStringList(raw: String): List<String> = Json.decodeFromString(raw)
}
