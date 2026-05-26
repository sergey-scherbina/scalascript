# NativeContext State Bag

## Goals

- Add a generic state channel to `NativeContext` so future std plugins do not
  need a new SPI method for every small runtime setting.
- Support both interpreter-scoped shared state and execution-scoped state used
  by block constructs such as `httpClient { ... }`.
- Keep existing named methods (`httpBaseUrl`, `setHttpTimeout`,
  `setMaxWsConnections`, etc.) source- and binary-compatible at the Scala
  source level.

## Non-goals

- This phase does not remove existing named methods from `NativeContext`.
- This phase does not expose the state bag directly to `.ssc` user code.
- This phase does not migrate route registration, database access, validation,
  or mount/eval hooks to generic keys; those remain typed methods until a
  separate migration proves the generic form is enough.

## Architecture

`NativeContext` now has two key/value layers:

- `featureGet` / `featureSet` / `featureRemove` / `featureUpdate` for
  interpreter-scoped shared plugin state.
- `featureLocalGet` / `featureLocalSet` / `featureLocalRemove` /
  `featureLocalUpdate` for execution-scoped state backed by `ThreadLocal`
  slots.

Keys are strings. Std plugins should use reverse-domain or module-prefixed
keys such as `scalascript.http.timeoutMs` to avoid collisions.

The interpreter implements both layers once and exposes them to all installed
native intrinsics. The minimal HTTP context used by interpreter HTTP effects
shares the same backing stores.

HTTP client configuration is the first migrated user. Public key constants live
in `NativeContextFeatureKeys`:

- `scalascript.http.baseUrl`
- `scalascript.http.timeoutMs`
- `scalascript.http.maxRetries`
- `scalascript.http.retryDelayMs`

The old named `NativeContext` methods now delegate to these keys, so existing
plugins keep compiling while new plugin code can use the bag directly.

## Migration

Existing plugins do not need to change.

New plugins should prefer the generic methods for small settings and caches
that do not deserve a first-class SPI hook. Keep typed SPI methods for
cross-cutting capabilities with real contracts, such as route registration or
database connection lookup.

## Phases

1. **Landed** — add shared + local feature state APIs to `NativeContext`,
   implement them in interpreter contexts, and route HTTP client scoped state
   through feature-local keys.
2. Migrate small plugin-specific knobs opportunistically when touching those
   plugins.
3. Reassess whether any existing named methods can be deprecated once no std
   plugin relies on them directly.

## Testing Strategy

- SPI unit test for default no-op behavior and update helpers.
- Interpreter/plugin test proving feature state persists across native calls
  and feature-local state restores after `httpClient {}` scopes.
- Existing HTTP client and plugin suites remain the regression net for named
  accessor compatibility.

## Open Questions

- Should keys eventually be typed with a small `NativeFeatureKey[A]` wrapper,
  or are string keys enough for the std-plugin layer?
- Should shared feature state offer atomic typed helpers beyond
  `featureUpdate`, or should plugins own their own concurrency primitives?
