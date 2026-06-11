package scalascript.codegen

/** JS runtime preamble (part 1c) — see `JsRuntimePart1a`. */
val JsRuntimePart1c: String = """
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
  if (_isMap(claims)) m = claims;
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
  if (_isMap(claims)) m = claims;
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
  if (_isMap(cfg)) cfg.forEach((v, k) => out[String(k)] = String(v));
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
  const session = _isMap(req.session) ? req.session : new Map();
  const expected = session.get('csrf') || '';
  const form     = _isMap(req.form) ? req.form : new Map();
  let supplied = form.get('csrf');
  if (!supplied && _isMap(req.headers)) {
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
  if (_isMap(v)) {
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
  if (_isMap(payload)) m = payload;
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
  const headers = _isMap(resp.headers) ? new Map(resp.headers) : new Map();
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
