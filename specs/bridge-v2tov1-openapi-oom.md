# Bounded imported JSON bridge conversion

## Problem

The v2 compatibility frontend may evaluate an exported value in one imported
module and consume it through another imported module. Finite nested JSON/API
trees must cross that bridge without recursive duplication or retained runtime
state. A regression between assembled ScalaScript `3666ccb7a` and `495467456`
causes busi's imported OpenAPI value to exhaust a 2 GiB heap in
`PluginBridge.v2ToV1` before its JSON assertions begin.

The real reproducer is busi `tests/v2/api.ssc`: it imports the API serializers
and `openApiJson`, evaluates ordinary domain serializers first, then parses and
navigates the OpenAPI document. It passes unchanged on the old assembled pin
and fails on the new one. In-process test runners are insufficient evidence;
the assembled CLI plus plugin distribution is the contract.

## Contract

- Every finite acyclic v2 record field converts to its v1/plugin representation
  once per field occurrence; the named, positional and array layouts reuse that
  exact converted value rather than recursively rebuilding it. Conversion must
  not create cycles or repeatedly expand a stable global/import environment.
- Imported `List`, data values, tuples, maps and native JSON wrappers preserve
  shape and scalar values exactly.
- Mutable/concurrent global storage must not change value identity, conversion
  ownership or the distinction between a global cell and its current value.
- The fix belongs in the bridge/runtime. Splitting busi's OpenAPI, raising heap,
  disabling schemas or bypassing typed JSON are rejected workarounds.
- HTTP transport concurrency remains safe and the fair per-interpreter gate and
  fast request/session behavior remain intact.

## Verification

1. Bisect with assembled distributions and the unchanged busi test to identify
   the first bad commit.
2. Add a multi-module fixture: one module exports a sufficiently nested JSON/API
   tree, another imports, parses and navigates it, and the assembled CLI reaches
   an exact completion sentinel under a bounded heap.
3. Add a focused unit regression at the owning bridge/runtime boundary.
4. Run the affected module suite and relevant conformance cases.
5. Assemble the final runtime and pass busi `tests/v2/api.ssc`, OpenAPI drift,
   full JVM and JS suites, Vault HTTP/restart/leakage and canonical browser E2E.

## Behavior

- [x] The first runnable bad boundary and precise recursive expansion are
      identified: self-hosted JSON first exposes the pre-existing triple
      `InstanceV` field conversion (`efb32a11f` assembled boundary).
- [x] Imported nested JSON conversion terminates under a bounded heap and keeps
      exact shape and navigation results.
- [x] Registered record layouts share each converted field by identity across
      named, positional and array access, independent of global storage type.
- [ ] The published runtime passes both ScalaScript and busi release gates.
