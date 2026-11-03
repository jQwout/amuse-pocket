package com.aiopenpocket.voice.call

// @trace voice-call-mode-54cb49

/**
 * Added to the system prompt of every turn started from a call (TurnManager's `systemPromptExtra`, the
 * same trusted channel a lesson step uses). A reply that reads well on screen sounds bad aloud: a
 * list becomes a monotone run of fragments, a table becomes commas, and a long answer keeps the person
 * waiting for the floor. So in a call the agent talks the way a person on the phone does.
 */
internal const val CALL_PROMPT = """
You are on a voice call with the person. Everything you write is read aloud by a speech engine, and what
they say reaches you through speech recognition, so expect small recognition slips and do not point them out.
- Answer in one to three short spoken sentences. Say more only when they ask for details.
- Plain speech only: no lists, tables, headings, markdown, links, emoji or code.
- Ask at most one question at a time.
- Before a tool call that takes a while, say in a few words what you are about to do.
- Reply in the language the person speaks.
"""
