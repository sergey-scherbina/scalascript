package scalascript.artifact

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser
import scalascript.ir.ArtifactVersion
import scala.concurrent.{Future, Await}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

/** Cross-platform portability smoke tests for the v2.0 artifact pipeline.
 *
 *  Covers the three historically Unix-only gaps called out in
 *  `docs/v2.0-scale-benchmark.md §Cross-platform smoke`:
 *    1. CRLF / CR line-ending normalization in `sourceHash`.
 *    2. Path-separator portability via os-lib.
 *    3. Concurrent artifact writes to the same directory.
 *
 *  These tests are JVM-only and deterministic — no network, no spawned
 *  processes.  They run on Windows (CRLF) and Unix (LF) without flags. */
class CrossPlatformSmokeTest extends AnyFunSuite:

  // ── 1. Line-ending normalization ─────────────────────────────────────────

  test("normalizeLineEndings — no-op on LF-only input (fast path, same reference)"):
    val lf = "hello\nworld\n".getBytes("UTF-8")
    assert(InterfaceExtractor.normalizeLineEndings(lf) eq lf,
      "must return the same array reference when no CR is present")

  test("normalizeLineEndings — CRLF stripped to LF"):
    val crlf = "hello\r\nworld\r\n".getBytes("UTF-8")
    val norm = InterfaceExtractor.normalizeLineEndings(crlf)
    assert(new String(norm, "UTF-8") == "hello\nworld\n")

  test("normalizeLineEndings — bare CR stripped (classic Mac line endings)"):
    val cr = "hello\rworld\r".getBytes("UTF-8")
    val norm = InterfaceExtractor.normalizeLineEndings(cr)
    assert(new String(norm, "UTF-8") == "helloworld")

  test("normalizeLineEndings — mixed CRLF and bare CR both removed"):
    val mixed = "a\r\nb\rc\r\n".getBytes("UTF-8")
    val norm = InterfaceExtractor.normalizeLineEndings(mixed)
    assert(new String(norm, "UTF-8") == "a\nbc\n")

  test("normalizeLineEndings — empty input stays empty"):
    val empty = Array.emptyByteArray
    val norm  = InterfaceExtractor.normalizeLineEndings(empty)
    assert(norm.isEmpty)

  // ── 2. sourceFileHash cross-platform stability ────────────────────────────

  test("sourceFileHash — LF and CRLF source produce identical hash"):
    val src  = "val x = 1\nval y = 2\n"
    val lf   = src.getBytes("UTF-8")
    val crlf = src.replace("\n", "\r\n").getBytes("UTF-8")
    assert(InterfaceExtractor.sourceFileHash(lf) == InterfaceExtractor.sourceFileHash(crlf),
      "CRLF and LF variants of the same source must hash identically")

  test("sourceFileHash — CR-only and LF source produce identical hash"):
    val src = "val x = 1\nval y = 2\n"
    val lf  = src.getBytes("UTF-8")
    val cr  = src.replace("\n", "\r").getBytes("UTF-8")
    assert(InterfaceExtractor.sourceFileHash(lf) == InterfaceExtractor.sourceFileHash(cr))

  test("sourceFileHash — empty bytes produce a stable 64-char hex digest"):
    val h = InterfaceExtractor.sourceFileHash(Array.emptyByteArray)
    assert(h.length == 64, s"expected 64-char SHA-256 hex, got '$h'")
    // SHA-256 of empty input is a well-known constant.
    assert(h == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")

  test("sourceFileHash — different content produces different hash"):
    val a = InterfaceExtractor.sourceFileHash("val x = 1".getBytes("UTF-8"))
    val b = InterfaceExtractor.sourceFileHash("val x = 2".getBytes("UTF-8"))
    assert(a != b)

  test("extract() — CRLF source yields same sourceHash as LF source"):
    val src = """---
                |name: smoke
                |---
                |
                |# Demo
                |
                |```scalascript
                |val x = 1
                |```
                |""".stripMargin
    val lfBytes   = src.getBytes("UTF-8")
    val crlfBytes = src.replace("\n", "\r\n").getBytes("UTF-8")
    val m         = Parser.parse(src)
    val ifaceLf   = InterfaceExtractor.extract(m, lfBytes)
    val ifaceCrlf = InterfaceExtractor.extract(m, crlfBytes)
    assert(ifaceLf.sourceHash == ifaceCrlf.sourceHash,
      s"sourceHash must be CRLF-normalized: lf=${ifaceLf.sourceHash} crlf=${ifaceCrlf.sourceHash}")

  // ── 3. Path-separator portability ────────────────────────────────────────

  test("os-lib path join is separator-agnostic — relative segments"):
    val base  = os.temp.dir(prefix = "ssc-xplat-")
    try
      val sub = base / "a" / "b" / "c"
      os.makeDir.all(sub)
      assert(os.exists(sub))
      // os.Path.toString uses the platform separator; os.RelPath uses `/` as
      // its canonical separator in its `toString`.
      val rel = sub.relativeTo(base)
      assert(rel.toString == "a/b/c",
        s"os.RelPath.toString must use '/' on all platforms; got '${rel.toString}'")
    finally os.remove.all(base)

  test("artifact file-name construction is separator-neutral"):
    val base    = os.temp.dir(prefix = "ssc-xplat-")
    try
      val artDir  = base / "artifacts"
      os.makeDir.all(artDir)
      val srcName = "my_module.ssc"
      val scimPath = artDir / (srcName.stripSuffix(".ssc") + ".scim")
      assert(scimPath.last == "my_module.scim")
      assert(scimPath.segments.toList.last == "my_module.scim")
    finally os.remove.all(base)

  // ── 4. Concurrent artifact writes ────────────────────────────────────────

  test("concurrent writes to distinct files in same dir do not corrupt"):
    val sandbox = os.temp.dir(prefix = "ssc-xplat-concurrent-")
    try
      val src = """---
                  |name: concurrent
                  |---
                  |
                  |# A
                  |
                  |```scalascript
                  |val n = 1
                  |```
                  |""".stripMargin
      val module = Parser.parse(src)

      val futs = (1 to 8).map { i =>
        Future {
          val artDir = sandbox / s"out-$i"
          os.makeDir.all(artDir)
          val iface = InterfaceExtractor.extract(module, src.getBytes("UTF-8"))
          val path  = artDir / "concurrent.scim"
          ArtifactIO.writeInterfaceFile(iface, path)
          ArtifactIO.readInterfaceFile(path)
        }
      }
      val results = Await.result(Future.sequence(futs), 30.seconds)
      results.foreach {
        case Left(err)    => fail(s"artifact read-back failed: $err")
        case Right(iface) =>
          assert(iface.magic == ArtifactVersion.magic)
          assert(iface.moduleName.contains("concurrent"))
      }
    finally os.remove.all(sandbox)

  test("concurrent writes to the same file path — last-write-wins, no corruption"):
    val sandbox = os.temp.dir(prefix = "ssc-xplat-samefile-")
    try
      val src = """---
                  |name: same
                  |---
                  |
                  |# B
                  |
                  |```scalascript
                  |val m = 2
                  |```
                  |""".stripMargin
      val artDir = sandbox / "out"
      os.makeDir.all(artDir)
      val module = Parser.parse(src)
      val path   = artDir / "same.scim"

      val futs = (1 to 6).map { _ =>
        Future {
          val iface = InterfaceExtractor.extract(module, src.getBytes("UTF-8"))
          ArtifactIO.writeInterfaceFile(iface, path)
        }
      }
      Await.result(Future.sequence(futs), 30.seconds)

      // Whatever was written last must be a valid artifact.
      ArtifactIO.readInterfaceFile(path) match
        case Left(err)    => fail(s"concurrent same-file write produced invalid artifact: $err")
        case Right(iface) =>
          assert(iface.magic == ArtifactVersion.magic)
          assert(iface.moduleName.contains("same"))
    finally os.remove.all(sandbox)
