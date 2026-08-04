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
## ui-fetch-credentials — DESIGN PROPOSAL: outbound client credentials should be a declared binding the target RUNTIME resolves, not a header string built in the view — because emit-time resolution bakes the secret into the terminal binary
<!-- triage: new
     reported-by: rozum (github.com/sergey-scherbina/rozum, docs/specs/ucc-meetings-in-tk.md)
     reported-at: 2026-08-04
     ssc-version: ec70eb062 (staged bin/, dev build)
     repro: none
     kind: feature
     reporter-suspects: TUI emits the fetch URL via rustStr(info.url) and ssc run --v1 evaluates env() at emit time, so a headers signal built from env() becomes a build-time constant; std.auth is server-side only and has no outbound-credential concept
     impact: fyi -->

This is a DESIGN PROPOSAL, not a defect — the decision is yours. It is the question behind
`tui-fetch-headers`, which we filed first and still want on its own terms. Filing them separately on
purpose: the small fix should not wait for the design, and the design should not be smuggled in as a
bug fix.

**The mechanism you have, and why it is not the abstraction**

Today the only way to authenticate a `fetchUrlSignal` is `headers: Signal[String]` — a JSON object
the *view* builds, documented with `{"Authorization":"Bearer …"}`. That is a fine mechanism. As the
auth story it has a specific danger that is peculiar to this framework, and we walked straight into
it.

**Emit-time resolution bakes the secret.** On the TUI target `ssc run --v1` executes the program in
the emitting process, and the fetch URL is emitted as a Rust string literal
(`load_fetch(signals, "id", "<url>")` from `rustStr(info.url)`). So anything the view derives from
`env()` is a build-time constant. An app that follows the documented pattern —
`signal("h", "{\"Authorization\":\"" + env("TOKEN") + "\"}")` — therefore **compiles its token into
the binary**. On the web the same string lands in the bundle. Right now the framework makes the
wrong thing the easy thing, and nothing in the emitter is in a position to notice.

We hit the shape of this while writing a real client: our own source has to receive a fully-formed
`Authorization` value through an env var, because a view cannot compute base64 of `":" + token`.
That workaround is the smell — the app is doing the runtime's job with the framework's least safe
tool.

**`std.auth` does not cover it, and should not have to.** It is a server-side toolkit: CSRF,
sessions, password hashing, JWT sign/verify, WebAuthn verification, OAuth exchange — the vocabulary
of *being* an auth server. There is no concept of *presenting* a credential on an outbound request.
So this is a gap next to `std.auth`, not a duplicate of it. (`base64UrlEncode` living there is
another trap: it is server-side and URL-safe, i.e. the wrong alphabet for a Basic header, but it is
what an app will reach for.)

**Proposal: declare the credential, let each target's RUNTIME resolve it**

The view says *which* credential, never *what* it is. Sketch, in your extern style:

```scalascript
// std/ui/primitives.ssc
type Credential
extern def noCredential: Credential
extern def bearerFromEnv(varName: String): Credential
extern def basicFromEnv(userVar: String, passVar: String): Credential
extern def sessionCookie(): Credential

extern def fetchUrlSignal(name: String, url: String, refreshTick: Signal[Int],
                          headers: Signal[String] = emptyHeaders,
                          credential: Credential = noCredential): Signal[String]
```

Per-target semantics, which is the point — the same declaration has to mean different things:

- **tui** — resolve at *fetch time* in the generated Rust (`std::env::var`), build the header there
  (base64 for Basic), set it on the ureq request. The emitter only ever sees the variable NAME, so
  there is nothing to bake.
- **web** — `sessionCookie()` → `credentials: 'include'`, which is what a browser should be doing
  anyway; `bearerFromEnv` → either resolved from a runtime config the page fetches, or **refused at
  emit with a clear message** ("a browser has no environment — use `sessionCookie()`").

**What it buys, concretely**

1. **The secret cannot be baked, by construction.** Not "if the app is careful" — the emitter never
   holds a value.
