package scalascript.codegen

import scalascript.ast.*
import scalascript.transform.EffectAnalysis
import scala.collection.mutable

/** JavaScript code generator for ScalaScript modules.
 *
 *  Walks the same Module type the interpreter uses, generating JS for each
 *  Scala code block. The generated JS can be embedded in HTML and run in-browser.
 */
object JsGen:

  enum Segment:
    case ScalaScriptJs(code: String)
    case ScalaSource(source: String)

  /** Generate JS source for all scalascript code blocks in a module. */
  def generate(
      module:     Module,
      baseDir:    Option[os.Path] = None,
      intrinsics: Map[scalascript.ir.QualifiedName, scalascript.backend.spi.IntrinsicImpl] = Map.empty
  ): String =
    val gen = new JsGen(baseDir, intrinsics)
    gen.genModule(module)

  /** Generate segments in document order, preserving scala/scalascript interleaving. */
  def generateSegmented(
      module:     Module,
      baseDir:    Option[os.Path] = None,
      intrinsics: Map[scalascript.ir.QualifiedName, scalascript.backend.spi.IntrinsicImpl] = Map.empty
  ): List[Segment] =
    val gen = new JsGen(baseDir, intrinsics)
    gen.genModuleSegmented(module)

  /** True if the module contains at least one scalascript block. */
  def hasBlocks(module: Module): Boolean =
    module.sections.exists(sectionHas)

  private def sectionHas(s: Section): Boolean =
    s.content.exists {
      case cb: Content.CodeBlock => Lang.isScalaScript(cb.lang)
      case _                     => false
    } || s.subsections.exists(sectionHas)

/** JS runtime preamble embedded in every generated page.  Split into
 *  two triple-quoted halves because the combined source exceeds the
 *  JVM's 64 KB string-literal limit; the `val JsRuntime` concatenation
 *  is declared after both halves so source-order init sees them. */
private val JsRuntimePart1a: String = """
let _output = [];
function _println(...args) { _output.push(args.map(_show).join(' ')); }
function _print(...args) { const s = args.map(_show).join(''); _output.push(s); }

// `_call(fn, ...args)` — wrapper for arbitrary callables in user code.
// Functions are invoked directly; Arrays and Maps route through `_dispatch`
// so `xs(i)` / `m(k)` behave like List / Map indexing.
function _call(fn, ...args) {
  if (typeof fn === 'function') return fn(...args);
  if (Array.isArray(fn) || fn instanceof Map) return _dispatch(fn, 'apply', args);
  // v1.5 Tier 5 #22 — `JsonValue` is a plain object with an `apply`
  // method, so `v("key")` and `v(0)` reach into the wrapper.
  if (fn && fn._type === 'JsonValue' && typeof fn.apply === 'function') return fn.apply(...args);
  throw new Error('not callable: ' + _show(fn));
}

// HTML / CSS interpolators — html"..." auto-escapes interpolated values
// unless wrapped in raw(...).
function _htmlEscape(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}
function raw(s)    { return { _type: '_Raw', html: _show(s) }; }
function escape(s) { return _htmlEscape(_show(s)); }
function _html_interp(v) {
  if (v && v._type === '_Raw') return v.html;
  return _htmlEscape(_show(v));
}

// `collectCss(comp1, comp2, ...)` — concatenate each argument's `css`
// field into one CSS string for a page-level <style>.  Convention helper
// for component-style .ssc files (see SPEC §8.4).  Anything without a
// String `css` field is silently skipped.
function collectCss(...parts) {
  return parts
    .map(p => (p && typeof p.css === 'string') ? p.css : '')
    .filter(s => s.length > 0)
    .join('\n');
}

// `collectJs(comp1, comp2, ...)` — same shape as collectCss, only it
// reads each argument's `js` field for stitching into a page <script>.
function collectJs(...parts) {
  return parts
    .map(p => (p && typeof p.js === 'string') ? p.js : '')
    .filter(s => s.length > 0)
    .join('\n');
}

// `scope("Card")` returns a small object with two helpers used by
// component-style .ssc files to suffix class names so two components
// can both use bare class names like `.title` without conflict.
//   const s = scope("Card");
//   s.css(".title { color: blue }")   // → ".title__Card { color: blue }"
//   s.cls("title")                    // → "title__Card"
// See SPEC §8.4 for the convention and trade-offs.
function scope(scopeName) {
  // Note: `.ident` matches anywhere in the input; CSS that contains
  // bare-identifier dots inside `url(...)` would be rewritten too — keep
  // URLs free of those dots if the rewrite matters.
  const rx = /\.([A-Za-z_][A-Za-z0-9_-]*)/g;
  return {
    name: scopeName,
    css: (s) => String(s).replace(rx, (_, cls) => '.' + cls + '__' + scopeName),
    cls: (n) => String(n) + '__' + scopeName,
  };
}

// ── Typed HTML DSL — `div(attr.cls := "hero", h1("hi"))` ───────────────
function _attr(key, value) { return { _type: 'Attr', name: key.name, value: _show(value) }; }
function _attrKey(name)    { return { _type: 'AttrKey', name }; }

const attr = {
  cls:         _attrKey('class'),
  id:          _attrKey('id'),
  href:        _attrKey('href'),
  src:         _attrKey('src'),
  alt:         _attrKey('alt'),
  name:        _attrKey('name'),
  title:       _attrKey('title'),
  style:       _attrKey('style'),
  type_:       _attrKey('type'),
  value_:      _attrKey('value'),
  placeholder: _attrKey('placeholder'),
  method_:     _attrKey('method'),
  action:      _attrKey('action'),
  target:      _attrKey('target'),
  rel:         _attrKey('rel'),
  for_:        _attrKey('for'),
  role:        _attrKey('role'),
  colspan:     _attrKey('colspan'),
  rowspan:     _attrKey('rowspan'),
  disabled:    _attrKey('disabled'),
};

function _renderChild(v) {
  if (v && v._type === '_Raw') return v.html;
  if (Array.isArray(v))        return v.map(_renderChild).join('');
  return _htmlEscape(_show(v));
}

function _renderTag(name, args, voidTag) {
  const attrs    = new Map();
  const children = [];
  function handle(v) {
    if (v && v._type === 'Attr')   { attrs.set(v.name, v.value); return; }
    if (Array.isArray(v))          { v.forEach(handle); return; }
    children.push(_renderChild(v));
  }
  args.forEach(handle);
  let attrStr = '';
  for (const [k, v] of attrs) attrStr += ` ${k}="${_htmlEscape(v)}"`;
  if (voidTag) return { _type: '_Raw', html: `<${name}${attrStr}>` };
  return { _type: '_Raw', html: `<${name}${attrStr}>${children.join('')}</${name}>` };
}

const _containerTags = [
  'html','head','body','title','style','script','main',
  'section','header','footer','nav','article','aside',
  'div','span','p','a','em','strong','small','code','pre',
  'h1','h2','h3','h4','h5','h6',
  'ul','ol','li','dl','dt','dd',
  'table','thead','tbody','tfoot','tr','td','th',
  'form','button','label','select','option','textarea',
  'figure','figcaption','blockquote',
];
const _voidTags = ['br','hr','img','input','link','meta'];

const __tagBindings = {};
_containerTags.forEach(t => { __tagBindings[t] = (...args) => _renderTag(t, args, false); });
_voidTags     .forEach(t => { __tagBindings[t] = (...args) => _renderTag(t, args, true);  });
// Promote to top-level globals so user code can call `div(...)` directly.
// Skip names the user has already bound (function declarations at the script
// top level are hoisted to globalThis before this preamble runs) so a
// `def section(...)` in user code still wins over the `section` tag.
for (const [k, v] of Object.entries(__tagBindings)) {
  if (globalThis[k] === undefined) globalThis[k] = v;
}

// Wall-clock for benchmarks — matches ScalaScript's `nanoTime()` primitive.
// Date.now() is millisecond-resolution, so we multiply to keep a single
// nanosecond-scale return type across backends.
function nanoTime() { return Date.now() * 1_000_000; }

// Environment variable reader — Node has process.env; the browser
// SPA target has no env, so getenv() falls back to its default in that
// case so the user code keeps compiling cleanly.
function getenv(key, defaultVal) {
  const env = (typeof process !== 'undefined' && process.env) ? process.env : {};
  const v   = env[key];
  if (v === undefined || v === null || v === '') return defaultVal ?? '';
  return v;
}

"""

private val JsRuntimePart1b: String = """
// ── REST routing + serve(port) ─────────────────────────────────────────
// Matches the interpreter / JVM-backend semantics: route(method, path)
// registers a closure, serve(port) starts Node's http.createServer and
// dispatches.  Node's event loop keeps the process alive — no Thread.join
// needed.  Browser-side execution is intentionally out of scope: this
// runtime require()s 'http' which only exists in Node.
const _routes = [];
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

function route(method, path) {
  return function(handler) {
    _routes.push({ method: method.toUpperCase(), path, pattern: _parsePath(path), handler });
  };
}

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
  if (ct.startsWith('application/x-www-form-urlencoded')) {
    new URLSearchParams(body).forEach((v, k) => form.set(k, v));
  } else if (ct.startsWith('multipart/form-data')) {
    _parseMultipart(ctOrig, bodyLatin1, form, files);
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
  const m = /boundary=([^;]+)/.exec(contentType);
  if (!m) return;
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
      files.set(nameM[1], {
        _type:       'UploadedFile',
        name:        nameM[1],
        filename:    filenameM[1],
        contentType: ctype,
        size:        partBody.length,
        bytes:       partBody, // latin1-encoded; Buffer.from(., 'latin1') restores bytes
      });
    } else {
      // text part: re-decode the byte view as UTF-8
      form.set(nameM[1], Buffer.from(partBody, 'latin1').toString('utf8'));
    }
  }
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
  if (map instanceof Map) map.forEach((v, k) => parts.push(esc(String(k)) + ':' + esc(String(v))));
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
  if (!map || (map instanceof Map ? map.size === 0 : Object.keys(map).length === 0))
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
  if (payload instanceof Map) payload.forEach((v, k) => m.set(String(k), String(v)));
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

"""

private val JsRuntimePart1c: String = """
// ── JWT (HS256) ────────────────────────────────────────────────────────
// Compact HS256 JWTs that match scalascript.server.Jwt byte-for-byte
// (header `{"alg":"HS256","typ":"JWT"}`, payload = JSON of a
// Map[String,String], signature over header.payload).  Secret picks up
// SSC_JWT_SECRET first, then falls back to SSC_SESSION_SECRET so a tiny
// deployment only needs one env var.

let _jwtSecretCache = null;
function _jwtSecret() {
  if (_jwtSecretCache !== null) return _jwtSecretCache;
  const env = (typeof process !== 'undefined' && process.env) ? process.env : {};
  const s = env.SSC_JWT_SECRET || env.SSC_SESSION_SECRET || '';
  if (s.length > 0) {
    _jwtSecretCache = Buffer.from(s, 'utf-8');
  } else {
    const crypto = require('crypto');
    _jwtSecretCache = crypto.randomBytes(32);
    if (typeof console !== 'undefined') console.error('[ssc] SSC_JWT_SECRET / SSC_SESSION_SECRET not set; JWTs signed with a process-local random key.');
  }
  return _jwtSecretCache;
}
function _hmacSha256Jwt(body) {
  const crypto = require('crypto');
  return crypto.createHmac('sha256', _jwtSecret()).update(body).digest();
}
// Lazy: avoid touching the Node-only Buffer at module-load time so the
// SPA bundle (no Buffer in the browser) can include this runtime as dead
// code without crashing on import.
let _jwtHeaderB64Cache = null;
function _jwtHeaderB64() {
  if (_jwtHeaderB64Cache !== null) return _jwtHeaderB64Cache;
  _jwtHeaderB64Cache = _b64urlEnc(Buffer.from('{"alg":"HS256","typ":"JWT"}', 'utf-8'));
  return _jwtHeaderB64Cache;
}
function jwtSign(claims) {
  // Accept either a Map or a plain object/case-class with string values.
  let m;
  if (claims instanceof Map) m = claims;
  else { m = new Map(); for (const [k, v] of Object.entries(claims || {})) m.set(String(k), String(v)); }
  const payloadB64 = _b64urlEnc(Buffer.from(_sessionJson(m), 'utf-8'));
  const headerB64  = _jwtHeaderB64();
  const sig        = _b64urlEnc(_hmacSha256Jwt(headerB64 + '.' + payloadB64));
  return headerB64 + '.' + payloadB64 + '.' + sig;
}
function jwtVerify(token) {
  if (typeof token !== 'string') return _None;
  const parts = token.split('.');
  if (parts.length !== 3) return _None;
  const [h, p, s] = parts;
  try {
    const expected = _b64urlEnc(_hmacSha256Jwt(h + '.' + p));
    if (expected.length !== s.length) return _None;
    let diff = 0;
    for (let i = 0; i < expected.length; i++) diff |= expected.charCodeAt(i) ^ s.charCodeAt(i);
    if (diff !== 0) return _None;
    const headerJson = _b64urlDec(h).toString('utf-8');
    if (!headerJson.includes('"alg":"HS256"')) return _None;
    const claims = JSON.parse(_b64urlDec(p).toString('utf-8'));
    // exp (Unix seconds) check, if present.
    if (claims.exp !== undefined) {
      const now = Math.floor(Date.now() / 1000);
      const exp = parseInt(claims.exp, 10);
      if (!Number.isFinite(exp) || exp < now) return _None;
    }
    const out = new Map();
    for (const [k, v] of Object.entries(claims)) if (typeof v === 'string') out.set(k, v);
    return _Some(out);
  } catch (e) { return _None; }
}
function _bearerFromAuth(h) {
  const t = (h || '').trim();
  if (t.length < 7) return '';
  if (t.substring(0, 7).toLowerCase() !== 'bearer ') return '';
  return t.substring(7).trim();
}

// ── JWT RS256 (asymmetric) ────────────────────────────────────────────
// Same wire format as scalascript.server.JwtRsa.  Reads keys from env:
//   SSC_JWT_PRIVATE_KEY (PKCS#8 RSA PEM) — signing
//   SSC_JWT_PUBLIC_KEY  (X.509 SPKI PEM) — verifying
// Use when verifier and signer are distinct processes.

const _jwtRsaHeaderB64 = (() => null)();   // computed lazily, see below.
let   _jwtRsaHeaderB64Cache = null;
function _jwtRsaHeader() {
  if (_jwtRsaHeaderB64Cache !== null) return _jwtRsaHeaderB64Cache;
  _jwtRsaHeaderB64Cache = _b64urlEnc(Buffer.from('{"alg":"RS256","typ":"JWT"}', 'utf-8'));
  return _jwtRsaHeaderB64Cache;
}
function jwtSignRsa(claims) {
  const priv = (typeof process !== 'undefined' && process.env)
    ? process.env.SSC_JWT_PRIVATE_KEY : '';
  if (!priv || !priv.length) throw new Error('SSC_JWT_PRIVATE_KEY is not set');
  let m;
  if (claims instanceof Map) m = claims;
  else { m = new Map(); for (const [k, v] of Object.entries(claims || {})) m.set(String(k), String(v)); }
  const payloadB64 = _b64urlEnc(Buffer.from(_sessionJson(m), 'utf-8'));
  const headerB64  = _jwtRsaHeader();
  const crypto     = require('crypto');
  const signer     = crypto.createSign('RSA-SHA256');
  signer.update(headerB64 + '.' + payloadB64);
  const sig = _b64urlEnc(signer.sign(priv));
  return headerB64 + '.' + payloadB64 + '.' + sig;
}
function jwtVerifyRsa(token) {
  const pub = (typeof process !== 'undefined' && process.env)
    ? process.env.SSC_JWT_PUBLIC_KEY : '';
  if (!pub || !pub.length) return _None;
  if (typeof token !== 'string') return _None;
  const parts = token.split('.');
  if (parts.length !== 3) return _None;
  const [h, p, s] = parts;
  try {
    const headerJson = _b64urlDec(h).toString('utf-8');
    if (!headerJson.includes('"alg":"RS256"')) return _None;
    const crypto = require('crypto');
    const ver    = crypto.createVerify('RSA-SHA256');
    ver.update(h + '.' + p);
    if (!ver.verify(pub, _b64urlDec(s))) return _None;
    const claims = JSON.parse(_b64urlDec(p).toString('utf-8'));
    if (claims.exp !== undefined) {
      const now = Math.floor(Date.now() / 1000);
      const exp = parseInt(claims.exp, 10);
      if (!Number.isFinite(exp) || exp < now) return _None;
    }
    const out = new Map();
    for (const [k, v] of Object.entries(claims)) if (typeof v === 'string') out.set(k, v);
    return _Some(out);
  } catch (e) { return _None; }
}

// ── OAuth2 helpers ────────────────────────────────────────────────────
// `oauthAuthorizeUrl(provider, clientId, redirectUri, state[, scope])`
// builds the provider's /authorize URL with the right defaults.
// `oauthExchangeCode(...)` POSTs to /token and returns the parsed
// response as a Map[String, String] wrapped in _Some, or _None on
// failure.  Uses Node's global fetch (Node 18+).
const _oauthProviders = {
  google: {
    authorizeUrl: 'https://accounts.google.com/o/oauth2/v2/auth',
    tokenUrl:     'https://oauth2.googleapis.com/token',
    userinfoUrl:  'https://www.googleapis.com/oauth2/v3/userinfo',
    defaultScope: 'openid email profile',
  },
  github: {
    authorizeUrl: 'https://github.com/login/oauth/authorize',
    tokenUrl:     'https://github.com/login/oauth/access_token',
    userinfoUrl:  'https://api.github.com/user',
    defaultScope: 'user:email',
  },
};
function oauthAuthorizeUrl(provider, clientId, redirectUri, state, scope) {
  const cfg  = _oauthProviders[provider];
  if (!cfg) throw new Error('unknown OAuth provider: ' + provider);
  const eff  = (scope && scope.length > 0) ? scope : (cfg.defaultScope || '');
  const params = new URLSearchParams();
  params.set('response_type', 'code');
  params.set('client_id', clientId);
  params.set('redirect_uri', redirectUri);
  params.set('state', state);
  if (eff) params.set('scope', eff);
  return cfg.authorizeUrl + '?' + params.toString();
}
// Node's fetch is async; the OAuth surface across backends is sync.
// We bridge the gap with worker_threads + Atomics.wait: spawn a Worker
// that does `await fetch(...)` and posts the result back through a
// MessageChannel; the main thread blocks on Atomics.wait until the
// Worker signals completion, then drains the message synchronously
// via receiveMessageOnPort.  Pure Node built-ins — no external binary,
// no extra dependencies.  Worker creation is ~50-100ms one-time;
// OAuth flows are rare (once per login) so we don't pool.
function _oauthSyncFetch(method, url, headers, body) {
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
    '    const r = await fetch(workerData.url, {',
    '      method:  workerData.method,',
    '      headers: workerData.headers,',
    '      body:    workerData.body,',
    '    });',
    '    const text = await r.text();',
    '    const ct   = r.headers.get(\'content-type\') || \'\';',
    '    msg = { ok: r.ok, status: r.status, contentType: ct, body: text };',
    '  } catch (e) {',
    '    msg = { ok: false, error: String(e) };',
    '  }',
    '  port.postMessage(msg);',
    '  Atomics.store(flag, 0, 1);',
    '  Atomics.notify(flag, 0);',
    '})();',
  ].join('\n');
  const worker = new Worker(workerSrc, {
    eval: true,
    workerData: { sab, port: port2, url, method, headers, body },
    transferList: [port2],
  });
  // Block the main thread until the worker bumps the flag.  Cap at 35s
  // so a dead provider doesn't hang the route handler forever.
  Atomics.wait(flag, 0, 0, 35_000);
  const drained = receiveMessageOnPort(port1);
  worker.terminate();
  port1.close();
  return drained ? drained.message : { ok: false, error: 'timeout' };
}
function _oauthDecodeBody(r) {
  if (!r.ok) return null;
  if (r.body.trim().startsWith('{') || (r.contentType || '').toLowerCase().includes('application/json'))
    try { return JSON.parse(r.body); } catch (_) { return null; }
  // application/x-www-form-urlencoded (GitHub default for /token)
  const parsed = new URLSearchParams(r.body);
  const obj = {};
  for (const [k, v] of parsed) obj[k] = v;
  return obj;
}
function _oauthMapFrom(obj) {
  if (!obj) return null;
  const m = new Map();
  for (const [k, v] of Object.entries(obj))
    m.set(k, (v === null || typeof v === 'object') ? JSON.stringify(v) : String(v));
  return m;
}

function oauthExchangeCode(provider, code, clientId, clientSecret, redirectUri) {
  const cfg = _oauthProviders[provider];
  if (!cfg) return _None;
  const params = new URLSearchParams();
  params.set('grant_type',    'authorization_code');
  params.set('code',          code);
  params.set('client_id',     clientId);
  params.set('client_secret', clientSecret);
  params.set('redirect_uri',  redirectUri);
  const r = _oauthSyncFetch('POST', cfg.tokenUrl,
    { 'Content-Type': 'application/x-www-form-urlencoded', 'Accept': 'application/json' },
    params.toString());
  const m = _oauthMapFrom(_oauthDecodeBody(r));
  return m ? _Some(m) : _None;
}
function oauthRegisterProvider(name, cfg) {
  const out = {};
  if (cfg instanceof Map) cfg.forEach((v, k) => out[String(k)] = String(v));
  else for (const [k, v] of Object.entries(cfg || {})) out[String(k)] = String(v);
  _oauthProviders[name] = { ..._oauthProviders[name], ...out };
}
function oauthRefreshToken(provider, refreshToken, clientId, clientSecret) {
  const cfg = _oauthProviders[provider];
  if (!cfg) return _None;
  const params = new URLSearchParams();
  params.set('grant_type',    'refresh_token');
  params.set('refresh_token', refreshToken);
  params.set('client_id',     clientId);
  params.set('client_secret', clientSecret);
  const r = _oauthSyncFetch('POST', cfg.tokenUrl,
    { 'Content-Type': 'application/x-www-form-urlencoded', 'Accept': 'application/json' },
    params.toString());
  const m = _oauthMapFrom(_oauthDecodeBody(r));
  return m ? _Some(m) : _None;
}
function oauthUserinfo(provider, accessToken) {
  const cfg = _oauthProviders[provider];
  if (!cfg || !cfg.userinfoUrl) return _None;
  const r = _oauthSyncFetch('GET', cfg.userinfoUrl,
    { 'Authorization': 'Bearer ' + accessToken,
      'Accept':        'application/json',
      'User-Agent':    'scalascript-oauth/0.6' },
    undefined);
  if (!r.ok || !r.body.trim().startsWith('{')) return _None;
  try {
    const m = _oauthMapFrom(JSON.parse(r.body));
    return m ? _Some(m) : _None;
  } catch (e) { return _None; }
}

// ── CSRF helpers ──────────────────────────────────────────────────────
// `csrfToken()` returns a fresh url-safe random string; the caller stashes
// it under "csrf" in the session and renders it in their form. `csrfValid`
// checks form `csrf` / `X-CSRF-Token` header against session.
function csrfToken() {
  const crypto = require('crypto');
  return _b64urlEnc(crypto.randomBytes(24));
}
function csrfValid(req) {
  if (!req) return false;
  const session = req.session instanceof Map ? req.session : new Map();
  const expected = session.get('csrf') || '';
  const form     = req.form instanceof Map ? req.form : new Map();
  let supplied = form.get('csrf');
  if (!supplied && req.headers instanceof Map) {
    for (const [k, v] of req.headers) if (k.toLowerCase() === 'x-csrf-token') { supplied = v; break; }
  }
  supplied = supplied || '';
  if (!expected || !supplied || expected.length !== supplied.length) return false;
  let diff = 0;
  for (let i = 0; i < expected.length; i++) diff |= expected.charCodeAt(i) ^ supplied.charCodeAt(i);
  return diff === 0;
}

// JSON-encode anything: strings pass through as raw JSON (so hand-built
// JSON strings keep working); other values get structural emission with
// proper escaping. Mirrors `toJson` in the interpreter.
function _toJson(v) {
  if (v === undefined || v === null) return 'null';
  if (typeof v === 'string')         return v;       // raw passthrough
  return _toJsonValue(v);
}
function _toJsonValue(v) {
  if (v === undefined || v === null) return 'null';
  if (typeof v === 'boolean')        return String(v);
  if (typeof v === 'number')         return String(v);
  if (typeof v === 'string')         return _jsonQuote(v);
  if (Array.isArray(v))              return '[' + v.map(_toJsonValue).join(',') + ']';
  if (v instanceof Map) {
    const parts = [];
    v.forEach((val, k) => parts.push(_jsonQuote(typeof k === 'string' ? k : _show(k)) + ':' + _toJsonValue(val)));
    return '{' + parts.join(',') + '}';
  }
  if (v && v._type === '_Some') return _toJsonValue(v.value);
  if (v && v._type === '_None') return 'null';
  if (v && v._type) {
    const parts = [];
    for (const [k, val] of Object.entries(v)) {
      if (k === '_type') continue;
      parts.push(_jsonQuote(k) + ':' + _toJsonValue(val));
    }
    return '{' + parts.join(',') + '}';
  }
  return _jsonQuote(String(v));
}
function _jsonQuote(s) {
  let out = '"';
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    if (c === 0x22) out += '\\"';
    else if (c === 0x5c) out += '\\\\';
    else if (c === 0x0a) out += '\\n';
    else if (c === 0x0d) out += '\\r';
    else if (c === 0x09) out += '\\t';
    else if (c === 0x08) out += '\\b';
    else if (c === 0x0c) out += '\\f';
    else if (c < 0x20) out += '\\u' + c.toString(16).padStart(4, '0');
    else out += s[i];
  }
  return out + '"';
}

// withSession/clearSession attach a `setSession` field; serve()'s
// response writer turns that into a Set-Cookie header.
function _withSession(resp, payload) {
  // Accept either a Map or a plain object/case-class.
  let m;
  if (payload instanceof Map) m = payload;
  else { m = new Map(); for (const [k, v] of Object.entries(payload || {})) m.set(String(k), String(v)); }
  return { ...resp, setSession: m, withSession: resp.withSession, clearSession: resp.clearSession };
}
function _clearSessionOn(resp) {
  return { ...resp, setSession: new Map(), withSession: resp.withSession, clearSession: resp.clearSession };
}
// `resp.withHeader(name, value)` — used by std/middleware.ssc to attach
// observability headers without rebuilding the Response by hand.
// Merges into the existing `headers` Map; later calls overwrite for
// the same key.
function _withHeaderOn(resp, name, value) {
  const headers = resp.headers instanceof Map ? new Map(resp.headers) : new Map();
  headers.set(String(name), String(value));
  return _mkResp({ ...resp, headers });
}
function _mkResp(fields) {
  const r = { _type: 'Response', ...fields };
  r.withSession  = function(payload)      { return _withSession(this, payload); };
  r.clearSession = function()             { return _clearSessionOn(this); };
  r.withHeader   = function(name, value)  { return _withHeaderOn(this, name, value); };
  return r;
}

const Response = {
  html(body)     { return _mkResp({ status: 200, headers: new Map([['Content-Type', 'text/html; charset=utf-8']]), body: _show(body) }); },
  text(body)     { return _mkResp({ status: 200, headers: new Map([['Content-Type', 'text/plain; charset=utf-8']]), body: _show(body) }); },
  json(body)     { return _mkResp({ status: 200, headers: new Map([['Content-Type', 'application/json']]), body: _toJson(body) }); },
  redirect(to)   { return _mkResp({ status: 302, headers: new Map([['Location', to]]), body: '' }); },
  notFound(body) { return _mkResp({ status: 404, headers: new Map(), body: _show(body ?? 'Not Found') }); },
  status(code, body) { return _mkResp({ status: code, headers: new Map(), body: _show(body ?? '') }); },
  basicAuthChallenge(realm) {
    const safe = String(realm).replace(/\\/g, '\\\\').replace(/"/g, '\\"');
    return _mkResp({ status: 401, headers: new Map([['WWW-Authenticate', 'Basic realm="' + safe + '"']]), body: 'Authentication required' });
  },
};

// Try to serve a static asset under the cwd; returns true when handled,
// false when the file is missing or disqualified.  Path-traversal is
// blocked via path.relative on resolved paths.
function _serveStatic(res, urlPath) {
  const path = require('path'), fs = require('fs');
  const cleaned = urlPath.replace(/^\//, '');
  if (!cleaned) return false;
  const rootDir = fs.realpathSync(process.cwd());
  let target;
  try { target = fs.realpathSync(path.join(rootDir, cleaned)); }
  catch { return false; }
  const rel = path.relative(rootDir, target);
  if (rel.startsWith('..') || path.isAbsolute(rel)) return false;
  let stat;
  try { stat = fs.statSync(target); }
  catch { return false; }
  if (!stat.isFile()) return false;
  if (target.endsWith('.ssc')) return false;
  const bytes = fs.readFileSync(target);
  res.writeHead(200, { 'Content-Type': _contentTypeFor(target) });
  res.end(bytes);
  return true;
}

function _contentTypeFor(name) {
  const lower = name.toLowerCase();
  if (lower.endsWith('.html') || lower.endsWith('.htm')) return 'text/html; charset=utf-8';
  if (lower.endsWith('.css'))   return 'text/css; charset=utf-8';
  if (lower.endsWith('.js') || lower.endsWith('.mjs')) return 'application/javascript; charset=utf-8';
  if (lower.endsWith('.json'))  return 'application/json; charset=utf-8';
  if (lower.endsWith('.txt') || lower.endsWith('.md')) return 'text/plain; charset=utf-8';
  if (lower.endsWith('.svg'))   return 'image/svg+xml';
  if (lower.endsWith('.png'))   return 'image/png';
  if (lower.endsWith('.jpg') || lower.endsWith('.jpeg')) return 'image/jpeg';
  if (lower.endsWith('.gif'))   return 'image/gif';
  if (lower.endsWith('.webp'))  return 'image/webp';
  if (lower.endsWith('.ico'))   return 'image/x-icon';
  if (lower.endsWith('.woff'))  return 'font/woff';
  if (lower.endsWith('.woff2')) return 'font/woff2';
  if (lower.endsWith('.wasm'))  return 'application/wasm';
  return 'application/octet-stream';
}

"""

