# 12 — Core IR serialization format

> Status: **v1**. The on-disk / on-wire form of Core IR ([`10-core-ir.md`](10-core-ir.md)).
> This is what `coreir.encode` emits and `coreir.decode` / the kernel loader read. The
> format is **canonical**: a given Core IR program has exactly one byte representation, so
> the fixpoint/diff test (`seed(sscc) == sscc(sscc)`) is a plain byte comparison.

## Choice: canonical S-expressions (text)

v1 uses a **canonical S-expression text** encoding — not binary. Rationale:

- **Human-readable and git-diffable.** Conformance fixtures *are* the format; IR is
  reviewable in PRs; debugging is reading.
- **Canonical with a strict printer.** With de Bruijn locals (so α-equivalent terms print
  identically) plus the canonicalization rules below, the encoding is unique. That is all
  the fixpoint test needs.
- **The reader is not a language parser.** It is a tiny, fixed S-expr tokenizer — part of
  the IR *loader*, distinct from the surface Markdown+Scala parser (decision D3 forbids the
  *surface* parser in the kernel, not a trivial IR reader).

A compact binary encoding may be added later as `v2-bin` for size/speed; the text form
stays the canonical interchange and test format. Out of scope for v1.

## Grammar

ASCII. `( … )` are literal parens. `*` is zero-or-more, `+` one-or-more. A single ASCII
space (`U+0020`) separates adjacent tokens. No other whitespace appears in canonical form.

```
program  := "(program " defs " " entry ")"
defs     := "(defs" ( " " def )* ")"
def      := "(def " name " " term ")"
entry    := "(entry " term ")"

term     := lit | local | global | lam | app | let | letrec | if | ctor | match | prim
          | while | seq                  -- optimization nodes; see 10-core-ir.md §3.1
lit      := "(lit " const ")"
const    := "unit" | "true" | "false"
          | "(int "   INT   ")"          -- 64-bit signed, wrapping
          | "(big "   INT   ")"          -- arbitrary precision
          | "(float " FLOAT ")"          -- IEEE-754 double
          | "(str "   STRING ")"
          | "(bytes " HEX   ")"
local    := "(local " NAT ")"            -- de Bruijn index; 0 = innermost (last binder)
global   := "(global " name ")"
lam      := "(lam " NAT " " term ")"     -- arity (0 allowed = thunk), body
app      := "(app " term ( " " term )* ")"     -- fn then args; zero args = thunk force
let      := "(let (" term ( " " term )* ") " term ")"   -- 1+ sequential rhs, then body
letrec   := "(letrec (" lam ( " " lam )* ") " term ")"  -- 1+ lambdas, then body
if       := "(if " term " " term " " term ")"
ctor     := "(ctor " tag ( " " term )* ")"
match    := "(match " term " (" arm ( " " arm )* ")" default? ")"
arm      := "(arm " tag " " NAT " " term ")"   -- tag, field-arity, body
default  := " (default " term ")"
prim     := "(prim " op ( " " term )* ")"
while    := "(while " term " " term ")"        -- cond, body; value is Unit (10-core-ir.md §3.1)
seq      := "(seq" ( " " term )* ")"           -- evaluate in order, value = last (§3.1)

name     := SYMBOL        -- global def name and surface variable symbol (debug only for locals)
tag      := SYMBOL        -- constructor tag
op       := SYMBOL        -- primitive op, e.g. i.add, io.print, coreir.encode
```

**`while` and `seq` are part of the canonical form** — reconciled 2026-07-16
(`coreir-canonical-contract-reconcile`). They were absent from this grammar while the canonical
`Reader` accepted them and the canonical `Writer` emitted them; the omission was contract drift, not
a restriction. They are semantics-preserving optimization nodes with exact desugarings — see
[`10-core-ir.md` §3.1](10-core-ir.md) for the equivalences and §3.2 for **the** pinned inventory
(13 nodes, 7 constants), which is mechanically enforced across this file, the code, and both codecs
by [`specs/coreir-inventory-gate.sh`](../../specs/coreir-inventory-gate.sh).

### Tokens

- `SYMBOL` := `[A-Za-z_][A-Za-z0-9_.]*` — dots allowed so `i.add`, `io.print`, `map.get`
  are single tokens. Tags are conventionally capitalized but the kernel does not require it.
