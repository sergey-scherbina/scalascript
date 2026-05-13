# ScalaScript

A meta-programming and specification language with hybrid Markdown/Scala syntax.

## What is ScalaScript?

ScalaScript (`.ssc`) is a language where **Markdown is syntax, not decoration**:

- **Headings** define namespaces and scopes
- **Links** define imports and references
- **Fenced code blocks** are typed expression units
- **YAML front-matter** is the module manifest
- **Inside code regions**: Scala-flavored syntax and type system

````ssc
---
name: hello
version: 0.1.0
---

# Hello Module

A simple greeting module.

## Exports

```scala
def greet(name: String): String =
  s"Hello, ${name}!"
```

Usage: `${greet("World")}`
````

## Design Principles

1. **Reuse, don't invent.** Markdown, YAML, EBNF, standard type theory — use what works.
2. **One source, many targets.** Source semantics are target-independent.
3. **Human and machine readable.** Pleasant for humans, trivially parseable for machines.
4. **No AI at runtime or compile time.** The language stands on its own.

## Targets

| Backend | Status | Description |
|---------|--------|-------------|
| JVM (Scala-CLI) | Planned | First target, mature ecosystem |
| JavaScript | Planned | Browser distribution, zero-install |
| WASM | Future | Portable binary format |
| Native | Future | Direct compilation |

## Documentation

- [Language Specification](SPEC.md)
- [Markdown as Syntax](docs/markdown-as-syntax.md)
- [Architecture](docs/architecture.md)
- [Target Backends](docs/targets.md)

## Examples

- [hello.ssc](examples/hello.ssc) — minimal example
- [typed-data.ssc](examples/typed-data.ssc) — type system showcase
- [imports.ssc](examples/imports.ssc) — module system

## Project Status

**M0 — Specification phase.** No compiler yet. We're defining the language.

See [AGENTS.md](AGENTS.md) for project roadmap and design decisions.

## License

[Apache License 2.0](LICENSE)

## Author

Sergiy (Victorovych) Shcherbyna <sergey.scherbina@gmail.com>
