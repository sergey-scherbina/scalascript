# 10 — Core IR

> Status: **design v0** (strawman to freeze). The keystone spec: everything in ssc 2.0
> compiles to Core IR, and the Scala evaluator runs it. `ssc₀`, the seed, and every
> backend derive from this. Once frozen, changes here are rare and ripple everywhere.

Core IR is the **untyped inner kernel language** ([overview](00-overview.md)). It is the
language's formal semantic contract: one definition, every backend agrees on it.

## 1. Invariants (binding)

1. **Untyped.** No type information at runtime. Types are checked and *erased* by the
   outer compiler before lowering. The evaluator trusts the IR.
2. **Strict, call-by-value, left-to-right.** Arguments and binding right-hand sides are
   fully evaluated before use. CBV is *forced* by the effect model: `Prim` performs I/O
   and mutates `Foreign` values directly (§5), and lazy-by-default + direct effects gives
   unpredictable effect order/occurrence. The kernel is not pure, so it is strict.
   **Laziness is a library, not a kernel feature** — and it needs *no new node*: a thunk
   is a nullary closure `Lam 0 body`, forced by `App thunk []`; `lazy val` / call-by-need
   is a memoizing thunk in a `Foreign` `cell`; by-name `=> T` lowers an argument to a thunk
   and each use site to a force; `LazyList` is a memo-thunk tail. The kernel's *evaluation
   strategy* and the *surface language's* strict-vs-lazy default are **separable**: a CBV
   kernel hosts a strict or a lazy surface via thunk lowering, so this choice does not
   constrain the surface (a lazy-by-default surface, if ever wanted, changes only lowering).
3. **Locals are de Bruijn indices; globals are named.** Local binders (λ params, `let`,
   `letrec`, `match` fields) are referenced by de Bruijn index — capture-free, and
   α-equivalence becomes *structural* equality (so the fixpoint/diff test is a plain
   structural comparison). Top-level definitions are referenced by stable **name**
   (robust across edits; good for incremental builds and the diff test). An optional
   human-readable name may be attached to any binder as a **debug annotation that the
   evaluator ignores and the canonical serialization excludes**.
4. **Exact-arity application.** `App` supplies exactly as many arguments as the callee's
   `Lam` arity. Currying / partial application is desugared by the outer compiler into
   explicit nested closures. The kernel has no partial application.
5. **Minimal control flow.** Branching is `If` (on a `Bool`) and `Match` (on tagged
   data); repetition is recursion (`LetRec` / recursive globals). There are **no
   continuations, no `call/cc`, no effect handlers** in Core IR. Algebraic effects are an
   *outer* concern: the compiler lowers them (e.g. to CPS over ordinary `Data`), or a
   primitive scheduler provides them. The kernel stays effect-node-free.
6. **Canonical, kernel-owned serialization.** There is exactly one canonical byte
   encoding of Core IR, owned by the kernel (exposed via the `coreir.*` primitives), so
   that "compile A vs compile B produced equal IR" is a meaningful, decidable check.
   Format detail: `specs/12-ir-format.md` (planned).
