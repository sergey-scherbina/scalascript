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

## Status
- [x] Scoped: exact input (Writer) + output (IrToData) formats captured; F built; verification defined.
- [ ] Implement `irTextToData` (S-expr tokenizer + recursive parser + Data build) in `fsub.ssc`.
- [ ] Wire the runner to drop `#coreir.decode`.
- [ ] Verify (correctness equivalence + isolation smokes + full A/B + fixpoint/semantic/dualrun).
