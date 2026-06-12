# UUID Support — Spec

**Status:** COMPLETE — p1/p2/p3/p4/p5 landed 2026-06-04; p6 (monotonic v7) landed 2026-06-12 on JVM **and** JS  
**Milestone:** v1.65 — UUID Library  
**Primary goal:** UUID v7 (time-ordered) for database primary keys

## What's already built (p1–p5)

```
runtime/std/uuid.ssc          — opaque type Uuid, v4/v7/rawV4/rawV7/fromString/unsafeFromString/isValid/nil/max + extensions
runtime/std/uuid-plugin/      — JVM SecureRandom v4/v7, regex parse/validate, raw tier, withFixedUuid support
runtime/backend/js/           — uuidV4/uuidV7/rawUuidV4/rawUuidV7/parse/validate runtime calls
frontend effect analysis      — Uuid.v4/v7 propagate SideEffect; Uuid.rawV4/rawV7 do not
```

`uuid.ssc` surface landed:
```scalascript
opaque type Uuid = String
object Uuid:
  extern def v4(): Uuid
  extern def v7(): Uuid
  extern def rawV4(): Uuid
  extern def rawV7(): Uuid
  extern def fromString(s: String): Option[Uuid]
  extern def unsafeFromString(s: String): Uuid
  extern def isValid(s: String): Boolean
  val nil: Uuid = "00000000-0000-0000-0000-000000000000"
  val max: Uuid = "ffffffff-ffff-ffff-ffff-ffffffffffff"
extension (u: Uuid)
  def asString: String = u
  def version: Int = ...
  def isNil: Boolean = ...
  def isMax: Boolean = ...
  def variant: Int = ...
```

Validation already covers the interpreter plugin, JS runtime mapping, effect
primitive detection, and dependency effectfulness propagation. `runSideEffect`
and `withFixedUuid` landed with p4. `uuid-p6` remains optional: JVM-only
within-millisecond monotonic UUID v7 counter support.

---

## 1. Goals

- Expose `Uuid.v7()` — time-ordered (RFC 9562) UUID suitable for database PKs and event IDs; lexicographically monotonic.
- Expose `Uuid.v4()` — cryptographically random UUID (same semantics as `Random.uuid()` but without a seeded handler).
- `Uuid` is a **true opaque type** — the compiler distinguishes UUID values from arbitrary strings; no `asInstanceOf` in user code.
- Safe opaque boundary: `Uuid.fromString`, `Uuid.unsafeFromString`, `.asString` — every crossing is explicit and named.
- Parse and validate UUID strings (`Uuid.fromString`, `Uuid.isValid`).
- Effect tracking: `Uuid.v4()` and `Uuid.v7()` carry a new **`SideEffect`** algebraic effect; adapters allow pseudo-pure and raw/low-level usage.
- SQL-side generation is optional and also supported — developer chooses per use case (app-generated `Uuid.v7()` is primary; SQL `gen_random_uuid()` / future `uuid_v7()` is opt-in).
- Both JVM and JS backends.

## 2. Non-goals

- UUID v1 (MAC address — privacy risk, not monotonic in a useful way).
- UUID v2 (DCE — obsolete).
- UUID v3/v5 (name-based MD5/SHA1 — no current use case).
- UUID v8 (custom — no spec yet).
- Seeded/deterministic UUID v7 — time-ordering comes from the real wall clock. Test reproducibility uses `Random.uuid()` (seeded v4) or `withFixedUuid` (see §3.5).
- Forcing SQL-side UUID generation — it is an optional capability, not the default.

## 3. Architecture

### 3.1 New stdlib module

`runtime/std/uuid.ssc` — package `std.uuid`.

### 3.2 New plugin

```
runtime/std/uuid-plugin/
  src/main/scala/scalascript/compiler/plugin/uuid/
    UuidInterpreterPlugin.scala
    UuidIntrinsics.scala
  src/main/resources/META-INF/services/
    scalascript.backend.spi.Backend
  src/test/scala/scalascript/compiler/plugin/uuid/
    UuidPluginTest.scala
```

Follows the same layout as `json-plugin`.

### 3.3 .ssc surface — opaque type and safe boundary

