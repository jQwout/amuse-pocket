# Agent builder as a chat

Owner's decision, 31 Oct 2026: building an agent must not start with a wall of form fields. It is a
conversation. Muse Spark asks a few questions at a time, each with 3–4 options to tick, then the
next ones. At the end a review screen shows everything that was set, with every box visible and
editable, and the person publishes from there.

## Why

- The six-step form (identity, instructions, tools, modes and model, rules, test) put every field
  in front of the person at once. The owner's words: it scares people off.
- A model that asks the right next question is itself the demo of what the platform teaches. It
  is also Meta tooling doing real work in the product, not a decoration.
- Publishing stays a human act on a screen, not something the model does. The model can only
  write a draft.

## Flow

1. "Create agent" (the FAB on Agents, and the capstone lesson's "Open the builder") opens a new
   chat bound to a hidden built-in agent, **Builder**. A draft agent is created for this chat.
2. Builder greets in one line and asks what the agent should do (free text).
3. From the answer, Builder fills the draft (name, description, instructions) and asks the next
   question with a **multi-choice card**: 3–4 options, ticks, a "Next" button. Typical questions,
   in this order, skipping what is already clear:
   - what the agent may use (3–4 fitting tools from the allowed list, the fitting ones pre-ticked);
   - how it should answer (length, format, tone);
   - when it should act by itself (reminders or background checks, or never);
   - which Muse model (Wonder for long multi-step work, Spark for faster and cheaper, app default).
   Never more than 4 options and never more than one question per card.
4. When the draft passes `AgentValidator`, Builder says so and shows a **"Review your agent"**
   card. Tapping it opens the review screen.
5. The **review screen** shows every part of the draft at once: name, description, instructions
   with the prompting checklist, tools as ticked boxes, modes, model, rules. Everything is editable
   in place. It also has the dry run and **Publish**. "Back to chat" returns to the conversation.

## Pieces

| Piece | Where | Notes |
|---|---|---|
| Multi-choice card | `render_card` template `multi_choice` (DrivenUiEngine, WidgetCardViews) | `question`, `options` (2–4), `preselected` indices, `min`/`max`. The submitted answer goes back as the user's message ("Memory, Reminders"), like the single `choice` card does today. Locked after submit. |
| Builder agent | hidden preset, not in the roster | System prompt in English and Russian; tools: `agent_draft`, `render_card`. Model: the app's model. Lists the allowed tool names and the 14 tools' one-line purposes in its prompt. |
| `agent_draft` tool | `agent/tools/AgentDraftTool.kt` | Actions: `get`, `update` (name, description, instructions, tools, modes, model, includeSharedProfile), `validate`, `finish`. Risk class WRITE (local draft only). It can never publish. Unknown tool names or modes are rejected with an actionable error listing the allowed values. The draft is bound to the chat: one draft per builder conversation. |
| Review screen | builder route `agent_builder/{agentId}?review=true` | New `BuilderStep.REVIEW` or a dedicated screen, reusing the existing step components as sections. Publish and the dry run live here. |
| Entry | `MainActivity` routes | "Create agent" opens the builder chat. The old step-by-step form stays reachable as "Edit" from the review screen and from an existing agent. |

## Limits

- Builder may suggest only tools and modes that `AgentValidator` accepts. The tool rejects
  anything else.
- No key means the usual "Add your Meta API key" path. The form route still works without a key.
- All user-visible text goes through `tr()` (English and Russian).

## Not in scope

Hooks (rules) authoring by chat. The review screen shows them and links to the existing editor.
Custom skills by chat.
