# `exec` — global process-execution builtin (JVM interpreter)

Status: **active**. The first concrete piece of the planned `std.process` surface
([`specs/std-fs-os.md` §4](std-fs-os.md)) — shipped as a **bare global builtin** on
the JVM interpreter, exactly as `writeFile`/`readFile`/`mkdirs`/`listDir` are the
bare-global form of `std.fs` (an `extern def` in a `std.*` module does not link to a
native on import; scripts need the primitive bare). Requested by **busi** for the
versioned-repository epic's `git-mirror` transport (vr-10): busi must drive the
local `git` CLI to mirror a repository losslessly to a real git host.

## API

```scalascript
// Run a program (argv form — no shell, no word-splitting). command.head is the
// executable; the rest are its arguments. Returns (exitCode, stdout, stderr).
exec(command: List[String]): (Int, String, String)

// With per-call environment variables added/overridden for the child process.
exec(command: List[String], env: List[(String, String)]): (Int, String, String)
```

- **argv, not a shell line**: `exec(List("git", "-C", dir, "push", "origin", "main"))`.
  No shell is involved, so there is no quoting/word-splitting/injection surface —
  arguments are passed verbatim to the OS.
- **blocking**: runs to completion and returns the exit code, full stdout, and full
  stderr (UTF-8). stderr is drained on a side thread while stdout is read, so a
  program that fills both pipes cannot deadlock.
- **environment**: the child inherits the interpreter's environment; the optional
  2-arg form adds/overrides the given `(name, value)` pairs (e.g.
  `exec(List("git", …), List(("GIT_AUTHOR_DATE", iso)))` for a deterministic,
  faithfully-dated commit). Per-call cwd is still via the program's own flag (e.g.
  `git -C <dir>`); a fuller `ProcessOptions` (cwd/timeout) form is the planned
  `std.process.exec` (§4, later).
- **not found / spawn failure**: throws `InterpretError` (the executable is missing
  or not runnable) — distinct from a process that *runs* and exits non-zero, which
  returns a non-zero exit code in the tuple.

## Relationship to `std.process` (§4 of std-fs-os.md)

`std.process.exec(cmd, args, opts): ProcessResult` is the eventual cross-backend,
capability-gated, options-bearing API with JS/Node + Rust backends and a
`ProcessResult` record. This builtin is its JVM bare-global primitive: a single
argv list in, a `(Int, String, String)` tuple out (the tuple is the wire-simple
analog of `ProcessResult{stdout, stderr, exitCode}`; ordering differs —
exit-code-first matches the `sqliteGetObject`-style tuple returns already in the
interpreter). The rich API can wrap this primitive when the os-plugin lands.

## Out of scope

- `spawn` (streaming/interactive `Process` handle), per-call `ProcessOptions`
  (cwd/env/timeout), the JS/Node and Rust backends, a `Feature.ProcessSpawn`
  capability gate, and the `std.process` module/`extern def` wiring — all remain in
  the `std-fs-os` plan. This slice is the JVM global primitive only.

## Implementation

`runtime/backend/interpreter/.../BuiltinsRuntime.scala`, alongside the `std.fs`
bare globals: `interp.globals("exec") = Value.NativeFnV("exec", …)`. Uses
`java.lang.ProcessBuilder(command*)`; reads `getErrorStream` on a
`CompletableFuture` while reading `getInputStream` on the calling thread, then
`waitFor()`; returns `Value.TupleV(List(IntV(code), StringV(out), StringV(err)))`.
A spawn failure (`IOException`) is rethrown as `InterpretError`.

## Behavior

- [x] `exec(List("echo", "hi"))` → `(0, "hi\n", "")`.
- [x] A program exiting non-zero returns its code with any stderr captured (no throw).
- [x] stdout and stderr are captured independently.
- [x] A missing executable throws (not a silent `(0,"","")`).
- [x] Large output does not deadlock (stderr drained concurrently).
- [x] Assembled-jar (`sbt installBin`) smoke test passes — the real harness, not
      `ssc run`.

## Verification

`sbt installBin`, then run a `.ssc` smoke that calls `exec(List("echo","hi"))`,
`exec(List("sh","-c","exit 3"))`, and a missing-binary case; assert the tuple fields.

## Results

Implemented 2026-06-13. Added `interp.globals("exec")` to `BuiltinsRuntime.scala`
beside the `std.fs` bare globals: `ProcessBuilder(command*)`, stderr drained on a
`CompletableFuture` while stdout is read, then `waitFor()`; returns
`TupleV(IntV(code), StringV(out), StringV(err))`; an `IOException` (spawn failure)
rethrows as `InterpretError`. No `CapabilityRegistry` entry needed (it is a
manifest heuristic, not a gate — the `mkdirs`/`listDir` bare globals likewise
aren't listed and work). Built `sbt installBin`; smoke (`echo` → `(0,"hi\n","")`,
`sh -c 'exit 3'` → code 3, independent stdout/stderr capture with exit 7, missing
binary → throws) **ALL OK in the assembled jar**. The rich `std.process.exec(cmd,
args, opts): ProcessResult` + JS/Rust backends + `spawn` remain in the `std-fs-os`
plan.
