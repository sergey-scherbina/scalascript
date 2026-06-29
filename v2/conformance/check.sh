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
chk run examples/quicksort.ssc0     "Cons(1, Cons(1, Cons(2, Cons(3, Cons(4, Cons(5, Nil))))))"
chk run examples/quicksort-lib.ssc0 "Cons(1, Cons(1, Cons(2, Cons(3, Cons(4, Cons(5, Nil))))))"
chk run examples/stdlib-demo.ssc0   '"0, 1, 2"'                       # strJoin + map + take + range
chk run examples/zipwith.ssc0       "Cons(11, Cons(22, Cons(33, Nil)))"
chk run examples/map-demo.ssc0      "Pair(2, 3)"                      # lib/map: insert/lookup/size
chk run examples/sieve.ssc0         "Cons(2, Cons(3, Cons(5, Cons(7, Cons(11, Cons(13, Cons(17, Cons(19, Cons(23, Cons(29, Nil))))))))))"  # mutable #arr.*
chk run examples/sha256-demo.ssc0   "4"                                # SHA-256 (lib/sha256.ssc0) vs 4 standard vectors incl. multi-block; VM-only (64-bit bitwise+byte+#arr)
chk run examples/string-build.ssc0  '"n=42"'                          # #sconcat + #i->str
chk run examples/stream-squares.ssc0 "Cons(1, Cons(4, Cons(9, Cons(16, Cons(25, Nil)))))"  # lazy infinite stream
chk run examples/letrec-fact.ssc0   "120"                            # local `let rec` -> Core IR letrec

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

echo "# typeclasses IN THE TYPER (ssct): show-e resolved from the inferred type; constraint enforced"
chk run examples/tc-show-int.ssc0  'Typed("String", "3")'
chk run examples/tc-show-bool.ssc0 'Typed("String", "true")'
chk run examples/tc-show-err.ssc0  'TypeError("no Show instance for the argument type")'
chk run examples/tc-eq-int.ssc0    'Typed("Bool", true)'
chk run examples/tc-eq-false.ssc0  'Typed("Bool", false)'
chk run examples/tc-eq-err.ssc0    'TypeError("eq: operands must be the same Eq-able type")'

echo "# ssct-hm: type INFERENCE (Algorithm W) — principal types for UNANNOTATED lambdas"
chk run examples/hm-inc.ssc0    '"(Int -> Int)"'                     # arg type deduced from use
chk run examples/hm-id.ssc0     '"(t0 -> t0)"'                       # polymorphic identity, no annotation
chk run examples/hm-app.ssc0    '"Int"'
chk run examples/hm-apply2.ssc0 '"((t1 -> t2) -> (t1 -> t2))"'       # f inferred to be a function
chk run examples/hm-err.ssc0    '"TypeError: Add needs Int operands"'
chk run examples/hm-letpoly.ssc0 '"(t2 -> t2)"'                       # let-poly: id id (occurs-error w/o generalization)
chk run examples/hm-letmono.ssc0 '"Int"'                             # same id reused at Int
chk run examples/hm-if.ssc0     '"(Bool -> Int)"'                    # Bool/If: cond forces Bool
chk run examples/hm-if-poly.ssc0 '"(Bool -> Bool)"'                  # x unified as Bool by cond AND branches
chk run examples/hm-if-err.ssc0 '"TypeError: if-condition must be Bool"'
chk run examples/hm-if-branch-err.ssc0 '"TypeError: if-branches must have the same type"'
chk run examples/hm-run-app.ssc0 'Typed("Int", 42)'                  # infer-then-RUN: well-typed evaluates
chk run examples/hm-run-if.ssc0  'Typed("Int", 10)'
chk run examples/hm-run-let.ssc0 'Typed("Int", 10)'
chk run examples/hm-run-err.ssc0 'TypeError("Add needs Int operands")'  # ill-typed rejected, never runs

echo "# ssct-hm POLYMORPHIC LISTS: infer [a], typecheck length/map, reject heterogeneous lists"
chk run examples/hm-nil.ssc0      '"[t0]"'                            # nil : forall a. [a]
chk run examples/hm-list.ssc0     'Typed("[Int]", Cons(1, Cons(2, Cons(3, Nil))))'
chk run examples/hm-list-err.ssc0 '"TypeError: list elements must have the same type"'
chk run examples/hm-length.ssc0   'Typed("Int", 3)'                  # length [1,2,3] (isNil/tail + recursion)
chk run examples/hm-map.ssc0      'Typed("[Int]", Cons(1, Cons(4, Cons(9, Nil))))'  # map (x*x) [1,2,3]

echo "# ssct-hm USER ADTs: one general ConApp/MatchT mechanism types Option/Either/Pair/Tree"
chk run examples/hm-adt-some.ssc0    '"Option Int"'                   # Some 5
chk run examples/hm-adt-none.ssc0    '"Option t0"'                    # None : polymorphic
chk run examples/hm-adt-pair.ssc0    '"(Int, Bool)"'                # Pair 1 true (Pair renders as a tuple type)
chk run examples/hm-adt-tree.ssc0    '"Tree Int"'                    # Node (Leaf 1) (Leaf 2)
chk run examples/hm-adt-match.ssc0   'Typed("Int", 5)'               # match (Some 5) { Some x => x; None => 0 }
chk run examples/hm-adt-treesum.ssc0 'Typed("Int", 6)'               # recursive fold over a Tree
chk run examples/hm-adt-err.ssc0     '"TypeError: match branches must have the same type"'
chk run examples/hm-adt-build.ssc0   'Typed("Option Int", "Some(5)")'
# ADTs COMPILE: tree-sum (Node/Leaf ctors + match + recursion) erases to Core IR and runs on all 3
ssc run examples/hm-adt-treesum-emit.ssc0 > "${TMPDIR:-/tmp}/ts.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/ts.coreir" | tail -1)
if [ "$got" = "6" ]; then printf 'ok   %-26s => %s\n' "adt tree-sum -> run-ir" "$got"; else printf 'FAIL %-26s got [%s] want [6]\n' "adt tree-sum" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run examples/hm-adt-match-js.ssc0 > "${TMPDIR:-/tmp}/ts.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/ts.js" 2>/dev/null | tail -1)
  if [ "$got" = "6" ]; then printf 'ok   %-26s => %s (node)\n' "adt tree-sum -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "adt tree-sum JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run examples/hm-adt-match-rust.ssc0 > "${TMPDIR:-/tmp}/ts.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/ts.rs" -o "${TMPDIR:-/tmp}/ts-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/ts-bin"); else got="(rustc err)"; fi
  if [ "$got" = "6" ]; then printf 'ok   %-26s => %s (rustc)\n' "adt tree-sum -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "adt tree-sum Rust" "$got"; fail=1; fi
fi

echo "# ssct-hm lists COMPILE: map/length erase to Core IR (ctor + Cons-match) and run on VM/JS/Rust"
LMAP="Cons(1, Cons(4, Cons(9, Nil)))"
ssc run examples/hm-length-emit.ssc0 > "${TMPDIR:-/tmp}/hm-length.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/hm-length.coreir" | tail -1)
if [ "$got" = "3" ]; then printf 'ok   %-26s => %s\n' "hm-length -> ir -> run-ir" "$got"; else printf 'FAIL %-26s got [%s] want [3]\n' "hm-length" "$got"; fail=1; fi
ssc run examples/hm-map-emit.ssc0 > "${TMPDIR:-/tmp}/hm-map.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/hm-map.coreir" | tail -1)
if [ "$got" = "$LMAP" ]; then printf 'ok   %-26s => %s\n' "hm-map -> ir -> run-ir" "$got"; else printf 'FAIL %-26s got [%s] want [%s]\n' "hm-map" "$got" "$LMAP"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run examples/hm-map-js.ssc0 > "${TMPDIR:-/tmp}/hm-map.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/hm-map.js" 2>/dev/null | tail -1)
  if [ "$got" = "$LMAP" ]; then printf 'ok   %-26s => %s (node)\n' "hm-map -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "hm-map JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run examples/hm-map-rust.ssc0 > "${TMPDIR:-/tmp}/hm-map.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/hm-map.rs" -o "${TMPDIR:-/tmp}/hm-map-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/hm-map-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$LMAP" ]; then printf 'ok   %-26s => %s (rustc)\n' "hm-map -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "hm-map Rust" "$got"; fail=1; fi
fi

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

echo "# ssct-hm textual surface (UNANNOTATED source text -> lex+parse+INFER, all in ssc0)"
chk_hm() { # file want
  got=$(ssc run bin/ssct-hm.ssc0 "$1" | tail -1)
  if [ "$got" = "$2" ]; then printf 'ok   %-26s => %s\n' "ssct-hm ${1##*/}" "$got"
  else printf 'FAIL %-26s got [%s] want [%s]\n' "ssct-hm ${1##*/}" "$got" "$2"; fail=1; fi
}
chk_hm examples/hm-src-inc.hm '"(Int -> Int)"'
chk_hm examples/hm-src-id.hm  '"(t0 -> t0)"'
chk_hm examples/hm-src-if.hm  '"(Bool -> Int)"'
chk_hm examples/hm-src-let.hm '"(t2 -> t2)"'
chk_hm examples/hm-src-err.hm '"TypeError: if-condition must be Bool"'
chk_hm examples/hm-fact.hm    '"Int"'                                # let rec + * - = in source text
chk_hm examples/hm-eqfun.hm   '"(Int -> (Int -> Int))"'              # '=' forces both operands Int

echo "# ssctc: .ssct -> ir bytecode (erase + coreir.encode, all ssc0) -> run-ir on the VM"
bc=$(ssc run bin/ssctc.ssc0 examples/id.ssct)
want_bc='(program (defs) (entry (let ((lam 1 (prim i.add (local 0) (lit (int 1))))) (app (local 0) (app (local 0) (lit (int 40)))))))'
if [ "$bc" = "$want_bc" ]; then printf 'ok   %-26s => <canonical bytecode>\n' "ssctc id.ssct (emit)"
else printf 'FAIL %-26s bytecode mismatch\n' "ssctc id.ssct"; fail=1; fi
printf '%s\n' "$bc" > "${TMPDIR:-/tmp}/ssctc-id.coreir"
got=$(ssc run-ir "${TMPDIR:-/tmp}/ssctc-id.coreir" | tail -1)
if [ "$got" = "42" ]; then printf 'ok   %-26s => %s\n' "ssctc id -> run-ir" "$got"
else printf 'FAIL %-26s got [%s] want [42]\n' "ssctc id -> run-ir" "$got"; fail=1; fi

echo "# ssctc-hm: INFERRED-typed source text -> ir bytecode (all ssc0) -> run-ir on the VM"
hbc=$(ssc run bin/ssctc-hm.ssc0 examples/hm-prog.hm)
want_hbc='(program (defs) (entry (app (lam 1 (prim i.add (local 0) (lit (int 1)))) (lit (int 41)))))'
if [ "$hbc" = "$want_hbc" ]; then printf 'ok   %-26s => <canonical bytecode>\n' "ssctc-hm hm-prog (emit)"
else printf 'FAIL %-26s bytecode mismatch [%s]\n' "ssctc-hm hm-prog" "$hbc"; fail=1; fi
printf '%s\n' "$hbc" > "${TMPDIR:-/tmp}/ssctc-hm-prog.coreir"
got=$(ssc run-ir "${TMPDIR:-/tmp}/ssctc-hm-prog.coreir" | tail -1)
if [ "$got" = "42" ]; then printf 'ok   %-26s => %s\n' "ssctc-hm prog -> run-ir" "$got"
else printf 'FAIL %-26s got [%s] want [42]\n' "ssctc-hm prog -> run-ir" "$got"; fail=1; fi

echo "# ssct-hm RECURSION: factorial (let rec + mul/sub/eq) infers Int, runs, AND compiles to letrec ir"
chk run examples/hm-fact.ssc0 'Typed("Int", 120)'        # interpreter: HM infer + evaluate
ssc run examples/hm-fact-emit.ssc0 > "${TMPDIR:-/tmp}/hm-fact.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/hm-fact.coreir" | tail -1)
if [ "$got" = "120" ]; then printf 'ok   %-26s => %s\n' "hm-fact -> ir -> run-ir" "$got"
else printf 'FAIL %-26s got [%s] want [120]\n' "hm-fact -> ir -> run-ir" "$got"; fail=1; fi
# the SAME factorial, written as SOURCE TEXT, compiled by ssctc-hm and run on the VM
ssc run bin/ssctc-hm.ssc0 examples/hm-fact.hm > "${TMPDIR:-/tmp}/hm-fact-text.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/hm-fact-text.coreir" | tail -1)
if [ "$got" = "120" ]; then printf 'ok   %-26s => %s\n' "ssctc-hm fact.hm -> run-ir" "$got"
else printf 'FAIL %-26s got [%s] want [120]\n' "ssctc-hm fact.hm -> run-ir" "$got"; fail=1; fi
# and the SAME factorial compiled to JavaScript (shared codegen reused via ssct-hm-js) and run on node
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-fact.hm > "${TMPDIR:-/tmp}/hm-fact.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/hm-fact.js" 2>/dev/null | tail -1)
  if [ "$got" = "120" ]; then printf 'ok   %-26s => %s (node)\n' "ssct-hm-js fact.hm" "$got"
  else printf 'FAIL %-26s got [%s] want [120]\n' "ssct-hm-js fact.hm" "$got"; fail=1; fi
fi
# and the SAME factorial compiled to native Rust (shared Rust codegen reused via ssct-hm-rust)
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-fact.hm > "${TMPDIR:-/tmp}/hm-fact.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/hm-fact.rs" -o "${TMPDIR:-/tmp}/hm-fact-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/hm-fact-bin"); else got="(rustc err)"; fi
  if [ "$got" = "120" ]; then printf 'ok   %-26s => %s (rustc)\n' "ssct-hm-rust fact.hm" "$got"
  else printf 'FAIL %-26s got [%s] want [120]\n' "ssct-hm-rust fact.hm" "$got"; fail=1; fi
fi

echo "# ssct-hm TEXTUAL LISTS: map written as source text (nil/cons/head/tail/isNil) -> all 3 backends"
chk_hm examples/hm-map.hm '"[Int]"'
chk_hm examples/hm-listlit.hm '"[Int]"'                            # [1, 2, 3] list-literal syntax
echo "# ssct-hm STRINGS: String type + literals + ++ (concat) + showInt, on every backend"
chk_hm examples/hm-string.hm '"String"'
SW='"n=42"'
ssc run bin/ssctc-hm.ssc0 examples/hm-string.hm > "${TMPDIR:-/tmp}/sw.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/sw.coreir" | tail -1)
if [ "$got" = "$SW" ]; then printf 'ok   %-26s => %s\n' "string -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "string" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-string.hm > "${TMPDIR:-/tmp}/sw.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/sw.js" 2>/dev/null | tail -1)
  if [ "$got" = "$SW" ]; then printf 'ok   %-26s => %s (node)\n' "string -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "string JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-string.hm > "${TMPDIR:-/tmp}/sw.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/sw.rs" -o "${TMPDIR:-/tmp}/sw-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/sw-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$SW" ]; then printf 'ok   %-26s => %s (rustc)\n' "string -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "string Rust" "$got"; fail=1; fi
fi
echo "# ssct-hm FLOAT: Float type + literals + fadd/fsub/fmul/fdiv/flt/feq + toFloat, on every backend"
chk_hm examples/hm-float.hm '"Float"'
FW="2.5"
ssc run bin/ssctc-hm.ssc0 examples/hm-float.hm > "${TMPDIR:-/tmp}/fw.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/fw.coreir" | tail -1)
if [ "$got" = "$FW" ]; then printf 'ok   %-26s => %s\n' "float -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "float" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-float.hm > "${TMPDIR:-/tmp}/fw.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/fw.js" 2>/dev/null | tail -1)
  if [ "$got" = "$FW" ]; then printf 'ok   %-26s => %s (node)\n' "float -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "float JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-float.hm > "${TMPDIR:-/tmp}/fw.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/fw.rs" -o "${TMPDIR:-/tmp}/fw-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/fw-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$FW" ]; then printf 'ok   %-26s => %s (rustc)\n' "float -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "float Rust" "$got"; fail=1; fi
fi
echo "# ssct-hm TUPLES: (a, b) / (a, b, c) syntax + fst/snd, on every backend"
chk_hm examples/hm-tuptype.hm '"(Int, Bool)"'                       # (1, true) renders as a tuple type
TW="10"
ssc run bin/ssctc-hm.ssc0 examples/hm-tuple.hm > "${TMPDIR:-/tmp}/tp.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/tp.coreir" | tail -1)
if [ "$got" = "$TW" ]; then printf 'ok   %-26s => %s\n' "tuple fst/snd -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "tuple" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-tuple.hm > "${TMPDIR:-/tmp}/tp.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/tp.js" 2>/dev/null | tail -1)
  if [ "$got" = "$TW" ]; then printf 'ok   %-26s => %s (node)\n' "tuple -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "tuple JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-tuple.hm > "${TMPDIR:-/tmp}/tp.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/tp.rs" -o "${TMPDIR:-/tmp}/tp-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/tp-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$TW" ]; then printf 'ok   %-26s => %s (rustc)\n' "tuple -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "tuple Rust" "$got"; fail=1; fi
fi
echo "# ssct-hm TYPECLASS Show: one `show` resolves to the instance for the inferred type, on every backend"
chk_hm examples/hm-show.hm '"String"'
SHW='"x=42"'
ssc run bin/ssctc-hm.ssc0 examples/hm-show.hm > "${TMPDIR:-/tmp}/shw.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/shw.coreir" | tail -1)
if [ "$got" = "$SHW" ]; then printf 'ok   %-26s => %s\n' "show Int -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "show" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-show.hm > "${TMPDIR:-/tmp}/shw.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/shw.js" 2>/dev/null | tail -1)
  if [ "$got" = "$SHW" ]; then printf 'ok   %-26s => %s (node)\n' "show Int -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "show JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-show.hm > "${TMPDIR:-/tmp}/shw.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/shw.rs" -o "${TMPDIR:-/tmp}/shw-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/shw-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$SHW" ]; then printf 'ok   %-26s => %s (rustc)\n' "show Int -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "show Rust" "$got"; fail=1; fi
fi
echo "# ssct-hm Show over LISTS: recursive type-directed (show [Int]/[[Int]]/[Bool]) on every backend"
chk_hm examples/hm-showlist.hm '"String"'
SLW='"[1, 2, 3]"'
ssc run bin/ssctc-hm.ssc0 examples/hm-showlist.hm > "${TMPDIR:-/tmp}/sl.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/sl.coreir" | tail -1)
if [ "$got" = "$SLW" ]; then printf 'ok   %-26s => %s\n' "show [Int] -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "showlist" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-showlist.hm > "${TMPDIR:-/tmp}/sl.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/sl.js" 2>/dev/null | tail -1)
  if [ "$got" = "$SLW" ]; then printf 'ok   %-26s => %s (node)\n' "show [Int] -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "showlist JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-showlist.hm > "${TMPDIR:-/tmp}/sl.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/sl.rs" -o "${TMPDIR:-/tmp}/sl-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/sl-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$SLW" ]; then printf 'ok   %-26s => %s (rustc)\n' "show [Int] -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "showlist Rust" "$got"; fail=1; fi
fi
echo "# ssct-hm Eq over LISTS: eq [Int]/[[Int]] structural, recursive type-directed, on every backend"
ELW="1"
ssc run bin/ssctc-hm.ssc0 examples/hm-eqlist.hm > "${TMPDIR:-/tmp}/el.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/el.coreir" | tail -1)
if [ "$got" = "$ELW" ]; then printf 'ok   %-26s => %s\n' "eq [Int] -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "eqlist" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-eqlist.hm > "${TMPDIR:-/tmp}/el.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/el.js" 2>/dev/null | tail -1)
  if [ "$got" = "$ELW" ]; then printf 'ok   %-26s => %s (node)\n' "eq [Int] -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "eqlist JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-eqlist.hm > "${TMPDIR:-/tmp}/el.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/el.rs" -o "${TMPDIR:-/tmp}/el-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/el-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$ELW" ]; then printf 'ok   %-26s => %s (rustc)\n' "eq [Int] -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "eqlist Rust" "$got"; fail=1; fi
