# ScalaScript documentation index

Guide documents live flat in `docs/`; normative feature contracts live in
`specs/` and are linked here with their stable paths. Files are intentionally not
nested into topic subdirectories. This index is the map for both sets.

> New feature specs land here first (see `AGENTS.md` → *Spec-driven
> development*), then implementation PRs reference them by path.

---

## Getting started & guides

- [project-summary.md](project-summary.md) — Project Summary — one-page overview of the language, the five backends, and headline capabilities
- [getting-started-standalone.md](getting-started-standalone.md) — Getting Started Standalone
- [v2.0-getting-started.md](v2.0-getting-started.md) — v2.0 Separate Compilation — Getting Started
- [tutorial.md](tutorial.md) — Tutorial 1: Collaborative Todo API
- [user-guide.md](user-guide.md) — ScalaScript User Guide
- [frontend-usage.md](frontend-usage.md) — Frontend SPI — usage guide
- [native-plugin-guide.md](native-plugin-guide.md) — Native Plugin Binary Guide
- [community-plugins.md](community-plugins.md) — Community Plugins
- [writing-a-backend.md](writing-a-backend.md) — Writing a ScalaScript Backend

## Language & semantics

- [markdown-as-syntax.md](markdown-as-syntax.md) — Markdown as Syntax
- [../specs/markdown-content-introspection.md](../specs/markdown-content-introspection.md) — Markdown-to-frontend content layer and metadata introspection
- [dsl.md](dsl.md) — Defining domain-specific languages
- [../specs/algebraic-effects.md](../specs/algebraic-effects.md) — Algebraic Effects
- [../specs/coroutines.md](../specs/coroutines.md) — Coroutines
- [../specs/control-interoperability.md](../specs/control-interoperability.md) — target-neutral effects, multi-prompt `shift`/`reset`, managed callbacks/TCO, and durable `save`/`run` laws
- [../specs/scala3-bidirectional-control.md](../specs/scala3-bidirectional-control.md) — Scala 3/JVM control host profile
- [../specs/scala3-control-macros.md](../specs/scala3-control-macros.md) — bounded Scala 3 lexical `direct.reset`/`direct.shift` macros
- [../specs/javascript-typescript-bidirectional-control.md](../specs/javascript-typescript-bidirectional-control.md) — JavaScript/TypeScript control host profile
- [../specs/javascript-typescript-control-direct.md](../specs/javascript-typescript-control-direct.md) — bounded TypeScript 5.9 lexical `shift`/`reset` transform, build-time marker erasure, and packed CLI
- [../specs/rust-bidirectional-control.md](../specs/rust-bidirectional-control.md) — Rust control host profile
- [../specs/swift-bidirectional-control.md](../specs/swift-bidirectional-control.md) — Swift control host profile
- [../specs/control-interop-profile-portable-vm.md](../specs/control-interop-profile-portable-vm.md) — portable-VM reference runner profile and measured capability row
- [../specs/wasm-wasi-control-runner.md](../specs/wasm-wasi-control-runner.md) — WASM/WASI saved-control runner profile
- [direct-syntax.md](direct-syntax.md) — Direct-syntax do-notation
- [error-handling.md](error-handling.md) — `throws[A, E]` + integration
- [exact-numerics.md](exact-numerics.md) — BigInt, Decimal, Money
- [final-tagless.md](final-tagless.md) — Final Tagless / typeclass UX
- [metaprogramming.md](metaprogramming.md) — `inline` + `derives` MVP
- [tuple-monoid.md](tuple-monoid.md) — Tuple Monoid
- [../specs/scala-interop.md](../specs/scala-interop.md) — Scala ↔ ScalaScript Interop
- [streams.md](streams.md) — Streams with Backpressure
- [saga.md](saga.md) — Coordinated multi-actor state changes with compensation

## Architecture & roadmaps

