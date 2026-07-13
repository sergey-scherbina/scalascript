
// ── Char (interp-js-string-map-nonchar) ─────────────────────────────────────
// JS has no distinct Char type, so a char produced by iterating a String
// (`s.map`/`charAt`/`head`/…) is boxed as a `_Char(code)`. `valueOf` returns the
// code point (so `c + 1`, `c < 'z'`, numeric coercion behave like Scala's Int
// promotion) and `toString` returns the 1-char string (so `"x" + c`, `_show(c)`,
// and passing `c` to a native String method via toString-coercion all work).
// Prototype methods are non-enumerable, so `_eq`'s generic object path compares
// two `_Char`s by their only own key, `__c`. The distinction lets `String.map`
// tell a Char result (→ String) from a non-Char result (→ Seq).
class _Char {
  constructor(code) { this.__c = code; }
  toString() { return String.fromCharCode(this.__c); }
  valueOf()  { return this.__c; }
}
function _char(code) { return new _Char(code); }
function _isChar(x)  { return x instanceof _Char; }
function _charCodeOrNull(x) {
  if (x instanceof _Char) return x.__c;
  if (typeof x === 'number') return x;
  if (typeof x === 'string' && [...x].length === 1) return x.codePointAt(0);
  return null;
}

// ── Exact numerics (v1.64): BigInt is native; Decimal is BigInt-backed ──────
// A Decimal is { _type:'_Decimal', u: bigint, s: int } with value = u * 10^-s.
const RoundingMode = { UP:'UP', DOWN:'DOWN', CEILING:'CEILING', FLOOR:'FLOOR',
  HALF_UP:'HALF_UP', HALF_DOWN:'HALF_DOWN', HALF_EVEN:'HALF_EVEN', UNNECESSARY:'UNNECESSARY' };
