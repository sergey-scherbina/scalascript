
// ── WebSocket framing (RFC 6455) ───────────────────────────────────────
// Pure-Node implementation — `crypto` for the handshake hash, raw
// `net.Socket` writes for frames.  No `ws` npm dependency.

const _WS_MAGIC = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';
// Hard cap on a single frame's payload (16 MB) — without it a hostile
// client could announce a multi-gigabyte payload and force us to
// allocate that much up front.
const _WS_MAX_FRAME_BYTES = 16 * 1024 * 1024;

function _wsAcceptKey(clientKey) {
  const crypto = require('crypto');
  return crypto.createHash('sha1').update(clientKey + _WS_MAGIC).digest('base64');
}

// Try to parse one frame starting at `offset` in `buf`.  Returns
// `{ fin, opcode, payload, consumed }` on success or `null` when more
// bytes are needed.  Throws on a protocol error (unknown opcode,
// oversized payload) — caller should close.
function _wsParseFrame(buf, offset) {
  const avail = buf.length - offset;
  if (avail < 2) return null;
  const b0 = buf[offset];
  const b1 = buf[offset + 1];
  const fin    = (b0 & 0x80) !== 0;
  const opcode = b0 & 0x0F;
  const masked = (b1 & 0x80) !== 0;
  const len7   = b1 & 0x7F;
  let hdrLen = 2;
  let payloadLen;
  if (len7 <= 125) payloadLen = len7;
  else if (len7 === 126) {
    if (avail < 4) return null;
    payloadLen = buf.readUInt16BE(offset + 2);
    hdrLen = 4;
  } else {
    if (avail < 10) return null;
    const big = buf.readBigUInt64BE(offset + 2);
    if (big > BigInt(_WS_MAX_FRAME_BYTES)) throw new Error('frame too large');
    payloadLen = Number(big);
    hdrLen = 10;
  }
  const maskLen = masked ? 4 : 0;
  const totalLen = hdrLen + maskLen + payloadLen;
  if (avail < totalLen) return null;
  const payload = Buffer.allocUnsafe(payloadLen);
  const payloadStart = offset + hdrLen + maskLen;
  if (masked) {
    const m = buf.slice(offset + hdrLen, offset + hdrLen + 4);
    for (let i = 0; i < payloadLen; i++)
      payload[i] = buf[payloadStart + i] ^ m[i & 3];
  } else if (payloadLen > 0) {
    buf.copy(payload, 0, payloadStart, payloadStart + payloadLen);
  }
  return { fin, opcode, payload, consumed: totalLen };
}

function _wsEncodeFrame(opcode, payload) {
  const len = payload.length;
  let buf;
  if (len <= 125) {
    buf = Buffer.allocUnsafe(2 + len);
    buf[0] = 0x80 | opcode;
    buf[1] = len;
    payload.copy(buf, 2);
  } else if (len <= 0xFFFF) {
    buf = Buffer.allocUnsafe(4 + len);
    buf[0] = 0x80 | opcode;
    buf[1] = 126;
    buf.writeUInt16BE(len, 2);
    payload.copy(buf, 4);
  } else {
    buf = Buffer.allocUnsafe(10 + len);
    buf[0] = 0x80 | opcode;
    buf[1] = 127;
    buf.writeBigUInt64BE(BigInt(len), 2);
    payload.copy(buf, 10);
  }
  return buf;
}

function _wsEncodeText(s)    { return _wsEncodeFrame(0x1, Buffer.from(String(s), 'utf-8')); }
// Binary frames take the Latin-1 byte-view convention the rest of
// the runtime already uses (req.files(...).bytes, inbound binary
// frames): one JS char per wire byte.
function _wsEncodeBinaryLatin1(s) { return _wsEncodeFrame(0x2, Buffer.from(String(s), 'latin1')); }
function _wsEncodePong(p)    { return _wsEncodeFrame(0xA, p); }
function _wsEncodeClose(code, reason) {
  const r = Buffer.from(reason || '', 'utf-8');
  const p = Buffer.allocUnsafe(2 + r.length);
  p.writeUInt16BE(code, 0);
  r.copy(p, 2);
  return _wsEncodeFrame(0x8, p);
}

