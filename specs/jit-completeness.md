# VmCompiler completeness — expanding the JIT-compilable subset

Status: **in progress** (p1 landed 2026-06-05). Companion to
[`vm-jit-spec.md`](vm-jit-spec.md) (VM design) and
[`vm-jit-next.md`](vm-jit-next.md) (architecture roadmap).

## 1. Goal

`VmCompiler.compile` converts a `Value.FunV` to a `CompiledFn` (register
bytecode) that `JitRuntime` runs on `SscVm` instead of the tree-walker.
Today only a small integer/double subset compiles; everything else bails and
falls back to the interpreter. Each bail permanently disables that function in
`JitRuntime`.

The goal of this spec is to eliminate as many bail reasons as possible so that
more real-world functions get the SscVm fast path automatically, without any
user-visible API or language change.

## 2. Measuring progress

```bash
SSC_JIT_STATS=1 sbt "backendInterpreter/test" 2>&1 | grep -A20 "JIT miss"
```

**Baseline (2026-06-05, after p1 fix):** 310 functions disabled.

```
 201  call: no compilable target (closures / HOF)
  54  unsupported: Term.Select
  26  unsupported: Lit.String
  17  undefined: name 'inner'
   4  undefined: name '_VNODES_PER_NODE'
   3  unsupported: Term.Function
   2  unsupported: Lit.Null
   2  unsupported: stmt Defn.Def
```

## 3. What was landed in p1 (2026-06-05)

Commit `36b163`: `feat(jit): completeness p1 — Boolean, unary ops, qualified match patterns`

- **Bug fix:** `catch case b: Bail => record(reason); None` — the `;` ended
  the catch arm, making `compile()` always return `None`. Fixed to
  `{ record(reason); None }`. All 11 direct VmCompiler unit tests were broken
  by this silently since the JitMissStats commit.
- `Boolean` added to `intTypes`: Boolean params/returns compile as TInt (0/1).
- `Lit.Boolean(v)` → emit CONST 0/1.
- `Term.ApplyUnary`: `-x` (int: `0 - x`; double: `0.0 - x`) and `!x`
  (`EQI dst, ar, 0`).
- Qualified `Pat.Extract` (`case Shape.Circle(r) =>`): take rightmost name
  from `Term.Select(_, n)`.
- No-arg qualified pattern (`case Color.Red =>`): new `Term.Name` and
  `Term.Select(_, n)` branches in `emitCaseHeader` — ISTAG + JF, no field
  bindings.
- 7 new unit tests; 45 SscVmTest tests pass.

## 4. Planned slices

### p1b — Arity-0 functions ✓ Landed (2026-06-05)

**Shape:** any top-level or local `def` with no parameters:

```scala
def workload(): Long = ...
def answer(): Int = 42
```

**Implemented via the bytecode-JIT lane (not the register VM).** A zero-arg
FunV is dispatched through a new `JitRuntime.tryRun0` / `invokeBytecode0`,
hooked into `CallRuntime.callValue0`. Both bytecode backends had their
param-count gate relaxed from `!= 1 && != 2` to `> 2`, with new
`LongFn0`/`DoubleFn0` direct interfaces and `determineInterface(0)`.

The `VmCompiler` `arity < 1` guard was also removed (in the p2 commit) so
zero-param functions can compile as callees inside register-VM call graphs.
However, the top-level hot path (`tryRun0`) uses BytecodeJit only — SscVm
does not handle 0-arg dispatch at the boundary.

To make the `nested-loop` body compile, the while-body emitters in both
backends (Javac `walkStatAsVoid`/`walkWhileAsStmt`, ASM
`emitStatAsVoid`/`emitWhileAsStmt`) were generalized to thread bindings
across body statements, so an inner `var` declaration and a nested `while`
now compile.

**Impact:** `nested-loop` 11.1 ms → 0.26 ms (ssc) / 11.8 ms → 0.27 ms (asm),
result 249500250000 verified on both backends.

**Behavior:**
- [x] `def workload(): Long = ...` (nested while) compiles and runs correctly
- [x] no regression on arity-1..8 functions (backendInterpreter suite: 184/185,
      lone failure is the pre-existing mutual-TCO Boolean-as-1 JIT bug)

### p2 — `Term.Select` standalone field access ✓ Landed (2026-06-05)

**Shape:** `obj.field` used as an expression outside of a `match` pattern.
Examples:

```scala
def getRadius(c: Circle): Int = c.r
def distSq(p: Point): Int = p.x * p.x + p.y * p.y
```

In scala.meta this is `Term.Select(obj: Term, field: Term.Name)`.

**Implemented via `refTypeName` map + `metaFor` lookup.** A new
`mutable.HashMap[Int, String]` (`refTypeName`) in `Builder` tracks the
declared type name for each TRef register. It is populated:
- For TRef function parameters (from `fn.paramTypes`).
- For GETFR match bindings (from `Pat.Extract` field types).

