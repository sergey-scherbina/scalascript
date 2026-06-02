# Frontend abstract model

Design exploration for a framework-agnostic UI programming model in
ScalaScript.  Goal: a model that captures the **essence** of modern
UI frameworks — independent of React / Vue / Solid / Svelte / etc. —
that any of them can later implement.

The companion `frontend-framework-spi-plan.md` describes the
mechanics of the SPI (modules, ServiceLoader, codegen flags).
This doc is one level deeper: what the SPI IS abstractly.  If we
get this right, the SPI becomes a thin translation layer; if we
get it wrong, the SPI leaks framework-specific concepts everywhere.

Audience: future-me when we start implementing Fr2 / Fr3 of the
frontend SPI plan and need a programming-model contract to translate.

## The question

What IS a frontend application, in the abstract?

Looking across React, Vue, Solid, Svelte, Elm, Cycle.js, Hyperapp,
Preact, Mithril, Inferno — every modern UI framework converges on
the same set of primitives, just with different names and
different reactivity-tracking implementations:

| Concept | React | Vue | Solid | Svelte | Elm |
| --- | --- | --- | --- | --- | --- |
| Reactive value | `useState` | `ref` | `createSignal` | `let` | `Model` |
| Derived value | `useMemo` | `computed` | `createMemo` | `$:` | derived |
| Side effect | `useEffect` | `watchEffect` | `createEffect` | `$: side` | `Cmd` |
| DOM tree | JSX | template/JSX | JSX | template | `view` |
| Component | function | setup/options | function | file | function |
| Event handler | `onClick` | `@click` | `onClick` | `on:click` | `Msg` |
| Composition | nested JSX | nested templates | nested JSX | nested | nested view |

The names differ, the **abstractions are the same**.  The
DIFFERENCES are entirely in HOW each implements update propagation:

| Framework | Update strategy |
| --- | --- |
| React | Re-run component fn on state change → diff VDOM → patch real DOM |
| Vue | Re-run reactive deps → diff VDOM → patch |
| Solid | Subscribe DOM nodes to signals → on signal change, patch only subscribers |
| Svelte | Compile-time: code analyses what state affects what DOM → generate imperative patches |
| Elm | Re-run `view : Model -> Html msg` → diff VDOM → patch (managed runtime) |

**The propagation strategy is an implementation detail.**  At the
user-code level, everyone writes "this state + this view function",
and the framework figures out the rest.

## The abstract model

A frontend application is:

```text
              ┌───── reactive cells ─────┐
              │                          │
   inputs ───>┤   State (S)              │
              │                          │
              └──────────┬───────────────┘
                         │
                         │  view : S -> Tree
                         ▼
                  ┌────────────┐
                  │ View tree  │  ── declarative DOM description
                  └─────┬──────┘
                        │
                        │  (renderer materialises into real DOM,
                        │   re-runs view on state change OR
                        │   wires individual nodes to specific
                        │   state cells — IMPLEMENTATION DETAIL)
                        ▼
                  ┌────────────┐
                  │  Real DOM  │
                  └─────┬──────┘
                        │
                        │  user events (clicks, input, …)
                        │
                        ▼
              ┌─────────────────────────┐
              │ Event handlers          │
              │  Event -> S -> S        │  ── update state
              │  (or async S => Future) │
              └──────────┬──────────────┘
                         │
                         ▼
                       (loop)
```

Effects are state-triggered side actions:

```text
   S changes ───> effect : (S, S_prev) -> Effect[Unit]
                          ─── runs after view materialised
                          ─── may have cleanup
                          ─── examples: log, fetch, timer, focus
```

That's the **whole** model.  Five primitives:

1. **`Signal[T]`** — a reactive cell with read + write.
2. **`Computed[T]`** — a value derived from one or more signals.
3. **`Effect`** — a side action triggered when its deps change.
4. **`View`** — a tree of nodes (HTML elements or other components).
5. **`Component[P]`** — a function from props `P` to a `View` that
   may close over signals + effects.

