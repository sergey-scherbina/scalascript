# Rust Backend

Compile ScalaScript to a self-contained native binary via Rust + Cargo.

This guide is the user-facing companion to the design spec
[`../specs/rust-backend.md`](../specs/rust-backend.md).  Read the spec
for *why* the backend looks the way it does; this page covers
*how to use it*.

---

## Prerequisites

You need the Rust toolchain (`rustc` + `cargo`).  ScalaScript itself
does not bundle one — the CLI shells out to your `cargo`.

**Install one of:**

- **Homebrew (macOS / Linux with Homebrew)**

  ```bash
  brew install rust
  ```

- **Official installer (every other platform)** — go to
  <https://www.rust-lang.org/tools/install> and follow the `rustup`
  setup.  Adds `cargo` to `~/.cargo/bin/`; make sure that's on your
  `PATH`.

Verify:

```bash
cargo --version
```

If `cargo` is missing, `ssc build-rust` / `ssc run-rust` print a
short message pointing you here and exit 1 — they do nothing else.

---

## Quick start

Write a `hello.ssc`:

````markdown
# Hello

```scalascript
@main def run(): Unit = println("Hello from Rust")
```
````

Build a native binary:

```bash
ssc build-rust hello.ssc
./hello
```

Output:

```
Hello from Rust
```

Or build-and-run in one step:

```bash
ssc run-rust hello.ssc
```

That's it.  The binary is fully self-contained — no JVM, no JS
runtime, no network calls at start-up.

---

## The three CLI commands

| Command | What it does | When to use |
|---|---|---|
| `ssc emit-rust` | Writes a Cargo crate to `-o <dir>` (default `./<stem>-rust/`).  No build, no run. | Inspecting the generated Rust source, integrating with your own Cargo workspace, CI artefacts. |
| `ssc build-rust` | Emits the crate to a temp dir, runs `cargo build --release`, copies the binary to `-o <path>` (default `./<stem>`), cleans up. | Shipping a binary. |
| `ssc run-rust` | Like `build-rust`, but executes the binary immediately and forwards its exit code.  Argv after `--` is passed to the binary. | One-shot scripts, smoke tests. |

### Common flags

`build-rust` and `run-rust`:

| Flag | Default | Notes |
|---|---|---|
| `-o <path>` (build-rust only) | `./<stem>` | Where the binary lands. |
| `--debug` | off | Use `cargo build` without `--release`.  Faster compile, slower binary. |
| `--target <triple>` | host | Forwarded to cargo as `--target <triple>` for cross-compile. |
| `--offline` | off | Forwarded to cargo. |
| `--verbose` | off | Stream cargo's stdout/stderr through; otherwise the build step is quiet. |
| `--keep-crate <dir>` (build-rust only) | discarded | Keep the emitted Cargo crate at `<dir>` for inspection. |
| `--` (run-rust only) | — | Everything after `--` becomes argv for the built binary, like `cargo run --`. |

`emit-rust`:

| Flag | Default | Notes |
|---|---|---|
| `-o <dir>` | `./<stem>-rust/` | Output directory. |
| `--print-only` | off | Stream every asset to stdout with `// ── <name> ──` separators; no disk writes. |
| `--bin-name <name>` | — | Override the binary name in `Cargo.toml`. |

---

## What the emitted crate looks like

```
hello-rust/
├─ Cargo.toml
└─ src/
   ├─ main.rs                    # binary entrypoint (when @main present)
   ├─ value.rs                   # closed Value enum (Unit, Bool, Int, …)
   ├─ runtime/
   │   └─ mod.rs                 # _show / _print / _println helpers
   └─ generated/
      ├─ mod.rs                  # pub mod <crate>
      └─ <crate>.rs              # one `pub fn` per top-level def
                                 # + rust fence blocks verbatim
```

The runtime files (`value.rs`, `runtime/mod.rs`) are emitted
byte-identical for every crate at R.1 — they are infrastructure
templates, not generated per-program.  `Cargo.toml` carries no
runtime dependencies for hello-world; phase R.3 pulls in `sha2`,
`base64`, `serde_json` only when a program actually uses the
corresponding intrinsic.

