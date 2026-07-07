package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import java.util.zip.ZipFile
import scala.jdk.CollectionConverters.*

/** v2.0 interop Tier 4 — subprocess smoke tests for
 *  `ssc link --backend jvm --bytecode --emit-scala-facade`.
 *
 *  These tests run the actual `ssc` jar (built via `sbt cli/assembly`)
 *  as a subprocess and inspect the produced JAR's contents.  No
 *  in-process mocking; the JAR-on-disk is the source of truth. */
class EmitScalaFacadeCliTest extends AnyFunSuite:

  // Same jar-discovery pattern as the other CLI subprocess suites.
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

  // `compile-jvm --bytecode` / `link --bytecode` lazily load the Scala compiler
  // jars from `<ssc.lib.path>/bin/lib/compiler/jars` (staged by `sbt installBin`).
  // Plain `java -jar ssc.jar` doesn't set `ssc.lib.path`, so derive it by walking
  // up from the jar to the staged root (same pattern as
  // ClusterMultiBackendMatrixTest.sscLibArgs) — without it the whole happy-path
  // family failed with "CompilerLoader: ssc.lib.path is not set".
  private lazy val sscLibArgs: Seq[String] =
    sscJar match
      case Some(j) =>
        var p: os.Path = j / os.up
        var found: Option[os.Path] = None
        var i = 0
        while found.isEmpty && i < 8 do
          if os.exists(p / "bin" / "lib" / "compiler" / "jars") then found = Some(p)
          if p != p / os.up then p = p / os.up
          i += 1
        found.map(r => s"-Dssc.lib.path=$r").toSeq
      case None => Seq.empty

  private def runSsc(cwd: os.Path, args: String*): os.CommandResult =
    val jar = requireJar()
    val cmd: Seq[os.Shellable] = Seq[os.Shellable]("java") ++
      sscLibArgs.map(a => a: os.Shellable) ++
      Seq[os.Shellable]("-jar", jar.toString) ++
      args.map(a => a: os.Shellable)
    os.proc(cmd).call(cwd = cwd, stdin = "", check = false, stderr = os.Pipe, stdout = os.Pipe)

  private def jarEntries(jar: os.Path): Set[String] =
    val zf = new ZipFile(jar.toIO)
    try zf.entries.asScala.map(_.getName).toSet
    finally zf.close()

  private def readJarEntry(jar: os.Path, name: String): Option[String] =
    val zf = new ZipFile(jar.toIO)
    try
      Option(zf.getEntry(name)).map { e =>
        new String(zf.getInputStream(e).readAllBytes(), "UTF-8")
      }
    finally zf.close()

  // ── Flag-validation: requires --bytecode + --backend jvm ───────────────

  test("--emit-scala-facade without --bytecode is rejected"):
    val sb = os.temp.dir(prefix = "ssc-facade-cli-")
    try
      os.makeDir.all(sb / "arts")
      val r = runSsc(sb, "link", "--backend", "jvm", "--emit-scala-facade", "arts")
      assert(r.exitCode != 0, "should fail without --bytecode")
      assert(r.err.text().contains("--bytecode"),
        s"diagnostic should mention --bytecode; got: ${r.err.text()}")
    finally os.remove.all(sb)

  test("--emit-scala-facade with --backend other-than-jvm is rejected"):
    val sb = os.temp.dir(prefix = "ssc-facade-cli-")
    try
      os.makeDir.all(sb / "arts")
      val r = runSsc(sb, "link", "--backend", "js", "--bytecode", "--emit-scala-facade", "arts")
      assert(r.exitCode != 0, "should fail with --backend js")
    finally os.remove.all(sb)

  // ── Happy path: META-INF/.scim resources land in the JAR ──────────────

  test("link --emit-scala-facade embeds .scim files at META-INF/scalascript/"):
    val sb = os.temp.dir(prefix = "ssc-facade-cli-")
    try
      val sscFile = sb / "demo.ssc"
      os.write(sscFile,
        """---
          |name: demo
          |package: demo.a
          |---
          |
          |# Demo
          |
          |```scalascript
          |def add(x: Int, y: Int): Int = x + y
          |def double(x: Int): Int = x * 2
          |```
          |""".stripMargin)

      val artDir = sb / "arts"
      os.makeDir.all(artDir)

      val r1 = runSsc(sb, "emit-interface", "demo.ssc", "-o", "arts/demo.scim")
      assert(r1.exitCode == 0, s"emit-interface failed: ${r1.err.text()}")

      val r2 = runSsc(sb, "compile-jvm", "--bytecode", "demo.ssc",
        "-o", "arts/demo.scjvm")
      assert(r2.exitCode == 0, s"compile-jvm failed: ${r2.err.text()}")

      val jar = sb / "lib.jar"
      val r3 = runSsc(sb, "link", "--backend", "jvm", "--bytecode",
        "--emit-scala-facade", "arts", "-o", jar.toString)
      assert(r3.exitCode == 0,
        s"link --emit-scala-facade failed: ${r3.err.text()}")
      assert(os.exists(jar), s"output JAR not produced at $jar")

      val entries = jarEntries(jar)
      assert(entries.contains("META-INF/scalascript/demo.scim"),
        s"META-INF/scalascript/demo.scim missing; entries:\n" +
        entries.filter(_.contains("META-INF")).mkString("\n"))
    finally os.remove.all(sb)

  test("embedded .scim has the same content as the source .scim"):
    val sb = os.temp.dir(prefix = "ssc-facade-cli-")
    try
      val sscFile = sb / "demo.ssc"
      os.write(sscFile,
        """---
          |name: demo
          |package: demo.a
          |---
          |
          |# Demo
          |
          |```scalascript
          |def add(x: Int, y: Int): Int = x + y
          |```
          |""".stripMargin)
      os.makeDir.all(sb / "arts")
      assert(runSsc(sb, "emit-interface", "demo.ssc", "-o", "arts/demo.scim").exitCode == 0)
      assert(runSsc(sb, "compile-jvm", "--bytecode", "demo.ssc", "-o", "arts/demo.scjvm").exitCode == 0)
      val jar = sb / "lib.jar"
      assert(runSsc(sb, "link", "--backend", "jvm", "--bytecode",
        "--emit-scala-facade", "arts", "-o", jar.toString).exitCode == 0)

      val onDisk = os.read(sb / "arts" / "demo.scim")
      val inJar  = readJarEntry(jar, "META-INF/scalascript/demo.scim").get
      assert(inJar == onDisk,
        s"embedded .scim should byte-match the source; lengths ${inJar.length} vs ${onDisk.length}")
    finally os.remove.all(sb)

  // ── Multi-module link embeds every .scim ──────────────────────────────

  test("multi-module link embeds every .scim file at META-INF"):
    val sb = os.temp.dir(prefix = "ssc-facade-cli-")
    try
      for (n <- List("a", "b", "c")) do
        os.write(sb / s"$n.ssc",
          s"""---
             |name: $n
             |package: demo.$n
             |---
             |
             |# $n
             |
             |```scalascript
             |def f$n(): Int = 1
             |```
             |""".stripMargin)
      os.makeDir.all(sb / "arts")
      for (n <- List("a", "b", "c")) do
        assert(runSsc(sb, "emit-interface", s"$n.ssc", "-o", s"arts/$n.scim").exitCode == 0)
        assert(runSsc(sb, "compile-jvm", "--bytecode", s"$n.ssc", "-o", s"arts/$n.scjvm").exitCode == 0)

      val jar = sb / "lib.jar"
      val r = runSsc(sb, "link", "--backend", "jvm", "--bytecode",
        "--emit-scala-facade", "arts", "-o", jar.toString)
      assert(r.exitCode == 0, s"link failed: ${r.err.text()}")

      val entries = jarEntries(jar)
      val embeddedScims = entries.filter(_.startsWith("META-INF/scalascript/"))
      assert(embeddedScims == Set(
        "META-INF/scalascript/a.scim",
        "META-INF/scalascript/b.scim",
        "META-INF/scalascript/c.scim"
      ), s"expected three embedded .scim; got: $embeddedScims")
    finally os.remove.all(sb)

  // ── Stdout reports facade activity ────────────────────────────────────

  test("link reports facade resource count in its summary line"):
    val sb = os.temp.dir(prefix = "ssc-facade-cli-")
    try
      os.write(sb / "demo.ssc",
        """---
          |name: demo
          |package: demo.x
          |---
          |
          |# Demo
          |
          |```scalascript
          |def f(): Int = 1
          |```
          |""".stripMargin)
      os.makeDir.all(sb / "arts")
      assert(runSsc(sb, "emit-interface", "demo.ssc", "-o", "arts/demo.scim").exitCode == 0)
      assert(runSsc(sb, "compile-jvm", "--bytecode", "demo.ssc", "-o", "arts/demo.scjvm").exitCode == 0)
      val r = runSsc(sb, "link", "--backend", "jvm", "--bytecode",
        "--emit-scala-facade", "arts", "-o", "lib.jar")
      val combined = r.out.text() + r.err.text()
      assert(combined.contains("META-INF/.scim resource"),
        s"summary should report META-INF resource count; got: $combined")
    finally os.remove.all(sb)

  // ── Tier 5 — `package:` clause emission lands user code in a real
  //  Scala package, so the JAR carries `demo/a/...class` entries that a
  //  Scala consumer can `import demo.a.*` against directly.  This pins
  //  the on-disk layout so the consumer-side import contract doesn't
  //  silently regress to the empty-package wrap.

  test("Tier 5 — `package: demo.a` produces JAR entries under demo/a/"):
    val sb = os.temp.dir(prefix = "ssc-facade-cli-")
    try
      os.write(sb / "demo.ssc",
        """---
          |name: demo
          |package: demo.a
          |---
          |
          |# Demo
          |
          |```scalascript
          |def add(x: Int, y: Int): Int = x + y
          |```
          |""".stripMargin)
      os.makeDir.all(sb / "arts")
      assert(runSsc(sb, "compile-jvm", "--bytecode", "demo.ssc",
        "-o", "arts/demo.scjvm").exitCode == 0)
      val jar = sb / "lib.jar"
      val r = runSsc(sb, "link", "--backend", "jvm", "--bytecode",
        "arts", "-o", jar.toString)
      assert(r.exitCode == 0, s"link failed: ${r.err.text()}")
      val entries = jarEntries(jar)
      // Real package directory hierarchy:
      assert(entries.exists(_.startsWith("demo/a/")),
        s"expected demo/a/* JAR entries; got: ${entries.filter(_.startsWith("demo")).take(10).mkString(", ")}")
      // Scala 3 cross-compile metadata must ship alongside the .class:
      assert(entries.exists(e => e.startsWith("demo/a/") && e.endsWith(".tasty")),
        s"expected demo/a/*.tasty for Scala 3 consumers; got: ${entries.filter(_.startsWith("demo/a/")).mkString(", ")}")
    finally os.remove.all(sb)

