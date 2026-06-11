package scalascript.codegen

/** JS runtime preamble (part 2b) — see `JsRuntimePart1a`. */
val JsRuntimePart2b: String = """
// Sync fallbacks — overridden by JsRuntimeAsyncB when Async capability is present.
function _seqForeach(arr, fn) { for (let _i = 0; _i < arr.length; _i++) fn(arr[_i]); return undefined; }
// _forEach: direct-call alternative to _dispatch(xs,'foreach',[fn]).
// Routes arrays through _seqForeach (so AsyncB can override it for effectful callbacks);
// falls back to _dispatch for Map/Set/Option and other non-array types.
function _forEach(xs, fn) {
  if (Array.isArray(xs)) return _seqForeach(xs, fn);
  return _dispatch(xs, 'foreach', [fn]);
}
function _seqMap(arr, fn) { return arr.map(fn); }
function _seqFlatMap(arr, fn) { return arr.flatMap(fn); }
function _seqFilter(arr, fn, negate) { return arr.filter(x => negate ? !fn(x) : fn(x)); }
function _seqExists(arr, fn) { return arr.some(fn); }
function _seqForall(arr, fn) { return arr.every(fn); }
function _seqFind(arr, fn) { const r = arr.find(fn); return r !== undefined ? {_type:'_Some',value:r} : {_type:'_None'}; }
function _seqCount(arr, fn) { return arr.filter(fn).length; }
function _seqFoldLeft(arr, init, fn) { return arr.reduce((acc, x) => fn(acc, x), init); }

function _tupleConcat(a, b) {
  const aArr = Array.isArray(a) ? a : [a];
  const bArr = Array.isArray(b) ? b : [b];
  const r = [...aArr, ...bArr];
  const aIsTuple = !Array.isArray(a) || a._isTuple;
  const bIsTuple = !Array.isArray(b) || b._isTuple;
  if (aIsTuple && bIsTuple) r._isTuple = true;
  return r;
}

function _dispatch(obj, method, args) {
  // Exact numerics (v1.64): Decimal (object), BigInt (native), and the
  // toBigInt/toDecimal conversions on plain Numbers.
  if (obj && obj._type === '_Decimal') {
    switch (method) {
      case 'scale': return obj.s;
      case 'precision': { const t = (obj.u < 0n ? -obj.u : obj.u).toString(); return obj.u === 0n ? 1 : t.length; }
      case 'toBigInt': return obj.s <= 0 ? obj.u * _pow10(-obj.s) : obj.u / _pow10(obj.s);
      case 'toInt': case 'toLong': { const b = obj.s <= 0 ? obj.u * _pow10(-obj.s) : obj.u / _pow10(obj.s); return Number(b); }
      case 'toDouble': return Number(_decShow(obj));
      case 'toDecimal': return obj;
      case 'toString': return _decShow(obj);
      case 'abs': return _mkDec(obj.u < 0n ? -obj.u : obj.u, obj.s);
      case 'negate': return _mkDec(-obj.u, obj.s);
      case 'signum': return obj.u < 0n ? -1 : obj.u > 0n ? 1 : 0;
      case 'isZero': return obj.u === 0n;
      case 'setScale': return _decSetScale(obj, Number(args[0]), args[1] === undefined ? 'HALF_UP' : args[1]);
      case 'round': return _decSetScale(obj, 0, args[0] === undefined ? 'HALF_UP' : args[0]);
      case 'divide': return args.length >= 3 ? _decDivide(obj, _toDec(args[0]), Number(args[1]), args[2])
                                             : _decDivide(obj, _toDec(args[0]), obj.s, args[1]);
      case 'pow': { let r = _mkDec(1n, 0); for (let i = 0; i < Number(args[0]); i++) r = _decMul(r, obj); return r; }
      case 'min': return _decCmp(obj, _toDec(args[0])) <= 0 ? obj : _toDec(args[0]);
      case 'max': return _decCmp(obj, _toDec(args[0])) >= 0 ? obj : _toDec(args[0]);
      case 'compareTo': return _decCmp(obj, _toDec(args[0]));
      case '+': return _decAdd(obj, _toDec(args[0]));
      case '-': return _decSub(obj, _toDec(args[0]));
      case '*': return _decMul(obj, _toDec(args[0]));
      case '/': return _decDivBare(obj, _toDec(args[0]));
    }
  }
  if (typeof obj === 'bigint') {
    switch (method) {
      case 'toInt': case 'toLong': return Number(obj);
      case 'toBigInt': return obj;
      case 'toDecimal': return Decimal(obj);
      case 'toDouble': return Number(obj);
      case 'toString': return obj.toString();
      case 'abs': return obj < 0n ? -obj : obj;
      case 'negate': return -obj;
      case 'signum': case 'sign': return obj < 0n ? -1 : obj > 0n ? 1 : 0;
      case 'isEven': return (obj & 1n) === 0n;
      case 'isOdd': return (obj & 1n) === 1n;
      case 'pow': { let r = 1n; for (let i = 0; i < Number(args[0]); i++) r *= obj; return r; }
      case 'gcd': { let a = obj < 0n ? -obj : obj, b = _toBig(args[0]); b = b < 0n ? -b : b; while (b) { [a, b] = [b, a % b]; } return a; }
      case 'mod': { const m = _toBig(args[0]); const r = obj % m; return r < 0n ? r + (m < 0n ? -m : m) : r; }
      case 'min': { const o = _toBig(args[0]); return obj < o ? obj : o; }
      case 'max': { const o = _toBig(args[0]); return obj > o ? obj : o; }
      case '+': return obj + _toBig(args[0]);
      case '-': return obj - _toBig(args[0]);
      case '*': return obj * _toBig(args[0]);
      case '/': return obj / _toBig(args[0]);
      case '%': return obj % _toBig(args[0]);
    }
  }
  if (typeof obj === 'number') {
    switch (method) {
      case 'toBigInt': return _toBig(obj);
      case 'toDecimal': return Decimal(obj);
    }
  }
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
  if (_isMap(obj)) {
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
  // Either[L, R] dispatch — Right(v) is the success channel; Left(v) is the
  // error channel. Methods are mapped 1:1 from the Scala Either surface.
  if (obj && obj._type === 'Right') {
    switch(method) {
      case 'map':       return { _type: 'Right', value: args[0](obj.value) };
      case 'flatMap':   return args[0](obj.value);
      case 'fold':      return args[1](obj.value);   // fold(left, right) → right path
      case 'getOrElse': return obj.value;
      case 'isLeft':    return false;
      case 'isRight':   return true;
      case 'left':      return _None;
      case 'right':     return _Some(obj.value);
      case 'toOption':  return _Some(obj.value);
      case 'swap':      return { _type: 'Left', value: obj.value };
      case 'forEach':
      case 'foreach':   args[0](obj.value); return undefined;
      case 'contains':  return obj.value === args[0];
      case 'exists':    return args[0](obj.value);
      case 'filterOrElse':
                        return args[0](obj.value) ? obj : { _type: 'Left', value: args[1] };
    }
  }
  if (obj && obj._type === 'Left') {
    switch(method) {
      case 'map':       return obj;
      case 'flatMap':   return obj;
      case 'fold':      return args[0](obj.value);   // fold(left, right) → left path
      case 'getOrElse': return args[0];
      case 'isLeft':    return true;
      case 'isRight':   return false;
      case 'left':      return _Some(obj.value);
      case 'right':     return _None;
      case 'toOption':  return _None;
      case 'swap':      return { _type: 'Right', value: obj.value };
      case 'forEach':
      case 'foreach':   return undefined;
      case 'contains':  return false;
      case 'exists':    return false;
      case 'filterOrElse': return obj;
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
      case 'indexOf': { const a = typeof args[0] === 'number' ? String.fromCharCode(args[0]) : args[0]; return obj.indexOf(a, args[1]); }
      case 'lastIndexOf': { const a = typeof args[0] === 'number' ? String.fromCharCode(args[0]) : args[0]; return obj.lastIndexOf(a, args[1]); }
      case 'forall': { for (const c of obj) { if (!args[0](c)) return false; } return true; }
      case 'exists': { for (const c of obj) { if (args[0](c)) return true; } return false; }
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
  if (typeof obj === 'boolean') {
    if (method === 'toString') return String(obj);
    if (method === 'toInt') return obj ? 1 : 0;
    if (method === '!') return !obj;
  }
  if (obj && typeof obj === 'object') {
    if (method === 'toString') {
      if (typeof _extensions !== 'undefined') {
        const _tsfn = _extensions[_typeOf(obj) + ':toString'];
        if (_tsfn) return _tsfn(obj);
      }
      return _show(obj);
    }
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
  if (_isMap(obj)) return 'Map';
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
  if (!Object.prototype.hasOwnProperty.call(_extensions, method)) _extensions[method] = [];
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

// Named with the `_ssc_ui_` extern convention (JsGen maps every `extern def`
// to `_ssc_ui_<name>`).  A bare `function jsonStringify` collided at top level
// with the `const jsonStringify = std.json.jsonStringify` import binding
// ("Identifier already declared") and left the binding resolving to undefined.
function _ssc_ui_jsonStringify(v) { return _toJsonStringify(v); }

// v1.5 Tier 5 #22 — indexed access on `Any`-typed JSON values.
// JS already lets users write `obj("name")` dynamically via `_dispatch`,
// but `lookup` / `lookupOpt` are the cross-backend escape hatch so the
// same source compiles cleanly on JvmGen too.  `lookup` throws on a
// missing key; `lookupOpt` returns `_None` / `_Some(v)`.
function _lookupKey(v, k) {
  if (_isMap(v)) {
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
      if (_isMap(inner)) {
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
    if (typeof k === 'string' && _isMap(inner) && inner.has(k))
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
    if (_isMap(inner)) {
      const out = new Map();
      for (const [k, v] of inner) out.set(k, _jsonValueWrap(v));
      return out;
    }
    throw new Error('JsonValue.asMap: expected map but got ' + _show(inner));
  };
  self.raw    = function() { return inner; };
  self.isNull = function() { return inner === null || inner === undefined; };
  self.keys   = function() {
    if (_isMap(inner)) return [...inner.keys()];
    return [];
  };
  self.size = function() {
    if (Array.isArray(inner))      return inner.length;
    if (_isMap(inner))      return inner.size;
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

// std.json — navigable, TOTAL JsonValue (mirrors the JVM `navJson` intrinsic).
// Unlike _jsonValueWrap (jsonRead), accessors never throw: missing key / wrong
// shape / malformed input → Null JsonValue / zero-default.  `get`/`at` return a
// wrapped (Null-on-miss) JsonValue (not an Option); `asDecimal` is exact money.
function _jsonValueTotal(inner) {
  const self = { _type: 'JsonValue', _inner: inner };
  self.get = function(k) {
    return _jsonValueTotal((_isMap(inner) && inner.has(k)) ? inner.get(k) : null);
  };
  self.at = function(i) {
    return _jsonValueTotal((Array.isArray(inner) && i >= 0 && i < inner.length) ? inner[i] : null);
  };
  self.isNull   = function() { return inner === null || inner === undefined; };
  self.asString = function() { return (typeof inner === 'string') ? inner : ''; };
  self.asInt    = function() {
    if (typeof inner === 'number') return Math.trunc(inner);
    if (typeof inner === 'bigint') return Number(inner);
    if (typeof inner === 'string') { const n = parseFloat(inner); return Number.isFinite(n) ? Math.trunc(n) : 0; }
    if (inner && inner._type === '_Decimal') return Number(inner.u / (10n ** BigInt(inner.s)));
    return 0;
  };
  self.asDouble = function() {
    if (typeof inner === 'number') return inner;
    if (typeof inner === 'string') { const n = parseFloat(inner); return Number.isFinite(n) ? n : 0; }
    return 0;
  };
  self.asBool   = function() { return inner === true; };
  self.asList   = function() { return Array.isArray(inner) ? inner.map(_jsonValueTotal) : []; };
  // Exact-decimal — lossless.  A JSON string ("1000.01") keeps its text; a bare
  // number is coerced via its textual form (already lossy at parse, spec §3.1).
  self.asDecimal = function() {
    try { return Decimal(typeof inner === 'number' ? String(inner) : inner); }
    catch (e) { return Decimal('0'); }
  };
  self.optString = function() { return (typeof inner === 'string') ? _Some(inner) : _None; };
  self.optInt    = function() {
    if (typeof inner === 'number' && Number.isInteger(inner)) return _Some(inner);
    return _None;
  };
  self.optDecimal = function() {
    if (typeof inner === 'string' || typeof inner === 'number' || typeof inner === 'bigint') {
      try { return _Some(Decimal(typeof inner === 'number' ? String(inner) : inner)); }
      catch (e) { return _None; }
    }
    return _None;
  };
  self.getOrElse = function(k, fb) {
    if (_isMap(inner) && inner.has(k)) {
      const v = inner.get(k);
      return (typeof v === 'string') ? v : _show(v);
    }
    return fb;
  };
  self.raw = function() { return inner; };
  return self;
}
// `_ssc_ui_` extern convention — see _ssc_ui_jsonStringify above.  A bare
// `function jsonValue` broke every emit-spa screen importing `jsonValue`.
function _ssc_ui_jsonValue(s) {
  if (typeof s === 'string') {
    let parsed = null;
    try { parsed = _jsonConvert(JSON.parse(s)); } catch (e) { parsed = null; }
    return _jsonValueTotal(parsed);
  }
  return _jsonValueTotal(s);
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
  if (req && _isMap(req.form) && req.form.has(name)) return req.form.get(name);
  if (req && _isMap(req.query) && req.query.has(name)) return req.query.get(name);
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

function _restRequireString(req, name) {
  const v = _restFieldOf(req, name);
  if (v === undefined) return _recordOrThrow(name, 'missing field: ' + name, '');
  return String(v);
}
function optionalString(req, name) {
  const v = _restFieldOf(req, name);
  return v === undefined ? _None : _Some(String(v));
}
function _restRequireInt(req, name) {
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
function _restRequireDouble(req, name) {
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
function _restRequireBool(req, name) {
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
  if (_isMap(v)) {
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

// One-shot variant of _handle: resume may only be called once per dispatch.
// Calling resume a second time throws a runtime error.
function _handleOneShot(bodyFn, handledOps, handlers) {
  const handled = new Set(handledOps);
  function interp(initial) {
    let current = initial;
    while (true) {
      if (current instanceof _Perform) {
        const key = current.eff + '.' + current.op;
        if (handled.has(key) && handlers[key]) {
          let _resumed = false;
          const resume = (v) => {
            if (_resumed) throw new Error('One-shot violation: ' + current.eff + '.' + current.op + ' resumed more than once');
            _resumed = true;
            return v;
          };
          current = handlers[key]([...current.args, resume]);
        } else { return current; }
      } else if (current instanceof _FlatMap) {
        const sub = current.sub;
        if (sub instanceof _FlatMap) {
          const sub2 = sub.sub; const g = sub.k; const f = current.k;
          current = new _FlatMap(sub2, (x) => new _FlatMap(g(x), f));
        } else if (sub instanceof _Perform) {
          const key = sub.eff + '.' + sub.op;
          const f = current.k;
          if (handled.has(key) && handlers[key]) {
            let _resumed = false;
            const resume = (v) => {
              if (_resumed) throw new Error('One-shot violation: ' + sub.eff + '.' + sub.op + ' resumed more than once');
              _resumed = true;
              return interp(f(v));
            };
            current = handlers[key]([...sub.args, resume]);
          } else {
            return new _FlatMap(sub, (v) => interp(f(v)));
          }
        } else { current = current.k(sub); }
      } else { return current; }
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
// UUID intrinsics — v4 (random) and v7 (time-ordered, RFC 9562 §5.7)
function uuidV4() {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID();
  // fallback: manual assembly with crypto.getRandomValues
  const b = new Uint8Array(16);
  (crypto || require('crypto')).getRandomValues(b);
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  return [...b].map((x,i) => (i===4||i===6||i===8||i===10?'-':'')+x.toString(16).padStart(2,'0')).join('');
}
function uuidV7() {
  const now = BigInt(Date.now());
  const rand = new Uint8Array(10);
  (typeof crypto !== 'undefined' ? crypto : require('crypto')).getRandomValues(rand);
  const randA = ((rand[0] & 0x0f) << 8) | rand[1];
  const randB1 = ((rand[2] & 0x3f) << 8) | rand[3] | 0x8000;
  const tHi = Number((now >> 16n) & 0xffffffffn).toString(16).padStart(8,'0');
  const tLo = Number(now & 0xffffn).toString(16).padStart(4,'0');
  const ra  = randA.toString(16).padStart(3,'0');
  const rb1 = randB1.toString(16).padStart(4,'0');
  const rb2 = (((rand[4]<<8)|rand[5])&0xffff).toString(16).padStart(4,'0');
  const rb3 = (((rand[6]<<8)|rand[7])&0xffff).toString(16).padStart(4,'0');
  const rb4 = (((rand[8]<<8)|rand[9])&0xffff).toString(16).padStart(4,'0');
  return `${tHi}-${tLo}-7${ra}-${rb1}-${rb2}${rb3}${rb4}`;
}
const _uuidRx = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
function uuidIsValid(s) { return _uuidRx.test(typeof s === 'string' ? s.toLowerCase() : ''); }
function uuidFromString(s) { return uuidIsValid(s) ? _Some(s.toLowerCase()) : _None; }
function uuidUnsafeFromString(s) {
  if (uuidIsValid(s)) return s.toLowerCase();
  throw new Error('uuidUnsafeFromString: not a valid UUID: ' + s);
}
// ── crypto ──────────────────────────────────────────────────────────────────
const _nodeCrypto = (typeof require !== 'undefined') ? require('crypto') : null;
function _sha256(s) {
  const data = (typeof TextEncoder !== 'undefined')
    ? new TextEncoder().encode(s)
    : Buffer.from(s, 'utf8');
  if (_nodeCrypto) {
    return _nodeCrypto.createHash('sha256').update(data).digest('hex');
  }
  throw new Error('sha256: no synchronous crypto available in this environment');
}
function _hmacSha256(key, data) {
  if (_nodeCrypto) {
    return _nodeCrypto.createHmac('sha256', Buffer.from(key, 'utf8')).update(Buffer.from(data, 'utf8')).digest('hex');
  }
  throw new Error('hmacSha256: no synchronous crypto available in this environment');
}
function _base64Encode(s) {
  if (typeof Buffer !== 'undefined') return Buffer.from(s, 'utf8').toString('base64');
  return btoa(encodeURIComponent(s).replace(/%([0-9A-F]{2})/g, (_, p) => String.fromCharCode(parseInt(p, 16))));
}
function _base64Decode(s) {
  if (typeof Buffer !== 'undefined') return Buffer.from(s, 'base64').toString('utf8');
  return decodeURIComponent(atob(s).split('').map(c => '%' + c.charCodeAt(0).toString(16).padStart(2, '0')).join(''));
}
"""
