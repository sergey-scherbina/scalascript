package scalascript.codegen

/** Optics JS runtime preamble — Lens / Optional / Traversal / Prism.
 *  Extracted verbatim from `JsGen.scala` (jsgen-split-p2); referenced
 *  unqualified as a package-level `val`, keeping emitted JS byte-identical. */
val JsRuntimeOptics: String = """
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
        if (!(_isMap(v))) return _None;
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
      if (!(_isMap(s))) return s;
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
      if (!(_isMap(s)) || !s.has(head.key)) return [];
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
      if (!(_isMap(s)) || !s.has(head.key)) return s;
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
"""
