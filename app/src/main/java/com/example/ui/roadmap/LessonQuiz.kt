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
  "l1_welcome" to listOf(
    QuizQuestion(
      question = "Что уходит в облако при твоём запросе?",
      options = listOf(
        "Только текст: промпт и история диалога",
        "Все твои файлы и база приложения целиком",
        "Ничего — всё считается на устройстве",
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
        "Список всех файлов и заметок на устройстве",
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
        "На сервере провайдера в облаке",
        "Нигде — после чата они стираются",
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
        "Сразу делает всё без плана и вопросов",
        "Отменяет задачу, если она сложная",
      ),
      correctIndex = 0,
      explanation = "План-мод разделяет «подумать» и «сделать»: сначала план, потом выполнение.",
    ),
    QuizQuestion(
      question = "Из каких ролей состоит план-мод в этом уроке?",
      options = listOf(
        "Планировщик → исполнитель → синтезатор",
        "Только одна модель без ролей",
        "Планировщик и больше никаких ролей",
      ),
      correctIndex = 0,
      explanation = "Планировщик строит план, исполнитель проходит шаги, синтезатор собирает итог — поверх одного ReAct-движка.",
    ),
  ),
)
