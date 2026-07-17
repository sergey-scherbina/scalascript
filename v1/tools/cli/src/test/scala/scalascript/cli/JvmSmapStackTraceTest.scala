package scalascript.cli

import java.util.jar.JarFile

import org.scalatest.funsuite.AnyFunSuite
import scalascript.artifact.JvmArtifactIO

/** v2.0 Phase 4 (Option A) — end-to-end test for JSR-45 SMAP injection
 *  via the `compile-jvm --bytecode` → `link --bytecode --source-map`
 *  pipeline.
 *
 *  What we assert at each layer:
 *
 *   1. `.scjvm` carries a non-empty `lineMap` after `compile-jvm
 *      --bytecode` against a fixture with known `.ssc` line positions.
 *   2. `link --backend jvm --bytecode --source-map` writes a JAR whose
 *      user-code `.class` entries carry a `SourceDebugExtension`
 *      attribute referencing `<moduleId>.ssc` and containing line
 *      mappings.
 *   3. The SMAP's `*L` section points back into the original `.ssc`
 *      line range (1-based) — the user-defined function's body line
 *      is somewhere in the recorded range.
 *
 *  Runtime stack-trace validation (`java -jar out.jar` + grep) is
 *  attempted under `SSC_EXTERNAL_SCALA_CLI=1` so the `.sc` script
 *  wrapper generates a `main(String[])` method (the in-process Driver
 *  path wraps in a bare `object X_sc { … }` which has no main and
 *  can't be invoked from `java -cp`).  When external scala-cli isn't
 *  available the runtime assertion is skipped via `cancel(...)`. */
