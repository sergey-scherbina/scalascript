package scalascript.cli

import scalascript.parser.Parser
import scalascript.artifact.{InterfaceExtractor, ArtifactIO}
import RenderHelpers.*

/** `.ssclib` library packaging (`ssc package --lib`) and interface-level
 *  compatibility checking (`ssc check-compat`).
 *
 *  Extracted verbatim from `Main.scala` (cli-main-helper-split-p3) as
 *  top-level package-level defs in `scalascript.cli`; the Main command
 *  classes (PackageCmd / CheckCompatCmd) call these unqualified. */

/** `ssc package [<project-file>] [--target <t>] [--out <dir>] [--compiled]`
 *
 *  Builds distributable packages for all targets in `targets:` frontmatter
 *  (or a single `--target` override).  Default target: `ssc`.
 *
 *  ssc (default)  →  fat JAR at `<out>/name.jar` (interpreter-based)
 *  jvm            →  fully compiled via JvmGen → scala-cli → bytecode JAR
 *  js             →  JS bundle at `<out>/name.js`
 *  web            →  static HTML + assets in `<out>/`
 *
 *  Auto-discovers the project file by directory name when none is given,
 *  exactly like `ssc build`. */
/** `ssc package --lib [<dir>] [-o <out.ssclib>] [--manifest <file>] [--precompile]`
 *
 *  Pack a ScalaScript library source tree into a `.ssclib` ZIP archive.
 *
 *  - `<dir>` defaults to `os.pwd`.
 *  - Manifest is read from `<dir>/ssclib-manifest.yaml` unless overridden.
 *  - Output defaults to `<cacheId>-<version>.ssclib` in the current directory.
 *  - All files under `src/` are included; falls back to all `.ssc` in the root.
 *  - `--precompile` adds `.scim` interfaces under `ir/` for fast consumers and
 *    `ssc check-compat`. */
def packageLib(args: List[String]): Unit =
  import java.util.zip.{ZipOutputStream, ZipEntry}
  import scalascript.imports.SsclibManifest

  var manifestArg: Option[String] = None
  var outputArg:   Option[String] = None
  var dirArg:      Option[String] = None
  var precompile  = false
  var jvmGlueArg: Option[String] = None
  var jsGlueArg:  Option[String] = None
  val it = args.iterator
  while it.hasNext do
    it.next() match
      case "--manifest" if it.hasNext       => manifestArg = Some(it.next())
      case "--output" | "-o" if it.hasNext  => outputArg   = Some(it.next())
      case "--precompile"                   => precompile  = true
      case "--jvm-glue" if it.hasNext       => jvmGlueArg  = Some(it.next())
      case "--js-glue" if it.hasNext        => jsGlueArg   = Some(it.next())
      case d                                => dirArg      = Some(d)

  val dir = os.Path(dirArg.getOrElse(os.pwd.toString), os.pwd)
  if !os.isDir(dir) then
    System.err.println(s"ssc package --lib: not a directory: $dir"); System.exit(1)

  val manifestFile = manifestArg
    .map(m => os.Path(m, os.pwd))
    .getOrElse(dir / SsclibManifest.FileName)

  val baseManifest =
    if os.exists(manifestFile) then
      SsclibManifest.parseString(os.read(manifestFile)) match
        case scala.util.Success(m) => m
        case scala.util.Failure(e) =>
          System.err.println(s"ssc package --lib: invalid manifest: ${e.getMessage}")
          System.exit(1); ???
    else
      val libName = s"local/${dir.last}"
      SsclibManifest(name = libName)

  // Resolve glue paths; --jvm-glue / --js-glue override manifest fields.
  val glueJvmPath: Option[os.Path] = jvmGlueArg.map(p => os.Path(p, os.pwd))
    .orElse(baseManifest.glueJvm.map(p => dir / os.RelPath(p)))
    .filter(os.exists)
  val glueJsPath: Option[os.Path] = jsGlueArg.map(p => os.Path(p, os.pwd))
    .orElse(baseManifest.glueJs.map(p => dir / os.RelPath(p)))
    .filter(os.exists)

  // Build the manifest with glue archive paths baked in.
  val jvmGlueEntry = glueJvmPath.map(_ => "jvm/glue.jar")
    .orElse(jvmGlueArg.map(_ => "jvm/glue.jar"))
  val jsGlueEntry  = glueJsPath.map(_ => "js/glue.js")
    .orElse(jsGlueArg.map(_ => "js/glue.js"))
  val manifest = baseManifest.copy(
    glueJvm = jvmGlueEntry.orElse(baseManifest.glueJvm),
    glueJs  = jsGlueEntry.orElse(baseManifest.glueJs),
  )

  val outName = outputArg.getOrElse(s"${manifest.cacheId}-${manifest.version}.ssclib")
  val outPath = os.Path(outName, os.pwd)

  val srcDir = dir / "src"
  val sources: Seq[(os.Path, String)] =
    if os.exists(srcDir) && os.isDir(srcDir) then
      os.walk(srcDir).filter(os.isFile).map { f => (f, "src/" + f.relativeTo(srcDir).toString) }
    else
      os.list(dir).filter(f => os.isFile(f) && f.ext == "ssc").map { f => (f, f.last) }

  val manifestContent = SsclibManifest.toYaml(manifest)

  os.makeDir.all(outPath / os.up)
  val zip = new ZipOutputStream(new java.io.FileOutputStream(outPath.toIO))
  try
    zip.putNextEntry(new ZipEntry(SsclibManifest.FileName))
    zip.write(manifestContent.getBytes("UTF-8"))
    zip.closeEntry()
    sources.foreach { (file, entryName) =>
      zip.putNextEntry(new ZipEntry(entryName))
      zip.write(os.read.bytes(file))
      zip.closeEntry()
    }
    if precompile then
      sources.filter(_._1.ext == "ssc").foreach { (file, entryName) =>
        val irName = ssclibIrEntryName(entryName)
        val bytes  = ssclibInterfaceBytes(file.toString, os.read.bytes(file))
        zip.putNextEntry(new ZipEntry(irName))
        zip.write(bytes)
        zip.closeEntry()
      }
    glueJvmPath.foreach { jvmJar =>
      zip.putNextEntry(new ZipEntry("jvm/glue.jar"))
      zip.write(os.read.bytes(jvmJar))
      zip.closeEntry()
    }
    glueJsPath.foreach { jsFile =>
      zip.putNextEntry(new ZipEntry("js/glue.js"))
      zip.write(os.read.bytes(jsFile))
      zip.closeEntry()
    }
  finally zip.close()

  val irCount   = if precompile then sources.count(_._1.ext == "ssc") else 0
  val glueCount = glueJvmPath.size + glueJsPath.size
  val fileCount = sources.length + 1 + irCount + glueCount
  val glueNote  = if glueCount > 0 then s" [+${glueCount} glue]" else ""
  println(s"${outPath.last}  ($fileCount files$glueNote) — name=${manifest.name} version=${manifest.version}")

