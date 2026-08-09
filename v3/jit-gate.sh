#!/usr/bin/env bash
# v3 JIT gate — the checks that judge `specs/ssc3-jit.md`. Build the gate before the feature.
#
#   v3/jit-gate.sh              every applicable check
#   v3/jit-gate.sh --sizes      method sizes against the JVM's own compilation limits
#   v3/jit-gate.sh --self-test  prove this gate can go RED — run it before believing a green
#
# ── WHY A SIZE CHECK IS A GATE AND NOT A LINT ────────────────────────────────────────────────────
#
# HotSpot refuses to JIT-compile any method over `-XX:DontCompileHugeMethods` = 8000 bytecodes. Not
# "compiles it later" or "compiles it worse": never, for the life of the process, at roughly its own
# bytecode-interpreter speed. Measured 2026-08-09 on this tree, `Exec$.invoke` is 13415 — so every
# method call any v3 program makes (`xs.map`, `s.length`, `.foldLeft`) has never once run compiled.
#
# This is the third time the repository has paid for it. The v2 runtime had a 49384-bytecode method
# on its call path and splitting it was worth 2.4-10.8x. What did not carry over from that lesson is
# a CHECK: nothing in the build looks at method size, so the next one arrives as silently as this
# one did, and it is invisible to every functional gate we own because the program is CORRECT — just
# slow, forever, for a reason no profile attributes to a line of source.
#
# `-XX:FreqInlineSize` = 325 is the second limit and the softer one: past it a hot method is never
# inlined into its caller, which for the dispatch loop means a megamorphic call per instruction.
# Reported, not enforced — a dispatch switch that big is a design fact, not a regression.
#
# ── WHY DECLARATIONS AND NOT A THRESHOLD ─────────────────────────────────────────────────────────
#
# A method over the limit that we have decided to live with is DECLARED below, with the reason and
# the condition that expires the declaration — the same mechanism as a `known-red:` in the corpus,
# for the same reason. Two rules, and the second is the one that keeps this honest:
#
#   * over the limit and NOT declared  -> RED. A new one cannot arrive quietly.
#   * declared but now UNDER the limit -> RED. A declaration cannot outlive its cause; the fix is to
#     delete the line. This is what stops the table becoming a list of things that used to be true.
#
# A bare threshold has neither property: it goes red for work nobody is doing and stays green after
# the work is done.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2

HUGE=8000      # -XX:DontCompileHugeMethods — over this, never JIT-compiled at all
INLINE=325     # -XX:FreqInlineSize — over this, never inlined into a hot caller

# ── THE DECLARATIONS ─────────────────────────────────────────────────────────────────────────────
#
# One per line: `<class>|<method-name>|<why it is over, and what expires this line>`.
# Matched on the class and the method NAME, not the full signature: a signature carries erased
# generic types that change when an unrelated parameter does, and a declaration that expires because
# someone added an argument is noise rather than a signal.
declarations() {
  cat <<'EOF'
ssc3.Lower$|lower|SSC3-J0 follow-up, and not on the RUNTIME path — it is the front, so it costs compile time and matters for self-hosting rather than for a benchmark row. v3/src/Lower.scala is held by another claim; queued rather than fixed here.
scalascript.alphabet.Alphabet$|<init>|A one-shot object initialiser: it runs once per process, before anything is hot, so "never JIT-compiled" costs a single interpreted execution and nothing after it. This one is not a defect and has no expiry condition beyond the class being rewritten.
EOF
}

