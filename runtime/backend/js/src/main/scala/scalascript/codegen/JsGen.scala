package scalascript.codegen

import scalascript.ast.*
import scalascript.transform.{DirectAnorm, DirectTypeUtils, EffectAnalysis}
import scalascript.typeddata.TypedJsonCodecRuntime
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
      intrinsics: Map[scalascript.ir.QualifiedName, scalascript.backend.spi.IntrinsicImpl] = Map.empty,
      lockPath:   Option[os.Path] = None
  ): String =
    val gen = new JsGen(baseDir, intrinsics, lockPath)
    gen.genModule(module)

  /** Generate segments in document order, preserving scala/scalascript interleaving. */
  def generateSegmented(
      module:     Module,
      baseDir:    Option[os.Path] = None,
      intrinsics: Map[scalascript.ir.QualifiedName, scalascript.backend.spi.IntrinsicImpl] = Map.empty,
      lockPath:   Option[os.Path] = None
  ): List[Segment] =
    val gen = new JsGen(baseDir, intrinsics, lockPath)
    gen.genModuleSegmented(module)

  /** True if the module contains at least one scalascript block. */
  def hasBlocks(module: Module): Boolean =
    module.sections.exists(sectionHas)

  private def sectionHas(s: Section): Boolean =
    s.content.exists {
      case cb: Content.CodeBlock => Lang.isScalaScript(cb.lang)
      case _                     => false
    } || s.subsections.exists(sectionHas)

  // ─── v2.0 Phase 2 — split-runtime emit (JS) ────────────────────────────
  //
  // Mirrors `JvmGen.{generateRuntime, generateUserOnly, detectCapabilities}`.
  //
  // The legacy `generate(module)` returns user code only (no preamble).  The
  // CLI's `buildScjsSource` historically prepended the full runtime preamble
  // before persisting to `.scjs`, so every module artifact carried ~80 KB
  // of duplicate runtime JS.  Phase 2 factors the preamble into a separate
  // `_runtime.scjs-runtime` artifact compiled once per artifact dir and
  // textually concatenated before each module's user code at link time.
  //
  // For JS the equivalent of JVM's `package _ssc_runtime` is just textual
  // concatenation: classic-script JS has a single global namespace, so
  // emitting `function _show(...) { … }` at the top of `out.js` makes the
  // symbol reachable from every later module without imports or namespacing.
  // An IIFE wrapper (`const _ssc_runtime = (function(){ … return { … }; })();`)
  // was considered but rejected — the runtime exports 200+ identifiers and
  // wrapping/destructuring each one is fragile (typed-tag DSL, `Console`,
  // `attr`, `Async`, `Logger`, `Random`, etc. would all need explicit
  // re-exports).  Flat-scope concatenation keeps the runtime body verbatim.

  /** Identifier for a runtime capability that the user module depends on.
   *  Drives the `generateRuntime` capability switch and determines which
   *  helper blocks (effects, async, mcp, dataset) are emitted. */
  sealed trait Capability
  object Capability:
    /** Core runtime: `_show`, `_println`, HTML DSL, given registry, signals,
     *  storage, generators, routes/serve, JSON, ALL the unconditionally-emitted
     *  helpers from `JsRuntime`.  Always present.  Listed as a capability so
     *  the encode/decode round-trip is total. */
    case object Core    extends Capability
    case object Async   extends Capability  // `JsRuntimeAsync` — Async + Actor + Storage
    case object Effects extends Capability  // `JsRuntimeV14Effects` — Logger/Random/Clock/Env/Auth
    case object Mcp     extends Capability  // `JsRuntimeMcp` — MCP server / client
    case object Dataset  extends Capability  // `JsRuntimeDataset` — Dataset[T] lazy pipeline
    case object Payment  extends Capability  // `JsRuntimePayment` — Payment Request API

    val all: Set[Capability] = Set(Core, Async, Effects, Mcp, Dataset, Payment)

    /** Encode a capability as a stable, persistence-safe string.
     *  These strings appear in `.scjs-runtime` envelopes — do not rename. */
    def encode(c: Capability): String = c match
      case Core    => "core"
      case Async   => "async"
      case Effects => "effects"
      case Mcp     => "mcp"
      case Dataset  => "dataset"
      case Payment  => "payment"

    def decode(s: String): Option[Capability] = s match
      case "core"    => Some(Core)
      case "async"   => Some(Async)
      case "effects" => Some(Effects)
      case "mcp"     => Some(Mcp)
      case "dataset" => Some(Dataset)
      case "payment" => Some(Payment)
      case _         => None

  /** Inspect `module` and return the capability set its emitted JS would
   *  depend on.  `Core` is always included.  Other capabilities are
   *  toggled by scanning the parsed scalascript blocks for references
   *  to the corresponding effect / DSL names (`Async.*`, `Actor.*`,
   *  `Logger.*` / `Random.*` / `Clock.*` / `Env.*`, `mcpServer` /
   *  `serveMcp`, `Dataset.*`). */
  def detectCapabilities(
      module:     Module,
      baseDir:    Option[os.Path] = None,
      intrinsics: Map[scalascript.ir.QualifiedName, scalascript.backend.spi.IntrinsicImpl] = Map.empty,
      lockPath:   Option[os.Path] = None
  ): Set[Capability] =
    new JsGen(baseDir, intrinsics, lockPath).detectCapabilities(module)

  /** Emit the runtime preamble for the given capability set.  No user code
   *  is included.  Capability `Core` is always added regardless of input —
   *  the per-module emit relies on `_show` / `_println` / HTML DSL helpers
   *  that live in the core block.
   *
   *  v2.0 Phase 2 — split-runtime emit. */
  def generateRuntime(capabilities: Set[Capability]): String =
    val caps = capabilities + Capability.Core
    val sb   = new StringBuilder
    sb.append("// ── scalascript JS runtime ──────────────────────────────────────────\n")
    // Core is always first — every other block uses `_show`, `_perform`,
    // `_handle`, etc., from the core preamble.
    sb.append(JsRuntime)
    if !JsRuntime.endsWith("\n") then sb.append('\n')
    if caps.contains(Capability.Async) then
      sb.append(JsRuntimeAsync)
      if !JsRuntimeAsync.endsWith("\n") then sb.append('\n')
    if caps.contains(Capability.Effects) then
      sb.append(JsRuntimeV14Effects)
      if !JsRuntimeV14Effects.endsWith("\n") then sb.append('\n')
    if caps.contains(Capability.Mcp) then
      sb.append(JsRuntimeMcp)
      if !JsRuntimeMcp.endsWith("\n") then sb.append('\n')
    if caps.contains(Capability.Dataset) then
      sb.append(JsRuntimeDataset)
      if !JsRuntimeDataset.endsWith("\n") then sb.append('\n')
    if caps.contains(Capability.Payment) then
      sb.append(JsRuntimePayment)
      if !JsRuntimePayment.endsWith("\n") then sb.append('\n')
    sb.toString

  /** Emit user code only — no runtime preamble.  In JS this is a synonym
   *  for [[generate]] today (`genModule` never prepended the preamble; the
   *  preamble was concatenated by the CLI's `buildScjsSource`).  The alias
   *  exists so call sites read symmetrically with the JVM split-runtime
   *  emit (`JvmGen.generateUserOnly`).
   *
   *  v2.0 Phase 2 — split-runtime emit. */
  def generateUserOnly(
      module:     Module,
      baseDir:    Option[os.Path] = None,
      intrinsics: Map[scalascript.ir.QualifiedName, scalascript.backend.spi.IntrinsicImpl] = Map.empty,
      lockPath:   Option[os.Path] = None
  ): String =
    generate(module, baseDir, intrinsics, lockPath)

/** JS runtime preamble embedded in every generated page.  Split into
 *  two triple-quoted halves because the combined source exceeds the
 *  JVM's 64 KB string-literal limit; the `val JsRuntime` concatenation
 *  is declared after both halves so source-order init sees them. */
private val JsRuntimePart1a: String = """
let _output = [];
function _println(...args) { _output.push(args.map(_show).join(' ')); }
function _print(...args) { const s = args.map(_show).join(''); _output.push(s); }
// Stage 5+/B.3 — Console companion so user code can call Console.println
// directly (or after Normalize's bare-println → Console.println rewrite).
const Console = { println: _println, print: _print };

// `_call(fn, ...args)` — wrapper for arbitrary callables in user code.
// Functions are invoked directly; Arrays and Maps route through `_dispatch`
// so `xs(i)` / `m(k)` behave like List / Map indexing.
function _call(fn, ...args) {
  if (typeof fn === 'function') return fn(...args);
  if (Array.isArray(fn) || fn instanceof Map) return _dispatch(fn, 'apply', args);
  // v1.5 Tier 5 #22 — `JsonValue` is a plain object with an `apply`
  // method, so `v("key")` and `v(0)` reach into the wrapper.
  if (fn && fn._type === 'Signal' && typeof fn.apply === 'function') return fn.apply(...args);
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

// User-defined string interpolators: `_sc(parts)` builds the StringContext
// object passed as the first argument to the extension method.
function _sc(parts) { return { _type: 'StringContext', parts }; }

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

// i18n runtime — t(key) / setLocale(code) / wc(tag, component, ...args)
let _i18nLocale = 'en';
let _i18nTable  = {};
function setLocale(code) { _i18nLocale = code; }
function t(key) {
  const tbl = _i18nTable[_i18nLocale];
  return (tbl && tbl[key] !== undefined) ? tbl[key] : key;
}
function wc(tag, component, ...args) {
  const css  = (typeof component.css === 'string') ? component.css : '';
  const html = (typeof component.render === 'function') ? component.render(...args) : '';
  return _Raw('<' + tag + '-component><template shadowrootmode="open"><style>' + css + '</style>' + _show(html) + '</template></' + tag + '-component>');
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

// ── Given / typeclass registry ─────────────────────────────────────────
// _ssc_givens["TC_Arg"] = instance — populated by every `given` declaration.
var _ssc_givens = {};
// Returns the ScalaScript type name for a runtime value (elem type for arrays).
function _ssc_typeOf(v) {
  if (Array.isArray(v)) return v.length > 0 ? _ssc_typeOf(v[0]) : 'Any';
  if (typeof v === 'number') return (v|0) === v ? 'Int' : 'Double';
  if (typeof v === 'string') return 'String';
  if (typeof v === 'boolean') return 'Boolean';
  if (v && v._type) return v._type;
  return 'Any';
}
function _resolveGiven(key) {
  var v = _ssc_givens[key];
  if (v !== undefined) return v;
  throw new Error('No given instance for ' + JSON.stringify(key));
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

function _httpSyncFetch(method, url, body, headers) {
  const effective = (_httpBaseUrl && !url.startsWith('http')) ? _httpBaseUrl + url : url;
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
    workerData: { sab, port: port2, url: effective, method, headers: headers || {}, body: body || null, timeoutMs },
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
  const maxTries = _httpMaxRetries + 1;
  let last;
  for (let attempt = 0; attempt < maxTries; attempt++) {
    last = _httpSyncFetch(method, url, body, headers);
    if (last.status !== 0 && last.status < 500) break;
    if (attempt < maxTries - 1) {
      const sab2 = new SharedArrayBuffer(4); const flag2 = new Int32Array(sab2);
      Atomics.wait(flag2, 0, 0, _httpRetryDelay);
    }
  }
  return last;
}

function httpGet(url, headers) {
  const h = headers instanceof Map ? Object.fromEntries(headers.entries()) : (headers || {});
  return _httpSyncFetchWithRetry('GET', url, null, h);
}

function httpPost(url, body, headers) {
  const h = headers instanceof Map ? Object.fromEntries(headers.entries()) : (headers || {});
  return _httpSyncFetchWithRetry('POST', url, body, h);
}

function httpPut(url, body, headers) {
  const h = headers instanceof Map ? Object.fromEntries(headers.entries()) : (headers || {});
  return _httpSyncFetchWithRetry('PUT', url, body, h);
}

function httpPatch(url, body, headers) {
  const h = headers instanceof Map ? Object.fromEntries(headers.entries()) : (headers || {});
  return _httpSyncFetchWithRetry('PATCH', url, body, h);
}

function httpDelete(url, headers) {
  const h = headers instanceof Map ? Object.fromEntries(headers.entries()) : (headers || {});
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
  const effective  = (_httpBaseUrl && !url.startsWith('http')) ? _httpBaseUrl + url : url;
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
    '    const text = await r.text();',
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
    workerData: { sab, port: port2, url: effective, method, headers, body: body || null, timeoutMs },
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
  const h = (headers instanceof Map) ? Object.fromEntries(headers.entries())
            : (headers && typeof headers === 'object') ? headers : {};
  return function(handler) { return _httpStreamFetch('GET', url, null, h, handler); };
}

function httpPostStream(url, body, headers) {
  const h = (headers instanceof Map) ? Object.fromEntries(headers.entries())
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
              const _sh = result.headers instanceof Map ? Object.fromEntries(result.headers.entries()) : {};
              if (!_sh['Content-Type']) _sh['Content-Type'] = 'text/plain; charset=utf-8';
              _applyCors(req.headers, _sh);
              res.writeHead(result.status || 200, _sh);
              result.block(chunk => res.write(chunk));
              res.end();
              return;
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
          } finally {
            if (request._spooled && request._spooled.length > 0) {
              const _fs = require('fs');
              request._spooled.forEach(f => { try { _fs.unlinkSync(f); } catch (_e) {} });
            }
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
  server.on('upgrade', (req, socket, _head) => _wsHandleUpgrade(req, socket));
  server.listen(port, () => console.log(`Listening on ${_useTls ? 'https' : 'http'}://localhost:${port}/  (backend=node${_ssc_frontend_name ? ', frontend=' + _ssc_frontend_name : ''})`));
  _activeServer = server;
}

let _activeServer = null;
let _ssc_frontend_name = '';

// Non-blocking variant of `serve` — mirrors the interpreter's
// `serveAsync` semantics.  In Node `serve` is already non-blocking
// (the event loop keeps the process alive while the server has open
// listeners), so `serveAsync` just delegates to `serve`.  The caller's
// script continues immediately; the server runs in the Node event
// loop in the background.  Required for Tier 4 codegen multi-backend
// cluster tests where a single Node process must both bind its WS
// server AND run an actor scheduler (see docs/cluster-codegen-gap.md).
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
    const hdrs = (headersOrBlock instanceof Map) ? headersOrBlock : new Map();
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
  // Caught exception support — `case e: RuntimeException => e.getMessage`
  // works on raw JS Errors (since Term.Throw lowers to `throw new Error(msg)`)
  // and on ScalaScript Instance-style throwables that carry a `message` field.
  if ((obj instanceof Error) || (obj && typeof obj === 'object' && 'message' in obj)) {
    if (method === 'getMessage' || method === 'message') return obj.message;
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

// Sequence an array of (Free | value); returns either the plain array
// (when none are Free) or a Free that yields it.
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

// ── std.fs — synchronous file primitives (Node only) ───────────────────
// Defined under user-facing names so nested calls like
// `_println(readFile(p))` resolve directly.
const _fsMod = (typeof require === 'function') ? require('fs') : null;
function writeFile(path, contents) {
  if (!_fsMod) throw new Error('writeFile: fs not available in this environment');
  _fsMod.writeFileSync(path, contents);
}
function readFile(path) {
  if (!_fsMod) throw new Error('readFile: fs not available in this environment');
  return _fsMod.readFileSync(path, 'utf-8');
}
function deleteFile(path) {
  if (!_fsMod) throw new Error('deleteFile: fs not available in this environment');
  try { _fsMod.unlinkSync(path); } catch (e) { if (e && e.code !== 'ENOENT') throw e; }
}
function exists(path) {
  if (!_fsMod) return false;
  return _fsMod.existsSync(path);
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

// ── Node.js stubs for std/ui/primitives.ssc extern defs ───────────────────
// Provide real implementations for run-js mode so extern def symbols
// are non-undefined when extracted from std.ui.primitives namespace.
function _ssc_ui_signal(name, initial) { return Signal(initial); }
function _ssc_ui_element(tag, attrs, events, children) {
  return { _type: '_Element', tag,
    attrs: (attrs instanceof Map) ? Object.fromEntries(attrs) : (attrs || {}),
    events: (events instanceof Map) ? Object.fromEntries(events) : (events || {}),
    children: children || [] };
}
function _ssc_ui_textNode(s) { return { _type: '_TextNode', s }; }
function _ssc_ui_signalText(sig) { return { _type: '_SignalText', sig }; }
function _ssc_ui_showSignal(cond, whenTrue, whenFalse) { return { _type: '_ShowSignal', cond, whenTrue, whenFalse }; }
function _ssc_ui_fragment(children) { return { _type: '_Fragment', children: children || [] }; }
function _ssc_ui_setSignal(s, v) { return { _type: '_SetSignal', s, v }; }
function _ssc_ui_inputChange(s) { return { _type: '_InputChange', s }; }
function _ssc_ui_toggleSignal(s) { return { _type: '_ToggleSignal', s }; }
function _ssc_ui_eqSignal(s, value) { return computed(() => (s && s.get) ? s.get() === value : false); }
function _ssc_ui_hashSignal() { return Signal(''); }
function _ssc_ui_emit(tree, outDir) {}

// Walk the View IR tree and produce a static HTML string + a Map of signal
// ids to their current values.  Split from _ssc_ui_renderPage so that the
// BrowserPatch's serve can call _ssc_ui_mount(sigs) directly — avoiding
// eval() or DOM <script> injection, both blocked by script-src 'self' CSP.
function _ssc_ui_renderBody(view) {
  const _esc  = s => String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
  const _escT = s => String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
  const sigs  = new Map(); // Signal id -> initial value (collected during walk)
  const voids = new Set(['br','hr','img','input','link','meta','area','base','col','embed','param','source','track','wbr']);

  function collectSig(s) {
    if (s && s._type === 'Signal' && !sigs.has(s.id))
      sigs.set(s.id, s.get());
  }

  function walk(v) {
    if (v === null || v === undefined) return '';
    if (typeof v === 'string') return _escT(v);
    if (typeof v !== 'object') return String(v);
    switch (v._type) {
      case '_Element': {
        const tag    = v.tag || 'div';
        const attrs  = v.attrs  || {};
        const events = v.events || {};
        let   aStr   = '';
        for (const [k, val] of Object.entries(attrs)) {
          const r = (val && typeof val === 'object' && typeof val.get === 'function') ? (collectSig(val), val.get()) : val;
          if (r !== undefined && r !== null && r !== false)
            aStr += ` ${k}="${_esc(r)}"`;
        }
        for (const [, h] of Object.entries(events)) {
          if (!h || typeof h !== 'object') continue;
          if (h._type === '_ToggleSignal' && h.s) { collectSig(h.s); aStr += ` data-ssc-toggle="${h.s.id}"`; }
          else if (h._type === '_SetSignal'  && h.s) { collectSig(h.s); aStr += ` data-ssc-set="${h.s.id}" data-ssc-set-val="${_esc(JSON.stringify(h.v))}"`; }
          else if (h._type === '_InputChange' && h.s) { collectSig(h.s); aStr += ` data-ssc-change="${h.s.id}"`; }
          else if ((h._type === '_FetchAction' || h._type === '_FetchActionClear') && h.url) {
            if (h.body) collectSig(h.body); if (h.tick) collectSig(h.tick);
            const bId = (h.body && h.body.id) ? h.body.id : '';
            const tId = (h.tick && h.tick.id) ? h.tick.id : '';
            aStr += ` data-ssc-fetch-method="${_esc(h.method||'POST')}" data-ssc-fetch-url="${_esc(h.url)}" data-ssc-fetch-body="${_esc(bId)}" data-ssc-fetch-tick="${_esc(tId)}"`;
            if (h._type === '_FetchActionClear') aStr += ` data-ssc-fetch-clear="1"`;
          }
        }
        const kids = (v.children || []).map(walk).join('');
        return voids.has(tag) ? `<${tag}${aStr}>` : `<${tag}${aStr}>${kids}</${tag}>`;
      }
      case '_TextNode':   return walk(v.s);
      case '_SignalText': {
        collectSig(v.sig);
        const id  = v.sig && v.sig.id;
        const txt = _escT(v.sig && v.sig.get ? String(v.sig.get()) : '');
        return id ? `<span data-ssc-text="${id}">${txt}</span>` : txt;
      }
      case '_ShowSignal': {
        collectSig(v.cond);
        const id   = v.cond && v.cond.id;
        const show = v.cond && v.cond.get ? v.cond.get() : false;
        const tHtml = walk(v.whenTrue);
        const fHtml = walk(v.whenFalse);
        if (!id) return show ? tHtml : fHtml;
        const tStyle = show ? ' style="display:contents"' : ' style="display:none"';
        const fStyle = show ? ' style="display:none"' : ' style="display:contents"';
        return `<span data-ssc-cond="${id}" style="display:contents"><span data-ssc-branch="true"${tStyle}>${tHtml}</span><span data-ssc-branch="false"${fStyle}>${fHtml}</span></span>`;
      }
      case '_Fragment':      return (v.children || []).map(walk).join('');
      case '_FetchTableView': {
        if (v.tick) collectSig(v.tick);
        const ftTick = (v.tick && v.tick.id) ? v.tick.id : '';
        return `<div data-ssc-fetch-table="${_esc(v.fetchUrl)}" data-ssc-fetch-delete="${_esc(v.deleteUrl||'')}" data-ssc-fetch-tick="${_esc(ftTick)}" style="overflow-x:auto"></div>`;
      }
      default: return '';
    }
  }

  const body = walk(view);
  return { body, sigs };
}

// Set up DOM reactivity after _ssc_ui_renderBody has been injected into the
// page.  Called directly from the BrowserPatch serve (no eval / no DOM script
// injection) with the sigs Map returned by _ssc_ui_renderBody.
function _ssc_ui_mount(sigs) {
  var _sv = {};
  sigs.forEach(function(v, id) { _sv[String(id)] = v; });
  var _sb = {};
  function _sub(id, fn) { (_sb[id] = _sb[id] || []).push(fn); fn(_sv[id]); }
  function _set(id, v) { _sv[id] = v; (_sb[id] || []).forEach(function(fn){ fn(v); }); }
  // show/hide branches
  document.querySelectorAll('[data-ssc-cond]').forEach(function(el) {
    var id = el.getAttribute('data-ssc-cond');
    var tBranch = el.querySelector('[data-ssc-branch="true"]');
    var fBranch = el.querySelector('[data-ssc-branch="false"]');
    _sub(id, function(v) {
      if (tBranch) tBranch.style.display = v ? 'contents' : 'none';
      if (fBranch) fBranch.style.display = v ? 'none' : 'contents';
    });
  });
  // signal text spans
  document.querySelectorAll('[data-ssc-text]').forEach(function(el) {
    var id = el.getAttribute('data-ssc-text');
    _sub(id, function(v) { el.textContent = v == null ? '' : String(v); });
  });
  // checkbox toggle
  document.querySelectorAll('[data-ssc-toggle]').forEach(function(el) {
    var id = el.getAttribute('data-ssc-toggle');
    el.addEventListener('change', function() { _set(id, el.checked); });
    _sub(id, function(v) { el.checked = !!v; });
  });
  // button setSignal
  document.querySelectorAll('[data-ssc-set]').forEach(function(el) {
    var id  = el.getAttribute('data-ssc-set');
    var val = JSON.parse(el.getAttribute('data-ssc-set-val'));
    el.addEventListener('click', function() { _set(id, val); });
  });
  // text input change
  document.querySelectorAll('[data-ssc-change]').forEach(function(el) {
    var id = el.getAttribute('data-ssc-change');
    el.addEventListener('input', function() { _set(id, el.value); });
    _sub(id, function(v) { el.value = v == null ? '' : String(v); });
  });
  // fetch action buttons (fetchAction / fetchActionClear)
  document.querySelectorAll('[data-ssc-fetch-url]').forEach(function(el) {
    var method = el.getAttribute('data-ssc-fetch-method') || 'POST';
    var url    = el.getAttribute('data-ssc-fetch-url');
    var bodyId = el.getAttribute('data-ssc-fetch-body');
    var tickId = el.getAttribute('data-ssc-fetch-tick');
    var clear  = el.getAttribute('data-ssc-fetch-clear');
    el.addEventListener('click', function() {
      var body = bodyId ? String(_sv[bodyId] == null ? '' : _sv[bodyId]) : '';
      fetch(url, {method: method, body: body})
        .then(function(r) { return r.text(); })
        .then(function() {
          if (tickId) _set(tickId, ((_sv[tickId] || 0) | 0) + 1);
          if (clear && bodyId) _set(bodyId, '');
        });
    });
  });
  // fetch tables (fetchTableView)
  document.querySelectorAll('[data-ssc-fetch-table]').forEach(function(container) {
    var fetchUrl  = container.getAttribute('data-ssc-fetch-table');
    var deleteUrl = container.getAttribute('data-ssc-fetch-delete');
    var tickId    = container.getAttribute('data-ssc-fetch-tick');
    var cs = window.getComputedStyle(container);
    var fs = cs.fontSize; var ff = cs.fontFamily;
    var thStyle  = 'text-align:left;padding:6px 12px;border-bottom:2px solid #e5e7eb;font-weight:600;color:#111827;font-size:'+fs+';font-family:'+ff;
    var tdStyle  = 'padding:6px 12px;border-bottom:1px solid #e5e7eb;color:#374151;vertical-align:middle;font-size:'+fs+';font-family:'+ff;
    var btnStyle = 'background:#ef4444;color:#fff;border:none;padding:6px 16px;border-radius:4px;cursor:pointer;font-size:'+fs+';font-family:'+ff;
    function renderTable(rows) {
      container.innerHTML = '';
      var tbl = document.createElement('table');
      tbl.setAttribute('style', 'border-collapse:collapse;width:100%;font-family:'+ff+';font-size:'+fs);
      var thead = document.createElement('thead'); thead.setAttribute('style', 'background:#f9fafb');
      var trH = document.createElement('tr');
      var th1 = document.createElement('th'); th1.setAttribute('style', thStyle); th1.textContent = 'Task'; trH.appendChild(th1);
      var th2 = document.createElement('th'); th2.setAttribute('style', thStyle); th2.textContent = ''; trH.appendChild(th2);
      thead.appendChild(trH); tbl.appendChild(thead);
      var tbody = document.createElement('tbody');
      (rows || []).forEach(function(row) {
        var tr = document.createElement('tr');
        var td1 = document.createElement('td'); td1.setAttribute('style', tdStyle); td1.textContent = String(row.text); tr.appendChild(td1);
        var td2 = document.createElement('td'); td2.setAttribute('style', tdStyle);
        var btn = document.createElement('button'); btn.setAttribute('style', btnStyle); btn.textContent = 'Delete';
        btn.addEventListener('click', function() {
          fetch(deleteUrl, {method: 'POST', body: String(row.id)}).then(function(r) { return r.text(); })
            .then(function() { if (tickId) _set(tickId, ((_sv[tickId] || 0) | 0) + 1); });
        });
        td2.appendChild(btn); tr.appendChild(td2); tbody.appendChild(tr);
      });
      tbl.appendChild(tbody); container.appendChild(tbl);
    }
    function doFetch() { fetch(fetchUrl).then(function(r) { return r.json(); }).then(renderTable); }
    doFetch();
    if (tickId) _sub(tickId, function(t) { if ((t | 0) > 0) doFetch(); });
  });
}

// Backward-compat wrapper: walk the view and return { body, script } where
// script is an inline <script> IIFE — used by _ssc_ui_serve for server-rendered pages.
function _ssc_ui_renderPage(view) {
  const { body, sigs } = _ssc_ui_renderBody(view);
  const sigJson = JSON.stringify(Object.fromEntries(sigs));
  const script = `<script>_ssc_ui_mount(new Map(Object.entries(${sigJson})));</script>`;
  return { body, script };
}

// Handles both serve(port) [port-only] and serve(view, port[, extraCss]).
// std.ui.primitives.serve extern def routes here; no top-level 'serve'
// needed — the binding 'const serve = std.ui.primitives.serve' works.
function _ssc_ui_serve(treeOrPort, portOrUndef, extraCssOrUndef) {
  if (typeof treeOrPort === 'number') { _ssc_http_serve(treeOrPort); return; }
  const view     = treeOrPort;
  const port     = portOrUndef;
  const extraCss = extraCssOrUndef || '';
  route('GET', '/')((_req) => {
    const { body, sigs } = _ssc_ui_renderBody(view);
    const sigJson = JSON.stringify(Object.fromEntries(sigs));
    const script = `<script>_ssc_ui_mount(new Map(Object.entries(${sigJson})));</script>`;
    const html = `<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>ScalaScript App</title>
<style>*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{margin:0;padding:0;background:#fff;-webkit-text-size-adjust:100%}
.ssc-page{max-width:700px;margin:0 auto;padding:24px 20px;font-size:16px;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',system-ui,sans-serif}
input[type=checkbox]{width:22px;height:22px;accent-color:#2563eb;cursor:pointer;flex-shrink:0}
button{touch-action:manipulation;cursor:pointer}
button:disabled{opacity:.5;cursor:default}
[data-ssc-cond]{display:contents}
hr{border:none;border-top:1px solid #e5e7eb;margin:0}
[data-ssc-fetch-table] table,[data-ssc-fetch-table] th,[data-ssc-fetch-table] td,[data-ssc-fetch-table] button{font-size:inherit;font-family:inherit}
@keyframes spin{from{transform:rotate(0deg)}to{transform:rotate(360deg)}}
.ssc-spin{animation:spin 0.8s linear infinite}
${extraCss}</style></head><body><div class="ssc-page">${body}</div>${script}</body></html>`;
    return Response.html(html);
  });
  _ssc_http_serve(port);
}
function _ssc_ui_fetchUrlSignal(name, url, tick) { return Signal(''); }
function _ssc_ui_fetchAction(method, url, body, tick) { return { _type: '_FetchAction', method, url, body, tick }; }
function _ssc_ui_incSignal(s) { return { _type: '_IncSignal', s }; }
function _ssc_ui_fetchActionClear(method, url, body, tick) { return { _type: '_FetchActionClear', method, url, body, tick }; }
function _ssc_ui_fetchTableView(fetchUrl, deleteUrl, tick) { return { _type: '_FetchTableView', fetchUrl, deleteUrl, tick }; }
"""

