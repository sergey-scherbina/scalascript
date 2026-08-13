#!/usr/bin/env bash
#
# v1-jit-size.sh — no NEW method in the v1 tree may exceed HotSpot's HugeMethodLimit.
#
# WHY THIS EXISTS, and why it is separate from v2-jit-size.sh.
#
# `-XX:+DontCompileHugeMethods` is ON by default, and a method whose bytecode exceeds
# `-XX:HugeMethodLimit` (8000) is NEVER JIT-compiled — not by C1, not by C2. It runs in the
# bytecode interpreter for the life of the process. No warning, no log line, no correctness
# signal. In v2 exactly this cost 2.4–10.8× until `Prims.__method__` (49 384 bytecodes) was
# split, which is why `tests/e2e/v2-jit-size.sh` exists.
#
# That gate scans `v2/{src,backend-jvm-bytecode,jvm-runtime}` ONLY. Nobody ever pointed it at v1 —
# the tree that is 4.3× larger (302 210 lines vs 70 844). This one does, over the SHIPPED artifacts.
#
# WHY A FROZEN DEBT LIST AND NOT A HARD FAIL. Pre-existing offenders cannot be fixed in the commit
# that adds the gate, and a gate that is red on arrival gets disabled within a day. So the known
# ones are frozen BY NAME with their measured size; the gate fails on a NEW one, and it also fails
# when a frozen method GROWS. It is the shape already used by the negtc release gate: freeze the
# hard invariant, derive the rest.
#
# It also fails when a frozen method DISAPPEARS from the census — but read that failure carefully,
# because it has two causes and only one of them means "fixed"; see the note on the check itself.
#
# ── 2026-08-12: THIS GATE HAD NEVER ONCE RUN, AND COULD NOT HAVE ────────────────────────────────
#
# Three independent defects, each enough on its own:
#
#   1. NOT WIRED. No workflow, no suite, no script invoked it. The only things naming it were a
#      BUGS entry, a source comment and the orphan probe. Its twin v2-jit-size.sh is in ci.yml.
#   2. SILENT ABORT. The observed-set pipeline ended in `grep -E '^[0-9]+ '`; grep exits 1 on zero
#      matches and `set -euo pipefail` turned that into rc=1 with EMPTY stderr. Run it by hand and
#      you got a failure with no message.
#   3. BLIND SCOPE. It scanned `v1/**/target/scala-*/classes`, which do not exist after the
#      `install.sh --dev` its own header tells you to run — that build restores `bin/lib` from the
#      toolchain cache and never invokes sbt. One unrelated Scala 2.12 directory from the sbt plugin
#      was enough to slip past the "no classes found" guard and into defect 2.
#
# What it cost, visible the moment the scan was pointed at the right artifacts: four frozen methods
# had grown (renderTerm by 3204 bytecodes), two new offenders had appeared, and the largest method
# in the tree had never been censused at all because plugin bytecode ships nested inside a .sscpkg.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

LIMIT=8000
CENSUS="$ROOT/scripts/bytecode-size-census"
[[ -x "$CENSUS" ]] || { echo "missing $CENSUS" >&2; exit 2; }

