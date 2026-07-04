package com.aiopenpocket.ui.roadmap

// @trace ai-academy-onboarding-5d51bb

/** Single multiple-choice quiz question shown on the post-lesson quiz screen. */
data class QuizQuestion(
  val question: String,
  val options: List<String>,
  val correctIndex: Int,
  /** Shown after the user answers, regardless of correctness. */
  val explanation: String,
)

/** Quiz questions per roadmap level. A level with no entry simply has no quiz. */
val LESSON_QUIZZES: Map<String, List<QuizQuestion>> = mapOf(
  // ВНИМАНИЕ: онбординг (l1_welcome, firstRun) рендерится ЭТИМ хардкодом + хардкод-шагами
  // OnboardingViewModel/OnboardingScreen, а НЕ assets/lessons/l1_welcome.json. JSON-версию урока
  // валидирует LessonAssetsValidationTest, но при показе онбординга она не используется: ChatViewModel
  // исключает l1_welcome из JSON-драйвера, а роадмап открывает его своим экраном (onReopenOnboarding).
  // Итог: правь квиз онбординга ЗДЕСЬ — редактировать l1_welcome.json ради онбординга бесполезно,
  // до экрана эти изменения не дойдут. (Ловушка задокументирована; см. code-review 2026-07-03.)
  "l1_welcome" to listOf(
    QuizQuestion(
      question = "Что уходит в облако при твоём запросе?",
      options = listOf(
        "Только текст: промпт и история диалога",
        "Все твои файлы и база",
        "Ничего не уходит",
      ),
      correctIndex = 0,
      // Не «история остаётся»: история диалога КАК РАЗ уходит в облако в составе запроса —
      // прежняя формулировка противоречила правильному ответу.
      explanation = "В облако уходит только текст: препромпт, история диалога и твоё сообщение. Файлы, база и долгая память остаются на устройстве.",
    ),
    QuizQuestion(
      question = "Из чего состоит препромпт?",
      options = listOf(
        "Личность ассистента + информация о тебе",
        "Только твоё последнее сообщение",
        "Список всех твоих файлов",
      ),
      correctIndex = 0,
      explanation = "Препромпт — скрытая инструкция: выбранная персона плюс твой профиль.",
    ),
  ),
  "l2_notes" to listOf(
    QuizQuestion(
      question = "Где живут твои заметки?",
      options = listOf(
        "В защищённой песочнице на устройстве",
        "На сервере в облаке",
        "Нигде, они не сохраняются",
      ),
      correctIndex = 0,
      explanation = "Файлы хранятся в песочнице на устройстве и в облако сами не уходят.",
    ),
    QuizQuestion(
      question = "Чем найти заметку по содержимому?",
      options = listOf("search_files", "write_file", "list_dir"),
      correctIndex = 0,
      explanation = "search_files ищет по содержимому. list_dir лишь покажет список имён.",
    ),
  ),
  "l3_reminders" to listOf(
    QuizQuestion(
      question = "Как часто можно повторять задачу минимум?",
      options = listOf("Раз в минуту", "Раз в 5 минут", "Раз в 15 минут"),
      correctIndex = 2,
      explanation = "Android не будит приложение чаще, чем раз в 15 минут.",
    ),
    QuizQuestion(
      question = "Чем поставить разовое напоминание?",
      options = listOf("schedule_notification", "schedule_periodic", "cancel_work"),
      correctIndex = 0,
      explanation = "schedule_notification — одноразовое уведомление. schedule_periodic повторяет.",
    ),
  ),
  "l4_memory" to listOf(
    QuizQuestion(
      question = "Что агент помнит даже в новом чате?",
      options = listOf(
        "Сохранённые факты о тебе",
        "Весь прошлый диалог дословно",
        "Ничего — память сбрасывается",
      ),
      correctIndex = 0,
      explanation = "Факты из remember_fact хранятся на устройстве и доступны в любом чате.",
    ),
  ),
  "plan_mode" to listOf(
    QuizQuestion(
      question = "Что делает план-мод?",
      options = listOf(
        "Сначала строит план, потом выполняет",
        "Сразу делает без плана",
        "Отменяет задачу",
      ),
      correctIndex = 0,
      explanation = "План-мод разделяет «подумать» и «сделать»: сначала план, потом выполнение.",
    ),
    QuizQuestion(
      question = "Из каких ролей состоит план-мод в этом уроке?",
      options = listOf(
        "Планировщик → исполнитель → синтезатор",
        "Только одна модель без ролей",
        "Планировщик и больше ничего",
      ),
      correctIndex = 0,
      explanation = "Планировщик строит план, исполнитель проходит шаги, синтезатор собирает итог — поверх одного ReAct-движка.",
    ),
  ),
)
