# Contributing to ScalaScript

Thank you for your interest in contributing to ScalaScript!

## Current Status

ScalaScript is in **M0 — Specification phase**. We are defining the language before writing any compiler code.

## How to Contribute

### Specification Feedback

The most valuable contributions right now are:

1. **Review the specification** in [SPEC.md](SPEC.md) and open issues for:
   - Ambiguities or unclear sections
   - Edge cases not covered
   - Inconsistencies between sections

2. **Review examples** in `examples/` and suggest:
   - Missing use cases
   - Clearer demonstrations of features
   - Real-world scenarios

3. **Grammar review** — check `grammar/scalascript.ebnf` for:
   - Parsing ambiguities
   - Missing productions
   - Conflicts with Markdown parsing

### Code Contributions (Future)

Once we reach M1 (JVM frontend), we'll accept code contributions. Until then, focus on specification quality.

## Style Guidelines

### Specification Documents

- Use clear, precise language
- Provide examples for every construct
- Reference related sections explicitly
- Mark open questions with `[OPEN]` prefix

### Example Files (`.ssc`)

- Keep examples minimal but complete
- One concept per example file
- Include comments explaining the demonstrated feature

## Communication

- Open issues for discussion
- Use English for technical discussions
- Russian/Ukrainian welcome for clarifications

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.
