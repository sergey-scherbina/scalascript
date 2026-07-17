# 10 — Core IR

> Status: **FROZEN v1** (2026-06-25; inventory reconciled 2026-07-16). The keystone spec:
> everything in ssc 2.0 compiles to Core IR, and the Scala evaluator runs it. `ssc₀`, the seed,
> and every backend derive from this. The **node set (13 nodes) and the constant set (7)** are
> frozen and pinned in §3.2 — a change there is a **version bump** (`v2`) that must update the
> evaluator, the seed, and `12-ir-format.md` together. Decisions: D1–D8 in
> [`00-overview.md`](00-overview.md).
>
> **Reconciled 2026-07-16** (`coreir-canonical-contract-reconcile`): this header said "node set
> (11 nodes)", §3 said "`Seq a b` is dropped", and invariant 7 said "no loop node is needed" — while
> `v2/src/CoreIR.scala` had **13** nodes and both the canonical `Reader` and `Writer` round-tripped
> `While` and `Seq`. The prose was reconciled **to the code**, because the code is what the frozen
> fixpoint bytes and every live consumer already depend on; see §3.1 for why the two extra nodes are
> safe (each has an exact, semantics-preserving desugaring) and §3.2 for the one pinned inventory.
> `specs/coreir-inventory-gate.sh` now compares six sources on every run so this cannot rot again.
> The **value domain** is discussed in §2 — its "ten shapes" line needed its own reconciliation.

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
   structural comparison). **Multi-binders bind right-to-left: the *last* of a group is
   index `0`** (the curried reading `λx₁.….λxₙ` ⇒ `xₙ` is innermost) — so in `Lam n`/`Let`/a
   `match` arm of arity `k`, the last param / rhs / field is `Local 0` and the first is
   `Local (k-1)`; outer bindings shift up by `k` inside the binder. Top-level definitions
   are referenced by stable **name**
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
   needed for semantics** in Core IR.

   **Reconciled 2026-07-16:** this clause used to end "so **no loop node is needed**", which read
   as "there is no loop node". There *is* one — `While` (§3.1) — and there has been for some time.
   The guarantee itself is unchanged and still exactly as strong: a loop node is not *needed*,
   because `LetRec` + TCO already expresses every loop in constant stack. `While` is an
   **optimization** the lowerer may choose (it avoids a trampoline bounce per iteration), never a
   requirement for expressiveness, and it is strictly redundant with the `LetRec` form given in
   §3.1. Nothing about TCO is weakened, and §9 (no continuations in Core IR) is untouched.

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

**Ten *semantic* shapes — plus `Decimal`, reconciled 2026-07-16.** The evaluator
(`v2/src/Runtime.scala`) actually defines **14** `Value` subclasses. Measured, they split cleanly
into three groups, and only the first is the semantic value domain:

| Group | Members | Status |
|---|---|---|
| The ten above | `UnitV BoolV IntV BigV FloatV StrV BytesV DataV ClosV ForeignV` | the semantic value domain — as documented |
| Private representations of `Foreign` | `MapV`, `LongCellV`, `DoubleCellV` | **not** new shapes. `Foreign` is defined below as covering "hash map, growable array, mutable cell"; these are unboxed/specialized representations of exactly that (e.g. `LongCellV` avoids `IntV` boxing on `cell.set` in tight loops). Semantically indistinguishable from `Foreign`. |
| A genuine 11th shape | `DecimalV` | **real drift.** It is language-visible (11 `dec.*` primitives) with its own numeric equality (`PortableDecimal`), so "ten shapes" undercounts. |

**`Decimal` is deliberately not capsule-encodable.** There is no `Const` case for it (measured:
zero occurrences of `Decimal` in `CoreIR.scala`), so a decimal cannot appear as a *literal* in
canonical IR — programs compute decimals through `dec.*` over strings instead. That is why the
constant inventory is **7** (§3.2) while the value domain is 11: the two counts answer different
questions, and conflating them is what made "ten shapes" look right for so long. The **capsule
encoding** depends only on §3.2, which is the machine-checked one.

Notes on the ten:

