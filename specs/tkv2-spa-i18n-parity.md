# tkv2 SPA i18n Parity

## Overview

Toolkit v2 P2 requires the generated SPA path to match the server-page
`std/ui/i18n` behavior for EN/RU/UK/PL catalogs with live locale switching.
This slice verifies the production `emit-spa --frontend custom` path and lands
the smallest fix or regression test needed to prove that `localeSwitcher`
updates `localeText`, `localeHeading`, and `localePlural` without a page
reload.

## Interface

No new public API is intended. The acceptance surface is the existing
`std/ui/i18n.ssc` API:

- `localeSignal: Signal[String]`
- `setLocale(loc: String): Any`
- `localeText(catalog, key, base): TkNode`
- `localeHeading(level, catalog, key, base): TkNode`
- `localePlural(catalog, keyBase, nSig): TkNode`
- `localeSwitcher(locales, labels): TkNode`

The runnable source is `examples/std-ui/i18n-demo.ssc`, emitted with:

```bash
bin/ssc emit-spa --frontend custom examples/std-ui/i18n-demo.ssc
```

## Behavior

- [x] The custom emitted SPA renders the default English heading, text, plural,
      and locale buttons from `examples/std-ui/i18n-demo.ssc`.
- [x] Clicking the Russian, Ukrainian, Polish, and English locale buttons
      updates the translated heading/text/plural in the mounted DOM without
      reloading the page.
- [x] The regression uses the production `emit-spa --frontend custom` runtime
      path, not only the interpreter or static string inspection.
- [x] The existing `std/ui/i18n` server/interpreter behavior remains green.
- [x] The affected toolkit-v2 conformance slice and `git diff --check` pass
      before push.

## Out of Scope

- Adding new i18n API surface.
- Changing pure `std/i18n.ssc` translation or plural rules.
- Framework emitter parity for React/Solid/Vue; toolkit-v2 production uses the
  custom `emit-spa` runtime.
- Full busi migration verification; this slice only closes the standalone
  `std/ui/i18n` SPA parity gate.

## Design

Use the existing generated-SPA test style rather than a new harness. The first
probe should emit `examples/std-ui/i18n-demo.ssc` through `bin/ssc emit-spa
--frontend custom` and drive the mounted page with the repo's browser/jsdom
pattern if available. If the runtime already updates correctly, add the missing
regression and documentation only. If it fails, inspect the custom runtime
bridge around `SignalButtonNode` lowering, `data-ssc-set`, `_set`, and
computed-signal recomputation, then fix only that narrow path.

Likely files to inspect or touch:

- `runtime/std/ui/i18n.ssc`
- `runtime/std/ui/lower.ssc`
- `runtime/backend/js/src/main/resources/scalascript/js-runtime/signals.mjs`
- `runtime/backend/interpreter/src/test/scala/scalascript/JsGenStdImportTest.scala`
- `tests/conformance/std-ui-i18n.ssc` or a new focused `tkv2-*` conformance
  case if static conformance coverage is useful.

## Decisions

- **Production custom SPA first** - chosen because `specs/ssc-toolkit-v2.md`
  declares `emit-spa`/custom as the toolkit-v2 production path. Rejected:
  spending this slice on React/Solid/Vue emitters.
- **Regression before broad refactor** - chosen because i18n may already work
  and only lack a browser-driven test. Rejected: changing the i18n API before
  reproducing a gap.

## Results

Reproduced a real emitted-SPA crash before the fix: the generated module
correctly imported `std.ui.primitives.serve` as `serve__ssc`, but the top-level
auto-call emitted bare `serve(...)`; jsdom failed before mounting `.ssc-page`
with `ReferenceError: serve is not defined`.

Root cause: `JsGen.dispatchIntrinsicJs` only checked
`declaredBindings.contains(fname)` before stealing `Term.Name(fname)` calls for
registered/hardcoded intrinsics. Collision-renamed imports bind
`emittedName(fname)` (`serve__ssc`), so the HTTP/server `serve` intrinsic won
over the imported UI binding. Fix `7e5d55e4f` makes intrinsic dispatch skip
when either the raw name, emitted name, or a top-level user rename is already
declared; the generated SPA now falls through to regular `_call(serve__ssc,
...)`.

Verification:

- `JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=1' scala-cli compile
  /tmp/TkV2I18nHarness.scala
  v1/runtime/backend/js/src/main/scala/scalascript/codegen/JsGen.scala
  --classpath "$CP" --server=false --compilation-output
  /tmp/tkv2-i18n-classes --print-class-path` passed for the patched `JsGen`
  plus standalone browser harness.
- `java -cp "/tmp/tkv2-i18n-classes:$(cat /tmp/tkv2-i18n-classpath.txt)"
  tkV2I18nHarness <worktree>` printed `i18n-spa-live-ok`; the harness checks
  default EN render and RU/UK/PL/EN live-click updates in jsdom.
- CLI-shaped smoke with patched classes:
  `java -Dssc.lib.path="$PWD" -cp "/tmp/tkv2-i18n-classes:<installed jars>"
  scalascript.cli.ssc emit-spa --frontend custom
  examples/std-ui/i18n-demo.ssc` emitted a 316842-byte HTML bundle containing
  `_call(serve__ssc, ...)`; jsdom over that HTML printed
  `i18n-spa-live-ok`.
- `JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=1'
  tests/conformance/run.sh --only 'std-ui-i18n,tkv2-*' --no-memo` passed 10/10
  matching cases.
- `git diff --check` passed.

Note: direct `sbt -batch "backendInterpreter/testOnly
scalascript.JsGenStdImportTest -- -z i18n"` and `scripts/sbtc` attempts in this
Codex tool session received external `SIGTERM` during full sbt build load
(`Cancel: Signal` in sbt logs), before executing the test. The added ScalaTest
regression mirrors the standalone harness; the standalone direct compile,
CLI-shaped emit smoke, affected conformance, and diff-check are the completed
local gates for this slice.