- `NAT` := `0` | `[1-9][0-9]*` — no leading zeros.
- `INT` := `-?` `NAT` — `-0` is not canonical; zero is `0`.
- `FLOAT` := canonical shortest round-tripping decimal of the IEEE-754 value (the unique
  shortest string that parses back to the same bits), always containing a `.` or an
  exponent; specials are exactly `nan`, `inf`, `-inf`. Negative zero is `-0.0`.

  **The encoder must preserve IEEE-754 bit identity, `-0.0` included.** This is `Writer.floatLit`,
  and it is deliberately **not** `Writer.floatStr`: `floatStr` is the *user-visible* renderer
  (`f->str`, `.toString`, string concat, `Show`) where whole doubles collapse (`2.0` renders as
  `"2"`) for ssc 1.0 output parity. Until 2026-07-16 the encoder went through `floatStr`, so
  `-0.0` encoded as `(float 0)` and decoded back as `+0.0` — bit identity silently lost — and
  integral floats emitted as `(float 2)`, violating the "always containing a `.` or an exponent"
  rule above. Both are fixed and pinned by `specs/coreir-codec-vectors.sh`. Do not re-merge the
  two functions: their contracts genuinely differ.

  On the JVM the implementation is `Double.toString`, which is exactly this token on **JDK 19+**
  (JDK-4511638 made it the shortest round-tripping decimal, always with a `.` or an `E`, and
  `-0.0` prints as `-0.0`). Verified on the toolchain JDK (21). **The kernel requires JDK 19+**:
  on an older JDK `Double.toString` still round-trips but is not always shortest, so the canonical
  bytes would become host-dependent and the fixpoint diff would stop being decidable.

  **Reader leniency (deliberate):** the reader accepts the non-canonical integral spelling
  `(float 2)` as well as `(float 2.0)`. That is what let the encoder be corrected to the canonical
  form without breaking any existing fixture or persisted capsule.
- `STRING` := `"` … `"`, UTF-8, with escapes `\"` `\\` `\n` `\r` `\t` and `\uXXXX` (lower-
  case hex) for other control points. No other characters are escaped (canonical = minimal
  escaping).
- `HEX` := an even-length run of `[0-9a-f]` (lowercase), two hex digits per byte; empty
  allowed (`(bytes )` is the empty byte string — note the single trailing space is dropped:
  empty bytes is `(bytes )`→ canonical `(bytes)`; see "empties" below).

### Canonicalization rules (what makes it unique)

1. Exactly one `U+0020` between sibling tokens; none after `(` or before `)`.
2. Opcodes/keywords are lowercase as written above.
3. Numbers use the canonical `INT`/`FLOAT` forms above (no `+`, no leading zeros, shortest
   float).
4. Strings use minimal escaping; bytes use lowercase hex.
5. **Locals are de Bruijn indices** — α-equivalent programs are byte-identical. Any human
   binder names live only in a separate debug sidecar, never in this stream.
6. **Empties:** an empty argument/field list collapses the separator, e.g. `(ctor Nil)`,
   `(app f)` (a thunk force has no args — but note `(app f)` with zero args *is* the force;
   a plain reference is just the term), `(bytes)`. `defs`/`let`/`letrec`/`match` require at
   least one element by grammar (a program with no defs is `(defs)`).

**Reader leniency vs. writer strictness.** The encoder (`coreir.encode`) emits *only* the
canonical form above. The *reader* (loader / `coreir.decode`) is deliberately lenient so
hand-authored fixtures are pleasant: it accepts arbitrary inter-token whitespace
(newlines, indentation) and `;`-to-end-of-line comments. Reading then re-encoding any
accepted input yields the canonical bytes — `encode ∘ decode = canonicalize`. Comments and
layout are not preserved (they are not part of the term).

Leniency is about **layout**, never about **validity**: an accepted-but-malformed term is a bug,
not a feature. See "Bounded decoding" below.

**Validation — the reader fails CLOSED** (2026-07-17, `coreir-canonical-codec-hardening` **H5**).
Because the reader decodes **untrusted persisted capsules**, every token and every scope is
validated on decode; a term the canonical writer could never have produced is rejected with a
diagnostic naming the offending node, not silently accepted. Before H5 these all decoded *open*
(the program ran with wrong or out-of-bounds semantics and no diagnostic):

