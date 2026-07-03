package scalascript.cli

/** Minimal Source Map V3 writer.
 *
 *  Implements just enough of the [V3 spec](https://sourcemaps.info/spec.html)
 *  to emit a line-granularity mapping JSON suitable for `out.js.map`:
 *
 *   - VLQ encoder (Base64-VLQ, 5-bit groups + continuation bit).
 *   - "Segment" model: each generated line carries zero or more segments;
 *     each segment is a 4-tuple `(genColumn, sourceIdx, srcLine, srcColumn)`
 *     where every field is delta-encoded against the previous segment
 *     (lines reset to 0 across line boundaries; everything else carries
 *     across the whole stream).
 *   - JSON serialiser (we hand-write the small JSON we need rather than
 *     pulling a dep — the keys and value shapes are fixed).
 *
 *  Not implemented (not needed for the MVP `link --source-map` MVP):
 *
 *   - `sourcesContent` (inline source bodies) — consumers can read the
 *     `.ssc` files directly from disk via the relative paths we emit.
 *   - `names` table — we have no symbol names to attach; the 5th VLQ field
 *     in a segment is omitted.
 *   - `sectioned` source maps (`{ "version": 3, "sections": [...] }`) —
 *     the `out.js` link output is one flat file, so the flat map shape is
 *     a clean fit.
 *
 *  Used by `linkJsFromScjs` when `--source-map` is requested: per-module
 *  generated line ranges are accumulated into a [[Builder]], one
 *  `Segment` per line, then serialised via [[Builder.toJson]] and written
 *  next to `out.js`.
 *
 *  v2.0 Phase 4 — JS source-map support for `ssc link --backend js
 *  --source-map`. */
