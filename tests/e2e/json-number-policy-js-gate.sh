#!/usr/bin/env bash
#
# The JS runtime's JSON number policy, asserted on the node that RUNS THIS SCRIPT.
#
# Why this gate exists. `jsonParse` on the js lane was made exact via the ES2025 reviver's
# `context.source`, with a documented fallback to the plain Number "whenever exactness is not
# achievable". Node 20 does not have `context.source`, so on that host the fallback was the only
# path — and by then the plain Number was the WRONG answer. The result was not a broken lane but a
# HOST-DEPENDENT one: `tests/conformance/json-read` PASSED on a local Node 26 and FAILED in CI,
# where every workflow pins `node-version: '20'`. `>=20` is also the floor the shipped
# `package.json`s declare, so that was a supported host printing a different number for the same
# program.
#
# Nothing in the repo asserted js-side exactness, so the only thing that could notice was a
# conformance golden — which reports it as one red cell in a 25-minute sharded job, on the wrong
# lane, with no hint that the host is the variable. This gate asserts it directly, in under a
# second, on whatever node is present. Run it on an old node and it fails; that is the point.
#
# It also runs the parser DIFFERENTIALLY against `JSON.parse`, because the fix replaced the reviver
# with a hand-written exact parser and "exact" must not mean "different in some other way":
# structure, escapes, and object key ORDER (JS visits integer-like keys first, ascending) all have
# to match the implementation it replaced.
#
# Usage: tests/e2e/json-number-policy-js-gate.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RT="$ROOT/v1/runtime/backend/js/src/main/resources/scalascript/js-runtime"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

command -v node >/dev/null 2>&1 || { echo 'json-number-policy-js-gate: node not found' >&2; exit 2; }
echo "== node $(node -v) =="

for f in core.mjs core-dispatch.mjs core-collections.mjs; do
  [ -f "$RT/$f" ] || { echo "json-number-policy-js-gate: missing $RT/$f" >&2; exit 2; }
done

# The runtime files are CONCATENATED into the emitted program by JsGen, not imported — so the gate
# concatenates them the same way instead of inventing an export surface that production never uses.
cat "$RT/core.mjs" "$RT/core-dispatch.mjs" "$RT/core-collections.mjs" > "$TMP/rt.mjs"

# Refuse to pass by accident if the functions under test are not there at all: a renamed helper
# would otherwise make every `typeof x === 'function'`-free check below throw on the FIRST case and
# look like an ordinary failure, or worse, a future refactor could delete the exactness entirely and
# leave a gate that only exercises JSON.parse.
cat >> "$TMP/rt.mjs" <<'PRELUDE'
for (const name of ['_jsonParseExact', '_jsonParseRaw', 'jsonParse', '_decShow']) {
  // An ABSENT module-scoped binding makes direct `eval` throw ReferenceError rather than yield
  // undefined, so the try is what turns "the helper was renamed or deleted" into this message
  // instead of an unhandled crash whose stack says nothing about the policy.
  let ok = false;
  try { ok = typeof eval(name) === 'function'; } catch (e) { ok = false; }
  if (!ok) {
    console.log('MISSING ' + name + ' — the js JSON number policy moved; update this gate');
    process.exit(3);
  }
}
// The reviver is GONE on purpose, and this check is the ONLY half of the gate that works on a NEW
// host: where `context.source` exists, the reviver produces the right answer, so no behavioural
// assertion below can tell the two implementations apart. The defect was never "wrong output here",
// it was "output that depends on the host" — so what has to be asserted is the ABSENCE of the
// dependence, structurally.
//
// `globalThis._jsonNumberReviver` would NOT work: a top-level `function` in an ES module is module
// -scoped, never a property of globalThis, so that form is vacuously false and would have passed on
// the very code it is meant to catch. Direct `eval` sees module scope; a ReferenceError means gone.
let _reviverPresent = true;
try { eval('_jsonNumberReviver'); } catch (e) { _reviverPresent = false; }
if (_reviverPresent) {
  console.log('_jsonNumberReviver is back — exactness must not depend on ES2025 JSON.parse source access');
  process.exit(3);
}
PRELUDE

cat >> "$TMP/rt.mjs" <<'HARNESS'
let fails = 0;
const bad = (...m) => { fails++; console.log('  FAIL', ...m); };

// ── 1. exactness: the property the whole policy is about ──────────────────
// A fractional literal's trailing zeros and its full digit string survive. binary64 cannot
// represent either, so every one of these fails on the lossy path.
const show = (t) => { const v = _jsonParseRaw(t); return v && v._type === '_Decimal' ? _decShow(v) : String(v); };
const exact = [
  ['0.0',    '0.0'],
  ['0.10',   '0.10'],
  ['1.50',   '1.50'],
  ['-0.50',  '-0.50'],
  ['0.10e1', '1.0'],     // the exponent shifts the SCALE: BigDecimal("0.10e1").toString, not "1"
  ['0.1000000000000000055511151231257827', '0.1000000000000000055511151231257827'],
  ['42',     '42'],      // integers are untouched — this is not a second integer policy
  ['-7',     '-7'],
  ['0',      '0'],
];
for (const [t, want] of exact) {
  const got = show(t);
  if (got !== want) bad('exact', JSON.stringify(t), 'want', want, 'got', got);
}

