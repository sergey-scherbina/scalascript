## stub-rendered-as-data-reached-an-http-body

<!-- status: fixed
     fixed-in: 3d1d92bbd
     lane: v2-jvm
     area: runtime
     reported-by: rozum / claude-opus-5, meeting room 'scalascript'
     reported-at: 2026-08-08
     ssc-version: 55e868c17
     repro: repro/list-join-stub.ssc
     confirmed: no
     gate: tests/e2e/stub-does-not-serialise.sh -->

Reported as `list-join-stub-serialises`. A missing method rendered as the text `Stub` and flowed on
as a value: in rozum's `.ssc` server it reached an HTTP response body as `{"cell":{Stub}}` — status
200, wrong data, **not one line in the log**.

```
ssc-tools run --v1 lj.ssc   ->  [ERROR] No method 'join' on ListV(...)      loud, correct
ssc-tools run       lj.ssc   ->  map = Stub                                  silent
```

**Cause.** `Runtime.anyStr` — the coercion behind string interpolation and `+`, which is the path
rozum's server took — had an explicit arm rendering the sentinel as `"Stub"`, deliberately, so stubs
would "look the same" in user-visible strings. `Show.show` reached the same result through its
generic `DataV` case.

**Fix: fatal at the OUTPUT boundary, not in dispatch.** `Stub` still exists as the soft landing for
an unknown method — that design is untouched. It is stopped where it would ESCAPE into a string.
Chosen over making dispatch fatal (breaks everything relying on the soft landing) and over
`SSC_FRONT_STRICT=1` (opt-in leaves the default corrupting, and the default is what rozum shipped).

**Second defect, found on the way:** the sentinel carries a breadcrumb `Stub(Tag.method)`, and the
list path blanked it — which is why the repro printed a bare `Stub` rather than naming `join`. Now
preserved, so the error reads:

```
ssc: `Cons.join` was called but does not exist, and the result reached output.
```

`join` does not exist on **either** lane — v1 says so too. The reporter's code was wrong; the defect
is that one lane said nothing.

Full suite 72/72: `anyStr` and `Show` are shared by every lane, and this repository has been burned
before by a targeted gate passing while a shared renderer broke ~28 checks elsewhere.

## f-multi-parameter-clause-def-is-not-lowered

<!-- status: open
     lane: native
     area: front
     reported-by: claude-code
     reported-at: 2026-08-08
     ssc-version: 1f708ae22
     confirmed: yes
     gate: none -->

Front F does not lower a `def` with two parameter clauses. **Pre-existing** — measured identical on
the pre-fix binary — but newly REACHABLE, and that is why it is filed now: it used to be masked by
an unrelated decline, and half of its failure modes are silent.

```
def v(gap: Int = 0)(children: String*): Int = gap + children.toList.length
def main(): Unit = println(v(gap = 24)("a"))

                                             F                        reference
default + varargs, named arg                 ssc: arity: 2 expected   25
varargs, named arg (no default)              24<closure>              25
default, plain second clause                 ssc: arity: 2 expected   25
default + varargs, positional arg            24<closure>              25
```

Two of the four are **silent wrong answers**: F applies the first clause, never applies the second,
and stringifies the resulting closure into the result.

**How it surfaced, and the one regression it caused.** Fixing
`f-ordered-match-arm-body-is-not-a-statement-sequence` (`1f708ae22`) flipped
`runtime/std/ui/lower.ssc` from GAP to F. A decline is program-scoped, so before that flip EVERY
program importing that module went to the reference front — which is what had been hiding this.
Of the 15 corpus files that moved GAP→F, 14 fail identically under both fronts (they are demos that
want a server, not `ssc run`), and **one regressed from working to failing**:
`examples/frontend/std-ui/smoke-test.ssc` printed `smoke:ok` and now reports
`ssc: arity: 2 expected, 1 given`, because `std/ui/layout.ssc` declares
`def vstack(gap: Int = 0)(children: TkNode*)`.

Five std modules declare curried defs: `ui/content.ssc`, `ui/reactive.ssc`, `ui/display.ssc`,
`ui/containers.ssc`, `ui/layout.ssc`.

**The question this raises is not "fix the arity error" but a policy one, and it is the owner's:**
should F REFUSE a file whose closure contains a construct it cannot lower, the way
`validateNoReader` already refuses an unbound global? That would restore the fallback for
`smoke-test.ssc`, convert the two silent-wrong-answer rows above into honest declines, and LOWER the
F coverage number — trading a metric for a guarantee. The alternative is implementing multi-clause
lowering in F, which is feature-sized work and overlaps the area of the active
`v2-three-param-clauses` claim on the reference front.

Not decided here on purpose. What is decided: leaving a construct that F answers WRONGLY reachable
without a written record would repeat the mistake this file exists to prevent.

## f-assignment-headed-arm-body-drops-the-rest-and-returns-a-closure

<!-- status: open
     lane: native
     area: front
     reported-by: claude-code
     reported-at: 2026-08-08
     ssc-version: 1f708ae22
     confirmed: yes
     gate: none -->

Sibling of `f-ordered-match-arm-body-is-not-a-statement-sequence`, found while probing that fix for
regressions, and **pre-existing** — measured identical before and after it, so it is not fallout.

An arm body that STARTS with an assignment is parsed by `parseAssign`, which takes the assignment
and nothing else, so the rest of the body is dropped:

```
def f(k: String): Int =
  var acc = 0
  k match
    case "a" => acc = 5; acc
    case _   => 0
def main() = println(f("a"))

F (native lane)          <closure>    <- wrong, and not even the right shape
reference front (legacy) 5
v1 interpreter           5
```

Another silent wrong answer, not a decline. It is deliberate as far as it goes — `armBodyExpr`
routes an assign head away from the sequence path, and the comment at :1603 records why: the block
form of a leading assign is `(seq ..)`, not `(let ..)`, so it is a different lowering rather than
one more statement. What is missing is the CONTINUATION: after the `(seq ..)` the remaining
statements still have to be parsed, and today they are discarded.

Not fixed with its sibling on purpose: that claim was about `(global v)`, this needs the seq-form
lowering reasoned through separately, and no corpus file currently hits it. Worth doing before any
corpus file does, because the failure mode is a wrong answer rather than a refusal.

## f-ordered-match-arm-body-is-not-a-statement-sequence

<!-- status: fixed
     lane: native
     area: front
     reported-by: claude-code
     reported-at: 2026-08-08
     ssc-version: 59f2145df
     confirmed: yes
     fixed-in: 1f708ae22
     gate: tests/e2e/f-global-v-gate.sh -->

Front F treated the body of an **ordered-resolver** match arm as a single expression. Two defects
followed from that one gap, and the quieter one is the worse one.

**1. A multi-statement arm body silently returned its FIRST statement.** No decline, no diagnostic,
wrong answer. Measured across three lanes on the same file:

```
def f(k: String): String = k match
  case "a" => "xy".length; 3.toString
  case _   => ""
def main() = println(f("a"))

F (native lane)          2      <- wrong
reference front (legacy) 3
v1 interpreter           3
```

**2. A `val` written inline in an arm body leaked out of the arm.** `parseExpr` consumed no `val`,
so the remaining tokens were read by the TOP-LEVEL item parser as a top-level val. The entry then
emitted `(prim cell.set (global n__cell) …)` while `collectTopVals` — which scans top level only,
correctly — never declared that cell, and F declined the whole file over an unbound global **it had
invented itself**.

**The reported name never named the cause, and differed by initializer.** With a closed initializer
(`val n = 1`) the invented cell surfaced: `unbound global (global n__cell)`. With an initializer
mentioning an enclosing parameter (`val n = _lenOf(v, theme)`) that parameter is unbound at top
level and surfaced *first*: `unbound global (global v)`. One mechanism, two symptoms, neither
naming the construct at fault — and `v` is what the corpus reported, which is why this was filed
against sixteen frontend examples that do not contain the construct at all.

**Where it actually was: one module.** `runtime/std/ui/lower.ssc`, in `_propCss`:

```
case "paddingX" => val n = _lenOf(v, theme); s"padding-left:${n}px;padding-right:${n}px;"
```

Every frontend example imports it, so every one inherited the decline. Asking each module for its
own `--front-report` verdict is what located it; the sixteen consumers were noise.

**Why the first fix did nothing, and the lesson worth keeping.** The obvious repair — teach
`armBodyExpr` about `val` — built clean, changed no behaviour whatsoever, and I nearly read that as
"the mechanism story is wrong". It was not: `armBodyExpr` belongs to `parseCtorMatch`, and **which
resolver a match uses is decided by its FIRST pattern** (`parseMatchArms` :1556). A string literal
routes to `parseGenMatch`, whose ten arm-body sites each called a bare `parseExpr`. So `case "a" =>`
and `case Some(n) =>` are the same construct through two parsers with two separate body handlers.
Every test in the first version of the gate matched on a String and never reached the code I had
edited — a green gate over an untouched path.

**Fix:** the ordered resolver's arm bodies go through `armBodyExpr`, the ctor path's existing body
parser, so the two families agree instead of drifting — `parseGenCtorPlain`, `parseGenInt`,
`parseGenLit`, `parseGenUnit`, `parseGenVarPlain`, `parseGenVarGuard2`, `parseGenWildPlain`,
`parseGenWildGuard2`, `parseTypedWild`, `parseTypedBind`. Guards are deliberately NOT sequences.
A single-expression body is byte-unchanged (`armSeqCont` hits the arm boundary immediately). New
`isArmValHead`/`parseArmVal` handle the val head, shaped after `parseDBlockVal` (:2441), and are
wired into BOTH `armBodyExpr` and `armSeqStmt` — the second is the same construct one statement
later (`a; val n = e; b`), and without it the leak survives behind a leading statement.

The guard was taught a statement, not widened: an arm body naming something undefined is still
refused, and now names it — before the fix the invented `n__cell` masked the real undefined name.

## build-rust-drops-defs-it-cannot-lower-without-saying-so

<!-- status: open
     lane: v2-rust
     area: codegen
     reported-by: rozum (claude-code), meeting room 'scalascript'
     reported-at: 2026-08-08
     ssc-version: bde14b2eb
     confirmed: no
     gate: none -->

Routed from `INBOX.md` `build-rust-std-json-cons`. rozum reports that `build-rust` cannot compile
any program touching `std/json`; downstream a production PWA (`clients/meeting`, serving :8405) has
a binary from 2026-06-29 that **cannot be rebuilt** — the running artifact is the only copy.

**Reproduced, minimally:**

```
$ cat /tmp/jc2.ssc
[jsonCoreRenderFields](std/json-core.ssc)
def main(): Unit = println(jsonCoreRenderFields(List()))

$ ssc-tools build-rust /tmp/jc2.ssc -o /tmp/out
error[E0425]: cannot find function `jsonCoreRenderFields` in this scope
```

**Two distinct problems, and the first is not the one reported.**

**1. The diagnostics are gone.** rozum saw three explicit messages naming the cause:

```
Generic(def `jsonCoreRenderFields` has unsupported pattern: Term.Name (Nil),Some(rust))
Generic(def `jsonCoreRenderFields` extracts `Cons` which is not a known enum constructor,Some(rust))
Generic(def `_normSegments` uses unsupported infix operator `::`,Some(rust))
```

Today there are **zero** ssc-level diagnostics — the whole log is nine lines of rustc. The function
is silently omitted from the crate and the user gets a missing-symbol error pointing at their own
call site. `BuildRustCmd` does print `CompileResult.Failed(diags)`, and `RustCodeWalk` does return
`Left(allErrs)` when a def fails to render, so the def is being dropped BEFORE it reaches
`renderDef` — the swallow is upstream of both. That is a worse failure than the reported one: the
message that named the real cause has been replaced by one that points at innocent code.

**2. The lowering gap is real and still there.** `RustCodeWalk` refuses exactly the reported
constructs, at four sites:

| line | refusal |
| --- | --- |
| 3451 | `has unsupported pattern: <other>` — `Nil` as a pattern |
| 3422 | `extracts <ctor> which is not a known enum constructor` — `Cons` |
| 3510 | `uses unsupported infix operator <op>` — `::` (allowed set is arithmetic/comparison/boolean) |
| 2511 | `contains an unsupported infix expression` |

So the Rust backend has no List lowering. `_normSegments` (in `std/fs.ssc`, **not** json — the
report's grouping is misleading) uses `h :: stack`; `jsonCoreRenderFields` matches `case Nil` and
cons.

**Note for whoever takes this:** the report suggests the JVM lane's 2026-08-05 `Cons` work might
just need applying to `genRust`. That is worth checking but should not be assumed — these four
refusals are in the Rust walker's own pattern/infix rendering, not in shared lowering.

**Not reproduced from rozum's own program** — the minimal probe above was built instead. Their
`build.sh` needs their checkout.

## scaffolded-projects-cannot-load-their-build

<!-- status: fixed
     lane: apparatus
     area: cli
     fixed-in: 6979fd870a189a6d71dd92756c0a31e125bbae63
     gate: tests/e2e/scaffold-loads-its-build.sh -->

`ssc new <name> --template app` produces a project that fails on its first command. Reproduced, not
inferred:

```
$ ssc-tools new demo --template app
Created app project: /tmp/scaffold-check/demo
$ cd demo && sbt compile
[error] sbt.librarymanagement.ResolveException: Error downloading
        org.scalascript:sbt-scalascript-interop;sbtVersion=1.0;scalaVersion=2.12
[error]   Not found
```

**Five of six templates** (`app`, `web-app`, `wasm-app`, `lib`, `dsl`) both request the plugin in
`project/plugins.sbt` and call `enablePlugins(ScalascriptInteropPlugin)` in `build.sbt`, so the
dependency is real — removing the line would break the build differently. Only `plugin` is unaffected.

**Three facts that do not line up:**

| | |
| --- | --- |
| templates ask for | `org.scalascript % sbt-scalascript-interop % **0.1.0**` |
| the plugin build produces | `0.1.0-SNAPSHOT` |
| published anywhere | **nothing** — no workflow, no script publishes it |

So aligning the version alone does not fix this: `0.1.0-SNAPSHOT` is equally unresolvable. The
question underneath is whether this plugin is meant to be published at all, which is a product
decision rather than a defect to patch.

**Fixed that way, on Sergiy's decision:** `install.sh` now `publishLocal`s the plugin, and the five
templates name `0.1.0-SNAPSHOT` — the version that build actually produces, instead of a `0.1.0`
nothing ever made. Gated by `tests/e2e/scaffold-loads-its-build.sh`, which scaffolds a project and
runs `sbt update` in it: checking the template FILES would not have caught the original defect,
because the coordinate was well-formed and simply named an artifact that did not exist.

Falsifiable, measured: remove the local publish from `~/.ivy2` and the gate fails with the
resolution error; restore it and it passes.

**Still open, deliberately:** whether this plugin should be published for real. Until it is,
`ssc new` works for people who build ssc from source and not for anyone else.

Related: this is the third site in the same family — `Main.scala`'s emitted coordinate and
`uniml/build.sbt` were the other two, both fixed and now gated by
`tests/e2e/emitted-coordinate-is-published.sh`. That gate deliberately does not cover the plugin,
because until it publishes there is no correct version for it to name.

## smoke-sbt-setup-skipped-on-cache-hit — every cache-HIT smoke run is red, and only those

<!-- status: fixed
     lane: apparatus
     area: build
     gate: .github/workflows/smoke.yml
     fixed-in: fb66987bc -->

`.github/workflows/smoke.yml` gated `Setup sbt` on `steps.toolchain.outputs.cache-hit != 'true'`,
with the reason written next to it: *"sbt is only needed to BUILD. On a toolchain-cache hit it is
pure setup cost for a step that will not run."* Two smoke CHECKS shell out to `sbt` at RUN time —
`tests sbt-plugin-scripted` and `tests scaffold-loads-its-build` — so on a cache hit both failed with
`sbt: command not found`.

**Measured on two runs twenty minutes apart, same cache key:**

| run | cache | `sbt-plugin-scripted` | verdict |
| --- | --- | --- | --- |
| 31251679562 (b86af8375) | `Cache not found for` | passed, 104.8s | 71/72, red on an unrelated check |
| 31252689448 (a3896e549) | `Cache restored from key` | **FAIL in 0.0s** | 70/72 |

**Why it survived.** A push that changes compiler sources changes the launcher digest and therefore
MISSES the cache — verified, not assumed: appending a byte to
`v1/.../interpreter/EvalRuntime.scala` moves `scripts/launcher-input-digest` from `6e4a0a15…` to
`6d88c30f…`. So every run that exercised the build had sbt and was green, and the runs that went red
were docs and bookkeeping pushes, which is the class nobody re-reads. The failure is deterministic,
not flaky; it just selects for the commits least likely to be investigated.

**Same shape as the comment directly below it**, which records the coursier version of this mistake
("NOT cached — only coursier/ivy/sbt were… making it conditional was a real regression that took
three red runs to find"). Both are a setup step gated on *"is this a build?"* when what it feeds is
the SUITE.

**Budget risk, stated rather than discovered later.** On a cache hit the two checks now RUN instead
of failing instantly, which adds roughly 105 s + 25 s to a suite that used 528 s of its 600 s cap on
that run. If the cap starts firing, that is `smoke-suite-over-its-own-budget`, not this — and the
suite reports its own budget line, so it will say so.

## sbt-plugin-fixtures-deleted-by-an-unrelated-commit-and-unrestorable

<!-- status: fixed
     lane: apparatus
     area: build
     fixed-in: 30113bf08e46c4818d3d443a838770588ab8ca6a
     gate: v1/tools/sbt-plugin scripted -->

The plugin is in no build — not aggregated, not in CI, not in smoke — so its ten `scripted`
scenarios had never been run by anything. Running them: **two red, and a third green for the wrong
reason.**

**In order.**

1. The `.ssc-artifacts/*.scim` fixtures existed and **survived** the `tools/ → v1/tools/` move in
   `b433a41e4`.
2. `1fea89a79` — *`fix(js): record leaderHistory on every accepted claim`* — **deleted all four**.
   A JS change, nothing to do with sbt. Almost certainly a stray `git add -A`.
3. Nothing runs these tests, so it went unnoticed for three weeks.
4. They could not simply be restored: `.gitignore` ignores `.ssc-artifacts/`, and its exception
   still read `!tools/sbt-plugin/…` — anchored at the repo root, so it stopped matching at step 1.
   The rule meant to protect the fixtures had rotted in the very move they survived.

**The symptom.** `sscGenerateFacade` skips with a warning when `sscArtifactDir` is absent:

| scenario | was | why |
| --- | --- | --- |
| `basic` | FAIL | asserts the facade exists; generation was skipped |
| `multi-module` | FAIL | same |
| `identity` | **PASS, blind** | asserts the facade does NOT exist — true, because nothing ran |

`identity` claimed "generation succeeds and emits no files" while observing "generation never
happened". It now also asserts the output DIRECTORY exists — created by the task before it invokes
the binary, which separates "ran, wrote nothing" from "never ran".

**Fix:** the four original fixtures restored from `b433a41e4` — real artifacts (`"magic": "SSCART"`,
`abiVersion 2.0`), not stand-ins that would satisfy the guard without testing the format — and the
ignore exception made path-independent (`!**/src/sbt-test/**/.ssc-artifacts/`).

10/10 green. Falsifiable: remove `basic`'s fixture → rc=1; restore → rc=0.

**Still open, and the reason this was invisible: nothing builds or tests this plugin.** Wiring it
into a suite is the follow-up — `scripts/smoke-ci.ssc` is held by another claim.

# Test harness — bugs

Scope: defects whose FIX goes in `tests/`. Layout and routing rules:
`specs/work-tracking-layout.md`. Entry format (the header is parsed, the prose is not):
`specs/bugs-index.md`. Query with `scripts/bugs-report --module tests`, never by
grepping for status.

Newest first.



## both-unbound-is-mostly-plugin-intrinsics-not-user-error — the label blames the program; the names say otherwise

<!-- status: open
     lane: apparatus
     area: front
     gate: - -->

`ssc info --front-report` classifies a file `BOTH-UNBOUND` when the REFERENCE front also fails
`validateNoReader`, and the surrounding comment reads that as "the file is broken whichever front
reads it" — the user's own program. On a 140-file sample that is 64 files, the largest single group.

**It reported the wrong front's reason, which is why nobody could check that reading.** The verdict
is decided by the reference front's failure and the row printed `fFailure`, F's reason. Fixed here:
a `BOTH-UNBOUND` row now carries both, F's first and then `||REF: <reason>`.

With the reference front's own objections visible, the 64 break down as:

    10  (global __yamlSection__)      4  (global _println)       3  (global NamedHandler)
     5  (global JsonCodec_derived)    3  (global self)           2  (global VertexCodec_derived)
     4  (global scope)                3  (global route)          2  (global sqlSection)
     4  (global awaitClient)

These are not typos. `__yamlSection__`, `_println`, `sqlSection` and `route` are plugin-provided
intrinsics, and `*_derived` are synthesised typeclass instances — names the RUNTIME binds, in the
same family as the declared `extern def`s that `f-validateNoReader-rejects-plugin-externs` covered
and that `validateNoReader` now accepts.

So the largest group in the front report is mostly NOT user error and NOT F's fault; it is the same
validator blind spot one layer further out. Two consequences: the `BOTH-UNBOUND` label as documented
is misleading, and the corpus coverage picture is better than the numbers suggest, because these
files run correctly — the reference front compiles and executes them, it is only the static
pre-check that objects.

**ONE MECHANISM WAS AUTHORISED, IMPLEMENTED, MEASURED AND REVERTED — record it so it is not tried
again.** The idea: since `BOTH-UNBOUND` means the REFERENCE front fails the same check, F's program
is "no worse" and should be kept instead of delegating. It is wrong, and cheaply so.

Built it (keep F's rejected program, use it when `userErrorNotGap`), then RAN eight of the affected
files instead of trusting the reasoning:

    3 of 8   ran as before
    5 of 8   died with `unbound global: msg / v / of / s` — names F alone cannot bind

And the decisive pair, same file both ways:

    examples/frontend/components-demo   F: unbound global: s      legacy: runs

So both fronts failing the STATIC check does not make them equal: the reference front's failure is
on a runtime-provided name it then binds at execution, while F's program carries ADDITIONAL unbound
names that are real gaps. "Both fail validation" and "both are equally usable" are different
statements, and this conflated them.

Reverted, nothing landed. What a real fix needs is what the entry above already said — the actual
registry of names the runtime binds, consulted per name, not a same-verdict shortcut. `pluginNativeNames`
exists in the interpreter (`Interpreter.scala:232`) but is `private[interpreter]` and belongs to the
wrong lane; the native side has no equivalent table, and `__yamlSection__` is not a plugin name at
all — the RUNNER synthesises it (`v2/bin/ssc1-run.ssc0:187`). Those three sources would have to be
unified first.

Not fixed here, and the reason is the same one the extern entry recorded: widening the guard to
accept plugin intrinsics trades a loud misclassification for a silent wrong answer if the name is
genuinely absent, so it wants the registry consulted rather than a pattern match on leading
underscores. Owner call, like its predecessor.

## uniml-version-drifted-from-root — the standalone build would publish two versions of one artifact

<!-- status: fixed
     lane: apparatus
     area: build
     gate: uniml/scala/.../UnimlCoordinatesSpec.scala
     fixed-in: f060e9b9fd9f2ac40598fc029fd4bf45e8e72af0 -->

**FIXED 2026-08-06.** `uniml/build.sbt` now carries `0.2.0-SNAPSHOT`, matching the root. Sergiy's
call, which is why it was filed rather than guessed: the bumping commit said the emitted coordinate
deliberately stays at the release, so the version UniML carries was a policy decision and not
something for whoever tripped over the red to pick. Asked, answered, applied.


`cd uniml && sbt -batch test` is RED on `origin/main`, and it is the check that exists for exactly
this:

    version drifted: standalone '0.1.0' vs root '0.2.0-SNAPSHOT'.
    The same sources would publish as two versions of one artifact.

`24581733e` ("build: main to 0.2.0-SNAPSHOT, and the emitted coordinate stays at the release")
bumped `ThisBuild / version` in the ROOT `build.sbt` and left `uniml/build.sbt` at `0.1.0`. UniML is
built standalone by the nightly `UniML — standalone build` job, which runs that exact command, so
this fails the whole job for everyone until it is reconciled.

**Isolated rather than assumed.** Found while landing an unrelated parser fix; stashing that change
and re-running `uniml/testOnly *UnimlCoordinatesSpec` fails identically, so it is inherited and
nothing in the ScalaScript dialect is involved.

**NOT fixed here on purpose, and the one-line fix is the trap.** Setting `uniml/build.sbt` to
`0.2.0-SNAPSHOT` makes the check green in one move — but that commit's own message says the emitted
coordinate deliberately STAYS at the release, so which version UniML should carry is a decision
belonging to the release work, not a guess to be made by whoever trips over the red. Reconcile it
where the version policy lives.

## smoke-check-guards-sized-by-local-time — two checks killed by their own guard, and the runner will not say so

<!-- status: fixed
     lane: apparatus
     area: build
     gate: scripts/smoke-ci.ssc
     fixed-in: c1d29e7cc -->

**FIXED 2026-08-06 — both remaining items.** `freeze-consistency` was closed earlier (`d0df30a89`,
and the diagnosis corrected with it); the two the entry left open because `scripts/smoke-ci.ssc` sat
inside another claim are done now that it is free.

**1. The guard is sized by MEASUREMENT, not by a dev host.** `run-lane-flags-are-flags` spawns
TWELVE JVMs — two launchers × five lane-flag combinations, plus two unknown-flag probes — each a
cold start plus a compile. On CI, across GREEN runs:

```
45d6148b5  51.3s     59dcabf82  51.3s     5a4ea14ab  47.2s     5903776db  54.0s
4d8b5ae7b  60.1s  ->  TIMED OUT against a 60 000 ms guard, main RED
```

so the guard sat at ~85 % of the check's ordinary cost and ordinary runner variance tipped it over.
`60000 -> 180000`, which is what the other multi-spawn checks here use, and it **costs the budget
nothing**: `timeoutMs` is a ceiling, not a cost.

The file's own rule — *"a check that approaches it is a check to look at, never a number to raise"* —
is right, and it was followed rather than waived: the twelve spawns ARE the assertions, and the flag
PAIRING is the point of the gate (the missing `--interpret` was found only because its partner
existed), so there is nothing to cut without dropping coverage. The check is honest; the guard was
sized against a dev host, which is this entry's title.

**2. A killed check now says it was killed.** `-1` was never in the runner's legend (124 / 127 /
137), so a timeout printed a bare `exit code -1` with no stdout and no stderr — three facts that read
as a crash. Exercised rather than assumed, with a one-check suite and a deliberate sleep:

```
FAIL deliberate-timeout-probe          1.0s
       | exit code -1 — TIMED OUT against its 1s guard
```

127 and 137 got the same treatment while the branch was open.

**Note the probe that did NOT work, because it looked like it did.** `ssc info --front-report` on
the modified suite reports `BOTH-UNBOUND: unbound global: readFile` — and reports exactly the same
for the UNMODIFIED file, because `readFile` is a plugin extern a static pre-check cannot see. Without
that control it reads as "my edit broke the file". The real check is running the suite.

Full suite after the change: 69/69 green, 281.2 s of 600 s.

### Original report (superseded 2026-08-06)

Main has been red since 6f8e3a98f (runs 31060534903, 31060671890 — the same two failures on both,
so the second commit inherited them). `66/68 green`, and the two reds are not logic failures:

    check                      local   CI      guard        what CI shows
    freeze-consistency           2.0s   94.5s  120000 ms    exit 1, 4 stdout lines, NO stderr
    run-lane-flags-are-flags    24.0s   60.1s   60000 ms    exit -1, no stdout, no stderr

`run-lane-flags-are-flags` is unambiguous: 60.1 s measured against a 60 000 ms guard is the guard
firing, and `exit -1` is what the runner reports for it — a value its own legend does not cover
("124 is the timeout guard firing, 127 a command that could not start, 137 a kill").

**CORRECTION, after fixing it (d0df30a89): `freeze-consistency` was NOT a guard-sizing failure.**
It exited 1 at 94.5 s against a 120 000 ms guard — inside its budget. The cause was `set -euo
pipefail` plus an assignment ending in `grep -v '^$'`, which exits 1 when nothing survives the
filter. `set -e` then killed the gate at that line, and the statement immediately after it is the
`if [ -z "$listed" ]` branch that exists to report exactly this case — unreachable code. Repro:
`set -euo pipefail; x="$(printf '' | grep -v '^$')"; echo unreachable` prints nothing, exits 1.
Emulating a `scala-cli` that exits 1 reproduced the CI signature down to which stdout line is last.
Fixed with `|| true` inside the substitution. The timing below still stands and still matters — it
is WHY scala-cli fails on a runner and never here — but it was the trigger, not the defect.

`freeze-consistency` is the sharper number: **2 s locally, 94.5 s on CI, a 47x ratio.** The
previous worst known in this repo was 19x (`launchers-not-dead`, 5.5 s → 104.5 s). Cause is not
mysterious — the gate shells out to `scala-cli tests/conformance/contract.sc -- --list`, which is
0 s here against a warm cache and a cold resolve-and-compile on a runner. Both its internal
`timeout 120` and the runner's 120 000 ms guard were sized as if local timing were indicative of
anything.

**The part that costs the most is neither: the runner cannot tell you why a check failed.** For
both reds the reason is absent from the FULL log, not just `--log-failed`. `exec` is not at fault —
probed directly, it captures stdout and stderr separately and correctly:

    exitCode = 1
    stdout   = [on stdout: line one]
    stderr   = [on stderr: THE REASON \n on stderr: second reason line]

So a check that is KILLED loses its stderr entirely, and `freeze-consistency` writes every one of
its failure messages to stderr — including its own summary line. A red that says only `exit code -1`
sends the next person to reproduce locally, where both checks pass in 2 s and 24 s.

Three things worth doing, smallest first:

1. Name the timeout. When the elapsed time reaches `timeoutMs`, or the exit code is negative, print
   `TIMED OUT after Ns (guard Ms)` instead of a bare number. This alone turns both reds into
   self-explaining ones.
2. Size the two guards off CI numbers, not local ones. A guard at 2.5x the CI observation, not 2.5x
   the local one.
3. ~~`freeze-consistency` should print findings as it makes them.~~ **DONE, d0df30a89** — and it
   turned out to be the actual defect rather than a diagnosability nicety, see the correction above.
   It also now prints a breadcrumb before the slow `scala-cli` step, which is the only thing that
   survives when the runner's guard kills a check.

**Residual, and it is a real one: the skip d0df30a89 introduced cannot be seen.** The runner prints
output only for a check that FAILS, so a passing `freeze-consistency` produces no lines at all —
and "I5 ran and the roster is clean" is therefore indistinguishable from "I5 skipped because
`contract.sc --list` produced nothing". The fix turned a silent RED into a silent SKIP, which is
strictly better (main is green, and the other five invariants still run) but not yet honest.

What the logs can and cannot settle, so nobody re-derives it:

    run 31060671890  toolchain cache HIT   freeze-consistency 94.5s  died silently (the set -e bug)
    run 31082837072  toolchain cache MISS  freeze-consistency 11.1s  passed, branch UNKNOWN

**Third observation 2026-08-06, and it settles the SHAPE if not the cause.** `freeze-consistency`
timed 94.5 s (run 31060671890), 11.1 s (run 31093483796), 94.8 s (run 31094926802) — it swings
between roughly 11 s and 95 s from run to run, so this is not a monotonic cache story at all and the
hit/miss correlation in the table below is coincidence on two samples. The 95 s runs are consistent
with the intermittent Bloop download already documented in
`corpus-breadth-slice-crashes-scala-cli-on-ci` ("three of the last fifteen runs").

Worth noting for the budget question: that 84 s swing is most of the 482 s -> 634 s jump between
those last two runs, so a suite that measures 634 s and blames the change in that push is usually
blaming the wrong thing. And on the 94.8 s run `freeze-consistency` PASSED — before d0df30a89 the
same condition killed it silently with no reason in the log.

The obvious reading — cache hit skips the coursier step, so scala-cli has to fetch Bloop — is
WRONG here: `Cache Coursier/sbt` in smoke.yml carries no `if:`, it was made unconditional by
5d397bd26 precisely because of `corpus-breadth-slice-crashes-scala-cli-on-ci`, and that is still
the case. The 8.5x timing difference is unexplained, and the intermittent scala-cli download
failure documented in that entry ("three of the last fifteen runs") is enough to produce it without
any cache story.

To settle it, the gate has to report the branch through the only channel a passing check has —
which is none. So either I5's outcome moves into the check NAME the runner prints, or the gate
fails when I5 cannot run under `CI`. The second is defensible (the workflow installs scala-cli
deliberately, so an inability to list is a real defect there and a legitimate skip on a dev host
without it) but it puts main red for an environment flake, so it is a decision, not a cleanup.

Items 1 and 2 NOT DONE HERE because `scripts/smoke-ci.ssc` is inside the `release-graalvm-pin`
claim. They remain worth doing: `run-lane-flags-are-flags` is still a genuine guard failure (24 s
local, 60.1 s against a 60 000 ms guard), and a killed check still reports a bare `exit code -1`.

Note this is NOT the same defect as `smoke-suite-over-its-own-budget`, though both runs are also
over budget (627.6 s and 629.1 s of 600 s). That one is about total wall time with every check
green; this is two checks going red for being sized against the wrong host.

## launcher-digest-gate-is-225s-of-a-500s-budget — two processes per line, 7272 lines

<!-- status: fixed
     lane: apparatus
     area: build
     gate: tests/e2e/launcher-digest-gate.sh
     fixed-in: 99f335023 -->

`scripts/launcher-input-digest` extracted the sha from each `git ls-tree` line with

```
printf '%s\t%s\n' "$(printf '%s' "$meta" | awk '{print $3}')" "$path"
```

— a command substitution spawning **two processes per line, over 7272 lines**: roughly 15,000
process spawns per digest. The git commands themselves cost 0.01–0.08 s; the spawning was the entire
runtime.

| | before | after |
|---|---|---|
| one `launcher-input-digest` run (local) | 11.3 s | **0.60 s** |
| `tests/e2e/launcher-digest-gate.sh` (local) | 5 m 34 s | **19.7 s** |
| the same check on CI | 225.5 s / 227.0 s | expected ~13 s |

The fix is `${meta##* }` — ls-tree meta is `<mode> <type> <sha>`, so the sha is the last
space-separated field and bash takes it with no process at all. **Verified equivalent on real data
rather than by reading**: for all 7271 `ls-tree` lines in this repo, `${meta##* }` and
`awk '{print $3}'` agree on every one.

**Why this mattered beyond being slow.** At 227 s this single check was 27% of the smoke suite and
3× the next one, and the suite ran at **467.6 s against its own 500 s cap** — 32 s of headroom on a
path whose measured variance is larger than that. One CI instance took 823 s and the job went red
with all 60 checks green, which reads to every agent as "main is broken". Removing ~214 s turns a
suite that flaps at its ceiling into one with roughly half the budget spare. Related, and left to
its owner: `smoke-suite-over-its-own-budget`.

The digest VALUE changes with this commit, which is correct and worth stating: the script is one of
its own inputs (verified), so editing it must change the digest — that is the property the digest
exists to have.

Loops 2 and 3 (`git diff HEAD` / `git ls-files --others`) still run one `git hash-object` per file.
Left alone deliberately: both lists are empty in a clean checkout, so they cost nothing today, and
batching them via `git hash-object --stdin-paths` is a change with no measurement behind it here.

## launcher-digest-gate-backslash-t-is-not-a-tab-in-ere — green on this machine, red on CI, main blocked for two hours

<!-- status: fixed
     lane: apparatus
     area: build
     gate: tests/e2e/launcher-digest-gate.sh
     fixed-in: 4cccc6ce4 -->

`tests/e2e/launcher-digest-gate.sh` asserted that every digest input line looks like
`<sha>TAB<path>`:

```
grep -vE '^  ([0-9a-f]{40}|deleted)\t'
```

**`\t` is not a tab in POSIX ERE.** GNU grep reads it as the letter `t`, so the pattern became
`^  ([0-9a-f]{40}|deleted)t`, NOTHING matched, and `grep -v` handed back EVERY line as malformed —
reported as `the digest still depends on git state`, which is the opposite of what was wrong.

**It passed locally because this machine's `grep` is ugrep 7.5.0**, which does accept `\t`. Measured
side by side on the same line: ugrep matches with `\t` and with a literal tab; GNU grep only with a
literal tab. So the gate was green for its author and red on CI from 15:09 to 17:20 — and a red
`main` costs every agent evidence level 1, not just the one who pushed.

**Fixed** by writing a real tab through bash `$'...'`, which both implementations expand before grep
ever sees it.

**Guard added, and it is the interesting half.** This gate cannot catch the bug by RUNNING itself —
on ugrep the broken pattern works fine. So the guard asserts the property that holds on every host:
no `grep -E` pattern in this file may contain a backslash-t, discriminating `$'...\t'` (bash expands
it, correct) from `'...\t'` (grep sees two characters, broken). Comment lines are skipped so the
paragraph explaining the trap does not trip it. Verified both ways: clean file passes, and removing
the `$` from the fixed line makes the gate fail with `ere-backslash-t`.

Found while investigating a CI red on my own push (`0f3f7c540`). It was NOT mine — the first red was
`45babaee6`, an hour earlier — but my local smoke had reported this same check as a 240s TIMEOUT
under host load, which masked the real failure. Two lessons in one: a timeout hides a content
failure, and "passes locally" is worth nothing when the tool is a different implementation from the
one CI runs.

## smoke-runner-cannot-run — a poisoned classpath cache, and a control that shared it

<!-- status: fixed
     lane: multi
     area: runtime
     fixed-in: e714c31ab
     gate: scripts/smoke-ci -->

`scripts/smoke-ci` died before its first check with

```
ssc: class scala.Tuple2 cannot be cast to class scala.collection.immutable.List
```

**No longer reproduces.** On current main: the suite runs 70/70 green.

**The cause was not a runtime regression, which is what I filed.** `e714c31ab` describes it exactly:
`uniml_classpath` cached a *directory path*, and scala-cli rewrites that directory on every compile
of the same inputs — so

```
A -> compile -> cache[A] = P, P holds A
B -> compile -> cache[B] = P, P now holds B     (shared, overwritten)
back to A -> HIT on cache[A] -> serves P, holding B
```

A class file from a different compilation state is precisely how you get a cast error between two
unrelated types. It hit me because I had been rebuilding across tree states all session — release
branch, main, revert, main — which is the A → B → A pattern that triggers it.

**The instructive part is why my control did not catch it.** I reverted my change, rebuilt, re-ran,
got the identical failure, and concluded "main is broken, not me". That inference needs the two arms
to differ only in my change — and they did not: both shared the same poisoned cache, so the control
could not exonerate anything. A control that shares the contaminated resource is not a control, it
is the same measurement twice.

What did hold up: the narrowing. Running the previous `smoke-ci.ssc` under the failing toolchain
showed the script was innocent, and testing all three fronts showed it was below the front. Both
were true and both pointed at shared state — I read them as pointing at shared *code*.

## compile-jvm-shipped-broken-in-the-native-binary — resources a native image never carried

<!-- status: fixed
     lane: native
     fixed-in: 1bd9374d444b8c7cbc0ee1b3e644166a0ce6b2dd
     area: build
     gate: scripts/native-release-qualify -->

`ssc compile-jvm` worked on the JVM launcher and failed in the **native binary on all three
platforms**, including the published v0.1.0:

```
compile-jvm error: JVM runtime resource not found on classpath:
  /scalascript/jvm-runtime/stubServeRuntime
```

The command inlines Scala source fragments that live as classpath RESOURCES. A native image carries
only the resources named in `resource-config.json`, and none of these were named.

**Three families were missing, and each surfaced only as the next failure of the same command** —
one per 2.5-minute image build: `scalascript/jvm-runtime`, then `http-server-spi-sources`, then
`http-server-common-sources`. They are produced by `resourceGenerators`, so they never appear under
`src/main/resources` and a scan of the tree does not find them. Registered as one pattern,
`[A-Za-z0-9-]+-sources/.*`, rather than three entries: build.sbt generates four such roots today and
the wildcard covers the fifth nobody has written yet.

**How it stayed invisible.** Nothing exercised `compile-jvm` on the native lane. I nearly filed it as
noise myself: seeing the identical failure on two differently-built binaries, I concluded it was my
probe environment. That inference was wrong in a specific way worth remembering — the failure was
identical because **neither** image had the resource, not because my setup was at fault. A control
answers the question it was asked (did my change break this?) and not the wider one (does this work
at all?).

Now qualified: `compile-jvm` runs in the release qualifier and must leave a non-empty `.scjvm`
artifact, with two self-test mutations (`cjvm-exit`, `cjvm-artifact`). Self-test 64 cases.

## native-release-native-image-three-defects — the stage nothing had ever reached

<!-- status: open
     lane: apparatus
     area: build
     gate: .github/workflows/native-release.yml -->

With the pipelining blocker fixed (`native-release-blocked-by-testutils-clean-compile`), run
`30954908133` became the first ever to reach `native-image`. All three runners fail there, and the
log separates into **three independent defects** — the memory one masks the others, so they must be
fixed in order.

**1. `build.sbt`'s native-image config paths point at a directory that does not exist.**
The configs live at the repo root, `./native-image-configs/`. `build.sbt:2347-2348` builds
`(cli / baseDirectory) / ".." / ".."`, and cli's base is `v1/tools/cli`, so that resolves to
`v1/native-image-configs` — **missing**. The workflow's `set` line supplies `".." / ".." / ".."`
(the root), which is correct. That override is not a diagnostic hack to be deleted, as its comment
implies: it is the only reason the configs load at all. Fix `build.sbt` FIRST, or removing the
override silently drops reflection and resource configuration.

**2. The forced heap is below what the tool asks for, on every runner.**
The same `set` line strips `-J-Xmx` and forces `-J-Xmx5g` (build.sbt asks for `8g`). native-image
states its own requirement in the log:

| runner | system RAM | given | native-image asks for |
| --- | --- | --- | --- |
| ubuntu-latest | 15.61 GB | 4.44 GB | > 6.04 GB |
| macos-15-intel | 14.00 GB | 4.44 GB | > 5.69 GB |
| macos-latest (arm64) | **7.00 GB** | 4.44 GB (63.5%) | not printed |

linux and arm64 die as `Image generator watchdog detected no activity` with used == maximum heap
(4700 and 4918 MB) — starvation, not deadlock. **`8g` is not the answer either:** it fits linux and
intel but is 114% of the arm64 runner's 7 GB. The heap has to vary per runner, or arm64 needs a
bigger machine.

**3. A genuine image-heap defect, visible only on the runner that got furthest.**
macos-15-intel had enough memory to finish analysis and then failed differently:

```
Image heap writing found a class not seen during static analysis
  object: DirectSubstrateObjectConstant[Object]
  reachable through: java.util.concurrent.ConcurrentHashMap$Node[]
  object: {sun.util.resources.LocaleNames-en-US=…, sun.text.resources.cldr.FormatData-nb-NO=…}
  root: sun.util.resources.LocaleData$LocaleDataStrategy.getCandidateLocales(String, Locale)
```

JDK locale data cached at build time and not seen by static analysis. **Expect this to become the
blocker for all three once memory is fixed** — intel is not special, it just got there first.

**4 (found once 1 and 2 let two runners reach the qualifier): `run` parses `--interpret` as a FILE.**
`Main.scala`'s `run` parser ends in `case f => fileArgs += f`, so a flag it does not know becomes a
path. `--bytecode` was handled; its opposite `--interpret` / `--vm` was not, though `StandardMain`
has accepted both all along (`StandardMain.scala:52-53`). The native binary enters through
`@main def ssc` in `Main.scala`, so the qualifier's `ssc run --v2 --interpret release-probe.ssc`
died as:

```
java.io.FileNotFoundException: native frontend input not found: --interpret
  at scalascript.cli.RunNativeV2$.$anonfun$5(RunNativeV2.scala:133)
```

Fixed, with gate `tests/e2e/run-lane-flags-are-flags.sh` (in smoke, ~2 s) asserting all five lane
flags on BOTH launchers — `bin/ssc` (StandardMain) and `bin/ssc-tools` (`scalascript.cli.ssc`, the
native binary's entry). The first draft tested only `bin/ssc` and passed against the unfixed tree,
because that entry never had the bug: a probe whose subject is reachable without the thing under
test measures nothing.

**5 (FIXED 2026-08-06): `run` printed its diagnostics to STDOUT.** Measured before:

```
ssc-tools run --no-such-flag-xyzzy p.ssc  2>&1 >/dev/null   ->  (empty)
ssc-tools run --no-such-flag-xyzzy p.ssc  2>/dev/null       ->  Error: File not found: …
```

An error on the data stream: a caller capturing program output got the message mixed into it, and a
caller reading stderr saw nothing.

It was filed unfixed on the theory that moving the stream might break consumers matching on stdout.
A survey found none — every hit was prose inside a gate, not an assertion — and the same file
already contradicted itself: `EmitCommands.scala` printed this identical message to stdout at four
sites and to **stderr** at a fifth. That is not a contract, it is an oversight, which made the fix
safe rather than risky.

22 sites moved across `Main.scala` and `EmitCommands.scala`; zero `println("Error…")` remain in the
CLI. Verified: stdout is now empty and the message is on stderr. `run-lane-flags-are-flags` asserts
BOTH halves, so a regression fails rather than being noticed by someone's broken pipeline. smoke
69/69.

**Do not treat this entry as "the release is nearly done".** Defect 3 has never been attempted and
is the substantive one; 1 and 2 are bookkeeping in front of it.

## native-release-blocked-by-testutils-clean-compile — the release workflow has never produced a release
<!-- status: open
     lane: apparatus
     area: build
     gate: .github/workflows/native-release.yml -->

**Found 2026-08-04** cutting `v0.1.0`, the repository's first tag. The `Native Release` workflow
fires on `v*.*.*`, proves its refusal contract, then builds three GraalVM binaries. All three
`Qualify …` jobs fail in the **`Build native binary, frontend data, and plugin host`** step, and
`Publish qualified tag` is skipped — so **no release has ever been published** (0 tags before this
one; the only earlier run, `30316338197` on 2026-07-28, failed identically).

**The whole failure is one file.** 6 errors on Linux, 6 in each macOS job, nothing else:

```
v1/runtime/backend/test-utils/src/main/scala/scalascript/testkit/TestInterpreter.scala:3
  value backend is not a member of scalascript
  value interpreter is not a member of scalascript
  value parser is not a member of scalascript
```

All three imports missing means testUtils compiled with **none of its project dependencies on the
classpath**, even though `build.sbt:1394` declares `.dependsOn(backendSpi, backendInterpreter)`, and
the log confirms the target is testUtils' own (`compiling 1 Scala source to
v1/runtime/backend/test-utils/target/scala-3.8.3/classes`).

**It does not reproduce locally.** `sbt "testUtils/clean" "testUtils/compile"` in a worktree at the
tagged commit exits 0. So the difference is in *how CI builds*, not in the source: CI runs one
invocation — `sbt 'set cli / graalVMNativeImageOptions := …' "cli/installBin"
"cli/graalvm-native-image:packageBin" "pluginHost/assembly"` — and the `set` rewrites a setting on a
reloaded build state. Reproducing means running THAT command, not an approximation of it; every
local shortcut tried so far passes and therefore proves nothing.

**Not caused by the release commit.** `5c32e60bf` only drops `-SNAPSHOT`; the identical failure
predates it by a week. Worth stating because a version change is exactly the kind of thing that
*could* break resolution if any module depended on a published coordinate rather than `dependsOn` —
it does not, but that hypothesis should be retired explicitly rather than left hanging.

**Why it survived a week unnoticed:** the per-push suite is `smoke.yml`, which measures a staged
launcher and never builds the sbt graph from clean; the full `sbt — compile and test` job lives in
`ci.yml`, which on a push is `Lint Markdown` alone. A clean-build breakage is therefore invisible
until someone dispatches the full suite — or tags a release, which is how this surfaced.

**Done since, and it did not reproduce.** The exact CI invocation was run locally on a fully clean
tree (`sbt clean` + the `set` + `cli/installBin` + `pluginHost/assembly`): **rc 0**. So did
`testUtils/clean + compile`, and a clean of the whole dependency chain (`core`, `backendSpi`,
`backendInterpreter`). `testUtils` IS in the root aggregate, so `clean` does reach it; the tag's tree
and the local tree are identical for `build.sbt` and `test-utils` (nothing touched them after the
tag). The only difference left is the JDK: CI compiles under **GraalVM JDK 21.0.12**
(`JAVA_HOME=/opt/hostedtoolcache/graalvm-jdk-21…`), locally only Temurin 21.0.7/21.0.11 are
available, so the environment cannot be reproduced on this machine.

**Attempted fix, stated as a hypothesis because it has no local reproduction:** `testUtils` now
declares `core` directly. It already arrived transitively (`testUtils → backendInterpreter → core`),
so this changes nothing locally and cannot break — but it removes the only structural difference
between the failing imports and the declaration, and a module importing `scalascript.parser.Parser`
should name the project that provides it instead of relying on a chain. **The verification is the CI
run itself**; if the release still fails on the same three imports, transitivity was not the cause
and the next step is a diagnostic job printing `testUtils/dependencyClasspath` on a runner, since
that is the one thing no local run can answer.

**REFUTED 2026-08-04, run `30907972567`.** With `core` declared directly, the release fails
identically — `(testUtils / Compile / compileIncremental) Compilation failed`, same three imports.
Transitivity was not the cause. The declaration is kept because it is correct on its own merits (a
module should name the project providing a package it imports), but it fixes nothing and must not be
read as the fix.

**So the next step is now the only step:** a diagnostic job on a runner printing
`testUtils/dependencyClasspath` (and `show testUtils/internalDependencyClasspath`) before the build.
Every local avenue is exhausted — module clean, dependency-chain clean, full clean, and the exact CI
invocation all pass on this machine, and the environment differs only in the JDK (GraalVM 21.0.12 in
CI, Temurin locally). Nothing further can be learned without asking the runner what classpath it
actually assembled.

### 2026-08-04 — root cause: `usePipelining`, and why every local reproduction missed it

**Cause.** `ThisBuild / usePipelining := true` (build.sbt:54). Under pipelining, a module compiles
against its dependencies' *early* TASTy output. In the branch that builds the full artifact set —
the one that writes 35 `.pom` files and runs scaladoc — testUtils is compiled with that early output
absent, so all three of its imports resolve to nothing:

```
value backend is not a member of scalascript
value interpreter is not a member of scalascript
value parser is not a member of scalascript
```

testUtils is the module that shows it because **nothing depends on it in Compile scope**; it is only
ever reached by the artifact-collection path, never by a normal dependency walk.

**Proof — A/B, both sides from `git clean -xdf` (0 `target/` dirs), differing only in the flag:**

| run | result |
| --- | --- |
| full 3-command sequence, pipelining **ON** | `rc=1`, the E008 triple — CI's failure, reproduced |
| full 3-command sequence, pipelining **OFF** | 0 E008; testUtils compiles, scaladoc runs, build proceeds past it |

**Why every previous local reproduction passed.** The CI step is *three* sbt commands:

```
sbt 'set cli / graalVMNativeImageOptions := ...' \
    "cli/installBin" "cli/graalvm-native-image:packageBin" "pluginHost/assembly"
```

Local reproductions ran only the first. `cli/installBin` succeeds in 13 s and never compiles
testUtils — the poms start with the *second* command. The tell was in the logs all along: the
passing local run wrote **0** poms, the failing CI run wrote **35**.

**The JDK hypothesis is closed.** The failure reproduces on Temurin 21 with no GraalVM involved; the
environments never "differed only in the JDK", they ran different task graphs.

**Two wrong turns worth keeping**, because both are the same mistake:

- "testUtils does not compile from clean" — false. On a genuinely clean tree `testUtils/compile`
  passes *with* pipelining. That claim came from a tree already mutated by the earlier failed run.
- A control run "with pipelining back on" passed and appeared to refute the cause — but the
  preceding no-pipelining run had itself built the dependencies' full jars, so the control was
  measuring a repaired tree. **A control taken after the experiment is not a control.**

**Fix (corrected 2026-08-05): `ThisBuild / usePipelining := false`, build-wide.**

The first fix scoped it to testUtils, on the reasoning that testUtils is singular because nothing
depends on it in Compile scope. That reasoning was wrong — or rather, it explained why testUtils
fails *first*, not why it fails *alone*. `core` fails the same way and far harder:

| module | pipelining ON | OFF |
| --- | --- | --- |
| `testUtils/compile` | 27 errors | 0 |
| `core/compile` | **627 errors across 25 files** | 0 |

Same cleaned target on both sides, flag the only variable. `core` reports `Not found: SimpleYaml`
and friends — again at the *use* site, again reading like deleted code rather than a missing
classpath. It surfaced only because the scoped fix let the build get far enough to reach it.

The scoped setting is removed; keeping both would leave a second, redundant statement of the same
decision.

**This is state dependent, and that is the trap.** On a fully clean tree `testUtils/compile` passes
with pipelining ON. So a green build proves nothing about re-enabling it, and neither does a control
run taken after a repairing build — that mistake was made twice here already.

## coord-claim-items-tokenised-so-prose-collides-on-stop-words — a claim refused over the word "the"
<!-- status: open
     lane: apparatus
     area: build
     gate: none -->

**Found 2026-08-04** claiming `v2-emitter-outline` with
`--items "E-4 outline the arm fallback, gate on emitted size"`:

```
✋ claim REFUSED — it overlaps a live claim. This is NOT a race; retrying will not help.
  item 'the' is already claimed by 'uniml-ssc3-frontend-readiness'
```

`--items` is **tokenised on whitespace** and each token is compared against every live claim's
tokens, so a prose description collides on any common word — `the`, `a`, `in`, `and`. The guard is
behaving exactly as designed (`--items` is documented as *"SPRINT ids / BUGS slugs"*, i.e. tokens);
prose is the misuse. Retrying with `--items E-4-outline-arm-fallback` succeeded immediately.

**Why it is still worth fixing:** the failure costs several minutes to diagnose because everything
about it points elsewhere. The message asserts "this is NOT a race" while the observable symptom is
a push rejection — the same symptom a race produces — and four earlier attempts in this session
were mis-diagnosed as contention and retried, which the message explicitly says will not help.
Naming `'the'` as the colliding item reads as nonsense until you know items are tokenised, and
nothing in the output says that.

Earlier claims in this session used prose `--items` too and were accepted, purely because their
words happened not to collide with a live claim — so the trap fires at random and gets rarer as
claims are released, which is the worst distribution for learning it.

**Fix directions** (small, not attempted here — `scripts/coord-claim` and `.githooks/pre-push` are
owned by whoever next touches the mutex): reject a whitespace-containing `--items` argument up
front with "items are ids, not prose"; or ignore tokens shorter than N characters when comparing;
or say "items are compared as whitespace-separated tokens" in the refusal message so the reader can
see the cause from the output alone.


## smoke-suite-over-its-own-budget — every check green, the suite red, main red for everyone
<!-- status: open
     lane: apparatus
     area: build
     fixed-in: -
     gate: scripts/smoke-ci -->

**MEASURED AGAIN 2026-08-08, and this time the cause is neither of the two below. Run 31253238252:
72/72 green, 634.9 s of the 600 s cap, `freeze-consistency` 3.0 s.** The cold-resolve cause recorded
below is dead — that check is 3 s, not 95 s. What changed is that `sbt-plugin-scripted` went from
**0.0 s to 102.9 s on this path**, because `smoke-sbt-setup-skipped-on-cache-hit` was fixed: on a
cache HIT the sbt setup used to be skipped and both sbt checks failed instantly. The suite's real
cost on a cache-hit push was always ~635 s; the breakage made it look like 528 s by not doing the
work.

Which means the cap is doing exactly the job this file's own note describes — *"One check that grew
is the signal this budget exists for"* — and the honest reading is that the push path has been over
its stated budget for as long as that step has been broken.

| module | this run |
| --- | --- |
| `tests` | 384.2 s of 634.9 s (60 %), of which `sbt-plugin-scripted` 102.9 s |
| everything else | 250.7 s |

**Decided by the project owner, asked with the numbers above in hand: RAISED to 750 s on
2026-08-08.** `scripts/smoke-ci.ssc` says a raise "was refused twice before by agents including me,
and correctly: an agent lifting a cap on its own work is how the cap stops meaning anything", and
both prior raises (500 s, 600 s) were the owner's, so this one was put to them rather than applied.
The alternative offered and not taken was moving `sbt-plugin-scripted` (16 % of the run) to the sbt
job, which would have held the suite at ~532 s at the cost of catching a plugin regression off the
push path.

750 s sits ~115 s above the observed maximum (580.4 s cache miss, 634.9 s cache hit), which is the
same "above the maximum with room for the spread" rule the 420 s cap was fitted by. **This entry
stays OPEN:** 750 s is headroom for 72 checks, not an answer to what the push path should contain.

**MEASURED 2026-08-07, and the framing below is WRONG. It is not "the suite is too big" — it is ONE
check and ONE missing cache path.** Thirteen consecutive runs, suite total against
`freeze-consistency` alone:

```
suite 376.5 421.6 463.7 469.3 471.8 473.0 474.7 479.1 482.9 484.8 │ 625.4 625.5 626.5
fc      8.1   8.1  10.6  11.0  11.4  11.9  12.3  12.4  12.8  10.6 │  95.5  95.6  96.3
```

**A perfect split with nothing in between.** Every run under budget has that check at 8–13 s; every
run over it has the same check at 95–96 s, clustered within 1.1 s of each other. Across the same
pairs the other 68 checks differ by an ordinary ~15 % host factor (`run-lane-flags` 54.1→63.9,
`import-alias` 39.1→44.0). 84 s of one check IS the breach.

Two hypotheses tested and REFUTED before the third was accepted: the slow runs did NOT touch
`build.sbt` or `project/*` (so it is not the cache KEY), and they are NOT a contiguous window —
03:57 fast, 03:58 slow, 04:01 slow, 04:02 fast, minutes apart. It varies per run.

**Cause.** `freeze-consistency`'s invariant I5 shells out to
`scala-cli tests/conformance/contract.sc -- --list`, and `tests/conformance/.scala-build` — where
scala-cli keeps that compiled script — **was in no cache path**. Only `~/.cache/coursier`,
`~/.ivy2/cache` and `~/.sbt` were. So the script is resolved and compiled per run, at ~11 s when
coursier covers the dependencies and ~96 s when it does not.

The cost was known and written down at the call site ("0.49 s warm … a cold resolve-and-compile on
a runner … a 47x ratio") when I5 was added. It was documented and not removed, and the entry below
then spent four rounds attributing the breach to the suite's size.

**Fix (this claim):** cache `tests/conformance/.scala-build`, and compile `contract.sc` once in a
named workflow step AFTER the cache restore — where a dependency resolve is visible setup rather
than 84 unlabelled seconds inside a check being timed against a 600 s budget. `|| true`, so a
warm-up that cannot run degrades to today's behaviour instead of failing the push.

**CONFIRMED ON CI (run 31154784901, `25607a5d2`), and on the hard case:**

```
Cache Coursier/sbt                    1 s   <- a MISS, nothing restored
Warm the conformance contract build 131 s   <- the cold resolve, paid as visible SETUP
  ok  freeze-consistency             12.1 s <- was 95-96 s on exactly this kind of run
checks: 69/69 green    470.1 s of 600 s budget
```

That run's cache missed, which is precisely the condition that used to produce ~96 s and a ~625 s
suite. It came in at 470.1 s. The 84 s did not vanish — it moved out of the budget and acquired a
name.

**Honest limits of this measurement.** One completed run carries the new step so far, so the
mechanism is demonstrated and the DURABILITY is not. And the warm-up itself varies 40–131 s across
runs, because caching `.scala-build` does not help when the coursier cache is the thing that missed;
that variance is now in setup, where it belongs, rather than in a budgeted check.

**What this does NOT settle:** whether 600 s is the right cap for 69 checks. That question is real
and is what the entry below is about — but it was never what turned main red, and the entry stays
open for it rather than being closed on this.

---


**RAISED TO 500 s ON 2026-08-04, by the project owner's instruction, and this entry STAYS OPEN.**
The 420 s cap was fitted when the suite held 18 checks; it holds 58, and with runner variance at
±14 s it had no headroom against its own noise — it failed run 30905783511 at 428.8 s with every
check green. 500 s buys room for what the suite contains today and answers nothing about what it
should contain, which is what this entry is actually about.

Recorded rather than applied quietly because raising it was refused twice before, including by me,
and that refusal was right: an agent lifting a cap on its own work is how a cap stops meaning
anything. The structural work that belongs to this entry was done separately and is not replaced by
the raise — three of the five most expensive checks were mine and are now 88 s → 10 s and
104.5 s → 3 s, the last measured on CI where it cost 104.5 s against 5.5 s locally.

**REOPENED the same day. Closing this was premature and the reason is worth more than the fix
was.** Four runs, 2026-08-03:

| run | total | verdict | what changed since the previous row |
|---|---:|---|---|
| 30798878836 | 437.9 s | RED | — |
| 30799937285 | **355.8 s** | green | `bench-seed-type` (45.4 s) moved to tier 2 |
| 30839675049 | 418.6 s | green | +`std-import-lanes` 17.7 s, +`build-smoke` 4.4 s, +`route-handler-shapes` 45.9 s |
| 30840744973 | **425.6 s** | RED | `std-import-lanes` trimmed 17.7 s → 10.9 s |

**Read the last two rows together: the suite got 7 s SLOWER across a change that only removed
work.** That is the finding. Runner variance here is ±14 s, so a cap with less than ~20 s of
headroom flaps regardless of what is in the suite — and 420 s for 60 checks does not have 20 s of
headroom. Every "fix" that shaves ten seconds off the newest arrival is inside the noise.

**What was done anyway, because main was red:** `std-import-lanes` and `build-smoke` — the two
newest checks, both mine, both added that morning — moved to tier 2, which is also per-push.
Rule applied: *when a shared budget is exhausted, the additions that arrived last leave first.* It
is the only rule an author can apply to their own work without a negotiation, which is what made it
possible to act at all while everyone's pushes were failing. It buys ~15 s. It is not a fix.

**What this actually needs, and it is not another trim.** The five most expensive checks are
`route-handler-shapes` 45.9 s, `render-lane-builtins` 42.0 s, `corpus-lane-breadth` 34.1 s,
`launchers-not-dead` 30.9 s, `no-test-reaches-an-exiting-cli` 27.9 s — 180.8 s of 425.6 s between
them, each with an owner. Either several move to tier 2, or the suite's design point is restated
honestly: it was built for 27 checks and ~157 s, it now has 60. Raising `SSC_SMOKE_BUDGET` is still
refused for the reason this file has always given, but "the cap is correct and the suite is too
big" is a claim someone has to actually decide, rather than each of us shaving our own newest gate.

---

**The first close, kept because its measurement is still valid:**

| run | sha | checks | total |
|---|---|---|---:|
| 30798878836 | `cbecbec42` | 58/58 green | **437.9 s** — RED |
| 30799937285 | `8b0ee9bb3` | 58/58 green | **355.8 s** — green |

`bench-seed-type` moved to tier 2 and `js-selfcall` was registered in the same commit, so the check
COUNT is unchanged at 58 and the suite still answers the same questions per push. The measured
saving is 82.1 s, against the 37 s I projected — the difference is runner variance, and it is the
reason the projection was written as a projection.

**Found 2026-08-03** by `legacy-object-apply`, from a CI red on a commit whose diff was one front's
uid chain. Nothing was broken:

```
checks: 57/57 green    433.0s of 420s budget
OVER BUDGET — the push path is what this suite exists to keep short.
##[error]Process completed with exit code 1.
```

**All 57 checks passed and the run still failed**, so every push fails until the total comes down.
Locally the same tree measured **300.2s**; the CI runner is slower, which means the local reading
cannot warn anyone — the budget is only ever breached where it is enforced.

**The suite grew from 32 checks to 57 during 2026-08-01/03**, several agents adding gates in
parallel, each one individually cheap and correct. No single commit is at fault, and that is the
point: a per-push budget is a SHARED resource with no owner, so it is spent until it runs out.

Slowest checks in that run:

| s | check |
|---:|---|
| 45.4 | `bench-seed-type` |
| 45.3 | `submodule-gitlinks-resolve` (network — one `git fetch` per gitlink) |
| 42.5 | `render-lane-builtins` |
| 33.9 | `corpus-lane-breadth` |
| 31.8 | `launchers-not-dead` |
| 28.3 | `no-test-reaches-an-exiting-cli` |

Those six are 227s — over half the run.

**Deliberately not fixed by raising `SSC_SMOKE_BUDGET`.** The suite's own message says that is how
the old 13.4-minute path happened, and it is right: the budget is the only thing that has been
holding the line. The fix is to cut or speed up a check, and WHICH check is a decision with an
owner, not something to take unilaterally while everyone's pushes are red.

**TAKEN 2026-08-03 by the owner of candidate 2's first half.** `bench-seed-type` is mine — added in
that same 2026-08-01/03 window — and it was the most expensive check in the suite. It now runs in
tier 2 of `ci.yml`, next to `bench-wrapper-gate.sh`, which is **also per-push**: the per-commit
answer is unchanged, it simply comes out of a 14-minute budget instead of a 7-minute one.

Measured per lane before deciding, rather than cutting the cheapest thing to cut:

| lane | local |
|---|---:|
| `ssc` | 2.2 s |
| `js`  | 2.2 s |
| `jvm` | 11.6 s |

The jvm cells are 72% of it and are **not** separable from the property — the jvm wrapper is where
the defect was, and the Long fixture on that lane is the control that says the gate still works. So
the honest choice was WHERE it runs, not whether. The argument for moving it is not that it is
expensive but that what it gates is a MEASUREMENT APPARATUS: a regression there costs a benchmark
table, not a build, and 45.4 s is 29% of a signal designed to take ~157 s.

Landing this frees 45.4 s of the 433.0 s run, and spends ~8 s of it on `js-selfcall`
(`js/BUGS.md js-worker-source-joined-with-literal-backslash-n`), which is registered in the same
commit. Projected ~395 s. **That is a ~25 s margin, which is not much** — the structural finding in
this entry stands untouched: a per-push budget is a shared resource with no owner, and one gate
moving out does not give it one.

**Not touched, deliberately:** the local/CI gap (300.2 s vs 433.0 s, x1.44). `scripts/smoke-ci.ssc`
already decided this question in writing — the budget fails on CI and only warns locally, the host
probe prints and is explicitly `informational — the budget is still absolute`, and the file records
what happened the last time a cap was fitted to local numbers (300 s, guessed from a dev machine,
red on the first run). Adding a calibration factor on top of that would be overruling a documented
decision from outside, on one data point.

**The remaining candidates**, still offered rather than taken:

1. `submodule-gitlinks-resolve` is 45s of NETWORK and its own comment says a slow network must read
   as "this run could not tell". It is a strong candidate for the nightly rather than the push path.
2. `render-lane-builtins` (42.5 s) — `bench-seed-type` was the other half of this line and is done.
   One correction to the original wording: it is not true that `bench-seed-type` gates nothing the
   corpus lanes cover, because the corpus lanes do not run `ssc bench` at all. It was moved on cost
   and proportion, not on redundancy.

I added two gates in that window (`js-shaker-effectful-binding` 0.7s, `v2-char-numeric-position`
4.6s, 5.3s together). Naming that because "someone else's checks are the slow ones" is exactly the
reasoning that spends a shared budget.

**The suite now names its own worst check (2026-08-05).** Every overrun printed "read the per-check
timings above: one check that grew is the signal this exists for", and then left the reader to scan
sixty lines for it. It now prints the three most expensive checks with their SHARE of the run:

```
most expensive checks
  tests run-lane-flags-are-flags       51.6s   8% of the run
  tests import-alias                   42.2s   6% of the run
  scripts claim-scope-hierarchy        28.0s   4% of the run
```

A share, not a comparison with a previous run, and not a raw second-count: this file's own host-speed
probe measured the same runner varying up to 1.4x day to day, so "slower than last time" is usually
the host. A share is scale-free — uniform inflation leaves it unchanged, one check growing moves it.

The first run with it printed 8% at the top, which reads immediately as "nothing dominates, the host
is loaded". Compare `launcher-input-digest` earlier the same day: 227 s, **27%** of the run and 3x
the next check. Finding that took ranking a CI log by hand; the ranking now costs nothing.

This entry stays open: what the suite should CONTAIN is still the question, and the budget number is
still a proxy for it.

## orphaned-e2e-gates-52 — 52 of 126 gates were invoked by nothing, and 33 of those do not pass
<!-- status: open
     lane: apparatus
     area: build
     kind: apparatus
     gate: none -->

**Measured 2026-08-02.** `tests/e2e` holds 126 scripts. 52 of them are referenced by no workflow, no
suite, and no other script — only, at most, by prose in a `.md`. Running all 52 with a built
launcher, 180 s timeout each, 1604 s total:

| outcome | count |
|---|---|
| pass | 19 |
| fail | 26 |
| hang past 180 s | 6 |
| usage error | 1 |

The 19 that pass are now wired — the twelve cheapest (≤7 s) into `scripts/smoke-ci`, the seven
costlier (14-59 s) into tier 2 in `ci.yml`. This entry is the remaining 33.

**Why they are not simply deleted.** Only two referenced paths that are all gone, and one of THOSE
passes (`indent-layout-v2-smoke.sh`) — so "the subject is gone" is not the explanation. A first
count said 33 gates had dead subjects; that was a bug in the counting loop, and the real number is
two. Nor are they wired red: a gate nobody asked for that fails on arrival is how a suite becomes
noise people learn to ignore.

**RE-TRIAGED 2026-08-02 — the first split above was wrong in two places, and both errors pointed
away from the cause.** The gates did not rot from one product bug. They rot from TWO mechanical
repo changes that landed in front of everything else, and the second one hid a genuine regression.

**Layer 1 — `ROOT` is one `..` short, in 22 of them.** `ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." …)"`
resolves to `tests/`, so `$ROOT/bin/ssc` is `tests/bin/ssc`, which has never existed. `d0665660a`
("group top-level dirs into … tests/") moved the gates a level down and did not adjust it. This is
the whole of the "EMPTY result where content was expected" category — the command was never found,
so stdout was empty. Not, as this entry read, "the pipeline stopped producing".

**Layer 2 — four of the five shipped launchers were DEAD.** `sscc`, `jssc`, `ssc-js` and `ssc-wasm`
each resolved `SSC="$SCRIPT_DIR/ssc"` and exec'd a subcommand the STANDARD ssc refuses
(`compile-jvm`, `emit-js`, `emit-wasm`); `ssc-spark` failed a step later on
`unknown standard run option: --backend`. On a two-line hello-world, every JVM lane and every JS
lane in `tests/e2e` died at its first call. Fixed: all five resolve `bin/ssc-tools`. Guarded by
`tests/e2e/launchers-are-not-dead-on-arrival.sh`, wired into `scripts/smoke-ci`.

Thirteen gates also called `render` / `bundle` / `build` / `site` through `bin/ssc` after those
moved to the optional tier; they now call `bin/ssc-tools`, which is what `StandardMain` tells you
to do when it declines.

**The six filed as HANGING do not hang.** Each has a `wait_for_server` deadline of 60-90 s and runs
three backends, so the worst case is 180-270 s — past the 180 s census timeout, which cut them off
mid-run and made a slow failure look like a hang. That single mislabel is why nobody read the
actual error for six gates. They now also FAIL FAST: `wait_for_server` takes the server's pid and
returns immediately once the process is gone, instead of polling a corpse for a minute. Measured
against a deliberately-killed launcher, `fm-routes-smoke` went 128 s -> 10 s, and the message says
"the server process EXITED before it listened" rather than "did not start within 60s", which was
the lie that produced the hang diagnosis in the first place.

**Layer 3 — what is left under both is real, and small.** One product bug now accounts for
`build-smoke`, `bundle-smoke`, `render-smoke` and `components-smoke`'s INT lane: filed as
`int-v1-lane-loses-a-builtin-companion-to-its-own-case-class` reduced to four lines.
On the v1 interpreter lane the imported `case class Response` overwrites the builtin companion, so
`Response.html` dies while `Response(200, Map(), "x")` works. NOT a render bug and NOT invoke-time:
`ssc-tools run --v1` reproduces it at a file's top level with no route and no handler. The first
filing said otherwise on both counts and is corrected in the entry.

**NOW WIRED (9).** `nested-build`, `site`, and the seven `std-ui-*` smokes pass under both fixes
and are in `scripts/smoke-ci`, at <=1 s each. Each was checked NOT to pass vacuously — mutating the
subject (`NavBar` -> `NavBarXX` in `examples/std-ui/nav-demo.ssc`) turns `std-ui-nav` red.

**STILL RED (13), and now for readable reasons.** All 22 together run in 89 s, where the six
"hangers" alone used to consume 180-270 s each. `build` / `bundle` / `render` / `components`(INT)
are the one filed interpreter bug; the rest report real product differences at their first
assertion rather than dying on a missing path.

**RE-CENSUS 2026-08-03 — the table below is a snapshot of 2026-08-02 and is now wrong in five
rows.** Re-ran all 13 against HEAD with a freshly built launcher:

| gate | then | now |
|---|---|---|
| `build` | red (interpreter companion bug) | **PASSES** — wired into `scripts/smoke-ci`, ~2 s |
| `components`, `middleware`, `upload` (JVM) | `the server process EXITED before it listened` | that was a RESOLUTION failure, not a serving one — fixed, see below; `components` now reaches `JVM artifact written` and fails later |
| `middleware` (INT) | `native callback arity` | `body=DataV(Stub,Vector(StrV(Response.withHeader)))` — the sibling-filed `native-Response-withHeader-is-a-Stub` |
| `validation` (INT) | `native callback arity` | `unbound global: requireInt` — the sibling-filed `native-requireInt-unbound-in-a-route-handler` |
| the other 8 | unchanged | unchanged |

**The shared cause under three of them was NOT what any of them reported.** `compile-jvm` refused
`[httpGet](std/http.ssc)` from a file that `run --v1` and `run-js` both accepted — a std-root
resolution defect in two layers, filed and fixed as
`BUGS.md compile-jvm-and-std-root-disagree-on-where-std-lives`, gated by
`tests/e2e/std-import-lanes-gate.sh`. Every one of the three reported it as a SERVING failure,
because the gate's own harness could only see that the server never listened. A third copy of the
same mistake lives in `BundleCommand` and is filed separately as
`bundle-command-resolves-imports-relative-only`.

**Method note, since this entry is partly about counting:** the previous table's rows for
`middleware` and `validation` were accurate when written and were invalidated by a SIBLING's fix
landing in between, not by an error. A census of failure messages has a shelf life measured in
commits; re-run it before acting on it.

**RE-CENSUS 2026-08-06 — thirteen red became ZERO.** Re-ran every gate this entry has ever listed as
red, against HEAD with a fresh build:

| gate | now |
|---|---|
| `build`, `bundle`, `components`, `import-alias`, `std-ui-forms`, `upload`, `url-import`, `validation` | **green** |
| `middleware` | **green 2026-08-06** — the signature mismatch is fixed; see `v1/runtime/backend/jvm/BUGS.md` |

Everything except `middleware` was closed by the causes this entry chased down rather than by the
gates being changed: the jvm `Cons` lowering and its scope/`__extern__` siblings, the json and
session host bridges, the extern-import filter, the triple-quoted-literal lexer fix, and native
multipart. `middleware` is the one filed cause still open —
`v1/runtime/backend/jvm/BUGS.md`'s middleware section: `std/http.ssc` declares
`use(fn: (Request, () => Response) => Response)` while the jvm host defines
`(Request, () => Any) => Any`, so `next()` infers `Any` and `.withHeader` is unreachable.

**The shelf-life warning this entry already carried proved itself twice.** The table below is the
2026-08-02 snapshot; it was wrong by 2026-08-04 and is wrong again now. Read it as history, not as
state — the row that matters is the one above.

**WHAT THE 13 RED ONES SAID ON 2026-08-02** — kept for the record:

| gate | first failure |
|---|---|
| `build`, `bundle`, `render`, `components`(INT) | `int-v1-lane-loses-a-builtin-companion-to-its-own-case-class` — filed, reduced to 4 lines |
| `middleware`, `validation` | `500 native HTTP handler failed: native callback arity` |
| `fm-routes`, `health-defaults` | **RESOLVED 2026-08-04 — three causes, only one of them the product.** Both gates now exit 0. (a) They ran `$BIN/sscc` as "the JVM backend"; `sscc` is `ssc-tools compile-jvm`, which COMPILES and exits — "the server process EXITED before it listened" was the literal truth about a compiler, and the JVM lane works fine via `run-jvm`. (b) They labelled `$BIN/ssc` INT; it is `StandardMain`, the NATIVE lane, so failures went to the wrong owner. (c) Body extraction used `head -n -2`, a GNU extension that BSD/macOS rejects outright, so `body` was ALWAYS empty and every body assertion failed on every lane regardless of what the server sent. What remains after fixing the instrument is ONE product gap on ONE lane, filed as `v2/BUGS.md native-lane-ignores-declarative-route-registration` and declared `[KNOWN GAP]` in both gates. |
| `package-keyword` | `ssc: unbound global: org` — the `[org](./cards.ssc)` import does not bind |
| `import-alias`, `url-import`, `std-ui-forms`, `upload` | INT-lane content mismatches, each unreduced |

`v21-native-http-request-source-arity` in this file is the nearest existing entry to the
`native HTTP handler failed` pair, but it is `status: fixed` / `confirmed: no` and its symptom is
`match: no arm for`, not `native callback arity`. Close enough to check first, NOT close enough to
call the same bug — three of these gates were mis-diagnosed once already by assuming a family
resemblance was an identity.

Note `package-keyword` hid its own error behind `2>/dev/null`, which is why the census recorded it
as "empty output". Bare `ssc file.ssc` still works; the emptiness was the interpreter erroring on
stderr.

**RUNNER SWEEP 2026-08-04 — the same three apparatus defects ran through the whole set.** After
`fm-routes` and `health-defaults` turned out to be measuring a compiler, the census asked how far
that went: **8 gates ran `$BIN/sscc` as a server** and **6 labelled `$BIN/ssc` as INT**. Seven were
free to fix (`package-keyword` is held elsewhere). Corrected runners, then re-measured:

| gate | after |
|---|---|
| `import-alias` | **green** |
| `url-import` | **green** |
| `validation` | **green** — INT/JVM/JS all listen, verified distinct |
| `components`, `middleware` | jvm fails to COMPILE — `jvm-lane-cannot-compile-a-json-import`, filed |
| `std-ui-forms` | **REDUCED 2026-08-04 to four lines and filed** — not a rendering defect at all. `ssc render` died with `InterpretError: Undefined: impl`, a name in no source file, at a position that does not exist in the file it names. It is a triple-quoted literal whose CONTENT ends with a quote (`""" aria-invalid="true""""` in `input.ssc`): int, js and jvm all turn it into a **List** instead of a String, native alone is right. `BUGS.md triple-quoted-literal-ending-in-a-quote-is-not-a-string`, gated. |
| `upload` | **MEASURED 2026-08-04 per lane and filed.** Three lanes, three different answers: int throws `For input string: "-"` (a number parse on a multipart boundary), native answers `missing 'file' part` AT HTTP 200 — a wrong answer, not an error — and js is the only one that round-trips the file correctly. `BUGS.md multipart-upload-three-lanes-three-answers`. |

**The finding under two of them is bigger than the gates.** `run-jvm` cannot compile *any* program
importing `std/json.ssc` — 14 `[E007]` in the emitted Scala, every one a destructured binder typed
`Any`. `std/http.ssc` imports json, so the jvm lane could not compile an HTTP server either. Both
gates reported it as `the server process EXITED before it listened`.

**A false green I produced and had to withdraw, worth recording.** The first sweep was one blanket
regex, and it broke two ways at once. Helpers taking positional args got `expected` shifted into the
wrong slot (`expected: --v1`); helpers invoking `"$launcher"` **dropped the added words entirely**,
so INT, JVM and JS all ran the same default lane — and `middleware` and `validation` went green for
no reason at all. Caught by checking that the per-lane logs DIFFER, which is now the thing to check
after any change to how a gate picks its lane. Reverted and redone file by file.

**Full list, by outcome.**

failing (26):
  - `actors-pingpong-smoke.sh`
  - `area-map-gate.sh`
  - `bundle-smoke.sh`
  - `import-alias-smoke.sh`
  - `nested-build-smoke.sh`
  - `package-keyword-smoke.sh`
  - `render-smoke.sh`
  - `req-type-collision-v2-smoke.sh`
  - `route-params-v2-smoke.sh`
  - `serve-view-frontend-v2-smoke.sh`
  - `site-smoke.sh`
  - `std-ui-content-smoke.sh`
  - `std-ui-data-smoke.sh`
  - `std-ui-feedback-smoke.sh`
  - `std-ui-forms-smoke.sh`
  - `std-ui-layout-smoke.sh`
  - `std-ui-nav-smoke.sh`
  - `std-ui-theming-smoke.sh`
  - `std-ui-widgets-smoke.sh`
  - `url-import-smoke.sh`
  - `v21-build-jvm-smoke.sh`
  - `v21-native-content-smoke.sh`
  - `v21-native-doc-render-smoke.sh`
  - `v21-typeclass-dictionary-smoke.sh`
  - `v21-unhandled-effect-smoke.sh`
  - `wc-card-smoke.sh`

filed as hanging (6) — they are not; see the re-triage above, they now fail in ~10s:
  - `components-smoke.sh`
  - `fm-routes-smoke.sh`
  - `health-defaults-smoke.sh`
  - `middleware-smoke.sh`
  - `upload-smoke.sh`
  - `validation-smoke.sh`

usage error (1):
  - `v21-portable-gates-smoke.sh`

**The lesson worth keeping separately from the list:** two of these were wired by hand earlier this
week and immediately caught real defects — `negtc-mapreduce-gate` proved the property the whole
negtc shard rests on and had never run, and `v21-negative-toolchain-release-gate-smoke` died at its
first line so none of its seven drift rejections ever executed. A gate that runs nowhere is not
neutral; it is a claim of coverage that is not being made.

## f4-dualrun-gate-compares-F-with-ITSELF-since-the-front-flip
<!-- status: fixed
     lane: apparatus
     kind: apparatus
     area: conformance
     fixed-in: d19d5fc38
     gate: specs/v2.2-p6.5-dualrun.sh DR_SELFTEST=1 -->

**FIXED.** The baseline side now sets `SSC_FRONT=legacy` explicitly, and NEITHER side is called
"the default" any more — the default IS F, and naming the thing under test that way is how the gate
came to compare F with itself.

`DR_SELFTEST=1` plants a divergence and the gate must go RED; verified. And the corrected gate is
GREEN for a real reason: **45/45 EQUAL between `SSC_FRONT=legacy` and `SSC_FRONT=F`**, which is
information the vacuous version never produced — it could not tell agreement from self-comparison.

**Found 2026-08-01** under `ssc3-core`, while looking for the gate that would catch a change to the
F front. The gate whose entire purpose is to compare the two fronts has been comparing one front
against itself since the F4 flip, and has therefore been **vacuously green**.

`specs/v2.2-p6.5-dualrun.sh:53` runs the two sides as:

```sh
if [ "$front" = F ]; then SSC_FRONT=F "${tb[@]}" "$SSC_BIN" run "$f" > "$CAP.out"
else                                  "${tb[@]}" "$SSC_BIN" run "$f" > "$CAP.out"
```

and the launcher resolves the front as:

```scala
private def frontIsF: Boolean =                                  // RunNativeV2.scala:791
  !sys.env.get("SSC_FRONT").exists(_.equalsIgnoreCase("legacy"))
```

`SSC_FRONT=F` → F. `SSC_FRONT` unset → **also F**. Both sides are the same front. The opt-out is
`legacy`, and no side of this gate ever sets it.

**Why it went unnoticed:** the script's own header still describes the pre-flip world —
*"def = (exit, stdout) of `bin/ssc run P` — DEFAULT front (ssc1-front + ssc1-lower)"*. That was true
until `56d7d705f` made F the default (`frontIsF` opt-OUT). The gate was not changed with it, and
comparing F to F cannot fail, so nothing ever complained.

**Proof it cannot see a real divergence**, from today rather than in principle: `2d29b3e71` fixed
`new Array[T](n)` on the legacy front only. Between that commit and the F fix, the two fronts
genuinely disagreed —

```text
SSC_FRONT=legacy  ./bin/ssc run --v2   new Array[Int](3).length   ->  3
default (F)                                                       ->  1
```

— and this gate would have reported GREEN throughout, because both of its sides were the `1`.

**Fix:** the baseline side must set `SSC_FRONT=legacy` explicitly. Rename the labels while doing it,
because "default" now names the thing under test rather than the reference, and that ambiguity is
what made the wrong comparison read as the right one. `--self-test` should plant a divergence and
require the gate to fail (P-6.1): a gate nobody has watched fail is a hypothesis, and this one had
been a hypothesis for the entire life of the flipped tree.

Note the interaction with the F4a delegate-fallback, which is what makes this expensive rather than
merely wrong: where F cannot lower a file it silently delegates to the reference front and the
program still prints the right answer. So a broken F shows up as neither a wrong answer nor a
failing gate — only as a trace line under `SSC_FRONT_TRACE=1`, which nobody sets.

## ci-status-guard-races-the-shared-repo-index-lock — a smoke check that fails on a busy host
<!-- status: open
     lane: apparatus
     area: build
     gate: none -->

**Found 2026-07-31** by `scripts/smoke-ci` going red on `tests / ci-status-guard` during the
`v2-wide-jit-j0` claim, on a diff (`v2/src/Jit.scala`, `RunNativeV2.scala`) that cannot reach it:

```
FAIL ci-status-guard    50.1s
   | exit code 128
   | fatal: Unable to create '/…/scalascript/.git/worktrees/claim-wt1/index.lock': File exists.
   | Another git process seems to be running in this repository, or the lock file may be stale
```

Re-run alone immediately afterwards: `ci-status-guard: PASS`, rc 0. So this is contention, not a
regression.

**Root cause is structural, not transient.** The check builds a temporary worktree (`claim-wt1`)
**in the shared main repo**, whose `.git` index is contended by every sibling agent — there were 45
worktrees and several agents committing at the time. `git worktree add` takes the shared index lock,
so the check's success depends on nobody else running git for that instant. On a quiet host it
always passes; under the parallel-agent load this repo is designed for, it fails at random.

**Why it matters more than one red run:** a pre-push suite that fails for reasons unrelated to the
diff teaches agents to re-run until green and then to stop reading it — which is how a real red gets
waved through. This one cost a full re-run of a 256 s suite to attribute.

**Fix directions** (not attempted here — `scripts/ci-status` and `tests/e2e/ci-status-guard.sh` are
held by the live `negtc-gate-shard-reduce` claim): give the check its own throwaway clone
(`git clone --depth 1` of the repo into `$TMPDIR`) instead of a worktree in the shared repo, or
retry the `worktree add` on lock contention with a bounded backoff. The clone is preferable — it
removes the shared-state dependency rather than making the race rarer.

## nul-byte-in-tracked-source — one NUL makes `grep` answer "nothing" for both match and no-match
<!-- status: fixed
     lane: apparatus
     area: build
     fixed-in: unrecorded
     gate: tests/e2e/no-nul-in-sources.sh -->

**Found 2026-07-31** while asking why `grep` kept returning nothing on `v2/src/Runtime.scala`.

A single NUL byte makes `grep` treat the **whole file** as binary, and it then prints nothing — the
same output it prints for "no matches". Two different states, one answer. `Runtime.scala` is 227 KB,
the largest runtime source, and every `grep` over it had been silently answering "nothing" for
anyone who did not know to pass `-a`. I nearly drew a conclusion from that silence, with the hazard
already recorded in my own notes.

**Three tracked files carried one, not the one I noticed by hand** — the gate found the other two on
its first run:

| file | what the NUL was |
|---|---|
| `v2/src/Runtime.scala:1961` | `case _ => '<NUL>'` — a Char literal default |
| `v2/backend/js/JsBackend.scala:634` | `tag+'<NUL>'+name` — a key separator inside generated JS |
| `…/ScljetVfsNativePluginTest.scala:50` | `"SQLite format 3<NUL>"` — the SQLite magic header |

All three are INTENTIONAL NUL semantics written as a raw byte instead of an escape, so all three fix
without changing behaviour: `\u0000` in the emitted JS, `("SQLite format 3" + 0.toChar)` for the
header, and the escape for the Char literal.

**The gate found a defect in ITSELF on its first run, and that is why it is worth having.** Invoked
from outside a repository, `git ls-files` failed, the file list came back empty, and it printed
`OK` — scanning nothing reported as a pass, which is exactly the failure it exists to prevent. An
empty list now refuses loudly. `--self-test` plants a NUL and requires it to be caught.

**Class, not incident.** Same shape as five others the same week: `grep -c` exits 1 on zero matches
(a successful build reported as failed), `coord-release` silently dropped `--level`,
`update-index --cacheinfo` accepted a sha with no commit behind it, a stale build answered about the
wrong code. **Not being able to answer must not look like answering "no".**

## shared-main-is-one-working-tree-for-every-agent — bookkeeping pushes from it are flaky, and `git push` can report success while pushing nothing
<!-- status: open
     lane: apparatus
     area: other
     gate: none -->

**Found 2026-07-30** across four attempts to land ONE two-file claim-release commit. Not a theory — each
symptom below was observed, and the last two cost real work.

Every agent runs `scripts/coord-claim` / `coord-release` in the SAME shared checkout, so `.git/index`,
`HEAD` and the working tree are shared state with no locking above git's own.

| observed | consequence |
|---|---|
| another agent committed into the checkout mid-operation | my `git commit --amend` landed on THEIR commit, and my release commit vanished from local `main` |
| `.git/index.lock` present from another agent's git | `coord-claim` aborted with "Another git process seems to be running" |
| `git push` printed success | **nothing was pushed** — the claim file was still on origin |
| `git push` refused over `file '.agents/plugins' is already claimed` | my commit contained two `.work/` files and no claim; see below |

**The rule this produces, and it is cheap:** verify a release against the remote, never against the push
output — `git ls-tree origin/main .work/active/`. I now do this after every release and it has caught a
silent no-op once already.

**About that last refusal — a narrowing, not a diagnosis.** I first told the room that
`.githooks/pre-push:148` judges ownership from "the working tree". That was WRONG and I retracted it:
`owner_holds_file()` inspects the OWNER's worktree, which is a reasonable "is that agent editing this file"
heuristic, and in my case it never ran because `plugins-registration-bump` declares `file:.agents/plugins`
explicitly. The refusal comes from the `$new_items` / `$new_paths` loops, which validate an INCOMING claim —
yet my commit added no claim file. The remaining explanation is that pushing shared `main` can carry ANOTHER
agent's claim commit inside `remote_tip..local_tip`, so the guard validates a foreign claim and refuses a
push the pusher cannot fix. **Unproven.** To settle it: instrument `incoming` in the hook and push twice from
shared main while another agent claims something.

I deliberately did not change the mutex on an unproven reading. Weakening it to gain convenience is how two
agents end up doing the same work, which is what `specs/claim-mutex.md` exists to prevent.

**CONFIRMED 2026-08-07 — the "unproven" reading above is exactly right, observed three times in one
day, twice with the whole queue stopped.** No instrumentation was needed in the end; the refusal
names the foreign claim itself:

```
✋ pre-push: this claim overlaps a live claim on origin/main.
  file 'tests/e2e/f-bare-member-call-gate.sh' is already claimed by 'f-name-the-failing-module'
```

— raised against a claim of mine whose paths were `tests/conformance/run.sc` and a BUGS file, which
touch neither that file nor that agent. `git log origin/main..HEAD` in the shared checkout then
showed two commits belonging to other agents, and the named file was in ONE OF THEIRS.

**The three occurrences, because the shape differs each time and the cause does not:**

| what was parked | why it could not be pushed |
|---|---|
| a claim file widened, its LEDGER row not yet updated | the guard refuses when the two copies disagree — the owner was mid-operation |
| a claim widened onto a file another live claim holds | a real overlap; the owner has to drop the path |
| a release for a claim ALREADY released on origin under a different sha | a pure duplicate: the owner had re-landed it from a worktree, and the local copy was left behind |

Every one of them blocked EVERY agent's next claim, because `coord-claim` pushes the whole branch
from the shared checkout and the hook validates every claim in `remote_tip..local_tip`.

**The workaround, and it is already in use.** Build the coordination commit on a branch off
`origin/main` in a worktree and `git push origin HEAD:main`. That carries only your commit, so a
stranger's parked one cannot refuse it. A sibling landed `442e8e308` that way while the queue was
stuck, and I took `batch-lane-keeps-stderr` the same way. The ledger's generation bump has to be
replicated by hand, which is the part that makes this a workaround and not a fix.

**What NOT to do, stated because it is the tempting one.** `git reset --hard origin/main` in the
shared checkout clears it instantly, and twice today I could PROVE the parked commits were dead —
one duplicated something already on origin, one contradicted its own owner's state of record. I did
not, because the newest was eleven minutes old with its owner active, and "I proved it is dead" is
what people think immediately before destroying someone's work. Report it, name the fix, and let the
owner act.

**The rule this produces, for the owner rather than the bystander:** a coordination commit that is
committed and not pushed is a landmine for everyone. If your `coord-claim` or `coord-release` push
is refused, either fix it and push, or reset your own commit — do not leave it parked. `coord-claim`
already rolls its own commit back on refusal; `coord-release` and hand-made claim-updates do not, and
all three of today's landmines were of the second kind.

## backend-jvm-cases-have-no-verdict-on-any-backend-they-name — `backend: jvm` now gates a case to INT alone
<!-- status: open
     lane: apparatus
     area: conformance
     gate: none -->

**Found 2026-07-30** while refreshing the paired freeze, and recorded here because it was previously only in
a commit message.

`parseTargetBackend("jvm")` yields `{int, jvm}` (`contract.sc:647`), and the contract's default lanes are
`int,js,v2`. The intersection is **`{int}`**. So the eight cases declaring `backend: jvm` —
`dataset-typed-mapping`, `distributed-dataset-{codec,typed-helpers,wire-protocol,wire-shuffle}`,
`graph-codecs`, `object-store-jdbc`, `typed-object-codec` — are measured on the interpreter only, and have no
verdict at all on the backend they name.

That is worse than it sounds in combination with the jvm lane's own state: `run-jvm` printed nothing and
exited 0 for `println(1+1)` when this was found (`run-jvm-silent-success`, since fixed by
`jvm-lane-never-calls-main`). So even switching `jvm` into the default lanes would have reported every case
as DIVERGE-by-empty-output rather than as a broken lane.

**It also explains ~20 rows that vanished from the freeze**, which could easily read as fixes: refreshing the
baseline after `baa55cdb9` (contract honours `backend:`) removed the js/v2 rows of those cases. They were not
fixed — they stopped being measured. Worth stating in the layout spec either way.

**Decide, do not patch:** either put `jvm` in the default lanes now that the lane runs, or say plainly that
`backend: jvm` means int-only today so nobody reads those green rows as backend coverage.

## ci-status-guard-selftest-two-stacked-defects — `Validate ScalaScript` is red on the guard's OWN self-test, twice over
<!-- status: open
     lane: apparatus
     area: build
     gate: tests/e2e/ci-status-guard.sh -->

**Found 2026-07-30.** `Validate ScalaScript` fails on every push at
`tests/e2e/ci-status-guard.sh`. Nothing in the repository is broken — the guard's own fixtures are.
While it is red **no agent can obtain level-1 evidence for anything**, which is the currency this
project releases work on; two of my claims closed at level 3 today for exactly this reason.

**Defect 1 — the fixture slug collides with a REAL claim, and a negative age is clamped to zero.**

`coord-status` rightly treats a stale heartbeat FIELD as live when the owner committed recently
(`live by COMMIT activity (stale heartbeat field, ignored)`), taking the newest of the fixture branch
tip **and** `git log -1 origin/main -- .work/active/<slug>.claim`. The fixture calls itself
`ci-red-main`, and a real claim by that name was committed on main:

```
real   .work/active/ci-red-main.claim   last touched  epoch 1784468534  (2026-07-19)
test   SSC_COORD_NOW_EPOCH (pinned)                   epoch 1784249101  (2026-07-17 + 45m)
```

Real history is **newer than the test's pinned fake now**, so `activity_age_seconds` is negative,
`coord-status` clamps negative to 0, the claim reads as "last commit 0m ago" forever, and the
assertion the test exists to make is unreachable. Not flaky — deterministically dead since that
claim landed.

The clamp is the part worth fixing in the tool rather than the fixture: **a commit dated after
"now" is a clock or fixture anomaly, and reporting it as "just committed" is the least safe reading
available.** `scripts/coord-status:446`.

**Defect 2 — `invalid-heartbeat` fails too, and it was hidden behind defect 1.** With defect 1's
assertion neutralised in a scratch copy of the file from `origin/main`, the run gets past
`missing-claim` (which passes) and dies at `invalid-heartbeat`. Same file, same shape as
2026-07-28's finding about this very job: **stacked defects in a job's own self-tests, where the
first hides the second.** Not diagnosed further.

**What I tried, and why it is not landed.** Dating the fixture commits before the pinned clock, plus
renaming the fixture slug so it cannot collide, does fix defect 1 — measured. But the rename makes
`missing-claim` fail, a case that passes today, so it trades one red for another and I reverted it.

**The fix I would try next, for whoever owns this** (`scripts/coord-status` is another live claim's,
which is the other reason I stopped): stop hardcoding `2026-07-17T00:00:00Z`. Derive the fixture's
heartbeat and its commit dates from the REAL current time — heartbeat at `now - (threshold + 1)`,
commit dates the same, `SSC_COORD_NOW_EPOCH=now`. Then the fixture is stale by both signals, the real
claim's history is 11 days old and cannot rescue it, no rename is needed, and nothing depends on a
literal date drifting past real history again.

## heartbeat-stale-while-active — the staleness check called a committing agent orphaned
<!-- status: fixed
     lane: apparatus
     area: docs
     gate: tests/coord/claim-activity-overrides-heartbeat.sh
     fixed-in: c24ca1c08 -->

**Re-verified 2026-08-02:** the named gate passes — `claim-activity-overrides-heartbeat: OK (commit
evidence outranks a stale heartbeat field)`. Both cited shas (`c24ca1c08`, and `0c7bba624` for the
gate) resolve and are ancestors of `origin/main`.

**Status: FIXED 2026-07-30** in `c24ca1c08`, gated by
`tests/coord/claim-activity-overrides-heartbeat.sh` (`0c7bba624`).

**Observed.** `v2-backend-matrix-gaps` carried `heartbeat: 2026-07-29T17:12:00Z` while committing to
`main` every few minutes — **13 commits in the very hour** `scripts/coord-status` reported it as
`potentially stale heartbeat` (field age 10.7 h). I triaged it as orphaned **twice** and only a
manual `git log` on its branch stopped me editing `v2/src` underneath an agent actively working
there. A second claim, `int-case-unit-type-pattern`, was in the same state (field 507 min old, last
commit 26 min ago).

**Why the field was not at fault.** AGENTS.md tells agents to heartbeat on a **material status
change, not as running commentary**, and the threshold was raised 20 → 45 min on 2026-07-28 for
exactly that reason: 202 of 253 commits in one 6-hour window carried no code. So "heartbeat more
often" is the wrong prescription — it would undo that fix and re-flood the log. The defect was that
the check treated a hand-maintained field as the ONLY evidence of life, while the cheapest and most
reliable evidence — commits — sat unread in git.

**Fix.** `claim_activity_epoch` takes the newest of the claim branch's tip (local *and* remote) and
the last commit touching `.work/active/<slug>.claim`; a claim with commit activity inside the same
45-minute window is reported live, **naming its stale field** rather than silently ignoring it. The
field still matters for an agent with nothing to commit yet (planning, a long build) — it just stops
being the only evidence.

**Verified on the live board, both directions in ONE run** — which is the comparison the old check
could not make at all:

```
live by COMMIT activity (stale heartbeat field, ignored): int-case-unit-type-pattern
    (heartbeat age 507m, last commit 26m ago)
potentially stale heartbeat: bugs-index-machine-readable
    (heartbeat age 518m, no commit inside the threshold)
```

**Still to do — the prose copies.** `AGENTS.md:1140` and the multi-agent skill's triage table both
still present heartbeat age as the verdict. They are not wrong so much as incomplete, and both were
held by other claims (`AGENTS.md` by `bugs-index-machine-readable`; the skill lives in the
`.agents/plugins` submodule). Queued in SPRINT as `HBL-2`. Note
`heartbeat-threshold-stated-in-two-repos` already pins the NUMBER across all three copies — this is
the same duplication one level up, now about the RULE rather than the number.

## v2-front-for-yield-remaining-layouts — two of four `for`/`yield` layouts still miss F, one silently wrong
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: unrecorded
     gate: tests/e2e/v2-front-coverage.sh -->


**Status:** **FIXED 2026-07-28** by `v2-front-for-yield-layouts`. `yield` joins `catch`/`finally`
in `canStartLineId`/`isContId`: it can never START a statement, it CONTINUES the `for` above it.
All four layouts now compile under F and match the INT reference — including the one that used to
compile into a program that ran and was wrong.

**Gate: `tests/e2e/v2-front-coverage.sh`, asserting on the `ssc info --front-report` COLUMN rather
than on output** — because output cannot see this class: when F declines, the F4a fallback
recompiles with the legacy front and the program prints the right answer. The pre-existing
`tests/conformance/for-yield-layout.ssc` contains the def-body layout and was green throughout.
A/B'd 2-of-6 FAIL against the unfixed front; its `--self-test` requires a known GAP to report GAP.

`wasm-scalascript`). **A/B'd against `cd14a97d5~1`: identical before and after that fix, so NOT a
regression from it** — but it does mean the fix's reach is narrower than its commit message sounds,
and the gate could not see the difference.

**Four layouts, measured on the default lane:**

| layout | F | result |
|---|---|---|
| `val r = for` ⏎ gens ⏎ `yield e` at **col 0** | ✅ compiles | `2` — correct |
| `val r = for` ⏎ gens ⏎ **indented** `yield e` | ⚠️ compiles | **`__method__: no dispatch for .foreach on 1` — a WRONG program that runs** |
| `val r =` ⏎ **indented** `for` ⏎ gens ⏎ `yield e` | declines | correct output via the F4a fallback |
| `def f() =` ⏎ **indented** `for` ⏎ gens ⏎ `yield e` | declines | correct output via the F4a fallback |

**Why the existing gate is blind to this.** `tests/conformance/for-yield-layout.ssc` compares
OUTPUT, and rows 3–4 produce the *correct* output — the fallback recompiles them with the legacy
front. So the case is green while F still cannot lower two of the four shapes. Row 4 is literally a
shape that gate contains, and the gate passes. **An output gate cannot measure which front
compiled the file**; that needs `ssc info --front-report` or the dualrun gate.

**Row 2 is the serious one** and it is the shape this project cares most about: F emits a program
that *runs* and is wrong, rather than failing. It is not new — it predates the layout-opener fix —
but it is now the only one of the four that is silently wrong rather than loudly delegated.

**Fix direction.** Rows 3–4: `for` as the first token of a layout block opened by `=` — the opener
fires on the `=`, so `for` on the next line is inside that block and its own opener never runs.
Row 2: `yield` indented to the same level as the generators is being absorbed INTO the generator
list instead of closing it, so the comprehension desugars with the yield expression as a generator
(hence `.foreach` on an Int).

**Do not gate this with a conformance case alone** — see above. Pair it with
`ssc info --front-report`, which answers "did F actually compile this file" directly.

## v2-front-for-comprehension-guard-line — a guard on its own line is parsed as a generator
<!-- status: open
     lane: apparatus
     area: front
     gate: tests/conformance/for-yield-layout.ssc -->

**Status:** OPEN (found 2026-07-28 by `v2-front-for-yield` — it was MASKED until then: the whole
enclosing shape produced `_err`, so the guard never got far enough to misbehave).

**Symptom.** `ssc: __method__: no dispatch for .map on false` — the guard's boolean is being treated
as the collection of a generator, so the desugarer calls `.map` on `false`.

**Measured** — the discriminator is *one line vs its own line*, NOT braces:

| shape | native | INT |
|---|---|---|
| one line: `for x <- xs if x % 2 == 0 yield x` | `List(2, 4)` | `List(2, 4)` |
| **braced, guard on its own line** | **`.map on false`** | `List(2, 4)` |
| **braceless, guard on its own line** | **`.map on false`** | `List(2, 4)` |
| braceless, no guard | `List(1, 2, 3, 4)` | same |

**Pre-existing, and A/B'd rather than assumed.** The braced form already worked before
`v2-front-for-yield-parse-gap` was fixed, and the guard fails there identically — verified by
reverting that fix, rebuilding, and re-running: `.map on false` on both sides. So it is independent
of the layout-opener change and was simply invisible behind it.

**Deliberately excluded from `tests/conformance/for-yield-layout.ssc`**, with a note in the case
saying why: including it would make that gate red for a reason it does not test.

**Fix direction.** The generator loop in `parseFor` treats every statement in the comprehension body
as `pat <- expr`. A statement starting with `if` and containing no `<-` is a GUARD and must desugar
to `.withFilter`/`.filter` on the accumulated source, not to a new generator. Both self-hosted
fronts, presumably — the one-line path clearly has the guard branch already, so the gap is in the
per-statement (layout-block) path.

## v2-serve-banner-belongs-on-stderr — a server banner is not program output
<!-- status: fixed
     fixed-in: 84bab666e
     lane: apparatus
     area: runtime
     gate: tests/conformance/run.sc -->

**Status:** OPEN, **deliberately deferred** (filed 2026-07-28 by `v2-stub-apply-and-serve-banner`
as option (2) of `v2-serve-banner-missing`, which took option (1)).

`WebServer.start`'s three-line startup banner goes to **stdout** on both lanes, which is how it got
captured into the goldens of every serving example in the first place. A banner is developer
chatter, not program output; on stderr it would not be part of any observable contract.

**Not done opportunistically, and the reason is a measurement, not taste.** It rewrites the golden
of every serving example and needs its own claim plus a full-corpus re-freeze. **Whoever takes it
must check first:** `tests/conformance/run.sc` builds its comparison with
`outputWithFailureContext(out, err, exitCode)`. If that folds stderr into the compared text
unconditionally, moving the banner to stderr changes **nothing** observable and only churns the
goldens — so establish that before touching anything.


**NEW EVIDENCE 2026-07-28 — the deferral now costs a RED RELEASE GATE, which it did not when it was
filed.** `ScalaScript 2.1 standard-only negative toolchain release gate` fails on this, and it is
the last red on that job after a chain of six unrelated ones was cleared:

```
v21-negative-toolchain-release-gate: http-server-provider (vm) MISMATCH (exit 0)
  expected=$'203\npong:/ping'
  got=$'ScalaScript web · http://localhost:35093/  (root: .)\n  (backend=fast)\nCtrl+C to stop.\n203\npong:/ping'
```

Note `exit 0` and the expected text present and correct — the banner is the entire difference, which
is exactly this entry's argument. Runs 30384832575 and 30388521933 (per-push jobs all green in both).

It had been failing SILENTLY: the assertion was a bare `[[ $(cmd) == … ]]` under `set -e`, which
aborts printing nothing, and the whole section is unreachable while an earlier gate step fails. It
became visible only once those steps were fixed and the assertions were made to print
expected/got (`1cde350c7`, corrected to stdout-only in `364c3b228`).

**Deliberately NOT worked around here.** Three options were considered:
1. update the gate's expectation to include the banner — blesses stdout as contract and has to be
   reverted when this entry is actioned;
2. loosen the assertion to ignore a prefix — weakening a release gate to accommodate behaviour this
   entry already calls wrong;
3. move the banner to stderr — what this entry proposes, and the only one that leaves the gate
   correct without blessing anything.

(3) is the owner's call, not a passing agent's: this entry already assessed the blast radius across
serving goldens, which is precisely the analysis a drive-by fix would skip. Raising the priority with
the measurement rather than acting on it.

**RESOLVED for the v2 lane 2026-07-29 — and the deferral's premise was wrong.**

The reason this was deferred was an assumed blast radius: "it rewrites the golden of every serving
example and needs its own claim plus a full-corpus re-freeze". Measured, that is not what it costs:

```
$ grep -rl 'ScalaScript web ·' tests/conformance/expected/ examples/
tests/conformance/expected/tkv2-pwa.txt          # exactly ONE
```

One golden, because the other serving examples are skipped by the runner as un-runnable standalone,
so the banner never reached their goldens.

The precondition this entry asked for was also checked, and it came out the *other* way:
`outputWithFailureContext` is `if exitCode == 0 then out` — stdout ONLY on success, stderr folded in
only as failure context. So moving the banner is NOT observationally neutral; it is exactly what
removes it from the compared text.

Fixed on the v2 lane (`NioNativeHttpServerHost`: `Console.out` → `Console.err`). Verified with the
real launcher against the exact assertion that was failing:

```
stdout: $'203\npong:/ping'      # byte-identical to the gate's expectation
stderr: ScalaScript web · … / (backend=fast) / Ctrl+C to stop.
```

Conformance unaffected: `tkv2-pwa` still PASSES (it is `backends: [int]`, produced by v1's
`WebServer`, untouched), and the serving/http slice is 2 passed / 0 failed.

**STILL OPEN: the v1 lane.** `WebServer.start(port, root, log)` uses `log` for the banner and for
nothing else; the CLI passes `System.out` at `v1/tools/cli/.../Main.scala:566`, so one word there
(`System.out` → `System.err`) completes it. NOT done here because that file is held by the
`v2-backend-matrix-gaps` claim — it is a one-line change waiting on a claim, not on a difficulty.
Note that doing it *will* require dropping the three banner lines from `tkv2-pwa.txt`, which also
removes a hardcoded port number (`localhost:18631`) from a golden — a latent flake in its own right.

**v1 CLI half done 2026-07-30** (`ssc serve` now passes `System.err`). **The program-facing path is
NOT done, and it is the one that matters more** — recording it so the entry is not mistaken for
closed:

An `.ssc` program calling `serve()` reaches `WebServer.start` through
`Interpreter.scala` → `InterpreterServerSupport.startServer(..., Interpreter.this.out, ...)`, i.e.
the banner is written to **the interpreter's own stdout — the program's output contract**. Nothing
captures it today (the serving conformance cases are skipped, and `tkv2-pwa` went through the v2 fast
backend, fixed in `fdb239e34`), so this is latent rather than active.

The clean fix is one level up and wider than the CLI: `log` carries ONLY the banner — those are its
three uses in `WebServer.scala` — so the parameter should be retired and the banner printed to
`System.err` unconditionally. That touches `WebServer.scala` plus every caller
(`Main.scala`, `ReplCommands.scala`, `InterpreterServerSupportImpl.scala`), which is why it is filed
rather than smuggled into a one-word change.

**CLOSED 2026-07-30 by `84bab666e`** ("the startup banner goes to stderr on BOTH lanes"), verified by
reading the code rather than by trusting this entry: the three banner lines are
`System.err.println` at `WebServer.scala:144-148`, so the program-facing path this entry called "the
one that matters more" is done. Recording it because a stale OPEN sends the next agent to retire a
parameter that no longer needs retiring.

The prescription above is now WRONG in its premise and should not be followed: `log` no longer
carries the banner, so it is not dead. Its remaining uses in `InterpreterHttpHandler` are all
EXCEPTION diagnostics — `Error: …`, `WS upgrade handler error: …`, `WS handler error: …` (lines 120,
152, 334-379). Whether those belong on stderr too is a real question, but a different and much
smaller one: they fire only on a thrown exception, so unlike the banner they are not in the output of
every serving program. Filed as its own line rather than folded in here, since the argument that
carried this entry ("a banner is developer chatter, not program output") does not transfer unchanged
to a handler's error report — a program may legitimately want to see that.

## ci-status-guard-desc-green-always-red — the CI-health job was red on its own self-test, not on the repo
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 083c78fd6 -->

**Status:** FIXED 2026-07-28 in `083c78fd6`.

**What it looked like.** `Validate ScalaScript` failed on **every** run, so the job that exists to
report health was itself a permanent red — and it named a SHA that does not exist:

```text
ci-status-guard[desc-green]: expected exit=0 got=2
CI UNKNOWN d684e68971c75ac11042f19f84fc32c1070fb064~5
d684e68971c75ac11042f19f84fc32c1070fb064
  reason: no push ci.yml run found for the exact SHA
```

The `~5` suffix is unresolved in the output, and the SHA is printed **twice, on two lines**. Both
are the symptom.

**Cause — two defects one line apart, neither in the production script.** `scripts/ci-status`
already uses `rev-parse --verify` correctly; only the guard's fixture did not:

```bash
DESC_SHA="$(git rev-parse origin/main 2>/dev/null || git rev-parse HEAD)"
ANC_SHA="$(git rev-parse "${DESC_SHA}~5" 2>/dev/null || echo "$DESC_SHA")"
```

1. `actions/checkout` clones at `fetch-depth: 1`, so `<tip>~5` does not exist in CI. Locally, with
   full history, it always did — which is why this passed on every developer machine.
2. **`git rev-parse` without `--verify` does not fail cleanly.** On an unresolvable revision it
   ECHOES the argument to stdout *and* exits 128. So `||` did not replace the value, it **appended**
   one: `ANC_SHA` became two lines. `ci-status` was then asked about the literal string `<sha>~5`.

The second defect is the transferable one: `cmd 2>/dev/null || echo fallback` is only a fallback if
`cmd` prints nothing on failure. `rev-parse` is a trap here; `--verify -q` is the fix.

**Fix.** Build the pair with `git commit-tree` — two real commits with a real parent edge written
straight into the object database: no refs, no index, no working tree, nothing to clean up, and no
dependence on checkout depth. The ancestry stays real, which was the reason the original used real
commits at all. Identity is passed by env because a CI checkout has no configured `user.email`.

Three assertions were added so the fixture cannot degenerate quietly (both SHAs present and
distinct, ancestry real, ancestry **not** symmetric — otherwise the `desc-none` negative proves
nothing). **Both were verified to fire**, by collapsing the pair and by dropping the parent edge.

**Evidence.** Reproduced on a `git clone --depth 1` of this repository, which reproduces CI exactly:
red with the message above before, `ci-status-guard: PASS` after; full clone also PASS.

## v2-native-error-printed-but-exit-zero — reported, NOT reproducible after the Mirror fix, now guarded
<!-- status: fixed
     lane: apparatus
     area: front
     gate: tests/e2e/v2-error-diagnostic.sh
     fixed-in: 4f5ecf261 -->

**Re-verified 2026-08-02 at `f9af6728d`** (the classification was `unknown`, which means nobody had
checked): `tests/e2e/v2-error-diagnostic.sh` runs **15 ok, 0 FAIL**. The invariant it pins — if the
run prints `ssc: <error>` the exit code must be non-zero — holds.

**Status:** **NOT REPRODUCIBLE** on `origin/main` after `4f5ecf261`; the invariant is now gated so
a return cannot be silent. Raised 2026-07-28 in rozum by the agent that fixed
`v2-mirror-isproduct-stub`: *"that example EXITS 0 while printing its error, so an exit-status check
sees success."*

**Why it deserved a look even though the observable is gone.** This is the worst failure shape
there is: every `if ssc run …; then`, every CI step, every script that checks a status reads a
printed error as success. It is invisible to exactly the machinery meant to catch it.

**Measured.** `examples/rozum-agent-schema-derived.ssc` now prints `Done / Derived posted. /
Explicit posted. / 2` and exits 0 **correctly** — the `Stub("Mirror.isProduct")` reaching an `if`
condition was the bug and it is fixed, so the trigger no longer exists. Ten shapes probed with the
status captured directly (never through a pipe, which yields the last command's status):
uncaught throw, unbound name, index-out-of-bounds, unbound qualified call, parse error, `10 / 0`,
unresolved method in an `if` condition, unresolved method in a plain call, unresolved predicate on
an Int, malformed `derives Mirror`. **All ten print `ssc: …` and exit 1.**

**Decision.** No fix invented — the trigger is gone and a fix for an unreproducible failure is a
guess. What lasts is the invariant: **if the run prints `ssc: <error>`, the exit code must be
non-zero.** Six cases now assert it in `tests/e2e/v2-error-diagnostic.sh`, and the assertion is
mutation-tested (injecting a runner that prints a diagnostic and returns 0 produces
`FAIL … printed a diagnostic but exited 0`). Writing it also caught the classic trap in my own
gate: `set -e` aborted the script the moment a probe exited non-zero — the expected outcome — so
`rc=$?` never ran and the whole section silently vanished from the output. `set +e` around exactly
those two lines.

**Still open, deferred to BACKLOG with the reasoning:** auditing the *nested* runner paths (the
ASM→VM link-time fallback and the F-delegation re-run) for a swallowed non-zero status. That is
where a fail-open of this shape would most plausibly hide, but it is a real audit with no live
symptom to anchor it.

## v2-list-apply-method-stub — `xs.apply(i)` is `Stub` on the native lane while `xs(i)` works
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 59e912e7f
     gate: tests/conformance/list-apply-method.ssc -->


**Status:** **FIXED 2026-07-28** by `v2-stub-apply-and-serve-banner` (`e34938737`). Two VM dispatch
arms next to the existing Map/Array `apply` arms route a cons/nil receiver to `Prims.listIndex` —
the SAME helper the `App` form uses, so the two spellings cannot drift, including the
out-of-bounds message. A/B on one worktree, arm reverted and restored: `xs.apply(1)` went `Stub` →
`20` while `xs(1)` stayed `20`.

**Deliberately not fixed in the frontend**, which this entry's roadmap line warned about: lowering
`.apply` to an application there breaks `object O { def apply(x) }`, whose `O.apply(1)` must reach
the user's method. That shape was A/B'd and is unchanged — it fails identically before and after,
and it fails LOUD, so it is a pre-existing gap now filed as `v2-object-apply-unbound`.

**Gate:** `tests/conformance/list-apply-method.ssc` — literal and computed indices, a `String`
list, and an index inside a HOF, since dispatch happens at run time and a constant-folded index
would not exercise it. PASS on INT/JS/JVM/V2; the golden's first line is `10` where the pre-fix
build printed `Stub`.

**Status:** OPEN (found 2026-07-28 by `corpus-contract-refresh-freeze` while probing list indexing
for a different fix).

**Reproduction** (real harness, fresh `sbt installBin`):

```scalascript
val xs = List(10, 20, 30)
println(xs(1))
println(xs.apply(1))
```

| lane | `xs(1)` | `xs.apply(1)` |
|---|---|---|
| `bin/ssc-tools run --v1` | `20` | `20` |
| `bin/ssc run` (default, native) | `20` | **`Stub`** |

**Root cause.** The two spellings lower differently and only one is implemented. Dumped IR:

```
xs(1)        -> (app (prim cell.get (global xs__cell)) (lit (int 1)))
xs.apply(1)  -> (prim __method__ (lit (str "apply")) (prim cell.get (global xs__cell)) (lit (int 1)))
```

The application form is handled; `__method__("apply", <list>, i)` has no arm — `Runtime.scala` has
`apply` arms for `MapV` and two `ForeignV` shapes and nothing else — so it falls through to the
ambient path and yields a `Stub` sentinel, at exit 0. `.apply` is *by definition* application, so
the two must agree for every receiver, closures included.

**Where the fix goes.** A late fallback arm in the VM (`case (recv, "apply", args) => callValue(recv,
args)`, after the Map/Foreign arms) is one line and covers every receiver. A front-side rewrite of
`.apply(a)` into `(app recv a)` looks tempting but is WRONG in general: `object O { def apply(x) }`
lowers to a mangled `O_apply` global, and `O` itself is not applicable — the rewrite would break it.
So: VM, not front. `v2/src` was held by a live claim when this was found.

## scljet-write-freelist-errors-are-swallowed — reclaiming pager accepts corrupt staged graphs
<!-- status: open
     lane: apparatus
     area: front
     gate: tests/conformance/run.sh -->

**Status:** OPEN (found 2026-07-28 during SPRINT `SC-2a.1`;
reporter: Codex production-completion audit; baseline `b6967a79f`).

**Real-harness reproduction.** All 16 negative observables in
`tests/conformance/run.sh --only 'scljet-freelist-write-corrupt' --no-memo`
return `Right` on INT and JS. They include head/count disagreement, range and
exact-count failures, out-of-range next/leaf pointers, a trunk cycle, duplicate
and overlapping pages, an excessive leaf count, a short staged trunk, page 1,
unsupported reserved-byte/auto-vacuum modes, and a caller page-size mismatch.
The apparatus deliberately deletes through a different B-tree leaf from the
staged trunk, so an eventual `Left` cannot be a proxy caused by corrupting the
target data page.

**Root cause / impact.** `readExistingFree` returns a plain `List[Int]`, maps
every header/trunk read failure to an empty or partial graph, narrows unchecked
u32 values to `Int`, and never verifies roles, cycles, duplicates, or the exact
header count. `applyFreelist` also returns early for an empty newly-freed list.
A direct staged-helper caller can therefore discard freelist metadata, overwrite
page 1, or stage an out-of-range trunk that extends the database at commit.

**Fix acceptance.** Validate the complete staged graph through an
`Either[ByteError, ...]` preflight at every `pagerDeleteRebalanced` entry, reject
unsupported layout modes and page-size disagreement, validate the union with
newly freed pages, and prove two pre-commit reclaim batches read the staged
header/trunks. Every negative case must fail before returning a changed pager.

## corpus-contract-roster-drift-48-cases — the always-on differential gate exits 1 for bookkeeping, not for a regression
<!-- status: fixed
     lane: apparatus
     area: runtime
     gate: tests/conformance/contract-roster.tsv
     gate: tests/e2e/freeze-consistency-gate.sh
     fixed-in: dd04074bd -->

**Status:** OPEN — **measured, not mine to fix** (found 2026-07-28 by `f-try-multistmt-def-body`
while rostering one new conformance case; the cases span at least six live claims, so a single
agent classifying all of them would be stepping on other people's work).

**What was measured.** `tests/conformance/contract-roster.tsv` has exactly one commit
(`fc5f07f28`, the freeze that introduced it). Since then **48 cases** were added to the corpus
without a roster row. `contract.sc` treats "a case absent from the frozen roster" as RED and
`System.exit(1)`s on it (contract.sc:788-820), so the Corpus Contract is currently red on
`origin/main` for pure bookkeeping.

Reproduce (seconds, runs nothing):

```bash
scala-cli --server=false tests/conformance/contract.sc -- --list | sort > /tmp/selected
tail -n +2 tests/conformance/contract-roster.tsv | sort > /tmp/roster
comm -23 /tmp/selected /tmp/roster          # 48 names; the reverse direction is EMPTY
```

The 48 (2026-07-28): `coroutine-demo`, `coroutine-native-lifecycle`,
`distributed-callback-user-throw`, `durable-save-run`, `effects-handler`, `fence-attr-code`,
`fence-doc-block`, `generator-callback-user-throw`, `int-literal`, `int-width`,
`js-int-boundary-const-lambda`, `js-int-division-by-zero`, `json-self-hosted-import`,
`list-combinators`, `map-ops`, `markdown-html`, 20 × `scljet-*`, 8 × `std-ui-native-*`,
`try-catch-exception-delivery`, `try-catch-io-failure`, `v2-system-clock`,
`w5-scala-fence-width-parity`.

**Why it matters more than a stale file.** This is the SAME failure mode as
`corpus-contract-never-green`, one step downstream. A gate that is red for a reason nobody acts on
stops being read, and the next *real* regression lands inside that noise. The gate is not lying —
it is honestly red — but the effect on behaviour is identical to a lie.

**Not a licence to skip.** The remedy the tool itself prints is the right one: *classify each new
case, then refresh the paired freeze with one full run* (`--update-baseline`, which rewrites the
whole non-PASS matrix AND the roster, and which the tool already refuses to run on a scoped
selection — `corpus-baseline-update-scoped-run-truncates`). That means one agent, one unsharded
full-corpus run, on a quiet machine.

**Already partly discharged:** `try-multistmt-body` was rostered by the finder with evidence
(scoped contract run, 9/9 PASS cells on int/js/v2), and the digest arithmetic was verified by
reproducing the recorded `baseline-sha256` before writing the new `roster-sha256`. The other 47 are
untouched on purpose — each needs its own classification, and several belong to claims that are
live right now.


**CLOSED 2026-08-05 — the drift was 2 cases, not 48, and that is the first thing worth recording.**
Between 07-28 and now the corpus absorbed almost all of it: `contract.sc --list` says 558 cases,
the roster had 556, and the two missing were `credential-vocabulary` and
`generic-ctor-and-array-alloc`. Both were run through the contract before being rostered — 5/5 PASS
cells — so a roster row with NO baseline row is their correct classification ("absent = expected
PASS"). Scoped run afterwards: `✓ contract GREEN`.

So this never needed the six-claim coordination the entry (rightly, at the time) declined to do.

**A count I got wrong on the way, which is why the prevention is shaped as it is.** My first
measurement globbed `tests/conformance/*.ssc examples/*.ssc` and reported **37** missing. The tool
says 2. The glob was wrong by 35 because `contract.sc` applies rules a directory listing does not
know. Re-deriving the case set is precisely the mistake that lets drift back in.

**Prevention — invariant I5 in `tests/e2e/freeze-consistency-gate.sh`, which runs per PUSH.** It
ASKS `contract.sc --list` for the case set and diffs it against the roster, in both directions
(missing rows, and rows naming a case that no longer exists). Two properties make it maintainable:

- **Derived, never frozen.** It compares two sets; a frozen COUNT would go stale on the next case
  added — which is how the roster drifted in the first place.
- **Cheap enough for the push path**: `--list` is 0.49 s warm, less than the rest of that gate
  (1.1 s). Drift used to surface only in the nightly, long after the push that caused it.

Verified both ways: green on the true tree, and planting a missing row — with the header rehashed,
so the existing pairing invariant could NOT be what fired — makes it fail naming the case. If
`scala-cli` is absent the gate says so and skips, rather than passing silently.

## uniml-yaml-tag-percent-decoder-quadratic — legal long tags trigger repeated prefix copies
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 3341a35a9 -->

**Status:** FIXED 2026-07-28 in `3341a35a9` (found during independent
UPR-2a.1 review; SPRINT `UPR-2a.1`).

**Measured reproduction.** At local feature commit `7ba41ae36`,
`YamlTagEnvironment.decodePercentEscapes` appends every literal/decoded code point
with `result = result + piece`. On JDK 21, projecting otherwise equivalent primary
tags took approximately 314 ms at 128 KiB, 830 ms at 256 KiB, 3.19 s at 512 KiB,
and 12.17 s at 1,000,000 characters; an undefined-handle control which skipped the
decoder took 2–11 ms. A source may contain many maximum-line tag spellings.

**Impact.** The new semantic path introduces a bounded but exploitable CPU/allocation
amplifier before the broader UPR-2d hardening slice.

**Fix and verification.** Representation expansion now performs one monotone
percent-syntax scan and preserves `%HH` verbatim. The temporary parser-event
decoder was removed entirely by `024d80524`; the normative event path now reuses
the preserved representation instead of allocating a second decoded spelling.
JVM/Scala.js suites, portable lint, the two-round build-isolation gate, and the
exact 402-case corpus baseline all pass; independent reviews accepted the linear
portable path.

## uniml-yaml-corpus-gate-exception-isolation — post-capture hashing can erase observed axes
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 70068fd7b -->

**Status:** FIXED in `70068fd7b` (found 2026-07-28 by the independent
`uniml_code_test_audit` sub-review while qualifying UPR-1a). The affected gate existed only in the
local YAML corpus commit `0720175d`; its push was stopped when the review retracted ACCEPT.

**Reproduction.** `YamlScheduleEvaluation.observation` computes the SHA-256 of reconstructed text
after `YamlCaptured[YamlReconstruction]` has returned. A parse hook that reconstructs a lone UTF-16
surrogate makes `YamlCorpusUtf8.encode` throw there. The outer per-case `safely` then replaces the
whole result with `crashedOutcome`: already collected parse/snapshot/reconstruction observations
are lost and the semantic hook is never attempted. Separately, `YamlCorpusOutcome.baselineRow` and
`YamlCorpusReport.baselineRows` canonicalize outside the per-case capture, so an unpaired surrogate
inside an observed semantic value can make the report itself throw instead of recording a red row.

**Impact.** The official pinned inputs are valid UTF-8, so the frozen 402-case numbers are stable,
but the apparatus fails its hostile-input and exception-isolation contract. A future parser
regression could turn a detailed per-axis red into an opaque outer crash or prevent later cases/axes
from being measured.

**Fix acceptance.** Add fail-first malformed-UTF-16 tests. Digest/canonicalization failures must
become deterministic axis errors without discarding already observed values; semantic evaluation
and later cases must still run; baseline rows/digests must remain total and distinguish the failure;
`LinkageError` and other fatal VM errors must still propagate. Re-run YAML JVM+Scala.js suites,
the 402-case census/expected-red strict gate, standalone `uniml`, portable lint, and affected
conformance before requesting renewed independent acceptance.

**Root cause and verification.** Hashing and canonical rendering were moved inside explicit
per-axis/per-row/per-case capture. Deterministic ASCII fallbacks retain every observable and escape
malformed UTF-16; semantic evaluation and later cases continue, while `LinkageError` and other
fatal errors still propagate. The fail-first regression passed on JVM and Scala.js (17/17 each),
the official census remained 402 cases / 94 expected errors / zero crashes, and the strict gate
remained expected-red on exactly 290 cases with unchanged baseline/category digests. Portable lint,
the standalone build, affected conformance, and renewed independent acceptance all passed.

## f-validateNoReader-rejects-plugin-externs — the F-vs-legacy guard counts a legitimate `extern def` as a coverage gap
<!-- status: open
     lane: apparatus
     area: front
     gate: tests/conformance/scljet-mutate-update.ssc -->

**Status:** OPEN (found 2026-07-28 by `v2-board-and-f5b` while measuring why F still delegates).
Affects `RunNativeV2.validateNoReader`, i.e. the F4a delegate-fallback decision — not F itself.

**What happens.** `validateNoReader` accepts a global only if it is a top-level `def` in the same
program or starts with `@`:

```scala
def globalOk(g: String): Boolean = defNames.contains(g) || g.startsWith("@")
```

A plugin intrinsic declared as `extern def` (e.g. `extern def jvmVfsOpen(path: String, readOnly:
Boolean, create: Boolean): JvmVfsResult` in `scljet/jvm-vfs.ssc`) is neither, so F's program is
rejected and the run silently delegates to the legacy front.

**The evidence that this is a mis-classification and not an F gap:** the LEGACY front emits exactly
the same thing. Measured on `tests/conformance/scljet-mutate-update.ssc`, both fronts produce
`(global jvmVfsOpen)` with no corresponding `(def …)` — the name is resolved by the plugin registry
at run time, which is the design. Legacy is never validated, so only F is punished for it.

**Impact.** Every program touching a plugin extern delegates unconditionally, regardless of what F
can actually compile. That is a permanent, invisible tax: double lowering on each run, F's output
discarded, and the program counted against F's breadth. It is a strong candidate for the dominant
delegation cause, but **that is NOT yet measured** — see the caveat below, and measure it before
repeating the claim.

**Fix direction (a decision, not a mechanical change).** Do not simply loosen the check: it exists
because genuine F gaps also surface as unbound globals, and loosening it trades a loud delegation for
a silent wrong answer. The information needed to tell them apart exists — the program's own `extern
def` declarations, and the plugin registry — so the guard should accept *declared* externs and keep
rejecting unknown names. Owner call, since it changes the F4a fallback contract.

**MEASURED 2026-07-28 — the caveat above is now resolved, and the hypothesis holds.**
Full corpus census via the new `ssc info --front-report` (`d684e6897`), 347 files,
one decision per file, no execution:

| decision | files | meaning |
|---|---:|---|
| `F` | 95 | F compiled it; no delegation |
| `BOTH-UNBOUND` | 213 | both fronts emit the same unbound global — the mis-classification in this entry |
| `GAP` | 33 | F's own coverage hole |
| `ERROR` | 6 | 5 parse failures (`_err` sentinel), 1 malformed fence |

So of the **246 delegations, 213 — 87% — are not F's fault at all**, and F's real
coverage hole is **33 files (~10% of the corpus)**, not 246. The entry called the
extern class "a strong candidate for the dominant delegation cause"; it is the
dominant cause, by a wide margin.

**One extern accounts for over half of it.** Grouping the 213 by the unbound name:

```text
115  jvmVfsOpen        ← the exact `extern def` this entry names (scljet/jvm-vfs.ssc)
 19  runActors
  7  self
  7  sc
  7  jsonCoreParseTolerant
  5  element
  5  __yamlSection__
```

`jvmVfsOpen` alone is 115/213 (54%) of the mis-classified delegations and 33% of
the whole corpus. Accepting *declared* externs — the fix direction already written
above — would move roughly 213 files from "delegated" to "F", changing F's measured
breadth from 95/341 (28%) to ~308/341 (90%) **without touching F itself**. That
reframes the remaining work: the 33 `GAP` files are the actual F breadth backlog,
and their causes are already grouped (`q` ×6, `handle` ×6, `html` ×4, `summon` ×3,
`effect` ×3, `x` ×3, then singles).

Method note: the census ran in two parts from two worktrees (the first was
interrupted at 145/347 when its worktree was removed mid-run), so absolute paths in
the raw rows differ. Classification is per-file and unaffected. Reproduce with
`ls tests/conformance/*.ssc | xargs bin/ssc info --front-report`.

---

## heartbeat-threshold-stated-in-two-repos — AGENTS.md and the multi-agent skill can drift apart
<!-- status: fixed
     lane: apparatus
     area: plugin
     fixed-in: unrecorded -->

**Status:** **FIXED 2026-07-28** by opus (`heartbeat-threshold-drift-gate`) — gate landed here, and
the submodule copy corrected in its own repo (commit `242501d` in `agent-plugins`, **local, not
pushed**: pushing to another remote is the owner's call). Originally flagged, deliberately not fixed,
by `heartbeat-cadence` the same day.

**The drift was live and harmful, not cosmetic.** The skill said 20 minutes in **seven** places while
`scripts/coord-status` enforced 45. An agent following the skill would declare a claim orphaned at
minute 21 and take work AGENTS.md says is still live — the exact collision the claim mutex exists to
prevent, reached by READING the rules rather than by ignoring them.

**Fix, in two parts.** The number in the skill now matches, and the skill says `scripts/coord-status`
is the source of truth rather than only restating a figure. More durably,
`tests/coord/heartbeat-threshold-single-source.sh` reads the threshold **from the code that performs
the comparison** — not from the comment beside it, which could itself have drifted — and fails if any
prose copy disagrees.

**Three drafts of the matcher, recorded because each failed in a way worth knowing:**
1. "any minute-figure on a line mentioning heartbeat" flagged the heartbeat CADENCE ("sitting for
   more than ~10 minutes") — a different quantity that is legitimately not 45.
2. Widening the trigger to any `<`/`>` beside a figure then flagged `can't separate confidently in
   <5 minutes` in AGENTS.md — prose with nothing to do with claims.
3. The deciding case is one line in the skill carrying BOTH: *"sits for more than ~10 minutes. Older
   than ~20 minutes → treat as potentially stale."* One clause right, one wrong, so **no line-level
   rule can be correct and complete**. The gate matches PHRASES.

A gate that cries about a correct line is one people learn to ignore, so this mattered more than the
number did.

**Two self-checks, because a checker only ever seen passing is not a checker.** It asserts a
deliberately-wrong fixture is REJECTED and a matching one ACCEPTED, and their output is suppressed —
a green run must not contain the word FAIL, or every human and every `grep -c FAIL` misreads it.
A/B: restoring the old submodule text makes the gate exit 1; with the fix it exits 0.

**Not wired into `ci.yml`** — that file is held by the live `ci-bookkeeping-floods-verdicts` claim.
It belongs beside the other two `tests/coord/` gates in the `Validate` job.

The claim-staleness threshold is written down twice: `AGENTS.md` + `scripts/coord-status` in THIS
repo, and `.agents/plugins/multi-agent/commands/multi-agent.md` in the **agent-plugins submodule**
(a separate repo). Raising it 20 min -> 45 min here leaves the submodule saying 20, so an agent
reading the skill and an agent reading AGENTS.md disagree about when a claim is orphaned.

Not edited from this claim on purpose: committing into another repository to keep a number in sync
is exactly the scope drift `claim-mutex` exists to catch, and it would land unreviewed in a repo
this claim does not own. Someone with the submodule in scope should update it, or — better — the
skill should stop restating the number and point at `scripts/coord-status` as the single source.

## ci-sbt-job-is-28x-the-code-push-interval — the arithmetic no queue policy can fix
<!-- status: open
     lane: apparatus
     area: runtime -->

**Status:** OPEN — **measurement, handed to whoever owns the `sbt` job** (2026-07-28,
`ci-bookkeeping-floods-verdicts`). Not mine to fix: job structure and `timeout-minutes` belong to
`ci-runs-cancelled-under-churn`.

**The numbers** (6 hours on `main`, measured 2026-07-28):

| quantity | value |
|---|---|
| commits | 253 |
| of those, changing code (i.e. creating a run after `paths-ignore`) | **51** |
| mean interval between CODE pushes | **7 min** |
| `sbt — compile and test` duration | **196 min** (17:55:20→21:11:40) |
| ratio | **28** |

Twenty-eight code pushes arrive while one `sbt` job runs. With GitHub's one-pending-run-per-
concurrency-group rule, at most 1 commit in 28 can reach a verdict, and only if a runner is free.

**What this rules out.** Queue management is not the lever. `paths-ignore` removed the 74% of load
that carried no code and drained the backlog from 62 runs to 1 — necessary, and visible — but the
ratio above is unchanged by it, because it was never about the docs commits.

**What is left.** Either the verdict-carrying job gets shorter than the push interval, or the
per-push verdict comes from something that already is. The other three jobs measured
`Validate` 34 s, `Lint` 28 s, `Conformance Suite` 38 min — so the candidate already listed under
`ci-runs-cancelled-under-churn` ("gate the fast jobs as the per-push verdict and run `sbt` on a
schedule") is not one option among several; it is the only shape that fits the arithmetic without
making the tests faster. A scheduled `sbt` still catches everything, just later and in batches.

## scljet-jdbc-nan-binding-diverges — PreparedStatement binds NaN as REAL instead of SQLite NULL
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: b39127f61 -->

**Status:** FIXED in `b39127f61` (found 2026-07-28 by the independently
reviewed live sqlite-jdbc SC-1a differential).

**Real-harness reproduction.** Bind `Double.NaN` with `setDouble` to the
`INTEGER PRIMARY KEY` parameter of an INSERT. Pinned Xerial sqlite-jdbc
canonicalises the value through `sqlite3_bind_double` to SQL NULL and assigns
the next automatic rowid; `jdbc:scljet:` passes `SqlReal(NaN)` into IPK
affinity and returns datatype mismatch. The compare-first matrix reports the
phase/category difference and the missing row explicitly.

**Root cause / fix.** `PreparedStatementHandler` mapped every
`setDouble`, `setFloat`, boxed `Double`, and boxed `Float` directly to
`SqlReal`. Its JDBC boundary now maps NaN to `SqlNull`, while preserving
positive and negative infinity as REAL values (which the IPK affinity layer
then rejects). Keep the pure engine contract distinct: an explicitly
constructed `SqlReal(NaN)` is still a non-integral REAL and must fail IPK
coercion. The phase-aware compare-first matrix passes for all four JDBC binding
routes and verifies the reference-persisted row.

## scljet-ipk-insert-indexed-out-of-order — indexed INSERT rebuild preserves statement order, not rowid order
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: b39127f61 -->

**Status:** FIXED in `b39127f61` (found 2026-07-28 by
`scljet-production-completion` in the live sqlite-jdbc SC-1a differential).

**Real-harness reproduction.** Through `jdbc:scljet:`, create one
`INTEGER PRIMARY KEY` table plus an index, seed rowid 50, then execute prepared
INSERTs whose bound IPKs arrive out of order (for example 4, 51, 7, and -5).
The mutations report success, but the next
`SELECT ... ORDER BY id` fails with `table rowids are not strictly increasing`.
The same bound-value matrix succeeds through Xerial `jdbc:sqlite:`.

**Root cause / fix.** The indexed branch of `executeInsertRows` passed
`existingRows ++ newRows` straight to `reindexTable`, which writes table cells
in list order. INSERT now uses the same `sortRowsByRowid` ordering/duplicate
backstop as indexed UPDATE before rebuilding the table and its indexes. The
portable regression forces a new key between two existing keys on INT+JS; the
live differential compares outcomes and rows with SQLite, reopens the
SclJet-written file, and gets `PRAGMA integrity_check = ok`.

## scljet-sql-numeric-literal-grammar-gaps — signed VALUES, exponent, and hex literals are incomplete
<!-- status: open
     lane: apparatus
     area: front -->

**Status:** OPEN (found 2026-07-27 by `scljet-production-completion` while
running the assembled IPK-affinity matrix). This is a declared SC-8 grammar
slice; SC-1a must make every unsupported form fail before mutation.

**Real-harness reproduction.** `INSERT INTO t VALUES (-5)` fails with
`expected a literal`; bare `2e2` is split into integer `2` plus identifier
`e2`; bare `0x10` is split into integer `0` plus identifier `x10`. Reference
SQLite inserts rowids `-5`, `200`, and `16`. TEXT `'0x10'` is deliberately
different and must remain a datatype mismatch under affinity.

**Root cause / required slice.** The numeric lexer starts only on a decimal
digit, supports only `digits` and `digits.digits`, and INSERT `VALUES` accepts
one unsigned literal token rather than a signed expression. SC-8 must add the
full SQLite signed decimal/exponent/hex source-literal grammar and differential
vectors without changing the distinct TEXT-affinity grammar.

## scljet-update-ignores-unconsumed-numeric-tail — mutation parsers can execute a recognized prefix
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: a00db1967 -->

**Status:** FIXED in `a00db1967` (found 2026-07-27 by
`scljet-production-completion`; reproduced through the assembled v1 runner and
real `jdbc:scljet:` driver). The fail-first INT+JS regression and the
post-rebase SC-1a sqlite-jdbc/file matrix now pass.

**Real-harness reproduction.** From rowid 100,
`UPDATE t SET id = 2e2 WHERE id = 100` moves the row to 2, while
`UPDATE t SET id = 0x10 WHERE id = 100` moves it to 0. The lexer leaves `e2`
or `x10` plus the intended WHERE tokens, but UPDATE execution ignores that
unconsumed tail. Reference SQLite moves to 200 and 16 respectively.

The fail-first portable gate also proved sibling paths on both INT and JS:
`INSERT INTO t SELECT 2e2` inserts rowid 2 after discarding `e2`;
`CREATE TABLE rogue(a) garbage`, a body-less/unclosed CREATE TABLE,
`CREATE INDEX idx ON t(a) garbage`, a partial-index `WHERE`, and
`DROP INDEX idx garbage` all return success after executing only the recognized
prefix. The immutable input image is not modified in place, but the API returns
a changed image for a statement it never parsed completely.

**Root cause / safety gate.** Mutation parsers do not all require an empty
remaining token stream before execution. UPDATE/DELETE/VALUES INSERT expose a
remainder directly; SELECT currently drops its remainder, and the CREATE
TABLE/INDEX plus DROP INDEX parsers ignore their trailing tokens. SC-1a must
reject any unconsumed or structurally incomplete mutation before opening a
mutable pager; SC-8 then implements the missing literal families. A syntax gap
may fail loud, but it may never partially execute.

## scljet-sql-integer-literal-overflow-wraps-rowid — decimal 2^63 becomes Long.MinValue
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: a00db1967 -->

**Status:** FIXED in `a00db1967` (found 2026-07-27 by
`scljet-production-completion`; reproduced through assembled INT and
`jdbc:scljet:` paths). `scljet-ipk-numeric-affinity` is green on INT+JS, and
the reference sqlite-jdbc/file closure landed green in `b39127f61`.

**Real-harness reproduction.** Bare decimal literal `9223372036854775808` in an
IPK INSERT or UPDATE succeeds as rowid `-9223372036854775808`. Reference SQLite
represents the positive literal as binary64 `2^63` and the IPK assignment
returns `datatype mismatch`.

**Root cause.** `tokenize` accumulates `num = num * 10 + digit` in `Long`
without overflow detection. SC-1a must parse the unsigned decimal magnitude
exactly, keep in-range integers as `SqlInteger`, and represent an out-of-range
decimal token through SQLite's binary64 path instead of wrapping.

Independent pre-push review caught the adjacent signed-boundary hazard:
reclassifying the unsigned magnitude as REAL must still fold exactly
`-9223372036854775808` (including leading-zero spellings) to integer
`Long.MinValue`, without accepting the binary64-rounded
`-9223372036854775809` or decimal `-9223372036854775808.0`. The regression gate
pins all three.

## scljet-jdbc-prepare-defers-query-errors — PreparedStatement accepts invalid correlated SELECT
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: ac33456bc -->

**Status:** FIXED (found 2026-07-28 by the live differential on `ac33456bc`
plus the in-flight correlated fix; fixed in `71d8a6f0e`).

**Real-harness reproduction.** Prepare joined SELECT statements containing a
missing correlated subquery table or malformed correlated subquery through
both `jdbc:scljet:` and Xerial sqlite-jdbc. Xerial rejects each statement
during prepare; SclJet originally returned a `PreparedStatement` proxy and
failed only on execute.

**Root cause.** `ConnectionHandler.prepareStatement` constructed the proxy
without invoking the parser or resolving the current schema. Query validation
first happened inside `executeQuery`.

**Fix:** `71d8a6f0e` adds `validatePreparedQuery`, binds typed NULL placeholders
for the validation pass, reads the current transaction image, and validates
query syntax plus named tables/subqueries before returning the JDBC proxy.
The live differential now observes the same prepare phase and semantic
category for the named cases. This is deliberately not a full compiled or
cached prepared plan: unknown-column and nested-scope resolution remain
partial, and execution still reparses the current image. SC-4a owns that
larger preparation contract.

## v2-f-small-vm-admission-loads-asm — F admission classification breaks VM backend isolation
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 198a9245f
     gate: tests/e2e/v21-plugin-backend-isolation-smoke.sh -->

**Status:** DONE (found 2026-07-27 by `codex` while verifying unlanded
`15b99b5ee`; fixed in `389b36e0f`). The reporter confirmed the fix by rerunning
the exact real-harness isolation gate successfully.

**Pre-fix reproduce.** Build the staged product with
`scripts/sbtc "installBin"`, then run
`tests/e2e/v21-plugin-backend-isolation-smoke.sh`. The VM control exits 1:

```text
native VM loaded a backend ASM or external parser/codec class
```

The pre-fix selective F path asked
`JvmByteGen.requiresStringChunking(firstNestedProgram)` even when the program
was small and would stay on VM. Initializing `JvmByteGen` initialized its ASM
`Handle`/emitter state, so the classification probe itself loaded
`org.objectweb.asm.*`. That violated the existing closed-native VM contract
even though no class was emitted.

**Root cause / fix.** Admission lived on an ASM-owning singleton, so a
supposedly data-only classification initialized the backend. Modified-UTF8
accounting, program-constant walking, and chunk splitting now live in the
ASM-free `JvmBytecodeAdmission`; both `RunNativeV2` and
`JvmByteGen.loadString` use it so selection and emission cannot drift.
Focused tests passed 11/11, the hello/SClJet product gate passed, and
`v21-plugin-backend-isolation-smoke.sh` now passes.

## corpus-contract-usage-missing-arg-separator — documented commands do not reach the script
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: fc5f07f28 -->

**Status:** **FIXED 2026-07-27** in `fc5f07f28`, with the operator forms
verified and documented in `2a796b258` (reporter confirmation pending). Found
by Codex re-review on `a7ef5749c`.

**Reproduce.** The usage block and feature spec showed
`scala-cli ... contract.sc --self-test`. scala-cli consumes that as its own
option and exits 1 with `Unrecognized argument: --self-test`; the script never
runs. The working form is `scala-cli ... contract.sc -- --self-test`.

**Root cause / fix.** Every example omitted scala-cli's argument separator.
Add the load-bearing `--` to all commands that pass Corpus Contract options and
pin the exact working forms in both operator docs.

## corpus-contract-skip-transition-false-improvement — runnable-but-failing is called PASS
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: fc5f07f28 -->

**Status:** **FIXED 2026-07-27** in `fc5f07f28` (reporter confirmation
pending). Found by Codex re-review on `a7ef5749c`.

**Reproduce.** Freeze `x<TAB>*<TAB>SKIP`, then make `x` runnable with current
`x<TAB>js<TAB>FAIL`. The wildcard is in scope and absent from the current
non-PASS rows, so the first key-based classifier emitted both a JS regression
and `IMPROVEMENT — now PASS` for the old SKIP. The same false improvement
appears when the case becomes runnable but has zero eligible requested lanes.

**Root cause.** A missing lane-specific non-PASS row means PASS only because
that exact lane key was observed. A wildcard SKIP is case-level; its absence
means merely "not skipped", not "all lanes passed".

**Fix / done-when.** A frozen wildcard SKIP improves only when at least one
eligible lane cell ran and every observed cell passed. Runnable-with-failure
and zero-eligible-cell transitions do not claim improvement. Synthetic tests
cover all three branches.

## corpus-contract-shards-miss-removals — every production shard suppresses coverage loss
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: fc5f07f28
     gate: tests/conformance/contract.sc -->

**Status:** **FIXED 2026-07-27** in `fc5f07f28` (reporter confirmation
pending). Found by Codex re-review on `a7ef5749c`.

**Reproduce.** `.github/workflows/corpus-contract.yml` runs only four
`--shard i/4` jobs. The first roster implementation computed removals only
when `shard.isEmpty`, so deleting/renaming a rostered PASS-only case produced
an empty delta in every production job.

**Root cause.** The design treated a shard's executed `cases` as its only
knowledge, but `selected` is already the complete unsharded, skip-filtered
universe and is computed before `cases` is sliced.

**Fix / done-when.** Every run without `--only`, including every shard,
compares `R - selectedCurrentCases`; an `--only` diagnostic slice suppresses
global removal inference. A synthetic/selection check proves a shard sees a
removed roster case.

## corpus-contract-zero-evidence-green — an empty selection can pass without comparing anything
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: fc5f07f28
     gate: tests/conformance/contract.sc -->

**Status:** **FIXED 2026-07-27** in `fc5f07f28` (reporter confirmation
pending). Found by Codex review on `e124cc20f`.

**Reproduce.** With a built toolchain, either a misspelled filter such as
`scala-cli --server=false tests/conformance/contract.sc -- --only definitely-not-a-case`
or an empty lane value such as `--only arithmetic --lanes ''` reaches the
normal gate with zero cases/cells. Removal inference is correctly suppressed
for a subset, so the empty delta prints GREEN (`PASS cells: 0/0`) even though
no observable was compared.

**Root cause.** CLI values and the selected case/cell counts are trusted
without a positive-evidence check. An empty slice is represented by the same
empty sets as a fully matching slice.

**Fix / done-when.** Validate option arity, lane names, non-empty lane sets,
duplicates, and positive numeric values; reject a normal gate with zero
selected cases or zero observed lane/skip cells. `--list` remains an
enumeration operation, not evidence. Regression checks must assert the exact
exit-2 diagnostic so a different early failure cannot masquerade as the fix.

## ir-normalize-drops-code-fence-attrs — no SPI backend can see a fence attribute on a code block
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 63b9470c8 -->

**Status:** FIXED for the CARRY (2026-07-28, `ir-fence-attrs-carry`, `63b9470c8`); **the ACT-ON-IT
half is a declared follow-up.** `ir.Content.CodeBlock` now has `attrs`, `Normalize` passes the
attributes it was already holding, and `Denormalize` restores them, so an SPI backend CAN now see
`@side=server`. Pinned by a boundary test in `NormalizeRoundTripTest` that fails without the fix
(`got attrs=List(Map())`); `core/test` 1151/0.

**Still open, and deliberately not claimed here:** no backend yet ACTS on `side`, so a
`@side=server` block is still emitted into the JS bundle — the difference is that the information
now exists at the boundary instead of being unrepresentable. Asserting the acting-on behaviour in
the carry test would have made it pass for a reason it does not verify.

**Also unchanged, on purpose:** `ir.Content.EmbeddedBlock` still has no `attrs`. Foreign fences have
attributes too, but that is a second slot on a second type with its own consumers.

**Historical status:** OPEN — by design today, and the design is the problem (found 2026-07-27 by
`corpus-gate-remaining-reds` while implementing `@doc`).

**Symptom.** `@side=server` on a ```` ```scalascript ```` block is honoured by INT and ignored by
every SPI backend (JS, JVM, Rust, Spark…), so a block meant to be server-only is emitted into the JS
bundle.

**Root cause.** `ir.Content.CodeBlock` has no `attrs` field at all, and `Denormalize.content`
rebuilds `ast.Content.CodeBlock(lang, source, tree, span)` without one. SQL attributes survive only
because `db` and `side` were promoted to dedicated IR fields on `ir.Content.SqlBlock`; the generic
`@key=value` surface has nowhere to live. INT keeps them because it interprets the `ast.Module`
directly, never crossing the SPI.

**Not blocking `@doc`,** which sidesteps it: `Normalize` now DROPS `@doc` blocks instead of trying to
carry the marker across the boundary — a documentation block has no business in a normalized program
IR anyway. Anything that must be *visible* to a backend still needs a real IR field or an `attrs`
map on `ir.Content.CodeBlock`; that is a format change (`SsccFormat` V3 writes IR) and is why it is
filed rather than fixed here.

## v21-explicit-lanes-gate-swift-em-dash-red — JVM launcher stdout is locale-dependent (non-ASCII → `?` on CI)
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 54eae3197
     gate: tests/e2e/v21-explicit-lanes-gate.sh -->

**Status:** FIXED (`54eae3197`, 2026-07-22). Last remaining CI-baseline red (the sbt
`ScalaScript 2.1 explicit provider and target lanes` step, `tests/e2e/v21-explicit-lanes-gate.sh`).
Orphaned pre-existing work — the scljet-VFS siblings it was attributed to were done/released.

**Repro (Linux / C locale):** `LANG= tests/e2e/v21-explicit-swift-provider-smoke.sh` after
`installBin` →  exit 1, silent (bare `[[ ]]`). Under `bash -x` / with the smoke hardened, the check
`'pacs.008 transfer output'` fails: expected `GPI hop: DEUTDEFF — ACCC …`, got
`GPI hop: DEUTDEFF ? ACCC …`. Passes on macOS (UTF-8 locale) — a "local green ≠ CI green" trap.
Reproduced in `catthehacker/ubuntu:act-latest` + `openjdk-21` mounting the built worktree.

**Root cause — NOT scljet-VFS (the claim's prime suspect).** The swift lane runs the `.ssc` example
through `bin/ssc-provider` → `java scalascript.cli.StandardMain`, which `println`s an em-dash (U+2014).
`file.encoding` is UTF-8 by default since JDK 18 (JEP 400), but `System.out`/`System.err` still use
`native.encoding`, which is locale-derived. On the CI runner `LANG` is unset (C locale) and stdout is
redirected to a file (no console), so `native.encoding = ANSI_X3.4-1968` (ASCII) and the em-dash is
replaced with `?`. `java -XshowSettings:properties` in the C-locale container confirms
`stdout.encoding = ANSI_X3.4-1968` while `file.encoding = UTF-8`.

**Fix:** added `-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8` to all three installBin launcher
templates in `build.sbt` (`ssc`/`ssc-standard`, `ssc-tools`, `ssc-provider`) — the same "on every
launcher" precedent as `-Xss`. `.ssc` observable output is now deterministic across locales; ASCII
output is byte-identical (UTF-8 ⊇ ASCII), so no other lane's expected changed (verified: both
scljet-VFS smokes still pass on Linux/C-locale, full gate 3/3 on macOS). Also hardened
`v21-explicit-swift-provider-smoke.sh` to route every assertion through named `fail`/`expect_out`
helpers (its bare `[[ ]]`/`grep`/`cmp`/redirected-run checks exited 1 printing NOTHING — the exact
silent-gate anti-pattern AGENTS.md warns about, and why this needed a manual Docker bisect).

## durable-save-run-verifier-red — effect verifier mis-flags a def that fully handles its own effect
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: af46212c3 -->

**Status:** FIXED (`durable-save-run-verifier-red`, 2026-07-22). Pre-existing CI Conformance red
since `examples/durable-save-run.ssc` landed (`af46212c3`) — the `ssc-tools check examples/*.ssc`
step (the CI "Conformance Suite" job) failed, blocking ALL flip/switch CI verdicts on the repo.
The durable-continuation example was ORPHANED (its sibling task was done/released).

**Repro:** `./bin/ssc-tools check examples/durable-save-run.ssc` →
`examples/durable-save-run.ssc: error: [effect-verifier] 'capture' appears effectful (reaches
Suspend) but declares no effect row (!)`.

**Root cause — verifier FALSE-POSITIVE, not an example leak.** `capture(): Int => Int =
handle { … Suspend.point() … } { case Suspend.point(saved) => saved }` fully DISCHARGES the
`Suspend` multi-effect at the `handle` boundary, so `capture` is genuinely pure (`Int => Int`,
exactly as the spec documents) and correctly declares no effect row. `EffectAnalysis.analyze`'s
name-reachability sees `Suspend.point` lexically inside `capture` and marks it effectful, ignoring
the enclosing `handle`. It only manifested here because durable-save-run puts the `multi effect
Suspend` declaration AND `capture` in ONE fenced block (the analysis runs per-block; effects.ssc /
algebraic-effects.ssc split the effect decl from the discharging code across blocks, so their
per-block `effectOps` set is empty in the def's block → never flagged). Annotating the example
with a row would be a LIE (`capture()`'s caller does not, and must not, handle `Suspend`).

**Fix:** added a discharge-aware `EffectAnalysis.leakingFuns(trees, effectOps)` — a handle-scoped
leak set: an op `Eff.op` counts as leaked only when reached OUTSIDE a `handle` that discharges
`Eff` (effect names taken from the handler's `case Eff.op(...)` patterns, the same extraction the
runtime's `EffectsRuntime.handledOps` uses, plus any explicit `handle[Eff]` type arg). The Typer's
effect-row verifier now consults `leakingFuns` instead of the coarse `effectfulFuns`. The coarse
`effectfulFuns` (consumed by JvmGen/JsGen CPS codegen) and the interpreter's `multiShotEffects` are
left UNCHANGED, so execution/codegen are unaffected — this is a verifier-only refinement.
Files: `v1/lang/core/src/main/scala/scalascript/transform/EffectAnalysis.scala`,
`v1/lang/core/src/main/scala/scalascript/typer/Typer.scala`. Regression tests in
`EffectTyperTest` (discharge accepted; op performed OUTSIDE the handle still flagged — proving
lexical scoping, not blanket suppression).

**Verify:** `ssc-tools check examples/durable-save-run.ssc` OK; example still runs
(run(1)=10 / run(5)=50 / run(42)=420 / prefix 1 time); full `ssc-tools check examples/*.ssc`
= 0 errors (220 examples, only pre-existing shadowing/quoted-macro warnings). EffectTyperTest +
EffectAnalysisVerifierTest green (incl. the existing "still flags a real leak" test); transform +
typer test packages 302/302.

## scljet-unique-index-not-supported — `CREATE UNIQUE INDEX` is rejected and uniqueness is not modelled
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 50d2ca5bc -->

**Status:** FIXED `50d2ca5bc` (landed 2026-07-22; reporter confirmation pending). Found
2026-07-16 by the `scljet-ipk-rowid` differential lane; claimed by Codex on 2026-07-22 as
`scljet-unique-index-not-supported`. Reporter/context: project SPRINT U1-U4; no Rozum
sequence was the original report.

**Repro:** against a SclJet image containing `CREATE TABLE t(a INTEGER, b TEXT)`,
`executeMutation(db, "CREATE UNIQUE INDEX ux ON t(a)")` returns `expected TABLE`.
The tokenizer succeeds, but both mutation dispatchers recognize only `CREATE INDEX`.
Reference SQLite accepts the statement. Reference duplicate probes establish the required
failure shape: `UNIQUE constraint failed: t.a` (or `t.a, t.b` for a composite index).

**Root cause:** `parseCreateIndex` requires `CREATE INDEX`; `executeMutation` and
`executeMutationCountedParams` route CREATE statements by checking only whether the second
token is `INDEX`. The stored/rebuilt `IndexInfo` carries key positions but no uniqueness bit,
so INSERT/UPDATE cannot enforce a unique index even if parsing alone is added.

The implementation audit also found a directly blocking writer defect:
`write.ssc::compareKeys` extracts only `SqlInteger` payloads from the numeric class (so every
`SqlReal` sorts as zero) and returns equality for every BLOB pair. A parser/enforcement fix that
left this comparator intact could still emit a physically misordered unique index. The feature
therefore uses one corrected exact comparator for both B-tree ordering and duplicate detection.

**Safety note:** a parser-only fix is forbidden. It would store `CREATE UNIQUE INDEX` in
`sqlite_schema` while allowing duplicate keys, producing a file whose declared constraint is
false and whose reference `PRAGMA integrity_check` can report a non-unique index entry.
The fix must cover CREATE over existing rows plus INSERT/UPDATE, distinct NULL semantics,
a cross-engine file differential, and the real conformance/JDBC harnesses. Feature contract:
`specs/scljet-unique-index.md`.

**Fix:** `parseCreateIndex` and both mutation dispatchers now carry the `unique` bit;
stored schema SQL is re-parsed into `IndexInfo`, and CREATE plus full-rowset DML validation
run before any rebuilt image is returned. The shared writer comparator now orders INTEGER/REAL
exactly (including above 2^53), TEXT by BINARY order, and BLOB byte-for-byte, so physical B-tree
ordering and duplicate equality cannot diverge. Verification: forced SclJet conformance 103/103
on INT+JS; `scljetJdbcPlugin/test` 63/63 in 6 suites; sqlite-jdbc matches the three rejection
paths and reference `PRAGMA integrity_check` returns `ok` for the SclJet-written REAL/BLOB file.

## v2-coroutine-example-tools-check-resolution — runnable native demo fails static check
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 3cb6209a4 -->

**Status:** DONE (2026-07-21, fix `3cb6209a4`, verification/spec `e2bad7262`; found and confirmed by
codex-q4 in exact-SHA CI run `29858870257` and the same assembled checker path locally; reported with
root cause to `@scalascript` / `@claude-code` in Rozum; SPRINT Q4.4c).

**Reproduce:** build the assembled launchers at the exact SHA, then run
`./bin/ssc-tools check examples/coroutine-demo.ssc` (the CI job runs
`./bin/ssc-tools check examples/*.ssc`). The checker reports `Reference to undefined name` for
`coroutineCreate` at lines 1/2 and `coroutineCancel` at lines 7/8 and exits 1. In the same job, the
full conformance corpus and all 17 selected examples on VM, direct ASM, and `build-jvm` pass, so
successful execution is not evidence that the static-check surface resolves the linked Coroutine
API.

**Root cause / fix:** execution made the Coroutine globals available through the runtime/prelude, but
the standalone document omitted the explicit `std/coroutine.ssc` link required by the static checker.
The demo now imports `Step`, create/resume/suspend/cancel from that module. The exact focused and
all-examples assembled checker commands pass; provider tests remain 9/9, focused conformance remains
3/3, and native VM/direct-ASM/standalone-JAR stdout is byte-identical. The existing all-examples CI
step is the real-path regression and still fails if the link is removed.

## v21-scljet-vfs-standard-gate-inventory-drift — staged provider JARs evade one gate and break another
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: c6cf03634
     gate: tests/e2e/v21-plugin-backend-isolation-smoke.sh -->

**Status:** DONE (2026-07-21, `c6cf03634`; found and confirmed by codex-q4 in the real final
provider-isolation gate; reported with root cause to `@scalascript` / `@claude-code` in Rozum;
SPRINT Q4.4a).

**Reproduce:** after `scripts/sbtc "installBin"`, run
`tests/e2e/v21-plugin-backend-isolation-smoke.sh`. It exits 1 because
`scripts/v21-core-dependency-gate` reports both
`scalascript-scljet-vfs-host_3-0.1.0-SNAPSHOT.jar` and
`scalascript-v2-native-scljet-vfs-plugin_3-0.1.0-SNAPSHOT.jar` as unclassified in the closed standard
layout. In the same staged image, `tests/e2e/v21-native-plugin-boundary-smoke.sh` passes without
inventorying either JAR, so its green result proves nothing about their dependencies or service
boundary.

**Root cause / fix:** feature commit `6131e17a3` added the native SclJet VFS plugin and shared
zero-dependency host to `installBin`, but the two explicit measurement inventories were not updated.
Both now have closed-layout ownership and native-boundary `jdeps` checks; only the plugin must carry
the native ServiceLoader entry, while the host is required not to. Dependency self-test,
closed-layout smoke (27 roots / 129 edges / 43 classified JARs / 0 violations), backend isolation,
and native plugin boundary all pass in the staged distribution.

## coroutine-contract-doc-drift — normative and feature specs disagree with the shipped surface
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 6a9f434e4
     gate: tests/conformance/run.sh -->

**Status:** DONE (2026-07-21, contract `6a9f434e4`, verification `54ebca43d`; found and confirmed by
codex-q4 while specifying `v2-native-coroutine-provider`; SPRINT Q4.1/Q4.4).

**Reproduce:** `SPEC.md` §7.5 says "Three primitive operations" but lists four functions, models
`Step` without the shipped/public `Errored(message)` case, and declares a by-name body while
`v1/runtime/std/coroutine.ssc` and both conformance cases use an explicit `() => T` thunk.
`specs/coroutines.md` separately repeats the old three-intrinsic pre-cancellation wording even though
its own later sections say cancellation landed.

**Fix / verification:** `SPEC.md`, `specs/coroutines.md`, and the new dedicated
`specs/v2.1-native-coroutine-provider.md` now define four functions,
`Yielded | Returned | Errored | Cancelled`, an explicit lazy thunk, terminal error/cancellation
semantics, and one shared Generator/Coroutine `suspend` owner. The feature spec landed before code;
markdownlint and `tests/conformance/run.sh --only 'coroutine-*'` pass.

## coroutine-demo-readme-link-missing — README points to an absent runnable example
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: b8fd4a31c -->

**Status:** DONE (2026-07-21, example `b8fd4a31c`, docs `bfc893d99`; found and confirmed by codex-q4
while specifying `v2-native-coroutine-provider`; SPRINT Q4.3).

**Reproduce:** from the repository root, `test -e examples/coroutine-demo.ssc` exits 1 while
`README.md` lists `[coroutine-demo.ssc](examples/coroutine-demo.ssc)` in its runnable examples table.
The missing file makes the public Coroutines entry a broken local link and leaves the documented
low-level API without the required self-contained example.

**Fix / verification:** the linked example now demonstrates lazy two-way exchange, nesting, and
idempotent pre-start cancellation. Its stdout is byte-identical through the assembled standard VM,
direct ASM, and a freshly built `build-jvm` JAR; README, User Guide, and the feature spec link to the
same checked-in file.

## f-extension-instance-dispatcher-arity — F miscompiles a same-named extension method across 2 instances
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: bcfc47ec8 -->

**Status:** FIXED for the standalone+given collision class (2026-07-21). Root cause was a NAME COLLISION:
a standalone `extension` method `m` was emitted as the bare global `m`, colliding with the bare `m`
DISPATCHER synthesised for the given-body instances — so the runtime resolved `recv.m` to the wrong def
(wrong arity / `Stub`). **Fix (mirrors ssc1-lower :4101/:5458):** a method that has BOTH a top-level ext
and a given-body dispatcher has its standalone impl emitted as `m__ext_default` (extEmit mangle, driven by
a new `mangleExtMethods` = collectTopExtMethods ∩ dispatcher-method-names threaded through cx), and the
bare `m` dispatcher delegates its fallthrough to `m__ext_default` (`edFallback`) instead of returning the
receiver. `collectTopExtMethods` skips `given`/`trait` bodies AND requires a concrete `=` body (`=>` is
token 2,30, body `=` is 2,20) so trait-ABSTRACT ext members never count as top-level (else the dispatcher
would delegate to a non-existent `m__ext_default` → TYPEERR). **Fixes tagless-sealed-dispatch** and the
std-monaderror arity crash; verified fixpoint stage1==stage2 byte-identical, semantic 246/246, dualrun
default 43/43. TWO residual cases were filed (separate bugs, below); tagless-multi-file now FIXED, one open:

- **f-monaderror-none-match** — FIXED 2026-07-21 (`bcfc47ec8`). Root cause: the bare extension-method
  DISPATCHER for a multi-param typeclass given (`MonadError[Option, Unit]`) derived its receiver type-head
  from `innerTypeStr` = the WHOLE joined type-arg string `"Option,Unit"` and fed that to `extTypeTags`. No
  runtime value carries tag `"Option,Unit"`, so the `__isTag__` test ALWAYS failed: with 1 instance the
  dispatcher returned the receiver (None instead of Some(99)); with 2 instances a None receiver fell through
  to the standalone Either impl's `match {Right|Left}` → runtime `match: no arm for None/0`. Fix (mirror
  ssc1-lower firstTypeArg :5339 / collectExtDispatch :5399): apply `firstTypeArg` (depth-0 prefix up to the
  first top-level comma, bracket-aware) to the TC type-args in `collectED2` before `extTypeTags`, so the
  dispatcher tests the CONTAINER type's ctor tags (Some/None). No-op for single-param TCs. Verified: X1
  fixpoint stage1==stage2 byte-identical, semantic 247/247, minimal repro (top-level-statement form, the
  shape the conformance harness wraps) now byte-identical to the reference front + prints Some(99). Real
  multi-file std-monaderror end-to-end equality is EXPECTED (the default front already prints 192 B → the
  fenced input does not trigger extension-group swallowing; F's dispatcher is now byte-identical to default)
  but was NOT observed via `SSC_FRONT=F bin/ssc run` (no staged install in this worktree) — a follow-up
  should confirm it and flip the std-monaderror GAP in `specs/v2.2-p6.5-dualrun.expected`.
- **tagless-multi-file `map`** — FIXED 2026-07-21 (`1a04abdc8`). NOT actually a `map`/builtin-collision case:
  the crash was on Monad's PLAIN `def log`/`def pure` (non-`extension` members) that immediately FOLLOW an
  `extension …` block inside a `given … with` body. Root cause: `skipExtBodyZ` (member-collection helper)
  CONSUMED the depth-0 `;` separator, so `collectOMExtMs` could not tell a glued extension member (deeper
  indent, NO `;`) from a dedented `;`-separated plain `def`, and mis-glued the plain `def` as an extension
  member → its impl emitted receiver-prepended (`lam 2`, arity N+1) + a spurious bare `m` dispatcher →
  `g.m(a)` / `recv.m(a)` crash `arity: 2 expected, 1 given`. Fix: KEEP the `;` (`Cons t rest`) so
  `collectOMExtMs` reads it as the extension-block boundary; `collectEMs`/`collectTopEMs` (top-level ext
  registry, whose members ARE `;`-separated) wrap `skipExtBody` in `skipSemisF`, so keeping the `;` is
  byte-identical for them. Verified: fixpoint stage1==stage2 byte-identical, semantic 247/247 (0 mismatch),
  and F-vs-reference byte-identical stdout on the REAL multi-file path (`ssc1-run-fsub.ssc0` — fence
  extraction + import closure) — OLD fsub crashed there, NEW fsub prints all 4 lines matching default.

(original characterization retained below)

Original status: OPEN, F-lane only (found 2026-07-21, characterized while fixing the extension-member-
collection bug). PRE-EXISTING (fails on pre-fix F too). Distinct from the member-collection fix.

When the SAME extension method name is defined by TWO instances — one inside a `given … with` body and one
as a standalone `extension` — F synthesises a bare dispatcher that type-tests the receiver and forwards to
the matching `g_m`/global, but the arity is wrong: F errors `arity: N expected, N-1 given` at the call site
(or F crashes compiling the pattern). Minimal repro (bare):

```
trait ME[F[_]]:
  extension [A](fa: F[A]) def handleError(h: Int => F[A]): F[A]
given optME: ME[Option] with
  extension [A](fa: Option[A]) def handleError(h: Int => Option[A]): Option[A] = fa match { case None => h(0) case Some(x) => Some(x) }
extension [A](fa: Either[String, A])
  def handleError(h: String => Either[String, A]): Either[String, A] = fa match { case Left(e) => h(e) case Right(x) => Right(x) }
def main(): Unit = { val o: Option[Int] = None; println(o.handleError((n: Int) => Some(99))) }
```
default prints `Some(99)`; `SSC_FRONT=F` → `arity: 1 expected, 0 given`. A SINGLE instance works
(`mono3`); adding the second (standalone Either) `handleError` breaks it — so the fault is the
2-instance dispatcher synthesis (`collectExtDisp`/`emitExtDispatchers`/`edBuild` + the standalone
`extension` emitting a bare `handleError` global that collides with the dispatcher name). Likely fix:
the dispatcher must own the bare method name and the standalone impl must be prefixed (or the dispatcher
must fold the standalone instance in), with a consistent receiver-prepended arity.

**Blocks 3 residuals** in `v2.2-p6.5-dualrun.expected`: std-monaderror (`arity: 3 expected, 2 given`),
tagless-sealed-dispatch (rc=0 SILENT-WRONG, lines 8-9), tagless-multi-file (`arity: 2 expected, 1 given`
— EXPOSED once the extension-member fix removed its unbound-global fallback).

## cli-command-System.exit-kills-the-test-fork — a whole CLASS of green-looking CI reds
<!-- status: fixed
     lane: apparatus
     area: cli
     fixed-in: dcd00d1ab
     gate: tests/e2e/cli-exit-reachability-guard.sh -->

**Status:** FIXED as a class (2026-07-28, `cli-exit-reachability-guard`, `dcd00d1ab`). The class is now
GATED, not merely detected: `tests/e2e/cli-exit-reachability-guard.sh` fails the build if any test can
reach a CLI boundary that ends the process, and it runs in the validate job — part of the per-push
verdict. `scripts/detect-fork-exit` remains the after-the-fact half; this is the before-the-fact half.

**The class was already half-armed when the guard was written.** At object granularity 10 test-to-object
pairs exist, so an object-level rule would have been red from birth and switched off within the day. At
MEMBER granularity — direct exit calls plus a fixpoint over each object's own call edges — exactly one
survived: `OAuthCliTest` called `OAuthCli.run` five times. Not fatal today, and that is precisely the
point: `run` is `status` plus `if rc != 0 then sys.exit(rc)`, so whether it kills the fork depends on
the ARGUMENTS at run time, and all five calls happen to take success paths. The suite was one added
failure-path case away from the exact signature this entry describes. Moved to `status` (13/13
unchanged), which gives the guard a zero baseline and makes it a gate rather than a tolerated warning.

**Proven in three directions**, because a detector only ever observed staying quiet is not a detector:
`--self-test` asserts it FIRES on a synthetic test calling `run` and STAYS QUIET on the same call
against `status`; and reverting the real `OAuthCliTest` back to `run` makes it fire on the real file,
naming it. Known bound, recorded rather than left implicit: reachability is intra-object — a
cross-object call graph needs a real front end, and over-reaching produces the noise that gets guards
disabled.

**Historical status:** OPEN as a class — but **no longer invisible** (2026-07-27). `scripts/detect-fork-exit`
recognises the signature (a fork-exit line with ZERO reported failures), explains what it means, and
points at the suite that was running instead of at a failing assertion that does not exist. Wired
into CI behind `if: failure()` so it speaks only in the situation it is about, and behind `|| true`
so it can never turn a red step green. It ships with a `--self-test` asserting BOTH verdicts — fires
on a silent fork exit, stays quiet on an ordinary reported failure and on a clean run — because a
detector only ever observed staying quiet is not a detector; also checked against a real sbt log.

**The live instance is CLOSED 2026-07-27** by opus (`cli-oauth-exit-in-tests`), and the "~200 sites"
figure turns out to overstate today's exposure by two orders of magnitude.

**Measured, not assumed.** The count that matters is not `System.exit` sites but sites REACHABLE
from a test in-process. Of the six helper files this entry tabulates, tests reference exactly one:
`OAuthCli.run`, from `OAuthCliTest`. No test invokes `Main.main` or `StandardMain` in-process either,
so nothing else in `tools/cli` is currently reachable. `EmitCommands`, `ClusterCommands`,
`PluginCommands`, `SwiftUiCommands` and `LockCommands` — 105 sites between them — have zero test
callers today. They stay latent (a new test calling one re-arms them), but they are not live.

**Fix.** `OAuthCli` now computes an exit code: `status(args): Int` holds all the logic and every
helper returns 0/1/2, while `run` — the single boundary `Main.scala` calls — is the only thing that
may end the process. 13 `sys.exit` sites became 1, and it is not reachable from a test.

**The old test file was itself evidence.** Its header said it tested only "the offline paths that
don't call sys.exit" — the defect had shaped the tests to avoid every path that could catch it. The
seven failure-path cases that were impossible before now assert the status: unknown subcommand,
missing args for mint/introspect/discover/jwks/dcr-register, and a token that does not verify. A
success-side case keeps the suite from passing by returning non-zero for everything. 13/13.

**A/B — the signature reproduced deliberately, then removed.** With a throwaway test calling the
exiting boundary `run`, the run printed `Tests: succeeded 13, failed 0` and `All tests passed.` and
then died with `sbt.ForkMain … failed with exit code 2`; the probe test itself never appeared in the
report, having been killed mid-flight. That is this entry's signature exactly, produced on demand.
`scripts/detect-fork-exit` was then pointed at that REAL log — not a synthetic one — and correctly
reported the silent fork exit and named `OAuthCliTest` as the suite that was running.

**Historical status:** OPEN as a class (2026-07-20). One instance is fixed —
`swiftui-real-fixture-system-exit-hides-failure`, closed the same day by `frontend-tui-fetch-refresh`
(`deb5e6c90`). This entry exists so the next instance is recognised in minutes instead of hours.

**The shape.** A CLI *command helper* calls `System.exit(n)` on failure. A test invokes that helper
**in-process**. The exit kills the forked test JVM before ScalaTest can attach exit/stdout/stderr to
the test, so the run shows:

- every suite printing `All tests passed.`
- **no** `*** FAILED ***` anywhere in the log
- and then `Error during tests: … sbt.ForkMain <n> failed with exit code 1`

That is the project's most expensive failure mode in its purest form: the apparatus reports success
while hiding a real defect. In the proven instance the *actual* bug was generated Scala failing on
`selected()` / missing `selectFromView` — invisible until the exit path was removed.

**Why it will recur.** `System.exit`/`sys.exit` appears **459** times in v1 production sources.
259 are in `tools/cli/Main.scala`, where a CLI entry point exiting is legitimate. The remaining ~200
are in *command helper* files, which are exactly what tests call in-process:

| File | exit sites |
|---|---|
| `EmitCommands.scala` | 32 |
| `ClusterCommands.scala` | 23 |
| `PluginCommands.scala` | 21 |
| `SwiftUiCommands.scala` | 16 ← the one that bit |
| `OAuthCli.scala` | 13 |
| `LockCommands.scala` | 13 |

**How to recognise it fast.** `ForkMain … exit code 1` **plus** zero `*** FAILED ***` in the whole
log means "something called System.exit", not "a test failed". Do not go looking for a failing
assertion — find which suite was running when the JVM died, then find the exit call it reached.

**Fix direction (do not paper over the non-zero exit).** A helper reachable from a test must return a
status instead of exiting; the test then asserts on exit/stdout/stderr. That is what the SwiftUI fix
did — it switched to invoking the staged `ssc-tools package --v1 --target macos` command and
capturing its result. Reproduce:
`java -jar … ` style in-process invocation, or run the suite and watch for a fork exit with no
reported failure.

**Done-when:** command helpers reachable from tests no longer terminate the JVM, and a deliberately
failing fixture produces a reported test failure rather than a silent fork exit.

## ssc0c-multifile-uselib-ir-divergence — self-hosted compiler disagrees with the Scala seed across an import
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 3056aa3b8
     confirmed: no -->

**Status:** FIXED 2026-07-19 by `v2-f7-internal-gate` (`3056aa3b8`; awaiting full F7 exact-SHA CI).
Found by the 2026-07-18 v2-state audit at `358facd8e`; re-measured after both the compiler and gate moved.

**Real-harness repro:** run `bash v2/conformance/check.sh` from a clean worktree and preserve its real
exit code (never `| tail`). The focused comparison is the same one the gate performs:

```bash
scala-front:  ssc compile examples/uselib.ssc0
self-hosted:  ssc run bin/ssc0c.ssc0 examples/uselib.ssc0
```

At the audited SHA the gate reported differing Core IR while the single-file and multi-file self-fixpoints
still passed. Fresh full-gate evidence shows that label was secondary and misleading: its default-stack
self-hosted command exits with `StackOverflowError`, retries, and never supplies a valid comparison side.
Running the same self-hosted command on `-Xss512m` succeeds; then the semantic/canonical payload has
converged. `/tmp/v2-f7-uselib-seed.ir` and `/tmp/v2-f7-uselib-self.ir` share all first 2865 bytes. The
remaining exact mismatch is one trailing LF: seed output is 2866 bytes and ends `29 0a`, while self-hosted
output is 2865 bytes and ends `29`. SHA-256 values are respectively
`924205b198d594fdd9683a25dcce48b21f2207ea930d86f988f1ebf652f04975` and
`d435633db63812dc39cf613d50b9cbeffc8dc9eaa080da10dbf748f6d5464a96`.

**Apparatus defect:** `v2/conformance/check.sh` captures both programs with command substitution
(`ua=$(...)`; `ub=$(...)`), and POSIX command substitution strips trailing newlines before comparison.
The gate therefore pre-processes away the only current byte mismatch and can print a false byte-identical
result. The gate must materialize both complete streams and `cmp` them before classification.

**Plan / done-when:** make both compiler CLIs obey one line-termination contract, save and compare complete
streams before classification, and add a real two-file regression that deliberately proves a trailing-byte
mismatch is caught with paths/sizes/diff. Done requires exact bytes for `uselib`, both fixpoints, and the
complete v2 gate; command-substitution normalization, weakening the comparison, or refreshing an expected
blob is forbidden.

**Resolution:** both CLIs now emit one trailing LF, and every compiler differential/fixpoint writes complete
streams to named artifacts before `cmp`. Empty streams fail even when equal; mismatches report paths, sizes,
the first 16 byte differences, and tail hex. A self-test proves `x` differs from `x\n` and that two empty
streams are not success. The persistent two-file fixture and `uselib` compare exactly at 259/259 and
2866/2866 bytes; `CONF_FAST=1 bash v2/conformance/check.sh` exits 0 with 408 ok / 0 FAIL.

## newfront-scala-spike-jvm-test-links-on-js — shared filesystem suite breaks Scala.js
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: aca439fcc -->

**Status:** FIXED (2026-07-17, `ci-last-red`, coordinated with the stale `v3-newfront-p1-toplevel`
owner in rozum; no objection). `ScalaSpikeSpec.scala` moved out of the shared crossProject test dir
`uniml/core/src/test/scala/...` into the JVM-only `uniml/core/src/test-jvm/scala/...`, wired via
`unimlCross`'s new `.jvmSettings(Test / unmanagedSourceDirectories += .../src/test-jvm/scala)` — the
same convention `unimlYaml` already uses. The filesystem-bound suite is no longer linked for
Scala.js. VERIFIED: `unimlJs/Test/fastLinkJS` succeeds (was 13 linking errors) and
`uniml/testOnly ...ScalaSpikeSpec` runs 60/0 on JVM. The pure spike helper `ScalaSpike.scala`
(no filesystem refs) stays shared and links for JS unchanged. Was: `unimlJS / Test / fastLinkJS`
failed its core module analyzer on `ScalaSpikeSpec.testFun$proxy59/60` with non-existent
`java.io.File` / `java.nio.file.Files` classes/methods (reconfirmed 2026-07-17 by `ci-red-main` in
`scripts/sbtc "test"` at `aca439fcc`).

**Root cause / real-harness evidence.** The corpus projection/batch/output tests use host files and
live in `uniml/core/src/test/scala/.../ScalaSpikeSpec.scala`, a source directory shared by the JVM
and Scala.js sides of `unimlCross`. Scala.js therefore links JVM-only test bodies even though the
harness is intrinsically filesystem-bound. Current diagnostics name `Files.deleteIfExists`,
`Files.readAllBytes`, `Files.writeString`, `Files.createDirectories`, `File.listFiles`, and
`File.toPath`; this is a linker failure before JS tests execute, not an assertion to skip.

**Expected/fix plan.** Move the filesystem-bound spike suite (or split only its host-file tests) to
the crossProject JVM test source, preserving the parser/projection tests on every backend only where
they remain platform-neutral. The aggregate must link and execute its JS tests, while the JVM suite
must still run the real C_min and projection assertions. Do not add fake `java.io` shims or silence
the linker. This overlaps the stale clean newfront claim; `ci-red-main` records and consumes the
owner fix but does not edit it without takeover authority.

## coord-status-ignores-heartbeat-age — old claims with live worktrees look current
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 52e1d0814
     confirmed: no -->

**Status:** FIXED (2026-07-17, `52e1d0814`; awaiting exact-SHA CI confirmation). Found by
`ci-red-main` at `5d932f6a4`. Project coordination rules
define a missing or older-than-20-minute `heartbeat:` as potentially orphaned and name
`scripts/coord-status` as the preferred status check. The script's `stale-claim check` instead tests
only whether a slug heuristically matches any worktree/branch. Consequently the current output says
`no stale-looking claims` while `v3-newfront-p1-toplevel` is about 18 hours old,
`scljet-m3-writes` about 41 hours old, and `v2-swift-nativeui-i18n-json` several days old.

**Root cause / real-harness evidence.** The stale loop never parses `heartbeat:` or computes age;
any matching worktree suppresses the row forever. This is independent of the zero-token branch bug
fixed in `8ad5f4d1e`: exact branch identity answers whether a worktree exists, while heartbeat age
answers whether its owner is still live. Neither observable may pre-classify the other.

**Expected/fix plan.** Parse strict UTC heartbeat timestamps portably on macOS and Linux, compare
against a test-overridable current epoch, and report a distinct potentially-stale heartbeat row at
age greater than 20 minutes even when the declared worktree branch exists. Missing/invalid
heartbeats are stale with a named reason; a fresh heartbeat stays live; a fresh claim whose branch
is absent retains the separate missing-worktree warning. Hermetic tests must fix time and compare all
three outcomes with timestamp/age/branch diagnostics.

**Fix/verification.** `coord-status` now parses strict `...Z` timestamps with GNU/BSD `date`, uses
an injectable epoch only for deterministic checks, and evaluates heartbeat age independently from
the exact branch/worktree match. The hermetic gate covers fresh/live, 1201-second stale/live,
fresh/missing-worktree, invalid heartbeat, and missing heartbeat. The real status now reports all
five old claims with exact timestamp, seconds/minutes, reason, and live/missing branch while keeping
the current `ci-red-main` claim fresh. Shell syntax, the complete gate, and focused conformance pass.

## coord-status-stopword-slug-false-stale — live claim is reported as stale
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 8ad5f4d1e
     confirmed: no -->

**Status:** FIXED (2026-07-17, `8ad5f4d1e`; awaiting exact-SHA CI confirmation). Found by
`ci-red-main` at `39feb9cc3`. A fresh authoritative
`.work/active/ci-red-main.claim` names the live branch `feature/ci-red-main-final`, and that exact
clean worktree exists, but `scripts/coord-status --no-fetch` prints:

```text
maybe stale: ci-red-main (... heartbeat: 2026-07-17T04:25:56Z ...)
```

**Root cause / real-harness evidence.** `significant_tokens("ci-red-main")` yields no tokens: `ci`
is shorter than three characters, while `red` and `main` are explicit stop words. Therefore
`slug_matches_record` has `needed=0` but no loop iteration in which it can return success. The
exact-key fallback also misses because the worktree key is `feature/ci-red-main-final`, not the
claim slug. This is a false stale classification in the coordination apparatus, not a stale claim.

**Expected/fix plan.** Prefer an exact live-worktree branch match from a claim's explicit `branch:`
metadata, retaining the current slug heuristic only for legacy claims without that field. Add a
hermetic regression whose slug has zero significant tokens and whose declared branch is live; it
must reject the old `maybe stale` output while preserving detection of a genuinely missing
worktree. Every mismatch must print the expected claim branch and observed worktree branches.

**Fix/verification.** `coord-status` now parses a claim's explicit `branch:` and compares it exactly
with the collected live worktree branch keys before invoking legacy slug heuristics. The e2e gate
creates a temporary live branch/worktree for `ci-red-main`, proves the old false-stale row is absent,
then points the same claim at a missing branch and requires that exact stale row. Both failure paths
print the expected branch and observed branches. The gate passes and cleans its temporary Git state;
the real current claim now reports `no stale-looking claims`.

## ci-example-typecheck-uses-compiler-free-launcher — green examples fail at the workflow command
<!-- status: fixed
     lane: apparatus
     area: cli
     fixed-in: a421d9077
     confirmed: no -->

**Status:** FIXED (2026-07-17, `a421d9077`; awaiting exact-SHA CI confirmation). Found by
`ci-red-main` in Linux run `29549382274`, SHA `d5492a129`. The conformance job passes the full
corpus 282/282 and all-examples backend parity, then its `Type-check examples (ssc check)` step ran
`./bin/ssc check examples/*.ssc`. The standard launcher correctly rejected that compiler command:

```text
ssc: 'check' requires the optional ScalaScript tools/compatibility tier; run ssc-tools explicitly
or install the full distribution
```

The same staged checkout's `./bin/ssc-tools check examples/*.ssc` exits 0 and reports every example
OK (with only documented warnings). This is launcher routing in `.github/workflows/ci.yml`, not a
typechecker failure and not a reason to put compiler tooling back into the standard distribution.

**Expected/fix plan.** Route only this compiler-bearing CI step through installed `bin/ssc-tools`,
preserve the standard launcher's negative contract, and rerun a focused conformance slice before
push. Exact-SHA Linux confirmation must show the type-check step green and continue to later steps.

**Fix/verification.** `a421d9077` names and invokes `ssc-tools check` in the workflow. Locally the
old standard command still exits 1 with the intended tier message, while the replacement checks the
complete examples glob successfully (documented warnings only). The two actor leader conformance
cases remain 2/2 across INT/JS/JVM before push.

## actor-leader-conformance-has-no-expected — tracked leader cases always skip
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: f403cb952
     confirmed: no
     gate: tests/conformance/run.sh -->

**Status:** FIXED (2026-07-17, `f403cb952`; awaiting exact-SHA CI confirmation). Found by
`ci-red-main` while selecting the affected gate for the multi-backend actor failure.
`tests/conformance/run.sh --only 'actors-leader-protocol' --no-memo` found the tracked case but
printed `SKIP (no expected/actors-leader-protocol.txt)` and finished with `0 passed, 0 failed out of
0 tests`. Therefore the case compared no backend output and could not detect a broken protocol.

The same family has at least one more empty gate:
`tests/conformance/run.sh --only 'actors-cluster-leader' --no-memo` also finds its source, reports
`SKIP (no expected/actors-cluster-leader.txt)`, and ends 0/0. Neither tracked leader scenario
currently compares any backend.

**Expected/fix plan.** After checking actor ownership, run the source on every declared backend,
compare the actual observable output, and add the expected fixture only when they agree; a guessed
file or an expected value copied from one broken lane would pre-judge the result. Keep this separate
from the `BigInt` runtime fix so activation can expose rather than conceal that bug.

**Activation result.** Compare-first execution exposed and blocked on the separate JVM history bug
above. After `34685277c`, normalized INT/JS/JVM bytes agree for both sources. `f403cb952` adds only
those measured observables; a forced wrapper run executes 2/2 cases, with PASS on INT, JS, and JVM
for each rather than the former 0/0 skips.

## jvm-bytecode-sibling-tests-ignore-installed-cli — thirteen assertions cancel before comparison
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 11a9e80e2
     confirmed: no -->

**Status:** FIXED (2026-07-17, `11a9e80e2`; awaiting CI confirmation). Found by `ci-red-main` while
auditing the Linux test tail after `JvmBytecodeRuntimeSeparationTest` was repaired. With the current
installed distribution already built, run:

```text
scripts/sbtc "cli/testOnly scalascript.cli.JvmBytecodeLinkCliTest scalascript.cli.JvmDirectDriverTest scalascript.cli.ReproducibilityTest scalascript.cli.JvmSmapStackTraceTest scalascript.cli.SourceMapJvmTest"
```

The five suites report 6 executed/pass and **13 `CANCELED`**. The split is exact:
`JvmBytecodeLinkCliTest` 1/4, `JvmDirectDriverTest` 0/3, `ReproducibilityTest` 4/1,
`JvmSmapStackTraceTest` 1/2, and `SourceMapJvmTest` 0/3 (executed/cancelled). Eight cancellations
look for compiler-driver jars through `ImportResolver.libPath` / the old `cli/stage` layout even
though `installBin` populated `bin/lib/compiler/jars`; five invoke the fat JAR without the installed
launcher's `-Dssc.lib.path`, so `CompilerLoader` aborts before bytecode or source-map observables are
compared.

**Expected/fix plan.** Make only the reproduced suites consume the supported installed
`bin/ssc-tools`/library tree and derive Scala runtime classpaths from classes loaded by the test JVM,
not a macOS Coursier cache. Preserve an explicit cancellation when a developer truly has not run
`installBin`; once staged, every command must execute and failures must include exit/stdout/stderr.
Decode current binary `.scjvm` artifacts with production `JvmArtifactIO` rather than the stale JSON
readers found behind the cancelled gates. Audit `ClusterMultiBackendMatrixTest` separately before
editing it because it was not part of this baseline.

**Additional apparatus defects exposed by source inspection.** `ReproducibilityTest` currently
declares two outputs equal when their SHA-256 strings match instead of comparing the bytes first,
and converts its ordered ZIP-entry buffer with `.toMap` before asserting entry order. Repair both so
the observable bytes/order drive the verdict and hashes are diagnostics only. `JvmSmapStackTraceTest`
and `SourceMapJvmTest` also turn any non-zero compile result into `CANCELED`; after the installed
launcher and compiler jars are present, those are real failures and must retain exit/stdout/stderr.

**First faithful staged result.** After switching to installed `ssc-tools`, all **19** tests execute:
15 pass, 4 fail, and 0 cancel. `JvmDirectDriverTest` is 3/3, `ReproducibilityTest` 5/5,
`SourceMapJvmTest` 3/3, `JvmSmapStackTraceTest` 2/3, and `JvmBytecodeLinkCliTest` 2/5. Three failures
are one helper defect: its current code-source classpath does not actually make Scala runtime
classes visible to the spawned `java` process (`scala.Predef$` / `scala.Option` missing). Measure
the loaded locations/test classpath and select the real runtime JARs, then retain the end-to-end JVM
execution assertions. The fourth failure is a contract question, not yet pre-classified: linked JARs
now contain `_ssc_runtime.tasty`, `a_sc.tasty`, and `b_sc.tasty` while the old test demands no TASTY.
Read the linker spec/history and test downstream separate-compilation needs before deciding whether
production or the assertion is stale.

**Fix/result.** The five suites share a bounded installed-distribution locator and invoke real
`bin/ssc-tools`; compiler commands now fail with exit/stdout/stderr once staging is present. Binary
artifacts use `JvmArtifactIO`. Reproducibility compares actual bytes and ordered ZIP entries before
printing SHA diagnostics. Scala runtime JARs come from loaded class resource URLs (Coursier reports
only a Maven directory through protection-domain code source on this host), and `CompilerLoader`
honours the live supported property before its cached fallback for embedded direct-driver calls.
The TASTY assertion was stale: Tier 5 commit `e401aa566` and `specs/v2.0-artifact-format.md` require
linked JARs to retain module/runtime TASTY for downstream Scala 3 compilation, so the test now
requires all three observed entries. Final focused result is 19/19 with zero failures/cancellations;
runtime-separation plus facade regressions are 12/12, and `dataset-parallel-jvm` passes.

## swiftui-real-fixture-system-exit-hides-failure — compiler error kills the forked test JVM
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: unrecorded -->

**Status:** FIXED (2026-07-20, `frontend-tui-fetch-refresh`). Found by `ci-red-main` in Linux run
`29545769651`, job `87777659720`, and reproduced again by exact run `29728630760`. Under
`SwiftUiRealFixtureBuildTest`, generated Scala failed on `selected()` and missing `selectFromView`,
but ScalaTest printed no `*** FAILED ***` row for the fixture before another suite began.

**Root cause / real-harness evidence.** The test calls `buildSwiftUIPackage` in-process. Its own
comment records that this production helper calls `System.exit(1)` when bytecode compilation fails,
which terminates the forked test JVM before ScalaTest can attach exit/stdout/stderr to the test. The
old guard only cancels when `scala-cli` is absent; it does not contain an actual compiler failure.

**Fix/result.** `JvmRuntimeUiPrimitives` now supplies the missing JVM snapshot implementation and the
hoisted primitive import includes it. The erased `Signal[T]()` bridge is scoped only to
`std.ui.lower`; the initially tested file-level extension was rejected because it shadowed ordinary
`String.apply(Int)` calls. The test now invokes the supported staged
`ssc-tools package --v1 --target macos` command, captures exit/stdout/stderr, and retains all
generated-package and executable
assertions. A deliberately invalid fixture returns non-zero with its compiler diagnostic and the
second named test continues. Focused result: real harness 2/2, SwiftUI 118/118, TUI 36/36, browser
select reconciliation 1/1.

## newfront-scala-spike-fixture-paths-linux — tracked C_min and output root depend on host CWD
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: unrecorded -->

**Status:** FIXED (2026-07-17, `ci-last-red`, coordinated with the stale `v3-newfront-p1-toplevel`
owner). Both failures were CWD/host-path assumptions in the (now JVM-only) `ScalaSpikeSpec`, not
spike-logic regressions — the guard was wrong, the self-host it guards is green. Fixes: (1) a
CWD-independent `repoRoot` resolver (walks up from `user.dir` to the `build.sbt` + `specs/` ancestor,
same shape as `SwiftBackendTest`) now anchors the C_min fixture at `repoRoot/specs/v2.2-p6.6-cmin.L`
(the `CMIN_L` env override is kept); (2) the `emit projections` test defaults `SPIKE_OUT` to the
always-writable `repoRoot/target/uniml-spike-out` instead of a hardcoded macOS `/private/tmp/...`
scratchpad. VERIFIED: `uniml/testOnly ...ScalaSpikeSpec` runs 60/0 (P6.21 C_min + emit both green).
Was: two deterministic filesystem failures — tracked `specs/v2.2-p6.6-cmin.L` not found from the sbt
module CWD, and `emit projections + toys for the diff harness` attempted to create `/private`, which
Ubuntu rejects with `java.nio.file.AccessDeniedException` (found by `ci-red-main` in Linux runs
`29544412767` / `29545769651`, job `87777659720`).

**Real-harness repro.** Run the JVM `uniml` `ScalaSpikeSpec` from aggregate `sbt test` on Ubuntu (or
set its process CWD to the module base). The C_min case must resolve the tracked repository fixture;
the diff-output case must use an explicit temporary/output directory inside the runner workspace,
not a macOS-root path. Missing tracked input is failure, while optional batch env inputs may remain
explicitly classified.

**Coordination.** `ScalaSpike*` is the authoritative live newfront claim's scope. `ci-red-main`
records both exact failures and waits for that lane to land; no concurrent test/source edit is safe.

## registry-seed-test-cwd-cancel — tracked packages.yaml validation silently skips on Linux CI
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: a99973c16
     confirmed: no -->

**Status:** FIXED (2026-07-17, `a99973c16`; awaiting CI confirmation). Found by `ci-red-main` in
Linux run `29545769651`, SHA `893bf2632`, job `87777659720`. `RegistrySchemaTest` cancelled
`seed registry/packages.yaml parses and validates without errors` with
`registry/packages.yaml not found — skipping seed validation`.

**Real-harness evidence.** GitHub checks out the complete repository before `sbt test`; the seed is
tracked at repository-root `registry/packages.yaml`. The suite nevertheless treats its own missing
path as an optional capability and returns green/cancelled because it searches relative to an sbt
module CWD. Run the focused `scalascript.imports.RegistrySchemaTest` from the normal aggregate build
to reproduce the path classification.

**Expected/fix plan.** Resolve the repository root robustly across shared checkout, external Git
worktree, and sbt project working directories; validate the tracked seed through that path. If the
fixture truly is missing, fail and print every searched location instead of cancelling. Keep the
existing parse/schema assertions unchanged. Done means the focused suite executes the seed case on
macOS and Linux-shaped paths rather than pre-judging it as unavailable.

**Fix/result.** The locator walks ancestors from process CWD, `user.dir`, and the loaded test class,
bounded by each path's segment count so it includes filesystem root once without walking above it.
The regression explicitly starts at `v1/lang/core`, matching aggregate sbt's module CWD. A missing
tracked seed now fails with all searched candidates. The suite executes 15/15 with zero cancellations
and passes; focused cross-backend conformance stays green.

## jvm-bytecode-runtime-tests-ignore-installed-drivers — five CI assertions cancel before comparing
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 1c109e49e
     confirmed: no -->

**Status:** FIXED (2026-07-17, `1c109e49e`; awaiting CI confirmation). Found by `ci-red-main` in
Linux run `29545769651`, SHA `893bf2632`, job `87777659720`.
`JvmBytecodeRuntimeSeparationTest` reported five `CANCELED` cases rather than success/failure because
it claimed compiler-driver jars were not staged.

**Real-harness evidence.** The same job's preceding `Compile and assemble ssc.jar` step runs
`sbt compile cli/assembly installBin` and explicitly logs `bin/lib/compiler/jars/ (6 JARs incl.
compiler-driver)`. During `sbt test`, however, all five runtime-separation cases cancel with
`compiler-driver jars not staged (run sbt cli/stage)`. Reproduce locally with
`scripts/sbtc "cli/assembly; installBin; cli/testOnly
scalascript.cli.JvmBytecodeRuntimeSeparationTest"` and compare the test's path detection with the
installed launcher/library tree. A cancellation is not a green comparison.

**Expected/fix plan.** Make the suite consume the same staged tools/compiler-driver location that
the supported `installBin` task creates (prefer the real installed launcher contract over a fat-jar
proxy). Keep capability union, unchanged-runtime timestamp, link, and standalone-runtime assertions
intact. Missing staging may cancel for an ad-hoc developer invocation, but after `installBin` all
five cases must execute and report their real stdout/stderr/exit mismatch instead of pre-judging.
Source inspection found the next hidden platform skip before implementing: the final test locates
Scala libraries only under macOS `~/Library/Caches/Coursier`, whereas GitHub uses Linux cache paths.
Use code-source locations already loaded by the test JVM for Scala 3 and 2.13 libraries; do not
replace one guessed cache path with another.

**Next layer exposed by the faithful run.** After routing through staged `ssc-tools` and resolving
Scala libraries from the test JVM, all five cases execute and all five fail. Four use `ujson.read`
directly on current `.scjvm` / `.scjvm-runtime` files and abort at byte zero because those artifacts
are now binary, not JSON. The link/run case reaches its size assertion and measures 762,184 bytes
against a stale `<400,000` bound. The fix must use the canonical production artifact decoder and
compare the real runtime-separation invariant; merely raising the old number would pre-judge the
result and could preserve duplicated runtime payloads.

**Fix/result.** The suite invokes installed `bin/ssc-tools`, verifies its actual
`bin/lib/compiler/jars` tree, and resolves Scala 3/2.13 runtime locations from classes already loaded
by the test JVM instead of an OS-specific cache. Current binary artifacts are decoded through
production `JvmArtifactIO`. The stale absolute JAR threshold is replaced by an in-run comparison:
the shared runtime bundle/artifact must be at least ten times the trivial module, directly proving
the runtime was not duplicated. All five cases execute and pass with zero cancellations; focused
JVM conformance passes.

**Related family audit queued as SPRINT 5r.** Several bytecode/link/source-map suites that the old
Linux step never reached contain similar `ImportResolver.libPath`, `cli/stage`, or macOS Coursier
assumptions. They are not pre-labelled broken: run them after the real `installBin` first, then
extend this apparatus fix only where a cancellation reproduces.

## v21-slim-distribution-gate-silent-assertions — Linux gate exits 1 with no failed check or diff
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 68ff5dacd
     confirmed: no
     gate: tests/e2e/v21-slim-distribution-gate.sh -->

**Status:** FIXED (2026-07-17, `68ff5dacd`; awaiting its queued Linux run `29548820854`). Found by
`ci-red-main` in run `29547476776`, SHA `0018dbf0c`, job `sbt — compile and test`. The release and
explicit-lanes gates pass, then
`v21-slim-distribution-gate.sh` runs for 70 seconds and exits `1`; the complete job log contains no
output between the step header and `Process completed with exit code 1`.

**Real-harness repro.** Run `tests/e2e/v21-slim-distribution-gate.sh --report
target/v21-slim-distribution.tsv` after `scripts/sbtc "installBin"`. The script contains dozens of
bare `[[ $(run_standard ...) == expected ]]` and file-state `[[ ... ]]` assertions under
`set -euo pipefail`. Any mismatch aborts before naming the check, expected value, actual value, exit
code, or diff. The CI log therefore cannot identify whether the underlying problem is semantic,
platform-specific, or stale expectation.

**Expected/fix plan.** Compare each real observable first through named helpers that preserve
stdout/stderr/exit, print expected/actual and a diff on mismatch, and only then classify failure.
Reproduce the current Linux failure from the newly diagnostic run; do not guess at or refresh an
expectation while the apparatus is blind.

**Fix/result.** Every semantic assertion now routes through named helpers that preserve stdout,
stderr, and exit status, and prints expected/actual plus a unified diff before failure. File-state
and negative checks likewise identify themselves. The complete gate passes locally. A later Linux
run, `29547740771` at `b829c8264`, passes the same slim step, proving the earlier semantic/platform
mismatch was not persistent; the first run containing the diagnostic implementation is still
queued, so Linux confirmation of the apparatus itself remains pending.

## ci-status-fixture-accepts-invalid-jq — fake-gh green hid a real CLI parse failure
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: c43d8f523
     confirmed: no
     gate: tests/e2e/ci-status-guard.sh -->

**Status:** FIXED (2026-07-17, `c43d8f523`; awaiting final CI confirmation). Found by
`ci-red-main` before the exact-SHA guard landed. The new fixture matrix passed 6/6, but the first
real `scripts/ci-status` invocation exited `2` with `failed to parse jq expression ... unexpected
token "\\"` instead of reporting the run.

**Reproduce.** `tests/e2e/ci-status-guard.sh` passes because its fake `gh` returns canned output and
ignores the `--jq` expression. Running `scripts/ci-status` against authenticated GitHub parses the
same expression and rejects the escaped quotes in `(.conclusion // \"\")`. The fake is therefore a
proxy, not proof that the real query is accepted.

**Expected/fix plan.** Use valid gojq syntax, keep the fixture result matrix, and add the real
authenticated invocation to the implementation verification. The guard is not shippable until the
exact current SHA produces a genuine `PENDING`, `RED`, or `GREEN` result with named jobs; `UNKNOWN`
from a query/parser defect is red for this task.

**Root cause/fix.** The shell single-quoted jq program unnecessarily escaped the empty string inside
an interpolation; fake `gh` returned canned records without parsing the supplied program. The jq is
now accepted by the real CLI. The fixture matrix still passes, and authenticated queries return the
exact run URL plus all four named jobs. Spec verification records the proxy failure so a fixture-only
green is never used as release evidence again.

## install-dev-rewrites-tracked-ssc-launcher — successful staging leaves the checkout dirty
<!-- status: fixed
     lane: apparatus
     area: cli
     fixed-in: b829c8264
     confirmed: no -->

**Status:** FIXED (2026-07-17, `b829c8264`; awaiting Sergiy confirmation). Found by `ci-red-main`
immediately after the worktree-isolation fix `0018dbf0c`. This was a generator-authority drift: the
build succeeded and the launcher semantics were unchanged, but a documented developer command
rewrote a tracked file on every clean checkout.

**Real-harness repro.** From a clean linked worktree, run `bash install.sh --dev`, then
`git diff -- bin/ssc`. The diff adds only the AppCDS explanatory comment and two blank lines.
`install.sh` first runs `sbt cli/installBin` (whose `build.sbt` template writes one spelling) and then
overwrites the same launcher with its own non-byte-identical heredoc. A green build is therefore an
insufficient oracle; the required observable is a clean byte comparison after both generators run.

**Expected.** Both supported staging paths must produce the same tracked launcher bytes. The
regression must compare the output and print the real diff on mismatch; it must not classify a
comments-only change as harmless before comparing.

**Root cause/fix.** `install.sh` invoked `cli/installBin` and then duplicated three launcher
templates in heredocs, overwriting the fresh canonical output; the templates had already drifted.
The duplicate generator is gone. `cli/installBin` is the sole authority and the installer now
fails if any expected launcher is not executable. `tests/e2e/staged-launchers-clean.sh` runs in CI
immediately after the full install and lets `git diff --exit-code` print the exact patch before it
classifies failure. The gate was proven red against the old output, then green after the fix; the
full install and both focused conformance cases pass.

## install-dev-initializes-skills-submodule-inside-worktree — documented local build violates the worktree contract
<!-- status: fixed
     lane: apparatus
     area: cli
     fixed-in: 0018dbf0c
     confirmed: no -->

**Status:** FIXED (2026-07-17, `0018dbf0c`; awaiting Sergiy confirmation). Found by `ci-red-main` on
`origin/main` `771b67d45`. This was a
workflow correctness bug, not merely redundant network work: project rules require
`.agents/plugins` to be initialized only in shared main, while the command printed by the
conformance wrapper for a missing launcher is `bash install.sh --dev`.

**Real-worktree repro.** Create a fresh worktree with `scripts/new-worktree <name>` and run the
printed command. Before any build output, `install.sh:54-57` announces `Updating git submodules...`
and unconditionally runs `git submodule update --init --remote --recursive`; git starts cloning
`.agents/plugins` into the worktree. The 2026-07-17 run was interrupted immediately, leaving only
an uninitialized empty submodule directory (`git submodule status` begins with `-`).

**Expected.** A worktree build must not initialize or update the skills submodule. `install.sh`
should detect a `.git` worktree file, use the shared-main skill checkout only for agent reads, and
continue building because the submodule is not a compiler input. A main-checkout install may retain
the explicit update. The regression gate must exercise both classifications without cloning or
running the expensive build.

**Fix/result.** `install.sh` detects the linked-worktree `.git` file, resolves the shared checkout
through `git rev-parse --git-common-dir`, and skips every submodule mutation while retaining the main
checkout update path. `tests/e2e/install-worktree-submodule-guard.sh` creates a real detached
worktree, compares both classifications, verifies the submodule gitlink remains uninitialized, and
runs in CI. The exact documented `bash install.sh --dev` command then completed staging in 4 seconds
from the feature worktree; both focused JavaScript conformance cases remained green on INT/JS/JVM.

## tower-thread-hardcoded-64m-stack — `run --bytecode` StackOverflowErrors on big programs, and `-Xss` cannot help
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: unrecorded -->

**Status:** FIXED (2026-07-16, `RunNativeV2.scala` tower stack 64m → 512m). Found by running
`v21-negative-toolchain-release-gate.sh` locally — a CI step that had **never once run**, because
every CI run died at an earlier step. Not a scljet bug despite only scljet examples showing it.

**Symptom.** `bin/ssc-standard run --bytecode examples/scljet-bytes.ssc` (and `scljet-full.ssc`)
fails ~80% of runs with `Exception in thread "main" java.lang.StackOverflowError`, trace inside
`ssc.Compiler$.compile`. Nondeterministic; stdout is empty when it fails (it dies during compile,
before the program runs). Surfaces as `v21-negative-toolchain-release-gate` `parity.one-sided` ≠ 0,
naming a *different* scljet case each run — which looks exactly like flaky infra.

**Root cause.** `RunNativeV2.runTower` created its thread with a **hardcoded 64 MB stack**
(`new Thread(null, task, threadName, 64L * 1024L * 1024L)`). That thread runs the self-hosted front
**and** `Compiler.compile`, so it — not `main` — is where a deep compile overflows. Compiling a big
program recurses much deeper than running it, and 64m was not enough for the scljet examples.
Flaky because stack frame sizes depend on how much the JIT has compiled, which depends on machine
load: same input, different outcome.

**Two traps that cost real time here:**

1. **`-Xss` looked inert.** Raising the launcher's stack changed nothing — 64m, 128m, 256m and even
   `-Xss1g` all failed at the same rate (measured: 2/8 pass at 64m, 2/8 at 256m, 1/6 at `-Xss1g`).
   That non-correlation is what wrongly suggested "unbounded recursion / a race". The stack the
   overflowing thread uses was simply never `-Xss`.
2. **"thread main" is a lie.** `runTower` catches `Throwable` into a cell and rethrows it on the
   joining thread, so a **tower** StackOverflowError prints under main's banner while carrying the
   tower's trace. Do not conclude from the banner that `-Xss` should have applied.

**Reproduce** (before the fix; ~80% of runs):

```bash
for i in 1 2 3 4 5 6 7 8; do
  bin/ssc-standard run --bytecode examples/scljet-bytes.ssc >/dev/null 2>&1; printf '%s ' $?
done   # 1 1 0 0 1 1 1 1   → after the fix: 0 0 0 0 0 0 0 0
```

**The fix, and why the stack sizes are deliberately NOT one knob.** `-Xss` / `SSC_XSS` bounds the
**user program** (compiled and run on the calling thread); `RunNativeV2.TowerStackBytes` bounds the
**compiler**. Different jobs, very different depth needs. Sharing a knob breaks
`v21-direct-asm-recursion-smoke`, which pins 256k to prove the compiled lanes need no big stack —
that 256k must not starve the compiler that gets them there (tried it; the gate went red). Stack is
reserved address space, not committed memory, so 512m is cheap.

## descriptor-v3-body-local-effect-evidence — raw effect scan makes descriptors depend on method bodies
<!-- status: open
     lane: apparatus
     area: runtime -->

**Status:** open (2026-07-15). Reported as P1 by the fresh independent review of
exact frozen checkpoint `0cb46c3cd`; local correction `ff0e2580b`, landing SHA
pending independent approval.

**Symptom/reproduce:** put a local `effect Local:` declaration inside the body of an
exported method whose public header is otherwise unchanged. The raw carrier regex
classifies the body-local header as top-level effect evidence and strict production
returns a section-level `Left`. Removing only that method body declaration changes
the result, violating the pre-body descriptor/body-invariance contract.

**Root cause/plan:** raw effect-header extraction is lexically string-aware but not
declaration-scope-aware, and `bindEffectHeaders` performs a second unscoped
interpretation. Correlate raw headers only with declaration-scope marked AST
candidates using validated positions/owners/order, structurally exclude headers in
method/value/template bodies that are not part of the projected declaration scope,
and pass the one validated evidence model into binding. Do not re-scan the chosen
carrier independently after correspondence.

**Done when:** a faithful body-local-effect regression fails on the reviewed
checkpoint, body-only add/remove edits preserve canonical descriptor bytes and
`apiHash`, genuine top-level plain/multi/empty effects retain their evidence, and
the full affected gates pass. Keep `open` until fresh independent approval and
landing.

**Red baseline:** regression commit `c1f57d99f`; focused producer is exactly
63/70. The body-local-effect regression fails with the section-level
`UNSUPPORTED_PUBLIC_DECLARATION` reported by the unscoped raw scan; all previous
63 producer tests remain green.

**Local verification:** `ff0e2580b` correlates sanitized raw headers only with
declaration-scope AST candidates, accounts for the parser's deterministic line
insertions, ignores body-local candidates, and stores the validated bindings for
later projection instead of rescanning. Body-local-only and same-name-before-real-
effect vectors are green; focused producer passes 82/82 and forced effect
conformance passes 9/9. Keep `open` until fresh review and landing.

## descriptor-v3-effect-sentinel-duplicate-collision — injected and user effect markers coexist
<!-- status: fixed
     lane: apparatus
     fixed-in: ab7678d0f
     area: front -->

**GATED AND FIXED — verified 2026-08-07 by reading the test against this entry's own recipe, not by matching names.** `v1/lang/core/src/test/scala/scalascript/artifact/PreBodyApiDescriptorProducerTest.scala` contains *"a real effect cannot duplicate its parser-owned origin sentinel"*. It reproduces this entry's recipe verbatim — `effect Stable:` carrying
`private type __effectDecl__ = true` — and requires `UNSUPPORTED_PUBLIC_DECLARATION`.

**Status:** open (2026-07-15). Reported as P1 by the fresh independent review of
exact frozen checkpoint `0cb46c3cd`; local correction `ff0e2580b`, landing SHA
pending independent approval.

**Symptom/reproduce:** source an actual `effect Stable:` whose body declares
`private type __effectDecl__ = true`. Parser preprocessing injects its own marker,
leaving duplicate reserved aliases, but strict managed production still returns
`Right`. The same collision class applies to `__effectUnsupportedShape__` and to
non-canonical marker aliases.

**Root cause/plan:** the producer treats marker presence as evidence and filters all
matching names without validating cardinality or exact parser-owned shape. For each
effect object require exactly one canonical `private type __effectDecl__ = true`,
require the unsupported-shape marker exactly when preprocessing the raw header calls
for it, reject duplicates/wrong RHS/modifiers/bounds/parameters, and reject either
reserved name anywhere it could be user-authored. Preserve marker filtering only
after validation, for both Document-backed and documentless packaged carriers.

**Done when:** faithful duplicate, malformed, and unsupported-marker collision
vectors fail on the reviewed checkpoint, then reject at stable managed-production
paths without descriptor/runtime members; parser and EffectAnalysis invariance plus
the full affected gates pass. Keep `open` until independent approval and landing.

**Red baseline:** regression commit `c1f57d99f`; focused producer is exactly
63/70. The duplicate-sentinel regression fails because strict production returns
`Right` for an actual effect containing the colliding user alias; all previous 63
producer tests remain green.

**Local verification:** `ff0e2580b` validates both reserved names before filtering:
each marker must be the sole canonical unscoped-private, parameterless, unbounded
`type ... = true` declaration, and unsupported-shape evidence requires the origin
marker and raw-header agreement. Duplicate, malformed, non-type, ordinary-object,
and unexpected-unsupported vectors are green; focused producer passes 82/82.
Keep `open` until fresh review and landing.

## descriptor-v3-import-witness-omission — retained carrier import mutations evade correspondence
<!-- status: fixed
     lane: apparatus
     fixed-in: ab7678d0f
     area: front -->

**GATED AND FIXED — and I got this wrong yesterday.** `v1/lang/core/src/test/scala/scalascript/artifact/PreBodyApiDescriptorProducerTest.scala` contains *"ordered imports participate in retained source and AST correspondence"*, which mutates the Document carrier `import foo.Int` → `import bar.Int` while the stored AST keeps the original, and requires `UNSUPPORTED_PUBLIC_DECLARATION`. Landed `ab7678d0f` (2026-07-15).

**My previous note here said STILL UNGATED, and the method was the defect.** I grepped for the identifiers this entry uses to describe the PRODUCER's internals — `earlyClause`, `rawSource` — which by construction do not appear in a test: a test is written in the vocabulary of its INPUT and its ASSERTION, tampering with source text and requiring a rejection, never naming the AST field it exercises. I also took only the FIRST grep hit, which for `effect Real` landed on an unrelated comment-handling test 150 lines above the real one. Both errors point the same way: search for the RECIPE, not for the mechanism.

**Status:** open (2026-07-15). Reported as P1 by the fresh independent review of
exact frozen checkpoint `0cb46c3cd`; local correction `c55ac86e9`, landing SHA
pending independent approval.

**Symptom/reproduce:** parse a module containing `import foo.Int` and a public use of
`Int`, then mutate only the retained Document carrier to `import bar.Int` while the
stored AST and CodeBlock carrier still contain `foo.Int`. Strict production returns
`Right` and projects `Named("foo.Int")` because carrier correspondence treats both
sources as the same declaration surface.

**Root cause/plan:** imports now influence projection, but `declarationWitness`
omits `Import`, so the exact source/AST contract does not cover that semantic input.
Add ordered import witnesses at their lexical owner, preserving importer syntax and
every selector kind (direct, rename, wildcard, unimport/exclusion, given, given-all)
while remaining position/format/body invariant. Require stored AST, CodeBlock, and
optional Document witnesses to agree before constructing import scope.

**Done when:** the faithful dual-carrier mutation fails on the reviewed checkpoint,
same-header formatting/body edits stay invariant, nested/source-ordered import
positives remain green, and the full focused, descriptor/core/interop/IR/ABI plus
affected conformance radius passes. Keep `open` until fresh independent approval
and landing.

**Red baseline:** regression commit `c1f57d99f`; focused producer is exactly
63/70. The carrier-import mutation returns `Right` with `Named("foo.Int")`; all
previous 63 producer tests remain green.

**Local verification:** `c55ac86e9` adds ordered import witnesses containing the
importer reference plus direct/rename/unimport/wildcard/given/given-all selector
shape. The faithful carrier mutation is green and all later focused/full gates
remain green. Keep `open` until fresh review and landing.

## descriptor-v3-nominal-derives-early-loss — derives and early initializers disappear from nominal APIs
<!-- status: fixed
     lane: apparatus
     fixed-in: 21ae17ec0
     area: front -->

**GATED AND FIXED — and I got this wrong yesterday.** `v1/lang/core/src/test/scala/scalascript/artifact/PreBodyApiDescriptorProducerTest.scala` contains *"early initializer headers participate in both retained source carriers"*, which changes the early-initializer header in the Document carrier AND in the CodeBlock carrier, and requires BOTH to be rejected. The `derives` half is covered separately by *"derives clauses reject on every directly parseable nominal form"*. Landed `21ae17ec0` (2026-07-15).

**My previous note here said STILL UNGATED, and the method was the defect.** I grepped for the identifiers this entry uses to describe the PRODUCER's internals — `earlyClause`, `rawSource` — which by construction do not appear in a test: a test is written in the vocabulary of its INPUT and its ASSERTION, tampering with source text and requiring a rejection, never naming the AST field it exercises. I also took only the FIRST grep hit, which for `effect Real` landed on an unrelated comment-handling test 150 lines above the real one. Both errors point the same way: search for the RECIPE, not for the mechanism.

**Status:** open (2026-07-15). Reported as P1 by the fresh independent review of
frozen checkpoint `4cd2a4aaa` (rebased as `05e498a72`); fix SHA pending.

**Symptom/reproduce:** `templateHeaderWitness` and `rejectUnsupportedParents` do
not inspect `Template.derives` or `Template.earlyClause`. A class, trait, enum, or
object can therefore retain an unrepresentable derives/early-initializer surface,
or one retained source carrier can change that surface, while strict production
still returns `Right` or accepts stale source/AST correspondence.

**Root cause/plan:** the declaration witness and nominal losslessness gate cover
parents/self/members but omit two current ScalaMeta template-header fields. Include
both fields in exact body-erased correspondence. Reject every actual public nominal
declaration with a non-empty derives or early clause until descriptor metadata can
represent it. Add direct class/trait/enum/object vectors for every shape that the
parser accepts plus stale Document/CodeBlock mismatches; require stable
`UNSUPPORTED_PUBLIC_DECLARATION` paths and retain all earlier wrapper/header tests.

**Done when:** faithful red vectors fail on the current checkpoint, then pass with
the full focused, descriptor/core/interop/IR/ABI, and affected conformance radius.
Keep `open` until fresh independent approval and landing on `origin/main`.

**Red baseline:** regression commit `f08ab9943`; focused producer is exactly
50/60. Three nominal tests fail: direct derives is accepted, derives carrier
tampering returns `Right`, and early-clause tampering reaches the later symbol-level
inheritance rejection instead of failing correspondence at the section path. All
previous nominal regressions remain green.

**Local fourth correction checkpoint:** implementation `43d41e88d` and spec
verification `38597ae85`, rebased on `origin/main@f63714680`, include derives and
early clauses in both exact declaration witnesses and the nominal rejection gate.
Focused producer/parser/effect tests pass 75/75 (producer 63/63), descriptor
27/27, core 1111/1111, interop 36/36, IR succeeds, artifact ABI 73/73, and affected
conformance passes 2/2 modules/import-dir plus 9/9 effect cases. Status remains
`open` until fresh independent approval and landing; the local commit is not a fix
SHA on `origin/main`.

## descriptor-v3-imported-builtin-shadow — imports are ignored before bare builtin projection
<!-- status: open
     lane: apparatus
     area: runtime -->

**Status:** open (2026-07-15). Reported as P1 by the fresh independent review of
frozen checkpoint `4cd2a4aaa` (rebased as `05e498a72`); fix SHA pending.

**Symptom/reproduce:** `projectStat` ignores imports. Sources such as
`import foo.Byte` followed by `Array[Byte]` still project as primitive `Bytes`;
renamed imports and wildcards can likewise make a bare spelling resolve somewhere
else while the producer guesses a frozen builtin. The same gap affects every bare
builtin mapping (`Int`, frozen collection constructors, and so on) and can bypass
the platform-type isolation rule.

**Root cause/plan:** lexical binder/local lookup is import-blind, so absence of a
local identity is treated as proof of the builtin. Collect source-ordered import
bindings at `projectStat` and fail closed before every bare builtin mapping whenever
a direct import, rename-to-that-name, or wildcard could supply the spelling. Preserve
renamed-away and unimport semantics only when absence is provable. Add direct,
rename, wildcard and exclusion vectors for both Array/Byte components and at least
representative `Int`/`List`; retain qualified builtin/external positives and assert
stable declaration paths/codes, including platform-root cases.

**Done when:** faithful red vectors fail on the current checkpoint, then pass with
all prior 46 focused tests and the full affected gates. Keep `open` until fresh
independent approval and landing on `origin/main`.

**Red baseline:** regression commit `f08ab9943`; focused producer is exactly
50/60. Five import tests fail: exact Array/Byte and rename-to-Byte still become
primitive `Bytes`, wildcard Array/Byte returns `Right`, exact Int remains `I32`, and
an exact platform rename remains `I32`. The combined Int/List test stops at its
first exact-Int assertion; its wildcard-List assertion is retained for the fix.
Rename-away/unimport and qualified positives already pass, as do all prior 46 tests.

**Local fourth correction checkpoint:** implementation `43d41e88d` and spec
verification `38597ae85`, rebased on `origin/main@f63714680`, add source-ordered
import scope and resolve direct/renamed/wildcard/excluded bindings before every bare
builtin. Focused producer/parser/effect tests pass 75/75 (producer 63/63),
descriptor 27/27, core 1111/1111, interop 36/36, IR succeeds, artifact ABI 73/73,
and affected conformance passes 2/2 modules/import-dir plus 9/9 effect cases. Status
remains `open` until fresh independent approval and landing; the local commit is
not a fix SHA on `origin/main`.

## descriptor-v3-mutable-export-loss — exported val and var collapse to one immutable descriptor
<!-- status: open
     lane: apparatus
     area: runtime -->

**Status:** open (2026-07-15). Reported by the independent Slice B frozen-checkpoint
re-review; affected pre-integration commit `0f60205c5` (rebased as `59ca2898f`);
fix SHA pending.

**Symptom/reproduce:** project otherwise identical public `val current: Int` and
`var current: Int` declarations. The strict pre-body producer returns the same
`ApiSymbolKind.Value`, descriptor JSON, and `apiHash` for both, so the descriptor
silently discards public mutability.

**Root cause/plan:** `projectValues` receives the declaration's `mutable` flag but
does not encode or reject it, while the frozen Slice A schema has no mutability
field. Reject every selected public/exported `Defn.Var` with stable
`UNSUPPORTED_PUBLIC_DECLARATION` until a future additive schema represents
mutability. Keep `val` positive and add a faithful val-versus-var regression without
changing the descriptor leaf.

**Baseline:** focused producer test accepts the `var` as the same `Value(I32)`
shape as the positive `val`; this is one of four expected failures in the
`25/29` pre-fix run.

**Latest local checkpoint:** implementation `72e6a2897` on
`origin/main@790366a9d` rejects the faithful repro. Focused producer 46/46,
descriptor 27/27, core 1092/1092, interop 36/36, IR, artifact ABI 73/73, and
affected conformance 2/2 are green. Status stays `open` until fresh independent
approval and landing on `origin/main`.

## js-control-direct-import-only-eval-erasure — unused marker removal changes direct-eval scope
<!-- status: open
     lane: apparatus
     area: codegen -->

**Status:** open; repair candidate `4c6b8e2a9` is locally verified and awaits fresh
independent review plus landing. Found by parent adversarial pre-rereview on
2026-07-15; the original independent-review snapshot was `f6fa34fac`.

**Symptom/reproduce:** compile a file that imports named `direct`, contains no
`reset`/`shift`, and evaluates `eval("typeof direct")`. The repair candidate selects
the file solely to erase the otherwise unused build-time marker import, but its
direct-eval scan is gated on `filesWithMarkerCalls`. Emit therefore removes the
lexical binding without a diagnostic and changes the eval result from `"object"` to
`"undefined"`.

**Required fix/verification:** intrinsic direct eval is a barrier for every source
file that the transform would rewrite, including import-only marker erasure. Keep
unused marker removal for eval-free files; do not reintroduce a production marker
dependency. Add an exact import-only direct-eval regression proving one stable
`JS_DIRECT_CAPTURE_BARRIER`, no `transformedFiles` entry, and byte-semantic
file-atomic emit when a programmatic caller ignores diagnostics.

**Root cause/fix candidate:** eval scanning was gated by the presence of marker
calls even though an import-only file was also a rewrite candidate. The candidate
now scans every selected file, retains the original import on diagnostic, and has an
executing regression proving that `eval("typeof direct")` still observes `"object"`.

## js-control-direct-typescript-version-ungated — unsupported compiler APIs are accepted
<!-- status: open
     lane: apparatus
     area: cli -->

**Status:** open; cumulative repair candidate `c19d42401` is locally verified and
awaits fresh independent review plus landing. Reported as P2 on 2026-07-15 by the
independent pre-integration review of frozen direct-transform snapshot `f6fa34fac`.

**Symptom/reproduce:** `createDirectTransform` accepts any object with enough
TypeScript-shaped members and the CLI loads any consumer `typescript` version.
The package therefore has no deterministic boundary for compiler-factory and AST
API compatibility, although its declarations and tests were built only against
TypeScript 5.9.3.

**Required fix/verification:** the feature spec must pin the supported compiler API
version policy, both the programmatic entrypoint and CLI must reject an unsupported
version before transforming, and positive/negative version-gate tests must preserve
an actionable stable failure. Keep the published package free of a bundled compiler.

**Root cause/fix candidate:** the transform validated only a TypeScript-shaped
object and never bounded compiler AST/factory compatibility. The candidate gates
both programmatic and CLI entrypoints on `versionMajorMinor === "5.9"`, with 5.9.3
as the qualification pin and deterministic rejection tests outside that line.

## js-control-direct-eval-capture-unsound — direct eval can observe rewritten lexical frames
<!-- status: open
     lane: apparatus
     area: codegen -->

**Status:** open; cumulative repair candidate `c19d42401` plus selected-file closure
`4c6b8e2a9` are locally verified and await fresh independent review plus landing.
Reported as P1 on 2026-07-15 by the independent pre-integration review of frozen
direct-transform snapshot `f6fa34fac`.

**Symptom/reproduce:** a file containing an otherwise transformable direct reset can
also contain direct `eval(...)`. The current region checks do not reject every
file-wide direct-eval occurrence or transparent callee wrappers such as parentheses,
`as`, non-null, and type assertions. Emit can therefore change which lexical frame
the evaluated source observes or mutates.

**Required fix/verification:** reject intrinsic direct eval anywhere in each file
that would be transformed, after unwrapping only transparent syntax, and emit
nothing for that file. The feature spec must explicitly pin indirect-eval and
`Function`-constructor policy. Tests must cover top-level, reset-local, nested
closure, wrapped, indirect, and `Function` cases with stable diagnostics.

**Root cause/fix candidate:** analysis had no file-wide intrinsic-eval ownership
pass. The repair resolves the unshadowed global binding through transparent wrappers
and cancels every rewrite in that selected file; the follow-up extends the same gate
to import-only erasure while leaving indirect eval and `Function` global-only.

## scljet-ipk-rowid-alias-not-substituted — reading a REAL SQLite file returns 0 for every `INTEGER PRIMARY KEY`
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 14f4da4ac -->

**Status:** **FIXED (2026-07-16)** — `14f4da4ac` (read substitution) + `2fc0a0fd1`
(`lastInsertRowid`). Found 2026-07-15 by `scljet-jdbc-j4-introspection` while probing whether the
JDBC shim can read a file written by the reference driver. **Engine bug — belongs to the
`scljet-m3-writes` lane, NOT the JDBC shim** (the shim only forwards `queryImage` rows).
Found on `origin/main` `727ea5e12`. **Silent wrong data, not an error** — the severity is that
nothing fails; the client just gets zeros.

**ACTUAL root cause (confirmed, not hypothesis).** Exactly the read-side hypothesis below, and
*only* that: `fieldValueAt` returned the record's stored field for the IPK column. A
reference-written file stores NULL there (the value lives in the rowid), so the engine returned
NULL — which the typed getters coerce to `0`. Verified through a file in both directions before
any fix: `SELECT id, name FROM emp` gave `null|ann, null|bob`, and `WHERE id = 7` matched *nothing*
(the filter reads the record too — a projection-only fix would have left this broken).

**The fix (read-only, `scljet/sql.ssc`).** Materialise the rowid into the IPK column once per
query, on the decoded row set, so every downstream reader — projection, WHERE, ORDER BY, GROUP BY,
aggregates, joins, DISTINCT — sees it through the existing `fieldValueAt` without knowing about the
alias. Three choke points cover every SELECT path: `finishRows` (full scan + rowid seek + index
seek), `executeSelectLimited` (LIMIT pushdown applies its WHERE *during* the scan, so
`collectRowsLimited` takes `ipkIdx` and normalizes before matching), and `joinTableRows` (the one
row source for both the 2-table and the N-table join). Each derives the index from the existing
`tableIpkIndex` — no new analysis, and no signature churn beyond `collectRowsLimited`.
**Byte-safety:** only the DECODED value is replaced; `serialType`/`encoded` carry over from the
original field, so `reconstructRecordBytes` still rebuilds the original on-disk payload
byte-for-byte and a normalized record cannot corrupt a re-encode.

**Regression cover:** `ScljetIpkRowidDifferentialTest` (new) crosses the two engines **through a
file** in both directions; `ScljetIntrospectionTest`'s deliberately-pinned `getLong(1) == 0` now
asserts `1`. Gates: `scljet-*` conformance 97/97 (`--no-memo`), `scljetJdbcPlugin/test` 48/48.

**Symptom/reproduce** (three-way differential; the JDBC lane is only the harness — the same read
goes through `queryImage`, so a pure `.ssc` repro should reproduce it too):

```scala
// 1. a file written by the REFERENCE driver (org.xerial:sqlite-jdbc)
CREATE TABLE emp(id INTEGER PRIMARY KEY, name TEXT); INSERT INTO emp VALUES (1,'ann'),(7,'bob')
// 2. read it back:
reference reading its own file        → 1|ann, 7|bob     ✓
scljet   reading the reference file   → 0|ann, 0|bob     ✗  ← BUG
scljet   reading a scljet-created db  → 1|ann, 7|bob     ✓  (masks the bug in our own tests)
```

**Root cause (hypothesis, needs engine confirmation).** In real SQLite an `INTEGER PRIMARY KEY`
column is an *alias for the rowid*: the record stores NULL for it and the value lives in the
rowid. scljet's read path does not substitute the rowid for the IPK column, so it returns the
stored NULL, which the getters coerce to `0`. The engine already models the concept — `isIpkType`
(`scljet/sql.ssc:1086`), `ipkColumnIndex(sql)` (`~:1098`), `tableIpkIndex(db, table)` (`~:4291`) —
so the fix is likely to apply the existing IPK index in the row-projection path, not new analysis.

**~~A second, opposite-direction divergence~~ — MEASURED AND DISPROVED (2026-07-16).** The
earlier suspicion here (that our WRITE stores the IPK value in the column with a *sequential*
rowid, so real SQLite would misread our files) is **WRONG**. Probed directly — write with
scljet, read with `org.xerial:sqlite-jdbc`:

```
scljet: INSERT INTO emp VALUES (7,'bob')  → the row's actual rowid = 7   (NOT sequential)
REAL SQLite reading OUR file → id=1|ann|rowid=1, id=7|bob|rowid=7        ✓ correct
REAL SQLite `PRAGMA integrity_check` on our file → ok                    ✓
```

**So the WRITE side is sound and our files are valid, correctly-readable SQLite.** What we
actually do is store the IPK value *redundantly*: in the rowid (correct) **and** in the record's
column (real SQLite stores NULL there). Real SQLite tolerates that because it always takes an IPK
column's value from the rowid and ignores what is stored. Our two inaccuracies cancel out on our
own files, which is why every existing test passes.

**Consequence for the fix — it is READ-ONLY and does not regress our own files.** Since our write
already sets `rowid = 7`, teaching the read path to take the IPK column from the rowid yields `7`
on *both* file flavours: ours (rowid 7, column 7) and the reference's (rowid 7, column NULL).
There is no need to change the write path to make the read correct. (Writing a canonical NULL in
the column is a separate, optional tidy-up — byte-level canonicity vs real SQLite — NOT required
for correctness, and it would be a storage-format change worth its own slice.)

**A REAL second bug found by the same probe — `lastInsertRowid` is wrong for an IPK table —
FIXED `2fc0a0fd1`:**

```
scljet: INSERT INTO emp VALUES (7,'bob')  → the row's rowid IS 7, but
                                             MutationResult.lastInsertRowid reports 1
reference sqlite-jdbc for the same INSERT  → last_insert_rowid() = 7
```

i.e. the counted-mutation path reports a sequential counter instead of the rowid actually
assigned. This makes the JDBC shim's `getGeneratedKeys` (J2) return the wrong key for exactly the
tables where generated keys matter most. The existing `getGeneratedKeys` tests use a *plain*
`INTEGER` column (not an IPK), where a sequential rowid IS the right answer — which is why they
pass, and why the reference cross-check passes too.

**Root cause:** `insertChangesRowid` derived the rowid as `maxRowid + #rows` *independently of*
`assignInsertRowids`, the function that actually places the rows — two derivations of one fact,
which agreed only while no IPK was involved. **Fix:** reuse `assignInsertRowids` and report the
last rowid it assigns, so both callers share one source of truth by construction.
`ScljetDriverTest` now covers an IPK table (explicit 7 → auto 8 → explicit 3), cross-checked
against the reference's `last_insert_rowid()`. Verified RED first: reported `(1, 8, 9)` vs the
correct `(7, 8, 3)`.

**A THIRD divergence, still OPEN — see `scljet-update-ipk-does-not-move-rowid` below.** Found while
verifying that the read fix does not regress `UPDATE`. It is a *pre-existing write* gap, not a
regression, and the read fix strictly improves cross-engine agreement on it.

**Method note that generalises.** A test whose oracle is "scljet reads back what scljet wrote"
cannot see any of this: it is self-consistent by construction. The differential must cross the
two engines **through a FILE**, in *both* directions (they-write/we-read AND we-write/they-read) —
only the second direction could have disproved the write-side hypothesis above.

**Notes.** Reading a reference-written file otherwise works (schema, indexes incl. `UNIQUE`,
non-IPK columns, TEXT) — see `ScljetIntrospectionTest` "reads a database created by the
reference driver", which pins the parts that DO hold. Related engine gaps found the same way:
`CREATE UNIQUE INDEX` is not parsed at all (`parseCreateIndex` requires `CREATE INDEX`;
`CREATE UNIQUE INDEX` falls through to `parseCreate` → "expected TABLE"), and
`INSERT INTO t SELECT …` is not parsed ("expected VALUES").

## js-control-prompt-key-extraction-never — invariant answer type breaks PromptKeyOf
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 1497623b5 -->

**Status:** done in `0d0ffcfd3`; confirmed closed by independent second
pre-integration review. Originally reported as P1 on 2026-07-15; affected pre-land
declaration commit `2a34d7ed3`.

**Symptom/reproduce:** `PromptKeyOf<Prompt<P, ConcreteAnswer>>` evaluates to
`never` because the conditional matches `Prompt<infer P, unknown>` while the
private prompt brand intentionally makes the answer type invariant.

**Root cause:** the conditional fixed the answer parameter to `unknown`; invariant
branding makes `Prompt<P, ConcreteAnswer>` intentionally non-assignable to that
shape, so inference never reached `P`.

**Fix/verification:** `PromptKeyOf` now infers both `P` and the concrete answer
parameter, then returns `P`. A concrete-answer positive compile assertion passes,
while nested-prompt incompatibility, answer invariance, and forged-prompt negative
gates remain green.

## descriptor-v3-nominal-surface-loss — strict pre-body projection drops public nominal semantics
<!-- status: open
     lane: apparatus
     area: runtime -->

**Status:** open (2026-07-15). Reported by the independent Slice B re-review
(`/root/descriptor_b_rereview`); fix SHA pending.

**Symptom/reproduce:** the in-progress descriptor-v3 producer accepts nominal
declarations whose public surface cannot be represented by the current descriptor:
`trait Configured(value: Int)` loses its trait constructor, a trait self type such
as `self: Base =>` loses the self constraint, a template `export delegate.exposed`
loses a public receiver member, and constructor `val`/`var` parameters lose their
generated public accessors. Each input currently produces a successful bare
`Type`/`Constructor` projection instead of failing closed.

**Root cause/plan:** the nominal losslessness gates inspect parents and most
`Stat.WithMods` members, but do not inspect trait constructor clauses, template
self types, `scala.meta.Export`, or constructor accessor modifiers. Freeze these
shapes as unsupported until receiver/member metadata exists, reject them with
stable `UNSUPPORTED_PUBLIC_DECLARATION`, and add one regression per shape before
requesting another independent review.

**Baseline:** focused producer suite reproduces all four nominal shapes as
unexpected successful descriptors (`18/25` total green before the fix).

**Local correction checkpoint:** implementation `72e6a2897` on
`origin/main@790366a9d` rejects all four shapes. Focused producer 46/46,
descriptor 27/27, core 1092/1092, interop 36/36, IR, artifact ABI 73/73, and
affected conformance 2/2 are green. Status remains `open` until fresh independent
approval and landing on `origin/main`.

## descriptor-v3-lost-ast-container-fail-open — retained declarations can project as an empty API
<!-- status: fixed
     lane: apparatus
     fixed-in: e6df78ce5
     area: front -->

**GATED AND FIXED — verified 2026-08-07 by reading the test against this entry's own recipe, not by matching names.** `v1/lang/core/src/test/scala/scalascript/artifact/PreBodyApiDescriptorProducerTest.scala` contains *"retained executable document source cannot silently lose its section AST"*. It does exactly what this entry describes — asserts `parsed.document.nonEmpty` and
`parsed.sections.nonEmpty`, then requires `descriptor(parsed.copy(sections = Nil))` to FAIL with
`UNSUPPORTED_PUBLIC_DECLARATION` at `$.sections`. The fail-open is closed.

**Status:** open (2026-07-15). Reported by the independent Slice B re-review
(`/root/descriptor_b_rereview`); fix SHA pending.

**Symptom/reproduce:** parse a valid declaration-bearing module, then copy it with
`sections = Nil` while retaining the original `document`/`sourceText` and an empty
manifest export list. The in-progress strict producer returns a valid descriptor
with zero symbols instead of rejecting the lost declaration AST.

**Root cause/plan:** `topLevelStats` folds only `module.sections`; it has no
completeness cross-check against retained parseable document blocks. Compare the
retained declaration-source containers with section code blocks and return stable
`UNSUPPORTED_PUBLIC_DECLARATION` whenever parsed declaration structure is missing.
Cover the exact copy-based repro.
**Baseline:** focused producer suite reproduces the copied module as an unexpected
successful empty `ApiDescriptor` (`18/25` total green before the fix).

**Local correction checkpoint:** implementation `72e6a2897` on
`origin/main@790366a9d` fails closed when retained declaration containers lose
their section AST. Focused producer 46/46, descriptor 27/27, core 1092/1092,
interop 36/36, IR, artifact ABI 73/73, and affected conformance 2/2 are green.
Status remains `open` until fresh independent approval and landing on
`origin/main`.

## control-interop-residual-forwarding-absent — FIXED / awaiting confirmation (2026-07-15, Codex)
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: d764c2ebe
     confirmed: no -->

**Status:** fixed in `d764c2ebe`, with self-hosted frontend qualification in
`84ad12651` and selected-only total-handler hardening in `9273ae0f6`; awaiting
reporter confirmation after the feature branch lands.

**Symptom:** an operation unhandled by the nearest inner handler is sent to that
handler anyway and fails with `match: no arm`, instead of remaining an explicit
`Op` for the next enclosing handler.

**Reproduce:** run
`tests/interop-conformance/probes/19-residual-forwarding-nested-handlers.ssc`:
it nests an inner `Rd` handler inside an outer `Wr` handler, then performs
`Wr.wr` inside the inner scope. Before the fix, portable VM/direct ASM reported
`no arm for wr/2`; both installed lanes now print exact `57` with empty stderr.

**Root cause:** the handler fold called the nearest generated partial-function
closure as an ordinary total function. Its missing arm became an immediate
textual match failure, so the fold could not distinguish a recoverable miss from
a failure thrown inside a selected arm or continue an effectful guard decision.

**Fix/verification:** qualified handler roots now return a private structured
`Matched | Unhandled | Suspended` decision with exact-event, owner, and activation
provenance. A miss rebuilds the existing three-field `Op` around the same deep
continuation and base multiplicity gate; no public CoreIR/data ABI changed and no
exception text is parsed. Axis 19 is measurable-now (`57`), focused JVM tests are
17/17 + 4/4 + 6/6, native e2e and stage2 source-exact fixed points pass, affected
conformance is 6/6, all 11 interop axes pass, and focused JVM/JS/Rust/Swift marker
checks are green. Full evidence is recorded in
`specs/control-residual-forwarding.md`.

## swift-effect-handler-implicit-return-fallback — FIXED / awaiting confirmation (2026-07-15, Codex)
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: f21abfcc8
     confirmed: no -->

**Status:** fixed in `f21abfcc8`; awaiting confirmation. Found by the real Swift
checked-source regression while implementing the portable one-shot guard.

**Symptom:** a Swift AOT handler that omits `case Return(value)` fails on its
first `resume` with `match: no arm for Return(1)`. JVM VM/ASM implement the
documented convenience semantics: if the handler has no `Return` arm, a normal
returned value passes through unchanged. The mismatch prevents the exact same
minimal handler source from reaching a second resume on Swift.

**Reproduce:** compile and run on Swift:
`handle(One.op()) { case One.op(resume) => resume(1) + resume(2) }`. With a
plain one-shot effect, Swift reports the missing `Return` arm before testing the
second claim; adding `case Return(value) => value` reaches the intended stable
`ONESHOT_VIOLATION`. The same source without a Return arm reaches that violation
on JVM VM/ASM.

**Root cause:** generated Swift `handleEffect` directly calls the handler with
`Return(value)`, while a no-match in the generic Swift evaluator is an immediate
`fatalError`. JVM `PortableEffects.handle` recognizes only a missing Return arm
and returns the value; Swift has no equivalent recoverable no-arm signal.

**Fix/verification:** Swift now uses a private `matched | noMatch` result only for
the directly invoked handler partial-function match. An absent `Return` arm maps
to identity; selected arms and fallbacks execute through the ordinary evaluator,
so nested match/runtime/control failures remain failures. The same no-`Return`
fixtures now reach the stable one-shot violation and reusable result `3` on Swift
and JVM VM/direct ASM. Swift focused tests, native effect e2e, `installBin`, and
fresh affected conformance (6/6) pass.

## v21-stage2-gate-ignores-symlinked-std-sources — FIXED / awaiting confirmation (2026-07-15, Codex)
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 13b29852e
     confirmed: no -->

**Status:** fixed in `13b29852e`; awaiting reporter confirmation. Found while
running the mandatory self-hosted fixed-point gate for `control-one-shot-guard`.

**Symptom:** `scripts/v21-stage2-bootstrap-gate` rejects a freshly generated
native-front image because all `runtime/std/scljet/*.ssc` files exist only in
the staged manifest. `installBin` correctly follows the tracked
`v1/runtime/std/scljet -> ../../../scljet` compatibility symlink, but the gate's
source manifest silently omits that directory.

**Reproduce:** run `scripts/sbtc "installBin"`, then
`scripts/v21-stage2-bootstrap-gate`. The fixpoint compiler checks complete, but
the source-exact comparison reports the 19 current SclJet `.ssc` files as staged
additions. `find v1/runtime/std -type f -name '*.ssc'` counts 105 while the same
command with `-L` and the installer both count 124.

**Root cause:** the gate builds its source manifest with `find` without `-L`,
unlike sbt's recursive glob used by `installBin`. The two sides therefore apply
different source-tree semantics to a documented, tracked compatibility symlink.

**Fix/verification:** the gate now uses symlink-following enumeration for the
source manifest, matching `installBin`. After a fresh install on current `main`,
`scripts/v21-stage2-bootstrap-gate` reports both single/multi fixed points true,
131 compiler-image files, and `compiler.image.source-exact=true`.

## scala3-control-capability-jvm-visibility — FIXED / awaiting confirmation (2026-07-14, Codex)
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 528d73af3
     confirmed: no -->

**Status:** fixed in `528d73af3`; awaiting reporter confirmation. Found by `api_type_design` with
`javap -public` against the uncommitted leaf; reported by codex-interop in the
`scalascript` rozum room.

**Symptom:** Scala `private[control]` and several plain-private cross-companion
bridges compile to public JVM methods/constructors. The draft exposed
`Eff.request`, continuation factories, key/prompt constructors and prompt internals,
so Java or same-package Scala could bypass the intended construction boundary.

**Reproduce:** run `javap -public` on the draft classes; it lists `Eff.request`,
`Continuation.runtime`, `OneShotContinuation.runtime/delegate`, and public JVM
constructors for `EffectKey`, `Continuation`, and `Prompt`.

**Fix/verification:** raw pending-request construction is absent. Private nested
implementations are used where possible; every unavoidable JVM-visible constructor
or factory requires an identity-validated private authority, and null/freshly forged
tokens fail before construction. The complete compiled-class `javap -public`
inventory is an executable test and reports no unguarded request, prompt, key,
resumption gate, authority issuer, or successful saved-continuation constructor.

## scala3-control-shift-row-widening — DONE (2026-07-14, Codex)
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 06b4e4be1 -->

**Status:** done in `06b4e4be1` (tier-1 API type-safety blocker). Found by the
`prompt_source_design` delegated compile audit while implementing the ABI landed at
`98e9645e1`; reported by codex-interop in the `scalascript` rozum room.

**Symptom:** the frozen `shift[P,A,Fx,R]` body receives
`Continuation[A,Fx,R]`, but the covariant result `Eff[Fx | Control[P],A]` can be
widened by a later `flatMap` before `reset`. The actual captured suffix may therefore
perform `Fx2 >: Fx` while the shift body still sees the narrower `Fx`; choosing
`Fx = Nothing` can incorrectly pass the effectful continuation to `Eff.runPure`.

**Reproduce:** construct `shift` with inferred minimum row `Nothing`; in its body,
call `Eff.runPure(k.resume(value))`; append a nominal effect operation with
`flatMap`; then enclose the combined computation in the matching `reset`. The old
types accept the program even though `k.resume` reaches that appended request.

**Fix/verification:** `ShiftBody` is now rank-2 over every
`Residual >: Fx`, so the shift body receives the actual widened continuation row.
Scala 3.8.3 compile probes accept ordinary/nested prompt use and reject the old
`runPure(k.resume(...))` repro at the typer. The reporting audit confirmed the
positive and negative probes after the spec correction.

## control-interop-effect-recursion-stack-unsafe — FIXED / awaiting confirmation (2026-07-14, claude)
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 3de5020c5
     confirmed: no -->

**Status:** fixed by the shared driver in `3de5020c5` and completed by JVM
lowering fixes in `956b42539`; awaiting reporter confirmation. Reported by
codex-interop (rozum #interoperability, 2026-07-14): "legacy deep-handler/return
paths appear recursively stack-unsafe".

**Symptom:** effect-performing recursion grows the native stack and overflows; pure tail
recursion is unaffected.

**Reproduce:** a recursion that performs one effect per frame overflows between depth 500 and
2000 (`java.lang.StackOverflowError` at 2000) on BOTH portable-VM tiers (`ssc run` and `ssc
run --bytecode`, 2026-07-14); the same shape with no effect (pure TCO) runs to 2,000,000
(conformance axis 03 ✓).

**Fix/verification:** handler-facing resume now returns an unforgeable private
deferred request, and one iterative two-mode driver consumes typed heap frames.
Only declared managed program/host boundaries drain escaped requests; public
`Op/3`, one-shot gates, and residual handler ownership remain unchanged.

**Secondary root cause found during the fix (2026-07-15):** after the private
resume carrier was moved to a managed-boundary driver, the installed VM still
printed that carrier in the escaped state-thread vector while direct ASM
completed it. `Compiler.C.compile` evaluated every `Term.Prim` argument and
invoked the primitive immediately; it did not mirror direct ASM's
`OpAnfNative` argument lifting. Thus `println(escapedResume(0))` consumed the
private `Op` before the managed program root could drain it. Spec update
`241c5dcd5` requires left-to-right per-argument `Runtime.letThreadOp` for every
non-effect-substrate primitive, exact exclusions for `effect.handle`,
`effect.perform`, `effect.perform.oneshot`, and `effect.pure`, and a FastCode
guard plus VM/ASM multiple-Op ordering regression. The combined implementation
verification is complete and axis 20 is now measurable.

**Additional correctness holes found during focused verification (2026-07-15):**

- `FastCode.tryFBc` guarded only consumed value positions. A raw CoreIR
  `If(Prim("cell.get", List(cellHoldingAutoOp)), yes, no)` therefore took the
  specialized getter, received an `Op`, and coerced that non-`BoolV` value to
  `false` instead of letting the normal `If` compiler thread it. The focused
  fix is for `tryFBc` to decline any complete condition satisfying
  `Compiler.mayProduceAutoThreadOp`; globally disabling `tryFC` is explicitly
  not required.
- The direct-ASM emitter's pending-method fixpoint omitted `letChains` from its
  outer loop condition. A curried `handle(computation)(handler)` whose entry
  lowering enqueued only an effect-aware `Let` emitted a call to `lam$1` but no
  corresponding method, failing with `NoSuchMethodError`. The queue must drain
  while any pending lambda, sequence chain, or let chain remains.
- After residual forwarding was rebased onto the always-deferred resume driver,
  its multiplicity test called an escaped forwarded continuation directly with
  `Prims.runClos1` and observed the private request. That call models an
  unmanaged host callback; the driver contract intentionally drains only at an
  explicit managed boundary. The integrated regression must invoke those
  continuations through `Runtime.runManaged`, then cover residual forwarding,
  a nested request entering `Handle` mode (`Rehandle`), and exact inner/outer
  `Return` ordering together on VM/direct ASM.

All integration holes above are closed. Focused stack/one-shot/residual suites
pass 39/39; installed VM and direct ASM both return exact `100000`, `100000`,
`20007`, `20000`; full interop is 12/12, affected conformance is 6/6, and the
133-file stage2 compiler image remains source-exact. Axis 20 is promoted from
`pending-runtime`; the verified evidence is in
`specs/control-effect-stack-safety.md`.

## spec-grammar-schema-links — FIXED (2026-07-14, Codex)
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 96fc5adfb -->

**Status:** fixed in `96fc5adfb`; found while mechanically checking local links
after the bidirectional-control update to canonical `SPEC.md`.

**Symptom:** `SPEC.md` links to `grammar/scalascript.ebnf` twice and
`schemas/frontmatter.yaml` once, but the tracked files now live under
`v1/lang/grammar/` and `v1/lang/schemas/`.

**Reproduce:** both old targets fail `test -e`; `rg --files` finds the grammar and
schema only at the `v1/lang/` paths.

**Fix/verification:** all three links now target the tracked `v1/lang/` files;
both destinations exist and the changed documentation passes Markdown lint.

## backlog-active-queue-link — FIXED (2026-07-14, Codex)
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 37c1a69c8 -->

**Status:** fixed in `37c1a69c8`; found by `final_control_spec_audit` while
verifying local links in the bidirectional-control planning slice.

**Symptom:** `BACKLOG.md` links active work to nonexistent `ACTIVE.md`, while the
repository's binding workflow and `SPRINT.md` define the active queue as
`SPRINT.md` plus authoritative `.work/active/*.claim` files on `origin/main`.

**Reproduce:** `test -e ACTIVE.md` fails; compare `BACKLOG.md` lines 3–4 with
`SPRINT.md` lines 3–5 and `AGENTS.md`'s claiming protocol.

**Fix/verification:** the header now links to `SPRINT.md` and names
`.work/active/*.claim` on `origin/main` as the ownership authority; Markdown lint
and the target-existence check pass.

## v2-swift-e2e-standard-launcher-stale — assembled scripts invoke the wrong tier
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: b3a4cea28
     confirmed: no
     gate: tests/e2e/v2-swift-cli.sh -->

**Status:** fixed (2026-07-12, `b3a4cea28`), awaiting Sergiy confirmation;
found by codex after fresh `installBin` and reported in the `scalascript` Rozum
room.

- **Real-harness repro:** `tests/e2e/v2-swift-cli.sh` exits on its first
  `bin/ssc emit-swift` with the bounded message that `emit-swift` requires the
  optional tools/compatibility tier. The Apple script has the same stale
  launcher assignment for `build`.
- **Root cause:** `e28560761` intentionally made `bin/ssc` StandardMain and
  installed the full compatibility CLI as `bin/ssc-tools`; the Swift e2e
  scripts still assumed the former single launcher.
- **Fix/done-when:** point both scripts at freshly installed `bin/ssc-tools`,
  update only their missing-binary hints, and pass the complete assembled CLI
  plus production-shaped macOS/iOS Apple gates. StandardMain remains narrow.
- **Fix/result:** both assembled scripts use `ssc-tools`; fresh CLI and Apple
  gates pass without expanding StandardMain.

## v2-swift-nativeui-standard-pipeline-parity — real Swift cannot run standard lower/serve + locale/JSON
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 11f1e68dc
     confirmed: no -->

**Status:** fixed (2026-07-12, `11f1e68dc`), awaiting `brave-newt` / Sergiy
confirmation; reported by `claude-code` / `brave-newt` in the `scalascript`
Rozum room from busi's production-shaped fixture and accepted by
`scalascript-codex`. Independent final verdicts from
`nativeui-try-reviewer` and `nativeui-json-reviewer`: `APPROVE`.

- **Real-harness repro:** compile the checked busi
  `src/v2/clients/swift-nativeui-smoke/pipeline-smoke.ssc` shape (three keyed
  JSON lists, per-row signals, nested visibility, i18n), or a minimal checked
  `.ssc` importing `text`, `heading`, `styled`, `defaultTheme`, `lower` and
  `serve`. The production pipeline fails first on module `val localeSignal`,
  then `__jsonCoreWrap`, and a minimal standard UI reaches unsupported
  primitive `__try__` through `lower.ssc::_lenOf` (`try v.toInt catch ...`).
- **Coverage hole:** the previously approved
  `examples/swift/appcore-nativeui.ssc` called `emit(fragment(...))` directly.
  It bypassed `lower`, theme-token conversion and the `__try__` path, so green
  Swift/Xcode gates did not prove the standard toolkit contract.
- **Root causes:** Swift validation sees definition names but not the
  compiler-generated module-init `global.reg`; Swift has no canonical
  PluginBridge-equivalent `__try__`/`__throw__` value/error distinction; and
  the new JSON draft differs from the reference renderer/facade in UTF-16,
  huge-number conversions, optional numeric coercion, deterministic/bounded
  failure and encoding behavior. A broad `global.reg` tree scan is unsafe
  because registrations inside lambdas/dead branches never execute.
- **Entrypoint root cause (real Swift discovery):** the checked metadata passed
  to `SwiftV2Cli.emit` omits front-matter `main`. FrontendBridge automatically
  invokes only a function literally named `main`, so a production-shaped
  `main: run` program initializes imported module globals but never calls
  `run()`; `NativeUiHost.evaluate` then fails with `native UI program did not
  register a root`. This is not a fixture issue: the assembled Swift command
  uses the same `convertSourceWithMetadata` result.
- **Builder root cause (next real-runtime boundary):** once `main: run` is
  invoked, `vstack`/`styled` reach `__method__("toList", children)` with an
  already proper `Cons/Nil` value. The v2 shared runtime returns that list
  unchanged, but Swift lacks the method case and terminates with `method not
  found: toList on List(TextNode_(...))` before `lower` can produce a root.
  After `toList` identity, the same gate advances to
  `method not found: mkString on List("padding-left:16px;...")`; Swift also
  omitted the shared List string-join overloads used by `_styleCss`.
  After `mkString`, `lower` reaches `element` but attrs arrive as the checked
  frontend's proper `List(Tuple2(String, Value))`; `nativeUiStringMap` accepts
  only `.map` and fails with `NativeUiElement.attrs expected Map[String,
  Value]`. The ABI must normalize the frontend representation rather than
  changing the standard lowerer fixture.
- **Rejected WIP behavior:** collapsing explicit throw and runtime failure to
  one description String loses the thrown ADT; catching every normalized host
  `Error` hides runtime bugs; `Int64(value)` omits VM/v1 trimming. The current
  v2 `String.toInt` also leaks `NumberFormatException`, so PluginBridge does not
  yet provide its claimed recoverable-error oracle. JSON surrogate
  halves are dropped and astral scalars render as invalid `\\u1f600`; renderer
  installation is ignored; huge integer/`optInt`/`asInt` and malformed-value
  behavior diverge. Spec review further found that installed self-hosted JSON
  emits uppercase escapes while the native fallback is lowercase; non-string
  map keys use the reference `Value.toString`; huge integral `optInt` uses
  `BigDecimal.longValue` low bits; and the exact unsafe validator case is a
  nested registration inside an outer registration's value expression.
- **Done-when:** the feature spec freezes exact semantics and receives Rozum
  pre-code approval; real Swift proves exact throw payload, nested handler
  propagation, recoverable trimmed `toInt`, non-catchable host negative, safe
  init-only global registration, complete JSON facade/Unicode/number/error
  parity, and the checked busi fixture runs through
  `serve(lower(view(), defaultTheme), ...)`. Full existing Swift/CLI/Apple and
  conformance gates remain green. Move to `fixed` only with landed SHA, and to
  `done` only after reporter/reviewer confirmation. The standard fixture must
  retain and invoke `main: run` exactly once after module initialization; an
  absent target is a checked error, not a root-registration runtime crash.
  Its unchanged curried builders must also prove `List.toList` identity under
  real Swift and the resulting CSS list must join through `mkString("")`;
  replacing either with direct constructors/string literals would not close
  the bug.
  `element` must then accept the association-list attrs and retain the produced
  styles; malformed list/tuple/key shapes remain bounded failures.
  The next observed failure is currently opaque (`app: not a function`); Swift
  omitted the non-callable value that shared v2 Runtime includes. Add that
  deterministic value text before classifying/fixing the next seam.
  With the value included, the failure is `List(32, 24, 20, 18, 16, 14)` from
  `lower.ssc`'s `sizes(level - 1)`. Shared `Runtime.applyFallback` supports
  proper-List indexed application; Swift evaluator does not.
- **Anonymous signal identity root cause:** after the standard lower fixture is
  green, the production locale/JSON fixture fails with `duplicate native UI
  signal '__computed__...localeText...' in scope 'root' has conflicting
  kind/default`. `computedSignal` and `eqSignal` derive ids from lexical site
  alone and ignore the spec's per-owner occurrence, so multiple calls to the
  same imported component alias one signal.
- **List concatenation root cause:** after anonymous ids are qualified, the
  production card lowerer fails on `headerParts ++ [bodyEl] ++ footerParts`.
  Shared v2 `arithOp` concatenates proper lists for `+`/`++`; Swift dynamic
  arithmetic implements only String concatenation.
- **Post-code review status:** runtime structure passed inspection, but closure
  remains blocked on faithful coverage. The first green regression checkpoint
  is `82e10647e`; reviewers require complete per-operator List matrices,
  `mkString` rejection negatives, anonymous-derived transactional lifecycle
  execution, and direct-map/events/array/nonzero-source NativeUi normalization
  before the bug may move from `open` to `fixed`.
- **Nested-owner lifecycle root cause (real Swift discovery):** an outer keyed
  render can provision an owner-scoped anonymous derived signal and then run an
  inner `reconcileKeyed`. The inner commit calls global scope disposal before
  the outer `ownerScopes` entry exists, so it deletes the still-live outer
  signal. The faithful probe observes four distinct outer/inner sibling ids but
  only three total cells instead of empty-header baseline plus four. Disposal
  must be deferred to the outermost reconciliation commit; the outer snapshot
  remains the rollback boundary and recursively removed owner subtrees are
  collected before the single disposal pass.
- **Final nested-delete proof still required:** keep the outer key and remove
  only its inner keyed child. The inner call must expose an empty deferred
  disposal list; the enclosing commit must dispose exactly the inner derived
  cell while preserving the outer cell, and reinsertion must not resurrect the
  deleted cell object even though its structural id is reused.
- **Fix/result:** the landed Swift evaluator/runtime now executes the standard
  lower/serve + locale/JSON pipeline, exact manifest/try/conversion/List/map
  boundaries, and owner-transactional anonymous derived signals. The final
  retained-outer nested-delete proof is green, both reviewers approved, Swift
  backend is 54/54, combined CLI is 54/54, PluginBridge is 33/33, assembled
  Swift CLI passes, and the same production app builds/verifies on macOS and
  iPhone 16 Pro Simulator. `tkv2-*` conformance is 12/12.

## v21-module-gate-misses-jca-provider — derived JRE omits Ed25519 module
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 43fded0f9
     confirmed: no
     gate: tests/e2e/v21-negative-toolchain-release-gate.sh -->

**Status:** fixed (2026-07-12, `43fded0f9`), awaiting Sergiy confirmation;
found by codex in the standard-only negative release gate.

- **Real-harness repro:** run `tests/e2e/v21-negative-toolchain-release-gate.sh`.
  The copied standard graph with the current `jdeps`-derived module set changes
  `crypto-verify-demo.ssc` from identical to `both-fail`; focused VM and ASM
  both exit 1 after three lines with `ssc: Ed25519 Signature not available`.
- **Root cause:** `Signature.getInstance("Ed25519")` discovers its JCA provider
  dynamically from `jdk.crypto.ec`, so static `jdeps --print-module-deps` cannot
  see the edge. The existing JRE-shaped gate did not exercise signature APIs
  and therefore reported an incomplete runtime module set as green.
- **Done-when:** the allowed standard runtime module set explicitly retains the
  JDK crypto provider module, focused Ed25519 VM/ASM checks pass under
  `--limit-modules`, and the negative exhaustive parity returns to 53/13 with
  zero unclassified or blocking rows. Compiler modules must remain absent.
- **Fix/result:** both module-limited gates add the reflective `jdk.crypto.ec`
  provider edge and run exact Ed25519 VM/ASM checks. The combined negative
  environment returns to 53/13/129, compiler modules remain unresolvable, and
  the consolidated release gate passes.

## v21-native-sql-fence-token-activates-client-code — SQL token parsing widened code fences
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: e3632db14
     confirmed: no -->

**Status:** fixed (2026-07-12, `e3632db14`), awaiting Sergiy confirmation;
found by codex in the full SQL-fence release gate before push.

- **Real-harness repro:** run
  `scripts/v21-self-hosted-core-release-gate --skip-install` after native SQL
  fence lowering. `sql-h2-quickstart.ssc` becomes standard-green, but
  `derived-route-clients.ssc` newly becomes `both-fail` on VM/ASM with
  `unbound global: awaitClient`, leaving parity at 51/15 and the taxonomy row
  unclassified.
- **Root cause:** `sscFenceSource` reused the new attribute-stripped SQL fence
  token for ordinary ScalaScript fences. That changed the existing standard
  JVM side contract by executing `scalascript @side=client`, which is a
  browser/JS-only block and was previously excluded.
- **Done-when:** attribute tokenization remains available for `sql @db` and
  `sql @side`, while ordinary code/YAML fence selection retains its prior exact
  language-tag behavior. Full parity must advance to 52 identical / 14
  both-fail with zero mismatch, one-sided, or unclassified rows.
- **Fix/result:** only SQL dispatch uses the attribute-stripped token; ordinary
  code/YAML fences keep exact tag matching. `derived-route-clients.ssc` is
  standard-green again, the SQL quickstart stays exact, and the exhaustive
  release gate reaches the required 52/14 classification.

## v21-native-typed-sql-crud-missing — standard provider lacks typed Db writes/read
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 50d01a136
     confirmed: no -->

**Status:** fixed (2026-07-12, implementation `50d01a136`, taxonomy
`f92ca4fcb`, boundaries `333d0a9bd`), awaiting Sergiy confirmation; accepted
from the final TI-8.2d runtime taxonomy and owned by codex.

- **Real-harness repro:** after `scripts/sbtc "installBin"`, run
  `bin/ssc-standard run examples/typed-sql-crud.ssc` and repeat with
  `--bytecode`; both are reviewed `both-fail` rows while explicit
  `bin/ssc-tools run --compat-frontend` supplies the reference behavior.
- **Root cause boundary:** the self-hosted document extractor drops the schema
  SQL fence; independently, derives initialization calls missing
  `RowCodec_derived`, and `Db.query[Todo]` loses `Todo` before CoreIR. Insert and
  update survive as `Db` method calls over a portable `Todo/3`, so provider
  writes can use registered field metadata once the missing boundaries land.
- **Done-when:** schema execution plus typed insert/update/read produce the
  public exact output on standard VM/direct ASM/reproducible build-jvm through
  the core-free SQL provider, with negative conversion coverage and no v1 or
  compiler fallback.
- **Fix/result:** the parser retains the exact `Db.query[A]` nominal tag,
  `RowCodec_derived` registers portable Mirror schemas, and the provider owns
  fully-bound query/insert/update. The public row is exact on VM/ASM/slim/JRE/
  build-jvm; focused errors are bounded before stdout, and exhaustive runtime
  taxonomy reaches zero blocking rows.

## v21-native-sql-fence-binding-missing — SQL section result is not native
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 97c7d3e00
     confirmed: no -->

**Status:** fixed (2026-07-12, implementation `97c7d3e00`, taxonomy
`721490e99`, side correction `e3632db14`), awaiting Sergiy confirmation;
accepted from the final TI-8.2d runtime taxonomy and owned by codex.

- **Real-harness repro:** after `scripts/sbtc "installBin"`, run
  `bin/ssc-standard run examples/sql-h2-quickstart.ssc` and repeat with
  `--bytecode`; both are reviewed `both-fail` rows while explicit compatibility
  supplies the reference behavior.
- **Root cause boundary:** `sscProgramSource` recognizes ScalaScript and YAML
  fences only. Every SQL fence is absent from CoreIR, so no DDL/DML/query runs;
  surviving code lowers `ActiveUsers.sql` and `Headcount.sql` as method calls on
  unbound heading globals. The current core-free provider already owns named
  connections plus `Db.query/execute`; it needs one generic raw SQL result
  operation and section-value registration, not a v1 SQL parser/runner.
- **Done-when:** DDL/DML/query fences, `${expr}` binds, and generic section
  result binding are exact on standard VM/direct ASM/reproducible build-jvm,
  including bounded malformed/config errors and no transparent fallback.
- **Fix/result:** the document projection injects source-ordered `Db.sql`
  calls, `_sqlBlock_N`, and the first structural `<Section>.sql`; the core-free
  provider returns portable row maps or update counts. Public quickstart and
  focused bind/order fixtures are exact on VM/ASM/slim/JRE/build-jvm, negative
  sentinels fail before stdout, and release parity advances to 52/14 with one
  typed CRUD blocker remaining.

## v21-native-tuple-lambda-destructuring — collection callbacks expect two arguments
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 12d3d9cf2
     confirmed: no -->

**Status:** fixed (2026-07-12, language `12d3d9cf2`, taxonomy `06a518685`),
awaiting Sergiy confirmation; found by codex after parameterless-def value
semantics advanced the mini-language pipeline report.

- **Real-harness repro:** standard VM/direct ASM now print the first nine
  canonical `dsl-mini-language.ssc` lines, then both fail after the pipeline
  report heading with `match: no arm for Pair/2`; explicit compatibility prints
  four additional `[phase] ok` lines.
- **Root cause:** source `(name, pass) => ...` and `(phase, outcome) => ...`
  callbacks lower as `lam 2`, but `List.map`/`flatMap` pass one portable
  `Pair/2` element. The body therefore receives the wrong environment shape and
  later tries to match a Pair as an Either outcome.
- **Done-when:** a tuple-pattern lambda accepts one Pair/Tuple2 value,
  destructures it once in declaration order, and works across imports and
  collection callbacks on VM/ASM/build-jvm. Ordinary `(a, b) =>` callables that
  are invoked with two arguments must retain their existing arity; no runtime
  arity retry, reflection, or DSL special case.
- **Fix/result:** the shared collection callback seam recognizes only exact
  `Pair`/`TupleN` tags whose field count matches the closure arity. The full
  public pipeline is exact on VM/ASM/build-jvm; release parity is 48/18 with
  five blockers and zero mismatch/one-sided rows.

## v21-native-focus-optics-unlowered — Focus/Prism remain globals
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: a3d5abde0
     confirmed: no -->

**Status:** fixed (2026-07-12, frontend/provider `a3d5abde0`, artifact
`16a4b9f8f`, taxonomy `5557ddf61`), awaiting Sergiy confirmation; found by
codex in TI-8.2d3m audit.

- **Real-harness repro:** standard VM/direct ASM run `examples/lenses.ssc`,
  print only the two pre-Focus rows, then fail `unbound global: Focus`;
  explicit `--compat-frontend` prints all 23 canonical rows.
- **Root boundary:** CoreIR retains `app(global Focus, lam ...)` and a bare
  `global Prism`; no structural path or optic value is synthesized.
- **Done-when:** portable Lens/Optional/Traversal/Prism values implement the
  public structural paths and composition exactly on VM/ASM/build-jvm without
  compiler macros, reflection, example-specific globals, or fallback.
- **Fix/result:** the self-hosted frontend serializes field/`.some`/`.each`
  selector steps and exact Prism variants to portable CoreIR; a required
  core-free ServiceLoader provider performs immutable get/set/modify/compose.
  The first release run exposed a separate strict `build-jvm` provider-prefix
  omission, fixed in `16a4b9f8f`. All 23 public rows are now exact on standard
  VM/direct ASM/reproducible JAR; arbitrary getter arithmetic fails before
  stdout with `__unsupported_focus_path`. Full release parity is 51/15 with
  zero language-runtime rows and two remaining SQL-provider blockers.

## v21-native-named-copy-labels-dropped — case-class copy overrides by position
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: d01d2e9f1
     confirmed: no -->

**Status:** fixed (2026-07-12, `d01d2e9f1`), awaiting Sergiy confirmation;
found by codex in TI-8.2d3m audit.

- **Real-harness repro:** before the Focus failure, `alice.copy(age = 31)`
  prints `older   : 31, 30` instead of `older   : Alice, 31` on both standard
  lanes. Explicit compatibility is correct.
- **Root cause boundary:** named-argument labels are erased before CoreIR;
  `copy(age = 31)` becomes `__method__("copy", alice, 31)` and replaces field
  zero. Multi-label copies happen to work only when labels follow declaration
  order.
- **Done-when:** generic structural copy preserves/reorders labels by the
  registered case-class layout, evaluates receiver/overrides once, and keeps
  positional copy behavior unchanged on VM/ASM/build-jvm.
- **Fix/result:** the native lowerer binds receiver and named overrides once in
  source order, then passes label/local pairs through existing portable copy
  dispatch. The generic focused fixture is exact on standard VM, direct ASM,
  and reproducible build-jvm (`RCN` proves ordering); release stays 50/16 with
  three blockers because the independently tracked Focus/Prism gap remains.

## v21-native-derives-mirror-unsynthesized — product evidence is absent
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: db97ad954
     confirmed: no -->

**Status:** fixed (2026-07-12, language `db97ad954`, taxonomy `151fd65b1`),
awaiting Sergiy confirmation; found by codex in TI-8.2d3m audit.

- **Real-harness repro:** standard VM/direct ASM fail before stdout with
  `unbound global: summon`; explicit compatibility prints
  `Person`, `name|age`, `String|Int`, `name,age`.
- **Root boundary:** the case-class layout is registered and `Csv.derived` is
  retained, but `derives Csv` creates no `Mirror.Of[Person]` value or exact
  `Csv[Person]` dictionary; both summons remain `global summon` in CoreIR.
- **Done-when:** portable product metadata and derived evidence are synthesized
  from declarations, exact summon lookup resolves both values, and unsupported
  evidence remains a bounded compile/runtime failure without reflection.
- **Fix/result:** the self-hosted AST retains ordered product types and derives
  names; the lowerer emits portable Mirror data plus one source-ordered cached
  `TC.derived` result under exact given keys. Focused aliases/caching and the
  public VM/ASM/build-jvm output are exact; release is 49/17 with four blockers.

## v21-native-parameterless-def-value — nullary method is passed as a function
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 12d3d9cf2
     confirmed: no -->

**Status:** fixed (2026-07-12, language `12d3d9cf2`, taxonomy `06a518685`),
awaiting Sergiy confirmation; found by codex in TI-8.2d3m audit.

- **Real-harness repro:** standard VM/direct ASM print the mini-language success
  heading then fail `arity: 0 expected, 1 given`; explicit
  `--compat-frontend` prints all 13 canonical lines.
- **Root cause:** `def typeCheckPass: Pass[Any, Any] = ast => ...` lowers to
  `lam 0(lam 1(...))`. Bare value use in
  `parsePass.andThen(...).andThen(typeCheckPass)` passes the nullary closure
  itself; the composed pipeline later calls it with the AST.
- **Done-when:** an ordinary reference to a declared parameterless def evaluates
  it once at the use site, while explicit `def f()` and higher-arity function
  values retain their current semantics; focused and public VM/ASM/build-jvm
  output is exact.
- **Fix/result:** source clause shape survives the self-hosted AST and lowering;
  a bare declared reference emits one zero-argument CoreIR application while
  explicit callees are not double-applied. The imported regression and all 13
  public lines are exact on VM/ASM/build-jvm.

## v21-native-distributed-loopback-provider-missing — NamedHandler is absent from standard runtime
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 31d730c1e
     confirmed: no -->

**Status:** fixed (2026-07-12, provider `31d730c1e`, language `2b87c57df`,
taxonomy `e0e7e98c3`), awaiting Sergiy confirmation; found by codex after the
core-free Actors slice landed while continuing TI-8.2d3.

- **Real-harness repro:** run `bin/ssc-standard run` and repeat with
  `--bytecode` on `examples/distributed-join.ssc` and
  `examples/distributed-log-aggregation.ssc`.
- **Observed:** all four runs exit 1 before file I/O at
  `ssc: unbound global: NamedHandler`; no stdout.
- **Second boundary after provider install:** `NamedHandler` resolves, but all
  four real runs now exit at `unhandled runtime effect:
  HandlerRegistry.register`. The self-hosted lowerer routes the imported object
  method through the effect ABI rather than the registered method-object field.
  Resolve that exact structural/effect ownership in the core-free provider or
  lowerer; do not install a catch-all effect fallback.
- **Third boundary after exact ABI binding:** log aggregation is byte-exact on
  VM/ASM. Join exits zero but exposes separately tracked
  `v21-native-tuple-field-patterns`; its literal and typed tuple fields are not
  enforced by self-hosted lowering.
- **Expected:** a required core-free provider owns the exact named-handler,
  stage, local-loopback cluster, distributed map/filter, shuffle group/reduce,
  result, and close contracts used by these two standard examples. Fixed input
  fixtures produce deterministic output on VM/ASM/build-jvm without actor
  network transport, the v1 scheduler, or compatibility fallback.
- **Plan/done-when:** specify portable values and deterministic partition/order/
  error semantics, implement and unit-test a ServiceLoader provider, prove both
  public programs with fixed inputs on all standard JVM paths, pass the full
  release/dependency gates, and retire only the two distributed taxonomy rows.
- **Fix/result:** the required core-free provider owns exact structural
  `HandlerRegistry.*` operations plus portable local-loopback stage/shuffle
  values. Both public examples are exact on VM/ASM/slim/build-jvm; parity moves
  to 46 identical / 20 both-fail with zero mismatch/one-sided rows and the two
  distributed taxonomy rows are retired.

## v21-native-actors-provider-missing — runActors is absent from standard native runtime
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 289b828b9
     confirmed: no -->

**Status:** fixed (2026-07-12, provider `289b828b9`, language/runtime
`ac30dd778`, taxonomy `2230ebc8a`), awaiting Sergiy confirmation; found by
codex after Async landed while continuing TI-8.2d3.

- **Real-harness repro:** run `bin/ssc-standard run --native` and
  `--native --bytecode` on `examples/actors-pingpong.ssc` and
  `examples/actors-typed-remote-spawn.ssc`.
- **Observed:** all four runs exit 1 at the first block with
  `ssc: unbound global: runActors`; no stdout.
- **Second boundary after provider install:** ServiceLoader rejects the complete
  standard provider set before evaluation with
  `native plugin ownership conflict for global 'exit': 20-os and 60-actors`.
  Actor `exit(pid, reason)` and process `exit(code)` therefore need one explicit
  arity/shape dispatch owner or a lowering-owned namespace; disabling ownership
  checks or falling back to compatibility code is not acceptable.
- **Third boundary after explicit exit composition:** typed loopback is exact on
  VM/ASM, but ping-pong is not. VM prints only `after timeout: None`,
  `before timeout: None`, `done`; direct ASM fails with
  `Actors scope failed: if: condition not Bool: "one"`. This points at the
  provider callback ABI/concurrent source-closure execution rather than mailbox
  registration. Diagnose against emitted CoreIR/closure invocation and pin the
  multi-actor source shape in the real launcher before retiring either row.
- **Expected:** a required core-free Actors provider owns local virtual-thread
  mailboxes, timeout receive, send/self/exit, quiescent runner shutdown, and the
  typed named-behavior loopback surface. Canonical outputs match the explicit
  compatibility tier without loading its bridge or interpreter.
- **Plan/done-when:** specify lifecycle/quiescence/error semantics, implement a
  ServiceLoader provider with unit and assembled regressions, prove both public
  examples exact on VM/ASM/build-jvm, pass full release/dependency gates, and
  retire only the two proven actor taxonomy rows.
- **Root cause/fix:** the standard tier had no Actors provider. Adding it exposed
  two independent boundaries: OS and Actors both claimed bare `exit`, and the
  self-hosted frontend treated infix `pid ! msg` as a later prefix negation while
  primitive typed patterns never matched `String`. OS now solely owns bare
  `exit` and explicitly dispatches actor-shaped calls to `actor.exit`; the front
  emits portable actor send and `__isTag__` recognizes primitive nominal types.
  The required Actors provider supplies FIFO virtual-thread mailboxes,
  quiescence/error propagation, timeout/self/exit, and typed named loopback.
  Provider unit is 4/4 and OS dispatch is 3/3; focused and public outputs are
  exact on VM/ASM/build-jvm. Full parity is 44 identical / 22 both-fail / 129
  skipped with zero mismatch/one-sided rows; taxonomy is 10 blockers / 22 total
  and the complete release gate passes.

## v21-native-async-provider-missing — runAsync is absent from standard native runtime
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 4a5caa0ae
     confirmed: no -->

**Status:** fixed (2026-07-12, `4a5caa0ae`, taxonomy `7ac63130d`), awaiting
Sergiy confirmation; found by codex after the Generator provider landed while
continuing TI-8.2d3.

- **Real-harness repro:** after `scripts/sbtc "installBin"`, run
  `bin/ssc-standard run --native examples/async-demo.ssc`, then repeat with
  `--bytecode`.
- **Observed:** both engines exit 1 at the first block with
  `ssc: unbound global: runAsync`; no demo output is produced.
- **Expected:** a core-free standard provider owns deterministic `runAsync`,
  virtual-thread `runAsyncParallel`, and `Async.delay/async/await/parallel`
  handling with ordered results and propagated failures on VM, ASM, and
  `build-jvm` without the compatibility bridge.
- **Plan/done-when:** specify the Future/error/lifecycle contract, extend the
  native effect-runners provider, cover sequential and parallel behavior in
  unit and real assembled fixtures, prove the complete public demo exact, pass
  every release/dependency gate, and retire only `async-demo.ssc` from runtime
  taxonomy.
- **Fix/evidence:** the required effect-runners provider now owns deterministic
  `runAsync`, virtual-thread `runAsyncParallel`, opaque futures, delay,
  async/await/parallel, nested scope restoration, ordered joins, explicit
  failures, and the bounded named-method `recvFrom` bridge. Unit is 4/4 with
  latch-proved concurrent start and reverse completion. Focused/public demos
  are exact on VM/direct ASM/build-jvm; full parity is 42 identical / 24
  both-fail / 129 skipped with zero mismatch/one-sided rows, and taxonomy falls
  to 12 blockers / 24 total.

## v21-native-generator-provider-missing — generator is absent from standard native runtime
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: fa265325f
     confirmed: no -->

**Status:** fixed (2026-07-11, `fa265325f`, taxonomy `6f3c398e5`), awaiting
Sergiy confirmation; found by codex while continuing TI-8.2d3 after the Dataset
provider landed.

- **Real-harness repro:** after `scripts/sbtc "installBin"`, run
  `bin/ssc-standard run --native examples/generators.ssc`, then repeat with
  `--bytecode`.
- **Observed:** both engines exit 1 at the first expression with
  `ssc: unbound global: generator`; no example output is produced.
- **Expected:** a core-free standard provider owns `generator`, `suspend`, and
  the local Generator combinators with pull backpressure. Finite streams are
  ordered; `take` cancels an abandoned infinite upstream; VM, ASM, and
  `build-jvm` are exact without compatibility code.
- **Plan/done-when:** specify the queue/lifecycle contract, implement a required
  ServiceLoader provider over JDK 21 virtual threads and synchronous handoff,
  add unit and assembled regressions including infinite Fibonacci and nested
  flatMap, pass every dependency/distribution/corpus gate, and retire only the
  proved generator taxonomy row.
- **Fix/evidence:** a required core-free provider now owns `generator`,
  dynamically scoped `suspend`, single-consumer synchronous pull, explicit
  producer failures, and all local combinators. Unit coverage is 5/5 including
  100k stack safety and latch-proved infinite-source cancellation. The focused
  lifecycle fixture and all thirteen public lines are exact on VM/direct ASM;
  `build-jvm` is exact and compiler-free. Full parity is 41 identical / 25
  both-fail / 129 skipped with zero mismatch/one-sided rows; taxonomy falls to
  13 blockers / 25 total and the release gate is ready.

## v21-native-dataset-provider-missing — standard Dataset calls escape as effects
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 17cee1805
     confirmed: no -->

**Status:** fixed (2026-07-11, `17cee1805`, taxonomy `9feff81a8`), awaiting
Sergiy confirmation; found by codex while starting queued TI-8.2d3g.

- **Real-harness repro:** after `scripts/sbtc "installBin"`, run each of
  `examples/dataset-{stats,word-count,parallel-sum}.ssc` through
  `bin/ssc-standard run --native`, then repeat with `--bytecode`.
- **Observed:** VM rejects `Dataset.of`, `Dataset.fromFile`, and
  `Dataset.fromList` as unhandled effects. Direct ASM leaks the same missing
  statics as `Op/3`; rendering the 100k-element `fromList` payload additionally
  overflows `Show.ul` before the real program can run.
- **Second boundary after provider install:** `dataset-word-count.ssc` still
  fails before any lazy stage executes. Structural CoreIR proves the lowerer
  sends opaque Dataset receivers through `_sel_flatMap`, `_sel_filter`,
  `_sel_map`, and `_sel_take`; those helpers match only List/Option/Either and
  try to destructure `ForeignV` as data instead of falling through to
  `__method__`. Split-val Dataset calls work, confirming provider values and
  tuple callbacks are sound.
- **Expected:** the standard native provider owns local `Dataset` constructors,
  lazy transformations, and terminals on both execution engines, with
  deterministic values and stack-safe large-list conversion. Spark and
  distributed execution remain explicit backend/provider surfaces.
- **Plan/done-when:** specify and add a core-free ServiceLoader provider with no
  `PluginBridge`, v1 interpreter, Scalameta, Scala compiler, or Java compiler
  dependency; prove provider unit behavior, exact assembled VM/ASM/build-jvm
  output for all three public examples, 100k-element stack safety, strict
  dependency/class-load gates, full corpus/parity/taxonomy, and fresh
  conformance before retiring the three rows.
- **Fix/evidence:** a required native Dataset provider now owns the lazy local
  plan, deterministic operations, UTF-8 files, iterative list conversion, and
  ordered virtual-thread pointwise stages. Structural selector helpers retain
  their List/Option/Either fast arms and dynamically fall through for opaque
  receivers. Provider unit is 4/4; all three public examples and the complete
  fixture are exact on VM/direct ASM/build-jvm. The full release gate is ready
  at 50 runtime successes, 40 identical / 26 both-fail / 129 skipped, zero
  mismatch/one-sided rows, and 14 remaining blockers.

## v21-native-doc-nested-render — nested documents leak the runtime tag
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: fe279650d
     confirmed: no -->

**Status:** fixed (2026-07-11, `fe279650d`), awaiting Sergiy confirmation;
found by codex when native `md` unblocked the complete public
`examples/content.ssc` execution.

- **Real-harness repro:** after a current-source `scripts/sbtc "installBin"`,
  run `bin/ssc-standard run --native examples/content.ssc` (or add
  `--bytecode`). Both lanes complete, but the `render(doc(table(...),
  table(...)))` section prints `NativeDoc(=== Fruits ===, ...)` and
  `NativeDoc(=== Numbers ===, ...)` rather than the nested document text.
- **Expected:** a `NativeDoc` nested as a part of another `NativeDoc` is rendered
  recursively in source order with the same newline contract; the reserved
  runtime representation never leaks into user output. VM, ASM, and
  `build-jvm` remain byte-identical.
- **Root cause:** the core-free host provider recursively recognizes only the
  outer document. Each inner part is passed to the generic runtime display
  function, which correctly exposes an arbitrary ADT tag but does not know the
  provider-owned `NativeDoc` representation.
- **Plan/done-when:** queue a separate provider-owned spec/fix after the
  language-owned `md` slice lands; recursively flatten only `NativeDoc` values,
  retain ordinary value display, add nested/empty provider and assembled
  regressions, then rerun the full release/dependency/content gates.
- **Fix/verification:** provider-local recursion now flattens only `NativeDoc`
  leaves, skips empty nested documents, and preserves shared display semantics
  for every ordinary value. Host unit is 3/3; focused and full content output is
  exact on VM/direct ASM/build-jvm with no leaked tag; plugin/dependency and
  standard/slim/JRE/build-jvm gates pass; no-memo conformance is 17/17.

## v21-native-multiline-markdown-import-dropped — std parser companion stays unloaded
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 96a1fa9dc
     confirmed: no -->

**Status:** fixed (2026-07-11, `96a1fa9dc`), awaiting Sergiy confirmation; found by codex while reproducing the queued
TI-8.2d2x `dsl-sql-recovery.ssc` blocker in the installed standard tier.

- **Real-harness repro:** after `scripts/sbtc "installBin"`, run
  `bin/ssc-standard run --native examples/dsl-sql-recovery.ssc` and repeat with
  `--bytecode`. VM fails `unhandled runtime effect: Parser.regex`; ASM fails
  `match: no arm for Op/3`. Structural compilation contains no `Parser_*`
  definitions and its source closure contains only the root file.
- **Root cause:** `ssc1-run.ssc0` scans Markdown imports one physical line at a
  time. Every import in the example wraps its link label before `](path.ssc)`,
  so none enters the DFS module closure; selected `Parser.regex` therefore
  looks like an unknown uppercase provider call and becomes the fallback `Op`.
- **Expected:** a standalone Markdown import link may wrap its label across
  physical lines outside fences. The scanner joins only that bounded label,
  preserves existing multiple-links-per-line behavior, and loads the ordinary
  `.ssc` declarations so `Parser.regex` resolves statically to `PRegex`.
- **Plan/done-when:** add a multi-file wrapped-import regression, fix the pure
  self-hosted import scanner without source rewriting or compatibility
  fallback, require the installed VM/direct-ASM public example to advance
  identically, then pass module-loading/native-entry/full release and fresh
  `v2-*` conformance gates before retiring only the proved taxonomy row.
- **Fix/verification:** a bounded pending-label state joins only complete
  standalone links outside fences and resets at paragraph/heading/fence
  boundaries. Focused VM/ASM output is `82`; the real four-module recovery
  closure loads exactly and all release gates pass.

## v21-native-dynamic-bigint-tostring — selected conversion is Int-only
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: e2511c6ad
     gate: tests/conformance/content-linked-namespaces.ssc -->

**Status:** done (2026-07-11, `e2511c6ad`); found by codex after the native structural
content provider exposed the next failure in `content-linked-namespaces.ssc`.

- **Real-harness repro:** after `scripts/sbtc "installBin"`, both
  `bin/ssc-standard run --native tests/conformance/content-linked-namespaces.ssc`
  and `--bytecode` print the imported section title, then fail at
  `(copyVersion() + fallbackVersion()).toString`: VM reports `i->str: not Int`
  and ASM reports `expected Int, got 1234`.
- **Expected:** selected zero-argument `toString` uses dynamic method dispatch
  unless the receiver is proven Int; BigInt renders `1234` identically on
  native VM/direct ASM and deterministic `build-jvm`.
- **Plan/done-when:** retain the `i->str` fast path for literal Int receivers,
  route every unproven receiver through `__method__("toString", value)`, add a
  focused structural regression for Int/BigInt/Float/String, and make the full
  linked-content example exact without weakening runtime errors.
- **Verification:** stage-2 single/multi fixpoints, native-entry, focused
  VM/ASM/build-jvm output, affected conformance 17/17, and the 195-row strict
  corpus/parity sweep pass; runtime successes improve from 44 to 45.

## v21-coreir-curried-closure-underapplication — nested parameter lists fail at runtime
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: e23bff273
     confirmed: no -->

**Status:** fixed (2026-07-11, `e23bff273`), awaiting Sergiy confirmation; found by codex while advancing the installed
`algebraic-effects.ssc` VM/direct-ASM regression after removing hidden
multi-effect CPS.

- **Real-harness repro:** after `scripts/sbtc "installBin"`, both
  `bin/ssc-standard run --native examples/algebraic-effects.ssc` and its
  `--bytecode` twin reach `withLogger(println) { () => "done" }`, then fail
  `arity: 2 expected, 1 given`. Structural CoreIR correctly represents the
  source as nested applications of a two-argument closure; the runtime rejects
  the first, intentionally partial, application.
- **Expected:** the lowerer reconciles the source's nested parameter-list call
  with the known definition's flattened total arity before emitting CoreIR.
  VM/direct ASM retain exact closure arity; an invalid single-clause
  `required()` call must still fail rather than become a closure.
- **Plan/done-when:** pre-scan definition arities, flatten only a nested call
  whose combined arguments exactly satisfy a known definition, retain the
  existing under/over-arity negative gates, then require exact installed
  VM/ASM output and the affected conformance/release gates.
- **Root cause/fix:** the parser preserves parameter clauses as nested calls,
  while definitions use one flattened CoreIR lambda. The lowerer now pre-scans
  definition arities and combines only a known nested call whose total exactly
  satisfies that definition; strict runtime arity remains unchanged.
- **Verification:** installed VM/direct ASM complete all eleven
  `algebraic-effects.ssc` lines, native-entry retains under/over-arity failures,
  and the consolidated release gate plus fresh conformance 11/11 pass.

## v21-native-multi-effect-hidden-cps — declared operation gains a hidden argument
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 2a66b1221
     confirmed: no -->

**Status:** fixed (2026-07-11, `2a66b1221`), awaiting Sergiy confirmation; found by codex while running the installed
VM/direct-ASM regression for TI-8.2d2w2 standard effect runners.

- **Real-harness repro:** after `scripts/sbtc "installBin"`, run
  `bin/ssc-standard run --native examples/algebraic-effects.ssc` (and repeat
  with `--bytecode`). Both lanes print through the Logger/State section and
  then fail with `arity: 1 expected, 0 given` at `handle(program())`. Structural
  CoreIR shows source `def program(): Int ! NonDet` as `program = lam 1 ...`
  instead of a zero-argument function whose `NonDet.choose` calls produce
  portable `Op` values.
- **Root cause:** the pre-portable KV9 `blockHasMultiShotResolved` branch still
  rewrites functions containing a declared `multi effect` operation into a
  private list-specific CPS convention with a hidden continuation argument.
  This conflicts with the now-canonical `effect.perform` / `effect.handle`
  contract and makes ordinary source calls arity-invalid.
- **Plan/done-when:** remove the hidden CPS rewrite so multi-shotness comes only
  from reusable portable resume closures, add the exact `handle(program())`
  shape to the installed VM/ASM regression, and require both the focused effect
  smoke and the full release gates before closing the bug.
- **Fix/verification:** the KV9 hidden-parameter branch is removed; declared
  multi-effect operations use the same portable `Op` and reusable resume
  closures as ordinary effects. The focused VM/ASM regression, exact public
  example, consolidated release gate, and fresh conformance 11/11 all pass.

## v21-runtime-taxonomy-stale-after-front-fixes — reviewed blockers lag parity
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 05454dd1c
     confirmed: no -->

**Status:** fixed (2026-07-11, `05454dd1c`), awaiting Sergiy confirmation; found by codex while running the exhaustive
post-rebase release gate for pure native content binding.

- **Real-harness repro:** fresh `scripts/native-front-corpus` reports 57
  sentinels and `scripts/bc-parity-sweep --strict` reports 57 identical / 9
  both-fail / 129 skipped, but `scripts/v21-sentinel-taxonomy` first rejects the
  stale `agent-mcp-toolsource.ssc` override and `scripts/v21-runtime-taxonomy`
  then rejects the older 34-row manifest (including rows now identical).
- **Expected:** both reviewed taxonomies describe the same fresh corpus/parity
  reports, retain zero sentinel `standard-gap` rows, and remove/reclassify only
  rows proven by exact installed VM/ASM output.
- **Plan/done-when:** join every stale/unclassified row to its exact fresh
  output and owning feature, update overrides/manifests/ceilings atomically,
  then rerun corpus, strict parity, both taxonomy gates, and the consolidated
  release gate. This reconciliation is independent of `contentBind`, whose
  focused and distribution gates are green.

## v21-build-jvm-content-path-nondeterminism — content.bin leaks source roots
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 82d27b896
     confirmed: no
     gate: tests/e2e/v21-build-jvm-release-gate.sh -->

**Status:** fixed (2026-07-11, `82d27b896`), awaiting Sergiy confirmation; found by codex in the exhaustive post-effect
release gate after the structural content provider landed.

- **Real-harness repro:** `tests/e2e/v21-build-jvm-release-gate.sh` builds the
  same `argv.ssc`/`std-crypto.ssc` closure under sibling `a/src` and `b/src`
  directories, then fails the first `cmp`. Extracted JARs differ only in
  `META-INF/scalascript/content.bin`; its module `source` strings contain the
  two canonical temporary paths (and absolute staged-library paths).
- **Expected:** build-jvm is byte-reproducible and checkout/source-location
  independent. Embedded content keeps the same documents and module graph but
  uses the already-frozen stable `NativeSourceUnit.displayPath` identities.
- **Plan/done-when:** map content module sources and direct imports through the
  linked source-unit display table only during artifact packaging, then make
  the full build-jvm reproducibility gate and exhaustive release gate pass.

## v21-native-http-request-source-arity — canonical Request omits provider fields
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 794cb6e7c
     confirmed: no
     gate: tests/e2e/v21-native-entry-smoke.sh -->

**Status:** fixed (2026-07-11, `794cb6e7c`), awaiting Sergiy confirmation; found by codex after fixing extern-class layout
ownership and rerunning the installed native-entry gate.

- **Real-harness repro:** `tests/e2e/v21-native-entry-smoke.sh` reaches the HTTP
  server fixture but returns `500 native HTTP handler failed: match: no arm for
  Request/11`. The canonical `std.http.Request` declaration generates 9-field
  accessors, while the portable HTTP host intentionally appends `params` and
  `query` and registers the same 11-field order used by existing examples.
- **Expected:** `std.http.Request` is the authority for the provider value shape
  and includes `params` and `query`; `req.path`, route parameters, and query
  access work through the same 11-field contract on VM/direct ASM.
- **Plan/done-when:** add the two missing fields to the canonical case class,
  retain the provider's established order, make the installed server fixture
  exact again, and rerun native-entry, release, and fresh conformance gates.

## v21-native-extern-class-members-escape — abstract fields become parser sentinels
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: fd36ee87e
     confirmed: no -->

**Status:** fixed (2026-07-11, `fd36ee87e`), awaiting Sergiy confirmation; found by codex in the exhaustive post-effect
release gate after the content-provider main rebase.

- **Real-harness repro:** `scripts/v21-self-hosted-core-release-gate
  --skip-install` fails in the HTTP response provider fixture because imported
  `std/http.ssc` lowers `UploadedFile`'s abstract `name`, `filename`,
  `contentType`, `size`, `bytes`, and `path` fields into top-level cell writes
  from `_err`; the native entry rejects that structural CoreIR in both lanes.
- **Expected:** `extern class C:` owns and erases exactly its abstract member
  body. Members cannot leak into the module's top-level executable declaration
  stream, while the following Request/Response declarations remain visible.
- **Plan/done-when:** extend declaration-layout ownership to class headers,
  consume a braced/layout extern-class body atomically, add a focused imported
  VM/ASM regression around the existing HTTP fixture, and rerun the exhaustive
  gate before retiring any effect taxonomy row.

## v21-native-explicit-effect-handler-erasure — declarations and handlers disappear
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 4c0435f4b
     confirmed: no -->

**Status:** fixed (2026-07-11, `4c0435f4b`), awaiting Sergiy confirmation; found by codex while reducing the reviewed
`effects.ssc` standard-language/runtime blocker on the installed compiler-free
binary.

- **Real-harness repro:** after `scripts/sbtc "installBin"`, both
  `bin/ssc-standard run --native examples/effects.ssc` and its `--bytecode`
  lane exit 1 with `unbound global: greet` and no stdout. A smaller staged
  source shows two independent losses: layout `effect E:` handling can consume
  following top-level declarations, and `resolveE` recognizes
  `handle(computation)(handler)` only to return the computation and discard the
  parsed partial-function handler.
- **Expected:** an effect declaration owns exactly its abstract member body;
  each member performs the portable `E.member` operation; explicit handlers
  receive source arguments plus a reusable resume closure and implement
  one-shot, early-return, deep/nested, `Return`, and multi-shot semantics
  identically on native VM/direct ASM.
- **Plan/done-when:** add an installed-binary VM/ASM fixture covering layout
  ownership plus zero/one/many arguments and every handler mode; retain an
  `effect_decl` through imports; lower operations to `effect.perform` and both
  handle operands to `effect.handle`; then make the focused fixture and all six
  compatibility lines of `examples/effects.ssc` exact before running the full
  release gate. No provider, v1 bridge, transparent fallback, or backend branch
  is allowed.

## v21-native-content-markdown-error-swallowed — malformed roots become empty content
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: b6fe50ef2
     gate: tests/e2e/v21-self-hosted-markdown-frontend-smoke.sh -->

**Status:** done (2026-07-11, `b6fe50ef2`); found by codex in the consolidated
self-hosted release gate while verifying the new structural content projection.

- **Real-harness repro:** after `scripts/sbtc "installBin"`,
  `tests/e2e/v21-self-hosted-markdown-frontend-smoke.sh` reports
  `unterminated Markdown fence unexpectedly compiled`. The exact malformed
  root reaches `contentProjectModule`, whose default `contentDocument` branch
  replaces `MarkdownError(message, offset, line, column)` with an empty
  `DocumentContent` before `NativeV2Structural` can reject it.
- **Expected:** the canonical self-hosted `MarkdownError` crosses the projection
  boundary unchanged and becomes the established source-located compile error
  before native provider installation; no fallback document is fabricated.
- **Plan/done-when:** preserve `MarkdownError/4` in `NativeContentModule`, teach
  the Scala seed validator to rethrow its existing `source:line:column`
  diagnostic without reparsing, extend the structural regression test, then
  rerun the exact frontend smoke and consolidated quick release gate.
- **Root cause/fix prepared:** `contentDocument` intentionally had a defensive
  empty-document default, but `contentProjectModule` called it for every
  non-`MarkdownDocument`, including the canonical error ADT. Commit
  `b6fe50ef2` now preserves `MarkdownError/4`; the seed converts it back to the
  established source-located failure. Structural tests are 8/8 and both the
  exact Markdown frontend repro and native content e2e pass after the final
  rebase; affected conformance is 16/16, and the fix is on `origin/main`.

## tkv2-pwa-stale-default-backend — expected output pins retired JDK default
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: b060951ce
     gate: tests/conformance/run.sh -->

**Status:** done (2026-07-11, `b060951ce`); found by codex while running the mandatory
`tkv2-*` landing gate for `v2-swiftui-xcode-project`.

- **Real-harness repro:** after a fresh `scripts/sbtc installBin`, run
  `tests/conformance/run.sh --only 'tkv2-*' --no-memo`. Eleven cases pass, but
  `tkv2-pwa` fails only on line 2: expected `  (backend=jdk)`, got
  `  (backend=fast)`; all eight functional PWA assertions remain `true`.
- **Root cause:** `HttpServerBackends` now deliberately prefers the
  installed `fast` provider when no backend is selected, while the corpus
  expected file still pins the former JDK default. The fixture tests PWA
  manifest/service-worker behavior, not transport selection.
- **Expected/fix:** update the real-harness expected banner to the current
  deterministic default, rerun the isolated case and full `tkv2-*` gate, and
  report the result in Rozum. Do not weaken or filter the remaining output.
- **Fix/result:** only the obsolete expected banner changed. Isolated
  `tkv2-pwa` passed 1/1 and the complete no-memo `tkv2-*` corpus passed 12/12
  after the final rebase; all eight functional PWA assertions remain exact.

## v21-runtime-taxonomy-stale-http-mount — resolved standard row still blocks freeze
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 77da8e8e2 -->

**Status:** done (2026-07-11, taxonomy `77da8e8e2`); found by codex while
joining the fresh 195-row typeclass release report to the reviewed runtime
taxonomy. Provider implementation already landed in `608d63425`.

- **Real-harness repro:** fresh strict parity classifies
  `derived-route-clients.ssc` as `identical` with VM/ASM exit 0, and direct
  installed `ssc-standard run --native` plus `--bytecode` both exit 0 with
  identical empty output/stderr. `scripts/v21-runtime-taxonomy` nevertheless
  fails `stale or reclassified manifest row: derived-route-clients.ssc` because
  the manifest still says standard HTTP `mount` is missing.
- **Expected:** completed standard-provider rows leave the reviewed blocker
  manifest and tighten category/total ceilings; the core-only no-provider
  corpus diagnostic remains visible but cannot override the plugin-enabled
  standard parity contract.
- **Fix/result:** the stale row alone was removed after exact installed VM/ASM
  exit/output evidence. Standard-provider, blocker, and total ceilings tightened
  to 13, 23, and 35; the real taxonomy and exhaustive compiler-free release
  gate pass. No HTTP implementation changed, and the core-only no-provider
  diagnostic remains visible in the native-front report.

## v2-native-table-urlprotocol-harness-race — strict action probe mutates shared Set concurrently
<!-- status: fixed
     lane: apparatus
     area: cli
     fixed-in: 400931f68 -->

**Status:** done (2026-07-11, `400931f68`); found by codex in the mandatory
post-rebase six-test rerun and confirmed fixed by `nativeui-reviewer` in the
`scalascript` Rozum room.

- **Real-harness repro:** run
  `scripts/sbtc 'v2SwiftBackend/testOnly ssc.swift.SwiftBackendTest -- -z "native table"'`.
  The generated action probe can exit 134 after `actions:edit` with
  `NSInvalidArgumentException` in `Set.contains`: `TableURLProtocol.stopLoading`
  inserts into the static `stopped` Set on a URLSession callback while the main
  actor calls `wasStopped` without synchronization. Static `instances`, request
  reads, and response lookup are likewise unsynchronized.
- **Expected:** the controllable URLProtocol probe is data-race-free under
  strict Swift 6; cancellation polling and synthetic response delivery observe
  coherent snapshots and never crash independently of Store behavior.
- **Plan/done-when:** guard all `TableURLProtocol` shared state with one lock,
  copy the selected instance/request under that lock before doing callback/body
  work, then pass the action probe repeatedly, all six named tests, and the full
  40-test backend suite. Ask `nativeui-reviewer` to confirm the harness-only
  root cause in Rozum before closing.
- **Fix/verification:** one `NSLock` now owns `instances` and `stopped` plus
  every lookup; body stream reads and URLProtocol client callbacks happen after
  the selected value is copied outside the critical section. The reviewer
  confirmed production sources are unchanged and approved action 5/5, named
  6/6, and full backend 40/40.

## v2-swiftui-ios16-onchange-availability — generated renderer requires iOS 17 accidentally
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: d54d02126 -->

**Status:** done (2026-07-11, `d54d02126`); found by codex in the real iOS
Simulator strict typecheck and confirmed fixed by `nativeui-reviewer` in the
`scalascript` Rozum room.

- **Real-harness repro:** typecheck all generated AppCore/AppleApp sources with
  `xcrun swiftc -target arm64-apple-ios16.0-simulator` against the installed
  Simulator SDK. The two-argument `onChange(of:) { old, new in ... }` overload
  in keyed rendering and editable table cells is iOS 17-only.
- **Expected:** the frozen iOS 16 deployment compiles without availability
  annotations or runtime branching; both observers use the compatible
  one-argument overload.
- **Plan/done-when:** update both generated call sites, retain their behavior,
  and pass the exact strict iOS Simulator typecheck in the sixth named table
  gate plus the full Swift backend suite. The one-argument overload compiles on
  iOS 16 but is deprecated under the installed macOS 14+ SDK and therefore also
  fails warnings-as-errors; use deployment-compatible `task(id:)` observation
  instead of choosing either incompatible `onChange` overload.
- **Fix/verification:** both generated observers use `task(id:)`. The sixth
  named table gate typechecks the complete generated Apple source set for the
  installed iOS 16 Simulator target under strict Swift 6; the full Swift backend
  is 40/40.

## v2-native-table-request-url-untested — Swift URL resolver and CLI routing lack executable coverage
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 1ecbc80ca -->

**Status:** done (2026-07-11, `1ecbc80ca`); reported and confirmed by
`nativeui-reviewer` in the `scalascript` Rozum room after executable review.

- **Real-harness repro:** current JVM generator tests normalize/embed a base and
  CLI tests call `SwiftV2Cli.emit` directly, but no generated Swift execution
  resolves absolute, root-relative, and base-relative request URLs or rejects
  scheme-relative/credential/fragment/hostless forms. No application command
  proves `--server-url` reaches the emitted Store configuration.
- **Expected:** executable Swift uses the sole normalized Apple base exactly as
  specified, and the real public command route threads the same value into the
  generated package.
- **Plan/done-when:** execute the resolver matrix through generated strict Swift
  plus controllable URLProtocol and invoke the actual CLI command path with
  `--server-url`; pin accepted URLs, rejected forms, and emitted configuration.
- **Fix/verification:** generated Swift executes the accepted/rejected URL
  matrix and the real `BuildCmd` threads normalized `--server-url` into Store
  configuration. Swift 34/34 and CLI 6/6 passed before the reviewer APPROVE.

## v2-native-table-rowkey-adapter-drop — non-default row identity is silently lost outside Swift
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 1ecbc80ca -->

**Status:** done (2026-07-11, `1ecbc80ca`); reported and confirmed by
`nativeui-reviewer` in the `scalascript` Rozum room after three review rounds.

- **Real-harness repro:** compile `dataTableView(..., rowKeyPath = "meta.key")`
  through every adapter. JS emits a DOM attribute that its mount runtime never
  reads, while Rust/TUI accept the fourth argument as `_row_key_path` and ignore
  it, so the target-independent public selection has no effect.
- **Expected:** every adapter preserves and consumes the exact dotted row key,
  or deterministically rejects a target that cannot implement it; silent
  fallback to `id`, index, or object identity is forbidden.
- **Plan/done-when:** make JS mount and Rust/TUI runtime consume strict row
  identity, add non-default/missing/empty/compound/duplicate adapter gates, and
  obtain the implementation reviewer's confirmation.
- **Review round 2:** JS must reject non-object rows rather than accepting an
  array via a numeric dotted segment. TUI/Rust must execute the complete
  missing/empty/compound/duplicate runtime matrix, not rely on compile smoke.
- **Fix/verification:** JS mount consumes the typed canonical identity and
  rejects arrays/missing/empty/compound/duplicates; TUI consumes the selected
  path and generic Rust rejects unsupported tables explicitly. JS 52/52, TUI
  35/35, and Rust 261/261 include executable invalid-key matrices.

## v21-functional-vm-asm-mkstring-parity — functional demo diverges on final dispatch
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 4c5254eed -->

**Status:** done (2026-07-11, `4c5254eed`); found and confirmed by codex
during the post-`PMapped` full strict parity sweep.

- **Real-harness repro:** `SSC=bin/ssc-standard scripts/bc-parity-sweep
  --strict` reports the only mismatch at `functional.ssc`. Both lanes exit 0
  and agree through `440`; the native VM prints
  `Op("Stub.mkString", ", ", <closure>)`, while direct ASM prints `Stub`.
- **Expected:** portable functional dispatch either produces the same real
  value on both lanes or fails identically under an owned runtime blocker;
  strict parity must have zero mismatch/one-sided rows.
- **Root cause:** self-hosted CoreIR lowers block-form
  `xs.foldLeft(z) { (acc, x) => ... }` as
  `App(__method__("foldLeft", xs, z), lambda)`. Portable method dispatch only
  accepts both `z` and `lambda` in one call, so the first application fabricates
  an unresolved `Op`; VM later exposes `Op("Stub.mkString", ...)`, while ASM
  collapses the same path to `Stub`.
- **Fix/result:** one-argument `foldLeft(z)` now returns a portable arity-one
  closure which completes the existing effect-aware fold when applied. The
  exact list/array fixture prints `1, 3, 6, 10, 15` and `10`; the functional
  example is canonical and byte-identical. Full parity is 26/0/40/129 with zero
  mismatch/one-sided rows; runtime blockers fall to 28.

## fast-http-session-cookie — successful setSession response loses Set-Cookie
<!-- status: fixed
     lane: apparatus
     area: cli
     fixed-in: d202d2abf -->

**Status:** fixed (2026-07-11); reported and confirmed by busi immediately
after pinning the hf-7 `--v2` fast backend. Fix commit: `d202d2abf`.

- **Real-harness repro:** assemble `bin/ssc`, boot busi's `src/v2/http/hub.ssc`
  on `--v2`, and submit the displayed code to `POST /pair`. The response is 200
  HTML but has no `Set-Cookie`; the cookie jar stays empty and the next
  `GET /api/vault` returns `{"error":"unpaired"}`. The same flow passed on the
  previous JDK transport.
- **Expected:** the correct pairing code reaches `req.form`; the resulting
  response's explicit `busi_device` cookie reaches the wire. Generic cookies,
  signed sessions, form/multipart fields and auth match the JDK SPI backend.
- **Impact:** every passwordless pairing/session flow is unusable on the new
  default `--v2` transport despite an apparently successful login response.
- **Root cause:** `FastServerBackend.toPojo` copied body/query/cookies but left
  `Request.form`, signed `session`, bearer/basic auth, JWT claims and files at
  defaults, bypassing the shared `RequestBuilder` used by the JDK backend. The
  correct code was therefore handled as an invalid form and no cookie response
  was created; `RawResponse` header serialization was not the fault.
- **Plan/done-when:** add a raw-input shared-builder path, reproduce through a
  real fast socket, run module/assembled/conformance gates, and obtain reporter
  confirmation from busi Vault plus canonical browser E2E.
- **Verification:** common 150/150, fast backend 5/5, interpreter-server 58/58,
  `rest-validate` INT/JS/JVM, assembled paired Vault 11-step/restart/leakage
  check, and canonical busi fast-backend Chromium 6/6 in 1.9 minutes.

## v2-swiftui-persisted-stale-wrapper-disposal — disposed wrapper can write disk or crash
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 0ade8bf7c -->

**Status:** done (2026-07-11, `0ade8bf7c`); reported and confirmed fixed by `nativeui-reviewer` in the
`scalascript` Rozum review of the persisted/online Apple slice.

- **Real-harness repro:** retain a scoped persisted signal wrapper, dispose its
  owner/scope, then call the old native `set` closure. It still captures the
  tombstone cell strongly and stages UserDefaults through the live Host. Retain
  it past Host/Store deinit and the persisted `afterWrite` force-unwraps weak
  `self`, which can crash.
- **Expected:** every wrapper mutation authenticates the exact current live
  Host cell; disposed/replaced wrappers fail deterministically and never touch
  disk. A retained closure after root disposal fails without a crash. A fresh
  reinserted wrapper works normally.
- **Plan/done-when:** weakly guard Host plus `signals[key] === cell` before any
  mutation/side effect, remove force unwraps from persisted callbacks, and gate
  committed write, scope deletion, stale-old versus fresh-reinsert behavior,
  and invocation after Store/session deinit.
- **Root cause/fix:** generated wrappers trusted their captured cell forever and
  persisted callbacks force-unwrapped a weak Host. Reads/writes now require the
  exact current Host cell; disposed, replaced, and post-deinit closures fail
  without mutation, while a fresh reinserted wrapper commits normally.

## v2-swiftui-online-component-scope-split — onlineSignal is not process-wide
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 0ade8bf7c -->

**Status:** done (2026-07-11, `0ade8bf7c`); reported and confirmed fixed by `nativeui-reviewer` in the
`scalascript` Rozum review of the persisted/online Apple slice.

- **Real-harness repro:** call `onlineSignal()` from two component/keyed scopes.
  Host creates two scope-local `__online__` cells. After the first owner receives
  `false`, a later owner of the second copy starts at default `true`; because the
  monitor is already active it receives no immediate replay and stays wrong until
  the next path transition.
- **Expected:** `onlineSignal` is one process/root-scoped signal, matching the
  frozen spec and JS/JVM singleton behavior; every owner observes current state,
  and only the last exact token cancels the single monitor.
- **Plan/done-when:** force online construction to the root signal key (or an
  equivalent singleton identity) and gate two component/keyed owners, late-owner
  current-value visibility, one monitor, and exact last-owner cancellation.
- **Root cause/fix:** online creation inherited `scopes.last`. It now explicitly
  uses the root key/cell, so component/keyed wrappers share current state and one
  target monitor.

## v2-swiftui-persisted-cell-dependent-journal — persisted writes can miss UserDefaults
<!-- status: fixed
     lane: apparatus
     area: other
     fixed-in: 0ade8bf7c -->

**Status:** done (2026-07-11, `0ade8bf7c`); reported and confirmed fixed by `nativeui-reviewer` in the
`scalascript` Rozum review of the persisted/online Apple slice.

- **Real-harness repro:** the strict generated-Swift platform-signals probe
  currently materializes `store.cell(for: persisted)` before writing. A live
  persisted signal written by a retained AppCore closure without that cell, or
  during successful root evaluation before `built.observe`, updates Host memory
  but not `UserDefaults`.
- **Expected:** every successful committed persisted String write reaches the
  configured defaults suite, independent of renderer/cell materialization;
  root-evaluation failure and keyed rollback never escape, and disposal does
  not drop an already committed write.
- **Plan/done-when:** add a Host-owned commit journal/callback independent of
  Store cells, flush only at successful root/keyed commit, and executable gates
  for no-cell post-init, successful/failed root evaluation, failed keyed
  rollback, and committed disposal.
- **Root cause/fix:** Store persistence looked up `cells[key]` after Host writes.
  A Host-owned String journal now flushes at successful root/outer keyed commit,
  restores on abort/rollback, and writes independently of rendered cells.

## v2-swiftui-online-stale-monitor-generation — cancelled callback can mutate a restarted monitor
<!-- status: fixed
     lane: apparatus
     area: other
     fixed-in: 0ade8bf7c -->

**Status:** done (2026-07-11, `0ade8bf7c`); reported and confirmed fixed by `nativeui-reviewer` in the
`scalascript` Rozum review of the persisted/online Apple slice.

- **Real-harness repro:** capture the old monitor callback, unsubscribe the last
  token, subscribe again, then deliver the captured callback. The Store has no
  monitor identity/generation guard, so the old callback sees the newly live
  online signal set and mutates the restarted generation.
- **Expected:** only the callback belonging to the current monitor generation
  may publish. A callback queued before cancel is inert after cancel/restart.
- **Plan/done-when:** bind callbacks to an opaque generation/token, invalidate it
  before cancel, and gate stale-versus-current delivery through the strict Swift
  fake monitor.
- **Root cause/fix:** callbacks had no identity. Every monitor start now owns a
  UUID token invalidated before cancel; only the current token may publish.

## v2-swiftui-persisted-wrong-type-corruption — rejected write can corrupt Host memory
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 0ade8bf7c
     gate: tests/e2e/v21-native-entry-smoke.sh -->

**Status:** done (2026-07-11, `0ade8bf7c`); reported and confirmed fixed by `nativeui-reviewer` in the
`scalascript` Rozum review of the persisted/online Apple slice.

- **Real-harness repro:** call the public persisted signal `set` closure with a
  non-String. Generic write currently assigns `current`/`dirty` before the
  persisted `afterWrite` type check throws, leaving a non-String in memory while
  disk/onWrite did not commit.
- **Expected:** rejected writes are atomic: the live value and defaults remain
  the prior String and the error is deterministic.
- **Plan/done-when:** prevalidate persisted values or restore the complete cell
  snapshot on `afterWrite` failure; add a real generated-Swift wrapper-set gate.
- **Root cause/fix:** generic writes mutated `current` before persisted String
  validation. The wrapper now prevalidates and restores its cell snapshot on any
  side-effect failure, leaving memory and defaults unchanged.

## v21-storage-container-print-gates — release fixtures expect obsolete quoted children
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: befc249d4
     gate: tests/e2e/v21-native-entry-smoke.sh -->

**Status:** done (2026-07-11, `befc249d4`; release confirmation
`d503cf856`); found and confirmed by codex in the mandatory post-rebase
native-entry run after K62.22 (`d1a7b5451`) intentionally aligned native
container printing with the parity renderer.

- **Real-harness repro:** `tests/e2e/v21-native-entry-smoke.sh` fails on
  `storage-demo.ssc`: actual `Some(alice)` / `List(user, role)` versus stale
  expected `Some("alice")` / `List("user", "role")`. The conformance golden
  `tests/conformance/expected/storage.txt` already owns the unquoted form.
- **Expected:** native-entry, native-provider class-load, and standalone
  build-jvm storage fixtures assert the current user-visible parity renderer.
- **Plan/done-when:** update all three stale exact/grep expectations and the
  storage feature spec, retain the value/order checks, then rerun native-entry,
  plugin-boundary, build-jvm smoke/release, slim/JRE, and conformance gates.
- **Fix/result:** the three assembled gates now assert `Some(alice)` and
  `List(user, role)`, matching conformance and
  `specs/v2.1-native-storage-effect.md`. Native-entry, provider boundary,
  build-jvm smoke/release, slim, JRE, and fresh conformance all pass.

## v21-http-fast-standard-tier-cutover — standard image lost its HTTP provider
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: d503cf856
     gate: tests/e2e/v21-native-plugin-boundary-smoke.sh -->

**Status:** done (2026-07-11, `d503cf856`); found and confirmed by codex while
running the v2.1 release gates after the HTTP-fast default swap (`67158c185`).

- **Real-harness repro:** after `scripts/sbtc "installBin"`,
  `tests/e2e/v21-native-plugin-boundary-smoke.sh` reports a missing staged
  `scalascript-v2-native-http-plugin`; `tests/e2e/v21-core-dependency-gate-smoke.sh`
  expects the same retired JAR. `tests/e2e/v21-slim-distribution-gate.sh` and
  `tests/e2e/v21-jre-module-gate.sh` both fail with `unhandled runtime effect:
  Response.text` because `http-fast-plugin` is present in tools `jars/` but not
  standard `jars/`. After the fast provider became runnable in the tools image,
  `tests/e2e/v21-native-entry-smoke.sh` also reaches its stale
  `http-server-feature-unavailable.ssc` assertion: `useGzip` now exits 0, while
  the gate still requires the retired "native HTTP server unavailable" error.
- **Expected:** the default HTTP-fast provider is staged into both tools and
  standard images; boundary/dependency gates discover its new artifact name;
  slim and module-limited HTTP response fixtures pass without the retired
  provider.
- **Plan/done-when:** update standard staging and all provider/dependency gate
  ownership from `http-plugin` to `http-fast-plugin`, replace the obsolete
  feature-unavailable assertion with a positive fast-provider feature check,
  retain the forbidden dependency/class-load scans, and rerun native provider,
  core dependency, slim, JRE, standard, build-jvm, native-entry, and
  conformance gates.
- **Root cause/fix:** the hf-5 module replacement updated the CLI dependency
  graph but left explicit standard/build-jvm allowlists and artifact-discovery
  globs on the removed JAR name. Those surfaces now own the fast provider plus
  its non-provider engine; VM/ASM positively execute `useGzip()`. All focused
  gates and the quick consolidated self-hosted-core release gate pass.

## v21-yaml-unit-global — native layout parser emits an unbound `Unit` value
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: aef599a80 -->

**Status:** done (2026-07-11, `aef599a80`); found and confirmed by codex after symbolic extension dispatch
advanced `dsl-yaml-like.ssc` beyond the former numeric `PChar(10)` failure.

- **Real-harness repro:** `bin/ssc-standard run --native
  examples/dsl-yaml-like.ssc` fails identically on VM/ASM with `unbound global:
  Unit` after the imported parser `|` extension is selected correctly.
- **Expected:** source-level unit types/literals used by the imported layout
  parser lower to the portable unit value and never become a value-level global
  named `Unit`.
- **Root cause:** `parseMatchArm` reuses the general `skipTypeAnnot`, whose
  depth-zero stop set does not contain `=>` or a guard `if`. For
  `case ic: IndentContext => ic.currentLevel`, it consumes the arrow and body up
  to `}`; `parseArmBody` then sees no statements and synthesizes `uid Unit`.
- **Plan/done-when:** isolate the owning imported declaration in a multi-file
  typed-pattern fixture, specify a pattern-specific type boundary that preserves
  nested delimiters but stops at `=>`/guard, eliminate the false `Unit` global,
  and rerun the YAML-like example plus release gates.
- **Root cause/fix:** the general scanner intentionally accepts function arrows;
  a dedicated pattern scanner now stops at depth-zero `if`/`=>` while preserving
  nested delimiters. The exact import fixture passes VM/ASM, and YAML advances
  identically to the separately tracked parser-context arity gap.

## v21-yaml-parser-context-arity — YAML parser calls a nullary value with one argument
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 878474b8d -->

**Status:** done (2026-07-11, `878474b8d`); found and confirmed by codex after the typed-pattern boundary
fix advanced `dsl-yaml-like.ssc` beyond the false `Unit` global.

- **Real-harness repro:** `bin/ssc-standard run --native
  examples/dsl-yaml-like.ssc` and the same command with `--bytecode` now fail
  identically with `arity: 0 expected, 1 given` after the imported layout
  parser enters its first typed arm.
- **Expected:** the imported parser/context operation selects the intended
  callable definition and completes identically on VM/ASM.
- **Root cause:** assembled CoreIR proves `IndentContext_at` is correctly
  `lam 1`; the first failing call is `seqItem.block`. The extension starts with
  receiver `p`; `withIndent(n)` lowers as `lam 2` and `sameIndent` as `lam 1`,
  but the nested layout close after `sameIndent` clears
  `extensionParamsCell`. Later receiver-only members `deeperIndent`, `block`,
  and `line` therefore lower as `lam 0`, retain unbound `(global p)` bodies,
  and are invoked with one receiver argument.
- **Plan/done-when:** identify the exact callee and declaration ownership from
  the assembled CoreIR, add an import-boundary regression whose earlier member
  has a nested layout body and whose later receiver-only/parameterized members
  retain the receiver, distinguish the extension's real dedent from nested
  virtual closes, and rerun all parser DSLs plus release gates.
- **Fix/result:** extension-specific layout/brace frames now own the only token
  that clears receiver state; nested `parseOneStmt` calls cannot clear it.
  Layout/braced multi-file output is exact on VM/ASM, and YAML advances
  identically to the already tracked `PMapped/2` gap.

## v21-case-object-no-context-unbound — native frontend drops `case object`
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 500ba1668 -->

**Status:** done (2026-07-11, `500ba1668`, taxonomy `9411ebf0e`); found and
confirmed by codex after imported extension dispatch advanced
`dsl-json-parser.ssc` beyond the `PRegex/1` failure.

- **Real-harness repro:** `bin/ssc-standard run examples/dsl-json-parser.ssc`
  fails identically on VM/ASM with `unbound global: NoContext`; the declaration
  is `case object NoContext extends ParserContext` in `std/parsing/core.ssc`.
- **Expected:** a nullary case object lowers to one stable constructor value and
  is usable as the default parser context without a host provider.
- **Plan/done-when:** specify native `case object` parsing/lowering, add an
  isolated import-boundary VM/ASM regression, and rerun the JSON/YAML parser
  examples plus release gates.
- **Root cause/fix:** the top-level `case` branch recognized only `case class`,
  so `case object` was parsed as an expression and never entered the imported
  declaration closure. An explicit `caseobj` AST tag now survives module
  filtering and lowers to one `IrCtor(Name, Nil)` value definition.
- **Verified:** imported value/alias/pattern/equality print
  `Empty/empty/true` on VM/ASM; calculator becomes identical, JSON advances to
  the separately tracked `PMapped/2` gap, YAML remains at `Unit`. Every release
  gate and fresh conformance 11/11 pass.

## v21-symbolic-extension-infix-precedence — `Parser.|` becomes numeric `i.or`
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 4a336ddec -->

**Status:** done (2026-07-11, `4a336ddec`); found and confirmed by codex after
imported extension dispatch advanced calculator/YAML-like parser examples
beyond `PRegex/1`.

- **Real-harness repro:** `bin/ssc-standard run examples/dsl-calc-parser.ssc`
  and `dsl-yaml-like.ssc` fail identically on VM/ASM with `expected Int, got
  PChar(42)` / `PChar(10)`. Their `Parser[A] | Parser[A]` calls lower through
  the hard-coded numeric `i.or` path instead of imported extension `|`.
- **Expected:** a registered symbolic extension method on the receiver wins
  over primitive numeric lowering; ordinary integer bitwise OR stays `i.or`.
- **Plan/done-when:** make infix resolution consult durable extension identity
  before primitive dispatch, cover Parser-like and Int receivers on VM/ASM, and
  rerun both DSLs plus release gates.
- **Root cause/fix:** the self-hosted lowerer hard-coded `|` to `i.or` before
  consulting its durable extension registry. Registered `|` now carries its
  exact closure through `__arithExt__`; only `IntV/IntV` keeps primitive OR.
- **Verified:** the imported two-file fixture prints `a|b`, `a|b|c`, `7` on
  VM/ASM; a no-extension String misuse fails honestly; calculator/YAML advance
  to separately tracked `NoContext`/`Unit` gaps. Full release gates and fresh
  conformance 11/11 pass.

## v21-match-pregex-constructor — extension body captures the following top-level def
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: f7ff66a1f -->

**Status:** done (2026-07-11, `f7ff66a1f`, taxonomy `4feb715ea`);
found and confirmed by codex after the layout-object fix advanced all three
parser-combinator examples past their missing owned members.

- **Real-harness repro:** run
  `bin/ssc-standard run examples/dsl-calc-parser.ssc` after the
  `v21-layout-object-members-unprefixed` fix. VM and direct ASM both fail with
  `ssc: match: no arm for PRegex/1`; `dsl-json-parser.ssc` and
  `dsl-yaml-like.ssc` reach the same failure.
- **Expected:** the dedent/code-block boundary after an indented `extension`
  closes its member group. The following top-level `def runParser` has exactly
  its four declared parameters, so its existing `PRegex(pattern)` arm matches.
- **Root cause:** constructor metadata and the emitted `arm PRegex 1` are
  correct. The native parser represents an extension group as mutable
  `extensionParamsCell` state and currently keeps that state across the
  physical dedent/code-block boundary when the next statement is another
  `def`. It therefore prepends the stale receiver `p` to `runParser`, emitting
  `lam 5`; four-argument calls shift the scrutinee and surface the misleading
  match failure. After closing the layout boundary, `runParser` correctly
  becomes `lam 4`, exposing the companion cross-module defect: extension method
  names live only in the parser's transient `extensionMethodsCell`. Per-module
  parsing resets that cell before the combined module closure is lowered, so
  imported `Parser.map` becomes the built-in `_sel_map(PRegex, ...)`; that
  list/option helper has no `PRegex` arm and produces the same diagnostic.
- **Plan/done-when:** give an indented extension declaration a real layout
  boundary and persist extension start/end ownership in the parsed AST so the
  combined lowerer reconstructs imported extension dispatch deterministically.
  Clear receiver state at virtual close, preserve all members inside the body,
  and verify a following top-level function's arity. Add a multi-file VM/ASM
  regression and rerun all three examples; keep any later independent failures
  separately classified.
- **Resolution:** contextual receiver delimiters now open/close a virtual
  extension body, and explicit AST markers preserve imported member identity
  through module filtering into the combined lowerer. `runParser` is `lam 4`,
  imported `map` is `(global map)`, the two-file fixture is exact on VM/ASM,
  and every release gate plus fresh conformance 11/11 passes. All three DSLs
  leave `PRegex/1`; their symbolic-infix and `case object` gaps are tracked
  separately above.

## v21-layout-object-members-unprefixed — colon object loses its first member and owner prefix
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: afe902ec8 -->

**Status:** done (2026-07-11, `afe902ec8`, property completion
`b703a6bf0`); found and confirmed by codex while selecting the next
toolchain-independence runtime blocker after core-free YAML.

- **Real-harness repro:** run
  `bin/ssc-standard run examples/dsl-calc-parser.ssc`. The assembled native
  route fails with `unbound global: Parser_regex`. Its emitted CoreIR contains
  `PRegex` and unprefixed `regex`, but no `Parser_regex`; `Parser.char` also
  points at missing `Parser_char`.
- **Expected:** `object Parser:` owns all contiguous indented members, emits
  `Parser_<member>` definitions, and runs identically on native VM/direct ASM.
- **Root cause:** the layout pass recognizes a trailing colon only while inside
  a `trait` header. For `object Parser:` it emits no virtual braces;
  `skipToBrace` consumes the first member and the remaining definitions are
  parsed as unrelated top-level declarations, while selector lowering still
  treats `Parser` as a known object.
- **Plan/done-when:** make colon layout opening declaration-contextual for
  object headers without treating ordinary type-ascription colons as blocks;
  add focused braced/layout VM/ASM coverage, rerun the three parser-combinator
  rows, then update taxonomy only for examples that fully complete. Require
  native-entry, corpus/parity/taxonomy, and fresh affected conformance gates.
- **Resolution:** object and trait headers now share contextual layout state;
  owned methods and parameterless properties lower under one prefix for both
  UID selectors and sibling references. The exact layout/braced fixture passes
  VM/direct ASM, all three real DSLs leave the missing-global boundary, and all
  release gates plus fresh conformance 11/11 pass. Their next independent
  `PRegex/1` failure is tracked above.

## v2-swiftui-unsourced-malformed-seams — malformed nodes and events lose site provenance
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 70bee065d
     confirmed: no -->

**Status:** fixed (2026-07-11, `70bee065d`), awaiting Sergiy confirmation; found by `nativeui-reviewer` in the third
read-only SwiftUI store/renderer review in Rozum.

- **Real-harness repro:** render malformed `NativeUiElement` attrs/events/
  children or `NativeUiForKeyed`, dispatch a malformed/unsupported ordinary
  event, or pass a non-boolean `aria-disabled`/`required`. Several paths omit
  the available site source, while semantic booleans silently become false.
- **Expected:** every malformed/unsupported diagnostic names the owning lexical
  site; semantic boolean attributes accept only their frozen value forms.
- **Plan/done-when:** pass site/source through the renderer and action seams,
  validate semantic booleans before modifiers run, and add executable exact-
  source negative gates for element, keyed, event, aria-disabled, and required.
  Fourth review found the remaining shapes: invalid `aria-modal` must also be
  rejected, and every `NativeUiEvent` kind must validate its target/payload
  before mutation so increment-on-non-Int and malformed set/input/toggle cannot
  silently no-op or surface an unsourced runtime failure.
  Fifth review narrowed the remaining event shape to field 3: metadata must be
  a portable Map (as every constructor emits), and the target must be the full
  six-field `NativeUiSignal`, not merely a matching tag/kind string.
  Sixth review adds the adversarial boundary: signal kind must be one of the
  eight frozen values and every event metadata key must be String.
  Seventh review leaves one final full-shape case: field 5 must match its kind
  (`mutable` String-key Map; exact `NativeUiSignalMeta*` tag/arity for seed,
  computed, equality, hash, fetch, online, and persisted).
  Eighth review requires typed nested fields too: seed/equality sources and the
  fetch refresh/headers/phase/error fields are valid signals; fetch URL is
  String or signal. Recursive validation is cycle-safe by `SscFields` identity.

## v2-swift-session-sticky-callback-failure — one caught render error poisons the retained runtime
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 70bee065d
     confirmed: no -->

**Status:** fixed (2026-07-11, `70bee065d`), awaiting Sergiy confirmation; found by codex in the executable Swift keyed
rollback probe and announced to `@scalascript` /
`@nativeui-reviewer` in Rozum.

- **Real-harness repro:** invoke a keyed render closure that throws after a
  previously committed owner tree, catch the error, then reconcile the prior
  clean item set through the same `NativeUiSession`. Host signal/owner state
  rolls back, but `Machine.failure` remains sticky and the clean call rethrows
  the old error.
- **Expected:** failure is sticky across every nested subterm within initial
  program evaluation or one callback invocation, so placeholder Unit is never
  consumed. Once a post-evaluation caller catches that callback failure, the
  retained session is reusable and the last committed UI tree remains valid.
- **Plan/done-when:** distinguish initial evaluation from retained callback
  boundaries; consume/clear the failure only while returning it from a
  post-evaluation `invokeResult`/host-bound callback. Pin nested short-circuit
  plus same-session keyed rollback/recovery under real Swift.

## v2-swiftui-dependent-double-publish — one dependency write advances a computed cell twice
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 70bee065d
     confirmed: no -->

**Status:** fixed (2026-07-11, `70bee065d`), awaiting Sergiy confirmation; found by codex while implementing the generated
SwiftUI observation store, announced to `@scalascript` /
`@nativeui-reviewer` in Rozum.

- **Real-harness repro:** generate the NativeUi Apple sources, mount stable cells
  for a mutable signal and a computed/equality signal that reads it, then write
  one semantically different value. The draft `NativeUiStore.publish` calls
  `changed()` on the dependent and recursively publishes that same dependent,
  so its revision advances twice for one source transition.
- **Expected:** the source and each transitively dependent signal publish at
  most once per write transaction; a semantic-equal write publishes nothing,
  and cycles are bounded by the visited set.
- **Plan/done-when:** centralize the revision increment in one graph traversal,
  add a real generated-Swift runtime probe covering stable cell identity,
  semantic-equal suppression, direct/transitive invalidation, opaque
  subscribe/unsubscribe tokens, and obtain independent Rozum approval before
  landing the store slice.

## v2-swift-nativeui-descriptor-proof — debug root summary hides ABI field drift
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 9ef73ac81 -->

**Status:** fixed (2026-07-11, `9ef73ac81`); found by `nativeui-reviewer` in Rozum during the
uncommitted Swift AppCore ABI-v1 review.

- **Real-harness repro:** `v2SwiftBackend/test` executes real generated packages,
  but `nativeUiDebug` prints only root version/tag/operation. A wrong descriptor
  field/default/source can therefore pass every current Swift assertion.
- **Expected/fix:** add a deterministic structural ABI digest/test seam and real
  Swift programs that pin shortened columns, fetch defaults, POST/id delete,
  raw sentinel, mobile CSS, and source provenance without flattening closures.
- **Done-when:** exact descriptor fields/defaults and source refs are asserted by
  real Swift execution and the reviewer approves; keep `fixed` until Sergiy confirms.
- **Fix/verified:** real AppCore probes inspect the exact table source, shortened
  column/options, POST/id delete, post request/payload, unsupported provenance,
  and trusted HTML; the reviewer approved the final diff.

## v2-swift-nativeui-duplicate-root-source — diagnostic omits both source refs
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 9ef73ac81 -->

**Status:** fixed (2026-07-11, `9ef73ac81`); found by `nativeui-reviewer` in Rozum during the
uncommitted Swift AppCore ABI-v1 review.

- **Real-harness repro:** register `emit` and then `serve`; `registerRoot` stores
  both `NativeUiSourceRef` values but the fatal message renders only configs.
- **Expected/fix:** the bounded duplicate diagnostic names both operations and
  both source refs, with a negative generated-Swift process gate.
- **Done-when:** the exact diagnostic is pinned and reviewer-approved; keep
  `fixed` until Sergiy confirms.
- **Fix/verified:** the negative real-Swift process names both operations and
  exact file/line/column/source-operation refs.

## v2-swift-nativeui-mobile-css-regex — valid shipped override is rejected
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 9ef73ac81 -->

**Status:** fixed (2026-07-11, `9ef73ac81`); found by `nativeui-reviewer` in Rozum during the
uncommitted Swift AppCore ABI-v1 review.

- **Real-harness repro:** pass the exact `mobileOverrideCss` grammar to generated
  `serve`; the Swift raw regex contains doubled backslashes and returns
  `NativeUiUnsupported` instead of the original root.
- **Expected/fix:** match the frozen JVM grammar exactly and reject a near miss;
  prove both branches through real Swift execution.
- **Done-when:** valid/invalid CSS gates pass and reviewer approves; keep `fixed`
  until Sergiy confirms.
- **Fix/verified:** the Swift raw regex now matches the JVM grammar; exact CSS
  retains the root and a one-character near miss becomes sourced Unsupported.

## v2-swift-nativeui-flat-name-detection — domain globals trigger UI mode
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 9ef73ac81 -->

**Status:** fixed (2026-07-11, `9ef73ac81`); found by `nativeui-reviewer` in Rozum during the
uncommitted Swift AppCore ABI-v1 review.

- **Real-harness repro:** generate a domain `Program` defining and calling its
  own `signal` or `emit`; flat-name scanning emits `NativeUiHost.swift` and later
  fails because the user function registered no UI root.
- **Expected/fix:** select UI mode only from reserved, provenance-annotated ABI
  globals (or otherwise exclude user definitions); same-named domain definitions
  remain host-free and run under real Swift.
- **Done-when:** same-name domain regression is byte-for-byte host-free and green;
  keep `fixed` until Sergiy confirms.
- **Fix/verified:** mode detection honors reserved ABI provenance and excludes
  program definitions; a user `signal` remains a normal host-free Swift package.

## v2-swift-nativeui-evaluation-rollback — arbitrary failure cannot reuse session
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 9ef73ac81 -->

**Status:** fixed (2026-07-11, `9ef73ac81`); found by `nativeui-reviewer` in Rozum during the
uncommitted Swift AppCore ABI-v1 review.

- **Real-harness repro:** begin a host, register provisional signal/root state,
  then trigger any runtime validation failure other than missing/duplicate root.
  AppCore uses `fatalError`, so no recoverable boundary calls `abort` and the same
  host cannot be proven clean on a second evaluation.
- **Expected/fix:** introduce a catchable Swift runtime failure boundary,
  abort-on-error, and same-host recovery without weakening bounded diagnostics.
- **Fresh review delta (Rozum 2026-07-11):** a native failure currently records
  `SscRuntimeFailure` and substitutes `Unit`, but an enclosing application/
  primitive/guard can inspect that placeholder and hit a second `fatalError`.
  Short-circuit every enclosing evaluation step as soon as failure is recorded;
  gate an invalid NativeUi call in outer-function position plus same-host reuse.
- **Done-when:** a real Swift test fails after provisional state, recovers on the
  same host, and extracts a clean root; keep `fixed` until Sergiy confirms.
- **Fix/verified:** extension failures are catchable and sticky; all enclosing
  evaluated subterms short-circuit. A nested invalid function position aborts,
  then the same host accepts a conflicting-default signal and clean root.

## v2-swift-nativeui-root-session-lifetime — extracted ABI loses callbacks/store
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 9ef73ac81 -->

**Status:** fixed (2026-07-11, `9ef73ac81`); found by `nativeui-reviewer` in Rozum during the
uncommitted Swift AppCore ABI-v1 review.

- **Real-harness repro:** `takeRoot()` calls `abort()` and clears signals while
  `Machine` is weakly captured by the host and deallocated when `evaluate`
  returns. Invoking an extracted signal/computed/keyed/user closure then sees an
  empty store or `native UI runtime released`.
- **Expected/fix:** expose `makeNativeUiRoot` backed by a retained evaluation
  session/store lifetime; successful handoff detaches provisional transaction
  bookkeeping without destroying live cells/Machine.
- **Fresh review delta (Rozum 2026-07-11):** successful extraction retains the
  signal map but replaces `emptyHeaders` with `Unit`; post-root render closures
  using short fetch/row-action arities then fail because extern defaults are not
  synthesized. Keep the root-scoped header signal until session disposal and
  invoke a short-arity action from an extracted render closure.
- **Done-when:** real Swift extracts a root and subsequently invokes signal get/
  set, computed, and user/render closures successfully; reviewer approves and
  the entry stays `fixed` until Sergiy confirms.
- **Fix/verified:** retained sessions own Machine/store until disposal; real
  post-root probes call mutable/computed/key/render closures and construct a
  short-arity fetch action through the still-live root `emptyHeaders` signal.

## v21-list-mkstring-capture — separator slot points at the source list
<!-- status: fixed
     lane: apparatus
     area: cli
     fixed-in: 23fddc6a2
     confirmed: no -->

**Status:** fixed (2026-07-11, `23fddc6a2`); found by codex when nested-pattern
fallback made `typed-data.ssc` execute through its Adults section; waiting for
Sergiy confirmation before `done`.

- **Real-harness repro:** after `scripts/sbtc installBin`, run
  `bin/ssc-standard run examples/typed-data.ssc` and inspect the line after
  `=== Adults ===`. It prints `AliceList(Alice, Charlie)Charlie` instead of
  `Alice, Charlie` on both VM and direct ASM.
- **Expected:** `_sel_mkString(List("Alice", "Charlie"), ", ")` inserts the
  supplied separator exactly once between adjacent elements.
- **Root cause:** inside the recursive `go` lambda and its `Cons/2` arm, the
  captured separator is de Bruijn local 4 and the original list is local 5;
  `selMkStringDef` reads local 5 as the separator.
- **Planned fix:** change the generated separator reference to local 4, add a
  direct multi-element regression on VM/ASM, rerun every native-entry/corpus/
  parity/taxonomy/conformance gate, and keep the entry `fixed` until Sergiy
  confirms.
- **Fix/verified:** the regression covers empty, singleton, multi-element, and
  numeric lists on both lanes; `typed-data.ssc` now prints `Alice, Charlie`.
  Native-entry, corpus, strict parity, both taxonomies, portable smokes, and
  fresh 11/11 conformance pass.

## v21-parity-mixed-scala-fence — native math exposes one-sided compiler surface
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: ee8467442
     confirmed: no -->

**Status:** fixed (2026-07-11, `ee8467442`); found by codex while implementing
TI-8.2d2i, waiting for Sergiy confirmation before `done`.

- **Real-harness repro:** after staging the native `math` global, run
  `scripts/bc-parity-sweep --ssc bin/ssc-standard --only 'lang-split.ssc'
  --strict`. The VM exits zero after printing `Stub`-derived Scala-fence output,
  while direct ASM fails on the later mixed numeric `%` expression.
- **Expected:** a document whose front matter explicitly opts into
  `runScalaFences: true` is a compiler/tools surface on the compiler-free
  standard lane, even when it also contains `scalascript` fences. It must be
  source-classified before either backend runs, not compared as portable CoreIR.
- **Root cause:** the parity classifier skips backend-specific fences only when
  no standard block exists. It ignores the explicit mixed-fence execution flag,
  so the old shared `math` failure hid divergent unsupported Scala semantics.
- **Fix/verified:** `runScalaFences: true` is classified as `skipped-backend`,
  `lang-split.ssc` is pinned in the portable-gates smoke, and its stale
  runtime-taxonomy row is removed. Focused and full strict parity have zero
  one-sided rows; runtime/sentinel taxonomy and conformance gates pass.
- **Done-when:** classify `runScalaFences: true` as `skipped-backend`, pin
  `lang-split.ssc` in the portable-gates smoke, remove its stale runtime-taxonomy
  row, keep mismatch/one-sided counts at zero, and retain `fixed` until Sergiy
  confirms.

## v2-nativeui-rust-component-scope-proof — Rust adapter lacks a real compiler gate
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 1f3ca3962 -->

**Status:** fixed (2026-07-11, `1f3ca3962`); found by `nativeui-reviewer` in Rozum while
reviewing the uncommitted NativeUi ABI-v1 migration.

- **Repro:** `RustGenWebToolkitTest` only string-matches the emitted generic
  `FnOnce` adapter; it never runs `cargo check`/`rustc` on a program calling
  `componentScope`.
- **Expected/fix:** compile a generated Rust package containing the generic
  identity call and retain the exact-return/exact-once contract.
- **Fix/verified:** the toolkit test now writes the generated crate and runs
  real `cargo run`; the generic `FnOnce` adapter compiles and prints `ok`.
- **Done-when:** a real Rust toolchain gate passes and its landed SHA is reported
  in Rozum; keep `fixed` until Sergiy confirms.

## v2-nativeui-transitive-native-provenance — childCtx rebind can replace user NativeFnV
<!-- status: fixed
     lane: apparatus
     area: plugin
     fixed-in: 1f3ca3962 -->

**Status:** fixed (2026-07-11, `1f3ca3962`); found by `nativeui-reviewer` in Rozum while
reviewing the `componentScope` compatibility fix.

- **Repro:** every raw `childCtx` `NativeFnV` currently enters
  `rebindPluginNative`; a same-named user/case-constructor native can be
  replaced whenever the parent owns a plugin native of that name.
- **Expected/fix:** require child plugin provenance and identity with the child
  plugin binding before rebinding to the parent.
- **Fix/verified:** transitive rebinding now requires both the child's recorded
  plugin name and object identity with its live global; a same-named user case
  constructor remains callable through an exported facade.
- **Done-when:** component callbacks stay green, a same-name non-plugin
  regression is preserved, and the SHA is reported in Rozum.

## v2-nativeui-keyed-scope-ownership — JVM ABI lacks transactional keyed lifecycle
<!-- status: fixed
     lane: apparatus
     area: plugin
     fixed-in: 1f3ca3962 -->

**Status:** fixed (2026-07-11, `1f3ca3962`); found by `nativeui-reviewer` in Rozum against the
frozen first JVM NativeUi gate.

- **Repro:** `UiNativePlugin` has only a scope stack; signals have no owner
  references/disposal, and `NativeUiForKeyed` static evaluation neither rejects
  duplicate keys nor commits/rolls back insert/move/delete ownership.
- **Expected/fix:** implement the frozen root/owner/scope/signal keys,
  provisional owner transactions, duplicate diagnostics, stable surviving
  scopes, deleted-key disposal, and rollback on render failure.
- **Fresh review delta (Rozum 2026-07-11):** `currentOwnerPath` still omits
  enclosing component scopes and lexical occurrence. Two component/repeated
  instances at the same `forKeyed` site/key can collide; add that collision
  repro plus shared-scope refcount/delete coverage before re-review.
- **Final retention delta (Rozum 2026-07-11):** strong component-result identity
  bindings survive keyed refresh/deletion even after `ownerScopes` is pruned,
  retaining old view→signal-closure→cell graphs. Prune bindings in the same
  owner transaction, restore on rollback, and gate bounded counts/deletion.
- **Fix/verified:** structural owners include component and site occurrence;
  insert/move/update/delete, duplicate, shared-scope refcounts, rollback, and
  25-refresh bounded-retention gates pass. The reviewer approved the result.
- **Done-when:** insert/move/update/delete/duplicate/rollback tests pass and the
  reviewer approves.

## v2-nativeui-descriptor-contract — public UI descriptors diverge from ABI-v1
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 1f3ca3962 -->

**Status:** fixed (2026-07-11, `1f3ca3962`); found by `nativeui-reviewer` in Rozum.

- **Repro:** shortened column arities are rejected; raw HTML does not require
  the exact sentinel; seed first-write can stay pristine; row-delete encodes
  DELETE/Unit instead of shipped POST/id payload; tagged signal dispatch omits
  `id`.
- **Expected/fix:** fill every public default, enforce the exact sentinel,
  dirty seed on the first user write, restore POST/id semantics, and register
  tag-qualified `id`.
- **Fix/verified:** every short column form, the two-attribute raw sentinel,
  first seed write, POST/id delete request, and tagged `id` are covered; the
  affected assembled conformance cases remain green.
- **Done-when:** focused tests plus `std-ui-jobpanel` and toolkit conformance pass.

## v21-layout-given-after-abstract-def — abstract return type consumes the next given
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 2a223d060
     confirmed: no -->

**Status:** fixed (2026-07-11, `2a223d060`); found by codex while implementing
TI-8.2d2h, waiting for Sergiy confirmation before `done`.

- **Real-harness repro:** after `scripts/sbtc installBin`, run
  `bin/ssc-standard run --native tests/fixtures/v21-native/layout-given-objects.ssc`.
  A `trait` with an abstract `def ...: String` immediately followed by
  `given intRender: Render[Int] with` leaves the first given body as an orphan
  block and fails with `ssc: unbound global: intRender` on both VM and direct
  ASM lanes.
- **Expected:** each named layout given is preserved as its own `given_obj`, its
  methods receive the static given prefix, sibling members resolve within that
  prefix, and both execution lanes agree.
- **Root cause:** `with` was not a layout opener, and a trait-header colon did
  not preserve a balanced body. The trait parser therefore returned at its
  first abstract `def`; generic return-type scanning then consumed the next
  given header until the newly inserted body brace.
- **Fix/verified:** `with` opens the existing generic layout path, while a
  narrow trait-header state makes only its trailing colon open a virtual block.
  Static member lowering prefixes bare sibling references after lexical lookup.
  Both a global `skipTypeAt` semicolon stop and a narrower `def` return-type
  stop were rejected because they regressed the real `std.http` fixture by
  exposing abstract class fields as top-level parser sentinels. The final
  fixture passes VM/direct ASM; `typeclass.ssc` reaches only `summon`; corpus,
  parity, taxonomy, native-entry, and fresh conformance are green.
- **Done-when:** the focused fixture passes VM/direct ASM, `typeclass.ssc`
  advances only to its independent `summon[...]` boundary, and the full native
  corpus, parity, taxonomy, native-entry, and affected conformance gates remain
  green. Keep `fixed` until Sergiy confirms.

## v21-parity-external-http-flake — live httpbin makes VM/ASM parity one-sided
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 2769bc479
     confirmed: no -->

**Status:** fixed (2026-07-11, `2769bc479`); found by codex while verifying
native extension dispatch, waiting for Sergiy confirmation before `done`.

- **Real-harness repro:** run `scripts/bc-parity-sweep --ssc bin/ssc-standard
  --report target/v21-standard-bc-parity-current.tsv`. The sweep executed
  `examples/v2-http-sql-demo.ssc` twice against live
  `https://httpbin.org/status/200`; the VM request timed out while the ASM run
  succeeded, producing forbidden `vm-error 1/0` despite identical compiler and
  runtime semantics.
- **Expected:** release parity must be deterministic and must not compare two
  independent public-network outcomes. The live HTTP demo belongs to the
  reviewed nondeterministic/server skip lane unless supplied a local fixture.
- **Observed root cause:** the parity skip classifier does not recognize this
  front-clean network example, so external availability can turn a skipped or
  symmetric row into a one-sided release failure.
- **Fix/verified:** `v2-http-sql-demo.ssc` is source-classified in the reviewed
  nondeterministic lane before either backend runs. The portable-gates smoke
  pins the row, and a fresh strict 195-row sweep is 12 identical / 57 both-fail /
  126 skipped with zero mismatch or one-sided error.
- **Done-when:** add a source-derived deterministic skip classification with a
  synthetic regression, rerun the real 195-row parity report to zero mismatch /
  one-sided rows, and keep the entry `fixed` until Sergiy confirms.

## v21-runtime-taxonomy-content-owner — content extern gaps assigned to module linker
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 6b736d078
     confirmed: no -->

**Status:** fixed (2026-07-11, `6b736d078`); found by codex while starting
TI-8.2d2 from the real `target/v21-runtime-taxonomy-current.tsv` report at
`df84e8acd`, waiting for Sergiy confirmation before `done`.

- **Real-harness repro:** run `scripts/v21-runtime-taxonomy`, then inspect
  `content-linked-namespaces.ssc`, `content-to-markdown.ssc`, and `content.ssc`.
  The manifest classifies them as `language-runtime/module-linker`, while
  `runtime/std/content.ssc` declares `contentModuleSection` and `contentSection`
  as `extern def` and the content plugin owns the `md` surface.
- **Expected:** all three rows are `standard-provider` blockers owned by the
  core-free content-provider migration. The total blocker ceiling remains 48.
- **Root cause:** the initial 60-row review grouped unbound imported names by
  their visible error without checking whether the imported declaration was
  pure ScalaScript or an extern/provider contract.
- **Fix/verified:** all three rows now belong to `standard-provider/content`;
  exact ceilings are 20 language-runtime / 25 standard-provider / 48 blockers.
  Synthetic smoke, real taxonomy, and fresh conformance 10/10 pass.
- **Done-when:** move the three rows to `standard-provider`, tighten exact
  category limits from 23/22 to 20 language-runtime / 25 standard-provider,
  update the recorded baseline, and rerun taxonomy smoke, the real report, and
  affected conformance. Keep `fixed` until Sergiy confirms the taxonomy.

## v21-standard-markdown-abi-packaging — slim launcher omits structural Markdown ABI class
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 36d5ef3b6
     confirmed: no -->

**Status:** fixed (2026-07-11, `36d5ef3b6`); found by codex while verifying the
self-hosted Markdown frontend cutover, waiting for Sergiy confirmation before
`done`.

- **Real-harness repro:** after `scripts/sbtc "installBin"`, run
  `bin/ssc-standard run --native tests/fixtures/v21-native/sql-provider.ssc`.
  The full launcher works, but the assembled slim launcher throws
  `NoClassDefFoundError: scalascript/cli/NativeSourceMarkdown` while decoding
  `NativeCompilation/4`.
- **Expected:** every structural ABI class reachable from `RunNativeV2` is in
  `bin/lib/standard/ssc.jar`; the slim launcher validates Markdown and runs the
  program without a compatibility/tools JAR.
- **Root cause:** `build.sbt` builds the slim CLI with an explicit class-prefix
  allowlist. The new top-level `NativeSourceMarkdown` product was not added to
  that list, so only the full `ssc.jar` contained it.
- **Fix:** the class is now in the standard allowlist. The assembled
  `v21-native-plugin-boundary-smoke.sh` and Markdown frontend smoke are the
  faithful regressions; both the full and slim launchers pass.
- **Done-when:** both `bin/ssc-standard` and `bin/ssc` pass native Markdown/SQL,
  the slim JAR contains the ABI class, and the landed fix SHA is recorded here.
  Keep `fixed` until Sergiy confirms the assembled distribution.

## v2-swiftui-event-increment-overflow-readonly — ordinary event dispatch can trap or lose source
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: f062a9184 -->

**Status:** done (2026-07-11; fixed in `f062a9184`, `9ae1a130b`, and
`12fae35e7`; `nativeui-reviewer` confirmed with APPROVE in the `scalascript`
Rozum room).

- **Real-harness repro:** dispatch an ordinary `NativeUiEvent(kind=increment)`
  against a live Int signal holding `Int64.max`; the generated Swift performs
  trapping `value + delta`. A forged/live read-only signal can also pass the
  current kind-shape guard and fail later through an unsourced write path.
- **Expected:** event preflight requires the exact live target to be user-writable,
  checked addition reports overflow without trapping or mutation, and every
  malformed/read-only/overflow rejection includes the owning element site/source.
- **Plan/done-when:** harden `NativeUiActions.run`/Store target validation and add
  strict generated-Swift regressions for max-value increment, read-only target,
  zero mutation, no process trap, and exact source diagnostics. Validation and
  mutation must bind to the same current Host cell: a forged otherwise-valid
  wrapper with a marker write closure cannot execute that closure or resurrect
  a disposed tombstone. Authenticated reads also use the current Host cell's
  `dynamicRead` (not a forged closure or stale `current` default), preserving
  pristine seed-source semantics before the event makes the seed dirty. This is outside
  the already approved async fetch/action slice; keep `fixed` until the Rozum
  reporter confirms it.

## v2-swiftui-surviving-owner-action-task-leak — removed action can finish under a surviving keyed owner
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 5c0b38ad9 -->

**Status:** done (2026-07-11; fixed in `5c0b38ad9`, `068e8b62d`, and
`03f2f1fcf`; `nativeui-reviewer` confirmed with final APPROVE in the
`scalascript` Rozum room).

- **Real-harness repro:** mount a keyed row containing a fetch action, start its
  controllable `URLProtocol` request, then reconcile the same key to a row that
  no longer contains that action. The Host disposes the old owner-owned
  phase/error signal keys, but the Store only cancels task owners returned as
  deleted keyed paths. Because the keyed owner itself survives, the request can
  remain current until a non-guaranteed SwiftUI `onDisappear`; a late 2xx may
  commit capture/clear/effects before the eventual status write detects the
  disposed signals.
- **Expected/root-cause direction:** bind every active action task generation to
  the exact phase/error signal capability that authorized it. Disposing either
  key during Host reconciliation must synchronously invalidate and cancel that
  task, even while its containing keyed owner survives; late completion must
  become inert before any user mutation.
- **Plan/done-when:** return or publish exact disposed action status keys through
  the Host/Session reconciliation seam, cancel matching Store tasks without
  view lifecycle callbacks, and add generated strict-concurrency Swift gates for
  same-key action removal/replacement, stopped transport, no late 2xx mutation,
  and a freshly idle/error-empty action after reinsertion. Explicit cancellation
  must reset a unique/last task to idle+empty after generation invalidation while
  preserving loading if another mounted task shares the exact phase/error
  capability. A delayed lifecycle callback holding an obsolete action descriptor
  must fail exact `validActionStatus` capability validation before it can cancel
  or reset a fresh replacement at the same structural owner. Navigation and
  `openJson` share one preflight/response URL policy: http/https need a non-empty
  host, mailto a non-empty target, and unsafe or hostless templates cannot start
  transport. Keep `fixed` until
  the Rozum reporter confirms the regression.

## v2-bigint-dynamic-arith-money — std/money allocation feeds Unit to an if condition on v2
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: ff3a52eba
     confirmed: no
     gate: tests/conformance/run.sh -->

**Status:** fixed (2026-07-10, `ff3a52eba`); waiting for reporter confirmation
before `done`. Found by codex in assembled conformance while implementing
`v2-portable-decimal-money-effects`.

- **Real-harness repro:** after `scripts/sbtc "installBin"`, run
  `tests/conformance/run.sh --only 'money-*,effect-*' --no-memo`.
  `money-portable-v2` prints five correct exact-Decimal rows, then exits 1 in
  `std/money.ssc` with `if: condition not Bool: ()` while evaluating
  `BigInt(i) < remainder` inside `allocate`.
- **Expected:** bridge-emitted dynamic arithmetic on `BigInt` implements the
  same exact arithmetic/comparison contract as named `big.*` primitives, so
  Money allocation returns `$0.02, $0.02, $0.01` instead of host `Unit`.
- **Root cause:** `Prims.resolve` implements named `big.add`/`big.lt` primitives,
  but bridge code emits `__arith__`; `arithRest` has no `BigV`/`BigV` or mixed
  `BigV`/`IntV` arms, so relational operators fall through to the generic
  plugin/declaration fallback and become `UnitV`.
- **Fix:** dynamic `__arith__` now delegates `BigV`/`BigV` and mixed
  `BigV`/`IntV` operations to exact BigInt arithmetic/comparison semantics.
- **Verified:** the focused frontend-bridge regression passed, `installBin`
  assembled the real distribution, and
  `tests/conformance/run.sh --only 'money-*,effect-*,effects' --no-memo`
  passed 6/6 including unchanged `std/money.allocate` behavior.

## v21-standard-index-vm-asm-divergence — index example fails VM and succeeds with malformed ASM output
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 86a2de03a
     confirmed: no -->

**Status:** fixed (2026-07-10, `86a2de03a`); waiting for human confirmation
before `done`.

- **Found by:** codex from `scripts/bc-parity-sweep --ssc bin/ssc-standard`.
- **Real-harness repro:** after `scripts/sbtc "installBin"`, run
  `bin/ssc-standard run examples/index.ssc` and the same command with
  `--bytecode`. VM prints only `ScalaScript 0.1 is running!` then fails
  `arity: 1 expected, 2 given`; direct ASM exits zero but prints the malformed
  second line `)}` instead of `Squares: 1, 4, 9, 16, 25`.
- **Expected:** both lanes execute the checked program, print the same complete
  two-line result, and either both reject or both succeed; malformed output may
  not count as bytecode success.
- **Root-cause direction:** inspect lowering/dispatch for the for-yield result
  and `mkString(", ")`; compare VM `App` arity with the generated direct-ASM
  dispatch path before changing the example.
- **Done-when:** assembled VM/ASM output is byte-identical and semantically
  correct, the focused parity row is `identical`, and affected conformance is
  green.
- **Root cause:** the self-hosted outer string scanner stopped at the quote in
  `${nums.mkString(", ")}`. Its interpolation splitter only understood a bare
  identifier and reclassified the remaining selector/call text as string
  literals. VM then failed on malformed CoreIR while ASM stringified it into a
  meaningless successful `)}`.
- **Fix:** the lexer now balances braced interpolation bodies and nested quoted
  strings, and the interpolation builder parses the complete inner expression
  through the normal expression grammar.
- **Verified:** `index.ssc` and a focused two-expression fixture print the exact
  expected text byte-for-byte on assembled VM/direct ASM; standard parity is
  1 identical/0 errors, native-entry and standard-tier smokes pass, KC12/KC13
  simple interpolation remains green, and affected conformance is 8/8.

## v21-standard-direct-asm-recursion-stack — direct ASM lacks the VM recursion trampoline
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 3153fb2db
     confirmed: no -->

**Status:** fixed (2026-07-10, `3153fb2db`); waiting for human confirmation
before `done`.

- **Found by:** codex from the standard VM/ASM parity report.
- **Real-harness repro:** `bin/ssc-standard run examples/recursion.ssc` prints
  all 13 expected rows, including 100,000-call self/mutual-tail recursion;
  `bin/ssc-standard run --bytecode examples/recursion.ssc` prints only the
  first four rows and then throws `StackOverflowError` through
  `ssc.gen.Entry.lam$77 -> ssc.Emit.letrec -> ssc.Runtime.run`.
- **Expected:** direct ASM implements the checked CoreIR recursion semantics and
  the same stack-safety contract as the VM, including self and mutual tail
  calls.
- **Root-cause direction:** retain the current direct emitter but route tail
  calls in generated `LetRec` groups through a bounded trampoline/loop; do not
  fall back to a compiler or the VM execution backend.
- **Done-when:** the full example is byte-identical on VM/ASM with a small JVM
  stack, focused recursion/TCO tests cover self/mutual groups, and affected
  conformance is green.
- **Root cause:** top-level recursive targets already had self-loop/mutual
  `Bounce` lowering, but local `LetRec` lambdas were emitted as anonymous
  methods without peer identity. Their tail `App(Local(...))` therefore entered
  `Emit.app -> Runtime.run` recursively. The first failure was `_sel_to`, not the
  user-level top-level `length` function.
- **Fix:** each local recursion body carries environment-relative peer
  method/arity metadata. Tail calls preserve `captured ++ tied-group`, replace
  the current argument suffix, and return a trampoline bounce; generic local
  closure invocation unrolls it iteratively.
- **Verified:** focused bytecode tests pass arithmetic/non-tail recursion plus
  100,000-call local self/mutual TCO (3/3). The real `recursion.ssc` produces all
  13 expected rows identically through VM, in-memory ASM, and `build-jvm` JAR at
  `-Xss256k`; strict focused parity is 1 identical/0 errors; native-entry,
  standard, slim, JRE-module, artifact, and affected conformance gates pass.

## v21-standard-ui-fetch-json-vm-arity — native VM rejects a five-argument UI helper accepted by ASM
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: d6b9ae9ce
     confirmed: no -->

**Status:** fixed (2026-07-10, `d6b9ae9ce`); waiting for human confirmation
before `done`.

- **Found by:** codex from the TI-8.2 standard corpus sweep.
- **Real-harness repro:** `bin/ssc-standard run examples/ui-fetch-json.ssc`
  prints the structured body then fails `arity: 3 expected, 5 given`; the same
  command with `--bytecode` prints `fetch-json:ok` and exits zero.
- **Expected:** the public five-argument `fetchJsonAction` helper has identical
  checked arity and behavior on VM and direct ASM.
- **Root cause:** the self-hosted type skipper balanced `[...]` but not nested
  `(...)`. In a multiline parameter list, the inner `)` of `() => String`
  prematurely ended the outer list, so imported fetch helpers lost parameters
  and their bodies. The VM then honestly rejected the malformed call while
  direct ASM omitted the VM's closure-arity check and falsely succeeded. After
  both compiler defects were corrected, the standard UI provider also needed
  explicit core-free `fetchUrlSignal`, `fetchAction`, and `emptyHeaders`
  declarative values instead of a v1 fallback.
- **Fix:** balance parentheses while skipping function types, enforce closure
  arity in `Emit.app`, and construct readable static fetch signals/actions in
  the native UI provider while leaving actual network execution to an emitted
  browser runtime.
- **Verified:** both assembled lanes print the identical structured body plus
  `fetch-json:ok`; focused strict parity is 1 identical/0 errors. A multiline
  function-parameter fixture, bytecode arity negative test, UI provider test,
  native-entry, standard, slim, JRE-module, artifact, JSON-cutover, and affected
  conformance gates all pass.

## v21-native-front-missing-ui-table-import — corpus import closure references a deleted std module
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: d4513cb8a
     confirmed: no -->

**Status:** fixed (2026-07-10, `d4513cb8a`, diagnostic gate
`ac441ef62`); waiting for human confirmation before `done`.

- **Found by:** codex from `scripts/native-front-corpus`.
- **Real-harness repro:** compiling `examples/graph-fullstack-rdf.ssc` aborts
  with `NoSuchFileException: v1/runtime/std/ui/table.ssc`; the document imports
  `table`, `tableHeader`, `tableRow`, and `tableCell` from that path, but the
  staged std tree has no such module.
- **Expected:** every checked-in example import resolves deterministically, or
  a removed API is migrated with an explicit source-level diagnostic rather
  than a host filesystem exception.
- **Root cause:** the example retained the removed `std/ui/table.ssc` path and
  old `tableHeader`/`tableCell` wrappers after the toolkit consolidated tables
  under `std/ui/data.ssc`.
- **Fix:** import `tableCol`/`tableRow`/`table` from the current module and build
  the three columns/row cells through that API; import resolution remains strict.
- **Verified:** the real row is frontend/checker OK and any remaining unsupported
  backend-specific surface is a filename-bearing bounded sentinel diagnostic.
  Native-entry rejects `NoSuchFileException`/host matcher leakage explicitly;
  the full frontend corpus has 194 successes, 0 host errors, 0 timeouts, and 1
  non-code document.

## v21-standard-h2-java-compiler-edge — slim gate misses compiler classes inside dependency JARs
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: e4cd55b36
     confirmed: no
     gate: tests/e2e/v21-slim-distribution-gate.sh -->

**Status:** fixed (2026-07-10, `e4cd55b36`); waiting for human confirmation
before `done`.

- **Found by:** codex while implementing the TI-8 JRE-shaped module gate.
- **Real-harness repro:** after `scripts/sbtc "installBin"`, run
  `jdeps --multi-release base --ignore-missing-deps -verbose:class
  bin/lib/standard/jars/h2-2.2.224.jar`. The staged standard H2 JAR reports
  `org.h2.util.SourceCompiler -> javax.tools.*` and a `java.compiler` module
  dependency even though `tests/e2e/v21-slim-distribution-gate.sh` reports zero
  forbidden references.
- **Root cause:** the TI-7 static gate starts recursive `jdeps` only from the
  class-filtered standard CLI JAR. Service-loaded providers and their JDBC
  drivers are not statically reachable from that entry, so dependency JARs are
  not scanned as roots. `build-jvm` already excludes H2's optional source
  compiler classes, but `installBin` copies the complete H2 JAR into the
  standard tier.
- **Expected:** every standard-tier dependency JAR is a scan root and the
  complete staged tier has no class/reference/module edge to `javax.tools`,
  `java.compiler`, or `jdk.compiler`; normal H2 SQL remains functional on a
  module-limited JRE-shaped runtime.
- **Fix direction:** stage a deterministic H2 runtime-only JAR in
  `lib/standard/jars` with the optional `org/h2/util/SourceCompiler*` family
  removed, retain the unmodified driver only in the tools tier, and strengthen
  the slim/JRE gates to inspect every standard dependency root.
- **Done-when:** full standard-tier `jdeps` reports no compiler module, the
  compiler modules are unresolvable under `java --limit-modules`, native H2 SQL
  passes on VM/direct ASM and as a generated JAR, and affected conformance is
  green.
- **Fix:** `installBin` deterministically repacks only the standard-tier H2 JAR
  and omits its eight optional `SourceCompiler*` classes; the tools-tier copy is
  unchanged. Slim and JRE gates merge every standard dependency into a
  scan-only archive so ServiceLoader/JDBC classes are static roots.
- **Verified:** derived runtime modules exclude `java.compiler`/`jdk.compiler`
  and both fail `--describe-module` under the limit; VM, direct ASM, FS/OS,
  JSON, HTTP, SQL, UI, State, and generated SQL JAR pass. Strengthened slim and
  core-dependency gates pass. At the H2 fix boundary the artifact SHA remained
  `1d078c3ffe330eae72a809f98794333c123d715bbf19012fbdc4f0c686715173`;
  subsequent self-hosted JSON and local-recursion runtime changes intentionally
  advanced the reproducible baseline. Affected conformance is 8/8.

## ui-fetch-get-offline-rejection — managed SPA GET rejects as an unhandled promise offline
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: a0d45ad44 -->

**Status:** done (2026-07-10, fix `a0d45ad44`, reporter confirmation in busi
`77399254`).

- **Found by:** codex while running busi Gate 1 canonical `/app` offline QA.
- **Real-harness repro:** emit and serve busi `src/v2/clients/ssc/app.ssc`, load
  paired `/app` online, stop the local hub, then reload from the installed PWA
  cache. The shell and local facts remain usable, but each mounted
  `fetchUrlSignal` logs an app-origin `TypeError: Failed to fetch`; hidden
  routes are mounted too, so one outage produces repeated console errors.
- **Generated root cause:** `_mountFetchGet` emits
  `fetch(...).then(responseText).then(setSignal)` without a rejection handler.
  A network failure therefore escapes as an unhandled promise rejection even
  though absence of the optional hub is a normal offline state.
- **Expected:** a rejected managed GET keeps its last-good signal value, emits
  no unhandled rejection, and remains eligible for the next tick-driven fetch.
  HTTP response semantics are unchanged.
- **Fix direction:** change the owning custom-SPA runtime generator, not emitted
  busi HTML; add a faithful generated-runtime test with a rejected `fetch` and
  a subsequent successful refresh.
- **Done-when:** focused frontend tests, assembled custom-SPA emission, and
  affected conformance are green; busi rebuild confirms a clean app-origin
  offline console.
- **Fix:** the shared `_mountFetchGet` promise chain now consumes transport and
  response-body rejection without writing the signal. Tick and reactive-URL
  subscriptions stay installed, so a later refresh can recover.
- **Verified:** real `JsRuntimeSignals` Node regression 1/1 plus existing
  `FetchUrlSignalToTest` 1/1; assembled `emit-spa --frontend custom` contains
  the rejection boundary; focused `std-ui-jobpanel`, `tkv2-busi-home`, and
  `tkv2-offline` conformance passes 3/3 on INT and JS.
- **Reporter confirmation:** busi rebuilt and published its canonical owner SPA
  with this runtime, loaded an existing installed profile online, stopped the
  hub, and reloaded cached `/app`. Last-good/local facts remained visible and
  the browser console contained zero app-origin `Failed to fetch` entries; the
  only remaining URL-less inspector-frame error was unrelated.

## v21-build-jvm-import-source-identity-gap — artifact metadata omits resolved imports
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: e4f16baaf
     confirmed: no -->

**Status:** fixed (2026-07-10, `e4f16baaf`); waiting for human confirmation
before `done`.

- **Found by:** codex during the direct-ASM artifact source-map review.
- **Real-harness repro:** after `scripts/sbtc "installBin"`, run `bin/ssc
  build-jvm tests/fixtures/v21-native/relative-main.ssc -o /tmp/import.jar`
  and inspect `META-INF/scalascript/artifact.properties`. The program prints
  `42`, proving `relative-helper.ssc` was linked, but `source.count=1` and only
  the explicit root identity is recorded.
- **Expected:** every source whose declarations contribute to the linked
  checked `Program` has a deterministic name/hash identity; debug SMAP includes
  the same closure while still guaranteeing that every explicit root appears.
- **Root cause:** `RunNativeV2.compile` retained only canonical command-line
  roots after the self-hosted loader resolved imports internally. The artifact
  writer therefore had no import closure to hash or map.
- **Fix direction:** mirror the native loader's standalone-link DFS in a small
  JDK-only host resolver, preserve explicit-root order plus deterministic import
  order, and pass separate `roots` and `sources` collections to artifact debug
  and metadata generation. Do not load the v1 parser/Scalameta.
- **Done-when:** the relative-import JAR metadata and SMAP name both
  `relative-main.ssc` and `relative-helper.ssc`, the helper hash changes when
  its source changes, two builds remain byte-identical, and the assembled
  artifact/conformance gates stay green.
- **Fix:** a JDK-only standalone-link resolver mirrors the self-hosted loader's
  DFS/postorder and retains stable display paths for explicit roots plus the
  linked import closure. Artifact metadata hashes those units, and the lexical
  fenced-source scanner assigns the same units to the SMAP file table.
- **Verified:** relative-import metadata contains helper + root with the
  helper's exact SHA-256; `javap -l -v` names both in SMAP; the runtime prints
  `42`; two base builds remain byte-identical; artifact/conformance gates pass.

## v2-frontendbridge-sqlite-timeout — SQLite conformance exceeds the 15-second bridge-test limit
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: b55811bf9
     confirmed: no
     gate: tests/conformance/v2-db-url-scheme-not-jdbc.ssc -->

**Status:** fixed (2026-07-12, `b55811bf9`), awaiting Sergiy confirmation.

- **Found by:** codex while running the broad post-TI-6.1 regression suite.
- **Real-harness repro:** `scripts/sbtc "v2FrontendBridge/test"`; all other 151
  executed tests pass, but `v2-conformance: v2-db-url-scheme-not-jdbc` returns
  `(timeout)` instead of `1` after the suite's 15-second `Await` bound.
- **Expected:** `tests/conformance/v2-db-url-scheme-not-jdbc.ssc` opens its
  `sqlite::memory:` database and prints `1` within the normal test bound.
- **Root cause:** sqlite-jdbc was present and Hikari was not involved. A live
  blocked-thread trace showed Xerial's first connection in
  `SQLiteJDBCLoader.cleanup → Files.list → readdir0`, scanning every entry in
  the large shared macOS `java.io.tmpdir` before extracting its native library.
- **Fix:** SQLite registration assigns Xerial a private, per-process native
  temp directory unless the host explicitly sets `org.sqlite.tmpdir`. The
  focused regression asserts that isolation and the real conformance fixture
  still performs an actual in-memory JDBC round trip.
- **Verified:** the named bridge case fell from timeout to 1.7 seconds;
  `v2PluginBridge/test` is 32/32 and affected conformance is 1/1. The broad
  bridge suite is 195/196 with this row green; the sole `tkv2-pwa`
  `backend=jdk`/`backend=fast` banner red is the separately tracked provider
  selection issue.

## ssr-forsignal-duplicate-attrs - SSR `ForSignal` fallback duplicated static attrs
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: bb5342f08
     confirmed: no
     gate: tests/conformance/run.sh -->

**Status:** fixed (2026-07-10, source fix `bb5342f08`; regression
`4291a7239`); waiting for human confirmation before `done`.

- **Found by:** codex during `tkv2-raw-html`.
- **Repro:** before `bb5342f08`, render
  `View.ForSignal[String](items = new ReactiveSignalList[String]("rows", Seq("a",
  "b")), tag = "li", attrs = Map("class" -> AttrValue.Str("row"),
  "data-id" -> AttrValue.Str("x")), itemTemplate = None)` through
  `Ssr.renderToHtml`.
- **Observed failure:** each fallback `<li>` serialized the same static attrs
  twice, producing duplicate `class`/`data-id` attributes for every row.
- **Expected:** fallback SSR should serialize the supplied attrs once per
  repeated item.
- **Root cause:** the `View.ForSignal(..., itemTemplate = None)` fallback
  branch called `writeAttrs(sb, attrs)` twice before closing the start tag.
- **Fix:** the duplicate call was removed as part of the raw-html SSR renderer
  patch in `bb5342f08`; `4291a7239` adds the focused regression that counts the
  serialized attrs for two repeated rows.
- **Verified:** `scripts/sbtc "frontendToolkit/testOnly
  scalascript.frontend.toolkit.SsrTest"` passes 33/33; `scripts/sbtc
  "installBin"` passes; `tests/conformance/run.sh --only 'tkv2-raw-html'
  --no-memo` passes 1/1 after staging a fresh CLI in the worktree.

## v2-backend-check-ssc1c-wrapper-app-lit — `fixed` (2026-07-09)
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** codex while verifying the `v2-source-jvm-recursion-fib-perf`
  source-backend slice.
- **Repro:** from a current ScalaScript worktree, run
  `v2/backend/check.sh bool` or `v2/backend/check.sh mutual-recursion`.
- **Observed failure:** both backend-check fixture rows fail before any JVM/JS/Rust
  source generator runs:
  `FAIL bool-predicate: run-ir failed` and
  `FAIL mutual-recursion: run-ir failed`.
- **Detailed bool repro:** generate the backend-check `bool-predicate` ssc1c
  wrapper (`def main(): Unit = { println(workload(42L)); () }`) and run the
  resulting CoreIR through `run-ir`. The emitted IR contains
  `(app (lit (int 1000)) (lam 0 ...))` inside the workload loop condition, and
  `run-ir` aborts with `java.lang.RuntimeException: app: not a function: 1000`.
- **Expected:** the ssc1c wrapper fixtures for `bool-predicate` and
  `mutual-recursion` should produce valid CoreIR so `v2/backend/check.sh bool`
  and `v2/backend/check.sh mutual-recursion` can be used as source-backend
  parity gates again.
- **Impact:** source-backend work touching `v2/backend/*` cannot currently use
  those two generated ssc1c rows as acceptance gates. CoreIR-only backend
  fixtures such as `tco` and `letrec` remain usable and green for the current
  JVM source-backend recursion slice.
- **Notes:** this is independent of `v2/backend/jvm/JvmBackend.scala`; the
  failure happens in the VM `run-ir` oracle before source generation. It may be
  another ssc1c precedence/lowering issue around the synthetic main wrapper or
  the `until`/loop desugaring in the corpus fixture. Track/fix as its own
  ssc1c/backend-check task rather than folding it into JVM source codegen work.
- **Root cause:** `v2/scripts/indent2braces.py` converted `while i < 1000 do`
  to `while i < 1000 { ... }`. `v2/lib/ssc1-front.ssc0` expects
  `while (cond) body`; without parentheses, the condition parser consumed the
  following block as an argument to literal `1000`, producing the invalid
  app-lit CoreIR.
- **Fix:** `043039b61` parenthesizes converted while conditions, e.g.
  `while (i < 1000) { ... }`, preserving the corpus workloads and all source
  generators.
- **Verified:** `v2/backend/check.sh bool`, `v2/backend/check.sh
  mutual-recursion`, `v2/backend/check.sh tco`, `v2/backend/check.sh letrec`,
  `scripts/sbtc "installBin"`, affected conformance
  `tests/conformance/run.sh --only 'mutual-recursion,variables' --no-memo`
  (2/2 across INT/JS/JVM), and `git diff --check`.
- **Status:** fixed; waiting for human confirmation before `done`.

## std-auth-webauthn-signature-drift — `fixed` (2026-07-09)
<!-- status: fixed
     lane: apparatus
     area: runtime
     gate: tests/conformance/webauthn-server-verify.ssc
     fixed-in: unrecorded -->

- **Found by:** codex, during `tkv2-webauthn` spec/implementation prep.
- **Repro:** compare `v1/runtime/std/auth.ssc` declarations with the existing
  implementations in `v1/runtime/std/auth-plugin/.../AuthIntrinsics.scala` and
  `v1/runtime/backend/js/.../JsRuntimeWebAuthn.scala`, or run the existing
  `tests/conformance/webauthn-server-verify.ssc` / `examples/webauthn-demo.ssc`
  call shapes. The implementations and shipped examples use:
  `webauthnStoreFind(userId, credentialId)`,
  `webauthnUpdateSignCount(userId, credentialId, newSignCount)`,
  `webauthnVerifyRegistration(clientDataJSONb64, attestationObjectB64, expectedOrigin)`,
  and `webauthnVerifyAssertion(clientDataJSONb64, authenticatorDataB64,
  signatureB64, credentialIdB64, expectedOrigin)`.
- **Observed failure:** the public std declarations still document older/wrong
  arities and return types for those four WebAuthn helpers, so new user code
  can be guided into calls the runtime does not implement.
- **Impact:** WebAuthn is production-sensitive; browser-client helpers would be
  confusing if the adjacent server verifier declarations stay stale.
- **Fix direction:** update `std/auth.ssc` declarations to the runtime-backed
  arities/return shapes without changing the verifier semantics, then keep
  `webauthn-server-verify` green on INT+JS.
- **Root cause:** `std/auth.ssc` lagged behind the already-shipped JVM/JS
  WebAuthn verifier/store implementations; examples and runtime code had moved
  to user-scoped credential lookup, boolean sign-count updates, and verifier
  inputs split into browser response fields.
- **Fixed in:** `e61a89b4c` (`feat: add tkv2 webauthn browser actions`).
- **Gates:** `scripts/sbtc "backendJs/compile; frontendPlugin/compile; backendInterpreter/compile"`;
  `scripts/sbtc "backendInterpreter/testOnly scalascript.JsRuntimeWebAuthnClientTest scalascript.JsGenStdImportTest"` (43 tests);
  `tests/conformance/run.sh --only 'tkv2-webauthn,webauthn-server-verify' --no-memo`
  (2/2 cases, INT+JS pass); `emit-spa --frontend custom` smoke for
  `examples/frontend/webauthn-toolkit-demo/webauthn-toolkit-demo.ssc`.
- **Done-when:** the declaration file, examples, runtime intrinsics, and
  conformance call shapes agree; fixed SHA and gates are recorded here.

## v2-ssc0-rust-float-literal-emits-int — `fixed` (2026-07-08)
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** codex, during `p4-rust-wasm-lanes` baseline.
- **Repro:** run `./v2/conformance/check.sh` and inspect the diagnostics log.
- **Observed failure:** several Rust target rows fail at `rustc` with
  `error[E0308]: mismatched types`, because the self-hosted Rust backend emits
  collapsed whole-float literals such as `V::Fl(2)` / `V::Fl(1)` after
  `#f->str`, but Rust's `V::Fl` variant requires `f64`. Representative rows:
  `numops Rust`, `numcmp Rust`, `div Rust`, `float math Rust`, `mathx* Rust`,
  `letrec poly Rust`, `dict-passing Rust`, `dict ord Rust`.
- **Impact:** real Rust target compilation is broken for typed numeric programs
  that contain whole-valued float constants. WASM inherits this through
  `ssc0-wasm` because it compiles the same Rust source to `wasm32-wasip1`.
- **Fix direction:** normalize generated Rust float literals in
  `v2/lib/backend-rust-gen.ssc0`: whole finite values need a Rust float suffix
  or decimal (`2.0`), while `nan`/`inf`/`-inf` should map to valid Rust
  constants if they surface.
- **Done-when:** the Rust rows above compile and pass in
  `./v2/conformance/check.sh`, and the WASM quicksort/TCO gate remains green.
- **FIXED (2026-07-08, `84d7ac77f`):** the self-hosted Rust backend normalizes
  `IrFloat` literals after `#f->str`: whole finite values get a decimal
  (`2.0`), existing decimal/exponent spellings are preserved, and
  `nan`/`inf`/`-inf` map to Rust `f64` constants.
- **Verified:** full `./v2/conformance/check.sh`; Rust numeric rows including
  `hm-numops`, `hm-numcmp`, `hm-div`, mathx, rounding, dict-passing, and
  method-poly/self compile and pass; WASM quicksort and 1e6-tail-call TCO remain
  green.

## root-test-cli-spark-submit-dry-run-deps — `fixed` (2026-07-08)
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: unrecorded -->

- **Found by:** codex, during `green-main-full-sbt-test-gating` focused
  `scripts/sbtc "cli/test"` after the Electron fork-exit blocker was fixed.
- **Repro:** `cd /Users/sergiy/work/my/scalascript-wt-green-main-full-sbt-test-gating &&
  scripts/sbtc "cli/testOnly scalascript.cli.SubmitCommandTest"`.
- **Observed failure:** `SubmitCommandTest` reports two failed assertions:
  `--dry-run prints package + submit argv with default master` no longer includes
  `org.apache.spark::spark-core:4.0.0`, and `--spark-version threads through to
  both deps` no longer includes `spark-core:3.5.1`.
- **Impact:** the CLI aggregate remains red; Spark submit dry-run output may have
  intentionally moved dependency information or stopped emitting it. The test and
  command contract must agree before root `test` can be a production gate.
- **Fix direction:** inspect `submit` dry-run output generation and the current
  intended Spark dependency surface. If deps are intentionally no longer present in
  the package argv, update the test to assert the current contract; otherwise
  restore dependency lines/options.
- **Done-when:** focused `SubmitCommandTest` is green and full `cli/test` no
  longer reports this suite.
- **FIXED (2026-07-08, `cea0c3aed`):** the dry-run contract is the generated
  package source, not inline `spark-submit --dep` argv. `SubmitCommandTest` now
  parses the `# source:` path from dry-run output and asserts Spark dependency
  directives in that generated source.
- **Verified:** `scripts/sbtc "cli/testOnly scalascript.cli.SubmitCommandTest"`;
  full `scripts/sbtc "cli/test"` (554 succeeded, 29 canceled, 0 failed);
  bounded root `scripts/sbtc "test"` (elapsed 1668s, success).

## root-test-cli-toolkit-electron-duplicate-seqmap — `fixed` (2026-07-08)
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** codex, during `green-main-full-sbt-test-gating` focused
  `scripts/sbtc "cli/test"` after the Electron fork-exit blocker was fixed.
- **Repro:** `cd /Users/sergiy/work/my/scalascript-wt-green-main-full-sbt-test-gating &&
  scripts/sbtc "cli/testOnly scalascript.cli.ToolkitElectronSmokeTest"`.
- **Observed failure:** `ToolkitElectronSmokeTest` case
  `toolkit-demo Electron bundle renders, routes Add, and persists after restart`
  fails with renderer error
  `Uncaught SyntaxError: Identifier '_seqMap' has already been declared`; the
  smoke then reports `SMOKE_FAIL initial render missing`.
- **Impact:** toolkit Electron smoke is a real browser/Electron bundle execution
  gate, not just a string assertion. Duplicate JS helper declarations in emitted
  bundles can blank desktop UI startup.
- **Fix direction:** reproduce focused, inspect the generated Electron bundle, and
  deduplicate or scope duplicate helper preamble emission (`_seqMap`) so runtime
  helpers are emitted once per bundle.
- **Done-when:** focused `ToolkitElectronSmokeTest` is green and full `cli/test`
  no longer reports this suite.
- **FIXED (2026-07-08, `cea0c3aed`):** the renderer bundle had a broader
  strict-mode duplicate/binding chain: collection sequencing helpers existed in
  both `core-collections.mjs` and `async.mjs`; session HMAC reused the core
  crypto helper name; the typed JSON facade could be included twice; browser
  patch assignments lacked stable bindings; and `_ssc_frontend_name` was split
  between ws/server and injected frontend code. The runtime now has a single
  collection helper source, repeat-safe typed JSON facade bindings, distinct
  session HMAC name, and a base frontend-name binding.
- **Verified:** `scripts/sbtc "cli/testOnly scalascript.cli.ToolkitElectronSmokeTest"`;
  full `scripts/sbtc "cli/test"` (554 succeeded, 29 canceled, 0 failed);
  bounded root `scripts/sbtc "test"` (elapsed 1668s, success).

## root-test-cli-fork-exit-after-green — `fixed` (2026-07-08)
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** codex, during `green-main-full-sbt-test-gating` root
  `scripts/sbtc "test"` after bounded sbt Test concurrency was added.
- **Repro observed in root gate:** `cli / Test / test` reported
  `Total number of tests run: 488`, `Tests: succeeded 488, failed 0, canceled 19`,
  and `All tests passed.`, then sbt still failed the task with
  `Error during tests: Running java with options ... sbt.ForkMain ... failed with exit code 1`.
- **Impact:** the CLI aggregate is red even though ScalaTest reports no failing
  test. Root `test` cannot be treated as a production gate until the forked JVM
  exits cleanly or the late exit is traced to a real failing resource cleanup path.
- **Fix direction:** reproduce with focused `cli/testOnly` suites first, starting
  from the last emitted suite in the root stream and then widening to `cli/test` if
  needed. Inspect late JVM/process cleanup and generated files such as
  `v1/tools/cli/ssc-storage.json`; do not paper over the non-zero fork exit.
- **Done-when:** targeted repro is understood and fixed, `scripts/sbtc "cli/test"`
  exits 0, and the final root-equivalent gate no longer reports this task failure.
- **Progress (2026-07-08, uncommitted worktree):** minimal
  `ElectronJvmRestCliTest` fork exit was caused by stale fake-Electron greps in
  the typed-route client smoke. The generated client now accepts
  `headers, cancelToken` and the HTTP runtime assigns `response = await fetch(...)`
  in a retry loop. Updating those smoke assertions made focused
  `ElectronJvmRestCliTest` pass with fork exit 0. Full `cli/test` now reaches
  ordinary assertion failures tracked separately above instead of the old
  after-green fork exit.
- **FIXED (2026-07-08, `cea0c3aed`):** after updating the stale Electron typed
  client smoke assertions and fixing the later deterministic CLI/runtime
  blockers, the full forked `cli/test` exits 0.
- **Verified:** `scripts/sbtc "cli/testOnly scalascript.cli.ElectronJvmRestCliTest"`;
  full `scripts/sbtc "cli/test"` (554 succeeded, 29 canceled, 0 failed);
  bounded root `scripts/sbtc "test"` (elapsed 1668s, success).

## root-test-verify-default-srcdir-parent-scan — `fixed` (2026-07-08)
<!-- status: fixed
     lane: apparatus
     area: runtime
     gate: tests/conformance/run.sh
     fixed-in: unrecorded -->

- **Found by:** codex, during the same full root `scripts/sbtc "test"` gate.
- **Repro observed in root gate:** `VerifyCliTest` cases such as
  `verify .../ssc-verify-noruntime-* --strict` and `verify
  .../ssc-verify-json-* --json` spent about 1-2 minutes each on tiny temp
  directories. Thread dump showed the child process hot in
  `runVerify(Main.scala:4125)` at `os.walk(srcDir).filter(os.isFile)`; default
  `srcDir` was `artifactDir / os.up`, so temp sandboxes scanned the entire
  `/var/.../T` parent containing many other root-suite temp directories.
- **Impact:** root `sbt test` becomes needlessly slow and can look hung after the
  first real failures; production `ssc verify <dir>` can also scan far outside
  the requested artifact set by default.
- **Root cause:** default `srcDir` was always `artifactDir / os.up`; custom
  artifact directories such as temp `out/` folders therefore indexed every
  sibling `.ssc` file in the parent tree before checking a tiny artifact set.
- **Fix:** `6c996bd63` changes the implicit default to the artifact directory
  itself, preserving parent lookup only for conventional `.ssc-artifacts`
  output dirs, and adds a subprocess regression for a custom `out/` directory.
- **Verified:** `scripts/sbtc "cli/testOnly scalascript.cli.VerifyCliTest"` 8/8
  green; `tests/conformance/run.sh --only 'std-process-import' --no-memo` 1/1
  green.

## root-test-command-registry-other-category — `fixed` (2026-07-08)
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 631ed8052
     gate: tests/conformance/run.sh -->

- **Found by:** codex, during `green-main-full-sbt-test-gating` root
  `scripts/sbtc "test"` after the bytecode split-runtime and Scala.js npm
  dependency fixes.
- **Repro observed in root gate:** `CommandRegistryTest` failed
  `every command category is in the help ordering` with `List("Other") was not
  empty` at `CommandRegistryTest.scala:57`.
- **Targeted repro to run before fixing:** `scripts/sbtc "cli/testOnly
  scalascript.cli.CommandRegistryTest"`.
- **Initial hypothesis:** at least one command provider now reports or defaults to
  category `Other`, but the help category ordering omits it. Either assign the
  provider a real existing category or deliberately add `Other` to the ordering
  if it is now a supported category; do not silence the test without preserving
  deterministic help grouping.
- **Status:** fixed in `631ed8052`. Root cause was `VersionCmd` explicitly using
  the fallback-style `Other` category. `Other` is the default for unclassified
  commands, while `CommandRegistryTest` intentionally requires every visible
  command to be placed into an ordered help bucket. `version` is metadata/help
  output, so it now uses the existing `Help` category instead of normalising
  `Other` as a public bucket.
- **Verified:** `scripts/sbtc "cli/testOnly scalascript.cli.CommandRegistryTest"`
  (**8/8 green**) and `tests/conformance/run.sh --only 'std-semigroup-monoid'
  --no-memo` (**1/1 green**).

## v2-args-global-shadowed-by-native — `fixed` (2026-07-08)
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: unrecorded -->

- **Found by:** claude-fable-5, unmasked while testing OpAnf (entry below): the
  If-cond Let-wrap re-routed `if args.length > 0` from the length FastCode (whose
  tolerant `case _ => 0L` swallowed the wrong receiver) to the honest generic
  dispatch — which crashed `.length on <closure>` (dataset-word-count et al).
- **Root cause:** `loadAll()`'s SPI bridging registers a native FUNCTION global
  under "args"; the args VALUE-list registration was guarded by `if isEmpty` and
  never fired — `args` was a closure everywhere on v2. `args.length`/`args(0)`
  (the documented semantics, examples/dataset-word-count.ssc) only "worked" via
  the FastCode accident. Pre-existing on origin/main, INDEPENDENT of OpAnf; the
  v1 lane has the same gap (`No method 'length' on NativeFnV(<native:args>)`) —
  v1 side left open (BACKLOG note).
- **Fix:** register the args Cons/Nil list AFTER the plugin loop (same
  post-plugins override pattern as cwd/sep/platform), built from `Runtime.argv`
  (now set BEFORE loadAll in RunV2/bridgeCli); `scalascript.args` prop stays as
  the embedder fallback.
- **Verified:** `println(args)` → `List()`, `args.length` → `0` through the
  generic dispatch; dataset-word-count PASSES honestly (not via `0L` tolerance).

## jvm-multishot-result-type — `fixed` (2026-06-21, `39b7c665f`)
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** benchmark perf-divergence sweep (`./bench.sh`), accepted from `SPRINT.md`.
- **SHA at filing:** `0ee00a29f` (`feature/jvm-multishot-result-type` worktree, after
  `sbt -no-colors cli/installBin`).
- **Symptom:** `bench/corpus/effect-multishot.ssc` reports `n/a` on the JVM backend even though the
  source declares `def workload(seed: Long): Long`. The bench wrapper uses an `AtomicLong` sink and
  emits `_ssc_sink.getAndAdd(workload(_ssc_sink.get()))`, but `emit-scala` currently lowers the CPS
  effectful `workload` as `def workload(seed: Long): Any`, so `scala-cli` rejects the wrapper with
  `Found: Any; Required: Long`.
- **Repro (real harness):** `./bench.sh effect-multishot --backend jvm` -> `n/a`; then
  `scala-cli --java-opt -XX:CompileThreshold=100 --java-opt -XX:-BackgroundCompilation --server=false
  /tmp/ssc-bench-jvm-effect-multishot.sc` shows the three `getAndAdd(workload(...))` type errors.
- **Root cause:** the top-level CPS def emitter always generated `def f(...): Any = ...` for any
  transitively effectful function. That is correct for effect-row defs (`A ! Eff`) that may return a
  Free computation, but wrong for total wrappers such as `def workload(seed: Long): Long` that handle
  their effects internally. The earlier handle-result fixes made `all.foldLeft(...)` compile, but the
  def boundary still widened the declared `Long` to `Any`.
- **FIXED (2026-06-21, `39b7c665f`):** JVM CPS def emission now keeps declared non-effect-row result
  types and casts the final CPS result at the boundary; `A ! Eff` defs still emit `Any`. The same helper
  is used for nested CPS defs inside CPS blocks. Regression guard: `JvmGenEffectsRuntimeTest` proves
  `addLong(workload(0L))` compiles and runs, so the total CPS def has static type `Long`.
- **Verified:** `sbt -no-colors "backendInterpreter/testOnly scalascript.JvmGenEffectsRuntimeTest"` =
  34/34; `sbt -no-colors cli/installBin`; `./bench.sh effect-multishot --backend jvm` = 0.075 ms/iter
  (was `n/a`); `./bench.sh effect-oneshot --backend jvm` = 0.160 ms/iter (same root cause).

## rust-foreach-list-realloc — `fixed` (2026-06-21, `abbc98eee`)
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** benchmark perf-divergence sweep (`./bench.sh`), accepted from `SPRINT.md`.
- **Symptom:** Rust codegen re-inlines a top-level collection `val` at each use site instead of referencing
  the `let` binding emitted in each def preamble. In hot loops this rebuilds the whole `vec![...]` every
  iteration: `pattern-match-heavy` emits `for s in vec![Circle { .. }, Rect { .. }, ..].iter().cloned()`
  inside `while i < 100000`, leaving the preamble `let shapes = vec![...]` dead. `list-fold` has the same
  shape for `xs`.
- **Repro:** inspect generated Rust for `pattern-match-heavy` / `list-fold` with the real Rust emitter, then
  run `./bench.sh pattern-match-heavy list-fold --backend rust`.
- **FIXED (2026-06-21):** `RustCodeWalk` now references top-level vals by their generated `let` binding
  instead of re-inlining the initializer at every use site, and only injects a top-val preamble into defs
  that actually reference it. `collectMultiUse` also stops counting lambda/def parameter binders as reads,
  removing the spurious `area(s.clone())` for a single-use foreach parameter. Guard:
  `RustGenCollectionTest` asserts one `let xs = vec![...]`, `for x in xs.iter()`, no `for x in vec!`, and
  no `inc(x.clone())`. Verified emitted Rust: `area` has no dead `shapes` preamble, `workload` builds
  `shapes`/`xs` once and iterates the binding. Bench: `./bench.sh pattern-match-heavy list-fold --backend rust`
  improved `list-fold` 0.153→0.044 ms and `pattern-match-heavy` 4.16→1.37 ms.

## v2-arith-split-jit-size — `fixed` (2026-07-09)
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: unrecorded
     gate: tests/conformance/head-field-shadow.ssc -->

- **Found by:** claude-fable-5 while gating the head-field fix: pattern-match-heavy
  at ~354 ms/iter on clean origin/main vs 23.6 the evening before (15×). Bisected
  to `a2985d911 fix(v2): unify dynamic arith dispatch`.
- **Root cause:** the unification merged the whole `__arith__` dispatch table into
  `Prims.arithOp` — semantically right (it closed the table/arithOp divergence),
  but the merged method blew past the JVM JIT size limits, so EVERY arith op
  (literal-name hot loops included) ran interpreted. Reordering patterns bought
  only 350→300; splitting restored 26.
- **Fix:** `arithOp` keeps only the hot head (`->`, Int/Float/mixed/Str×Str pairs,
  Op-lifting) and delegates everything else to `private arithRest`. Bench:
  pattern-match-heavy 26.0/26.1, arith-loop 9.56, nested 15.3, effect-multishot
  5.12 — all at baseline. FrontendBridgeTest 25/25 (incl. the unification tests).

## v2-user-type-shadows-plugin-type — a user case class named "Request" (or any plugin-owned tag) has its fields clobbered on v2
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: d5f9ce486
     gate: tests/conformance/v2-user-type-shadows-plugin-type.ssc -->

**Status:** FIXED 2026-07-09 (8feeda99f) — the conditional-Request-lock +
snapshot/restore revert. Verified: the conformance repro passes (r1/task),
AND the "any plugin-owned tag" generalization did NOT hold empirically —
only `Request` was in `FrontendBridge.runtimeShapedTypes`, so ONLY it was
lockable/clobbered; user case classes named KV/Rate/Response/etc. always
went through registerCaseClass normally and already won (verified KV→7,
Rate→x in a batch). d5f9ce486 (snapshot/restore) was reverted; the real
root was the GLOBAL "Request" reservation, now conditional on the exact
std/http.ssc lib shape. v1 lanes correct, guarded by
tests/conformance/v2-user-type-shadows-plugin-type.ssc

`V2PluginRegistry.fieldNames` is a single GLOBAL, tag-keyed map shared by
EVERY case class in a program (user-declared or plugin-owned) — this is
the same root design (global, receiver-blind field-name registry) behind
`v2-head-field-dispatch-shadow` and `v2-route-params-stub`. Plugin load
registers a baseline entry for the runtime-owned `Request` tag (the
`std/http.ssc` `Request` type: method/path/headers/body/params/query/…,
needed for `req.params` to resolve — see `v2-route-params-stub`, now
fixed). `d5f9ce486` made `snapshot()` capture that baseline and `restore()`
reset `fieldNames` back to it. Any program that ALSO declares its OWN
`case class Request(...)` — a completely unrelated type, sharing nothing
but the tag name — gets that registration silently clobbered: field access
on the user's `Request` instances resolves against the HTTP-shaped field
list instead of the user's own, the user's field name isn't found there,
and the value comes back as `Stub`.

Repro (self-contained, no `std/http.ssc` import at all):
```
case class Request(id: String, kind: String)
val items = List(Request("r1", "task"), Request("r2", "investor"))
val hit = items.filter(r => r.id == "r1").head   // → RuntimeException: head on empty list
```
`items.filter(r => r.id == "r1")` comes back EMPTY (both items' `.id`
reads as `Stub`, so nothing matches `"r1"`), then `.head` on that empty
list crashes. v1 is correct: `println` shows `r1` / `task`.

busi hit this for real via `src/v2/domain/requests.ssc`'s OWN
`case class Request(id, partyId, kind, subject, body, channel, …)` (an
inbox-request domain type, nothing to do with HTTP) — this regressed
`tests/v2/requests.ssc` from passing to `RuntimeException: head on empty
list` the same afternoon the route-params fix landed (busi's full v2
domain sweep went 61/61 → 60/61).

Likely fix direction: `fieldNames` (and the runtime-baseline snapshot
introduced by `d5f9ce486`) needs to key on something that disambiguates
plugin-owned/builtin tags from user-declared ones (or restore() needs to
only reset entries that WEREN'T re-registered by the user's own compile
unit), not just the bare tag string "Request" — any user type name that
happens to collide with a plugin-owned runtime tag (Request is a very
natural, common domain name — busi is not the only likely victim) will hit
this. Found+minimized 2026-07-09 by busi (fable), same day as the
route-params-stub fix.

## v2-req-form-stub-in-hub — req.form(name) returns Stub inside busi's real hub.ssc (isolated minimization did NOT reproduce)
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: unrecorded
     gate: tests/e2e/req-type-collision-v2-smoke.sh. -->

**Status:** FIXED 2026-07-09 (renamed root cause: **v2-req-form-type-collision**).
MINIMIZED: the hub imports TWO different `Request` types — `std/http.ssc`
`Request` (http, 14-field runtime layout with form/params) AND
`../domain/requests.ssc` `Request` (a business request: id/partyId/kind/…).
v2's field registry is keyed by tag NAME → the last-registered layout (the
domain `Request`) wins, so http `req.form`/`req.params` resolved against a
layout with no such field → `Stub`. (busi couldn't minimize it because a
single-Request fixture has no collision.) v1 tolerates it via fully-dynamic
by-name field lookup on the value. Fix: **arity-matched field resolution** — a
secondary `(tag, arity)` index in `V2PluginRegistry`; field access (both the
bare and field-with-args paths: `__method__`/`__methodOrExt__`/methodOp
fallback/`fieldAt` 3-arg/`.copy`) and `v1ToV2`/`v2ToV1` DataV building now
resolve against the layout whose arity == the receiver's field count. Fixes
BOTH collision directions. Verified: busi hub `POST /pair` correct code now
sets the auth cookie; corpus 154/8; gated by
tests/e2e/req-type-collision-v2-smoke.sh. Reported by busi (fable), minimized
+ fixed by lucky-perch.

busi's live hub (`src/v2/http/hub.ssc`), booted on `--v2`, has its
`POST /pair` route (`req.form.getOrElse("code", "") == pairCode`) always
read `req.form.getOrElse("code", "")` as the literal string `"Stub"`,
confirmed by temporarily instrumenting the route directly:
`DEBUG-PAIR formCode=[Stub] pairCode=[019f47] rawBody=[code=019f47]` — the
raw POST body IS received correctly (`code=019f47`), but `req.form`
parsing/field-access yields `Stub` instead of the parsed map. v1 (same
`busi.conf`, same route, same request) pairs successfully on the first
try. Since `POST /pair` is the ONLY way into every cookie-gated flow, this
alone blocks driving busi's live hub end to end on `--v2` (a live
money-loop pass could only proceed via a break-glass device-token seeded
directly into `tokens.txt` on disk, bypassing `/pair` entirely).

Two independent minimization attempts did NOT reproduce this in isolation:
1. A trivial `route("POST","/echo"){ req => ...req.form.getOrElse("code","<empty>") }`
   fixture, alone — `req.form` parses correctly (`code=hello`).
2. The same fixture PLUS a colocated `case class Subject(id, displayName,
   form, data, from)` — deliberately colliding the FIELD NAME "form" with
   `std/http.ssc`'s `Request.form` at a different index (2 vs the
   Request's declared index 4), the same class of bug as
   `v2-user-type-shadows-plugin-type`/`v2-head-field-dispatch-shadow` —
   still parses correctly.

So the trigger is something about `hub.ssc`'s actual scale/import graph
(it is one of the largest files in busi, importing dozens of modules) that
neither of those two isolation attempts captured — possibly import COUNT,
a DIFFERENT specific field-name collision elsewhere in the graph, or
route-registration-order sensitivity. Repro (needs a busi checkout):
boot `SSC_LANE_FLAG=--v2 scripts/ssc src/v2/http/hub.ssc` with a
`busi.conf` pointing at a scratch `dataDir`, read the printed pairing
code (or `cat /code.txt`), `curl -X POST http://localhost:/pair
-d "code="` — response is always "Неверный код" (v1: succeeds
first try). Found 2026-07-09 by busi (fable), same session as
`v2-option-exists`; flagging for whoever has better tooling to bisect a
large real file (busi did this successfully before for
`v2-head-field-dispatch-shadow` by copying+halving the failing module,
but hub.ssc's own internal complexity — not just its import graph — may
need a different bisection approach, e.g. commenting out route
registrations in blocks).

## v2-string-split-limit-overload — String.split(delimiter, limit) unimplemented on v2 (CAUSED A REAL PRODUCTION OUTAGE)
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: unrecorded
     gate: tests/conformance/v2-string-split-limit- -->

**Status:** FIXED 2026-07-10 — `Runtime.scala`'s `String` method dispatch had
`(StrV(s), "split", List(StrV(d)))` (one-arg) but no two-arg case. Added a
sibling `(StrV(s), "split", List(StrV(d), IntV(limit)))` arm right next to it,
calling the SAME underlying `s.split(d, limit.toInt)` the one-arg case already
used internally (with `-1` hardcoded) — just parameterizing the limit instead.
Mirrors the existing `substring`/`substring(i,j)` sibling-arm pattern in the
same match. Verified: the repro below now matches v1 exactly; edge cases
across the full Java/Scala `split` limit semantics (positive/zero/negative)
are byte-identical v1 vs v2; 8+ busi domain tests exercising this code path
(durable, ledger, requests, bank_reconcile, social, sync, plus the exact
production trigger shape) pass cleanly on the patched binary. The full
175-test conformance suite could not be run to completion in this session
(scala-cli's compilation server died early under heavy concurrent load from a
sibling agent's work on the same machine — confirmed environmental, not
code-related: smaller batches through the same real harness passed cleanly).
Whoever lands this should re-run the full suite once on a quieter machine as
final confirmation. Fixed by busi (fable), same session as the report.

**Status (superseded, kept for history):** OPEN — v1 correct, guarded by
tests/conformance/v2-string-split-limit-overload.ssc. **Severity: this
exact gap took down a real, live production service** (busi, 2026-07-10)
within minutes of a routine v1→v2 default flip + deploy — not a test
failure, an actual customer-facing outage (two systemd services
crash-looped, restart counter 4+, both sites returning connection-refused
until a live rollback).

The one-arg overload (`s.split(delimiter)`) works correctly on v2. The
TWO-arg overload (`s.split(delimiter, limit)` — delimiter + a limit,
e.g. `-1` to keep trailing empty fields, the standard idiom for parsing
TSV/CSV rows that may have a blank last column) is not dispatched at all:

```
val s = "a\tb\tc\t"
s.split("\t")       // works on v2
s.split("\t", -1)   // RuntimeException: __method__: no dispatch for .split
```

busi's real trigger: `identity.ssc`'s `readTsv()` parses a real TSV
sessions file via `line.split("\t", -1)` at hub boot (loading the
sessions store for WebAuthn/email-login identity resolution). This file
only exists on an instance with real prior login history — every
pre-flip verification (a full v1-vs-v2 A/B harness covering an entire
money-loop end to end, a 30-route sweep over a seeded demo dataset, and a
real-browser e2e suite) used a fresh data directory with no sessions
file, so the two-arg `.split` call was never reached before the flip hit
production. Repro: `bin/ssc --v2
tests/conformance/v2-string-split-limit-overload.ssc` (v1 lanes below
pin the correct, passing behavior).

Found+minimized 2026-07-10 by busi (fable) immediately after diagnosing
and rolling back the live incident. Given the severity (a working
default-flip reached real production and broke it), this — and auditing
for other unimplemented String/collection method overloads with the same
shape (single-arg works, multi-arg silently missing) — should be
high-priority for v2 parity work.
