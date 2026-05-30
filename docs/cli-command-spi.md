# CLI Command SPI — spec

## Motivation

`ssc`'s entry point dispatched ~55 subcommands through one hand-maintained
`match` in `Main.scala`, and each command was a free-standing top-level
function. Adding a command meant editing the match, the usage text, and
Main.scala. This spec introduces a small **command SPI** so each command is a
self-contained, self-registering module and dispatch becomes a registry
lookup.

It follows the project's existing ServiceLoader idiom (`Backend`,
`SourceLanguage`, `PaymentProvider`, …): a trait + `META-INF/services`
discovery.

## Scope / non-goals

- Commands **stay inside the `cli` module** for now. They are tightly coupled
  to cli-internal helpers (`compileViaBackend`, `expectText`, the JvmGen/JsGen
  pipeline), so they cannot yet move to other modules or ship as external
  command plugins. The SPI is the contract and the discovery mechanism; the
  *cross-module plugin command* capability it enables is future work, unlocked
  only once a command's dependencies are decoupled.
- Migration is **incremental**: command providers initially delegate to the
  existing `xxxCommand` functions. Moving each command's body into its provider
  is follow-up work and need not happen atomically.

## Contract

```scala
package scalascript.cli

/** A single `ssc` subcommand. Providers are discovered via ServiceLoader, so
 *  each must be a concrete class with a public no-arg constructor (a Scala
 *  `object` cannot be loaded by ServiceLoader). */
trait CliCommand:
  /** Primary subcommand token, e.g. "build". */
  def name: String
  /** Additional tokens that map to this command, e.g. "--help", "-h". */
  def aliases: List[String] = Nil
  /** One-line summary for `ssc help`. May be empty during incremental migration. */
  def summary: String = ""
  /** Execute with the post-subcommand argument list (i.e. `args.tail`). */
  def run(args: List[String]): Unit
```

### Registry

```scala
object CommandRegistry:
  def all: List[CliCommand]                 // ServiceLoader-discovered, cached
  def lookup(name: String): Option[CliCommand]
```

Discovery uses `ServiceLoader.load(classOf[CliCommand], <cli classloader>)`,
backed by `META-INF/services/scalascript.cli.CliCommand`. Name + alias
collisions are resolved first-wins with a stderr warning (none expected in
the in-module set).

### Dispatch

`dispatchCommand` reduces to:

```
CommandRegistry.lookup(args.head) match
  case Some(c) => c.run(args.tail)
  case None    => scriptCommand(args.head, args.tail)   // unknown ⇒ project script
```

Three behaviours that don't fit the plain `name → run` shape are themselves
modelled as `CliCommand`s rather than left as special cases in the match:

- `help` / `--help` / `-h` → `HelpCommand` (aliases) → `printUsage()`.
- `--list-backends` → `ListBackendsCommand`.
- `install` → `InstallCommand`, whose `run` keeps the existing conditional:
  no args or `--prefix` ⇒ self-install; otherwise ⇒ `plugin install` shortcut.

The only thing remaining in `dispatchCommand`'s default branch is the
"unknown token ⇒ run a project script" fallback (`scriptCommand`), which is
intentionally not a named command.

## Layout

- `cli/.../CliCommand.scala` — trait.
- `cli/.../CommandRegistry.scala` — ServiceLoader registry.
- `cli/.../commands/*.scala` — provider classes (thin wrappers during migration).
- `cli/.../resources/META-INF/services/scalascript.cli.CliCommand` — provider list.

Command functions that providers delegate to are widened from file-private to
`private[cli]` so providers in sibling files can reach them.

## Testing

- Existing `cli` suite must stay green (the dispatch behaviour is unchanged).
- A registry test asserts: every legacy subcommand token resolves to a
  provider; no duplicate name/alias; `ServiceLoader` discovers the full set.

## Status / done

- ✅ `ssc help`'s command listing is generated from the registry
  (`Help.renderCommands` over `name`/`summary`/`category`/`details`);
  `printUsage` keeps only the curated static tail (platform targets, package
  flags, logging, examples).
- ✅ Command bodies migrated into their `CliCommand` classes — the `xxxCommand`
  free functions are gone. Only thin adapters remain (`DebugCmd`/`OauthCmd`/
  `ToolchainCmd` wrap existing objects; `Help`/`ListBackends`/`Install` specials).

## Plugin-provided commands (§plugin-commands) — design, deferred

The discovery path already supports out-of-module providers: any `CliCommand` on
the classpath with a `META-INF/services/scalascript.cli.CliCommand` entry is
picked up. The blocker is **coupling, not discovery** — every real command's
`run` reaches cli-internal helpers (`compileViaBackend`, `expectText`, artifact
IO, `Routes`, the JvmGen/JsGen pipeline) that are `private[cli]`. A plugin jar
can't call those, so today a plugin could only contribute a command that does no
real compiler work.

### Proposed shape (when a consumer exists)

Introduce a stable `CommandContext` — the curated surface a command needs — and
pass it to `run`:

```scala
trait CommandContext:
  def compile(target: String, src: os.Path): CompileResult
  def expectText(r: CompileResult, what: String): String
  def libPath: Option[os.Path]
  def stdout: java.io.PrintStream
  def stderr: java.io.PrintStream
  // … only what plugin commands legitimately need, kept small and versioned

trait CliCommand:
  def name: String
  // … name/aliases/summary/category/details as today …
  def run(ctx: CommandContext, args: List[String]): Unit
```

In-tree commands get `ctx` injected by the launcher; the cli-internal helpers
move behind a `DefaultCommandContext` in the `cli` module. Plugin commands
depend only on a small `scalascript-cli-spi` artifact exposing `CliCommand` +
`CommandContext` (no dependency on Main).

### Migration sketch

1. Extract `scalascript-cli-spi` (traits `CliCommand`, `CommandContext`) as its
   own module; the cli module depends on it.
2. Change `run(args)` → `run(ctx, args)`; update all in-tree commands (the
   context replaces direct `private[cli]` calls).
3. `DefaultCommandContext` in cli wires the real helpers; dispatch passes it.
4. `.sscpkg` install puts plugin jars on the classpath → ServiceLoader already
   finds their `CliCommand`s.

### Why deferred

This is a real SPI surface with a versioning commitment, and changing `run`'s
signature touches every command. **There is no concrete plugin that needs to
contribute a command yet**, so building it now would be speculative
infrastructure — a wide, risky change for zero current benefit. Land it when the
first real third-party / `std` plugin command appears, so the `CommandContext`
surface is designed against that command's actual needs rather than guessed.

## Other future work

- Further split Main.scala's remaining shared helper clusters (build pipeline,
  artifact IO) into focused files — behavior-preserving, low priority.
