# The portable subset — one source tree, two hosts

> Invariant I-2 and I-3 of [`00-charter.md`](00-charter.md). This file says what the v3 kernel
> sources may use, and how that is enforced.

The v3 kernel is written so that the **same files** compile with Scala 3 and run on ScalaScript 2.
Not two ports kept in sync — one tree, two hosts. Self-hosting then arrives as a consequence rather
than as a project.

The precedent is `uniml/`, which is dual-compilable today and whose differential harness found 8
defects that point examples had missed. The lint there (`uniml/lint-portable-subset.sh`) is the
starting point for ours, with one deliberate difference stated in §2.

## 1 · The rule

A kernel source may use: immutable `case class` with constructor parameters and methods; `enum` /
sealed ADTs and pattern matching; generics, including generic traits used for dispatch; local `val`
/ `var` and `while`; `List`, `Option`, tuples; `String` operations; `Array` (§2).

A kernel source may **not** use: `scala.collection.mutable.*`, `ArrayBuffer`, `StringBuilder`,
`.newBuilder`, regex (`.matches`, `"…".r`), `java.lang.Character` and the `Char` classification
methods (`isWhitespace`, `isLetter`, `isDigit`, …), mutable fields in a class or object body
(`var`/`val` members), plain non-`case` `class`, anonymous instances (`new Trait:`).

Anything a kernel source needs from outside that list is written **in** the kernel, in the subset —
that is the whole point of the version. Character classification, for example, is a table in our
lexer, not a call into the host.

## 2 · `Array` is in the subset, and it is the one thing that had to be fixed first

A register machine is a fixed-size mutable indexed frame. Without `Array` there is no §1 frame in
[`10-ssc-ir.md`](10-ssc-ir.md), so `Array` is **in** the portable subset by decision, and the
UniML lint's ban on `new Array` / `Array[` does not carry over to v3.

It did not work when that decision was taken. Measured 2026-08-01 on both lanes:

```text
def main(): Unit =
  val a = new Array[Int](3)
  println(a.length)          // prints 1
```

`new Array[T](n)` was being treated as the factory `Array(n)` — an array of the single element `n` —
so `a.length` is `1` and `a(1) = …` fails with `1 is out of bounds (min 0, max 0)`. `Array(1,2,3)`
was correct, which is why the defect survived: the form the corpus exercises works, and the form a
VM needs does not. **`./bin/ssc run` exits 0 on the failing program**, so no exit-code check could
have seen it either.

Tracked in [`../BUGS.md`](../BUGS.md); the fix and its gate are `SSC3-1`. The gate asserts the
`length` of `new Array[Int](3)` is `3` on every lane v3 targets, and must be observed failing before
the fix lands.

## 3 · Enforcement

- **`v3/lint-portable-subset.sh`** — scans the kernel sources for the §1 banned constructs. Static,
  fast, runs on every push. It is a necessary and *insufficient* check: it sees constructs, not
  behaviour.
- **`v3/portable-diff.sh`** — the real one (I-3). Builds the front with scalac, runs the same
  sources on ScalaScript 2, and compares the emitted `.ssir` byte for byte over the corpus. A
  divergence is a defect in one of the two hosts, and the harness says which files diverged rather
  than only that something did.

The gap map is carried from `specs/uniml-portable-gapmap.md` and is **stale by construction** — it
was measured 2026-07-13. `SSC3-1` re-measures every entry before the kernel is written against it,
because building on an inherited list of what does not work is how you discover, three files in,
that two of them work fine and a fourth does not.