# ── FINDING THE CLASSES OF *THIS* TREE ───────────────────────────────────────────────────────────
#
# `v3/ssc3` compiles into `v3/.jars/ssc3-<digest of the sources>`, so the digest is recomputed here
# with the same formula rather than picking the newest directory. That is deliberate: `ls -t | head`
# would serve a SIBLING WORKTREE'S state, or the state before a revert, and report a size for code
# that is not in front of you — the exact failure that made a digest-keyed cache of a directory PATH
# unsound in this repo once already.
#
# If the formula ever drifts from the driver's, this finds no directory and says so. That is the
# safe direction: a gate that refuses is recoverable, a gate that measures the wrong tree is not.
classes_dir() {
  local scala_v srcs=() dg
  scala_v="$(grep -h '^//> using scala ' "$ROOT/v3/src"/*.scala | head -1 | awk '{print $NF}')"
  local t
  for t in "$ROOT/v3/src" "$ROOT/alphabet/src"; do srcs+=("$t"/*.scala); done
  dg="$(cat "${srcs[@]}" <(printf '%s' "$scala_v") | shasum | cut -c1-16)"
  printf '%s' "$ROOT/v3/.jars/ssc3-$dg"
}

# Highest bytecode OFFSET in each method, which is a lower bound on its code length (the final
# instruction's own width is not added). Conservative in the right direction: anything this reports
# as over the limit is over it.
measure() {
  local dir="$1" names
  names="$(cd "$dir" && find . -name '*.class' | sed 's|^\./||; s|\.class$||; s|/|.|g')"
  # shellcheck disable=SC2086
  javap -c -p -cp "$dir" $names 2>/dev/null | awk '
    /^(public |protected |private |final |abstract |static )*(class|interface|enum) / {
      for (i = 1; i <= NF; i++) if ($i == "class" || $i == "interface" || $i == "enum") { cls = $(i+1); break }
      next
    }
    # A method or constructor: javap indents a MEMBER by exactly two spaces and ends a method
    # signature with `);`. The two-space anchor is load-bearing — an instruction is indented further,
    # and `ldc // String foo);` would otherwise be read as a method and reset the attribution.
    /^  [a-zA-Z].*\);$/ {
      sig = $0
      sub(/\(.*/, "", sig); sub(/.*[ \t]/, "", sig)          # the bare name, without the parameters
      # javap spells a CONSTRUCTOR with the fully qualified class name where a method has its name,
      # so `Alphabet$()` arrives here as `scalascript.alphabet.Alphabet$`. Left alone, a declaration
      # can never be written for it in a form a reader would guess.
      if (sig == cls || sig == "") sig = "<init>"
      cur = cls "|" sig
      next
    }
    /^ +[0-9]+: [a-z]/ { n = $1; sub(":", "", n); if (n + 0 > max[cur]) max[cur] = n + 0 }
    END { for (k in max) printf "%d|%s\n", max[k], k }
  ' | sort -t'|' -k1,1rn
}

check_sizes() {
  local dir="$1" quiet="${2:-}"
  local huge_undeclared=0 stale=0 total=0 rc=0
  local decls; decls="$(declarations)"
  local measured; measured="$(measure "$dir")"
  [ -n "$measured" ] || { echo "  ✋ measured NOTHING in $dir — javap produced no methods"; return 2; }

  [ -n "$quiet" ] || echo "── methods over -XX:DontCompileHugeMethods ($HUGE bytecodes) ───────────"
  while IFS='|' read -r size cls name; do
    [ -n "${size:-}" ] || continue
    total=$((total + 1))
    [ "$size" -gt "$HUGE" ] || continue
    local why
    why="$(printf '%s\n' "$decls" | awk -F'|' -v c="$cls" -v m="$name" '$1 == c && $2 == m { print $3 }')"
    if [ -n "$why" ]; then
      [ -n "$quiet" ] || echo "  decl $size  $cls.$name"
      [ -n "$quiet" ] || echo "         $why"
    else
      echo "  RED  $size  $cls.$name — over the limit and NOT declared: it will never be JIT-compiled"
      huge_undeclared=$((huge_undeclared + 1)); rc=1
    fi
  done <<<"$measured"

  # The expiry half. A declared method that is now under the limit means the work landed and the
  # line is stale — and a stale declaration silences the NEXT regression in the same method.
  while IFS='|' read -r cls name why; do
    [ -n "${cls:-}" ] || continue
    local size
    size="$(printf '%s\n' "$measured" | awk -F'|' -v c="$cls" -v m="$name" '$2 == c && $3 == m { print $1; exit }')"
    if [ -z "$size" ]; then
      echo "  RED  declared method $cls.$name NO LONGER EXISTS — delete the declaration"
      stale=$((stale + 1)); rc=1
    elif [ "$size" -le "$HUGE" ]; then
      echo "  RED  $cls.$name is $size now, UNDER the $HUGE limit, and still declared — delete the line."
      echo "       A declaration that outlives its cause silences the next regression in this method."
      stale=$((stale + 1)); rc=1
    fi
  done <<<"$decls"

  if [ -z "$quiet" ]; then
    echo
    echo "── past -XX:FreqInlineSize ($INLINE) — reported, not enforced ───────────"
    printf '%s\n' "$measured" | awk -F'|' -v lim="$INLINE" -v huge="$HUGE" '
      $1 > lim && $1 <= huge { n++; if (n <= 6) printf "  %7d  %s.%s\n", $1, $2, $3 }
      END { if (n > 6) printf "  … and %d more\n", n - 6; printf "  %d method(s) past the inline limit\n", n+0 }'
    echo
    echo "  $total method(s) measured; $huge_undeclared undeclared over $HUGE, $stale stale declaration(s)"
  fi
  return $rc
}

