# ScalaScript 2 Swift backend and SwiftUI native toolkit

**Status:** accepted; implementation in progress (2026-07-10)

## Overview

ScalaScript 2 must compile checked CoreIR to a real Swift package and render the
same `std/ui` application as native SwiftUI on macOS and iOS. The production
path is independent of the legacy v1 `Parser -> JvmGen -> scala-cli -> View ->
SwiftUIEmitter` execution chain: selecting an Apple native target must never
silently parse, execute, or lower through v1.

The work is deliberately staged. A domain-only Swift backend is established
and verified on real arithmetic/effect programs before the reactive SwiftUI
state model is introduced. This keeps backend correctness independently
testable while still making dynamic `std/ui` parity—not a hand-written native
shell—the final acceptance contract.

This specification refines `specs/native-platform.md`, `specs/swiftui.md`, and
`specs/ssc-toolkit-v2.md` for the v2 compiler/runtime. Their user-facing
platform and toolkit promises remain applicable; their v1/JvmGen implementation
details are not reused by the v2 route.

## Baseline and problem statement

The assembled 2026-07-10 baseline was reproduced on Swift 6.3.2 and Xcode 26.5:

- `bin/ssc build --v2 --target macos examples/frontend/ios-hello/ios-hello.ssc`
  exits 1 with `Error: --v2 is not a directory`; `BuildCmd` treats the lane flag
  as a positional directory.
- `bin/ssc --v2 build ...` dispatches the v2 runner on a source path named
  `build` and throws `FileNotFoundException`.
- `bin/ssc run --v2 --target macos ...` exits through `RunV2` before native
  target selection, produces no package, and reports success.
- The legacy command without `--v2` reaches JvmGen but the generated Scala
  script fails with 27–29 compile errors, including unresolved bare `View` and
  `EventHandler`. That compatibility bug is owned by the separate
  `swiftui-legacy-real-harness` SPRINT slice and is not the v2 architecture.
- No `v2/` Swift code generator exists. Current v2 JS/Rust/JVM generators also
  do not provide portable Decimal/Money or generated effect-handler semantics.
- Existing SwiftUI tests construct Scala `View` values directly. They do not
  compile a real `.ssc` file through the assembled native command, and the v1
  `forKeyedView` path renders a one-time list snapshot.

The real consumer bar is not a counter demo. The reduced busi native client
uses `vstack`/`hstack`, `showWhen`, dynamic `forKeyed`, component-scoped state,
cards, links, trusted rich content, Money/Decimal domain logic, and algebraic
effects. A backend that passes only `Text`/`Button` string assertions is not
complete.

## Coordination and ownership

The design was reviewed in the `scalascript` Rozum room on 2026-07-10 by
`scalascript-codex` and the busi-side `claude-code` agent. The review established
the backend-first sequence, identified the real JvmGen harness failure, measured
the busi toolkit surface, and split the compatibility fix into an independent
claim. New design questions must be raised in that room before this accepted
contract changes.

## Public interface

### Developer backend commands

The Swift backend follows the Rust backend's inspect/run shape:

```text
ssc emit-swift [--target macos|ios] [-o <dir>] <file.ssc>
ssc run-swift  [--target macos] [-- <args...>] <file.ssc>
```

`emit-swift` performs checked frontend + CoreIR lowering and writes a Swift
package without building it. `run-swift` invokes `swift run` and is supported
for host macOS; iOS execution continues through the simulator/device adapter.
Both commands accept the existing client setting `--server-url <absolute-url>`.
The value is generated into UI-mode target configuration and is the only base
used to resolve relative native HTTP requests; it does not affect domain-mode
programs that never construct NativeUi.

### Application commands

```text
ssc build   --target macos|desktop-macos <file.ssc>
ssc run     --target macos|desktop-macos <file.ssc>
ssc build   --target ios|mobile-ios <file.ssc>
ssc run     --target ios|mobile-ios [--device] <file.ssc>
ssc package --target macos|ios <file.ssc>
ssc publish --target macos|ios <file.ssc>
```

`build`, `run`, `package`, and `publish` accept the same `--server-url` client
setting for Apple UI targets and validate/normalize it once before generation. A
manifest/client build setting may provide the same resolved value before this
backend boundary, but the Swift generator receives one optional value, never a
list of fallbacks.

For these targets the v2 Swift backend is the default. `--v2` is accepted as an
explicit spelling of that default. `--v1` is the only compatibility selection
and stays visibly legacy; it may not be selected implicitly after a v2 error.
Lane flags are parsed as flags by every command and may never enter the source
file/positional list.

Code generation and package writing require Swift. Simulator builds, device
deployment, signing, archive/export, notarization, TestFlight, and App Store
publication retain their current Xcode/fastlane tool requirements and bounded
diagnostics.

## Architecture

```text
.ssc roots + imports
        |
        v
checked v2 Program/CoreIR
        |
        v
PortableTargetLowering
  - canonical Decimal/Money primitives
  - explicit Pure/Op effect representation
  - portable NativeUi values/closures (UI slice)
        |
        v
SwiftBackend -> generated Swift package + Xcode project
  AppCore: runtime + compiled domain program
  App: real Xcode application target + portable NativeUi renderer (UI mode)
        |
        +-> swift run / swift build (macOS)
        +-> xcodebuild / simctl / signing adapters (iOS)
```

The frontend may initially be the explicit compatibility FrontendBridge while
the self-hosted frontend reaches parity, but the backend input is only checked
v2 `Program`/CoreIR. The generator does not import v1 AST types and never calls
the v1 parser, interpreter, JvmGen, Scala CLI, `FrontendFrameworks`, or
`SwiftUIEmitter`.

### Generated package

The deterministic package layout is:

```text
<AppName>/
  Package.swift
  Sources/AppCore/SscRuntime.swift
  Sources/AppCore/GeneratedProgram.swift
  Sources/<AppName>/main.swift               # domain mode only
  Sources/<AppName>Cli/main.swift            # UI-mode debug executable only
  AppleApp/<AppName>App.swift                # UI mode only
  AppleApp/NativeUiStore.swift
  AppleApp/NativeUiRenderer.swift
  AppleApp/NativeUiStyles.swift
  AppleApp/NativeUiHtml.swift
  AppleApp/Resources/...                     # declared UI assets
  <AppName>.xcodeproj/project.pbxproj         # real Apple application target
  Tests/AppCoreTests/...                     # optional emitted debug fixtures
```

`AppCore` has no SwiftUI dependency. A domain-only package can therefore run
under `swift run` on macOS and serve as the first backend gate. In UI mode the
generated Xcode application target compiles the same `AppCore` sources together
with the `AppleApp` SwiftUI sources; the SwiftPM debug executable imports the
`AppCore` library. The frozen mode-specific filenames and products are specified
in the reviewed package section below.

For direct generator tests the backend may expose a single-file rendering of
the same `AppCore` sources, but CLI artifacts use the package layout above.

## CoreIR to Swift contract

The Swift runtime uses a dynamic, target-owned value model parallel to the v2
VM/JS/Rust runtimes. It must preserve the semantics of every structural CoreIR
term before UI work begins:

| CoreIR | Swift runtime contract |
|---|---|
| Unit / Boolean / Int / Float / String / Bytes | exact corresponding `SscValue` case; Int is signed 64-bit |
| BigInt | arbitrary-precision signed value; never truncated to Int64 or Double |
| Ctor | tag plus ordered fields |
| closure | captured environment plus callable arity/body |
| `Let` / `LetRec` | left-to-right binding and mutually visible recursive closures |
| `Match` | ordered tag/arity arms plus the same missing-arm diagnostic |
| cells / maps | reference identity and deterministic key/show behavior |
| tail application | trampoline/bounce; self and mutual deep recursion are constant-stack |
| output | Unit is silent; other values use the same canonical `Show` shape |

Unknown primitives and globals are compile errors naming the operation and
source program. They may not become comments, `nil`, or runtime no-ops.

## Portable Decimal and Money

Decimal is target-independent data, not a JVM `ForeignV` and not a Swift
`Foundation.Decimal` value in CoreIR.

- The portable boundary representation is canonical decimal text: optional
  `-`, at least one integer digit, optional fractional digits, no locale or
  exponent ambiguity in rendered values, and exact scale where the public
  Money contract requires it.
- CoreIR uses named `dec.*` primitives for construction, normalization,
  comparison, arithmetic, rounding, scale, and canonical display. Adding a
  `Const.CDecimal` host-specific literal is rejected; `Prim` already provides a
  backend-neutral extension point.
- `Currency` and `Money` remain ordinary tagged constructors. Arithmetic routes
  through portable decimal primitives and enforces currency agreement exactly
  as the VM/interpreter contract does.
- Swift may implement the primitives with Foundation `Decimal` plus explicit
  locale/rounding normalization. The same observable cases must also be
  implementable by JS, Rust, and JVM generators from the portable contract.
- Invalid decimal text, division by zero, incompatible currencies, and
  unsupported rounding modes produce deterministic errors rather than host
  exceptions with platform-specific text.

The runtime value is `DecimalV(canonicalDisplayText)`. It is a target-neutral
value case, not a CoreIR literal: construction still enters through `Prim`, so
serialized CoreIR contains only portable strings and operations. The display
text preserves scale (`1.00` stays `1.00`), while equality, ordering, and hash
identity use the normalized numeric value (`1.0` equals and hashes like `1.00`).
This distinction is required for Decimal map keys as well as arithmetic.

