package scalascript.cli

import java.nio.file.Path
import scala.jdk.CollectionConverters.*

import _root_.ssc.swift.{SwiftAppMetadata, SwiftBackend, SwiftPlatform, XcodeAppArtifact}

private[cli] object SwiftV2Cli:
  final case class EmittedPackage(
      root: os.Path,
      debugCli: String,
      platform: SwiftPlatform,
      xcodeApp: Option[XcodeAppArtifact],
  )
  final case class BuiltXcodeApp(
      bundle: os.Path,
      executable: os.Path,
      buildSettings: Map[String, String],
  )
  /** Result of `ssc package --target macos` (no `--distribution`).
   *  UI-mode apps yield a built, ad-hoc-signed, verified `.app` (`signed = true`);
   *  a domain-only macOS program yields its built SwiftPM package root
   *  (`signed = false` — a plain executable, nothing to bundle-sign). */
  final case class PackagedMacApp(artifact: os.Path, signed: Boolean)

  def parsePlatform(raw: String, command: String): SwiftPlatform =
    raw.trim.toLowerCase match
      case "macos" | "desktop-macos" => SwiftPlatform.MacOS
      case "ios" | "mobile-ios" => SwiftPlatform.IOS
      case other =>
        throw new IllegalArgumentException(
          s"$command: unsupported target '$other' (valid: macos, ios)"
        )

  def emit(
      input: os.Path,
      output: os.Path,
      platform: SwiftPlatform,
      requestedProduct: Option[String] = None,
      backendBaseUrl: Option[String] = None,
  ): EmittedPackage =
    if !os.exists(input) || !os.isFile(input) then
      throw new IllegalArgumentException(s"file not found: $input")

    _root_.ssc.Runtime.argv = Nil
    // Native ssc1 front — ONE compile yields the Core IR program, the parsed
    // front-matter manifests (metadata), and any self-hosted content modules.
    // (Replaces the scalameta FrontendBridge; `main:` is synthesized inside
    // RunNativeV2.compile.)
    val compiled = RunNativeV2.compile(List(input.toString))
    val program  = compiled.program
    def meta(key: String): Option[String] = manifestString(compiled, input, key)
    val product = SwiftBackend.productName(
      requestedProduct.orElse(meta("name")).getOrElse(input.last.stripSuffix(".ssc"))
    )
    val appMetadata = meta("bundle-id").map { bundleId =>
      SwiftAppMetadata(
        bundleId = bundleId,
        displayName = meta("display-name").orElse(meta("name")).getOrElse(product),
        marketingVersion = meta("version").getOrElse("1.0.0"),
        buildVersion = meta("build-version").getOrElse("1"),
      )
    }
    val forceNativeUi = meta("frontend").exists(_.trim.equalsIgnoreCase("swiftui"))
    if (forceNativeUi || SwiftBackend.requiresNativeUi(program)) && appMetadata.isEmpty then
      throw new IllegalArgumentException(
        "Swift UI application requires front-matter bundle-id")
    appMetadata.foreach(SwiftBackend.validateAppMetadata)

    val contentModulesBase64 =
      if compiled.contentModules.isEmpty then None
      else Some(java.util.Base64.getEncoder.encodeToString(
        _root_.ssc.plugin.NativeContentCodec.encode(compiled.contentModules)))

    os.makeDir.all(output)
    val resourcesRoot = output / "AppleApp" / "Resources"
    val existingResources =
      if !os.exists(resourcesRoot) then Vector.empty
      else os.walk(resourcesRoot).filter(os.isFile).map(_.relativeTo(output).toString).toVector
        .filterNot(_.startsWith("AppleApp/Resources/Assets.xcassets/"))
        .sorted
    val generated = SwiftBackend.generate(
      program, product, platform, backendBaseUrl, appMetadata,
      forceNativeUi = forceNativeUi,
      appleResourcePaths = existingResources,
      contentModulesBase64 = contentModulesBase64,
    )
    generated.writeTo(output.toNIO)
    EmittedPackage(output, generated.debugCli, platform, generated.xcodeApp)

  /** A top-level front-matter string value from the compiled root manifest
   *  (parsed once by the native front), e.g. bundle-id / display-name / frontend. */
  private def manifestString(
      compiled: NativeV2Compilation, input: os.Path, key: String): Option[String] =
    val canonical = input.toIO.getCanonicalFile
    compiled.manifests.find(_.source == canonical)
      .orElse(compiled.manifests.lastOption)
      .flatMap(_.value)
      .collect {
        case NativeManifestObject(fields) =>
          fields.collectFirst { case (`key`, NativeManifestString(v)) if v.nonEmpty => v }
      }
      .flatten

  def requireSwift(command: String): String =
    val executable = sys.props.getOrElse("ssc.swift.command", "swift")
    val available =
      try
        val process = new ProcessBuilder(executable, "--version")
          .redirectOutput(ProcessBuilder.Redirect.DISCARD)
          .redirectError(ProcessBuilder.Redirect.DISCARD)
          .start()
        process.waitFor() == 0
      catch case _: Exception => false
    if !available then
      throw new IllegalStateException(
        s"$command: swift not found on PATH. Run: ssc toolchain check --target macos"
      )
    executable

  def runPackage(pkg: EmittedPackage, programArgs: List[String], command: String): Int =
    if pkg.platform != SwiftPlatform.MacOS then
      throw new IllegalArgumentException(
        s"$command: running iOS packages requires ssc run --target ios"
      )
    val swift = requireSwift(command)
    val process = new ProcessBuilder(
      (List(swift, "run", "--package-path", pkg.root.toString, "--quiet", pkg.debugCli) ++ programArgs)*
    ).inheritIO().start()
    val hook = new Thread(() => process.destroy())
    Runtime.getRuntime.addShutdownHook(hook)
    try process.waitFor()
    finally
      try Runtime.getRuntime.removeShutdownHook(hook)
      catch case _: IllegalStateException => ()

  def buildXcodeApplication(
      pkg: EmittedPackage,
      destination: String,
      derivedData: os.Path,
      command: String,
      configuration: String = "Debug",
      extraArgs: List[String] = List("CODE_SIGNING_ALLOWED=NO", "CODE_SIGNING_REQUIRED=NO"),
  ): BuiltXcodeApp =
    val app = pkg.xcodeApp.getOrElse(
      throw new IllegalArgumentException(s"$command: checked program does not define a NativeUi application"))
    val common = xcodeSelection(app, destination, configuration, derivedData)
    val build = callXcode(
      List("build") ++ common ++ extraArgs, pkg.root, command, "xcodebuild")
    if build.exitCode != 0 then
      throw new IllegalStateException(
        s"$command: xcodebuild failed (exit ${build.exitCode}): ${build.err.text().take(2048)}")
    val parsed = queryBuildSettings(pkg, destination, configuration, derivedData, extraArgs, command)
    val targetDir = parsed.getOrElse("TARGET_BUILD_DIR",
      throw new IllegalStateException(s"$command: TARGET_BUILD_DIR missing from Xcode settings"))
    val fullName = parsed.getOrElse("FULL_PRODUCT_NAME",
      throw new IllegalStateException(s"$command: FULL_PRODUCT_NAME missing from Xcode settings"))
    val bundle = os.Path(targetDir, os.pwd) / fullName
    verifyAppBundle(pkg, bundle, parsed, command)

  def queryBuildSettings(
      pkg: EmittedPackage,
      destination: String,
      configuration: String,
      derivedData: os.Path,
      extraArgs: List[String],
      command: String,
  ): Map[String, String] =
    val app = pkg.xcodeApp.getOrElse(
      throw new IllegalArgumentException(s"$command: checked program does not define a NativeUi application"))
    val common = xcodeSelection(app, destination, configuration, derivedData)
    val settings = callXcode(
      List("-showBuildSettings") ++ common ++ extraArgs, pkg.root, command,
      "xcodebuild -showBuildSettings")
    if settings.exitCode != 0 then
      throw new IllegalStateException(
        s"$command: xcodebuild -showBuildSettings failed (exit ${settings.exitCode})")
    settings.out.text().linesIterator.flatMap { line =>
      val marker = " = "
      val at = line.indexOf(marker)
      if at < 0 then None else Some(line.take(at).trim -> line.drop(at + marker.length).trim)
    }.toMap

  def verifyAppBundle(
      pkg: EmittedPackage,
      bundle: os.Path,
      buildSettings: Map[String, String],
      command: String,
  ): BuiltXcodeApp =
    val app = pkg.xcodeApp.getOrElse(
      throw new IllegalArgumentException(s"$command: checked program does not define a NativeUi application"))
    val macInfo = bundle / "Contents" / "Info.plist"
    val infoPlist = pkg.platform match
      case SwiftPlatform.MacOS => macInfo
      case SwiftPlatform.IOS => bundle / "Info.plist"
    if bundle.ext != "app" || !os.exists(infoPlist) then
      throw new IllegalStateException(s"$command: Xcode application product missing at $bundle")
    def plist(key: String): String =
      val result = callProcess(
        List("plutil", "-extract", key, "raw", "-o", "-", infoPlist.toString),
        None, command, s"Info.plist $key")
      if result.exitCode != 0 then throw new IllegalStateException(s"$command: Info.plist missing $key")
      result.out.text().trim
    val packageType = plist("CFBundlePackageType")
    val bundleId = plist("CFBundleIdentifier")
    val executableName = plist("CFBundleExecutable")
    if packageType != "APPL" || bundleId != app.bundleId || executableName == pkg.debugCli then
      throw new IllegalStateException(s"$command: selected product is not the expected application")
    val executable =
      if infoPlist == macInfo then bundle / "Contents" / "MacOS" / executableName
      else bundle / executableName
    if !os.exists(executable) then
      throw new IllegalStateException(s"$command: application executable missing at $executable")
    BuiltXcodeApp(bundle, executable, buildSettings)

  /** Ad-hoc codesign (`codesign --sign -`) the bundle, then prove it with
   *  `codesign --verify --deep --strict`. Ad-hoc signing needs NO Apple
   *  identity/certificate and produces a launch-ready, self-consistent
   *  signature (Gatekeeper still rejects it — that requires Developer ID via
   *  `--distribution`; ad-hoc is the credential-free tier). Both steps run
   *  hermetically (`--timestamp=none`, no notary/network). */
  def adhocSignAndVerify(bundle: os.Path, command: String): Unit =
    val codesign = sys.props.getOrElse("ssc.codesign.command", "codesign")
    val sign = callProcess(
      List(codesign, "--force", "--deep", "--timestamp=none", "--sign", "-", bundle.toString),
      None, command, "ad-hoc codesign")
    if sign.exitCode != 0 then
      throw new IllegalStateException(
        s"$command: ad-hoc codesign failed (exit ${sign.exitCode}): ${sign.err.text().take(2048)}")
    val verify = callProcess(
      List(codesign, "--verify", "--deep", "--strict", bundle.toString),
      None, command, "codesign verification")
    if verify.exitCode != 0 then
      throw new IllegalStateException(
        s"$command: codesign verification failed (exit ${verify.exitCode}): ${verify.err.text().take(2048)}")

  /** `ssc package --target macos` (no `--distribution`): emit the package, build
   *  the real artifact, and — for a UI-mode app — ad-hoc sign + verify the
   *  produced `.app` so the packaged bundle is signed and launch-ready without
   *  any Apple credential. A domain-only macOS program has no `.app` bundle to
   *  sign; its SwiftPM executable is built and returned unsigned. */
  def packageMacos(
      sscFile: os.Path,
      outDir: os.Path,
      backendBaseUrl: Option[String],
      command: String,
  ): PackagedMacApp =
    val emitted = emit(sscFile, outDir, SwiftPlatform.MacOS, backendBaseUrl = backendBaseUrl)
    emitted.xcodeApp match
      case Some(_) =>
        val built = buildXcodeApplication(
          emitted, "platform=macOS", outDir / "derived", command)
        adhocSignAndVerify(built.bundle, command)
        PackagedMacApp(built.bundle, signed = true)
      case None =>
        val swift = requireSwift(command)
        val result = os.proc(swift, "build")
          .call(stdout = os.Inherit, stderr = os.Inherit, cwd = outDir, check = false)
        if result.exitCode != 0 then
          throw new IllegalStateException(s"$command: swift build failed (exit ${result.exitCode})")
        PackagedMacApp(outDir, signed = false)

  def archiveXcodeApplication(
      pkg: EmittedPackage,
      destination: String,
      archivePath: os.Path,
      derivedData: os.Path,
      teamId: String,
      command: String,
  ): BuiltXcodeApp =
    val app = pkg.xcodeApp.getOrElse(
      throw new IllegalArgumentException(s"$command: checked program does not define a NativeUi application"))
    if os.exists(archivePath) then os.remove.all(archivePath)
    val signing = List("-allowProvisioningUpdates", s"DEVELOPMENT_TEAM=$teamId")
    val settings = queryBuildSettings(
      pkg, destination, "Release", derivedData, signing, command)
    val archive = callXcode(
      archiveArguments(app, destination, archivePath, derivedData, teamId),
      pkg.root, command, "xcodebuild archive")
    if archive.exitCode != 0 then
      throw new IllegalStateException(
        s"$command: xcodebuild archive failed (exit ${archive.exitCode}): ${archive.err.text().take(2048)}")
    val appBundle = archivedApplication(archivePath, command)
    verifyAppBundle(pkg, appBundle, settings, command)

  def archivedApplication(archivePath: os.Path, command: String): os.Path =
    val info = archivePath / "Info.plist"
    val result = callProcess(
      List("plutil", "-extract", "ApplicationProperties.ApplicationPath", "raw", "-o", "-", info.toString),
      None, command, "xcarchive ApplicationPath")
    if result.exitCode != 0 then
      throw new IllegalStateException(s"$command: xcarchive ApplicationPath missing")
    val raw = result.out.text().trim
    val relative = Path.of(raw)
    if raw.isEmpty || relative.isAbsolute || relative.iterator().asScala.exists(_.toString == "..") then
      throw new IllegalStateException(s"$command: invalid xcarchive ApplicationPath '$raw'")
    val products = archivePath / "Products"
    val resolved = os.Path(products.toNIO.resolve(relative).normalize())
    if !resolved.toNIO.startsWith(products.toNIO.normalize()) then
      throw new IllegalStateException(s"$command: xcarchive ApplicationPath escapes Products")
    resolved

  def archiveArguments(
      app: XcodeAppArtifact,
      destination: String,
      archivePath: os.Path,
      derivedData: os.Path,
      teamId: String,
  ): List[String] =
    List("archive") ++ xcodeSelection(app, destination, "Release", derivedData) ++
      List("-archivePath", archivePath.toString, "-allowProvisioningUpdates", s"DEVELOPMENT_TEAM=$teamId")

  private[cli] def xcodeSelection(
      app: XcodeAppArtifact,
      destination: String,
      configuration: String,
      derivedData: os.Path,
  ): List[String] =
    List(
      "-project", app.project, "-scheme", app.scheme,
      "-configuration", configuration, "-destination", destination,
      "-derivedDataPath", derivedData.toString,
    )

  private def callXcode(
      args: List[String],
      cwd: os.Path,
      command: String,
      purpose: String,
  ): os.CommandResult =
    callProcess(List(sys.props.getOrElse("ssc.xcodebuild.command", "xcodebuild")) ++ args,
      Some(cwd), command, purpose)

  private def callProcess(
      args: List[String],
      cwd: Option[os.Path],
      command: String,
      purpose: String,
  ): os.CommandResult =
    try
      os.proc(args).call(
        cwd = cwd.getOrElse(os.pwd), stdout = os.Pipe, stderr = os.Pipe, check = false)
    catch case _: java.io.IOException =>
      throw new IllegalStateException(
        s"$command: ${args.headOption.getOrElse(purpose)} is required for $purpose")