Everything else is sugar.

## The five primitives, formally

### `Signal[T]`

```scala
trait Signal[T]:
  /** Read the current value AND, if called inside a reactive
   *  context (a component render or a computed/effect body),
   *  subscribe the context to changes of this signal. */
  def apply(): T
  /** Replace the current value, notifying subscribers. */
  def set(value: T): Unit
  /** Apply a function; same effect as `set(f(apply()))` but
   *  atomic in implementations that buffer batches. */
  def update(f: T => T): Unit
```

User syntax (sugar on top):

```scala
val count = signal(0)
count()                    // read = subscribe
count := count() + 1       // shorthand for count.update(_ + 1)
```

Implementation by framework:

- **React**: `signal[T](init)` returns a thin wrapper around
  `useState[T]`.  `count()` reads the React state; `count := X`
  calls the React setter.  Subscription IS the component re-render
  (no fine-grained subscriptions).
- **Vue**: `ref[T](init)`.  `count()` accesses `.value`.
  `count := X` writes `.value`.  Subscription via Vue's proxy
  dependency-tracking.
- **Solid**: `createSignal[T](init)` returns `(getter, setter)`.
  `count()` IS the getter (which is a function that subscribes).
  `count := X` calls the setter.  Subscription is fine-grained
  per-DOM-node.
- **Custom**: a simple cell + Set of subscribers.  On `set`,
  notify subscribers.

### `Computed[T]`

```scala
trait Computed[T]:
  def apply(): T  // read = subscribe; value is cached + memoized
```

User syntax:

```scala
val fullName = computed { firstName() + " " + lastName() }
fullName()  // re-derived only when firstName or lastName changes
```

Implementation by framework:

- React: `useMemo(() => firstName() + " " + lastName(), [firstName, lastName])`.
- Vue: `computed(() => ...)`.
- Solid: `createMemo(() => ...)`.
- Custom: lazy cache + recompute on deps invalidation.

### `Effect`

```scala
trait Effect:
  /** Cancel this effect (run its cleanup, unsubscribe). */
  def stop(): Unit

def effect(action: () => Unit): Effect
def effect(action: () => (() => Unit)): Effect  // with cleanup
```

User syntax:

```scala
val tick = signal(0)

effect {
  println(s"tick is now ${tick()}")
}

effect {
  val timer = setInterval(() => tick := tick() + 1, 1000)
  () => clearInterval(timer)  // cleanup
}
```

Implementation by framework:

- React: `useEffect(() => action(), [deps])`.  Tricky because
  React needs explicit deps (the abstract model uses implicit
  tracking via signal reads).  Compiler can lower implicit reads
  into explicit deps array.
- Vue: `watchEffect`.
- Solid: `createEffect`.
- Custom: re-run when subscribed signals change.

### `View`

```scala
sealed trait View
case class Element(tag: String, attrs: Map[String, Any], children: Seq[View]) extends View
case class TextNode(value: () => String) extends View            // dynamic; subscribes via thunk
case class Fragment(children: Seq[View]) extends View
case class ComponentInstance[P](component: Component[P], props: P) extends View
```

User syntax (HTML-like DSL):

```scala
div(
  attr.cls := "counter",
  button(onClick := (() => count := count() + 1), "+"),
  span(s"Count: ${count()}")
)
```

Note: `span(s"Count: ${count()}")` is special.  The interpolation
captures `count` by reference, so each render reads it fresh.
Solid would wrap the text in a `() => s"Count: ${count()}"` thunk
that re-runs on signal change.  React would re-render the
component (re-creating the whole VDOM subtree).  Same source,
different update granularity.

### `Component[P]`

```scala
trait Component[P]:
  def render(props: P): View
```