The first frozen primitive vocabulary is:

| Primitive | Arguments | Result / contract |
|---|---|---|
| `dec.parse` | canonicalizable string | `DecimalV`, preserving explicit fractional scale |
| `dec.from-unscaled` | `BigInt`, scale | exact `DecimalV(unscaled × 10^-scale)` |
| `dec.add`, `dec.sub`, `dec.mul`, `dec.rem` | two Decimal-compatible values | exact `DecimalV` |
| `dec.div` | lhs, rhs, result scale, rounding mode | rounded exact quotient; zero divisor is a bounded error |
| `dec.compare` | two Decimal-compatible values | `-1`, `0`, or `1`, ignoring scale |
| `dec.set-scale` | value, scale, rounding mode | scale-preserving rounded `DecimalV` |
| `dec.pow` | value, non-negative integral exponent | exact `DecimalV` |
| `dec.abs`, `dec.negate`, `dec.signum` | value | exact unary result |
| `dec.scale`, `dec.unscaled` | value | scale `Int` / exact unscaled `BigInt` |
| `dec.to-bigint`, `dec.to-string` | value | truncating `BigInt` / canonical display text |

The public `Decimal`, `BigDecimal`, conversion, arithmetic, comparison, and
method surfaces lower or dispatch to this vocabulary. Constructing Decimal
from binary floating point is rejected as inexact. CoreIR `__arith__` remains a
dynamic compatibility operation, but its Decimal arms delegate to the same
`dec.*` contract; a target must not implement a second set of semantics.

## Portable algebraic effects

Generated Swift must not copy the current JVM-only `ThreadLocal` handler stack.
Before target code generation, effect operations and handlers lower to an
explicit target-independent computation representation:

```text
Pure(value)
Op(label, argument, continuation)
```

Handler application is a CoreIR/runtime loop over these values. `resume` invokes
the captured continuation, and each multi-shot resume evaluates an independent
branch. Nested handlers preserve nearest-handler selection and restore outer
state after success or failure. Unhandled operations remain explicit `Op`
values until the program boundary, where they produce the standard bounded
diagnostic.

The lowering pass is shared infrastructure. Swift is its first compiled target
consumer; VM parity tests protect existing semantics and later JS/Rust/JVM
adoption. A Swift-only handler shortcut is forbidden even if it makes a smoke
test pass.

The v2 kernel and Mira library already encode user computations as ordinary
`Pure`/`Op` data with reusable closures. The portable slice extracts one named
runtime contract for operation construction, argument packing, continuation
threading, and the handler loop, then makes bridge and generated targets use it.
`V2EffectContext` remains only the in-process JVM compatibility adapter for
legacy plugin `BlockForm` runners; it is not part of compiled Swift semantics
and arbitrary JVM-only BlockForms are not promised on Apple targets.

## CLI and packaging boundary

Generation is separated from Apple orchestration:

1. Resolve roots/imports and obtain checked CoreIR.
2. Apply portable target lowering.
3. Generate/write the deterministic Swift package and, in UI mode, Xcode
   application project.
4. Optionally invoke `swift build` or `xcodebuild`.
5. Reuse the existing launch/sign/archive/notarize/publish adapters against the
   generated package/Xcode application product.

Steps 1–3 are unit/e2e testable without a simulator or signing identity. Missing
Swift/Xcode/SDK/device/signing tools fail only at the first step that needs them
and name the corrective command. A failed v2 generation never retries v1.

## Portable NativeUi ABI

The UI ABI is produced by `std/ui` primitive lowering and survives CoreIR to
Swift without JVM objects. Its frozen design is a separate spec-review gate
before UI implementation because dynamic identity is a new runtime contract.
At minimum it must represent:

- mutable/derived signals with stable string ids and subscription/change
  notifications;
- text, signal text, element/layout, fragment, conditional, keyed collection,
  component instance, and trusted-rich-content nodes;
- attributes/styles/accessibility metadata without flattening them into
  unparseable host objects;
- declarative event/fetch/navigation/form/table actions plus callable render
  closures where the language contract requires them;
- explicit unsupported nodes carrying a source-located diagnostic.

The ABI is not `scalascript.frontend.View`, `PluginValue.Foreign`, or v2
`ForeignV`. Those shapes tie artifacts to JVM process memory and cannot be
compiled into an Apple application.

## SwiftUI state and reconciliation requirements

The reviewed contract below settles the generated value families; these
required behaviors remain the acceptance summary:

- signal reads used by a rendered subtree subscribe that subtree; writes occur
  on the main actor and invalidate the correct view;
- `forKeyed(items, key)(render)` maps to real key-based SwiftUI identity. Insert,
  move, delete, and value update preserve the state of surviving component keys;
  it may not snapshot the list at generation time;
- `component`/`ctxSignal` ids scope state per `(component kind, key)` and release
  it when the keyed instance permanently unmounts;
- async fetch actions expose loading/error/done state, cancel obsolete work on
  unmount or input change, and update signals on the main actor;
- `cardWithHeader` and styled/theme tokens retain surface/padding/border/header
  semantics rather than flattening to `Fragment`;
- route links and display anchors retain navigation/href/tap semantics;
- trusted `rawHtml` renders through a bounded iOS/macOS native adapter (or an
  explicitly specified safe rich-text subset); it may not silently disappear;
- platform types never appear in `.ssc` source.

## Frozen NativeUi ABI and SwiftUI lifecycle (reviewed 2026-07-11)

This section closes the dedicated reactive-spec gate. It is normative for the
portable runtime and Apple renderer; implementation must not substitute v1
objects merely because they expose a similar method surface.

### Runtime value boundary

The public `std/ui` API remains unchanged. Its externs lower to portable runtime
constructors with the following shapes. Every field is an ordinary CoreIR value,
list/map, or closure; no field is a `ForeignV`, `PluginValue`, Scala `View`, DOM
node, Foundation/SwiftUI object, or host callback.

```text
NativeUiAbi(version = 1, root, config)
NativeUiRootConfig(operation, outDir, port, extraCss)

NativeUiSignal(
  id: String,
  scope: String,
  kind: String,
  read: () => Value,
  write: Value => Unit,
  metadata: Map[String, Value])

NativeUiText(text)
NativeUiSignalText(signal)
NativeUiShow(condition, whenTrue, whenFalse)
NativeUiFragment(children)
NativeUiElement(siteId, tag, attrs, events, children)
NativeUiForKeyed(siteId, items, keyClosure, renderClosure)
NativeUiTrustedHtml(siteId, source)
NativeUiDataTable(siteId, source, columns, actions, rowKeyPath)
NativeUiUnsupported(feature, sourceRef, detail)
NativeUiSourceRef(file, line, column, operation)
```

`NativeUiAbi(1, root, config)` is mandatory at the generated-program boundary. The
renderer rejects a missing or unknown version before constructing a window.
The v2 UI plugin switches its complete constructor family atomically to version
1; it may not mix the current experimental four-field `NativeUiElement`/
`ForeignV` signal shapes with the frozen ABI.

Portable maps in this graph use the target-owned insertion-ordered runtime map
value (`Value.MapV` on the JVM and `SscValue.map` in Swift), never a tagged-data
imitation or a host collection inside `ForeignV`. The general mutable-map value
keeps reference identity for ordinary runtime `equals`/`hash` behavior on both
targets. NativeUi change suppression uses a separate `portableEquals` operation:
it recursively compares scalar values (including numeric Decimal equality),
constructor fields, lists, and map key/value contents; closure equality is
identity. Pairwise identity tracking makes the comparison cycle-safe. This
separate operation is used for signal defaults/writes and keyed-list unchanged
checks, so mutable map identity does not create target-dependent UI updates.

Every NativeUi constructor recursively canonicalizes incoming lists, maps, and
constructor fields before storing them. The walk preserves closure identity,
uses an identity visited set for cyclic runtime containers, and rejects the
first remaining `ForeignV` with a stable value path such as
`NativeUiElement.attrs["aria-label"]`. Attributes, events, metadata, headers,
and table-row maps additionally require string keys. Transitional external
SQL/HTTP/plugin adapters may accept a host map at their own boundary, but it
must be copied to `MapV` before entering a NativeUi value.

### Root registration and program handoff

Existing `.ssc` UI programs normally finish with `emit(tree, outDir)` or
`serve(tree, port, extraCss)` and therefore return `Unit`; requiring the program
result itself to be a view would reject the shipped source contract. In an
Apple UI context those two externs perform the portable operation
`ui.root(tree, config)`, register a root in the current evaluation context, and
return `Unit`:

- `emit` records operation=`emit` and `outDir`; it does not write an HTML file;
- `serve` records operation=`serve`, `port`, and `extraCss`; it does not bind a
  native server socket;
- `port`/`outDir` remain provenance for diagnostics and other targets, not
  silently repurposed Apple behavior.

`SscGeneratedProgram.makeNativeUiRoot(store)` evaluates the checked program to
completion, then extracts the registered root and returns
`NativeUiAbi(1, root, config)`. No registration fails with `native UI program
did not register a root; call emit(...) or serve(...) exactly once`. A second
registration fails immediately and names both operations/source refs. A failed
evaluation or root registration rolls back every provisional signal/scope/task.

