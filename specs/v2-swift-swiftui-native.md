# ScalaScript 2 Swift backend and SwiftUI native toolkit

**Status:** accepted; implementation reopened for standard-pipeline parity
(2026-07-12). The 2026-07-11 Apple closure remains valid for its direct
`emit(fragment(...))` fixture, but did not cover `std/ui/lower.ssc`.

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

Signed adapter flags are explicit:

```text
ssc run --target ios --device [--device-id <udid>] --team-id <team> <file.ssc>
ssc package --target ios --team-id <team> [--export-method <method>] <file.ssc>
ssc package --target macos --distribution --team-id <team>
  [--notary-profile <profile>] [--notary-timeout-seconds <1..3600>]
  [--no-notarize] [--no-dmg] <file.ssc>
ssc publish --target ios|macos --team-id <team> --api-key-path <key.json> ...
```

`SSC_TEAM_ID`, `APP_STORE_CONNECT_API_KEY_PATH`, and
`SSC_NOTARY_KEYCHAIN_PROFILE` are the corresponding noninteractive environment
fallbacks; no v2 signed route reads these values from frontmatter.

macOS signing is tiered by credential requirement. Plain `ssc package --target
macos <file.ssc>` (no `--distribution`, no team id) builds the UI-mode `.app`
and **ad-hoc signs** it (`codesign --sign -`, no Apple identity) then proves it
with `codesign --verify --deep --strict`, so the packaged bundle is signed and
launch-ready on the build host with zero credentials. This is the credential-
free tier: an ad-hoc signature is self-consistent but carries no certificate
authority, so Gatekeeper still rejects it for redistribution. Trusted
distribution stays behind `--distribution --team-id <team>`, which archives and
exports a Developer ID-signed (and optionally notarized) `.app`/DMG. A
domain-only macOS program has no application bundle to sign; its SwiftPM
executable is built unsigned.

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
the captured continuation. Raw `effect.perform` and typed `multi effect` use a
reusable continuation, so each resume evaluates an independent branch. Typed
plain `.ssc effect` uses `effect.perform.oneshot`; its base continuation owns one
lock-protected linearizable claim, and a second/concurrent losing invocation
returns `AlreadyResumed(OperationId)` before executing the suffix. The primitive
receives the effect id and operation name as separate strings; Swift never splits
the legacy dispatch label to reconstruct identity. Direct `.ssc resume` maps the
rejection to `ControlRunFailure(AlreadyResumed(...))`, with stable code
`ONESHOT_VIOLATION`, outside user `try/catch`. Nested handlers preserve the original continuation/gate, nearest-handler
selection, and outer state after success or failure. Unhandled operations remain
explicit `Op` values until the program boundary, where they produce the standard
bounded diagnostic.

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

Anonymous site-derived signals (`computedSignal` and `eqSignal`) use that same
structural identity, not lexical `siteId` alone. Their internal id includes a
kind-separated zero-based occurrence under the current owner, so three calls to
an imported helper such as `localeText` create three independent root signals;
the same call order in a keyed owner recreates the same ids. Explicitly named
`signal`/fetch/persisted ids retain their existing duplicate/conflict rules and
do not receive an occurrence suffix.

For a non-root owner, each anonymous derived signal is stored in an
owner-specific scope and enrolled in `currentOwnerScopes`, so keyed reorder
retains it, deletion disposes it, and sibling/nested owners cannot alias. A
stable derived re-registration may change its newly evaluated default and must
replace the metadata signature and `dynamicRead` closure; this is required for
locale flips and updated JSON rows and must not report the explicit-signal
default conflict or retain a stale captured row. Explicit named, fetch, online,
and persisted signals keep the strict existing-default rule.

Occurrence allocation is transactional: root `begin` and each keyed-owner
render start from zero; keyed snapshot/rollback restores counters and cells; a
failed compute/allocation does not advance the counter; retry and keyed
recreation therefore select the same id. Named-signal construction neither
uses nor increments anonymous counters. Real Swift gates cover failed keyed
render then retry, abort then new begin, sibling/nested owners, key reorder and
delete back to the signal-count baseline, named interleaving, three localeText
calls plus locale flip, and JSON row update without stale closure/default
conflict. Computed and equality counters are kind-separated.

Nested keyed reconciliation is one transaction tree for scope disposal. An
inner successful reconciliation records its owner/scope state but must not run
global unreferenced-scope disposal while an outer render still has provisional
owner scopes. The outermost successful commit first removes obsolete owner
subtrees and then performs exactly one disposal pass. Any failure restores the
outer snapshot, including cells, owner maps, occurrence counters and derived
closures, before disposal is observable.

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
- `select` maps to a menu-style `Picker` two-way bound to its value `Signal`
  (the `NativeUiSelectControl`): the `<option>` children decode into
  `(value, label)` entries and a user pick runs the `change` event
  (`inputChange(signal)`) with the chosen value; a `select` with no signal
  binding renders a real read-only dropdown pinned to the pre-selected option.
  A bare `<option>` outside a `<select>` is a strict, sourced `Unsupported`;
- `a` with a route event writes the route signal, while an ordinary anchor
  uses SwiftUI `openURL`; `#/path` also updates the process hash/route signal;
- table roles/data descriptors map to the shared native table/grid renderer;
- `role`, `aria-label`, `aria-disabled`, `required`, `disabled`, and value/
  checked bindings map to SwiftUI accessibility/control modifiers.

