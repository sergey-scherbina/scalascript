# v1 language core — backlog

Can-wait and not-yet-started work whose code lives in `v1/lang/`. When an item is
picked up it moves to `v1/lang/SPRINT.md` as `[~]` and gets a row on the root board —
in the same commit as the claim. Layout: `specs/work-tracking-layout.md`.

Sections below were carried over whole from the flat root `SPRINT.md`/`BACKLOG.md`,
verbatim, on 2026-07-30.

## 2026-07-30 — jsonParse's number policy: v1 contradicts itself, and v2 was RIGHT

- [x] **JSON-1 — DONE (`56b2b3e5d`), approved by Sergiy 2026-07-30.** The bug said two policies; there
      were THREE — the JS backend was a second lossy site, found only by measuring the js lane. Fixing
      int alone would have TRADED `json-read`'s v2 DIVERGE for a js DIVERGE, so both were fixed and all
      three lanes now agree byte-for-byte. The js side needed no tokenizer: the ES2025 reviver's
      `context.source` gives the original literal and the JS runtime has had an exact `_Decimal` since
      v1.64.
      **Full-corpus blast radius, int lane, 527 cases: exactly 2 regressions**, both fixed —
      `json-read` (frozen golden's lossy `0` -> `0.0`; int and v2 now BOTH pass it, closing the DIVERGE
      this started from) and `json-value` (`JsonValue.asDouble` rejected a Decimal in
      `JsonSupport.scala`, while its two siblings already converted).
      ⚠️ Two lessons worth keeping: a literal grep for the error string found ONE `asDouble` site
      because the message is ASSEMBLED by `typedFail` — the site that actually fires did not match, and
      only the rebuild exposed it. And do NOT rebuild while a corpus run is in flight: `install.sh`
      overwrites the very jar the contract invokes per case.
      Remaining cost, unchanged and documented in `specs/json-number-policy.md`: mixing a parsed JSON
      number with a Double LITERAL now raises for `+`/`>` and — the one for review attention — makes
      `== 1.5` return `false` SILENTLY. Superseded text:
- [ ] ~~**JSON-1 — `v1-json-two-contradictory-number-policies`, needs Sergiy's go.**~~ `JsonParser.scala:93`
      parses every fractional JSON number to binary64, so the GOLDEN lane turns `0.10` into `0.1`,
      `1.50` into `1.5`, and a 34-digit decimal into `0.1`. `V1JsonCore.scala:127` does the exact
      `BigDecimal` thing and its comment says *"never a lossy `Double`"*; v2 agrees with THAT one. The
      fix is one line, but that parser also reads HTTP bodies, JWT claims and session cookies, so it
      moves the golden corpus-wide. Order of work is fixed: change it, run the FULL corpus, report the
      changed cells, and only THEN decide about freezing.
      **Do not resolve it by making v2 lossy to match** — that was the original entry's leaning and it
      trades away exactness v1's own source says is required.
