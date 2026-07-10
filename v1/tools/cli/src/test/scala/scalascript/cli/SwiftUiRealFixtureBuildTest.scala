package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

/** End-to-end regression for `swiftui-legacy-real-harness`: a REAL, parsed `.ssc` file —
 *  not a hand-built Scala `View` literal — must produce a Swift package that `swift build`
 *  actually compiles.
 *
 *  Before this slice, `bin/ssc build --target macos` failed on ANY real `.ssc` module using
 *  `std/ui` (27-29 generated-Scala errors headed by unresolved bare `View`/`EventHandler`):
 *  every prior SwiftUI test (`SwiftUIEmitterTest`, `SwiftUIDashboardSmokeTest`, ...)
 *  hand-constructs `View` trees directly in Scala, bypassing `Parser.parse`/`JvmGen` entirely,
 *  so none of them ever exercised this path for real — or needed `std/ui` import resolution
 *  at all (`ExamplesSmokeTest` deliberately runs only dependency-free examples for the same
 *  reason). `-Dssc.lib.path` is normally set by the `bin/ssc` launcher script, not by `sbt
 *  test`, so this test sets it itself before compiling.
 *
 *  Gated on a Swift toolchain (`assume(swiftAvailable)`) so a box without Xcode/swift skips
 *  cleanly — mirrors `RustGenCargoSmokeTest`'s `assume(cargoAvailable)` gate, which caught a
 *  real move/borrow bug that the Rust backend's string-match-only tests missed. Kept out of
 *  the fast string-match path: an actual `swift build` costs real wall-clock time. */
class SwiftUiRealFixtureBuildTest extends AnyFunSuite:

  private val repoRoot: os.Path =
    var cur = os.pwd
    while !(os.exists(cur / "build.sbt") && os.exists(cur / "examples")) && cur != (cur / os.up) do
      cur = cur / os.up
    cur

  private def swiftAvailable: Boolean =
    try os.proc("swift", "--version").call(check = false).exitCode == 0
    catch case _: Throwable => false

  test("examples/frontend/ios-hello/ios-hello.ssc builds a real Swift package via swift build") {
    assume(swiftAvailable, "swift not on PATH — skipping end-to-end SwiftUI native build")

    // ssc.lib.path = repoRoot itself, matching bin/ssc's launcher (`-Dssc.lib.path="$_SSC_ROOT"`,
    // _SSC_ROOT = dirname(bin/)) — ImportResolver falls back to <libPath>/runtime/<path> for
    // std/* imports (repoRoot/runtime is a symlink to v1/runtime), AND buildSwiftUIPackage's own
    // jarsDir lookup needs <libPath>/bin/lib/jars, which only exists at the true repo root.
    val savedLibPath = sys.props.get("ssc.lib.path")
    sys.props("ssc.lib.path") = repoRoot.toString
    val sscFile = repoRoot / "examples" / "frontend" / "ios-hello" / "ios-hello.ssc"
    assert(os.exists(sscFile), s"fixture missing: $sscFile")

    val outDir = os.temp.dir(prefix = "ssc-swiftui-real-fixture-")
    try
      buildSwiftUIPackage(sscFile, outDir, platform = "macos", runSwiftBuild = true)

      val packageSwift = outDir / "Package.swift"
      assert(os.exists(packageSwift), "Package.swift was not written")
      val contentView = os.walk(outDir).find(_.last == "ContentView.swift")
      assert(contentView.isDefined, "ContentView.swift was not written")
      assert(os.read(contentView.get).contains("struct ContentView"), "ContentView.swift is not valid generated Swift")

      val built = os.walk(outDir / ".build").exists(p => os.isFile(p) && p.last == "Ioshello")
      assert(built, "swift build did not produce the Ioshello executable")
    finally
      scala.util.Try(os.remove.all(outDir))
      savedLibPath match
        case Some(v) => sys.props("ssc.lib.path") = v
        case None    => sys.props.remove("ssc.lib.path")
  }
