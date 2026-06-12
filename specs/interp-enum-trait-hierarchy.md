# Interpreter — enum → trait hierarchy resolution (busi seq-120 / seq-121)

**Status:** implemented 2026-06-12.
**Source:** busi rozum seq-120 + seq-121 (two symptoms of one root).

## Problem

The interpreter records type ancestry in a single-parent chain
`interp.parentTypes: Map[String, String]`, walked by runtime type-tests
(`PatternRuntime`, `case _: T`) and method dispatch (`DispatchRuntime`). Three gaps
broke resolution through an `enum case → enum → (intermediate) trait → trait`
hierarchy:

1. **Enum/trait own-parent not recorded.** `Defn.Enum` recorded only `case → enum`
   and `Defn.Trait` recorded nothing, so the chain broke at the enum/trait.
   `case _: TaxEvent` on a `PolishTaxEvent.PolishVatConfigured` value returned
   `false` — the walk from the case reached the enum and stopped (seq-120).
2. **Trait concrete methods not registered.** A `def kind = …` body on a trait
   went nowhere (only abstract `Decl.Def`s were collected, for remoteStub). So
   `e.kind` on an enum-case instance raised `No field 'kind'` (seq-121).
3. **Dispatch never walked the parent chain for `typeMethods`** — it checked only
   the exact type — and **`this` (`Term.This`) was unimplemented** (`Cannot eval:
   Term.This`), so even a found trait method whose body uses `this match { … }`
   could not run.

## Fix

- **`StatRuntime`** — `recordFirstParent(name, inits, interp)` records the first
  declared supertype for **enums and traits** (it already happened for classes), so
  the chain is `case → enum → trait → trait`. `Defn.Trait` now also registers its
  **concrete** `Defn.Def` methods into `interp.typeMethods(traitName)`.
- **`DispatchRuntime`** — `lookupTypeMethod(typeName, name)` walks the
  `parentTypes` chain checking each `typeMethods(t)`; used in the member-dispatch
  fallback **after** instance-field resolution (so a field shadows an inherited
  method) for both the no-arg (`e.kind`) and with-args (`e.m(a)`) paths.
  `invokeTypeMethod` binds `this` to the receiver when the body references it.
- **`Interpreter`** — `methodUsesThis(body)` is a body-keyed (IdentityHashMap)
  cache so the `this`-binding `fields.updated("this", recv)` allocation is paid
  only by methods that actually use `this`; the common path is unchanged.
- **`EvalRuntime`** — `Term.This` evaluates to the bound `this` (or a located
  error outside a method body).
- Type-test resolution (`PatternRuntime`) is unchanged — it already walked
  `parentTypes`; gap #1 simply completed the chain it walks.

## Behaviour checklist

- [x] `case _: IntermediateTrait` matches an enum-case value through the chain.
- [x] `case _: TopTrait` / `case _: Enum` also match (each level of the chain).
- [x] a concrete method on a sealed trait dispatches on enum-case / case-class
      instances of subtypes, no-arg and with args.
- [x] `this` resolves to the receiver inside a class/enum/trait method body.
- [x] an instance **field** shadows an inherited method of the same name.
- [x] full `backendInterpreter` suite green (1654) — central dispatch path touched.
