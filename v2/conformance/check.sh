#!/usr/bin/env bash
# Conformance for the ssc v2 runtime compiler (ssc0 -> ir -> ssc -> cpu).
# Builds one assembly jar, then exercises all three pipeline modes.
# bash 3.2 compatible (no associative arrays).
set -u
cd "$(dirname "$0")/.." || exit 2   # -> v2/

JAR="${TMPDIR:-/tmp}/ssc-conformance.jar"
echo "building ssc ..." >&2
scala-cli --power package src -o "$JAR" -f --assembly --server=false -q >/dev/null 2>&1 \
  || { echo "build failed"; exit 2; }
ssc() { java -jar "$JAR" "$@" 2>/dev/null; }

fail=0
chk() { # mode file want
  got=$(ssc "$1" "$2" | tail -1)
  if [ "$got" = "$3" ]; then printf 'ok   %-26s => %s\n' "$1 ${2##*/}" "$got"
  else printf 'FAIL %-26s got [%s] want [%s]\n' "$1 ${2##*/}" "$got" "$3"; fail=1; fi
}

echo "# ssc0 source -> ir -> run"
chk run examples/fact.ssc0 "120"
chk run examples/map.ssc0  "Cons(2, Cons(4, Cons(6, Nil)))"
chk run examples/tco.ssc0  "500000500000"

echo "# widened primitives (strings, BigInt, maps, Option)"
chk run examples/bigfact.ssc0 "265252859812191058636308480000000"
chk run examples/mapdemo.ssc0 "1"

echo "# multi-file import"
chk run examples/uselib.ssc0 "4950"

echo "# stdlib + an interpreter, written in ssc0"
chk run examples/pipeline.ssc0 "120"
chk run examples/calc.ssc0     "42"

echo "# algebraic effects + handlers (lib/effects.ssc0) — incl. MULTI-SHOT continuations"
chk run examples/effects-state.ssc0  "Pair(2, 2)"
chk run examples/effects-nondet.ssc0 "Cons(11, Cons(21, Cons(12, Cons(22, Nil))))"

echo "# async: cooperative scheduler on effects (lib/async.ssc0) — yield + fork"
chk run examples/async-tasks.ssc0 "Cons(1, Cons(10, Cons(2, Cons(20, Cons(3, Nil)))))"
chk run examples/async-fork.ssc0  "Cons(1, Cons(2, Cons(100, Cons(3, Cons(200, Nil)))))"

echo "# typeclasses: type-directed instance resolution + dict passing (lib/typeclass.ssc0)"
chk run examples/typeclass.ssc0        '"[1, 2, 3]"'
chk run examples/typeclass-nested.ssc0 '"[[1, 2], [3]]"'
chk run examples/typeclass-bool.ssc0   '"[true, false]"'

echo "# actors: message passing + per-actor behavior (lib/actors.ssc0)"
chk run examples/actors-pingpong.ssc0 "Cons(Ball(0), Cons(Ball(1), Cons(Ball(2), Cons(Ball(3), Cons(Ball(4), Cons(Ball(5), Nil))))))"

echo "# ssct — the typed layer (a type checker written in ssc0)"
chk run examples/typed.ssc0    'Typed("Int", 42)'
chk run examples/typed-fn.ssc0 'Typed("Int", 42)'
chk run examples/illtyped.ssc0 'TypeError("Add expects Int operands")'

echo "# ssct textual surface (.ssct text -> lex+parse+typecheck+run, all in ssc0)"
chk_ssct() { # file want
  got=$(ssc run bin/ssct.ssc0 "$1" | tail -1)
  if [ "$got" = "$2" ]; then printf 'ok   %-26s => %s\n' "ssct ${1##*/}" "$got"
  else printf 'FAIL %-26s got [%s] want [%s]\n' "ssct ${1##*/}" "$got" "$2"; fail=1; fi
}
chk_ssct examples/id.ssct   'Typed("Int", 42)'
chk_ssct examples/cond.ssct 'Typed("Int", 1)'
chk_ssct examples/bad.ssct  'TypeError("Add expects Int operands")'

echo "# ssctc: .ssct -> ir bytecode (erase + coreir.encode, all ssc0) -> run-ir on the VM"
bc=$(ssc run bin/ssctc.ssc0 examples/id.ssct)
want_bc='(program (defs) (entry (let ((lam 1 (prim i.add (local 0) (lit (int 1))))) (app (local 0) (app (local 0) (lit (int 40)))))))'
if [ "$bc" = "$want_bc" ]; then printf 'ok   %-26s => <canonical bytecode>\n' "ssctc id.ssct (emit)"
else printf 'FAIL %-26s bytecode mismatch\n' "ssctc id.ssct"; fail=1; fi
printf '%s\n' "$bc" > "${TMPDIR:-/tmp}/ssctc-id.coreir"
got=$(ssc run-ir "${TMPDIR:-/tmp}/ssctc-id.coreir" | tail -1)
if [ "$got" = "42" ]; then printf 'ok   %-26s => %s\n' "ssctc id -> run-ir" "$got"
else printf 'FAIL %-26s got [%s] want [42]\n' "ssctc id -> run-ir" "$got"; fail=1; fi

echo "# self-hosting: ssc0c (the ssc0 compiler, in ssc0) emits the SAME ir as the Scala compiler"
chk_diff() { # file-stem
  a=$(ssc compile "examples/$1.ssc0")
  b=$(ssc run bin/ssc0c.ssc0 "examples/$1.ssc0")
  if [ "$a" = "$b" ]; then printf 'ok   %-26s => byte-identical ir\n' "ssc0c $1.ssc0"
  else printf 'FAIL %-26s ir differs from Scala compiler\n' "ssc0c $1.ssc0"; fail=1; fi
}
chk_diff fact
chk_diff tco
chk_diff map
chk_diff calc
ssc run bin/ssc0c.ssc0 examples/fact.ssc0 > "${TMPDIR:-/tmp}/ssc0c-fact.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/ssc0c-fact.coreir" | tail -1)
if [ "$got" = "120" ]; then printf 'ok   %-26s => %s\n' "ssc0c fact -> run-ir" "$got"
else printf 'FAIL %-26s got [%s] want [120]\n' "ssc0c fact -> run-ir" "$got"; fail=1; fi

