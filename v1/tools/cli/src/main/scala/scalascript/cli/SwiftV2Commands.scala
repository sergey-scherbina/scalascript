package scalascript.cli

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import _root_.ssc.swift.{SwiftBackend, SwiftPlatform}

private[cli] object SwiftV2Cli:
  final case class EmittedPackage(root: os.Path, product: String, platform: SwiftPlatform)

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
  ): EmittedPackage =
    if !os.exists(input) || !os.isFile(input) then
      throw new IllegalArgumentException(s"file not found: $input")

    RunV2.loadPluginJars()
    _root_.ssc.Runtime.argv = Nil
    _root_.ssc.bridge.FrontendBridge.resetState()
    _root_.ssc.bridge.PluginBridge.loadAll()
    val source = Files.readString(input.toNIO, StandardCharsets.UTF_8)
    val program = _root_.ssc.bridge.FrontendBridge.convertSource(
      source,
      Some((input / os.up).toIO),
    )
    val product = SwiftBackend.productName(
      requestedProduct.getOrElse(input.last.stripSuffix(".ssc"))
    )

    // These paths are wholly generator-owned. Clearing them prevents stale v1
    // SwiftUI sources from being compiled into an otherwise-v2 package while
    // preserving user resources and unrelated files under an explicit -o dir.
    scala.util.Try(os.remove.all(output / "Sources"))
    scala.util.Try(os.remove(output / "Package.swift"))
    os.makeDir.all(output)
    val generated = SwiftBackend.generate(program, product, platform)
    generated.writeTo(output.toNIO)
    EmittedPackage(output, generated.executable, platform)

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
      (List(swift, "run", "--package-path", pkg.root.toString, "--quiet", pkg.product) ++ programArgs)*
    ).inheritIO().start()
    val hook = new Thread(() => process.destroy())
    Runtime.getRuntime.addShutdownHook(hook)
    try process.waitFor()
    finally
      try Runtime.getRuntime.removeShutdownHook(hook)
      catch case _: IllegalStateException => ()

private[cli] def buildV2SwiftPackage(
    sscFile: os.Path,
    outDir: os.Path,
    platform: SwiftPlatform,
    runSwiftBuild: Boolean = false,
): SwiftV2Cli.EmittedPackage =
  val emitted = SwiftV2Cli.emit(sscFile, outDir, platform)
  if runSwiftBuild then
    if platform == SwiftPlatform.IOS then
      throw new IllegalStateException(
        "build --target ios: the v2 NativeUi application target is not generated yet; no v1 fallback was attempted"
      )
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
    "Flags: --target <macos|ios>, -o <dir>, --product-name <name>"
  )

  def run(args: List[String]): Unit =
    var target = ActiveFlags.current.target.getOrElse("macos")
    var output: Option[String] = None
    var product: Option[String] = None
    val files = scala.collection.mutable.ArrayBuffer.empty[String]
    val it = args.iterator
    while it.hasNext do
      it.next() match
        case "--target" if it.hasNext => target = it.next()
        case "-o" | "--output" if it.hasNext => output = Some(it.next())
        case "--product-name" if it.hasNext => product = Some(it.next())
        case flag if flag.startsWith("-") => fail(s"unknown flag $flag")
        case file => files += file
    if files.size != 1 then
      fail("Usage: ssc emit-swift [--target macos|ios] [-o <dir>] [--product-name <name>] <file.ssc>")

    try
      val input = os.Path(files.head, os.pwd)
      val stem = input.last.stripSuffix(".ssc")
      val out = output.map(os.Path(_, os.pwd)).getOrElse(os.pwd / s"$stem-swift")
      val emitted = SwiftV2Cli.emit(input, out, SwiftV2Cli.parsePlatform(target, name), product)
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
  override def details: List[String] = List("Flags: --target macos; argv after `--`")

  def run(args: List[String]): Unit =
    val (commandArgs, programArgs) = args.span(_ != "--") match
      case (lhs, Nil) => (lhs, Nil)
      case (lhs, _ :: rhs) => (lhs, rhs)
    var target = ActiveFlags.current.target.getOrElse("macos")
    val files = scala.collection.mutable.ArrayBuffer.empty[String]
    val it = commandArgs.iterator
    while it.hasNext do
      it.next() match
        case "--target" if it.hasNext => target = it.next()
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
      val emitted = SwiftV2Cli.emit(input, temp, platform)
      val exit = SwiftV2Cli.runPackage(emitted, programArgs, name)
      if exit != 0 then System.exit(exit)
    catch case e: Exception => fail(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
    finally scala.util.Try(os.remove.all(temp))

  private def fail(message: String): Nothing =
    val rendered = if message.startsWith(s"$name:") then message else s"$name: $message"
    System.err.println(rendered)
    System.exit(1)
    throw new AssertionError()
