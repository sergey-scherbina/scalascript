# Changelog

Completed milestones, newest first. Each entry is a brief summary; git history has full implementation notes.

---

## 2026-05-26

- **v1.48.5** — `ssc publish --target ios` (TestFlight + App Store via fastlane): generates `Fastfile` with `testflight`/`appstore` lanes by default; `--fastlane` uses existing Fastfile; `--testflight`/`--appstore` route selection; `--api-key-path` / `APP_STORE_CONNECT_API_KEY_PATH`; `--submit-for-review`; `--release-notes`; 6 new tests (18 total in SwiftUIBuildCliTest).
- **v1.48.4** — `ssc package --target ios` → signed `.ipa`: `xcodebuild archive` + `exportArchive`; ExportOptions.plist generated from frontmatter `bundle-id:`/`team-id:` or `SSC_TEAM_ID` env; `--export-method` (development|ad-hoc|enterprise|app-store, default: development); `--team-id`; `--out`; 4 new tests (12 total in SwiftUIBuildCliTest).
- **SQL plugin cleanup** — interpreter `transaction` fenced blocks now route through `SqlBlockRunner.runTransaction`; JDBC transaction execution and result encoding live in `runtime/std/sql-plugin` instead of interpreter core.
- **v1.48.3** — `ssc run --target ios --device` real device via ios-deploy: xcodebuild arm64 + automatic signing (`-allowProvisioningUpdates`) + `ios-deploy --bundle ... --no-wifi [--debug|--justlaunch]`. `--device-id <udid>` for specific device. Same `--console`/`--no-rebuild` flags as simulator path.
- **v1.48.2** — `ssc run --target ios` one-command iOS Simulator launch: xcodebuild → boot latest iPhone sim → open Simulator.app → install → `simctl launch`. `--console`/`--no-console` (default: stream logs), `--rebuild`/`--no-rebuild` (default: incremental mtime check). `--target ios` canonical; `mobile-ios` alias kept. `pickIosSimulator` picks latest available iPhone from highest iOS runtime.
- **v1.48.1** — `ssc run --target macos` one-command wrapper: generates Swift Package, runs `swift build`, launches binary. `ssc package --target macos` now includes `swift build`. Target renamed `desktop-macos` → `macos` (alias kept). `swiftAppName` helper derives Swift product name from `.ssc` name frontmatter.
- **v1.48** (SwiftUI Phase 3) — Reactive list lowering: `ForSignal` → `ForEach` with `ForCtx` for index-aware `RemoveSelfFromList`; `@Observable AppModel` emitted when list signals present (Observation framework, iOS 17+); 11 new tests (41 total). v1.48 now feature-complete.
- **v1.48** — JavaFX Typed Route Clients: same-process in-process BackendTransport for JavaFX; typed route client codegen for JavaFX mode.
- **v1.47** — JavaFX Desktop Frontend: JavaFX renderer with reactive View DSL and full SwingFrontend-parity API.
- **v1.46** (all phases complete) — Typed Route Clients: generated frontend clients over backend routes. JVM/Swing in-process + JS HTTP transports. Auth/custom header injection, per-call header overrides, retry policy, cancellation tokens. Phase 7: SSE streaming. Phase 8 (WS): `stream: ws` → `_SscWsHandle` bidirectional subscriptions. Pagination: `paginated: true` → `<name>Paged(page, size, ...)` appending `?page=N&size=M` to URL on both JVM and JS targets. Extended Phase 5: `RouteDeriver` now covers `routes:` front-matter, `mount()` calls, and cross-file typed-handler analysis (`(input: T) => ...` → `requestType = "T"`); `Parser.parseFile` passes `baseDir` for handler lookup.
- **v1.45** — JVM Desktop Frontend: Swing-based desktop frontend; reactive View DSL; JvmUiRuntime; `ssc run --frontend swing`.
- **v1.44** — Full-Stack In-Process Transport: BackendTransport + same-process fetch dispatch; Swing/JVM frontend reaches backend without HTTP.
- **v1.43** — Electron JVM REST Backend: `ssc run --mode server` + Electron renderer; REST-over-localhost JVM backend for Electron apps.