Empty `extraCss` is accepted. The exact `mobileOverrideCss` grammar emitted by
`std/ui/lower.ssc` is decoded to breakpoint-dependent native typography,
spacing, and radius overrides. Any other non-empty `extraCss` produces
`NativeUiUnsupported("root extraCss", sourceRef, detail)`; it is never ignored
or evaluated by WebKit. Domain mode retains the existing file/server behavior
of `emit`/`serve`; only an explicit Apple UI evaluation context captures a root.

`NativeUiSignal` is data plus closures over a target-owned store, not a host
object hidden inside data. The dynamic method surface is fixed as `apply/get`
→ `read()`, `set(value)` → `write(value)`, `update(f)` →
`write(f(read()))`, and `id` → the stable id. A read-only signal still carries
a write closure, but that closure raises `native UI signal '<id>' is read-only`.
Runtime extension dispatch for this surface is tag-qualified
(`NativeUiSignal.apply/get/set/update/id`), snapshot/restored with the plugin
registry, and checked before generic method/effect fallback. Registering
name-only `get`/`set` hooks is forbidden because it would collide with maps,
JSON facades, and effect receivers. Swift mirrors the same tag dispatch through
the AppCore `NativeUiHost` callback.

Signal `kind` is one of `mutable`, `seed`, `computed`, `equality`, `hash`,
`fetch`, `online`, or `persisted`. Metadata is portable tagged data and may
refer to other `NativeUiSignal` values and closures. It never contains a Swift
`Task`, `URLSession`, `UserDefaults`, `NWPathMonitor`, or JVM object. Those live
in the target store keyed by `(scope, id)`.

The store reuses a second registration of the same `(scope, id, kind)` only
when its default value is semantically equal. A different kind or conflicting
default is a deterministic duplicate-signal error. After the owning scope is
disposed, recreating the same id starts from the declared default. Signal ids
retain the existing `ctxClean` sanitization and collision rules.

`siteId` is a hidden stable string assigned by portable UI lowering from the
lexical CoreIR path of the constructor call (definition name plus child-term
path). It is not observable from `.ssc` or derived from a collection index.
Eligibility is captured by the frontend import resolver from the exact
`std/ui` extern definitions before imported sources are flattened; a later pass
must never rewrite an arbitrary same-named user function. After effect Op-ANF,
a pure `NativeUiSites` pass rewrites eligible calls to reserved versioned
internal globals under `__ssc_nativeui_v1.*`. User definitions in that prefix,
bare/eta-expanded references to a site-bearing primitive, and unexpected
arities are deterministic compile errors. The unhashed id contains definition
ordinal/name plus the CoreIR child-index path so snapshots remain explainable
and stable across executions. The import resolver also captures file/range
provenance before flattening; hidden internal constructor arguments register a
`NativeUiSourceRef` for the site in the target store without changing the
public node field shapes above.

Runtime instance identity is the structural path
`(rootId, ownerPath, siteId, occurrence)`: `ownerPath` contains every enclosing
`(forSiteId, key)` and component scope, while `occurrence` is the zero-based
count of repeated evaluation of the same site under that exact owner. Each
root/keyed render transaction resets its occurrence counters, so re-executing a
render closure recreates the same structural ids rather than consuming a global
monotonic id. Unkeyed repeated siblings are intentionally position-identified;
stateful moves require `forKeyed`.

Unsupported UI data must carry `NativeUiSourceRef(file, line, column,
operation)` when source position is available; otherwise it carries the entry
file and operation. A host stack trace is not an acceptable substitute.

### Events, fetches, and tables

Event values use tagged portable data:

```text
NativeUiEvent(kind, target, payload, metadata)
NativeUiFetchRequest(method, urlSource, bodySource, headersSource)
NativeUiFetchAction(siteId, request, onSuccess, captureTarget, status)
NativeUiSuccessEffect(kind, target, payload)
NativeUiFormBody(fields)

NativeUiSignalMetaSeed(source)
NativeUiSignalMetaComputed(compute)
NativeUiSignalMetaEquality(source, expected)
NativeUiSignalMetaHash()
NativeUiSignalMetaFetch(urlSource, refresh, headers, phase, error)
NativeUiSignalMetaOnline()
NativeUiSignalMetaPersisted(storageKey)
```

Event `kind` covers `set`, `input`, `toggle`, `increment`, `navigate`, and
`openUrl`. URL/body/header sources are either literal portable values, signal
references, or the existing `formBody` descriptor; they are resolved at click
time. Success effects cover `bumpTick`, `setSignal`, `navigate`, and `openJson`
and run in list order only after a 2xx result. Non-2xx, decoding, cancellation,
and transport failures do not run success effects.

The frozen public-to-ABI mapping is complete, not just the initial plugin
subset:

| Public primitive family | ABI behavior |
|---|---|
| `signal`, `seedSignal`, `computedSignal`, `eqSignal`, `hashSignal` | the matching signal kind/metadata above |
| `emptyHeaders` | one root-scoped read-only string signal with id `__empty_headers__` |
| `fetchUrlSignal`, `fetchUrlSignalTo` | fetch metadata with literal or signal URL source |
| `setSignal`, `inputChange`, `toggleSignal`, `incSignal` | `NativeUiEvent` kinds `set`, `input`, `toggle`, `increment` |
| `fetchAction`, `fetchActionTo`, `fetchActionClear`, `fetchCaptureAction`, `fetchActionWith` | one request/action family; clear/capture are explicit metadata, never inferred from ids |
| `formBody`, `onBumpTick`, `onSetSignal`, `onNavigate`, `onOpenJson` | form descriptor plus ordered success effects |
| `localStorageGet/Set/Remove` | portable `ui.storage.get/set/remove` operations owned by the target store |
| `onlineSignal`, `persistedSignal` | `online`/`persisted` signal metadata |
| `component` | portable `ui.componentScope(scopeId, bodyThunk)` operation |

Every defaulted arity in `primitives.ssc` resolves before ABI construction.
Literal and dynamic URL variants remain distinguishable, as do clear, capture,
and structured-success actions. A backend that supports the short legacy arity
but drops one of these descriptors does not implement ABI version 1.

On a successful action, capture writes the response body first, clear resets
the submitted body signal second, and the declared success effects then run in
source order. The legacy single refresh tick is encoded as the final
`bumpTick` effect. No capture/clear/tick mutation occurs on failure.

A fetch owns portable phase/value/error signals. Phase is exactly `idle`,
`loading`, `done`, or `error`; cancellation is a task transition, not a fifth
user-visible phase. The value signal retains the public response-body behavior
and the error signal contains a bounded message. The
returned public `Signal[String]` is the value reference, with phase/error refs
in its metadata so the renderer and tests can observe the full state without a
new Apple-only source API. Header signals contain a JSON object of string
values; malformed JSON or non-string values are deterministic request errors.

Table values preserve the existing descriptor families rather than flattening
to pre-rendered text. The public surface is target-independent and gains only
backward-compatible defaults:

```scalascript
dataTableView(source, columns, actions, rowKeyPath = "id")
staticDataTable(rows, columns, actions = [], rowKeyPath = "id")
signalDataTable(signal, columns, actions = [], rowKeyPath = "id")
remoteTable(fetch, columns, rowsPath = "", actions = [], rowKeyPath = "id")
dataTable(signal, columns, actions = [], rowKeyPath = "id")
rowPostAction(label, method, url, payload: Any, tick, headers = emptyHeaders)
rowPost(label, method, url, payload: Any, tick, headers = emptyHeaders)
```

`DataTableNode` stores `rowKeyPath` so lowering cannot lose the selection.
Passing the historical String `bodyField` to `rowPostAction`/`rowPost` remains
equivalent to `fieldPayload(bodyField)`; no existing call site changes meaning.

The ABI descriptors are:

```text
NativeUiTableSource(kind = static|signal|fetch, value, rowsPath)
NativeUiColumn(kind = text|date|money|status|link|stacked,
               title, fieldPath, align, options)
NativeUiRowAction(kind = delete|post|link|edit,
                  label, request, payload, refresh, options)
NativeUiRowPayload(kind = field|wholeRow|fields, names)
```

Rows remain portable `Map[String, Value]` values. macOS and iOS use one shared
`Grid`/`LazyVStack` implementation and one strict descriptor decoder; platform
conditionals may change spacing only. There is one dotted-path walker for row
keys, columns, templates, payloads, and edits. A malformed descriptor or row is
a source-located table error, never a silently dropped field, row, or action.

#### Table row identity

`rowKeyPath` is normalized to `"id"` only when the caller supplies an empty
string or uses the default. Otherwise the explicit non-empty dotted path wins.
The resolved key must be a String or an integral scalar (`Int` or exact
integral `BigInt`). Identity is the pair `(portable scalar type, canonical
value)`, so `Int(1)` and String `"1"` are distinct. An empty String is invalid.
Missing, null, empty-String, non-scalar, or duplicate keys reject the complete
candidate row set with a source-located
error and retain the last valid rows. Collection indices, action ids, display
fields, hashes, and object identity are forbidden fallbacks.
Row-local action state is keyed by the stable row identity plus the action's
list index; edit state is keyed by the stable row identity plus column index.
Neither slot participates in row identity.

