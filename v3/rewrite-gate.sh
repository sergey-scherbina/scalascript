#!/usr/bin/env bash
#
# THE REWRITE PASS CAN SAY NO — the seven rules of specs/60-compile-time-extension.md, each asserted
# on the failure it prevents, not just the happy path.
#
# WHY EACH CHECK IS HERE. The pass has exactly three refusals and three guards, and every one was
# designed against a failure this repository has already paid for (the spec names them). A gate
# that ran one marker through and checked the output would be green while a runaway rewrite hangs,
# while an exception escapes as a CRASH, or while two plugins silently fight over a name — the
# last-wins registry read that turned main red on 2026-08-19 is the standing example.
#
# THE PROBE IS THE LEVER. `MarkerProbe` (v3/plugins/MarkerProbe.scala) claims a name only when
# `SSC3_MARKER_PROBE` asks, and `SSC3_MARKER_PROBE_MODE` selects which behaviour to demonstrate —
# success, refusal, an unclaimed marker, a runaway. The modes exist FOR this gate: without a lever
# per failure, four of the seven rules are unfalsifiable from the outside.
#
# BOTH LANES where the assertion is about behaviour (I-3): a rewrite that ran on one lane and not
# the other would be exactly the two-front bug the pass exists to prevent.
#
# Usage: v3/rewrite-gate.sh
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2
SSC3="v3/ssc3"

# THE PROBE LIVES IN THE FLEET, so a tree without it cannot ask this gate's questions. Saying so is
# not the same as passing: a gate that goes green when its subject is absent reports less than it
# claims, which is the defect `front-capability-gate.sh` was written against. Same convention as that
# file — loud always, and RED on CI, where the fleet is built by an explicit step that must fail
# instead of silently shrinking what the gates measure.
cannot() {
  echo "rewrite-gate: CANNOT RUN — $1"
  echo "  MarkerProbe is compiled from v3/plugins/, which is the fleet; run v3/plugin-classpath.sh."
  if [ "${CI:-}" = "true" ]; then exit 1; fi
  exit 0
}
[ "${SSC3_FLEET:-on}" = "off" ] && cannot "SSC3_FLEET=off removes the fleet from the classpath"
[ -s "$ROOT/v3/.jars/plugins.cp" ] || v3/plugin-classpath.sh >/dev/null 2>&1 || true
[ -s "$ROOT/v3/.jars/plugins.cp" ] || cannot "v3/.jars/plugins.cp is absent in this checkout"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

F="$TMP/probe.ssc"
printf 'val x = probe(41)\nprintln(x)\n' > "$F"

fails=0
say()  { printf '  %-4s %s\n' "$1" "$2"; }
red()  { say FAIL "$1"; fails=$((fails + 1)); }

# ── 1. THE PASS RUNS, on both lanes, and the CONTROL is the same file with the probe off ─────────
# With no claim, `probe` is an ordinary unknown name and the program refuses; with the claim, the
# marker is rewritten to `{ val $mrN_probe = 41; $mrN_probe }` and both lanes answer 41. The two
# runs differing is what proves the pass moved something — a gate whose green does not depend on
# the mechanism would be green for the wrong reason.
ctl_err="$(timeout 60 $SSC3 run "$F" 2>&1 >/dev/null)"; ctl_rc=$?
if [ $ctl_rc -eq 0 ]; then red "control: 'probe(41)' with NO claim was accepted — the control is dead"; fi
got="$(SSC3_MARKER_PROBE=1 timeout 60 $SSC3 run "$F" 2>"$TMP/e1")"; rc=$?
via="$(SSC3_MARKER_PROBE=1 timeout 60 $SSC3 run --bridge "$F" 2>"$TMP/e2")"; brc=$?
if [ $rc -ne 0 ] || [ "$got" != "41" ]; then
  red "unwrap: executor said rc=$rc [$got], wanted 41 — $(grep -m1 '^ssc3:' "$TMP/e1" | cut -c1-100)"
