# Сабмишн на Meta Global AI Developer Hackathon

Сценарий демо-ролика и текст для судей. Сдать до **4 ноя, 21:00 ET** (5 ноя, 03:00 по Белграду). План: ролик 2 ноября, отправка 3-го, 4-е в запасе. Цифры сняты 25 окт 2026.

**Правила.** Источник — Participant Guide в кабинете участника (`cerebralvalley.ai/e/meta-ai-developer-hackathon/details`, видно только залогиненным; прочитан 26 окт). Official Rules отдельным документом там нет: в гайде сказано, что их публикуют до старта и допущенные обязаны их принять — если появятся, перечитать.

**Жёсткие требования, из которых всё остальное:**

- **Репозиторий обязан быть публичным** («Open source: Repositories must be public»).
- **Демо показывает только то, что сделано на хакатоне.** Судьи должны ясно понимать, что именно создано в окне; невозможность это различить = немедленная дисквалификация на усмотрение судей. Право надстраивать существующий код дано стартапам (и то с явным разделением нового) — мы на Indie.
- **Окно работы: 27 окт — 4 ноя.** Всё, что закоммичено 23–26 окт (Muse, картинки, видео-урок, сцены, выпиливание NIM, аудит, английский интерфейс пути демо — 26 окт, `3b3ca8d`), — ДО окна, и даты видны в git.
- **Демо-ролик 3 минуты** — обязателен и назван одним из главных артефактов судейства наравне с кодом и живым продуктом.
- Команда ≤4, один сабмишн на команду, представитель команды отправляет; сдача через платформу CV до 4 ноя 21:00 ET; опоздания не рассматривают.

**Как судят — три стадии.** Stage 1 — pass/fail: проект попадает в тему и реально использует Meta Model API и/или открытые веса Meta. Stage 2 — рубрика 0–10 с весами: **Impact 30%** (кому и насколько это нужно, станет ли этим кто-то пользоваться, попадает ли в тему), **Use of Meta AI tooling 25%** (насколько творчески, вышли ли за базовую интеграцию, «удивили ли нас»), **Technical Depth & Execution 20%**, **Demo 25%** (работает ли живьём, интересно ли смотреть ролик). Stage 3 — топ-5 на трек, судьи смотрят ролики живьём и ранжируют по той же рубрике.

**Anti-projects — чего нельзя.** В гайде есть список «generic» проектов под дисквалификацию, и два пункта касаются нас впрямую: **простые «AI for Education» чат-боты** и **AI Nutrition Coach** (а также базовый RAG, простые анализаторы картинок, медсоветы, анализаторы личности, спорт, политика и война). Отсюда два правила подачи: заголовок и первые 15 секунд ролика — **платформа сборки агентов**, а обучение — её часть («учим возможностям моделей Meta», не «обучающий бот»); агента про питание в ролике и в тексте **не показывать вообще**.

## 0. Подача

**Для кого (Impact, решение юзера 26 окт).** Для людей, которым интересно, как применять ИИ-агентов, но которые стесняются спросить. Как книжки «для чайников» про Windows из 90-х, только про ИИ-агентов.

Как это стыкуется с запретом на «AI for Education» чат-ботов: те книжки не читали лекций про Windows — они вели по шагам, пока ты сам что-то делал. Здесь так же: на выходе не знания, а **работающий агент на твоём телефоне**, собранный своими руками. Стесняться некого — спрашиваешь модель, а не человека. Ошибиться не страшно — пробный прогон имитирует действия, а удаление и отправка наружу всегда ждут твоего «да»: это страховочные колёса.

Сравнение с «For Dummies» — только в тексте и в голосе ролика; в название и слоган его не выносить: это товарный знак издательства Wiley.

**AI Open Pocket — платформа для создания ИИ-агентов на моделях Meta, со встроенным обучением.** Две части:

