package scalascript.codegen

/** v1.17 Phase 3 — browser-compatible MCP client preamble.
 *
 *  Unlike `JsRuntimeMcp` (which uses Node's `worker_threads` +
 *  `@modelcontextprotocol/sdk`), this variant runs in any browser
 *  with **no dependencies**: HTTP transport via synchronous
 *  `XMLHttpRequest`, no Workers, no SDK.
 *
 *  Scope:
 *    - **Client only.** `mcpServer { ... }` / `serveMcp(...)` raise an
 *      actionable error — a browser can't be an MCP server.
 *    - **`Transport.Http(port, path)` only.**  Stdio / Spawn / Ws are
 *      nonsensical in a browser sandbox.
 *    - **Synchronous API** via sync XHR — matches `std/mcp/client.ssc`'s
 *      `def callTool(name, args): ToolResult` signature.  Sync XHR is
 *      deprecated by browsers (blocks the main thread) but works
 *      everywhere with zero setup; an async-Promise variant can ship
 *      later as `mcpConnectAsync` once a real consumer asks.
 *
 *  Selected by the CLI's `spa` command (alongside `JsRuntimeBrowserPatch`)
 *  in place of the Node-flavoured `JsRuntimeMcp`.  Detection of MCP
 *  usage happens upstream: if a user `scalascript` block calls
 *  `mcpConnect(...)`, the SPA HTML output includes this preamble. */
