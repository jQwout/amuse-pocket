package com.aiopenpocket.agent.core.llm

// @trace sam-segmentation-boxes-in-a-lesson-c3ea52

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The text SAM 3.1 answers with: one line per frame, records with an id, one box and one mask.
 *
 * The strings are modelled on Meta's protocol text and on the example in the task brief; no live call produced
 * them. What the tests pin down is where a wrong parser would quietly draw frames in the wrong place: the
 * inclusive far corner, fields read by name, ids kept as they came, and "no match" kept apart from "not SAM's
 * grammar".
 */
class SegmentationParserTest {

  private val one = "<0f>0<|box;x1=181;y1=365;x2=556;y2=486;w=1516;h=1600|><|mask;x=0;y=0;data=122,376,~...|>"

  private fun parseOk(text: String): List<SegmentRecord> {
    val result = SegmentationParser.parse(text)
    assertTrue("unexpected diagnostics: ${result.diagnostics}", result.diagnostics.isEmpty())
    return result.records
  }

  @Test
  fun `reads the example line of the brief`() {
    val record = parseOk(one).single()

    assertEquals("0", record.id)
    assertEquals(SegmentBox(x1 = 181, y1 = 365, x2 = 556, y2 = 486, width = 1516, height = 1600), record.box)
    assertNull(record.box.confidence)
    // Kept raw: height, width, then the payload with its marker, untouched.
    assertEquals(122, record.mask.height)
    assertEquals(376, record.mask.width)
    assertEquals("~...", record.mask.payload)
    assertEquals(MaskEncoding.LOSSLESS, record.mask.encoding)
  }

  /** x2 and y2 are inclusive on the wire: `x1=0;x2=4` is five pixels, and drawing code works in half-open boxes. */
  @Test
  fun `far corner is inclusive`() {
    val record = parseOk("<0f>0<|box;x1=0;y1=0;x2=4;y2=4;w=20;h=10|><|mask;x=0;y=0;data=5,5,!!!!!(QO(0lu8?|>").single()

    assertEquals(0, record.box.left)
    assertEquals(5, record.box.right)
    assertEquals(5, record.box.bottom)
    assertEquals(MaskEncoding.ONE_BIT, record.mask.encoding)
    assertEquals("!!!!!(QO(0lu8?", record.mask.payload)
  }

  @Test
  fun `several records on one line, ids kept as they came`() {
    // Ids need not start at zero or be dense: "10" then "2", never renumbered by position.
    val line = "<0f>10<|box;x1=1;y1=1;x2=2;y2=2;w=50;h=40|><|mask;x=0;y=0;data=2,2,!!!!!aa|>" +
      ",2<|box;x1=5;y1=6;x2=9;y2=9;w=50;h=40|><|mask;x=0;y=0;data=4,5,!!!!!bb|>"

    val records = parseOk(line)

    assertEquals(listOf("10", "2"), records.map { it.id })
    assertEquals(listOf(1, 5), records.map { it.box.x1 })
  }

  @Test
  fun `the first object may start at any id, and the comma before it is optional`() {
    val records = parseOk("<0f>7<|box;x1=0;y1=0;x2=0;y2=0;w=1;h=1|><|mask;x=0;y=0;data=1,1,!!!!!z|>")
    assertEquals("7", records.single().id)
  }

  @Test
  fun `confidence is read per token and a missing one is null, not zero`() {
    val line = "<0f>0<|box;x1=10;y1=20;x2=14;y2=24;w=100;h=80;c=0.75|><|mask;x=0;y=0;c=0.5;data=5,5,!!!!!(QO(0lu8?|>" +
      ",1<|box;x1=30;y1=40;x2=49;y2=59;w=100;h=80|><|mask;x=0;y=0;data=20,20,!!!!!abc|>"

    val (first, second) = parseOk(line)

    assertEquals(0.75, first.box.confidence!!, 1e-9)
    // The box's and the mask's values are independent; nothing requires them to match.
    assertEquals(0.5, first.mask.confidence!!, 1e-9)
    assertNull(second.box.confidence)
    assertNull(second.mask.confidence)
  }

