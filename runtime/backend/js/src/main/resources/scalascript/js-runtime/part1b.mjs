
// ── REST routing + serve(port) ─────────────────────────────────────────
// Matches the interpreter / JVM-backend semantics: route(method, path)
// registers a closure, serve(port) starts Node's http.createServer and
// dispatches.  Node's event loop keeps the process alive — no Thread.join
// needed.  Browser-side execution is intentionally out of scope: this
// runtime require()s 'http' which only exists in Node.
const _routes = [];
const _middlewares = [];
function use(fn) { _middlewares.push(fn); }
const _wsRoutes = [];
// Process-wide cap on active WS sessions.  `setMaxWsConnections(n)`
// raises / lowers it.  Default = unlimited; upgrades past the cap
// are refused with 503 Service Unavailable.
let _wsMaxActive   = Number.POSITIVE_INFINITY;
let _wsActiveCount = 0;
function setMaxWsConnections(n) {
  _wsMaxActive = (n == null || n < 0) ? Number.POSITIVE_INFINITY : n;
}

// ── Process-wide metrics (Sprint 4 #14) ─────────────────────────────
// Same keys as scalascript.server.Metrics on the interpreter side,
// so log shippers / health checks scrape identical output across
// backends.  Node is single-threaded so counters are plain Numbers.
const _metricsState = {
  'ws.active': 0, 'ws.upgraded': 0, 'ws.rejected': 0,
  'ws.messages.in': 0, 'ws.messages.out': 0,
  'ws.bytes.in': 0,   'ws.bytes.out': 0,
  'http.requests': 0, 'http.4xx': 0, 'http.5xx': 0,
};
function metrics() {
  // Snapshot returned as a Map<string, number> — same shape as the
  // interpreter / JvmGen surfaces.
  const out = new Map();
  for (const [k, v] of Object.entries(_metricsState)) out.set(k, v);
  return out;
}

// WsRoom() — thread-safe (well, event-loop-safe) registry of
// WebSocket objects + broadcast helper.  Replaces the pattern
// every chat demo otherwise reinvents.  Node is single-threaded
// so the array is unsynchronised; mutations land on the event
// loop in order.
function WsRoom() {
  const members = [];
  return {
    _type: 'WsRoom',
    add: (ws) => { members.push(ws); },
    remove: (ws) => {
      const i = members.indexOf(ws);
      if (i >= 0) members.splice(i, 1);
    },
    broadcast: (msg) => {
      for (const ws of members.slice()) {
        try { if (ws && ws.send) ws.send(msg); }
        catch (_) { /* dead client; reaped via onClose */ }
      }
    },
    size: () => members.length
  };
}

function _parsePath(p) {
  return p.split('/').filter(s => s.length > 0).map(s =>
    s.startsWith(':') ? { kind: 'cap', name: s.slice(1) }
                      : { kind: 'lit', value: s });
}

function _ssc_http_route(method, path) {
  return function(handler) {
    _routes.push({ method: method.toUpperCase(), path, pattern: _parsePath(path), handler });
  };
}
if (typeof globalThis !== 'undefined') globalThis.route = _ssc_http_route;

