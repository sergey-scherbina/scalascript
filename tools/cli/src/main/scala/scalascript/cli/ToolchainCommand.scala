package scalascript.cli

import scala.sys.process.*
import scala.util.Try

/** `ssc toolchain` — detect and auto-install tools required by each build target.
 *
 *  Subcommands:
 *  {{{
 *  ssc toolchain check                       # check all known tools
 *  ssc toolchain check --target mobile-ios   # check only what that target needs
 *  ssc toolchain install --target mobile-ios # auto-install (Coursier / Homebrew / mise / apt)
 *  ssc toolchain list                        # table of installed tools + versions
 *  }}}
 */
object ToolchainCommand:

  def run(args: List[String]): Unit =
    args match
      case "check"         :: rest => checkCommand(rest)
      case "install"       :: rest => installCommand(rest)
      case "list"          :: rest => listCommand(rest)
      case "setup-signing" :: rest => setupSigningCommand(rest)
      case ("help" | "--help" | "-h") :: _ => printHelp()
      case _ =>
        System.err.println("Usage: ssc toolchain {check|install|list|setup-signing} [--target <target>]")
        System.exit(2)

  // ── data model ────────────────────────────────────────────────────────────

  enum Os { case Mac, Linux, Windows, Unknown }

  private val currentOs: Os =
    val name = System.getProperty("os.name", "").toLowerCase
    if name.contains("mac") then Os.Mac
    else if name.contains("linux") then Os.Linux
    else if name.contains("win") then Os.Windows
    else Os.Unknown

  /** A tool that may or may not be installed. */
  case class Tool(
    id:          String,           // machine identifier, e.g. "kotlin"
    displayName: String,           // e.g. "Kotlin 2.0"
    checkCmd:    String,           // shell command whose success = "installed"
    versionCmd:  String,           // command that prints the version
    installer:   Option[Installer] // None = manual only
  )

  case class Installer(
    method:  String,     // "coursier" | "homebrew" | "mise" | "apt" | "manual"
    cmd:     String,     // shell command to run
    sizeHint: String     // rough download size for display
  )

  /** Per-target required tool sets. */
  private[cli] val targetTools: Map[String, List[String]] = Map(
    "web"            -> List("node", "jdk"),
    "desktop"        -> List("node", "jdk", "electron"),
    "mobile-android" -> List("jdk", "kotlin", "kotlin-native", "android-sdk"),
    "ios"            -> List("swift", "xcode", "ios-deploy", "fastlane"),
    "mobile-ios"     -> List("swift", "xcode", "ios-deploy", "fastlane"),
    "macos"          -> List("jdk", "swift", "fastlane"),
    "desktop-macos"  -> List("jdk", "swift", "fastlane"),
    "desktop-linux"  -> List("jdk", "scala-native", "gtk"),
    "desktop-windows"-> List("jdk", "graalvm"),
    "all"            -> List("jdk", "node", "kotlin", "kotlin-native", "swift",
                             "scala-native", "graalvm", "gtk", "android-sdk",
                             "ios-deploy", "fastlane")
  )

  /** All known tools with detection + install recipes. */
  private def allTools: Map[String, Tool] =
    val cs = coursierInstaller
    val hb = homebrewInstaller
    val mi = miseInstaller
    val ap = aptInstaller
    Map(
      "jdk" -> Tool("jdk", "JDK 21",
        checkCmd   = "java -version",
        versionCmd = "java -version 2>&1 | head -1",
        installer  = currentOs match
          case Os.Mac     => Some(cs("java:temurin:21", "~310 MB"))
          case Os.Linux   => Some(cs("java:temurin:21", "~310 MB"))
          case Os.Windows => Some(cs("java:temurin:21", "~310 MB"))
          case Os.Unknown => None
      ),
      "node" -> Tool("node", "Node.js 20",
        checkCmd   = "node --version",
        versionCmd = "node --version",
        installer  = currentOs match
          case Os.Mac     => Some(mi("node@20", "~60 MB"))
          case Os.Linux   => Some(mi("node@20", "~60 MB"))
          case Os.Windows => Some(mi("node@20", "~60 MB"))
          case Os.Unknown => None
      ),
      "kotlin" -> Tool("kotlin", "Kotlin 2.0",
        checkCmd   = "kotlinc -version",
        versionCmd = "kotlinc -version 2>&1 | head -1",
        installer  = Some(cs("org.jetbrains.kotlin:kotlin-compiler:2.0.0", "~12 MB"))
      ),
      "kotlin-native" -> Tool("kotlin-native", "Kotlin/Native",
        checkCmd   = "kotlinc-native -version",
        versionCmd = "kotlinc-native -version 2>&1 | head -1",
        installer  = Some(cs("org.jetbrains.kotlin:kotlin-native-compiler-embeddable:2.0.0", "~185 MB"))
      ),
      "swift" -> Tool("swift", "Swift 5.10",
        checkCmd   = "swift --version",
        versionCmd = "swift --version 2>&1 | head -1",
        installer  = currentOs match
          case Os.Mac     => None  // bundled with Xcode
          case Os.Linux   => Some(Installer("apt", "apt-get install -y swift", "~500 MB"))
          case _          => None
      ),
      "scala-native" -> Tool("scala-native", "Scala Native 0.5",
        checkCmd   = "clang --version",
        versionCmd = "clang --version 2>&1 | head -1",
        installer  = currentOs match
          case Os.Mac     => Some(hb("llvm", "~1.2 GB"))
          case Os.Linux   => Some(ap("clang", "~200 MB"))
          case _          => None
      ),
      "graalvm" -> Tool("graalvm", "GraalVM 21",
        checkCmd   = "native-image --version",
        versionCmd = "native-image --version 2>&1 | head -1",
        installer  = Some(cs("org.graalvm.nativeimage:native-image:21.0.2", "~150 MB"))
      ),
      "android-sdk" -> Tool("android-sdk", "Android SDK",
        checkCmd   = "sdkmanager --version",
        versionCmd = "sdkmanager --version 2>&1 | head -1",
        installer  = None  // requires manual Android Studio / cmdline-tools install
      ),
      "xcode" -> Tool("xcode", "Xcode 15+",
        checkCmd   = "xcodebuild -version",
        versionCmd = "xcodebuild -version 2>&1 | head -1",
        installer  = None  // App Store only
      ),
      "electron" -> Tool("electron", "Electron",
        checkCmd   = "electron --version",
        versionCmd = "electron --version",
        installer  = Some(Installer("npm", "npm install -g electron", "~90 MB"))
      ),
      "ios-deploy" -> Tool("ios-deploy", "ios-deploy",
        checkCmd   = "ios-deploy --version",
        versionCmd = "ios-deploy --version",
        installer  = currentOs match
          case Os.Mac => Some(homebrewInstaller("ios-deploy", "~5 MB"))
          case _      => None
      ),
      "fastlane" -> Tool("fastlane", "fastlane",
        checkCmd   = "fastlane --version",
        versionCmd = "fastlane --version 2>&1 | head -1",
        installer  = currentOs match
          case Os.Mac => Some(homebrewInstaller("fastlane", "~50 MB"))
          case _      => None
      ),
      "gtk" -> Tool("gtk", "GTK 4",
        checkCmd   = "pkg-config --modversion gtk4",
        versionCmd = "pkg-config --modversion gtk4",
        installer  = currentOs match
          case Os.Mac   => Some(hb("gtk4", "~80 MB"))
          case Os.Linux => Some(ap("libgtk-4-dev", "~80 MB"))
          case _        => None
      )
    )

  private def coursierInstaller(dep: String, size: String) =
    Installer("coursier", s"cs install $dep", size)
  private def homebrewInstaller(pkg: String, size: String) =
    Installer("homebrew", s"brew install $pkg", size)
  private def miseInstaller(pkg: String, size: String) =
    Installer("mise", s"mise install $pkg", size)
  private def aptInstaller(pkg: String, size: String) =
    Installer("apt", s"sudo apt-get install -y $pkg", size)

  // ── probe ─────────────────────────────────────────────────────────────────

  private def isInstalled(tool: Tool): Boolean =
    Try(s"${tool.checkCmd}" ! ProcessLogger(_ => (), _ => ())).map(_ == 0).getOrElse(false)

  private def installedVersion(tool: Tool): Option[String] =
    Try {
      val lines = scala.collection.mutable.ListBuffer.empty[String]
      tool.versionCmd ! ProcessLogger(l => lines += l, l => lines += l)
      lines.find(_.nonEmpty).getOrElse("").trim
    }.toOption.filter(_.nonEmpty)

  // ── subcommands ───────────────────────────────────────────────────────────

  private def checkCommand(args: List[String]): Unit =
    val target = activeTarget(args)
    val tools  = toolsForTarget(target)
    val installed   = tools.filter(t => isInstalled(allTools(t)))
    val missing     = tools.filterNot(t => isInstalled(allTools(t)))

    target.foreach(t => println(s"Target: $t"))
    println()
    println("  Required tools:")
    println(s"  ${"─" * 66}")
    tools.foreach { id =>
      val tool = allTools(id)
      val ok   = installed.contains(id)
      val mark = if ok then "✓" else "✗"
      val loc  = if ok then installedVersion(tool).getOrElse("installed") else "not found"
      println(f"  │  [$mark] ${tool.displayName}%-20s $loc%-40s│")
    }
    println(s"  ${"─" * 66}")
    println()

    if missing.isEmpty then
      println("  All required tools are installed.")
    else
      println(s"  ${missing.length} tool(s) missing. Run:")
      val tFlag = target.map(t => s" --target $t").getOrElse("")
      println(s"    ssc toolchain install$tFlag")

  private def installCommand(args: List[String]): Unit =
    val target = activeTarget(args)
    val tools  = toolsForTarget(target)
    val missing = tools.filterNot(t => isInstalled(allTools(t)))

    if missing.isEmpty then
      println("  All required tools are already installed.")
      return

    val (autoTools, manualTools) = missing.partition(id => allTools(id).installer.isDefined)

    if autoTools.nonEmpty then
      println("  Installing automatically:")
      autoTools.foreach { id =>
        val tool      = allTools(id)
        val installer = tool.installer.get
        print(s"  ↳  ${tool.displayName} via ${installer.method} (${installer.sizeHint}) … ")
        Console.flush()
        val exitCode = installer.cmd ! ProcessLogger(_ => (), _ => ())
        if exitCode == 0 then println("✓")
        else println(s"✗  (exit $exitCode — try manually: ${installer.cmd})")
      }

    if manualTools.nonEmpty then
      println()
      println("  Requires manual installation:")
      println(s"  ${"─" * 66}")
      manualTools.foreach { id =>
        val tool = allTools(id)
        println(s"  │  ${tool.displayName}")
        printManualInstructions(id)
      }
      println(s"  ${"─" * 66}")

    val tFlag = target.map(t => s" --target $t").getOrElse("")
    println()
    println(s"  After installing, re-run: ssc toolchain check$tFlag")

  private def listCommand(args: List[String]): Unit =
    val target     = activeTarget(args)
    val toolIds    = toolsForTarget(target)
    val header     = f"  ${"Tool"}%-22s ${"Version"}%-35s ${"Status"}%-10s"
    println(header)
    println(s"  ${"─" * 68}")
    toolIds.foreach { id =>
      val tool = allTools(id)
      val ok   = isInstalled(tool)
      val ver  = if ok then installedVersion(tool).getOrElse("installed") else "—"
      val stat = if ok then "installed" else "missing"
      println(f"  ${tool.displayName}%-22s ${ver}%-35s $stat%-10s")
    }

  // ── helpers ───────────────────────────────────────────────────────────────

  private def activeTarget(args: List[String]): Option[String] =
    // --target is consumed globally by GlobalFlags.parse; read from ActiveFlags.
    // Fall back to inline --target in args for the rare case this is called
    // without going through the normal dispatch path (e.g. tests).
    ActiveFlags.current.target
      .orElse(args.dropWhile(_ != "--target").drop(1).headOption)

  private def toolsForTarget(target: Option[String]): List[String] =
    target match
      case Some(t) =>
        targetTools.getOrElse(t, {
          System.err.println(s"Unknown target: $t")
          System.err.println(s"Known targets: ${targetTools.keys.toList.sorted.mkString(", ")}")
          System.exit(2)
          Nil
        })
      case None => targetTools("all").distinct

  private def printManualInstructions(id: String): Unit =
    id match
      case "xcode" =>
        println("  │  → App Store: apps.apple.com/app/xcode/id497799835")
        println("  │  → or: xcode-select --install  (command-line tools only)")
      case "android-sdk" =>
        println("  │  → https://developer.android.com/studio#command-line-tools-only")
        println("  │  → Extract cmdline-tools, then: sdkmanager --install \"platforms;android-34\"")
      case "swift" if currentOs == Os.Windows =>
        println("  │  → https://www.swift.org/download/")
      case _ =>
        val tool = allTools(id)
        println(s"  │  → Install ${tool.displayName} and ensure it is on PATH")

  private def setupSigningCommand(args: List[String]): Unit =
    val target = activeTarget(args)
    if os.proc("fastlane", "--version")
        .call(check = false, stdout = os.Pipe, stderr = os.Pipe).exitCode != 0 then
      System.err.println("Error: fastlane is required for setup-signing.\nInstall: brew install fastlane")
      System.exit(1)
    target match
      case Some("ios") | Some("mobile-ios") =>
        println("Setting up fastlane match for iOS...")
        println("  Running: fastlane match init")
        os.proc("fastlane", "match", "init")
          .call(stdout = os.Inherit, stderr = os.Inherit, check = false)
        println()
        println("  To fetch/create distribution certificates, run:")
        println("    fastlane match appstore")
        println("    fastlane match development")
      case Some("macos") | Some("desktop-macos") =>
        println("Setting up fastlane match for macOS...")
        println("  Running: fastlane match init")
        os.proc("fastlane", "match", "init")
          .call(stdout = os.Inherit, stderr = os.Inherit, check = false)
        println()
        println("  To fetch/create Developer ID and Mac App Store certificates, run:")
        println("    fastlane match developer_id_application")
        println("    fastlane match appstore  (Mac App Store)")
      case Some(t) =>
        System.err.println(s"setup-signing: unsupported target '$t'  (valid: ios, macos)")
        System.exit(1)
      case None =>
        System.err.println("setup-signing: --target is required  (valid: ios, macos)")
        System.exit(1)

  private def printHelp(): Unit =
    println("Usage: ssc toolchain <subcommand> [options]")
    println()
    println("Subcommands:")
    println("  check         [--target <t>]   Detect installed tools (all or target-specific)")
    println("  install       [--target <t>]   Auto-install missing tools where possible")
    println("  list          [--target <t>]   Print installed tools and versions")
    println("  setup-signing --target <t>     Initialize fastlane match for code signing (ios|macos)")
    println()
    println("Targets:")
    targetTools.keys.toList.sorted.foreach(t => println(s"  $t"))
    println()
    println("Examples:")
    println("  ssc toolchain check")
    println("  ssc toolchain check --target ios")
    println("  ssc toolchain install --target ios")
    println("  ssc toolchain install --target mobile-android")
    println("  ssc toolchain list")
    println("  ssc toolchain setup-signing --target ios")
    println("  ssc toolchain setup-signing --target macos")
