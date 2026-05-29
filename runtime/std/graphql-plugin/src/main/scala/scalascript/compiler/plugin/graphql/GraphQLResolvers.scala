package scalascript.compiler.plugin.graphql

import scalascript.interpreter.Value

/** Codec for a custom GraphQL scalar type.
 *
 *  @param serialize  converts a resolver return value (ScalaScript Value) to JSON-serializable Any
 *  @param coerce     converts a JSON input value (Any from graphql-java) to a ScalaScript Value
 */
case class ScalarCodec(
  serialize: Value => Any,
  coerce:    Any   => Value,
)

/** Descriptor for a DataLoader registered with `GraphQL.dataLoader(name, batchFn)`.
 *
 *  `batchFn` is a ScalaScript closure: `List[K] => Map[K, V]`.
 *  Invoked via `PluginContext.invokeCallback`.
 */
case class DataLoaderSpec(name: String, batchFn: Any)

case class GraphQLResolvers(
  query:        Map[String, Value],
  mutation:     Map[String, Value],
  subscription: Map[String, Value]           = Map.empty,
  scalars:      Map[String, ScalarCodec]     = Map.empty,
  loaders:      Map[String, DataLoaderSpec]  = Map.empty,
)
