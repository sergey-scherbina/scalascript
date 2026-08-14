# INBOX — reports from people who USE ScalaScript, before anyone knows where the fix goes

**The rule is [`POLICY.md`](POLICY.md) P-3.10. The mechanics are
[`specs/work-tracking-layout.md`](specs/work-tracking-layout.md) §Inbound queue. This file is the
queue itself.**

Register with the tool, never by hand — it is what keeps the fields well-formed:

```sh
scripts/inbox-add <slug> --summary "…" --reported-by <who> --ssc-version <v> [--repro <path>]
scripts/inbox-add --list                 # what is waiting, oldest first
tests/e2e/inbox-gate.sh                  # what this file must satisfy
```

## Why this file exists at all

Every other record in this repo is born already routed: `lane:` and `area:` are judgements about
**where the fix goes**, and an agent makes them when filing. A user's report cannot carry them. The
reporter knows a symptom and a version; the module is a conclusion someone reaches later, by reading
code. Before this file there was nowhere to put a report that had not reached that conclusion yet, so
the choice was to guess a module or to lose the report.

**This is the one place an entry may exist without a module**, and it is a STATE, not a subtype —
`kind:` still classifies the report the same way it classifies everything else (P-3.8).

**Everything the reporter offers is kept, diagnosis included.** That is a correction, made
2026-07-31 after users said the form was too narrow, and they were right. The original wording told
reporters not to diagnose — which threw away real work (a bisect, a read of the source, a workaround
that failed) because of a rule that was never about their information at all. What P-3.3 forbids is
routing ON a guess; it says nothing against RECORDING one. So a reporter's reading of the cause goes
in `reporter-suspects:` and in the body, verbatim, and the triager still reaches their own conclusion
before `lane:`/`area:` are written. Information and authority are different things, and only the
second was ever meant to be constrained.

## What leaves this file, and how

- **Routed** — the entry MOVES into `<module>/BUGS.md` (or `BACKLOG.md`) of the module where the fix
  goes (P-3.2), gains `status`/`lane`/`area`/`gate`, and is **deleted from here**. Two copies of one
  record is the exact failure the whole policy exists to prevent.
  The reporter fields — `reported-by`, `reported-at`, `ssc-version` — **travel with it**. They are
  not decoration: `confirmed: no` in the header schema already means "fixed, but the reporter has not
  confirmed", and until now nothing recorded WHO to ask.
  There is deliberately no "routed" list here: it would be a second copy. The routed set is
  DERIVED — every entry anywhere carrying `reported-by` came from a user (P-3.5).
- **Closed without routing** — `duplicate` or `not-a-defect` produce no destination entry, so they
  are the one outcome nothing else can record. They get ONE line in the section below, with the
  reason. Not the report body; a line.

**Nothing is allowed to sit OUTSIDE the queue either.** `inbox-gate` also lists open `user-report`
issues and fails on any whose URL appears nowhere — not here, not as a `reported-by:` on a board.
Without that, the age limit would only govern reports that already got imported, and "lost politely"
would simply relocate to GitHub instead of being removed. Needs `gh`; without it the check says so
out loud rather than passing quietly, because a network check that silently becomes a no-op is worse
than one that is missing.

## Queue

<!-- inbox-entries:start — `scripts/inbox-add` appends here; the gate parses this region -->
## serve-binds-all-interfaces — serve(port) always binds 0.0.0.0 with no way to say otherwise; four live .ssc services are LAN-reachable while every Rust service on the same machine binds loopback. Branch feature/ssc-http-bind-address pushed
<!-- triage: new
     reported-by: rozum
     reported-at: 2026-08-13
     ssc-version: 61eaefc57
     repro: none
     kind: bug -->

`serve(port)` binds `0.0.0.0` and there is no way to say otherwise, so every ScalaScript HTTP
service is reachable from the LAN whether or not it was meant to be.

EVIDENCE, from the machine this was found on — every listening socket, split by who built it:

```
rozum-gateway  127.0.0.1:8089   127.0.0.1:8401   127.0.0.1:8411   127.0.0.1:8779   (Rust)
rozum-meeting  *:8405   *:8406                                                     (.ssc)
ssc_program    *:8493   *:8497                                                     (.ssc)
```

Four live services listening on every interface. Not one of them chose that: `_http_serve` in the
Rust runtime template does `SocketAddr::from(([0, 0, 0, 0], port as u16))` and `serve` takes a port
and nothing else, so the program cannot express the narrower choice.

WHERE IT BIT: a rozum console route is moving from an in-process Rust handler to a .ssc program.
The Rust one binds loopback. The .ssc one cannot, so the move would widen exposure as a side effect
of a refactor that is supposed to be behaviour-preserving. That is the whole reason this is a report
and not a preference.

A BRANCH IS PUSHED: `feature/ssc-http-bind-address` (21e9ad9ba). It reads `SSC_HTTP_BIND` when set
and keeps `0.0.0.0` otherwise, so nothing that serves the network today stops doing so, and a value
that does not parse fails loudly at startup rather than silently binding wider than asked:

```
serve(8499): SSC_HTTP_BIND="не-адрес" is not an address: invalid socket address syntax
```

Measured with a four-line server built by that toolchain (`.build-digest` equals the tree digest,
STALE banner silent): unset → `*:8499`; `SSC_HTTP_BIND=127.0.0.1` → `127.0.0.1:8499` with the LAN
address refused; a bad value → the panic above.

An environment variable rather than a `serve(host, port)` overload on purpose — smallest change that
makes an existing deployment confinable, no typer or lowering change. The second arity is the better
API; take it instead if you prefer, the branch does not block it. Either way the default should stay
`0.0.0.0` so nobody's running service goes dark on upgrade.
<!-- inbox-entries:end -->

## Closed without routing

<!-- inbox-closed:start -->
<!-- inbox-closed:end -->

## Entry format

```markdown
## <slug> — <the summary in the REPORTER's words, not yours>
<!-- triage: new
     reported-by: <handle, address, or issue URL>
     reported-at: 2026-07-31
     ssc-version: 1.72.0
     repro: examples/reported/foo.ssc
     kind: bug -->

Everything they wrote, in their terms and at their length — what they ran, what happened, what they
already tried, logs, their theory, the workaround that did not help. Do not compress it into what you
think matters: you are reading it before you know which part turns out to be the clue. Add your own
findings under a separate heading rather than editing theirs, so the two never blur.
```

| field | required | values |
|---|---|---|
| `triage` | always | `new` · `needs-info` |
| `reported-by` | always | a handle, an address, or a URL — something that can be replied to |
| `reported-at` | always | `YYYY-MM-DD` |
| `ssc-version` | always | what they ran; `unknown` is allowed and is itself information |
| `repro` | always | a path to a minimal case, or `none` |
| `kind` | optional | as in `specs/bugs-index.md`; defaults to `bug` |
| `waiting-on` | when `triage: needs-info` | what was asked of the reporter, and when |
| `reporter-suspects` | optional | THEIR hypothesis about the cause, in one line. Explicitly not a routing decision — see below |
| `impact` | optional | `blocks` · `workaround` · `annoying` · `fyi`, as the reporter judged it |

**`lane:` and `area:` are absent on purpose, and that is NOT a limit on what a reporter may say.**
Those two fields carry the ROUTING DECISION, whose authority order is fixed (P-3.3: a resolvable
`fixed-in` > a human's declared judgement > never keyword extraction). An inbox entry has not reached
that decision yet, so the fields stay empty — writing them to look complete is how four entries
landed under the wrong owner.

A reporter's own diagnosis is a different thing entirely and is **welcome**: it goes in
`reporter-suspects:` and in the body. The gate refuses `lane:`/`area:` here and accepts
`reporter-suspects:` precisely so that the distinction is mechanical rather than a matter of
etiquette — you can say anything, and nothing you say can silently become the routing.