- **Three numeric kinds, all primitive** (`Int`, `BigInt`, `Float`) — kept in the kernel
  rather than emulated in a library. By the "primitive only if unexpressible" rule (§5),
  they qualify: faithful arbitrary-precision arithmetic and exact IEEE-754 semantics
  genuinely cannot be expressed efficiently/correctly in-language over `Int`. They have no
  implicit coercions in the kernel — conversion is always an explicit primitive (§5); the
  outer type system decides the surface numeric tower.
  - **`Int`** is 64-bit two's-complement, wrapping (matches `ssc 1.0`'s `Int = Long`).
  - **`BigInt`** is arbitrary precision (host bignum).
  - **`Float`** is a 64-bit IEEE-754 double (NaN, ±∞, signed zero per IEEE-754).

  These three widths are not local to the Core IR — they are the **conformance requirement** for
  every backend, normatively stated with the surface spellings, the ABI declaration and the host
  carriers in [`specs/numeric-widths.md`](../../specs/numeric-widths.md) §2 (surface `Int` and
  `Long` both denote the 64-bit `Int` above; surface `Float` and `Double` both denote the 64-bit
  `Float` above). **A backend that truncates `Int` to 32 bits is non-conforming** — it is not a
  backend with a different `Int`.
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
- **Nullary `Lam`/`App` are legal and load-bearing.** `Lam 0 body` is a thunk; `App f []`
  forces it. This is the *entire* kernel mechanism behind by-name params, `lazy val`, and
  lazy streams (invariant 2) — laziness needs no dedicated node.

### 3.1 The two optimization nodes: `While` and `Seq`

**Reconciled 2026-07-16** (`coreir-canonical-contract-reconcile`). Earlier revisions of this
section said "`Seq a b` **is dropped**" and invariant 7 said "**no loop node is needed**". Both
statements were **contract drift**: `v2/src/CoreIR.scala` has carried `Term.While` and `Term.Seq`
for some time, the canonical `Reader` accepts the `while`/`seq` heads, and the canonical `Writer`
emits them — so they are, in fact, part of the canonical encoding. The prose was reconciled to the
code (not the reverse): removing them would move the frozen fixpoint bytes and break live
consumers, and they are semantically redundant, which is exactly what makes them safe.

Both are **semantics-preserving optimizations with an exact desugaring**, not new expressive power:

| Node | Meaning (exact equivalent) | Why it is kept |
|---|---|---|
| `While cond body` | the tail-recursive `LetRec [Lam 0 (If cond (Seq [body, App (Local 0) []]) (Lit CUnit))] (App (Local 0) [])` | runs as a host `while` loop — no trampoline bounce per iteration |
| `Seq [e₁ … eₙ]` | `Let [e₁] (… (Let [eₙ₋₁] eₙ))` with unused bindings | evaluates in sequence in the *same* env — no per-statement `Let` frame |

Consequences, binding:

- **They add no semantics.** Invariant 7 still holds in full: TCO is a kernel guarantee, and a
  loop node is **not needed** *for semantics* — the outer compiler may still lower surface `while`
  to tail-recursive `LetRec` and rely on constant stack. `While` is an *optimization* the lowerer
  may choose; it is never required for a program to be expressible.
- **`Seq` is not "dropped".** It is a distinct node with the `Let`-with-unused-binding meaning.
- **Tail positions (invariant 7) extend as follows:** the `body` of a `While` is **not** tail
  (the loop continues after it) and a `While`'s value is `Unit`; the **last** term of a `Seq` in
  tail position **is** tail, and every earlier term is not.
- **This is not a precedent for a continuation node.** §9 stands unchanged: no `call/cc`, no
  effect handlers, no `reset`/`shift` node in Core IR. `While`/`Seq` qualify only because each has
  an exact, semantics-preserving desugaring to nodes that already exist. A continuation node has
  none — that is the whole point of the distinction.

### 3.2 The pinned canonical inventory (machine-checked)

This block is **the** canonical node/constant inventory — one place, no second list. It is
**order-significant** and mechanically compared against six independent sources by
[`specs/coreir-inventory-gate.sh`](../../specs/coreir-inventory-gate.sh): this block, `enum Term`,
`enum Const`, the canonical `Reader`, the canonical `Writer`, and the `IrEncode`/`IrDecode`
Data-tree tags. **Editing this block without editing the code (or vice versa) fails the gate** —
which is precisely what did not happen while the "11 nodes" claim rotted.

Columns: `node|const` · Scala case in `CoreIR.scala` · canonical S-expr head/form
(`12-ir-format.md`) · Data-tree tag consumed by `coreir.encode` / produced by `coreir.decode`.