Component bodies are PURE w.r.t. signal creation: a single
`Component.render(props)` execution creates the same signals each
time.  Frameworks differ in whether they call `render` once
(Solid — wires once, signals drive updates) or repeatedly
(React — re-runs on every state change).

User syntax:

```scala
@component
def Counter(initial: Int = 0): View =
  val count = signal(initial)
  div(
    button(onClick := (() => count := count() + 1), "+"),
    span(s"Count: ${count()}")
  )
```

The `@component` annotation tells the codegen this is a Component;
the rest is pure ScalaScript.

## The mapping: how each framework realises the model

### Custom (default, no external deps)

Direct interpretation.  At runtime:
- Signals are objects with `value: T` + `subscribers: Set[() => Unit]`.
- `apply()` reads `value`, if called inside a render/effect/computed
  context, adds the current context to `subscribers`.
- `set()` notifies subscribers, which re-run themselves.
- Components are functions; rendered once; signal subscriptions wire
  text nodes / attribute values / conditional sub-trees to specific
  signals.
- DOM updates happen via direct `node.textContent = ...` /
  `node.setAttribute(...)` calls — no VDOM.

This is essentially "Solid in a few hundred lines of Scala-compiled
JS".  Smallest bundle (~3-5 KB).  No external dep.

### React

Translation strategy:
- `signal[T](init)` lowers to `useState[T](init)`; the wrapper
  caches the React state ref so `signal()` reads the latest value.
- Component `render(props)` becomes a React function component;
  it re-runs on any state change (React semantics).
- `computed` lowers to `useMemo`.
- `effect` lowers to `useEffect` — the compiler analyses signal
  reads inside the effect body to populate the deps array.
- View → JSX via `React.createElement(...)`.
- Composition: nested components become nested JSX.

What's lost: fine-grained reactivity.  Every signal change re-runs
the WHOLE component's render.  React's reconciler keeps it
performant for most cases but pathological re-renders are possible.

What's gained: React's ecosystem.  Component libraries (MUI,
Chakra, etc.) work; React DevTools works; SSR via react-dom/server.

### Solid

Translation strategy:
- `signal[T](init)` lowers to `createSignal[T](init)` returning a
  signal pair.  `count()` IS the getter (already function-shaped).
- Component render runs ONCE; signal subscriptions wire DOM
  fragments directly.
- `computed` lowers to `createMemo`.
- `effect` lowers to `createEffect`.
- View → Solid JSX.  Identical to React's `createElement` shape
  but Solid's JSX-transform wires fine-grained subs.

What's lost: a small bit of React's mental model (re-render).
Users coming from React might be surprised that closure captures
behave differently.

What's gained: best perf for granular state changes (only the
affected DOM nodes update, not the whole component).  Smallest
bundle of the "real frameworks" tier.

### Vue

Similar to React: `signal` lowers to `ref`, components are setup
functions, view → render functions.  VDOM-based update.

What's worth noting: Vue's proxy-based dep tracking is the closest
to the abstract model's implicit subscription.  Less surprising
than React, less aggressive than Solid.

### Svelte (deferred)

Compile-time framework: Svelte's compiler converts component
source into imperative DOM updates.  Integrating this requires
either:
- Spawning Svelte's compiler from JsGen (Node dep).
- Reimplementing Svelte's analysis in Scala (large effort).

Neither is in scope for v1.

## Where the abstraction breaks down

Honest list of edge cases the model doesn't (currently) handle:

### 1. Refs (DOM access)

Some libraries need a raw DOM node — e.g., focusing an input,
measuring text, attaching a third-party Canvas/WebGL widget.

Framework primitives differ: React `useRef`, Vue `ref` (overloaded!),
Solid `let el: HTMLElement` then `<input ref={el}/>`.

**Abstract:** `domRef[T <: Element](): DomRef[T]` returning a
proxy that becomes valid after mount.

### 2. Two-way binding (forms)

