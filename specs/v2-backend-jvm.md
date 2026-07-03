# v2 JVM Backend — Core IR → Scala 3 Source Generator

**Slug:** `v2-backend-jvm`
**Status:** implemented 2026-07-03

## Goal

Generate a self-contained Scala 3 source file from Core IR (text S-expression format)
that, when compiled with `scalac` and run with `scala`, produces the same output as
running the same program through the v2 VM (`ssc run-ir`).

## Scope

- **In scope:** all Core IR constructs (Lit, Local, Global, Lam, App, Let, LetRec, If,
  Ctor, Match, Prim, While, Seq) and all primitives that appear in the bench corpus.
- **Out of scope:** WASM/JS targets; multi-file compilation.
- **TCO:** global self-tail-recursive `Def`s and single-lam `LetRec`s with a self-tail-call
  are emitted as `@tailrec def` (Scala compiler enforces tail-call elimination).
  Mutual recursion (`LetRec` with 2+ lams that call each other) falls back to closure vars
  (no trampoline — suitable for shallow mutual recursion like even/odd).

## Design

### Value representation

```
type V = Any      // all values are opaque Any
type Fn = Array[V] => V   // universal closure type
```

- **Unit** → `()`
- **Bool** → `Boolean`
- **Int** → `Long`
- **BigInt** → `scala.math.BigInt`
- **Float** → `Double`
- **String** → `String`
- **Bytes** → `Vector[Byte]`
- **ADT** → `(String, Array[V])` — a 2-tuple (tag, fields)
- **Closure** → `Array[V] => V`
- **Cell** → `Array[V]` (1-element mutable ref)
- **LongCell** → `Array[Long]` (1-element, avoids boxing)
- **Map** → `scala.collection.mutable.HashMap[V, V]`
- **ArrayBuffer** → `scala.collection.mutable.ArrayBuffer[V]`

### De Bruijn → named variables

Variables are named with a prefix and a fresh counter suffix:
- `Lam(n, body)` params: `p0_$d`, `p1_$d`, ... `p{n-1}_$d` (p0 = first, p{n-1} = last = Local(0))
- `Let` bindings: `l0_$d`, `l1_$d`, ... (sequential; l{n-1} = Local(0) in body)
- `LetRec` bindings: `r0_$d`, `r1_$d`, ... (r{N-1} = Local(0) in body)
- `Arm` fields: `f0_$d`, `f1_$d`, ... (f{n-1} = Local(0) in arm body)

Scope is a `List[String]` where `scope(i)` = name for `Local(i)`.

### Top-level def generation

Two-pass, following the VM:
1. Lambda defs (`Def(name, Lam(...))`) → Scala `val`s holding closures.
   Bodies compiled with scope = empty outer scope (defs are global).
2. Value defs (`Def(name, other)`) → Scala `val`s holding evaluated results.

All defs are `lazy val` to handle forward references correctly.

### Closures and mutual recursion (LetRec)

LetRec is generated as `var` bindings initialized to `null` then populated, so the
lambdas close over the var references (set before any lambda is called):

```scala
var r0_$d: V = null.asInstanceOf[V]
var r1_$d: V = null.asInstanceOf[V]
r0_$d = ((_a: Array[V]) => { ... uses r1_$d ... }): V
r1_$d = ((_a: Array[V]) => { ... uses r0_$d ... }): V
// body uses r0_$d, r1_$d
```

LetRec lambda bodies see: params (innermost) + letrec vars + outer scope.

### Prim dispatch

Primitives are dispatched through an `R` object with `prim1`/`prim2`/`prim3`/`primN`
methods to avoid Array allocation on the common path.

## Files

- `v2/backend/jvm/JvmBackend.scala` — the generator (reads Core IR, writes Scala 3)
- `v2/backend/jvm/project.scala` — scala-cli config

## Testing

```bash
# Compile generator
scala-cli compile v2/backend/jvm/

# Compile a program to IR and run through backend
echo '(program (defs ...) (entry ...))' | scala-cli run v2/backend/jvm/ -- /dev/stdin > /tmp/out.scala
scalac /tmp/out.scala -d /tmp/out_cls && scala -cp /tmp/out_cls v2main
```

Test programs:
1. `v2/conformance/fact.coreir` — recursion, Int arithmetic
2. `v2/conformance/letrec.coreir` — mutual recursion (even/odd)
3. `v2/conformance/map.coreir` — ADT, pattern match, closures
4. Compiled `fib(10)` program — recursive, Int
5. Compiled `hello-world` — io.println

## Correctness invariants

1. `Local(i)` always resolves to the correct scope variable
2. LetRec closures see each other (via `var` boxing)
3. ADT fields accessed at correct indices: `fields(k)` for `Local(arity-1-k)` in arm body
4. `While` generates a Scala `while` loop (correct for non-recursive programs)
5. `Seq` evaluates all terms left to right, returns last
