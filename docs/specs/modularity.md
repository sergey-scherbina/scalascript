# Modularity — three layers, status and roadmap

Status: **design / planning**.  Captures the modularity story
across all three layers (compiler / SPI, user `.ssc` imports,
packaging) with what's already shipped vs what's deferred, and
fixes namespace policy + introduces two new milestones
(v1.18 `package` keyword + std layout; v1.19 URL/dep imports).

Companion to [`docs/specs/backend-spi.md`](backend-spi.md) (Layer 1,
landed), the `.sscpkg` archive landed in v1.7 Tier 2 (Layer 3,
partial), and every `std/*` document we've shipped (Layer 2,
growing fast).

## 1. The three-layer model

ScalaScript has modularity at three layers with very
different maturity:

| Layer | What | Status |
|-------|------|--------|
| **1 — compiler / SPI** | sbt module split; Backend SPI plugin discovery via JAR | **Mature** (Stages 1-9.1 of Backend SPI landed) |
| **2 — user `.ssc` imports** | `[name](./path)` binding syntax; directory-as-index; transitive imports | **Working with friction** — see §3 |
| **3 — packaging / distribution** | `.sscpkg` archive format; plugin JAR loading | **Partial** — archive format just landed; registry deferred |

Layer 1 is solid.  Layer 3 is moving (`.sscpkg` landed in
v1.7 Tier 2 commit `6605dc6`).  Layer 2 is where the most
real growth pressure is happening — `std/` has reached 13
files and is on track for 30+ by the time v1.5 / v1.6 /
v1.10 / v1.11.5 / v1.15 / v1.17 all land.  This document
focuses there.

## 2. Layer 1 — compiler / SPI (mature)

The sbt module graph since Backend SPI v0.1:

```
ir
└── backend-spi
    └── core
        ├── backend-jvm
        ├── backend-js          ←─ scalajs depends on this
        ├── backend-scalajs
        ├── backend-scala-source
        ├── backend-html
        ├── backend-css
        └── backend-interpreter ←─ depends on backend-js for SPA emit
            └── cli (aggregates all backends + extras via plugins)
```

Each backend ships its own `META-INF/services/scalascript.backend.spi.Backend`
entry; the CLI grabs them via `ServiceLoader`.  Adding a new
backend (.NET, WASM, Python, native) is a plugin JAR, not a
core modification.

**Healthy.**  No design work needed at this layer for v1.x.

## 3. Layer 2 — user `.ssc` imports (working with friction)

What works:

- `[name1, name2](./path/to/file.ssc)` — binding import of
  specific exported names
- `[name](./dir)` — directory-as-index (`./dir/index.ssc`
  resolves)
- `[name as Alias](./path)` — caller-side aliasing for
  collisions
- Transitive imports propagate on all three backends (v1.1
  step 7 fix)
- `exports:` frontmatter advertises what a module ships
- 13 files in `std/` already use this pattern productively

What's growing:

| Group | Files today | Files projected (incl. planned milestones) |
|-------|------------|---------------------------------------------|
| Algebraic & functor typeclasses (v1.1) | 7 | 7 |
| Runtime utilities (v1.6 / v1.10 / v1.15) | 3 | 8 (+ `monad-control`, `free`, `error`, `coroutines` API, `actors-supervision`) |
| Protocol bindings | 2 (`http`, `mcp` planned) | 5 (`mcp/server`, `mcp/client`, `mcp/types`, `http`, `ws/client` for v1.5 Tier 3) |
| UI components | 1 (`nodes.ssc`) | many (v0.9 std component pack — ~30 components) |
| Aggregators | 1 (`index.ssc`) | 5+ (one per area) |

By the time v1.17 lands, `std/` is plausibly **30-50
files**.  Without a namespace policy, flat-stacking that
many `.ssc` files becomes a navigation problem (try
finding `actors-supervision.ssc` among 47 peers) and a
collision-risk problem (two libraries with `Card.ssc`).

### Friction points

1. **`std/` is flat.**  Already at 13 files; no nesting
   policy.  v1.17 MCP design correctly proposes
   `std/mcp/{server,client,types}.ssc` as the first nested
   area, but the convention isn't documented anywhere.

2. **Imports are file-path-shaped.**  `[X](../std/either.ssc)`
   exposes the on-disk structure.  Reorganising `std/`
   (e.g., moving things into sub-dirs) breaks every
   consumer.  Directory-as-index helps a little but isn't a
   true namespace system.