fi
echo "# ssct-hm Show/Eq over TUPLES: show (1,true) / eq (1,2) (1,2), type-directed, on every backend"
STW='"(1, true)"'
ssc run bin/ssctc-hm.ssc0 examples/hm-showtup.hm > "${TMPDIR:-/tmp}/st.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/st.coreir" | tail -1)
if [ "$got" = "$STW" ]; then printf 'ok   %-26s => %s\n' "show tuple -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "showtup" "$got"; fail=1; fi
ssc run bin/ssctc-hm.ssc0 examples/hm-eqtup.hm > "${TMPDIR:-/tmp}/et.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/et.coreir" | tail -1)
if [ "$got" = "1" ]; then printf 'ok   %-26s => %s\n' "eq tuple -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "eqtup" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-showtup.hm > "${TMPDIR:-/tmp}/st.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/st.js" 2>/dev/null | tail -1)
  if [ "$got" = "$STW" ]; then printf 'ok   %-26s => %s (node)\n' "show tuple -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "showtup JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-showtup.hm > "${TMPDIR:-/tmp}/st.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/st.rs" -o "${TMPDIR:-/tmp}/st-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/st-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$STW" ]; then printf 'ok   %-26s => %s (rustc)\n' "show tuple -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "showtup Rust" "$got"; fail=1; fi
fi
echo "# ssct-hm Show/Eq over Option/Either: show (Some 5)/eq, type-directed, on every backend"
SOW='"Some(5)"'
ssc run bin/ssctc-hm.ssc0 examples/hm-showopt.hm > "${TMPDIR:-/tmp}/so.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/so.coreir" | tail -1)
if [ "$got" = "$SOW" ]; then printf 'ok   %-26s => %s\n' "show Option -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "showopt" "$got"; fail=1; fi
ssc run bin/ssctc-hm.ssc0 examples/hm-eqopt.hm > "${TMPDIR:-/tmp}/eo.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/eo.coreir" | tail -1)
if [ "$got" = "1" ]; then printf 'ok   %-26s => %s\n' "eq Option -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "eqopt" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-showopt.hm > "${TMPDIR:-/tmp}/so.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/so.js" 2>/dev/null | tail -1)
  if [ "$got" = "$SOW" ]; then printf 'ok   %-26s => %s (node)\n' "show Option -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "showopt JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-showopt.hm > "${TMPDIR:-/tmp}/so.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/so.rs" -o "${TMPDIR:-/tmp}/so-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/so-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$SOW" ]; then printf 'ok   %-26s => %s (rustc)\n' "show Option -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "showopt Rust" "$got"; fail=1; fi
fi
echo "# ssct-hm Show/Eq over GENERAL/RECURSIVE ADTs: Tree + user enums, via per-type recursive helpers"
STW='"Node(Leaf(1), Node(Leaf(2), Leaf(3)))"'
ssc run bin/ssctc-hm.ssc0 examples/hm-showtree.hm > "${TMPDIR:-/tmp}/tr.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/tr.coreir" | tail -1)
if [ "$got" = "$STW" ]; then printf 'ok   %-26s => %s\n' "show Tree -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "showtree" "$got"; fail=1; fi
ssc run bin/ssctc-hm.ssc0 examples/hm-eqtree.hm > "${TMPDIR:-/tmp}/etr.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/etr.coreir" | tail -1)
if [ "$got" = "1" ]; then printf 'ok   %-26s => %s\n' "eq Tree -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "eqtree" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-showtree.hm > "${TMPDIR:-/tmp}/tr.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/tr.js" 2>/dev/null | tail -1)
  if [ "$got" = "$STW" ]; then printf 'ok   %-26s => %s (node)\n' "show Tree -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "showtree JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-showtree.hm > "${TMPDIR:-/tmp}/tr.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/tr.rs" -o "${TMPDIR:-/tmp}/tr-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/tr-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$STW" ]; then printf 'ok   %-26s => %s (rustc)\n' "show Tree -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "showtree Rust" "$got"; fail=1; fi
fi
echo "# ssct-hm TYPECLASS Eq/Ord: eq (4 base types) + compare (Int+Float) resolved by type"
ssc run bin/ssctc-hm.ssc0 examples/hm-cmp.hm > "${TMPDIR:-/tmp}/cmp.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/cmp.coreir" | tail -1)
if [ "$got" = "1" ]; then printf 'ok   %-26s => %s\n' "compare 5 3 -> run-ir" "$got"; else printf 'FAIL %-26s got [%s] want [1]\n' "compare" "$got"; fail=1; fi
EQW="1"
ssc run bin/ssctc-hm.ssc0 examples/hm-eq.hm > "${TMPDIR:-/tmp}/eq.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/eq.coreir" | tail -1)
if [ "$got" = "$EQW" ]; then printf 'ok   %-26s => %s\n' "eq String -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "eq" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-eq.hm > "${TMPDIR:-/tmp}/eq.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/eq.js" 2>/dev/null | tail -1)
  if [ "$got" = "$EQW" ]; then printf 'ok   %-26s => %s (node)\n' "eq -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "eq JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-eq.hm > "${TMPDIR:-/tmp}/eq.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/eq.rs" -o "${TMPDIR:-/tmp}/eq-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/eq-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$EQW" ]; then printf 'ok   %-26s => %s (rustc)\n' "eq -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "eq Rust" "$got"; fail=1; fi
fi
echo "# ssct-hm USER TYPECLASSES: `method m` + `instance m T = impl`, m resolves by arg type-head, on every backend"
chk_hm examples/hm-userclass.hm '"String"'
UCW='"int"'
ssc run bin/ssctc-hm.ssc0 examples/hm-userclass.hm > "${TMPDIR:-/tmp}/uc.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/uc.coreir" | tail -1)
if [ "$got" = "$UCW" ]; then printf 'ok   %-26s => %s\n' "describe 5 -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "userclass" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-userclass.hm > "${TMPDIR:-/tmp}/uc.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/uc.js" 2>/dev/null | tail -1)
  if [ "$got" = "$UCW" ]; then printf 'ok   %-26s => %s (node)\n' "describe 5 -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "userclass JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-userclass.hm > "${TMPDIR:-/tmp}/uc.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/uc.rs" -o "${TMPDIR:-/tmp}/uc-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/uc-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$UCW" ]; then printf 'ok   %-26s => %s (rustc)\n' "describe 5 -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "userclass Rust" "$got"; fail=1; fi
fi
echo "# ssct-hm USER TYPECLASS over a user data type: instance name Color resolves + dispatches the impl"
chk_hm examples/hm-userclass-color.hm '"String"'
UCC='"green"'
ssc run bin/ssctc-hm.ssc0 examples/hm-userclass-color.hm > "${TMPDIR:-/tmp}/ucc.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/ucc.coreir" | tail -1)
if [ "$got" = "$UCC" ]; then printf 'ok   %-26s => %s\n' "name Green -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "userclass-color" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-userclass-color.hm > "${TMPDIR:-/tmp}/ucc.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/ucc.js" 2>/dev/null | tail -1)
  if [ "$got" = "$UCC" ]; then printf 'ok   %-26s => %s (node)\n' "name Green -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "userclass-color JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-userclass-color.hm > "${TMPDIR:-/tmp}/ucc.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/ucc.rs" -o "${TMPDIR:-/tmp}/ucc-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/ucc-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$UCC" ]; then printf 'ok   %-26s => %s (rustc)\n' "name Green -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "userclass-color Rust" "$got"; fail=1; fi
fi
echo "# ssct-hm RECORDS: { x = e, y = e } literals + r.x access (structural record types), 3 backends"
chk_hm examples/hm-rectype.hm '"{x: Int, y: Bool}"'
RW="10"
ssc run bin/ssctc-hm.ssc0 examples/hm-record.hm > "${TMPDIR:-/tmp}/rec.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/rec.coreir" | tail -1)
if [ "$got" = "$RW" ]; then printf 'ok   %-26s => %s\n' "record p.x+p.y -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "record" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-record.hm > "${TMPDIR:-/tmp}/rec.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/rec.js" 2>/dev/null | tail -1)
  if [ "$got" = "$RW" ]; then printf 'ok   %-26s => %s (node)\n' "record -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "record JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-record.hm > "${TMPDIR:-/tmp}/rec.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/rec.rs" -o "${TMPDIR:-/tmp}/rec-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/rec-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$RW" ]; then printf 'ok   %-26s => %s (rustc)\n' "record -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "record Rust" "$got"; fail=1; fi
fi
echo "# ssct-hm ARITH: div/mod/neg (Int) + fneg/fsqrt (Float), on every backend"
chk_hm examples/hm-arith.hm '"Int"'
AW="2"
ssc run bin/ssctc-hm.ssc0 examples/hm-arith.hm > "${TMPDIR:-/tmp}/ar.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/ar.coreir" | tail -1)
if [ "$got" = "$AW" ]; then printf 'ok   %-26s => %s\n' "mod 17 5 -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "arith" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-arith.hm > "${TMPDIR:-/tmp}/ar.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/ar.js" 2>/dev/null | tail -1)
  if [ "$got" = "$AW" ]; then printf 'ok   %-26s => %s (node)\n' "arith -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "arith JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-arith.hm > "${TMPDIR:-/tmp}/ar.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/ar.rs" -o "${TMPDIR:-/tmp}/ar-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/ar-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$AW" ]; then printf 'ok   %-26s => %s (rustc)\n' "arith -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "arith Rust" "$got"; fail=1; fi
fi
echo "# ssct-hm SHOWCASE: a typed arithmetic-expression interpreter, written in ssct-hm, on 3 backends"
chk_hm examples/hm-eval.hm '"Int"'                                  # data Expr = Num | Plus | Times ; eval
ssc run bin/ssctc-hm.ssc0 examples/hm-eval.hm > "${TMPDIR:-/tmp}/ev.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/ev.coreir" | tail -1)
if [ "$got" = "7" ]; then printf 'ok   %-26s => %s\n' "eval interp -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "eval" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-eval.hm > "${TMPDIR:-/tmp}/ev.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/ev.js" 2>/dev/null | tail -1)
  if [ "$got" = "7" ]; then printf 'ok   %-26s => %s (node)\n' "eval interp -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "eval JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-eval.hm > "${TMPDIR:-/tmp}/ev.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/ev.rs" -o "${TMPDIR:-/tmp}/ev-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/ev-bin"); else got="(rustc err)"; fi
  if [ "$got" = "7" ]; then printf 'ok   %-26s => %s (rustc)\n' "eval interp -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "eval Rust" "$got"; fail=1; fi
fi
chk_hm examples/hm-streq.hm '"Int"'                                 # strEq (string equality)
ssc run bin/ssctc-hm.ssc0 examples/hm-streq.hm > "${TMPDIR:-/tmp}/se.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/se.coreir" | tail -1)
if [ "$got" = "1" ]; then printf 'ok   %-26s => %s\n' "strEq -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "strEq" "$got"; fail=1; fi
echo "# ssct-hm Ord operators: > <= >= <> derived from </=/if (consistent on all backends)"
chk_hm examples/hm-ord.hm '"Int"'
ssc run bin/ssctc-hm.ssc0 examples/hm-ord.hm > "${TMPDIR:-/tmp}/o.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/o.coreir" | tail -1)
if [ "$got" = "42" ]; then printf 'ok   %-26s => %s\n' "ord (>= <> <=) -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "ord" "$got"; fail=1; fi
QS2="Cons(1, Cons(1, Cons(2, Cons(3, Cons(4, Nil)))))"
ssc run bin/ssctc-hm.ssc0 examples/hm-qsort2.hm > "${TMPDIR:-/tmp}/q2.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/q2.coreir" | tail -1)
if [ "$got" = "$QS2" ]; then printf 'ok   %-26s => %s\n' "qsort dup (>=) -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "qsort dup" "$got"; fail=1; fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-qsort2.hm > "${TMPDIR:-/tmp}/q2.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/q2.rs" -o "${TMPDIR:-/tmp}/q2-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/q2-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$QS2" ]; then printf 'ok   %-26s => %s (rustc)\n' "qsort dup -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "qsort dup Rust" "$got"; fail=1; fi
fi
echo "# ssct-hm BOOLEAN operators: && (tighter) / || (looser) / not — desugar to If, consistent on all backends"
chk_hm examples/hm-bool.hm '"Bool"'
BLW="true"
ssc run bin/ssctc-hm.ssc0 examples/hm-bool.hm > "${TMPDIR:-/tmp}/hb.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/hb.coreir" | tail -1)
if [ "$got" = "$BLW" ]; then printf 'ok   %-26s => %s\n' "and/or/not -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "bool" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-bool.hm > "${TMPDIR:-/tmp}/hb.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/hb.js" 2>/dev/null | tail -1)
  if [ "$got" = "$BLW" ]; then printf 'ok   %-26s => %s (node)\n' "and/or/not -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "bool JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-bool.hm > "${TMPDIR:-/tmp}/hb.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/hb.rs" -o "${TMPDIR:-/tmp}/hb-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/hb-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$BLW" ]; then printf 'ok   %-26s => %s (rustc)\n' "and/or/not -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "bool Rust" "$got"; fail=1; fi
fi
echo "# ssct-hm STRING ops: strLen / charAt / substr (typed builtins -> slen/scodeAt/sslice) on every backend"
chk_hm examples/hm-strops.hm '"String"'
SOW='"hello/11/119"'
ssc run bin/ssctc-hm.ssc0 examples/hm-strops.hm > "${TMPDIR:-/tmp}/sop.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/sop.coreir" | tail -1)
if [ "$got" = "$SOW" ]; then printf 'ok   %-26s => %s\n' "strLen/charAt/substr -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "strops" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-strops.hm > "${TMPDIR:-/tmp}/sop.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/sop.js" 2>/dev/null | tail -1)
  if [ "$got" = "$SOW" ]; then printf 'ok   %-26s => %s (node)\n' "strLen/charAt/substr -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "strops JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-strops.hm > "${TMPDIR:-/tmp}/sop.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/sop.rs" -o "${TMPDIR:-/tmp}/sop-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/sop-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$SOW" ]; then printf 'ok   %-26s => %s (rustc)\n' "strLen/charAt/substr -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "strops Rust" "$got"; fail=1; fi
fi
echo "# ssct-hm CURRY sugar: multi-arg 'fun x y => e' + function-def 'let f x y = e' / 'let rec f n = e'"
chk_hm examples/hm-curry.hm '"Int"'
CUW="132"
ssc run bin/ssctc-hm.ssc0 examples/hm-curry.hm > "${TMPDIR:-/tmp}/cur.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/cur.coreir" | tail -1)
if [ "$got" = "$CUW" ]; then printf 'ok   %-26s => %s\n' "curry sugar -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "curry" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-curry.hm > "${TMPDIR:-/tmp}/cur.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/cur.js" 2>/dev/null | tail -1)
  if [ "$got" = "$CUW" ]; then printf 'ok   %-26s => %s (node)\n' "curry sugar -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "curry JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-curry.hm > "${TMPDIR:-/tmp}/cur.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/cur.rs" -o "${TMPDIR:-/tmp}/cur-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/cur-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$CUW" ]; then printf 'ok   %-26s => %s (rustc)\n' "curry sugar -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "curry Rust" "$got"; fail=1; fi
fi
echo "# ssct-hm WILDCARD match: 'case _ => e' catch-all arm -> IrMatch default slot, on every backend"
chk_hm examples/hm-wildcard.hm '"String"'
WCW='"red/other/other"'
ssc run bin/ssctc-hm.ssc0 examples/hm-wildcard.hm > "${TMPDIR:-/tmp}/wc.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/wc.coreir" | tail -1)
if [ "$got" = "$WCW" ]; then printf 'ok   %-26s => %s\n' "wildcard match -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "wildcard" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-wildcard.hm > "${TMPDIR:-/tmp}/wc.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/wc.js" 2>/dev/null | tail -1)
  if [ "$got" = "$WCW" ]; then printf 'ok   %-26s => %s (node)\n' "wildcard match -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "wildcard JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-wildcard.hm > "${TMPDIR:-/tmp}/wc.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/wc.rs" -o "${TMPDIR:-/tmp}/wc-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/wc-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$WCW" ]; then printf 'ok   %-26s => %s (rustc)\n' "wildcard match -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "wildcard Rust" "$got"; fail=1; fi
fi
echo "# ssct-hm LINE COMMENTS: '// ...' to end of line (lexer skips; strings keep their slashes), all backends"
chk_hm examples/hm-comments.hm '"Int"'
CMW="120"
ssc run bin/ssctc-hm.ssc0 examples/hm-comments.hm > "${TMPDIR:-/tmp}/cmt.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/cmt.coreir" | tail -1)
if [ "$got" = "$CMW" ]; then printf 'ok   %-26s => %s\n' "comments -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "comments" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-comments.hm > "${TMPDIR:-/tmp}/cmt.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/cmt.js" 2>/dev/null | tail -1)
  if [ "$got" = "$CMW" ]; then printf 'ok   %-26s => %s (node)\n' "comments -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "comments JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-comments.hm > "${TMPDIR:-/tmp}/cmt.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/cmt.rs" -o "${TMPDIR:-/tmp}/cmt-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/cmt-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$CMW" ]; then printf 'ok   %-26s => %s (rustc)\n' "comments -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "comments Rust" "$got"; fail=1; fi
fi
echo "# ssct-hm VARIABLE pattern: 'case x => body' binds the whole scrutinee (let x = scrut in match _), all backends"
chk_hm examples/hm-varpat.hm '"String"'
VPW='"C/B/C"'
ssc run bin/ssctc-hm.ssc0 examples/hm-varpat.hm > "${TMPDIR:-/tmp}/vp.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/vp.coreir" | tail -1)
if [ "$got" = "$VPW" ]; then printf 'ok   %-26s => %s\n' "var pattern -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "varpat" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-varpat.hm > "${TMPDIR:-/tmp}/vp.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/vp.js" 2>/dev/null | tail -1)
  if [ "$got" = "$VPW" ]; then printf 'ok   %-26s => %s (node)\n' "var pattern -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "varpat JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-varpat.hm > "${TMPDIR:-/tmp}/vp.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/vp.rs" -o "${TMPDIR:-/tmp}/vp-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/vp-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$VPW" ]; then printf 'ok   %-26s => %s (rustc)\n' "var pattern -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "varpat Rust" "$got"; fail=1; fi
fi
echo "# ssct-hm PRELUDE: map/filter/foldr/foldl/append/reverse/length/sum/range auto-injected ONLY when used free"
chk_hm examples/hm-prelude.hm '"Int"'
PLW="14"
ssc run bin/ssctc-hm.ssc0 examples/hm-prelude.hm > "${TMPDIR:-/tmp}/pl.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/pl.coreir" | tail -1)
if [ "$got" = "$PLW" ]; then printf 'ok   %-26s => %s\n' "prelude compose -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "prelude" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-prelude.hm > "${TMPDIR:-/tmp}/pl.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/pl.js" 2>/dev/null | tail -1)
  if [ "$got" = "$PLW" ]; then printf 'ok   %-26s => %s (node)\n' "prelude compose -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "prelude JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-prelude.hm > "${TMPDIR:-/tmp}/pl.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/pl.rs" -o "${TMPDIR:-/tmp}/pl-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/pl-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$PLW" ]; then printf 'ok   %-26s => %s (rustc)\n' "prelude compose -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "prelude Rust" "$got"; fail=1; fi
fi
# the prelude must NOT perturb a program that defines its own helper (free-var scan excludes bound names)
ssc run bin/ssctc-hm.ssc0 examples/hm-map.hm > "${TMPDIR:-/tmp}/plchk.coreir" 2>/dev/null
nlet=$(ssc run bin/ssctc-hm.ssc0 examples/hm-map.hm 2>/dev/null | grep -o 'letrec' | wc -l | tr -d ' ')
if [ "$nlet" = "1" ]; then printf 'ok   %-26s => %s\n' "prelude not injected (own map)" "1 letrec"; else printf 'FAIL %-26s got [%s letrec]\n' "prelude over-inject" "$nlet"; fail=1; fi
echo "# ssct-hm PRELUDE (more): take/drop/zip/replicate/all/any — pure polymorphic list functions"
chk_hm examples/hm-prelude2.hm '"Int"'
PL2="2"
ssc run bin/ssctc-hm.ssc0 examples/hm-prelude2.hm > "${TMPDIR:-/tmp}/pl2.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/pl2.coreir" | tail -1)
if [ "$got" = "$PL2" ]; then printf 'ok   %-26s => %s\n' "take/zip/replicate -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "prelude2" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-prelude2.hm > "${TMPDIR:-/tmp}/pl2.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/pl2.js" 2>/dev/null | tail -1)
  if [ "$got" = "$PL2" ]; then printf 'ok   %-26s => %s (node)\n' "take/zip/replicate -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "prelude2 JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-prelude2.hm > "${TMPDIR:-/tmp}/pl2.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/pl2.rs" -o "${TMPDIR:-/tmp}/pl2-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/pl2-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$PL2" ]; then printf 'ok   %-26s => %s (rustc)\n' "take/zip/replicate -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "prelude2 Rust" "$got"; fail=1; fi