The new `Term.Select` case in `compileInto`:
1. Compiles `obj` via `compileExpr` → register `or` (bails if not TRef).
2. Looks up `refTypeName(or)` to get the type name (bails if unknown).
3. Calls `ctx.metaFor(typeName)` to get `(names, types)` arrays (bails if null).
4. Finds `field.value` in `names`; bails if not found.
5. Emits `GETFI(dst, or, strSlot(field))` for Int fields, or `GETFR` + sets
   `refTypeName(dst)` for Ref fields; bails for Double fields (unsupported
   in meta so far).

Method calls (`Term.Select` as the `fun` of `Term.Apply`) remain bailed as
`"unsupported: Term.Select"`. Only standalone selects (outside `Apply.fun`)
reach the new case.

**Behavior:**
- [x] `def normSq(v: Vec): Int = v.x * v.x + v.y * v.y` compiles and runs
- [x] `def getA(o: Outer): Int = o.a` (Int field on case class) compiles
- [x] Method calls and String-field selects still bail gracefully
- [x] No SscVmTest regression (50 tests pass)

### p3+p4 — Inner `def` as non-capturing local ✓ Landed (2026-06-05)

**Shape:** a `def` appearing inside a function body (statement position):

```scala
def outer(n: Int): Int =
  def double(x: Int): Int = x * 2
  double(n) + 1
```

**Implemented:** new `case d: Defn.Def` in `compileStmt`. Extracts params and
param-types from `d.paramClauseGroups`, creates a `Value.FunV(params, d.body,
Map.empty, d.name.value, defaults, paramTypes)` (empty closure — non-capturing
only), and calls `ctx.compileFn(innerFunV)`. If the inner body references outer
locals (captures), the inner Builder bails with "undefined: name '...'" which
propagates and disables the outer function too (correct). On success, stores
`innerFunV` in a new `innerDefs` map. The `callTarget` method checks `innerDefs`
before `ctx.resolveName` so calls to inner defs resolve correctly.

**Note on the 17 "undefined: name 'inner'" misses:** those come from functions
in the test suite that close over a *module-level* `val inner = ...` variable
(e.g. `direct-syntax.ssc`, `async.ssc`). They are closure captures of a
free variable, not inner defs — not fixable by this slice. They remain 17
after this landing.

**Behavior:**
- [x] `def double(x: Int): Int = x * 2` inside outer — compiles and callable
- [x] Capturing inner def (`inner(x: Int) = x + n`) bails gracefully; interpreter
      fallback gives correct result
- [x] 4 new SscVmTest tests; 93/93 pass; no bench regression

### p5 — `Lit.Null` ✓ Landed (2026-06-05)

Emit `CONST dst, constSlot(0L), 0`, `setType(dst, TRef)`. Null is a valid
TRef value (the VM represents it as 0 in the ref bank). Returns TRef, so using
null as a sentinel `val` inside a function body now compiles. Returning null
still bails at `unifyRet(TRef)` (RET is Long-typed — correct).

**Behavior:**
- [x] `val sentinel = null` inside a function body compiles; function runs correctly
- [x] Returning null from a function still bails (RET is Long-typed, by design)

### p6 — `Lit.String` in intermediate position (23 misses)

**Status:** ✓ Landed (2026-06-05).

**Shape:** string literals appear in a function body in a non-return position
(passed as arguments, used in equality comparisons, stored in a local `val`),
while the function itself returns an `Int` or `Boolean`.

```scala
def isUsd(s: String): Boolean = s == "USD"
def classify(s: String): Int  = if s == "EUR" then 1 else if s == "USD" then 2 else 0
```

**Analysis of current 23 misses (2026-06-05):**

Diagnostic (`[LitString bail]` log) shows 8 unique bodies:
- `"n=" + x` — string concatenation (out of scope this slice)
- `if (s == "42") Right(42) else Left("bad")` — string comparison + ref return
- `minorUnits(money(s, "USD"))` — string as call arg; `money` returns TRef
- `helper(s)` — cascaded bail from `money`
- `renderDoc(doc, 0)` — callee returns String (TRef)
- `renderDoc body: doc match { ... }` — match arms return String (TRef)
- `prettyCalc body: e match { ... }` — match arms return Doc (TRef)

All 23 are blocked by **ref returns** in the callee or the function itself
(plus string concatenation in one case). LOADS + EQREF/NEREF alone do not
free any of the current 23 misses. They are the correct foundation for a
future p7 (RETREF + CALLREF) slice.

**New opcodes added (SscVm):**

