#!/usr/bin/env bash
# The Rust backend must not emit a crate that is MISSING what it could not handle.
#
# rozum's report: `build-rust` on a program importing `std/json-core.ssc` produced
#
#     error[E0425]: cannot find function `jsonCoreRenderFields` in this scope
#      --> src/generated/jc2.rs:8:30
#
# — rustc blaming the user's own call site for a def the backend had dropped. Downstream that
# stopped a production binary (`clients/meeting`, :8405) from being rebuildable at all.
#
# The cause was NOT the reported one. `[names](path.ssc)` reaches the AST as a `Content.Import`
# only when it is a Markdown LINK. Fences have been optional since 2026-07-09, so in a bare `.ssc`
# the whole file is code and that identical line stays INSIDE the code block. Every other lane
# scans for it; the Rust inliner looked only at `Content.Import`, so on that one lane the import
# silently did not exist. With the defs absent, the walker's refusals never ran either — which is
# why the ssc-level diagnostics that used to name the real cause had vanished.
#
# Two halves, and they fail in opposite directions:
#   1. an import the language accepts must REACH the crate;
#   2. a construct the backend cannot lower must be a LOUD refusal naming the def — never a crate
#      quietly missing it.
#
# `emit-rust`, not `build-rust`: this must run where there is no cargo. Nothing here needs one.
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
SSC="$ROOT/bin/ssc-tools"
[[ -x $SSC ]] || { echo "build-rust-refuses-loudly: no launcher at $SSC — run ./install.sh --dev" >&2; exit 2; }

