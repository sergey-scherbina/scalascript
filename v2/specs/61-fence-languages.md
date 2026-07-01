# 61 — Fence Language Registry

> Status: **design** (2026-06-30). Implemented progressively: ssc0/lark in K0-K53;
> scalascript compat frontend in KC1-KC8.

Every `.ssc` file is a Markdown document. Code blocks are tagged by fence language.
The v2 Markdown extractor (KC1) reads the fence tag and routes each block to the
appropriate compiler pipeline.

## Fence language table

| Fence tag               | Pipeline                                     | Status          |
|-------------------------|----------------------------------------------|-----------------|
| `scalascript` / `ssc`   | v1.0-compat frontend → Core IR → VM/JS/Rust  | planned (KC1–8) |
| `scala`                 | Standard Scala 3 (passthrough / JVM only)    | deferred        |
| `lark`                  | Lark (Lark): HM-typed FP language → Core IR → VM/JS/Rust | done (K5–K53) |
| `ssc0`                  | Raw ssc₀ seed language → Core IR → VM        | done (K0)       |
| `rust`                  | Rust source — passthrough into Cargo crate   | done (K4)       |
| `javascript` / `js`     | JS source — passthrough                      | deferred        |

## Pipeline routing (v2)

The Markdown extractor (KC1) produces a list of `(fence-lang, source)` pairs.
Routing is itself a Lark/ssc0 program (not a kernel feature):

```
.ssc file
  └─ Markdown extractor (KC1, written in Lark)
       ├─ (scalascript, src) → v1.0-compat frontend (KC2–8) → Core IR
       ├─ (lark, src)        → Lark compiler (Lark-front.ssc0) → Core IR
       ├─ (ssc0, src)        → ssc compile → Core IR
       └─ (rust, src)        → passthrough → Cargo
```

The routing table is data: adding a new language means adding a row, not changing
the kernel.

## Lark language identity

**Lark** is the v2 typed surface language. It was previously called `Lark`.

- ML/Haskell-family: HM type inference + let-polymorphism + algebraic effects
- ADTs + pattern matching + mutual recursion
- Type classes via qualified-type dict passing (Num/Ord/Show + user-defined)
- Full IEEE-754 float math library
- Algebraic effects with effect rows (perform/handle/resume + doE sugar)
- ~90-fn standard prelude (list/string/math/Option/Either/assoc-list/parser combinators)
- Three backends: VM (via ssc run-ir), JS (via node), native Rust (via rustc)
- File extension: `.lark` (preferred) or `.hm` (legacy, still accepted)

**Lark is not v1.0-compatible.** It is a different language in the same ecosystem.
v1.0 `.ssc` files use the `scalascript` fence tag and run through the compat frontend.

## Front-matter and multi-block files

A `.ssc` file may contain both `scalascript` and `lark` blocks. The Markdown extractor
collects all blocks; execution order follows document order. Cross-block communication
uses the shared module namespace (imported values; the v2 module loader handles dedup).

YAML front-matter (before the first `#` heading) is extracted separately and passed
to the runtime as a metadata record.