elif [ $brc -ne 0 ] || [ "$via" != "41" ]; then
  red "unwrap: bridge said rc=$brc [$via], wanted 41 — $(grep -m1 '^ssc3:' "$TMP/e2" | cut -c1-100)"
else
  say ok "the pass rewrites, both lanes answer 41, and the claimless control still refuses"
fi

# ── 2. AN UNCLAIMED MARKER REFUSES, positioned, naming the GHOST, and only a rewrite can mint one —
# the fronts ask `hasRewrite` before building a marker, so `mint` mode (probe rewrites to
# `probe$ghost`, which nobody claims) is the only road to this refusal, and the blame must land on
# the producer's output, not on the name the user wrote.
err="$(SSC3_MARKER_PROBE=1 SSC3_MARKER_PROBE_MODE=mint timeout 60 $SSC3 run "$F" 2>&1 >/dev/null)"; rc=$?
if [ $rc -eq 0 ]; then red "mint: an unclaimed marker was accepted"
elif ! grep -qE ':[0-9]+:[0-9]+: ' <<<"$err"; then red "mint: refusal has no :line:col: — [$(cut -c1-100 <<<"$err")]"
elif ! grep -q "no rewrite is registered for the marker 'probe\$ghost'" <<<"$err"; then
  red "mint: refusal does not name the ghost — [$(cut -c1-120 <<<"$err")]"
else say ok "an unclaimed marker refuses with a position and names 'probe\$ghost'"; fi

# ── 3. A CLIENT'S `Refusal` KEEPS THE `:line:col:` SHAPE — corpus-report.sh classifies an honest
# refusal by that shape; an exception escaping here counts as CRASH, which is a floor.
err="$(SSC3_MARKER_PROBE=1 SSC3_MARKER_PROBE_MODE=refuse timeout 60 $SSC3 run "$F" 2>&1 >/dev/null)"; rc=$?
if [ $rc -eq 0 ]; then red "refuse: a refusing rewrite was accepted"
elif ! grep -qE ":[0-9]+:[0-9]+: the probe refuses 'probe' by request" <<<"$err"; then
  red "refuse: wrong shape — [$(cut -c1-120 <<<"$err")]"
elif grep -qE 'Exception|at ssc3\.' <<<"$err"; then red "refuse: a stack trace escaped — [$(cut -c1-120 <<<"$err")]"
else say ok "a client Refusal becomes the same :line:col: sentence a front produces"; fi

# ── 4. A RUNAWAY REWRITE STOPS AT THE BOUND and says which marker — identity IS the runaway: a
# rewrite that returns its own marker unchanged would loop forever, and must refuse, not hang.
err="$(SSC3_MARKER_PROBE=1 SSC3_MARKER_PROBE_MODE=runaway timeout 60 $SSC3 run "$F" 2>&1 >/dev/null)"; rc=$?
if [ $rc -eq 0 ]; then red "runaway: an identity rewrite was accepted"
elif ! grep -q "the marker 'probe' was still a marker after" <<<"$err"; then
  red "runaway: the bound refusal does not name the marker — [$(cut -c1-120 <<<"$err")]"
else say ok "a runaway rewrite refuses at the bound and names 'probe'"; fi

# ── 5. A CLAIM IS EXCLUSIVE — registering the same name twice throws at registration, not a race.
err="$(SSC3_MARKER_PROBE=probe,probe timeout 60 $SSC3 run "$F" 2>&1 >/dev/null)"; rc=$?
if [ $rc -eq 0 ]; then red "duplicate: two claims on 'probe' were accepted"
elif ! grep -q "two rewrites claim the marker 'probe'" <<<"$err"; then
  red "duplicate: wrong message — [$(cut -c1-120 <<<"$err")]"
else say ok "a second claim on 'probe' is refused at registration"; fi

