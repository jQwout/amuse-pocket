package com.aiopenpocket.agent.tools

import com.aiopenpocket.agent.core.llm.FunctionSchema
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.memory.model.LinkDirection
import com.aiopenpocket.agent.core.memory.repository.WikiRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import dev.zacsweers.metro.Inject
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.SingleIn

/**
 * Wiki-style long-term memory: pages with tags and links instead of flat facts.
 *
 * Actions: write_page (create/update), read_page (page + neighbors), search (by tag/text),
 * link_pages (associate two pages).
 */
@Inject
@SingleIn(AppScope::class)
class WikiTools(
  private val wikiRepository: WikiRepository,
) : Tool {

  override val name = "wiki_tool"
  override val description =
    "Долговременная память в виде вики: страницы-заметки с тегами и связями. " +
      "write_page (создать/обновить), read_page (страница + связи), search (по тегам/тексту), " +
      "link_pages (связать две страницы)."

  override val schema = FunctionSchema(
    function = FunctionSchema.FunctionDefinition(
      name = name,
      description = description,
      parameters = JsonObject(
        mapOf(
          "type" to JsonPrimitive("object"),
          "properties" to JsonObject(
            mapOf(
              "action" to JsonObject(
                mapOf(
                  "type" to JsonPrimitive("string"),
                  "enum" to JsonPrimitive("write_page,read_page,search,link_pages"),
                  "description" to JsonPrimitive("Действие: write_page, read_page, search, link_pages"),
                ),
              ),
              "slug" to JsonObject(
                mapOf(
                  "type" to JsonPrimitive("string"),
                  "description" to JsonPrimitive("Идентификатор страницы (write_page/read_page). Если пуст — выводится из title."),
                ),
              ),
              "title" to JsonObject(
                mapOf(
                  "type" to JsonPrimitive("string"),
                  "description" to JsonPrimitive("Заголовок страницы (write_page)"),
                ),
              ),
              "content" to JsonObject(
                mapOf(
                  "type" to JsonPrimitive("string"),
                  "description" to JsonPrimitive("Текст страницы (write_page)"),
                ),
              ),
              "tags" to JsonObject(
                mapOf(
                  "type" to JsonPrimitive("string"),
                  "description" to JsonPrimitive("Теги через запятую (write_page — задают набор; search — фильтр)"),
                ),
              ),
              "query" to JsonObject(
                mapOf(
                  "type" to JsonPrimitive("string"),
                  "description" to JsonPrimitive("Текстовый запрос (search)"),
                ),
              ),
              "from" to JsonObject(
                mapOf(
                  "type" to JsonPrimitive("string"),
                  "description" to JsonPrimitive("Slug страницы-источника связи (link_pages)"),
                ),
              ),
              "to" to JsonObject(
                mapOf(
                  "type" to JsonPrimitive("string"),
                  "description" to JsonPrimitive("Slug страницы-цели связи (link_pages)"),
                ),
              ),
              "relation" to JsonObject(
                mapOf(
                  "type" to JsonPrimitive("string"),
                  "description" to JsonPrimitive("Тип связи, напр. related/part_of/caused_by (link_pages)"),
                ),
              ),
            ),
          ),
          "required" to JsonArray(listOf(JsonPrimitive("action"))),
        ),
      ),
    ),
  )

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    val action = arguments.str("action") ?: return ToolResult.Error("Missing 'action'")
    return when (action) {
      "write_page" -> writePage(arguments)
      "read_page" -> readPage(arguments)
      "search" -> search(arguments)
      "link_pages" -> linkPages(arguments)
      else -> ToolResult.Error("Unknown action: $action")
    }
  }

  private suspend fun writePage(args: Map<String, JsonElement>): ToolResult {
    val title = args.str("title") ?: return ToolResult.Error("Missing 'title'")
    val content = args.str("content").orEmpty()
    val slug = args.str("slug")
    // tags absent => leave existing untouched; tags present (even empty) => replace the set.
    val tags = if (args.containsKey("tags")) parseTags(args.str("tags")) else null
    val page = wikiRepository.upsertPage(slug = slug, title = title, content = content, tags = tags)
    val tagSuffix = if (page.tags.isNotEmpty()) " [${page.tags.joinToString(", ")}]" else ""
    return ToolResult.Success("Page saved: ${page.slug} — ${page.title}$tagSuffix")
  }

  private suspend fun readPage(args: Map<String, JsonElement>): ToolResult {
    val key = args.str("slug") ?: return ToolResult.Error("Missing 'slug'")
    val view = wikiRepository.getPage(key) ?: return ToolResult.Success("No page found for '$key'")
    val out = buildString {
      appendLine("# ${view.page.title} (${view.page.slug})")
      if (view.page.tags.isNotEmpty()) appendLine("Теги: ${view.page.tags.joinToString(", ")}")
      appendLine()
      appendLine(view.page.content.ifBlank { "(пусто)" })
      if (view.neighbors.isNotEmpty()) {
        appendLine()
        appendLine("Связи:")
        view.neighbors.forEach { n ->
          val arrow = if (n.direction == LinkDirection.OUTGOING) "->" else "<-"
          appendLine("- $arrow [${n.relation}] ${n.slug} (${n.title})")
        }
      }
    }
    return ToolResult.Success(out.trim())
  }

  private suspend fun search(args: Map<String, JsonElement>): ToolResult {
    val query = args.str("query")
    val tags = parseTags(args.str("tags"))
    if (query.isNullOrBlank() && tags.isEmpty()) {
      return ToolResult.Error("Provide 'query' and/or 'tags'")
    }
    val pages = wikiRepository.search(query = query, tags = tags)
    return if (pages.isEmpty()) {
      ToolResult.Success("No pages found")
    } else {
      ToolResult.Success(
        pages.joinToString("\n") { p ->
          val t = if (p.tags.isNotEmpty()) " [${p.tags.joinToString(", ")}]" else ""
          "- ${p.slug}: ${p.title}$t"
        },
      )
    }
  }

  private suspend fun linkPages(args: Map<String, JsonElement>): ToolResult {
    val from = args.str("from") ?: return ToolResult.Error("Missing 'from'")
    val to = args.str("to") ?: return ToolResult.Error("Missing 'to'")
    val relation = args.str("relation") ?: WikiRepository.DEFAULT_RELATION
    val ok = wikiRepository.link(from, to, relation)
    return if (ok) {
      ToolResult.Success("Linked: $from -[$relation]-> $to")
    } else {
      ToolResult.Error("Could not link: one of the pages does not exist (or from == to)")
    }
  }

  private fun parseTags(raw: String?): List<String> =
    raw?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()

  private fun Map<String, JsonElement>.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
}
