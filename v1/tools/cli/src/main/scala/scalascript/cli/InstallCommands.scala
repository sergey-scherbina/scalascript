package scalascript.cli

/** Script-alias dispatch (`ssc <script>`) and the `ssc install` self-installer.
 *
 *  Extracted verbatim from `Main.scala` (cli-main-helper-split-p3). These are
 *  top-level package-level defs in `scalascript.cli`, so the Main call sites
 *  resolve them unqualified and behave identically. */

/** `ssc <script>` — run a named script from the project .ssc frontmatter.
 *  Falls back to `runCommand` if no project file is found or the script is
 *  not declared, so `ssc somefile.ssc` continues to work as before. */
private[cli] def scriptCommand(cmd: String, extraArgs: List[String]): Unit =
  findProjectSsc() match
    case None => CommandRegistry.dispatch("run", cmd :: extraArgs)
    case Some(sscFile) =>
      val scripts =
        scala.util.Try(scalascript.parser.Parser.parse(os.read(sscFile)).manifest)
          .toOption.flatten.map(_.scripts).getOrElse(Map.empty)
      scripts.get(cmd) match
        case None => CommandRegistry.dispatch("run", cmd :: extraArgs)
        case Some(scriptStr) =>
          val parts = scriptStr.trim.split("\\s+").toList.filterNot(_.isEmpty)
          parts match
            case Nil => ()
            case subCmd :: rest =>
              val cmdArgs = rest ::: sscFile.toString :: extraArgs
              if !CommandRegistry.dispatch(subCmd, cmdArgs) then
                System.err.println(
                  s"ssc: script '$cmd' maps to unknown subcommand '$subCmd'\n" +
                  s"  Defined in: $sscFile")
                System.exit(1)

/** `ssc install [--prefix <dir>]` — install ssc to a system prefix (default: `~/.local`).
 *  Copies `bin/lib/` (JARs, from `ssc.lib.path`) and `std/` (from `ImportResolver.stdPath` —
 *  a separate discovery, since `std/` is not under `lib/`) to `<prefix>/lib/ssc/`, then writes
 *  a self-contained launcher at `<prefix>/bin/ssc`. The launcher hard-codes the prefix so the
 *  binary works from any directory. */
def selfInstallCommand(args: List[String]): Unit =
  val prefix: os.Path = args match
    case "--prefix" :: p :: _ => os.Path(p, os.pwd)
    case Nil                  => os.home / ".local"
    case _ =>
      System.err.println("Usage: ssc install [--prefix <dir>]")
      System.exit(1); os.pwd

  // ssc.lib.path (ImportResolver.libPath) names the lib/ directory itself
  // (specs/arch-lib-path-resolution.md) — this IS the source to copy, not a root above it.
  val srcLib: os.Path = scalascript.imports.ImportResolver.libPath.getOrElse {
    System.err.println(
      "ssc install: cannot determine current install root.\n" +
      "  ssc must be launched via the bin/ssc launcher (ssc.lib.path must be set).")
    System.exit(1); os.pwd
  }

  val destRoot = prefix / "lib" / "ssc"
  val destBin  = prefix / "bin"

  println(s"Installing ssc → $prefix")

  // Copy bin/lib/ (runtime JARs + thin ssc.jar)
  if !os.exists(srcLib) then
    System.err.println(s"ssc install: bin/lib not found at $srcLib"); System.exit(1)
  os.makeDir.all(destRoot / "bin")
  os.walk(srcLib).foreach { src =>
    val dest = destRoot / "bin" / "lib" / src.relativeTo(srcLib)
    if os.isDir(src) then os.makeDir.all(dest)
    else { os.makeDir.all(dest / os.up); os.copy.over(src, dest) }
  }
  val jarCount = os.walk(destRoot / "bin" / "lib").count(_.ext == "jar")
  println(s"  ✓  Library   → $destRoot/bin/lib/  ($jarCount jars)")

  // Copy std/ (standard library .ssc files) — std/ is NOT under lib/, so this goes through
  // ImportResolver's own std-root discovery rather than a path built off srcLib.
  val srcStdOpt = scalascript.imports.ImportResolver.stdPath.map(_ / "std").filter(os.exists)
  srcStdOpt.foreach { srcStd =>
    os.walk(srcStd).foreach { src =>
      val dest = destRoot / "std" / src.relativeTo(srcStd)
      if os.isDir(src) then os.makeDir.all(dest)
      else { os.makeDir.all(dest / os.up); os.copy.over(src, dest) }
    }
    println(s"  ✓  Stdlib    → $destRoot/std/")
  }

  // Write a self-contained launcher with hard-coded prefix
  os.makeDir.all(destBin)
  val launcher = destBin / "ssc"
  os.write.over(launcher,
    s"""#!/usr/bin/env bash
       |exec java -Dssc.lib.path="$destRoot/bin/lib" \\
       |  -cp "$destRoot/bin/lib/standard/jars/*:$destRoot/bin/lib/standard/ssc.jar" \\
       |  scalascript.cli.StandardMain "$$@"
       |""".stripMargin)
  java.nio.file.Files.setPosixFilePermissions(
    launcher.toNIO,
    java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
  println(s"  ✓  Launcher  → $launcher")
  val standardLauncher = destBin / "ssc-standard"
  os.write.over(standardLauncher,
    s"""#!/usr/bin/env bash
       |exec java -Dssc.lib.path="$destRoot/bin/lib" \\
       |  -cp "$destRoot/bin/lib/standard/jars/*:$destRoot/bin/lib/standard/ssc.jar" \\
       |  scalascript.cli.StandardMain "$$@"
       |""".stripMargin)
  java.nio.file.Files.setPosixFilePermissions(
    standardLauncher.toNIO,
    java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
  val toolsLauncher = destBin / "ssc-tools"
  os.write.over(toolsLauncher,
    s"""#!/usr/bin/env bash
       |exec java -Dssc.lib.path="$destRoot/bin/lib" \\
       |  -cp "$destRoot/bin/lib/jars/*:$destRoot/bin/lib/ssc.jar" \\
       |  scalascript.cli.ssc "$$@"
       |""".stripMargin)
  java.nio.file.Files.setPosixFilePermissions(
    toolsLauncher.toNIO,
    java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
  println(s"  ✓  Tier launchers → $standardLauncher, $toolsLauncher")
  println()

  val pathDirs = sys.env.getOrElse("PATH", "").split(':').toSet
  if pathDirs.contains(destBin.toString) then
    println(s"$destBin is already in PATH — done.")
  else
    println(s"Add to PATH (not yet present):")
    println(s"""  echo 'export PATH="$$PATH:$destBin"' >> ~/.zshrc""")
