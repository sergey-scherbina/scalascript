package scalascript.compiler.plugin.graphql

/** Runtime limits and policy for a GraphQL endpoint.
 *
 *  Created via the `GraphQL.options(...)` intrinsic and passed as the 3rd
 *  argument to `graphqlHandler`, `graphqlMount`, or `serveGraphQL`.
 *
 *  @param maxDepth             reject queries exceeding this nesting depth
 *  @param maxComplexity        reject queries exceeding this complexity score
 *  @param maxQueryLength       reject queries whose text exceeds this byte count
 *  @param disableIntrospection reject `__schema` / `__type` introspection queries
 *  @param persistedOps         pre-approved operation manifest: SHA-256 hash → operation text.
 *                              When non-empty, APQ requests (hash-only) are looked up here.
 *  @param persistedOnly        when true, reject any request whose query text is not in the
 *                              persisted-operations manifest (or whose hash is unknown).
 */
case class GraphQLOpts(
  maxDepth:             Option[Int]            = None,
  maxComplexity:        Option[Int]            = None,
  maxQueryLength:       Option[Int]            = None,
  disableIntrospection: Boolean                = false,
  persistedOps:         Map[String, String]    = Map.empty,
  persistedOnly:        Boolean                = false,
)

object GraphQLOpts:
  val default: GraphQLOpts = GraphQLOpts()
