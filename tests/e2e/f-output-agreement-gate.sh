#!/usr/bin/env bash
#
# f-output-agreement-gate — front F must not answer DIFFERENTLY from the reference front on a file
# it accepts. This is the correctness counterpart to the coverage census, and the reason it exists
# is that the coverage census cannot see the failures that matter most.
#
# WHAT IT CAUGHT WHEN IT WAS RUN BY HAND, over four days:
#   * a multi-statement match-arm body returned its FIRST statement and dropped the rest — F answered
#     2 where both the reference front and the v1 interpreter answer 3, with no diagnostic;
#   * a curried extern had its call site flattened, so `httpClient(url) { … }` ran the call and threw
#     the block away silently;
#   * an extern vararg call started building a Cons list where the oracle passes the arguments
#     individually — a divergence introduced BY A FIX, three hours old, that no test noticed.
#
# NONE of those moved the coverage number. `ssc info --front-report` answers "did F lower the file",
# and all three were lowered — to code that gave the wrong answer. A suite that only counts coverage
# is green through every one of them, which is why this is a gate and not a note.
#
# METHOD. For each corpus file: run it once with SSC_FRONT_STRICT=1 (F, refusing to fall back) and
# once with SSC_FRONT=legacy (the reference), and compare stdout+stderr.
#
#   F declines            -> NOT this gate's business (that is coverage) — counted, not judged
#   both fail identically -> environment or a shared gap, not a divergence
#   both produce the same -> agreement
#   anything else         -> DISAGREEMENT, and if the reference RAN and F did not, F is WORSE
#
# THREE NUMBERS ARE FROZEN, not one. A floor on the good number is not a guard: agreement can climb
# while divergence climbs with it, and both can look fine while the SUBJECT SET quietly shrinks to
# nothing. So the gate pins the worse-count as a ceiling, agreement as a floor, AND the size of the
# set it actually measured. Drop the corpus to one file and the third number fails.
#
# IT MUST FAIL LOUDLY IF IT MEASURES NOTHING. A gate that reports 0 == 0 as green is worse than no
# gate; `specs/…` calls this out and it has bitten this repository before.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
. "$SCRIPT_DIR/lib/ssc-usable.sh"

# ── the frozen expectations ──────────────────────────────────────────────────────────────────────
# Measured 2026-08-12 over the 639-file subject set at -P 8:
#   declined 239 · timed out 133 · measured 267 · agree 222 · both fail identically 32 · disagree 8
#   arbitrated: F contradicted by both other lanes 0 · reference contradicted 3 · all three differ 2
#
# FWRONG_MAX IS THE NUMBER THAT MATTERS, and it is 0. It used to be called "F worse than the
# reference" and counted every divergence against F — which was wrong three times out of five,
# because the reference front is not an oracle. A divergence is now put to the v1 interpreter and
# charged to whichever front the other two contradict, so this counts files where F is contradicted
# BY BOTH OTHER LANES. Nothing else belongs in a ceiling.
#
# The ceiling is TIGHT and the two floors are slack, on purpose. Under load a subject moves into the
# TIMEOUT bucket, which can only make `agree` and `subjects` smaller and can only REMOVE rows from
# the ceiling — never add. 133 of 639 timed out on the host this was measured on, so the floors are
# set well below the measurement rather than just under it: a busy runner must not turn this red for
# being busy, and a shrinking subject count is reported by SUBJECT_MIN rather than hidden.
FWRONG_MAX=${FWRONG_MAX:-0}
AGREE_MIN=${AGREE_MIN:-150}
SUBJECT_MIN=${SUBJECT_MIN:-180}

CAP=${CAP:-45}

ssc_usable_or_skip f-output-agreement-gate "$ssc"