private val JsRuntimeIndexedDb: String = """
// ── Client IndexedDB object store facade ───────────────────────────────
//
// `IndexedDb.store[Todo]("todos")` returns a Promise-based local object
// store. Browser/Electron clients use native IndexedDB. Node/tests fall back
// to an in-memory Map, persisted through localStorage when available.
const _ssc_indexeddb_memory = globalThis.__sscIndexedDbMemory || (globalThis.__sscIndexedDbMemory = new Map());

function _ssc_indexeddb_store_key(dbName, storeName) {
  return String(dbName || "ssc") + "::" + String(storeName || "default");
}

function _ssc_indexeddb_local_storage() {
  try {
    if (typeof window !== "undefined" && window.localStorage) return window.localStorage;
  } catch (_) {}
  return null;
}

function _ssc_indexeddb_memory_store(dbName, storeName) {
  const key = _ssc_indexeddb_store_key(dbName, storeName);
  if (!_ssc_indexeddb_memory.has(key)) {
    const map = new Map();
    const storage = _ssc_indexeddb_local_storage();
    if (storage) {
      try {
        const raw = storage.getItem("__ssc_indexeddb:" + key);
        if (raw) {
          const obj = JSON.parse(raw);
          for (const k of Object.keys(obj)) map.set(k, obj[k]);
        }
      } catch (_) {}
    }
    _ssc_indexeddb_memory.set(key, map);
  }
  return _ssc_indexeddb_memory.get(key);
}

function _ssc_indexeddb_memory_flush(dbName, storeName, map) {
  const storage = _ssc_indexeddb_local_storage();
  if (!storage) return;
  try {
    const obj = {};
    for (const [k, v] of map.entries()) obj[k] = v;
    storage.setItem("__ssc_indexeddb:" + _ssc_indexeddb_store_key(dbName, storeName), JSON.stringify(obj));
  } catch (_) {}
}

function _ssc_indexeddb_decode(value, typeName) {
  return _ssc_typed_json_decode_value(value, typeName || "");
}

function _ssc_indexeddb_encode(value, typeName) {
  return _ssc_typed_json_plain(value, typeName || "");
}

function _ssc_indexeddb_key(value, plain, keyField) {
  if (value != null && typeof value === "object" && value[keyField] !== undefined && value[keyField] !== null) {
    return String(value[keyField]);
  }
  if (plain != null && typeof plain === "object" && plain[keyField] !== undefined && plain[keyField] !== null) {
    return String(plain[keyField]);
  }
  throw new Error("IndexedDb.put: missing key field '" + keyField + "'; pass an explicit key or choose another key field");
}

function _ssc_indexeddb_native_available() {
  return typeof indexedDB !== "undefined" && indexedDB && typeof indexedDB.open === "function";
}

function _ssc_indexeddb_open_native(dbName, storeName) {
  return new Promise((resolve, reject) => {
    const first = indexedDB.open(dbName);
    first.onerror = () => reject(first.error || new Error("IndexedDB open failed"));
    first.onupgradeneeded = () => {
      const db = first.result;
      if (!db.objectStoreNames.contains(storeName)) db.createObjectStore(storeName);
    };
    first.onsuccess = () => {
      const db = first.result;
      if (db.objectStoreNames.contains(storeName)) {
        resolve(db);
        return;
      }
      const nextVersion = db.version + 1;
      db.close();
      const upgrade = indexedDB.open(dbName, nextVersion);
      upgrade.onerror = () => reject(upgrade.error || new Error("IndexedDB upgrade failed"));
      upgrade.onupgradeneeded = () => {
        const upgraded = upgrade.result;
        if (!upgraded.objectStoreNames.contains(storeName)) upgraded.createObjectStore(storeName);
      };
      upgrade.onsuccess = () => resolve(upgrade.result);
    };
  });
}

function _ssc_indexeddb_request(req) {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error || new Error("IndexedDB request failed"));
  });
}

function _ssc_indexeddb_tx_done(tx) {
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error || new Error("IndexedDB transaction failed"));
    tx.onabort = () => reject(tx.error || new Error("IndexedDB transaction aborted"));
  });
}

function _ssc_indexeddb_make_store(storeName, typeName, dbName, keyField) {
  const store = String(storeName || "default");
  const tpe = String(typeName || "");
  const db = String(dbName || "ssc");
  const key = String(keyField || "id");

  async function withNative(mode, f) {
    const handle = await _ssc_indexeddb_open_native(db, store);
    try {
      const tx = handle.transaction(store, mode);
      const objectStore = tx.objectStore(store);
      const result = await f(objectStore);
      if (mode !== "readonly") await _ssc_indexeddb_tx_done(tx);
      return result;
    } finally {
      handle.close();
    }
  }

  function withMemory(f) {
    const map = _ssc_indexeddb_memory_store(db, store);
    const result = f(map);
    _ssc_indexeddb_memory_flush(db, store, map);
    return Promise.resolve(result);
  }

  return {
    put(value, explicitKey) {
      const plain = _ssc_indexeddb_encode(value, tpe);
      const objectKey = explicitKey === undefined || explicitKey === null ? _ssc_indexeddb_key(value, plain, key) : String(explicitKey);
      if (_ssc_indexeddb_native_available()) {
        return withNative("readwrite", os => _ssc_indexeddb_request(os.put(plain, objectKey))).then(() => value);
      }
      return withMemory(map => { map.set(objectKey, plain); return value; });
    },
    get(objectKey) {
      const k = String(objectKey);
      if (_ssc_indexeddb_native_available()) {
        return withNative("readonly", os => _ssc_indexeddb_request(os.get(k)))
          .then(value => value === undefined ? _None : _Some(_ssc_indexeddb_decode(value, tpe)));
      }
      return withMemory(map => map.has(k) ? _Some(_ssc_indexeddb_decode(map.get(k), tpe)) : _None);
    },
    remove(objectKey) {
      const k = String(objectKey);
      if (_ssc_indexeddb_native_available()) {
        return withNative("readwrite", os => _ssc_indexeddb_request(os.delete(k))).then(() => undefined);
      }
      return withMemory(map => { map.delete(k); return undefined; });
    },
    delete(objectKey) {
      return this.remove(objectKey);
    },
    keys() {
      if (_ssc_indexeddb_native_available()) {
        return withNative("readonly", os => {
          if (typeof os.getAllKeys === "function") return _ssc_indexeddb_request(os.getAllKeys()).then(keys => keys.map(String));
          return new Promise((resolve, reject) => {
            const keys = [];
            const req = os.openCursor();
            req.onerror = () => reject(req.error || new Error("IndexedDB cursor failed"));
            req.onsuccess = () => {
              const cursor = req.result;
              if (!cursor) resolve(keys);
              else { keys.push(String(cursor.key)); cursor.continue(); }
            };
          });
        });
      }
      return withMemory(map => Array.from(map.keys()));
    },
    all() {
      if (_ssc_indexeddb_native_available()) {
        return withNative("readonly", os => _ssc_indexeddb_request(os.getAll()))
          .then(values => values.map(value => _ssc_indexeddb_decode(value, tpe)));
      }
      return withMemory(map => Array.from(map.values()).map(value => _ssc_indexeddb_decode(value, tpe)));
    },
    entries() {
      if (_ssc_indexeddb_native_available()) {
        return withNative("readonly", os => {
          return new Promise((resolve, reject) => {
            const rows = [];
            const req = os.openCursor();
            req.onerror = () => reject(req.error || new Error("IndexedDB cursor failed"));
            req.onsuccess = () => {
              const cursor = req.result;
              if (!cursor) resolve(rows);
              else {
                rows.push({ key: String(cursor.key), value: _ssc_indexeddb_decode(cursor.value, tpe) });
                cursor.continue();
              }
            };
          });
        });
      }
      return withMemory(map => Array.from(map.entries()).map(([k, v]) => ({ key: String(k), value: _ssc_indexeddb_decode(v, tpe) })));
    },
    clear() {
      if (_ssc_indexeddb_native_available()) {
        return withNative("readwrite", os => _ssc_indexeddb_request(os.clear())).then(() => undefined);
      }
      return withMemory(map => { map.clear(); return undefined; });
    }
  };
}

const IndexedDb = {
  store(storeName, typeName = "", dbName = "ssc", keyField = "id") {
    return _ssc_indexeddb_make_store(storeName, typeName, dbName, keyField);
  }
};

function _ssc_sync_base_url(serverUrl) {
  const raw = serverUrl === undefined || serverUrl === null ? "" : String(serverUrl);
  return raw.endsWith("/") ? raw.slice(0, -1) : raw;
}

function _ssc_sync_cursor_key(dbName, storeName) {
  return "__ssc_sync_cursor:" + _ssc_indexeddb_store_key(dbName, storeName);
}

function _ssc_sync_queue_key(dbName, storeName) {
  return "__ssc_sync_queue:" + _ssc_indexeddb_store_key(dbName, storeName);
}

function _ssc_sync_versions_key(dbName, storeName) {
  return "__ssc_sync_versions:" + _ssc_indexeddb_store_key(dbName, storeName);
}

function _ssc_sync_json_get(key, fallback) {
  const storage = _ssc_indexeddb_local_storage();
  if (!storage) {
    const mem = globalThis.__sscSyncMemory || (globalThis.__sscSyncMemory = new Map());
    return mem.has(key) ? mem.get(key) : fallback;
  }
  try {
    const raw = storage.getItem(key);
    return raw == null ? fallback : JSON.parse(raw);
  } catch (_) {
    return fallback;
  }
}

function _ssc_sync_json_set(key, value) {
  const storage = _ssc_indexeddb_local_storage();
  if (!storage) {
    const mem = globalThis.__sscSyncMemory || (globalThis.__sscSyncMemory = new Map());
    mem.set(key, value);
    return;
  }
  storage.setItem(key, JSON.stringify(value));
}

function _ssc_sync_get_cursor(dbName, storeName) {
  const raw = _ssc_sync_json_get(_ssc_sync_cursor_key(dbName, storeName), 0);
  const parsed = raw == null ? 0 : Number(raw);
  return Number.isFinite(parsed) ? parsed : 0;
}

function _ssc_sync_set_cursor(dbName, storeName, cursor) {
  _ssc_sync_json_set(_ssc_sync_cursor_key(dbName, storeName), Number(cursor || 0));
}

function _ssc_sync_get_queue(dbName, storeName) {
  const raw = _ssc_sync_json_get(_ssc_sync_queue_key(dbName, storeName), []);
  return Array.isArray(raw) ? raw : [];
}

function _ssc_sync_set_queue(dbName, storeName, queue) {
  _ssc_sync_json_set(_ssc_sync_queue_key(dbName, storeName), Array.isArray(queue) ? queue : []);
}

function _ssc_sync_get_versions(dbName, storeName) {
  const raw = _ssc_sync_json_get(_ssc_sync_versions_key(dbName, storeName), {});
  return raw && typeof raw === "object" && !Array.isArray(raw) ? raw : {};
}

function _ssc_sync_set_versions(dbName, storeName, versions) {
  _ssc_sync_json_set(_ssc_sync_versions_key(dbName, storeName), versions || {});
}

function _ssc_sync_expected_version(dbName, storeName, key) {
  const versions = _ssc_sync_get_versions(dbName, storeName);
  const value = versions[String(key)];
  return value === undefined || value === null ? undefined : Number(value);
}

function _ssc_sync_set_version(dbName, storeName, key, version) {
  const n = Number(version);
  if (!Number.isFinite(n)) return;
  const versions = _ssc_sync_get_versions(dbName, storeName);
  versions[String(key)] = n;
  _ssc_sync_set_versions(dbName, storeName, versions);
}

function _ssc_sync_forget_version(dbName, storeName, key) {
  const versions = _ssc_sync_get_versions(dbName, storeName);
  delete versions[String(key)];
  _ssc_sync_set_versions(dbName, storeName, versions);
}

function _ssc_sync_enqueue(dbName, storeName, mutation) {
  const key = String(mutation.key);
  const queue = _ssc_sync_get_queue(dbName, storeName).filter(item => String(item.key) !== key);
  queue.push({ ...mutation, key, queuedAt: new Date().toISOString() });
  _ssc_sync_set_queue(dbName, storeName, queue);
  return mutation;
}

function _ssc_sync_ack(dbName, storeName, results, conflicts) {
  if (Array.isArray(results)) {
    for (const row of results) {
      if (row && row.key !== undefined && row.version !== undefined) _ssc_sync_set_version(dbName, storeName, row.key, row.version);
    }
  }
  const conflictKeys = new Set(Array.isArray(conflicts) ? conflicts.map(row => String(row.key)) : []);
  const resultKeys = new Set(Array.isArray(results) ? results.map(row => String(row.key)) : []);
  const queue = _ssc_sync_get_queue(dbName, storeName).filter(item => !resultKeys.has(String(item.key)) || conflictKeys.has(String(item.key)));
  _ssc_sync_set_queue(dbName, storeName, queue);
}

async function _ssc_sync_json(method, url, body) {
  if (typeof fetch !== "function") throw new Error("Sync requires fetch in this JS runtime");
  const init = { method, headers: { "Content-Type": "application/json" } };
  if (body !== undefined) init.body = JSON.stringify(body);
  const res = await fetch(url, init);
  if (!res || res.ok === false) {
    const status = res && res.status !== undefined ? res.status : "unknown";
    throw new Error("Sync request failed: " + status + " " + url);
  }
  if (typeof res.json === "function") return await res.json();
  if (typeof res.text === "function") {
    const text = await res.text();
    return text ? JSON.parse(text) : {};
  }
  return {};
}

const Sync = {
  async put(storeName, typeName = "", value, dbName = "ssc", keyField = "id") {
    const store = IndexedDb.store(storeName, typeName, dbName, keyField);
    const plain = _ssc_indexeddb_encode(value, typeName || "");
    const key = _ssc_indexeddb_key(value, plain, keyField || "id");
    await store.put(value, key);
    const expected = _ssc_sync_expected_version(dbName, storeName, key);
    const mutation = { key, deleted: false, value: plain };
    if (expected !== undefined) mutation.expectedVersion = expected;
    _ssc_sync_enqueue(dbName, storeName, mutation);
    return value;
  },
  async remove(storeName, typeName = "", objectKey, dbName = "ssc", keyField = "id") {
    const key = String(objectKey);
    const store = IndexedDb.store(storeName, typeName, dbName, keyField);
    await store.remove(key);
    const expected = _ssc_sync_expected_version(dbName, storeName, key);
    const mutation = { key, deleted: true };
    if (expected !== undefined) mutation.expectedVersion = expected;
    _ssc_sync_enqueue(dbName, storeName, mutation);
    return undefined;
  },
  pending(storeName, dbName = "ssc") {
    return _ssc_sync_get_queue(dbName, storeName).slice();
  },
  async pull(storeName, typeName = "", dbName = "ssc", keyField = "id", serverUrl = "", limit = 100) {
    const store = IndexedDb.store(storeName, typeName, dbName, keyField);
    const since = _ssc_sync_get_cursor(dbName, storeName);
    const url = _ssc_sync_base_url(serverUrl) + "/__ssc/sync/" + encodeURIComponent(String(storeName)) +
      "/changes?since=" + encodeURIComponent(String(since)) + "&limit=" + encodeURIComponent(String(limit));
    const payload = await _ssc_sync_json("GET", url);
    const changes = Array.isArray(payload.changes) ? payload.changes : [];
    for (const change of changes) {
      if (!change || change.key === undefined) continue;
      if (change.deleted) {
        await store.remove(change.key);
        _ssc_sync_forget_version(dbName, storeName, change.key);
      } else if (Object.prototype.hasOwnProperty.call(change, "value")) {
        await store.put(change.value, change.key);
        _ssc_sync_set_version(dbName, storeName, change.key, change.version);
      }
    }
    const nextCursor = payload.nextCursor === undefined ? changes.reduce((acc, change) => Math.max(acc, Number(change.version || 0)), since) : Number(payload.nextCursor);
    if (Number.isFinite(nextCursor)) _ssc_sync_set_cursor(dbName, storeName, nextCursor);
    return payload;
  },
  async push(storeName, typeName = "", dbName = "ssc", keyField = "id", serverUrl = "") {
    const store = IndexedDb.store(storeName, typeName, dbName, keyField);
    let mutations = _ssc_sync_get_queue(dbName, storeName);
    if (mutations.length === 0) {
      const entries = await store.entries();
      mutations = entries.map(entry => {
        const mutation = {
          key: String(entry.key),
          deleted: false,
          value: _ssc_indexeddb_encode(entry.value, typeName || "")
        };
        const expected = _ssc_sync_expected_version(dbName, storeName, mutation.key);
        if (expected !== undefined) mutation.expectedVersion = expected;
        return mutation;
      });
    }
    const url = _ssc_sync_base_url(serverUrl) + "/__ssc/sync/" + encodeURIComponent(String(storeName)) + "/push";
    const payload = await _ssc_sync_json("POST", url, { mutations });
    const versions = [];
    if (Array.isArray(payload.results)) for (const row of payload.results) versions.push(Number(row.version || 0));
    if (versions.length > 0) _ssc_sync_set_cursor(dbName, storeName, Math.max(_ssc_sync_get_cursor(dbName, storeName), ...versions));
    _ssc_sync_ack(dbName, storeName, payload.results, payload.conflicts);
    return payload;
  }
};
"""

val JsRuntime: String =
  JsRuntimePart1a + JsRuntimePart1b + JsRuntimePart1c + JsRuntimePart1d + JsRuntimePart2 +
    TypedJsonCodecRuntime.jsFacade + JsRuntimeIndexedDb

/** Built-in `Async` effect runtime — concatenated onto `JsRuntime`.
 *  Lives in its own val because together with the rest of the runtime
 *  it overflows the JVM's 65 535-byte string-literal limit.  Same
 *  semantics as the interpreter and JvmGen: `delay` blocks via
 *  Atomics on Node, thunks passed to `async` / `parallel` run
 *  synchronously, results come back in declared order.  Itself split
 *  into two halves to stay under that 65 KiB string-literal cap. */
lazy val JsRuntimeAsync: String = JsRuntimeAsyncA + JsRuntimeAsyncB