# Frozen debt: <bytecodes> <fully.qualified.Class::method>.
#
# ⚠ SIZES MUST BE MEASURED FROM A FRESHLY BUILT TREE. The first version of this list was taken from
# whatever `target/*/classes` happened to be on disk in the shared checkout — built 2026-07-23 while
# HEAD was 07-30. The very next run in a fresh worktree then reported
# `frozen method GREW: JsGen::genExpr 24984 -> 25100` with NO commit to JsGen.scala in between: the
# baseline, not the method, was wrong. Re-baselined 2026-07-30 from a tree built out of current main.
# Before touching a number here, run `bash install.sh --dev` (or `scripts/sbtc cli/installBin`) first.
#
# Sizes below are the 2026-07-30 fresh-build measurement;
# growth beyond the recorded number is a regression even while the method stays exempt.
# SHRINKING this list is the goal. Do not add to it without a measured reason in the commit.
# RE-BASELINED 2026-08-12 from the shipped artifacts of a fresh build, and the deltas are the cost
# of this gate never having run. It is referenced by no workflow and no suite — until today the only
# things naming it were a BUGS entry, a source comment and the orphan probe. In that time:
#
#   renderTerm   16346 -> 19550   (+3204)
#
# AND 19550 -> 19630 (+80) ON THE SAME DAY, from `fix(rust): the last four, and the BADRUST column
# reaches zero` (189b8b111, 10:10) -- thirty-two minutes after this gate first became capable of
# catching anything, and its author had no reason to expect it. The number is raised rather than the
# work reverted, and raised OUT LOUD: by this gate's own definition growth is a regression, so a
# silent bump would be accepting one. What the bump does not mean is that the method got worse in
# any way a user feels -- at 19630 it is 2.45x the 8000-bytecode JIT limit and has not been
# JIT-compiled for a long time. +80 is DRIFT, and the freeze exists to make drift visible.
# The debt itself now has a slug: tests/BUGS.md `renderTerm-is-two-and-a-half-times-the-jit-limit`.
#   genExpr      25100 -> 25328   (+228)
#   evalCore     15330 -> 15428   (+98)
#   dispatchString 9839 -> 10013  (+174)
#   StaticJsEmitter$Ctx::compile  11387   NEW
#   SolidEmitter$Ctx::compile     10670   NEW
#
# dispatchString DELETED 2026-08-12: split into dispatchString/B at 5781/3437.
#
# WITH THIS THE V1 INTERPRETER HAS NO METHOD OVER THE LIMIT AT ALL. The five that remain below are
# other subsystems — the actors plugin, the JS and Rust code generators, and two frontend emitters —
# each its own debt with its own owner, not the interpreter's hot path.
#
# Acceptance was a NAMED asymmetry, not a timing: on a string-heavy workload dispatchString was
# compiled 5 times with the limit on and 8 with it off. It is now 8/8, and at 5782 bytes it reaches
# tier 2 and tier 4 with the default limit in force. Measured on a STRING-heavy workload on purpose:
# it is not hot on a list-heavy one (0 either way), where the measurement would have said nothing.
#
# evalCore DELETED 2026-08-12: split into evalCore/B/C at 4455/5120/3616. Checked the source first,
# as the disappeared-check instructs — alive, simply no longer over the limit. PrintCompilation on
# the shipped build: `evalCore (4456 bytes)` reaches tier 3 AND tier 4 with the default limit in
# force, where at 15429 bytes it was never submitted at all.
#
# The interpreter now has exactly ONE method left over the limit: dispatchString, 10013.
#
# dispatchList DELETED 2026-08-12: split into dispatchList/B/C at 4123/6043/2999, all under the
# limit, so the exemption expired and the list SHRANK — which is the whole point of freezing it.
# Checked the source before deleting, as the disappeared-check now tells you to: the method is
# alive, it is simply no longer over the limit. Verified by PrintCompilation on the shipped build:
# `dispatchList (4124 bytes)` reaches tier 3 AND tier 4 with the default limit in force, where at
# 14697 bytes it was never submitted at all.
#
# renderTerm 19630 -> 20042 the NEXT DAY, from b68389c3b (rust extension methods lower to a function
# taking the receiver first). RAISED, NOT REVERTED, and raised out loud: by this gate's own
# definition growth IS a regression, so a constant bumped quietly is how a freeze stops meaning
# anything. The commit is named, the delta is +412, and the debt underneath has its own entry —
# renderTerm is 20042 bytecodes, 2.51x the limit, so it is never JIT-compiled and has not been for
# a long time. That is the hazard; the +412 is drift. Third growth caught in two days, which is what
# the gate is for: before it ran, renderTerm went 16346 -> 19550 with nobody noticing.
#
# renderTerm 20042 -> 20333, from 3ae3258ce (std/i18n and std/ui/i18n compile — five defects behind
# one refusal). RAISED, NOT REVERTED, on the same terms as the +412 above, and this time with the
# alternative MEASURED rather than assumed: the two new arms' BODIES were extracted into helpers
# first — `renderMapContains` and `renderNegate` — and that bought EIGHT bytecodes of the 291.
# The cost of an arm is the pattern-match dispatch, not the body, so extraction does not shrink this
# method; it only adds indirection. The extraction was reverted and the number raised instead.
#
# THAT IS THE USEFUL PART OF THIS ENTRY: "split the big method up" is the obvious response to a
# frozen-size failure and it does not work here. Shrinking renderTerm means having FEWER ARMS —
# routing whole families of syntax to a separate renderer — not moving their bodies elsewhere.
#
# I ALSO GOT THIS WRONG BEFORE CI CAUGHT IT: v1-jit-size was already red locally when I pushed, and
# I filed it as a known sibling-owned red without reading the message, which named my own method.
# A red you have decided to ignore has to be re-read on every run, or it stops being a signal.
#
# Measured on a build whose stamp has b68389c3b as an ancestor — checked, not assumed, because a
# number taken from a tree fast-forwarded but not rebuilt is how this same line went out 80 short
# yesterday.
#
# renderTerm 19550 -> 19630 SAME DAY, and the miss is instructive: `189b8b111` (the Rust BADRUST
# work) had already landed when I re-baselined, but I measured against a toolchain built BEFORE the
# rebase that brought it in. The gate ran green on stale bytecode and the number went out 80 short —
# which turned main red the moment the gate reached the push path. Rebuild AFTER the rebase, not
# before; the repo has that lesson written down and it still cost a red.
#
# renderTerm 20333 -> 20345, and the +12 is the most useful number in this file.
#
# IT IS NOT AN ARM. The change that grew it adds ONE FIELD to the `Ctx` record. Proved by control
# rather than inferred, after three wrong guesses in a row: adding a SECOND field that is never read
# anywhere took it to 20357. Twelve bytecodes per field, read or unread, because `renderTerm`
# contains five `ctx.copy(...)` call sites and each one materialises every field.
#
# THAT IS WHY LOCAL RESTRUCTURING KEEPS BUYING NOTHING, and it was measured four ways today:
#
#   extract two arms' BODIES into helpers .......... 8 bytecodes of 291
#   fold a new arm into an existing dispatch ....... 8 bytecodes of 96
#   both together .................................. 36 of 96
#   lift a nine-set guard out of the method ........ 0
#
# The arms are not where the bytecode is. THE CONTEXT RECORD'S WIDTH IS, multiplied by the number of
# `copy` sites — so the lever that would actually shrink this method is fewer fields on `Ctx`, or
# the copies factored into one helper, and NOT the "split it along its term cases" that the debt
# entry has been recommending. tests/BUGS.md `renderTerm-is-two-and-a-half-times-the-jit-limit` is
# corrected to say so.
#
# The growth itself is a real fix — a local bound to a def is now callable — and the alternative was
# to leave a lowering that every other backend performs. Raised, announced, and with the mechanism
# named so the next person spends their build cycles on the lever that works.
#
# ─────────────────────────────────────────────────────────────────────────────────────────────
# WHAT THE JIT LIMIT COSTS renderTerm: NOTHING MEASURABLE. Measured 2026-08-13, because three
# raises in one day had turned "it is 2.5x the JIT limit" into a phrase nobody had checked.
#
# The instrument was validated BEFORE the result was believed — `-XX:-DontCompileHugeMethods`
# has to actually change the thing under test, and a first attempt did not:
#
#   one module, one JVM:   renderTerm submitted 0 times WITH the flag and 0 without   <- vacuous
#   51 modules, one JVM:   0 without the flag; 1 WITH it, tier 3, (20334 bytes)       <- valid
#
# Then the A/B, alternating, on that same 51-module workload — the most favourable one there is,
# since it is the only way the method gets hot at all:
#
#   capped   (renderTerm NEVER JIT-compiled)   2.25 s
#   uncapped (renderTerm JIT-compiled)         2.29 s     delta +0.040 s, INSIDE the spread
#
# Allowing the JIT to compile it changes nothing, and even when allowed it only ever reaches
# tier 3, never tier 4. The work is in the ARMS — 402 RustCodeWalk methods do get compiled, as
# separate lambda and anon-class methods — not in the dispatch. And that measurement is generous:
# in production this compiler runs ONE SHORT-LIVED JVM PER MODULE, where renderTerm is never
# submitted even with the flag, because it never becomes hot.
#
# SO STOP CITING THE JIT FOR THIS ENTRY. The freeze on renderTerm is worth keeping, but for the
# reason that is real: 198 arms in one 2000-line match, where ORDER IS SEMANTICS. A `Map.contains`
# arm must precede the str-receiver arm; an `extension` call arm must come first among the Select
# arms; a signal read must be decided before the generic call arms. Each of those was a defect
# before it was a rule. That is a maintainability ratchet, not a performance one, and it is the
# only argument that survives measurement.
#
# THIS DOES NOT GENERALISE TO `handleActorOp`, and the distinction is the point. That one is the
# ACTOR SCHEDULER — it runs inside the user's long-lived program, millions of times, where a
# never-JIT-compiled dispatch loop is exactly the hazard this limit describes; splitting such a
# method elsewhere in this repo bought 2.4-10.8x. The four codegen/emitter entries run at COMPILE
# time in a process that exits in seconds. Same list, same number, opposite meaning. Anyone
# reusing this measurement must re-take it for the method they are actually looking at.
# ─────────────────────────────────────────────────────────────────────────────────────────────
#
# The two NEW entries are frontend emitters, not the INT hot path; they are frozen with that as the
# measured reason rather than fixed here. `handleActorOp` is UNCHANGED at 28036 — see the nested-jar
# note below for why it briefly looked as though it had gone away.
read -r -d '' FROZEN <<'EOF' || true
28036 scalascript.interpreter.ActorScheduler::handleActorOp
25328 scalascript.codegen.JsGen::genExpr
20345 scalascript.codegen.rust.RustCodeWalk$::renderTerm
11387 scalascript.frontend.custom.StaticJsEmitter$Ctx::compile
10670 scalascript.frontend.solid.SolidEmitter$Ctx::compile
EOF

