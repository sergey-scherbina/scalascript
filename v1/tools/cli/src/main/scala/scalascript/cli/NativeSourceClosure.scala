package scalascript.cli

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.collection.mutable

/** One source contributing declarations or entry statements to the linked
 * native Program. `displayPath` is stable across checkout locations.
 *
 * `explicitRoot` means "named as a root of the closure"; `userRoot` means "named
 * by the USER on the command line". They are not the same thing and conflating
 * them is what BUGS `jvm-artifact-stack-trace-never-names-the-users-own-file`
 * was: `RunNativeV2` injects the ambient prelude as LEADING roots, so a std
 * module is an explicit root and sorts ahead of the program being compiled. */
private[cli] final case class NativeSourceUnit(
    file: File,
    displayPath: String,
    explicitRoot: Boolean,
    userRoot: Boolean)

/** JDK-only mirror of the self-hosted native loader's standalone Markdown-link
 * DFS. Imported modules are post-ordered before their importer, exactly like
 * `ssc1-run.ssc0`; explicit roots retain command-line order. */
private[cli] object NativeSourceClosure:
  private val StandaloneImport = """^\s*\[[^]]+\]\(([^)]+[.]ssc)\)\s*$""".r

  // `import std.a.b` INSIDE a fence — the keyword form, which this scanner never saw. It matched
  // only the Markdown link above, outside fences, so an import naming a module that does not exist
  // ran to completion with no diagnostic while `[x](std/nosuchmodule.ssc)` reported not-found.
  // Decided 2026-08-09 by Sergiy: it must say not found.
  private val KeywordImport = """^\s*import\s+(std(?:[.][A-Za-z_][A-Za-z0-9_]*)+)(?:[.][*])?\s*$""".r

  // SCOPED TO `std.`, AND THAT IS WHAT THE DATA SAYS RATHER THAN A HEDGE. Counted across every
  // `.ssc` in the repository: of 192 keyword imports, `std` is the ONLY root whose imports land in a
  // declared package — 18 of 19 do. `scalascript` (95), `scala` (32), `actors` (11), `org` (8),
  // `nodes` (6), `cluster` (3), `java` (3) resolve to ZERO declared packages, because they are host
  // or plugin surfaces rather than modules. Holding those to "not found" would refuse twenty-odd
  // correct lines, which is the compatibility risk the entry was opened for.
  //
  // A package is NOT a path here: it is declared in a module's front-matter and the file may sit
  // anywhere. Checking `std/pdf.ssc` exists is a different question and gives a different answer —
  // `std.pdf` is declared by `std/pdf-gen.ssc`, so a path check calls a correct import missing.
  private val PackageDecl = """^\s*package:\s*(\S+)\s*$""".r

  private def declaredPackages(stdRoot: File): Set[String] =
    val out = mutable.HashSet.empty[String]
    def walk(d: File): Unit =
      val kids = d.listFiles()
      if kids != null then kids.foreach { f =>
        if f.isDirectory then walk(f)
        else if f.getName.endsWith(".ssc") then
          // Front matter only: the `package:` key is in the leading `---` block, and reading the
          // whole file would also match the word inside prose or a fence.
          val lines = Files.readAllLines(f.toPath, StandardCharsets.UTF_8)
          var i = 0
          var seenOpen = false
          var done = false
          while i < lines.size && i < 40 && !done do
            val t = lines.get(i).trim
            if t == "---" then { if seenOpen then done = true else seenOpen = true }
            else if seenOpen then t match
              case PackageDecl(p) => out += p
              case _              => ()
            i += 1
      }
    if stdRoot.isDirectory then walk(stdRoot)
    out.toSet

  /** Does anything provide `mod`? A package is provided when it is declared, when it is an ANCESTOR
    * of a declared one (`std.ui` is real if `std.ui.form` is), or when the import names a MEMBER of
    * a declared package (`import std.json.parse`). Anything else is not found. */
  private def isProvided(mod: String, declared: Set[String]): Boolean =
    declared.contains(mod) ||
    declared.exists(_.startsWith(mod + ".")) ||
    (mod.lastIndexOf('.') match
      case -1 => false
      case n  => declared.contains(mod.substring(0, n)))

  /** `userRoots` are the canonical paths the user named on the command line. Every
   * other root is a prelude this compiler injected, and debug metadata must not
   * present one as the file the user wrote. */
  def resolve(
      roots: List[File],
      stdRoot: File,
      libRoot: File,
      userRoots: Set[String] = Set.empty): List[NativeSourceUnit] =
    val seen = mutable.HashSet.empty[String]
    val result = mutable.ListBuffer.empty[NativeSourceUnit]
    val rootPrefix = roots.lengthCompare(1) > 0

    def visitImported(file: File, displayPath: String): Unit =
      val canonical = file.getCanonicalFile
      val key = canonical.getPath
      if seen.add(key) then
        imports(canonical).foreach { relative =>
          val (target, childDisplay) = resolveImport(canonical, displayPath, relative, stdRoot, libRoot)
          visitImported(target, childDisplay)
        }
        result += NativeSourceUnit(canonical, normalizeDisplay(displayPath), explicitRoot = false, userRoot = false)

    roots.zipWithIndex.foreach { case (root0, index) =>
      val root = root0.getCanonicalFile
      val display =
        if rootPrefix then s"root-${index + 1}/${root.getName}" else root.getName
      // sscLoadRoot always contributes the explicit root, while the shared
      // seen set prevents an import from being loaded twice.
      seen += root.getPath
      imports(root).foreach { relative =>
        val (target, childDisplay) = resolveImport(root, display, relative, stdRoot, libRoot)
        visitImported(target, childDisplay)
      }
      result += NativeSourceUnit(root, normalizeDisplay(display), explicitRoot = true,
        userRoot = userRoots.isEmpty || userRoots.contains(root.getPath))
    }
    val units = result.toList
    // ONCE, over the finished closure, and only if some file actually uses the keyword form —
    // `declaredPackages` walks the std tree, and a program that never writes `import std.…` should
    // not pay for it.
    val keyworded = units.map(u => u.file -> keywordImports(u.file)).filter(_._2.nonEmpty)
    if keyworded.nonEmpty then
      val declared = declaredPackages(stdRoot)
      keyworded.foreach { case (file, mods) =>
        mods.foreach { mod =>
          if !isProvided(mod, declared) then
            throw new java.io.FileNotFoundException(
              s"native frontend import not found: $mod from ${file.getName} — no module declares " +
              s"`package: $mod`")
        }
      }
    units

  private def resolveImport(
      importer: File,
      importerDisplay: String,
      relative: String,
      stdRoot: File,
      libRoot: File): (File, String) =
    val normalizedRelative = relative.replace('\\', '/')
    // Path convention (mirrors ssc1-run.ssc0 `sscResolve` and v1 ImportResolver):
    //   std/… → the std root;  ./… or ../… → relative to the importing file.
    //   A BARE path is ambiguous (a sibling of the importer, e.g. std/http.ssc →
    //   `json.ssc`, OR a repo-root-relative case import like
    //   tests/conformance/lib/foo.ssc). Try the importer directory first and fall
    //   back to the install/lib root (ssc.lib.path — the repo root in a checkout).
    val target =
      if normalizedRelative.startsWith("std/") then
        new File(stdRoot, normalizedRelative)
      else if normalizedRelative.startsWith(".") then
        new File(importer.getParentFile, normalizedRelative)
      else
        val sourceRel = new File(importer.getParentFile, normalizedRelative)
        if sourceRel.isFile then sourceRel else new File(libRoot, normalizedRelative)
    val canonical = target.getCanonicalFile
    if !canonical.isFile then
      throw new java.io.FileNotFoundException(
        s"native frontend import not found: $normalizedRelative from ${importer.getName}")
    val display =
      if normalizedRelative.startsWith("std/") then normalizedRelative
      else
        val parent = importerDisplay.lastIndexOf('/') match
          case -1 => ""
          case n  => importerDisplay.substring(0, n + 1)
        parent + normalizedRelative
    canonical -> normalizeDisplay(display)

  private def imports(file: File): List[String] =
    val lines = Files.readAllLines(file.toPath, StandardCharsets.UTF_8)
    val result = mutable.ListBuffer.empty[String]
    var inFence = false
    var index = 0
    while index < lines.size do
      val trimmed = lines.get(index).trim
      if trimmed.startsWith("```") then inFence = !inFence
      else if !inFence then trimmed match
        case StandaloneImport(path) => result += path
        case _                      => ()
      index += 1
    result.toList

  /** The keyword form, which is CODE rather than a Markdown link — so it lives inside a fence, or
    * anywhere in a file that has no fences at all.
    *
    * THAT SECOND CASE IS NOT AN EDGE. Fences are optional: a bare `.ssc` is code from the first line,
    * and both this repository's own probe and `tests/e2e/keyword-import-missing-module.sh` write
    * exactly that shape. A first cut of this scanner matched only inside fences and therefore saw
    * nothing in the very file the gate uses — it compiled, it looked right, and it did not fire. */
  private def keywordImports(file: File): List[String] =
    val lines = Files.readAllLines(file.toPath, StandardCharsets.UTF_8)
    var fenced = false
    var i = 0
    while i < lines.size && !fenced do
      if lines.get(i).trim.startsWith("```") then fenced = true
      i += 1
    val result = mutable.ListBuffer.empty[String]
    var inFence = false
    var index = 0
    while index < lines.size do
      val trimmed = lines.get(index).trim
      if trimmed.startsWith("```") then inFence = !inFence
      else if inFence || !fenced then trimmed match
        case KeywordImport(mod) => result += mod
        case _                  => ()
      index += 1
    result.toList.distinct

  private def normalizeDisplay(path: String): String =
    val absolute = path.startsWith("/")
    val parts = mutable.ArrayBuffer.empty[String]
    path.replace('\\', '/').split('/').foreach {
      case "" | "." => ()
      case ".." if parts.nonEmpty => parts.remove(parts.length - 1)
      case ".." if !absolute      => parts += ".."
      case segment                 => parts += segment
    }
    parts.mkString("/")
