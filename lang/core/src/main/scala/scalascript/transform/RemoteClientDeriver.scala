package scalascript.transform

import scalascript.ast.*

/** Derives typed HTTP client metadata from `remoteHandlers:` entries.
 *
 *  A remote handler with a transport `path:` is already an HTTP POST JSON
 *  fallback endpoint.  This pass exposes the same endpoint to the existing
 *  typed route client generators instead of creating a separate one-off RPC
 *  client path.
 */
object RemoteClientDeriver:

  private val ClientName = "RemoteRpc"

  def derive(module: Module): Module =
    module.manifest match
      case None => module
      case Some(manifest) =>
        val endpoints = manifest.remoteHandlers.flatMap(endpointFromHandler)
        if endpoints.isEmpty then module
        else
          val existingClients = manifest.apiClients
          val existingRemote = existingClients.find(_.name == ClientName)
          val existingKeys = existingRemote.toList.flatMap(_.endpoints).map(e => e.name -> e.path).toSet
          val fresh = endpoints.filterNot(e => existingKeys.contains(e.name -> e.path))
          if fresh.isEmpty then module
          else
            val updatedClients =
              if existingRemote.isDefined then
                existingClients.map {
                  case c if c.name == ClientName => c.copy(endpoints = c.endpoints ++ fresh)
                  case c => c
                }
              else existingClients :+ ApiClientDecl(ClientName, fresh)
            module.copy(manifest = Some(manifest.copy(apiClients = updatedClients)))

  private def endpointFromHandler(handler: RemoteHandlerDecl): Option[ApiEndpointDecl] =
    handler.path.map { path =>
      ApiEndpointDecl(
        name         = endpointName(handler.name),
        method       = "POST",
        path         = path,
        requestType  = handler.requestType.getOrElse("Any"),
        responseType = handler.responseType.getOrElse("Any")
      )
    }

  private def endpointName(operation: String): String =
    val words = operation.split("[^A-Za-z0-9]+").toList.filter(_.nonEmpty)
    val raw = words match
      case Nil => "call"
      case first :: rest => first.toLowerCase + rest.map(capitalize).mkString
    val cleaned = raw.replaceAll("[^A-Za-z0-9_]", "")
    if cleaned.headOption.exists(_.isLetter) then cleaned else "call" + capitalize(cleaned)

  private def capitalize(value: String): String =
    value.headOption match
      case Some(ch) => ch.toUpper.toString + value.drop(1)
      case None     => value
