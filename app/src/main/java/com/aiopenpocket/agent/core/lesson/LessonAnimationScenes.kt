package com.aiopenpocket.agent.core.lesson

// @trace protsedurnye-animatsii-v-urokah-68b2bf

/**
 * Сцены шага "animation", которые приложение умеет рисовать. Анимация не видеофайл, а код на
 * Compose: несколько килобайт вместо мегабайт в APK, работает без сети, подстраивается под экран и
 * может быть интерактивной (ползунок температуры).
 *
 * Один список на рендер и на проверку уроков: шаг с неизвестной сценой показал бы заглушку
 * вместо урока, и LessonAssetsValidationTest ловит это до сборки.
 */
val LESSON_ANIMATION_SCENES: Set<String> =
  setOf(
    "tokens", "temperature", "video_frames", "react_loop", "context_window", "rag",
    "persona_switch", "chat_vs_agent", "prompt_layers", "prompt_sharpen", "confident_wrong",
    "tool_call", "skill_levels", "data_flow", "risk_gate", "hook_timeline",
    "workflow_vs_agent", "chain_router", "context_budget", "plan_gate", "goal_loop",
    "draft_critique", "step_evidence", "day_clock", "arena_swarm", "agent_assembler",
    "blueprint", "meta_lineup", "open_weights", "thinking_modes", "image_reasoning",
  )