# ── self-test: a detector only ever observed staying quiet is not a detector ─────────────────
# Same reasoning as v2-jit-size.sh: prove the census still measures before trusting a clean report.
if [[ "${1:-}" == "--self-test" ]]; then
  command -v javac >/dev/null || { echo "self-test needs javac" >&2; exit 2; }
  TMP="$(mktemp -d "${TMPDIR:-/tmp}/ssc-v1-jit-selftest.XXXXXX")"
  trap 'rm -rf "$TMP"' EXIT
  gen() { { printf 'public class %s { public static int f(int x) {\n' "$1"
            for ((i = 0; i < $2; i++)); do printf '    x += 1;\n'; done
            printf '    return x; } }\n'; } > "$TMP/$1.java"
          javac -d "$TMP/classes-$1" "$TMP/$1.java"; }
  gen Huge 5000; gen Small 10
  [[ -n "$("$CENSUS" "$TMP/classes-Huge" "$LIMIT")" ]] \
    || { echo "SELF-TEST FAIL: census stayed quiet on a method built to exceed $LIMIT" >&2; exit 1; }
  [[ -z "$("$CENSUS" "$TMP/classes-Small" "$LIMIT")" ]] \
    || { echo "SELF-TEST FAIL: census flagged a 10-statement method" >&2; exit 1; }

  # An EMPTY census must flow on to the frozen-list checks, not abort the script.
  #
  # This is the defect that made this gate useless: the observed-set pipeline ended in
  # `grep -E '^[0-9]+ '`, `grep` exits 1 when nothing matches, and `set -euo pipefail` turned that
  # into rc=1 with EMPTY stderr — a failure with no message, indistinguishable from a real one.
  # It fired exactly when the census found nothing, which is the state a mis-scoped scan produces.
  # Asserted on the pipeline's own EXIT STATUS, not by wrapping it in a subshell and hoping `set -e`
  # fires. It does not: bash suppresses `-e` inside a compound command whose status is tested, so
  # `( set -e; … ) || fail` passes whatever happens. The first version of this assertion was written
  # that way, and re-running it against a copy with the `|| true` deliberately REMOVED still
  # reported PASS — it was a check that could not fail. Take the status directly instead.
  set +e
  ( set -o pipefail; printf 'no numbers here\n' | { grep -E '^[0-9]+ ' || true; } | sort -u >/dev/null )
  empty_rc=$?
  set -e
  [[ $empty_rc -eq 0 ]] \
    || { echo "SELF-TEST FAIL: the empty-census pipeline exits $empty_rc instead of 0 —" >&2
         echo "  under 'set -euo pipefail' that aborts this gate with NO message at all." >&2
         echo "  The grep needs its '|| true'. (Verified to fail when that is removed.)" >&2; exit 1; }

  # The SIZE PREFILTER must not be able to hide an over-limit method. The property it rests on is
  # that a method's Code attribute lives inside the class file, so `bytecodes <= file size` — assert
  # it on the generated over-limit class rather than trusting the reasoning, because the filter is
  # the one place where making the gate fast could quietly make it blind.
  huge_class="$(find "$TMP/classes-Huge" -name 'Huge.class' | head -1)"
  huge_bytes="$(wc -c < "$huge_class" | tr -d ' ')"
  [[ "$huge_bytes" -ge "$LIMIT" ]] \
    || { echo "SELF-TEST FAIL: a class holding a >$LIMIT-bytecode method is only $huge_bytes bytes," >&2
         echo "  so the 'file smaller than the limit cannot hold an over-limit method' prefilter" >&2
         echo "  would DISCARD it and this gate would go green while blind." >&2; exit 1; }

  echo "v1-jit-size self-test: PASS (census detects over-limit, stays quiet under it,"
  echo "                            an empty census does not abort the run,"
  echo "                            and a class holding an over-limit method survives the size filter)"
  # FALL THROUGH to the census, matching v2-jit-size.sh, whose usage line says
  # "assert BOTH verdicts, then check the artifacts". One CI invocation must do both: wiring only
  # `--self-test` would run the detector's self-check and never look at the tree — the exact shape
  # of uselessness this gate was already in.
