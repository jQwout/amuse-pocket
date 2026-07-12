package com.aiopenpocket.data.database

// @trace agent-memory-05e545

import androidx.room.Database
import androidx.room.RoomDatabase
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

@Database(
  entities = [
    ConversationEntity::class,
    ConversationTagEntity::class,
    MessageEntity::class,
    FactEntity::class,
    EpisodeEntity::class,
    SrsWordEntity::class,
    HeartbeatEntity::class,
  ],
  version = 2,
  exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
  abstract fun conversationDao(): ConversationDao
  abstract fun conversationTagDao(): ConversationTagDao
  abstract fun messageDao(): MessageDao
  abstract fun factDao(): FactDao
  abstract fun episodeDao(): EpisodeDao
  abstract fun srsWordDao(): SrsWordDao
  abstract fun heartbeatDao(): HeartbeatDao
}