- [architecture.md](architecture.md) — Architecture overview
- [targets.md](targets.md) — Target Backends (index)
- [rust-backend.md](rust-backend.md) — Rust target user guide (emit-rust / build-rust / run-rust, rust fence blocks, + the reactive web toolkit `serve`: SSR + signals + SSE + WebSocket)
- [typer-real-types-roadmap.md](typer-real-types-roadmap.md) — Typer real-type evidence roadmap
- [modularity.md](modularity.md) — Modularity — three layers
- [arch-library-modularity.md](arch-library-modularity.md) — Library Modularity spec
- [optimization-roadmap.md](optimization-roadmap.md) — Optimization & Modularity Roadmap
- [performance.md](performance.md) — Performance & Memory (v1.61 roadmap + shipped phases)
- [benchmarks.md](benchmarks.md) — Benchmark reference (scripts/bench, JMH, corpus workloads)
- [vm-jit-spec.md](vm-jit-spec.md) — Hot-spot register VM + run-time BytecodeJIT spec
- [vm-jit-next.md](vm-jit-next.md) — Interpreter performance — next optimization phases
- [instancev-array-repr-spec.md](instancev-array-repr-spec.md) — InstanceV positional array fields (Direction B)
- [interpreter-perf-findings-2026-06.md](interpreter-perf-findings-2026-06.md) — JFR profiling findings (2026-06-02)
- [arch-metaprogramming-v2.md](arch-metaprogramming-v2.md) — Metaprogramming v2.x Roadmap
- [arch-dsl-hooks.md](arch-dsl-hooks.md) — DSL Platform Hooks
- [../specs/arch-ffi.md](../specs/arch-ffi.md) — Lightweight FFI (@jvm / @js / @rust / @wasm)
- [../specs/separate-compilation-plan.md](../specs/separate-compilation-plan.md) — v2.0 Separate Compilation Plan
- [../specs/v2.0-artifact-format.md](../specs/v2.0-artifact-format.md) — v2.0 Artifact Format wire spec
- [v2.0-scale-benchmark.md](v2.0-scale-benchmark.md) — v2.0 Scale Benchmark
- [v1.20-plan.md](v1.20-plan.md) — v1.20 DSL primitives + `std/parsing`

## Backend SPI & plugins

- [backend-spi.md](backend-spi.md) — Backend SPI — Plan
- [backend-spi-protocol.md](backend-spi-protocol.md) — Backend SPI out-of-process wire protocol
- [plugin-architecture.md](plugin-architecture.md) — Pluggable Intrinsics — Architecture
- [arch-stable-spi.md](arch-stable-spi.md) — Stable Plugin SPI
- [arch-build-registry.md](arch-build-registry.md) — Build-time Registry Consolidation
- [arch-registry.md](arch-registry.md) — Package Registry
- [arch-distribution.md](arch-distribution.md) — Distribution Ecosystem
- [arch-sbt-plugin.md](arch-sbt-plugin.md) — sbt-scalascript Plugin
- [arch-ssc-new.md](arch-ssc-new.md) — `ssc new` scaffolding & installation
- [intrinsics-migration.md](intrinsics-migration.md) — Intrinsics → `.sscpkg` migration
- [spi-5b-http-plan.md](spi-5b-http-plan.md) — SPI 5+/B std.http migration
- [spi-followups-plan.md](spi-followups-plan.md) — Backend SPI follow-ups
- [spi-intrinsics-design.md](spi-intrinsics-design.md) — Backend SPI intrinsics design notes
- [nativecontext-state-bag.md](nativecontext-state-bag.md) — NativeContext State Bag
- [config-system.md](config-system.md) — Config System
- [secret-resolvers.md](secret-resolvers.md) — Secret Resolvers
- [interpreter-server-extraction.md](interpreter-server-extraction.md) — Interpreter Server Extraction
- [runtime-server-strategic-plan.md](runtime-server-strategic-plan.md) — Runtime-server strategic plan

## Backends & targets

- [wasm-backend.md](wasm-backend.md) — WebAssembly Backend
- [native-platform.md](native-platform.md) — Native Platform Support
- [spark-catalog.md](spark-catalog.md) — Apache Spark Catalog DSL
- [spark-lakehouse.md](spark-lakehouse.md) — Spark Lakehouse formats (Delta / Iceberg / Hudi)
- [spark-mllib.md](spark-mllib.md) — Spark MLlib integration
- [spark-streaming.md](spark-streaming.md) — Spark Structured Streaming backend
- [mapreduce.md](mapreduce.md) — Map-reduce — `Dataset[T]`
- [dep-cps-rewrite.md](dep-cps-rewrite.md) — Dep-block CPS rewriting on the JVM
- [http-server-backends.md](http-server-backends.md) — Picking Jetty or Netty
- [http-server-spi-plan.md](http-server-spi-plan.md) — HTTP/WS Server SPI plan

## Distribution, clustering & actors

- [distributed-runtime.md](distributed-runtime.md) — Distributed Runtime
- [distributed-streams.md](distributed-streams.md) — `DStream[T]` Beam-style pipelines
- [distributed-wire-protocol.md](distributed-wire-protocol.md) — Distributed Wire Protocol
- [actors-dist.md](actors-dist.md) — Actors Phase 2 + 3 architecture
- [cluster-codegen-gap.md](cluster-codegen-gap.md) — Cluster intrinsics per-backend gap matrix
- [cluster-federation.md](cluster-federation.md) — Multi-cluster addressing & routing
- [cluster-management.md](cluster-management.md) — Peer-cluster orchestration
- [cluster-raft.md](cluster-raft.md) — Cluster leader election

## Frontend, desktop & mobile