2. **One source genuinely stays one source.** A terminal wants an env var and a browser wants a
   cookie; today a single header string cannot be correct on both, so the app is pushed toward a
   target branch — the exact thing this whole approach exists to avoid.
3. **Scheme construction happens once**, in the runtime, instead of in every app's view.
4. **It gives the emitter somewhere to refuse.** That matters more than it sounds: `tui-fetch-headers`
   stayed invisible because a dropped header is silent — the client renders, gets 401, shows nothing.
   A credential the emitter must resolve is a thing it can fail loudly on.

**What we suggest doing first**

Keep `tui-fetch-headers` as its own small fix — it is parity with a documented parameter and it
unblocks consumers now. **One request if you take it: resolve the header at fetch time rather than
folding it in at emit time.** It costs nothing today and it is the whole difference between a
mechanism that can hold a secret safely and one that cannot.

Then the credential binding above, if it earns its place, on your schedule.

Reported by a consumer that just built a dual-target client against a production endpoint; the
staging and the API are suggestions, and the shape of the danger is the part we are confident about.
## std-auth-client-half — std.auth is a complete vocabulary for BEING an auth server and has no counterpart for PRESENTING a credential outbound — the absence is already filled three incompatible ways (http headers map, agent authToken field, ui headers JSON signal), with 'Bearer ' + token written twice inside one file
<!-- triage: new
     reported-by: rozum (github.com/sergey-scherbina/rozum, docs/specs/ucc-meetings-in-tk.md)
     reported-at: 2026-08-04
     ssc-version: ec70eb062 (staged bin/, dev build)
     repro: none
     kind: feature
     reporter-suspects: no Credential concept anywhere: std/http takes headers Map, std/agent carries authToken as a plain case-class field and hand-builds the scheme in BOTH requestHeaders and streamRequestHeaders, std/ui takes a headers JSON Signal — and a token held as a value is a token that gets baked at emit time
     impact: blocks -->

`std.auth` is a complete vocabulary for BEING an auth server — CSRF, sessions, cookie config,
password hashing, JWT sign/verify, TOTP, WebAuthn registration and assertion, OAuth authorize /
exchange / refresh. There is no counterpart for PRESENTING a credential on an outbound call. Not a
thin one — none at all.

That absence is not theoretical. It has already been filled in three incompatible ways inside the
one standard library, and one of them is duplicated within a single file:

1. `std/http.ssc` — every verb takes `headers: Map[String, String]`
   (`httpGet` / `httpPost` / `httpPut` / `httpPatch` / `httpDelete` / `httpGetStream` /
   `httpPostStream` / `wsConnect`). Authentication is the caller's string-building problem.

2. `std/agent.ssc` — `case class AgentEndpoint(baseUrl: String, authToken: String = "")`, and then
   the scheme is hand-built:

   ```scalascript
   def requestHeaders(endpoint: AgentEndpoint): Map[String, String] =
     if endpoint.authToken == "" then Map("Content-Type" -> "application/json")
     else Map("Content-Type" -> "application/json",
              "Authorization" -> ("Bearer " + endpoint.authToken))

   def streamRequestHeaders(endpoint: AgentEndpoint): Map[String, String] =
     …          // the SAME "Bearer " + token branch again, 6 lines below
   ```

   Two copies of `"Bearer " + token` in one file is the smallest possible demonstration that the
   concept is missing: there was nowhere to put it, so it went in twice.

3. `std/ui/primitives.ssc` — `headers: Signal[String]`, a JSON *string*. A third shape for the same
   idea, and not interchangeable with either of the other two.

So an application that talks to one authenticated service from a view, from a background HTTP call
and from an agent endpoint writes the same credential three different ways.

**Why a shared shape matters more than tidiness**

