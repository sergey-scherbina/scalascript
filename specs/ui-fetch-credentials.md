# Outbound credentials — a declaration the runtime resolves

Status: S1 landed (2026-08-04) — vocabulary in `std/credential.ssc`; emitter resolution is S2, blocked
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

## Decisions (2026-08-04) — taken rather than referred back

The reporter left these to us and they were blocking the concept, so they are answered here with
what was rejected and why. Each is reversible; none is a coin flip.

**1. `Credential` lives BESIDE `std.auth`, in its own `std/credential.ssc` — not inside it.**
The dependency direction decides it: a UI fetch or an HTTP verb must be able to present a credential
without importing a vocabulary for CSRF, JWT signing, TOTP, WebAuthn and OAuth. Putting the outbound
half inside the server half would make every client program depend on the server surface.
*Rejected:* one module for "everything auth", which is tidier to describe and worse to import.

**2. Migration is ADDITIVE. The string forms stay.**
`AgentEndpoint(baseUrl, authToken: String = "")` is public API and rozum and busi are mid-flight
against it. A new `credential` field arrives alongside, `authToken` keeps working, and the two
hand-built `"Bearer " + endpoint.authToken` sites (`std/agent.ssc:443` and `:452` — verified, six
lines apart) collapse onto one resolution call.
*Rejected:* one cut now. It buys a smaller surface at the cost of breaking every downstream program
simultaneously, for an ergonomic gain that can arrive later. Parked: the cut, once the additive form
has bake time.

**3. A target that cannot resolve a credential is a COMPILE ERROR, never an empty string.**
`credentialEnv` has no meaning in a browser bundle. Emitting an empty header there produces a 401 in
production — silent, at the worst moment, and indistinguishable from a server problem. Refusing at
emit time is the only outcome that reaches the person who can fix it.
*Rejected:* a runtime warning; it arrives where nobody reads it.

**4. `credentialLiteral` is ALLOWED, and named so it is visible.**
Our own fixtures need a literal token, and a form that tests cannot express gets worked around
rather than followed. It is the one form that CAN be baked, so it is spelled `literal` and reads as
such in review.
*Rejected for now:* refusing it in a release build — the repo has no "release build" concept to hang
that on, and inventing one for this is a bigger change than the feature.

## What the decisions above replaced

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

## Result — S1 (2026-08-04)

`v1/runtime/std/credential.ssc` carries the vocabulary. Gate
`tests/conformance/credential-vocabulary.ssc` passes INT and JS.

The gate's third line is the one that earns its keep: it constructs `credentialEnv("HOME")` where
`HOME` is genuinely set, and asserts `source` is still the string `HOME`. Verified by mutation
**through the conformance runner** — making resolution eager turns both lanes red with
`env|Some(/Users/sergiy)|Bearer`, a machine-dependent path; restoring turns them green. An earlier
falsifiability check run through `bin/ssc` was discarded: it read a hand-patched staged tree, so it
proved nothing about the gate.

Two things surfaced that were not part of the design:

1. **`install.sh --dev` stages `std/` into two trees** — `bin/lib/standard/native-front/` (read by
   `bin/ssc`) and `bin/lib/native-front/` (read by the conformance runner). Patching one and
   verifying with the other is a false green.
2. **A paren-less `def` is not portable** — the native front auto-invokes it, the interpreter yields
   the function, and no call spelling satisfies both. Filed as `BUGS.md`
   `parameterless-def-diverges-native-vs-interp`. `credentialNone` is therefore a `val`.
