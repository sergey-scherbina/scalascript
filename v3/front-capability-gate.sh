#!/usr/bin/env bash
#
# The two fronts must ACCEPT AND REFUSE THE SAME PROGRAMS.
#
# WHY THIS IS NOT front-diff.sh. That gate compares the two fronts' AST OUTPUT, and `Front.scala`
# explains why: they agree on every fixture, so no fixture's output can distinguish them. Sound, and
# it leaves a hole exactly one shape wide — **a program one front REFUSES and the other RUNS produces
# no output to compare**. Capability divergence is invisible to an output differential by
# construction, and this gate is the axis that differential cannot see.
#
# HOW IT WENT UNNOTICED FOR A DAY. `Front.default` picks UniML whenever it is registered, which
# depends on the WORKING TREE — UniML needs `uniml-classpath.sh`, so a worktree without it runs v3's
# own front while the shared checkout runs UniML. Algebraic effects were built and verified in a
# worktree, where they work; on the shared checkout the same file reports
# `` `effect` is outside SSC3 core Tier 0 ``, UniML's refusal. Both fronts covered 30 of 36 corpus
# files, so the COUNT matched and the SETS did not. A number that is true of both and describes
# neither is worse than a red gate. (BUGS.md v3-two-fronts-differ-in-CAPABILITY.)
#
# WHAT IT CHECKS: `ssc3 ast <file> <front>` for every corpus file on both fronts, comparing only
# whether each ACCEPTED. `ast` is the right instrument because it stops at the front — a difference
# it reports cannot be blamed on the lowering or the executor.
#
# Usage: v3/front-capability-gate.sh
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2
SSC3="v3/ssc3"

# KNOWN divergences, declared so this gate is green today and FAILS THE DAY ONE CLOSES. A declared
# gap that quietly becomes a closed one is a permanent exemption for a fixed bug, which this
# repository has shipped before and written down.
# `effect-oneshot` was here until 2026-08-08 and came out the same day the projection landed —
# which is the gate doing its job: it went red saying "no longer diverges; drop it from KNOWN_v3 in
# this commit", and this is that commit.
# `usingp` and `summon2` were DECLARED for ONE DAY, with SSC3-G2 stage 2a, and came out with U1:
# UniML's dialect now keeps a definition's type parameters and a `using` parameter's type
# ARGUMENTS, so the projection resolves what v3's own parser resolves. What the declaration bought
# was a day in which the gap was visible and named instead of being a silent difference between the
# front a user gets and the front a test ran.
declare -a KNOWN_V3_ONLY=()                     # v3 accepts, uniml refuses
# `absval` was here for one commit. The projection accepted a trait's non-`def` member and dropped it
# SILENTLY; it now refuses, which is not a new decision — `20-core-language.md` and UniFront's own
# `AbstractVal` case already said v3's traits carry methods, not abstract state. This gate went red
# the moment that landed, which is the behaviour a declaration list is for.
# `type-lambda-native` was the last entry here and came out on 2026-08-09 with SSC3-7i, which is
# this gate doing its job twice over. It went red saying "no longer diverges; drop it in this
# commit", and it is also what identified the row as CLOSEABLE: the sprint had `[A] =>> …` filed
# behind the generics wall and gated on the type-checker decision, while this list recorded that
# UniML ALREADY accepted the file. A construct one front takes at Tier 0 is expressible at Tier 0,
# so what was missing was v3's parser, not a checker — three lines in `skipType`.
#
# BOTH LISTS WERE EMPTY for the length of one commit — over the corpus and the probe set the two
# fronts accepted and refused exactly the same programs — and `KNOWN_V3_ONLY` filled again the same
# day with `usingp` and `summon2`, for the reason written against it above. Stating it here rather
# than deleting the paragraph, because "was empty once" is the fact that makes the current two
# entries a debt with a date on it instead of the normal condition.
declare -a KNOWN_UNIML_ONLY=()                          # uniml accepts, v3 refuses