The style decoder supports every declaration emitted by the current
`lower.ssc`: flex direction/wrap/alignment/gap/grow, display, padding/margins,
width/min-width/max-width/height, foreground/background, border and individual
border sides/colors, radius, font size/family/weight, opacity, text decoration,
white space/overflow/overflow-x, text alignment, modal position/inset/z-index,
shadow, and flex shorthands. `flex-wrap:wrap` on a `flex-direction:row` `div`
selects a real wrapping flow layout (`NativeUiFlowLayout`, a custom `Layout`
on the macOS 13/iOS 16 floor the semantic-table `Grid` already requires)
instead of the non-wrapping `HStack` — SwiftUI has no direct `flex-wrap`, so
this is the nearest faithful equivalent. It accepts px and the fill keyword
`100%` on width/height (mapped to `frame(maxWidth/maxHeight: .infinity)`);
other non-px length units (e.g. `vw`, `%` other than `100%`) surface as a
sourced `Unsupported` rather than silently mis-rendering. It accepts unitless
numeric, hex/rgba, `transparent`, `none`, and the exact keywords emitted by
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

Each representable creates a fresh `WKWebViewConfiguration`, fresh
`WKUserContentController`, and per-view `.nonPersistent()`
`WKWebsiteDataStore`. It does not set the deprecated `processPool` property:
on the deployment floors WebKit owns process selection and multiple explicit
pools no longer provide isolation. The normative guarantee is no persistent or
app-shared cookie/cache state plus isolated scripts/handlers, not a dedicated
operating-system web process.

HTML replacement is generation-owned. After the current generation's content
rule compiles and is installed, the coordinator authorizes exactly one
renderer-originated main-frame `about:blank` navigation produced by
`loadHTMLString`. Initial content and every replacement get a new generation;
stale compile, navigation, finish, size, and failure callbacks cannot authorize
or publish. Every other main-frame/subframe, programmatic, redirect, meta, or
user navigation is cancelled. Only a `.linkActivated` absolute `http`,
`https`, or non-empty `mailto` URL may be handed to SwiftUI `openURL`. The
decision uses the same factored external-URL predicate as native actions.
For `target=_blank`, the UI delegate performs that handoff at most once and
returns `nil`; unsafe/relative/hash/data/file/javascript/about targets only
cancel. Programmatic redirects never call `openURL`.

The first HTML load cannot start until rule compilation and installation both
succeed for the latest, still-mounted generation. The rule matches network
schemes only and the subresource types image, style-sheet, script, font, media,
raw, and svg-document; it does not match the document/navigation resource, so
inline markup/CSS, `data:` resources, and external link taps remain available.
Compilation failure loads no HTML and renders a Unicode-bounded sourced
`NativeUiUnsupported`. Executable coverage uses a loopback resource and proves
zero network hits under the installed rule.

Height is clamped to the finite positive range `1...100000` points. iOS
observes `scrollView.contentSize`. macOS has no public `WKWebView.scrollView`
API, so a constant adapter-owned `ResizeObserver` runs at main-frame document
end in `WKContentWorld.defaultClient` and posts generation-tagged numeric
height through a handler in the same isolated world. Page-world
`allowsContentJavaScript` remains false: markup `<script>` cannot run or access
the client world. The client observer is retained in its world, replaced for
each load, and removed with its handler/script on dismantle. All iOS KVO tokens,
both delegates, message handlers, scripts, and callbacks are invalidated or
detached on replacement/dismantle/deinit; no post-dismantle height/error/link
publication is permitted. Tests cover initial/grow/shrink replacement, stale
generation, and teardown.

The version-1 `element` builder canonicalizes the exact lowerer sentinel
`span(style = display:contents, data-ssc-raw-html = source, children = [])` to
`NativeUiTrustedHtml(siteId, source)`. A malformed sentinel (wrong tag,
children, or non-string source) is `NativeUiUnsupported`, not an ordinary span
whose special attribute is ignored.

The Apple renderer accepts `NativeUiTrustedHtml` only at arity two with a
source-ref site and String markup. A forged tag/arity/site/source becomes a
deterministic bounded sourced Unsupported view; the exact Host-side malformed
rawHtml sentinel diagnostic remains unchanged.

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

Cleanup authority is explicit. Each successful write atomically replaces a
sorted `.ssc-swift-generated.json` containing the exact generator-owned file
paths for that tree. Before writing a replacement, the emitter reads the prior
manifest, rejects any absolute path or segment equal to `..`, deletes only
listed files, then prunes only listed parent directories that became empty.
Unlisted files and resources are always preserved, including content below
`AppleApp/Resources`. The new ownership manifest is written last through a
same-directory temporary file plus atomic move, so interruption cannot grant
ownership of a partially written tree. The manifest itself participates in
full-tree determinism. Executable gates cover product-name change,
UI→domain→UI mode switching, stale project/entry-point removal, and preservation
of an unlisted resource.

Application metadata is part of the v2 checked-source result; the CLI obtains
it in the same FrontendBridge conversion that produces `Program` and never
calls the v1 `Parser`, `JvmGen`, or SwiftUI emitter. The app/scheme/product name
uses explicit `--product-name`, otherwise top-level `name`, otherwise the source
file stem, then passes through the existing `SwiftBackend.productName`
normalizer. UI application mode requires a top-level `bundle-id`; it must be a
non-empty reverse-DNS sequence of dot-separated ASCII alphanumeric/hyphen
segments and is rejected rather than rewritten. `display-name` falls back to
top-level `name`, then the normalized product. `version` becomes
`MARKETING_VERSION` and defaults to `1.0.0`; a new top-level `build-version`
becomes `CURRENT_PROJECT_VERSION` and defaults to `1`. Both version values must
be one to three dot-separated non-negative decimal components. Invalid or
missing required metadata fails before file output with a Unicode-bounded
diagnostic naming the key and supplied value.