fi
echo "# ssct-hm LITERAL Int patterns: 'match n { 0 => .. | 1 => .. | _ => .. }' -> if-chain, all backends"
chk_hm examples/hm-litpat.hm '"Int"'
LPW="55"
ssc run bin/ssctc-hm.ssc0 examples/hm-litpat.hm > "${TMPDIR:-/tmp}/lp.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/lp.coreir" | tail -1)
if [ "$got" = "$LPW" ]; then printf 'ok   %-26s => %s\n' "fib (lit patterns) -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "litpat" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-litpat.hm > "${TMPDIR:-/tmp}/lp.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/lp.js" 2>/dev/null | tail -1)
  if [ "$got" = "$LPW" ]; then printf 'ok   %-26s => %s (node)\n' "fib (lit patterns) -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "litpat JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-litpat.hm > "${TMPDIR:-/tmp}/lp.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/lp.rs" -o "${TMPDIR:-/tmp}/lp-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/lp-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$LPW" ]; then printf 'ok   %-26s => %s (rustc)\n' "fib (lit patterns) -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "litpat Rust" "$got"; fail=1; fi
fi
echo "# ssct-hm STRING/BOOL literal patterns: 'match s { \"add\" => .. | _ => .. }' (strEq) / true|false (if)"
chk_hm examples/hm-litpat-str.hm '"Int"'
LSW="45"
ssc run bin/ssctc-hm.ssc0 examples/hm-litpat-str.hm > "${TMPDIR:-/tmp}/ls.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/ls.coreir" | tail -1)
if [ "$got" = "$LSW" ]; then printf 'ok   %-26s => %s\n' "string dispatch -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "litpat-str" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-litpat-str.hm > "${TMPDIR:-/tmp}/ls.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/ls.js" 2>/dev/null | tail -1)
  if [ "$got" = "$LSW" ]; then printf 'ok   %-26s => %s (node)\n' "string dispatch -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "litpat-str JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-litpat-str.hm > "${TMPDIR:-/tmp}/ls.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/ls.rs" -o "${TMPDIR:-/tmp}/ls-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/ls-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$LSW" ]; then printf 'ok   %-26s => %s (rustc)\n' "string dispatch -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "litpat-str Rust" "$got"; fail=1; fi
fi
echo "# ssct-hm NESTED patterns: Con (Sub ..) / tuple / list (Cons/Nil) / literal sub-patterns, backtracking"
chk_hm examples/hm-nestpat.hm '"Int"'
NPW="15"
ssc run bin/ssctc-hm.ssc0 examples/hm-nestpat.hm > "${TMPDIR:-/tmp}/ne.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/ne.coreir" | tail -1)
if [ "$got" = "$NPW" ]; then printf 'ok   %-26s => %s\n' "nested patterns -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "nestpat" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-nestpat.hm > "${TMPDIR:-/tmp}/ne.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/ne.js" 2>/dev/null | tail -1)
  if [ "$got" = "$NPW" ]; then printf 'ok   %-26s => %s (node)\n' "nested patterns -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "nestpat JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-nestpat.hm > "${TMPDIR:-/tmp}/ne.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/ne.rs" -o "${TMPDIR:-/tmp}/ne-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/ne-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$NPW" ]; then printf 'ok   %-26s => %s (rustc)\n' "nested patterns -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "nestpat Rust" "$got"; fail=1; fi
fi
echo "# ssct-hm PRELUDE Option/list: concatMap (transitive dep on append) / mapOption / getOrElse / find"
chk_hm examples/hm-option.hm '"Int"'
OEW="30"
ssc run bin/ssctc-hm.ssc0 examples/hm-option.hm > "${TMPDIR:-/tmp}/oe.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/oe.coreir" | tail -1)
if [ "$got" = "$OEW" ]; then printf 'ok   %-26s => %s\n' "option/concatMap -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "option" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-option.hm > "${TMPDIR:-/tmp}/oe.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/oe.js" 2>/dev/null | tail -1)
  if [ "$got" = "$OEW" ]; then printf 'ok   %-26s => %s (node)\n' "option/concatMap -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "option JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-option.hm > "${TMPDIR:-/tmp}/oe.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/oe.rs" -o "${TMPDIR:-/tmp}/oe-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/oe-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$OEW" ]; then printf 'ok   %-26s => %s (rustc)\n' "option/concatMap -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "option Rust" "$got"; fail=1; fi
fi
echo "# ssct-hm DO-NOTATION: do { x <- e ; ... ; r } -> bind/pure (Option monad by default), all backends"
chk_hm examples/hm-do.hm '"Int"'
DOW="130"
ssc run bin/ssctc-hm.ssc0 examples/hm-do.hm > "${TMPDIR:-/tmp}/de.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/de.coreir" | tail -1)
if [ "$got" = "$DOW" ]; then printf 'ok   %-26s => %s\n' "do-notation -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "do" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-do.hm > "${TMPDIR:-/tmp}/de.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/de.js" 2>/dev/null | tail -1)
  if [ "$got" = "$DOW" ]; then printf 'ok   %-26s => %s (node)\n' "do-notation -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "do JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-do.hm > "${TMPDIR:-/tmp}/de.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/de.rs" -o "${TMPDIR:-/tmp}/de-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/de-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$DOW" ]; then printf 'ok   %-26s => %s (rustc)\n' "do-notation -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "do Rust" "$got"; fail=1; fi
fi
echo "# ssct-hm PRELUDE (sort): concat / zipWith / elemBy / sortBy — polymorphic, comparator-passing"
chk_hm examples/hm-sort.hm '"Int"'
SRW="3210"
ssc run bin/ssctc-hm.ssc0 examples/hm-sort.hm > "${TMPDIR:-/tmp}/se.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/se.coreir" | tail -1)
if [ "$got" = "$SRW" ]; then printf 'ok   %-26s => %s\n' "sortBy/zipWith -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "sort" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-sort.hm > "${TMPDIR:-/tmp}/se.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/se.js" 2>/dev/null | tail -1)
  if [ "$got" = "$SRW" ]; then printf 'ok   %-26s => %s (node)\n' "sortBy/zipWith -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "sort JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-sort.hm > "${TMPDIR:-/tmp}/se.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/se.rs" -o "${TMPDIR:-/tmp}/se-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/se-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$SRW" ]; then printf 'ok   %-26s => %s (rustc)\n' "sortBy/zipWith -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "sort Rust" "$got"; fail=1; fi
fi
echo "# ssct-hm TYPE ASCRIPTION: (e : T) checks/documents, and disambiguates typeclasses (show (None : Option Int))"
chk_hm examples/hm-ascribe.hm '"String"'
ASW='"None/6"'
ssc run bin/ssctc-hm.ssc0 examples/hm-ascribe.hm > "${TMPDIR:-/tmp}/ae.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/ae.coreir" | tail -1)
if [ "$got" = "$ASW" ]; then printf 'ok   %-26s => %s\n' "ascription -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "ascribe" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-ascribe.hm > "${TMPDIR:-/tmp}/ae.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/ae.js" 2>/dev/null | tail -1)
  if [ "$got" = "$ASW" ]; then printf 'ok   %-26s => %s (node)\n' "ascription -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "ascribe JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-ascribe.hm > "${TMPDIR:-/tmp}/ae.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/ae.rs" -o "${TMPDIR:-/tmp}/ae-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/ae-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$ASW" ]; then printf 'ok   %-26s => %s (rustc)\n' "ascription -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "ascribe Rust" "$got"; fail=1; fi
fi
echo "# ssct-hm PRELUDE combinators: id / const / abs / minBy / maxBy"
chk_hm examples/hm-combinators.hm '"Int"'
CBW="22"
ssc run bin/ssctc-hm.ssc0 examples/hm-combinators.hm > "${TMPDIR:-/tmp}/ce.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/ce.coreir" | tail -1)
if [ "$got" = "$CBW" ]; then printf 'ok   %-26s => %s\n' "combinators -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "combinators" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-combinators.hm > "${TMPDIR:-/tmp}/ce.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/ce.js" 2>/dev/null | tail -1)
  if [ "$got" = "$CBW" ]; then printf 'ok   %-26s => %s (node)\n' "combinators -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "combinators JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-combinators.hm > "${TMPDIR:-/tmp}/ce.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/ce.rs" -o "${TMPDIR:-/tmp}/ce-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/ce-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$CBW" ]; then printf 'ok   %-26s => %s (rustc)\n' "combinators -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "combinators Rust" "$got"; fail=1; fi
fi
echo "# ssct-hm FUNCTION-TYPED data fields: data F a = Op (Int -> F a) | Ret a  (free monads, K7-P1)"
chk_hm examples/hm-fnfield.hm '"Int"'
FFW="20"
ssc run bin/ssctc-hm.ssc0 examples/hm-fnfield.hm > "${TMPDIR:-/tmp}/ffe.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/ffe.coreir" | tail -1)
if [ "$got" = "$FFW" ]; then printf 'ok   %-26s => %s\n' "fn-typed field -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "fnfield" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-fnfield.hm > "${TMPDIR:-/tmp}/ffe.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/ffe.js" 2>/dev/null | tail -1)
  if [ "$got" = "$FFW" ]; then printf 'ok   %-26s => %s (node)\n' "fn-typed field -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "fnfield JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-fnfield.hm > "${TMPDIR:-/tmp}/ffe.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/ffe.rs" -o "${TMPDIR:-/tmp}/ffe-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/ffe-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$FFW" ]; then printf 'ok   %-26s => %s (rustc)\n' "fn-typed field -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "fnfield Rust" "$got"; fail=1; fi
fi
echo "# ssct-hm TYPED EFFECTS (K7-P): State (one-shot) + Nondeterminism (MULTI-SHOT) as typed free monads + do-notation"
chk_hm examples/hm-eff-state.hm '"Int"'
ESW="22"
ssc run bin/ssctc-hm.ssc0 examples/hm-eff-state.hm > "${TMPDIR:-/tmp}/es.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/es.coreir" | tail -1)
if [ "$got" = "$ESW" ]; then printf 'ok   %-26s => %s\n' "State effect -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "eff-state" "$got"; fail=1; fi
ENW="102"
ssc run bin/ssctc-hm.ssc0 examples/hm-eff-nondet.hm > "${TMPDIR:-/tmp}/en.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/en.coreir" | tail -1)
if [ "$got" = "$ENW" ]; then printf 'ok   %-26s => %s\n' "Nondet (multi-shot) -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "eff-nondet" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-eff-state.hm > "${TMPDIR:-/tmp}/es.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/es.js" 2>/dev/null | tail -1)
  if [ "$got" = "$ESW" ]; then printf 'ok   %-26s => %s (node)\n' "State effect -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "eff-state JS" "$got"; fail=1; fi
  ssc run bin/ssct-hm-js.ssc0 examples/hm-eff-nondet.hm > "${TMPDIR:-/tmp}/en.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/en.js" 2>/dev/null | tail -1)
  if [ "$got" = "$ENW" ]; then printf 'ok   %-26s => %s (node)\n' "Nondet (multi-shot) -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "eff-nondet JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-eff-state.hm > "${TMPDIR:-/tmp}/es.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/es.rs" -o "${TMPDIR:-/tmp}/es-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/es-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$ESW" ]; then printf 'ok   %-26s => %s (rustc)\n' "State effect -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "eff-state Rust" "$got"; fail=1; fi
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-eff-nondet.hm > "${TMPDIR:-/tmp}/en.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/en.rs" -o "${TMPDIR:-/tmp}/en-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/en-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$ENW" ]; then printf 'ok   %-26s => %s (rustc)\n' "Nondet (multi-shot) -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "eff-nondet Rust" "$got"; fail=1; fi
fi
echo "# ssct-hm UNIVERSAL Comp via Dyn (K7-E): one effect monad for all ops; perform + label-dispatch handler"
chk_hm examples/hm-eff-comp.hm '"Int"'
ECW="41"
ssc run bin/ssctc-hm.ssc0 examples/hm-eff-comp.hm > "${TMPDIR:-/tmp}/ec.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/ec.coreir" | tail -1)
if [ "$got" = "$ECW" ]; then printf 'ok   %-26s => %s\n' "universal Comp/Dyn -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "eff-comp" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-eff-comp.hm > "${TMPDIR:-/tmp}/ec.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/ec.js" 2>/dev/null | tail -1)
  if [ "$got" = "$ECW" ]; then printf 'ok   %-26s => %s (node)\n' "universal Comp/Dyn -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "eff-comp JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-eff-comp.hm > "${TMPDIR:-/tmp}/ec.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/ec.rs" -o "${TMPDIR:-/tmp}/ec-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/ec-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$ECW" ]; then printf 'ok   %-26s => %s (rustc)\n' "universal Comp/Dyn -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "eff-comp Rust" "$got"; fail=1; fi
fi
# Dyn round-trip (the escape-hatch) types and erases
echo -n "ok   Dyn round-trip          => "; printf '((5 : Dyn) : Int) + 1' > "${TMPDIR:-/tmp}/dyn.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/dyn.hm" > "${TMPDIR:-/tmp}/dyn.coreir" 2>/dev/null; dg=$(ssc run-ir "${TMPDIR:-/tmp}/dyn.coreir" | tail -1); if [ "$dg" = "6" ]; then echo "6"; else echo "FAIL [$dg]"; fail=1; fi
echo "# ssct-hm OVERLOADED arithmetic (K8.1): + - * resolve to Int or Float by operand type, all backends"
chk_hm examples/hm-numops.hm '"Float"'
NOW="13.56"
ssc run bin/ssctc-hm.ssc0 examples/hm-numops.hm > "${TMPDIR:-/tmp}/no.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/no.coreir" | tail -1)
if [ "$got" = "$NOW" ]; then printf 'ok   %-26s => %s\n' "float +/* -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "numops" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-numops.hm > "${TMPDIR:-/tmp}/no.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/no.js" 2>/dev/null | tail -1)
  if [ "$got" = "$NOW" ]; then printf 'ok   %-26s => %s (node)\n' "float +/* -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "numops JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-numops.hm > "${TMPDIR:-/tmp}/no.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/no.rs" -o "${TMPDIR:-/tmp}/no-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/no-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$NOW" ]; then printf 'ok   %-26s => %s (rustc)\n' "float +/* -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "numops Rust" "$got"; fail=1; fi
fi
# int arithmetic still resolves to Int (regression guard)
echo -n "ok   int +/* still Int       => "; printf 'let sq = fun n => n * n + 1 in sq 6' > "${TMPDIR:-/tmp}/ia.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/ia.hm" > "${TMPDIR:-/tmp}/ia.coreir" 2>/dev/null; ig=$(ssc run-ir "${TMPDIR:-/tmp}/ia.coreir" | tail -1); if [ "$ig" = "37" ]; then echo "37"; else echo "FAIL [$ig]"; fail=1; fi
echo "# ssct-hm OVERLOADED comparisons (K8.2): < = (and > <= >= <>) on Int or Float, all backends"
chk_hm examples/hm-numcmp.hm '"Float"'
NCW="1.75"
ssc run bin/ssctc-hm.ssc0 examples/hm-numcmp.hm > "${TMPDIR:-/tmp}/nc.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/nc.coreir" | tail -1)
if [ "$got" = "$NCW" ]; then printf 'ok   %-26s => %s\n' "float < -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "numcmp" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-numcmp.hm > "${TMPDIR:-/tmp}/nc.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/nc.js" 2>/dev/null | tail -1)
  if [ "$got" = "$NCW" ]; then printf 'ok   %-26s => %s (node)\n' "float < -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "numcmp JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-numcmp.hm > "${TMPDIR:-/tmp}/nc.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/nc.rs" -o "${TMPDIR:-/tmp}/nc-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/nc-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$NCW" ]; then printf 'ok   %-26s => %s (rustc)\n' "float < -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "numcmp Rust" "$got"; fail=1; fi
fi
# int comparison still resolves to Int (regression guard)
echo -n "ok   int < still Int         => "; printf 'if 3 < 5 then 1 else 0' > "${TMPDIR:-/tmp}/ic.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/ic.hm" > "${TMPDIR:-/tmp}/ic.coreir" 2>/dev/null; icg=$(ssc run-ir "${TMPDIR:-/tmp}/ic.coreir" | tail -1); if [ "$icg" = "1" ]; then echo "1"; else echo "FAIL [$icg]"; fail=1; fi
echo "# ssct-hm OVERLOADED division (K8.3): '/' resolves to Int (truncating) or Float by operand type, all backends"
chk_hm examples/hm-div.hm '"(Int, Float)"'                            # (20 / 6, 9.0 / 2.0): Int division truncates, Float division does not
DIVW="Pair(3, 4.5)"
ssc run bin/ssctc-hm.ssc0 examples/hm-div.hm > "${TMPDIR:-/tmp}/dv.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/dv.coreir" | tail -1)
if [ "$got" = "$DIVW" ]; then printf 'ok   %-26s => %s\n' "int/float div -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "div" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-div.hm > "${TMPDIR:-/tmp}/dv.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/dv.js" 2>/dev/null | tail -1)
  if [ "$got" = "$DIVW" ]; then printf 'ok   %-26s => %s (node)\n' "int/float div -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "div JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-div.hm > "${TMPDIR:-/tmp}/dv.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/dv.rs" -o "${TMPDIR:-/tmp}/dv-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/dv-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$DIVW" ]; then printf 'ok   %-26s => %s (rustc)\n' "int/float div -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "div Rust" "$got"; fail=1; fi
fi
echo -n "ok   mixed div rejected       => "; printf '9.0 / 2' > "${TMPDIR:-/tmp}/dmx.hm"; dmx=$(ssc run bin/ssct-hm.ssc0 "${TMPDIR:-/tmp}/dmx.hm" | tail -1); if [ "$dmx" = '"TypeError: arithmetic operands must have the same type"' ]; then echo "Int/Float mix rejected (correct)"; else echo "FAIL [$dmx]"; fail=1; fi
echo "# ssct-hm TYPED ASYNC: cooperative scheduler (yield/log) on the typed effect monad, all backends"
chk_hm examples/hm-async.hm '"[Int]"'
AYW="Cons(1, Cons(2, Cons(101, Cons(102, Nil))))"
ssc run bin/ssctc-hm.ssc0 examples/hm-async.hm > "${TMPDIR:-/tmp}/ay.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/ay.coreir" | tail -1)
if [ "$got" = "$AYW" ]; then printf 'ok   %-26s => %s\n' "async sched -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "async" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-async.hm > "${TMPDIR:-/tmp}/ay.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/ay.js" 2>/dev/null | tail -1)
  if [ "$got" = "$AYW" ]; then printf 'ok   %-26s => %s (node)\n' "async sched -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "async JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-async.hm > "${TMPDIR:-/tmp}/ay.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/ay.rs" -o "${TMPDIR:-/tmp}/ay-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/ay-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$AYW" ]; then printf 'ok   %-26s => %s (rustc)\n' "async sched -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "async Rust" "$got"; fail=1; fi
fi
echo "# ssct-hm TYPED ACTORS: stateful behavior (state, msg) -> (state', out) over a message stream, all backends"
chk_hm examples/hm-actors.hm '"[Int]"'
ATW="Cons(2, Cons(3, Cons(2, Nil)))"
ssc run bin/ssctc-hm.ssc0 examples/hm-actors.hm > "${TMPDIR:-/tmp}/at.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/at.coreir" | tail -1)
if [ "$got" = "$ATW" ]; then printf 'ok   %-26s => %s\n' "actor behavior -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "actors" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-actors.hm > "${TMPDIR:-/tmp}/at.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/at.js" 2>/dev/null | tail -1)
  if [ "$got" = "$ATW" ]; then printf 'ok   %-26s => %s (node)\n' "actor behavior -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "actors JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-actors.hm > "${TMPDIR:-/tmp}/at.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/at.rs" -o "${TMPDIR:-/tmp}/at-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/at-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$ATW" ]; then printf 'ok   %-26s => %s (rustc)\n' "actor behavior -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "actors Rust" "$got"; fail=1; fi
