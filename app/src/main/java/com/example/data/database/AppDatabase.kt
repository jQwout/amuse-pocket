package com.aiopenpocket.data.database

// @trace agent-memory-05e545

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.aiopenpocket.agent.core.agent.AgentRoomConverters
import com.aiopenpocket.agent.core.agent.UserAgentDao
import com.aiopenpocket.agent.core.agent.UserAgentEntity
import com.aiopenpocket.agent.core.skill.UserSkillDao
import com.aiopenpocket.agent.core.skill.UserSkillEntity
import com.aiopenpocket.agent.core.memory.dao.ConversationDao
import com.aiopenpocket.agent.core.memory.dao.ConversationTagDao
import com.aiopenpocket.agent.core.memory.dao.EpisodeDao
import com.aiopenpocket.agent.core.memory.dao.FactDao
import com.aiopenpocket.agent.core.memory.dao.HeartbeatDao
import com.aiopenpocket.agent.core.memory.dao.MessageDao
import com.aiopenpocket.agent.core.memory.entity.ConversationEntity
import com.aiopenpocket.agent.core.memory.entity.ConversationTagEntity
import com.aiopenpocket.agent.core.memory.entity.EpisodeEntity
import com.aiopenpocket.agent.core.memory.entity.FactEntity
import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import com.aiopenpocket.agent.core.memory.entity.MessageEntity
import com.aiopenpocket.agent.core.srs.SrsWordDao
import com.aiopenpocket.agent.core.srs.SrsWordEntity

// @trace user-agent-builder-848153

// UserAgentEntity/userAgentDao() registered for Task 2 (user-agent-builder); version bumped 2 -> 3
// accordingly. UserSkillEntity/userSkillDao() registered for Task 4 (composite SkillRegistry);
// version bumped 3 -> 4. No Migration is added either time — see the policy comment on
// DatabaseBindings.provideDatabase.
@Database(
  entities = [
    ConversationEntity::class,
    ConversationTagEntity::class,
    MessageEntity::class,
    FactEntity::class,
    EpisodeEntity::class,
    SrsWordEntity::class,
    HeartbeatEntity::class,
    UserAgentEntity::class,
    UserSkillEntity::class,
  ],
  version = 4,
  exportSchema = false,
)
@TypeConverters(AgentRoomConverters::class)
abstract class AppDatabase : RoomDatabase() {
  abstract fun conversationDao(): ConversationDao
  abstract fun conversationTagDao(): ConversationTagDao
  abstract fun messageDao(): MessageDao
  abstract fun factDao(): FactDao
  abstract fun episodeDao(): EpisodeDao
  abstract fun srsWordDao(): SrsWordDao
  abstract fun heartbeatDao(): HeartbeatDao
  abstract fun userAgentDao(): UserAgentDao
  abstract fun userSkillDao(): UserSkillDao
}