// Build the `WebSocket` value the handler receives — wraps `socket`
// with `send` / `close` / `onMessage` / `onClose` and pumps inbound
// frames through `_wsParseFrame`.  Control frames (ping/close) are
// handled here; text/binary frames invoke `onMessage` if registered.
function _wsMakeWebSocket(socket, request, subprotocol, maxMessagesPerSec, userPayload) {
  // Stable per-connection identifier — UUID-v4 generated at upgrade
  // time, surfaced to user code as `ws.id` and used to tag every log
  // line for a single session.
  const id = (typeof crypto !== 'undefined' && crypto.randomUUID)
    ? crypto.randomUUID()
    : require('crypto').randomUUID();
  // Wall-clock time of upgrade — feeds `duration_ms` into the
  // Sprint-4 close log emitted on `'close'`.
  const _startedAt = Date.now();
  socket.once('close', () => {
    const dur = Date.now() - _startedAt;
    console.log(`ws.close\tid=${id}\tduration_ms=${dur}`);
  });
  // The subprotocol the server selected during upgrade negotiation
  // (RFC 6455 §1.9), or '' when no negotiation took place.
  // `request.headers['sec-websocket-protocol']` still carries the
  // client's full offer list.
  const _subprotocol = subprotocol || '';
  // Rate-limit state — fixed 1-second window.  0 cap = unlimited;
  // overrun closes the offending client with code 1008.
  const _rateCap = (typeof maxMessagesPerSec === 'number' && maxMessagesPerSec > 0)
    ? maxMessagesPerSec : 0;
  let _rateWindowStart = 0;
  let _rateMsgs        = 0;
  // Payload returned by the route's auth hook, or `_None` for
  // routes without one.  Surfaced to handlers as `ws.user`.
  const _user = userPayload || { _type: '_None' };
  let onMessage = null;
  let onClose   = null;
  let onPong    = null;
  let closing   = false;
  let inBuf     = Buffer.alloc(0);
  // Recv queue for Async.recvFrom — messages land here when no waiter is
  // active; _nextMessage() drains the queue or parks a Promise resolver.
  const _recvQueue = [];
  let _recvWaiter = null;
  function _deliverRecvMsg(msgOrNull) {
    if (_recvWaiter !== null) {
      const res = _recvWaiter; _recvWaiter = null;
      res(msgOrNull === null ? { _type: '_None' } : { _type: '_Some', value: msgOrNull });
    } else if (msgOrNull !== null) {
      _recvQueue.push(msgOrNull);
    }
  }
  // Fragmentation reassembly (RFC 6455 §5.4): the first frame of a
  // fragmented message carries the opcode with FIN=0, follow-up frames
  // are Continuation (opcode=0) with the rest, and the last has FIN=1.
  // Control frames may interleave freely.  Buffer until FIN=1 then
  // dispatch the joined payload using the original opcode.
  let fragOpcode = -1;
  const fragParts = [];
  let   fragSize  = 0;

  // ── Server-initiated heartbeat ────────────────────────────────────
  // Empty Ping every 30 s; if no Pong within 90 s the connection is
  // assumed dead and torn down.  Catches half-closed TCP / NAT
  // timeouts long before the OS keepalive (~2 h) would notice.
  const HEARTBEAT_INTERVAL_MS = 30_000;
  const DEAD_AFTER_MS         = 90_000;
  let lastPongAt = Date.now();
  const heartbeat = setInterval(() => {
    try {
      if (Date.now() - lastPongAt > DEAD_AFTER_MS) {
        if (!closing) {
          closing = true;
          socket.write(_wsEncodeClose(1001, 'ping timeout'));
        }
        socket.destroy();
      } else if (!closing && !socket.destroyed) {
        socket.write(_wsEncodeFrame(0x9, Buffer.alloc(0)));
      }
    } catch (_) { /* socket closed mid-write — fall through */ }
  }, HEARTBEAT_INTERVAL_MS);

  const dispatchMessage = (opcode, payload) => {
    if (_rateCap > 0) {
      const now = Date.now();
      if (now - _rateWindowStart >= 1000) { _rateWindowStart = now; _rateMsgs = 0; }
      _rateMsgs += 1;
      if (_rateMsgs > _rateCap) {
        if (!closing && !socket.destroyed) {
          closing = true;
          try { socket.write(_wsEncodeClose(1008, 'rate limit exceeded')); } catch (_) {}
          try { socket.end(); } catch (_) {}
        }
        return;
      }
    }
    _metricsState['ws.messages.in']++;
    _metricsState['ws.bytes.in'] += payload.length;
    const msg = opcode === 0x1 ? payload.toString('utf-8') : payload.toString('latin1');
    _deliverRecvMsg(msg);
    if (!onMessage) return;
    try { onMessage(msg); } catch (e) { console.error('WS message handler:', e.message); }
  };

  const ws = {
    _type: 'WebSocket',
    send: (s) => {
      if (closing || socket.destroyed) return;
      // Backpressure: `socket.write` is async — Node will buffer the
      // bytes internally and return `false` when the kernel's send
      // buffer is full.  If we keep ignoring that signal, a slow
      // peer lets Node's per-socket queue grow without bound.  Past
      // a 4 MB backlog, drop the connection.
      const frame = _wsEncodeText(s);
      _metricsState['ws.messages.out']++;
      _metricsState['ws.bytes.out'] += frame.length;
      const ok = socket.write(frame);
      if (!ok && socket.writableLength > 4 * 1024 * 1024) {
        closing = true;
        try { socket.destroy(); } catch (_) {}
      }
    },
    sendBytes: (s) => {
      if (closing || socket.destroyed) return;
      const frame = _wsEncodeBinaryLatin1(s);
      _metricsState['ws.messages.out']++;
      _metricsState['ws.bytes.out'] += frame.length;
      const ok = socket.write(frame);
      if (!ok && socket.writableLength > 4 * 1024 * 1024) {
        closing = true;
        try { socket.destroy(); } catch (_) {}
      }
    },
    close: (code, reason) => {
      if (!closing && !socket.destroyed) {
        closing = true;
        socket.write(_wsEncodeClose(code ?? 1000, reason ?? ''));
        socket.end();
      }
    },
    onMessage: (cb) => { onMessage = cb; },
    onClose:   (cb) => { onClose   = cb; },
    onPong:    (cb) => { onPong    = cb; },
    id:        id,
    subprotocol: _subprotocol,
    user:        _user,
    // ping([payload]) — empty Ping or Latin-1-byte-view payload.
    // Peer's Pong arrives via the `onPong` callback above.
    ping: (s) => {
      if (closing || socket.destroyed) return;
      const payload = (s == null || s === '') ? Buffer.alloc(0) : Buffer.from(String(s), 'latin1');
      socket.write(_wsEncodeFrame(0x9, payload));
    },
    isClosed: () => closing,
    _nextMessage: () => new Promise(resolve => {
      if (closing) { resolve({ _type: '_None' }); return; }
      if (_recvQueue.length > 0) { resolve({ _type: '_Some', value: _recvQueue.shift() }); return; }
      _recvWaiter = resolve;
    }),
    request:   request
  };

  socket.on('data', chunk => {
    inBuf = inBuf.length === 0 ? chunk : Buffer.concat([inBuf, chunk]);
    let offset = 0;
    try {
      while (true) {
        const f = _wsParseFrame(inBuf, offset);
        if (!f) break;
        offset += f.consumed;
        switch (f.opcode) {
          case 0x9: socket.write(_wsEncodePong(f.payload)); break;            // ping
          case 0xA:                                                            // pong (peer alive)
            lastPongAt = Date.now();
            if (onPong) {
              try { onPong(f.payload.toString('latin1')); }
              catch (e) { console.error('WS onPong handler:', e.message); }
            }
            break;
          case 0x8: {                                                          // close
            const status = f.payload.length >= 2 ? f.payload.readUInt16BE(0) : 1000;
            if (!closing) { closing = true; socket.write(_wsEncodeClose(status, '')); }
            socket.end();
            break;
          }
          case 0x1: case 0x2: {                                                // text / binary
            if (!f.fin) {
              if (fragOpcode !== -1) {
                if (!closing) { closing = true; socket.write(_wsEncodeClose(1002, 'new data frame mid-fragment')); }
                socket.end(); return;
              }
              fragOpcode = f.opcode;
              fragParts.push(f.payload); fragSize += f.payload.length;
              if (fragSize > _WS_MAX_FRAME_BYTES) {
                fragOpcode = -1; fragParts.length = 0; fragSize = 0;
                if (!closing) { closing = true; socket.write(_wsEncodeClose(1009, 'message too big')); }
                socket.end(); return;
              }
            } else dispatchMessage(f.opcode, f.payload);
            break;
          }
          case 0x0: {                                                          // continuation
            if (fragOpcode === -1) {
              if (!closing) { closing = true; socket.write(_wsEncodeClose(1002, 'continuation without prior data frame')); }
              socket.end(); return;
            }
            fragParts.push(f.payload); fragSize += f.payload.length;
            if (fragSize > _WS_MAX_FRAME_BYTES) {
              fragOpcode = -1; fragParts.length = 0; fragSize = 0;
              if (!closing) { closing = true; socket.write(_wsEncodeClose(1009, 'message too big')); }
              socket.end(); return;
            }
            if (f.fin) {
              const op  = fragOpcode;
              const buf = Buffer.concat(fragParts, fragSize);
              fragOpcode = -1; fragParts.length = 0; fragSize = 0;
              dispatchMessage(op, buf);
            }
            break;
          }
          default:
            if (!closing) { closing = true; socket.write(_wsEncodeClose(1003, '')); }
            socket.end();
        }
      }
    } catch (e) {
      if (!closing) { closing = true; socket.write(_wsEncodeClose(1002, 'protocol error')); }
      socket.end();
      return;
    }
    inBuf = offset > 0 ? inBuf.slice(offset) : inBuf;
  });

  socket.on('close', () => {
    clearInterval(heartbeat);
    closing = true;
    _deliverRecvMsg(null);  // wake any pending Async.recvFrom with None
    if (onClose) try { onClose(); } catch (e) { console.error('WS close handler:', e.message); }
  });

  return ws;
}