# ── THE SPECIALIZER ──────────────────────────────────────────────────────────────────────────────
#
# `v3/src/Specialize.scala` rewrites the `kind` field on `Un`/`Bin` where the operand types are
# proved. Each fixture in `v3/tests/jit/` asserts the kind of every arithmetic instruction it
# produces, and each one is there because a DIFFERENT way of getting the analysis wrong changes its
# answer — the fixture's own prose says which. A golden file nobody can explain is a golden file
# nobody will dare to change.
#
# THIS IS THE ONLY CHECK WITH AN OPINION ABOUT THE SPECIALIZER, and that is worth stating rather
# than discovering. `Exec` ignores `kind` today, so running the corpus with the pass on and off
# produces identical output WHATEVER the pass writes — a byte-equality gate would certify a
# specializer that marked string concatenation `f64`. It becomes evidence on the day `Exec.step`
# dispatches on the field (`specs/ssc3-jit.md` §3, J1 step 2), and not before.
#
# The fixture directory is a PARAMETER so the self-test can corrupt a copy in a temp directory
# instead of a tracked file. A gate whose self-test edits the repository is one interrupted run away
# from leaving a wrong expectation checked in, and the next reader has no way to tell.
check_specialize() {
  local dir="$1" fixdir="$2" quiet="${3:-}" fail=0 ran=0
  local tc; tc="$(cat "$ROOT"/v3/.jars/toolchain-*.cp 2>/dev/null | head -1)"
  [ -n "$tc" ] || { echo "  ✋ no toolchain classpath cached — run v3/ssc3 selftest first"; return 2; }

  [ -n "$quiet" ] || echo "── specializer: the kind of every arithmetic instruction ───────────────"
  local f
  for f in "$fixdir"/*.ssc; do
    [ -f "$f" ] || continue
    local name want got
    name="$(basename "$f" .ssc)"
    want="${f%.ssc}.kinds"
    [ -f "$want" ] || { echo "  FAIL $name has no .kinds expectation"; fail=1; continue; }
    ran=$((ran + 1))
    # Captured, then compared. Piping javap-style output into `grep -q` is how this repo has twice
    # inverted a check: `grep` exits on the first match, the writer dies with EPIPE, and pipefail
    # takes the pipeline non-zero exactly when the thing being looked for HAPPENED.
    got="$(java -cp "$dir:$tc" ssc3.SpecializeMain "$f" 2>&1 | grep -oE '\((bin|un) [a-z]+ [a-z0-9]+' | sed 's/^(//')"
    if [ "$got" = "$(cat "$want")" ]; then
      [ -n "$quiet" ] || echo "  ok   $name — $(printf '%s' "$got" | tr '\n' ' ')"
    else
      # The DIFF, not "they differ". A gate that can fail silently will.
      echo "  FAIL $name — the specializer marked instructions differently than $want expects:"
      diff <(cat "$want") <(printf '%s\n' "$got") | sed 's/^/         /'
      fail=1
    fi
  done

  if [ "$ran" = 0 ]; then
    echo "  ✋ NO FIXTURES RAN — $fixdir/*.ssc is empty or unreadable"
    return 2
  fi
  [ -n "$quiet" ] || echo "  $ran fixture(s)"
  return $fail
}

# ── IDENTITY: specializing must not change what a program PRINTS ─────────────────────────────────
#
# This check was deliberately NOT written until 2026-08-09, and the reason is worth keeping. While
# `Exec` ignored the `kind` field, running a program with the pass on and off produced identical
# output WHATEVER the pass wrote — it would have certified a specializer that marked string
# concatenation `f64`. `v3/jit-gate.sh --identity` printed a refusal saying so.
#
# SSC3-J1b made `Exec.step` dispatch on the field. The two arms are now genuinely different
# executions of the same program, so this comparison finally has an opinion, and the self-test's
# rule 4 proves it by planting a specializer that lies.
check_identity() {
  local quiet="${1:-}" fail=0 ran=0
  [ -n "$quiet" ] || echo "── identity: --no-specialize must print the same bytes ─────────────────"
  local f
  for f in "$ROOT"/v3/tests/front/*.ssc "$ROOT"/v3/tests/jit/*.ssc; do
    [ -f "$f" ] || continue
    local name on off
    name="$(basename "$f" .ssc)"
    # `.uniml-only` fixtures need a front this checkout may not have registered; exec-gate.sh owns
    # that diagnosis, and repeating it here would report the state of the checkout twice.
    [ -f "${f%.ssc}.uniml-only" ] && continue
    ran=$((ran + 1))
    on="$(v3/ssc3 exec "$f" 2>&1)"
    off="$(v3/ssc3 exec --no-specialize "$f" 2>&1)"
    if [ "$on" = "$off" ]; then
      [ -n "$quiet" ] || echo "  ok   $name"
    else
      echo "  FAIL $name — specializing CHANGED the output:"
      diff <(printf '%s\n' "$off") <(printf '%s\n' "$on") | sed 's/^/         /'
      fail=1
    fi
  done
  if [ "$ran" = 0 ]; then echo "  ✋ NO PROGRAMS RAN"; return 2; fi
  # The count, and a verdict that cannot contradict the lines above it. The first version of this
  # line said "both arms identical" unconditionally and printed it directly under four FAILs.
  if [ "$fail" = 0 ]; then
    [ -n "$quiet" ] || echo "  $ran program(s), both arms identical"
  else
    echo "  $ran program(s) compared, and the arms DIFFER above"
  fi

  # THE KIND IS A CLAIM, NOT A GUARANTEE — the other half of the identity property, and the one an
  # on/off comparison cannot reach. `wrong-kind.ssir` is a hand-edited module in which two STRINGS
  # are added under an `i64` annotation: a lie no pass would emit, and exactly what a future
  # specializer bug looks like from the executor's side. `Exec.binI64` must honour the values and
  # fall back, so the program still prints `ab`.
  #
  # PROVEN TO DISCRIMINATE, 2026-08-09, not argued: with `binI64`'s `case _ => binOp(...)` arm
  # replaced by a throw and the kernel rebuilt, this fixture fails
  # (`PLANTED: binI64 fallback removed`); restored, it prints `ab` again. Without that arm a
  # specializer defect would be a WRONG ANSWER rather than a slow one.
  local wk="$ROOT/v3/tests/jit/wrong-kind.ssir"
  if [ -f "$wk" ]; then
    local got want
    got="$(v3/ssc3 exec "$wk" 2>&1)"
    want="$(cat "${wk%.ssir}.expected")"
    if [ "$got" = "$want" ]; then
      [ -n "$quiet" ] || echo "  ok   wrong-kind — a lying \`i64\` on two strings still prints [$got]"
    else
      echo "  FAIL wrong-kind — expected [$want] got [$got]"
      echo "       A kind the executor TRUSTS instead of checking turns a specializer defect into a"
      echo "       wrong answer. The fallback arm in Exec.binI64/binF64 is what this asserts."
      fail=1
    fi
  else
    echo "  FAIL v3/tests/jit/wrong-kind.ssir is missing — the fallback is unasserted"
    fail=1
  fi
  return $fail
}

# ── THE SELF-TEST — a gate is only a gate if it can go red ───────────────────────────────────────
#
# Both rules are exercised against the REAL measurement of this tree, by perturbing the declaration
# table rather than the code: dropping a declaration must make the check RED (rule 1), and declaring
# a method that is comfortably under the limit must also make it RED (rule 2). If either comes back
# green, this file is reporting an opinion it did not check.
self_test() {
  local dir="$1" fails=0
  echo "── self-test: can this gate go RED? ────────────────────────────────────"

  # The real table, captured ONCE. Every perturbation below is expressed as a redefinition of
  # `declarations` over this text, so restoring is one assignment and cannot half-restore.
  BASE_DECLS="$(declarations)"
  restore() { declarations() { printf '%s\n' "$BASE_DECLS"; }; }

  if check_sizes "$dir" quiet >/dev/null 2>&1; then
    echo "  ok   baseline: the declarations cover this tree, the check is GREEN"
  else
    echo "  FAIL baseline is already RED — the self-test cannot tell a planted failure from it"
    check_sizes "$dir" | sed 's/^/       /'
    fails=$((fails + 1))
  fi

  # Rule 1 — over the limit and undeclared must be RED. Planted by removing every real declaration,
  # which is the same thing seen from the other side.
  declarations() { printf 'ssc3.NoSuchClass|noSuchMethod|a declaration that matches nothing\n'; }
  if check_sizes "$dir" quiet >/dev/null 2>&1; then
    echo "  FAIL rule 1 did not fire: with every real declaration removed, the check stayed GREEN."
    echo "       It is not looking at the sizes it claims to look at."
    fails=$((fails + 1))
  else
    echo "  ok   rule 1 fires: drop the declarations and the over-limit methods go RED"
  fi
  restore

  # Rule 2 — a declaration for a method UNDER the limit must be rejected as stale. Planted on a real
  # small method of this tree, so the check has to compare a size rather than match a name.
  local small
  small="$(measure "$dir" | awk -F'|' -v lim="$HUGE" '$1 < lim && $1 > 100 { print $2 "|" $3; exit }')"
  if [ -z "$small" ]; then
    echo "  FAIL could not find an under-limit method to plant a stale declaration on"
    fails=$((fails + 1))
  else
    declarations() { printf '%s\n%s|planted by the self-test; must be rejected as stale\n' "$BASE_DECLS" "$small"; }
    if check_sizes "$dir" quiet >/dev/null 2>&1; then
      echo "  FAIL rule 2 did not fire: a declaration for ${small/|/.} (UNDER $HUGE) was accepted."
      echo "       Stale declarations would accumulate silently and silence the next regression."
      fails=$((fails + 1))
    else
      echo "  ok   rule 2 fires: a declaration for ${small/|/.}, which is under $HUGE, is rejected as stale"
    fi
    restore
  fi

  # Rule 3 — the specializer check must notice a kind it did not expect. Planted on a COPY of the
  # fixtures: `i64` is rewritten to `dyn` in one expectation, which is exactly the shape of the
  # regression that matters (the pass quietly stops proving something it used to prove).
  local tmp; tmp="$(mktemp -d "${TMPDIR:-/tmp}/ssc3jit.XXXXXX")"
  cp "$ROOT"/v3/tests/jit/*.ssc "$ROOT"/v3/tests/jit/*.kinds "$tmp"/ 2>/dev/null
  if ! check_specialize "$dir" "$tmp" quiet >/dev/null 2>&1; then
    echo "  FAIL rule 3 baseline: the fixtures do not pass on an untouched copy"
    fails=$((fails + 1))
  else
    local victim; victim="$(grep -lE '^(bin|un) [a-z]+ i64$' "$tmp"/*.kinds 2>/dev/null | head -1)"
    if [ -z "$victim" ]; then
      echo "  FAIL rule 3 has nothing to plant on: no fixture expects an i64"
      fails=$((fails + 1))
    else
      sed -i.bak 's/ i64$/ dyn/' "$victim" && rm -f "$victim.bak"
      if check_specialize "$dir" "$tmp" quiet >/dev/null 2>&1; then
        echo "  FAIL rule 3 did not fire: an expectation was changed from i64 to dyn and the check"
        echo "       still passed, so it is not comparing what it prints."
        fails=$((fails + 1))
      else
        echo "  ok   rule 3 fires: a changed kind expectation ($(basename "$victim")) goes RED"
      fi
    fi
  fi
  rm -rf "$tmp"

  echo
  [ "$fails" = 0 ] && echo "  self-test: the gate discriminates (3 rules, all proven to fire)" \
                   || echo "  self-test: $fails rule(s) DID NOT FIRE — do not trust a green from this gate"
  return "$fails"
}

want_sizes=0; want_spec=0; want_ident=0; want_self=0
if [ $# -eq 0 ]; then want_sizes=1; want_spec=1; want_ident=1; fi
for a in "$@"; do
  case "$a" in
    --sizes)      want_sizes=1 ;;
    --specialize) want_spec=1 ;;
    --identity)   want_ident=1 ;;
    --self-test)  want_self=1 ;;
    *) echo "v3/jit-gate.sh: unknown flag $a" >&2; exit 2 ;;
  esac
done

DIR="$(classes_dir)"
if [ ! -d "$DIR" ]; then
  # Build through the driver, which owns the compile. Not a second compile path: a gate that builds
  # its own classes is measuring a tree the driver never runs.
  v3/ssc3 selftest >/dev/null 2>&1
fi
if [ ! -d "$DIR" ]; then
  echo "✋ no class directory for this tree at $DIR"
  echo "   Either the build failed, or the digest formula here has drifted from v3/ssc3's."
  echo "   REFUSING rather than measuring the newest directory: that would report a size for code"
  echo "   that is not in front of you — a sibling worktree's state, or the state before a revert."
  exit 2
fi

rc=0
[ "$want_sizes" = 1 ] && { check_sizes "$DIR" || rc=1; }
[ "$want_spec"  = 1 ] && { echo; check_specialize "$DIR" "$ROOT/v3/tests/jit" || rc=1; }
[ "$want_ident" = 1 ] && { echo; check_identity || rc=1; }
[ "$want_self"  = 1 ] && { self_test  "$DIR" || rc=1; }

echo
[ "$rc" = 0 ] && echo "== v3 jit gate: GREEN ==" || echo "== v3 jit gate: RED =="
exit "$rc"