React forces controlled-input pattern (value + onChange).  Vue has
`v-model`.  Solid has a similar pattern via signals.  Svelte uses
`bind:value`.

**Abstract:** `input(value := signal)` where `value :=` desugars
to `value -> signal()` + `onInput -> (e => signal := e.target.value)`.
Compiler synthesises the two-way binding.

### 3. Context / dependency injection

React `Context`, Vue `provide/inject`, Solid `createContext`.
Useful for "deeply nested component needs to read a value
provided several layers up".

**Abstract:** `context[T](default: T)` returns a `Context[T]` that
can be `provided` in an ancestor and `read` in any descendant.
Maps to each framework's primitive.

### 4. Suspense / async boundaries

React 18+ `Suspense`, Solid `<Suspense>`, Vue `<Suspense>`.
Lets components throw promises and render fallback.

**Abstract:** `suspense(fallback: View) { … }` — the body may
contain async signals (`resource[T](fetchFn)` from React/Vue/Solid).

### 5. Portals

Render a subtree into a different DOM root (modals, tooltips).
React `createPortal`, Vue `<Teleport>`, Solid `<Portal>`.

**Abstract:** `portal(target: DomRef[Element]) { … }`.

### 6. Server-side rendering

Not in the abstract model's CORE, but each framework offers
SSR.  An SSR-aware backend interprets View into an HTML string
(not a real DOM).  Effects don't run on the server.

**Abstract:** orthogonal — SSR is a different "interpreter" of
the same View tree.  Add a `Renderer` typeclass with two impls:
`DomRenderer` (browser) and `HtmlRenderer` (server).

### 7. Animations / transitions

React, Vue, Solid each have their own animation primitives.
Hard to abstract.  Solution: don't.  Users who need animations
opt into framework-native APIs via escape-hatch imports.

### 8. Framework-specific perf tricks

React's `memo` / `useCallback`, Vue's `shallowRef`, Solid's
`untrack`.  These are perf escape valves.  Abstract them as
`untrack(thunk)` (Solid's name fits best) — every framework can
provide a no-op fallback.

## Semantic gotchas

Honest list of where the abstraction LIES — places the model
pretends frameworks are uniform when they aren't:

### Closures over state

React's component re-runs on every state change.  Inside a
handler captured in JSX, the closure sees the state at the time
of render, not "now".  Solid's component runs ONCE; closures see
the live signal.

The abstract model treats `count()` as "read current value".  In
React, this means we have to either:
- Re-render on every signal change (giving up some perf).
- Use refs / `useEvent` pattern to make closures see live state.

The compiler probably picks the simplest (re-render) and emits
React `useCallback` for handlers that need stable identity.

### Batched updates

React batches multiple `setState` calls within an event handler.
Vue/Solid batch within microtasks.  Custom would too if implemented
right.

The abstract model's `signal.set(v)` is atomic from user code's
view.  Backends do the right batching.

### Effects and order

React's `useEffect` runs after paint.  Solid's `createEffect`
runs synchronously after the signal write.  Vue's `watchEffect`
defaults to post-render but is configurable.

The abstract model says "effect runs when deps change".  When
EXACTLY?  Probably the safest contract: "after the next render"
(matches React + Vue default + Custom).  Solid users have a
sub-effect (`createRenderEffect`) for the synchronous variant.

## Programming model surface in ScalaScript

What user code in `.ssc` looks like (target syntax):

```scala
@component
def App(): View =
  val count    = signal(0)
  val doubled  = computed { count() * 2 }
  effect {
    println(s"count is now ${count()}, doubled is ${doubled()}")
  }
  div(
    attr.cls := "app",
    h1("Counter"),
    p(s"Count: ${count()}, Doubled: ${doubled()}"),
    button(onClick := (() => count := count() + 1), "+"),
    button(onClick := (() => count := 0), "Reset")
  )

mount(App(), into = "#app")
```

That's enough to write a real app — and it compiles to **React,
Vue, Solid, or Custom** without changing a line.

The choice happens once:

```scala
setFrontendFramework("solid")   // or "react" / "vue" / "custom"
```

Or via CLI:

```bash
ssc compile --frontend solid app.ssc
```

## Why this is worth doing

The skeptical view: "why not just pick one framework and standardise on it?"

Three answers:

1. **Ecosystem leverage.**  React's component libraries (design
   systems, charts, forms) are massive.  Solid's signals-based
   patterns work for performance-critical paths.  Vue's templates
   make designers comfortable.  No single framework covers all
   use cases.  A model that targets multiple lets a team pick
   per-project (or even per-component, via escape hatches).