private val JsRuntimePart1d: String = """
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
    if (!onMessage) return;
    const msg = opcode === 0x1 ? payload.toString('utf-8') : payload.toString('latin1');
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
let _httpBaseUrl = '';

function _httpSyncFetch(method, url, body, headers) {
  const effective = (_httpBaseUrl && !url.startsWith('http')) ? _httpBaseUrl + url : url;
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
    '    const opts = { method: workerData.method, headers: workerData.headers };',
    '    if (workerData.body) opts.body = workerData.body;',
    '    const r = await fetch(workerData.url, opts);',
    '    const text = await r.text();',
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
    workerData: { sab, port: port2, url: effective, method, headers: headers || {}, body: body || null },
    transferList: [port2],
  });
  Atomics.wait(flag, 0, 0, 35_000);
  const drained = receiveMessageOnPort(port1);
  worker.terminate(); port1.close();
  const r = drained ? drained.message : { status: 0, body: 'timeout', headers: {} };
  const hdrsMap = new Map(Object.entries(r.headers || {}));
  return { _type: 'Response', status: r.status, body: r.body, headers: hdrsMap };
}

function httpGet(url, headers) {
  const h = headers instanceof Map ? Object.fromEntries(headers.entries()) : (headers || {});
  return _httpSyncFetch('GET', url, null, h);
}

function httpPost(url, body, headers) {
  const h = headers instanceof Map ? Object.fromEntries(headers.entries()) : (headers || {});
  return _httpSyncFetch('POST', url, body, h);
}

function httpClient(baseUrl, block) {
  const prior = _httpBaseUrl;
  _httpBaseUrl = baseUrl;
  try { return block(); } finally { _httpBaseUrl = prior; }
}

function serve(port, _tlsCfg) {
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
    req.on('end', () => {
      try {
        const bodyBuf = Buffer.concat(chunks);
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
          let result;
          try {
            result = r.handler(request);
          } catch (e) {
            if (e && e._restValidation) {
              result = _mkResp({
                status: 400,
                headers: new Map([['Content-Type', 'text/plain; charset=utf-8']]),
                body: String(e.message || e)
              });
            } else {
              throw e;
            }
          }
          const headers = result && result.headers instanceof Map ? result.headers : new Map();
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
              if ((ssetting instanceof Map && ssetting.size === 0) ||
                  (!(ssetting instanceof Map) && Object.keys(ssetting || {}).length === 0)) {
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
  server.on('upgrade', (req, socket, _head) => _wsHandleUpgrade(req, socket));
  server.listen(port, () => console.log(`Listening on ${_useTls ? 'https' : 'http'}://localhost:${port}/`));
  _activeServer = server;
}

let _activeServer = null;

function stop() {
  if (_activeServer) { _activeServer.close(); _activeServer = null; }
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
  const h = r && r.headers instanceof Map ? new Map(r.headers) : new Map();
  h.set('Cache-Control', `public, max-age=${maxAge}`);
  if (etag) h.set('ETag', String(etag));
  return Object.assign({}, r, { headers: h });
}

function noCache(r) {
  const h = r && r.headers instanceof Map ? new Map(r.headers) : new Map();
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
    let _subproto = '';

    function _doClose() {
      if (!_closed) {
        _closed = true; _closing = true;
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
    ]);

    // RFC 6455 handshake
    const _crypto = require('crypto');
    const _wsKey  = _crypto.randomBytes(16).toString('base64');
    const _hdrs   = extraHeaders instanceof Map ? Object.fromEntries(extraHeaders.entries()) : (extraHeaders || {});
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
            _msgQueue.push(msg);
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
"""

