# Milestones

Tracks work that is **not yet done**. As things land, move them out of here
(into git history) rather than ticking checkboxes — the file should always
read forward.

## v0.6 — Auth / sessions / cookies

Building blocks for authenticated `.ssc` web apps.  Each step is its own
commit on `feature/auth-sessions`; they're ordered foundation-first so
later items can lean on the earlier ones.

1. **Signed cookie sessions.**  HMAC-signed cookie carrying a JSON
   payload; `req.session("k")` reads, `Response.html(...).withSession(m)`
   writes.  Stateless on the server, works on INT/JVM/Node runtimes.
   Bedrock for everything that follows.
2. **CSRF token middleware.**  Auto-generated per-session token,
   double-submit cookie strategy.  `Request` exposes `csrfToken`;
   POST/PUT/DELETE handlers refuse mismatched tokens.  Pairs with
   cookie sessions but useful independently.
3. **Login / logout example** (`examples/auth-demo.ssc`).  Tiny site
   with a login form, password check (against a hard-coded `Map[String,
   String]`), session-gated `/profile`, logout button.  Drives the
   `req.session` / `withSession` / CSRF APIs end-to-end and gives
   `e2e/auth-smoke.sc` something to exercise.
4. **JWT bearer-token auth.**  `Authorization: Bearer <jws>` with HS256
   signing.  `req.auth: Option[Claims]`, `Response.json(...).withJwt(...)`.
   Stateless API alternative to cookie sessions — natural fit for the
   browser-SPA target where the same `.ssc` can serve API + UI.
5. **Server-side session store.**  Opt-in `SessionStore` abstraction
   (in-memory by default) so callers who need instant revocation or
   payloads >4KB can swap cookie-resident state for an SSID lookup.
   Plays nicely with the upcoming `Storage` effect.
6. **HTTP Basic auth helper.**  `req.basicAuth: Option[(String, String)]`
   + a `requireBasicAuth(realm)` route guard.  Low priority — useful
   for dev / internal endpoints, not for product-facing flows.
7. **OAuth2 / OIDC** (Google + GitHub).  Authorization-code flow with
   provider-specific config; `oauthClient(provider, ...).authorizeUrl()`,
   callback handler, token exchange.  Last in the order because it
   needs an HTTP client and per-provider quirks.

## v0.5 — Interpreter performance (Tier 1) — landed

Closed in a series of small commits on the
`worktree-interp-perf-slots` branch.  The interpreter is now 3-8×
faster on the bench workloads (target was ~5×, hit it).

  | workload | before | after | speed-up |
  | -------- | -----: | ----: | -------: |
  | fib(28)  | 2734   |   330 |  **8.2×** |
  | sum(1e6) | 2886   |   610 |  **4.8×** |
  | list-ops | 580    |   175 |  **3.3×** |

What landed:

- **TCO-analysis cache on FunV.**  `tailCallTargets` / `callsInTailPos`
  / `hasNonTailSelfCall` were running on every invocation; now cached
  in an IdentityHashMap and reused.
- **Env trim.**  `globals.toMap` no longer splatted into env per call;
  `eval`'s `Term.Name` falls back through `globals` directly, and the
  defaults-pass base env is built lazily.
- **Pure-value shortcuts in eval.**  When `Term.ApplyInfix` /
  `Term.Apply` / `Term.If`'s sub-Computations are already `Pure`, call
  `infix` / `dispatch` / `callValue` directly and skip the FlatMap.
- **Trampoline stable-env hoist.**  The TCO loop rebuilds only the
  per-iteration param binding; `closure + selfTco + mutualEntries`
  is computed once per `curFun` and reused.
- **Param specialisation (`.updated` chains, 1- / 2-param frames).**
  Non-TCO calls use `closureWithSelf.updated(p, v)` for 1-arg
  functions and a chain for 2-arg, skipping the generic
  `zip + toMap` builder for the common arities.
- **Lit interning by AST identity.**  Per-Lit `Pure(IntV(...))`
  caching in an IdentityHashMap — single biggest step.
- **closureWithSelfFor cache.**  `closure.updated(name, self)` is the
  same Map on every call of the same FunV; cache it.
- **FrameMap (1 / 2 / N slots).**  A specialised `Map[String, Value]`
  that stores a small frame of local bindings as direct fields on top
  of a `parent` map.  Construction is one allocation; lookup is a
  string-equality check on the slot then a fall-through to parent.

Re-benchmark protocol after each step lives in this commit history;
re-running `scala-cli bench/run.sc` should reproduce the figures.

Tier 2 (bytecode IR) and Tier 3 (JVM-bytecode JIT or Truffle/Graal)
are out of scope until a real workload demands them — `ssc compile`
already covers throughput.

## Beyond

Larger features that aren't on the critical path but are worth keeping in
view so they shape near-term decisions.

- **Component system.**  A `.ssc` file as a self-contained component:
  `scalascript` for logic, `html` for view, `css` for style, optional
  front-matter route declarations.  Plays well with v0.2 heading-bound
  blocks.
- **WebSocket support.**  `ws("/path") { conn => ... }` upgrade primitive,
  bidirectional `Frame` type.
- **Persistence.**  At minimum a `Storage` effect (key-value, JSON-backed)
  so REST demos can outlive a process restart without dragging in JDBC.
- **Hot reload in `serve` mode.**  Reparse and re-register routes when a
  `.ssc` file changes on disk; today the server pins them at start.
- **REPL: web-aware mode.**  `bin/ssc repl` that lets you mount routes
  interactively and inspect the route table.
- **`html"..."` precision.**  Smarter `${}` parsing inside string-blocks
  so `${ a + "}" }` doesn't fool the regex (current TODO in the inline
  block evaluator).
