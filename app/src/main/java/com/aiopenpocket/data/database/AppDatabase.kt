package com.aiopenpocket.data.database

// @trace agent-memory-05e545

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.aiopenpocket.agent.core.agent.AgentRoomConverters
import com.aiopenpocket.agent.core.agent.UserAgentDao
import com.aiopenpocket.agent.core.agent.UserAgentEntity
import com.aiopenpocket.agent.core.hook.AgentHookDao
import com.aiopenpocket.agent.core.hook.AgentHookEntity
import com.aiopenpocket.agent.core.hook.AgentHookRunEntity
import com.aiopenpocket.agent.core.llm.usage.LlmUsageDao
import com.aiopenpocket.agent.core.llm.usage.LlmUsageEntity
import com.aiopenpocket.agent.core.skill.UserSkillDao
import com.aiopenpocket.agent.core.skill.UserSkillEntity
import com.aiopenpocket.agent.core.storage.AgentStorageDao
import com.aiopenpocket.agent.core.storage.AgentStorageEntity
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
import com.aiopenpocket.agent.core.plan.persistence.AgentPlanEntity
import com.aiopenpocket.agent.core.plan.persistence.PlanDao
import com.aiopenpocket.agent.core.plan.persistence.PlanEventEntity
import com.aiopenpocket.agent.core.plan.persistence.PlanEvidenceEntity
import com.aiopenpocket.agent.core.plan.persistence.PlanStepStateEntity
import com.aiopenpocket.agent.core.plan.persistence.PlanVersionEntity
import com.aiopenpocket.agent.core.srs.SrsWordDao
import com.aiopenpocket.agent.core.srs.SrsWordEntity

// @trace user-agent-builder-848153
// @trace structured-plan-engine-907066

// UserAgentEntity/userAgentDao() registered for Task 2 (user-agent-builder); version bumped 2 -> 3
// accordingly. UserSkillEntity/userSkillDao() registered for Task 4 (composite SkillRegistry);
// version bumped 3 -> 4. AgentHookEntity/AgentHookRunEntity/agentHookDao() registered for Task 8
// (declarative HookEngine); version bumped 4 -> 5. AgentPlanEntity/PlanVersionEntity/
// PlanStepStateEntity/PlanEvidenceEntity/PlanEventEntity/planDao() registered for Task 3 of the
// Structured Plan Engine (structured-plan-engine-907066); version bumped 5 -> 6. HeartbeatEntity
// gained its typed plan-target columns (targetType/planId/planVersion/planStepId) for Task 11's typed
// heartbeat scheduling — no new entity/table, just new nullable columns on an existing one; version
// bumped 6 -> 7. AgentStorageEntity/agentStorageDao() registered for Part B of the database_query
// security hardening (user-agent-builder-848153) — storage_tool's per-agent key-value store, the
// structured write path database_query's Part A SELECT-only restriction pushes agents toward instead
// of raw SQL; version bumped 7 -> 8. Message-pinning removed — MessageEntity lost its
// isPinned column; version bumped 8 -> 9. UserAgentEntity gained stateKeyPrefixes so a USER agent
// (in particular a clone of the "languages" preset) keeps its prompt state-injection prefixes instead
// of silently reporting an empty set; version bumped 9 -> 10. LlmUsageEntity/llmUsageDao()
// registered for token metering (streaming-llm-chat-6595a1) — one row per completed LLM call, the
// data the pricing/tariff work is sized from; version bumped 10 -> 11. No Migration is added any of
// these times — see the policy comment on DatabaseBindings.provideDatabase.
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
    AgentHookEntity::class,
    AgentHookRunEntity::class,
    AgentPlanEntity::class,
    PlanVersionEntity::class,
    PlanStepStateEntity::class,
    PlanEvidenceEntity::class,
    PlanEventEntity::class,
    AgentStorageEntity::class,
    LlmUsageEntity::class,
  ],
  version = 11,
  // exportSchema = true, хотя миграций пока нет и всё пересоздаётся разрушительно (см. политику на
  // DatabaseBindings.provideDatabase). Именно поэтому и включено: первую настоящую Migration придётся
  // писать ОТ схемы релизной версии, а взять её будет неоткуда, если снимки не начать копить заранее.
  // Снимки складываются в app/schemas (ksp room.schemaLocation) и коммитятся вместе с кодом.
  exportSchema = true,
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
  abstract fun agentHookDao(): AgentHookDao
  abstract fun planDao(): PlanDao
  abstract fun agentStorageDao(): AgentStorageDao
  abstract fun llmUsageDao(): LlmUsageDao
}
