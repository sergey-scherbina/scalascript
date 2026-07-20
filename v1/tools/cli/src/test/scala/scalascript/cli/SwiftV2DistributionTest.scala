package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

import _root_.ssc.swift.{SwiftPlatform, XcodeAppArtifact}

final class SwiftV2DistributionTest extends AnyFunSuite:
  // `plutil` is a macOS-only tool. The tests below inspect real .plist files (directly
  // or via SwiftV2Cli/SwiftV2Distribution, which shell out to it), so on a Linux runner
  // they die with `Cannot run program "plutil"` — which surfaced as red CI once the
  // fork-death that had swallowed the result was fixed. Gate them the same way
  // SwiftUiRealFixtureBuildTest gates `assume(swiftAvailable)` and RustGenCargoSmokeTest
  // gates `assume(cargoAvailable)`: skip cleanly where the macOS toolchain is absent.
  // iOS/xcarchive packaging is inherently macOS, so nothing meaningful is lost on Linux.
  // The pure-logic tests (argv/team/export-method) do NOT read plists and stay ungated.
  private def plutilAvailable: Boolean =
    try { os.proc("plutil").call(check = false); true }
    catch case _: Throwable => false

  private val app = XcodeAppArtifact(
    project = "Sentinel.xcodeproj",
    scheme = "Sentinel",
    target = "Sentinel",
    appProduct = "Sentinel.app",
    bundleId = "com.scalascript.sentinel",
    displayName = "Sentinel",
    marketingVersion = "1.0.0",
    buildVersion = "1",
  )

  test("signed archive argv pins project scheme release destination paths and team"):
    val root = os.Path("/tmp/ssc-dist-sentinel", os.pwd)
    val args = SwiftV2Cli.archiveArguments(
      app, "generic/platform=iOS", root / "Sentinel.xcarchive", root / "derived", "TEAM123")
    assert(args == List(
      "archive",
      "-project", "Sentinel.xcodeproj",
      "-scheme", "Sentinel",
      "-configuration", "Release",
      "-destination", "generic/platform=iOS",
      "-derivedDataPath", (root / "derived").toString,
      "-archivePath", (root / "Sentinel.xcarchive").toString,
      "-allowProvisioningUpdates",
      "DEVELOPMENT_TEAM=TEAM123",
    ))
    assert(!args.exists(_.contains("Cli")))

  test("team and export method authority is explicit and canonical"):
    assert(SwiftV2Distribution.resolveTeam(Some(" CLI9 "), Map("SSC_TEAM_ID" -> "ENV1"), "cmd") == "CLI9")
    assert(SwiftV2Distribution.resolveTeam(None, Map("SSC_TEAM_ID" -> "ENV1"), "cmd") == "ENV1")
    intercept[IllegalArgumentException](SwiftV2Distribution.resolveTeam(None, Map.empty, "cmd"))
    intercept[IllegalArgumentException](SwiftV2Distribution.resolveTeam(Some("bad team"), Map.empty, "cmd"))
    assert(SwiftV2Distribution.normalizeExportMethod("development", "cmd") == "debugging")
    assert(SwiftV2Distribution.normalizeExportMethod("ad-hoc", "cmd") == "release-testing")
    assert(SwiftV2Distribution.normalizeExportMethod("app-store", "cmd") == "app-store-connect")
    assert(SwiftV2Distribution.normalizeExportMethod("enterprise", "cmd") == "enterprise")
    intercept[IllegalArgumentException](SwiftV2Distribution.normalizeExportMethod("legacy", "cmd"))
    assert(SwiftV2Distribution.parseNotaryTimeout(None, "cmd") == 900)
    assert(SwiftV2Distribution.parseNotaryTimeout(Some("3600"), "cmd") == 3600)
    for raw <- List("abc", "0", "3601") do
      val error = intercept[IllegalArgumentException](
        SwiftV2Distribution.parseNotaryTimeout(Some(raw), "cmd"))
      assert(error.getMessage == "cmd: --notary-timeout-seconds must be an integer in 1..3600")

  test("export options are canonical automatic and contain no empty profile map"):
    assume(plutilAvailable)
    val xml = SwiftV2Distribution.exportOptionsPlist("app-store-connect", "TEAM123")
    assert(xml.contains("<key>method</key><string>app-store-connect</string>"))
    assert(xml.contains("<key>destination</key><string>export</string>"))
    assert(xml.contains("<key>signingStyle</key><string>automatic</string>"))
    assert(xml.contains("<key>manageAppVersionAndBuildNumber</key><false/>"))
    assert(xml.contains("<key>teamID</key><string>TEAM123</string>"))
    assert(!xml.contains("provisioningProfiles"))
    val file = os.temp(xml, suffix = ".plist")
    val lint = os.proc("plutil", "-lint", file).call(check = false, stdout = os.Pipe, stderr = os.Pipe)
    assert(lint.exitCode == 0, lint.err.text())

  test("archive ApplicationPath is relative contained and traversal is rejected"):
    assume(plutilAvailable)
    val archive = os.temp.dir(prefix = "ssc-archive-") / "Sentinel.xcarchive"
    os.makeDir.all(archive / "Products" / "Applications" / "Sentinel.app")
    writeArchiveInfo(archive, "Applications/Sentinel.app")
    assert(SwiftV2Cli.archivedApplication(archive, "test") ==
      archive / "Products" / "Applications" / "Sentinel.app")
    writeArchiveInfo(archive, "../Outside.app")
    val traversal = intercept[IllegalStateException](SwiftV2Cli.archivedApplication(archive, "test"))
    assert(traversal.getMessage.contains("invalid xcarchive ApplicationPath"))
    writeArchiveInfo(archive, "/tmp/Outside.app")
    val absolute = intercept[IllegalStateException](SwiftV2Cli.archivedApplication(archive, "test"))
    assert(absolute.getMessage.contains("invalid xcarchive ApplicationPath"))
    os.remove(archive / "Info.plist")
    val missing = intercept[IllegalStateException](SwiftV2Cli.archivedApplication(archive, "test"))
    assert(missing.getMessage.contains("ApplicationPath missing"))
    os.write(archive / "Info.plist", "not-a-plist")
    val malformed = intercept[IllegalStateException](SwiftV2Cli.archivedApplication(archive, "test"))
    assert(malformed.getMessage.contains("ApplicationPath missing"))

  test("fresh export selection rejects zero and duplicate IPA app and PKG artifacts"):
    for extension <- List("ipa", "app", "pkg") do
      val root = os.temp.dir(prefix = s"ssc-export-$extension-")
      intercept[IllegalStateException](SwiftV2Distribution.uniqueArtifact(root, extension, "test"))
      val one = root / s"one.$extension"
      if extension == "app" then os.makeDir(one) else os.write(one, "one")
      assert(SwiftV2Distribution.uniqueArtifact(root, extension, "test") == one)
      val two = root / s"two.$extension"
      if extension == "app" then os.makeDir(two) else os.write(two, "two")
      val duplicate = intercept[IllegalStateException](
        SwiftV2Distribution.uniqueArtifact(root, extension, "test"))
      assert(duplicate.getMessage.contains("found 2"))

  test("shared app verifier rejects wrong bundle and debug CLI executable"):
    assume(plutilAvailable)
    val root = os.temp.dir(prefix = "ssc-app-verify-")
    val bundle = root / "Sentinel.app"
    os.makeDir.all(bundle)
    val emitted = SwiftV2Cli.EmittedPackage(root, "SentinelCli", SwiftPlatform.IOS, Some(app))
    writeAppInfo(bundle, "com.example.wrong", "Sentinel")
    os.write(bundle / "Sentinel", "")
    val wrongBundle = intercept[IllegalStateException](
      SwiftV2Cli.verifyAppBundle(emitted, bundle, Map.empty, "test"))
    assert(wrongBundle.getMessage.contains("not the expected application"))
    writeAppInfo(bundle, app.bundleId, "SentinelCli")
    os.write(bundle / "SentinelCli", "")
    val debugCli = intercept[IllegalStateException](
      SwiftV2Cli.verifyAppBundle(emitted, bundle, Map.empty, "test"))
    assert(debugCli.getMessage.contains("not the expected application"))

  test("synthetic archives reject wrong bundle and debug CLI application"):
    assume(plutilAvailable)
    val root = os.temp.dir(prefix = "ssc-archive-identity-")
    val archive = root / "Sentinel.xcarchive"
    val bundle = archive / "Products" / "Applications" / "Sentinel.app"
    os.makeDir.all(bundle)
    writeArchiveInfo(archive, "Applications/Sentinel.app")
    val emitted = SwiftV2Cli.EmittedPackage(root, "SentinelCli", SwiftPlatform.IOS, Some(app))
    writeAppInfo(bundle, "com.example.wrong", "Sentinel")
    os.write(bundle / "Sentinel", "")
    val wrongBundle = intercept[IllegalStateException](
      SwiftV2Cli.verifyAppBundle(
        emitted, SwiftV2Cli.archivedApplication(archive, "archive test"), Map.empty, "archive test"))
    assert(wrongBundle.getMessage.contains("not the expected application"))
    writeAppInfo(bundle, app.bundleId, "SentinelCli")
    os.write(bundle / "SentinelCli", "")
    val debugCli = intercept[IllegalStateException](
      SwiftV2Cli.verifyAppBundle(
        emitted, SwiftV2Cli.archivedApplication(archive, "archive test"), Map.empty, "archive test"))
    assert(debugCli.getMessage.contains("not the expected application"))

  test("shared app verifier selects bundle layout strictly from platform"):
    val root = os.temp.dir(prefix = "ssc-app-platform-layout-")
    val bundle = root / "Sentinel.app"
    os.makeDir.all(bundle / "Contents" / "MacOS")
    writeAppInfo(bundle / "Contents", app.bundleId, "Sentinel")
    os.write(bundle / "Contents" / "MacOS" / "Sentinel", "")
    val ios = SwiftV2Cli.EmittedPackage(root, "SentinelCli", SwiftPlatform.IOS, Some(app))
    val iosError = intercept[IllegalStateException](
      SwiftV2Cli.verifyAppBundle(ios, bundle, Map.empty, "ios layout"))
    assert(iosError.getMessage.contains("Xcode application product missing"))

    os.write(bundle / "Info.plist", os.read(bundle / "Contents" / "Info.plist"))
    os.write(bundle / "Sentinel", "")
    os.remove.all(bundle / "Contents")
    val mac = SwiftV2Cli.EmittedPackage(root, "SentinelCli", SwiftPlatform.MacOS, Some(app))
    val macError = intercept[IllegalStateException](
      SwiftV2Cli.verifyAppBundle(mac, bundle, Map.empty, "mac layout"))
    assert(macError.getMessage.contains("Xcode application product missing"))

  test("device export and notary plans preserve exact verified artifact inputs"):
    val root = os.Path("/tmp/ssc-dist-plan", os.pwd)
    assert(SwiftV2Distribution.iosDeployArguments(root / "Sentinel.app", Some("DEVICE"), console = false) ==
      List("--bundle", (root / "Sentinel.app").toString, "--no-wifi", "--id", "DEVICE", "--justlaunch"))
    assert(SwiftV2Distribution.exportArguments(root / "a.xcarchive", root / "out", root / "opts.plist") ==
      List("-exportArchive", "-archivePath", (root / "a.xcarchive").toString,
        "-exportPath", (root / "out").toString,
        "-exportOptionsPlist", (root / "opts.plist").toString, "-allowProvisioningUpdates"))
    assert(SwiftV2Distribution.notaryArguments(root / "app.zip", "profile", 900) ==
      List("notarytool", "submit", (root / "app.zip").toString, "--wait", "--timeout", "900s",
        "--output-format", "json", "--no-progress", "--keychain-profile", "profile"))

  test("generated Fastfile uploads explicit IPA or PKG and never invokes gym"):
    val source = SwiftV2Distribution.generatedFastfile
    assert(!source.contains("gym("))
    assert(source.contains("lane :testflight") && source.contains("lane :appstore"))
    assert(source.contains("lane :mac_appstore"))
    assert(source.contains("pilot(ipa: ENV.fetch(\"SSC_IPA_PATH\")"))
    assert(source.contains("deliver(ipa: ENV.fetch(\"SSC_IPA_PATH\")"))
    assert(source.contains("deliver(pkg: ENV.fetch(\"SSC_PKG_PATH\")"))
    assert(source.count(_ == '\n') > 5)
    assert(source.sliding("app_identifier: ENV.fetch(\"SSC_BUNDLE_ID\")".length)
      .count(_ == "app_identifier: ENV.fetch(\"SSC_BUNDLE_ID\")") == 3)
    assert(source.contains("changelog: ENV[\"SSC_RELEASE_NOTES\"]"))
    val hostile = "quotes \" backslash \\ cr\r\nline\nПривіт"
    val root = os.Path("/tmp/ssc-fastlane-env", os.pwd)
    val emitted = SwiftV2Cli.EmittedPackage(root, "SentinelCli", SwiftPlatform.IOS, Some(app))
    val context = SwiftV2Distribution.Context(emitted, app, root)
    val env = SwiftV2Distribution.fastlaneEnvironment(
      context, root / "Sentinel.ipa", root / "key.json", Some(hostile), submitForReview = true)
    assert(env("SSC_RELEASE_NOTES") == hostile)
    assert(!source.contains(hostile))
    assert(env("SSC_IPA_PATH").endsWith("Sentinel.ipa"))
    assert(!env.contains("SSC_PKG_PATH"))
    if commandAvailable("ruby") then
      val file = os.temp(source, suffix = ".rb")
      val syntax = os.proc("ruby", "-c", file).call(check = false, stdout = os.Pipe, stderr = os.Pipe)
      assert(syntax.exitCode == 0, syntax.err.text())

  test("API key JSON preflight is regular valid JSON and CLI wins"):
    val root = os.temp.dir(prefix = "ssc-api-key-")
    val cli = root / "cli.json"
    val env = root / "env.json"
    os.write(cli, "{\"key_id\":\"CLI\",\"key\":\"SECRET\"}")
    os.write(env, "{\"key_id\":\"ENV\",\"issuer_id\":\"ISS\",\"key\":\"SECRET\"}")
    assert(SwiftV2Distribution.requireApiKey(
      Some(cli.toString), Map("APP_STORE_CONNECT_API_KEY_PATH" -> env.toString), "cmd") == cli)
    val bad = root / "bad.json"
    os.write(bad, "not-json")
    intercept[IllegalArgumentException](
      SwiftV2Distribution.requireApiKey(Some(bad.toString), Map.empty, "cmd"))
    val incomplete = root / "incomplete.json"
    os.write(incomplete, "{\"key_id\":\"ONLY\"}")
    intercept[IllegalArgumentException](
      SwiftV2Distribution.requireApiKey(Some(incomplete.toString), Map.empty, "cmd"))

  test("missing distribution tools fail during bounded preflight"):
    val missing = "/definitely/missing/tool"
    withProperties(Map("ssc.xcodebuild.command" -> missing)) {
      assert(intercept[IllegalStateException](SwiftV2Distribution.requireXcodebuild("cmd"))
        .getMessage.contains("xcodebuild"))
    }
    withProperties(Map("ssc.fastlane.command" -> missing)) {
      assert(intercept[IllegalStateException](SwiftV2Distribution.requireFastlane("cmd"))
        .getMessage.contains("fastlane"))
    }
    withProperties(Map("ssc.ios-deploy.command" -> missing)) {
      assert(intercept[IllegalStateException](SwiftV2Distribution.requireIosDeploy("cmd"))
        .getMessage.contains("ios-deploy"))
    }
    val trueTool = "/usr/bin/true"
    withProperties(Map(
      "ssc.xcodebuild.command" -> trueTool,
      "ssc.codesign.command" -> trueTool,
    )) {
      val profile = intercept[IllegalArgumentException](
        SwiftV2Distribution.preflightMacDistribution(
          notarize = true, dmg = false, None, "cmd"))
      assert(profile.getMessage ==
        "cmd: --notary-profile or SSC_NOTARY_KEYCHAIN_PROFILE is required")
    }
    for (property, purpose, notarize, dmg) <- List(
      ("ssc.codesign.command", "codesign", false, false),
      ("ssc.ditto.command", "ditto", true, false),
      ("ssc.xcrun.command", "notarytool", true, false),
      ("ssc.hdiutil.command", "hdiutil", false, true),
    ) do
      val props = Map(
        "ssc.xcodebuild.command" -> trueTool,
        "ssc.codesign.command" -> trueTool,
        "ssc.ditto.command" -> trueTool,
        "ssc.xcrun.command" -> trueTool,
        "ssc.hdiutil.command" -> trueTool,
        property -> missing,
      )
      withProperties(props) {
        val error = intercept[IllegalStateException](
          SwiftV2Distribution.preflightMacDistribution(
            notarize, dmg, Some("profile"), "cmd"))
        assert(error.getMessage.contains(purpose), error.getMessage)
      }

  test("fake archive failure is bounded before export"):
    val root = os.temp.dir(prefix = "ssc-fake-xcode-failure-")
    val fake = executable(root / "xcodebuild",
      """|#!/bin/sh
         |set -eu
         |if [ "$1" = "-version" ]; then exit 0; fi
         |if [ "$1" = "-showBuildSettings" ]; then
         |  echo '    TARGET_NAME = Sentinel'
         |  echo '    FULL_PRODUCT_NAME = Sentinel.app'
         |  echo '    TARGET_BUILD_DIR = /tmp/unused'
         |  exit 0
         |fi
         |if [ "$1" = "archive" ]; then exit 23; fi
         |exit 64
         |""".stripMargin)
    withProperties(Map("ssc.xcodebuild.command" -> fake.toString)) {
      val emitted = SwiftV2Cli.EmittedPackage(root, "SentinelCli", SwiftPlatform.IOS, Some(app))
      val context = SwiftV2Distribution.Context(emitted, app, root)
      val error = intercept[IllegalStateException](
        SwiftV2Distribution.packageIos(context, "debugging", "TEAM123", "fake failure"))
      assert(error.getMessage.contains("xcodebuild archive failed (exit 23)"))
      assert(!os.exists(root / "ipa"))
    }

  test("fake Xcode runner preserves archive verification and unique IPA handoff"):
    assume(plutilAvailable)
    val root = os.temp.dir(prefix = "ssc-fake-xcode-")
    val fake = root / "xcodebuild"
    val log = root / "argv.log"
    os.write(fake, fakeXcodeScript(log))
    os.perms.set(fake, "rwxr--r--")
    val previous = sys.props.get("ssc.xcodebuild.command")
    sys.props("ssc.xcodebuild.command") = fake.toString
    try
      val emitted = SwiftV2Cli.EmittedPackage(root, "SentinelCli", SwiftPlatform.IOS, Some(app))
      val context = SwiftV2Distribution.Context(emitted, app, root)
      val ipa = SwiftV2Distribution.packageIos(context, "app-store", "TEAM123", "fake package")
      assert(ipa == root / "ipa" / "Sentinel.ipa")
      val argv = os.read(log)
      assert(argv.contains("archive -project Sentinel.xcodeproj -scheme Sentinel"))
      assert(argv.contains("-destination generic/platform=iOS"))
      assert(argv.contains("DEVELOPMENT_TEAM=TEAM123"))
      assert(argv.contains("-exportArchive -archivePath"))
      assert(!argv.contains("SentinelCli"))
    finally
      previous match
        case Some(value) => sys.props("ssc.xcodebuild.command") = value
        case None => sys.props.remove("ssc.xcodebuild.command")

  test("fake signed device chain deploys only the verified Xcode app"):
    assume(plutilAvailable)
    val root = os.temp.dir(prefix = "ssc-fake-device-")
    val log = root / "argv.log"
    val xcode = executable(root / "xcodebuild", fakeXcodeScript(log))
    val deploy = executable(root / "ios-deploy", fakeToolScript(log))
    withProperties(Map(
      "ssc.xcodebuild.command" -> xcode.toString,
      "ssc.ios-deploy.command" -> deploy.toString,
    )) {
      val emitted = SwiftV2Cli.EmittedPackage(root, "SentinelCli", SwiftPlatform.IOS, Some(app))
      val context = SwiftV2Distribution.Context(emitted, app, root)
      SwiftV2Distribution.runIosDevice(
        context, "TEAM123", Some("DEVICE1"), console = false, "fake device")
      val argv = os.read(log)
      assert(argv.contains("build -project Sentinel.xcodeproj -scheme Sentinel"))
      assert(argv.contains("DEVELOPMENT_TEAM=TEAM123"))
      assert(argv.contains("--bundle") && argv.contains("Sentinel.app"))
      assert(argv.contains("--id DEVICE1 --justlaunch"))
      assert(!argv.contains("SentinelCli"))
    }

  test("fake Developer ID chain preserves notarize and DMG toggles"):
    assume(plutilAvailable)
    val root = os.temp.dir(prefix = "ssc-fake-macdist-")
    val log = root / "argv.log"
    val xcode = executable(root / "xcodebuild", fakeXcodeScript(log, "app", "macos"))
    val fake = executable(root / "tool", fakeToolScript(log))
    val props = Map(
      "ssc.xcodebuild.command" -> xcode.toString,
      "ssc.codesign.command" -> fake.toString,
      "ssc.ditto.command" -> fake.toString,
      "ssc.xcrun.command" -> fake.toString,
      "ssc.hdiutil.command" -> fake.toString,
    )
    withProperties(props) {
      val emitted = SwiftV2Cli.EmittedPackage(root, "SentinelCli", SwiftPlatform.MacOS, Some(app))
      val context = SwiftV2Distribution.Context(emitted, app, root)
      SwiftV2Distribution.preflightMacDistribution(
        notarize = true, dmg = true, Some("profile"), "fake mac")
      val result = SwiftV2Distribution.packageMacDeveloperId(
        context, "TEAM123", notarize = true, dmg = true, Some("profile"), 900, "fake mac")
      assert(result.app.last == "Sentinel.app" && result.dmg.exists(_.last == "Sentinel.dmg"))
      val argv = os.read(log)
      assert(argv.contains("--verify --deep --strict"))
      assert(argv.contains("-c -k --keepParent"))
      assert(argv.contains("notarytool submit") && argv.contains("--keychain-profile profile"))
      assert(argv.contains("stapler staple") && argv.contains("stapler validate"))
      assert(argv.contains("create -volname Sentinel -srcfolder"))
    }

    val noNotaryRoot = os.temp.dir(prefix = "ssc-fake-macplain-")
    val noNotaryLog = noNotaryRoot / "argv.log"
    val noNotaryXcode = executable(
      noNotaryRoot / "xcodebuild", fakeXcodeScript(noNotaryLog, "app", "macos"))
    val noNotaryTool = executable(noNotaryRoot / "tool", fakeToolScript(noNotaryLog))
    withProperties(Map(
      "ssc.xcodebuild.command" -> noNotaryXcode.toString,
      "ssc.codesign.command" -> noNotaryTool.toString,
      "ssc.ditto.command" -> "/definitely/missing/ditto",
      "ssc.xcrun.command" -> "/definitely/missing/xcrun",
      "ssc.hdiutil.command" -> "/definitely/missing/hdiutil",
    )) {
      val emitted = SwiftV2Cli.EmittedPackage(
        noNotaryRoot, "SentinelCli", SwiftPlatform.MacOS, Some(app))
      val context = SwiftV2Distribution.Context(emitted, app, noNotaryRoot)
      SwiftV2Distribution.preflightMacDistribution(
        notarize = false, dmg = false, None, "fake mac")
      val result = SwiftV2Distribution.packageMacDeveloperId(
        context, "TEAM123", notarize = false, dmg = false, None, 900, "fake mac")
      assert(result.dmg.isEmpty)
      val argv = os.read(noNotaryLog)
      assert(!argv.contains("notarytool") && !argv.contains("stapler") && !argv.contains("create -volname"))
    }

  test("fake Developer ID notarization and DMG toggles are independent"):
    assume(plutilAvailable)
    for (notarize, dmg) <- List(true -> false, false -> true) do
      val root = os.temp.dir(prefix = s"ssc-fake-mac-${notarize}-${dmg}-")
      val log = root / "argv.log"
      val xcode = executable(root / "xcodebuild", fakeXcodeScript(log, "app", "macos"))
      val fake = executable(root / "tool", fakeToolScript(log))
      withProperties(Map(
        "ssc.xcodebuild.command" -> xcode.toString,
        "ssc.codesign.command" -> fake.toString,
        "ssc.ditto.command" -> fake.toString,
        "ssc.xcrun.command" -> fake.toString,
        "ssc.hdiutil.command" -> fake.toString,
      )) {
        val emitted = SwiftV2Cli.EmittedPackage(root, "SentinelCli", SwiftPlatform.MacOS, Some(app))
        val context = SwiftV2Distribution.Context(emitted, app, root)
        val profile = Option.when(notarize)("profile")
        SwiftV2Distribution.preflightMacDistribution(notarize, dmg, profile, "fake mac")
        val result = SwiftV2Distribution.packageMacDeveloperId(
          context, "TEAM123", notarize, dmg, profile, 900, "fake mac")
        assert(result.dmg.isDefined == dmg)
        val argv = os.read(log)
        assert(argv.contains("notarytool submit") == notarize)
        assert(argv.contains("stapler staple") == notarize)
        assert(argv.contains("create -volname") == dmg)
      }

  test("fake Mac App Store export and fastlane consume one explicit PKG"):
    assume(plutilAvailable)
    val root = os.temp.dir(prefix = "ssc-fake-macstore-")
    val log = root / "argv.log"
    val envLog = root / "env.log"
    val xcode = executable(root / "xcodebuild", fakeXcodeScript(log, "pkg", "macos"))
    val fastlane = executable(root / "fastlane", fakeFastlaneScript(envLog))
    val key = root / "key.json"
    os.write(key, "{\"key_id\":\"K\",\"issuer_id\":\"I\",\"key\":\"S\"}")
    withProperties(Map(
      "ssc.xcodebuild.command" -> xcode.toString,
      "ssc.fastlane.command" -> fastlane.toString,
    )) {
      val emitted = SwiftV2Cli.EmittedPackage(root, "SentinelCli", SwiftPlatform.MacOS, Some(app))
      val context = SwiftV2Distribution.Context(emitted, app, root)
      val pkg = SwiftV2Distribution.packageMacAppStore(context, "TEAM123", "fake publish")
      assert(pkg == root / "pkg" / "Sentinel.pkg")
      SwiftV2Distribution.runFastlane(
        context, "mac_appstore", pkg, key, None, submitForReview = true,
        useExisting = false, root, "fake publish")
      val custom = root / "custom"
      os.makeDir.all(custom)
      os.write(custom / "Fastfile", "lane :mac_appstore do\nend\n")
      SwiftV2Distribution.runFastlane(
        context, "mac_appstore", pkg, key, None, submitForReview = false,
        useExisting = true, custom, "fake publish")
      val env = os.read(envLog)
      assert(env.contains("lane=mac_appstore"))
      assert(env.contains("argv=mac mac_appstore"))
      assert(env.linesIterator.contains("argv=mac_appstore"))
      assert(env.contains(s"pkg=${pkg.toString}"))
      assert(env.contains("bundle=com.scalascript.sentinel"))
      assert(!os.read(root / "Fastfile").contains("gym("))
    }

  test("generated and existing iOS Fastfiles receive explicit artifact cwd and env"):
    val root = os.temp.dir(prefix = "ssc-fake-ios-publish-")
    val output = root / "generated"
    os.makeDir.all(output)
    val log = root / "env.log"
    val fastlane = executable(root / "fastlane", fakeFastlaneScript(log))
    val key = root / "key.json"
    val ipa = root / "Sentinel.ipa"
    os.write(key, "{\"key_id\":\"K\",\"issuer_id\":\"I\",\"key\":\"S\"}")
    os.write(ipa, "ipa")
    val emitted = SwiftV2Cli.EmittedPackage(output, "SentinelCli", SwiftPlatform.IOS, Some(app))
    val context = SwiftV2Distribution.Context(emitted, app, output)
    val custom = root / "custom"
    os.makeDir.all(custom)
    os.write(custom / "Fastfile", "lane :appstore do\nend\n")
    val hostile = "quote \" slash \\ cr\r\nПривіт"
    withProperties(Map("ssc.fastlane.command" -> fastlane.toString)) {
      SwiftV2Distribution.runFastlane(
        context, "testflight", ipa, key, Some(hostile), submitForReview = false,
        useExisting = false, custom, "fake publish")
      SwiftV2Distribution.runFastlane(
        context, "appstore", ipa, key, None, submitForReview = true,
        useExisting = true, custom, "fake publish")
      val env = os.read(log)
      assert(env.contains("lane=testflight") && env.contains("lane=appstore"))
      assert(env.contains(s"ipa=${ipa.toString}"))
      assert(env.contains("bundle=com.scalascript.sentinel"))
      val workingDirectories = env.linesIterator.filter(_.startsWith("cwd="))
        .map(line => os.Path(line.stripPrefix("cwd="), os.pwd).toNIO.toRealPath()).toSet
      assert(workingDirectories == Set(output.toNIO.toRealPath(), custom.toNIO.toRealPath()))
      assert(env.contains("notes=quote \" slash \\ cr"))
      assert(os.exists(output / "Fastfile"))
      assert(!os.read(output / "Fastfile").contains("gym("))
    }

  private def writeArchiveInfo(archive: os.Path, applicationPath: String): Unit =
    os.write.over(archive / "Info.plist",
      s"""|<?xml version="1.0" encoding="UTF-8"?>
          |<plist version="1.0"><dict>
          |<key>ApplicationProperties</key><dict>
          |<key>ApplicationPath</key><string>$applicationPath</string>
          |</dict></dict></plist>
          |""".stripMargin)

  private def writeAppInfo(bundle: os.Path, bundleId: String, executable: String): Unit =
    os.write.over(bundle / "Info.plist",
      s"""|<?xml version="1.0" encoding="UTF-8"?>
          |<plist version="1.0"><dict>
          |<key>CFBundlePackageType</key><string>APPL</string>
          |<key>CFBundleIdentifier</key><string>$bundleId</string>
          |<key>CFBundleExecutable</key><string>$executable</string>
          |</dict></plist>
          |""".stripMargin)

  private def commandAvailable(command: String): Boolean =
    try os.proc(command, "--version").call(check = false, stdout = os.Pipe, stderr = os.Pipe).exitCode == 0
    catch case _: Throwable => false

  private def withProperties[A](values: Map[String, String])(body: => A): A =
    val previous = values.keys.map(key => key -> sys.props.get(key)).toMap
    values.foreach((key, value) => sys.props(key) = value)
    try body
    finally
      previous.foreach {
        case (key, Some(value)) => sys.props(key) = value
        case (key, None) => sys.props.remove(key)
      }

  private def executable(path: os.Path, content: String): os.Path =
    os.write(path, content)
    os.perms.set(path, "rwxr--r--")
    path

  private def fakeToolScript(log: os.Path): String =
    s"""|#!/bin/sh
        |set -eu
        |echo "$$*" >> '${log.toString}'
        |last=''
        |for arg in "$$@"; do last="$$arg"; done
        |case "$$last" in
        |  *'.zip'|*'.dmg') : > "$$last" ;;
        |esac
        |exit 0
        |""".stripMargin

  private def fakeFastlaneScript(log: os.Path): String =
    s"""|#!/bin/sh
        |set -eu
        |if [ "$$1" = '--version' ]; then exit 0; fi
        |{
        |  echo "argv=$$*"
        |  if [ "$$1" = 'mac' ]; then echo "lane=$$2"; else echo "lane=$$1"; fi
        |  echo "cwd=$$(pwd)"
        |  echo "ipa=$${SSC_IPA_PATH-}"
        |  echo "pkg=$${SSC_PKG_PATH-}"
        |  echo "bundle=$${SSC_BUNDLE_ID-}"
        |  echo "project=$${SSC_XCODE_PROJECT-}"
        |  echo "scheme=$${SSC_XCODE_SCHEME-}"
        |  echo "notes=$${SSC_RELEASE_NOTES-}"
        |} >> '${log.toString}'
        |exit 0
        |""".stripMargin

  private def fakeXcodeScript(
      log: os.Path,
      exportKind: String = "ipa",
      platform: String = "ios",
  ): String =
    s"""|#!/bin/sh
        |set -eu
        |echo "$$*" >> '${log.toString}'
        |if [ "$$1" = "-version" ]; then exit 0; fi
        |if [ "$$1" = "-showBuildSettings" ]; then
        |  echo '    TARGET_NAME = Sentinel'
        |  echo '    FULL_PRODUCT_NAME = Sentinel.app'
        |  echo '    TARGET_BUILD_DIR = ${log / os.up / "Build"}'
        |  exit 0
        |fi
        |if [ "$$1" = "build" ]; then
        |  app='${log / os.up / "Build"}/Sentinel.app'
        |  if [ '$platform' = 'macos' ]; then
        |    mkdir -p "$$app/Contents/MacOS"
        |    info="$$app/Contents/Info.plist"
        |    executable="$$app/Contents/MacOS/Sentinel"
        |  else
        |    mkdir -p "$$app"
        |    info="$$app/Info.plist"
        |    executable="$$app/Sentinel"
        |  fi
        |  printf '%s' '<?xml version="1.0"?><plist version="1.0"><dict><key>CFBundlePackageType</key><string>APPL</string><key>CFBundleIdentifier</key><string>com.scalascript.sentinel</string><key>CFBundleExecutable</key><string>Sentinel</string></dict></plist>' > "$$info"
        |  : > "$$executable"
        |  exit 0
        |fi
        |if [ "$$1" = "archive" ]; then
        |  archive=''
        |  previous=''
        |  for arg in "$$@"; do
        |    if [ "$$previous" = '-archivePath' ]; then archive="$$arg"; fi
        |    previous="$$arg"
        |  done
        |  app="$$archive/Products/Applications/Sentinel.app"
        |  mkdir -p "$$archive"
        |  printf '%s' '<?xml version="1.0"?><plist version="1.0"><dict><key>ApplicationProperties</key><dict><key>ApplicationPath</key><string>Applications/Sentinel.app</string></dict></dict></plist>' > "$$archive/Info.plist"
        |  if [ '$platform' = 'macos' ]; then
        |    mkdir -p "$$app/Contents/MacOS"
        |    info="$$app/Contents/Info.plist"
        |    executable="$$app/Contents/MacOS/Sentinel"
        |  else
        |    mkdir -p "$$app"
        |    info="$$app/Info.plist"
        |    executable="$$app/Sentinel"
        |  fi
        |  printf '%s' '<?xml version="1.0"?><plist version="1.0"><dict><key>CFBundlePackageType</key><string>APPL</string><key>CFBundleIdentifier</key><string>com.scalascript.sentinel</string><key>CFBundleExecutable</key><string>Sentinel</string></dict></plist>' > "$$info"
        |  : > "$$executable"
        |  exit 0
        |fi
        |if [ "$$1" = '-exportArchive' ]; then
        |  output=''
        |  previous=''
        |  for arg in "$$@"; do
        |    if [ "$$previous" = '-exportPath' ]; then output="$$arg"; fi
        |    previous="$$arg"
        |  done
        |  mkdir -p "$$output"
        |  if [ '$exportKind' = 'app' ]; then
        |    app="$$output/Sentinel.app"
        |    mkdir -p "$$app/Contents/MacOS"
        |    printf '%s' '<?xml version="1.0"?><plist version="1.0"><dict><key>CFBundlePackageType</key><string>APPL</string><key>CFBundleIdentifier</key><string>com.scalascript.sentinel</string><key>CFBundleExecutable</key><string>Sentinel</string></dict></plist>' > "$$app/Contents/Info.plist"
        |    : > "$$app/Contents/MacOS/Sentinel"
        |  else
        |    : > "$$output/Sentinel.$exportKind"
        |  fi
        |  exit 0
        |fi
        |exit 64
        |""".stripMargin
