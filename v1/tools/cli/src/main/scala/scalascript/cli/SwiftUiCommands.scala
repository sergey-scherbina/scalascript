package scalascript.cli

import scalascript.codegen.JvmGen
import scalascript.parser.Parser

// iOS / macOS SwiftUI packaging for `ssc build --target mobile-ios|macos`:
// emit a Swift Package via JvmGen(frontend=swiftui), then drive swift build /
// xcodebuild / simctl to run on a simulator or device and produce an .ipa.
// Extracted from Main.scala.

/** Compile `sscFile` via JvmGen (frontend=swiftui) and emit a Swift Package
 *  to `outDir`.  `platform` is either `"ios"` or `"macos"`.
 *  When `runSwiftBuild` is true, also invoke `swift build` in `outDir`.
 *  Requires `scala-cli` on PATH. */
private[cli] def buildSwiftUIPackage(
    sscFile: os.Path, outDir: os.Path, platform: String, runSwiftBuild: Boolean = false
): Unit =
  if !JvmBytecode.scalaCliAvailable then
    System.err.println(s"build --target mobile-ios/macos: ${JvmBytecode.scalaCliMissingMessage}")
    System.exit(1)
  val module  = Parser.parse(os.read(sscFile))
  val baseDir = Some(sscFile / os.up)
  val raw     = JvmGen.generate(module, baseDir, frontendOverride = Some("swiftui"))
  val jarsDir = scalascript.imports.ImportResolver.libPath.map(_ / "bin" / "lib" / "jars")
  val source  = jarsDir match
    case Some(jars) => patchLocalSscDeps(raw, jars)
    case None       => raw
  os.makeDir.all(outDir)
  val tmp = os.temp(source, suffix = ".sc", deleteOnExit = true)
  try
    val result = os.proc(
      "scala-cli", "run", tmp, "--server=false",
      "-J", s"-Dssc.build.outdir=${outDir}",
      "-J", s"-Dssc.build.platform=$platform"
    ).call(stdout = os.Inherit, stderr = os.Inherit, cwd = os.pwd, check = false)
    if result.exitCode != 0 then System.exit(result.exitCode)
  finally
    scala.util.Try(os.remove(tmp))
  if runSwiftBuild then
    println(s"  Running swift build in ${displayPath(outDir)}...")
    val swiftResult = os.proc("swift", "build")
      .call(stdout = os.Inherit, stderr = os.Inherit, cwd = outDir, check = false)
    if swiftResult.exitCode != 0 then System.exit(swiftResult.exitCode)

/** Derive the Swift product name from the .ssc `name:` frontmatter, matching
 *  SwiftUIEmitter.swiftIdent so we know where swift build / xcodebuild puts the binary. */
private[cli] def swiftAppName(sscName: Option[String]): String =
  val raw = sscName.getOrElse("ScalaScript App")
  raw.filter(c => c.isLetterOrDigit || c == '_').capitalize match
    case ""  => "App"
    case str => if str.head.isDigit then s"App$str" else str

/** Return (udid, name) of the latest available iPhone simulator, or None. */
private[cli] def pickIosSimulator(): Option[(String, String)] =
  // `xcrun` is macOS-only; on Linux/headless CI the spawn itself throws
  // IOException ("Cannot run program xcrun") — `check = false` suppresses only
  // a NON-ZERO exit, not a missing executable. Treat an absent or failed xcrun
  // as "no simulator available" (the documented None case).
  val resultOpt = scala.util.Try(
    os.proc("xcrun", "simctl", "list", "devices", "available", "--json")
      .call(check = false, stderr = os.Pipe)
  ).toOption
  if !resultOpt.exists(_.exitCode == 0) then None
  else
    val result = resultOpt.get
    scala.util.Try {
      val json   = ujson.read(result.out.text())
      val devMap = json.obj.get("devices").map(_.obj).getOrElse(
        ujson.Obj().obj
      )
      // Sort iOS runtime keys descending so we try the latest SDK first
      val iosKeys = devMap.keys
        .filter(k => k.contains("iOS") && !k.contains("watchOS") && !k.contains("tvOS"))
        .toList.sorted.reverse
      iosKeys.iterator.flatMap { key =>
        val devs = devMap.get(key).map(_.arr).getOrElse(scala.collection.mutable.ArrayBuffer.empty)
        devs.toList
          .filter { d =>
            d.obj.get("isAvailable").exists(_.bool) &&
            d.obj.get("name").map(_.str).exists(_.startsWith("iPhone"))
          }
          .sortBy(_.obj("name").str)
          .reverse
          .headOption
          .map(d => (d.obj("udid").str, d.obj("name").str))
      }.nextOption()
    }.toOption.flatten

/** Full `ssc run --target ios` flow: generate Swift Package → xcodebuild →
 *  boot simulator → install → launch (optionally streaming logs). */