private[cli] def buildV2SwiftPackage(
    sscFile: os.Path,
    outDir: os.Path,
    platform: SwiftPlatform,
    runSwiftBuild: Boolean = false,
    backendBaseUrl: Option[String] = None,
): SwiftV2Cli.EmittedPackage =
  val emitted = SwiftV2Cli.emit(sscFile, outDir, platform, backendBaseUrl = backendBaseUrl)
  if runSwiftBuild then
    if emitted.xcodeApp.nonEmpty then
      val destination = if platform == SwiftPlatform.IOS then "generic/platform=iOS Simulator" else "platform=macOS"
      SwiftV2Cli.buildXcodeApplication(
        emitted, destination, outDir / "derived", s"build --target ${if platform == SwiftPlatform.IOS then "ios" else "macos"}")
    else if platform == SwiftPlatform.IOS then
      throw new IllegalStateException("build --target ios: checked program does not define a NativeUi application")
    else
      val swift = SwiftV2Cli.requireSwift("build --target macos")
      println(s"  Running swift build in ${displayPath(outDir)}...")
      val result = os.proc(swift, "build")
        .call(stdout = os.Inherit, stderr = os.Inherit, cwd = outDir, check = false)
      if result.exitCode != 0 then
        throw new IllegalStateException(s"build --target macos: swift build failed (exit ${result.exitCode})")
  emitted