  /** The payload alphabet has `=` and letters, so a payload can hold `c=`. Fields are found by splitting, never by searching. */
  @Test
  fun `a c= inside the mask payload is not a confidence`() {
    val record = parseOk("<0f>0<|box;x1=0;y1=0;x2=3;y2=3;w=10;h=10|><|mask;x=0;y=0;data=4,4,!!!!!c=0.99ab|>").single()

    assertNull(record.box.confidence)
    assertNull(record.mask.confidence)
    assertEquals("!!!!!c=0.99ab", record.mask.payload)
  }

  @Test
  fun `fields are read by name, not by position`() {
    val record = parseOk("<0f>0<|box;h=80;w=100;y2=24;x2=14;y1=20;x1=10|><|mask;data=5,5,!!!!!ab;y=0;x=0|>").single()

    assertEquals(SegmentBox(10, 20, 14, 24, 100, 80), record.box)
    assertEquals("!!!!!ab", record.mask.payload)
  }

  @Test
  fun `a value that is not a valid confidence is dropped with a warning, the record stays`() {
    for (bad in listOf("c=1.5", "c=-0.2", "c=abc", "c=", "c", "c=NaN", "c=1e1")) {
      val result = SegmentationParser.parse("<0f>0<|box;x1=0;y1=0;x2=3;y2=3;w=10;h=10;$bad|><|mask;x=0;y=0;data=4,4,!!!!!ab|>")
      assertEquals("'$bad' must not drop the record", 1, result.records.size)
      assertNull("'$bad' must not become a confidence", result.records.single().box.confidence)
      assertEquals(listOf("ignored_confidence"), result.diagnostics.map { it.code })
    }
  }

  @Test
  fun `valid confidence spellings`() {
    for ((spelling, expected) in listOf("1" to 1.0, ".25" to 0.25, "7.5e-1" to 0.75, "1e-05" to 1e-5, "-0" to 0.0, "0" to 0.0)) {
      val record = parseOk("<0f>0<|box;x1=0;y1=0;x2=3;y2=3;w=10;h=10;c=$spelling|><|mask;x=0;y=0;data=4,4,!!!!!ab|>").single()
      assertEquals("c=$spelling", expected, record.box.confidence!!, 1e-12)
    }
  }

  @Test
  fun `a repeated confidence is ignored`() {
    val result = SegmentationParser.parse("<0f>0<|box;x1=0;y1=0;x2=3;y2=3;w=10;h=10;c=0.9;c=0.8|><|mask;x=0;y=0;data=4,4,!!!!!ab|>")

    assertNull(result.records.single().box.confidence)
    assertEquals(listOf("ignored_confidence"), result.diagnostics.map { it.code })
  }

  /** Zero matches is a valid, finished answer — an empty lane. */
  @Test
  fun `no text at all means no matches and no problems`() {
    for (empty in listOf("", "\n", "  \n \n")) {
      val result = SegmentationParser.parse(empty)
      assertTrue(result.records.isEmpty())
      assertTrue(result.diagnostics.isEmpty())
    }
  }

  @Test
  fun `prose around the record lines is ignored`() {
    val records = parseOk("Synthetic scene.\n$one\nDone.\n")
    assertEquals(1, records.size)
  }

  @Test
  fun `carriage returns do not matter`() {
    assertEquals(1, parseOk("$one\r\n").size)
  }

  @Test
  fun `a bare frame header is malformed, not nothing found`() {
    val result = SegmentationParser.parse("<0f>")

    assertTrue(result.records.isEmpty())
    assertEquals(listOf("malformed_record"), result.diagnostics.map { it.code })
    assertEquals(DiagnosticSeverity.ERROR, result.diagnostics.single().severity)
  }

  @Test
  fun `a frame other than zero is malformed in an image response`() {
    val result = SegmentationParser.parse("<3f>0<|box;x1=0;y1=0;x2=3;y2=3;w=10;h=10|><|mask;x=0;y=0;data=4,4,!!!!!ab|>")

    assertTrue(result.records.isEmpty())
    assertEquals("malformed_record", result.errors.single().code)
  }

