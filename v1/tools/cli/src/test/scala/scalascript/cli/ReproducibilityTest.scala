package scalascript.cli

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipInputStream

import org.scalatest.funsuite.AnyFunSuite
import scalascript.artifact.JvmArtifactIO
import scalascript.ir.ModuleJvmArtifact

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
 *   - Artifact-envelope field order (the production MessagePack writer is
 *     deterministic, but a hand-written writer might not be).
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
 *  How the byte comparison is diagnosed:
 *
 *   - The two complete binary `.scjvm` files are compared directly. For a
 *     useful mismatch report, we also decode through the production artifact
 *     IO, compare the envelope without `classBundle`, preserve and compare ZIP
 *     entry order, and compare every entry's bytes. All entries (`<X>$.class`,
 *     `<X>.class`, `<X>.tasty`) are now byte-identical thanks to the two fixes
 *     above.
 *
 *  Run with: `sbt "cli/testOnly *ReproducibilityTest*"`
 */
class ReproducibilityTest extends AnyFunSuite:

  // ── Installed distribution ──────────────────────────────────────────────

  private def requireLauncher(): os.Path =
    StagedCliTestSupport.toolsLauncher.getOrElse:
      cancel("bin/ssc-tools not found — run `sbt cli/assembly installBin` first")

  private def requireScalaCli(): Unit =
    val res = scala.util.Try {
      os.proc("scala-cli", "--version").call(check = false, stderr = os.Pipe, stdout = os.Pipe)
    }
    if res.isFailure || !res.toOption.exists(_.exitCode == 0) then
      cancel("`scala-cli` not on PATH — needed for compile-jvm --bytecode")

  private def compilerDriverAvailable: Boolean =
    StagedCliTestSupport.compilerDriverAvailable

  private def requireCompilerDriver(): Unit =
    if !compilerDriverAvailable then
      cancel("compiler-driver jars not staged (run `sbt cli/assembly installBin`); skipping --bytecode test")

  private def runSsc(cwd: os.Path, args: String*): os.CommandResult =
    StagedCliTestSupport.runTools(requireLauncher(), cwd, args = args)

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

  private def readJvmArtifact(path: os.Path): ModuleJvmArtifact =
    JvmArtifactIO.readJvmFile(path).fold(
      err => fail(s"failed to decode $path through JvmArtifactIO: $err"),
      identity)

  /** Strip the classBundle from a decoded `.scjvm` for comparison purposes.
   *  `.class` files embed compilation-side metadata
   *  (TASTY UUIDs, etc.) we can't normalise without invasive
   *  post-processing — for now we just verify the envelope reproduces.
   *  When the bundle itself becomes reproducible we drop this. */
  private def stripClassBundle(artifact: ModuleJvmArtifact): Array[Byte] =
    JvmArtifactIO.writeJvm(artifact.copy(classBundle = None)).getBytes("UTF-8")

  /** Decode classBundle and preserve the ZIP's actual entry order. */
  private def bundleEntries(artifact: ModuleJvmArtifact): List[(String, Array[Byte])] =
    artifact.classBundle match
      case None        => Nil
      case Some(b64) if b64.isEmpty => Nil
      case Some(b64) =>
        val zis = new ZipInputStream(new ByteArrayInputStream(Base64.getDecoder.decode(b64)))
        try
          val out = scala.collection.mutable.ListBuffer.empty[(String, Array[Byte])]
          var e = zis.getNextEntry
          val buf = new Array[Byte](8192)
          val baos = new java.io.ByteArrayOutputStream()
          while e != null do
            baos.reset()
            var n = zis.read(buf)
            while n > 0 do
              baos.write(buf, 0, n)
              n = zis.read(buf)
            out += e.getName -> baos.toByteArray
            zis.closeEntry()
            e = zis.getNextEntry
          out.toList
        finally zis.close()

  /** SHA-256 of two runs' outputs; the test asserts they match.  When
   *  they don't, we print a diff-friendly delta to help diagnose. */
  private def assertBytewiseIdentical(label: String, b1: Array[Byte], b2: Array[Byte]): Unit =
    if !java.util.Arrays.equals(b1, b2) then
      val h1 = sha256(b1)
      val h2 = sha256(b2)
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
    requireCompilerDriver()
    val sandbox = os.temp.dir(prefix = "ssc-repro-")
    try
      os.write(sandbox / "a.ssc", aSsc)

      val out1 = sandbox / "run1.scjvm"
      val out2 = sandbox / "run2.scjvm"

      assert(runSsc(sandbox, "compile-jvm", "--bytecode", "a.ssc", "-o", out1.toString).exitCode == 0)
      Thread.sleep(1100)
      assert(runSsc(sandbox, "compile-jvm", "--bytecode", "a.ssc", "-o", out2.toString).exitCode == 0)

      val artifact1 = readJvmArtifact(out1)
      val artifact2 = readJvmArtifact(out2)

      // (a) Envelope sans classBundle is byte-identical.
      val stripped1 = stripClassBundle(artifact1)
      val stripped2 = stripClassBundle(artifact2)
      assertBytewiseIdentical(
        ".scjvm envelope (classBundle stripped)",
        stripped1,
        stripped2)

      // (b) The bundle's entry order matches AND every entry's bytes
      //     match.  Thanks to pinned ZIP timestamps + `-sourceroot`
      //     in Scala3Driver, the inner `.class` / `.tasty` bytes are
      //     also reproducible.  If a future regression introduces a
      //     new non-determinism source (timestamp leak, randomised
      //     UUID, etc.) this assertion catches it immediately.
      val entries1 = bundleEntries(artifact1)
      val entries2 = bundleEntries(artifact2)
      val names1 = entries1.map(_._1)
      val names2 = entries2.map(_._1)
      assert(names1 == names2,
        s"classBundle entry order changed across runs:\n  run1=$names1\n  run2=$names2")
      entries1.zip(entries2).foreach { case ((n1, b1), (n2, b2)) =>
        assert(n1 == n2, s"entry order mismatch: run1=$n1 run2=$n2")
        assertBytewiseIdentical(s"classBundle entry $n1", b1, b2)
      }

      // (c) The classBundle base64 itself is byte-identical, covering ZIP
      //     framing too: central directory, EOCD, etc.
      val cb1 = artifact1.classBundle.getOrElse(fail("run1 has no classBundle"))
      val cb2 = artifact2.classBundle.getOrElse(fail("run2 has no classBundle"))
      assertBytewiseIdentical("classBundle base64", cb1.getBytes("UTF-8"), cb2.getBytes("UTF-8"))

      // The actual cache artifact is the binary file, not its decoded proxy.
      assertBytewiseIdentical("complete .scjvm artifact", os.read.bytes(out1), os.read.bytes(out2))
    finally os.remove.all(sandbox)
