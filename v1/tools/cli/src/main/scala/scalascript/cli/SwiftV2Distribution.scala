package scalascript.cli

import _root_.ssc.swift.{SwiftPlatform, XcodeAppArtifact}

private[cli] object SwiftV2Distribution:
  final case class Context(
      emitted: SwiftV2Cli.EmittedPackage,
      app: XcodeAppArtifact,
      output: os.Path,
  )

  final case class MacDistribution(app: os.Path, dmg: Option[os.Path])

  def context(
      source: os.Path,
      output: os.Path,
      platform: SwiftPlatform,
      backendBaseUrl: Option[String],
      command: String,
  ): Context =
    val emitted = SwiftV2Cli.emit(source, output, platform, backendBaseUrl = backendBaseUrl)
    val app = emitted.xcodeApp.getOrElse(
      throw new IllegalArgumentException(s"$command: checked program does not define a NativeUi application"))
    Context(emitted, app, output)

  def resolveTeam(cli: Option[String], environment: Map[String, String], command: String): String =
    val value = cli.orElse(environment.get("SSC_TEAM_ID")).map(_.trim).filter(_.nonEmpty).getOrElse(
      throw new IllegalArgumentException(s"$command: --team-id or SSC_TEAM_ID is required"))
    if !value.matches("[A-Za-z0-9]+") then
      throw new IllegalArgumentException(s"$command: invalid Apple team id")
    value

  def parseNotaryTimeout(raw: Option[String], command: String): Int =
    val parsed = raw match
      case None => 900
      case Some(value) => scala.util.Try(value.toInt).toOption.getOrElse(
        throw new IllegalArgumentException(
          s"$command: --notary-timeout-seconds must be an integer in 1..3600"))
    if parsed < 1 || parsed > 3600 then
      throw new IllegalArgumentException(
        s"$command: --notary-timeout-seconds must be an integer in 1..3600")
    parsed

  def requireXcodebuild(command: String): Unit =
    requireSuccess(tool("xcodebuild") ++ List("-version"), None, command, "xcodebuild")

  def preflightMacDistribution(
      notarize: Boolean,
      dmg: Boolean,
      notaryProfile: Option[String],
      command: String,
  ): Unit =
    requireXcodebuild(command)
    requireSuccess(tool("codesign") ++ List("--version"), None, command, "codesign")
    if notarize then
      notaryProfile.map(_.trim).filter(_.nonEmpty).getOrElse(
        throw new IllegalArgumentException(
          s"$command: --notary-profile or SSC_NOTARY_KEYCHAIN_PROFILE is required"))
      requireSuccess(tool("ditto") ++ List("--help"), None, command, "ditto")
      requireSuccess(tool("xcrun") ++ List("--find", "notarytool"), None, command, "notarytool")
      requireSuccess(tool("xcrun") ++ List("--find", "stapler"), None, command, "stapler")
    if dmg then
      requireSuccess(tool("hdiutil") ++ List("help"), None, command, "hdiutil")

  def normalizeExportMethod(raw: String, command: String): String =
    raw.trim.toLowerCase match
      case "development" | "debugging" => "debugging"
      case "ad-hoc" | "release-testing" => "release-testing"
      case "app-store" | "app-store-connect" => "app-store-connect"
      case "enterprise" => "enterprise"
      case other =>
        throw new IllegalArgumentException(
          s"$command: unsupported export method '$other' " +
          "(valid: debugging, release-testing, app-store-connect, enterprise)")

  def exportOptionsPlist(method: String, teamId: String): String =
    s"""|<?xml version="1.0" encoding="UTF-8"?>
        |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        |<plist version="1.0">
        |<dict>
        |  <key>method</key><string>${xml(method)}</string>
        |  <key>destination</key><string>export</string>
        |  <key>signingStyle</key><string>automatic</string>
        |  <key>manageAppVersionAndBuildNumber</key><false/>
        |  <key>teamID</key><string>${xml(teamId)}</string>
        |  <key>uploadSymbols</key><true/>
        |</dict>
        |</plist>
        |""".stripMargin

  def packageIos(
      context: Context,
      exportMethod: String,
      teamId: String,
      command: String,
  ): os.Path =
    val method = normalizeExportMethod(exportMethod, command)
    val archivePath = context.output / s"${context.app.target}.xcarchive"
    SwiftV2Cli.archiveXcodeApplication(
      context.emitted, "generic/platform=iOS", archivePath,
      context.output / "derived-archive", teamId, command)
    exportArchive(
      context, archivePath, context.output / "ipa", context.output / "ExportOptions.plist",
      exportOptionsPlist(method, teamId), "ipa", command)

  def runIosDevice(
      context: Context,
      teamId: String,
      deviceId: Option[String],
      console: Boolean,
      command: String,
  ): Unit =
    requireIosDeploy(command)
    val destination = deviceId match
      case Some(id) => s"platform=iOS,id=$id"
      case None => "generic/platform=iOS"
    val built = SwiftV2Cli.buildXcodeApplication(
      context.emitted, destination, context.output / "derived-device", command,
      configuration = "Debug",
      extraArgs = List("-allowProvisioningUpdates", s"DEVELOPMENT_TEAM=$teamId"))
    requireSuccess(
      tool("ios-deploy") ++ iosDeployArguments(built.bundle, deviceId, console),
      None, command, "ios-deploy")

  def packageMacDeveloperId(
      context: Context,
      teamId: String,
      notarize: Boolean,
      dmg: Boolean,
      notaryProfile: Option[String],
      notaryTimeoutSeconds: Int,
      command: String,
  ): MacDistribution =
    if notaryTimeoutSeconds < 1 || notaryTimeoutSeconds > 3600 then
      throw new IllegalArgumentException(s"$command: --notary-timeout-seconds must be 1..3600")
    val profile =
      if notarize then Some(notaryProfile.map(_.trim).filter(_.nonEmpty).getOrElse(
        throw new IllegalArgumentException(
          s"$command: --notary-profile or SSC_NOTARY_KEYCHAIN_PROFILE is required")))
      else None
    val archivePath = context.output / s"${context.app.target}.xcarchive"
    val archive = SwiftV2Cli.archiveXcodeApplication(
      context.emitted, "generic/platform=macOS", archivePath,
      context.output / "derived-archive", teamId, command)
    val exported = exportArchive(
      context, archivePath, context.output / "export", context.output / "ExportOptions-macos.plist",
      exportOptionsPlist("developer-id", teamId), "app", command)
    val verified = SwiftV2Cli.verifyAppBundle(context.emitted, exported, archive.buildSettings, command)
    requireSuccess(
      tool("codesign") ++ List("--verify", "--deep", "--strict", verified.bundle.toString),
      None, command, "codesign verification")

    if notarize then
      val zip = context.output / s"${context.app.target}-notarization.zip"
      if os.exists(zip) then os.remove(zip)
      requireSuccess(
        tool("ditto") ++ List("-c", "-k", "--keepParent", verified.bundle.toString, zip.toString),
        None, command, "notarization ZIP")
      requireSuccess(
        tool("xcrun") ++ notaryArguments(zip, profile.get, notaryTimeoutSeconds),
        None, command, "notarytool")
      requireSuccess(
        tool("xcrun") ++ List("stapler", "staple", verified.bundle.toString),
        None, command, "stapler staple")
      requireSuccess(
        tool("xcrun") ++ List("stapler", "validate", verified.bundle.toString),
        None, command, "stapler validate")

    val dmgPath =
      if dmg then
        val path = context.output / s"${context.app.target}.dmg"
        if os.exists(path) then os.remove(path)
        requireSuccess(
          tool("hdiutil") ++ List(
            "create", "-volname", context.app.displayName,
            "-srcfolder", verified.bundle.toString, "-ov", "-format", "UDZO", path.toString),
          None, command, "DMG creation")
        Some(path)
      else None
    MacDistribution(verified.bundle, dmgPath)

  def packageMacAppStore(
      context: Context,
      teamId: String,
      command: String,
  ): os.Path =
    val archivePath = context.output / s"${context.app.target}.xcarchive"
    SwiftV2Cli.archiveXcodeApplication(
      context.emitted, "generic/platform=macOS", archivePath,
      context.output / "derived-archive", teamId, command)
    exportArchive(
      context, archivePath, context.output / "pkg", context.output / "ExportOptions-mac-appstore.plist",
      exportOptionsPlist("app-store-connect", teamId), "pkg", command)

  def requireApiKey(
      cli: Option[String],
      environment: Map[String, String],
      command: String,
  ): os.Path =
    val raw = cli.orElse(environment.get("APP_STORE_CONNECT_API_KEY_PATH"))
      .map(_.trim).filter(_.nonEmpty).getOrElse(
        throw new IllegalArgumentException(
          s"$command: --api-key-path or APP_STORE_CONNECT_API_KEY_PATH is required"))
    val path = os.Path(raw, os.pwd)
    if !os.exists(path) || !os.isFile(path) then
      throw new IllegalArgumentException(s"$command: API key JSON file not found")
    val parsed = scala.util.Try(ujson.read(os.read(path)).obj).toOption
    val complete = parsed.exists { obj =>
      def string(key: String): Boolean =
        obj.get(key).exists(value => scala.util.Try(value.str.trim.nonEmpty).getOrElse(false))
      string("key_id") && string("issuer_id") && string("key")
    }
    if !complete then
      throw new IllegalArgumentException(
        s"$command: API key JSON requires non-empty key_id, issuer_id, and key")
    path

  def requireFastlane(command: String): Unit =
    requireSuccess(tool("fastlane") ++ List("--version"), None, command, "fastlane")

  def requireIosDeploy(command: String): Unit =
    requireSuccess(tool("ios-deploy") ++ List("--version"), None, command, "ios-deploy")

  def generatedFastfile: String =
    """|default_platform(:ios)
       |
       |platform :ios do
       |  lane :testflight do
       |    pilot(ipa: ENV.fetch("SSC_IPA_PATH"), app_identifier: ENV.fetch("SSC_BUNDLE_ID"), api_key_path: ENV.fetch("APP_STORE_CONNECT_API_KEY_PATH"), changelog: ENV["SSC_RELEASE_NOTES"], skip_waiting_for_build_processing: true)
       |  end
       |  lane :appstore do
       |    deliver(ipa: ENV.fetch("SSC_IPA_PATH"), app_identifier: ENV.fetch("SSC_BUNDLE_ID"), api_key_path: ENV.fetch("APP_STORE_CONNECT_API_KEY_PATH"), submit_for_review: ENV["SSC_SUBMIT_FOR_REVIEW"] == "true", automatic_release: false)
       |  end
       |end
       |
       |platform :mac do
       |  lane :mac_appstore do
       |    deliver(pkg: ENV.fetch("SSC_PKG_PATH"), app_identifier: ENV.fetch("SSC_BUNDLE_ID"), api_key_path: ENV.fetch("APP_STORE_CONNECT_API_KEY_PATH"), submit_for_review: ENV["SSC_SUBMIT_FOR_REVIEW"] == "true", automatic_release: false, platform: :mac)
       |  end
       |end
       |""".stripMargin

  def runFastlane(
      context: Context,
      lane: String,
      artifact: os.Path,
      apiKey: os.Path,
      releaseNotes: Option[String],
      submitForReview: Boolean,
      useExisting: Boolean,
      sourceDirectory: os.Path,
      command: String,
  ): Unit =
    val allowed = Set("testflight", "appstore", "mac_appstore")
    if !allowed(lane) then throw new IllegalArgumentException(s"$command: invalid fastlane lane")
    val fastfilePath =
      if useExisting then sourceDirectory / "Fastfile"
      else
        val path = context.output / "Fastfile"
        os.write.over(path, generatedFastfile)
        path
    if !os.exists(fastfilePath) then
      throw new IllegalArgumentException(s"$command: no Fastfile found at $fastfilePath")
    val env = fastlaneEnvironment(
      context, artifact, apiKey, releaseNotes, submitForReview)
    requireSuccess(tool("fastlane") ++ List(lane), Some(fastfilePath / os.up), command,
      s"fastlane $lane", env)

  private def exportArchive(
      context: Context,
      archivePath: os.Path,
      exportDirectory: os.Path,
      plist: os.Path,
      plistContent: String,
      extension: String,
      command: String,
  ): os.Path =
    if os.exists(exportDirectory) then os.remove.all(exportDirectory)
    os.makeDir.all(exportDirectory)
    os.write.over(plist, plistContent)
    requireSuccess(
      tool("xcodebuild") ++ exportArguments(archivePath, exportDirectory, plist),
      Some(context.output), command, "xcodebuild export")
    uniqueArtifact(exportDirectory, extension, command)

  def uniqueArtifact(directory: os.Path, extension: String, command: String): os.Path =
    val found = os.walk(directory).filter(path => os.isFile(path) || path.ext == "app")
      .filter(_.ext == extension).toVector.distinct
    found match
      case Vector(only) => only
      case _ => throw new IllegalStateException(
        s"$command: expected exactly one .$extension export, found ${found.size}")

  def fastlaneEnvironment(
      context: Context,
      artifact: os.Path,
      apiKey: os.Path,
      releaseNotes: Option[String],
      submitForReview: Boolean,
  ): Map[String, String] =
    val project = context.output / os.RelPath(context.app.project)
    val artifactEnv =
      if artifact.ext == "ipa" then Map("SSC_IPA_PATH" -> artifact.toString)
      else Map("SSC_PKG_PATH" -> artifact.toString)
    Map(
      "APP_STORE_CONNECT_API_KEY_PATH" -> apiKey.toString,
      "SSC_XCODE_PROJECT" -> project.toString,
      "SSC_XCODE_SCHEME" -> context.app.scheme,
      "SSC_BUNDLE_ID" -> context.app.bundleId,
      "SSC_SUBMIT_FOR_REVIEW" -> submitForReview.toString,
    ) ++ artifactEnv ++ releaseNotes.map("SSC_RELEASE_NOTES" -> _)

  def iosDeployArguments(bundle: os.Path, deviceId: Option[String], console: Boolean): List[String] =
    List("--bundle", bundle.toString, "--no-wifi") ++
      deviceId.toList.flatMap(id => List("--id", id)) ++
      (if console then List("--debug") else List("--justlaunch"))

  def exportArguments(archive: os.Path, output: os.Path, plist: os.Path): List[String] =
    List(
      "-exportArchive", "-archivePath", archive.toString,
      "-exportPath", output.toString,
      "-exportOptionsPlist", plist.toString, "-allowProvisioningUpdates")

  def notaryArguments(zip: os.Path, profile: String, timeoutSeconds: Int): List[String] =
    List(
      "notarytool", "submit", zip.toString, "--wait",
      "--timeout", s"${timeoutSeconds}s", "--output-format", "json",
      "--no-progress", "--keychain-profile", profile)

  private def tool(name: String): List[String] =
    List(sys.props.getOrElse(s"ssc.$name.command", name))

  private def requireSuccess(
      args: List[String],
      cwd: Option[os.Path],
      command: String,
      purpose: String,
      environment: Map[String, String] = Map.empty,
  ): Unit =
    val result =
      try os.proc(args).call(
        cwd = cwd.getOrElse(os.pwd), env = environment,
        stdout = os.Inherit, stderr = os.Inherit, check = false)
      catch case _: java.io.IOException =>
        throw new IllegalStateException(s"$command: ${args.head} is required for $purpose")
    if result.exitCode != 0 then
      throw new IllegalStateException(s"$command: $purpose failed (exit ${result.exitCode})")

  private def xml(value: String): String =
    value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
      .replace("\"", "&quot;").replace("'", "&apos;")
