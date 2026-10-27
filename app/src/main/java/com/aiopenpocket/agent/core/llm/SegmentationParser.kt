package com.aiopenpocket.agent.core.llm

// @trace sam-segmentation-boxes-in-a-lesson-c3ea52

/**
 * One object SAM 3.1 found in the photo: its id, its box and its mask.
 *
 * [id] is a string on purpose: the wire id is an ASCII number that "need not be contiguous, dense, or
 * start at zero" (Meta's protocol text), and nothing here may derive it from the record's position.
 */
data class SegmentRecord(
  val id: String,
  val box: SegmentBox,
  val mask: SegmentMask,
)

/**
 * A box as SAM writes it: pixels of the picture that was SENT (after any EXIF rotation, which is why the
 * app rotates the photo itself before sending, see SegmentPhoto), with an INCLUSIVE far corner. A box
 * `x1=0;x2=4` is five pixels wide — hence [right] and [bottom] are `x2 + 1` and `y2 + 1`: the half-open
 * form drawing code wants. [width] and [height] are the size of that picture; the model repeats them in
 * every record.
 */
data class SegmentBox(
  val x1: Int,
  val y1: Int,
  val x2: Int,
  val y2: Int,
  val width: Int,
  val height: Int,
  /** The model's confidence, 0..1, or `null` when the token carried none. A missing value is NOT zero. */
  val confidence: Double? = null,
) {
  val left: Int get() = x1
  val top: Int get() = y1
  val right: Int get() = x2 + 1
  val bottom: Int get() = y2 + 1
}

enum class MaskEncoding { LOSSLESS, ONE_BIT }

/**
 * The mask token, kept RAW. Stage 1 draws boxes only: the payload is base85 text behind an arithmetic
 * coder, and decoding it is a separate decision (a licence one). It is parsed and kept here so that
 * stage 2 does not have to touch the parser.
 *
 * [payload] is everything after `height,width,` including the encoding marker, exactly as received.
 */
data class SegmentMask(
  val height: Int,
  val width: Int,
  val payload: String,
  val confidence: Double? = null,
) {
  /** The marker character decides it: `~` lossless, `!` one_bit. Nothing is decoded. */
  val encoding: MaskEncoding get() = if (payload.startsWith("~")) MaskEncoding.LOSSLESS else MaskEncoding.ONE_BIT
}

enum class DiagnosticSeverity { WARNING, ERROR }

/**
 * Codes follow Meta's protocol text: `malformed_record` (an error — data was dropped) and
 * `ignored_field` / `ignored_token` / `ignored_confidence` (warnings — the record was kept).
 */
data class SegmentationDiagnostic(
  val severity: DiagnosticSeverity,
  val code: String,
  /** 1-based line of the response text. */
  val line: Int,
  val message: String,
)

data class SegmentationParse(
  val records: List<SegmentRecord>,
  val diagnostics: List<SegmentationDiagnostic>,
) {
  val errors: List<SegmentationDiagnostic> get() = diagnostics.filter { it.severity == DiagnosticSeverity.ERROR }
}

/**
 * Reads the text SAM 3.1 answers with. There is no JSON with masks: the Responses API returns ONE text
 * lane, a line per frame, and for a still picture that is one line:
 *
 * ```
 * <0f>0<|box;x1=181;y1=365;x2=556;y2=486;w=1516;h=1600|><|mask;x=0;y=0;data=122,376,~...|>
 * ```
 *
 * `<Nf>` is the frame (always 0 for a picture), then comma-separated records: an object id, exactly one
 * `box` token and exactly one `mask` token. Fields inside a token are read BY NAME, never by position,
 * and never by searching the token text for `key=`: the mask payload's alphabet contains `=` and
 * letters, so it can contain `c=` by itself.
 *
 * Rules implemented from Meta's protocol text (the grammar, not the mask codec):
 *  - A line that does not open with a frame marker is prose and is ignored; one that does and breaks the
 *    grammar (a bare `<0f>` included) yields a `malformed_record` error. A broken segment drops itself and
 *    the rest of ITS line — the grammar has no way to resynchronise — while segments accepted before it
 *    stay, as they would in a streaming parser.
 *  - Unknown tokens, unknown fields and a bad `c` are warnings, once per name for the whole text, and only
 *    from a record that was accepted.
 *  - Zero matches is a valid, empty answer: no lines at all.
 *
 * Pure Kotlin with no Android types, so plain JUnit covers it.
 */