fi
echo "# ssct-hm EFFECT ROWS (K10): type tracks effects + runE rejects unhandled; runs on all backends"
chk_hm examples/hm-effrow.hm '"(Int, Int)"'                          # put 5; get; return get+100 -> handled, runs
ERV="Pair(105, 5)"
ssc run bin/ssctc-hm.ssc0 examples/hm-effrow.hm > "${TMPDIR:-/tmp}/er.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/er.coreir" | tail -1)
if [ "$got" = "$ERV" ]; then printf 'ok   %-26s => %s\n' "effect run (State) -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "effrow" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-effrow.hm > "${TMPDIR:-/tmp}/er.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/er.js" 2>/dev/null | tail -1)
  if [ "$got" = "$ERV" ]; then printf 'ok   %-26s => %s (node)\n' "effect run (State) -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "effrow JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-effrow.hm > "${TMPDIR:-/tmp}/er.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/er.rs" -o "${TMPDIR:-/tmp}/er-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/er-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$ERV" ]; then printf 'ok   %-26s => %s (rustc)\n' "effect run (State) -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "effrow Rust" "$got"; fail=1; fi
fi
echo -n "ok   effect tracked in type   => "; printf 'getE' > "${TMPDIR:-/tmp}/er1.hm"; et=$(ssc run bin/ssct-hm.ssc0 "${TMPDIR:-/tmp}/er1.hm" | tail -1); if [ "$et" = '"Comp {State | e0} Dyn"' ]; then echo "Comp {State | e0} Dyn"; else echo "FAIL [$et]"; fail=1; fi
echo -n "ok   unhandled effect = error => "; printf 'runE getE' > "${TMPDIR:-/tmp}/er2.hm"; eu=$(ssc run bin/ssct-hm.ssc0 "${TMPDIR:-/tmp}/er2.hm" | tail -1); if [ "$eu" = '"TypeError: effect not handled: State"' ]; then echo "rejected (correct)"; else echo "FAIL [$eu]"; fail=1; fi
echo "# K10.4c — TWO effects (State + Log): the ROW tracks both; runE demands BOTH handled; runs on all backends"
chk_hm examples/hm-eff2.hm '"((Int, Int), [Dyn])"'                   # put 3; log 7; get -> row {State, Log}, both handled
E2V="Pair(Pair(103, 3), Cons(7, Nil))"
ssc run bin/ssctc-hm.ssc0 examples/hm-eff2.hm > "${TMPDIR:-/tmp}/e2.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/e2.coreir" | tail -1)
if [ "$got" = "$E2V" ]; then printf 'ok   %-26s => %s\n' "two effects -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "eff2" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-eff2.hm > "${TMPDIR:-/tmp}/e2.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/e2.js" 2>/dev/null | tail -1)
  if [ "$got" = "$E2V" ]; then printf 'ok   %-26s => %s (node)\n' "two effects -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "eff2 JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-eff2.hm > "${TMPDIR:-/tmp}/e2.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/e2.rs" -o "${TMPDIR:-/tmp}/e2-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/e2-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$E2V" ]; then printf 'ok   %-26s => %s (rustc)\n' "two effects -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "eff2 Rust" "$got"; fail=1; fi
fi
echo -n "ok   second effect tracked too => "; printf 'runE (runStateE (bindE (logE (7 : Dyn)) (fun w => bindE getE (fun x => pureE ((x : Int) + 1)))) 0)' > "${TMPDIR:-/tmp}/er3.hm"; el=$(ssc run bin/ssct-hm.ssc0 "${TMPDIR:-/tmp}/er3.hm" | tail -1); if [ "$el" = '"TypeError: effect not handled: Log"' ]; then echo "Log unhandled rejected (correct)"; else echo "FAIL [$el]"; fail=1; fi
echo "# K10.4d — USER-EXTENSIBLE effects: 'perform' any effect + general deep 'handle' (incl MULTI-SHOT). No built-in support."
chk_hm examples/hm-eff-handle.hm '"[Int]"'                            # nondeterminism: flip;flip via a user handler that resumes TWICE
EHV="Cons(3, Cons(2, Cons(1, Cons(0, Nil))))"
ssc run bin/ssctc-hm.ssc0 examples/hm-eff-handle.hm > "${TMPDIR:-/tmp}/eh.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/eh.coreir" | tail -1)
if [ "$got" = "$EHV" ]; then printf 'ok   %-26s => %s\n' "multi-shot handle -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "eff-handle" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-eff-handle.hm > "${TMPDIR:-/tmp}/eh.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/eh.js" 2>/dev/null | tail -1)
  if [ "$got" = "$EHV" ]; then printf 'ok   %-26s => %s (node)\n' "multi-shot handle -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "eff-handle JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-eff-handle.hm > "${TMPDIR:-/tmp}/eh.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/eh.rs" -o "${TMPDIR:-/tmp}/eh-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/eh-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$EHV" ]; then printf 'ok   %-26s => %s (rustc)\n' "multi-shot handle -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "eff-handle Rust" "$got"; fail=1; fi
fi
echo -n "ok   user effect tracked too   => "; printf 'runE (perform "Choose" "flip" (0 : Dyn))' > "${TMPDIR:-/tmp}/er4.hm"; ec=$(ssc run bin/ssct-hm.ssc0 "${TMPDIR:-/tmp}/er4.hm" | tail -1); if [ "$ec" = '"TypeError: effect not handled: Choose"' ]; then echo "user Choose unhandled rejected (correct)"; else echo "FAIL [$ec]"; fail=1; fi
echo "# K10.4e — the general 'handle' SUBSUMES the built-ins: a parameterized (state-threading) State handler in USER source"
chk_hm examples/hm-eff-userstate.hm '"(Int, Int)"'                    # runState written with perform/handle only (no runStateE), threads state via a returned fn
USV="Pair(105, 5)"
ssc run bin/ssctc-hm.ssc0 examples/hm-eff-userstate.hm > "${TMPDIR:-/tmp}/us.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/us.coreir" | tail -1)
if [ "$got" = "$USV" ]; then printf 'ok   %-26s => %s\n' "user State handler -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "eff-userstate" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-eff-userstate.hm > "${TMPDIR:-/tmp}/us.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/us.js" 2>/dev/null | tail -1)
  if [ "$got" = "$USV" ]; then printf 'ok   %-26s => %s (node)\n' "user State handler -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "eff-userstate JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-eff-userstate.hm > "${TMPDIR:-/tmp}/us.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/us.rs" -o "${TMPDIR:-/tmp}/us-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/us-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$USV" ]; then printf 'ok   %-26s => %s (rustc)\n' "user State handler -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "eff-userstate Rust" "$got"; fail=1; fi
fi
echo "# K10.4e.2 — 'doE { x <- m ; .. }' do-notation for effects (desugars <- to bindE; the existing 'do' -> 'bind' is untouched)"
chk_hm examples/hm-eff-do.hm '"(Int, Int)"'                           # doE State: put 5; get; return get+100
DOV="Pair(105, 5)"
ssc run bin/ssctc-hm.ssc0 examples/hm-eff-do.hm > "${TMPDIR:-/tmp}/edo.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/edo.coreir" | tail -1)
if [ "$got" = "$DOV" ]; then printf 'ok   %-26s => %s\n' "doE State -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "eff-do" "$got"; fail=1; fi
chk_hm examples/hm-eff-do-nondet.hm '"[Int]"'                         # doE in BOTH the handler body and the computation (multi-shot)
DNV="Cons(3, Cons(2, Cons(1, Cons(0, Nil))))"
ssc run bin/ssctc-hm.ssc0 examples/hm-eff-do-nondet.hm > "${TMPDIR:-/tmp}/edn.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/edn.coreir" | tail -1)
if [ "$got" = "$DNV" ]; then printf 'ok   %-26s => %s\n' "doE nondet -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "eff-do-nondet" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-eff-do.hm > "${TMPDIR:-/tmp}/edo.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/edo.js" 2>/dev/null | tail -1)
  if [ "$got" = "$DOV" ]; then printf 'ok   %-26s => %s (node)\n' "doE State -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "eff-do JS" "$got"; fail=1; fi
  ssc run bin/ssct-hm-js.ssc0 examples/hm-eff-do-nondet.hm > "${TMPDIR:-/tmp}/edn.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/edn.js" 2>/dev/null | tail -1)
  if [ "$got" = "$DNV" ]; then printf 'ok   %-26s => %s (node)\n' "doE nondet -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "eff-do-nondet JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-eff-do.hm > "${TMPDIR:-/tmp}/edo.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/edo.rs" -o "${TMPDIR:-/tmp}/edo-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/edo-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$DOV" ]; then printf 'ok   %-26s => %s (rustc)\n' "doE State -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "eff-do Rust" "$got"; fail=1; fi
fi
echo "# K11.1 — 'effect Name op1 op2 in …' declaration sugar: 'op arg' => perform (no string literals)"
chk_hm examples/hm-eff-decl.hm '"(Int, Int)"'                         # effect State get put in … (get/put as declared ops)
EDV="Pair(105, 5)"
ssc run bin/ssctc-hm.ssc0 examples/hm-eff-decl.hm > "${TMPDIR:-/tmp}/edl.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/edl.coreir" | tail -1)
if [ "$got" = "$EDV" ]; then printf 'ok   %-26s => %s\n' "effect decl State -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "eff-decl" "$got"; fail=1; fi
chk_hm examples/hm-eff-decl-choose.hm '"[Int]"'                       # effect Choose flip in … + general handle (multi-shot)
EDCV="Cons(3, Cons(2, Cons(1, Cons(0, Nil))))"
ssc run bin/ssctc-hm.ssc0 examples/hm-eff-decl-choose.hm > "${TMPDIR:-/tmp}/edc.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/edc.coreir" | tail -1)
if [ "$got" = "$EDCV" ]; then printf 'ok   %-26s => %s\n' "effect decl Choose -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "eff-decl-choose" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-eff-decl.hm > "${TMPDIR:-/tmp}/edl.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/edl.js" 2>/dev/null | tail -1)
  if [ "$got" = "$EDV" ]; then printf 'ok   %-26s => %s (node)\n' "effect decl State -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "eff-decl JS" "$got"; fail=1; fi
  ssc run bin/ssct-hm-js.ssc0 examples/hm-eff-decl-choose.hm > "${TMPDIR:-/tmp}/edc.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/edc.js" 2>/dev/null | tail -1)
  if [ "$got" = "$EDCV" ]; then printf 'ok   %-26s => %s (node)\n' "effect decl Choose -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "eff-decl-choose JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-eff-decl-choose.hm > "${TMPDIR:-/tmp}/edc.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/edc.rs" -o "${TMPDIR:-/tmp}/edc-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/edc-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$EDCV" ]; then printf 'ok   %-26s => %s (rustc)\n' "effect decl Choose -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "eff-decl-choose Rust" "$got"; fail=1; fi
fi
echo -n "ok   declared op tracked too   => "; printf 'effect Choose flip in runE (flip (0 : Dyn))' > "${TMPDIR:-/tmp}/edu.hm"; edu=$(ssc run bin/ssct-hm.ssc0 "${TMPDIR:-/tmp}/edu.hm" | tail -1); if [ "$edu" = '"TypeError: effect not handled: Choose"' ]; then echo "declared Choose unhandled rejected (correct)"; else echo "FAIL [$edu]"; fail=1; fi
echo "# K11.2 — effect-row syntax in the type parser: {} / {l} / {l, m} / {l | r} in ascriptions"
echo -n "ok   closed row ascription     => "; printf '(getE : Comp {State} Dyn)' > "${TMPDIR:-/tmp}/rs1.hm"; rs1=$(ssc run bin/ssct-hm.ssc0 "${TMPDIR:-/tmp}/rs1.hm" | tail -1); if [ "$rs1" = '"Comp {State} Dyn"' ]; then echo "Comp {State} Dyn"; else echo "FAIL [$rs1]"; fail=1; fi
echo -n "ok   open row ascription       => "; printf '(getE : Comp {State | r} Dyn)' > "${TMPDIR:-/tmp}/rs2.hm"; rs2=$(ssc run bin/ssct-hm.ssc0 "${TMPDIR:-/tmp}/rs2.hm" | tail -1); case "$rs2" in '"Comp {State | e'*'} Dyn"') echo "$rs2 (open)";; *) echo "FAIL [$rs2]"; fail=1;; esac
echo -n "ok   wrong row ascr rejected   => "; printf '(getE : Comp {Log} Dyn)' > "${TMPDIR:-/tmp}/rs3.hm"; rs3=$(ssc run bin/ssct-hm.ssc0 "${TMPDIR:-/tmp}/rs3.hm" | tail -1); if [ "$rs3" = '"TypeError: type ascription mismatch"' ]; then echo "State vs Log rejected (correct)"; else echo "FAIL [$rs3]"; fail=1; fi
chk_hm examples/hm-eff-rowann.hm '"(Int, Int)"'                       # the doE block ascribed : Comp {State} Int
RAV="Pair(105, 5)"
ssc run bin/ssctc-hm.ssc0 examples/hm-eff-rowann.hm > "${TMPDIR:-/tmp}/ra.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/ra.coreir" | tail -1)
if [ "$got" = "$RAV" ]; then printf 'ok   %-26s => %s\n' "row ascription -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "eff-rowann" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-eff-rowann.hm > "${TMPDIR:-/tmp}/ra.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/ra.js" 2>/dev/null | tail -1)
  if [ "$got" = "$RAV" ]; then printf 'ok   %-26s => %s (node)\n' "row ascription -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "eff-rowann JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-eff-rowann.hm > "${TMPDIR:-/tmp}/ra.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/ra.rs" -o "${TMPDIR:-/tmp}/ra-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/ra-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$RAV" ]; then printf 'ok   %-26s => %s (rustc)\n' "row ascription -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "eff-rowann Rust" "$got"; fail=1; fi
fi
echo "# K11.3 — NUMERIC POLYMORPHISM (light qualified types via inlining): a closed numeric helper works at Int AND Float"
chk_hm examples/hm-poly-num.hm '"(Int, Float)"'                       # let twice = fun x => x+x in (twice 5, twice 2.25): used at BOTH numeric types
PNV="Pair(10, 4.5)"
ssc run bin/ssctc-hm.ssc0 examples/hm-poly-num.hm > "${TMPDIR:-/tmp}/pn.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/pn.coreir" | tail -1)
if [ "$got" = "$PNV" ]; then printf 'ok   %-26s => %s\n' "poly numeric -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "poly-num" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-poly-num.hm > "${TMPDIR:-/tmp}/pn.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/pn.js" 2>/dev/null | tail -1)
  if [ "$got" = "$PNV" ]; then printf 'ok   %-26s => %s (node)\n' "poly numeric -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "poly-num JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-poly-num.hm > "${TMPDIR:-/tmp}/pn.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/pn.rs" -o "${TMPDIR:-/tmp}/pn-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/pn-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$PNV" ]; then printf 'ok   %-26s => %s (rustc)\n' "poly numeric -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "poly-num Rust" "$got"; fail=1; fi
fi
# the eager-default sharp edge is gone for a closed helper, but a let-bound NON-inlined numeric fn still defaults to Int (sound)
echo -n "ok   int helper still Int     => "; printf 'let sq = fun n => n * n in sq 7' > "${TMPDIR:-/tmp}/sq.hm"; sqg=$(ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/sq.hm" 2>/dev/null > "${TMPDIR:-/tmp}/sq.coreir"; ssc run-ir "${TMPDIR:-/tmp}/sq.coreir" | tail -1); if [ "$sqg" = "49" ]; then echo "49"; else echo "FAIL [$sqg]"; fail=1; fi
echo "# K11.4 — TYPED PAYLOADS (light): effect Name { op : ArgT -> ReplyT } — typed perform, no Dyn ascriptions"
chk_hm examples/hm-eff-typed.hm '"(Int, Int)"'                        # get : Dyn -> Int, put : Int -> Dyn; `x <- get` gives x : Int with NO ascription
TPV="Pair(105, 5)"
ssc run bin/ssctc-hm.ssc0 examples/hm-eff-typed.hm > "${TMPDIR:-/tmp}/tp.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/tp.coreir" | tail -1)
if [ "$got" = "$TPV" ]; then printf 'ok   %-26s => %s\n' "typed payloads -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "eff-typed" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-eff-typed.hm > "${TMPDIR:-/tmp}/tp.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/tp.js" 2>/dev/null | tail -1)
  if [ "$got" = "$TPV" ]; then printf 'ok   %-26s => %s (node)\n' "typed payloads -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "eff-typed JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-eff-typed.hm > "${TMPDIR:-/tmp}/tp.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/tp.rs" -o "${TMPDIR:-/tmp}/tp-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/tp-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$TPV" ]; then printf 'ok   %-26s => %s (rustc)\n' "typed payloads -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "eff-typed Rust" "$got"; fail=1; fi
fi
echo -n "ok   typed op arg checked     => "; printf 'effect State { get : Dyn -> Int , put : Int -> Dyn } in runE (runStateE (doE { u <- put "x" ; x <- get ; pureE (x + 1) }) 0)' > "${TMPDIR:-/tmp}/tpb.hm"; tpb=$(ssc run bin/ssct-hm.ssc0 "${TMPDIR:-/tmp}/tpb.hm" | tail -1); if [ "$tpb" = '"TypeError: effect op arg type mismatch for put"' ]; then echo "put String rejected (correct)"; else echo "FAIL [$tpb]"; fail=1; fi
echo "# K11.3b — USER-TYPECLASS POLYMORPHISM: a `method m : R` (result sig) used in a closed fn resolves the instance per use"
chk_hm examples/hm-method-poly.hm '"(String, String)"'                # let f = fun x => describe x in (f 5, f true): Int & Bool instances
MPV='Pair("an int", "a bool")'
ssc run bin/ssctc-hm.ssc0 examples/hm-method-poly.hm > "${TMPDIR:-/tmp}/mp.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/mp.coreir" | tail -1)
if [ "$got" = "$MPV" ]; then printf 'ok   %-26s => %s\n' "method poly -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "method-poly" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-method-poly.hm > "${TMPDIR:-/tmp}/mp.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/mp.js" 2>/dev/null | tail -1)
  if [ "$got" = "$MPV" ]; then printf 'ok   %-26s => %s (node)\n' "method poly -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "method-poly JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-method-poly.hm > "${TMPDIR:-/tmp}/mp.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/mp.rs" -o "${TMPDIR:-/tmp}/mp-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/mp-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$MPV" ]; then printf 'ok   %-26s => %s (rustc)\n' "method poly -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "method-poly Rust" "$got"; fail=1; fi
fi
echo -n "ok   eager method still works => "; printf 'method sz in instance sz Int = fun n => n + 1 in instance sz Bool = fun b => 0 in sz 5' > "${TMPDIR:-/tmp}/eg.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/eg.hm" 2>/dev/null > "${TMPDIR:-/tmp}/eg.coreir"; egg=$(ssc run-ir "${TMPDIR:-/tmp}/eg.coreir" | tail -1); if [ "$egg" = "6" ]; then echo "6 (monomorphic, no sig)"; else echo "FAIL [$egg]"; fail=1; fi
# SOUNDNESS: a deferred method whose instance impl uses an overloaded op (`<` on a Float) must type-check the impl so the op resolves (f.lt, not i.lt) — all 3 backends agree
chk_hm examples/hm-method-poly-ops.hm '"(Int, Int)"'                  # instance impls use `<` on Int/Float
MOV="Pair(200, 111)"
ssc run bin/ssctc-hm.ssc0 examples/hm-method-poly-ops.hm > "${TMPDIR:-/tmp}/mo.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/mo.coreir" | tail -1)
if [ "$got" = "$MOV" ]; then printf 'ok   %-26s => %s\n' "method poly (ops) -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "method-poly-ops" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-method-poly-ops.hm > "${TMPDIR:-/tmp}/mo.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/mo.js" 2>/dev/null | tail -1)
  if [ "$got" = "$MOV" ]; then printf 'ok   %-26s => %s (node)\n' "method poly (ops) -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "method-poly-ops JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-method-poly-ops.hm > "${TMPDIR:-/tmp}/mo.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/mo.rs" -o "${TMPDIR:-/tmp}/mo-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/mo-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$MOV" ]; then printf 'ok   %-26s => %s (rustc)\n' "method poly (ops) -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "method-poly-ops Rust" "$got"; fail=1; fi
