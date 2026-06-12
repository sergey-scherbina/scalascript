# Typer — nominal subtyping for `case class … extends Trait`

**Status**: bug fix. Reported by busi (testbed) 2026-06-12 while building the
`RepositoryBackend` trait SPI. **Priority**: high — silently blocks the idiomatic
`trait` + `case class … extends Trait` pattern with a confusing, name-dependent
error.

## 1  Symptom

A `case class C() extends T` is **not** recognised as a subtype of `T`, so the
typer rejects an upcast:

```scalascript
trait Ab:
  def m: Int
case class Bc() extends Ab:
  def m: Int = 1
def f(): Ab = Bc()      // error: Type mismatch: expected Ab, found Bc
```

Deterministic and **name-dependent** in a way that made it look flaky:

| trait | class | result |
|---|---|---|
| `A`  | `B`  | OK (accidental) |
| `Ab` | `B`  | OK (accidental) |
| `A`  | `Bc` | OK (accidental) |
| `Ab` | `Bc` | **error** |
| `Foo`| `Bar`| **error** |

## 2  Root cause

`Typer.isCompatible` had **no nominal-subtype rule at all**. The only reason any
`C <: T` upcast ever passed was an accidental match in `genericCompatible`:

```scala
case (SType.Named(an, Nil), _) if looksLikeTypeVar(an) => true
case (_, SType.Named(en, Nil)) if looksLikeTypeVar(en) => true
```

`looksLikeTypeVar(name) = name.length <= 3 && name.forall(isUpper||isDigit)` —
true for a single-uppercase name like `A`/`B`/`S`/`M`, so the typer treated those
as **type variables** and skipped the check (a false pass). Real multi-character
mixed-case names (`Ab`, `Foo`, `MemoryRepo`) are not type-var-shaped, so the
clause did not fire and there was no other rule → `Type mismatch`.

## 3  Fix

Add real nominal subtyping over the declared `extends` graph (additive — only
turns false-negatives into correct passes; the `looksLikeTypeVar` heuristic stays
for actual type parameters):

1. A `classParents: Map[String, List[String]]` registry, populated in the collect
   pass (alongside `classFields`) from `templ.inits` for every `class` /
   `case class` / `trait` / `enum`.
2. `nominalSubtype(sub, sup)` — reflexive + transitive reachability over
   `classParents`, cycle-guarded.
3. An `isCompatible` clause: `Named(an, _)` is compatible with `Named(en, _)` when
   `nominalSubtype(an, en)`.

## 4  Behavior checklist

- [ ] `case class C() extends T; def f(): T = C()` type-checks (multi-char names).
- [ ] Transitive chain: `case class C extends Mid`, `trait Mid extends Top` ⇒ `C <: Top`.
- [ ] `enum E extends T: case A` ⇒ a value of `E` is usable where `T` is expected.
- [ ] **Negative kept**: `case class C(); def f(): T = C()` (C does NOT extend T)
      still errors (no false acceptance for unrelated multi-char names).
- [ ] Single-uppercase names keep working (still pass — now via the real rule too).
- [ ] Full interpreter/typer suite green (no regression from the new compatibility).

## 5  Verification

`TyperTest` cases for each checklist item; build `cli/installBin`; re-run the busi
`RepositoryBackend` trait SPI shape (now compiles) and `backendInterpreter/test`.

## 6  busi context

Blocks busi `vr-1` (versioned-repository object model), where the owner directed
the `RepositoryBackend` SPI to be a `trait` (not the older df-1 record-of-functions).
Without this fix every `case class MemoryRepo() extends RepoBackend` was rejected.