object SegmentationParser {

  fun parse(text: String): SegmentationParse {
    val records = mutableListOf<SegmentRecord>()
    val diagnostics = mutableListOf<SegmentationDiagnostic>()
    val reported = mutableSetOf<String>()

    text.split('\n').forEachIndexed { index, raw ->
      val lineNumber = index + 1
      val line = raw.trim()
      if (line.isEmpty() || !FRAME_START.containsMatchIn(line)) return@forEachIndexed
      LineReader(line, lineNumber, records, diagnostics, reported).read()
    }
    return SegmentationParse(records, diagnostics)
  }

  private class Malformed(message: String) : Exception(message)

  /** [dedupKey] makes a warning once-per-text: one per field key, token name, or `c`. */
  private class Warning(val dedupKey: String, val code: String, val message: String)

  private class LineReader(
    private val line: String,
    private val lineNumber: Int,
    private val records: MutableList<SegmentRecord>,
    private val diagnostics: MutableList<SegmentationDiagnostic>,
    private val reported: MutableSet<String>,
  ) {
    private var pos = 0

    // The frame marker's warnings wait for the first accepted record of the line: a line whose records
    // are all malformed reports none, and a later line reports them instead.
    private val markerWarnings = mutableListOf<Warning>()

    fun read() {
      try {
        readFrameMarker()
        // A bare frame header carries no record: "<0f>" alone is malformed, not "nothing found".
        if (pos >= line.length) throw Malformed("a frame marker with no record after it")
        while (pos < line.length) {
          // A comma between segments is optional, including before the first one.
          if (line[pos] == ',') pos++
          readSegment()
        }
      } catch (e: Malformed) {
        diagnostics += SegmentationDiagnostic(
          DiagnosticSeverity.ERROR,
          "malformed_record",
          lineNumber,
          "${e.message} (at column ${pos + 1})",
        )
      }
    }

    private fun readFrameMarker() {
      // Guaranteed by FRAME_START: "<" digits "f".
      val close = line.indexOf('>')
      if (close < 0) throw Malformed("the frame marker is not closed")
      val fields = line.substring(1, close).split(';')
      val frame = FRAME_INDEX.matchEntire(fields[0])?.groupValues?.get(1)?.toLongOrNull()
        ?: throw Malformed("bad frame marker '${fields[0]}'")
      // Images only: a record of any other frame belongs to a video response.
      if (frame != 0L) throw Malformed("frame $frame in an image response, expected 0")
      fields.drop(1).forEach { f ->
        val key = f.substringBefore('=').trim()
        if (key.isNotEmpty()) {
          markerWarnings += Warning("ignored_field:frame:$key", "ignored_field", "ignored field '$key' of the frame marker")
        }
      }
      pos = close + 1
    }

    private fun readSegment() {
      val idStart = pos
      while (pos < line.length && line[pos] in '0'..'9') pos++
      if (pos == idStart) throw Malformed("expected an object id")
      val id = line.substring(idStart, pos)

      var box: SegmentBox? = null
      var mask: SegmentMask? = null
      val pending = mutableListOf<Warning>()
      var boxCount = 0
      var maskCount = 0

      while (pos < line.length && line[pos] != ',') {
        if (!line.startsWith("<|", pos)) throw Malformed("text between tokens")
        val end = line.indexOf("|>", pos + 2)
        if (end < 0) throw Malformed("a token is not closed")
        val body = line.substring(pos + 2, end)
        pos = end + 2

        val name = body.substringBefore(';')
        if (!TOKEN_NAME.matches(name)) throw Malformed("bad token name '$name'")
        val fields = readFields(body.substringAfter(';', ""))
        when (name) {
          "box" -> {
            boxCount++
            box = readBox(fields, pending)
          }
          "mask" -> {
            maskCount++
            mask = readMask(fields, pending)
          }
          else -> pending += Warning("ignored_token:$name", "ignored_token", "ignored token '$name'")
        }
      }
      if (boxCount != 1) throw Malformed("object $id has $boxCount box tokens, expected exactly one")
      if (maskCount != 1) throw Malformed("object $id has $maskCount mask tokens, expected exactly one")

      records += SegmentRecord(id, box!!, mask!!)
      // Warnings only from a record that was accepted: a malformed one reports none, a later one does.
      (markerWarnings + pending).forEach { warnOnce(it) }
      markerWarnings.clear()
    }

    /** `key=value` pairs; empty fields are skipped, a field without `=` has a key and no value. */
    private fun readFields(raw: String): List<Pair<String, String?>> =
      raw.split(';').mapNotNull { f ->
        val trimmed = f.trim()
        if (trimmed.isEmpty()) return@mapNotNull null
        val eq = trimmed.indexOf('=')
        if (eq < 0) trimmed to null else trimmed.substring(0, eq).trim() to trimmed.substring(eq + 1).trim()
      }

    private fun readBox(
      fields: List<Pair<String, String?>>,
      pending: MutableList<Warning>,
    ): SegmentBox {
      val values = uniqueFields("box", fields, BOX_KEYS, pending)
      val x1 = signedInt("box", "x1", values)
      val y1 = signedInt("box", "y1", values)
      val x2 = signedInt("box", "x2", values)
      val y2 = signedInt("box", "y2", values)
      val w = unsignedInt("box", "w", values)
      val h = unsignedInt("box", "h", values)
      if (w <= 0 || h <= 0) throw Malformed("box dimensions ${w}x$h are not positive")
      if (x1 > x2 || y1 > y2) throw Malformed("box corners are reversed: ($x1,$y1)-($x2,$y2)")
      // x2 and y2 are inclusive, so the last pixel is x2 itself: it must be < w.
      if (x1 < 0 || y1 < 0 || x2 >= w || y2 >= h) {
        throw Malformed("box ($x1,$y1)-($x2,$y2) is outside the ${w}x$h picture")
      }
      return SegmentBox(x1, y1, x2, y2, w, h, confidence("box", fields, pending))
    }

    private fun readMask(
      fields: List<Pair<String, String?>>,
      pending: MutableList<Warning>,
    ): SegmentMask {
      val values = uniqueFields("mask", fields, MASK_KEYS, pending)
      // Per the protocol the mask always starts at the picture's origin.
      if (signedInt("mask", "x", values) != 0 || signedInt("mask", "y", values) != 0) {
        throw Malformed("mask offset is not 0,0")
      }
      val data = values["data"] ?: throw Malformed("mask has no data")
      // height,width,payload — the payload alphabet excludes ',', so the first two commas split it.
      val first = data.indexOf(',')
      val second = if (first < 0) -1 else data.indexOf(',', first + 1)
      if (second < 0) throw Malformed("mask data is not 'height,width,payload'")
      val height = parseUnsigned(data.substring(0, first)) ?: throw Malformed("mask height is not a number")
      val width = parseUnsigned(data.substring(first + 1, second)) ?: throw Malformed("mask width is not a number")
      if (height <= 0 || width <= 0) throw Malformed("mask dimensions ${width}x$height are not positive")
      val payload = data.substring(second + 1)
      if (payload.isEmpty() || (payload[0] != '~' && payload[0] != '!')) {
        throw Malformed("mask payload has no '~' or '!' encoding marker")
      }
      if (payload.any { it in PAYLOAD_FORBIDDEN }) throw Malformed("mask payload holds a delimiter character")
      return SegmentMask(height, width, payload, confidence("mask", fields, pending))
    }

    /**
     * The named fields of a token. A repeated key other than `c` makes the record malformed; a key the
     * grammar does not define is a warning (once per key for the whole text).
     */
    private fun uniqueFields(
      token: String,
      fields: List<Pair<String, String?>>,
      known: Set<String>,
      pending: MutableList<Warning>,
    ): Map<String, String?> {
      val values = LinkedHashMap<String, String?>()
      for ((key, value) in fields) {
        if (key == "c") continue
        if (key in values) throw Malformed("$token token repeats '$key'")
        values[key] = value
        if (key !in known) {
          pending += Warning("ignored_field:$token:$key", "ignored_field", "ignored field '$key' of the $token token")
        }
      }
      return values
    }

    /**
     * `c` is optional and never fatal: a repeated one, a valueless one, a malformed one and one outside
     * 0..1 are all ignored with a warning, and the record is kept without that token's confidence.
     */
    private fun confidence(
      token: String,
      fields: List<Pair<String, String?>>,
      pending: MutableList<Warning>,
    ): Double? {
      val all = fields.filter { it.first == "c" }
      if (all.isEmpty()) return null
      val value = all.singleOrNull()?.second?.let(::parseConfidence)
      if (value == null) {
        pending += Warning("ignored_confidence", "ignored_confidence", "ignored confidence 'c' of the $token token")
      }
      return value
    }

    private fun signedInt(token: String, key: String, values: Map<String, String?>): Int {
      val raw = values[key] ?: throw Malformed("$token token has no '$key'")
      return parseSigned(raw) ?: throw Malformed("$token '$key' is not an integer: '$raw'")
    }

    private fun unsignedInt(token: String, key: String, values: Map<String, String?>): Int {
      val raw = values[key] ?: throw Malformed("$token token has no '$key'")
      return parseUnsigned(raw) ?: throw Malformed("$token '$key' is not an unsigned integer: '$raw'")
    }

    private fun warnOnce(warning: Warning) {
      if (reported.add(warning.dedupKey)) {
        diagnostics += SegmentationDiagnostic(DiagnosticSeverity.WARNING, warning.code, lineNumber, warning.message)
      }
    }
  }