private val JsRuntimeAsyncA: String = """

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
  recvFrom: (ws)     => _perform('Async', 'recvFrom', [ws]),
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

// ── runAsyncParallel: true I/O concurrency on Node via Promises ────────────
//
// Each `Async.async(thunk)` launches an independent Promise-driven run of
// `thunk`, returning a handle that `Async.await` can resolve.  `parallel`
// fans out all thunks concurrently with `Promise.all`.  `recvFrom(ws)`
// delegates to `ws._nextMessage()` — a Promise that resolves on the next
// incoming WebSocket frame, or `None` on close.  The outer `async function`
// keeps the Node.js event loop live while awaiting I/O.
async function _runAsyncParallelInner(node) {
  while (true) {
    // Right-associate nested _FlatMap nodes to avoid stack growth.
    while (node instanceof _FlatMap && node.sub instanceof _FlatMap) {
      const sub2 = node.sub.sub, g = node.sub.k, f = node.k;
      node = new _FlatMap(sub2, (x) => new _FlatMap(g(x), f));
    }
    if (node instanceof _FlatMap) {
      const result = await _runAsyncParallelInner(node.sub);
      node = node.k(result);
    } else if (node instanceof _Perform && node.eff === 'Async') {
      switch (node.op) {
        case 'delay': {
          const ms = node.args[0];
          if (ms > 0) await new Promise(r => setTimeout(r, ms));
          node = undefined;
          break;
        }
        case 'async': {
          const thunk = node.args[0];
          const p = _runAsyncParallelInner(thunk());
          return { _type: 'Future', _isParFut: true, _promise: p };
        }
        case 'await': {
          const fut = node.args[0];
          if (!fut || !fut._isParFut) throw new Error('Async.await: expected parallel Future');
          return await fut._promise;
        }
        case 'parallel': {
          const thunks = node.args[0];
          return await Promise.all(thunks.map(t => _runAsyncParallelInner(t())));
        }
        case 'recvFrom': {
          const ws = node.args[0];
          const nextMsg = typeof ws._nextMessage === 'function' ? ws._nextMessage
                        : (ws instanceof Map ? ws.get('_nextMessage') : null);
          if (typeof nextMsg !== 'function') throw new Error('Async.recvFrom: ws has no _nextMessage');
          node = await nextMsg();
          break;
        }
        default:
          throw new Error('Unknown Async operation in runAsyncParallel: ' + node.op);
      }
    } else {
      return node;  // plain value
    }
  }
}
async function _runAsyncParallel(bodyFn) {
  try { return await _runAsyncParallelInner(bodyFn()); }
  catch (e) {
    if (typeof process !== 'undefined') process.stderr.write('runAsyncParallel error: ' + (e && e.message || String(e)) + '\n');
    throw e;
  }
}

// ── v1.6 Actors — Phase 1 cooperative scheduler ────────────────────────
//
// Mirrors the interpreter's `actorInterp` / `handleActorOp`.  Each
// `_runActors(bodyFn)` invocation creates a fresh actor registry,
// spawns `bodyFn` as the root actor, and drives all spawned actors
// cooperatively until quiescence.  Mailboxes are plain JS arrays
// (v1.9.x: JS is single-threaded so LinkedBlockingQueue N/A; array
// IS the cooperative-mailbox equivalent for this backend); the
// scheduler is a simple round-robin ready queue.  `receive` with a
// non-empty matching head returns the case body's value; with an
// empty mailbox it suspends.  `receive(timeout = N)` arms a deadline;
// when the ready queue empties and no other progress is possible the
// scheduler sleeps until the earliest deadline, then resumes that
// actor with None.

function Pid(nodeId, localId) { return { _type: 'Pid', nodeId, localId }; }

const Actor = {
  spawn:      (thunk)      => _perform('Actor', 'spawn',      [thunk]),
  spawn_link: (thunk)      => _perform('Actor', 'spawnLink',  [thunk]),
  self:       ()           => _perform('Actor', 'self',       []),
  exit:      (pid, reason) => _perform('Actor', 'exit',      [pid, reason]),
  send:      (pid, msg)    => _perform('Actor', 'send',      [pid, msg]),
  receive_:  (specId)              => _perform('Actor', 'receive',   [specId]),
  receive_t: (specId, timeoutMs)   => _perform('Actor', 'receive_t', [specId, timeoutMs]),
  // v1.6 Phase 2 — supervision
  link:      (pid)         => _perform('Actor', 'link',      [pid]),
  monitor:   (pid)         => _perform('Actor', 'monitor',   [pid]),
  demonitor: (ref)         => _perform('Actor', 'demonitor', [ref]),
  trapExit:  (b)           => _perform('Actor', 'trapExit',  [b]),
  // v1.6 Phase 3 — distributed nodes
  startNode:   (nodeId, url)  => _perform('Actor', 'startNode',   [nodeId, url || '']),
  connectNode: (url, token)   => _perform('Actor', 'connectNode', [url, token]),
  register:    (name, pid)    => _perform('Actor', 'register',    [name, pid]),
  whereis:     (name)         => _perform('Actor', 'whereis',     [name]),
  // v1.6.x — cluster discovery
  joinCluster:     (seeds, token) => _perform('Actor', 'joinCluster',     [seeds, token || '']),
  // v1.6.x — cluster-wide registry
  globalRegister:  (name, pid)   => _perform('Actor', 'globalRegister',  [name, pid]),
  globalWhereis:   (name)        => _perform('Actor', 'globalWhereis',   [name]),
  // v1.6.x — scheduled sends
  sendAfter:   (delayMs, pid, msg)  => _perform('Actor', 'sendAfter',   [delayMs, pid, msg]),
  sendInterval:(periodMs, pid, msg) => _perform('Actor', 'sendInterval',[periodMs, pid, msg]),
  cancelTimer: (ref)                => _perform('Actor', 'cancelTimer', [ref]),
  // v1.6.x — bounded mailbox spawn
  spawnBounded:(cap, overflow, thunk) => _perform('Actor', 'spawnBounded', [cap, overflow, thunk]),
  // v1.6.x — process introspection
  processInfo: (pid) => _perform('Actor', 'processInfo', [pid]),
  // v1.23 — cluster visibility
  clusterMembers:         ()  => _perform('Actor', 'clusterMembers',         []),
  subscribeClusterEvents: ()  => _perform('Actor', 'subscribeClusterEvents', []),
  // v1.23 — phi-accrual failure detector
  phiOf:     (nid)              => _perform('Actor', 'phiOf',     [nid]),
  isSuspect: (nid, thr)         => _perform('Actor', 'isSuspect', [nid, thr == null ? 8.0 : thr]),
  // v1.23 — local node identity + phi vector
  selfNode:      ()             => _perform('Actor', 'selfNode',      []),
  clusterHealth: ()             => _perform('Actor', 'clusterHealth', []),
  // v1.23 — cluster-wide failure detector
  broadcastHealth: ()           => _perform('Actor', 'broadcastHealth', []),
  clusterIsDown:   (nid, thr)   => _perform('Actor', 'clusterIsDown',
                                            [nid, thr == null ? 8.0 : thr]),
  // v1.23 — leader election (Bully)
  electLeader:           ()  => _perform('Actor', 'electLeader',           []),
  currentLeader:         ()  => _perform('Actor', 'currentLeader',         []),
  subscribeLeaderEvents: ()  => _perform('Actor', 'subscribeLeaderEvents', []),
  setAutoReelect:        (b) => _perform('Actor', 'setAutoReelect', [b]),
  // v1.23 — protocol switch + history (cluster-raft.md §6)
  useRaftLeaderElection: ()  => _perform('Actor', 'useRaftLeaderElection', []),
  useExternalCoordinator:(acq, ren, rel, hol) =>
                              _perform('Actor', 'useExternalCoordinator', [acq, ren, rel, hol]),
  leaderProtocol:        ()  => _perform('Actor', 'leaderProtocol', []),
  leaderHistory:         ()  => _perform('Actor', 'leaderHistory', []),
  // v1.23 — auto-reconnect policy (2- or 3-arg form)
  setReconnectPolicy: function() {
    const args = Array.prototype.slice.call(arguments);
    return _perform('Actor', 'setReconnectPolicy', args);
  },
  // v1.23 — per-link heartbeat tuning
  setHeartbeatTimeout: (iv, dead) => _perform('Actor', 'setHeartbeatTimeout', [iv, dead]),
  // v1.23 — quorum-aware Bully threshold (split-brain guard)
  setQuorumSize: (n) => _perform('Actor', 'setQuorumSize', [n]),
  // v1.23 — shared-secret Bearer token for /_ssc-cluster/* endpoints.
  setClusterAuthToken: (t) => _perform('Actor', 'setClusterAuthToken', [t]),
  // v1.23 — periodic gossip re-discovery
  requestGossip: () => _perform('Actor', 'requestGossip', []),
  // v1.23 — cluster configuration distribution
  clusterConfigSet:      (key, value) => _perform('Actor', 'clusterConfigSet',      [key, value]),
  clusterConfigGet:      (key)        => _perform('Actor', 'clusterConfigGet',      [key]),
  clusterConfigKeys:     ()           => _perform('Actor', 'clusterConfigKeys',     []),
  subscribeConfigEvents: ()           => _perform('Actor', 'subscribeConfigEvents', []),
  // v1.23 — drain / rolling-restart
  setDraining:           (b)          => _perform('Actor', 'setDraining',           [b]),
  isDraining:            ()           => _perform('Actor', 'isDraining',            []),
  drainingPeers:         ()           => _perform('Actor', 'drainingPeers',         []),
  subscribeDrainEvents:  ()           => _perform('Actor', 'subscribeDrainEvents',  []),
  // v1.23 — cluster metrics aggregation
  clusterMetricSet:      (name, value) => _perform('Actor', 'clusterMetricSet',     [name, value]),
  clusterMetricGet:      (name)        => _perform('Actor', 'clusterMetricGet',     [name]),
  clusterMetricSum:      (name)        => _perform('Actor', 'clusterMetricSum',     [name]),
  clusterMetricNames:    ()            => _perform('Actor', 'clusterMetricNames',   []),
  subscribeMetricEvents: ()            => _perform('Actor', 'subscribeMetricEvents',[]),
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

async function _runActors(bodyFn) {
  // id -> { mailbox: [], pending: _Computation | null,
  //         blocked: { matcher, k, deadline, wrapSome } | null }
  // (async) The scheduler is an async function so it can `await
  // setImmediate`/`setTimeout` between ticks — that's what lets Node's
  // event loop dispatch the HTTP/WS requests libuv `accept`ed on the
  // server bound by a `serveAsync(...)` call from inside the actor body.
  // Previously the scheduler was a synchronous `while (true)` loop with
  // `Atomics.wait(...)` waits — fast but it monopolised the main thread,
  // so any actor blocking on a long-armed `receive` left every HTTP
  // route permanently unreachable until the next mailbox delivery.
  const actors     = new Map();
  // Phase 2 supervision state (per _runActors invocation)
  const links      = new Map();   // id -> Set<id>
  const monitors   = new Map();   // watchedId -> Map<monRef, observerId>
  const trapExitMap = new Map();  // id -> bool
  let   nextMonRef = 0;
  // v1.6.x scheduled sends — timerId -> { fireAt, periodMs, targetId, msg }
  const _timers    = new Map();
  let   _nextTimerId = 0;
  // Phase 3 distributed node state
  let   _localNodeId  = "";
  const _nodeRegistry   = new Map();  // name -> localId
  const _globalRegistry = new Map();  // cluster-wide name -> Pid
  // Node-down tracking — drained by the scheduler loop.
  const _nodeDownQueue   = [];
  const _remoteLinks     = new Map();  // nodeId -> [[localActorId, remotePid], ...]
  const _remoteMonitors  = new Map();  // nodeId -> [[monRef, localActorId, remoteLocalId], ...]
  // Phase 3: outbound peer channels (nodeId -> { worker, send })
  const _peerChannels = new Map();
  // Inbound messages from peer workers (raw JSON strings), drained each tick.
  const _remoteRawInbox = [];
  // Ring buffer constants (shared with workers via workerData).
  const _RING_SLOTS = 64, _RING_SLOT_BYTES = 2048, _RING_HDR = 8;
  // v1.6.x cluster discovery state
  let _selfUrl  = '';          // set by startNode(nodeId, url)
  let _joinMode = false;       // true once joinCluster has been called
  let _joinToken = '';         // token for auto-connect to gossip'd peers
  const _peerUrls = new Map(); // nodeId -> url (populated from connectNode + peers_resp)
  // v1.23 — cluster visibility
  const _clusterEventSubs  = new Set();   // actor ids subscribed to NodeJoined/NodeLeft
  const _clusterEventQueue = [];          // {tag, nodeId, reason} pending delivery
  // v1.23 — bounded ring buffer of every cluster event as JSON lines.
  // Independent of the in-process subscription system: events land here
  // whether or not any actor has called subscribe*Events, so the
  // `GET /_ssc-cluster/events` endpoint always has data for ops tooling.
  // Mirrors `Interpreter.clusterEventLog` (cap 200, oldest-first drop).
  const _CLUSTER_EVENT_LOG_MAX = 200;
  const _clusterEventLog       = []; // JSON-string entries
  function _recordEventLog(jsonObj) {
    _clusterEventLog.push(jsonObj);
    while (_clusterEventLog.length > _CLUSTER_EVENT_LOG_MAX) _clusterEventLog.shift();
  }
  function _fireClusterEvent(tag, nodeId, reason) {
    // Mirror into the ops event log regardless of in-process subscribers.
    const ts = Date.now();
    const logEntry = (tag === 'NodeJoined')
      ? '{"ts":' + ts + ',"type":"NodeJoined","nodeId":' + JSON.stringify(nodeId) + '}'
      : '{"ts":' + ts + ',"type":"NodeLeft","nodeId":' + JSON.stringify(nodeId) +
        ',"reason":' + JSON.stringify(reason || '') + '}';
    _recordEventLog(logEntry);
    if (_clusterEventSubs.size === 0) return;
    _clusterEventQueue.push({ tag, nodeId, reason: reason || '' });
  }
  // v1.23 — shared-secret Bearer token for /_ssc-cluster/* endpoints.
  // Default reads SSC_CLUSTER_TOKEN env at runtime; runtime override via
  // the setClusterAuthToken intrinsic.  Empty string ⇒ endpoints are open
  // (backwards compatible).  Mirrors `Interpreter.clusterAuthToken`.
  let _clusterAuthToken = (typeof process !== 'undefined' && process.env && process.env.SSC_CLUSTER_TOKEN) || '';
  function _clusterAuthReject(req) {
    if (!_clusterAuthToken) return null;
    let hdr = '';
    try {
      if (req && req.headers && typeof req.headers.get === 'function') {
        hdr = req.headers.get('authorization') || req.headers.get('Authorization') || '';
      }
    } catch (_) {}
    const expected = 'Bearer ' + _clusterAuthToken;
    if (hdr === expected) return null;
    return {
      _type: 'Response', status: 401,
      headers: new Map([['Content-Type', 'application/json']]),
      body: '{"error":"unauthorized","hint":"set Authorization: Bearer <token>"}'
    };
  }
  // v1.23 — phi-accrual failure detector
  const _PHI_HIST_MAX  = 100;
  const _peerPongHist  = new Map();   // nodeId -> [intervalMs, ...] up to PHI_HIST_MAX
  const _peerLastPong  = new Map();   // nodeId -> epoch ms of last pong (mirrors INT/JVM)
  // v1.23 — cluster-wide FD: peerNodeId -> Map<targetNodeId, phi>
  const _peerPhiViews  = new Map();
  // v1.23 — leader election (Bully) state.
  let   _currentLeader      = "";
  let   _electionInProgress = false;
  let   _electionStartedAt  = 0;
  let   _gotAliveResponse   = false;
  let   _autoReelect         = false;
  const _ELECTION_TIMEOUT_MS = 2000;
  // v1.23 — protocol dispatch (cluster-raft.md §6).  "bully" today;
  //   Phase 3a flips to "raft", Phase 3b flips to "coord".
  let   _leaderProtocol      = "bully";
  let   _leaderCoordinator   = null;
  // v1.23 — coordinator lease state (cluster-raft.md §5).
  let   _coordAcquireFn      = null;
  let   _coordRenewFn        = null;
  let   _coordReleaseFn      = null;
  let   _coordHolderFn       = null;
  let   _coordIsLeader       = false;
  let   _coordTickHandle     = null;
  const _COORD_LEASE_TIMEOUT_MS  = 5000;
  const _COORD_RENEW_INTERVAL_MS = 1000;
  function _ensureCoordTickThread() {
    if (_coordTickHandle != null) return;
    _coordTickHandle = setInterval(() => {
      if (_leaderProtocol !== 'coord') { clearInterval(_coordTickHandle); _coordTickHandle = null; return; }
      try {
        if (!_coordIsLeader) {
          if (_coordAcquireFn) {
            let got = false;
            try { got = !!_coordAcquireFn(_localNodeId, _COORD_LEASE_TIMEOUT_MS); } catch (_) {}
            if (got) {
              _coordIsLeader = true;
              const prev = _currentLeader;
              _currentLeader = _localNodeId;
              if (prev !== _localNodeId) {
                _fireLeaderEvent("LeaderElected", _localNodeId);
                _recordLeaderHist(_localNodeId);
              }
            }
          }
        } else {
          if (_coordRenewFn) {
            let ok = false;
            try { ok = !!_coordRenewFn(_localNodeId); } catch (_) {}
            if (!ok) {
              _coordIsLeader = false;
              const prev = _currentLeader;
              _currentLeader = "";
              if (prev) _fireLeaderEvent("LeaderLost", prev);
            }
          }
        }
      } catch (_) {}
    }, _COORD_RENEW_INTERVAL_MS);
    if (_coordTickHandle && typeof _coordTickHandle.unref === 'function') _coordTickHandle.unref();
  }
  // v1.23 — drain-aware step-down (cluster-raft.md §7).
  function _stepDownIfLeader() {
    if (_leaderProtocol === 'raft') {
      if (_raftStateName === 'leader') {
        _raftStateName = 'follower';
        _raftLeaderId  = '';
        const prev = _currentLeader; _currentLeader = '';
        if (prev) _fireLeaderEvent("LeaderLost", prev);
      }
    } else if (_leaderProtocol === 'coord') {
      if (_coordIsLeader) {
        _coordIsLeader = false;
        if (_coordReleaseFn) {
          try { _coordReleaseFn(_localNodeId); } catch (_) {}
        }
        const prev = _currentLeader; _currentLeader = '';
        if (prev) _fireLeaderEvent("LeaderLost", prev);
      }
    } else {
      if (_currentLeader === _localNodeId) {
        _currentLeader = '';
        _fireLeaderEvent("LeaderLost", _localNodeId);
      }
    }
  }
  // v1.23 — bounded leader-claim history.
  const _LEADER_HIST_MAX     = 100;
  let   _leaderHistTermSeq   = 0;
  const _leaderHist          = []; // [[term, leaderId, ms], ...]
  function _recordLeaderHist(leaderId) {
    _leaderHistTermSeq += 1;
    _leaderHist.push([_leaderHistTermSeq, leaderId, Date.now()]);
    while (_leaderHist.length > _LEADER_HIST_MAX) _leaderHist.shift();
  }
  // v1.23 — Raft state (cluster-raft.md §4.1).
  let _raftCurrentTerm = 0;
  let _raftVotedFor    = "";
  let _raftStateName   = "follower"; // follower | candidate | leader
  let _raftLeaderId    = "";
  let _raftElectionDue = 0;
  let _raftVotes       = 0;
  const _RAFT_ELECTION_LO  = 150;
  const _RAFT_ELECTION_HI  = 300;
  const _RAFT_HEARTBEAT_MS = 50;
  let _raftTickHandle = null;
  function _raftRandTimeout() {
    return _RAFT_ELECTION_LO + Math.floor(Math.random() * (_RAFT_ELECTION_HI - _RAFT_ELECTION_LO + 1));
  }
  function _raftBroadcastHeartbeat() {
    const payload = JSON.stringify({ t: 'raft_append', from: _localNodeId, term: _raftCurrentTerm });
    for (const [, ch] of _peerChannels) { try { ch.send(payload); } catch (_) {} }
  }
  function _raftAdoptLeader(newLeader) {
    const prev = _currentLeader;
    _currentLeader = newLeader;
    if (prev !== newLeader) {
      _fireLeaderEvent("LeaderElected", newLeader);
      _recordLeaderHist(newLeader);
    }
  }
  function _startRaftElection() {
    _raftStateName    = "candidate";
    _raftCurrentTerm  = _raftCurrentTerm + 1;
    _raftVotedFor     = _localNodeId;
    _raftVotes        = 1;
    _raftElectionDue  = Date.now() + _raftRandTimeout();
    _raftPersist();
    const peerIds = [];
    for (const nid of _peerChannels.keys()) peerIds.push(nid);
    const total = peerIds.length + 1;
    if (_raftVotes > Math.floor(total / 2)) {
      _raftStateName = "leader";
      _raftLeaderId  = _localNodeId;
      _raftAdoptLeader(_localNodeId);
      _raftBroadcastHeartbeat();
    } else {
      const payload = JSON.stringify({
        t: 'raft_vote_req', from: _localNodeId, term: _raftCurrentTerm, lastLogTerm: 0
      });
      for (const nid of peerIds) {
        const ch = _peerChannels.get(nid);
        if (ch) { try { ch.send(payload); } catch (_) {} }
      }
    }
  }
  // v1.23 — Raft persistence (cluster-raft.md §4.1).  Node has `fs`;
  // browser does not run distributed Raft anyway (no inbound WS).
  let _raftFs = null;
  try { _raftFs = require('fs'); } catch (_) { _raftFs = null; }
  function _raftStatePath() {
    const key = _localNodeId ? _localNodeId.replace(/[^A-Za-z0-9._-]/g, '_') : 'default';
    return '.ssc-raft-state-' + key + '.json';
  }
  function _raftPersist() {
    if (!_raftFs) return;
    try {
      const voted = _raftVotedFor.replace(/\\/g, '\\\\').replace(/"/g, '\\"');
      const json  = '{"currentTerm":' + _raftCurrentTerm + ',"votedFor":"' + voted + '"}';
      _raftFs.writeFileSync(_raftStatePath(), json, 'utf8');
    } catch (_) {}
  }
  function _raftLoad() {
    if (!_raftFs) return;
    try {
      const p = _raftStatePath();
      if (!_raftFs.existsSync(p)) return;
      const s = _raftFs.readFileSync(p, 'utf8');
      const o = JSON.parse(s);
      if (typeof o.currentTerm === 'number') _raftCurrentTerm = o.currentTerm;
      if (typeof o.votedFor    === 'string') _raftVotedFor    = o.votedFor;
    } catch (_) {}
  }
  function _ensureRaftTickThread() {
    if (_raftTickHandle != null) return;
    _raftTickHandle = setInterval(() => {
      if (_leaderProtocol !== 'raft') { clearInterval(_raftTickHandle); _raftTickHandle = null; return; }
      const now = Date.now();
      if (_raftStateName === 'leader') {
        _raftBroadcastHeartbeat();
      } else if (now >= _raftElectionDue) {
        _startRaftElection();
      }
    }, _RAFT_HEARTBEAT_MS);
    // Don't keep the Node event loop alive on the timer — apps end naturally.
    if (_raftTickHandle && typeof _raftTickHandle.unref === 'function') _raftTickHandle.unref();
  }
  const _leaderEventSubs    = new Set();
  const _leaderEventQueue   = [];
  function _fireLeaderEvent(tag, leaderId) {
    // Mirror into ops event log regardless of subscribers (parity with INT).
    const ts = Date.now();
    _recordEventLog('{"ts":' + ts + ',"type":' + JSON.stringify(tag) +
      ',"nodeId":' + JSON.stringify(leaderId) + '}');
    if (_leaderEventSubs.size === 0) return;
    _leaderEventQueue.push({ tag, leaderId });
  }
  // v1.23 — auto-reconnect: exponential-backoff retry per peer URL after a
  // disconnect.  Both 0 ⇒ disabled (default).  giveUp caps wall-clock
  // retry budget per URL (0 = retry forever).
  let _reconnectInitialMs = 0;
  let _reconnectMaxMs     = 0;
  let _reconnectGiveUpMs  = 0;
  // v1.23 — per-link heartbeat cadence + dead-after.  Defaults match
  // pre-v1.23 hardcoded 30s/40s; `setHeartbeatTimeout` tunes them.
  let _peerHeartbeatIntervalMs  = 30000;
  let _peerHeartbeatDeadAfterMs = 40000;
  // v1.23 — quorum-aware Bully threshold.  0 = no quorum check.
  let _quorumSize = 0;
  function _hasQuorum() {
    return _quorumSize <= 0 || (_peerChannels.size + 1) >= _quorumSize;
  }
  // v1.23 — cluster configuration distribution.  LWW per key by (ts, origin).
  const _clusterConfig    = new Map();   // key -> { value, ts, origin }
  const _configEventSubs  = new Set();
  const _configEventQueue = [];
  function _fireConfigEvent(key, value) {
    if (_configEventSubs.size === 0) return;
    _configEventQueue.push({ key, value });
  }
  function _applyConfigUpdate(key, value, ts, origin) {
    const prev = _clusterConfig.get(key);
    const accept =
      !prev || ts > prev.ts || (ts === prev.ts && origin > prev.origin);
    if (accept) {
      _clusterConfig.set(key, { value, ts, origin });
      _fireConfigEvent(key, value);
    }
    return accept;
  }
  // Snapshot every locally-known config entry to a single peer.  Called on
  // every successful handshake so late-joining nodes pick up entries set
  // before they joined (LWW on the receiver protects existing values).
  function _sendConfigSnapshot(sendFn) {
    for (const [key, entry] of _clusterConfig) {
      const payload = JSON.stringify({
        t: 'config_set', key, value: entry.value, ts: entry.ts, origin: entry.origin
      });
      try { sendFn(payload); } catch (_) {}
    }
  }
  // v1.23 — drain / rolling-restart state
  let _isDrainingSelf = false;
  const _drainingPeers   = new Map(); // nodeId -> bool
  const _drainEventSubs  = new Set();
  const _drainEventQueue = [];
  function _fireDrainEvent(nodeId, draining) {
    const ts = Date.now();
    _recordEventLog('{"ts":' + ts + ',"type":"DrainStateChanged","nodeId":' +
      JSON.stringify(nodeId) + ',"draining":' + (draining ? 'true' : 'false') + '}');
    if (_drainEventSubs.size === 0) return;
    _drainEventQueue.push({ nodeId, draining });
  }
  function _sendDrainState(sendFn) {
    if (!_isDrainingSelf) return;
    const payload = JSON.stringify({ t: 'drain', from: _localNodeId, draining: true });
    try { sendFn(payload); } catch (_) {}
  }
  // v1.23 — cluster metrics aggregation: per-node gauges.
  const _clusterMetrics    = new Map(); // name -> Map<nodeId, value>
  const _metricEventSubs   = new Set();
  const _metricEventQueue  = [];
  function _fireMetricEvent(name, nodeId, value) {
    const ts = Date.now();
    _recordEventLog('{"ts":' + ts + ',"type":"MetricChanged","name":' +
      JSON.stringify(name) + ',"nodeId":' + JSON.stringify(nodeId) +
      ',"value":' + value + '}');
    if (_metricEventSubs.size === 0) return;
    _metricEventQueue.push({ name, nodeId, value });
  }
  function _applyMetricUpdate(name, nodeId, value) {
    let inner = _clusterMetrics.get(name);
    if (!inner) { inner = new Map(); _clusterMetrics.set(name, inner); }
    const prev = inner.get(nodeId);
    inner.set(nodeId, value);
    if (prev !== value) _fireMetricEvent(name, nodeId, value);
  }
  function _sendMetricSnapshot(sendFn) {
    for (const [name, inner] of _clusterMetrics) {
      const v = inner.get(_localNodeId);
      if (v != null) {
        const payload = JSON.stringify({ t: 'metric', from: _localNodeId, name, value: v });
        try { sendFn(payload); } catch (_) {}
      }
    }
  }
  // ── v1.23 — operational /_ssc-cluster/* HTTP routes ────────────────
  // Mirrors `Interpreter.registerCluster*Route` (status / drain / events /
  // step-down / metrics-prom).  Installed by `startNode` so codegen-built
  // Node bundles expose the same ops surface as interpreter-run nodes —
  // see docs/cluster-codegen-gap.md (Tier 4 gating gap #3).  Idempotent
  // via a module-level flag; double `startNode` calls are no-ops.
  let _clusterRoutesInstalled = false;
  function _hasRoute(method, path) {
    return _routes.some(r => r.method === method && r.path === path);
  }
  function _registerClusterStatusRoute() {
    const path = '/_ssc-cluster/status';
    if (_hasRoute('GET', path)) return;
    const handler = (req) => {
      const rej = _clusterAuthReject(req);
      if (rej) return rej;
      const members = [];
      for (const nid of _peerChannels.keys()) members.push(nid);
      const drainPeers = [];
      for (const [nid, dr] of _drainingPeers) if (dr) drainPeers.push(nid);
      const leaderNow = (_leaderProtocol === 'raft') ? _raftLeaderId : _currentLeader;
      const body = '{' +
        '"nodeId":'        + JSON.stringify(_localNodeId) +
        ',"leader":'       + JSON.stringify(leaderNow) +
        ',"protocol":'     + JSON.stringify(_leaderProtocol) +
        ',"members":'      + JSON.stringify(members) +
        ',"drainingSelf":' + (_isDrainingSelf ? 'true' : 'false') +
        ',"drainingPeers":'+ JSON.stringify(drainPeers) +
        ',"raftTerm":'     + String(_raftCurrentTerm) +
        ',"raftState":'    + JSON.stringify(_raftStateName) +
        '}';
      return {
        _type: 'Response', status: 200,
        headers: new Map([['Content-Type', 'application/json']]),
        body
      };
    };
    _routes.push({ method: 'GET', path, pattern: _parsePath(path), handler });
  }
  function _registerClusterDrainRoute() {
    const path = '/_ssc-cluster/drain';
    if (_hasRoute('POST', path)) return;
    const handler = (req) => {
      const rej = _clusterAuthReject(req);
      if (rej) return rej;
      const body = (req && typeof req.body === 'string') ? req.body : '';
      let enabled;
      if (body.trim().length === 0) {
        enabled = true;
      } else {
        const needle = '"enabled":';
        const i = body.indexOf(needle);
        if (i < 0) enabled = true;
        else {
          const rest = body.substring(i + needle.length).trim();
          enabled = !rest.startsWith('false');
        }
      }
      const prev = _isDrainingSelf;
      _isDrainingSelf = enabled;
      if (prev !== enabled) {
        const payload = '{"t":"drain","from":' + JSON.stringify(_localNodeId) +
          ',"draining":' + (enabled ? 'true' : 'false') + '}';
        for (const [, ch] of _peerChannels) { try { ch.send(payload); } catch (_) {} }
        _fireDrainEvent(_localNodeId, enabled);
        if (enabled) _stepDownIfLeader();
      }
      return {
        _type: 'Response', status: 200,
        headers: new Map([['Content-Type', 'application/json']]),
        body: '{"drainingSelf":' + (enabled ? 'true' : 'false') + '}'
      };
    };
    _routes.push({ method: 'POST', path, pattern: _parsePath(path), handler });
  }
  function _registerClusterEventsRoute() {
    const path = '/_ssc-cluster/events';
    if (_hasRoute('GET', path)) return;
    const handler = (req) => {
      const rej = _clusterAuthReject(req);
      if (rej) return rej;
      let sinceMs = 0;
      try {
        if (req && req.query && typeof req.query.get === 'function') {
          const s = req.query.get('since');
          if (s) { const n = Number(s); if (Number.isFinite(n)) sinceMs = n; }
        }
      } catch (_) {}
      const parts = [];
      const tsPrefix = '{"ts":';
      for (const line of _clusterEventLog) {
        let pass = true;
        if (sinceMs > 0) {
          pass = false;
          if (line.startsWith(tsPrefix)) {
            const end = line.indexOf(',', tsPrefix.length);
            if (end > 0) {
              const v = Number(line.substring(tsPrefix.length, end));
              if (Number.isFinite(v) && v > sinceMs) pass = true;
            }
          }
        }
        if (pass) parts.push(line);
      }
      return {
        _type: 'Response', status: 200,
        headers: new Map([['Content-Type', 'application/json']]),
        body: '[' + parts.join(',') + ']'
      };
    };
    _routes.push({ method: 'GET', path, pattern: _parsePath(path), handler });
  }
  function _registerClusterStepDownRoute() {
    const path = '/_ssc-cluster/step-down';
    if (_hasRoute('POST', path)) return;
    const handler = (req) => {
      const rej = _clusterAuthReject(req);
      if (rej) return rej;
      let wasLeader = false;
      if (_leaderProtocol === 'raft')       wasLeader = (_raftStateName === 'leader');
      else if (_leaderProtocol === 'coord') wasLeader = !!_coordIsLeader;
      else                                  wasLeader = (_currentLeader === _localNodeId);
      if (!wasLeader) {
        return {
          _type: 'Response', status: 409,
          headers: new Map([['Content-Type', 'application/json']]),
          body: '{"error":"not_leader","leader":' + JSON.stringify(_currentLeader) + '}'
        };
      }
      _stepDownIfLeader();
      return {
        _type: 'Response', status: 200,
        headers: new Map([['Content-Type', 'application/json']]),
        body: '{"steppedDown":true,"nodeId":' + JSON.stringify(_localNodeId) + '}'
      };
    };
    _routes.push({ method: 'POST', path, pattern: _parsePath(path), handler });
  }
  function _registerClusterMetricsPromRoute() {
    const path = '/_ssc-cluster/metrics-prom';
    if (_hasRoute('GET', path)) return;
    const sanitize = (s) => {
      let out = '';
      for (let i = 0; i < s.length; i++) {
        const c = s.charCodeAt(i);
        const ok =
          (c >= 97 && c <= 122) /* a-z */ ||
          (c >= 65 && c <= 90)  /* A-Z */ ||
          (c >= 48 && c <= 57)  /* 0-9 */ ||
          c === 95 /* _ */ || c === 58 /* : */;
        out += ok ? s.charAt(i) : '_';
      }
      if (out.length > 0 && out.charCodeAt(0) >= 48 && out.charCodeAt(0) <= 57) out = '_' + out;
      return out;
    };
    const escLabel = (s) => s.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '\\n');
    const handler = (req) => {
      const rej = _clusterAuthReject(req);
      if (rej) return rej;
      let out = '';
      for (const [name, inner] of _clusterMetrics) {
        const pName = sanitize(name);
        out += '# TYPE ' + pName + ' gauge\n';
        for (const [nodeId, value] of inner) {
          out += pName + '{nodeId="' + escLabel(nodeId) + '"} ' + Number(value) + '\n';
        }
      }
      return {
        _type: 'Response', status: 200,
        headers: new Map([['Content-Type', 'text/plain; version=0.0.4; charset=utf-8']]),
        body: out
      };
    };
    _routes.push({ method: 'GET', path, pattern: _parsePath(path), handler });
  }
  function _installClusterRoutes() {
    if (_clusterRoutesInstalled) return;
    _clusterRoutesInstalled = true;
    _registerClusterStatusRoute();
    _registerClusterDrainRoute();
    _registerClusterEventsRoute();
    _registerClusterStepDownRoute();
    _registerClusterMetricsPromRoute();
  }
  // v1.23 — URL-keyed dedupe so concurrent peer-loss + dial-failure
  // events for the same URL don't each spin up an independent
  // exponential-backoff loop.
  const _reconnectActive = new Set();
  function _scheduleReconnect(rurl, rtok) {
    if (_reconnectActive.has(rurl)) return;
    _reconnectActive.add(rurl);
    const startedAt = Date.now();
    let delay = Math.max(_reconnectInitialMs, 1);
    const attempt = () => {
      if (_reconnectInitialMs <= 0) { _reconnectActive.delete(rurl); return; }
      // Already reconnected?  `_peerUrls` is populated on successful handshake.
      for (const u of _peerUrls.values()) if (u === rurl) { _reconnectActive.delete(rurl); return; }
      // v1.23 — give-up budget: stop after the configured wall-clock.
      if (_reconnectGiveUpMs > 0 && (Date.now() - startedAt) >= _reconnectGiveUpMs) {
        _reconnectActive.delete(rurl); return;
      }
      try { _connectNodeAsync(rurl, rtok); } catch (_) {}
      const cap = _reconnectMaxMs > 0 ? _reconnectMaxMs : delay;
      delay = Math.min(delay * 2, Math.max(cap, delay));
      setTimeout(attempt, delay);
    };
    setTimeout(attempt, delay);
  }
  function _broadcastCoordinator() {
    const payload = '{"t":"coordinator","from":' + JSON.stringify(_localNodeId) + '}';
    for (const [, ch] of _peerChannels) { try { ch.send(payload); } catch (_) {} }
  }
  function _startElection() {
    if (!_localNodeId) {
      const prev = _currentLeader; _currentLeader = _localNodeId;
      if (prev !== _localNodeId) { _fireLeaderEvent("LeaderElected", _localNodeId); _recordLeaderHist(_localNodeId); }
      return;
    }
    const higher = [];
    for (const nid of _peerChannels.keys()) if (nid > _localNodeId) higher.push(nid);
    if (higher.length === 0) {
      // v1.23 — quorum gate: refuse self-claim when below quorum
      // (split-brain guard).  No-op when `_quorumSize = 0`.
      if (_hasQuorum()) {
        const prev = _currentLeader; _currentLeader = _localNodeId;
        _broadcastCoordinator();
        if (prev !== _localNodeId) { _fireLeaderEvent("LeaderElected", _localNodeId); _recordLeaderHist(_localNodeId); }
      }
    } else {
      _electionInProgress = true;
      _electionStartedAt  = Date.now();
      _gotAliveResponse   = false;
      const payload = '{"t":"election","from":' + JSON.stringify(_localNodeId) + '}';
      for (const nid of higher) {
        const ch = _peerChannels.get(nid);
        if (ch) { try { ch.send(payload); } catch (_) {} }
      }
    }
  }
  function _recordPongInterval(nid) {
    const now = Date.now();
    const last = _peerLastPong.get(nid);
    if (last != null && last > 0) {
      let hist = _peerPongHist.get(nid);
      if (!hist) { hist = []; _peerPongHist.set(nid, hist); }
      hist.push(now - last);
      while (hist.length > _PHI_HIST_MAX) hist.shift();
    }
    _peerLastPong.set(nid, now);
  }
  function _computePhi(nid) {
    const hist = _peerPongHist.get(nid);
    if (!hist || hist.length === 0) return Number.POSITIVE_INFINITY;
    const n = hist.length;
    let s = 0; for (let i = 0; i < n; i++) s += hist[i];
    const mean = s / n;
    let sq = 0; for (let i = 0; i < n; i++) { const d = hist[i] - mean; sq += d * d; }
    const variance = n > 1 ? sq / (n - 1) : 1.0;
    const stddev   = Math.max(Math.sqrt(variance), 50.0);
    const last     = _peerLastPong.get(nid);
    const now      = Date.now();
    const elapsed  = last == null ? Number.POSITIVE_INFINITY : now - last;
    if (elapsed <= mean) return 0.0;
    // Right-tail Normal approximation (Akka / Cassandra style):
    //   phi = -log10( exp(-z² / 2) / (z * sqrt(2π)) )  with  z = (elapsed - μ) / σ
    const z    = (elapsed - mean) / stddev;
    const tail = Math.exp(-z * z / 2) / (z * Math.sqrt(2 * Math.PI));
    if (tail <= 0) return Number.POSITIVE_INFINITY;
    return -Math.log10(Math.min(tail, 1.0));
  }

  // Drain all peer ring buffers into _remoteRawInbox.
  function _drainPeerBuffers() {
    for (const [, peer] of _peerChannels) {
      // Inbound (server-side) peers route messages through the WS
      // `onMessage` callback which pushes straight into
      // `_remoteRawInbox`; they have no SharedArrayBuffer ring (`sab:
      // null`).  Skipping them here avoids `new Int32Array(null, ...)`
      // which throws "Invalid atomic access index" under Node ≥ 16.
      if (!peer.sab) continue;
      const hdr = new Int32Array(peer.sab, 0, 2);
      for (;;) {
        const wc = Atomics.load(hdr, 0);
        const rc = Atomics.load(hdr, 1);
        if (wc === rc) break;
        const slotOff = (rc % _RING_SLOTS) * _RING_SLOT_BYTES;
        const len  = new DataView(peer.sab).getInt32(_RING_HDR + slotOff, true);
        if (len > 0) {
          const bytes = new Uint8Array(peer.sab, _RING_HDR + slotOff + 4, len);
          _remoteRawInbox.push(Buffer.from(bytes).toString('utf8'));
        }
        Atomics.store(hdr, 1, rc + 1);
      }
    }
  }

  // Deserialise a $t-tagged JSON object into a ScalaScript value.
  function _deserActorVal(o) {
    if (o == null) return undefined;
    switch (o.$t) {
      case 'i': case 'd': return o.v;
      case 's': return o.v;
      case 'b': return o.v;
      case 'u': return undefined;
      case 'pid': return Pid(o.n, o.id);
      case 'l': return o.v.map(_deserActorVal);
      case 'o': {
        const obj = { _type: o.cls };
        for (const [k, v] of Object.entries(o.f)) obj[k] = _deserActorVal(v);
        return obj;
      }
      default: return String(o.v != null ? o.v : '');
    }
  }

  // Serialise a ScalaScript value to a $t-tagged JSON string.
  function _serActorVal(v) {
    if (v === null || v === undefined) return '{"$t":"u"}';
    if (typeof v === 'number' && Number.isInteger(v)) return '{"$t":"i","v":' + v + '}';
    if (typeof v === 'number') return '{"$t":"d","v":' + v + '}';
    if (typeof v === 'string')  return '{"$t":"s","v":' + JSON.stringify(v) + '}';
    if (typeof v === 'boolean') return '{"$t":"b","v":' + v + '}';
    if (v && v._type === 'Pid') return '{"$t":"pid","n":' + JSON.stringify(v.nodeId) + ',"id":' + v.localId + '}';
    if (Array.isArray(v)) return '{"$t":"l","v":[' + v.map(_serActorVal).join(',') + ']}';
    if (v && v._type) {
      const flds = Object.entries(v).filter(([k]) => k !== '_type').map(([k, vv]) => JSON.stringify(k) + ':' + _serActorVal(vv)).join(',');
      return '{"$t":"o","cls":' + JSON.stringify(v._type) + ',"f":{' + flds + '}}';
    }
    return '{"$t":"s","v":' + JSON.stringify(String(v)) + '}';
  }

  // Establish a new outbound peer connection (shared by connectNode op and joinCluster gossip).
  function _connectNodeAsync(peerUrl, token) {
    if (_peerChannels.has('__pending__' + peerUrl)) return; // already connecting
    const sab = new SharedArrayBuffer(_RING_HDR + _RING_SLOTS * _RING_SLOT_BYTES);
    const ownNodeId = _localNodeId;
    const ringSlots = _RING_SLOTS, ringSlotBytes = _RING_SLOT_BYTES, ringHdr = _RING_HDR;
    const workerSrc = [
      "const { workerData, parentPort } = require('worker_threads');",
      "const { url, token, sab, ownNodeId, RS, RSB, RH } = workerData;",
      "let WsClass; try { WsClass = require('ws'); } catch(_) { throw new Error('connectNode requires the ws npm package'); }",
      "const hdr  = new Int32Array(sab, 0, 2);",
      "function ringWrite(json) {",
      "  const wc = Atomics.load(hdr, 0), rc = Atomics.load(hdr, 1);",
      "  if (((wc - rc + RS) % RS) >= RS - 1) return;",
      "  const bytes = Buffer.from(json, 'utf8');",
      "  if (bytes.length + 4 > RSB) return;",
      "  const off = RH + (wc % RS) * RSB;",
      "  new DataView(sab).setInt32(off, bytes.length, true);",
      "  new Uint8Array(sab).set(bytes, off + 4);",
      "  Atomics.store(hdr, 0, wc + 1);",
      "}",
      "const hdrs = token ? { Authorization: 'Bearer ' + token } : {};",
      "const ws = new WsClass(url, ['ssc-actors-v1'], { headers: hdrs });",
      "let peerNodeId = '';",
      "ws.on('open', () => ws.send(JSON.stringify({ nodeId: ownNodeId })));",
      "ws.on('message', (data) => {",
      "  const msg = Buffer.isBuffer(data) ? data.toString('utf8') : String(data);",
      "  if (!peerNodeId) {",
      "    try { const h = JSON.parse(msg); if (h.nodeId) { peerNodeId = h.nodeId; ringWrite(JSON.stringify({ t: 'handshake', nodeId: peerNodeId })); } } catch(_) {}",
      "  } else {",
      "    // v1.23 — inject peer nodeId into 'pong' frames so the main thread can",
      "    // attribute the inter-arrival sample to the right peer (phi-accrual).",
      "    try { const p = JSON.parse(msg); if (p && p.t === 'pong') { ringWrite(JSON.stringify({ t: 'pong', from: peerNodeId })); return; } } catch(_) {}",
      "    ringWrite(msg);",
      "  }",
      "});",
      "ws.on('close', () => { if (peerNodeId) ringWrite(JSON.stringify({ t: 'down', nodeId: peerNodeId })); });",
      "ws.on('error', (e) => { console.error('ssc-actors connectNode error [' + url + ']:', e.message); if (peerNodeId) ringWrite(JSON.stringify({ t: 'down', nodeId: peerNodeId })); });",
      "parentPort.on('message', ({ json }) => { try { if (ws.readyState === 1) ws.send(json); } catch(_) {} });",
      "setInterval(() => { try { if (ws.readyState === 1) ws.send(JSON.stringify({ t: 'ping' })); } catch(_) {} }, 30000);"
    ].join('\n');
    const { Worker } = require('worker_threads');
    const worker = new Worker(workerSrc, { eval: true,
      workerData: { url: peerUrl, token, sab, ownNodeId, RS: ringSlots, RSB: ringSlotBytes, RH: ringHdr } });
    const sendFn = (json) => worker.postMessage({ json });
    _peerChannels.set('__pending__' + peerUrl, { sab, send: sendFn, worker, pending: true, url: peerUrl });
  }

  // Process _remoteRawInbox: deliver messages, handle handshakes, node-downs.
  function _processRemoteInbox() {
    while (_remoteRawInbox.length > 0) {
      const raw = _remoteRawInbox.shift();
      try {
        const env = JSON.parse(raw);
        if (env.t === 'handshake') {
          // Worker completed WS handshake — upgrade pending entry to real nodeId.
          const peerNodeId = env.nodeId;
          for (const [key, peer] of _peerChannels) {
            if (peer.pending) {
              _peerChannels.delete(key);
              _peerChannels.set(peerNodeId, { sab: peer.sab, send: peer.send, worker: peer.worker });
              _peerUrls.set(peerNodeId, peer.url || '');
              _fireClusterEvent('NodeJoined', peerNodeId);
              // If in joinCluster mode, request this peer's peer list.
              if (_joinMode) {
                peer.send(JSON.stringify({ t: 'peers_req', from: _localNodeId }));
              }
              // v1.23 — snapshot the cluster config + drain state to the new peer.
              _sendConfigSnapshot(peer.send);
              _sendDrainState(peer.send);
              _sendMetricSnapshot(peer.send);
              break;
            }
          }
        } else if (env.t === 'msg') {
          const toId = env.to && env.to.localId;
          if (toId != null) {
            const body = _deserActorVal(env.body);
            const st   = actors.get(toId);
            if (st) {
              st.mailbox.push(body);
              tryWakeBlocked(toId);
            }
          }
        } else if (env.t === 'ping') {
          const peer = _peerChannels.get(env.from);
          if (peer) peer.send(JSON.stringify({ t: 'pong' }));
        } else if (env.t === 'pong') {
          // v1.23 — phi-accrual: record inter-pong interval.  The worker
          // injects `from: peerNodeId` so we can attribute the sample.
          if (env.from) _recordPongInterval(env.from);
        } else if (env.t === 'down') {
          _nodeDownQueue.push(env.nodeId);
          const _lostUrl = _peerUrls.get(env.nodeId) || '';
          _peerChannels.delete(env.nodeId);
          _peerUrls.delete(env.nodeId);
          _peerPongHist.delete(env.nodeId);
          _peerLastPong.delete(env.nodeId);
          _peerPhiViews.delete(env.nodeId);
          _drainingPeers.delete(env.nodeId);
          for (const [, inner] of _clusterMetrics) inner.delete(env.nodeId);
          _fireClusterEvent('NodeLeft', env.nodeId, 'disconnect');
          if (_currentLeader === env.nodeId) {
            _currentLeader = "";
            _fireLeaderEvent("LeaderLost", env.nodeId);
            if (_autoReelect) _startElection();
          }
          if (_reconnectInitialMs > 0 && _lostUrl) _scheduleReconnect(_lostUrl, _joinToken || '');
        } else if (env.t === 'peers_req') {
          // Respond with our known peer URLs + self URL.
          const myPeers = [];
          if (_selfUrl) myPeers.push({ nodeId: _localNodeId, url: _selfUrl });
          for (const [nid] of _peerChannels) {
            const u = _peerUrls.get(nid);
            if (u) myPeers.push({ nodeId: nid, url: u });
          }
          const reqFrom = env.from;
          const reqChan = reqFrom ? _peerChannels.get(reqFrom) : null;
          if (reqChan) reqChan.send(JSON.stringify({ t: 'peers_resp', peers: myPeers }));
        } else if (env.t === 'peers_resp') {
          // Connect to any peers we don't yet know.
          const peers = env.peers || [];
          for (const { nodeId: pnid, url: purl } of peers) {
            if (pnid && purl && pnid !== _localNodeId && !_peerChannels.has(pnid)) {
              _connectNodeAsync(purl, _joinToken);
            }
          }
        } else if (env.t === 'global_reg') {
          if (env.name && env.nodeId != null) {
            _globalRegistry.set(env.name, Pid(env.nodeId, Number(env.localId)));
          }
        } else if (env.t === 'phi_vector') {
          // v1.23 — peer broadcasted its phi vector; record its view.
          if (env.from && Array.isArray(env.view)) {
            const m = new Map();
            for (const pair of env.view) {
              if (Array.isArray(pair) && pair.length === 2) {
                m.set(String(pair[0]), Number(pair[1]));
              }
            }
            _peerPhiViews.set(env.from, m);
          }
        } else if (env.t === 'election') {
          // v1.23 — Bully: lower-id peer is calling an election.
          if (env.from && env.from < _localNodeId) {
            const reply = '{"t":"alive","from":' + JSON.stringify(_localNodeId) + '}';
            const ch = _peerChannels.get(env.from);
            if (ch) { try { ch.send(reply); } catch (_) {} }
            if (!_electionInProgress) _startElection();
          }
        } else if (env.t === 'alive') {
          _gotAliveResponse = true;
        } else if (env.t === 'coordinator') {
          if (env.from) {
            const prev = _currentLeader;
            _currentLeader = env.from;
            _electionInProgress = false;
            if (prev !== env.from) { _fireLeaderEvent("LeaderElected", env.from); _recordLeaderHist(env.from); }
          }
        } else if (env.t === 'config_set') {
          const k = env.key != null ? String(env.key) : '';
          const v = env.value != null ? String(env.value) : '';
          const o = env.origin != null ? String(env.origin) : '';
          const t = env.ts != null ? Number(env.ts) : 0;
          if (k) _applyConfigUpdate(k, v, t, o);
        } else if (env.t === 'drain') {
          const from = env.from != null ? String(env.from) : '';
          if (from) {
            const dr = !!env.draining;
            const prev = _drainingPeers.get(from);
            _drainingPeers.set(from, dr);
            if (prev !== dr) _fireDrainEvent(from, dr);
          }
        } else if (env.t === 'metric') {
          const from  = env.from != null ? String(env.from) : '';
          const name  = env.name != null ? String(env.name) : '';
          const value = env.value != null ? Number(env.value) : 0;
          if (from && name) _applyMetricUpdate(name, from, value);
        } else if (env.t === 'raft_vote_req') {
          const from = env.from != null ? String(env.from) : '';
          const term = env.term != null ? Number(env.term) : 0;
          if (from) {
            let granted = false;
            let mutated = false;
            if (term < _raftCurrentTerm) granted = false;
            else {
              if (term > _raftCurrentTerm) {
                _raftCurrentTerm = term;
                _raftVotedFor    = "";
                _raftStateName   = "follower";
                mutated = true;
              }
              if (_raftVotedFor === "" || _raftVotedFor === from) {
                _raftVotedFor    = from;
                _raftElectionDue = Date.now() + _raftRandTimeout();
                mutated = true;
                granted = true;
              }
            }
            if (mutated) _raftPersist();
            const reply = JSON.stringify({
              t: 'raft_vote_resp', from: _localNodeId, term: _raftCurrentTerm, granted
            });
            const ch = _peerChannels.get(from);
            if (ch) { try { ch.send(reply); } catch (_) {} }
          }
        } else if (env.t === 'raft_vote_resp') {
          const term = env.term != null ? Number(env.term) : 0;
          const granted = !!env.granted;
          if (term === _raftCurrentTerm && _raftStateName === "candidate" && granted) {
            _raftVotes = _raftVotes + 1;
            const total = _peerChannels.size + 1;
            if (_raftVotes > Math.floor(total / 2)) {
              _raftStateName = "leader";
              _raftLeaderId  = _localNodeId;
              _raftAdoptLeader(_localNodeId);
              _raftBroadcastHeartbeat();
            }
          }
        } else if (env.t === 'raft_append') {
          const from = env.from != null ? String(env.from) : '';
          const term = env.term != null ? Number(env.term) : 0;
          if (from && term >= _raftCurrentTerm) {
            const termChanged = term > _raftCurrentTerm;
            _raftCurrentTerm = term;
            _raftStateName   = "follower";
            const prevLeader = _raftLeaderId;
            _raftLeaderId    = from;
            _raftElectionDue = Date.now() + _raftRandTimeout();
            if (termChanged) _raftPersist();
            if (prevLeader !== from) _raftAdoptLeader(from);
          }
        }
      } catch (_e) {}
    }
  }

  function _fireNodeDown(deadNodeId) {
    const deadPidOf = (localId) => Pid(deadNodeId, localId);
    const mons = _remoteMonitors.get(deadNodeId) || [];
    _remoteMonitors.delete(deadNodeId);
    for (const [monRef, observerId, remoteLocalId] of mons) {
      const st = actors.get(observerId);
      if (st) {
        st.mailbox.push({ _type: 'Down', ref: monRef, from: deadPidOf(remoteLocalId), reason: 'noconnection' });
        tryWakeBlocked(observerId);
      }
    }
    const lnks = _remoteLinks.get(deadNodeId) || [];
    _remoteLinks.delete(deadNodeId);
    for (const [localActorId, remotePid] of lnks) {
      const ls = links.get(localActorId);
      if (ls) ls.delete(remotePid.localId);
      if (trapExitMap.get(localActorId)) {
        const st = actors.get(localActorId);
        if (st) {
          st.mailbox.push({ _type: 'Exit', from: deadPidOf(remotePid.localId), reason: 'noconnection' });
          tryWakeBlocked(localActorId);
        }
      } else {
        killActor(localActorId, 'noconnection');
      }
    }
  }

  const ready  = [];
  let   nextId = 0;
  let   rootResult = undefined;

  function spawnActor(thunk, cap, overflow) {
    const id = nextId++;
    actors.set(id, { mailbox: [], pending: thunk(), blocked: null,
                     cap: cap || 0, overflow: overflow || '',
                     blockedSends: [] });
    ready.push(id);
    return Pid(_localNodeId, id);
  }
  const rootId = spawnActor(bodyFn);

  function _resumeBlockedSender(state) {
    if (!state.cap || !state.blockedSends || state.blockedSends.length === 0) return;
    if (state.mailbox.length >= state.cap) return;
    while (state.blockedSends.length > 0) {
      const { senderId, msg, k } = state.blockedSends.shift();
      const ss = actors.get(senderId);
      if (!ss) continue;  // dead sender — skip
      state.mailbox.push(msg);
      ss.pending = k(undefined);
      ready.push(senderId);
      return;
    }
  }

  function tryDeliver(state, matcher, wrapSome) {
    while (state.mailbox.length > 0) {
      const msg = state.mailbox[0];
      const r   = matcher(msg);
      if (r && r.matched) {
        state.mailbox.shift();
        _resumeBlockedSender(state);
        const bodyC = r.body();
        return wrapSome
          ? new _FlatMap(bodyC, (v) => _Some(v))
          : bodyC;
      }
      state.mailbox.shift();  // dead-letter
      _resumeBlockedSender(state);
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
    const _dying = actors.get(targetId);
    actors.delete(targetId);
    trapExitMap.delete(targetId);
    // Resume blocked senders: target died → send becomes silent no-op.
    if (_dying && _dying.blockedSends && _dying.blockedSends.length > 0) {
      for (const { senderId, k } of _dying.blockedSends) {
        const ss = actors.get(senderId);
        if (ss) { ss.pending = k(undefined); ready.push(senderId); }
      }
      _dying.blockedSends.length = 0;
    }

    const deadPid = Pid(_localNodeId, targetId);

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
      case 'spawnLink': {
        const childPid = spawnActor(args[0]);
        // Atomic bidirectional link
        const childId = childPid.localId;
        if (!links.has(id))      links.set(id,      new Set());
        if (!links.has(childId)) links.set(childId, new Set());
        links.get(id).add(childId);
        links.get(childId).add(id);
        return { suspend: false, next: k(childPid) };
      }
      case 'spawnBounded': {
        const cap      = args[0];
        const overflow = args[1] && (args[1]._type || '');
        const childPid = spawnActor(args[2], cap, overflow);
        return { suspend: false, next: k(childPid) };
      }
      case 'self':
        return { suspend: false, next: k(Pid(_localNodeId, id)) };
      case 'processInfo': {
        const target = args[0];
        if (!target || target._type !== 'Pid') return { suspend: false, next: k(undefined) };
        const targetId = target.localId;
        const ts = actors.get(targetId);
        if (!ts) return { suspend: false, next: k(undefined) };  // dead → None
        const lnks = links.get(targetId);
        const linkList = lnks ? Array.from(lnks).map(lid => Pid(_localNodeId, lid)) : [];
        const status   = ts.blocked ? 'blocked' : 'running';
        const info = { _type: 'ProcessInfo', mailboxSize: ts.mailbox.length,
                       links: linkList, status };
        return { suspend: false, next: k({ _type: 'Some', value: info }) };
      }
      case 'send': {
        const target = args[0];
        if (target && target._type === 'Pid') {
          const targetNode = target.nodeId || '';
          if (targetNode && targetNode !== _localNodeId) {
            // Remote send — serialise and deliver via peer channel.
            const peer = _peerChannels.get(targetNode);
            if (peer) {
              const body = _serActorVal(args[1]);
              const env  = '{"t":"msg","to":{"nodeId":' + JSON.stringify(targetNode) +
                           ',"localId":' + target.localId + '},"from":{"nodeId":' +
                           JSON.stringify(_localNodeId) + ',"localId":' + id + '},"body":' + body + '}';
              peer.send(env);
            }
          } else {
            const ts = actors.get(target.localId);
            if (ts) {
              // Bounded mailbox: apply overflow strategy when at capacity.
              if (ts.cap > 0 && ts.mailbox.length >= ts.cap) {
                switch (ts.overflow) {
                  case 'DropOldest':
                    ts.mailbox.shift();
                    ts.mailbox.push(args[1]);
                    break;
                  case 'DropNewest':
                    return { suspend: false, next: k(undefined) };
                  case 'Fail':
                    killActor(id, { _type: 'mailbox_overflow' });
                    if (!actors.has(id)) return { suspend: true };
                    return { suspend: false, next: k(undefined) };
                  case 'Block':
                    ts.blockedSends.push({ senderId: id, msg: args[1], k });
                    return { suspend: true };
                  default:
                    ts.mailbox.push(args[1]);
                }
              } else {
                ts.mailbox.push(args[1]);
              }
              if (ts.blocked) {
                const b = ts.blocked;
                const delivered = tryDeliver(ts, b.matcher, b.wrapSome);
                if (delivered !== null) {
                  ts.pending = new _FlatMap(delivered, b.k);
                  ts.blocked = null;
                  ready.push(target.localId);
                }
              }
            }
          }
        }
        return { suspend: false, next: k(undefined) };
      }
      case 'exit': {
        const target = args[0];
        if (target && target._type === 'Pid') {
          killActor(target.localId, args[1]);
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
"""

