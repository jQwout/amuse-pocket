package com.aiopenpocket.agent.core.memory.repository

import com.aiopenpocket.agent.core.memory.dao.WikiLinkDao
import com.aiopenpocket.agent.core.memory.dao.WikiPageDao
import com.aiopenpocket.agent.core.memory.dao.WikiTagDao
import com.aiopenpocket.agent.core.memory.entity.WikiLinkEntity
import com.aiopenpocket.agent.core.memory.entity.WikiPageEntity
import com.aiopenpocket.agent.core.memory.entity.WikiPageTagEntity
import com.aiopenpocket.agent.core.memory.model.LinkDirection
import com.aiopenpocket.agent.core.memory.model.WikiNeighbor
import com.aiopenpocket.agent.core.memory.model.WikiPage
import com.aiopenpocket.agent.core.memory.model.WikiPageView
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope
import java.util.UUID

/**
 * Knowledge-graph memory store: wiki pages + tags + directed links.
 *
 * Pages are addressed by a stable [slug]. Retrieval is graph-shaped — by tag overlap and by
 * one-hop link traversal — rather than substring search over flat facts.
 */
@SingleIn(AppScope::class)
class WikiRepository @Inject constructor(
  private val pageDao: WikiPageDao,
  private val tagDao: WikiTagDao,
  private val linkDao: WikiLinkDao,
) {

  /**
   * Create or update a page addressed by [slug] (derived from [title] when blank). Existing pages
   * keep their id/createdAt so inbound links and tags survive; [tags] fully replace the prior set
   * when provided (a `null` list leaves existing tags untouched).
   */
  suspend fun upsertPage(
    slug: String?,
    title: String,
    content: String,
    tags: List<String>? = null,
  ): WikiPage {
    val normalizedSlug = normalizeSlug(slug?.takeIf { it.isNotBlank() } ?: title)
    val existing = pageDao.getBySlug(normalizedSlug)
    val now = System.currentTimeMillis()

    val id: String
    val createdAt: Long
    if (existing == null) {
      id = UUID.randomUUID().toString()
      createdAt = now
      pageDao.insert(
        WikiPageEntity(
          id = id,
          slug = normalizedSlug,
          title = title.trim(),
          content = content.trim(),
          createdAt = now,
          updatedAt = now,
        ),
      )
    } else {
      id = existing.id
      createdAt = existing.createdAt
      // UPDATE in place (not REPLACE) so cascade does not wipe tags/links.
      pageDao.update(id, title.trim(), content.trim(), now)
    }

    if (tags != null) {
      tagDao.deleteForPage(id)
      tags.map(::normalizeTag).filter { it.isNotBlank() }.distinct().forEach {
        tagDao.insert(WikiPageTagEntity(pageId = id, tag = it))
      }
    }

    return WikiPage(
      id = id,
      slug = normalizedSlug,
      title = title.trim(),
      content = content.trim(),
      tags = tagDao.getTagsForPage(id),
      createdAt = createdAt,
      updatedAt = now,
    )
  }

  /** Resolve a page by [slugOrId] and load its one-hop neighborhood. */
  suspend fun getPage(slugOrId: String): WikiPageView? {
    val entity = resolve(slugOrId) ?: return null
    val neighbors = neighborsOf(entity.id)
    return WikiPageView(entity.toDomain(tagDao.getTagsForPage(entity.id)), neighbors)
  }

  /**
   * Find pages by tag overlap and/or free text. When [tags] is non-empty, tag matches lead and
   * text matches fill the remaining slots; deduped, ranked best-first.
   */
  suspend fun search(
    query: String? = null,
    tags: List<String> = emptyList(),
    limit: Int = DEFAULT_SEARCH_LIMIT,
  ): List<WikiPage> {
    val normalizedTags = tags.map(::normalizeTag).filter { it.isNotBlank() }.distinct()
    val byTag = if (normalizedTags.isNotEmpty()) {
      pageDao.getPagesByTags(normalizedTags, limit)
    } else {
      emptyList()
    }
    val byText = query?.takeIf { it.isNotBlank() }?.let {
      pageDao.search(escapeLike(it.trim()), limit)
    }.orEmpty()

    val merged = LinkedHashMap<String, WikiPageEntity>()
    (byTag + byText).forEach { merged.putIfAbsent(it.id, it) }
    return merged.values.take(limit).map { it.toDomain(tagDao.getTagsForPage(it.id)) }
  }

  /** Most recently touched pages — used to seed context when nothing else matches. */
  suspend fun recentPages(limit: Int = DEFAULT_SEARCH_LIMIT): List<WikiPage> =
    pageDao.getRecent(limit).map { it.toDomain(tagDao.getTagsForPage(it.id)) }

  /**
   * Create a directed [relation] link from one page to another (both addressed by slug or id).
   * Returns false when either endpoint does not exist.
   */
  suspend fun link(
    fromSlugOrId: String,
    toSlugOrId: String,
    relation: String = DEFAULT_RELATION,
  ): Boolean {
    val from = resolve(fromSlugOrId) ?: return false
    val to = resolve(toSlugOrId) ?: return false
    if (from.id == to.id) return false
    linkDao.insert(
      WikiLinkEntity(
        fromPageId = from.id,
        toPageId = to.id,
        relation = normalizeTag(relation).ifBlank { DEFAULT_RELATION },
      ),
    )
    return true
  }

  suspend fun unlink(
    fromSlugOrId: String,
    toSlugOrId: String,
    relation: String = DEFAULT_RELATION,
  ): Boolean {
    val from = resolve(fromSlugOrId) ?: return false
    val to = resolve(toSlugOrId) ?: return false
    linkDao.delete(from.id, to.id, normalizeTag(relation).ifBlank { DEFAULT_RELATION })
    return true
  }

  suspend fun deletePage(slugOrId: String): Boolean {
    val entity = resolve(slugOrId) ?: return false
    pageDao.delete(entity.id) // cascade removes tags + links
    return true
  }

  suspend fun allTags(): List<String> = tagDao.getAllTags()

  private suspend fun neighborsOf(pageId: String): List<WikiNeighbor> {
    val outgoing = linkDao.getOutgoing(pageId)
    val incoming = linkDao.getIncoming(pageId)
    val ids = (outgoing.map { it.toPageId } + incoming.map { it.fromPageId }).distinct()
    if (ids.isEmpty()) return emptyList()
    val byId = pageDao.getByIds(ids).associateBy { it.id }
    return outgoing.mapNotNull { link ->
      byId[link.toPageId]?.let {
        WikiNeighbor(it.id, it.slug, it.title, link.relation, LinkDirection.OUTGOING)
      }
    } + incoming.mapNotNull { link ->
      byId[link.fromPageId]?.let {
        WikiNeighbor(it.id, it.slug, it.title, link.relation, LinkDirection.INCOMING)
      }
    }
  }

  private suspend fun resolve(slugOrId: String): WikiPageEntity? {
    val key = slugOrId.trim()
    return pageDao.getBySlug(normalizeSlug(key)) ?: pageDao.getById(key)
  }

  private fun WikiPageEntity.toDomain(tags: List<String>) = WikiPage(
    id = id,
    slug = slug,
    title = title,
    content = content,
    tags = tags,
    createdAt = createdAt,
    updatedAt = updatedAt,
  )

  companion object {
    const val DEFAULT_RELATION = "related"
    const val DEFAULT_SEARCH_LIMIT = 8

    /** Lowercase, collapse non-alphanumerics (Unicode-aware, so Cyrillic survives) to hyphens. */
    fun normalizeSlug(raw: String): String =
      raw.trim().lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
        .trim('-')
        .ifBlank { "page" }

    /** Tags/relations are single tokens: lowercase, internal whitespace collapsed to one hyphen. */
    fun normalizeTag(raw: String): String =
      raw.trim().lowercase().replace(Regex("\\s+"), "-")

    private fun escapeLike(query: String): String =
      query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
  }
}
