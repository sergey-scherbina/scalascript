package scalascript.cli

// CLI `ssc plugin ...` subcommands: install / list / uninstall / check /
// pack / registry. Extracted from Main.scala. Everything here references
// os-lib and scalascript.compiler.plugin.* by fully-qualified name, so no
// imports are required.

private def pluginsDir: os.Path = os.home / ".scalascript" / "compiler" / "plugins"

final class PluginCmd extends CliCommand:
  def name = "plugin"
  override def summary = "Manage installed .sscpkg plugins"
  override def category = "Dependencies & plugins"
  override def details = List("Subs: install | list | uninstall | check | pack | registry")
  def run(args: List[String]): Unit =
    args match
      case "install"   :: rest => pluginInstall(rest)
      case "list"      :: _    => pluginList()
      case "uninstall" :: rest => pluginUninstall(rest)
      case "check"     :: rest => pluginCheck(rest)
      case "pack"      :: rest => pluginPack(rest)
      case "registry"  :: rest => pluginRegistryCommand(rest)
      case sub :: _            =>
        System.err.println(s"Unknown plugin subcommand: '$sub'")
        System.err.println("Usage: ssc plugin install|list|uninstall|check|pack|registry ...")
        System.exit(1)
      case Nil =>
        System.err.println("Usage: ssc plugin install|list|uninstall|check|pack|registry ...")
        System.exit(1)

/** `ssc plugin install <path-or-url-or-name>` — copy/download a
 *  `.sscpkg` to `~/.scalascript/compiler/plugins/` and print a confirmation.
 *  Short names (e.g. "redis") are resolved via the local registry
 *  (`~/.scalascript/registry.yaml`). */
def pluginInstall(args: List[String]): Unit =
  val rawSrc = args.headOption.getOrElse {
    System.err.println("Usage: ssc plugin install <path-or-url-or-name>"); System.exit(1); ""
  }
  try
    if !rawSrc.startsWith("http://") && !rawSrc.startsWith("https://") && !os.exists(os.Path(rawSrc, os.pwd)) then
      scalascript.compiler.plugin.LocalRegistry.resolve(rawSrc).foreach { entry =>
        println(s"Resolved '$rawSrc' → ${entry.url}  (${entry.description})")
      }
    val installed = scalascript.compiler.plugin.RemotePluginInstaller.install(rawSrc, pluginsDir)
    println(s"Installed ${installed.manifest.id} ${installed.manifest.version} → ${installed.path}")
  catch
    case e: RuntimeException =>
      System.err.println(s"plugin install: ${e.getMessage}")
      System.exit(1)

/** `ssc plugin list` — print every `.sscpkg` in `~/.scalascript/compiler/plugins/`. */
def pluginList(): Unit =
  if !os.isDir(pluginsDir) then
    println("(no plugins installed)"); return
  val pkgs = os.list(pluginsDir).filter(_.ext == "sscpkg").sorted
  if pkgs.isEmpty then println("(no plugins installed)")
  else
    pkgs.foreach { p =>
      val m = scala.util.Try(scalascript.compiler.plugin.SscpkgLoader.load(p).manifest)
      m match
        case scala.util.Success(manifest) =>
          val kinds   = manifest.kind.mkString(", ")
          val targets = if manifest.targets.isEmpty then "" else s"  targets=${manifest.targets.mkString(",")}"
          println(f"${manifest.id}%-30s  ${manifest.version}  spi=${manifest.spiVersion}  [$kinds]$targets")
        case scala.util.Failure(e) =>
          println(s"${p.last}  [parse error: ${e.getMessage}]")
    }

/** `ssc plugin uninstall <id>` — remove the installed `.sscpkg` for that id. */
def pluginUninstall(args: List[String]): Unit =
  val id = args.headOption.getOrElse {
    System.err.println("Usage: ssc plugin uninstall <id>"); System.exit(1); ""
  }
  if !os.isDir(pluginsDir) then
    System.err.println(s"plugin uninstall: '$id' not installed"); System.exit(1)
  val pkgs = os.list(pluginsDir)
    .filter(p => p.ext == "sscpkg" && p.last.startsWith(id + "-") || p.last == id + ".sscpkg")
  if pkgs.isEmpty then
    System.err.println(s"plugin uninstall: '$id' not installed"); System.exit(1)
  pkgs.foreach { p => os.remove(p); println(s"Removed $p") }

/** `ssc plugin check <id>` — verify that the installed plugin's spiVersion
 *  matches the current compiler's supported SPI version. */
def pluginCheck(args: List[String]): Unit =
  val id = args.headOption.getOrElse {
    System.err.println("Usage: ssc plugin check <id>"); System.exit(1); ""
  }
  if !os.isDir(pluginsDir) then
    System.err.println(s"plugin check: '$id' not installed"); System.exit(1)
  val pkgs = os.list(pluginsDir)
    .filter(p => p.ext == "sscpkg" && (p.last.startsWith(id + "-") || p.last == id + ".sscpkg"))
  if pkgs.isEmpty then
    System.err.println(s"plugin check: '$id' not installed"); System.exit(1)
  pkgs.foreach { p =>
    val m = scalascript.compiler.plugin.SscpkgLoader.load(p).manifest
    val supported = scalascript.backend.spi.SpiVersion.Current
    if m.spiVersion == supported then
      println(s"${m.id} ${m.version}: OK  (spiVersion=${m.spiVersion})")
    else
      println(s"${m.id} ${m.version}: INCOMPATIBLE  (plugin=${m.spiVersion}, compiler=$supported)")
      System.exit(1)
  }

