#!/usr/bin/env bash
#
# js-capability-triggers-gate — an intrinsic whose implementation lives in a gated runtime CHUNK
# must be REACHABLE: some trigger, directly or through the capability implications, has to pull that
# chunk in when a program uses the name.
#
# THE FAILURE IS SILENT AT COMPILE TIME. JsGen picks runtime chunks by scanning the program text for
# names. A name in the intrinsic table but in no trigger emits its CALL and none of its DEFINITION:
#
#     const ctx = tls("server.crt", "server.key");     // …and no `function tls` anywhere
#     ReferenceError: tls is not defined
#
# Nothing catches that but running a program that uses the name. MEASURED 2026-08-02: 13 of the 21
# WsServer intrinsics had no trigger; `hasJwt` tested `JwtSign(` / `JwtVerify(` with a capital J
# against intrinsics named `jwtSign` / `jwtVerify`, two conditions no source could ever match; and
# `hashPassword` / `verifyPassword` / `cookieConfig` / `onWebSocket` had none at all.
#
# ── WHY "REACHABLE" AND NOT "HAS ITS OWN TRIGGER" ────────────────────────────────────────────────
#
# Capabilities imply one another, and JsGen says so in two lines:
#
#     if hasHtmlDsl then { caps += HtmlDsl; caps += Jwt }
#     if hasGraphql then { caps += Graphql; caps += HtmlDsl; caps += Jwt; caps += WsServer; caps += Async }
#
# so a jwt-auth name can arrive through an HtmlDsl trigger and needs no `hasJwt` entry of its own.
# The first version of this check ignored that and compared list against list; against real emitted
# programs it was WRONG ON 3 OF 5 SAMPLES — it called all eleven jwt-auth names untriggered when
# only the `jwt*` ones were, because `csrfToken` is covered by the substring trigger `csrf` and
# others arrive transitively. A gate with that false-positive rate gets switched off, so the
# closure is modelled instead of ignored.
#
# ── EVERYTHING IS DERIVED FROM JsGen, NOTHING RESTATED ───────────────────────────────────────────
#
#   chunk file    <- `val JsRuntimeX = JsRuntimeResource.load("y.mjs")`
#   chunk gate    <- `if caps.contains(Capability.C) then sb.append(JsRuntimeX)`
#   triggers      <- `val hasC = allText.contains("a(") || …`
#   implications  <- `if hasC then { caps += D; caps += E }`
#
# A hand-written copy here would be the third copy of a thing whose two existing copies already
# drifted — which is the bug this file exists for. If an extraction stops matching, the gate FAILS
# rather than passing over an empty set.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export ROOT
echo "── js capability triggers (all gated chunks, transitive)"

python3 - <<'PY'
import os, re, pathlib, sys

root  = pathlib.Path(os.environ["ROOT"])
cg    = root / "v1/runtime/backend/js/src/main/scala/scalascript/codegen"
jsgen = (cg / "JsGen.scala").read_text()
rt    = root / "v1/runtime/backend/js/src/main/resources/scalascript/js-runtime"
problems, notes = [], []
exempt_candidates = set()

# A chunk is either loaded from a .mjs resource or written inline as a Scala string literal.
# Only the first form was handled at first, so JsRuntimeWebAuthn — an inline literal — resolved to
# no file and was SKIPPED with a note. A gate that skips is a gate that misses, which is the exact
# defect class this file exists to catch, so both forms are read here.
chunk_text = {}
for p in cg.glob("*.scala"):
    src = p.read_text()
    for m in re.finditer(r'val\s+(JsRuntime\w+)[^=]*=\s*JsRuntimeResource\.load\("([^"]+)"\)', src):
        f = rt / m.group(2)
        if f.exists(): chunk_text[m.group(1)] = (m.group(2), f.read_text())
    for m in re.finditer(r'val\s+(JsRuntime\w+)\s*:\s*String\s*=\s*"""(.*?)"""', src, re.S):
        chunk_text.setdefault(m.group(1), (p.name, m.group(2)))
