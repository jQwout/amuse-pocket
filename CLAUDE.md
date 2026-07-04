# CLAUDE.md

## Traceability (traxer)

Репо использует traxer: `traceability.yaml` + маркеры `@trace <id>` в коде (см. скилл `trace`).

Перед коммитом — даже если работаешь по процессу superpowers (verification-before-completion, finishing-a-development-branch):

1. Прогони `traxer check` и разгреби, что он покажет (broken link / фича без кода / stale).
2. Новая фича → `traxer id`, запись в `traceability.yaml` (id, title, rationale = ПОЧЕМУ), маркер `@trace <id>` в коде.
3. В коммит — трейлер `Trace: <id>`.
4. Stale-warnings blessить (`traxer bless <id>`) только после подтверждения, что rationale ещё верен.