private val JsRuntimeAsyncB: String = """
      // ── v1.6 Phase 2 — supervision ────────────────────────────────────
      case 'link': {
        const target = args[0];
        if (target && target._type === 'Pid') {
          const targetNodeId = target.nodeId;
          const targetId     = target.localId;
          if (targetNodeId && targetNodeId !== _localNodeId) {
            // Remote pid — register for node-down notification.
            if (!_remoteLinks.has(targetNodeId)) _remoteLinks.set(targetNodeId, []);
            _remoteLinks.get(targetNodeId).push([id, target]);
          } else if (actors.has(targetId)) {
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
          const targetNodeId = target.nodeId;
          const targetId     = target.localId;
          const monRef = nextMonRef++;
          if (targetNodeId && targetNodeId !== _localNodeId) {
            // Remote pid — register for node-down notification.
            if (!_remoteMonitors.has(targetNodeId)) _remoteMonitors.set(targetNodeId, []);
            _remoteMonitors.get(targetNodeId).push([monRef, id, targetId]);
            return { suspend: false, next: k(monRef) };
          } else if (actors.has(targetId)) {
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
      // ── v1.6 Phase 3 — distributed node primitives ────────────────────
      case 'startNode': {
        _localNodeId = String(args[0]);
        if (args[1] != null) _selfUrl = String(args[1]);
        // v1.23 — auto-register operational /_ssc-cluster/* HTTP routes
        // so `ssc cluster status / drain / events / step-down /
        // metrics-prom` work against codegen-built Node nodes.  Idempotent.
        _installClusterRoutes();
        // Register /_ssc-actors WS handler for inbound peer connections.
        // Peers connect, exchange nodeId handshake, then send actor messages.
        if (typeof onWebSocket === 'function') {
          onWebSocket('/_ssc-actors', (ws) => {
            let peerNodeId = '';
            ws.onMessage((msg) => {
              if (!peerNodeId) {
                try {
                  const h = JSON.parse(msg);
                  if (h.nodeId) {
                    peerNodeId = h.nodeId;
                    ws.send(JSON.stringify({ nodeId: _localNodeId }));
                    // Register send channel for this inbound peer.
                    _peerChannels.set(peerNodeId, { sab: null, send: (json) => ws.send(json) });
                    _fireClusterEvent('NodeJoined', peerNodeId);
                    // v1.23 — snapshot the cluster config + drain state to the new peer.
                    _sendConfigSnapshot((json) => ws.send(json));
                    _sendDrainState((json) => ws.send(json));
                    _sendMetricSnapshot((json) => ws.send(json));
                  }
                } catch (_) {}
              } else {
                // v1.23 — tag pongs inbound on this server-side channel.
                try {
                  const p = JSON.parse(msg);
                  if (p && p.t === 'pong') {
                    _remoteRawInbox.push(JSON.stringify({ t: 'pong', from: peerNodeId }));
                    return;
                  }
                } catch (_) {}
                _remoteRawInbox.push(msg);
              }
            });
            ws.onClose(() => {
              if (peerNodeId) {
                _nodeDownQueue.push(peerNodeId);
                _peerChannels.delete(peerNodeId);
                _peerUrls.delete(peerNodeId);
                _peerPongHist.delete(peerNodeId);
                _peerLastPong.delete(peerNodeId);
                _peerPhiViews.delete(peerNodeId);
                _drainingPeers.delete(peerNodeId);
                for (const [, inner] of _clusterMetrics) inner.delete(peerNodeId);
                _fireClusterEvent('NodeLeft', peerNodeId, 'disconnect');
                if (_currentLeader === peerNodeId) {
                  _currentLeader = "";
                  _fireLeaderEvent("LeaderLost", peerNodeId);
                  if (_autoReelect) _startElection();
                }
              }
            });
          });
        }
        return { suspend: false, next: k(undefined) };
      }
      case 'connectNode': {
        const peerUrl = String(args[0]);
        const token   = args[1] != null ? String(args[1]) : '';
        _connectNodeAsync(peerUrl, token);
        return { suspend: false, next: k(undefined) };
      }
      case 'joinCluster': {
        _joinMode  = true;
        _joinToken = args[1] != null ? String(args[1]) : '';
        const seeds = args[0];
        const urls  = Array.isArray(seeds) ? seeds : [];
        for (const u of urls) { if (typeof u === 'string' && u) _connectNodeAsync(u, _joinToken); }
        return { suspend: false, next: k(undefined) };
      }
      case 'register': {
        const regName = args[0];
        const regPid  = args[1];
        if (regPid && regPid._type === 'Pid') _nodeRegistry.set(regName, regPid.localId);
        return { suspend: false, next: k(undefined) };
      }
      case 'whereis': {
        const lookName = args[0];
        const found = _nodeRegistry.has(lookName)
          ? { _type: 'Some', value: Pid(_localNodeId, _nodeRegistry.get(lookName)) }
          : { _type: 'None' };
        return { suspend: false, next: k(found) };
      }
      case 'globalRegister': {
        const grName = args[0];
        const grPidRaw = args[1];
        if (grPidRaw && grPidRaw._type === 'Pid') {
          // v1.23 — stamp local nodeId on Pids that came back from a
          // local spawn (which sets nodeId='').  Without this the
          // broadcast payload's `nodeId` is empty and remote nodes
          // silently drop every cross-node send to this name.
          const grNid = grPidRaw.nodeId ? grPidRaw.nodeId : _localNodeId;
          const grPid = Pid(grNid, grPidRaw.localId);
          _globalRegistry.set(grName, grPid);
          const payload = JSON.stringify({ t: 'global_reg', name: grName, nodeId: grNid, localId: String(grPid.localId) });
          for (const [, peer] of _peerChannels) { try { peer.send(payload); } catch (_) {} }
        }
        return { suspend: false, next: k(undefined) };
      }
      case 'globalWhereis': {
        const gwName = args[0];
        const gwPid  = _globalRegistry.get(gwName);
        const found  = gwPid ? { _type: 'Some', value: gwPid } : { _type: 'None' };
        return { suspend: false, next: k(found) };
      }
      // v1.23 — cluster visibility
      case 'clusterMembers': {
        const mems = [];
        for (const [nid, peer] of _peerChannels) {
          if (peer && !peer.pending) mems.push(nid);
        }
        return { suspend: false, next: k(mems) };
      }
      case 'subscribeClusterEvents': {
        _clusterEventSubs.add(id);
        return { suspend: false, next: k(undefined) };
      }
      // v1.23 — phi-accrual failure detector
      case 'phiOf': {
        return { suspend: false, next: k(_computePhi(String(args[0]))) };
      }
      case 'isSuspect': {
        const thr = args[1] == null ? 8.0 : Number(args[1]);
        return { suspend: false, next: k(_computePhi(String(args[0])) >= thr) };
      }
      // v1.23 — local node identity
      case 'selfNode': {
        return { suspend: false, next: k(_localNodeId) };
      }
      // v1.23 — cluster health (phi vector for connected peers)
      case 'clusterHealth': {
        const m = new Map();
        for (const [nid, peer] of _peerChannels) {
          if (peer && !peer.pending) m.set(nid, _computePhi(nid));
        }
        return { suspend: false, next: k(m) };
      }
      // v1.23 — cluster-wide FD: broadcast phi vector to peers.
      case 'broadcastHealth': {
        const view = [];
        for (const [nid, peer] of _peerChannels) {
          if (peer && !peer.pending) {
            const phi = _computePhi(nid);
            if (Number.isFinite(phi)) view.push([nid, phi]);
          }
        }
        const payload = JSON.stringify({ t: 'phi_vector', from: _localNodeId, view });
        for (const [, peer] of _peerChannels) {
          if (peer && !peer.pending) { try { peer.send(payload); } catch (_) {} }
        }
        return { suspend: false, next: k(undefined) };
      }
      // v1.23 — cluster-wide FD: majority vote of phi >= threshold across peers.
      case 'clusterIsDown': {
        const target = String(args[0]);
        const thr    = args[1] == null ? 8.0 : Number(args[1]);
        let votes = 0;
        let total = 0;
        if (_peerChannels.has(target) && _peerChannels.get(target) && !_peerChannels.get(target).pending) {
          total += 1;
          if (_computePhi(target) >= thr) votes += 1;
        }
        for (const [peerNid, peerView] of _peerPhiViews) {
          if (peerNid === target) continue;
          const p = peerView.get(target);
          if (p != null) { total += 1; if (p >= thr) votes += 1; }
        }
        const majority = Math.floor((total + 1) / 2);
        return { suspend: false, next: k(total > 0 && votes >= majority) };
      }
      // v1.23 — leader election (Bully or Raft, picked by _leaderProtocol)
      case 'electLeader': {
        if (_leaderProtocol === 'raft') _startRaftElection();
        else _startElection();
        return { suspend: false, next: k(undefined) };
      }
      case 'currentLeader': {
        if (_leaderProtocol === 'raft') return { suspend: false, next: k(_raftLeaderId) };
        if (_leaderProtocol === 'coord') {
          let held = "";
          if (_coordHolderFn) {
            try {
              const opt = _coordHolderFn();
              // Option emits as { _type: 'Some', value } or { _type: 'None' }.
              if (opt && opt._type === 'Some') held = String(opt.value || "");
            } catch (_) {}
          }
          return { suspend: false, next: k(held) };
        }
        return { suspend: false, next: k(_currentLeader) };
      }
      case 'subscribeLeaderEvents': {
        _leaderEventSubs.add(id);
        return { suspend: false, next: k(undefined) };
      }
      case 'setAutoReelect': {
        _autoReelect = !!args[0];
        return { suspend: false, next: k(undefined) };
      }
      // v1.23 — protocol switch + history (cluster-raft.md §6)
      case 'useRaftLeaderElection': {
        _leaderProtocol = 'raft';
        _raftLoad();
        _raftStateName  = 'follower';
        _raftElectionDue = Date.now() + _raftRandTimeout();
        _ensureRaftTickThread();
        return { suspend: false, next: k(undefined) };
      }
      case 'useExternalCoordinator': {
        _leaderProtocol = 'coord';
        if (args.length >= 4) {
          _coordAcquireFn = typeof args[0] === 'function' ? args[0] : null;
          _coordRenewFn   = typeof args[1] === 'function' ? args[1] : null;
          _coordReleaseFn = typeof args[2] === 'function' ? args[2] : null;
          _coordHolderFn  = typeof args[3] === 'function' ? args[3] : null;
          if (_coordAcquireFn) {
            let got = false;
            try { got = !!_coordAcquireFn(_localNodeId, _COORD_LEASE_TIMEOUT_MS); } catch (_) {}
            if (got) {
              _coordIsLeader = true;
              const prev = _currentLeader;
              _currentLeader = _localNodeId;
              if (prev !== _localNodeId) {
                _fireLeaderEvent("LeaderElected", _localNodeId);
                _recordLeaderHist(_localNodeId);
              }
            }
            _ensureCoordTickThread();
          }
        }
        return { suspend: false, next: k(undefined) };
      }
      case 'leaderProtocol': {
        return { suspend: false, next: k(_leaderProtocol) };
      }
      case 'leaderHistory': {
        // Return a copy so callers can't mutate our buffer.
        return { suspend: false, next: k(_leaderHist.map(e => [e[0], e[1], e[2]])) };
      }
      // v1.23 — auto-reconnect policy
      case 'setReconnectPolicy': {
        const ini = Number(args[0]) | 0;
        const mx  = Number(args[1]) | 0;
        // v1.23 — optional 3rd arg: total wall-clock retry budget per
        // URL; 0 = no cap (retry forever).
        const giveUp = args.length > 2 ? (Number(args[2]) | 0) : 0;
        _reconnectInitialMs = Math.max(0, ini);
        _reconnectMaxMs     = Math.max(_reconnectInitialMs, mx);
        _reconnectGiveUpMs  = Math.max(0, giveUp);
        return { suspend: false, next: k(undefined) };
      }
      // v1.23 — heartbeat cadence + dead-after
      case 'setHeartbeatTimeout': {
        const iv   = Number(args[0]) | 0;
        const dead = Number(args[1]) | 0;
        _peerHeartbeatIntervalMs  = Math.max(1, iv);
        _peerHeartbeatDeadAfterMs = Math.max(_peerHeartbeatIntervalMs, dead);
        return { suspend: false, next: k(undefined) };
      }
      // v1.23 — quorum-aware Bully threshold
      case 'setQuorumSize': {
        const n = Number(args[0]) | 0;
        _quorumSize = Math.max(0, n);
        return { suspend: false, next: k(undefined) };
      }
      // v1.23 — shared-secret Bearer token for /_ssc-cluster/* endpoints.
      case 'setClusterAuthToken': {
        _clusterAuthToken = args[0] != null ? String(args[0]) : '';
        return { suspend: false, next: k(undefined) };
      }
      // v1.23 — periodic gossip re-discovery
      case 'requestGossip': {
        const payload = '{"t":"peers_req","from":' + JSON.stringify(_localNodeId) + '}';
        for (const [, ch] of _peerChannels) { try { ch.send(payload); } catch (_) {} }
        return { suspend: false, next: k(undefined) };
      }
      // v1.23 — cluster configuration distribution
      case 'clusterConfigSet': {
        const key   = String(args[0]);
        const value = String(args[1]);
        const ts    = Date.now();
        _applyConfigUpdate(key, value, ts, _localNodeId);
        const payload = JSON.stringify({ t: 'config_set', key, value, ts, origin: _localNodeId });
        for (const [, ch] of _peerChannels) { try { ch.send(payload); } catch (_) {} }
        return { suspend: false, next: k(undefined) };
      }
      case 'clusterConfigGet': {
        const entry = _clusterConfig.get(String(args[0]));
        const result = entry ? { _type: 'Some', value: entry.value } : { _type: 'None' };
        return { suspend: false, next: k(result) };
      }
      case 'clusterConfigKeys': {
        return { suspend: false, next: k(Array.from(_clusterConfig.keys())) };
      }
      case 'subscribeConfigEvents': {
        _configEventSubs.add(id);
        return { suspend: false, next: k(undefined) };
      }
      // v1.23 — drain / rolling-restart
      case 'setDraining': {
        const b = !!args[0];
        const prev = _isDrainingSelf;
        _isDrainingSelf = b;
        if (prev !== b) {
          const payload = JSON.stringify({ t: 'drain', from: _localNodeId, draining: b });
          for (const [, ch] of _peerChannels) { try { ch.send(payload); } catch (_) {} }
          _fireDrainEvent(_localNodeId, b);
          if (b) _stepDownIfLeader();
        }
        return { suspend: false, next: k(undefined) };
      }
      case 'isDraining': {
        return { suspend: false, next: k(_isDrainingSelf) };
      }
      case 'drainingPeers': {
        const buf = [];
        for (const [nid, dr] of _drainingPeers) if (dr) buf.push(nid);
        return { suspend: false, next: k(buf) };
      }
      case 'subscribeDrainEvents': {
        _drainEventSubs.add(id);
        return { suspend: false, next: k(undefined) };
      }
      // v1.23 — cluster metrics aggregation
      case 'clusterMetricSet': {
        const name = String(args[0]);
        const value = Number(args[1]);
        _applyMetricUpdate(name, _localNodeId, value);
        const payload = JSON.stringify({ t: 'metric', from: _localNodeId, name, value });
        for (const [, ch] of _peerChannels) { try { ch.send(payload); } catch (_) {} }
        return { suspend: false, next: k(undefined) };
      }
      case 'clusterMetricGet': {
        const inner = _clusterMetrics.get(String(args[0]));
        const m = new Map();
        if (inner) for (const [nid, v] of inner) m.set(nid, v);
        return { suspend: false, next: k(m) };
      }
      case 'clusterMetricSum': {
        const inner = _clusterMetrics.get(String(args[0]));
        let sum = 0;
        if (inner) for (const v of inner.values()) sum += v;
        return { suspend: false, next: k(sum) };
      }
      case 'clusterMetricNames': {
        return { suspend: false, next: k(Array.from(_clusterMetrics.keys())) };
      }
      case 'subscribeMetricEvents': {
        _metricEventSubs.add(id);
        return { suspend: false, next: k(undefined) };
      }
      // v1.6.x — scheduled sends
      case 'sendAfter': {
        const delayMs  = args[0];
        const tgtPid   = args[1];
        const tMsg     = args[2];
        const fireAt   = Date.now() + delayMs;
        const tRef     = _nextTimerId++;
        _timers.set(tRef, { fireAt, period: null, targetId: tgtPid.localId, msg: tMsg });
        return { suspend: false, next: k(tRef) };
      }
      case 'sendInterval': {
        const periodMs = args[0];
        const tgtPid2  = args[1];
        const tMsg2    = args[2];
        const fireAt2  = Date.now() + periodMs;
        const tRef2    = _nextTimerId++;
        _timers.set(tRef2, { fireAt: fireAt2, period: periodMs, targetId: tgtPid2.localId, msg: tMsg2 });
        return { suspend: false, next: k(tRef2) };
      }
      case 'cancelTimer': {
        _timers.delete(args[0]);
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
        const myPid = Pid(_localNodeId, id);
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

  // Lightweight yield to Node's event loop.  `setImmediate` runs after
  // pending I/O callbacks (HTTP request handlers, WS frames, accept'ed
  // connections) drain — exactly what we need so a `serveAsync(...)`
  // bound from inside the actor body becomes reachable while the
  // scheduler keeps spinning.  Falls back to a resolved Promise when
  // `setImmediate` is missing (non-Node hosts), which still yields one
  // microtask between iterations.
  const _yieldToIO = () => (typeof setImmediate === 'function')
    ? new Promise(r => setImmediate(r))
    : Promise.resolve();
  while (true) {
    // Drain peer ring buffers then node-down queue before each scheduler tick.
    if (_peerChannels.size > 0) {
      _drainPeerBuffers();
      _processRemoteInbox();
    }
    while (_nodeDownQueue.length > 0) _fireNodeDown(_nodeDownQueue.shift());
    // v1.23 — deliver cluster events to subscribers.
    while (_clusterEventQueue.length > 0) {
      const _ev = _clusterEventQueue.shift();
      const _msg = _ev.tag === 'NodeJoined'
        ? { _type: 'NodeJoined', nodeId: _ev.nodeId }
        : { _type: 'NodeLeft',   nodeId: _ev.nodeId, reason: _ev.reason };
      for (const _sid of _clusterEventSubs) {
        const _st = actors.get(_sid);
        if (_st) { _st.mailbox.push(_msg); tryWakeBlocked(_sid); }
      }
    }
    // v1.23 — Bully election timeout: claim self if no higher-id peer responded.
    if (_electionInProgress && Date.now() - _electionStartedAt >= _ELECTION_TIMEOUT_MS) {
      _electionInProgress = false;
      // v1.23 — quorum gate: same as `_startElection.higher.length === 0`
      // branch — even though no higher peer responded, decline self-
      // claim when below quorum.
      if (!_gotAliveResponse && _hasQuorum()) {
        const _prev = _currentLeader;
        _currentLeader = _localNodeId;
        _broadcastCoordinator();
        if (_prev !== _localNodeId) { _fireLeaderEvent("LeaderElected", _localNodeId); _recordLeaderHist(_localNodeId); }
      }
    }
    // v1.23 — deliver leader events to subscribers.
    while (_leaderEventQueue.length > 0) {
      const _lev = _leaderEventQueue.shift();
      const _lmsg = _lev.tag === 'LeaderElected'
        ? { _type: 'LeaderElected', nodeId: _lev.leaderId }
        : { _type: 'LeaderLost',    nodeId: _lev.leaderId };
      for (const _sid of _leaderEventSubs) {
        const _st = actors.get(_sid);
        if (_st) { _st.mailbox.push(_lmsg); tryWakeBlocked(_sid); }
      }
    }
    // v1.23 — deliver config-change events to subscribers.
    while (_configEventQueue.length > 0) {
      const _cev = _configEventQueue.shift();
      const _cmsg = { _type: 'ConfigChanged', key: _cev.key, value: _cev.value };
      for (const _sid of _configEventSubs) {
        const _st = actors.get(_sid);
        if (_st) { _st.mailbox.push(_cmsg); tryWakeBlocked(_sid); }
      }
    }
    // v1.23 — deliver drain-state events to subscribers.
    while (_drainEventQueue.length > 0) {
      const _dev = _drainEventQueue.shift();
      const _dmsg = { _type: 'DrainStateChanged', nodeId: _dev.nodeId, draining: _dev.draining };
      for (const _sid of _drainEventSubs) {
        const _st = actors.get(_sid);
        if (_st) { _st.mailbox.push(_dmsg); tryWakeBlocked(_sid); }
      }
    }
    // v1.23 — deliver metric events to subscribers.
    while (_metricEventQueue.length > 0) {
      const _mev = _metricEventQueue.shift();
      const _mmsg = { _type: 'MetricChanged', name: _mev.name, nodeId: _mev.nodeId, value: _mev.value };
      for (const _sid of _metricEventSubs) {
        const _st = actors.get(_sid);
        if (_st) { _st.mailbox.push(_mmsg); tryWakeBlocked(_sid); }
      }
    }
    // Fire scheduled sends whose deadline has passed.
    if (_timers.size > 0) {
      const _nowMs = Date.now();
      for (const [_tRef, _te] of Array.from(_timers)) {
        if (_nowMs >= _te.fireAt) {
          const _tSt = actors.get(_te.targetId);
          if (_tSt) { _tSt.mailbox.push(_te.msg); tryWakeBlocked(_te.targetId); }
          if (_te.period != null) {
            _timers.set(_tRef, { fireAt: _te.fireAt + _te.period, period: _te.period,
                                  targetId: _te.targetId, msg: _te.msg });
          } else {
            _timers.delete(_tRef);
          }
        }
      }
    }
    if (ready.length > 0) {
      const id = ready.shift();
      const state = actors.get(id);
      if (state && state.pending !== null) stepActor(id);
      // When distributed, give Node's event loop a tick between every
      // actor step — peer-channel I/O, accept'ed HTTP connections, and
      // remote-inbox writes from worker threads all flow through the
      // libuv queue, and a tight `ready.shift() → stepActor` loop would
      // otherwise starve them.  Pure-actor (non-distributed) workloads
      // skip this — the scheduler runs straight through and matches the
      // pre-async tick performance to within a few %.
      if (_peerChannels.size > 0) await _yieldToIO();
    } else {
      // Quiescence — but timeout-armed receives, timers, remote messages, or node-downs may fire.
      if (_nodeDownQueue.length > 0) continue;
      let earliest = null;
      for (const [aid, st] of actors) {
        if (st && st.blocked && st.blocked.deadline != null) {
          if (earliest === null || st.blocked.deadline < earliest.d)
            earliest = { id: aid, d: st.blocked.deadline };
        }
      }
      // Earliest pending timer deadline.
      let _timerMin = null;
      for (const [, _te] of _timers) {
        if (_timerMin === null || _te.fireAt < _timerMin) _timerMin = _te.fireAt;
      }
      // Distributed: keep running while peer channels are open and actors are blocked.
      const isDistributed = _peerChannels.size > 0;
      const hasBlockedActors = isDistributed && actors.size > 0 &&
        [...actors.values()].some(st => st && st.blocked !== null);
      if (!earliest && _timers.size === 0 && !hasBlockedActors &&
          !_electionInProgress && _leaderEventQueue.length === 0 &&
          _configEventQueue.length === 0 && _drainEventQueue.length === 0 &&
          _metricEventQueue.length === 0) break;
      const _sleepUntil = [earliest != null ? earliest.d : null, _timerMin].filter(v => v != null);
      const _minDeadline = _sleepUntil.length > 0 ? Math.min(..._sleepUntil) : null;
      const sleepFor = _minDeadline != null ? Math.min(_minDeadline - Date.now(), 10) : 10;
      // Promise-based sleep — `setTimeout(r, ms)` registers a libuv
      // timer and `await`-ing the Promise releases the main thread, so
      // Node's event loop can dispatch HTTP/WS callbacks, ring-buffer
      // writes from peer workers, and any other libuv-driven I/O.
      // Previously this was `_asyncSleep` (Atomics.wait on the main
      // thread), which Node ≥ 16 permits but blocks the event loop
      // entirely — every HTTP request `accept`ed by libuv queued and
      // never dispatched, making `/_ssc-cluster/*` endpoints
      // unreachable for the duration of the sleep.
      if (sleepFor > 0) await new Promise(r => setTimeout(r, sleepFor));
      else              await _yieldToIO();
      if (earliest != null) {
        const now = Date.now();
        const s = actors.get(earliest.id);
        if (s && s.blocked && s.blocked.deadline != null && now >= s.blocked.deadline) {
          const kk = s.blocked.k;
          s.blocked = null;
          s.pending = kk(_None);
          ready.push(earliest.id);
        }
      }
    }
  }
  // v1.23 — graceful cluster shutdown: release the coord lease if we
  // hold it, so the next leader can claim immediately.
  if (_leaderProtocol === 'coord' && _coordIsLeader && _coordReleaseFn) {
    try { _coordReleaseFn(_localNodeId); } catch (_) {}
    _coordIsLeader = false;
  }
  // Clear tick intervals so they don't leak across reused processes.
  if (_raftTickHandle  != null) { clearInterval(_raftTickHandle);  _raftTickHandle  = null; }
  if (_coordTickHandle != null) { clearInterval(_coordTickHandle); _coordTickHandle = null; }
  return rootResult;
}

// ── v1.10 Generator — pull-based lazy streams via JS native generators ────
// generator { () => ...; suspend(v); ... } lowers to
//   _makeGenerator(function*() { ...; yield v; ... })
// The returned object wraps the JS iterator with the ScalaScript API.
function _makeGenerator(genFn) {
  const iter = genFn();
  function _wrap(iter2) {
    return {
      next()    { const r = iter2.next(); return r.done ? null : { _isSome: true, _value: r.value }; },
      nextOpt() { const r = iter2.next(); return r.done ? _None : _Some(r.value); },
      foreach(f) { for (const v of { [Symbol.iterator]() { return iter2; } }) f(v); },
      toList()  { const a = []; for (const v of { [Symbol.iterator]() { return iter2; } }) a.push(v); return a; },
      map(f)    { return _wrap((function*(it) { for (const v of { [Symbol.iterator]() { return it; } }) yield f(v); })(iter2)); },
      filter(p) { return _wrap((function*(it) { for (const v of { [Symbol.iterator]() { return it; } }) if (p(v)) yield v; })(iter2)); },
      take(n)   { return _wrap((function*(it) { let i=0; for (const v of { [Symbol.iterator]() { return it; } }) { if(i++>=n) break; yield v; } })(iter2)); },
      drop(n)   { return _wrap((function*(it) { let i=0; for (const v of { [Symbol.iterator]() { return it; } }) { if(i++<n) continue; yield v; } })(iter2)); },
      flatMap(f){ return _wrap((function*(it) { for (const v of { [Symbol.iterator]() { return it; } }) { const inner = f(v); yield* { [Symbol.iterator]() { const i2 = inner._iter(); return { next() { const r = i2.next(); return r; } }; } }; } })(iter2)); },
      zip(other){ return _wrap((function*(it) { const oit = other._iter(); for (const v of { [Symbol.iterator]() { return it; } }) { const ob = oit.next(); if (ob.done) break; const t = [v, ob.value]; t._isTuple=true; yield t; } })(iter2)); },
      zipWithIndex(){ return _wrap((function*(it) { let i=0; for (const v of { [Symbol.iterator]() { return it; } }) { const t=[v,i++]; t._isTuple=true; yield t; } })(iter2)); },
      _iter()   { return iter2; },
    };
  }
  return _wrap(iter);
}

// ── v1.9 Coroutine primitive — JS native generators ────────────────────────
// coroutineCreate(fn) wraps a function* generator; coroutineResume steps it.
// suspend(v) inside a coroutineCreate body compiles to `yield v` (JsGen
// emits it via genGeneratorBody / genGenExpr, same path as generator bodies).
function Yielded(value)   { return { _type: 'Yielded',   value }; }
function Returned(value)  { return { _type: 'Returned',  value }; }
function Errored(message) { return { _type: 'Errored',  message }; }

function _coroutineCreate(genFn) {
  const gen = genFn();
  return { _type: '_Coroutine', _gen: gen, _done: false };
}

function _coroutineResume(co, input) {
  if (co._done) throw new Error('coroutineResume: coroutine already completed');
  let r;
  try { r = co._gen.next(input); }
  catch (e) { co._done = true; return Errored(e.message || String(e)); }
  if (r.done) { co._done = true; return Returned(r.value); }
  return Yielded(r.value);
}
// Runtime stub: suspend called outside a generator/coroutine body throws.
// genGenExpr rewrites suspend(v) → (yield v) inside function* bodies.
function suspend(v) { throw new Error('suspend called outside a coroutine or generator body'); }

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
// (_seq, _seqMap, _seqFlatMap, _seqFilter are defined in JsRuntimePart2 so
// they are available in the base runtime too.)
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

/** v1.4 built-in effects: Logger, Random, Clock, Env.
 *  Concatenated after `JsRuntimeAsync` wherever effects are available. */
val JsRuntimeV14Effects: String = """