private val JsRuntimePart2: String = """
function _show(v) {
  if (v === undefined) return '()';
  if (v === null) return 'null';
  if (typeof v === 'boolean') return String(v);
  if (typeof v === 'number') return String(v);
  if (typeof v === 'string') return v;
  if (Array.isArray(v)) {
    if (v._isTuple) return '(' + v.map(_show).join(', ') + ')';
    return 'List(' + v.map(_show).join(', ') + ')';
  }
  if (v instanceof Map) {
    if (v.size === 0) return 'Map()';
    return 'Map(' + [...v.entries()].map(([k,vv]) => _show(k)+' -> '+_show(vv)).join(', ') + ')';
  }
  if (v && v._type === '_Some') return 'Some(' + _show(v.value) + ')';
  if (v && v._type === '_None') return 'None';
  if (v && v._type === '_Raw')  return v.html;
  if (v && v._type === 'Lens' && v._path) {
    return 'Lens(_.' + v._path.join('.') + ')';
  }
  if (v && (v._type === 'Optional' || v._type === 'Traversal') && v._steps) {
    const parts = v._steps.map(s => s === '__some__' ? 'some' : s === '__each__' ? 'each' : s);
    return v._type + '(_.' + parts.join('.') + ')';
  }
  if (v && v._type === 'Prism' && v._variant) {
    return 'Prism[?, ' + v._variant + ']';
  }
  if (v && v._type === 'JsonValue') {
    // Hide the bundled accessor closures and render the wrapped inner
    // value so output matches the interpreter / JVM.
    return _show(v._inner);
  }
  if (v && v._type) {
    // Hide internal optic helpers (`get`, `set`, …) by skipping any value
    // whose type indicates it's an optic but no `_path`/`_steps` survived.
    const fields = Object.entries(v).filter(([k]) =>
      k !== '_type' && k !== '_path' && k !== '_steps' && k !== '_variant');
    if (!fields.length) return v._type;
    return v._type + '(' + fields.map(([,vv]) => _show(vv)).join(', ') + ')';
  }
  if (typeof v === 'function') return '<function>';
  return String(v);
}

const None = {_type: '_None'};
const _None = {_type: '_None'};
function Some(v) { return {_type: '_Some', value: v}; }
function _Some(v) { return {_type: '_Some', value: v}; }

const math = {
  sqrt: x => Math.sqrt(x), abs: x => Math.abs(x),
  pow: (x,y) => Math.pow(x,y), floor: x => Math.floor(x),
  ceil: x => Math.ceil(x), round: x => Math.round(x),
  Pi: Math.PI, E: Math.E
};

function assert(cond, msg) { if (!cond) throw new Error(msg != null ? _show(msg) : 'Assertion failed'); }

function _md(s) {
  const lines = s.split('\n');
  let start = 0, end = lines.length;
  while (start < end && lines[start].trim() === '') start++;
  while (end > start && lines[end-1].trim() === '') end--;
  const body = lines.slice(start, end);
  if (!body.length) return '';
  const indent = Math.min(...body.filter(l => l.trim() !== '').map(l => l.match(/^(\s*)/)[1].length));
  return body.map(l => l.slice(indent)).join('\n');
}

function doc(...args) { return {_type: '_Doc', parts: args}; }
function render(...args) {
  function toStr(v) {
    if (v && v._type === '_Doc') return v.parts.map(toStr).join('\n');
    return _show(v);
  }
  const parts = args.length === 1 && args[0] && args[0]._type === '_Doc' ? args[0].parts : args;
  _println(parts.map(toStr).join('\n'));
}

function List(...args) { return [...args]; }
List.fill     = (n) => (elem) => Array.from({length: n}, () => elem);
List.tabulate = (n) => (f)    => Array.from({length: n}, (_, i) => f(i));
List.range    = (from, until, step=1) => { const r=[]; for(let i=from;i<until;i+=step) r.push(i); return r; };
List.empty    = [];

function _Map(...pairs) {
  const m = new Map();
  pairs.forEach(p => m.set(p[0], p[1]));
  return m;
}

// ── `.copy(...)` helper — fills positional args against the object's
// own key order, then applies named overrides on top. Case-class
// instances emit `{_type, a, b, …}` whose Object.keys order matches
// the declared field order in V8 / modern Node.
function _copy(obj, positional, named) {
  const result = { ...obj, ...named };
  if (positional.length === 0) return result;
  const keys = Object.keys(obj).filter(k => k !== '_type');
  let posIdx = 0;
  for (const k of keys) {
    if (posIdx >= positional.length) break;
    if (k in named) continue;
    result[k] = positional[posIdx++];
  }
  return result;
}

// ── Lens runtime — get/set/modify/andThen over a static field path ────
function _lensGet(path, s) {
  let v = s;
  for (let i = 0; i < path.length; i++) v = v[path[i]];
  return v;
}
function _lensSet(path, s, v) {
  if (path.length === 0) return v;
  const head = path[0];
  const child = s[head];
  return { ...s, [head]: _lensSet(path.slice(1), child, v) };
}
function _makeLens(path) {
  const get      = (s) => _lensGet(path, s);
  const set      = (s, v) => _lensSet(path, s, v);
  const modify   = (s, f) => _lensSet(path, s, f(_lensGet(path, s)));
  const andThen  = (other) => {
    if (other && other._type === 'Lens' && other._path) {
      return _makeLens(path.concat(other._path));
    }
    if (other && other._type === 'Optional' && other._steps) {
      // Lens.andThen(Optional) → Optional. Lift field path to steps.
      return _makeOptional(path.concat(other._steps));
    }
    if (other && other._type === 'Traversal' && other._steps) {
      // Lens.andThen(Traversal) → Traversal.
      return _makeTraversal(path.concat(other._steps));
    }
    return _composeLens(_makeLens(path), other);
  };
  return { _type: 'Lens', _path: path, get, set, modify, andThen };
}
function _composeLens(a, b) {
  const get     = (s) => b.get(a.get(s));
  const set     = (s, v) => a.set(s, b.set(a.get(s), v));
  const modify  = (s, f) => a.modify(s, x => b.modify(x, f));
  const andThen = (other) => _composeLens({ _type: 'Lens', get, set, modify, andThen }, other);
  return { _type: 'Lens', get, set, modify, andThen };
}

// ── Optional runtime — partial optic for paths containing `.some` /
// `.index(i)` / `.at(k)` ────────────────────────────────────────────────
// Steps are an array of either strings (field name / "__some__" /
// "__each__") or small objects ({kind:'index',i}, {kind:'at',key}).
function _opticGetOption(steps, s) {
  let v = s;
  for (let i = 0; i < steps.length; i++) {
    const step = steps[i];
    if (typeof step === 'object') {
      if (step.kind === 'index') {
        if (!Array.isArray(v) || step.i < 0 || step.i >= v.length) return _None;
        v = v[step.i];
      } else if (step.kind === 'at') {
        if (!(v instanceof Map)) return _None;
        if (!v.has(step.key)) return _None;
        v = v.get(step.key);
      } else return _None;
    } else if (step === '__some__') {
      if (v && v._type === '_Some') v = v.value;
      else return _None;
    } else {
      if (v == null) return _None;
      v = v[step];
      if (v === undefined) return _None;
    }
  }
  return _Some(v);
}
function _opticSet(steps, s, v) {
  if (steps.length === 0) return v;
  const [head, ...rest] = steps;
  if (typeof head === 'object') {
    if (head.kind === 'index') {
      if (!Array.isArray(s) || head.i < 0 || head.i >= s.length) return s;
      const out = s.slice();
      out[head.i] = _opticSet(rest, s[head.i], v);
      return out;
    }
    if (head.kind === 'at') {
      if (!(s instanceof Map)) return s;
      if (!s.has(head.key)) return s;
      const out = new Map(s);
      out.set(head.key, _opticSet(rest, s.get(head.key), v));
      return out;
    }
    return s;
  }
  if (head === '__some__') {
    if (s && s._type === '_Some') return _Some(_opticSet(rest, s.value, v));
    return s;
  }
  if (s == null || s[head] === undefined) return s;
  return { ...s, [head]: _opticSet(rest, s[head], v) };
}
// Read the steps array off any path-optic (Lens uses _path, others _steps).
function _opticSteps(o) {
  if (!o) return null;
  if (o._steps) return o._steps;
  if (o._path)  return o._path;
  return null;
}
function _makeOptional(steps) {
  const getOption = (s) => _opticGetOption(steps, s);
  const set       = (s, v) => _opticSet(steps, s, v);
  const modify    = (s, f) => {
    const got = getOption(s);
    if (got && got._type === '_Some') return _opticSet(steps, s, f(got.value));
    return s;
  };
  const andThen = (other) => {
    const inner = _opticSteps(other);
    if (inner === null) {
      throw new Error('Optional.andThen(other): only path optic supported');
    }
    if (other._type === 'Traversal') return _makeTraversal(steps.concat(inner));
    return _makeOptional(steps.concat(inner));
  };
  return { _type: 'Optional', _steps: steps, getOption, set, modify, andThen };
}

// ── Traversal runtime — multi-foci optic for `.each` paths ────────────
function _opticGetAll(steps, s) {
  if (steps.length === 0) return [s];
  const [head, ...rest] = steps;
  if (typeof head === 'object') {
    if (head.kind === 'index') {
      if (!Array.isArray(s) || head.i < 0 || head.i >= s.length) return [];
      return _opticGetAll(rest, s[head.i]);
    }
    if (head.kind === 'at') {
      if (!(s instanceof Map) || !s.has(head.key)) return [];
      return _opticGetAll(rest, s.get(head.key));
    }
    return [];
  }
  if (head === '__some__') {
    if (s && s._type === '_Some') return _opticGetAll(rest, s.value);
    return [];
  }
  if (head === '__each__') {
    if (Array.isArray(s)) return s.flatMap(item => _opticGetAll(rest, item));
    return [];
  }
  if (s == null || s[head] === undefined) return [];
  return _opticGetAll(rest, s[head]);
}
function _opticModifyAll(steps, s, f) {
  if (steps.length === 0) return f(s);
  const [head, ...rest] = steps;
  if (typeof head === 'object') {
    if (head.kind === 'index') {
      if (!Array.isArray(s) || head.i < 0 || head.i >= s.length) return s;
      const out = s.slice();
      out[head.i] = _opticModifyAll(rest, s[head.i], f);
      return out;
    }
    if (head.kind === 'at') {
      if (!(s instanceof Map) || !s.has(head.key)) return s;
      const out = new Map(s);
      out.set(head.key, _opticModifyAll(rest, s.get(head.key), f));
      return out;
    }
    return s;
  }
  if (head === '__some__') {
    if (s && s._type === '_Some') return _Some(_opticModifyAll(rest, s.value, f));
    return s;
  }
  if (head === '__each__') {
    if (Array.isArray(s)) return s.map(item => _opticModifyAll(rest, item, f));
    return s;
  }
  if (s == null || s[head] === undefined) return s;
  return { ...s, [head]: _opticModifyAll(rest, s[head], f) };
}
function _makeTraversal(steps) {
  const getAll = (s) => _opticGetAll(steps, s);
  const modify = (s, f) => _opticModifyAll(steps, s, f);
  const set    = (s, v) => _opticModifyAll(steps, s, _ => v);
  const andThen = (other) => {
    const inner = _opticSteps(other);
    if (inner === null) {
      throw new Error('Traversal.andThen(other): only path optic supported');
    }
    return _makeTraversal(steps.concat(inner));
  };
  return { _type: 'Traversal', _steps: steps, getAll, modify, set, andThen };
}

// ── Prism runtime — sum-type optic, conditional get / set / modify ────
function _makePrism(variant) {
  const matches = (s) => s != null && s._type === variant;
  const getOption  = (s) => matches(s) ? _Some(s) : _None;
  const reverseGet = (v) => v;
  const set        = (s, v) => matches(s) ? v : s;
  const modify     = (s, f) => matches(s) ? f(s) : s;
  const andThen    = (other) => {
    if (other && other._type === 'Prism' && other._variant) {
      // Prism-Prism: dynamic typeName check collapses to the inner variant.
      return _makePrism(other._variant);
    }
    throw new Error('Prism.andThen(other): only Prism-Prism composition supported in this stage');
  };
  return { _type: 'Prism', _variant: variant, getOption, reverseGet, set, modify, andThen };
}

function _dispatch(obj, method, args) {
  if (Array.isArray(obj)) {
    switch(method) {
      case 'head': return obj[0];
      case 'last': return obj[obj.length-1];
      case 'tail': return obj.slice(1);
      case 'init': return obj.slice(0,-1);
      case 'length': case 'size': return obj.length;
      case 'indices': return Array.from({length: obj.length}, (_, i) => i);
      case 'apply': {
        const i = args[0];
        if (i < 0 || i >= obj.length) throw new Error('index ' + i + ' out of bounds for list of length ' + obj.length);
        return obj[i];
      }
      case 'isEmpty': return obj.length === 0;
      case 'nonEmpty': return obj.length > 0;
      case 'reverse': return [...obj].reverse();
      case 'distinct': return [...new Set(obj)];
      case 'toList': return obj;
      case 'toSet': return [...new Set(obj)];
      case 'sum': return obj.reduce((a,b)=>a+b, 0);
      case 'min': return Math.min(...obj);
      case 'max': return Math.max(...obj);
      case 'sorted': return [...obj].sort((a,b)=>a<b?-1:a>b?1:0);
      case 'flatten': return obj.flat();
      // Higher-order methods route through CPS-aware helpers so that a
      // callback returning a Free tree (effectful caller, e.g. inside
      // `runAsync { ... }`) is sequenced into a single Free instead of
      // leaving an array of Free nodes behind.  Pure callbacks see no
      // overhead — `_seq` short-circuits when nothing is Free.
      case 'map':     return _seqMap(obj, args[0]);
      case 'flatMap': return _seqFlatMap(obj, args[0]);
      case 'filter':    return _seqFilter(obj, args[0], false);
      case 'filterNot': return _seqFilter(obj, args[0], true);
      case 'foreach': case 'forEach': return _seqForeach(obj, args[0]);
      case 'exists':  return _seqExists(obj, args[0]);
      case 'forall':  return _seqForall(obj, args[0]);
      case 'find':    return _seqFind(obj, args[0]);
      case 'count':   return _seqCount(obj, args[0]);
      case 'take': return obj.slice(0, args[0]);
      case 'drop': return obj.slice(args[0]);
      case 'takeRight': return obj.slice(-args[0]);
      case 'dropRight': return obj.slice(0, -args[0]);
      case 'takeWhile': { const i = obj.findIndex(x => !args[0](x)); return i<0 ? obj : obj.slice(0,i); }
      case 'dropWhile': { const i = obj.findIndex(x => !args[0](x)); return i<0 ? [] : obj.slice(i); }
      case 'zip': return obj.map((v,i) => { const t = [v, args[0][i]]; t._isTuple=true; return t; });
      case 'zipWithIndex': return obj.map((v,i) => { const t = [v,i]; t._isTuple=true; return t; });
      case 'appended': return [...obj, args[0]];
      case 'prepended': return [args[0], ...obj];
      case 'contains': return obj.includes(args[0]);
      case 'indexOf': return obj.indexOf(args[0]);
      case 'mkString':
        if (!args.length) return obj.map(_show).join('');
        if (args.length === 1) return obj.map(_show).join(args[0]);
        return args[0] + obj.map(_show).join(args[1]) + args[2];
      case 'sortBy': return [...obj].sort((a,b) => { const fa=args[0](a),fb=args[0](b); return fa<fb?-1:fa>fb?1:0; });
      case 'groupBy': { const m=new Map(); obj.forEach(x=>{const k=args[0](x);if(!m.has(k))m.set(k,[]);m.get(k).push(x);}); return m; }
      case 'reduceLeft': return obj.reduce(args[0]);
      case 'foldLeft':  return (f) => _seqFoldLeft(obj, args[0], f);
      case 'foldRight': return (f) => _seqFoldLeft([...obj].reverse(), args[0], (acc, x) => f(x, acc));
      case 'sliding': { const n=args[0]; const r=[]; for(let i=0;i<=obj.length-n;i++) r.push(obj.slice(i,i+n)); return r; }
      case 'grouped': { const n=args[0]; const r=[]; for(let i=0;i<obj.length;i+=n) r.push(obj.slice(i,i+n)); return r; }
      case 'toString': return _show(obj);
      case '_1': return obj[0];
      case '_2': return obj[1];
      case '_3': return obj[2];
      case '_4': return obj[3];
    }
  }
  if (obj instanceof Map) {
    switch(method) {
      case 'get': return obj.has(args[0]) ? _Some(obj.get(args[0])) : _None;
      case 'getOrElse': return obj.has(args[0]) ? obj.get(args[0]) : args[1];
      case 'contains': return obj.has(args[0]);
      case 'apply':
        if (!obj.has(args[0])) throw new Error('key not found: ' + _show(args[0]));
        return obj.get(args[0]);
      case 'keys': return [...obj.keys()];
      case 'values': return [...obj.values()];
      case 'size': return obj.size;
      case 'isEmpty': return obj.size === 0;
      case 'nonEmpty': return obj.size > 0;
      case 'updated': { const m2=new Map(obj); m2.set(args[0],args[1]); return m2; }
      case 'removed': { const m2=new Map(obj); m2.delete(args[0]); return m2; }
      case 'mkString': return [...obj.entries()].map(([k,v])=>_show(k)+'->'+_show(v)).join(args[0]??'');
      case 'map': return [...obj.entries()].map(([k,v])=>args[0]([k,v]));
      case 'filter': return new Map([...obj.entries()].filter(([k,v])=>args[0]([k,v])));
      case 'toList': return [...obj.entries()].map(([k,v])=>{ const t=[k,v]; t._isTuple=true; return t; });
      case 'foreach': [...obj.entries()].forEach(([k,v])=>args[0]([k,v])); return undefined;
    }
  }
  if (obj && obj._type === 'Signal') {
    switch(method) {
      case 'get':   return obj.get();
      case 'set':   return obj.set(args[0]);
      case 'apply': return obj.apply();
    }
  }
  if (obj && obj._type === '_Some') {
    switch(method) {
      case 'map': return _Some(args[0](obj.value));
      case 'flatMap': return args[0](obj.value);
      case 'getOrElse': return obj.value;
      case 'isDefined': return true;
      case 'isEmpty': return false;
      case 'nonEmpty': return true;
      case 'get': return obj.value;
      case 'filter': return args[0](obj.value) ? obj : _None;
      case 'foreach': args[0](obj.value); return undefined;
      case 'toList': return [obj.value];
      case 'orElse': return obj;
      case 'contains': return obj.value === args[0];
    }
  }
  if (obj && obj._type === '_None') {
    switch(method) {
      case 'map': return _None;
      case 'flatMap': return _None;
      case 'getOrElse': return args[0];
      case 'isDefined': return false;
      case 'isEmpty': return true;
      case 'nonEmpty': return false;
      case 'get': throw new Error('None.get');
      case 'filter': return _None;
      case 'foreach': return undefined;
      case 'toList': return [];
      case 'orElse': return args[0];
      case 'contains': return false;
    }
  }
  if (typeof obj === 'string') {
    switch(method) {
      case 'length': case 'size': return obj.length;
      case 'toUpperCase': return obj.toUpperCase();
      case 'toLowerCase': return obj.toLowerCase();
      case 'trim': return obj.trim();
      case 'split': return obj.split(args[0]);
      case 'replace': return obj.replaceAll(args[0], args[1]);
      case 'startsWith': return obj.startsWith(args[0]);
      case 'endsWith': return obj.endsWith(args[0]);
      case 'contains': return obj.includes(args[0]);
      case 'toInt': return parseInt(obj);
      case 'toDouble': return parseFloat(obj);
      case 'toList': return [...obj];
      case 'charAt': return obj[args[0]];
      case 'codePointAt': return obj.codePointAt(args[0]);
      case 'substring': return obj.substring(args[0], args[1]);
      case 'take': return obj.slice(0, args[0]);
      case 'drop': return obj.slice(args[0]);
      case 'mkString': return obj;
      case 'reverse': return [...obj].reverse().join('');
      case 'isEmpty': return obj.length === 0;
      case 'nonEmpty': return obj.length > 0;
      case 'toString': return obj;
      case 'map': return [...obj].map(args[0]).join('');
      case 'trim': return obj.trim();
    }
  }
  if (typeof obj === 'number') {
    switch(method) {
      case 'toInt': case 'toLong': return Math.trunc(obj);
      case 'toDouble': return obj;
      case 'toString': return String(obj);
      case 'abs': return Math.abs(obj);
      case 'round': return Math.round(obj);
      case 'floor': return Math.floor(obj);
      case 'ceil': return Math.ceil(obj);
      case 'to': { const r=[]; for(let i=obj;i<=args[0];i++) r.push(i); return r; }
      case 'until': { const r=[]; for(let i=obj;i<args[0];i++) r.push(i); return r; }
    }
  }
  if (obj && typeof obj === 'object') {
    if (method === 'toString') return _show(obj);
    if (obj[method] !== undefined) {
      if (typeof obj[method] === 'function') {
        // If args is empty and the function takes args, return the function reference (eta-expansion)
        // If args is empty and the function takes no args, call it
        if (args.length === 0 && obj[method].length === 0) return obj[method]();
        if (args.length === 0) return obj[method];  // return as reference
        return obj[method](...args);
      }
      // A value field with args is a chained call: `req.form("k")` parses as
      // Apply(Select(req, form), "k") which lands here as
      // _dispatch(req, 'form', ["k"]).  Get the field, then `apply` the args.
      if (args.length > 0) return _dispatch(obj[method], 'apply', args);
      return obj[method];
    }
  }
  // Function-object dispatch: for companion objects (List.fill, etc.)
  if (typeof obj === 'function' && obj[method] !== undefined) {
    const val = obj[method];
    if (typeof val === 'function') return args.length ? val(...args) : val;
    return val;
  }
  // Extension method fallback: first look up by (receiver type, method) —
  // this is how typeclass instances disambiguate (Functor[List].map vs
  // Functor[Option].map).  Fall back to the older method-only registry
  // for extensions whose receiver type isn't known statically.
  const _ext_t = _typeOf(obj);
  if (typeof _extensions !== 'undefined') {
    const typed = _extensions[_ext_t + ':' + method];
    if (typed) return typed(obj, ...args);
    if (_extensions[method]) {
      const fns = _extensions[method];
      for (const fn of fns) {
        try { return fn(obj, ...args); } catch(e) { /* try next */ }
      }
    }
  }
  throw new Error('Method not found: ' + method + ' on ' + _show(obj));
}

function _typeOf(obj) {
  if (obj === null || obj === undefined) return 'Any';
  if (Array.isArray(obj)) return 'List';
  if (obj === _None) return 'Option';
  if (typeof obj === 'object' && obj._type === '_Some') return 'Option';
  if (typeof obj === 'object' && obj._type === '_None') return 'Option';
  if (obj instanceof Map) return 'Map';
  if (typeof obj === 'string') return 'String';
  if (typeof obj === 'number') return Number.isInteger(obj) ? 'Int' : 'Double';
  if (typeof obj === 'boolean') return 'Boolean';
  if (typeof obj === 'object' && obj._type) return obj._type;
  return 'Any';
}

const _extensions = {};
function _registerExt(method, fn, type) {
  // Two registries kept side-by-side:
  //   _extensions[method]            — legacy method-name lookup with try/catch
  //   _extensions[type + ':' + method] — direct (type, method) lookup
  // When `type` is omitted, only the legacy registry is populated.
  if (!_extensions[method]) _extensions[method] = [];
  _extensions[method].push(fn);
  if (type) _extensions[type + ':' + method] = fn;
}

// JSON read side — bridges native JS values into our runtime shape so
// the result of jsonParse(...) is indistinguishable from a literal
// constructed in user code: objects become Map (not plain objects),
// nulls become _None (matches Option literals), arrays stay arrays.
function _jsonConvert(v) {
  if (v === null || v === undefined) return _None;
  if (Array.isArray(v)) return v.map(_jsonConvert);
  if (typeof v === 'object') {
    const m = new Map();
    for (const k of Object.keys(v)) m.set(k, _jsonConvert(v[k]));
    return m;
  }
  return v;
}

function jsonParse(s) {
  try { return _jsonConvert(JSON.parse(s)); }
  catch (e) { throw new Error('jsonParse: ' + e.message); }
}

function jsonStringify(v) { return _toJsonStringify(v); }

// v1.5 Tier 5 #22 — indexed access on `Any`-typed JSON values.
// JS already lets users write `obj("name")` dynamically via `_dispatch`,
// but `lookup` / `lookupOpt` are the cross-backend escape hatch so the
// same source compiles cleanly on JvmGen too.  `lookup` throws on a
// missing key; `lookupOpt` returns `_None` / `_Some(v)`.
function _lookupKey(v, k) {
  if (v instanceof Map) {
    return v.has(k) ? v.get(k) : undefined;
  }
  if (Array.isArray(v)) {
    if (typeof k !== 'number') return undefined;
    return (k >= 0 && k < v.length) ? v[k] : undefined;
  }
  if (typeof v === 'string') {
    if (typeof k !== 'number') return undefined;
    return (k >= 0 && k < v.length) ? v.charAt(k) : undefined;
  }
  if (v && typeof v === 'object') {
    return Object.prototype.hasOwnProperty.call(v, k) ? v[k] : undefined;
  }
  return undefined;
}
function lookup(v, k) {
  const r = _lookupKey(v, k);
  if (r === undefined) throw new Error('lookup: key ' + _show(k) + ' not found in ' + _show(v));
  return r;
}
function lookupOpt(v, k) {
  const r = _lookupKey(v, k);
  return (r === undefined) ? _None : _Some(r);
}

// v1.5 Tier 5 #22 option (c) — `JsonValue` wrapper.  Same idiomatic
// apply / get / typed-accessor surface as INT / JVM.  Stored as a
// plain object with method properties; `_show` special-cases the
// `_type === 'JsonValue'` discriminator so output matches the
// other backends.
function _jsonValueWrap(inner) {
  const self = { _type: 'JsonValue', _inner: inner };
  self.apply = function(k) {
    if (typeof k === 'string') {
      if (inner instanceof Map) {
        if (inner.has(k)) return _jsonValueWrap(inner.get(k));
        throw new Error("JsonValue: no key '" + k + "'");
      }
      throw new Error("JsonValue.apply('" + k + "'): not an object");
    }
    if (typeof k === 'number') {
      if (Array.isArray(inner)) {
        if (k >= 0 && k < inner.length) return _jsonValueWrap(inner[k]);
        throw new Error("JsonValue: index " + k + " out of bounds (size=" + inner.length + ')');
      }
      throw new Error("JsonValue.apply(" + k + "): not an array");
    }
    throw new Error("JsonValue.apply(key: String | index: Int)");
  };
  self.get = function(k) {
    if (typeof k === 'string' && inner instanceof Map && inner.has(k))
      return _Some(_jsonValueWrap(inner.get(k)));
    if (typeof k === 'number' && Array.isArray(inner) && k >= 0 && k < inner.length)
      return _Some(_jsonValueWrap(inner[k]));
    return _None;
  };
  self.asString = function() {
    if (typeof inner === 'string') return inner;
    throw new Error('JsonValue.asString: expected string but got ' + _show(inner));
  };
  self.asInt = function() {
    if (typeof inner === 'number') return Math.trunc(inner);
    throw new Error('JsonValue.asInt: expected int but got ' + _show(inner));
  };
  self.asLong   = self.asInt;
  self.asDouble = function() {
    if (typeof inner === 'number') return inner;
    throw new Error('JsonValue.asDouble: expected double but got ' + _show(inner));
  };
  self.asBool = function() {
    if (typeof inner === 'boolean') return inner;
    throw new Error('JsonValue.asBool: expected bool but got ' + _show(inner));
  };
  self.asList = function() {
    if (Array.isArray(inner)) return inner.map(_jsonValueWrap);
    throw new Error('JsonValue.asList: expected list but got ' + _show(inner));
  };
  self.asMap = function() {
    if (inner instanceof Map) {
      const out = new Map();
      for (const [k, v] of inner) out.set(k, _jsonValueWrap(v));
      return out;
    }
    throw new Error('JsonValue.asMap: expected map but got ' + _show(inner));
  };
  self.raw    = function() { return inner; };
  self.isNull = function() { return inner === null || inner === undefined; };
  self.keys   = function() {
    if (inner instanceof Map) return [...inner.keys()];
    return [];
  };
  self.size = function() {
    if (Array.isArray(inner))      return inner.length;
    if (inner instanceof Map)      return inner.size;
    if (typeof inner === 'string') return inner.length;
    return 0;
  };
  return self;
}
function jsonRead(s) {
  if (typeof s === 'string') {
    try { return _jsonValueWrap(_jsonConvert(JSON.parse(s))); }
    catch (e) { throw new Error('jsonRead: ' + e.message); }
  }
  // Allow re-wrapping a previously-parsed value.
  return _jsonValueWrap(s);
}

// Tier 5 #20 — typed request validation primitives.  Each `requireX`
// throws a tagged Error which the serve() dispatch catches and turns
// into a 400 Bad Request.  Lookup walks form → query (JSON body lives
// behind req.json — handlers fish field values out themselves).
function _restValidationError(msg) {
  const e = new Error(msg);
  e._restValidation = true;
  return e;
}
function _restFieldOf(req, name) {
  if (req && req.form instanceof Map && req.form.has(name)) return req.form.get(name);
  if (req && req.query instanceof Map && req.query.has(name)) return req.query.get(name);
  return undefined;
}

// v1.5 Tier 5 #20 — validation collector stack.  Inside a `validate { … }`
// block the `require*` helpers record the error on the head of the stack
// and return a safe default, so the body keeps running and accumulates
// every problem in one pass.  Outside the block they throw as before
// and the serve() dispatcher emits a 400.
const _validationStack = [];
function _recordOrThrow(name, msg, defaultValue) {
  if (_validationStack.length > 0) {
    _validationStack[_validationStack.length - 1].set(name, msg);
    return defaultValue;
  }
  throw _restValidationError(msg);
}

function requireString(req, name) {
  const v = _restFieldOf(req, name);
  if (v === undefined) return _recordOrThrow(name, 'missing field: ' + name, '');
  return String(v);
}
function optionalString(req, name) {
  const v = _restFieldOf(req, name);
  return v === undefined ? _None : _Some(String(v));
}
function requireInt(req, name) {
  const v = _restFieldOf(req, name);
  if (v === undefined) return _recordOrThrow(name, 'missing field: ' + name, 0);
  const s = String(v).trim();
  if (!/^-?[0-9]+$/.test(s)) return _recordOrThrow(name, 'invalid integer for field: ' + name, 0);
  return Number(s);
}
function optionalInt(req, name) {
  const v = _restFieldOf(req, name);
  if (v === undefined) return _None;
  const s = String(v).trim();
  if (!/^-?[0-9]+$/.test(s)) return _None;
  return _Some(Number(s));
}
function requireDouble(req, name) {
  const v = _restFieldOf(req, name);
  if (v === undefined) return _recordOrThrow(name, 'missing field: ' + name, 0.0);
  const n = Number(String(v).trim());
  if (Number.isNaN(n)) return _recordOrThrow(name, 'invalid number for field: ' + name, 0.0);
  return n;
}
function optionalDouble(req, name) {
  const v = _restFieldOf(req, name);
  if (v === undefined) return _None;
  const n = Number(String(v).trim());
  return Number.isNaN(n) ? _None : _Some(n);
}
function requireBool(req, name) {
  const v = _restFieldOf(req, name);
  if (v === undefined) return _recordOrThrow(name, 'missing field: ' + name, false);
  const s = String(v).trim().toLowerCase();
  if (s === 'true'  || s === '1' || s === 'yes' || s === 'on')  return true;
  if (s === 'false' || s === '0' || s === 'no'  || s === 'off') return false;
  return _recordOrThrow(name, 'invalid boolean for field: ' + name, false);
}
function optionalBool(req, name) {
  const v = _restFieldOf(req, name);
  if (v === undefined) return _None;
  const s = String(v).trim().toLowerCase();
  if (s === 'true'  || s === '1' || s === 'yes' || s === 'on')  return _Some(true);
  if (s === 'false' || s === '0' || s === 'no'  || s === 'off') return _Some(false);
  return _None;
}

// Bounded numeric + enum require — inclusive `[min..max]` range, or a
// fixed list of allowed strings.  Same record-or-throw protocol so they
// compose inside `validate { … }`.
function requireRange(req, name, min, max) {
  const v = _restFieldOf(req, name);
  if (v === undefined) return _recordOrThrow(name, 'missing field: ' + name, min);
  const s = String(v).trim();
  if (!/^-?[0-9]+$/.test(s)) return _recordOrThrow(name, 'invalid integer for field: ' + name, min);
  const n = Number(s);
  if (n < min || n > max) return _recordOrThrow(name, 'out of range [' + min + '..' + max + '] for field: ' + name, min);
  return n;
}
// JS Number can't tell `1` from `1.0` at runtime, but the Scala / JVM
// interpolator formats `0.0..1.0` with the trailing `.0`.  Match that
// format for whole numbers so conformance output agrees byte-for-byte.
function _doubleFmt(n) {
  return Number.isInteger(n) ? (n.toFixed(1)) : String(n);
}
function requireRangeDouble(req, name, min, max) {
  const v = _restFieldOf(req, name);
  if (v === undefined) return _recordOrThrow(name, 'missing field: ' + name, min);
  const n = Number(String(v).trim());
  if (Number.isNaN(n)) return _recordOrThrow(name, 'invalid number for field: ' + name, min);
  if (n < min || n > max)
    return _recordOrThrow(name,
      'out of range [' + _doubleFmt(min) + '..' + _doubleFmt(max) + '] for field: ' + name, min);
  return n;
}
function requireOneOf(req, name, options) {
  const v = _restFieldOf(req, name);
  const fallback = (options && options.length > 0) ? options[0] : '';
  if (v === undefined) return _recordOrThrow(name, 'missing field: ' + name, fallback);
  const s = String(v);
  if (!options.includes(s)) {
    return _recordOrThrow(name,
      "invalid value '" + s + "' for field: " + name +
        ' (expected one of: ' + options.join(', ') + ')',
      fallback);
  }
  return s;
}

function validate(thunk) {
  const buf = new Map();
  _validationStack.push(buf);
  let result;
  try { result = thunk(); } finally { _validationStack.pop(); }
  if (buf.size > 0) {
    return { _type: 'Left', value: buf };
  }
  return { _type: 'Right', value: result };
}

function _toJsonStringify(v) {
  if (v === null || v === undefined) return 'null';
  if (v === _None || (v && v._type === '_None')) return 'null';
  if (v && v._type === '_Some') return _toJsonStringify(v.value);
  if (typeof v === 'string') return JSON.stringify(v);
  if (typeof v === 'number' || typeof v === 'boolean') return JSON.stringify(v);
  if (Array.isArray(v)) {
    if (v._isTuple === true) return '[' + v.map(_toJsonStringify).join(',') + ']';
    return '[' + v.map(_toJsonStringify).join(',') + ']';
  }
  if (v instanceof Map) {
    const parts = [];
    for (const [k, val] of v) parts.push(JSON.stringify(String(k)) + ':' + _toJsonStringify(val));
    return '{' + parts.join(',') + '}';
  }
  if (typeof v === 'object') {
    // Plain object — usually a case class (`_type` + named fields)
    // or a Request-shaped record.  Emit field-by-field, skipping the
    // `_type` discriminator since it's not user-visible JSON.
    const parts = [];
    for (const k of Object.keys(v)) {
      if (k === '_type' || k.startsWith('_')) continue;
      parts.push(JSON.stringify(k) + ':' + _toJsonStringify(v[k]));
    }
    return '{' + parts.join(',') + '}';
  }
  return JSON.stringify(String(v));
}

function _trampoline(fn, ...args) {
  let r = fn(...args);
  while (typeof r === 'object' && r !== null && r._isTailCall === true) {
    r = r.fn(...r.args);
  }
  return r;
}
function _tailCall(fn, ...args) { return {_isTailCall: true, fn, args}; }

// ── Algebraic effects runtime (Free Monad, trampolined) ────────────────────
//
// Trampolined Free Monad in the sense of Bjarnason 2012 — three shapes:
//
//   plain JS value     — doubles as Pure(value); no wrapper needed
//   _Perform           — { eff, op, args }; the rest of the computation
//                        lives in an outer _FlatMap
//   _FlatMap           — { sub, k }; explicit bind node
//
// `_bind(c, f)` is O(1): it just wraps in _FlatMap, never inspects. Stepping
// happens in `_run` / `_handle.interp` via a while-loop that right-associates
// `_FlatMap(_FlatMap(c, g), f)` → `_FlatMap(c, x => _FlatMap(g(x), f))`. The
// loop processes arbitrarily deep bind chains in O(1) JS stack.
//
// Handler semantics: each handled Perform is dispatched to its case with a
// real `resume` closure that invokes the captured continuation. resume may
// be called multiple times (multi-shot) — each call interprets a fresh
// branch. Side effects in the body run exactly once.

class _Perform {
  constructor(eff, op, args) { this.eff = eff; this.op = op; this.args = args; }
}
class _FlatMap {
  constructor(sub, k) { this.sub = sub; this.k = k; }
}

function _isFree(c) {
  return c !== null && typeof c === 'object' && (c instanceof _Perform || c instanceof _FlatMap);
}

// O(1) — never inspects, just wraps.
function _bind(c, f) { return new _FlatMap(c, f); }

function _perform(eff, op, args) { return new _Perform(eff, op, args); }

// Top-level runner — errors on any unhandled Perform.
function _run(c) {
  let current = c;
  while (true) {
    if (current instanceof _Perform) {
      throw new Error('Unhandled effect: ' + current.eff + '.' + current.op + ' (no handler in scope)');
    }
    if (current instanceof _FlatMap) {
      const sub = current.sub;
      if (sub instanceof _FlatMap) {
        // Right-associate: FlatMap(FlatMap(c2, g), f) → FlatMap(c2, x => FlatMap(g(x), f))
        const sub2 = sub.sub;
        const g    = sub.k;
        const f    = current.k;
        current = new _FlatMap(sub2, (x) => new _FlatMap(g(x), f));
      } else if (sub instanceof _Perform) {
        throw new Error('Unhandled effect: ' + sub.eff + '.' + sub.op + ' (no handler in scope)');
      } else {
        // Pure: step into the continuation
        current = current.k(sub);
      }
    } else {
      return current;  // plain value (Pure)
    }
  }
}

function _handle(bodyFn, handledOps, handlers) {
  const handled = new Set(handledOps);
  function interp(initial) {
    let current = initial;
    while (true) {
      if (current instanceof _Perform) {
        const key = current.eff + '.' + current.op;
        if (handled.has(key) && handlers[key]) {
          // bare Perform: no rest — resume returns the injected value as Pure
          const resume = (v) => v;
          current = handlers[key]([...current.args, resume]);
        } else {
          return current;  // propagate
        }
      } else if (current instanceof _FlatMap) {
        const sub = current.sub;
        if (sub instanceof _FlatMap) {
          const sub2 = sub.sub;
          const g    = sub.k;
          const f    = current.k;
          current = new _FlatMap(sub2, (x) => new _FlatMap(g(x), f));
        } else if (sub instanceof _Perform) {
          const key = sub.eff + '.' + sub.op;
          const f   = current.k;
          if (handled.has(key) && handlers[key]) {
            // Handled — resume runs the captured continuation through interp
            // so multi-shot branches produce values the case body can compose.
            const resume = (v) => interp(f(v));
            current = handlers[key]([...sub.args, resume]);
          } else {
            // Unhandled — propagate, but re-enter this handler's interp on
            // resume so nested Performs handled here still get dispatched.
            return new _FlatMap(sub, (v) => interp(f(v)));
          }
        } else {
          // Pure
          current = current.k(sub);
        }
      } else {
        return current;  // plain value (Pure)
      }
    }
  }
  return interp(bodyFn());
}
"""

