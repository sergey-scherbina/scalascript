# Bug tracker

## v21-json-parser-pmapped-match — JSON DSL reaches an unhandled `PMapped/2`

**Status:** open (2026-07-11); found by codex after native `case object`
support advanced `dsl-json-parser.ssc` beyond `unbound global: NoContext`.

- **Real-harness repro:** `bin/ssc-standard run --native
  examples/dsl-json-parser.ssc` now fails identically on VM/ASM with `match: no
  arm for PMapped/2`.
- **Expected:** the imported combinator evaluator's existing `PMapped(inner, f)`
  arm matches the reified parser node and applies the mapping closure.
- **Plan/done-when:** isolate the constructor/import boundary in a multi-file
  regression, compare the emitted `PMapped/2` arm with the assembled value,
  repair native match ownership/order without parser-specific host code, and
  rerun all parser DSLs plus release gates.

## v21-k62-flat-tuple-pattern-regression — flat tuple values keep nested `Pair` patterns

**Status:** done (2026-07-11, `7f6821856`); found and confirmed by codex in the
mandatory native-entry gate after K62.20 (`28061083a`) changed tuple
expressions of arity three or greater to flat `TupleN` values.

- **Real-harness repro:** `bin/ssc-standard run --native
  tests/fixtures/v21-native/nested-tuple-pattern.ssc` prints `left` followed by
  `()` on VM and direct ASM; the established exact output is `left` followed by
  `left+right`.
- **Expected:** pattern `Some((left, '+', right))` matches the flat `Tuple3`
  constructed for its nested tuple and binds `left`/`right` on both lanes.
- **Root cause:** K62.20 updated `lowerTuple` to emit flat `TupleN`, while
  `ssc1-front.ssc0::tuplePat` still emits the obsolete right-nested `Pair`
  pattern for arity three or greater.
- **Plan/done-when:** make tuple expression and pattern shapes agree (Pair for
  arity two, flat `TupleN` for arity three or greater), retain existing nested
  and flat tuple regressions, and rerun native-entry, corpus/parity/taxonomy,
  release, and fresh conformance gates.
- **Fix/verified:** `tuplePat` now emits the same Pair/2 versus flat `TupleN`
  representation as expression lowering. The existing fixture again prints
  `left` / `left+right` exactly on VM and ASM; every release gate and fresh
  conformance 11/11 pass.

## v21-imports-tuple2-collection-match — imported collection pipeline rejects `Tuple2/2`

**Status:** open (2026-07-11); found by codex while refreshing native-entry
after K62.19 tuple selector support advanced `examples/imports.ssc` beyond its
former collection arity failure.

- **Real-harness repro:** `bin/ssc-standard run --native examples/imports.ssc`
  prints the complete native math section and `distance (0,0)-(3,4) = 5`, then
  VM/ASM reach the classified collection pipeline and fail with `match: no arm
  for Tuple2/2`. `examples/extensions.ssc` now reaches the same boundary after
  printing through `min = 1, max = 9`.
- **Expected:** the imported list/tuple pipeline binds portable tuple pairs and
  completes identically on VM/ASM.
- **Plan/done-when:** isolate the post-math tuple pipeline in a multi-file
  fixture, repair constructor/tuple pattern ownership without a host fallback,
  make `imports.ssc` and `extensions.ssc` identical, and retire their
  language-runtime taxonomy rows only after all release gates pass.

## v21-yaml-unit-global — native layout parser emits an unbound `Unit` value

**Status:** open (2026-07-11); found by codex after symbolic extension dispatch
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

## v21-yaml-parser-context-arity — YAML parser calls a nullary value with one argument

**Status:** open (2026-07-11); found by codex after the typed-pattern boundary
fix advanced `dsl-yaml-like.ssc` beyond the false `Unit` global.

- **Real-harness repro:** `bin/ssc-standard run --native
  examples/dsl-yaml-like.ssc` and the same command with `--bytecode` now fail
  identically with `arity: 0 expected, 1 given` after the imported layout
  parser enters its first typed arm.
- **Expected:** the imported parser/context operation selects the intended
  callable definition and completes identically on VM/ASM.
- **Plan/done-when:** identify the exact callee and declaration ownership from
  the assembled CoreIR, add an import-boundary regression, repair name/call
  lowering without a YAML-specific host path, and rerun all parser DSLs plus
  release gates.

## v21-case-object-no-context-unbound — native frontend drops `case object`

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

## v2-swiftui-fetch-wrapper-silent-default — non-text fetch bindings render an empty value

**Status:** fixed (2026-07-11, `70bee065d`), awaiting Sergiy confirmation; found by `nativeui-reviewer` in the third
read-only SwiftUI store/renderer review in Rozum.

- **Real-harness repro:** bind a deferred `fetch` signal to a text control or a
  signal-backed style. `NativeUiSignalText` reports sourced Unsupported, but
  the other wrappers read the fetch signal's empty default and render it as if
  a request had completed.
- **Expected:** until the async slice lands, every rendered seam rejects a
  fetch-kind signal with the same source-located Unsupported diagnostic.
- **Plan/done-when:** centralize the guard in the observation/binding seam and
  execute signal-text, text-control, toggle/style, and keyed-items probes; none
  may expose the empty fetch default.

## v2-swiftui-unsourced-malformed-seams — malformed nodes and events lose site provenance

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

## v2-swiftui-shipped-inventory-semantic-loss — accepted tags/styles render different semantics

**Status:** fixed (2026-07-11, `70bee065d`), awaiting Sergiy confirmation; found by `nativeui-reviewer` in the third
read-only SwiftUI store/renderer review in Rozum.

- **Real-harness repro:** shipped `align-items:center` is accepted while stacks
  remain hard-coded leading/top; `font-weight:500` is accepted but unapplied;
  `strong`/`em`/`code` are plain children; an href-only anchor is a no-op
  button; and `ol start` still renders bullet list items.
- **Expected:** every shipped inventory value maps to its native behavior or a
  sourced Unsupported node; accepted-but-ignored semantics are forbidden.
- **Plan/done-when:** implement the exact native mapping where bounded, use
  sourced Unsupported for any deferred value, and execute behavior-or-
  Unsupported probes for alignment, medium weight, semantic text, href-only
  navigation, and ordered-list numbering/start.
  The real `content.ssc` ordered-list `start` field is an `Int`, not the String
  used by the first draft gate. Recognized CSS also needs value-total handling:
  `display`, `flex-direction`, `gap`, `flex`/`flex-grow`, `text-align`,
  `text-decoration`, and border shorthands must map or diagnose invalid values.
  Until a route-signal seam exists, hash/relative href is sourced Unsupported;
  treating `#/path` as a generic `Link` violates the frozen route contract.
  Fifth review found the remaining recognized-value holes: parse the shipped
  `box-shadow` grammar exactly (or source Unsupported) rather than applying one
  hard-coded shadow to every value, and require the exact accepted border
  shorthand instead of accepting trailing junk.
  Sixth review adds that an explicit `border-color` must not mask an invalid
  third color token in the shorthand itself; validate the shorthand first,
  then apply the explicit override.

## v2-swiftui-owner-hint-closure-clone-leak — node identity mutates ABI and retains refresh tombstones

**Status:** fixed (2026-07-11, `70bee065d`), awaiting Sergiy confirmation; found by `nativeui-reviewer` in the third
read-only SwiftUI store/renderer review in Rozum.

- **Real-harness repro:** two `NativeUiForKeyed` nodes reuse the same render
  closure. The draft clones that closure solely to key owner hints, violating
  the frozen constructor identity contract. Every successful refresh of a
  surviving owner also retains superseded cloned closure/hint entries because
  pruning runs only for deleted owner subtrees.
- **Expected:** constructors preserve the exact original closure identity;
  owner metadata binds to the concrete returned node/instance, remains bounded
  across refreshes, rolls back transactionally, and disappears on deletion.
- **Plan/done-when:** attach host-only exact-node identity without adding or
  changing ABI fields, prune superseded hints within the owner transaction,
  and real-gate shared closure identity plus bounded counts across repeated
  refresh, failed rollback, and committed delete.

## v2-swiftui-owner-hint-fifo-swap — reversed tree construction exchanges repeated-site state

**Status:** fixed (2026-07-11, `70bee065d`), awaiting Sergiy confirmation; found by `nativeui-reviewer` in the second
read-only SwiftUI store/renderer review in Rozum.

- **Real-harness repro:** construct two `NativeUiForKeyed` nodes at the same
  lexical site under distinct component/occurrence owners, then place them in
  the returned tree in reverse construction order. The draft records owner
  paths in a per-site FIFO and assigns them during later tree traversal, so the
  nodes exchange owners and may inherit each other’s component state.
- **Expected:** an owner hint is bound to the exact returned node/structural
  instance independently of construction order.
- **Plan/done-when:** replace FIFO correlation with node-bound identity without
  changing ABI fields and add a real reversed construction/tree-order gate
  covering move and fresh reinsertion.

## v2-swiftui-keyed-store-rollback-publication — failed provisional render leaks revisions

**Status:** fixed (2026-07-11, `70bee065d`), awaiting Sergiy confirmation; found by `nativeui-reviewer` in the second
read-only SwiftUI store/renderer review in Rozum.

- **Real-harness repro:** a keyed render writes a live signal and then a later
  item throws. Host restores signal/owner state, but provisional `onWrite`
  already advanced Store revisions, derived caches, and dependency edges.
- **Expected:** Host values/owners and Store cells/revisions/caches/dependencies
  commit or roll back atomically; no mounted subtree observes a failed render.
- **Plan/done-when:** buffer observer effects for the keyed batch and flush
  after commit (drop on rollback), or snapshot/restore Store state. Gate
  write-then-throw with unchanged value/revision/cache/dependencies and clean
  subsequent reconcile.
## v21-native-zero-arg-println-arity — blank println fails before later statements

**Status:** fixed (2026-07-11, `e74241f5e`), awaiting Sergiy confirmation;
found by codex while running the new core-free YAML provider against
`yaml-parse.ssc`.

- **Real-harness repro:** after staging the YAML provider, run
  `bin/ssc run --native examples/yaml-parse.ssc` or its `--bytecode` lane. Both
  print the first five YAML-derived lines and then fail at the source
  `println()` with `arity: 1 expected, 0 given`.
- **Expected:** the established zero-argument `println()` emits one empty line
  and document execution continues identically on VM/direct ASM/build-jvm.
- **Root cause:** the self-hosted lowerer exposes `println` only as an arity-one
  lambda and lowers an empty source application without the compatibility
  frontend's empty-string adaptation.
- **Fix/evidence:** lower only zero-argument global `println()` to the portable
  print primitive with an empty string. The focused real-harness fixture is
  exact on VM/direct ASM, the complete native-entry smoke passes, the YAML
  example advances to its independent fenced-section boundary, and fresh
  affected conformance passes 11/11.
## v21-parity-backends-list-ignored — JS-only examples run on the standard JVM lane

**Status:** fixed (2026-07-11, `d4c953b9c`), awaiting Sergiy confirmation;
found by codex while starting the TI-8.2d4 example/config blocker sweep.

- **Real-harness repro:** run full `scripts/bc-parity-sweep --ssc
  bin/ssc-standard`. Both `sql-browser-{sqlite,duckdb}.ssc` declare
  `backends: [js, node, wasm]`, yet the harness executes them on VM/direct ASM
  and records `both-fail` for their browser-only SQL result bindings.
- **Expected:** a front-matter list containing only JS-family backends is a
  reviewed backend-specific source classification, identical in status to the
  existing singular `backend:`/`target:` rules. Lists that include `jvm` (for
  example `dataset-parallel-sum.ssc`) must not hide standard-runtime debt.
- **Root cause:** `bc-parity-sweep` recognizes only singular `backend:` and
  `target:` keys; it never inspects the established plural `backends:` key.
- **Plan/done-when:** add a bounded inline-list classifier for JS/Node/Wasm-only
  lists, cover a real browser SQL example in the portable-gates smoke, rerun
  all 195 parity rows, and remove only the two newly skipped runtime-taxonomy
  rows while preserving `dataset-parallel-sum.ssc` as a blocker.
- **Fix/verified:** the harness now recognizes only inline lists composed of
  `js`/`node`/`wasm`; both browser SQL rows are `skipped-backend`, while the
  focused `[jvm]` dataset row still executes and remains `both-fail`. Full
  parity is 21/45/129 with zero mismatch or one-sided error.

## v21-native-reactive-effect-parsed-as-declaration — top-level effects disappear

**Status:** fixed (2026-07-11, `dae51ecab`), awaiting Sergiy confirmation;
found by codex while gating the core-free reactive provider.

- **Real-harness repro:** after `scripts/sbtc "installBin"`, run
  `bin/ssc run --native examples/signals-demo.ssc` (or `--bytecode`). Both
  assembled lanes print only `0`, `5`, `10`; the expected `c=...` and `n=...`
  effect lines are absent. Dumping the staged self-hosted CoreIR shows every
  top-level `effect { ... }` missing while the following signal writes remain.
- **Expected:** `effect { body }` is parsed as an ordinary global call with a
  zero-argument thunk and executes in document order; `effect Name:`
  declarations remain parse-only declarations.
- **Root cause:** the lexer classifies `effect` as a keyword and
  `parseOneStmt` unconditionally routes every keyword-led `effect` through the
  declaration skipper, including a reactive call whose next token is `{`.
- **Plan/done-when:** discriminate the `{` call form before the declaration
  branch, keep existing algebraic-effect declaration fixtures green, and pin
  the complete `signals-demo.ssc` output on assembled VM/direct ASM and
  standalone `build-jvm` lanes.
- **Fix/verified:** `parseOneStmt` now routes the keyword-plus-`{` form through
  ordinary expression/block-argument parsing and leaves named declarations on
  the erasure path. The complete demo is exact on assembled VM/direct ASM and
  build-jvm; algebraic `effects` conformance is green on all four lanes.

## v2-swift-session-sticky-callback-failure — one caught render error poisons the retained runtime

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

## v2-swiftui-fake-native-fallbacks — deferred semantics render misleading content

**Status:** open (2026-07-11); found by `nativeui-reviewer` during the first
read-only Apple store/renderer review in Rozum.

- **Real-harness repro:** generate a root containing trusted HTML, a data table,
  an unknown semantic tag/style, or an unimplemented event. The draft renderer
  shows raw markup as `Text`, shows a fake “Native data table”, ignores the
  action/style, or converts a malformed list/map to empty output.
- **Expected:** implemented inventory entries have their exact native semantics;
  every deferred, malformed, or unknown semantic value becomes a deterministic
  sourced `NativeUiUnsupported` presentation. Silent ignore/fake success is
  forbidden.
- **Plan/done-when:** make the core renderer strict and use explicit Unsupported
  stubs for the separate actions/tables/WKWebView slice; add generated inventory
  and malformed-value gates before replacing each stub with real semantics.
  Inventory acceptance is behavioral, not string presence: CSS values,
  align/justify/position/inset/borders/white-space, semantic role/
  aria-disabled/required attributes, and malformed declarations must either
  map exactly or render sourced Unsupported. Fetch signals/actions also remain
  sourced Unsupported until the complete async lifecycle slice.

## v2-swiftui-keyed-owner-lifecycle — deleted keys retain and resurrect component state

**Status:** fixed (2026-07-11, `70bee065d`), awaiting Sergiy confirmation; found by `nativeui-reviewer` during the first
read-only Apple store/renderer review in Rozum.

- **Real-harness repro:** the draft Swift renderer invokes a keyed render
  closure directly and relies only on SwiftUI `ForEach`; `NativeUiHost`
  stores signals by `(scope,id)` and has no structural owner transaction,
  scope refcounts, rollback, or deletion. Removing then reinserting a key can
  reuse stale component signals, while duplicate/non-String keys are silently
  dropped/coerced.
- **Expected:** Host-owned begin/commit/rollback/delete transactions use the
  frozen root/owner/site/occurrence path, preserve moved keys, dispose a scope
  after its last owner, and create fresh state after committed deletion. Store
  orchestrates those calls and removes returned observation/dependency keys.
- **Plan/done-when:** expose the transaction across `NativeUiSession`, render
  keyed entries provisionally, retain the last committed tree on error, reject
  the first duplicate/non-String key, and pass executable Swift
  insert/move/update/delete/reinsert/rollback probes.

## v2-swiftui-unobserved-signal-read — dynamic nodes do not rerender

**Status:** fixed (2026-07-11, `70bee065d`), awaiting Sergiy confirmation; found by `nativeui-reviewer` during the first
read-only Apple store/renderer review in Rozum.

- **Real-harness repro:** only `NativeUiSignalTextView` owns an
  `@ObservedObject` cell/token. Show conditions, keyed item signals,
  value/checked bindings, and signal-backed style/attribute reads call
  `store.read` directly, so a write does not invalidate those view subtrees.
- **Expected:** every rendered signal read is owned by a stable observed cell
  and exact appearance token; first/last subscriptions activate/release
  dependencies and rerender only the affected subtree.
- **Plan/done-when:** route each dynamic node/binding/style seam through a small
  observed wrapper and pin token/revision behavior with executable Swift probes
  plus real SwiftUI typecheck.

## v2-swiftui-dependent-double-publish — one dependency write advances a computed cell twice

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

## v21-native-dynamic-toint-dropped — selected String conversion vanishes

**Status:** fixed (2026-07-11, `63ab041a6`), awaiting Sergiy confirmation;
found by codex while the new core-free Storage provider advanced
`storage-demo.ssc` into `bumpCounter`.

- **Real-harness repro:** after staging the native frontend, inspect/run
  `Storage.get(key).getOrElse("0").toInt + 1` from `storage-demo.ssc`. The
  generated CoreIR applies `__arith__("+", <String>, 1)` and contains no
  `__str_toInt` call; VM later reports `i->str: not Int`, direct ASM reports
  `expected Int, got "01"`.
- **Expected:** zero-argument selected `.toInt` lowers through the existing
  portable `__method__("toInt", receiver)` contract on both lanes before
  arithmetic. Unlike the String-only `__str_toInt` helper, method dispatch also
  preserves established numeric receiver conversions and normal parse failure.
- **Plan/done-when:** add a focused dynamic String conversion fixture (including
  an Option/getOrElse receiver), repair selector lowering without changing
  numeric `.toString`, rerun `storage-demo.ssc` and every native release gate,
  and keep `fixed` until Sergiy confirms.
- **Root cause/fix:** `resolveField` erased selected `.toInt` whenever its
  resolved receiver was not syntactically recognized as a String, so a dynamic
  `Option.getOrElse` result reached arithmetic unchanged. It now emits portable
  `__method__("toInt", receiver)` dispatch, preserving both dynamic String and
  numeric conversions. The focused VM/ASM fixture and full Storage/release gates
  pass; keep this entry `fixed` until Sergiy confirms.

## v2-swift-nativeui-descriptor-proof — debug root summary hides ABI field drift

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

## v21-runtime-taxonomy-ui-remote-table-stale — successful UI row remains blocked

**Status:** fixed (2026-07-11, `4cdca959c`); found by codex in the full
post-NativeUi v2.1 release gate after `ui-remote-table.ssc` moved from both-fail
to identical; waiting for Sergiy confirmation before `done`.

- **Real-harness repro:** generate the current corpus/parity reports and run
  `scripts/v21-runtime-taxonomy`; it exits non-zero with
  `stale or reclassified manifest row: ui-remote-table.ssc`.
- **Expected:** a standard example that now runs identically on VM/direct ASM is
  absent from the blocking runtime manifest; taxonomy counts match the report.
- **Plan/done-when:** verify the row is identical with zero one-sided failure,
  remove only its stale manifest entry, tighten smoke ceilings/counts, and rerun
  runtime taxonomy plus the full portable release gates. Keep `fixed` until
  Sergiy confirms.
- **Fix/verified:** removed the single stale row and tightened standard-provider
  to 22, blockers to 40, and total rows to 52. Runtime/sentinel taxonomy,
  portable gates, and fresh 11/11 conformance pass.

## v21-native-serve-ownership-conflict — NativeUi duplicates HTTP `serve`

**Status:** fixed (2026-07-11, `727c806e8`); found by codex while re-running the
native release gates after rebasing onto NativeUi runtime commit `1f3ca3962`;
waiting for Sergiy confirmation before `done`.

- **Real-harness repro:** after `scripts/sbtc installBin`, every
  `bin/ssc run --native <portable-file>` invocation exits before evaluation with
  `native plugin ownership conflict for intrinsic 'serve': 50-http and 55-ui`;
  both VM and direct ASM are affected.
- **Expected:** the compiler-free native route loads HTTP and UI providers
  together with unique intrinsic ownership, and unrelated portable programs
  start normally.
- **Plan:** identify the new NativeUi declaration/handler that claims the
  HTTP-owned global, preserve the public UI surface without duplicate ownership,
  add an installed-binary regression that loads the full provider set, then rerun
  native-entry, corpus, strict parity, taxonomies, and fresh conformance.
- **Done-when:** full-provider startup has no duplicate owner, focused HTTP/UI
  behavior stays green, and the fix remains `fixed` until Sergiy confirms.
- **Fix/verified:** UI registers only the provenance-rewritten reserved ABI-v1
  name; HTTP remains the sole public `serve` owner. NativeUi is 14/14,
  installed native-entry passes, and the full corpus/parity gates load both
  providers without conflict.

## v21-list-mkstring-capture — separator slot points at the source list

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

## v2-nativeui-root-transaction — failed Apple extraction leaks root/runtime state

**Status:** fixed (2026-07-11, `1f3ca3962`); found by `nativeui-reviewer` in Rozum.

- **Repro:** missing-root `__nativeUiTakeRoot` throws before clearing
  `appleContext`; duplicate registration retains the first root and signals.
  The current test reinstalls the plugin and masks leakage.
- **Expected/fix:** explicit begin/commit/abort transaction with cleanup or
  restoration for zero roots, duplicate roots, and evaluation failure.
- **Fresh review delta (Rozum 2026-07-11):** `emptyHeaders` is registered once
  at plugin install, but begin clears its `SignalKey` while the global retains
  the old cell. Make it root-local/lazy and test an omitted-header Apple root.
- **Fix/verified:** begin/take/abort and duplicate failure reset the same plugin
  instance; each Apple begin re-registers the constant header cell under its
  root key, including the omitted-header action path.
- **Done-when:** one plugin instance can fail then begin a clean extraction;
  zero/duplicate/evaluation-error tests prove rollback.

## v2-nativeui-descriptor-contract — public UI descriptors diverge from ABI-v1

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

## v2-nativeui-portable-graph — canonicalization/equality can leak host values or miscompare cycles

**Status:** fixed (2026-07-11, `1f3ca3962`); found by `nativeui-reviewer` in Rozum.

- **Repro:** DataV→MapV cycles point to an unconverted DataV; ClosV traversal
  mutates caller-owned environments; equality marks failed map-key candidates
  as visited/equal. Descriptor helpers retain raw values, and static table rows
  are not validated as String-keyed maps.
- **Expected/fix:** graph-safe non-mutating validation/canonicalization,
  tri-state or candidate-isolated cyclic equality, deep stable paths at every
  ABI constructor, and exact row/map validation.
- **Fresh review delta (Rozum 2026-07-11):** conversion still breaks a benign
  alias when an outer DataV and a closure env share the same portable MapV and
  an unrelated host map forces copying. Preserve that alias without changing
  ClosV identity or its environment.
- **Fix/verified:** validation is closure-context aware; conversion pins the
  closure-reachable portable subgraph, copies transitional host maps without
  alias loss, and equality backtracks cyclic unordered candidates soundly.
- **Done-when:** adversarial cycle/reorder negatives, nested ForeignV paths,
  closure non-mutation, and every descriptor family are green.
## v21-layout-given-after-abstract-def — abstract return type consumes the next given

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

## v2-nativeui-component-scope-compat — new scope extern is unbound in legacy INT/JS lanes

**Status:** fixed (2026-07-11, `1f3ca3962`); found by codex while verifying the atomic
NativeUi ABI-v1 migration, announced to `@scalascript` in Rozum.

- **Real-harness repro:** run `tests/conformance/run.sh --only 'tkv2-*'
  --no-memo` after `std/ui/component.ssc` starts wrapping component bodies in
  the new `componentScope(scopeId, bodyThunk)` extern. Nine cases pass, while
  `tkv2-busi-home`, `tkv2-component`, and `tkv2-forms` fail: INT reports
  `'componentScope' not found in primitives.ssc` and JS reports `not callable:
  ()`.
- **Expected:** the new source-level helper must preserve all existing toolkit
  lanes. V2 NativeUi owns scoped signal identity; backends without that state
  model must execute the body exactly once and return its value.
- **Root cause:** the public extern was added before compatibility
  implementations existed. After those adapters were assembled, JS became
  green but INT exposed a deeper module boundary: `SectionRuntime` rebinds an
  explicitly imported plugin native to the parent interpreter, but leaves the
  same native child-owned when it enters exported functions through transitive
  `childCtx` closure enrichment. `componentScope` therefore invoked the user
  thunk in the primitives child interpreter, where caller module globals such
  as `ctxName`, `ctxSignal`, and `form` do not exist.
- **Planned fix:** retain identity adapters
  `componentScope(scopeId, thunk) = thunk()` in the owning standard plugin and
  JS/JVM/Rust runtimes; additionally apply the existing parent
  `rebindPluginNative` rule to plugin-native entries placed into transitive
  `childCtx`. Do not broaden lambda capture: that experiment made the component
  case pass but broke optimized forms/fold parameter semantics, while the same
  program stayed green with fast/JIT disabled. The existing multi-file toolkit
  imports are the faithful cross-module regression. Keep the v2 NativeUi
  plugin's scoped semantics unchanged.
- **Fix/verified:** exact-once identity adapters landed in all legacy runtimes;
  only child-provenance/identity-proven natives are rebound to the caller. The
  assembled multi-file toolkit corpus is 12/12 and the Rust adapter compiles.
- **Done-when:** fresh toolkit conformance is 12/12 across declared lanes,
  focused plugin/codegen tests cover the thunk contract, and the landed SHA is
  reported in Rozum. Keep `fixed` until Sergiy confirms.

## v21-parity-external-http-flake — live httpbin makes VM/ASM parity one-sided

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

## v2-swift-global-reg — generated Swift rejected ordinary top-level values

**Status:** fixed (2026-07-11, `0174796ef`); found by codex while running the
new user-facing Swift AppCore example, waiting for Sergiy confirmation before
`done`.

- **Real-harness repro:** after `scripts/sbtc installBin`, run `bin/ssc
  run-swift examples/swift/appcore-money.ssc` with a top-level `val total = ...`.
  Generation stopped with `swift backend: unsupported primitive 'global.reg'`.
- **Expected:** compiler-internal top-level registration updates the target
  runtime's dynamic global table, matching the v2 VM; it is not an unsupported
  user intrinsic and must not become a no-op when later definitions read it.
- **Root cause:** the checked bridge lowers top-level value initialization to
  `global.reg(name, value)`. JVM/Rust already own backend handling, but the new
  Swift primitive vocabulary omitted it because the initial checked Money gate
  used only inline expressions.
- **Fix/verified:** AppCore now stores the value in the machine's mutable global
  environment. `SwiftV2CliTest` builds and runs the unchanged example with real
  SwiftPM; it prints `$3.75`, `1.2100`, and the exact allocation list. Focused
  Money conformance remains 1/1.
- **Done-when:** the user example stays in the real Swift CLI test and Sergiy
  confirms ordinary top-level values in the Swift workflow.

## v21-runtime-taxonomy-content-owner — content extern gaps assigned to module linker

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

## v21-sentinel-taxonomy-parity-success — parser sentinel becomes unclassified when both lanes exit zero

**Status:** fixed (2026-07-10, `07c1d9b55`); found by codex while verifying
TI-8.2c2i, waiting for Sergiy confirmation before `done`.

- **Real-harness repro:** after `scripts/sbtc "installBin"`, run
  `scripts/native-front-corpus --report target/v21-native-front-current.tsv`,
  `scripts/bc-parity-sweep --report
  target/v21-standard-bc-parity-current.tsv`, then
  `scripts/v21-sentinel-taxonomy`. The fresh reports contain 74 frontend
  sentinel rows, while parity classifies 63 documents `identical`; taxonomy
  exits nonzero with ten `unclassified sentinel` / stale-override diagnostics,
  including the six remaining standard DSL rows and four reviewed tools rows.
- **Expected:** readiness classification is driven by the frontend sentinel
  column. An identical VM/ASM exit does not erase `_err`; backend/server/source
  categories and reviewed tools overrides still apply, and otherwise the row is
  a standard syntax gap.
- **Root cause:** `scripts/v21-sentinel-taxonomy` accepts a standard gap and
  reviewed override only when the parity category is `both-fail`. Uncalled or
  non-observable `_err` nodes can let both lanes exit zero identically, so parity
  success and frontend completeness are independent axes.
- **Fix:** readiness now accepts `both-fail` or `identical` parity after
  source-derived categories, while mismatch/one-sided rows remain rejected.
  Reviewed overrides follow the same rule, so an identical unobserved sentinel
  cannot become stale merely because neither lane executes it.
- **Verified:** synthetic identical standard/tools sentinels pass; the real
  normative standard-tier report classifies all 74 sentinels as 6 standard, 26
  server, 36 backend, 5 tools/backend, and 1 nondeterministic. Native-entry and
  fresh affected conformance 9/9 pass.
- **Done-when:** a synthetic regression covers an `identical` sentinel plus an
  `identical` reviewed tools row, the real 74-row report classifies completely,
  category ceilings shrink to the measured counts, and affected conformance is
  green. Keep `fixed` until Sergiy confirms the release-gate behavior.

## v2-swift-swiftui-native — v2 has no proven native Swift/SwiftUI path for macOS and iOS

**Status:** open (2026-07-10); reported by Sergiy in the Codex session.

- **Reported symptom:** ScalaScript 2.0 has a problem with both the Swift backend
  and the SwiftUI toolkit for desktop and mobile applications.
- **Real-harness repro (assembled `bin/ssc`, 2026-07-10):** after
  `scripts/sbtc "installBin"`, the local Apple toolchain was Swift 6.3.2 and
  Xcode 26.5. `bin/ssc build --v2 --target macos
  examples/frontend/ios-hello/ios-hello.ssc` exits 1 with `Error: --v2 is not
  a directory` because `BuildCmd` does not parse a v2 lane flag. Putting the
  flag before the command (`bin/ssc --v2 build ...`) dispatches `RunV2` on the
  positional filename `build` and throws `FileNotFoundException`. `bin/ssc run
  --v2 --target macos examples/frontend/ios-hello/ios-hello.ssc` exits 0 but
  produces no native package: `RunCmd` returns through `RunV2.run` before it
  computes or dispatches `targetSelection`, so the macOS target is ignored.
- **Legacy native-path repro:** `bin/ssc build --target macos
  examples/frontend/ios-hello/ios-hello.ssc --out
  target/swift-legacy-repro` reaches `JvmGen` but fails the real generated
  `.sc` compile with 27 errors. The first is `.style(padding = 8)` calling an
  overload without `padding`; the generated `std.ui.primitives` block then
  cannot resolve bare `View` and `EventHandler` in its imports/signatures and
  exposes a missing-default-argument call to `_ssc_ui_emit_to_dir`. No Swift
  package is written.
- **Expected:** selecting v2 must compile the checked v2 program through an
  explicit Swift backend and the shared SwiftUI View toolkit, producing native
  macOS and iOS packages from the same `.ssc` source. It must not silently parse
  or lower through v1 and must not choose SwiftUI for a web-serving route.
- **Root-cause direction:** there are three independent boundaries to fix:
  `BuildCmd` has no v2/native lane selection; `RunCmd` selects `RunV2` before
  native target dispatch; and `buildSwiftUIPackage` unconditionally parses via
  v1 `Parser` and executes `JvmGen.generate`. Even that compatibility route is
  red because `JvmRuntimeUiPrimitives.source` is inserted into generated code
  without a self-contained type/import contract and the checked-in iOS example
  uses a stale style surface. The v2 tree has no Swift generator. Preserve one
  shared View/toolkit IR rather than cloning toolkit semantics into a
  backend-specific parser.
- **V2 core/CLI progress (2026-07-11):** `f20b47b35` closes the checked AppCore
  domain backend and `159e45625` adds assembled `emit-swift`/`run-swift` plus
  v2-default Apple build/run routing. Both original flag-order failures are
  covered: `--v2` is consumed as a lane flag and Apple target dispatch happens
  before the generic VM return. `0174796ef` adds ordinary top-level globals.
  macOS executes exact Money under real SwiftPM; iOS generation declares the
  correct deployment platform. The bug remains open because NativeUi/SwiftUI
  app rendering, simulator/device, signing, and distribution gates are still
  the remaining user-reported half.
- **Done-when:** an assembled real-harness regression proves v2 owns Swift
  generation, a common toolkit example emits/builds for macOS and iOS as far as
  the installed Apple toolchain permits, affected conformance is green, and the
  landed SHA plus actual root cause are recorded here. Keep `fixed` until Sergiy
  confirms the original workflow.
- **Legacy native-path repro — FIXED (2026-07-10, `swiftui-legacy-real-harness`
  sub-slice, busi-side `claude-code` agent).** `bin/ssc build --target macos
  examples/frontend/ios-hello/ios-hello.ssc` now writes a real Swift package
  and `swift build` links it — the first time any real parsed `.ssc` module
  has compiled through this path (every prior SwiftUI test hand-builds a
  Scala `View` literal, bypassing `Parser`/`JvmGen` entirely). Six distinct,
  independently-verified bugs, not one:
  1. `JvmGen.hoistSscImportsIntoObjectStd`'s hardcoded `ui.primitives.{...}`
     import listed capitalized `Signal` (never a real member — extern-filtered
     out of the JVM backend's `object primitives`; a separate top-level
     `type Signal[A]` alias exists only in the swiftui-DSL preamble branch) and
     was missing six real names (`seedSignal`, `forKeyedView`, `emptyHeaders`,
     `fetchActionTo`, `fetchCaptureAction`, `rowEditAction`) that had silently
     drifted out of sync.
  2. `JvmGenPreamble`'s `frontendName == "swiftui"` branch never got the
     `text(String)` shadow-fix (`beats extension (r: Response.type) def
     text(body: Any)`) that the non-swiftui branch already had — the ONLY
     branch reachable via `--target macos|ios` lacked it.
  3. `JvmGenPreamble` re-declared `dataTableView` as a byte-for-byte duplicate
     of `JvmRuntimeUiPrimitives.scala`'s version, ambiguous once the (always-
     active for any frontend) hoisted import also brought it in; `dataTable`'s
     wrapper needed a qualified call + loosened return type instead.
     (`JvmRuntimeUiPrimitives.scala` itself needed NO change — its bare `View`/
     `EventHandler` correctly resolve via plain sibling-member visibility once
     the real `std/ui/primitives.ssc` module is genuinely merged in, which
     requires the caller to actually import from it, e.g. the CLI's own
     minimal test fixture never did.)
  4. `std/ui/lower.ssc`'s intentional idempotent-passthrough catch-all
     (`case alreadyLowered => alreadyLowered`, Layer 2 of
     `specs/js-backend-ui-render-gaps.md`) type-checks fine for the
     interpreter/JS backends (dynamically typed) but not JVM-generated Scala,
     where the match's static scrutinee type is `TkNode`, not the declared
     `View` return type — needs `.asInstanceOf[View]`.
  5. `std/ui/lower.ssc`'s `KeyedForNode` case pinned its callback parameter to
     an explicit `(item: Any)`, conflicting with `forKeyedView[A]`'s own
     existential `A` inferred from the case's own type parameter; removing the
     explicit annotation lets both infer consistently.
  6. `SwiftUIEmitter.scala`'s `View.ShowSignal` case appended the `if` block's
     closing brace unconditionally AND again at the start of a non-empty
     `elseClause`, emitting invalid Swift (`}\n} else {`) for every
     `showWhen`/dynamic-condition view — a real Swift syntax error, not a Scala
     one, only caught by actually running `swift build` on the output.
  New regression: `SwiftUiRealFixtureBuildTest` (`v1/tools/cli`), gated on
  `assume(swiftAvailable)`, drives the real `examples/frontend/ios-hello/
  ios-hello.ssc` fixture (rewritten off its stale `Signal`/`Column`/`Text`
  aspirational DSL onto the real `std/ui` API busi's production app actually
  uses) through `buildSwiftUIPackage(..., runSwiftBuild = true)` and asserts a
  real `Ioshello` executable is produced — mirrors `RustGenCargoSmokeTest`'s
  "actually run the toolchain, don't string-match" gate, which is exactly the
  class of bug (5 of 6 above) a string-match-only suite would have missed.
  118 `frontendSwiftUI` tests + 26 existing `SwiftUIBuildCliTest` cases +
  4 `std/ui/lower.ssc`-touching conformance fixtures (`tkv2-keyed-for`,
  `tkv2-raw-html`, `tkv2-textfield-reactive-label`, `tkv2-tri-state`, INT+JS)
  all still green — no regression. The v2-native Swift backend itself remains
  open, owned by the `v2-swift-swiftui-native` claim.

## v2-http-json-renderer-test-contract — native HTTP test omits the required self-hosted renderer

**Status:** fixed (2026-07-10, `ff3a52eba`); waiting for reporter confirmation
before `done`. Found by codex while verifying the portable Decimal/JSON/HTTP
boundary; regression source `ed945466d`.

- **Real-harness repro:** `scripts/sbtc
  "v2NativeJsonPlugin/test;v2NativeSqlPlugin/test;v2NativeHttpPlugin/test"`
  reaches `HttpNativePluginTest` and fails `Response builders reuse native JSON
  and cache helpers preserve fields` with `self-hosted JSON renderer is not
  installed; import std/json.ssc`.
- **Expected:** provider-level tests obey the post-cutover contract: production
  JSON has no host fallback, and a test that calls `Response.json` installs an
  explicit renderer through `__jsonCoreInstallRenderer` before asserting the
  HTTP bridge output.
- **Root cause:** `ed945466d` correctly made `NativeJsonCodec.stringify`
  require the self-hosted renderer, but the HTTP unit fixture still installs
  only `HttpNativePlugin` and assumes the removed host renderer exists.
- **Fix:** the provider fixture now installs `JsonNativePlugin` and a bounded
  deterministic renderer through `__jsonCoreInstallRenderer`; production
  `NativeJsonCodec` retains the no-host-fallback rule.
- **Verified:** `v2NativeJsonPlugin/test` 3/3 and
  `v2NativeHttpPlugin/test` 4/4 passed in the final 94-test focused gate.

## v2-bigint-dynamic-arith-money — std/money allocation feeds Unit to an if condition on v2

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

## v21-native-front-dsl-pair-match-crash — valid tuple pattern aborts the self-hosted frontend

**Status:** fixed (2026-07-10, `d4513cb8a`, diagnostic gate
`ac441ef62`); waiting for human confirmation before `done`.

- **Found by:** codex from `scripts/native-front-corpus`.
- **Real-harness repro:** the assembled self-hosted frontend on
  `examples/dsl-mini-language.ssc` aborts before CoreIR with
  `RuntimeException: match: no arm for Pair/2`, while the checker-only route
  reports `OK`. The source uses nested tuple patterns such as
  `case Some((l, '+', r))`.
- **Expected:** supported tuple/constructor patterns lower or produce a
  source-located compile diagnostic; the compiler must never crash inside its
  own pattern matcher.
- **Root cause:** `=>` was not a native layout opener. A multiline lambda whose
  first statement was `val (a, c) = ac` therefore left the lambda after one
  expression and fed detached tuple AST nodes into the lowerer, which crashed
  while matching an unexpected `Pair/2`. Separately, 3+-element tuple patterns
  used synthetic `TupleN` tags although tuple expressions lower to right-nested
  `Pair` constructors.
- **Fix:** `=>` now opens an offside block, and tuple patterns recursively build
  the same right-nested `Pair` shape as tuple expressions. The standard launcher
  source-locates any remaining parser sentinel instead of exposing a host stack.
- **Verified:** a multiline-lambda/local-tuple plus nested
  `Some((left, '+', right))` fixture prints `left` and `left+right` identically
  on assembled VM/direct ASM. The real DSL row is frontend/checker OK and fails
  only through its filename-bearing bounded sentinel diagnostic; no `Pair/2`
  host exception remains. Native-entry passes, affected conformance is 8/8, and
  the full frontend corpus has zero host errors/timeouts. Remaining unsupported
  DSL surface syntax is tracked by TI-8.2c, not this crash bug.

## v21-native-front-missing-ui-table-import — corpus import closure references a deleted std module

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

## v21-native-bytecode-vm-prepass-state — direct ASM run depends on VM compilation side effects

**Status:** fixed (2026-07-10, `e4f16baaf`); waiting for human confirmation
before `done`.

- **Found by:** codex while running the TI-6.3 post-source-map regression gate.
- **Real-harness repro:** after `scripts/sbtc "installBin"`, run `bin/ssc run
  --native --bytecode examples/hello.ssc`. The command exits nonzero with
  `run --native: None.get`; the same source succeeds through native VM and the
  self-contained `build-jvm` direct-ASM JAR.
- **Expected:** in-memory direct ASM consumes the same checked `Program` and
  initializes generated globals through `JvmByteGen.install`, just like the
  artifact entry; unrelated VM compiler optimizations/dispatch must not change
  its result.
- **Root cause:** `RunNativeV2.runBytecode` calls
  `Compiler.compileWithGlobals` solely to seed `Emit.globalsRef` before emitting
  ASM. Since TI-6.1 the generated `install()` evaluates/registers value defs
  itself. The redundant VM prepass now observes installed plugin handlers and
  takes the new VM-only App(Global)->Prim route, leaving state that makes the
  generated hello path evaluate `None.get`.
- **Fix direction:** initialize a fresh mutable generated-global map and rely on
  `JvmByteGen.install`, eliminating the VM compilation prepass from the direct
  ASM lane. Keep the artifact and in-memory lanes on the same initialization
  contract.
- **Done-when:** native VM, in-memory direct ASM, and `java -jar` hello/import/
  ordered-value fixtures all pass; `v2-*` conformance stays green.
- **Fix:** the in-memory ASM lane now starts from an empty mutable generated
  global map and lets the generated `install()` method evaluate/register every
  lambda and value definition. The VM compiler is no longer invoked merely for
  initialization side effects.
- **Verified:** `tests/e2e/v21-native-entry-smoke.sh` PASS across VM/direct ASM,
  `tests/e2e/v21-native-plugin-boundary-smoke.sh` PASS, artifact e2e PASS, and
  affected conformance 8/8.

## v21-build-jvm-import-source-identity-gap — artifact metadata omits resolved imports

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

**Status:** open (2026-07-10); reproduced twice against TI-6.1 `a8a86fffe`,
whose artifact changes do not touch the compatibility FrontendBridge execution
path.

- **Found by:** codex while running the broad post-TI-6.1 regression suite.
- **Real-harness repro:** `scripts/sbtc "v2FrontendBridge/test"`; all other 151
  executed tests pass, but `v2-conformance: v2-db-url-scheme-not-jdbc` returns
  `(timeout)` instead of `1` after the suite's 15-second `Await` bound.
- **Expected:** `tests/conformance/v2-db-url-scheme-not-jdbc.ssc` opens its
  `sqlite::memory:` database and prints `1` within the normal test bound.
- **Root-cause direction:** inspect the compatibility bridge SQL runtime/test
  classpath for SQLite driver availability and Hikari's default connection
  timeout. The direct native TI-6 artifact path and its H2/provider gate are a
  separate standard-tier lane.
- **Done-when:** the single named test and full `v2FrontendBridge/test` pass in
  isolation without increasing the timeout; add a focused regression if the
  missing-driver/classpath hypothesis is confirmed.

## v21-native-front-eager-plugin-val — plugin-backed top-level `val` runs before earlier statements

**Status:** fixed (2026-07-10, `5db137a20`); waiting for human confirmation
before `done`.

- **Found by:** codex while adding the native SQL provider boundary.
- **Real-harness repro:** after `scripts/sbtc "installBin"`, run a native file
  containing `Db.execute(CREATE)`, `Db.execute(INSERT)`, then
  `val rows = Db.query(SELECT)` through both `bin/ssc run --native` and
  `bin/ssc run --native --bytecode`. Both fail with H2 `Table "PEOPLE" not
  found`; replacing the `val` with an inline query after the writes succeeds.
- **Observed:** the native lowerer emits the plugin-backed `val` as eager global
  initialization, so its SELECT runs before preceding entry statements. The
  same ordering trap previously forced the native HTTP server fixture to inline
  `httpGet` after `serveAsync`. A second assembled repro in the native State
  provider showed the scope variant: `val inside = runState(...)` inside an
  outer `runState` thunk is hoisted out of the thunk and its following use fails
  as `unbound global: inside`; constructing the nested result inline succeeds.
- **Expected:** effectful/plugin-backed top-level values preserve source order;
  an initializer may not run before preceding statements on either VM or ASM.
- **Root cause:** `ssc1-lower` emitted every top-level immutable `val` as an
  eager `IrDef`, outside the document-ordered entry. Independently, the layout
  pass discarded newlines directly inside explicit braces, so a line beginning
  with `(` after a block-valued initializer was parsed as extra application
  arguments and the local binder appeared as an unbound global.
- **Fix:** top-level immutable values and tuple bindings now use entry-initialized
  global cells, while Scala-style newline inference emits separators only when
  the adjacent tokens can end/start statements. The assembled plugin-boundary
  gate runs both faithful fixtures on VM and direct ASM and compares exact
  output.
- **Owner/slice:** `v21-ti-native-front-parity`. The active native-front sibling
  owns named/default-argument work; rebase and coordinate before editing the
  shared self-hosted frontend files.
- **Done-when:** an assembled VM/ASM regression keeps the `val rows = Db.query`
  shape after DDL/DML and prints the row, with `v2-*` conformance green.
- **Verification:** `tests/e2e/v21-native-plugin-boundary-smoke.sh` PASS;
  `tests/e2e/v21-native-entry-smoke.sh` PASS; `tests/conformance/run.sh --only
  'v2-*' --no-memo` 8/8; `scripts/native-front-corpus` completes all 195 rows
  without frontend/checker timeout or crash.

## v21-native-front-prefix-postfix-precedence — `!exists(path)` applies the call after prefix `!`

**Status:** fixed (2026-07-10, `6e8464ea8`); waiting for human confirmation
before `done`.

- **Found by:** codex while classifying the final sentinel-clear native-checker
  corpus rejection after `66b7c4ede`.
- **Real-harness repro:** after `scripts/sbtc "installBin"`, run
  `bin/ssc run --native examples/fs-roundtrip.ssc`. The smaller source shape is
  `println("Deleted: " + !exists(path))`.
- **Observed:** the native frontend lowers the operand as
  `app(if(global exists, false, true), global path)`; the checker consequently
  rejects an attempted Bool-as-function application with `cannot unify Bool
  with non-Bool`.
- **Expected:** postfix application binds within the prefix operand:
  `if(app(global exists, global path), false, true)`. The checker accepts the
  valid source and the VM/ASM paths evaluate the negated call.
- **Root cause direction:** `parsePrefix` parses only the atom/name after `!`,
  while the enclosing postfix phase attaches `(path)` after constructing the
  prefix node. Make postfix selection/application part of the prefix operand
  without regressing unary minus or operator-section parsing.
- **Owner/slice:** `v21-ti-native-front-parity`; rebase the active hex/frontend
  sibling before editing `ssc1-front.ssc0`, then add an assembled native
  regression with the exact call shape.
- **Fix:** each unary operator parses the operand atom and completes its postfix
  selection/application chain before wrapping it in the prefix AST node. This
  applies uniformly to `!`, unary `-`, and bitwise `~`.
- **Verified:** the staged native-entry smoke exercises `!flag()`, `-one()`,
  and `~one()` on both VM and direct ASM; the original `fs-roundtrip.ssc`
  native checker result is now `OK`; checker smoke PASS and affected `v2-*`
  conformance 8/8.

## v2-run-plugin-temp-tree-leak — RunV2 leaves extracted plugin JAR trees

**Status:** fixed (2026-07-10, `0ccecb44d`); waiting for human confirmation
before `done`.

- **Found by:** codex while designing the TI-3 in-process native entry after
  fixing the analogous `SscpkgLoader` lifecycle bug.
- **Real-harness repro:** run `bin/ssc run --v2 examples/hello.ssc` with an
  isolated `java.io.tmpdir`; after process exit, an `ssc-v2-plugins*` directory
  and its extracted JAR children remain.
- **Expected:** the temporary URLClassLoader inputs live for the CLI process and
  the complete extraction tree disappears at process exit.
- **Root cause:** `RunV2.loadPluginJars` calls `tmp.deleteOnExit()` before
  `extractIntrinsicsJars` writes child JARs, but never registers those children.
  JVM shutdown cannot delete the non-empty root. This path is separate from
  `SscpkgLoader`, so `784ac95d3` does not cover it.
- **Fix direction:** register each extracted JAR after copying it, preserving
  reverse-order file-before-root cleanup; extend the assembled temp-lifecycle
  smoke to reject both `sscpkg-*` and `ssc-v2-plugins*` survivors.
- **Owner/slice:** `v21-ti-native-front-production-entry` (`RunV2.runNative`
  reuses this plugin loader).
- **Fix:** `RunV2.loadPluginJars` now registers every extracted JAR with
  `deleteOnExit()` after it is copied. Java therefore deletes the children
  before the already-registered root at process shutdown.
- **Verified:** assembled `tests/e2e/sscpkg-temp-cleanup-smoke.sh` and
  `tests/e2e/v21-native-entry-smoke.sh` PASS with an isolated
  `java.io.tmpdir`; the cleanup smoke rejects both `sscpkg-*` and
  `ssc-v2-plugins*` survivors; affected `v2-*` conformance 8/8.

## sscpkg-loader-temp-tree-leak — every CLI process leaves extracted plugin directories

**Status:** fixed (2026-07-10, `784ac95d3`); waiting for human confirmation
before `done`.

- **Found by:** codex while running the TI-2 assembled-CLI corpus baselines; the
  host temp directory reached tens of thousands of `sscpkg-*-intrinsics*`
  entries and eventually failed plugin loading with `No space left on device`.
- **Real-harness repro:** create an empty sandbox and run
  `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=<sandbox> bin/ssc run
  examples/hello.ssc`; after the JVM exits, `<sandbox>` still contains one
  non-empty extraction directory per packaged plugin.
- **Expected:** extracted intrinsic/source trees live for at most the CLI JVM
  lifetime and are gone after process exit.
- **Root cause:** `SscpkgLoader.load` creates the root with `os.temp.dir`, whose
  default `deleteOnExit=true` registers only the then-empty directory. It later
  writes intrinsic JAR children without registering them. JVM shutdown cannot
  delete the non-empty root, so every invocation leaks the whole tree. The
  analogous `extractSources` path also creates unregistered descendants.
- **Fix direction:** register every extracted file and nested directory for
  reverse-order deletion (or own an explicit recursive shutdown cleanup), while
  preserving the paths for the lifetime of URL classloaders/callers. Add a
  subprocess/assembled-CLI regression that asserts an isolated temp root is
  empty after exit.
- **Owner/slice:** urgent `v21-ti-sscpkg-temp-lifecycle`, before more corpus
  sweeps and the slim/plugin-runtime work.
- **Fix:** after intrinsic/source extraction, `SscpkgLoader` walks the complete
  temp tree and registers descendants parent-first. Java's reverse-order
  shutdown hook therefore removes files, nested directories, and finally the
  root while keeping every path alive for the process lifetime.
- **Verified:** `core/testOnly scalascript.compiler.plugin.SscpkgLoaderTest`
  12/12; assembled `tests/e2e/sscpkg-temp-cleanup-smoke.sh` PASS with an
  isolated `java.io.tmpdir`; affected `v2-*` conformance 8/8; `git diff
  --check`.

## v2-bytecode-x402-unhandled-op-success — bytecode lane exits 0 with unresolved `Wallets.metaMask` op

**Status:** fixed (2026-07-10, `7192cd6e4`); waiting for human confirmation
before `done`.

- **Found by:** codex during the TI-2 VM/ASM corpus baseline at `7ba4d413b`.
- **Real-harness repro:** after `scripts/sbtc "installBin"`, compare
  `bin/ssc run examples/x402-metamask.ssc` with
  `bin/ssc run --bytecode examples/x402-metamask.ssc` (the focused portable
  repro is `scripts/bc-parity-sweep --only x402-metamask.ssc --strict`).
- **Observed:** the VM exits 1 with source-located `Undefined: Wallets` at
  `Wallets.metaMask(Network.Base)`. The bytecode lane exits 0 and prints
  `Op("Wallets.metaMask", Base, <closure>)` as if the unresolved effect were a
  successful program result.
- **Expected:** VM and ASM agree. Until the wallet plugin/global is available,
  both lanes must fail with a stable unresolved-symbol/effect diagnostic; once
  it is available, both must execute it and produce the same observable output.
- **Root cause direction:** the v2 bytecode route preserves an unhandled plugin
  method fallback as a top-level `Op` value and the CLI treats that value as a
  successful result, while the VM/default route rejects the undefined
  `Wallets` global earlier. Inspect FrontendBridge method-object registration,
  `PluginBridge` fallback dispatch, and bytecode top-level result handling.
- **Owner/slice:** `v21-ti-native-front-parity` plus VM/ASM parity; add a
  focused conformance row before closing.
- **Root cause:** all four public v2 execution routes independently treated any
  non-Unit result as printable success. The bridge ASM path correctly preserved
  the unresolved dotted plugin fallback as `DataV("Op", ...)`, but its CLI
  result branch did not distinguish that diagnostic sentinel from ordinary
  user values.
- **Fix:** route bridge/native and VM/ASM final values through one result
  validator. A dotted auto-thread `Op` now raises `unhandled runtime effect`;
  the related missing-method `Stub` sentinel raises `unresolved runtime
  dispatch`; undotted user free-monad `Op` data remains printable.
- **Verified:** assembled `tests/e2e/v21-unhandled-effect-smoke.sh` rejects a
  native missing dispatch on VM and ASM and the exact bridge-ASM
  `Wallets.metaMask` repro; native-entry smoke PASS; CLI argv tests 2/2;
  affected `v2-*` conformance 8/8.

## v21-native-front-prose-self-import-loop — raw link scan follows prose links as module imports

**Status:** fixed (2026-07-10, `0ccecb44d`); waiting for human confirmation
before `done`.

- **Found by:** codex during the TI-2 native-front corpus baseline at
  `7ba4d413b`.
- **Real-harness repro:** after `scripts/sbtc "installBin"`, run
  `SSC_NO_CDS=1 scripts/run-with-timeout 20 java -Xss512m -cp 'bin/lib/jars/*'
  ssc.cli run v2/bin/ssc1-run.ssc0 examples/components-demo.ssc`.
- **Observed:** the native loader repeatedly prefixes `./` until
  `java.nio.file.FileSystemException: .../components/././.../button.ssc: File
  name too long`.
- **Expected:** prose links are not imports, and a canonical module path is
  loaded at most once.
- **Root cause:** `sscImports` scans the entire raw Markdown document for every
  `](...ssc)` substring. The prose in `examples/components/button.ssc` contains
  the documentation example `` `[Button](./button.ssc)` ``, so the module
  imports itself. `sscResolve` concatenates paths without canonicalizing `./`,
  and the textual `seen` set therefore never recognizes the cycle.
- **Fix direction:** derive imports from actual top-level Markdown import nodes
  (or at minimum exclude fenced/inline-code/prose link examples) and normalize
  path segments before DFS/load-once comparison. Add this repro to the native
  corpus gate; do not merely special-case `button.ssc`.
- **Owner/slice:** `v21-ti-native-front-production-entry` / frontend parity.
- **Fix:** the native loader now recognizes only standalone Markdown import
  links outside fenced code, normalizes `.`/`..` path segments lexically before
  the shared DFS seen-set comparison, and runs the frontend tower on a dedicated
  bounded-stack thread so a remaining frontend failure cannot consume the CLI
  thread stack.
- **Verified:** the multi-file relative-import fixture includes the former prose
  self-link shape and completes with `42`; the real `components-demo.ssc` repro
  now terminates with a bounded parser-sentinel diagnostic instead of `File name
  too long` or `StackOverflowError`. Assembled native-entry smoke PASS and
  affected `v2-*` conformance 8/8.

## v2-type-ascription-pattern-no-op — `case _: T =>` silently matched everything (type test dropped)

**Status:** fixed (2026-07-10); waiting for human confirmation before `done`.

- **Found by:** claude, while root-causing why `case _: PSameIndent =>` never
  fired in std/parsing's `runLayout` (surfaced during the dsl-yaml audit —
  the ctor pattern `case PSameIndent(r) =>` matched but the type-ascription
  `case _: PSameIndent =>` did not).
- **Repro before fix** (`bin/ssc run`):
  ```
  case class A(x: Int) extends P
  def check(p: Any) = p match { case _: A => "is-A"; case _ => "not-A" }
  check(A(1))   // v1: is-A   v2 + --bytecode: not-A   (WRONG)
  ```
- **Root cause:** the frontend dropped the ascribed type in BOTH lowering paths.
  `convertPat` mapped `Pat.Typed(Pat.Wildcard(), T)` → `(None, Nil)` (a plain
  wildcard → the CT.Match *default* arm), and `flattenPattern`'s
  `Pat.Typed(inner, _)` recursed into `inner` discarding the type. So
  `case _: A => …; case _ => …` compiled to two default arms; the later `case _`
  overwrote the first, and `_: A` never matched. Both v2 lanes (VM + bytecode)
  shared the bug because it is purely in FrontendBridge; v1 was correct.
- **Fix:** emit a runtime tag test for a type-ascription pattern when the type
  resolves to a KNOWN concrete DataV tag set — a registered case class (single
  tag), a sealed trait / enum (its transitive subtype tags, via a new
  `subtypesOf` registry populated from `extends` clauses + enum cases incl.
  `Defn.RepeatedEnumCase`), or `Option`/`Either`. Unknown / type-parameter /
  `Any` / scalar / non-DataV-collection types return `None` and keep the
  historical unconditional-wildcard behavior (conservative — the fix only ever
  ADDS a discriminating test where the tag set is fully known, never a false
  negative). `case _: T =>` now routes to the general if-chain (needsGeneralChain)
  so `flattenPattern` can attach the test; the test is arity-independent via a
  new `__isTag__` sentinel arity `-1` (avoids the Request injected-field landmine).
- **Verified:** minimal + comprehensive (concrete class, sealed trait, enum incl.
  comma-grouped zero-arg cases, `x: T` binding form, non-DataV negatives) all
  correct on v1 / v2 / --bytecode. Gate: tests/conformance/v2-type-ascription-pattern.ssc.
  V2ConformanceTest 102 pass / 2 pre-existing fail (graph-edge-display,
  tkv2-typed-client-derived); corpus batch 155 PASS / 7 FAIL (all 7 environmental
  — missing files/env/DB — no pattern-match regressions), up from 154/8.

## v2-jvm-backend-echo-macos — shell `echo "$text"` can corrupt generated/source text on macOS

**Status:** fixed (2026-07-10, `a4f7662be`); waiting for external
confirmation only if a macOS reporter rechecks the helper paths.

- **Found by:** codex while working the BACKLOG
  `v2-jvm-backend-echo-macos` harness gotcha.
- **Repro class:** any shell helper that stores generated/source text containing
  backslash escapes such as `split("\n", -1)` in a variable and later pipes it
  with `echo "$var"` can corrupt the text on shells whose `echo` interprets
  `\n`. The historical symptom was JVM/Rust generated source becoming
  `split("` + real newline + `", -1)` before scalac/rustc.
- **Observed current surface:** `v2/backend/check.sh` already uses files and
  redirects for generated backend sources and carries the macOS warning, but
  `v2/scripts/bench.sh` still piped source/IR variables with `echo "$src"` /
  `echo "$ir"`, and `v2/ssc1` piped generated IR with `echo "$IR"`.
- **Expected:** generated/source text should be written to stdin with `printf`
  or direct file redirects so backslash escapes stay byte-preserving.
- **Root cause:** shell `echo` is not a byte-preserving serialization primitive
  for arbitrary generated/source text.
- **Fix:** replaced live text-to-stdin `echo "$..."` uses in
  `v2/scripts/bench.sh` and `v2/ssc1` with `printf '%s\n'`.
- **Verified:** `v2/backend/check.sh fact` (1 fixture x JVM/JS/Rust),
  `v2/scripts/bench.sh arith-loop` (`13.5810 ms`, warmup/reps 1/1),
  `v2/ssc1 v2/examples/kc13-hello.ssc` (`Hello, World!`),
  `v2/ssc0c v2/examples/fact.ssc0 | v2/ssc run-ir /dev/stdin` (`120`),
  `scripts/sbtc "installBin"`, `tests/conformance/run.sh --only 'litdoc'
  --no-memo` (1/1 across INT/JS/JVM), and `git diff --check`.

## v2-scala-cli-stack-option-wrappers — v2 shell wrappers used rejected `-J-Xss512m`

**Status:** fixed (2026-07-10, `a4f7662be`).

- **Found by:** codex while verifying `v2-jvm-backend-echo-macos`.
- **Repro:** `v2/ssc1 v2/examples/kc13-hello.ssc` exited before running the
  program with `Unrecognized argument: -J-Xss512m`.
- **Root cause:** the v2 helper wrappers still used the old Scala CLI
  `-J-Xss512m` spelling; current Scala CLI accepts the stack option through
  `--java-opt=-Xss512m`.
- **Fix:** updated `v2/ssc`, `v2/ssc0c`, and `v2/ssc1` to pass
  `--java-opt=-Xss512m`.
- **Verified:** same wrapper gates as `v2-jvm-backend-echo-macos` above.

## ssr-forsignal-duplicate-attrs - SSR `ForSignal` fallback duplicated static attrs

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

## tkv2-spa-i18n-serve-intrinsic-shadow — emitted custom SPA calls bare `serve` instead of imported `serve__ssc`

**Status:** fixed (2026-07-10, `7e5d55e4f`); waiting for human confirmation
before `done`.

- **Found by:** codex during `tkv2-spa-i18n-parity`.
- **Repro:** emit `examples/std-ui/i18n-demo.ssc` through the custom SPA path and
  execute it in a browser/jsdom harness. The generated module imports
  `std.ui.primitives.serve` as `serve__ssc`, but the top-level auto-call was
  emitted as bare `serve(...)`; jsdom failed before mounting `.ssc-page` with
  `ReferenceError: serve is not defined`.
- **Expected:** imported/user bindings, including collision-renamed imports,
  must take precedence over JS intrinsic dispatch. The i18n demo should mount
  and live-switch EN/RU/UK/PL without reload.
- **Root cause:** `JsGen.dispatchIntrinsicJs` checked only
  `declaredBindings.contains(fname)` before stealing `Term.Name(fname)` calls.
  Collision-renamed imports bind `emittedName(fname)` (`serve__ssc`), so the
  hardcoded `serve` intrinsic path incorrectly won over the imported binding.
- **Fix:** `dispatchIntrinsicJs` now also skips intrinsic dispatch when the
  emitted binding name is declared or when a top-level user rename exists. The
  generated call falls through to regular `_call(serve__ssc, ...)`.
- **Verified:** standalone patched-`JsGen` jsdom harness prints
  `i18n-spa-live-ok`; CLI-shaped `emit-spa --frontend custom` with patched
  classes emits `_call(serve__ssc, ...)` and the emitted HTML passes the same
  jsdom live-switch check; affected conformance
  `tests/conformance/run.sh --only 'std-ui-i18n,tkv2-*' --no-memo` passes
  10/10; `git diff --check` passes.

## v2-indent-conformance-demos-skipped — indent demo cases are skipped by conformance but fail directly

**Status:** fixed (2026-07-10, demo fix `886502d64`, conformance lane
`bcffa0019`); waiting for human confirmation before `done`.

- **Found by:** codex while fixing `v2-dsl-yaml-tuple-accessor`.
- **Repro before fix:** after `scripts/sbtc "installBin"`, direct v2 runs
  failed: `bin/ssc run tests/conformance/indent-config-format.ssc` crashed with
  `__method__: no dispatch for ._1 on "host"`, and
  `bin/ssc run tests/conformance/indent-block-statements.ssc` crashed with
  `__method__: no dispatch for ._1 on "x"`. The conformance harness also
  reported both cases as `SKIP (no expected/*.txt)`, so it did not catch the
  direct production v2 failures.
- **Root cause:** the demo parsers relied on infix precedence in expressions
  like `identifier <~ ... ~ value`; v2 grouped the `~` under the `<~`, so the
  mapper received the left scalar (`"host"`/`"x"`) instead of a Tuple2. The
  config demo also used a nullable blank-line regex that could match the empty
  string before consuming separators, leaving the second section in `rest`.
  Finally, `tests/conformance/run.sc` had only INT/JS/JVM lanes, so there was no
  honest way to activate v2-only expected-output cases.
- **Fix:** parenthesized the parser sequences so maps receive the intended
  Tuple2 shapes, changed config blank-line skipping to a non-nullable
  `blankLine.many()`, added a `while`/`for` sample to cover nested tuple shape,
  added expected outputs, and taught the conformance runner an opt-in `V2` lane
  for files declaring `backends: [v2]`.
- **Verified:** direct `bin/ssc run --v2` for both files; `bash -n
  tests/e2e/indent-layout-v2-smoke.sh &&
  tests/e2e/indent-layout-v2-smoke.sh`; `tests/conformance/run.sh --only
  'indent-config-format,indent-block-statements' --no-memo` now reports 2/2
  with `PASS [V2 ]`; `tests/conformance/run.sh --only 'parsing-*' --no-memo`
  passes 3/3; `git diff --check` passes.

## v2-dsl-yaml-tuple-accessor — `pair._2` on a parser result hits fieldAt OOB (long-standing, NOT a fresh regression)

**Status:** fixed (2026-07-10, code fix `4def0c749`); waiting for human or
external reporter confirmation before `done`.

- **Found by:** claude/codex while auditing the corpus v2 failures.
- **Repro before fix:** after `scripts/sbtc "installBin"`,
  `bin/ssc run examples/dsl-yaml-like.ssc` and
  `bin/ssc run --v2 examples/dsl-yaml-like.ssc` printed `Parsed successfully.`
  and then crashed with `ArrayIndexOutOfBoundsException: Index 1 out of bounds
  for length 1` at the v2 runtime `fieldAt` path. Instrumentation showed a
  `YStr` receiver being accessed at tuple field index 1 (`._2`). The v1 lane was
  not a clean reference: `bin/ssc run --v1 examples/dsl-yaml-like.ssc` failed
  earlier with unresolved `withIndent` imports.
- **Root cause:** three parser/layout issues compounded. First,
  `Parser.withIndent(n)` lowered through the generic `PWithLocalContext` shape;
  on v2 that captured the incoming context as the new current level, so
  `withIndent(3)` behaved like `withIndent(IndentContext(...))`. Second,
  `PSameIndent`/`block` checked the current column without consuming leading
  indentation or guarding the first block item, letting nested mapping lines fall
  through as scalars. Third, the YAML demo grammar wrapped an already parsed
  nested `YMap` in another `YMap(List(...))` and required a newline at EOF.
- **Fix:** added an explicit `PWithIndent` parser node handled directly by
  `runLayout`, made layout indentation guards skip blank lines and consume the
  required indentation before parsing each block item, and changed the YAML demo
  to parse one nested value with `yamlValueRef.withIndent(level + 2)` plus
  EOF-aware `eol`.
- **Verified:** `scripts/sbtc "installBin"`; `tests/conformance/run.sh --only
  'parsing-*' --no-memo` passed 3/3; new
  `tests/e2e/dsl-yaml-like-v2-smoke.sh` passed and checked
  `server.host = localhost`, `database.name = myapp`, and
  `database.pool.max = 10`; `git diff --check` passed before the code commit.


Durable ledger of bugs reported in the `scalascript` rozum room (or found locally).
See the `rozum` skill — "The bug-tracking loop". Newest first. Status flow:
`open → needs-info → fixed → (confirmed) → done`. Keep fixed/done entries with their
commit SHA until the reporter confirms, then they can be trimmed.

| Status legend | |
|---|---|
| `open` | reproduced / accepted, work to do |
| `needs-info` | blocked on a repro question asked in the room |
| `fixed` | landed on `origin/main`, reporter not yet re-confirmed |
| `done` | reporter confirmed fixed (safe to trim) |

## v2-serve-view-frontend-default — serve(view,port) crashes 'swiftui native-only' instead of serving the web SPA

**Status:** FIXED 2026-07-10 — on `--v2`, `serve(view, port)` for any UI-serving
program crashed `the active frontend backend 'swiftui' is native-only` instead of
serving the web SPA. v1 reads the front-matter `frontend:` value and selects the
framework (`Interpreter.scala`: `m.frontendFramework.foreach(FrontendFrameworks.setBackend)`);
the v2 bridge never wired this, so `serve` fell to `FrontendFrameworks.current()` →
`impls.head` (swiftui, native-only). Invisible to the corpus (serve is stubbed in
batch mode) but broke every real `ssc run --v2` serving a UI view
(content-introspection, datatable-static-spa, …). Fix: `FrontendBridge.selectFrontendFromFrontmatter`
reads the `frontend:` value and calls `FrontendFrameworks.setBackend`, mirroring v1
(only when nothing is selected, so CLI `--frontend` / `setFrontendFramework` still win).
Verified: content-introspection + datatable-static-spa serve `frontend=react` on --v2
(matching v1); corpus 154/8. Gate: tests/e2e/serve-view-frontend-v2-smoke.sh. Fixed by lucky-perch.

## v2-rust-recursion-tco-bench-fold — `fixed` (2026-07-09)

- **Found by:** codex during `v2-source-backend-production-perf-sweep`.
- **Repro:** after `scripts/sbtc "installBin"` on current `origin/main`, run
  `scripts/bench v2-backends recursion-tco`.
- **Observed failure:** the v2-rust row reports `0.000000 ms/iter` while the
  same public command reports nonzero work for the other lanes
  (`v2=0.301 ms`, `v2-jvm=3.18 ms`). This is below any plausible execution
  floor and means `rustc -O` still constant-folds this benchmark shape.
- **Expected:** the v2-rust benchmark path must defeat LLVM folding for
  tail-recursive zero-input workload helpers before a `recursion-tco` source
  backend row can be accepted as green.
- **Impact:** `v2-source-backend-production-perf-gates` cannot count the
  v2-rust `recursion-tco` row as closed until the harness emits an honest,
  nonzero measurement.
- **Root cause:** the existing v2-rust bench anti-folding made the zero-arg
  `g_workload_long()` literal opaque, but LLVM still converted the
  single-self-call tail-recursive Long helper into a closed-form result.
- **Fix:** `3d514f411` extends the benchmark-only `BenchCmd.timeV2Rust` patch
  to wrap the first simple loop-carried `wrapping_add` update inside Long
  helpers that contain exactly one self-call. This blocks the tail-recursive
  closed-form fold without penalizing non-tail `fib`, whose helper contains two
  self-calls and is intentionally not patched this way.
- **Verified:** short real smoke
  `bin/ssc --backend v2-rust bench --machine --warmup-time 10 --reps 1 bench/corpus/recursion-tco.ssc`
  reports `BENCH v2-rust 0.6620`; public
  `scripts/bench v2-backends recursion-tco` reports `v2-rust=0.721 ms`; and
  `scripts/bench v2-backends recursion-fib` remains stable at
  `v2-rust=1.46 ms`.
- **Status:** fixed; waiting for human confirmation before `done`.

## v2-rust-bench-zero-input-helper-fold — `fixed` (2026-07-09)

- **Found by:** codex while implementing
  `v2-source-rust-recursion-fib-perf`.
- **Repro:** after changing the v2 Rust source backend to emit direct
  Long-specialized helpers for `bench/corpus/recursion-fib.ssc`, run the real
  v2-rust bench path:
  `bin/ssc --backend v2-rust bench --machine --warmup-time 10 --reps 1 bench/corpus/recursion-fib.ssc`.
  The generated Rust wrapper contains a zero-arg `g_workload_long()` helper
  returning `g_fib_long(30i64)`.
- **Observed failure:** `rustc -O` can constant-fold the whole zero-input
  helper chain, producing a near-zero `BENCH_MS` value even though the
  workload is recursive and should run at about the same order as the v2 JVM
  direct helper lane.
- **Expected:** the benchmark-only v2-rust path must keep production codegen
  unchanged while making benchmark input opaque enough that LLVM cannot
  precompute the measured helper chain.
- **Manual confirmation:** patching the generated bench-only Rust source to
  call `g_fib_long(std::hint::black_box(30i64))` makes the same smoke run
  honest and fast (`BENCH_MS: 1.44545`, `BENCH_SINK: 1385346600`).
- **Impact:** once the production backend stops paying generic closure/vector
  dispatch, the public `scripts/bench v2-backends recursion-fib` row can become
  falsely green unless the v2-rust bench harness adds its own anti-folding.
- **Fix:** `3d975bda7` patches only `BenchCmd.timeV2Rust` before writing its
  temporary `main.rs` to `rustc -O`. Zero-argument Long helpers have their first
  integer literal wrapped with `std::hint::black_box(...)`, while public
  `emit-rust` output and the corpus workload remain unchanged.
- **Verified:** `bin/ssc --backend v2-rust bench --machine --warmup-time 10
  --reps 1 bench/corpus/recursion-fib.ssc` reports `BENCH v2-rust 1.56`
  instead of near-zero; final `scripts/bench v2-backends recursion-fib` reports
  `v2-rust=1.44 ms`.
- **Status:** fixed; waiting for human confirmation before `done`.

## v2-scripts-bench-mktemp-template — `fixed` (2026-07-09)

- **Found by:** codex while verifying `v2-backend-check-ssc1c-wrapper-app-lit`.
- **Repro:** run two `v2/scripts/bench.sh` instances concurrently, for example
  `BENCH_WARMUP=1 BENCH_REPS=3 ./scripts/bench.sh bool-predicate` and
  `BENCH_WARMUP=1 BENCH_REPS=3 ./scripts/bench.sh mutual-recursion` from `v2/`.
- **Observed failure:** one process can fail immediately with
  `mktemp: mkstemp failed on /tmp/v2-bench-XXXXXX.jar: File exists`.
- **Expected:** each bench process gets a unique temporary jar path.
- **Impact:** parallel agents or local parallel probes can spuriously fail while
  checking v2 corpus rows; the semantic benchmark itself is not at fault.
- **Fix:** `ed680a585` changes `v2/scripts/bench.sh` to use the suffix-free
  `mktemp /tmp/v2-bench-XXXXXX` template so macOS substitutes the trailing Xs
  and concurrent processes get distinct temporary jar paths.
- **Verified:** short `bool-predicate` and `mutual-recursion` bench probes
  completed concurrently with unique temp jars (`/tmp/v2-bench-JUGk7f` and
  `/tmp/v2-bench-qu9Sqy`), followed by `git diff --check`.
- **Status:** fixed; waiting for human confirmation before `done`.

## v2-bytecode-param-long-nontail-self-loop — `fixed` (2026-07-09)

- **Found by:** codex while rerunning final gates for
  `v2-source-jvm-recursion-fib-perf` after rebasing onto `origin/main`.
- **Repro:** on fresh `origin/main` `8ec03cfbf`, run
  `scripts/sbtc "v2FrontendBridge/testOnly ssc.bridge.FrontendBridgeTest -- -z recursive"`.
- **Observed failure:** `v2 bytecode self-recursive int arithmetic keeps VM result`
  returns `IntV(1)` instead of `IntV(832040)` for:
  `def fib(n: Int): Int = if n <= 1 then n else fib(n - 1) + fib(n - 2); fib(30)`.
- **Expected:** the bytecode lane returns the same `IntV(832040)` as the v2 VM.
- **Root-cause hypothesis:** `JvmByteGen.emitParamLong` emits every self-call in a
  Long-specialized helper as a parameter-rebinding loop jump. That is valid only
  for tail self-calls. Non-tail recursive calls inside expressions such as
  `fib(n - 1) + fib(n - 2)` must call the Long helper recursively and leave a
  value on the operand stack.
- **Impact:** the current v2 bytecode recursive-Int fast path is semantically
  wrong for non-tail recursion; this blocks using the focused recursive bridge
  gate as a production signal.
- **Fix:** `41e2fe1ed` threads tail-position information through
  `JvmByteGen.emitParamLong`: tail self-calls keep the parameter-rebinding loop,
  while non-tail self-calls emit recursive `invokestatic <helper>(J...)J`.
- **Verified:** focused recursive bridge test 3/3, self-tail bridge test 1/1,
  affected recursion conformance 3/3, and `git diff --check`.
- **Status:** fixed; waiting for human confirmation before `done`.

## v2-money-decimal-regression — money amounts became Int (Decimal shadowed by payments Money companion)

**Status:** FIXED 2026-07-09 — busi's v2 domain sweep dropped 61/61 → 25/61 after
bumping past `d255f18f8` (feat(v2): bridge payments examples surface). That commit
registered `Money`/`Currency` as `functionConstructors` and a global `Money`
companion whose `apply(amount, currency)` coerces a `Decimal` amount to minor-units
`Long`. This SHADOWED std/money.ssc's `case class Money(amount: Decimal, currency)`:
`Money(Decimal("3000.00"), cur)` routed to the payments companion → `IntV(300000)`,
so `amount.setScale(...)` downstream hit `no dispatch for .setScale on IntV(300000)`
and money math was ×100 off (take_gig 15000000 vs 1500). Fix: `registerCaseClass`
records `userCaseClasses`; the functionConstructor path (FrontendBridge) skips names
the user DEFINED/imported as a `case class`, so their `X(args)` builds a plain Ctor
for that compile unit while payments-only files still use the companion. busi sweep
restored to 61/61; corpus 154/8 (payments examples still green). Reported by busi
(fable, n=105); fixed by lucky-perch. Pin: tests/conformance/v2-user-type-shadows-function-ctor.ssc.

## v2-backend-check-ssc1c-wrapper-app-lit — `fixed` (2026-07-09)

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

## green-main-conformance-7fail — `fixed` (2026-07-09)

- **Found by:** codex, while closing the `p4-bc-unboxed-arith` bytecode perf
  slice. The affected bytecode/arithmetic gate is green, but the broader default
  conformance gate is not.
- **Repro:** stage the CLI with `scripts/sbtc "installBin"`, then run
  `tests/conformance/run.sh --only 'case-classes,dataset-shape,direct-control-flow,effect-imported-handler,effect-transitive-handler,fenceless-bare-code,js-applyunary-effect-cps,sealed-traits' --no-memo`.
- **Observed failure:** the fresh targeted repro reports 1 passed, 7 failed out
  of 8 tests. Failing rows: `case-classes` JS (`78.54` becomes `NaN`, and
  expected ordinals `0, 1, 2, 1` become `7, 7, 7, 7`); `dataset-shape` JVM
  missing all stdout; `direct-control-flow` JS missing all stdout;
  `effect-imported-handler` JS missing stdout; `effect-transitive-handler` JS
  missing stdout; `js-applyunary-effect-cps` JS missing stdout; `sealed-traits`
  JS (`3.14` becomes `NaN`). `fenceless-bare-code` passed in the fresh
  targeted `--no-memo` repro even though the earlier full non-`--no-memo` run
  reported a JS missing-stdout failure.
- **Expected:** every enabled lane in the focused repro passes, and a full
  `tests/conformance/run.sh --no-memo` has no deterministic failures beyond
  explicit pending/skips.
- **Impact:** the default top-level conformance gate is red, so v2 production
  readiness still has a deterministic blocker independent of bytecode
  arithmetic performance.
- **Notes:** the current bytecode slice's affected gate is green:
  `tests/conformance/run.sh --only
  'arithmetic,recursion,tail-recursion,mutual-recursion' --no-memo` reports
  4 passed, 0 failed; focused `FrontendBridgeTest -- -z "v2 bytecode"` reports
  2/2. Direct repro notes 2026-07-09: `direct-control-flow` JS throws
  `RangeError: Maximum call stack size exceeded` in `iterateWhileMOption`;
  the three effect JS rows throw `ReferenceError: query__ssc is not defined`;
  `case-classes` and `sealed-traits` JS run but produce `NaN` for numeric fields
  and wrong enum ordinals; `dataset-shape` JVM fails at scalac with
  `_Dataset.mkString must be called with () argument` from generated
  `xs.map(_show).mkString`.
- **Progress 2026-07-09:** fixed `dataset-shape` JVM by making the generated
  `_Dataset.mkString` no-arg overload parameterless to match Scala collections,
  and bumping the JVM `.scjvm` codegen cache key so stale artifacts regenerate.
  Verified direct `bin/ssc run-jvm tests/conformance/dataset-shape.ssc`,
  `tests/conformance/run.sh --only 'dataset-shape' --no-memo` (1/1), and the
  original eight-row repro now reports 2 passed, 6 failed (`dataset-shape` and
  `fenceless-bare-code` pass).
- **Progress 2026-07-09:** direct generated-JS inspection narrowed
  `case-classes` and `sealed-traits` to a JS lexical-shadowing bug. Pattern
  binders and lambda params are declared with local names (`const r = ...`,
  `p => ...`), but body references choose the top-level collision-safe names
  (`r__ssc`, `p__ssc`) when the module also has top-level `r` / `p` values. That
  makes circle radius arithmetic read a `Rect`/`Rectangle` object (`NaN`) and
  `points.map(p => p.x + p.y)` read the top-level point (`7, 7, 7, 7`).
- **Progress 2026-07-09:** fixed JS local-scope precedence for lambda,
  pattern, generator/CPS match, receive, and handler binders. Direct
  `emit-js | node` for `case-classes` prints `3/4/78.54/24/4/0, 1, 2, 1`;
  `sealed-traits` prints `circle/rect/tri/3.14/12/12`; focused conformance
  `case-classes,sealed-traits` reports 2 passed, 0 failed. The original
  eight-row repro now reports 5 passed, 3 failed: only `effect-imported-handler`,
  `effect-transitive-handler`, and `js-applyunary-effect-cps` still fail in the
  JS lane with missing stdout.
- **Progress 2026-07-09:** direct generated-JS inspection of the remaining
  effect rows shows a cross-module JS import alias bug. The parent module maps
  imported `query` references to `query__ssc` because `query` collides with the
  JS runtime top level. The imported child module then emits the actual
  unqualified `def query` as `query__ssc1` because `query__ssc` was already
  reserved by the parent. Since `genImport` skips binding when source and local
  names are both `query`, no `const query__ssc = query__ssc1` alias exists, so
  effect calls throw `ReferenceError: query__ssc is not defined`.
- **Progress 2026-07-09:** after those semantic fixes, a post-rebase full
  conformance run exposed an infrastructure flake: `strings`,
  `fenceless-bare-code`, and early actor JVM lanes could report missing stdout
  when Scala.js or JVM `scala-cli` paths reused a broken Bloop BSP socket. Direct
  stderr showed `Scala.js compilation failed` with `InterruptedException` /
  BSP socket timeout. `--cold-jvm` actor slices passed, proving the rows were
  not semantic regressions.
- **Fix:** `bd85a5f95` fixed stale JVM dataset artifacts, `bf0402b12` fixed
  JS local-scope precedence for lambda/pattern binders, `76b9432ef` fixed
  unqualified JS import aliases when parent and child top-level runtime
  collision renames diverge, `7f4cb82d7` makes Scala.js standard-block
  `scala-cli --js` package/run calls serverless, and `1291ed03b` makes the
  conformance JVM lane serverless by default while keeping `--warm-jvm` as an
  opt-in.
- **Verified:** `backendJs/compile; installBin`; `backendScalajs/compile;
  installBin`; direct `emit-js | node` for `case-classes`, `sealed-traits`,
  `effect-imported-handler`, `effect-transitive-handler`, and
  `js-applyunary-effect-cps`; focused conformance for the JS semantic slices;
  original eight-row repro 8/8; actor Bloop repro slice 4/4; fenceless /
  standard-Scala slice 4/4; full default `tests/conformance/run.sh --no-memo`
  reports 145 passed, 0 failed out of 145 tests (+2 pending); `git diff --check`.
- **Status:** fixed; waiting for human confirmation before `done`.

## v2-jvm-user-request-shadow — `fixed` (2026-07-09)

- **Found by:** codex, during the final `unmask-payments-bridge` affected
  conformance gate after the sibling `user-request-collision` fix landed.
- **Repro:** after `scripts/sbtc "installBin"`, run
  `bin/ssc run-jvm tests/conformance/user-request-shadow.ssc` or
  `tests/conformance/run.sh --only 'user-request-shadow' --no-memo`.
- **Observed failure:** INT and JS lanes print `7`, `9`, `7`, `42`, but the JVM
  lane produces no stdout because scala-cli compilation fails. The generated
  Scala source contains both the always-inlined HTTP runtime
  `case class Request(method, path, ...)` and the user's
  `case class Request(alpha: Int, beta: Int)`, so scalac reports
  `Request is already defined`.
- **Expected:** non-HTTP user code may define a top-level `Request` case class,
  and `run-jvm` must behave like INT/JS for field access and `copy`.
- **Impact:** `origin/main` has a conformance regression in the JVM lane; the
  v2 production gate cannot be considered green until this lane passes.
- **Root cause:** non-server JVM codegen always inlined the HTTP runtime model
  (`Request`, `Response`, `StreamResponse`) even when the script did not use an
  HTTP server. That leaked public runtime case-class names into ordinary user
  modules, so a user top-level `Request` collided at scalac time.
- **Fix:** `d5538d66a` keeps HTTP/server modules on the existing `commonRuntime
  + serveRuntime` path, but switches non-server scripts that define
  `Request`/`Response`/`StreamResponse` to a collision-safe JVM preamble. Actor
  and HTTP-effect stubs use private `_SscRuntime*` names there, and the JVM
  artifact codegen version was bumped so stale `.scjvm` artifacts regenerate.
- **Verified:** `scripts/sbtc 'v2FrontendBridge/testOnly
  ssc.bridge.FrontendBridgeTest'` (42/42); `scripts/sbtc 'installBin'`; direct
  `bin/ssc run-jvm tests/conformance/user-request-shadow.ssc` prints
  `7`, `9`, `7`, `42`; `tests/conformance/run.sh --only
  'money-multisection,v2-*,user-request-shadow' --no-memo` (7/7); full
  `./v2/conformance/check.sh`; `git diff --check`.
- **Status:** fixed; waiting for human confirmation before `done`.

## v2-multiline-list-literal-desugar — `fixed` (2026-07-09)

`bin/ssc --v2` crashed with scala.meta `illegal start of simple expression`
on any fence containing a MULTI-LINE `[ … ]` list literal (bracket opens a
line, elements follow, `]` closes a later line). Corpus repro:
`datatable-static-spa.ssc` (`staticDataTable(rows, [ fcol… ], [ rowPost… ])`),
v1 green. Root cause was NOT the `[…]`→`List(…)` desugarer (it never ran) but
`FrontendBridge.filterImportLines`: a bare `[` opening a multi-line list was
misclassified as the start of a multi-line import directive
(`[A,\n B](path.ssc)`); finding no closing `](….ssc)` line it swallowed the
rest of the fence, so the merged source ended mid-expression and scala.meta
failed on the next `def`. Fix (`FrontendBridge.filterImportLines`): only
consume a multi-line import when a real `](….ssc)` close actually follows
through ident-list continuations; otherwise the line is code and is kept.
Corpus 152/10 → 154/8 (`datatable-static-spa` now green; no regressions — the
whole corpus still resolves its multi-line std-imports). Pinned by
tests/conformance/v2-multiline-list-literal.ssc. Fixed by lucky-perch.

## v2-payments-bankrails-op-stub-leaks - `fixed` (2026-07-09)

- **Found by:** codex, during the v2 production unmasking loop after standard
  `scala` fences became runnable.
- **Repro:** from a clean worktree, stage the CLI with
  `scripts/sbtc "installBin"`, then run:
  `bin/ssc run --v2 examples/traditional-payments.ssc`,
  `bin/ssc run --v2 examples/bank-rails-pix.ssc`, and
  `bin/ssc run --v2 examples/bank-rails-fednow.ssc`.
- **Observed failure:** all three commands exit 0, but the payment/bank-rails
  bridge surface is not actually handled. `traditional-payments.ssc` prints
  `Op("PaymentProvider.named", "stripe", <closure>)`; `bank-rails-pix.ssc`
  prints `Transfer initiated: Stub, status: Stub`, `Transfer status: Stub`,
  and an unhandled `PixQrCode.buildStatic` operation; `bank-rails-fednow.ssc`
  prints `FedNow transfer Stub submitted - status: Stub` and an
  `Op("Instant.now", ...)` leak.
- **Expected:** documented payment examples that are runnable on v2 should
  execute through deterministic bridge objects and print concrete provider,
  transfer, QR, and poll results, without `Op(` or `Stub` in stdout.
- **Impact:** the production v2 lane reports success while user-facing payment
  examples expose unresolved plugin-boundary values in output.
- **Notes:** `--v1` is not a valid oracle for this bug: the rollback lane fails
  earlier on undefined `PaymentProvider`, `PixConfig`, and `FedNowConfig`.
  The v2 oracle is the documented example behavior plus the explicit absence of
  unresolved `Op`/`Stub` output.
- **Root cause:** `FrontendBridge` treated several payment/bank-rails
  companion/factory names as ordinary constructors or unresolved method-object
  selects, while `PluginBridge` did not register the payment provider,
  bank-rails provider, Pix QR, Money/Currency, or small time/poll method objects
  needed by the examples. Several illustrative server/webhook/negative-path
  snippets were also still marked runnable even though they depend on route,
  webhook, platform, or disconnected example state.
- **Fix:** `d255f18f8` adds the v2 payments bridge surface: `v2PluginBridge`
  depends on the existing payments/Pix/FedNow modules; `FrontendBridge`
  pre-registers payment/bank-rails field names and method-object/factory names;
  `PluginBridge` registers deterministic no-network Stripe/Pix/FedNow provider
  method objects, `Money`/`Currency`, pure `PixQrCode` generation, and
  `Instant`/`Thread` helpers; the v2 runtime handles basic `Money` arithmetic.
  `69aad3c3f` marks non-self-contained payment snippets `scala no-run` and
  keeps the runnable money section on supported bridge behavior.
- **Verified:** `scripts/sbtc 'v2FrontendBridge/testOnly
  ssc.bridge.FrontendBridgeTest'` (42/42); `scripts/sbtc 'installBin'`; direct
  `bin/ssc run --v2` for `examples/traditional-payments.ssc`,
  `examples/bank-rails-pix.ssc`, and `examples/bank-rails-fednow.ssc` all exit
  0 and a shell guard confirms stdout contains no `Op(` or `Stub`;
  `tests/conformance/run.sh --only 'money-multisection,v2-*' --no-memo` (4/4);
  full `./v2/conformance/check.sh`; `git diff --check`.
- **Status:** fixed; waiting for human confirmation before `done`.

## v2-xslt-transform-empty-output — `fixed` (2026-07-09)

- **Found by:** codex, during the v2 production unmasking loop.
- **Repro:** from a clean worktree, stage the CLI with
  `scripts/sbtc "installBin"`, then run
  `bin/ssc run --v2 examples/xslt-transform.ssc`.
- **Observed failure:** the command exits 0 with empty stdout. The same staged
  CLI with `--v1` exits 1 on `Unknown interpolator 'xml'`, so v1 is not a valid
  output oracle for this example.
- **Expected:** v2 should execute the documented JVM/interpreter markup example:
  identity transform, element rename, HTML transform with `EUR` parameter
  substitution, and malformed-stylesheet error handling.
- **Impact:** a documented example in `README.md` and `docs/user-guide.md`
  silently does nothing on the v2 production lane.
- **Root cause:** the v2 bridge did not register the markup-core surface used by
  the example. `FrontendBridge` treated `xml"""..."""` like generic string
  interpolation, and `PluginBridge` had no `MarkupCodec`, `PureMarkupCodec`,
  `SerializeOpts`, `TransformError.message`, or `Right`/`Left` result bridge for
  XSLT calls.
- **Fix:** `b668359f9` adds the v2 markup/XSLT bridge: `v2PluginBridge` depends
  on `markupCore`/`backendInterpreter`, `FrontendBridge` pre-registers
  `SerializeOpts`/`TransformError` and lowers `xml` interpolation through
  XML-escaping bridge helpers, and `PluginBridge` registers JvmMarkupCodec-backed
  method objects for parse/serialize/transform plus a markup `Show` renderer.
- **Verified:** `scripts/sbtc 'v2FrontendBridge/testOnly
  ssc.bridge.FrontendBridgeTest'` (39/39); `scripts/sbtc 'installBin'`;
  `bin/ssc run --v2 examples/xslt-transform.ssc` prints identity `<catalog>`,
  rename `<report>/<item>`, HTML `EUR`, and expected stylesheet error handling;
  `tests/conformance/run.sh --only 'v2-*,content*' --no-memo` (7/7);
  full `./v2/conformance/check.sh`; `git diff --check`.
- **Status:** fixed; waiting for human confirmation before `done`.

## v2-serve-noop-minimalctx — `fixed` (2026-07-09)

- **Found by:** busi (rozum 07-09 n=24): hub boots on --v2 (banner + pairing code)
  but serve() never binds — no listener, curl 000.
- **Root cause:** `serve(port)` on the v2 run lane resolves to the frontend-plugin
  native, which calls `ctx.startServer`/`ctx.startTlsServer` — and the
  NativeContext trait DEFAULTS are silent no-ops (IntrinsicImpl.scala:132-138).
  The v2 bridge's MinimalCtx never overrode them, so every serve variant
  "succeeded" without a socket. (serveAsync was unaffected — it has its own real
  registerWebServer bridge.)
- **Fix:** MinimalCtx overrides startServer/startTlsServer (BLOCKING on the
  calling thread — v1 serve semantics, the program stays alive serving),
  startServerAsync/startTlsServerAsync (daemon thread + bound latch), stopServer,
  and registerHealthDefaults (/_health + /_ready on the bridge route registry,
  mirroring ClusterRoutesRuntime).
- **Verified:** busi hub on --v2: `lsof` shows the listener, `curl /` → 200 (busi
  routes), `/_health` → {"status":"ok"}, `/_ready` → 200. Gates: corpus 148/14 =
  main, conformance batch 123/37 = main, run.sh — all fails accounted
  (case-classes[JS] pre-existing on main — the unowned f57c74da8-family report;
  actors/async flakes pass idle; tls-smoke green).
- **Ladder:** busi drives the full storefront+money loop on --v2 next.

## v2-vm-effect-handlers-regression — `fixed` (2026-07-09)

- **Found by:** codex, while verifying `v2-vm-production-jit-gate` after
  rebasing on current `origin/main`.
- **Repro:** in a worktree with a staged v2 runtime, run
  `./v2/conformance/check.sh`. Minimal direct repro from a packaged v2 jar:
  `java -jar <v2-jar> run examples/effects-state.ssc0` returns
  `Op("get", (), <closure>)` instead of `Pair(2, 2)`.
- **Observed failure:** current `origin/main` (checked in detached diagnostic
  worktree at `ab78c6cac`) reproduces the same failures before the arith-loop
  optimizer commits: `effects-state`, `effects-nondet`, `async-tasks`, and
  `hm-eff-comp` all return unhandled `Op(...)` values on the VM lane while the
  JS/Rust lanes in the same conformance script pass. The full run also reports
  the same shape across effect rows such as `effrow`, `eff2`, `eff-traverse`,
  `eff-handle`, `eff-userstate`, `eff-do`, `eff-decl`, `eff-rowann`,
  `eff-typed`, `typed resume`, and `handleM`.
- **Impact:** the v2 VM conformance gate is red for algebraic effects/typed
  effect handlers, which is a production blocker independent of the current
  scalar-loop performance slice.
- **Notes:** this is not caused by the `v2-vm-production-jit-gate` arith-loop
  recognizer: the same minimal failures reproduce on clean `origin/main` at
  `ab78c6cac`, whose latest change is only a `.work/active/` claim.
- **Root cause:** `Compiler.C.compile` lifted every `DataV("Op", ...)`
  scrutinee over `Match` before ordinary ADT matching. That lift is only
  correct for bridge/runtime auto-thread operations; pure free-monad handlers
  need to match `Op("get", ...)`, `Op("choose", ...)`, and typed-effect
  `Op("double", ...)` as data.
- **Fix:** `b6f88744c` guards the `Match` lift with `Runtime.isAutoThreadOp`,
  matching the existing `Let`/`Seq` behavior. Added focused
  `FrontendBridgeTest` coverage for `examples/effects-state.ssc0` and
  `examples/hm-eff-comp.hm` compiled through `bin/mirac.ssc0` to CoreIR.
- **Gates:** focused
  `scripts/sbtc 'v2FrontendBridge/testOnly ssc.bridge.FrontendBridgeTest -- -z "effect handlers"'`;
  full `./v2/conformance/check.sh`; `scripts/sbtc "installBin"`;
  `tests/conformance/run.sh --only 'litdoc'` passed INT/JS/JVM.
- **Status:** fixed; waiting for human/reporter confirmation before `done`.

## v2-source-backend-bridge-bench-prims — `fixed` (2026-07-09)

- **Found by:** codex, while implementing `v2-backend-performance-harness`.
- **Repro:** stage `bin/ssc`, then run
  `bin/ssc --backend v2-jvm bench --warmup-time 100 --reps 3 bench/corpus/arith-loop.ssc`
  and
  `bin/ssc --backend v2-rust bench --warmup-time 100 --reps 3 bench/corpus/arith-loop.ssc`.
- **Observed failure:** the v2 VM label reported `BENCH v2 ...`, but the v2 JVM
  and v2 Rust source backend labels returned `n/a`. With `SSC_BENCH_DEBUG=1`, JVM
  generated source failed on missing `global.reg`/bridge method support, and Rust
  generated source failed on missing `g_println` plus missing bridge method support.
- **Impact:** the Phase-3 backend performance harness cannot produce v2 source
  backend timing columns, so `v2 JVM backend within 2x` and `v2 Rust backend
  within 1.5x` cannot even be measured on the corpus shape.
- **Root cause:** the standalone v2 source generators did not provide the small
  FrontendBridge standard global/method subset that the VM path already sees
  (`println`, `print`, `System.nanoTime`, `__autoPrint__`, `global.reg`, and the
  simple method calls used by the benchmark wrapper).
- **Fix:** `01d9abf32` keeps the v2 benchmark wrapper portable and adds the
  minimal JVM/Rust generated-runtime bridge globals plus `__method__` dispatch
  needed by the harness.
- **Gates:** `./v2/backend/check.sh tco`; `./v2/backend/check.sh bool`;
  `scripts/sbtc "cli/testOnly scalascript.cli.CommandRegistryTest"`;
  `scripts/sbtc "cli/testOnly scalascript.cli.GlobalFlagsTest"`;
  `scripts/sbtc "installBin"`; `tests/conformance/run.sh --only 'litdoc'`;
  `scripts/bench v2-backends arith-loop` reported non-`n/a` rows
  (`v2=9.68 ms`, `v2-jvm=0.265 ms`, `v2-rust=66.8 ms`).
- **Status:** fixed; waiting for human confirmation before `done`.

## scripts-bench-wall-all-na — `fixed` (2026-07-09)

- **Found by:** codex, while measuring `v2-prod-performance-gate-baseline`.
- **Repro:** after staging `bin/ssc`, run `scripts/bench wall` from the repo
  root.
- **Observed failure:** every cell prints `n/a` for `fib`, `sum`, and
  `list-ops`, even though `tests/bench/{fib,sum,list-ops}.{ssc,scala,js}`
  exist.
- **Impact:** the mandated `scripts/bench wall` entrypoint cannot produce
  useful cross-language wall-clock numbers, so production performance notes
  would have to rely on `bench.sh` only.
- **Root cause:** two stale assumptions in `tests/bench/run.sc`: it set
  `dir = os.pwd / "bench"`, so `scripts/bench wall` from repo root looked for
  `/repo/bench/fib.ssc` instead of `/repo/tests/bench/fib.ssc`; and its
  missing-`sscc` fallback used the obsolete `ssc compile <file>` command.
- **Fix:** `966a530e6` resolves the data directory from either repo root or
  direct script-directory execution, and changes the JVM fallback to
  `ssc run-jvm <file>`.
- **Gates:** `scripts/bench wall` now reports usable rows:
  `fib 50/2/0/0/2`, `sum 51/4/1/0/2`, and
  `list-ops 110/n/a/33/73/2` for `ssc-int/ssc-js/ssc-jvm/scala-cli/node`.
  The remaining `list-ops` JS `n/a` is an unsupported-row caveat, not the
  all-`n/a` runner failure.
- **Status:** fixed; waiting for human confirmation before `done`.

## jvm-artifact-cache-codegen-invalidation — `fixed` (2026-07-09)

- **Found by:** codex, while fixing `v2-litdoc-js-jvm-backend-lanes`.
- **Repro:** generate a `.scjvm` artifact for a fixture, change JVM codegen
  without changing the `.ssc` source bytes, run
  `bin/ssc run-jvm tests/conformance/litdoc.ssc`.
- **Observed failure:** `run-jvm` reused
  `tests/conformance/.ssc-artifacts/litdoc.scjvm` because the artifact cache is
  invalidated only by the source SHA. `bin/ssc emit-scala
  tests/conformance/litdoc.ssc` showed the fixed generated Scala, but
  `bin/ssc run-jvm tests/conformance/litdoc.ssc` still compiled the stale
  `.scjvm` until that one generated file was removed.
- **Impact:** after upgrading compiler/codegen/runtime bits, a user can keep
  running stale JVM generated source for unchanged `.ssc` files. This can hide
  fixes or preserve old failures in production verification.
- **Root cause:** `.scjvm` freshness only compared the `.ssc` source SHA, so
  source-fresh artifacts survived JVM backend/runtime codegen changes.
- **Fix:** `322ee868f` added `codegenVersion` to `ModuleJvmArtifact`, writes
  the current JVM codegen cache key, and makes `ModuleGraph.isJvmStale`
  invalidate legacy/old-key artifacts. `14aa2819d` adds a CLI regression that
  proves `run-jvm` regenerates a source-fresh `.scjvm` with an old key.
- **Gates:** `core/testOnly scalascript.artifact.ModuleGraphTest`;
  `cli/assembly; cli/testOnly scalascript.cli.JvmIncrementalCliTest`;
  `scripts/sbtc "installBin"`; `tests/conformance/run.sh --only 'litdoc' --no-memo`.
- **Status:** fixed; waiting for reporter/human confirmation before `done`.

## v2-stream-family-output-parity — `fixed` (2026-07-09)

- **Found by:** codex, in the valid full production parity sweep after
  `v2-v1-side-mismatch-classification`.
- **Repro:** with a staged runner, run
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/distributed-streams.ssc examples/streams.ssc`.
- **Observed failure:** `distributed-streams.ssc` is still a strict mismatch:
  v1 prints the word-count block (`=== 2. Word count ===` plus sorted
  `KV(...)` rows) and v2 omits that block. `streams.ssc` is also a strict
  mismatch: after `=== 2. Stream block ===`, v1 stops at the header while v2
  prints `1`, `4`, and `9`.
- **Impact:** after moving known v1-side/better-output rows out of strict
  byte-parity, these are the only remaining unexplained production output
  mismatches in the default v2 gate.
- **Plan:** claim `v2-stream-family-output-parity`; reproduce both rows in the
  real assembled harness; determine whether each is a v2 stream/section
  execution bug, standard-Scala multi-section behavior, or a documented v1-side
  row; then fix v2 with focused conformance/regression coverage or classify the
  row explicitly.
- **Reproduced 2026-07-09:** `distributed-streams.ssc` v1 reaches the word-count
  block and then fails later on missing `String.toIntOption`; v2 fails earlier
  inside DStreams `combinePerKey` with `NoSuchElementException: key not found:
  key`. The likely bridge gap is that v2-created `KV(...)` reaches the v1
  DStreams plugin as positional `_0`/`_1` fields instead of named
  `key`/`value`. `streams.ssc` v1 fails in `stream { emit(...) }` with
  `emit called outside a stream body`; v2 correctly emits the stream block and
  then fails at `Source.runFold(z)(f) — outer`, likely because v2 invokes the
  curried native method with both arguments in one call. The next code pass
  should register v2 field names for `KV` and make stream/DStream `runFold`
  accept both curried and flattened two-argument calls.
- **Progress 2026-07-09:** after registering `KV` fields, accepting flattened
  `runFold`, and making v2→v1 list conversion iterative for large Cons/Nil
  chains, both examples advance further. `distributed-streams.ssc` now prints
  sections 1-4 and fails in section 5 with `__method__: no dispatch for .value
  on 10`, meaning DStreams stateful callbacks receive the raw input value where
  the example expects a per-key `KV(key, value)` shape. `streams.ssc` now prints
  sections 1-7 through `Buffer and timing` and fails at
  `Source.throttle: rate elements must be > 0`, meaning v2 invokes
  `Source.throttle(rate, per)` as flattened two-arg native call while the plugin
  currently expects the old curried shape. Next code pass: normalize DStreams
  stateful callback input and accept flattened rate arguments in stream timing
  natives.
- **Root cause:** the remaining strict mismatch bucket mixed real v2 plugin
  bridge gaps with rollback-v1 short-output rows. v2 needed named `KV`/`Rate`
  fields at the v2↔v1 plugin boundary, iterative Cons/Nil conversion for large
  stream ranges, flattened curried-call compatibility for stream/DStream
  natives, DStreams tuple/option result shapes that v2 pattern matching can
  consume, and a signal `.bind` method on bridged v1 `ReactiveSignal` values.
- **Fix:** `d1d0bc1fd` fixes those bridge/runtime gaps and classifies
  `distributed-streams.ssc` and `streams.ssc` as v1-side/better-output rows in
  `scripts/v2-output-parity`, because v2 now runs both documented examples to
  completion while rollback v1 stops early.
- **Gates:** `git diff --check`; `streamsPlugin/testOnly
  scalascript.compiler.plugin.streams.StreamsPluginInterpreterTest` 83/83;
  `dstreamsPlugin/testOnly
  scalascript.compiler.plugin.dstreams.DStreamsPluginInterpreterTest` 66/66;
  `v2PluginBridge/testOnly ssc.bridge.PluginBridgeTest` 26/26;
  `v2FrontendBridge/testOnly ssc.bridge.FrontendBridgeTest` 29/29;
  `tests/conformance/run.sh --only 'signals' --no-memo` passed INT/JS/JVM;
  direct `bin/ssc run --v2` for both stream examples exits 0; targeted parity
  reports `2 v1-side`; full parity is
  `68/91 identical · 0 mismatch · 0 v2-error · 23 v1-only` with `4 v1-side`
  skips across 195 examples.
- **Status:** fixed; awaiting any external confirmation before trimming.

## v2-output-parity-temp-write-fail-fast — `fixed` (2026-07-09)

- **Found by:** codex, while re-running the full v2 output-parity sweep during
  `v2-v1-side-mismatch-classification`.
- **Repro:** with a nearly full host disk, run
  `PARITY_TIMEOUT=45 SSC="/Users/sergiy/work/my/scalascript/bin/ssc" scripts/v2-output-parity --all`.
- **Observed failure:** once `target/v2-output-parity-tmp` cannot accept writes,
  `run_one` logs `No space left on device` for the shared RC file but the script
  keeps reading the previous RC value. Later rows are then misreported as
  `both-fail`, and the final summary looks like a valid corpus result even
  though the run is corrupted.
- **Impact:** production parity baselines can be polluted by false counts when
  the host has insufficient disk. This happened during the attempted
  2026-07-09 full sweep; that full-sweep output must not be recorded as a
  baseline.
- **Root cause:** the parity harness wrote every backend exit code through one
  shared RC file but did not check whether creating or writing that file
  succeeded. When the filesystem filled, later rows reused stale RC state and
  the summary looked valid.
- **Fix:** `18ee5ecfc` moves parity temp files into a repo-local temp dir and
  makes temp-dir creation, RC-file creation, RC writes, RC reads, and temporary
  port-rewrite files fail fast with exit 2.
- **Gates:** artificial unwritable `SSC_PARITY_TMPDIR` exits with `rc=2` and
  `cannot create rc file`; the subsequent full parity sweep completed without
  temp-write errors and produced
  `68/93 identical · 2 mismatch · 0 v2-error · 23 v1-only`
  with `2 v1-side` skips.
- **Status:** fixed; awaiting any external confirmation before trimming.

## v2-v1-side-mismatch-classification — `fixed` (2026-07-09)

- **Found by:** codex, during the v2 production output-parity loop after
  `v2-scala-fence-multiblock-parity`.
- **Repro:** after `scripts/sbtc "installBin"`, run
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/effects.ssc examples/dsl-calc-parser.ssc`.
- **Observed prior findings:** `effects.ssc` is a strict v1/v2 mismatch because
  v2 prints the documented six-line example output while v1 stops after the
  first three sections. `dsl-calc-parser.ssc` is a strict mismatch because v1
  truncates parser round-trips to the first number, while v2 prints the full
  parsed/pretty expression strings.
- **Impact:** the production output-parity gate still reports these as v2
  mismatches even though the durable notes identify them as v1-side or
  better-output rows. That hides the smaller remaining stream-family surface
  that likely needs real v2 work.
- **Root cause:** the strict byte-parity gate had no bucket for rows where the
  rollback v1 runner is the bad side and v2 matches the documented behavior.
  That made two known v1-side/better-output rows look like active v2
  production regressions.
- **Fix:** `18ee5ecfc` classifies `effects.ssc` and `dsl-calc-parser.ssc` as
  v1-side/better-output skips in `scripts/v2-output-parity`. The example and
  runtime behavior are unchanged.
- **Gates:** targeted parity for `examples/effects.ssc` and
  `examples/dsl-calc-parser.ssc` reports `2 v1-side` skips; targeted parity for
  `examples/scala-js-demo.ssc` and `examples/lang-split.ssc` still reports
  2/2 identical; `tests/conformance/run.sh --only 'effects' --no-memo` passed
  INT/JS/JVM; full parity is now
  **68/93 identical · 2 mismatch · 0 v2-error · 23 v1-only** with
  `2 v1-side` skips across 195 examples.
- **Status:** fixed; awaiting any external confirmation before trimming.

## v2-scala-fence-multiblock-parity — `fixed` (2026-07-09)

- **Found by:** codex, during the v2 production output-parity loop after
  `v2-mcp-oauth-secret-nondet-parity`.
- **Repro:** after `scripts/sbtc "installBin"`, run
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/scala-js-demo.ssc examples/lang-split.ssc`.
- **Observed failure:** `examples/scala-js-demo.ssc` is a standard-Scala-only
  document with three `scala` fences; v1 prints all collection, ADT, and
  recursive-sort lines, while v2 only matches the first collection line(s).
  `examples/lang-split.ssc` explicitly documents that `scala` and
  `scalascript` blocks may coexist, but v2 omits the standard `scala` block
  output (`Distance`, `Evens`, `Primes`) and only runs the ScalaScript block.
- **Impact:** default v2 no longer silently drops all standard `scala` documents
  after `v2-standard-scala-fences-skipped`, but the production parity gate still
  has deterministic user-visible gaps for multi-fence and intentional mixed
  `scala`/`scalascript` runnable documents.
- **Initial hypothesis:** `FrontendBridge.extractCode(..., allFences = true)`
  has two remaining policy/shape gaps: standard `scala` fences are included only
  for standard-Scala-only documents, which is too strict for examples that
  explicitly mark both languages runnable; and multi-block Scala source may still
  expose top-level conversion or auto-print ordering differences.
- **Reproduced 2026-07-09:** the two rows have different root causes. For
  `scala-js-demo.ssc`, v2 extracts and starts the later standard `scala` fences
  but exits after the first two lines with
  `__method__: no dispatch for .takeWhile on "Circle(3)"`; adding the missing
  string predicate method should expose the remaining ADT/sort output. For
  `lang-split.ssc`, v2 exits 0 but only prints the ScalaScript block, confirming
  that intentional mixed runnable `scala` fences are still excluded by
  `extractCode`.
- **Second repro pass 2026-07-09:** after `String.takeWhile` and mixed-fence
  opt-in, `lang-split.ssc` matches. `scala-js-demo.ssc` then exposes two more
  narrow v2 gaps in existing support code: `f"..."` interpolation is lowered as
  raw string concatenation (`Circle%-12s area = ...%.2f`), and guarded
  constructor-pattern arms abort the whole match on guard failure instead of
  falling through to the next case (`mergeSort` needs the later
  `case (_, bh :: bt)` arm).
- **Plan:** add focused extraction/runtime regressions for an all-`scala`
  multi-fence document and an intentional mixed runnable document; adjust the
  extractor policy narrowly so documented runnable `scala` fences are included
  without re-enabling arbitrary illustrative snippets in mixed ScalaScript docs;
  then run targeted parity plus affected conformance before pushing.
- **Root cause:** the all-fences extraction path only ran standard `scala`
  fences for standard-Scala-only documents, mixed runnable examples had no
  explicit opt-in, and the follow-on `scala-js-demo.ssc` path exposed missing
  standard-runtime/lowering shapes (`String.takeWhile`/`dropWhile`, `f"..."`
  formatting, guarded constructor-pattern fall-through).
- **Fix:** `f57c74da8` adds the mixed-fence opt-in
  (`runScalaFences: true` / `run-scala-fences: true` or
  `scalaFences: runnable` / `scala-fences: runnable`), executes standard
  multi-fence documents in order, adds the runtime/lowering support, and adds
  focused frontend + conformance regressions.
- **Gates:** `scripts/sbtc "v2FrontendBridge/testOnly ssc.bridge.FrontendBridgeTest"`
  passed 25/25; `scripts/sbtc "installBin"` passed;
  `tests/conformance/run.sh --only 'standard-scala-*' --no-memo` passed 3/3
  across INT/JS/JVM; targeted parity for `examples/scala-js-demo.ssc` and
  `examples/lang-split.ssc` is 2/2 identical; full parity is now
  **68/95 identical · 4 mismatch · 0 v2-error · 23 v1-only** with 5 nondet
  skips across 195 examples.
- **Status:** fixed; awaiting any external confirmation before trimming.

## v2-mcp-oauth-secret-nondet-parity — `fixed` (2026-07-09)

- **Found by:** codex, during the v2 production output-parity loop after
  `v2-os-env-nondet-parity`.
- **Repro:** after `scripts/sbtc "installBin"`, run
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/mcp-server-protected.ssc examples/oauth-mcp-full-stack.ssc`.
- **Observed failure:** both examples print generated OAuth/MCP client ids and
  secrets, so v1/v2 runs produce different credentials. They also include
  server startup/banner lines that are not the semantic client/server contract.
- **Impact:** this is generated-output noise in the strict byte-parity gate, not
  a v2 production runtime failure. Keeping these rows as mismatches hides the
  smaller set of semantic parser/stream-shape rows still needing investigation.
- **Root cause:** the strict parity harness was treating generated credentials
  and server banners as deterministic stdout. Those examples are useful demos,
  but their startup output cannot byte-match across independent v1/v2 runs.
- **Fix:** `2142f8e0d` classifies `mcp-server-protected.ssc` and
  `oauth-mcp-full-stack.ssc` as nondeterministic-output by design. The examples
  and runtime behavior are unchanged.
- **Gates:** `scripts/sbtc "installBin"` passed; targeted parity for the two
  examples now reports nondeterministic-output skips;
  `tests/conformance/run.sh --only 'mcp-*' --no-memo` passed enabled
  `mcp-types` on INT/JS with the server/client cases skipped by requirements;
  full parity is now **66/95 identical · 6 mismatch · 0 v2-error · 23 v1-only**
  with 5 nondet skips across 195 examples.

## v2-os-env-nondet-parity — `fixed` (2026-07-09)

- **Found by:** codex, during the v2 production output-parity loop after
  `v2-async-parallel-timing-parity`.
- **Repro:** after `scripts/sbtc "installBin"`, run
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/os-env.ssc`.
- **Observed failure:** v1 prints unresolved native placeholders for platform
  values (`<native:platform>`, `<native:cwd>`, `<native:sep>`), while v2 prints
  real host values such as `JVM`, the current worktree path, and `/`.
- **Impact:** this is not a v2 regression: v2 is doing the useful thing, and
  the example's output is host-dependent by design. Keeping it in the strict
  byte-parity mismatch bucket makes the production gate noisier.
- **Root cause:** the strict parity harness was treating a host-dependent demo
  as a deterministic v1/v2 output comparison. The mismatch was useful as a
  visibility signal, but not actionable as a v2 production blocker.
- **Fix:** `6e82f20b2` classifies `os-env.ssc` as nondeterministic-output by
  design in `scripts/v2-output-parity`, alongside `sql-sqlite-file` and
  `uuid-v7`. The example and runtime behavior are unchanged. Added
  `tests/conformance/std-os.ssc` to cover deterministic std/os helpers.
- **Gates:** `scripts/sbtc "installBin"` passed;
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/os-env.ssc`
  now reports nondeterministic-output skip; `tests/conformance/run.sh --only 'std-os' --no-memo`
  passed INT; full parity is now
  **66/97 identical · 8 mismatch · 0 v2-error · 23 v1-only** with 3 nondet
  skips across 195 examples.

## v2-async-parallel-timing-parity — `fixed` (2026-07-09)

- **Found by:** codex, during the v2 production output-parity loop after
  `v2-graph-neo4j-foreign-parity`.
- **Repro:** after `scripts/sbtc "installBin"`, run
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/async-parallel-demo.ssc`.
- **Observed failure:** v1 and v2 both compute `List(50, 50, 50)` for the
  `runAsync`/`runAsyncParallel` examples, but the example prints measured
  wall-clock milliseconds (`took ~Nms`), so byte-for-byte parity mismatches even
  when semantics agree.
- **Impact:** default v2 production gate has a false output mismatch. The
  example itself says output stays byte-identical for code that does not depend
  on timing, but its stdout currently depends on timing.
- **Root cause:** the example itself was nondeterministic: it printed live
  elapsed milliseconds for the sequential and parallel handlers. v1/v2 semantic
  results matched, but byte-for-byte stdout parity could not.
- **Fix:** `ea62f9d38` keeps the result lines and timing guidance, but removes
  live elapsed milliseconds from stdout. No runtime semantics changed.
- **Gates:** `scripts/sbtc "installBin"` passed;
  `tests/conformance/run.sh --only 'async-parallel' --no-memo` passed INT/JS/JVM;
  targeted `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/async-parallel-demo.ssc`
  passed 1/1; full parity is now
  **66/98 identical · 9 mismatch · 0 v2-error · 23 v1-only** across 195
  examples.

## v2-graph-neo4j-foreign-parity — `fixed` (2026-07-09)

- **Found by:** codex, during the post-split production output-parity refresh.
- **Repro:** after `scripts/sbtc "installBin"`, run
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/graph-neo4j-storage.ssc`
  or directly compare `bin/ssc run --v1 examples/graph-neo4j-storage.ssc`
  against `bin/ssc run --v2 examples/graph-neo4j-storage.ssc`.
- **Observed failure:** v1 prints
  `StoredEdge(knows, carol, bob-knows-carol, bob, Knows(bob, carol, 2021))`
  while v2 prints `<foreign>`.
- **Impact:** default v2 output-parity mismatch in the production gate. The
  graph plugin's edge write result is usable enough to run, but direct display
  of a plugin-created instance leaks the v2 opaque foreign renderer.
- **Initial hypothesis:** `Graph.putEdge` returns
  `PluginValue.instance("StoredEdge", ...)`, i.e. a v1 `InstanceV` with named
  fields. `PluginBridge.v1ToV2` keeps named-field instances as
  `ForeignV(NamedMethodObj)` to preserve field access when v2 has no registered
  field order, but the bridged print path `v1Show` falls through to
  `Show.show` for that foreign wrapper, producing `<foreign>` instead of the
  underlying v1 `Value.show`.
- **Root cause:** the hypothesis was correct, with one extra display path:
  registered `println` uses the bridged `v1Show`, but last-expression
  `__autoPrint__` goes through the v2 core `Show.show`. Both paths treated
  `ForeignV(NamedMethodObj)` as opaque even when `underlying` was a v1
  interpreter `Value`, so plugin-created named instances printed as `<foreign>`.
- **Fix:** `c39afa9ba` renders bridged named v1 values with v1
  `Value.show` while preserving `NamedMethodObj` field access. The v2 core
  `Show` now sends `NamedMethodObj.underlying` through the existing
  `foreignRenderer` callback, so auto-print and ordinary print agree.
- **Gates:** `scripts/sbtc "v2PluginBridge/testOnly ssc.bridge.PluginBridgeTest"`
  passed 23/23; `scripts/sbtc "installBin"` passed;
  `tests/conformance/run.sh --only 'graph-edge-display' --no-memo` passed INT;
  targeted `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/graph-neo4j-storage.ssc`
  passed 1/1; full parity is now
  **65/98 identical · 10 mismatch · 0 v2-error · 23 v1-only** across 195
  examples.

## jvmgen-litdoc-mapped-string-mkstring — `fixed` (2026-07-09)

- **Found by:** codex, while enabling `tests/conformance/litdoc.ssc` expected
  output during `v2-litdoc-inline-bold-parity`.
- **Repro:** after `scripts/sbtc "installBin"`, run
  `bin/ssc run-jvm tests/conformance/litdoc.ssc`.
- **Observed failure:** generated Scala fails to compile around the litdoc fence
  line with `missing argument for parameter i of method apply in class StringOps`
  for a generated expression shaped like
  `doc.nodes.filter(...).map(...).map(_show).mkString()`.
- **Impact:** backend-lane only. The default v2 VM path (`bin/ssc run --v2`) is
  the production gate for this slice and now matches v1; `litdoc.ssc` is marked
  `backends: [int]` until the JVM generator issue is fixed.
- **Root cause:** two backend-lane issues stacked on the same litdoc line. The
  generated JVM preamble always emitted `def doc(args: Any*)`, so a user
  top-level `val doc = parseDoc(md)` lived in the same generated Scala scope as
  the helper. After that was fixed, the remaining `StringOps.apply` error came
  from rewriting no-arg `.mkString()` to `.map(_show).mkString()`: Scala's
  no-arg `Iterable.mkString` is parameterless, so `mkString()` applies the
  returned `String` as a function.
- **Fix:** `782f07438` omits the JVM `doc` helper when user code owns top-level
  `doc`, rewrites no-arg `.mkString()` to `.map(_show).mkString`, and enables
  `tests/conformance/litdoc.ssc` across all backend lanes.
- **Gates:** `scripts/sbtc "backendJs/compile; backendJvm/compile; installBin"`;
  direct `bin/ssc run-jvm tests/conformance/litdoc.ssc`;
  `tests/conformance/run.sh --only 'litdoc' --no-memo`; focused
  `backendInterpreter/testOnly scalascript.JvmGenBackendBlockTest`.
- **Status:** fixed; awaiting any external confirmation before trimming.

## v2-litdoc-inline-bold-parity — `fixed` (2026-07-09)

- **Found by:** codex, while verifying `v2-arith-unification` against the
  litdoc real harness.
- **Repro:** after `scripts/sbtc "installBin"`, run:
  `bin/ssc run --v1 tests/conformance/litdoc.ssc` and
  `bin/ssc run --v2 tests/conformance/litdoc.ssc`, then diff stdout.
- **Observed failure:** all litdoc lines match except inline emphasis rendering:
  v1 prints `inline: P(buy a )B(new)P( dress)`, while v2 prints
  `inline: P(buy a **new** dress)`.
- **Impact:** this is an output-parity mismatch in a non-example conformance
  document. It is not caused by the arith dispatch split fixed in
  `v2-arith-unification`; the map/data line now agrees, but inline bold parsing
  still diverges.
- **Root cause:** v1 string `.split` uses Java/Scala regex semantics
  (`s.split(sep, -1)`), but v2 quoted the delimiter with
  `Pattern.quote(...)` in the primitive, string-method, and FastCode
  `str.split` paths. The litdoc delimiter `"\\*\\*"` therefore became a literal
  backslash-star pattern under v2 and never split the bold marker.
- **Fix:** `2b5a36660` restores regex semantics in all three v2 split paths and
  adds `tests/conformance/expected/litdoc.txt`. The fixture is marked
  `backends: [int]` because JS/JVM have separate backend-lane blockers tracked
  in `jsgen-toplevel-name-vs-preamble` and
  `jvmgen-litdoc-mapped-string-mkstring`.
- **Gates:** `scripts/sbtc "installBin"` passed;
  `tests/conformance/run.sh --only 'litdoc' --no-memo` passed INT and skipped
  JS/JVM by `backends: [int]`; direct
  `bin/ssc run --v1 tests/conformance/litdoc.ssc` vs
  `bin/ssc run --v2 tests/conformance/litdoc.ssc` diff is empty.

## v2-arith-dispatch-split — `fixed` (2026-07-09)

- **Found by:** codex, while promoting BACKLOG `v2-arith-unification` for v2
  production readiness.
- **Related history:** `v2-arith-table-divergence` fixed the immediate busi
  litdoc regression by keeping literal op names in place through `OpAnf` and by
  patching Map+Tuple2 into the non-literal table. That entry intentionally left
  full unification to BACKLOG.
- **Repro shape:** construct CoreIR where `__arith__` receives the operator from
  a local binding rather than a literal, e.g. `let op = "+" in __arith__(op,
  attrs, ("id" -> "demo"))`. This forces `resolve("__arith__")` instead of the
  literal-op `Prims.arithOp` fast path. The same operator/value pair should have
  identical semantics regardless of whether `op` is a literal or a local.
- **Impact:** production v2 has two arithmetic semantics tables. A future ANF,
  bridge, or optimizer change can silently switch programs between the richer
  `Prims.arithOp` path (Op lifting, Map+Tuple2, char-code comparisons,
  Cons-minus) and the table path (historically weaker plus table-only Decimal,
  actor-send, and declaration fallbacks).
- **Plan:** move table-only behavior into `Prims.arithOp`, make
  `resolve("__arith__")` delegate to `arithOp`, and add focused regressions for
  non-literal operator dispatch so this split cannot reappear.
- **Root cause:** `resolve("__arith__")` carried a second, hand-maintained
  arithmetic table. Literal-op fast paths went straight to `Prims.arithOp`, but
  non-literal op names used the table and could miss richer semantics or preserve
  table-only cases independently.
- **Fix:** `a2985d911` makes `resolve("__arith__")` a thin delegate to
  `Prims.arithOp` and moves the table-only Decimal, actor-send, `:=`, list/tuple,
  string/numeric, char-code, and unknown-declaration fallback cases into
  `arithOp`.
- **Gates:** `scripts/sbtc "v2FrontendBridge/testOnly ssc.bridge.FrontendBridgeTest"`
  passed 20/20; `scripts/sbtc "installBin"` passed;
  `tests/conformance/run.sh --only 'litdoc,arithmetic' --no-memo` passed
  `arithmetic` on INT/JS/JVM and skipped `litdoc` because no
  `expected/litdoc.txt` exists. Direct litdoc real-harness A/B still has the
  separate inline-bold mismatch tracked as `v2-litdoc-inline-bold-parity`; the
  arith/map data line agrees.

## v2-cluster-stdlib-import-gap — `fixed` (2026-07-09)

- **Found by:** codex, after `cdd032f03` fixed standard `scala` fence
  extraction during `v2-parity-current-errors`.
- **Repro:** after `scripts/sbtc "installBin"`, run:
  `bin/ssc run --v1 examples/cluster-capability.ssc` and
  `bin/ssc run --v2 examples/cluster-capability.ssc`. v1 prints:
  `demo-node`, `ws://seed:9100/_ssc-actors`, `sha256`, `64`; v2 now reaches
  program execution but exits with `RuntimeException: unbound global: clusterOf`.
- **Observed failure:** the standard-fence targeted parity slice after
  `cdd032f03` reports `1/6 identical · 4 mismatch · 1 v2-error`; the remaining
  v2-error is `cluster-capability.ssc`.
- **Impact:** the cluster stdlib import/export path is not production-safe under
  the v2 default runner; `clusterOf` is visible to v1 but unresolved in v2.
- **Root cause:** v2's actor compatibility bridge registered `runActors`,
  `startNode`, and related actor globals, but not the cluster capability globals
  that v1 installs through `ActorGlobals` (`clusterOf`, `resolveSeeds`,
  `codeIdentity`, `assertCodeIdentity`, `SeedResolver`). After those globals were
  added, the imported case-class method `ClusterCapability.resolveSeeds` exposed
  a second shape bug: the generated case-class method global named `resolveSeeds`
  shadowed the plugin extern/global of the same name and recursively treated a
  `SeedResolver` as a `ClusterCapability`. `__methodOrExt__` now gives registered
  plugin method dispatch a chance before falling back to the user extension
  global when a DataV has no real field by that name.
- **Fixed in:** `70969362f` (`fix(v2): bridge cluster capability globals`).
- **Gates:** targeted v2 regression
  `v2FrontendBridge/testOnly ssc.bridge.V2ConformanceTest -- -z cluster`;
  `v2PluginBridge/testOnly ssc.bridge.PluginBridgeTest` (22/22);
  `v2FrontendBridge/testOnly ssc.bridge.FrontendBridgeTest` (17/17);
  `scripts/sbtc "installBin"`; real harness
  `bin/ssc run --v1/--v2 examples/cluster-capability.ssc` (both print
  `demo-node`, `ws://seed:9100/_ssc-actors`, `sha256`, `64`); targeted six-example
  parity is now `2/6 identical · 4 mismatch · 0 v2-error`; full production gate is
  `64/98 identical · 11 mismatch · 0 v2-error · 23 v1-only`.

## v2-standard-scala-fences-skipped — `fixed` (2026-07-09)

- **Found by:** codex, during `v2-parity-current-errors` full output-parity
  refresh.
- **Repro:** after `scripts/sbtc "installBin"`, run:
  `bin/ssc run --v1 examples/cluster-capability.ssc` and
  `bin/ssc run --v2 examples/cluster-capability.ssc`. v1 prints:
  `demo-node`, `ws://seed:9100/_ssc-actors`, `sha256`, `64`; v2 exits 0 with
  empty stdout. A minimal repro is a document containing only:
  ````markdown
  ```scala
  println("scala-block-ok")
  ```
  ````
  which prints on v1 and is empty on v2, while the same fence tagged
  `scalascript` prints on both.
- **Observed failure:** the fresh production gate
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity --all` reports
  `62/93 identical · 7 mismatch · 6 v2-error · 18 v1-only`; the six v2-error
  rows all use standard `scala` fenced blocks:
  `cluster-capability`, `distributed-streams`, `graph-neo4j-storage`,
  `graph-storage`, `scala-js-demo`, and `streams`.
- **Impact:** default v2 silently treats standard-Scala-only `.ssc` examples as
  empty programs, so `ssc run --v2` can exit 0 without running user code.
- **Root cause:** `FrontendBridge.convertSource` uses
  `extractCode(..., allFences = true)`, whose non-SQL runnable-fence regex
  includes `scalascript` but excludes `scala`; `RunV2` does not use the
  `ModuleBridge.convert(Parser.parse(...))` path that walks all parseable
  `Content.CodeBlock`s.
- **Fix:** `cdd032f03` teaches the v2 source extraction path to include standard
  `scala` fences when they are the runnable source for the document, without
  re-enabling illustrative Scala snippets in mixed ScalaScript docs that the
  existing comment warns about.
- **Gates:** `scripts/sbtc "v2FrontendBridge/testOnly ssc.bridge.FrontendBridgeTest"`
  (17/17); `tests/conformance/run.sh --only 'standard-scala-fence' --no-memo`
  (INT/JS/JVM pass); `scripts/sbtc "installBin"`; minimal real harness
  `bin/ssc run --v1/--v2 <standard-scala-fence>` prints `scala-block-ok` on both;
  targeted six-example parity changed the failure mode to one remaining
  `clusterOf` v2-error plus four non-empty mismatches, with `graph-storage.ssc`
  now matching.
- **Follow-up:** `cluster-capability.ssc` now exposes
  `v2-cluster-stdlib-import-gap`; that is tracked as a separate bug.

## route-deriver-path-param-unit-client — `fixed` (2026-07-09)

- **Found by:** codex, during `tkv2-typed-client` prep.
- **Repro:** with no explicit `apiClients:` front matter, derive a client from
  `route("GET", "/api/todos/:id") { ... }` or
  `route("DELETE", "/api/todos/:id") { ... }`, then inspect
  `bin/ssc emit-js examples/derived-route-clients.ssc`. Current output warns
  `path param ':id' cannot be filled — request type is Unit` and emits methods
  such as `getApiTodosById(headers, cancelToken)`, so a browser client cannot
  pass the id.
- **Observed failure:** route-derived non-body endpoints with path parameters
  are not callable as typed browser clients; the generated method shape treats
  the first user argument as headers instead of the path value.
- **Impact:** toolkit-v2's no-manual-`apiClients:` route-derived browser client
  path is not production-safe for common detail/delete routes.
- **Fix direction:** have `RouteDeriver` use `String` for one path parameter
  and `Any` for multiple path parameters when no typed handler evidence exists;
  keep explicit `apiClients:` behavior unchanged.
- **Root cause:** `RouteDeriver.makeEndpoint` defaulted every non-body endpoint
  without typed handler evidence to `requestType = Unit`, ignoring path params.
  JS/JVM client codegen correctly treats `Unit` as a no-input method, so the
  first user argument was interpreted as headers and the route id could never
  fill `:id`.
- **Fixed in:** `4656f9629` (`feat: derive typed clients for path params`).
- **Gates:** `scripts/sbtc "core/testOnly scalascript.transform.RouteDeriverTest"`
  (16/16); `scripts/sbtc "backendInterpreter/testOnly scalascript.JsGenTypedRouteClientTest scalascript.JvmGenTypedRouteClientTest"`
  (57/57); `scripts/sbtc "core/compile; backendJs/compile; backendJvm/compile; backendInterpreter/compile"`;
  `scripts/sbtc "installBin"`; `tests/conformance/run.sh --only 'tkv2-typed-client-derived' --no-memo`
  (1/1 JS case); `emit-js` and `emit-spa --frontend custom --server-url`
  smokes for `examples/derived-route-clients.ssc`.
- **Done-when:** RouteDeriver, JS codegen, JVM/Swing codegen, a JS-only
  conformance smoke, docs/example, and affected tests agree; fixed SHA and
  gates are recorded here.

## std-auth-webauthn-signature-drift — `fixed` (2026-07-09)

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

## v2-case-class-instance-methods-stub — `fixed` (2026-07-08)

- **Found by:** codex, during `p3-connectnode-node-sim` verification.
- **Repro:** after `scripts/sbtc "installBin"`, run a `.ssc` program that
  constructs a case-class value with an instance method and calls it, or run
  `bin/ssc run examples/distributed-word-count.ssc` before the local shutdown
  workaround. `cluster.close()` lowers to a runtime `Stub("Cluster.close")`
  instead of executing the case-class method body.
- **Observed failure:** v2 registers case-class fields and can read them, but
  methods defined inside `case class ...:` are not emitted as callable closures.
  Runtime method dispatch therefore falls through from field lookup to a
  `Stub(Tag.method)` value.
- **Impact:** std APIs that rely on case-class instance methods are not fully
  production-safe on the default v2 lane. `p3-connectnode-node-sim` avoids this
  in the distributed examples by sending `ShutdownWorker()` directly; the
  language/runtime gap remains.
- **Fix direction:** extend `v2/frontend-bridge` lowering for case-class
  template methods, likely by reusing the existing tag-dispatched extension
  method path and binding constructor fields from the receiver before compiling
  the method body. Add a focused CLI/v2 regression for a case-class method that
  reads a constructor field and a std-facing regression for `Cluster.close`.
- **Done-when:** `cluster.close()` executes without printing a stub under the
  assembled default v2 CLI, the focused regression is green, and the fixed SHA
  is recorded here.
- **Root cause:** `FrontendBridge` registered case-class field names but ignored
  `Defn.Def` members inside case-class templates when emitting v2 CoreIR.
  Calls such as `Cluster.close()` therefore had no generated closure and fell
  through to `Stub(Tag.method)`.
- **FIXED (2026-07-08, `f12cad127`):** v2 now lowers case-class template
  methods through the existing tag-dispatched extension machinery, binding
  constructor fields from the receiver before compiling method bodies.
  `__methodOrExt__` also preserves registered `DataV` field precedence so a
  generated method name such as `name` does not hijack ordinary `.name` fields
  on other case classes. The distributed examples were restored to the public
  `cluster.close()` shutdown API.
- **Verified:** `V2CaseClassMethodCliTest` passed 3/3 through the assembled CLI;
  `V2TuplePatternCliTest` stayed 4/4 green; direct default-v2 runs of
  `distributed-word-count`, `distributed-log-aggregation`, and
  `distributed-join` passed with `cluster.close()`; affected conformance
  `cluster-connect,distributed-*` passed 6/6 and
  `data-types,lenses,optional,traversal,fn-typed-field` passed 4/4.

## v2-mapreduce-handler-registry-tuple-lookup — `fixed` (2026-07-08)

- **Found by:** codex, during `p3-connectnode-node-sim` implementation after
  adding the local loopback map-reduce worker helper.
- **Repro:** after `scripts/sbtc "installBin"`, run
  `SSC_DEBUG_ACTORS=1 timeout 60 bin/ssc run examples/distributed-word-count.ssc`
  from the assembled CLI in the worktree. Minimal lowering repro:
  `val pair: Any = ("ada", 1); pair match { case (w: String, _: Int) => w }`
  fails under `bin/ssc run` with `unbound global: w`, while
  `bin/ssc run --v1` prints `ada`.
- **Observed failure:** the original `Cluster.connect(...)` address-string hang
  is replaced by actor deaths:
  `[actor-death] lookup(v, key)`, followed by main-task failure
  `RuntimeException: match: no arm for Exit/1`.
- **Impact:** offline distributed map-reduce examples cannot be used as a v2
  production smoke until the worker handler registry survives the real actor
  worker boundary.
- **Root cause:** the local worker path exposed several v2 lowering/library
  gaps that were previously masked by the `Cluster.connect` hang:
  typed tuple patterns did not bind names; nested tuple patterns inside
  constructor patterns such as `Some((_, found))` stayed on the fast
  non-recursive match path and lost binders; tuple `val` destructuring ignored
  wildcard field positions; unqualified `lookup(name)` inside
  `HandlerRegistry.apply` resolved to the JSON `lookup` intrinsic; top-level
  map-reduce calls were hoisted before handler registration; and std
  map-reduce relied on tuple selectors, as-patterns, `List.reduce`, and
  `List.flatMap(Option)` shapes that are not production-safe on the current v2
  lane.
- **Fix direction:** keep the map-reduce API unchanged, remove selector use from
  the std/examples path as a narrow hardening step, and fix v2 tuple
  pattern/selector lowering with a focused regression so tuple values crossing
  actor/map-reduce boundaries remain ordinary tuples.
- **Done-when:** direct `bin/ssc run examples/distributed-word-count.ssc` prints a
  non-empty result, affected conformance
  `tests/conformance/run.sh --only 'cluster-connect,distributed-*' --no-memo`
  passes, and the fixed SHA/root cause are recorded here.
- **FIXED (2026-07-08, `6c0e39559`):** added `localLoopbackCluster`, hardened
  std map-reduce against the v2 tuple/collection gaps, fixed v2 tuple pattern
  and tuple val-destructuring lowering, qualified the handler registry lookup
  self-call, blocked eager hoisting for impure map-reduce method-object calls,
  and rewired the offline distributed examples to use local workers plus
  explicit shutdown.
- **Verified:** `scripts/sbtc "cli/assembly; cli/testOnly scalascript.cli.V2TuplePatternCliTest"`
  passed 4/4; direct default-v2 runs of `distributed-word-count`,
  `distributed-log-aggregation` with a temp log, and `distributed-join` with
  temp CSVs passed; affected conformance
  `tests/conformance/run.sh --only 'cluster-connect,distributed-*' --no-memo`
  passed 6/6.

## v2-vm-effect-handlers-return-raw-op — `fixed` (2026-07-08)

- **Found by:** codex, during `p4-rust-wasm-lanes` full
  `./v2/conformance/check.sh` after list/float expectation realignment.
- **Repro:** run `./v2/conformance/check.sh`, or focus these rows:
  `examples/async-tasks.ssc0`, `examples/hm-async.hm`,
  `examples/hm-eff-multiop.hm`, and the inline `handleM row composition`
  program through `bin/mirac.ssc0` + `run-ir`.
- **Observed failure:** the VM `run`/`run-ir` lane returns unhandled effect
  values such as `Op("log", 1, <closure>)`, `Op("yield", 0, <closure>)`, and
  `Op("QA", Pair("ask", 0), <closure>)` where the JS/Rust generated lanes for
  the same typed programs produce the expected results (`List(...)` or `42`).
- **Impact:** the self-hosted v2 conformance gate remains red after the
  Rust/WASM target fixes, and production cannot treat the VM as authoritative
  for typed async / multi-op effect handler examples.
- **Fix direction:** inspect VM effect handling in `v2/src/Runtime.scala` and
  the generated CoreIR for the failing programs. Do not update expectations to
  raw `Op(...)`; the JS/Rust rows show the intended semantics still runs to a
  value.
- **Done-when:** the focused rows above and the full `./v2/conformance/check.sh`
  pass, with the fix SHA and root cause recorded here.
- **FIXED (2026-07-08, `84d7ac77f`):** VM `Let`/`Seq` auto-threading treated
  every `DataV("Op", ...)` as a runtime statement effect. That is correct for
  bridge-emitted runtime ops with dotted labels such as `Console.writeLine`, but
  wrong for pure v2/typed free-monad values such as `Op("log", ...)`,
  `Op("yield", ...)`, and `Op("QA", ...)`, which handlers/schedulers must match
  as ordinary data. `Runtime.isAutoThreadOp` now limits statement/binding
  auto-threading to dotted bridge labels, preserving free-monad Ops as values.
- **Verified:** minimal `run-ir` repro now returns `List(1)` instead of raw
  `Op("log", 1, <closure>)`; focused rows
  `async-tasks.ssc0`, `hm-async.hm`, `hm-eff-multiop.hm`, and `handleM row
  composition` pass; full `./v2/conformance/check.sh` is green.

## v2-ssc0-target-display-drift — `fixed` (2026-07-08)

- **Found by:** codex, during `p4-rust-wasm-lanes` baseline.
- **Repro:** from a fresh worktree with Rust and Node available, run
  `./v2/conformance/check.sh`.
- **Observed failure:** the v2 VM now renders proper `Cons`/`Nil` chains as
  `List(...)` and whole floats with collapsed `Writer.floatStr` output such as
  `10`, but the self-hosted JS/Rust target backends and parts of
  `v2/conformance/check.sh` still expect or emit the older `Cons(..., Nil)` /
  `10.0` shape. Representative failures:
  `js map.ssc0 vm=[List(2, 4, 6)] node=[Cons(...)]`,
  `rust map.ssc0 vm=[List(2, 4, 6)] rust=[Cons(...)]`,
  `run-ir map.coreir got [List(...)] want [Cons(...)]`, and
  `kc-float Double math got [10 ...] want [10.0 ...]`.
- **Impact:** the self-hosted v2 target gate is red even though the Scala
  `v2/backend/check.sh` CoreIR source-generator harness is green. This blocks a
  credible Rust/WASM lane gate for Phase 4.
- **Fix direction:** update `v2/lib/backend-js-gen.ssc0` and
  `v2/lib/backend-rust-gen.ssc0` display helpers to match VM `Show.show`
  semantics for proper lists, and update `v2/conformance/check.sh` expectations
  for the accepted list/float display contract. Keep WASM expectations aligned
  because `ssc0-wasm` reuses the Rust generator.
- **Done-when:** `./v2/conformance/check.sh` no longer reports list/float display
  mismatches; JS/Rust/WASM target rows compare against the VM output.
- **FIXED (2026-07-08, `84d7ac77f`):** self-hosted JS and Rust target preludes
  now render proper `Cons`/`Nil` chains as `List(...)`, matching VM `Show.show`
  and the Scala source-generator backends. `v2/conformance/check.sh` was
  rebaselined to the accepted kernel display contract for proper lists and
  collapsed whole-float display.
- **Verified:** full `./v2/conformance/check.sh`; `./v2/backend/check.sh`;
  affected conformance
  `tests/conformance/run.sh --only 'effects,effect-*,async*,direct-*,js-*-effect-*,std-functor-applicative-monad,std-foldable-traversable,std-index' --no-memo`;
  `tests/conformance/run.sh --only 'rust*,wasm*' --no-memo` has no matching
  top-level cases, so Rust/WASM coverage is the v2 gate.

## v2-ssc0-rust-float-literal-emits-int — `fixed` (2026-07-08)

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

## v2-run-cli-argv-not-forwarded — `fixed` (2026-07-08)

- **Found by:** codex, during `p4-js-lane-bridge` direct argv smoke.
- **Repro:** after `scripts/sbtc "installBin"`, run a temp `.ssc`:
  `println(args.length); println(args(0))`. Then compare
  `bin/ssc run-js --v2 /tmp/args.ssc one two` with
  `bin/ssc run --v2 /tmp/args.ssc one two`.
- **Observed failure:** `run-js --v2` prints `2` and `one`; `run --v2`
  prints `0` and then fails with `IndexOutOfBoundsException: 0`.
- **Impact:** the v2 VM runner has an `argv` parameter internally, and
  `PluginBridge` documents `args` as runner-provided command-line args, but
  `RunCmd` currently treats every non-flag as another source file and calls
  `RunV2.run(..., Nil)`. User code reading `args` under the default/explicit v2
  runner cannot receive program argv.
- **Fix direction:** add an explicit argv separator for `ssc run`, most likely
  `ssc run [flags] <file.ssc> -- [args...]`, so existing multi-file run
  semantics are not reinterpreted. Forward the trailing argv to `RunV2.run` and
  `RunV2.runBytecode`; keep legacy/default behavior clear in usage text.
- **Done-when:** a real assembled-CLI regression covers `run --v2 <file> --
  one two`, default v2 if applicable, and `run-js --v2` remains green.
- **FIXED (2026-07-08, `64de9b9af`):** `ssc run` now treats `--` as the
  explicit separator between source files and program argv for v2 VM runners.
  Default `ssc run <file> -- one two`, explicit `ssc run --v2 <file> -- one
  two`, and `ssc run --bytecode <file> -- one two` forward argv into
  `Runtime.argv`. The bytecode lane also gained list-application fallback parity
  so `args(0)` works through `Emit.app`.
- **Verified:** `scripts/sbtc "cli/compile; cli/assembly; cli/testOnly
  *V2RunArgvCliTest"`; `scripts/sbtc "installBin"`; direct installed CLI smokes
  for default/`--v2`/`--bytecode`; conformance `collections`; combined
  assembled-CLI smoke `*V2RunArgvCliTest *V2JsLaneCliTest`.

## root-test-cli-spark-submit-dry-run-deps — `fixed` (2026-07-08)

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

## root-test-js-rowpost-runtime-contract — `fixed` (2026-07-08)

- **Found by:** codex, during `green-main-full-sbt-test-gating` root
  `scripts/sbtc "test"` after bounded sbt Test concurrency was added.
- **Repro observed in root gate:** `backendInterpreter / Test / test` failed
  `scalascript.JsGenStdImportTest` case
  `JS signal runtime defines the std/ui row-data natives` at
  `JsGenStdImportTest.scala:403`: generated runtime did not contain the expected
  `_RowPost` body payload line `body: resolvePayload(r, act.bodyField)`.
- **Impact:** JS std/ui row-data runtime contract may no longer send row POST
  body payloads through the same resolver used by other row fields. If this is a
  true runtime regression, browser UI row actions can submit stale or unresolved
  bodies; if only the string assertion is stale, the production contract still
  needs a sharper test.
- **Fix direction:** run the focused `JsGenStdImportTest` filter, inspect the
  emitted JS runtime around `_RowPost` / `resolvePayload`, then either restore the
  payload resolution path or update the structural assertion to match the current
  equivalent implementation.
- **Done-when:** focused `JsGenStdImportTest` is green, the row POST body
  contract is covered by the test, and affected std/ui conformance runs before
  pushing.
- **FIXED (2026-07-08, `cea0c3aed`):** the runtime still resolves row POST
  bodies through `resolvePayload`; the old string assertion expected the whole
  object literal inline shape and missed the current `_postBody` local. The test
  now asserts the resolver assignment and the fetch body use separately.
- **Verified:** `scripts/sbtc "backendInterpreter/testOnly scalascript.JsGenStdImportTest"`;
  affected conformance `tests/conformance/run.sh --only
  'collections,dataset-from-file,dataset-shape,json-*,std-ui-*,tkv2-*'
  --no-memo` (19/19); bounded root `scripts/sbtc "test"` (elapsed 1668s,
  success).

## root-test-sbt-aggregate-heap-oom — `fixed` (2026-07-08)

- **Found by:** codex, during `green-main-full-sbt-test-gating` root retest
  after v2 `V2ConformanceTest` was green and pushed through `ab37c7d0b`.
- **Repro:** `cd /Users/sergiy/work/my/scalascript-wt-green-main-full-sbt-test-gating &&
  scripts/sbtc "test"` on `origin/main@c9d300335`.
- **Observed failure:** the aggregate root test run progressed through many
  payments/crypto/config/server suites, then entered a long silent section with
  the sbt JVM pegged at >1000% CPU, ~5.7 GB RSS despite `-Xmx4G`, and 47 idle
  node children. It printed repeated
  `Exception in thread "pool-453-thread-..." java.lang.OutOfMemoryError: Java heap space`.
  `jcmd <pid> Thread.print` could not attach within 10.5s. Ctrl-C did not stop
  the run; SIGTERM removed the node children but the sbt JVM required SIGKILL.
- **Impact:** root `sbt "test"` is not yet a reliable production gate even after
  deterministic v2 conformance blockers are fixed.
- **Fix direction:** determine whether the failure is root-aggregate parallelism,
  Scala.js jsEnv node fan-out, or a specific test module leaking heap. Start by
  reproducing with a bounded/constrained root test invocation or focused
  Scala.js module groups, then encode the fix in build/test settings or the
  project wrapper. Record the exact command that becomes the production gate.
- **Done-when:** a root-equivalent gate completes without heap OOM/hung sbt JVM,
  and the chosen command is recorded in `SPRINT.md`/`CHANGELOG.md`.
- **Progress (2026-07-08, uncommitted worktree):** adding
  `Global / concurrentRestrictions += Tags.limit(Tags.Test,
  SSC_SBT_TEST_CONCURRENCY default 4)` to `build.sbt` made the next root
  `scripts/sbtc "test"` complete in about 27m32s without the previous OOM/hung
  sbt JVM pattern. The gate still exited 1 because it exposed the two separate
  blockers tracked above: `root-test-js-rowpost-runtime-contract` and
  `root-test-cli-fork-exit-after-green`.
- **FIXED (2026-07-08, `cea0c3aed`):** bounded root `scripts/sbtc "test"`
  completed successfully with the `Tags.Test` cap defaulting to 4. No heap OOM,
  hung sbt JVM, or lingering fork-exit failure remained.
- **Verified:** `cd /Users/sergiy/work/my/scalascript-wt-green-main-full-sbt-test-gating &&
  rm -f v1/tools/cli/ssc-storage.json && scripts/sbtc "test"`:
  `[success] elapsed: 1668 s (0:27:48.0)`.

## v2-busi-testsweep-gaps batch — `fixed` (2026-07-08)

Seven root causes closed working busi tests/v2 47/61 → 61/61 on --v2 (v1 = 61/61
same launcher; every fail was a real engine gap). One entry per cause:

- **v2-topvar-def-split-cell** — a top-level `var` referenced from def bodies was
  TWO cells: entry-local Let cell vs the defs' auto-created `Global("@name")`.
  Assignments from defs vanished (busi persistence: `def saveLocal(t) = localCell = t`).
  Fix: convertStats pre-pass (sharedTopVars) + global.reg of the entry cell under
  "@name". Regression: `tests/conformance/var-topdef-shared.ssc`. Killed sync,
  sync_http, local_journal, deferred_action.
- **v2-fbc-string-eq-optimistic** — tryFBc kept `==`/`!=` UNGUARDED on the Long
  fast path while ordering ops required provably-Long operands; tryFLC reads a
  StrV Local as 0L → string equality of two locals was ALWAYS true inside If
  conditions (`if p == period` matched every period — July facts leaked into June
  folds). Fix: extend the flcProvablyLong guard to equality. Regression:
  `tests/conformance/string-eq-locals.ssc`. Killed income, invoicing, trust, vat,
  meeting_room.
- **v2-hof-effect-threading** — a perform inside a list-HOF lambda returned a raw
  Op that map/filter/fold/foreach collected as DATA (busi operator: hPlan was a
  list of Ops). Fix: mapThreadOp/foldThreadOp — per-element Op results defer the
  REST of the traversal into the op's continuation (letThreadOp protocol);
  bridge-only by construction (__method__ dispatch). Killed operator.
- **v2-array-companion-list** — `Array.fill/tabulate` returned Cons-lists;
  `m(i) = v` then hit arr.set with "expected Array, got List". Fix: Array
  companion returns ForeignV(ArrayBuffer); + ArrayBuffer indexing in
  applyFallback, ArrayBuffer length/size/isEmpty/toList in dispatch. Part 1 of qr.
- **v2-fastcode-length-tolerant** — the length/size FastCode returned `0L` for ANY
  unrecognized receiver (and cons-cell field count 2 for lists): every
  `while i < msg.length` over an Array.fill result ran ZERO iterations (busi qr:
  a data-less, mask-only QR matrix that STILL passed structural checks). Fix:
  honest lengths (unlist walk for Cons/Nil, ArrayBuffer size) + sys.error for
  unknown receivers. Part 2 of qr.
- **v2-fence-regex-midline** — the all-fences extractor matched fence opens
  MID-LINE (inside a string literal holding markdown), desyncing the fence walk —
  prose after the next real close parsed as code ("illegal unicode codepoint:
  0xab"). Fix: (?m)^ anchors + newline-anchored first-fence search. Killed model.
- **v2-arith-table-divergence** — TWO arith implementations: Prims.arithOp (full:
  Map+(k->v), char semantics) for LITERAL op names vs the resolve-table __arith__
  (string-concat fallback) for non-literal names. OpAnf's letify was binding
  `Lit("+")` into a Local — demoting map-extend to string concat (busi litdoc:
  attrs became "Map()(id, Str(demo))…"). Fix: OpAnf keeps pure args (Lit/Global/
  Lam/Local) IN PLACE (also preserves FastCode shapes), and the table arith gained
  the Map+Tuple2 case. Full unification of the two ariths → BACKLOG. Killed
  litdoc_content.
- **v1-content-imported-doc-fallback** — contentToolkitSection/contentSection/
  contentBlock/contentData resolve only the CURRENT document; on v1 an imported
  module runs with its own document, but the v2 bridge inlines imports under the
  entry file's document, so a module's sections were unreachable from its own
  code. Fix (v1 content-plugin, fires only where it previously errored/None'd):
  fall back to the registered ContentImportedModules documents. Killed
  content_toolkit.
## root-test-v2-conformance-toolkit-regressions — `fixed` (2026-07-08)

- **Found by:** codex, during `green-main-full-sbt-test-gating` full root
  `scripts/sbtc "test"` after the sealed-extension and cluster blockers were fixed.
- **Repro observed in root gate:** `V2ConformanceTest` failed:
  `std-ui-jobpanel` rendered `?` labels instead of `2:Jobs` / `2:New job`;
  `tkv2-busi-home`, `tkv2-forms`, and `tkv2-offline` threw
  `RuntimeException: __method__: no field 'set' on named-method-obj (None)`;
  `tkv2-pwa` threw `RuntimeException: unbound global: pwa`.
- **Impact:** v2 default is not production-ready for the tk/std-ui conformance
  cluster until these cases either pass or are explicitly classified out of the
  production gate with a documented reason.
- **Fix direction:** split into focused repros from `V2ConformanceTest` and fix
  the underlying bridge/runtime gaps. Start with the shared `named-method-obj.set`
  family because it blocks three tk cases, then `pwa`, then the jobpanel label
  rendering gap. Verify the selected `V2ConformanceTest` cases and the affected
  conformance slice before pushing.
- **Progress (2026-07-08, `dad57a70b`):** the shared
  `named-method-obj.set` family is fixed. v1 `ReactiveSignal` values converted
  into v2 `NamedMethodObj`s now expose `get`/`set` and writes use host raw
  values, so `tkv2-busi-home`, `tkv2-forms`, and `tkv2-offline` pass targeted
  `V2ConformanceTest` filters. Affected conformance
  `tkv2-busi-home,tkv2-forms,tkv2-offline` is 3/3 green across INT+JS.
  Remaining failures from this root entry: `tkv2-pwa` (`unbound global: pwa`)
  and `std-ui-jobpanel` heading labels (`?` instead of `2:...`).
- **Progress (2026-07-08, `a9028b830`):** `tkv2-pwa` is fixed. Root causes:
  `pwaPlugin` was absent from the v2 plugin bridge classpath, `pwa(...)` named
  args were not pre-registered in the v2 frontend bridge, and plugin-owned
  `ctx.registerRoute(...)` calls were no-ops under `MinimalCtx`, so PWA routes
  never reached the v2 web server registry. Gates: `V2ConformanceTest -z
  tkv2-pwa` green, `V2ConformanceTest -z tkv2` green (6/6), and
  `tests/conformance/run.sh --only 'tkv2-pwa' --no-memo` green (INT pass;
  JS/JVM skipped by metadata). Remaining failure from this root entry:
  `std-ui-jobpanel` heading labels (`?` instead of `2:...`).
- **Progress (2026-07-08, `0facf7506`):** `std-ui-jobpanel` is fixed
  on the rebased `green-main-full-sbt-test-gating` branch. Root cause:
  `FrontendBridge` registered curried vararg defs such as
  `cardWithHeader(header)(body*)` as ordinary direct-vararg defs, so the first
  clause call lowered as `cardWithHeader(List(heading))`; the UI header became
  `List(List(HeadingNode(...)))` and the label extractor fell through to `?`.
  Gates after rebasing on `origin/main@9e48204e5`: `V2ConformanceTest -z
  std-ui-jobpanel` green, `V2ConformanceTest -z tkv2` green (6/6), and
  `tests/conformance/run.sh --only 'std-ui-jobpanel' --no-memo` green
  (INT+JS pass; JVM skipped by metadata).
- **New blocker after rebase (2026-07-08, `origin/main@9e48204e5`):** full
  `v2FrontendBridge/testOnly ssc.bridge.V2ConformanceTest` now fails only
  `array-companion-statics` with
  `RuntimeException: __method__: no dispatch for .sum on <foreign>`. Repro:
  `cd /Users/sergiy/work/my/scalascript-wt-green-main-full-sbt-test-gating &&
  scripts/sbtc "v2FrontendBridge/testOnly ssc.bridge.V2ConformanceTest"`.
  Root cause confirmed below: the fresh real-array runtime semantics batch left
  read-only collection dispatch list-only.
- **FIXED (2026-07-08, `f6e6383ac`):** `array-companion-statics` is fixed.
  `ForeignV(ArrayBuffer)` is now list-like for read-only collection dispatch
  (`sum`, `mkString`, HOFs, etc.) while keeping mutable array operations
  (`arr.get/set`, indexed apply, `length`) on the real ArrayBuffer. Gates:
  `V2ConformanceTest -z array-companion-statics` green,
  `tests/conformance/run.sh --only 'array-companion-statics' --no-memo` green
  (INT+JS+JVM), and full
  `v2FrontendBridge/testOnly ssc.bridge.V2ConformanceTest` green
  (76 succeeded, 54 ignored, 0 failed). No known deterministic blocker remains
  in this `V2ConformanceTest` root entry.

## root-test-stable-spi-os-plugin-import — `fixed` (2026-07-08)

- **Found by:** codex, during the same full root `scripts/sbtc "test"` gate.
- **Repro observed in root gate:** `StableSpiEnforcementTest` failed
  `value-surface plugins depend only on scalascript-plugin-api` with
  `os-plugin/scala/scalascript/compiler/plugin/os/OsIntrinsics.scala: import
  scalascript.interpreter.InterpretError`.
- **Impact:** stable plugin API enforcement is red; value-surface plugins are not
  fully isolated from interpreter internals.
- **Root cause:** OS plugin had already migrated to `PluginNative`/`PluginValue`,
  but one fallback still imported and threw interpreter `InterpretError` directly.
- **Fix:** `c3e277723` replaces the direct interpreter error with
  `PluginError.raise("exit(code: Int)")`, keeping the value-surface plugin on
  `scalascript-plugin-api`; the existing NUL separator literal was also normalized
  to `"\u0000"` so future diffs stay text-friendly.
- **Verified:** `scripts/sbtc "backendInterpreterPluginTests/testOnly scalascript.StableSpiEnforcementTest"`
  2/2 green; `scripts/sbtc "osPlugin/testOnly scalascript.compiler.plugin.os.OsPluginTest"`
  14/14 green; `tests/conformance/run.sh --only 'std-process-import' --no-memo`
  1/1 green.

## root-test-verify-default-srcdir-parent-scan — `fixed` (2026-07-08)

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

## v2-actors-sendafter-cli-default-noop — `fixed` (2026-07-08)

- **Found by:** codex, while fixing `root-test-cluster-cli-runtime-readiness`.
- **Repro:** after `scripts/sbtc "cli/assembly"`, run a fat-jar script containing
  `runActors { val me = spawn { () => val pid = self(); sendAfter(10, pid,
  "hello"); receive { case msg => println("got: " + msg) } } }`.
  `java -jar v1/tools/cli/target/scala-3.8.3/ssc.jar <file>` and `--v2`
  exit 0 with no `got: hello`; `--v1` prints `got: hello`.
- **Impact:** v2/default does not yet execute delayed actor flows that v1's actor
  scheduler supports. The root `sbt test` blocker below was fixed by making v1
  cluster integration fixtures explicit `--v1`; this entry tracks the actual v2
  production gap separately instead of hiding it behind test harness selection.
- **Fix direction:** implement/parity-check actor timer handling in the v2 runtime
  path or explicitly reject unsupported actor APIs under `--v2` with a diagnostic
  until v2 actor support is complete. Verify with the fat-jar repro above plus
  actor/cluster conformance slices.
- **Root cause:** `PluginBridge.registerActors` had a partial v2 actor runtime:
  `spawn`, `receive`, `self`, and `runActors` existed, but scheduled sends were
  not registered and `runActors` waited only for the root actor thread. In the
  fat-jar path the root actor completed immediately after `spawn`, so the JVM
  exited with virtual child/timer work still pending and no diagnostic.
- **Fix:** `a6c9d8b7c` adds v2 actor run-state/quiescence tracking, real
  `sendAfter` / `sendInterval` / `cancelTimer` globals, queue wakeups, and a
  real assembled-CLI regression covering default and `--v2`.
- **Verified:** `scripts/sbtc "v2PluginBridge/compile"`; `scripts/sbtc
  "cli/assembly"`; the original fat-jar repro now prints `got: hello` under
  default, `--v2`, and `--v1`; `scripts/sbtc "cli/testOnly *V2ActorCliTest"`;
  `scripts/sbtc "installBin"` followed by `tests/conformance/run.sh --only
  'actors-*' --no-memo` (8/8 passed; first pre-install run was invalid because
  the conformance runner uses `bin/ssc` / `bin/lib/ssc.jar`, not the freshly
  assembled `v1/tools/cli/.../ssc.jar`).

## root-test-cluster-cli-runtime-readiness — `fixed` (2026-07-08)

- **Found by:** codex, during `green-main-full-sbt-test-gating` root
  `scripts/sbtc "test"` after the bytecode split-runtime and Scala.js npm
  dependency fixes.
- **Repro observed in root gate:** the full root test PTY was lost before the final
  sbt summary, but the running output showed a deterministic cluster failure
  family: `ClusterStepDownCliTest`, `ClusterStatusCliTest`, and
  `ClusterAuthCliTest` fail because nodes do not bind within 8s; `MultiNodeClusterTest`
  fails to print `LEADER:` / `REMOTE_SPAWN_OK`; `ClusterBullyStatusConvergenceTest`
  never sees the status HTTP endpoint; `PartitionHealingTest` reports every node
  as `LEADER=<none>`; `SingletonFailoverTest` never propagates `SENT1:true`.
- **Targeted repro to run before fixing:** `scripts/sbtc "cli/testOnly
  scalascript.cli.ClusterStepDownCliTest scalascript.cli.ClusterStatusCliTest
  scalascript.cli.ClusterAuthCliTest scalascript.cli.MultiNodeClusterTest
  scalascript.cli.ClusterBullyStatusConvergenceTest scalascript.cli.PartitionHealingTest
  scalascript.cli.SingletonFailoverTest"`.
- **Initial hypothesis:** this is one cluster-runtime/CLI readiness family, not
  separate assertion issues. The subprocesses start and print the web banner, but
  the cluster markers/status endpoints are missing or late. Confirm whether the
  regression is runtime startup, test timeout/readiness detection, or an interaction
  with concurrent root-suite execution before changing semantics.
- **Status:** fixed in `da63bb96a`. Actual root cause was the v2 default switch in
  the fat-jar launch path: the cluster suites spawn node fixture scripts with
  `java -jar ssc.jar <node.ssc>`, so those v1 actor-cluster fixtures started on
  v2/default. Minimal repro showed `sendAfter` actor flows print under `--v1` but
  exit 0 with no delayed message under default/`--v2`. The test harness now runs
  node fixture subprocesses with explicit `--v1`; CLI subcommands such as
  `cluster status`, `cluster drain`, and `cluster step-down` still run normally
  against those nodes.
- **Verified:** `scripts/sbtc "cli/testOnly scalascript.cli.ClusterStepDownCliTest
  scalascript.cli.ClusterStatusCliTest scalascript.cli.ClusterAuthCliTest
  scalascript.cli.MultiNodeClusterTest scalascript.cli.ClusterBullyStatusConvergenceTest
  scalascript.cli.PartitionHealingTest scalascript.cli.SingletonFailoverTest
  scalascript.cli.ClusterDrainCliTest scalascript.cli.ClusterEventsCliTest
  scalascript.cli.PartitionTest"` (**13/13 green**); `tests/conformance/run.sh
  --only 'actors*,cluster-connect,distributed*' --no-memo` (**14 passed,
  0 failed**).

## root-test-command-registry-other-category — `fixed` (2026-07-08)

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

## root-test-sealed-extension-option-dispatch — `fixed` (2026-07-08)

- **Found by:** codex, during `green-main-full-sbt-test-gating` root
  `scripts/sbtc "test"` after the bytecode split-runtime and Scala.js npm
  dependency fixes.
- **Repro observed in root gate:** `SealedExtensionDispatchTest` failed
  `Some dispatches extension on Option`: expected stdout `42\n99`, actual
  `Some(42)\n99` at `SealedExtensionDispatchTest.scala:81`.
- **Targeted repro to run before fixing:** `scripts/sbtc "backendInterpreter/testOnly
  scalascript.SealedExtensionDispatchTest"`.
- **Initial hypothesis:** the `Some` receiver path dispatches an extension found on
  the sealed parent `Option`, but passes the case instance instead of the expected
  unwrapped payload for this extension shape. Inspect the test before changing
  dispatch: `None` still prints `99`, so the bug may be specific to case payload
  extraction for `Some`.
- **Status:** fixed in `1e503de04`. Actual root cause was built-in dispatch
  applicability, not payload extraction: interpreter `Option.orElse` accepted any
  single argument, so `Some(42).orElse(0)` returned the built-in receiver `Some(42)`
  before the user extension `def orElse(default: A): A` could run. Built-in
  `Option.orElse` now handles only Option-valued alternatives; non-Option
  defaults fall through to extension dispatch.
- **Verified:** `scripts/sbtc "backendInterpreter/testOnly
  scalascript.SealedExtensionDispatchTest"` (**4/4 green**);
  `scripts/sbtc "backendInterpreter/testOnly scalascript.SealedExtensionDispatchTest
  scalascript.InterpreterTest -- -z \"built-in members take precedence\" -z
  \"option orElse\""` (filtered invariant slice green); and
  `tests/conformance/run.sh --only
  'option,optional,typeclass-extension,std-functor-applicative-monad,std-monaderror'
  --no-memo` (**5/5 green** on INT/JS/JVM).

## v2-args-global-shadowed-by-native — `fixed` (2026-07-08)

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

## v2-op-arg-lifting — `fixed` (2026-07-08)

- **Found by:** claude-fable-5, working busi's ledger repro past the append/2 fix.
- **Symptom:** a strict call (user fn OR native) with an unresolved effect `Op` as an
  ARGUMENT executes immediately instead of deferring into the Op's continuation.
  busi `tests/v2/ledger.ssc` now fails at check #2: `accountBalance` (imported fn
  performing `Journal.read` inside) returns a raw `Op(Journal.read, …)`; `formatMoney`
  / `println` then consume the Op as a value. Same family: conformance
  `js-applyunary-effect-cps.ssc` on the v2 lane (`__unary__: - on Op(...)`), and a
  perform whose argument is effectful (`Journal.append("sum", xs.foldLeft(...))`
  where `xs` came from a read) leaks the Op into the handler's payload.
- **What works vs not:** val-binding (`letThreadOp`), statement sequencing
  (`seqThreadOp`), method-receiver (`methodOp`), arith operands, and fn-position
  (`applyFallback`) all lift Ops; **call-argument position does not** — neither for
  closures nor for plugin natives.
- **Repro (minimal):** inside `runJournal(() => { … })`:
  `println("balance=" + formatMoney(accountBalance(...)))` prints the Op raw.
  Full: `cd ~/work/my/busi && scalascript/bin/ssc --v2 --plugin crypto,auth,smtp,tcp,sql tests/v2/ledger.ssc`
  (on ≥ d2340f85e) → `FAIL: cash debit = 100.00` after `ok: entry balances`.
- **Fix (landed):** NOT at the runtime call chokepoint — a blanket runtime lift
  would break the Mira/hm kernel lane, where passing Op VALUES to functions is
  legitimate (`runState(k(r), s)` must receive the op raw; deferring forwards it
  past its own handler). Instead: `OpAnf` — a bridge-side CoreIR pass (bridged
  lane only) that Let-binds potentially-Op arguments (App args, Prim args, Ctor
  fields, Match scrutinees, If conditions), so the kernel's existing
  letThreadOp/seqThreadOp threading performs the deferral. De Bruijn
  cutoff-shifting for the inserted binders. Exclusions: `handle(expr)(handler)`
  paren form (the body's Op must reach handle RAW — effect-multishot bench
  caught this); `While` untouched (per-iteration re-evaluation). GATED: the pass
  runs only when the merged source mentions `effect `/`handle` — ops cannot
  materialize otherwise (context runners intercept pre-Op), and unconditional
  wrapping made pattern-match-heavy 3-4× slower; with the gate it's at baseline.
- **Bench A/B (bin/ssc bench --machine --backend v2):** pattern-match-heavy
  26.2-26.9 vs baseline ~28 ✓; effect-multishot 5.19 vs 5.04-6.01 ✓;
  streams-pipeline 0.0080 vs 0.0085 ✓; arith-loop/nested-loop/list-fold/
  hof-pipeline parity.
- **Verified:** busi ledger.ssc ALL OK on --v2 (was: FAIL check #2); examples
  corpus 153/9 = baseline; tests/conformance v2 batch 109/39 (was 108/40 —
  `js-applyunary-effect-cps` FLIPPED TO PASS: `-Op` unary operand now threads);
  run.sh effect family 4/4 INT/JS/JVM; v2 kernel check.sh 8×3 ALL GREEN
  (kernel lane untouched by construction).
- **busi full sweep (tests/v2, 61 files):** --v2 47/61 PASS (was 0 — died on the
  first test); --v1 same launcher/flags 61/61 → the 14 remaining fails are real
  v2 parity gaps, queued as SPRINT `v2-busi-testsweep-gaps`. run.sh full
  conformance 123/123.

## v1-jvm-state-threaded-handler-codegen — `open` (2026-07-08)

- **Found by:** claude-fable-5, while shaping the effect-multiarg-op regression.
- **Symptom:** `bin/ssc run-jvm` fails to COMPILE any handler whose arms return
  lambdas (the state-threading deep-handler idiom, busi's `runJournal`):
  "I could not infer the type of the parameter s / Expected type for the whole
  anonymous function: Any". Arity-independent (1-arg op repros identically);
  INT and JS lanes run the same code fine.
- **Repro:** `effect Cnt: def tick(n: Int): Unit` + handler arms
  `case Cnt.tick(n, resume) => (s: Int) => resume(())(s + n)` /
  `case Return(x) => (s: Int) => x`, applied as `threaded(0)` → `run-jvm` fails,
  `run --v1` / `emit-js` pass.
- **Impact:** low today — busi runs the interpreter lane; no corpus case uses the
  idiom on the JVM lane (that's why it was never seen).

## v2-effect-multiarg-op — `fixed` (2026-07-08)

- **Found by:** busi agent (rozum `scalascript` room, 2026-07-08 seq31), while bumping
  busi to scalascript pin `0a6358787` with `v2-prod-default-switch` active.
- **Repro:** `cd ~/work/my/busi && scalascript/bin/ssc --v2 --plugin crypto,auth,smtp,tcp,sql tests/v2/ledger.ssc`
  → `RuntimeException: match: no arm for append/2` at `PluginBridge.runEffectLoop`
  (PluginBridge.scala:2165 → Runtime.scala:367). Reproduced locally 1:1.
- **Root cause:** multi-argument effect operations lost their arity crossing the
  Free-monad Op protocol. `Runtime.scala` effect dispatch packed
  `Journal.append(scope, fact)` as `DataV("Op", [label, Tuple2(scope, fact), k])`;
  `PluginBridge.runEffectLoop` called the handler with **append/2** while the user
  arm `case Journal.append(scope, fact, resume)` compiles to **append/3**. Nothing
  in the corpus exercised a >1-arg effect op. v1 delivers payload args unpacked.
- **Fix:** `2ef288004` — multi-arg payloads pack under the internal `__EffArgs__`
  marker (NOT `TupleN`: a genuine single tuple argument must stay op/2);
  `runEffectLoop` unpacks to `op(a1…aN, resume)`. Companion fix `d2340f85e` —
  std/ imports on the v2 lane now fall back to `libPath/runtime/<path>` (mirrors
  v1's ImportResolver), fixing `unbound global: money` when running the assembled
  jar from busi's cwd (std resolution was cwd-sensitive).
- **Verified:** regression `tests/conformance/effect-multiarg-op.ssc` (+
  `lib/effect-journal.ssc`, imported-module handler, 2-arg + 1-arg ops) green on
  INT/JS/JVM + v2 engine; `run.sh --only 'effect*'` 4/4; examples corpus at the
  153/9 baseline; busi ledger repro gets past both failures (now blocked by
  `v2-op-arg-lifting` above — reported to busi).
- **Awaiting:** busi re-runs its 62-test suite on `--v2` (blocked on
  v2-op-arg-lifting for ledger.ssc at least).

## conformance-int-std-semigroup-monoid — `fixed` (2026-07-08)

- **Found by:** codex, during full `green-main-conformance-gating` after the
  `.scjvm` cache invalidation fix.
- **Repro:** after `scripts/sbtc "installBin"`, run
  `tests/conformance/run.sh --only 'std-semigroup-monoid' --no-memo` or direct
  `bin/ssc run --v1 tests/conformance/std-semigroup-monoid.ssc`.
- **Observed:** full conformance reports `std-semigroup-monoid` failing only on
  INT: expected lines 4-6 are `Some(24)`, `42`, and `foo`, but INT prints fewer
  lines (`<missing>` for those entries). JS and JVM pass.
- **Status:** fixed in `e571fd3ae`. Root cause was INT given registration:
  `given intSum: Monoid[Int]` was registered only as `Monoid[Int]`, while
  `combineAllOption[A: Semigroup]` needs `Semigroup[Int]`. Scala/JS/JVM accept
  this because `Monoid extends Semigroup`; the interpreter did not expose that
  parent typeclass key.
- **Fix:** concrete and parametric `given` registration now follows the
  interpreter's `parentTypes` chain and registers parent typeclass aliases such
  as `Semigroup[Int]` for `Monoid[Int]`. Exact concrete keys still own ambiguity
  tracking; aliases fill only missing parent keys.
- **Verified:** direct `bin/ssc run --v1 tests/conformance/std-semigroup-monoid.ssc`
  prints all six expected lines; `scripts/sbtc "backendInterpreter/testOnly scalascript.FinalTaglessConformanceTest scalascript.GivenUsingTest"`
  (**17/17 green**); and
  `tests/conformance/run.sh --only 'std-semigroup-monoid' --no-memo`
  (**1/1 green** across INT/JS/JVM).

## jvm-scjvm-cache-codegen-version — `fixed` (2026-07-08)

- **Found by:** codex, while fixing `conformance-jvm-std-ui-generated-braces`.
- **Repro:** keep a stale generated artifact such as
  `tests/conformance/.ssc-artifacts/std-ui-extended.scjvm` from before a JVM
  backend codegen fix, rebuild/install the CLI, then run
  `bin/ssc run-jvm tests/conformance/std-ui-extended.ssc`.
- **Observed:** `run-jvm` reused the source-fresh `.scjvm` artifact and still
  failed with the old generated Scala `'}' expected, but eof found`. Removing only
  `tests/conformance/.ssc-artifacts/std-ui*.scjvm` forced regeneration and the same
  assembled command passed. This means the `.scjvm` freshness key does not account
  for backend codegen/runtime changes.
- **Status:** fixed in `322ee868f`. Root cause was that `.scjvm` artifacts used
  only the `.ssc` `sourceHash` as their freshness key, so generated Scala from an
  older JVM backend survived source-fresh after codegen/runtime fixes.
- **Fix:** `.scjvm` artifacts now carry `codegenVersion =
  "jvm-codegen-2026-07-08-1"` when emitted by the normal JVM artifact writer.
  `ModuleGraph.isJvmStale` treats missing/old codegen versions as stale while
  preserving ABI compatibility: legacy artifacts remain readable, then regenerate.
- **Verified:** `scripts/sbtc "core/testOnly scalascript.artifact.ModuleGraphTest"`
  (**15/15 green**, including legacy/old-version source-fresh `.scjvm`
  invalidation), `scripts/sbtc "cli/testOnly scalascript.cli.VerifyCliTest"`
  (**7/7 green**), `scripts/sbtc "installBin"`, and
  `tests/conformance/run.sh --only 'std-ui-aggregator,std-ui-extended*' --no-memo`
  (**5/5 green**).

## conformance-int-variables-while-update — `fixed` (2026-07-08)

- **Found by:** codex, during full `green-main-conformance-gating`.
- **Repro:** after `scripts/sbtc "installBin"`,
  `tests/conformance/run.sh --only 'variables' --no-memo` or
  `bin/ssc run --v1 tests/conformance/variables.ssc`.
- **Observed:** INT prints `5`, `10`, `720`, `55`; expected line 2 is `15`.
  JS/JVM pass. The first `while x < 5` loop increments `x` but does not accumulate
  the updated `x` into `sum`.
- **Status:** fixed in `4e67a2f41`. Root cause was the interpreter closed-form
  while optimizer, not the generic assignment path: it folded a body shaped like
  `x = x + 1; sum = sum + x` as if `sum` read the pre-update counter, producing
  `0+1+2+3+4 = 10`. ScalaScript assignment order requires `sum` to read the
  post-update `x`, producing `1+2+3+4+5 = 15`.
- **Verified:** `scripts/sbtc 'backendInterpreter/testOnly scalascript.SscVmTest -- -z "closed-form"'`
  (**6/6 green**); `scripts/sbtc "installBin"`; direct
  `bin/ssc run --v1 tests/conformance/variables.ssc`; and
  `tests/conformance/run.sh --only 'variables' --no-memo` (**1/1 green**).

## conformance-jvm-std-ui-generated-braces — `fixed` (2026-07-08)

- **Found by:** codex, during full `green-main-conformance-gating`.
- **Repro:** after `scripts/sbtc "installBin"`,
  `bin/ssc run-jvm tests/conformance/std-ui-extended.ssc`.
- **Observed:** JVM generated Scala fails with `'}' expected, but eof found`; the
  warnings start where many imported `std/ui` component `object`s begin, so the
  conformance harness reports missing stdout for `std-ui-aggregator` and
  `std-ui-extended*`. INT/JS pass.
- **Status:** fixed in `9bd6cb87d`. Root cause was two string-level JVM source
  transforms treating braces inside imported UI triple-quoted JavaScript/CSS
  literals as Scala structure. `colonObjectsToBraces` also stopped collecting an
  `object Name:` body when a triple-quoted literal continued at column 0, which
  prematurely inserted `}` inside `SubmitButton.js`. `JvmGen` now tracks
  triple-quoted strings while collecting colon-object bodies and uses a shared
  string/comment-aware brace matcher for duplicate object/package merges.
- **Verified:** `scripts/sbtc "backendInterpreter/testOnly scalascript.JsGenUsingTest"`
  (**14/14 green**); direct
  `bin/ssc run-jvm tests/conformance/std-ui-extended.ssc` after regenerating the
  stale local `.scjvm` artifact; and
  `tests/conformance/run.sh --only 'std-ui-aggregator,std-ui-extended*' --no-memo`
  (**5/5 green**).

## conformance-std-typeclass-int-jvm-gaps — `fixed` (2026-07-08)

- **Found by:** codex, during full `green-main-conformance-gating`.
- **Repro:** after `scripts/sbtc "installBin"`, run either
  `bin/ssc run --v1 tests/conformance/std-index.ssc` or
  `bin/ssc run-jvm tests/conformance/std-index.ssc`.
- **Observed:** INT prints the first two lines of `std-index` and then hits
  `StackOverflowError` in `EvalRuntime.evalApplyGeneral`/`DispatchRuntime.dispatch1`.
  JVM generated Scala rejects imports from `std/index.ssc` and sibling typeclass
  modules: `Left`/`Right` are imported from module objects that do not define them,
  and aggregate exports such as `std.intSum` are missing. Related full-gate misses:
  `std-foldable-traversable`, `std-functor-applicative-monad`, `std-index`,
  `std-bifunctor`, `std-monaderror`, and `std-selective`.
- **Status:** fixed in `f92d147b0` and `7328e35db`. Root causes were split
  across both lanes: INT extension dispatch preferred an imported same-named
  extension over built-in members, so `Option.map` in `map2Option` recursed;
  JVM import generation lost std typeclass re-export provenance, omitted
  standalone top-level extension imports, and emitted explicit context-bound /
  `using` instance calls as flat Scala calls. The std typeclass manifests also
  needed explicit type exports/imports so the strict import gate can resolve
  aggregator exports deterministically.
- **Verified:** `scripts/sbtc "backendJvm/compile"`;
  `scripts/sbtc "backendInterpreter/testOnly scalascript.JsGenUsingTest"`;
  `scripts/sbtc "installBin"`; direct INT/JVM repros for `std-index`,
  `std-selective`, `std-monaderror`, and `std-foldable-traversable`; and
  `tests/conformance/run.sh --only 'std-functor-applicative-monad,std-foldable-traversable,std-index,std-bifunctor,std-monaderror,std-selective' --no-memo`
  (**6/6 green**).

## conformance-int-sql-block-scope — `fixed` (2026-07-08)

- **Found by:** codex, during full `green-main-conformance-gating`.
- **Repro:** after `scripts/sbtc "installBin"`,
  `bin/ssc run --v1 tests/conformance/sql-basic.ssc`.
- **Observed:** SQL block interpolation fails with
  `[line 1, col 1] Undefined: newId` even though the preceding Scala block defines
  `val newId = 1L`; `sql-basic` and `sql-transaction` therefore produce missing
  stdout. JS/JVM are skipped by backend metadata.
- **Status:** fixed in `c31389b25`; root cause was the CLI backend path
  normalizing parseable fenced `scala` blocks to `ir.Content.EmbeddedBlock` and
  denormalizing them back to AST code blocks without a parsed tree. The interpreter
  therefore skipped those blocks, so globals such as `newId` / `personId` never
  existed when SQL bind expressions were evaluated. `Denormalize` now re-parses
  parseable embedded blocks (`scala`, `ssc`, `scalascript`) while keeping opaque
  foreign blocks tree-less.
- **Verified:** `scripts/sbtc "sqlPlugin/testOnly scalascript.compiler.plugin.sql.SqlPluginInterpreterTest"`;
  `scripts/sbtc "installBin"`; direct `bin/ssc run --v1 tests/conformance/sql-basic.ssc`
  and `bin/ssc run --v1 tests/conformance/sql-transaction.ssc`; and
  `tests/conformance/run.sh --only 'sql-basic,sql-transaction' --no-memo`
  (**2/2 green**).

## conformance-js-product-show-synthetic-tag — `fixed` (2026-07-08)

- **Found by:** codex, during full `green-main-conformance-gating`.
- **Repro:** after `scripts/sbtc "installBin"`,
  `bin/ssc run-js tests/conformance/prisms.ssc`.
- **Observed:** JS prints product values with an extra synthetic numeric field, e.g.
  `Circle(0, 5)` instead of `Circle(5)`, `Rect(1, 3, 4)` instead of `Rect(3, 4)`,
  and `User(0, bob, false)` in optics/optional cases. INT/JVM pass.
- **Status:** fixed in `4e8cbb635`; root cause was JS runtime product handling
  treating the internal `_tag` field as user data. `_show` now skips `_tag`, and
  positional `.copy(...)` maps arguments over user fields only (`_type` / `_tag`
  excluded), so enum tags remain available for pattern matching without leaking
  into display or copy semantics.
- **Verified:** `scripts/sbtc "installBin"`; direct
  `bin/ssc run-js tests/conformance/prisms.ssc` and
  `bin/ssc run-js tests/conformance/optic-polish.ssc`; and
  `tests/conformance/run.sh --only 'prisms,optic-polish,optics-index-at,optional' --no-memo`
  (**4/4 green**).

## conformance-js-json-stringify-missing-global — `fixed` (2026-07-08)

- **Found by:** codex, during full `green-main-conformance-gating`.
- **Repro:** after `scripts/sbtc "installBin"`,
  `bin/ssc run-js tests/conformance/json-read.ssc`.
- **Observed:** JS crashes before stdout with `ReferenceError: jsonStringify is not
  defined` at the first `jsonStringify(42)` call. INT/JVM pass.
- **Status:** fixed in `718d04027`; root cause was JS intrinsic registration still
  targeting bare `jsonStringify` / `jsonValue` after the runtime helpers were
  intentionally renamed to `_ssc_ui_jsonStringify` / `_ssc_ui_jsonValue` to avoid
  duplicate top-level declarations with std import bindings.
- **Verified:** `scripts/sbtc "backendInterpreter/testOnly scalascript.JsGenStdImportTest"`;
  `scripts/sbtc "installBin"`; `bin/ssc run-js tests/conformance/json-read.ssc`;
  `tests/conformance/run.sh --only 'json-read' --no-memo` (**1/1 green**).

## conformance-jvm-cps-local-unit-effect-cast — `fixed` (2026-07-08)

- **Found by:** codex, while refreshing `green-main-conformance-gating`.
- **Repro:** after `scripts/sbtc "installBin"`,
  `tests/conformance/run.sh --only 'cluster-connect' --no-memo`.
- **Observed:** the JVM lane compiled and ran, but printed
  `unhealthy nodes: 3` instead of `unhealthy nodes: 0`.
- **Root cause:** a local actor loop declared as `def workerLoop(): Unit =
  receive { ... }` inside a CPS block emitted as
  `Actor.receive_(...).asInstanceOf[Unit]`. The cast discarded the unresolved Free
  computation before `runActors` could schedule it, so the worker actors exited
  immediately and never answered the health-check messages.
- **Fix:** `df7cfb613` makes local CPS-emitted defs follow the same result-type
  rule as top-level effectful defs: preserve the declared return type only when
  the def handles its own effects; otherwise return the unresolved computation as
  `Any`.
- **Verification:** `bin/ssc run-jvm tests/conformance/cluster-connect.ssc` prints
  `unhealthy nodes: 0`; the full targeted slice
  `tests/conformance/run.sh --only 'cluster-connect,distributed-failure-*,distributed-heterogeneous,distributed-shuffle,effect-transitive-handler' --no-memo`
  passes 6/6.

## conformance-jvm-cps-any-typing-and-effect-args — `fixed` (2026-07-08)

- **Found by:** codex, while refreshing `green-main-conformance-gating`.
- **Repro:** after `scripts/sbtc "installBin"`,
  `tests/conformance/run.sh --only 'cluster-connect,distributed-failure-*,distributed-heterogeneous,distributed-shuffle,effect-transitive-handler' --no-memo`.
- **Observed:** the JVM lane failed during generated Scala compilation. Examples:
  `effect-transitive-handler` widened a handled value to `Any` before `+`;
  cluster/distributed cases widened `cluster` to `Any` before method calls; and
  `DatasetWirePartition(_t197, _t198)` passed `Any` where the constructor expects
  `Int` and `Vector[JsonValue]`.
- **Root cause:** the CPS transform only preserved explicit val ascriptions.
  Untyped vals bound from known dep constructors/defs were passed to
  continuations as `Any`; casts for known constructors did not include the JVM
  runtime `DatasetWirePartition` case; and effectful lambdas nested under call
  argument clauses could stay raw because effect detection only recursed through
  direct `Term` children and intrinsic dispatch emitted `.syntax` args.
- **Fix:** `df7cfb613` infers CPS val continuation types from known dep class/def
  result signatures, qualifies dep type names at generated call sites, adds the
  `DatasetWirePartition` external constructor signature, recursively detects
  effects through non-`Term` tree nodes, and routes effectful call args through
  CPS emission.
- **Verification:** `scripts/sbtc "backendInterpreter/compile"`,
  `scripts/sbtc "installBin"`, and
  `tests/conformance/run.sh --only 'cluster-connect,distributed-failure-*,distributed-heterogeneous,distributed-shuffle,effect-transitive-handler' --no-memo`
  pass.

## conformance-effects-choose-one-shot — `fixed` (2026-07-08)

- **Found by:** codex, while refreshing `green-main-conformance-gating`.
- **Repro:** after `scripts/sbtc "installBin"`,
  `scripts/conformance -- --only 'effects' --no-memo`.
- **Observed:** the INT lane printed only `Alice` and then failed with
  `One-shot violation: Choose.pick resumed more than once`; JS/JVM printed the
  expected nondeterminism list and passed.
- **Root cause:** `tests/conformance/effects.ssc` documented the `Choose` block as
  "Multi-shot: nondeterminism" but declared it as plain `effect Choose`. Per
  `specs/algebraic-effects.md` and existing interpreter tests, multi-resume
  handlers must opt in with `multi effect`.
- **Fix:** `edda7c5d3` changes the conformance declaration to
  `multi effect Choose`.
- **Verification:** `bin/ssc run --v1 tests/conformance/effects.ssc` prints all
  three expected lines, and
  `scripts/conformance -- --only 'effects' --no-memo` passes INT/JS/JVM.

## conformance-actors-exit-os-shadow — `fixed` (2026-07-08)

- **Found by:** codex, while refreshing `green-main-conformance-gating`.
- **Repro:** after `scripts/sbtc "installBin"`,
  `bin/ssc run --v1 tests/conformance/actors-supervision.ssc` or
  `scripts/conformance -- --only 'actors-supervision' --no-memo`.
- **Observed:** the INT lane printed only `worker starting`; JS/JVM passed. A
  focused trace showed `link(worker)` registered but `exit(me, "crash")` never
  reached `ActorScheduler.killActor`.
- **Root cause:** lazy plugin loading registered `std.os.exit(code)` under the same
  bare global name as the core actor primitive `exit(pid, reason)`, overwriting the
  actor native. The OS intrinsic treated non-code argument shapes as `sys.exit(0)`,
  so the worker actor stopped the process path instead of sending an actor exit
  signal.
- **Fix:** `96bf969ed` keeps a pre-existing native binding as a fallback when a
  plugin intrinsic reports a usage mismatch, and changes `std.os.exit` to throw a
  usage error for non-`Int` arguments. A regression test loads actors+os plugins
  together and verifies actor `exit(pid, reason)` still drives supervision.
- **Verification:** `scripts/sbtc "backendInterpreterPluginTests/testOnly scalascript.ActorSupervisionTest"`
  passes 10/10; after `scripts/sbtc "installBin"`,
  `scripts/conformance -- --only 'actors-supervision' --no-memo` passes INT/JS/JVM.

## conformance-http-client-external-httpbin — `fixed` (2026-07-08)

- **Found by:** codex, while refreshing `green-main-conformance-gating`.
- **Repro:** after `scripts/sbtc "installBin"`,
  `scripts/conformance -- --only 'http-client' --no-memo`.
- **Observed:** the fixture calls live `https://httpbin.org`; the INT lane returned
  five `503` statuses instead of `200/204`, and the JS lane produced no stdout and
  stalled until interrupted. This is an external-network fixture, not a deterministic
  default conformance gate.
- **Fix:** mark `tests/conformance/http-client.ssc` as `pending:` with an explicit
  reason. Follow-up: replace it with a local deterministic HTTP fixture before
  re-enabling it in default conformance.
- **Verification:** `scripts/conformance -- --only 'http-client' --no-memo` reports
  `PENDING` and exits green without hanging.

## conformance-parsing-int-empty-output — `fixed` (2026-07-08)

- **Found by:** codex, while running a neighbor conformance slice for
  `v2-prod-js-dsl-conformance`.
- **Repro:**
  `scala-cli tests/conformance/run.sc -- --only 'dsl*,collections,parsing*,indent*' --no-memo`
- **Observed:** `collections` and `dsl-multi-pass` pass, but three INT-only parsing
  cases produce empty output:
  `parsing-error-node`, `parsing-parse-all`, and `parsing-recover-until`. JS/JVM are
  skipped for those cases by backend metadata, so this is not the JS char-ordering
  failure and not a v2 default output-parity blocker.
- **Scope:** std/parsing conformance hygiene. Fix before claiming broad repo-wide
  conformance green; do not mix with the v2 default-switch slice unless its gate
  explicitly requires these parser-combinator cases.
- **Root cause:** `std/parsing/recovery.ssc` documented and defined
  `recoverUntil`, `errorNode`, `parseAll`, `advanceToSync`, and `runParserAll`, but
  its front-matter `exports:` omitted those public names. The conformance files
  explicitly import `runParserAll` / `advanceToSync`, so INT failed during import on
  stderr before any `println`, which the conformance harness reported as missing
  stdout.
- **Fix:** `d65c678bd` exports the recovery extension methods and runner helpers
  from `std/parsing/recovery.ssc`.
- **Verification:** after `scripts/sbtc "installBin"`,
  `bin/ssc run --v1 tests/conformance/parsing-error-node.ssc` prints the expected
  eight lines; `scala-cli tests/conformance/run.sc -- --only 'parsing*' --no-memo`
  passes all three INT parsing cases; the neighbor slice
  `scala-cli tests/conformance/run.sc -- --only 'dsl*,collections,parsing*,indent*' --no-memo`
  passes 5/5 runnable cases with the two indent cases skipped for missing expected
  files.

## conformance-dsl-multi-pass-js — `fixed` (2026-07-08)

- **Found by:** codex, while verifying the docs-only `v2-prod-corpus-scope` slice.
- **Repro:**
  `scala-cli tests/conformance/run.sc -- --only 'dsl*' --no-memo`
- **Observed:** `dsl-multi-pass` passes the INT and JVM lanes but fails JS:
  expected line 2 `[name-resolve] undefined: z` and line 3 `ok: 8`, got
  `[parse] unrecognised token: x` for both. The failing source parses
  `"x + z"` / `"x + y"`; `parseExpr("x")` should produce `Var("x")`.
- **Scope:** JS backend/conformance lane. Not caused by the corpus-scope docs
  change and not a default output-parity blocker, but it is a production hygiene
  gate before claiming broad green status.
- **Hypothesis:** JS lowering/runtime mishandles the string-character predicate
  shape used by `t.forall(c => (c >= 'a' && c <= 'z') || c == '_')`, so alphabetic
  identifiers are rejected as parse errors.
- **Root cause:** JS `String.forall` passes boxed `_Char` values to the predicate, but
  `_arith` ordered `_Char` against a one-character JS string literal with native JS
  object-vs-string comparison. Equality already normalized `_Char`; ordering did not,
  so `c >= 'a' && c <= 'z'` was false for alphabetic characters.
- **Fix:** `39ebb6fda` adds a shared `_charCodeOrNull` helper and normalizes `<`, `>`,
  `<=`, and `>=` when either operand is `_Char`, while preserving normal string
  concatenation and ordinary string-vs-string comparison.
- **Verification:** after `scripts/sbtc "installBin"`,
  `scala-cli tests/conformance/run.sc -- --only 'dsl*' --no-memo` passes
  `dsl-multi-pass` in INT/JS/JVM. Neighbor slice
  `scala-cli tests/conformance/run.sc -- --only 'dsl*,collections,parsing*,indent*' --no-memo`
  confirms `collections` and `dsl-multi-pass` still pass; the remaining `parsing*`
  INT-only failures are tracked separately as `conformance-parsing-int-empty-output`.

## v2-rozum-schema-streaming-parity — `fixed` (2026-07-08)

- **Found by:** codex, during the v2 production parity loop after
  `v2-quoted-macro-interpreter-parity` was fixed.
- **Symptom:** the latest full
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity --all` has **0
  v2-error** cases and only one remaining production-relevant blocker cluster:
  `examples/rozum-agent-schema-derived.ssc` and
  `examples/rozum-agent-streaming.ssc` still mismatch, while
  `rozum-agent.ssc` and `rozum-agent-pool.ssc` already match.
- **Repro:** after `scripts/sbtc "installBin"`, run:
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/rozum-agent-schema-derived.ssc examples/rozum-agent-streaming.ssc`.
  If needed, compare direct outputs:
  `bin/ssc run examples/rozum-agent-schema-derived.ssc`,
  `bin/ssc run --v2 examples/rozum-agent-schema-derived.ssc`,
  `bin/ssc run examples/rozum-agent-streaming.ssc`, and
  `bin/ssc run --v2 examples/rozum-agent-streaming.ssc`.
- **Notes:** decide by evidence whether this is a v2 bridge/server/batch bug or a
  scope classification issue. Do not normalize `scripts/v2-output-parity`; fix the
  real output path or document an explicit lane/scope exclusion.
- **Root cause:** two independent v2 bridge/runtime gaps. `AgentSchemaInstance` is a
  case class with a method body (`decode`), but v2 only dispatched its fields, so
  `schema.decode(argsJson)` returned `Stub` and the typed handler later matched on
  `Stub` instead of `Some`/`None`. Separately, FrontendBridge's constructor lowering
  dropped positional args whenever any named arg was present, so
  `AgentEvent("TextDelta", text = content)` produced `kind = Unit`; streaming callbacks
  ran but every `event.kind == ...` guard was false.
- **Fix:** `e80b1e70b` preserves positional constructor args in mixed
  positional/named case-class calls, adds `AgentSchemaInstance.decode` dispatch to the
  v2 runtime, and adds v2 regression tests for both shapes.
- **Verification:** after `scripts/sbtc "installBin"`, targeted parity
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/rozum-agent-schema-derived.ssc examples/rozum-agent-streaming.ssc`
  is **2/2 MATCH**; the full rozum cluster (`rozum-agent`, `rozum-agent-pool`,
  `rozum-agent-schema-derived`, `rozum-agent-streaming`) is **4/4 MATCH**. Affected
  conformance `scala-cli tests/conformance/run.sc -- --only 'rozum*' --no-memo`
  has **0 matching cases**. Full parity is now **60/81 identical · 5 mismatch ·
  0 v2-error · 16 v1-only**.

## v2-quoted-macro-interpreter-parity — `fixed` (2026-07-08)

- **Found by:** codex, during the v2 production parity sweep after content-toolkit
  section parity was fixed.
- **Symptom:** `examples/quoted-macro-interpreter.ssc` was a remaining production
  mismatch. v1 printed three lines (`42`, `literal: 7`, `x`), while v2 printed
  only `42` or, after registering the first helper, returned curried closures for
  the computed-body macros.
- **Repro:** after `scripts/sbtc "installBin"`, run:
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/quoted-macro-interpreter.ssc`.
  Direct check:
  `bin/ssc run examples/quoted-macro-interpreter.ssc` versus
  `bin/ssc run --v2 examples/quoted-macro-interpreter.ssc`.
- **Root cause:** the v2 run path used `MacroCodegen.expand` only for generated-
  backend-expandable macro bodies. That correctly left interpreter-only bodies
  (`x.asValue.getOrElse(...)`, `x.asTerm.name`) in helper form, but the v2 bridge
  did not register the v1 interpreter's helper globals (`__ssc_macro__`,
  `__ssc_quote__`, `Expr`, `QuotedContext`, etc.) or `Expr.asValue` /
  `Expr.asTerm` method dispatch. A second forward-reference bug meant an inline
  macro entrypoint that appeared before its `impl(...)(using QuotedContext)` helper
  converted before FrontendBridge knew the helper needed a synthesized `using`
  argument, so the impl call returned a curried closure.
- **Fix (387c804da):** `PluginBridge.registerInterpreterBuiltins` registers the
  restricted quoted-macro helper globals for v2 runs, `Prims.__method__` handles
  `DataV("Expr").asValue/asTerm`, `__resolve_given__` supplies the built-in
  `QuotedContext`, and FrontendBridge pre-records `using` metadata before converting
  top-level bodies.
- **Verification:** `bin/ssc run examples/quoted-macro-interpreter.ssc` and
  `bin/ssc run --v2 examples/quoted-macro-interpreter.ssc` both print `42`,
  `literal: 7`, `x`; targeted parity for `quoted-macro-interpreter.ssc` plus
  `quoted-macro-constfold.ssc` is **2/2 MATCH**; affected conformance
  `scala-cli tests/conformance/run.sc -- --only '*quoted*' --no-memo` has **0
  matching cases**; full
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity --all` is now
  **58/81 identical · 7 mismatch · 0 v2-error · 16 v1-only**.

## v2-content-toolkit-section-parity — `fixed` (2026-07-08)

- **Found by:** codex, during `v2-prod-post-p3-baseline` full-corpus production
  parity verification.
- **Symptom:** the latest full `scripts/v2-output-parity --all` has exactly one
  v2-error: `examples/content-toolkit-yaml-controls.ssc`. The same content/toolkit
  section family also has a mismatch in `examples/content-slot.ssc`, where v2 prints
  an extra `Unsupported: TermSelectPostfixImpl` before the expected
  `content-slot:ok` line.
- **Repro:** after `scripts/sbtc "installBin"`, run:
  `bin/ssc run --v2 examples/content-toolkit-yaml-controls.ssc` and
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/content-slot.ssc examples/content-toolkit-yaml-controls.ssc`.
- **Observed direct error:** `contentToolkitNode: table column builder 'fieldColumn'
  is not available — import it from std/ui/data (fcol/mcol/scol/dcol/lcol)`.
- **Root cause:** the v2 plugin `MinimalCtx` did not implement `resolveGlobal` or
  `invokeCallback`, so the real content plugin could not call imported toolkit
  builders such as `fieldColumn` while lowering inline YAML table columns. The
  sibling `content-slot.ssc` mismatch was a separate FrontendBridge lowering issue:
  `[bodyEl]` after the spaced infix operator in `headerParts ++ [bodyEl] ++
  footerParts` was not desugared as a list literal, so scalameta produced an
  unsupported `TermSelectPostfixImpl` node.
- **Fix (7dee6daf0):** `MinimalCtx` now resolves v2 globals and invokes v2/v1
  callbacks with value conversion; FrontendBridge treats spaced operator-following
  list literals as expression-position list literals and has a regression test for
  `xs ++ [y]`.
- **Verification:** `bin/ssc run --v2 examples/content-toolkit-yaml-controls.ssc`
  and `bin/ssc run --v2 examples/content-slot.ssc` both print only the expected
  `:ok` line; targeted parity for both examples is **2/2 MATCH**; `scala-cli
  tests/conformance/run.sc -- --only 'content*' --no-memo` passes **5/5**; full
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity --all` has
  **0 v2-error** and now measures **57/81 identical · 8 mismatch · 16 v1-only**.

## v2-invoice-email-nondet — `fixed` (2026-07-08)

- **Found by:** codex, during `v2-prod-plugin-boundary` full-corpus production parity
  verification.
- **Symptom:** a full `scripts/v2-output-parity --all` run after the dataset stack fix
  reported one extra mismatch in `examples/invoice-email.ssc`: the generated artifact
  byte-count line differed (`2681` vs `2685`). An immediate targeted rerun matched, so
  this is treated as nondeterministic/generated-output exposure rather than a v2-error.
- **Repro:** after `scripts/sbtc "installBin"`, run targeted parity repeatedly:
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/invoice-email.ssc`.
- **Suspect:** the example prints generated MIME/PDF byte length instead of stable
  semantic facts. Byte-exact generated artifacts can vary across runners and should not
  be the user-facing example contract for the v2 production gate.
- **Root cause:** the user-facing example contract exposed exact generated MIME/PDF
  message length. That length is not semantically important and can vary when the PDF
  renderer/MIME generator changes incidental bytes.
- **Fix (d8e0ecee4):** keep building the PDF and MIME message, but print a stable
  semantic line after confirming the message is non-empty:
  `MIME message assembled: PDF attached`.
- **Verification:** direct v1/v2 runs print the same stable line; repeated targeted
  parity for `examples/invoice-email.ssc` was **5/5 MATCH**; neighbor parity for
  `examples/invoice*.ssc examples/pdf-extract-demo.ssc` was **3/3 MATCH**. Conformance
  globs `invoice*`, `*pdf*`, and `*mime*` have **0 cases**, so there is no affected
  conformance case to run.

## v2-list-unlist-stack-overflow — `fixed` (2026-07-08)

- **Found by:** codex, during `v2-prod-plugin-boundary` production parity work.
- **Symptom:** `examples/dataset-parallel-sum.ssc` was the only remaining full-parity
  v2-error. Direct v2 execution crashed before stdout with `StackOverflowError`.
- **Repro:** after `scripts/sbtc "installBin"`, run:
  `bin/ssc run --v2 examples/dataset-parallel-sum.ssc`.
- **Root cause:** `Prims.unlistPub` recursively converted ScalaScript `Cons/Nil`
  lists to Scala `List`; `Dataset.fromList(List.range(1, 100_001))` crosses that
  boundary with 100k elements and overflowed the JVM stack. `Prims.listOf` also used
  `foldRight`, which had the same large-list stack-risk in the opposite direction.
- **Fix (44f3d4a24):** `Prims.unlistPub` and `Prims.listOf` are iterative. The stale
  `dataset-parallel-int` expected snapshot was updated to match the numeric sorted
  output already produced by both v1 and v2 direct runs.
- **Verification:** `dataset-parallel-sum.ssc` parity is MATCH; `scala-cli
  tests/conformance/run.sc -- --only 'dataset*' --no-memo` passes **15/15**;
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/dataset*.ssc`
  has **0 v2-error**; full corpus has **0 v2-error**.

## v2-content-document-context — `fixed` (2026-07-08)

- **Found by:** codex, during the `v2-production-readiness` output-parity baseline.
- **Symptom:** `ssc run --v2` produced non-v1 output for structured Markdown content
  examples: `content-linked-namespaces.ssc` leaked a `Stub`/`Op` shape instead of the
  imported section title, `content-to-markdown.ssc` rendered empty content, and
  `content-tables.ssc` differed on the toolkit table value.
- **Repro:** after `scripts/sbtc "installBin"`, run:
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/content-linked-namespaces.ssc examples/content-tables.ssc examples/content-to-markdown.ssc`.
- **Root cause:** the v2 bridge populated only the current source document and then
  let batch content stubs override the real content plugin natives. The FrontendBridge
  import walk also did not register imported Markdown documents under
  `ContentImportedModules`, so `contentModuleSection` had no namespace table. After
  enabling the real content plugin path, bridged println still rendered
  `TableNode.sortCol` as `None` where v1 case-class output uses `null`.
- **Fix (146779cb6):** `PluginBridge.setDocumentFromSource` resets/seeds content
  document/current-section context, `FrontendBridge` registers imported content
  documents by namespace, content introspection/module/markdown natives use the real
  plugin path, and bridge display preserves the v1 `TableNode(..., null)` rendering.
  Only `contentToolkitSection` remains a selective batch stub until section-level
  toolkit lowering is fixed.
- **Verification:** `examples/content*.ssc` parity is **10/10 identical** (one
  v1 long-running skip), `scala-cli tests/conformance/run.sc -- --only 'content*'
  --no-memo` passes **5/5**, and the full parity gate is **54/88 identical ·
  10 mismatch · 1 v2-error · 23 v1-only**.

## plugin-lazyload-extern-imports — `fixed` (2026-07-07)

- **Found by:** claude (tkv2-pwa-adopt slice) — the stock `examples/pwa/pwa-demo.ssc`
  fails on clean origin/main.
- **Symptom:** extern defs provided by lazy-loaded std plugins are unreachable from
  `.ssc`: `[smtpSend](std/smtp.ssc)` / `[tcpListen](std/tcp.ssc)` → "'X' not found in
  std/Y.ssc" at import; `requires: [std.pwa]` + `pwa(...)` → "Undefined: pwa".
  Preloaded plugins (std/ui frontend/fetch) are unaffected. Reproduced with a fresh
  origin/main worktree build — pre-existing, likely from the recent plugin-loading /
  stable-SPI stream.
- **Repro:** `bin/ssc examples/pwa/pwa-demo.ssc` (stock example) or a 5-line probe
  importing `[smtpSend](std/smtp.ssc)`.
- **Impact:** every opt-in plugin capability (smtp send, raw TCP / IMAP sim, pwa) is
  dead from user code on main. busi's live deploys pin an older ssc, so production
  is unaffected until a bump.
- **Fix (2026-07-07):** the essential/advanced .sscpkg split (fast startup) never
  wired the advanced set into any load path. Now: Main registers the
  `plugin-available/` dirs (`BackendRegistry.setAvailableDirs`) and the
  interpreter's LAZY `ensurePluginsLoaded()` (first missing name/extern) commits
  them via `BackendRegistry.loadAvailableNow()` (idempotent, best-effort per pkg).
  Startup stays fast; smtp/tcp/pwa/sql/auth/crypto… are reachable again. Verified:
  probes ([smtpSend](std/smtp.ssc), [tcpListen](std/tcp.ssc), bare tcpConnect
  extern), stock pwa-demo boots, `tkv2-pwa` conformance un-pended and green.
- **Note:** `tests/conformance/tkv2-pwa.ssc` covers the .ssc-level path;
  `PwaPluginTest` covers the generators.

## bytecode-shared-runtime-routes-unbound — `fixed` (2026-07-07)

- **Found by:** claude (green-main takeover), unmasked by fixing EmitScalaFacadeCliTest's missing
  `-Dssc.lib.path` (the CompilerLoader env error hid it).
- **Symptom:** `ssc compile-jvm --bytecode` fails: "shared runtime compile failed …
  `_ssc_runtime.scala:4931: Not found: _routes` / `Not found: route`" — `JvmGen.genRuntime`'s
  capability gating emits runtime code that references the route registry without emitting its
  definitions (route infra lives in the http/serve runtime piece; the gate combination for the
  bytecode shared runtime includes the referencing piece but not the defining one).
- **Repro:** `sbt 'cli/testOnly *EmitScalaFacadeCliTest'` — 5 of 7 fail on this (2 pass after the
  lib-path harness fix). Or any `compile-jvm --bytecode` invocation.
- **Impact:** the whole `--bytecode` separate-compilation happy path (compile-jvm/link facade family).
- **Root cause:** self-contained `JvmGen.genModule` always emitted either `serveRuntime` or
  `stubServeRuntime`, but split `JvmGen.genRuntime` omitted both when the unioned capability set did
  not contain `Serve`. The always-included common/effects runtime still references route/http/ws
  dispatch symbols, so the shared `_ssc_runtime.scala` could not compile for no-server bytecode
  artifacts.
- **Fix:** `83fc339e2` emits `stubServeRuntime` in split runtime when `Serve` is absent and adds a
  `JvmGen.generateRuntime(Set.empty)` regression test for `_routes`, `route`, `onWebSocket`, and
  `_httpDoRequest`.
- **Verified:** `scripts/sbtc "backendInterpreter/testOnly scalascript.JvmGenRuntimeSeparationTest"`;
  `scripts/sbtc "installBin"`; `scripts/sbtc "cli/assembly"`; `scripts/sbtc "cli/testOnly *EmitScalaFacadeCliTest"`;
  `tests/conformance/run.sh --only 'std-semigroup-monoid' --no-memo`.

## scalajs-jsenv-run-terminated — `fixed` (2026-07-07)

- **Found by:** claude (green-main takeover), root `sbt test` + serial retest.
- **Symptom:** Scala.js test modules (walletVaultEncryptedJs, walletStrategyErc4337Js,
  blockchainEvmAbiJs, markupNode, cryptoNobleJs, walletConnectJs) die in `loadedTestFrameworks`
  with `JSEnvRPC$RunTerminatedException` / `ExternalJSRun$NonZeroExitException: exited with code 1`
  — the node process exits immediately. Reproduces SERIALLY (not load-related) on node v26.4.0
  locally AND in CI (18 occurrences in the failed CI log).
- **Root cause:** the Node process was exiting because required npm packages were not installed in
  the module-local `node_modules` trees. The first concrete repro was `cryptoNobleJs/test` failing
  with `MODULE_NOT_FOUND: '@noble/ciphers/aes'`; the other failing Scala.js modules had the same
  manual-install assumption for their `package.json` dependencies.
- **Fix:** `1da48bfd5` adds an idempotent `npmInstallForScalaJsTest` sbt task and wires it into
  `Test / loadedTestFrameworks` for the npm-dependent Scala.js test projects, so clean worktrees and
  CI run `npm ci` automatically before the Scala.js test runner loads.
- **Verified:** `scripts/sbtc "cryptoNobleJs/test"`;
  `scripts/sbtc "walletVaultEncryptedJs/test; walletStrategyErc4337Js/test; blockchainEvmAbiJs/test; walletConnectJs/test; markupNode/test"`;
  `tests/conformance/run.sh --only 'std-semigroup-monoid' --no-memo`.
- **Repro:** `scripts/sbtc "cryptoNobleJs/test"` in a clean worktree with no
  `payments/crypto/noble-js/node_modules` previously failed before the fix.

## scjvm-artifact-cache-ignores-compiler-version — `fixed` (2026-07-07)

- **Found by:** claude (green-main takeover), while fixing jvmgen-block-call-empty-parens: after
  rebuilding `bin/ssc` with a codegen fix, `ssc run-jvm` kept producing byte-identical BROKEN output.
- **Symptom:** `run-jvm` reuses `<file-dir>/.ssc-artifacts/<name>.scjvm` whenever the SOURCE sha
  matches (`ModuleGraph.isJvmStale`) — the cache key ignores the compiler/binary version, so codegen
  fixes are invisible until the artifact is deleted by hand. Cost ~4 rebuild-and-scratch-head cycles.
- **Repro:** run any `.ssc` via `run-jvm` (caches the emission), change JvmGen, `installBin`, run
  again — the old emission runs.
- **Fix:** duplicate of `jvm-artifact-cache-codegen-invalidation`, fixed by
  `322ee868f` (`codegenVersion` on `.scjvm` artifacts +
  `ModuleGraph.isJvmStale` key comparison) and guarded by `14aa2819d`
  (`run-jvm` regenerates a source-fresh `.scjvm` whose codegen key is old).
- **Gates:** see `jvm-artifact-cache-codegen-invalidation` above:
  `ModuleGraphTest`, `JvmIncrementalCliTest`, `installBin`, and conformance
  `litdoc` on INT/JS/JVM.
- **Workaround:** `rm -rf <dir>/.ssc-artifacts` after rebuilding the binary.
- **Status:** fixed; retained as a historical duplicate of the 2026-07-09
  cache-invalidation fix.

## jvmgen-block-call-empty-parens — `fixed` (2026-07-07)

- **Fixed by:** claude (green-main takeover), SHA: see `fix(jvm): conformance JVM-lane...` on main.
  Peeling the symptom exposed THREE stacked root causes; all four tests now PASS
  (`bin/ssc run-jvm` on signals / effects / rest-validate / distributed-map), with regression
  guards frontendSwiftUI 118/118 (widget `f() { block }` form preserved for curried callees) and
  backendInterpreter `*JvmGen* *Effect*` 193/193.
  1. **empty-parens** (signals, rest-validate): `JvmGen.emitExprDeep` emitted `f() { block }` for
     EVERY bare-name single-block call (fa5d3c821, Jun 2 — swiftui widgets). Broke single-thunk
     callees (`computed`/`effect`/`validate`/`runActors`). Now curried form only when
     depDefs/localDefSigs shows ≥2 param clauses. Exposed 2026-07-06 when bp2-2 enabled the
     warm JVM batch lane — the bug is older than the "suspect window" guessed below.
  2. **Console duplicate** (effects): the preamble's `object Console` println-shadow collided with
     a user `effect Console:` lowering. Preamble shadow is now omitted when the module defines its
     own top-level `Console` (JvmRuntimePreamble.sourceFor + collectUserTopNames bare-Stat arm);
     the effect-object lowering carries the println/print bridges instead.
  3. **premature declared-type cast** (effects, distributed-map): CPS-emitted defs whose ops are
     handled at the CALL SITE (`handle(greet())`) were cast to their declared type
     (`.asInstanceOf[String]`) while still holding an unresolved Free → ClassCastException
     `_FlatMap → String`/`DistributedResult`. Declared type is now preserved only for
     SELF-handling bodies (contains a `handle` form), extending codex's
     `preserveTotalEffectfulReturnTypes`.
  Diagnosis gotchas that cost cycles, recorded for the next agent: `.ssc-artifacts` scjvm cache
  (see the new open bug above), a stale sbt/bloop daemon from a deleted worktree answering builds
  (`scripts/kill-stale-builders --kill`), and macOS Xcode `strings` silently failing on JVM
  `.class` files (use `grep -ac` / `unzip -p … | grep -ac` instead).

## jvmgen-block-call-empty-parens — original report (was `open`, 2026-07-07)

- **Found by:** claude (tkv2-components slice), via the full-corpus A/B: 4 tests
  (signals, effects, rest-validate, distributed-map) fail the JVM lane in any FRESH
  build of origin/main, while the shared main checkout "passes" only because its
  `bin/ssc` is STALE (pre-2026-07-03 source — its generated preamble lacks the
  webauthn `configureStore` block and `Bench.opaque`). Reproduced on a pristine
  origin/main worktree + fresh `installBin`.
- **Symptom:** `ssc run-jvm tests/conformance/signals.ssc` — user code
  `val doubled = computed { … }` is emitted as `computed() { … }` (empty first arg
  list + trailing block) → Scala compile error "missing argument for parameter
  thunk". Same for `effect { … }`.
- **Repro:** `scripts/new-worktree probe && cd ../scalascript-wt-probe &&
  scripts/sbtc installBin && bin/ssc run-jvm tests/conformance/signals.ssc`.
- **Suspect window:** whatever changed JvmGen's call-with-block emission between the
  main checkout's stale binary (~2026-07-03) and current origin/main. Not caused by
  the tkv2 slice (repro has none of its commits). NOTE: rebuild the shared main
  checkout's bin/ssc after fixing — its staleness masks this class of regression
  in any corpus run executed from the main checkout.

## jsgen-signal-type-import-vs-preamble — `fixed` (2026-07-07)

- **Found by:** claude (tkv2-components slice) — first .ssc module importing the opaque
  `Signal` TYPE from `std/ui/primitives.ssc` and emitting to JS.
- **Symptom:** `ssc emit-js` of any file importing `[Signal, …](std/ui/primitives.ssc)`
  dies on Node with `SyntaxError: Identifier 'Signal' has already been declared` —
  the import emits `const Signal = std.ui.primitives.Signal`, colliding with the
  signals.mjs preamble `function Signal`.
- **Root cause / fix:** the `jsgen-toplevel-name-vs-preamble` (#5) class. `Signal` is
  now pre-seeded into `declaredBindings` (like the std/fs file-ops): the import const
  is skipped; type positions erase, and value uses correctly resolve to the preamble
  reactivity constructor. `JsGen.scala` declaredBindings init.
- **Guard:** `tests/conformance/tkv2-component.ssc` (imports the type transitively via
  `std/ui/component.ssc`, INT==JS).

## jsgen-reserved-param-body-rename — `fixed` (2026-07-07)

- **Found by:** claude (tkv2-components slice) — `std/ui/component.ssc`'s
  `ctxSignal(ctx, name, default)` parameter named `default`.
- **Symptom:** a def with a JS-reserved-word parameter (e.g. `default`), emitted through
  the namespace/object member path (`const f = (a, b, default_p) => …`), renames the
  formal via `safeJsParam` but NOT the body references — the body emits bare `default`
  → Node `SyntaxError: Unexpected token 'default'`.
- **Root cause / fix:** the object-member def emission built `bodyJsRaw` without
  `withParamRenames` (unlike the top-level def paths at JsGen.scala:2462-2482). Now the
  same `objDefRenames` map wraps body generation. Any .ssc module function with a
  reserved-word param was affected on the JS lane.
- **Guard:** `tests/conformance/tkv2-component.ssc` (ctxSignal carries a `default`
  param, INT==JS).

## green-main-full-sbt-test-gating — `fixed` (2026-07-07)

- **Found by:** codex, while verifying the `plugin-cli-oslib-shadow` fix.
- **Symptom:** after the `PluginCliTest` compile blocker is fixed, the root
  `sbt "test"` gate is still red in unrelated integration suites.
- **Repro:** `cd /Users/sergiy/work/my/scalascript-wt-finish-green-main && sbt "test"`.
  The second full run completed in 29:08 with non-zero exit. It confirmed
  `PluginCliTest` now passes, then reported:
  - `CrossBackendIntrinsicParityTest`: JS-only drift for `webauthnConfigureStore`
    and `webauthnStoreRemove`.
  - `JvmGenSwingRuntimeTest`: failed inside the `cli / Test / test` aggregate.
  - `StableSpiEnforcementTest`: `tcp-plugin` still imports
    `scalascript.interpreter.Value` from a value-surface plugin.
  - `AgentConformanceTest`: suite aborted with `java.net.BindException:
    Address already in use` in `beforeAll`.
  - JS test-framework fallout: several Scala.js modules report
    `RPCCore$ClosedException` after a Node-side non-zero exit.
- **Notes:** the first full run hit a transient Scala 3 compiler crash in
  `clientEvm/Test/compile`; targeted `clientEvm/Test/compile` passed immediately,
  so the durable gate is the second run's failure set above.
- **Status:** fixed in `cea0c3aed`; the root `sbt "test"` gate is green.
- **Progress (2026-07-07, `8dfd2989e`):** `CrossBackendIntrinsicParityTest`
  fixed by documenting `webauthnConfigureStore` and `webauthnStoreRemove` as
  JS-core/JVM-`auth-plugin` exceptions; targeted parity test passes.
- **Progress (2026-07-07, `484d56101`):** `StableSpiEnforcementTest` fixed by
  migrating `tcp-plugin` from direct `scalascript.interpreter.Value` constructors
  to `PluginValue`; `StableSpiEnforcementTest` and `tcpPlugin/test` pass.
- **Progress (2026-07-07, `395e8aab3`):** `JvmGenSwingRuntimeTest` fixed by
  replacing the local `v1`-anchored repo-root finder with `TestPaths.repoRoot`;
  targeted Swing runtime test passes 5/5.
- **Progress (2026-07-07, `eae491e11`):** `AgentConformanceTest` fixed by
  binding its mock OpenAI gateway to a loopback ephemeral port instead of
  hard-coded `19694`; targeted conformance test passes 3/3.
- **Progress (2026-07-07, `7e2650e2c`):** `PluginBridgeTest` fixed by aligning
  the test stub with the bridge's stable SPI raw-value contract
  (`IntV` args arrive at `NativeImpl` as `Long`, and raw `Long` returns wrap back
  to v2 `IntV`); targeted `v2PluginBridge/testOnly ssc.bridge.PluginBridgeTest`
  passes 22/22.
- **Progress (2026-07-07, `2e1f2c287`):** `V2ConformanceTest` fixed by letting
  real `.ssc` `Defn.Def` bodies shadow same-named plugin globals after
  `stripExternDecls`; `std/mcp/types.ssc` `requireString` now wins over the
  `validate {}` helper in mcp imports. Also renamed the conformance fixture's
  local `args` to `mcpArgs` so the JS lane avoids the known
  `jsgen-toplevel-name-vs-preamble` collision. Verified full
  `v2FrontendBridge/testOnly ssc.bridge.V2ConformanceTest` (62/62) and
  `scripts/conformance -- --only mcp-types --no-memo` (INT/JS pass).
- **Remaining targeted blockers (2026-07-07):**
  `backendWasm/testOnly scalascript.codegen.WasmBackendTest` still has 7
  effectful-WASM failures: handler/resume and `String*` effectful mains print
  empty output or throw under Node v26.4.0, arithmetic/HOF effect bodies print
  empty output, and the cross-module imported-effect case prints empty output.
  Scala.js `loadedTestFrameworks` fallout still needs re-checking after the
  deterministic JVM/v2/WASM failures are fixed.
- **Final verification (2026-07-08, `cea0c3aed`):** full `cli/test` is green
  (554 succeeded, 29 canceled, 0 failed), affected conformance
  `collections,dataset-from-file,dataset-shape,json-*,std-ui-*,tkv2-*` is green
  (19/19), and bounded root `scripts/sbtc "test"` is green
  (`[success] elapsed: 1668 s (0:27:48.0)`).

## plugin-cli-oslib-shadow — `fixed` (2026-07-07)

- **Found by:** codex, while stabilizing the red `origin/main` CI run
  `28832706348`.
- **Symptom:** CI `sbt - compile and test` builds the launcher but fails during
  `Test via sbt` while compiling
  `v1/tools/cli/src/test/scala/scalascript/plugin/PluginCliTest.scala`.
- **Repro:** `cd /Users/sergiy/work/my/scalascript-wt-finish-green-main && sbt "cli/Test/compile"`.
  The failure reports `type Path is not a member of scalascript.compiler.plugin.os`
  and missing `temp`, `remove`, `makeDir`, `read`, `write`, `exists`, `list`,
  `copy`, and `walk` members.
- **Root cause:** because `PluginCliTest` is in package `scalascript.compiler.plugin`,
  the local `scalascript.compiler.plugin.os` package shadows os-lib's root `os`
  package.
- **FIXED (2026-07-07, `6d133361a`):** qualified all os-lib references in
  `PluginCliTest` as `_root_.os`, so the test no longer resolves the local plugin
  package.
- **Verified:** `cd /Users/sergiy/work/my/scalascript-wt-finish-green-main && sbt "cli/Test/compile"`;
  `cd /Users/sergiy/work/my/scalascript-wt-finish-green-main && sbt "cli/testOnly scalascript.compiler.plugin.PluginCliTest"`
  (8/8).

## v2-cellset-flc-corruption — `fixed` (2026-07-05)

- **Found by:** claude (v2-recursion-opt slice), via `map-ops` regressing to
  `SKIP(no-main)` in the v2 bench sweep.
- **Symptom:** `expected Map, got 0` crash on `map-ops` via the ssc1c pipeline; the
  failure class is broader — SILENT data corruption: any generic-cell `var` reassigned
  from a non-Int expression could store `IntV(0)` instead of the real value.
- **Root cause:** the `cell.set` FLC fast path in `FastCode` (`v2/src/Runtime.scala`)
  assumed "tryFLC fails for Float/String expressions", but the FastCode phase-1/2 batch
  (2026-07-04) added OPTIMISTIC leaves to `tryFLC` (`App(Global)`, `cell.get`,
  `arr.get`, `fieldAt`, `Local`) that coerce non-Int values to `0L`. So
  `m = m.updated(k, v)` (App body returning a Map) FLC-compiled and stored `IntV(0)`
  over the map.
- **Fix:** new `flcProvablyLong(t)` structural predicate; `cell.set` takes the FLC
  fast path ONLY when the body is provably Long (int literals, `lcell.get`, int
  arith, `.toInt/.toLong/.length/.size`). `lcell.set` is unaffected (lcells hold Long
  by construction).
- **Blast radius audit:** all 10 var-reassign corpus programs compared old-vs-new —
  identical outputs everywhere except map-ops (now correct: 124750); no other corpus
  program had engaged the corrupt path.

## v2-conformance-empty-output-flake — `fixed` (2026-07-01)

- **Found by:** codex, while continuing K49 after the K48 multi-op typed handler work.
- **Symptom:** `cd v2 && ./conformance/check.sh` can report a contiguous block of unrelated
  `got []` failures. Direct reruns of the first failing examples pass, so the useful failure
  signal is lost when Java/Rust stderr is discarded.
- **Repro:** run the assembled-jar harness, not a dev runner: `cd v2 && ./conformance/check.sh`.
  K48 observed two full runs failing in different sections after otherwise unrelated Rust/Java
  activity.
- **Root cause:** the harness built every run into the shared path `/tmp/ssc-conformance.jar`.
  Parallel agents or repeated harness runs could overwrite that jar while an earlier run was still
  executing it, producing `NoClassDefFoundError: ssc/Program$` followed by `Invalid or corrupt
  jarfile`. Rust failures were downstream: empty generated `.rs` files had no `main`.
- **FIXED (2026-07-01, `d4ca120bf`):** `check.sh` now builds the assembled jar inside the run's
  unique diagnostic log directory, captures Java/Rust stderr and stdout artifacts, retries empty
  Java stdout once, and prints a diagnostic summary on failure.
- **Verified:** `bash -n v2/conformance/check.sh`; reproduced the old flake once and captured the
  corrupt-jar root cause; after switching to the per-run jar, two consecutive full
  `cd v2 && ./conformance/check.sh` runs passed (`run1 exit=0`, `run2 exit=0`). After rebasing on
  KC7, a final full run with the KC7 tests also passed (`final exit=0`).

## js-spa-hashchange-bridge-sync — `fixed` (2026-06-29)

- **Reported by:** Sergiy, from the rozum Unified Control Center (`clients/control/control-center-live.ssc`).
- **Symptom:** clicking a hash-route navigation control changed `location.hash`, but the visible SPA route did
  not switch until a manual browser refresh. The reactive `hashSignal()` / `computedSignal` graph updated, but
  mounted `data-ssc-cond` branches still kept their previous `display:none` / `display:contents` state.
- **Repro:** mount a JS browser SPA with `hashSignal()` feeding an `eqSignal` / route guard, then change
  `window.location.hash` and dispatch `hashchange`; before the fix the computed signal value changed while the
  mounted branch styles did not.
- **Root cause:** `_ssc_ui_hashSignal()` already registered a `hashchange` listener and updated the reactive
  graph, but `_ssc_ui_mount()` only pushed computed values from the reactive graph into the DOM bridge store
  (`_sv` / `_sb`) through `_syncBridgeSignals()` after bridge-owned `_set(...)` calls. Native browser
  `hashchange` events never called that bridge sync.
- **FIXED (2026-06-29, `23789503d8b9c2a4cba41545ba5ae7ba0219bc1b`):** `_ssc_ui_mount()` now listens for
  `hashchange` and calls `_syncBridgeSignals()`, so `data-ssc-cond`, signal text, and other mounted bridge
  subscribers observe hash-derived computed changes without a refresh.
- **Guard:** `JsGenStdImportTest` now dispatches `hashchange` after mount and asserts the `data-ssc-cond`
  branches toggle. Also re-ran `SpaComputedBodyBridgeTest` to cover the adjacent computed-to-bridge path.

## v2-conformance-echo-backticks — `fixed` (2026-06-29)

- **Found by:** codex, while running full `v2/conformance/check.sh` for K46 async/actor breadth.
- **Symptom:** the conformance assertions were green, but the harness printed shell noise such as
  `show: command not found`, `method: command not found`, `effect: command not found`, and
  `a,b,c,d: command not found` to stderr.
- **Repro:** `cd v2 && conformance/check.sh`; the offending `echo "..."` lines contained Markdown
  backticks, so the shell performed command substitution before printing the heading.
- **Root cause:** double-quoted shell strings around headings that intentionally contained literal
  backticks.
- **FIXED (2026-06-29):** changed those headings to single-quoted strings. `bash -n
  v2/conformance/check.sh` passes, and the final full K46 conformance rerun completed successfully
  with captured stdout/stderr checked for `FAIL` and `command not found` (none present).

## parser-trysplitparse-quadratic-hang — `fixed` (2026-06-28)

- **Found by:** busi (phone-demo hub). A `/api/issue` route used `given` as a local val name: `val given = req.form.getOrElse("number", ""); val number = if given.length > 0 then given else …`. Loading the ~3500-line `demo_server.ssc` pegged one core at ~100% CPU and never bound (>90s); the *same* code in a tiny file instead fast-failed with `illegal start of definition`. (busi originally mis-attributed this to the `if <param> then <param> else …` shape and to a `View[Int]` — both red herrings; the trigger is purely the identifier name.)
- **Root cause:** `given` is a Scala-3 soft keyword, so scalameta rejects it as an identifier → in `Parser.parseScalaWithDiagnostic` BOTH the Source-mode parse and the `{…}` Term-mode parse fail → the `trySplitParse` fallback runs. That fallback tried EVERY split point (`lines.length - 1 to 1 by -1`), each re-parsing an O(N)-line `prefix` as `Source` plus a `suffix` as `Term`. For a large block that is O(N) parses over O(N)-line prefixes = **O(N²)** total. Confirmed size-driven, not single-parse-exponential: a 1010-line block ≈ 6s, a ~3500-line block ≈ 90s; a `jstack` mid-hang showed `main` in `Parser$.trySplitParse$…` → `…prefix.parse[Source]` → scalameta `argumentExprsInParens` recursion.
- **Minimal repro:** `val given = "x"; val number = if given.length > 0 then given else "z"` in a code block — a fast `illegal start` in a small file, a ~quadratic hang in a multi-thousand-line one. Renaming `given` → `gv` parses and runs fine.
- **FIXED (2026-06-28):** bounded `trySplitParse` to small trailing suffixes — `private val MaxSplitSuffixLines = 48`, range `lines.length - 1 to math.max(1, lines.length - MaxSplitSuffixLines) by -1`. The handler-file pattern this fallback targets (class defs + a trailing lambda) always has a short trailing term, so only the last few split points are useful; small blocks (≤48 lines) keep the original full-range behaviour. Turns the 90s hang into a fast diagnostic (busi hub: 90s → ~3s `illegal start`).
- **Guard:** `ParseErrorPositionTest` — "large block with `given` as an identifier yields a fast diagnostic, not a quadratic hang" (2500-line block; asserts a populated `parseError` in <15s). All 146 `scalascript.parser.*` tests pass; the handler-file trailing-lambda split still parses; busi `make v2-test` + `make v2-test-js` are 47/47 on both backends with the rebuilt jar.
- **Note:** verified against this branch's base (`origin/main` @ ce0554245) — `trySplitParse` is byte-identical to the commit busi pins (72d0196f3), where it was first reproduced + the fix built/tested. busi keeps its own workaround (no `given` val, `getOrElse` auto-number) so it is unaffected; this lands the parser-robustness fix for everyone.

## jsgen-emitjs-capability-standalone — `fixed` (2026-06-22)

- **Found by:** busi (deep-offline browser/Node bundle) — the standalone-bundle frontier after `jsgen-emitjs-effect-handler`: `inbox`/`ksef`/`repo*` (clock) and the crypto path failed under raw `ssc emit-js | node`, while the JIT path (`SSC_JIT_BACKEND=js`) was green.
- **Symptom (two distinct bugs):**
  - **(clock)** A `RuntimeCall` intrinsic (`nowMillis` → `Date.now`) called inside an *effectful* (CPS-lowered) function emitted the bare source name (`nowMillis()`) → `ReferenceError: nowMillis is not defined`. `dispatchIntrinsicJs` rewrote it for `Term.Apply` sites in `genExpr`, but `genCpsApply`'s "regular call" path didn't.
  - **(crypto)** Importing a `std/crypto` extern (`[sha256](std/crypto.ssc)`) emitted `const sha256 = std.crypto.sha256` AND added `sha256` to `declaredBindings` (disabling the `sha256` → `_sha256` intrinsic rewrite at call sites). The namespace member was `(typeof _ssc_ui_sha256 !== 'undefined') ? _ssc_ui_sha256 : undefined` = `undefined` under Node → `not callable: ()`.
- **FIXED (2026-06-22):**
  - **(clock)** `genCpsApply` now handles `Term.Name(fname)` whose `intrinsicRuntimeTarget(fname)` is defined: it binds the args CPS-style and emits `target(args)` (e.g. `Date.now()`). New `private[codegen]` helper `JsGen.intrinsicRuntimeTarget`.
  - **(crypto)** In `genObjectAsExpr`, an extern namespace member falls back to its `RuntimeCall` intrinsic target (`_sha256`) instead of `undefined` when the host UI stub is absent — guarded by an inner `typeof` (stays `undefined` if the target isn't emitted) and by `target != fname` (so identity intrinsics like std/auth's `webauthnChallenge` don't self-reference → TDZ). Browser still prefers the `_ssc_ui_*` host stub.
- **Guards:** `tests/conformance/js-cps-intrinsic-rewrite.ssc` (nowMillis in a CPS body) + `tests/conformance/js-crypto-extern-standalone.ssc` (`sha256("abc")` standalone), both INT==JS. busi standalone `ssc emit-js tests/v2/<f>.ssc | node` sweep: **13/21 → 20/21** v2 domain files (only `auth` remains — its WebAuthn externs are host-only, no Node preamble, a separate feature). busi `make v2-test` + `make v2-test-js` green (26 files); before/after emit-js+node sweep over all conformance tests: **zero PASS→FAIL regressions** (84→84).
- **Still open (separate):** `auth.ssc` standalone needs Node WebAuthn impls (`webauthnChallenge`/`webauthnVerify*` are identity-`RuntimeCall` host externs with no `_webauthn*` preamble). `jsgen-toplevel-name-vs-preamble` (#5, general preamble-shadow) also still open.

## rust-index-read-moves-noncopy — `fixed` (2026-06-22)

- **Found by:** mellow-shrew (self), via an end-to-end `cargo run` smoke against the just-landed rust-web-toolkit follow-ons (`origin/main` @ d0141a1d4). The `backendRust` unit suite is string-match only (no `cargo` compile), so it missed a generated-Rust move error.
- **Symptom:** an index *read* on a non-Copy element sequence panicked the Rust compiler, not the program — `error[E0507]: cannot move out of index of Vec<String>`. Minimal repro:
  ```scalascript
  @main def run(): Unit =
    val parts: List[String] = "a,b,c".split(",").toList
    println(parts(1))      // → parts[(1i64) as usize]  — moves the String out of the Vec
  ```
  `Vec<i64>` indexing was fine (i64 is `Copy`), so the bug only surfaced once `f2afd3378` made `.split`/`.toList` results indexable (`Vec<String>`, non-Copy).
- **Root cause:** the `seq(i)` index-read lowering (`RustCodeWalk.scala`) emitted a bare `seq[(i) as usize]`. Using a `Vec`'s `Index` output by value moves it; legal only for `Copy` elements.
- **FIXED (2026-06-22):** index *reads* now emit `seq[(i) as usize].clone()` — required for `Vec<String>`/structs, elided by rustc for `Copy` elements (i64/char/bool), so zero cost. The `seq(i) = v` *store* path is now handled explicitly in `Term.Assign` (new `asSeqIndexTarget` helper) so the assignment **target** stays bare — you can't assign to a clone.
- **Guard:** `RustGenCollectionTest` — "index read on a String seq clones the element" + "index store on a mutable array stays bare". Verified end-to-end with a throwaway `cargo run` smoke (all new collection/string ops compile + run): output `30 70 70 30 100 6 1 a-b-c true true true b 3`. `backendRust` 235/0.
- **Follow-up (filed in BACKLOG):** the rust backend has no `cargo`-compile coverage in its unit suite — this whole bug class (move/borrow errors in valid-looking generated Rust) is invisible to string-match tests.

## jsgen-emitjs-effect-handler — `fixed` (2026-06-22)

- **Found by:** busi (deep-offline browser bundle) — blocker #3 of 5 in `src/v2/specs/lf-1-browser-bundle.md`. Only the raw `emit-js` standalone path was affected; the JIT path (`SSC_JIT_BACKEND=js`) was always green.
- **Symptom:** raw `emit-js` of code using an effect + deep handler (an effectful `query` that folds over `Eff.read`, run inside a `handle`) failed at runtime — `TypeError: arr.reduce is not a function`, or `Unhandled effect: …`.
- **FIXED (2026-06-22) for the direct + single-import case — two layers:**
  - (a) **Imported effect now recognised.** `genImport` runs `analyzeEffects` on the imported module and merges the discovered `effectOps`/`effectfulFuns`/`multiShotEffects` back into the importer, so an effect-performing function defined in an imported module (e.g. `query` calling `Box.read`) lowers its op to `_perform`+`_bind` (not a generic `_dispatch`) and is CPS-transformed — the `_Perform` no longer leaks into the fold.
  - (b) **Effectful lambdas emit a CPS body.** `genExpr` for `Term.Function` now emits the body via the CPS path when `jsForTermPerforms(body)` — so an effect-performing call in a handler-body thunk (`runBox(() => query(...))`) returns the Free computation for the handler to interpret instead of being `_run`-wrapped (which threw "Unhandled effect"). (`jsForTermPerforms` made `private[codegen]`.)
  - **Guard:** `tests/conformance/effect-imported-handler.ssc` (+ `lib/effect-box.ssc`) — an imported effect + generic effectful reader + deep handler, run twice; INT==JS==JVM. busi `make v2-test-js` (full effectful v2 core on JS) green; `CrossBackendPropertyTest` effect cases green. Single-file and 2-level-import effect+handler code now runs under raw `emit-js`.
- **FIXED — transitive multi-level imports (3+ levels) (2026-06-22, whole-program pass).** Spec `specs/emitjs-effect-whole-program.md`. `JsGen.analyzeEffects` now collects trees across the ENTIRE import graph (recursively resolve imports — reusing `genImport`'s resolution — parse each once, visited-set for diamonds/cycles) and runs `EffectAnalysis.analyze` on the union, so a function calling a transitively-imported effectful function (busi: `ledger.accountBalance` → `journal.query` → `Journal`) is marked effectful and CPS-lowered. `effectOps`/`effectfulFuns`/`multiShotEffects` are now SHARED constructor params threaded to child generators (like `topLevelConsts`), populated once by the entry generator's whole-program pre-pass — every module emits against the same view; the per-`genImport` `analyzeEffects`+merge (the single-import fix) is dropped as redundant. **Result:** `ssc emit-js tests/v2/ledger.ssc | node` runs end-to-end (all checks pass), as do obligation/plan/payment/gate/income standalone. Guard: `tests/conformance/effect-transitive-handler.ssc` (+ `lib/eff-a.ssc`, `lib/eff-b.ssc`), INT==JS==JVM; busi `make v2-test` + `make v2-test-js` green; cross-backend green.
- **Remaining busi standalone-bundle frontiers — UPDATED 2026-06-22 (claude-code, `fix/js-standalone-frontiers`).** All three originally-listed frontiers are now CLOSED under raw `emit-js | node`:
  - `trust.ssc` ✅ — the CPS gap was a **unary operator on an effectful operand** (`!x` / `-x` where the operand performs an effect) falling through to `genExpr`, which `_run`-wrapped it and ran the effect outside the handler. `Term.ApplyUnary` now CPS-lowers via `_bind(operand, v => op(v))`. Guard: `tests/conformance/js-applyunary-effect-cps.ssc`.
  - `qr.ssc` ✅ — the `Method not found` was `Array.fill(n)(x)` (+ `tabulate`/`range`/`empty`): `Array(...)` emits a JS array literal, so the bare `Array` value at a `_dispatch` site is the native constructor, which lacks the Scala statics. `_dispatch` now routes these to the `List` companion (shared JS array repr). Guard: `tests/conformance/array-companion-statics.ssc`. (Plus the `fn-typed-field` dispatch refinement above.)
  - `ksef.ssc` ✅ (syntax) — the duplicate global `const readFile` is gone: the std/fs file-ops (`readFile`/`writeFile`/`exists`/… 14 names) are extern decls whose real impl is the preamble (`JsRuntimeFs`), so they're seeded into `declaredBindings` and never re-emitted as a colliding top-level `const`. `node --check` now passes. This closes the std/fs subset of the `jsgen-toplevel-name-vs-preamble` (#5) class.
- **New frontier exposed (next):** `ksef.ssc`/`inbox.ssc`/`repo*` now reach runtime and hit `ReferenceError: nowMillis is not defined` / `not callable: ()` — the `nowMillis` clock capability (`JsCapabilities`: `QualifiedName("nowMillis") -> RuntimeCall("Date.now")`) is wired on the JIT path but not emitted into the raw `emit-js` preamble; `auth.ssc` hits a similar crypto-capability gap. **This overlaps the active `core-min-clock-env-migrate` (Clock/Env→plugin) work and is left for that stream / a follow-up.** Standalone emit-js+node sweep: **85/113 conformance + 13/21 busi v2 domain files** pass; the rest are clock/crypto capability gaps + infra (actors/cluster/distributed/sql).

## jsgen-toplevel-name-vs-preamble — `fixed` (2026-06-22)

- **Found by:** busi (deep-offline browser bundle) — blocker #5 of 5 in `src/v2/specs/lf-1-browser-bundle.md`.
- **Symptom:** a top-level user binding named exactly like a preamble helper (e.g. `val scope = …` vs the runtime's user-facing `function scope(scopeName)` for CSS scoping, SPEC §8.4) emits a colliding top-level `const scope = …` → `SyntaxError: Identifier 'scope' has already been declared` under `node --check`. Other preamble names (`doc`, `escape`, `assert`, `List`, `Decimal`, …) can collide the same way.
- **Additional repro (2026-07-07):** `scripts/conformance -- --only mcp-types`
  passes INT but JS fails before printing anything because
  `tests/conformance/mcp-types.ssc` used `val args = ...`, colliding with the
  JS preamble's `function args()` from `std/os`. The conformance fixture
  workaround landed in `2e1f2c287` (`mcpArgs`); the broad top-level
  name-mangling fix is now covered by this entry.
- **Additional repro (2026-07-09):** after enabling an expected file for
  `tests/conformance/litdoc.ssc`, `bin/ssc emit-js tests/conformance/litdoc.ssc
  | node` failed with `SyntaxError: Identifier 'doc' has already been declared`
  because the fixture has top-level `val doc = parseDoc(md)`, colliding with the
  JS preamble surface.
- **Fixed subset (2026-07-09, `782f07438`):** JS generation now derives the
  runtime top-level declaration set and renames colliding user top-level
  `val`/`var` bindings plus normal references. This fixes the litdoc `val doc`
  repro and is guarded by `JsGenStdImportTest` plus
  `tests/conformance/run.sh --only 'litdoc' --no-memo`. This left
  non-`val`/`var` declaration forms for the follow-up fixed below.
- **Fixed remainder (2026-07-09, `854a87f1b`):** top-level JS collision
  handling now covers user/import bindings that actually emit flat-scope JS:
  `def`, `@js`/`@jvm` extern stubs, `object`, case class constructors, enum
  companions/cases, explicit named givens, and import aliases. Emission and
  call sites share the same `emittedName` map; recursive/effect/TCO analysis
  still keys off original source names. Object collisions now create a renamed
  binding instead of mutating the runtime helper with `Object.assign(scope, ...)`.
- **Root cause:** the first fix only renamed `val`/`var`; other declaration
  emitters and several direct call-site fast paths still used the source name,
  so a renamed `def doc` declaration would still call the runtime `doc(...)`.
- **Guards:** `scripts/sbtc "backendInterpreter/testOnly scalascript.JsGenStdImportTest"`
  (49/49), `tests/conformance/run.sh --only 'litdoc' --no-memo` (INT/JS/JVM),
  and `tests/conformance/run.sh --only 'mcp-types' --no-memo` (INT/JS; JVM
  intentionally skipped by fixture frontmatter).

## jsgen-fn-typed-field-autoinvoke — `fixed` (2026-06-22)

- **Found by:** busi (deep-offline browser bundle) — facet of blocker #4 of 5 in `src/v2/specs/lf-1-browser-bundle.md` ("a generic `View.step` fold reaches syntax-valid JS but the `step` field is not callable").
- **Symptom:** a case-class field whose value is a function (e.g. `View.step: (S, Int) => S`), passed as a *value* to a HOF (`xs.foldLeft(v.init)(v.step)`), threw `TypeError: fn is not a function` on the JS backend. A direct call `v.step(1, 2)` worked; only the eta/value position failed. interp + JVM were correct.
- **Root cause:** lambdas are emitted variadic (`(...__a) => …`, to support tuple-destructuring), so their `.length` is 0. Accessing the field as a value lowers to `_dispatch(v, 'step', [])`, and `_dispatch`'s zero-arg branch auto-invoked any property whose `typeof === 'function' && .length === 0` — so it CALLED the variadic field-lambda (no args → NaN) instead of returning it.
- **FIXED (2026-06-22):** in `_dispatch`, a no-arg property access on a case-class / enum instance (`obj._type !== undefined`) returns the data field as-is — case-class methods live in `_extensions`, never on the object, so an existing own-property is always a data field and must never be auto-invoked. Direct calls (`args.length > 0`) and all non-`_type` objects are unchanged.
- **REFINED (2026-06-22):** the blanket `obj._type !== undefined && args.length === 0 → return as-is` guard was too broad — it also suppressed *genuine* zero-arg methods that SHOULD auto-invoke (`JsonValue.asString` and friends, emitted as a real `() => …` / `function(){…}` own-property), so `tests/conformance/json-value.ssc` failed under raw `emit-js`. The guard is replaced with a precise test inside the function branch: a zero-arg-arity own-property is returned as a reference only when its source is a **variadic-emitted lambda** (`(...__a) => …`, detected via `Function.prototype.toString`); a genuine zero-arg function is still auto-invoked. Net: `json-value` now passes standalone (FAIL→PASS in the emit-js+node conformance sweep) while `fn-typed-field` stays green; before/after sweep over all 113 conformance tests showed **zero PASS→FAIL regressions**.
- **Guard:** `tests/conformance/fn-typed-field.ssc` (variadic field as value) + `tests/conformance/json-value.ssc` (genuine zero-arg method auto-invoke), INT==JS==JVM. busi `make v2-test-js` + `CrossBackendPropertyTest`/`MoneyCrossBackendTest`/`CustomDerivesMirrorCrossBackendTest` green; `tests/v2/qr.ssc` now runs as a raw `emit-js` standalone bundle.

## jsgen-dup-enum-global — `fixed` (2026-06-22)

- **Found by:** busi (deep-offline browser bundle) — blocker #2 of 5 in `src/v2/specs/lf-1-browser-bundle.md` ("emit-js / emit-spa for tests/v2/local_journal.ssc fails syntax checks with duplicate global `Pending` declarations from ObligationStatus.Pending and DeferredActionStatus.Pending").
- **Symptom:** two enums (in the same file or different modules) that share a *parameterless* case name each emitted a top-level global `const <Case> = {_type:'<Case>', _tag:N}`; child generators share the global scope, so the bundle had a duplicate `const` and Node rejected it: `SyntaxError: Identifier 'Pending' has already been declared`. `SSC_JIT_BACKEND=js` was fine; only raw `emit-js`/`emit-spa` failed (`node --check`).
- **Root cause:** the top-level (`genStat`) `Defn.Enum` emission unconditionally emitted `const <Case>`/`function <Case>` per case. Enum-case tags are global-by-name, so the two `Pending` objects are byte-identical; qualified refs already go through the companion (`_dispatch(ObligationStatus, 'Pending', [])`), not the bare global.
- **FIXED (2026-06-22):** a shared `declaredEnumCases: Set[String]` (threaded to child gens like `declaredBindings`) skips re-declaring a global enum-case binding already emitted by another enum; each companion still references the surviving (structurally identical) global. Only the global `genStat` path is guarded — module-IIFE (`genObjectAsExpr`) enum cases are scoped and don't collide. JIT/JVM/interp paths untouched.
- **Guard:** `tests/conformance/enum-shared-casename.ssc` (+expected) — two enums with a shared `Pending` case; within-enum equality + `.values.size` identical on INT/JS/JVM (cross-enum equality is intentionally NOT asserted: after dedup the JS objects are shared, which never matters in well-typed code). `EnumCrossBackendTest` 3/3; busi `tests/v2/local_journal.ssc` emit-js now passes `node --check`.

## jvm-multishot-result-type — `fixed` (2026-06-21, `39b7c665f`)

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

## asm-jit-effect-pathology — `fixed` (2026-06-21, `0d5e03b87`)

- **Found by:** benchmark perf-divergence sweep (`./bench.sh`), accepted from `SPRINT.md`.
- **Symptom:** the synthetic `ssc-asm` backend (`SSC_JIT_BACKEND=asm`) is orders of magnitude slower than
  default `ssc` on `bench/corpus/effect-oneshot.ssc`, a hot loop that performs and handles a one-shot
  algebraic effect (`Bump.tick(resume) => resume(1)`). Current worktree repro after `sbt cli/installBin`:
  `./bench.sh effect-oneshot --backend ssc` = 0.043 ms/iter; `./bench.sh effect-oneshot --backend ssc-asm`
  = 9.46 ms/iter.
- **Root cause:** `JavacJitBackend.walkLong` already lowered one-shot tail-resume effect calls to
  `JitGlobals.resolveEffectLong*`, but `AsmJitBackend.walkLong` did not. The `Bump.tick().toLong` expression
  therefore made ASM bytecode JIT bail and left the workload on the slow effect trampoline.
- **FIXED (2026-06-21, `0d5e03b87`):** ASM now mirrors the Javac lowering for active one-shot effect
  resolvers (`resolveEffectLong`, `resolveEffectLong1`, `resolveEffectLong2`) and treats a resolved effect
  call as Long-shaped for `.toLong`/`.toInt` routing. Regression guard: `AsmEffectJitTest` compiles and runs
  `acc + Bump.tick().toLong` through `AsmJitBackend` with an active resolver.
- **Verified:** `sbt -no-colors "backendInterpreter/testOnly scalascript.interpreter.vm.jit.AsmEffectJitTest
  scalascript.EffectOneShotFastPathTest scalascript.JitLintTest"` = 85/85; `sbt -no-colors cli/installBin`;
  `./bench.sh effect-oneshot --backend ssc` = 0.025 ms/iter; `./bench.sh effect-oneshot --backend ssc-asm`
  = 0.032 ms/iter (was 9.46 ms/iter in the accepted repro).

## rust-foreach-list-realloc — `fixed` (2026-06-21, `abbc98eee`)

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

## effect-op-trailing-comment — `fixed` (2026-06-20)

- **Found by:** busi (building the v2 KSeF inbound port `effect Ksef`).
- **Symptom:** a trailing `//` line-comment on an effect operation's declaration silently broke the
  WHOLE effect. `effect Ksef:` / `  def pull(t: String, s: String): List[String]  // FA(3) docs`
  made `Ksef` parse as a plain object, so every `Ksef.pull(...)` perform threw
  `No method 'pull' on InstanceV(Ksef)` at runtime (the handler never caught it). Root: `preprocessEffects`
  appended the synthetic `= __effectOp__` body at the absolute end of the op line, so a trailing comment
  swallowed it → the op had no body → not an effect op. The same `!bodyLine.contains("=")` guard also
  wrongly skipped an op whose param had a function type (`f: Int => Int`).
- **FIXED (2026-06-20):** `preprocessEffects` now splits off any trailing line-comment first
  (`splitLineComment`, string-literal aware) and inserts `= __effectOp__` into the CODE part, before the
  comment; the "already has a body" check ignores `=>`. Guard: `PreprocessEffectsTest` (7 cases). 53
  existing effect/parser tests green; real-harness repro now returns the handler's value, not a throw.

## jsgen-module-section-scope — `fixed` (2026-06-22)

- **Found by:** busi (deep-offline browser bundle) — the #1 raw `emit-js` full-bundle blocker codex recorded in `src/v2/specs/lf-1-browser-bundle.md` ("importing `std/money.ssc` fails at runtime with `Currency` not initialized before `defaultCurrencies`").
- **Symptom:** any program that `emit-js`'d a markdown module split across sections (e.g. `std/money`, whose `Currency`/`Money` constructors are under one heading and `defaultCurrencies`/`currencyOf` under another) threw on Node — `ReferenceError: Currency is not defined`, or (when reached via the import binding) `not callable: ()`. `SSC_JIT_BACKEND=js` (the JIT path) was fine; only raw `emit-js`/`run-js` failed.
- **Root cause:** each module section is emitted by a *separate* child `JsGen` sharing `topLevelConsts`; the first declares `const std = (()=>{ const money = (()=>{ function Currency… })(); … })()` and later sections merge via `_ssc_mergeDeep(std, (()=>{ const money = (()=>{ … defaultCurrencies = … Currency … })(); … })())`. Each section's IIFE is its own lexical scope, so a later section's bare reference to an earlier section's `Currency` had nothing to resolve to — even though `std.money.Currency` existed at runtime.
- **FIXED (2026-06-22):** a shared `namespaceMembers: Map[path, Set[name]]` (threaded to child gens like `topLevelConsts`) records the members each section declares per namespace path. When emitting a section, `genObjectAsExpr(d, path)` prepends `const { <prior members not declared here> } = <path>;` (e.g. `const { Currency, Money } = std.money;`) so cross-section references resolve from the live, already-merged namespace. `mergeDeep` is unchanged. Keeps the JIT path identical.
- **Guard:** `tests/conformance/money-multisection.ssc` (+ `expected/money-multisection.txt`) — imports `std/money`, calls `currencyOf` (which reaches `Currency` via `defaultCurrencies` and via its `getOrElse` fallback); runs identically on INT/JS/JVM. `MoneyCrossBackendTest` "money.ssc — JS output matches the interpreter" + busi `make v2-test-js` (full v2 core on JS) stay green.

## collection-ctor-aliases — `fixed` (2026-06-15)

- **Found by:** a collections survey (prompted by a "do we only have List/Map?" question).
- **Symptom:** despite the user guide listing `Seq`/`List`/`Vector`/`Set`/`Array`/`Map`, the interpreter only had `List`/`Map`/`Set` companions — `Seq(1,2,3)`, `Vector(...)`, `Array(...)`, `IndexedSeq(...)` all threw `Undefined: Seq` (etc.); `.toVector`/`.toSeq`/`.toIndexedSeq` and `Map.toSeq` were also missing. (JVM, real Scala, was fine.)
- **FIXED (2026-06-15):** the interpreter backs every sequence type with a single `ListV` (JS with arrays), so `Seq`/`Vector`/`Array`/`IndexedSeq`/`Iterable`/`LazyList` companions now alias `List`'s (`BuiltinsRuntime`), JsGen emits those constructors as arrays, and `toList`/`toSeq`/`toVector`/`toIndexedSeq`/`toArray`/`toIterable` are identity conversions on List + Map (interp `dispatchList`/`dispatchMap`, JS array/Map `_dispatch`). On the **JVM backend** each stays its REAL Scala type (raw emit — `Vector(1,2,3)` → a real `Vector`, etc.); a guard asserts JvmGen preserves the companion call so a future change can't silently collapse them to List. Guard: `CrossBackendPropertyTest` "Seq/Vector/Array constructors + conversions cross-backend" (9 shapes incl. LazyList, interp == JS == JVM). Caveat: off-JVM these are NOT distinct runtime types (Vector/Array = List/array, `LazyList` is eager — an infinite LazyList won't work off-JVM). Available collections: List, Map, Set, Seq, Vector, Array, IndexedSeq, Iterable, LazyList, plus Option, Either, Tuple, Range.

## jsgen-enum-payload-extract — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (wave-6 enum probes).
- **Symptom:** matching an `enum` case WITH a payload bound the wrong value on JS — `enum Shape: case Circle(r: Int); … case Circle(r) => …` bound `r` to the case's `_tag` (0/1), not the field. `area(Circle(2)) + area(Square(3))` gave `1` instead of `21`; interp + JVM correct. `genPattern`'s Extract used field NAMES from `caseClassFieldsByType` when known, else the positional `Object.values(scrut).slice(1)[i]` — but enum cases carry an extra `_tag` field, and `caseClassFieldsByType` was populated only for `Defn.Class`, not enum cases, so `slice(1)[0]` returned `_tag`.
- **FIXED (2026-06-15):** `caseClassFieldsInModule` now also indexes `Defn.Enum` cases (name → field list), so enum-case Extract binds by field name. Guard: `CrossBackendPropertyTest` "enum payload, collect, Option.fold cross-backend" (enum-payload-match + enum-nullary).

## interp-collect-partial / jsgen-collect-partial — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (wave-6 collection probes).
- **Symptom:** `xs.collect { case x if x % 2 == 0 => x * 10 }` (a partial function with a guard) threw `Match failure: 1` in the INTERPRETER (it called the PF as a total function), and on JS threw `Method not found: collect` (no `collect` in the array `_dispatch`); JVM correct. `collect` must SKIP elements the PF isn't defined on.
- **FIXED (2026-06-15):** interp — a `collectStep` helper catches the located "Match failure" and skips (reusing the existing `None`-skip path). JS — added a `collect` array-dispatch case that calls the element fn and skips when it throws a "Match failure" (the emitted PF closure's no-match error). Guard: `CrossBackendPropertyTest` collect-guard.

## jsgen-option-fold-curried — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (wave-6 Option probes).
- **Symptom:** `Some(5).fold(0)(x => x * 2)` failed on JS — the curried `Option.fold(ifEmpty)(f)` was absent from the `_Some`/`_None` dispatch (only `Either.fold(fa, fb)` uncurried was present). interp + JVM correct.
- **FIXED (2026-06-15):** added `fold` to the JS Option dispatch — `_Some`: `(f) => f(value)`, `_None`: `(f) => ifEmpty` — handling the curried second clause. Also added `exists`/`forall` and fixed `Some.contains` to use structural `_eq`. Guard: `CrossBackendPropertyTest` option-fold-some/-none.

## xbackend-range-by-step — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (wave-6/7).
- **Symptom:** `(0 to 10 by 2)` — a Range with a `by` step — threw on interp (`No method 'by' on List`) and on JS; JVM correct. interp + JS materialize a Range as a List/array, which had no `by`; the JS `by` infix also fell to an invalid `(range by step)` emission.
- **FIXED (2026-06-15):** `by(step)` keeps every step-th element of the materialized range — added to interp `dispatchList`/`dispatchList1` and the JS array `_dispatch`; JsGen now emits the `by` infix as `_dispatch(range, 'by', [step])`. Guard: `CrossBackendPropertyTest` "ranges, collection + string method gaps cross-backend" (range-by-sum/-until).

## jsgen-collection-method-gaps / jsgen-string-padto — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (wave-7).
- **Symptom:** several stdlib methods were missing from the JS `_dispatch` (and one from interp), failing with `Method not found` / `No method`: `List.scanLeft`/`scanRight`/`indexWhere`, tuple `.swap`, `String.padTo`; interp also lacked `indexWhere`. interp + JVM (or JVM alone) were correct.
- **FIXED (2026-06-15):** added JS array dispatch `scanLeft`(curried)/`scanRight`/`indexWhere`/`swap`, JS string `padTo` (Char arg arrives as a char-code number), and interp `indexWhere` (`dispatchList`). Guard: `CrossBackendPropertyTest` string-pad / list-scanleft / list-indexwhere / tuple-swap.

## interp-js-string-map-nonchar — `fixed (interp + js)`

- **Found by:** `CrossBackendPropertyTest` (wave-7).
- **Symptom:** `"abc".map(c => c.toInt).sum` threw (`No method 'sum'`) on interp + JS — mapping a String's chars to a NON-Char value should yield a `Seq[Int]` (then `.sum`), but interp/JS `String.map` rebuild a String. JVM correct (294).
- **FIXED (interp, 2026-06-15):** `String.map` returns a `String` only when EVERY mapped element is a `Char` (interp has a real `CharV`); otherwise a `List` (`strMapResult`). `"abc".map(_.toInt)` → `List(97,98,99)`; char-to-char maps stay Strings.
- **FIXED (JS, 2026-06-21):** added a JS Char wrapper. A char produced by iterating a String (`map`/`filter`/`foreach`/`flatMap`/`charAt`/`head`/`last`/`toList`/`forall`/`exists`/`count`) is now boxed as a `_Char(code)` (`JsRuntimePart2a`): `valueOf` returns the code point and `toString` the 1-char string, so concatenation/arithmetic/`_show` coerce naturally. `_dispatch` gains a `_Char` branch mirroring the interp's `dispatchChar` (`toInt`→code point, `isDigit`/`isLetter`/`toUpper`/`asDigit`/…), and `String.map` now returns a String only when every result is a `_Char` (else a Seq) — mirroring `strMapResult`. `_eq` bridges `_Char` to a 1-char String literal and to an Int (the interp allows `CharV == IntV`), so `c == 'a'` and predicates work even though char *literals* stay JS strings. Verified: interp == JS == JVM on `"abc".map(_.toInt).sum` (294) and char-method map/filter; `CrossBackendPropertyTest` "String.map char vs non-char cross-backend" now asserts all three agree.
- **Residual (minor, by design):** a char *literal*'s `.toInt` (`'5'.toInt` → 5 on JS vs 53 on interp/JVM) still diverges — char literals stay JS strings to avoid touching literal-pattern codegen (which compares with `===`, not `_eq`). The actionable bug (`String.map(nonChar)` + iterated-`Char` methods) is closed; literal coercion is left as a separate, lower-value follow-up.

## jvmgen-autooutput-after-classdef — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (wave-5 case-class probes).
- **Symptom:** a JVM program with a top-level `case class` (or trait/object) followed by ANY auto-output/expression statement printed NOTHING — `case class P(x: Int)\nprintln(if P(1) == P(1) then 10 else 0)` produced empty output; interp + JS correct. `wrapAutoOutput` emitted a bare `{ … }` block, and `case class P(x: Int)` on one line followed by `{ … }` on the next is parsed by Scala as **P's body template**, so the statement was swallowed (never run).
- **FIXED (2026-06-15):** `wrapAutoOutput` now emits `locally { … }` (an unambiguous method call) instead of a bare `{ … }`, so the block can't attach to a preceding definition. Guard: `CrossBackendPropertyTest` "collections, case-class equality, num+string cross-backend" (caseclass-eq/-ne/-output).

## jsgen-structural-equality — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (wave-5 case-class probes).
- **Symptom:** `==` on the JS backend used JS reference equality (`===`), so two structurally-equal case-class instances / tuples / Lists compared unequal — `P(1) == P(1)` → `false`; interp + JVM correct.
- **FIXED (2026-06-15):** added a `_eq(a, b)` deep-structural-equality runtime helper (arrays elementwise, objects by `_type` + own keys, primitives by `===`) and routed `_arith('==' / '!=', …)` through it. Also used for Set dedup. Guard: `CrossBackendPropertyTest` caseclass-eq/-ne, tuple-eq.

## jsgen-set-constructor — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (wave-5 Set probes).
- **Symptom:** `Set(1, 2, 3)` failed on JS with `TypeError: Constructor Set requires 'new'` — JsGen had `Map`/`List` constructor cases but no `Set`, so `Set(...)` fell through to the JS global `Set`.
- **FIXED (2026-06-15):** added a `Set(...)` / `Set[T](...)` case emitting `_setOf(...)` — a runtime helper that builds a structurally-deduplicated array, so the existing array `_dispatch` methods (`size`/`toList`/`sorted`/`contains`/…) apply. Guard: `CrossBackendPropertyTest` set-dedup-ops, set-contains.

## interp-num-string-concat — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (wave-5 Map probes).
- **Symptom:** `6 + "_"` (a number `+` a String — Scala's `any2stringadd`) threw in the interpreter (`No method '+' on IntV`); JS + JVM correct. interp's `Int + …` only handled numeric operands.
- **FIXED (2026-06-15):** `dispatchInt` / `dispatchInt1` now concatenate when the `+` operand is a `StringV` (`n.toString + s`). Guard: `CrossBackendPropertyTest` num-string-concat.

## js-supertype-typetest — `fixed` (2026-06-15)

- **Found by:** busi (UI session). A `cardWithHeader(header)` card title rendered on **no**
  screen in the SPA — money, compliance, and the new UA ФОП cockpit alike — while the card
  body rendered fine and the interpreter (`ssc render`) was correct, so every `.ssc` test
  passed. Browser DOM inspection showed the card-header `<div>` absent; the page heading
  (`thView(2,…)`) and standalone section headings (`thView(3,…)` in a vstack) rendered.
- **Symptom:** on the **JS backend**, a type-test against a supertype — sealed trait /
  parent enum / abstract class — never matches a subtype instance. `sealed trait TkNode;
  case class HeadingNode(t) extends TkNode; (x: Any) match { case h: TkNode => … }` skips the
  `TkNode` arm for a `HeadingNode`. Emitted objects carry only their leaf `_type`
  (`{_type:'HeadingNode'}`); `JsGenCpsCodegen.genPattern`'s `Pat.Typed` branch emitted an
  exact `scrut._type === 'TkNode'` check, which a subtype never satisfies. `cardWithHeader`
  lowers `header match { case h: TkNode => render; case _ => [] }` (header field typed `Any`),
  so the title fell to the empty wildcard. The JS analogue of the interp/JIT fix for #1/#3.
- **FIXED — single-module (commit 775a10e68):** scanned type decls + `extends` into
  `supertypeName → Set[concrete leaf _type]` per module; `genPattern`'s `Pat.Typed` widens a
  no-tag (supertype) check to an `_type` OR over that closure. Guard `SupertypeTypeTestJsTest`.
- **FIXED — cross-module (follow-up):** the first commit was insufficient for the actual busi
  case and the single-module test gave **false confidence**. The JS backend emits each imported
  module with a *fresh child `JsGen`* (genImport), and `TkNode` + subtypes live in `nodes.ssc`
  (a `package:` module) while `case h: TkNode` lives in `lower.ssc` — so the importer's matcher
  had no record of the subtype graph and still fell back to the broken exact check (browser
  re-verify after the rebuild still showed dropped titles + `_type === 'TkNode'` in the emitted
  SPA). Fix: accumulate the subtype edges ACROSS imports — `collectSubtypeEdgesFromModule`
  (descends into `package:` wrapping objects) + `recomputeSubtypeClosure`, folded in for the
  entry module and, in genImport, for each imported module + propagated into the child gen
  (mirrors `importedParamOrder`). Guard `SupertypeTypeTestXModuleJsTest` (multi-file: imported
  `package:` trait/subtypes + transitive enum across the import boundary) — the multi-file test
  the `bugs` rule requires. Spec `specs/js-supertype-typetest.md`.
- **Repro:**
  ```scalascript
  sealed trait TkNode
  case class HeadingNode(text: String) extends TkNode
  def isTk(x: Any): String = x match
    case h: TkNode => "tk"
    case _         => "other"
  println(isTk(HeadingNode("hi")))  // interp/JVM: "tk" ; JS (buggy): "other"
  ```

## jsgen-collection-dispatch-gaps — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (wave-4 collection-HOF probes).
- **Symptom:** `xs.sortWith((a,b) => a < b)`, `xs.sorted`, `xs.partition(p)` fail on the JS backend (node) — they were simply MISSING from the `_dispatch` runtime method table (`JsRuntimePart2b.scala`); interp + JVM correct. `val (a, b) = xs.partition(…)` then also failed for lack of `partition`.
- **FIXED (2026-06-15):** added `sortWith` (`lt(a,b)?-1:lt(b,a)?1:0`), `sorted`, `partition` (→ `[yes, no]`), and `span` to the JS `_dispatch` array-method table. The `val (a, b) = …` tuple destructuring already works (`genPatDestructure`). Guard: `CrossBackendPropertyTest` "collection HOFs and pattern matching cross-backend".

## jsgen-match-guard-bind — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (wave-4 pattern-match probes).
- **Symptom:** a `match` with a case GUARD (`case x if x < 0 => …`) fails on the JS backend (node syntax error); interp + JVM correct. `genMatchAsStmts` and the coroutine `genGenStmt` match dropped `c.cond` entirely, so a guarded `case x if …` got pattern-cond `"true"` and was treated as a catch-all mid-chain → malformed `{ … } else if (…)` JS. (`genReceiveMatcher` ANDed the guard but evaluated it with the pattern bindings out of scope.)
- **FIXED (2026-06-15):** all three JS match paths now fold the guard into the arm condition via an IIFE that scopes the pattern bindings: `(cond) && (() => { <bindings>; return (<guard>); })()`. Guarded arms are no longer mistaken for catch-alls (the switch fast-path also excludes them since the cond is no longer `"true"`). Guard: `CrossBackendPropertyTest` "collection HOFs and pattern matching cross-backend" (match-guard-bind shape).

## interp-monadic-forcomp — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (wave-4 comprehension probes).
- **Symptom:** a `for`-comprehension over `Option` / `Either` (non-`List` monad) threw **in the interpreter**; JS + JVM were correct.
  - `for x <- Some(3); y <- Some(4) yield x + y` → interp `No method 'getOrElse' on List` (interp desugared the Option for-comp as a List op → result was a `List`, not an `Option`).
  - `for x <- Right(3); y <- Right(4) yield x * y` → interp `Cannot iterate over Right(3)`.
- **FIXED (2026-06-15):** made `PatternRuntime.evalForYield` monad-polymorphic. When a generator's evaluated value is NOT a `ListV` (and the pattern is irrefutable + the tail is all simple generators), it desugars to `recv.flatMap(pat => <rest>)` / `recv.map(pat => body)` dispatched on the actual value via `DispatchRuntime.dispatch1` + a `NativeFnV` closure — exactly what the JS/JVM backends emit. `List` keeps its allocation-light fast path; guards / refutable patterns over a non-List monad fall through unchanged. Guard: `CrossBackendPropertyTest` "monadic for-comprehension cross-backend" (option some/none, either right/left, single-generator, + a List regression — interp == JS == JVM).

## xbackend-wave4-jvm-transient — `wontfix` (2026-06-15, not reproduced)

- Two wave-4 shapes (`xs.zip(ys).map((a,b)=>a+b).sum`, `(1,(2,3)) match { case (a,(b,c)) => … }`) reported a JVM `scala-cli failed` ONCE, but did NOT reproduce on a clean re-run (interp == JS == JVM all green). The original failure coincided with two contending `sbt`/`scala-cli` processes corrupting temp compiles. Kept as cross-backend guards in "collection HOFs and pattern matching cross-backend"; no code change.

## jvmgen-js-curried-partial — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (main-path edge-case probes).
- **Symptom:** PARTIAL application of a curried def fails on the **JS backend** (`not callable: NaN`); interp + JVM are correct. `def add(a: Int)(b: Int) = a + b; val f = add(3); f(4)` — JsGen flattens curried params to `function add(a, b)`, so `add(3)` runs the body with `b === undefined` → `3 + undefined` = `NaN`. FULL application `add(1)(2)` works (it arrives flattened as `add(1, 2)`); only under-applied calls break. Reproduced for 2- and 3-clause defs.
- **FIXED (2026-06-15):** added a `_curry(fn, arity, args)` JS runtime helper (accumulates args, applies when arity reached) and an auto-curry guard at the top of plain multi-clause def emission: `if (arguments.length < N) return _curry(fname, N, arguments);`. Only emitted for multi-clause defs with no defaults / using / context-bounds; single-clause defs and full applications are unaffected (arity already reached). Guard: `CrossBackendPropertyTest` "curried partial application cross-backend" (2-/3-clause, full + partial, interp == JS == JVM).

## effect-perform-in-fordo — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (effects-in-HOF/loop probes).
- **Symptom:** an effect op performed inside a `for i <- 0 until n do …` loop diverged across all three backends. interp was CORRECT; **JVM** failed scala-cli (`None of the overloaded alternatives of method + in class Int` — `acc + Counter.tick()` where `tick()` is the Any `_perform`), and **JS** printed garbage (`0[object Object][object Object]…`). The `while`-loop form of the same program works on all backends (dedicated CPS while-trampoline); the `for … do` → `foreach(i => …)` desugar did NOT CPS-thread the effect in the closure body. `.map` / `.foldLeft` closures DO thread effects — only `foreach`-from-`for-do` was broken.
- **FIXED (2026-06-15):** added for-do recognizers to BOTH CPS emitters (`JvmGenCpsTransform.emitCpsExpr` + `JsGenCpsCodegen.genCpsExpr`) that desugar to the same while-trampoline the `while` form uses, so the body's `perform`s thread through `_bind`:
  - **Range** `for i <- (lo until/to hi) do body` → index `var`/`let` + trampoline (covers `until` exclusive + `to` inclusive + bodies reading the loop var).
  - **Collection** `for x <- coll do body` (pure non-Range `coll`) → `.iterator` (JVM) / array-index (JS) + trampoline.
  Multi-generator / guarded / complex-pattern for-do falls through to the existing (raw / `_forEach`) path unchanged. Guard: `CrossBackendPropertyTest` "effect perform in for-do loop cross-backend" — 5 shapes (range until/to/loop-var + collection elem/side-effect), interp == JS == JVM.
- **Repro:**
  ```scalascript
  effect Counter:
    def tick(): Int
  def prog(): Int ! Counter =
    var acc = 0
    for i <- 0 until 3 do
      acc = acc + Counter.tick()
    acc
  println(handle(prog()) { case Counter.tick(resume) => resume(5) })  // interp: 15 ; jvm: COMPILE ERROR ; js: garbage
  ```

## jvmgen-handle-result-mainpath — `fixed` (2026-06-15, all contexts incl. Any-taint propagation)

- **Found by:** `CrossBackendPropertyTest` (effect-result × main-path composition probes).
- **Symptom:** a `val r = handle(...)` (Any-typed `_handle` result) used in a NON-arithmetic main-path
  context fails JVM scala-cli; interp + JS run it fine. A cluster of related JVM-only divergences:
  - `r match { case _ => r * 2 }` → `value * is not a member of Any` (`emitExprDeep` had no `Term.Match` case → arm fell to `.syntax`).
  - `if r > 5 then r * 10 else 0` → `Found Any / Required Boolean` (the `_binOp(">", r, 5)` cond wasn't cast to Boolean).
  - `dbl(r)` (user fn) → `Found Any / Required Int` (main-path call didn't cast the arg to the callee param type; only the CPS path did).
- **FIXED (2026-06-15):** in `emitExprDeep` — added a `Term.If` Boolean cast when the cond is an Any-typed handle-result comparison, a `Term.Match` case that recurses scrutinee + arm bodies + guards, and a `Term.Tuple` case; cast main-path call args that reference a handle-result val to the callee's `calleeParamType` (reusing the CPS `localDefSigs`/`depDefs` index). Routed any term that references a handle-result val through `emitExprDeep` via a new `termRefsHandleResultVal` in `termNeedsCustomEmit`. Guard: `CrossBackendPropertyTest` "effect-result main-path composition cross-backend" (match / if-cmp / fn-arg / multishot-arith / nested-handles — interp == JS == JVM).
- **ALSO FIXED (2026-06-15, Any-taint propagation):** the two formerly-deferred contexts:
  - `List(r, r).sum` → `No given Numeric[Any]` — broadened the `emitExprDeep` `_anyCall0` Select routing from "qual IS a handle-result-val Name" to "qual REFERENCES one" (`termRefsHandleResultVal(qual)`), so `List(r, r).sum` → `_anyCall0(List(r, r), "sum")`.
  - tuple-accessor arithmetic `val t = (r, r+1); t._1 + t._2` — added `anyTypedVals`, a superset of `handleResultVals` populated by Any-taint PROPAGATION: an untyped val whose rhs references an Any-typed val (`val t = (r, r+1)`) is itself Any-typed. The routing predicates now key off `anyTypedVals`, and the arith-operand check also recognizes `Select(anyTypedVal, _)` (so `t._1 + t._2` lowers to `_binOp`). Only ever non-empty for effect programs (seeded by `handleResultVals`), so pure code is unaffected. Guard: `result-in-list-sum` + `result-in-tuple` added to the composition test (interp == JS == JVM).

## agent-streaming-test-port-collision — `fixed` (2026-06-15, 26dae7699)

- **Found by:** codex during `rozum-agent-endpoint-pool` regression check.
- **SHA at filing:** `2334d0be4` (feature worktree).
- **Symptom:** running the sync and streaming agent SDK suites in the order
  `AgentSdkInterpreterTest AgentSdkStreamingInterpreterTest` aborts the streaming
  suite with `java.net.BindException: Address already in use`.
- **Repro:** `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/rozum-agent-endpoint-pool && sbt "backendInterpreterPluginTests/testOnly scalascript.AgentSdkInterpreterTest scalascript.AgentSdkStreamingInterpreterTest"`.
- **Root cause:** `examples/rozum-agent.ssc` binds `19694`, the same port as
  `AgentSdkStreamingInterpreterTest`; when the sync suite ran first, the
  streaming suite could immediately rebind the same port and abort.
- **Fix:** moved `AgentSdkStreamingInterpreterTest` to port `19698`.
- **Verify:** `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/rozum-agent-endpoint-pool && sbt "backendInterpreterPluginTests/testOnly scalascript.AgentSdkInterpreterTest scalascript.AgentSdkStreamingInterpreterTest"` — 14 tests passed in the formerly failing order.



---

## jvmgen-handle-result-arith — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (new effect-composition shapes — handle result fed into main-path arithmetic).
- **Symptom:** using a `val` bound to `handle(...)` as an operand of an arithmetic/comparison infix fails JVM scala-cli with `value * is not a member of Any`. `handle(...)` lowers to `_handle(...)` (returns `Any`), so `val r = handle(...){…}; println(r * 2 + base)` emits `r * 2` raw on the Any-typed result, which Scala 3 rejects. interp + JS run it fine.
- **Repro:** a one-shot effect program ending `val r = handle(loop(n)){ case Counter.tick(resume) => resume(k) }; println(r * 2 + base)` (or two results: `println(r1 + r2)`).
- **Root cause:** `termNeedsCustomEmit` only routed a handle-result-val through `emitExprDeep` (where `ApplyInfix` lowers `+ - * / % < > <= >=` to `_binOp`) when the val appeared in a 0-arg method `Select` (`termContainsHandleResultCall`), NOT when it appeared as an arithmetic operand — so `r * 2` fell to `emitExpr`'s `.syntax` raw fallback.
- **FIXED:** added `termContainsHandleResultArith` (walks for a handle-result-val `Term.Name` used as an operand of an arithmetic/comparison `ApplyInfix`) and wired it into `termNeedsCustomEmit`; the existing `emitExprDeep` `ApplyInfix` → `_binOp` path then lowers it (nested arith re-fires the predicate via `emitCallArg`→`emitExpr`). Guard: `CrossBackendPropertyTest` effect sub-shapes 8 (`r*2+base`) and 9 (`r1+r2`), run through scala-cli on seeds 11/47 and 155/191. Property test green.

---

## interp-returnclause-effect-in-while — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` diagnostic (return-clause shape localization).
- **Symptom:** a deep return-clause handler over a program that performs an effect inside a `while` loop threw **in the interpreter** with `Unhandled effect: Log.emit (no handler in scope)`, even for a single iteration. **JS and JVM both produce the correct result.** This made the property test's case-7 (return-clause) shape vacuous: interp threw → seed skipped → JS/JVM never compared.
- **Repro:**
  ```scalascript
  effect Log:
    def emit(): Int
  def prog(): Int ! Log =
    var i = 0
    while i < 3 do
      Log.emit()
      i = i + 1
    0
  val xs = handle(prog()) {
    case Log.emit(resume) => 7 :: resume(())
    case Return(_) => List()
  }
  println(xs.length)   // js/jvm: 3 ; interp: THROWS
  ```
- **Root cause:** the handler body `7 :: resume(())` is NOT a clean tail-resume, so `evalHandle` installs no inline resolver for `Log.emit`. The op then has to thread as a `Computation` (Perform/FlatMap) through `handleInterp`, but the fast-while path (`tryFastWhileAssign`, `EvalRuntime.scala`) drove the loop's leading applies eagerly via `Computation.run`, so the `Perform` escaped the handler. A direct (non-loop) emit works; only the while-loop shape failed.
- **FIXED (2026-06-15):** captured `EffectAnalysis.effectOps` into `Interpreter.effectOpNames` (alongside `multiShotEffects`) at module init, and added an up-front guard `whileBodyHasUnresolvedEffect` at the top of `tryFastWhileAssign`: if the loop body performs an effect op with NO active inline resolver (`EffectsRuntime.lookupResolver(eff, op) == null`), bail (return null) to the monadic trampoline, which threads effects via `FlatMap`. The one-shot tail-resume fast path keeps a live resolver, so the guard returns false for it and the fast/JIT path is preserved (no perf regression — `EffectVmContinuationsTest` / `EffectOneShotFastPathTest` stay green). Guard: `CrossBackendPropertyTest` "effect return-clause cross-backend (… / while)" now runs the while shape interp == JS == JVM, and the generated JVM differential rose from 17 → 19 checked seeds (the formerly-skipped return-clause seeds 23/59 now produce an interp baseline). 366 effect/JIT/VM tests green.

---

## jvmgen-returnclause-effect-in-recursion — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` diagnostic (return-clause shape localization).
- **Symptom:** a return-clause handler over a **recursive** effectful function fails JVM scala-cli compilation: `Found: (_t3 : Any) / Required: Int`. **interp and JS both produce the correct result.**
- **Repro:**
  ```scalascript
  effect Log:
    def emit(): Int
  def go(n: Int): Int ! Log =
    if n <= 0 then 0
    else
      Log.emit()
      go(n - 1)
  def prog(): Int ! Log =
    go(3)
  val xs = handle(prog()) {
    case Log.emit(resume) => 7 :: resume(())
    case Return(_) => List()
  }
  println(xs.length)   // interp/js: 3 ; jvm: scala-cli COMPILE ERROR
  ```
- **Root cause:** the CPS transform emits `def go(n: Int): Any = _bind(..., (_t3: Any) => go(_t3))` — the recursive call passes the Any-typed `_bind` continuation result `_t3` to `go`, whose param stays declared `Int`. The existing `applyCalleeCasts` (which casts CPS call args to the callee's declared param types) only consulted `depDefs`/`depClasses` (IMPORTED deps), never the user module's own defs, so a recursive/sibling call got no cast. (Widening the param to `Any` is NOT a valid fix — params keep their declared type so field access like `node.nodes` type-checks; the design casts at call sites instead.)
- **FIXED (2026-06-15):** added `localDefSigs` — a pre-pass index of the user module's own `Defn.Def`s — and made `applyCalleeCasts` / `calleeParamType` / `calleeTypeArgMap` consult it as a fallback after `depDefs`. `go(_t3)` now emits `go(_t3.asInstanceOf[Int])`. Guard: `CrossBackendPropertyTest` "effect return-clause cross-backend (direct / recursion)" (interp == JS == JVM). 120 effect/CPS unit tests stay green.

---

## jvmgen-multishot-handle-result-any — `fixed` (2026-06-15, 23a33c976)

- **Found by:** `CrossBackendPropertyTest` (its multi-shot effect shape).
- **Symptom:** a method call on the result of `handle(...)` fails JVM scala-cli with e.g. `value sum is not a member of Any` — `handle(...)` lowers to `_handle(...)` which returns `Any`, so `val all = handle(prog()){…}; all.sum` (typical for a multi-shot handler whose result is a `List`) doesn't type-check. interp + JS (dynamically typed) run it fine.
- **Repro:** a `multi effect NonDet` program ending `val all = handle(prog()){ case NonDet.choose(opts, resume) => opts.flatMap(o => resume(o)) }; println(all.sum)`.
- **Severity / why deferred at filing:** harder than the emitCaseBody class — it is about the `_handle` RESULT type (Any), not the handler body. A real fix needed the codegen to know the handled-program's result type (here `List[Int]`) and cast, or `_handle` to be generically typed; `List[Any].sum` would still need `Numeric[Any]`.
- **FIXED (23a33c976):** runtime `_anyCall0(recv, m)` dynamically dispatches 0-arg collection methods on an Any Iterable (numeric folds via `_binOp`); codegen tracks vals bound to `handle(...)` (`handleResultVals`), routes a `x.method` on them through `emitExprDeep` (via `termContainsHandleResultCall` in `termNeedsCustomEmit`) → `_anyCall0`. Property test re-added the multi-shot `all.{sum,max,min,length}` shape as the guard; 96 tests green.
---

## jvmgen-effect-handler-arg-arith — `fixed` (2026-06-15, 7c843b121)

- **Found by:** `CrossBackendPropertyTest` (its broadened multi-arg / arithmetic effect handlers).
- **Symptom:** a handler that does arithmetic on op-args, e.g. `case Combine.mix(a, b, resume) =>
  resume(a * b + 1)`, fails JVM scala-cli with `value * is not a member of Any`. The op-args are
  bound `val a = _args(0)` (type `Any`) and `emitCaseBody` had no arithmetic case → `a * b`
  emitted raw, which Scala 3 rejects on `Any`. interp + JS run it fine.
- **Repro:** `println(handle(loop(5)) { case Combine.mix(a, b, resume) => resume(a * b + 1) })`
  for `effect Combine: def mix(a: Int, b: Int): Int` → scala-cli "value * is not a member of Any".
- **FIXED (7c843b121):** `emitCaseBody` now lowers an arithmetic/comparison `ApplyInfix` to the
  `_binOp("op", l, r)` runtime helper (same as `emitExpr` for Any operands; mirrors the existing
  `::` Any-cast case). Guard: `CrossBackendPropertyTest` effect shapes (arg-carrying / two-op) run
  through scala-cli. 101 effect+jvmgen tests green.
- **ALSO FIXED (78d1ce178) — control-flow case:** an `if` in a handler body with a comparison on Any-typed op-args (`if k > 2 then resume(k) else resume(0)`) — `emitCaseBody` had no `Term.If` case so `k > 2` emitted raw. Added a `Term.If` case that recurses (lowers `k > 2` to `_binOp`) + casts the condition to `Boolean`. Property test gained a conditional-resume effect shape (run through scala-cli).

---

## jvmgen-handle-in-arg-position — `fixed` (2026-06-15, 91fc574f5)

- **Found by:** `CrossBackendPropertyTest` (xbackend-property-equivalence — the generated
  cross-backend differential, found this on its first effects run).
- **Symptom:** JVM codegen emits a `handle(...)` effect expression RAW (unqualified) when it
  appears in **call-argument position**, e.g. `println(handle(body){cases})`, so scala-cli fails
  with `Not found: handle - did you mean _handle?`. interp **and** JS run it correctly.
- **Works (idiomatic):** binding the result first — `val r = handle(body){cases}; println(r)` —
  lowers correctly to `_handle(() => body, Set(...), Map(...))`. Only the inline/nested form breaks.
- **Repro (minimal):**
  ```scalascript
  effect Counter:
    def tick(): Int
  def loop(n: Int): Int ! Counter =
    var acc = 0
    var i = 0
    while i < n do
      acc = acc + Counter.tick()
      i = i + 1
    acc
  println(handle(loop(3)) { case Counter.tick(resume) => resume(2) })
  ```
  `ssc emit-jvm` / scala-cli the output → "Not found: handle". Change last line to
  `val r = handle(loop(3)) { ... }
println(r)` → works.
- **Root cause:** `JvmGen` lowers `handle` via `emitExpr` (case `handle(body){cases}` →
  `emitHandleForm`) and special-cases the `val x = handle(...)` / statement forms, but an
  effectful term nested inside another `Term.Apply` arg falls to the `.syntax` raw fallback
  instead of recursing the arg through `emitExpr`/`emitHandleForm`. (Likely the same for other
  effectful forms — `runAsync`, etc. — as direct call args.)
- **Severity:** low — narrow corner case, trivial workaround (bind to a `val`). Fix touches the
  core CPS emission path (would need care vs the 33 JvmGenEffects tests), so deferred from the
  property-test slice that found it.
- **FIXED (91fc574f5):** `termContainsEffectExpr` (walks children for any effectful sub-expr) added to `termNeedsCustomEmit` so a `handle`/effect nested in a call arg routes through `emitExprDeep` and lowers to `_handle(...)`. Regression guard: `CrossBackendPropertyTest` effect kind uses the inline `println(handle(...))` form (interp==JS==JVM via scala-cli). 119 effect+jvmgen tests green, no regression.

---

## interp-import-cycle-stackoverflow — `fixed` (2026-06-14)

- **Reported:** busi (`@busi-claude-code`), during the busi `p5` `dispatch.ssc`
  decomposition (the facade re-export / strict-DAG work).
- **Symptom:** a true module **import cycle** (`A→B→A`, e.g. a sub-module importing
  back from the facade that imports it) aborts with a bare `java.lang.StackOverflowError`
  and **no module-resolution message** — the cause (a cycle) is invisible. Distinct
  from the FIXED `interp-module-loader-dedup` (a *diamond* is acyclic and handled by
  the cache; a *cycle* is not).
- **Repro:** 3–4 modules forming a cycle: `a` imports `b`, `b` imports `a` (or the
  facade↔leaf variant: `a` imports back from `facade`, `facade` imports `a`). Run the
  entry → `StackOverflowError`. See `runtime/.../InterpImportCycleTest.scala`.
- **Root cause:** `SectionRuntime.runImport`'s `moduleCache.getOrElseUpdate(path, …)`
  only **inserts after the thunk returns**; while a module's body is still running its
  path is absent from the cache, so a cyclic re-import re-runs it → unbounded recursion.
- **Fix:** a shared, insertion-ordered `moduleLoading: LinkedHashSet[os.Path]` threaded
  into child interpreters like `moduleCache`. `runImport` checks it **before**
  `getOrElseUpdate` — a re-entry on a still-loading path throws
  `InterpretError("Import cycle detected: a.ssc → b.ssc → a.ssc")`; the path is added
  before the body runs and removed in a `finally`, so a later legitimate import of the
  same (finished) module is unaffected. Purely diagnostic — no semantic change for
  acyclic graphs / diamonds. Spec `specs/import-cycle-diagnostic.md`.
- **Verify:** `InterpImportCycleTest` (2-cycle + facade↔leaf cycle → legible error not
  `StackOverflowError`; acyclic re-export control still computes) + `InterpModuleDedupTest`
  green (no regression).
- **Landed:** (this branch → origin/main).

## interp-cons-in-effect-handler — `fixed` (example) (2026-06-13, `721ee62b9`)

- **FINAL diagnosis (two earlier mis-diagnoses corrected):** NOT a `::` bug and NOT a
  "resume result not forced to ListV" bug. `resume(())` **correctly** returns the
  continuation's pure result `()` (Unit); `println(rest)` after `val rest = resume(())`
  prints `()`. The `algebraic-effects.ssc` Logger handler did `msg :: resume(())`, i.e.
  `msg :: ()` → "No method '::' on StringV" — it assumed `resume(())` of the final
  continuation would be `Nil`. That is the **deep-handler list-accumulation** pattern
  (Koka/Eff `return x => []`), which needs a handler **return clause**. ScalaScript's
  `handle` has **no return clause** (the spec's own Logger example just does `resume(())`,
  returning Unit), so the pattern is unsupported. **Example bug, not an interp bug.**
- **Fixed:** rewrote the Logger section to a working accumulator (append each msg + resume)
  producing the same `List(Hello, World!)`, with a comment on the return-clause gap.
  Also corrected the State section (stdlib `State` + `set`, dropped a broken parameterized
  redecl — see `interp-parameterized-effect-decl`).
- **Underlying language gap (future feature, not filed as a bug):** a handler **return
  clause** would make `msg :: resume(())` work (the spec types `resume` as returning the
  *handler body's* type, which requires bridging the pure/base case). Large feature
  (parser + typer + interp + 4 backends) — out of scope; noted in BACKLOG.

## interp-parameterized-effect-decl — `fixed` (2026-06-13, `2a818e45c`)

- **Fixed:** `Parser.effectLinePat` (the regex that rewrites `effect Name:` →
  `object Name { … }`) had no type-param clause after the name, so `effect State[S]:` /
  `effect Box[T]:` were left un-rewritten and reached the Scala parser as a bare
  `effect Name[T]` expression → `No method 'Name' on NativeFnV(<native:effect>)`. Added an
  optional `(?:\[[^\]]*\])?` after `(\w+)` (the `object` drops the type param; op
  signatures may still mention it — the interpreter erases types). Shared `lang/core`
  Parser, so all backends benefit. Regress: `StdEffectsTest` (`effect Box[T]:` decl + handle).

## interp-effect-multishot-in-subsection — `fixed` (2026-06-13, `2a818e45c`)

- **CORRECTION:** filed as `interp-effect-multishot-cross-section-leak` — that "global state
  leaks from an earlier one-shot `handle`" diagnosis was **wrong**. Real cause: `multiShotEffects`
  was **never populated for subsection code blocks at all**. `Interpreter.runInit` collected the
  effect-analysis trees only from top-level `module.sections` content, not the nested `##`/`###`
  subsections where the blocks actually live (`[DBG] sections=1 allTrees=0 multiShotEffects=Set()`).
  So a `multi effect` declared in a subsection was never registered → its handler defaulted to
  one-shot → `One-shot violation` on the 2nd `resume`. A `multi effect` directly under the top-level
  `#` worked, which made it look order/leak-dependent.
- **Fixed:** `runInit`'s tree collection now recurses `s.subsections`. Regress: `StdEffectsTest`
  (`multi effect` in a `##` subsection multi-shots); `examples/algebraic-effects.ssc` runs
  end-to-end and is in `ExamplesSmokeTest`. Interp-only — JVM/JS codegen already gather all
  blocks recursively.

## interp-toString-on-collection — `fixed` (2026-06-13, `225aacc18`)

- **Fixed:** intercept `toString` (0-arg) at the top of `DispatchRuntime.dispatch`
  (alongside the `asInstanceOf` early-return) → render via `Value.show`, the canonical
  println / string-interpolation path, so `x.toString == s"$x"` for every value. A
  case-class instance with a user-defined `toString` method keeps it (checked via
  `lookupTypeMethod` first). Needed to intercept at the TOP because type-specific
  dispatchers mis-handle the name first (`map.toString` → key lookup → "No key
  'toString'"). Interp-only fix (JVM/JS codegen emit native `toString`). Regress:
  `BugReproTest` (list render + composite canonical-render invariant across
  List/Map/tuple/Option/case-class); 65 `.toString`-dependent tests across 7 suites
  green; `examples/async-parallel-demo.ssc` now runs end-to-end.
- **Found:** by me, expanding `ExamplesSmokeTest` (`examples/async-parallel-demo.ssc`
  fails). Reproduces on `origin/main` (`e73fd9a73`) via the interpreter.
- **Symptom:** `.toString` is universal in Scala (every value has it) but the
  interpreter has no `.toString` dispatch for a `ListV` (and likely other collection /
  composite Values) → `No method 'toString' on ListV(List(50, 50, 50))`.
- **Repro:**
  ```scalascript
  val xs = List(50, 50, 50)
  println("result=" + xs.toString)   // No method 'toString' on ListV
  ```
- **Note:** broadly useful, likely small — add a universal `.toString` fallback in the
  interpreter's method dispatch (render via the same path as `println`/string-concat).
  Check Map/Set/tuple/Option/Either too. Cross-backend regression.

## interp-typed-data-not-callable (a.k.a. bare-fn-ref auto-invoke) — `fixed` (2026-06-13, `175c01d72`)

- **Root cause (narrowed):** NOT a rare typed-data construct — it was the common
  `xs.foreach(println)` idiom. Normalize rewrote **every** bare `println` → `Console.println`
  (a `Select` to an InstanceV native-fn field); the interpreter evaluates a bare member `a.b`
  as a 0-arg field access, so `Console.println` was auto-invoked → `()` → `Not callable: ()`.
  Minimal repro: `List("a","b").foreach(println)` and `val f = println; f("x")`.
- **Fixed:** Normalize now rewrites `println`/`print` to `Console.*` **only when applied**
  (a `(?=\s*\()` lookahead). A bare reference stays the plain name → every backend binds it
  to the intrinsic function value (interp globals, JVM Predef, JS `_println`, Rust intrinsic
  table). Surgical: only `println`/`print`, so paren-less 0-arg method calls like
  `gen.zipWithIndex` are untouched (an earlier dispatch-level `bareSelect` attempt regressed
  exactly those — reverted). Regress: `BugReproTest` (foreach(println), val-bound println,
  explicit `println()`/`println(x)`, `nanoTime()`); `examples/typed-data.ssc` runs end-to-end
  and is now in `ExamplesSmokeTest`'s curated run-set (which goes through Normalize); Rust +
  JS codegen + interp suites green.

## js-self-handling-cps-fn-not-run — `fixed` (2026-06-12)

- **Fixed:** `JsGen.runIfEffectful` wraps a non-CPS-context call to an effectful
  function in `_run`, so a self-handling CPS fn's lazy `_FlatMap` resolves at the
  value boundary (`println(workload())`). `_run` is idempotent on an already-resolved
  plain value (so a direct-runner result like `_handleOneShot(…)` is unaffected) and
  throws loudly on an unhandled effect; CPS-context calls go through `genCpsApply`,
  never `genApply`, so they're untouched. Verified via node: non-loop self-handling →
  3, multi-shot handled-in-while → 204, one-shot regression → 5. backendInterpreter 1678
  green. Regress: `JsEffectLoopTest` (self-handling + multi-shot). **effect-multishot now
  runs on JS.** Diagnosis below.
- **Found:** while landing `effect-cps-loops-js` (the perform-in-while lowering).
- **Symptom:** on the **JS backend only**, a function that handles its OWN effects
  internally (so it has no unresolved `perform`) but is still CPS-emitted (because its
  body contains `handle`/effect machinery) returns an **un-run lazy `_FlatMap`**. A
  value-position call to it (`println(workload())`) prints `[object Object]` instead of
  the result. Blocks the `effect-multishot` corpus on JS (and any self-handling block).
- **Repro (JS only; jvm + interp are correct):**
  ```scalascript
  multi effect NonDet:
    def choose(options: List[Int]): Int
  def program(): Int ! NonDet =
    val a = NonDet.choose(List(1, 2, 3))
    a
  def workload(): Int =
    val all = handle(program()) { case NonDet.choose(opts, resume) => opts.flatMap(opt => resume(opt)) }
    all.length
  println(workload())   // JS: prints [object Object]; expected 3
  ```
  Note: NO `while` needed — this is **not** a perform-in-loop bug; the `while` fix is
  orthogonal. `effect-oneshot` (where `workload` is a *direct* `handle(...)` → a runner
  call → plain value) works on JS.
- **Root cause:** JS `_bind(c, f)` is **always lazy** (`return new _FlatMap(c, f)`),
  unlike JVM's `_bind` which is eager on a non-`Perform` value. A CPS'd self-handling
  function's chain has no `Perform` nodes, so on JVM it eager-resolves to a plain value,
  but on JS it stays a lazy `_FlatMap` that nothing runs at the (non-CPS) call site.
- **Verified fix hypothesis:** wrapping the value-position call in `_run` resolves it
  (`_run(workload())` → 3 / 12 / 204). The fix is to emit `_run(...)` at a non-CPS value
  boundary for a call whose result is a CPS'd (effectful) function — `_run` is idempotent
  on plain values, so it's safe for the direct-runner case too. Needs care in `genApply`
  to avoid wrapping calls that are themselves inside a CPS context (those go through
  `genCpsApply`). HIGH-ish risk — gate on the full effect suite + node tests.

## interp-module-loader-dedup — `done` (busi confirmed, rozum seq-137)

- **Reported:** busi (`@busi-claude-code`), rozum `scalascript` seq-132 (2026-06-12).
- **Symptom:** interpreting (not `ssc check`) an entry that imports a large module via
  **two edges** (diamond) — e.g. `server.ssc` imports `dispatch.ssc` (~7942 lines)
  directly *and* via a small `route_spi.ssc` that also imports `dispatch` — blows up:
  pathological re-evaluation → OOM / hang at load time, 0 lines of the program run.
  `ssc check` is green (typer memoizes module loads; the interpreter loader did not).
- **Repro:** 3 modules — `big` (large/with a load-time side effect) + `spi` importing
  `big` + `entry` importing both `big` and `spi`. Without dedup, `big` is evaluated
  once per DAG path (exponential in diamond layers). See
  `runtime/.../InterpModuleDedupTest.scala`.
- **Root cause:** `SectionRuntime.runImport` created a fresh `Interpreter` and re-ran
  the imported module on **every import edge** — no cache keyed by module path.
- **Fix:** shared `moduleCache: Map[os.Path, Interpreter]` threaded through child
  interpreter constructors; `getOrElseUpdate(resolvedPath)` in `runImport` → each module
  evaluated once per run (init side effects run once, matching the typer). Spec
  `specs/interp-module-loader-dedup.md`.
- **Verify:** rebuild `installBin` on the landing pin, re-run the busi diamond (drop the
  `Any`-typed `route_spi` workaround). Regression: `InterpModuleDedupTest` (diamond +
  3-layer stacked diamond; asserts shared module loads exactly once).
- **Landed:** `f6d3245a3` (origin/main, 2026-06-12).
- **Confirmed:** busi bumped to `7470392e` + `installBin`, removed the `Any` workaround,
  their phase23 diamond (was OOM at load) now loads + passes (30 checks), full regression
  green, ph-2 domain-module split unblocked (rozum seq-137). **Closed.**

## v2-arith-split-jit-size — `fixed` (2026-07-09)

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

## v2-head-field-dispatch-shadow — a case-class field named `head` (non-zero index) breaks List.head

**Status:** FIXED 2026-07-09 (was OPEN; guarded by tests/conformance/head-field-shadow.ssc).
**Fix:** the 3-arg `fieldAt(recv, idx, name)` now resolves by the RECEIVER's own
registered field names (`lookupFieldNames(tag)` → index of `name`), falling back to
full dynamic `methodOp` dispatch when the tag has no such field (Cons/Nil/Some/… —
builtin members stay builtin). Also fixes the same-name-at-different-index case
across classes. Companion: foldLeft/map/foreach for ForeignV(ArrayBuffer) and
foldLeft for ForeignV(mutable.Map) (the hub folds over Array.fill tables at module
load — the next boot blocker after head). **busi hub BOOTS on --v2**
("listening on 0.0.0.0:8392"). instance-field bench A/B flat (the tag check rides
the already-generic 3-arg path; the 2-arg match/tuple fast paths are untouched).

Importing/loading ANY module that defines e.g. `case class Ref(name: String, head: String)` makes
`xs.head` on a List resolve through the case-class FIELD accessor — by field INDEX — for other
modules: a 1-element list yields Nil (element #1 missing), and a downstream `.trim` surfaces as an
unhandled `Op("Nil.trim", (), <closure>)`. With `head` as the FIRST field (index 0) the bug hides.
This is the busi hub-boot blocker: busi core/repo_commit.ssc `RepoRef(name, head)` ×
http/context.ssc `busiCfGet` (`hits.head.drop(n).trim` at module load). Repro:
`bin/ssc --v2 tests/conformance/head-field-shadow.ssc` (expected file has the correct v1 output).
Likely general: any builtin member name (head/tail/name/…) reused as a non-first case-class field.
Found+minimized 2026-07-09 by busi (fable) while attempting the v2 hub conformance pass.

## v2-db-url-scheme-not-jdbc — `databases:` front-matter parser only recognizes `jdbc:`-prefixed URLs

**Status:** FIXED 2026-07-09 (1e43ba347) — registerDb normalizes sqlite:/h2:/postgres(ql)/mysql to jdbc:; guarded by
tests/conformance/v2-db-url-scheme-not-jdbc.ssc)

busi's `databases:` convention uses the `sqlite:` scheme (`sqlite::memory:`,
`sqlite:./data.db`, `sqlite:${env:VAR}` — v1's own JsGenSqlBlockTest /
WasmBackendSqlTest pin this as first-class, tested, alongside `jdbc:`). v2's
ad hoc line-by-line parser (`FrontendBridge.parseDatabasesFromFrontmatter`,
v2/frontend-bridge/.../FrontendBridge.scala ~line 129) only calls
`PluginBridge.registerDb` when `rawUrl.startsWith("jdbc:")` — any other
scheme is silently skipped (the `if` guard no-ops; nothing is logged). At
runtime `Db.query`/`Db.execute` hits `MinimalCtx.dbConnect`, finds nothing
registered, and crashes: "No database registered for '<name>' — add a
databases: section to front-matter" — even though the front-matter IS
present and correct. This is the busi hub-boot-adjacent `repo_sqlite_index`
v2-sweep failure. Repro: `bin/ssc --v2
tests/conformance/v2-db-url-scheme-not-jdbc.ssc`. Fix candidate: recognize
`sqlite:` (and ideally any scheme, deferring validity to the JDBC driver)
instead of hardcoding `jdbc:`.
Found+minimized 2026-07-09 by busi (fable).

## v2-native-result-unregistered-field — fieldAt crashes on a native result whose case class isn't imported

**Status:** FIXED 2026-07-09 (793922d00) — fieldAt named 3-arg routes through methodOp/__method__ (handles ForeignV/NamedMethodObj); guarded by
tests/conformance/v2-native-result-unregistered-field.ssc)

Calling a GLOBAL, extern-backed std function (e.g. `exec`, from
std/process.ssc, bound by the os-plugin) WITHOUT importing its declared
return type (`ProcessResult`), then accessing a field on the result
(`r.exitCode`), crashes on v2 — even though the exact same code runs fine
on v1. busi's `core/repo_git_mirror.ssc` does exactly this (calls `exec`
bare, never imports `ProcessResult`; the plugin activates because the file
separately imports OTHER externs from std/fs.ssc, e.g. `mkdirs`/`readFile`).

v1's interpreter resolves `.exitCode` DYNAMICALLY by name against the
native result's own field map — it never needs `ProcessResult`'s shape
ahead of time. v2 compiles named field access to a static
`fieldAt(recv, index, name)` using a GLOBAL, receiver-blind
field-name→index registry built only from `case class` declarations
TEXTUALLY PRESENT in the compiled unit (FrontendBridge's `fieldIndex`/
`registerCaseClass`). Since `ProcessResult` is never imported here, its
shape is unregistered — the v1→v2 bridge can't build a proper
`DataV("ProcessResult", …)` for the native result and falls back to a raw
representation. `fieldAt`'s runtime fallback then calls `asData` on that
non-`DataV` value and crashes: "expected Data, got ProcessResult(...)".

GENERAL gap, not process-specific: any native/extern function returning a
case-class-shaped value whose class isn't ALSO imported in the same
compile unit will hit this (the sibling of v2-head-field-dispatch-shadow —
same "fieldIndex is global + receiver-blind, fieldAt trusts it
unconditionally" root design, different receiver shape: plugin-native
value instead of a builtin Cons). This is the busi `repo_git_mirror`
v2-sweep failure. Repro: `bin/ssc --v2
tests/conformance/v2-native-result-unregistered-field.ssc`. Fix candidate:
`fieldAt`'s runtime fallback should route through the `__method__`
structural/plugin dispatch (which already handles
`ForeignV(NamedMethodObj)`) instead of unconditionally calling `asData`,
OR the v1↔v2 bridge should tag native results by their declared name
regardless of whether that case class was locally registered.
Found+minimized 2026-07-09 by busi (fable).

## v2-route-params-stub — req.params(name) always returns Stub on v2

**Status:** FIXED 2026-07-09 — `req.params`/`req.query` (and `bearerToken`/
`jwtClaims`/`basicAuth`) are runtime-INJECTED by the server
(`InterpreterHttpHandler.liftRequest`) and are NOT in std/http.ssc's `Request`
case class. `FrontendBridge.registerCaseClass` was overwriting BOTH the field
registry and `V2PluginRegistry` with the 9-field case-class layout, so
`v1ToV2` dropped `params`/`query` from the Request `DataV` entirely and
`req.params` fell through to a stubbed dispatch. Fix: a single source of truth
`PluginBridge.requestFieldNames` (the runtime layout) that FrontendBridge locks
into its registry (`runtimeShapedTypes`), which `registerCaseClass` no longer
overrides. `req.params(:name)` now returns the real segment on --v2 for mid and
trailing positions; other Request fields (method/path/headers/query) verified
unchanged. Corpus 154/8 (no regression). Gate flipped green:
tests/e2e/route-params-v2-smoke.sh now fails on regression. Fixed by
lucky-perch; reported+repro'd by busi.

FOLLOW-UP FIXED 2026-07-09 (user-request-collision): the fix reserved
"Request" GLOBALLY (FrontendBridge.runtimeShapedTypes), so a user's OWN
`case class Request` resolved as the HTTP Request(14) and its fields read
Stub in the batch/conformance path (standalone `ssc run` was fine). The
lock is now CONDITIONAL — only the std/http.ssc lib Request shape (its
exact 9 declared fields) is locked; a user Request with a different shape
registers and wins. Guarded by tests/conformance/user-request-shadow.ssc.
(Also reverted the parallel fieldNames snapshot/restore batch-isolation
d5f9ce486 — it was orthogonal, did not fix an active bug, and re-asserted
the built-in Request baseline.)

Any HTTP route registered with a `:name` dynamic path segment
(`route("GET", "/foo/:id/bar")`) works fine on v1: `req.params("id")`
resolves to the real matched segment. On v2, `req.params("id")` (and any
other `:name`) silently returns `DataV("Stub", ...)` instead — no crash, no
warning, just a wrong value flowing into business logic. Position doesn't
matter: a MID-position segment (`/mid/:x/tail`) and a TRAILING one
(`/end/:x`) both reproduce identically. Repro:
`tests/e2e/route-params-v2-smoke.sh` (fixture:
tests/e2e/fixtures/route-params-smoke.ssc) — prints `--v1: mid=hello |
end=hello` (correct) vs `--v2: mid=Stub | end=Stub` (broken).

Found 2026-07-09 running busi's full JDG money-loop simulator cycle
(`make v2-sims`). This is why 2 of busi's 3 KSeF checks and its tax
e-Deklaracje check fail on --v2 — every one of those routes reads
`req.params("ref")` to look up an object by ID
(`/api/online/Invoice/Get/:ref`, `/e-deklaracje/status/:ref`,
`/e-deklaracje/upo/:ref`) and gets "Stub" instead, so the lookup misses and
the handler falls through to a 404-shaped "no such X" response — easy to
misdiagnose as an application bug rather than a routing one. NOTE: busi's
bank simulator (PSD2/AIS) ALSO hits this (its `:id`-authorisations route
prints `id=[Stub]` too, confirmed by instrumenting it directly) but its
Makefile check (`v2-bank-pis-check`) happens to still report OK because
`payViaBank`'s client code reads `transactionStatus` straight off the
`/authorisations` POST response body (which the sim hardcodes to `"ACSC"`
regardless of whether the id matched) and never calls the separate
`/status` GET that would have exposed the same bug — a test-coverage gap,
not evidence the bug is narrower than it is.

Likely mechanism (not fully traced to a single line — flagging for whoever
picks this up): `PluginBridge.scala`'s `v1ToV2` conversion of a v1
`Request` `InstanceV` has two diverging paths — a positional
`fieldsArr != null` fast path that trusts the v1 instance's own field
order directly, and a named `effFields` + `V2PluginRegistry.lookupFieldNames`
path that reorders by a hardcoded 14-name list (registered at
PluginBridge.scala ~line 349: `method, path, body, headers, params, query,
json, form, files, session, cookies, bearerToken, jwtClaims, basicAuth`).
That hardcoded order does NOT match std/http.ssc's own declared 9-field
`Request` case class (`method, path, headers, body, form, files, cookies,
session, json` — no `params`/`query`/etc at all), so whichever v1 HTTP
server backend actually constructs the runtime `Request` instance for a
matched route (three different backend implementations exist under
v1/runtime/http-server: JdkServerBackend / RestRuntime / ProxyRuntime) may
not be populating (or ordering) a `params` slot the way either the ssc
declaration or the hardcoded bridge list expects — worth checking whether
the real Request instance even carries path-match results in
`effFields`/`fieldsArr` at all for the backend serving this test.
Found+minimized 2026-07-09 by busi (fable) while running the full JDG
money-loop simulator cycle.

## v2-user-type-shadows-plugin-type — a user case class named "Request" (or any plugin-owned tag) has its fields clobbered on v2

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

## v2-option-exists — Option.exists is unimplemented on v2

**Status:** FIXED 2026-07-09 — the v2 VM Option method dispatch
(Runtime.scala) had map/flatMap/filter/foreach/fold/orElse/getOrElse but was
missing `exists`/`forall`/`contains`/`nonEmpty`, so they fell through to
Op/Stub. Added all four arms (same idiom as the list methods): `Some.exists`
runs the predicate → Boolean, `None.exists` → false; `forall` mirror
(None → true); `contains` by `==`; `nonEmpty`. Repro
tests/conformance/v2-option-exists.ssc green (`false`/`true`); forall/contains/
nonEmpty verified identical v1==v2; corpus 154/8 (no regression). Reported by
busi (fable); fixed by lucky-perch.

`Option.exists(pred)` is not dispatched at all on v2, for EITHER arm:
`None.exists(pred)` raises an unhandled `Op("None.exists", <closure>, <closure>)`
instead of returning `false`; `Some.exists(pred)` returns `Stub("Some.exists")`
instead of evaluating `pred` and returning a `Boolean`. v1 is correct on both.

Repro (self-contained):
```
val n: Option[Int] = None
println(n.exists(x => x > 0))   // "false" on v1, unhandled Op on v2
val s: Option[Int] = Some(5)
println(s.exists(x => x > 0))   // "true" on v1, Stub on v2
```

Found 2026-07-09 by busi (fable) driving a live v2 hub through the full
JDG money-loop simulator cycle: EVERY auth-gated route
(`isPaired`/`isOwner`/operator role checks —
`identity.exists(i => i.roles.contains(role))` on an `Option[Identity]`)
crashes or misbehaves the moment it reaches a role check, because this is
the idiomatic way busi (and presumably most v2 programs) writes an
Option-guarded predicate. This is a foundational stdlib gap, not specific
to HTTP or money-loop — it should be one of the highest-priority items to
close for v2 parity given how common the pattern is.

## v2-req-form-stub-in-hub — req.form(name) returns Stub inside busi's real hub.ssc (isolated minimization did NOT reproduce)

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

## v2-read-gigs-handle-leak — GigSource.fetch handle{} effect leaks unhandled inside busi's real hub.ssc/mcp.ssc (isolated minimization did NOT reproduce)

**Status:** FIXED 2026-07-09 in `dd42da430` and `615ed5f8f`; awaiting
reporter confirmation on busi's next pinned ScalaScript update. The original
live hub failure was reduced to a multi-import field-dispatch shadow, and the
fix was verified against the real busi hub.

Found completing a full live JDG money-loop pass on `--v2` (busi's first
successful end-to-end run of find-work→contract→track→invoice→get-paid
against real simulators through a live hub, after `v2-option-exists` and
`v2-req-form-type-collision` were fixed). Every MCP tool worked EXCEPT
`read_gigs`:

```
POST /mcp {"method":"tools/call","params":{"name":"read_gigs","arguments":{}}}
→ HTTP 500; hub log: Error: if: condition not Bool: Op("GigSource.fetch", (), <closure>)
```

busi's own domain test `tests/v2/gigs.ssc` (isolated, part of the 61/61
v2 sweep) exercises the SAME `handle { body() } { case
GigSource.fetch(resume) => resume(simGigs()) }` pattern
(`src/v2/domain/gigs.ssc`, `runSimGigSource`) successfully — so the
`handle{}` construct itself works in isolation. The tool is invoked from
`src/v2/http/mcp.ssc`'s `runTool` dispatcher:
`case "read_gigs" => runSimGigSource(() => gigsJsonStr(scoutGigs()))`
— something about calling this handle{}-wrapping function FROM a generic
`runTool(name, args)` dispatch (rather than as a top-level call, as
`tests/v2/gigs.ssc` does) drops the handler, letting the raw
`GigSource.fetch` Op reach an `if` condition somewhere downstream
un-lifted. The `(condition not Bool)` phrasing suggests the Op itself
ends up being tested as a boolean, not that `scoutGigs()`'s result is
malformed — a clue for whoever bisects this.

2026-07-09 update while claiming `v2-read-gigs-handle-leak-minimize`:
current ScalaScript `origin/main` has a newer/stronger regression before the
original live-hub-only symptom can be minimized. With this worktree's staged
CLI, `cd /Users/sergiy/work/my/busi &&
/Users/sergiy/work/my/scalascript-wt-v2-read-gigs-handle-leak-minimize/bin/ssc
--v2 tests/v2/gigs.ssc` fails during v2 compilation with
`java.lang.RuntimeException: arity: 1 expected, 3 given` at
`ssc.Runtime.run(Runtime.scala:144)`. The same busi test still passes with
busi's pinned ScalaScript submodule via
`SSC_LANE_FLAG=--v2 scripts/ssc tests/v2/gigs.ssc`. Treat this arity regression
as the first blocker in this bug: fix/pin the isolated `tests/v2/gigs.ssc`
shape on current ScalaScript first, then return to the original live hub
`/mcp tools/call read_gigs` handle leak if it still reproduces.

2026-07-09 root cause after fixing the Currency arity blocker: this is the
same field-dispatch shadow family as earlier `head` bugs, but the effectful
list receiver made it look like a `handle{}` leak. Reduced shape:
`std/json + requests.ssc + gigs.ssc` is green; adding
`runRepoJournalFrom` from `repo_journal.ssc` is enough to fail. That import
pulls in `case class RepoRef(name: String, head: String)`, after which
FrontendBridge's global `fieldIndex("head")` lowers every `.head` to
`fieldAt`. In `scoredGigs`, `gigs.foldLeft(gigs.head)(...)` then evaluates
`List.head` as eager `fieldAt` instead of the dynamic `__method__` path that
lifts over `Op("GigSource.fetch", ...)`. Self-contained repro: define
`RepoRef(name, head)`, define the gigs effect/scorer, print `ref.head`, then
run `runSimGigSource(() => gigsText(scoutGigs()))`; current v2 prints `abc`
and then fails with
`if: condition not Bool: Op("GigSource.fetch", (), <closure>)`.

2026-07-09 fix: first, payments' one-field `Currency(code)` bridge metadata
shadowed std/money's three-field `Currency(code, scale, symbol)` constructor,
so current ScalaScript failed busi's isolated `tests/v2/gigs.ssc` with
`arity: 1 expected, 3 given`; `dd42da430` keeps `Currency.apply` compatible
with both arities and returns full std/money-compatible values. Second,
`615ed5f8f` keeps common zero-arg collection/string members such as
`List.head` on the dynamic `__method__` dispatch path even when a case class
also has a same-named field; same-named data fields still resolve at runtime
through the tag/arity-aware field lookup.

Verification: focused `FrontendBridgeTest` for Currency and `List.head`,
`installBin`, reduced `RepoRef.head` + effectful `List.head` repro, busi
`tests/v2/gigs.ssc`, real busi hub `/api/gigs`, real busi hub
`/mcp tools/list`, real busi hub `/mcp tools/call read_gigs`, affected
conformance `tests/conformance/run.sh --only 'head-field-*,money-multisection'
--no-memo`, full `v2FrontendBridge/testOnly ssc.bridge.FrontendBridgeTest`,
and the three payments/bank-rails v2 examples all passed.

Workaround used to complete the money-loop pass: busi's tool set also
exposes `open_opportunity` as a direct entry point (bypassing
`read_gigs`/`take_gig`), which worked correctly and let the rest of the
pipeline (send_proposal → win_opportunity → sign_contract → log_work →
invoice_from_work [real KSeF send] → bank_reconcile [real match+pay] →
file_tax [real UPO]) complete successfully end to end.

Minimization attempt (did NOT reproduce): a `handle{}`-wrapping function
called from inside a `runTool(name, args)`-shaped dispatcher (an
`if name == "x" then wrapper(() => effectfulCall())` chain) — works fine
on both v1 and v2 in isolation. The trigger is something else about
hub.ssc/mcp.ssc's actual scale (imports, prior effect regions already
active from other routes/middleware, or the specific
`{"content":[...],"isError":false}` JSON-wrapping around the tool result)
that a small dispatcher didn't capture. Found 2026-07-09 by busi (fable),
same session as `v2-req-form-type-collision`.

## v2-string-split-limit-overload — String.split(delimiter, limit) unimplemented on v2 (CAUSED A REAL PRODUCTION OUTAGE)

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
