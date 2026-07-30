# JS backend — backlog

Can-wait and not-yet-started work whose code lives in `v1/runtime/backend/js/`. When an item is
picked up it moves to `v1/runtime/backend/js/SPRINT.md` as `[~]` and gets a row on the root board —
in the same commit as the claim. Layout: `specs/work-tracking-layout.md`.

Sections below were carried over whole from the flat root `SPRINT.md`/`BACKLOG.md`,
verbatim, on 2026-07-30.

## Custom-backend (StaticJsEmitter) correctness (2026-07-13)

- [ ] **custom-jsemitter-signal-list-literal** — `frontend/custom/StaticJsEmitter.scala`'s
      `jsLiteral` (`registerSignal`, called from `compileEventHandler`) has no
      `List`/`Seq` (or `InstanceV`) case — only bare scalars. Any program
      where a `Signal[List[_]]` (of scalars or case-class instances) is
      referenced by an event handler crashes `ssc run` (both the default
      v2-VM/`custom`-frontend path and `--v1`) at startup, before serving
      anything. Not new — already affects the previously-shipped
      `examples/frontend/keyed-for-demo/keyed-for-demo.ssc` the same way
      (its own docstring's `ssc run` instructions are currently stale).
      `emit-js`/`emit-spa` (the production static-compile pipeline) are
      unaffected. Found 2026-07-13 building `select-from-signal`
      (`specs/std-ui-select.md` § "Reactive options (selectFrom)"); not
      fixed there — see `BUGS.md` § `custom-jsemitter-signal-list-literal`
      for the full repro. A real fix means teaching `jsLiteral` to
      recursively encode `List`/`InstanceV`/`Map` values.
