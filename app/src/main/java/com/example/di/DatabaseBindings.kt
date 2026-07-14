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

    @Provides
    @SingleIn(AppScope::class)
    fun provideDatabase(application: Application): AppDatabase =
        // Pre-release policy (unchanged by Task 2/4/8/plan-Task 3, just corrected to describe it
        // honestly): schema is not versioned toward a stable baseline yet — AppDatabase.version is
        // currently 6 (bumped from 2 -> 3 by the user-agent-builder feature's user_agents table, 3 -> 4
        // by its user_skills table, 4 -> 5 by its agent_hooks/agent_hook_runs tables, 5 -> 6 by the
        // Structured Plan Engine's agent_plans/plan_versions/plan_step_states/plan_evidence/plan_events
        // tables). Every schema change just bumps this number; Room recreates the DB from scratch
        // (fallbackToDestructiveMigration) instead of migrating, so any local dev data — including
        // custom agent drafts, their custom skills/hooks, and any in-progress plan — can be lost on
        // upgrade. Acceptable pre-release (no production users yet, confirmed 2026-07-12). Real
        // Migration + addMigrations + schema export start from the first release's baseline, not before.
        Room.databaseBuilder(application, AppDatabase::class.java, "ai_open_pocket_db")
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
}
