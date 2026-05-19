package scalascript.cli

import scalascript.ast.{Module, Section, Content}
import scalascript.parser.Parser
import scalascript.artifact.{ArtifactIO, JvmArtifactIO, JsArtifactIO, InterfaceExtractor}

import scala.collection.mutable.ListBuffer

/** Recursive import resolution for the per-module `compile-jvm` and
 *  `compile-js` commands.
 *
 *  Given a target `.ssc` file the resolver:
 *    1. Parses the file (and every local-path import transitively).
 *    2. Builds a dependency DAG.  Edges go `importer → dep`.
 *    3. Detects cycles via DFS coloring; reports them as a list of
 *       node paths forming the cycle.
 *    4. Returns nodes in topological order — every dep comes before
 *       any module that imports it (Kahn-style ordering).
 *
 *  Only **local-path** imports participate.  URL / `dep:` / scheme
 *  imports are skipped (those are handled by `ImportResolver` at the
 *  per-module level and pull from the user cache).
 *
 *  v2.0 — auto-resolve imports for `compile-jvm` / `compile-js`.
 */
object AutoResolve:

  /** A node in the local-import dependency graph. */
  final case class Node(
      path:        os.Path,
      module:      Module,
      sourceBytes: Array[Byte],
      /** Absolute paths of files this module directly imports
       *  (local-path imports only; URL / `dep:` imports filtered out). */
      depPaths:    List[os.Path]
  )

  /** Result of resolution.  When `cycles.nonEmpty` the caller should
   *  surface them and abort — `orderedNodes` is best-effort under cycle
   *  conditions (Kahn produces a partial order). */
  final case class Result(
      orderedNodes: List[Node],
      cycles:       List[List[os.Path]]
  )

  /** Walk the local-import closure of `targetPath` and return a topo-
   *  sorted node list with cycle diagnostics.
   *
   *  Throws `RuntimeException` on parse / file-not-found errors with a
   *  message that names the unresolvable dependency (and which module
   *  asked for it).  The caller catches these to convert into a CLI
   *  exit code. */
  def resolve(targetPath: os.Path): Result =
    val visited = scala.collection.mutable.Map.empty[os.Path, Node]
    // DFS-order traversal so error messages can attribute a missing
    // dep to the parent that asked for it.
    val stack = scala.collection.mutable.Stack[os.Path](targetPath)
    while stack.nonEmpty do
      val p = stack.pop()
      if !visited.contains(p) then
        if !os.exists(p) then
          throw new RuntimeException(s"auto-resolve: dependency not found: $p")
        val bytes = scala.util.Try(os.read.bytes(p)).fold(
          e => throw new RuntimeException(s"auto-resolve: cannot read $p: ${e.getMessage}"),
          identity
        )
        val module = scala.util.Try(Parser.parse(new String(bytes, "UTF-8"))).fold(
          e => throw new RuntimeException(s"auto-resolve: parse error in $p: ${e.getMessage}"),
          identity
        )
        // Front-matter dep aliases that look like local URI maps
        // (e.g. `a: ./a.ssc`) ARE handled as local imports here.  URL /
        // dep: aliases are skipped — those use the user cache.
        val rawImports = collectLocalImports(module)
        val resolvedDeps = ListBuffer.empty[os.Path]
        for raw <- rawImports do
          if isLocalPath(raw) then
            val resolved = (p / os.up / os.RelPath(raw)).resolveFrom(os.pwd)
            // Directory-as-index: `[X](./pack)` with pack/index.ssc inside.
            val actual =
              if os.exists(resolved) && os.isDir(resolved) then
                val idx = resolved / "index.ssc"
                if os.exists(idx) then idx else resolved
              else resolved
            if !os.exists(actual) then
              throw new RuntimeException(
                s"auto-resolve: cannot resolve import '$raw' from ${p.last} " +
                s"(looked at $actual)"
              )
            resolvedDeps += actual
            if !visited.contains(actual) then stack.push(actual)
        visited(p) = Node(p, module, bytes, resolvedDeps.result().distinct)

    // Topological sort via Kahn's algorithm; cycle detection by finding
    // nodes that never reach zero in-degree.
    val nodes = visited.values.toList
    val byPath = nodes.iterator.map(n => n.path -> n).toMap

    // Adjacency: dep → list of importers.  In-degree[n] = #deps of n.
    val inDegree = scala.collection.mutable.Map.empty[os.Path, Int]
    val outEdges = scala.collection.mutable.Map.empty[os.Path, ListBuffer[os.Path]]
    nodes.foreach { n =>
      inDegree.getOrElseUpdate(n.path, 0)
      outEdges.getOrElseUpdate(n.path, ListBuffer.empty)
    }
    nodes.foreach { n =>
      n.depPaths.foreach { dep =>
        if byPath.contains(dep) then
          outEdges.getOrElseUpdate(dep, ListBuffer.empty) += n.path
          inDegree(n.path) = inDegree.getOrElse(n.path, 0) + 1
      }
    }

    val queue   = scala.collection.mutable.Queue.empty[os.Path]
    val ordered = ListBuffer.empty[Node]
    inDegree.foreach { case (p, d) => if d == 0 then queue.enqueue(p) }
    while queue.nonEmpty do
      val p = queue.dequeue()
      byPath.get(p).foreach(ordered += _)
      outEdges.getOrElse(p, Nil).foreach { dst =>
        val nd = inDegree.getOrElse(dst, 1) - 1
        inDegree(dst) = nd
        if nd == 0 then queue.enqueue(dst)
      }

    // Anything left with in-degree > 0 participates in a cycle.
    val cyclic = inDegree.collect { case (p, d) if d > 0 => p }.toList.sorted
    val cycles: List[List[os.Path]] =
      if cyclic.isEmpty then Nil
      else List(extractCycle(cyclic.head, byPath))

    Result(ordered.result(), cycles)

  /** Reconstruct a concrete cycle path starting from `start` for a
   *  human-readable error message.  Walks `node.depPaths` greedily until
   *  it revisits a node, then slices the loop. */
  private def extractCycle(
      start:  os.Path,
      byPath: Map[os.Path, Node]
  ): List[os.Path] =
    val seen = scala.collection.mutable.LinkedHashMap.empty[os.Path, Int]
    var cur  = start
    var step = 0
    while !seen.contains(cur) do
      seen(cur) = step
      step += 1
      byPath.get(cur) match
        case None    => return seen.keys.toList
        case Some(n) =>
          // Pick the first dep that is itself in the byPath set — that's
          // the edge that keeps the cycle alive.
          n.depPaths.find(byPath.contains) match
            case Some(next) => cur = next
            case None       => return seen.keys.toList
    // Slice the prefix tail that forms the loop and close it.
    val startIdx = seen(cur)
    val loop = seen.keys.toList.drop(startIdx)
    loop :+ cur

  /** Collect raw import path strings from a module — both `Content.Import`
   *  link destinations AND front-matter `dependencies:` map values.  The
   *  latter let users alias paths in YAML; treat the value as a regular
   *  import path. */
  private def collectLocalImports(module: Module): List[String] =
    val fromSections = collectImportsFromSections(module.sections)
    val fromManifest = module.manifest.toList.flatMap(_.dependencies.values)
    (fromSections ++ fromManifest).distinct

  private def collectImportsFromSections(sections: List[Section]): List[String] =
    sections.flatMap { s =>
      s.content.collect { case imp: Content.Import => imp.path } ++
        collectImportsFromSections(s.subsections)
    }

  /** True for import paths that resolve as local files (not URL / dep:
   *  / arbitrary scheme).  Matches `ModuleGraph.isLocalPath` so the same
   *  rules apply in both `build --incremental` and `compile-jvm/-js`. */
  private def isLocalPath(raw: String): Boolean =
    !raw.startsWith("http://") &&
    !raw.startsWith("https://") &&
    !raw.startsWith("dep:") &&
    !raw.contains("://")

  /** Default artifact directory for dependency outputs when the user
   *  doesn't pass `--artifact-dir`.  Lives next to the target source. */
  def defaultArtifactDir(targetPath: os.Path): os.Path =
    targetPath / os.up / ".ssc-artifacts"

  /** True when a dependency's `.scim` artifact at `artifactDir` matches
   *  its current source SHA-256. */
  def isScimFresh(node: Node, artifactDir: os.Path): Boolean =
    val scim = artifactDir / (node.path.last.stripSuffix(".ssc") + ".scim")
    if !os.exists(scim) then false
    else ArtifactIO.readInterfaceFile(scim) match
      case Right(iface) =>
        iface.sourceHash == InterfaceExtractor.sha256(node.sourceBytes)
      case Left(_) => false

  /** True when a dependency's `.scjvm` artifact at `artifactDir` matches
   *  its current source SHA-256. */
  def isScjvmFresh(node: Node, artifactDir: os.Path): Boolean =
    val scjvm = artifactDir / (node.path.last.stripSuffix(".ssc") + ".scjvm")
    if !os.exists(scjvm) then false
    else JvmArtifactIO.readJvmFile(scjvm) match
      case Right(a) =>
        a.sourceHash == InterfaceExtractor.sha256(node.sourceBytes)
      case Left(_) => false

  /** True when a dependency's `.scjs` artifact at `artifactDir` matches
   *  its current source SHA-256. */
  def isScjsFresh(node: Node, artifactDir: os.Path): Boolean =
    val scjs = artifactDir / (node.path.last.stripSuffix(".ssc") + ".scjs")
    if !os.exists(scjs) then false
    else JsArtifactIO.readJsFile(scjs) match
      case Right(a) =>
        a.sourceHash == InterfaceExtractor.sha256(node.sourceBytes)
      case Left(_) => false