# ── 6. THE MECHANISM SWITCHES OFF — with SSC3_FLEET=off there are no registrations, so the probe
# env var changes NOTHING: stderr must be byte-identical to the claimless control. Rule 5 verbatim:
# a mechanism that cannot be switched off cannot be measured.
off_err="$(SSC3_FLEET=off SSC3_MARKER_PROBE=1 timeout 60 $SSC3 run "$F" 2>&1 >/dev/null)"; off_rc=$?
off_ctl="$(SSC3_FLEET=off timeout 60 $SSC3 run "$F" 2>&1 >/dev/null)"
if [ $off_rc -eq 0 ]; then red "fleet-off: the probe still rewrote with the fleet off"
elif [ "$off_err" != "$off_ctl" ]; then
  red "fleet-off: the probe env var changed the refusal — [probe: $(cut -c1-80 <<<"$off_err")] vs [ctl: $(cut -c1-80 <<<"$off_ctl")]"
else say ok "with SSC3_FLEET=off the probe changes nothing, byte for byte"; fi

# ── 7. A REWRITE RUNS TWICE ON A FILE THAT NEEDS THE PRELUDE, and mints the same names both times.
# `Driver.moduleOf` lowers WITHOUT the prelude first and retries with it on any `LowerFail`, so a
# file naming a prelude member goes through the pass twice. That is rule 7, and it is the one rule
# nobody would have guessed — it is a fact about `Driver`, not about this door.
#
# THE PAYLOAD HAS TO DEPEND ON HOW MANY TIMES THE PASS RAN, or this check cannot fail. `probe(41)`
# answers 41 whether the pass ran once or twice, and a fixture whose payload ignores its input is
# exactly how a run-twice defect stays invisible — measured elsewhere in this repository the same
# week, where a continuation composed TWICE was green for months because its remainder was `"END"`.
# So `stamp` mode expands to the STRING of the name `Ctx.fresh` minted: with a per-pass counter both
# attempts mint `$mr1_probe` and the two files agree; with a counter that survived the retry the
# prelude file would answer `$mr2_probe` and this check would say so.
G="$TMP/retry.ssc"
printf 'val x = probe(0)\nprintln(x)\nprintln(math.Pi - math.Pi)\n' > "$G"
H="$TMP/noretry.ssc"
printf 'val x = probe(0)\nprintln(x)\n' > "$H"
plain="$(SSC3_MARKER_PROBE=1 SSC3_MARKER_PROBE_MODE=stamp timeout 60 $SSC3 run "$H" 2>"$TMP/e7a")"; prc=$?
stamped="$(SSC3_MARKER_PROBE=1 SSC3_MARKER_PROBE_MODE=stamp timeout 60 $SSC3 run "$G" 2>"$TMP/e7")"; rc=$?
stamped="$(printf '%s' "$stamped" | head -1)"
if [ $prc -ne 0 ] || [ -z "$plain" ]; then
  red "retry: the no-prelude control did not run — $(grep -m1 '^ssc3:' "$TMP/e7a" | cut -c1-90)"
elif [ $rc -ne 0 ]; then
  red "retry: the prelude file did not run — $(grep -m1 '^ssc3:' "$TMP/e7" | cut -c1-90)"
elif [ "$stamped" != "$plain" ]; then
  red "retry: the prelude retry minted [$stamped] where one pass mints [$plain] — Ctx.fresh survived the retry, so a rewrite that reads its own counter would ship the SECOND attempt's tree"
else say ok "the prelude retry lowers twice and mints the same name both times ($plain)"; fi