function _pow10(n) { return 10n ** BigInt(n); }
function _mkDec(u, s) { return { _type:'_Decimal', u: u, s: s }; }
function Decimal(a, b) {
  if (b !== undefined) return _mkDec(BigInt(a), Number(b));
  if (typeof a === 'bigint') return _mkDec(a, 0);
  if (a && a._type === '_Decimal') return a;
  if (typeof a === 'number') {
    if (!Number.isInteger(a)) throw new Error("Decimal: refusing to build from a non-integer Number (inexact). Use Decimal(\"…\").");
    return _mkDec(BigInt(a), 0);
  }
  if (typeof a === 'string') {
    const m = /^([+-]?)(\d*)(?:\.(\d*))?$/.exec(a.trim());
    if (!m) throw new Error("Decimal: not a valid number: '" + a + "'");
    const frac = m[3] || '', digits = ((m[2]||'0') + frac).replace(/^0+(?=\d)/, '');
    const u = BigInt(digits === '' ? '0' : digits) * (m[1] === '-' ? -1n : 1n);
    return _mkDec(u, frac.length);
  }
  throw new Error("Decimal: cannot build from " + a);
}
const BigDecimal = Decimal;
function _decShow(d) {
  const neg = d.u < 0n;
  let digits = (neg ? -d.u : d.u).toString();
  if (d.s <= 0) return (neg ? '-' : '') + digits + '0'.repeat(-d.s);
  if (digits.length <= d.s) digits = '0'.repeat(d.s - digits.length + 1) + digits;
  const point = digits.length - d.s;
  return (neg ? '-' : '') + digits.slice(0, point) + '.' + digits.slice(point);
}
function _decAlign(x, y) {
  if (x.s === y.s) return [x.u, y.u, x.s];
  if (x.s > y.s)  return [x.u, y.u * _pow10(x.s - y.s), x.s];
  return [x.u * _pow10(y.s - x.s), y.u, y.s];
}
function _decAdd(x, y) { const [a,b,s] = _decAlign(x,y); return _mkDec(a+b, s); }
function _decSub(x, y) { const [a,b,s] = _decAlign(x,y); return _mkDec(a-b, s); }
function _decMul(x, y) { return _mkDec(x.u * y.u, x.s + y.s); }
function _decCmp(x, y) { const [a,b] = _decAlign(x,y); return a < b ? -1 : a > b ? 1 : 0; }
// Round num/den to an integer per a java.math.RoundingMode name.
function _divRound(num, den, mode) {
  const neg = (num < 0n) !== (den < 0n);
  let n = num < 0n ? -num : num, d = den < 0n ? -den : den;
  let q = n / d; const r = n % d;
  if (r !== 0n) {
    const twice = r * 2n; let up = false;
    switch (mode) {
      case 'UP': up = true; break;
      case 'DOWN': up = false; break;
      case 'CEILING': up = !neg; break;
      case 'FLOOR': up = neg; break;
      case 'HALF_UP': up = twice >= d; break;
      case 'HALF_DOWN': up = twice > d; break;
      case 'HALF_EVEN': up = twice > d || (twice === d && (q % 2n) === 1n); break;
      case 'UNNECESSARY': throw new Error('Rounding necessary');
      default: throw new Error('bad RoundingMode: ' + mode);
    }
    if (up) q += 1n;
  }
  return neg ? -q : q;
}
function _decSetScale(d, scale, mode) {
  if (scale >= d.s) return _mkDec(d.u * _pow10(scale - d.s), scale);
  return _mkDec(_divRound(d.u, _pow10(d.s - scale), mode), scale);
}
function _decDivide(x, y, scale, mode) {
  const e = scale - x.s + y.s;
  const num = e >= 0 ? x.u * _pow10(e) : x.u;
  const den = e >= 0 ? y.u : y.u * _pow10(-e);
  return _mkDec(_divRound(num, den, mode), scale);
}
// Bare Decimal `/` — HALF_EVEN to 34 significant digits (≈ Java DECIMAL128),
// trailing zeros trimmed. Prefer the explicit divide(scale, mode) for money.
function _decDivBare(x, y) {
  if (y.u === 0n) throw new Error('Decimal division by zero');
  // pick a scale giving ~34 significant digits in the result
  const xd = (x.u < 0n ? -x.u : x.u).toString().length;
  const yd = (y.u < 0n ? -y.u : y.u).toString().length;
  const scale = Math.max(34 - xd + yd + (x.s - y.s), x.s - y.s, 0) + 1;
  let r = _decDivide(x, y, scale, 'HALF_EVEN');
  // trim trailing zeros (keep at least scale 0)
  while (r.s > 0 && r.u % 10n === 0n) r = _mkDec(r.u / 10n, r.s - 1);
  return r;
}
// Coerce a JS value to BigInt for mixed BigInt arithmetic.
function _toBig(v) {
  if (typeof v === 'bigint') return v;
  if (typeof v === 'number' && Number.isInteger(v)) return BigInt(v);
  throw new Error('cannot use ' + _show(v) + ' as an integer');
}
// v1-js-long-precision-and-bitops: exact 64-bit bitwise/shift ops. ssc `Int`/`Long`
// are 64-bit, but native JS `& | ^ << >> >>>` are 32-bit and mask shift counts mod 32.
// Coerce both operands to BigInt, apply the op, and mask to signed 64 bits with
// asIntN(64) — matching the interpreter/JVM. `>>>` is a logical (zero-fill) shift over
// the 64-bit width; shift counts wrap mod 64 (JVM Long-shift semantics). The result is a
// BigInt (a Long), so downstream arithmetic/comparison stays exact via _arith.
function _bit(op, a, b) {
  // `& | ^ << >> >>>` double as user operator/method names — most notably the
  // parser-combinator `|` (PChoice) and `&`. When the receiver isn't a
  // bit-coercible number/bigint, treat the operator as a method call and dispatch
  // through the extension registry instead of forcing a numeric bit op (which
  // would throw "cannot use … as an integer"). Int/Long bitwise is unchanged.
  // (js-parser-choice-pipe-bitwise.)
  if (typeof a !== 'number' && typeof a !== 'bigint') return _dispatch(a, op, [b]);
  const x = _toBig(a);
  switch (op) {
    case '&':   return BigInt.asIntN(64, x & _toBig(b));
    case '|':   return BigInt.asIntN(64, x | _toBig(b));
    case '^':   return BigInt.asIntN(64, x ^ _toBig(b));
    case '<<':  return BigInt.asIntN(64, x << (_toBig(b) & 63n));
    case '>>':  return BigInt.asIntN(64, x >> (_toBig(b) & 63n));
    case '>>>': return BigInt.asIntN(64, BigInt.asUintN(64, x) >> (_toBig(b) & 63n));
    default: throw new Error('bad _bit op: ' + op);
  }
}
// `.toInt` (32-bit Int wrap) on a receiver that may be a plain number OR a BigInt (Long).
// `x | 0` throws on a BigInt, so branch: BigInt → signed 32-bit truncation via asIntN(32).
function _toI32(v) {
  return typeof v === 'bigint' ? Number(BigInt.asIntN(32, v)) : (v | 0);
}
// Coerce to Decimal; integer-valued Numbers/BigInt widen, fractional Numbers error.
function _toDec(v) {
  if (v && v._type === '_Decimal') return v;
  if (typeof v === 'bigint') return Decimal(v);
  if (typeof v === 'number') {
    if (!Number.isInteger(v)) throw new Error('cannot mix Decimal and a fractional Number — convert explicitly');
    return Decimal(v);
  }
  throw new Error('cannot use ' + _show(v) + ' as a Decimal');
}
// Arithmetic/comparison dispatch for operands that may be BigInt/Decimal.
// Emitted by codegen only when operands are not both statically Int.
// Structural (deep) equality — matches the interpreter/JVM `==` for case classes, tuples, Lists.
// JS `===` is reference equality, so `P(1) === P(1)` is false for two distinct instances.
// (jsgen-structural-equality.)
function _eq(a, b) {
  if (a === b) return true;
  // A boxed `_Char` equals another `_Char` with the same code, an Int with the
  // same code point (the interp allows `CharV == IntV`), or a 1-char String
  // literal (char literals stay JS strings). (interp-js-string-map-nonchar.)
  const _aC = a instanceof _Char, _bC = b instanceof _Char;
  if (_aC || _bC) {
    const av = _charCodeOrNull(a), bv = _charCodeOrNull(b);
    return av !== null && bv !== null && av === bv;
  }
  if (a == null || b == null) return false;
  if (typeof a !== 'object' || typeof b !== 'object') return a === b;
  const aArr = Array.isArray(a), bArr = Array.isArray(b);
  if (aArr !== bArr) return false;
  if (aArr) {
    if (a.length !== b.length) return false;
    for (let i = 0; i < a.length; i++) if (!_eq(a[i], b[i])) return false;
    return true;
  }
  if (a._type !== b._type) return false;
  const ka = Object.keys(a), kb = Object.keys(b);
  if (ka.length !== kb.length) return false;
  for (const k of ka) if (!_eq(a[k], b[k])) return false;
  return true;
}
// Set constructor → a deduplicated array (structural dedup), so array `_dispatch` methods apply.
function _setOf(...xs) {
  const out = [];
  for (const x of xs) if (!out.some(y => _eq(y, x))) out.push(x);
  return out;
}
function _arith(op, a, b) {
  // String concatenation keeps priority (matches the interpreter's `a + show(b)`).
  if (op === '+' && (typeof a === 'string' || typeof b === 'string'))
    return (typeof a === 'string' ? a : _show(a)) + (typeof b === 'string' ? b : _show(b));
  if ((a instanceof _Char || b instanceof _Char) &&
      (op === '<' || op === '>' || op === '<=' || op === '>=')) {
    const x = _charCodeOrNull(a), y = _charCodeOrNull(b);
    if (x !== null && y !== null) {
      switch (op) {
        case '<': return x < y; case '>': return x > y;
        case '<=': return x <= y; case '>=': return x >= y;
      }
    }
  }
  const aDec = a && a._type === '_Decimal', bDec = b && b._type === '_Decimal';
  if (aDec || bDec) {
    const x = _toDec(a), y = _toDec(b);
    switch (op) {
      case '+': return _decAdd(x,y); case '-': return _decSub(x,y); case '*': return _decMul(x,y);
      case '/': return _decDivBare(x,y);
      case '<': return _decCmp(x,y) < 0;  case '>': return _decCmp(x,y) > 0;
      case '<=': return _decCmp(x,y) <= 0; case '>=': return _decCmp(x,y) >= 0;
      case '==': return _decCmp(x,y) === 0; case '!=': return _decCmp(x,y) !== 0;
    }
  }
  if (typeof a === 'bigint' || typeof b === 'bigint') {
    const x = _toBig(a), y = _toBig(b);
    switch (op) {
      case '+': return x+y; case '-': return x-y; case '*': return x*y; case '/': return x/y; case '%': return x%y;
      case '<': return x<y; case '>': return x>y; case '<=': return x<=y; case '>=': return x>=y;
      case '==': return x===y; case '!=': return x!==y;
    }
  }
  switch (op) {
    case '+': return a + b; case '-': return a - b; case '*': return a * b; case '/': return a / b; case '%': return a % b;
    case '<': return a < b; case '>': return a > b; case '<=': return a <= b; case '>=': return a >= b;
    // Structural equality for objects/arrays (case classes, tuples, Lists); `===` for primitives.
    case '==': return _eq(a, b); case '!=': return !_eq(a, b);
  }
  throw new Error('bad _arith op: ' + op);
}

