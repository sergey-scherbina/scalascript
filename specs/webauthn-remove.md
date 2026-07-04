# webauthn-remove

## Problem

`WebAuthn`'s credential store (`v1/runtime/http-server/common/.../WebAuthn.scala`) supported
`storePut` / `storeGet` / `storeFind` / `storeUpdateSignCount` but no way to remove a credential —
requested by busi, which wants a "disable Face ID" button in its identity profile page. Currently the
only way to remove an enrolled passkey is to hand-edit the credentials TSV file on the server.

## Fix

Add `WebAuthn.storeRemove(userId: String): Boolean` — removes every credential enrolled for that
user (account-level "disable Face ID", not per-device; the caller doesn't currently track which
credential belongs to which physical device), persists like every other store mutation, returns
whether there was anything to remove.

Threaded through the full stack, mirroring the existing `storeFind` plumbing:
- `WebAuthn.scala`: `storeRemove`
- `auth-plugin/AuthIntrinsics.scala`: JVM intrinsic `webauthnStoreRemove`
- `backend/js/JsRuntimeWebAuthn.scala` + `intrinsics/Auth.scala`: JS mirror + intrinsic mapping
- `std/auth.ssc`: `exports:` entry + `extern def webauthnStoreRemove(userId: String): Boolean`

## Verify

- `runtimeServerCommon/testOnly scalascript.server.WebAuthnPersistTest` — 4 new cases (clears all
  credentials, false on empty user, only affects the named user, persists across a reload).
- End-to-end via a fresh `install.sh --dev` build, both backends: `./bin/ssc --plugin
  .../auth-plugin.sscpkg <script>` (tree-walking interpreter) and `./bin/ssc emit-js --plugin
  .../auth-plugin.sscpkg <script> | node` — both print the same before/after/removed-again sequence.