# ── 8. THE THREE SPELLINGS PRINT THE SAME TREE ON BOTH FRONTS, and this check exists because its
# absence let one through. R1 verified two spellings by hand — a bare call and a call with type
# arguments — and `direct[F] { … }`, the third, disagreed: v3's front read it as an ordinary
# trailing-block call and wrapped the block in a LAMBDA, while the projection built a marker holding
# the raw block AND handed back `direct` as its own first type argument. Neither is visible until a
# client reads `typeArgs`, so the divergence would have surfaced as a wrong program in R3.
#
# `ssc3 ast` renders the merged tree WITHOUT lowering, so this compares what the FRONTS built and
# nothing downstream of them.
printf 'val a = probe(41)\n'                                   > "$TMP/s1.ssc"
printf 'val b = Focus[Person](_.age)\n'                        > "$TMP/s2.ssc"
printf 'val c = direct[Option] {\n  x = Some(40)\n  Some(x)\n}\n' > "$TMP/s3.ssc"
# THE FOURTH SPELLING: type arguments and NO argument list. `Prism[S, C]` is named entirely by
# its types, and the two fronts threw that away differently — v3's own parser dropped the captured
# brackets and left a bare name, while the projection named the marker after the node's KIND
# (`prism`) instead of what the person wrote. The nested one is here because the two capturers
# disagreed once more the moment they stopped disagreeing about the first: the projection kept the
# COMMA as a list element (`["Shape", ",", "Circle"]`) where v3 splits at top-level commas.
printf 'val d = Prism[Shape, Circle]\n' > "$TMP/s4.ssc"
printf 'val e = Focus[Map[String, Int]](_.size)\n' > "$TMP/s5.ssc"
# THE PROBE CLAIMS ONLY WHAT NO CLIENT OWNS, and this line has been paid for twice — once when
# `DirectSyntax` took `direct`, once when `PrismSyntax` took `Prism`. Asking the probe for a claimed
# name is two plugins claiming one marker, rule 1, and the fleet refuses to install at all rather
# than picking a winner. Neither spelling needs a probe once its client exists: `ssc3 ast` renders
# the tree WITHOUT running the pass, so which plugin owns the name does not enter into what the
# fronts print — only THAT one owns it does. Every future client shortens this list by one.
for n in 1 2 3 4 5; do
  f="$TMP/s$n.ssc"
  u="$(SSC3_MARKER_PROBE=probe,Focus timeout 60 $SSC3 ast "$f" 2>&1)"
  v="$(SSC3_FRONT=v3 SSC3_MARKER_PROBE=probe,Focus timeout 60 $SSC3 ast "$f" 2>&1)"
  if [ "$u" != "$v" ]; then
    red "spelling $n: the fronts print different trees
      uniml: $(printf '%s' "$u" | tr -d '\n' | cut -c1-150)
      v3   : $(printf '%s' "$v" | tr -d '\n' | cut -c1-150)"
  elif ! grep -q '(marker ' <<<"$u"; then
    red "spelling $n: neither front built a marker at all — [$(printf '%s' "$u" | tr -d '\n' | cut -c1-120)]"
  else
    say ok "spelling $n prints one tree on both fronts: $(printf '%s' "$u" | tr -d '\n' | grep -o '(marker [^()]*' | head -1)"
  fi
done

# ══ THE CLIENTS ══════════════════════════════════════════════════════════════════════════════════
# Checks 1-8 are about the MECHANISM. What follows is one section per registered client, and they
# live here rather than in `v3/tests/front/` for a precondition reason: a client is a plugin, so a
# tree without the fleet cannot run it — and this gate already refuses to be green in that tree,
# which a front fixture would not.

# ── 9. `direct[M] { … }` — every clause of the reference rule in ONE program, on BOTH lanes.
# The four statement kinds are the whole desugaring (`v2/lib/ssc1-lower.ssc0:2273`, `directStmts`),
# and the one that catches a re-derivation is the MUTABLE one: `x = Some(2)` and `c = c + x` are the
# same node, `Expr.Assign`, and only the `var` declared above tells the second from the first. A
# client that dropped the mutable set would bind `c` monadically and answer `Some(Some(…))` or
# refuse — so this program is written so that getting it wrong cannot answer 44.
D="$TMP/direct.ssc"
printf 'val r = direct[Option] {\n  var c = 40\n  x = Some(2)\n  val doubled = x * 2\n  val _ = Some("ignored")\n  c = c + doubled\n  Some(c)\n}\nprintln(r)\n' > "$D"
dgot="$(timeout 60 $SSC3 run "$D" 2>"$TMP/e9")"; drc=$?
dvia="$(timeout 120 $SSC3 run --bridge "$D" 2>"$TMP/e9b")"; dbrc=$?
if [ $drc -ne 0 ] || [ "$dgot" != "Some(44)" ]; then
  red "direct: executor said rc=$drc [$dgot], wanted Some(44) — $(grep -m1 '^ssc3:' "$TMP/e9" | cut -c1-100)"