```scalascript
// package std.uuid

opaque type Uuid = String

object Uuid:
  // ── Generators (carry ! SideEffect) ──────────────────────────────────
  extern def v4(): Uuid ! SideEffect
  extern def v7(): Uuid ! SideEffect

  // ── Safe boundary crossings ───────────────────────────────────────────
  extern def fromString(s: String): Option[Uuid]     // validated; None if malformed
  extern def unsafeFromString(s: String): Uuid       // for known-valid strings; throws on malformed
  extern def isValid(s: String): Boolean

  // ── Well-known constants (constructed through unsafeFromString) ───────
  val nil: Uuid = unsafeFromString("00000000-0000-0000-0000-000000000000")
  val max: Uuid = unsafeFromString("ffffffff-ffff-ffff-ffff-ffffffffffff")

extension (u: Uuid)
  def asString: String = u               // opaque unwrap — explicit, named
  def version: Int     = u.charAt(14).asDigit
  def isNil: Boolean   = (u: String) == "00000000-0000-0000-0000-000000000000"
  def isMax: Boolean   = (u: String) == "ffffffff-ffff-ffff-ffff-ffffffffffff"
  def variant: Int     = Integer.parseInt(u.substring(19, 20), 16) >> 2  // 2 = RFC4122
```

**No `asInstanceOf` in user code.** The opaque type boundary is crossed only through:
1. `Uuid.fromString` / `Uuid.unsafeFromString` — String → Uuid
2. `.asString` extension — Uuid → String
3. Internally in the plugin, the runtime representation is always `String`; the opaque distinction is compile-time only.

### 3.4 New effect: `SideEffect`

`Uuid.v4()` and `Uuid.v7()` are non-deterministic (CSPRNG + wall clock). They carry a new algebraic effect `SideEffect`, separate from `Random` (which is seeded and reproducible).

**Why a new category, not `! Random`:**
- `Random` is seeded — `runRandomSeeded(42)` makes it deterministic. UUID v7 reads the real wall clock; seeding it is meaningless.
- In the future, other IO primitives (non-seeded timestamp reads, OS-level entropy) belong in the same category.

```scalascript
// Canonical handler — just executes the effects, no mocking:
extern def runSideEffect[A](body: A ! SideEffect): A

// Test helper — substitutes a fixed UUID for all Uuid.v4/v7 calls in scope:
extern def withFixedUuid[A](fixed: Uuid)(body: A ! SideEffect): A
```

#### 3.4.1 Handled and raw usage

For contexts where tracking `! SideEffect` is inconvenient (script top-levels,
one-off tooling, interop with non-effect-aware code), the shipped surface has
two escape hatches:

```scalascript
// Handler — preserves the explicit SideEffect boundary.
val id = runSideEffect { Uuid.v7() }
```

```scalascript
// Raw — low-level primitive with no effect tracking, for library authors and
// interop. Named explicitly to make impurity visible at the call site.
object Uuid:
  extern def rawV4(): Uuid   // no SideEffect annotation
  extern def rawV7(): Uuid   // no SideEffect annotation
```

Backend intrinsic names are `rawUuidV4` / `rawUuidV7`; the public `.ssc`
surface exposes them as `Uuid.rawV4()` / `Uuid.rawV7()`.

Hierarchy: `v7()` (effectful, tracked) > `runSideEffect { v7() }`
(handled) > `rawV7()` (raw, no annotation).

Guideline:
- Default user code: `val id = runSideEffect { Uuid.v7() }`
- Script / top-level: `val id = runSideEffect { Uuid.v7() }`
- Library interop: `val id = Uuid.rawV7()`

#### 3.4.2 Effect system wiring

- `ContainsEffectPrimitive`: register `("Uuid", "v4")` and `("Uuid", "v7")` alongside `("Random", "uuid")`.
- `DepEffectfulnessFixpoint`: functions that call `Uuid.v4()` or `Uuid.v7()` (without `runSideEffect`) propagate `SideEffect`.
- `SideEffect` is a new effect row label; the `runSideEffect` handler eliminates it.

### 3.5 Intrinsics table

