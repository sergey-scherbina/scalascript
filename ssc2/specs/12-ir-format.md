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

name     := SYMBOL        -- global def name and surface variable symbol (debug only for locals)
tag      := SYMBOL        -- constructor tag
op       := SYMBOL        -- primitive op, e.g. i.add, io.print, coreir.encode
```

### Tokens

- `SYMBOL` := `[A-Za-z_][A-Za-z0-9_.]*` — dots allowed so `i.add`, `io.print`, `map.get`
  are single tokens. Tags are conventionally capitalized but the kernel does not require it.
- `NAT` := `0` | `[1-9][0-9]*` — no leading zeros.
- `INT` := `-?` `NAT` — `-0` is not canonical; zero is `0`.
- `FLOAT` := canonical shortest round-tripping decimal of the IEEE-754 value (the unique
  shortest string that parses back to the same bits), always containing a `.` or an
  exponent; specials are exactly `nan`, `inf`, `-inf`. Negative zero is `-0.0`.
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
