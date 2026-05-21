# REPL Debugger (`ssc repl` debug commands)

**Status:** complete — v1.34 (2026-05-21)

Interactive step-debugger built into `ssc repl`.  No IDE or TCP connection
needed — breakpoints and step execution are controlled directly from the
`ssc>` and `(debug)` prompts.

Reuses the same `DebugHooks` / `BreakpointRegistry` infrastructure as the
DAP debugger (`ssc debug`), so all breakpoint and step semantics are
identical.

---

## Quick start

```
$ ssc repl
ScalaScript REPL  (blank line to run, :quit to exit)
Debug: :break <N>  :step  — :help inside (debug) prompt

ssc> :break 2
[break] set at line 2

ssc> val x = 10
   | val y = x * 2
   | y + 1
   |
[stopped] at line 2
  > val y = x * 2

(debug) :locals
  x = 10

(debug) :next
[stopped] at line 3
  > y + 1

(debug) :print y
  = 20

(debug) :continue
=> 21

ssc>
```

---

## Commands at the `ssc>` prompt

| Command | Description |
|---|---|
| `:break <N>` | Set a breakpoint at line N of the next snippet |
| `:break clear` | Remove all breakpoints |
| `:break list` | List currently set breakpoints |
| `:step` | Enable step-in mode for the next snippet (stops at every line) |

Breakpoints persist across snippets until explicitly cleared.  Line numbers
are 1-based relative to the snippet you type, not the document.

---

## Commands at the `(debug)` prompt

Available when the interpreter is stopped at a breakpoint or step.

| Command | Alias | Description |
|---|---|---|
| `:continue` | `:c` | Resume to the next breakpoint, or to the end |
| `:next` | `:n` | Step over — advance to the next line at the same call depth |
| `:step` | `:s` | Step into — advance to the next expression (may enter a function) |
| `:out` | | Step out — run until the current function returns |
| `:locals` | `:l` | Print all local variables in the current frame |
| `:stack` | `:bt` | Print the call stack |
| `:print <expr>` | | Evaluate `<expr>` in the current frame and print the result |
| `:help` | `:h` | Show this command list |
| `:quit` | `:q` | Stop the current snippet and return to `ssc>` |

---

## Typical workflows

### Inspect a value mid-computation

```
ssc> :break 3
[break] set at line 3

ssc> val items = List(1, 2, 3)
   | val doubled = items.map(_ * 2)
   | val total = doubled.sum
   |
[stopped] at line 3
  > val total = doubled.sum

(debug) :print doubled
  = List(2, 4, 6)

(debug) :continue
=> 12
```

### Step through a function call

```
ssc> def square(n: Int) = n * n
   |
ssc> :step
[step] step-in enabled — enter your snippet

ssc> val a = square(5)
   |
[stopped] at line 1
  > val a = square(5)

(debug) :step
[stopped] at line 1
  > n * n             ← now inside square()

(debug) :locals
  n = 5

(debug) :continue
=> 25
```

---

## Threading model

When debug mode is active (at least one breakpoint or `:step` enabled), the
snippet is run on a **background virtual thread**.  When the interpreter hits
a stop condition it:

1. Puts the `DebugFrame` on `stoppedQueue`.
2. Blocks on a `CountDownLatch`.

The REPL main thread drains `stoppedQueue`, prints stop info, and enters the
`(debug)` sub-loop.  Calling `:continue`, `:next`, `:step`, or `:out` releases
the latch and resumes the interpreter thread.

This is the same pattern used by the DAP debugger — the only difference is
that communication happens via in-process queues instead of TCP JSON-RPC.

---

## `:print` expression evaluation

`:print <expr>` calls `Interpreter.evalExpr(expr, frame.locals)`, which
evaluates a Scala 3 expression with the current frame's local variables
merged into the interpreter globals.  Debug hooks are suppressed during
evaluation so the expression itself does not trigger further breakpoints.

---

## Limitations (v1.34)

- **Multiple stops on the same source line**: if a breakpoint is set on a
  line containing several sub-expressions (e.g. `val x = f(g(1) + h(2))`),
  the interpreter may stop multiple times on that line — once per `eval` call.
  `:continue` will advance to the next occurrence.  A "first-stop-per-line"
  deduplication is planned for a later release.
- **Imports**: breakpoints apply only to the current snippet's source file
  (`<repl>`).  Code loaded via `:import` or `runImport` is not stepped through
  from the REPL debugger (use `ssc debug` for that).
- **State after `:quit`**: quitting mid-snippet leaves the interpreter globals
  in a partially-evaluated state.  Use `:quit` only when you want to discard
  the snippet result.

---

## Related

- [`ssc debug`](dap-debugger.md) — DAP-based IDE debugger (VS Code / IntelliJ)
- [REPL web-aware mode](repl-web.md) — planned `:mount` / `:http` commands (v1.30)
