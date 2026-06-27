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
