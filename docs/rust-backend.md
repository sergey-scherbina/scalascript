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

This lets you escape into Rust whenever a feature is outside the R.1
capability surface — for now, that includes pattern matching, type
classes, mutable state, etc.  Treat `rust` blocks as a release valve
until R.2 lands.

Non-rust backends (`jvm`, `js`, interpreter) reject a `rust` block as
`Diagnostic.Generic` — never a silent miscompile.

---

## What's supported in R.1

Phase R.1 accepts the hello-world shape:

- `@main def name(): Unit = …` and `def name(): Unit = …`
- Body: a single expression or a `Term.Block`
- Statements: `Lit.{Int, Long, Double, String, Boolean, Unit}` and
  `Term.Apply` against a callee in the `RustIntrinsics` table
  (`println`, `print`, `Console.println`, `Console.print`)

Anything outside this surface returns `CompileResult.Failed` with a
`Diagnostic.Generic` naming the offending shape.  Specifically:

| Feature | Status | Slated for |
|---|---|---|
| Console I/O (`println`, `print`) | ✅ R.1 | — |
| String interpolators (`s"…"`) | ✅ R.1 | — |
| Module imports | ✅ R.1 | — |
| `rust` fence blocks | ✅ R.1 | — |
| Pattern matching, case classes | ❌ | R.2 |
| `var`, `while` | ❌ | R.2 |
| Closures, higher-order functions | ❌ | R.2 |
| `for` comprehensions | ❌ | R.2 |
| `std.io.readFile` / `writeFile` | ❌ | R.3 |
| `sha256`, `base64`, JSON | ❌ | R.3 |
| Algebraic effects (`perform` / `handle`) | ❌ | R.4 |
| HTTP server (`std.http`) | ❌ | R.5 |
| WebSockets, Auth, MCP, streams | ❌ | R.6 |

See [`../specs/rust-backend.md §8`](../specs/rust-backend.md) for the
authoritative capability matrix per phase.

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

Phases R.2 through R.6 widen the capability set incrementally; see the
spec.  Highlights:

- **R.2** — core IR coverage: case classes, pattern matching, mutable
  state, `while`, closures, `for` comprehensions.
- **R.3** — intrinsics MVP: filesystem I/O, time, sha256/base64, JSON
  via `serde_json` (added to `Cargo.toml` per-call demand).
- **R.4** — algebraic effects via a Free-monad runtime in stable Rust.
- **R.5** — `std.http` parity via `tokio` + `hyper` (only pulled in
  when the program uses the HTTP intrinsics).
- **R.6** — WebSockets (`tokio-tungstenite`), Auth (`argon2` +
  `jsonwebtoken`), MCP, streams, type classes, multi-shot
  continuations, monomorphisation pass in core.

---

## Related documents

- [`targets.md`](targets.md) — index of every backend.
- [`../specs/rust-backend.md`](../specs/rust-backend.md) — design
  spec; phase-by-phase scope and decisions.
- [`../README.md`](../README.md) — top-level project README.
