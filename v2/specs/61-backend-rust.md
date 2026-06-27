# 61 — Backend: Core IR → Rust

> Status: **v1 (2026-06-27)** — `v2/lib/backend-rust.ssc0`. A second target backend, written
> in ssc0: Core IR → Rust that `rustc` compiles to a **native binary**. v2 now reaches three
> targets — JVM (the VM), JS (node), and native Rust — from one source, all on the frozen kernel.

## How

Rust is statically typed with ownership, so the backend emits over a **dynamic value enum**
plus reference-counted closures (a tiny runtime prelude):

```rust
enum V { I(i64), B(bool), S(String), D(String, Vec<V>), F(Rc<dyn Fn(Vec<V>) -> V>), U }
```

The walk mirrors the JS backend (`specs/60`):

| Core IR | Rust |
|---|---|
| `IrLocal i` | `v{d-1-i}.clone()` (clone on each use — never moves out) |
| `IrLam n body` | `{ <clone captured outer vars> V::F(Rc::new(move \|a:Vec<V>\| { …body })) }` |
| `IrApp f args` | `app(f, vec![args…])` |
| `IrGlobal name` | `g_name()` (each def is `fn g_name() -> V`) |
| `IrCtor tag fs` | `V::D("tag".to_string(), vec![fs…])` |
| `IrMatch` | `{ let s=…; match &s { V::D(t,f) => if t=="tag" {…} … } }` |
| `IrPrim "i.add"` | `V::I(asi(&a) + asi(&b))`, etc. |

The key Rust-specific care: each `move` closure first **clones the in-scope outer vars** so it
captures owned copies (stays `Fn`, callable many times); variable *uses* `.clone()` rather than
move. A `show` matching the VM's renders output comparably.

## Conformance (`conformance/check.sh`)

For `fact`, `map`, `calc`: `rustc -O (ssc0-rust X)` then run == `ssc run X`. Same output.

## Known limitation

`tco.ssc0` (1e6-deep tail loop) overflows — the `Rc<dyn Fn>` encoding doesn't get TCO. The
shared TCO-trampoline lowering pass (planned for JS too, `specs/60`) fixes this for both
targets; deferred.

## Why this matters

Two real backends now exist, each a ~120-line ssc0 walker over the same IR. "One source, many
targets" is literal: the same `fact.ssc0` runs on the VM, as JS on node, and as a native Rust
binary — all producing identical output, with the Scala kernel unchanged.