7. **Proper tail calls (TCO) — guaranteed.** A call in *tail position* runs in constant
   stack space (Scheme-style proper tail calls), so deep tail recursion never overflows.
   This is a **semantic guarantee**, not an optimization: the K1 evaluator implements it
   with an explicit control loop / trampoline rather than host (JVM) recursion for tail
   calls. Because the kernel guarantees it, the outer compiler may lower surface `while` /
   `for` loops to tail-recursive `LetRec` and rely on constant stack — so **no loop node is
   needed** in Core IR.

   **Tail positions** (where a sub-term inherits the enclosing function's continuation):
   the body of a `Lam`; both branches of an `If` in tail position; every `arm` body and the
   `default` of a `Match` in tail position; the `body` of a `Let`/`LetRec` in tail
   position; and an `App` in tail position (the call itself — its result *is* the caller's
   result). **Not** tail: any `Prim` argument, any `Ctor` field, an `App`'s function/argument
   sub-terms, a `Match`/`If` scrutinee, and `Let`/`LetRec` right-hand sides.

## 2. Value domain

The evaluator manipulates exactly these runtime value shapes:

```
Value ::= Unit
        | Bool   b                       -- true | false
        | Int    n                       -- 64-bit two's-complement, wrapping arithmetic
        | BigInt n                       -- arbitrary-precision integer
        | Float  f                       -- 64-bit IEEE-754 double
        | Str    s                       -- immutable Unicode (sequence of code points)
        | Bytes  b                       -- immutable byte array
        | Data   tag [Value]             -- tagged record: tuples, case classes, ADT
                                         --   variants, and lists (Cons/Nil) all live here
        | Clos   ρ arity body            -- closure: captured env ρ + λ arity + body term
        | Foreign h                      -- opaque host object created/consumed by
                                         --   primitives: hash map, growable array, mutable
                                         --   cell, file handle, …
```

Ten shapes. Notes:

- **Three numeric kinds, all primitive** (`Int`, `BigInt`, `Float`) — kept in the kernel
  rather than emulated in a library. By the "primitive only if unexpressible" rule (§5),
  they qualify: faithful arbitrary-precision arithmetic and exact IEEE-754 semantics
  genuinely cannot be expressed efficiently/correctly in-language over `Int`. They have no
  implicit coercions in the kernel — conversion is always an explicit primitive (§5); the
  outer type system decides the surface numeric tower.
  - **`Int`** is 64-bit two's-complement, wrapping (matches `ssc 1.0`'s `Int = Long`).
  - **`BigInt`** is arbitrary precision (host bignum).
  - **`Float`** is a 64-bit IEEE-754 double (NaN, ±∞, signed zero per IEEE-754).
- **`Data tag fields`** is the universal product/sum carrier. `tag` is a small symbol
  (interned string or integer). Tuples = `Data "Tuple" [...]`; a case class = `Data
  "Point" [x, y]`; an enum variant = `Data "Some" [v]`; lists = `Data "Cons" [h, t]` /
  `Data "Nil" []`. The kernel ascribes no meaning to tags beyond equality.
- **`Foreign`** is the escape hatch for things that must be mutable or host-backed for the
  compiler to be fast (maps, arrays, cells, I/O handles). They are produced and consumed
  only through `Prim`.

## 3. Terms (the node set)

Eleven nodes. Each line gives the abstract form; `e` ranges over terms.

```
Term ::= Lit    const                         -- Unit|Bool|Int|BigInt|Float|Str|Bytes literal
       | Var    ref                            -- ref = Local i (de Bruijn) | Global name
       | Lam    arity body                     -- closure of fixed arity
       | App    fn [arg]                        -- exact-arity call
       | Let    [rhs] body                      -- sequential (let*) bindings, then body
       | LetRec [lam] body                      -- mutually-recursive λ group, then body
       | If     cond then else                  -- branch on a Bool
       | Ctor   tag [field]                     -- build Data tag
       | Match  scrut [arm] (default?)          -- destructure Data by tag
       | Prim   op [arg]                         -- call a host primitive (the FFI escape)
arm  ::= (tag, arity, body)                     -- on Data(tag, fs) with |fs| = arity:
                                                --   bind fs as the next `arity` locals, eval body
```

Why this set (and the deliberate near-misses):

- **`Let` and `LetRec` are kept, not desugared.** `Let [r] body` is expressible as
  `App (Lam 1 body) [r]`, but keeping `Let` avoids a closure allocation per binding and
  keeps generated IR small; `LetRec` is *not* expressible without a fixpoint combinator
  (undesirable in a strict language), so it is primitive. The binding machinery they need
  (push a frame of N values) is shared.
- **`If` and `Match` are both kept.** `Bool` is a primitive value (not `Data`), so `If`
  tests it directly; `Match` destructures `Data`. Two trivial branch forms rather than
  encoding `Bool` as `Data` and unifying — cheaper and clearer.