3. **No `package` keyword in `.ssc`.**  v0.7 marked this as
   "optional, mostly cosmetic" when std was 3 files.  At
   13+ files and growing, namespace collisions across
   third-party libs become real — two libraries can't both
   export `Card`, and the `as Alias` workaround pushes the
   problem to every caller.

4. **No URL / dep imports.**  Third-party `.ssc` library =
   `git submodule` or vendoring.  `.sscpkg` archive format
   (v1.7 Tier 2) is the first foundation; URL imports
   build on top.

5. **`index.ssc` aggregators are manually maintained.**
   Adding a new typeclass to `std/` means editing
   `std/index.ssc` by hand.  Cheap but error-prone — easy
   to forget.

6. **No "what's std vs what's community" policy.**
   `std/actors.ssc` lives next to `std/middleware.ssc`
   next to `examples/std-ui/Card.ssc` — peers in the
   filesystem but very different in meaning.  Need a
   formal boundary.

## 4. Layer 3 — packaging (partial)

What landed (commit `6605dc6` this week):

- `.sscpkg` archive format — zip of `.ssc` files + manifest
- `SscpkgManifest` + `SscpkgLoader` in `core`
- `BackendRegistry.loadSscpkg` SPI hook

What's still open:

- **URL imports** — `[X](https://github.com/user/lib/main/lib.ssc)`
  resolving HTTP and caching locally.  Foundation for a
  decentralised ecosystem before central registry.
- **Dep imports** — `[X](dep:com.org/lib:1.2)` with semver
  resolution and `ssc.lock`.  Steps toward a central
  registry.
- **Registry** — `registry.scalascript.io` with publish /
  yank / search.  Deferred (v0.7 future) until "the
  surface above is well-trodden."

## 5. Decided — namespace policy for `std/`

When a feature ships in `std/`, follow this layout rule:

**One file as long as the area is a single cohesive
concept.**  Examples that stay one-file: `either.ssc`,
`middleware.ssc`, `generators.ssc`, `free.ssc` (when v1.11.5
lands).

**Move to `std/<area>/{server, client, types, index}.ssc`
when the area splits into multiple roles or grows past
~300 lines.**  Examples that go nested:

- `std/mcp/{server, client, types, index}.ssc` — v1.17 ships
  this from day one
- `std/ws/{server, client, types, index}.ssc` — when v1.5
  Tier 3 (WS client) lands
- `std/http/{server, client, types, index}.ssc` — when v1.5
  Tier 2 (HTTP client) lands
- `std/actors/{core, supervision, distributed, index}.ssc`
  — when v1.6 Phase 3 (distributed) lands
- `std/effects/{coroutines, free, algebraic, index}.ssc` —
  when v1.9 / v1.11.5 / v1.12 all ship

**Standard nested-area layout (decided):**

```
std/<area>/
├── types.ssc       # shared data types, ADTs, sum types
├── server.ssc      # outbound / authoritative role (if applicable)
├── client.ssc      # inbound / consuming role (if applicable)
├── core.ssc        # foundational / utility role (if applicable)
└── index.ssc       # aggregator — re-exports all of the above
```

Not every area uses all four; pick the ones that fit.  The
**aggregator (`index.ssc`) is always present** so consumers
can `[X, Y, Z](./std/<area>)` and get everything via
directory-as-index.

### Existing flat files — migration policy

**Don't move them eagerly.**  Each existing flat file
(`either.ssc`, `monaderror.ssc`, etc.) stays where it is.
Move only when:

1. The file grows past ~300 lines and a clear sub-role
   split is visible, OR
2. A sibling file gets added to the same area (e.g., when
   `actors.ssc` gains a supervision module, move both into
   `std/actors/`)

Each move ships its own PR with a `# moved from` redirect
file or a deprecation period; consumers don't break
silently.

## 6. Decided — public vs internal convention

Conventions for marking a file as **public API** (intended
for users) vs **internal** (implementation detail of std):

- **Public** — file lives at `std/<area>/<role>.ssc` or
  `std/<flat>.ssc` and is documented in
  [`std/STD-INDEX.md`](../std/STD-INDEX.md) (TBD when the
  catalogue gets big enough to warrant it).
- **Internal** — file lives at `std/<area>/_internal/` or
  begins with `_` prefix (`std/_runtime-helpers.ssc`).  Not
  expected to be imported from user code; subject to
  breaking change without notice.

This is convention only — there's no compiler-enforced
visibility today.  When `package` keyword lands (v1.18),
internal modules can additionally use `package std.internal.<area>`
to discourage casual import.

