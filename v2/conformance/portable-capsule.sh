#!/usr/bin/env bash
# Portable-CodeMode capsule — the fresh-process cross-host-resume FOUNDATION (vector 15).
#
# Freezes a capsule whose resume PROGRAM travels as closed CoreIR bytes, then admits and
# runs it in a SEPARATE JVM process that holds NO machine — proving control-interoperability
# §14.3 items 10-11 (a run does not need the original process or artifact). This is the
# VM-side Portable counterpart of the host SDK's ExactArtifact cross-host test
# (v2/host/scala/control/.../CrossHostResumeTest.scala), where the machine stays in memory.
#
# It does NOT flip conformance vector 15-cross-host-resume: the §10.2 pass that GENERATES a
# closed resume program from an arbitrary .ssc saveable region, and a second admitting
# backend for the full §14.4 cross-backend N→M matrix, remain separate work. The resume
# program here is hand-authored: (frame, input) => frame*10 + input.
set -euo pipefail
cd "$(dirname "$0")"          # v2/conformance
SRC=../src

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
JAR="$TMP/ssc.jar"

# Build the assembly jar, cached by a hash of src/ (mirrors check.sh). ~2-3 min cold.
CACHE_DIR="${SSC_CONF_CACHE:-$HOME/.cache/ssc-conf}"
mkdir -p "$CACHE_DIR"
HASH="$(find "$SRC" -type f \( -name '*.scala' -o -name '*.sc' \) -exec shasum {} + 2>/dev/null | shasum | awk '{print $1}')"
CACHED="$CACHE_DIR/ssc-$HASH.jar"
if [ -z "${SSC_CONF_NOCACHE:-}" ] && [ -n "$HASH" ] && [ -s "$CACHED" ]; then
  cp "$CACHED" "$JAR"
else
  echo "building ssc ..." >&2
  scala-cli --power package "$SRC" -o "$JAR" -f --assembly --server=false -q >/dev/null 2>&1
  [ -n "$HASH" ] && cp "$JAR" "$CACHED" 2>/dev/null || true
fi
ssc() { java -jar "$JAR" "$@"; }

fail=0
check() { # name got want
  if [ "$2" = "$3" ]; then printf 'ok   %-30s => %s\n' "$1" "$2"
  else printf 'FAIL %-30s got [%s] want [%s]\n' "$1" "$2" "$3"; fail=1; fi
}

CAP="$TMP/demo.portable"

# process 1: FREEZE the capsule (frame captured = 4).
ssc freeze-capsule "$CAP" 4 >/dev/null

# the resume program travels as closed CoreIR in the bytes (no machine in the capsule).
if grep -q '(resume (program' "$CAP"; then printf 'ok   %-30s => yes\n' "resume program travels in bytes"
else printf 'FAIL %-30s\n' "resume program travels in bytes"; fail=1; fi

# processes 2 & 3 (SEPARATE JVMs, holding no machine): admit + run. frame*10 + input.
check "fresh-process run input=2" "$(ssc run-capsule "$CAP" 2)" "42"
check "fresh-process run input=5" "$(ssc run-capsule "$CAP" 5)" "45"  # multi-shot, independent

# integrity: tampering the resume program is rejected at admission, before any run.
sed 's/i.mul/i.sub/' "$CAP" > "$CAP.tampered"
if ssc run-capsule "$CAP.tampered" 2 >/dev/null 2>&1; then
  printf 'FAIL %-30s tampered resume admitted\n' "tamper rejected"; fail=1
else
  printf 'ok   %-30s => rejected\n' "tamper rejected"
fi

