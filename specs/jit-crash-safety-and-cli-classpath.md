# JIT crash-safety + the CLI/runMain JIT classpath fallback

**Status:** implemented 2026-06-12 (crash-safety guards); two follow-ups filed.
**Source:** sprint `cli-jit-classpath-fallback` (found while diagnosing busi seq-124).

## The classpath fallback is `runMain`-only

The javac JIT backend compiles the generated `GenJit_*` Java at run time with
`ToolProvider.getSystemJavaCompiler` and **no `-classpath`** option, so the runtime
javac uses `java.class.path`.

- Under **sbt / `cli/runMain`**, app classes are loaded by sbt's URLClassLoader,
  *not* `java.class.path` (which is just the sbt launcher). So the generated source
  can't resolve `scalascript.interpreter.vm.jit.*` → `package … does not exist` →
  the JIT bails to tree-walk. **Silent.**
- Under the assembled **fat jar** (`java -jar ssc.jar`, i.e. real `bin/ssc`),
  `java.class.path` *is* the jar, which contains those classes → the JIT compiles
  fine. Verified: `fib(30)` JITs to `832040` via the jar.

So the silent fallback is a **dev-harness artifact**, not a user-facing CLI bug.
(It did mislead the seq-124 diagnosis — a `runMain` repro tree-walked and looked
"fixed", while the test harness, which *also* bails the javac JIT, never exercised
the compiled path either.)

## The real bug it hid: JIT codegen crashes weren't caught

Because the javac JIT *always bails in sbt* (classpath), its source-generation is
**effectively untested by the suite**. Running a JIT-eligible program through the
**fat jar** surfaced a `StackOverflowError` in `walkLocalSlotCtx` on a specific
shape (a `while` accumulator calling a recursive fn) — and it **crashed the
program** (`[ERROR] null`, exit 1) instead of bailing.

Root: the JIT *compile entries* guarded the downstream javac/classload steps but
**not the source-generation** (`walkLong`/`walkLocalSlotCtx`). A JIT codegen bug
must never crash a valid program — it must bail to tree-walk.

**Fix:** wrap every JIT compile entry in `try … catch case _: Throwable => null`:
- `JavacJitBackend.tryCompile` / `AsmJitBackend.tryCompile` (the function-JIT path).
- The three while-loop-JIT call sites in `EvalRuntime` (`tryCompileWhileLong`,
  `tryCompileWhileMixed`, `tryCompileWhileLongEmit`).

After the fix the same program bails to tree-walk and prints the correct `236775`.
Verified on the fat jar; the sbt suite stays green (the guards are pure additions
with existing bail semantics).

## Follow-ups (filed in SPRINT)

- **jit-runmain-classpath** — pass the running classloader's URLs as `-classpath`
  to the runtime javac so the JIT compiles under sbt/`runMain` too. This makes the
  javac codegen *actually tested* by the suite (and removes the diagnosis trap).
  RISK: latent JIT codegen bugs (like the `walkLocalSlotCtx` SO) would start firing
  in tests — now safe to bail thanks to the crash-safety guard, but may surface as
  perf/coverage changes.
- **jit-walklocalslotctx-so** — fix the `walkLocalSlotCtx` recursion that overflows
  on the `while`-accumulator-over-recursive-call shape (now bails harmlessly; a real
  fix restores JIT for that shape).

### Behaviour checklist

- [x] fat-jar JIT works (no classpath error) — `fib(30)` → `832040`.
- [x] a JIT codegen crash (SO) bails to tree-walk instead of crashing — the
      `while`-accumulator shape prints `236775` on the fat jar (was exit-1 crash).
- [x] guards added at all 4 JIT compile entries (2 fn + 3 while = function/while
      paths, both backends for the fn path); suite green (327 JIT/pattern + 48 BugRepro).
- [x] shape correctness guard in `BugReproTest` (tree-walks in sbt; guards the JIT
      crash too if the runMain classpath is ever fixed).
