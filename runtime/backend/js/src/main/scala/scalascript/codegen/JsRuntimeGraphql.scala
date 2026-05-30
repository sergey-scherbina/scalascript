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
val JsRuntimeGraphql: String = """
// ── GraphQL runtime (Node target, 'graphql' npm package) ─────────────────────

// SDL registered by a ```graphql fenced block earlier in the document.
// At most one per module — the last block wins (parallel to sql).
let _graphqlSdl = null;
function _registerGraphqlSdl(sdl) { _graphqlSdl = sdl; }

// Normalise a resolver collection (a ScalaScript Map, a plain object, or
// undefined) into a flat coordinate → function table.
function _graphqlToTable(m) {
  const o = {};
  if (m instanceof Map) { for (const [k, v] of m) o[k] = v; }
  else if (m && typeof m === 'object') { Object.assign(o, m); }
  return o;
}

const GraphQL = {
  // GraphQL.schema(sdl) — parse + validate SDL, return an opaque handle.
  schema: function(sdl) {
    const g = require('graphql');
    return { _type: 'GraphQLSchema', sdl: sdl, schema: g.buildSchema(sdl) };
  },
  // GraphQL.resolvers(opts) — opts carries named args { query, mutation,
  // subscription, scalars, loaders } (named-arg call sites are lowered to an
  // options object by dispatchIntrinsicJs).
  resolvers: function(opts) {
    opts = opts || {};
    return {
      _type:        'GraphQLResolvers',
      query:        _graphqlToTable(opts.query),
      mutation:     _graphqlToTable(opts.mutation),
      subscription: _graphqlToTable(opts.subscription),
      scalars:      _graphqlToTable(opts.scalars),
      loaders:      _graphqlToTable(opts.loaders),
    };
  },
  // GraphQL.scalar(name, serialize, coerce) — custom scalar codec.
  //   serialize : resolver output value -> JSON-serializable value
  //   coerce    : JSON/literal input value -> resolver value
  // Accepts both the named-arg shape `(name, { serialize, coerce })` (the
  // common DSL form, lowered by dispatchIntrinsicJs) and the all-positional
  // shape `(name, serializeFn, coerceFn)`.
  scalar: function(name, a, b) {
    let serialize, coerce;
    if (a && typeof a === 'object' && (typeof a.serialize === 'function' || typeof a.coerce === 'function')) {
      serialize = a.serialize; coerce = a.coerce;
    } else {
      serialize = a; coerce = b;
    }
    return { _type: 'GraphQLScalar', name: name, serialize: serialize, coerce: coerce };
  },
  // GraphQL.dataLoader(name, batchFn) — register a per-request-cached
  // DataLoader.  `batchFn : List[K] => Map[K, V]` receives an Array of keys
  // and returns a Map (may be async / return a Promise).  Resolvers reach the
  // loader through the `_load(name, key)` / `_batchLoad(name, keys)` functions
  // injected into their argument Map (see `_graphqlFieldResolver`).
  dataLoader: function(name, batchFn) {
    return { _type: 'GraphQLDataLoader', name: name, batchFn: batchFn };
  },
  // GraphQL.options(maxDepth, maxComplexity, maxQueryLength, disableIntrospection)
  // — runtime security limits, passed as the optional last arg to
  // `graphqlHandler` / `graphqlMount` / `serveGraphQL`.  Accepts both the
  // named-arg shape (a single options object, lowered by dispatchIntrinsicJs)
  // and the all-positional shape.  All fields are optional; an absent limit is
  // not enforced.  Mirrors the JVM `GraphQL.options` (depth/complexity/length/
  // introspection); persisted-operations policy is JVM-only for now.
  options: function(a, b, c, d) {
    let o;
    if (a && typeof a === 'object') {
      o = a;
    } else {
      o = { maxDepth: a, maxComplexity: b, maxQueryLength: c, disableIntrospection: d };
    }
    const num = v => (typeof v === 'number' ? v : null);
    return {
      _type:                'GraphQLOptions',
      maxDepth:             num(o.maxDepth),
      maxComplexity:        num(o.maxComplexity),
      maxQueryLength:       num(o.maxQueryLength),
      disableIntrospection: o.disableIntrospection === true,
    };
  },
};

// Build the validation rules implied by a GraphQLOptions object: introspection
// lock-down (graphql-js' NoSchemaIntrospectionCustomRule), a query-depth limit,
// and a field-count complexity limit.  `maxComplexity` is approximated by the
// total number of field selections in the document (the JVM uses graphql-java's
// MaxQueryComplexityInstrumentation; field-count is a close, documented proxy).
function _graphqlLimitRules(opts, g) {
  const rules = [];
  if (opts.disableIntrospection && g.NoSchemaIntrospectionCustomRule) {
    rules.push(g.NoSchemaIntrospectionCustomRule);
  }
  if (opts.maxDepth != null) {
    const maxDepth = opts.maxDepth;
    rules.push(function(context) {
      const depthOf = (sel, depth) => {
        if (!sel || !sel.selections) return depth;
        let max = depth;
        for (const s of sel.selections) {
          const next = s.kind === 'Field' ? depth + 1 : depth;
          const d = depthOf(s.selectionSet, next);
          if (d > max) max = d;
        }
        return max;
      };
      return {
        OperationDefinition: function(node) {
          const d = depthOf(node.selectionSet, 0);
          if (d > maxDepth) context.reportError(new g.GraphQLError(
            'Query exceeds maximum depth of ' + maxDepth + ' (got ' + d + ')', { nodes: [node] }));
        },
      };
    });
  }
  if (opts.maxComplexity != null) {
    const maxComplexity = opts.maxComplexity;
    rules.push(function(context) {
      let count = 0;
      return {
        Field: function() { count++; },
        Document: { leave: function() {
          if (count > maxComplexity) context.reportError(new g.GraphQLError(
            'Query exceeds maximum complexity of ' + maxComplexity + ' (got ' + count + ')'));
        } },
      };
    });
  }
  return rules;
}

// Attach custom-scalar serialize/parse behaviour to a built schema.  graphql-js
// `buildSchema` materialises `scalar Foo` declarations as pass-through
// `GraphQLScalarType` instances; we override their `serialize` / `parseValue` /
// `parseLiteral` so resolver outputs and inputs flow through the user codecs.
function _graphqlApplyScalars(schema, resolvers, g) {
  const scalars = (resolvers && resolvers.scalars) || {};
  for (const name of Object.keys(scalars)) {
    const sc = scalars[name];
    const t  = schema.getType(name);
    if (!t || !sc) continue;
    if (typeof sc.serialize === 'function') t.serialize = function(v) { return sc.serialize(v); };
    if (typeof sc.coerce === 'function') {
      t.parseValue   = function(v)   { return sc.coerce(v); };
      t.parseLiteral = function(ast) { return sc.coerce(g.valueFromASTUntyped(ast)); };
    }
  }
}

// Per-request DataLoader cache.  Deduplicates repeated key fetches within one
// request and coalesces a request's keys into a single batch-function call.
// Mirrors the JVM (`graphql-java`) DataLoaderContext semantics:
//   - `load(name, key)` returns the value for one key; the FIRST load for an
//     uncached key invokes the batch fn with `[key]` and back-fills the cache
//     with every entry the batch returns;
//   - `batchLoad(name, keys)` invokes the batch fn ONCE for all uncached keys
//     and returns a Map of key → value for the requested keys.
// A synchronous batch fn yields synchronous results (matching the JVM, so a
// resolver can index the returned Map directly); a batch fn that returns a
// Promise yields a Promise — which graphql-js awaits.
function _GraphqlDataLoaderCtx(specs) {
  this.specs = specs || {};
  this.cache = new Map();   // loaderName -> Map(key -> value)
}
_GraphqlDataLoaderCtx.prototype._spec = function(name) {
  const s = this.specs[name];
  if (!s) throw new Error("Unknown DataLoader: '" + name + "'");
  return s;
};
_GraphqlDataLoaderCtx.prototype._cacheFor = function(name) {
  let c = this.cache.get(name);
  if (!c) { c = new Map(); this.cache.set(name, c); }
  return c;
};
_GraphqlDataLoaderCtx.prototype.load = function(name, key) {
  const spec = this._spec(name);
  const c    = this._cacheFor(name);
  if (c.has(key)) return c.get(key);
  const fill = res => {
    if (res instanceof Map) {
      for (const [k, v] of res) c.set(k, v);
      return c.has(key) ? c.get(key) : null;
    }
    c.set(key, res);
    return res;
  };
  const res = spec.batchFn([key]);
  return (res && typeof res.then === 'function') ? res.then(fill) : fill(res);
};
_GraphqlDataLoaderCtx.prototype.batchLoad = function(name, keys) {
  const spec     = this._spec(name);
  const c        = this._cacheFor(name);
  const uncached = keys.filter(k => !c.has(k));
  const collect  = () => new Map(keys.map(k => [k, c.has(k) ? c.get(k) : null]));
  if (uncached.length === 0) return collect();
  const fill = res => { if (res instanceof Map) for (const [k, v] of res) c.set(k, v); return collect(); };
  const res = spec.batchFn(uncached);
  return (res && typeof res.then === 'function') ? res.then(fill) : fill(res);
};

// Build the combined coordinate → resolver table and a graphql-js
// fieldResolver that dispatches custom resolvers and otherwise falls back
// to default property resolution.
function _graphqlFieldResolver(resolvers, g) {
  const tbl = Object.assign({}, resolvers.query, resolvers.mutation, resolvers.subscription);
  const def = g.defaultFieldResolver;
  return function(source, args, context, info) {
    const coord = info.parentType.name + '.' + info.fieldName;
    const fn    = tbl[coord] || tbl[info.fieldName];
    if (typeof fn === 'function') {
      // ScalaScript resolvers take a Map[String, Any] of field arguments.
      const m = new Map();
      const a = args || {};
      for (const k of Object.keys(a)) m.set(k, a[k]);
      // Inject DataLoader accessors bound to the per-request cache (when any
      // loaders are registered) — same contract as the JVM backend.
      const dl = context && context._dlCtx;
      if (dl) {
        m.set('_load',      function(ln, key)  { return dl.load(ln, key); });
        m.set('_batchLoad', function(ln, keys) { return dl.batchLoad(ln, keys); });
      }
      return fn(m);
    }
    // Default resolution — read the field off the parent. Support both
    // ScalaScript Map sources and plain objects (case-class instances).
    if (source instanceof Map) {
      const v = source.get(info.fieldName);
      return typeof v === 'function' ? v(source, args, context, info) : v;
    }
    return def(source, args, context, info);
  };
}

// Execute one GraphQL request against a built schema + resolvers.
// Returns a Promise of the { data, errors } execution result object.  When
// `opts` carries depth/complexity/introspection limits the request is run
// through an explicit parse → validate(+ limit rules) → execute pipeline so
// the extra validation rules apply (graphql-js' one-shot `graphql()` does not
// accept custom rules); otherwise the convenient `graphql()` path is used.
async function _graphqlExecute(built, resolvers, query, variables, operationName, opts) {
  const g = require('graphql');
  // Fresh DataLoader cache per request (only when loaders are registered).
  const dlCtx = (resolvers.loaders && Object.keys(resolvers.loaders).length > 0)
    ? new _GraphqlDataLoaderCtx(resolvers.loaders) : null;
  const contextValue  = { _dlCtx: dlCtx };
  const fieldResolver = _graphqlFieldResolver(resolvers, g);
  opts = opts || {};
  const needsRules = opts.disableIntrospection || opts.maxDepth != null || opts.maxComplexity != null;
  if (!needsRules) {
    return g.graphql({
      schema:         built.schema,
      source:         query,
      rootValue:      {},
      contextValue:   contextValue,
      variableValues: variables || undefined,
      operationName:  operationName || undefined,
      fieldResolver:  fieldResolver,
    });
  }
  let document;
  try { document = g.parse(query); }
  catch (e) { return { errors: [{ message: e.message }] }; }
  const rules = g.specifiedRules.concat(_graphqlLimitRules(opts, g));
  const errors = g.validate(built.schema, document, rules);
  if (errors && errors.length > 0) return { errors: errors.map(e => ({ message: e.message })) };
  return g.execute({
    schema:         built.schema,
    document:       document,
    rootValue:      {},
    contextValue:   contextValue,
    variableValues: variables || undefined,
    operationName:  operationName || undefined,
    fieldResolver:  fieldResolver,
  });
}

// Detect whether a query document contains a top-level mutation operation
// (used to reject mutations over GET per GraphQL-over-HTTP §6.2.2).
function _graphqlIsMutation(query) {
  return /(^|\})\s*mutation\b/.test(String(query || ''));
}

// graphqlHandler(schema, resolvers[, opts]) — return a Request =>
// Promise<Response> handler suitable for manual `route(...)` wiring.  `opts`
// is an optional GraphQLOptions carrying security limits (maxQueryLength is
// enforced here as a pre-parse body/query guard; depth/complexity/introspection
// are enforced inside `_graphqlExecute`).
function graphqlHandler(schema, resolvers, opts) {
  // Wire any custom scalars into the executable schema once, at handler
  // construction (not per-request).
  _graphqlApplyScalars(schema.schema, resolvers, require('graphql'));
  opts = opts || {};
  const tooLong = function(s) {
    return opts.maxQueryLength != null && String(s || '').length > opts.maxQueryLength;
  };
  const lengthError = function() {
    return _mkResp({
      status:  413,
      headers: new Map([['Content-Type', 'application/json']]),
      body:    JSON.stringify({ errors: [{ message: 'Request exceeds maximum length of ' + opts.maxQueryLength + ' bytes' }] }),
    });
  };
  return async function(req) {
    const method = (req && req.method ? String(req.method) : 'POST').toUpperCase();
    let query = null, variables = null, operationName = null;
    if (method === 'GET') {
      const q = (req && req.query instanceof Map) ? req.query : new Map();
      query = q.get('query');
      if (tooLong(query)) return lengthError();
      const v = q.get('variables');
      if (v) { try { variables = JSON.parse(v); } catch (e) {} }
      operationName = q.get('operationName') || null;
      if (_graphqlIsMutation(query)) {
        return _mkResp({
          status:  405,
          headers: new Map([['Content-Type', 'application/json']]),
          body:    JSON.stringify({ errors: [{ message: 'Mutations are not allowed over GET' }] }),
        });
      }
    } else {
      const body = (req && typeof req.body === 'string') ? req.body : '';
      if (tooLong(body)) return lengthError();
      try {
        const parsed = body.length > 0 ? JSON.parse(body) : {};
        query         = parsed.query;
        variables     = parsed.variables || null;
        operationName = parsed.operationName || null;
      } catch (e) {
        return _mkResp({
          status:  400,
          headers: new Map([['Content-Type', 'application/json']]),
          body:    JSON.stringify({ errors: [{ message: 'Malformed JSON body' }] }),
        });
      }
    }
    const result = await _graphqlExecute(schema, resolvers, query, variables, operationName, opts);
    return _mkResp({
      status:  200,
      headers: new Map([['Content-Type', 'application/json']]),
      body:    JSON.stringify(result),
    });
  };
}

// graphqlMount(resolvers[, opts]) — register POST + GET /graphql on the current
// server WITHOUT calling serve().  SDL comes from the registered block.
function graphqlMount(resolvers, opts) {
  if (_graphqlSdl == null) {
    throw new Error('No graphql SDL registered — add a ```graphql block before graphqlMount');
  }
  const built   = GraphQL.schema(_graphqlSdl);
  const handler = graphqlHandler(built, resolvers, opts);
  route('POST', '/graphql')(handler);
  route('GET',  '/graphql')(handler);
}

// serveGraphQL(port, resolvers[, opts][, tls]) — mount + start an HTTP server.
// The optional 3rd arg is a GraphQLOptions (security limits); for backward
// compatibility a non-options 3rd arg is treated as the `tls` config (the
// historic `serveGraphQL(port, resolvers, tls)` shape still works).
function serveGraphQL(port, resolvers, optsOrTls, tls) {
  let opts = null, tlsCfg = tls;
  if (optsOrTls && optsOrTls._type === 'GraphQLOptions') opts = optsOrTls;
  else tlsCfg = optsOrTls;
  graphqlMount(resolvers, opts);
  return serve(port, tlsCfg);
}

// graphqlQuery(url, query[, variables]) — execute a query against a remote
// GraphQL server; resolves to the `data` field as a ScalaScript Map.
// Throws if the response carries GraphQL `errors`.
async function graphqlQuery(url, query, variables) {
  let endpoint = String(url || '');
  if (endpoint.endsWith('/')) endpoint = endpoint.slice(0, -1);
  if (!endpoint.endsWith('/graphql')) endpoint = endpoint + '/graphql';
  const u       = new URL(endpoint);
  const isTls   = u.protocol === 'https:';
  const httpMod = require(isTls ? 'https' : 'http');
  const varsObj = (variables instanceof Map)
    ? Object.fromEntries(variables) : (variables || undefined);
  const payload = JSON.stringify({ query: query, variables: varsObj });
  return new Promise((resolve, reject) => {
    const reqOpts = {
      hostname: u.hostname,
      port:     u.port || (isTls ? 443 : 80),
      path:     u.pathname + u.search,
      method:   'POST',
      headers:  {
        'Content-Type':   'application/json',
        'Accept':         'application/json',
        'Content-Length': Buffer.byteLength(payload),
      },
    };
    const r = httpMod.request(reqOpts, res => {
      let buf = '';
      res.on('data', c => buf += c);
      res.on('end', () => {
        let parsed;
        try { parsed = JSON.parse(buf); }
        catch (e) { reject(new Error('graphqlQuery: invalid JSON response')); return; }
        if (parsed && parsed.errors && parsed.errors.length > 0) {
          const msg = parsed.errors.map(e => e && e.message ? e.message : String(e)).join('; ');
          reject(new Error('GraphQL error: ' + msg));
          return;
        }
        resolve(_jsonConvert(parsed ? parsed.data : null));
      });
    });
    r.on('error', e => reject(e));
    r.write(payload);
    r.end();
  });
}
"""