# §10.2 reification: `freeze-region` closure-converts a compiler-declared saveable region
# (frame captures a=3, b=4; resume (a,b,input) => a*input + b) into a capsule whose resume
# program is GENERATED (not hand-authored) — a closed Lam(2, ...) that destructures the frame
# tuple and applies the region lambda. Proves the §10.2 frame-construction + closure-conversion
# steps end-to-end via the same machine-less fresh-process runner.
REG="$TMP/region.portable"
ssc freeze-region "$REG" >/dev/null
if grep -q '(frame (ctor frame' "$REG"; then printf 'ok   %-30s => yes\n' "reified frame is a tuple"
else printf 'FAIL %-30s\n' "reified frame is a tuple"; fail=1; fi
check "reified region run input=5" "$(ssc run-capsule "$REG" 5)" "19"   # 3*5 + 4
check "reified region run input=2" "$(ssc run-capsule "$REG" 2)" "10"   # 3*2 + 4

# §10.2 auto-liveness (slice 2): `freeze-region-auto` DERIVES the frame from a free-variable
# analysis of the region body — no explicit slots. The demo region `(input) => a + input*b`
# is written with a nested lambda so the depth-aware de-Bruijn rewrite is exercised; the pass
# finds the free outer vars {a=3, b=4}, builds the frame tuple, and closes the body over it.
AUTO="$TMP/auto.portable"
ssc freeze-region-auto "$AUTO" >/dev/null
check "auto-liveness run input=5" "$(ssc run-capsule "$AUTO" 5)" "23"   # 3 + 5*4
check "auto-liveness run input=2" "$(ssc run-capsule "$AUTO" 2)" "11"   # 3 + 2*4

# §10.2 global closure (slice 2): the region calls `quad`, which calls `dbl`, so BOTH must travel
# in resume.defs — `validate` admits `(global g)` only when g is a def of the same program, and the
# runner process holds no machine and no source. `unused` must NOT travel: the pass selects the
# transitive closure, it does not dump the program. Region: (input) => quad(input) + a, a = 5.
GLOB="$TMP/global.portable"
ssc freeze-region-global "$GLOB" >/dev/null
if grep -q '(def quad' "$GLOB" && grep -q '(def dbl' "$GLOB"; then
  printf 'ok   %-30s => yes\n' "reached defs travel (transitive)"
else printf 'FAIL %-30s\n' "reached defs travel (transitive)"; fail=1; fi
if grep -q '(def unused' "$GLOB"; then
  printf 'FAIL %-30s unreached def was carried\n' "closure selects, not dumps"; fail=1
else printf 'ok   %-30s => yes\n' "closure selects, not dumps"; fi
check "global-closure run input=3" "$(ssc run-capsule "$GLOB" 3)" "17"  # quad(3)=12, +5
check "global-closure run input=1" "$(ssc run-capsule "$GLOB" 1)" "9"   # quad(1)=4,  +5

# Fail-CLOSED: dropping a carried def from the bytes must be rejected at admission, not run with a
# silently-missing global. This is the check that makes the two above mean something.
sed '/(def dbl/d' "$GLOB" > "$GLOB.nodbl"
if ssc run-capsule "$GLOB.nodbl" 3 >/dev/null 2>&1; then
  printf 'FAIL %-30s capsule with a missing def was admitted\n' "missing def rejected"; fail=1
else
  printf 'ok   %-30s => rejected\n' "missing def rejected"
fi

# §10.2 nominal frame (slice 3): the frame slot is a CONSTRUCTOR value, not a scalar — the region
# `(input) => match p { case Pair(x,y) => x*input + y }` with p = Pair(3,4). Auto-liveness derives
# the single slot; the value travels as data and the resume destructures it with an ordinary Match.
NOM="$TMP/nominal.portable"
ssc freeze-region-nominal "$NOM" >/dev/null
if grep -q '(frame (ctor frame (ctor Pair' "$NOM"; then printf 'ok   %-30s => yes\n' "nominal slot travels as data"
else printf 'FAIL %-30s\n' "nominal slot travels as data"; fail=1; fi
check "nominal frame run input=5" "$(ssc run-capsule "$NOM" 5)" "19"   # 3*5 + 4
check "nominal frame run input=2" "$(ssc run-capsule "$NOM" 2)" "10"   # 3*2 + 4