  // Kotlin's toIntOrNull accepts a leading '+' and non-ASCII digits; the wire format allows neither.
  private val SIGNED = Regex("-?[0-9]+")
  private val UNSIGNED = Regex("[0-9]+")
  private val CONFIDENCE = Regex("-?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?")
  private val TOKEN_NAME = Regex("[A-Za-z][A-Za-z0-9_.-]*")
  private val FRAME_INDEX = Regex("([0-9]+)f")

  /** "<", digits, "f": what makes a line an API record rather than prose. */
  private val FRAME_START = Regex("^<[0-9]+f")

  private val BOX_KEYS = setOf("x1", "y1", "x2", "y2", "w", "h")
  private val MASK_KEYS = setOf("x", "y", "data")
  private const val PAYLOAD_FORBIDDEN = ",;<>|\"\\"

  private fun parseSigned(raw: String): Int? = if (SIGNED.matches(raw)) raw.toIntOrNull() else null

  private fun parseUnsigned(raw: String): Int? = if (UNSIGNED.matches(raw)) raw.toIntOrNull() else null

  /** A finite number from 0 through 1; `-0` becomes 0. Anything else is not a confidence. */
  private fun parseConfidence(raw: String): Double? {
    if (!CONFIDENCE.matches(raw)) return null
    val value = raw.toDoubleOrNull() ?: return null
    if (!value.isFinite() || value < 0.0 || value > 1.0) return null
    return if (value == 0.0) 0.0 else value
  }
}
