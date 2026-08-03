package com.aiopenpocket.agent.core.llm

// @trace streaming-llm-chat-6595a1

import kotlinx.coroutines.flow.Flow

interface LlmClient {
  /**
   * Провайдер — ОБЯЗАТЕЛЬНЫЙ параметр, без значения по умолчанию.
   *
   * Раньше здесь стоял `= LlmProvider.NIM`, и два вызова его просто не передавали: аннотатор
   * диалогов (уходит после каждой реплики, тело — дословный кусок переписки) и извлечение профиля
   * из рассказа о себе. Оба молча уезжали к NVIDIA даже после того, как пользователь осознанно
   * переключился на другого вендора, и не падали при этом: NIM-ключ вшит в сборку. Дефолт
   * превращает «забыл прокинуть провайдера» в тихую отправку данных не тому адресату; без него
   * это ошибка компиляции.
   */
  fun streamChat(
    messages: List<ChatMessage>,
    tools: List<FunctionSchema>,
    provider: LlmProvider,
  ): Flow<StreamEvent>
}