# The per-file classifier, in a FUNCTION so `--self-test` can drive it directly. It used to be an
# inline heredoc, which meant the only way to exercise a classification rule was to run all 640
# subjects and hope one of them had the shape you were testing.
write_worker() {
  cat > "$1" <<'WORKER'
#!/usr/bin/env bash
f=$1
o="$WORK/$(echo "$f" | tr '/' '_')"
SSC_FRONT_STRICT=1 timeout "$CAP" "$SSC_BIN" run "$f" > "$o.f" 2>&1; frc=$?
[ $frc -eq 124 ] && { printf 'TIMEOUT\t%s\n' "$f"; rm -f "$o.f"; exit 0; }
fout=$(head -8 "$o.f"); rm -f "$o.f"
case "$fout" in *"refusing to fall back"*) printf 'DECLINED\t%s\n' "$f"; exit 0 ;; esac
# The reference run, memoized. A TIMEOUT is never cached — it is a property of the host, not of the
# program, and caching one would freeze a busy afternoon into every later run.
refrun() { SSC_FRONT=legacy timeout "$CAP" "$SSC_BIN" run "$1" > "$2" 2>&1; }
rkey=""; rhit=0
if [ -n "$REFCACHE" ]; then rkey="$REFCACHE/$(printf '%s' "$f" | shasum -a 256 | cut -d' ' -f1)"; fi
if [ -n "$rkey" ] && [ -f "$rkey" ]; then
  rout=$(cat "$rkey"); rhit=1
  # Sampled re-verification: recompute anyway on one hit in REF_VERIFY_EVERY and demand a match.
  if [ $(( 0x$(printf '%s' "$f" | shasum -a 256 | cut -c1-4) % REF_VERIFY_EVERY )) -eq 0 ]; then
    refrun "$f" "$o.r"; rrc=$?
    if [ $rrc -ne 124 ]; then
      fresh=$(head -8 "$o.r")
      if [ "$fresh" != "$rout" ]; then
        # Before blaming the cache, ask whether the SUBJECT is stable: a program that answers
        # differently on two fresh runs makes any stored answer "wrong" without the key being wrong.
        refrun "$f" "$o.r3"; r3rc=$?
        fresh2=$(head -8 "$o.r3"); rm -f "$o.r3"
        if [ $r3rc -ne 124 ] && [ "$fresh2" != "$fresh" ]; then
          rm -f "$rkey" "$o.r"; printf 'FLAKY\t%s\n' "$f"; exit 0
        fi
        rm -f "$o.r"; printf 'CACHE-MISMATCH\t%s\n' "$f"; exit 0
      fi
    fi
    rm -f "$o.r"
  fi
  printf 'REFHIT\n' >> "$WORK/refstats.txt"
else
  refrun "$f" "$o.r"; rrc=$?
  [ $rrc -eq 124 ] && { printf 'TIMEOUT\t%s\n' "$f"; rm -f "$o.r"; exit 0; }
  rout=$(head -8 "$o.r"); rm -f "$o.r"
  [ -n "$rkey" ] && printf '%s' "$rout" > "$rkey"
  printf 'REFMISS\n' >> "$WORK/refstats.txt"
fi
if [ "$fout" = "$rout" ]; then
  case "$rout" in
    *"ssc: "*) printf 'BOTHFAIL\t%s\n' "$f" ;;
    *)         printf 'AGREE\t%s\n' "$f" ;;
  esac
  exit 0
fi
# A DIVERGENCE MUST REPRODUCE BEFORE IT IS JUDGED.
#
# `examples/v2-http-sql-demo.ssc` makes a REAL HTTP REQUEST, and it landed in `all three lanes
# differ` twice — with F seeing `HTTP GET failed: request timed out` and the reference seeing
# `HTTP status: 200`. Nothing about that is a property of either front; it is a property of the
# network that afternoon. Excluding the file by name was the obvious fix and the wrong one: the
# subject set here is a RULE precisely so that nobody has to maintain a list, and the next
# nondeterministic subject — a clock, a port, a random seed — would arrive unannounced.
#
# So both fronts are run once more. If the second pair AGREES, or if either front's own answer
# changed between the two runs, the subject is nondeterministic and goes to a bucket that is
# reported and never judged — the same treatment a timeout already gets, for the same reason.
# Costs two runs per DIVERGENT row only, and divergences are the small bucket.
#
# THE STAKES ARE HIGHER THAN "NOISE IN THE REPORT". Measured by disabling this rule and re-running
# the self-test: the nondeterministic subject came back classified **F-WRONG** — the one bucket this
# gate has a ceiling on. A network hiccup could therefore fail the gate and name F as the cause, and
# the next reader would go looking for a regression that never existed.
SSC_FRONT_STRICT=1 timeout "$CAP" "$SSC_BIN" run "$f" > "$o.f2" 2>&1; f2rc=$?
SSC_FRONT=legacy   timeout "$CAP" "$SSC_BIN" run "$f" > "$o.r2" 2>&1; r2rc=$?
fout2=$(head -8 "$o.f2"); rout2=$(head -8 "$o.r2"); rm -f "$o.f2" "$o.r2"
if [ $f2rc -eq 124 ] || [ $r2rc -eq 124 ]; then printf 'TIMEOUT\t%s\n' "$f"; exit 0; fi
if [ "$fout2" = "$rout2" ] || [ "$fout2" != "$fout" ] || [ "$rout2" != "$rout" ]; then
  # A flaky subject must not stay in the reference cache either: a stored answer for a program that
  # answers differently each time would be reported as a CACHE MISMATCH by the sampling below, which
  # is a hard failure — a false one, blamed on the cache instead of on the program.
  [ -n "$rkey" ] && rm -f "$rkey"
  printf 'FLAKY\t%s\n' "$f"; exit 0