#### Table sources and states

- `static` resolves its row list immediately. `signal` subscribes through the
  exact signal capability token and reconciles on semantic changes.
- `fetch` reuses the existing fetch signal/value/phase/error cells and their
  ownership generation; a table never starts a second request stack. The
  response body is parsed as JSON and drilled by `rowsPath`. A non-empty path
  first tries that explicit dotted path; if it is missing or not an array, the
  root's built-in envelope keys are tried in exact order `data`, `rows`,
  `items`, `results`. An empty path accepts a root array before trying the same
  ordered envelope fallback. Failure of every candidate is one sourced error.
- The fixed visible copy is `Loading…`, `No rows`, and
  `Error: <bounded message>`. Loading/error retains and displays the last valid
  rows when one exists; otherwise it displays the status alone. An idle/done
  source with zero rows displays `No rows`.
- A non-array result, non-object row, invalid key, malformed field/options, or
  JSON parse/drill failure enters sourced `Error` without partially committing
  a new row set.

#### Column semantics

- Every display field uses the same dotted walker. Missing or Unit renders an
  empty field; a present compound value is a sourced row error. String is
  verbatim, Int/BigInt decimal, Bool lowercase, and Decimal uses canonical
  display text.
- Date accepts RFC3339/ISO-8601 date-time input or exact `yyyy-MM-dd`. Empty
  format uses a medium date with no time; `short`, `medium`, `long`, and `full`
  select the corresponding date
  style with no time; every other non-empty string is a Unicode-TR35 pattern.
  Formatting uses injectable `Locale.current` and `TimeZone.current` defaults.
  Parse failure renders the original scalar text.
- Money accepts Int, BigInt, Decimal, finite Float, or a String parseable as a
  locale-independent decimal. It uses the non-empty ISO-4217 currency (default
  `USD`) and the declared locale identifier, or injectable `Locale.current`
  when locale is empty. An unparseable String renders its original text; an
  invalid currency/locale or unsupported present value is a sourced error.
- Status requires scalar text. Unit/missing renders empty. `colorMap` is Unit
  or a String-to-String map; keys match status text exactly. An unmapped status
  renders neutral text with no badge background. A mapped color must use the
  shared native color grammar: `black|white|red|green|blue|gray|grey`,
  `transparent|none`, 3/6-digit hex, or bounded `rgba(r,g,b,a)`; malformed maps
  or colors are sourced errors. RGB components are decimal `0...255` and alpha
  is decimal `0...1`.
- Stacked requires exactly one non-empty dotted `subFieldPath` and renders the
  main value plus that one subordinate scalar line. A missing, Unit, or empty
  subordinate value omits the second line and renders the plain one-line cell;
  lists of subpaths are not accepted. Alignment accepts exactly empty,
  `start`, `left`, `center`, `end`, or `right`: empty/`start`/`left` normalize
  to leading and `end`/`right` to trailing. Every other value is a sourced
  descriptor error on both Apple platforms.
- A link column with an empty template treats the resolved field as the target.
  A non-empty template replaces every `:value` occurrence with the field text
  percent-encoded as one URL component. The final target uses the shared safe
  external-URL policy: absolute `http`/`https` requires a host and `mailto`
  requires a meaningful recipient; all other schemes and malformed targets
  are non-tappable and produce a sourced table error before handoff.

#### Row requests, payloads, and edits

Row request URL substitution happens before base resolution. Every `/:field`
token resolves the named dotted row path to a scalar and percent-encodes it as
exactly one path segment. The token grammar is exactly
`/:([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*)(?=/|\?|#|$)`;
pre-scan rejects the complete request if any `/:` occurrence is not one exact
token, rather than accepting a valid prefix. String (including empty), Int,
BigInt, and Bool resolve to their canonical text; Decimal, Float, Unit,
missing/null, and compound values fail before transport. `rowLinkAction` uses
the same four-type canonicalization for its local signal write and starts no
transport.
After substitution an absolute `http`/`https` URL with a host is unchanged. A
root-relative or relative URL is resolved by Foundation against the sole
generated `--server-url`, which itself must be absolute `http`/`https` with a
host and must not contain credentials, query, or fragment. Generation forces a
trailing slash on the base path so it always has directory semantics: `/x`
resolves from the origin root, while `x` resolves beneath that normalized base
directory using Foundation URL semantics. Scheme-relative targets,
credentials, fragments, a relative request without a configured base, and
localhost inference are rejected.

Payload descriptors are decoded exactly:

- `Field(name)` requires one non-empty dotted name and sends that resolved
  scalar as canonical UTF-8 text: String verbatim, Int/BigInt decimal, and Bool
  as `true`/`false`; Unit and compound values are rejected;
- `WholeRow` sends the complete row as JSON;
- `Fields(names)` requires a non-empty list of unique non-empty dotted names
  and sends JSON whose keys are the exact declared path strings and whose
  values are the resolved values.

Missing paths, non-JSON values, malformed names, headers, URL, or method fail
before transport and cannot bump refresh. Delete preserves its specified
id-field canonical UTF-8 scalar request body. WholeRow, Fields, and edit JSON
requests add `Content-Type: application/json` only when no caller header with a
case-insensitive `Content-Type` name is present. `rowLinkAction` is local
selection: it writes the exact dotted scalar text to its authenticated target
signal and performs no network or external navigation.

Inline edit looks up both id and field through dotted paths but uses the exact
descriptor strings as JSON object keys. Each focus acquisition creates one
monotonic edit revision. Enter or blur atomically consumes that revision before
starting transport; the other callback for the same focus revision is ignored,
irrespective of callback order or request outcome. Re-focus creates a new
revision. Row post/delete/edit execution reuses the existing exact-capability
request runner with per-row/action status,
canonical descriptor signatures, UUID/generation checks, owner cancellation,
2xx-only refresh, and late-completion rejection; a second networking engine is
forbidden. Table/root/key disposal cancels owned work.

### Component scopes and keyed reconciliation

The generic source signature `component[N](kind, key)(body)` stays compatible.
Its implementation enters a portable `ui.componentScope(scopeId, bodyThunk)`
runtime region, invokes the thunk, and returns `N` unchanged. There is no
`ComponentScope` view wrapper that would change the generic result type.

`component.ssc` is updated internally to call that operation with the existing
sanitized `Ctx.prefix`; callers and result types do not change. The target store
maintains a dynamic scope stack while the body thunk creates signals. The exact
keys are:

```text
ownerKey = (rootId, structuralOwnerPath)
scopeKey = (rootId, sanitizedCtxPrefix)
signalKey = (scopeKey, fullSignalId)
taskKey = (ownerKey, lexicalSiteId, occurrence)
```

Outside a keyed renderer the owner is the root and state lives until root
disposal. While evaluating a `NativeUiForKeyed` render closure, the structural
owner path is extended by `(forSiteId, key)`. Every nested component scope,
signal registration, observer dependency, async task, and site occurrence is
recorded in a provisional owner transaction. A committed owner contributes one
reference to each scope it uses; a scope is disposed only when its committed
owner reference count reaches zero. This preserves the existing documented
`kind + key` sharing rule without leaking a removed key's private state.

For every items update the renderer:

1. reads the complete list and evaluates `keyClosure(item)` to a `String`;
2. rejects the first duplicate with `duplicate NativeUiForKeyed key '<key>' at
   site <siteId>`; array-index fallback is forbidden;
3. re-evaluates `renderClosure` for every item on a non-equal items-signal
   emission, while retaining identity `(forInstancePath, key)` and the
   surviving scope store; no host collection/reference equality heuristic is
   used to guess whether an item changed;
4. presents entries in the new list order, so moves preserve state;
5. commits the new owner→scope set, then disposes scopes belonging only to
   deleted keys and cancels their async work.

Scope ownership changes are transactional: an error while rendering the new
value rolls back provisional scopes, subscriptions, tasks, and occurrence
counters and leaves the last committed keyed state intact. Portable semantic
equality at signal write suppresses a truly unchanged list notification;
otherwise every key is refreshed as above. Re-inserting a key after a committed
deletion creates fresh component state. There is no hidden tombstone cache.
Root disposal releases every remaining scope, observer, and task.

### Swift observation and concurrency

The generated App module owns one `@MainActor NativeUiStore`. AppCore still
imports neither SwiftUI nor v1 UI code: it exposes an evaluation entry point
returning the root `SscValue` and talks to the store through a small target-owned
callback interface. The existing domain executable continues to use the
printing `execute` entry point.

For each live `signalKey`, the App module creates exactly one stable
`NativeUiObservableCell: ObservableObject` with `@Published private(set) var
revision: UInt64`. The store retains that cell until scope disposal. A rendered
binding/subtree holds the cell with `@ObservedObject`, acquires one opaque
subscription token on appearance, and releases that exact token on
disappearance. The token, not a view count guess, owns dependency/task
lifetime. A write updates portable storage, and only when semantic value changed
increments that cell's revision. A single global revision or root-wide
`objectWillChange` is forbidden.