function _show(v) {
  if (v === undefined) return '()';
  if (v === null) return 'null';
  if (typeof v === 'boolean') return String(v);
  if (typeof v === 'number') return String(v);
  if (typeof v === 'bigint') return v.toString();
  if (v && v._type === '_Decimal') return _decShow(v);
  if (typeof v === 'string') return v;
  if (v instanceof _Char) return v.toString();
  // A lazy list renders `LazyList(<not computed>)` until forced — matches interp/JVM toString.
  if (v && v._lazy) return 'LazyList(<not computed>)';
  if (Array.isArray(v)) {
    if (v._isTuple) return '(' + v.map(_show).join(', ') + ')';
    return (v._kind || 'List') + '(' + v.map(_show).join(', ') + ')';
  }
  if (_isMap(v)) {
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
      k !== '_type' && k !== '_tag' && k !== '_path' && k !== '_steps' && k !== '_variant');
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
function Left(v) { return {_type: 'Left', value: v}; }
function Right(v) { return {_type: 'Right', value: v}; }

// std.bench — Bench.opaque identity (anti-folding helper for Rust target;
// V8 doesn't constant-fold pure-arith loops the way LLVM does, so identity is fine).
const Bench = { opaque: (x) => x };

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

// Tag a backing array with its real Scala collection type for display (`Vector(1, 2, 3)` vs the
// default `List(...)`). Non-enumerable so it never leaks into JSON.stringify / spread / structural
// `_eq`. The tag is set at construction; ops that rebuild the array (`.map`) drop it (shown as List)
// — a known, documented JS-display limitation; the interpreter/JVM keep it through ops. (collection-real-type.)
function _seqKind(kind, arr) {
  Object.defineProperty(arr, '_kind', { value: kind, writable: true, configurable: true, enumerable: false });
  return arr;
}

function List(...args) { return [...args]; }
List.fill     = (n) => (elem) => Array.from({length: n}, () => elem);
List.tabulate = (n) => (f)    => Array.from({length: n}, (_, i) => f(i));
List.range    = (from, until, step=1) => { const r=[]; for(let i=from;i<until;i+=step) r.push(i); return r; };
List.empty    = [];
const Nil = [];

// ── LazyList — a real lazy list (thunk-based memoised cons), so infinite streams work and only
// the demanded prefix is forced. `tail` is computed once and memoised. (lazylist-all-backends.)
const _LZ_NIL = { _lazy: true, _nil: true };
function _lzCons(head, tailThunk) {
  let memo, forced = false;
  return { _lazy: true, _nil: false, head: head,
           tail: () => { if (!forced) { memo = tailThunk(); forced = true; } return memo; } };
}
function _lzFromArray(arr) { let r = _LZ_NIL; for (let i = arr.length - 1; i >= 0; i--) { const t = r; r = _lzCons(arr[i], () => t); } return r; }
function _lzFrom(n)        { return _lzCons(n, () => _lzFrom(n + 1)); }            // infinite n, n+1, …
function _lzIterate(seed, f) { return _lzCons(seed, () => _lzIterate(f(seed), f)); }  // infinite
function _lzContinually(f) { return _lzCons(f(), () => _lzContinually(f)); }       // f re-evaluated lazily
function _lzRange(a, b, step) { step = step || 1; return (step > 0 ? a >= b : a <= b) ? _LZ_NIL : _lzCons(a, () => _lzRange(a + step, b, step)); }
function _lzMap(ll, f)     { return ll._nil ? _LZ_NIL : _lzCons(f(ll.head), () => _lzMap(ll.tail(), f)); }
function _lzFilter(ll, p, neg) { while (!ll._nil) { if (p(ll.head) !== !!neg) { const cur = ll; return _lzCons(cur.head, () => _lzFilter(cur.tail(), p, neg)); } ll = ll.tail(); } return _LZ_NIL; }
function _lzTake(ll, k)    { return (k <= 0 || ll._nil) ? _LZ_NIL : _lzCons(ll.head, () => _lzTake(ll.tail(), k - 1)); }
function _lzDrop(ll, k)    { while (k > 0 && !ll._nil) { ll = ll.tail(); k--; } return ll; }
function _lzTakeWhile(ll, p) { return (ll._nil || !p(ll.head)) ? _LZ_NIL : _lzCons(ll.head, () => _lzTakeWhile(ll.tail(), p)); }
function _lzDropWhile(ll, p) { while (!ll._nil && p(ll.head)) ll = ll.tail(); return ll; }
function _lzZipIdx(ll, i)  { i = i || 0; if (ll._nil) return _LZ_NIL; const t = [ll.head, i]; t._isTuple = true; return _lzCons(t, () => _lzZipIdx(ll.tail(), i + 1)); }
function _lzFlatMap(ll, f) { while (!ll._nil) { const inner = _lzCoerce(f(ll.head)); if (!inner._nil) { const rest = ll; return _lzAppend(inner, () => _lzFlatMap(rest.tail(), f)); } ll = ll.tail(); } return _LZ_NIL; }
function _lzAppend(ll, thunk) { return ll._nil ? thunk() : _lzCons(ll.head, () => _lzAppend(ll.tail(), thunk)); }
function _lzCoerce(v)      { return (v && v._lazy) ? v : _lzFromArray(Array.isArray(v) ? v : [v]); }
function _lzToArray(ll)    { const r = []; while (!ll._nil) { r.push(ll.head); ll = ll.tail(); } return r; }
const LazyList = Object.assign(function(...args) { return _lzFromArray(args); }, {
  from: (n) => _lzFrom(n), iterate: (seed) => (f) => _lzIterate(seed, f),
  continually: (x) => _lzContinually(() => x), range: (a, b, step) => _lzRange(a, b, step),
  tabulate: (n) => (f) => { let r = _LZ_NIL; for (let i = n - 1; i >= 0; i--) { const t = r, j = i; r = _lzCons(f(j), () => t); } return r; },
  empty: _LZ_NIL,
});

// ── Persistent immutable Map (_HAMT) — T2.2 ───────────────────────────────────
// The ssc immutable Map. A path-copying 8-nibble trie on a 32-bit hash of a
// canonical key string; `updated`/`removed` copy only the O(8) nodes on the path
// (structural sharing) instead of the old O(n) `new Map(obj)` copy. Keys use
// value equality via the canonical string (matches the interpreter's
// `Map[Value,Value]`; for primitives this coincides with native-Map equality).
// Exposes the native-Map read interface (has/get/size/keys/values/entries/
// iterator/forEach) so every `_isMap` consumer works on both reps unchanged, and
// native Maps and `_HAMT`s interoperate freely.
const _HMISS = Symbol('miss');
function _hKey(k) {
  const t = typeof k; let s;
  if (t === 'string') s = 's' + k;
  else if (t === 'number') s = 'n' + k;
  else if (t === 'boolean') s = 'b' + k;
  else if (k === null || k === undefined) s = 'u';
  else s = 'o' + _show(k);
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (Math.imul(31, h) + s.charCodeAt(i)) | 0;
  return [h >>> 0, s];
}
function _hGet(node, hash, d, ks) {
  if (node === undefined) return _HMISS;
  if (d === 8) { for (const e of node) if (e[0] === ks) return e[2]; return _HMISS; }
  return _hGet(node[(hash >>> (d * 4)) & 0xf], hash, d + 1, ks);
}
function _hPut(node, hash, d, ks, k, v) {
  if (d === 8) {
    const bucket = node || [];
    const i = bucket.findIndex(e => e[0] === ks);
    if (i >= 0) { const nb = bucket.slice(); nb[i] = [ks, k, v]; return [nb, 0]; }
    return [bucket.concat([[ks, k, v]]), 1];
  }
  const nib = (hash >>> (d * 4)) & 0xf;
  const [nc, delta] = _hPut(node ? node[nib] : undefined, hash, d + 1, ks, k, v);
  const nn = node ? { ...node } : {};
  nn[nib] = nc;
  return [nn, delta];
}
function _hDel(node, hash, d, ks) {
  if (node === undefined) return [node, 0];
  if (d === 8) {
    const i = node.findIndex(e => e[0] === ks);
    if (i < 0) return [node, 0];
    const nb = node.slice(); nb.splice(i, 1);
    return [nb.length ? nb : undefined, 1];
  }
  const nib = (hash >>> (d * 4)) & 0xf;
  if (node[nib] === undefined) return [node, 0];
  const [nc, del] = _hDel(node[nib], hash, d + 1, ks);
  if (!del) return [node, 0];
  const nn = { ...node };
  if (nc === undefined) delete nn[nib]; else nn[nib] = nc;
  return [nn, 1];
}
function* _hEntries(node, d) {
  if (node === undefined) return;
  if (d === 8) { for (const e of node) yield [e[1], e[2]]; return; }
  for (let i = 0; i < 16; i++) if (node[i] !== undefined) yield* _hEntries(node[i], d + 1);
}
class _HAMT {
  constructor(root, size) { this._r = root; this._n = size; }
  get size() { return this._n; }
  has(k) { const [h, ks] = _hKey(k); return _hGet(this._r, h, 0, ks) !== _HMISS; }
  get(k) { const [h, ks] = _hKey(k); const v = _hGet(this._r, h, 0, ks); return v === _HMISS ? undefined : v; }
  updated(k, v) { const [h, ks] = _hKey(k); const [r, d] = _hPut(this._r, h, 0, ks, k, v); return new _HAMT(r, this._n + d); }
  removed(k) { const [h, ks] = _hKey(k); const [r, d] = _hDel(this._r, h, 0, ks); return d ? new _HAMT(r, this._n - 1) : this; }
  *entries() { yield* _hEntries(this._r, 0); }
  *keys() { for (const [k] of _hEntries(this._r, 0)) yield k; }
  *values() { for (const [, v] of _hEntries(this._r, 0)) yield v; }
  forEach(f) { for (const [k, v] of _hEntries(this._r, 0)) f(v, k, this); }
  [Symbol.iterator]() { return _hEntries(this._r, 0); }
}
// Build a persistent map from [k,v] pairs (any iterable of pairs).
function _hamtOf(pairs) {
  let m = new _HAMT(undefined, 0);
  for (const p of pairs) m = m.updated(p[0], p[1]);
  return m;
}
// updated/removed that accept either rep and always return a persistent _HAMT
// (converting a native Map on first write).
function _mapUpdated(m, k, v) {
  return (m instanceof _HAMT ? m : _hamtOf(m.entries())).updated(k, v);
}
function _mapRemoved(m, k) {
  return (m instanceof _HAMT ? m : _hamtOf(m.entries())).removed(k);
}

// Map predicate — true for both the native-Map rep (internal runtime maps) and
// the persistent `_HAMT` (ssc immutable Map). All 71 consumer sites go through
// this (see T2.2 p2 sweep), so the two reps are interchangeable for reads.
function _isMap(x) { return x instanceof Map || x instanceof _HAMT; }

function _Map(...pairs) { return _hamtOf(pairs); }

// ── `.copy(...)` helper — fills positional args against the object's
// own key order, then applies named overrides on top. Case-class
// instances emit `{_type, a, b, …}` whose Object.keys order matches
// the declared field order in V8 / modern Node.
function _copy(obj, positional, named) {
  const result = { ...obj, ...named };
  if (positional.length === 0) return result;
  const keys = Object.keys(obj).filter(k => k !== '_type' && k !== '_tag');
  let posIdx = 0;
  for (const k of keys) {
    if (posIdx >= positional.length) break;
    if (k in named) continue;
    result[k] = positional[posIdx++];
  }
  return result;
}