## 7. Decided — aggregator policy

`std/<area>/index.ssc` exists if and only if the area has
**2+ public files** that users plausibly import together.

- Hand-maintained — adding a new sibling file means
  updating the aggregator's `exports:` and import list.
- Doc comment at the top of every aggregator explicitly
  lists every re-export so reviewers catch missing entries
  in PRs.
- Future tooling (`ssc check --aggregator-coverage` — out
  of scope for v1.x) could automate validation.

Single-file areas (`std/either.ssc`, `std/middleware.ssc`)
don't get an aggregator — the file IS the aggregator.

## 8. Decided — std vs community

Formal boundary:

| Lives in `std/` | Lives outside `std/` (community packages) |
|------------------|---------------------------------------------|
| Language-level abstractions (typeclasses, monads, effects) | Domain-specific integrations |
| Protocol bindings to the listed core protocols (HTTP, WS, MCP) | Integrations with third-party services (Stripe, Slack, Discord) |
| Runtime primitives (actors, coroutines, generators) | Application frameworks built on those primitives |
| Universal helpers (`middleware.ssc`, `error-handling`) | Opinionated middleware bundles (auth-jwt-spring-style) |

**Heuristic test**: would 90% of `.ssc` projects plausibly
benefit from this?  If yes → std.  If "only e-commerce
apps need it" or "only integration tests" → community
package.

Std `std/ui/` (v0.9 std component pack) is a borderline
case kept in `std/` because the component convention is
language-level (the `object Foo { val css; def render(...): String }`
shape is a feature, not a library), not because every
project needs `Button`.

## 9. v1.18 — `package` keyword + std layout migration

Promotes the v0.7 "package keyword (optional, mostly
cosmetic)" out of deferred-future into a real milestone.
Closes the namespace-collision risk between third-party
libraries before community packages start surfacing.

```scala
---
package: org.example.ui
exports: [Card, Button]
---

# Card

```scalascript
object Card { val css = ...; def render(t: String): String = ... }
```
```

Consumer:

```scala
[Card as MyCard](dep:org.example/ui:1.0)
[Card as TheirCard](dep:other.org/components:2.1)

println(MyCard.render("hi"))
println(TheirCard.render("hello"))
```

Both `Card`s coexist because their full qualified names
differ (`org.example.ui.Card` vs `other.org.components.Card`).
Caller still uses aliases for readability.

**Implementation cost**:

- Parser: accept `package:` in frontmatter.  Pure data.
  ~0.5 day.
- Typer: track package name per module; expose qualified
  names for symbol resolution.  ~1 day.
- Codegen per backend: emit `object pkg.name { … }` for
  package-namespaced modules.  ~2 days each × 3 = ~6 days.
- Conformance: collision-free dual-import test.  ~1 day.

Total: ~1.5 weeks.

### Hard-no list

- **Implicit package from directory layout** (`std/mcp/` →
  package `std.mcp`) — explicit `package:` only.
  Directory layout is a navigation concern, package is a
  semantic concern.
- **Wildcard imports** (`import std.mcp.*`) — keep
  per-name `[name1, name2](path)` discipline.  Wildcards
  hide collisions.
- **`package object`** — Scala 3 deprecated these; we
  don't reintroduce.

## 10. v1.19 — URL / dep imports

Builds on v1.7 Tier 2 (`.sscpkg` archive) and v1.18
(`package` keyword) to enable distributed library
distribution before a central registry exists.

```scala
[Card](https://github.com/user/lib/main/Card.ssc)
[Tools](dep:org.example/mcp-tools:1.2)
```

Two resolution strategies:

- **URL imports**: HTTPS-resolved at compile time, cached
  in `~/.cache/scalascript/url-imports/`, integrity-checked
  via SHA-256 of the fetched content (committed to
  `ssc.lock`).
- **Dep imports**: resolved through a lookup chain —
  `dep:org.example/lib:1.2` first checks
  `~/.cache/scalascript/deps/`, then a `~/.config/scalascript/dep-sources`
  list of HTTP endpoints, finally falls back to
  `registry.scalascript.io` once it exists.

Both produce a `ssc.lock` file alongside the entry point;
re-running `ssc check` re-validates lock file consistency.

**Implementation cost**:

- Resolver: URL + dep paths plus integrity-check.  ~5 days.
- Lock file format + re-validation.  ~2 days.
- Cache layout + invalidation.  ~2 days.
- Per-backend integration: each import resolves to a local
  path that backends emit normally.  ~1 day × 3.

