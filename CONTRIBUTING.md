# Contributing to ScalaScript

Thank you for your interest in contributing to ScalaScript!

## Current Status

ScalaScript is in **M0 — Specification phase**. We are defining the language before writing any compiler code.

## How to Contribute

### Something you ran did not work? Report it

**[Open a report](https://github.com/sergey-scherbina/scalascript/issues/new?template=user-report.yml)** —
you do not need the repository, a checkout, or any knowledge of where the bug lives.

The form asks for four things, and each one is there because it decides whether the report can be
acted on: one line of what went wrong, the **version** (`ssc --version`), the **smallest program**
that shows it with the exact command you ran, and what you **expected versus what happened** —
the real output, not a paraphrase.

Two things that help more than they look like they should: a version where it *used to* work turns a
bug hunt into a bisect, and a program that is already minimal usually skips the reproduction step
entirely.

Please do not try to diagnose where the fault is. Where a fix goes is a conclusion someone reaches by
reading code, and a guess in the report tends to send the reader the same wrong way it sent you.

What happens next: the report enters the inbound queue (`INBOX.md`), a maintainer routes it to
whichever part of the compiler owns the fix, and your issue URL travels with it — so the entry can
still say who to tell when it is fixed. The queue is time-bounded by a gate, so a report cannot sit
in it unnoticed.

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