Computed/equality signals activate on their first mounted subscriber. Evaluation
records all transitive signal reads in a provisional dependency set, commits
new subscriptions only after success, and releases obsolete dependencies. The
last subscriber releases the dependency set; a later first subscriber computes
again from current values. A dependency cell change recomputes, but publishes
only when the result changed semantically. The evaluation stack rejects direct
or transitive cycles and names the ordered signal ids. Fetch signals use the
same first-subscriber/last-subscriber ownership, so off-screen or deleted
subtrees do not keep requests alive.

Root evaluation, signal writes, keyed reconciliation commits, and UI callbacks
run on the main actor. Network/connectivity/storage callbacks hop to the main
actor before observing or mutating portable values. AppCore closures are never
invoked concurrently on the same machine. Swift 6 strict-concurrency warnings
are build failures for the generated package.

### Async lifecycle

`fetchUrlSignal` starts on first mounted observation and whenever its URL,
refresh tick, or headers change. `fetchAction*` resolves all dynamic request
sources when tapped. A fetch signal is subscribed when a mounted view or active
computed signal holds a subscription token to its value, phase, or error ref.
An action is mounted while the element containing it is mounted. Each
fetch/action `taskKey` owns one generation counter and task; a dependency change
cancels the old task before starting the next. Last-subscriber, element, scope,
or root disposal also cancels it. Completion checks both task identity and
generation before writing, so a late cancelled response cannot overwrite newer
state.

Starting a request atomically sets phase=`loading` and error=`""` while
retaining the previous response value. A 2xx completion writes the full response
body, clears error, sets phase=`done`, and only then runs ordered success
effects. A non-2xx completion retains the previous value and sets phase=`error`
with `HTTP <status>: <body-prefix>`. Transport/decoding failure does the same
with `request failed: <message>`. Error text is truncated to 1024 Unicode
scalars and never contains a host stack. Cancellation caused by replacement is
not published; the replacement remains `loading`. Cancellation with no
replacement returns a still-mounted signal to `idle`, while disposal has no
observable transition because its subscribers are gone.

All phase/value/error transitions are atomic main-actor transactions. Request
bodies and headers are snapshots taken at start. `URLSession` is the Apple
transport; platform security policy errors are surfaced as bounded error state,
not worked around with an insecure fallback. Seed signals subscribe to their
source and copy it only while pristine; the first program or user write marks
them dirty and releases that subscription. Persisted signals use `UserDefaults`,
and the process-wide online signal uses one reference-counted `NWPathMonitor`;
both retain the same public ids and value types on macOS/iOS and stop target
callbacks when their last subscriber/root is disposed.

### Native element and rich-content mapping

The renderer decodes the exact HTML-like vocabulary emitted by
`std/ui/lower.ssc`:

- `div` flex column/row → `VStack`/`HStack`; fragment/display-contents →
  transparent grouping; spacer/divider map to native equivalents;
- `p`, `span`, `pre`, `label`, `strong`, `em`, `code`, `br`, `hr`, and
  `h1`…`h6` map to configured text/group/divider variants;
- `ul`, `ol`, and `li` preserve list order/markers; `img` maps asset/data URLs
  synchronously and network URLs through bounded `AsyncImage` state;
- `input[type=text]`, `input[type=checkbox]`, and `button` map to
  `TextField`, `Toggle`, and `Button` bindings/actions;
- `a` with a route event writes the route signal, while an ordinary anchor
  uses SwiftUI `openURL`; `#/path` also updates the process hash/route signal;
- table roles/data descriptors map to the shared native table/grid renderer;
- `role`, `aria-label`, `aria-disabled`, `required`, `disabled`, and value/
  checked bindings map to SwiftUI accessibility/control modifiers.

The style decoder supports every declaration emitted by the current
`lower.ssc`: flex direction/alignment/gap/grow, display, padding/margins,
width/min-width/max-width/height, foreground/background, border and individual
border sides/colors, radius, font size/family/weight, opacity, text decoration,
white space/overflow/overflow-x, text alignment, modal position/inset/z-index,
shadow, and flex shorthands. It accepts px, `%`, `vw`, unitless numeric,
hex/rgba, `transparent`, `none`, and the exact keywords emitted by
`lower.ssc`/content lowering. `box-sizing`, `border-collapse`, `cursor`, and
`user-select` are recognized native-inert declarations rather than errors;
their browser-only behavior is irrelevant after the corresponding native
layout/control has been selected.

A generated inventory test extracts tags, attributes, declarations, and value
forms from the shipped `std/ui/lower.ssc` plus content lowering. Every entry
must map to a native behavior or the explicit native-inert allowlist. Only an
unknown direct `element` tag, semantic attribute, declaration, or value becomes
`NativeUiUnsupported`; silently discarding it is forbidden. Adding toolkit
output requires updating the Apple decoder/inventory in the same change.

`rawHtml` retains the accepted trusted-markup contract; it is not redefined as
safe rich text. Both Apple platforms use a dynamically sized isolated
`WKWebView` with a non-persistent data store. Content JavaScript is disabled,
a compiled content rule blocks every subresource network request, navigation
outside the loaded document is cancelled, and tapped `http`/`https`/`mailto`
links are handed to SwiftUI `openURL`. Inline markup/CSS and `data:` resources
remain visible, preserving arbitrary trusted fragments including the existing
strong/`data-x` regression. No cookie/cache state is shared with the app.
Callers remain responsible for sanitizing user-controlled input before
`rawHtml`, exactly as on the browser path. A future safe `richText` API is a
separate additive contract; it cannot silently narrow `rawHtml`.

The version-1 `element` builder canonicalizes the exact lowerer sentinel
`span(style = display:contents, data-ssc-raw-html = source, children = [])` to
`NativeUiTrustedHtml(siteId, source)`. A malformed sentinel (wrong tag,
children, or non-string source) is `NativeUiUnsupported`, not an ordinary span
whose special attribute is ignored.

### Generated package and acceptance

Generation has two explicit modes so a top-level `main.swift` and SwiftUI
`@main App` never share one target:

```text
# domain mode (`emit-swift` / `run-swift`, no NativeUi constructors)
Sources/AppCore/SscRuntime.swift
Sources/AppCore/GeneratedProgram.swift
Sources/<AppName>/main.swift

# UI mode (`build/run/package --target macos|ios` with NativeUi constructors)
Package.swift                              # AppCore library + <AppName>Cli only
Sources/AppCore/SscRuntime.swift
Sources/AppCore/GeneratedProgram.swift
Sources/<AppName>Cli/main.swift
AppleApp/<AppName>App.swift
AppleApp/NativeUiStore.swift
AppleApp/NativeUiRenderer.swift
AppleApp/NativeUiStyles.swift
AppleApp/NativeUiHtml.swift
AppleApp/Resources/...
<AppName>.xcodeproj/project.pbxproj
```

Domain mode retains the current `<AppName>` executable product. UI mode emits
two independently useful artifacts: a SwiftPM package exposing the `AppCore`
library plus `<AppName>Cli` domain/debug executable, and an Xcode project whose
`<AppName>` scheme builds a real application product. Only `AppleApp` contains
an `@main App`; only `<AppName>Cli` contains `main.swift`. To keep one generated
domain implementation without requiring a separately embedded framework, the
Xcode application target compiles `Sources/AppCore/*.swift` directly into its
module together with `AppleApp/*.swift`. `AppleApp` sources therefore reference
AppCore declarations directly and do not `import AppCore`; the SwiftPM CLI does
import the library. `AppCore` itself remains SwiftUI-free.

The generated PBX project contains an application target with product type
`com.apple.product-type.application`, a sources phase for the AppCore and
AppleApp Swift files, and a resources phase for `AppleApp/Resources`. Its shared
scheme selects that application target, never the CLI. Generated settings pin
`SWIFT_VERSION = 6.0`, `GENERATE_INFOPLIST_FILE = YES`, the manifest bundle id,
display name, marketing/build versions, `IPHONEOS_DEPLOYMENT_TARGET = 16.0`,
`MACOSX_DEPLOYMENT_TARGET = 13.0`, and
`SUPPORTED_PLATFORMS = "iphoneos iphonesimulator macosx"`; signing/team flags
remain adapter inputs rather than being persisted as secrets. The generator
selects UI mode when checked CoreIR references ABI-v1 UI constructors (or the
manifest explicitly requests SwiftUI), never by attempting a v1 parse after a
failure. Generated source cleanup is target-scoped so changing modes cannot
leave the old entry point in the new target.

The generator also emits one immutable optional backend base URL into the
Apple target configuration. It is the already-resolved `--server-url`/client
build setting, validated during generation as absolute `http` or `https` with
a host. `NativeUiStore` receives it explicitly; AppCore and the portable
`NativeUiRootConfig` do not read process environment, infer localhost, or own a
second base-URL source.

The iOS adapter invokes `xcodebuild -project <AppName>.xcodeproj -scheme
<AppName>` with an iOS Simulator/device destination, derived-data path, and the
existing signing/team flags. It locates the application target's produced
`.app`, verifies its `Info.plist` bundle id before install or archive, and rejects
a command-line executable even if it compiled. macOS distribution selects the
same application scheme/product before codesign/notarization/DMG;
package/publish never point at `<AppName>Cli`. Simulator, device, IPA,
notarization, TestFlight, and App Store adapters consume that generated app
bundle and retain their existing tool/credential diagnostics.