fi

# Both failed, with different messages: a divergence worth seeing, but neither front is usable here.
case "$rout" in
  *"ssc: "*) printf 'DISAGREE\t%s\n' "$f"; exit 0 ;;
esac
# THE REFERENCE FRONT IS NOT AN ORACLE, and treating it as one is what this gate got wrong until
# 2026-08-12. Of the five divergences the conformance sweep found, THREE were reference-front
# defects with F in the right, and the gate counted every one of them against F. So a divergence is
# put to a THIRD lane, the v1 interpreter, and charged to whichever front the other two contradict.
# It costs one extra run per DIVERGENT row only, and divergences are the small bucket.
iout=$(timeout "$CAP" "$SSC_BIN"-tools run --v1 "$f" 2>&1 | head -8)
if [ "$fout" = "$iout" ]; then printf 'REF-WRONG\t%s\n' "$f"; exit 0; fi
if [ "$rout" = "$iout" ]; then printf 'F-WRONG\t%s\n' "$f"; exit 0; fi
printf 'UNRESOLVED\t%s\n' "$f"
WORKER
  chmod +x "$1"
}

# THE REFERENCE CACHE KEY, in one place because --self-test has to compute the same thing.
#
# It covers exactly two populations, and it took two wrong versions to get there:
#
#   1. every tracked `.ssc`/`.ssc0` a SUBJECT could import. Not just the subject — this gate has no
#      import graph, so the whole tree stands in for the closure. `scripts/` and `v3/` are dropped:
#      measured, no corpus subject imports from either (their imports go to `std/…` and to siblings),
#      and `scripts/smoke-ci.ssc` is the file an F session touches on nearly every change — leaving
#      it in meant registering a gate invalidated the whole cache. Measured: 93 of 347 reused.
#
#   2. the staged REFERENCE FRONT (`.ssc0` under `bin/lib`, minus F's two files) and the tracked
#      `*.scala` SOURCES of the runtime — not the jars. Three versions of this line were wrong and
#      each was caught by measurement rather than review:
#
#        * `bin/lib/.build-digest` — `scripts/launcher-input-digest` DELIBERATELY includes
#          `specs/*.ssc` (see its comment near line 112) so an F edit marks the toolchain stale.
#          Correct for ITS purpose, exactly wrong here: it invalidated the cache on the one change
#          the exclusion exists for, the moment the tree was rebuilt.
#        * `ssc.jar` — a jar's bytes carry the ZIP ENTRY MTIMES, so any rebuild that repackages moves
#          them regardless of content. `bin/lib/ssc.jar` measured "byte-stable across rebuilds" only
#          because an unchanged build never regenerates it; with F edited, `bin/lib/standard/ssc.jar`
#          moved while containing no F at all. Hashing behaviour means hashing SOURCES.
#        * unit rows alone — nine of them passed while the end-to-end (edit F, REBUILD, compare)
#          failed, because they each touched one enumerated file and a real rebuild touches whatever
#          it likes. The end-to-end check is the one that decides.
#
#      `*.scala` costs nothing measurable: 4,000 files hash in 0.27 s, once per run.
#
# F's own two files are dropped from BOTH populations — the source and its staged copy — and nothing
# else is. `--self-test` proves each of those claims separately rather than trusting this comment.
ref_world_digest() {
  {
    git -C "$ROOT" ls-files -z '*.ssc' '*.ssc0' '*.scala' \
      | tr '\0' '\n' \
      | grep -vFx 'specs/v2.2-p6.5-fsub.ssc' \
      | grep -vE '^(scripts|v3)/' \
      | tr '\n' '\0' \
      | (cd "$ROOT" && xargs -0 shasum -a 256)
    find "$ROOT/bin/lib" \
         \( -name '*.ssc0' -o -name '*.ssc' \) -type f 2>/dev/null \
      | grep -vE '/(fsub\.ssc|ssc1-run-fsub\.ssc0)$' \
      | LC_ALL=C sort \
      | xargs shasum -a 256 2>/dev/null
  } | shasum -a 256 | cut -d' ' -f1
}

