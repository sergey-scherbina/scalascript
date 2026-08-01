#!/usr/bin/env python3
"""bugs-migrate-status — give the pre-schema entries the header their own heading already claims.

    scripts/bugs-migrate-status.py            # dry run: print what would change, touch nothing
    scripts/bugs-migrate-status.py --apply

THE POPULATION. `scripts/bugs-report` reports ~118 entries whose HEADING says fixed while their
header says `unknown`. They pre-date the header schema and were never migrated, so the sanctioned
query — and `AGENTS.md` bans the alternative — was low by 118 on `fixed` and its `unknown` bucket
was 86 % noise. `BUGS.md bugs-headers-were-never-migrated-from-the-prose` has the measurement.

WHY A SCRIPT AND NOT 118 EDITS. Every agent that fixes something appends to a BUGS.md, so a sweep
across eight of them is guaranteed to collide with somebody. A rebase conflict in a hand-made
118-entry diff is unresolvable in practice; re-running a script on the rebased tree is trivial.
The migration is therefore idempotent and re-runnable: it selects on the CURRENT state, so an entry
a sibling already migrated is simply not in the set the second time.

WHAT `fixed-in` GETS: `unrecorded`, for all of them. `specs/bugs-index.md` sanctions that sentinel
for exactly this population — "the many older entries whose prose says a defect was fixed but never
names the commit" — and it is the only value that is true for every entry in the set.

SCRAPING A SHA OUT OF THE PROSE WAS TRIED FIRST AND IS WRONG. Two findings killed it, both from
reading the dry run instead of trusting it:

  1. A window of the entry's next N lines runs into the NEXT entry. `parser-trysplitparse-
     quadratic-hang` has no sha of its own and was handed `f2afd3378` from the neighbour below it.
  2. Worse, and fatal to the whole idea: a sha in an entry's prose need not be its FIX. In
     `rust-index-read-moves-noncopy` that same `f2afd3378` is the commit that CAUSED the bug —
     "the bug only surfaced once `f2afd3378` made `.split`/`.toList` results indexable".

So position cannot distinguish a fix sha from a cause sha, a neighbour's sha, or a mention. A
recorded `fixed-in` that names the wrong commit looks authoritative and is worse than a sentinel
that says "fixed, provenance missing" — which is what these entries honestly are. `git log -S<slug>`
fails the same way: it finds the commit that EDITED THE ENTRY, not the one that fixed the defect.
Whoever actually knows a fix sha can add it; nothing here guesses one.
"""
import argparse, pathlib, re, sys

HEADER = re.compile(r"(<!--)(.*?)(-->)", re.S)


def bug_files(root):
    """Every BUGS.md once. `runtime` is a symlink to `v1/runtime`; a naive glob counts three files
    twice, which is a mistake `bugs-report` documents having made about itself."""
    seen, out = set(), []
    for pat in ("BUGS.md", "*/BUGS.md", "*/*/BUGS.md", "*/*/*/BUGS.md", "*/*/*/*/BUGS.md"):
        for p in sorted(root.glob(pat)):
            if any(part in {"target", "node_modules", ".git", ".scala-build", ".agents"} for part in p.parts):
                continue
            rel = p.relative_to(root)
            if any((root / pathlib.Path(*rel.parts[: i + 1])).is_symlink() for i in range(len(rel.parts) - 1)):
                continue
            if p.resolve() in seen:
                continue
            seen.add(p.resolve())
            out.append(p)
    return out



def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true", help="write the files (default is a dry run)")
    ap.add_argument("--root", default=".")
    a = ap.parse_args()
    root = pathlib.Path(a.root).resolve()

    changed = sentinels = 0
    for f in bug_files(root):
        text = f.read_text()
        out, i, lines = [], 0, text.split("\n")
        while i < len(lines):
            line = lines[i]
            out.append(line)
            if not line.startswith("## "):
                i += 1
                continue
            head = line[3:].strip()
            # The same claim test `bugs-report`'s status_drift uses, including the `(?!-)` that keeps
            # the FIELD NAME in a slug like `…-fixed-in-checks-…` from counting as a claim.
            claims_fixed = re.search(r"\bfixed\b(?!-)", head, re.I)
            blob = "\n".join(lines[i + 1: i + 14])
            m = HEADER.search(blob)
            if not (claims_fixed and m):
                i += 1
                continue
            body = m.group(2)
            st = re.search(r"status:\s*(\S+)", body)
            if not st or st.group(1) == "fixed":
                i += 1
                continue

            newbody = re.sub(r"status:\s*\S+", "status: fixed", body, count=1)
            if "fixed-in:" not in newbody:
                # Align with a CONTINUATION line (`lane:`/`area:`), never with `status:` — that one
                # sits immediately after `<!--` and carries a single space, so taking its indent put
                # `fixed-in:` one column in while every neighbour was at five.
                ind = re.search(r"\n(\s+)(?:lane|area|kind|gate):", newbody)
                pad = ind.group(1) if ind else "     "
                newbody = newbody.rstrip() + f"\n{pad}fixed-in: unrecorded "
                sentinels += 1

            # splice the rewritten header back over the same span
            start = i + 1 + blob[: m.start()].count("\n")
            end = i + 1 + blob[: m.end()].count("\n")
            newhdr = ("<!--" + newbody + "-->").split("\n")
            out.extend(newhdr)
            print(f"  unrecorded  {f.relative_to(root)}  {head[:70]}")
            changed += 1
            i = end + 1
        if a.apply:
            f.write_text("\n".join(out))

    verb = "migrated" if a.apply else "WOULD migrate (dry run — pass --apply)"
    print(f"\n{verb}: {changed} entries, all `fixed-in: unrecorded` "
          f"(a sha in the prose is not evidence of a fix — see this file's docstring)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
