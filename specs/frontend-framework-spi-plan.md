# Frontend framework SPI plan

Design spec for pluggable frontend frameworks in the scalajs-spa
backend.  Same shape as the HTTP/WS server SPI
(`specs/http-server-spi-plan.md`): a thin trait at the network /
framework boundary, multiple impls below it, the user-facing API
(routes, components, state) stays backend-agnostic.

Audience: future-me when someone asks "why can't I render this SPA
with Solid?" or "we need React because the design system is already
React."

## Why this is interesting

The scalajs-spa backend today renders SPAs by:
1. Compiling `.ssc` → JS via `JsGen` (codegen target `scalajs-spa`).
2. Emitting a small client-side runtime that registers `route(...)`
   handlers and dispatches based on `window.location.pathname`.
3. User code writes `Response.html(html"...")`; the runtime sets
   `document.body.innerHTML` to the result.

This works but is conceptually **server-rendered with client-side
routing** — there's no real component tree, no reactivity, no
incremental DOM updates.  The output is a string of HTML that
replaces the whole page on each route change.

Real SPAs need:
- A **component tree** (composition, props, slots).
- **Reactivity** (state changes → only the affected nodes re-render).
- **Lifecycle hooks** (mount, unmount, effect).
- **Forms with bidirectional binding** (or one-way + handlers).
- **Refs / DOM access** for libraries that need it.
- **Routing with code-split lazy loading** (eventually).

Today's scalajs-spa fakes the first two via "diff the new HTML
string against the previous" or "innerHTML and hope".  Both have
performance + correctness costs.

A frontend SPI would let us share the .ssc-language-level component
model + reactivity primitives across multiple framework backends.
Same source compiles to React, Vue, Solid, or a custom runtime —
the user picks based on their team's preferences, the design
system they're consuming, or the perf budget.

## Status quo

- `backend-scalajs` registers as target `scalajs-spa` (see
  `docs/architecture.md` and `specs/backend-spi.md`).
- JsGen-with-`scalajs-spa`-cap emits a JS string that includes:
  - The `route()` registry built up at module-load time.
  - A startup hook that reads `window.location.pathname` and
    invokes the matching handler.
  - Helpers for `html"..."` interpolation → DOM string injection.
- No virtual-DOM, no component instance tree, no reactivity
  primitives.  State changes redirect to a different route or
  manually re-call the route handler.
- MCP client support (`mcpConnect(Transport.Http)`) lands here via
  `JsRuntimeMcpBrowser` — proves the backend-scalajs runtime can
  host non-trivial async logic, not just static rendering.

## Why an SPI (vs. ship one framework)

Two paths to "real components" exist:

### Path A — Build one custom runtime

Write a minimal framework in Scala that compiles to the scalajs-spa
target.  Like a tiny version of Solid.js but in Scala.  Total
control, no external runtime dependency, exact semantics we choose.

Pros: smallest output bundle, exact semantics, no version-skew
worries with upstream framework.
Cons: maintaining a framework is a long-term commitment.  We'd
end up reimplementing what React / Vue / Solid already do well,
likely with bugs they've already fixed.  Years of work.

### Path B — Plug into existing frameworks via an SPI

Define a trait that describes "what a frontend framework needs to
expose to scalajs-spa output".  Implement it for React, Vue, Solid,
maybe Svelte.  User picks at compile time.

Pros: leverage existing ecosystems (component libraries, devtools,
SSR stories), more user-friendly than rolling our own,
framework-specific perf optimisations stay where they belong.
Cons: API has to be the intersection of what the frameworks
support — features in one might not work in others.  Cross-impl
bug surface.

**Recommendation: Path B (SPI).**  Same logic as the HTTP/WS SPI:
the cost of maintaining a framework is enormous compared to
wrapping existing ones.

## Where the SPI cuts

The depth of the cut matters a lot.  Three options:

### Cut depth 1 — Output-level SPI

The SPI lives at the JS-emit boundary: same .ssc AST goes in,
framework-specific JS comes out.

