package scalascript.transform

import scala.meta.*
import scalascript.ast.*

/** Derives `ApiClientDecl` metadata from route declarations.
 *
 *  When a module's front-matter does NOT declare `apiClients:`, this pass
 *  collects routes from three sources and produces a single
 *  `ApiClientDecl("Api", ...)`:
 *
 *  1. `routes:` front-matter entries (`RouteDecl` list in the manifest).
 *  2. Inline `route("METHOD", "/path") { ... }` calls in code blocks
 *     (via scalameta AST traversal).
 *  3. `mount("METHOD", "/path", "file.ssc")` calls — with optional cross-file
 *     typed-handler analysis when `baseDir` is provided.
 *
 *  For typed `mount()` handlers (a `.ssc` file whose last expression is a
 *  function literal like `(input: GreetInput) => ...`), the derivation
 *  extracts the parameter type as `requestType` instead of defaulting to
 *  `"Any"`.
 *
 *  If any explicit `apiClients:` are already declared in the front-matter
 *  (`raw` YAML map contains `"apiClients"` or `"api-clients"` key), the
 *  module is returned unchanged — explicit always wins.
 *
 *  When called with `baseDir`, the pass re-derives even if a previous
 *  no-baseDir call already ran (e.g., from inside `Parser.parse()`), so
 *  `parseFile()` can enhance mount endpoints with typed parameters.
 */