# ── --self-test: prove the reference cache's KEY, because the key is the only thing that can lie ──
#
# The main run cannot check these — they need the tree mutated — so they live here and must be run
# after touching the memoization block. Three claims, in the order they can go wrong:
#   1. a changed non-excluded file invalidates      (the key sees the world)
#   2. a changed F source does NOT invalidate       (the exclusion is worth having)
#   3. and, crucially, the reference's ANSWER is unchanged by that same F edit
#      (the exclusion is SOUND — this is the claim, 2 is only the optimisation it buys)
if [ "${1:-}" = "--self-test" ]; then
  st_fail=0
  digest() { ref_world_digest; }
  # A probe that RUNS, not one that happens to fail: if both sides error for an unrelated reason the
  # comparison is vacuous and this row would pass while proving nothing. Written fresh so it cannot
  # drift with the corpus.
  probe=$(mktemp "${TMPDIR:-/tmp}/agree-selftest.XXXXXX").ssc
  printf 'def main(): Unit = println(List(1, 2, 3).map(x => x * 7))\n' > "$probe"
  base=$(digest)
  echo "── self-test: the reference cache key"

  # A REAL tracked corpus file, since the claim is about what `git ls-files` sweeps — the temp probe
  # above is untracked and would prove nothing here.
  # `>>` CREATES the file when the path is wrong, and an earlier draft of this block computed the
  # path from the temp probe and so appended a newline into `tests/conformance/agree-selftest.*.ssc`
  # — a file that then became a CORPUS SUBJECT for every later run of this gate (641 subjects where
  # there are 640). Hence the existence check, and hence the restore-on-kill trap: a self-test that
  # mutates the tree must not be able to leave anything behind, INCLUDING when it is killed.
  victim="$ROOT/$(cd "$ROOT" && git ls-files 'tests/conformance/*.ssc' | head -1)"
  if [ ! -f "$victim" ]; then
    echo "  ✗ cannot resolve a tracked corpus file to mutate — refusing to create one"
    exit 1
  fi
  fsub="$ROOT/specs/v2.2-p6.5-fsub.ssc"
  bak=$(mktemp); cp "$victim" "$bak"
  fbak=$(mktemp); cp "$fsub" "$fbak"
  st_restore() {
    [ -f "$bak" ] && cp "$bak" "$victim"
    [ -f "$fbak" ] && cp "$fbak" "$fsub"
    rm -f "$bak" "$fbak" "$probe"
  }
  trap st_restore EXIT HUP INT TERM
  printf '\n' >> "$victim"
  if [ "$(digest)" != "$base" ]; then echo "  ✓ a changed corpus file invalidates the key"
  else echo "  ✗ a changed corpus file did NOT invalidate the key"; st_fail=$((st_fail+1)); fi
  cp "$bak" "$victim"
  [ "$(digest)" = "$base" ] || { echo "  ✗ restoring the corpus file did not restore the key"; st_fail=$((st_fail+1)); }

  fsub="$ROOT/specs/v2.2-p6.5-fsub.ssc"
  # SSC_NO_BUILD_CHECK: editing fsub.ssc moves the tree's content digest, so without this the second
  # run measures the launcher's STALE BUILD warning instead of the reference front. Measured — that
  # is exactly how this row failed on its first draft, accusing a sound exclusion.
  before=$(SSC_NO_BUILD_CHECK=1 SSC_FRONT=legacy timeout "$CAP" "$ssc" run "$probe" < /dev/null 2>&1 | head -8)
  printf '\n// agree-gate self-test probe\n' >> "$fsub"
  if [ "$(digest)" = "$base" ]; then echo "  ✓ a changed F source does NOT invalidate the key"
  else echo "  ✗ a changed F source invalidated the key — the exclusion is not in effect"; st_fail=$((st_fail+1)); fi
  after=$(SSC_NO_BUILD_CHECK=1 SSC_FRONT=legacy timeout "$CAP" "$ssc" run "$probe" < /dev/null 2>&1 | head -8)
  if [ "$before" = "$after" ]; then echo "  ✓ and the reference's ANSWER is unchanged by it — the exclusion is sound"
  else
    echo "  ✗ the reference front's answer CHANGED when F's source changed:"
    echo "      before: $(printf '%s' "$before" | head -1)"
    echo "      after : $(printf '%s' "$after" | head -1)"
    echo "    The exclusion is unsound and the cache must not exclude F."
    st_fail=$((st_fail+1))
  fi

  # ── the rows the FIRST version of this key was missing ────────────────────────────────────────
  #
  # The original self-test proved the exclusion on an UNBUILT edit of F's source, and passed — while
  # the real workflow is edit, REBUILD, run, and the rebuild moved `.build-digest`, which was in the
  # key. So the cache was cold on exactly the change it was built to survive, and the self-test could
  # not see it. These rows exercise what a rebuild actually changes instead of what an editor does.
  st_holds() {   # <name> <file> — mutating this must NOT move the key
    local nm=$1 f=$2 kb
    if [ ! -f "$f" ]; then echo "  ⊘ $nm: $f absent, skipped"; return; fi
    kb=$(mktemp); cp "$f" "$kb"; printf '\n// key probe\n' >> "$f"
    if [ "$(digest)" = "$base" ]; then echo "  ✓ $nm does not invalidate the key"
    else echo "  ✗ $nm INVALIDATED the key"; st_fail=$((st_fail+1)); fi
    cp "$kb" "$f"; rm -f "$kb"
  }
  st_moves() {   # <name> <file> — mutating this MUST move the key
    local nm=$1 f=$2 kb
    if [ ! -f "$f" ]; then echo "  ✗ $nm: $f absent — cannot prove the key covers it"; st_fail=$((st_fail+1)); return; fi
    kb=$(mktemp); cp "$f" "$kb"; printf '\n-- key probe\n' >> "$f"
    if [ "$(digest)" != "$base" ]; then echo "  ✓ $nm invalidates the key"
    else echo "  ✗ $nm did NOT invalidate the key — the reference front is not covered"; st_fail=$((st_fail+1)); fi
    cp "$kb" "$f"; rm -f "$kb"
  }

  base=$(digest)
  # what a REBUILD does to F: it restages the source. This is the row that would have caught the
  # first version, where `.build-digest` carried the F edit into the key.
  st_holds "the staged F front" "$ROOT/bin/lib/native-front/tower/bin/fsub.ssc"
  st_holds "the F runner"       "$ROOT/bin/lib/native-front/tower/bin/ssc1-run-fsub.ssc0"
  st_holds "the build digest"   "$ROOT/bin/lib/.build-digest"
  # the narrowing that motivated this: registering a gate must not cost the whole cache
  st_holds "scripts/smoke-ci.ssc" "$ROOT/scripts/smoke-ci.ssc"
  # …and the other direction, because a key that holds for everything is not a key
  st_moves "the reference lowerer" "$ROOT/bin/lib/native-front/tower/lib/ssc1-lower.ssc0"
  st_moves "the reference front"   "$ROOT/bin/lib/native-front/tower/lib/ssc1-front.ssc0"
  # the runtime is covered by its SOURCES, since its jars carry mtimes and cannot be hashed usefully
  st_moves "the CLI runtime source" "$ROOT/v1/tools/cli/src/main/scala/scalascript/cli/RunNativeV2.scala"

  # ── a divergence that does not reproduce must not be judged ───────────────────────────────────
  #
  # `examples/v2-http-sql-demo.ssc` makes a real HTTP request and reached `all three lanes differ`
  # on a timeout — a property of the network, not of either front. Excluding it by name would have
  # contradicted this gate's own rule that the subject set is a RULE and not a list, and would have
  # done nothing about the next nondeterministic subject.
  #
  # This row drives the classifier directly on a program whose FIRST run differs from every later
  # one: it prints A and drops a marker, then prints B forever. F runs first and sees A, the
  # reference runs second and sees B — a divergence — and the re-run pair both see B and agree. That
  # is exactly the shape the rule exists for, produced deterministically so the row cannot flake
  # itself.
  echo "── self-test: a divergence that does not reproduce"
  stw="$(mktemp -d)"
  write_worker "$stw/one.sh"
  marker="$stw/marker"
  cat > "$stw/flaky.ssc" <<SSCEOF
