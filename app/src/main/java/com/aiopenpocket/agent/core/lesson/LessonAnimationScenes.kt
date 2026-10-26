package com.aiopenpocket.agent.core.lesson

// @trace protsedurnye-animatsii-v-urokah-68b2bf

/**
 * The "animation" step scenes the app can draw. An animation is not a video file but Compose code: a few
 * kilobytes instead of megabytes in the APK, it works offline, adapts to the screen and can be
 * interactive (the temperature slider).
 *
 * One list for rendering and for checking lessons: a step with an unknown scene would show a placeholder
 * instead of the lesson, and LessonAssetsValidationTest catches that before the build.
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
