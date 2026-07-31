# Contributing to ScalaScript

Thank you for your interest in contributing to ScalaScript!

## Current Status

ScalaScript is in **M0 — Specification phase**. We are defining the language before writing any compiler code.

## How to Contribute

### Something you ran did not work? Report it

**[Open a report](https://github.com/sergey-scherbina/scalascript/issues/new?template=user-report.yml)** —
you do not need the repository, a checkout, or any knowledge of where the bug lives.

**Only the first box is required** — one line saying what went wrong. Everything else helps and
none of it blocks you: a partial report is worth a great deal, and a report you could not file is
worth nothing. Send what you have.

The boxes that save the most time when you can fill them: the **version**, the **smallest program**
that shows it with the exact command, and what you **expected versus what happened** in the real
output rather than a paraphrase. Two that punch above their weight: a version where it *used to*
work turns a bug hunt into a bisect, and a case that is already minimal usually skips reproduction
entirely.

**Tell us what you worked out, including where you think the bug is.** If you bisected, read the
source, or found a workaround that failed, that is real work and we want it — there is a box for it.
It is recorded as *your* finding and we reach our own conclusion separately, so a guess that turns
out wrong costs you nothing and an accurate one can save hours. (This changed on 2026-07-31: the form
used to ask people not to diagnose, which threw away exactly this. It was aimed at a rule about how
we ROUTE entries, not at what you are allowed to say, and it should never have been pointed at you.)

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
