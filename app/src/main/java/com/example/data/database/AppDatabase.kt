package com.aiopenpocket.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aiopenpocket.agent.core.memory.dao.ConversationDao
import com.aiopenpocket.agent.core.memory.dao.EpisodeDao
import com.aiopenpocket.agent.core.memory.dao.FactDao
import com.aiopenpocket.agent.core.memory.dao.MessageDao
import com.aiopenpocket.agent.core.memory.dao.WikiLinkDao
import com.aiopenpocket.agent.core.memory.dao.WikiPageDao
import com.aiopenpocket.agent.core.memory.dao.WikiTagDao
import com.aiopenpocket.agent.core.memory.entity.ConversationEntity
import com.aiopenpocket.agent.core.memory.entity.EpisodeEntity
import com.aiopenpocket.agent.core.memory.entity.FactEntity
import com.aiopenpocket.agent.core.memory.entity.MessageEntity
import com.aiopenpocket.agent.core.memory.entity.WikiLinkEntity
import com.aiopenpocket.agent.core.memory.entity.WikiPageEntity
import com.aiopenpocket.agent.core.memory.entity.WikiPageTagEntity

@Database(
  entities = [
    ConversationEntity::class,
    MessageEntity::class,
    FactEntity::class,
    EpisodeEntity::class,
    WikiPageEntity::class,
    WikiPageTagEntity::class,
    WikiLinkEntity::class,
  ],
  version = 6,
  exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
  abstract fun conversationDao(): ConversationDao
  abstract fun messageDao(): MessageDao
  abstract fun factDao(): FactDao
  abstract fun episodeDao(): EpisodeDao
  abstract fun wikiPageDao(): WikiPageDao
  abstract fun wikiTagDao(): WikiTagDao
  abstract fun wikiLinkDao(): WikiLinkDao
}
