# Parser robustness — silent ScalaMeta NPE must become a diagnostic

**Status**: spec — bug fix (developer-experience / safety). Reported by busi, 2026-06-09.
**Priority**: medium-high. The interpreter **hangs with no output** — worst
possible failure mode (no error, no stack trace, no line number).

## 1  Symptom

Two distinct source shapes trigger a `NullPointerException` deep inside ScalaMeta
parsing that is swallowed; the interpreter produces no output and appears to hang.

### Case A — bare `\"` used as an argument delimiter

```scalascript
// outside any containing string literal:
badCsv.replace(\"\n\", \"\\n\")
```

A backslash-escaped quote `\"` in argument position (not inside a string literal)
makes the tokenizer enter a state that NPEs in `termParam`. Correct source uses
plain `"`:

```scalascript
badCsv.replace("\n", "\\n")   // works
```

### Case B — mismatched closing parens in deep nesting

```scalascript
jObj(List(jField("a", jObj(List(jField("b", v))))   // one paren short
```

Deeply nested `jObj(List(jField(...jObj(...))))` chains with an unbalanced paren
trigger the same `termParam` NPE — silent, no helpful message. Found in busi
phase85a KSeF `InitToken` handler.

## 2  Expected behavior

Both cases are **syntax errors**. The parser must report a diagnostic with:
- a clear message ("unbalanced parentheses" / "unexpected `\"` outside string literal"),
- the source file and an approximate line/column,
- and a non-zero exit — never a silent hang.

It is acceptable if the location is approximate; the requirement is "fail loudly,
not silently."

## 3  Implementation plan

- Wrap the ScalaMeta parse entry point so a thrown `NullPointerException` (or any
  Throwable from the tokenizer/parser) is caught and rethrown as a ScalaScript
  `ParseError` carrying the file name and the best-known position.
- For Case A specifically: detect a `\"` token sequence in non-string context
  during preprocessing and emit a targeted message ("a `\\\"` escape only has
  meaning inside a string literal").
- For Case B: surface ScalaMeta's own position if available; otherwise report the
  enclosing top-level definition and a paren-balance hint.

## 4  Behavior checklist

✓ **Landed 2026-06-11.** Root cause turned out to be narrower than "swallowed
NPE": ScalaMeta already returns a clean `Parsed.Error` for both shapes (no longer
NPEs), and `parseScalaWithDiagnostic` already produces a structured
`CodeBlockParseError` with file:line:col + a `^` caret. The gap was at the CLI:
the `run`/`compile` dispatch (`compileViaBackend`) **never surfaced** the
per-block parse error — it ran partial IR and produced no output, the "silent
hang" symptom. Fix: `compileViaBackend` now calls `reportCodeBlockParseErrors`
right after `loadModule` and returns `CompileResult.Failed(Nil)` (the structured
diagnostic is already on stderr), so every `run`/`compile`/`emit` path exits
non-zero. (`ssc check` already returned exit 2.) The Case-A/B targeted-message
polish was unnecessary — ScalaMeta's own message points exactly at the `\"` / the
missing `)`.

- [x] Case A source produces a `ParseError` with file + position, non-zero exit.
- [x] Case B source produces a `ParseError` with file + position, non-zero exit.
- [x] No input causes a silent hang / empty output on a parse failure.
- [x] Valid programs are unaffected (no false positives) — CLI suite 335 green.
- [x] The error text names the file so multi-file module builds point at the culprit.

## 5  Verification

`ParserNpeDiagnosticTest` with the two repro shapes asserting a `ParseError` (not
a hang). Run the full interpreter test suite to confirm no false positives.

## 6  busi context

Cost busi real debugging time twice (phase90 e2e story-f; phase85a KSeF handler):
a hanging interpreter with no message is indistinguishable from an infinite loop.
A one-line diagnostic would have made both trivially obvious.
