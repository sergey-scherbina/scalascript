# JS backend: supertype type-tests must match subtype instances

## Problem

On the JS backend, a type-test against a **supertype** — a sealed trait, a parent
enum, or an abstract class — never matched a concrete subtype instance:

```scalascript
sealed trait TkNode
case class HeadingNode(text: String) extends TkNode
def isTk(x: Any) = x match
  case h: TkNode => "tk"   // JS: never taken
  case _         => "other"
isTk(HeadingNode("hi"))    // interp/JVM: "tk"  ·  JS (buggy): "other"
```

Emitted case-class / enum-case objects carry only their own **leaf** `_type`
(`{_type:'HeadingNode', ...}`). `JsGenCpsCodegen.genPattern`'s `Pat.Typed` branch,
for a non-primitive type with no case-class tag, emitted an exact
`scrut._type === 'TkNode'` check — which a `HeadingNode` instance never satisfies.
So the supertype arm was skipped and control fell to the wildcard.

This is the JS-backend analogue of the interpreter/JIT supertype-type-test bug
(BUGS #1/#3), which was fixed there via an `isSubtype` chain but never on the JS side.

**Found in busi (2026-06-15):** `std/ui/containers.cardWithHeader(header)` lowers
`header match { case h: TkNode => renderHeader; case _ => [] }` (the header field is
typed `Any`). Under the SPA (JS) runtime every `cardWithHeader` **title was silently
dropped on every screen** — the card body rendered, the header did not — while the
interpreter (`ssc render`) was correct, so `.ssc` tests never caught it. Affects any
`case x: <trait>` in JS-compiled code, not just cards.

## Fix

Give the JS pattern matcher a subtype closure that spans imports.

1. `JsGen.collectSubtypeEdgesFromModule(module)` records `child → direct parents`
   (`subtypeParents`) and the concrete leaf names (`subtypeConcrete`) of a module —
   case classes, case objects, traits, enums + their cases — descending into
   namespace/`package:` wrapping objects. `recomputeSubtypeClosure()` derives
   `subtypeClosure` (`supertypeName → transitive concrete-descendant `_type` names`).
2. **Cross-module accumulation.** The JS backend emits each imported module with a
   *fresh child `JsGen`* via `genImport`, and a trait and its subtypes routinely live
   in a different file than the `match` (busi: `TkNode` in `nodes.ssc`, `case h: TkNode`
   in `lower.ssc`). So the edges accumulate across the import boundary: the entry
   module seeds the accumulators, and `genImport` folds each imported module's edges in
   and propagates them into the child `JsGen` (mirroring `importedParamOrder`), with the
   closure refreshed as the accumulator grows. Without this, the importer's matcher has
   no record of the imported subtype graph and falls back to the broken exact check —
   which is why a single-module test passes while the real (cross-module) bug persists.
3. `genPattern`'s `Pat.Typed` branch: when the tested type is not a primitive and has
   no case-class tag (i.e. it is a supertype), widen the check to the closure of
   concrete descendants:
   `(scrut && (scrut._type === 'HeadingNode' || scrut._type === 'TextNode' || …))`.
   An unknown type with no recorded subtypes falls back to the previous exact-name
   check; a tagged leaf case class still uses its O(1) `_tag` check unchanged.

Only the supertype (no-tag) path changes; leaf-tag matches, primitives, and the
`Pat.Extract` (destructuring) path are untouched. The `_tag`-switch fast path in
`genMatchAsStmts` is unaffected (a supertype arm is an `_type` OR, not a pure tag
check, so such matches already use the if-else chain).

## Verify

- `backendInterpreter/testOnly scalascript.SupertypeTypeTestJsTest` (single-module) —
  direct case-class subtype, transitive enum-case subtype, 3-level intermediate-trait
  narrowing, value binding.
- `backendInterpreter/testOnly scalascript.SupertypeTypeTestXModuleJsTest`
  (**cross-module** — the real busi shape): a `case x: <trait>` whose trait + subtypes
  live in an imported `package:` module matches a subtype; transitive enum-case across
  the import boundary. (A single-module test alone is insufficient — it passed while the
  busi symptom persisted.)
- End-to-end: rebuild `bin/ssc`, re-emit the busi SPA, and confirm `cardWithHeader`
  titles render in a browser across screens.
- Pattern/enum/trait + cross-backend conformance suites stay green
  (`PatternMatchTest`, `UnionTypeTest`, `EnumCrossBackendTest`,
  `SealedExtensionDispatchTest`, `CrossBackendPropertyTest`, conformance tests).