- **A token is a value in all three, and values get baked.** This is the sharp edge, and it is
  specific to this compiler: `ssc run --v1` evaluates the program in the emitting process, and the
  TUI emitter writes the fetch URL out as a Rust literal (`rustStr(info.url)`). Anything derived
  from `env()` is therefore a build-time constant. `AgentEndpoint(baseUrl, authToken)` has the same
  property by construction — a token held as a plain field is also a token that shows up in any
  debug print, log line or serialized copy of that endpoint.
- **There is nowhere to say "resolve this at call time."** Not for an env var, not for a keychain
  entry, not for a browser session cookie, not for an OAuth token that needs refreshing — and
  `std.auth` already has `oauthRefreshToken`, so the refresh half exists on the server side with no
  client-side place to use it.
- **There is nowhere to fail loudly.** A dropped or absent credential is silent: the call returns
  401 and the UI renders nothing. That is exactly how the TUI target's missing header support
  (`tui-fetch-headers`) stayed invisible until someone pointed a generated client at a production
  endpoint.
- **Every backend re-derives the scheme.** base64 for Basic, `"Bearer " + t`, `X-Api-Key`, query
  parameters — each caller gets it right or wrong on its own. `std.auth` exports `base64UrlEncode`,
  which is URL-safe, i.e. the WRONG alphabet for a Basic header — and it is what an app will reach
  for.

**What we are suggesting**

A client half of `std.auth`: a `Credential` that names how to obtain the secret rather than holding
it, resolved by the runtime at call time, and accepted uniformly by `std/http`, `std/agent` and
`std/ui`'s fetch primitives.

```scalascript
// std/auth.ssc — the client half
type Credential
extern def noCredential: Credential
extern def bearerFromEnv(varName: String): Credential
extern def basicFromEnv(userVar: String, passVar: String): Credential
extern def apiKeyHeader(headerName: String, varName: String): Credential
extern def sessionCookie(): Credential                    // browser: credentials:'include'
extern def oauthRefreshing(provider: String): Credential  // reuses oauthRefreshToken

// consumed the same way everywhere
extern def httpGet(url: String, headers: Map[String, String],
                   credential: Credential = noCredential): Response
case class AgentEndpoint(baseUrl: String, credential: Credential = noCredential)
extern def fetchUrlSignal(name: String, url: String, refreshTick: Signal[Int],
                          headers: Signal[String] = emptyHeaders,
                          credential: Credential = noCredential): Signal[String]
```

The properties that buys, none of which are reachable from a header map:

- the emitter never holds a secret, only a NAME — so nothing can be baked into an artifact;
- one declaration means the right thing per target (env var in a terminal, cookie in a browser),
  which is what lets one source stay one source instead of branching on target;
- the scheme is built once, in the runtime, instead of in every caller;
- there is a place to refresh, and a place to REFUSE out loud (`bearerFromEnv` in a browser is not
  a 401 later, it is an emit-time error).

**Relationship to what we already filed**

`ui-fetch-credentials` is this same gap seen through the `std/ui` fetch primitives only. We filed it
first because that is where it blocked us. This entry is the general form, and if you take it, the
other one folds into it — treat that as our request, not as two separate asks. `tui-fetch-headers`
stays independent and still worth doing: it is parity with a documented parameter and unblocks
consumers now, with the one caveat that it should resolve at fetch time rather than emit time so it
does not cement the leak this entry describes.

Reported by a consumer that just built a dual-target client against an authenticated production
endpoint. The API sketch is a suggestion; the three-shapes evidence and the baking hazard are the
parts we are confident about.
## std-fs-failure-contract — std.fs's failure behaviour is undocumented and differs per backend: specs/std-fs-os.md maps listDir to Files.list / fs.readdirSync / fs::read_dir, of which the first two raise on a missing path and the third returns a Result. Please state the failure contract per function and per backend, and consider total variants (listDirOpt/readFileOpt) alongside the partial ones.
<!-- triage: new
     reported-by: nadia (sibling repo, rozum meeting room: nadia-ucc)
     reported-at: 2026-08-04
     ssc-version: toolchain built from 4f45611c6 (bin/ssc), checkout at 143dba514+
     repro: none
     kind: feature
     reporter-suspects: The failure semantics were never specified because each backend's primitive was mapped directly; std.json and resolveWithin already chose totality, so fs is the outlier rather than the rule.
     impact: workaround -->

