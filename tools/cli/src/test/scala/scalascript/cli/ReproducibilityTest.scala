package scalascript.cli

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipInputStream

import org.scalatest.funsuite.AnyFunSuite

/** v2.0 Phase 3 follow-up — reproducibility tests.
 *
 *  Compiling the same `.ssc` twice in a row MUST produce byte-identical
 *  artifacts.  This matters for content-addressed caching, supply-chain
 *  attestations (SLSA-style provenance), and CI cross-checks where two
 *  builders independently produce the same output.
 *
 *  Sources of non-determinism this suite is designed to catch:
 *
 *   - ZIP entry timestamps (the JDK defaults to the current wall clock
 *     unless the caller sets `setTime(...)`).  Catch: `.scjvm`
 *     classBundle differs across runs.
 *   - ZIP entry order (collection iteration order).  Catch: same bytes,
 *     different ordering → different bytes when reassembled.
 *   - JSON field order (upickle is deterministic, but `Map` iteration
 *     is not — case-class-derived writers are fine, hand-written ones
 *     might not be).
 *   - Source-hash inputs that include absolute paths or wall-clock
 *     timestamps.  Catch: `sourceHash` changes across runs even when
 *     the source bytes do not.
 *
 *  What we DID fix in this pass:
 *
 *   - ZIP entry timestamps: `JvmBytecode.packAsZip` now pins every
 *     entry's mtime to 1980-01-01 instead of letting the JDK stamp
 *     wall-clock time.  Without the pin, the same source compiled
 *     2 s apart produced bundles whose ZIP local file headers
 *     differed at byte 10 ("last mod file time").
 *   - `-sourceroot` is now passed to the in-process Scala 3 driver
 *     ([[Scala3Driver]]) so the embedded SourceFile path in `.class`
 *     and `.tasty` is just the source's basename (`a_sc.scala`) and
 *     not the absolute path under `os.temp.dir` (whose suffix is
 *     randomised every invocation).  Before the fix, two runs of
 *     the same source produced TASTY files of DIFFERENT byte length
 *     (~1238 vs 1239 bytes) because the temp-dir path string length
 *     varied with the integer suffix.
 *
 *  What we exclude from byte-comparison and why:
 *
 *   - For `.scjvm --bytecode` we strip the `classBundle` JSON field
 *     before comparing the envelope, then re-open the bundle and
 *     compare every entry's bytes.  All entries (`<X>$.class`,
 *     `<X>.class`, `<X>.tasty`) are now byte-identical thanks to
 *     the two fixes above.
 *
 *  Run with: `sbt "cli/testOnly *ReproducibilityTest*"`
 */