2. **Framework churn.**  React was the dominant choice in 2018.
   Solid is gaining in 2024.  The next big thing in 2028 is
   probably something we haven't heard of yet.  If our user code
   targets an abstract model, we can add new framework backends
   as they emerge without users rewriting.

3. **Cross-paradigm experiments.**  An abstract model lets us
   try things real frameworks can't: pure-functional state via
   algebraic effects, time-travel debugging via state snapshots,
   automatic differentiation of view functions for incremental
   re-rendering, etc.  Research opportunities a single-framework
   binding wouldn't unlock.

The cost: every backend has to implement the full primitive set
honestly.  Some won't be perfect approximations (React can't do
truly fine-grained subs without escape hatches).  We document the
gaps in each backend's "caveats" section.

## Recommended primitive set (concrete)

After surveying React / Vue / Solid / Svelte / Elm / Cycle, this
is the minimal primitive set that covers ~90% of UI work:

| Primitive | Purpose | All frameworks have it? |
| --- | --- | --- |
| `Signal[T]` | Reactive value | ✓ |
| `Computed[T]` | Derived value | ✓ |
| `Effect` | Side effect on dep change | ✓ |
| `Component[P]` | Composable UI unit | ✓ |
| `View` | DOM tree description | ✓ |
| `event handlers` | Event → state change | ✓ |
| `domRef[T]` | Raw DOM access | ✓ (all have escape hatch) |
| `context[T]` | Cross-tree dependency injection | ✓ |
| `suspense / async` | Async boundaries | React ≥18, Vue, Solid; weak in Custom |
| `portal` | Render into different DOM root | ✓ (Custom needs minor impl) |
| `untrack` | Read a signal without subscribing | ✓ in Vue / Solid / Custom; emulated in React |

That's eleven primitives.  Eleven is more than e.g. Elm's three
(`Model`, `Msg`, `view`) but matches what real apps need.

## Sequencing for the abstract model

This is design-only today, but if we go to implementation:

- **Phase A1** — primitive definitions in a new `frontend-core`
  module.  Pure Scala 3 traits + helpers.  No external deps, no
  backend assumed.  ~3 days.
- **Phase A2** — Custom backend interprets the primitives directly.
  Effectively building a minimal signal-based runtime in
  Scala-compiled JS.  ~2 weeks.
