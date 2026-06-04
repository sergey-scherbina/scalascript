# Native Plugin Binary Guide

This guide explains how to compile a ScalaScript backend plugin to a **native binary**
so it can run alongside the native `ssc` binary without a JVM.

```
ssc (native)  →  subprocess wire protocol  →  my-plugin (native)
```

Both `ssc` and the plugin binary communicate over stdin/stdout using the existing
stdio-JSON wire protocol — exactly the same as the JVM subprocess mode.  No changes
to the plugin implementation are needed.

## When to use this

- You are distributing your plugin to users who run the native `ssc` binary and
  want a **fully JVM-free** setup.
- Your plugin has a stable class surface (reflection config maintenance is
  manageable).
- Build time (5–15 min per platform in CI) is acceptable for your release cadence.

If your users already have a JVM, the simpler `--plugin foo.jar` path via
`ssc-plugin-host.jar` (Phase 3) works fine and requires no native build.

## Architecture

A native plugin binary is built from the **same plugin code** but with an extra
`SubprocessHost` entry point that:

1. Loads the plugin's `Backend` implementation directly (no URLClassLoader needed).
2. Enters the stdio-JSON wire protocol loop.

`ssc` discovers native plugin binaries via the standard `plugin.yaml` manifest
(the same mechanism as any other subprocess plugin).

## Prerequisites

- GraalVM 21+ with the `native-image` component:
  ```bash
  sdk install java 21.0.2-graalce      # SDKMAN
  # or
  brew install --cask graalvm-jdk21    # Homebrew
  native-image --version               # verify
  ```
- `sbt` with `sbt-native-packager` 1.10+.
- Your plugin source builds cleanly with Scala 3.

## Step 1 — Add the entry point

Your plugin needs a `SubprocessHost`-compatible main class.  The easiest approach
is to add `scalascript.plugin.SubprocessHost` to your plugin's assembly — it is
already in `ssc-plugin-host.jar` — but for a fully native build you need the
sources on the classpath.

**Option A (recommended): depend on `ssc-plugin-host`.**

```scala
// in your plugin's build.sbt
libraryDependencies += "org.scalascript" %% "ssc-plugin-host" % sscVersion
```

Then set your main class to `scalascript.plugin.SubprocessHost`.  The host
discovers your `Backend` via `ServiceLoader` from the same classpath, so no extra
wiring is needed.

**Option B: inline the entry point.**

Copy `SubprocessHost.scala` from `tools/plugin-host/src/main/scala/` into your
plugin project.  Useful if you cannot take the library dependency.

## Step 2 — Enable GraalVM native-image in sbt

Add `sbt-native-packager` to `project/plugins.sbt`:

```scala
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.10.4")
```

Enable the plugin and configure the build in your plugin's `build.sbt`:

```scala
lazy val myPlugin = project
  .in(file("."))
  .enablePlugins(GraalVMNativeImagePlugin)
  .settings(
    name := "my-ssc-plugin",

    // The SubprocessHost entry point (Option A above)
    GraalVMNativeImage / mainClass := Some("scalascript.plugin.SubprocessHost"),

    graalVMNativeImageOptions ++= Seq(
      "--no-fallback",
      "--initialize-at-build-time=scala",
      "--initialize-at-build-time=scalascript",
      "--initialize-at-build-time=myorg.myplugin",          // your package(s)
      "-H:ReflectionConfigurationFiles=native-image/reflect-config.json",
      "-H:ResourceConfigurationFiles=native-image/resource-config.json",
      "-H:+ReportExceptionStackTraces",
      "-J-Xmx4g"
    )
  )
```

## Step 3 — Write the reflection and resource configs

Plugin reflection config is much simpler than `ssc`'s — you only need entries for:

- Your `Backend` implementation class (for `ServiceLoader`).
- Any classes you access via reflection (typically none, if you avoid Java beans).
- Scala / upickle runtime (if not already covered by `--initialize-at-build-time`).

**`native-image/reflect-config.json` (minimal):**

```json
[
  {
    "name": "myorg.myplugin.MyBackend",
    "allDeclaredConstructors": true,
    "allPublicMethods": true
  }
]
```

**`native-image/resource-config.json`:**

```json
{
  "resources": {
    "includes": [
      {"pattern": "META-INF/services/.*"},
      {"pattern": "META-INF/MANIFEST.MF"}
    ]
  },
  "bundles": []
}
```

