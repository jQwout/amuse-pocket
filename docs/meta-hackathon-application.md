# Заявка на Meta Global AI Developer Hackathon

Текст для формы Apply на https://dev.meta.ai/events/global-hackathon (форму держит Cerebral Valley). Полей формы заранее не видно, поэтому ниже готовые блоки: вставляй каждый в подходящее поле. Сама форма англоязычная, текст тоже.

## Перед отправкой

- [ ] Отправить до **26 окт, 04:59 по Белграду** (25 окт, 23:59 ET). Лучше раньше: мест 20 000, приём могут закрыть досрочно.
- [ ] Трек: **Indie developer**. Тема: **Build for Your World**.
- [ ] Репозиторий сейчас **приватный**. Либо открыть его перед отправкой, либо не давать ссылку (поле опциональное): закрытая ссылка ревьюеру ничего не покажет.
- [ ] LinkedIn — по желанию.
- [ ] Цифры в тексте сняты 23 окт 2026: 416 коммитов с 31 мая, ~40k строк Kotlin, ~950 юнит-тестов, 15 тулов, 26 уроков.
- [ ] Ответ о допуске придёт до **26 окт, 14:00 по Белграду**.

Оценивают две равные половины: ясность и выполнимость идеи + попадание в тему, и техническая зрелость по описанному AI-проекту.

---

## Project name

AI Open Pocket

## One-liner

A phone AI agent that teaches people how AI agents work, inside a real agent and on their own photos, videos and data. Rebuilt on Muse.

## Track / theme

Indie developer · Build for Your World

## Project idea

Millions of people talk to AI assistants every day, yet almost nobody knows what a prompt, a context window, a tool call or a hallucination actually is. So they either trust every answer or give up. Courses teach this with slides. AI Open Pocket teaches it inside a working agent on your phone.

You learn in the same chat where the agent works. 26 short interactive lessons (quizzes, fill-in-the-blank, sorting, scenarios) explain one concept at a time and then let you try it on the live agent: switch its persona, watch it call a tool, see what lands in its context. At the end you assemble your own mini-agent, with its tools, skill, memory and plan mode, and run it on your phone.

For the hackathon I'm rebuilding it on Muse:

- **Muse Spark 1.3 as the brain** of the agent's tool loop: tool calling, 1M context, reasoning.
- **Lessons that draw.** Muse Image illustrates every lesson, and a "draw it for me" button redraws the picture around the learner's own life and language. Bundled images keep lessons working offline.
- **Learn from your own world.** Film 5–10 seconds of your shelf, your fridge or a product label, and Muse Spark's video understanding turns it into lesson material. SAM 3.1 can outline the objects it talks about.
- **Honest cost.** Every model call, text or image, is metered on the device and shown to the user as a weekly spend card.

Why "Build for Your World": this is my domain from the inside. I've spent five months building agent infrastructure on Android, and the lessons explain exactly the machinery the app runs on. The learner's memory and profile stay on the phone; only the chosen model provider sees a request.

## Feasibility and plan

The agent, the lessons and the mini-agents already run on Android. The hackathon work is the Muse layer:

- **Oct 26–27:** Muse Spark in the agent loop, model catalog, cost tracking.
- **Oct 28–29:** Muse Image lessons: a pre-generated set plus personalized "draw it for me".
- **Oct 30 – Nov 1:** the video-input lesson.
- **Nov 2–3:** demo video and submission. Nov 4 is the buffer.

## Technical background (the AI project)

AI Open Pocket is a native Android agent app I've built solo since May 2026 (not released yet).

- **Stack:** Kotlin, Jetpack Compose, Metro DI, Ktor with SSE streaming, Room, WorkManager.
- **Agent loop:** ReAct over OpenAI-compatible function calling, provider-agnostic, with model fallback inside the chosen provider and a live model catalog.
- **15 on-device tools:** alarms, calendar, notifications, email, files, database queries, memory, background work, step counter, spaced-repetition flashcards, and generative UI cards: the model renders todo lists, tables, charts and forms right in the chat.
- **Control:** a structured plan engine (plan → user approval → execution with verification), goal mode and reflection mode; tool approval, risk classes and per-run tool budgets.
- **Multi-agent:** a roster of scoped specialists (budget, a language tutor with spaced repetition, diary, fitness, nutrition, tasks, reminders) and an agent builder where users assemble their own.
- **Also:** layered memory in Room (profile, facts, episodes), 7 swappable personas, voice input, agent turns that survive leaving the screen, proactive heartbeats.
- **Engineering:** ~40k lines of Kotlin, ~950 unit tests, 416 commits; a traceability check links every feature to its rationale and its code.

## Short version (if a field is limited to ~500 characters)

AI Open Pocket is an Android AI agent that teaches people how AI agents work, inside the agent itself: 26 interactive lessons, then you build your own mini-agent. For the hackathon I'm rebuilding it on Muse: Muse Spark 1.3 runs the tool loop, Muse Image draws each lesson around the learner's life, and Muse Spark's video understanding turns 10 seconds filmed on your phone into lesson material. Built solo: Kotlin/Compose, ReAct tool loop, plan engine, multi-agent roster, ~950 tests.

## Links

- GitHub: https://github.com/jQwout/ai-open-pocket-2 *(сейчас приватный — открыть или не давать)*
- LinkedIn: *(твоя ссылка)*
