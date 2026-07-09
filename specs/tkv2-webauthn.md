# Toolkit v2 WebAuthn Browser Actions

## Overview

Toolkit v2 needs passkey buttons without hand-written browser JavaScript. This
slice adds browser-side WebAuthn actions for the production `emit-spa`/custom
runtime: a `.ssc` button can request server-issued options, invoke
`navigator.credentials.create` or `navigator.credentials.get`, and POST the
base64url-encoded response back to the existing server verifier.

Server-side verification and credential storage already exist in `std/auth.ssc`
for the interpreter/JVM path and in `JsRuntimeWebAuthn` for the JS/Node path.
This spec is only about the browser gesture/client side.

## Interface

New module:

```scalascript
// v1/runtime/std/ui/webauthn.ssc
package std.ui.webauthn

extern def webauthnRegister(beginUrl: String, completeUrl: String, rpName: String,
                            result: Signal[String], error: Signal[String],
                            headers: Signal[String] = emptyHeaders,
                            timeoutMs: Int = 60000,
                            userVerification: String = "preferred"): EventHandler

extern def webauthnAssert(beginUrl: String, completeUrl: String,
                          result: Signal[String], error: Signal[String],
                          headers: Signal[String] = emptyHeaders,
                          timeoutMs: Int = 60000,
                          userVerification: String = "preferred"): EventHandler
```

`beginUrl` is POSTed first and must return JSON.

Registration begin response:

```json
{
  "challenge": "<base64url>",
  "userId": "owner",
  "userName": "owner",
  "displayName": "Owner"
}
```

Only `challenge` and `userId` are required. `userName` and `displayName`
default to `userId`.

Assertion begin response:

```json
{
  "challenge": "<base64url>",
  "allowCredentials": ["<credentialIdBase64url>"]
}
```

For backward compatibility with existing simple `Response.json(Map(...))`
examples, `allowCredentials` may also be a JSON-encoded string containing the
array.

Registration complete POST body:

```json
{
  "clientDataJSON": "<base64url>",
  "attestationObject": "<base64url>"
}
```

Assertion complete POST body:

```json
{
  "clientDataJSON": "<base64url>",
  "authenticatorData": "<base64url>",
  "signature": "<base64url>",
  "credentialId": "<base64url>"
}
```

On a 2xx complete response, the response body is written to `result` and
`error` is cleared. On begin failure, browser API failure, unsupported runtime,
or complete non-2xx, `error` receives a concise message and `result` is left
unchanged.

## Behavior

- [ ] `webauthnRegister` renders as an `EventHandler` and, when clicked in a
      browser, POSTs `beginUrl`, calls `navigator.credentials.create` with a
      decoded challenge, RP name, user identity, ES256 `pubKeyCredParams`,
      `"none"` attestation, timeout, and user-verification policy.
- [ ] Registration encodes `clientDataJSON` and `attestationObject` as
      unpadded base64url strings and POSTs exactly those fields to
      `completeUrl`.
- [ ] `webauthnAssert` POSTs `beginUrl`, calls `navigator.credentials.get` with
      decoded challenge and decoded `allowCredentials`, then POSTs
      `clientDataJSON`, `authenticatorData`, `signature`, and `credentialId` to
      `completeUrl`.
- [ ] Header handling matches existing fetch actions: `headers` is a
      `Signal[String]` containing a JSON object; complete POSTs force
      `Content-Type: application/json` while preserving caller headers.
- [ ] Off-browser invocation does not pretend success. Node/interpreter fallback
      handlers are constructible for tests and static rendering, but clicking
      them writes a clear WebAuthn-unavailable error.
- [ ] Existing server verifier helpers remain green and their public
      declarations in `std/auth.ssc` match the implemented JVM/JS arities.

## Out of scope

- Attestation policy beyond `"none"`.
- Resident keys, discoverable credentials, extensions, transports, or platform
  authenticator selection beyond the `userVerification` string.
- A new language-level Promise/async bridge. WebAuthn is exposed as a UI
  `EventHandler` because the browser API requires a user gesture.
- Replacing the existing WebAuthn server verifier or changing its crypto.

## Design

The browser action belongs in `std/ui/webauthn.ssc` because its public value is
an `EventHandler` and its outputs are `Signal[String]` cells. Server storage and
verification stay in `std/auth.ssc`.

The production implementation lives in `signals.mjs`, the same custom/browser
runtime that owns `fetchAction`, `fetchCaptureAction`, forms, offline signals,
and keyed rendering. `JsGen` capability detection must treat
`webauthnRegister` and `webauthnAssert` as signal-runtime users even when they
are only imported from the new std module.

The action performs a full begin -> browser credential -> complete ceremony so
users do not have to hand-chain async JavaScript. This is intentionally higher
level than a raw `navigator.credentials.*` Promise wrapper; `.ssc` does not yet
have a browser Promise bridge that can be called safely from ordinary button
code.

## Decisions

- **Expose EventHandler actions, not synchronous functions** — chosen because
  WebAuthn must run from a browser user gesture and returns a Promise. Rejected:
  `webauthnRegister(...): String` / `webauthnAssert(...): String`, which would
  either block impossibly in the browser or leak a JS Promise into `.ssc` user
  code.
- **Keep browser client APIs in `std/ui/webauthn.ssc`** — chosen because the
  API depends on `Signal` and `EventHandler`. Rejected: adding these names to
  `std/auth.ssc`, which would couple server auth helpers to the UI runtime.
- **Use begin/complete route actions** — chosen because it matches the existing
  `examples/webauthn-demo.ssc` ceremony and busi-style server challenge flows.
  Rejected: an options-signal-only primitive as the only API, because users
  would still need custom async chaining between fetch and WebAuthn.

## Results

Fill this after implementation verification with exact test commands, counts,
and any runtime gotchas.