Total: ~2 weeks.

### Hard-no list

- **Auto-resolve transitive deps from URL imports** — if a
  URL import has its own URL imports, fail the build with
  an explicit message.  User must hoist the transitive
  imports into their own lock file.  This avoids the
  "single bad upstream takes down the entire ecosystem"
  failure mode.
- **`git://` / `git@` imports** — git clone semantics are
  a separate beast (auth, refs, history).  Use a `.sscpkg`
  hosted at an HTTPS URL instead.
- **Mutable URL imports without integrity check** — every
  URL import gets a SHA-256 in `ssc.lock`; mismatch is a
  build error.
- **Pre-`ssc.lock` semantics** — when running on a fresh
  checkout without a lock file, refuse to fetch silently
  with a default-pin policy; require `ssc lock` to be
  invoked explicitly.

## 11. Hard-no list (decisions across all of §5-§10)

| Feature | Reason |
|---------|--------|
| **Renaming or removing existing flat `std/*.ssc` files eagerly** | Migration only when sub-roles emerge or file grows; consumers don't break silently |
| **Compiler-enforced public vs internal visibility** | Convention only in v1.x; promote to enforced if abuse becomes real |
| **Auto-generated aggregator `index.ssc`** | Hand-maintained for v1.x; tooling later when std catalogue justifies it |
| **Wildcard imports** | Per-name discipline; wildcards hide collisions |
| **Auto-transitive URL imports** | Hoist into your lock file; one-bad-upstream-breaks-ecosystem prevention |
| **Git-protocol imports** | HTTPS-hosted `.sscpkg` instead |

## 12. Technical debt — JvmGen `inlineImport` bare-name leakage

When the JVM backend inlines a `[name](./dep.ssc)` import, it parses
the dep, wraps its code in the dep's `package:` objects, and emits an
`import std.pkg.{name, …}` line so the caller can use the bound names
unqualified. Two problems sit inside this otherwise-working flow:

1. **Duplicate `object pkg { object sub { … } }` wrappers** when
   several imports share a package prefix (e.g. three files all
   declaring `package: std.mapreduce`). Each import emits its own
   wrapper, Scala 3 refuses to compile duplicate top-level objects.
   **Mitigated** 2026-05-19 by `JvmGen.mergeDuplicatePackageObjects`
   (brace-balanced string scanner that merges same-name top-level
   `object` blocks). scala.meta was tried first but parse fails on the
   300 KB+ emitted preamble, so the merger uses string-level scanning
   with proper string/`//` comment escaping.

2. **Bare-name top-level helpers don't resolve from inside the
   `object pkg { object sub { … } }` wrappers**. A dep that uses
   ScalaScript-level intrinsics like `self()`, `receiveWithTimeout`,
   `connectNode`, `spawn`, etc. relies on them being available
   unqualified — they're injected by JvmGen's runtime preamble at the
   true top level (e.g. `Actor.self()`) AND `genModule` rewrites bare
   calls to qualified ones for user-code blocks. But `inlineImport`
   bypasses that rewrite stage: dep blocks are pulled in via
   `collectBlocks` directly, so bare names stay bare and Scala can't
   resolve `self()` from inside `object std { object mapreduce { … } }`
   (there is no `self()` in that scope; the qualified `Actor.self()`
   exists but is not imported into the wrapper).

   Surface symptom: `Not found: self`, `Not found: connectNode`,
   `value ! is not a member of Any` (the actor send sugar) when
   compiling a test that uses `import std/mapreduce`.

   The proper fix involves either (a) routing `inlineImport` blocks
   through the same bare-name → qualified-name rewriting pipeline
   that `genModule` applies to user code (medium scope, touches the
   rewriter's `emitBlock` path), or (b) auto-emitting helper imports
   inside each wrapped package block (smaller, but needs JvmGen to
   know which helpers exist — currently that knowledge is implicit in
   the preamble template).

   **Why option (a) didn't drop in cleanly** (attempted 2026-05-19,
   reverted): widening `statsUseEffects` to recurse through
   `Defn.Object` / `Defn.Class` / `Defn.Trait` bodies and adding
   matching emit cases (`emitObjectLike` / `emitClassLike` /
   `emitTraitLike`) is straightforward enough. So is adding a
   `Defn.Def if termNeedsCustomEmit(body)` arm to route method bodies
   through `emitDefWithRewrittenBody`. But the rewriting pipeline
   was built around top-level user-code shapes — once dep-block
   members start flowing through it, `actors-process-info.ssc` and
   similar regress: `info.links` becomes `_bind(info.links, …)`
   because the CPS-wrapping arm now fires on subterms whose receiver
   is `Any`-typed inside a case class body (no static `info: Some` to
   prove `.links` resolves). A real fix needs:
   - a stronger filter on `termNeedsCustomEmit` for dep contexts
     (only rewrite the specific shapes that benefit — `actorBareNames`
     and bare intrinsic calls — not the whole `termUsesEffects`
     blanket); or
   - a separate `dep-mode` emit path that rewrites bare-name actor
     calls only and leaves the rest of the source verbatim.

   Pending design pass; conformance suite still uses the `pending:`
   marker to keep the v1.22 tests out of the FAIL count.

   Tracked in `MILESTONES.md` "Known issues / latent flakes". The six
   v1.22 distributed-* conformance tests carry `pending: needs std/
   mapreduce/* auto-resolution in \`ssc compile\`` until this lands;
   they document the intended API but don't run in the suite.

   The bare-name layer (this section) is the *textual* / namespace
   problem. The deeper *semantic* problem — dep code calling effect
   primitives without going through CPS rewriting — is documented
   separately in `docs/specs/dep-cps-rewrite.md`, which captures the
   architectural analysis and a concrete implementation plan for
   the rewriter side.

