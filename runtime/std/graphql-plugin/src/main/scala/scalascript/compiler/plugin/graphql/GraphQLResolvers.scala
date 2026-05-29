package scalascript.compiler.plugin.graphql

import scalascript.interpreter.Value

case class GraphQLResolvers(
  query:    Map[String, Value],
  mutation: Map[String, Value],
)
