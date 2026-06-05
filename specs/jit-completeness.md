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

### p3 — Inner `def` as non-capturing local (17 misses)

**Shape:** a `def` appearing inside a function body where the inner function
only references outer params and other locals (no heap closure):

```scala
def outer(n: Int): Int =
  def inner(x: Int): Int = x * 2 + n
  inner(n + 1)
```

**Strategy:** treat `inner` as a callee with a fixed slot in the call pool.
At `Defn.Def` in `compileStmt`, recursively compile `inner` into a `Builder`
using the same `Ctx`; wire it as a call pool entry. The outer `locals` map
gets a synthetic entry so `callTarget` can find it.

**Constraint:** inner def must have no free variables beyond `outer`'s params
and other already-compiled locals. If it captures a `var`, bail — `var` writes
are not tracked across call frames.

**Expected impact:** 17 → 0 for the simple inner-def pattern.

### p4 — `stmt Defn.Def` in block (2 misses)

Same as p3 but the `Defn.Def` appears in `compileStmt` position (non-tail,
non-result stmt inside a block). Currently hits `bail("unsupported: stmt
Defn.Def")`. Fix: inline the p3 logic into `compileStmt`.

### p5 — `Lit.Null` (2 misses)

Emit `CONST dst, constSlot(0L), 0`, `setType(dst, TRef)`. Null is a valid
TRef value (the VM represents it as 0 in the ref bank). One-liner.

### p6 — `Term.Function` (3 misses)

Anonymous lambdas used as values (not immediately applied). These are
closures, so they overlap with the "no compilable target" category. Likely
not compilable without a heap model. Skip unless analysis shows they are
always self-contained `Int → Int` lambdas immediately passed to a compiled
callee.

## 5. Out of scope

- **`call: no compilable target` (201 misses):** free-name calls to
  `NativeFnV`, closures, HOF. Requires a ref-callable slot type in the VM
  and a new `CALLREF` opcode. Large scope; separate phase.
- **`Lit.String` (26 misses):** string operations require heap. Excluded
  from this phase; the VM is a numeric-domain compiler.
- **`undefined: name '_VNODES_PER_NODE'` (4 misses):** global vals that are
  not `FunV` — compile-time constant folding. Small scope but needs a new
  resolver hook; deferred.

## 6. Success criterion

After all p2–p5 slices: disabled count target ≤ 220 (down from 310), driven
almost entirely by the irreducible 201 closure/HOF category. All new paths
have unit tests; no `SscVmTest` regression; no benchmark regression on
`recursionFib`, `recursionTco`, `recursiveEval`.