fi
echo "# K11.3c — RECEIVER-RESULT methods: `method negate : self` (result = the receiver type) polymorphic at Int & Float"
chk_hm examples/hm-method-self.hm '"(Int, Float)"'                    # negate : self; 0-n (Int) / 0.0-x (Float)
MSV="Pair(-5, -2.5)"
ssc run bin/ssctc-hm.ssc0 examples/hm-method-self.hm > "${TMPDIR:-/tmp}/ms.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/ms.coreir" | tail -1)
if [ "$got" = "$MSV" ]; then printf 'ok   %-26s => %s\n' "method self -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "method-self" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-method-self.hm > "${TMPDIR:-/tmp}/ms.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/ms.js" 2>/dev/null | tail -1)
  if [ "$got" = "$MSV" ]; then printf 'ok   %-26s => %s (node)\n' "method self -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "method-self JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-method-self.hm > "${TMPDIR:-/tmp}/ms.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/ms.rs" -o "${TMPDIR:-/tmp}/ms-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/ms-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$MSV" ]; then printf 'ok   %-26s => %s (rustc)\n' "method self -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "method-self Rust" "$got"; fail=1; fi
fi
echo "# MUTUAL RECURSION: let rec f = .. and g = .. in ..  (multi-binding IrLetRec; Rust gen got an n-way knot-tie)"
chk_hm examples/hm-mutual.hm '"Bool"'                                 # isEven/isOdd mutual recursion
ssc run bin/ssctc-hm.ssc0 examples/hm-mutual.hm > "${TMPDIR:-/tmp}/mut.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/mut.coreir" | tail -1)
if [ "$got" = "true" ]; then printf 'ok   %-26s => %s\n' "mutual rec -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "mutual" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-mutual.hm > "${TMPDIR:-/tmp}/mut.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/mut.js" 2>/dev/null | tail -1)
  if [ "$got" = "true" ]; then printf 'ok   %-26s => %s (node)\n' "mutual rec -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "mutual JS" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-mutual.hm > "${TMPDIR:-/tmp}/mut.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/mut.rs" -o "${TMPDIR:-/tmp}/mut-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/mut-bin"); else got="(rustc err)"; fi
  if [ "$got" = "true" ]; then printf 'ok   %-26s => %s (rustc)\n' "mutual rec -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "mutual Rust" "$got"; fail=1; fi
fi
echo -n "ok   3-way mutual rec         => "; printf 'let rec f = fun n => if n = 0 then 0 else g (n - 1) and g = fun n => if n = 0 then 1 else h (n - 1) and h = fun n => if n = 0 then 2 else f (n - 1) in f 7' > "${TMPDIR:-/tmp}/m3.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/m3.hm" 2>/dev/null > "${TMPDIR:-/tmp}/m3.coreir"; m3=$(ssc run-ir "${TMPDIR:-/tmp}/m3.coreir" | tail -1); if [ "$m3" = "1" ]; then echo "1 (f→g→h→f…)"; else echo "FAIL [$m3]"; fail=1; fi
echo "# 4-TUPLES (Quad): `(a,b,c,d)` no longer silently truncates to Triple; 5+ nest the tail (no data loss)"
echo -n "ok   4-tuple type            => "; printf '(1, 2, 3, 4)' > "${TMPDIR:-/tmp}/q.hm"; qt=$(ssc run bin/ssct-hm.ssc0 "${TMPDIR:-/tmp}/q.hm" | tail -1); if [ "$qt" = '"(Int, Int, Int, Int)"' ]; then echo "(Int, Int, Int, Int)"; else echo "FAIL [$qt]"; fail=1; fi
chk_hm examples/hm-quad.hm '"Int"'                                    # 4-tuple pattern (a, b, c, d) => sum
ssc run bin/ssctc-hm.ssc0 examples/hm-quad.hm > "${TMPDIR:-/tmp}/q.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/q.coreir" | tail -1)
if [ "$got" = "10" ]; then printf 'ok   %-26s => %s\n' "4-tuple pattern -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "quad" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then ssc run bin/ssct-hm-js.ssc0 examples/hm-quad.hm > "${TMPDIR:-/tmp}/q.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/q.js" 2>/dev/null | tail -1); if [ "$got" = "10" ]; then printf 'ok   %-26s => %s (node)\n' "4-tuple -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "quad JS" "$got"; fail=1; fi; fi
if command -v rustc >/dev/null 2>&1; then ssc run bin/ssct-hm-rust.ssc0 examples/hm-quad.hm > "${TMPDIR:-/tmp}/q.rs" 2>/dev/null; if rustc -O "${TMPDIR:-/tmp}/q.rs" -o "${TMPDIR:-/tmp}/q-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/q-bin"); else got="(rustc err)"; fi; if [ "$got" = "10" ]; then printf 'ok   %-26s => %s (rustc)\n' "4-tuple -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "quad Rust" "$got"; fail=1; fi; fi
echo -n "ok   5-tuple no data loss     => "; printf 'match (1, 2, 3, 4, 5) { (a, b, c, (d, e)) => a + e }' > "${TMPDIR:-/tmp}/q5.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/q5.hm" 2>/dev/null > "${TMPDIR:-/tmp}/q5.coreir"; q5=$(ssc run-ir "${TMPDIR:-/tmp}/q5.coreir" | tail -1); if [ "$q5" = "6" ]; then echo "6 (nested, a+e)"; else echo "FAIL [$q5]"; fail=1; fi
echo "# PATTERN GUARDS: pat 'if' cond '=>' body — a failed guard falls through to the next arm (\$guardfail → fail cont)"
chk_hm examples/hm-guard.hm '"Int"'                                   # classify with x<0 / x>0 / else guards
ssc run bin/ssctc-hm.ssc0 examples/hm-guard.hm > "${TMPDIR:-/tmp}/g.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/g.coreir" | tail -1)
if [ "$got" = "0" ]; then printf 'ok   %-26s => %s\n' "pattern guards -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "guard" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then ssc run bin/ssct-hm-js.ssc0 examples/hm-guard.hm > "${TMPDIR:-/tmp}/g.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/g.js" 2>/dev/null | tail -1); if [ "$got" = "0" ]; then printf 'ok   %-26s => %s (node)\n' "pattern guards -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "guard JS" "$got"; fail=1; fi; fi
if command -v rustc >/dev/null 2>&1; then ssc run bin/ssct-hm-rust.ssc0 examples/hm-guard.hm > "${TMPDIR:-/tmp}/g.rs" 2>/dev/null; if rustc -O "${TMPDIR:-/tmp}/g.rs" -o "${TMPDIR:-/tmp}/g-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/g-bin"); else got="(rustc err)"; fi; if [ "$got" = "0" ]; then printf 'ok   %-26s => %s (rustc)\n' "pattern guards -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "guard Rust" "$got"; fail=1; fi; fi
echo -n "ok   guard on Con + fallthrough => "; printf 'data O = Som Int | Non in match (Som 5) { Som x if x > 10 => 100 | Som x => x | Non => 0 }' > "${TMPDIR:-/tmp}/gc.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/gc.hm" 2>/dev/null > "${TMPDIR:-/tmp}/gc.coreir"; gc=$(ssc run-ir "${TMPDIR:-/tmp}/gc.coreir" | tail -1); if [ "$gc" = "5" ]; then echo "5 (guard false -> next arm)"; else echo "FAIL [$gc]"; fail=1; fi
echo "# STRING ESCAPES: \\n \\t \\r \\\\ \\\" in string literals; backends re-escape on emit (Core IR already round-trips)"
chk_hm examples/hm-escapes.hm '"Int"'                                 # strLen "a\nb\tc"
ssc run bin/ssctc-hm.ssc0 examples/hm-escapes.hm > "${TMPDIR:-/tmp}/se.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/se.coreir" | tail -1)
if [ "$got" = "5" ]; then printf 'ok   %-26s => %s\n' "string escapes -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "escapes" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then ssc run bin/ssct-hm-js.ssc0 examples/hm-escapes.hm > "${TMPDIR:-/tmp}/se.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/se.js" 2>/dev/null | tail -1); if [ "$got" = "5" ]; then printf 'ok   %-26s => %s (node)\n' "string escapes -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "escapes JS" "$got"; fail=1; fi; fi
if command -v rustc >/dev/null 2>&1; then ssc run bin/ssct-hm-rust.ssc0 examples/hm-escapes.hm > "${TMPDIR:-/tmp}/se.rs" 2>/dev/null; if rustc -O "${TMPDIR:-/tmp}/se.rs" -o "${TMPDIR:-/tmp}/se-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/se-bin"); else got="(rustc err)"; fi; if [ "$got" = "5" ]; then printf 'ok   %-26s => %s (rustc)\n' "string escapes -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "escapes Rust" "$got"; fail=1; fi; fi
echo -n "ok   escaped quote in string  => "; printf 'strLen "x\\"y"' > "${TMPDIR:-/tmp}/sq.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/sq.hm" 2>/dev/null > "${TMPDIR:-/tmp}/sq.coreir"; sq=$(ssc run-ir "${TMPDIR:-/tmp}/sq.coreir" | tail -1); if [ "$sq" = "3" ]; then echo "3 (x\"y, quote not a terminator)"; else echo "FAIL [$sq]"; fail=1; fi
echo "# RECORD UPDATE: { r with f = v , … } — a new record like r with field f replaced (type-directed, all fields copied)"
chk_hm examples/hm-recupd.hm '"Int"'                                  # { r with x = 9 } then r2.x + r2.y
ssc run bin/ssctc-hm.ssc0 examples/hm-recupd.hm > "${TMPDIR:-/tmp}/ru.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/ru.coreir" | tail -1)
if [ "$got" = "11" ]; then printf 'ok   %-26s => %s\n' "record update -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "recupd" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then ssc run bin/ssct-hm-js.ssc0 examples/hm-recupd.hm > "${TMPDIR:-/tmp}/ru.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/ru.js" 2>/dev/null | tail -1); if [ "$got" = "11" ]; then printf 'ok   %-26s => %s (node)\n' "record update -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "recupd JS" "$got"; fail=1; fi; fi
if command -v rustc >/dev/null 2>&1; then ssc run bin/ssct-hm-rust.ssc0 examples/hm-recupd.hm > "${TMPDIR:-/tmp}/ru.rs" 2>/dev/null; if rustc -O "${TMPDIR:-/tmp}/ru.rs" -o "${TMPDIR:-/tmp}/ru-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/ru-bin"); else got="(rustc err)"; fi; if [ "$got" = "11" ]; then printf 'ok   %-26s => %s (rustc)\n' "record update -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "recupd Rust" "$got"; fail=1; fi; fi
echo -n "ok   multi-field + unknown-field => "; printf 'let r = {x = 1, y = 2} in let r2 = {r with x = 9, y = 8} in r2.x + r2.y' > "${TMPDIR:-/tmp}/ru2.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/ru2.hm" 2>/dev/null > "${TMPDIR:-/tmp}/ru2.coreir"; ru2=$(ssc run-ir "${TMPDIR:-/tmp}/ru2.coreir" | tail -1); printf 'let r = {x = 1} in {r with z = 9}' > "${TMPDIR:-/tmp}/ru3.hm"; ru3=$(ssc run bin/ssct-hm.ssc0 "${TMPDIR:-/tmp}/ru3.hm" | tail -1); if [ "$ru2" = "17" ] && [ "$ru3" = '"TypeError: record has no field z"' ]; then echo "17 ; unknown field rejected"; else echo "FAIL [$ru2 / $ru3]"; fail=1; fi

echo "# NEGATIVE LITERALS: unary minus on a numeric literal (-5, -2.5) at any operand position; binary 'f - 5' stays subtraction"
chk_hm examples/hm-neg.hm '"Int"'                                     # let x = -5 in let y = -3 in x*y + -1
ssc run bin/ssctc-hm.ssc0 examples/hm-neg.hm > "${TMPDIR:-/tmp}/neg.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/neg.coreir" | tail -1)
if [ "$got" = "14" ]; then printf 'ok   %-26s => %s\n' "neg literal -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "neg literal" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then ssc run bin/ssct-hm-js.ssc0 examples/hm-neg.hm > "${TMPDIR:-/tmp}/neg.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/neg.js" 2>/dev/null | tail -1); if [ "$got" = "14" ]; then printf 'ok   %-26s => %s (node)\n' "neg literal -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "neg literal JS" "$got"; fail=1; fi; fi
if command -v rustc >/dev/null 2>&1; then ssc run bin/ssct-hm-rust.ssc0 examples/hm-neg.hm > "${TMPDIR:-/tmp}/neg.rs" 2>/dev/null; if rustc -O "${TMPDIR:-/tmp}/neg.rs" -o "${TMPDIR:-/tmp}/neg-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/neg-bin"); else got="(rustc err)"; fi; if [ "$got" = "14" ]; then printf 'ok   %-26s => %s (rustc)\n' "neg literal -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "neg literal Rust" "$got"; fail=1; fi; fi
echo -n "ok   neg precedence + binary '-' => "; printf 'let n = -3 in n * -2' > "${TMPDIR:-/tmp}/ng2.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/ng2.hm" 2>/dev/null > "${TMPDIR:-/tmp}/ng2.coreir"; ng2=$(ssc run-ir "${TMPDIR:-/tmp}/ng2.coreir" | tail -1); printf 'let f = fun a => a - 1 in f 10' > "${TMPDIR:-/tmp}/ng3.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/ng3.hm" 2>/dev/null > "${TMPDIR:-/tmp}/ng3.coreir"; ng3=$(ssc run-ir "${TMPDIR:-/tmp}/ng3.coreir" | tail -1); if [ "$ng2" = "6" ] && [ "$ng3" = "9" ]; then echo "-3 * -2 = 6 ; binary a-1 still subtraction (=9)"; else echo "FAIL [$ng2 / $ng3]"; fail=1; fi

echo "# LET ANNOTATION: 'let x : T = e in body' ascribes e to T (enforced, aids error localization)"
chk_hm examples/hm-letann.hm '"[Int]"'                                # let x : Int = 5 in let xs : [Int] = [x,2,3] in xs
LANN="Cons(5, Cons(2, Cons(3, Nil)))"
ssc run bin/ssctc-hm.ssc0 examples/hm-letann.hm > "${TMPDIR:-/tmp}/la.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/la.coreir" | tail -1)
if [ "$got" = "$LANN" ]; then printf 'ok   %-26s => %s\n' "let annot -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "let annot" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then ssc run bin/ssct-hm-js.ssc0 examples/hm-letann.hm > "${TMPDIR:-/tmp}/la.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/la.js" 2>/dev/null | tail -1); if [ "$got" = "$LANN" ]; then printf 'ok   %-26s => %s (node)\n' "let annot -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "let annot JS" "$got"; fail=1; fi; fi
if command -v rustc >/dev/null 2>&1; then ssc run bin/ssct-hm-rust.ssc0 examples/hm-letann.hm > "${TMPDIR:-/tmp}/la.rs" 2>/dev/null; if rustc -O "${TMPDIR:-/tmp}/la.rs" -o "${TMPDIR:-/tmp}/la-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/la-bin"); else got="(rustc err)"; fi; if [ "$got" = "$LANN" ]; then printf 'ok   %-26s => %s (rustc)\n' "let annot -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "let annot Rust" "$got"; fail=1; fi; fi
echo -n "ok   let annot enforced + plain   => "; printf 'let x : Int = true in x' > "${TMPDIR:-/tmp}/la2.hm"; la2=$(ssc run bin/ssct-hm.ssc0 "${TMPDIR:-/tmp}/la2.hm" | tail -1); printf 'let f x = x + 1 in f 9' > "${TMPDIR:-/tmp}/la3.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/la3.hm" 2>/dev/null > "${TMPDIR:-/tmp}/la3.coreir"; la3=$(ssc run-ir "${TMPDIR:-/tmp}/la3.coreir" | tail -1); if [ "$la2" = '"TypeError: type ascription mismatch"' ] && [ "$la3" = "10" ]; then echo "bad annot rejected ; fn-sugar 'let f x =' still works (=10)"; else echo "FAIL [$la2 / $la3]"; fail=1; fi

echo "# STRING ORDERING: Ord/Eq for String — =, <>, <, <=, >, >= compare lexicographically (via __strLt helper; backends unchanged)"
chk_hm examples/hm-strord.hm '"Int"'                                  # "a"<"b" etc. — a boolean battery summed
ssc run bin/ssctc-hm.ssc0 examples/hm-strord.hm > "${TMPDIR:-/tmp}/so.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/so.coreir" | tail -1)
if [ "$got" = "101" ]; then printf 'ok   %-26s => %s\n' "string ord -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "string ord" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then ssc run bin/ssct-hm-js.ssc0 examples/hm-strord.hm > "${TMPDIR:-/tmp}/so.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/so.js" 2>/dev/null | tail -1); if [ "$got" = "101" ]; then printf 'ok   %-26s => %s (node)\n' "string ord -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "string ord JS" "$got"; fail=1; fi; fi
if command -v rustc >/dev/null 2>&1; then ssc run bin/ssct-hm-rust.ssc0 examples/hm-strord.hm > "${TMPDIR:-/tmp}/so.rs" 2>/dev/null; if rustc -O "${TMPDIR:-/tmp}/so.rs" -o "${TMPDIR:-/tmp}/so-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/so-bin"); else got="(rustc err)"; fi; if [ "$got" = "101" ]; then printf 'ok   %-26s => %s (rustc)\n' "string ord -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "string ord Rust" "$got"; fail=1; fi; fi
echo -n "ok   string lexicographic + guard => "; printf 'if "apple" < "banana" then (if "cat" < "car" then 1 else 7) else 0' > "${TMPDIR:-/tmp}/so2.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/so2.hm" 2>/dev/null > "${TMPDIR:-/tmp}/so2.coreir"; so2=$(ssc run-ir "${TMPDIR:-/tmp}/so2.coreir" | tail -1); printf '"x" + "y"' > "${TMPDIR:-/tmp}/so3.hm"; so3=$(ssc run bin/ssct-hm.ssc0 "${TMPDIR:-/tmp}/so3.hm" | tail -1); if [ "$so2" = "7" ] && [ "$so3" = '"TypeError: + - * need Int or Float operands (use ++ to concatenate strings)"' ]; then echo "apple<banana ✓, cat<car ✗ => 7 ; string + still rejected"; else echo "FAIL [$so2 / $so3]"; fail=1; fi

echo "# LIST APPEND: ++ overloaded — String concat OR List append (dispatch by operand type; ambiguous defaults to String)"
chk_hm examples/hm-listconcat.hm '"Int"'                              # sum ([1,2] ++ [3,4] ++ [5]) = 15
ssc run bin/ssctc-hm.ssc0 examples/hm-listconcat.hm > "${TMPDIR:-/tmp}/lc.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/lc.coreir" | tail -1)
if [ "$got" = "15" ]; then printf 'ok   %-26s => %s\n' "list ++ -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "list ++" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then ssc run bin/ssct-hm-js.ssc0 examples/hm-listconcat.hm > "${TMPDIR:-/tmp}/lc.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/lc.js" 2>/dev/null | tail -1); if [ "$got" = "15" ]; then printf 'ok   %-26s => %s (node)\n' "list ++ -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "list ++ JS" "$got"; fail=1; fi; fi
if command -v rustc >/dev/null 2>&1; then ssc run bin/ssct-hm-rust.ssc0 examples/hm-listconcat.hm > "${TMPDIR:-/tmp}/lc.rs" 2>/dev/null; if rustc -O "${TMPDIR:-/tmp}/lc.rs" -o "${TMPDIR:-/tmp}/lc-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/lc-bin"); else got="(rustc err)"; fi; if [ "$got" = "15" ]; then printf 'ok   %-26s => %s (rustc)\n' "list ++ -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "list ++ Rust" "$got"; fail=1; fi; fi
echo -n "ok   list ++ shape + string ++ both => "; printf '[1, 2] ++ [3]' > "${TMPDIR:-/tmp}/lc2.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/lc2.hm" 2>/dev/null > "${TMPDIR:-/tmp}/lc2.coreir"; lc2=$(ssc run-ir "${TMPDIR:-/tmp}/lc2.coreir" | tail -1); printf '"foo" ++ "bar"' > "${TMPDIR:-/tmp}/lc3.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/lc3.hm" 2>/dev/null > "${TMPDIR:-/tmp}/lc3.coreir"; lc3=$(ssc run-ir "${TMPDIR:-/tmp}/lc3.coreir" | tail -1); if [ "$lc2" = "Cons(1, Cons(2, Cons(3, Nil)))" ] && [ "$lc3" = '"foobar"' ]; then echo "[1,2]++[3]=list ; \"foo\"++\"bar\"=foobar (one ++, both types)"; else echo "FAIL [$lc2 / $lc3]"; fail=1; fi