The PBX project is Xcode-14-compatible (`objectVersion = 56`) and contains one
multi-platform native application target. Every object id is the first 24
uppercase hexadecimal digits of SHA-256 over a unique semantic key; generation
checks collisions and renders sections/items in stable semantic-key order. The
target sets `SWIFT_VERSION = 6.0`, `GENERATE_INFOPLIST_FILE = YES`,
`SUPPORTS_MACCATALYST = NO`, the deployment/platform settings above, and no
team, identity, profile, or credential. Its sources phase includes every sorted
`Sources/AppCore/*.swift`, including `NativeUiHost.swift`, and every sorted
`AppleApp/*.swift`; it excludes `Sources/<AppName>Cli/main.swift` and
`Package.swift`. The resources phase recursively includes sorted files below
`AppleApp/Resources`. UI generation always writes a minimal deterministic
`AppleApp/Resources/Assets.xcassets/Contents.json`, so both the directory and
phase are real. The shared scheme is written to
`<AppName>.xcodeproj/xcshareddata/xcschemes/<AppName>.xcscheme` and references
only the application target and `<AppName>.app` product.

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

The generated-package result exposes two distinct capabilities: `debugCli` for
`run-swift` only, and `XcodeAppArtifact(project, scheme, target, appProduct,
bundleId, displayName, marketingVersion, buildVersion)` for every Apple
build/run/package/publish lane. No generic `executable`/`product` field may be
interpreted differently by callers. Apple lanes invoke `xcodebuild` with
explicit `-project` and `-scheme`, query `-showBuildSettings` for the selected
destination/configuration, and form the output from `TARGET_BUILD_DIR` plus
`FULL_PRODUCT_NAME`; hard-coded `Debug-*` paths are forbidden. Before launch,
install, archive handoff, signing, or publication, the common verifier requires
an existing `.app`, an `Info.plist` with `CFBundlePackageType = APPL` and the
exact expected `CFBundleIdentifier`, and `CFBundleExecutable` different from
the debug CLI product. Product discovery/verification is shared by unsigned
macOS/iOS gates and the signed device/archive/distribution adapters.

Signed device/archive/IPA, macOS codesign/notarization/DMG, TestFlight, and App
Store routing is a named implementation sub-slice after the common artifact
helper. Those adapters keep their existing bounded missing-tool/credential
diagnostics, but generation and product selection use only the v2 artifact;
none may call the legacy generator or infer a product path independently.

### Signed Apple distribution authority

Every signed v2 route constructs exactly one `V2AppleDistributionContext` from
one `SwiftV2Cli.emit` call and requires its `XcodeAppArtifact`. The context owns
the generated root, project, scheme, target, debug-CLI name, bundle identity,
archive path, derived-data path, and fresh export paths. It never calls the v1
`Parser`, `swiftAppName`, `JvmGen`, or `buildSwiftUIPackage`, and it never derives
a product from a configuration directory or filename convention. `PackageCmd`
dispatches an explicit non-v1 Apple `--target` before the current legacy
manifest parse; signed v2 package/publish commands require the target rather
than consulting v1 metadata.

The unsigned build helper is factored into three shared authorities:

1. `queryBuildSettings(context, destination, configuration, signingArgs)` runs
   `xcodebuild -showBuildSettings` with the exact project, scheme, destination,
   configuration, derived-data path, and signing arguments used by its build or
   archive command.
2. `verifyAppBundle(context, appPath, settings, command)` requires a contained
   existing `.app`, `CFBundlePackageType=APPL`, the exact checked bundle id, and
   a `CFBundleExecutable` different from `debugCli`, then requires that
   executable at the platform-correct path.
3. `archiveApplication` runs this exact shape (with the appropriate platform):

   ```text
   xcodebuild archive
     -project <project> -scheme <scheme> -configuration Release
     -destination generic/platform=iOS|macOS
     -archivePath <owned.xcarchive> -derivedDataPath <owned-derived>
     -allowProvisioningUpdates DEVELOPMENT_TEAM=<team>
   ```

   The same provisioning/team arguments enter the settings query. The archived
   app is not guessed: read `ApplicationProperties.ApplicationPath` from the
   xcarchive `Info.plist`, reject an absolute path or any `..` segment, resolve
   it beneath `<archive>/Products`, and run the shared app verifier. A missing,
   malformed, escaping, wrong-bundle, or debug-CLI archive fails before export.

Signed v2 team authority is adapter input only: `--team-id` wins, then
`SSC_TEAM_ID`; an empty/missing value is a bounded preflight error. Frontmatter
and generated PBX files never supply or persist it. `run --target ios --device`
therefore accepts `--team-id`, preserves `--device-id`, performs a signed Debug
build with explicit project/scheme and destination
`platform=iOS,id=<device-id>` when supplied (otherwise `generic/platform=iOS`),
verifies the produced app, then gives that exact bundle to `ios-deploy` with the
selected id and `--debug`/`--justlaunch`. The v2 route may not drop `--device` or
reuse the simulator adapter.

`package --target ios` accepts canonical Xcode 26.5 export methods
`debugging`, `release-testing`, `app-store-connect`, and `enterprise`; legacy
spellings normalize as `development -> debugging`, `ad-hoc -> release-testing`,
and `app-store -> app-store-connect`. Export options set the canonical method,
`destination=export`, `signingStyle=automatic`,
`manageAppVersionAndBuildNumber=false`, and optional exact `teamID`; they do not
emit an empty `provisioningProfiles` dictionary. `xcodebuild -exportArchive`
receives the exact verified archive and a freshly absent/created owned export
directory. Success requires exactly one `.ipa`; zero or multiple artifacts are
errors, so a stale find-first result cannot be published.

`package --target macos --distribution` archives the same v2 application target
for Release and exports with canonical `developer-id`. The exported directory
must contain exactly one app, which passes the shared verifier followed by
`codesign --verify --deep --strict`. With notarization enabled, the adapter
requires `--notary-profile` or `SSC_NOTARY_KEYCHAIN_PROFILE`, creates one fresh
ZIP with `ditto -c -k --keepParent`, and submits it using
`xcrun notarytool submit --wait --timeout <N>s --output-format json --no-progress
--keychain-profile <profile>`. `--notary-timeout-seconds` defaults to 900 and
accepts only `1...3600`. It then runs `xcrun stapler staple` and
`xcrun stapler validate` on the verified app. `--no-notarize` requires no
notary credential. A requested DMG is created only from that exact verified,
codesign-checked exported app; when notarization is enabled it is created only
after successful staple and validate, while `--no-notarize` intentionally has
no staple precondition. `--no-dmg` returns the same verified app.

