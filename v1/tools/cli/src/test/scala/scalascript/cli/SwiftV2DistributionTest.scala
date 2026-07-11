package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

import _root_.ssc.swift.{SwiftPlatform, XcodeAppArtifact}

final class SwiftV2DistributionTest extends AnyFunSuite:
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

  test("export options are canonical automatic and contain no empty profile map"):
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
    val archive = os.temp.dir(prefix = "ssc-archive-") / "Sentinel.xcarchive"
    os.makeDir.all(archive / "Products" / "Applications" / "Sentinel.app")
    writeArchiveInfo(archive, "Applications/Sentinel.app")
    assert(SwiftV2Cli.archivedApplication(archive, "test") ==
      archive / "Products" / "Applications" / "Sentinel.app")
    writeArchiveInfo(archive, "../Outside.app")
    val traversal = intercept[IllegalStateException](SwiftV2Cli.archivedApplication(archive, "test"))
    assert(traversal.getMessage.contains("invalid xcarchive ApplicationPath"))

  test("fresh export selection rejects zero and duplicate artifacts"):
    val root = os.temp.dir(prefix = "ssc-export-")
    intercept[IllegalStateException](SwiftV2Distribution.uniqueArtifact(root, "ipa", "test"))
    os.write(root / "one.ipa", "one")
    assert(SwiftV2Distribution.uniqueArtifact(root, "ipa", "test").last == "one.ipa")
    os.write(root / "two.ipa", "two")
    val duplicate = intercept[IllegalStateException](
      SwiftV2Distribution.uniqueArtifact(root, "ipa", "test"))
    assert(duplicate.getMessage.contains("found 2"))

  test("shared app verifier rejects wrong bundle and debug CLI executable"):
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
    os.write(cli, "{\"key_id\":\"CLI\"}")
    os.write(env, "{\"key_id\":\"ENV\"}")
    assert(SwiftV2Distribution.requireApiKey(
      Some(cli.toString), Map("APP_STORE_CONNECT_API_KEY_PATH" -> env.toString), "cmd") == cli)
    val bad = root / "bad.json"
    os.write(bad, "not-json")
    intercept[IllegalArgumentException](
      SwiftV2Distribution.requireApiKey(Some(bad.toString), Map.empty, "cmd"))

  test("fake Xcode runner preserves archive verification and unique IPA handoff"):
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

  private def fakeXcodeScript(log: os.Path): String =
    s"""|#!/bin/sh
        |set -eu
        |echo "$$*" >> '${log.toString}'
        |if [ "$$1" = "-showBuildSettings" ]; then
        |  echo '    TARGET_NAME = Sentinel'
        |  echo '    FULL_PRODUCT_NAME = Sentinel.app'
        |  echo '    TARGET_BUILD_DIR = /tmp/unused'
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
        |  mkdir -p "$$app"
        |  printf '%s' '<?xml version="1.0"?><plist version="1.0"><dict><key>ApplicationProperties</key><dict><key>ApplicationPath</key><string>Applications/Sentinel.app</string></dict></dict></plist>' > "$$archive/Info.plist"
        |  printf '%s' '<?xml version="1.0"?><plist version="1.0"><dict><key>CFBundlePackageType</key><string>APPL</string><key>CFBundleIdentifier</key><string>com.scalascript.sentinel</string><key>CFBundleExecutable</key><string>Sentinel</string></dict></plist>' > "$$app/Info.plist"
        |  : > "$$app/Sentinel"
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
        |  : > "$$output/Sentinel.ipa"
        |  exit 0
        |fi
        |exit 64
        |""".stripMargin
