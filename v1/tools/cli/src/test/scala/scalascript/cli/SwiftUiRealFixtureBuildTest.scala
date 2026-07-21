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
 *  reason). The test invokes the staged `bin/ssc-tools` launcher in a subprocess, exactly where
 *  `ssc.lib.path` and the installed compiler/runtime JAR layout are part of the supported contract.
 *
 *  Gated on an actual `swiftc -typecheck` of `import SwiftUI`, not merely a Swift binary: Linux
 *  distributions include `swift`/`swiftc` without Apple's SwiftUI SDK. This mirrors
 *  `RustGenCargoSmokeTest`'s capability gate, which caught a real move/borrow bug that the Rust
 *  backend's string-match-only tests missed. Kept out of the fast string-match path: an actual
 *  `swift build` costs real wall-clock time. */
class SwiftUiRealFixtureBuildTest extends AnyFunSuite:

  private val repoRoot: os.Path =
    var cur = os.pwd
    while !(os.exists(cur / "build.sbt") && os.exists(cur / "examples")) && cur != (cur / os.up) do
      cur = cur / os.up
    cur

  private def swiftModuleCapability(moduleName: String): Either[String, Unit] =
    val probeDir = os.temp.dir(prefix = "ssc-swift-module-probe-")
    try
      val probe = probeDir / "Probe.swift"
      os.write(probe, s"import $moduleName\n")
      try
        val result = os.proc("swiftc", "-typecheck", probe).call(check = false)
        Either.cond(
          result.exitCode == 0,
          (),
          s"swiftc cannot import $moduleName:\n${diagnostics(result)}")
      catch
        case error: Throwable =>
          Left(s"swiftc cannot probe import $moduleName: ${error.getMessage}")
    finally
      scala.util.Try(os.remove.all(probeDir))

  private def requireSwiftUi(): Unit =
    swiftModuleCapability("SwiftUI") match
      case Right(()) => ()
      case Left(reason) => cancel(s"SwiftUI compiler capability unavailable — skipping native build:\n$reason")

  private def requireLauncher(): os.Path =
    StagedCliTestSupport.toolsLauncher.getOrElse:
      cancel("bin/ssc-tools not found — run `sbt cli/assembly installBin` first")

  private def packageMacos(launcher: os.Path, sscFile: os.Path, outDir: os.Path): os.CommandResult =
    StagedCliTestSupport.runTools(
      launcher,
      repoRoot,
      args = Seq(
        "package", "--v1", "--target", "macos", "--out", outDir.toString,
        sscFile.toString))

  private def diagnostics(result: os.CommandResult): String =
    s"exit=${result.exitCode}\nstdout:\n${result.out.text()}\nstderr:\n${result.err.text()}"

  test("Swift module capability probe rejects an unavailable module") {
    val missing = "ScalaScriptDefinitelyMissingSwiftModule"
    swiftModuleCapability(missing) match
      case Left(reason) => assert(reason.contains(missing), reason)
      case Right(()) => fail(s"swiftc unexpectedly imported deliberately missing module $missing")
  }

  test("examples/frontend/ios-hello/ios-hello.ssc builds a real Swift package via swift build") {
    requireSwiftUi()
    assume(JvmBytecode.scalaCliAvailable, "scala-cli not on PATH — needed for the --bytecode SwiftUI build")
    val launcher = requireLauncher()
    val sscFile = repoRoot / "examples" / "frontend" / "ios-hello" / "ios-hello.ssc"
    assert(os.exists(sscFile), s"fixture missing: $sscFile")

    val outDir = os.temp.dir(prefix = "ssc-swiftui-real-fixture-")
    try
      val result = packageMacos(launcher, sscFile, outDir)
      assert(result.exitCode == 0, s"staged SwiftUI package failed:\n${diagnostics(result)}")

      val packageDir = outDir / "macos"
      val packageSwift = packageDir / "Package.swift"
      assert(os.exists(packageSwift), "Package.swift was not written")
      val contentView = os.walk(packageDir).find(_.last == "ContentView.swift")
      assert(contentView.isDefined, "ContentView.swift was not written")
      assert(os.read(contentView.get).contains("struct ContentView"), "ContentView.swift is not valid generated Swift")

      val built = os.walk(packageDir / ".build").exists(p => os.isFile(p) && p.last == "Ioshello")
      assert(built, "swift build did not produce the Ioshello executable")
    finally
      scala.util.Try(os.remove.all(outDir))
  }

  test("generated-Scala failure is captured by the staged SwiftUI subprocess") {
    assume(JvmBytecode.scalaCliAvailable, "scala-cli not on PATH — needed for the --bytecode SwiftUI build")
    val launcher = requireLauncher()
    val fixture = repoRoot / "examples" / "frontend" / "ios-hello" / "ios-hello.ssc"
    val brokenSource = os.read(fixture).replace(
      "serve(lower(view(), defaultTheme), 0)",
      "serve(thisSymbolMustNotCompile(), 0)")
    assert(brokenSource != os.read(fixture), "failed to construct the deliberate compiler-error fixture")

    val sandbox = os.temp.dir(prefix = "ssc-swiftui-failing-fixture-")
    try
      val brokenFile = sandbox / "broken-ios-hello.ssc"
      os.write(brokenFile, brokenSource)
      val result = packageMacos(launcher, brokenFile, sandbox / "out")
      assert(result.exitCode != 0, s"deliberately invalid fixture unexpectedly packaged:\n${diagnostics(result)}")
      val output = result.out.text() + "\n" + result.err.text()
      assert(
        output.contains("thisSymbolMustNotCompile") || output.contains("Compilation failed"),
        s"compiler failure returned no actionable diagnostic:\n${diagnostics(result)}")
    finally
      scala.util.Try(os.remove.all(sandbox))
  }
