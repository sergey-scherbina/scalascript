package scalascript.codegen

/** GraphQL JS runtime preamble — emitted when GraphQL usage is detected.
 *
 *  Backed by the `graphql` npm package (`buildSchema` + `graphql()` /
 *  `execute`).  The `graphql` dependency is declared in the companion
 *  `package.json` emitted by `NodeBackend.emitPackageJson`.
 *
 *  Resolver dispatch mirrors the JVM (`graphql-java`) backend:
 *    - resolver keys may be plain field names (`"hello"`) or schema
 *      coordinates (`"Query.hello"`, `"User.name"`);
 *    - a resolver receives the field's arguments as a `Map` (NOT the
 *      parent source — same contract as the interpreter / JVM paths);
 *    - fields without a custom resolver fall back to graphql-js default
 *      property resolution from the parent object (nested Maps / objects and
 *      lists thereof resolve recursively through the same field resolver);
 *    - resolvers may be async (return a Promise) — graphql-js awaits them;
 *    - custom scalars declared with `GraphQL.scalar(name, serialize, coerce)`
 *      and passed via `GraphQL.resolvers(scalars = …)` override the schema's
 *      `serialize` / `parseValue` / `parseLiteral` (see `_graphqlApplyScalars`);
 *    - per-request DataLoaders declared with `GraphQL.dataLoader(name, batchFn)`
 *      and passed via `GraphQL.resolvers(loaders = …)` are reachable inside a
 *      resolver through the `_load(name, key)` / `_batchLoad(name, keys)`
 *      functions injected into its argument Map (see `_GraphqlDataLoaderCtx`);
 *    - security limits declared with `GraphQL.options(maxDepth, maxComplexity,
 *      maxQueryLength, disableIntrospection)` and passed as the optional last
 *      arg to `graphqlHandler` / `graphqlMount` / `serveGraphQL` enforce a
 *      pre-parse body/query length guard plus graphql-js validation rules
 *      (introspection lock-down, query-depth, field-count complexity).
 *
 *  The runtime integrates with the HTTP runtime (`route` / `_routes` /
 *  `serve`), so capability detection forces `HtmlDsl` + `Async` whenever
 *  GraphQL is used. */
val JsRuntimeGraphql: String = JsRuntimeResource.load("graphql.mjs")
