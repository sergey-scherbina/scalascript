# D2 — F runner emits structural Data directly (drop `#coreir.decode` / `ssc.Reader`)

Sergiy chose **D2** for the flip blocker `f-native-out-of-corpus-smoke-regressions` ③.2 (BUGS.md): under
F-as-default, `ssc run --native` class-loads the Java `ssc.Reader` because the F runner round-trips F's
emitted IR **text** through `#coreir.decode` (`IrToData.program(ssc.Reader.parseProgram(s))`). The
`v21-native-plugin-boundary` / `v21-plugin-backend-isolation` smokes forbid loading `ssc.Reader` → RED
under the re-flip. Legacy loads **zero** Reader. D2 = the F runner produces the `IrProg` Data tree
**directly**, without a text→decode round-trip, so zero `ssc.Reader` at native runtime.

## Why legacy is Reader-free and F is not (measured)
- Legacy runner `v2/bin/ssc1-run.ssc0:651`: `let ir = lowerProg(allStmts) in NativeCompilation(ir, …)` —
  `lowerProg` (ssc1-lower.ssc0:5542) builds the `IrProg`/`IrDef` Data **in-process** (Data constructors,
  no text). No `#coreir.decode`, no `ssc.Reader`.
- F runner `v2/bin/ssc1-run-fsub.ssc0:680`: `#coreir.decode(#coreir.eval(lowerProg(fProg)))`. `#coreir.eval`
  runs F0 (F's compiler), whose `compile(userSrc)` emits the user program IR as **canonical TEXT**;
  `#coreir.decode(text)` = `IrToData.program(Reader.parseProgram(text))` parses it → loads `ssc.Reader`.
  F emits text (not Data) because the X1 fixpoint gate byte-compares F's IR **text**.

## Approach — self-hosted S-expr → IrProg-Data reader in `fsub.ssc`
Keep F's `compile` emitting text (fixpoint unchanged). Add a self-hosted reader `irTextToData(text)` in
`specs/v2.2-p6.5-fsub.ssc` that parses the canonical IR text and builds **exactly** the `IrToData` Data
tree. The runner calls `compileToData = irTextToData(compile(src))` inside the eval'd F program, so
`#coreir.eval(lowerProg(fProg))` returns the Data directly — drop `#coreir.decode`. Because the reader
runs via `#coreir.eval` (kernel evaluator), it loads **no** `ssc.Reader` Java class. F's output is
FIRST-PARTY/trusted, so the reader needs a plain S-expr parse — NOT the untrusted-capsule fail-closed
validation (bounded depth, strict token checks) that `ssc.Reader` carries.

### Exact INPUT — canonical IR text (Writer, `v2/src/CoreIR.scala`)
```
prog   := (program (defs (def NAME TERM) …) (entry TERM))     ; empty defs -> (defs)
TERM   := (lit CONST) | (local I) | (global N) | (lam A TERM)
       | (app TERM TERM…) | (let (TERM…) TERM) | (letrec (TERM…) TERM)
       | (if TERM TERM TERM) | (ctor TAG TERM…) | (prim OP TERM…)
       | (while TERM TERM) | (seq TERM…)
       | (match TERM (ARM…) DFLT?)     ARM := (arm TAG ARITY TERM)   DFLT := (default TERM)
CONST  := unit | true | false | (int N) | (big N) | (float F) | (str "…") | (bytes HEX) | (bytes)
```
Notes: `app`/`ctor`/`prim`/`seq` are space-separated variadic; `let`/`letrec` wrap their binder list in
`( … )`; strings use the Writer's `strLit` escaping; bytes are `%02x` hex pairs.

### Exact OUTPUT — the `IrProg` Data (IrToData, `v2/src/Runtime.scala`) the reader must reproduce
```
IrProg(listV[IrDef], entryTERM)   IrDef(StrV name, TERM)
Lit->IrLit(CONST)  Local->IrLocal(IntV i)  Global->IrGlobal(StrV n)  Lam->IrLam(IntV a, b)
App->IrApp(fn, listV[args])  Let->IrLet(listV[rhs], b)  LetRec->IrLetRec(listV[lams], b)
If->IrIf(c,t,e)  Ctor->IrCtor(StrV tag, listV[fields])  Prim->IrPrim(StrV op, listV[args])
While->IrWhile(c,b)  Seq->IrSeq(listV[terms])
Match->IrMatch(scrut, listV[IrArm(StrV tag, IntV arity, body)], Some(term)|None)
CONST: unit->IrUnit  true/false->IrBool(BoolV)  (int N)->IrInt(IntV)  (big N)->IrBig(BigV)
       (float F)->IrFloat(FloatV)  (str s)->IrStr(StrV)  (bytes hex)->IrBytes(BytesV)
```
`listV = foldRight Cons Nil` (a `Cons`/`Nil` list). Leaf `IntV`/`StrV`/`BoolV`/`FloatV`/`BigV`/`BytesV`
are F's natural scalar values, so they fall out of parsing the atoms.

### Runner wiring (`v2/bin/ssc1-run-fsub.ssc0`)
`sscFsubIr`: replace `#coreir.decode(#coreir.eval(lowerProg(fProg)))` with
`#coreir.eval(lowerProg(fProgData))` where `fProgData` appends `irTextToData(compile(userSrc,dq,bs))`
instead of bare `compile(...)`. Confirm no other `#coreir.decode` remains on the F native path.

## IMPLEMENTATION AS LANDED (2026-07-23, revised from the scoped plan — measurement drove two changes)

**Revision 1 — the reader lives in the ssc0 RUNNER, not `fsub.ssc`.** The scoped plan put `irTextToData`
in `specs/v2.2-p6.5-fsub.ssc` and wrapped `callExpr`. That is **unsafe**: F's lexer DROPS `#` (`opCode`
35 = 0 → skipped), so `#str->f`/`#str->big`/`#str->utf8` would mis-lex under the X1 fixpoint (F compiling
its own source), and **pure F cannot parse a float from a string** (F treats floats as verbatim source
text; there is no float-from-string in the subset). So `irTextToData` is written in the **ssc0 runner**
`v2/bin/ssc1-run-fsub.ssc0` (ssc0 has full `#prim` access), and `sscFsubIr` becomes
`irTextToData(#coreir.eval(lowerProg(fProg)))` with `callExpr` UNCHANGED (`compile(...)`). Effects: F's
`compile` still emits text ⇒ X1 fixpoint **byte-identical** (`fsub.ssc` is not touched at all); the reader
runs in the runner's own kernel evaluation ⇒ loads **no** `ssc.Reader`; ssc0 capitalized-ctor application
builds `DataV` byte-identical to `IrToData` (verified via `#__eq__`). Notes on leaves: `#str->i`/`#str->f`/
`#str->big` return `Some/None` (unwrapped); `(bytes)` empty → `#str->utf8("")`; `(bytes <hex>)` non-empty is
an ASCII-only total-function path that **never occurs** in F output (F's front has no byte-literal syntax);
`(float nan|inf|-inf)` never occurs either (not `.ssc` source syntax). `-` has no ssc0 operator token, so
negative ints are read purely from the `"-N"` atom via `#str->i` (no source minus).

**Revision 2 — RunNativeV2 `Reader.validate` is a SECOND Reader source (was mislabelled a "red herring").**
MEASURED: removing `#coreir.decode` alone still loaded `ssc.Reader` **12×** — because the F4a delegate-
fallback pre-check `RunNativeV2:101` calls `_root_.ssc.Reader.validate(s.program)`, and that class-loads
`ssc.Reader` too. (BUGS.md ③.2 tested each source in isolation and, finding each alone insufficient,
wrongly concluded validate was a red herring — but BOTH must go: decode-only → 12×, validate-only → ~24×,
both → 0.) Fix: a Reader-free `validateNoReader` in `RunNativeV2` (v1/tools/cli only — NOT `frontIsF`,
NO `v2/src` change), a faithful port of `Reader.validate` (unbound global / out-of-range local / non-lam
letrec) so the fallback fires identically. Result (MEASURED, `-verbose:class`): **zero** `ssc.Reader`
under `SSC_FRONT=F` on both a direct-lower program and a fallback program, == legacy's zero.

## Verification (gates)
1. **Correctness:** `irTextToData(t)` output == `#coreir.decode(t)` output for a spread of programs
   (compare the Data trees). This is the exact-equivalence check.
2. **Isolation smokes GREEN under F:** `v21-native-plugin-boundary-smoke.sh` + `v21-plugin-backend-isolation-smoke.sh`
   — `-verbose:class` shows **zero** `ssc.Reader` under an F-default `ssc run --native`.
3. **No regression:** every `tests/e2e/v21-native-*` / `v21-self-hosted-*` smoke A/B green (F == legacy);
   X1 fixpoint byte-identical (F text output unchanged); semantic 248/248; dualrun 45/45.

## Coordination
`opus-orch` owns the flip (`v2-f4-flip`) and is active in `v2-f5c-removal` (KERNEL `v2/src` removal — does
NOT touch the F runner / decode / `fsub.ssc`, so no direct collision). `fsub.ssc` is also touched by the
F5b / f-stmt / f-string-literal lanes — rebase-and-coordinate. D3 (reimplement `#coreir.decode` without
`ssc.Reader` in the kernel) was rejected (risks the fail-closed guarantee); D1 (scope the guard) was the
lighter alternative Sergiy passed over in favour of this purest-isolation D2.

## Status — DONE (2026-07-23)
- [x] Scoped: exact input (Writer) + output (IrToData) formats captured; F built; verification defined.
- [x] Implement `irTextToData` (S-expr tokenizer + recursive term/const parser + Data build) — in the ssc0
      RUNNER `v2/bin/ssc1-run-fsub.ssc0` (see Revision 1), not `fsub.ssc`.
- [x] Wire the runner to drop `#coreir.decode` (`irTextToData(#coreir.eval(lowerProg(fProg)))`).
- [x] Make the F4a fallback pre-check Reader-free (`validateNoReader` in RunNativeV2; see Revision 2).
- [x] Verify:
  - Correctness: `#__eq__(irTextToData(t), #coreir.decode(t))` EQ on 28 synthetic node/const forms (neg int,
    i64-max, escaped/control strings, big, empty+ASCII bytes, match ±default, all terms) AND on real IR
    (a real program + the 407 KB runner IR itself, no stack overflow).
  - Isolation: MEASURED **zero** `ssc.Reader` under `SSC_FRONT=F` (direct + fallback programs), == legacy.
    `v21-native-plugin-boundary-smoke` + `v21-plugin-backend-isolation-smoke` PASS under F AND legacy.
  - No regression: X1 fixpoint byte-identical (405,396 B); semantic 248/248; dualrun (see run); v21-native /
    v21-self-hosted smoke family A/B (F == legacy).
