# hello-backend — in-process Backend plugin sample

A 30-line ScalaScript Backend plugin that returns
`CompileResult.TextOutput("hello, world", ...)` from every `compile()`
call.  Demonstrates the **in-process / ServiceLoader** distribution
shape — the JAR variety where any `Backend` impl on the classpath is
discoverable automatically.

Pair this with the subprocess sample at
`examples/plugins/canned-backend/` to see both distribution shapes
side by side.

## What's here

```text
hello-backend/
├── project.scala                              # scala-cli build config
├── src/main/
│   ├── resources/META-INF/services/
│   │   └── scalascript.backend.spi.Backend    # registers HelloBackend
│   └── scala/mybackend/HelloBackend.scala     # the Backend impl
└── README.md                                  # this file
```

## Build

```bash
cd examples/plugins/hello-backend
scala-cli --power package . --output hello-backend.jar
```

The jar bundles `mybackend.HelloBackend` + the META-INF/services
entry pointing at it.  The SPI types come from the sibling
`backend-spi/` and `ir/` source directories — see
`project.scala` for why (and how to swap for the published artifact
once one exists).

## Use

```bash
# Verify discovery
ssc --plugin hello-backend.jar --list-backends
# ...
# hello          Hello, World  [spi=0.1.0, in-process]

# Inspect
ssc --plugin hello-backend.jar --describe-backend hello
# id:          hello
# displayName: Hello, World
# spiVersion:  0.1.0
# acceptedSources:
# capabilities.features:
# capabilities.outputs:  ExecutionResult

# Run
echo '# Demo
```scalascript
val x = 1
```' > /tmp/demo.ssc
ssc --plugin hello-backend.jar --backend hello run /tmp/demo.ssc
# hello, world
```

## What it demonstrates

- ServiceLoader auto-discovery from a JAR — no central registration.
- The minimal `Backend` surface (7 abstract members + `compile`).
- `Capabilities` declaration — the backend says it produces
  `ExecutionResult` and supports no language features.  Programs
  using any feature get a `Diagnostic.Unsupported` before `compile`
  runs.
- `CompileResult.TextOutput` as the canonical text-bearing reply.
- `--plugin` + `--backend` CLI flow.

## Next steps

- Read [`docs/writing-a-backend.md`](../../../docs/writing-a-backend.md)
  for the full walk-through.
- Read [`docs/backend-spi.md`](../../../docs/backend-spi.md) §4-8 for
  the SPI contract reference.
- See `examples/plugins/canned-backend/` for the subprocess variant.