private[cli] def runSwiftUIIosSimulator(
    sscFile: os.Path, outDir: os.Path, console: Boolean, forceRebuild: Boolean
): Unit =
  // Pre-flight: xcodebuild must be present
  if os.proc("xcodebuild", "-version")
      .call(check = false, stdout = os.Pipe, stderr = os.Pipe).exitCode != 0 then
    System.err.println(
      "Error: Xcode is required for --target ios.\n" +
      "Run: ssc toolchain check --target ios"
    )
    System.exit(1)

  val module  = Parser.parse(os.read(sscFile))
  val appName = swiftAppName(module.manifest.flatMap(_.name))
  val bundleId = module.manifest
    .flatMap(_.raw.get("bundle-id").collect { case s: String => s })
    .getOrElse("com.example.app")

  val derivedDataPath = outDir / "derived"
  val appPath = derivedDataPath / "Build" / "Products" / "Debug-iphonesimulator" / s"$appName.app"

  // Pick simulator before building (needed for -destination)
  val (simUdid, simName) = pickIosSimulator().getOrElse {
    System.err.println(
      "Error: No available iOS Simulator found.\n" +
      "Install a simulator runtime via Xcode → Settings → Platforms."
    )
    System.exit(1)
    throw new AssertionError()
  }

  val needsBuild = forceRebuild || !os.exists(appPath / "Info.plist") ||
    os.mtime(sscFile) > os.mtime(appPath / "Info.plist")

  if needsBuild then
    buildSwiftUIPackage(sscFile, outDir, "ios")
    println(s"  Building for iOS Simulator ($simName)...")
    val r = os.proc(
      "xcodebuild", "build",
      "-scheme", appName,
      "-destination", s"platform=iOS Simulator,id=$simUdid",
      "-derivedDataPath", derivedDataPath.toString,
      "CODE_SIGN_IDENTITY=", "CODE_SIGNING_REQUIRED=NO", "CODE_SIGNING_ALLOWED=NO"
    ).call(stdout = os.Inherit, stderr = os.Inherit, cwd = outDir, check = false)
    if r.exitCode != 0 then System.exit(r.exitCode)
    if !os.exists(appPath) then
      System.err.println(s"xcodebuild did not produce ${displayPath(appPath)}")
      System.exit(1)
  else
    println(s"  Skipping build (no .ssc changes since last build). Use --rebuild to force.")

  // Boot simulator (ignore "already booted" error)
  println(s"  Booting $simName...")
  os.proc("xcrun", "simctl", "boot", simUdid)
    .call(check = false, stdout = os.Pipe, stderr = os.Pipe)

  // Open Simulator.app so the user sees it
  os.proc("open", "-a", "Simulator")
    .call(check = false, stdout = os.Pipe, stderr = os.Pipe)

  // Install
  println(s"  Installing $appName...")
  val installResult = os.proc("xcrun", "simctl", "install", "booted", appPath.toString)
    .call(stdout = os.Inherit, stderr = os.Inherit, check = false)
  if installResult.exitCode != 0 then System.exit(installResult.exitCode)

  // Launch — with or without log streaming
  println(s"  Launching $appName ($bundleId)...")
  val launchArgs =
    if console then List("xcrun", "simctl", "launch", "--console", "booted", bundleId)
    else             List("xcrun", "simctl", "launch",              "booted", bundleId)
  val launchResult = os.proc(launchArgs)
    .call(stdout = os.Inherit, stderr = os.Inherit, check = false)
  if launchResult.exitCode != 0 then System.exit(launchResult.exitCode)

/** `ssc run --target ios --device` — build for arm64 device via xcodebuild with
 *  automatic signing, then deploy + launch via ios-deploy.
 *
 *  Requires: Apple ID signed into Xcode (for -allowProvisioningUpdates),
 *  ios-deploy on PATH (`brew install ios-deploy`), USB-connected iPhone. */
private[cli] def runSwiftUIIosDevice(
    sscFile: os.Path, outDir: os.Path,
    console: Boolean, forceRebuild: Boolean, deviceId: Option[String]
): Unit =
  // Pre-flight: ios-deploy must be present
  if os.proc("ios-deploy", "--version")
      .call(check = false, stdout = os.Pipe, stderr = os.Pipe).exitCode != 0 then
    System.err.println(
      "Error: ios-deploy is required for --target ios --device.\n" +
      "Run: brew install ios-deploy"
    )
    System.exit(1)

  val module  = Parser.parse(os.read(sscFile))
  val appName = swiftAppName(module.manifest.flatMap(_.name))

  val derivedDataPath = outDir / "derived"
  val appPath = derivedDataPath / "Build" / "Products" / "Debug-iphoneos" / s"$appName.app"

  val needsBuild = forceRebuild || !os.exists(appPath / "Info.plist") ||
    os.mtime(sscFile) > os.mtime(appPath / "Info.plist")

  if needsBuild then
    buildSwiftUIPackage(sscFile, outDir, "ios")
    println(s"  Building for iOS device (arm64)...")
    val r = os.proc(
      "xcodebuild", "build",
      "-scheme", appName,
      "-destination", "generic/platform=iOS",
      "-allowProvisioningUpdates",
      "-derivedDataPath", derivedDataPath.toString
    ).call(stdout = os.Inherit, stderr = os.Inherit, cwd = outDir, check = false)
    if r.exitCode != 0 then System.exit(r.exitCode)
    if !os.exists(appPath) then
      System.err.println(s"xcodebuild did not produce ${displayPath(appPath)}")
      System.exit(1)
  else
    println(s"  Skipping build (no .ssc changes). Use --rebuild to force.")

  // ios-deploy: --debug streams LLDB output (blocks); --justlaunch returns immediately
  println(s"  Deploying $appName to device${deviceId.map(id => s" ($id)").getOrElse("")}...")
  val idArgs     = deviceId.toList.flatMap(id => List("--id", id))
  val modeArgs   = if console then List("--debug") else List("--justlaunch")
  val deployResult = os.proc(
    List("ios-deploy", "--bundle", appPath.toString, "--no-wifi") ++ idArgs ++ modeArgs
  ).call(stdout = os.Inherit, stderr = os.Inherit, check = false)
  if deployResult.exitCode != 0 then System.exit(deployResult.exitCode)