elif [ $dbrc -ne 0 ] || [ "$dvia" != "Some(44)" ]; then
  red "direct: bridge said rc=$dbrc [$dvia], wanted Some(44) — $(grep -m1 '^ssc3:' "$TMP/e9b") "
else say ok "direct: bind, pure val, discarded val, var-and-assignment — Some(44) on both lanes"; fi

# ── 10. AN UNSUPPORTED MONAD REFUSES WITH A POSITION. The reference answers `direct[IO] { … }` with
# a VARIABLE named `__unsupported_direct_IO` — an unknown name, reported wherever names resolve and
# naming nothing the author can act on. A client refuses instead, which is rule 3, and this check is
# what keeps that from quietly regressing to an invented identifier.
printf 'val r = direct[IO] {\n  x = Some(1)\n  Some(x)\n}\n' > "$TMP/unsup.ssc"
uerr="$(timeout 60 $SSC3 run "$TMP/unsup.ssc" 2>&1 >/dev/null)"; urc=$?
if [ $urc -eq 0 ]; then red "direct: an unsupported monad was accepted"
elif ! grep -qE ':[0-9]+:[0-9]+: direct\[IO\] is not supported' <<<"$uerr"; then
  red "direct: the unsupported-monad refusal is the wrong shape — [$(cut -c1-120 <<<"$uerr")]"
elif grep -q "__unsupported_direct" <<<"$uerr"; then
  red "direct: refused by inventing an identifier, the reference's way, rather than by position"
else say ok "direct[IO] refuses with a position instead of inventing a name"; fi

# ── 11. THE TWO DEGENERATE BLOCKS, both found by asking rather than by a corpus case — the corpus
# has neither shape, so nothing else in this repository would have said a word.
#
# A TRAILING BIND. `direct[M] { x = e }` binds with an empty remainder in the reference, whose
# `directStmts` matches `assign` FIRST in any position. v3's parser has already moved that trailing
# statement out of `stmts` and into `result`, so a client that only reads `stmts` hands the raw
# assignment to the lowering and the author is told `assignment to unknown name 'x'` — blamed for an
# assignment they did not write.
#
# AN EMPTY BLOCK. `direct[M] { }` reaches v3's own front as `Block(Nil, None)` and the projection as
# NO ARGUMENT, because it drops an empty block entirely. Answering unit for the first would refuse
# the second, so the two fronts would disagree about a program legal on neither. Both refuse.
printf 'val a = direct[Option] {\n  x = Some(1)\n}\nprintln(a)\n' > "$TMP/tail.ssc"
printf 'val b = direct[Option] {\n}\n' > "$TMP/empty.ssc"
tgot="$(timeout 60 $SSC3 run "$TMP/tail.ssc" 2>"$TMP/e11")"; trc=$?
if [ $trc -ne 0 ] || [ "$tgot" != "()" ]; then
  red "direct: a trailing bind gave rc=$trc [$tgot], wanted () — $(grep -m1 '^ssc3:' "$TMP/e11" | cut -c1-100)"
elif grep -q "assignment to unknown name" "$TMP/e11"; then
  red "direct: a trailing bind reached the lowering as a bare assignment"
else say ok "direct: a trailing bind binds with an empty remainder, as the reference does"; fi

eu="$(timeout 60 $SSC3 run "$TMP/empty.ssc" 2>&1 >/dev/null)"
ev="$(SSC3_FRONT=v3 timeout 60 $SSC3 run "$TMP/empty.ssc" 2>&1 >/dev/null)"
if [ "$eu" != "$ev" ]; then
  red "direct: an empty block differs by FRONT
      uniml: $(printf '%s' "$eu" | tr -d '\n' | cut -c1-110)
      v3   : $(printf '%s' "$ev" | tr -d '\n' | cut -c1-110)"
elif ! grep -qE ':[0-9]+:[0-9]+: direct\[Option\] \{ \} has an empty block' <<<"$eu"; then
  red "direct: an empty block is not refused with a position — [$(cut -c1-110 <<<"$eu")]"