Publication never invokes fastlane `gym` and never permits fastlane to rebuild
or discover a product. v2 iOS publish first creates the verified
`app-store-connect` IPA, then uploads its explicit path with `pilot` for
TestFlight or `deliver` for App Store. v2 macOS publish first exports exactly one
verified Mac App Store `.pkg`, then calls `deliver(pkg: <exact path>)`.
The Mac App Store route uses the shared checked Release archive authority,
verifies the archived app, then exports canonical `method=app-store-connect`,
`destination=export`, `signingStyle=automatic`,
`manageAppVersionAndBuildNumber=false`, and the exact team id into a fresh
directory that must contain exactly one `.pkg`; it never reuses the
Developer-ID app export.
`--api-key-path` wins over `APP_STORE_CONNECT_API_KEY_PATH` and must name a
regular API-key JSON file before build/upload; it is passed by environment or
`api_key_path`, never embedded in generated text or logs. Generated Fastfiles
live in the owned distribution directory and consume
`SSC_IPA_PATH`/`SSC_PKG_PATH`, `SSC_XCODE_PROJECT`, `SSC_XCODE_SCHEME`,
`SSC_BUNDLE_ID`, the API-key path, and optional `SSC_RELEASE_NOTES`. Release
notes are never interpolated into Ruby source: the variable is absent when the
flag is unset, and the generated TestFlight lane passes
`ENV["SSC_RELEASE_NOTES"]` to `pilot` as its changelog. Tests cover quotes,
backslashes, CR/LF, and Unicode without syntax or code injection. `--fastlane`
selects an existing source-adjacent Fastfile only after the CLI has built and
verified the artifact;
the same environment contract is provided and the custom lane is responsible
for uploading that path, not substituting a build. Existing custom lane names
remain exactly `testflight`, `appstore`, and `mac_appstore`.

Every external tool probe catches a missing executable and emits one
command-scoped corrective diagnostic with no host stack before expensive work.
Missing team, API-key JSON, or notarization profile is likewise noninteractive
and bounded. Tests need no real certificate, secret, notarization, or network:
pure argv/environment plans pin every project/scheme/path/credential boundary;
synthetic archives cover traversal, wrong bundle, debug CLI, and malformed
plist; fresh export fixtures cover zero/one/multiple IPA/PKG/app results; a fake
runner proves archive→verify→export→codesign/ZIP/notary/staple/DMG and explicit
fastlane handoffs; generated plist/Fastfile text passes `plutil -lint` and
`ruby -c`; assembled commands prove missing-tool/credential failures contain no
v1 fallback or stack trace.

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
The iOS 26.5 Simulator runtime and available iPhone devices were installed and
confirmed on the implementation host before this design checkpoint, so the
current acceptance run must execute the iOS build rather than record a skip.
The gate also executes `xcodebuild -list` and destination-specific
`-showBuildSettings`, byte-compares two full generated trees, inspects the
macOS bundle plist/product/executable, and bounded-launches that executable.

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

## Standard toolkit, module initialization, failure, locale and JSON closure

The production NativeUi source shape is
`serve(lower(view(), theme), port)`, not a direct `emit(fragment(...))` call.
Swift acceptance therefore includes the imported `std/ui/lower.ssc` execution
path, theme-token conversion, module-level values such as `localeSignal`, and
the self-hosted `std/json.ssc` facade used by fetched/keyed application data.
Curried toolkit builders receive varargs as the canonical `Cons`/`Nil` list and
call `.toList` before constructing nodes. Swift therefore implements the shared
v2 method contract `List.toList == identity` for every proper list; it does not
copy, reorder, or retag the value, and non-list receivers remain unsupported.
The lowerer then maps style pairs and joins their strings. Swift implements the
shared proper-list `mkString` overloads exactly: `mkString()` joins `sscPlain`
element text with no separator, `mkString(sep)` inserts the String separator,
and `mkString(prefix, sep, suffix)` adds the three String delimiters. Other
arities/types and non-list receivers remain unsupported. Together with the
existing `List.map`, these are the complete dynamic List methods called by the
shipped `std/ui/lower.ssc`; the standard fixture crosses all three.
The dedicated real-Swift/CoreIR method matrix additionally pins every promised
join overload: `["a", 2]` produces `a2`, `a|2`, and `<a|2>` for 0, 1, and 3
arguments; `Nil` produces `""`, `""`, and `"<>"`. Wrong arity, a non-String
delimiter, and a non-list receiver remain rejected and are negative gates.
Calling a proper `Cons`/`Nil` list with exactly one Int performs zero-based
indexing, matching shared v2 `Runtime.applyFallback`; the standard heading
lowerer depends on `sizes(level - 1)`. A negative index or index at/above length
is the bounded runtime failure `app: list index out of bounds`; a non-Int or
wrong arity is `app: list index requires exactly one Int`. Malformed lists do
not produce a value: the evaluator validates the entire receiver before
indexing and records catchable `SscRuntimeFailure("app: malformed list")`.
`Cons(1, BadTail)` at index 0, a wrong-arity `Cons`, and a `Nil` carrying fields
are explicit real-Swift negatives; none may return a partial head or host-trap.
For two proper lists, dynamic `+` and `++` concatenate left then right into a
fresh canonical `Cons`/`Nil` list, matching shared v2 arithmetic and the
`cardWithHeader` lowerer. Both complete operands are validated before any
result: a malformed left/right list records catchable
`SscRuntimeFailure("list concat: malformed list")`; a non-list right operand
records `SscRuntimeFailure("list concat: right operand must be List")`.
Empty/non-empty combinations preserve exact order and never mutate either
input.