This project shape is an acceptance requirement, not packaging preference. On
2026-07-11 the legacy generated SwiftPM package with an `@main App` and
`.iOS(.v17)` was tested using `xcodebuild` from Xcode 26.5. The generic iOS
Simulator build exited 70 because the matching iOS 26.5 platform was not
installed; eligible destinations exposed by the scheme were macOS, Catalyst,
and DriverKit, so that run did not prove an installable iOS `.app`. Therefore a
plain SwiftPM executable scheme is not accepted as the Apple application
contract. The first implementation gate builds the generated Xcode application
scheme on macOS, inspects the real `.app` product and target type/settings, and
also builds for an iOS Simulator wherever the SDK is installed. A missing local
iOS SDK is a recorded skip only; CI with that SDK must run the simulator build.

The first implementation gate must prove the ABI on the JVM v2 runtime before
SwiftUI rendering: no `ForeignV`, exact tags/fields, mutable/computed dependency
updates, component scope ownership, keyed insert/move/update/delete, duplicate
diagnostics, fetch cancellation generations, and unsupported source refs. The
Apple gates then run the same reduced busi fixture through real SwiftPM
AppCore/CLI tests, a macOS `xcodebuild` of the application scheme, and an
available iOS Simulator `xcodebuild` compile. Snapshot/string-only Swift
assertions are not acceptance.

## Toolkit parity acceptance surface

The final Apple renderer covers the shared toolkit-v2 contract used by a
reduced busi screen:

- layout/content: `vstack`, `hstack`, box, spacer, divider, text, signal text,
  headings, cards, styled/theme tokens;
- controls/reactivity: text fields, toggles, buttons, show/fragment, dynamic
  keyed lists, component-scoped signals;
- data/actions: fetch and typed route actions, forms and validation state,
  model/table display, offline/online status where Apple has equivalent
  semantics;
- navigation/rich content: router/display links and trusted rich content;
- accessibility metadata and deterministic diagnostics for any feature whose
  native semantics are intentionally unavailable.

The same `.ssc` source emits both macOS and iOS application projects. Platform
selection may change deployment metadata and native adapters, not source-level
behavior.

## Behavior

### Specification and regression baseline

- [x] The assembled CLI routing failures and legacy real-harness failure are
  recorded with exact commands/toolchain versions.
- [x] Swift/backend/toolkit ownership and the Rozum decision record are durable.
- [x] The global backend and CLI specifications list Swift/macOS/iOS.

### Portable lowering

- [x] Decimal values and Money arithmetic lower without host `ForeignV` data and
  match VM output on the focused money fixtures.
- [x] Nested, transitive, and multi-shot effects lower to explicit `Pure`/`Op`
  computations and match VM output.
- [x] Unhandled effects produce the same bounded program-boundary diagnostic.

### Swift core backend

- [x] Every structural CoreIR term and core value has executable Swift lowering.
- [x] Deep self and mutual recursion run with bounded stack through a trampoline.
- [x] `fact`, `tco`, and `map` CoreIR fixtures match VM output under real
  `swift run`.
- [x] Existing money/effect `.ssc` fixtures compile through the checked frontend
  and match VM output under real `swift run`.
- [x] Unsupported globals/primitives fail at generation with actionable names.

### CLI/package

- [x] `emit-swift` writes deterministic sources and `run-swift` runs AppCore.
- [x] Apple build/run lane flags are parsed as flags in every supported order.
- [x] Apple targets use v2 by default, `--v1` is explicit, and v2 failures never
  fall back.
- [ ] Package/sign/simulator/device/publish adapters consume the generated
  package and retain bounded missing-tool diagnostics.

### SwiftUI portable runtime

- [x] The NativeUi ABI contains no v1 View/PluginValue/ForeignV instance.
- [x] Signal bindings and event handlers update SwiftUI on the main actor.
- [x] Keyed insert/move/delete preserves surviving component state by key.
- [ ] Toolkit layout/controls/cards/styles/links/rich content retain their
  specified semantics on macOS and iOS.
- [ ] Fetch/form/model/table state is reactive and cancels obsolete work.
- [x] Unsupported native features produce deterministic source diagnostics.
- [x] Native tables decode static/signal/fetch sources transactionally, keep
  stable explicit dotted row identity, and share exact rendering semantics on
  macOS and iOS.
- [x] Field/WholeRow/Fields row payloads, relative URL resolution, column
  formatting, link safety, inline-edit dedupe, 2xx refresh, and cancellation
  match the frozen table contract under strict Swift 6 execution.

### Apple end to end

- [ ] One reduced busi `.ssc` fixture emits for both macOS and iOS.
- [ ] The macOS Xcode application scheme produces a real `.app` and launches a
  smoke fixture.
- [ ] The iOS Xcode application scheme passes an available simulator
  `xcodebuild` compile.
- [ ] Existing focused conformance and SwiftUI compatibility suites stay green.

## Testing strategy

Planned focused gates, refined to exact test names as slices land:

```bash
cd <worktree> && scripts/sbtc "v2Swift/test"
cd <worktree> && tests/e2e/v2-swift-core.sh
cd <worktree> && tests/e2e/v2-swift-cli.sh
cd <worktree> && tests/e2e/v2-swiftui-apple.sh
cd <worktree> && tests/conformance/run.sh --only 'money-*'
cd <worktree> && tests/conformance/run.sh --only 'effect-*'
cd <worktree> && tests/conformance/run.sh --only 'tkv2-*'
cd <worktree> && tests/conformance/run.sh --only 'v2-*'
```

Generator unit tests pin escaping, identifiers, structural lowering, package
metadata, and negative diagnostics. Acceptance always includes executing or
building generated Swift; snapshot/string tests alone are insufficient.

The table slice adds a generated Swift 6 executable probe with a controllable
`URLProtocol`: static/signal/fetch envelopes and failures, explicit identity,
dotted columns, all payload modes, path/base resolution, exact bodies/headers,
link safety, edit dedupe, 2xx refresh, generation cancellation, last-good-row
retention, and disposal execute on macOS. The same Apple table/store/renderer
sources must also type-check for an installed iOS SDK; when no iOS SDK is
available locally the skip is recorded and the CI iOS compile remains required.
The focused ScalaTest cases are named and remain independently runnable:

- `native table ABI decodes five fields and rejects malformed payloads`;
- `native table sources apply rowsPath fallback and retain exact states`;
- `native table columns format dotted values deterministically`;
- `native table row identity rejects missing empty compound and duplicates`;
- `native table actions emit exact request bytes and lifecycle transitions`;
- `native table generated Swift runs on macOS and typechecks for iOS`.

Together they pin the five-field ABI and payload-negative matrix; all source
kinds/fallback/status copy; date/money/status/link/stacked/alignment behavior;
typed key identity; URL substitution/base validation; headers/body bytes;
link/edit/post/delete behavior; generation ownership and cancellation; strict
Swift 6 warnings-as-errors execution on macOS and compile on iOS.

## Implementation order

1. Commit this spec and normative global backend/CLI entries.
2. Land portable Decimal/Money and explicit effect lowering with VM parity.
3. Land the domain-only CoreIR-to-Swift backend and real `swift run` gates.
4. Land CLI generation/build routing and Apple orchestration reuse.
5. Run a dedicated Rozum/spec review for NativeUi identity/state semantics.
6. Land the portable UI ABI and SwiftUI runtime in small behavior-tested slices.
7. Land reduced-busi parity and macOS/iOS end-to-end gates.
8. Verify every behavior item, document results, and release the claim.

## Decisions

- **Backend-first, toolkit second** — real domain semantics can be proved on
  existing money/effect fixtures, while reactive keyed SwiftUI has no prior
  implementation. Rejected: one toy end-to-end UI slice (it would repeat the
  false confidence of direct-View unit tests).
- **Generated AppCore package module** — separates domain execution from UI and
  composes with Swift Package/Xcode tooling. Rejected: a permanent monolithic
  Swift script (poor package/UI boundary).
- **Canonical decimal text plus portable primitives** — keeps CoreIR target
  independent. Rejected: Foundation `Decimal` in IR or JVM ForeignV (host leak).
- **Shared explicit effect lowering** — preserves multi-shot semantics across
  targets. Rejected: Swift-only handler stack or JVM ThreadLocal imitation.
- **Portable NativeUi values** — artifacts contain serializable/compilable
  target-neutral data and closures. Rejected: v1 View/PluginValue/ForeignV reuse.
- **Dynamic keyed reconciliation is its own reviewed contract** — it is a state
  runtime, not a trivial emitter case. Rejected: v1 one-shot list rendering.
- **V2 default with explicit v1 compatibility** — target selection is honest and
  failures are visible. Rejected: transparent v1 fallback.

## Out of scope

- watchOS, tvOS, visionOS, Android/Compose, and non-Apple Swift server targets;
- SwiftUI Preview/HMR;
- obtaining signing identities, App Store credentials, or external device
  access in CI;
- changing the public `std/ui` vocabulary or creating an Apple-only `.ssc` UI
  dialect;
- migrating the complete busi application in this repository (the reduced
  native conformance screen is in scope).

## Results

### Native Apple tables and row actions (`45033e891`, 2026-07-11)

- Generated Apple sources now decode the exact five-field table ABI into one
  shared macOS/iOS Grid renderer and a transactional model for static, signal,
  and fetch sources. Typed dotted row identities, last-good retention,
  deterministic column formatting, sourced bounded diagnostics, and strict
  kind-specific `rowsPath` handling execute in generated Swift.