else say ok "direct: an empty block refuses identically on both fronts"; fi

# ── 12. `Prism[S, C]` — the second client, on BOTH lanes, plus the two ways to name it wrongly.
# A prism is named ENTIRELY by its type arguments, so this is also the only client whose whole input
# is the fourth spelling checked above. The emitted value is a `PrismOptic` from the prelude holding
# a type-ascription match; what this asserts is the behaviour a person would get from writing that
# match by hand — `Some` on the variant, the value UNCHANGED on a miss, and `reverseGet` putting a
# variant back.
P="$TMP/prism.ssc"
printf 'enum Shape:\n  case Circle(radius: Int)\n  case Rect(width: Int, height: Int)\n\nval c: Shape = Circle(5)\nval r: Shape = Rect(3, 4)\nval p = Prism[Shape, Circle]\nprintln(p.getOption(c))\nprintln(p.getOption(r))\nprintln(p.modify(r, x => Circle(9)))\nprintln(p.reverseGet(Circle(7)))\n' > "$P"
want='Some(Circle(5))
None
Rect(3, 4)
Circle(7)'
pgot="$(timeout 60 $SSC3 run "$P" 2>"$TMP/e12")"; prc=$?
pvia="$(timeout 180 $SSC3 run --bridge "$P" 2>"$TMP/e12b")"; pbrc=$?
if [ $prc -ne 0 ] || [ "$pgot" != "$want" ]; then
  red "prism: executor rc=$prc [$(printf '%s' "$pgot" | tr '\n' '/')] — $(grep -m1 '^ssc3:' "$TMP/e12" | cut -c1-100)"
elif [ $pbrc -ne 0 ] || [ "$pvia" != "$want" ]; then
  red "prism: bridge rc=$pbrc [$(printf '%s' "$pvia" | tr '\n' '/')] — $(grep -m1 '^ssc3:' "$TMP/e12b" | cut -c1-100)"
else say ok "prism: getOption hits and misses, modify is a no-op on a miss, reverseGet wraps — both lanes"; fi

# NAMED BY ITS TYPES MEANS BOTH OF THEM, and guessing here would pick a variant for the author.
for n in 1 3; do
  case $n in
    1) printf 'val q = Prism[Shape]\n' > "$TMP/pn.ssc" ;;
    3) printf 'val q = Prism[A, B, C]\n' > "$TMP/pn.ssc" ;;
  esac
  perr="$(timeout 60 $SSC3 run "$TMP/pn.ssc" 2>&1 >/dev/null)"; prc=$?
  if [ $prc -eq 0 ]; then red "prism: $n type argument(s) was accepted"
  elif ! grep -qE ':[0-9]+:[0-9]+: Prism needs exactly two type arguments' <<<"$perr"; then
    red "prism: $n type argument(s) refused in the wrong shape — [$(cut -c1-110 <<<"$perr")]"
  else say ok "prism: $n type argument(s) refuses with a position and says how many it saw"; fi
done

# HYGIENE, rule 4: the binders come from `Ctx.fresh`, so a user name in the same scope cannot be
# captured by the lambda the rewrite emits. Written so that capture would answer a different number.
printf 'enum Shape:\n  case Circle(radius: Int)\n\nval s: Shape = Circle(5)\nval x = 7\nval p = Prism[Shape, Circle]\nprintln(p.modify(s, c => Circle(c.radius + x)))\n' > "$TMP/pcap.ssc"
cgot="$(timeout 60 $SSC3 run "$TMP/pcap.ssc" 2>"$TMP/e12c")"
if [ "$cgot" != "Circle(12)" ]; then
  red "prism: a free name in scope was captured — got [$cgot], wanted Circle(12) — $(grep -m1 '^ssc3:' "$TMP/e12c" | cut -c1-90)"
else say ok "prism: a user name x in the same scope survives the rewrite's own binders"; fi

if [ $fails -gt 0 ]; then echo "rewrite-gate: FAIL ($fails)"; exit 1; fi
echo "rewrite-gate: OK"