```
.ssc → AST → [normalize / typer / lowering ] → FrameworkBackend.emit(...)
                                                ├── ReactBackend.emit
                                                ├── VueBackend.emit
                                                ├── SolidBackend.emit
                                                └── CustomBackend.emit
```

Each backend reads the same lowered IR and emits idiomatic JS for
its framework: JSX for React, single-file components for Vue, JSX
for Solid, runtime closures for the custom one.

Pros: full control over emit; each backend can use its framework's
idioms cleanly.
Cons: maintenance cost — every new language feature needs N
implementations.  And we have to choose the same IR shape that's
expressible in all N frameworks.

### Cut depth 2 — Runtime-adapter SPI

The .ssc compiler emits to a single normalized JS form (e.g. a
virtual-DOM-ish op stream or a reactive-signal graph).  Different
"runtime adapter" libs wire the normalized form to actual
frameworks.

```
.ssc → AST → [normalize / lowering ] → normalized JS + adapter call
                                                            │
                                  ┌─── ReactAdapter   (renders via React.createElement + reconciler)
                                  ├── VueAdapter      (creates VNodes + h() calls)
                                  ├── SolidAdapter    (creates signals + JSX dispatch)
                                  └── CustomAdapter   (interprets ops directly to DOM)
```

Pros: less per-framework code (the normalized form is the contract);
adapters can be third-party.
Cons: normalized form has to LCD all frameworks' capabilities;
performance overhead of an extra translation layer; we'd need to
basically build a virtual-DOM or reactive-graph spec, which is its
own can of worms.

### Cut depth 3 — Component-API SPI

The .ssc syntax stays generic, but the user-facing API has multiple
implementations.  `Component { ... }` blocks compile differently
depending on which framework binding the user opted into.

```scala
// .ssc
def Counter() =
  val (count, setCount) = useState(0)
  div(
    button(onClick = () => setCount(count + 1), "+"),
    span(count.toString)
  )

// User picks backend in build config or top-level:
setFrontendFramework("react")  // or "vue" / "solid" / "custom"
```

Pros: smallest cognitive cost for users; .ssc API stays uniform.
Cons: tight coupling — the .ssc API has to fit all backends.
Some framework idioms (Solid's signals vs React's setState) are
hard to expose uniformly.

**Recommendation: Cut depth 1 (output-level SPI)** initially, with
the option to migrate to depth 2 later if multiple adapters share
enough emit code to make a normalized form worthwhile.

Depth 3 sounds attractive but assumes the .ssc-level API can be
made uniform across all frameworks — empirically that's hard.
React's `useState` vs Solid's `createSignal` aren't just syntactic
differences; the SEMANTICS differ (stale closures, render granularity).
Better to let each backend emit idiomatic code for its framework.

## Candidate frameworks

### React — the safe default

- Largest ecosystem; most component libraries assume React.
- JSX → React.createElement is well-known territory; the lowering
  is mechanical.
- Reconciler model fits "diff the new tree against the old" — same
  shape the existing scalajs-spa runtime tries to fake.
- Bundle size: React + ReactDOM ~140 KB minified, ~45 KB gzipped.

### Vue — the alternative default

- Smaller bundle than React, similar feature surface.
- Template DSL OR JSX OR render functions — three input shapes,
  we'd pick render functions (closest to React).
- Less popular in the Scala / FP-leaning ecosystem.
- Single-file-component support could be added later if there's demand.

### Solid — the performance default

- Signals-based reactivity (no virtual DOM).  Faster than React /
  Vue in many micro-benchmarks; less wasted work on small changes.
- JSX-like syntax, but the semantics are very different (no
  re-renders; signal subscriptions wire DOM nodes directly).
- Bundle size: ~10 KB.  Fits well with our minimal-dep ethos.
- Smaller ecosystem than React / Vue.
- The "signals as primitives" model maps well to functional state
  management.

### Custom — the no-deps default

- Tiny in-house runtime in Scala-compiled JS.  Lifecycle-less
  components, manual DOM updates via the existing `route()` model
  - a tiny "update this fragment" API.
