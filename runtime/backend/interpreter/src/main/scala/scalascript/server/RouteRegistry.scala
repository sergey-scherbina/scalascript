package scalascript.server

import scalascript.interpreter.{Interpreter, Value}

/** SPI for HTTP route registration and dispatch.
 *
 *  Decouples the `Interpreter` (which registers routes) from the
 *  `interpreter-server` module (which dispatches them) so neither side
 *  needs to reference the other's concrete types directly.
 *
 *  The default implementation is the global [[Routes]] singleton.
 *  Tests may supply a custom implementation to inspect registered routes
 *  without spinning up a real HTTP server. */
trait RouteRegistry:

  def register(
      method:   String,
      path:     String,
      handler:  Value,
      interp:   Interpreter,
      source:   Option[String]     = None,
      mountCtx: Map[String, Value] = Map.empty,
      style:    String             = "route"
  ): Unit

  def remove(method: String, path: String): Boolean

  def removeBySource(absPath: String): Unit

  def addMiddleware(fn: Value, interp: Interpreter): Unit

  def middlewares: List[(Value, Interpreter)]

  def all: List[Routes.Entry]

  def matchRequest(method: String, path: String): Option[(Routes.Entry, Map[String, String])]

  def clear(): Unit