// Called from the `server.on('upgrade', …)` listener — finishes the
// 101 handshake, builds the WebSocket value, and runs the user's
// `onWebSocket` block.  No matching route → 404 + close.
function _wsHandleUpgrade(req, socket) {
  try {
    // TCP keepalive lets the OS detect peers that vanished without
    // sending FIN.  Without it a dead WS holds its socket FD for
    // ~2 h before the TCP stack notices.
    socket.setKeepAlive(true);
    const u = new URL(req.url, 'http://localhost');
    const segs = u.pathname.split('/').filter(s => s.length > 0);
    for (const r of _wsRoutes) {
      const params = _matchPath(r.pattern, segs);
      if (params == null) continue;
      // Origin allowlist (CSRF guard).  Empty list = no restriction.
      if (r.origins && r.origins.length > 0) {
        const origin = req.headers['origin'] ?? '';
        if (!r.origins.includes(origin)) {
          socket.write(
            'HTTP/1.1 403 Forbidden\r\n' +
            'Content-Length: 0\r\nConnection: close\r\n\r\n'
          );
          socket.destroy();
          _metricsState['ws.rejected']++;
          return;
        }
      }
      // Build a Request-shaped object — same shape as `_mkRequest`
      // for REST routes (minus body/form/files; the WS upgrade is a
      // GET with no body).  Constructed here (before the auth hook)
      // so the hook receives the same shape user code sees later
      // via `ws.request`.
      const reqHeaders = new Map();
      for (const [k, v] of Object.entries(req.headers))
        reqHeaders.set(k, Array.isArray(v) ? (v[0] ?? '') : String(v ?? ''));
      const reqQuery = new Map();
      u.searchParams.forEach((v, k) => reqQuery.set(k, v));
      const reqCookies = new Map();
      const cookieRaw0 = reqHeaders.get('cookie') ?? '';
      for (const pair of cookieRaw0.split(';')) {
        const t = pair.trim();
        const i = t.indexOf('=');
        if (i > 0) reqCookies.set(t.substring(0, i).trim(), t.substring(i + 1).trim());
      }
      const request = {
        _type:  'Request',
        method: 'GET',
        path:   u.pathname,
        params,
        query:  reqQuery,
        headers: reqHeaders,
        cookies: reqCookies,
      };
      // Pre-upgrade auth hook.  Same contract as interpreter / JvmGen:
      // None → reject 401, Some(v) → carry v to ws.user.
      let _authPayload = { _type: '_None' };
      if (typeof r.auth === 'function') {
        let _v = undefined;
        try { _v = r.auth(request); }
        catch (e) { console.error('WS auth hook:', e.message); _v = null; }
        const _isNone = _v == null || (_v && _v._type === '_None');
        const _isSome = _v && _v._type === '_Some';
        if (_isNone) {
          socket.write(
            'HTTP/1.1 401 Unauthorized\r\n' +
            'Content-Length: 0\r\nConnection: close\r\n\r\n'
          );
          socket.destroy();
          _metricsState['ws.rejected']++;
          return;
        }
        _authPayload = _isSome ? _v : { _type: '_Some', value: _v };
      }
      const clientKey = req.headers['sec-websocket-key'];
      if (!clientKey) { socket.destroy(); return; }
      // Process-wide active-connection cap — refuse with 503 before
      // building the WebSocket value.  Slot is released in the
      // `socket.on('close', ...)` listener below.
      if (_wsActiveCount >= _wsMaxActive) {
        socket.write(
          'HTTP/1.1 503 Service Unavailable\r\n' +
          'Content-Length: 0\r\nConnection: close\r\n\r\n'
        );
        socket.destroy();
        _metricsState['ws.rejected']++;
        return;
      }
      // Per-route cap.  0 = unlimited; composes with the
      // process-wide cap above (both must permit).  Route counter
      // released in the `socket.on('close', ...)` listener.
      const _routeCap = (typeof r.maxConnections === 'number') ? r.maxConnections : 0;
      if (_routeCap > 0 && (r.activeCount ?? 0) >= _routeCap) {
        socket.write(
          'HTTP/1.1 503 Service Unavailable\r\n' +
          'Content-Length: 0\r\nConnection: close\r\n\r\n'
        );
        socket.destroy();
        _metricsState['ws.rejected']++;
        return;
      }
      // Subprotocol negotiation (RFC 6455 §1.9).  Server picks the
      // first protocol it offers that's in the client's request;
      // no match refuses with 400.  Empty server list = no
      // negotiation, the request's protocol header (if any) is
      // ignored and not echoed back.
      let chosenProtocol = '';
      if (r.protocols && r.protocols.length > 0) {
        const offered = (req.headers['sec-websocket-protocol'] ?? '')
          .split(',').map(s => s.trim()).filter(Boolean);
        const offSet = new Set(offered);
        chosenProtocol = r.protocols.find(p => offSet.has(p)) ?? '';
        if (chosenProtocol === '') {
          socket.write(
            'HTTP/1.1 400 Bad Request\r\n' +
            'Content-Length: 0\r\nConnection: close\r\n\r\n'
          );
          socket.destroy();
          _metricsState['ws.rejected']++;
          return;
        }
      }
      _wsActiveCount++;
      _metricsState['ws.active']++;
      _metricsState['ws.upgraded']++;
      if (_routeCap > 0) r.activeCount = (r.activeCount ?? 0) + 1;
      socket.once('close', () => {
        _wsActiveCount--;
        _metricsState['ws.active']--;
        if (_routeCap > 0 && r.activeCount > 0) r.activeCount--;
      });
      const accept = _wsAcceptKey(clientKey);
      const protoHeader = chosenProtocol
        ? 'Sec-WebSocket-Protocol: ' + chosenProtocol + '\r\n'
        : '';
      socket.write(
        'HTTP/1.1 101 Switching Protocols\r\n' +
        'Upgrade: websocket\r\n' +
        'Connection: Upgrade\r\n' +
        'Sec-WebSocket-Accept: ' + accept + '\r\n' +
        protoHeader +
        '\r\n'
      );
      // Structured connect log (Sprint 4 #13).
      const _ip     = (socket && socket.remoteAddress) ? socket.remoteAddress : '?';
      const _origin = req.headers['origin'] ?? '';
      const ws = _wsMakeWebSocket(socket, request, chosenProtocol, r.maxMessagesPerSec ?? 0, _authPayload);
      console.log(`ws.connect\tid=${ws.id}\tip=${_ip}\troute=${u.pathname}\torigin=${_origin}\tproto=${chosenProtocol}`);
      try { r.handler(ws); } catch (e) { console.error('WS upgrade handler:', e.message); }
      return;
    }
    socket.write('HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n');
    socket.destroy();
    _metricsState['ws.rejected']++;
  } catch (e) {
    try { socket.destroy(); } catch (_) {}
  }
}

