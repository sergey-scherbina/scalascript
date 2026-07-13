# UniML × ScalaScript v2 — compile gap map (uniml-portable Phase 0.5)

Goal of the `uniml-portable` program: UniML's single Scala 3 source compiles both with standard
scalac **and** with the self-hosted ScalaScript v2 compiler. This document is the measured baseline —
what v2's Scala-flavored `.ssc` frontend accepts today, and the concrete gaps that must close for it
to compile UniML. It is the TODO for the v2-side track (`uniml-portable-3-v2compile`).

## Method

Probes are small bare `.ssc` programs (`def main(): Unit = …`) exercising one or a few UniML-shape
constructs, run through the v2 self-hosted pipeline:

```
v2/ssc1 <probe>.ssc      # extract scalascript blocks → compile (front+checker) → run on the v2 VM
```

A construct "works" if the probe compiles and prints the expected result; a "gap" surfaces either as
a compile/`[E…]` error, or a runtime `unbound global: _err` (the lowering emitted an unsupported
stub), or wrong behavior. Probe sources live in `uniml/v2-smoke/`. (Ignore the deprecation warnings
from compiling `v2/src` itself — those are not from the probe.)

## Works today (confirmed compile + run on v2 `.ssc`)

| Construct | Probe result |
|---|---|
| `enum` with ADT payloads + **nested** pattern match | `sumT(Node(Leaf 1, Leaf 2))` → `3` |
| Generic `def` (`def id[A](x: A): A`) | `id(42)` → `42` |
| Generic `case class` (`case class Box[A](value: A)`) | `Box(7).value` → `7` |
| `var` + `while` | `sumTo(10)` → `55` |
| `trait` + `case class … extends` + method dispatch | `En().greet("bob")` → `hi bob` |
| **Generic trait** `Proc[I,O]` + impl + generic method dispatch | `runProc(Inc(), 41)` → `42` |
| `String`: `.length`, `.charAt`, `.substring`, concat, `Int.toString` | `"hello".substring(1,3)+len` → `el5` |

This is most of UniML's structural surface — the ADT/CST model, the `Processor[I,O]`/`DialectAdapter`
generic-trait abstraction, and string scanning all fall inside what v2 already compiles. **v2 is far
more capable than the `v2/examples/*.ssc` corpus (no enum/trait/generics there) suggested.**

## Gaps (block compiling UniML) — the v2-side TODO

| Gap | Symptom | UniML dependency | Resolution options |
|---|---|---|---|
| **Anonymous instances** `new Trait[..]:` `def …` | runtime `unbound global: _err` (lowered to an unsupported stub) | `Processor.andThen` and dialect processors create `new Processor[I,P]:` | **UniML-side:** refactor anonymous instances to **named classes** (cheap, keeps within the subset). **or v2-side:** support anonymous class instances. |
| **Mutable `Array[T]`** (`new Array[Int](n)`, `a(i) = v`, `a(i)`) | `IndexOutOfBoundsException` — the array is not sized to `n`; indexed update/apply is broken | **The compat-layer floor.** `uniml.compat` (Phase 1) needs *some* growable/mutable primitive to build `Buffer`/`LinkedMap`/`StrBuilder` on. | **v2-side:** fix `new Array[T](n)` + indexed `apply`/`update` (the natural floor). **or** provide a v2-native mutable buffer primitive the compat layer can wrap. Until then, the compat layer has no floor on v2. |

## Untested / deferred

- **Variance** `[+A]` (`ProcessBatch[+A]`) — probe inconclusive (timed out). UniML-side fallback if
  unsupported: drop `+`, use invariant type params (trivial).
- **`package` / `import` / multi-file** — UniML is multi-file under `package scalascript.uniml`. v2's
  `.ssc` module/import story (vs `import "path"`) must be reconciled for real multi-file compilation.
- **Unicode char classification** (`Character.getType`, categories) and **`Integer.parseInt`** — these
  are JDK, forbidden/absent in v2 by design. They are **replaced by `uniml.compat`** (a compact
  portable Unicode table + hand-rolled int↔hex), so they are not v2 gaps — they are Phase 1 work.

## Conclusions

**UniML-side (Phases 1–2), no v2 changes needed:**
- Replace anonymous `new Trait[..]:` instances with **named classes** throughout.
- Replace `scala.collection.mutable.*` / `StringBuilder` / `Character.*` / `Integer.*` with
  `uniml.compat` — but the compat mutable primitives still need a working **mutable-array floor** in v2.
- Drop variance annotations if v2 can't parse them (invariant is fine).

**v2-side (Phase 3), the concrete asks, in priority order:**
1. **Fix `new Array[T](n)` + indexed `apply`/`update`** — this is the floor everything else builds on.
2. **Multi-file `package`/`import`** so UniML's modules compile together.
3. (Optional) anonymous class instances — else UniML uses named classes.
4. (Optional) covariance annotations — else UniML uses invariant.

The v2-compile smoke (`uniml/v2-smoke/`) is currently **RED** on the Array and anonymous-instance
probes and **GREEN** on the rest; it becomes fully green as the gaps close.

## Results

To be updated as gaps close (Phase 3).