1. **Обучение (теория).** Что умеют модели Meta и как из них строится агент: уроки про линейку Meta, режимы Muse Spark, как рисует Muse Image, открытые веса и API, как модель видит видео; уроки про устройство агента — инструменты, память, навыки, правила, планы. В каждом уроке интерактивная сцена, в части уроков модель Meta работает вживую.
2. **Платформа.** Конструктор: собираешь агента на Muse Spark (роль, инструкции, инструменты, режимы, правила), проверяешь пробным прогоном и запускаешь. Агент живёт на телефоне, делает настоящие дела и спрашивает разрешения перед удалением и отправкой наружу.

Ролик — не промо, а демо: запись экрана настоящего приложения, английский голос и субтитры, подписи внизу кадра, какая модель Meta сейчас работает.

## 1. До записи

- [ ] **Репозиторий открыт** — без этого сабмишн не принимают. Порядок в разделе 4.
- [ ] **Сроки работы — честно.** Muse-часть сделана 23–25 окт, английский интерфейс пути демо — 26 окт; всё это до окна 27 окт — 4 ноя. В описании разделить «было до хакатона» и «сделано в окне»; заметную часть делать в окне. Выдавать сделанное раньше за сделанное на хакатоне нельзя: даты видны в git, а за нечитаемый вклад в гайде обещана дисквалификация.
- [ ] **Ролик ровно про сделанное в окне.** Если сцена ролика показывает то, что было до 27 окт, — либо переделать это в окне, либо не показывать.
- [x] **Питание убрано целиком** (30 окт, решение владельца): агента «Нутрициолог» нет ни в ростере, ни в ролике, ни в тексте (anti-project «AI Nutrition Coach» и медсоветы); его шаблон плана удалён вместе с ним.
- [x] **Английский интерфейс на пути демо** — сделан 26 окт (`3b3ca8d`), то есть до окна: в тексте для судей он в «Before the hackathon», в «Built during» его не писать.
- [ ] **Конструктор от начала до конца на эмуляторе:** все шаги, пробный прогон, публикация, чат с агентом. Пробный прогон на устройстве ещё не проверялся.
- [ ] **Письмо на эмуляторе:** после «Разрешить» должно открыться почтовое приложение — проверить, что оно есть.
- [ ] **SAM 3.1 (обещан в заявке)** — только если после допуска дадут кредиты на Meta Model API.
- [ ] Чистые данные для записи: свежая установка, ключ Meta Model API введён в приложении (онбординг или Настройки; в сборку ключи не вшиты), карточка расходов не пустая.

## 2. Сценарий ролика (3:00)

Запись с эмулятора, 1080p. Ожидание ответов модели вырезаем или ускоряем с пометкой «sped up». Подпись модели — внизу кадра в момент её работы.

| Время | Что на экране | Подпись | Голос (EN) |
| --- | --- | --- | --- |
| 0:00–0:15 | Главный экран: «Путь», «Агенты» | — | Remember the "For Dummies" books? They didn't lecture you about Windows — they walked you through it. AI Open Pocket does that for AI agents: you build a real one, on Meta's models, right on your phone. |
| 0:15–0:35 | Урок «Модели Meta: кто есть кто», сцена-линейка | — | First, learn. Short interactive lessons explain Meta's models — Muse Spark, Muse Image, SAM, Llama — and how an agent is built from them. |
| 0:35–1:15 | Урок «Как модель видит»: ползунок кадров 1 → 4; «Показать на примере» → ответ в чате | Muse Spark · video understanding | Video becomes frames, and frames become tokens. One tap sends a clip to Muse Spark — it lists what it sees and names the one thing it isn't sure about. Live, not a mock-up. |
| 1:15–2:15 | Конец курса → «Открыть конструктор» → имя и роль → инструкции → инструменты («Память», «Напоминания», «Почта») → режим → пробный прогон → «Опубликовать» | Muse Spark · agent brain | Then, build. The builder turns the lessons into a real agent: a role, instructions, tools, a mode, rules. A dry run simulates every action before anything touches the phone. Every agent you build thinks with Muse Spark. |
| 2:15–2:50 | Чат агента: «remind me in 2 minutes to call mom» → «Напоминания: поставить → ок»; «email Masha the meeting moves to Friday» → «Агенту нужно разрешение · Покидает устройство» → «Разрешить»; уведомление приходит | Muse Spark · tool call | And it runs. Every action is visible. Reading and writing just happen; deleting or sending anything off the phone always waits for my OK. Two minutes later — the reminder, on time. |
| 2:50–3:00 | Логотип, слоган | — | AI Open Pocket — learn Meta's models, build agents on them. Built solo on Muse Spark and Muse Image. |