## 2026-05-23

- **v1.42** — Native Platform P3: Electron Renderer — Electron shell + Node.js IPC bridge; `ssc run --frontend electron`.
- **v1.41** — Native Platform P2: Toolchain UX — native build CLI ergonomics; `ssc native` subcommand improvements.
- **v1.40** — Native Platform P2: Web Renderer Update — updated Electron-embedded web renderer; renderer protocol version bump.
- **v1.39** — Native Platform P1: IR Foundation — new IR nodes + codegen for native platform targets.

## 2026-05-21

- **v1.37** — Typer: `ssc check` 33→94 examples — typer fixes raising passing conformance suite from 33 to 94 examples.
- **v1.36** — Parser bugfix: `preprocessInlineImports` ordering — fixed parse-order regression in inline import preprocessing.
- **v1.35** — `run-jvm` artifact caching — incremental rebuild avoidance for JVM-target `.ssc` scripts.
- **v1.34** — REPL Debugger — interactive breakpoint + step-through debugger in the REPL.
- **v1.33** — Interpreter lazy loading Phase 2 — deferred plugin loading; faster cold start.
- **v1.32** — `runtime/std/pwa-plugin`: Progressive Web App support — service worker, manifest generation, offline mode.
- **v1.31** — `transaction` fenced block — database transaction scope (`transaction { … }`) as a language construct.
- **v1.30** — REPL web-aware mode + `mount()` intrinsic — `ssc repl --web`; `mount()` hot-replaces running server routes.
- **v1.29** — DAP Debugger (`ssc debug`) — Debug Adapter Protocol server; VS Code / IDE debugger integration.
- **v1.28** — Config System — `config.ssc` front-matter + `ssc.Config.*` typed accessor; environment overrides.

## 2026-05-20

- **v1.25** — JavaScript / Node.js fenced code blocks — `js` and `node` fenced blocks executed natively in JS target; seamless JS interop from `.ssc`.
- **Wallet SPI** — Scala.js cross-compile sprint: wallet interface + Scala.js cross-compiled runtime; browser + JVM wallet stubs.

## 2026-05-19

