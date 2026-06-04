# canned-backend — subprocess plugin smoke test

A 50-line ScalaScript backend plugin that returns a fixed
`TextOutput("// canned-backend produced this", "text", Nil)` on every
`compile` call.  Exists to prove the SubprocessBackend wire protocol
works end-to-end without needing a real codegen.

## Running by hand

```bash
cd examples/plugins/canned-backend

# Spawn the plugin directly:
scala-cli run plugin.scala

# Then type one JSON request per line, e.g.:
{"method":"describe","id":1}
# Plugin replies on stdout:
{"id":1,"result":{"id":"canned",...}}
```

## Running via BackendRegistry

```bash
# Drop a symlink (or copy) into the discovery path:
mkdir -p ~/.scalascript/compiler/plugins
ln -s "$(pwd)" ~/.scalascript/compiler/plugins/canned

# Then:
ssc --list-backends
# canned         Canned (smoke)  [spi=0.1.0, subprocess (stdio-json)]
```

## What it demonstrates

- Newline-delimited JSON framing on stdin/stdout.
- The three core methods every plugin must implement (`describe`,
  `compile`, `shutdown`).
- Canonical Response envelope (`id` + `result` or `error`).
- A plugin.yaml manifest discoverable by `~/.scalascript/compiler/plugins/`.

The wire shape is documented in `specs/backend-spi-protocol.md`.