final class EmitSwiftCmd extends CliCommand:
  def name: String = "emit-swift"
  override def summary: String = "Compile checked v2 CoreIR to a deterministic Swift package"
  override def category: String = "Emit & transpile"
  override def details: List[String] = List(
    "Flags: --target <macos|ios>, -o <dir>, --product-name <name>, --server-url <url>"
  )

  def run(args: List[String]): Unit =
    var target = ActiveFlags.current.target.getOrElse("macos")
    var output: Option[String] = None
    var product: Option[String] = None
    var serverUrl: Option[String] = None
    val files = scala.collection.mutable.ArrayBuffer.empty[String]
    val it = args.iterator
    while it.hasNext do
      it.next() match
        case "--target" if it.hasNext => target = it.next()
        case "-o" | "--output" if it.hasNext => output = Some(it.next())
        case "--product-name" if it.hasNext => product = Some(it.next())
        case "--server-url" if it.hasNext => serverUrl = Some(it.next())
        case flag if flag.startsWith("-") => fail(s"unknown flag $flag")
        case file => files += file
    if files.size != 1 then
      fail("Usage: ssc emit-swift [--target macos|ios] [-o <dir>] [--product-name <name>] <file.ssc>")

    try
      val input = os.Path(files.head, os.pwd)
      val stem = input.last.stripSuffix(".ssc")
      val out = output.map(os.Path(_, os.pwd)).getOrElse(os.pwd / s"$stem-swift")
      val emitted = SwiftV2Cli.emit(input, out, SwiftV2Cli.parsePlatform(target, name), product, serverUrl)
      System.err.println(s"Swift package written to ${displayPath(emitted.root)}")
    catch case e: Exception => fail(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))

  private def fail(message: String): Nothing =
    val rendered = if message.startsWith(s"$name:") then message else s"$name: $message"
    System.err.println(rendered)
    System.exit(1)
    throw new AssertionError()

