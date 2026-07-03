# 60 — Backend: Core IR → JavaScript

## v1: ssc0-written backend (2026-06-27)

> `v2/lib/backend-js.ssc0`. The first *target* backend: a translator from Core IR to
> JavaScript, **written in ssc0**. v2 now emits runnable artifacts with no JVM.

The backend reuses `ssc0c`'s front (`source → IrProg` Data), then walks the IR emitting JS.
A `show` prelude renders values exactly like the VM's `Show`. TCO via a trampoline
(`bounce(f,a)` step objects; tail `IrApp` emits `bounce` instead of calling).
`./ssc0-js f.ssc0 | node`.

## v2: Scala 3 backend (2026-07-03)

> `v2/backend/js/JsBackend.scala` — reads the v2 Core IR S-expr format (via `ssc.Reader`)
> and emits a self-contained JS file. Run with `scala-cli run v2/backend/js/ v2/src/ --main-class ssc.js.main -- file.coreir | node`.

Pipeline:
```
.ssc  →  indent2braces.py  →  ssc1c.ssc0 (via VM)  →  Core IR  →  JsBackend  →  .js  →  node
```

### Representation

| Core IR type | JavaScript |
|---|---|
| Unit | null |
| Bool | true / false |
| Int (64-bit) | JS number |
| Big | JS bigint |
| Float | JS number |
| Str | JS string |
| Bytes | Uint8Array |
| ADT Ctor(tag, fs) | {t: "tag", f: [f0, f1, ...]} |
| cell / lcell | single-element array [v] |
| map | wrapper {m: Map, k: Map} |

### De Bruijn scope

`Scope = List[String]` newest-first. `Local(i)` → `scope(i)`.
- `Lam(N, body)`: N params, newest (last) = `local(0)`
- `Let([e1..eN], body)`: N bindings, eN = `local(0)`, e1 = `local(N-1)`
- `LetRec([l1..lN], body)`: recScope shared (lN = `local(0)`)
- `Arm(tag, N, body)`: N fields, field[N-1] = `local(0)`
- `While(cond, body)` / `Seq(terms)`: inherit enclosing scope (no new bindings)

### TCO

Tail `App` emits `$tco(fn,[args])` (a `{$k,  $a}` thunk). Non-tail emits `$c(fn,[args])`
which drives the trampoline loop until a non-thunk is returned.

### Entry value

Like `Main.scala out()`: Unit (null) is silent; other values printed with `$show()`.

### Conformance

All 5 `v2/conformance/*.coreir` fixtures match `ssc run-ir` output:
- fact=120, tco=500000500000, letrec=true, thunk=42, map=Cons(2,Cons(4,Cons(6,Nil)))

Tested kc examples (all match VM): hello, fact, strcat, list, fold, str, substr,
match, casecls, opt, while, lambda, return, block.
TCO: 100k-deep tail recursion produces 5000050000 without stack overflow.