fi

# ── WHAT IS SCANNED: the SHIPPED JARS, not `v1/**/target/*/classes` ──────────────────────────────
#
# This gate scanned `target/*/classes` and, measured 2026-08-12, that made it BLIND in exactly the
# state its own header tells you to be in. `install.sh --dev` restores `bin/lib` from the toolchain
# cache when the inputs digest matches, and then sbt never runs — a fresh worktree has NO
# `v1/**/target/scala-3*/classes` at all. What it does have is one unrelated Scala 2.12 directory
# from the sbt plugin, which was enough to get past the "no classes found" guard below.
#
# The jars are also the RIGHT artifact on the merits: `bin/lib/jars/*.jar` is what `bin/ssc-tools`
# puts on its classpath, so it is the bytecode that actually runs. `target/classes` is an
# intermediate that may be stale, absent, or from another build. Verified identical where both
# exist: EvalRuntime 15428, dispatchList 14696, dispatchString 10013 from the jar and from a fresh
# `backendInterpreter/compile`.
#
# THIRD-PARTY JARS ARE EXCLUDED BY NAME. `bin/lib/jars` also holds scalameta, postgresql, h2 and
# ujson, each with its own over-limit methods that are none of our business and that we cannot fix.
# Only `scalascript-*.jar` is ours. The v2 tree has its own gate (v2-jit-size.sh), so `-v2-` jars
# are left to it rather than double-reported here.
jars=()
while IFS= read -r j; do jars+=("$j"); done < <(
  find "$ROOT/bin/lib/jars" -name 'scalascript-*.jar' 2>/dev/null | grep -v -- '-v2-' | sort)
