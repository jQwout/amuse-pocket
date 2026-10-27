package com.aiopenpocket.ui.chat.components

// @trace sam-segmentation-boxes-in-a-lesson-c3ea52

import android.net.Uri
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.aiopenpocket.agent.core.llm.SegmentBox
import com.aiopenpocket.i18n.AppLanguage
import com.aiopenpocket.ui.chat.DrivenUiCard
import com.aiopenpocket.ui.chat.SegmentResultBox
import com.aiopenpocket.ui.theme.MyApplicationTheme
import com.aiopenpocket.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The two cards of the "segment" step on screen: the one that asks for a photo and the one that shows SAM's frames.
 *
 * The input card is the part a learner without a Meta key meets first, so it must say what the step needs and
 * must never lock the buttons for a word the model would accept. The result card is where the boxes are drawn:
 * the check is where a frame's label lands against the photo, not that something was drawn.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class LessonSegmentCardsTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Before
  fun english() {
    AppLanguage.code = AppLanguage.EN
  }

  @After
  fun resetLanguage() {
    AppLanguage.code = AppLanguage.RU
  }

  private val card = DrivenUiCard.LessonSegmentCard(
    instruction = "Name one thing.",
    prompt = "cup",
    sampleImage = "lessons/videos/sample_desk.webp",
  )

  private fun renderInput(
    card: DrivenUiCard.LessonSegmentCard = this.card,
    analyzing: Boolean = false,
    onPhoto: (String, Uri) -> Unit = { _, _ -> },
  ) {
    composeTestRule.setContent {
      MyApplicationTheme(mode = ThemeMode.LIGHT) {
        LessonSegmentCardView(card = card, analyzing = analyzing, onPhotoChosen = onPhoto)
      }
    }
  }

  private fun renderResult(card: DrivenUiCard.LessonSegmentResultCard) {
    composeTestRule.setContent {
      MyApplicationTheme(mode = ThemeMode.LIGHT) { LessonSegmentResultCardView(card) }
    }
  }

  // --- the input card ---

  /** A judge or a fresh clone has no Meta key: the card has to say what is needed and where to get it, up front. */
  @Test
  fun `the card says a Meta key is needed and where to get one`() {
    renderInput()

    composeTestRule.onNodeWithText("Needs a Meta Model API key", substring = true).assertExists()
    composeTestRule.onNodeWithText("Settings → API KEY", substring = true).assertExists()
    composeTestRule.onNodeWithText("you can skip the step", substring = true).assertExists()
    composeTestRule.onNodeWithText("dev.meta.ai", substring = true).assertExists()
    // The photo's destination is on the card too.
    composeTestRule.onNodeWithText("goes to Meta in full", substring = true).assertExists()
  }

  @Test
  fun `the example button sends the sample photo and the default prompt`() {
    var sent: Pair<String, Uri>? = null
    renderInput(onPhoto = { concept, photo -> sent = concept to photo })

    composeTestRule.onNodeWithText("Show an example").assertIsEnabled().performClick()

    val (concept, photo) = sent!!
    assertEquals("cup", concept)
    assertEquals(Uri.parse("file:///android_asset/lessons/videos/sample_desk.webp"), photo)
    // The step's own bundled photo is read from the APK's assets, which ChatViewModel opens by this path.
    assertEquals("lessons/videos/sample_desk.webp", photo.bundledAssetPath())
  }

  @Test
  fun `an edited prompt is what gets sent`() {
    var sent: String? = null
    renderInput(onPhoto = { concept, _ -> sent = concept })

    composeTestRule.onNode(hasSetTextAction()).performTextReplacement("laptop")
    composeTestRule.onNodeWithText("Show an example").performClick()

    assertEquals("laptop", sent)
  }

  /** SAM takes one noun phrase: a list or a command would cost a call and fail, so the buttons wait for a real word. */
  @Test
  fun `a list or an empty prompt locks the buttons`() {
    renderInput()

    for (bad in listOf("cup, plate", "", "   ")) {
      composeTestRule.onNode(hasSetTextAction()).performTextReplacement(bad)
      composeTestRule.onNodeWithText("Show an example").assertIsNotEnabled()
      composeTestRule.onNodeWithText("Take my own").assertIsNotEnabled()
      composeTestRule.onNodeWithText("From gallery").assertIsNotEnabled()
    }
    composeTestRule.onNode(hasSetTextAction()).performTextReplacement("yellow school bus")
    composeTestRule.onNodeWithText("Show an example").assertIsEnabled()
  }

  @Test
  fun `while SAM works the buttons give way to the progress line`() {
    renderInput(analyzing = true)

    composeTestRule.onNodeWithText("Looking in the photo…").assertExists()
    composeTestRule.onNodeWithText("Show an example").assertDoesNotExist()
    composeTestRule.onNodeWithText("From gallery").assertDoesNotExist()
  }

  @Test
  fun `a step without a sample photo offers the camera and the gallery`() {
    renderInput(card = card.copy(sampleImage = ""))

    composeTestRule.onNodeWithText("Take a photo").assertIsEnabled()
    composeTestRule.onNodeWithText("Choose from gallery").assertIsEnabled()
    composeTestRule.onNodeWithText("Show an example").assertDoesNotExist()
  }

  // --- the result card ---

  private fun resultCard(
    boxes: List<SegmentResultBox>,
    total: Int = boxes.size,
    concept: String = "cup",
  ) = DrivenUiCard.LessonSegmentResultCard(
    imageUrl = "file:///does/not/exist.jpg",
    imageWidth = 1000,
    imageHeight = 500,
    concept = concept,
    total = total,
    boxes = boxes,
  )

  @Test
  fun `each frame is labelled with the request and the confidence`() {
    renderResult(
      resultCard(
        listOf(
          SegmentResultBox("0", SegmentBox(100, 50, 299, 149, 1000, 500, 0.96)),
          SegmentResultBox("1", SegmentBox(600, 50, 799, 149, 1000, 500)),
        ),
      ),
    )

    composeTestRule.onNodeWithText("cup 96%").assertExists()
    // No confidence in the answer: the label is the request alone, not "cup 0%".
    composeTestRule.onNodeWithText("cup").assertExists()
    composeTestRule.onNodeWithText("Found “cup”: 2.").assertExists()
  }

  /** This is where the picture's coordinates meet the screen's: a frame must cover the same part of the photo as in the answer. */
  @Test
  fun `a frame lands on the same fraction of the photo as in the answer`() {
    renderResult(resultCard(listOf(SegmentResultBox("0", SegmentBox(500, 250, 749, 374, 1000, 500, 0.9)))))

    val photo = composeTestRule.onNodeWithContentDescription("Photo with the objects that were found").getBoundsInRoot()
    val frame = composeTestRule.onNodeWithTag("segment_frame_0").getBoundsInRoot()
    val label = composeTestRule.onNodeWithText("cup 90%").getBoundsInRoot()

    // x1 = 500 of 1000 is half the photo's width; y1 = 250 of 500 is half its height.
    assertEquals(photo.left.value + photo.width.value * 0.5f, frame.left.value, 0.2f)
    assertEquals(photo.top.value + photo.height.value * 0.5f, frame.top.value, 0.2f)
    // 500..749 inclusive is 250 pixels of 1000, and 250..374 inclusive is 125 of 500: a quarter each way. Without the
    // inclusive far corner the frame would be a pixel short, which at this width is about 0.4 dp.
    assertEquals(photo.width.value * 0.25f, frame.width.value, 0.2f)
    assertEquals(photo.height.value * 0.25f, frame.height.value, 0.2f)
    // The label sits on the frame's top-left corner, inside it.
    assertTrue("label ${label.left} vs frame ${frame.left}", label.left.value in frame.left.value..(frame.left.value + 8f))
    assertTrue("label ${label.top} vs frame ${frame.top}", label.top.value in frame.top.value..(frame.top.value + 4f))
    assertTrue("the photo keeps the sent aspect ratio", kotlin.math.abs(photo.width.value / photo.height.value - 2f) < 0.01f)
  }

  /** Zero matches is an answer: the card shows the photo and says nothing was found, in plain words. */
  @Test
  fun `no matches says so without calling it an error`() {
    renderResult(resultCard(emptyList(), concept = "unicorn"))

    composeTestRule.onNodeWithText("No “unicorn” found.", substring = true).assertExists()
    composeTestRule.onNodeWithText("normal answer", substring = true).assertExists()
  }

  @Test
  fun `a capped crowd says how many there were`() {
    renderResult(resultCard(listOf(SegmentResultBox("0", SegmentBox(0, 0, 9, 9, 1000, 500))), total = 60, concept = "person"))

    composeTestRule.onNodeWithText("Found “person”: 60. Showing the first 1.").assertExists()
  }

  /** The copy of the photo lives in the app's cache, which the system may clear: the frames must not depend on it. */
  @Test
  fun `the frames are drawn even when the photo cannot be loaded`() {
    renderResult(resultCard(listOf(SegmentResultBox("0", SegmentBox(0, 0, 9, 9, 1000, 500, 0.8)))))
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithText("cup 80%").assertExists()
  }
}
