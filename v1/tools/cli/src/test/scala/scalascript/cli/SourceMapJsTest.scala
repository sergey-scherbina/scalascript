package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

/** v2.0 Phase 4 — tests for `ssc link --backend js --source-map`.
 *
 *  Verifies the source-map V3 writer produces a structurally valid map
 *  alongside `out.js`:
 *
 *   1. `link --backend js --source-map a/ b/` emits BOTH `out.js` and
 *      `out.js.map`.
 *   2. `out.js.map` parses as JSON, declares `version: 3`, lists both
 *      `.ssc` paths in its `sources` array, and carries a non-empty
 *      VLQ-encoded `mappings` string.
 *   3. The last non-blank line of `out.js` is `//# sourceMappingURL=out.js.map`.
 *   4. The decoded mapping for at least one generated line in the user-code
 *      region points back into a `.ssc` source (sourceIdx >= 0).
 *
 *  Tests spawn `ssc.jar` as a subprocess and cancel when the jar is
 *  missing (run `sbt cli/assembly` first).
 *
 *  Run with: `sbt "cli/testOnly *SourceMapJs*"`
 */
class SourceMapJsTest extends AnyFunSuite:

  // ── Test scaffolding (mirrors JsRuntimeSeparationTest) ──────────────────

  private val sscJar: Option[os.Path] =
    val cwd = os.pwd
    def jarUnder(root: os.Path): os.Path =
      root / "cli" / "target" / "scala-3.8.3" / "ssc.jar"
    def findCanonicalRepo(p: os.Path): Option[os.Path] =
      val parts = p.segments.toList
      val idx = parts.lastIndexOf(".claude")
      if idx >= 0 && idx + 1 < parts.length && parts(idx + 1) == "worktrees" then
        Some(os.Path("/" + parts.take(idx).mkString("/")))
      else None
    val candidates = List(
      jarUnder(cwd),
      jarUnder(cwd / os.up)
    ) ++ findCanonicalRepo(cwd).map(jarUnder).toList
    candidates.find(os.exists)

  private def requireJar(): os.Path = sscJar.getOrElse:
    cancel("ssc.jar not found — run `sbt cli/assembly` first")

  private def runSsc(cwd: os.Path, args: String*): os.CommandResult =
    val jar = requireJar()
    val cmd: Seq[os.Shellable] = Seq[os.Shellable]("java", "-jar", jar.toString) ++
      args.map(a => a: os.Shellable)
    os.proc(cmd).call(cwd = cwd, stdin = "", check = false, stderr = os.Pipe, stdout = os.Pipe)

  // ── Fixtures ────────────────────────────────────────────────────────────

  private val aSsc: String =
    """---
      |name: a
      |---
      |
      |# Module A
      |
      |```scalascript
      |def add(x: Int, y: Int): Int = x + y
      |println("a.add(2, 3) = " + add(2, 3))
      |```
      |""".stripMargin

  private val bSsc: String =
    """---
      |name: b
      |---
      |
      |# Module B
      |
      |```scalascript
      |def sub(x: Int, y: Int): Int = x - y
      |println("b.sub(5, 2) = " + sub(5, 2))
      |```
      |""".stripMargin

  // ── VLQ decoder (in-test copy; we don't expose the encoder publicly). ──

  private val Base64Alphabet: String =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZ" +
    "abcdefghijklmnopqrstuvwxyz" +
    "0123456789+/"

  /** Decode a Base64-VLQ stream into a list of signed integers — used to
   *  verify the `mappings` field actually decodes cleanly. */
  private def decodeVlqList(s: String): List[Int] =
    val out = scala.collection.mutable.ListBuffer.empty[Int]
    var i = 0
    while i < s.length do
      var result: Long = 0L
      var shift: Int   = 0
      var more         = true
      while more do
        val ch = s.charAt(i)
        val digit = Base64Alphabet.indexOf(ch.toInt)
        if digit < 0 then sys.error(s"non-base64 char in VLQ: $ch")
        more   = (digit & 0x20) != 0
        result |= ((digit & 0x1f).toLong) << shift
        shift  += 5
        i      += 1
      // Sign: low bit.  Recover absolute value, then negate if needed.
      val negate = (result & 1L) != 0L
      val abs    = (result >>> 1).toInt
      out += (if negate then -abs else abs)
    out.toList

  /** Decode one line's worth of segments. */
  private def decodeLineSegments(line: String): List[List[Int]] =
    if line.isEmpty then Nil
    else line.split(",", -1).toList.map(decodeVlqList)

  // ── Tests ───────────────────────────────────────────────────────────────

  test("link --backend js --source-map emits out.js + out.js.map with version 3, sources, mappings"):
    val sandbox = os.temp.dir(prefix = "ssc-srcmap-js-")
    try
      val artDir = sandbox / "artifacts"
      os.makeDir.all(artDir)
      os.write(sandbox / "a.ssc", aSsc)
      os.write(sandbox / "b.ssc", bSsc)

      val ra = runSsc(sandbox, "compile-js", "a.ssc", "-o", "artifacts/a.scjs")
      assert(ra.exitCode == 0,
        s"compile-js a failed:\nstdout=${ra.out.text()}\nstderr=${ra.err.text()}")
      val rb = runSsc(sandbox, "compile-js", "b.ssc", "-o", "artifacts/b.scjs")
      assert(rb.exitCode == 0,
        s"compile-js b failed:\nstdout=${rb.out.text()}\nstderr=${rb.err.text()}")

      // Drop the .ssc files into the artifact dir too so the linker can
      // resolve their paths for the `sources` array.  The link path
      // already searches both sibling and parent locations; copying here
      // keeps the test deterministic regardless of which one wins.
      os.copy(sandbox / "a.ssc", artDir / "a.ssc")
      os.copy(sandbox / "b.ssc", artDir / "b.ssc")

      val outJs = sandbox / "out.js"
      val rl = runSsc(sandbox, "link", "--backend", "js",
                      "--source-map", "artifacts",
                      "-o", outJs.toString)
      assert(rl.exitCode == 0,
        s"link --source-map failed:\nstdout=${rl.out.text()}\nstderr=${rl.err.text()}")

      val outMap = sandbox / "out.js.map"
      assert(os.exists(outJs),  s"expected $outJs")
      assert(os.exists(outMap), s"expected $outMap")

      // 1. JSON shape.
      val json = ujson.read(os.read(outMap))
      assert(json("version").num == 3.0,
        s"expected version: 3 in source map; got: ${json("version")}")

      val sources = json("sources").arr.map(_.str).toList
      assert(sources.exists(_.endsWith("a.ssc")),
        s"expected an a.ssc entry in sources; got: $sources")
      assert(sources.exists(_.endsWith("b.ssc")),
        s"expected a b.ssc entry in sources; got: $sources")

      val mappings = json("mappings").str
      assert(mappings.nonEmpty,
        s"expected non-empty mappings VLQ string")
      // Mappings must contain at least one `;` (we emit one VLQ field
      // per generated line of `out.js`, so the count of `;` separators
      // matches the line count minus 1; a single line would be empty).
      assert(mappings.contains(';'),
        s"expected line separators in mappings; got: ${mappings.take(80)}")

      // 2. Last non-blank line of out.js is the sourceMappingURL pragma.
      val outLines = os.read(outJs).linesIterator.toList
      val lastNonBlank = outLines.reverse.dropWhile(_.trim.isEmpty).headOption.getOrElse("")
      assert(lastNonBlank == "//# sourceMappingURL=out.js.map",
        s"expected last non-blank line to be sourceMappingURL pragma; got:\n$lastNonBlank")

      // 3. The mappings field decodes cleanly with deltas that reference
      //    each source in `sources`.  We walk every line; whenever a
      //    line carries a non-empty segment, we update the running
      //    `currentSourceIdx` per V3 spec.  At least one line must end
      //    up pointing at a known source.
      val lineFields = mappings.split(";", -1).toList
      var srcIdx = 0
      var anyMapped = false
      var idx = 0
      while idx < lineFields.length do
        val field = lineFields(idx)
        if field.nonEmpty then
          val segs = decodeLineSegments(field)
          // First segment of any line should have at least 4 fields
          // (genCol, srcIdxDelta, srcLine, srcCol) — we don't emit
          // 1-field "unmapped column" segments.
          val first = segs.head
          assert(first.length >= 4,
            s"segment at gen line $idx: expected >=4 VLQ fields, got ${first.length}")
          srcIdx += first(1)
          assert(srcIdx >= 0 && srcIdx < sources.length,
            s"decoded sourceIdx $srcIdx out of bounds for ${sources.length} sources")
          anyMapped = true
        idx += 1
      assert(anyMapped, "expected at least one mapped generated line in the user-code region")

      // 4. Without --source-map the .map file must NOT be produced.
      val outJs2  = sandbox / "out2.js"
      val outMap2 = sandbox / "out2.js.map"
      val rNoMap  = runSsc(sandbox, "link", "--backend", "js",
                           "artifacts", "-o", outJs2.toString)
      assert(rNoMap.exitCode == 0,
        s"link without --source-map failed:\nstderr=${rNoMap.err.text()}")
      assert(os.exists(outJs2))
      assert(!os.exists(outMap2),
        "out2.js.map should NOT exist without --source-map")
      val out2LastNonBlank = os.read(outJs2).linesIterator.toList
        .reverse.dropWhile(_.trim.isEmpty).headOption.getOrElse("")
      assert(out2LastNonBlank != "//# sourceMappingURL=out2.js.map",
        "without --source-map there must be no sourceMappingURL pragma")
    finally os.remove.all(sandbox)

  test("SourceMapV3 encodes VLQ values per the V3 spec"):
    // Spot-check known encodings (cross-referenced against
    // https://sourcemaps.info/spec.html worked examples and the
    // `source-map` npm package's tests).
    //   0  → "A"
    //   1  → "C"
    //  -1  → "D"
    //   2  → "E"
    //  16  → "gB"
    //  -16 → "hB"
    //   32 → "gC"
    assert(SourceMapV3.encodeVlq(0)   == "A",  SourceMapV3.encodeVlq(0))
    assert(SourceMapV3.encodeVlq(1)   == "C",  SourceMapV3.encodeVlq(1))
    assert(SourceMapV3.encodeVlq(-1)  == "D",  SourceMapV3.encodeVlq(-1))
    assert(SourceMapV3.encodeVlq(2)   == "E",  SourceMapV3.encodeVlq(2))
    assert(SourceMapV3.encodeVlq(16)  == "gB", SourceMapV3.encodeVlq(16))
    assert(SourceMapV3.encodeVlq(-16) == "hB", SourceMapV3.encodeVlq(-16))
    assert(SourceMapV3.encodeVlq(32)  == "gC", SourceMapV3.encodeVlq(32))

    // Round-trip: encode then decode reproduces input.
    val cases = List(0, 1, -1, 2, -2, 7, -7, 16, -16, 32, -32, 1024, -1024, 65535)
    for v <- cases do
      val enc = SourceMapV3.encodeVlq(v)
      val dec = decodeVlqList(enc)
      assert(dec == List(v), s"VLQ round-trip failed for $v: encode=$enc, decode=$dec")

  test("SourceMapV3.Builder emits version-3 JSON with delta-encoded segments"):
    // Direct test of the builder API — no CLI subprocess, just verifies
    // the in-memory pipeline.
    val b = new SourceMapV3.Builder("out.js")
    // 3 unmapped runtime lines, then 2 user-code lines from a.ssc, then
    // 1 unmapped wrapper line, then 1 user-code line from b.ssc.
    b.addUnmappedLines(3)
    b.addMappedLine("a.ssc", 0)
    b.addMappedLine("a.ssc", 1)
    b.addUnmappedLines(1)
    b.addMappedLine("b.ssc", 5)
    val json = ujson.read(b.toJson())
    assert(json("version").num == 3.0)
    assert(json("sources").arr.map(_.str).toList == List("a.ssc", "b.ssc"))
    assert(json("file").str == "out.js")
    val mappings = json("mappings").str
    // 7 generated lines → 6 ';' separators in the mappings.
    assert(mappings.count(_ == ';') == 6,
      s"expected 6 line separators, got ${mappings.count(_ == ';')} in '$mappings'")
    // Decode and verify per-line state.  Empty fields are unmapped lines.
    val fields = mappings.split(";", -1).toList
    assert(fields.size == 7, s"expected 7 fields, got ${fields.size}")
    // Lines 0..2 (runtime) are unmapped.
    assert(fields(0).isEmpty && fields(1).isEmpty && fields(2).isEmpty,
      s"expected runtime lines unmapped; got: $fields")
    // Line 3 maps to a.ssc line 0:
    //   genCol=0 → delta 0 → "A"
    //   srcIdx=0 → delta 0 → "A"
    //   srcLine=0 → delta 0 → "A"
    //   srcCol=0 → delta 0 → "A"
    assert(fields(3) == "AAAA",
      s"expected first mapping AAAA, got ${fields(3)}")
    // Line 4 maps to a.ssc line 1:
    //   genCol=0 (line restart, no delta from prev within this line) → "A"
    //   srcIdx delta 0 → "A"
    //   srcLine delta 1 → "C"
    //   srcCol delta 0 → "A"
    assert(fields(4) == "AACA",
      s"expected second mapping AACA, got ${fields(4)}")
    // Line 5 (unmapped wrapper).
    assert(fields(5).isEmpty)
    // Line 6 maps to b.ssc line 5:
    //   genCol=0 → "A"
    //   srcIdx delta +1 (was 0, now 1) → "C"
    //   srcLine delta +4 (was 1, now 5) → "I"  (5 << 1 = 10 = 0xA → "K"? recompute)
    // Let's recompute manually instead of hardcoding the long form —
    // just round-trip-decode and check the recovered tuple.
    val line6  = decodeLineSegments(fields(6))
    val seg6   = line6.head
    assert(seg6.size == 4, s"expected 4 fields in seg6, got ${seg6.size}: $seg6")
    // Running totals after line 4: sourceIdx=0, srcLine=1.
    // Expected seg6 deltas: genCol=0, srcIdxDelta=+1, srcLineDelta=+4, srcColDelta=0.
    assert(seg6(0) == 0, s"genCol delta should be 0; got ${seg6(0)}")
    assert(seg6(1) == 1, s"srcIdx delta should be +1; got ${seg6(1)}")
    assert(seg6(2) == 4, s"srcLine delta should be +4 (5-1); got ${seg6(2)}")
    assert(seg6(3) == 0, s"srcCol delta should be 0; got ${seg6(3)}")