object SourceMapV3:

  /** Base64 alphabet used by source-map VLQ encoding (RFC 4648 standard,
   *  reproduced here so we don't drag a dep just for one constant). */
  private val Base64Alphabet: Array[Char] =
    ("ABCDEFGHIJKLMNOPQRSTUVWXYZ" +
     "abcdefghijklmnopqrstuvwxyz" +
     "0123456789+/").toCharArray

  /** Encode a single signed integer as a Base64-VLQ.
   *
   *  Scheme: the value's absolute magnitude is shifted left by 1 and ORed
   *  with the sign bit (0 = positive/zero, 1 = negative).  Then the result
   *  is chunked into 5-bit groups, LSB-first; each group becomes one
   *  Base64 character where bit 6 (the "continuation" bit) is set on every
   *  group EXCEPT the last. */
  def encodeVlq(value: Int): String =
    var vuLong: Long =
      if value < 0 then ((-value.toLong) << 1) | 1L
      else (value.toLong << 1)
    val sb = new StringBuilder
    var more = true
    while more do
      var digit = (vuLong & 0x1f).toInt
      vuLong >>>= 5
      more = vuLong != 0
      if more then digit |= 0x20
      sb.append(Base64Alphabet(digit))
    sb.toString

  /** One mapping segment for a particular generated line.
   *
   *  @param genColumn  0-based column in the generated line (always 0 for
   *                    line-only mappings — we emit one segment per line).
   *  @param sourceIdx  Index into the source-map's `sources` array.
   *  @param srcLine    0-based source line.
   *  @param srcColumn  0-based source column (0 for line-only mappings). */
  final case class Segment(
      genColumn: Int,
      sourceIdx: Int,
      srcLine:   Int,
      srcColumn: Int
  )

  /** Accumulator that grows one entry per generated line.
   *
   *  Each entry is `Option[Segment]`: `Some(s)` means the line carries one
   *  mapping segment; `None` means the line has no mapping (it's emitted
   *  as an empty VLQ field, which sourcemap consumers display as
   *  "unmapped" — exactly what we want for the runtime preamble region
   *  that has no `.ssc` origin).
   *
   *  Callers:
   *   1. Call [[addUnmappedLines]] for every preamble / blank / no-source
   *      line emitted into `out.js`.
   *   2. Call [[addMappedLine]] for every user-code line that maps back
   *      to an originating `.ssc`.
   *   3. Call [[toJson]] when the whole `out.js` has been laid out.
   *
   *  The builder treats `sources` as an ordered registry: pass a relative
   *  path into [[registerSource]] (or [[addMappedLine]]) and it returns
   *  the stable index used in subsequent segments. */
  final class Builder(file: String):
    private val sourcesList = scala.collection.mutable.LinkedHashMap.empty[String, Int]
    private val lines       = scala.collection.mutable.ArrayBuffer.empty[Option[Segment]]

    /** Register a source path and return its index.  Calling twice with
     *  the same path is idempotent. */
    def registerSource(srcPath: String): Int =
      sourcesList.getOrElseUpdate(srcPath, sourcesList.size)

    /** Append `n` unmapped generated lines (e.g. the runtime preamble). */
    def addUnmappedLines(n: Int): Unit =
      var i = 0
      while i < n do
        lines += None
        i += 1

    /** Append one generated line that maps to (`srcPath`, `srcLine`).
     *
     *  @param srcPath  Filesystem path (typically relative to `out.js`) of
     *                  the originating `.ssc` source.
     *  @param srcLine  0-based source line (the spec is 0-based throughout). */
    def addMappedLine(srcPath: String, srcLine: Int): Unit =
      val idx = registerSource(srcPath)
      lines += Some(Segment(genColumn = 0, sourceIdx = idx, srcLine = srcLine, srcColumn = 0))

    /** Generated-line count accumulated so far. */
    def generatedLineCount: Int = lines.size

    /** Serialised list of source paths, in registration order. */
    def sources: List[String] = sourcesList.keys.toList

    /** Encode the accumulated lines into the V3 `mappings` field.
     *
     *  Between-line separator: `;`.  Within-line segments are
     *  comma-separated; we only emit one segment per line.  Segments are
     *  delta-encoded against the running state:
     *    - `genColumn` resets to 0 each line, then deltas within a line;
     *    - `sourceIdx`, `srcLine`, `srcColumn` carry across line breaks.
     */
    private def encodeMappings(): String =
      val sb = new StringBuilder
      var prevSourceIdx = 0
      var prevSrcLine   = 0
      var prevSrcColumn = 0
      var first = true
      for entry <- lines do
        if !first then sb.append(';')
        first = false
        entry match
          case None => ()
          case Some(seg) =>
            // genColumn always starts at 0 each new line, so the column
            // delta = seg.genColumn - 0 = seg.genColumn.
            sb.append(encodeVlq(seg.genColumn))
            sb.append(encodeVlq(seg.sourceIdx - prevSourceIdx))
            sb.append(encodeVlq(seg.srcLine - prevSrcLine))
            sb.append(encodeVlq(seg.srcColumn - prevSrcColumn))
            prevSourceIdx = seg.sourceIdx
            prevSrcLine   = seg.srcLine
            prevSrcColumn = seg.srcColumn
      sb.toString

    /** Render the source map as a JSON string.  Hand-written to avoid
     *  pulling a dep for one tiny document; the shape is fixed. */
    def toJson(): String =
      val mappings = encodeMappings()
      val sb = new StringBuilder
      sb.append("{\n")
      sb.append("  \"version\": 3,\n")
      sb.append("  \"file\": ").append(jsonStr(file)).append(",\n")
      sb.append("  \"sourceRoot\": \"\",\n")
      sb.append("  \"sources\": [")
      val srcs = sourcesList.keys.toList
      var i = 0
      while i < srcs.size do
        if i > 0 then sb.append(", ")
        sb.append(jsonStr(srcs(i)))
        i += 1
      sb.append("],\n")
      sb.append("  \"names\": [],\n")
      sb.append("  \"mappings\": ").append(jsonStr(mappings)).append("\n")
      sb.append("}\n")
      sb.toString

    /** Minimal JSON string escaper — enough for filesystem paths and the
     *  Base64 alphabet that appears in `mappings`. */
    private def jsonStr(s: String): String =
      val sb = new StringBuilder
      sb.append('"')
      var i = 0
      while i < s.length do
        val c = s.charAt(i)
        c match
          case '"'  => sb.append("\\\"")
          case '\\' => sb.append("\\\\")
          case '\n' => sb.append("\\n")
          case '\r' => sb.append("\\r")
          case '\t' => sb.append("\\t")
          case ch if ch < 0x20 =>
            sb.append("\\u%04x".format(ch.toInt))
          case ch   => sb.append(ch)
        i += 1
      sb.append('"')
      sb.toString
