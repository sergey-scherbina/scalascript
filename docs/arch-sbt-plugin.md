# sbt-scalascript Plugin — Full Completion Spec

Status: **partially implemented**. Phases 1 and 2 landed on 2026-05-29.
Tracked as `arch-sbt-plugin` milestone in `BACKLOG.md`.
Current state: `tools/sbt-plugin/` contains the existing `sscGenerateFacade`
task plus Phase 1 source-convention compilation (`sscSourceDirectories`,
`sscCompile`, `sscBackend`, `sscExtraArgs`) and Phase 2 linking (`sscLink`,
`sscLinkedJar`). This spec covers the remaining plugin surface needed for
standalone ScalaScript projects.

---

## 1. Goals

Make `addSbtPlugin("io.scalascript" % "sbt-scalascript" % "1.0.0")` the
complete answer to "how do I use ScalaScript in a new Scala/sbt project?"
Specifically:

- **.ssc source convention** — `src/main/scalascript/` and
  `src/test/scalascript/` picked up automatically.
- **Compile + Link tasks** — `sscCompile` and `sscLink` wired into the
  standard `compile` / `packageBin` lifecycle.
- **Test integration** — `sbt test` discovers and runs `.ssc` tests.
- **REPL / Run / Watch** — `sscRepl`, `sscRun`, `sscWatch` tasks.
- **LSP / BSP wiring** — Metals and IntelliJ pick up `.ssc` sources.
- **Dep resolution** — front-matter `dependencies:` maps to Coursier.
- **Cross-platform targets** — JVM / JS / WASM per `sscBackend` setting.

## 2. Non-goals

- Replacing the existing `sscGenerateFacade` task (Tier 3 Scala interop);
  it stays unchanged.
- Authoring `.ssc` files inside an sbt build itself (meta-build use case).
- sbt 1.x compatibility — target sbt 2.x (Scala 3 first).
- Removing the `ssc` CLI; the plugin delegates to it.

## 3. Architecture

### 3a. Plugin structure

```
tools/sbt-plugin/src/main/scala/scalascript/sbt/
  ScalascriptPlugin.scala      // AutoPlugin, keys, task wiring
  SscRunner.scala              // fork `ssc` binary, capture output
  SscTestFramework.scala       // sbt TestFramework adapter
  BspIntegration.scala         // emit .bsp/scalascript.json
```

`ScalascriptPlugin` extends `AutoPlugin` with `trigger = noTrigger` (opt-in).

Implementation note: the current source file is still
`ScalascriptInteropPlugin.scala` to preserve the existing
`sbt-scalascript-interop` artifact and scripted tests. `SscRunner.scala` landed
in Phase 1 and is shared by `sscCompile` and `sscGenerateFacade`.

### 3b. Settings keys

```scala
object ScalascriptPlugin extends AutoPlugin {
  object autoImport {
    val sscSourceDirectories = settingKey[Seq[File]]("ScalaScript source dirs")
    val sscBinary            = settingKey[String]("Path to ssc binary (default: ssc on PATH)")
    val sscBackend           = settingKey[String]("Target backend: jvm | js | wasm | node (default: jvm)")
    val sscBackends          = settingKey[Seq[String]]("For cross-build: all target backends")
    val sscExtraArgs         = settingKey[Seq[String]]("Extra args passed to ssc compile/link")
    val sscArtifactDir       = settingKey[File]("Output dir for ssc artifacts")
    val sscLinkedJar         = settingKey[File]("Runnable JAR produced by ssc link")
    val sscCompile           = taskKey[Seq[File]]("Compile .ssc sources")
    val sscLink              = taskKey[File]("Link .ssc artifacts")
    // existing:
    val sscGenerateFacade    = taskKey[Seq[File]]("Generate Scala facade from .scim")
  }
}
```

Default values:
```scala
sscSourceDirectories := Seq(
  (Compile / sourceDirectory).value / "scalascript",
  (Test    / sourceDirectory).value / "scalascript",
)
sscBinary    := "ssc"
sscBackend   := "jvm"
sscArtifactDir := (Compile / target).value / "ssc-artifacts"
sscLinkedJar := (Compile / sscArtifactDir).value / "linked.jar"
```

### 3c. Compile task

```scala
val sscCompile = taskKey[Seq[File]]("Compile .ssc sources")

sscCompile := {
  val sources = (Compile / sscSourceDirectories).value.flatMap(_.listFiles.filter(_.ext == "ssc"))
  if (sources.isEmpty) Seq.empty
  else {
    SscRunner.run(
      binary  = sscBinary.value,
      args    = Seq("build", "--incremental", "--backend", sscBackend.value,
                    "--output", sscArtifactDir.value.toString)
            ++ sources.map(_.toString)
            ++ sscExtraArgs.value,
      logger  = streams.value.log,
    )
    (sscArtifactDir.value ** "*.class").get
  }
}

Compile / compile := (Compile / compile).dependsOn(sscCompile).value
```

Incremental: `ssc build --incremental` uses the `.scim` cache inside
`sscArtifactDir`. Phase 1 currently runs once per configured source directory
that contains at least one `.ssc` file and returns all files under
`sscArtifactDir`; fine-grained `Tracked.inputChanged` guards remain a future
optimization.

### 3d. Link task