/** `ssc package --target ios` — archive + export a signed `.ipa`.
 *
 *  Requires Xcode + an Apple Developer account (automatic signing via
 *  `-allowProvisioningUpdates`).  `teamId` resolves from:
 *    1. `--team-id` CLI flag
 *    2. `team-id:` frontmatter field
 *    3. `SSC_TEAM_ID` environment variable
 *
 *  `exportMethod` is one of `development`, `ad-hoc`, `enterprise`,
 *  `app-store` (default: `development`). */
private[cli] def packageIosIpa(
    sscFile:      os.Path,
    outDir:       os.Path,
    exportMethod: String,
    teamId:       Option[String]
): Unit =
  if os.proc("xcodebuild", "-version")
      .call(check = false, stdout = os.Pipe, stderr = os.Pipe).exitCode != 0 then
    System.err.println("Error: Xcode is required for ssc package --target ios.\nRun: ssc toolchain check --target ios")
    System.exit(1)

  val module   = Parser.parse(os.read(sscFile))
  val manifest = module.manifest
  val appName  = swiftAppName(manifest.flatMap(_.name))
  val resolvedTeamId = teamId
    .orElse(manifest.flatMap(_.raw.get("team-id").collect { case s: String => s }))
    .orElse(sys.env.get("SSC_TEAM_ID"))

  println(s"  Generating Swift package for iOS...")
  buildSwiftUIPackage(sscFile, outDir, "ios")

  val archivePath = outDir / s"$appName.xcarchive"
  val ipaDir      = outDir / "ipa"
  val exportPlist = outDir / "ExportOptions.plist"

  os.write.over(exportPlist, generateExportOptionsPlist(exportMethod, resolvedTeamId))
  println(s"  ExportOptions.plist → ${displayPath(exportPlist)}")

  println(s"  Archiving $appName (method=$exportMethod)...")
  val archiveArgs = List(
    "xcodebuild", "archive",
    "-scheme", appName,
    "-destination", "generic/platform=iOS",
    "-allowProvisioningUpdates",
    "-archivePath", archivePath.toString
  ) ++ resolvedTeamId.toList.flatMap(id => List("DEVELOPMENT_TEAM=" + id))
  val archResult = os.proc(archiveArgs)
    .call(stdout = os.Inherit, stderr = os.Inherit, cwd = outDir, check = false)
  if archResult.exitCode != 0 then System.exit(archResult.exitCode)

  os.makeDir.all(ipaDir)
  println(s"  Exporting .ipa...")
  val exportResult = os.proc(
    "xcodebuild", "-exportArchive",
    "-archivePath",        archivePath.toString,
    "-exportPath",         ipaDir.toString,
    "-exportOptionsPlist", exportPlist.toString,
    "-allowProvisioningUpdates"
  ).call(stdout = os.Inherit, stderr = os.Inherit, cwd = outDir, check = false)
  if exportResult.exitCode != 0 then System.exit(exportResult.exitCode)

  val ipa = os.list(ipaDir).find(_.ext == "ipa")
  ipa match
    case Some(p) => println(s"  .ipa → ${displayPath(p)}")
    case None    => System.err.println(s"  Warning: no .ipa found in ${displayPath(ipaDir)}")

/** Generate the XML content of ExportOptions.plist for `xcodebuild -exportArchive`. */
private[cli] def generateExportOptionsPlist(
    method: String, teamId: Option[String]
): String =
  val teamLine = teamId.map(id =>
    s"  <key>teamID</key>\n  <string>$id</string>\n"
  ).getOrElse("")
  s"""|<?xml version="1.0" encoding="UTF-8"?>
      |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
      |<plist version="1.0">
      |<dict>
      |  <key>method</key>
      |  <string>$method</string>
      |$teamLine  <key>uploadSymbols</key>
      |  <true/>
      |  <key>compileBitcode</key>
      |  <false/>
      |  <key>provisioningProfiles</key>
      |  <dict/>
      |</dict>
      |</plist>
      |""".stripMargin
