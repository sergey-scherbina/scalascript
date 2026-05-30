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
 *      property resolution from the parent object;
 *    - resolvers may be async (return a Promise) — graphql-js awaits them.
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
  // subscription } (named-arg call sites are lowered to an options object
  // by dispatchIntrinsicJs).
  resolvers: function(opts) {
    opts = opts || {};
    return {
      _type:        'GraphQLResolvers',
      query:        _graphqlToTable(opts.query),
      mutation:     _graphqlToTable(opts.mutation),
      subscription: _graphqlToTable(opts.subscription),
    };
  },
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
// Returns a Promise of the { data, errors } execution result object.
async function _graphqlExecute(built, resolvers, query, variables, operationName) {
  const g = require('graphql');
  return g.graphql({
    schema:         built.schema,
    source:         query,
    rootValue:      {},
    contextValue:   {},
    variableValues: variables || undefined,
    operationName:  operationName || undefined,
    fieldResolver:  _graphqlFieldResolver(resolvers, g),
  });
}

// Detect whether a query document contains a top-level mutation operation
// (used to reject mutations over GET per GraphQL-over-HTTP §6.2.2).
function _graphqlIsMutation(query) {
  return /(^|\})\s*mutation\b/.test(String(query || ''));
}

// graphqlHandler(schema, resolvers) — return a Request => Promise<Response>
// handler suitable for manual `route(...)` wiring.
function graphqlHandler(schema, resolvers) {
  return async function(req) {
    const method = (req && req.method ? String(req.method) : 'POST').toUpperCase();
    let query = null, variables = null, operationName = null;
    if (method === 'GET') {
      const q = (req && req.query instanceof Map) ? req.query : new Map();
      query = q.get('query');
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
    const result = await _graphqlExecute(schema, resolvers, query, variables, operationName);
    return _mkResp({
      status:  200,
      headers: new Map([['Content-Type', 'application/json']]),
      body:    JSON.stringify(result),
    });
  };
}

// graphqlMount(resolvers) — register POST + GET /graphql on the current
// server WITHOUT calling serve().  SDL comes from the registered block.
function graphqlMount(resolvers) {
  if (_graphqlSdl == null) {
    throw new Error('No graphql SDL registered — add a ```graphql block before graphqlMount');
  }
  const built   = GraphQL.schema(_graphqlSdl);
  const handler = graphqlHandler(built, resolvers);
  route('POST', '/graphql')(handler);
  route('GET',  '/graphql')(handler);
}

// serveGraphQL(port, resolvers[, tls]) — mount + start an HTTP server.
function serveGraphQL(port, resolvers, tls) {
  graphqlMount(resolvers);
  return serve(port, tls);
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
