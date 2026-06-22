
// ── GraphQL runtime (Node target, 'graphql' npm package) ─────────────────────

// SDL registered by a ```graphql fenced block earlier in the document.
// At most one per module — the last block wins (parallel to sql).
let _graphqlSdl = null;
function _registerGraphqlSdl(sdl) { _graphqlSdl = sdl; }

// Normalise a resolver collection (a ScalaScript Map, a plain object, or
// undefined) into a flat coordinate → function table.
function _graphqlToTable(m) {
  const o = {};
  if (_isMap(m)) { for (const [k, v] of m) o[k] = v; }
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
  // GraphQL.entityResolvers(entities) — Apollo Federation v2 entity resolver
  // map, passed as the (optional) entity-resolvers arg to `serveSubgraph` /
  // `graphqlSubgraphMount`.  `entities : Map[typeName, representation => Value]`;
  // each resolver receives a representation Map (`__typename` + the `@key`
  // fields) and returns the resolved entity.  Mirrors the JVM intrinsic.
  entityResolvers: function(entities) {
    return { _type: 'GraphQLFederationEntities', entities: _graphqlToTable(entities) };
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

// Normalise a subscription resolver's return value into an async iterator of
// payload objects shaped `{ <fieldName>: item }`.  Mirrors the JVM backend,
// where a subscription resolver returns a List (or single value) that is
// wrapped in a Reactive-Streams Publisher emitting one event per element:
//   - an Array (ScalaScript List) emits one event per element;
//   - an async iterable / generator streams its elements lazily;
//   - a sync iterable streams its elements;
//   - any other (scalar / Map / object) value emits a single event.
// graphql-js executes each yielded payload as the root value, so the field's
// `resolve` (set in `_graphqlApplySubscriptions`) reads `payload[fieldName]`.
async function* _graphqlToAsyncIterable(result, fieldName) {
  let r = result;
  if (r && typeof r.then === 'function') r = await r;
  if (r != null && typeof r[Symbol.asyncIterator] === 'function') {
    for await (const item of r) yield { [fieldName]: item };
    return;
  }
  if (r != null && typeof r !== 'string' && typeof r[Symbol.iterator] === 'function') {
    for (const item of r) yield { [fieldName]: item };
    return;
  }
  yield { [fieldName]: r };
}

// Wire subscription resolvers onto the built schema's Subscription type.
// graphql-js' `subscribe` drives each field's `subscribe` to obtain a source
// event stream (an AsyncIterable) and then runs a normal execution per event;
// the field's `resolve` projects the per-event payload.  We mutate the field
// objects in place (same approach as `_graphqlApplyScalars`).  Resolver keys
// may be plain (`"messages"`) or coordinates (`"Subscription.messages"`); the
// argument Map + DataLoader injection match `_graphqlFieldResolver`.
function _graphqlApplySubscriptions(schema, resolvers, g) {
  const subs = (resolvers && resolvers.subscription) || {};
  if (Object.keys(subs).length === 0) return;
  const subType = schema.getSubscriptionType && schema.getSubscriptionType();
  if (!subType) return;
  const fields = subType.getFields();
  for (const fieldName of Object.keys(fields)) {
    const fn = subs['Subscription.' + fieldName] || subs[fieldName];
    if (typeof fn !== 'function') continue;
    const field = fields[fieldName];
    field.subscribe = function(source, args, context, info) {
      const m = new Map();
      const a = args || {};
      for (const k of Object.keys(a)) m.set(k, a[k]);
      const dl = context && context._dlCtx;
      if (dl) {
        m.set('_load',      function(ln, key)  { return dl.load(ln, key); });
        m.set('_batchLoad', function(ln, keys) { return dl.batchLoad(ln, keys); });
      }
      return _graphqlToAsyncIterable(fn(m), fieldName);
    };
    field.resolve = function(payload) {
      return (payload && typeof payload === 'object') ? payload[fieldName] : payload;
    };
  }
}

// Run a subscription operation through graphql-js' `subscribe`, applying the
// same validation rules (incl. depth/complexity/introspection limits) as the
// query/mutation path.  Returns either { iterable } (an AsyncIterable of
// ExecutionResults), or { errors } (validation / parse / not-a-subscription).
async function _graphqlRunSubscription(built, resolvers, query, variables, operationName, opts, g) {
  let document;
  try { document = g.parse(query); }
  catch (e) { return { errors: [{ message: e.message }] }; }
  opts = opts || {};
  const rules = g.specifiedRules.concat(_graphqlLimitRules(opts, g));
  const verrs = g.validate(built.schema, document, rules);
  if (verrs && verrs.length > 0) return { errors: verrs.map(e => ({ message: e.message })) };
  const dlCtx = (resolvers.loaders && Object.keys(resolvers.loaders).length > 0)
    ? new _GraphqlDataLoaderCtx(resolvers.loaders) : null;
  const contextValue  = { _dlCtx: dlCtx };
  const fieldResolver = _graphqlFieldResolver(resolvers, g);
  let res;
  try {
    res = await g.subscribe({
      schema:         built.schema,
      document:       document,
      rootValue:      {},
      contextValue:   contextValue,
      variableValues: variables || undefined,
      operationName:  operationName || undefined,
      fieldResolver:  fieldResolver,
    });
  } catch (e) { return { errors: [{ message: e.message }] }; }
  if (res && typeof res[Symbol.asyncIterator] === 'function') return { iterable: res };
  // A single ExecutionResult (e.g. not a subscription, or an execution error).
  if (res && res.errors && res.errors.length > 0) {
    return { errors: res.errors.map(e => ({ message: e.message })) };
  }
  return { errors: [{ message: 'Not a subscription operation' }] };
}

// True if the operation document's selected (or sole) operation is a
// subscription.  Used by `graphqlHandler` to route an SSE request to the
// subscription pipeline rather than a one-shot execution.
function _graphqlIsSubscription(query) {
  return /(^|\})\s*subscription\b/.test(String(query || ''));
}

// graphql-transport-ws connection handler (the protocol behind graphql-ws /
// Apollo subscriptions).  `ws` is the object handed to an `onWebSocket` block
// (`send` / `onMessage` / `onClose`).  Implements the message types this
// runtime needs: connection_init→connection_ack, subscribe→next*/complete|error,
// complete (client cancel), ping→pong.  Mirrors the JVM `handleWsMessage`. */
function _graphqlHandleWsConnection(ws, built, resolvers, opts, g) {
  let initReceived = false;
  const active = new Map(); // id -> { cancelled }
  const send = function(o) { try { ws.send(JSON.stringify(o)); } catch (e) {} };
  ws.onMessage(function(text) {
    let msg;
    try { msg = JSON.parse(text); } catch (e) { return; }
    if (!msg || typeof msg.type !== 'string') return;
    const id = typeof msg.id === 'string' ? msg.id : '';
    switch (msg.type) {
      case 'connection_init':
        initReceived = true;
        send({ type: 'connection_ack' });
        return;
      case 'ping':
        send({ type: 'pong', payload: msg.payload });
        return;
      case 'pong':
        return;
      case 'complete': {
        const s = active.get(id);
        if (s) { s.cancelled = true; active.delete(id); }
        return;
      }
      case 'subscribe': {
        if (!initReceived) {
          send({ type: 'error', id: id, payload: [{ message: 'Connection not initialised' }] });
          return;
        }
        const payload = msg.payload || {};
        const query   = payload.query || '';
        if (!query) { send({ type: 'error', id: id, payload: [{ message: 'Missing query' }] }); return; }
        const variables     = payload.variables || null;
        const operationName  = payload.operationName || null;
        const sub = { cancelled: false };
        active.set(id, sub);
        (async function() {
          const run = await _graphqlRunSubscription(built, resolvers, query, variables, operationName, opts, g);
          if (sub.cancelled) { active.delete(id); return; }
          if (run.errors) {
            send({ type: 'error', id: id, payload: run.errors });
            active.delete(id);
            return;
          }
          try {
            for await (const er of run.iterable) {
              if (sub.cancelled) break;
              const p = {};
              if (er.data !== undefined) p.data = er.data;
              if (er.errors && er.errors.length > 0) p.errors = er.errors.map(e => ({ message: e.message }));
              send({ type: 'next', id: id, payload: p });
            }
            if (!sub.cancelled) send({ type: 'complete', id: id });
          } catch (e) {
            if (!sub.cancelled) send({ type: 'error', id: id, payload: [{ message: e.message }] });
          } finally {
            active.delete(id);
          }
        })();
        return;
      }
      default:
        return; // unknown type — ignore (spec §4.5)
    }
  });
  ws.onClose(function() {
    for (const s of active.values()) s.cancelled = true;
    active.clear();
  });
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
    if (_isMap(res)) {
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
  const fill = res => { if (_isMap(res)) for (const [k, v] of res) c.set(k, v); return collect(); };
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
    if (_isMap(source)) {
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
  const g = require('graphql');
  // Wire any custom scalars + subscription source streams into the executable
  // schema once, at handler construction (not per-request).
  _graphqlApplyScalars(schema.schema, resolvers, g);
  _graphqlApplySubscriptions(schema.schema, resolvers, g);
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
      const q = (req && _isMap(req.query)) ? req.query : new Map();
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
    // GraphQL-over-SSE (single-connection mode): when the client sends
    // `Accept: text/event-stream` for a subscription operation, drain the
    // event stream into a buffered `text/event-stream` body (one `data:` frame
    // per event).  Matches the JVM backend's `handleSseResult` — suited to
    // finite / bounded subscriptions.
    const accept = (req && _isMap(req.headers))
      ? (req.headers.get('accept') || req.headers.get('Accept') || '') : '';
    if (accept.indexOf('text/event-stream') >= 0 && _graphqlIsSubscription(query)) {
      const run = await _graphqlRunSubscription(schema, resolvers, query, variables, operationName, opts, g);
      let body = '';
      if (run.errors) {
        body += 'data: ' + JSON.stringify({ errors: run.errors }) + '\n\n';
      } else {
        try {
          for await (const er of run.iterable) {
            const p = {};
            p.data = er.data !== undefined ? er.data : null;
            if (er.errors && er.errors.length > 0) p.errors = er.errors.map(e => ({ message: e.message }));
            body += 'data: ' + JSON.stringify(p) + '\n\n';
          }
        } catch (e) {
          body += 'data: ' + JSON.stringify({ errors: [{ message: e.message }] }) + '\n\n';
        }
      }
      return _mkResp({
        status:  200,
        headers: new Map([['Content-Type', 'text/event-stream'], ['Cache-Control', 'no-cache']]),
        body:    body,
      });
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
  _ssc_http_route('POST', '/graphql')(handler);
  _ssc_http_route('GET',  '/graphql')(handler);
  // graphql-transport-ws subscriptions: only mount the upgrade route when the
  // resolver set actually declares subscriptions.  The schema's subscription
  // source streams are already wired by `graphqlHandler` (shared built schema).
  if (resolvers && resolvers.subscription && Object.keys(resolvers.subscription).length > 0) {
    const g = require('graphql');
    onWebSocket('/graphql/ws', [], ['graphql-transport-ws'])(function(ws) {
      _graphqlHandleWsConnection(ws, built, resolvers, opts || {}, g);
    });
  }
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

// ── Apollo Federation v2 subgraph support ────────────────────────────────────

// Federation v2 scalars + directive definitions + the `_Service` type.  These
// must be present in the SDL handed to `buildSchema` so `@key` / `@external` /
// etc. annotations on user types parse cleanly.  Mirrors the JVM preamble.
const _GRAPHQL_FED_PREAMBLE =
  'scalar _Any\n' +
  'scalar _FieldSet\n' +
  '\n' +
  'directive @key(fields: _FieldSet!, resolvable: Boolean) repeatable on OBJECT | INTERFACE\n' +
  'directive @external on FIELD_DEFINITION | OBJECT\n' +
  'directive @requires(fields: _FieldSet!) on FIELD_DEFINITION\n' +
  'directive @provides(fields: _FieldSet!) on FIELD_DEFINITION\n' +
  'directive @shareable repeatable on FIELD_DEFINITION | OBJECT\n' +
  'directive @inaccessible on FIELD_DEFINITION | OBJECT | INTERFACE | UNION | ARGUMENT_DEFINITION | SCALAR | ENUM | ENUM_VALUE | INPUT_OBJECT | INPUT_FIELD_DEFINITION\n' +
  'directive @override(from: String!) on FIELD_DEFINITION\n' +
  'directive @tag(name: String!) repeatable on FIELD_DEFINITION | OBJECT | INTERFACE | UNION | ARGUMENT_DEFINITION | SCALAR | ENUM | ENUM_VALUE | INPUT_OBJECT | INPUT_FIELD_DEFINITION | SCHEMA\n' +
  '\n' +
  'type _Service {\n  sdl: String!\n}\n';

// Assemble the federated SDL: preamble + user SDL + the `_service` query
// extension, and (when entity types are present) the `_Entity` union and the
// `_entities` query extension.  Matches the JVM `buildFederationSdl`.
function _graphqlBuildFederationSdl(userSdl, entityTypeNames) {
  let sdl = _GRAPHQL_FED_PREAMBLE + userSdl + '\nextend type Query {\n  _service: _Service!\n}\n';
  if (entityTypeNames.length > 0) {
    const union = entityTypeNames.slice().sort().join(' | ');
    sdl += '\nunion _Entity = ' + union + '\n';
    sdl += 'extend type Query {\n  _entities(representations: [_Any!]!): [_Entity]!\n}\n';
  }
  return sdl;
}

// Read `__typename` off a resolved entity, whether it's a ScalaScript Map or a
// plain object — used by the `_Entity` union's resolveType and the `_entities`
// representation dispatch.
function _graphqlTypename(obj) {
  if (_isMap(obj)) return obj.get('__typename');
  if (obj && typeof obj === 'object') return obj.__typename;
  return undefined;
}

// Register the `_Entity` union's type resolver on a built federated schema so
// graphql-js can pick the concrete object type for each resolved entity by its
// `__typename`.  Mirrors the JVM `_Entity` TypeResolver.
function _graphqlApplyEntityUnion(schema, g) {
  const t = schema.getType('_Entity');
  if (t) t.resolveType = function(obj) { return _graphqlTypename(obj) || null; };
}

// graphqlSubgraphMount(resolvers[, entityResolvers][, opts]) — like
// `graphqlMount` but adds Federation v2 subgraph support: a `_service { sdl }`
// field returning the *user* SDL and (when entity resolvers are supplied) an
// `_entities(representations:)` resolver dispatching each representation to the
// matching entity resolver by `__typename`.  The 2nd/3rd args are discriminated
// by `_type` (`GraphQLFederationEntities` vs `GraphQLOptions`).
function graphqlSubgraphMount(resolvers, a, b) {
  if (_graphqlSdl == null) {
    throw new Error('No graphql SDL registered — add a ```graphql block before graphqlSubgraphMount');
  }
  let entities = {}, opts = null;
  for (const arg of [a, b]) {
    if (arg && arg._type === 'GraphQLFederationEntities') entities = arg.entities || {};
    else if (arg && arg._type === 'GraphQLOptions')        opts = arg;
  }
  const userSdl   = _graphqlSdl;
  const entityKeys = Object.keys(entities);
  const fedSdl    = _graphqlBuildFederationSdl(userSdl, entityKeys);

  // Inject _service (+ _entities) into the query resolver table.
  const serviceResolver = function() { return new Map([['sdl', userSdl]]); };
  const extraQuery = { '_service': serviceResolver };
  if (entityKeys.length > 0) {
    extraQuery['_entities'] = function(args) {
      const reps = _isMap(args) ? args.get('representations') : null;
      const list = Array.isArray(reps) ? reps : [];
      return list.map(function(rep) {
        // Representations arrive as plain `_Any` objects; convert to a
        // ScalaScript Map so the entity resolver can index `rep("id")`.
        const m = _jsonConvert(rep);
        const typeName = _graphqlTypename(m);
        const fn = entities[typeName];
        return (typeof fn === 'function') ? fn(m) : null;
      });
    };
  }
  const fedResolvers = GraphQL.resolvers({
    query:        Object.assign({}, resolvers.query, extraQuery),
    mutation:     resolvers.mutation,
    subscription: resolvers.subscription,
    scalars:      resolvers.scalars,
    loaders:      resolvers.loaders,
  });

  // Build the federated schema, wire the _Entity union resolver, and reuse the
  // standard handler (scalars / subscriptions wiring happen inside it).
  const built = GraphQL.schema(fedSdl);
  _graphqlApplyEntityUnion(built.schema, require('graphql'));
  const handler = graphqlHandler(built, fedResolvers, opts);
  _ssc_http_route('POST', '/graphql')(handler);
  _ssc_http_route('GET',  '/graphql')(handler);
}

// serveSubgraph(port, resolvers[, entityResolvers][, opts][, tls]) — mount a
// Federation v2 subgraph + start an HTTP server.  Like `serveGraphQL`, a 3rd/4th
// arg that is neither a GraphQLFederationEntities nor a GraphQLOptions is treated
// as the `tls` config.
function serveSubgraph(port, resolvers, a, b, tls) {
  let tlsCfg = tls;
  for (const arg of [a, b]) {
    if (arg && (arg._type === 'GraphQLFederationEntities' || arg._type === 'GraphQLOptions')) continue;
    if (arg !== undefined && arg !== null && tlsCfg === undefined) tlsCfg = arg;
  }
  graphqlSubgraphMount(resolvers, a, b);
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
  const varsObj = (_isMap(variables))
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

// graphqlSse(url, query[, variables]) — POST a subscription operation with
// `Accept: text/event-stream` and collect the streamed events.  Resolves to a
// ScalaScript List of each event's `data` value (mirrors the JVM client which
// returns the parsed event payloads).  Suited to finite / bounded streams.
async function graphqlSse(url, query, variables) {
  let endpoint = String(url || '');
  if (endpoint.endsWith('/')) endpoint = endpoint.slice(0, -1);
  if (!endpoint.endsWith('/graphql')) endpoint = endpoint + '/graphql';
  const u       = new URL(endpoint);
  const isTls   = u.protocol === 'https:';
  const httpMod = require(isTls ? 'https' : 'http');
  const varsObj = (_isMap(variables)) ? Object.fromEntries(variables) : (variables || undefined);
  const payload = JSON.stringify({ query: query, variables: varsObj });
  return new Promise((resolve, reject) => {
    const reqOpts = {
      hostname: u.hostname,
      port:     u.port || (isTls ? 443 : 80),
      path:     u.pathname + u.search,
      method:   'POST',
      headers:  {
        'Content-Type':   'application/json',
        'Accept':         'text/event-stream',
        'Content-Length': Buffer.byteLength(payload),
      },
    };
    const r = httpMod.request(reqOpts, res => {
      let buf = '';
      res.on('data', c => buf += c);
      res.on('end', () => {
        const events = [];
        for (const block of buf.split('\n\n')) {
          const dataLines = block.split('\n')
            .filter(l => l.indexOf('data:') === 0)
            .map(l => l.slice(5).trim());
          if (dataLines.length === 0) continue;
          let parsed;
          try { parsed = JSON.parse(dataLines.join('\n')); } catch (e) { continue; }
          events.push(_jsonConvert(parsed && parsed.data !== undefined ? parsed.data : parsed));
        }
        resolve(events);
      });
    });
    r.on('error', e => reject(e));
    r.write(payload);
    r.end();
  });
}

// graphqlSubscribe(url, query[, variables], handler) — open a graphql-transport-ws
// WebSocket to `<url>/graphql/ws`, run the subscription, and invoke
// `handler(payload)` (a ScalaScript fn) for each `next` event's data.  Resolves
// when the server sends `complete`; rejects on `error`.  Implemented over a raw
// masked RFC-6455 client (no external dependency) so it works against this
// runtime's own `serveGraphQL` subscription endpoint.
async function graphqlSubscribe(url, query, variablesOrHandler, handlerArg) {
  let variables = null, handler = handlerArg;
  if (typeof variablesOrHandler === 'function') { handler = variablesOrHandler; }
  else { variables = variablesOrHandler; }
  let endpoint = String(url || '');
  if (endpoint.endsWith('/')) endpoint = endpoint.slice(0, -1);
  if (endpoint.endsWith('/graphql')) endpoint = endpoint + '/ws';
  else if (!endpoint.endsWith('/graphql/ws')) endpoint = endpoint + '/graphql/ws';
  const u      = new URL(endpoint);
  const isTls  = u.protocol === 'wss:' || u.protocol === 'https:';
  const net    = require(isTls ? 'tls' : 'net');
  const crypto = require('crypto');
  const varsObj = (_isMap(variables)) ? Object.fromEntries(variables) : (variables || undefined);

  // RFC-6455 client framing: client→server frames MUST be masked.
  const encode = function(str) {
    const data = Buffer.from(str, 'utf-8');
    const len  = data.length;
    let header;
    if (len < 126)        { header = Buffer.alloc(2); header[1] = 0x80 | len; }
    else if (len < 65536) { header = Buffer.alloc(4); header[1] = 0x80 | 126; header.writeUInt16BE(len, 2); }
    else                  { header = Buffer.alloc(10); header[1] = 0x80 | 127; header.writeUInt32BE(0, 2); header.writeUInt32BE(len, 6); }
    header[0] = 0x81; // FIN + text
    const mask = crypto.randomBytes(4);
    const masked = Buffer.alloc(len);
    for (let i = 0; i < len; i++) masked[i] = data[i] ^ mask[i & 3];
    return Buffer.concat([header, mask, masked]);
  };

  return new Promise((resolve, reject) => {
    const key = crypto.randomBytes(16).toString('base64');
    const port = u.port || (isTls ? 443 : 80);
    const socket = net.connect(isTls ? { host: u.hostname, port, servername: u.hostname } : { host: u.hostname, port }, () => {
      socket.write(
        'GET ' + (u.pathname || '/graphql/ws') + ' HTTP/1.1\r\n' +
        'Host: ' + u.hostname + ':' + port + '\r\n' +
        'Upgrade: websocket\r\n' +
        'Connection: Upgrade\r\n' +
        'Sec-WebSocket-Key: ' + key + '\r\n' +
        'Sec-WebSocket-Version: 13\r\n' +
        'Sec-WebSocket-Protocol: graphql-transport-ws\r\n\r\n'
      );
    });
    const send = o => socket.write(encode(JSON.stringify(o)));
    let upgraded = false, settled = false, recv = Buffer.alloc(0);
    const done = (fn, arg) => { if (!settled) { settled = true; try { socket.end(); } catch (e) {} fn(arg); } };

    const onMessage = text => {
      let msg;
      try { msg = JSON.parse(text); } catch (e) { return; }
      switch (msg && msg.type) {
        case 'connection_ack':
          send({ id: '1', type: 'subscribe', payload: { query: query, variables: varsObj } });
          break;
        case 'next': {
          const data = msg.payload && msg.payload.data !== undefined ? msg.payload.data : msg.payload;
          if (typeof handler === 'function') { try { _call(handler, _jsonConvert(data)); } catch (e) {} }
          break;
        }
        case 'complete': done(resolve, undefined); break;
        case 'error': {
          const errs = Array.isArray(msg.payload) ? msg.payload : [msg.payload];
          const text = errs.map(e => e && e.message ? e.message : String(e)).join('; ');
          done(reject, new Error('GraphQL subscription error: ' + text));
          break;
        }
        default: break;
      }
    };

    // Parse server→client frames (unmasked).  Handles text/close opcodes and
    // the small/medium/large payload-length encodings.
    const drain = () => {
      while (recv.length >= 2) {
        const opcode = recv[0] & 0x0f;
        let len = recv[1] & 0x7f, off = 2;
        if (len === 126)      { if (recv.length < 4) return; len = recv.readUInt16BE(2); off = 4; }
        else if (len === 127) { if (recv.length < 10) return; len = recv.readUInt32BE(6); off = 10; }
        if (recv.length < off + len) return;
        const payload = recv.slice(off, off + len);
        recv = recv.slice(off + len);
        if (opcode === 0x8) { done(resolve, undefined); return; } // close
        if (opcode === 0x1 || opcode === 0x0) onMessage(payload.toString('utf-8'));
      }
    };

    socket.on('data', chunk => {
      if (!upgraded) {
        recv = Buffer.concat([recv, chunk]);
        const idx = recv.indexOf('\r\n\r\n');
        if (idx < 0) return;
        const head = recv.slice(0, idx).toString('latin1');
        if (!/HTTP\/1\.1 101/.test(head)) { done(reject, new Error('graphqlSubscribe: upgrade failed')); return; }
        upgraded = true;
        recv = recv.slice(idx + 4);
        send({ type: 'connection_init' });
        drain();
      } else {
        recv = Buffer.concat([recv, chunk]);
        drain();
      }
    });
    socket.on('error', e => done(reject, e));
    socket.on('close', () => done(resolve, undefined));
  });
}
