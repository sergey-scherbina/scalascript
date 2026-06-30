# 60 — v1.0-compat Frontend

> Status: **design** (2026-06-30). Implementation: KC1–KC8 in SPRINT.md.

## Goal

Run existing v1.0 `.ssc` files on the v2 kernel without modification. All v1.0
stdlib libraries that compile on the v2 kernel should produce identical results.

## Why this matters

- v1.0 has a large body of `.ssc` programs (std plugins, user code, examples).
- Once they run on v2, the v2 kernel is the runtime for the whole ecosystem.
- v2 backends (JS/Rust/WASM) become available to v1.0 code for free.

## Architecture

```
.ssc file
  └─ Markdown extractor (KC1)     — fence-tagged block list
       └─ (scalascript, src)
            └─ v1.0 Lexer (KC2)   — token stream
                 └─ v1.0 Parser (KC3) — v1.0 AST
                      └─ Type checker (KC5)   — typed AST
                      └─ Lowering (KC4+KC7)   — Core IR
                           └─ ssc VM / JS / Rust backends (existing)
```

All stages KC1–KC8 are written in **Lark** (the v2 typed surface language, formerly
ssct-hm). They are themselves `.lark` programs running on the v2 kernel.

## Phases

### KC1 — Markdown extractor

**Input:** `.ssc` file content (string).  
**Output:** `(Option YamlStr, List (FenceLang, SourceStr))`.

Reuses K51 parser combinators (`pChar`/`pStr`/`pSeq`/`pMany`/`pAlt`/`pMap`/`pInt`)
from the Lark prelude. Written in Lark.