Запасной вариант, если Meta Model API в день записи не ответит: 0:35–1:15 — только ползунок кадров и сцена «Цикл ReAct».

## 3. Текст для судей (EN)

**Project name:** AI Open Pocket

**Tagline:** Build your first AI agent on Meta's models, right on your phone — for everyone curious about agents but too shy to ask.

**Track / theme:** Indie developer · Build for Your World

### Who it's for

People who keep hearing about AI agents, wonder what one could do for them, and feel too embarrassed to ask. In the 90s, the "For Dummies" books did this for Windows: no lectures — they walked you through doing it yourself. AI Open Pocket does the same for AI agents. You don't read about an agent; you build one, on Meta's models, on your own phone. There is no one to feel silly in front of: you ask the model, not a person. And there is nothing to break: a dry run simulates every action, and anything that deletes data or leaves the phone waits for your yes.

### Short description

AI Open Pocket is an Android platform for building AI agents on Meta's models, with learning built in. First you learn: 31 short interactive lessons explain what Meta's models can do and how an agent is made of them, with Muse Spark and Muse Image working live inside the lessons. Then you build: a no-code builder turns those lessons into your own agent — role, instructions, tools, modes, rules — and runs it on your phone, on Muse Spark, asking before anything risky.

### What it does

- **Learn.** 31 lessons from "what is an AI" to building your own agent, each a strict step contract driven by code, with an interactive scene you can touch: drag the temperature, scroll your name out of the context window, step an agent through its ReAct loop. Four lessons are about Meta's own models: the lineup (Muse Spark, Muse Image, SAM 3.1, Voice Transcribe, Llama), Muse Spark's reasoning modes, how Muse Image plans and draws, open weights vs API.
- **Build.** A no-code agent builder: identity, instructions, 14 on-device tools (reminders, alarms, calendar, notifications, files, memory, email drafts, spaced-repetition cards, generative UI cards and more), custom skills, modes (chat, reflection, plan, goal) and declarative rules. A dry run simulates every side effect before the agent goes live.
- **Run.** Agents live on the phone: a ReAct loop over function calling, layered memory, background reminders and heartbeats, a structured plan engine with approval and evidence-checked steps. Safe by default: reading and writing just happen, deleting or sending anything off the device always asks in chat and is never done in the background.

### How it uses Meta models

- **Muse Spark 1.3** is the brain of every agent built on the platform: the tool loop, reflection, plans, onboarding and every lesson question. Fallback stays inside Meta's lineup (Muse Spark 1.2 → Wonder → …).
- **Muse Spark video understanding** powers "How a model sees": one tap sends a short clip (or the learner films their own), and Muse Spark explains what it saw, what it is unsure about, and why.
- **Muse Image** illustrates the course — generated ahead of time and bundled, so lessons work offline — and "Draw it for me" redraws a lesson scene around the learner's own life.

### Private and safe by design

