# Сабмишн на Meta Global AI Developer Hackathon

Сценарий демо-видео и текст сабмишна. Сдать до **4 ноя, 21:00 ET** (5 ноя, 03:00 по Белграду); план — 3 ноября, 4-е в запасе. Цифры сняты 24 окт 2026.

Как судят проекты (из правил события): польза 30%, использование инструментов Meta 25% (вышли ли за базовую интеграцию, показали ли неожиданные возможности), техническая глубина 20%, демо 25% (рабочее, впечатляющее, держится вживую; качество ролика).

## 0. До записи — обязательно

- [ ] **Перечитать Official Rules (выйдут 26 окт):** длина и формат ролика, поля формы, требования к репозиторию и лицензии.
- [ ] **Сроки работы.** Вся Muse-часть сделана **24–25 октября, до старта 26-го**: Muse по умолчанию, картинки Muse Image, «Нарисуй под меня», видео-урок, интерактивные сцены во всех 31 уроке, 4 урока про модели Meta. Если правила требуют, чтобы проект делался в окне хакатона, — в описании честно разделить «было до 26 окт» и «сделано на хакатоне», и сделать заметную часть в окне (кандидаты — раздел 1). Выдавать сделанное 24-го за сделанное на хакатоне нельзя: даты видны в git.
- [x] **Видео-урок проверен вживую** 24 окт на эмуляторе: Muse Spark через OpenRouter принял base64-ролик. Свой ролик (комната) и ролик-пример (стол) — ответ по-русски за ~11 с: предметы, одно сомнение, объяснение про кадры.
- [ ] **Язык.** Интерфейс и уроки на русском, судьи, скорее всего, англоязычные: озвучка и субтитры ролика — на английском. Локализация уроков — кандидат на работу в окне.
- [ ] Ключ OpenRouter в `.env`, провайдер — OpenRouter; в профиле «о себе» — что-то, что красиво ляжет в картинку (пример ниже); карточка расхода за неделю не пустая.
- [ ] Ключ, засветившийся в переписке, после хакатона заменить.

## 1. Что сделать в окне хакатона (26 окт – 3 ноя)

По влиянию на оценку, сверху вниз:

1. **Уроки на английском через Muse Spark.** 31 JSON-урок перевести моделью по их же контракту, в выборе языка «English» уже есть (сейчас «скоро»). Польза — курс для всех, а не только русскоязычных; демо — судьи видят интерфейс на своём языке.
2. **Генеративные сцены.** «Объясни RAG анимацией» → Muse Spark пишет раскадровку на маленьком языке примитивов (блоки, стрелки, фишки, подписи) → приложение проигрывает. Прямой ответ на «вышли ли за базовую интеграцию».
3. **Прямой Meta Model API** на $150 кредитов допущенного участника и **SAM 3.1** поверх кадров видео-урока: модель обводит то, о чём говорит. Неожиданная возможность — и вторая модель Meta в видео-уроке.
4. Запись ролика и сабмишн — 2–3 ноября.

## 2. Сценарий демо-видео (3:00)

Камера для демо не нужна: видео-шаг идёт через «Показать на примере» (ролик в сборке). Записывать можно с эмулятора или телефона — scrcpy с записью или встроенная запись экрана, 1080p. Озвучка — на английском, субтитры английские, интерфейс русский. Пример «о себе» для профиля: «Учитель биологии, бегаю по утрам, у меня кот Барсик» — картинка «под меня» получится узнаваемой.

| Время | Что на экране | Закадровый текст (EN) |
| --- | --- | --- |
| 0:00–0:15 | Главный экран, быстрые нарезки сцен уроков | Millions of people talk to AI every day, yet almost nobody knows what a prompt, a context window or a tool call actually is. AI Open Pocket teaches it inside a working AI agent on your phone — built on Meta Muse. |
| 0:15–0:35 | Онбординг: одно свободное «о себе» → профиль заполнен | Muse Spark is the agent's brain. During onboarding it reads one free-form answer and fills in my profile — no forms. |
| 0:35–1:05 | Урок с иллюстрацией → «Нарисуй под меня» → новая картинка под шагом | Every lesson is illustrated by Muse Image — 26 pictures generated ahead of time and bundled, so lessons work offline. One tap redraws the scene around my own life. Only my "about me" and my language leave the phone — never my name, age or gender. |
| 1:05–1:45 | «Почему ответы разные»: ползунок температуры, «Вытянуть слово». Затем «Память и контекст»: имя уезжает за окно, переключатель долгой памяти | Lessons you can touch. Drag the temperature and watch the next-word distribution sharpen or spread out. Scroll a chat and see my name fall out of the context window — the model forgets it. Switch on long-term memory, and the answer changes. |
| 1:45–2:00 | «Как модель видит»: шкала кадров, 1 → 4 кадра в секунду | Video becomes frames, and frames become tokens. At one frame per second the cup lift slips between frames; at four, the model sees it — and pays four times more. |
| 2:00–2:20 | Шаг с видео: «Показать на примере» → «Смотрю видео…» → ответ Muse Spark в чате (ноутбук, три книги, рыжий кот; сомнение про крошки) | One tap sends a six-second clip to Muse Spark. It lists what it sees — a laptop, three books, a sleeping ginger cat — and names the one detail it isn't sure about. Exactly the lesson's point. Learners can film their own, too. |
| 2:20–2:45 | Обычный чат: задача с инструментом (напоминание) → трасса вызовов; затем Настройки → карточка расхода за неделю | And it's a real agent: a ReAct loop, fifteen on-device tools, plans I approve before anything runs. Every model call — text, image or video — is metered, so I see exactly what learning cost me this week. |
| 2:45–3:00 | Логотип, ссылка на репозиторий | AI Open Pocket — understand AI by using it. Built solo on Muse Spark and Muse Image. |

