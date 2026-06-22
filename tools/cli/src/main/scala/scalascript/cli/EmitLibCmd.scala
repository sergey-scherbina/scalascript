package scalascript.cli

import scalascript.codegen.{JsLibPackager, JvmLibPackager}
import scalascript.codegen.rust.RustLibPackager

/** `ssc emit-lib` — emit a ScalaScript feature as a standalone, host-native library package
 *  (Task B, `specs/polyglot-libraries.md` §4), with no `.ssc`/ScalaScript runtime dependency at the
 *  consumer's edge. Supported today: **JS optics** (an `@scalascript/optics` npm ESM package) and
 *  **JVM optics** (a buildable `ssc-optics` sbt Scala library). More host/feature combos follow the
 *  same packager shape. */
final class EmitLibCmd extends CliCommand:
  def name = "emit-lib"
  override def summary =
    "Emit a feature as a standalone per-host library (e.g. optics → npm package / sbt library)"
  override def category = "Emit & transpile"
  override def details = List(
    "Flags: --host <js|jvm|rust> (default: js), --feature <optics> (default: optics),",
    "       -o <dir> (default: ./<feature>-<host>-lib/), --version <semver> (default: 0.1.0)",
    "Supported today: --host js|jvm|rust --feature optics",
  )

  def run(args: List[String]): Unit =
    var host                   = "js"
    var feature                = "optics"
    var version                = "0.1.0"
    var outDir: Option[String] = None
    val it = args.iterator
    while it.hasNext do
      it.next() match
        case "--host"    if it.hasNext       => host    = it.next()
        case "--feature" if it.hasNext       => feature = it.next()
        case "--version" if it.hasNext       => version = it.next()
        case "-o" | "--output" if it.hasNext => outDir  = Some(it.next())
        case other =>
          System.err.println(s"emit-lib: unknown argument '$other'")
          sys.exit(1)

    val files: Map[String, String] = (host, feature) match
      case ("js",   "optics") => JsLibPackager.opticsNpmPackage(version)
      case ("jvm",  "optics") => JvmLibPackager.opticsScalaPackage(version)
      case ("rust", "optics") => RustLibPackager.opticsRustPackage(version)
      case _ =>
        System.err.println(
          s"emit-lib: unsupported combination --host $host --feature $feature " +
          "(supported: --host js|jvm|rust --feature optics)")
        sys.exit(1)

    val dir = os.Path(outDir.getOrElse(s"$feature-$host-lib"), os.pwd)
    os.makeDir.all(dir)
    for (fileName, content) <- files.toList.sortBy(_._1) do
      val out = dir / os.RelPath(fileName)   // may be nested (e.g. src/main/scala/…)
      os.makeDir.all(out / os.up)
      os.write.over(out, content)
      System.err.println(s"Wrote $out (${content.length} bytes)")
    System.err.println(s"$feature $host library written to $dir")
