package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import scalascript.backend.spi.CompileResult

/** Smoke test for the `examples` directory.
 *
 *  1. **Lint** — runnable scala must live inside a ` ```scalascript ` fence. A
 *     fenceless `.ssc` is prose by design, so `ssc run` silently does nothing;
 *     this catches the "raw scala outside any fence" mistake (which shipped on 9
 *     examples, undetected, because nothing exercised them).
 *  2. **Run** — a few pure-core, dependency-free examples actually execute and
 *     exit 0 via the in-process interpreter, so a silently-broken example fails CI.
 */
class ExamplesSmokeTest extends AnyFunSuite:

  private val repoRoot: os.Path =
    var cur = os.pwd
    while !(os.exists(cur / "build.sbt") && os.exists(cur / "examples")) && cur != (cur / os.up) do
      cur = cur / os.up
    cur
  private val examplesDir = repoRoot / "examples"

  // Patterns matching the STRUCTURAL shape of a real scala statement/definition —
  // tight enough that ordinary markdown prose starting with the same English word
  // ("import in the source…", "case class lifts into…") does NOT match. A match
  // OUTSIDE a fence/front-matter is code that will silently never run.
  private val scalaPatterns: List[scala.util.matching.Regex] = List(
    """^\s*import\s+[\w]+(\.[\w*{}, ]+)*\s*$""".r,   // import a.b.*  (qualified, ends the line)
    """^\s*package\s+[\w.]+\s*$""".r,
    """^\s*(val|var)\s+\w+\s*[:=]""".r,              // val x = / var x:
    """^\s*def\s+\w+\s*[(\[:]""".r,                  // def f( / def f[ / def f:
    """^\s*(object|trait|enum)\s+[A-Z]\w*""".r,      // object Foo / trait Bar
    """^\s*case\s+class\s+[A-Z]\w*\s*[(\[]""".r,     // case class Foo(
    """^\s*given\s+[\w\[(]""".r,
    """^\s*extension\s*\(""".r,
    """^\s*type\s+[A-Z]\w*\s*[=\[]""".r,
    """^\s*println\(""".r
  )
  private def looksLikeScala(line: String): Boolean =
    scalaPatterns.exists(_.findFirstIn(line).isDefined)

  /** Scan one example for scala statements that sit outside any fence and outside
   *  the leading YAML front-matter. Returns the first offending (lineNo, text). */
  private def firstUnfencedScala(content: String): Option[(Int, String)] =
    val lines = content.linesIterator.toArray
    var i = 0
    // Skip a shebang.
    if i < lines.length && lines(i).startsWith("#!") then i += 1
    // Skip a leading `---` … `---` front-matter block.
    if i < lines.length && lines(i).trim == "---" then
      i += 1
      while i < lines.length && lines(i).trim != "---" do i += 1
      if i < lines.length then i += 1 // consume closing ---
    var inFence = false
    while i < lines.length do
      val line = lines(i)
      if line.trim.startsWith("```") then inFence = !inFence
      else if !inFence && looksLikeScala(line) then
        return Some((i + 1, line.trim))
      i += 1
    None

  test("every examples/*.ssc keeps runnable scala inside a ```scalascript fence"):
    val sscFiles = os.list(examplesDir).filter(p => p.ext == "ssc").sortBy(_.last)
    assert(sscFiles.nonEmpty, s"no examples found under $examplesDir")
    val offenders = sscFiles.flatMap { f =>
      firstUnfencedScala(os.read(f)).map { case (ln, txt) => s"${f.last}:$ln  $txt" }
    }
    assert(offenders.isEmpty,
      "these examples have runnable scala OUTSIDE a ```scalascript fence (so `ssc run` " +
      "silently does nothing — wrap the code in a fence):\n  " + offenders.mkString("\n  "))

  /** Curated set of CORE-interpreter examples that run end-to-end in-process and exit 0:
   *  no network, no DB, no GUI/browser, no external service, no build step, and — because
   *  the `cli` test classpath carries only the core interpreter — **no std plugin**.
   *
   *  Excluded on purpose:
   *   - plugin-backed examples (crypto / uuid / spark / typed-data / pdf+email): they need
   *     a plugin that isn't on the `cli` test classpath (they exit 1 here even though the
   *     bundled `bin/ssc` fat jar runs them). Each is already covered by its own plugin
   *     test suite (`CryptoPluginTest`, `UuidPluginTest`, …).
   *   - examples needing CLI args, a server/socket, a real browser (`@js`/IndexedDB/WASM),
   *     a Spark/maven build, or an undefined provider — they can't run headlessly.
   *
   *  We assert only `exit == 0` (not captured stdout): the in-process interpreter writes
   *  directly to the real `System.out`, so `Executed.stdout` is empty by construction. The
   *  fence-lint test above is what catches the "parsed away as prose" silent no-op.
   *
   *  Expanded from 2 → 22 on 2026-06-13 to give the 180-example corpus real regression
   *  coverage (previously only the fence-lint ran on the whole corpus). */
  private val runnableExamples: List[String] = List(
    "hello.ssc", "script.ssc", "recursion.ssc", "functional.ssc", "enums.ssc",
    "extensions.ssc", "typeclass.ssc", "lenses.ssc", "generators.ssc",
    "default-params.ssc", "data-types.ssc", "imports.ssc", "index.ssc",
    "custom-derives-mirror.ssc", "quoted-macro-interpreter.ssc",
    // quoted-macro-constfold exercises `Expr.asValue match` const-folding on the
    // interpreter run path (literal arg → Some branch; `${ }` splice unwraps Expr).
    "quoted-macro-constfold.ssc",
    "lang-split.ssc",
    "content.ssc", "signals-demo.ssc", "storage-demo.ssc",
    "graph-storage-interpreter.ssc", "dataset-parallel-sum.ssc", "dataset-stats.ssc",
    // durable-save-run exercises the same-process save/run idiom: a multi-shot effect's
    // captured continuation is run several times while the prefix executes exactly once.
    "durable-save-run.ssc",
    // typed-data exercises `foreach(println)` end-to-end through Normalize (the bare
    // `println` rewrite that used to break with "Not callable: ()").
    "typed-data.ssc"
    // NOTE: algebraic-effects.ssc was MOVED to PluginExamplesSmokeTest (core-min). It exercises
    // runLogger/runState/runRandomSeeded/runClockAt/runEnvWith, which are now bundled PLUGINS
    // (extracted from interpreter core), so it needs the plugin classpath this cli smoke test
    // deliberately lacks. Keeping it here failed at runtime with "Undefined: runState".
  )

  test("curated core examples run and exit 0 (no silent no-op)"):
    val failures = runnableExamples.flatMap { name =>
      val f = examplesDir / name
      if !os.exists(f) then Some(s"$name: missing")
      else
        try compileViaBackend("int", f) match
          case CompileResult.Executed(_, _, exit) =>
            if exit != 0 then Some(s"$name: exit $exit") else None
          case other => Some(s"$name: did not Execute → $other")
        catch case e: Throwable => Some(s"$name: threw ${e.getClass.getSimpleName}: ${e.getMessage}")
    }
    assert(failures.isEmpty,
      s"${failures.size}/${runnableExamples.size} curated examples failed to run cleanly:\n  " +
      failures.mkString("\n  "))
