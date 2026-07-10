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

### Application commands

```text
ssc build   --target macos|desktop-macos <file.ssc>
ssc run     --target macos|desktop-macos <file.ssc>
ssc build   --target ios|mobile-ios <file.ssc>
ssc run     --target ios|mobile-ios [--device] <file.ssc>
ssc package --target macos|ios <file.ssc>
ssc publish --target macos|ios <file.ssc>
```

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
SwiftBackend -> generated Swift Package
  AppCore: runtime + compiled domain program
  App: SwiftUI entry + portable NativeUi renderer (UI target only)
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
  Sources/<AppName>/<AppName>App.swift       # UI target
  Sources/<AppName>/SscSwiftUIView.swift     # UI target
  Sources/<AppName>/ContentView.swift        # UI target
  Tests/AppCoreTests/...                     # optional emitted debug fixtures
  Resources/...                              # declared assets
```

`AppCore` has no SwiftUI dependency. A domain-only package can therefore run
under `swift run` on macOS and serve as the first backend gate. The application
target imports `AppCore` and SwiftUI. Stable filenames and product names are
part of the command/test contract.

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
3. Generate/write the deterministic Swift package.
4. Optionally invoke `swift build` or `xcodebuild`.
5. Reuse the existing launch/sign/archive/notarize/publish adapters against the
   generated package/app product.

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

The UI review must settle the exact generated types, but the required behavior
is fixed:

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

The same `.ssc` source emits both macOS and iOS packages. Platform selection may
change deployment metadata and native adapters, not source-level behavior.

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

- [ ] Every structural CoreIR term and core value has executable Swift lowering.
- [ ] Deep self and mutual recursion run with bounded stack through a trampoline.
- [ ] `fact`, `tco`, and `map` CoreIR fixtures match VM output under real
  `swift run`.
- [ ] Existing money/effect `.ssc` fixtures compile through the checked frontend
  and match VM output under real `swift run`.
- [ ] Unsupported globals/primitives fail at generation with actionable names.

### CLI/package

- [ ] `emit-swift` writes deterministic sources and `run-swift` runs AppCore.
- [ ] Apple build/run lane flags are parsed as flags in every supported order.
- [ ] Apple targets use v2 by default, `--v1` is explicit, and v2 failures never
  fall back.
- [ ] Package/sign/simulator/device/publish adapters consume the generated
  package and retain bounded missing-tool diagnostics.

### SwiftUI portable runtime

- [ ] The NativeUi ABI contains no v1 View/PluginValue/ForeignV instance.
- [ ] Signal bindings and event handlers update SwiftUI on the main actor.
- [ ] Keyed insert/move/delete preserves surviving component state by key.
- [ ] Toolkit layout/controls/cards/styles/links/rich content retain their
  specified semantics on macOS and iOS.
- [ ] Fetch/form/model/table state is reactive and cancels obsolete work.
- [ ] Unsupported native features produce deterministic source diagnostics.

### Apple end to end

- [ ] One reduced busi `.ssc` fixture emits for both macOS and iOS.
- [ ] The macOS package passes real `swift build` and an executable smoke.
- [ ] The iOS package passes an available simulator `xcodebuild` compile.
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
- `scripts/sbtc "v2SwiftBackend/test"` passed 3/3 generator/negative/toolchain
  tests. The real Swift 6.3.2 toolchain compiled and ran independent packages
  for `v2/conformance/fact.coreir` (`120`), `tco.coreir` at one million calls
  (`500000500000`), and `map.coreir` (`List(2, 4, 6)`). The affected assembled
  smoke `tests/conformance/run.sh --only 'money-portable-v2' --no-memo` also
  remained green 1/1.
- This is the first backend sub-slice, not closure of the Swift-core gate:
  arbitrary BigInt arithmetic, portable Decimal/Money, explicit `Pure`/`Op`,
  mutual-TCO, and real checked `.ssc` domain fixtures remain required before
  `v2-swift-core-backend` is complete.

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
