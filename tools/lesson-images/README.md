# Картинки уроков

Рисует иллюстрации к урокам через Muse Image и кладёт их в сборку, чтобы урок показывал
картинку без сети, бесплатно и на любом провайдере.

## Запуск

Сначала посмотреть, что будет нарисовано и сколько это стоит:

    python tools/lesson-images/pregenerate.py --dry-run

Нарисовать (нужен ключ OpenRouter и Pillow: `pip install pillow`). Вместо `sk-or-...` —
свой настоящий ключ.

PowerShell (Windows):

    $env:OPENROUTER_API_KEY = "sk-or-..."; python tools/lesson-images/pregenerate.py

bash / Git Bash:

    OPENROUTER_API_KEY=sk-or-... python tools/lesson-images/pregenerate.py

Только отдельные уроки — добавь `--only rag plan_mode`.

## Что делает

Берёт шаги `{"type": "image"}`, у которых есть `imagePrompt`, но нет `asset`/`imageKey`.
Для каждого:

1. просит у `meta/muse-image` картинку по промпту и общему стилю уроков;
2. ужимает её в WebP шириной до 1280 px и кладёт в
   `app/src/main/assets/lessons/images/generated/<урок>-<хеш промпта>.webp`;
3. вписывает в шаг урока `"asset": "lessons/images/generated/…"`.

JSON урока правится как текст, а не пересобирается: ручное форматирование и переводы строк
остаются как были. Поэтому промпт — печатный ASCII без кавычек и обратных слешей, это
проверяет `LessonAssetsValidationTest`.

Шаги, у которых `asset` уже есть, скрипт не трогает. Если прошлый запуск нарисовал картинку,
но не успел вписать её в урок, файл переиспользуется без новой генерации.

## Как это связано с приложением

У шага с ассетом приложение показывает картинку из сборки и ничего не рисует. Рисование на
телефоне (`LessonImageGenerator`) остаётся для шагов без ассета и работает, только когда
провайдером выбран OpenRouter.

Стиль промпта (`STYLE` в скрипте) обязан дословно совпадать с `LESSON_IMAGE_STYLE` в
`LessonImageGenerator.kt`, иначе картинки из сборки и с телефона разъедутся по стилю. Это
проверяет `LessonImageStyleSyncTest`.
