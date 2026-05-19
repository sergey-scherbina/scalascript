package scalascript.codegen

/** Dataset[T] JS runtime preamble — emitted when dataset usage is detected.
 *
 *  `_Dataset` is a lazy pipeline: a source thunk `() => Array` plus a
 *  composed transformation `Array => Array`.  Nothing executes until a
 *  terminal operation is called.  `runParallel()` falls back to sequential
 *  on JS (Node parallel via worker_threads deferred to v1.3 Node). */
val JsRuntimeDataset: String = """
// ── v1.21 Dataset[T] — lazy map-reduce pipeline ─────────────────────────────

class _Dataset {
  constructor(sourceFn, pipeline, parallel) {
    this._sourceFn  = sourceFn;   // () => Array — produces the raw source
    this._pipeline  = pipeline;   // Array => Array — composed transformations
    this._parallel  = !!parallel; // true = parallel mode (JS: sequential fallback)
  }

  // ── Lazy transformations ─────────────────────────────────────────────────

  map(f) {
    const prev = this._pipeline;
    return new _Dataset(this._sourceFn, xs => prev(xs).map(f), this._parallel);
  }
  filter(p) {
    const prev = this._pipeline;
    return new _Dataset(this._sourceFn, xs => prev(xs).filter(p), this._parallel);
  }
  flatMap(f) {
    const prev = this._pipeline;
    return new _Dataset(this._sourceFn, xs => {
      const out = [];
      for (const x of prev(xs)) {
        const r = f(x);
        if (Array.isArray(r)) { for (const v of r) out.push(v); }
        else if (r && typeof r.toList === 'function') { for (const v of r.toList()) out.push(v); }
        else out.push(r);
      }
      return out;
    }, this._parallel);
  }
  take(n) {
    const prev = this._pipeline;
    return new _Dataset(this._sourceFn, xs => prev(xs).slice(0, n), this._parallel);
  }
  drop(n) {
    const prev = this._pipeline;
    return new _Dataset(this._sourceFn, xs => prev(xs).slice(n), this._parallel);
  }
  get distinct() {
    const prev = this._pipeline;
    return new _Dataset(this._sourceFn, xs => {
      const seen = new Set();
      const out  = [];
      for (const x of prev(xs)) {
        const k = JSON.stringify(x);
        if (!seen.has(k)) { seen.add(k); out.push(x); }
      }
      return out;
    }, this._parallel);
  }
  union(other) {
    // Concatenate this dataset's materialised output with `other`'s.
    // Both pipelines run at terminal time; multiplicities preserved
    // (Spark semantics — use .distinct after if dedup is wanted).
    const self = this;
    return new _Dataset(() => [...self.collect(), ...other.collect()],
                        xs => xs, this._parallel);
  }
  intersect(other) {
    // Set intersection; preserves left order, dedups. Uses JSON.stringify
    // for value equality so case-class instances compare structurally.
    const self = this;
    return new _Dataset(() => {
      const otherKeys = new Set(other.collect().map(v => JSON.stringify(v)));
      const seen = new Set();
      const out = [];
      for (const x of self.collect()) {
        const k = JSON.stringify(x);
        if (otherKeys.has(k) && !seen.has(k)) { seen.add(k); out.push(x); }
      }
      return out;
    }, xs => xs, this._parallel);
  }
  zip(other) {
    // Pair element-wise; stops at the shorter side. Emits 2-tuples that
    // round-trip through the rest of the runtime (the _isTuple flag is
    // what `case (a, b) =>` destructuring keys off).
    const self = this;
    return new _Dataset(() => {
      const ls = self.collect(), rs = other.collect();
      const n  = Math.min(ls.length, rs.length);
      const out = new Array(n);
      for (let i = 0; i < n; i++) {
        const t = [ls[i], rs[i]]; t._isTuple = true; out[i] = t;
      }
      return out;
    }, xs => xs, this._parallel);
  }
  get zipWithIndex() {
    const self = this;
    return new _Dataset(() => {
      const ls = self.collect();
      return ls.map((v, i) => { const t = [v, i]; t._isTuple = true; return t; });
    }, xs => xs, this._parallel);
  }
  // Numeric / ordered terminal aggregations.
  min() {
    const xs = this.collect();
    if (xs.length === 0) throw new Error('Dataset.min: empty dataset');
    return xs.reduce((a, b) => a <= b ? a : b);
  }
  max() {
    const xs = this.collect();
    if (xs.length === 0) throw new Error('Dataset.max: empty dataset');
    return xs.reduce((a, b) => a >= b ? a : b);
  }
  sum() {
    return this.collect().reduce((a, b) => a + b, 0);
  }
  avg() {
    const xs = this.collect();
    if (xs.length === 0) throw new Error('Dataset.avg: empty dataset');
    return xs.reduce((a, b) => a + b, 0) / xs.length;
  }
  // top(n) — n largest values, descending. takeOrdered(n) — n smallest,
  // ascending. countByValue — Map of element → frequency (Long count).
  top(n) {
    return [...this.collect()].sort((a, b) => a < b ? 1 : a > b ? -1 : 0).slice(0, n);
  }
  takeOrdered(n) {
    return [...this.collect()].sort((a, b) => a < b ? -1 : a > b ? 1 : 0).slice(0, n);
  }
  countByValue() {
    const m = new Map();
    for (const x of this.collect()) {
      const k = JSON.stringify(x);
      const entry = m.get(k);
      if (entry) entry.count++;
      else m.set(k, { key: x, count: 1 });
    }
    // Mirror INT/JVM output — a Map of value → count
    const out = _Map();
    for (const { key, count } of m.values()) out.set(key, count);
    return out;
  }
  groupBy(key) {
    const prev = this._pipeline;
    return new _Dataset(this._sourceFn, xs => {
      const map = new Map();
      for (const x of prev(xs)) {
        const k = key(x);
        const ks = JSON.stringify(k);
        if (!map.has(ks)) map.set(ks, { _key: k, _vals: [] });
        map.get(ks)._vals.push(x);
      }
      return [...map.values()].map(e => { const t = [e._key, e._vals]; t._isTuple = true; return t; });
    }, this._parallel);
  }
  reduceByKey(key) {
    return combine => {
      const prev = this._pipeline;
      return new _Dataset(this._sourceFn, xs => {
        const map = new Map();
        for (const x of prev(xs)) {
          const k  = key(x);
          const ks = JSON.stringify(k);
          if (!map.has(ks)) map.set(ks, { _key: k, _acc: x });
          else { const e = map.get(ks); e._acc = combine(e._acc, x); }
        }
        return [...map.values()].map(e => { const t = [e._key, e._acc]; t._isTuple = true; return t; });
      }, this._parallel);
    };
  }
  sortBy(key) {
    const prev = this._pipeline;
    return new _Dataset(this._sourceFn, xs => {
      const src = prev(xs).slice();
      src.sort((a, b) => {
        const ka = key(a), kb = key(b);
        return ka < kb ? -1 : ka > kb ? 1 : 0;
      });
      return src;
    }, this._parallel);
  }

  // ── Execution mode ───────────────────────────────────────────────────────

  runLocal()    { return new _Dataset(this._sourceFn, this._pipeline, false); }
  runParallel() {
    // JS: no worker_threads parallel for Dataset yet (v1.3 Node parallel
    // is deferred).  Fall back to sequential; warn once per process.
    if (!_Dataset._parallelWarnedJs) {
      _Dataset._parallelWarnedJs = true;
      if (typeof process !== 'undefined' && process.stderr)
        process.stderr.write('[Dataset] runParallel() not yet available on JS — running sequentially\n');
    }
    return new _Dataset(this._sourceFn, this._pipeline, false);
  }

  // ── Terminal operations ──────────────────────────────────────────────────

  collect()              { return this._pipeline(this._sourceFn()); }
  count()                { return this._pipeline(this._sourceFn()).length; }
  reduce(combine)        {
    const xs = this._pipeline(this._sourceFn());
    if (xs.length === 0) throw new Error('Dataset.reduce: empty dataset');
    return xs.reduce(combine);
  }
  fold(z) {
    return combine => this._pipeline(this._sourceFn()).reduce(combine, z);
  }
  foreach(action)        { for (const x of this._pipeline(this._sourceFn())) action(x); }
  first() {
    const xs = this._pipeline(this._sourceFn());
    return xs.length > 0 ? _Some(xs[0]) : _None;
  }
  toGenerator() {
    const data = this._pipeline(this._sourceFn());
    return _makeGenerator(function*() { for (const x of data) yield x; });
  }
}

_Dataset._parallelWarnedJs = false;

// ── Constructors ─────────────────────────────────────────────────────────────

_Dataset.of = function(...items) {
  return new _Dataset(() => items, xs => xs, false);
};
_Dataset.fromList = function(list) {
  const arr = Array.isArray(list) ? list : (list && typeof list.toList === 'function' ? list.toList() : [...list]);
  return new _Dataset(() => arr, xs => xs, false);
};
_Dataset.fromGenerator = function(gen) {
  return new _Dataset(() => gen.toList(), xs => xs, false);
};
_Dataset.fromFile = function(path) {
  return new _Dataset(() => {
    if (typeof require === 'undefined') throw new Error('Dataset.fromFile: require not available in this environment');
    const fs = require('fs');
    const text = fs.readFileSync(path, 'utf-8');
    const lines = text.split('\n');
    // Match Scala's getLines() / Java's BufferedReader.lines() — the
    // trailing empty after a final '\n' is not a line.
    if (lines.length > 0 && lines[lines.length - 1] === '') lines.pop();
    return lines;
  }, xs => xs, false);
};

// Top-level Dataset object alias so user code can write `Dataset.of(...)`.
const Dataset = _Dataset;
"""