/** `ssc plugin pack <dir> [-o output.sscpkg]` — pack a source tree containing
 *  a `manifest.yaml` + optional `sources/`, `runtime/`, `intrinsics/` into
 *  a `.sscpkg` archive. */
def pluginPack(args: List[String]): Unit =
  import java.util.zip.{ZipOutputStream, ZipEntry}

  var output: Option[String] = None
  var dirArg: Option[String] = None
  val it = args.iterator
  while it.hasNext do
    it.next() match
      case "-o" | "--output" if it.hasNext => output = Some(it.next())
      case d => dirArg = Some(d)

  val dir = os.Path(dirArg.getOrElse {
    System.err.println("Usage: ssc plugin pack <dir> [-o output.sscpkg]"); System.exit(1); ""
  }, os.pwd)
  if !os.isDir(dir) then
    System.err.println(s"plugin pack: not a directory: $dir"); System.exit(1)

  val manifestPath = dir / "manifest.yaml"
  if !os.exists(manifestPath) then
    System.err.println(s"plugin pack: missing manifest.yaml in $dir"); System.exit(1)

  val manifest = scalascript.compiler.plugin.SscpkgManifest.parseString(os.read(manifestPath)).get
  val outName  = output.getOrElse(s"${manifest.id}-${manifest.version}.sscpkg")
  val outPath  = os.Path(outName, os.pwd)

  val zip = new ZipOutputStream(new java.io.FileOutputStream(outPath.toIO))
  try
    // Walk every file under dir and pack it, preserving the subtree.
    os.walk(dir).filter(os.isFile).foreach { file =>
      val rel = file.relativeTo(dir).toString
      zip.putNextEntry(new ZipEntry(rel))
      zip.write(os.read.bytes(file))
      zip.closeEntry()
    }
  finally zip.close()

  val fileCount = os.walk(dir).count(os.isFile)
  println(s"$outName  ($fileCount files) — id=${manifest.id} version=${manifest.version}")

/** `ssc plugin registry <sub> [args]` — manage local registry.
 *
 *  Subcommands:
 *    list                  — print all registry entries
 *    add <id> <url>        — add/update an entry in ~/.scalascript/registry.yaml
 *    remove <id>           — remove an entry
 *    search <query>        — search entries by id or description substring
 */
def pluginRegistryCommand(args: List[String]): Unit =
  import scalascript.compiler.plugin.LocalRegistry
  val regPath = os.home / ".scalascript" / "registry.yaml"
  args match
    case "list" :: _ =>
      val entries = LocalRegistry.loadAll()
      if entries.isEmpty then println("(no registry entries)")
      else entries.foreach { e =>
        val ver  = if e.version.nonEmpty then s"  v${e.version}" else ""
        val desc = if e.description.nonEmpty then s"  — ${e.description}" else ""
        println(f"${e.id}%-40s$ver$desc")
      }
    case "add" :: id :: url :: rest =>
      val description = rest.headOption.getOrElse("")
      val existing    = if os.exists(regPath) then LocalRegistry.loadAll() else Nil
      val updated     = existing.filterNot(_.id == id) :+
                        LocalRegistry.Entry(id, url, description = description)
      os.makeDir.all(regPath / os.up)
      os.write.over(regPath, LocalRegistry.toYaml(updated))
      println(s"Registry: added '$id'  →  $url")
    case "remove" :: id :: _ =>
      if !os.exists(regPath) then
        System.err.println("registry remove: no registry file found"); System.exit(1)
      val entries = LocalRegistry.loadAll()
      val updated = entries.filterNot(_.id == id)
      if updated.size == entries.size then
        System.err.println(s"registry remove: '$id' not found"); System.exit(1)
      os.write.over(regPath, LocalRegistry.toYaml(updated))
      println(s"Registry: removed '$id'")
    case "search" :: query :: _ =>
      val q       = query.toLowerCase
      val matches = LocalRegistry.loadAll().filter { e =>
        e.id.toLowerCase.contains(q) || e.description.toLowerCase.contains(q)
      }
      if matches.isEmpty then println("(no matches)")
      else matches.foreach { e =>
        val desc = if e.description.nonEmpty then s"  — ${e.description}" else ""
        println(s"${e.id}$desc")
      }
    case sub :: _ =>
      System.err.println(s"Unknown registry subcommand: '${sub}'")
      System.err.println("Usage: ssc plugin registry list|add|remove|search ...")
      System.exit(1)
    case Nil =>
      System.err.println("Usage: ssc plugin registry list|add|remove|search ...")
      System.exit(1)