```coreir-inventory
# 13 nodes — 11 semantic (§3) + 2 optimization (§3.1)
node  Lit     lit     IrLit
node  Local   local   IrLocal
node  Global  global  IrGlobal
node  Lam     lam     IrLam
node  App     app     IrApp
node  Let     let     IrLet
node  LetRec  letrec  IrLetRec
node  If      if      IrIf
node  Ctor    ctor    IrCtor
node  Match   match   IrMatch
node  Prim    prim    IrPrim
node  While   while   IrWhile   # optimization (§3.1)
node  Seq     seq     IrSeq     # optimization (§3.1)

# 7 constants — the entire capsule-encodable constant surface
const CUnit   unit    IrUnit
const CBool   true    IrBool    # canonical form is `true` | `false`
const CInt    int     IrInt
const CBig    big     IrBig
const CFloat  float   IrFloat
const CStr    str     IrStr
const CBytes  bytes   IrBytes
```

**Counts, pinned:** **13 nodes** (11 semantic + 2 optimization) and **7 constants**. The header's
former "node set (11 nodes) are frozen" counted `Local`/`Global` separately and predated
`While`/`Seq`; 13 is the number the code has, and it is now the number the gate enforces.

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

   ρ' = (c_n :: … :: c_1 :: ρ)   where c_i = Clos(ρ', n_i, b_i)   -- last binding = index 0,
   Σ; ρ' ⊢ body ⇓ v                                              -- cyclic: each c_i sees all
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
(transcendentals such as `sin`/`cos`/`log`/`exp` live in the `Mira` prelude, not the kernel)

**Numeric conversions (explicit — the kernel has no implicit coercion):**
`i->big` · `big->i` (may overflow) · `i->f` · `f->i` (truncate toward zero) · `big->f` ·
`f->big` (truncate) · `i->str` · `big->str` · `f->str` · `str->i?` · `str->big?` · `str->f?`
(the `?` forms return `Data Option` — parse may fail)

**Boolean:** `not` (`and`/`or` are short-circuit ⇒ lowered to `If`, not primitives)

**String (Unicode, immutable; indexed by UTF-16 code units):**
`slen` (length in code units) · `sconcat` · `sslice s a b` · `scodeAt s i` →`Int` ·
`sfromCodes [Int]`→`Str` · `seq`→`Bool` · `scmp`→`Int` · `sindexOf`
(int/bigint/float ↔ string conversions live in the numeric-conversions group above)

**Bytes (immutable):**
`blen` · `bget b i`→`Int` · `bslice` · `bconcat` · `str->utf8`→`Bytes` ·
`utf8->str`→`Str`

**Data (generic):** `tagOf d`→`Str` · `arity d`→`Int` · `fieldAt d i`→`Value`
(Match is the normal destructurer; these help generic code)

**Map — `Foreign`, mutable hash map (keys and values are arbitrary `Value`s, compared
structurally by the host value representation):**
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
(`io.args` is the program's argv — the trailing args of `ssc run <file> ARGS...` /
`ssc run-ir <file> ARGS...`, as a `Cons`/`Nil` list of `Str`. Implemented; `io.print`/
`io.eprint` also implemented. The rest of the I/O group is deferred δ-widening.)

**Core IR (canonical, kernel-owned serialization):**
`coreir.encode v`→**`Str`** (serialize a `Data`-tree representation of a Core IR program to the
canonical form) · `coreir.eval v` (compile and run an IR `Data` tree directly) ·
`coreir.decode`→**not implemented** (see below).
These let `sscc` *emit* Core IR without reimplementing the format, and keep the format canonical
in exactly one place — which is what makes the fixpoint diff meaningful.

**Reconciled 2026-07-16** (`coreir-canonical-codec-hardening`), because this table promised two
things that were not true:

- **`coreir.encode` returns `Str`, not `Bytes`.** It always did (`Runtime.scala`:
  `case "coreir.encode" => a => StrV(IrEncode.program(a(0)))`). The canonical v1 format **is text**
  (`12-ir-format.md`: "canonical S-expressions (text)"), so `Str` is the honest type, and the
  signature was reconciled to it. *Rejected alternative:* changing the primitive to return `Bytes`
  — it would break every existing caller (`lib/ssct-emit.ssc0`, `bin/mirac.ssc0`, the P6.5 driver)
  and buy nothing, since UTF-8 bytes are one `str->utf8` away. If a binary encoding is ever wanted
  it is `v2-bin`, already scoped as deferred in `12-ir-format.md`.
