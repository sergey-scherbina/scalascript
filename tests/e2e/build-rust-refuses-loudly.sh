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

[[ $failed -eq 0 ]] || { echo "build-rust-refuses-loudly: FAILED" >&2; exit 1; }
echo "build-rust-refuses-loudly: OK (both import forms reach the crate; a refusal names the def; lists lower)"