// onWebSocket(path)(handler) — handler receives a WebSocket object with
// `send` / `close` methods and `onMessage` / `onClose` registration.
// Path matching shares `_parsePath` with `route` (literal + `:name`
// captures), so `/chat/:room` works the same way for both.
//
// Two-arg form `onWebSocket(path, origins)(handler)` restricts the
// upgrade to requests whose `Origin:` header matches one of `origins`
// — a CSRF guard, since the browser same-origin policy does NOT
// block cross-site `new WebSocket(...)` calls.
//
// Three-arg form `onWebSocket(path, origins, protocols)(handler)` adds
// Sec-WebSocket-Protocol negotiation (RFC 6455 §1.9): server picks the
// first protocol it offers that's in the client's request; no match
// refuses with 400.  Required for `socket.io` / `graphql-ws` clients.
function onWebSocket(path, originsArg, protocolsArg, maxConnectionsArg, maxMessagesPerSecArg) {
  let origins           = [];
  let protocols         = [];
  let maxConnections    = 0;
  let maxMessagesPerSec = 0;
  if (Array.isArray(originsArg)) {
    origins        = originsArg;
    protocols      = Array.isArray(protocolsArg) ? protocolsArg : [];
    // Four-arg form: also a per-route active-connection cap.
    // 0 = unlimited; positive values refuse upgrades past the cap
    // with 503.  Composes with the process-wide cap.
    maxConnections = (typeof maxConnectionsArg === 'number' && maxConnectionsArg > 0)
      ? maxConnectionsArg : 0;
    // Five-arg form: per-connection rate cap (msgs/sec).  Overrun
    // closes the offending client with 1008.
    maxMessagesPerSec = (typeof maxMessagesPerSecArg === 'number' && maxMessagesPerSecArg > 0)
      ? maxMessagesPerSecArg : 0;
  } else if (originsArg !== undefined) {
    // Older call style: `onWebSocket(path)(handler)`, second arg is
    // already the handler.  Wrap it.
    _wsRoutes.push({
      pattern: _parsePath(path), handler: originsArg,
      origins: [], protocols: [], maxConnections: 0, maxMessagesPerSec: 0,
      auth: null, activeCount: 0,
    });
    return undefined;
  }
  return function(handler) {
    _wsRoutes.push({
      pattern: _parsePath(path), handler,
      origins, protocols, maxConnections, maxMessagesPerSec,
      auth: null, activeCount: 0,
    });
  };
}

// Pre-upgrade auth hook.  `authFn(req)` returns the auth payload
// (carried to `ws.user`) or null/undefined/None-like to refuse the
// upgrade with HTTP 401.  Hook runs synchronously on the upgrade
// dispatch; must be read-only over module-level state.  The hook's
// return convention matches the interpreter / JvmGen contracts:
//   - returning `{_type: '_Some', value: v}` accepts with `ws.user = Some(v)`
//   - returning `{_type: '_None'}` rejects with 401
//   - returning any other truthy value accepts with `ws.user = Some(that)`
//   - returning null / undefined rejects with 401
function onWebSocketAuth(path, authFn) {
  return function(handler) {
    _wsRoutes.push({
      pattern: _parsePath(path), handler,
      origins: [], protocols: [], maxConnections: 0, maxMessagesPerSec: 0,
      auth: authFn, activeCount: 0,
    });
  };
}

function _matchPath(pat, segs) {
  if (pat.length !== segs.length) return null;
  const params = new Map();
  for (let i = 0; i < pat.length; i++) {
    const p = pat[i];
    if (p.kind === 'lit') { if (p.value !== segs[i]) return null; }
    else                  { params.set(p.name, segs[i]); }
  }
  return params;
}

function _mkRequest(req, params, bodyBuf) {
  const headers = new Map();
  for (const [k, v] of Object.entries(req.headers)) {
    if (Array.isArray(v)) headers.set(k, v[0] ?? '');
    else                  headers.set(k, String(v ?? ''));
  }
  const u = new URL(req.url, 'http://localhost');
  const query = new Map();
  u.searchParams.forEach((v, k) => query.set(k, v));
  // `bodyBuf` is a Buffer (byte-exact). The UTF-8 view goes to req.body
  // for back-compat; multipart parsing uses the latin1 view, which is
  // 1-char-per-byte so JS string operations preserve binary content.
  const body       = bodyBuf.toString('utf8');
  const bodyLatin1 = bodyBuf.toString('latin1');
  const ct  = (headers.get('content-type') || headers.get('Content-Type') || '').toLowerCase();
  const ctOrig = headers.get('content-type') || headers.get('Content-Type') || '';
  const form  = new Map();
  const files = new Map();
  let _spooled = [];
  if (ct.startsWith('application/x-www-form-urlencoded')) {
    new URLSearchParams(body).forEach((v, k) => form.set(k, v));
  } else if (ct.startsWith('multipart/form-data')) {
    _spooled = _parseMultipart(ctOrig, bodyLatin1, form, files);
  }
  // Parse signed session cookie if present.
  const cookieHeader     = headers.get('cookie') || headers.get('Cookie') || '';
  const rawCookieSession = _parseCookieSession(cookieHeader);
  // In store mode the cookie payload is `{_ssid:"..."}`; look the real
  // payload up.  Otherwise the cookie *is* the payload.  The raw cookie
  // travels back as `_rawCookieSession` so serve()'s writer can spot
  // the prior SSID and evict it.
  let session;
  if (_sessionStoreEnabled) {
    const ssid = rawCookieSession.get('_ssid');
    session = ssid ? (_sessionStoreGet(ssid) || new Map()) : new Map();
  } else {
    session = rawCookieSession;
  }
  // Parse Authorization: Bearer <jwt> if present.
  const authHeader = headers.get('authorization') || headers.get('Authorization') || '';
  const bearerStr  = _bearerFromAuth(authHeader);
  const bearerToken = bearerStr ? _Some(bearerStr) : _None;
  const claims      = bearerStr ? jwtVerify(bearerStr) : _None;
  // Parse Authorization: Basic <b64(user:pass)> into a 2-element tuple.
  const basicAuth = _basicFromAuth(authHeader);
  // Lenient `req.json` — _Some(parsed) on success, _None on parse
  // failure or empty body.  Same shape as the interpreter / JVM
  // backends; handlers decide whether to short-circuit with a 400.
  const json = (body && body.length > 0)
    ? (() => { try { return _Some(_jsonConvert(JSON.parse(body))); } catch (e) { return _None; } })()
    : _None;
  return {
    _type:   'Request',
    method:  req.method,
    path:    u.pathname,
    params,
    query,
    headers,
    body,
    json,
    form,
    files,
    session,
    bearerToken,
    jwtClaims: claims,
    basicAuth,
    _rawCookieSession: rawCookieSession,
    _spooled,
  };
}

