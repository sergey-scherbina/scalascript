package scalascript.codegen

/** MCP (Model Context Protocol) JavaScript runtime preamble.
 *
 *  Wraps `@modelcontextprotocol/sdk` (npm) for both server and client.
 *  Server side: `mcpServer(setup)` + `serveMcp(transport)` — same shape
 *  as `route(method, path)(handler)` + `serve(port)`.
 *  Client side: `mcpConnect(transport)` returns a synchronous McpClient
 *  backed by a Worker thread + Atomics.wait bridge (same pattern as the
 *  OAuth sync-fetch bridge in JsRuntimePart1c). */
val JsRuntimeMcp: String = """

// ── MCP server ─────────────────────────────────────────────────────────────
//
// _mcpTools / _mcpResources / _mcpPrompts accumulate registrations from
// mcpServer { srv => srv.tool(...) } calls before serveMcp() fires.

const _mcpTools     = [];   // { name, description, handler }
const _mcpResources = [];   // { uri, name, mimeType, handler }
const _mcpPrompts   = [];   // { name, description, handler }
let   _mcpOnConnected    = null;
let   _mcpOnDisconnected = null;

function mcpServer(setup) {
  const srv = {
    _type: 'McpServer',
    tool: function(name, descOrHandler, handler) {
      if (typeof descOrHandler === 'function') {
        _mcpTools.push({ name, description: '', handler: descOrHandler });
      } else {
        _mcpTools.push({ name, description: descOrHandler || '', handler });
      }
    },
    resource: function(uri, nameOrHandler, mimeOrHandler, handler) {
      if (typeof nameOrHandler === 'function') {
        _mcpResources.push({ uri, name: '', mimeType: '', handler: nameOrHandler });
      } else if (typeof mimeOrHandler === 'function') {
        _mcpResources.push({ uri, name: nameOrHandler || '', mimeType: '', handler: mimeOrHandler });
      } else {
        _mcpResources.push({ uri, name: nameOrHandler || '', mimeType: mimeOrHandler || '', handler });
      }
    },
    prompt: function(name, descOrHandler, handler) {
      if (typeof descOrHandler === 'function') {
        _mcpPrompts.push({ name, description: '', handler: descOrHandler });
      } else {
        _mcpPrompts.push({ name, description: descOrHandler || '', handler });
      }
    },
    onConnected:    function(h) { _mcpOnConnected    = h; },
    onDisconnected: function(h) { _mcpOnDisconnected = h; },
  };
  setup(srv);
}

// Convert a ScalaScript Content list to MCP SDK content array.
function _toMcpContent(c) {
  if (!c || typeof c !== 'object') return { type: 'text', text: String(c) };
  switch (c._type) {
    case 'Text':     return { type: 'text', text: c.text };
    case 'Image':    return { type: 'image', data: c.data, mimeType: c.mimeType };
    case 'Resource': return { type: 'resource', resource: { uri: c.uri } };
    default:         return { type: 'text', text: JSON.stringify(c) };
  }
}

// Convert SDK args object to ScalaScript Map[String, Any].
function _argsToMap(args) {
  const m = new Map();
  if (args && typeof args === 'object') {
    for (const [k, v] of Object.entries(args)) m.set(k, v);
  }
  return m;
}

// McpError sentinel — mirrors the ScalaScript case class.
function McpError(message) { return { _type: 'McpError', message }; }

function _mcpHandlerResult(handler, ...handlerArgs) {
  try {
    return handler(...handlerArgs);
  } catch (e) {
    if (e && e._type === 'McpError') return e;
    return McpError(e && e.message ? e.message : String(e));
  }
}

// Builds an stdio or WebSocket transport.  Http (SSE) is handled directly
// in serveMcp — SSEServerTransport must be created per-connection.
function _mcpTransport(transport) {
  if (!transport || typeof transport !== 'object') throw new Error('serveMcp: invalid transport');
  switch (transport._type) {
    case 'Stdio': {
      const { StdioServerTransport } = require('@modelcontextprotocol/sdk/server/stdio.js');
      return new StdioServerTransport();
    }
    case 'Ws': {
      try {
        const { WebSocketServerTransport } = require('@modelcontextprotocol/sdk/server/websocket.js');
        return new WebSocketServerTransport({ port: transport.port, path: transport.path || '/mcp' });
      } catch (_) {
        throw new Error('serveMcp(Transport.Ws): @modelcontextprotocol/sdk WebSocket transport not available');
      }
    }
    default:
      throw new Error('serveMcp: unsupported transport: ' + transport._type);
  }
}

function _mcpRegister(server) {
  for (const t of _mcpTools) {
    server.tool(t.name, t.description, {}, async ({ arguments: args }) => {
      const result = _mcpHandlerResult(t.handler, _argsToMap(args));
      if (result && result._type === 'McpError') {
        return { content: [{ type: 'text', text: result.message }], isError: true };
      }
      const contents = (result && result.content) ? result.content.map(_toMcpContent) : [];
      return { content: contents, isError: !!(result && result.isError) };
    });
  }
  for (const r of _mcpResources) {
    server.resource(r.uri, r.name || r.uri, async (uri) => {
      const result = _mcpHandlerResult(r.handler, uri);
      if (result && result._type === 'McpError') {
        return { contents: [{ uri, text: result.message, mimeType: 'text/plain' }] };
      }
      const contents = (result && result.contents)
        ? result.contents.map(c => {
            if (c._type === 'Text') return { uri: result.uri || uri, text: c.text, mimeType: r.mimeType || 'text/plain' };
            return { uri: result.uri || uri, text: JSON.stringify(c) };
          })
        : [{ uri, text: String(result) }];
      return { contents };
    });
  }
  for (const p of _mcpPrompts) {
    server.prompt(p.name, p.description, async ({ arguments: args }) => {
      const result = _mcpHandlerResult(p.handler, _argsToMap(args));
      if (result && result._type === 'McpError') {
        return { messages: [{ role: 'user', content: { type: 'text', text: result.message } }] };
      }
      const messages = (result && result.messages)
        ? result.messages.map(m => ({
            role: m.role && m.role._type ? m.role._type.toLowerCase() : 'user',
            content: _toMcpContent(m.content)
          }))
        : [];
      return { messages };
    });
  }
}

function serveMcp(transport) {
  const { McpServer } = require('@modelcontextprotocol/sdk/server/mcp.js');
  const server = new McpServer({ name: 'scalascript-mcp', version: '1.0.0' });
  _mcpRegister(server);

  if (!transport || typeof transport !== 'object') throw new Error('serveMcp: invalid transport');

  if (transport._type === 'Http') {
    // SSE over HTTP: spawn a Node http.Server, create one SSEServerTransport
    // per incoming GET (SSE stream) and forward POSTs to the matching session.
    const http = require('http');
    const { SSEServerTransport } = require('@modelcontextprotocol/sdk/server/sse.js');
    const port = transport.port || 3000;
    const path = transport.path || '/mcp';
    const _sessions = {};   // sessionId -> SSEServerTransport

    const httpServer = http.createServer(async (req, res) => {
      res.setHeader('Access-Control-Allow-Origin', '*');
      res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
      if (req.method === 'OPTIONS') { res.writeHead(204); res.end(); return; }

      const base   = 'http://localhost:' + port;
      const reqUrl = new URL(req.url || '/', base);

      try {
        if (req.method === 'GET' && reqUrl.pathname === path) {
          // New SSE client — create a fresh transport bound to this response.
          const t = new SSEServerTransport(path, res);
          _sessions[t.sessionId] = t;
          res.on('close', () => {
            delete _sessions[t.sessionId];
            if (_mcpOnDisconnected) _mcpOnDisconnected();
          });
          await server.connect(t);
          if (_mcpOnConnected) _mcpOnConnected();

        } else if (req.method === 'POST') {
          // Inbound JSON-RPC message — route to the matching session.
          const sid = reqUrl.searchParams.get('sessionId');
          const t   = sid && _sessions[sid];
          if (t) {
            await t.handlePostMessage(req, res);
          } else {
            res.writeHead(400, { 'Content-Type': 'text/plain' });
            res.end('Unknown or expired session: ' + sid);
          }
        } else {
          res.writeHead(404); res.end();
        }
      } catch (e) {
        if (!res.headersSent) { res.writeHead(500); res.end(e && e.message || 'internal error'); }
        console.error('MCP SSE handler error:', e);
      }
    });

    httpServer.listen(port);

  } else {
    const t = _mcpTransport(transport);
    server.connect(t).then(() => {
      if (_mcpOnConnected) _mcpOnConnected();
    }).catch(e => {
      console.error('MCP server error:', e && e.message ? e.message : e);
      process.exit(1);
    });
  }
}

// ── MCP client ─────────────────────────────────────────────────────────────
//
// `mcpConnect(transport)` returns a synchronous McpClient object.
// Each method call (listTools, callTool, etc.) blocks the main thread
// using a Worker + SharedArrayBuffer + Atomics.wait bridge — the same
// pattern as the OAuth sync-fetch bridge (see _oauthSyncFetch).
//
// The worker script is inlined as a data: URL so there is no on-disk
// dependency.  On Node 18+ data: URLs are supported for `new Worker(...)`.

function _mcpClientWorkerSrc(transportSpec) {
  return `
const { parentPort, workerData } = require('worker_threads');
const { Client } = require('@modelcontextprotocol/sdk/client/index.js');
const spec = workerData.transportSpec;

async function makeTransport(spec) {
  switch (spec.type) {
    case 'Spawn': {
      const { StdioClientTransport } = require('@modelcontextprotocol/sdk/client/stdio.js');
      return new StdioClientTransport({ command: spec.cmd, args: spec.args || [] });
    }
    case 'Http': {
      const { SSEClientTransport } = require('@modelcontextprotocol/sdk/client/sse.js');
      return new SSEClientTransport(new URL(spec.path || '/mcp', 'http://localhost:' + spec.port));
    }
    default:
      throw new Error('mcpConnect: unsupported transport: ' + spec.type);
  }
}

const client = new Client({ name: 'scalascript-mcp-client', version: '1.0.0' });

(async () => {
  const transport = await makeTransport(spec);
  await client.connect(transport);
  parentPort.postMessage({ ready: true });

  parentPort.on('message', async (msg) => {
    let reply;
    try {
      switch (msg.op) {
        case 'listTools':     reply = { ok: await client.listTools() }; break;
        case 'listResources': reply = { ok: await client.listResources() }; break;
        case 'listPrompts':   reply = { ok: await client.listPrompts() }; break;
        case 'callTool':      reply = { ok: await client.callTool({ name: msg.name, arguments: msg.args || {} }) }; break;
        case 'readResource':  reply = { ok: await client.readResource({ uri: msg.uri }) }; break;
        case 'getPrompt':     reply = { ok: await client.getPrompt({ name: msg.name, arguments: msg.args || {} }) }; break;
        case 'close':         await client.close(); reply = { ok: null }; break;
        default:              reply = { error: 'unknown op: ' + msg.op };
      }
    } catch (e) {
      reply = { error: e && e.message ? e.message : String(e) };
    }
    parentPort.postMessage(reply);
  });
})().catch(e => {
  parentPort.postMessage({ error: e && e.message ? e.message : String(e), fatal: true });
});
`;
}

function _mcpClientCall(worker, sab, op, extra) {
  const flag  = new Int32Array(sab);
  const ch    = require('worker_threads').receiveMessageOnPort;
  const mc    = require('worker_threads').MessageChannel;
  // Reset flag
  Atomics.store(flag, 0, 0);
  // Post request with a reply-back channel
  const { port1, port2 } = new mc();
  worker.postMessage({ op, ...extra, replyPort: port2 }, [port2]);
  // Wait for reply (max 30 s)
  Atomics.wait(flag, 0, 0, 30000);
  const msg = ch(port1);
  if (!msg) throw new Error('mcpConnect: timeout on op=' + op);
  if (msg.message.error) throw McpError(msg.message.error);
  return msg.message.ok;
}

// Simpler synchronous bridge using Atomics on a SharedArrayBuffer flag:
// Worker signals flag[0]=1 after posting result, main thread spins until flag[0]===1.
function _mcpSyncCall(worker, op, extra, timeoutMs) {
  const { receiveMessageOnPort, MessageChannel } = require('worker_threads');
  const sab   = new SharedArrayBuffer(4);
  const flag  = new Int32Array(sab);
  const { port1, port2 } = new MessageChannel();
  worker._pendingPort = port1;
  worker.postMessage({ op, ...extra, _sab: sab, _replyPort: port2 }, [port2]);
  const deadline = Date.now() + (timeoutMs || 30000);
  while (Atomics.load(flag, 0) === 0) {
    if (Date.now() > deadline) throw McpError('timeout on op=' + op);
    Atomics.wait(flag, 0, 0, 50);
  }
  const reply = receiveMessageOnPort(port1);
  if (!reply) throw McpError('no reply for op=' + op);
  if (reply.message.error) throw McpError(reply.message.error);
  return reply.message.ok;
}

function _mcpDescriptorsToList(sdkResult, type_) {
  // sdk returns { tools: [...] } / { resources: [...] } / { prompts: [...] }
  const arr = sdkResult && (sdkResult.tools || sdkResult.resources || sdkResult.prompts) || [];
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

function _mcpResultToSsc(sdkResult) {
  // callTool result: { content: [{type, text}], isError }
  const contents = (sdkResult && sdkResult.content || []).map(c => {
    if (c.type === 'image')    return { _type: 'Image',    data: c.data, mimeType: c.mimeType };
    if (c.type === 'resource') return { _type: 'Resource', uri: c.resource && c.resource.uri || '' };
    return { _type: 'Text', text: c.text || '' };
  });
  return { _type: 'ToolResult', content: contents, isError: !!(sdkResult && sdkResult.isError) };
}

function _mcpResourceResultToSsc(sdkResult, uri) {
  const contents = (sdkResult && sdkResult.contents || []).map(c =>
    c.text !== undefined ? { _type: 'Text', text: c.text }
                         : { _type: 'Resource', uri: c.uri || uri }
  );
  return { _type: 'ResourceResult', uri, contents };
}

function _mcpPromptResultToSsc(sdkResult) {
  const messages = (sdkResult && sdkResult.messages || []).map(m => {
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

function mcpConnect(transport, timeoutMs) {
  const { Worker, isMainThread } = require('worker_threads');
  if (!isMainThread) throw McpError('mcpConnect cannot be called from a Worker thread');
  if (!transport || typeof transport !== 'object') throw McpError('mcpConnect: invalid transport');

  const spec = (() => {
    switch (transport._type) {
      case 'Spawn': return { type: 'Spawn', cmd: transport.cmd, args: Array.isArray(transport.args) ? transport.args : [] };
      case 'Http':  return { type: 'Http',  port: transport.port, path: transport.path || '/mcp' };
      case 'Ws':    return { type: 'Ws',    port: transport.port, path: transport.path || '/mcp' };
      default: throw McpError('mcpConnect: unsupported transport: ' + transport._type);
    }
  })();

  const src  = _mcpClientWorkerSrc(spec);
  const worker = new Worker(src, { eval: true, workerData: { transportSpec: spec } });

  // Wait for worker to signal ready
  const { receiveMessageOnPort, MessageChannel } = require('worker_threads');
  const sab  = new SharedArrayBuffer(4);
  const flag = new Int32Array(sab);
  let _ready = false;
  worker.on('message', msg => {
    if (msg.ready) { _ready = true; Atomics.store(flag, 0, 1); }
  });
  const deadline = Date.now() + (timeoutMs || 10000);
  while (!_ready) {
    if (Date.now() > deadline) { worker.terminate(); throw McpError('mcpConnect: connection timeout'); }
    Atomics.wait(flag, 0, 0, 100);
  }

  let _closed = false;

  function _call(op, extra) {
    if (_closed) throw McpError('McpClient is closed');
    // Simple synchronous round-trip using worker.postMessage + receiveMessageOnPort
    const { port1, port2 } = new MessageChannel();
    const sabCall = new SharedArrayBuffer(4);
    const flagCall = new Int32Array(sabCall);
    let _reply = null;
    worker.once('message', msg => { _reply = msg; Atomics.store(flagCall, 0, 1); });
    worker.postMessage({ op, ...extra });
    const dl = Date.now() + 30000;
    while (_reply === null) {
      if (Date.now() > dl) throw McpError('timeout on op=' + op);
      Atomics.wait(flagCall, 0, 0, 50);
    }
    if (_reply.error) throw McpError(_reply.error);
    return _reply.ok;
  }

  return {
    _type: 'McpClient',
    listTools:     () => _mcpDescriptorsToList(_call('listTools'), 'tool'),
    listResources: () => _mcpDescriptorsToList(_call('listResources'), 'resource'),
    listPrompts:   () => _mcpDescriptorsToList(_call('listPrompts'), 'prompt'),
    callTool:      (name, args) => _mcpResultToSsc(_call('callTool', { name, args: args instanceof Map ? Object.fromEntries(args) : (args || {}) })),
    readResource:  (uri)  => _mcpResourceResultToSsc(_call('readResource', { uri }), uri),
    getPrompt:     (name, args) => _mcpPromptResultToSsc(_call('getPrompt', { name, args: args instanceof Map ? Object.fromEntries(args) : (args || {}) })),
    close:         () => { if (!_closed) { _closed = true; try { _call('close'); } finally { worker.terminate(); } } },
    isClosed:      () => _closed,
  };
}
"""
