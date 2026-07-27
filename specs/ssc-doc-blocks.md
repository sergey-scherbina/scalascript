# `@doc` — documentation-only code blocks

**Status:** SPEC, not implemented. Decided by Sergiy 2026-07-27 ("маркер doc-only блока") after the
corpus contract surfaced an INT-vs-v2 divergence on `examples/coroutine-demo.ssc`.

## The problem this solves

A `.ssc` literate module holds definitions **and** runnable `## Example` blocks. Every fence tagged
`scalascript` executes, so a module's examples run whenever the module is *imported* — their output
lands in the importer's stdout.

Measured (`examples/coroutine-demo.ssc`, which imports `v1/runtime/std/coroutine.ssc`):

| lane | lines printed |
|---|---|
| `int` | **17** — 9 from `std/coroutine.ssc`'s `## Example` blocks, then the demo's 8 |
| `v2` | **8** — the demo's own output only |

The demo's author wrote 8. The two lanes disagree, so the corpus contract reports `DIVERGE`.

**Why "just don't execute imported blocks" is not the answer.** Importing a module *must* run its
top-level code: `std/http.ssc` registers routes at import, modules initialise `val`s. Suppressing
execution on import breaks all of that. The language simply has no way to say *"this block is
documentation, not part of the program"* — that is the actual gap.

## The surface

Reuse the shipped fence-attribute syntax (`ContentDocumentTest` already covers `` ```yaml
@id=plans-data ``; `Content.CodeBlock.attrs` is already read by the SQL/transaction runners):

````markdown
```scalascript @doc
val pingpong = coroutineCreate[String, String, Unit] { () => … }
println(coroutineResume(pingpong, ""))
```
````

Semantics — one rule, no exceptions:

1. A `@doc` block is **parsed and type-checked exactly like any other block**, so it cannot rot into
   uncompilable documentation. This is the whole point of preferring it over a plain ` ``` ` fence.
2. It is **never executed and never lowered into the program** — not when the file is run directly,
   not when it is imported, on **every** lane.
3. Its bindings are **not** visible to other blocks. A `@doc` block may *read* names the module
   defines (that is what makes it a usage example).

Rule 2 is what makes the lanes agree; rule 1 is what keeps the examples honest.

## Blocking prerequisite

**`BUGS.md` → `v2-native-front-drops-attributed-code-fence` must be fixed first.** The native lane —
the default — currently discards *any* `scalascript` fence carrying an attribute, code and all
(`def helper()` inside `` ```scalascript @id=defs `` → `unbound global: helper`, on both F and
legacy). That makes `@doc` *appear* to already work on v2 while it is really an accident that would
equally swallow `@id=…`. Implementing `@doc` on top of it would build a feature on a bug.

## Lanes that must honour it

Every lane that executes blocks, or the feature just moves the divergence:

| lane | where |
|---|---|
| INT | `SectionRuntime.runSection` — the `Content.CodeBlock if Lang.isParseable` arm |
| native / v2 | the native front's fence scanner (same place as the prerequisite bug) |
| JS | JS codegen's block walk |
| JVM | JVM codegen's block walk |

## Verification (write these before the code)

- A fixture with one `@doc` block and one bare block, run on **all four lanes**: every lane prints
  only the bare block's output. This is the whole feature — it must be a cross-lane test, not a
  per-lane one, because per-lane greens are exactly what hid the current divergence.
- A `@doc` block containing a **syntax error** must fail the build (rule 1). Without this test,
  `@doc` silently degrades into ` ``` `.
- `examples/coroutine-demo.ssc` on `int` and `v2` produce byte-identical output once
  `v1/runtime/std/coroutine.ssc`'s three `## Example` blocks are marked `@doc`.
- The corpus contract entry `coroutine-demo v2 DIVERGE` disappears.

## Migration

Mark the `## Example` blocks of literate `v1/runtime/std/*.ssc` modules `@doc`. `std/coroutine.ssc`
is the known case; sweep the rest rather than fixing one — the same shape is free to appear in any of
them, which is the argument that made a language marker preferable to deleting the `println`s.
