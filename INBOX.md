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
## process-needs-a-detached-spawn — std/process can only run children it WAITS for, so a served program cannot start anything that outlives the request
<!-- triage: new
     reported-by: rozum (sergey-scherbina/rozum, agent claude-code)
     reported-at: 2026-08-16
     ssc-version: bin/ssc-tools built from bad74ecdf
     repro: examples/reported/process-needs-a-detached-spawn.ssc
     kind: feature
     impact: blocks -->

We ported fifteen HTTP routes of a control console (rozum's UCC) from Rust to ScalaScript. Every
route that STARTS something — an agent run, a coder session, a terminal, a benchmark — stopped at
the same wall, and it is not "no process control": `std/process` has exactly one primitive and it
WAITS.

    extern def exec(cmd, args, opts): ProcessResult      // stdout, stderr, exitCode

By construction that cannot return before the child is finished. A request handler starting a
five-minute agent run can hold the connection for five minutes or not start it. Measured with the
attached repro, built with `build-rust`:

    before exec
    after exec, child exit 0
    real 2.14        # a /bin/sleep 2 the handler had to sit through

**What would be enough:** a spawn that returns a handle instead of a result.

    case class Child(pid: Int)
    extern def spawn(cmd, args, opts): Child

`pid` alone carries us — the caller records it, and killing already works through
`exec("kill", [pid], …)`. `wait`/`isAlive`/streams are welcome but are not what blocks the port.
Detaching from the parent's lifetime is part of the ask: the child must survive the server that
started it.

**What we do instead:** the Rust half keeps every launch route (`Command::spawn()`, pid into a
registry file, handler returns immediately). The ScalaScript half serves the routes that read
files, call HTTP, or run a child to completion — the boundary between the two halves is exactly
this primitive, not a preference.
## process-needs-a-stdin-pipe — exec cannot write to a child's stdin, so a secret can only reach it through argv where every local process can read it
<!-- triage: new
     reported-by: rozum (sergey-scherbina/rozum, agent claude-code)
     reported-at: 2026-08-16
     ssc-version: bin/ssc-tools built from bad74ecdf
     repro: examples/reported/process-needs-a-stdin-pipe.ssc
     kind: feature
     impact: blocks -->

One route of our control console installs a Telegram bot, which means handing a bot token to a
child process. The Rust implementation writes it to the child's STDIN precisely so it never appears
in an argument vector:

    let child = Command::new(&exe).args(args).stdin(Stdio::piped()).spawn()?;
    child.stdin.take().write_all(token.as_bytes())?;

`ProcessOptions` has `cwd`, `env`, `timeout`, `inheritEnv` — and no stdin — so a ScalaScript port of
that route would have to pass the token as an argument, where any local process can read it:

    $ ps -axo command | grep bot-add
    rozum-gateway messenger bot-add mybot --token 7712345678:AA…

That route is the ONLY one of the whole port blocked for a security reason rather than a capability
one — everything else either moved or is blocked by the detached-spawn gap. A workaround exists and
is worse: writing the token to a temp file and passing the path leaves the secret on disk, which is
what the stdin design was avoiding.

**What would be enough:** one field, or one overload.

    case class ProcessOptions(…, stdin: Option[String] = None)   // written to the child, then closed

A string is enough here — the value is short and known before the call. A streaming handle would be
more, and is not what blocks us.

The attached repro shows the shape we want beside the one that exists; it passes the secret on the
command line, which is exactly what we will not ship.
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