// Tier 5 #21 — auto-register `/_health` and `/_ready` defaults the
// first time `serve(...)` runs.  User-registered routes with the
// same path keep precedence (we only fill slots that aren't taken).
function _registerHealthDefaults() {
  const ok = () => ({
    _type: 'Response',
    status: 200,
    headers: new Map([['Content-Type', 'application/json']]),
    body: '{"status":"ok"}'
  });
  const has = (path) => _routes.some(r => r.method === 'GET' && r.path === path);
  if (!has('/_health')) _routes.push({ method: 'GET', path: '/_health', pattern: _parsePath('/_health'), handler: ok });
  if (!has('/_ready'))  _routes.push({ method: 'GET', path: '/_ready',  pattern: _parsePath('/_ready'),  handler: ok });
}

// TLS configuration object — pass to serve() to enable HTTPS.
function tls(cert, key) {
  const fs = require('fs');
  return { cert: fs.readFileSync(cert), key: fs.readFileSync(key) };
}

// Outbound HTTP client (synchronous via worker_threads, same pattern as _oauthSyncFetch).
let _httpBaseUrl    = '';
let _httpTimeoutMs  = 30_000;
let _httpMaxRetries = 0;
let _httpRetryDelay = 1_000;

function httpTimeout(ms) { _httpTimeoutMs = ms; }
function httpRetry(n, delayMs) { _httpMaxRetries = n; if (delayMs !== undefined) _httpRetryDelay = delayMs; }

function _httpGuardUrl(url) {
  // H2: opt-in SSRF guard. Literal-host check only (no sync DNS in the worker), so a
  // hostname that resolves to an internal IP is NOT caught — literals + localhost.
  var block = (typeof process !== 'undefined') && process.env && process.env.SSC_HTTP_BLOCK_INTERNAL;
  if (block === '1' || block === 'true') {
    var host = '';
    try { host = new URL(url).hostname; } catch (e) { host = ''; }
    host = host.replace(/^\[|\]$/g, '');
    var internal = host === 'localhost' || host === '0.0.0.0' || host === '::1' ||
      /^127\./.test(host) || /^10\./.test(host) || /^192\.168\./.test(host) ||
      /^169\.254\./.test(host) || /^172\.(1[6-9]|2\d|3[01])\./.test(host);
    if (internal) throw new Error("SSRF blocked: host '" + host + "' is internal (SSC_HTTP_BLOCK_INTERNAL)");
  }
}

function _httpResolveUrl(url) {
  // H3: absolute only on an explicit http(s):// scheme; otherwise join base + a
  // leading-'/' path so a `url` like "@evil/x" can't inject userinfo that re-points the host.
  var resolved;
  if (!_httpBaseUrl || url.startsWith('http://') || url.startsWith('https://')) resolved = url;
  else { var base = _httpBaseUrl.replace(/\/+$/, ''); resolved = url.startsWith('/') ? base + url : base + '/' + url; }
  _httpGuardUrl(resolved);
  return resolved;
}

function _httpMaxBody() {
  // M2: cap the response body read into memory (default 10 MB; SSC_HTTP_MAX_BODY
  // bytes to override; negative = unbounded). Parity with JVM/interp/Rust clients.
  var v = (typeof process !== 'undefined') && process.env && process.env.SSC_HTTP_MAX_BODY;
  var n = v ? parseInt(v, 10) : NaN;
  return Number.isFinite(n) ? n : 10 * 1024 * 1024;
}

function _httpSyncFetch(method, url, body, headers) {
  const effective = _httpResolveUrl(url);
  const timeoutMs = _httpTimeoutMs;
  const { Worker, MessageChannel, receiveMessageOnPort } = require('worker_threads');
  const sab  = new SharedArrayBuffer(4);
  const flag = new Int32Array(sab);
  const { port1, port2 } = new MessageChannel();
  const workerSrc = [
    'const { parentPort, workerData } = require(\'worker_threads\');',
    'const flag = new Int32Array(workerData.sab);',
    'const port = workerData.port;',
    '(async () => {',
    '  let msg;',
    '  try {',
    '    const ac = new AbortController();',
    '    const timer = setTimeout(() => ac.abort(), workerData.timeoutMs);',
    '    const opts = { method: workerData.method, headers: workerData.headers, signal: ac.signal };',
    '    if (workerData.body) opts.body = workerData.body;',
    '    const r = await fetch(workerData.url, opts);',
    '    clearTimeout(timer);',
    '    const _maxBody = workerData.maxBody;',
    '    let text;',
    '    if (_maxBody < 0 || !r.body || !r.body.getReader) { text = await r.text(); }',
    '    else {',
    '      const _rd = r.body.getReader(); const _dec = new TextDecoder(); let _tot = 0; text = "";',
    '      for (;;) { const _c = await _rd.read(); if (_c.done) break;',
    '        _tot += _c.value.length;',
    '        if (_tot > _maxBody) { await _rd.cancel(); throw new Error("HTTP response body exceeds " + _maxBody + " bytes (SSC_HTTP_MAX_BODY)"); }',
    '        text += _dec.decode(_c.value, { stream: true }); }',
    '      text += _dec.decode(); }',
    '    const hdrs = {};',
    '    r.headers.forEach((v, k) => hdrs[k] = v);',
    '    msg = { status: r.status, body: text, headers: hdrs };',
    '  } catch (e) { msg = { status: 0, body: String(e), headers: {} }; }',
    '  port.postMessage(msg);',
    '  Atomics.store(flag, 0, 1);',
    '  Atomics.notify(flag, 0);',
    '})();',
  ].join('\\n');
  const worker = new Worker(workerSrc, {
    eval: true,
    workerData: { sab, port: port2, url: effective, method, headers: headers || {}, body: body || null, timeoutMs, maxBody: _httpMaxBody() },
    transferList: [port2],
  });
  Atomics.wait(flag, 0, 0, timeoutMs + 500);
  const drained = receiveMessageOnPort(port1);
  worker.terminate(); port1.close();
  const r = drained ? drained.message : { status: 0, body: 'timeout', headers: {} };
  const hdrsMap = new Map(Object.entries(r.headers || {}));
  return { _type: 'Response', status: r.status, body: r.body, headers: hdrsMap };
}

