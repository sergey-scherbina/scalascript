package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

/** Regression for JvmGen transitive `package:` (std) imports.
 *
 *  A Markdown import lists only *value* bindings, so a dependency that uses a
 *  sibling `package:` module's TYPE in a signature — the busi shape, where
 *  `ledger.ssc` writes `def sumBySide(...): Map[String, Money]` while importing
 *  only `money`/`plus`/… from `std/money.ssc` — used to emit Scala with an
 *  unresolved type, and, when the std dep was reached *transitively* through a
 *  relative dep, no `import` line for it at all.
 *
 *  The fix (a) propagates `importedPkgs`/type-exports to nested JvmGen
 *  instances so the transitive import still resolves its package, and (b) pulls
 *  the dep's capitalised exports into the generated import so the TYPE resolves.
 *
 *  Driven through the real `ssc` jar (mirrors `V2RealStdModulesTest`) so std
 *  resolution behaves exactly as in production.
 */
class JvmTransitiveStdImportCliTest extends AnyFunSuite:

  private val sscJar: Option[os.Path] =
    val cwd = os.pwd
    val rel = os.RelPath("target") / "scala-3.8.3" / "ssc.jar"
    val roots = List(
      cwd / "tools" / "cli",   // sbt running from repo root
      cwd / "cli",             // alternate layout
      cwd,                     // sbt running from the cli subproject
      cwd / os.up / "tools" / "cli",
      cwd / os.up)
    roots.map(_ / rel).find(os.exists)

  // A plain (no-package) dep that imports the real std/money — listing only
  // VALUE names — yet uses the `Money` TYPE in its signatures.  `main` imports
  // only this helper (never std/money directly), mirroring busi's structure
  // where `ledger.ssc` is the only thing a driver imports.
  private val helperMod =
    """---
      |name: busi-like-helper
      |exports:
      |  - mkUsd
      |  - sumUsd
      |  - showUsd
      |---
      |# helper
      |[money, plus, formatMoney](std/money.ssc)
      |```scalascript
      |def mkUsd(s: String): Money = money(s, "USD")
      |def sumUsd(xs: List[Money]): Money = xs.foldLeft(money("0", "USD"))(plus)
      |def showUsd(m: Money): String = formatMoney(m)
      |```
      |""".stripMargin

  private val mainMod =
    """---
      |name: busi-like-main
      |version: 0.1.0
      |---
      |# main
      |[mkUsd, sumUsd, showUsd](helper.ssc)
      |```scalascript
      |println(showUsd(sumUsd(List(mkUsd("10.00"), mkUsd("5.50")))))
      |```
      |""".stripMargin

  test("emit-scala resolves a TYPE used transitively through a relative dep"):
    val jar = sscJar.getOrElse(cancel("ssc.jar not found — run `sbt cli/assembly` first"))
    val dir = os.temp.dir(prefix = "ssc-transitive-std-")
    os.write(dir / "helper.ssc", helperMod)
    os.write(dir / "main.ssc", mainMod)
    val r = os.proc("java", "-jar", jar.toString, "emit-scala", "main.ssc")
      .call(cwd = dir, check = false, stdin = os.Path("/dev/null"), stderr = os.Pipe, stdout = os.Pipe)
    assert(r.exitCode == 0, s"emit-scala failed:\n${r.err.text()}")
    val scala = r.out.text()
    // The std/money dep is inlined and the generated import carries the TYPE
    // `Money` (not just the value bindings the Markdown import listed).
    assert(scala.contains("import std.money."), "no std.money import emitted")
    assert(scala.contains("Money"), "Money type not pulled into import")

  test("emitted Scala compiles & runs identically to the interpreter"):
    val jar = sscJar.getOrElse(cancel("ssc.jar not found — run `sbt cli/assembly` first"))
    val hasScalaCli =
      try os.proc("scala-cli", "--version").call(check = false).exitCode == 0
      catch case _: Throwable => false
    assume(hasScalaCli, "scala-cli not available")
    val dir = os.temp.dir(prefix = "ssc-transitive-std-run-")
    os.write(dir / "helper.ssc", helperMod)
    os.write(dir / "main.ssc", mainMod)

    // The default `run` reaches the native frontend, which reads its staged layout
    // from `ssc.lib.path` — the property `bin/ssc` sets and a bare `java -jar` does
    // not. Derive the repo root from the jar (`<root>/v1/tools/cli/target/scala-3.8.3/
    // ssc.jar`) so the subprocess finds the installBin-staged tree, exactly as CI
    // does (`cli/assembly installBin`). Without it the native front exits 1 with
    // "native frontend requires a staged installation".
    val stagedRoot = jar / os.up / os.up / os.up / os.up / os.up / os.up
    val interp = os.proc("java", s"-Dssc.lib.path=$stagedRoot", "-jar", jar.toString, "run", "main.ssc")
      .call(cwd = dir, check = false, stdin = os.Path("/dev/null"), stderr = os.Pipe, stdout = os.Pipe)
    assert(interp.exitCode == 0, s"interpreter run failed:\n${interp.err.text()}")

    val emit = os.proc("java", "-jar", jar.toString, "emit-scala", "main.ssc")
      .call(cwd = dir, check = false, stdin = os.Path("/dev/null"), stderr = os.Pipe, stdout = os.Pipe)
    assert(emit.exitCode == 0, s"emit-scala failed:\n${emit.err.text()}")
    val scriptFile = dir / "main.sc"
    os.write(scriptFile, "//> using scala 3.8.3\n" + emit.out.text())
    val compiled = os.proc("scala-cli", "run", "--server=false", scriptFile.toString)
      .call(cwd = dir, check = false, stdin = os.Path("/dev/null"), stderr = os.Pipe, stdout = os.Pipe)
    assert(compiled.exitCode == 0, s"scala-cli failed:\n${compiled.err.text()}")

    assert(compiled.out.text().trim == interp.out.text().trim,
      s"compiled != interpreter:\n  jvm=${compiled.out.text().trim}\n  int=${interp.out.text().trim}")
    assert(interp.out.text().trim == "$15.50", s"unexpected output: ${interp.out.text().trim}")
