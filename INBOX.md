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
## tui-fetch-post — The TUI frontend emits only a managed GET, so a TextInput composer in a dual-target .ssc app renders but can never submit — no fetchAction/POST binding exists
<!-- triage: new
     reported-by: rozum (github.com/sergey-scherbina/rozum, docs/specs/ucc-meetings-in-tk.md)
     reported-at: 2026-08-04
     ssc-version: ec70eb062 (staged bin/, dev build)
     repro: none
     kind: feature
     reporter-suspects: collectFetches records only FetchUrlSignal -> FetchInfo(url, tickId), documented as 'Managed GET metadata'; no fetchAction/POST anywhere under frontend/tui/src/main
     impact: blocks -->

rozum emits the same `.ssc` source to two targets: `emit-spa --frontend react` for the web control
centre, and `emit(view(), "tui-out")` for a native ratatui client. That dual-target path is already
proven end-to-end for READING — `specs/frontend-tui-fetch-refresh.md` on your side, and rozum's
`docs/specs/ucc-poc-msglist.md` + `clients/control/meeting-message-list.ssc` on ours: one source, a
React artifact and a ratatui crate that builds and renders live rows headlessly.

We are now extending that client to replace a hand-written ratatui meeting client, and the extension
stops at the network boundary. The widgets are all there — `TuiEmitter` slice 3 gives
`TextInput`/`Button`/`Toggle`, a focus ring, Tab/arrow traversal, `Enter` activation and typed-char
editing, so a composer LOOKS buildable. It just cannot send anything.

What we observe in `frontend/tui/src/main/scala/scalascript/frontend/tui/TuiEmitter.scala`:

- `collectFetches` records only `FetchUrlSignal`, into `FetchInfo(url, tickId)`;
- the doc comment on that record calls it "Managed **GET** metadata";
- `grep -rE 'fetchAction|FetchAction|"POST"|Method::Post' frontend/tui/src/main/` returns nothing.

So on the web target a composer is `fetchAction("POST", …)` and works; on the TUI target the same
source has no way to submit. The result is not a broken build — it is a client that renders a text
box which silently does nothing, which is worse.

What would unblock us: an emitter-side counterpart of `fetchAction` — a POST binding that sends a
body and bumps a tick on success so the bound GET re-reads. Same shape as the refresh contract you
already gate: a deterministic local-HTTP test asserting the generated Rust posts the body and then
performs the follow-up GET.

We are not asking for auth, headers or streaming — a plain POST with a body and an on-success tick
covers the composer, and covers it for every other `.ssc` app that wants to write from a terminal.
## tui-fetch-url-signal — The TUI frontend bakes the fetch URL in at emit time (only the tick is dynamic), so a room switcher or day-pager on the TUI target keeps showing the endpoint chosen at build time
<!-- triage: new
     reported-by: rozum (github.com/sergey-scherbina/rozum, docs/specs/ucc-meetings-in-tk.md)
     reported-at: 2026-08-04
     ssc-version: ec70eb062 (staged bin/, dev build)
     repro: none
     kind: feature
     reporter-suspects: FetchInfo(f.fetchUrl, f.tickId) captures the URL literal; no fetchUrlSignalTo/urlSignal equivalent under frontend/tui/src/main
     impact: blocks -->

Sibling of the POST report, same rozum dual-target meeting client, different missing piece.

In `TuiEmitter`, a fetch-bound signal is collected as `FetchInfo(f.fetchUrl, f.tickId)` — the URL is
a literal captured at EMIT time, and only the tick is dynamic. `grep -rE
'fetchUrlSignalTo|urlSignal' frontend/tui/src/main/` returns nothing, so there is no counterpart of
the primitive that takes the URL from a signal.

That makes anything whose data source is CHOSEN AT RUNTIME impossible on the TUI target. Two
concrete cases from one screen:

1. a room switcher — picking a room must re-target `GET /rooms/{name}/messages/{date}`;
2. paging to older history — `PgUp` must fetch the previous day, i.e. another `{date}`.

Both are ordinary on the web target (`fetchUrlSignalTo` with a signal URL). On the TUI target the
list of rooms renders, the selection moves, `Enter` fires — and the transcript below keeps showing
the room that was baked in at emit time. Again: not a build error, a silently wrong client.

What would unblock us: let the fetch URL come from a `Signal[String]`, so setting that signal
re-targets the GET, with the same "changed → schedule a new GET before the next frame" rule the tick
already has. Acceptance shape identical to `specs/frontend-tui-fetch-refresh.md`: a deterministic
local-HTTP test where changing the URL signal makes the generated crate read the second endpoint.

Ordering, if it matters to you: this one is the cheaper of the two and unblocks read-side navigation
on its own — a switcher that navigates is useful before a composer that posts.
## tui-fetch-headers — fetchUrlSignal takes a headers signal that the TUI target silently drops — the emitted ureq GET sends no headers, so a source that authenticates on the web emits a terminal binary that gets 401
<!-- triage: new
     reported-by: rozum (github.com/sergey-scherbina/rozum, docs/specs/ucc-meetings-in-tk.md)
     reported-at: 2026-08-04
     ssc-version: ec70eb062 (staged bin/, dev build)
     repro: none
     kind: feature
     reporter-suspects: collectFetches keeps only FetchInfo(url, tickId); emitted fetch_text is ureq::get(url).call() with no header plumbing
     impact: blocks -->

Third report from the same rozum screen, and the one that blocks earliest — earlier than
`tui-fetch-post` and `tui-fetch-url-signal`, because it stops the *read-only* client.

`fetchUrlSignal` takes a `headers: Signal[String]` parameter — a JSON object read at fetch time,
documented in `std/ui/primitives.ssc` with `{"Authorization":"Bearer …"}` as the example. The web
target honours it. The TUI target does not: `collectFetches` keeps only `FetchInfo(url, tickId)`, and
the emitted helper is

```rust
fn fetch_text(url: &str) -> Option<String> {
    match ureq::get(url).call() { Ok(resp) => resp.into_string().ok(), Err(_) => None }
}
```

— no headers, no auth, no way to pass either. So a `.ssc` source that authenticates correctly on the
web emits a terminal binary that sends a bare GET.

Why this is not a small thing for us: rozum's meeting daemon requires HTTP Basic on **every** route
(`GET /rooms` answers `401`), and so does the control server (`GET :8411/chat/messages` → `401`).
There is no unauthenticated read path anywhere, by design. So today the terminal target cannot read
the real data at all — only an unauthenticated fixture, which is exactly what
`specs/frontend-tui-fetch-refresh.md` and our own PoC smoke were proven against. That is worth saying
plainly: **the dual-target proof so far has only ever run against fixtures with no auth**, so this
gap was invisible until someone pointed the generated client at a production endpoint.

What would unblock us: honour the `headers` signal on the TUI target — read the JSON object at fetch
time and set the headers on the ureq request. `Authorization` alone covers our case; doing it
generically for any key is presumably the same work.

Acceptance shape, same as the refresh contract you already gate: a deterministic local-HTTP test
where the fixture returns 401 without a header and 200 with it, and the generated crate reads the
body only when the source supplied the header signal.
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