- **`coreir.decode` is not registered as a primitive at all.** Only `coreir.encode` and
  `coreir.eval` exist. The kernel *can* read canonical text — that is `Reader`, used by
  `ssc run-ir` — but it is not exposed to `.ssc`, so `encode ∘ decode = canonicalize` (promised by
  `12-ir-format.md`) is currently not expressible from the language. Still open; tracked in
  `SPRINT.md` as `coreir-canonical-codec-hardening` **H4**. The status note below already said
  "Still deferred at the kernel level: `coreir.decode`" — the signature line above simply had not
  been kept in sync with it.

The reader that *does* exist is **bounded**: see `12-ir-format.md` §"Bounded decoding". It is the
entry point for untrusted capsules, so it rejects over-deep input with a diagnostic rather than a
`StackOverflowError`.

Design rules for the primitive set:

- **Maps / arrays / cells are primitives, not pure structures.** The compiler leans on
  them heavily and pure persistent versions are too slow on a tree-walking evaluator.
- **Add a primitive only when it cannot be written in `ssc₀`/ScalaScript.** Anything
  expressible in the language must be a library, not a primitive.
- The set is **frozen-ish**: growing it touches both the evaluator and the seed (decision
  D4 makes that a normal, expected maintenance action).

**Implementation status (`v2/src/Runtime.scala` `Prims.resolve`, 2026-06-26).** Implemented:
all `i.*`/`big.*`/`f.*` + numeric conversions; `not`; the string group; the bytes group;
the data-reflection group (`tagOf`/`arity`/`fieldAt`); `map.*`/`arr.*`/`cell.*` (Foreign,
mutable); I/O (`print`/`eprint`/`args`/`readFile`/`writeFile`/`env`/`exit`);
**`coreir.encode`** (2026-06-27 — reads an IR-as-`Data` tree built in ssc0 and emits the
canonical S-expr of §12; `IrEncode` in `Runtime.scala`; used by `lib/ssct-emit.ssc0` to close
the `.ssct → ir → run-ir` loop). `Option` results use `Some`/`None`; lists use `Cons`/`Nil`.
**Strings are UTF-16 code units** (O(1) indexing, matching JVM/JS) — `slen`/`scodeAt`/`sslice`/
`sfromCodes` index in code units, not code points (a deliberate relaxation of the original
"code points" wording for practical performance). Still deferred at the kernel level:
`coreir.decode`.

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
def map = Lam 2 (                            -- params (f, xs): xs = Local 0, f = Local 1
  Match (Local 0)                            -- match on xs
    [ ("Nil",  0, Ctor "Nil" [])
    , ("Cons", 2, Ctor "Cons"                -- Cons(h,t) binds h = Local 1, t = Local 0;
        [ App (Local 3) [Local 1]            --   f, xs shift +2 ⇒ f = Local 3.   f head
        , App (Global map) [Local 3, Local 0] ]) ]   -- map f tail
    Nothing)
```

## 8. Deferred / open (do not block the freeze)

Resolved this round (no longer open): **`BigInt` + `Float` are in the kernel** (§2);
**TCO is a kernel guarantee** (invariant 7); **evaluation is strict CBV, laziness is a
thunk library** (invariant 2).

Resolved after the freeze, without changing Core IR:

- **`mathx.*`** — shipped as pure `Mira` prelude code (`K33`-`K35`), not as kernel primitives.
- **Structural map keys** — the runtime `map.*` primitives already accept arbitrary `Value` keys;
  `lib/mapx.ssc0` and `lib/set.ssc0` build structural map/set helpers on top.
- **`hash.sha256`** — shipped as `lib/sha256.ssc0`, written in raw ssc0 over existing bitwise,
  byte, array, and string-building primitives.

Still open / deferred:

- **Surface evaluation default (strict vs lazy)** — a *surface-language* question, decided
  later in the outer compiler; **does not affect the kernel** (invariant 2): a CBV kernel
  hosts either via thunk lowering.

## 9. Explicitly NOT in Core IR

Types, the Markdown+Scala surface parser, name/import resolution, effect handlers /
continuations, actors, async, the JIT, target backends (JVM/JS/WASM/native), and the
standard library. All are outer concerns, written in ScalaScript and compiled to the
nodes above. If it can be expressed in the language, it does not belong here.