- Only Meta models are used; variants that train on user data ("contributor") are filtered out of the model list and the fallback chain.
- Destructive and outbound actions always need the user's OK; background runs can't do them at all.
- "Draw it for me" sends only the learner's own "about me" text and language — never name, age or gender. Lesson videos stay in the app cache and are deleted after analysis.
- Every model call — text, image, video — is metered in a weekly spend card.
- **Your key, your quota.** The Meta API key is typed into the app by the person using it and kept in `EncryptedSharedPreferences` behind an Android Keystore master key. No key lives in the repository — `.env` is gitignored and has never been committed. And no live key can reach a shipped build: a Gradle check fails `bundleRelease` and `assembleRelease` when `.env` holds one, because the secrets plugin copies `.env` into `BuildConfig`, and `BuildConfig` travels inside the AAB — one unzip away from anyone. (Local debug builds do read a key from the developer's own gitignored `.env`, so onboarding can reach a model before the settings screen is open; a release build gets the placeholder instead and uses only the key you type in.)

### Challenges

- **Strict tool-call replay:** Meta's API rejects an assistant tool call without its paired result; every branch of the loop (stops, limits, errors, denials) answers every call.
- **A denial must read as a decision:** a bare "not approved" made the model tell users to enable a permission; the loop now says the user declined.
- **Muse Image is driven by the prompt alone** (the app sends no extra parameters), so composition and one consistent style live in the prompt, shared by the build-time script and the app and kept in sync by a test.
- **Video from a phone** goes as a base64 data URL, short and capped at 20 MB.

### How it's built

Kotlin, Jetpack Compose, Metro DI, Ktor (SSE streaming), Room, WorkManager. ~49k lines of Kotlin, 1075 unit tests on CI, 432 commits since May 2026, built solo. A traceability check links every feature to the reason it exists and to its code.

### Before / during the hackathon

*Обязательный раздел: судьи должны различить вклад, иначе дисквалификация. Черновик от оркестратора 27 окт, цифры из git; формулировки — за владельцем.*

**Before the hackathon** *(up to Oct 26)* — the platform existed: the agent loop, the builder, the on-device tools, the lesson engine, Meta models through OpenRouter (Oct 24, `b83db81`), lesson images by Muse Image (`c17a968`, `85979eb`), the video-input lesson on Muse Spark (`682649c`), an interactive scene in every lesson and the four Meta-model lessons (Oct 25, `ff190fd`), NVIDIA NIM removed so the whole app runs on Muse (`197306c`), **the direct Meta Model API route with the key entered by the user** (Oct 26, `256dd03`), and the English interface on the demo path with three lessons translated (Oct 26, `3b3ca8d`; the rest of the course stayed Russian).

**Built during the hackathon** *(Oct 27 – Nov 4, commits `0f1e9a5`..)* — from Oct 30, the app talks only to the Meta Model API with a key the person enters in onboarding or Settings (OpenRouter and every bundled key removed). As of Oct 27, 26 commits: the Settings and Scheduled-tasks screens fully in English (`5530793`, `657c1e2`), every source comment and tool README translated, `DrivenUiCardViews`/`SettingsCards`/`RoadmapScreen` split up, dead code and manifest lint cleared, and a criteria-driven orchestrator with one check command over the whole project — build, 1091 unit tests, traceability, key scans of the sources and of the built APK, the English side of every `tr()` pair (`d8a8f7a`, `e92d7d6`, `20b1c0d`).

> **Внимание, решение за владельцем.** По состоянию на 27 окт в окне нет ни одной
> пользовательской возможности, которую судья увидел бы в демо: окно — это перевод двух экранов,
> перевод комментариев, рефакторинг и внутренний оркестратор. Вся продуктовая часть на моделях Meta
> (включая прямой Meta Model API, главный аргумент Stage 1) закоммичена 23–26 окт, то есть **до**
> окна, и даты видны в git. Гайд обещает немедленную дисквалификацию, если вклад в окне не
> различить, а сценарий ролика сейчас показывает ровно то, что сделано до 27 окт. Нужна заметная
> продуктовая работа в окне 28 окт — 4 ноя: кандидаты — SAM 3.1 и Voice Transcribe через прямой
> Meta Model API (обещаны в заявке), перевод остального курса на английский, агенты, которые видят
> и рисуют. Выбор — за владельцем, это про подачу, а не про код.

### What's next

English and other languages for the whole course via Muse Spark; SAM 3.1 and Voice Transcribe through Meta Model API; agents that see and draw; Play Store release.

### Built with

Muse Spark 1.3 · Muse Image · Meta Model API · Kotlin · Jetpack Compose · Ktor · Room · WorkManager

### Links

- Demo video: *(после записи)*
- Repository: https://github.com/jQwout/amuse-pocket *(публичный с 29 окт 2026; см. раздел 4)*

## 4. Публикация репозитория

Без публичного репо сабмишн не примут, поэтому это не «потом», а отдельная задача со своим порядком.

**Что мешает.** В истории лежит собранный APK `.build-outputs/app-debug.apk` (20,7 МБ), добавлен в `0e3331a`, удалён только в `197306c`. Внутри него, в `classes4.dex` и `classes9.dex`, зашит Google-ключ `AIzaSyDSmlm5Mc1g…` эпохи AI Studio (проверено 26 окт распаковкой блоба из истории). Удаление файла в последнем коммите ничего не меняет: блоб достаётся из любого коммита диапазона.

**Порядок:**

1. **Отозвать ключ** `AIzaSyDSmlm5Mc1g…` в Google Cloud Console — это делает юзер, и это главное: даже после чистки истории копии блоба могут остаться у клонов и в кеше GitHub.
2. **Выбросить `.build-outputs/` из всей истории** (`git filter-repo --path .build-outputs --invert-paths`, при отсутствии filter-repo — `filter-branch`/BFG). Побочно уносит 20 МБ мусора. Переписывает хеши ВСЕХ коммитов, поэтому делать на чистом дереве и когда ни одна ветка не в работе.
3. **Повторно просканировать** историю тем же способом (распаковка всех блобов + поиск по шаблонам ключей). Проверено 26 окт: `.env` в истории никогда не было; единственное совпадение по `sk-or-v1-` — заведомо фейковый ключ в `RequestJournalTest.kt`.
4. **Force-push**, затем README на английском и лицензия. В README — как собрать и **где взять свой ключ Meta Model API и куда его вставить в приложении** (ключ вводит пользователь, в репозитории и в артефакте его нет): судьи пойдут этим путём, и спотыкаться им негде.
5. **Сделать репо публичным** и открыть ссылку в сабмишне.

Делать после того, как закончится работа над английским интерфейсом: переписывание истории на ветке, которую параллельно правят, гарантированно всё поломает.

**Сделано 29 окт 2026 — другим путём, чем расписано выше.** Ключ владелец отозвал. При подготовке выяснилось, что force-push старый репозиторий не очищает: на GitHub в нём 16 ссылок пулл-реквестов (`refs/pull/*`), их нельзя ни переписать, ни удалить, и коммиты с APK остались бы достижимы со страниц PR. Поэтому вычищенная история (`filter-branch` в отдельном голом клоне `mvp`, 512 коммитов, дерево последнего коммита байт в байт совпадает с исходным) залита в **новый** репозиторий https://github.com/jQwout/amuse-pocket, а старый `ai-open-pocket-2` остался закрытым архивом со всеми ветками и не тронут. Сканер прошёл по каждому блобу новой истории с распаковкой архивов и дал CLEAN; на старой истории тот же сканер нашёл отозванный ключ в `classes4.dex` и `classes9.dex` — то есть он умеет падать. Все хеши поменялись; `orchestrator/COMMIT_MAP.txt` сопоставляет старые с новыми.

## 5. Бонусные призы ($50K помимо треков)

Берём те, что ложатся на проект без отдельной разработки:

- **Best Agentic Use-Case $5K** — наш профиль: судят автономность, эффективность в своей задаче и практическую пользу. Значит в ролике нужен не один тул-колл, а многошаговый ход агента с решением и подтверждением.
- **Most Viral Project $20K** — пост с демо на X плюс ссылка на пост, отправленная через платформу; считают лайки, репосты и комментарии до 8 ноя включительно. Нужен аккаунт X и пост сразу после сабмишна, не 8-го.
- **Community Vote $10K** — голоса в публичной галерее, один голос с подтверждённого аккаунта, объявят 16 ноя. Делать нечего, кроме понятной карточки проекта и ролика.
- **Best Creative Build $5K** (Muse Image или Wonder) — картинки курса рисует Muse Image, формально подходим; отмечают на первом круге судейства. Не гнаться, но упомянуть Muse Image в сабмишне обязательно.
- **Best Build on Open Weights $10K** — требует Muse Glimmer или Muse Spark Open на своём железе либо у партнёра (Baseten/Modal). Сейчас не наш путь; открывается, если дадут партнёрские кредиты.

## 6. Кредиты, даты и ссылки

**Кредиты — заявки подаёт юзер сам, за другого нельзя. Два потолка работают по принципу «кто раньше».**

| Что | Сколько | Условия | Форма |
| --- | --- | --- | --- |
| Meta Model API | $150 | до 4 ноя 21:00 ET, нужна привязанная карта (будет временная преавторизация), лимит расходов ставится на странице Billing; начисляют партиями | [форма](https://docs.google.com/forms/d/e/1FAIpQLScam78z7tHsNYibiFyJQVFzEgr0coyBTiY568SxxNbsPa3jiw/viewform) |
| Baseten | $100 + 2M TPM | **не более 2000 участников**, отбор по описанию применения; в хакатоне модель называется `meta-models/muse-spark-open-1026` (а не `muse-spark-open`, как в доках) | [форма](https://docs.google.com/forms/d/e/1FAIpQLSfM7gcK8yMZEmAGq6DbTyiuv2c71IH7CKCy0fXGIfUED1qxVQ/viewform) |
| Modal | $100 | **первым 1000** | [форма](https://docs.google.com/forms/d/e/1FAIpQLSe_GDPtLZsdA57xPhFcOCi_yLjPf8HRxMIQJ5V7ystoKSqWCQ/viewform) |
| Fireworks | $25 | шортлист команд, присылают реквизиты письмом | [форма](https://docs.google.com/forms/d/e/1FAIpQLSdPQTTES8_nhavzZkOrebVNYbM1FumWLdpeZx8ZSNe4QLXKPA/viewform) |

**Даты (ET, +6 ч к Белграду):** окно работы 27 окт — 4 ноя · воркшопы 27 окт Agents 101, 28 окт Agents 102 (verifiable agents), 30 окт Fireworks · офис-часы Meta в Discord по будням 10:00–11:00, текстом в `#office-hours` · 3 ноя — последний день вопросов по сабмишну · **4 ноя 21:00 — сдача** · 5–8 ноя судейство · 9 ноя 12:00 закрытие (топ-5 на трек, живой финал) · 16 ноя — народное голосование.

**Ссылки:** [сабмишн](https://cerebralvalley.ai/e/meta-ai-developer-hackathon/hackathon/submit) · [Discord](https://cerebralvalley.ai/e/meta-ai-developer-hackathon/discord) · [трансляция](https://cerebralvalley.ai/e/meta-ai-developer-hackathon/live) · [гайд участника](https://cerebralvalley.ai/e/meta-ai-developer-hackathon/details) · [Meta Model API](https://dev.meta.ai/products/meta-model-api) · [quickstart](https://dev.meta.ai/docs/quickstart) · [список моделей](https://dev.meta.ai/docs/models) · [кукбук](https://github.com/meta-models/meta-model-cookbook) · [SAM 3.1](https://dev.meta.ai/models/sam-3-1) · [Voice Transcribe](https://dev.meta.ai/models/muse-voice-transcribe)
