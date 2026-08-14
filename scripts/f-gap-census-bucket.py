#!/usr/bin/env python3
"""Bucket an `ssc info --front-report` census by REASON.

The reason string embeds the offending NAME, so raw strings scatter into
singletons — `(global Parser)`, `(global _)`, `(global doc)` are one shape and
three buckets. Normalise the name out to see the mechanism, and keep the names
as the bucket's members so nothing is hidden by the grouping.
"""
import re, sys, collections

raw = sys.argv[1] if len(sys.argv) > 1 else "census-raw.tsv"

rows = []
for line in open(raw):
    line = line.rstrip("\n")
    if not line:
        continue
    parts = line.split("\t")
    if len(parts) < 2:
        continue
    path, verdict = parts[0], parts[1]
    reason = parts[2] if len(parts) > 2 else ""
    rows.append((path, verdict, reason))

verdicts = collections.Counter(v for _, v, _ in rows)
print(f"subjects: {len(rows)}")
for v, n in verdicts.most_common():
    print(f"  {v:14s} {n}")
print()


def shape(reason):
    """Collapse the variable part of a diagnostic to its mechanism."""
    r = reason.split("||REF:")[0].strip()
    r = re.sub(r"\(global [^)]*\)", "(global X)", r)
    r = re.sub(r"no arm for \S+", "no arm for T/N", r)
    r = re.sub(r"arity: \d+ expected, \d+ given", "arity: N expected, M given", r)
    r = re.sub(r"'[^']*'", "'X'", r)
    r = re.sub(r'"[^"]*"', '"X"', r)
    r = re.sub(r"\b\d+\b", "N", r)
    return r[:110]


def name_of(reason):
    m = re.search(r"\(global ([^)]*)\)", reason)
    return m.group(1) if m else ""


for want in ("GAP", "BOTH-UNBOUND", "ERROR"):
    sel = [(p, r) for p, v, r in rows if v == want]
    if not sel:
        continue
    print(f"=== {want}: {len(sel)} files ===")
    buckets = collections.defaultdict(list)
    for p, r in sel:
        buckets[shape(r)].append((p, name_of(r)))
    for sh, members in sorted(buckets.items(), key=lambda kv: -len(kv[1])):
        names = collections.Counter(n for _, n in members if n)
        std = sum(1 for p, _ in members if p.startswith("std/"))
        print(f"\n{len(members):4d}  {sh}")
        print(f"      std modules: {std}   examples/conformance: {len(members) - std}")
        if names:
            top = ", ".join(f"{n}×{c}" if c > 1 else n for n, c in names.most_common(8))
            print(f"      names: {top}")
        for p, _ in sorted(members)[:6]:
            print(f"        {p}")
        if len(members) > 6:
            print(f"        … {len(members) - 6} more")
    print()
