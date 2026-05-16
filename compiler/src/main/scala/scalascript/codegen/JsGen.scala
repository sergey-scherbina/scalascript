package scalascript.codegen

import scalascript.ast.*
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
  def generate(module: Module, baseDir: Option[os.Path] = None): String =
    val gen = new JsGen(baseDir)
    gen.genModule(module)

  /** Generate segments in document order, preserving scala/scalascript interleaving. */
  def generateSegmented(module: Module, baseDir: Option[os.Path] = None): List[Segment] =
    val gen = new JsGen(baseDir)
    gen.genModuleSegmented(module)

  /** True if the module contains at least one scalascript block. */
  def hasBlocks(module: Module): Boolean =
    module.sections.exists(sectionHas)

  private def sectionHas(s: Section): Boolean =
    s.content.exists {
      case cb: Content.CodeBlock => Lang.isScalaScript(cb.lang)
      case _                     => false
    } || s.subsections.exists(sectionHas)

/** JS runtime preamble embedded in every generated page. Split into two
 *  triple-quoted parts because the combined source exceeds the JVM's
 *  64KB string-literal limit. */
private val JsRuntimePart1: String = """
let _output = [];
function _println(...args) { _output.push(args.map(_show).join(' ')); }
function _print(...args) { const s = args.map(_show).join(''); _output.push(s); }

// `_call(fn, ...args)` — wrapper for arbitrary callables in user code.
// Functions are invoked directly; Arrays and Maps route through `_dispatch`
// so `xs(i)` / `m(k)` behave like List / Map indexing.
function _call(fn, ...args) {
  if (typeof fn === 'function') return fn(...args);
  if (Array.isArray(fn) || fn instanceof Map) return _dispatch(fn, 'apply', args);
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

// ── REST routing + serve(port) ─────────────────────────────────────────
// Matches the interpreter / JVM-backend semantics: route(method, path)
// registers a closure, serve(port) starts Node's http.createServer and
// dispatches.  Node's event loop keeps the process alive — no Thread.join
// needed.  Browser-side execution is intentionally out of scope: this
// runtime require()s 'http' which only exists in Node.
const _routes = [];
const _wsRoutes = [];

function _parsePath(p) {
  return p.split('/').filter(s => s.length > 0).map(s =>
    s.startsWith(':') ? { kind: 'cap', name: s.slice(1) }
                      : { kind: 'lit', value: s });
}

function route(method, path) {
  return function(handler) {
    _routes.push({ method: method.toUpperCase(), pattern: _parsePath(path), handler });
  };
}

// onWebSocket(path)(handler) — handler receives a WebSocket object with
// `send` / `close` methods and `onMessage` / `onClose` registration.
// Path matching shares `_parsePath` with `route` (literal + `:name`
// captures), so `/chat/:room` works the same way for both.
function onWebSocket(path) {
  return function(handler) {
    _wsRoutes.push({ pattern: _parsePath(path), handler });
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
  return {
    _type:   'Request',
    method:  req.method,
    path:    u.pathname,
    params,
    query,
    headers,
    body,
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
function _mkResp(fields) {
  const r = { _type: 'Response', ...fields };
  r.withSession  = function(payload) { return _withSession(this, payload); };
  r.clearSession = function() { return _clearSessionOn(this); };
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
function _wsMakeWebSocket(socket) {
  let onMessage = null;
  let onClose   = null;
  let closing   = false;
  let inBuf     = Buffer.alloc(0);

  const ws = {
    _type: 'WebSocket',
    send: (s) => {
      if (!closing && !socket.destroyed) socket.write(_wsEncodeText(s));
    },
    close: (code, reason) => {
      if (!closing && !socket.destroyed) {
        closing = true;
        socket.write(_wsEncodeClose(code ?? 1000, reason ?? ''));
        socket.end();
      }
    },
    onMessage: (cb) => { onMessage = cb; },
    onClose:   (cb) => { onClose   = cb; }
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
          case 0xA: break;                                                     // pong
          case 0x8: {                                                          // close
            const status = f.payload.length >= 2 ? f.payload.readUInt16BE(0) : 1000;
            if (!closing) { closing = true; socket.write(_wsEncodeClose(status, '')); }
            socket.end();
            break;
          }
          case 0x1: case 0x2: {                                                // text / binary
            if (onMessage) {
              const msg = f.opcode === 0x1 ? f.payload.toString('utf-8') : f.payload.toString('latin1');
              try { onMessage(msg); } catch (e) { console.error('WS message handler:', e.message); }
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
      const clientKey = req.headers['sec-websocket-key'];
      if (!clientKey) { socket.destroy(); return; }
      const accept = _wsAcceptKey(clientKey);
      socket.write(
        'HTTP/1.1 101 Switching Protocols\r\n' +
        'Upgrade: websocket\r\n' +
        'Connection: Upgrade\r\n' +
        'Sec-WebSocket-Accept: ' + accept + '\r\n\r\n'
      );
      const ws = _wsMakeWebSocket(socket);
      try { r.handler(ws); } catch (e) { console.error('WS upgrade handler:', e.message); }
      return;
    }
    socket.write('HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n');
    socket.destroy();
  } catch (e) {
    try { socket.destroy(); } catch (_) {}
  }
}

function serve(port) {
  const http = require('http');
  const server = http.createServer((req, res) => {
    // Collect chunks as Buffers (not strings) so multipart file uploads
    // round-trip byte-for-byte.
    const chunks = [];
    req.on('data', c => chunks.push(c));
    req.on('end', () => {
      try {
        const bodyBuf = Buffer.concat(chunks);
        const method = req.method.toUpperCase();
        const u = new URL(req.url, 'http://localhost');
        const segs = u.pathname.split('/').filter(s => s.length > 0);
        for (const r of _routes) {
          if (r.method !== method) continue;
          const params = _matchPath(r.pattern, segs);
          if (params == null) continue;
          const request = _mkRequest(req, params, bodyBuf);
          const result  = r.handler(request);
          const headers = result && result.headers instanceof Map ? result.headers : new Map();
          if (!headers.has('Content-Type')) headers.set('Content-Type', 'text/plain; charset=utf-8');
          const out = headers ? Object.fromEntries(headers.entries()) : {};
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
          res.writeHead(result.status ?? 200, out);
          res.end(result.body ?? '');
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
  });
  // WebSocket upgrade lives on the same server — Node hands us the raw
  // socket (post-headers) and stays out of the way after.
  server.on('upgrade', (req, socket, _head) => _wsHandleUpgrade(req, socket));
  server.listen(port, () => console.log(`Listening on http://localhost:${port}/`));
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
  if (v && v._type) {
    const fields = Object.entries(v).filter(([k]) => k !== '_type');
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

// ── Optional runtime — partial optic for paths containing `.some` ─────
// Steps are an array of strings: a field name, or "__some__" to traverse
// into the inner value of a Some(...).
function _opticGetOption(steps, s) {
  let v = s;
  for (let i = 0; i < steps.length; i++) {
    const step = steps[i];
    if (step === '__some__') {
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
  // Extension method fallback: look up _ext_<paramName>_<method>
  // We try to find registered extension functions by method name
  if (typeof _extensions !== 'undefined' && _extensions[method]) {
    const fns = _extensions[method];
    for (const fn of fns) {
      try { return fn(obj, ...args); } catch(e) { /* try next */ }
    }
  }
  throw new Error('Method not found: ' + method + ' on ' + _show(obj));
}

const _extensions = {};
function _registerExt(method, fn) {
  if (!_extensions[method]) _extensions[method] = [];
  _extensions[method].push(fn);
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

val JsRuntime: String = JsRuntimePart1 + JsRuntimePart2

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

class JsGen(baseDir: Option[os.Path] = None):
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

  def genModule(module: Module): String =
    sb.clear()
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
    effectOps.clear()
    effectfulFuns.clear()

    // Built-in `Async` effect — operations always available without
    // requiring the user to declare `effect Async:` themselves.  Lets
    // call sites like `Async.delay(...)` be rewritten to `_perform(...)`
    // and functions that transitively call them get CPS-emitted.
    effectOps ++= Set(
      "Async.delay", "Async.async", "Async.await", "Async.parallel"
    )

    val funBodies = mutable.Map[String, Term]()

    def collectFromStats(stats: List[Stat]): Unit = stats.foreach {
      case d: Defn.Object =>
        d.templ.body.stats.foreach {
          case dd: Defn.Def if isEffectOpDef(dd.body) =>
            effectOps += s"${d.name.value}.${dd.name.value}"
          case _ => ()
        }
      case d: Defn.Def => funBodies(d.name.value) = d.body
      case _           => ()
    }

    def scan(section: Section): Unit =
      section.content.foreach {
        case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
          cb.tree.foreach { node =>
            ScalaNode.fold(node) {
              case Source(stats)     => collectFromStats(stats)
              case Term.Block(stats) => collectFromStats(stats)
              case _                 => ()
            }
          }
        case _ => ()
      }
      section.subsections.foreach(scan)
    module.sections.foreach(scan)

    /** Collect all `name` (function names) and `Eff.op` (effect op refs) referenced
     *  in a tree. We only care about Term.Apply heads (call sites). */
    def callees(tree: scala.meta.Tree): Set[String] = tree match
      case Term.Apply.After_4_6_0(Term.Name(n), argClause) =>
        Set(n) ++ argClause.values.flatMap(callees).toSet
      case Term.Apply.After_4_6_0(Term.Select(Term.Name(qual), Term.Name(method)), argClause) =>
        Set(s"$qual.$method") ++ argClause.values.flatMap(callees).toSet
      case Term.Apply.After_4_6_0(fun, argClause) =>
        callees(fun) ++ argClause.values.flatMap(callees).toSet
      case Term.Select(Term.Name(qual), Term.Name(method)) =>
        Set(s"$qual.$method")
      case other =>
        other.children.flatMap(callees).toSet

    // Iterate to fixed point
    var changed = true
    while changed do
      changed = false
      funBodies.foreach { (fname, body) =>
        if !effectfulFuns.contains(fname) then
          val calls = callees(body)
          val isEff = calls.exists(c => effectOps.contains(c) || effectfulFuns.contains(c))
          if isEff then
            effectfulFuns += fname
            changed = true
          end if
        end if
      }
    end while

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
    val resolvedPath = baseDir match
      case Some(dir) => dir / os.RelPath(imp.path)
      case None      => os.Path(imp.path, os.pwd)
    if os.exists(resolvedPath) then
      val childDir = resolvedPath / os.up
      val childModule = Parser.parse(os.read(resolvedPath))
      val childGen = new JsGen(Some(childDir))
      // Emit only the definitions from the imported module (suppress top-level output)
      childModule.sections.foreach { section =>
        section.content.foreach {
          case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
            cb.tree.foreach(childGen.genScalaNode)
          case _ => ()
        }
        section.subsections.foreach(childGen.genSection)
      }
      sb.append(childGen.sb)

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
      val objectName = d.name.value
      // Emit as an IIFE so members can reference earlier siblings —
      // matches Scala semantics where `val s = ...; val css = s.css(...)`
      // inside `object Card:` resolves `s` against the object body.
      // A bare `{k1: ..., k2: ...}` object literal can't do that.
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
        case _ => ()
      }
      val body = decls.mkString(" ")
      val ret  = names.mkString(", ")
      line(s"const $objectName = (() => { $body return { $ret }; })();")

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
      // also register as Show_Int
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
          d.body match
            case defn: Defn.Def =>
              genExtensionDef(recvName, defn)
            case Term.Block(stats) =>
              stats.foreach { case defn: Defn.Def => genExtensionDef(recvName, defn); case _ => () }
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

  private def genExtensionDef(recvName: String, defn: Defn.Def): Unit =
    val mparamVals = defn.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values)
    val paramsStr  = (recvName :: mparamVals.map(formalWithDefault)).mkString(", ")
    val fnName = s"_ext_${recvName}_${defn.name.value}"
    defn.body match
      case Term.Block(bodyStats) =>
        line(s"function $fnName($paramsStr) {")
        indent += 1
        genFunctionBody(bodyStats)
        indent -= 1
        line("}")
      case expr =>
        line(s"function $fnName($paramsStr) { return ${genExpr(expr)}; }")
    // Register extension for dispatch
    line(s"_registerExt('${defn.name.value}', ($recvName, ...args) => $fnName($recvName, ...args));")

  private def genObjectMember(stat: Stat): String = stat match
    case dd: Defn.Def =>
      s"${dd.name.value}: ${genDefAsMethod(dd)}"
    case Defn.Val(_, List(Pat.Var(n)), _, rhs) =>
      s"${n.value}: ${genExpr(rhs)}"
    case _ => ""

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

  private def isEffectOpDef(body: Term): Boolean = body match
    case Term.Name("__effectOp__") => true
    case _                         => false

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

  /** Generate a JS expression string for a scalameta Term. */
  def genExpr(term: Term): String = term match
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
        sb2.append(part.replace("`", "\\`").replace("\\", "\\\\").replace("$", "\\$"))
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
    val pairs  = args.collect {
      case Term.Assign(Term.Name(field), rhs) => s"$field: ${genExpr(rhs)}"
    }
    if pairs.length != args.length then
      s"(()=>{ throw new Error('.copy(...) requires named arguments — e.g. p.copy(x = 5)'); })()"
    else
      s"({...$qualJs, ${pairs.mkString(", ")}})"

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
          // Steps are encoded as strings: a field name, "__some__" for a
          // `.some` traversal, or "__each__" for a `.each` traversal.
          // Runtime helpers dispatch on the marker tokens.
          val stepLiterals = s"[${steps.map(s => s"'$s'").mkString(", ")}]"
          if steps.contains("__each__")      then s"_makeTraversal($stepLiterals)"
          else if steps.contains("__some__") then s"_makeOptional($stepLiterals)"
          else                                    s"_makeLens($stepLiterals)"
        case _ =>
          s"(()=>{ throw new Error('Focus: expected a field-access lambda like _.field.subfield'); })()"
    case _ =>
      s"(()=>{ throw new Error('Focus expects exactly one lambda argument'); })()"

  private def extractPathSteps(body: Term, isBase: Term => Boolean): Option[List[String]] =
    def loop(t: Term, acc: List[String]): Option[List[String]] = t match
      case Term.Select(qual, Term.Name("some")) => loop(qual, "__some__" :: acc)
      case Term.Select(qual, Term.Name("each")) => loop(qual, "__each__" :: acc)
      case Term.Select(qual, name)              => loop(qual, name.value :: acc)
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
          sb2.append(part.replace("`", "\\`").replace("\\", "\\\\").replace("$", "\\$"))
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