- **No first-class primitives.** To pass a primitive as a value, wrap it:
  `Lam 1 (Prim op [Local 0])`. So `Prim` is only ever a call site.
- **`Seq a b` is dropped** — it is `Let [a] b` with an unused binding.
- **Nullary `Lam`/`App` are legal and load-bearing.** `Lam 0 body` is a thunk; `App f []`
  forces it. This is the *entire* kernel mechanism behind by-name params, `lazy val`, and
  lazy streams (invariant 2) — laziness needs no dedicated node.

## 4. Operational semantics (big-step)

Judgement `Σ; ρ ⊢ e ⇓ v`: under global environment `Σ` (name → value) and local
environment `ρ` (a stack of values; `ρ(i)` is the i-th from the top, `0` = most recent),
term `e` evaluates to value `v`. Evaluation is strict and deterministic; argument and
binding lists evaluate left to right.

```
            ─────────────────────────────                 (Lit)
            Σ; ρ ⊢ Lit c ⇓ const(c)

            ─────────────────────                          (Var-Local)
            Σ; ρ ⊢ Var(Local i) ⇓ ρ(i)

            ─────────────────────────                      (Var-Global)
            Σ; ρ ⊢ Var(Global g) ⇓ Σ(g)

            ───────────────────────────────────           (Lam)
            Σ; ρ ⊢ Lam n b ⇓ Clos(ρ, n, b)

   Σ;ρ ⊢ f ⇓ Clos(ρ', n, b)   k = n
   Σ;ρ ⊢ a_i ⇓ v_i   (i = 1..k, left→right)
   Σ; (v_n :: … :: v_1 :: ρ') ⊢ b ⇓ v
   ────────────────────────────────────────────          (App)   -- arity mismatch ⇒ error
            Σ; ρ ⊢ App f [a_1..a_k] ⇓ v

   Σ; ρ                 ⊢ r_1 ⇓ v_1
   Σ; (v_1 :: ρ)        ⊢ r_2 ⇓ v_2     …                 -- let* : r_i sees v_1..v_{i-1}
   Σ; (v_{n-1}::…::ρ)   ⊢ r_n ⇓ v_n
   Σ; (v_n :: … :: v_1 :: ρ) ⊢ body ⇓ v
   ────────────────────────────────────────────          (Let)
            Σ; ρ ⊢ Let [r_1..r_n] body ⇓ v

   ρ' = (c_1 :: … :: c_n :: ρ)   where c_i = Clos(ρ', n_i, b_i)   -- cyclic: each c_i sees all
   Σ; ρ' ⊢ body ⇓ v
   ────────────────────────────────────────────          (LetRec)  -- bindings must be Lam
            Σ; ρ ⊢ LetRec [Lam n_i b_i] body ⇓ v

   Σ;ρ ⊢ c ⇓ Bool true     Σ;ρ ⊢ t ⇓ v
   ────────────────────────────────────────              (If-T)
            Σ; ρ ⊢ If c t e ⇓ v
   Σ;ρ ⊢ c ⇓ Bool false    Σ;ρ ⊢ e ⇓ v
   ────────────────────────────────────────              (If-F)
            Σ; ρ ⊢ If c t e ⇓ v

   Σ;ρ ⊢ f_i ⇓ v_i   (i = 1..k, left→right)
   ────────────────────────────────────────────          (Ctor)
            Σ; ρ ⊢ Ctor t [f_1..f_k] ⇓ Data(t, [v_1..v_k])

   Σ;ρ ⊢ s ⇓ Data(t, [w_1..w_m])
   arm (t, m, b) ∈ arms       Σ; (w_m::…::w_1::ρ) ⊢ b ⇓ v
   ────────────────────────────────────────────          (Match)
            Σ; ρ ⊢ Match s arms d ⇓ v
   (no arm matches tag t with arity m) ⇒ evaluate default d, or error if absent

   Σ;ρ ⊢ a_i ⇓ v_i   (i = 1..k, left→right)   δ(op, [v_1..v_k]) = v
   ────────────────────────────────────────────          (Prim)
            Σ; ρ ⊢ Prim op [a_1..a_k] ⇓ v
```

