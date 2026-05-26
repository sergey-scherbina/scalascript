package scalascript.transform

import scala.meta.*
import scalascript.ast.*

/** Derives `ApiClientDecl` metadata from `route()` call expressions.
 *
 *  When a module's front-matter does NOT declare `apiClients:`, this pass
 *  walks every parseable code block and collects all
 *  `route("METHOD", "/path") { ... }` calls, producing a single
 *  `ApiClientDecl("Api", ...)` entry so typed clients can be generated
 *  downstream without manual front-matter maintenance.
 *
 *  If any explicit `apiClients:` are already declared in the front-matter
 *  the module is returned unchanged — explicit always wins.
 */
object RouteDeriver:

  def derive(module: Module): Module =
    val existingClients = module.manifest.map(_.apiClients).getOrElse(Nil)
    if existingClients.nonEmpty then module
    else
      val endpoints = collectEndpoints(module)
      if endpoints.isEmpty then module
      else
        val derived = ApiClientDecl("Api", endpoints)
        module.copy(
          manifest = module.manifest.map(m => m.copy(apiClients = List(derived)))
        )

  private def collectEndpoints(module: Module): List[ApiEndpointDecl] =
    val seen = collection.mutable.LinkedHashMap.empty[String, ApiEndpointDecl]
    walkSections(module.sections, seen)
    seen.values.toList

  private def walkSections(
    sections: List[Section],
    acc: collection.mutable.LinkedHashMap[String, ApiEndpointDecl]
  ): Unit =
    sections.foreach { s =>
      s.content.foreach {
        case cb: Content.CodeBlock if Lang.isParseable(cb.lang) =>
          cb.tree.foreach { node =>
            ScalaNode.fold(node) { tree =>
              tree.collect {
                case Term.Apply.After_4_6_0(
                  Term.Apply.After_4_6_0(Term.Name("route"), Term.ArgClause(args, _)),
                  _
                ) =>
                  extractEndpoint(args).foreach { ep =>
                    val key = s"${ep.method}:${ep.path}"
                    if !acc.contains(key) then acc(key) = ep
                  }
              }
            }
          }
        case _ =>
      }
      walkSections(s.subsections, acc)
    }

  private def extractEndpoint(args: List[Term]): Option[ApiEndpointDecl] =
    args match
      case Lit.String(method) :: Lit.String(path) :: _ =>
        val name         = deriveName(method, path)
        val requestType  = if needsBody(method) then "Any" else "Unit"
        val responseType = "Any"
        Some(ApiEndpointDecl(name, method.toUpperCase, path, requestType, responseType))
      case _ => None

  private def needsBody(method: String): Boolean =
    method.toUpperCase match
      case "POST" | "PUT" | "PATCH" => true
      case _                        => false

  /** Derives a camelCase name from a method + path.
   *  `GET /api/users/:id` → `getUsersById`
   *  `POST /api/todos`    → `postTodos`
   *  `DELETE /api/items`  → `deleteItems`
   */
  private def deriveName(method: String, path: String): String =
    val methodPart = method.toLowerCase
    val segments   = path.split('/').filter(_.nonEmpty).toList
    val pathPart   = segments.map {
      case s if s.startsWith(":") => "By" + s.drop(1).capitalize
      case s if s.startsWith("{") && s.endsWith("}") =>
        "By" + s.drop(1).dropRight(1).capitalize
      case s => s.replaceAll("[^a-zA-Z0-9]", "_").split('_')
                  .filter(_.nonEmpty).map(_.capitalize).mkString
    }.mkString
    if pathPart.isEmpty then methodPart
    else methodPart + pathPart