## 13. Open questions

- **`ssc.lock` format** — minimal JSON-with-SHAs, or YAML
  with structured groups by source?  Lock when v1.19
  starts.
- **Centralised vs federated registry** — decentralised
  (Maven Central style: HTTP path lookup, no central
  authority) or central index?  Decide when registry
  proper starts (v0.7's "Registry future" item).
- **`package` keyword reach** — frontmatter-only, or also
  inline `package` statements?  Frontmatter-only for v1.18;
  inline maps to "each file = one module" which is
  ScalaScript's model anyway.
- **Std-side organisational refactor** — when to merge
  several flat files into a nested area (e.g., when does
  `actors.ssc` get joined by `actors-supervision.ssc` →
  move both into `std/actors/`)?  Per the migration
  policy in §5: only when the second sibling lands.
- **Internal-marker convention precedence** — `_` prefix
  vs `_internal/` directory.  Probably both, with `_` for
  one-off helpers and `_internal/` for whole sub-systems.
  Lock when the first internal module ships.
- **What's in v1.19 vs v1.19.1** — does the registry MVP
  ship as v1.19.1 or stay deferred?  Probably deferred —
  v1.19 ships URL + dep with HTTP fallback list; central
  registry is a deployment + ops concern that needs its
  own milestone.

## 13. Implementation phases — v1.18 + v1.19

Per §9 and §10 above.  Both can land in parallel after the
docs lock — v1.18 is parser + typer + codegen work; v1.19
is resolver + lock file + cache work; no overlap.

Total combined: ~3.5 weeks.

## 14. Cross-references

| Layer | Document | Status |
|-------|----------|--------|
| 1 (compiler/SPI) | [`docs/specs/backend-spi.md`](backend-spi.md) | Landed |
| 1 (SPI followups) | [`docs/specs/spi-followups-plan.md`](spi-followups-plan.md) | Landed |
| 3 (packaging) | v1.7 Tier 2 `.sscpkg` | Landed (commit `6605dc6`) |
| 2 (this doc) | **`docs/specs/modularity.md`** | This document |
| 2 (v1.18) | v1.18 milestone — `package` keyword | New |
| 2 (v1.19) | v1.19 milestone — URL/dep imports | New |

## 15. Decision summary (TL;DR)

**Decided** (locked in this document):

- Three-layer model documented
- `std/<area>/{server, client, types, core, index}.ssc`
  nesting convention when an area has multiple roles or
  grows past ~300 lines
- One-file areas stay flat; lazy migration only on sibling
  arrival or size threshold
- `_` prefix / `_internal/` directory for non-public modules
- `index.ssc` aggregator hand-maintained, present iff 2+
  public files
- `std/` boundary vs community packages: language-level &
  protocol bindings vs domain-specific & opinionated
- `package` keyword promoted from "v0.7 cosmetic-future"
  to v1.18 real milestone
- URL / dep imports as v1.19 milestone after v1.18

**Open** (carry-forward — see §12):

- `ssc.lock` format
- Central registry timing
- Internal-marker precedence
- Std-side organisational refactor timing
- Registry MVP versus v1.19 scope
