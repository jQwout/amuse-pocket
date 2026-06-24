---
name: trace
description: Keep traceability.yaml and @trace markers in sync with code. Use when adding a feature, finishing a change, or when `traxer check` reports drift in a repo that has a traceability.yaml.
---

# trace — feature ↔ code ↔ reason

This repo uses **traxer** to link features to code and to the reason they changed.
The source of truth is `traceability.yaml` (edited by hand); the code map is derived
by scanning `@trace` markers. You are the semantic linker — the hook is only a backup.

Run `traxer --help` for the full command list, and `traxer doctor` to verify the setup.

## When you finish a change

1. Run `traxer check`. Resolve what it reports:
   - **broken link** — an `@trace <id>` points at an id absent from `traceability.yaml`.
   - **feature has no code** — a registered feature with no `@trace` marker.
   - **code without explanation** — a feature with markers but an empty `rationale`.
2. If the change introduces a **new feature**:
   - mint an id: `traxer id "<short name>"`
   - add it to `traceability.yaml` by hand: `id`, `title`, and `rationale` (WHY it exists).
   - tag the implementing code with a comment marker `@trace <id>` (optional `flow:<flow>`).
3. Record the reason in the commit with a trailer line: `Trace: <id>`.
4. Re-run `traxer check` until it is clean.

## Onboarding an existing project (no markers yet)

When a repo has no traceability, bootstrap it. **You do the understanding; traxer just
records it** (it has no LLM). Work incrementally — a handful of real features beats
tagging everything.

1. `traxer survey` — the cheap lay of the land: areas (top-level dirs), languages, and
   how many files are already traced. Start with the largest areas.
2. Read the notable code there and identify **real features** — a user-facing capability
   or a cohesive module — not one-per-file.
3. For each feature, hand it off to traxer:
   - `traxer id "<name>"` → a frozen id,
   - add it to `traceability.yaml` by hand (`id`, `title`, `rationale` = WHY),
   - tag the implementing code with `@trace <id>`.
4. `traxer check` until clean, then commit with `Trace: <id>` trailers. Repeat per area.

## Drafting a spec (ТЗ) from code

If a feature has no spec yet, scaffold one: `traxer spec "<feature>" --write` creates
`specs/<id>.draft.md` from the code the feature is tagged in. Then read the code at the
listed locations and fill the TODO sections — judging **intent, not current behavior**
(the code may have bugs). Open a PR and link the result via `spec:` in `traceability.yaml`.

## Rules
- Never invent or edit a frozen id — ids are stable for life.
- A marker must be the first token of a comment (`// @trace <id>`), one per line.
- traxer never writes features into the registry; only a human/agent does, deliberately.