class ReproducibilityTest extends AnyFunSuite:

  // ── ssc.jar discovery (mirrors other CLI tests) ─────────────────────────

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

  private def requireScalaCli(): Unit =
    val res = scala.util.Try {
      os.proc("scala-cli", "--version").call(check = false, stderr = os.Pipe, stdout = os.Pipe)
    }
    if res.isFailure || !res.toOption.exists(_.exitCode == 0) then
      cancel("`scala-cli` not on PATH — needed for compile-jvm --bytecode")

  private def runSsc(cwd: os.Path, args: String*): os.CommandResult =
    val jar = requireJar()
    val cmd: Seq[os.Shellable] = Seq[os.Shellable]("java", "-jar", jar.toString) ++
      args.map(a => a: os.Shellable)
    os.proc(cmd).call(cwd = cwd, stdin = "", check = false, stderr = os.Pipe, stdout = os.Pipe)

  // ── Fixture ─────────────────────────────────────────────────────────────

  /** A small but non-trivial module exercising a `def`, a literal print, and
   *  a type that the typer will look at.  Keep it self-contained so we don't
   *  need an iface dir. */
  private val aSsc: String =
    """---
      |name: a
      |---
      |
      |# Module A
      |
      |```scalascript
      |def add(x: Int, y: Int): Int = x + y
      |def stringify(n: Int): String = "n=" + n
      |println("a.add(2, 3) = " + add(2, 3))
      |println(stringify(42))
      |```
      |""".stripMargin

  // ── Helpers ─────────────────────────────────────────────────────────────

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256")
      .digest(bytes).map(b => f"$b%02x").mkString

  /** Strip the `"classBundle": "…"` field from a `.scjvm` JSON for
   *  comparison purposes.  `.class` files embed compilation-side metadata
   *  (TASTY UUIDs, etc.) we can't normalise without invasive
   *  post-processing — for now we just verify the envelope reproduces.
   *  When the bundle itself becomes reproducible we drop this. */
  private def stripClassBundle(json: String): String =
    val parsed = ujson.read(json).obj
    parsed.remove("classBundle")
    ujson.write(parsed, indent = 2)

  /** Decode the `classBundle` and return entry name → bytes for every entry. */
  private def bundleEntries(json: String): Map[String, Array[Byte]] =
    val parsed = ujson.read(json).obj
    parsed.get("classBundle").flatMap(_.strOpt) match
      case None        => Map.empty
      case Some(b64) if b64.isEmpty => Map.empty
      case Some(b64) =>
        val zis = new ZipInputStream(new ByteArrayInputStream(Base64.getDecoder.decode(b64)))
        try
          val out = scala.collection.mutable.LinkedHashMap.empty[String, Array[Byte]]
          var e = zis.getNextEntry
          val buf = new Array[Byte](8192)
          val baos = new java.io.ByteArrayOutputStream()
          while e != null do
            baos.reset()
            var n = zis.read(buf)
            while n > 0 do
              baos.write(buf, 0, n)
              n = zis.read(buf)
            out(e.getName) = baos.toByteArray
            zis.closeEntry()
            e = zis.getNextEntry
          out.toMap
        finally zis.close()

  /** SHA-256 of two runs' outputs; the test asserts they match.  When
   *  they don't, we print a diff-friendly delta to help diagnose. */
  private def assertBytewiseIdentical(label: String, b1: Array[Byte], b2: Array[Byte]): Unit =
    val h1 = sha256(b1)
    val h2 = sha256(b2)
    if h1 != h2 then
      // Find the first differing byte to give a hint.
      val len = math.min(b1.length, b2.length)
      val firstDiff = (0 until len).find(i => b1(i) != b2(i)).getOrElse(len)
      fail(
        s"$label not reproducible.\n" +
        s"  run1 sha256=$h1 (${b1.length} bytes)\n" +
        s"  run2 sha256=$h2 (${b2.length} bytes)\n" +
        s"  first differing byte at offset $firstDiff"
      )

  // ── 1. emit-interface is byte-identical across runs ─────────────────────

  test("emit-interface twice → identical .scim bytes"):
    val sandbox = os.temp.dir(prefix = "ssc-repro-")
    try
      os.write(sandbox / "a.ssc", aSsc)

      val out1 = sandbox / "run1.scim"
      val out2 = sandbox / "run2.scim"

      assert(runSsc(sandbox, "emit-interface", "a.ssc", "-o", out1.toString).exitCode == 0)
      Thread.sleep(1100)  // make sure any wall-clock-derived field has a chance to differ
      assert(runSsc(sandbox, "emit-interface", "a.ssc", "-o", out2.toString).exitCode == 0)

      assertBytewiseIdentical(".scim", os.read.bytes(out1), os.read.bytes(out2))
    finally os.remove.all(sandbox)

  // ── 2. emit-ir is byte-identical across runs ────────────────────────────

  test("emit-ir twice → identical .scir bytes"):
    val sandbox = os.temp.dir(prefix = "ssc-repro-")
    try
      os.write(sandbox / "a.ssc", aSsc)

      val out1 = sandbox / "run1.scir"
      val out2 = sandbox / "run2.scir"

      assert(runSsc(sandbox, "emit-ir", "a.ssc", "-o", out1.toString).exitCode == 0)
      Thread.sleep(1100)
      assert(runSsc(sandbox, "emit-ir", "a.ssc", "-o", out2.toString).exitCode == 0)

      assertBytewiseIdentical(".scir", os.read.bytes(out1), os.read.bytes(out2))
    finally os.remove.all(sandbox)

  // ── 3. compile-jvm (source-only) is byte-identical across runs ──────────

  test("compile-jvm (source-only, no --bytecode) twice → identical .scjvm bytes"):
    val sandbox = os.temp.dir(prefix = "ssc-repro-")
    try
      os.write(sandbox / "a.ssc", aSsc)

      val out1 = sandbox / "run1.scjvm"
      val out2 = sandbox / "run2.scjvm"

      assert(runSsc(sandbox, "compile-jvm", "a.ssc", "-o", out1.toString).exitCode == 0)
      Thread.sleep(1100)
      assert(runSsc(sandbox, "compile-jvm", "a.ssc", "-o", out2.toString).exitCode == 0)

      assertBytewiseIdentical(".scjvm (source-only)", os.read.bytes(out1), os.read.bytes(out2))
    finally os.remove.all(sandbox)

  // ── 4. compile-js is byte-identical across runs ─────────────────────────

  test("compile-js twice → identical .scjs bytes"):
    val sandbox = os.temp.dir(prefix = "ssc-repro-")
    try
      os.write(sandbox / "a.ssc", aSsc)

      val out1 = sandbox / "run1.scjs"
      val out2 = sandbox / "run2.scjs"

      assert(runSsc(sandbox, "compile-js", "a.ssc", "-o", out1.toString).exitCode == 0)
      Thread.sleep(1100)
      assert(runSsc(sandbox, "compile-js", "a.ssc", "-o", out2.toString).exitCode == 0)

      assertBytewiseIdentical(".scjs", os.read.bytes(out1), os.read.bytes(out2))
    finally os.remove.all(sandbox)

  // ── 5. compile-jvm --bytecode classBundle ZIP is reproducible ───────────
  //
  //  Two runs must produce identical envelope bytes (sourceHash, scalaSource,
  //  imports, capabilities) AND identical classBundle ZIP bytes.  The
  //  classBundle wraps `.class`/`.tasty` files which themselves carry
  //  TASTY UUIDs that vary on every compile — so we don't byte-compare the
  //  inner `.class` files.  Instead we verify:
  //
  //   (a) the JSON envelope sans classBundle is identical, and
  //   (b) the classBundle's ZIP entries appear in the same order with the
  //       same names (the most common shape-level non-determinism).

  test("compile-jvm --bytecode twice → identical envelope + every bundle entry byte-identical"):
    requireScalaCli()
    val sandbox = os.temp.dir(prefix = "ssc-repro-")
    try
      os.write(sandbox / "a.ssc", aSsc)

      val out1 = sandbox / "run1.scjvm"
      val out2 = sandbox / "run2.scjvm"

      assert(runSsc(sandbox, "compile-jvm", "--bytecode", "a.ssc", "-o", out1.toString).exitCode == 0)
      Thread.sleep(1100)
      assert(runSsc(sandbox, "compile-jvm", "--bytecode", "a.ssc", "-o", out2.toString).exitCode == 0)

      val json1 = os.read(out1)
      val json2 = os.read(out2)

      // (a) Envelope sans classBundle is byte-identical.
      val stripped1 = stripClassBundle(json1)
      val stripped2 = stripClassBundle(json2)
      assertBytewiseIdentical(
        ".scjvm envelope (classBundle stripped)",
        stripped1.getBytes("UTF-8"),
        stripped2.getBytes("UTF-8"))

      // (b) The bundle's entry order matches AND every entry's bytes
      //     match.  Thanks to pinned ZIP timestamps + `-sourceroot`
      //     in Scala3Driver, the inner `.class` / `.tasty` bytes are
      //     also reproducible.  If a future regression introduces a
      //     new non-determinism source (timestamp leak, randomised
      //     UUID, etc.) this assertion catches it immediately.
      val entries1 = bundleEntries(json1)
      val entries2 = bundleEntries(json2)
      assert(entries1.keys.toList == entries2.keys.toList,
        s"classBundle entry order changed across runs:\n  run1=${entries1.keys.toList}\n  run2=${entries2.keys.toList}")
      for (n, b1) <- entries1 do
        val b2 = entries2(n)
        assertBytewiseIdentical(s"classBundle entry $n", b1, b2)

      // (c) And the classBundle base64 itself is byte-identical (the
      //     strongest possible check — implies (a) + (b) hold at the
      //     ZIP framing level too: central directory, EOCD, etc.).
      val cb1 = ujson.read(json1).obj("classBundle").str
      val cb2 = ujson.read(json2).obj("classBundle").str
      assertBytewiseIdentical("classBundle base64", cb1.getBytes("UTF-8"), cb2.getBytes("UTF-8"))
    finally os.remove.all(sandbox)
