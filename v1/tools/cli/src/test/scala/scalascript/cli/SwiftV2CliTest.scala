package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

import _root_.ssc.swift.SwiftPlatform

final class SwiftV2CliTest extends AnyFunSuite:
  private val repoRoot: os.Path =
    Iterator.iterate(os.pwd)(_ / os.up)
      .find(path => os.exists(path / "build.sbt") && os.exists(path / "tests" / "conformance"))
      .getOrElse(fail("cannot locate repository root"))

  private val money = repoRoot / "tests" / "conformance" / "money-portable-v2.ssc"
  private val appcoreExample = repoRoot / "examples" / "swift" / "appcore-money.ssc"
  private val nativeUiExample = repoRoot / "examples" / "swift" / "appcore-nativeui.ssc"

  test("emit writes deterministic v2 AppCore packages for macOS and iOS"):
    withLibPath {
      val macos = os.temp.dir(prefix = "ssc-v2-swift-macos-")
      val ios = os.temp.dir(prefix = "ssc-v2-swift-ios-")
      try
        val first = SwiftV2Cli.emit(money, macos, SwiftPlatform.MacOS)
        val firstFiles = generatedFiles(macos)
        val second = SwiftV2Cli.emit(money, macos, SwiftPlatform.MacOS)
        assert(first == second)
        assert(generatedFiles(macos) == firstFiles)
        assert(os.read(macos / "Package.swift").contains("platforms: [.macOS(.v13)]"))
        assert(os.exists(macos / "Sources" / "AppCore" / "GeneratedProgram.swift"))
        assert(!os.walk(macos).exists(_.last == "ContentView.swift"))

        SwiftV2Cli.emit(money, ios, SwiftPlatform.IOS)
        assert(os.read(ios / "Package.swift").contains("platforms: [.iOS(.v16)]"))
      finally
        os.remove.all(macos)
        os.remove.all(ios)
    }

  test("build accepts v2 lane flags in positional-independent order"):
    withLibPath {
      val out = os.temp.dir(prefix = "ssc-v2-swift-build-")
      try
        new BuildCmd().run(List(money.toString, "--v2", "--out", out.toString, "--target", "macos"))
        val pkg = out / "macos" / "Package.swift"
        assert(os.exists(pkg))
        assert(os.read(pkg).contains("platforms: [.macOS(.v13)]"))
        assert(os.exists(out / "macos" / "Sources" / "AppCore" / "SscRuntime.swift"))
      finally os.remove.all(out)
    }

  test("real build command threads and normalizes --server-url into Swift NativeUi"):
    withLibPath {
      val out = os.temp.dir(prefix = "ssc-v2-swift-base-")
      try
        new BuildCmd().run(List(
          nativeUiExample.toString,
          "--v2",
          "--target", "macos",
          "--out", out.toString,
          "--server-url", "https://api.example.com/v1"))
        val generated = os.read(out / "macos" / "Sources" / "AppCore" / "GeneratedProgram.swift")
        assert(generated.contains("https://api.example.com/v1/"))
      finally os.remove.all(out)
    }

  test("run package uses the real Swift toolchain"):
    assume(swiftAvailable, "swift not on PATH")
    withLibPath {
      val out = os.temp.dir(prefix = "ssc-v2-swift-run-")
      try
        val emitted = SwiftV2Cli.emit(appcoreExample, out, SwiftPlatform.MacOS)
        assert(SwiftV2Cli.runPackage(emitted, Nil, "run-swift") == 0)
      finally os.remove.all(out)
    }

  test("NativeUi package uses and runs the dedicated debug CLI product"):
    assume(swiftAvailable, "swift not on PATH")
    withLibPath {
      val out = os.temp.dir(prefix = "ssc-v2-swift-nativeui-")
      try
        val emitted = SwiftV2Cli.emit(nativeUiExample, out, SwiftPlatform.MacOS)
        assert(emitted.debugCli == "appcore_nativeuiCli")
        assert(emitted.xcodeApp.exists(_.appProduct == "appcore_nativeui.app"))
        assert(os.exists(out / "Sources" / "AppCore" / "NativeUiHost.swift"))
        assert(os.exists(out / "Sources" / emitted.debugCli / "main.swift"))
        assert(os.exists(out / "appcore_nativeui.xcodeproj" / "project.pbxproj"))
        assert(SwiftV2Cli.runPackage(emitted, Nil, "run-swift") == 0)
      finally os.remove.all(out)
    }

  test("missing Swift diagnostic is bounded and corrective"):
    val previous = sys.props.get("ssc.swift.command")
    sys.props("ssc.swift.command") = "/definitely/missing/swift"
    try
      val error = intercept[IllegalStateException](SwiftV2Cli.requireSwift("run-swift"))
      assert(error.getMessage ==
        "run-swift: swift not found on PATH. Run: ssc toolchain check --target macos")
    finally
      previous match
        case Some(value) => sys.props("ssc.swift.command") = value
        case None => sys.props.remove("ssc.swift.command")

  private def generatedFiles(root: os.Path): Vector[(String, String)] =
    os.walk(root)
      .filter(os.isFile)
      .map(path => path.relativeTo(root).toString -> os.read(path))
      .toVector
      .sortBy(_._1)

  private def swiftAvailable: Boolean =
    try os.proc("swift", "--version").call(check = false, stdout = os.Pipe, stderr = os.Pipe).exitCode == 0
    catch case _: Throwable => false

  private def withLibPath[A](body: => A): A =
    val previous = sys.props.get("ssc.lib.path")
    sys.props("ssc.lib.path") = repoRoot.toString
    try body
    finally
      previous match
        case Some(value) => sys.props("ssc.lib.path") = value
        case None => sys.props.remove("ssc.lib.path")