chunk_file = {k: v[0] for k, v in chunk_text.items()}

gated = {}
for m in re.finditer(r'if caps\.contains\(Capability\.(\w+)\) then\s*\n\s*sb\.append\((JsRuntime\w+)\)', jsgen):
    gated.setdefault(m.group(1), set()).add(m.group(2))

triggers = {}
for m in re.finditer(r'val has(\w+)\s*=(.*?)(?=\n\s*(?:if |val |\Z))', jsgen, re.S):
    pats = set(re.findall(r'allText\.contains\("([^"]+)"\)', m.group(2)))
    if pats: triggers[m.group(1)] = pats

implies = {}
for m in re.finditer(r'if has(\w+) then \{([^}]*)\}', jsgen):
    implies[m.group(1)] = set(re.findall(r'caps \+= (\w+)', m.group(2)))
for m in re.finditer(r'if has(\w+) then caps \+= (\w+)', jsgen):
    implies.setdefault(m.group(1), set()).add(m.group(2))

# A vacuous run is the failure mode this file is about: if an extraction breaks, "nothing to check,
# all fine" would print green forever.
if len(chunk_file) < 8 or len(gated) < 6 or len(triggers) < 6 or len(implies) < 6:
    print(f"✗ extraction broke — {len(chunk_file)} chunk files, {len(gated)} gated chunks, "
          f"{len(triggers)} trigger sets, {len(implies)} implication rules")
    print("    A near-empty expected set makes every assertion vacuous, so this is a failure.")
    sys.exit(1)
print(f"✓ derived from JsGen: {len(gated)} gated chunks, {len(triggers)} trigger sets, "
      f"{len(implies)} implication rules")

# SOURCE name -> RUNTIME target. These are NOT the same name: 48 of the pairs differ
# (`webauthnChallenge -> _webauthnChallenge`, `paymentRequestShow -> _pr_show`). The triggers scan
# for what a PROGRAM writes, i.e. the QualifiedName, so probing with the runtime target reports
# every renamed intrinsic as broken — the first run of this version did exactly that and produced
# 28 findings, of which the `_pr_*` and `_Payment*` ones were pure artefact.
target_of = {}
for p in (cg / "intrinsics").glob("*.scala"):
    for m in re.finditer(r'QualifiedName\("([^"]+)"\)\s*->\s*RuntimeCall\("([^"]+)"\)', p.read_text()):
        target_of.setdefault(m.group(2), set()).add(m.group(1))
intr = set(target_of)

def produced_by(trig):
    """Capabilities a program gets when `has<trig>` fires, following the implications."""
    out, stack = set(), [trig]
    while stack:
        c = stack.pop()
        for d in implies.get(c, {c}):
            if d not in out:
                out.add(d); stack.append(d)
    return out or {trig}

closure = {t: produced_by(t) for t in triggers}
checked = 0
for cap, chunks in sorted(gated.items()):
    for ch in sorted(chunks):
        if ch not in chunk_text:
            print(f"✗ {ch} is gated by Capability.{cap} but its source could not be read.")
            print("    Skipping it would make this gate blind to that whole chunk — failing instead.")
            sys.exit(1)
        fn, text = chunk_text[ch]
        defined = set(re.findall(r'^function ([A-Za-z_]\w*)', text, re.M))
        for name in sorted(defined & intr):
            checked += 1
            # A program calling only `name(` fires trigger T when some pattern of T is a substring
            # of `name(` — that is exactly how `allText.contains` behaves, and it is why the
            # substring trigger `csrf` covers `csrfToken`. The chunk arrives if T's closure holds
            # the capability gating it.
            # Reachable if ANY source name that routes here has a trigger whose closure holds
            # this capability. One runtime function can back several source names.
            sources = sorted(target_of.get(name, {name}))
            if not any(any(pat in (src + "(") for pat in pats) and cap in closure[t]
                       for src in sources
                       for t, pats in triggers.items()):
                shown = ", ".join(sources[:3])
                exempt_candidates.add((shown, cap))
                problems.append(f"{shown}  -> {name} (in {fn}, gated by {cap})")

