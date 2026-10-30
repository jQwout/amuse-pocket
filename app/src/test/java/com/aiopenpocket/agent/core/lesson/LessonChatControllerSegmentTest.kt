package com.aiopenpocket.agent.core.lesson

// @trace sam-segmentation-boxes-in-a-lesson-c3ea52

import android.app.Application
import android.net.Uri
import androidx.room.Room
import com.aiopenpocket.agent.core.llm.ImageGenClient
import com.aiopenpocket.agent.core.llm.NoOpLlmUsageRecorder
import com.aiopenpocket.agent.core.llm.SegmentBox
import com.aiopenpocket.agent.core.llm.SegmentationClient
import com.aiopenpocket.agent.core.llm.SegmentationFailure
import com.aiopenpocket.agent.core.llm.VideoUnderstandingClient
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.data.database.AppDatabase
import com.aiopenpocket.ui.chat.DrivenUiCard
import com.aiopenpocket.ui.chat.DrivenUiEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The "segment" lesson step: the card the step posts, and what the chat gets when SAM answers.
 *
 * What matters is the contract "1 step = exactly 1 assistant message" and the round trip: the card the controller
 * writes must parse back, in [DrivenUiEngine], into the same boxes SAM sent — in the picture's own grid, with the
 * inclusive far corner intact. The answer is a scripted SSE stream; nothing here talks to Meta.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LessonChatControllerSegmentTest {

  private val application = RuntimeEnvironment.getApplication() as Application
  private lateinit var db: AppDatabase
  private lateinit var repo: ConversationRepository

  @get:Rule
  val tmp = TemporaryFolder()

  @Before
  fun setUp() {
    db = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    repo = ConversationRepository(db.conversationDao(), db.conversationTagDao(), db.messageDao())
  }

  @After
  fun tearDown() {
    db.close()
  }

  private val record =
    "<0f>0<|box;x1=181;y1=365;x2=556;y2=486;w=1516;h=1600;c=0.96|><|mask;x=0;y=0;c=0.96;data=122,376,!!!!!abc|>"

  private fun data(json: String) = "data: $json\n\n"

  private fun answer(text: String): String =
    data("""{"type":"response.output_text.delta","delta":${JsonPrimitive(text)}}""") +
      data("""{"type":"response.completed","response":{"usage":{"images_processed":1}}}""")

  private fun controller(
    key: String = "meta-key",
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
  ): LessonChatController {
    // The other two clients must stay untouched: a segment step has nothing to ask the image or video clients.
    val unused = MockEngine { error("not this client") }
    return LessonChatController(
      repo,
      LessonImageGenerator(application, ImageGenClient(unused, { "k" }, NoOpLlmUsageRecorder)),
      VideoUnderstandingClient(unused, { "k" }, NoOpLlmUsageRecorder),
      SegmentationClient(MockEngine(handler), { key }, NoOpLlmUsageRecorder),
    )
  }

  private fun MockRequestHandleScope.sse(text: String) =
    respond(answer(text), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))

  private fun photo(width: Int = 1516, height: Int = 1600): PreparedPhoto {
    val file = tmp.newFile("segment-test.jpg").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
    return PreparedPhoto(file, byteArrayOf(1, 2, 3), width, height)
  }

  private suspend fun onlyParams(conversationId: String): JSONObject {
    val message = repo.getAllMessages(conversationId).single()
    return JSONObject(message.cardSpec!!).getJSONObject("params")
  }

  @Test
  fun `a segment step posts one card with the instruction, the default prompt and the sample`() = runBlocking {
    val id = repo.createConversation(title = "Урок", isTutorial = true)
    val step = LessonStep(type = "segment", instruction = "Назови вещь", segmentPrompt = "cup", sampleImage = "lessons/videos/sample_desk.webp")

    controller { error("no request for a step") }.postStep(id, Lesson("l", "Урок", steps = listOf(step)), 0)

    val message = repo.getAllMessages(id).single()
    assertEquals("lesson_segment", JSONObject(message.cardSpec!!).getString("template"))
    assertEquals(
      DrivenUiCard.LessonSegmentCard("Назови вещь", "cup", "lessons/videos/sample_desk.webp"),
      DrivenUiEngine.parseJsonString(message.cardSpec),
    )
  }

  @Test
  fun `the result card parses back into the boxes SAM sent`() = runBlocking {
    val id = repo.createConversation(title = "Урок", isTutorial = true)
    val photo = photo()

    val failure = controller { sse(record) }.postSegmentation(id, "cup", photo)

    assertNull(failure)
    val message = repo.getAllMessages(id).single()
    assertEquals("assistant", message.role)
    val card = DrivenUiEngine.parseJsonString(message.cardSpec!!) as DrivenUiCard.LessonSegmentResultCard
    assertEquals(Uri.fromFile(photo.file).toString(), card.imageUrl)
    assertEquals(1516, card.imageWidth)
    assertEquals(1600, card.imageHeight)
    assertEquals("cup", card.concept)
    assertEquals(1, card.total)
    // The inclusive far corner survives the round trip: x2/y2 stay x2/y2, not right/bottom.
    assertEquals(SegmentBox(181, 365, 556, 486, 1516, 1600, 0.96), card.boxes.single().box)
    assertEquals("0", card.boxes.single().id)
  }

  /** Masks are stage 2's business: a raw payload is tens of KB per object and must not bloat the message. */
  @Test
  fun `the stored card holds no mask payload`() = runBlocking {
    val id = repo.createConversation(title = "Урок", isTutorial = true)

    controller { sse(record) }.postSegmentation(id, "cup", photo())

    val spec = repo.getAllMessages(id).single().cardSpec!!
    assertFalse(spec, spec.contains("!!!!!abc"))
    assertFalse(spec, spec.contains("mask"))
  }

  /** Zero matches: not a failure. The card is posted, and says so. */
  @Test
  fun `no matches posts a card with no boxes`() = runBlocking {
    val id = repo.createConversation(title = "Урок", isTutorial = true)

    val failure = controller { sse("") }.postSegmentation(id, "unicorn", photo(640, 427))

    assertNull(failure)
    val params = onlyParams(id)
    assertEquals(0, params.getInt("total"))
    assertEquals(0, params.getJSONArray("boxes").length())
    assertEquals("unicorn", params.getString("concept"))
  }

  @Test
  fun `a failure returns its reason and posts nothing`() = runBlocking {
    val id = repo.createConversation(title = "Урок", isTutorial = true)
    val controller = controller { respond("", HttpStatusCode.PaymentRequired) }

    val failure = controller.postSegmentation(id, "cup", photo())

    assertEquals(SegmentationFailure.BILLING, failure)
    assertTrue("a failed call must not leave a message", repo.getAllMessages(id).isEmpty())
  }

  @Test
  fun `a crowd is capped at fifty frames and the total says how many there were`() = runBlocking {
    val id = repo.createConversation(title = "Урок", isTutorial = true)
    val crowd = (0 until 60).joinToString(",") { i ->
      "$i<|box;x1=$i;y1=0;x2=${i + 1};y2=5;w=200;h=100|><|mask;x=0;y=0;data=6,2,!!!!!ab|>"
    }

    controller { sse("<0f>$crowd") }.postSegmentation(id, "person", photo(200, 100))

    val params = onlyParams(id)
    assertEquals(60, params.getInt("total"))
    assertEquals(50, params.getJSONArray("boxes").length())
  }

  @Test
  fun `segmentation is ready only with a Meta key`() {
    assertTrue(controller(key = "meta-key") { error("unused") }.segmentationReady())
    assertFalse(controller(key = "") { error("unused") }.segmentationReady())
  }
}
