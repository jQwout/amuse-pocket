package com.aiopenpocket.ui.chat

// @trace sam-segmentation-boxes-in-a-lesson-c3ea52

import com.aiopenpocket.agent.core.llm.SegmentationFailure
import com.aiopenpocket.i18n.tr

/**
 * What to tell the learner when the "segment" step did not draw anything. One sentence per cause, because the
 * fixes differ: a missing key is added in Settings, missing billing is fixed at Meta, and a bad word is
 * retyped on the card. A single "it did not work" would send all of them to the wrong place.
 *
 * Called when the failure is shown, not kept in a static list: [tr] reads the language at that moment.
 */
internal fun segmentationProblemText(reason: SegmentationFailure): String = when (reason) {
  SegmentationFailure.NO_KEY -> tr(
    "Для этого шага нужен ключ Meta Model API: добавь его в Настройках → «КЛЮЧ API». Без ключа шаг можно пропустить.",
    "This step needs a Meta Model API key — add it in Settings → API KEY. Without a key you can skip the step.",
  )
  SegmentationFailure.BAD_CONCEPT -> tr(
    "Напиши одно понятие по-английски, без запятых и команд, например: cup.",
    "Type one thing to find, in English, with no commas or commands — for example: cup.",
  )
  SegmentationFailure.IMAGE_TOO_LARGE -> tr(
    "Фото слишком большое для модели. Выбери другое.",
    "The photo is too large for the model. Pick another one.",
  )
  SegmentationFailure.BAD_REQUEST -> tr(
    "Meta не приняла запрос. Попробуй другое слово.",
    "Meta rejected the request. Try another word.",
  )
  SegmentationFailure.UNAUTHORIZED -> tr(
    "Meta не приняла ключ. Проверь его в Настройках → «КЛЮЧ API».",
    "Meta didn't accept the key. Check it in Settings → API KEY.",
  )
  SegmentationFailure.BILLING -> tr(
    "В аккаунте Meta не настроена оплата (billing_not_configured). SAM платный: 0,0025 \$ за фото.",
    "Billing isn't set up on your Meta account (billing_not_configured). SAM is paid: \$0.0025 per photo.",
  )
  SegmentationFailure.NO_MODEL_ACCESS -> tr(
    "У этого ключа нет доступа к модели sam-3.1.",
    "This key has no access to the sam-3.1 model.",
  )
  SegmentationFailure.RATE_LIMITED -> tr(
    "Meta просит подождать: слишком много запросов. Попробуй через минуту.",
    "Meta asks you to wait: too many requests. Try again in a minute.",
  )
  SegmentationFailure.SERVER -> tr(
    "Сервер Meta сейчас не отвечает. Попробуй ещё раз.",
    "Meta's server isn't responding right now. Try again.",
  )
  SegmentationFailure.NETWORK -> tr(
    "Нет связи с Meta. Проверь сеть и попробуй ещё раз.",
    "Couldn't reach Meta. Check your network and try again.",
  )
  SegmentationFailure.BAD_RESPONSE, SegmentationFailure.INCOMPLETE, SegmentationFailure.REFUSED -> tr(
    "Модель ответила так, что приложение не смогло прочитать. Попробуй ещё раз или другое слово.",
    "The model's answer couldn't be read. Try again, or try another word.",
  )
}