`δ` is the primitive interpretation function (§5). It is the *only* place effects (I/O,
mutation of `Foreign` values) happen — every other rule is pure.

Errors (arity mismatch, no-matching-arm-without-default, primitive type error, division
by zero, …) abort evaluation with a diagnostic. The kernel does not attempt recovery; the
outer type checker is responsible for ruling these out for well-typed programs.

## 5. Primitives — the FFI boundary (`δ`)

The frozen set of host operations the kernel provides. All are **strict** and
**fixed-arity**. Most are pure; the side-effecting ones are flagged **[eff]**. This table
is the strawman to freeze; it splits into `specs/11-primitives.md` once stable.

**Int — 64-bit, wrapping (`i.*`):**
`i.add i.sub i.mul i.div i.mod i.neg` · bitwise `i.and i.or i.xor i.not i.shl i.shr i.ushr` ·
compare `i.eq i.lt i.le i.gt i.ge` → `Bool`

**BigInt — arbitrary precision (`big.*`):**
`big.add big.sub big.mul big.div big.mod big.neg` ·
compare `big.eq big.lt big.le big.gt big.ge` → `Bool`

**Float — IEEE-754 double (`f.*`):**
`f.add f.sub f.mul f.div f.neg` · `f.sqrt` · `f.floor f.ceil f.round f.trunc` ·
compare `f.eq f.lt f.le f.gt f.ge` → `Bool` (IEEE ordering; `NaN ≠ NaN`) · `f.isNaN f.isInf` → `Bool`
(other transcendentals — `sin`/`cos`/`log`/`exp` — deferred to a later `mathx.*` group, §8)

**Numeric conversions (explicit — the kernel has no implicit coercion):**
`i->big` · `big->i` (may overflow) · `i->f` · `f->i` (truncate toward zero) · `big->f` ·
`f->big` (truncate) · `i->str` · `big->str` · `f->str` · `str->i?` · `str->big?` · `str->f?`
(the `?` forms return `Data Option` — parse may fail)

**Boolean:** `not` (`and`/`or` are short-circuit ⇒ lowered to `If`, not primitives)

**String (Unicode, immutable):**
`slen` (length in code points) · `sconcat` · `sslice s a b` · `scodeAt s i` →`Int` ·
`sfromCodes [Int]`→`Str` · `seq`→`Bool` · `scmp`→`Int` · `sindexOf`
(int/bigint/float ↔ string conversions live in the numeric-conversions group above)

**Bytes (immutable):**
`blen` · `bget b i`→`Int` · `bslice` · `bconcat` · `str->utf8`→`Bytes` ·
`utf8->str`→`Str`

**Data (generic):** `tagOf d`→`Str` · `arity d`→`Int` · `fieldAt d i`→`Value`
(Match is the normal destructurer; these help generic code)

**Map — `Foreign`, mutable hash map (keys: `Str`|`Int`):**
`map.new` · `map.get m k`→`Data Option` · `map.put m k v` **[eff]** · `map.has`→`Bool` ·
`map.del` **[eff]** · `map.keys m`→list · `map.size`→`Int`

**Array — `Foreign`, growable mutable vector:**
`arr.new` · `arr.len` · `arr.get a i` · `arr.set a i v` **[eff]** · `arr.push a v`
**[eff]** · `arr.pop a` **[eff]** · `arr.slice`

**Cell — `Foreign`, single mutable reference:**
`cell.new v` · `cell.get c` · `cell.set c v` **[eff]**

