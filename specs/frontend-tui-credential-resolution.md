# Terminal fetch: resolving a `Credential` on the target

Status: **implemented below the primitive** (landed 2026-08-04, `d4180b88b`) — model + intrinsic-ready + emitter resolution, gated by a cargo probe that proves the secret is absent from the emitted source. The `credential` PARAMETER on `fetchUrlSignal` is still pending in `primitives.ssc`.
Owner: `tui-credential-resolution` — **S2 of [`ui-fetch-credentials.md`](ui-fetch-credentials.md)**
Affected: `frontend/core` (model), `v1/runtime/std/fetch-plugin` (site), static `frontend/tui`
Reported by: rozum — `INBOX.md` `ui-fetch-credentials`, `std-auth-client-half`

S1 landed the vocabulary (`v1/runtime/std/credential.ssc`): a `Credential` names **where** a secret
comes from and carries none. This is the half that makes it do something.

## Why this is not optional

It is the only thing between a generated terminal client and being installable. Measured on rozum's
meeting client, which now matches its hand-written predecessor feature for feature:

```rust
m.insert("meetingsHeaders".to_string(), Value::S("{\"Authorization\":\"Bearer tok\"}".to_string()));
```

The token is **in the binary**. `env()` on this target runs in the emitting process, and the emitted
crate touches `std::env` exactly once, for the snapshot flag. So a binary built once and installed
carries the builder's token: wrong for every other operator, and a secret inside a shipped artifact.
There is no consumer-side workaround — the signal store is fixed at compile time.

## Required behavior

1. `FetchUrlSignal` carries an optional credential — `(kind, source, scheme)`, no secret — beside
   the `headersId`, `urlId` and `tickMs` the earlier terminal-fetch work added.
2. The emitted crate resolves it **at fetch time on the target**: `kind="env"` reads
   `std::env::var(source)`, `kind="file"` reads the file at `source` (with `~` expanded), and
   `kind="literal"` uses `source` as-is.
3. The resolved secret becomes `Authorization: <scheme> <secret>`, so `credentialBasic` works by
   changing the scheme and nothing else.
4. **A missing or unreadable source sends NO Authorization header** rather than an empty or malformed
   one. `Bearer ` with nothing after it is a header that reads as authentication and is not — a 401
   that says "no credential" is easier to diagnose than one that says "bad credential".
5. The credential composes with a `headers` signal: both are applied, and an explicit `Authorization`
   in the headers signal wins — an app that already solved this its own way must not silently change
   behaviour.
6. A fetch with no credential emits exactly what it emits today, and no crate gains a dependency it
   did not have.

## What is deliberately NOT here

`fetchUrlSignal`'s `credential` PARAMETER lives in `v1/runtime/std/ui/primitives.ssc`, which the
`ui-fetch-credentials` claim holds. This spec covers the three layers below it — model, intrinsic
site, emitter — so that adding the parameter is the last small step rather than the whole job. Asked
in the room which way they want it; not working inside another agent's claim either way.

## Verification

Emitter tests: an env credential emits the runtime read and the scheme; a file credential emits the
file read; no credential emits neither. The negative half is what keeps existing crates identical.

Cargo gate: a local server that answers 401 without the header and 200 with `Bearer <value>`. The
probe sets the env var in the child process, runs the fetch, and asserts the body arrived — proving
the value was read **on the target**, not folded in at emit time. Then the same crate with the
variable UNSET must send no header and get the 401, which is the property that keeps a missing
secret honest.

**And the assertion that matters most:** grep the emitted `main.rs` for the secret's value and
assert it is ABSENT. Everything else can pass while the token is still baked; only this proves the
thing the whole design exists for.