object RouteDeriver:

  def derive(module: Module): Module = derive(module, None)

  /** Full derivation with cross-file typed-handler analysis.
   *  `baseDir` should be the directory containing the `.ssc` source file. */
  def derive(module: Module, baseDir: Option[os.Path]): Module =
    module.manifest match
      case None => module
      case Some(manifest) =>
        // Explicit front-matter apiClients always win.
        val hasExplicit =
          manifest.raw.contains("apiClients") || manifest.raw.contains("api-clients")
        if hasExplicit then module
        else
          val endpoints = collectEndpoints(module, baseDir)
          if endpoints.isEmpty then module
          else
            val derived = ApiClientDecl("Api", endpoints)
            val clients =
              if manifest.apiClients.exists(_.name == derived.name) then
                manifest.apiClients.map {
                  case c if c.name == derived.name => c.copy(endpoints = mergeEndpoints(c.endpoints, derived.endpoints))
                  case c => c
                }
              else manifest.apiClients :+ derived
            module.copy(manifest = Some(manifest.copy(apiClients = clients)))

  private def mergeEndpoints(existing: List[ApiEndpointDecl], fresh: List[ApiEndpointDecl]): List[ApiEndpointDecl] =
    val seen = existing.map(e => e.method -> e.path).toSet
    existing ++ fresh.filterNot(e => seen.contains(e.method -> e.path))

  private def collectEndpoints(
    module:  Module,
    baseDir: Option[os.Path],
  ): List[ApiEndpointDecl] =
    val seen = collection.mutable.LinkedHashMap.empty[String, ApiEndpointDecl]

    // 1. front-matter routes:
    module.manifest.foreach { manifest =>
      manifest.routes.foreach { r =>
        val ep  = makeEndpoint(r.method, r.path, None)
        val key = s"${ep.method}:${ep.path}"
        if !seen.contains(key) then seen(key) = ep
      }
    }

    // 2 + 3. inline route() and mount() calls in code blocks
    walkSections(module.sections, seen, baseDir)
    seen.values.toList

  private def walkSections(
    sections: List[Section],
    acc:      collection.mutable.LinkedHashMap[String, ApiEndpointDecl],
    baseDir:  Option[os.Path],
  ): Unit =
    sections.foreach { s =>
      s.content.foreach {
        case cb: Content.CodeBlock if Lang.isParseable(cb.lang) =>
          cb.tree.foreach { node =>
            ScalaNode.fold(node) { tree =>
              tree.collect {
                // route("METHOD", "/path") { ... }
                case Term.Apply.After_4_6_0(
                  Term.Apply.After_4_6_0(Term.Name("route"), Term.ArgClause(routeArgs, _)),
                  _
                ) =>
                  extractRouteEndpoint(routeArgs).foreach { ep =>
                    val key = s"${ep.method}:${ep.path}"
                    if !acc.contains(key) then acc(key) = ep
                  }
                // mount("METHOD", "/path", "file.ssc") / mount("METHOD", "/path", "file.ssc", ctx)
                case Term.Apply.After_4_6_0(Term.Name("mount"), Term.ArgClause(mountArgs, _)) =>
                  extractMountEndpoint(mountArgs, baseDir).foreach { ep =>
                    val key = s"${ep.method}:${ep.path}"
                    if !acc.contains(key) then acc(key) = ep
                  }
              }
            }
          }
        case _ =>
      }
      walkSections(s.subsections, acc, baseDir)
    }

  // ── route() extraction ─────────────────────────────────────────────────────

  private def extractRouteEndpoint(args: List[Term]): Option[ApiEndpointDecl] =
    args match
      case Lit.String(method) :: Lit.String(path) :: _ =>
        Some(makeEndpoint(method, path, None))
      case _ => None

  // ── mount() extraction ─────────────────────────────────────────────────────

  private def extractMountEndpoint(
    args:    List[Term],
    baseDir: Option[os.Path],
  ): Option[ApiEndpointDecl] =
    args match
      case Lit.String(method) :: Lit.String(path) :: Lit.String(handlerFile) :: _ =>
        val types = baseDir.flatMap(d => loadHandlerTypes(d, handlerFile))
        Some(makeEndpoint(method, path, types))
      case Lit.String(method) :: Lit.String(path) :: _ =>
        Some(makeEndpoint(method, path, None))
      case _ => None

  // ── Cross-file typed handler analysis ─────────────────────────────────────

  private def loadHandlerTypes(
    baseDir:     os.Path,
    handlerPath: String,
  ): Option[(String, String)] =
    scala.util.Try(baseDir / os.RelPath(handlerPath)).toOption
      .filter(os.exists)
      .flatMap(f => scala.util.Try(os.read(f)).toOption)
      .flatMap(extractFunctionTypes)

  /** Parse `src` and look for a top-level function literal whose first
   *  parameter has an explicit uppercase type annotation.
   *  `(input: GreetInput) => ...`  →  Some(("GreetInput", "Any"))
   *
   *  Tries `parse[Term]` first (for bare lambdas), then `parse[Source]` for
   *  full files with class/def wrappers.
   */
  private def extractFunctionTypes(src: String): Option[(String, String)] =
    val code = if src.startsWith("---") then src.dropWhile(_ != '\n').dropWhile(_ == '\n') else src
    val input = dialects.Scala3(Input.VirtualFile("<handler>", code))
    val tree: Option[Tree] =
      input.parse[Term].toOption.orElse(input.parse[Source].toOption)
    tree.flatMap { t =>
      val clauses = t.collect {
        case Term.Function.After_4_6_0(paramClause, _) => paramClause
      }
      clauses.lastOption.flatMap { clause =>
        clause.values.headOption.flatMap { p =>
          p.decltpe.collect {
            case Type.Name(n) if n.headOption.exists(_.isUpper) => (n, "Any")
          }
        }
      }
    }

  // ── Endpoint construction ──────────────────────────────────────────────────

  private def makeEndpoint(
    method: String,
    path:   String,
    types:  Option[(String, String)],
  ): ApiEndpointDecl =
    val requestType  = types.map(_._1).getOrElse(if needsBody(method) then "Any" else "Unit")
    val responseType = types.map(_._2).getOrElse("Any")
    ApiEndpointDecl(deriveName(method, path), method.toUpperCase, path, requestType, responseType)

  private def needsBody(method: String): Boolean =
    method.toUpperCase match
      case "POST" | "PUT" | "PATCH" => true
      case _                        => false

  // ── Name derivation ────────────────────────────────────────────────────────

  /** Derives a camelCase name from a method + path.
   *  `GET /api/users/:id` → `getApiUsersById`
   *  `POST /api/todos`    → `postApiTodos`
   *  `DELETE /api/items`  → `deleteApiItems`
   */
  private def deriveName(method: String, path: String): String =
    val methodPart = method.toLowerCase
    val segments   = path.split('/').filter(_.nonEmpty).toList
    val pathPart   = segments.map {
      case s if s.startsWith(":") => "By" + s.drop(1).capitalize
      case s if s.startsWith("{") && s.endsWith("}") =>
        "By" + s.drop(1).dropRight(1).capitalize
      case s =>
        s.replaceAll("[^a-zA-Z0-9]", "_").split('_')
          .filter(_.nonEmpty).map(_.capitalize).mkString
    }.mkString
    if pathPart.isEmpty then methodPart else methodPart + pathPart