echo "# LET DESTRUCTURE: let (pat) = e in body  ->  match e { pat => body }  (tuples, nested) — used to crash"
chk_hm examples/hm-letdestr.hm '"Int"'                                # let (a,b)=(3,4) in let (c,(d,e))=(5,(6,7)) in ...
ssc run bin/ssctc-hm.ssc0 examples/hm-letdestr.hm > "${TMPDIR:-/tmp}/ld.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/ld.coreir" | tail -1)
if [ "$got" = "25" ]; then printf 'ok   %-26s => %s\n' "let destructure -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "let destructure" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then ssc run bin/ssct-hm-js.ssc0 examples/hm-letdestr.hm > "${TMPDIR:-/tmp}/ld.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/ld.js" 2>/dev/null | tail -1); if [ "$got" = "25" ]; then printf 'ok   %-26s => %s (node)\n' "let destructure -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "let destructure JS" "$got"; fail=1; fi; fi
if command -v rustc >/dev/null 2>&1; then ssc run bin/ssct-hm-rust.ssc0 examples/hm-letdestr.hm > "${TMPDIR:-/tmp}/ld.rs" 2>/dev/null; if rustc -O "${TMPDIR:-/tmp}/ld.rs" -o "${TMPDIR:-/tmp}/ld-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/ld-bin"); else got="(rustc err)"; fi; if [ "$got" = "25" ]; then printf 'ok   %-26s => %s (rustc)\n' "let destructure -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "let destructure Rust" "$got"; fail=1; fi; fi
echo -n "ok   destructure fn-result + plain => "; printf 'let swap = fun p => match p { (x, y) => (y, x) } in let (a, b) = swap (1, 2) in a * 10 + b' > "${TMPDIR:-/tmp}/ld2.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/ld2.hm" 2>/dev/null > "${TMPDIR:-/tmp}/ld2.coreir"; ld2=$(ssc run-ir "${TMPDIR:-/tmp}/ld2.coreir" | tail -1); printf 'let x = 5 in x + 1' > "${TMPDIR:-/tmp}/ld3.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/ld3.hm" 2>/dev/null > "${TMPDIR:-/tmp}/ld3.coreir"; ld3=$(ssc run-ir "${TMPDIR:-/tmp}/ld3.coreir" | tail -1); if [ "$ld2" = "21" ] && [ "$ld3" = "6" ]; then echo "swap then (a,b) => 21 ; plain let x= still works (=6)"; else echo "FAIL [$ld2 / $ld3]"; fail=1; fi

echo "# LITERAL PATTERNS: negative-int / float / negative-float patterns (used to fail/crash); positive int/str/bool/ctor unchanged"
chk_hm examples/hm-negpat.hm '"Int"'                                  # match n { 0 => .. | -1 => .. | -5 => .. | _ => .. }
ssc run bin/ssctc-hm.ssc0 examples/hm-negpat.hm > "${TMPDIR:-/tmp}/np.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/np.coreir" | tail -1)
if [ "$got" = "601" ]; then printf 'ok   %-26s => %s\n' "neg-lit pattern -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "neg-lit pattern" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then ssc run bin/ssct-hm-js.ssc0 examples/hm-negpat.hm > "${TMPDIR:-/tmp}/np.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/np.js" 2>/dev/null | tail -1); if [ "$got" = "601" ]; then printf 'ok   %-26s => %s (node)\n' "neg-lit pattern -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "neg-lit pattern JS" "$got"; fail=1; fi; fi
if command -v rustc >/dev/null 2>&1; then ssc run bin/ssct-hm-rust.ssc0 examples/hm-negpat.hm > "${TMPDIR:-/tmp}/np.rs" 2>/dev/null; if rustc -O "${TMPDIR:-/tmp}/np.rs" -o "${TMPDIR:-/tmp}/np-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/np-bin"); else got="(rustc err)"; fi; if [ "$got" = "601" ]; then printf 'ok   %-26s => %s (rustc)\n' "neg-lit pattern -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "neg-lit pattern Rust" "$got"; fail=1; fi; fi
echo -n "ok   float pattern + neg-no-match  => "; printf 'match 2.5 { 1.5 => 0 | 2.5 => 42 | _ => 9 }' > "${TMPDIR:-/tmp}/lp2.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/lp2.hm" 2>/dev/null > "${TMPDIR:-/tmp}/lp2.coreir"; lp2=$(ssc run-ir "${TMPDIR:-/tmp}/lp2.coreir" | tail -1); printf 'match 5 { -1 => 7 | _ => 0 }' > "${TMPDIR:-/tmp}/lp3.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/lp3.hm" 2>/dev/null > "${TMPDIR:-/tmp}/lp3.coreir"; lp3=$(ssc run-ir "${TMPDIR:-/tmp}/lp3.coreir" | tail -1); if [ "$lp2" = "42" ] && [ "$lp3" = "0" ]; then echo "float 2.5=>42 ; -1 vs 5 falls through =>0"; else echo "FAIL [$lp2 / $lp3]"; fail=1; fi

echo "# LIST BRACKET PATTERNS: match xs { [] => .. | [a] => .. | [a,b] => .. | Cons h t => .. } — desugars to Cons/Nil"
chk_hm examples/hm-listpat.hm '"Int"'                                # describe with [] / [a] / [a,b] / Cons arms
ssc run bin/ssctc-hm.ssc0 examples/hm-listpat.hm > "${TMPDIR:-/tmp}/lpt.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/lpt.coreir" | tail -1)
if [ "$got" = "21" ]; then printf 'ok   %-26s => %s\n' "list pattern -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "list pattern" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then ssc run bin/ssct-hm-js.ssc0 examples/hm-listpat.hm > "${TMPDIR:-/tmp}/lpt.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/lpt.js" 2>/dev/null | tail -1); if [ "$got" = "21" ]; then printf 'ok   %-26s => %s (node)\n' "list pattern -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "list pattern JS" "$got"; fail=1; fi; fi
if command -v rustc >/dev/null 2>&1; then ssc run bin/ssct-hm-rust.ssc0 examples/hm-listpat.hm > "${TMPDIR:-/tmp}/lpt.rs" 2>/dev/null; if rustc -O "${TMPDIR:-/tmp}/lpt.rs" -o "${TMPDIR:-/tmp}/lpt-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/lpt-bin"); else got="(rustc err)"; fi; if [ "$got" = "21" ]; then printf 'ok   %-26s => %s (rustc)\n' "list pattern -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "list pattern Rust" "$got"; fail=1; fi; fi
echo -n "ok   list-pat shapes + empty       => "; printf 'match [3, 4, 5] { [a, b] => 0 | [a, b, c] => a + b + c | _ => 9 }' > "${TMPDIR:-/tmp}/lpt2.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/lpt2.hm" 2>/dev/null > "${TMPDIR:-/tmp}/lpt2.coreir"; l2=$(ssc run-ir "${TMPDIR:-/tmp}/lpt2.coreir" | tail -1); printf 'match ([] : [Int]) { [] => 7 | _ => 0 }' > "${TMPDIR:-/tmp}/lpt3.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/lpt3.hm" 2>/dev/null > "${TMPDIR:-/tmp}/lpt3.coreir"; l3=$(ssc run-ir "${TMPDIR:-/tmp}/lpt3.coreir" | tail -1); if [ "$l2" = "12" ] && [ "$l3" = "7" ]; then echo "[a,b,c] picks len-3 =>12 ; [] matches empty =>7"; else echo "FAIL [$l2 / $l3]"; fail=1; fi

echo "# RECORD DESTRUCTURE: let { f = v , … } = e in body  ->  let \$r=e in let v=\$r.f in …  (type-directed, order-free)"
chk_hm examples/hm-recdestr.hm '"Int"'                               # let {lo=a,hi=b}=mk 4 in let {x=p,y=q}={..} in ..
ssc run bin/ssctc-hm.ssc0 examples/hm-recdestr.hm > "${TMPDIR:-/tmp}/rdc.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/rdc.coreir" | tail -1)
if [ "$got" = "344" ]; then printf 'ok   %-26s => %s\n' "rec destructure -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "rec destructure" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then ssc run bin/ssct-hm-js.ssc0 examples/hm-recdestr.hm > "${TMPDIR:-/tmp}/rdc.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/rdc.js" 2>/dev/null | tail -1); if [ "$got" = "344" ]; then printf 'ok   %-26s => %s (node)\n' "rec destructure -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "rec destructure JS" "$got"; fail=1; fi; fi
if command -v rustc >/dev/null 2>&1; then ssc run bin/ssct-hm-rust.ssc0 examples/hm-recdestr.hm > "${TMPDIR:-/tmp}/rdc.rs" 2>/dev/null; if rustc -O "${TMPDIR:-/tmp}/rdc.rs" -o "${TMPDIR:-/tmp}/rdc-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/rdc-bin"); else got="(rustc err)"; fi; if [ "$got" = "344" ]; then printf 'ok   %-26s => %s (rustc)\n' "rec destructure -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "rec destructure Rust" "$got"; fail=1; fi; fi
echo -n "ok   rec destr order-free + plain  => "; printf 'let {y = b, x = a} = {x = 3, y = 4} in a * 10 + b' > "${TMPDIR:-/tmp}/rd2.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/rd2.hm" 2>/dev/null > "${TMPDIR:-/tmp}/rd2.coreir"; rd2=$(ssc run-ir "${TMPDIR:-/tmp}/rd2.coreir" | tail -1); printf 'let r = {x = 1, y = 2} in r.x + r.y' > "${TMPDIR:-/tmp}/rd3.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/rd3.hm" 2>/dev/null > "${TMPDIR:-/tmp}/rd3.coreir"; rd3=$(ssc run-ir "${TMPDIR:-/tmp}/rd3.coreir" | tail -1); if [ "$rd2" = "34" ] && [ "$rd3" = "3" ]; then echo "{y=b,x=a} binds by name =>34 ; .x access still =>3"; else echo "FAIL [$rd2 / $rd3]"; fail=1; fi

echo -n "ok   parse error = clean (no crash) => "; printf 'if true then 1' > "${TMPDIR:-/tmp}/pe.hm"; pe=$(ssc run bin/ssct-hm.ssc0 "${TMPDIR:-/tmp}/pe.hm" 2>&1 | tail -1); printf 'if true then 1 else 2' > "${TMPDIR:-/tmp}/pe2.hm"; pe2=$(ssc run bin/ssct-hm.ssc0 "${TMPDIR:-/tmp}/pe2.hm" 2>&1 | tail -1); case "$pe" in *"parse error"*) peok=1;; *) peok=0;; esac; if [ "$peok" = "1" ] && [ "$pe2" = '"Int"' ]; then echo "if-no-else => clean TypeError (not a Java crash) ; valid if/else => Int"; else echo "FAIL [$pe / $pe2]"; fail=1; fi

echo "# CHAR LITERALS: 'a' lexes to its Int code (97), '\\n'=10 etc. — consistent with charAt/scodeAt returning codes"
chk_hm examples/hm-charlit.hm '"Int"'                                # isUpper via 'A'..'Z' ranges + 'Z'-'A'
ssc run bin/ssctc-hm.ssc0 examples/hm-charlit.hm > "${TMPDIR:-/tmp}/cl.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/cl.coreir" | tail -1)
if [ "$got" = "1025" ]; then printf 'ok   %-26s => %s\n' "char literal -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "char literal" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then ssc run bin/ssct-hm-js.ssc0 examples/hm-charlit.hm > "${TMPDIR:-/tmp}/cl.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/cl.js" 2>/dev/null | tail -1); if [ "$got" = "1025" ]; then printf 'ok   %-26s => %s (node)\n' "char literal -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "char literal JS" "$got"; fail=1; fi; fi
if command -v rustc >/dev/null 2>&1; then ssc run bin/ssct-hm-rust.ssc0 examples/hm-charlit.hm > "${TMPDIR:-/tmp}/cl.rs" 2>/dev/null; if rustc -O "${TMPDIR:-/tmp}/cl.rs" -o "${TMPDIR:-/tmp}/cl-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/cl-bin"); else got="(rustc err)"; fi; if [ "$got" = "1025" ]; then printf 'ok   %-26s => %s (rustc)\n' "char literal -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "char literal Rust" "$got"; fail=1; fi; fi
echo -n "ok   char code + escape + cmp      => "; printf "('a' + 0) * 100 + '\\\\n'" > "${TMPDIR:-/tmp}/cl2.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/cl2.hm" 2>/dev/null > "${TMPDIR:-/tmp}/cl2.coreir"; cl2=$(ssc run-ir "${TMPDIR:-/tmp}/cl2.coreir" | tail -1); printf "(charAt \"xyz\" 1) = 'y'" > "${TMPDIR:-/tmp}/cl3.hm"; cl3=$(ssc run bin/ssct-hm.ssc0 "${TMPDIR:-/tmp}/cl3.hm" | tail -1); if [ "$cl2" = "9710" ] && [ "$cl3" = '"Bool"' ]; then echo "'a'=97,'\\n'=10 => 9710 ; charAt = 'y' typechecks Bool"; else echo "FAIL [$cl2 / $cl3]"; fail=1; fi

echo "# FLOAT MATH: prelude fabs / fmin / fmax / fsign (definable from flt+fneg; floor/ceil too — see FLOAT ROUNDING below)"
chk_hm examples/hm-floatmath.hm '"Int"'                              # near(fmax/fmin/fsign/fabs results) summed to an Int
ssc run bin/ssctc-hm.ssc0 examples/hm-floatmath.hm > "${TMPDIR:-/tmp}/fm.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/fm.coreir" | tail -1)
if [ "$got" = "1111" ]; then printf 'ok   %-26s => %s\n' "float math -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "float math" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then ssc run bin/ssct-hm-js.ssc0 examples/hm-floatmath.hm > "${TMPDIR:-/tmp}/fm.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/fm.js" 2>/dev/null | tail -1); if [ "$got" = "1111" ]; then printf 'ok   %-26s => %s (node)\n' "float math -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "float math JS" "$got"; fail=1; fi; fi
if command -v rustc >/dev/null 2>&1; then ssc run bin/ssct-hm-rust.ssc0 examples/hm-floatmath.hm > "${TMPDIR:-/tmp}/fm.rs" 2>/dev/null; if rustc -O "${TMPDIR:-/tmp}/fm.rs" -o "${TMPDIR:-/tmp}/fm-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/fm-bin"); else got="(rustc err)"; fi; if [ "$got" = "1111" ]; then printf 'ok   %-26s => %s (rustc)\n' "float math -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "float math Rust" "$got"; fail=1; fi; fi

echo "# TRANSCENDENTALS (mathx): exp/ln/sin/cos/tan/pow/sqrt/pi — pure ssct-hm prelude (Taylor series over + - * / + fsqrt; ln range-reduced by e). IEEE-754, so bit-identical across all 3 backends"
chk_hm examples/hm-mathx.hm '"Int"'                                  # near(exp/ln/sin/cos/pow/sqrt/tan results) summed -> 1111111
ssc run bin/ssctc-hm.ssc0 examples/hm-mathx.hm > "${TMPDIR:-/tmp}/mx.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/mx.coreir" | tail -1)
if [ "$got" = "1111111" ]; then printf 'ok   %-26s => %s\n' "transcendentals -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "transcendentals" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then ssc run bin/ssct-hm-js.ssc0 examples/hm-mathx.hm > "${TMPDIR:-/tmp}/mx.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/mx.js" 2>/dev/null | tail -1); if [ "$got" = "1111111" ]; then printf 'ok   %-26s => %s (node)\n' "transcendentals -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "transcendentals JS" "$got"; fail=1; fi; fi
if command -v rustc >/dev/null 2>&1; then ssc run bin/ssct-hm-rust.ssc0 examples/hm-mathx.hm > "${TMPDIR:-/tmp}/mx.rs" 2>/dev/null; if rustc -O "${TMPDIR:-/tmp}/mx.rs" -o "${TMPDIR:-/tmp}/mx-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/mx-bin"); else got="(rustc err)"; fi; if [ "$got" = "1111111" ]; then printf 'ok   %-26s => %s (rustc)\n' "transcendentals -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "transcendentals Rust" "$got"; fail=1; fi; fi
echo -n "ok   exp 1.0 bit-identical on 3 backends => "; printf 'exp 1.0' > "${TMPDIR:-/tmp}/mxe.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/mxe.hm" 2>/dev/null > "${TMPDIR:-/tmp}/mxe.coreir"; mxv=$(ssc run-ir "${TMPDIR:-/tmp}/mxe.coreir" | tail -1); mxok=1; if command -v node >/dev/null 2>&1; then ssc run bin/ssct-hm-js.ssc0 "${TMPDIR:-/tmp}/mxe.hm" > "${TMPDIR:-/tmp}/mxe.js" 2>/dev/null; [ "$(node "${TMPDIR:-/tmp}/mxe.js" 2>/dev/null | tail -1)" = "$mxv" ] || mxok=0; fi; if command -v rustc >/dev/null 2>&1; then ssc run bin/ssct-hm-rust.ssc0 "${TMPDIR:-/tmp}/mxe.hm" > "${TMPDIR:-/tmp}/mxe.rs" 2>/dev/null; rustc -O "${TMPDIR:-/tmp}/mxe.rs" -o "${TMPDIR:-/tmp}/mxe-bin" 2>/dev/null && [ "$("${TMPDIR:-/tmp}/mxe-bin")" = "$mxv" ] || mxok=0; fi; if [ "$mxok" = "1" ]; then echo "$mxv (run-ir == JS == Rust)"; else echo "FAIL diverged"; fail=1; fi

echo "# FLOAT ROUNDING (mathx): floor/ceil/round/trunc/rint — pure prelude via the (x + 1.5*2^52) - 1.5*2^52 IEEE round-to-integer trick (NO kernel float->int prim needed). Round-to-nearest-even is IEEE default everywhere -> identical on all 3 backends"
chk_hm examples/hm-rounding.hm '"Int"'                               # floor/ceil/round/trunc/rint near-checks summed -> 1111111
ssc run bin/ssctc-hm.ssc0 examples/hm-rounding.hm > "${TMPDIR:-/tmp}/rd.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/rd.coreir" | tail -1)
if [ "$got" = "1111111" ]; then printf 'ok   %-26s => %s\n' "float rounding -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "float rounding" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then ssc run bin/ssct-hm-js.ssc0 examples/hm-rounding.hm > "${TMPDIR:-/tmp}/rd.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/rd.js" 2>/dev/null | tail -1); if [ "$got" = "1111111" ]; then printf 'ok   %-26s => %s (node)\n' "float rounding -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "float rounding JS" "$got"; fail=1; fi; fi
if command -v rustc >/dev/null 2>&1; then ssc run bin/ssct-hm-rust.ssc0 examples/hm-rounding.hm > "${TMPDIR:-/tmp}/rd.rs" 2>/dev/null; if rustc -O "${TMPDIR:-/tmp}/rd.rs" -o "${TMPDIR:-/tmp}/rd-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/rd-bin"); else got="(rustc err)"; fi; if [ "$got" = "1111111" ]; then printf 'ok   %-26s => %s (rustc)\n' "float rounding -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "float rounding Rust" "$got"; fail=1; fi; fi

echo "# MATH LIBRARY COMPLETE (mathx): inverse trig atan/asin/acos (atan = double half-angle reduce + Maclaurin), hyperbolic sinh/cosh/tanh, cbrt/hypot, log2/log10/logBase — all pure prelude. exp now range-reduced by halving (exp x = exp(x/2)^2) so it's accurate for large |x| too (exp 1.0 still bit-identical). Identical on all 3 backends"
chk_hm examples/hm-mathx2.hm '"Int"'                                 # 14 near-checks of atan/asin/acos/sinh/cosh/tanh/cbrt/hypot/log2/log10/logBase/sinh5 -> 14
ssc run bin/ssctc-hm.ssc0 examples/hm-mathx2.hm > "${TMPDIR:-/tmp}/m2.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/m2.coreir" | tail -1)
if [ "$got" = "14" ]; then printf 'ok   %-26s => %s\n' "math library -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "math library" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then ssc run bin/ssct-hm-js.ssc0 examples/hm-mathx2.hm > "${TMPDIR:-/tmp}/m2.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/m2.js" 2>/dev/null | tail -1); if [ "$got" = "14" ]; then printf 'ok   %-26s => %s (node)\n' "math library -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "math library JS" "$got"; fail=1; fi; fi
if command -v rustc >/dev/null 2>&1; then ssc run bin/ssct-hm-rust.ssc0 examples/hm-mathx2.hm > "${TMPDIR:-/tmp}/m2.rs" 2>/dev/null; if rustc -O "${TMPDIR:-/tmp}/m2.rs" -o "${TMPDIR:-/tmp}/m2-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/m2-bin"); else got="(rustc err)"; fi; if [ "$got" = "14" ]; then printf 'ok   %-26s => %s (rustc)\n' "math library -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "math library Rust" "$got"; fail=1; fi; fi

