package scalascript.cli

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.collection.mutable

/** One source contributing declarations or entry statements to the linked
 * native Program. `displayPath` is stable across checkout locations. */
private[cli] final case class NativeSourceUnit(
    file: File,
    displayPath: String,
    explicitRoot: Boolean)

/** JDK-only mirror of the self-hosted native loader's standalone Markdown-link
 * DFS. Imported modules are post-ordered before their importer, exactly like
 * `ssc1-run.ssc0`; explicit roots retain command-line order. */
private[cli] object NativeSourceClosure:
  private val StandaloneImport = """^\s*\[[^]]+\]\(([^)]+[.]ssc)\)\s*$""".r

  def resolve(roots: List[File], stdRoot: File, libRoot: File): List[NativeSourceUnit] =
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
        result += NativeSourceUnit(canonical, normalizeDisplay(displayPath), explicitRoot = false)

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
      result += NativeSourceUnit(root, normalizeDisplay(display), explicitRoot = true)
    }
    result.toList

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
