# `std.ui` — Typed JSON in/out for `fetch*`

Status: **planned**. Tracked as `ui-typed-json` milestone in SPRINT.md.
Origin: busi UI proposals (P1, highest ROI) — `busi/docs/scalascript-ui-proposals.md`.
Related: [`specs/std-yaml.md`](std-yaml.md). std/ui surface: `runtime/std/ui/primitives.ssc`.

---

## 1. Motivation

`fetchUrlSignal` returns a raw `String`. There is no client-side JSON *decode*,
so every busi screen hand-scans the response string:

```scalascript
def onbStr(json: String, key: String): String =
  val q = "\"" + key + "\":"
  val i = json.indexOf(q)
  ... substring / indexOf juggling ...    // web/app.ssc has a 3rd copy: extractStr
```

And there is no shared client-side JSON *encode*, so the escape helper is
duplicated **13×** across screens (`appQ`, `clientsQ`, `peerQ`, …), each byte-identical,
with POST bodies assembled by string concatenation:

```scalascript
"\"yaml\":" + studioQ(yamlSig()) + ",\"name\":" + studioQ(nameSig())
```

This is the single most duplicated and most fragile pattern in the busi frontend,
and the exact place the silent `jObj(...)` paren NPE bites (see
[`std-ui-jobj-failloud`](std-ui-styled-tknode.md) — P4b is tracked separately).

The runtime already has the parsing machinery: the `json-plugin` exposes
`jsonParse` / `jsonRead` / `jsonStringify` / `lookup` / `lookupOpt` (backed by
`JsonParser` in the interpreter, with a JS preamble counterpart). The gap is purely
the **client-side fetch seam**: a navigable value type, a fetch signal that decodes,
and a structured body encoder. Pure language/stdlib work, independent of any
Markdown/native-UI question.

---

## 2. API

New surface in `runtime/std/ui/` (a new `json.ssc` module under `std.ui`, re-exported
from the ui barrel), plus one new fetch primitive in `primitives.ssc`.

### 2.1 Navigable JSON value

```scalascript
opaque type JsonValue = Any   // backed by the interpreter's parsed Value tree

// Parse a JSON string into a navigable value. Tolerant: malformed input yields
// JsonValue.Null rather than throwing (client decode of a server error body must
// not crash a screen). Use jsonParseStrict when a throw is wanted.
extern def jsonValue(s: String): JsonValue
extern def jsonParseStrict(s: String): JsonValue   // throws on malformed input

extension (v: JsonValue)
  // Navigation — always total, never throws. Missing key / wrong shape → Null.
  def get(key: String): JsonValue          // object field, else Null
  def at(idx: Int): JsonValue              // array element, else Null
  def isNull: Boolean

  // Typed extraction with sensible zero-defaults (never throws).
  def asString: String                     // "" if not a string
  def asInt: Int                           // 0 if not an int-shaped number
  def asDouble: Double                     // 0.0 if not a number
  def asBool: Boolean                      // false if not a bool
  def asList: List[JsonValue]              // [] if not an array

  // Optional extraction — None on missing/wrong-shape, for explicit handling.
  def opt: Option[JsonValue]               // None if Null, else Some(this)
  def optString: Option[String]
  def optInt: Option[Int]

  // Default-on-miss convenience.
  def getOrElse(key: String, fallback: String): String   // get(key).asString or fallback
```

Navigation chains read fluently and total:

```scalascript
sumSig().get("steps").asList.map(s => s.get("label").asString)
sumSig().get("business").get("name").getOrElse("country", "??")
```

### 2.2 Structured JSON encoding (request bodies)

Reuse the value-builder vocabulary busi already ships server-side in `infra/json.ssc`,
promoted to `std.ui` so screens build request bodies as values, not concatenated strings.
The runtime owns all escaping:

```scalascript
opaque type JsonField = Any

extern def jStr(s: String): JsonValue
extern def jNum(n: Double): JsonValue
extern def jBool(b: Boolean): JsonValue
def jNull: JsonValue
extern def jArr(items: List[JsonValue]): JsonValue
extern def jField(key: String, value: JsonValue): JsonField
extern def jObj(fields: List[JsonField]): JsonValue

// Serialize a JsonValue to a compact JSON string (runtime-escaped).
extern def jsonStringify(v: JsonValue): String
```