final class RunSwiftCmd extends CliCommand:
  def name: String = "run-swift"
  override def summary: String = "Build checked v2 CoreIR with SwiftPM and run it on macOS"
  override def category: String = "Emit & transpile"
  override def details: List[String] = List("Flags: --target macos, --server-url <url>; argv after `--`")

  def run(args: List[String]): Unit =
    val (commandArgs, programArgs) = args.span(_ != "--") match
      case (lhs, Nil) => (lhs, Nil)
      case (lhs, _ :: rhs) => (lhs, rhs)
    var target = ActiveFlags.current.target.getOrElse("macos")
    var serverUrl: Option[String] = None
    val files = scala.collection.mutable.ArrayBuffer.empty[String]
    val it = commandArgs.iterator
    while it.hasNext do
      it.next() match
        case "--target" if it.hasNext => target = it.next()
        case "--server-url" if it.hasNext => serverUrl = Some(it.next())
        case flag if flag.startsWith("-") => fail(s"unknown flag $flag")
        case file => files += file
    if files.size != 1 then
      fail("Usage: ssc run-swift [--target macos] <file.ssc> [-- <args...>]")

    val temp = os.temp.dir(prefix = "ssc-swift-")
    try
      val platform = SwiftV2Cli.parsePlatform(target, name)
      if platform == SwiftPlatform.IOS then
        fail("running iOS packages requires ssc run --target ios")
      val input = os.Path(files.head, os.pwd)
      val emitted = SwiftV2Cli.emit(input, temp, platform, backendBaseUrl = serverUrl)
      val exit = SwiftV2Cli.runPackage(emitted, programArgs, name)
      if exit != 0 then System.exit(exit)
    catch case e: Exception => fail(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
    finally scala.util.Try(os.remove.all(temp))

  private def fail(message: String): Nothing =
    val rendered = if message.startsWith(s"$name:") then message else s"$name: $message"
    System.err.println(rendered)
    System.exit(1)
    throw new AssertionError()
