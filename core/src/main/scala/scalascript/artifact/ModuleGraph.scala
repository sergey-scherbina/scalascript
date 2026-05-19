package scalascript.artifact

import scalascript.parser.Parser
import scala.collection.mutable.{ListBuffer, Queue, Map as MMap}

/** Dependency graph for a collection of `.ssc` modules.
 *
 *  Reads all `.ssc` files in a directory tree, extracts their `Import` edges,
 *  and produces a topological order suitable for incremental compilation.
 *
 *  v2.0 / Stage 6 — build orchestration.
 */
object ModuleGraph:

  /** A node in the dependency graph.
   *
   *  @param path         Absolute path to the `.ssc` source file.
   *  @param pkg          Package segments from the file's front-matter.
   *  @param importPaths  Absolute paths of files this module directly imports.
   */
  case class Node(
    path:        os.Path,
    pkg:         List[String],
    importPaths: List[os.Path]
  ):
    /** Relative display path for log messages. */
    def relPath(base: os.Path): String = path.relativeTo(base).toString

  /** The result of `build` — nodes in topological order + any detected cycles. */
  case class GraphResult(
    orderedNodes: List[Node],
    cycles:       List[List[os.Path]]   // each cycle is a list of paths
  )

  /** Walk `root` for `.ssc` files, parse their imports, and return a
   *  `GraphResult` with nodes in topological dependency order.
   *
   *  Files in `skipDirs` (e.g. `target`, `node_modules`) are excluded.
   *  Imports that point outside `root` or to non-existent files are
   *  silently skipped (they may be URL imports handled by `ImportResolver`).
   *
   *  @param root     The root directory to walk.
   *  @param skipDirs Directory names to skip during the walk.
   *  @return `GraphResult` with topo-sorted nodes.  `cycles` is empty
   *          if the graph is acyclic (the normal case).
   */
  def build(
      root:     os.Path,
      skipDirs: Set[String] = Set("target", "node_modules", "dist", "out", ".scala-build")
  ): GraphResult =
    // Walk all .ssc files.
    val files = os.walk(root, skip = p => skipDirs.contains(p.last) || p.last.startsWith("."))
      .filter(p => os.isFile(p) && p.ext == "ssc")
      .toList
      .sorted

    // Parse each file to extract its package and import edges.
    val nodes: List[Node] = files.map { path =>
      val src    = scala.util.Try(os.read(path)).getOrElse("")
      val module = scala.util.Try(Parser.parse(src)).getOrElse(
        scalascript.ast.Module(manifest = None, sections = Nil)
      )
      val pkg = module.manifest.flatMap(_.pkg).getOrElse(Nil)
      val importPaths = module.sections.flatMap { s =>
        collectImports(s).flatMap { rawPath =>
          if isLocalPath(rawPath) then
            val resolved = path / os.up / os.RelPath(rawPath)
            if os.exists(resolved) then List(resolved) else Nil
          else Nil
        }
      }
      Node(path, pkg, importPaths)
    }

    // Build an index: path → Node
    val byPath: Map[os.Path, Node] = nodes.map(n => n.path -> n).toMap

    // Kahn's algorithm for topological sort + cycle detection.
    val inDegree = MMap.empty[os.Path, Int]
    val adjList  = MMap.empty[os.Path, ListBuffer[os.Path]]
    nodes.foreach { n =>
      inDegree.getOrElseUpdate(n.path, 0)
      adjList.getOrElseUpdate(n.path, ListBuffer.empty)
      n.importPaths.foreach { dep =>
        if byPath.contains(dep) then
          adjList.getOrElseUpdate(dep, ListBuffer.empty) += n.path
          inDegree(n.path) = inDegree.getOrElse(n.path, 0) + 1
          inDegree.getOrElseUpdate(dep, 0)
      }
    }

    val queue   = Queue.empty[os.Path]
    val ordered = ListBuffer.empty[Node]
    inDegree.foreach { case (path, deg) => if deg == 0 then queue.enqueue(path) }

    while queue.nonEmpty do
      val p = queue.dequeue()
      byPath.get(p).foreach { n =>
        ordered += n
        adjList.getOrElse(p, Nil).foreach { dep =>
          val newDeg = inDegree.getOrElse(dep, 1) - 1
          inDegree(dep) = newDeg
          if newDeg == 0 then queue.enqueue(dep)
        }
      }

    // Any node with inDegree > 0 is part of a cycle.
    val cyclicPaths = inDegree.filter(_._2 > 0).keys.toList.sorted
    val cycles = if cyclicPaths.nonEmpty then List(cyclicPaths) else Nil

    GraphResult(ordered.toList, cycles)

  /** Check whether a `.ssc` module is stale relative to its artifact.
   *
   *  A module is stale if:
   *  - Its `.scim` or `.scir` artifact does not exist, OR
   *  - The SHA-256 of the current source bytes does not match the
   *    `sourceHash` stored in the `.scim` artifact.
   *
   *  @param srcPath    Path to the `.ssc` source file.
   *  @param artifactDir Directory where `.scim` / `.scir` artifacts live.
   *  @return `true` if the module needs recompilation.
   */
  def isStale(srcPath: os.Path, artifactDir: os.Path): Boolean =
    val baseName = srcPath.last.stripSuffix(".ssc")
    val scimPath = artifactDir / (baseName + ".scim")
    if !os.exists(scimPath) then return true
    ArtifactIO.readInterfaceFile(scimPath) match
      case Left(_) => true
      case Right(iface) =>
        val currentHash = InterfaceExtractor.sha256(os.read.bytes(srcPath))
        iface.sourceHash != currentHash

  /** Collect raw import paths from a section recursively. */
  private def collectImports(s: scalascript.ast.Section): List[String] =
    s.content.collect {
      case imp: scalascript.ast.Content.Import => imp.path
    } ++ s.subsections.flatMap(collectImports)

  /** True for import paths that resolve locally (not URLs, not dep: URIs). */
  private def isLocalPath(raw: String): Boolean =
    !raw.startsWith("http://") &&
    !raw.startsWith("https://") &&
    !raw.startsWith("dep:") &&
    !raw.contains("://")
