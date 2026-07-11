package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import java.util.concurrent.TimeUnit

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
        os.makeDir.all(out / "AppleApp" / "Resources")
        os.write(out / "AppleApp" / "Resources" / "user.txt", "owned by user")
        val emitted = SwiftV2Cli.emit(nativeUiExample, out, SwiftPlatform.MacOS)
        assert(emitted.debugCli == "appcore_nativeuiCli")
        assert(emitted.xcodeApp.exists(_.appProduct == "appcore_nativeui.app"))
        assert(os.exists(out / "Sources" / "AppCore" / "NativeUiHost.swift"))
        assert(os.exists(out / "Sources" / emitted.debugCli / "main.swift"))
        assert(os.exists(out / "appcore_nativeui.xcodeproj" / "project.pbxproj"))
        assert(os.read(out / "appcore_nativeui.xcodeproj" / "project.pbxproj").contains("user.txt in Resources"))
        assert(SwiftV2Cli.runPackage(emitted, Nil, "run-swift") == 0)
      finally os.remove.all(out)
    }

  test("checked top-level metadata forces SwiftUI mode and rejects nested or empty bundle ids"):
    withLibPath {
      val root = os.temp.dir(prefix = "ssc-v2-swift-metadata-")
      try
        val good = root / "good.ssc"
        os.write(good,
          "---\nname: Meta App\nfrontend: swiftui\nbundle-id: com.scalascript.meta\nversion: 1.2\nbuild-version: 9\n---\n```scalascript\nprintln(\"meta\")\n```\n")
        val emitted = SwiftV2Cli.emit(good, root / "good-out", SwiftPlatform.MacOS)
        assert(emitted.xcodeApp.exists(_.bundleId == "com.scalascript.meta"))

        val nested = root / "nested.ssc"
        os.write(nested,
          "---\nname: Nested\nfrontend: swiftui\nconfig:\n  bundle-id: com.scalascript.nested\n---\n```scalascript\nprintln(\"nested\")\n```\n")
        val nestedError = intercept[IllegalArgumentException](
          SwiftV2Cli.emit(nested, root / "nested-out", SwiftPlatform.MacOS))
        assert(nestedError.getMessage.contains("requires front-matter bundle-id"))

        val empty = root / "empty.ssc"
        os.write(empty,
          "---\nname: Empty\nfrontend: swiftui\nbundle-id:\n---\n```scalascript\nprintln(\"empty\")\n```\n")
        val emptyError = intercept[IllegalArgumentException](
          SwiftV2Cli.emit(empty, root / "empty-out", SwiftPlatform.MacOS))
        assert(emptyError.getMessage.contains("bundle-id"))
        assert(!os.exists(root / "nested-out") && !os.exists(root / "empty-out"))
      finally os.remove.all(root)
    }

  test("checked NativeUi Xcode artifact builds verifies and bounded-launches macOS plus iOS simulator"):
    assume(xcodeAvailable, "Xcode is not available")
    withLibPath {
      val out = os.temp.dir(prefix = "ssc-v2-swift-xcode-cli-")
      try
        os.makeDir.all(out / "AppleApp" / "Resources")
        os.write(out / "AppleApp" / "Resources" / "user-e2e.txt", "preserved resource")
        val emitted = SwiftV2Cli.emit(nativeUiExample, out, SwiftPlatform.MacOS)
        val second = out / "second"
        os.makeDir.all(second / "AppleApp" / "Resources")
        os.write(second / "AppleApp" / "Resources" / "user-e2e.txt", "preserved resource")
        SwiftV2Cli.emit(nativeUiExample, second, SwiftPlatform.MacOS)
        assert(generatedFiles(out).filterNot(_._1.startsWith("second/")) == generatedFiles(second))
        val listing = os.proc("xcodebuild", "-list", "-json", "-project", emitted.xcodeApp.get.project)
          .call(cwd = out, stdout = os.Pipe, stderr = os.Pipe)
        val project = ujson.read(listing.out.text())("project")
        assert(project("targets").arr.map(_.str).toVector == Vector("appcore_nativeui"))
        assert(project("schemes").arr.map(_.str).toVector == Vector("appcore_nativeui"))
        val mac = SwiftV2Cli.buildXcodeApplication(
          emitted, "platform=macOS", out / "derived-mac", "test macOS app")
        assert(mac.bundle.ext == "app" && os.exists(mac.executable))
        assert(os.read(mac.bundle / "Contents" / "Resources" / "user-e2e.txt") == "preserved resource")
        assertFrozenSettings(mac.buildSettings, "appcore_nativeui")
        val process = new ProcessBuilder(mac.executable.toString).start()
        try
          Thread.sleep(1000)
          assert(process.isAlive, "macOS application exited before bounded smoke interval")
        finally
          process.destroy()
          if !process.waitFor(2, TimeUnit.SECONDS) then
            process.destroyForcibly()
            assert(process.waitFor(2, TimeUnit.SECONDS), "macOS application ignored forced bounded termination")

        val (udid, _) = pickIosSimulator().getOrElse(fail("installed iOS simulator is unavailable"))
        val iosEmitted = SwiftV2Cli.emit(nativeUiExample, out / "ios", SwiftPlatform.IOS)
        val ios = SwiftV2Cli.buildXcodeApplication(
          iosEmitted, s"platform=iOS Simulator,id=$udid", out / "derived-ios", "test iOS app")
        assert(ios.bundle.ext == "app")
        assertFrozenSettings(ios.buildSettings, "appcore_nativeui")
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

  private def assertFrozenSettings(settings: Map[String, String], product: String): Unit =
    assert(settings("TARGET_NAME") == product)
    assert(settings("FULL_PRODUCT_NAME") == s"$product.app")
    assert(settings("PRODUCT_BUNDLE_IDENTIFIER") == "com.scalascript.appcore-nativeui")
    assert(settings("INFOPLIST_KEY_CFBundleDisplayName") == "appcore-nativeui")
    assert(settings("MARKETING_VERSION") == "1.0.0")
    assert(settings("CURRENT_PROJECT_VERSION") == "1")
    assert(settings("IPHONEOS_DEPLOYMENT_TARGET") == "16.0")
    assert(settings("MACOSX_DEPLOYMENT_TARGET") == "13.0")
    assert(settings("SUPPORTED_PLATFORMS") == "iphoneos iphonesimulator macosx")
    assert(settings("SUPPORTS_MACCATALYST") == "NO")
    assert(settings("SWIFT_VERSION") == "6.0")
    assert(settings("GENERATE_INFOPLIST_FILE") == "YES")
    assert(settings.get("DEVELOPMENT_TEAM").forall(_.isEmpty))

  private def swiftAvailable: Boolean =
    try os.proc("swift", "--version").call(check = false, stdout = os.Pipe, stderr = os.Pipe).exitCode == 0
    catch case _: Throwable => false

  private def xcodeAvailable: Boolean =
    try os.proc("xcodebuild", "-version").call(check = false, stdout = os.Pipe, stderr = os.Pipe).exitCode == 0
    catch case _: Throwable => false

  private def withLibPath[A](body: => A): A =
    val previous = sys.props.get("ssc.lib.path")
    sys.props("ssc.lib.path") = repoRoot.toString
    try body
    finally
      previous match
        case Some(value) => sys.props("ssc.lib.path") = value
        case None => sys.props.remove("ssc.lib.path")