Запасной вариант, если OpenRouter в день записи не ответит: 2:00–2:20 заменить на сцену «Цикл ReAct» (ведём агента по кругу кнопкой).

## 3. Текст сабмишна (EN)

**Project name:** AI Open Pocket

**Tagline:** Learn how AI agents work inside a real agent on your phone — lessons you can touch, built on Meta Muse.

**Track / theme:** Indie developer · Build for Your World

### Short description

AI Open Pocket is an Android AI agent that teaches people how AI agents work — inside the agent itself. 31 short lessons, each with an interactive scene you can touch, four of them about Meta's own models, illustrations by Muse Image that redraw around your life, a lesson where Muse Spark watches a video in one tap, and an agent builder at the end. Muse Spark runs the agent; every call is metered and private by design.

### What it does

People use AI assistants every day without knowing what a prompt, a context window, a tool call or a hallucination is — so they either trust every answer or give up. Courses explain this with slides. AI Open Pocket explains it inside a working agent on your phone: you learn in the same chat where the agent works, try each idea on the live agent, and finish by assembling your own mini-agent.

- **31 lessons** from "what is an AI" to building your own agent, each a strict step contract (text, quizzes, fill-in, sorting, scenarios) driven by code, not by the model.
- **An interactive scene in every lesson** — 31, drawn in code, not video files. Each one turns the lesson's mechanism into something you do: drag the temperature and watch the next-word distribution reshape, scroll a chat until your name falls out of the context window, step an agent through its ReAct loop, toggle the hidden layers the app adds to your prompt, approve or strike out plan steps before anything runs, run a day of cron jobs and heartbeat wake-ups, assemble an agent part by part.
- **Four lessons about Meta's models:** who is who in the lineup (Muse Spark, Muse Image, SAM 3.1, Voice Transcribe, Llama), open weights vs API and the «contributor» versions, Muse Spark's Instant / Thinking / Contemplating modes, and how Muse Image plans, draws and checks itself.
- **A real agent:** ReAct loop over function calling, 15 on-device tools (alarms, calendar, notifications, files, memory, spaced-repetition cards, generative UI cards and more), a structured plan engine with approval, specialist mini-agents, layered memory.

### How it uses Meta models

- **Muse Spark 1.3** is the agent's brain: the tool loop, onboarding (one free-form "about me" turned into a profile) and every lesson question.
- **Muse Image** illustrates the course. 26 illustrations were generated ahead of time by a script and bundled, so lessons work offline and on any provider. On the phone, "Draw it for me" redraws a lesson scene around the learner's own life — the same idea, their world.
- **Muse Spark video understanding** powers "How a model sees": one tap sends a short bundled clip (or the learner films 5–10 seconds of their own), and Muse Spark explains what it saw, what it is unsure about, and why — frames, not continuous motion. The sample clip is itself a Muse Image scene with a slow camera pan. The interactive timeline right before it shows the same thing: what slips between frames, and what extra frames cost in tokens.

### Private by design

- Only the provider the user picked receives requests; images and video are sent only when it is Muse via OpenRouter.
- "Draw it for me" sends only the learner's own "about me" text (capped) and language — never name, age, gender or religion.
- Videos filmed for a lesson go to the app cache, not the gallery, and are deleted right after analysis.
- Model variants that train on user data (Muse Spark "contributor") are filtered out of the model list and the fallback chain.
- Every model call — text, image, video — is recorded with its cost in a weekly spend card.

### Challenges

- **Muse Image declares no parameters on OpenRouter**, so a strict request is just `model` + `prompt`. Composition and a consistent "no text, 16:9, flat editorial" style live in the prompt — one style string shared by the build-time script and the app, kept in sync by a test.
- **Strict tool-call replay:** the Meta API rejects an assistant tool call without its paired tool result. We verified every branch of the loop (stops, budget limits, errors) still answers every call.
- **Video from a phone** can't be a URL, so it goes as a base64 data URL, recorded short and low-quality by the system camera and capped at 20 MB.
- **Filming is friction.** Camera, permissions and light stopped people at the video step, so the main button now sends a bundled sample clip in one tap; filming your own is the second option.
- **A lesson must never hang:** if a picture or a video answer can't be produced (offline, no key, provider error), the step still posts and the lesson continues.

### How it's built

Kotlin, Jetpack Compose, Metro DI, Ktor (SSE streaming), Room, WorkManager. ~42k lines of Kotlin, ~980 unit tests, 426 commits since May 2026, built solo. A traceability check links every feature to the reason it exists and to its code.

### What's next

English lessons translated by Muse Spark; scenes that Muse Spark storyboards on request ("explain RAG as an animation"); SAM 3.1 outlines on the video lesson; Play Store release.

### Built with

Muse Spark 1.3 · Muse Image · OpenRouter · Kotlin · Jetpack Compose · Ktor · Room · WorkManager · Coil

### Links

- Demo video: *(YouTube, после записи)*
- Repository: https://github.com/jQwout/ai-open-pocket-2 *(сейчас приватный — открыть или не давать; помнить про лицензию организаторам на показанные материалы из правил)*