When the source contains no `@main`, RustGen emits `src/lib.rs`
instead of `src/main.rs` and `Cargo.toml` declares a `[lib]` target.

---

## Mixing `scalascript` and `rust` fence blocks

A single `.ssc` can contain both ScalaScript definitions and
hand-written Rust items:

````markdown
```scalascript
@main def run(): Unit = println("Hello via rust block")
```

```rust
pub fn util() -> i64 { 7 }
```
````

The Rust source is appended into `src/generated/<crate>.rs`
verbatim, under a `// ── rust block <N> ──` separator.  Both halves
end up as ordinary crate-level definitions; `cargo build` does not
distinguish between SS-derived `pub fn run()` and user-written
`pub fn util()`.

This lets you escape into Rust whenever a feature is outside the
current capability surface (see the support table below) — for
example, `perform` / `handle` lowering (R.4.2) or type classes.  Treat
`rust` blocks as a release valve.

Non-rust backends (`jvm`, `js`, interpreter) reject a `rust` block as
`Diagnostic.Generic` — never a silent miscompile.

---

## What's supported

The Rust backend has grown well past the original R.1 hello-world
shape.  Anything outside the supported surface returns
`CompileResult.Failed` with a `Diagnostic.Generic` (or
`Diagnostic.Unsupported`) naming the offending shape — never a silent
miscompile — and you can always drop into a `rust` fence block as a
release valve.

| Feature | Status | Phase |
|---|---|---|
| Console I/O (`println`, `print`), string interpolators (`s"…"`) | ✅ | R.1 |
| Module imports, `rust` fence blocks | ✅ | R.1 |
| `var` + reassignment, `while` loops | ✅ | R.2 |
| Scala 3 `enum` + `match` pattern matching | ✅ | R.2 |
| Closures / higher-order functions (`A => B` → `impl Fn`) | ✅ | R.2 |
| `for … yield` (single-generator), `List(…)` → `Vec` | ✅ | R.2 |
| Filesystem I/O (`readFile` / `writeFile`), env (`getenv`) | ✅ | R.3 |
| `sha256`, `base64`, JSON (`jsonParse` / `jsonStringify` via `serde_json`) | ✅ | R.3 |
| Algebraic-effects **runtime** (`effect.rs` emitted on `perform`/`handle`) | ◐ | R.4.1 |
| `perform` / `handle` IR lowering (use a `rust` block until then) | ❌ | R.4.2 |
| HTTP server — `route(method, path, handler)` + `serve(port)` (hyper + tokio) | ✅ | R.5 |
| **Web toolkit `serve(view, port)` — SSR + reactive signals** | ✅ | R.5 |
| WebSockets (signal transport ✅; general `std.ws` / Auth / MCP / streams) | ◐ | R.6 |

The **web toolkit** in R.5 is the headline: a declarative `std/ui` view
(`element` / `signal` / `signalText` / `computedSignal`) compiled with
`serve(view, port)` emits a self-contained `tokio` + `hyper` HTTP
server with server-side rendering, a reactive signal store, real-time
**Server-Sent Events** push, **computed-signal live recompute**, typed
signal reads, and a **direct WebSocket** signal endpoint — see the next
section.  Dependencies are demand-driven: a program that never calls a
networking intrinsic stays dependency-free.

See [`../specs/rust-backend.md §8`](../specs/rust-backend.md) for the
authoritative capability matrix per phase.

---

## Web toolkit on Rust — reactive `serve`

A declarative `std/ui` view compiled to native Rust now boots a real
HTTP server with server-side rendering **and** end-to-end reactivity —
no JavaScript framework, no Node runtime.  The emitted crate pulls in
`tokio` + `hyper` (and `tokio-tungstenite` for the WS endpoint) only
when the program calls `serve`.

```scalascript
@main def run(): Unit =
  val locale   = signal("locale", "fr")            // server-side signal store
  val greeting = computedSignal(() => locale())    // derived; recomputes on change
  val view = element("div", Map(), Map(), List(
    signalText(greeting),                          // <span data-ssc-text="__c0">fr</span>
    signalText(locale)
  ))
  serve(view, 8080)                                // HTTP on :8080, WebSocket on :8081
```