[exists, writeFile](std/fs.ssc)

def main(): Unit =
  if exists("$marker") then println("B")
  else
    writeFile("$marker", "x")
    println("A")
SSCEOF
  verdict=$(SSC_BIN="$ssc" CAP="$CAP" WORK="$stw" REFCACHE="" REF_VERIFY_EVERY=999999 \
            SSC_NO_BUILD_CHECK=1 bash "$stw/one.sh" "$stw/flaky.ssc" 2>/dev/null | cut -f1)
  if [ "$verdict" = "FLAKY" ]; then
    echo "  ✓ a non-reproducing divergence is bucketed FLAKY, not judged"
  else
    echo "  ✗ a non-reproducing divergence was classified '$verdict' — it should be FLAKY"
    echo "    (a program answering differently each run would be charged to a front)"
    st_fail=$((st_fail+1))
  fi
  rm -rf "$stw"

  # ── the row that DECIDES, opt-in because it rebuilds twice (~10 min) ──────────────────────────
  #
  # Ten unit rows above passed against a key that still broke in practice: each touches one file it
  # was told about, and a real rebuild touches whatever it likes — the jar it repackages carries ZIP
  # MTIMES, so its bytes moved while its content did not. Only this row saw it. Run it after any
  # change to `ref_world_digest`; the unit rows are necessary and not sufficient, which is the whole
  # lesson of this block.
  if [ "${2:-}" = "--with-rebuild" ]; then
    echo "── self-test: the real workflow (edit F, rebuild, compare) — two rebuilds, be patient"
    rb=$(mktemp); cp "$fsub" "$rb"
    k1=$(digest)
    printf '\n// self-test rebuild probe\n' >> "$fsub"
    if (cd "$ROOT" && ./install.sh --dev) > /dev/null 2>&1; then
      k2=$(digest)
      cp "$rb" "$fsub"; (cd "$ROOT" && ./install.sh --dev) > /dev/null 2>&1
      if [ "$k1" = "$k2" ]; then echo "  ✓ the key survives an F edit followed by a rebuild"
      else
        echo "  ✗ the key moved across an F edit + rebuild — the cache is cold on the one change"
        echo "    it exists to survive. Diff the staged tree before and after to find the input:"
        echo "    find bin/lib -name '*.ssc0' -o -name '*.ssc' | xargs shasum -a 256"
        st_fail=$((st_fail+1))
      fi
    else
      cp "$rb" "$fsub"
      echo "  ✗ the probe rebuild failed — cannot judge the key"; st_fail=$((st_fail+1))
    fi
    rm -f "$rb"
  fi

  if [ $st_fail -eq 0 ]; then echo "✓ f-output-agreement-gate --self-test PASSED"; exit 0; fi
  echo "✗ f-output-agreement-gate --self-test: $st_fail failure(s)"; exit 1
