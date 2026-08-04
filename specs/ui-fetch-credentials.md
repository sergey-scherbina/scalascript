# Outbound credentials — a declaration the runtime resolves

Status: design (2026-08-04) — **the decision is open; this spec states the problem and one proposal**
Owner: `ui-fetch-credentials`
Reported by: rozum — `INBOX.md` entries `ui-fetch-credentials` (design proposal) and
`std-auth-client-half` (the missing concept). **Filed as two, specced as one**, because the reporter
says plainly that if the second is taken the first folds inside it.

## The defect, verified rather than accepted

**The documented pattern compiles the secret into the artifact.** `fetchUrlSignal`'s `headers`
parameter is a `Signal[String]` holding a JSON object, documented as
`{"Authorization":"Bearer …"}`. A signal's INITIAL VALUE is emitted as a literal —
`TuiEmitter.emitSignalSeed` writes `m.insert("<id>".to_string(), <initExpr>)` — so a view that
builds that JSON from `env("TOKEN")` at emit time puts the token in `src/main.rs`, and thus in the
terminal binary. On the web target the same string lands in the bundle.

Confirmed by reading the emitter, not by taking the report's word for it. **And it is worse for
being new:** `tui-fetch-headers` (landed 2026-08-04, `5616c18b0`) is what made authenticating from
the terminal possible at all, so the framework now makes the wrong thing the easy thing, and nothing
in the pipeline is positioned to notice.

**The concept is missing, not the parameter.** `std.auth` is a complete vocabulary for BEING an auth
server — CSRF, sessions, JWT, TOTP, WebAuthn, OAuth — and has nothing for PRESENTING a credential
outbound. That absence is already filled three incompatible ways inside one standard library:

| module | shape |
|---|---|
| `std/http.ssc` | every verb takes `headers: Map[String, String]` — string-building is the caller's problem |
| `std/agent.ssc` | `AgentEndpoint(baseUrl, authToken: String = "")`, scheme hand-built — `"Bearer " + token` appears **twice, six lines apart**, in `requestHeaders` and `streamRequestHeaders` |
| `std/ui` | `headers: Signal[String]`, a JSON object |

Three spellings of one idea is the evidence that the idea has nowhere to live. The duplication
inside a single file is the strongest form of it.

## Proposal — `Credential` names how to obtain a secret; it does not hold one

```
Credential.env("ROZUM_TOKEN")        -- read from the environment, AT CALL TIME on the target
Credential.file("~/.rozum/token")    -- read from a file
Credential.literal("…")              -- explicit, and explicitly discouraged
```

with a scheme applied by the consumer (`Bearer`, `Basic`), not by the caller's string concatenation.

The property that matters is **when it resolves**: a `Credential` is inert data at emit time and is
read by the TARGET runtime at the moment of the call. That is what makes baking impossible rather
than discouraged — an emitter cannot bake what the value does not contain.

`fetchUrlSignal` then takes a `Credential` alongside (not instead of) `headers`, `std/http` verbs
take one, and `AgentEndpoint` holds one instead of a `String`.

## What must be decided before any code

1. **Does `Credential` belong in `std.auth`** (which is server-side today) or beside it? The report
   argues the vocabulary is one; the module boundary is ours.
2. **Migration of the three existing shapes** — additive first, with the string forms kept and
   deprecated, or a single cut? `std/agent`'s `authToken: String` is public API.
3. **What each target runtime is allowed to read.** `env` on a browser target has no meaning; the
   proposal needs a per-target answer, and "unsupported here" is a legitimate one **if it is a
   compile error rather than an empty string**.
4. **Whether the emitter should refuse a literal-looking secret.** A heuristic that rejects
   `Signal[String]` values matching a token shape would be a guess; refusing `Credential.literal`
   in a release build is not.

## Immediate mitigation, independent of the decision

The hazard exists today and the design will take longer than a doc fix. `fetchUrlSignal`'s contract
in `v1/runtime/std/ui/primitives.ssc` must say that the headers signal's INITIAL VALUE is emitted as
a literal, so a secret must not be built into it at emit time. That is not the fix; it is the
warning that the fix has not happened yet.

## Verification (when the design is chosen)

- an emitted TUI crate built from a source using `Credential.env` contains **no** occurrence of the
  secret — the direct negative of the defect above;
- the same source authenticates against a fixture requiring the header, i.e. the credential is
  resolved at call time on the target;
- `std/agent`'s two hand-built `"Bearer " + token` sites become one call.

## Non-goals

- Credential storage, rotation, or a keychain integration. `Credential` NAMES a source; obtaining it
  is the host's business.
- Server-side auth. `std.auth` keeps its half untouched.

## Result

*(open — this spec is the proposal, not a completed change)*