At the checked CoreIR boundary a source `Map[String, Any]` may be represented
either as `SscMap` or as the frontend's proper association list of
`Tuple2(String, Value)`. NativeUi `element` normalizes both shapes into the same
owned `SscMap`, validates every value as portable, and consumes association
entries left-to-right so a duplicate String key is last-wins. An improper list,
a non-`Tuple2` entry, a tuple of the wrong arity, or a non-String key is a
bounded sourced runtime failure. The Apple renderer receives only the
normalized map ABI; it does not decode source association lists itself.
The real host/Apple-boundary probe pins
`[("style", "first"), ("style", "last")]` to one `.map` attribute value
`style = "last"` and verifies the original list is absent from the ABI. A cell
or array association value is rejected by portable-value validation. Every
malformed/nonportable diagnostic includes the originating `NativeUiSourceRef`
file, line, column, and operation and returns through the bounded runtime
failure path; none of these cases calls `fatalError`.

### Checked manifest entrypoint

The checked source result retains the optional top-level front-matter `main`
name alongside the existing application metadata. For Swift package emission,
`main: run` means that the CoreIR entry executes imported/top-level module
initialization first and then calls the zero-argument global `run` exactly
once. This call is part of the checked program before backend validation and
code generation; the generated Swift runtime does not reparse front matter.

FrontendBridge already appends a call for a user function literally named
`main` when no manifest entry is present. A present manifest entry is
authoritative and replaces that implicit selection: if a source defines both
`main()` and `run()` with `main: run`, only `run()` executes; `main()` must not
run first. `main: main` emits/reuses one call and may not append a duplicate.
Only an absent manifest entry preserves the existing script-mode plus implicit
`def main()` behavior. A manifest entry must be a plain source identifier, must
resolve to a checked zero-argument function, and otherwise fails before Swift
source generation with a deterministic diagnostic naming the entry. This
prevents a malformed/missing entry from degrading into the later NativeUi error
`program did not register a root`.

### Module `val` registration

FrontendBridge represents an imported/top-level `val` as an unconditional
initialization-continuation spine: `Let`/`LetRec` bodies and ordered `Seq`
elements contain `global.reg(Lit(CStr(name)), value)` before the rest of the
entry program. Swift validation may authorize those literal names while
following only that entry initialization spine. It must not descend into a
definition body, lambda, conditional/match arm, loop body, dead branch, or the
registered value expression merely to discover another registration. A name
that is not guaranteed by that compiler-owned spine remains an unsupported
global at generation time. Runtime `global.reg` still installs the evaluated
value in source order. This rule exposes `localeSignal` without allowing a
never-executed registration to mask an unbound global. The generation-negative
matrix includes the exact unsafe draft shape: an outer spine registration whose
value expression contains `global.reg("ghost", ...)` must not authorize a later
`Global("ghost")`, in addition to definition/lambda/branch negatives.

### Portable `__throw__`, `__try__`, and recoverable conversion failures

The normative oracle is FrontendBridge plus PluginBridge/v1 intended language
behavior:

```text
__try__(bodyThunk, handler)
  bodyThunk arity = 0
  handler arity   = 1
  success         -> body result; handler is not invoked
  explicit throw -> handler receives the exact thrown SscValue
  runtime failure -> handler receives String(the deterministic description)
```

Swift keeps three failure categories in the evaluator side channel:

1. `thrown(SscValue)` for `__throw__`; the value is never stringified or
   wrapped before a matching handler receives it;
2. `runtime(SscRuntimeFailure)` for an intentional catchable runtime failure,
   including invalid primitive conversion and NativeUi extension failures;
3. `host(Error)` (or an equivalent non-catchable terminal) for unexpected host
   errors/invariant failures. This category reaches the bounded top-level
   failure boundary and is never converted into a language catch payload.

`__try__` owns only failures produced while invoking its body. It starts with a
clean body scope, returns normally on success, and on `thrown`/`runtime` clears
that caught failure before it invokes the handler. The handler result is the
whole expression result. A throw or runtime failure from the handler is outside
the same catch and therefore propagates to an enclosing `__try__`; nested
handlers must work without restoring an older sticky failure. An existing
failure may not be consumed by a later unrelated `__try__`. `fatalError` and
arbitrary Swift host bugs are not recovery mechanisms and must not be swallowed.

`String.toInt` removes leading/trailing UTF-16 code units whose value is at most
U+0020 before exact Int64 parsing, matching JVM/Scala `String.trim.toLong`.
ASCII spaces/control padding therefore trims; NBSP U+00A0 does not. Invalid or
out-of-range input records the catchable deterministic runtime failure
`String.toInt: invalid integer` and does not return `0` or expose host parser
text. This is the failure used by `lower.ssc::_lenOf` to fall back from tokens
such as `md`; numeric strings such as `"\t 12 \r"` return `12` without invoking
the handler, while `"\u00a012\u00a0"` follows the invalid-input handler path.

At the start of this closure, v2 `Prims.__method__` leaked a raw
`NumberFormatException` for invalid `String.toInt`, while PluginBridge
`__try__` caught only `BridgeThrow` and `InterpretError`. This is a recorded
implementation divergence, not the oracle. The slice normalizes that primitive
failure into `InterpretError("String.toInt: invalid integer")` (or the same
portable recoverable category) and adds focused v2 VM/PluginBridge tests before
using PluginBridge as cross-target evidence.

### Self-hosted JSON facade on Swift