class JvmSmapStackTraceTest extends AnyFunSuite:

  // ── Installed distribution ──────────────────────────────────────────────

  private def requireLauncher(): os.Path =
    StagedCliTestSupport.toolsLauncher.getOrElse:
      cancel("bin/ssc-tools not found — run `sbt cli/assembly installBin` first")

  private def runSsc(cwd: os.Path, env: Map[String, String], args: String*): os.CommandResult =
    StagedCliTestSupport.runTools(requireLauncher(), cwd, env, args)

  private def runSsc(cwd: os.Path, args: String*): os.CommandResult =
    runSsc(cwd, Map.empty, args*)

  // ── Fixture: `boom()` lives at a known .ssc line.
  //   Counting from line 1 (the front-matter `---`):
  //     1: ---
  //     2: name: a
  //     3: ---
  //     4: (blank)
  //     5: # Module A
  //     6: (blank)
  //     7: ```scalascript
  //     8: def add(x: Int, y: Int): Int = x + y
  //     9: def boom(): Int = throw new RuntimeException("kaboom")
  //    10: println("a.add(2, 3) = " + add(2, 3))
  //    11: ```
  //  So `boom` is at line 9.
  private val aSsc: String =
    """---
      |name: a
      |---
      |
      |# Module A
      |
      |```scalascript
      |def add(x: Int, y: Int): Int = x + y
      |def boom(): Int = throw new RuntimeException("kaboom")
      |println("a.add(2, 3) = " + add(2, 3))
      |```
      |""".stripMargin

  private val BoomLineInSsc = 9

  // ── Tests ───────────────────────────────────────────────────────────────

  test("compile-jvm --bytecode produces a .scjvm with a non-empty lineMap"):
    val sandbox = os.temp.dir(prefix = "ssc-smap-st-")
    try
      os.write(sandbox / "a.ssc", aSsc)
      os.makeDir.all(sandbox / "artifacts")
      val r = runSsc(sandbox, "compile-jvm", "--bytecode", "a.ssc",
                     "-o", "artifacts/a.scjvm")
      assert(r.exitCode == 0,
        s"compile-jvm --bytecode failed: exit=${r.exitCode}\n" +
          s"stdout=${r.out.text()}\nstderr=${r.err.text()}")

      val artifactPath = sandbox / "artifacts" / "a.scjvm"
      val artifact = JvmArtifactIO.readJvmFile(artifactPath).fold(
        err => fail(s"failed to decode $artifactPath through JvmArtifactIO: $err"),
        identity)
      val lineMap = artifact.lineMap
      assert(lineMap.nonEmpty,
        s"expected non-empty lineMap in a.scjvm; got: $lineMap")

      // The mapping should refer to .ssc line 9 (where `boom` lives) and
      // probably line 8 / 10 (add / println), depending on emit details.
      val origLines = lineMap.values.toSet
      assert(origLines.contains(BoomLineInSsc),
        s"expected the lineMap to include .ssc line $BoomLineInSsc; " +
        s"got original lines: ${origLines.toList.sorted}")
    finally os.remove.all(sandbox)

  test("link --bytecode --source-map injects SourceDebugExtension into user-code .class"):
    val sandbox = os.temp.dir(prefix = "ssc-smap-st-")
    try
      os.write(sandbox / "a.ssc", aSsc)
      os.makeDir.all(sandbox / "artifacts")

      val rc = runSsc(sandbox, "compile-jvm", "--bytecode", "a.ssc",
                      "-o", "artifacts/a.scjvm")
      assert(rc.exitCode == 0,
        s"compile-jvm --bytecode failed: exit=${rc.exitCode}\n" +
          s"stdout=${rc.out.text()}\nstderr=${rc.err.text()}")

      val outJar = sandbox / "out.jar"
      val rl = runSsc(sandbox, "link", "--backend", "jvm", "--bytecode",
                      "--source-map", "artifacts", "-o", outJar.toString)
      assert(rl.exitCode == 0,
        s"link --bytecode --source-map failed: stderr=${rl.err.text()}")
      assert(os.exists(outJar))

      // Decode the user-code .class file (a_sc.class) from the JAR and
      // confirm it has a SourceDebugExtension whose body looks like a
      // JSR-45 SMAP referencing a.ssc with our mapping line.
      val jar = new JarFile(outJar.toIO)
      try
        val userClass = scala.jdk.CollectionConverters
          .EnumerationHasAsScala(jar.entries()).asScala.toList
          .find(_.getName == "a_sc$.class")
          .getOrElse(throw new AssertionError("expected a_sc$.class in out.jar"))
        val bytes = jar.getInputStream(userClass).readAllBytes()
        val smap = JvmSmapInjector.readSourceDebugExtension(bytes)
        assert(smap.isDefined,
          s"expected SourceDebugExtension on ${userClass.getName}")
        val s = smap.get
        assert(s.startsWith("SMAP\n"), s"SMAP must start with 'SMAP'; got:\n$s")
        assert(s.contains("a.ssc"),
          s"expected SMAP to reference 'a.ssc'; got:\n$s")
        assert(s.contains(s":$BoomLineInSsc"),
          s"expected SMAP to map back to .ssc line $BoomLineInSsc; got:\n$s")
        // Status output should mention SMAP-carrying modules.
        val stdout = rl.out.text()
        assert(stdout.contains("SMAP") || stdout.contains("SourceDebugExtension"),
          s"expected link stdout to mention SMAP injection; got:\n$stdout")
      finally jar.close()
    finally os.remove.all(sandbox)

  test("SMAP injected by link --source-map is recognised by JVM tools (external scala-cli only)"):
    // ── About this test ─────────────────────────────────────────────────
    //
    // The JVM's default `Throwable.printStackTrace` does NOT honour the
    // JSR-45 `SourceDebugExtension` attribute — it always uses the raw
    // `SourceFile` + `LineNumberTable`.  SMAP is consulted by JVM TI
    // (debuggers, profilers) and IDEs (IntelliJ, VS Code Metals) but
    // not by the default `java -cp` stack-trace dump.
    //
    // We can still validate that:
    //
    //   1. The end-to-end pipeline produces a JAR whose user-code .class
    //      files carry a SourceDebugExtension with a well-formed SMAP.
    //   2. The SMAP's `*L` section translates the `LineNumberTable`
    //      entries (the values we see in `javap -v`) back to the user's
    //      .ssc lines.  We do this by parsing `javap -v` output for the
    //      `LineNumberTable` of the script body and looking up each
    //      generated line in the SMAP's mapping.
    //
    // The in-process Driver path wraps user code in `object <name>_sc {…}`
    // (which lacks a `main(String[])` method).  scala-cli's `.sc` script
    // wrapper synthesises a real main — toggle that path here so we have
    // a JAR with code we can actually decode end-to-end.
    val cli = scala.util.Try {
      os.proc("scala-cli", "--version").call(check = false, stderr = os.Pipe, stdout = os.Pipe)
    }.toOption
    if cli.forall(_.exitCode != 0) then
      cancel("`scala-cli` not on PATH — needed for the stack-trace assertion " +
        "(in-process Driver wrapper has no main method).")

    val sandbox = os.temp.dir(prefix = "ssc-smap-st-")
    try
      // boom-only fixture: top-level statement form that scala-cli's `.sc`
      // wrapper compiles into the script body's `<init>` (vs. block-expr
      // wrappers, which get discarded — a pre-existing quirk of the
      // auto-output emit path).  Counting lines:
      //   1: ---
      //   2: name: a
      //   3: ---
      //   4: (blank)
      //   5: # Module A
      //   6: (blank)
      //   7: ```scalascript
      //   8: def boom(): Int = throw new RuntimeException("kaboom")
      //   9: val x = boom()
      val boomSsc =
        """---
          |name: a
          |---
          |
          |# Module A
          |
          |```scalascript
          |def boom(): Int = throw new RuntimeException("kaboom")
          |val x = boom()
          |```
          |""".stripMargin
      val BoomLineInSscRun = 8
      os.write(sandbox / "a.ssc", boomSsc)
      os.makeDir.all(sandbox / "artifacts")

      val env = Map("SSC_EXTERNAL_SCALA_CLI" -> "1")
      val rc = runSsc(sandbox, env, "compile-jvm", "--bytecode", "a.ssc",
                      "-o", "artifacts/a.scjvm")
      assert(rc.exitCode == 0,
        s"compile-jvm --bytecode failed: exit=${rc.exitCode}\n" +
          s"stdout=${rc.out.text()}\nstderr=${rc.err.text()}")

      val outJar = sandbox / "out.jar"
      val rl = runSsc(sandbox, env, "link", "--backend", "jvm", "--bytecode",
                      "--source-map", "artifacts", "-o", outJar.toString)
      assert(rl.exitCode == 0,
        s"link failed: exit=${rl.exitCode}\n" +
          s"stdout=${rl.out.text()}\nstderr=${rl.err.text()}")

      val stdlib = StagedCliTestSupport.scalaRuntimeClasspath.getOrElse:
        cancel("Scala runtime libraries are not visible to the test JVM")

      val runRes = os.proc("java", "-cp", s"$outJar:$stdlib", "a_sc").call(
        stdin = "", check = false, stderr = os.Pipe, stdout = os.Pipe
      )
      // Process must crash (the script throws); we use the trace to
      // identify the line numbers the JVM blamed.
      assert(runRes.exitCode != 0,
        s"expected the test script to throw, but it exited 0:\nstdout=${runRes.out.text()}")
      val stderr = runRes.err.text()

      // The raw `printStackTrace` output references the synthetic Scala
      // source name (`a.sc` / `a_sc.scala`) with the bytecode's
      // `LineNumberTable` line numbers.  We confirm that — then translate
      // through the SMAP and check the result lands inside the original
      // `.ssc`'s code-block range.
      val genLineRx = """\(a\.sc:(\d+)\)""".r
      val genLines  = genLineRx.findAllMatchIn(stderr).map(_.group(1).toInt).toList
      assert(genLines.nonEmpty,
        s"expected at least one `(a.sc:N)` frame in stack trace; got:\n$stderr")

      // Read the SMAP from the user-code .class entry in out.jar.
      val jar = new JarFile(outJar.toIO)
      val smap =
        try
          val entry = scala.jdk.CollectionConverters
            .EnumerationHasAsScala(jar.entries()).asScala.toList
            .find(e => e.getName == "a$_.class")
            .getOrElse(throw new AssertionError("expected a$_.class in JAR"))
          val bytes = jar.getInputStream(entry).readAllBytes()
          JvmSmapInjector.readSourceDebugExtension(bytes)
            .getOrElse(throw new AssertionError(s"expected SourceDebugExtension on a$$_"))
        finally jar.close()

      // Parse the SMAP's `*L` section into a Map[Int, Int] and look up
      // every generated line that appeared in the trace.  At least one
      // of those must resolve back to a line inside the .ssc's code
      // block range (lines 7..9 — the fence-open through the fence-close).
      val lineSection = smap.linesIterator
        .dropWhile(_ != "*L").drop(1).takeWhile(_ != "*E").toList
      val mapping = lineSection.flatMap { l =>
        val Pat = """(\d+)#1:(\d+)""".r
        l match
          case Pat(g, o) => Some(g.toInt -> o.toInt)
          case _         => None
      }.toMap

      val resolved = genLines.flatMap(mapping.get)
      assert(resolved.nonEmpty,
        s"expected at least one stack-frame line ($genLines) to translate via " +
        s"the SMAP (${mapping}); got nothing")
      assert(resolved.exists(orig => orig >= 7 && orig <= 11),
        s"expected at least one SMAP-resolved line to land inside the .ssc " +
        s"code block (lines 7-11); got resolved=$resolved, mapping=$mapping")
      val _ = BoomLineInSscRun // used in docstring; suppress unused-var warning
    finally os.remove.all(sandbox)
