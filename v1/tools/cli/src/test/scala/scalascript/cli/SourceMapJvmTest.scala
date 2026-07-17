package scalascript.cli

import java.util.jar.JarFile

import org.scalatest.funsuite.AnyFunSuite
import scalascript.artifact.JvmArtifactIO

/** v2.0 Phase 4 — tests for `ssc link --backend jvm --bytecode --source-map`.
 *
 *  Verifies the Option B sidecar-source approach:
 *
 *   1. `link --backend jvm --bytecode --source-map artifacts -o out.jar`
 *      produces `out.jar` AND a `<moduleId>.ssc.scala` file in the same
 *      directory as the JAR.
 *   2. The sidecar `.scala` file's contents match the `scalaSource` field
 *      of the `.scjvm` artifact (so the user can attach it as source in
 *      IntelliJ / drop it in `META-INF/sources/`).
 *   3. Without `--source-map`, no sidecar is written.
 *
 *  Tests `cancel(...)` when the installed launcher is missing. Once staged,
 *  compile/link failures are assertion failures with full process diagnostics.
 *
 *  Run with:  `sbt "cli/testOnly *SourceMapJvm*"`
 */
class SourceMapJvmTest extends AnyFunSuite:

  // ── Installed distribution ──────────────────────────────────────────────

  private def requireLauncher(): os.Path =
    StagedCliTestSupport.toolsLauncher.getOrElse:
      cancel("bin/ssc-tools not found — run `sbt cli/assembly installBin` first")

  private def runSsc(cwd: os.Path, args: String*): os.CommandResult =
    StagedCliTestSupport.runTools(requireLauncher(), cwd, args = args)

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

  // ── Tests ───────────────────────────────────────────────────────────────

  test("link --backend jvm --bytecode --source-map emits .ssc.scala sidecars"):
    val sandbox = os.temp.dir(prefix = "ssc-srcmap-jvm-")
    try
      val artDir = sandbox / "artifacts"
      os.makeDir.all(artDir)
      os.write(sandbox / "a.ssc", aSsc)
      os.write(sandbox / "b.ssc", bSsc)

      val ra = runSsc(sandbox, "compile-jvm", "--bytecode", "a.ssc",
                      "-o", "artifacts/a.scjvm")
      assert(ra.exitCode == 0,
        s"compile-jvm --bytecode a failed: exit=${ra.exitCode}\n" +
          s"stdout=${ra.out.text()}\nstderr=${ra.err.text()}")

      val rb = runSsc(sandbox, "compile-jvm", "--bytecode", "b.ssc",
                      "-o", "artifacts/b.scjvm")
      assert(rb.exitCode == 0,
        s"compile-jvm --bytecode b failed: exit=${rb.exitCode}\n" +
          s"stdout=${rb.out.text()}\nstderr=${rb.err.text()}")

      // Link WITH --source-map.  Output JAR lives next to the sidecars.
      val outJar = sandbox / "build" / "out.jar"
      os.makeDir.all(outJar / os.up)
      val rl = runSsc(sandbox, "link", "--backend", "jvm", "--bytecode",
                      "--source-map", "artifacts",
                      "-o", outJar.toString)
      assert(rl.exitCode == 0,
        s"link --bytecode --source-map failed:\nstdout=${rl.out.text()}\nstderr=${rl.err.text()}")
      assert(os.exists(outJar), s"expected $outJar")

      val sidecarA = outJar / os.up / "a.ssc.scala"
      val sidecarB = outJar / os.up / "b.ssc.scala"
      assert(os.exists(sidecarA),
        s"expected $sidecarA next to the linked JAR")
      assert(os.exists(sidecarB),
        s"expected $sidecarB next to the linked JAR")

      // Sidecar content should match the .scjvm's scalaSource field.
      val artifactA = JvmArtifactIO.readJvmFile(artDir / "a.scjvm").fold(
        err => fail(s"failed to decode a.scjvm through JvmArtifactIO: $err"),
        identity)
      val srcAField = artifactA.scalaSource
      assert(os.read(sidecarA) == srcAField,
        s"sidecar a.ssc.scala doesn't match the .scjvm scalaSource field")
      // Sanity: source contains the `add` function.
      assert(os.read(sidecarA).contains("add"),
        s"expected `add` in sidecar a.ssc.scala")

      val artifactB = JvmArtifactIO.readJvmFile(artDir / "b.scjvm").fold(
        err => fail(s"failed to decode b.scjvm through JvmArtifactIO: $err"),
        identity)
      val srcBField = artifactB.scalaSource
      assert(os.read(sidecarB) == srcBField)
      assert(os.read(sidecarB).contains("sub"))

      // The status output mentions the sidecars (so users know to look).
      val stdout = rl.out.text()
      assert(stdout.contains("Source-map sidecars written"),
        s"expected stdout to mention sidecars; got:\n$stdout")
    finally os.remove.all(sandbox)

  test("link --backend jvm --bytecode WITHOUT --source-map does NOT emit sidecars"):
    val sandbox = os.temp.dir(prefix = "ssc-srcmap-jvm-")
    try
      val artDir = sandbox / "artifacts"
      os.makeDir.all(artDir)
      os.write(sandbox / "a.ssc", aSsc)

      val ra = runSsc(sandbox, "compile-jvm", "--bytecode", "a.ssc",
                      "-o", "artifacts/a.scjvm")
      assert(ra.exitCode == 0,
        s"compile-jvm --bytecode failed: exit=${ra.exitCode}\n" +
          s"stdout=${ra.out.text()}\nstderr=${ra.err.text()}")

      val outJar = sandbox / "build" / "out.jar"
      os.makeDir.all(outJar / os.up)
      val rl = runSsc(sandbox, "link", "--backend", "jvm", "--bytecode",
                      "artifacts", "-o", outJar.toString)
      assert(rl.exitCode == 0,
        s"link --bytecode failed:\nstderr=${rl.err.text()}")
      assert(os.exists(outJar))

      val sidecarA = outJar / os.up / "a.ssc.scala"
      assert(!os.exists(sidecarA),
        s"sidecar must NOT be emitted without --source-map; found at $sidecarA")
    finally os.remove.all(sandbox)

  test("packed JAR has a SourceFile attribute on the user-code .class entries"):
    // Sanity check: even without our SMAP / SourceDebugExtension injection,
    // the Scala 3 compiler always emits a SourceFile attribute pointing
    // at the source file name we passed in (`<safeName>_sc.scala` today).
    // Decode the `.class` raw bytes far enough to confirm there's a
    // `SourceFile` string in the constant pool.  This is the Option B
    // baseline we lean on for IDE source attachment: when a user names a
    // sibling `<moduleId>.ssc.scala` (which our sidecar writer creates),
    // IntelliJ resolves the source by filename.
    val sandbox = os.temp.dir(prefix = "ssc-srcmap-jvm-")
    try
      val artDir = sandbox / "artifacts"
      os.makeDir.all(artDir)
      os.write(sandbox / "a.ssc", aSsc)

      val ra = runSsc(sandbox, "compile-jvm", "--bytecode", "a.ssc",
                      "-o", "artifacts/a.scjvm")
      assert(ra.exitCode == 0,
        s"compile-jvm --bytecode failed: exit=${ra.exitCode}\n" +
          s"stdout=${ra.out.text()}\nstderr=${ra.err.text()}")

      val outJar = sandbox / "out.jar"
      val rl = runSsc(sandbox, "link", "--backend", "jvm", "--bytecode",
                      "--source-map", "artifacts", "-o", outJar.toString)
      assert(rl.exitCode == 0,
        s"link --bytecode --source-map failed:\nstderr=${rl.err.text()}")

      // Open the JAR, find a user-code `.class` entry, and search its
      // bytes for the literal string "SourceFile".  Class files store
      // attribute names as Utf8 entries in the constant pool, so the raw
      // ASCII bytes appear verbatim in the file.
      val jar = new JarFile(outJar.toIO)
      try
        val classEntries = scala.jdk.CollectionConverters.EnumerationHasAsScala(
          jar.entries()
        ).asScala.toList.filter(_.getName.endsWith(".class"))
        assert(classEntries.nonEmpty, "expected at least one .class in the JAR")
        // Look at the wrapper script class first — `a_sc.class` or
        // similar — that's the most likely to have user code with line
        // information.
        val candidate = classEntries.find(_.getName.endsWith("_sc.class"))
          .getOrElse(classEntries.head)
        val in = jar.getInputStream(candidate)
        val bytes =
          try in.readAllBytes()
          finally in.close()
        val s = new String(bytes, "ISO-8859-1") // byte-for-byte
        assert(s.contains("SourceFile"),
          s"expected 'SourceFile' attribute name in ${candidate.getName}")
      finally jar.close()
    finally os.remove.all(sandbox)