# ── EXEMPTIONS THAT HAVE TO EARN THEIR PLACE ─────────────────────────────────────────────────────
# Two names cannot get the obvious `allText.contains("name(")` trigger, because `contains` is a
# plain SUBSTRING test and these substrings occur in unrelated code. Measured over the repo's .ssc
# files: `use(` also matches `because(`-shaped words and pulled http-server.mjs into the etcd and
# consul cluster drivers; `.complete(` is ordinary stream/promise vocabulary and pulled payment.mjs
# into streams.ssc. Both are reachable in practice only together with a companion call that does
# trigger — middleware needs a server, and a PaymentResponse can only come from a payment request.
#
# An exemption list rots into a dumping ground unless it is checked, so each entry must PROVE it is
# needed: the naive trigger has to be genuinely expensive. If someone exempts a name whose trigger
# would in fact be free, this fails and tells them to just add the trigger. And if a name stops
# being a problem, its stale exemption fails too.
EXEMPT = {
    "use":                      ("use(",       "middleware; only meaningful alongside serve(/route("),
    "PaymentResponse.complete": (".complete(", "the response can only come from a payment request"),
}

# `.worktrees` excludes a NESTED worktree checked out under this one — the same corpus would
# otherwise be counted once per sibling worktree, corrupting both this count and `would_cost`
# below (which measures how many corpus files would gain a capability, and a duplicated corpus
# answers that question wrong). RELATIVE to root, not the absolute path: this script's OWN root is
# often itself `.../.worktrees/<slug>` — this repo's own documented per-task workflow — and the
# absolute-path form matched that too, excluding the entire real corpus and reporting 0 files on
# every ordinary worktree run, never just the genuinely nested case it was written for.
ssc = [q for q in root.rglob("*.ssc") if ".worktrees" not in str(q.relative_to(root))]
if len(ssc) < 200:
    print(f"✗ only {len(ssc)} .ssc files found — too few to justify any exemption")
    sys.exit(1)

def would_cost(pat, cap):
    """Files that would newly gain `cap` if `pat` were added as a trigger for it."""
    n = 0
    for q in ssc:
        txt = q.read_text(errors="ignore")
        if pat not in txt: continue
        if not any(cap in closure[t] and any(x in txt for x in pats)
                   for t, pats in triggers.items()):
            n += 1
    return n

kept = []
for src, cap in list(exempt_candidates):
    if src not in EXEMPT: continue
    pat, why = EXEMPT[src]
    cost = would_cost(pat, cap)
    if cost == 0:
        problems.append(f"{src}: exempted, but `{pat}` would pull {cap} into NO extra file — "
                        f"the trigger is free, add it to JsGen instead of exempting")
    else:
        kept.append(f"{src} — `{pat}` would over-include {cap} in {cost} unrelated file(s); {why}")
        problems[:] = [x for x in problems if not x.startswith(src + " ")]

for src in EXEMPT:
    if src not in {s for s, _ in exempt_candidates}:
        problems.append(f"{src}: listed as exempt but is now reachable — drop the stale exemption")

print(f"✓ checked {checked} intrinsics across {len(gated)} gated chunks")
for k in kept: print(f"  exempt: {k}")
for n in notes: print(f"  note: {n}")
if problems:
    print(f"✗ {len(problems)} emit a call that no trigger can define:")
    for x in problems: print(f"      {x}")
    print('    Add `allText.contains("<name>(")` to that capability\'s trigger in JsGen.scala.')
    print("    Over-including a chunk costs bundle bytes; under-including it ships a ReferenceError.")
    sys.exit(1)
print("✓ every intrinsic in a gated chunk is reachable by some trigger")
PY
rc=$?

echo
[ "$rc" -eq 0 ] && { echo "✓ js capability triggers gate PASSED"; exit 0; }
echo "✗ js capability triggers gate FAILED"; exit 1