Swift implements only the provider boundary from `std/json.ssc`; parsing and
the preferred renderer remain the imported self-hosted `json-core.ssc` code.
`__jsonCoreInstallRenderer(fn)` stores the program closure for the Machine that
installed it. `JsonValue.raw` and non-string `getOrElse` values invoke that
renderer; a faithful native canonical renderer is permitted only when no
self-hosted renderer is installed. Renderer state is not process-global and
cannot leak between generated sessions.

The canonical `JsonCoreString` payload is an ordered list of UTF-16 code units.
Swift encodes from `String.utf16` and decodes with UTF-16 pairing semantics:
BMP, valid surrogate pairs, controls, U+2028/U+2029, and astral scalars survive
round-trip. Invalid standalone code units are bounded runtime failures, never
silently dropped. The installed self-hosted renderer is authoritative and uses
uppercase four-hex `\\uXXXX` escapes (`A😀` becomes
`A\\uD83D\\uDE00`). Only the no-renderer native fallback follows the existing
provider fallback's lowercase four-hex form (`\\ud83d\\ude00`). Both emit one
escape per UTF-16 unit, never a five/six-digit scalar escape, and each path is
gated separately.

The existing provider contract remains exact:

- tolerant `__jsonCoreWrap` yields a total navigable wrapper; strict
  wrap/raw unwrap `JsonCoreOk` and fail deterministically on `JsonCoreErr` or a
  malformed parser result;
- `get`, `at`, `isNull`, `asString`, `asInt`, `asDouble`, `asBool`, `asList`,
  `asDecimal`, `optString`, `optInt`, `optDecimal`, `getOrElse`, `raw`, `size`,
  and `keys` match `JsonNativePlugin.JsonBox`; missing/wrong-shape navigation
  yields the documented null/zero/empty/default rather than a trap;
- number text without a decimal/exponent becomes Int64 when representable and
  otherwise portable BigInt; decimal/exponent forms become exact Decimal.
  `asInt` uses total truncating low-64-bit conversion matching
  `BigDecimal.longValue`; `optInt` accepts number or string values whose exact
  decimal value is integral (including `1.0` and `1e3`) and returns `None` for
  fractional/invalid input. A huge integral value returns `Some` of the same
  low 64 bits as `BigDecimal.longValue`; the matrix pins
  `18446744073709551617` to `Some(1)`;
- `lookup`/`lookupOpt` preserve the reference missing/null distinctions across
  a JsonValue, insertion-ordered map, list, and UTF-16-indexed string;
- ordinary values encode through `__jsonCoreEncodeValue`: BigInt and Decimal
  remain exact, non-finite Float is a bounded runtime failure, maps sort object
  fields by the exact v2 `Value.toString` key text for non-string keys (for
  example `IntV(1)` and `BoolV(true)`), while string keys remain their literal
  contents, and a closure becomes `"<function>"`;
- malformed JsonCore/list/field/code-unit shapes and unrepresentable numeric
  conversions fail through the catchable runtime category. They never
  `fatalError`, silently discard data, or manufacture a different value.

The real regression is a checked application fixture containing `text`,
`heading`, `styled` with both token and numeric lengths, `defaultTheme`,
`lower`, and `serve`, plus the production-shaped busi locale/JSON/keyed-list
fixture. Snapshot/string inspection alone is insufficient: generated SwiftPM
must execute and its lowered ABI/style must prove `md` resolves to `16px` while
the numeric `12` resolves to `12px`; the same checked application must pass the
assembled macOS and iOS Xcode gates.

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
- [ ] Plain typed `.ssc effect` lowers through `effect.perform.oneshot`; real
      Swift execution rejects a second/concurrent resume with the same structured
      identity, non-user-catchable run failure, and stable diagnostic as VM/ASM,
      while raw/`multi effect` remains reusable. The Swift primitive allowlist
      includes semantic ABI `effect.perform.oneshot@1`.

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
- [x] Package/sign/simulator/device/publish adapters consume the generated
      package and retain bounded missing-tool diagnostics.
- [x] Signed device/archive/export routes use one checked-v2 distribution
      context, explicit project/scheme, archive-relative product discovery, and the
      common app verifier without a v1 parse or inferred product path.
- [x] IPA/PKG/app exports are fresh and unique; notarization is bounded and
      fastlane uploads only the explicit verified artifact without `gym`.
- [x] UI application metadata is obtained with the checked v2 source result,
  validated before output, and never reparsed through the v1 frontend.
- [x] Generator ownership cleanup removes only validated manifest-listed paths,
  survives product/mode changes, and preserves unlisted resources.

### SwiftUI portable runtime

- [x] Checked metadata preserves `main: run`, invokes it exactly once after
  module initialization, suppresses an otherwise implicit `def main()` when
  `run` is selected, leaves absent-manifest script/auto-main mode unchanged,
  and rejects an invalid or missing manifest entry before Swift generation.
- [x] The standard `text`/`heading`/`styled`/`defaultTheme`/`lower`/`serve`
  checked-source fixture executes as real Swift with token fallback and numeric
  conversion, including the builders' proper-list `.toList` identity and the
  mapped CSS list's `.mkString("")`, rather than bypassing the toolkit lowerer.
- [x] Real Swift pins List `mkString` 0/1/3 overloads for mixed and empty lists,
  plus wrong-arity, wrong-delimiter-type, and non-list rejection boundaries.
- [x] Real Swift applies proper lists as zero-based indexed values and pins
  valid, negative/out-of-range, non-Int, wrong-arity, malformed tail/Cons, and
  non-empty Nil cases with catchable runtime failures.
- [x] Real Swift concatenates proper lists for `+`/`++` with exact order and
  empty cases, rejecting non-list and malformed operands recoverably.
- [x] NativeUi `element` accepts both checked String maps and proper String-key
  association lists with last-wins duplicates, while malformed list/tuple/key
  shapes and a cell/array value fail with the original source before a malformed
  ABI reaches the Apple renderer; a host probe inspects the normalized map.