val JsRuntime: String =
  JsRuntimePart1a + JsRuntimePart1b + JsRuntimePart1c + JsRuntimePart1d + JsRuntimePart2

/** Built-in `Async` effect runtime — concatenated onto `JsRuntime`.
 *  Lives in its own val because together with the rest of the runtime
 *  it overflows the JVM's 65 535-byte string-literal limit.  Same
 *  semantics as the interpreter and JvmGen: `delay` blocks via
 *  Atomics on Node, thunks passed to `async` / `parallel` run
 *  synchronously, results come back in declared order. */
val JsRuntimeAsync: String = """

// ── Async — built-in effect on top of the Free Monad ───────────────────────
//
// `Async.delay(ms)`, `Async.async(thunk)`, `Async.await(fut)`, and
// `Async.parallel(thunks)` produce `_Perform("Async", op, args)` nodes,
// indistinguishable from user-declared effect ops.  `_runAsync(bodyFn)`
// is the default handler — single-threaded so output is deterministic
// and byte-identical to the interpreter and JVM backends.  On Node
// `delay(ms)` blocks via `Atomics.wait` on a SharedArrayBuffer flag (no
// child process, no async coloring of caller code).  In the browser
// (no SharedArrayBuffer without crossOriginIsolation) we fall back to
// `Date.now()` spin, which is fine for the small delays used in demos.
const Async = {
  delay:    (ms)     => _perform('Async', 'delay',    [ms]),
  async:    (thunk)  => _perform('Async', 'async',    [thunk]),
  await:    (fut)    => _perform('Async', 'await',    [fut]),
  parallel: (thunks) => _perform('Async', 'parallel', [thunks]),
};
function Future(value) { return { _type: 'Future', value }; }

function _asyncSleep(ms) {
  if (!(ms > 0)) return;
  if (typeof SharedArrayBuffer !== 'undefined' && typeof Atomics !== 'undefined') {
    try {
      const sab = new SharedArrayBuffer(4);
      Atomics.wait(new Int32Array(sab), 0, 0, ms);
      return;
    } catch (_) { /* not allowed in main thread of some envs — fall through */ }
  }
  // Last-resort spin (browser without isolation, etc.).  Tight but
  // accurate enough for the small ms values typical in demos.
  const end = Date.now() + ms;
  while (Date.now() < end) { /* spin */ }
}

function _asyncDispatch(op, args, resume) {
  switch (op) {
    case 'delay': {
      const ms = args[0];
      if (typeof ms !== 'number') throw new Error('Async.delay(ms: Int)');
      _asyncSleep(ms);
      return resume(undefined);
    }
    case 'async': {
      const thunk = args[0];
      if (typeof thunk !== 'function') throw new Error('Async.async(thunk)');
      const v = _runAsyncInner(thunk());
      return resume(Future(v));
    }
    case 'await': {
      const fut = args[0];
      if (!fut || fut._type !== 'Future') throw new Error('Async.await(future)');
      return resume(fut.value);
    }
    case 'parallel': {
      const thunks = args[0];
      if (!Array.isArray(thunks)) throw new Error('Async.parallel(thunks: List[() => A])');
      const out = [];
      for (const t of thunks) {
        if (typeof t !== 'function') throw new Error('Async.parallel(thunks: List[() => A])');
        out.push(_runAsyncInner(t()));
      }
      return resume(out);
    }
    default:
      throw new Error('Unknown Async operation: ' + op);
  }
}

// Drive a Computation to a plain value, dispatching `Async.*` ops along
// the way.  Non-Async Performs propagate outward — useful for nested
// handlers (`runAsync` inside `handle`, or vice versa).
function _runAsyncInner(initial) {
  let current = initial;
  while (true) {
    if (current instanceof _Perform) {
      if (current.eff === 'Async') {
        current = _asyncDispatch(current.op, current.args, (v) => v);
      } else {
        return current;  // propagate
      }
    } else if (current instanceof _FlatMap) {
      const sub = current.sub;
      if (sub instanceof _FlatMap) {
        const sub2 = sub.sub, g = sub.k, f = current.k;
        current = new _FlatMap(sub2, (x) => new _FlatMap(g(x), f));
      } else if (sub instanceof _Perform) {
        const f = current.k;
        if (sub.eff === 'Async') {
          current = _asyncDispatch(sub.op, sub.args, (v) => _runAsyncInner(f(v)));
        } else {
          return new _FlatMap(sub, (v) => _runAsyncInner(f(v)));
        }
      } else {
        current = current.k(sub);
      }
    } else {
      return current;  // plain value
    }
  }
}

function _runAsync(bodyFn) { return _runAsyncInner(bodyFn()); }

// ── runAsyncParallel: same API on Node, single-threaded for now ────────────
//
// Real concurrency on Node would require `worker_threads` per `async` +
// `Atomics.wait` for `await` — viable but heavyweight (50-100ms worker
// creation, separate JS context per task).  v1.3 keeps the Node target
// single-threaded and aliases `_runAsyncParallel` to `_runAsync`: code
// written against the parallel handler still runs, just without the
// speedup.  The JVM backends provide real concurrency.
function _runAsyncParallel(bodyFn) { return _runAsyncInner(bodyFn()); }

// ── v1.6 Actors — Phase 1 cooperative scheduler ────────────────────────
//
// Mirrors the interpreter's `actorInterp` / `handleActorOp`.  Each
// `_runActors(bodyFn)` invocation creates a fresh actor registry,
// spawns `bodyFn` as the root actor, and drives all spawned actors
// cooperatively until quiescence.  Mailboxes are arrays; the
// scheduler is a simple round-robin ready queue.  `receive` with a
// non-empty matching head returns the case body's value; with an
// empty mailbox it suspends.  `receive(timeout = N)` arms a deadline;
// when the ready queue empties and no other progress is possible the
// scheduler sleeps until the earliest deadline, then resumes that
// actor with None.

function Pid(id) { return { _type: 'Pid', id }; }

const Actor = {
  spawn:     (thunk)       => _perform('Actor', 'spawn',     [thunk]),
  self:      ()            => _perform('Actor', 'self',      []),
  exit:      (pid, reason) => _perform('Actor', 'exit',      [pid, reason]),
  send:      (pid, msg)    => _perform('Actor', 'send',      [pid, msg]),
  receive_:  (specId)              => _perform('Actor', 'receive',   [specId]),
  receive_t: (specId, timeoutMs)   => _perform('Actor', 'receive_t', [specId, timeoutMs]),
  // v1.6 Phase 2 — supervision
  link:      (pid)         => _perform('Actor', 'link',      [pid]),
  monitor:   (pid)         => _perform('Actor', 'monitor',   [pid]),
  demonitor: (ref)         => _perform('Actor', 'demonitor', [ref]),
  trapExit:  (b)           => _perform('Actor', 'trapExit',  [b]),
};

// `receive { case … }` lowers to a registered matcher function whose
// integer token is passed to Actor.receive_/receive_t.  Codegen emits
// a fresh closure per `receive` site that returns either { matched:
// false } or { matched: true, body: () => <case-body Computation> }.
let _receiveSpecNext = 0;
const _receiveSpecs = new Map();
function _registerReceive(matcher) {
  const id = _receiveSpecNext++;
  _receiveSpecs.set(id, matcher);
  return id;
}

function _runActors(bodyFn) {
  // id -> { mailbox: [], pending: _Computation | null,
  //         blocked: { matcher, k, deadline, wrapSome } | null }
  const actors     = new Map();
  // Phase 2 supervision state (per _runActors invocation)
  const links      = new Map();   // id -> Set<id>
  const monitors   = new Map();   // watchedId -> Map<monRef, observerId>
  const trapExitMap = new Map();  // id -> bool
  let   nextMonRef = 0;

  const ready  = [];
  let   nextId = 0;
  let   rootResult = undefined;

  function spawnActor(thunk) {
    const id = nextId++;
    actors.set(id, { mailbox: [], pending: thunk(), blocked: null });
    ready.push(id);
    return Pid(id);
  }
  const rootId = spawnActor(bodyFn);

  function tryDeliver(state, matcher, wrapSome) {
    while (state.mailbox.length > 0) {
      const msg = state.mailbox[0];
      const r   = matcher(msg);
      if (r && r.matched) {
        state.mailbox.shift();
        const bodyC = r.body();
        return wrapSome
          ? new _FlatMap(bodyC, (v) => _Some(v))
          : bodyC;
      }
      state.mailbox.shift();  // dead-letter
    }
    return null;
  }

  function tryWakeBlocked(id) {
    const st = actors.get(id);
    if (!st || !st.blocked) return;
    const b = st.blocked;
    const delivered = tryDeliver(st, b.matcher, b.wrapSome);
    if (delivered !== null) {
      st.pending = new _FlatMap(delivered, b.k);
      st.blocked = null;
      ready.push(id);
    }
  }

  // Kill actor targetId with reason, propagate through links, fire monitors.
  // Idempotent: if actor not in `actors` map it's already dead.
  function killActor(targetId, reason) {
    if (!actors.has(targetId)) return;
    actors.delete(targetId);
    trapExitMap.delete(targetId);

    const deadPid = Pid(targetId);

    // Notify linked actors.
    const linkedSet = links.get(targetId);
    links.delete(targetId);
    if (linkedSet) {
      for (const linkedId of linkedSet) {
        const ls = links.get(linkedId);
        if (ls) ls.delete(targetId);
        if (trapExitMap.get(linkedId)) {
          const st = actors.get(linkedId);
          if (st) {
            st.mailbox.push({ _type: 'Exit', from: deadPid, reason });
            tryWakeBlocked(linkedId);
          }
        } else {
          killActor(linkedId, reason);
        }
      }
    }

    // Fire Down to all monitors watching targetId.
    const monMap = monitors.get(targetId);
    monitors.delete(targetId);
    if (monMap) {
      for (const [monRef, observerId] of monMap) {
        const st = actors.get(observerId);
        if (st) {
          st.mailbox.push({ _type: 'Down', ref: monRef, from: deadPid, reason });
          tryWakeBlocked(observerId);
        }
      }
    }
  }

  function handleActorOp(id, state, op, args, k) {
    switch (op) {
      case 'spawn': {
        const childPid = spawnActor(args[0]);
        return { suspend: false, next: k(childPid) };
      }
      case 'self':
        return { suspend: false, next: k(Pid(id)) };
      case 'send': {
        const target = args[0];
        if (target && target._type === 'Pid') {
          const ts = actors.get(target.id);
          if (ts) {
            ts.mailbox.push(args[1]);
            if (ts.blocked) {
              const b = ts.blocked;
              const delivered = tryDeliver(ts, b.matcher, b.wrapSome);
              if (delivered !== null) {
                ts.pending = new _FlatMap(delivered, b.k);
                ts.blocked = null;
                ready.push(target.id);
              }
            }
          }
        }
        return { suspend: false, next: k(undefined) };
      }
      case 'exit': {
        const target = args[0];
        if (target && target._type === 'Pid') {
          killActor(target.id, args[1]);
        }
        // The caller may have been killed by a link; check.
        if (!actors.has(id)) return { suspend: true };
        return { suspend: false, next: k(undefined) };
      }
      case 'receive': {
        const matcher = _receiveSpecs.get(args[0]);
        const c = tryDeliver(state, matcher, false);
        if (c !== null) return { suspend: false, next: new _FlatMap(c, k) };
        state.blocked = { matcher, k, wrapSome: false, deadline: null };
        return { suspend: true };
      }
      case 'receive_t': {
        const matcher = _receiveSpecs.get(args[0]);
        const c = tryDeliver(state, matcher, true);
        if (c !== null) return { suspend: false, next: new _FlatMap(c, k) };
        state.blocked = { matcher, k, wrapSome: true, deadline: Date.now() + args[1] };
        return { suspend: true };
      }
      // ── v1.6 Phase 2 — supervision ────────────────────────────────────
      case 'link': {
        const target = args[0];
        if (target && target._type === 'Pid') {
          const targetId = target.id;
          if (actors.has(targetId)) {
            if (!links.has(id))       links.set(id,       new Set());
            if (!links.has(targetId)) links.set(targetId, new Set());
            links.get(id).add(targetId);
            links.get(targetId).add(id);
          } else {
            // Target already dead — noproc exit signal.
            const noproc = { _type: 'noproc' };
            if (trapExitMap.get(id)) {
              const st = actors.get(id);
              if (st) st.mailbox.push({ _type: 'Exit', from: target, reason: noproc });
            } else {
              killActor(id, noproc);
            }
          }
        }
        if (!actors.has(id)) return { suspend: true };
        return { suspend: false, next: k(undefined) };
      }
      case 'monitor': {
        const target = args[0];
        if (target && target._type === 'Pid') {
          const targetId = target.id;
          const monRef = nextMonRef++;
          if (actors.has(targetId)) {
            if (!monitors.has(targetId)) monitors.set(targetId, new Map());
            monitors.get(targetId).set(monRef, id);
          } else {
            // Already dead — immediate Down(noproc).
            const st = actors.get(id);
            if (st) {
              st.mailbox.push({ _type: 'Down', ref: monRef, from: target, reason: { _type: 'noproc' } });
              tryWakeBlocked(id);
            }
          }
          return { suspend: false, next: k(monRef) };
        }
        return { suspend: false, next: k(-1) };
      }
      case 'demonitor': {
        const monRef = args[0];
        for (const [, monMap] of monitors) monMap.delete(monRef);
        return { suspend: false, next: k(undefined) };
      }
      case 'trapExit': {
        trapExitMap.set(id, args[0] === true || args[0]);
        return { suspend: false, next: k(undefined) };
      }
      default:
        throw new Error('Unknown Actor op: ' + op);
    }
  }

  function stepActor(id) {
    const state = actors.get(id);
    if (!state) return;
    let current = state.pending;
    state.pending = null;
    while (true) {
      if (current instanceof _Perform) {
        if (current.eff !== 'Actor')
          throw new Error('Unhandled effect inside actor: ' + current.eff + '.' + current.op);
        const r = handleActorOp(id, state, current.op, current.args, (v) => v);
        if (r.suspend) return;
        current = r.next;
      } else if (current instanceof _FlatMap) {
        const sub = current.sub;
        if (sub instanceof _FlatMap) {
          const sub2 = sub.sub, g = sub.k, f = current.k;
          current = new _FlatMap(sub2, (x) => new _FlatMap(g(x), f));
        } else if (sub instanceof _Perform) {
          if (sub.eff !== 'Actor')
            throw new Error('Unhandled effect inside actor: ' + sub.eff + '.' + sub.op);
          const r = handleActorOp(id, state, sub.op, sub.args, current.k);
          if (r.suspend) return;
          current = r.next;
        } else {
          current = current.k(sub);
        }
      } else {
        // Pure value — actor done normally; fire monitors with reason "normal".
        if (id === rootId) rootResult = current;
        const myPid = Pid(id);
        const monMap = monitors.get(id);
        monitors.delete(id);
        if (monMap) {
          for (const [monRef, observerId] of monMap) {
            const st = actors.get(observerId);
            if (st) {
              st.mailbox.push({ _type: 'Down', ref: monRef, from: myPid, reason: 'normal' });
              tryWakeBlocked(observerId);
            }
          }
        }
        // Clean up link entries (normal exit does not propagate link signals).
        const linkedSet = links.get(id);
        links.delete(id);
        if (linkedSet) {
          for (const linkedId of linkedSet) {
            const ls = links.get(linkedId);
            if (ls) ls.delete(id);
          }
        }
        actors.delete(id);
        return;
      }
    }
  }

  while (true) {
    if (ready.length > 0) {
      const id = ready.shift();
      const state = actors.get(id);
      if (state && state.pending !== null) stepActor(id);
    } else {
      // Quiescence — but timeout-armed receives may still fire.
      let earliest = null;
      for (const [aid, st] of actors) {
        if (st && st.blocked && st.blocked.deadline != null) {
          if (earliest === null || st.blocked.deadline < earliest.d)
            earliest = { id: aid, d: st.blocked.deadline };
        }
      }
      if (!earliest) break;
      const sleepFor = earliest.d - Date.now();
      if (sleepFor > 0) _asyncSleep(sleepFor);
      const s = actors.get(earliest.id);
      if (s && s.blocked) {
        const kk = s.blocked.k;
        s.blocked = null;
        s.pending = kk(_None);
        ready.push(earliest.id);
      }
    }
  }
  return rootResult;
}

// ── Storage: built-in key-value effect ────────────────────────────────────
//
// `Storage.{get,put,remove,has,keys}` produce `_Perform("Storage", op,
// args)` nodes; `_runStorage(bodyFn, path)` is the handler — when
// `path` is non-null it hydrates from / flushes to a JSON file on
// every mutation (file-backed mode), otherwise it stays in memory
// (ephemeral mode, for tests).  Same Free-Monad walking shape as
// `_runAsync`; non-Storage Performs propagate outward so an outer
// handler picks them up.
const Storage = {
  get:    (key)        => _perform('Storage', 'get',    [key]),
  put:    (key, value) => _perform('Storage', 'put',    [key, value]),
  remove: (key)        => _perform('Storage', 'remove', [key]),
  has:    (key)        => _perform('Storage', 'has',    [key]),
  keys:   ()           => _perform('Storage', 'keys',   []),
};

function _storageDefaultPath() {
  if (typeof process !== 'undefined' && process.env && process.env.SSC_STORAGE_PATH) {
    return process.env.SSC_STORAGE_PATH;
  }
  return './ssc-storage.json';
}

function _storageLoad(path, state) {
  if (typeof require === 'undefined') return;
  try {
    const fs = require('fs');
    if (!fs.existsSync(path)) return;
    const src = fs.readFileSync(path, 'utf-8');
    const obj = JSON.parse(src);
    for (const [k, v] of Object.entries(obj)) state.set(k, String(v));
  } catch (e) { /* corrupt file — start empty */ }
}

function _storageSave(path, state) {
  if (typeof require === 'undefined') return;
  const fs  = require('fs');
  const obj = {};
  for (const [k, v] of state) obj[k] = v;
  fs.writeFileSync(path, JSON.stringify(obj));
}

function _runStorage(bodyFn, path) {
  const state = new Map();
  if (path) _storageLoad(path, state);
  function flush() { if (path) _storageSave(path, state); }

  function dispatch(op, args, resume) {
    switch (op) {
      case 'get': {
        const k = args[0];
        return resume(state.has(k) ? _Some(state.get(k)) : _None);
      }
      case 'put': {
        const k = args[0], v = args[1];
        state.set(k, _show(v));
        flush();
        return resume(undefined);
      }
      case 'remove': {
        state.delete(args[0]);
        flush();
        return resume(undefined);
      }
      case 'has':  return resume(state.has(args[0]));
      case 'keys': return resume([...state.keys()]);
      default:     throw new Error('Unknown Storage operation: ' + op);
    }
  }

  function interp(initial) {
    let current = initial;
    while (true) {
      if (current instanceof _Perform) {
        if (current.eff === 'Storage') {
          current = dispatch(current.op, current.args, (v) => v);
        } else { return current; }
      } else if (current instanceof _FlatMap) {
        const sub = current.sub;
        if (sub instanceof _FlatMap) {
          const sub2 = sub.sub, g = sub.k, f = current.k;
          current = new _FlatMap(sub2, (x) => new _FlatMap(g(x), f));
        } else if (sub instanceof _Perform) {
          const f = current.k;
          if (sub.eff === 'Storage') {
            current = dispatch(sub.op, sub.args, (v) => interp(f(v)));
          } else {
            return new _FlatMap(sub, (v) => interp(f(v)));
          }
        } else {
          current = current.k(sub);
        }
      } else {
        return current;
      }
    }
  }
  return interp(bodyFn());
}

// ── CPS-aware collection helpers ──────────────────────────────────────────
//
// When a higher-order method like `xs.map(fn)` is called inside an
// effectful context, `fn(x)` may return a Free tree instead of a plain
// value.  These helpers detect that and stitch the per-element Free
// values into a single sequenced Free that yields the final array.
// Pure callbacks pass straight through with no overhead.
//
// `_seq(comps)` → sequence an array of (Free | value); returns either
// the plain array (when none are Free) or a Free that yields it.
function _seq(comps) {
  let anyFree = false;
  for (let i = 0; i < comps.length; i++) if (_isFree(comps[i])) { anyFree = true; break; }
  if (!anyFree) return comps;
  function loop(i, acc) {
    if (i === comps.length) return acc;
    return _bind(comps[i], (v) => { acc.push(v); return loop(i + 1, acc); });
  }
  return loop(0, []);
}

function _seqMap(arr, fn)        { return _seq(arr.map(x => fn(x))); }
function _seqFlatMap(arr, fn)    {
  const s = _seqMap(arr, fn);
  if (_isFree(s)) return _bind(s, (rs) => rs.flat());
  return s.flat();
}
function _seqFilter(arr, fn, neg) {
  const flags = arr.map(x => fn(x));
  const seq   = _seq(flags);
  const pick  = (bs) => arr.filter((_, i) => neg ? !bs[i] : bs[i]);
  if (_isFree(seq)) return _bind(seq, pick);
  return pick(seq);
}
function _seqForeach(arr, fn) {
  const comps = arr.map(x => fn(x));
  const s     = _seq(comps);
  if (_isFree(s)) return _bind(s, () => undefined);
  return undefined;
}
function _seqExists(arr, fn) {
  const seq = _seq(arr.map(x => fn(x)));
  if (_isFree(seq)) return _bind(seq, (bs) => bs.some(b => b));
  return seq.some(b => b);
}
function _seqForall(arr, fn) {
  const seq = _seq(arr.map(x => fn(x)));
  if (_isFree(seq)) return _bind(seq, (bs) => bs.every(b => b));
  return seq.every(b => b);
}
function _seqCount(arr, fn) {
  const seq = _seq(arr.map(x => fn(x)));
  if (_isFree(seq)) return _bind(seq, (bs) => bs.filter(b => b).length);
  return seq.filter(b => b).length;
}
function _seqFind(arr, fn) {
  const seq = _seq(arr.map(x => fn(x)));
  const pick = (bs) => {
    const i = bs.findIndex(b => b);
    return i < 0 ? _None : _Some(arr[i]);
  };
  if (_isFree(seq)) return _bind(seq, pick);
  return pick(seq);
}
function _seqFoldLeft(arr, init, fn) {
  function loop(i, acc) {
    if (i === arr.length) return acc;
    const next = fn(acc, arr[i]);
    if (_isFree(next)) return _bind(next, (v) => loop(i + 1, v));
    return loop(i + 1, next);
  }
  return loop(0, init);
}

// ── Reactive signals (fine-grained reactivity) ────────────────────────────
//
// Same push model as the interpreter and JvmGen: signals are mutable
// cells with a subscriber set; reading inside an active `effect`/
// `computed` registers a mutual subscription; writes queue subscribers
// into a `LinkedHashSet` and a single flush drains it, so the diamond
// (root → derived → consumer; consumer also reads root) sees each
// effect at most once per synchronous transaction.

let _signalSeq = 0;
const _signals = new Map();   // id → { value, subs:Set<eid> }
const _effects = new Map();   // eid → { thunk, deps:Set<sid> }
const _effectStack = [];
const _pendingEffects = new Set();  // insertion-ordered in JS Sets
let _reactiveFlushing = false;

function _freshReactiveId() { _signalSeq += 1; return _signalSeq; }

function _signalGet(id) {
  const s = _signals.get(id);
  if (!s) throw new Error('Signal disposed or unknown id');
  if (_effectStack.length > 0) {
    const eid = _effectStack[_effectStack.length - 1];
    s.subs.add(eid);
    const e = _effects.get(eid);
    if (e) e.deps.add(id);
  }
  return s.value;
}

function _signalSet(id, v) {
  const s = _signals.get(id);
  if (!s) throw new Error('Signal disposed or unknown id');
  s.value = v;
  // Skip subscribers currently running — without this, an effect
  // that writes a signal it also reads infinite-loops itself.
  for (const eid of s.subs) {
    if (_effectStack.indexOf(eid) < 0) _pendingEffects.add(eid);
  }
  if (!_reactiveFlushing) _reactiveFlush();
}

function _reactiveFlush() {
  _reactiveFlushing = true;
  try {
    while (_pendingEffects.size > 0) {
      const eid = _pendingEffects.values().next().value;
      _pendingEffects.delete(eid);
      _runEffect(eid);
    }
  } finally { _reactiveFlushing = false; }
}

function _clearEffectDeps(eid) {
  const e = _effects.get(eid);
  if (!e) return;
  for (const sid of e.deps) {
    const s = _signals.get(sid);
    if (s) s.subs.delete(eid);
  }
  e.deps.clear();
}

function _runEffect(eid) {
  const e = _effects.get(eid);
  if (!e) return;
  _clearEffectDeps(eid);
  _effectStack.push(eid);
  try { e.thunk(); }
  finally { _effectStack.pop(); }
}

function Signal(initial) {
  const id = _freshReactiveId();
  _signals.set(id, { value: initial, subs: new Set() });
  return {
    _type: 'Signal',
    id,
    get:   () => _signalGet(id),
    set:   (v) => _signalSet(id, v),
    apply: () => _signalGet(id),
  };
}

function effect(thunk) {
  const eid = _freshReactiveId();
  _effects.set(eid, { thunk, deps: new Set() });
  _runEffect(eid);
}

function computed(thunk) {
  const sid = _freshReactiveId();
  const eid = _freshReactiveId();
  _signals.set(sid, { value: undefined, subs: new Set() });
  const updater = () => _signalSet(sid, thunk());
  _effects.set(eid, { thunk: updater, deps: new Set() });
  _runEffect(eid);
  return {
    _type: 'Signal',
    id: sid,
    get:   () => _signalGet(sid),
    set:   () => { throw new Error('computed signal is read-only'); },
    apply: () => _signalGet(sid),
  };
}
"""

