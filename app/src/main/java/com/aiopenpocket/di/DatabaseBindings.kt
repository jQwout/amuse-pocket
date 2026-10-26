package com.aiopenpocket.di

// @trace agent-memory-05e545
// @trace user-agent-builder-848153
// @trace structured-plan-engine-907066

import android.app.Application
import androidx.room.Room
import com.aiopenpocket.agent.core.agent.UserAgentDao
import com.aiopenpocket.agent.core.hook.AgentHookDao
import com.aiopenpocket.agent.core.plan.persistence.PlanDao
import com.aiopenpocket.agent.core.skill.UserSkillDao
import com.aiopenpocket.agent.core.llm.LlmUsageRecorder
import com.aiopenpocket.agent.core.llm.usage.LlmUsageDao
import com.aiopenpocket.agent.core.llm.usage.RoomLlmUsageRecorder
import com.aiopenpocket.agent.core.storage.AgentStorageDao
import com.aiopenpocket.agent.core.memory.dao.ConversationDao
import com.aiopenpocket.agent.core.memory.dao.ConversationTagDao
import com.aiopenpocket.agent.core.memory.dao.EpisodeDao
import com.aiopenpocket.agent.core.memory.dao.FactDao
import com.aiopenpocket.agent.core.memory.dao.HeartbeatDao
import com.aiopenpocket.agent.core.memory.dao.MessageDao
import com.aiopenpocket.agent.core.srs.SrsWordDao
import com.aiopenpocket.data.database.AppDatabase
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

@ContributesTo(AppScope::class)
@BindingContainer
object DatabaseBindings {

    /**
     * The DB file name. A constant rather than a literal in one place, because the same name is
     * repeated verbatim in `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml` — the
     * database is excluded from cloud backup (all of the agent's conversations and memory are in it as
     * plain text). XML cannot reference a Kotlin constant, so `BackupRulesTest` catches a mismatch:
     * renaming the DB without updating the rules would silently put it back into the backup.
     */
    const val DATABASE_NAME = "ai_open_pocket_db"

    @Provides
    @SingleIn(AppScope::class)
    fun provideDatabase(application: Application): AppDatabase =
        // Pre-release policy (unchanged by Task 2/4/8/plan-Task 3/plan-Task 11/the database_query
        // hardening's Part B, just corrected to describe it honestly): schema is not versioned toward a
        // stable baseline yet — AppDatabase.version is currently 9 (bumped from 2 -> 3 by the
        // user-agent-builder feature's user_agents table, 3 -> 4 by its user_skills table, 4 -> 5 by its
        // agent_hooks/agent_hook_runs tables, 5 -> 6 by the Structured Plan Engine's agent_plans/
        // plan_versions/plan_step_states/plan_evidence/plan_events tables, 6 -> 7 by that same engine's
        // typed heartbeat columns on the existing heartbeats table — targetType/planId/planVersion/
        // planStepId, 7 -> 8 by storage_tool's agent_storage table, 8 -> 9 by dropping the isPinned
        // column when message pinning was removed). Every schema change just bumps this
        // number; Room recreates the DB from scratch (fallbackToDestructiveMigration) instead of
        // migrating, so any local dev data — including custom agent drafts, their custom skills/hooks,
        // any in-progress plan, and any agent's stored KV state — can be lost on upgrade. Acceptable
        // pre-release (no production users yet, confirmed 2026-07-12). Real Migration + addMigrations +
        // schema export start from the first release's baseline, not before.
        Room.databaseBuilder(application, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
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

    @Provides
    fun provideSrsWordDao(database: AppDatabase): SrsWordDao = database.srsWordDao()

    @Provides
    fun provideHeartbeatDao(database: AppDatabase): HeartbeatDao = database.heartbeatDao()

    @Provides
    fun provideUserAgentDao(database: AppDatabase): UserAgentDao = database.userAgentDao()

    @Provides
    fun provideUserSkillDao(database: AppDatabase): UserSkillDao = database.userSkillDao()

    @Provides
    fun provideAgentHookDao(database: AppDatabase): AgentHookDao = database.agentHookDao()

    @Provides
    fun providePlanDao(database: AppDatabase): PlanDao = database.planDao()

    @Provides
    fun provideAgentStorageDao(database: AppDatabase): AgentStorageDao = database.agentStorageDao()

    @Provides
    fun provideLlmUsageDao(database: AppDatabase): LlmUsageDao = database.llmUsageDao()

    /**
     * Spend accounting — the Room implementation. There is deliberately no separate stub in
     * production: the binding is required here, and if it is removed the graph build fails instead of
     * silently not counting money.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideLlmUsageRecorder(recorder: RoomLlmUsageRecorder): LlmUsageRecorder = recorder
}