### 2.3 Fetch signals that speak JSON

```scalascript
// Decode: GET url on mount + whenever refreshTick increments; the signal yields a
// navigable JsonValue (tolerant parse of the response body), not a String.
extern def fetchJsonSignal(name: String, url: String, refreshTick: Signal[Int],
                           headers: Signal[String] = emptyHeaders): Signal[JsonValue]

// Encode: fetchAction already takes body: Signal[String]. Add a JSON overload that
// takes a JsonValue-producing thunk; the runtime stringifies + escapes at click time.
extern def fetchJsonAction(method: String, url: String, body: () => JsonValue,
                           onSuccessTick: Signal[Int],
                           headers: Signal[String] = emptyHeaders): EventHandler
```

`computedSignal` continues to work for deriving display strings from a
`Signal[JsonValue]`:

```scalascript
val sumSig  = fetchJsonSignal("onbSummary", url, tick, authHdr)
val name    = computedSignal(() => sumSig().get("businessName").asString)
val missing = computedSignal(() => sumSig().get("missingCount").asInt.toString)
```

---

## 3. Semantics

- **Totality of navigation.** `get`/`at`/`as*` never throw. Missing keys, wrong
  shapes, and parse failures all funnel to `JsonValue.Null` / zero-defaults. This is
  deliberate: a screen decoding an unexpected server response must degrade, not crash.
  `opt*` accessors and `jsonParseStrict` are the escape hatch when a caller wants to
  branch on absence explicitly.
- **Number model.** JSON has one number type. `asInt` truncates toward zero on a
  fractional value; `asDouble` is the lossless accessor. `optInt` returns `None` for a
  non-integer-shaped number.
- **Encoding ownership.** `jStr`/`jObj`/`jArr` escape `"`, `\`, control chars, and
  newlines in the runtime. Screens never hand-build JSON strings, which removes the
  13 `*Q` escapers and the paren-NPE foot-gun by construction.
- **Backed by existing machinery.** `jsonValue`/`jsonStringify` wrap the interpreter's
  `JsonParser` + `jsonToJson` already used by `json-plugin`; no second parser. The
  navigable accessors are thin wrappers over `lookupKey` + value coercion.

---

## 4. Backend policy

| Backend | Decode | Encode | Notes |
|---|---|---|---|
| JVM interpreter | `JsonParser.parse` → Value tree | `jsonToJson` | reuse `json-plugin` core; new accessors in interp/plugin |
| JS / Node | `JSON.parse` in preamble | `JSON.stringify` w/ runtime escape | `fetchJsonSignal` lowers to `fetch().then(r => r.json())` |
| Browser (emit-spa) | same JS preamble | same | navigable accessors compile to plain property/index access |
| Native (swing/javafx/swiftui) | JVM path (`JsonParser`) | `jsonToJson` | data-layer only — no UI coupling, works as-is |

`fetchJsonSignal` is the only piece with a per-backend fetch lowering; it mirrors the
existing `fetchUrlSignal` lowering and additionally JSON-decodes the body.

---

## 5. Non-goals (v1)

- Schema validation / typed case-class decoding (`derives JsonCodec`). Future work;
  the navigable `JsonValue` is the v1 contract.
- Streaming / incremental parse. Bodies are parsed whole.
- Pretty-printing. `jsonStringify` emits compact JSON only.
- JSON5 / comments / trailing commas. Strict RFC 8259 input.

---

## 6. busi adoption

Migrate screens off the 13 `*Q` escapers and the `onbStr`/`extractStr` decoders to
`fetchJsonSignal` + `jObj` bodies. Touches nearly all 17 screens; removes the most
duplicated and fragile frontend pattern. Independent of P2/P3.

---

## 7. Verify

- Round-trip: `jsonStringify(jObj([jField("k", jStr("a\"b"))]))` parses back to a
  `JsonValue` whose `get("k").asString == "a\"b"` (escaping correct).
- Totality: `jsonValue("not json").get("x").asString == ""`; `.opt == None`.
- Number coercion: `jsonValue("{\"n\":3.7}").get("n").asInt == 3` and `.asDouble == 3.7`.
- `fetchJsonSignal` over a stub endpoint yields a navigable value; `computedSignal`
  derived from it re-renders on `refreshTick`.
- Example `examples/ui-typed-json.ssc` runs under `ssc run`.