- **Phase A3** — React backend.  Lowers primitives to React hooks.
  ~2 weeks; this is where the SPI shape gets validated against a
  real impl (same lesson as HTTP/WS SPI's S2).
- **Phase A4** — Solid backend.  ~1 week (closest fit to the
  abstract model; mostly syntactic translation).
- **Phase A5** — Vue backend.  ~1 week.
- **Phase A6** — Smaller: refs / context / suspense / portal
  rounded out per backend.  ~1 week.
- **Phase A7** — Codegen + CLI integration.  ~3 days.
- **Phase A8** — Docs + examples.  ~3 days.

Total: ~7-8 weeks for the full core (Custom + 3 frameworks +
primitives), with SSR + animations + Svelte deferred.

## When to use which strategy

Mental model for "which framework should I pick":

| Need | Best backend |
| --- | --- |
| Zero JS deps, smallest bundle | Custom |
| Existing React design system / component library | React |
| Maximum perf on granular state changes | Solid |
| Familiar template syntax for designers | Vue |
| Server-side rendering as primary use case | React (most mature SSR) |
| Embedded in larger React app | React |
| Build-step minimisation (no JSX, no bundler) | Custom |

## Open research questions

These aren't blockers for v1, but worth keeping in mind:

1. **Can the model be FULLY pure-functional?**  Elm-style: state
   is a single immutable model, `update : Msg -> Model -> Model`,
   view is `view : Model -> Html msg`.  No signals, no effects-as-
   subscriptions, just a pure reducer.  Pros: easiest to reason
   about, time-travel debugging free.  Cons: every state change
   re-runs the entire view; harder to optimise without compiler
   smartness.  Worth a side-explore.

2. **Can we expose algebraic effects?**  Components become effect-
   ful computations; `signal`, `effect`, `dom_op` are operations
   in a `Frontend` effect.  Each backend provides handlers.  This
   is the most theoretically clean.  Cons: ScalaScript's existing
   algebraic-effects work (v1.5 Phase 7) was scoped to backend-side
   server code; extending to frontend is its own design exercise.

3. **Can we use compile-time analysis to AUTO-tune?**  Inspect
   the view function for which signals are used where; for
   stable backends like React, automatically insert `memo` /
   `useCallback`.  For Solid, automatically insert
   `untrack` where pure reads would cause sub-storms.  The
   compiler knows more than the runtime; can it bridge?

4. **Reactivity at the component-tree level — or finer?**  React
   re-renders whole components.  Solid re-renders nothing
   (just patches DOM nodes).  Where in between is "the right
   level"?  Some research suggests "component-level except for
   text + attribute interpolations, which are signal-level" is
   the sweet spot.  Worth experimentation.

5. **Should the model dictate routing?**  The current scalajs-spa
   uses route()-based pages.  React Router etc. have their own
   models.  We could either:
   - Keep route() as the canonical model; each backend adapts.
   - Let each backend use its own router.
   First is more in keeping with the abstract-model goal; second
   is friendlier to existing framework ecosystem.

## Implementation maintenance notes

The current backend emitters have converged on the same operational pattern:
walk the `View[?]` tree, collect signals/refs/fetches, then lower nodes. Typed
models make that duplication more visible because `ModelView`, `ForModel`, and
`ModelText` must be traversed the same way in every backend.

The next behavior-preserving cleanup should add a tiny traversal layer in
`frontend/core`: one walker over `View[?]` plus callback hooks for signal
registration, fetch registration, refs, and typed model scopes. Backends can
adopt it incrementally. The goal is not a new rendering abstraction; it is to
make future `View` cases harder to miss in collector code.

## When NOT to build this

- When no user has asked.  The abstract model is the kind of
  thing that's WORTH IT only when the cost of adding a new
  framework binding is paid back by users who want it.  If
  only one framework matters, just bind to that one directly.

- When v2.0 separate compilation isn't yet mature.  The
  per-backend codegen leans on the artifact-cache machinery
  v2.0 builds.

## When to build this

- When the team's choice of frontend framework changes (we add a
  third or fourth backend).  Then the abstract model pays off.

- When research projects in the FRP / signals / algebraic-effects
  space need a substrate.  ScalaScript's strong typing + compile-
  time analysis make it a good experimental platform.

- When the existing scalajs-spa runtime's perf or correctness
  becomes a real pain.  An abstract-model rewrite is a natural
  point to fix both.

## Out of scope (vs `frontend-framework-spi-plan.md`)

- The SPI mechanics (modules, ServiceLoader, codegen flags)
  belong in `frontend-framework-spi-plan.md`.
- The framework-by-framework trade-offs (bundle size, dev tools,
  ecosystem) also belong there.
- THIS doc is about the *primitives* — the contract that the
  SPI's `FrontendFrameworkSpi.emit(...)` must respect.