**I/O — [eff] (the compiler's whole interaction with the world):**
`io.readFile path`→`Bytes` · `io.writeFile path bytes` · `io.print str` · `io.eprint str`
· `io.args`→list of `Str` · `io.env name`→`Data Option` · `io.exit code`

**Core IR (canonical, kernel-owned serialization):**
`coreir.encode v`→`Bytes` (serialize a `Data`-tree representation of a Core IR program to
the canonical byte form) · `coreir.decode bytes`→`Value` (inverse, for tooling).
These let `sscc` *emit* Core IR without reimplementing the byte format, and keep the
format canonical in exactly one place — which is what makes the fixpoint diff meaningful.

Design rules for the primitive set:

- **Maps / arrays / cells are primitives, not pure structures.** The compiler leans on
  them heavily and pure persistent versions are too slow on a tree-walking evaluator.
- **Add a primitive only when it cannot be written in `ssc₀`/ScalaScript.** Anything
  expressible in the language must be a library, not a primitive.
- The set is **frozen-ish**: growing it touches both the evaluator and the seed (decision
  D4 makes that a normal, expected maintenance action).

## 6. Program envelope

A compilation unit is a recursive group of top-level definitions plus an entry term:

```
Program ::= { defs:  [Def],          -- mutually recursive; each visible to all (+ entry)
              entry: Term }          -- the term to evaluate to run the program
Def     ::= { name:  Symbol,         -- global name; referenced via Var(Global name)
              body:  Term }          -- typically a Lam; value bodies must not form
                                     --   value-cycles (only λ-cycles are well-defined)
```

Loading builds `Σ` by creating a closure/value for each `def` in a shared global
environment (top-level `LetRec` semantics, keyed by name), then evaluates `entry`.

## 7. Conformance sketch (illustrative, not final syntax)

These are the kind of programs the K1 evaluator must run; they double as the smallest
conformance suite. Written here in a readable pseudo-form (the real input is serialized
Core IR per §1.6).

```
-- factorial
def fact = Lam 1 (
  If (Prim i.le [Local 0, Lit 1])
     (Lit 1)
     (Prim i.mul [Local 0, App (Global fact) [Prim i.sub [Local 0, Lit 1]]]))
entry = App (Global fact) [Lit 5]            -- ⇓ Int 120

-- list map  (list = Data "Cons" [h,t] | Data "Nil" [])
def map = Lam 2 (                            -- Local 0 = f, Local 1 = xs
  Match (Local 1)
    [ ("Nil",  0, Ctor "Nil" [])
    , ("Cons", 2, Ctor "Cons"                -- match binds head→Local0, tail→Local1
        [ App (Local 2) [Local 0]            -- f head        (Local 2 = f, shifted by 2)
        , App (Global map) [Local 2, Local 1] ]) ]   -- map f tail
    Nothing)
```

## 8. Deferred / open (do not block the freeze)

Resolved this round (no longer open): **`BigInt` + `Float` are in the kernel** (§2);
**TCO is a kernel guarantee** (invariant 7); **evaluation is strict CBV, laziness is a
thunk library** (invariant 2).

Still open / deferred:

- **`mathx.*`** — transcendental floats (`sin cos tan log exp pow atan2`, …). Deferred; a
  later primitive group, since IEEE-correct versions can't be written in-language.
- **Structural map keys** — keys are `Str`|`Int` for now; structural-value keys via a
  `hash`/`eq` primitive pair later if needed.
- **`hash.sha256 bytes`** — would make content-addressed IR and the fixpoint diff
  cheaper; tiny, optional, can land with K1.
- **Surface evaluation default (strict vs lazy)** — a *surface-language* question, decided
  later in the outer compiler; **does not affect the kernel** (invariant 2): a CBV kernel
  hosts either via thunk lowering.

## 9. Explicitly NOT in Core IR

Types, the Markdown+Scala surface parser, name/import resolution, effect handlers /
continuations, actors, async, the JIT, target backends (JVM/JS/WASM/native), and the
standard library. All are outer concerns, written in ScalaScript and compiled to the
nodes above. If it can be expressed in the language, it does not belong here.
