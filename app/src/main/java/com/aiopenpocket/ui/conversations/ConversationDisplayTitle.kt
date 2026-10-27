package com.aiopenpocket.ui.conversations

// @trace multi-chat-conversations-b3c93b

import com.aiopenpocket.agent.core.memory.model.Conversation
import com.aiopenpocket.ui.roadmap.lessonDisplayTitle

/**
 * The title a person reads in a list of chats.
 *
 * A lesson's chat is stored under the lesson id (`how_model_sees`): that is how the chat is found again
 * (`findLatestTutorialConversation`) and how the chat screen knows which lesson to drive, so the stored
 * [Conversation.title] cannot become a human name. The lists that show chats turn the id back into the
 * lesson's name here, in the interface language. An id that is not a known lesson stays as stored.
 */
internal fun Conversation.displayTitle(): String =
  if (isTutorial) lessonDisplayTitle(title) ?: title else title