| Malformed input | Was (fail-open) | Now (fail-closed) |
|---|---|---|
| `(local -1)` | negative de Bruijn index → OOB `env` read | rejected: "not a canonical NAT" |
| `(local 5)` with < 6 binders in scope | reads a wrong / out-of-bounds `env` slot | rejected: "local index out of range" |
| `(lam +1 …)` / `(arm T -1 …)` | signed/negative arity accepted | rejected: "not a canonical NAT" |
| `(int +1)` / `(int 01)` | read as `1` | rejected: "not a canonical INT" |
| `(bytes abc)` (odd) / `(bytes +1)` (non-hex) | took 2 bytes / `Integer.parseInt` accepted the sign | rejected: "hex must be even length" / "non-hex digit" |
| `(letrec ((lit …)) …)` | non-`Lam` binding | rejected: "letrec binding must be a lam" |
| an unbound `(global g)` (esp. in a dead branch) | accepted; program ran clean | rejected: "unbound global" |

`NAT` = `0|[1-9][0-9]*`; `INT` = `-?`NAT (no `+`, no leading zeros, no `-0`); `HEX` = even-length
`[0-9a-fA-F]`. A `Global g` is **closed** iff `g` is a top-level `def` name or an `@`-named-arg cell
(the runtime's own resolve fallback; the kernel reader cannot see the plugin registry). The de
Bruijn scope check matches the evaluator exactly (`10-core-ir.md` §4). This validation backs both
`ssc run-ir` and the `coreir.decode` primitive. Pinned by `specs/coreir-codec-vectors.sh`
(rejection vectors: RED on the pre-H5 kernel, GREEN after).

## Bounded decoding (binding)

The reader is the entry point for **untrusted persisted capsules** (`control-interoperability.md`),
so it is an attack surface, not just a convenience. A decoder that fails *open* here is a security
defect. Two rules:

1. **Nesting is bounded.** The reader rejects input nested deeper than `Reader.MaxDepth`
   (default **1000**; override `-Dssc.coreir.maxDepth=N`) with a *diagnostic*.
   `StackOverflowError` is an `Error`, not a catchable failure — it is a crash, and on hostile
   input that is a denial of service. Measured before this bound existed (2026-07-16): a 300 KB
   **well-formed** capsule (`(seq (seq … (lit unit) …))` — nothing malformed, merely deep) killed
   the reader with `StackOverflowError` in `Reader$P.readAtom` at `-Xss1m`, the **Linux/CI default**
   main-thread stack. macOS defaults to 2m, which is exactly the asymmetry behind the 192-run CI red.
2. **The bound is generous, because real IR is shallow.** Measured: the 79,667 B self-hosted
   compiler's own IR (the X1 fixpoint) is depth **25**; the `.coreir` fixtures under `v2/conformance`
   are 6–12. 1000 is ~40× headroom over the deepest program the toolchain has ever produced.

Tested at `-Xss1m` on purpose by `specs/coreir-codec-vectors.sh` §"bounded decoding" — testing only
at the developer default is how a whole family passed on macs and StackOverflowError'd in CI.

**Known gap (`BUGS.md` → `coreir-compiler-unbounded-depth`):** bounding the reader is only the
codec's half. `Compiler.valuePositionsNeedEffectThreading` / `FastCode.tryFC` independently overflow
at ~depth 500 on `-Xss1m`, so the capsule path is not yet fully DoS-safe. Recorded, not silently
papered over.

## Semantics mapping

Each S-expr form maps 1:1 to a `10-core-ir.md` node; the loader builds the in-memory term
directly. Evaluation is defined entirely by `10-core-ir.md §4`; this file adds no
semantics, only surface bytes.

## Example (canonical, single line)

Factorial (`fact 5 ⇒ 120`), exactly as a `.coreir` file would contain it:

```
(program (defs (def fact (lam 1 (if (prim i.le (local 0) (lit (int 1))) (lit (int 1)) (prim i.mul (local 0) (app (global fact) (prim i.sub (local 0) (lit (int 1))))))))) (entry (app (global fact) (lit (int 5)))))
```

(Conformance fixtures may pretty-print across lines for readability; the **canonical** form
— what `coreir.encode` emits and the fixpoint test compares — is the single-line,
single-space rendering above. A fixture's pretty form and its canonical form must decode to
the same term.)

## Open / deferred

- **`v2-bin`** — a compact canonical binary (LEB128 indices, length-prefixed atoms,
  big-endian IEEE-754, canonical NaN). Add when size/parse-speed matters; text stays
  canonical-of-record.
- **String-constant pool** — dedupe repeated tags/symbols; a size optimization, deferred.
- **Source spans / debug sidecar** — a parallel, non-canonical stream mapping nodes back to
  surface positions for diagnostics. Never part of the canonical bytes.