// HTTP Basic: Authorization: Basic <b64(user:pass)>.  Returns _Some
// of a 2-element tuple [user, pass] or _None.  The tuple is just an
// Array — scalascript codegen lowers `case (u, p) =>` to indexed reads.
function _basicFromAuth(h) {
  const t = (h || '').trim();
  if (t.length < 6 || t.substring(0, 6).toLowerCase() !== 'basic ') return _None;
  try {
    const decoded = Buffer.from(t.substring(6).trim(), 'base64').toString('utf-8');
    const colon   = decoded.indexOf(':');
    if (colon < 0) return _None;
    const tup = [decoded.substring(0, colon), decoded.substring(colon + 1)];
    tup._isTuple = true;
    return _Some(tup);
  } catch (e) { return _None; }
}

// `bodyLatin1` is the body decoded as Latin-1 (1 char = 1 byte), so the
// boundary split is byte-exact even when parts contain binary.  Text
// parts get UTF-8-decoded for `form`; file parts surface as an
// `UploadedFile` whose `bytes` field is still Latin-1 — round-trip back
// to bytes with `Buffer.from(bytes, 'latin1')`.
function _parseMultipart(contentType, bodyLatin1, form, files) {
  const spooled = [];
  const m = /boundary=([^;]+)/.exec(contentType);
  if (!m) return spooled;
  let b = m[1].trim();
  if (b.startsWith('"') && b.endsWith('"')) b = b.slice(1, -1);
  const sep = '--' + b;
  // Split on the boundary; first chunk (preamble) and last chunk (closing --)
  // contain no part data — skip both.
  const chunks = bodyLatin1.split(sep);
  for (let i = 1; i < chunks.length - 1; i++) {
    let part = chunks[i];
    if (part.startsWith('\r\n')) part = part.slice(2);
    if (part.endsWith('\r\n'))   part = part.slice(0, -2);
    const headEnd = part.indexOf('\r\n\r\n');
    if (headEnd < 0) continue;
    const headerText = part.slice(0, headEnd);
    const partBody   = part.slice(headEnd + 4); // still latin1
    const dispLine = headerText.split(/\r\n/).find(l => /^content-disposition/i.test(l)) || '';
    const ctLine   = headerText.split(/\r\n/).find(l => /^content-type/i.test(l)) || '';
    const ctype    = ctLine ? ctLine.split(':').slice(1).join(':').trim() : 'application/octet-stream';
    const nameM     = /name="([^"]*)"/.exec(dispLine);
    const filenameM = /filename="([^"]*)"/.exec(dispLine);
    if (!nameM) continue;
    if (filenameM) {
      let bytesVal = partBody, pathVal = '';
      if (Buffer.byteLength(partBody, 'latin1') > _spoolThreshold) {
        const _fs   = require('fs'), _path = require('path'), _crypto = require('crypto');
        const _tmp  = _path.join(_uploadDir, 'ssc-upload-' + _crypto.randomBytes(8).toString('hex'));
        _fs.writeFileSync(_tmp, Buffer.from(partBody, 'latin1'));
        spooled.push(_tmp);
        bytesVal = ''; pathVal = _tmp;
      }
      files.set(nameM[1], {
        _type:       'UploadedFile',
        name:        nameM[1],
        filename:    filenameM[1],
        contentType: ctype,
        size:        partBody.length,
        bytes:       bytesVal, // latin1-encoded; Buffer.from(., 'latin1') restores bytes
        path:        pathVal,
      });
    } else {
      // text part: re-decode the byte view as UTF-8
      form.set(nameM[1], Buffer.from(partBody, 'latin1').toString('utf8'));
    }
  }
  return spooled;
}

