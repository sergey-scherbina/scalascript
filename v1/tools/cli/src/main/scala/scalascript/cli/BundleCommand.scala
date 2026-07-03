package scalascript.cli

import scalascript.ast.*

// `ssc bundle <file.ssc>... [-o name.sscpkg]`: packs one or more .ssc entry
// points (and their imported deps) into a portable bundle. Extracted from
// Main.scala.

/** `ssc bundle <file.ssc> [<file.ssc>...] [-o name.sscpkg]` — packs
 *  one or more `.ssc` entry files together with every transitively-
 *  imported `.ssc` into a zip archive (`.sscpkg`).  A consumer
 *  unzips and uses the entries with relative imports.
 *
 *  Archive layout:
 *    bundle.yaml                 metadata: entries, transitives, name/version
 *    <entry>.ssc                 each entry at the archive root
 *    <relative paths>/...        every imported file under its
 *                                 path relative to the bundle root
 *    _external/<basename>        files that lived ABOVE the bundle
 *                                 root in the source tree — flattened
 *                                 here; references inside the bundle
 *                                 are rewritten so the archive is
 *                                 self-contained.
 *
 *  The bundle root is the common parent directory of every entry's
 *  parent.  Inside-root files keep their relative path; outside-root
 *  files get flattened.
 */