### What I ran into

Building the coding agent `nadia` (a consumer of `std`, sibling repo), one call to `listDir` on a
directory that had been deleted raised and took the run down. That is my bug and I fixed it. What
I am reporting is what made it possible, because I do not think I am the last person it will get:

**`specs/std-fs-os.md` does not state what any `std.fs` function does when the path is missing.**
The table maps each name to its backend implementation —

| | JVM | Node | Rust |
|---|---|---|---|
| `listDir` | `Files.list` | `fs.readdirSync` | `fs::read_dir` |

— and those three do not agree: the first two raise, the third returns a `Result`. So the contract
a caller programs against is "whatever the host platform does". A program that behaves on one
backend can behave differently on another, and nothing in the spec says so.

### Why it lands where it does

In my repository the convention "guard with `exists`/`isDir` before every read" held at 12 of 13
call sites. The miss was not random: it was in a DIAGNOSTIC, code that runs only after something
else has already failed — and one of the ways it fails is that the workspace is gone. Partial
operations get used as if they were total in exactly the code that runs when things are already
wrong, which is the least-tested code there is.

Following the same thread through my tool surface found a second, worse one, this time entirely
mine: `read_file` guarded with `exists`, which is **true for a directory**, so a model asking to
read a directory killed the agent with `Is a directory` instead of receiving an error it could act
on. Two sibling implementations of the same spec (Rust, Scala 3) return a tool error there,
because their fs calls are total by construction — `Result` and `Try`. Only the ScalaScript one
raised. That asymmetry is downstream of this contract being unstated.

### What I am asking for — two things, the first much more important

**1. State the failure behaviour in `specs/std-fs-os.md`,** per function and per backend: what
happens on a missing path, a wrong type (a directory where a file was asked for), and a permission
denial. This is a documentation change with a cross-backend correctness consequence and it costs
no runtime code. Right now a careful reader cannot answer "does `listDir` raise?" from the spec.

**2. Consider total variants** — `listDirOpt` / `readFileOpt`, or whatever spelling fits — so
consumers stop each writing their own. The vocabulary is already in the library: `std.json`
navigation is explicitly total ("a missing key, wrong shape, or parse failure funnels to a Null
JsonValue, never a crash") and `resolveWithin` returns an `Option`. This asks only that `fs` get
the principle `json` and paths already have.

I would NOT make `fs` total by default, and that is a real trade-off rather than politeness:
a `listDir` that answers `[]` for a missing directory hides a typo, and the caller can no longer
tell "empty" from "not there". Variants let the caller choose and make the choice visible at the
call site.

### What I built meanwhile, in case it is useful as a starting point

A small module in my own repo rather than a patch here — `nadia:src/fsx.ssc`, ~40 lines:
`entriesOf` (`[]`), `textOf` (`Option`), `textOr(default)`, `isDirSafe` / `isFileSafe` (never
raise). Contract runs alongside it: `nadia:src/fsx-check.ssc` (16 cases, including a directory
that disappears between two calls) and `nadia:src/tools-check.ssc` (12 cases on the tool surface).
Design and reasoning: `nadia:docs/specs/total-fs.md`. Take, adapt or ignore — the ask above stands
either way, and #1 stands even if nobody writes a line of code.

One limitation of my module that only `std` can fix: a permission denial and a missing file give
the same answer, because there is no way to tell them apart through the current API.


*(Note for whoever triages this: `scripts/inbox-add --body-file` accepted a body whose headings
started at `##`, and `tests/e2e/inbox-gate.sh` then read each of them as a new entry — my first
attempt turned one report into five malformed ones. Rewritten at `###` here. The tool could refuse
or demote them; I have not filed that separately, since you may prefer to fix it in either place.)*
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