  @Test
  fun `a box outside the picture is malformed`() {
    // x2=10 with w=10: the last pixel is x2 itself, so it has to be 9 at most.
    val outside = "<0f>0<|box;x1=0;y1=0;x2=10;y2=3;w=10;h=10|><|mask;x=0;y=0;data=4,4,!!!!!ab|>"
    assertEquals("malformed_record", SegmentationParser.parse(outside).errors.single().code)
    val negative = "<0f>0<|box;x1=-1;y1=0;x2=3;y2=3;w=10;h=10|><|mask;x=0;y=0;data=4,4,!!!!!ab|>"
    assertEquals("malformed_record", SegmentationParser.parse(negative).errors.single().code)
    val reversed = "<0f>0<|box;x1=5;y1=0;x2=3;y2=3;w=10;h=10|><|mask;x=0;y=0;data=4,4,!!!!!ab|>"
    assertEquals("malformed_record", SegmentationParser.parse(reversed).errors.single().code)
    // The last pixel (x2 = w - 1) is inside.
    val edge = "<0f>0<|box;x1=5;y1=0;x2=9;y2=9;w=10;h=10|><|mask;x=0;y=0;data=5,10,!!!!!ab|>"
    assertEquals(1, parseOk(edge).size)
  }

  @Test
  fun `a record needs exactly one box and one mask`() {
    val noMask = "<0f>0<|box;x1=0;y1=0;x2=3;y2=3;w=10;h=10|>"
    val twoBoxes = "<0f>0<|box;x1=0;y1=0;x2=3;y2=3;w=10;h=10|><|box;x1=0;y1=0;x2=3;y2=3;w=10;h=10|><|mask;x=0;y=0;data=4,4,!!!!!ab|>"
    val noBox = "<0f>0<|mask;x=0;y=0;data=4,4,!!!!!ab|>"
    for (line in listOf(noMask, twoBoxes, noBox)) {
      val result = SegmentationParser.parse(line)
      assertTrue(line, result.records.isEmpty())
      assertEquals(line, "malformed_record", result.errors.single().code)
    }
  }

  @Test
  fun `a missing or broken required field is malformed`() {
    val lines = listOf(
      "<0f>0<|box;x1=0;y1=0;x2=3;y2=3;w=10|><|mask;x=0;y=0;data=4,4,!!!!!ab|>", // no h
      "<0f>0<|box;x1=a;y1=0;x2=3;y2=3;w=10;h=10|><|mask;x=0;y=0;data=4,4,!!!!!ab|>", // not a number
      "<0f>0<|box;x1=+1;y1=0;x2=3;y2=3;w=10;h=10|><|mask;x=0;y=0;data=4,4,!!!!!ab|>", // a plus sign is not on the wire
      "<0f>0<|box;x1=0;y1=0;x2=3;y2=3;w=10;h=0|><|mask;x=0;y=0;data=4,4,!!!!!ab|>", // zero height
      "<0f>0<|box;x1=0;y1=0;x2=3;y2=3;w=10;h=10|><|mask;x=0;y=0|>", // no data
      "<0f>0<|box;x1=0;y1=0;x2=3;y2=3;w=10;h=10|><|mask;x=0;y=0;data=4,4,?abc|>", // no encoding marker
      "<0f>0<|box;x1=0;y1=0;x2=3;y2=3;w=10;h=10|><|mask;x=2;y=0;data=4,4,!!!!!ab|>", // mask not at the origin
      "<0f>0<|box;x1=0;y1=0;x2=3;y2=3;w=10;h=10;x1=1|><|mask;x=0;y=0;data=4,4,!!!!!ab|>", // repeated key
      "<0f>0 <|box;x1=0;y1=0;x2=3;y2=3;w=10;h=10|><|mask;x=0;y=0;data=4,4,!!!!!ab|>", // text between tokens
      "<0f><|box;x1=0;y1=0;x2=3;y2=3;w=10;h=10|><|mask;x=0;y=0;data=4,4,!!!!!ab|>", // no object id
      "<0f>0<|box;x1=0;y1=0;x2=3;y2=3;w=10;h=10|><|mask;x=0;y=0;data=4,4,!!!!!ab", // token not closed
    )
    for (line in lines) {
      val result = SegmentationParser.parse(line)
      assertTrue("should be malformed: $line", result.records.isEmpty())
      assertEquals("should be malformed: $line", listOf("malformed_record"), result.diagnostics.map { it.code })
    }
  }