Tasks:
- Parse optional YAML front-matter (up to first blank line or `#` heading).
- Extract fenced code blocks: ` ```<lang>\n<source>\n``` `.
- Handle nested fences (e.g., a ` ```ssc0` block inside a prose section).
- Return all `(lang, source)` pairs in document order.

Done-when: `check.sh` test on `examples/hello.ssc` (v1.0) extracts one
`("scalascript", "println(\"Hello, World!\")\n")` block.

### KC2 — v1.0 Lexer

**Input:** source string.  
**Output:** list of `Token` (tagged union).

Token kinds:
- Keywords: `def val var class trait object case given using import match if else
  for yield return type sealed abstract override extends with new`.
- Identifiers / operators: `x`, `+`, `::`, `=>`, `->`, `_`, `@`.
- Literals: integer, float, string (with escape sequences), boolean (`true`/`false`).
- Punctuation: `( ) [ ] { } , . : ; =`.
- Comments: `//` line and `/* */` block (discarded).

Written in Lark using K51 parser combinators. The same combinator library that
parsed arithmetic (`hm-arith-parser.hm`) is the foundation.

Done-when: `lex("def f(x: Int) = x + 1")` → correct token list, all types.

### KC3 — v1.0 Parser (functional subset first)

**Input:** token stream.  
**Output:** v1.0 AST (`Expr` tagged union).

AST nodes (functional subset — required for KC4):
```
TopDef   = Def(name, params, retType, body)
         | ValDef(name, type, body)
         | Import(path, selectors)
         | TypeAlias(name, typeParams, rhs)
         | CaseCls(name, typeParams, fields)   -- case class
         | SealedTrait(name, typeParams)        -- sealed trait header

Expr     = Lit(value)
         | Var(name)
         | App(fn, args)
         | Lam(params, body)                   -- fun(x: T) => body
         | Let(name, rhs, body)                -- val x = rhs; body
         | LetRec(name, rhs, body)             -- def f = ...; body
         | If(cond, then, else)
         | Match(scrutinee, arms)
         | Block(stmts)
         | Tuple(elems)
         | Select(expr, field)                 -- expr.field
         | New(cls, args)
         | StringInterp(parts)                 -- s"hello $name"
```

OOP nodes (KC7, deferred): `ClassDef`, `TraitDef`, `ObjectDef`, `GivenDef`, `Override`.

Done-when: parses all functional examples from `examples/` (factorial, fibonacci,
list operations, option chain).

### KC4 — Functional subset lowering to Core IR

Maps functional-subset AST directly to Core IR. No type inference required for this
phase (types are erased; v2 kernel is untyped).

| v1.0 AST node         | Core IR                                             |
|-----------------------|-----------------------------------------------------|
| `Def(f, ps, _, body)` | `LetRec(f, Lam(len(ps), lower(body)))`              |
| `ValDef(x, _, e)`     | `Let(x, lower(e))`                                  |
| `Var(x)`              | `Local(i)` (de Bruijn) or `Global(x)`               |
| `App(f, [a, b])`      | `App(App(lower(f), lower(a)), lower(b))` (curried)  |
| `Lam([x], body)`      | `Lam(1, lower(body))`                               |
| `If(c, t, e)`         | `If(lower(c), lower(t), lower(e))`                  |
| `Match(s, arms)`      | `Match(lower(s), lowerArms(arms))`                  |
| `Lit(n: Int)`         | `Lit(IrInt(n))`                                     |
| `Lit(s: String)`      | `Lit(IrStr(s))`                                     |
| `CaseCls(C, _, fs)`   | registers constructor `C` with arity `len(fs)`      |
| `StringInterp(parts)` | chain of `Prim("scatstr", [...])` (new prim, see §Intrinsics) |
| `Tuple(elems)`        | `Ctor("Tuple_N", [lower(e) for e in elems])`        |
| `Select(e, f)`        | method dispatch (see KC6 intrinsics map)            |
| `New(cls, args)`      | `Ctor(cls, [lower(a) for a in args])`               |

Name resolution: a `val x` binding inside a block scope → `Let(x, rhs, body)`.
Top-level `def`s become `Global` entries (registered before lowering starts).

Done-when: `println("hello")` → Core IR → `io.print` primitive → "hello" on stdout.

### KC5 — Type checker (functional subset)

HM-style inference for the functional subset, written in Lark.

Reuses the Algorithm W implementation from `lib/ssct-hm.ssc0` as a reference.
Key additions vs ssct-hm:
- Scala-like type syntax: `Int`, `String`, `List[A]`, `Option[A]`, `(A, B)`, `A => B`.
- Subtyping within sealed hierarchies: `sealed trait T; case class C() extends T`.
- Type aliases: `type Alias[A] = ...` → expand before unification.

Inference is optional for KC4 (lowering works without types). KC5 adds type checking
so that type errors in v1.0 code are caught at compile time.

Deferred (KC7/KC8 scope): class hierarchy subtyping, variance, implicit resolution.

Done-when: type-checks the functional examples; rejects type errors with clear messages.

### KC6 — Intrinsics mapping

v1.0 methods that lower to Core IR primitives.

**String intrinsics** (all available in v2 kernel today):
| v1.0 call          | Core IR primitive            |
|--------------------|------------------------------|
| `s.length`         | `Prim("slen", [s])`           |
| `s.charAt(i)`      | `Prim("scodeAt", [s, i])`     |
| `s.substring(i,j)` | `Prim("sslice", [s, i, j])`   |
| `s.indexOf(t)`     | `Prim("sindexOf", [s, t])`    |
| `s + t`            | `Prim("scatstr", [s, t])`  ← needs new prim |
| `s == t`           | `Prim("seq", [s, t])`         |
| `s.compareTo(t)`   | `Prim("scmp", [s, t])`        |
| `s"hello $x"`      | chain of `scatstr`            |
| `s.toInt`          | `Prim("str->i", [s])`      ← needs new prim |
| `s.toDouble`       | `Prim("str->f", [s])`      ← needs new prim |

**Int intrinsics** (all in kernel):
`+`, `-`, `*`, `/`, `%`, `&`, `|`, `^`, `~`, `<<`, `>>`, `>>>`, `==`, `<`, `<=`, `>`, `>=`
→ `Prim("i.add", ...)` etc. Already available.

**IO**:
| v1.0 call           | Core IR primitive           |
|---------------------|-----------------------------|
| `println(x)`        | `Prim("io.print", [x])`     |
| `print(x)`          | `Prim("io.print", [x])`     |  
| `Console.err(x)`    | `Prim("io.eprint", [x])`    |
| `sys.exit(n)`       | `Prim("io.exit", [n])`      |
| `readFile(path)`    | `Prim("io.readFile", [path])` |
| `writeFile(p, b)`   | `Prim("io.writeFile", [p, b])` |

**New primitives needed** (extend `v2/src/Runtime.scala`):
| prim name    | semantics                              | priority |
|--------------|----------------------------------------|----------|
| `scatstr`    | string concatenation (s + t)           | KC4 P1   |
| `str->i`     | `"42".toInt` (may throw)               | KC6 P1   |
| `str->f`     | `"3.14".toDouble` (may throw)          | KC6 P1   |
| `slen`       | string length                          | KC4 P1 (check if missing) |
| `io.stdin`   | read one line from stdin               | KC6 P2   |
| `io.http`    | HTTP GET/POST (plugin-level, deferred) | plugin   |

**Collection intrinsics** — `List`, `Option`, `Map`, `Set` in v1.0 are built on
the Scala stdlib. In v2, the functional subset is covered by `lib/list.ssc0` etc.
The lowering translates `List(1, 2, 3)` → ssc0 `cons(1, cons(2, cons(3, nil)))`.

### KC7 — OOP lowering (classes / traits / objects)

**Hardest phase. Deferred until KC4–KC6 are solid.**

Strategy: structural records + vtable dicts (same as qualified types in Lark).

| v1.0 construct           | v2 lowering                                         |
|--------------------------|-----------------------------------------------------|
| `case class C(a: A, b: B)` | ADT `Ctor("C", [a, b])` — already done in KC4   |
| `class C(x: T) { def m = ... }` | ADT `Ctor("C_inst", [x])` + dict `"C" → {m: fn}` |
| `trait T { def m: R }`   | dict type `{m: R}` (like a typeclass)               |
| `object O { val x = ... }` | single global `DataV("O", [x, ...])`             |
| `C extends T with T2`    | dict merge: `{m_from_T: ..., m_from_T2: ...}`      |
| `override def m = ...`   | replace method in dict at construction site         |
| `new C(args)` : `T`      | `Ctor("C", args)` + dict selection at call site    |
| `val t: T = new C()`     | coerce: `t` receives the `C` dict downcast to `T`  |

Subtyping is structural at the dict level: if `C` provides all methods of `T`, then
`C <: T` holds. No nominal subtype hierarchy needed in the kernel.

### KC8 — given/using (implicits)

v1.0's `given`/`using` maps naturally to Lark's qualified-type dict passing.

| v1.0                          | v2 lowering                                      |
|-------------------------------|--------------------------------------------------|
| `given Show[Int] = ...`       | `val showInt: ShowDict = { show: ... }`          |
| `def f[A: Show](x: A)`        | `def f(showA: ShowDict)(x: A)` (dict prepended)  |
| `f(42)` (implicit resolution) | type checker resolves `showA = showInt`; inserts |
| `using` parameter             | explicit dict argument (same as above)           |

The resolution algorithm is the same as Lark's `closeDeps` + instance lookup.

### Scope and non-goals for v1.0-compat

**In scope (KC1–KC8):**
- Functional programs: def/val/match/if/case-class/sealed-trait/recursion/HOF
- String interpolation
- Option/List/Either (via ssc0 stdlib equivalents)
- Basic IO (println, readFile, writeFile)
- Type classes via given/using (KC8)
- Simple OOP (case class, object, one-level inheritance) (KC7)

**Deferred / out of scope:**
- Markdown toolkit / content helpers (`contentToolkit`, `ui`, `serve`)
- HTTP plugin, database plugin, Spark plugin, actor clustering
- `@annotation` processing
- Scala-style `for` comprehensions with generators (deferred, requires desugaring)
- Dependent types, path-dependent types
- Existentials, wildcard `_` types
- Complex variance (covariant/contravariant type parameters)
- Reflection (`getClass`, `isInstanceOf` beyond `match`)

The v2 kernel has no plugin system. Plugins that wrap JVM code need to be
reimplemented as ssc0/Lark programs over the v2 primitive set, or deferred.

## Intrinsics strategy

**Principle**: keep the kernel minimal. Every v1.0 intrinsic that can be expressed
as an ssc0 library should be. Only add kernel primitives for things expressly NOT
expressible in ssc0 (I/O, FFI, raw arithmetic).

Current kernel primitives cover: Int/Float/BigInt arithmetic, string ops, bytes,
mutable map/array/cell, basic IO (print/file/env/exit), Core IR encoding.

New kernel primitives needed: `scatstr` (string concat), `str->i`/`str->f` (parse),
`io.stdin` (readline). Everything else is a library.

## File formats

| Extension  | Contents                              | Pipeline             |
|------------|---------------------------------------|----------------------|
| `.ssc`     | Markdown with fence blocks            | Markdown extractor   |
| `.lark`    | Lark source (bare, no Markdown)       | Lark compiler direct |
| `.hm`      | Lark source (legacy name, still works)| Lark compiler direct |
| `.ssc0`    | ssc₀ source                           | ssc compile          |
| `.coreir`  | Core IR S-expr                        | ssc run-ir           |
| `.irbin`   | Core IR compact binary (K40)          | ssc run-irbin        |

## Conformance

Each KC phase adds conformance tests to `conformance/check.sh` under a `# KC<N>`
section. The v1.0 examples directory is the acceptance corpus. Gate: all currently-
passing v1.0 functional examples must pass on the v2 kernel.