- [x] Entry-init module registrations expose `localeSignal`; a registration in
  a definition/lambda/dead branch or inside an outer registration value cannot
  authorize an unbound global.
- [x] Repeated imported `localeText`/anonymous computed and equality signal
  sites receive stable owner/site/occurrence ids without weakening explicit
  named-signal conflict detection; locale/JSON updates refresh derived closures,
  and keyed rollback/retry/reorder/delete plus nested owners retain exact
  transactional ownership without leaks.
- [x] Swift `__throw__` preserves the exact ADT/value payload and `__try__`
  distinguishes explicit throw from recoverable runtime failure.
- [x] Successful, invalid/trimmed-conversion, nested handler rethrow/runtime
  failure, and non-catchable host-negative cases execute under real Swift;
  v2 VM/PluginBridge also normalize invalid `String.toInt` without leaking
  `NumberFormatException`, while NBSP remains non-trimmed.
- [x] Swift `JsonValue` matches the self-hosted renderer and provider facade for
  every accessor, lookup, missing/null case, UTF-16 escape, exact number class,
  deterministic encoding, and bounded malformed/non-finite failure.
- [x] The checked production-shaped locale/JSON/keyed-list fixture reaches
  `serve(lower(view(), defaultTheme), ...)` and builds/runs on macOS while the
  same application builds for a concrete installed iOS Simulator.

- [x] The NativeUi ABI contains no v1 View/PluginValue/ForeignV instance.
- [x] Signal bindings and event handlers update SwiftUI on the main actor.
- [x] Keyed insert/move/delete preserves surviving component state by key.
- [x] Toolkit layout/controls/cards/styles/links/rich content retain their
  specified semantics on macOS and iOS.
- [x] Fetch/form/model/table state is reactive and cancels obsolete work.
- [x] Unsupported native features produce deterministic source diagnostics.
- [x] Native tables decode static/signal/fetch sources transactionally, keep
  stable explicit dotted row identity, and share exact rendering semantics on
  macOS and iOS.
- [x] Field/WholeRow/Fields row payloads, relative URL resolution, column
  formatting, link safety, inline-edit dedupe, 2xx refresh, and cancellation
  match the frozen table contract under strict Swift 6 execution.

### Apple end to end

- [x] One reduced busi `.ssc` fixture emits for both macOS and iOS.
- [x] The macOS Xcode application scheme produces a real `.app` and launches a
  smoke fixture.
- [x] The iOS Xcode application scheme passes an available simulator
  `xcodebuild` compile.
- [x] Full generated trees and semantic PBX ids are byte-for-byte deterministic;
  `xcodebuild -list` and `-showBuildSettings` select only the application target.
- [x] Product discovery verifies `.app`, `APPL`, bundle id, and a non-CLI
  executable before unsigned launch or signed adapter handoff.
- [x] Existing focused conformance and SwiftUI compatibility suites stay green.

## Testing strategy

Planned focused gates, refined to exact test names as slices land:

