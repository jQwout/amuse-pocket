package com.aiopenpocket.agent.tools

// @trace photo-outline-tool-8dec5b
// @trace mcp-tools-f77d9b

import android.graphics.BitmapFactory
import com.aiopenpocket.agent.core.lesson.buildSegmentResultCardSpec
import com.aiopenpocket.agent.core.llm.SegmentationClient
import com.aiopenpocket.agent.core.llm.SegmentationConcept
import com.aiopenpocket.agent.core.llm.SegmentationFailure
import com.aiopenpocket.agent.core.llm.SegmentationResult
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.mcp.missingArg
import com.aiopenpocket.agent.core.mcp.string
import com.aiopenpocket.agent.core.mcp.toolSchema
import com.aiopenpocket.agent.loop.OUTLINE_PHOTO_TOOL
import com.aiopenpocket.agent.loop.TurnContextHolder
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.io.File

/**
 * "Outline on photo": SAM 3.1 draws a box round every match of one named thing in the photo the person
 * attached in this chat, and the person sees the photo with the boxes as a card.
 *
 * SAM does not say what is in a picture: it takes ONE English noun phrase and finds every such thing. So
 * the agent does the looking (Muse Spark sees the attached photo) and SAM does the pointing — "here is
 * every stain" — which is what an agent that acts on a photo (a cleaning plan, a checklist, a reminder)
 * needs to show the person. Until now SAM lived only in the "How the model sees" lesson.
 *
 * The photo is the last one attached in this conversation. It is already the upright, downscaled JPEG SAM
 * wants (ChatImages stores it through prepareSegmentPhoto), so it is sent as is, and the card draws the
 * boxes on that same file.
 *
 * Risk: READ, like web_search. It changes nothing on the device; the photo goes to Meta, where the chat
 * model already saw it, and a call costs $0.0025 (recorded by [SegmentationClient]).
 *
 * The result is the card spec itself: the chat shows it as a card ([com.aiopenpocket.agent.loop.isCardResult])
 * and the model reads the same JSON — how many were found and where.
 */
@Inject
@SingleIn(AppScope::class)
class PhotoOutlineTool(
  private val client: SegmentationClient,
  private val conversations: ConversationRepository,
  private val turnContextHolder: TurnContextHolder,
) : Tool {

  override val name = NAME

  override val description =
    "Outline things on the photo the person attached in this chat: draws a box around every match of ONE thing " +
      "you name and shows the person the photo with the boxes. Look at the photo yourself first, then call this " +
      "once per kind of thing you want to point at (\"stain\", \"sneaker\", \"dust\"). The name must be a short " +
      "English noun phrase, no lists or commands. Each call costs a quarter of a cent. The result says how many " +
      "were found (total) and where (pixel boxes on an image_w x image_h picture); zero found is a normal answer, " +
      "try a plainer word once at most. The person already sees the boxes, so do not repeat the coordinates."

  override val schema = toolSchema(name, description) {
    string("what", "The ONE thing to outline, as a short English noun phrase: \"stain\", \"red sneaker\".", required = true)
  }

  /** Slightly above the client's own request timeout, so a slow answer ends with the client's clear text. */
  override val timeoutMs: Long = SegmentationClient.REQUEST_TIMEOUT_MS + 5_000L

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    val raw = arguments.string("what") ?: return missingArg("what")
    val concept = SegmentationConcept.normalize(raw)
      ?: return ToolResult.Error(
        "\"$raw\" cannot be outlined: name ONE thing as a short English noun phrase (\"stain\"), no lists or commands.",
      )
    if (!client.isConfigured()) return ToolResult.Error(failureText(SegmentationFailure.NO_KEY), recoverable = false)
    val conversationId = turnContextHolder.conversationId
      ?: return ToolResult.Error("There is no chat to take a photo from.", recoverable = false)
    val photo = latestPhoto(conversationId)
      ?: return ToolResult.Error(
        "There is no photo in this chat. Ask the person to attach one with the photo button next to the message field.",
        recoverable = false,
      )
    val (width, height) = withContext(Dispatchers.IO) { imageSize(photo) }
    val bytes = withContext(Dispatchers.IO) { photo.readBytes() }
    if (width <= 0 || height <= 0) return ToolResult.Error("The attached photo cannot be read.", recoverable = false)
    return when (val result = client.segment(concept, bytes, MIME_TYPE)) {
      is SegmentationResult.Failed -> ToolResult.Error(failureText(result.reason))
      is SegmentationResult.Success -> ToolResult.Success(buildSegmentResultCardSpec(result, photo, width, height))
    }
  }

  /** The newest photo attached in [conversationId] that is still on disk, or null. */
  private suspend fun latestPhoto(conversationId: String): File? =
    conversations.getAllMessages(conversationId)
      .asReversed()
      .firstNotNullOfOrNull { message -> message.imagePath?.let(::File)?.takeIf { it.isFile } }

  companion object {
    const val NAME = OUTLINE_PHOTO_TOOL
    private const val MIME_TYPE = "image/jpeg"

    private fun imageSize(file: File): Pair<Int, Int> {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(file.absolutePath, bounds)
      return bounds.outWidth to bounds.outHeight
    }

    /** What the model is told when SAM did not answer: enough to tell the person what to do, in one sentence. */
    internal fun failureText(reason: SegmentationFailure): String = when (reason) {
      SegmentationFailure.NO_KEY, SegmentationFailure.UNAUTHORIZED ->
        "Outlining needs the person's Meta Model API key: they add or check it in Settings -> API key."
      SegmentationFailure.BILLING -> "The person's Meta account has no billing set up, and outlining is paid."
      SegmentationFailure.NO_MODEL_ACCESS -> "The person's Meta key has no access to the sam-3.1 model."
      SegmentationFailure.BAD_CONCEPT, SegmentationFailure.BAD_REQUEST ->
        "Meta refused this name. Try one plainer English noun."
      SegmentationFailure.IMAGE_TOO_LARGE -> "The photo is too large for the model. Ask for another one."
      SegmentationFailure.RATE_LIMITED, SegmentationFailure.SERVER, SegmentationFailure.NETWORK,
      SegmentationFailure.INCOMPLETE ->
        "Outlining did not finish (network or Meta is busy). Tell the person and offer to try again later."
      SegmentationFailure.BAD_RESPONSE, SegmentationFailure.REFUSED ->
        "The outlining model did not give a usable answer. Describe what you see instead."
    }
  }
}