val JsRuntimeMcpBrowser: String = """

// ── MCP browser client — sync XHR over HTTP ────────────────────────────────

function McpError(msg) {
  const e = new Error(msg); e.name = 'McpError'; return e;
}

// Phase 3 deliberately rejects server-side calls — a browser tab cannot
// listen on a port.  Users who reach these should switch backends.
function mcpServer(_setup) {
  throw McpError('mcpServer: not available in scalajs-spa (browser cannot host an MCP server). Use --backend jvm or interpreter.');
}
function serveMcp(_transport) {
  throw McpError('serveMcp: not available in scalajs-spa (browser cannot host an MCP server). Use --backend jvm or interpreter.');
}

let _mcpClientNextId = 1;

function _mcpHttpUrl(transport) {
  if (!transport || transport._type !== 'Http') {
    throw McpError('mcpConnect: scalajs-spa only supports Transport.Http; got ' +
      (transport && transport._type || 'unknown'));
  }
  const path = transport.path || '/mcp';
  if (/^https?:\/\//i.test(path)) return path;
  const port = transport.port || 8080;
  return (typeof location !== 'undefined' && location.origin)
    ? location.origin.replace(/:\d+$/, '') + ':' + port + path
    : 'http://localhost:' + port + path;
}

function _mcpHttpPost(url, body, timeoutMs) {
  const xhr = new XMLHttpRequest();
  // Synchronous XHR — blocks the main thread until the response arrives
  // or the timeout fires.  The browser will log a deprecation warning;
  // acceptable for v1 of Phase 3 (the alternative would be async-only
  // API which diverges from std/mcp/client.ssc).
  xhr.open('POST', url, false);
  xhr.setRequestHeader('Content-Type', 'application/json');
  xhr.setRequestHeader('Accept',       'application/json');
  // XHR's `timeout` property is ignored for synchronous requests in
  // some browsers (Firefox); fall back to whatever the user agent does.
  try { xhr.timeout = timeoutMs || 30000; } catch (_) {}
  try {
    xhr.send(body);
  } catch (e) {
    throw McpError('mcpConnect HTTP send failed: ' + (e && e.message || e));
  }
  if (xhr.status < 200 || xhr.status >= 300) {
    throw McpError('mcpConnect HTTP ' + xhr.status + ': ' + (xhr.responseText || '').slice(0, 200));
  }
  return xhr.responseText || '';
}

function _mcpRpcRequest(url, method, params, timeoutMs) {
  const id   = _mcpClientNextId++;
  const body = JSON.stringify({ jsonrpc: '2.0', method, params: params || {}, id });
  const respBody = _mcpHttpPost(url, body, timeoutMs);
  let resp;
  try { resp = JSON.parse(respBody); }
  catch (e) { throw McpError('mcpConnect: invalid JSON-RPC response: ' + (e && e.message || e)); }
  if (resp.error) throw McpError(resp.error.message || 'unknown error');
  return resp.result;
}

function _mcpRpcNotify(url, method, params) {
  // Notification — best-effort fire-and-forget.  Async XHR doesn't
  // block; we don't read the response.
  try {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', url, true);
    xhr.setRequestHeader('Content-Type', 'application/json');
    xhr.send(JSON.stringify({ jsonrpc: '2.0', method, params: params || {} }));
  } catch (_) { /* notifications swallow errors */ }
}

// ── Wire-shape adapters mirror the Node variant (_mcpDescriptorsToList,
//    _mcpResultToSsc, etc.) so the McpClient surface is identical.

function _mcpDescriptorsToListB(rpcResult, type_) {
  const arr = rpcResult && (rpcResult.tools || rpcResult.resources || rpcResult.prompts) || [];
  return arr.map(item => {
    if (type_ === 'tool')     return { _type: 'ToolDescriptor',     name: item.name, description: item.description || '', schema: item.inputSchema || {} };
    if (type_ === 'resource') return { _type: 'ResourceDescriptor', uri: item.uri, name: item.name || '', mimeType: item.mimeType || '' };
    if (type_ === 'prompt')   return {
      _type: 'PromptDescriptor', name: item.name, description: item.description || '',
      args: (item.arguments || []).map(a => ({ _type: 'ArgSpec', name: a.name, typeName: a.type || 'String', required: !!a.required }))
    };
    return item;
  });
}

function _mcpToolResultB(rpcResult) {
  const contents = (rpcResult && rpcResult.content || []).map(c => {
    if (c.type === 'image')    return { _type: 'Image',    data: c.data, mimeType: c.mimeType };
    if (c.type === 'resource') return { _type: 'Resource', uri: (c.resource && c.resource.uri) || '' };
    return { _type: 'Text', text: c.text || '' };
  });
  return { _type: 'ToolResult', content: contents, isError: !!(rpcResult && rpcResult.isError) };
}

function _mcpResourceResultB(rpcResult, uri) {
  const contents = (rpcResult && rpcResult.contents || []).map(c =>
    c.text !== undefined ? { _type: 'Text', text: c.text }
                         : { _type: 'Resource', uri: c.uri || uri }
  );
  return { _type: 'ResourceResult', uri, contents };
}

function _mcpPromptResultB(rpcResult) {
  const messages = (rpcResult && rpcResult.messages || []).map(m => {
    const role = m.role === 'assistant' ? { _type: 'Assistant' }
               : m.role === 'system'    ? { _type: 'System' }
               : { _type: 'User' };
    const content = m.content && m.content.type === 'image'
      ? { _type: 'Image', data: m.content.data, mimeType: m.content.mimeType }
      : { _type: 'Text', text: m.content && m.content.text || '' };
    return { _type: 'Message', role, content };
  });
  return { _type: 'PromptResult', messages };
}

function _mcpArgsToObj(args) {
  if (args instanceof Map) return Object.fromEntries(args);
  if (args && typeof args === 'object') return args;
  return {};
}

function mcpConnect(transport, timeoutMs) {
  const url = _mcpHttpUrl(transport);
  const tms = timeoutMs || 30000;
  // Initialize handshake — fail loudly if the server isn't reachable
  // (clearer than failing on the first callTool).
  _mcpRpcRequest(url, 'initialize', {
    protocolVersion: '2024-11-05',
    capabilities:    {},
    clientInfo:      { name: 'ssc-scalajs-spa', version: '1.0.0' }
  }, tms);
  _mcpRpcNotify(url, 'notifications/initialized', {});

  let _closed = false;
  let _es     = null;   // active EventSource for SSE notifications
  let _notif  = null;   // user's onNotification handler
  let _onReq  = null;   // user's onRequest handler (bidirectional sampling)
  function _guard() { if (_closed) throw McpError('McpClient is closed'); }

  // Lazily open one EventSource per McpClient that dispatches both
  // Notification frames (no id) and Request frames (with id; POST
  // the response back to the same `/mcp` URL — server's
  // handleHttpRequest routes it through routeInboundResponse so
  // srv.request unblocks).  Idempotent across multiple set* calls.
  function _ensureES() {
    if (_es || typeof EventSource === 'undefined') return;
    const es = new EventSource(url + '/events');
    es.onmessage = (ev) => {
      let frame;
      try { frame = JSON.parse(ev.data); } catch (_) { return; }
      if (!frame || !frame.method) return;
      if (frame.id === undefined) {
        if (_notif) try { _notif(frame.method, frame.params || {}); } catch (_) {}
      } else {
        let resp;
        try {
          if (_onReq) {
            const result = _onReq(frame.method, frame.params || {});
            resp = { jsonrpc: '2.0', id: frame.id, result: result };
          } else {
            resp = { jsonrpc: '2.0', id: frame.id,
                     error: { code: -32601, message: 'client has no handler for server-initiated method: ' + frame.method } };
          }
        } catch (e) {
          resp = { jsonrpc: '2.0', id: frame.id,
                   error: { code: -32603, message: (e && e.message) || String(e) } };
        }
        try {
          const xhr = new XMLHttpRequest();
          xhr.open('POST', url, true);
          xhr.setRequestHeader('Content-Type', 'application/json');
          xhr.send(JSON.stringify(resp));
        } catch (_) {}
      }
    };
    es.onerror = () => {};
    _es = es;
  }

  return {
    _type: 'McpClient',
    listTools:     () => { _guard(); return _mcpDescriptorsToListB(_mcpRpcRequest(url, 'tools/list',     {},                                                           tms), 'tool');     },
    listResources: () => { _guard(); return _mcpDescriptorsToListB(_mcpRpcRequest(url, 'resources/list', {},                                                           tms), 'resource'); },
    listPrompts:   () => { _guard(); return _mcpDescriptorsToListB(_mcpRpcRequest(url, 'prompts/list',   {},                                                           tms), 'prompt');   },
    callTool:      (name, args) => { _guard(); return _mcpToolResultB    (_mcpRpcRequest(url, 'tools/call',     { name, arguments: _mcpArgsToObj(args) },               tms));            },
    readResource:  (uri)        => { _guard(); return _mcpResourceResultB(_mcpRpcRequest(url, 'resources/read', { uri },                                                tms), uri);        },
    getPrompt:     (name, args) => { _guard(); return _mcpPromptResultB  (_mcpRpcRequest(url, 'prompts/get',    { name, arguments: _mcpArgsToObj(args) },               tms));            },
    onNotification: (handler) => { _notif = handler; _ensureES(); },
    onRequest:      (handler) => { _onReq = handler; _ensureES(); },
    close: () => { _closed = true; if (_es) { try { _es.close(); } catch (_) {} _es = null; } },
    isClosed: () => _closed
  };
}

// ── Async / Promise-based variant ──────────────────────────────────────────
//
// `mcpConnectAsync(transport, timeoutMs)` returns a `Promise<McpClientAsync>`
// whose methods (`listTools`, `callTool`, ...) all return Promises.  Uses
// `fetch()` instead of sync XHR — doesn't block the main thread, which is
// what production browser UIs actually want.
//
// API divergence from std/mcp/client.ssc: every method returns a Promise.
// Users writing scalajs-spa code typically already work with Promises (DOM
// events, fetch, etc.), so this is a natural fit.  Caller-side: chain
// `.then()` / `.catch()` or use Scala.js's `Future` interop.

async function _mcpRpcRequestAsync(url, method, params, timeoutMs) {
  const id   = _mcpClientNextId++;
  const body = JSON.stringify({ jsonrpc: '2.0', method, params: params || {}, id });
  const ctrl = (typeof AbortController !== 'undefined') ? new AbortController() : null;
  const timer = (ctrl && timeoutMs)
    ? setTimeout(() => { try { ctrl.abort(); } catch (_) {} }, timeoutMs)
    : null;
  let resp;
  try {
    resp = await fetch(url, {
      method:  'POST',
      headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
      body,
      signal:  ctrl ? ctrl.signal : undefined
    });
  } catch (e) {
    throw McpError('mcpConnectAsync: fetch failed: ' + (e && e.message || e));
  } finally {
    if (timer) clearTimeout(timer);
  }
  if (!resp.ok) {
    const txt = await resp.text().catch(() => '');
    throw McpError('mcpConnectAsync HTTP ' + resp.status + ': ' + txt.slice(0, 200));
  }
  const text = await resp.text();
  let json;
  try { json = JSON.parse(text); }
  catch (e) { throw McpError('mcpConnectAsync: invalid JSON-RPC response: ' + (e && e.message || e)); }
  if (json.error) throw McpError(json.error.message || 'unknown error');
  return json.result;
}

function _mcpRpcNotifyAsync(url, method, params) {
  try {
    // Fire-and-forget; ignore the returned promise so the caller doesn't
    // have to await notification-style sends.
    fetch(url, {
      method:  'POST',
      headers: { 'Content-Type': 'application/json' },
      body:    JSON.stringify({ jsonrpc: '2.0', method, params: params || {} })
    }).catch(() => {});
  } catch (_) {}
}

async function mcpConnectAsync(transport, timeoutMs) {
  const url = _mcpHttpUrl(transport);
  const tms = timeoutMs || 30000;
  await _mcpRpcRequestAsync(url, 'initialize', {
    protocolVersion: '2024-11-05',
    capabilities:    {},
    clientInfo:      { name: 'ssc-scalajs-spa-async', version: '1.0.0' }
  }, tms);
  _mcpRpcNotifyAsync(url, 'notifications/initialized', {});

  let _closed = false;
  let _esA    = null;  // EventSource
  let _notifA = null;  // handler
  let _onReqA = null;  // request handler (bidirectional sampling)
  function _guardA() {
    if (_closed) return Promise.reject(McpError('McpClient is closed'));
    return null;
  }

  // Lazily open one EventSource per McpClientAsync; same dispatch
  // shape as the sync variant — notifications fan out to _notifA;
  // requests invoke _onReqA, the result is POSTed back via fetch.
  function _ensureESA() {
    if (_esA || typeof EventSource === 'undefined') return;
    const es = new EventSource(url + '/events');
    es.onmessage = (ev) => {
      let frame;
      try { frame = JSON.parse(ev.data); } catch (_) { return; }
      if (!frame || !frame.method) return;
      if (frame.id === undefined) {
        if (_notifA) try { _notifA(frame.method, frame.params || {}); } catch (_) {}
      } else {
        // Request — handler may return a Promise; await it.  Result or
        // error becomes a Response frame fetch-POSTed back to /mcp.
        Promise.resolve()
          .then(() => _onReqA ? _onReqA(frame.method, frame.params || {})
                              : Promise.reject(new Error('client has no handler for server-initiated method: ' + frame.method)))
          .then(result => ({ jsonrpc: '2.0', id: frame.id, result: result }))
          .catch(e => ({ jsonrpc: '2.0', id: frame.id,
                         error: { code: _onReqA ? -32603 : -32601,
                                  message: (e && e.message) || String(e) } }))
          .then(resp => fetch(url, {
            method:  'POST',
            headers: { 'Content-Type': 'application/json' },
            body:    JSON.stringify(resp)
          }).catch(() => {}));
      }
    };
    es.onerror = () => {};
    _esA = es;
  }

  return {
    _type: 'McpClientAsync',
    listTools:     async () => {
      const g = _guardA(); if (g) return g;
      return _mcpDescriptorsToListB(await _mcpRpcRequestAsync(url, 'tools/list',     {}, tms), 'tool');
    },
    listResources: async () => {
      const g = _guardA(); if (g) return g;
      return _mcpDescriptorsToListB(await _mcpRpcRequestAsync(url, 'resources/list', {}, tms), 'resource');
    },
    listPrompts:   async () => {
      const g = _guardA(); if (g) return g;
      return _mcpDescriptorsToListB(await _mcpRpcRequestAsync(url, 'prompts/list',   {}, tms), 'prompt');
    },
    callTool: async (name, args) => {
      const g = _guardA(); if (g) return g;
      return _mcpToolResultB(await _mcpRpcRequestAsync(url, 'tools/call', { name, arguments: _mcpArgsToObj(args) }, tms));
    },
    readResource: async (uri) => {
      const g = _guardA(); if (g) return g;
      return _mcpResourceResultB(await _mcpRpcRequestAsync(url, 'resources/read', { uri }, tms), uri);
    },
    getPrompt: async (name, args) => {
      const g = _guardA(); if (g) return g;
      return _mcpPromptResultB(await _mcpRpcRequestAsync(url, 'prompts/get', { name, arguments: _mcpArgsToObj(args) }, tms));
    },
    onNotification: (handler) => { _notifA = handler; _ensureESA(); },
    onRequest:      (handler) => { _onReqA = handler; _ensureESA(); },
    close:    () => { _closed = true; if (_esA) { try { _esA.close(); } catch (_) {} _esA = null; } },
    isClosed: () => _closed
  };
}
"""