/** Browser-SPA overlay loaded AFTER `JsRuntime` so its `serve(...)` /
 *  `console.log`-based output flush replace the Node-target versions.
 *  The Node-only helpers (`_serveStatic`, `_contentTypeFor`, `require('http')`)
 *  are never invoked, so they sit as dead code without crashing the page. */
val JsRuntimeBrowserPatch: String = """
// ── Browser SPA overlay ──────────────────────────────────────────────────
// Replaces serve(port) with a popstate/link-click dispatcher.  Same
// route(method, path)(handler) surface as the Node target; same Response
// shape; same _routes / _matchPath / _mkRequest reused unchanged.

function _spaFlush() {
  if (_output.length) {
    for (const line of _output) console.log(line);
    _output = [];
  }
}

function _spaRender(response) {
  if (!response) { _spaFlush(); return; }
  const status  = response.status ?? 200;
  const headers = response.headers instanceof Map ? response.headers : new Map();
  const ct      = (headers.get('Content-Type') || headers.get('content-type') || '').toLowerCase();
  if (status >= 300 && status < 400) {
    const loc = headers.get('Location') || headers.get('location');
    if (loc) { _spaNavigate(loc, true); return; }
  }
  const body = response.body ?? '';
  if (ct.startsWith('text/html')) {
    // Replace just the body so <head>/<title>/<script> stay intact across
    // navigations — the SPA runtime itself lives in the original <script>.
    document.body.innerHTML = body;
  } else if (ct.startsWith('application/json')) {
    document.body.innerHTML = '<pre>' + body.replace(/&/g, '&amp;').replace(/</g, '&lt;') + '</pre>';
  } else {
    document.body.textContent = body;
  }
  _spaFlush();
}

function _spaDispatch(method, pathname, body) {
  const segs = pathname.split('/').filter(s => s.length > 0);
  for (const r of _routes) {
    if (r.method !== method) continue;
    const params = _matchPath(r.pattern, segs);
    if (params == null) continue;
    const request = {
      _type: 'Request',
      method,
      path:    pathname,
      params,
      query:   new Map(),
      headers: new Map(),
      body:    body || '',
      form:    new Map(),
    };
    try { _spaRender(r.handler(request)); }
    catch (e) {
      document.body.textContent = 'SPA route error: ' + (e && e.message ? e.message : e);
      _spaFlush();
    }
    return true;
  }
  document.body.textContent = 'Not Found: ' + pathname;
  _spaFlush();
  return false;
}

function _spaNavigate(pathname, replace) {
  if (replace) history.replaceState({}, '', pathname);
  else         history.pushState({}, '', pathname);
  _spaDispatch('GET', pathname);
}

// In browser there's no port to bind.  `serve(...)` hooks link clicks +
// popstate and dispatches the initial location so the page renders.
function serve(/* ignored */) {
  document.addEventListener('click', e => {
    const a = e.target && e.target.closest && e.target.closest('a');
    if (!a) return;
    const href = a.getAttribute('href');
    if (!href) return;
    // External / fragment / protocol-relative — let the browser handle.
    if (/^(https?:)?\/\//.test(href) || href.startsWith('#') || href.startsWith('mailto:')) return;
    e.preventDefault();
    _spaNavigate(href, false);
  });
  window.addEventListener('popstate', () => {
    _spaDispatch('GET', location.pathname || '/');
  });
  _spaDispatch('GET', location.pathname || '/');
}
"""