// ── Signed cookie sessions ────────────────────────────────────────────
// HMAC-SHA256 signed Map -> cookie value -> verified Map.  Mirrors the
// scalascript.server.SessionCookie helper used by the interpreter, so
// the same cookie packed on the JVM runtime is accepted on Node and
// vice-versa (given a matching SSC_SESSION_SECRET).

let _sessionSecretCache = null;
function _sessionSecret() {
  if (_sessionSecretCache !== null) return _sessionSecretCache;
  const env = (typeof process !== 'undefined' && process.env) ? process.env.SSC_SESSION_SECRET : undefined;
  if (env && env.length > 0) {
    _sessionSecretCache = Buffer.from(env, 'utf-8');
  } else {
    const crypto = require('crypto');
    _sessionSecretCache = crypto.randomBytes(32);
    if (typeof console !== 'undefined') console.error('[ssc] SSC_SESSION_SECRET not set; using a process-local random key. Sessions will not survive a server restart.');
  }
  return _sessionSecretCache;
}
function _b64urlEnc(buf) {
  return buf.toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
function _b64urlDec(s) {
  const pad = (4 - s.length % 4) % 4;
  return Buffer.from(s.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat(pad), 'base64');
}
function _hmacSha256(body) {
  const crypto = require('crypto');
  return crypto.createHmac('sha256', _sessionSecret()).update(body).digest();
}
function _sessionJson(map) {
  // Map<String, String> only — keys/values escaped for JSON.
  const esc = s => '"' + s.replace(/\\/g, '\\\\').replace(/"/g, '\\"')
    .replace(/\n/g, '\\n').replace(/\r/g, '\\r').replace(/\t/g, '\\t') + '"';
  const parts = [];
  if (_isMap(map)) map.forEach((v, k) => parts.push(esc(String(k)) + ':' + esc(String(v))));
  else for (const [k, v] of Object.entries(map || {})) parts.push(esc(String(k)) + ':' + esc(String(v)));
  return '{' + parts.join(',') + '}';
}
function _packSession(map) {
  const body = _b64urlEnc(Buffer.from(_sessionJson(map), 'utf-8'));
  const sig  = _b64urlEnc(_hmacSha256(body));
  return body + '.' + sig;
}
function _unpackSession(cookieValue) {
  const dot = cookieValue.indexOf('.');
  if (dot <= 0 || dot === cookieValue.length - 1) return new Map();
  const body = cookieValue.substring(0, dot);
  const sig  = cookieValue.substring(dot + 1);
  try {
    const expected = _b64urlEnc(_hmacSha256(body));
    // Constant-time-ish compare: never short-circuit on first byte.
    if (expected.length !== sig.length) return new Map();
    let diff = 0;
    for (let i = 0; i < expected.length; i++) diff |= expected.charCodeAt(i) ^ sig.charCodeAt(i);
    if (diff !== 0) return new Map();
    const json = _b64urlDec(body).toString('utf-8');
    const parsed = JSON.parse(json);
    const out = new Map();
    for (const [k, v] of Object.entries(parsed)) if (typeof v === 'string') out.set(k, v);
    return out;
  } catch (e) { return new Map(); }
}
function _parseCookieSession(headerValue) {
  if (!headerValue) return new Map();
  const pair = headerValue.split(';').map(s => s.trim()).find(s => s.startsWith('session='));
  if (!pair) return new Map();
  return _unpackSession(pair.substring('session='.length));
}
// ── Rate limiting ─────────────────────────────────────────────────────
// Fixed-window counter, process-local.  Same surface on all three
// backends.
const _rateLimitBuckets = new Map();
function rateLimit(key, limit, windowSeconds) {
  const now      = Date.now();
  const windowMs = windowSeconds * 1000;
  const current  = _rateLimitBuckets.get(key);
  if (!current || now - current.windowStartMs >= windowMs) {
    _rateLimitBuckets.set(key, { count: 1, windowStartMs: now });
    return 1 <= limit;
  }
  current.count += 1;
  return current.count <= limit;
}
function rateLimitReset(key) { _rateLimitBuckets.delete(key); }

// ── TOTP / 2FA (RFC 6238) ─────────────────────────────────────────────
// Compatible with Google Authenticator etc: HMAC-SHA1, 30-second step,
// 6-digit code, base32 secret.
const _totpAlphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
function _base32Encode(buf) {
  let out = '', bits = 0, val = 0;
  for (let i = 0; i < buf.length; i++) {
    val = (val << 8) | buf[i];
    bits += 8;
    while (bits >= 5) { bits -= 5; out += _totpAlphabet[(val >>> bits) & 0x1f]; }
  }
  if (bits > 0) out += _totpAlphabet[(val << (5 - bits)) & 0x1f];
  return out;
}
function _base32Decode(s) {
  const clean = String(s).toUpperCase().replace(/[= ]/g, '');
  const out = [];
  let bits = 0, val = 0;
  for (const c of clean) {
    const v = _totpAlphabet.indexOf(c);
    if (v < 0) continue;
    val = (val << 5) | v;
    bits += 5;
    if (bits >= 8) { bits -= 8; out.push((val >>> bits) & 0xff); }
  }
  return Buffer.from(out);
}
function totpSecret() {
  const crypto = require('crypto');
  return _base32Encode(crypto.randomBytes(20));
}
function totpUri(secret, account, issuer) {
  const labelIssuer = (issuer && issuer.length > 0) ? issuer + ':' : '';
  const label = encodeURIComponent(labelIssuer + account).replace(/\+/g, '%20');
  const params = [
    'secret=' + encodeURIComponent(secret),
    'algorithm=SHA1',
    'digits=6',
    'period=30',
  ];
  if (issuer && issuer.length > 0) params.push('issuer=' + encodeURIComponent(issuer));
  return 'otpauth://totp/' + label + '?' + params.join('&');
}
function _totpCodeAt(secret, counter) {
  const key = _base32Decode(secret);
  const buf = Buffer.alloc(8);
  const hi  = Math.floor(counter / 0x100000000);
  buf.writeUInt32BE(hi >>> 0, 0);
  buf.writeUInt32BE((counter >>> 0) >>> 0, 4);
  const crypto = require('crypto');
  const h   = crypto.createHmac('sha1', key).update(buf).digest();
  const off = h[h.length - 1] & 0x0f;
  const bin = ((h[off]     & 0x7f) << 24) |
              ((h[off + 1] & 0xff) << 16) |
              ((h[off + 2] & 0xff) <<  8) |
               (h[off + 3] & 0xff);
  return (bin % 1000000).toString().padStart(6, '0');
}
function totpCode(secret) {
  return _totpCodeAt(secret, Math.floor(Date.now() / 1000 / 30));
}
function totpValid(secret, code, skew) {
  const sk = (typeof skew === 'number') ? skew : 1;
  if (typeof code !== 'string' || code.length !== 6 || !/^\d+$/.test(code)) return false;
  const now = Math.floor(Date.now() / 1000 / 30);
  let ok = false;
  for (let i = -sk; i <= sk; i++) {
    const c = _totpCodeAt(secret, now + i);
    if (c.length === code.length) {
      let diff = 0;
      for (let j = 0; j < c.length; j++) diff |= c.charCodeAt(j) ^ code.charCodeAt(j);
      if (diff === 0) ok = true;
    }
  }
  return ok;
}

// ── Password hashing (PBKDF2-HMAC-SHA256) ─────────────────────────────
// Same algorithm + encoded format as scalascript.server.Password so
// a hash minted on one backend verifies on another.  Default 200k iter.
function hashPassword(password, iter) {
  const crypto = require('crypto');
  const it     = (typeof iter === 'number' && iter > 0) ? iter : 200000;
  const salt   = crypto.randomBytes(16);
  const hash   = crypto.pbkdf2Sync(password, salt, it, 32, 'sha256');
  const b64    = b => b.toString('base64').replace(/=+$/, '');
  return 'pbkdf2$iter=' + it + '$' + b64(salt) + '$' + b64(hash);
}
function verifyPassword(password, encoded) {
  try {
    const parts = String(encoded).split('$');
    if (parts.length !== 4 || parts[0] !== 'pbkdf2') return false;
    const iter     = parseInt(parts[1].replace('iter=', ''), 10);
    if (!Number.isFinite(iter) || iter <= 0) return false;
    const salt     = Buffer.from(parts[2], 'base64');
    const expected = Buffer.from(parts[3], 'base64');
    const crypto   = require('crypto');
    const actual   = crypto.pbkdf2Sync(password, salt, iter, expected.length, 'sha256');
    if (expected.length !== actual.length) return false;
    let diff = 0;
    for (let i = 0; i < expected.length; i++) diff |= expected[i] ^ actual[i];
    return diff === 0;
  } catch (e) { return false; }
}

// Cookie security config — secure flag + SameSite policy, set via
// the top-level cookieConfig(secure, sameSite) call.  Production
// HTTPS deployments should flip secure=true.
let _cookieSecure   = false;
let _cookieSameSite = 'Lax';
function cookieConfig(secure, sameSite) {
  _cookieSecure = !!secure;
  if (typeof sameSite === 'string' && (sameSite === 'Strict' || sameSite === 'Lax' || sameSite === 'None'))
    _cookieSameSite = sameSite;
}
function _buildSetCookie(map) {
  const base = 'Path=/; HttpOnly; SameSite=' + _cookieSameSite + (_cookieSecure ? '; Secure' : '');
  if (!map || (_isMap(map) ? map.size === 0 : Object.keys(map).length === 0))
    return 'session=; ' + base + '; Max-Age=0';
  return 'session=' + _packSession(map) + '; ' + base;
}

// ── Opt-in server-side session store ──────────────────────────────────
// Same semantics as scalascript.server.SessionStore: process-local Map
// keyed by random SSID, lazy TTL sweep on every 256th access.  When
// enabled, the cookie payload becomes `{"_ssid": "..."}` and the real
// data lives on the server.
const _sessionStore = new Map();
let _sessionStoreEnabled = false;
let _sessionStoreTtlMs   = 30 * 60 * 1000;
let _sessionAccessCount  = 0;
function useSessionStore(ttlSeconds) {
  _sessionStoreEnabled = true;
  if (typeof ttlSeconds === 'number' && ttlSeconds > 0) _sessionStoreTtlMs = ttlSeconds * 1000;
}
function _sessionStoreSweep() {
  const cutoff = Date.now() - _sessionStoreTtlMs;
  for (const [k, v] of _sessionStore) if (v.lastAccess < cutoff) _sessionStore.delete(k);
}
function _sessionStoreMaybeSweep() {
  if ((++_sessionAccessCount & 255) === 0) _sessionStoreSweep();
}
function _sessionStorePut(payload) {
  const crypto = require('crypto');
  const ssid   = _b64urlEnc(crypto.randomBytes(24));
  const m = new Map();
  if (_isMap(payload)) payload.forEach((v, k) => m.set(String(k), String(v)));
  else for (const [k, v] of Object.entries(payload || {})) m.set(String(k), String(v));
  _sessionStore.set(ssid, { payload: m, lastAccess: Date.now() });
  _sessionStoreMaybeSweep();
  return ssid;
}
function _sessionStoreGet(ssid) {
  const entry = _sessionStore.get(ssid);
  if (!entry) return null;
  if (Date.now() - entry.lastAccess > _sessionStoreTtlMs) {
    _sessionStore.delete(ssid);
    return null;
  }
  entry.lastAccess = Date.now();
  _sessionStoreMaybeSweep();
  return entry.payload;
}
function _sessionStoreDelete(ssid) { _sessionStore.delete(ssid); }

