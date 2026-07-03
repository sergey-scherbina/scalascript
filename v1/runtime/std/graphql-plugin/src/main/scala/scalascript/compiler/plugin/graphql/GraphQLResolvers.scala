package scalascript.compiler.plugin.graphql

/** Codec for a custom GraphQL scalar type.
 *
 *  These descriptors are opaque value carriers — they hold ScalaScript runtime values
 *  as `Any` so this file needs no interpreter / plugin-api dependency; the plugin wraps
 *  them as `PluginValue` at the boundary where it operates on them.
 *
 *  @param serialize  converts a resolver return value (runtime value) to JSON-serializable Any
 *  @param coerce     converts a JSON input value (Any from graphql-java) to a runtime value
 */
case class ScalarCodec(
  serialize: Any => Any,
  coerce:    Any => Any,
)

/** Descriptor for a DataLoader registered with `GraphQL.dataLoader(name, batchFn)`.
 *
 *  `batchFn` is a ScalaScript closure: `List[K] => Map[K, V]`.
 *  Invoked via `PluginContext.invokeCallback`.
 */
case class DataLoaderSpec(name: String, batchFn: Any)

case class GraphQLResolvers(
  query:        Map[String, AnyRef],
  mutation:     Map[String, AnyRef],
  subscription: Map[String, AnyRef]         = Map.empty,
  scalars:      Map[String, ScalarCodec]    = Map.empty,
  loaders:      Map[String, DataLoaderSpec] = Map.empty,
)

/** Entity resolvers for Apollo Federation v2 subgraph support.
 *
 *  keys are GraphQL type names (e.g. "Product", "User");
 *  values are resolver functions `representation: Map => value`
 *  where the representation map contains `__typename` plus the `@key` fields.
 */
case class GraphQLFederationEntities(entities: Map[String, AnyRef])
