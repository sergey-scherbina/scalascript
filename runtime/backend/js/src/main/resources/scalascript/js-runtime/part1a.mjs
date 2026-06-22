
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
  if (Array.isArray(fn) || _isMap(fn)) return _dispatch(fn, 'apply', args);
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

// i18n runtime — _i18n(key) / setLocale(code) / wc(tag, component, ...args)
let _i18nLocale = 'en';
let _i18nTable  = {};
function setLocale(code) { _i18nLocale = code; }
function _i18n(key) {
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
  if (Array.isArray(v)) {
    if (v.length === 0) return 'Any';
    return Array.isArray(v[0]) ? 'List' : _ssc_typeOf(v[0]);
  }
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
// ── arch-meta-v2-p5 Track A (A2) — runtime Mirror metadata (JS) ────────────
// Mirrors the JVM `_SscMirror` / interpreter `DerivesRuntime.mirrorForType`.
function _ssc_mkMirror(label, elemLabels, elemTypes, variants, isProduct, fromProduct, ordinal) {
  return {
    label: label, elemLabels: elemLabels, elemTypes: elemTypes, fields: elemLabels,
    variants: variants, isProduct: isProduct, isSum: !isProduct,
    fromProduct: fromProduct, ordinal: ordinal
  };
}
// Register a LAZY given factory under `key`: the factory runs (once) on first
// access, deferring any reference to a not-yet-initialised `const` (e.g. an
// `object TC` emitted later in source order) until the summon site runs.
function _ssc_def_given(key, factory) {
  var done = false, val;
  Object.defineProperty(_ssc_givens, key, {
    configurable: true,
    get: function() { if (!done) { val = factory(); done = true; } return val; }
  });
}
// ── arch-meta-v2-p5 Track A (A1c) — structural derives helpers (JS) ────────
// Mirror the interpreter's DerivesRuntime.structuralShow / structuralCompare.
// `Show` renders `TypeName(field=value, ...)` WITH field names (unlike `_show`,
// which omits them).  Eq reuses `_eq` (deep structural equality).
function _ssc_structShow(v) {
  if (v && typeof v === 'object' && v._type && v._type[0] !== '_' &&
      !Array.isArray(v) && !v._lazy) {
    var fs = Object.entries(v).filter(function(e) { return e[0] !== '_type' && e[0] !== '_tag'; });
    if (!fs.length) return v._type;
    return v._type + '(' + fs.map(function(e) { return e[0] + '=' + _ssc_structShow(e[1]); }).join(', ') + ')';
  }
  return _show(v);
}
function _ssc_structCompare(a, b) {
  if (typeof a === 'number'  && typeof b === 'number')  return a < b ? -1 : a > b ? 1 : 0;
  if (typeof a === 'string'  && typeof b === 'string')  return a < b ? -1 : a > b ? 1 : 0;
  if (typeof a === 'boolean' && typeof b === 'boolean') return (a === b) ? 0 : (a ? 1 : -1);
  if (a && b && typeof a === 'object' && a._type && a._type === b._type) {
    var ks = Object.keys(a).filter(function(k) { return k !== '_type' && k !== '_tag'; });
    for (var i = 0; i < ks.length; i++) { var r = _ssc_structCompare(a[ks[i]], b[ks[i]]); if (r !== 0) return r; }
    return 0;
  }
  return 0;
}
function _ssc_structHash(v) {
  var s = _ssc_structShow(v), h = 0;
  for (var i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0;
  return h;
}
// Partial application of a curried def `def add(a)(b)`: when `add(3)` is called with
// fewer args than the flattened arity, return a function that accumulates the rest.
// `add(1)(2)` (full) still calls `add(1, 2)` directly. (jvmgen-js-curried-partial.)
function _curry(fn, arity, args0) {
  var a0 = Array.prototype.slice.call(args0);
  return function() {
    var all = a0.concat(Array.prototype.slice.call(arguments));
    return all.length >= arity ? fn.apply(null, all) : _curry(fn, arity, all);
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

