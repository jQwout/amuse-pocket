package com.aiopenpocket.di

import android.app.Application
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aiopenpocket.data.database.AppDatabase
import com.aiopenpocket.agent.core.memory.dao.ConversationDao
import com.aiopenpocket.agent.core.memory.dao.ConversationTagDao
import com.aiopenpocket.agent.core.memory.dao.EpisodeDao
import com.aiopenpocket.agent.core.memory.dao.FactDao
import com.aiopenpocket.agent.core.memory.dao.MessageDao
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

@ContributesTo(AppScope::class)
@BindingContainer
object DatabaseBindings {

    @Provides
    @SingleIn(AppScope::class)
    fun provideDatabase(application: Application): AppDatabase =
        Room.databaseBuilder(
            application,
            AppDatabase::class.java,
            "queen_chat_db",
        )
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideConversationDao(database: AppDatabase): ConversationDao = database.conversationDao()

    @Provides
    fun provideConversationTagDao(database: AppDatabase): ConversationTagDao =
        database.conversationTagDao()

    @Provides
    fun provideMessageDao(database: AppDatabase): MessageDao = database.messageDao()

    @Provides
    fun provideFactDao(database: AppDatabase): FactDao = database.factDao()

    @Provides
    fun provideEpisodeDao(database: AppDatabase): EpisodeDao = database.episodeDao()

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE conversations ADD COLUMN isTutorial INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE messages ADD COLUMN cardSpec TEXT")
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE messages ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
        }
    }

    // Wiki-style memory layer: pages + tags + directed links. DDL must match Room's generated
    // schema for these entities exactly, or Room's runtime schema validation will throw.
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `wiki_pages` (" +
                    "`id` TEXT NOT NULL, `slug` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                    "`content` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            database.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_wiki_pages_slug` ON `wiki_pages` (`slug`)",
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_wiki_pages_updatedAt` ON `wiki_pages` (`updatedAt`)",
            )

            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `wiki_page_tags` (" +
                    "`pageId` TEXT NOT NULL, `tag` TEXT NOT NULL, PRIMARY KEY(`pageId`, `tag`), " +
                    "FOREIGN KEY(`pageId`) REFERENCES `wiki_pages`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_wiki_page_tags_tag` ON `wiki_page_tags` (`tag`)",
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_wiki_page_tags_pageId` ON `wiki_page_tags` (`pageId`)",
            )

            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `wiki_links` (" +
                    "`fromPageId` TEXT NOT NULL, `toPageId` TEXT NOT NULL, `relation` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`fromPageId`, `toPageId`, `relation`), " +
                    "FOREIGN KEY(`fromPageId`) REFERENCES `wiki_pages`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                    "FOREIGN KEY(`toPageId`) REFERENCES `wiki_pages`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_wiki_links_fromPageId` ON `wiki_links` (`fromPageId`)",
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_wiki_links_toPageId` ON `wiki_links` (`toPageId`)",
            )
        }
    }

    // Bind a conversation to a mini-agent: scopes its tools + role. Nullable, default null.
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE conversations ADD COLUMN agentId TEXT")
        }
    }

    // Knowledge base reframed onto conversations: drop the separate wiki store, tag conversations
    // directly. Relatedness is tag overlap, so there is no link table. DDL must match Room's
    // generated schema for ConversationTagEntity exactly, or runtime validation will throw.
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("DROP TABLE IF EXISTS `wiki_links`")
            database.execSQL("DROP TABLE IF EXISTS `wiki_page_tags`")
            database.execSQL("DROP TABLE IF EXISTS `wiki_pages`")

            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `conversation_tags` (" +
                    "`conversationId` TEXT NOT NULL, `tag` TEXT NOT NULL, " +
                    "PRIMARY KEY(`conversationId`, `tag`), " +
                    "FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_conversation_tags_tag` " +
                    "ON `conversation_tags` (`tag`)",
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_conversation_tags_conversationId` " +
                    "ON `conversation_tags` (`conversationId`)",
            )
        }
    }
}
