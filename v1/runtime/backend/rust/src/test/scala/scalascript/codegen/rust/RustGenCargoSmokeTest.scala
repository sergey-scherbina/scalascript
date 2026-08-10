package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize
import org.scalatest.funsuite.AnyFunSuite

/** End-to-end `cargo run` smoke for the Rust backend.
 *
 *  The rest of the `backendRust` suite is **string-match only** — it asserts the *shape*
 *  of generated Rust but never compiles it, so a whole class of bugs (move/borrow errors,
 *  type mismatches, missing trait bounds in *valid-looking* generated Rust) is invisible.
 *  That gap already shipped `rust-index-read-moves-noncopy` (E0507: an index read on a
 *  `Vec<String>` moved out of the `Index`), caught only by an ad-hoc `cargo run`.
 *
 *  This suite closes that gap: it emits feature-exercising `.ssc` programs to a temp crate
 *  and runs them through `cargo`, asserting real stdout. It is **gated on a Rust toolchain**
 *  (`assume(cargoAvailable)`) so a CI box without `cargo` skips cleanly, and kept OUT of the
 *  fast string-match path because each `cargo` build costs seconds.
 *
 *  BACKLOG: `rust-backend-cargo-smoke-coverage`. */
class RustGenCargoSmokeTest extends AnyFunSuite:

  private val opts = BackendOptions(
    baseDir = None, outputDir = None,
    optimizationLevel = 0, emitSourceMaps = false, emitAssertions = false,
    target = None, extra = Map("binName" -> "smoke")
  )

  /** `cargo` on PATH? Detected by running `cargo --version` — `backendRust` does not depend
   *  on the CLI module (where `RustToolchain.findCargo` lives), so we probe directly. */
  private def cargoAvailable: Boolean =
    try os.proc("cargo", "--version").call(check = false).exitCode == 0
    catch case _: Throwable => false

  /** Compile `src` to a Rust crate, write it to a temp dir, `cargo run` it, return stdout
   *  lines (trimmed). stderr is kept separate so cargo's compile warnings don't pollute the
   *  program's output. Cleans up the temp crate on success. */
  private def runCrate(src: String): List[String] =
    val assets = new RustBackend().compile(Normalize(Parser.parse(src)), opts) match
      case CompileResult.Segmented(segs) => segs.collect { case a: Segment.Asset => a }
      case other                         => fail(s"expected Segmented, got $other")
    assert(assets.nonEmpty, "backend produced no assets")

    val crateDir = os.temp.dir(prefix = "ssc-rust-smoke-")
    for a <- assets do
      val out = crateDir / os.RelPath(a.name)
      os.makeDir.all(out / os.up)
      os.write.over(out, a.bytes)

    val res = os.proc("cargo", "run", "--quiet").call(cwd = crateDir, check = false)
    if res.exitCode != 0 then
      fail(s"cargo run failed (exit ${res.exitCode}) — generated Rust did not compile:\n${res.err.text()}")
    val lines = res.out.text().trim.linesIterator.toList
    os.remove.all(crateDir)
    lines

  test("collection + string lowerings compile and run end-to-end via cargo"):
    assume(cargoAvailable, "cargo not on PATH — skipping end-to-end Rust smoke")
    val lines = runCrate(
      """```scalascript
        |def roomLineName(l: String): String = l.trim
        |def roomStatusLines(): List[String] = List("demo", "rozum")
        |def roomNames(): List[String] = roomStatusLines().map(roomLineName).toList
        |
        |@main def run(): Unit =
        |  // Vec ops — take/drop/takeRight/dropRight reduce via the generalized .sum
        |  println(List(10, 20, 30, 40).take(2).sum)        // 30
        |  println(List(10, 20, 30, 40).drop(2).sum)        // 70
        |  println(List(10, 20, 30, 40).takeRight(2).sum)   // 70
        |  println(List(10, 20, 30, 40).dropRight(2).sum)   // 30
        |  println(List(10, 20, 30, 40).sum)                // 100
        |  println(List(1, 1, 2, 2, 3).distinct.sum)        // 6  (first occurrence wins)
        |  println(List(3, 1, 2).sorted.take(1).sum)        // 1  (ascending sort → head)
        |  // String ops — &str patterns
        |  println("a,b,c".replace(",", "-"))               // a-b-c
        |  println("hello".startsWith("he"))                // true
        |  println("hello".endsWith("lo"))                  // true
        |  println("hello".contains("ell"))                 // true
        |  // indexable split/toList on a Vec<String> — the index READ must clone (E0507 regression)
        |  val parts: List[String] = "a,b,c".split(",").toList
        |  println(parts(1))                                // b
        |  println("abc".toList.size)                       // 3  (String.toList → Vec<char>)
        |  // Function reference over a named List-returning def must stay Vec.map,
        |  // not the Either.map arm (`Either::...` without an Either enum).
        |  println(roomNames().mkString("|"))                // demo|rozum
        |```
        |""".stripMargin
    )
    assert(lines == List(
      "30", "70", "70", "30", "100", "6", "1",
      "a-b-c", "true", "true", "true", "b", "3", "demo|rozum"
    ), s"unexpected program output:\n${lines.mkString("\n")}")

  /** `try`/`catch` and `throw`, COMPILED AND RUN — the only evidence that matters for this pair.
   *
   *  A string-match test can confirm `catch_unwind` appears; it cannot confirm the closure borrows
   *  legally, that `AssertUnwindSafe` is actually required where it is written, or that the payload
   *  downcast finds the `String` a `panic!("{}", …)` really produces. Each of those is a compile or
   *  run failure in Rust that valid-LOOKING generated code hides, which is this suite's stated
   *  reason for existing. */
  test("try/catch and throw compile and run end-to-end via cargo"):
    assume(cargoAvailable, "cargo not on PATH — skipping end-to-end Rust smoke")
    val lines = runCrate(
      """```scalascript
        |def risky(fail: Boolean): String =
        |  if fail then throw new RuntimeException("bang") else "fine"
        |
        |def guarded(fail: Boolean): String =
        |  try risky(fail)
        |  catch case e: Throwable => "caught:" + e
        |
        |def ignored(): String =
        |  try throw "boom"
        |  catch case _ => "recovered"
        |
        |@main def run(): Unit =
        |  println(guarded(false))   // fine        — the Ok arm returns the body's value
        |  println(guarded(true))    // caught:bang — the payload came back as its message
        |  println(ignored())        // recovered   — a wildcard arm discards the payload
        |```""".stripMargin)
    assert(lines == List("fine", "caught:bang", "recovered"), lines)

  // A typed pattern does not narrow its arm's binding on this lane, so `l.length` and
  // `m.get(k)` are emitted ON the `Value` — which is why this belongs in the cargo suite and
  // not the string-match one: the generated code LOOKED right and simply had no such method.
  // Reported from rozum as `typed-pattern-does-not-narrow`.
  test("len/get on a value reached through a typed pattern compile and run end-to-end via cargo"):
    assume(cargoAvailable, "cargo not on PATH — skipping end-to-end Rust smoke")
    val lines = runCrate(
      """```scalascript
        |[jsonParse](std/json.ssc)
        |
        |def size(v: Any): Int =
        |  v match
        |    case l: List[Any] => l.length
        |    case _            => 0
        |
        |def key(v: Any): String =
        |  v match
        |    case m: Map[String, Any] => m.get("k").map(x => x.toString).getOrElse("-")
        |    case _                   => "-"
        |
        |@main def run(): Unit =
        |  println(size(jsonParse("[1,2,3]")))   // 3 — Value::len over the List arm
        |  println(key(jsonParse("{\"k\":\"v\"}")))  // v — Value::get over the Map arm
        |```""".stripMargin)
    assert(lines == List("3", "v"), lines)