// ── v1.4 Logger effect ──────────────────────────────────────────────────────
//
// Logger.{info,warn,error,debug}  → _perform("Logger", op, [msg])
// runLogger(bodyFn)               — "[LEVEL] msg" to process.stdout
// runLoggerJson(bodyFn)           — {"level":"…","msg":"…"} newline-JSON
// runLoggerToList(bodyFn)         — [result, [[level, msg], …]]

const Logger = {
  info:  (msg) => _perform('Logger', 'info',  [msg]),
  warn:  (msg) => _perform('Logger', 'warn',  [msg]),
  error: (msg) => _perform('Logger', 'error', [msg]),
  debug: (msg) => _perform('Logger', 'debug', [msg]),
};

function _loggerJsonStr(s) {
  return JSON.stringify(String(s));
}

function _loggerMakeHandlers(fmt) {
  function makeHandler(level) {
    return function(args) {
      const msg = args[0];
      if (fmt === 'json') {
        process.stdout.write('{"level":"' + level + '","msg":' + _loggerJsonStr(msg) + '}\n');
      } else {
        process.stdout.write('[' + level.toUpperCase() + '] ' + msg + '\n');
      }
      return args[args.length - 1](undefined);
    };
  }
  return {
    'Logger.info':  makeHandler('info'),
    'Logger.warn':  makeHandler('warn'),
    'Logger.error': makeHandler('error'),
    'Logger.debug': makeHandler('debug'),
  };
}

function runLogger(bodyFn) {
  const handled = new Set(['Logger.info', 'Logger.warn', 'Logger.error', 'Logger.debug']);
  return _handle(bodyFn, handled, _loggerMakeHandlers('text'));
}

function runLoggerJson(bodyFn) {
  const handled = new Set(['Logger.info', 'Logger.warn', 'Logger.error', 'Logger.debug']);
  return _handle(bodyFn, handled, _loggerMakeHandlers('json'));
}

function runLoggerToList(bodyFn) {
  const log = [];
  const handlers = {};
  for (const level of ['info', 'warn', 'error', 'debug']) {
    handlers['Logger.' + level] = function(args) {
      log.push([level, String(args[0])]);
      return args[args.length - 1](undefined);
    };
  }
  const handled = new Set(Object.keys(handlers));
  const result = _handle(bodyFn, handled, handlers);
  return [result, log];
}

// ── v1.4 Random effect ──────────────────────────────────────────────────────
//
// Random.{nextInt,nextDouble,uuid,pick}  → _perform("Random", op, args)
// runRandom(bodyFn)           — Math.random()-based (non-deterministic)
// runRandomSeeded(seed)(body) — seeded LCG for deterministic output

const Random = {
  nextInt:    (n)  => _perform('Random', 'nextInt',    [n]),
  nextDouble: ()   => _perform('Random', 'nextDouble', []),
  uuid:       ()   => _perform('Random', 'uuid',       []),
  pick:       (xs) => _perform('Random', 'pick',       [xs]),
};

function _randomHandlers(rng) {
  return {
    'Random.nextInt': function(args) {
      const n = args[0] >>> 0;
      return args[args.length - 1](n > 0 ? (rng() * n) | 0 : 0);
    },
    'Random.nextDouble': function(args) {
      return args[args.length - 1](rng());
    },
    'Random.uuid': function(args) {
      const b = new Array(16);
      for (let i = 0; i < 16; i++) b[i] = (rng() * 256) | 0;
      b[6] = (b[6] & 0x0f) | 0x40;
      b[8] = (b[8] & 0x3f) | 0x80;
      const hex = (x) => x.toString(16).padStart(2, '0');
      const u = b.map(hex).join('');
      const uuid = u.slice(0,8)+'-'+u.slice(8,12)+'-'+u.slice(12,16)+'-'+u.slice(16,20)+'-'+u.slice(20);
      return args[args.length - 1](uuid);
    },
    'Random.pick': function(args) {
      const xs = args[0];
      return args[args.length - 1](xs[(rng() * xs.length) | 0]);
    },
  };
}

function _lcgRng(seed) {
  let s = seed >>> 0;
  return function() {
    s = (Math.imul(1664525, s) + 1013904223) >>> 0;
    return s / 4294967296;
  };
}

function runRandom(bodyFn) {
  const ops = new Set(['Random.nextInt', 'Random.nextDouble', 'Random.uuid', 'Random.pick']);
  return _handle(bodyFn, ops, _randomHandlers(Math.random));
}

function runRandomSeeded(seed) {
  return function(bodyFn) {
    const ops = new Set(['Random.nextInt', 'Random.nextDouble', 'Random.uuid', 'Random.pick']);
    return _handle(bodyFn, ops, _randomHandlers(_lcgRng(seed)));
  };
}

// ── v1.4 Clock effect ───────────────────────────────────────────────────────
//
// Clock.{now,nowIso,sleep}  → _perform("Clock", op, args)
// runClock(bodyFn)          — real wall clock; sleep → Atomics.wait spin
// runClockAt(t0)(bodyFn)    — frozen at t0 ms since epoch; sleep is no-op

const Clock = {
  now:    ()   => _perform('Clock', 'now',    []),
  nowIso: ()   => _perform('Clock', 'nowIso', []),
  sleep:  (ms) => _perform('Clock', 'sleep',  [ms]),
};

function _clockHandlers(frozen) {
  function nowMs()  { return frozen !== null ? frozen : Date.now(); }
  function nowIso() { return new Date(nowMs()).toISOString(); }
  return {
    'Clock.now':    function(args) { return args[args.length - 1](nowMs()); },
    'Clock.nowIso': function(args) { return args[args.length - 1](nowIso()); },
    'Clock.sleep':  function(args) {
      const ms = args[0];
      if (frozen === null && ms > 0) { _asyncSleep(ms); }
      return args[args.length - 1](undefined);
    },
  };
}

function runClock(bodyFn) {
  const ops = new Set(['Clock.now', 'Clock.nowIso', 'Clock.sleep']);
  return _handle(bodyFn, ops, _clockHandlers(null));
}

function runClockAt(t0) {
  return function(bodyFn) {
    const ops = new Set(['Clock.now', 'Clock.nowIso', 'Clock.sleep']);
    return _handle(bodyFn, ops, _clockHandlers(t0));
  };
}

// ── v1.4 Env effect ─────────────────────────────────────────────────────────
//
// Env.{get,set,required}  → _perform("Env", op, args)
// runEnv(bodyFn)          — reads process.env; Env.set mutates local overlay
// runEnvWith(map)(bodyFn) — fixture map; Env.set mutates overlay

const Env = {
  get:      (key)        => _perform('Env', 'get',      [key]),
  set:      (key, value) => _perform('Env', 'set',      [key, value]),
  required: (key)        => _perform('Env', 'required', [key]),
};

function _envHandlers(overlay, useReal) {
  function lookup(k) {
    if (k in overlay) return overlay[k];
    if (useReal && typeof process !== 'undefined' && process.env) {
      const v = process.env[k];
      return v !== undefined && v !== '' ? v : undefined;
    }
    return undefined;
  }
  return {
    'Env.get': function(args) {
      const v = lookup(String(args[0]));
      return args[args.length - 1](v !== undefined ? v : null);
    },
    'Env.set': function(args) {
      overlay[String(args[0])] = String(args[1]);
      return args[args.length - 1](undefined);
    },
    'Env.required': function(args) {
      const k = String(args[0]);
      const v = lookup(k);
      if (v === undefined) throw new Error("Env.required: key '" + k + "' not found in environment");
      return args[args.length - 1](v);
    },
  };
}

function runEnv(bodyFn) {
  const ops = new Set(['Env.get', 'Env.set', 'Env.required']);
  return _handle(bodyFn, ops, _envHandlers({}, true));
}

function runEnvWith(initMap) {
  return function(bodyFn) {
    const ops = new Set(['Env.get', 'Env.set', 'Env.required']);
    const overlay = {};
    if (initMap instanceof Map) {
      for (const [k, v] of initMap) overlay[k] = v;
    } else if (initMap && typeof initMap === 'object') {
      Object.assign(overlay, initMap);
    }
    return _handle(bodyFn, ops, _envHandlers(overlay, false));
  };
}

// ── v1.4 Http effect ────────────────────────────────────────────────────────
//
// Http.{get,post,request}  → _perform("Http", op, args)
// runHttp(bodyFn)                — delegates to real _httpSyncFetchWithRetry
// runHttpStub(routes)(bodyFn)    — test stub: returns {status:200,…} for known urls

const Http = {
  get:     (url)                         => _perform('Http', 'get',     [url]),
  post:    (url, body)                   => _perform('Http', 'post',    [url, body]),
  request: (method, url, headers, body)  => _perform('Http', 'request', [method, url, headers, body]),
};

function _httpEffectHandlers(routes) {
  function stubResponse(url) {
    if (routes instanceof Map && routes.has(url)) {
      return { status: 200, headers: new Map(), body: String(routes.get(url)) };
    }
    return { status: 404, headers: new Map(), body: '' };
  }
  return {
    'Http.get': function(args) {
      const url = args[0];
      const resp = routes ? stubResponse(url) : _httpSyncFetchWithRetry('GET', url, null, {});
      return args[args.length - 1](resp);
    },
    'Http.post': function(args) {
      const url = args[0]; const body = args[1];
      const resp = routes ? stubResponse(url) : _httpSyncFetchWithRetry('POST', url, body, {});
      return args[args.length - 1](resp);
    },
    'Http.request': function(args) {
      const method = args[0]; const url = args[1];
      const headers = args[2] instanceof Map ? Object.fromEntries(args[2].entries()) : (args[2] || {});
      const body = args[3] != null ? String(args[3]) : null;
      const resp = routes ? stubResponse(url) : _httpSyncFetchWithRetry(method, url, body, headers);
      return args[args.length - 1](resp);
    },
  };
}

function runHttp(bodyFn) {
  const ops = new Set(['Http.get', 'Http.post', 'Http.request']);
  return _handle(bodyFn, ops, _httpEffectHandlers(null));
}

function runHttpStub(routes) {
  return function(bodyFn) {
    const ops = new Set(['Http.get', 'Http.post', 'Http.request']);
    return _handle(bodyFn, ops, _httpEffectHandlers(routes));
  };
}

// ── v1.4 Retry effect ───────────────────────────────────────────────────────
//
// Retry.attempt(n, delayMs)(thunk)  → _perform("Retry", "attempt", [n, delayMs, thunk])
// runRetry(bodyFn)         — real sleep between attempts (_asyncSleep)
// runRetryNoSleep(bodyFn)  — no sleep (test mode)

const Retry = {
  attempt: (n, delayMs) => function(thunk) {
    return _perform('Retry', 'attempt', [n, delayMs, thunk]);
  },
};

function _retryHandlers(doSleep) {
  return {
    'Retry.attempt': function(args) {
      const n = args[0]; const delayMs = args[1]; const thunk = args[2];
      const resume = args[args.length - 1];
      let lastErr = null;
      for (let attempt = 0; attempt <= n; attempt++) {
        try {
          const result = thunk();
          return resume(result);
        } catch(e) {
          lastErr = e;
          if (attempt < n && doSleep && delayMs > 0) _asyncSleep(delayMs);
        }
      }
      throw lastErr;
    },
  };
}

function runRetry(bodyFn) {
  const ops = new Set(['Retry.attempt']);
  return _handle(bodyFn, ops, _retryHandlers(true));
}

function runRetryNoSleep(bodyFn) {
  const ops = new Set(['Retry.attempt']);
  return _handle(bodyFn, ops, _retryHandlers(false));
}

// ── v1.4 Cache effect ───────────────────────────────────────────────────────
//
// Cache.memoize(key, ttlSeconds)(thunk)  → _perform("Cache", "memoize", args)
// runCache(bodyFn)        — uses module-level _cacheStore
// runCacheBypass(bodyFn)  — always recomputes; skips cache

const _cacheStore = new Map();
let _cacheBypass = false;

const Cache = {
  memoize: (key, ttlSeconds) => function(thunk) {
    return _perform('Cache', 'memoize', [key, ttlSeconds, thunk]);
  },
};

function _cacheHandlers(bypass) {
  return {
    'Cache.memoize': function(args) {
      const key = String(args[0]); const ttlMs = Number(args[1]) * 1000;
      const thunk = args[2]; const resume = args[args.length - 1];
      if (bypass) return resume(thunk());
      const nowMs = Date.now();
      const entry = _cacheStore.get(key);
      if (entry && nowMs < entry[0]) return resume(entry[1]);
      const v = thunk();
      _cacheStore.set(key, [nowMs + ttlMs, v]);
      return resume(v);
    },
  };
}

function runCache(bodyFn) {
  const ops = new Set(['Cache.memoize']);
  return _handle(bodyFn, ops, _cacheHandlers(false));
}

function runCacheBypass(bodyFn) {
  const ops = new Set(['Cache.memoize']);
  return _handle(bodyFn, ops, _cacheHandlers(true));
}

// ── v1.4 State effect ───────────────────────────────────────────────────────
//
// State.get              → _perform("State", "get",    [])
// State.set(s)           → _perform("State", "set",    [s])
// State.modify(f)        → _perform("State", "modify", [f])
// runState(s0)(bodyFn)   — returns [finalState, result]

const State = {
  get:    ()  => _perform('State', 'get',    []),
  set:    (s) => _perform('State', 'set',    [s]),
  modify: (f) => _perform('State', 'modify', [f]),
};

function runState(s0) {
  return function(bodyFn) {
    let state = s0;
    const handlers = {
      'State.get': function(args) {
        return args[args.length - 1](state);
      },
      'State.set': function(args) {
        state = args[0];
        return args[args.length - 1](undefined);
      },
      'State.modify': function(args) {
        state = args[0](state);
        return args[args.length - 1](undefined);
      },
    };
    const ops = new Set(['State.get', 'State.set', 'State.modify']);
    const result = _handle(bodyFn, ops, handlers);
    return [state, result];
  };
}

// ── v1.4 Tx effect ──────────────────────────────────────────────────────────
//
// Tx.atomic(thunk)  — signals transactional scope; default is no-op
// runTx(bodyFn)     — default no-op handler (just runs body directly)

const Tx = {
  atomic: (thunk) => thunk(),
};

function runTx(bodyFn) {
  return bodyFn();
}

// ── v1.4 Auth effect ────────────────────────────────────────────────────────
//
// Auth.currentUser  — returns current user or null (Option-like)
// Auth.require      — returns current user or throws
// runAuthWith(user)(bodyFn)  — injects a fixed user

let _authUser = null;

const Auth = {
  currentUser: () => _authUser,
  require:     () => {
    if (_authUser === null) throw new Error('Auth.require: no authenticated user in context');
    return _authUser;
  },
};

function runAuthWith(user) {
  return function(bodyFn) {
    const prior = _authUser;
    _authUser = user;
    try { return bodyFn(); }
    finally { _authUser = prior; }
  };
}
"""

/** Browser-SPA overlay loaded AFTER `JsRuntime` so its `serve(...)` /
 *  `console.log`-based output flush replace the Node-target versions.
 *  The Node-only helpers (`_serveStatic`, `_contentTypeFor`, `require('http')`)
 *  are never invoked, so they sit as dead code without crashing the page. */
val JsRuntimeBrowserPatch: String = """
// ── Browser / Electron SPA overlay ───────────────────────────────────────
// Replaces serve(view, port) with a route-register + popstate dispatcher.
// _ssc_http_serve is stubbed so _ssc_ui_serve can register routes without
// trying to require('http') in the renderer process.
// Same route(method, path)(handler) surface as the Node target; same
// Response shape; same _routes / _matchPath / _mkRequest reused unchanged.

_ssc_http_serve = function() {}   // no-op: no TCP server in the browser/Electron renderer

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
  const response = _spaRouteResponse(method, pathname, body);
  if (!response) {
    document.body.textContent = 'Not Found: ' + pathname;
    _spaFlush();
    return false;
  }
  _spaRender(response);
  return true;
}

function _spaRouteResponse(method, pathname, body) {
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
    try { return r.handler(request); }
    catch (e) {
      return Response.status(500, 'SPA route error: ' + (e && e.message ? e.message : e));
    }
  }
  return null;
}

function _spaNavigate(pathname, replace) {
  if (replace) history.replaceState({}, '', pathname);
  else         history.pushState({}, '', pathname);
  _spaDispatch('GET', pathname);
}

function _spaFetchResponse(resp) {
  const status = resp && resp.status ? resp.status : 200;
  const headers = resp && resp.headers instanceof Map ? resp.headers : new Map();
  const body = resp && resp.body != null ? String(resp.body) : '';
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: {
      get(name) {
        const wanted = String(name).toLowerCase();
        for (const [k, v] of headers) if (String(k).toLowerCase() === wanted) return String(v);
        return null;
      }
    },
    text() { return Promise.resolve(body); },
    json() { return Promise.resolve(JSON.parse(body)); }
  };
}

function _spaFetchPath(input) {
  if (typeof input === 'string') {
    if (input.startsWith('/') && !input.startsWith('//')) return input;
    try {
      const u = new URL(input, location.href);
      if (u.origin === location.origin && u.pathname) return u.pathname + u.search;
    } catch (_) {}
  } else if (input && typeof input.url === 'string') {
    return _spaFetchPath(input.url);
  }
  return null;
}

const _ssc_native_fetch = globalThis.fetch ? globalThis.fetch.bind(globalThis) : null;
globalThis.fetch = function(input, init) {
  const rawPath = _spaFetchPath(input);
  if (!rawPath) {
    if (_ssc_native_fetch) return _ssc_native_fetch(input, init);
    return Promise.reject(new Error('fetch is not available'));
  }
  if (globalThis.__sscBackendBaseUrl && _ssc_native_fetch) {
    const target = new URL(rawPath, String(globalThis.__sscBackendBaseUrl)).toString();
    return _ssc_native_fetch(target, init);
  }
  const method = String((init && init.method) || (input && input.method) || 'GET').toUpperCase();
  const pathOnly = rawPath.split('?')[0] || '/';
  const rawBody = init && init.body != null ? init.body : '';
  return Promise.resolve(rawBody)
    .then(body => {
      const response = _spaRouteResponse(method, pathOnly, body == null ? '' : String(body));
      if (response) return _spaFetchResponse(response);
      if (_ssc_native_fetch) return _ssc_native_fetch(input, init);
      return _spaFetchResponse(Response.notFound('Not Found: ' + pathOnly));
    });
};