if [[ ${#jars[@]} -eq 0 ]]; then
  echo "v1-jit-size: no shipped scalascript jars found — build first (bash install.sh --dev)" >&2
  echo "  looked for: bin/lib/jars/scalascript-*.jar" >&2
  exit 2
fi
echo "v1-jit-size: scanning ${#jars[@]} shipped jar(s), limit $LIMIT"

# ── PLUGIN BYTECODE SHIPS NESTED, AND NO CENSUS HAD EVER LOOKED AT IT ────────────────────────────
#
# A v1 compiler plugin ships as `bin/lib/compiler/plugins/<name>.sscpkg` — a zip whose payload is
# `intrinsics/<name>.jar`, a jar INSIDE a zip. All 27 plugins are built that way and none of them
# had ever been censused by anything.
#
# This is not a completeness nicety, it is what keeps the disappeared-check honest. `handleActorOp`
# (28036 bytecodes, the largest offender on the list) lives in `actors-plugin.sscpkg`. Scanning only
# the flat jars made the gate report it as "no longer over the limit — DELETE it from FROZEN", which
# is FALSE: the method is alive, unchanged, and still shipping. Following that instruction would
# have dropped the biggest offender in the tree out of the census permanently, and the gate would
# have gone green doing it.
#
# A frozen entry that a scan cannot see is indistinguishable from one that was fixed. The
# disappeared-check is only safe when coverage is complete, so coverage comes first.
pkgtmp="$(mktemp -d "${TMPDIR:-/tmp}/v1jit-pkg.XXXXXX")"
classes="$(mktemp -d "${TMPDIR:-/tmp}/v1jit-cls.XXXXXX")"
observed="$(mktemp)"
# ${TMP:-} too: --self-test now falls through to here, and a second `trap ... EXIT` REPLACES the
# first, so the self-test's own scratch dir would leak on every CI run.
trap 'rm -rf "$pkgtmp" "$classes" "$observed" ${TMP:+"$TMP"}' EXIT

# Everything is unpacked into ONE tree and censused ONCE. Per-jar invocation cost 68 s of an 85 s
# run — 56 unzip+javap pipelines instead of one. Same 8 methods, same sizes, 53 s.
for j in "${jars[@]}"; do unzip -q -o "$j" '*.class' -d "$classes" 2>/dev/null || true; done
npkg=0
while IFS= read -r p; do
  d="$pkgtmp/$(basename "${p%.sscpkg}")"
  unzip -q -o "$p" 'intrinsics/*.jar' -d "$d" 2>/dev/null || continue
  while IFS= read -r nj; do
    unzip -q -o "$nj" '*.class' -d "$classes" 2>/dev/null || true
    npkg=$((npkg + 1))
  done < <(find "$d" -name '*.jar' 2>/dev/null)
done < <(find "$ROOT/bin/lib/compiler/plugins" -name '*.sscpkg' 2>/dev/null | sort)
echo "v1-jit-size: plus $npkg nested plugin jar(s) from .sscpkg payloads"

# ── SIZE PREFILTER, and it is exact rather than a heuristic ──────────────────────────────────────
#
# A method's Code attribute is stored INSIDE the class file, so its length can never exceed the
# file's own size: a `.class` smaller than $LIMIT bytes cannot hold a method of $LIMIT bytecodes.
# Filtering on that is therefore lossless, not a sampling trade — and it takes the census from 5650
# class files to 563, and from 50 s to 12 s, with byte-identical output on all 8 known methods.
#
# Derived from $LIMIT rather than written as a number, so raising the limit cannot silently make the
# filter too aggressive. The self-test asserts the property directly on a generated over-limit class.
big="$(mktemp -d "${TMPDIR:-/tmp}/v1jit-big.XXXXXX")"
trap 'rm -rf "$pkgtmp" "$classes" "$big" "$observed" ${TMP:+"$TMP"}' EXIT
while IFS= read -r -d '' f; do
  rel="${f#$classes/}"; mkdir -p "$big/$(dirname "$rel")"; cp "$f" "$big/$rel"
done < <(find "$classes" -name '*.class' -size +$((LIMIT - 1))c -print0 2>/dev/null)
echo "v1-jit-size: $(find "$big" -name '*.class' | wc -l | tr -d ' ') of $(find "$classes" -name '*.class' | wc -l | tr -d ' ') class files are large enough to hold an over-limit method"

# `|| true` on the grep, and it is load-bearing: `grep` exits 1 on ZERO matches, and under
# `set -euo pipefail` that killed this script with rc=1 and an EMPTY stderr — a silent failure
# indistinguishable from a real one, and impossible to diagnose. It fired whenever the census came
# back empty, which is precisely the blind state described above. An empty census must reach the
# "frozen method disappeared" check below and be reported there, not abort the run.
"$CENSUS" "$big" "$LIMIT" 2>/dev/null \
  | sed -E 's/^ *([0-9]+) +([A-Za-z0-9_.$]+) :: .*[ (]([A-Za-z0-9_$]+)\(.*/\1 \2::\3/' \
  | { grep -E '^[0-9]+ ' || true; } | sort -u > "$observed"

fail=0
declare -A frozen_size=()
while read -r size name; do [[ -n "${name:-}" ]] && frozen_size["$name"]="$size"; done <<< "$FROZEN"

# NEW offenders, and frozen ones that GREW
while read -r size name; do
  [[ -n "${name:-}" ]] || continue
  if [[ -z "${frozen_size[$name]+x}" ]]; then
    echo "FAIL  NEW method over HugeMethodLimit — it will NEVER be JIT-compiled:" >&2
    echo "        $size  $name" >&2
    echo "        Split it, or add it to FROZEN with a measured reason in the commit." >&2
    fail=1
  elif (( size > ${frozen_size[$name]} )); then
    echo "FAIL  frozen method GREW: $name  ${frozen_size[$name]} -> $size" >&2
    fail=1
  fi
done < "$observed"

# Frozen entries that no longer appear — the exemption expired, shrink the list
while read -r size name; do
  [[ -n "${name:-}" ]] || continue
  grep -qE " ${name//$/\\$}$" "$observed" \
    || { echo "FAIL  frozen method is not in the census: $name" >&2
         echo "        Either it was fixed — then DELETE it from FROZEN, an exemption that outlives" >&2
         echo "        its need is the same rot as a stale known-red — OR THE SCAN NO LONGER REACHES" >&2
         echo "        IT, in which case deleting it drops a live offender from the census forever." >&2
         echo "        CHECK THE SOURCE BEFORE DELETING: grep for the method name in v1/." >&2
         echo "        Measured 2026-08-12: handleActorOp reported exactly this while alive and" >&2
         echo "        unchanged at 28036 — its class ships in a jar NESTED inside a .sscpkg, which" >&2
         echo "        the scan did not open. That is a coverage hole, not a fix." >&2
         fail=1; }
done <<< "$FROZEN"

if [[ "$fail" -ne 0 ]]; then
  echo "" >&2
  echo "v1-jit-size: FAIL" >&2
  exit 1
fi
echo "v1-jit-size: PASS ($(wc -l < "$observed" | tr -d ' ') known over-limit method(s), none new, none grown)"