final class BundleCmd extends CliCommand:
  def name = "bundle"
  override def summary = "Pack .ssc files + their .ssc imports into a .sscpkg archive"
  override def category = "Build, bundle & package"
  def run(args: List[String]): Unit =
    import scalascript.parser.Parser
    import java.util.zip.{ZipOutputStream, ZipEntry}

    // ─── Argument parsing ─────────────────────────────────────────
    var output: Option[String] = None
    var withArtifacts: Boolean = false
    // backendId -> jar path pairs from --with-backend-jar backendId:path
    val backendJars = scala.collection.mutable.ArrayBuffer.empty[(String, os.Path)]
    val files = scala.collection.mutable.ArrayBuffer.empty[String]
    val it = args.iterator
    while it.hasNext do
      it.next() match
        case "-o" | "--output" if it.hasNext => output = Some(it.next())
        case "--with-artifacts"              => withArtifacts = true
        case "--with-backend-jar" if it.hasNext =>
          val spec = it.next()
          spec.indexOf(':') match
            case -1 =>
              System.err.println(s"Error: --with-backend-jar requires backendId:path (got '$spec')")
              System.exit(1)
            case i =>
              val bid  = spec.substring(0, i)
              val path = os.Path(spec.substring(i + 1), os.pwd)
              if !os.exists(path) then
                System.err.println(s"Error: backend JAR not found: $path"); System.exit(1)
              backendJars += bid -> path
        case f => files += f
    if files.isEmpty then
      System.err.println("Usage: ssc bundle <file.ssc> [<file.ssc>...] [-o name.sscpkg] [--with-artifacts] [--with-backend-jar backendId:path]")
      System.exit(1)

    val entryPaths = files.toList.map { f =>
      val p = os.Path(f, os.pwd)
      if !os.exists(p) then
        System.err.println(s"Error: $f not found"); System.exit(1)
      if p.ext != "ssc" then
        System.err.println(s"Error: $f is not a .ssc file"); System.exit(1)
      p
    }

    // Bundle root: the common parent directory of every entry's parent.
    // Single entry → its parent dir.  Two entries in the same folder →
    // that folder.  Two entries in sibling folders → their common parent.
    val bundleRoot = commonAncestor(entryPaths.map(_ / os.up))

    // ─── Transitive walk ──────────────────────────────────────────
    // archivePath[abs] = path the file gets under inside the zip.
    // externalNames    = basenames already taken under `_external/`.
    val archivePath    = scala.collection.mutable.LinkedHashMap.empty[os.Path, String]
    val externalNames  = scala.collection.mutable.Set.empty[String]

    def assignPath(abs: os.Path): String =
      if archivePath.contains(abs) then archivePath(abs)
      else if abs.startsWith(bundleRoot) then
        val p = abs.relativeTo(bundleRoot).toString
        archivePath(abs) = p; p
      else
        var name = abs.last
        var n    = 1
        while externalNames.contains(name) do
          name = abs.last.stripSuffix(".ssc") + "_" + n + ".ssc"
          n   += 1
        externalNames += name
        val p = s"_external/$name"
        archivePath(abs) = p; p

    def visit(file: os.Path): Unit =
      if archivePath.contains(file) then return
      val _ = assignPath(file)
      val module = Parser.parse(os.read(file))
      val imports = scala.collection.mutable.ArrayBuffer.empty[scalascript.ast.Content.Import]
      def gatherImports(secs: List[scalascript.ast.Section]): Unit =
        secs.foreach { s =>
          s.content.foreach {
            case imp: scalascript.ast.Content.Import => imports += imp
            case _ => ()
          }
          gatherImports(s.subsections)
        }
      gatherImports(module.sections)
      imports.foreach { imp =>
        val resolved = (file / os.up) / os.RelPath(imp.path)
        if os.exists(resolved) then visit(resolved)
        else System.err.println(s"  [warn] import ${imp.path} from ${file.last} — not found, skipped")
      }

    entryPaths.foreach(visit)

    // ─── Rewrite import paths to match the new archive layout ─────
    //
    // For every `.ssc` we packed: re-scan its imports.  If the resolved
    // target's archive path doesn't match the original source path the
    // import wrote, splice the new one into the file content before
    // writing it to the zip.  We rewrite via a `](old)→](new)` Markdown
    // edit so it's localised to the link destination.
    def rewriteImports(file: os.Path): String =
      val raw = os.read(file)
      val module = Parser.parse(raw)
      val imports = scala.collection.mutable.ArrayBuffer.empty[scalascript.ast.Content.Import]
      def gather(secs: List[scalascript.ast.Section]): Unit =
        secs.foreach { s =>
          s.content.foreach {
            case imp: scalascript.ast.Content.Import => imports += imp
            case _ => ()
          }
          gather(s.subsections)
        }
      gather(module.sections)
      var out = raw
      imports.foreach { imp =>
        val resolved = (file / os.up) / os.RelPath(imp.path)
        if archivePath.contains(resolved) then
          val targetArchive = archivePath(resolved)
          val ownArchive    = archivePath(file)
          // Relative path FROM the importing file's own archive dir
          // TO the imported file's archive location.
          val ownDir = if ownArchive.contains('/') then ownArchive.substring(0, ownArchive.lastIndexOf('/')) else ""
          val rel    = relativeArchivePath(ownDir, targetArchive)
          if rel != imp.path then
            // Markdown link: `](OLD)` → `](NEW)`.  Quote the regex.
            val pat = java.util.regex.Pattern.quote("](" + imp.path + ")")
            out = out.replaceAll(pat, java.util.regex.Matcher.quoteReplacement("](" + rel + ")"))
      }
      out

    // ─── Write the zip ────────────────────────────────────────────
    val outName =
      output.getOrElse(
        if entryPaths.length == 1 then entryPaths.head.last.stripSuffix(".ssc") + ".sscpkg"
        else "bundle.sscpkg"
      )
    val outPath = os.Path(outName, os.pwd)
    os.makeDir.all(outPath / os.up)

    // Derive bundle id from output name (strip version suffix + extension if present)
    val bundleId = outName.stripSuffix(".sscpkg").replaceAll("-\\d+\\.\\d+.*$", "")
    val isHybrid = backendJars.nonEmpty
    val kind     = if isHybrid then "[library, plugin]" else "[library]"
    val targets  = if isHybrid then backendJars.map(_._1).distinct.mkString("[", ", ", "]") else "[]"

    // ─── v2.0 Phase 5 — pre-compile artifacts when `--with-artifacts` ─────
    //
    // For every bundled `.ssc`, drive `build --incremental --backend jvm`
    // and `--backend js` into a temporary artifact dir, then splice the
    // produced `.scim` / `.scjvm` / `.scjs` files into the ZIP under
    // `.ssc-artifacts/<basename>.<ext>` so a consumer can use them
    // directly without re-parsing the source.
    //
    // `findArtifactAlongside` (in `ImportResolver`) discovers the layout
    // automatically — no manifest schema change required.
    //
    // Refuses on compile errors: build/compile failure aborts the bundle
    // and removes the partially-written ZIP.
    val artifactStaging: Option[os.Path] =
      if !withArtifacts then None
      else
        // Stage rewritten sources to a temp src dir so the build step sees
        // the same archive layout the consumer will see.  Build JVM + JS
        // artifacts in-process via `buildArtifactsInto` (the exit-safe
        // sibling of `build --incremental`) and refuse the bundle if any
        // input fails to compile.
        val srcStage = os.temp.dir(prefix = "ssc-bundle-src-")
        val artStage = os.temp.dir(prefix = "ssc-bundle-art-")
        try
          archivePath.toList.foreach { case (file, archive) =>
            val dest = srcStage / os.RelPath(archive)
            os.makeDir.all(dest / os.up)
            os.write.over(dest, rewriteImports(file))
          }
          val (_, _, jvmFailed) = buildArtifactsInto(srcStage, artStage, Some("jvm"))
          val (_, _, jsFailed)  = buildArtifactsInto(srcStage, artStage, Some("js"))
          if jvmFailed > 0 || jsFailed > 0 then
            os.remove.all(srcStage)
            os.remove.all(artStage)
            System.err.println(
              s"bundle --with-artifacts: compile errors in inputs " +
              s"(jvm=$jvmFailed js=$jsFailed); refusing to bundle"
            )
            System.exit(1)
          Some(artStage)
        catch case e: Throwable =>
          scala.util.Try(os.remove.all(srcStage))
          scala.util.Try(os.remove.all(artStage))
          System.err.println(s"bundle --with-artifacts: ${e.getMessage}")
          System.exit(1)
          None
        finally
          scala.util.Try(os.remove.all(srcStage))

    val zip = new ZipOutputStream(new java.io.FileOutputStream(outPath.toIO))
    try
      // manifest.yaml (v1.7 Tier 2) — supersedes legacy bundle.yaml
      val manifestYaml = new StringBuilder
      manifestYaml.append("# ScalaScript package manifest\n")
      manifestYaml.append(s"id:         $bundleId\n")
      manifestYaml.append(s"version:    0.1.0\n")
      manifestYaml.append(s"spiVersion: \"0.1.0\"\n")
      manifestYaml.append(s"kind:       $kind\n")
      manifestYaml.append(s"targets:    $targets\n")
      manifestYaml.append("exports:\n")
      manifestYaml.append("  externDefs: []\n")
      zip.putNextEntry(new ZipEntry("manifest.yaml"))
      zip.write(manifestYaml.toString.getBytes("UTF-8"))
      zip.closeEntry()

      // sources/*.ssc — all .ssc files packed under sources/ prefix
      archivePath.toList.sortBy(_._2).foreach { case (file, archive) =>
        zip.putNextEntry(new ZipEntry("sources/" + archive))
        zip.write(rewriteImports(file).getBytes("UTF-8"))
        zip.closeEntry()
      }

      // .ssc-artifacts/* — pre-compiled .scim / .scjvm / .scjs (v2.0 P5).
      // Layout in the ZIP: one .ssc-artifacts dir at the archive root, with
      // entry names matching the source basenames (no `sources/` prefix).
      // A consumer extracts the archive and finds the artifacts next to
      // the sources under `.ssc-artifacts/`.
      artifactStaging.foreach { artDir =>
        if os.isDir(artDir) then
          os.list(artDir).filter(os.isFile).sortBy(_.last).foreach { p =>
            val ext = p.ext
            if Set("scim", "scjvm", "scjs", "scjvm-runtime", "scjs-runtime").contains(ext) then
              zip.putNextEntry(new ZipEntry(".ssc-artifacts/" + p.last))
              zip.write(os.read.bytes(p))
              zip.closeEntry()
          }
      }

      // intrinsics/*.jar — one per --with-backend-jar entry
      backendJars.foreach { case (_, jar) =>
        zip.putNextEntry(new ZipEntry("intrinsics/" + jar.last))
        zip.write(os.read.bytes(jar))
        zip.closeEntry()
      }
    finally
      zip.close()
      artifactStaging.foreach(d => scala.util.Try(os.remove.all(d)))

    val entryList = entryPaths.map(archivePath).mkString(", ")
    val external  = archivePath.values.count(_.startsWith("_external/"))
    val jarLine   = if backendJars.isEmpty then "" else s", ${backendJars.size} intrinsic JAR(s)"
    val artLine   = if withArtifacts then ", with pre-compiled artifacts" else ""
    println(s"$outName  (${archivePath.size} sources, $external external$jarLine$artLine) — entries: $entryList")

// ─────────────────────────────────────────────────────────────────────────────

// ssc plugin <subcommand>  —  v1.7 Tier 5
// ─────────────────────────────────────────────────────────────────────────────

/** Default directory where installed `.sscpkg` files live. */