// In browser/Electron there's no port to bind.
// Overrides _ssc_ui_serve so that std.ui.primitives.serve = _ssc_ui_serve
// dispatches here.  No eval(), no DOM <script> injection — both blocked by
// script-src 'self' CSP (Electron default).
_ssc_ui_serve = function(treeOrPort, portOrUndef, extraCssOrUndef) {
  if (typeof treeOrPort !== 'number') {
    const extraCss = extraCssOrUndef || '';
    const { body, sigs } = _ssc_ui_renderBody(treeOrPort);
    const style = document.createElement('style');
    style.textContent = [
      '*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}',
      'body{margin:0;padding:0;background:#fff;-webkit-text-size-adjust:100%}',
      '.ssc-page{max-width:700px;margin:0 auto;padding:24px 20px;font-size:16px;',
      'font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",system-ui,sans-serif}',
      'input[type=checkbox]{width:22px;height:22px;accent-color:#2563eb;cursor:pointer;flex-shrink:0}',
      'button{touch-action:manipulation;cursor:pointer}',
      'button:disabled{opacity:.5;cursor:default}',
      '[data-ssc-cond]{display:contents}',
      'hr{border:none;border-top:1px solid #e5e7eb;margin:0}',
      '[data-ssc-fetch-table] table,[data-ssc-fetch-table] th,',
      '[data-ssc-fetch-table] td,[data-ssc-fetch-table] button{font-size:inherit;font-family:inherit}',
      '@keyframes spin{from{transform:rotate(0deg)}to{transform:rotate(360deg)}}',
      '.ssc-spin{animation:spin 0.8s linear infinite}',
      extraCss
    ].join('');
    document.head.appendChild(style);
    const app = document.getElementById('app') || document.body;
    const wrapper = document.createElement('div');
    wrapper.className = 'ssc-page';
    wrapper.innerHTML = body;
    app.appendChild(wrapper);
    _ssc_ui_mount(sigs);
    return;
  }
  // Port-only serve: SPA router for apps that register routes via get()/post()
  document.addEventListener('click', e => {
    const a = e.target && e.target.closest && e.target.closest('a');
    if (!a) return;
    const href = a.getAttribute('href');
    if (!href) return;
    if (/^(https?:)?\/\//.test(href) || href.startsWith('#') || href.startsWith('mailto:')) return;
    e.preventDefault();
    _spaNavigate(href, false);
  });
  window.addEventListener('popstate', () => {
    _spaDispatch('GET', location.pathname || '/');
  });
  _spaDispatch('GET', '/');
}
"""

class JsGen(
    baseDir:    Option[os.Path] = None,
    intrinsics: Map[scalascript.ir.QualifiedName, scalascript.backend.spi.IntrinsicImpl] = Map.empty,
    lockPath:   Option[os.Path] = None,
    // Shared across parent + all child generators to track which top-level `const X` names
    // have been declared.  When two package-qualified imports share the same top-level namespace
    // (e.g. std.ui.primitives and std.ui.nodes both wrap content in `object std { ... }`), the
    // second occurrence merges via _ssc_mergeDeep instead of re-declaring the const.
    private[codegen] val topLevelConsts: mutable.Set[String] = mutable.Set.empty,
    private[codegen] val mergeHelperEmitted: Array[Boolean]  = Array(false),
    // Shared across parent + all child generators to track which import-binding `const` names
    // have been declared.  A module imported transitively (e.g. nodes.ssc pulled in by both
    // primitives.ssc and layout.ssc) must not emit duplicate `const TkNode = …` lines.
    private[codegen] val declaredBindings: mutable.Set[String] = mutable.Set.empty):
  import scala.meta.*

  private[codegen] val sb = StringBuilder()
  private var indent = 0
  private var tmpIdx = 0
  private var hasMain = false
  private var mainCalled = false
  // Active parameter renames for JS reserved-word param names (e.g. `default` → `default_p`).
  private val paramRenames = mutable.Map.empty[String, String]
  // Set when the module uses runAsyncParallel; causes the user code sections to
  // be wrapped in a top-level async IIFE so `await _runAsyncParallel(...)` works.
  private var usesRunAsyncParallel: Boolean = false
  // Set when the module uses runActors; same async-IIFE wrap so
  // `await _runActors(...)` works.  The async-aware `_runActors`
  // yields to Node's event loop between scheduler ticks (see the
  // runtime emission of `_runActors` in `JsRuntimeAsync`) so that
  // libuv-accepted HTTP requests still drain while actors are
  // long-blocked on a `receive` deadline.  Detected by
  // `scanForRunActors` and combined into the `needsAsync` decision
  // alongside `usesRunAsyncParallel` and `needSqlPreamble`.
  private var usesRunActors: Boolean = false
  // Set when client/browser code uses `awaitClient(promise)`.  This helper
  // lowers directly to JS `await` and therefore needs the top-level async IIFE.
  private var usesAwaitClient: Boolean = false
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
  // Maps summon key "TC_A" → local param name "A$TC" for context-bound params
  // of the current Defn.Def being emitted. Cleared before each function.
  private val cbSummonMap = scala.collection.mutable.Map.empty[String, String]
  // funcParamOrder: function name → ordered parameter names.
  // Populated by collectFuncParamOrders before the main emit pass.
  // Used at call sites with named args to reorder them to positional order.
  private val funcParamOrder = scala.collection.mutable.Map.empty[String, List[String]]
  // v1.27 Phase 3 — sql block emission state.  Mirrors JvmGen's
  // `sqlBlockCounter` / `sqlPerSection`: sequential `_sqlBlock_<n>`
  // names, and per-section "first-only" tracking so only the first
  // `sql` block in each section gets the friendly `<sectionId>.sql`
  // alias.  Cleared at the start of every `genModule` call.
  private var sqlBlockCounter: Int = 0
  private val sqlPerSection = scala.collection.mutable.Map.empty[String, Int]

  private def freshTmp(): String =
    tmpIdx += 1
    s"_t$tmpIdx"

  private def line(s: String): Unit =
    sb.append("  " * indent).append(s).append("\n")

  // ─── Named-arg param-order collection ────────────────────────────
  //
  // Pre-pass over all Defn.Def nodes in the module to record the ordered
  // parameter list for each user-defined function.  At call sites that
  // pass named args (Term.Assign), genApply consults funcParamOrder to
  // reorder the arguments into the declared positional order before
  // emitting a regular positional JS call.

  private def collectFuncParamOrders(module: Module): Unit =
    funcParamOrder.clear()
    def collectDefs(stats: List[Stat]): Unit = stats.foreach {
      case d: Defn.Def =>
        val params = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).map(_.name.value).toList
        if params.nonEmpty then funcParamOrder(d.name.value) = params
      case _ => ()
    }
    def scanSection(section: Section): Unit =
      section.content.foreach {
        case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
          cb.tree.foreach { node =>
            ScalaNode.fold(node) {
              case Source(stats)     => collectDefs(stats)
              case Term.Block(stats) => collectDefs(stats)
              case _                 => ()
            }
          }
        case _ => ()
      }
      section.subsections.foreach(scanSection)
    module.sections.foreach(scanSection)

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
    collectFuncParamOrders(module)
    analyzeMutualRecursion(module)
    analyzeEffects(module)
    scanForRunAsyncParallel(module)
    scanForRunActors(module)
    scanForAwaitClient(module)
    // v1.27 Phase 3 — sql state.  Reset every module to keep `genModule`
    // re-entrant; emit preamble once when at least one sql block is
    // present (URL-prefix providers + ConnectionRegistry init + resolver).
    sqlBlockCounter = 0
    sqlPerSection.clear()
    val needSqlPreamble = hasSqlBlocks(module)
    if needSqlPreamble then emitSqlPreamble(module)
    // Front-matter route declarations are emitted BEFORE the user blocks so
    // a typical user-side `serve(port)` (last statement of the script) sees
    // them already registered.  JS function declarations are hoisted, so
    // forward references to the handler defs resolve at call time.
    emitFrontmatterRoutes(module)
    emitI18nTable(module)
    emitHttpTypedRouteClients(module.manifest.toList.flatMap(_.apiClients))
    // Async wrap rationale (v1.27): sql blocks compile to `await
    // SqlRuntimeJs.execute(...)`, which is only legal at top level in
    // ESM with top-level-await support.  An async IIFE is the
    // universally-portable wrapper that also keeps the legacy
    // classic-script target working.  Same wrapper as the existing
    // `runAsyncParallel` path; the three flags collapse into one
    // `needsAsync` decision.  `runActors` joins this set because its
    // scheduler is `async` — it `await`s `setImmediate`/`setTimeout`
    // between ticks so Node's event loop drains its I/O queue while
    // any actor is blocked on a long-armed `receive` (otherwise an
    // HTTP server bound via `serveAsync(...)` from inside the same
    // `runActors { ... }` body would be unreachable).
    val needsAsync = usesRunAsyncParallel || usesRunActors || usesAwaitClient || needSqlPreamble
    if needsAsync then
      line("(async () => {")
      // Write-through `_println` for code that runs *inside* the
      // async IIFE.  Without this, every `_println` call after the
      // first `await` pushes to `_output` but the outer
      // `process.stdout.write(_output...)` (appended by
      // `Main.scala`'s emit-js / link pipeline) ran synchronously
      // *before* the IIFE's microtasks fire — so async-scheduled
      // prints (actor body output, post-await SQL prints, etc.) were
      // silently dropped.  Override `Console.println` / `Console.print`
      // too — Normalize rewrites bare `println(...)` to
      // `Console.println(...)`, and the runtime's `Console` object
      // captures `_println` by reference at init time, so
      // reassigning `_println` alone leaves `Console.println`
      // pointing at the *buffered* original.  The overrides do NOT
      // push to `_output` (the buffer is only useful for the browser
      // SPA overlay, which doesn't share the Node code path), so the
      // outer segment-end flush has nothing to re-emit and we avoid
      // the duplicate-print failure mode.  `NodeBackend` re-overrides
      // these with an equivalent body — the duplicate-assign is
      // intentional and harmless.
      line("if (typeof process !== 'undefined' && process.stdout && typeof _output !== 'undefined') {")
      line("  _println = function(s) { process.stdout.write(String(s) + '\\n'); };")
      line("  _print   = function(s) { process.stdout.write(String(s));        };")
      line("  if (typeof Console !== 'undefined') {")
      line("    Console.println = _println;")
      line("    Console.print   = _print;")
      line("  }")
      line("}")
    // Pre-connect all databases declared in frontmatter so that the
    // synchronous Db facade (defined in emitSqlPreamble) can serve
    // route-handler Db.query / Db.execute calls without awaiting.
    if needSqlPreamble then
      val dbNames = module.manifest.toList.flatMap(_.databases).map(_.name)
      for dbName <- dbNames do
        line(s"Db._conns[${jsQuote(dbName)}] = await _ssc_sql_resolve(${jsQuote(dbName)});")
    module.sections.foreach(genSection)
    // Auto-call main() if defined and not already called
    if hasMain && !mainCalled then
      line("if (typeof main === 'function') { main(); }")
    if needsAsync then
      line("})().catch(e => {")
      line("  const msg = String(e && e.stack ? e.stack : e);")
      line("  if (typeof process !== 'undefined' && process.stderr) { process.stderr.write(msg + '\\n'); process.exit(1); }")
      line("  else if (typeof document !== 'undefined') { document.body.textContent = msg; }")
      line("  else { console.error(msg); }")
      line("});")
    sb.toString

  /** Emit the v1.27 sql-block preamble: hand-written `sql-runtime.mjs`
   *  source from `backend-sql-runtime-js`, plus the per-module
   *  `_ssc_sql_registry` + `_ssc_sql_resolve(dbName)` dispatcher.
   *
   *  The runtime source is read once (lazy val) from the classpath
   *  resource shipped by `backend-sql-runtime-js`.  Calls into
   *  `ConnectionRegistry`, `execute`, etc. resolve inside that source —
   *  no `import` statements are required in user-emitted code.
   *
   *  Note on async: the runtime is itself ES module syntax (`export
   *  class …`) when run via `node --test`-style import, but JsGen
   *  embeds it textually into a classic script context.  Strip the
   *  `export` keyword so the names land at the function/class top of
   *  the IIFE scope. */
  private def emitSqlPreamble(module: Module): Unit =
    val databases = module.manifest.toList.flatMap(_.databases)
    val entries   = databases.map { d =>
      scalascript.sql.js.SqlRuntimeJsEmit.DatabaseEntry(
        name     = d.name,
        url      = d.url,
        user     = d.user,
        password = d.password,
        driver   = d.driver,
      )
    }
    val runtimeSrc = scalascript.sql.js.SqlRuntimeJsEmit.runtimeSource
    // Strip ESM `export` keywords so the names land at script-level
    // scope (works for both ESM and classic-script consumers).  The
    // SqlRuntimeJs namespace below re-exports the public surface for
    // `genSqlBlock`-emitted call sites.
    val stripped   = runtimeSrc.replace("export ", "")
    sb.append(stripped)
    sb.append("\nconst SqlRuntimeJs = { execute, ConnectionRegistry, makeRow, isResultSetProducer, Providers, SqlJsProvider, SqliteWasmProvider, DuckDbWasmProvider };\n")
    sb.append(scalascript.sql.js.SqlRuntimeJsEmit.emitRegistryInit(entries))
    // Synchronous Db facade.  Connections are pre-initialized inside the
    // async IIFE (see genModule) and stored in Db._conns so that route
    // handlers can call Db.query / Db.execute synchronously.  sql.js is
    // itself synchronous once connected; only the initial load is async.
    sb.append("""const Db = {
  _conns: {},
  query(dbName, sql, params) {
    const conn = Db._conns[dbName || 'default'];
    const p = Array.isArray(params) ? params : (params ? [...params] : []);
    if (conn && conn._sscElectronBridge) return conn.querySync(sql, p);
    if (!conn || !conn._db) throw new Error('Db: no connection for "' + (dbName || 'default') + '"');
    const stmt = conn._db.prepare(sql);
    try {
      stmt.bind(p.map(toSqlJsBind));
      if (isResultSetProducer(sql)) {
        const rows = [];
        let columns = null;
        while (stmt.step()) {
          if (columns === null) columns = stmt.getColumnNames();
          rows.push(makeRow(columns, stmt.get().map(fromSqlJsValue)));
        }
        return rows;
      }
      while (stmt.step()) {}
      return conn._db.getRowsModified();
    } finally {
      stmt.free();
    }
  },
  execute(dbName, sql, params) {
    const conn = Db._conns[dbName || 'default'];
    const p = Array.isArray(params) ? params : (params ? [...params] : []);
    if (conn && conn._sscElectronBridge) return conn.executeSync(sql, p);
    return Db.query(dbName, sql, p);
  }
};
""")
    sb.append("\n")

  // ─── v2.0 Phase 2 — capability detection ─────────────────────────────
  //
  // The split-runtime emit uses this to decide which optional preamble
  // blocks (`JsRuntimeAsync`, `JsRuntimeV14Effects`, `JsRuntimeMcp`,
  // `JsRuntimeDataset`) to include in the shared `_runtime.scjs-runtime`
  // artifact.  Heuristic: scan the module's scalascript blocks for
  // textual references to the corresponding effect/DSL namespaces.
  // Conservative — when the heuristic is unsure we include the block;
  // a missing-block false negative would surface as a `ReferenceError`
  // at runtime, far worse than the few KB of extra runtime size.
  def detectCapabilities(module: Module): Set[JsGen.Capability] =
    import JsGen.Capability.*
    moduleDeps = module.manifest.map(_.dependencies).getOrElse(Map.empty)
    // Run the existing effect analysis to populate `effectOps` /
    // `effectfulFuns`.  `analyzeEffects` seeds `effectOps` with the
    // built-in `Async.*` / `Actor.*` / `Logger.*` / … names so the
    // CPS-emit pipeline recognises them, so we can't rely on the size
    // of that set — only on whether a user-declared `effect E:` block
    // appeared (those names won't be in the builtin seed).
    analyzeEffects(module)
    val caps = scala.collection.mutable.Set.empty[JsGen.Capability]
    caps += Core
    // Collect all parsed scalascript block sources (textual) so we can
    // grep for capability markers without rebuilding the AST traversal.
    def collectSources(s: Section): List[String] =
      s.content.collect {
        case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) => cb.source
      } ++ s.subsections.flatMap(collectSources)
    val sources = module.sections.flatMap(collectSources)
    val allText = sources.mkString("\n")
    // True iff the user declared their own `effect E:` block in this
    // module — `EffectAnalysis.analyze(builtins=…)` seeds `effectOps`
    // with the builtin names, so size alone is not a signal.  We use a
    // textual marker: an `effect ` keyword at the start of a line (the
    // language's effect-declaration syntax).
    val userEffectDecl = sources.exists { s =>
      s.linesIterator.exists(l => l.stripLeading.startsWith("effect "))
    }
    // Async / Actor / Storage live in `JsRuntimeAsync`.  `effect E:` blocks
    // also need the Free Monad runtime that ships in `JsRuntimeAsync`.
    // Include bare actor API calls (`runActors`, `spawn`, `receive`, `send`)
    // in addition to the qualified `Actor.*` / `Async.*` namespace markers.
    // SQL blocks and Db.* calls also require the async runtime: sql blocks
    // compile to `await` expressions and `Db.*` intrinsics are async.
    val hasSql = hasSqlBlocks(module) || module.manifest.exists(_.databases.nonEmpty)
    val hasAsync = hasSql ||
                   allText.contains("Db.") ||
                   allText.contains("route(") ||
                   allText.contains("Async.") || allText.contains("Actor.") ||
                   allText.contains("Storage.") || userEffectDecl ||
                   allText.contains("runAsync") || allText.contains("runActors") ||
                   allText.contains("handle(") || allText.contains("spawn {") ||
                   allText.contains("spawn{") || allText.contains("receive {") ||
                   allText.contains("receive{") || allText.contains("receive(") ||
                   allText.contains("_perform") || allText.contains("perform ") ||
                   // v1.23 cluster intrinsics that lower to Actor.* in JsGen —
                   // bare-name calls in user source still need the actor
                   // runtime emitted (without it `Actor` is undefined at run
                   // time).  Listed explicitly so capability detection picks
                   // them up before the Term-level lowering runs.
                   allText.contains("startNode") || allText.contains("connectNode") ||
                   allText.contains("joinCluster")
    if hasAsync then caps += Async
    // v1.4 effects: Logger / Random / Clock / Env / Auth — `JsRuntimeV14Effects`.
    val hasV14 = allText.contains("Logger.") || allText.contains("Random.") ||
                 allText.contains("Clock.")  || allText.contains("Env.")    ||
                 allText.contains("Auth.")   || allText.contains("runLogger") ||
                 allText.contains("runRandom") || allText.contains("runClock") ||
                 allText.contains("runEnv")  || allText.contains("runAuth")
    if hasV14 then caps += Effects
    // MCP — `JsRuntimeMcp`.
    val hasMcp = allText.contains("mcpServer") || allText.contains("serveMcp") ||
                 allText.contains("mcpConnect")
    if hasMcp then caps += Mcp
    // Dataset[T] — `JsRuntimeDataset`.
    val hasDataset = allText.contains("Dataset.") || allText.contains("Dataset(")
    if hasDataset then caps += Dataset
    // Payment Request — `JsRuntimePayment`.
    val hasPayment = allText.contains("PaymentRequest") || allText.contains("PaymentMethod.")
    if hasPayment then caps += Payment
    caps.toSet

  /** Emit `route(method, path)(handler)` registrations for every
   *  `routes:` entry in the module's front-matter. */
  private def emitFrontmatterRoutes(module: Module): Unit =
    module.manifest.toList.flatMap(_.routes).foreach { r =>
      val m = jsQuote(r.method)
      val p = jsQuote(r.path)
      line(s"route($m, $p)(${r.handler});")
    }

  /** Emit `_i18nTable = { ... }` from the module's front-matter translations. */
  private def emitI18nTable(module: Module): Unit =
    module.manifest.foreach { m =>
      if m.translations.nonEmpty then
        val entries = m.translations.map { (locale, kvs) =>
          val pairs = kvs.map { (k, v) => s"${jsQuote(k)}: ${jsQuote(v)}" }.mkString(", ")
          s"${jsQuote(locale)}: {$pairs}"
        }.mkString(", ")
        line(s"_i18nTable = {$entries};")
    }

  /** Emit browser/Electron HTTP clients declared by front-matter
   *  `apiClients:`. The generated methods intentionally return Promises:
   *  browser `fetch` is asynchronous, and SPA/Electron client-only bundles
   *  can route relative URLs to a JVM backend through
   *  `globalThis.__sscBackendBaseUrl`.
   */
  private def emitHttpTypedRouteClients(clients: List[ApiClientDecl]): Unit =
    val endpoints = clients.flatMap(client => client.endpoints.map(endpoint => client.name -> endpoint))
    if endpoints.nonEmpty then
      line("const _ssc_typedRouteClients = [")
      indent += 1
      endpoints.zipWithIndex.foreach { case ((client, endpoint), idx) =>
        val comma = if idx == endpoints.size - 1 then "" else ","
        line(
          "{client: " + jsQuote(client) +
            ", name: " + jsQuote(endpoint.name) +
            ", method: " + jsQuote(endpoint.method) +
            ", path: " + jsQuote(endpoint.path) +
            ", requestType: " + jsQuote(endpoint.requestType) +
            ", responseType: " + jsQuote(endpoint.responseType) +
            "}" + comma
        )
      }
      indent -= 1
      line("];")
      sb.append(httpTypedRouteClientRuntime)
      clients.foreach { client =>
        if client.endpoints.nonEmpty then
          line(s"const ${client.name} = {")
          indent += 1
          client.endpoints.zipWithIndex.foreach { case (endpoint, idx) =>
            val comma = if idx == client.endpoints.size - 1 then "" else ","
            val method = jsQuote(endpoint.method)
            val path = jsQuote(endpoint.path)
            val requestType = jsQuote(endpoint.requestType)
            val responseType = jsQuote(endpoint.responseType)
            if endpoint.requestType == "Unit" then
              line(s"${endpoint.name}() { return _ssc_api_request($method, $path, undefined, $requestType, $responseType); }$comma")
            else
              line(s"${endpoint.name}(input) { return _ssc_api_request($method, $path, input, $requestType, $responseType); }$comma")
          }
          indent -= 1
          line("};")
      }

  private val httpTypedRouteClientRuntime: String =
    """|// ── Typed route clients: HTTP transport ───────────────────────────
       |function _ssc_api_url_encode(value) {
       |  return encodeURIComponent(String(value));
       |}
       |
       |function _ssc_api_product_fields(value) {
       |  if (value == null) return {};
       |  if (typeof value !== 'object') return {};
       |  const out = {};
       |  for (const key of Object.keys(value)) {
       |    if (key !== '_type' && typeof value[key] !== 'function') out[key] = value[key];
       |  }
       |  return out;
       |}
       |
       |function _ssc_api_path_param_names(pathTemplate) {
       |  return String(pathTemplate).split('/').filter(s => s.startsWith(':')).map(s => s.slice(1));
       |}
       |
       |function _ssc_api_path(pathTemplate, input) {
       |  const names = _ssc_api_path_param_names(pathTemplate);
       |  const fields = _ssc_api_product_fields(input);
       |  const primitiveForSingleParam =
       |    names.length === 1 && Object.keys(fields).length === 0 && input !== undefined && input !== null;
       |  return String(pathTemplate).split('/').map(segment => {
       |    if (!segment.startsWith(':')) return segment;
       |    const name = segment.slice(1);
       |    const value = primitiveForSingleParam ? input : fields[name];
       |    if (value === undefined || value === null) {
       |      throw new Error("typed route client: missing path field '" + name + "'");
       |    }
       |    return _ssc_api_url_encode(value);
       |  }).join('/');
       |}
       |
       |function _ssc_api_query(pathTemplate, input) {
       |  const used = new Set(_ssc_api_path_param_names(pathTemplate));
       |  const fields = _ssc_api_product_fields(input);
       |  const pairs = [];
       |  for (const key of Object.keys(fields)) {
       |    if (!used.has(key) && fields[key] !== undefined && fields[key] !== null) {
       |      pairs.push(_ssc_api_url_encode(key) + "=" + _ssc_api_url_encode(fields[key]));
       |    }
       |  }
       |  return pairs.length === 0 ? "" : "?" + pairs.join("&");
       |}
       |
       |function _ssc_api_body(method, input, requestType) {
       |  if (method === "GET" || input === undefined || input === null) return undefined;
       |  return _ssc_typed_json_encode(input, requestType);
       |}
       |
       |""".stripMargin + TypedJsonCodecRuntime.jsFacade + """|async function _ssc_api_request(methodRaw, pathTemplate, input, requestType, responseType) {
       |  const method = String(methodRaw).toUpperCase();
       |  const url = _ssc_api_path(pathTemplate, input) + (method === "GET" ? _ssc_api_query(pathTemplate, input) : "");
       |  const init = { method: method, headers: {} };
       |  const body = _ssc_api_body(method, input, requestType);
       |  if (body !== undefined) {
       |    init.body = body;
       |    init.headers["Content-Type"] = "application/json";
       |  }
       |  const response = await fetch(url, init);
       |  const text = await response.text();
       |  if (!response.ok) {
       |    throw new Error("typed route client: " + method + " " + url + " returned " + response.status + ": " + text);
       |  }
       |  const contentType = response.headers && response.headers.get ? response.headers.get("content-type") || "" : "";
       |  return _ssc_typed_json_decode_response(text, contentType, responseType);
       |}
       |
       |""".stripMargin

  private def jsTypedJsonRegisterProduct(typeName: String, fields: Seq[String], ctorName: String): String =
    jsTypedJsonRegisterProduct(typeName, fields, Some(ctorName))

  private def jsTypedJsonRegisterProduct(typeName: String, fields: Seq[String], ctorName: Option[String]): String =
    val fieldArray = fields.map(jsQuote).mkString("[", ", ", "]")
    val ctor = ctorName.getOrElse("undefined")
    s"""if (typeof _ssc_typed_json_register_product === "function") _ssc_typed_json_register_product(${jsQuote(typeName)}, $fieldArray, $ctor);"""

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
      "Async.delay", "Async.async", "Async.await", "Async.parallel", "Async.recvFrom",
      "Storage.get", "Storage.put", "Storage.remove", "Storage.has", "Storage.keys",
      "Actor.spawn", "Actor.spawn_link", "Actor.self", "Actor.send", "Actor.exit",
      "Actor.receive", "Actor.receive_t",
      "Actor.link", "Actor.monitor", "Actor.demonitor", "Actor.trapExit",
      "Actor.startNode", "Actor.connectNode", "Actor.joinCluster", "Actor.register", "Actor.whereis",
      "Actor.globalRegister", "Actor.globalWhereis",
      "Actor.clusterMembers", "Actor.subscribeClusterEvents",
      "Actor.phiOf", "Actor.isSuspect",
      "Actor.selfNode", "Actor.clusterHealth",
      "Actor.broadcastHealth", "Actor.clusterIsDown",
      "Actor.electLeader", "Actor.currentLeader", "Actor.subscribeLeaderEvents",
      "Actor.setAutoReelect",
      "Actor.useRaftLeaderElection", "Actor.useExternalCoordinator",
      "Actor.leaderProtocol", "Actor.leaderHistory",
      "Actor.setReconnectPolicy", "Actor.requestGossip",
      "Actor.clusterConfigSet", "Actor.clusterConfigGet",
      "Actor.clusterConfigKeys", "Actor.subscribeConfigEvents",
      "Actor.setDraining", "Actor.isDraining",
      "Actor.drainingPeers", "Actor.subscribeDrainEvents",
      "Actor.clusterMetricSet", "Actor.clusterMetricGet",
      "Actor.clusterMetricSum", "Actor.clusterMetricNames",
      "Actor.subscribeMetricEvents",
      "Actor.sendAfter", "Actor.sendInterval", "Actor.cancelTimer",
      "Logger.info", "Logger.warn", "Logger.error", "Logger.debug",
      "Random.nextInt", "Random.nextDouble", "Random.uuid", "Random.pick",
      "Clock.now", "Clock.nowIso", "Clock.sleep",
      "Env.get", "Env.set", "Env.required"
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

  /** Walk the module AST and set `usesRunAsyncParallel` if any `runAsyncParallel` call
   *  is present.  Called from `genModule` before emitting user code sections so the
   *  IIFE wrapper and `await` prefix can be applied consistently. */
  private def scanForRunAsyncParallel(module: Module): Unit =
    def collectTrees(s: Section): List[scala.meta.Tree] =
      s.content.collect {
        case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
          cb.tree.map(ScalaNode.fold(_)(identity))
      }.flatten ++ s.subsections.flatMap(collectTrees)
    usesRunAsyncParallel = module.sections.flatMap(collectTrees).exists { tree =>
      tree.collect {
        case scala.meta.Term.Apply.After_4_6_0(scala.meta.Term.Name("runAsyncParallel"), _) => ()
      }.nonEmpty
    }

  /** Walk the module AST and set `usesRunActors` if any `runActors` call is
   *  present.  Mirrors `scanForRunAsyncParallel` — sister flag that also feeds
   *  the async-IIFE wrap decision in `genModule` and toggles the `await`
   *  prefix on `_runActors(...)` callsites.  The async-aware scheduler yields
   *  to Node's event loop between ticks (see runtime emission), which is
   *  what unblocks `serveAsync(...)` bound from inside `runActors { ... }`. */
  private def scanForRunActors(module: Module): Unit =
    def collectTrees(s: Section): List[scala.meta.Tree] =
      s.content.collect {
        case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
          cb.tree.map(ScalaNode.fold(_)(identity))
      }.flatten ++ s.subsections.flatMap(collectTrees)
    usesRunActors = module.sections.flatMap(collectTrees).exists { tree =>
      tree.collect {
        case scala.meta.Term.Apply.After_4_6_0(scala.meta.Term.Name("runActors"), _) => ()
      }.nonEmpty
    }

  /** Walk client-side ScalaScript blocks and detect `awaitClient(promise)`.
   *  Server-only blocks are skipped because JS targets do not emit them.
   */
  private def scanForAwaitClient(module: Module): Unit =
    def collectTrees(s: Section): List[scala.meta.Tree] =
      s.content.collect {
        case cb: Content.CodeBlock
            if Lang.isScalaScript(cb.lang) && !cb.attrs.get("side").contains("server") =>
          cb.tree.map(ScalaNode.fold(_)(identity))
      }.flatten ++ s.subsections.flatMap(collectTrees)
    usesAwaitClient = module.sections.flatMap(collectTrees).exists { tree =>
      tree.collect {
        case scala.meta.Term.Apply.After_4_6_0(scala.meta.Term.Name("awaitClient"), _) => ()
      }.nonEmpty
    }

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
    collectFuncParamOrders(module)
    analyzeMutualRecursion(module)
    analyzeEffects(module)
    scanForRunAsyncParallel(module)
    scanForRunActors(module)
    scanForAwaitClient(module)
    // Emit `route(...)` registrations from front-matter before user blocks,
    // so a typical user-side `serve(port)` (last statement of the script)
    // sees them already registered.  JS function declarations are hoisted,
    // so forward references to handler defs resolve at call time.
    emitFrontmatterRoutes(module)
    emitI18nTable(module)
    emitHttpTypedRouteClients(module.manifest.toList.flatMap(_.apiClients))
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
        case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) && cb.attrs.get("side").contains("server") =>
          ()
        case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
          flushScala()
          cb.tree.foreach(genScalaNode)
        case Content.CodeBlock(lang, src, _, _, _, _, _) if Lang.isStandardScala(lang) =>
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

    val needsAsyncSeg = usesRunAsyncParallel || usesRunActors || usesAwaitClient
    if needsAsyncSeg then
      sb.append("(async () => {\n")
      // See `genModule` for the rationale — install a write-through
      // `_println` (and rebind `Console.println` which captures the
      // original `_println` by reference) inside the IIFE so prints
      // from after the first `await` reach stdout instead of being
      // buffered into a `_output` array the outer segment-end flush
      // no longer sees.
      sb.append("if (typeof process !== 'undefined' && process.stdout && typeof _output !== 'undefined') {\n")
      sb.append("  _println = function(s) { process.stdout.write(String(s) + '\\n'); };\n")
      sb.append("  _print   = function(s) { process.stdout.write(String(s));        };\n")
      sb.append("  if (typeof Console !== 'undefined') {\n")
      sb.append("    Console.println = _println;\n")
      sb.append("    Console.print   = _print;\n")
      sb.append("  }\n")
      sb.append("}\n")
    module.sections.foreach(walkSection)
    flushScala()
    if hasMain && !mainCalled then
      sb.append("if (typeof main === 'function') { main(); }\n")
    if needsAsyncSeg then
      sb.append("})().catch(e => {\n")
      sb.append("  const msg = String(e && e.stack ? e.stack : e);\n")
      sb.append("  if (typeof process !== 'undefined' && process.stderr) { process.stderr.write(msg + '\\n'); process.exit(1); }\n")
      sb.append("  else if (typeof document !== 'undefined') { document.body.textContent = msg; }\n")
      sb.append("  else { console.error(msg); }\n")
      sb.append("});\n")
    val finalCode = sb.substring(ssStart)
    if finalCode.trim.nonEmpty then result += JsGen.Segment.ScalaScriptJs(finalCode)
    result.toList

  private[codegen] def genSection(section: Section): Unit =
    section.content.foreach {
      case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) && cb.attrs.get("side").contains("server") =>
        ()
      case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
        cb.tree.foreach(genScalaNode)
      case cb: Content.CodeBlock if Lang.isStandardScala(cb.lang) =>
        line(s"/* scala: standard Scala 3 block — compile via Scala.js for JS execution */")
      case cb: Content.CodeBlock if Lang.isStringBlock(cb.lang) =>
        genStringBlock(cb, section)
      case cb: Content.CodeBlock if Lang.isSql(cb.lang) =>
        genSqlBlock(cb, section)
      case imp: Content.Import =>
        genImport(imp)
      case _ => ()
    }
    section.subsections.foreach(genSection)

  /** v1.27 Phase 3 — emit a `sql` fenced block as a `_sqlBlock_<n>`
   *  `const` initialised by `await SqlRuntimeJs.execute(...)`.
   *
   *  Mirrors `JvmGen.sqlBlockToScala` but emits JS:
   *    - `${expr}` interpolations have already been lifted to a `?`-
   *      template + ordered bind list by `SqlBindRewriter.rewriteJdbc`.
   *      Each bind text is parsed back as a Scala term and emitted as
   *      JS via the existing `genExpr` machinery, so the bind value
   *      evaluates in the surrounding scope at runtime.
   *    - Connection resolution funnels through the module-scope
   *      `_ssc_sql_resolve(dbName)` helper emitted once by
   *      `emitSqlPreamble`.
   *    - First sql block in each section also lands as
   *      `const <sectionId> = { ..., sql: _sqlBlock_<n> }` — the
   *      friendly `<sectionId>.sql` alias matches JvmGen's Phase
   *      6.C / Spark Phase C.2 convention.  Subsequent sql blocks in
   *      the same section skip the alias (the first-only book-keeping
   *      in `sqlPerSection` enforces this). */
  private def genSqlBlock(cb: Content.CodeBlock, section: Section): Unit =
    // v1.30 Phase 4 — @side=server blocks are server-only; skip in JS targets.
    if cb.attrs.get("side").contains("server") then return
    val n = sqlBlockCounter
    sqlBlockCounter += 1
    val rewrite = scalascript.transform.SqlBindRewriter.rewriteJdbc(cb.source)
    val sqlLit  = jsStringLit(rewrite.sql)
    val bindsJs = rewrite.binds.map(bindExprToJs).mkString(", ")
    val dbArg   = cb.attrs.get("db") match
      case Some(name) => jsStringLit(name)
      case None       => "undefined"
    val valName = s"_sqlBlock_$n"
    line(s"const $valName = await SqlRuntimeJs.execute(await _ssc_sql_resolve($dbArg), $sqlLit, [$bindsJs]);")
    sectionIdent(section.heading.text).foreach { id =>
      val prior = sqlPerSection.getOrElse(id, 0)
      sqlPerSection(id) = prior + 1
      if prior == 0 then
        line(s"if (typeof $id === 'undefined') var $id = {};")
        line(s"$id.sql = $valName;")
    }

  /** Parse a single bind-expression text (Scala source from inside
   *  `${...}`) back to a `Term` and emit it as JS.  Falls back to
   *  splicing the raw source verbatim when scala.meta can't parse it
   *  (defensive — the parser already rejected malformed source upstream,
   *  but never trust the boundary). */
  private def bindExprToJs(exprSrc: String): String =
    val trimmed = exprSrc.trim
    val parsed  =
      try
        Some(scala.meta.dialects.Scala3(scala.meta.Input.String(trimmed)).parse[scala.meta.Term].toOption).flatten
      catch case _: Throwable => None
    parsed match
      case Some(t) => genExpr(t)
      case None    => trimmed   // last-resort fallback

  /** Escape a string for a double-quoted JS literal. */
  private def jsStringLit(s: String): String =
    val sb = StringBuilder()
    sb.append('"')
    var i = 0
    while i < s.length do
      val c = s.charAt(i)
      (c: @scala.annotation.switch) match
        case '\\' => sb.append("\\\\")
        case '"'  => sb.append("\\\"")
        case '\n' => sb.append("\\n")
        case '\r' => sb.append("\\r")
        case '\t' => sb.append("\\t")
        case '\b' => sb.append("\\b")
        case '\f' => sb.append("\\f")
        case _    =>
          if c < 0x20 || c == 0x7f then sb.append("\\u%04x".format(c.toInt))
          else                          sb.append(c)
      i += 1
    sb.append('"')
    sb.toString

  /** Detect whether the module has any sql blocks — drives preamble
   *  emission + async wrap.  Walks sections recursively. */
  private def hasSqlBlocks(module: Module): Boolean =
    def go(s: Section): Boolean =
      s.content.exists {
        // v1.30 — @side=server blocks are server-only; don't count them as
        // requiring the SQL preamble in a JS-family bundle.
        case cb: Content.CodeBlock =>
          Lang.isSql(cb.lang) && !cb.attrs.get("side").contains("server")
        case _ => false
      } || s.subsections.exists(go)
    module.sections.exists(go)

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
      val tail = parts.tail.map(p => s"${p.head.toUpper}${p.tail}")
      val raw  = head + tail.mkString
      Some(if raw.head.isDigit then "_" + raw else raw)

  private def genImport(imp: Content.Import): Unit =
    import scalascript.parser.Parser
    val base = baseDir.getOrElse(os.pwd)
    val initiallyResolved =
      try scalascript.imports.ImportResolver.resolve(imp.path, base, moduleDeps, lockPath)
      catch case _: Throwable => base / os.RelPath(imp.path)
    val resolvedPath =
      if os.exists(initiallyResolved) then initiallyResolved
      else resolveStdImportFromProjectTree(imp.path, base).getOrElse(initiallyResolved)
    if !os.exists(resolvedPath) then return
    val key         = resolvedPath.toString
    val childModule = Parser.parse(os.read(resolvedPath))
    if !importedFiles.contains(key) then
      importedFiles += key
      val childDir = resolvedPath / os.up
      val childGen = new JsGen(Some(childDir), lockPath = lockPath, topLevelConsts = topLevelConsts, mergeHelperEmitted = mergeHelperEmitted, declaredBindings = declaredBindings)
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
    // Always extract bindings, even when this module was already imported by
    // a transitive dependency.  Cycle-protection only guards code emission —
    // each explicit import still needs its `const x = pkg.x;` binding.
    // `declaredBindings` prevents duplicate `const` declarations when the same
    // symbol is extracted more than once (e.g. TkNode from nodes.ssc imported
    // by both primitives.ssc and layout.ssc).
    val childPkg     = childModule.manifest.flatMap(_.pkg).getOrElse(Nil)
    val pkgPrefix    = if childPkg.isEmpty then "" else childPkg.mkString("", ".", ".")
    val childExports = childModule.manifest.map(_.exports).getOrElse(Nil)
    imp.bindings.foreach { b =>
      val fullName  = s"$pkgPrefix${b.name}"
      val localName = b.alias.getOrElse(b.name)
      // If the child module declares an exports list and this name is absent,
      // skip — don't block a later import from the correct module.
      val notExported = childExports.nonEmpty && !childExports.contains(b.name)
      if fullName != localName && !notExported && !declaredBindings.contains(localName) then
        declaredBindings += localName
        line(s"const $localName = $fullName;")
    }

  private def resolveStdImportFromProjectTree(rawPath: String, base: os.Path): Option[os.Path] =
    if !rawPath.startsWith("std/") then None
    else
      val rel = os.RelPath(rawPath)
      var cur = base
      while true do
        val runtimeStd = cur / "runtime" / rel
        if os.exists(runtimeStd) then return Some(runtimeStd)
        val installedStd = cur / rel
        if os.exists(installedStd) then return Some(installedStd)
        val parent = cur / os.up
        if parent == cur then return None
        cur = parent
      None

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
          stat match
            case d: Defn.Object if topLevel =>
              val name = d.name.value
              if topLevelConsts.contains(name) then
                emitMergeHelper()
                line(s"_ssc_mergeDeep($name, ${genObjectAsExpr(d)});")
              else
                topLevelConsts += name
                genStat(stat)
            case _ =>
              genStat(stat)
    }

  private def emitMergeHelper(): Unit =
    if !mergeHelperEmitted(0) then
      mergeHelperEmitted(0) = true
      line("function _ssc_mergeDeep(dst, src) { for (const k of Object.keys(src)) { if (dst[k] !== null && typeof dst[k] === 'object' && typeof src[k] === 'object') _ssc_mergeDeep(dst[k], src[k]); else dst[k] = src[k]; } }")

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
      val paramVals   = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values)
      val params      = paramVals.map(_.name.value)
      val hasDefaults = paramVals.exists(_.default.isDefined)
      val fname       = d.name.value
      val defRenames  = params.collect { case p if jsReservedWords.contains(p) => p -> safeJsParam(p) }.toMap
      // Context-bound type params [A: TC] → synthetic JS param "A$TC", summon key "TC_A"
      @annotation.nowarn("msg=deprecated")
      val cbParams: List[(String, String)] =
        d.paramClauseGroups.flatMap(_.tparamClause.values).flatMap { tp =>
          tp.cbounds.map { cb =>
            val tvName = tp.name.value
            val tcName = cb match
              case Type.Name(n)   => n
              case ta: Type.Apply => ta.tpe match { case Type.Name(n) => n; case _ => "?" }
              case _              => "?"
            s"${tvName}$$${tcName}" -> s"${tcName}_${tvName}"
          }
        }
      val savedCbMap    = cbSummonMap.toMap
      cbSummonMap.clear()
      cbParams.foreach { (pname, skey) => cbSummonMap(skey) = pname }
      val baseParamsStr = paramListWithDefaults(paramVals)
      val cbParamsStr   = cbParams.map(_._1).mkString(", ")
      val paramsStr     =
        if baseParamsStr.isEmpty then cbParamsStr
        else if cbParamsStr.isEmpty then baseParamsStr
        else s"$baseParamsStr, $cbParamsStr"
      if fname == "main" && params.isEmpty then hasMain = true
      // Effectful function: body emitted in CPS form, returns Free value.
      if isEffectfulFun(fname) then
        d.body match
          case Term.Block(stats) =>
            line(s"function $fname($paramsStr) { return ${genCpsBlockAsIife(stats)}; }")
          case expr =>
            line(s"function $fname($paramsStr) { return ${genCpsExpr(expr)}; }")
      // Context-bound params: emit plain function with auto-resolve guards; skip TCO
      else if cbParams.nonEmpty then
        val hintParam = paramVals.headOption.map(_.name.value).getOrElse("undefined")
        val cbGuards = cbParams.map { (pname, skey) =>
          val tcName = skey.takeWhile(_ != '_')
          s"""if ($pname === undefined) $pname = _resolveGiven("${tcName}_" + _ssc_typeOf($hintParam));"""
        }
        d.body match
          case Term.Block(bodyStats) =>
            line(s"function $fname($paramsStr) {")
            indent += 1
            cbGuards.foreach(line)
            genFunctionBody(bodyStats)
            indent -= 1
            line("}")
          case expr =>
            line(s"function $fname($paramsStr) {")
            indent += 1
            cbGuards.foreach(line)
            line(s"return ${genExpr(expr)};")
            indent -= 1
            line("}")
      // Mutual recursion group → _impl + trampoline wrapper.
      // Defaults disable the TCO/mutual-TCO shadowing path since the _p shadow
      // names would shadow the original parameter names referenced in default
      // expressions; defaults are uncommon in tight recursive loops anyway.
      else if mutualGroups.contains(fname) && params.nonEmpty && !hasDefaults then
        genMutualTcoFun(d, fname, params)
      // Self-TCO: emit a while-loop trampoline when all self-calls are in tail position
      else if params.nonEmpty && fname.nonEmpty && !hasDefaults &&
              !hasNonTailSelfCall(d.body, fname, tailPos = true) then
        // Formals are _p shadow-names so we can declare mutable let params inside.
        // safeJsParam guards against JS reserved words (e.g. `default` → `default_p`).
        val renames  = params.collect { case p if jsReservedWords.contains(p) => p -> safeJsParam(p) }.toMap
        val formals  = params.map(p => s"_$p").mkString(", ")
        val letDecls = "let " + params.map(p => s"${safeJsParam(p)} = _$p").mkString(", ")
        line(s"function $fname($formals) {")
        indent += 1
        line(s"$letDecls;")
        line("while(true) {")
        indent += 1
        withParamRenames(renames)(genTcoBody(d.body, fname, params))
        indent -= 1
        line("}")
        indent -= 1
        line("}")
      else
        d.body match
          case Term.Block(bodyStats) =>
            line(s"function $fname($paramsStr) {")
            indent += 1
            withParamRenames(defRenames)(genFunctionBody(bodyStats))
            indent -= 1
            line("}")
          case expr =>
            line(s"function $fname($paramsStr) { return ${withParamRenames(defRenames)(genExpr(expr))}; }")
      cbSummonMap.clear()
      cbSummonMap ++= savedCbMap

    case d: Defn.Class =>
      // case class → constructor function returning plain object
      val paramVals = d.ctor.paramClauses.flatMap(_.values)
      val params = paramVals.map(_.name.value)
      val typeName = d.name.value
      val paramsStr = paramListWithDefaults(paramVals)
      val fields = params.map(p => s"$p: $p").mkString(", ")
      line(s"function $typeName($paramsStr) { return {_type: '$typeName', $fields}; }")
      line(jsTypedJsonRegisterProduct(typeName, params, typeName))

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
            line(jsTypedJsonRegisterProduct(caseName, Nil, None))
          else
            val paramsStr = paramListWithDefaults(paramVals)
            val fields = params.map(p => s"$p: $p").mkString(", ")
            line(s"function $caseName($paramsStr) { return {_type: '$caseName', $fields}; }")
            line(jsTypedJsonRegisterProduct(caseName, params, caseName))
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
            // Also register with typeclass key for summon and _ssc_givens
            line(s"const $typeKey = $explicitName;")
            line(s"""_ssc_givens["$typeKey"] = $explicitName;""")
          else
            line(s"const $typeKey = $obj;")
            line(s"""_ssc_givens["$typeKey"] = $typeKey;""")
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
      case dd: Defn.Def if scalascript.transform.EffectAnalysis.isExternDef(dd.body) =>
        val fname = dd.name.value
        val stub = s"_ssc_ui_$fname"
        // Forward to the corresponding Node.js runtime stub if present,
        // otherwise leave as undefined.  Avoids TDZ issues that occur when
        // the parent scope later does `const fname = pkg.fname` (shadowing).
        decls += s"const $fname = (typeof $stub !== 'undefined') ? $stub : undefined;"
        names += fname
      case dd: Defn.Def if isEffectOpDef(dd.body) =>
        val opName = dd.name.value
        val paramVals = dd.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values)
        val params    = paramVals.map(_.name.value)
        val paramsStr = paramListWithDefaults(paramVals)
        val argsArr = if params.isEmpty then "[]" else s"[${params.mkString(", ")}]"
        decls += s"const $opName = ($paramsStr) => _perform('$objectName', '$opName', $argsArr);"
        names += opName
      case dd: Defn.Def =>
        val fname = dd.name.value
        val allClauses = dd.paramClauseGroups.flatMap(_.paramClauses).filterNot(_.mod.nonEmpty)
        val bodyJs = dd.body match
          case Term.Block(bodyStats) => genBlockAsIife(bodyStats)
          case expr                   => genExpr(expr)
        def clauseSig(params: List[Term.Param]): String =
          if params.nonEmpty && params.last.decltpe.exists(_.isInstanceOf[Type.Repeated]) then
            val nonVararg = params.init
            val vararg    = params.last.name.value
            if nonVararg.isEmpty then s"(...$vararg)"
            else s"(${paramListWithDefaults(nonVararg)}, ...$vararg)"
          else s"(${paramListWithDefaults(params)})"
        if allClauses.length <= 1 then
          val sig = clauseSig(allClauses.flatMap(_.values))
          decls += s"const $fname = $sig => $bodyJs;"
        else
          val innerFn = allClauses.init.foldRight(clauseSig(allClauses.last.values) + s" => $bodyJs") {
            (clause, inner) => clauseSig(clause.values) + s" => $inner"
          }
          decls += s"const $fname = $innerFn;"
        names += fname
      case Defn.Val(_, List(Pat.Var(n)), _, rhs) =>
        decls += s"const ${n.value} = ${genExpr(rhs)};"
        names += n.value
      case nested: Defn.Object =>
        decls += s"const ${nested.name.value} = ${genObjectAsExpr(nested)};"
        names += nested.name.value
      case d: Defn.Class =>
        val paramVals = d.ctor.paramClauses.flatMap(_.values)
        val params    = paramVals.map(_.name.value)
        val typeName  = d.name.value
        val paramsStr = paramListWithDefaults(paramVals)
        val fields    = params.map(p => s"$p: $p").mkString(", ")
        decls += s"function $typeName($paramsStr) { return {_type: '$typeName', $fields}; }"
        decls += jsTypedJsonRegisterProduct(typeName, params, typeName)
        names += typeName
      case d: Defn.Enum =>
        d.templ.body.stats.foreach {
          case ec: Defn.EnumCase =>
            val caseName  = ec.name.value
            val paramVals = ec.ctor.paramClauses.flatMap(_.values)
            val params    = paramVals.map(_.name.value)
            if params.isEmpty then
              decls += s"const $caseName = {_type: '$caseName'};"
              decls += jsTypedJsonRegisterProduct(caseName, Nil, None)
              names += caseName
            else
              val paramsStr = paramListWithDefaults(paramVals)
              val fields    = params.map(p => s"$p: $p").mkString(", ")
              decls += s"function $caseName($paramsStr) { return {_type: '$caseName', $fields}; }"
              decls += jsTypedJsonRegisterProduct(caseName, params, caseName)
              names += caseName
          case _ => ()
        }
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

  // JS reserved words that cannot appear as parameter names (ES2022 strict mode).
  private val jsReservedWords = Set(
    "await", "break", "case", "catch", "class", "const", "continue", "debugger",
    "default", "delete", "do", "else", "export", "extends", "false", "finally",
    "for", "function", "if", "import", "in", "instanceof", "let", "new", "null",
    "return", "static", "super", "switch", "this", "throw", "true", "try",
    "typeof", "var", "void", "while", "with", "yield"
  )

  private def safeJsParam(name: String): String =
    if jsReservedWords.contains(name) then s"${name}_p" else name

  private def formalWithDefault(p: Term.Param): String =
    val n = safeJsParam(p.name.value)
    p.default match
      case Some(d) => s"$n = ${genExpr(d)}"
      case None    => n

  // ─── Mutual TCO helpers ──────────────────────────────────────────

  // Emits _fname_impl (while-loop + _tailCall for mutual calls) and the public wrapper.
  private def genMutualTcoFun(d: Defn.Def, fname: String, params: List[String]): Unit =
    val implName = s"_${fname}_impl"
    val friends  = mutualGroups(fname) - fname
    val renames  = params.collect { case p if jsReservedWords.contains(p) => p -> safeJsParam(p) }.toMap
    val formals  = params.map(p => s"_$p").mkString(", ")
    val letDecls = "let " + params.map(p => s"${safeJsParam(p)} = _$p").mkString(", ")
    line(s"function $implName($formals) {")
    indent += 1
    line(s"$letDecls;")
    line("while(true) {")
    indent += 1
    withParamRenames(renames)(genMutualTcoBody(d.body, fname, params, friends))
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

  // ── Generator / coroutine body helpers ───────────────────────────────
  // The parser wraps `{ () => body }` in a Term.Block; this helper unwraps
  // it so we always call genGeneratorBody with just the body content.
  private def extractGenBody(arg: Term): String = arg match
    case Term.Function.After_4_6_0(_, body) =>
      genGeneratorBody(body.asInstanceOf[Term])
    case Term.Block(List(Term.Function.After_4_6_0(_, body))) =>
      genGeneratorBody(body.asInstanceOf[Term])
    case other =>
      s"function*() { return ${genExpr(other)}; }"

  private def extractCoroutineBody(arg: Term): String = arg match
    case Term.Function.After_4_6_0(_, body) =>
      genCoroutineBody(body.asInstanceOf[Term])
    case Term.Block(List(Term.Function.After_4_6_0(_, body))) =>
      genCoroutineBody(body.asInstanceOf[Term])
    case other =>
      s"function*() { return ${genExpr(other)}; }"

  private def genGeneratorBody(t: Term): String =
    s"function*() {\n${genGenStmt(t)}\n}"

  // Like genGeneratorBody but emits `return` for the last expression so
  // the coroutine's completion value is propagated via gen.next().done.
  private def genCoroutineBody(t: Term): String =
    s"function*() {\n${genCoroutineStmts(t)}\n}"

  private def genCoroutineStmts(t: Term): String = t match
    case Term.Block(stats) if stats.nonEmpty =>
      val (init, last) = (stats.init, stats.last)
      val initJs = init.map(genGenStatItem).mkString("\n")
      val lastJs = last match
        case w: Term.While  => genGenStatItem(w)  // while returns Unit — no return needed
        case t: Term.If     => genGenStatItem(t)  // if-as-statement form
        case t: Term.Throw  => genGenStatItem(t)  // throw is a statement, not an expression
        case t: Term        => s"  return ${genGenExpr(t)};"
        case s              => s"  ${genStatInline(s)}"
      if init.isEmpty then lastJs else initJs + "\n" + lastJs
    case t: Term => s"  return ${genGenExpr(t)};"
    case null    => ""

  private def genGenStmt(t: Term): String = t match
    case Term.Block(stats) => stats.map(genGenStatItem).mkString("\n")
    case s: Stat           => genGenStatItem(s)

  private def genGenStatItem(s: Stat): String = s match
    case Defn.Val(_, List(Pat.Var(n)), _, rhs) =>
      s"  const ${n.value} = ${genGenExpr(rhs)};"
    case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs) =>
      s"  let ${n.value} = ${genGenExpr(rhs)};"
    case Term.Assign(Term.Name(n), rhs) =>
      s"  $n = ${genGenExpr(rhs)};"
    case t: Term.While =>
      val bodyStr = genGenStmt(t.body)
      s"  while (${genExpr(t.expr)}) {\n$bodyStr\n  }"
    case t: Term.If =>
      val elseStr = t.elsep match
        case Lit.Unit() => ""
        case ep         => s" else {\n${genGenStmt(ep)}\n  }"
      s"  if (${genExpr(t.cond)}) {\n${genGenStmt(t.thenp)}\n  }$elseStr"
    case t: Term.Match =>
      // Match as statement in a generator/coroutine body — must NOT wrap in an IIFE
      // because case branches may contain `yield` (via suspend), which is invalid inside arrow fns.
      val scrutVar = freshTmp()
      val scrutExpr = genGenExpr(t.expr)
      val casesJs = t.casesBlock.cases.map { c =>
        val (cond, bindings) = genPattern(scrutVar, c.pat)
        val bindingJs = if bindings.isEmpty then ""
          else bindings.map { case (n, e) => s"    const $n = $e;" }.mkString("\n") + "\n"
        val bodyJs = genGenStmt(c.body)
        val condStr = s"($cond) "
        s"  if $condStr{\n$bindingJs$bodyJs\n  }"
      }.mkString(" else ")
      s"  const $scrutVar = $scrutExpr;\n$casesJs"
    case Term.Throw(expr) =>
      val errMsg = expr match
        case Term.New(init) =>
          init.argClauses.headOption.flatMap(_.values.headOption)
            .map(v => genExpr(v.asInstanceOf[Term]))
            .getOrElse("'error'")
        case Term.Apply.After_4_6_0(Term.Name("RuntimeException" | "Exception" | "Error"), argClause)
            if argClause.values.size == 1 =>
          genExpr(argClause.values.head.asInstanceOf[Term])
        case _ => genGenExpr(expr)
      s"  throw new Error($errMsg);"
    case t: Term => s"  ${genGenExpr(t)};"
    case _       => s"  ${genStatInline(s)}"

  private def genGenExpr(t: Term): String = t match
    case Term.Apply.After_4_6_0(Term.Name("suspend"), argClause) if argClause.values.size == 1 =>
      s"(yield ${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Block(stats) => stats.map(genGenStatItem).mkString("\n")
    case _                 => genExpr(t)

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

    // Stage 5+/B.3 — qualified intrinsic dispatch for `Obj.method(args)`.
    case Term.Apply.After_4_6_0(Term.Select(Term.Name(obj), Term.Name(method)), argClause)
        if dispatchIntrinsicJs(s"$obj.$method", argClause).isDefined =>
      dispatchIntrinsicJs(s"$obj.$method", argClause).get

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

    // User-defined interpolator: _ext_StringContext_prefix(_sc([...]), [arg1, arg2])
    // Args are packed into an array so the `args: Any*` param binds a list.
    case Term.Interpolate(Term.Name(prefix), parts, args) =>
      val partsJs = parts.map { p =>
        val s = p.asInstanceOf[Lit.String].value
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
      }.mkString("[", ", ", "]")
      val argsJs = args.map(a => genExpr(a.asInstanceOf[Term])).mkString("[", ", ", "]")
      s"_ext_StringContext_$prefix(_sc($partsJs), $argsJs)"

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
          // Prefer a local CB param if one shadows this summon key
          cbSummonMap.getOrElse(key, key)
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
      val awaitPrefix = if usesRunAsyncParallel then "await " else ""
      s"${awaitPrefix}_runAsyncParallel(() => ${genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])})"

    // Browser/client helper for Promise-returning typed HTTP clients.
    // Source form: `awaitClient(Messages.list())` → JS: `await Messages.list()`.
    case Term.Apply.After_4_6_0(Term.Name("awaitClient"), argClause)
        if argClause.values.size == 1 =>
      s"await ${genExpr(argClause.values.head.asInstanceOf[Term])}"

    // Client IndexedDB typed helper.
    // Source: `IndexedDb.store[Draft]("drafts")`
    // JS:     `IndexedDb.store("drafts", "Draft")`
    case Term.Apply.After_4_6_0(
          Term.ApplyType.After_4_6_0(Term.Select(Term.Name("IndexedDb"), Term.Name("store")), typeArgs),
          argClause
        ) =>
      val typeName = typeArgs.values.headOption match
        case Some(Type.Name(name)) => name
        case Some(other) => other.syntax
        case None => ""
      val args = argClause.values.map(v => genExpr(v.asInstanceOf[Term])).toList
      val jsArgs = args.headOption.toList ++ List(jsQuote(typeName)) ++ args.drop(1)
      s"IndexedDb.store(${jsArgs.mkString(", ")})"

    // Client ObjectStore sync helpers.
    // Source: `Sync.pull[Draft]("drafts")`
    // JS:     `Sync.pull("drafts", "Draft")`
    case Term.Apply.After_4_6_0(
          Term.ApplyType.After_4_6_0(Term.Select(Term.Name("Sync"), Term.Name(method)), typeArgs),
          argClause
        ) if method == "pull" || method == "push" =>
      val typeName = typeArgs.values.headOption match
        case Some(Type.Name(name)) => name
        case Some(other) => other.syntax
        case None => ""
      val args = argClause.values.map(v => genExpr(v.asInstanceOf[Term])).toList
      val jsArgs = args.headOption.toList ++ List(jsQuote(typeName)) ++ args.drop(1)
      s"Sync.$method(${jsArgs.mkString(", ")})"
    case Term.Apply.After_4_6_0(
          Term.ApplyType.After_4_6_0(Term.Select(Term.Name("Sync"), Term.Name(method)), typeArgs),
          argClause
        ) if method == "put" || method == "remove" =>
      val typeName = typeArgs.values.headOption match
        case Some(Type.Name(name)) => name
        case Some(other) => other.syntax
        case None => ""
      val args = argClause.values.map(v => genExpr(v.asInstanceOf[Term])).toList
      val jsArgs = args.headOption.toList ++ List(jsQuote(typeName)) ++ args.drop(1)
      s"Sync.$method(${jsArgs.mkString(", ")})"

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
    // a Free tree the scheduler walks.  When the module uses `runActors`
    // anywhere, the whole top level runs inside an async IIFE (see
    // `genModule`'s `needsAsync`), and `_runActors` is async so it yields
    // to Node's event loop between scheduler ticks; the `await` here
    // makes the caller observe the body's last expression value (instead
    // of a Promise) and lets surrounding statements see the actor body's
    // side effects finish before they run.
    case Term.Apply.After_4_6_0(Term.Name("runActors"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      val awaitPrefix = if usesRunActors then "await " else ""
      s"${awaitPrefix}_runActors(() => ${genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])})"

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
    case Term.Apply.After_4_6_0(Term.Name("spawn_link"), argClause)
        if argClause.values.size == 1 =>
      val thunk = argClause.values.head.asInstanceOf[Term]
      thunk match
        case Term.Function.After_4_6_0(_, body) =>
          s"Actor.spawn_link(() => ${genCpsExpr(body)})"
        case other => s"Actor.spawn_link(${genExpr(other)})"
    case Term.Apply.After_4_6_0(Term.Name("spawnBounded"), argClause)
        if argClause.values.size == 3 =>
      val capJs      = genExpr(argClause.values(0).asInstanceOf[Term])
      val overflowJs = genExpr(argClause.values(1).asInstanceOf[Term])
      val thunk      = argClause.values(2).asInstanceOf[Term]
      val thunkJs = thunk match
        case Term.Function.After_4_6_0(_, body) => s"() => ${genCpsExpr(body)}"
        case other => genExpr(other)
      s"Actor.spawnBounded($capJs, $overflowJs, $thunkJs)"
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
    // v1.6 Phase 3 — distributed node primitives
    case Term.Apply.After_4_6_0(Term.Name("startNode"), argClause)
        if argClause.values.size >= 1 =>
      val nodeId = genExpr(argClause.values(0).asInstanceOf[Term])
      val url    = if argClause.values.size >= 2 then genExpr(argClause.values(1).asInstanceOf[Term]) else "\"\""
      s"Actor.startNode($nodeId, $url)"
    case Term.Apply.After_4_6_0(Term.Name("connectNode"), argClause)
        if argClause.values.size >= 1 =>
      val url   = genExpr(argClause.values(0).asInstanceOf[Term])
      val token = if argClause.values.size >= 2 then genExpr(argClause.values(1).asInstanceOf[Term]) else "\"\""
      s"Actor.connectNode($url, $token)"
    case Term.Apply.After_4_6_0(Term.Name("joinCluster"), argClause)
        if argClause.values.size >= 1 =>
      val seeds = genExpr(argClause.values(0).asInstanceOf[Term])
      val token = if argClause.values.size >= 2 then genExpr(argClause.values(1).asInstanceOf[Term]) else "\"\""
      s"Actor.joinCluster($seeds, $token)"
    case Term.Apply.After_4_6_0(Term.Name("register"), argClause)
        if argClause.values.size == 2 =>
      s"Actor.register(${genExpr(argClause.values(0).asInstanceOf[Term])}, ${genExpr(argClause.values(1).asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("whereis"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.whereis(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("globalRegister"), argClause)
        if argClause.values.size == 2 =>
      s"Actor.globalRegister(${genExpr(argClause.values(0).asInstanceOf[Term])}, ${genExpr(argClause.values(1).asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("globalWhereis"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.globalWhereis(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.23 — cluster visibility
    case Term.Apply.After_4_6_0(Term.Name("clusterMembers"), argClause)
        if argClause.values.isEmpty =>
      "Actor.clusterMembers()"
    case Term.Apply.After_4_6_0(Term.Name("subscribeClusterEvents"), argClause)
        if argClause.values.isEmpty =>
      "Actor.subscribeClusterEvents()"
    // v1.23 — phi-accrual failure detector
    case Term.Apply.After_4_6_0(Term.Name("phiOf"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.phiOf(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("isSuspect"), argClause)
        if argClause.values.size >= 1 =>
      val nid = genExpr(argClause.values(0).asInstanceOf[Term])
      val thr = if argClause.values.size >= 2 then genExpr(argClause.values(1).asInstanceOf[Term]) else "8.0"
      s"Actor.isSuspect($nid, $thr)"
    // v1.23 — local node identity + phi vector
    case Term.Apply.After_4_6_0(Term.Name("selfNode"), argClause)
        if argClause.values.isEmpty =>
      "Actor.selfNode()"
    case Term.Apply.After_4_6_0(Term.Name("clusterHealth"), argClause)
        if argClause.values.isEmpty =>
      "Actor.clusterHealth()"
    // v1.23 — cluster-wide failure detector
    case Term.Apply.After_4_6_0(Term.Name("broadcastHealth"), argClause)
        if argClause.values.isEmpty =>
      "Actor.broadcastHealth()"
    case Term.Apply.After_4_6_0(Term.Name("clusterIsDown"), argClause)
        if argClause.values.size >= 1 =>
      val nid = genExpr(argClause.values(0).asInstanceOf[Term])
      val thr = if argClause.values.size >= 2 then genExpr(argClause.values(1).asInstanceOf[Term]) else "8.0"
      s"Actor.clusterIsDown($nid, $thr)"
    // v1.23 — leader election (Bully)
    case Term.Apply.After_4_6_0(Term.Name("electLeader"), argClause)
        if argClause.values.isEmpty =>
      "Actor.electLeader()"
    case Term.Apply.After_4_6_0(Term.Name("currentLeader"), argClause)
        if argClause.values.isEmpty =>
      "Actor.currentLeader()"
    case Term.Apply.After_4_6_0(Term.Name("subscribeLeaderEvents"), argClause)
        if argClause.values.isEmpty =>
      "Actor.subscribeLeaderEvents()"
    case Term.Apply.After_4_6_0(Term.Name("setAutoReelect"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.setAutoReelect(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.23 — protocol switch + history
    case Term.Apply.After_4_6_0(Term.Name("useRaftLeaderElection"), argClause)
        if argClause.values.isEmpty =>
      "Actor.useRaftLeaderElection()"
    case Term.Apply.After_4_6_0(Term.Name("useExternalCoordinator"), argClause)
        if argClause.values.size == 4 =>
      val vs = argClause.values.map(v => genExpr(v.asInstanceOf[Term]))
      s"Actor.useExternalCoordinator(${vs(0)}, ${vs(1)}, ${vs(2)}, ${vs(3)})"
    case Term.Apply.After_4_6_0(Term.Name("leaderProtocol"), argClause)
        if argClause.values.isEmpty =>
      "Actor.leaderProtocol()"
    case Term.Apply.After_4_6_0(Term.Name("leaderHistory"), argClause)
        if argClause.values.isEmpty =>
      "Actor.leaderHistory()"
    // v1.23 — drain / rolling-restart
    case Term.Apply.After_4_6_0(Term.Name("setDraining"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.setDraining(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("isDraining"), argClause)
        if argClause.values.isEmpty =>
      "Actor.isDraining()"
    case Term.Apply.After_4_6_0(Term.Name("drainingPeers"), argClause)
        if argClause.values.isEmpty =>
      "Actor.drainingPeers()"
    case Term.Apply.After_4_6_0(Term.Name("subscribeDrainEvents"), argClause)
        if argClause.values.isEmpty =>
      "Actor.subscribeDrainEvents()"
    // v1.23 — cluster metrics aggregation
    case Term.Apply.After_4_6_0(Term.Name("clusterMetricSet"), argClause)
        if argClause.values.size == 2 =>
      val n0 = genExpr(argClause.values(0).asInstanceOf[Term])
      val v0 = genExpr(argClause.values(1).asInstanceOf[Term])
      s"Actor.clusterMetricSet($n0, $v0)"
    case Term.Apply.After_4_6_0(Term.Name("clusterMetricGet"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.clusterMetricGet(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("clusterMetricSum"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.clusterMetricSum(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("clusterMetricNames"), argClause)
        if argClause.values.isEmpty =>
      "Actor.clusterMetricNames()"
    case Term.Apply.After_4_6_0(Term.Name("subscribeMetricEvents"), argClause)
        if argClause.values.isEmpty =>
      "Actor.subscribeMetricEvents()"
    // v1.23 — auto-reconnect policy
    case Term.Apply.After_4_6_0(Term.Name("setReconnectPolicy"), argClause)
        if argClause.values.size == 2 =>
      val ini = genExpr(argClause.values(0).asInstanceOf[Term])
      val mx  = genExpr(argClause.values(1).asInstanceOf[Term])
      s"Actor.setReconnectPolicy($ini, $mx)"
    // v1.23 — periodic gossip re-discovery
    case Term.Apply.After_4_6_0(Term.Name("requestGossip"), argClause)
        if argClause.values.isEmpty =>
      "Actor.requestGossip()"
    // v1.23 — cluster configuration distribution
    case Term.Apply.After_4_6_0(Term.Name("clusterConfigSet"), argClause)
        if argClause.values.size == 2 =>
      val k0 = genExpr(argClause.values(0).asInstanceOf[Term])
      val v0 = genExpr(argClause.values(1).asInstanceOf[Term])
      s"Actor.clusterConfigSet($k0, $v0)"
    case Term.Apply.After_4_6_0(Term.Name("clusterConfigGet"), argClause)
        if argClause.values.size == 1 =>
      val k0 = genExpr(argClause.values(0).asInstanceOf[Term])
      s"Actor.clusterConfigGet($k0)"
    case Term.Apply.After_4_6_0(Term.Name("clusterConfigKeys"), argClause)
        if argClause.values.isEmpty =>
      "Actor.clusterConfigKeys()"
    case Term.Apply.After_4_6_0(Term.Name("subscribeConfigEvents"), argClause)
        if argClause.values.isEmpty =>
      "Actor.subscribeConfigEvents()"
    // v1.6.x — scheduled sends
    case Term.Apply.After_4_6_0(Term.Name("sendAfter"), argClause)
        if argClause.values.size == 3 =>
      val vs = argClause.values.map(v => genExpr(v.asInstanceOf[Term]))
      s"Actor.sendAfter(${vs(0)}, ${vs(1)}, ${vs(2)})"
    case Term.Apply.After_4_6_0(Term.Name("sendInterval"), argClause)
        if argClause.values.size == 3 =>
      val vs = argClause.values.map(v => genExpr(v.asInstanceOf[Term]))
      s"Actor.sendInterval(${vs(0)}, ${vs(1)}, ${vs(2)})"
    case Term.Apply.After_4_6_0(Term.Name("cancelTimer"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.cancelTimer(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.6.x — process introspection
    case Term.Apply.After_4_6_0(Term.Name("processInfo"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.processInfo(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.10 Generator — generator { () => body } / generator[T] { () => body } / suspend(v)
    case Term.Apply.After_4_6_0(
        Term.ApplyType.After_4_6_0(Term.Name("generator"), _) | Term.Name("generator"),
        argClause) if argClause.values.size == 1 =>
      s"_makeGenerator(${extractGenBody(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("suspend"), argClause)
        if argClause.values.size == 1 =>
      s"(yield ${genExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.9 Coroutine — coroutineCreate { () => body } / coroutineResume(co, in)
    case Term.Apply.After_4_6_0(
        Term.ApplyType.After_4_6_0(Term.Name("coroutineCreate"), _) | Term.Name("coroutineCreate"),
        argClause) if argClause.values.size == 1 =>
      s"_coroutineCreate(${extractCoroutineBody(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(
        Term.ApplyType.After_4_6_0(Term.Name("coroutineResume"), _) | Term.Name("coroutineResume"),
        argClause) if argClause.values.size == 2 =>
      val vs = argClause.values.map(v => genExpr(v.asInstanceOf[Term]))
      s"_coroutineResume(${vs(0)}, ${vs(1)})"

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

    case Term.Ascribe(inner, _) =>
      genExpr(inner)

    // throw expr — JS `throw` is a statement, wrap in an IIFE so it's
    // usable in expression position (e.g. inside a ternary). Mirrors the
    // Term.Throw lowering in genGenStatItem.
    case Term.Throw(expr) =>
      val errMsg = expr match
        case Term.New(init) =>
          init.argClauses.headOption.flatMap(_.values.headOption)
            .map(v => genExpr(v.asInstanceOf[Term]))
            .getOrElse("'error'")
        case Term.Apply.After_4_6_0(Term.Name("RuntimeException" | "Exception" | "Error"), argClause)
            if argClause.values.size == 1 =>
          genExpr(argClause.values.head.asInstanceOf[Term])
        case _ => genExpr(expr)
      s"(() => { throw new Error($errMsg); })()"

    // try { body } catch { case ... => ... } finally { ... }
    // Lowered to an IIFE so it works in expression position (val x = try …).
    // The catch handler reuses genCase, which produces an if/else chain that
    // returns from the IIFE; a trailing `throw errVar` rethrows when no case
    // matches (Scala semantics). Finally runs regardless.
    case Term.Try.After_4_9_9(bodyExpr, catchClauseOpt, finallyOpt) =>
      val bodyJs = genExpr(bodyExpr)
      val errVar = freshTmp()
      val cases  = catchClauseOpt.toList.flatMap(_.cases)
      val catchJs =
        if cases.isEmpty then s"throw $errVar;"
        else cases.map(c => genCase(errVar, c)).mkString(" else ") + s" else { throw $errVar; }"
      val finallyJs = finallyOpt.map { f => s" finally { ${genExpr(f)}; }" }.getOrElse("")
      s"(() => { try { return $bodyJs; } catch ($errVar) { $catchJs }$finallyJs })()"

    case other =>
      s"/* unsupported: ${other.productPrefix} */"

  private def genApply(app: Term.Apply): String =
    // f(regular)(using tc) — flatten all curried arg lists when the outermost
    // Apply carries a `using` clause, so the JS call passes all args at once.
    if app.argClause.mod.nonEmpty then
      def collectAllArgs(t: Term, acc: List[Term]): (Term, List[Term]) = t match
        case inner: Term.Apply => collectAllArgs(inner.fun, inner.argClause.values ++ acc)
        case other             => (other, acc)
      val (baseFun, allArgs) = collectAllArgs(app.fun, app.argClause.values)
      return s"_call(${genExpr(baseFun)}, ${allArgs.map(genExpr).mkString(", ")})"

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

    // direct[M] { stmts } — v1.8 do-notation sugar
    app.fun match
      case Term.ApplyType.After_4_6_0(Term.Name("direct"), typeArgClause) if app.argClause.values.size == 1 =>
        val typeArg = typeArgClause.values.headOption.getOrElse(Type.Name("?"))
        DirectTypeUtils.validateDirectTypeArg(typeArg)
        return (app.argClause.values.head match
          case block: Term.Block => genDirectBlock(block.stats)
          case single: Term      => genExpr(single)
          case null              => "undefined")
      case _ => ()

    // Named-arg reordering: when any arg is Term.Assign (name = expr),
    // look up the function's param order and reorder args to positional form.
    // Falls back to the original (potentially wrong) order when the function
    // is not in funcParamOrder (e.g. higher-order / imported functions).
    val rawArgs = app.argClause.values
    val hasNamedArgs = rawArgs.exists(_.isInstanceOf[Term.Assign])
    val argVals: List[String] =
      if !hasNamedArgs then rawArgs.map(genExpr)
      else
        // Extract the function name for param-order lookup.
        val fnNameOpt: Option[String] = app.fun match
          case Term.Name(n)             => Some(n)
          case Term.Select(_, Term.Name(n)) => Some(n)
          case _                        => None
        fnNameOpt.flatMap(funcParamOrder.get) match
          case Some(params) =>
            // Reorder: fill slots by name, then fill remaining with positionals.
            val slots = Array.fill[Option[String]](params.length)(None)
            rawArgs.foreach {
              case Term.Assign(Term.Name(n), rhs) =>
                val idx = params.indexOf(n)
                if idx >= 0 then slots(idx) = Some(genExpr(rhs))
              case _ => ()
            }
            val positionals = rawArgs.collect { case t if !t.isInstanceOf[Term.Assign] => genExpr(t) }.iterator
            for i <- slots.indices do
              if slots(i).isEmpty && positionals.hasNext then slots(i) = Some(positionals.next())
            // Emit up to the last filled slot; trailing Nones become undefined (JS default).
            val lastFilled = slots.lastIndexWhere(_.isDefined)
            if lastFilled < 0 then Nil
            else slots.take(lastFilled + 1).map(_.getOrElse("undefined")).toList
          case None =>
            // Function not in table — fall back: positionals first, then named by RHS only.
            rawArgs.map {
              case Term.Assign(_, rhs) => genExpr(rhs)
              case other               => genExpr(other)
            }
    app.fun match
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

    // User-defined interpolator (CPS path): _ext_StringContext_prefix(_sc([...]), [...])
    case Term.Interpolate(Term.Name(prefix), parts, args) =>
      bindArgsCps(args.map(_.asInstanceOf[Term])) { vs =>
        val partsJs = parts.map { p =>
          val s = p.asInstanceOf[Lit.String].value
          "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }.mkString("[", ", ", "]")
        val argsJs = vs.mkString("[", ", ", "]")
        s"_ext_StringContext_$prefix(_sc($partsJs), $argsJs)"
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
      val awaitPrefix = if usesRunAsyncParallel then "await " else ""
      s"${awaitPrefix}_runAsyncParallel(() => ${genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])})"

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
      val awaitPrefix = if usesRunActors then "await " else ""
      s"${awaitPrefix}_runActors(() => ${genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])})"

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
    case Term.Apply.After_4_6_0(Term.Name("spawn_link"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.spawn_link(${genCpsExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("spawnBounded"), argClause)
        if argClause.values.size == 3 =>
      val capJs      = genExpr(argClause.values(0).asInstanceOf[Term])
      val overflowJs = genExpr(argClause.values(1).asInstanceOf[Term])
      val thunkJs    = genCpsExpr(argClause.values(2).asInstanceOf[Term])
      s"Actor.spawnBounded($capJs, $overflowJs, $thunkJs)"
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
    // v1.6 Phase 3 — distributed node primitives (inside CPS body)
    case Term.Apply.After_4_6_0(Term.Name("startNode"), argClause)
        if argClause.values.size >= 1 =>
      val nodeId = genExpr(argClause.values(0).asInstanceOf[Term])
      val url    = if argClause.values.size >= 2 then genExpr(argClause.values(1).asInstanceOf[Term]) else "\"\""
      s"Actor.startNode($nodeId, $url)"
    case Term.Apply.After_4_6_0(Term.Name("connectNode"), argClause)
        if argClause.values.size >= 1 =>
      val url   = genExpr(argClause.values(0).asInstanceOf[Term])
      val token = if argClause.values.size >= 2 then genExpr(argClause.values(1).asInstanceOf[Term]) else "\"\""
      s"Actor.connectNode($url, $token)"
    case Term.Apply.After_4_6_0(Term.Name("joinCluster"), argClause)
        if argClause.values.size >= 1 =>
      val seeds = genExpr(argClause.values(0).asInstanceOf[Term])
      val token = if argClause.values.size >= 2 then genExpr(argClause.values(1).asInstanceOf[Term]) else "\"\""
      s"Actor.joinCluster($seeds, $token)"
    case Term.Apply.After_4_6_0(Term.Name("register"), argClause)
        if argClause.values.size == 2 =>
      s"Actor.register(${genExpr(argClause.values(0).asInstanceOf[Term])}, ${genExpr(argClause.values(1).asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("whereis"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.whereis(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("globalRegister"), argClause)
        if argClause.values.size == 2 =>
      s"Actor.globalRegister(${genExpr(argClause.values(0).asInstanceOf[Term])}, ${genExpr(argClause.values(1).asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("globalWhereis"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.globalWhereis(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.23 — cluster visibility
    case Term.Apply.After_4_6_0(Term.Name("clusterMembers"), argClause)
        if argClause.values.isEmpty =>
      "Actor.clusterMembers()"
    case Term.Apply.After_4_6_0(Term.Name("subscribeClusterEvents"), argClause)
        if argClause.values.isEmpty =>
      "Actor.subscribeClusterEvents()"
    // v1.23 — phi-accrual failure detector
    case Term.Apply.After_4_6_0(Term.Name("phiOf"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.phiOf(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("isSuspect"), argClause)
        if argClause.values.size >= 1 =>
      val nid = genExpr(argClause.values(0).asInstanceOf[Term])
      val thr = if argClause.values.size >= 2 then genExpr(argClause.values(1).asInstanceOf[Term]) else "8.0"
      s"Actor.isSuspect($nid, $thr)"
    // v1.23 — local node identity + phi vector
    case Term.Apply.After_4_6_0(Term.Name("selfNode"), argClause)
        if argClause.values.isEmpty =>
      "Actor.selfNode()"
    case Term.Apply.After_4_6_0(Term.Name("clusterHealth"), argClause)
        if argClause.values.isEmpty =>
      "Actor.clusterHealth()"
    // v1.23 — cluster-wide failure detector
    case Term.Apply.After_4_6_0(Term.Name("broadcastHealth"), argClause)
        if argClause.values.isEmpty =>
      "Actor.broadcastHealth()"
    case Term.Apply.After_4_6_0(Term.Name("clusterIsDown"), argClause)
        if argClause.values.size >= 1 =>
      val nid = genExpr(argClause.values(0).asInstanceOf[Term])
      val thr = if argClause.values.size >= 2 then genExpr(argClause.values(1).asInstanceOf[Term]) else "8.0"
      s"Actor.clusterIsDown($nid, $thr)"
    // v1.23 — leader election (Bully)
    case Term.Apply.After_4_6_0(Term.Name("electLeader"), argClause)
        if argClause.values.isEmpty =>
      "Actor.electLeader()"
    case Term.Apply.After_4_6_0(Term.Name("currentLeader"), argClause)
        if argClause.values.isEmpty =>
      "Actor.currentLeader()"
    case Term.Apply.After_4_6_0(Term.Name("subscribeLeaderEvents"), argClause)
        if argClause.values.isEmpty =>
      "Actor.subscribeLeaderEvents()"
    case Term.Apply.After_4_6_0(Term.Name("setAutoReelect"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.setAutoReelect(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.23 — protocol switch + history
    case Term.Apply.After_4_6_0(Term.Name("useRaftLeaderElection"), argClause)
        if argClause.values.isEmpty =>
      "Actor.useRaftLeaderElection()"
    case Term.Apply.After_4_6_0(Term.Name("useExternalCoordinator"), argClause)
        if argClause.values.size == 4 =>
      val vs = argClause.values.map(v => genExpr(v.asInstanceOf[Term]))
      s"Actor.useExternalCoordinator(${vs(0)}, ${vs(1)}, ${vs(2)}, ${vs(3)})"
    case Term.Apply.After_4_6_0(Term.Name("leaderProtocol"), argClause)
        if argClause.values.isEmpty =>
      "Actor.leaderProtocol()"
    case Term.Apply.After_4_6_0(Term.Name("leaderHistory"), argClause)
        if argClause.values.isEmpty =>
      "Actor.leaderHistory()"
    // v1.23 — drain / rolling-restart
    case Term.Apply.After_4_6_0(Term.Name("setDraining"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.setDraining(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("isDraining"), argClause)
        if argClause.values.isEmpty =>
      "Actor.isDraining()"
    case Term.Apply.After_4_6_0(Term.Name("drainingPeers"), argClause)
        if argClause.values.isEmpty =>
      "Actor.drainingPeers()"
    case Term.Apply.After_4_6_0(Term.Name("subscribeDrainEvents"), argClause)
        if argClause.values.isEmpty =>
      "Actor.subscribeDrainEvents()"
    // v1.23 — cluster metrics aggregation
    case Term.Apply.After_4_6_0(Term.Name("clusterMetricSet"), argClause)
        if argClause.values.size == 2 =>
      val n0 = genExpr(argClause.values(0).asInstanceOf[Term])
      val v0 = genExpr(argClause.values(1).asInstanceOf[Term])
      s"Actor.clusterMetricSet($n0, $v0)"
    case Term.Apply.After_4_6_0(Term.Name("clusterMetricGet"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.clusterMetricGet(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("clusterMetricSum"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.clusterMetricSum(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("clusterMetricNames"), argClause)
        if argClause.values.isEmpty =>
      "Actor.clusterMetricNames()"
    case Term.Apply.After_4_6_0(Term.Name("subscribeMetricEvents"), argClause)
        if argClause.values.isEmpty =>
      "Actor.subscribeMetricEvents()"
    // v1.23 — auto-reconnect policy
    case Term.Apply.After_4_6_0(Term.Name("setReconnectPolicy"), argClause)
        if argClause.values.size == 2 =>
      val ini = genExpr(argClause.values(0).asInstanceOf[Term])
      val mx  = genExpr(argClause.values(1).asInstanceOf[Term])
      s"Actor.setReconnectPolicy($ini, $mx)"
    // v1.23 — periodic gossip re-discovery
    case Term.Apply.After_4_6_0(Term.Name("requestGossip"), argClause)
        if argClause.values.isEmpty =>
      "Actor.requestGossip()"
    // v1.23 — cluster configuration distribution
    case Term.Apply.After_4_6_0(Term.Name("clusterConfigSet"), argClause)
        if argClause.values.size == 2 =>
      val k0 = genExpr(argClause.values(0).asInstanceOf[Term])
      val v0 = genExpr(argClause.values(1).asInstanceOf[Term])
      s"Actor.clusterConfigSet($k0, $v0)"
    case Term.Apply.After_4_6_0(Term.Name("clusterConfigGet"), argClause)
        if argClause.values.size == 1 =>
      val k0 = genExpr(argClause.values(0).asInstanceOf[Term])
      s"Actor.clusterConfigGet($k0)"
    case Term.Apply.After_4_6_0(Term.Name("clusterConfigKeys"), argClause)
        if argClause.values.isEmpty =>
      "Actor.clusterConfigKeys()"
    case Term.Apply.After_4_6_0(Term.Name("subscribeConfigEvents"), argClause)
        if argClause.values.isEmpty =>
      "Actor.subscribeConfigEvents()"
    // v1.6.x — scheduled sends (inside CPS body)
    case Term.Apply.After_4_6_0(Term.Name("sendAfter"), argClause)
        if argClause.values.size == 3 =>
      val vs = argClause.values.map(v => genExpr(v.asInstanceOf[Term]))
      s"Actor.sendAfter(${vs(0)}, ${vs(1)}, ${vs(2)})"
    case Term.Apply.After_4_6_0(Term.Name("sendInterval"), argClause)
        if argClause.values.size == 3 =>
      val vs = argClause.values.map(v => genExpr(v.asInstanceOf[Term]))
      s"Actor.sendInterval(${vs(0)}, ${vs(1)}, ${vs(2)})"
    case Term.Apply.After_4_6_0(Term.Name("cancelTimer"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.cancelTimer(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.6.x — process introspection (inside CPS body)
    case Term.Apply.After_4_6_0(Term.Name("processInfo"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.processInfo(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.10 Generator inside CPS body — generator { } / generator[T] { }
    case Term.Apply.After_4_6_0(
        Term.ApplyType.After_4_6_0(Term.Name("generator"), _) | Term.Name("generator"),
        argClause) if argClause.values.size == 1 =>
      val bodyJs = argClause.values.head match
        case Term.Function.After_4_6_0(_, body) =>
          genGeneratorBody(body.asInstanceOf[Term])
        case other => s"function*() { return ${genExpr(other.asInstanceOf[Term])}; }"
      s"_makeGenerator($bodyJs)"
    case Term.Apply.After_4_6_0(Term.Name("suspend"), argClause)
        if argClause.values.size == 1 =>
      s"(yield ${genExpr(argClause.values.head.asInstanceOf[Term])})"

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

    case Pat.Typed(inner, tpe) =>
      // Emit a type-test guard for union-type narrowing: `case s: String =>`.
      // Map the declared type name to a JS typeof / instanceof check.
      val typeName = tpe match
        case Type.Name(n)   => n
        case ta: Type.Apply => ta.tpe match { case Type.Name(n) => n; case _ => "" }
        case _              => ""
      val typeCond = typeName match
        case "String"  => s"(typeof $scrutVar === 'string')"
        case "Int" | "Long" | "Double" | "Float" | "Number" =>
          s"(typeof $scrutVar === 'number')"
        case "Boolean" => s"(typeof $scrutVar === 'boolean')"
        case ""        => "true"    // unknown type — fall through
        case _         => s"($scrutVar && $scrutVar._type === '$typeName')"
      val (innerCond, bindings) = genPattern(scrutVar, inner)
      val cond =
        if typeCond == "true" then innerCond
        else if innerCond == "true" then typeCond
        else s"$typeCond && $innerCond"
      (cond, bindings)

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

    // @ binder: `xs @ pattern` — bind `xs` to the whole scrutinee, then match `pattern`
    case Pat.Bind(lhs: Pat.Var, rhs) =>
      val (cond, bindings) = genPattern(scrutVar, rhs)
      (cond, (lhs.name.value -> scrutVar) :: bindings)

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

  // ─── direct[M] { ... } — v1.8 do-notation ────────────────────────

  private def checkDirectBlockStatics(stats: List[Stat]): Unit =
    def isNestedDirect(t: Tree): Boolean = t match
      case app: Term.Apply =>
        app.fun match
          case Term.ApplyType.After_4_6_0(Term.Name("direct"), _) => true
          case _ => false
      case _ => false
    def go(t: Tree): Unit = t match
      case _: Term.Return =>
        throw new RuntimeException("'return' inside a direct block escapes the flatMap chain — for early failure use the monad's zero (None, Nil, Left(err), …) instead")
      case _ if isNestedDirect(t) => ()
      case _: Defn.Def | _: Term.Function => ()
      case other => other.children.foreach(go)
    stats.foreach(go)

  private def genDirectBlock(stats: List[Stat]): String =
    checkDirectBlockStatics(stats)
    val expanded = DirectAnorm.expand(stats)
    if expanded.isEmpty then "undefined"
    else
      val varNames: Set[String] = expanded.collect {
        case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, _) => n.value
      }.toSet
      def go(remaining: List[Stat]): String = remaining match
        case Nil => "undefined"
        case List(t: Term)  => genExpr(t)
        case List(other)    => s"(() => { ${genStatInline(other)} return undefined; })()"
        case Term.Assign(Term.Name(x), rhs) :: rest if varNames.contains(x) =>
          s"(() => { $x = ${genExpr(rhs)}; return ${go(rest)}; })()"
        case Term.Assign(Term.Name(x), rhs) :: rest =>
          s"_dispatch(${genExpr(rhs)}, 'flatMap', [($x) => ${go(rest)}])"
        case Defn.Val(_, List(_: Pat.Wildcard), _, rhs) :: rest =>
          s"_dispatch(${genExpr(rhs)}, 'flatMap', [(_) => ${go(rest)}])"
        case Defn.Val(_, List(Pat.Var(n)), _, rhs) :: rest =>
          s"(() => { const ${n.value} = ${genExpr(rhs)}; return ${go(rest)}; })()"
        case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs) :: rest =>
          s"(() => { let ${n.value} = ${genExpr(rhs)}; return ${go(rest)}; })()"
        case (t: Term) :: rest =>
          s"(() => { ${genExpr(t)}; return ${go(rest)}; })()"
        case _ :: rest => go(rest)
      go(expanded)

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
    // @ binder: bind the whole scrutinee to the name, then destructure rhs
    case Pat.Bind(lhs: Pat.Var, rhs) =>
      val rhsBindings = genForPatBinding(rhs, scrutVar)
      val lhsBinding  = if lhs.name.value == scrutVar then "" else s"const ${lhs.name.value} = $scrutVar;"
      s"$lhsBinding $rhsBindings".trim
    case _ => ""

  private def mapName(name: String): String = name match
    case "println" => "_println"
    case "print"   => "_print"
    case "Some"    => "_Some"
    case "None"    => "_None"
    case other     => paramRenames.getOrElse(other, other)

  private def withParamRenames[A](renames: Map[String, String])(f: => A): A =
    paramRenames ++= renames
    try f finally paramRenames --= renames.keys

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
