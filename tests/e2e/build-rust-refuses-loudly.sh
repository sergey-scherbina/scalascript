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
# `try/catch` is refused by the walker today. The point is not that it is unsupported — it is that
# being unsupported produces a NAMED diagnostic and a non-zero exit, instead of a crate silently
# missing `boom`. If someone implements try/catch, this goes red rather than quietly passing, and
# the right response is to pick another construct the walker still refuses.
cat > "$tmp/unsupported.ssc" <<'SSC'
def boom(): Int = try 1 catch case _: Throwable => 2
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
else
  echo "build-rust-refuses-loudly: no cargo — emission checked, SEMANTICS NOT checked" >&2
fi

[[ $failed -eq 0 ]] || { echo "build-rust-refuses-loudly: FAILED" >&2; exit 1; }
echo "build-rust-refuses-loudly: OK (both import forms reach the crate; a refusal names the def; lists lower)"