function _httpSyncFetchWithRetry(method, url, body, headers) {
  const { receiveMessageOnPort } = require('worker_threads');
  const maxTries = Math.min(_httpMaxRetries, 10) + 1;  // L1: cap runaway retry counts
  let last;
  for (let attempt = 0; attempt < maxTries; attempt++) {
    last = _httpSyncFetch(method, url, body, headers);
    if (last.status !== 0 && last.status < 500) break;
    if (attempt < maxTries - 1) {
      const sab2 = new SharedArrayBuffer(4); const flag2 = new Int32Array(sab2);
      // L1: exponential backoff (delay·2^attempt) ± 20% jitter.
      const backoff = Math.max(0, Math.round(_httpRetryDelay * Math.pow(2, Math.min(attempt, 16)) * (0.8 + Math.random() * 0.4)));
      Atomics.wait(flag2, 0, 0, backoff);
    }
  }
  return last;
}

function httpGet(url, headers) {
  const h = _isMap(headers) ? Object.fromEntries(headers.entries()) : (headers || {});
  return _httpSyncFetchWithRetry('GET', url, null, h);
}

function httpPost(url, body, headers) {
  const h = _isMap(headers) ? Object.fromEntries(headers.entries()) : (headers || {});
  return _httpSyncFetchWithRetry('POST', url, body, h);
}

function httpPut(url, body, headers) {
  const h = _isMap(headers) ? Object.fromEntries(headers.entries()) : (headers || {});
  return _httpSyncFetchWithRetry('PUT', url, body, h);
}

function httpPatch(url, body, headers) {
  const h = _isMap(headers) ? Object.fromEntries(headers.entries()) : (headers || {});
  return _httpSyncFetchWithRetry('PATCH', url, body, h);
}

function httpDelete(url, headers) {
  const h = _isMap(headers) ? Object.fromEntries(headers.entries()) : (headers || {});
  return _httpSyncFetchWithRetry('DELETE', url, null, h);
}

function httpClient(baseUrl, block) {
  const priorBase = _httpBaseUrl, priorT = _httpTimeoutMs;
  const priorR = _httpMaxRetries, priorD = _httpRetryDelay;
  _httpBaseUrl = baseUrl;
  try { return block(); }
  finally { _httpBaseUrl = priorBase; _httpTimeoutMs = priorT;
            _httpMaxRetries = priorR; _httpRetryDelay = priorD; }
}

// Streaming HTTP — collects lines in a worker thread then calls handler per line.
// Uses _httpTimeoutMs for the worker AbortController and the Atomics.wait guard.
function _httpStreamFetch(method, url, body, headers, handler) {
  const effective  = _httpResolveUrl(url);
  const timeoutMs  = _httpTimeoutMs;
  const { Worker, MessageChannel, receiveMessageOnPort } = require('worker_threads');
  const sab  = new SharedArrayBuffer(4);
  const flag = new Int32Array(sab);
  const { port1, port2 } = new MessageChannel();
  const workerSrc = [
    'const { workerData } = require(\'worker_threads\');',
    'const { port, sab, url, method, headers, body, timeoutMs } = workerData;',
    'const flag = new Int32Array(sab);',
    '(async () => {',
    '  let result;',
    '  try {',
    '    const ac = new AbortController();',
    '    const timer = setTimeout(() => ac.abort(), timeoutMs);',
    '    const opts = { method, headers, signal: ac.signal };',
    '    if (body) opts.body = body;',
    '    const r = await fetch(url, opts);',
    '    const hdrs = {};',
    '    r.headers.forEach((v, k) => hdrs[k] = v);',
    '    const _maxBody = workerData.maxBody;',
    '    let text;',
    '    if (_maxBody < 0 || !r.body || !r.body.getReader) { text = await r.text(); }',
    '    else {',
    '      const _rd = r.body.getReader(); const _dec = new TextDecoder(); let _tot = 0; text = "";',
    '      for (;;) { const _c = await _rd.read(); if (_c.done) break;',
    '        _tot += _c.value.length;',
    '        if (_tot > _maxBody) { await _rd.cancel(); throw new Error("HTTP response body exceeds " + _maxBody + " bytes (SSC_HTTP_MAX_BODY)"); }',
    '        text += _dec.decode(_c.value, { stream: true }); }',
    '      text += _dec.decode(); }',
    '    clearTimeout(timer);',
    '    const lines = text.split(\'\\n\');',
    '    result = { status: r.status, headers: hdrs, lines };',
    '  } catch (e) { result = { status: 0, headers: {}, lines: [], error: String(e) }; }',
    '  port.postMessage(result);',
    '  Atomics.store(flag, 0, 1);',
    '  Atomics.notify(flag, 0);',
    '})();',
  ].join('\\n');
  const worker = new Worker(workerSrc, {
    eval: true,
    workerData: { sab, port: port2, url: effective, method, headers, body: body || null, timeoutMs, maxBody: _httpMaxBody() },
    transferList: [port2],
  });
  Atomics.wait(flag, 0, 0, timeoutMs + 500);
  const drained = receiveMessageOnPort(port1);
  worker.terminate(); port1.close();
  const r = drained ? drained.message : { status: 0, headers: {}, lines: [] };
  const hdrsMap = new Map(Object.entries(r.headers || {}));
  for (const line of (r.lines || [])) { handler(line); }
  return { _type: 'Response', status: r.status, body: '', headers: hdrsMap };
}

function httpGetStream(url, headers) {
  const h = (_isMap(headers)) ? Object.fromEntries(headers.entries())
            : (headers && typeof headers === 'object') ? headers : {};
  return function(handler) { return _httpStreamFetch('GET', url, null, h, handler); };
}

function httpPostStream(url, body, headers) {
  const h = (_isMap(headers)) ? Object.fromEntries(headers.entries())
            : (headers && typeof headers === 'object') ? headers : {};
  return function(handler) { return _httpStreamFetch('POST', url, body, h, handler); };
}