available="$($SSC3 front 2>/dev/null | sed -n 's/^available: //p')"
case "$available" in
  *uniml*)
    ;;
  *)
    # NOT a silent pass. One front cannot be compared with itself, and a gate that says nothing when
    # it cannot run is the failure mode this whole entry is about.
    echo "front-capability-gate: CANNOT RUN — only these fronts are registered: ${available:-none}"
    echo "  UniML needs its classpath; run v3/uniml-classpath.sh in this tree."
    if [ "${CI:-}" = "true" ]; then exit 1; fi
    exit 0
    ;;
esac

classify() { # $1 newline-separated paths -> "<v3><uniml> <name>" per line
  printf '%s\n' "$1" | SSC3="$SSC3" xargs -P "${SSC3_GATE_JOBS:-8}" -I{} sh -c '
    f="$1"; a=0; u=0
    timeout 180 "$SSC3" ast "$f" v3    >/dev/null 2>&1 && a=1
    timeout 180 "$SSC3" ast "$f" uniml >/dev/null 2>&1 && u=1
    printf "%s%s %s\n" "$a" "$u" "$(basename "$f" .ssc)"
  ' _ {}
}

# `accepts()` lived here and is gone: `classify` above asks both fronts in one pass, in parallel,
# and a second way to spell the same question is a second thing to keep in step with the driver.

fails=0
v3_only=""
uniml_only=""
checked=0