- Row post/delete/link/edit work authenticates the current canonical
  descriptor, action/edit slot, and committed typed row before launch and
  completion. Ordinary fetch actions and row requests share the same
  generation/token URLSession runner; replacement, deleted-row, unmount, and
  store-deinit cancellation cannot publish stale results.
- `nativeui-reviewer` approved round 3 in the `scalascript` Rozum room after
  two blocker rounds. Independent and local gates both passed the six named
  table tests 6/6 and the full Swift backend 40/40, including generated macOS
  execution and strict Swift 6 typechecking against the installed iOS 16
  Simulator target. Provider 14/14, compatibility fetch 12/12, and Swift CLI
  6/6 also remain green.

### SwiftUI persisted and online Apple ownership (`0ade8bf7c`, 2026-07-11)

- Persisted String signals now load from `UserDefaults` before fallback and
  commit through a Host-owned journal independent of Store-cell materialization.
  Successful root evaluation, direct post-handoff writes, keyed outer commit,
  and disposal preserve committed values; failed root evaluation (including
  same-Host retry), nested/keyed rollback, wrong-type writes, and stale wrappers
  cannot mutate memory or disk.
- `onlineSignal()` is one root/process-scoped signal across component and keyed
  scopes. One `NWPathMonitor` is acquired by the first direct or transitive
  computed/equality owner and cancelled by the last; callbacks hop to the main
  actor and carry an opaque monitor token, so a callback queued before
  cancel/restart is inert and late owners immediately observe current state.
- The executable generated-Swift probe covers no-cell and root journals,
  rollback, type atomicity, scope delete/fresh reinsert, post-deinit closures,
  component singleton identity, computed-only ownership, stale generations,
  and root cleanup under Swift 6 strict concurrency with warnings as errors.
  `nativeui-reviewer` approved the final diff in the `scalascript` Rozum room;
  the full Swift backend passed 31/31 and `tkv2-*` conformance passed 12/12.

### SwiftUI ordinary event mutation hardening (`12fae35e7`, 2026-07-11)

- Set/input/toggle/increment now authenticate and mutate the same current Host
  signal cell. Caller-supplied or stale read/write closures are never executed;
  user writes retain Host rollback, observer publication, and source diagnostics.
- Toggle/increment read through the authenticated cell's `dynamicRead`, so a
  pristine seed observes its latest source before the event write makes it dirty.
  Int64 increment uses checked addition and reports overflow without trapping or
  mutation; live read-only targets are rejected before dispatch.
- `nativeui-reviewer` approved the follow-up in Rozum. The executable gate uses
  Swift 6 strict concurrency with warnings as errors, forged marker closures,
  seed-source changes, read-only targets, and max-value overflow. Swift backend
  passed 30/30 and affected `tkv2-*` conformance 12/12.

### SwiftUI async fetch/action lifecycle (`261dadb6b`, 2026-07-11)

- The main-actor Store now owns URLSession fetch families and action tasks by
  structural owner/site/occurrence plus exact phase/error capabilities.
  First/last observation, dependency changes, action replacement, keyed
  absence/deletion, explicit cancellation, and root disposal invalidate the
  right generation; late or stale callbacks cannot mutate current state.
- Request URL/body/headers and form fields are resolved at the required
  observation/tap boundary. Only 2xx responses run capture, clear, and the
  source-ordered success plan. Error text is Unicode-bounded, response-time
  effect projection is non-trapping, and shared mounts retain loading until
  their last exact capability task ends.
- Action and fetch metadata compare nested signal references by validated
  `(scope,id,kind)`, not regenerated closure identity. Same-key reconstruction
  preserves live work, genuine descriptor changes restart once, and multiple
  same-key registrations in one Host transaction coalesce to the final
  committed descriptor.
- One external URL predicate rejects javascript/data/file/hash and hostless
  navigation/openJson templates before transport; http/https require an
  authority and mailto a non-empty target. HTTP methods/header names use token
  validation and header values reject control characters.
- `nativeui-reviewer` approved the final async slice in the `scalascript` Rozum
  room. Landing gates passed Swift backend 30/30 with real SwiftPM/SwiftUI and
  strict-concurrency URLProtocol execution, plus `tkv2-*` conformance 12/12.
  Native tables, WKWebView, and Xcode application products remain separate
  queued slices.

### SwiftUI observation store and recursive renderer (`70bee065d`, 2026-07-11)

- UI-mode packages now emit a main-actor `NativeUiStore` plus recursive
  renderer/styles/App entry while AppCore remains SwiftUI-free. Stable
  per-signal cells, opaque subscription tokens, dependency recomputation,
  semantic-equal suppression, direct/transitive cycle detection, and one
  publication per changed transaction are executed by real Swift probes.
- Keyed reconciliation crosses `NativeUiSession` into a Host-owned atomic
  owner transaction. Structural node identity preserves the original shared
  render closure, repeated-site occurrence paths, component-scope refcounts,
  move/update state, delete disposal, fresh reinsertion, Store write buffering,
  rollback, and bounded owner metadata without tombstones.
- The core renderer subscribes signal text, Show, keyed items, controls, and
  signal-backed styles. Shipped tags/CSS/accessibility values either map to
  explicit SwiftUI behavior or produce a source-located Unsupported result;
  forged signal/event shapes are rejected recursively and cycle-safely.
  Fetch execution, tables, navigation/hash routes, trusted WKWebView HTML, and
  the Xcode application project remain the explicitly queued next slices.
- `nativeui-reviewer` approved the final uncommitted diff in Rozum after nine
  blocker-driven passes. Final gates: Swift backend 27/27 with real SwiftPM/
  SwiftUI execution, Swift CLI 5/5, JVM NativeUi 14/14, `tkv2-*` 12/12, and
  `std-ui-jobpanel` 1/1.

### Swift AppCore NativeUi host (`9ef73ac81`, 2026-07-11)

- Swift generation now selects UI mode from provenance-qualified ABI globals
  while excluding same-named user definitions. Domain packages remain
  byte-for-byte free of `NativeUiHost.swift`; UI packages expose AppCore plus a
  dedicated `<AppName>Cli` debug product and the checked
  `examples/swift/appcore-nativeui.ssc` source runs through real SwiftPM.
- `NativeUiHost` mirrors the complete JVM ABI-v1 signal, view, event, fetch,
  form, storage/offline, table/column/row-action, trusted-HTML, mobile-CSS, and
  exactly-one-root families. Constructor boundaries reject non-portable target
  values, preserve ordered maps/closure identity, use tag-qualified signal
  dispatch, and retain exact shortened defaults and source-rich diagnostics.
- `makeNativeUiRoot` returns a retained `NativeUiSession`: successful handoff
  keeps the Machine, signal store, and root-local `emptyHeaders` alive until
  disposal. Mutable/computed/key/render closures were invoked after extraction
  by a generated Swift probe, including a short-arity action. Native extension
  failures are catchable and short-circuit every enclosing evaluated subterm;
  abort clears provisional state and the same host then builds a clean root.
- The independent `nativeui-reviewer` approved the final uncommitted diff in
  Rozum after two blocker rounds. Final gates passed: Swift backend 19/19 with
  real package execution; Swift CLI 5/5; JVM NativeUi 14/14; `tkv2-*` 12/12;
  and `std-ui-jobpanel` 1/1.

### Portable NativeUi ABI-v1 JVM gate (`1f3ca3962`, 2026-07-11)

- `UiNativePlugin` now emits the complete frozen ABI-v1 signal, view, event,
  fetch, form, offline/storage, table, column, row-action, and exactly-one-root
  families as `DataV`/`MapV`/`ClosV`; it no longer embeds v1 `View`,
  `PluginValue`, or v2 `ForeignV` values in a NativeUi graph. Every descriptor
  boundary canonicalizes recursively with stable paths and String-key checks.
- `NativeUiPortable` preserves cyclic DataV/MapV graphs and closure identity
  without mutating caller environments. Closure-reachable portable containers
  stay pinned when an unrelated transitional host map is copied, preserving
  shared aliases. NativeUi semantic equality is separate from MapV identity,
  cycle-safe, and transactionally backtracks unordered-map candidates.
- The JVM/static lifecycle gate implements root begin/take/abort rollback,
  component- and occurrence-qualified structural owner paths, duplicate keyed
  diagnostics, move/update retention, shared-scope reference counting,
  deleted-key disposal, render rollback, and bounded identity bindings with no
  component tombstone graph. The root-local `emptyHeaders` signal is registered
  again after each Apple begin.
- Public shortened column arities, the exact trusted-HTML sentinel, seed first
  write detachment, POST/id delete payload, and tag-qualified signal `id` match
  the frozen contract. Legacy interpreter/JS/JVM/Rust backends implement
  `componentScope(_, thunk) = thunk()` exactly once; transitive interpreter
  rebinding requires child plugin provenance plus object identity.
- The independent `nativeui-reviewer` approved the final uncommitted diff in
  the `scalascript` Rozum room after four blocker rounds. Final gates passed:
  NativeUi 14/14; NativeUiSites + FrontendBridge 63/63; frontend intrinsic 6/6;
  JS/JVM/import compatibility 57/57; Rust toolkit 25/25 including a real Cargo
  compile/run; assembled `installBin`; `tkv2-*` conformance 12/12; and
  `std-ui-jobpanel` 1/1.

