package scalascript.cli

import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.jar.JarFile
import java.util.zip.ZipInputStream

import org.scalatest.funsuite.AnyFunSuite
import scalascript.artifact.JvmArtifactIO
import scalascript.ir.ModuleJvmArtifact

/** v2.0 Phase 2 — end-to-end tests for the bytecode-level JVM linker.
 *
 *  Cover the three CLI surfaces added for `.scjvm` classBundles:
 *
 *   1. `ssc compile-jvm --bytecode <file.ssc>` produces a `.scjvm` whose
 *      `classBundle` is a valid base64-encoded ZIP containing at least one
 *      `.class` entry.
 *   2. `ssc link --backend jvm --bytecode <dir> -o out.jar` packages the
 *      classBundles into a single JAR; `jar tf out.jar` lists the expected
 *      `<moduleId>_sc.class` script entries.
 *   3. Negative: `ssc link --backend jvm --bytecode` against a `.scjvm`
 *      WITHOUT a `classBundle` fails with a clear error.
 *   4. Run-test: 1-module fixture — `java -cp out.jar:scala-lib <main>`
 *      prints expected stdout and exits 0.
 *   5. Two-module run-test where b imports a's `add` via `[add](./a.ssc)`.
 *
 *  All tests `cancel(...)` when prerequisites (the installed `ssc-tools`,
 *  `scala-cli`, the staged compiler driver, or Scala runtime libraries) are
 *  missing — local dev setups vary.
 *
 *  Run with:  `sbt "cli/testOnly *JvmBytecode*"`
 */
