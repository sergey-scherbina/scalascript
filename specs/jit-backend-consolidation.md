# JIT backend consolidation — decision record

Status: **RESOLVED 2026-06-15 → KEEP BOTH** (arch-review item `jit-backend-consolidation`).
No code change: the architecture is already correctly factored.

## Question

The interpreter ships two bytecode-JIT backends — `JavacJitBackend` (4309 LOC) and `AsmJitBackend`
(5017 LOC), ~9.3K LOC of seemingly-parallel code. The arch review (2026-06-14) flagged this as a
possible consolidation target: keep-both-with-shared-lowering, or primary + archive?

## Investigation (2026-06-15)

**It is a deliberate dual-strategy design, not accidental duplication.**

- **Clean SPI already exists.** `JitBackend` (trait) defines `tryCompile` / `tryCompileWhileLong` /
  `tryCompileWhileMixed` / `tryCompileWhileLongEmit` / `classifyBailReasons`. `JitBackend.default`
  selects by `SSC_JIT_BACKEND` (`asm` → AsmJit; `javac`/unset → JavacJit).
- **Shared infrastructure is already extracted.** `JitPredicates.classifyBailReasons` (the default
  `classifyBailReasons` impl), `JitLint` (targets either backend; asserts both classify identically),
  `WhileJitEntry`, `JitGlobals`, `JitBailReason`, `JitInterfaces` are all shared. The earlier
  `jit-predicates-shared` work already lifted the pure classifiers into `JitPredicates`.
- **The two backends are legitimately different compilation strategies whose lowering CANNOT be
  shared:**
  - `JavacJitBackend`: emits **Java source** → `javax.tools.JavaCompiler` → bytecode. Simpler to
    author/maintain; **requires the JDK compiler** + pays javac startup cost. The default.
  - `AsmJitBackend`: emits **JVM bytecode directly** via ASM 9.7 — no Java-source intermediate, **no
    javac dependency**, no javac startup. Works in a JRE-only / native-image context where
    `javax.tools` is absent. Opt-in via `SSC_JIT_BACKEND=asm`.
  - Their per-backend code is the lowering itself (AST → Java-source-string vs AST → ASM opcodes).
    These target **different outputs** and are inherently un-shareable — you cannot factor "append a
    Java `String`" together with "visit an ASM `MethodVisitor`".
- **AsmJit is complete and actively maintained at parity**, not a stale experiment: it overrides all
  four SPI compile methods (not stubs), is covered by tests (`JitClasspathTest`, `JitLintTest` + the
  JitLint parity assertion), and recent commits keep it in lockstep with Javac feature work — e.g.
  `711ee7c35 perf(interp): ASM parity for JIT tuple construction/access/++ (T3)`,
  `596614acb fix(interp/jit): JIT codegen crashes bail to tree-walk` (touched both).

## Decision

**Keep both.** The ~9.3K LOC is two distinct, deliberately-maintained compilation strategies behind a
clean SPI, with all the *shareable* infrastructure already extracted. Removing AsmJit would drop the
no-javac / native-image-friendly path (a real capability); merging the two is impossible (different
output targets). There is **no safe shared-lowering extraction left** — the consolidation premise does
not hold.

## Maintenance contract (the real ongoing concern)

The genuine risk is **drift** — a JIT feature landing on one backend but not the other. This is already
mitigated:
- `JitLint`'s parity assertion: both backends must classify the same functions as JIT-able / bailed.
- Convention: feature commits land "ASM parity" alongside the Javac change (see the T3 commits).

If drift recurs, the cheapest fix is to keep lifting *pure, output-independent* helpers into
`JitPredicates` (the proven `jit-predicates-shared` pattern) — e.g. small shared constants like the
`builtinAdtCtorNames` set that is currently duplicated "mirrors JavacJitBackend". That is a micro-cut,
not a consolidation, and only worth doing if a specific drift bug motivates it.

## Outcome

Arch-review item closed as **resolved (keep both)**. No code change. This record exists so the
"should we consolidate the JITs?" question is not re-litigated from a surface LOC scan.
