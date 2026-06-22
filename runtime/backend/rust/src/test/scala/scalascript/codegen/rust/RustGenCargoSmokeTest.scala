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
        |```
        |""".stripMargin
    )
    assert(lines == List(
      "30", "70", "70", "30", "100", "6", "1",
      "a-b-c", "true", "true", "true", "b", "3"
    ), s"unexpected program output:\n${lines.mkString("\n")}")