| QualifiedName        | JVM                                                            | JS                                          |
|----------------------|----------------------------------------------------------------|---------------------------------------------|
| `uuidV4`             | `java.util.UUID.randomUUID().toString`                         | `crypto.randomUUID()`                       |
| `uuidV7`             | custom: `System.currentTimeMillis()` + `SecureRandom`          | custom: `Date.now()` + `crypto.getRandomValues()` |
| `uuidFromString`     | regex validate → `Some(s)` or `None`                           | same                                        |
| `uuidUnsafeFromString`| regex validate → `s` or throw `InterpretError`                | same                                        |
| `uuidIsValid`        | regex validate → `Boolean`                                     | same                                        |
| `rawUuidV4`          | same as `uuidV4` (no effect tracking)                          | same                                        |
| `rawUuidV7`          | same as `uuidV7` (no effect tracking)                          | same                                        |
| `runSideEffect`      | identity handler (just runs the body)                          | same                                        |
| `withFixedUuid`      | installs thread-local override; restores after body            | same                                        |

### 3.6 UUID v7 bit layout (RFC 9562 §5.7)

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                           unix_ts_ms                          |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|          unix_ts_ms           |  ver  |        rand_a         |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|var|                         rand_b                            |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                           rand_b                              |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

- Bits 0–47: `unix_ts_ms` — 48-bit unsigned millisecond Unix timestamp (big-endian)
- Bits 48–51: `ver` = `0b0111` (7)
- Bits 52–63: `rand_a` — 12 bits of CSPRNG (p4: monotonic counter within same ms)
- Bits 64–65: `var` = `0b10` (RFC 4122 variant)
- Bits 66–127: `rand_b` — 62 bits of CSPRNG

Result format: `xxxxxxxx-xxxx-7xxx-[89ab]xxx-xxxxxxxxxxxx`

UUID v7 strings are lexicographically sortable: a later UUID always compares greater than an earlier one.

### 3.7 SQL integration (optional, both modes supported)

Developer chooses per use case:

**Mode A — app-generated (primary):** generate `Uuid.v7()` in ScalaScript before the INSERT.
```scalascript
val id = runSideEffect { Uuid.v7() }
sql"INSERT INTO events (id, ...) VALUES ($id, ...)"
```

**Mode B — DB-generated (optional, future SQL plugin follow-up):** let the database generate the UUID.
```scalascript
// Future: SQL plugin adds uuid_v7() / gen_random_uuid() SQL functions
val id = sql"INSERT INTO events (...) VALUES (uuid_generate_v7(), ...) RETURNING id"
  .query[Uuid].head
```

Mode B is a follow-up to the SQL plugin; out of scope for uuid-p1 through uuid-p3.

### 3.8 Interaction with `Random.uuid()`

`Random.uuid()` stays unchanged — seeded v4 for test reproducibility under `runRandomSeeded`. No deprecation.

New code:
- `Uuid.v7()` for database PKs and event IDs (sortable, unique, time-ordered).
- `Uuid.v4()` for session tokens and temporary IDs (random, opaque).
- `Random.uuid()` only in tests under `runRandomSeeded`.

### 3.9 build.sbt additions

```scala
lazy val uuidPlugin = project
  .in(file("runtime/std/uuid-plugin"))
  .dependsOn(backendSpi, pluginApi, ir, core, testUtils % Test)
  .settings(
    name := "scalascript-uuid-plugin",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )
  .settings(sscpkgSettings("scalascript.std.uuid"))
```

Plus `PluginSpec("uuid", uuidPlugin, "scalascript-uuid-plugin")` in `allPlugins`.

## 4. Migration

No existing public API changes. `Random.uuid()` remains.

## 5. Phases

### ✓ Phase 1 — Core JVM (`uuid-p1`, landed 2026-06-04)

`uuid.ssc` + `uuid-plugin` with JVM `SecureRandom` v4/v7, `build.sbt` wiring, 8 tests.

### ✓ Phase 2 — Parse + Validate (`uuid-p2`, landed 2026-06-04)

`uuidFromString` / `uuidIsValid` intrinsics; `Value.OptionV` return; tests.

### ✓ Phase 3 — JS backend (`uuid-p3`, landed 2026-06-04)

`JsUuidIntrinsics` with Web Crypto API (`crypto.randomUUID()` for v4, custom `Date.now()` for v7).

### ✓ Phase 4 — Opaque boundary hardening + effect system (`uuid-p4`, landed 2026-06-04)