```scala
val sscLink = taskKey[File]("Link .ssc artifacts into a runnable JAR")

sscLink := {
  val out = sscArtifactDir.value / "linked.jar"
  SscRunner.run(
    binary = sscBinary.value,
    args   = Seq("link", "--backend", sscBackend.value,
                 "--output", out.toString,
                 sscArtifactDir.value.toString),
    logger = streams.value.log,
  )
  out
}

Compile / packageBin := (Compile / packageBin).dependsOn(sscLink).value
```

Phase 2 implementation: `Compile / sscLink` first depends on
`Compile / sscCompile`, then invokes `ssc link --backend <sscBackend>
--output <sscLinkedJar> <sscArtifactDir>` when artifacts exist. Projects with
no `.ssc` sources skip linking and still package normally. `sscExtraArgs` are
appended to both compile and link invocations.

### 3e. Test integration

`ssc test` already exists as a CLI subcommand.  The sbt integration:

1. `sscTest` task forks `ssc test --backend jvm --output-format junit-xml
   --output <target>/ssc-test-results/` and parses the JUnit XML.
2. Results are fed into sbt's `testGrouping` / `executeTests` via a
   `SscTestFramework` wrapper implementing `sbt.testing.Framework`.
3. `Test / test` depends on `sscTest`.

### 3f. REPL / Run / Watch

```scala
val sscRepl  = taskKey[Unit]("Start ScalaScript REPL")
val sscRun   = inputKey[Unit]("Run a .ssc file")
val sscWatch = taskKey[Unit]("Watch + recompile on change")
```

All three fork the `ssc` binary and inherit stdin/stdout so interactive
REPL works.  `sscWatch` uses sbt's `watchSources` to trigger on `*.ssc` changes.

### 3g. BSP / LSP wiring

`BspIntegration.scala` emits `<project>/.bsp/scalascript.json` on
`sscBspSetup` task:

```json
{
  "name": "scalascript",
  "version": "1.0.0",
  "bspVersion": "2.1.0",
  "languages": ["scalascript"],
  "argv": ["ssc", "lsp", "--project", "<baseDirectory>"]
}
```

Metals / IntelliJ pick this up via BSP discovery and route `.ssc`
diagnostics to `ssc lsp`.

### 3h. Dep resolution

Front-matter `dependencies: { foo: "dep:io.example/myLib:1.0" }` is parsed
by `ssc build`; the plugin additionally exposes these as sbt
`libraryDependencies` entries so Coursier downloads them into the Coursier
cache and they appear on the JVM test classpath.  Requires
`arch-distribution.md` Phase 2 (Coursier wiring) to be implemented first.

## 4. Migration

- The existing `sscGenerateFacade` task and all four scripted tests are
  preserved unchanged.
- Plugin renamed from `sbt-scalascript-interop` to `sbt-scalascript`; the
  old artifact ID is kept as an alias for one release cycle.
- Group ID changes from `org.scalascript` to `io.scalascript` to align with
  runtime artifacts (see `arch-distribution.md §3f`).
- Existing users of `sscGenerateFacade` add one `addSbtPlugin` line change.

## 5. Phases

### Phase 1 — Source convention + `sscCompile`

- `sscSourceDirectories` setting; `sscCompile` task; wire into `compile`.
- `SscRunner` shared command runner.
- Scripted coverage for `sbt compile` invoking `ssc build --incremental`.
  The scripted test uses a local mock binary for determinism, while the task
  itself calls the real CLI contract.
- Deliverable: `sbt compile` compiles `.ssc` files from `src/main/scalascript/`.
  ✓ Landed 2026-05-29.

### Phase 2 — `sscLink` + `packageBin`

- `sscLink` task; wire into `Compile / packageBin`.
- Deliverable: `sbt package` produces a runnable JAR with ScalaScript output.
  ✓ Landed 2026-05-29.

### Phase 3 — Test integration

- `SscTestFramework`; `sscTest`; JUnit XML parsing; `Test / test` wire.
- Scripted test: create project with `.ssc` test file, `sbt test` passes.

### Phase 4 — REPL / Run / Watch + BSP

- `sscRepl`, `sscRun`, `sscWatch` tasks.
- `BspIntegration` / `sscBspSetup`.
- End-to-end smoke test: `sbt sscRepl` opens a REPL in a subprocess.

### Phase 5 — Dep resolution + Maven publication

- Dep resolution wiring (depends on `arch-distribution.md` Phase 2).
- Maven Central publish (`io.scalascript:sbt-scalascript:1.0.0`).
- sbt Plugin Portal registration.

## 6. Testing strategy

- Scripted tests for each phase in
  `tools/sbt-plugin/src/sbt-test/sbt-scalascript/`.
- Phase 1: `compile-sources/` — one `.ssc` file, `sbt compile` invokes
  `ssc build --incremental` and writes an artifact marker. ✓ Landed
  2026-05-29.
- Phase 2: `link-jar/` — `sbt package` produces a JAR that `java -jar` runs.
- Phase 3: `run-tests/` — `.ssc` test file, `sbt test` green.
- Phase 4: `bsp/` — `.bsp/scalascript.json` present after `sbt sscBspSetup`.
- Phase 5: `dep-resolution/` — front-matter dep resolved via Coursier.

## 7. Open questions

1. Should `sscCompile` fork `ssc` or call it in-process via a launcher
   classloader?  Fork is safer (no classpath pollution); in-process would
   enable better incremental signals.
2. Should `sscBackends` (plural) produce parallel outputs in one `sbt compile`,
   or require separate sbt configurations (like `js` / `jvm` cross-build)?
3. Should `sscRepl` attach to sbt's stdin (blocking) or spawn a terminal emulator?
