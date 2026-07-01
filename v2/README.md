# ssc 2.0 — a self-hosting language on a tiny frozen kernel

`v2/` is a **clean-room redesign** of ScalaScript: a minimal kernel in Scala 3, with the
*entire language* — its own compiler, type system, effects, concurrency, typeclasses, and
three code backends — grown on top **in the language itself**. Isolated (own scala-cli build,
zero dependency on the `ssc 1.0` tree). Architecture & decisions: [`specs/00-overview.md`](specs/00-overview.md).

## The one bet

Scope the hand-written, **permanent Scala kernel** to *host the self-compiler* — not to run
the whole language. Everything else is an `.ssc0` program compiled to a tiny untyped bytecode
(**Core IR**) the kernel runs. Result: the kernel is **frozen at 913 lines of Scala**, and
across this entire project it grew by exactly **one primitive** (`coreir.encode`). Everything
below is **~2500 lines of ssc0** on top of it — including a feature-complete Hindley-Milner
typed language (typeclasses, records, pattern matching, a prelude) compiling to three backends.

```
ssc0 source ──► ir (bytecode) ──► ssc (VM: compile-to-closures + trampoline) ──► cpu
                         │
                         └──► JS  (lib/backend-js.ssc0)   ──► node
                         └──► Rust(lib/backend-rust.ssc0) ──► rustc → native
```

## What it is, end to end

| Layer | Where | What |
|---|---|---|
| **kernel** (compiler + VM + ssc0 front + `coreir.encode`) | `src/*.scala`, **frozen 913 LOC** | untyped Core IR ([`10-core-ir`](specs/10-core-ir.md)), a compile-to-closures VM with a trampoline (constant-stack TCO), the ssc0 lexer/parser/lower, and canonical IR serialization |
| **self-hosting compiler** | `lib/ssc0c.ssc0` (+ `loader.ssc0`) | the ssc0 compiler **written in ssc0**; compiles itself byte-for-byte (single- and **multi-file** fixpoints) — [`20-bootstrap`](specs/20-bootstrap.md) |
| **typed layer `ssct`** | `lib/ssct*.ssc0` | a typed lambda calculus: type checker, textual `.ssct` surface (lexer+parser in ssc0), erase-to-ir, and **typeclasses resolved by the typer** — [`40`](specs/40-typer-as-library.md), [`52`](specs/52-typeclasses.md) |
| **typed language `Lark`** | `lib/Lark*.ssc0` | a **feature-complete Hindley-Milner** functional language: inference + let-polymorphism, recursion + currying, Int/Bool/String/**Float**, polymorphic lists `[a]`, **tuples**, **records**, **user `data` types**, pattern matching (constructor / wildcard / variable / **literal** / **nested**), built-in **Show/Eq/Ord** + **user `method`/`instance` typeclasses**, monadic **do-notation**, **type ascription** `(e : T)`, **algebraic effects** (one-shot + multi-shot, effect rows, and typed resumes for declared single- and multi-op effects), an auto-injected **standard prelude** (~90 functions), `//` comments — written as source text, compiled to **VM / JS / native Rust** — [`41`](specs/41-Lark.md) |
| **effects** | `lib/effects.ssc0` | algebraic effects + handlers, incl. **multi-shot** continuations — [`50`](specs/50-effects.md) |
| **concurrency** | `lib/async.ssc0`, `lib/actors.ssc0` | cooperative schedulers (`yield`/`fork`, plus futures/await/channels/mailboxes) and the actor model — [`51`](specs/51-async.md), [`53`](specs/53-actors.md), [`56`](specs/56-async-actors-breadth.md) |
| **backends** | `lib/backend-js.ssc0`, `lib/backend-rust.ssc0` | `ir → JS` and `ir → Rust`, both TCO-correct and multi-file — [`60`](specs/60-backend-js.md), [`61`](specs/61-backend-rust.md) |
| **stdlib** | `lib/list.ssc0`, `lib/string.ssc0`, `lib/option.ssc0`, `lib/mapx.ssc0`, `lib/set.ssc0`, `lib/sha256.ssc0`, `lib/irbin.ssc0` | lists, strings, options, structural map/set helpers, SHA-256, and compact IR tooling |

The kernel never gained: a type checker, the typed surface parser, effect/continuation nodes,
actors, a JIT-to-bytecode, or any target backend. Each is an ssc0 program on the frozen core.

## Run it

```bash
# the kernel binary (./ssc run | compile | run-ir):
./ssc run examples/quicksort.ssc0          # => Cons(1, Cons(1, Cons(2, …)))   (a real algorithm)

# the self-hosting ssc0 compiler (emits the same ir bytecode as the kernel, byte-for-byte):
./ssc0c examples/fact.ssc0 | ./ssc run-ir /dev/stdin     # => 120

# the typed layer:  ./ssct <file.ssct>   (lex+parse+typecheck+run, all in ssc0)
./ssct examples/id.ssct                    # => Typed("Int", 42)

# the HM-inferred typed language:  infer a type, or compile it to any of three targets
./Lark      examples/hm-qsort.hm                # => "[Int]"   (Algorithm-W inferred)
./Lark-rust examples/hm-eval.hm > e.rs && rustc -O e.rs -o e && ./e   # => 7  (a typed interpreter, native)

# the backends — one source, three targets, identical output:
./ssc0-js   examples/quicksort.ssc0 | node                          # => Cons(1, …)
./ssc0-rust examples/quicksort.ssc0 > q.rs && rustc -O q.rs -o q && ./q   # => Cons(1, …)
```

(`./ssc`, `./ssc0c`, `./ssct`, `./ssctc`, `./Lark`, `./Lark-{js,rust}`, `./ssc0-js`,
`./ssc0-rust` are thin scala-cli launchers over [`src/`](src) running the corresponding ssc0
driver in `bin/`.)

## Layout

```
v2/
  ssc ssc0c ssct ssctc ssc0-js ssc0-rust   launchers
  src/        the kernel (Scala 3): CoreIR · Runtime (VM/δ/coreir.encode) · Ssc0 · Main
  lib/        the language, in ssc0: ssc0c, ssct*, effects, async, actors, typeclass,
              backend-js, backend-rust, loader, list, string, option, mapx, set, sha256, irbin
  bin/        ssc0 drivers (ssc0c, ssct, ssctc, ssc0-js, ssc0-rust)
  examples/   runnable .ssc0 / .ssct programs
  conformance/ ir fixtures + check.sh (one jar, 300+ checks across every layer & target)
  specs/      00-overview · 10-core-ir · 12-ir-format · 15-ssc0 · 20-bootstrap ·
              40-typer · 50-effects · 51-async · 52-typeclasses · 53-actors · 60/61-backends
```

## Status

Conformance (`conformance/check.sh`, all green): the runtime compiler (3 modes), the
self-hosting fixpoints (single- and multi-file), the typed layer + typeclass resolution,
effects (incl. multi-shot) / async / actors, and **all three targets (VM / JS / native Rust),
TCO-correct**, agreeing byte-for-byte on real programs. Roadmap: [`ROADMAP.md`](ROADMAP.md);
queue: [`SPRINT.md`](SPRINT.md). Remaining work is breadth (more stdlib/showcases and
concurrency libraries) plus WASM when a toolchain is present — all ssc0 on the frozen kernel.