- `Uuid.unsafeFromString(s: String): Uuid` — named intrinsic for known-valid strings; throws on malformed; replaces raw string literal coercion for `nil`/`max`
- Extension methods: `.version: Int`, `.isNil: Boolean`, `.isMax: Boolean`, `.variant: Int`
- `SideEffect` effect row label (new category, separate from `Random`)
- Register `Uuid.v4` / `Uuid.v7` in `ContainsEffectPrimitive` + `DepEffectfulnessFixpoint`
- `runSideEffect[A](body: A ! SideEffect): A` — trivial identity handler
- `withFixedUuid[A](fixed: Uuid)(body: A ! SideEffect): A` — thread-local override for deterministic tests

### ✓ Phase 5 — Raw tier (`uuid-p5`, landed 2026-06-04; bundled with p4)

- `Uuid.rawV4(): Uuid` + `Uuid.rawV7(): Uuid` — public `.ssc` wrappers without `! SideEffect` annotation; for library authors and interop code
- `rawUuidV4(): Uuid` + `rawUuidV7(): Uuid` — backend intrinsic names used by the interpreter/JS runtime

### ✓ Phase 6 — Monotonic v7 counter (`uuid-p6`, landed 2026-06-12, `bcb687ec3`)

- `rand_a` (12 bits, bits 52–63) used as a dedicated within-millisecond counter (RFC 9562
  §6.2 Method 1): seed in the lower half (11 bits) each new ms for increment headroom,
  advance by 1 within the same ms, and on overflow (>0xFFF) spin to the next ms + reseed.
  A clock-rewind guard never moves the timestamp backwards. Because `rand_a` is more
  significant than `rand_b`, a larger counter always yields a lexicographically larger UUID.
- `Uuid.v7Monotonic(): Uuid ! SideEffect` as explicit opt-in; default `v7()` stays CSPRNG-only.
- **Shipped on both backends** (the original "JVM-only / no JS equivalent" plan was superseded):
  JVM `UuidIntrinsics.generateV7Monotonic` (guarded by `monoLock`) and JS
  `JsRuntimePart2b.uuidV7Monotonic` (mirrors the same algorithm). Tests: `UuidPluginTest`
  (valid v7 + strictly increasing across many same-process calls).

## 6. Testing strategy

| Test                | What to verify |
|---------------------|----------------|
| Format              | Matches `[0-9a-f]{8}-[0-9a-f]{4}-[47][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}` |
| Version bit         | `v4()` → `version == 4`; `v7()` → `version == 7` |
| Variant bit         | Both → `variant == 2` (RFC 4122) |
| Ordering            | 100 consecutive handled or raw v7 calls: each ≥ previous (lexicographic) |
| Uniqueness          | 1000 handled or raw v4 calls: no duplicates |
| Opaque boundary     | `Uuid.fromString(id.asString) == Some(id)`; `Uuid.fromString("bad") == None` |
| `unsafeFromString`  | Valid input returns Uuid; invalid throws |
| `withFixedUuid`     | All `v4()`/`v7()` calls inside the block return the fixed UUID |
| Extension methods   | `.version`, `.isNil`, `.isMax`, `.variant` return correct values |
| Cross-backend (p3)  | JVM and JS produce format-compatible UUIDs |

## 7. Open questions — resolved

1. **`opaque type Uuid` vs `type Uuid = String`**: **Resolved — opaque.** Boundary crossings are explicit named methods (`fromString`, `unsafeFromString`, `.asString`). No `asInstanceOf` in user code; the plugin uses raw String internally as the runtime representation.

2. **Effectfulness of `Uuid.v7()`**: **Resolved — new `SideEffect` category.** `Uuid.v4()` and `Uuid.v7()` carry `! SideEffect`. Three tiers of usage: `v7()` (tracked), `runSideEffect { Uuid.v7() }` (handled), `Uuid.rawV7()` (no annotation). `withFixedUuid` enables deterministic testing.

3. **SQL-side UUID generation**: **Resolved — both modes supported, app-generated is primary.** `Uuid.v7()` in ScalaScript before INSERT is the default. SQL-side generation (`uuid_generate_v7()` etc.) is a follow-up to the SQL plugin; no blocker on uuid-p1.
