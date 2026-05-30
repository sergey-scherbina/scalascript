package scalascript.codegen

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName

/** GraphQL server + client intrinsics for the JS (Node.js) backend.
 *
 *  Each entry is a `RuntimeCall` whose target is a function/object emitted
 *  by `JsRuntimeGraphql` (see `JsRuntimeGraphql.scala`).  Declaring them
 *  here satisfies `CapabilityCheck` so a program calling these `extern def`s
 *  is accepted by the JS / Node backends, and `dispatchIntrinsicJs` rewrites
 *  each call site to the named runtime symbol.
 *
 *  Scope: query + mutation + nested-type resolvers, custom scalars
 *  (`GraphQL.scalar`), per-request DataLoader batching (`GraphQL.dataLoader`
 *  + the `_load` / `_batchLoad` functions injected into resolver args),
 *  security limits (`GraphQL.options`: maxDepth / maxComplexity /
 *  maxQueryLength / disableIntrospection), `graphqlHandler` / `graphqlMount` /
 *  `serveGraphQL`, the `graphqlQuery` client, and subscriptions — served over
 *  graphql-transport-ws (`/graphql/ws`, mounted automatically when the resolver
 *  set declares subscriptions) plus a buffered GraphQL-over-SSE response on
 *  `POST /graphql` with `Accept: text/event-stream`; the `graphqlSse` /
 *  `graphqlSubscribe` clients consume them.  Federation and persisted-operation
 *  policy are JVM-only for now — the JS runtime omits them. */
val JsGraphqlIntrinsics: Map[QualifiedName, IntrinsicImpl] = Map(
  QualifiedName("GraphQL.schema")     -> RuntimeCall("GraphQL.schema"),
  QualifiedName("GraphQL.resolvers")  -> RuntimeCall("GraphQL.resolvers"),
  QualifiedName("GraphQL.scalar")     -> RuntimeCall("GraphQL.scalar"),
  QualifiedName("GraphQL.dataLoader") -> RuntimeCall("GraphQL.dataLoader"),
  QualifiedName("GraphQL.options")    -> RuntimeCall("GraphQL.options"),
  QualifiedName("serveGraphQL")      -> RuntimeCall("serveGraphQL"),
  QualifiedName("graphqlMount")      -> RuntimeCall("graphqlMount"),
  QualifiedName("graphqlHandler")    -> RuntimeCall("graphqlHandler"),
  QualifiedName("graphqlQuery")      -> RuntimeCall("graphqlQuery"),
  QualifiedName("graphqlSse")        -> RuntimeCall("graphqlSse"),
  QualifiedName("graphqlSubscribe")  -> RuntimeCall("graphqlSubscribe"),
)