# The frame is DATA, never code (BUGS portable-capsule-frame-unvalidated). Before validateFrame,
# `decode` validated only the resume: a frame carrying (global g) injected a closure into the
# resume, and (local 0) reached Compiler.compile and died with ArrayIndexOutOfBounds. Both must now
# be REJECTED at admission. Measured pre-fix behaviour is in the BUGS entry.
reject_frame() { # name  sed-expr
  sed "$2" "$GLOB" > "$TMP/badframe"
  if ssc run-capsule "$TMP/badframe" 3 >/dev/null 2>&1; then
    printf 'FAIL %-30s admitted\n' "$1"; fail=1
  else
    printf 'ok   %-30s => rejected\n' "$1"
  fi
}
reject_frame "frame with code rejected"  's/(frame (ctor frame (lit (int 5))/(frame (ctor frame (global dbl)/'
reject_frame "frame with local rejected" 's/(frame (ctor frame (lit (int 5))/(frame (ctor frame (local 0)/'
reject_frame "frame with lam rejected"   's/(frame (ctor frame (lit (int 5))/(frame (ctor frame (lam 1 (local 0))/'
# ... and a legitimate value edit still runs (the guard rejects CODE, not data — it must not be a
# blanket "any frame edit fails", which would pass the three checks above for the wrong reason).
sed 's/(frame (ctor frame (lit (int 5))/(frame (ctor frame (lit (int 99))/' "$GLOB" > "$TMP/datachange"
check "data-only frame edit still runs" "$(ssc run-capsule "$TMP/datachange" 3)" "111"  # quad(3)=12, +99

# §10.2 slice 4 — EFFECTFUL regions. Measured 2026-07-27 and it corrected the slice's premise: an
# Fx-CLOSED region (the perform AND its handler inside) already reifies and runs machine-less, with
# no local CPS pass — the frame slot is even read from inside the handler lambda. The region is
# `(input) => effect.handle(effect.perform("E.get", input), (event) => a * 10)` with a = 5, so the
# handled result is 50 for ANY input (the handler discards the operation and uses the frame).
EFF="$TMP/effect.portable"
ssc freeze-region-effect "$EFF" >/dev/null
check "effectful region run input=3" "$(ssc run-capsule "$EFF" 3)" "50"
check "effectful region run input=7" "$(ssc run-capsule "$EFF" 7)" "50"

# The OPEN case must be refused BEFORE any bytes exist (§11.3 Fx-closed). Before the guard this
# froze happily and the run returned `Op("E.get", 8, <closure>)` — a LIVE continuation handed to a
# runner that holds no machine and no handlers.
if ssc freeze-region-effect "$TMP/open.portable" --escaping >/dev/null 2>&1; then
  printf 'FAIL %-30s Fx-open region was frozen\n' "Fx-open refused at freeze"; fail=1
else
  printf 'ok   %-30s => refused\n' "Fx-open refused at freeze"
fi

# Defence in depth: a capsule from ANY other producer (here a committed fixture, frozen by the
# pre-guard build) must be refused at RUN. The reify-time guard cannot cover foreign capsules, and
# this fixture is the only way to keep that second layer honest — the current tool cannot produce
# one by design.
if ssc run-capsule fixtures/fx-open.portable 3 >/dev/null 2>&1; then
  printf 'FAIL %-30s foreign Fx-open capsule ran\n' "Fx-open refused at run"; fail=1
else
  printf 'ok   %-30s => refused\n' "Fx-open refused at run"
fi

