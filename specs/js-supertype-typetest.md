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

Give the JS pattern matcher a subtype closure.

1. `JsGen.subtypeClosureInModule(module)` scans the module's type declarations
   (case classes, case objects, traits, enums + their cases) and their `extends`
   clauses, then computes the transitive closure `supertypeName → Set[concrete leaf
   `_type` names]`. Stored per module in `JsGen.subtypeClosure` alongside
   `caseClassTagMap`.
2. `genPattern`'s `Pat.Typed` branch: when the tested type is not a primitive and has
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

- `backendInterpreter/testOnly scalascript.SupertypeTypeTestJsTest` — direct
  case-class subtype (the busi card-header repro), transitive enum-case subtype,
  3-level intermediate-trait narrowing, and value binding; JS output equals the
  documented interpreter semantics.
- Pattern/enum/trait + cross-backend conformance suites stay green
  (`PatternMatchTest`, `UnionTypeTest`, `EnumCrossBackendTest`,
  `SealedExtensionDispatchTest`, `CrossBackendPropertyTest`, conformance tests).
