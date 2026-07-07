# ssc toolkit v2 — the .ssc UI toolkit that can replace a real React app

Status: **DRAFT (2026-07-07)** — design + slice plan. Driven by a real consumer:
busi's decision to migrate its React/TypeScript SPA to ScalaScript
(`busi:src/v2/specs/frontend-on-scalascript.md`, owner-directed 2026-07-06).
busi is the **conformance target**: toolkit v2 is done when busi's `App.tsx`
(1096 lines, 99 pieces of state, ~91 form interactions, offline-first PWA,
WebAuthn, EN/RU/UK/PL) can be expressed in `.ssc` without hand-rolled JS.

Relationship to existing specs:
- `specs/frontend-toolkit-spec.md` — the widget vocabulary + pure-`.ssc`
  library architecture (Phases 1–7 mostly landed). Toolkit v2 **builds on it**,
  it does not replace it.
- `specs/fullstack-in-process-transport.md` — phases 0–5 complete; the typed
  route client direction toolkit v2 P1 extends to the browser.
- busi `src/v2/specs/frontend-on-scalascript.md` — the requirements source.

## 1. Why v2 (what a real app hit that the demos didn't)

The current `std/ui` stack renders real SPAs (rozum's control-center,
`clients/control/control-center-live.ssc`). But the control-center is 382
lines of **module-level `val` signals with globally-unique string names**
(`signal("draft", "")`, `signal("agModel", "")`, …). That shape does not
scale to busi:

- 99 independent pieces of state (per-obligation expand, per-form drafts,
  sync status, online flag…) — as module-level globals, every one competes
  for a unique name in one namespace, and nothing ties its lifetime to the
  screen fragment that owns it.
- ~91 form interactions — each today needs hand-wired
  `signal` + `element` + `setSignal`/`fetchAction` glue.
- Offline-first: busi caches state in `localStorage`, watches
  `navigator.onLine`, and computes its to-do list **on-device** from the same
  pure domain (emit-js bundle) when the hub is unreachable. None of that has
  a toolkit primitive today; busi hand-writes `sw.js`, the manifest, and the
  localStorage glue in its server code.

## 2. Requirements (from busi, prioritized)

### P0 — blocks a faithful App.tsx replacement
1. **Component abstraction that scales**: components with *instance-scoped*
   local signal state + typed props + composition. Not one giant `View`
   function; not global-signal soup.
2. **Offline-first primitives**: `localStorage` read/write from `.ssc`;
   a reactive `onlineSignal()`; "fetch, else compute locally" as an ergonomic
   documented pattern; toolkit-generated service worker + PWA manifest.
3. **Forms**: a form model — record-backed field binding, declarative
   (JS-translatable) validation, submit → typed action with a
   loading/error/done tri-state.
4. **Production-grade browser JS**: the emitted SPA must be correct + fast +
   small on a real app. busi's suite is the conformance bar.

### P1 — needed soon after
5. **Typed API client from ssc HTTP routes** (extend the in-process-transport
   direction to the browser: `api.home()` not `fetch("/api/home")`).
6. **WebAuthn primitive** — `navigator.credentials` register/authenticate
   (the server side — `webauthnStore*` — already exists).