echo "# self-hosting FIXPOINT: ssc0c (built by the Scala front) run on its OWN source == itself"
# gen1 = Scala front compiles the self-compiler; gen2 = that bytecode compiling itself.
gen1=$(ssc compile examples/ssc0c-self.ssc0)
printf '%s' "$gen1" > "${TMPDIR:-/tmp}/ssc0c-gen1.ir"
# the VM needs a big stack for the self-compiler's deep non-tail recursion over its own source
gen2=$(java -Xss512m -jar "$JAR" run-ir "${TMPDIR:-/tmp}/ssc0c-gen1.ir" examples/ssc0c-self.ssc0 2>/dev/null)
if [ -n "$gen1" ] && [ "$gen1" = "$gen2" ]; then
  printf 'ok   %-26s => reproduces itself byte-for-byte (%s bytes)\n' "ssc0c FIXPOINT" "${#gen1}"
else
  printf 'FAIL %-26s gen1(%s) != gen2(%s)\n' "ssc0c FIXPOINT" "${#gen1}" "${#gen2}"; fail=1
fi

echo "# backend: ir -> JS (lib/backend-js.ssc0) — node output == VM output"
if command -v node >/dev/null 2>&1; then
  chk_js() { # file-stem
    vm=$(ssc run "examples/$1.ssc0" | tail -1)
    ssc run bin/ssc0-js.ssc0 "examples/$1.ssc0" > "${TMPDIR:-/tmp}/ssc0bk-$1.js" 2>/dev/null
    js=$(node "${TMPDIR:-/tmp}/ssc0bk-$1.js" 2>/dev/null | tail -1)
    if [ "$vm" = "$js" ]; then printf 'ok   %-26s => %s (node == vm)\n' "js $1.ssc0" "$js"
    else printf 'FAIL %-26s vm=[%s] node=[%s]\n' "js $1.ssc0" "$vm" "$js"; fail=1; fi
  }
  chk_js fact
  chk_js map
  chk_js calc
  chk_js tco    # now passes: the trampoline gives constant-stack tail calls
else
  echo "ok   js backend                => skipped (node not installed)"
fi

echo "# backend: ir -> Rust (lib/backend-rust.ssc0) — native binary output == VM output"
if command -v rustc >/dev/null 2>&1; then
  chk_rust() { # file-stem
    vm=$(ssc run "examples/$1.ssc0" | tail -1)
    ssc run bin/ssc0-rust.ssc0 "examples/$1.ssc0" > "${TMPDIR:-/tmp}/ssc0bk-$1.rs" 2>/dev/null
    if rustc -O "${TMPDIR:-/tmp}/ssc0bk-$1.rs" -o "${TMPDIR:-/tmp}/ssc0bk-$1" 2>/dev/null; then
      rs=$("${TMPDIR:-/tmp}/ssc0bk-$1")
      if [ "$vm" = "$rs" ]; then printf 'ok   %-26s => %s (rustc == vm)\n' "rust $1.ssc0" "$rs"
      else printf 'FAIL %-26s vm=[%s] rust=[%s]\n' "rust $1.ssc0" "$vm" "$rs"; fail=1; fi
    else printf 'FAIL %-26s rustc compile error\n' "rust $1.ssc0"; fail=1; fi
  }
  chk_rust fact
  chk_rust map
  chk_rust calc
  chk_rust tco    # now passes: the trampoline (Step::Bounce + app loop) gives constant stack
else
  echo "ok   rust backend              => skipped (rustc not installed)"
fi

echo "# ir bytecode -> run"
chk run-ir conformance/thunk.coreir  "42"
chk run-ir conformance/fact.coreir   "120"
chk run-ir conformance/map.coreir    "Cons(2, Cons(4, Cons(6, Nil)))"
chk run-ir conformance/letrec.coreir "true"
chk run-ir conformance/tco.coreir    "500000500000"

echo "# argv: ssc run <file> ARGS... -> #io.args()"
chkargv() { # want -- file args...
  want=$1; shift 2
  file=$1
  got=$(ssc run "$@" | tail -1)
  if [ "$got" = "$want" ]; then printf 'ok   %-26s => %s\n' "run ${file##*/} [${*:2}]" "$got"
  else printf 'FAIL %-26s got [%s] want [%s]\n' "run ${file##*/} [${*:2}]" "$got" "$want"; fail=1; fi
}
chkargv '"hello"'           -- examples/args.ssc0 hello world
chkargv '"(no args)"'       -- examples/args.ssc0
chkargv '"Hello, Sergiy!"'  -- examples/greet.ssc0 Sergiy

echo "# ssc0 -> ir reproduces the hand-written map def (15-ssc0 acceptance)"
mapdef='(def map (lam 2 (match (local 0) ((arm Nil 0 (ctor Nil)) (arm Cons 2 (ctor Cons (app (local 3) (local 1)) (app (global map) (local 3) (local 0))))))))'
if ssc compile examples/map.ssc0 | grep -qF "$mapdef"; then
  printf 'ok   %-26s => matches conformance/map.coreir\n' "compile map.ssc0"
else
  printf 'FAIL %-26s map def mismatch\n' "compile map.ssc0"; fail=1
fi

exit $fail