# TWO SOURCES, and the second is the one that found anything. The corpus is 36 real programs and
# exercises the constructs those programs happen to use; a front gap in something none of them
# writes is invisible to it. `v3/tests/front-capability/` is one small file per construct the
# PROJECTION explicitly refuses (`UniFront.scala`'s `no(...)` list) — the fronts' own statement of
# what they do not do, turned into something that can disagree.
#
# Measured when the probes were added: 14 constructs, 13 agree, and the one that did not —
# an abstract `val` in a trait — is invisible to the corpus entirely.
sec1_out="$(classify "$(ls bench/corpus/*.ssc v3/tests/front-capability/*.ssc 2>/dev/null)")"
checked="$(printf '%s\n' "$sec1_out" | grep -c .)"
v3_only="$(printf '%s\n' "$sec1_out" | awk '$1=="10"{print $2}' | sort | tr '\n' ' ')"
uniml_only="$(printf '%s\n' "$sec1_out" | awk '$1=="01"{print $2}' | sort | tr '\n' ' ')"

# `declared X actual` both ways: a divergence that appears is a regression, and a declared one that
# disappears means the list is stale and must shrink in the same commit that closed it.
check_set() { # $1 label, $2 declared (space list), $3 actual (space list)
  local label="$1" declared="$2" actual="$3" x
  for x in $actual; do
    case " $declared " in
      *" $x "*) printf '  KNOWN  %-24s accepted only by %s (declared)\n' "$x" "$label" ;;
      *) printf '  FAIL   %-24s NEW divergence — accepted only by %s\n' "$x" "$label"; fails=$((fails + 1)) ;;
    esac
  done
  for x in $declared; do
    case " $actual " in
      *" $x "*) ;;
      *) printf '  FAIL   %-24s no longer diverges; drop it from KNOWN_%s in this commit\n' "$x" "$label"
         fails=$((fails + 1)) ;;
    esac
  done
}

echo "── front capability: $checked programs (corpus + probes), both fronts ──────"
check_set "v3"    "${KNOWN_V3_ONLY[*]}"    "$v3_only"
check_set "uniml" "${KNOWN_UNIML_ONLY[*]}" "$uniml_only"


# ── SECTION TWO: the conformance corpus ───────────────────────────────────────────────────────────
#
# A SECOND SECTION RATHER THAN MORE FILES IN THE FIRST, and the reason is the claim above. Section
# one says the two fronts accept and refuse EXACTLY the same programs, with both lists empty, and
# that statement is true of its 50 programs. Pouring 398 more in would replace a strong empty-list
# claim with a weak 85-row one and lose the first for good. Two scopes, two claims, each falsifiable
# on its own.
#
# It also removes an ambiguity the merged form would have introduced: `map-ops` and
# `mutual-recursion` exist in BOTH `bench/corpus` and `tests/conformance`, so a list keyed on the
# basename could not say WHICH one diverges.
#
# WHY THE CONFORMANCE CORPUS AT ALL. `v3-two-fronts-differ-in-CAPABILITY` asked for exactly this and
# named the reason an output differential cannot supply it: a program one front refuses and the
# other runs produces no output to compare, so capability is invisible to `front-diff.sh` by
# construction. Measured 2026-08-10, twice on two trees with the same result:
#
#     both fronts accept   277
#     only v3              13        <- UniML's projection refuses these
#     only uniml           72        <- v3's own parser refuses these
#     NEITHER accepts      36        <- not a front divergence: a gap both share
#
# The 38 are reported and NOT guarded here. They are a Tier-0 language gap, which the corpus report's
# UNSUPPORTED bucket already counts and `N` already floors; counting them as a front divergence
# would hide a real capability regression behind a number that moves for unrelated reasons. That
# distinction is the whole point of splitting the count — a single "one front only" number of 123
# conflates a capability gap with a shared gap, and I published that conflated number this morning
# before measuring the split.
#
# LISTS, NOT A CEILING. A count lets one divergence close while another opens and reports nothing;
# the bidirectional `check_set` above cannot — a row that stops diverging must come OUT of the list
# in the same commit, which is what caught `type-lambda-native` as closeable.
# `tagless-program` and `tagless-resolution` came OUT on 2026-08-09 with SSC3-U1 and stage 2b:
# UniML's dialect now keeps a definition's type parameters and every parameter's type ARGUMENTS,
# so the projection resolves the instances v3's own front resolves and both fronts accept them.
# The gate demanded this removal in the same commit, which is what these lists are for.
declare -a KNOWN_CONF_V3_ONLY=(
  content
  direct-syntax
  enum-shared-casename
  lenses
  markdown-html
  optic-polish
  optics-index-at
  optional
  prisms
  tagless-direct-syntax
  traversal
)

# THREE ENTRIES ADDED 2026-08-10 WITH `extension`, AND NONE OF THEM IS ABOUT EXTENSIONS.
# Accepting `extension` (v3-extension-type-params) let the UniML front walk PAST a construct both
# fronts used to stop at, into later parts of those files that v3's parser has never handled. The
# files were refused before and are refused now; what changed is WHERE, and only on one front.
# Each row names the gap that actually blocks it, so closing that gap deletes the row — and this
# gate fails the day a declared divergence closes, which is what stops the list becoming a
# graveyard.
#   actors-bounded-mailbox   std/actors.ssc:558 — a non-`def` member in an `object`
#   actors-process-info      the same file, the same line
#   indent-block-statements  tests/conformance/indent-block-statements.ssc:113 — an expression
#                            form v3's parser does not have
# FOUR ROWS CAME OUT 2026-08-15 — `std-ui-i18n`, `tkv2-component`, `tkv2-offline`, `tkv2-webauthn`.
# They were not four separate closures: `ast` compares `Loader.closure`, which FOLLOWS IMPORTS, and
# all four reach `std/ui/primitives.ssc`, which declares three `opaque type`s. v3's own parser
# refused that file and UniML's accepted it, so the divergence the gate saw was in an import, not in
# the case. `28c34951e` taught BOTH fronts `opaque type` and closed all four at once.
#
# The row-per-file shape is what made this legible: a COUNT would have fallen by four and said
# nothing about one cause. The check that the story is not larger than the evidence — no other
# corpus case reaches `primitives.ssc`, so the set that stopped diverging is exactly the set that
# imported it, with nothing left over.
#
# It also arrived here as a RED CI JOB rather than as a removal in `28c34951e`, which is what this
# list is designed to do — but the gate names the row and not the reason, so recovering "why" cost a
# separate investigation. If you close a divergence, take its row out in the same commit.
declare -a KNOWN_CONF_UNIML_ONLY=(
  actors-bounded-mailbox actors-process-info indent-block-statements
  actors-cluster-coordinator
  actors-cluster-visibility
  actors-global-registry
  coroutine-native-lifecycle
  curried-def-clauses
  dataset-agg
  distributed-callback-user-throw
  effects-handler
  fewer-braces-colon
  for-comprehensions
  for-yield-layout
  fs-confined
  generator-callback-user-throw
  json-deep-import
  json-self-hosted-import
  litdoc
  literal-pattern-in-case-lambda
  mcp-client-invoke
  mcp-server-resource
  mcp-server-tool
  mcp-types
  named-arg-defaults
  parameterless-def-mention
  predef-notimplemented
  scljet-address-write
  scljet-byte-codec
  scljet-journal-recover
  scljet-pager-mutate
  scljet-readonly-pager-btree
  std-fs-failure
  std-fs-failure-raises
  std-ui-native-css-scope
  std-ui-native-css-scope-lib
  std-ui-native-pair-lib
  std-ui-native-pair-minimal
  string-eq-locals
  tkv2-busi-home
  tkv2-button-size
  tkv2-button-variant
  tkv2-forms
  tkv2-hstack-wrap
  tkv2-keyed-for
  tkv2-pwa
  tkv2-raw-html
  tkv2-select
  tkv2-select-reactive
  tkv2-textfield-reactive-label
  tkv2-tri-state
  try-catch-exception-delivery
  try-catch-io-failure
  type-ascription
  type-ascription-list
  type-ascription-map
  type-ascription-option
  type-ascription-set
  type-ascription-tuple
  unit-literal-pattern
  v2-db-url-scheme-not-jdbc
  v2-multiline-list-literal
  v2-native-result-unregistered-field
  v2-self-hosted-parser-fuzz
  v2-self-hosted-yaml-core
  v2js-unit-pattern
  webauthn-server-verify
)

# PARALLEL, because the serial form is ten minutes. 398 files times two fronts at ~0.7 s each is
# the cost of the whole `gates` job again, on every push that touches `v3/`. A gate that makes
# pushing expensive is a gate people route around.

if [ "${SSC3_CAP_CONFORMANCE:-1}" = 1 ] && [ -d tests/conformance ]; then
  conf_out="$(classify "$(ls tests/conformance/*.ssc)")"
  conf_both="$(printf '%s\n' "$conf_out" | grep -c '^11 ')"
  conf_neither="$(printf '%s\n' "$conf_out" | grep -c '^00 ')"
  conf_v3_only="$(printf '%s\n' "$conf_out" | awk '$1=="10"{print $2}' | sort | tr '\n' ' ')"
  conf_uniml_only="$(printf '%s\n' "$conf_out" | awk '$1=="01"{print $2}' | sort | tr '\n' ' ')"
  conf_n="$(printf '%s\n' "$conf_out" | grep -c .)"

  echo
  echo "── the conformance corpus: $conf_n programs, both fronts ──────────────────"
  echo "  both accept: $conf_both   only v3: $(echo $conf_v3_only | wc -w | tr -d ' ')   only uniml: $(echo $conf_uniml_only | wc -w | tr -d ' ')   NEITHER: $conf_neither (a shared gap, not a divergence)"
  check_set "v3"    "${KNOWN_CONF_V3_ONLY[*]}"    "$conf_v3_only"
  check_set "uniml" "${KNOWN_CONF_UNIML_ONLY[*]}" "$conf_uniml_only"
fi

if [ $fails -ne 0 ]; then
  echo "front-capability-gate: FAIL ($fails)" >&2
  exit 1
fi
echo "front-capability-gate: OK (the two fronts differ on exactly the declared rows)"