### Generating config automatically

Run the native-image agent against your plugin's fat JAR to capture any reflection
calls you may have missed:

```bash
# 1. Build fat JAR
sbt assembly

# 2. Run agent — exercise your plugin's main paths
java -agentlib:native-image-agent=config-merge-dir=native-image \
  -cp target/scala-3.x/my-plugin.jar \
  scalascript.plugin.SubprocessHost target/scala-3.x/my-plugin.jar &
HOST_PID=$!

# Send a describe request to exercise the handshake
echo '{"method":"describe","params":{},"id":1}' | nc localhost 0  # adjust

kill $HOST_PID

# 3. Review generated native-image/reflect-config.json
# Remove overly broad entries (e.g. java.lang.** wildcards)
```

## Step 4 — Build the native binary

```bash
sbt myPlugin/graalvm-native-image:packageBin
# Output: target/graalvm-native-image/my-ssc-plugin
```

First build: 5–15 minutes.  Subsequent incremental builds are faster if sources
didn't change significantly.

Cross-platform binaries must be built on the target platform (Linux x86_64,
macOS arm64, macOS x86_64).  Use GitHub Actions with a matrix:

```yaml
jobs:
  native:
    strategy:
      matrix:
        include:
          - os: ubuntu-latest
            artifact: my-ssc-plugin-linux-x86_64
          - os: macos-latest
            artifact: my-ssc-plugin-macos-arm64
          - os: macos-13
            artifact: my-ssc-plugin-macos-x86_64
    runs-on: ${{ matrix.os }}
    steps:
      - uses: actions/checkout@v4
      - uses: graalvm/setup-graalvm@v1
        with:
          java-version: '21'
          distribution: 'graalvm'
      - uses: sbt/setup-sbt@v1
      - run: sbt myPlugin/graalvm-native-image:packageBin
      - uses: softprops/action-gh-release@v1
        with:
          files: target/graalvm-native-image/my-ssc-plugin
          name: ${{ matrix.artifact }}
```

## Step 5 — Write a plugin.yaml manifest

Tell `ssc` about your native plugin via the standard subprocess manifest.  Place
`plugin.yaml` in one of the search paths (`~/.scalascript/compiler/plugins/` or
a directory passed via `--plugin-dir`):

```yaml
id: my-ssc-plugin
displayName: My ScalaScript Plugin
spiVersion: "0.1.0"
executable: /path/to/my-ssc-plugin    # absolute path to the native binary
protocol: stdio-json
```

Or ship it alongside the binary and register the directory:

```bash
ssc --plugin-dir /opt/my-plugin/  run myfile.ssc
```

## Step 6 — Verify

```bash
# List all discovered backends (native ssc)
ssc --list-backends
# Should show: my-ssc-plugin   My ScalaScript Plugin  [spi=0.1.0, subprocess (stdio-json)]

# Run with your backend
ssc --backend my-ssc-plugin run myfile.ssc
```

## Known limitations

| Issue | Notes |
|-------|-------|
| Reflection config drift | Rerun the agent after significant dependency changes. |
| Binary size | 60–100 MiB vs a JAR + JVM.  The trade-off is eliminating the JVM requirement. |
| Debug ergonomics | Stack traces are less informative in native mode.  Add `-ea` during dev with the JVM path. |
| No hot-reload of plugin code | Rebuild + restart `ssc` to pick up plugin changes. |
| Cross-compilation | Build separately per platform; no universal binaries. |

## Compared to JAR plugins (via ssc-plugin-host)

| | JAR plugin + ssc-plugin-host | Native plugin binary |
|---|---|---|
| Build complexity | Low — standard `sbt assembly` | Medium — GraalVM + reflection config |
| Requires JVM at runtime | Yes (for the host subprocess) | No |
| Cold-start latency | ~300 ms JVM startup | ~5 ms |
| Distribution | Single fat JAR | Platform-specific binary per OS/arch |
| Debug | Standard JVM tools | Limited |

For most plugin authors, **JAR + ssc-plugin-host** is the right default.  Native
binaries are an opt-in optimisation for distribution-sensitive scenarios.

## Related

- `native-image-configs/` — reflection and resource configs for `ssc` itself (reference)
- `tools/plugin-host/` — `SubprocessHost` source
- `docs/specs/backend-spi.md` — Backend SPI contract
- `BACKLOG.md §CLI — native binary` — implementation notes for all four phases