// ── 1b. the SAME answers on a host without ES2025 JSON.parse source text ──
// This is the CI failure, reproduced without CI's node. Stripping the reviver's third argument is
// exactly what Node 20 does, and it is what turned `0.0` into `0` there while every local run
// stayed green. Any implementation that reaches for `context.source` — however carefully it falls
// back — fails this block, on every host, which is the property the structural check above can only
// approximate by name.
const _realParse = JSON.parse;
try {
  JSON.parse = (text, reviver) =>
    _realParse(text, reviver ? function (k, v) { return reviver.call(this, k, v); } : undefined);
  for (const [t, want] of exact) {
    const got = show(t);
    if (got !== want) bad('host without JSON.parse source text:', JSON.stringify(t), 'want', want, 'got', got);
  }
} finally { JSON.parse = _realParse; }

// ── 2. differential vs JSON.parse: same structure, same key order ─────────
const norm = (v) => {
  if (v && v._type === '_Decimal') return { dec: v.u.toString() + 'e-' + v.s };
  if (Array.isArray(v)) return v.map(norm);
  if (v && typeof v === 'object') { const o = {}; for (const k of Object.keys(v)) o[k] = norm(v[k]); return o; }
  return typeof v === 'number' ? { num: v } : v;
};
const shape = (v) => {
  if (Array.isArray(v)) return ['[]', v.map(shape)];
  if (v && typeof v === 'object' && v._type !== '_Decimal') {
    const o = {}; for (const k of Object.keys(v)) o[k] = shape(v[k]);
    return [Object.keys(v), o];
  }
  return null;
};
const structural = [
  '42', '-7', '0', '{}', '[]', '[1,2,3]', '"hi"', 'true', 'false', 'null',
  '{"a":1,"b":[1,2,{"c":null}]}',
  '{"2":1,"1":2,"b":3,"10":4}',              // integer-like keys: JS order, not source order
  '{"__proto__":1,"x":2}',                   // must be an OWN property, as JSON.parse makes it
  '  { "a" : [ 1 , 2 ] }  ',                 // leading/trailing/internal whitespace
  '"a\\nb\\tc\\u0041\\\\d\\"e"',             // the full escape chain
  '{"k":"has . and E inside a string"}',      // a '.' inside a STRING must not confuse the scan
  '[1,2,3,"x.y"]',
];
for (const t of structural) {
  const a = JSON.parse(t), b = _jsonParseExact(t);
  if (JSON.stringify(norm(a)) !== JSON.stringify(norm(b)))
    bad('differential value', JSON.stringify(t), JSON.stringify(norm(a)), 'vs', JSON.stringify(norm(b)));
  // Key ORDER, not just key SET. Insertion method cannot get this wrong — JS orders integer-like
  // own keys numerically whatever you do — so this guards the shape of a future REWRITE (one that
  // sorts, or returns entries in source order) rather than today's insertion code.
  if (JSON.stringify(shape(a)) !== JSON.stringify(shape(b)))
    bad('differential key order', JSON.stringify(t), JSON.stringify(shape(a)), 'vs', JSON.stringify(shape(b)));
}
if (!Object.prototype.hasOwnProperty.call(_jsonParseExact('{"__proto__":1}'), '__proto__'))
  bad('__proto__ is not an own property — the key silently vanishes from Object.keys');

// ── 3. malformed input still throws, and through jsonParse's message ──────
for (const t of ['{', '[1,', '1.2.3', 'tru', '{"a":}', '01.5', '"unterminated', '{"a" 1}', '[1 2]']) {
  let threw = false;
  try { _jsonParseRaw(t); } catch (e) { threw = true; }
  if (!threw) bad('accepted malformed input', JSON.stringify(t));
}
let msg = '';
try { jsonParse('1.2.3'); } catch (e) { msg = String(e.message); }
if (!msg.startsWith('jsonParse: ')) bad('jsonParse error message lost its prefix:', JSON.stringify(msg));
if (/jsonParse: jsonParse: /.test(msg)) bad('jsonParse error message double-prefixed:', JSON.stringify(msg));

// ── 4. the runtime shape jsonParse hands to user code ─────────────────────
// `_jsonConvert` turns objects into Map and null into _None; a _Decimal must pass through as a
// SCALAR rather than being walked as a container (it is an object with `u` and `s` fields).
const parsed = jsonParse('{"amount":0.10,"n":null,"xs":[1.50]}');
if (!(parsed instanceof Map)) bad('jsonParse did not produce a Map for an object');
else {
  const amt = parsed.get('amount');
  if (!(amt && amt._type === '_Decimal')) bad('object field lost its Decimal:', JSON.stringify(amt));
  if (parsed.get('n') !== _None) bad('null did not become _None');
  const xs = parsed.get('xs');
  if (!(Array.isArray(xs) && xs[0] && xs[0]._type === '_Decimal')) bad('array element lost its Decimal');
}

console.log(fails === 0 ? 'json-number-policy-js-gate: OK' : `json-number-policy-js-gate: ${fails} FAILURE(S)`);
process.exit(fails === 0 ? 0 : 1);
HARNESS

node "$TMP/rt.mjs"