echo "# RECORD PATTERNS in match arms: match r { {f = subpat, …} => .. } — binds fields via FieldGet (order-free, nested)"
chk_hm examples/hm-recpat.hm '"Int"'                                 # {name=n, age=a} then nested {x=Some v, y=w}
ssc run bin/ssctc-hm.ssc0 examples/hm-recpat.hm > "${TMPDIR:-/tmp}/rpc.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/rpc.coreir" | tail -1)
if [ "$got" = "6530" ]; then printf 'ok   %-26s => %s\n' "record pattern -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "record pattern" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then ssc run bin/ssct-hm-js.ssc0 examples/hm-recpat.hm > "${TMPDIR:-/tmp}/rpc.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/rpc.js" 2>/dev/null | tail -1); if [ "$got" = "6530" ]; then printf 'ok   %-26s => %s (node)\n' "record pattern -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "record pattern JS" "$got"; fail=1; fi; fi
if command -v rustc >/dev/null 2>&1; then ssc run bin/ssct-hm-rust.ssc0 examples/hm-recpat.hm > "${TMPDIR:-/tmp}/rpc.rs" 2>/dev/null; if rustc -O "${TMPDIR:-/tmp}/rpc.rs" -o "${TMPDIR:-/tmp}/rpc-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/rpc-bin"); else got="(rustc err)"; fi; if [ "$got" = "6530" ]; then printf 'ok   %-26s => %s (rustc)\n' "record pattern -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "record pattern Rust" "$got"; fail=1; fi; fi
echo -n "ok   record pat order-free + ctor pat => "; printf 'match {x = 3, y = 4} { {y = b, x = a} => a * 10 + b }' > "${TMPDIR:-/tmp}/rp2.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/rp2.hm" 2>/dev/null > "${TMPDIR:-/tmp}/rp2.coreir"; rp2=$(ssc run-ir "${TMPDIR:-/tmp}/rp2.coreir" | tail -1); printf 'match (Some 5) { Some n => n | None => 0 }' > "${TMPDIR:-/tmp}/rp3.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/rp3.hm" 2>/dev/null > "${TMPDIR:-/tmp}/rp3.coreir"; rp3=$(ssc run-ir "${TMPDIR:-/tmp}/rp3.coreir" | tail -1); if [ "$rp2" = "34" ] && [ "$rp3" = "5" ]; then echo "{y=b,x=a} binds by name =>34 ; ctor pattern still =>5"; else echo "FAIL [$rp2 / $rp3]"; fail=1; fi

echo "# NON-RECURSIVE let-rec polymorphism: a gratuitous 'let rec' (no self-call) gets full let-poly (incl. numeric)"
chk_hm examples/hm-letrecpoly.hm '"Int"'                             # let rec dbl = fun x => x+x ; used at Int AND Float
ssc run bin/ssctc-hm.ssc0 examples/hm-letrecpoly.hm > "${TMPDIR:-/tmp}/lrp.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/lrp.coreir" | tail -1)
if [ "$got" = "618" ]; then printf 'ok   %-26s => %s\n' "letrec poly -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "letrec poly" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then ssc run bin/ssct-hm-js.ssc0 examples/hm-letrecpoly.hm > "${TMPDIR:-/tmp}/lrp.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/lrp.js" 2>/dev/null | tail -1); if [ "$got" = "618" ]; then printf 'ok   %-26s => %s (node)\n' "letrec poly -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "letrec poly JS" "$got"; fail=1; fi; fi
if command -v rustc >/dev/null 2>&1; then ssc run bin/ssct-hm-rust.ssc0 examples/hm-letrecpoly.hm > "${TMPDIR:-/tmp}/lrp.rs" 2>/dev/null; if rustc -O "${TMPDIR:-/tmp}/lrp.rs" -o "${TMPDIR:-/tmp}/lrp-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/lrp-bin"); else got="(rustc err)"; fi; if [ "$got" = "618" ]; then printf 'ok   %-26s => %s (rustc)\n' "letrec poly -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "letrec poly Rust" "$got"; fail=1; fi; fi
echo -n "ok   genuine recursion still works => "; printf 'let rec f = fun n => if n = 0 then 1 else n * f (n - 1) in f 6' > "${TMPDIR:-/tmp}/lr2.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/lr2.hm" 2>/dev/null > "${TMPDIR:-/tmp}/lr2.coreir"; lr2=$(ssc run-ir "${TMPDIR:-/tmp}/lr2.coreir" | tail -1); printf 'let rec sum = fun l => if isNil l then 0 else head l + sum (tail l) in sum [1, 2, 3, 4, 5]' > "${TMPDIR:-/tmp}/lr3.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/lr3.hm" 2>/dev/null > "${TMPDIR:-/tmp}/lr3.coreir"; lr3=$(ssc run-ir "${TMPDIR:-/tmp}/lr3.coreir" | tail -1); if [ "$lr2" = "720" ] && [ "$lr3" = "15" ]; then echo "fact 6 => 720 ; sum [1..5] => 15 (self-recursive unaffected)"; else echo "FAIL [$lr2 / $lr3]"; fail=1; fi

echo "# DICT-PASSING qualified types: a GENUINELY self-recursive numeric let rec used at BOTH Int and Float"
chk_hm examples/hm-dictpass.hm '"Int"'                               # let rec scale = ...x + scale x... at Int & Float
ssc run bin/ssctc-hm.ssc0 examples/hm-dictpass.hm > "${TMPDIR:-/tmp}/dp.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/dp.coreir" | tail -1)
if [ "$got" = "920" ]; then printf 'ok   %-26s => %s\n' "dict-passing -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "dict-passing" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then ssc run bin/ssct-hm-js.ssc0 examples/hm-dictpass.hm > "${TMPDIR:-/tmp}/dp.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/dp.js" 2>/dev/null | tail -1); if [ "$got" = "920" ]; then printf 'ok   %-26s => %s (node)\n' "dict-passing -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "dict-passing JS" "$got"; fail=1; fi; fi
if command -v rustc >/dev/null 2>&1; then ssc run bin/ssct-hm-rust.ssc0 examples/hm-dictpass.hm > "${TMPDIR:-/tmp}/dp.rs" 2>/dev/null; if rustc -O "${TMPDIR:-/tmp}/dp.rs" -o "${TMPDIR:-/tmp}/dp-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/dp-bin"); else got="(rustc err)"; fi; if [ "$got" = "920" ]; then printf 'ok   %-26s => %s (rustc)\n' "dict-passing -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "dict-passing Rust" "$got"; fail=1; fi; fi
echo -n "ok   dict-fn (* op) at Int & Float => "; printf 'let rec acc = fun x => fun n => if n = 0 then x else x * acc x (n - 1) in (acc 2 3) * 100 + (if flt (fabs (acc 1.5 1 - 2.25)) 0.01 then 1 else 0)' > "${TMPDIR:-/tmp}/dp2.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/dp2.hm" 2>/dev/null > "${TMPDIR:-/tmp}/dp2.coreir"; dp2=$(ssc run-ir "${TMPDIR:-/tmp}/dp2.coreir" | tail -1); if [ "$dp2" = "1601" ]; then echo "acc 2 3 = 16 (*100) ; acc 1.5 1 = 2.25 (~) => 1601"; else echo "FAIL [$dp2]"; fail=1; fi

echo "# DICT-PASSING over Ord: a recursive function using < / = on a polymorphic value, at Int AND Float AND String"
chk_hm examples/hm-dictord.hm '"Int"'                                # maxOf via < at Int, Float, String
ssc run bin/ssctc-hm.ssc0 examples/hm-dictord.hm > "${TMPDIR:-/tmp}/do.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/do.coreir" | tail -1)
if [ "$got" = "511" ]; then printf 'ok   %-26s => %s\n' "dict ord -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "dict ord" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then ssc run bin/ssct-hm-js.ssc0 examples/hm-dictord.hm > "${TMPDIR:-/tmp}/do.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/do.js" 2>/dev/null | tail -1); if [ "$got" = "511" ]; then printf 'ok   %-26s => %s (node)\n' "dict ord -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "dict ord JS" "$got"; fail=1; fi; fi
if command -v rustc >/dev/null 2>&1; then ssc run bin/ssct-hm-rust.ssc0 examples/hm-dictord.hm > "${TMPDIR:-/tmp}/do.rs" 2>/dev/null; if rustc -O "${TMPDIR:-/tmp}/do.rs" -o "${TMPDIR:-/tmp}/do-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/do-bin"); else got="(rustc err)"; fi; if [ "$got" = "511" ]; then printf 'ok   %-26s => %s (rustc)\n' "dict ord -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "dict ord Rust" "$got"; fail=1; fi; fi
echo -n "ok   dict-fn = at Int & String     => "; printf 'let rec countEq = fun x => fun xs => match xs { Cons h t => if x = h then 1 + countEq x t else countEq x t | Nil => 0 } in (countEq 2 [2, 3, 2, 2]) * 10 + (countEq "a" ["a", "b", "a"])' > "${TMPDIR:-/tmp}/do2.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/do2.hm" 2>/dev/null > "${TMPDIR:-/tmp}/do2.coreir"; do2=$(ssc run-ir "${TMPDIR:-/tmp}/do2.coreir" | tail -1); if [ "$do2" = "32" ]; then echo "countEq 2 (3 times) =3*10 ; countEq a (2 times) =2 => 32"; else echo "FAIL [$do2]"; fail=1; fi

echo "# SINGLE VAR-ARM MATCH: match scrut { x => body } == let x = scrut in body (was a run-ir crash on a scalar)"
chk_hm examples/hm-scalarmatch.hm '"Int"'                            # match 7 { x => x+1 } etc.
ssc run bin/ssctc-hm.ssc0 examples/hm-scalarmatch.hm > "${TMPDIR:-/tmp}/sm.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/sm.coreir" | tail -1)
if [ "$got" = "861" ]; then printf 'ok   %-26s => %s\n' "scalar var-match -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "scalar var-match" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then ssc run bin/ssct-hm-js.ssc0 examples/hm-scalarmatch.hm > "${TMPDIR:-/tmp}/sm.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/sm.js" 2>/dev/null | tail -1); if [ "$got" = "861" ]; then printf 'ok   %-26s => %s (node)\n' "scalar var-match -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "scalar var-match JS" "$got"; fail=1; fi; fi
if command -v rustc >/dev/null 2>&1; then ssc run bin/ssct-hm-rust.ssc0 examples/hm-scalarmatch.hm > "${TMPDIR:-/tmp}/sm.rs" 2>/dev/null; if rustc -O "${TMPDIR:-/tmp}/sm.rs" -o "${TMPDIR:-/tmp}/sm-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/sm-bin"); else got="(rustc err)"; fi; if [ "$got" = "861" ]; then printf 'ok   %-26s => %s (rustc)\n' "scalar var-match -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "scalar var-match Rust" "$got"; fail=1; fi; fi

echo "# USER-CLASS DICT-PASSING: a recursive fn generic over a USER typeclass method, at user types Color & Shape"
chk_hm examples/hm-userdict.hm '"Int"'                               # let rec acc = ... tone x + acc x ... at Color & Shape
ssc run bin/ssctc-hm.ssc0 examples/hm-userdict.hm > "${TMPDIR:-/tmp}/ud.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/ud.coreir" | tail -1)
if [ "$got" = "1433" ]; then printf 'ok   %-26s => %s\n' "user-class dict -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "user-class dict" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then ssc run bin/ssct-hm-js.ssc0 examples/hm-userdict.hm > "${TMPDIR:-/tmp}/ud.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/ud.js" 2>/dev/null | tail -1); if [ "$got" = "1433" ]; then printf 'ok   %-26s => %s (node)\n' "user-class dict -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "user-class dict JS" "$got"; fail=1; fi; fi
if command -v rustc >/dev/null 2>&1; then ssc run bin/ssct-hm-rust.ssc0 examples/hm-userdict.hm > "${TMPDIR:-/tmp}/ud.rs" 2>/dev/null; if rustc -O "${TMPDIR:-/tmp}/ud.rs" -o "${TMPDIR:-/tmp}/ud-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/ud-bin"); else got="(rustc err)"; fi; if [ "$got" = "1433" ]; then printf 'ok   %-26s => %s (rustc)\n' "user-class dict -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "user-class dict Rust" "$got"; fail=1; fi; fi
echo -n "ok   user method at Int & Bool too => "; printf 'method sz : Int in instance sz Int = fun n => 5 in instance sz Bool = fun b => 9 in let rec count = fun x => fun n => if n = 0 then 0 else sz x + count x (n - 1) in (count 0 3) * 10 + (count true 2)' > "${TMPDIR:-/tmp}/ud2.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/ud2.hm" 2>/dev/null > "${TMPDIR:-/tmp}/ud2.coreir"; ud2=$(ssc run-ir "${TMPDIR:-/tmp}/ud2.coreir" | tail -1); if [ "$ud2" = "168" ]; then echo "count(sz) at Int=15 (*10), Bool=18 => 168"; else echo "FAIL [$ud2]"; fail=1; fi

echo "# PRELUDE 2: takeWhile/dropWhile/span/partition/scanl/lookup/maximum/minimum/count/nub/enumerate"
chk_hm examples/hm-prelude3.hm '"Int"'                               # maximum/nub/count/lookup combined
ssc run bin/ssctc-hm.ssc0 examples/hm-prelude3.hm > "${TMPDIR:-/tmp}/p3.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/p3.coreir" | tail -1)
if [ "$got" = "5329" ]; then printf 'ok   %-26s => %s\n' "prelude2 -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "prelude2" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then ssc run bin/ssct-hm-js.ssc0 examples/hm-prelude3.hm > "${TMPDIR:-/tmp}/p3.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/p3.js" 2>/dev/null | tail -1); if [ "$got" = "5329" ]; then printf 'ok   %-26s => %s (node)\n' "prelude2 -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "prelude2 JS" "$got"; fail=1; fi; fi
if command -v rustc >/dev/null 2>&1; then ssc run bin/ssct-hm-rust.ssc0 examples/hm-prelude3.hm > "${TMPDIR:-/tmp}/p3.rs" 2>/dev/null; if rustc -O "${TMPDIR:-/tmp}/p3.rs" -o "${TMPDIR:-/tmp}/p3-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/p3-bin"); else got="(rustc err)"; fi; if [ "$got" = "5329" ]; then printf 'ok   %-26s => %s (rustc)\n' "prelude2 -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "prelude2 Rust" "$got"; fail=1; fi; fi
echo -n "ok   takeWhile/span/partition shape => "; printf 'match span (fun x => x < 3) [1, 2, 3, 4] { (a, b) => length a * 10 + length b }' > "${TMPDIR:-/tmp}/p32.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/p32.hm" 2>/dev/null > "${TMPDIR:-/tmp}/p32.coreir"; p32=$(ssc run-ir "${TMPDIR:-/tmp}/p32.coreir" | tail -1); printf 'match partition (fun x => x > 2) [1, 2, 3, 4] { (a, b) => length a * 10 + length b }' > "${TMPDIR:-/tmp}/p33.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/p33.hm" 2>/dev/null > "${TMPDIR:-/tmp}/p33.coreir"; p33=$(ssc run-ir "${TMPDIR:-/tmp}/p33.coreir" | tail -1); if [ "$p32" = "22" ] && [ "$p33" = "22" ]; then echo "span (<3) => (2 | 2) => 22 ; partition (>2) => (2 | 2) => 22"; else echo "FAIL [$p32 / $p33]"; fail=1; fi

echo "# STRING FUNCTIONS 2: startsWith / endsWith / strContains / trim (substr/charAt-based; no kernel prim needed)"
chk_hm examples/hm-strfns2.hm '"Int"'                                # startsWith/endsWith/strContains/trim combined
ssc run bin/ssctc-hm.ssc0 examples/hm-strfns2.hm > "${TMPDIR:-/tmp}/sf.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/sf.coreir" | tail -1)
if [ "$got" = "1112" ]; then printf 'ok   %-26s => %s\n' "string fns 2 -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "string fns 2" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then ssc run bin/ssct-hm-js.ssc0 examples/hm-strfns2.hm > "${TMPDIR:-/tmp}/sf.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/sf.js" 2>/dev/null | tail -1); if [ "$got" = "1112" ]; then printf 'ok   %-26s => %s (node)\n' "string fns 2 -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "string fns 2 JS" "$got"; fail=1; fi; fi
if command -v rustc >/dev/null 2>&1; then ssc run bin/ssct-hm-rust.ssc0 examples/hm-strfns2.hm > "${TMPDIR:-/tmp}/sf.rs" 2>/dev/null; if rustc -O "${TMPDIR:-/tmp}/sf.rs" -o "${TMPDIR:-/tmp}/sf-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/sf-bin"); else got="(rustc err)"; fi; if [ "$got" = "1112" ]; then printf 'ok   %-26s => %s (rustc)\n' "string fns 2 -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "string fns 2 Rust" "$got"; fail=1; fi; fi
echo -n "ok   trim edges + contains miss   => "; printf 'strLen (trim "   ") * 100 + strLen (trim "  z  ") * 10 + (if strContains "q" "hello" then 1 else 0)' > "${TMPDIR:-/tmp}/sf2.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/sf2.hm" 2>/dev/null > "${TMPDIR:-/tmp}/sf2.coreir"; sf2=$(ssc run-ir "${TMPDIR:-/tmp}/sf2.coreir" | tail -1); if [ "$sf2" = "10" ]; then echo "trim all-spaces=0 ; trim \"  z  \"=1 (*10) ; contains miss=0 => 10"; else echo "FAIL [$sf2]"; fail=1; fi

echo -n "ok   empty match = clean error    => "; printf 'match 5 { }' > "${TMPDIR:-/tmp}/em.hm"; em=$(ssc run bin/ssct-hm.ssc0 "${TMPDIR:-/tmp}/em.hm" 2>&1 | tail -1); printf 'match 5 { 1 => 10 | _ => 0 }' > "${TMPDIR:-/tmp}/em2.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/em2.hm" 2>/dev/null > "${TMPDIR:-/tmp}/em2.coreir"; em2=$(ssc run-ir "${TMPDIR:-/tmp}/em2.coreir" | tail -1); case "$em" in *"empty match"*) emok=1;; *) emok=0;; esac; if [ "$emok" = "1" ] && [ "$em2" = "0" ]; then echo "empty match => clean TypeError (not a crash) ; normal match still works"; else echo "FAIL [$em / $em2]"; fail=1; fi

echo "# STRING BUILDING: fromCodes ([Int]->String) intrinsic + chr / toUpper / toLower (prelude)"
chk_hm examples/hm-strbuild.hm '"Int"'                               # toUpper/toLower + charAt of results
ssc run bin/ssctc-hm.ssc0 examples/hm-strbuild.hm > "${TMPDIR:-/tmp}/sb.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/sb.coreir" | tail -1)
if [ "$got" = "662" ]; then printf 'ok   %-26s => %s\n' "string build -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "string build" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then ssc run bin/ssct-hm-js.ssc0 examples/hm-strbuild.hm > "${TMPDIR:-/tmp}/sb.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/sb.js" 2>/dev/null | tail -1); if [ "$got" = "662" ]; then printf 'ok   %-26s => %s (node)\n' "string build -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "string build JS" "$got"; fail=1; fi; fi
if command -v rustc >/dev/null 2>&1; then ssc run bin/ssct-hm-rust.ssc0 examples/hm-strbuild.hm > "${TMPDIR:-/tmp}/sb.rs" 2>/dev/null; if rustc -O "${TMPDIR:-/tmp}/sb.rs" -o "${TMPDIR:-/tmp}/sb-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/sb-bin"); else got="(rustc err)"; fi; if [ "$got" = "662" ]; then printf 'ok   %-26s => %s (rustc)\n' "string build -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "string build Rust" "$got"; fail=1; fi; fi
echo -n "ok   fromCodes + chr + roundtrip   => "; printf 'strLen (fromCodes [72, 105]) * 100 + (charAt (chr 90) 0) + (if toLower (toUpper "Yo") = "yo" then 1 else 0)' > "${TMPDIR:-/tmp}/sb2.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/sb2.hm" 2>/dev/null > "${TMPDIR:-/tmp}/sb2.coreir"; sb2=$(ssc run-ir "${TMPDIR:-/tmp}/sb2.coreir" | tail -1); if [ "$sb2" = "291" ]; then echo "fromCodes[72,105]=\"Hi\" len 2 (*100) ; chr 90='Z'=90 ; roundtrip=1 => 291"; else echo "FAIL [$sb2]"; fail=1; fi