- [frontend-abstract-model.md](frontend-abstract-model.md) — Frontend abstract model
- [frontend-framework-spi-plan.md](frontend-framework-spi-plan.md) — Frontend framework SPI plan
- [frontend-toolkit-spec.md](frontend-toolkit-spec.md) — High-level declarative UI spec
- [electron-renderer.md](electron-renderer.md) — Electron Renderer
- [electron-jvm-rest-backend.md](electron-jvm-rest-backend.md) — Electron JVM REST Backend
- [electron-persistence-bridge.md](electron-persistence-bridge.md) — Electron Persistence Bridge
- [electron-sql.md](electron-sql.md) — Electron SQL
- [javafx-desktop-frontend.md](javafx-desktop-frontend.md) — JavaFX Desktop Frontend
- [jvm-desktop-frontend.md](jvm-desktop-frontend.md) — JVM Desktop Frontend
- [swiftui.md](swiftui.md) — SwiftUI Native Frontend Backend
- [swiftui-typed-models.md](swiftui-typed-models.md) — SwiftUI Typed JSON Models (v1.66)
- [typed-models-ir.md](typed-models-ir.md) — Typed Models IR — cross-backend contract
- [pwa-plugin.md](pwa-plugin.md) — Progressive Web App support
- [repl-web.md](repl-web.md) — REPL Web-Aware Mode + `mount()`
- [mount-handlers.md](mount-handlers.md) — Mount handlers

## Web services & protocols

- [openapi.md](openapi.md) — OpenAPI contract platform
- [graphql.md](graphql.md) — GraphQL contract platform
- [contract-validation.md](contract-validation.md) — Shared OpenAPI/GraphQL contract validation plan
- [oauth.md](oauth.md) — OAuth 2.1 + OIDC
- [mcp.md](mcp.md) — Model Context Protocol support
- [future-protocols.md](future-protocols.md) — Future web-services protocols
- [typed-route-clients.md](typed-route-clients.md) — Typed Route Clients
- [fullstack-in-process-transport.md](fullstack-in-process-transport.md) — Full-Stack In-Process Transport

## Data & storage

- [postgres.md](postgres.md) — PostgreSQL Client
- [redis.md](redis.md) — Redis Client
- [kafka.md](kafka.md) — Kafka Client
- [graph-storage.md](graph-storage.md) — Graph Storage
- [data-mapping.md](data-mapping.md) — Typed Data Mapping Across Stores
- [client-server-object-store.md](client-server-object-store.md) — Client/Server Object Store & Sync
- [client-zookeeper.md](client-zookeeper.md) — Minimal ZooKeeper client (deferred)
- [browser-sql.md](browser-sql.md) — `sql` blocks on JS / Node / Wasm
- [scljet.md](scljet.md) — **SclJet**: a SQLite engine in pure ScalaScript — reads and writes real `.db` files (proven against `sqlite3`), a JDBC façade, and value-level addressing

## Payments, wallets & blockchain

- [x402.md](x402.md) — HTTP Payment Protocol
- [x402-cardano-scalus.md](x402-cardano-scalus.md) — Scalus settlement for Cardano
- [payment-request.md](payment-request.md) — Payment Request API
- [payment-rails-apac.md](payment-rails-apac.md) — Payment Rails APAC + Americas
- [traditional-payments.md](traditional-payments.md) — v1.53 Traditional Payment Processors
- [bank-rails.md](bank-rails.md) — v1.54 Bank Rails Payments
- [international-bank-rails.md](international-bank-rails.md) — v1.55 International & Domestic-Instant Bank Rails
- [micropayment-spi.md](micropayment-spi.md) — Micropayment Platform SPI
- [compliance-provider.md](compliance-provider.md) — v1.58 AML/KYC/Sanctions Compliance Provider
- [bureau.md](bureau.md) — v1.59 Bureau: Business–Government Interaction SPI
- [blockchain-spi.md](blockchain-spi.md) — Blockchain SPI — Plan
- [evm.md](evm.md) — EVM Client
- [coinbase.md](coinbase.md) — Coinbase Client
- [smart-contracts.md](smart-contracts.md) — ScalaScript Smart Contracts (DRAFT)
- [wallet-spi.md](wallet-spi.md) — Wallet SPI — Plan
- [wallet-spi-scalajs.md](wallet-spi-scalajs.md) — Wallet SPI — Scala.js cross-compile
- [wallet-vault-ledger.md](wallet-vault-ledger.md) — Ledger Hardware Wallet Vault
- [wallet-vault-trezor.md](wallet-vault-trezor.md) — Trezor Hardware Wallet Vault
- [wallet-vault-mpc.md](wallet-vault-mpc.md) — MPC Wallet Vault
- [mcp-x402-wallet.md](mcp-x402-wallet.md) — MCP × x402 × Wallet plan

## Deploy, debug & tooling

- [deploy.md](deploy.md) — Deploy to hostings, clouds & Kubernetes-like environments
- [dap-debugger.md](dap-debugger.md) — `ssc debug` Debug Adapter Protocol server
- [repl-debugger.md](repl-debugger.md) — REPL debugger commands
