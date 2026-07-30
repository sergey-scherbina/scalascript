# v2 has no `Char`: a Char literal is its code point, everywhere

**Found 2026-07-30** by `jsgen-char-escape`, by its own gate. The conformance case written to prove
the JS Char-escape fix (`tests/conformance/char-literal-escapes.ssc`) was run on all three lanes, and
v2 failed one line of it. This file exists because every `BUGS.md` in the repo is held by the
`work-tracking-per-module` claim, so the finding could not be filed as an entry; **route it into
`v2/BUGS.md` as `v2-char-is-an-int` once that claim releases.**

## Measured

```scalascript
def main() =
  println('x')
  println('x'.toString)
  val c = 'a'
  println(c)
  println("s" + 'b')
  println('x' == 'x')
```

| line | int (golden) | v2 |
|---|---|---|
| `println('x')` | `x` | **`120`** |
| `println('x'.toString)` | `x` | **`120`** |
| `println(c)` where `val c = 'a'` | `a` | **`97`** |
| `println("s" + 'b')` | `sb` | **`s98`** |
| `println('x' == 'x')` | `true` | `true` — agrees |

## What it is, and what it is not

It is **not** a display/`Show` difference. `.toString` and string concatenation are wrong too, so a
Char literal is not being rendered as a number — it *is* a number on this lane. v2 has no distinct
Char representation, so `'x'` compiles to the `Int` 120 and every downstream operation sees an Int.

Equality still agrees, which is exactly why this hides: `'x' == 'x'` is `true` either way, and Char
comparisons are the common case in a parser or lexer. What breaks is anything that puts a Char into
text.

Note the operations that DO work on v2 — `"a\nb".lastIndexOf('\n')` and `'\n' == '\n'` both give the
same answer as int — so the gap is confined to Char-as-a-value-in-text, not to Char arguments.

## Why it surfaced now

Nothing in the corpus rendered a bare Char literal on the v2 lane until this case was added. That is
the same shape as `jsgen-char-literal-escape`, which stayed invisible while `dsl-yaml-like` was a
corpus SKIP: a defect with no case that exercises it reads exactly like a defect that is not there.

## Fix direction

`v2/src` is unclaimed as of 2026-07-30 (`v2-backend-matrix-gaps` released in `5e9e8b599`). This is not
a one-liner: it needs a Char-shaped value in the v2 runtime, or a compile-time marker that survives
into `Show`/`toString`/concatenation, so it wants its own claim and its own fail-first case. The
conformance case `char-literal-escapes.ssc` deliberately does NOT include the bare `println('x')`
line, so it gates the JS fix cleanly on all three lanes; a v2 Char fix should add its own case rather
than widen that one.

**Do not** "fix" this by making `Show` special-case an Int in Char position — the `.toString` and
concatenation rows above show the value has already lost its identity by then.

---

# Two more findings from the same gate

The `char-literal-escapes` case was written to gate ONE fix and surfaced two more defects. Both are
recorded here for the same reason as the Char gap above: every `BUGS.md` is held by
`work-tracking-per-module`, so neither could be filed as an entry.

## `js-imported-extension-method-not-dispatched` — route to `v1/runtime/backend/js/BUGS.md`

With `jsgen-char-literal-escape` fixed, `dsl-yaml-like` stops failing to PARSE on js and fails one step
later:

```
Method not found: parseLayoutWith
```

`parseLayoutWith` is declared at `v1/runtime/std/parsing/layout.ssc:292`, inside
`extension [A](p: Parser[A])` (line 288). So the js lane does not dispatch an extension method that
arrived through a module import — the js counterpart of the interpreter gap fixed in `91c326d1f`, where
the import-binding loop refused to even NAME such a method.

Worth checking as one family rather than one case: the interpreter needed both a name fix and the
wholesale `exportedExtensions` copy to make imported extensions work. The js lane inlines imports, so
the failure mode is different but the surface is the same.

## `jsgen-char-literal-escape` — FIXED, and what the evidence actually was

The fix is `escapeJsString` for both literal arms plus the third partial copy at `:5494`. Recorded here
because one intermediate observation looked like a second defect and was NOT one:

when the fix was reverted to prove the gate fails first, Node's error display appeared to show the
STRING literal broken as well. Emitting the JS and reading the bytes settled it — the original output is

```
_println(_dispatch("a\nb", 'lastIndexOf', ["<RAW NEWLINE>"]));
```

so `Lit.String` was always correct and only the Char arm was raw. What looked like a second defect was
Node printing a physical source line that the raw newline had split. Measured rather than assumed,
because the alternative was filing a bug against working code.