- **v1.24** — Language features: pattern matching extensions, string interpolation improvements, type inference fixes, sealed-trait enhancements.
- **v1.23** — Cluster management: membership view + events, Phi-accrual failure detection, Bully + Raft leader election, config distribution, rolling-restart drain, cluster metrics, external-coordinator adapter (etcd/Consul/ZooKeeper).
- **v1.22** — Distributed map-reduce: `Dataset[T]` API over v1.6 distributed actors; coordinator-dispatched partitions; shuffle; configurable failure handling.
- **v1.21** — Local map-reduce (`Dataset[T]`): lazy fluent API; sequential + parallel local execution; streaming via v1.10 generators.
- **v1.20** (all sub-versions) — DSL primitives + `runtime/std/parsing`: user-defined string interpolators, parser-combinator library, error recovery, indentation-aware parsing, multi-pass pipeline.
- **v1.19** — URL / dep imports: `[X](https://...)` URL fetch + `[X](dep:org/lib:1.2)` resolver with `ssc.lock` SHA-256 integrity.
- **v1.18** — `package` keyword + std layout migration: `package foo.bar` declarations; `runtime/std/*` reorganised under package hierarchy.
- **v1.17** — MCP support (client + server): full MCP 2025-03 + OAuth 2.1 + OIDC compliance; AS + RS + OIDC IdP; WebAuthn passkey grant; persistent stores; observability; CLI `ssc oauth` subcommand.
- **v1.13** — Final Tagless ergonomics: `using` auto-resolution, context bounds, cross-file trait inheritance with HKT, sealed-trait extension dispatch.
- **v1.9.x** — Actor internals refactor: mailbox + scheduler rewrite; reduced allocation on hot actor paths.
- **v0.12** — SSR + client hydration: Declarative Shadow DOM via `wc()` + zero-JS-rerender hydration guard.
- **v0.11** — i18n / l10n: `translations:` front-matter + `t(key)` / `setLocale(code)` intrinsics across all backends.
- **v2.0** (MVP) — Separate compilation: artifact format, `InterfaceExtractor`, `ArtifactIO`, `InterfaceScope`, `Linker` (FQN rewrite), `ModuleGraph`, six CLI commands. Full pipeline deferred; see [BACKLOG.md](BACKLOG.md#v20--separate-compilation-of-modules).

## 2026-05-18 and earlier

- **v1.16** — Restartable errors via algebraic effects: `perform`/`handle`/`resume` across all backends.
- **v1.15** — Checked errors via `throws`: dual-encoding (`throws` / `throwsRaw`), `attemptCatch`, `HasStackTrace`, platform-exception shims.
- **v1.14** — Metaprogramming MVP: `inline def`/`val`/`if`/`match`, `compiletime.summonInline`, `derives` recipes for Eq/Show/Hash/Order.
- **v1.11.5** — `Free[F, A]` as stdlib type: user-facing Free monad in `runtime/std/free.ssc`.
- **v1.11** — Continuation-based `Async`: rewrite on top of v1.9 coroutines; `Computation[A]` shim; ≥20% allocation reduction.
- **v1.10** — Generators: `flatMap`, `zip`, `zipWithIndex`; all three backends; streaming foundation.
- **v1.9** — Coroutine primitive: `Coroutine[A, B]`; interpreter + JvmGen + JsGen; 19 conformance tests.
- **v1.8.1** — Direct-syntax extensions: additional monad `do`-notation shapes; error-channel integration.
- **v1.8** — Direct-syntax do-notation: `for`/`yield` over arbitrary monads; all phases; conformance suite.
- **v1.7** — Plugin packaging & discovery: `.sscpkg` format, `pkg:` URI resolver, `ssc install`, local registry.
- **v1.6** — Actors (Erlang-style, WebSocket-distributed): local actors, supervision trees, distributed via WS; all backends.
- **v1.5** — Transport layer: TLS + HTTP/WS clients + streaming (Phases A–D′; NIO migration deferred).
- **v1.4** — Standard-library effects: `State`, `Reader`, `Writer`, `IO`, effect-system stdlib.
- **v1.3** — Runtime upgrades: real-thread Async (virtual threads), persistence layer, Async-integrated WS.
- **v1.2** — Auth follow-up: combined example + WebAuthn / passkeys.
- **v1.1** — Standard type-class hierarchy: `Functor`, `Applicative`, `Monad`, `Foldable`, `Traversable`, etc.
- **v1.0** — WebSocket production-readiness: Sprints 1–4, 6 (Sprint 5 deferred).
- **v0.10** — Extended component pack: Card, Switch, Alert, Tag, Stats, Empty, Toolbar, Tree, Stepper, Lightbox, FileUpload, DateInput/Picker, TimePicker, Combobox, RangeSlider, Carousel.
- **v0.9** — Standard component pack + Optics second pass: 8-tier UI component library; Index optic (`.index(i)` / `.at(key)`).
- **v0.8** — Web Components target: `ssc emit-wc`, `customElements.define`, Shadow DOM, hydration guard.
- **v0.6** — Optics: Lens / Prism / Optional / Traversal across all backends.
- **v0.5** — Interpreter performance Tier 1: dispatch-table rewrite; ~3× faster on typical workloads.
- **Backend SPI v0.1** (Stages 1–9.1) + followups: 9-module sbt layout, SPI traits, in-process + out-of-process plugins, intrinsic extraction (`std.http`, `std.ws`, `std.auth`, etc.), `ssc fmt`.
- **Scala ↔ ScalaScript interop** — Tiers 1 + 2: `@ssc` annotation → `.ssc` stub generation; Scala callers import generated stubs.