function _ssc_http_serve(port, _tlsCfg) {
  _registerHealthDefaults();
  const _useTls = !!_tlsCfg;
  const http = _useTls ? require('https') : require('http');
  const serverOpts = _useTls ? { cert: _tlsCfg.cert, key: _tlsCfg.key } : {};
  const _requestHandler = (req, res) => {
    // Collect chunks as Buffers (not strings) so multipart file uploads
    // round-trip byte-for-byte.
    const chunks = [];
    _metricsState['http.requests']++;
    // Structured access log (Sprint 4 #15) — one tab-separated line
    // per request, emitted from the `finish` event so the status
    // code is final.
    const _accessStart  = Date.now();
    const _accessMethod = req.method ?? '?';
    const _accessPath   = (req.url ?? '').split('?')[0];
    const _accessIp     = (req.socket && req.socket.remoteAddress) ? req.socket.remoteAddress : '?';
    const _accessUa     = (req.headers['user-agent'] ?? '').replace(/"/g, "'");
    res.on('finish', () => {
      const c = res.statusCode;
      if (c >= 400 && c < 500) _metricsState['http.4xx']++;
      else if (c >= 500)       _metricsState['http.5xx']++;
      const dur = Date.now() - _accessStart;
      console.log(`http\tip=${_accessIp}\tmethod=${_accessMethod}\tpath=${_accessPath}\tstatus=${c}\tduration_ms=${dur}\tua="${_accessUa}"`);
    });
    req.on('data', c => chunks.push(c));
    req.on('end', async () => {
      try {
        const bodyBuf = Buffer.concat(chunks);
        if (_maxBodySizeBytes > 0 && bodyBuf.length > _maxBodySizeBytes) {
          res.writeHead(413, { 'Content-Type': 'text/plain; charset=utf-8' });
          res.end('Request Entity Too Large');
          return;
        }
        const method = req.method.toUpperCase();
        const u = new URL(req.url, 'http://localhost');
        // CORS preflight — OPTIONS with configured origins short-circuits route dispatch
        if (method === 'OPTIONS' && _corsOrigins) {
          const _preOut = {};
          _applyCors(req.headers, _preOut);
          res.writeHead(204, _preOut); res.end(); return;
        }
        const segs = u.pathname.split('/').filter(s => s.length > 0);
        for (const r of _routes) {
          if (r.method !== method) continue;
          const params = _matchPath(r.pattern, segs);
          if (params == null) continue;
          const request = _mkRequest(req, params, bodyBuf);
          // Tier 5 #20 — validation primitives short-circuit by throwing
          // _RestValidationError; catch here and turn into 400.
          // D′.2 — build middleware chain: first registered = outermost.
          function _baseHandler() {
            try {
              return r.handler(request);
            } catch (e) {
              if (e && e._restValidation) {
                return _mkResp({ status: 400, headers: new Map([['Content-Type', 'text/plain; charset=utf-8']]), body: String(e.message || e) });
              }
              throw e;
            }
          }
          let _chain = _baseHandler;
          for (let _i = _middlewares.length - 1; _i >= 0; _i--) {
            const _mw = _middlewares[_i], _inner = _chain;
            _chain = () => _mw(request, _inner);
          }
          let result;
          result = await _chain();
          try {
            // Streaming response — invoke the writer block with a chunk callback
            if (result && result._streaming) {
              const _sh = _isMap(result.headers) ? Object.fromEntries(result.headers.entries()) : {};
              if (!_sh['Content-Type']) _sh['Content-Type'] = 'text/plain; charset=utf-8';
              _applyCors(req.headers, _sh);
              res.writeHead(result.status || 200, _sh);
              result.block(chunk => res.write(chunk));
              res.end();
              return;
            }
            const headers = result && _isMap(result.headers) ? result.headers : new Map();
            if (!headers.has('Content-Type')) headers.set('Content-Type', 'text/plain; charset=utf-8');
            const out = headers ? Object.fromEntries(headers.entries()) : {};
            _applyCors(req.headers, out);
            // `withSession`/`clearSession` attach a Map at `setSession`.
            // In stateless mode the cookie *is* the payload. In store
            // mode we stash the payload server-side and emit only the
            // signed SSID, plus we delete any prior SSID so the store
            // doesn't accumulate dead entries.
            if (result && result.setSession !== undefined) {
              const ssetting = result.setSession;
              let cookiePayload = ssetting;
              if (_sessionStoreEnabled) {
                const priorSsid = request.session && request.session.get && request.session.get('_ssid');
                // request.session in store mode is the looked-up payload,
                // not the raw cookie; grab the SSID off the raw cookie:
                const rawSsid = (request._rawCookieSession && request._rawCookieSession.get)
                  ? request._rawCookieSession.get('_ssid') : null;
                if (rawSsid) _sessionStoreDelete(rawSsid);
                if ((_isMap(ssetting) && ssetting.size === 0) ||
                    (!(_isMap(ssetting)) && Object.keys(ssetting || {}).length === 0)) {
                  cookiePayload = new Map();
                } else {
                  const newSsid = _sessionStorePut(ssetting);
                  cookiePayload = new Map([['_ssid', newSsid]]);
                }
              }
              out['Set-Cookie'] = _buildSetCookie(cookiePayload);
            }
            // 304 short-circuit when ETag matches If-None-Match
            const _etag = out['ETag'] || out['etag'] || '';
            const _inm  = req.headers['if-none-match'] || '';
            if (_etag && _inm && (_etag === _inm || `"${_etag}"` === _inm)) {
              res.writeHead(304, {}); res.end(); return;
            }
            // gzip compression for text responses
            const _body = result.body ?? '';
            const _acceptGzip  = (req.headers['accept-encoding'] || '').includes('gzip');
            const _contentType = out['Content-Type'] || '';
            const _compressible = _contentType.startsWith('text/') || _contentType.includes('json') || _contentType.includes('javascript');
            if (_gzipEnabled && _acceptGzip && _compressible && _body.length > 0) {
              const _compressed = require('zlib').gzipSync(Buffer.from(_body, 'utf-8'));
              out['Content-Encoding'] = 'gzip';
              out['Content-Length'] = String(_compressed.length);
              res.writeHead(result.status ?? 200, out);
              res.end(_compressed);
            } else {
              res.writeHead(result.status ?? 200, out);
              res.end(_body);
            }
            return;
          } finally {
            if (request._spooled && request._spooled.length > 0) {
              const _fs = require('fs');
              request._spooled.forEach(f => { try { _fs.unlinkSync(f); } catch (_e) {} });
            }
          }
        }
        // No route matched: still run global middleware so a leading use{} can
        // short-circuit an unrouted path (parity with the interpreter dispatch).
        // A passthrough sentinel lets middleware that calls next() fall through
        // to the normal static/404 path below; a middleware that returns a
        // Response is written here.
        if (_middlewares.length > 0) {
          const _uReq = _mkRequest(req, {}, bodyBuf);
          const _passthrough = { _sscUnrouted: true };
          let _uChain = () => _passthrough;
          for (let _i = _middlewares.length - 1; _i >= 0; _i--) {
            const _mw = _middlewares[_i], _inner = _uChain;
            _uChain = () => _mw(_uReq, _inner);
          }
          const _uResult = await _uChain();
          if (_uResult && _uResult !== _passthrough && !_uResult._sscUnrouted) {
            const _uh = _isMap(_uResult.headers) ? _uResult.headers : new Map();
            if (!_uh.has('Content-Type')) _uh.set('Content-Type', 'text/plain; charset=utf-8');
            const _uo = Object.fromEntries(_uh.entries());
            _applyCors(req.headers, _uo);
            res.writeHead(_uResult.status ?? 200, _uo);
            res.end(_uResult.body ?? '');
            return;
          }
        }
        // Fall through to a static file under the cwd before 404'ing.
        if (_serveStatic(res, u.pathname)) return;
        res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end(`Not Found: ${u.pathname}`);
      } catch (e) {
        console.error('route error:', e.message);
        res.writeHead(500); res.end('Internal Error');
      }
    });
  };
  const server = _useTls
    ? http.createServer(serverOpts, _requestHandler)
    : http.createServer(_requestHandler);
  // WebSocket upgrade lives on the same server — Node hands us the raw
  // socket (post-headers) and stays out of the way after.
  server.on('upgrade', (req, socket, _head) => {
    // Node fires 'upgrade' for ANY `Connection: Upgrade` request, not just
    // WebSocket. Non-WS upgrades (notably java.net.http's default HTTP/2
    // cleartext probe — `Upgrade: h2c`) must NOT be routed into the WS
    // handshake: with no matching WS route they would leave the socket hung
    // (no response → client times out), so `/_ssc-cluster/*` and every other
    // REST route became unreachable to HTTP/2-preferring clients. Serve them
    // as a normal HTTP/1.1 request instead (the client then falls back to 1.1).
    if (String(req.headers['upgrade'] || '').toLowerCase() !== 'websocket') {
      const res = new http.ServerResponse(req);
      res.assignSocket(socket);
      res.on('finish', () => res.detachSocket(socket));
      _requestHandler(req, res);
      return;
    }
    _wsHandleUpgrade(req, socket);
  });
  server.listen(port, () => console.log(`Listening on ${_useTls ? 'https' : 'http'}://localhost:${port}/  (backend=node${_ssc_frontend_name ? ', frontend=' + _ssc_frontend_name : ''})`));
  _activeServer = server;
}

let _activeServer = null;

function serve(port, _tlsCfg) {
  return _ssc_http_serve(port, _tlsCfg);
}

// Non-blocking variant of `serve` — mirrors the interpreter's
// `serveAsync` semantics.  In Node `serve` is already non-blocking
// (the event loop keeps the process alive while the server has open
// listeners), so `serveAsync` just delegates to `serve`.  The caller's
// script continues immediately; the server runs in the Node event
// loop in the background.  Required for Tier 4 codegen multi-backend
// cluster tests where a single Node process must both bind its WS
// server AND run an actor scheduler (see specs/cluster-codegen-gap.md).
function serveAsync(port, _tlsCfg) {
  return _ssc_http_serve(port, _tlsCfg);
}

function stop() {
  if (_activeServer) { _activeServer.close(); _activeServer = null; }
}

// ── Body size limit ───────────────────────────────────────────────────────────
let _maxBodySizeBytes = 0;
function maxBodySize(n) { _maxBodySizeBytes = n; }

// ── Upload spool-to-disk ──────────────────────────────────────────────────────
let _spoolThreshold = 1024 * 1024; // 1 MB
let _uploadDir = (typeof require === 'function') ? require('os').tmpdir() : '/tmp';
function uploadSpoolThreshold(n) { _spoolThreshold = n; }
function uploadDir(path) { _uploadDir = path; }

// ── Streaming response ────────────────────────────────────────────────────────
function streamResponse(statusOrBlock, headersOrBlock) {
  if (typeof statusOrBlock === 'function') {
    return { _streaming: true, status: 200, headers: new Map(), block: statusOrBlock };
  }
  return function(block) {
    const hdrs = (_isMap(headersOrBlock)) ? headersOrBlock : new Map();
    return { _streaming: true, status: statusOrBlock || 200, headers: hdrs, block: block };
  };
}

// ── Server-Sent Events ────────────────────────────────────────────────────────
const _sseHeaders = new Map([
  ['Content-Type',      'text/event-stream'],
  ['Cache-Control',     'no-cache'],
  ['Connection',        'keep-alive'],
  ['X-Accel-Buffering', 'no']
]);
function sse(req) {
  return function(block) {
    return streamResponse(200, _sseHeaders)(function(write) {
      const stream = {
        send: function(eventOrData, data) {
          if (data !== undefined) write('event: ' + eventOrData + '\ndata: ' + data + '\n\n');
          else                    write('data: ' + eventOrData + '\n\n');
        },
        close: function() {}
      };
      block(stream);
    });
  };
}

// ── CORS / gzip / cache helpers ───────────────────────────────────────────────
let _corsOrigins = null;
let _corsMethods = null;
let _corsHeaders = null;
let _gzipEnabled = false;

function cors(origins, methods, headers) {
  _corsOrigins = Array.isArray(origins) ? origins : [...(origins || [])];
  _corsMethods = Array.isArray(methods) ? methods : ['GET','POST','PUT','DELETE','OPTIONS','PATCH'];
  _corsHeaders = Array.isArray(headers) ? headers : [];
}

function useGzip() { _gzipEnabled = true; }

function cacheable(r, maxAge, etag) {
  const h = r && _isMap(r.headers) ? new Map(r.headers) : new Map();
  h.set('Cache-Control', `public, max-age=${maxAge}`);
  if (etag) h.set('ETag', String(etag));
  return Object.assign({}, r, { headers: h });
}

function noCache(r) {
  const h = r && _isMap(r.headers) ? new Map(r.headers) : new Map();
  h.set('Cache-Control', 'no-store, no-cache, must-revalidate');
  return Object.assign({}, r, { headers: h });
}

function _applyCors(reqHeaders, outHeaders) {
  if (!_corsOrigins) return;
  const origin = (reqHeaders && reqHeaders['origin']) || '';
  let allowed = '';
  if (_corsOrigins.includes('*')) allowed = '*';
  else if (_corsOrigins.includes(origin)) allowed = origin;
  if (!allowed) return;
  outHeaders['Access-Control-Allow-Origin'] = allowed;
  if (_corsMethods && _corsMethods.length) outHeaders['Access-Control-Allow-Methods'] = _corsMethods.join(', ');
  if (_corsHeaders && _corsHeaders.length) outHeaders['Access-Control-Allow-Headers'] = _corsHeaders.join(', ');
  outHeaders['Vary'] = 'Origin';
}

// ── Outbound WebSocket client (ws:// and wss://) ─────────────────────────────
// Uses Node.js net/tls modules + manual RFC 6455 framing.
// Client→server frames are masked (RFC 6455 §5.3).

function _wsEncodeMasked(opcode, payload) {
  const mask = require('crypto').randomBytes(4);
  const masked = Buffer.alloc(payload.length);
  for (let i = 0; i < payload.length; i++) masked[i] = payload[i] ^ mask[i % 4];
  const L = payload.length;
  let hdr;
  if (L <= 125)    { hdr = Buffer.alloc(6);  hdr[0] = 0x80 | opcode; hdr[1] = 0x80 | L;   mask.copy(hdr, 2); }
  else if (L<65536){ hdr = Buffer.alloc(8);  hdr[0] = 0x80 | opcode; hdr[1] = 0xFE; hdr.writeUInt16BE(L, 2); mask.copy(hdr, 4); }
  else             { hdr = Buffer.alloc(14); hdr[0] = 0x80 | opcode; hdr[1] = 0xFF; hdr.writeUInt32BE(0, 2); hdr.writeUInt32BE(L, 6); mask.copy(hdr, 10); }
  return Buffer.concat([hdr, masked]);
}

function wsConnect(url, extraHeaders, protocols) {
  return function(handler) {
    const _u = new URL(url);
    const isTls = _u.protocol === 'wss:';
    const _port = parseInt(_u.port) || (isTls ? 443 : 80);
    const _host = _u.hostname;
    const _path = (_u.pathname || '/') + (_u.search || '');
    const _id = require('crypto').randomUUID();

    let _sock = null;
    let _closing = false, _closed = false;
    let _onMsgCb = null, _onCloseCb = null, _onPongCb = null;
    const _msgQueue = [];
    let _recvWaiter = null;
    let _subproto = '';

    function _doClose() {
      if (!_closed) {
        _closed = true; _closing = true;
        if (_recvWaiter !== null) { const r = _recvWaiter; _recvWaiter = null; r({ _type: '_None' }); }
        if (_onCloseCb) try { _onCloseCb(); } catch(_e) {}
      }
    }

    const _wsObj = new Map([
      ['id',         _id],
      ['subprotocol', ''],
      ['send',       s  => { if (!_closing && _sock) _sock.write(_wsEncodeMasked(0x1, Buffer.from(String(s), 'utf-8'))); }],
      ['sendBytes',  s  => { if (!_closing && _sock) _sock.write(_wsEncodeMasked(0x2, Buffer.from(String(s), 'latin1'))); }],
      ['close',      (code, reason) => {
        if (!_closing) {
          _closing = true;
          const c = (typeof code === 'number') ? code : 1000;
          const rb = Buffer.from(typeof reason === 'string' ? reason : '', 'utf-8');
          const p = Buffer.alloc(2 + rb.length); p.writeUInt16BE(c, 0); rb.copy(p, 2);
          if (_sock) _sock.write(_wsEncodeMasked(0x8, p));
        }
      }],
      ['onMessage',  cb => { _onMsgCb  = cb; }],
      ['onClose',    cb => { _onCloseCb = cb; }],
      ['ping',       payload => {
        if (_sock) _sock.write(_wsEncodeMasked(0x9,
          payload ? Buffer.from(String(payload), 'latin1') : Buffer.alloc(0)));
      }],
      ['onPong',     cb => { _onPongCb = cb; }],
      ['recv',       () => {
        const msg = _msgQueue.shift();
        return msg !== undefined ? { _type: '_Some', value: msg } : { _type: '_None' };
      }],
      ['isClosed',   () => _closing],
      ['_nextMessage', () => new Promise(resolve => {
        if (_closing) { resolve({ _type: '_None' }); return; }
        const msg = _msgQueue.shift();
        if (msg !== undefined) { resolve({ _type: '_Some', value: msg }); return; }
        _recvWaiter = resolve;
      })],
    ]);

    // RFC 6455 handshake
    const _crypto = require('crypto');
    const _wsKey  = _crypto.randomBytes(16).toString('base64');
    const _hdrs   = _isMap(extraHeaders) ? Object.fromEntries(extraHeaders.entries()) : (extraHeaders || {});
    const _prots  = Array.isArray(protocols) ? protocols : [];
    let _req = `GET ${_path} HTTP/1.1\r\nHost: ${_host}:${_port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: ${_wsKey}\r\nSec-WebSocket-Version: 13\r\n`;
    if (_prots.length > 0) _req += `Sec-WebSocket-Protocol: ${_prots.join(', ')}\r\n`;
    for (const [k, v] of Object.entries(_hdrs)) _req += `${k}: ${v}\r\n`;
    _req += '\r\n';

    let _buf = Buffer.alloc(0), _upgraded = false;
    let _fragOp = 0, _fragBuf = Buffer.alloc(0);

    function _parseFrames(data) {
      _buf = Buffer.concat([_buf, data]);
      outer: while (_buf.length >= 2) {
        const fin = (_buf[0] >> 7) & 1;
        const op  = _buf[0] & 0xF;
        let payLen = _buf[1] & 0x7F;
        let off = 2;
        if (payLen === 126) { if (_buf.length < 4) break; payLen = _buf.readUInt16BE(2); off = 4; }
        else if (payLen === 127) { if (_buf.length < 10) break; payLen = _buf.readUInt32BE(6); off = 10; }
        if (_buf.length < off + payLen) break;
        const payload = _buf.slice(off, off + payLen);
        _buf = _buf.slice(off + payLen);
        if      (op === 0x8) { _doClose(); break; }
        else if (op === 0xA) { if (_onPongCb) try { _onPongCb(payload.toString('latin1')); } catch(_e) {} }
        else if (op === 0x9) { if (_sock) _sock.write(_wsEncodeMasked(0xA, payload)); }
        else {
          if (op !== 0x0) { _fragOp = op; _fragBuf = payload; }
          else { _fragBuf = Buffer.concat([_fragBuf, payload]); }
          if (fin) {
            const msg = _fragOp === 0x1 ? _fragBuf.toString('utf-8') : _fragBuf.toString('latin1');
            _fragBuf = Buffer.alloc(0);
            if (_recvWaiter !== null) {
              const r = _recvWaiter; _recvWaiter = null;
              r({ _type: '_Some', value: msg });
            } else {
              _msgQueue.push(msg);
            }
            if (_onMsgCb) try { _onMsgCb(msg); } catch(_e) {}
          }
        }
      }
    }

    const _netMod = isTls ? require('tls') : require('net');
    const _connOpts = isTls ? { host: _host, port: _port, servername: _host } : { host: _host, port: _port };
    _sock = _netMod.connect(_connOpts, () => { _sock.write(_req); });

    _sock.on('data', data => {
      if (!_upgraded) {
        _buf = Buffer.concat([_buf, data]);
        const idx = _buf.indexOf('\r\n\r\n');
        if (idx === -1) return;
        const respHdrs = _buf.slice(0, idx).toString('utf-8');
        const remaining = _buf.slice(idx + 4);
        _buf = Buffer.alloc(0);
        if (!respHdrs.includes(' 101 ')) { _sock.destroy(); _doClose(); return; }
        const spM = respHdrs.match(/Sec-WebSocket-Protocol:\s*([^\r\n]+)/i);
        if (spM) { _subproto = spM[1].trim(); _wsObj.set('subprotocol', _subproto); }
        _upgraded = true;
        handler(_wsObj);
        if (remaining.length > 0) _parseFrames(remaining);
      } else {
        _parseFrames(data);
      }
    });
    _sock.on('error', e => { console.error(`wsConnect error [${url}]: ${e.message}`); _doClose(); });
    _sock.on('close', _doClose);
    _sock.on('end',   _doClose);
  };
}