| Opcode | Encoding | Semantics |
|---|---|---|
| `LOADS 44` | dst, strSlot, 0 | `refStack[base+dst] = StringV(strPool[strSlot])` |
| `EQREF 45` | dst, a, b | `stack[base+dst] = (refStack[base+a] == refStack[base+b]) ? 1L : 0L` |
| `NEREF 46` | dst, a, b | `stack[base+dst] = (refStack[base+a] != refStack[base+b]) ? 1L : 0L` |

`EQREF`/`NEREF` use structural equality (`.equals()`), matching SSC semantics
for string comparison.

**VmCompiler changes:**

- `compileInto`: `case Lit.String(s) =>` emits `LOADS dst, strSlot(s), 0`,
  sets `TRef`, returns `TRef`.
- `emitArith`: `TRef op TRef` for `==` → EQREF (result TInt), `!=` → NEREF.
  Any other operator on TRef bails ("ref: unsupported operator on ref types").
- `unifyRet(TRef)` still bails ("ret: ref-typed return") — returning a string
  from a compiled function is deferred to p7.

**Results (2026-06-05):**

`unsupported: Lit.String` bail eliminated. Post-p6 miss profile (300 total):

```
199  call: no compilable target
 39  field: no meta for type 'String'   ← new: String.method() calls
 18  ret: ref-typed return              ← former Lit.String misses unblocked, now hit ref-return wall
 17  undefined: name 'inner'
  6  undefined: name 'ring'
  4  undefined: name '_VNODES_PER_NODE'
  4  field: no meta for type 'List[...]'
  3  unsupported: Term.Function
  3  call: ref/numeric arg type mismatch
```

Next targets: `ret: ref-typed return` (18) via p7 RETREF/CALLREF, or
`field: no meta for type 'String'` (39) via a String-meta resolver.

**Behavior:**
- [x] `def isUsd(s: String): Boolean = s == "USD"` compiles and returns true/false
- [x] `def classify(s: String): Int = if s == "EUR" then 1 else if s == "USD" then 2 else 0` compiles
- [x] String literal in a `val` inside the function body no longer bails
- [x] Functions returning `String` still bail (unifyRet(TRef) unchanged)
- [x] No `SscVmTest` regression (1329/1329 pass)

### p7 — `String.length` meta (39 misses)

**Status:** ✓ Landed (2026-06-05).

**Diagnostic:** 37 × `String.length` (runParser + _hash), 2 × `String.scale`
(Money field confused as String — skip), 5 × `List[*].isEmpty` (List meta,
out of scope this slice).

**Changes:**
- `JitRuntime.metaFor` — add `"String"` before `typeFieldOrder` lookup:
  `("length", "isEmpty", "nonEmpty") → ("Int", "Boolean", "Boolean")`
- `SscVm GETFI` — `Value.StringV` branch: `"length" → v.length`, `"isEmpty" → isEmpty`, `"nonEmpty" → nonEmpty`
- `VmCompiler Lit.String` case — add `setRefType(dst, "String")` so that
  `.length` on a string literal also compiles.

**Behavior:**
- [ ] `def hashLen(s: String): Int = s.length` compiles and runs
- [ ] `def isBlank(s: String): Boolean = s.isEmpty` compiles and runs
- [ ] `"hello".length` as expression compiles
- [ ] No `SscVmTest` regression

### p8 — `Lit.String` ref returns + `RETREF`/`CALLREF` (deferred)

Add `RETREF` opcode and `CALLREF` dispatch so functions that return strings
(or other ref values) can compile. Requires callee return-type detection at
instruction-build time (two-phase compileFn or syntactic pre-scan).

### p9 — `Term.Function` (3 misses)

Anonymous lambdas used as values (not immediately applied). These are
closures, so they overlap with the "no compilable target" category. Likely
not compilable without a heap model. Skip unless analysis shows they are
always self-contained `Int → Int` lambdas immediately passed to a compiled
callee.

## 5. Out of scope

- **`call: no compilable target` (198 misses):** free-name calls to
  `NativeFnV`, closures, HOF. Requires a ref-callable slot type in the VM
  and a new `CALLREF` opcode. Large scope; separate phase.
- **String concatenation** (`"n=" + x`): needs `toString` + heap allocation.
  Not supported in the numeric-domain VM; remains a permanent bail.
- **`undefined: name '_VNODES_PER_NODE'` (4 misses):** global vals that are
  not `FunV` — compile-time constant folding. Small scope but needs a new
  resolver hook; deferred.

## 6. Success criterion

After all p2–p6 slices: disabled count same as after p5 (299) for actual
miss reduction (p6 adds no wins on current tests), but the new opcodes
enable future string-heavy functions to compile correctly. All new paths
have unit tests; no `SscVmTest` regression; no benchmark regression on
`recursionFib`, `recursionTco`, `recursiveEval`.