  /** The grammar has no way to resynchronise inside a line: the broken segment and the rest of its line go, earlier ones stay. */
  @Test
  fun `segments accepted before a broken one are kept`() {
    val good = "<0f>0<|box;x1=0;y1=0;x2=3;y2=3;w=10;h=10|><|mask;x=0;y=0;data=4,4,!!!!!ab|>"
    val bad = ",1<|box;x1=0;y1=0;x2=30;y2=3;w=10;h=10|><|mask;x=0;y=0;data=4,4,!!!!!ab|>"
    val after = ",2<|box;x1=0;y1=0;x2=3;y2=3;w=10;h=10|><|mask;x=0;y=0;data=4,4,!!!!!ab|>"

    val result = SegmentationParser.parse(good + bad + after)

    assertEquals(listOf("0"), result.records.map { it.id })
    assertEquals(listOf("malformed_record"), result.diagnostics.map { it.code })
    assertEquals(1, result.diagnostics.single().line)
  }

  @Test
  fun `a malformed line does not stop the next line`() {
    val result = SegmentationParser.parse("<0f>\n$one")

    assertEquals(1, result.records.size)
    assertEquals(1, result.diagnostics.single().line)
  }

  @Test
  fun `unknown tokens and fields are warnings, once per name`() {
    val line = "<0f>0<|label;text=cup|><|box;x1=0;y1=0;x2=3;y2=3;w=10;h=10;z=1|><|mask;x=0;y=0;data=4,4,!!!!!ab|>" +
      ",1<|label;text=mug|><|box;x1=0;y1=0;x2=3;y2=3;w=10;h=10;z=2|><|mask;x=0;y=0;data=4,4,!!!!!ab|>"

    val result = SegmentationParser.parse(line)

    assertEquals(2, result.records.size)
    assertEquals(
      listOf("ignored_token", "ignored_field"),
      result.diagnostics.map { it.code },
    )
    assertTrue(result.diagnostics.all { it.severity == DiagnosticSeverity.WARNING })
  }

  @Test
  fun `an ignored field of the frame marker is a warning`() {
    val result = SegmentationParser.parse("<0f;example=1>0<|box;x1=0;y1=0;x2=3;y2=3;w=10;h=10|><|mask;x=0;y=0;data=4,4,!!!!!ab|>")

    assertEquals(1, result.records.size)
    assertEquals(listOf("ignored_field"), result.diagnostics.map { it.code })
  }

  /** A diagnostic echoes what it choked on, and that is the model's text: only the first few characters go into it. */
  @Test
  fun `a diagnostic does not echo a long value`() {
    val junk = "A woman in a red coat holds a child on a beach at dusk"
    val result = SegmentationParser.parse("<0f>0<|box;x1=$junk;y1=0;x2=3;y2=3;w=10;h=10|><|mask;x=0;y=0;data=4,4,!!!!!ab|>")

    val message = result.errors.single().message
    assertTrue(message, message.contains("A woman"))
    assertTrue("echoed more than it should: $message", !message.contains("child"))
  }

  @Test
  fun `whitespace around keys and values is trimmed and empty fields are skipped`() {
    val record = parseOk("<0f>0<|box; x1 = 0 ;y1=0;;x2=3;y2=3;w=10;h=10;|><|mask;x=0;y=0;data=4,4,!!!!!ab|>").single()

    assertEquals(SegmentBox(0, 0, 3, 3, 10, 10), record.box)
  }
}