What the emitted server gives you:

- **SSR** — `serve` renders the view to HTML on every request, with each
  signal's current value inlined into a `data-ssc-text` span.
- **A server-side signal store** — `signal(name, default)` seeds a shared,
  thread-safe store. `setSignal(sig, value)` / `toggleSignal(sig)` buttons
  and `inputChange` inputs persist back via `/__ssc/push?name=<n>&value=<v>`.
- **Computed signals that recompute live** — `computedSignal(() => dep())`
  registers a re-runnable closure. When a dependency changes the server
  recomputes every derived signal (`ssc_recompute_all`) and pushes the new
  value out before responding. A `Signal[Int]` read inside a computed thunk
  is parsed back to `i64` (typed reads); `Signal[String]` stays textual.
- **Server-Sent Events** — clients subscribe to `GET /__ssc/events`
  (`text/event-stream`), which streams `data: <state-json>` frames off a
  `tokio::sync::broadcast` channel. The client script prefers `EventSource`
  and falls back to a 1 s state poll.
- **Direct WebSocket** — a WS endpoint on `port + 1` sends the full signal
  state on connect, streams updates, and accepts `name=value` text frames
  (set → recompute → broadcast) for external/programmatic clients.

Verify the reactive loop without a browser:

```console
$ ssc build-rust app.ssc && ./app &
$ curl -s localhost:8080/__ssc/state                       # {"__c0":"fr","locale":"fr"}
$ curl -s 'localhost:8080/__ssc/push?name=locale&value=de'
$ curl -s localhost:8080/__ssc/state                       # {"__c0":"de","locale":"de"}  ← recomputed
```

---

## Errors you may hit

**`cargo not found on PATH`** — install Rust per the prerequisites
section.

**`Diagnostic.Unsupported(Feature.X, backend = "rust")`** — the
program uses a feature the rust target doesn't accept yet.  Either
write that bit inside a `rust` fence block as a workaround, or wait
for the phase that lights up the feature (R.2 covers the largest
batch — pattern matching, mutable state, while/for, closures).

**`def \`name\` has parameters; R.1 hello-emit accepts only
zero-parameter defs`** — defs with parameters are R.2 work.  Inline
the call site for now, or escape into a `rust` block.

**`cargo build failed`** — the emitted crate is well-formed, but
something in your `rust` blocks does not type-check on the host
toolchain.  Use `ssc build-rust --keep-crate <dir> --verbose` to
inspect the generated source and re-run `cargo build` manually for a
full diagnostic.

---

## Roadmap

Phases R.2 through R.5 have landed (see the support table); R.6 widens
the remaining surface.  See the spec for the authoritative matrix.

- **R.2 ✅** — core IR coverage: `enum` + pattern matching, mutable
  state, `while`, closures, `for` comprehensions.
- **R.3 ✅** — intrinsics MVP: filesystem I/O, env, sha256/base64, JSON
  via `serde_json` (added to `Cargo.toml` per-call demand).
- **R.4 ◐** — algebraic-effects runtime (`effect.rs`) is emitted
  (R.4.1); direct `perform` / `handle` IR lowering (R.4.2) is the next
  slice — until then, drive the runtime from a `rust` block.
- **R.5 ✅** — HTTP via `tokio` + `hyper`: `route` + `serve(port)`, and
  the **web toolkit** `serve(view, port)` (SSR + reactive signals + SSE
  + computed live recompute + direct WS), all dependency-gated.
- **R.6** — general WebSockets (`std.ws`), Auth (`argon2` +
  `jsonwebtoken`), MCP, streams, type classes, multi-shot
  continuations, monomorphisation pass in core.

---

## Related documents

- [`targets.md`](targets.md) — index of every backend.
- [`../specs/rust-backend.md`](../specs/rust-backend.md) — design
  spec; phase-by-phase scope and decisions.
- [`../README.md`](../README.md) — top-level project README.
