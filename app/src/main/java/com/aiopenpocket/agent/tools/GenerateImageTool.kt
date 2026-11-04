package com.aiopenpocket.agent.tools

// @trace agents-draw-pictures-in-a-reply-generate-image-c2a961
// @trace mcp-tools-f77d9b

import android.app.Application
import android.net.Uri
import android.util.Log
import com.aiopenpocket.agent.core.llm.ImageGenClient
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.mcp.missingArg
import com.aiopenpocket.agent.core.mcp.string
import com.aiopenpocket.agent.core.mcp.toolSchema
import com.aiopenpocket.agent.loop.GENERATE_IMAGE_TOOL
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * "Draw a picture": Muse Image draws what the agent describes, and the picture appears in the reply as an
 * image card. Until now Muse Image drew only lesson illustrations; an agent asked to "show me how it would
 * look" had nothing to draw with and answered with a table.
 *
 * The file goes to `filesDir/generated_images` (not the cache: the system clears it, and the chat would
 * lose a picture already paid for). The result is the card spec itself, like [PhotoOutlineTool]: the chat
 * shows it ([com.aiopenpocket.agent.loop.isCardResult]) and the model reads that the picture is shown.
 *
 * Risk: READ, like web_search: it changes nothing on the device, its only effect is spending money
 * ($0.01 per picture, recorded by [ImageGenClient]). A built agent gets it only if its author picked it.
 */
@Inject
@SingleIn(AppScope::class)
class GenerateImageTool(
  private val application: Application,
  private val client: ImageGenClient,
) : Tool {

  override val name = NAME

  override val description =
    "Draw a picture with the Muse Image model and show it to the person in your reply. Use it when the person " +
      "asks to draw, picture, visualize or show how something would look. Write the prompt in English: what is " +
      "in the picture, the style, the setting; one picture per call, wide 16:9 frame. Avoid text in the picture, " +
      "the model draws letters badly. Each picture costs one cent and takes up to a minute. The person already " +
      "sees the picture, so do not describe it back at length."

  override val schema = toolSchema(name, description) {
    string("prompt", "What to draw, in English: subject, style, setting, colors.", required = true)
    string("caption", "A short caption under the picture, in the person's language.", required = false)
  }

  /** Muse Image reasons before drawing, and a busy answer is retried: well above the default tool ceiling. */
  override val timeoutMs: Long = 150_000L

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    val prompt = arguments.string("prompt")?.trim()?.takeIf { it.isNotEmpty() } ?: return missingArg("prompt")
    val caption = arguments.string("caption")?.trim().orEmpty()
    if (!client.isConfigured()) {
      return ToolResult.Error(
        "Drawing needs the person's Meta Model API key: they add it in Settings -> API key.",
        recoverable = false,
      )
    }
    val image = client.generate(prompt.take(MAX_PROMPT))
      ?: return ToolResult.Error(
        "The picture was not drawn (Meta refused the prompt, or the network or Meta is busy). " +
          "Tell the person, and offer to try again with a plainer description.",
      )
    val file = withContext(Dispatchers.IO) { save(image.bytes) }
      ?: return ToolResult.Error("The picture was drawn but could not be saved on the phone.", recoverable = false)
    return ToolResult.Success(cardSpec(Uri.fromFile(file).toString(), caption))
  }

  private fun save(bytes: ByteArray): File? = try {
    val dir = File(application.filesDir, DIR_NAME).apply { mkdirs() }
    File(dir, "${UUID.randomUUID()}$EXTENSION").apply { writeBytes(bytes) }
  } catch (e: IOException) {
    Log.w(TAG, "Failed to save a generated image: ${e.message}")
    null
  }

  companion object {
    const val NAME = GENERATE_IMAGE_TOOL
    private const val TAG = "GenerateImageTool"
    private const val DIR_NAME = "generated_images"

    // Nominal: Coil detects the type (webp/png/jpeg) from the content.
    private const val EXTENSION = ".img"

    /** A picture prompt is a paragraph, not a document; a runaway one only costs tokens. */
    private const val MAX_PROMPT = 2_000

    /** The same `image` card the lessons post: a file url and a caption. */
    internal fun cardSpec(url: String, caption: String): String = buildJsonObject {
      put("template", "image")
      putJsonObject("params") {
        put("asset", "")
        put("url", url)
        put("caption", caption)
      }
    }.toString()
  }
}