tmp=$(mktemp -d "${TMPDIR:-/tmp}/rust-loud.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
failed=0

# NOTE on cost, so the next person does not re-derive it: this gate runs five cargo builds, one of
# which pulls `serde_json`, and each crate is emitted into its own temp dir — so the dependency tree
# is compiled more than once. Measured 74.8 s standalone. Setting CARGO_TARGET_DIR to share one
# target dir cuts it to 60.7 s and BREAKS the gate: `build-rust` computes the produced binary's path
# as `<crate>/target/<profile>/<name>` itself, so the binary is then not where the CLI looks. The
# timeout in scripts/smoke-ci.ssc carries the measurement instead.

# ── 1. A bare-file import must reach the crate ───────────────────────────────
# No prose, no fences — the form this backend used to ignore. `twice` lives in another file and
# must appear in the emitted Rust.
cat > "$tmp/lib.ssc" <<'SSC'
def twice(n: Int): Int = n * 2
SSC
cat > "$tmp/bare.ssc" <<'SSC'
[twice](lib.ssc)
def main(): Unit = println(twice(21))
SSC

set +e
out=$("$SSC" emit-rust "$tmp/bare.ssc" -o "$tmp/bare-crate" 2>&1); rc=$?
set -e
if [[ $rc -ne 0 ]]; then
  echo "build-rust-refuses-loudly: FAILED — emit-rust rejected a valid bare-import program" >&2
  echo "--- output: $out" >&2
  failed=1
elif ! grep -rqE '^(pub )?fn twice' "$tmp/bare-crate/src/generated/" 2>/dev/null; then
  echo "build-rust-refuses-loudly: FAILED — the imported def is missing from the crate" >&2
  echo "    That is the shape that reaches a user as rustc's 'cannot find function' at their own" >&2
  echo "    call site, with nothing said about the import." >&2
  grep -rhoE '^(pub )?fn [a-zA-Z0-9_]+' "$tmp/bare-crate/src/generated/" 2>/dev/null >&2 || true
  failed=1
fi

# The Markdown-LINK form must keep working — it is the form that DID work, and a fix that traded
# one for the other would pass the assertion above.
cat > "$tmp/linked.ssc" <<'SSC'
# probe

[twice](lib.ssc)

```scalascript
def main(): Unit = println(twice(21))
```
SSC
set +e
out2=$("$SSC" emit-rust "$tmp/linked.ssc" -o "$tmp/linked-crate" 2>&1); rc2=$?
set -e
if [[ $rc2 -ne 0 ]] || ! grep -rqE '^(pub )?fn twice' "$tmp/linked-crate/src/generated/" 2>/dev/null; then
  echo "build-rust-refuses-loudly: FAILED — the Markdown-link import form regressed" >&2
  echo "--- output: $out2" >&2
  failed=1
fi

# ── 2. What it cannot lower must be said out loud ────────────────────────────
# `return` is refused by the walker today. The point is not that it is unsupported — it is that
# being unsupported produces a NAMED diagnostic and a non-zero exit, instead of a crate silently
# missing `boom`. If someone implements `return`, this goes red rather than quietly passing, and
# the right response is to pick another construct the walker still refuses.
#
# It used to be `try/catch`, and the swap is the rule above being followed rather than discovered:
# a sibling had `throw`/`try`/`catch` committed and about to push, which would have turned this red
# on their commit for a reason that has nothing to do with their change. The construct here is a
# stand-in for "something unlowerable"; it is not the subject.
cat > "$tmp/unsupported.ssc" <<'SSC'
def boom(): Int =
  return 1
def main(): Unit = println(boom())
SSC
set +e
out3=$("$SSC" emit-rust "$tmp/unsupported.ssc" -o "$tmp/unsup-crate" 2>&1); rc3=$?
set -e
if [[ $rc3 -eq 0 ]]; then
  echo "build-rust-refuses-loudly: FAILED — an unlowerable def emitted a crate at exit 0" >&2
  echo "--- output: $out3" >&2
  failed=1
fi
if [[ $out3 != *"boom"* ]]; then
  echo "build-rust-refuses-loudly: FAILED — the refusal does not name the def it refused" >&2
  echo "--- output: $out3" >&2
  failed=1
fi

# ── 3. List code must lower, not be refused ──────────────────────────────────
# `Nil`, `h :: t` and `!cond` were four separate refusals; they are the vocabulary any list-shaped
# program is written in, so the backend refused most real code. Emission is asserted here (no
# cargo); the SEMANTIC check below runs only where a toolchain exists.
cat > "$tmp/list.ssc" <<'SSC'
def sum(xs: List[Int]): Int = xs match
  case Nil => 0
  case h :: t => h + sum(t)

def main(): Unit =
  println(sum(1 :: List(2, 3)))
  println(!(1 == 2))
SSC
set +e
out4=$("$SSC" emit-rust "$tmp/list.ssc" -o "$tmp/list-crate" 2>&1); rc4=$?
set -e
if [[ $rc4 -ne 0 || $out4 == *"Generic("* ]]; then
  echo "build-rust-refuses-loudly: FAILED — list vocabulary is refused" >&2
  echo "--- output: $out4" >&2
  failed=1
fi

# ── 4. A case class must produce Rust that is Rust ───────────────────────────
# `case class Marker()` emitted `pub struct Marker {\n,\n}` — a stray comma where the fields would
# be, which is not valid Rust at any level — and every case-class PATTERN came out as
# `Point::Point { x, y }`, an ambiguous associated type (E0223). Together that meant no program
# using a case class could build on this backend at all; it surfaced as 41 of the 150 errors from
# `std/json-core.ssc`, but the two-struct probe below is enough to show it.
cat > "$tmp/structs.ssc" <<'SSC'
case class Marker()
case class Point(x: Int, y: Int)

def describe(p: Point): Int = p match
  case Point(x, y) => x + y

def main(): Unit =
  val m = Marker()
  println(describe(Point(3, 4)))
SSC
set +e
out5=$("$SSC" emit-rust "$tmp/structs.ssc" -o "$tmp/structs-crate" 2>&1); rc5=$?
set -e
gen="$tmp/structs-crate/src/generated"
if [[ $rc5 -ne 0 ]]; then
  echo "build-rust-refuses-loudly: FAILED — emit-rust rejected a plain case-class program" >&2
  echo "--- output: $out5" >&2
  failed=1
else
  # A field-less struct is a UNIT struct. The old output is caught precisely: a line that is a
  # lone comma inside the struct body.
  if grep -rqE '^\s*,\s*$' "$gen" 2>/dev/null; then
    echo "build-rust-refuses-loudly: FAILED — a struct body is a bare comma; that is not Rust" >&2
    grep -rn -B2 -A1 -E '^\s*,\s*$' "$gen" >&2 || true
    failed=1
  fi
  if grep -rqE '\bPoint::Point\b' "$gen" 2>/dev/null; then
    echo "build-rust-refuses-loudly: FAILED — a standalone case class is matched as an enum variant" >&2
    failed=1
  fi
fi

# ── 4a. Emit what the entry REACHES — and only when there IS an entry ────────
# An import pulls in a whole module and the backend used to lower all of it, so a six-line program
# importing `std/json` failed to build on hundreds of lines it never calls. Two directions, and the
# second is the one that would hurt: a BIN prunes, a LIB must not — a lib's defs are called by
# someone this walker cannot see, and dropping one reproduces the `cannot find function` at a
# user's own call site that this whole gate exists for.
cat > "$tmp/unused.ssc" <<'SSC'
def neverCalled(n: Int): Int = n * 3
def alsoCalled(n: Int): Int = n + 1
def main(): Unit = println(alsoCalled(1))
SSC
set +e
out10=$("$SSC" emit-rust "$tmp/unused.ssc" -o "$tmp/reach-crate" 2>&1); rc10=$?
set -e
gen2="$tmp/reach-crate/src/generated"
if [[ $rc10 -ne 0 ]]; then
  echo "build-rust-refuses-loudly: FAILED — emit-rust rejected a plain program" >&2
  echo "--- output: $out10" >&2
  failed=1
else
  if grep -rqE '^(pub )?fn neverCalled' "$gen2" 2>/dev/null; then
    echo "build-rust-refuses-loudly: FAILED — an unreachable def was emitted into a bin crate" >&2
    failed=1
  fi
  if ! grep -rqE '^(pub )?fn alsoCalled' "$gen2" 2>/dev/null; then
    echo "build-rust-refuses-loudly: FAILED — a REACHABLE def was pruned; that is the bug this guards" >&2
    failed=1
  fi
fi

# The lib direction: no `main`, so nothing may be dropped.
cat > "$tmp/lib.ssc" <<'SSC'
def exported(n: Int): Int = n * 3
SSC
set +e
out11=$("$SSC" emit-rust "$tmp/lib.ssc" -o "$tmp/lib-crate" 2>&1); rc11=$?
set -e
if [[ $rc11 -ne 0 ]] || ! grep -rqE '^(pub )?fn exported' "$tmp/lib-crate/src/generated/" 2>/dev/null; then
  echo "build-rust-refuses-loudly: FAILED — a lib crate lost a def it exports" >&2
  echo "--- output: $out11" >&2
  failed=1
fi

# ── 4b. A method with no lowering: lowered, or REFUSED BY NAME ───────────────
# `indexOf`/`find`/`zipWithIndex` were emitted verbatim as Rust method calls, which a `Vec` does
# not have, so rustc blamed the user's own line. Both halves are checked here because the reporter
# asked for the SHAPE and not the three names: the three now lower and agree with the default lane
# (the cargo section below), and a method that still has NO lowering is a refusal that NAMES it
# rather than a call nobody can compile.
cat > "$tmp/unlowered.ssc" <<'SSC'
def main(): Unit =
  val xs = List(1, 2, 3)
  println(xs.sliding(2))
SSC
set +e
out12=$("$SSC" emit-rust "$tmp/unlowered.ssc" -o "$tmp/unl-crate" 2>&1); rc12=$?
set -e
if [[ $rc12 -eq 0 ]]; then
  echo "build-rust-refuses-loudly: FAILED — an unlowered List method emitted a crate at exit 0" >&2
  failed=1
fi
if [[ $out12 != *"sliding"* ]]; then
  echo "build-rust-refuses-loudly: FAILED — the refusal does not name the method it refused" >&2
  echo "--- output: $out12" >&2
  failed=1
fi

# The other side: a method on a receiver whose type we do NOT know still passes through. This is
# the fallback for every method call in the language — refusing there would break a user's own
# types, which is far more than this reports.
cat > "$tmp/ownmethod.ssc" <<'SSC'
case class Box(n: Int)
def main(): Unit = println(Box(1).n)
SSC
set +e
out13=$("$SSC" emit-rust "$tmp/ownmethod.ssc" -o "$tmp/own-crate" 2>&1); rc13=$?
set -e
if [[ $rc13 -ne 0 ]]; then
  echo "build-rust-refuses-loudly: FAILED — a user's own type was caught by the List refusal" >&2
  echo "--- output: $out13" >&2
  failed=1
fi

# ── 5. An extern with no Rust side must say so, and only when it is CALLED ───
# An `extern def` without `@rust(...)` renders to nothing on purpose — an extern nobody calls needs
# no Rust side. But the CALL was still emitted, so importing std/json gave the user
# `cannot find function __jsonCoreEncodeValue` pointing into generated code they never wrote.
cat > "$tmp/extern-called.ssc" <<'SSC'
extern def __notInRust(x: Int): Int
def useIt(n: Int): Int = __notInRust(n)
def main(): Unit = println(useIt(1))
SSC
set +e
out6=$("$SSC" emit-rust "$tmp/extern-called.ssc" -o "$tmp/ext-crate" 2>&1); rc6=$?
set -e
if [[ $rc6 -eq 0 ]]; then
  echo "build-rust-refuses-loudly: FAILED — a called extern with no Rust side emitted at exit 0" >&2
  echo "--- output: $out6" >&2
  failed=1
fi
if [[ $out6 != *"__notInRust"* || $out6 != *"useIt"* ]]; then
  echo "build-rust-refuses-loudly: FAILED — the refusal names neither the extern nor its caller" >&2
  echo "--- output: $out6" >&2
  failed=1
fi

# The other side of it: an extern nobody calls is NOT an error. Without this the fix would break
# every program that declares externs for another backend — which is most of std.
cat > "$tmp/extern-unused.ssc" <<'SSC'
extern def __neverCalled(x: Int): Int
def main(): Unit = println(1 + 1)
SSC
set +e
out7=$("$SSC" emit-rust "$tmp/extern-unused.ssc" -o "$tmp/ext2-crate" 2>&1); rc7=$?
set -e
if [[ $rc7 -ne 0 ]]; then
  echo "build-rust-refuses-loudly: FAILED — an UNCALLED extern was refused; that breaks most of std" >&2
  echo "--- output: $out7" >&2
  failed=1
fi

# ── 6a. A case class can live in an `Any`, and come back out ─────────────────
# `Any` maps to `crate::value::Value`, a closed enum with no variant for a user struct, so code
# written against `Any` — which is most of std/json-core — could not be lowered at all. `Value` now
# has an `Obj(name, fields)` variant, and the coercions are driven by DECLARED types: a case-class
# field, a def parameter, a return type. Checked against the default lane, not against expected
# text I wrote, because the point is that both lanes agree.
cat > "$tmp/anyrt.ssc" <<'SSC'
case class Ok(value: Any, next: Int)
case class Err(message: String)

def describe(r: Any): String = r match
  case Ok(v, n) => "ok:" + n
  case Err(m) => "err:" + m

def make(good: Boolean): Any =
  if good then Ok(1, 42) else Err("no")

def main(): Unit =
  println(describe(make(true)))
  println(describe(make(false)))
SSC
set +e
out9=$("$SSC" emit-rust "$tmp/anyrt.ssc" -o "$tmp/anyrt-crate" 2>&1); rc9=$?
set -e
if [[ $rc9 -ne 0 || $out9 == *"Generic("* ]]; then
  echo "build-rust-refuses-loudly: FAILED — a case class in an Any is refused" >&2
  echo "--- output: $out9" >&2
  failed=1
fi

# ── 6. charAt / substring are UTF-16 code units ──────────────────────────────
# They had no arm and came out as Rust String methods that do not exist (32 + 5 errors on a
# six-line program). The kernel is `IntV(s.charAt(i.toInt).toLong)` — an Int, a CODE UNIT — so the
# probe uses a non-ASCII string: indexing `chars()` instead would agree on ASCII and silently
# disagree here, which is the whole reason the helper exists.
cat > "$tmp/strings.ssc" <<'SSC'
def main(): Unit =
  val s = "aé漢"
  println(s.charAt(0))
  println(s.charAt(2))
  println(s.substring(1, 2))
  println(s.substring(2))
SSC
set +e
out8=$("$SSC" emit-rust "$tmp/strings.ssc" -o "$tmp/str-crate" 2>&1); rc8=$?
set -e
if [[ $rc8 -ne 0 || $out8 == *"Generic("* ]]; then
  echo "build-rust-refuses-loudly: FAILED — charAt/substring are refused" >&2
  echo "--- output: $out8" >&2
  failed=1
fi

# -- A field typed `List[UserEnum]`, and `throw` of a case class without `new` -
#
# Two defects that only ever showed together, in a module no gate could reach. Both are GENERAL --
# neither is about the module that exposed them.
#
#   1. `List[Colour]` emitted `Vec<i64>`. Scala-3 `enum` declarations were missing from the set of
#      known user types while being rendered a few lines away, so mapType fell through to its
#      `i64` default -- the one meant for a generic type PARAMETER. rustc then said
#      "expected i64, found Colour" about a field the author had declared correctly.
#
#   2. `throw Err("msg")` demanded a Display no generated type has. The lowering already lifts the
#      message out of `throw new Err("msg")` -- an exception on this target IS its message -- but
#      only from the `new` spelling. Scala 3 writes it without `new`, which is what every .ssc
#      actually contains.
#
# Neither moves the survey: it measures 81/51 with and without both, because every std module
# writing either form is REFUSED earlier for an unrelated reason and never reaches cargo. That is
# precisely why they are checked here.
if command -v cargo >/dev/null 2>&1; then
  cat > "$tmp/usertypes.ssc" <<'SSC'
---
name: usertypes
version: 1.0.0
description: a List of a user enum keeps its element type; throw lifts its message
---

```scalascript
enum Colour:
  case Named(n: String)

case class Box(items: List[Colour])
case class Err(message: String)

def count(b: Box): Int =
  if b.items.isEmpty then throw Err("empty") else b.items.length

@main def run(): Unit =
  println(count(Box(List(Colour.Named("red")))))
```
SSC
  set +e
  u_out=$("$SSC" build-rust "$tmp/usertypes.ssc" -o "$tmp/usertypesbin" 2>&1); urc=$?
  u_ran=$("$tmp/usertypesbin" 2>&1)
  set -e
  if [[ $urc -ne 0 ]]; then
    echo "build-rust-refuses-loudly: FAILED -- a List of a user enum, or a throw without new" >&2
    echo "  'expected i64, found Colour' means the enum is missing from the known-type set;" >&2
    echo "  'doesnt implement Display' means the throw payload was not lifted." >&2
    echo "--- output: $u_out" >&2
    failed=1
  elif [[ $u_ran != "1" ]]; then
    echo "build-rust-refuses-loudly: FAILED -- user-types binary printed '$u_ran', want '1'" >&2
    failed=1
  fi
fi

# -- A QUALIFIED enum constructor must be `Enum::Ctor`, not `Enum.Ctor` -------
#
# `Shape.Box(2, 3)` emitted as `Shape.Box(2, 3)` -- rustc: E0423 expected value, found enum. The
# UNQUALIFIED spelling was always right, so only this one was wrong, and nothing showed it: every
# std module writing a qualified constructor is REFUSED earlier for an unrelated reason and never
# reaches cargo. The survey is silent about this fix -- it measures 81/51 with and without it --
# which is exactly why the case lives here instead of there.
#
# The named-field variant is the one that catches a lazy fix: formatting `Enum::Ctor($args)` by
# hand compiles for a tuple variant and gives E0533 for a named-field one, so the lowering
# delegates to the unqualified path rather than reformatting it.
if command -v cargo >/dev/null 2>&1; then
  cat > "$tmp/qctor.ssc" <<'SSC'
---
name: qctor
version: 1.0.0
description: a qualified enum constructor must lower to Enum::Ctor
---

```scalascript
enum Shape:
  case Box(w: Int, h: Int)

@main def run(): Unit =
  val b = Shape.Box(2, 3)
  println("qctor-ok")
```
SSC
  set +e
  q_out=$("$SSC" build-rust "$tmp/qctor.ssc" -o "$tmp/qctorbin" 2>&1); qrc=$?
  q_ran=$("$tmp/qctorbin" 2>&1)
  set -e
  if [[ $qrc -ne 0 ]]; then
    echo "build-rust-refuses-loudly: FAILED -- a qualified enum constructor did not build" >&2
    echo "  E0423 here means it emitted Enum.Ctor; E0533 means it emitted the tuple form" >&2
    echo "  for a named-field variant." >&2
    echo "--- output: $q_out" >&2
    failed=1
  elif [[ $q_ran != "qctor-ok" ]]; then
    echo "build-rust-refuses-loudly: FAILED -- qualified-ctor binary printed '$q_ran'" >&2
    failed=1
  fi
fi

# Does it MEAN the right thing? Emission proves only that nothing was refused, and a wrong slice
# pattern emits happily. Needs cargo, so it is conditional — and says so when it skips, because a
# check that silently becomes a no-op is worse than one that is absent.
if command -v cargo >/dev/null 2>&1; then
  set +e
  bin_out=$("$SSC" build-rust "$tmp/list.ssc" -o "$tmp/listbin" 2>&1); brc=$?
  ran=$("$tmp/listbin" 2>&1)
  set -e
  if [[ $brc -ne 0 ]]; then
    echo "build-rust-refuses-loudly: FAILED — cargo rejected the emitted list code" >&2
    echo "--- output: $bin_out" >&2
    failed=1
  elif [[ $ran != *"6"* || $ran != *"true"* ]]; then
    echo "build-rust-refuses-loudly: FAILED — the list program ran but gave '$ran', wanted 6 / true" >&2
    failed=1
  fi
  set +e
  sbin=$("$SSC" build-rust "$tmp/structs.ssc" -o "$tmp/structsbin" 2>&1); src=$?
  sran=$("$tmp/structsbin" 2>&1)
  set -e
  if [[ $src -ne 0 ]]; then
    echo "build-rust-refuses-loudly: FAILED — cargo rejected the emitted case-class code" >&2
    echo "--- output: $sbin" >&2
    failed=1
  elif [[ $sran != *"7"* ]]; then
    echo "build-rust-refuses-loudly: FAILED — the case-class program gave '$sran', wanted 7" >&2
    failed=1
  fi

  # The `Any` round-trip, against the default lane: construct a case class into an `Any`, return it
  # from an `Any`-returning def through both branches of an `if`, then match it back out. Every one
  # of those is a separate boundary in the walker, and a coercion missing at any of them shows up
  # here as a cargo failure or a different answer.
  set +e
  abin=$("$SSC" build-rust "$tmp/anyrt.ssc" -o "$tmp/anyrtbin" 2>&1); arc=$?
  any_rust=$("$tmp/anyrtbin" 2>&1)
  any_ref=$("$ROOT/bin/ssc" run "$tmp/anyrt.ssc" 2>/dev/null)
  set -e
  if [[ $arc -ne 0 ]]; then
    echo "build-rust-refuses-loudly: FAILED — cargo rejected the Any round-trip" >&2
    echo "--- output: $abin" >&2
    failed=1
  elif [[ "$any_rust" != "$any_ref" || -z "$any_ref" ]]; then
    echo "build-rust-refuses-loudly: FAILED — rust and the default lane disagree on the Any round-trip" >&2
    echo "--- rust: $(printf '%s' "$any_rust" | tr '\n' '|')   ssc: $(printf '%s' "$any_ref" | tr '\n' '|')" >&2
    failed=1
  fi

  # The three list methods, against the default lane. Emission proves only that nothing was
  # refused; `zipWithIndex` in particular is a PAIR ORDER question — Scala gives (element, index)
  # and Rust's `enumerate` gives (index, element) — which only a comparison can catch.
  cat > "$tmp/listm.ssc" <<'SSC'
def main(): Unit =
  val xs = ["a", "b", "c"]
  println("indexOf      = " + xs.indexOf("b"))
  println("find         = " + xs.find(s => s == "b"))
  println("zipWithIndex = " + xs.zipWithIndex.length)
  println("missing      = " + xs.indexOf("zz"))
SSC
  set +e
  lmb=$("$SSC" build-rust "$tmp/listm.ssc" -o "$tmp/listmbin" 2>&1); lmrc=$?
  lm_rust=$("$tmp/listmbin" 2>&1)
  lm_ref=$("$ROOT/bin/ssc" run "$tmp/listm.ssc" 2>/dev/null)
  set -e
  if [[ $lmrc -ne 0 ]]; then
    echo "build-rust-refuses-loudly: FAILED — the list methods do not build" >&2
    echo "--- output: $(printf '%s' "$lmb" | tail -5)" >&2
    failed=1
  elif [[ "$lm_rust" != "$lm_ref" || -z "$lm_ref" ]]; then
    echo "build-rust-refuses-loudly: FAILED — rust and the default lane disagree on list methods" >&2
    echo "--- rust: $(printf '%s' "$lm_rust" | tr '\n' '|')   ssc: $(printf '%s' "$lm_ref" | tr '\n' '|')" >&2
    failed=1
  fi

  # AN MCP CLIENT DRIVING AN MCP SERVER, both built from .ssc by this lane.
  #
  # THIS IS THE ONLY CASE HERE THAT PROVES A FEATURE RATHER THAN A LOWERING, and it is end to end on
  # purpose: the client SPAWNS the server binary, completes the JSON-RPC handshake, lists its tools
  # and calls one. Nothing short of running both halves can show that — an emission check would pass
  # on a client that connects and then hangs, and the survey cannot see any of it because
  # std/mcp/client.ssc is a declaration module whose members are only reached by a program.
  #
  # It also exercises the extern-class member rule: `c.listToolNames()` lowers to a free call with
  # the RECEIVER AS THE FIRST ARGUMENT, because an extern class has no Rust type of its own here.
  # Getting that wrong is a compile error, so this case is the rule's only real gate.
  #
  # ALL NINE CLIENT MEMBERS ARE EXERCISED, and the resource and prompt halves are here because I
  # landed them WITHOUT a gate: the ssc server answered only initialize/tools/*, so there was
  # nothing to call them against and the code shipped uncovered. The server now registers a resource
  # and a prompt, which is what makes them testable at all.
  #
  # `m.role` IS DESTRUCTURED ACROSS ALL THREE VARIANTS on purpose. `Role` is FIELD-LESS, the one
  # shape Rust spells differently — `Role::User`, not `Role::User {}` — and a variant rebuilt with
  # the wrong tag still compiles and still counts one message. Only the match tells the difference.
  #
  # AND IT EXERCISES THE SDK-SHAPED MEMBERS, whose types no runtime can name — `listTools(): List[
  # ToolDescriptor]` and `callTool(…): ToolResult`, the latter carrying `List[Content]` where
  # `Content` is an ENUM. The runtime answers in `Value` and the call site assembles the real type.
  # THE PATTERN MATCH ON `Text(t)` IS THE POINT: a variant rebuilt with the wrong tag still compiles
  # and still counts 1, and only destructuring it shows the difference. `Map("k" -> "v")` is there
  # for the other direction — an argument at a `Map[String, Any]` parameter is a
  # `HashMap<String, String>` until something lifts it, and the server echoing `{"k":"v"}` is what
  # proves it arrived as JSON rather than as a shrug.
  #
  # Two binaries and two cargo builds, which is why it is inside the `command -v cargo` block.
  cat > "$tmp/mcpsrv.ssc" <<'SSC'
def main(): Unit =
  mcpRegisterTool("greet", "Greet someone", args => "got:" + args)
  mcpRegisterResource("file:///readme", "readme", "text/plain", uri => "body of " + uri)
  mcpRegisterPrompt("summarize", "Summarize a text", args => "please summarize " + args)
  mcpServe()
SSC
  # The real module, copied BESIDE the program rather than imported by an absolute path: an
  # absolute path in a Markdown-link import silently inlines NOTHING
  # (rust-absolute-import-path-inlines-nothing), and the program still builds because the factory
  # intrinsic is keyed globally — so the case would have passed while testing nothing. `types.ssc`
  # comes too because `client.ssc` imports it as a sibling.
  cp "$ROOT/std/mcp/client.ssc" "$ROOT/std/mcp/types.ssc" "$tmp/"
  cat > "$tmp/mcpcli.ssc" <<SSC
[McpClient, mcpConnectSpawn, ToolDescriptor, ToolResult, ResourceDescriptor, ResourceResult, PromptDescriptor, PromptResult, Content, Role](client.ssc)

def main(): Unit =
  val c = mcpConnectSpawn("$tmp/mcpsrvbin", [])
  println("open=" + c.isOpen())
  val names = c.listToolNames()
  println("tools=" + names.length + ":" + names.mkString(","))
  println("call=" + c.callToolText("greet", "{}"))
  val ts = c.listTools()
  println("desc=" + ts(0).name + "/" + ts(0).description)
  val r = c.callTool("greet", Map("k" -> "v"))
  println("isError=" + r.isError + " parts=" + r.content.length)
  r.content.foreach(x => x match
    case Text(t) => println("text=" + t)
    case other   => println("other"))
  val rs = c.listResources()
  println("res=" + rs.length + ":" + rs(0).uri + "/" + rs(0).mimeType)
  c.readResource("file:///readme").contents.foreach(x => x match
    case Text(t) => println("read=" + t)
    case other   => println("other"))
  val ps = c.listPrompts()
  println("prompts=" + ps.length + ":" + ps(0).name)
  val pr = c.getPrompt("summarize", Map("k" -> "v"))
  pr.messages.foreach(m =>
    val who = m.role match
      case User      => "user"
      case Assistant => "assistant"
      case System    => "system"
    m.content match
      case Text(t) => println("msg=" + who + ":" + t)
      case other   => println("other"))
  c.close()
SSC
  set +e
  msb=$("$SSC" build-rust "$tmp/mcpsrv.ssc" -o "$tmp/mcpsrvbin" 2>&1); msrc=$?
  mcb=$("$SSC" build-rust "$tmp/mcpcli.ssc" -o "$tmp/mcpclibin" 2>&1); mcrc=$?
  mc_out=$("$tmp/mcpclibin" 2>&1)
  set -e
  if [[ $msrc -ne 0 ]]; then
    echo "build-rust-refuses-loudly: FAILED — the MCP server half does not build" >&2
    echo "--- output: $(printf '%s' "$msb" | tail -6)" >&2
    failed=1
  elif [[ $mcrc -ne 0 ]]; then
    echo "build-rust-refuses-loudly: FAILED — the MCP client half does not build" >&2
    echo "--- output: $(printf '%s' "$mcb" | tail -8)" >&2
    failed=1
  elif [[ "$mc_out" != "open=true
tools=1:greet
call=got:{}
desc=greet/Greet someone
isError=false parts=1
text=got:{\"k\":\"v\"}
res=1:file:///readme/text/plain
read=body of file:///readme
prompts=1:summarize
msg=user:please summarize {\"k\":\"v\"}" ]]; then
    echo "build-rust-refuses-loudly: FAILED — the MCP client did not drive the server" >&2
    echo "--- got: $(printf '%s' "$mc_out" | tr '\n' '|')" >&2
    echo "    wanted: …|text=got:{\"k\":\"v\"}|res=1:…|read=body of …|prompts=1:summarize|msg=user:please summarize {\"k\":\"v\"}|" >&2
    failed=1
  fi

  # QUALIFIED and FIELD-LESS enum forms — four spellings of one feature, against the default lane.
  #
  # `19ebadf00` fixed the qualified CONSTRUCTOR with arguments, `Shape.Circle(3)`, and left three
  # relatives behind: the qualified PATTERN `case Shape.Circle(r)`, the field-less pattern in BOTH
  # spellings, and the field-less CONSTRUCTOR `Shape.Dot`, which emitted `Shape.Dot.clone()`.
  #
  # THE FIELD-LESS VARIANT IS WHY THE CASE HAS A `Dot` IN IT. A variant with no fields takes a
  # different route through the walker — no argument list, so it is a `Term.Select` or a bare
  # `Term.Name` rather than an extractor — and in Rust it is `Shape::Dot`, not `Shape::Dot {}`. A
  # probe built only from the shapes the reported defect named would have passed while three quarters
  # of the feature stayed broken; this one found them.
  cat > "$tmp/qenum.ssc" <<'SSC'
enum Shape:
  case Dot
  case Circle(r: Int)
  case Rect(w: Int, h: Int)

def qualified(s: Shape): String = s match
  case Shape.Dot        => "dot"
  case Shape.Circle(r)  => "circle:" + r
  case Shape.Rect(w, h) => "rect:" + w + "x" + h

def bare(s: Shape): String = s match
  case Dot        => "dot"
  case Circle(r)  => "circle:" + r
  case Rect(w, h) => "rect:" + w + "x" + h

def main(): Unit =
  println(qualified(Shape.Circle(3)))
  println(qualified(Shape.Rect(2, 5)))
  println(qualified(Shape.Dot))
  println(bare(Circle(7)))
  println(bare(Dot))
SSC
  set +e
  qeb=$("$SSC" build-rust "$tmp/qenum.ssc" -o "$tmp/qenumbin" 2>&1); qerc=$?
  qe_rust=$("$tmp/qenumbin" 2>&1)
  qe_ref=$("$ROOT/bin/ssc" run "$tmp/qenum.ssc" 2>/dev/null)
  set -e
  if [[ $qerc -ne 0 ]]; then
    echo "build-rust-refuses-loudly: FAILED — qualified/field-less enum forms do not build" >&2
    echo "--- output: $(printf '%s' "$qeb" | tail -8)" >&2
    failed=1
  elif [[ "$qe_rust" != "$qe_ref" || -z "$qe_ref" ]]; then
    echo "build-rust-refuses-loudly: FAILED — rust and the default lane disagree on enum forms" >&2
    echo "--- rust: $(printf '%s' "$qe_rust" | tr '\n' '|')   ssc: $(printf '%s' "$qe_ref" | tr '\n' '|')" >&2
    failed=1
  fi

  # A TYPED LOCAL at the `Any` boundary — the declared type must be APPLIED, not just printed.
  #
  # `renderLetBinding` computed the annotation from the declaration and rendered the RHS separately,
  # so `val m: Map[String, Any] = Map("k" -> "v")` emitted a `HashMap<String, String>` under a
  # `HashMap<String, Value>` annotation. ALL THREE SHAPES of the boundary had it, which is why all
  # three are here: only the Map one was reported, and fixing that alone would have left the same
  # trap one keystroke away.
  #
  # The ARGUMENT boundary already worked — `take(m)` coerces — so a probe that only passed a literal
  # to a function would have shown nothing. The local is the site that was left out.
  cat > "$tmp/typedlocal.ssc" <<'SSC'
def take(m: Map[String, Any]): Int = m.size

def main(): Unit =
  val m: Map[String, Any] = Map("k" -> "v")
  val xs: List[Any] = [1, 2]
  val a: Any = 5
  println("m=" + take(m))
  println("xs=" + xs.length)
  println("a=" + a)
SSC
  set +e
  tlb=$("$SSC" build-rust "$tmp/typedlocal.ssc" -o "$tmp/typedlocalbin" 2>&1); tlrc=$?
  tl_rust=$("$tmp/typedlocalbin" 2>&1)
  tl_ref=$("$ROOT/bin/ssc" run "$tmp/typedlocal.ssc" 2>/dev/null)
  set -e
  if [[ $tlrc -ne 0 ]]; then
    echo "build-rust-refuses-loudly: FAILED — a typed local at the Any boundary does not build" >&2
    echo "--- output: $(printf '%s' "$tlb" | tail -8)" >&2
    failed=1
  elif [[ "$tl_rust" != "$tl_ref" || -z "$tl_ref" ]]; then
    echo "build-rust-refuses-loudly: FAILED — rust and the default lane disagree on a typed local" >&2
    echo "--- rust: $(printf '%s' "$tl_rust" | tr '\n' '|')   ssc: $(printf '%s' "$tl_ref" | tr '\n' '|')" >&2
    failed=1
  fi

  # A GIVEN INSTANCE'S MEMBERS ARE NOT TOP-LEVEL DEFS.
  #
  # `collectDefs` is a DEEP collect, so each `def` inside a `given … with` was picked up as a free
  # function AND emitted again by `renderGiven`, which owns them. The overloading check counted the
  # copies and refused the module — `def combine emits 2 times (overloading)` — naming a Scala
  # feature the file does not use. Ten std modules were refused for it.
  #
  # TWO INSTANCES OF ONE TRAIT is the minimum that shows it: with one instance there is one copy and
  # nothing collides, so a single-instance probe passes while the defect stands. The program also
  # CALLS through both, because a refusal counts what RENDERS and an unreachable instance renders
  # nothing.
  #
  # The givens are NAMED deliberately. An ANONYMOUS `given Combiner[Int] with` has two defects of
  # its own on this lane — every anonymous instance emits as `UnknownGiven`, so two of them are
  # E0428, and `summon[T]` lowers to an empty expression — both filed separately. Writing the case
  # that way would have made a red here ambiguous between three causes, and it did: the first draft
  # failed on those two and not on the one this fixes.
  cat > "$tmp/giveninst.ssc" <<'SSC'
trait Combiner[A]:
  def combine(a: A, b: A): A

given intCombiner: Combiner[Int] with
  def combine(a: Int, b: Int): Int = a + b

given strCombiner: Combiner[String] with
  def combine(a: String, b: String): String = a + b

def main(): Unit =
  println("i=" + intCombiner.combine(2, 3))
  println("s=" + strCombiner.combine("a", "b"))
SSC
  set +e
  gib=$("$SSC" build-rust "$tmp/giveninst.ssc" -o "$tmp/giveninstbin" 2>&1); girc=$?
  gi_rust=$("$tmp/giveninstbin" 2>&1)
  gi_ref=$("$ROOT/bin/ssc" run "$tmp/giveninst.ssc" 2>/dev/null)
  set -e
  if [[ $girc -ne 0 ]]; then
    echo "build-rust-refuses-loudly: FAILED — two given instances of one trait do not build" >&2
    echo "--- output: $(printf '%s' "$gib" | tail -6)" >&2
    failed=1
  elif [[ "$gi_rust" != "$gi_ref" || -z "$gi_ref" ]]; then
    echo "build-rust-refuses-loudly: FAILED — rust and the default lane disagree on given instances" >&2
    echo "--- rust: $(printf '%s' "$gi_rust" | tr '\n' '|')   ssc: $(printf '%s' "$gi_ref" | tr '\n' '|')" >&2
    failed=1
  fi

  # OBJECT MEMBERS — two objects sharing a member name, called from OUTSIDE and from INSIDE.
  #
  # THE CASE HAS TO CROSS THE BOUNDARY OR IT MEASURES NOTHING, and that is why it is here rather
  # than left to the survey. BADRUST reads 0 for this defect today only because every module that
  # exercises it is REFUSED earlier, so the survey cannot see a fix OR a regression in it; a probe
  # that only defined the objects and never called across would compile in both states.
  #
  # THREE SITES, and each is a separate way to be wrong, so each is exercised:
  #   `Tool.text("x")`      the QUALIFIED call from outside — the reported defect, emitted verbatim
  #                         as `Tool.text(...)`, which is not Rust;
  #   `Resource.text(…)`    the same member name on a DIFFERENT owner — this is what made the lane
  #                         call it overloading and refuse the whole module;
  #   `Tool.wrap("y")`      whose body calls its sibling `text(s)` UNQUALIFIED. That one works today
  #                         precisely because both ends are flattened, so it is the site a fix can
  #                         silently break — and a probe without it would pass while it did.
  #
  # Answers are compared against the default lane rather than asserted here: `tool:x`, `res:y`,
  # `tool:wrapped-y`. Getting the OWNER wrong is not a compile error — it is a call to the other
  # object's function with a matching signature, which is exactly the failure a differential
  # catches and a build check does not.
  cat > "$tmp/objmem.ssc" <<'SSC'
object Tool:
  def text(s: String): String = "tool:" + s
  def wrap(s: String): String = text("wrapped-" + s)

object Resource:
  def text(s: String): String = "res:" + s

def main(): Unit =
  println(Tool.text("x"))
  println(Resource.text("y"))
  println(Tool.wrap("y"))
SSC
  set +e
  omb=$("$SSC" build-rust "$tmp/objmem.ssc" -o "$tmp/objmembin" 2>&1); omrc=$?
  om_rust=$("$tmp/objmembin" 2>&1)
  om_ref=$("$ROOT/bin/ssc" run "$tmp/objmem.ssc" 2>/dev/null)
  set -e
  if [[ $omrc -ne 0 ]]; then
    echo "build-rust-refuses-loudly: FAILED — object members do not build" >&2
    echo "--- output: $(printf '%s' "$omb" | tail -8)" >&2
    failed=1
  elif [[ "$om_rust" != "$om_ref" || -z "$om_ref" ]]; then
    echo "build-rust-refuses-loudly: FAILED — rust and the default lane disagree on object members" >&2
    echo "--- rust: $(printf '%s' "$om_rust" | tr '\n' '|')   ssc: $(printf '%s' "$om_ref" | tr '\n' '|')" >&2
    failed=1
  fi

  # A NESTED typed pattern — `case Some(s: String)` — against the default lane.
  #
  # TWO halves have to be right and each fails differently, so both are exercised here. The GUARD:
  # without it the ascription is dropped, the FIRST arm is irrefutable, and every value answers from
  # it. The REBIND: without it the arm hands back a `Value` where the signature says `String`, which
  # is a cargo error rather than a wrong answer.
  #
  # THE DOUBLE FED TO THE `Int` ARM IS THE DISCRIMINATING ROW, and it is why this is a differential
  # and not a compile check: `2.9` must come back as `dbl:2`, taking the SECOND arm. An emitter that
  # drops the ascription reaches the first arm instead — with the rebind in place that is a runtime
  # panic, without it a silently wrong answer, and a probe that only fed a String to a String arm
  # would pass in every one of those states.
  #
  # `Map(…)` is passed straight at the parameter rather than through a typed local on purpose: a
  # `val m: Map[String, Any] = Map("k" -> "x")` does not lift its values today
  # (rust-any-valued-map-literal-not-lifted), and routing this probe through that defect would make
  # a red here ambiguous between two causes.
  cat > "$tmp/nestpat.ssc" <<'SSC'
def pick(args: Map[String, Any], key: String): String =
  args.get(key) match
    case Some(s: String)  => "str:" + s
    case Some(n: Int)     => "int:" + n
    case Some(n: Double)  => "dbl:" + n.toInt
    case Some(b: Boolean) => "bool:" + b
    case Some(v)          => "other:" + v
    case None             => "missing"

def main(): Unit =
  println(pick(Map("k" -> "x"), "k"))
  println(pick(Map("k" -> 7), "k"))
  println(pick(Map("k" -> 2.9), "k"))
  println(pick(Map("k" -> true), "k"))
  println(pick(Map("k" -> "x"), "absent"))
SSC
  set +e
  npb=$("$SSC" build-rust "$tmp/nestpat.ssc" -o "$tmp/nestpatbin" 2>&1); nprc=$?
  np_rust=$("$tmp/nestpatbin" 2>&1)
  np_ref=$("$ROOT/bin/ssc" run "$tmp/nestpat.ssc" 2>/dev/null)
  set -e
  if [[ $nprc -ne 0 ]]; then
    echo "build-rust-refuses-loudly: FAILED — cargo rejected the nested typed patterns" >&2
    echo "--- output: $(printf '%s' "$npb" | tail -8)" >&2
    failed=1
  elif [[ "$np_rust" != "$np_ref" || -z "$np_ref" ]]; then
    echo "build-rust-refuses-loudly: FAILED — rust and the default lane disagree on nested typed patterns" >&2
    echo "--- rust: $(printf '%s' "$np_rust" | tr '\n' '|')   ssc: $(printf '%s' "$np_ref" | tr '\n' '|')" >&2
    failed=1
  fi

  # `++` must not CONSUME its operands (rust-list-concat-moves-its-operands). The emission was
  # `[a, b].concat()`, which builds an array and MOVES both operands into it, so using either one
  # again is E0382 and the program does not build AT ALL — while in ssc a list is immutable and
  # reusing it is ordinary.
  #
  # BOTH statements concatenate the SAME lists, and that is the whole point: either one alone
  # compiles under the moving form too, so a single-use probe would pass while the defect stood.
  # The string pair is here for the same reason and is not decoration — `s ++ t` twice failed
  # identically before the fix, so the defect was never only about lists.
  cat > "$tmp/concatreuse.ssc" <<'SSC'
def main(): Unit =
  val a = List(1, 2)
  val b = List(3)
  val c = List(4, 5)
  println((a ++ b ++ c).length.toString)
  println((a ++ b ++ c).sum.toString)
  val s = "ab"
  val t = "cd"
  println(s ++ t)
  println(s ++ t)
SSC
  set +e
  crb=$("$SSC" build-rust "$tmp/concatreuse.ssc" -o "$tmp/concatreusebin" 2>&1); crrc=$?
  cr_rust=$("$tmp/concatreusebin" 2>&1)
  cr_ref=$("$ROOT/bin/ssc" run "$tmp/concatreuse.ssc" 2>/dev/null)
  set -e
  if [[ $crrc -ne 0 ]]; then
    echo "build-rust-refuses-loudly: FAILED — an operand reused after '++' does not build" >&2
    echo "--- output: $(printf '%s' "$crb" | grep -E 'E0382|error\[' | head -4 | tr '\n' '|')" >&2
    failed=1
  elif [[ "$cr_rust" != "$cr_ref" || -z "$cr_ref" ]]; then
    echo "build-rust-refuses-loudly: FAILED — rust and the default lane disagree on reused '++' operands" >&2
    echo "--- rust: $(printf '%s' "$cr_rust" | tr '\n' '|')   ssc: $(printf '%s' "$cr_ref" | tr '\n' '|')" >&2
    failed=1
  fi

  # The rest of the class the reporter named — `get`, `exists`, and an `Option[Any]` crossing into
  # an `Any` through a CONTAINER (`Map[String, Any]`), which is the same boundary one level down:
  # `HashMap<String, i64>` does not coerce to `HashMap<String, Value>` on its own.
  cat > "$tmp/getexists.ssc" <<'SSC'
case class Cell(payload: Any)

def wrap(m: Map[String, Any], k: String): Any = Cell(m.get(k))

def main(): Unit =
  val m = Map("a" -> 1, "b" -> 2)
  println("get hit  = " + m.get("a"))
  println("get miss = " + m.get("zz"))
  val xs = List(1, 2, 3)
  println("exists   = " + xs.exists(n => n > 2))
  println("forall   = " + xs.forall(n => n > 0))
  println(wrap(m, "a"))
  println(wrap(m, "zz"))
SSC
  set +e
  geb=$("$SSC" build-rust "$tmp/getexists.ssc" -o "$tmp/gebin" 2>&1); gerc=$?
  ge_rust=$("$tmp/gebin" 2>&1)
  ge_ref=$("$ROOT/bin/ssc" run "$tmp/getexists.ssc" 2>/dev/null)
  set -e
  if [[ $gerc -ne 0 ]]; then
    echo "build-rust-refuses-loudly: FAILED — get/exists/Option-in-a-container do not build" >&2
    echo "--- output: $(printf '%s' "$geb" | tail -5)" >&2
    failed=1
  elif [[ "$ge_rust" != "$ge_ref" || -z "$ge_ref" ]]; then
    echo "build-rust-refuses-loudly: FAILED — rust and the default lane disagree on get/exists" >&2
    echo "--- rust: $(printf '%s' "$ge_rust" | tr '\n' '|')   ssc: $(printf '%s' "$ge_ref" | tr '\n' '|')" >&2
    failed=1
  fi

  # The RESULT of a seq-producing method must still be indexable. A user isolated this against a
  # control in the same file: a LITERAL list of tuples indexed fine, only the `zipWithIndex` result
  # did not — so the lowering was right and what got lost was the is-a-list FACT about the local,
  # which made `zwi(0)` lower to a CALL (`expected function, found Vec<(String, i64)>`). The
  # control is kept here for the same reason it was useful there.
  cat > "$tmp/zwi.ssc" <<'SSC'
def main(): Unit =
  val lit = [("a", 1), ("b", 2)]
  println("literal = " + lit(0)._1)
  val xs = ["a", "b"]
  val zwi = xs.zipWithIndex
  println("zipWith = " + zwi(0)._1)
  val srt = [3, 1, 2].sorted
  println("sorted  = " + srt(0))
SSC
  set +e
  zwb=$("$SSC" build-rust "$tmp/zwi.ssc" -o "$tmp/zwibin" 2>&1); zwrc=$?
  zw_rust=$("$tmp/zwibin" 2>&1)
  zw_ref=$("$ROOT/bin/ssc" run "$tmp/zwi.ssc" 2>/dev/null)
  set -e
  if [[ $zwrc -ne 0 ]]; then
    echo "build-rust-refuses-loudly: FAILED — indexing a seq-method result does not build" >&2
    echo "--- output: $(printf '%s' "$zwb" | tail -5)" >&2
    failed=1
  elif [[ "$zw_rust" != "$zw_ref" || -z "$zw_ref" ]]; then
    echo "build-rust-refuses-loudly: FAILED — rust and the default lane disagree on seq-method results" >&2
    echo "--- rust: $(printf '%s' "$zw_rust" | tr '\n' '|')   ssc: $(printf '%s' "$zw_ref" | tr '\n' '|')" >&2
    failed=1
  fi

  # A type the walker WAS TOLD must survive to the emitter. Both shapes come from a user report
  # that paired each with a CONTROL in the same file — that pairing is what proved neither was an
  # inference limit: the declared annotation and the working literal sit side by side.
  #   1. `def rowsOf(...): List[String]` — the RESULT must index, not lower to a call.
  #   2. `.toInt` on a lambda parameter — `String as i32` is not a cast Rust has.
  cat > "$tmp/typelost.ssc" <<'SSC'
def rowsOf(s: String): List[String] = s.split(",")

def main(): Unit =
  val direct = ["a", "b"]
  println("control-index  = " + direct(0))
  val rows = rowsOf("x,y")
  println("declared-index = " + rows(0))
  println("control-toInt  = " + "121".toInt)
  val m = Map("tail" -> "7")
  println("lambda-toInt   = " + m.get("tail").map(s => s.toInt))
SSC
  set +e
  tlb=$("$SSC" build-rust "$tmp/typelost.ssc" -o "$tmp/tlbin" 2>&1); tlrc=$?
  tl_rust=$("$tmp/tlbin" 2>&1)
  tl_ref=$("$ROOT/bin/ssc" run "$tmp/typelost.ssc" 2>/dev/null)
  set -e
  if [[ $tlrc -ne 0 ]]; then
    echo "build-rust-refuses-loudly: FAILED — a declared type lost at a boundary does not build" >&2
    echo "--- output: $(printf '%s' "$tlb" | tail -5)" >&2
    failed=1
  elif [[ "$tl_rust" != "$tl_ref" || -z "$tl_ref" ]]; then
    echo "build-rust-refuses-loudly: FAILED — rust and the default lane disagree on the declared-type cases" >&2
    echo "--- rust: $(printf '%s' "$tl_rust" | tr '\n' '|')   ssc: $(printf '%s' "$tl_ref" | tr '\n' '|')" >&2
    failed=1
  fi

  # A typed pattern against an `Any` must TEST the type, and a Map value must survive `getOrElse`.
  # Both came out of reviewing a colleague's branches. The first is the sharper one: dropping the
  # ascription made the first arm irrefutable, so `case l: List[Any]` answered for a Map — it
  # compiled and ran and was wrong, which is worse than the build error it replaced.
  cat > "$tmp/anymatch.ssc" <<'SSC'
def describe(x: Any): String = x match
  case l: List[Any] => "list:" + l.length
  case m: Map[String, Any] => "map:" + m.get("k")
  case other => "other"

def main(): Unit =
  println(describe(List(1, 2, 3)))
  println(describe(Map("k" -> 9)))
  val mm = Map("a" -> "x")
  println(mm.getOrElse("a", ""))
SSC
  set +e
  amb=$("$SSC" build-rust "$tmp/anymatch.ssc" -o "$tmp/ambin" 2>&1); amrc=$?
  am_rust=$("$tmp/ambin" 2>&1)
  am_ref=$("$ROOT/bin/ssc" run "$tmp/anymatch.ssc" 2>/dev/null)
  set -e
  if [[ $amrc -ne 0 ]]; then
    echo "build-rust-refuses-loudly: FAILED — a typed match on an Any does not build" >&2
    echo "--- output: $(printf '%s' "$amb" | tail -5)" >&2
    failed=1
  elif [[ "$am_rust" != "$am_ref" || -z "$am_ref" ]]; then
    echo "build-rust-refuses-loudly: FAILED — rust and the default lane disagree on a typed Any match" >&2
    echo "--- rust: $(printf '%s' "$am_rust" | tr '\n' '|')   ssc: $(printf '%s' "$am_ref" | tr '\n' '|')" >&2
    failed=1
  fi

  # JSON is checked against the default lane, because the bug it replaced was a CONTRACT mismatch
  # that compiled: `jsonParse` returned a String on this lane and a value everywhere else, so the
  # only assertion that would have caught it is "the two lanes say the same thing".
  cat > "$tmp/json.ssc" <<'SSC'
[jsonParse, jsonStringify](std/json.ssc)
def main(): Unit =
  println(jsonStringify(Map("a" -> "b")))
  println(jsonParse("{\"k\": [1, true, 2.5]}"))
SSC
  set +e
  jbin=$("$SSC" build-rust "$tmp/json.ssc" -o "$tmp/jsonbin" 2>&1); jrc=$?
  json_rust=$("$tmp/jsonbin" 2>&1)
  json_ref=$("$ROOT/bin/ssc" run "$tmp/json.ssc" 2>/dev/null)
  set -e
  if [[ $jrc -ne 0 ]]; then
    echo "build-rust-refuses-loudly: FAILED — importing std/json does not build" >&2
    echo "--- output: $(printf '%s' "$jbin" | tail -5)" >&2
    failed=1
  elif [[ "$json_rust" != "$json_ref" || -z "$json_ref" ]]; then
    echo "build-rust-refuses-loudly: FAILED — rust and the default lane disagree on JSON" >&2
    echo "--- rust: $(printf '%s' "$json_rust" | tr '\n' '|')   ssc: $(printf '%s' "$json_ref" | tr '\n' '|')" >&2
    failed=1
  fi

  # String indexing is checked DIFFERENTIALLY, against another lane rather than against numbers I
  # wrote down — hardcoding 97/28450 would only assert that I did the same arithmetic twice.
  #
  # The reference is `bin/ssc`, the DEFAULT lane, not `--v1`. Running this three ways showed the
  # interpreter disagreeing with everything else: it prints `a` where `bin/ssc` and `--bytecode`
  # print `97`, i.e. it yields a Char where they yield a UTF-16 code unit. Scala's `charAt` is a
  # Char, so the interpreter is arguably the one following Scala — but `std/json-core` compares
  # `source.charAt(next) != 92` and stores strings as `List[Int]`, so the Int reading is what the
  # standard library is written against. Filed as `charat-returns-char-on-v1-and-int-everywhere-else`;
  # picking a side belongs there, not in a codegen gate.
  reference="$ROOT/bin/ssc"
  set +e
  sbin2=$("$SSC" build-rust "$tmp/strings.ssc" -o "$tmp/strbin" 2>&1); src2=$?
  rust_out=$("$tmp/strbin" 2>&1)
  interp_out=$("$reference" run "$tmp/strings.ssc" 2>/dev/null)
  set -e
  if [[ $src2 -ne 0 ]]; then
    echo "build-rust-refuses-loudly: FAILED — cargo rejected the emitted charAt/substring code" >&2
    echo "--- output: $sbin2" >&2
    failed=1
  elif [[ "$rust_out" != "$interp_out" ]]; then
    echo "build-rust-refuses-loudly: FAILED — rust and the default lane disagree on charAt/substring" >&2
    echo "--- rust:   $(printf '%s' "$rust_out" | tr '\n' '|')" >&2
    echo "--- ssc:    $(printf '%s' "$interp_out" | tr '\n' '|')" >&2
    failed=1
  elif [[ -z "$interp_out" ]]; then
    echo "build-rust-refuses-loudly: FAILED — both lanes printed nothing; that is not agreement" >&2
    failed=1
  fi
else
  echo "build-rust-refuses-loudly: no cargo — emission checked, SEMANTICS NOT checked" >&2
fi

# ── An Option must survive a BINDING ─────────────────────────────────────────
# `xs.find(p).map(f).getOrElse(d)` lowered correctly while the expression was WHOLE, and the same
# chain over `val bound = xs.find(p)` lowered as a LIST map — so `getOrElse` became `unwrap_or` on a
# `Vec<String>`, which has no such method (E0599). Reported by rozum; the diagnosis came with it.
#
# Checked on the EMITTED TEXT rather than with cargo: the two lines here differ only by the binding,
# so the defect is visible without paying for a sixth crate build (see the cost note at the top).
# The signal is `collect::<Vec<_>>()` followed by `unwrap_or` — an Option unwrap applied to a
# collected Vec is precisely the shape of an Option that was lost, and it names the defect class
# rather than one method: whatever else gains a lowering, `unwrap_or` on a Vec is always wrong.
cat > "$tmp/optbind.ssc" <<'SSC'
def main(): Unit =
  val xs = ["a", "bb", "ccc"]
  println("inline = " + xs.find(s => s.length > 1).map(s => s + "!").getOrElse("-"))
  val bound = xs.find(s => s.length > 1)
  println("bound  = " + bound.map(s => s + "!").getOrElse("-"))
SSC
set +e
out5=$("$SSC" emit-rust "$tmp/optbind.ssc" -o "$tmp/optbind-crate" 2>&1); rc5=$?
set -e
if [[ $rc5 -ne 0 ]]; then
  echo "build-rust-refuses-loudly: FAILED — emit-rust rejected a valid find/map/getOrElse program" >&2
  echo "--- output: $out5" >&2
  failed=1
else
  emitted=$(cat "$tmp/optbind-crate"/src/generated/*.rs 2>/dev/null || true)
  if [[ -z "$emitted" ]]; then
    echo "build-rust-refuses-loudly: FAILED — no generated source to inspect for the Option binding" >&2
    failed=1
  elif [[ "$emitted" == *"collect::<Vec<_>>().unwrap_or"* ]]; then
    echo "build-rust-refuses-loudly: FAILED — an Option lost its type through a val binding" >&2
    echo "    the chain over the bound name lowered as a LIST map, so unwrap_or lands on a Vec:" >&2
    printf '%s\n' "$emitted" | grep -n 'collect::<Vec<_>>().unwrap_or' | head -2 >&2
    failed=1
  # The inline form has always worked; if it stops, this gate must not read as the binding defect.
  elif [[ "$emitted" != *".unwrap_or("* ]]; then
    echo "build-rust-refuses-loudly: FAILED — neither getOrElse lowered at all; the probe tests nothing" >&2
    failed=1
  fi
fi

[[ $failed -eq 0 ]] || { echo "build-rust-refuses-loudly: FAILED" >&2; exit 1; }
echo "build-rust-refuses-loudly: OK (both import forms reach the crate; a refusal names the def; lists lower; an Option survives a binding)"