echo "# PRELUDE BATCH: compose / flip / min / max / elem / notElem / product / last / null / join"
chk_hm examples/hm-preludecombi.hm '"Int"'                           # min/max/product/last combined
ssc run bin/ssctc-hm.ssc0 examples/hm-preludecombi.hm > "${TMPDIR:-/tmp}/pc.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/pc.coreir" | tail -1)
if [ "$got" = "11453" ]; then printf 'ok   %-26s => %s\n' "prelude batch -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "prelude batch" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then ssc run bin/ssct-hm-js.ssc0 examples/hm-preludecombi.hm > "${TMPDIR:-/tmp}/pc.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/pc.js" 2>/dev/null | tail -1); if [ "$got" = "11453" ]; then printf 'ok   %-26s => %s (node)\n' "prelude batch -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "prelude batch JS" "$got"; fail=1; fi; fi
if command -v rustc >/dev/null 2>&1; then ssc run bin/ssct-hm-rust.ssc0 examples/hm-preludecombi.hm > "${TMPDIR:-/tmp}/pc.rs" 2>/dev/null; if rustc -O "${TMPDIR:-/tmp}/pc.rs" -o "${TMPDIR:-/tmp}/pc-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/pc-bin"); else got="(rustc err)"; fi; if [ "$got" = "11453" ]; then printf 'ok   %-26s => %s (rustc)\n' "prelude batch -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "prelude batch Rust" "$got"; fail=1; fi; fi
echo -n "ok   compose/flip/elem/notElem/join => "; printf '(compose (fun x => x + 1) (fun x => x * 2)) 5 * 100 + (flip (fun a => fun b => a - b) 3 10) * 10 + (if elem 2 [1, 2] then (if notElem 9 [1, 2] then strLen (join "-" ["x", "y"]) else 0) else 0)' > "${TMPDIR:-/tmp}/pc2.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/pc2.hm" 2>/dev/null > "${TMPDIR:-/tmp}/pc2.coreir"; pc2=$(ssc run-ir "${TMPDIR:-/tmp}/pc2.coreir" | tail -1); if [ "$pc2" = "1173" ]; then echo "compose=11*100 ; flip sub=7*10 ; elem&notElem -> join len 3 => 1173"; else echo "FAIL [$pc2]"; fail=1; fi

echo "# AS-PATTERNS: name@pat binds the whole matched value AND destructures (top-level + as a ctor sub-pattern)"
chk_hm examples/hm-aspat.hm '"Int"'                                  # dedup using  Cons a rest@(Cons b t)
ssc run bin/ssctc-hm.ssc0 examples/hm-aspat.hm > "${TMPDIR:-/tmp}/apc.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/apc.coreir" | tail -1)
if [ "$got" = "4" ]; then printf 'ok   %-26s => %s\n' "as-pattern -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "as-pattern" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then ssc run bin/ssct-hm-js.ssc0 examples/hm-aspat.hm > "${TMPDIR:-/tmp}/apc.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/apc.js" 2>/dev/null | tail -1); if [ "$got" = "4" ]; then printf 'ok   %-26s => %s (node)\n' "as-pattern -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "as-pattern JS" "$got"; fail=1; fi; fi
if command -v rustc >/dev/null 2>&1; then ssc run bin/ssct-hm-rust.ssc0 examples/hm-aspat.hm > "${TMPDIR:-/tmp}/apc.rs" 2>/dev/null; if rustc -O "${TMPDIR:-/tmp}/apc.rs" -o "${TMPDIR:-/tmp}/apc-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/apc-bin"); else got="(rustc err)"; fi; if [ "$got" = "4" ]; then printf 'ok   %-26s => %s (rustc)\n' "as-pattern -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "as-pattern Rust" "$got"; fail=1; fi; fi
echo -n "ok   as-pattern top + literal     => "; printf 'match [1, 2, 3] { all@(Cons h t) => h + length all | Nil => 0 }' > "${TMPDIR:-/tmp}/ap2.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/ap2.hm" 2>/dev/null > "${TMPDIR:-/tmp}/ap2.coreir"; ap2=$(ssc run-ir "${TMPDIR:-/tmp}/ap2.coreir" | tail -1); printf 'match 5 { n@5 => n * 2 | _ => 0 }' > "${TMPDIR:-/tmp}/ap3.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/ap3.hm" 2>/dev/null > "${TMPDIR:-/tmp}/ap3.coreir"; ap3=$(ssc run-ir "${TMPDIR:-/tmp}/ap3.coreir" | tail -1); if [ "$ap2" = "4" ] && [ "$ap3" = "10" ]; then echo "all@(Cons h t): 1+len 3 => 4 ; n@5: 5*2 => 10"; else echo "FAIL [$ap2 / $ap3]"; fail=1; fi

echo "# OPERATOR SECTIONS: (+ 1), (* 2), (< 5), (++ \"!\") = fun x => x OP e — for point-free map/filter"
chk_hm examples/hm-opsections.hm '"Int"'                             # sum(map (* 3) (filter (< 4) ..)) + length(filter (= 2) ..)
ssc run bin/ssctc-hm.ssc0 examples/hm-opsections.hm > "${TMPDIR:-/tmp}/osc.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/osc.coreir" | tail -1)
if [ "$got" = "21" ]; then printf 'ok   %-26s => %s\n' "op section -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "op section" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then ssc run bin/ssct-hm-js.ssc0 examples/hm-opsections.hm > "${TMPDIR:-/tmp}/osc.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/osc.js" 2>/dev/null | tail -1); if [ "$got" = "21" ]; then printf 'ok   %-26s => %s (node)\n' "op section -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "op section JS" "$got"; fail=1; fi; fi
if command -v rustc >/dev/null 2>&1; then ssc run bin/ssct-hm-rust.ssc0 examples/hm-opsections.hm > "${TMPDIR:-/tmp}/osc.rs" 2>/dev/null; if rustc -O "${TMPDIR:-/tmp}/osc.rs" -o "${TMPDIR:-/tmp}/osc-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/osc-bin"); else got="(rustc err)"; fi; if [ "$got" = "21" ]; then printf 'ok   %-26s => %s (rustc)\n' "op section -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "op section Rust" "$got"; fail=1; fi; fi
echo -n "ok   section + paren/neg unchanged => "; printf '(++ "!") "hi"' > "${TMPDIR:-/tmp}/os2.hm"; os2=$(ssc run bin/ssct-hm.ssc0 "${TMPDIR:-/tmp}/os2.hm" | tail -1); printf 'let x = -5 in x * (3 + 1)' > "${TMPDIR:-/tmp}/os3.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/os3.hm" 2>/dev/null > "${TMPDIR:-/tmp}/os3.coreir"; os3=$(ssc run-ir "${TMPDIR:-/tmp}/os3.coreir" | tail -1); if [ "$os2" = '"String"' ] && [ "$os3" = "-20" ]; then echo "(++ \"!\") : String->String ; (3+1) paren + -5 neg => -20"; else echo "FAIL [$os2 / $os3]"; fail=1; fi

echo "# LEFT OPERATOR SECTIONS: (10 -), (100 /), (5 <), (\"hi\" ++) = fun x => e OP x — complement to right sections; '-' allowed (follows operand, no neg ambiguity)"
chk_hm examples/hm-lsections.hm '"Int"'                              # sum(map (100 -) ..) + (20 /) 4 + length(filter (10 <) ..)
ssc run bin/ssctc-hm.ssc0 examples/hm-lsections.hm > "${TMPDIR:-/tmp}/lsc.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/lsc.coreir" | tail -1)
if [ "$got" = "247" ]; then printf 'ok   %-26s => %s\n' "left section -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "left section" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then ssc run bin/ssct-hm-js.ssc0 examples/hm-lsections.hm > "${TMPDIR:-/tmp}/lsc.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/lsc.js" 2>/dev/null | tail -1); if [ "$got" = "247" ]; then printf 'ok   %-26s => %s (node)\n' "left section -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "left section JS" "$got"; fail=1; fi; fi
if command -v rustc >/dev/null 2>&1; then ssc run bin/ssct-hm-rust.ssc0 examples/hm-lsections.hm > "${TMPDIR:-/tmp}/lsc.rs" 2>/dev/null; if rustc -O "${TMPDIR:-/tmp}/lsc.rs" -o "${TMPDIR:-/tmp}/lsc-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/lsc-bin"); else got="(rustc err)"; fi; if [ "$got" = "247" ]; then printf 'ok   %-26s => %s (rustc)\n' "left section -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "left section Rust" "$got"; fail=1; fi; fi
echo -n "ok   left/right sections + tuple/ascr coexist => "; printf '((10 -) 3) + ((+ 1) 4)' > "${TMPDIR:-/tmp}/ls2.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/ls2.hm" 2>/dev/null > "${TMPDIR:-/tmp}/ls2.coreir"; ls2=$(ssc run-ir "${TMPDIR:-/tmp}/ls2.coreir" | tail -1); printf '(3, 4)' > "${TMPDIR:-/tmp}/ls3.hm"; ls3=$(ssc run bin/ssct-hm.ssc0 "${TMPDIR:-/tmp}/ls3.hm" | tail -1); if [ "$ls2" = "12" ] && [ "$ls3" = '"(Int, Int)"' ]; then echo "(10 -) 3 + (+ 1) 4 => 12 ; (3,4) tuple intact"; else echo "FAIL [$ls2 / $ls3]"; fail=1; fi

echo "# STRING FUNCTIONS: split (by char code) / words / lines — prelude, char-iterating; roundtrips with join"
chk_hm examples/hm-stringfns.hm '"Int"'                              # split/words + join-split roundtrip
ssc run bin/ssctc-hm.ssc0 examples/hm-stringfns.hm > "${TMPDIR:-/tmp}/sfc.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/sfc.coreir" | tail -1)
if [ "$got" = "431" ]; then printf 'ok   %-26s => %s\n' "string fns -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "string fns" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then ssc run bin/ssct-hm-js.ssc0 examples/hm-stringfns.hm > "${TMPDIR:-/tmp}/sfc.js" 2>/dev/null; got=$(node "${TMPDIR:-/tmp}/sfc.js" 2>/dev/null | tail -1); if [ "$got" = "431" ]; then printf 'ok   %-26s => %s (node)\n' "string fns -> JS" "$got"; else printf 'FAIL %-26s got [%s]\n' "string fns JS" "$got"; fail=1; fi; fi
if command -v rustc >/dev/null 2>&1; then ssc run bin/ssct-hm-rust.ssc0 examples/hm-stringfns.hm > "${TMPDIR:-/tmp}/sfc.rs" 2>/dev/null; if rustc -O "${TMPDIR:-/tmp}/sfc.rs" -o "${TMPDIR:-/tmp}/sfc-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/sfc-bin"); else got="(rustc err)"; fi; if [ "$got" = "431" ]; then printf 'ok   %-26s => %s (rustc)\n' "string fns -> Rust" "$got"; else printf 'FAIL %-26s got [%s]\n' "string fns Rust" "$got"; fail=1; fi; fi
echo -n "ok   split by char literal + words => "; printf "length (split ',' \"a,b,c\") * 10 + length (words \"p q r s\")" > "${TMPDIR:-/tmp}/sf2.hm"; ssc run bin/ssctc-hm.ssc0 "${TMPDIR:-/tmp}/sf2.hm" 2>/dev/null > "${TMPDIR:-/tmp}/sf2.coreir"; sf2=$(ssc run-ir "${TMPDIR:-/tmp}/sf2.coreir" | tail -1); if [ "$sf2" = "34" ]; then echo "split ',' (3) *10 + words (4) => 34"; else echo "FAIL [$sf2]"; fail=1; fi

LMAP="Cons(1, Cons(4, Cons(9, Nil)))"
ssc run bin/ssctc-hm.ssc0 examples/hm-map.hm > "${TMPDIR:-/tmp}/tmap.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/tmap.coreir" | tail -1)
if [ "$got" = "$LMAP" ]; then printf 'ok   %-26s => %s\n' "ssctc-hm map.hm -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "ssctc-hm map.hm" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-map.hm > "${TMPDIR:-/tmp}/tmap.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/tmap.js" 2>/dev/null | tail -1)
  if [ "$got" = "$LMAP" ]; then printf 'ok   %-26s => %s (node)\n' "ssct-hm-js map.hm" "$got"; else printf 'FAIL %-26s got [%s]\n' "ssct-hm-js map.hm" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-map.hm > "${TMPDIR:-/tmp}/tmap.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/tmap.rs" -o "${TMPDIR:-/tmp}/tmap-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/tmap-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$LMAP" ]; then printf 'ok   %-26s => %s (rustc)\n' "ssct-hm-rust map.hm" "$got"; else printf 'FAIL %-26s got [%s]\n' "ssct-hm-rust map.hm" "$got"; fail=1; fi
fi

echo "# ssct-hm TYPED QUICKSORT: filter/append/qsort + less-than as source text -> infer [Int] -> 3 backends"
chk_hm examples/hm-qsort.hm '"[Int]"'
QS="Cons(1, Cons(2, Cons(3, Cons(4, Nil))))"
ssc run bin/ssctc-hm.ssc0 examples/hm-qsort.hm > "${TMPDIR:-/tmp}/qs.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/qs.coreir" | tail -1)
if [ "$got" = "$QS" ]; then printf 'ok   %-26s => %s\n' "ssctc-hm qsort.hm -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "ssctc-hm qsort.hm" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-qsort.hm > "${TMPDIR:-/tmp}/qs.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/qs.js" 2>/dev/null | tail -1)
  if [ "$got" = "$QS" ]; then printf 'ok   %-26s => %s (node)\n' "ssct-hm-js qsort.hm" "$got"; else printf 'FAIL %-26s got [%s]\n' "ssct-hm-js qsort.hm" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-qsort.hm > "${TMPDIR:-/tmp}/qs.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/qs.rs" -o "${TMPDIR:-/tmp}/qs-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/qs-bin"); else got="(rustc err)"; fi
  if [ "$got" = "$QS" ]; then printf 'ok   %-26s => %s (rustc)\n' "ssct-hm-rust qsort.hm" "$got"; else printf 'FAIL %-26s got [%s]\n' "ssct-hm-rust qsort.hm" "$got"; fail=1; fi
fi

echo "# ssct-hm TEXTUAL ADTs: capitalized constructors + match { | } as source text -> 3 backends"
chk_hm examples/hm-adt-match.hm '"Int"'                              # match (Some 5) { Some x => x | None => 0 }
ssc run bin/ssctc-hm.ssc0 examples/hm-adt-match.hm > "${TMPDIR:-/tmp}/om.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/om.coreir" | tail -1)
if [ "$got" = "5" ]; then printf 'ok   %-26s => %s\n' "ssctc-hm match.hm -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "ssctc-hm match.hm" "$got"; fail=1; fi
chk_hm examples/hm-adt-tree.hm  '"Int"'                              # recursive tree-sum via match
echo "# ssct-hm USER data DECLS: declare your own types in text -> type-checked + compiled"
chk_hm examples/hm-data-box.hm   '"Box Int"'                         # data Box a = Box a in Box 7
chk_hm examples/hm-data-shape.hm '"Int"'                            # data Shape a = Circle a | Rect a a
ssc run bin/ssctc-hm.ssc0 examples/hm-data-shape.hm > "${TMPDIR:-/tmp}/sh.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/sh.coreir" | tail -1)
if [ "$got" = "12" ]; then printf 'ok   %-26s => %s\n' "data Shape -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "data Shape" "$got"; fail=1; fi
chk_hm examples/hm-data-tree.hm  '"Int"'                            # user-declared recursive Tree
ssc run bin/ssctc-hm.ssc0 examples/hm-data-tree.hm > "${TMPDIR:-/tmp}/mt.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/mt.coreir" | tail -1)
if [ "$got" = "6" ]; then printf 'ok   %-26s => %s\n' "data MyTree -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "data MyTree" "$got"; fail=1; fi

ssc run bin/ssctc-hm.ssc0 examples/hm-adt-tree.hm > "${TMPDIR:-/tmp}/tt.coreir" 2>/dev/null
got=$(ssc run-ir "${TMPDIR:-/tmp}/tt.coreir" | tail -1)
if [ "$got" = "6" ]; then printf 'ok   %-26s => %s\n' "ssctc-hm tree.hm -> run-ir" "$got"; else printf 'FAIL %-26s got [%s]\n' "ssctc-hm tree.hm" "$got"; fail=1; fi
if command -v node >/dev/null 2>&1; then
  ssc run bin/ssct-hm-js.ssc0 examples/hm-adt-tree.hm > "${TMPDIR:-/tmp}/tt.js" 2>/dev/null
  got=$(node "${TMPDIR:-/tmp}/tt.js" 2>/dev/null | tail -1)
  if [ "$got" = "6" ]; then printf 'ok   %-26s => %s (node)\n' "ssct-hm-js tree.hm" "$got"; else printf 'FAIL %-26s got [%s]\n' "ssct-hm-js tree.hm" "$got"; fail=1; fi
fi
if command -v rustc >/dev/null 2>&1; then
  ssc run bin/ssct-hm-rust.ssc0 examples/hm-adt-tree.hm > "${TMPDIR:-/tmp}/tt.rs" 2>/dev/null
  if rustc -O "${TMPDIR:-/tmp}/tt.rs" -o "${TMPDIR:-/tmp}/tt-bin" 2>/dev/null; then got=$("${TMPDIR:-/tmp}/tt-bin"); else got="(rustc err)"; fi
  if [ "$got" = "6" ]; then printf 'ok   %-26s => %s (rustc)\n' "ssct-hm-rust tree.hm" "$got"; else printf 'FAIL %-26s got [%s]\n' "ssct-hm-rust tree.hm" "$got"; fail=1; fi
fi

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

echo "# MULTI-FILE: ssc0c resolves imports == Scala (uselib); and the multi-file fixpoint"
ua=$(ssc compile examples/uselib.ssc0); ub=$(ssc run bin/ssc0c.ssc0 examples/uselib.ssc0)
if [ -n "$ua" ] && [ "$ua" = "$ub" ]; then printf 'ok   %-26s => byte-identical ir (across import)\n' "ssc0c uselib.ssc0"
else printf 'FAIL %-26s ir differs\n' "ssc0c uselib.ssc0"; fail=1; fi
# the multi-file driver bin/ssc0c.ssc0 (imports lib/ssc0c.ssc0) compiles ITSELF, byte-for-byte
m1=$(ssc compile bin/ssc0c.ssc0)
printf '%s' "$m1" > "${TMPDIR:-/tmp}/m3-gen1.ir"
m2=$(java -Xss512m -jar "$JAR" run-ir "${TMPDIR:-/tmp}/m3-gen1.ir" bin/ssc0c.ssc0 2>/dev/null)
if [ -n "$m1" ] && [ "$m1" = "$m2" ]; then printf 'ok   %-26s => reproduces itself (%s bytes)\n' "ssc0c MULTI-FILE FIXPOINT" "${#m1}"
else printf 'FAIL %-26s m1(%s) != m2(%s)\n' "ssc0c MULTI-FILE FIXPOINT" "${#m1}" "${#m2}"; fail=1; fi

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
  chk_js quicksort
  chk_js quicksort-lib   # multi-file: imports lib/list (backend resolves imports)
  chk_js zipwith
  chk_js map-demo
  chk_js string-build '"n=42"'
  chk_js stream-squares
  chk_js letrec-fact
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
  chk_rust quicksort
  chk_rust quicksort-lib   # multi-file: imports lib/list (backend resolves imports)
  chk_rust zipwith
  chk_rust map-demo
  chk_rust string-build '"n=42"'
  chk_rust stream-squares
  chk_rust letrec-fact
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