class JsGen(
    baseDir:    Option[os.Path] = None,
    intrinsics: Map[scalascript.ir.QualifiedName, scalascript.backend.spi.IntrinsicImpl] = Map.empty):
  import scala.meta.*

  private[codegen] val sb = StringBuilder()
  private var indent = 0
  private var tmpIdx = 0
  private var hasMain = false
  private var mainCalled = false
  // Stack of placeholder counters: each AnonymousFunction pushes 0, Placeholder increments top
  private var phCounters: List[Int] = Nil
  // Names of variables known to hold integer values (for integer division detection)
  private val intVars = scala.collection.mutable.Set[String]()
  // fname → set of all group members (populated by analyzeMutualRecursion before emit)
  private var mutualGroups: Map[String, Set[String]] = Map.empty
  // Effect operations declared in the module, as "Eff.op" strings.
  private val effectOps: scala.collection.mutable.Set[String] = scala.collection.mutable.Set.empty
  // Functions that transitively perform effects — emitted in CPS form so callers
  // get a Free value (Pure plain value or Perform node) and can compose them.
  private val effectfulFuns: scala.collection.mutable.Set[String] = scala.collection.mutable.Set.empty

  private def freshTmp(): String =
    tmpIdx += 1
    s"_t$tmpIdx"

  private def line(s: String): Unit =
    sb.append("  " * indent).append(s).append("\n")

  // ─── Mutual recursion analysis ───────────────────────────────────

  private def analyzeMutualRecursion(module: Module): Unit =
    val funcs = mutable.Map[String, Set[String]]()

    def collectFuncs(stats: List[Stat]): Unit = stats.foreach {
      case d: Defn.Def if d.paramClauseGroups.nonEmpty =>
        funcs(d.name.value) = tailCallTargets(d.body, d.name.value, tailPos = true)
      case _ => ()
    }

    def scanSection(section: Section): Unit =
      section.content.foreach {
        case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
          cb.tree.foreach { node =>
            ScalaNode.fold(node) {
              case Source(stats)     => collectFuncs(stats)
              case Term.Block(stats) => collectFuncs(stats)
              case _                 => ()
            }
          }
        case _ => ()
      }
      section.subsections.foreach(scanSection)

    module.sections.foreach(scanSection)

    val funcNames = funcs.keySet.toSet
    val sccs = findSCCs(funcs.toMap, funcNames)
    mutualGroups = sccs.filter(_.size > 1).foldLeft(Map.empty[String, Set[String]]) { (acc, scc) =>
      acc ++ scc.map(name => name -> scc)
    }

  private def findSCCs(graph: Map[String, Set[String]], names: Set[String]): List[Set[String]] =
    var idx = 0
    val stack  = mutable.Stack[String]()
    val onStk  = mutable.Set[String]()
    val nodeIdx = mutable.Map[String, Int]()
    val low     = mutable.Map[String, Int]()
    val result  = mutable.ListBuffer[Set[String]]()

    def connect(v: String): Unit =
      nodeIdx(v) = idx; low(v) = idx; idx += 1
      stack.push(v); onStk += v
      for w <- graph.getOrElse(v, Set.empty) if names.contains(w) do
        if !nodeIdx.contains(w) then
          connect(w)
          low(v) = low(v) min low(w)
        else if onStk.contains(w) then
          low(v) = low(v) min nodeIdx(w)
      if low(v) == nodeIdx(v) then
        val scc = mutable.Set[String]()
        var w = ""
        while { w = stack.pop(); onStk -= w; scc += w; w != v } do ()
        result += scc.toSet

    for v <- names do
      if !nodeIdx.contains(v) then connect(v)
    result.toList

  // Returns names of functions called in tail position (excludes selfName).
  private def tailCallTargets(tree: scala.meta.Tree, selfName: String, tailPos: Boolean): Set[String] =
    tree match
      case Term.Apply.After_4_6_0(Term.Name(n), argClause) =>
        if tailPos && n != selfName then Set(n)
        else argClause.values.flatMap(a => tailCallTargets(a, selfName, false)).toSet
      case t: Term.If =>
        tailCallTargets(t.cond,  selfName, false) ++
        tailCallTargets(t.thenp, selfName, tailPos) ++
        tailCallTargets(t.elsep, selfName, tailPos)
      case Term.Block(stats) =>
        stats.dropRight(1).flatMap(s => tailCallTargets(s, selfName, false)).toSet ++
        stats.lastOption.map(s => tailCallTargets(s, selfName, tailPos)).getOrElse(Set.empty)
      case t: Term.Match =>
        tailCallTargets(t.expr, selfName, false) ++
        t.casesBlock.cases.flatMap(c => tailCallTargets(c.body, selfName, tailPos)).toSet
      case other =>
        other.children.flatMap(c => tailCallTargets(c, selfName, false)).toSet

  // ─── Module entry ─────────────────────────────────────────────────

  /** Module-level `dependencies:` from the front-matter (set at the top
   *  of `genModule` / `genModuleSegmented`).  Threaded into `genImport`
   *  so `<dep-name>://path` imports rewrite through the resolver. */
  private var moduleDeps: Map[String, String] = Map.empty

  /** Absolute paths of `.ssc` files that have already been inlined by
   *  `genImport`.  Mirrors the cycle-protection invariant in
   *  `JvmGen.importedFiles` — a child module re-importing something
   *  the parent already pulled in (or a diamond import) emits nothing
   *  the second time around. */
  private val importedFiles: scala.collection.mutable.Set[String] =
    scala.collection.mutable.Set.empty

  def genModule(module: Module): String =
    sb.clear()
    moduleDeps = module.manifest.map(_.dependencies).getOrElse(Map.empty)
    analyzeMutualRecursion(module)
    analyzeEffects(module)
    // Front-matter route declarations are emitted BEFORE the user blocks so
    // a typical user-side `serve(port)` (last statement of the script) sees
    // them already registered.  JS function declarations are hoisted, so
    // forward references to the handler defs resolve at call time.
    emitFrontmatterRoutes(module)
    module.sections.foreach(genSection)
    // Auto-call main() if defined and not already called
    if hasMain && !mainCalled then
      line("if (typeof main === 'function') { main(); }")
    sb.toString

  /** Emit `route(method, path)(handler)` registrations for every
   *  `routes:` entry in the module's front-matter. */
  private def emitFrontmatterRoutes(module: Module): Unit =
    module.manifest.toList.flatMap(_.routes).foreach { r =>
      val m = jsQuote(r.method)
      val p = jsQuote(r.path)
      line(s"route($m, $p)(${r.handler});")
    }

  private def jsQuote(s: String): String =
    val sb = StringBuilder().append('"')
    s.foreach {
      case '"'  => sb.append("\\\"")
      case '\\' => sb.append("\\\\")
      case '\n' => sb.append("\\n")
      case '\r' => sb.append("\\r")
      case c    => sb.append(c)
    }
    sb.append('"').toString

  // ─── Effect analysis ─────────────────────────────────────────────
  //
  // Walks the module to:
  //   (1) collect effect operation names: `effect Eff: def op(...) = __effectOp__`
  //       contributes the string "Eff.op" to effectOps.
  //   (2) determine the set of functions that may transitively perform effects.
  //       A function is effectful if its body calls an effect op or another
  //       effectful function. Iterate to a fixed point.
  //
  // Effectful functions are emitted in CPS form (returning a Free value).
  // Pure functions stay direct — plain values double as Pure(value), so they
  // compose with the Free Monad runtime without any wrapping.

  private def analyzeEffects(module: Module): Unit =
    val builtins = Set(
      "Async.delay", "Async.async", "Async.await", "Async.parallel",
      "Storage.get", "Storage.put", "Storage.remove", "Storage.has", "Storage.keys",
      "Actor.spawn", "Actor.self", "Actor.send", "Actor.exit",
      "Actor.receive", "Actor.receive_t",
      "Actor.link", "Actor.monitor", "Actor.demonitor", "Actor.trapExit"
    )

    def collectTrees(s: Section): List[scala.meta.Tree] =
      s.content.collect {
        case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
          cb.tree.map(ScalaNode.fold(_)(identity))
      }.flatten ++ s.subsections.flatMap(collectTrees)

    val trees = module.sections.flatMap(collectTrees)
    val r     = EffectAnalysis.analyze(trees, builtins)

    effectOps.clear();     effectOps     ++= r.effectOps
    effectfulFuns.clear(); effectfulFuns ++= r.effectfulFuns

  /** True if `Eff.op` is a declared effect operation. */
  private def isEffectOpRef(eff: String, op: String): Boolean =
    effectOps.contains(s"$eff.$op")

  /** True if `name` resolves to an effectful function. */
  private def isEffectfulFun(name: String): Boolean =
    effectfulFuns.contains(name)

  /** Walk the module in document order, grouping consecutive same-type blocks into
   *  Segment values.  ScalaScript blocks are transpiled to JS; scala blocks are
   *  collected as raw Scala source for later Scala.js compilation.
   */
  def genModuleSegmented(module: Module): List[JsGen.Segment] =
    sb.clear()
    moduleDeps = module.manifest.map(_.dependencies).getOrElse(Map.empty)
    analyzeMutualRecursion(module)
    analyzeEffects(module)
    // Emit `route(...)` registrations from front-matter before user blocks,
    // so a typical user-side `serve(port)` (last statement of the script)
    // sees them already registered.  JS function declarations are hoisted,
    // so forward references to handler defs resolve at call time.
    emitFrontmatterRoutes(module)
    val result    = mutable.ListBuffer[JsGen.Segment]()
    val scalaBuf  = mutable.ListBuffer[String]()
    var ssStart   = 0

    def flushSS(): Unit =
      val code = sb.substring(ssStart)
      if code.trim.nonEmpty then result += JsGen.Segment.ScalaScriptJs(code)
      ssStart = sb.length

    def flushScala(): Unit =
      if scalaBuf.nonEmpty then
        result += JsGen.Segment.ScalaSource(scalaBuf.mkString("\n\n"))
        scalaBuf.clear()

    def walkSection(s: Section): Unit =
      s.content.foreach {
        case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
          flushScala()
          cb.tree.foreach(genScalaNode)
        case Content.CodeBlock(lang, src, _, _) if Lang.isStandardScala(lang) =>
          flushSS()
          scalaBuf += src.stripTrailing()
        case cb: Content.CodeBlock if Lang.isStringBlock(cb.lang) =>
          flushScala()
          genStringBlock(cb, s)
        case imp: Content.Import =>
          flushScala()
          genImport(imp)
        case _ => ()
      }
      s.subsections.foreach(walkSection)

    module.sections.foreach(walkSection)
    flushScala()
    if hasMain && !mainCalled then
      sb.append("if (typeof main === 'function') { main(); }\n")
    val finalCode = sb.substring(ssStart)
    if finalCode.trim.nonEmpty then result += JsGen.Segment.ScalaScriptJs(finalCode)
    result.toList

  private[codegen] def genSection(section: Section): Unit =
    section.content.foreach {
      case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
        cb.tree.foreach(genScalaNode)
      case cb: Content.CodeBlock if Lang.isStandardScala(cb.lang) =>
        line(s"/* scala: standard Scala 3 block — compile via Scala.js for JS execution */")
      case cb: Content.CodeBlock if Lang.isStringBlock(cb.lang) =>
        genStringBlock(cb, section)
      case imp: Content.Import =>
        genImport(imp)
      case _ => ()
    }
    section.subsections.foreach(genSection)

  /** Emit a heading-bound html / css block: render the source as a JS
   *  template literal (using `_html_interp` for html), assign to
   *  `<sectionIdent>.<lang>`. */
  private def genStringBlock(cb: Content.CodeBlock, section: Section): Unit =
    sectionIdent(section.heading.text).foreach { id =>
      val rendered = stringBlockTemplate(cb.source, cb.lang == Lang.Html)
      // Use `var` so multiple kinds of block in one section (html + css)
      // can share a single object literal without each invocation
      // clobbering the previous.
      line(s"if (typeof $id === 'undefined') var $id = {};")
      line(s"$id.${cb.lang} = $rendered;")
    }

  /** Build a JS template literal from the block source, routing every
   *  `${...}` interpolation through `_html_interp` (html-escape unless raw)
   *  or `_show` (css passthrough).  The expression text is preserved
   *  verbatim — JS evaluates it in the surrounding scope at runtime. */
  private def stringBlockTemplate(src: String, escape: Boolean): String =
    val sb = StringBuilder()
    sb.append('`')
    var i = 0
    while i < src.length do
      if i + 1 < src.length && src.charAt(i) == '$' && src.charAt(i + 1) == '{' then
        val end = findBalancedClose(src, i + 2)
        if end < 0 then
          sb.append(jsTemplateEscape(src.substring(i))); i = src.length
        else
          val expr = src.substring(i + 2, end).trim
          val wrap = if escape then "_html_interp" else "_show"
          sb.append("${").append(wrap).append("(").append(expr).append(")}")
          i = end + 1
      else
        sb.append(jsTemplateEscape(src.charAt(i).toString))
        i += 1
    sb.append('`')
    sb.toString

  private def jsTemplateEscape(s: String): String =
    s.replace("\\", "\\\\").replace("`", "\\`").replace("$", "\\$")

  private def findBalancedClose(src: String, from: Int): Int =
    var depth = 1
    var i = from
    while i < src.length && depth > 0 do
      src.charAt(i) match
        case '{' => depth += 1
        case '}' => depth -= 1; if depth == 0 then return i
        case _   => ()
      i += 1
    -1

  /** Mirror Interpreter.sectionIdent: camelCase alphanumeric runs, preserve
   *  the first word's casing; None when the heading is all punctuation. */
  private def sectionIdent(text: String): Option[String] =
    val parts = text.split("[^A-Za-z0-9]+").filter(_.nonEmpty)
    if parts.isEmpty then None
    else
      val head = parts.head
      val tail = parts.tail.map(p => p.head.toUpper + p.tail)
      val raw  = head + tail.mkString
      Some(if raw.head.isDigit then "_" + raw else raw)

  private def genImport(imp: Content.Import): Unit =
    import scalascript.parser.Parser
    val base = baseDir.getOrElse(os.pwd)
    val resolvedPath =
      try scalascript.imports.ImportResolver.resolve(imp.path, base, moduleDeps)
      catch case _: Throwable => base / os.RelPath(imp.path)
    val key = resolvedPath.toString
    if os.exists(resolvedPath) && !importedFiles.contains(key) then
      importedFiles += key
      val childDir = resolvedPath / os.up
      val childModule = Parser.parse(os.read(resolvedPath))
      val childGen = new JsGen(Some(childDir))
      childGen.importedFiles ++= importedFiles
      // Emit only the definitions from the imported module (suppress top-level output)
      childModule.sections.foreach { section =>
        section.content.foreach {
          case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
            cb.tree.foreach(childGen.genScalaNode)
          case nestedImp: Content.Import =>
            // Propagate transitive imports — e.g., std/selective.ssc
            // pulls in std/either.ssc, and consumers of selective need
            // Either's constructors emitted too.
            childGen.genImport(nestedImp)
          case _ => ()
        }
        section.subsections.foreach(childGen.genSection)
      }
      sb.append(childGen.sb)
      // Pull cycle-protection state back so siblings don't re-import
      importedFiles ++= childGen.importedFiles
      // For each `[Name as Alias]` binding, rebind the original to the
      // alias.  The original `Name` stays in scope too — JS imports are
      // currently whole-module inlines (see v0.7 follow-up: scoped
      // imports).  No alias emitted when binding has no `as`.
      imp.bindings.foreach { b =>
        b.alias.foreach { alias =>
          line(s"const $alias = ${b.name};")
        }
      }

  private[codegen] def genScalaNode(node: ScalaNode): Unit =
    ScalaNode.fold(node) {
      case Source(stats) => genBlockStats(stats, topLevel = true)
      case t: Term.Block => genBlockStats(t.stats, topLevel = true)
      case t: Term       => line(genExpr(t) + ";")
      case _             => ()
    }

  private def genBlockStats(stats: List[Stat], topLevel: Boolean): Unit =
    stats.zipWithIndex.foreach { (s, i) =>
      val isLast = i == stats.length - 1
      s match
        case t: Term if isLast && topLevel =>
          // Track main() calls; auto-output non-unit last expression
          t match
            case Term.Apply.After_4_6_0(Term.Name("main"), _) => mainCalled = true
            case _ => ()
          val expr = genExpr(t)
          line(s"{ const _auto = $expr; if (_auto !== undefined && !(_auto === null)) _println(_show(_auto)); }")
        case t: Term =>
          t match
            case Term.Apply.After_4_6_0(Term.Name("main"), _) => mainCalled = true
            case _ => ()
          line(genExpr(t) + ";")
        case stat =>
          genStat(stat)
    }

  private def genStat(stat: Stat): Unit = stat match
    case Defn.Val(_, pats, _, rhs) =>
      pats match
        case List(Pat.Var(n)) =>
          if isIntExpr(rhs) then intVars += n.value
          line(s"const ${n.value} = ${genExpr(rhs)};")
        case List(pat) =>
          // Tuple/pattern destructuring
          val patJs = genPatDestructure(pat)
          line(s"const $patJs = ${genExpr(rhs)};")
        case _ =>
          line(s"/* multi-pat val */")

    case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs) =>
      if isIntExpr(rhs) then intVars += n.value
      line(s"let ${n.value} = ${genExpr(rhs)};")

    // Stage 5+/A.6 (Б-1) — extern def stub is type-only; the intrinsic
    // table provides the real impl (dispatched at call sites by
    // dispatchIntrinsicJs).  Skip emission entirely so __extern__ doesn't
    // leak into the JS.
    case d: Defn.Def if scalascript.transform.EffectAnalysis.isExternDef(d.body) => ()

    case d: Defn.Def =>
      val paramVals = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values)
      val params    = paramVals.map(_.name.value)
      val hasDefaults = paramVals.exists(_.default.isDefined)
      val fname   = d.name.value
      val paramsStr = paramListWithDefaults(paramVals)
      if fname == "main" && params.isEmpty then hasMain = true
      // Effectful function: body emitted in CPS form, returns Free value.
      if isEffectfulFun(fname) then
        d.body match
          case Term.Block(stats) =>
            line(s"function $fname($paramsStr) { return ${genCpsBlockAsIife(stats)}; }")
          case expr =>
            line(s"function $fname($paramsStr) { return ${genCpsExpr(expr)}; }")
      // Mutual recursion group → _impl + trampoline wrapper.
      // Defaults disable the TCO/mutual-TCO shadowing path since the _p shadow
      // names would shadow the original parameter names referenced in default
      // expressions; defaults are uncommon in tight recursive loops anyway.
      else if mutualGroups.contains(fname) && params.nonEmpty && !hasDefaults then
        genMutualTcoFun(d, fname, params)
      // Self-TCO: emit a while-loop trampoline when all self-calls are in tail position
      else if params.nonEmpty && fname.nonEmpty && !hasDefaults &&
              !hasNonTailSelfCall(d.body, fname, tailPos = true) then
        // Formals are _p shadow-names so we can declare mutable let params inside
        val formals  = params.map(p => s"_$p").mkString(", ")
        val letDecls = "let " + params.map(p => s"$p = _$p").mkString(", ")
        line(s"function $fname($formals) {")
        indent += 1
        line(s"$letDecls;")
        line("while(true) {")
        indent += 1
        genTcoBody(d.body, fname, params)
        indent -= 1
        line("}")
        indent -= 1
        line("}")
      else
        d.body match
          case Term.Block(bodyStats) =>
            line(s"function $fname($paramsStr) {")
            indent += 1
            genFunctionBody(bodyStats)
            indent -= 1
            line("}")
          case expr =>
            line(s"function $fname($paramsStr) { return ${genExpr(expr)}; }")

    case d: Defn.Class =>
      // case class → constructor function returning plain object
      val paramVals = d.ctor.paramClauses.flatMap(_.values)
      val params = paramVals.map(_.name.value)
      val typeName = d.name.value
      val paramsStr = paramListWithDefaults(paramVals)
      val fields = params.map(p => s"$p: $p").mkString(", ")
      line(s"function $typeName($paramsStr) { return {_type: '$typeName', $fields}; }")

    case d: Defn.Object =>
      line(s"const ${d.name.value} = ${genObjectAsExpr(d)};")

    case d: Defn.Enum =>
      d.templ.body.stats.foreach {
        case ec: Defn.EnumCase =>
          val caseName = ec.name.value
          val paramVals = ec.ctor.paramClauses.flatMap(_.values)
          val params = paramVals.map(_.name.value)
          if params.isEmpty then
            line(s"const $caseName = {_type: '$caseName'};")
          else
            val paramsStr = paramListWithDefaults(paramVals)
            val fields = params.map(p => s"$p: $p").mkString(", ")
            line(s"function $caseName($paramsStr) { return {_type: '$caseName', $fields}; }")
        case _ => ()
      }

    case _: Defn.Trait => () // erased

    case d: Defn.Given =>
      // given intShow: Show[Int] with { def show(x) = ... }
      // → const intShow = { show: (x) => ..., ... };
      // also register as Show_Int.
      //
      // Extension methods inside the given body (`extension [A](fa: F[A]) def fmap[B](f) = ...`)
      // are registered into the global `_extensions` table so `fa.fmap(f)` dispatches —
      // same machinery as top-level extension groups.
      d.templ.body.stats.foreach {
        case eg: Defn.ExtensionGroup => genStat(eg)
        case _                       => ()
      }
      d.templ.inits.headOption.foreach { init =>
        val typeKeyOpt: Option[String] = init.tpe match
          case n: Type.Name  => Some(n.value)
          case ta: Type.Apply =>
            (ta.tpe match { case n: Type.Name => Some(n.value); case _ => None }).map { tc =>
              val arg = ta.argClause.values match
                case List(n: Type.Name) => n.value
                case _                  => "_"
              s"${tc}_${arg}"
            }
          case _ => None
        typeKeyOpt.foreach { typeKey =>
          val members = d.templ.body.stats.collect { case dd: Defn.Def =>
            s"${dd.name.value}: ${genDefAsMethod(dd)}"
          }
          val obj = s"{${members.mkString(", ")}}"
          val explicitName = d.name.value
          if explicitName.nonEmpty then
            line(s"const $explicitName = $obj;")
            // Also register with typeclass key for summon
            line(s"const $typeKey = $explicitName;")
          else
            line(s"const $typeKey = $obj;")
        }
      }

    case _: Decl.Def => () // abstract

    case d: Defn.ExtensionGroup =>
      d.paramClauseGroup.foreach { pcg =>
        pcg.paramClauses.headOption.flatMap(_.values.headOption).foreach { recvParam =>
          val recvName = recvParam.name.value
          val recvType = recvParam.decltpe match
            case Some(Type.Name(n))   => n
            case Some(ta: Type.Apply) => ta.tpe match { case Type.Name(n) => n; case _ => "Any" }
            case _                    => "Any"
          d.body match
            case defn: Defn.Def =>
              genExtensionDef(recvName, recvType, defn)
            case Term.Block(stats) =>
              stats.foreach { case defn: Defn.Def => genExtensionDef(recvName, recvType, defn); case _ => () }
            case _ => ()
        }
      }

    case t: Term =>
      // Track if main() is explicitly called
      t match
        case Term.Apply.After_4_6_0(Term.Name("main"), _) => mainCalled = true
        case _ => ()
      line(genExpr(t) + ";")

    case _: Import => () // ignored
    case _: Export => () // ignored
    case _ => () // type aliases etc.

  private def genExtensionDef(recvName: String, recvType: String, defn: Defn.Def): Unit =
    val mparamVals = defn.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values)
    val paramsStr  = (recvName :: mparamVals.map(formalWithDefault)).mkString(", ")
    // Encode receiver TYPE into the function name so that the same extension
    // method on different types (e.g., Functor[List].ap and Functor[Option].ap)
    // does not collide and silently overwrite each other.
    val fnName = s"_ext_${recvType}_${defn.name.value}"
    defn.body match
      case Term.Block(bodyStats) =>
        line(s"function $fnName($paramsStr) {")
        indent += 1
        genFunctionBody(bodyStats)
        indent -= 1
        line("}")
      case expr =>
        line(s"function $fnName($paramsStr) { return ${genExpr(expr)}; }")
    // Register extension for dispatch.  The receiver type (when known)
    // disambiguates same-named extensions across typeclass instances
    // — e.g., Functor[List].map vs Functor[Option].map both register
    // `map` but route by `_typeOf(obj)` at the call site.  `Any` means
    // the legacy method-only registry handles it.
    val regType = if recvType == "Any" then "null" else s"'$recvType'"
    line(s"_registerExt('${defn.name.value}', ($recvName, ...args) => $fnName($recvName, ...args), $regType);")

  private def genObjectMember(stat: Stat): String = stat match
    case dd: Defn.Def =>
      s"${dd.name.value}: ${genDefAsMethod(dd)}"
    case Defn.Val(_, List(Pat.Var(n)), _, rhs) =>
      s"${n.value}: ${genExpr(rhs)}"
    case _ => ""

  /** Emit a Scala `Defn.Object` as a JS expression — an IIFE that
   *  declares each member as a local const and returns them as an
   *  object literal.  Used both at top level (`const X = (iife)()`)
   *  and as the right-hand side of a nested `const inner = (iife)()`
   *  inside another object's body, which is how the `package:`
   *  front-matter wrapper survives JS emission. */
  private def genObjectAsExpr(d: Defn.Object): String =
    val objectName = d.name.value
    val decls = mutable.ArrayBuffer.empty[String]
    val names = mutable.ArrayBuffer.empty[String]
    d.templ.body.stats.foreach {
      case dd: Defn.Def if isEffectOpDef(dd.body) =>
        val opName = dd.name.value
        val paramVals = dd.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values)
        val params    = paramVals.map(_.name.value)
        val paramsStr = paramListWithDefaults(paramVals)
        val argsArr = if params.isEmpty then "[]" else s"[${params.mkString(", ")}]"
        decls += s"const $opName = ($paramsStr) => _perform('$objectName', '$opName', $argsArr);"
        names += opName
      case dd: Defn.Def =>
        val paramVals = dd.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values)
        val paramsStr = paramListWithDefaults(paramVals)
        val bodyJs = dd.body match
          case Term.Block(bodyStats) => genBlockAsIife(bodyStats)
          case expr                   => genExpr(expr)
        decls += s"const ${dd.name.value} = ($paramsStr) => $bodyJs;"
        names += dd.name.value
      case Defn.Val(_, List(Pat.Var(n)), _, rhs) =>
        decls += s"const ${n.value} = ${genExpr(rhs)};"
        names += n.value
      case nested: Defn.Object =>
        decls += s"const ${nested.name.value} = ${genObjectAsExpr(nested)};"
        names += nested.name.value
      case _ => ()
    }
    val body = decls.mkString(" ")
    val ret  = names.mkString(", ")
    s"(() => { $body return { $ret }; })()"

  private def genDefAsMethod(dd: Defn.Def): String =
    val paramVals = dd.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values)
    val paramsStr = paramListWithDefaults(paramVals)
    dd.body match
      case Term.Block(bodyStats) =>
        val bodyJs = genBlockAsIife(bodyStats)
        s"($paramsStr) => $bodyJs"
      case expr =>
        s"($paramsStr) => ${genExpr(expr)}"

  /** Render a comma-separated formal parameter list with `= <expr>` for any
   *  parameter that has a default value. Used everywhere we emit a JS function
   *  signature from a scalameta `Term.Param` list. */
  private def paramListWithDefaults(paramVals: Seq[Term.Param]): String =
    paramVals.map(formalWithDefault).mkString(", ")

  private def formalWithDefault(p: Term.Param): String = p.default match
    case Some(d) => s"${p.name.value} = ${genExpr(d)}"
    case None    => p.name.value

  // ─── Mutual TCO helpers ──────────────────────────────────────────

  // Emits _fname_impl (while-loop + _tailCall for mutual calls) and the public wrapper.
  private def genMutualTcoFun(d: Defn.Def, fname: String, params: List[String]): Unit =
    val implName = s"_${fname}_impl"
    val friends  = mutualGroups(fname) - fname
    val formals  = params.map(p => s"_$p").mkString(", ")
    val letDecls = "let " + params.map(p => s"$p = _$p").mkString(", ")
    line(s"function $implName($formals) {")
    indent += 1
    line(s"$letDecls;")
    line("while(true) {")
    indent += 1
    genMutualTcoBody(d.body, fname, params, friends)
    indent -= 1
    line("}")
    indent -= 1
    line("}")
    // Public wrapper that starts the trampoline
    val wrapArgs = params.map(p => s"_$p").mkString(", ")
    line(s"function $fname($formals) { return _trampoline($implName, $wrapArgs); }")

  // Like genTcoBody but mutual tail calls return _tailCall thunks.
  private def genMutualTcoBody(term: Term, fname: String, params: List[String], friends: Set[String]): Unit =
    term match
      case Term.Apply.After_4_6_0(Term.Name(`fname`), argClause) =>
        val newArgs = argClause.values.map(v => genExpr(v.asInstanceOf[Term]))
        if params.length == 1 then line(s"${params(0)} = ${newArgs(0)};")
        else line(s"[${params.mkString(", ")}] = [${newArgs.mkString(", ")}];")
        line("continue;")
      case Term.Apply.After_4_6_0(Term.Name(n), argClause) if friends.contains(n) =>
        val newArgs = argClause.values.map(v => genExpr(v.asInstanceOf[Term]))
        line(s"return _tailCall(_${n}_impl, ${newArgs.mkString(", ")});")
      case t: Term.If =>
        line(s"if (${genExpr(t.cond)}) {")
        indent += 1; genMutualTcoBody(t.thenp, fname, params, friends); indent -= 1
        line("} else {")
        indent += 1; genMutualTcoBody(t.elsep, fname, params, friends); indent -= 1
        line("}")
      case Term.Block(stats) =>
        stats.dropRight(1).foreach {
          case t: Term => line(genExpr(t) + ";")
          case s       => genStat(s)
        }
        stats.lastOption.foreach {
          case t: Term => genMutualTcoBody(t, fname, params, friends)
          case _       => ()
        }
      case other =>
        line(s"return ${genExpr(other)};")

  // ─── Self-TCO helpers ─────────────────────────────────────────────

  // Emits statements for the body of a TCO while-loop.
  // Tail calls to fname become parameter reassignment + continue.
  // All other expressions become return statements.
  private def genTcoBody(term: Term, fname: String, params: List[String]): Unit = term match
    case Term.Apply.After_4_6_0(Term.Name(`fname`), argClause) =>
      val newArgs = argClause.values.map(v => genExpr(v.asInstanceOf[Term]))
      if params.length == 1 then
        line(s"${params(0)} = ${newArgs(0)};")
      else
        line(s"[${params.mkString(", ")}] = [${newArgs.mkString(", ")}];")
      line("continue;")
    case t: Term.If =>
      line(s"if (${genExpr(t.cond)}) {")
      indent += 1; genTcoBody(t.thenp, fname, params); indent -= 1
      line("} else {")
      indent += 1; genTcoBody(t.elsep, fname, params); indent -= 1
      line("}")
    case Term.Block(stats) =>
      stats.dropRight(1).foreach {
        case t: Term => line(genExpr(t) + ";")
        case s       => genStat(s)
      }
      stats.lastOption.foreach {
        case t: Term => genTcoBody(t, fname, params)
        case _       => ()
      }
    case other =>
      line(s"return ${genExpr(other)};")

  // Returns true if term contains a call to fname NOT in tail position.
  private def hasNonTailSelfCall(term: Term, fname: String, tailPos: Boolean): Boolean =
    import scala.meta.*
    term match
      case Term.Apply.After_4_6_0(Term.Name(`fname`), argClause) =>
        if tailPos then argClause.values.collect { case t: Term => t }
                                        .exists(hasNonTailSelfCall(_, fname, tailPos = false))
        else true
      case t: Term.If =>
        hasNonTailSelfCall(t.cond,  fname, tailPos = false) ||
        hasNonTailSelfCall(t.thenp, fname, tailPos = tailPos) ||
        hasNonTailSelfCall(t.elsep, fname, tailPos = tailPos)
      case Term.Block(stats) =>
        stats.dropRight(1).exists {
          case t: Term => hasNonTailSelfCall(t, fname, tailPos = false)
          case _       => false
        } || stats.lastOption.exists {
          case t: Term => hasNonTailSelfCall(t, fname, tailPos = tailPos)
          case _       => false
        }
      case t: Term.Match =>
        hasNonTailSelfCall(t.expr, fname, tailPos = false) ||
        t.casesBlock.cases.exists(c => hasNonTailSelfCall(c.body, fname, tailPos = tailPos))
      case other =>
        anywhereContainsSelfCall(other, fname)

  private def anywhereContainsSelfCall(tree: scala.meta.Tree, fname: String): Boolean =
    tree match
      case Term.Apply.After_4_6_0(Term.Name(`fname`), _) => true
      case t => t.children.exists(anywhereContainsSelfCall(_, fname))

  private def genFunctionBody(stats: List[Stat]): Unit =
    if stats.isEmpty then
      line("return undefined;")
    else
      stats.zipWithIndex.foreach { (s, i) =>
        val isLast = i == stats.length - 1
        s match
          case t: Term if isLast =>
            line(s"return ${genExpr(t)};")
          case t: Term =>
            line(genExpr(t) + ";")
          case stat =>
            genStat(stat)
        // After each non-last stat, continue with remaining
      }

  private def genBlockAsIife(stats: List[Stat]): String =
    if stats.isEmpty then "undefined"
    else if stats.length == 1 then
      stats.head match
        case t: Term => genExpr(t)
        case stat =>
          s"(() => { ${genStatInline(stat)} return undefined; })()"
    else
      // Multi-stat IIFE
      val inner = StringBuilder()
      inner.append("(() => {\n")
      stats.zipWithIndex.foreach { (s, i) =>
        val isLast = i == stats.length - 1
        val pad = "  "
        s match
          case t: Term if isLast =>
            inner.append(pad).append("return ").append(genExpr(t)).append(";\n")
          case t: Term =>
            inner.append(pad).append(genExpr(t)).append(";\n")
          case stat =>
            inner.append(pad).append(genStatInline(stat)).append("\n")
      }
      inner.append("})()")
      inner.toString

  private def genStatInline(stat: Stat): String = stat match
    case Defn.Val(_, List(Pat.Var(n)), _, rhs) =>
      s"const ${n.value} = ${genExpr(rhs)};"
    case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs) =>
      s"let ${n.value} = ${genExpr(rhs)};"
    case d: Defn.Def =>
      val params = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).map(_.name.value)
      d.body match
        case expr =>
          s"function ${d.name.value}(${params.mkString(", ")}) { return ${genExpr(expr)}; }"
    case t: Term => genExpr(t) + ";"
    case _ => "/* stat */;"

  private def genPatDestructure(pat: Pat): String = pat match
    case Pat.Tuple(pats) =>
      "[" + pats.map(p => p match
        case Pat.Var(n) => n.value
        case Pat.Wildcard() => "_"
        case inner => genPatDestructure(inner)
      ).mkString(", ") + "]"
    case Pat.Var(n) => n.value
    case Pat.Wildcard() => "_"
    case _ => "_"

  private def isEffectOpDef(body: Term): Boolean =
    scalascript.transform.EffectAnalysis.isEffectOpDef(body)

  /** Emits a JS matcher closure for a `receive { case … }` block.
   *  The closure takes the next mailbox message and returns either
   *  `{ matched: false }` or `{ matched: true, body: () => <computation> }`.
   *  Case bodies are CPS-emitted so any nested Actor / Async / handle
   *  effects compose into the actor's pending Computation. */
  private def genReceiveMatcher(cases: List[Case]): String =
    val scrut = "__rcv_msg__"
    val chain = cases.map { c =>
      val (cond, bindings) = genPattern(scrut, c.pat)
      val bindStmts = bindings.map { case (n, e) => s"const $n = $e;" }.mkString(" ")
      val bodyCps   = genCpsExpr(c.body)
      val condFinal = c.cond match
        case Some(g) =>
          val guardJs = genExpr(g)
          if cond == "true" then s"($guardJs)" else s"($cond) && ($guardJs)"
        case None => if cond == "true" then "true" else s"($cond)"
      s"if ($condFinal) { $bindStmts return { matched: true, body: () => $bodyCps }; }"
    }.mkString(" ")
    s"($scrut) => { $chain return { matched: false }; }"

  private def genHandleForm(body: Term, cases: List[Case]): String =
    val handledOps = cases.flatMap { c =>
      c.pat match
        case Pat.Extract.After_4_6_0(Term.Select(Term.Name(eff), Term.Name(op)), _) => Some(s"'$eff.$op'")
        case _ => None
    }
    val handlerEntries = cases.flatMap { c =>
      c.pat match
        case Pat.Extract.After_4_6_0(Term.Select(Term.Name(eff), Term.Name(op)), argClause) =>
          val paramNames = argClause.values.map {
            case Pat.Var(n)     => n.value
            case Pat.Wildcard() => "_"
            case _              => "_"
          }
          val paramsStr = s"[${paramNames.mkString(", ")}]"
          // Handler case bodies stay direct: they receive `resume` (a plain
          // function returning the value of the resumed branch) and compose it
          // with regular JS. Effects inside case bodies are uncommon and would
          // need their own handle.
          val bodyJs = c.body match
            case Term.Block(stats) =>
              val stmts = stats.dropRight(1).map {
                case t: Term => genExpr(t) + ";"
                case s       => genStatInline(s)
              }.mkString(" ")
              val last = stats.lastOption.map {
                case t: Term => s"return ${genExpr(t)};"
                case _       => ""
              }.getOrElse("")
              s"($paramsStr) => { $stmts $last }"
            case expr =>
              s"($paramsStr) => ${genExpr(expr)}"
          Some(s"'$eff.$op': $bodyJs")
        case _ => None
    }
    // The handle body is always emitted in CPS form so that effect ops build
    // a Free tree which _handle can interpret.
    val bodyThunk = s"() => ${genCpsExpr(body)}"
    s"_handle($bodyThunk, [${handledOps.mkString(", ")}], {${handlerEntries.mkString(", ")}})"

  /** Stage 5+/A.5 — per-call-site intrinsic dispatch.  Returns the
   *  JS expression string to splice in, or `None` if no intrinsic
   *  claims this name.  Called from `genExpr` for Term.Apply
   *  (Term.Name(fname), args) sites BEFORE the existing hardcoded
   *  pattern matches, so a registered intrinsic always wins. */
  private def dispatchIntrinsicJs(fname: String, argClause: Term.ArgClause): Option[String] =
    val qn = scalascript.ir.QualifiedName(fname)
    intrinsics.get(qn).map {
      case scalascript.backend.spi.RuntimeCall(target) =>
        s"$target(${argClause.values.map(genExpr).mkString(", ")})"
      case scalascript.backend.spi.InlineCode(emit) =>
        val irArgs = argClause.values.map(termToIrJs)
        val ctx    = JsEmitContext
        emit(irArgs, ctx).value
      case _ =>
        // NativeImpl / HostCallback don't emit target source; fall
        // through to scalameta's default emission.
        argClause.values.map(genExpr).mkString(s"$fname(", ", ", ")")
    }

  /** Minimum-viable IrExpr conversion for intrinsic dispatch — only
   *  string / int / double / bool literals survive shape; everything
   *  else becomes a `VarRef` carrying the genExpr-emitted JS. */
  private def termToIrJs(t: Term): scalascript.ir.IrExpr = t match
    case Lit.String(s)  => scalascript.ir.Lit(scalascript.ir.LitValue.StringL(s))
    case Lit.Int(n)     => scalascript.ir.Lit(scalascript.ir.LitValue.IntL(n.toLong))
    case Lit.Long(n)    => scalascript.ir.Lit(scalascript.ir.LitValue.IntL(n))
    case Lit.Double(d)  => scalascript.ir.Lit(scalascript.ir.LitValue.DoubleL(d.toDouble))
    case Lit.Boolean(b) => scalascript.ir.Lit(scalascript.ir.LitValue.BoolL(b))
    case Lit.Unit()     => scalascript.ir.Lit(scalascript.ir.LitValue.UnitL)
    case other          => scalascript.ir.VarRef(genExpr(other))

  /** Stage 5+/A.5 — `JsGen`'s per-call-site EmitContext.  Stub for
   *  now; future intrinsics extend the trait surface as needed. */
  private object JsEmitContext extends scalascript.ir.EmitContext

  /** Generate a JS expression string for a scalameta Term. */
  def genExpr(term: Term): String = term match
    // Stage 5+/A.5 intrinsic dispatch — fires first.
    case Term.Apply.After_4_6_0(Term.Name(fname), argClause)
        if dispatchIntrinsicJs(fname, argClause).isDefined =>
      dispatchIntrinsicJs(fname, argClause).get

    // Literals
    case Lit.Int(v)     => v.toString
    case Lit.Long(v)    => v.toString
    case Lit.Double(v)  => v.toString
    case Lit.Float(v)   => v.toString
    case Lit.String(v)  =>
      // Escape for JS string literal
      "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
    case Lit.Boolean(v) => v.toString
    case Lit.Char(v)    => "\"" + v.toString.replace("\"", "\\\"") + "\""
    case Lit.Unit()     => "undefined"
    case Lit.Null()     => "null"

    // Name lookup
    case Term.Name(name) => mapName(name)

    // Block
    case Term.Block(stats) =>
      if stats.isEmpty then "undefined"
      else genBlockAsIife(stats)

    // If/else
    case t: Term.If =>
      val cond = genExpr(t.cond)
      val thenp = genExpr(t.thenp)
      val elsep = t.elsep match
        case Lit.Unit() => "undefined"
        case e          => genExpr(e)
      s"($cond ? $thenp : $elsep)"

    // String interpolation
    case Term.Interpolate(Term.Name(prefix), parts, args)
        if prefix == "s" || prefix == "f" || prefix == "md"
        || prefix == "html" || prefix == "css" =>
      val sb2 = StringBuilder()
      sb2.append("`")
      for i <- parts.indices do
        val part = parts(i).asInstanceOf[Lit.String].value
        // Backslash first — replacing `\\` AFTER `` ` `` would double-escape
        // the backslash inserted by the `` ` `` step, breaking the JS
        // template literal.
        sb2.append(part.replace("\\", "\\\\").replace("`", "\\`").replace("$", "\\$"))
        if i < args.length then
          val arg = args(i).asInstanceOf[Term]
          val argJs = genExpr(arg)
          // html"..." escapes interpolated values unless they're a raw() marker.
          val wrapped =
            if prefix == "html" then s"_html_interp($argJs)"
            else                     s"_show($argJs)"
          sb2.append("${").append(wrapped).append("}")
      sb2.append("`")
      val templateLiteral = sb2.toString
      if prefix == "md" then s"_md($templateLiteral)" else templateLiteral

    // Anonymous function with _ placeholders — stack-based param counting
    case t: Term.AnonymousFunction =>
      phCounters = 0 :: phCounters          // push fresh counter
      val bodyJs = genExpr(t.body)
      val count  = phCounters.head
      phCounters = phCounters.tail           // pop
      val params = (0 until count).map(i => s"_$$${i}")
      if params.isEmpty then s"() => $bodyJs"
      else s"(${params.mkString(", ")}) => $bodyJs"

    // Placeholder _ — increment top counter and return indexed name
    case _: Term.Placeholder =>
      val i = phCounters.headOption.getOrElse(0)
      phCounters = phCounters match
        case h :: t => (h + 1) :: t
        case Nil    => Nil
      s"_$$$i"

    // Lambda
    case Term.Function.After_4_6_0(paramClause, body) =>
      val params = paramClause.values.map(_.name.value)
      val bodyJs = body match
        case Term.Block(stats) => genBlockAsIife(stats)
        case expr              => genExpr(expr)
      if params.length == 1 then s"${params.head} => $bodyJs"
      else
        // Auto-tuple: when this lambda is passed somewhere that supplies a
        // single tuple-arg (e.g. `pairs.foreach((n, s) => ...)`, where the
        // callback receives one `[n, s]` array), destructure on entry.
        val arity   = params.length
        val joined  = params.mkString(", ")
        s"((...__a) => { const [$joined] = (__a.length === 1 && Array.isArray(__a[0]) && __a[0].length === $arity) ? __a[0] : __a; return $bodyJs; })"

    // Partial function { case ... => ... }
    case Term.PartialFunction(cases) =>
      val scrutVar = freshTmp()
      val casesJs = cases.map { c =>
        genCase(scrutVar, c)
      }.mkString(" else ")
      s"(($scrutVar) => { $casesJs else { throw new Error('Match failure: ' + _show($scrutVar)); } })"

    // Match
    case t: Term.Match =>
      val scrutVar = freshTmp()
      val scrutExpr = genExpr(t.expr)
      val casesJs = t.casesBlock.cases.map { c =>
        genCase(scrutVar, c)
      }.mkString(" else ")
      s"(($scrutVar => { $casesJs else { throw new Error('Match failure: ' + _show($scrutVar)); } })($scrutExpr))"

    // Tuple
    case Term.Tuple(elems) =>
      val elemsJs = elems.map(genExpr).mkString(", ")
      val tmp = freshTmp()
      s"(() => { const $tmp = [$elemsJs]; $tmp._isTuple = true; return $tmp; })()"

    // Assignment
    case Term.Assign(lhs, rhs) =>
      s"${genExpr(lhs)} = ${genExpr(rhs)}"

    // While
    case t: Term.While =>
      val cond = genExpr(t.expr)
      val body = genExpr(t.body)
      s"(() => { while ($cond) { $body; } })()"

    // For-do
    case t: Term.For =>
      genForDo(t.enumsBlock.enums, t.body)

    // For-yield
    case t: Term.ForYield =>
      genForYield(t.enumsBlock.enums, t.body)

    // new ClassName(args)
    case Term.New(Init.After_4_6_0(tpe, _, argClauses)) =>
      val typeName = tpe match { case Type.Name(n) => n; case _ => "?" }
      val args = argClauses.toList.flatMap(_.values).map(genExpr)
      s"$typeName(${args.mkString(", ")})"

    // Return
    case Term.Return(expr) =>
      genExpr(expr)  // We can't easily return from JS like Scala; treat as expression

    // summon[TC[T]]
    case t: Term.ApplyType =>
      (t.fun, t.argClause.values) match
        case (Term.Name("summon"), List(typeArg)) =>
          val key = typeArg match
            case n: Type.Name  => n.value
            case ta: Type.Apply =>
              val tc  = ta.tpe match { case n: Type.Name => n.value; case _ => "?" }
              val arg = ta.argClause.values match { case List(n: Type.Name) => n.value; case _ => "_" }
              s"${tc}_${arg}"
            case _ => "undefined"
          key
        case (Term.Name("Prism"), List(_, variantType)) =>
          val variantName = variantType match
            case n: Type.Name => n.value
            case _            => return "(()=>{ throw new Error('Prism[Outer, Variant]: Variant must be a simple type name'); })()"
          s"_makePrism('$variantName')"
        case _ => genExpr(t.fun)

    // Field/method selection without arguments
    case Term.Select(qual, name) =>
      val qualJs = genExpr(qual)
      name.value match
        // Built-in collection/string methods that need runtime dispatch (computed properties)
        case "head" | "tail" | "last" | "init" | "reverse" | "distinct" | "sorted" |
             "toList" | "toSet" | "sum" | "min" | "max" | "flatten" | "isEmpty" |
             "nonEmpty" | "size" | "length" | "keys" | "values" | "isDefined" |
             "toUpperCase" | "toLowerCase" | "trim" | "toInt" | "toDouble" | "toLong" |
             "abs" | "round" | "floor" | "ceil" | "zipWithIndex" | "nonEmpty" |
             "_1" | "_2" | "_3" | "_4" =>
          s"_dispatch($qualJs, '${name.value}', [])"
        case other =>
          // Direct property access for regular objects (case classes, typeclasses, etc.)
          // Use _dispatch for extension methods, but try direct property first
          s"_dispatch($qualJs, '$other', [])"

    // Special form: handle(body) { case Eff.op(args, resume) => ... }
    case Term.Apply.After_4_6_0(
      Term.Apply.After_4_6_0(Term.Name("handle"), bodyArgClause),
      pfArgClause
    ) if bodyArgClause.values.size == 1 =>
      pfArgClause.values match
        case List(pf: Term.PartialFunction) =>
          genHandleForm(bodyArgClause.values.head.asInstanceOf[Term], pf.cases)
        case _ => s"/* invalid handle */ undefined"

    // Special form: runAsync(body) — built-in Async-effect driver.  Body
    // is CPS-emitted so Async.* ops build a Free tree; `_runAsync`
    // walks it and dispatches each op against the default handler.
    case Term.Apply.After_4_6_0(Term.Name("runAsync"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      s"_runAsync(() => ${genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("runAsyncParallel"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      s"_runAsyncParallel(() => ${genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])})"

    // Storage handlers — file-backed (with optional path arg) and ephemeral
    case Term.Apply.After_4_6_0(Term.Name("runStorage"), bodyArgClause)
        if bodyArgClause.values.size >= 1 =>
      val bodyJs = genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])
      val pathJs = bodyArgClause.values.lift(1).map(p => genExpr(p.asInstanceOf[Term])).getOrElse("null")
      s"_runStorage(() => $bodyJs, $pathJs)"
    case Term.Apply.After_4_6_0(Term.Name("runEphemeralStorage"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      s"_runStorage(() => ${genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])}, null)"

    // ── v1.6 Actors Phase 1 ─────────────────────────────────────────
    // `runActors { body }` — body is CPS-emitted so Actor.* ops build
    // a Free tree the scheduler walks.
    case Term.Apply.After_4_6_0(Term.Name("runActors"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      s"_runActors(() => ${genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])})"

    // `receive(timeout = N) { case … }` — same machinery as `receive`
    // but the matcher is registered with `wrapSome=true` and the
    // driver tracks a deadline.
    case Term.Apply.After_4_6_0(
            Term.Apply.After_4_6_0(Term.Name("receive"), timeoutArgClause),
            pfArgClause)
        if pfArgClause.values.size == 1 && timeoutArgClause.values.size == 1 =>
      val timeoutTerm = timeoutArgClause.values.head match
        case Term.Assign(Term.Name("timeout"), v) => v
        case other: Term                          => other
      pfArgClause.values.head match
        case pf: Term.PartialFunction =>
          val matcherJs = genReceiveMatcher(pf.cases)
          s"Actor.receive_t(_registerReceive($matcherJs), ${genExpr(timeoutTerm.asInstanceOf[Term])})"
        case _ => "/* invalid receive */ undefined"

    // `receive { case … }` — special form so we can synthesise the
    // matcher closure with the right CPS-emitted bodies.
    case Term.Apply.After_4_6_0(Term.Name("receive"), pfArgClause)
        if pfArgClause.values.size == 1 =>
      pfArgClause.values.head match
        case pf: Term.PartialFunction =>
          val matcherJs = genReceiveMatcher(pf.cases)
          s"Actor.receive_(_registerReceive($matcherJs))"
        case _ => "/* invalid receive */ undefined"

    // Spawn / self / exit — emit through the Actor runtime so they
    // produce _Perform nodes the scheduler picks up.
    case Term.Apply.After_4_6_0(Term.Name("spawn"), argClause)
        if argClause.values.size == 1 =>
      val thunk = argClause.values.head.asInstanceOf[Term]
      // The thunk's body is CPS-emitted so its Actor.* ops chain.
      thunk match
        case Term.Function.After_4_6_0(_, body) =>
          s"Actor.spawn(() => ${genCpsExpr(body)})"
        case other => s"Actor.spawn(${genExpr(other)})"
    case Term.Apply.After_4_6_0(Term.Name("self"), argClause)
        if argClause.values.isEmpty =>
      "Actor.self()"
    case Term.Apply.After_4_6_0(Term.Name("exit"), argClause)
        if argClause.values.size == 2 =>
      val pidJs    = genExpr(argClause.values(0).asInstanceOf[Term])
      val reasonJs = genExpr(argClause.values(1).asInstanceOf[Term])
      s"Actor.exit($pidJs, $reasonJs)"
    // v1.6 Phase 2 — supervision primitives
    case Term.Apply.After_4_6_0(Term.Name("link"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.link(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("monitor"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.monitor(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("demonitor"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.demonitor(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("trapExit"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.trapExit(${genExpr(argClause.values.head.asInstanceOf[Term])})"

    // Special forms: computed / effect — wrap the by-name body as a
    // zero-arg thunk so the reactive scheduler can rerun it when its
    // signal deps change.
    case Term.Apply.After_4_6_0(Term.Name(react @ ("computed" | "effect")), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      val bodyJs = genExpr(bodyArgClause.values.head.asInstanceOf[Term])
      s"$react(() => $bodyJs)"

    // v1.5 Tier 5 #20 — `validate { body }` collects all `require*`
    // errors raised inside `body` and returns Right(value) / Left(map).
    case Term.Apply.After_4_6_0(Term.Name("validate"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      val bodyJs = genExpr(bodyArgClause.values.head.asInstanceOf[Term])
      s"validate(() => $bodyJs)"

    // Function application
    case app: Term.Apply =>
      genApply(app)

    // Infix
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause) =>
      val lhsJs = genExpr(lhs)
      val args = argClause.values
      val rhsJs = if args.length == 1 then genExpr(args.head) else args.map(genExpr).mkString(", ")
      op.value match
        case "::" => s"[${genExpr(lhs)}, ...(${genExpr(args.head)})]"
        case ":+" => s"[...($lhsJs), ${genExpr(args.head)}]"
        case "+:" => s"[${genExpr(lhs)}, ...(${genExpr(args.head)})]"
        case "++" | ":::" => s"[...($lhsJs), ...(${genExpr(args.head)})]"
        // HTML DSL: `attr.cls := "hero"` builds an Attr object.
        case ":=" => s"_attr($lhsJs, $rhsJs)"
        // v1.6 actors: `pid ! msg` enqueues into the receiver's mailbox.
        case "!" => s"Actor.send($lhsJs, $rhsJs)"
        case "->" =>
          val tmp = freshTmp()
          s"(() => { const $tmp = [$lhsJs, $rhsJs]; $tmp._isTuple = true; return $tmp; })()"
        case "*" =>
          // Could be string * n or numeric.  Wrap `lhsJs` in parens so a
          // bare number literal (`1 * 1`) doesn't trip JS's number-then-`.`
          // parse rule on the string-repeat fallback branch.
          s"(typeof ($lhsJs) === 'string' ? ($lhsJs).repeat($rhsJs) : ($lhsJs) * ($rhsJs))"
        case "==" => s"($lhsJs === $rhsJs)"
        case "!=" => s"($lhsJs !== $rhsJs)"
        case "&&" => s"($lhsJs && $rhsJs)"
        case "||" => s"($lhsJs || $rhsJs)"
        case "to" =>
          // n to m → array [n, n+1, ..., m]
          s"_dispatch($lhsJs, 'to', [$rhsJs])"
        case "until" =>
          // n until m → array [n, n+1, ..., m-1]
          s"_dispatch($lhsJs, 'until', [$rhsJs])"
        case "/" if isIntExpr(lhs) && args.headOption.exists(isIntExpr) =>
          s"Math.trunc($lhsJs / $rhsJs)"
        case other => s"($lhsJs $other $rhsJs)"

    // Prefix unary operators: `!x`, `-x`, `+x`, `~x`.
    case t: Term.ApplyUnary =>
      val argJs = genExpr(t.arg)
      t.op.value match
        case "!" => s"!($argJs)"
        case "-" => s"-($argJs)"
        case "+" => s"+($argJs)"
        case "~" => s"~($argJs)"
        case op  => s"/* unsupported unary $op */"

    case other =>
      s"/* unsupported: ${other.productPrefix} */"

  private def genApply(app: Term.Apply): String =
    // .copy(field = value, ...) — spread the receiver, override named fields.
    // Intercepted before argVals are computed so Term.Assign doesn't fall into
    // genExpr's `lhs = rhs` path (which would emit a JS assignment expression).
    app.fun match
      case Term.Select(qual, Term.Name("copy")) =>
        return genCopy(qual, app.argClause.values)
      case _ => ()

    // Focus[T](_.a.b) / Focus(_.a.b) — emit a Lens object built from the
    // syntactic field path. The lambda body is inspected at codegen time;
    // letting it through normal genExpr would lose the path information.
    app.fun match
      case ta: Term.ApplyType if isFocusFun(ta.fun) =>
        return genFocus(app.argClause.values)
      case Term.Name("Focus") =>
        return genFocus(app.argClause.values)
      case _ => ()

    val argVals = app.argClause.values.map(genExpr)
    app.fun match
      // println / print
      case Term.Name("println") =>
        if argVals.isEmpty then "_println()"
        else s"_println(${argVals.map(a => s"_show($a)").mkString(", ")})"
      case Term.Name("print") =>
        if argVals.isEmpty then "_print()"
        else s"_print(${argVals.map(a => s"_show($a)").mkString(", ")})"

      // Map constructor - args are tuple pairs
      case Term.Name("Map") =>
        s"_Map(${argVals.mkString(", ")})"

      // List constructor
      case Term.Name("List") =>
        s"[${argVals.mkString(", ")}]"

      // Some / None
      case Term.Name("Some") | Term.Name("_Some") =>
        s"_Some(${argVals.mkString(", ")})"

      // assert
      case Term.Name("assert") =>
        s"assert(${argVals.mkString(", ")})"

      // foldLeft curried: Apply(Apply(Select(xs, "foldLeft"), [init]), [f])
      case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name("foldLeft")), initArgClause) =>
        val qualJs = genExpr(qual)
        val initJs = initArgClause.values.map(genExpr).mkString(", ")
        val fJs = argVals.mkString(", ")
        s"_seqFoldLeft($qualJs, $initJs, $fJs)"

      // foldRight curried
      case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name("foldRight")), initArgClause) =>
        val qualJs = genExpr(qual)
        val initJs = initArgClause.values.map(genExpr).mkString(", ")
        val fJs = argVals.mkString(", ")
        s"(($qualJs).reduceRight(($fJs === undefined ? (acc,x) => acc : (acc,x) => ($fJs)(x, acc)), $initJs))"

      // Method calls: obj.method(args) → _dispatch(obj, "method", [args])
      case Term.Select(qual, Term.Name(method)) =>
        val qualJs = genExpr(qual)
        method match
          // String * n handled specially
          case _ =>
            val argsJs = argVals.mkString(", ")
            s"_dispatch($qualJs, '$method', [$argsJs])"

      // Regular function call or constructor — wrap in `_call` so a
      // bare Array / Map reference (`xs(i)` / `m(k)`) is dispatched as
      // indexing rather than failing with "not a function".
      case fun =>
        val funJs = genExpr(fun)
        s"_call($funJs, ${argVals.mkString(", ")})"

  // ─── Lenses / Focus / .copy ──────────────────────────────────────

  private def isFocusFun(t: Term): Boolean = t match
    case Term.Name("Focus") => true
    case _                  => false

  private def genCopy(qual: Term, args: List[Term]): String =
    val qualJs = genExpr(qual)
    val positional = args.collect {
      case t if !t.isInstanceOf[Term.Assign] => genExpr(t)
    }
    val named = args.collect {
      case Term.Assign(Term.Name(field), rhs) => s"$field: ${genExpr(rhs)}"
    }
    if positional.isEmpty then
      // All-named — emit a plain spread for clarity / speed.
      if named.isEmpty then s"({...$qualJs})"
      else s"({...$qualJs, ${named.mkString(", ")}})"
    else
      // Mixed or all-positional — route through the `_copy` runtime helper,
      // which uses the object's own key order to map positionals to fields.
      val posArr = s"[${positional.mkString(", ")}]"
      val namedObj = if named.isEmpty then "{}" else s"{${named.mkString(", ")}}"
      s"_copy($qualJs, $posArr, $namedObj)"

  private def genFocus(args: List[Term]): String = args match
    case List(lambda) =>
      val stepsOpt: Option[List[String]] = lambda match
        case Term.AnonymousFunction(body) =>
          extractPathSteps(body, _.isInstanceOf[Term.Placeholder])
        case Term.Function.After_4_6_0(paramClause, body) =>
          paramClause.values.headOption.map(_.name.value).flatMap { p =>
            extractPathSteps(body, {
              case Term.Name(n) => n == p
              case _            => false
            })
          }
        case _ => None
      stepsOpt match
        case Some(steps) if steps.nonEmpty =>
          // Steps are JS-code fragments: each entry is a literal that goes
          // straight into the array.  Field / __some__ / __each__ encode
          // as plain strings ('field' / '__some__' / '__each__'); v0.9
          // `.index(i)` / `.at(k)` encode as small object literals
          // (`{kind:'index',i:3}` / `{kind:'at',key:'u-42'}`).
          val stepLiterals = s"[${steps.mkString(", ")}]"
          val hasIndexOrAt =
            steps.exists(s => s.startsWith("{kind:'index'") || s.startsWith("{kind:'at'"))
          if steps.contains("'__each__'")                      then s"_makeTraversal($stepLiterals)"
          else if steps.contains("'__some__'") || hasIndexOrAt then s"_makeOptional($stepLiterals)"
          else                                                       s"_makeLens($stepLiterals)"
        case _ =>
          s"(()=>{ throw new Error('Focus: expected a field-access lambda like _.field.subfield'); })()"
    case _ =>
      s"(()=>{ throw new Error('Focus expects exactly one lambda argument'); })()"

  private def extractPathSteps(body: Term, isBase: Term => Boolean): Option[List[String]] =
    def jsLit(lit: Lit): Option[String] = lit match
      case Lit.String(v)  => Some("\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
      case Lit.Int(v)     => Some(v.toString)
      case Lit.Long(v)    => Some(v.toString)
      case Lit.Double(v)  => Some(v.toString)
      case Lit.Boolean(v) => Some(v.toString)
      case _              => None
    def loop(t: Term, acc: List[String]): Option[List[String]] = t match
      case Term.Select(qual, Term.Name("some")) => loop(qual, "'__some__'" :: acc)
      case Term.Select(qual, Term.Name("each")) => loop(qual, "'__each__'" :: acc)
      // v0.9 pointwise — `.index(i)` / `.at(k)`.
      case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name("index")), argClause)
          if argClause.values.size == 1 =>
        argClause.values.head match
          case Lit.Int(i)  => loop(qual, s"{kind:'index',i:$i}" :: acc)
          case Lit.Long(i) => loop(qual, s"{kind:'index',i:$i}" :: acc)
          case _           => None
      case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name("at")), argClause)
          if argClause.values.size == 1 =>
        argClause.values.head match
          case lit: Lit => jsLit(lit).flatMap(js => loop(qual, s"{kind:'at',key:$js}" :: acc))
          case _        => None
      case Term.Select(qual, name)              => loop(qual, s"'${name.value}'" :: acc)
      case other if isBase(other)               => Some(acc)
      case _                                     => None
    loop(body, Nil)

  // ─── CPS codegen for effectful contexts ──────────────────────────
  //
  // Inside a handle body (or the body of an effectful function), expressions
  // are emitted in CPS form: every operation that may depend on a Free value
  // is threaded through `_bind`. Plain JS values double as Pure(value), so
  // pure sub-expressions don't pay any wrapping overhead.
  //
  // genCpsExpr(t) returns a JS expression that evaluates to a Free value:
  // either a plain JS value (Pure) or a {_tag:'Perform', eff, op, args, k} node.

  /** Whether `t` is a syntactically simple value reference: no sub-computation,
   *  guaranteed not to be a Perform. Used to avoid pointless `_bind` chains. */
  private def isSimpleCpsExpr(t: Term): Boolean = t match
    case _: Lit                                  => true
    case _: Term.Placeholder                     => true
    case Term.Name(n) if !isEffectfulFun(n)      => true
    case _                                       => false

  /** Bind a list of CPS sub-expressions; pass their resulting plain values to k.
   *  Simple sub-expressions are inlined without a bind. */
  private def bindArgsCps(args: List[Term])(k: List[String] => String): String =
    def loop(remaining: List[Term], acc: List[String]): String = remaining match
      case Nil       => k(acc.reverse)
      case t :: rest =>
        if isSimpleCpsExpr(t) then loop(rest, genExpr(t) :: acc)
        else
          val v = freshTmp()
          s"_bind(${genCpsExpr(t)}, $v => ${loop(rest, v :: acc)})"
    loop(args, Nil)

  /** Generate a JS expression in CPS form. */
  private def genCpsExpr(term: Term): String = term match
    // Literals / names — pure values pass straight through
    case _: Lit              => genExpr(term)
    case _: Term.Placeholder => genExpr(term)
    case Term.Name(_)        => genExpr(term)

    // Block — chain stats through _bind
    case Term.Block(stats) => genCpsBlockAsIife(stats)

    // If — bind cond, then branch (each branch is CPS)
    case t: Term.If =>
      val thenJs = genCpsExpr(t.thenp)
      val elseJs = t.elsep match
        case Lit.Unit() => "undefined"
        case e          => genCpsExpr(e)
      if isSimpleCpsExpr(t.cond) then s"(${genExpr(t.cond)} ? ($thenJs) : ($elseJs))"
      else
        val tmp = freshTmp()
        s"_bind(${genCpsExpr(t.cond)}, $tmp => $tmp ? ($thenJs) : ($elseJs))"

    // String interpolation — bind args
    case Term.Interpolate(Term.Name(prefix), parts, args)
        if prefix == "s" || prefix == "f" || prefix == "md" =>
      bindArgsCps(args.map(_.asInstanceOf[Term])) { vs =>
        val sb2 = StringBuilder()
        sb2.append("`")
        for i <- parts.indices do
          val part = parts(i).asInstanceOf[Lit.String].value
          // Backslash first — see twin in genExpr.
          sb2.append(part.replace("\\", "\\\\").replace("`", "\\`").replace("$", "\\$"))
          if i < args.length then sb2.append("${_show(").append(vs(i)).append(")}")
        sb2.append("`")
        val templateLiteral = sb2.toString
        if prefix == "md" then s"_md($templateLiteral)" else templateLiteral
      }

    // Tuple
    case Term.Tuple(elems) =>
      bindArgsCps(elems) { vs =>
        val tmp = freshTmp()
        s"(() => { const $tmp = [${vs.mkString(", ")}]; $tmp._isTuple = true; return $tmp; })()"
      }

    // Lambda — CPS body
    case Term.Function.After_4_6_0(paramClause, body) =>
      val params = paramClause.values.map(_.name.value)
      val bodyJs = body match
        case Term.Block(stats) => genCpsBlockAsIife(stats)
        case expr              => genCpsExpr(expr)
      if params.length == 1 then s"${params.head} => $bodyJs"
      else
        val arity  = params.length
        val joined = params.mkString(", ")
        s"((...__a) => { const [$joined] = (__a.length === 1 && Array.isArray(__a[0]) && __a[0].length === $arity) ? __a[0] : __a; return $bodyJs; })"

    // Anonymous function with placeholders — body is CPS
    case t: Term.AnonymousFunction =>
      phCounters = 0 :: phCounters
      val bodyJs = genCpsExpr(t.body)
      val count  = phCounters.head
      phCounters = phCounters.tail
      val params = (0 until count).map(i => s"_$$${i}")
      if params.isEmpty then s"() => $bodyJs"
      else s"(${params.mkString(", ")}) => $bodyJs"

    // Nested handle inside CPS body — returns Free that we treat like any value
    case Term.Apply.After_4_6_0(
      Term.Apply.After_4_6_0(Term.Name("handle"), bodyArgClause),
      pfArgClause
    ) if bodyArgClause.values.size == 1 =>
      pfArgClause.values match
        case List(pf: Term.PartialFunction) =>
          genHandleForm(bodyArgClause.values.head.asInstanceOf[Term], pf.cases)
        case _ => "/* invalid handle */ undefined"

    // Nested runAsync inside CPS body
    case Term.Apply.After_4_6_0(Term.Name("runAsync"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      s"_runAsync(() => ${genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("runAsyncParallel"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      s"_runAsyncParallel(() => ${genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])})"

    // Nested storage handlers inside CPS body
    case Term.Apply.After_4_6_0(Term.Name("runStorage"), bodyArgClause)
        if bodyArgClause.values.size >= 1 =>
      val bodyJs = genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])
      val pathJs = bodyArgClause.values.lift(1).map(p => genExpr(p.asInstanceOf[Term])).getOrElse("null")
      s"_runStorage(() => $bodyJs, $pathJs)"
    case Term.Apply.After_4_6_0(Term.Name("runEphemeralStorage"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      s"_runStorage(() => ${genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])}, null)"

    // ── v1.6 Actors Phase 1 (inside CPS body) ──────────────────────────
    case Term.Apply.After_4_6_0(Term.Name("runActors"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      s"_runActors(() => ${genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])})"

    case Term.Apply.After_4_6_0(
            Term.Apply.After_4_6_0(Term.Name("receive"), timeoutArgClause),
            pfArgClause)
        if pfArgClause.values.size == 1 && timeoutArgClause.values.size == 1 =>
      val timeoutTerm = timeoutArgClause.values.head match
        case Term.Assign(Term.Name("timeout"), v) => v
        case other: Term                          => other
      pfArgClause.values.head match
        case pf: Term.PartialFunction =>
          val matcherJs = genReceiveMatcher(pf.cases)
          s"Actor.receive_t(_registerReceive($matcherJs), ${genExpr(timeoutTerm.asInstanceOf[Term])})"
        case _ => "/* invalid receive */ undefined"

    case Term.Apply.After_4_6_0(Term.Name("receive"), pfArgClause)
        if pfArgClause.values.size == 1 =>
      pfArgClause.values.head match
        case pf: Term.PartialFunction =>
          val matcherJs = genReceiveMatcher(pf.cases)
          s"Actor.receive_(_registerReceive($matcherJs))"
        case _ => "/* invalid receive */ undefined"

    case Term.Apply.After_4_6_0(Term.Name("spawn"), argClause)
        if argClause.values.size == 1 =>
      // The spawn arg is a behavior thunk.  genCpsExpr on a Function
      // emits a lambda whose body is `_bind`-chained — exactly the
      // shape `Actor.spawn(thunk)` expects.
      s"Actor.spawn(${genCpsExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("self"), argClause)
        if argClause.values.isEmpty =>
      "Actor.self()"
    case Term.Apply.After_4_6_0(Term.Name("exit"), argClause)
        if argClause.values.size == 2 =>
      val pidJs    = genExpr(argClause.values(0).asInstanceOf[Term])
      val reasonJs = genExpr(argClause.values(1).asInstanceOf[Term])
      s"Actor.exit($pidJs, $reasonJs)"
    // v1.6 Phase 2 — supervision primitives (inside CPS body)
    case Term.Apply.After_4_6_0(Term.Name("link"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.link(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("monitor"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.monitor(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("demonitor"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.demonitor(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("trapExit"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.trapExit(${genExpr(argClause.values.head.asInstanceOf[Term])})"

    // Nested computed / effect inside CPS body — same wrapping as the
    // non-CPS form: by-name body becomes a zero-arg thunk.
    case Term.Apply.After_4_6_0(Term.Name(react @ ("computed" | "effect")), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      s"$react(() => ${genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])})"

    // Apply — function or method call
    case app: Term.Apply =>
      genCpsApply(app)

    // Infix — bind both sides, then apply op
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause) =>
      val rhs = argClause.values.head
      bindArgsCps(List(lhs, rhs)) { case List(vl, vr) =>
        op.value match
          case "::"           => s"[$vl, ...$vr]"
          case ":+"           => s"[...$vl, $vr]"
          case "+:"           => s"[$vl, ...$vr]"
          case "++" | ":::"   => s"[...$vl, ...$vr]"
          case "!"            => s"Actor.send($vl, $vr)"
          case "->"           =>
            val tmp = freshTmp()
            s"(() => { const $tmp = [$vl, $vr]; $tmp._isTuple = true; return $tmp; })()"
          case "*"            => s"(typeof ($vl) === 'string' ? ($vl).repeat($vr) : ($vl) * ($vr))"
          case "=="           => s"($vl === $vr)"
          case "!="           => s"($vl !== $vr)"
          case "&&"           => s"($vl && $vr)"
          case "||"           => s"($vl || $vr)"
          case "to"           => s"_dispatch($vl, 'to', [$vr])"
          case "until"        => s"_dispatch($vl, 'until', [$vr])"
          case "/" if isIntExpr(lhs) && isIntExpr(rhs) => s"Math.trunc($vl / $vr)"
          case other          => s"($vl $other $vr)"
        case _ => "/* infix arity mismatch */"
      }

    // Select — bind qual, dispatch
    case Term.Select(qual, name) =>
      bindArgsCps(List(qual)) { case List(q) =>
        s"_dispatch($q, '${name.value}', [])"
        case _ => "/* select arity */"
      }

    // Match — bind scrutinee, then dispatch cases
    case t: Term.Match =>
      val scrutVar = freshTmp()
      val casesJs = t.casesBlock.cases.map(c => genCpsCase(scrutVar, c)).mkString(" else ")
      bindArgsCps(List(t.expr)) { case List(sv) =>
        s"(($scrutVar => { $casesJs else { throw new Error('Match failure: ' + _show($scrutVar)); } })($sv))"
        case _ => "/* match arity */"
      }

    // For-yield in CPS — fall back: the rhs collections / generators don't typically
    // perform effects, so direct codegen with bind on result suffices for now.
    case t: Term.ForYield => genForYield(t.enumsBlock.enums, t.body)
    case t: Term.For      => genForDo(t.enumsBlock.enums, t.body)

    // While — CPS not really meaningful (side-effecting loop). Fall back.
    case t: Term.While    => genExpr(t)

    // Return
    case Term.Return(expr) => genCpsExpr(expr)

    // Default: try direct codegen (covers values, partial functions, etc.)
    case other => genExpr(other)

  /** Call site in CPS mode: bind args, then call. Handles effect ops specially. */
  private def genCpsApply(app: Term.Apply): String =
    val args = app.argClause.values
    app.fun match
      // Effect op: Eff.op(args) → _bind args then _perform
      case Term.Select(Term.Name(eff), Term.Name(op)) if isEffectOpRef(eff, op) =>
        bindArgsCps(args) { vs =>
          s"_perform('$eff', '$op', [${vs.mkString(", ")}])"
        }

      // println / print: stringify and call
      case Term.Name("println") =>
        bindArgsCps(args) { vs =>
          val callArgs = vs.map(v => s"_show($v)").mkString(", ")
          s"_println($callArgs)"
        }
      case Term.Name("print") =>
        bindArgsCps(args) { vs =>
          val callArgs = vs.map(v => s"_show($v)").mkString(", ")
          s"_print($callArgs)"
        }

      // Builtin constructors
      case Term.Name("Map") =>
        bindArgsCps(args) { vs => s"_Map(${vs.mkString(", ")})" }
      case Term.Name("List") =>
        bindArgsCps(args) { vs => s"[${vs.mkString(", ")}]" }
      case Term.Name("Some") | Term.Name("_Some") =>
        bindArgsCps(args) { vs => s"_Some(${vs.mkString(", ")})" }
      case Term.Name("assert") =>
        bindArgsCps(args) { vs => s"assert(${vs.mkString(", ")})" }

      // foldLeft curried: bind qual + init + f
      case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name("foldLeft")), initArgClause) =>
        bindArgsCps(qual :: initArgClause.values ++ args) { vs =>
          val q = vs.head; val init = vs(1); val f = vs(2)
          s"_seqFoldLeft($q, $init, $f)"
        }

      // foldRight curried
      case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name("foldRight")), initArgClause) =>
        bindArgsCps(qual :: initArgClause.values ++ args) { vs =>
          val q = vs.head; val init = vs(1); val f = vs(2)
          s"(($q).reduceRight((acc, x) => ($f)(x, acc), $init))"
        }

      // Method call: obj.method(args) → _dispatch
      case Term.Select(qual, Term.Name(method)) =>
        bindArgsCps(qual :: args) { vs =>
          s"_dispatch(${vs.head}, '$method', [${vs.tail.mkString(", ")}])"
        }

      // Regular function call: bind args, then call (function value itself is simple)
      case fun =>
        if isSimpleCpsExpr(fun) then
          bindArgsCps(args) { vs =>
            s"${genExpr(fun)}(${vs.mkString(", ")})"
          }
        else
          bindArgsCps(fun :: args) { vs =>
            s"${vs.head}(${vs.tail.mkString(", ")})"
          }

  /** Block as IIFE in CPS form — chains statements through _bind. */
  private def genCpsBlockAsIife(stats: List[Stat]): String =
    if stats.isEmpty then "undefined"
    else
      def build(remaining: List[Stat]): String = remaining match
        case Nil => "undefined"
        case List(s) =>
          s match
            case t: Term => genCpsExpr(t)
            case Defn.Val(_, List(Pat.Var(n)), _, rhs) =>
              // Last statement is a binding — block evaluates to undefined.
              // Still bind it so its effects (if any) run.
              s"_bind(${genCpsExpr(rhs)}, ${n.value} => undefined)"
            case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs) =>
              s"_bind(${genCpsExpr(rhs)}, ${n.value} => undefined)"
            case stat =>
              s"(() => { ${genStatInline(stat)} return undefined; })()"
        case s :: rest =>
          s match
            case Defn.Val(_, List(Pat.Var(n)), _, rhs) =>
              s"_bind(${genCpsExpr(rhs)}, ${n.value} => ${build(rest)})"
            case Defn.Val(_, List(pat), _, rhs) =>
              val patJs = genPatDestructure(pat)
              val tmp = freshTmp()
              s"_bind(${genCpsExpr(rhs)}, $tmp => { const $patJs = $tmp; return ${build(rest)}; })"
            case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs) =>
              // For simplicity, treat var like val in CPS context.
              s"_bind(${genCpsExpr(rhs)}, ${n.value} => ${build(rest)})"
            case d: Defn.Def =>
              // Function definition in block — emit as nested function declaration
              val fnJs = genCpsInlineFn(d)
              s"((${d.name.value}) => ${build(rest)})($fnJs)"
            case t: Term =>
              if isSimpleCpsExpr(t) then s"(${genExpr(t)}, ${build(rest)})"
              else s"_bind(${genCpsExpr(t)}, _ => ${build(rest)})"
            case stat =>
              s"(() => { ${genStatInline(stat)} return ${build(rest)}; })()"
      build(stats)

  /** Emit a function definition as an inline function value in CPS form. */
  private def genCpsInlineFn(d: Defn.Def): String =
    val params = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).map(_.name.value)
    val paramsStr = params.mkString(", ")
    d.body match
      case Term.Block(stats) => s"(${paramsStr}) => ${genCpsBlockAsIife(stats)}"
      case expr              => s"(${paramsStr}) => ${genCpsExpr(expr)}"

  /** CPS case generator — like genCase but the body is CPS. */
  private def genCpsCase(scrutVar: String, c: Case): String =
    val (cond, bindings) = genPattern(scrutVar, c.pat)
    val bindingStmts = bindings.map { case (name, expr) => s"const $name = $expr;" }.mkString(" ")
    val bodyJs = genCpsExpr(c.body)
    c.cond match
      case Some(guard) =>
        val guardExpr = genExpr(guard)
        val condStr = if cond == "true" then s"($guardExpr)" else s"($cond) && ($guardExpr)"
        s"if ($condStr) { $bindingStmts return $bodyJs; }"
      case None =>
        val condStr = if cond == "true" then "true" else s"($cond)"
        s"if ($condStr) { $bindingStmts return $bodyJs; }"

  private def genCase(scrutVar: String, c: Case): String =
    val (cond, bindings) = genPattern(scrutVar, c.pat)
    val bindingStmts = bindings.map { case (name, expr) => s"const $name = $expr;" }.mkString(" ")
    val bodyJs = genExpr(c.body)

    // Pattern guard: we need bindings set up before evaluating the guard.
    // We use a nested IIFE to set up bindings, evaluate guard, then run body.
    c.cond match
      case Some(guard) if bindings.nonEmpty =>
        // Put bindings and guard inside the if block
        val guardExpr = genExpr(guard)
        val patCond = if cond == "true" then "" else s"($cond) && "
        s"if (${patCond}(() => { $bindingStmts return $guardExpr; })()) { $bindingStmts return $bodyJs; }"
      case Some(guard) =>
        val guardExpr = genExpr(guard)
        val condStr = if cond == "true" then s"($guardExpr)" else s"($cond) && ($guardExpr)"
        s"if ($condStr) { return $bodyJs; }"
      case None =>
        val condStr = if cond == "true" then "true" else s"($cond)"
        s"if ($condStr) { $bindingStmts return $bodyJs; }"

  /** Returns (condition JS, list of (varName, expr) bindings).
   *  The condition must be true for the pattern to match.
   *  Bindings are set up when condition is true.
   */
  private def genPattern(scrutVar: String, pat: Pat): (String, List[(String, String)]) = pat match
    case Pat.Wildcard() =>
      ("true", Nil)

    case Pat.Var(n) =>
      ("true", List(n.value -> scrutVar))

    case lit: Lit =>
      val litJs = lit match
        case Lit.Int(v)     => v.toString
        case Lit.Long(v)    => v.toString
        case Lit.Double(v)  => v.toString
        case Lit.String(v)  => "\"" + v.replace("\"", "\\\"") + "\""
        case Lit.Boolean(v) => v.toString
        case Lit.Null()     => "null"
        case _              => "undefined"
      (s"$scrutVar === $litJs", Nil)

    case Pat.Typed(inner, _) =>
      genPattern(scrutVar, inner)

    case Pat.Tuple(pats) =>
      val subConditions = pats.zipWithIndex.map { (p, i) =>
        genPattern(s"$scrutVar[$i]", p)
      }
      val cond = subConditions.map(_._1).filter(_ != "true").mkString(" && ")
      val bindings = subConditions.flatMap(_._2)
      (if cond.isEmpty then "true" else cond, bindings)

    case Pat.Extract.After_4_6_0(fn, argClause) =>
      val typeName = fn match
        case Term.Name(n)                 => n
        case Term.Select(_, Term.Name(n)) => n
        case _                            => "?"
      val args = argClause.values

      typeName match
        case "Some" =>
          val innerScrutVar = s"$scrutVar.value"
          val subConds = if args.isEmpty then Nil
            else args.zipWithIndex.map { (p, i) =>
              genPattern(if args.length == 1 then innerScrutVar else s"$innerScrutVar[$i]", p)
            }
          val subCond = subConds.map(_._1).filter(_ != "true").mkString(" && ")
          val bindings = subConds.flatMap(_._2)
          val cond = s"($scrutVar && $scrutVar._type === '_Some')" +
            (if subCond.nonEmpty then s" && $subCond" else "")
          (cond, bindings)

        case "None" =>
          (s"($scrutVar && $scrutVar._type === '_None')", Nil)

        case _ =>
          // Case class or enum case extract
          val fields = args.zipWithIndex.map { (p, i) =>
            genPattern(s"Object.values($scrutVar).slice(1)[$i]", p)
          }
          val typeCond = s"($scrutVar && $scrutVar._type === '$typeName')"
          val subCond = fields.map(_._1).filter(_ != "true").mkString(" && ")
          val bindings = fields.flatMap(_._2)
          val cond = typeCond + (if subCond.nonEmpty then s" && $subCond" else "")
          (cond, bindings)

    case Pat.Alternative(lhs, rhs) =>
      // Either alternative matches, no bindings from alternatives typically
      val (lCond, _) = genPattern(scrutVar, lhs)
      val (rCond, _) = genPattern(scrutVar, rhs)
      (s"($lCond || $rCond)", Nil)

    // Enum singleton reference: case Red => or case Color.Red =>
    case t: Term.Name =>
      t.value match
        case "None" => (s"($scrutVar && $scrutVar._type === '_None')", Nil)
        case n      => (s"($scrutVar === $n || ($scrutVar && $scrutVar._type === '$n'))", Nil)

    case Term.Select(_, Term.Name(n)) =>
      if n == "None" then (s"($scrutVar && $scrutVar._type === '_None')", Nil)
      else (s"($scrutVar === $n || ($scrutVar && $scrutVar._type === '$n'))", Nil)

    case _ =>
      ("true", Nil)

  private def genForDo(enums: List[Enumerator], body: Term): String =
    genForDoHelper(enums, genExpr(body))

  private def genForDoHelper(enums: List[Enumerator], bodyJs: String): String = enums match
    case Nil => s"(() => { $bodyJs; })()"
    case Enumerator.Generator(pat, rhs) :: rest =>
      val rhsJs = genExpr(rhs)
      val iterVar = freshTmp()
      val patJs = genForPatBinding(pat, iterVar)
      val inner = genForDoHelper(rest, bodyJs)
      s"(() => { _dispatch($rhsJs, 'forEach', [($iterVar) => { $patJs $inner; }]); })()"
    case Enumerator.Guard(cond) :: rest =>
      val condJs = genExpr(cond)
      val inner = genForDoHelper(rest, bodyJs)
      s"(() => { if ($condJs) { $inner; } })()"
    case Enumerator.Val(pat, rhs) :: rest =>
      val rhsJs = genExpr(rhs)
      val v = freshTmp()
      val patJs = genForPatBinding(pat, v)
      val inner = genForDoHelper(rest, bodyJs)
      s"(() => { const $v = $rhsJs; $patJs $inner; })()"
    case _ :: rest => genForDoHelper(rest, bodyJs)

  private def genForYield(enums: List[Enumerator], body: Term): String =
    genForYieldHelper(enums, genExpr(body))

  private def genForYieldHelper(enums: List[Enumerator], bodyJs: String): String = enums match
    case Nil => bodyJs
    case Enumerator.Generator(pat, rhs) :: Nil =>
      val rhsJs = genExpr(rhs)
      val iterVar = freshTmp()
      val patJs = genForPatBinding(pat, iterVar)
      if patJs.isEmpty then
        s"_dispatch($rhsJs, 'map', [($iterVar) => $bodyJs])"
      else
        s"_dispatch($rhsJs, 'map', [($iterVar) => { $patJs return $bodyJs; }])"
    case Enumerator.Generator(pat, rhs) :: rest =>
      val rhsJs = genExpr(rhs)
      val iterVar = freshTmp()
      val patJs = genForPatBinding(pat, iterVar)
      val inner = genForYieldHelper(rest, bodyJs)
      if patJs.isEmpty then
        s"_dispatch($rhsJs, 'flatMap', [($iterVar) => $inner])"
      else
        s"_dispatch($rhsJs, 'flatMap', [($iterVar) => { $patJs return $inner; }])"
    case Enumerator.Guard(cond) :: rest =>
      val condJs = genExpr(cond)
      val inner = genForYieldHelper(rest, bodyJs)
      // wrap in filter; but we need the generator context
      // For guard as first enum (unusual), filter is not trivially accessible
      // Return inner filtered - but we don't have a collection here, use conditional
      s"($condJs ? [$inner] : [])"
    case Enumerator.Val(pat, rhs) :: rest =>
      val rhsJs = genExpr(rhs)
      val v = freshTmp()
      val patJs = genForPatBinding(pat, v)
      val inner = genForYieldHelper(rest, bodyJs)
      s"(() => { const $v = $rhsJs; $patJs return $inner; })()"
    case _ :: rest => genForYieldHelper(rest, bodyJs)

  private def genForPatBinding(pat: Pat, scrutVar: String): String = pat match
    case Pat.Var(n) if n.value == scrutVar => ""
    case Pat.Var(n) => s"const ${n.value} = $scrutVar;"
    case Pat.Wildcard() => ""
    case Pat.Tuple(pats) =>
      pats.zipWithIndex.map { (p, i) =>
        p match
          case Pat.Var(n) => s"const ${n.value} = $scrutVar[$i];"
          case Pat.Wildcard() => ""
          case inner => genForPatBinding(inner, s"$scrutVar[$i]")
      }.mkString(" ")
    case Pat.Extract.After_4_6_0(_, argClause) =>
      argClause.values.zipWithIndex.map { (p, i) =>
        p match
          case Pat.Var(n) => s"const ${n.value} = Object.values($scrutVar).slice(1)[$i];"
          case _ => ""
      }.mkString(" ")
    case _ => ""

  private def mapName(name: String): String = name match
    case "println" => "_println"
    case "print"   => "_print"
    case "Some"    => "_Some"
    case "None"    => "_None"
    case other     => other

  /** Returns true if the term is provably integer-valued (no decimal arithmetic). */
  private def isIntExpr(t: Term): Boolean = t match
    case _: Lit.Int | _: Lit.Long                 => true
    case _: Lit.Double | _: Lit.Float              => false
    case Term.Name(n)                              => intVars.contains(n)
    case Term.Apply.After_4_6_0(Term.Select(_, Term.Name("toInt" | "toLong")), _) => true
    case Term.Apply.After_4_6_0(Term.Select(_, Term.Name("toDouble" | "toFloat")), _) => false
    // Collection / String methods that always return an Int regardless of the
    // element type — both the field-access (`xs.length`) and the apply
    // (`xs.indexOf(x)`) forms.
    case Term.Select(_, Term.Name("length" | "size")) => true
    case Term.Apply.After_4_6_0(Term.Select(_, Term.Name("length" | "size" | "indexOf" | "lastIndexOf" | "count")), _) => true
    // `xs.foldLeft(init)(_ + _)` returns whatever `init` is — Int when the
    // seed literal is Int.
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Select(_, Term.Name("foldLeft" | "foldRight" | "fold")), initClause),
        _) => initClause.values.headOption.exists(isIntExpr)
    case Term.ApplyInfix.After_4_6_0(l, Term.Name(op), _, argClause)
        if Set("+", "-", "*", "/", "%").contains(op) =>
      argClause.values.headOption.exists(r => isIntExpr(l) && isIntExpr(r))
    case _ => false