# ── format-v2 SEAL (specs/portable-capsule-seal.md; Sergiy's decision (c)) ───────────────────────
# The capsule's DATA half used to be covered by nothing: `resume-digest` is by design a CODE digest,
# so editing the captured frame in the bytes was accepted silently (17 -> 111, exit 0). The VM lane
# now carries the host lane's seal — HMAC-SHA256 over the canonical body with an EMPTY signature
# slot, plus audience/tenant binding and a budget.
#
# The PAIR below is what makes this gate mean something: the SAME frame edit must be REJECTED when
# the runner is keyed and must still RUN when it is not. Only one of those lines on its own would
# be satisfied by a blanket refusal (or by no seal at all).
SEAL="$TMP/sealed.portable"
export SSC_CAPSULE_KEY=gate-key SSC_CAPSULE_AUDIENCE=gate-aud SSC_CAPSULE_TENANT=gate-ten
ssc freeze-region-global "$SEAL" >/dev/null
if grep -q '(signature [0-9a-f]' "$SEAL"; then printf 'ok   %-30s => yes\n' "keyed freeze signs the body"
else printf 'FAIL %-30s\n' "keyed freeze signs the body"; fail=1; fi
check "sealed run, same key" "$(ssc run-capsule "$SEAL" 3)" "17"

reject_sealed() { # name | env-prefix | file
  if env $2 java -jar "$JAR" run-capsule "$3" 3 >/dev/null 2>&1; then
    printf 'FAIL %-30s admitted\n' "$1"; fail=1
  else printf 'ok   %-30s => rejected\n' "$1"; fi
}
sed 's/(lit (int 5))/(lit (int 99))/' "$SEAL" > "$TMP/sealed-edited.portable"
reject_sealed "sealed: frame edit rejected"  "SSC_CAPSULE_KEY=gate-key SSC_CAPSULE_AUDIENCE=gate-aud SSC_CAPSULE_TENANT=gate-ten" "$TMP/sealed-edited.portable"
reject_sealed "sealed: wrong key rejected"   "SSC_CAPSULE_KEY=other    SSC_CAPSULE_AUDIENCE=gate-aud SSC_CAPSULE_TENANT=gate-ten" "$SEAL"
reject_sealed "sealed: audience mismatch"    "SSC_CAPSULE_KEY=gate-key SSC_CAPSULE_AUDIENCE=elsewhere SSC_CAPSULE_TENANT=gate-ten" "$SEAL"
reject_sealed "sealed: tenant mismatch"      "SSC_CAPSULE_KEY=gate-key SSC_CAPSULE_AUDIENCE=gate-aud SSC_CAPSULE_TENANT=other"     "$SEAL"

# The trusted in-process path — unkeyed — still runs a sealed capsule (the host's contract, adopted
# deliberately: an unkeyed runner has no key to verify with, so a signature admits nothing extra).
unset SSC_CAPSULE_KEY SSC_CAPSULE_AUDIENCE SSC_CAPSULE_TENANT
check "sealed run, unkeyed runner" "$(ssc run-capsule "$SEAL" 3)" "17"
# … and THIS is the other half of the pair: the same edit the keyed runner refused still runs here.
check "unsigned path: frame edit runs" "$(ssc run-capsule "$TMP/sealed-edited.portable" 3)" "111"

# A keyed runner does not admit what it cannot verify: an unsigned v2, or a v1 legacy capsule.
ssc freeze-region-global "$TMP/unsigned.portable" >/dev/null
reject_sealed "keyed: unsigned rejected"     "SSC_CAPSULE_KEY=gate-key" "$TMP/unsigned.portable"
reject_sealed "keyed: v1 legacy rejected"    "SSC_CAPSULE_KEY=gate-key" "fixtures/fx-open.portable"

# Budget is a RESOURCE failure, kept distinct from tampering (§13 non-collapsibility: a quota
# problem must not be reported as an attack). Checked even on the unkeyed path.
SSC_CAPSULE_BUDGET=100 ssc freeze-region-global "$TMP/big.portable" >/dev/null
reject_sealed "budget over runner rejected"  "SSC_CAPSULE_RUNNER_BUDGET=10" "$TMP/big.portable"

if [ "$fail" -eq 0 ]; then echo "portable-capsule: PASS"; else echo "portable-capsule: FAIL"; exit 1; fi
