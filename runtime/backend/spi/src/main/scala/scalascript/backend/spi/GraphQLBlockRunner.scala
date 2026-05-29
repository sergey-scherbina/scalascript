package scalascript.backend.spi

/** Interpreter hook for executable `graphql` fenced blocks.
 *
 *  A plugin that understands GraphQL SDL owns schema compilation and
 *  execution.  The interpreter owns markdown traversal (SectionRuntime).
 */
trait GraphQLBlockRunner:
  def registerSdl(source: String): Unit
  def registeredSdl: Option[String]