- Bundle size: ~3 KB.
- For users who want zero external JS deps (matches ssc's current
  "no external runtime deps" pattern on the JVM side).
- Limited features — no real reactivity, no devtools, no ecosystem.

### Svelte — not recommended yet

- Compile-time framework: components compile to imperative DOM
  manipulation, no runtime.
- Smallest bundle of all (~1 KB per app).
- BUT: requires a compile-time step we'd have to integrate with
  our codegen.  Svelte's compiler is JS; we'd need to either
  spawn Node during JsGen or reimplement Svelte's compiler in
  Scala.  Both are big investments.
- Re-evaluate after React / Vue / Solid / Custom land.

## SPI shape sketch

```scala
package scalascript.frontend.spi

/** A pluggable frontend framework backend.  Implementations
 *  translate the scalajs-spa-targeted IR into framework-specific
 *  JS source code. */
trait FrontendFrameworkSpi:

  /** Short identifier — `react`, `vue`, `solid`, `custom`. */
  def name: String

  /** Maven coordinates of any JS lib the user's bundler needs to
   *  pull in.  Empty for backends that have no external runtime
   *  (custom).  Used by `ssc compile --frontend <name>` to inject
   *  the right `package.json` deps / `<script>` tags. */
  def jsDeps: List[JsDep] = Nil

  /** Emit a complete SPA from the IR.  `module` is the
   *  scalajs-spa-targeted IR (already lowered to use only the
   *  framework-agnostic primitives — `Component`, `useState`,
   *  `effect`, `route`, etc.).  Returns the framework-specific JS
   *  source ready to be served as the SPA entrypoint, plus an
   *  HTML shell that boots it. */
  def emit(module: scalascript.ir.Module): EmittedSpa

  /** What this backend supports.  Drives feature-gating in the
   *  lowering pass — e.g., user code that calls `useEffect` would
   *  not type-check if the chosen backend doesn't declare
   *  `Capability.LifecycleHooks`. */
  def capabilities: Set[Capability] = Set(
    Capability.ComponentTree,
    Capability.UseState,
    Capability.UseEffect
  )

enum Capability:
  case ComponentTree        // <-- basic composition; required
  case UseState             // <-- reactive state
  case UseEffect            // <-- side effects on mount / dep change
  case Refs                 // <-- DOM refs
  case Context              // <-- React-style context / Vue provide-inject
  case Suspense             // <-- async boundaries
  case Memo                 // <-- memoization helper
  case Portals              // <-- render into a different DOM subtree

final case class JsDep(npmName: String, version: String, importPath: String)

final case class EmittedSpa(
    js:   String,   // the SPA bundle entrypoint JS
    html: String,   // the HTML shell that loads it
    css:  String    // any global CSS to inject
)
```

User-facing intrinsics:

```scala
// In .ssc:
setFrontendFramework("react")
// or "vue" / "solid" / "custom"
```

Selection mechanism: same hybrid pattern as the HTTP/WS SPI —
`ServiceLoader[FrontendFrameworkSpi]` discovery + by-name override.
Default ssc bundles `scalascript-frontend-custom` (the tiny in-house
runtime); React / Vue / Solid are opt-in via sbt modules
`scalascript-frontend-react`, `scalascript-frontend-vue`,
`scalascript-frontend-solid`.

## Open design questions

### Q1 — Component-tree IR

The lowering pass produces a tree of `Component(name, props, children)`
nodes.  But components in different frameworks have different
parameter conventions: React uses `props: object`, Vue uses
`defineProps({...})`, Solid uses destructured arg.

Solution: emit the framework-specific calling convention in
each backend's `emit`.  Cost: each backend has to know about
the IR's parameter shape and translate.

### Q2 — Reactivity primitives

React's `useState(initial): [getter, setter]`.  Solid's
`createSignal(initial): [getter, setter]` (looks identical but
the getter is a function, not a value; reads are subscriptions).
Vue's `ref(initial): { value: T }` (mutation via `.value`).

