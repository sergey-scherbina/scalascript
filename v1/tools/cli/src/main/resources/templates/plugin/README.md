# ${Name}

ScalaScript plugin starter.

## Build

```bash
sbt package
mkdir -p plugin/intrinsics
cp target/scala-*/${name}_*.jar plugin/intrinsics/${name}.jar
ssc plugin pack plugin -o dist/${name}.sscpkg
```

Consumers can install the release asset directly:

```markdown
[${Name}](github:owner/${name}@v${version})
```