private[cli] def ssclibIrEntryName(sourceEntry: String): String =
  val withoutSrc = sourceEntry.stripPrefix("src/")
  "ir/" + withoutSrc.stripSuffix(".ssc") + ".scim"

private[cli] def ssclibInterfaceBytes(label: String, sourceBytes: Array[Byte]): Array[Byte] =
  val module = Parser.parse(new String(sourceBytes, "UTF-8"))
  if reportCodeBlockParseErrors(module, label) then
    throw RuntimeException(s"cannot precompile $label: parse errors")
  val iface = InterfaceExtractor.extract(module, sourceBytes)
  ArtifactIO.writeInterface(iface).getBytes("UTF-8")

case class CompatReport(removed: List[String], changed: List[String]):
  def isCompatible: Boolean = removed.isEmpty && changed.isEmpty

def checkSsclibCompat(oldPath: os.Path, newPath: os.Path): CompatReport =
  val oldSymbols = publicSsclibSymbols(oldPath)
  val newSymbols = publicSsclibSymbols(newPath)
  val removed = (oldSymbols.keySet -- newSymbols.keySet).toList.sorted
  val changed = oldSymbols.keySet.intersect(newSymbols.keySet).toList.sorted.filter { key =>
    oldSymbols(key) != newSymbols(key)
  }
  CompatReport(removed, changed)

private[cli] def publicSsclibSymbols(path: os.Path): Map[String, String] =
  if !os.exists(path) then throw RuntimeException(s"archive not found: $path")
  val interfaces = readSsclibInterfaces(path)
  interfaces.values.toList.flatMap(iface =>
    iface.exports.flatMap(publicSymbolShapes) ++ iface.externDefs.flatMap(publicSymbolShapes)
  ).toMap

private[cli] def publicSymbolShapes(sym: scalascript.ir.ExportedSymbol): List[(String, String)] =
  if sym.isInternal then Nil
  else
    val key = if sym.fqn.nonEmpty then sym.fqn else sym.name
    (key -> s"${sym.kind}:${sym.tpe}") :: sym.nested.flatMap(publicSymbolShapes)

private[cli] def readSsclibInterfaces(path: os.Path): Map[String, scalascript.ir.ModuleInterface] =
  import java.util.zip.ZipInputStream
  import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

  val scim = scala.collection.mutable.LinkedHashMap.empty[String, scalascript.ir.ModuleInterface]
  val sources = scala.collection.mutable.LinkedHashMap.empty[String, Array[Byte]]
  val zis = ZipInputStream(ByteArrayInputStream(os.read.bytes(path)))
  try
    var entry = zis.getNextEntry
    while entry != null do
      if !entry.isDirectory then
        val out = ByteArrayOutputStream()
        zis.transferTo(out)
        val bytes = out.toByteArray
        val name = entry.getName
        if name.startsWith("ir/") && name.endsWith(".scim") then
          ArtifactIO.readInterface(bytes) match
            case Right(iface) => scim(name) = iface
            case Left(err)    => throw RuntimeException(s"${path.last}: $name: $err")
        else if name.startsWith("src/") && name.endsWith(".ssc") then
          sources(name) = bytes
      entry = zis.getNextEntry
  finally zis.close()

  if scim.nonEmpty then scim.toMap
  else
    sources.map { (name, bytes) =>
      ArtifactIO.readInterface(ssclibInterfaceBytes(name, bytes)) match
        case Right(iface) => name -> iface
        case Left(err)    => throw RuntimeException(s"${path.last}: generated $name interface unreadable: $err")
    }.toMap
