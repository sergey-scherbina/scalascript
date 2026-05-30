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
 *  `graphqlHandler` / `graphqlMount` / `serveGraphQL`, and the `graphqlQuery`
 *  client.  Subscriptions, federation, and security limits are JVM-only for
 *  now — the JS runtime omits them. */
val JsGraphqlIntrinsics: Map[QualifiedName, IntrinsicImpl] = Map(
  QualifiedName("GraphQL.schema")     -> RuntimeCall("GraphQL.schema"),
  QualifiedName("GraphQL.resolvers")  -> RuntimeCall("GraphQL.resolvers"),
  QualifiedName("GraphQL.scalar")     -> RuntimeCall("GraphQL.scalar"),
  QualifiedName("GraphQL.dataLoader") -> RuntimeCall("GraphQL.dataLoader"),
  QualifiedName("serveGraphQL")      -> RuntimeCall("serveGraphQL"),
  QualifiedName("graphqlMount")      -> RuntimeCall("graphqlMount"),
  QualifiedName("graphqlHandler")    -> RuntimeCall("graphqlHandler"),
  QualifiedName("graphqlQuery")      -> RuntimeCall("graphqlQuery"),
)