### Provenance-aware NativeUi lexical sites (`0643fde39`, 2026-07-11)

- `NativeUiSites` is a pure post-Op-ANF CoreIR pass. The FrontendBridge import
  resolver records exact `runtime/std/ui` extern eligibility before flattening,
  source-marker ownership maps imported definitions back to their files, and
  only those direct calls become reserved `__ssc_nativeui_v1.*` globals with
  explainable definition/path site ids plus `NativeUiSourceRef` data.
- Same-named user definitions remain untouched. Reserved-prefix definitions,
  bare/eta-expanded site-bearing primitives, and unexpected arities fail during
  lowering. `emit`/`serve` receive source metadata without adding a public site
  field. Current JVM/static providers accept the hidden arguments through
  versioned compatibility wrappers; the next atomic plugin migration consumes
  them into the frozen ABI rather than dropping them.
- The dedicated pass/provenance suite passes 6/6; combined FrontendBridge tests
  pass 62/62 and the UI plugin baseline passes 4/4. The affected toolkit
  conformance gate passes 12/12 and `std-ui-jobpanel` remains green 1/1 on its
  declared lanes.

### Portable map and tag-qualified runtime seam (`689969978`, 2026-07-11)

- The v2 JVM runtime now has a target-neutral, insertion-ordered `Value.MapV`
  whose identity equality matches Swift `SscMap`. Core map factories,
  primitives, methods, field access, arithmetic copy-on-write, rendering, and
  the v1 compatibility adapter use that value; host-map acceptance remains only
  on transitional external adapter paths.
- Native providers can register tag-qualified apply and method handlers. Those
  handlers participate in ownership checks and registry snapshot/restore/clear,
  so the forthcoming `NativeUiSignal` DataV can remain callable without global
  `get`/`set` collisions.
- Focused gates passed: native plugin SPI 9/9, plugin bridge 30/30,
  FrontendBridge unit tests 56/56, JSON 3/3, HTTP 4/4, and the pre-migration UI
  baseline 3/3. `tests/conformance/run.sh --only 'maps,json-*' --no-memo`
  passed 4/4 on INT/JS/JVM. The broad FrontendBridge suite still hits only the
  pre-existing `v2-frontendbridge-sqlite-timeout` entry recorded in `BUGS.md`;
  its isolated 15-second timeout reproduced unchanged.

### Structural AppCore Swift backend (`68d0b6610`, 2026-07-10)

- `v2/backend/swift` is an sbt-built first-class CoreIR consumer. It writes a
  deterministic SwiftPM package with an `AppCore` library, generated typed
  `SscProgram` data, a target-owned runtime, and a thin executable product;
  neither generated sources nor the Scala generator reference v1 `JvmGen`,
  `View`, or `SwiftUIEmitter`.
- The AppCore evaluator implements every structural `Term` shape, closures,
  cyclic `LetRec` environments, ordered constructor matching, cells, arrays,
  maps, sequences/loops, and a tail-call trampoline. Generation rejects an
  unknown global or primitive with its exact name rather than emitting a no-op.
- `scripts/sbtc "v2SwiftBackend/test"` initially passed 3/3
  generator/negative/toolchain
  tests. The real Swift 6.3.2 toolchain compiled and ran independent packages
  for `v2/conformance/fact.coreir` (`120`), `tco.coreir` at one million calls
  (`500000500000`), and `map.coreir` (`List(2, 4, 6)`). The affected assembled
  smoke `tests/conformance/run.sh --only 'money-portable-v2' --no-memo` also
  remained green 1/1.
- Follow-up `02342d967` added a target-owned arbitrary-precision signed BigInt
  implementation (including exact add/subtract/multiply/divide/remainder and
  comparisons). A real generated package round-tripped a 30-digit value through
  multiplication and division; the Swift backend suite is now 4/4.
- Follow-up `21939ae49` added the complete frozen `dec.*` vocabulary on top of
  that BigInt core. Scale-preserving display, numeric equality/map-key identity,
  arithmetic, division and set-scale rounding modes, powers, unscaled access,
  and dynamic Decimal arithmetic all execute without binary-float or host
  Decimal conversion. A generated SwiftPM package produced
  `(3.50, 0.125, 2.35, true, 12.30, Some(7))`; the suite is now 5/5 and the
  affected `money-portable-v2` conformance smoke remains green 1/1.
- Follow-up `ddcc01156` added `effect.pure`, `effect.perform`, and
  `effect.handle` to the same AppCore machine. Resumption is an ordinary
  reusable Swift closure that folds the next explicit computation through the
  handler; there is no thread-local or captured host stack. A real SwiftPM
  package resumed one `Choose` continuation twice and returned `30`; the suite
  is now 6/6, while the existing imported/multi-argument/transitive/multi-shot
  effect conformance slice remains green 4/4 on all applicable lanes.
- Closure `f20b47b35` added the checked-source seam and closed the Swift-core
  gate. Constructor field layouts discovered by the checked frontend are
  serialized into generated `SscProgram` data, so dynamic field/method access
  is target-owned rather than coupled to Scala reflection or v1 values. The
  trampoline runs both the one-million-call self-TCO fixture and the 100,000-
  step mutual `even`/`odd` fixture with bounded stack.
- The unchanged `money-portable-v2.ssc` source now passes through
  `FrontendBridge.convertSource`, the Swift generator, SwiftPM compilation, and
  a real executable with exact expected output (including `$3.75`, `1.2100`,
  `3.3333`, `1234`, and the scale-preserving allocation list). The unchanged
  `effect-transitive-handler.ssc` follows the same path and prints `6`,
  `sum=6`, and `sum=6!6`, proving operation lifting through collection methods,
  arithmetic, and reusable continuations.
- The Swift backend suite passed 8/8 at core closure and now passes 10/10 after
  platform metadata and executable-argv coverage on Swift 6.3.2. The unchanged
  conformance gates pass 1/1 for `money-portable-v2` and 4/4 for
  `effect-*,effects` on every applicable lane.

### Swift CLI/package routing (`159e45625`, 2026-07-11)

- `emit-swift` and `run-swift` are ServiceLoader-discovered commands over the
  checked FrontendBridge → CoreIR → SwiftBackend path. Generated packages carry
  explicit macOS 13 or iOS 16 deployment metadata; executable argv is read from
  Swift `CommandLine.arguments` through portable `io.args`.
- `build` and `run` consume `--v2`, `--v1`, and Apple target flags in either
  order. macOS and iOS generation default to v2, while `--v1` is the only route
  to `Parser`/`JvmGen`/the compatibility SwiftUI emitter. v2 errors never retry
  that route.
- The domain-only macOS package builds/runs now. Until the separately reviewed
  NativeUi App target lands, iOS launch and Apple package/publish operations
  stop with a bounded `NativeUi application target is not generated yet`
  diagnostic after v2 generation; this is an explicit staged boundary, not a
  claim that simulator/signing adapters are complete.
- Final gates for this slice: Swift backend 10/10; new CLI/registry tests 12/12;
  existing SwiftUI CLI and real legacy fixture tests 27/27; assembled
  `tests/e2e/v2-swift-cli.sh` passes real `bin/ssc` emit/build/run in both flag
  orders plus no-fallback diagnostics; Money/effect conformance remains 1/1 and
  4/4.
- Follow-up `0174796ef` made compiler-internal `global.reg` update the Swift
  machine's dynamic globals instead of rejecting ordinary top-level `val`
  initialization. The user-facing `examples/swift/appcore-money.ssc` now runs
  through real `run-swift` and pins that non-toy source shape.

### Portable Decimal/Money/effects (`ff3a52eba`, 2026-07-10)

- `DecimalV` now carries exact scale-preserving text while numeric equality and
  hashing ignore scale. Public Decimal construction, arithmetic, comparison,
  JSON, SQL, HTTP, UI display, and v1/v2 plugin conversion cross the portable
  value/`dec.*` boundary; no new public result contains a host Decimal
  `ForeignV`.
- `effect.pure`, `effect.perform`, and `effect.handle` share one explicit
  reusable-closure `Pure`/`Op` runtime loop. Nested/transitive handlers and
  multi-shot continuation reuse no longer depend on the JVM compatibility
  handler stack. An unresolved operation reaches the CLI boundary as the
  bounded `unhandled effect` diagnostic.
- The real `std/money.ssc` allocation path exposed a missing dynamic BigInt
  operation family. `BigV`/`BigV` and mixed `BigV`/`IntV` arithmetic and
  comparison now delegate to exact BigInt semantics instead of falling through
  to `UnitV`.
- Final gates on the rebased commit: 94 focused unit tests passed across the v2
  frontend bridge, plugin bridge, native JSON/SQL/HTTP/UI providers, and CLI;
  `scripts/sbtc "...;installBin"` assembled the distribution; and
  `tests/conformance/run.sh --only 'money-*,effect-*,effects' --no-memo`
  passed 6/6 cases on every applicable INT/JS/JVM/V2 lane.
- This slice emits no Swift artifact by design. Swift 6.3.2/Xcode 26.5 remain
  the recorded toolchain for the next CoreIR-to-AppCore execution gate.
