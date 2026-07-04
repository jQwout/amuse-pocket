package com.aiopenpocket.agent.core.srs

// @trace yazykovoy-agent-repetitor-s-intervalnymi-povtoreniyami-4a9360

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope

/**
 * Storage for spaced-repetition vocabulary cards, backed by a dedicated Room table (not the shared
 * facts store). The tool owns the interval math ([SrsScheduler]); this owns persistence and the
 * "<lang>:<term>" identity so re-adding a word updates it in place.
 */
@SingleIn(AppScope::class)
class SrsRepository @Inject constructor(
  private val dao: SrsWordDao,
) {
  suspend fun upsert(word: SrsWord) {
    val id = idOf(word.lang, word.term)
    val now = System.currentTimeMillis()
    val existing = dao.getById(id)
    dao.upsert(
      SrsWordEntity(
        id = id,
        lang = word.lang.trim().lowercase(),
        term = word.term.trim(),
        translation = word.translation.trim(),
        example = word.example.trim(),
        stage = word.stage,
        dueMs = word.dueMs,
        createdAt = existing?.createdAt ?: now,
        updatedAt = now,
      ),
    )
  }

  suspend fun get(lang: String, term: String): SrsWord? =
    dao.getById(idOf(lang, term))?.toDomain()

  /** All cards, optionally narrowed to one language. */
  suspend fun list(lang: String?): List<SrsWord> =
    (if (lang.isNullOrBlank()) dao.getAll() else dao.getByLang(lang.trim().lowercase()))
      .map { it.toDomain() }

  private fun idOf(lang: String, term: String): String =
    "${lang.trim().lowercase()}:${term.trim().lowercase()}"

  private fun SrsWordEntity.toDomain() = SrsWord(
    lang = lang,
    term = term,
    translation = translation,
    example = example,
    stage = stage,
    dueMs = dueMs,
  )
}
