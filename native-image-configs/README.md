# GraalVM native-image configuration

Configuration files for building the `ssc` native binary via GraalVM native-image.

## Files

| File | Purpose |
|------|---------|
| `reflect-config.json` | Classes accessed via Java reflection at runtime |
| `resource-config.json` | Resources (META-INF/services, logger-sources) included in the image |

## Building the native binary

Prerequisites: GraalVM 21+ with native-image component.

```bash
sbt cli/graalvm-native-image:packageBin
# Output: tools/cli/target/graalvm-native-image/ssc
```

## Regenerating the config

Run the native-image-agent against real CLI paths to capture any reflection
calls that aren't yet in `reflect-config.json`:

```bash
# Build the fat JAR first
sbt cli/assembly

# Run the agent against several CLI paths
java -agentlib:native-image-agent=config-merge-dir=native-image-configs \
  -jar tools/cli/target/scala-3.8.3/ssc.jar run examples/hello.ssc

java -agentlib:native-image-agent=config-merge-dir=native-image-configs \
  -jar tools/cli/target/scala-3.8.3/ssc.jar compile examples/hello.ssc

java -agentlib:native-image-agent=config-merge-dir=native-image-configs \
  -jar tools/cli/target/scala-3.8.3/ssc.jar check examples/hello.ssc

# Review and curate the generated configs (remove overly broad entries)
# Then rebuild
sbt cli/graalvm-native-image:packageBin
```

## Known limitations

- `--plugin foo.jar` is not supported in native mode (URLClassLoader can't load
  class files at runtime). Use `ssc-plugin-host.jar` bridge (Phase 3).
- First native build takes 5-15 minutes; subsequent incremental builds are faster.
- The Scala 3 compiler (`dotty`) is NOT embedded in the native binary by default.
  The `scala3-compiler` dependency is pulled in via sbt but excluded from the
  native-image classpath to keep binary size manageable. JVM-based compilation
  falls back to a subprocess.