In the .ssc API, we'd expose a unified shape:
```scala
val (count, setCount) = useState(0)
```
Each backend translates this to its native primitive.  But the
SEMANTICS differ — React re-renders the whole component on
`setCount`; Solid only re-runs the specific subscriptions; Vue
batches updates.  Users have to be aware of which they're targeting.

Compromise: document the semantic differences per backend; the
.ssc API is intentionally "useState-shaped" but users should know
they're picking semantics, not just syntax.

### Q3 — JSX-like template syntax

scalascript's `html"..."` interpolator + DSL builders (`div(...)`,
`button(onClick = ..., "label")`) already feel JSX-shaped.  Direct
mapping to React's `createElement` / Solid's JSX desugar /
Vue's `h(...)` is mechanical.

Decision: keep the existing DSL; emit framework calls.

### Q4 — SSR (server-side rendering)

Most SPAs need an SSR story for SEO + first-paint perf.  React
has react-dom/server, Vue has vue/server-renderer, Solid has
solid-js/server.  Each has different async semantics.

Out of scope for the initial SPI — first ship CSR (client-side
rendering) for all backends, then layer SSR per-backend.  The
existing scalajs-spa "render via Node" code path can serve as a
fallback SSR mechanism.

### Q5 — Routing

Each framework has its own router (React Router, Vue Router,
Solid Router).  We could:
- (a) Use the existing scalascript `route()` model and emit
  framework-specific router code per backend.
- (b) Force the user to use the framework's native router via
  imports.

(a) is more in keeping with the rest of the language; (b) avoids
re-implementation.  Recommend (a) for the first cut; allow (b) as
an escape hatch for users who need framework-specific router
features (route guards, lazy chunks, etc.).

## Trade-offs at a glance

| | Custom (default) | React | Vue | Solid |
| --- | --- | --- | --- | --- |
| External JS deps | 0 KB | ~45 KB gzip | ~30 KB gzip | ~10 KB gzip |
| Reactivity model | Manual / re-render | VDOM diff | VDOM diff | Signals |
| Component ecosystem | None | Massive | Big | Growing |
| Devtools | None | React DevTools | Vue Devtools | Solid Devtools |
| SSR maturity | N/A (CSR only) | Production-proven | Production-proven | Production-proven |
| Bundle output size | Smallest | Largest | Mid | Small |
| Perf on small change | Adequate | Mid | Mid | Best |
| User popularity | (n/a) | Highest | High | Growing |

## Sequencing

Same pattern as HTTP/WS SPI — define SPI + ship two impls together.

- **Phase Fr1** — SPI traits in new `frontend-spi` sbt module.
  `FrontendFrameworkSpi`, `Capability` enum, `EmittedSpa` /
  `JsDep` records.  ~2 days.
- **Phase Fr2** — `CustomFrameworkBackend` impl (the in-house
  tiny runtime — basically what scalajs-spa does today, extracted
  behind the SPI).  Default; bundles no external deps.  ~3 days.
- **Phase Fr3** — `ReactFrameworkBackend` impl.  React + ReactDOM
  via `npm`.  JSX-emit through the existing DSL → `createElement`
  calls.  Routing via React Router.  ~1-2 weeks (this is the
  validation step for the SPI shape — the first second-impl that
  forces design decisions).
- **Phase Fr4** — `SolidFrameworkBackend` impl.  Signals + JSX.
  ~1 week.  By this point the SPI shape should be stable.
- **Phase Fr5** — `VueFrameworkBackend` impl.  ~1 week.
- **Phase Fr6** ✓ Landed 2026-05-20 (as MILESTONES Phase A7) —
  `setFrontendFramework(name)` interpreter intrinsic +
  `ssc emit-spa --frontend <custom|react|solid|vue>` CLI flag.
  Both call `FrontendFrameworks.setBackend(name)` so downstream
  codegen routes through the chosen impl; loud failure if no impl
  with that name is on the classpath.  CLI bundles all four impl
  modules.  ~1 day.