class JvmBytecodeLinkCliTest extends AnyFunSuite:

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

  // ── Test fixtures ────────────────────────────────────────────────────────

  /** A module with a single Int-returning function and an inline println. */
  private val aSscWithMain: String =
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

  /** A module without a println — just the def. */
  private val aSscQuiet: String =
    """---
      |name: a
      |---
      |
      |# Module A
      |
      |```scalascript
      |def add(x: Int, y: Int): Int = x + y
      |```
      |""".stripMargin

  /** A second module that pulls in A via Content.Import and calls `add`. */
  private val bSscCallingA: String =
    """---
      |name: b
      |---
      |
      |# Module B
      |
      |[add](./a.ssc)
      |
      |```scalascript
      |println("a.add(7, 8) = " + add(7, 8))
      |```
      |""".stripMargin

  private def requireScalaStdlib(): String =
    StagedCliTestSupport.scalaRuntimeClasspath.getOrElse:
      cancel("Scala runtime libraries are not visible to the test JVM — skipping JAR-run test")

  private def readJvmArtifact(path: os.Path): ModuleJvmArtifact =
    JvmArtifactIO.readJvmFile(path).fold(
      err => fail(s"failed to decode $path through JvmArtifactIO: $err"),
      identity)

  // ── 1. compile-jvm --bytecode produces a valid classBundle ──────────────

  test("compile-jvm --bytecode produces a .scjvm with a non-empty classBundle"):
    requireScalaCli()
    requireCompilerDriver()
    val sandbox = os.temp.dir(prefix = "ssc-bytecode-")
    try
      val src = sandbox / "a.ssc"
      os.write(src, aSscQuiet)

      val res = runSsc(sandbox, "compile-jvm", "--bytecode", "a.ssc")
      assert(res.exitCode == 0,
        s"compile-jvm --bytecode failed: exit=${res.exitCode}\nstdout=${res.out.text()}\nstderr=${res.err.text()}")

      val scjvm = sandbox / "a.scjvm"
      assert(os.exists(scjvm), s"expected $scjvm; got: ${os.list(sandbox).mkString(", ")}")

      val artifact = readJvmArtifact(scjvm)
      assert(artifact.magic == "SSCART")
      assert(artifact.abiVersion == "2.0")

      // classBundle must be a non-empty base64 string.
      val cbStr = artifact.classBundle.getOrElse:
        fail("expected classBundle to be present")
      assert(cbStr.nonEmpty, "expected non-empty classBundle")

      // Decoded base64 must be a valid ZIP whose entries include at least
      // one `.class` file (the wrapping `a_sc.class` script entry).
      val zipBytes = Base64.getDecoder.decode(cbStr)
      val zis      = new ZipInputStream(new ByteArrayInputStream(zipBytes))
      try
        val entries = scala.collection.mutable.ListBuffer.empty[String]
        var entry = zis.getNextEntry
        while entry != null do
          entries += entry.getName
          zis.closeEntry()
          entry = zis.getNextEntry
        val classEntries = entries.filter(_.endsWith(".class"))
        assert(classEntries.nonEmpty,
          s"expected at least one .class entry in classBundle; got: ${entries.mkString(", ")}")
        // The moduleId `a` is used as the script name, so `a_sc.class`
        // must be in the bundle.
        assert(classEntries.exists(_.endsWith("a_sc.class")),
          s"expected `a_sc.class` (script wrapper) in bundle; got: ${classEntries.mkString(", ")}")
      finally zis.close()
    finally os.remove.all(sandbox)

  // ── 2. link --backend jvm --bytecode produces a JAR with expected entries

  test("link --backend jvm --bytecode packs class bundles into a JAR with expected entries"):
    requireScalaCli()
    requireCompilerDriver()
    val sandbox = os.temp.dir(prefix = "ssc-bytecode-")
    try
      val artDir = sandbox / "artifacts"
      os.makeDir.all(artDir)

      os.write(sandbox / "a.ssc", aSscQuiet)
      os.write(sandbox / "b.ssc",
        """---
          |name: b
          |---
          |
          |# Module B
          |
          |```scalascript
          |def mul(x: Int, y: Int): Int = x * y
          |```
          |""".stripMargin)

      assert(runSsc(sandbox, "compile-jvm", "--bytecode", "a.ssc",
                    "-o", "artifacts/a.scjvm").exitCode == 0)
      assert(runSsc(sandbox, "compile-jvm", "--bytecode", "b.ssc",
                    "-o", "artifacts/b.scjvm").exitCode == 0)

      val outJar = sandbox / "out.jar"
      val rl = runSsc(sandbox, "link", "--backend", "jvm", "--bytecode",
                      "artifacts", "-o", outJar.toString)
      assert(rl.exitCode == 0,
        s"link --bytecode failed:\nstdout=${rl.out.text()}\nstderr=${rl.err.text()}")
      assert(os.exists(outJar), "expected out.jar to be produced")
      assert(os.size(outJar) > 0, "expected non-empty JAR")

      val jar = new JarFile(outJar.toIO)
      try
        val names = jar.stream().toArray.map(_.asInstanceOf[java.util.jar.JarEntry].getName).toList
        assert(names.exists(_.endsWith("a_sc.class")),
          s"expected a_sc.class in JAR; got first 20: ${names.take(20).mkString(", ")}")
        assert(names.exists(_.endsWith("b_sc.class")),
          s"expected b_sc.class in JAR; got first 20: ${names.take(20).mkString(", ")}")
        // Tier 5: the linked JAR is also a Scala 3 compile-time dependency,
        // so module and shared-runtime TASTY must survive linking.
        val tastyEntries = names.filter(_.endsWith(".tasty"))
        assert(tastyEntries.exists(_.endsWith("a_sc.tasty")),
          s"expected a_sc.tasty in linked JAR; got: ${tastyEntries.mkString(", ")}")
        assert(tastyEntries.exists(_.endsWith("b_sc.tasty")),
          s"expected b_sc.tasty in linked JAR; got: ${tastyEntries.mkString(", ")}")
        assert(tastyEntries.exists(_.endsWith("_ssc_runtime.tasty")),
          s"expected shared-runtime TASTY in linked JAR; got: ${tastyEntries.mkString(", ")}")
      finally jar.close()
    finally os.remove.all(sandbox)

  // ── 3. negative: link --bytecode fails on a source-only .scjvm ───────────

  test("link --backend jvm --bytecode fails clearly when a .scjvm has no classBundle"):
    val sandbox = os.temp.dir(prefix = "ssc-bytecode-")
    try
      val artDir = sandbox / "artifacts"
      os.makeDir.all(artDir)

      os.write(sandbox / "a.ssc", aSscQuiet)

      // Produce a source-only .scjvm (no --bytecode flag).
      val r = runSsc(sandbox, "compile-jvm", "a.ssc", "-o", "artifacts/a.scjvm")
      assert(r.exitCode == 0,
        s"compile-jvm (source-only) failed: ${r.err.text()}")

      // Sanity: confirm that the produced artifact really lacks a classBundle.
      val sourceOnly = readJvmArtifact(artDir / "a.scjvm")
      assert(sourceOnly.classBundle.forall(_.isEmpty),
        s"expected classBundle to be missing/empty in source-only artifact; " +
          s"got ${sourceOnly.classBundle.map(_.length)} chars")

      val outJar = sandbox / "out.jar"
      val rl = runSsc(sandbox, "link", "--backend", "jvm", "--bytecode",
                      "artifacts", "-o", outJar.toString)
      assert(rl.exitCode != 0,
        s"expected link --bytecode to fail; got exit ${rl.exitCode}\nstdout=${rl.out.text()}")
      val err = rl.err.text() + rl.out.text()
      assert(err.contains("classBundle") || err.contains("--bytecode"),
        s"expected clear error mentioning classBundle/--bytecode; got:\n$err")
      assert(!os.exists(outJar), "expected no JAR to be written on failure")
    finally os.remove.all(sandbox)

  // ── 4. run-test: 1-module fixture, java -cp out.jar produces stdout ─────

  test("compile-jvm --bytecode + link --bytecode + java -cp produce expected stdout (1 module)"):
    requireScalaCli()
    requireCompilerDriver()
    val stdlib = requireScalaStdlib()
    val sandbox = os.temp.dir(prefix = "ssc-bytecode-")
    try
      val artDir = sandbox / "artifacts"
      os.makeDir.all(artDir)
      os.write(sandbox / "a.ssc", aSscWithMain)

      assert(runSsc(sandbox, "compile-jvm", "--bytecode", "a.ssc",
                    "-o", "artifacts/a.scjvm").exitCode == 0)

      val outJar = sandbox / "out.jar"
      assert(runSsc(sandbox, "link", "--backend", "jvm", "--bytecode",
                    "artifacts", "-o", outJar.toString).exitCode == 0)

      val runRes = os.proc("java", "-cp", s"$outJar:$stdlib", "a_sc").call(
        check = false, stderr = os.Pipe, stdout = os.Pipe
      )
      assert(runRes.exitCode == 0,
        s"java -cp out.jar a_sc failed: exit=${runRes.exitCode}\nclasspath=$stdlib\n" +
          s"stdout=${runRes.out.text()}\nstderr=${runRes.err.text()}")
      val out = runRes.out.text()
      assert(out.contains("a.add(2, 3) = 5"),
        s"expected expected stdout 'a.add(2, 3) = 5'; got:\n$out")
    finally os.remove.all(sandbox)

  // ── 5. run-test: 2-module fixture, b calls a's exported function ────────

  test("compile-jvm --bytecode + link --bytecode + java -cp run b → calls a.add (2 modules)"):
    requireScalaCli()
    requireCompilerDriver()
    val stdlib = requireScalaStdlib()
    val sandbox = os.temp.dir(prefix = "ssc-bytecode-")
    try
      val artDir = sandbox / "artifacts"
      os.makeDir.all(artDir)

      os.write(sandbox / "a.ssc", aSscQuiet)
      os.write(sandbox / "b.ssc", bSscCallingA)

      // Emit .scim for a so b's typer can see it.
      assert(runSsc(sandbox, "emit-interface", "a.ssc",
                    "-o", "artifacts/a.scim").exitCode == 0)
      assert(runSsc(sandbox, "compile-jvm", "--bytecode", "a.ssc",
                    "-o", "artifacts/a.scjvm").exitCode == 0)
      val cb = runSsc(sandbox, "compile-jvm", "--bytecode", "b.ssc",
                      "--iface-dir", "artifacts",
                      "-o", "artifacts/b.scjvm")
      assert(cb.exitCode == 0,
        s"compile-jvm b --bytecode failed:\nstdout=${cb.out.text()}\nstderr=${cb.err.text()}")

      val outJar = sandbox / "out.jar"
      val rl = runSsc(sandbox, "link", "--backend", "jvm", "--bytecode",
                      "artifacts", "-o", outJar.toString)
      assert(rl.exitCode == 0,
        s"link --bytecode failed:\nstdout=${rl.out.text()}\nstderr=${rl.err.text()}")

      // Run b's script wrapper.  It calls add (inlined from a.ssc by the
      // Content.Import).
      val runRes = os.proc("java", "-cp", s"$outJar:$stdlib", "b_sc").call(
        check = false, stderr = os.Pipe, stdout = os.Pipe
      )
      assert(runRes.exitCode == 0,
        s"java -cp out.jar b_sc failed: exit=${runRes.exitCode}\nclasspath=$stdlib\n" +
          s"stderr=${runRes.err.text()}")
      val out = runRes.out.text()
      assert(out.contains("a.add(7, 8) = 15"),
        s"expected 'a.add(7, 8) = 15' in stdout; got:\n$out")
    finally os.remove.all(sandbox)
