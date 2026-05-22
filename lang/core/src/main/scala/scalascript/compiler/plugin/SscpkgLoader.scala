package scalascript.compiler.plugin

import java.util.zip.ZipFile
import scala.jdk.CollectionConverters.*

/** Reads a `.sscpkg` ZIP archive and extracts its contributions.
 *  See docs/milestones.md §v1.7 Tier 2.
 *
 *  Archive layout:
 *  {{{
 *  mypackage-1.2.3.sscpkg
 *  ├── manifest.yaml          — SscpkgManifest
 *  ├── sources/               — .ssc prelude files
 *  ├── runtime/               — per-backend helper strings (jvm.scala, js.js, …)
 *  ├── intrinsics/            — compiled IntrinsicImpl JARs
 *  └── subprocess/            — optional out-of-process executables
 *  }}}
 */
object SscpkgLoader:

  /** All contributions extracted from one `.sscpkg`. */
  case class LoadResult(
    manifest:       SscpkgManifest,
    intrinsicJars:  List[os.Path],        // extracted to a temp dir
    runtimeStrings: Map[String, String],  // backendId → helper code
    sourcePaths:    List[String],         // archive-relative paths under sources/
  )

  /** Load a `.sscpkg` archive.  Intrinsic JARs are extracted to a
   *  freshly-created temp directory that lives for the JVM lifetime
   *  (no explicit cleanup needed for a CLI process). */
  def load(pkg: os.Path): LoadResult =
    val zip = new ZipFile(pkg.toIO)
    try
      val entries = zip.entries().asScala.toList

      // ── manifest.yaml ──────────────────────────────────────────────
      val manifestEntry = entries
        .find(_.getName == "manifest.yaml")
        .getOrElse(throw RuntimeException(s"${pkg.last}: missing manifest.yaml"))
      val manifestYaml  = new String(zip.getInputStream(manifestEntry).readAllBytes(), "UTF-8")
      val manifest      = SscpkgManifest.parseString(manifestYaml).get

      // ── runtime/* ──────────────────────────────────────────────────
      val runtimeStrings = entries
        .filter(e => !e.isDirectory && e.getName.startsWith("runtime/"))
        .flatMap { e =>
          // entry name like "runtime/jvm.scala" → backendId = "jvm"
          val base = e.getName.stripPrefix("runtime/")
          val dotIdx = base.lastIndexOf('.')
          val backendId = if dotIdx > 0 then base.substring(0, dotIdx) else base
          if backendId.isEmpty then Nil
          else
            val code = new String(zip.getInputStream(e).readAllBytes(), "UTF-8")
            List(backendId -> code)
        }
        .toMap

      // ── intrinsics/*.jar ───────────────────────────────────────────
      val intrinsicEntries = entries
        .filter(e => !e.isDirectory && e.getName.startsWith("intrinsics/") && e.getName.endsWith(".jar"))

      val intrinsicJars =
        if intrinsicEntries.isEmpty then Nil
        else
          val tmpDir = os.temp.dir(prefix = s"sscpkg-${manifest.id}-intrinsics")
          intrinsicEntries.map { e =>
            val fileName = e.getName.split('/').last
            val dest = tmpDir / fileName
            os.write(dest, zip.getInputStream(e).readAllBytes())
            dest
          }

      // ── sources/*.ssc ──────────────────────────────────────────────
      val sourcePaths = entries
        .filter(e => !e.isDirectory && e.getName.startsWith("sources/") && e.getName.endsWith(".ssc"))
        .map(_.getName)

      LoadResult(manifest, intrinsicJars, runtimeStrings, sourcePaths)
    finally
      zip.close()

  /** Extract source `.ssc` entries from a `.sscpkg` archive (under `sources/`)
   *  into a fresh temp directory and return the directory path.
   *
   *  The directory lives for the JVM process lifetime (no explicit cleanup).
   *  If the archive has a single source file the caller may use it directly;
   *  if there are multiple, prefer `index.ssc` as the entry point. */
  def extractSources(pkg: os.Path): os.Path =
    val zip = new ZipFile(pkg.toIO)
    try
      val entries    = zip.entries().asScala.toList
      val srcEntries = entries.filter(e =>
        !e.isDirectory && e.getName.startsWith("sources/") && e.getName.endsWith(".ssc"))
      val tmpDir = os.temp.dir(prefix = s"sscpkg-${pkg.last.stripSuffix(".sscpkg")}-sources")
      srcEntries.foreach { e =>
        val relName = e.getName.stripPrefix("sources/")
        val dest    = tmpDir / os.RelPath(relName)
        os.makeDir.all(dest / os.up)
        os.write(dest, zip.getInputStream(e).readAllBytes())
      }
      tmpDir
    finally
      zip.close()