- **Phase Fr7** — Docs + examples (3 reference apps, one per
  backend).  ~3 days.
- **Phase Fr8** — SSR per backend (later; not in initial spec).
- **Phase Fr9** — Svelte (deferred; needs compile-time integration).

Total: ~5-6 weeks for the core (Fr1-Fr7), with SSR + Svelte as
follow-ups.  Each phase is independently shippable.

## Open decisions before we cut

1. **Which framework is the "primary" we ship first after Custom?**
   React (broadest ecosystem) vs Solid (best fit for ScalaScript's
   FP-leaning users).  Strong argument for **Solid** — the signal-
   based reactivity maps cleanly to Scala's `val` / function-as-value
   ergonomics, and the smallest bundle aligns with our zero-deps
   instinct.  React's larger ecosystem is a soft argument; users who
   need React-specific component libraries can opt in.

2. **`ssc compile --frontend <name>` resolves how?**  npm-pulls
   via `package.json` injection?  scala-cli `using lib` directives
   for the underlying scala-js facades?  Bundle-tool integration
   (Vite / Webpack / esbuild)?  Each has trade-offs; recommend
   Vite as the default bundler since it has best Scala.js integration.

3. **Type-checking framework-specific code.**  React's `useState`
   has different stale-closure semantics than Solid's `createSignal`.
   Should the .ssc typer warn about cross-backend portability
   issues?  Or just document the semantics?

4. **SSR — first-class or follow-up?**  First-class means
   designing the SPI with `emit(...): { js, html, css, ssrFn }`
   from day one.  Follow-up means adding `def ssrEmit(...)` later
   without breaking the SPI.  Recommend follow-up — SSR has too
   many per-backend gotchas to bake in upfront.

5. **Single-file-component formats** (Vue's `.vue`, Svelte's
   `.svelte`).  Do we support them?  Probably not — .ssc IS our
   single-file-component format.

6. **Devtools integration.**  Each framework has its own devtools
   browser extension that needs to see the component tree.
   React's devtools work via a global hook; Vue and Solid similarly.
   The SPI should NOT force backends to disable devtools — that's
   one of the main reasons to use them.

## When NOT to build this

- When no user has asked for "I want React in my SPA".  Today
  the .ssc-targeted-to-scalajs-spa world is small enough that
  the existing "innerHTML and hope" model is adequate.
- When v2.0 separate compilation isn't yet stable enough to
  emit per-module artifacts that get glued at link time.  The
  frontend SPI's per-component-emit path benefits from the same
  incremental-compilation infrastructure.

## When to build this

- When the first real user hits the wall ("I need React's design
  system for our admin panel").
- When SPA performance becomes a measurable pain (the current
  innerHTML model wastes lots of work on small state changes).
- When devtools become important (debugging Scala-compiled JS
  without a component-tree view is painful).

## Out of scope

- React Native / mobile native bindings — different rendering target.
- Native desktop (Electron / Tauri) — different.
- Cross-framework state libraries (Redux / Pinia / etc.) — users
  pick their own.
- Build-tool integration beyond `ssc compile --frontend <name>`
  injection.  Users keep their existing Vite / Webpack / esbuild
  setup.
- Server-side framework-agnostic state hydration — that's an SSR
  follow-up.

## Relationship to other tracks

- **HTTP/WS server SPI** (`specs/http-server-spi-plan.md`): orthogonal.
  Frontend SPI is for the SPA backend; HTTP/WS SPI is for the JVM
  backend.  An ssc app can use both — e.g., Solid frontend +
  Jetty backend.
- **v2.0 separate compilation** (`specs/separate-compilation-plan.md`):
  the frontend SPI's per-component emit will lean on the same
  artifact model — each `Component(...)` block gets its own
  `.scjs` (or `.scsolid`, etc.) cache entry that re-emits only on
  source change.
- **Option D from runtime-server-strategic-plan.md** (dual-impl
  elimination): only affects the JVM side.  Not relevant for
  scalajs-spa.
