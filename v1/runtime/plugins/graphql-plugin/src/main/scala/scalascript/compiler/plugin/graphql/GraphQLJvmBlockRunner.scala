package scalascript.compiler.plugin.graphql

import scalascript.backend.spi.GraphQLBlockRunner

/** Thread-safe SDL accumulator for the interpreter runtime.
 *
 *  A single `.ssc` file may have at most one `graphql` fenced block.
 *  If there are multiple, the last one wins (parallel to `sql` behaviour).
 */
class GraphQLJvmBlockRunner extends GraphQLBlockRunner:
  @volatile private var _sdl: Option[String] = None

  def registerSdl(source: String): Unit = _sdl = Some(source)
  def registeredSdl: Option[String]      = _sdl