```bash
cd <worktree> && scripts/sbtc "v2Swift/test"
cd <worktree> && scripts/sbtc "v2SwiftBackend/test"
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
- **One deterministic multi-platform PBX application target** — the same source
  module and scheme build macOS and iOS, while semantic hashed ids make the
  generated project reproducible. Rejected: SwiftPM executable schemes (not an
  installable iOS application) and separate drifting platform projects.
- **Explicit debug CLI versus Xcode application artifact** — command authority
  is represented in types and every Apple product is discovered and verified
  from Xcode build settings. Rejected: ambiguous product strings and hard-coded
  `.build`/`Debug-*` paths that can select `<AppName>Cli`.
- **Checked v2 application metadata** — bundle/version identity crosses the same
  frontend conversion as CoreIR and fails before output. Rejected: using the v1
  parser as a metadata side channel or silently rewriting invalid bundle ids.
- **Manifest-scoped generated-file ownership** — exact sorted paths permit safe
  rename/mode cleanup without claiming user resources. Rejected: broad directory
  deletion and filename heuristics, which either retain stale `@main` files or
  destroy unlisted assets.

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

### Standard lower, locale, JSON and transactional lifecycle closure (2026-07-12)

- `730055e78` runs checked manifest selection, safe init-spine registration,
  portable `__try__`/`String.toInt`, exact JSON facade/renderer behavior,
  proper-list methods/application/concatenation, normalized association maps,
  anonymous owner-qualified derived signals, and the unchanged standard
  `serve(lower(...))` production shape in generated Swift. `fb2590069` fixes
  the nested reconciliation defect found by the real lifecycle matrix: only
  the outermost successful transaction performs global scope disposal.
- The independent `nativeui-try-reviewer` and `nativeui-json-reviewer` both
  posted final post-code `APPROVE` in the `scalascript` Rozum room. Their final
  gates cover unexpected host errors, every List/mkString shape, sourced
  attrs/events normalization, locale/JSON closure refresh, keyed rollback,
  reorder/delete/reinsert, retained outer owners, and exact disposal counts.
- Fresh post-rebase feature gates pass: `v2SwiftBackend/test` 54/54;
  `SwiftV2CliTest` + `SwiftV2DistributionTest` + `SwiftUIBuildCliTest` +
  `SwiftUiRealFixtureBuildTest` 54/54; `v2PluginBridge/test` 33/33;
  assembled `tests/e2e/v2-swift-cli.sh`; and assembled
  `tests/e2e/v2-swiftui-apple.sh`, which builds/verifies
  `busi_pipeline_nativeui_smoke.app` on macOS and iPhone 16 Pro Simulator.
- Final release closure adds `b4b574c68`, routing only multi-parameter native
  `map` lambdas through the runtime tuple-spreading seam, and `fe4dfb0ae`,
  explicitly delegating the newly landed `backends:[int]` SclJet memory-VFS
  row away from the extra compatibility-bridge harness in agreement with its
  v2.1 real tools/v1 host-plugin lane. Native VM/ASM money output is exact,
  native-entry smoke passes, full FrontendBridge is 200/200, and the fresh
  no-memo money/effect/tkv2/v2 conformance gate is 28/28.

### Final Apple assembled and release verification (2026-07-11)

- `tests/e2e/v2-swiftui-apple.sh` now drives the assembled `bin/ssc` over the
  checked NativeUi example, byte-compares two complete macOS trees, rejects
  legacy sources and the debug-CLI scheme, real-builds/settings-discovers/
  verifies and bounded-launches the macOS app, then builds and verifies the
  same app for a concrete installed iPhone Simulator. `nativeui-reviewer`
  independently reran it and approved the gate in Rozum.
- Final gates pass: Swift backend 43/43; distribution plus v2/legacy Swift CLI
  53/53; assembled `v2-swift-cli` and `v2-swiftui-apple`; money 2/2; effects
  4/4; toolkit-v2 12/12; and v2 conformance 11/11 on every applicable lane.
  Xcode 26.5 selected `appcore_nativeui.app` for both macOS and the concrete
  iPhone 16 Pro Simulator. No certificate, secret, upload, or external network
  was used.

### Signed Apple distribution adapters (`v2-swiftui-apple-distribution-adapters`, 2026-07-11)

- Device, archive, IPA, Developer-ID/notary/DMG, TestFlight, iOS App Store,
  and Mac App Store commands now construct one checked-v2 distribution context
  and consume only its explicit Xcode project/scheme/application identity.
  Destination/configuration build settings, xcarchive-relative application
  discovery, and the platform-strict APPL/bundle/non-CLI verifier are shared by
  unsigned and signed routes; explicit non-v1 Apple packages return before the
  legacy parser.
- Team/API-key/notary credentials and every selected external tool are checked
  noninteractively before generation/archive. Exports use canonical Xcode 26
  methods and fresh exactly-one IPA/app/PKG directories. Generated fastlane
  lanes pin the checked bundle and explicit artifact, invoke the platform-scoped
  Mac lane correctly, never call `gym`, and transport hostile release notes
  only through environment.
- `nativeui-reviewer` approved implementation round 3 in the `scalascript`
  Rozum room. Secret-free plan/synthetic/fake tests pass 19/19; combined v2
  distribution/CLI and legacy SwiftUI CLI tests pass 53/53; fresh assembled
  `v2-swift-cli` passes bounded credential/tool/no-v1 routes; no-memo
  `tkv2-*` conformance passes 12/12; and the final full Swift backend repeat
  passes 43/43 with real SwiftPM, SwiftUI, macOS, and iOS Simulator gates.

### Deterministic Xcode application products (`v2-swiftui-xcode-project`, 2026-07-11)

- UI-mode generation now emits one objectVersion-56 multi-platform application
  target and app-only shared scheme over the exact AppCore, AppleApp, and sorted
  resource inputs. Semantic SHA-256 object ids, a canonical generated-path
  ownership manifest, atomic manifest-last replacement, and hostile-path gates
  make product rename and UI/domain mode switches deterministic without claiming
  user resources.
- The checked frontend carries exact top-level Swift app metadata and explicit
  SwiftUI mode without a v1 parser side channel. CLI results distinguish the
  SwiftPM debug CLI from `XcodeAppArtifact`; unsigned macOS build/run and iOS
  Simulator run share project/scheme invocation, destination build-setting
  discovery, and APPL/bundle/non-CLI executable verification.
- The real checked-source gate byte-compared two complete written trees,
  validated every frozen macOS/iOS build setting, bounded-launched the macOS
  executable, and built the concrete installed iOS 26.5 Simulator destination.
  `nativeui-reviewer` approved round 3 in Rozum. Final pre-publication gates
  passed Swift backend 43/43, Swift CLI 8/8, assembled `v2-swift-cli` e2e, and
  isolated no-memo `tkv2-*` conformance 12/12.

### Isolated trusted HTML on Apple (`7cc1ff978`, 2026-07-11)

- The generated macOS/iOS renderer now decodes only the exact sourced
  `NativeUiTrustedHtml` ABI and mounts a fresh nonpersistent WKWebView with page
  JavaScript disabled. Five real-compiling content rules block HTTP, HTTPS,
  WS, WSS, and FTP subresources while inline markup/CSS and `data:` images stay
  visible; the loopback executable probe records zero network hits.
- One serialized document-policy capability owns each renderer-generated
  `about:blank` load. The latest compiled rule and prepared document wait for
  an older outstanding policy action to cancel before issuance; exact
  WKNavigation identity plus generation rejects stale finish/fail/height
  callbacks. Both main-frame and new-window taps use the same
  linkActivated-only external URL handoff, and replacement/failure/teardown
  cannot publish obsolete state.
- Probe-only compiler/navigation seams are excluded from production builds by
  `SSC_NATIVEUI_HTML_PROBE`. The real macOS gate covers compile-before-load,
  page/client-world isolation, data-image dimensions, delayed replacement,
  grow/shrink, source-only recovery, forced stale terminals, nil load,
  malformed descriptors, and deinit; the installed iOS 16 Simulator SDK
  typechecks the production source set under strict Swift 6 warnings-as-errors.
- `nativeui-reviewer` approved round 3 in the `scalascript` Rozum room. The
  Swift backend passed 41/41 twice on successive integration bases, the final
  affected WebKit gate passed 2/2, and `tkv2-*` conformance passed 12/12.

### Native Apple tables and row actions (`d54d02126`, 2026-07-11)

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
  6/6 also remain green. A final-repeat URLProtocol harness race was isolated
  from production code and fixed in `400931f68`; action execution then passed
  5/5 consecutively before another named 6/6 and full 40/40 run.

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