7. **Keyed list diffing** — reconcile a 50-item list without wholesale rebuild.
8. **Theme as ssc** — design tokens as an `.ssc` value driving the toolkit
   (busi's dark instrument-panel identity without a hand-kept `theme.css`).

### P2 — quality of life
9. Fast dev loop (watch-rebuild-reload) for the web target.
10. Loading/empty/error tri-state helper for every fetched view.
11. `rawHtml` / raw-JS escape hatch so a missing widget never blocks migration.
12. i18n parity in the generated SPA (four locales, live switch — parity with
    the server pages' `std/ui/i18n`).

## 3. Current state (what exists; surveyed 2026-07-07)

**Naming note:** this "toolkit v2" is unrelated to (a) the `v2/` language
track (the Mira kernel redesign — no UI there; `v2/frontend-bridge` is a
*compiler-frontend* bridge) and (b) `frontend-toolkit-spec.md`'s "v2 widget
pack" (a widget-version label). All UI work lands on the v1 runtime.

- **std/ui externs** are implemented by `v1/runtime/std/frontend-plugin/`
  (`FrontendIntrinsics.scala`: signal/element/serve/emit/…) and
  `std/fetch-plugin/` (`FetchIntrinsics.scala`: fetch*/row*/dataTable*),
  producing `frontend/core` objects (`View`, `ReactiveSignal`,
  `EventHandler.FetchAction`, …).
- **`serve(tree, port)` is not SSR**: it emits an SPA bundle
  (`index.html` + `app.js`) via the current `FrontendFrameworkSpi` backend
  and serves it statically. Default backend = `custom`, whose
  `StaticJsEmitter` *is* the client-side signal runtime
  (subscriber-set `__setSignal`, reactive attrs).
- **Two divergent .ssc→browser lowerings exist**:
  1. **`JsGen`** (`v1/runtime/backend/js/`, ~5k LOC + 42 runtime files:
     IndexedDB facade, WebAuthn *server-verifier* port, HTTP client) —
     the static compiler behind `ssc emit-js` / `ssc emit-spa`;
     capability-tree-shaken, self-contained output. The production-leaning
     path; busi's offline domain bundle already uses it (20/21 busi v2
     domain files run standalone under node after the 2026-06-22 sweep).
  2. **Interpreter `serve` → SPI emitters** (custom/react/solid/vue) —
     the demo path; react/vue/solid shells load framework deps from a CDN
     ("a real build pipeline would replace this" — their own comments).
- **Components**: genuinely missing at the .ssc level. `signal(name, default)`
  registers a *global* string id (duplicate registration is an error);
  `TkNode` has no component/props node; even the Scala-side `Component[P]` /
  `ComponentDef` / `PropDef` (frontend/core) is inlined/unfinished.
- **Two mirrored widget layers**: the Scala `frontend/toolkit`
  (`ToolkitNode`, with real `Form`/`FormField[T]`/`Validators`/Router/Table
  + tests) and the .ssc `std/ui` (`TkNode` + `lower.ssc`). Both lower to
  `frontend/core.View`; .ssc **cannot** reach the Scala toolkit — its
  validators/forms are unexposed.
- **Offline/PWA**: `std/pwa.ssc` **already exists** — server-registered
  `GET /manifest.json` + `GET /sw.js` with a `precache` list (busi's
  hand-written `http/pwa.ssc` predates/duplicates it). An IndexedDB browser
  facade exists on the JsGen path (`indexeddb.mjs`, localStorage fallback).
  Missing: a first-class localStorage API, `navigator.onLine` reactivity,
  and offline sync/queue helpers.
- **WebAuthn**: full server verifier (JVM + a JS port of the *server* side).
  The browser `navigator.credentials.create/get` side does not exist.
- **Keyed list diffing**: not implemented — `View.ForSignal` wipes and
  rebuilds the container on every change (custom + solid emitters alike).
- **Forms at .ssc level**: a boolean `required` HTML attr; nothing else.
- Full-stack in-process transport: phases 0–5 complete (JVM); typed route
  clients exist on the JVM path only.

## 4. Design

### 4.1 Components with instance-scoped signals (P0-1)

The single deepest gap. Today `signal(name, default)` registers a global
name; a `def` that creates signals is a footgun (every call site shares
them). Proposal:

**Landed 2026-07-07 (slice tkv2-components)** as `std/ui/component.ssc` —
pure `.ssc`, free functions (no extern needed):

```scalascript
// std/ui/component.ssc (as landed)
case class Ctx(prefix: String)
def component[N](kind: String, key: String)(body: Ctx => N): N
def childCtx(parent: Ctx, kind: String, key: String): Ctx
def ctxSignal[T](ctx: Ctx, name: String, default: T): Signal[T]
def ctxSeedSignal(ctx: Ctx, name: String, source: Signal[String]): Signal[String]
```

- Signals register as `"<kind>__<key>__<name>"`, **sanitized** — the emitter
  contract requires signal ids to be JS identifiers (`[A-Za-z_][A-Za-z0-9_]*`;
  the React emitter derives `useState` variable names from them), so `/`
  separators are rejected at emit time and non-identifier chars map to `_`.
- **Typed props** = ordinary function parameters (already typed by the ssc
  typer): `def todoCard(o: Obligation): TkNode = component("todoCard", o.id)(
  ctx => ...)`.
- **Lifecycle**: DEFERRED — the view tree is built once today, so instance
  signals live for the app's lifetime; disposal lands with P1-7 keyed diffing
  (`tkv2-keyed-for`), where instances actually unmount.
- Portability gotchas recorded for later slices: per-char comparisons and
  regex `replaceAll` diverge between the INT and JS lanes — `ctxClean`
  sanitizes via substring+contains.

### 4.2 Offline/PWA primitives (P0-2)

```scalascript
// std/ui/offline.ssc
extern def localStorageGet(key: String): Option[String]
extern def localStorageSet(key: String, value: String): Unit
extern def onlineSignal(): Signal[Boolean]          // navigator.onLine + events
def persistedSignal[T](name: String, default: T): Signal[T]  // signal ⇄ localStorage

// "fetch, else compute locally": the busi pattern, as one helper
def fetchOrLocal[T](url: String, tick: Signal[Int], local: () => T): Signal[T]
```

JVM lowering: `localStorage*` over a per-process map (testable);
`onlineSignal` constant `true`. Browser side lands in the JsGen runtime
(where the IndexedDB facade already lives) and the custom-backend runtime.
**PWA**: `std/pwa.ssc` already provides manifest + service worker + precache;
the slice here is *adoption + extension* (offline fallback page, cache
versioning, theme fields busi needs) — not a new primitive. busi's
hand-written `http/pwa.ssc` is the consumer that validates it.

### 4.3 Form model (P0-3)

Builds on the existing `input.ssc` widgets + the validator-DSL decision in
`frontend-toolkit-spec.md` (risk #1: validators must be *data*, not closures,
to be JS-translatable):

```scalascript
// std/ui/form.ssc
case class FieldSpec(name: String, required: Boolean = false,
                     minLength: Int = 0, pattern: String = "", ...)
def form(kind: String, key: String, fields: List[FieldSpec],
         submitUrl: String)(render: FormCtx => View): View
// FormCtx: field(name) → Signal[String] (draft), error(name) → Signal[String],
// submitState → Signal[String] ("idle"|"busy"|"done"|"error"), submit → EventHandler
```

Submit lowers to the existing `fetchAction`/`formBody` machinery; validation
predicates evaluate client-side from the `FieldSpec` data (and identically
on the JVM for SSR/tests — one pure `validate(spec, value)` in `.ssc`).

### 4.4 Production JS codegen: one path, busi as the conformance bar (P0-4)

**Decision: the production SPA path is `emit-spa`/`JsGen` with the
framework-free (`custom`-style) runtime.** Rationale: self-contained output
(no CDN deps), capability tree-shaking, and it is the path busi's offline
domain bundle already runs on. The SPI framework emitters (react/vue/solid)
stay as demos; the interpreter `serve` path keeps working for dev.
Consequences:

- New toolkit-v2 runtime work (component scopes, keyed reconciliation,
  offline primitives) lands in the JsGen/custom runtime first; other
  emitters follow only on demand.
- `emit-spa` output must be fully self-contained (audit for any remaining
  CDN/network fetches in the shell).
- Add a `tests/conformance/` family exercising the toolkit-v2 primitives
  INT==JS — every new extern ships with one.
- Add a **busi-home conformance case**: a reduced to-do screen (list +
  expand + form + offline fallback) as a standing corpus case, so toolkit
  regressions surface here, not in busi's live app.
- Close remaining emit-js preamble capability gaps as they surface through
  the busi cases (coordinate with the clock/env plugin stream; don't fork it).

### 4.5 P1 sketches (own slices, specced when picked up)

- **Typed browser client**: generate a `.ssc` client module from `routes`
  declarations; browser transport = `fetch`, JVM transport = in-process (the
  existing Phase 3–4 machinery). One app codebase, two transports.
- **WebAuthn**: `extern def webauthnRegister(...)`/`webauthnAssert(...)`
  wrapping `navigator.credentials` with the same option shapes the server
  `webauthnStore*` already speaks.
- **Keyed diffing**: `forKeyed(items, key)(render)` — browser runtime
  reconciles by key; instance-scoped component signals (4.1) survive moves.
- **Theme-as-ssc**: `theme.ssc` already models tokens; add a
  `cssVariables(theme)` emitter so an app (busi) can feed one theme object to
  both the toolkit and any legacy CSS through `var(--…)`.

## 5. Slice plan (SPRINT queue)

Ordered so busi's migration pilot (SPA shell + home screen) unblocks earliest:

1. **tkv2-components** — `component`/`Ctx` instance-scoped signals + disposal
   + conformance cases (INT==JS). The pilot's prerequisite.
2. **tkv2-offline** — `localStorageGet/Set`, `onlineSignal`,
   `persistedSignal`, `fetchOrLocal` + conformance.
3. **tkv2-forms** — `FieldSpec`/`form`/`FormCtx` + validation data-DSL +
   tri-state submit + conformance (port the Scala toolkit's `Validators`
   semantics; don't invent new rules).
4. **tkv2-spa-pipeline** — `emit-spa` self-contained audit (no CDN in the
   shell) + document the JsGen/custom runtime as THE production path;
   toolkit-v2 externs verified on it.
5. **tkv2-pwa-adopt** — extend `std/pwa.ssc` with what busi needs (offline
   fallback page, cache versioning, theme fields); busi's `http/pwa.ssc`
   replaced by it as the consumer proof.
6. **tkv2-busi-home-conformance** — the reduced busi home screen as a
   standing corpus case (uses 1–4).
7. **tkv2-keyed-for** (P1-7) — `forKeyed(items, key)(render)`: keyed
   reconciliation in the JsGen/custom runtime (today `ForSignal` wipes and
   rebuilds); component-signal lifecycle on move.
8. **tkv2-webauthn** (P1-6) — browser `navigator.credentials.create/get`
   externs (the server verifier already exists on both JVM and JS).
9. **tkv2-typed-client** (P1-5) — route-derived `.ssc` client, browser
   transport.
10. **tkv2-theme-css-vars** (P1-8) — `cssVariables(theme)`.

P2 items (dev loop, tri-state helper, rawHtml, SPA i18n parity check) are
queued to BACKLOG; rawHtml may already be covered by `element`/`rawText` —
verify before building.

## 6. Non-goals

- Not a React clone; no virtual-DOM rewrite of the browser runtime beyond
  keyed reconciliation.
- No SSR-of-SPA hybrid rendering work (busi keeps its no-JS server pages
  separately).
- No breaking changes to existing `std/ui` consumers (rozum control-center,
  busi server pages, content toolkit) — v2 is additive; existing signals API
  stays.

## 7. Verification

- Every slice: conformance cases green INT==JS (+ JVM where applicable),
  `scala-cli tests/conformance/run.sc -- --only 'tkv2-*'` before push
  (AGENTS.md 4b).
- Slice 5 is the integration bar: the reduced busi home screen runs
  identically interpreted and as an emitted browser bundle.
- The real acceptance test lives in busi: the migration pilot (SPA shell +
  home/to-do over toolkit v2) browser-verified in all four locales + offline.