fi

# THE SUBJECT SET IS A RULE, not a list. The hand-sampled 140 files the numbers were first measured
# on had no reproducible definition, and a threshold frozen against a set nobody can rebuild is not a
# gate — it is a number. `examples/*.ssc` plus one level of `examples/frontend/` is the same SHAPE
# that sample had, stated as a glob, so it rebuilds identically on any checkout and grows when the
# corpus grows. tests/conformance IS INCLUDED since 2026-08-12: excluding it was measured wrong —
# a sweep of those 398 files found FIVE divergences against ONE in the examples set.
corpus=$(cd "$ROOT" && ls examples/*.ssc examples/frontend/*/*.ssc tests/conformance/*.ssc 2>/dev/null | sort)
total=$(printf '%s\n' "$corpus" | grep -c . || true)
if [ "${total:-0}" -lt 100 ]; then
  echo "✗ f-output-agreement-gate: found only ${total:-0} corpus files — the subject list is broken,"
  echo "  and a gate that measures nothing must not report green."
  exit 1
fi
echo "── comparing F against the reference front over $total corpus files (cap ${CAP}s)"

cd "$ROOT" || exit 1
export SSC_NO_BUILD_CHECK=1

# One classification per file, computed in PARALLEL and aggregated afterwards. Sequentially this is
# 20-50 minutes; the work is entirely JVM startup, so it parallelises almost perfectly. Each worker
# prints exactly one line — `<verdict> <TAB> <file>` — and nothing else, so the aggregation cannot be
# confused by a subject that writes to stderr.
work=$(mktemp -d "${TMPDIR:-/tmp}/f-agreement.XXXXXX")
trap 'rm -rf "$work"' EXIT HUP INT TERM
export SSC_BIN="$ssc" CAP WORK="$work"

# ── THE REFERENCE SIDE IS MEMOIZED ACROSS RUNS ───────────────────────────────────────────────────
#
# This gate exists to measure F, and F changes every session — but the REFERENCE front's answer for a
# given subject does not depend on F's source at all. Recomputing it costs ~3 s per measured file,
# ~416 files, ~21 worker-minutes on every rerun of a change that could not possibly have moved it.
#
# THE KEY IS A WHOLE-WORLD DIGEST, not the subject file. A subject's output depends on its transitive
# IMPORTS as much as on itself, and this gate has no import graph — so instead of guessing which
# files matter, every tracked `.ssc`/`.ssc0` in the tree is hashed, plus the toolchain's own build
# digest. Any change anywhere invalidates every entry. That is coarse on purpose: a key that is too
# broad wastes a run, a key that is too narrow reports a stale answer as a measurement, and only one
# of those is recoverable. Measured: hashing all 1501 files costs 0.28 s, once.
#
# THE ONE EXCLUSION IS F'S OWN SOURCE, and it is the reason this is worth anything — without it the
# common case (edit F, rerun the gate) invalidates the entire cache. An exclusion in a cache key is
# exactly how this repo once hid the default front, so it is not asserted here: `--self-test` proves
# that mutating F's staged source leaves the reference's answer byte-identical, and that mutating a
# NON-excluded file does invalidate. Run it after touching this block.
#
# AND EVERY RUN RE-VERIFIES A SAMPLE. A cache that is silently wrong passes every threshold this gate
# has, because a wrong reference answer turns a real divergence into AGREE. So one hit in
# REF_VERIFY_EVERY is recomputed anyway and compared; a mismatch is a hard failure, not a warning.
# The sample rotates with the world digest, so successive trees check different files while any one
# tree is reproducible.
REF_VERIFY_EVERY=${REF_VERIFY_EVERY:-12}
refcache=""
refworld=""
if [ "${SSC_GATE_REF_CACHE:-on}" != "off" ]; then
  refworld=$(ref_world_digest)
  refcache="${SSC_GATE_CACHE_DIR:-$HOME/.cache/ssc/agree-ref}/$refworld"
  mkdir -p "$refcache" 2>/dev/null || refcache=""
fi
export REFCACHE="$refcache" REF_VERIFY_EVERY
if [ -n "$refcache" ]; then
  echo "    reference side memoized under ${refcache/#$HOME/\~} (verifying 1 hit in $REF_VERIFY_EVERY)"
else
  echo "    reference side NOT memoized (SSC_GATE_REF_CACHE=off or no writable cache dir)"
fi
# A TIMEOUT IS NOT A VERDICT, and the first version of this worker treated it as one. Measured:
# examples/content-live-rows.ssc takes 13.3 s unloaded, so under `-P 4` it exceeded a 15 s cap, was
# killed, lost the "refusing to fall back" line that marks an F DECLINE, and was reported as
# "F is worse than the reference". Six files landed in that bucket for no reason but load, and
# examples/direct-syntax-demo.ssc did too — its two outputs are byte-identical when either side is
# allowed to finish. A gate whose verdict depends on how busy the machine is will be believed on a
# quiet host and disbelieved on a loud one, which is the worst of both.
#
# So the exit code is read, and a kill (124) puts the file in its own bucket that is never judged.
# Under contention the gate then degrades to "fewer subjects measured" — which the SUBJECT_MIN floor
# reports honestly — instead of manufacturing regressions.
write_worker "$work/one.sh"
chmod +x "$work/one.sh"
printf '%s\n' "$corpus" | grep . | xargs -P "${JOBS:-8}" -I{} "$work/one.sh" {} > "$work/rows.txt" 2>/dev/null

declined=$(grep -c '^DECLINED' "$work/rows.txt" || true)
agree=$(grep -c '^AGREE' "$work/rows.txt" || true)
bothfail=$(grep -c '^BOTHFAIL' "$work/rows.txt" || true)
timedout=$(grep -c '^TIMEOUT' "$work/rows.txt" || true)
disagree=$(grep -c '^DISAGREE' "$work/rows.txt" || true)
fwrong=$(grep -c '^F-WRONG' "$work/rows.txt" || true)
refwrong=$(grep -c '^REF-WRONG' "$work/rows.txt" || true)
unresolved=$(grep -c '^UNRESOLVED' "$work/rows.txt" || true)
cachemismatch=$(grep -c '^CACHE-MISMATCH' "$work/rows.txt" || true)
flaky=$(grep -c '^FLAKY' "$work/rows.txt" || true)
refhits=$(grep -c '^REFHIT' "$work/refstats.txt" 2>/dev/null || true)
refmiss=$(grep -c '^REFMISS' "$work/refstats.txt" 2>/dev/null || true)
worse=$fwrong   # kept for the threshold block below
subjects=$((agree + bothfail + disagree + fwrong + refwrong + unresolved))

echo "    F declined (coverage, not judged here): $declined   timed out (not judged): $timedout   nondeterministic (not judged): $flaky"
echo "    measured: $subjects   agree: $agree   both fail identically: $bothfail   disagree: $disagree"
echo "    divergences arbitrated by the v1 interpreter:"
echo "      F contradicted by BOTH other lanes: $fwrong   reference contradicted: $refwrong   all three differ: $unresolved"
if [ -n "$refcache" ]; then echo "    reference cache: ${refhits:-0} reused, ${refmiss:-0} computed"; fi
if [ "${flaky:-0}" -gt 0 ]; then echo "── nondeterministic (divergence did not reproduce; verdict is not a property of either front):"; awk -F'\t' '$1=="FLAKY"{print "  " $2}' "$work/rows.txt"; fi
if [ "$disagree" -gt 0 ]; then echo "── disagreements (both fail, different message):"; awk -F'\t' '$1=="DISAGREE"{print "  " $2}' "$work/rows.txt"; fi
if [ "$fwrong" -gt 0 ]; then echo "── where F is WRONG (interpreter agrees with the reference):"; awk -F'\t' '$1=="F-WRONG"{print "  " $2}' "$work/rows.txt"; fi
if [ "$refwrong" -gt 0 ]; then echo "── where the REFERENCE is wrong (interpreter agrees with F) — not F's work:"; awk -F'\t' '$1=="REF-WRONG"{print "  " $2}' "$work/rows.txt"; fi
if [ "$unresolved" -gt 0 ]; then echo "── all three lanes differ:"; awk -F'\t' '$1=="UNRESOLVED"{print "  " $2}' "$work/rows.txt"; fi

fails=0
# A WRONG CACHE IS WORSE THAN A SLOW GATE, and it is invisible to every threshold below: a stale
# reference answer turns a real divergence into AGREE. So this is checked first and fails hard.
if [ "${cachemismatch:-0}" -gt 0 ]; then
  echo "✗ the memoized reference answer DIFFERS from a fresh run on $cachemismatch file(s):"
  awk -F'\t' '$1=="CACHE-MISMATCH"{print "  " $2}' "$work/rows.txt"
  echo "  The world digest is missing an input the reference front reads. Clear the cache"
  echo "  ($refcache) and widen the key before believing any number in this run."
  fails=$((fails + 1))
fi
if [ "$subjects" -lt "$SUBJECT_MIN" ]; then
  echo "✗ measured only $subjects files, floor is $SUBJECT_MIN — the subject set shrank, so the two"
  echo "  numbers below are not comparable with the frozen ones and cannot be trusted."
  fails=$((fails + 1))
fi
if [ "$fwrong" -gt "$FWRONG_MAX" ]; then
  echo "✗ F is contradicted by BOTH other lanes on $fwrong files, ceiling is $FWRONG_MAX."
  echo "  Two independent lanes agreeing against F is a REGRESSION, not a coverage gap."
  fails=$((fails + 1))
fi
if [ "$agree" -lt "$AGREE_MIN" ]; then
  echo "✗ agreement fell to $agree, floor is $AGREE_MIN."
  fails=$((fails + 1))
fi

if [ "$fails" -eq 0 ]; then
  echo "✓ f-output-agreement-gate PASSED  (F-wrong $fwrong ≤ $FWRONG_MAX, agree $agree ≥ $AGREE_MIN, measured $subjects ≥ $SUBJECT_MIN)"
  exit 0
fi
echo "✗ f-output-agreement-gate: $fails threshold(s) breached"
exit 1
