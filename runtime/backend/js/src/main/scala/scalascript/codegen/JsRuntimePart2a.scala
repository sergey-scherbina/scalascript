package scalascript.codegen

/** JS runtime preamble (part 2a) — see `JsRuntimePart1a`. */
val JsRuntimePart2a: String = """
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
function _arith(op, a, b) {
  // String concatenation keeps priority (matches the interpreter's `a + show(b)`).
  if (op === '+' && (typeof a === 'string' || typeof b === 'string'))
    return (typeof a === 'string' ? a : _show(a)) + (typeof b === 'string' ? b : _show(b));
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
    case '==': return a === b; case '!=': return a !== b;
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

function List(...args) { return [...args]; }
List.fill     = (n) => (elem) => Array.from({length: n}, () => elem);
List.tabulate = (n) => (f)    => Array.from({length: n}, (_, i) => f(i));
List.range    = (from, until, step=1) => { const r=[]; for(let i=from;i<until;i+=step) r.push(i); return r; };
List.empty    = [];
const Nil = [];

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
"""
