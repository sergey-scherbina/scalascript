# Community Plugins

Status: **partially implemented**. The plugin starter template and
`ssc new <name> --template plugin` scaffolding landed in
`arch-distribution-p4` on 2026-05-29. Public artifact publication and stable
plugin API packaging remain planned.

## Create a Plugin Project

```bash
ssc new my-plugin --template plugin
cd my-plugin
```

The generated project contains:

- `src/main/scala/...` with a minimal `Backend` SPI implementation.
- `src/main/resources/META-INF/services/scalascript.backend.spi.Backend` for
  ServiceLoader registration.
- `plugin/manifest.yaml` and `plugin/sources/index.ssc` for `.sscpkg`
  packaging.
- `.github/workflows/release.yml` that builds a JAR, runs
  `ssc plugin pack`, and uploads the `.sscpkg` to a GitHub Release.

## Package

```bash
sbt package
mkdir -p plugin/intrinsics dist
cp target/scala-*/my-plugin_*.jar plugin/intrinsics/my-plugin.jar
ssc plugin pack plugin -o dist/my-plugin.sscpkg
```

## Consume From GitHub Releases

After tagging and publishing a release asset, consumers can import it with the
GitHub resolver:

```markdown
[MyPlugin](github:owner/my-plugin@v0.1.0)
```

For reproducible builds, add a `sha256:` suffix:

```markdown
[MyPlugin](github:owner/my-plugin@v0.1.0 sha256:...)
```

## Current Limitations

- The generated `build.sbt` references planned public ScalaScript artifacts.
  Until official publication lands, plugin authors working from this monorepo
  should publish the required ScalaScript modules locally or replace the
  dependencies with local project wiring.
- `ssc new` currently ships the plugin template path. The broader `app`, `lib`,
  `dsl`, `web-app`, and `wasm-app` templates remain tracked in
  `docs/arch-ssc-new.md`.
