# pdf-plugin runtime deps missing from the staged binary

**Status**: LANDED 2026-06-10. Reported by busi (htmlToPdfBase64 in staged binary, rozum).

## 1  Symptom

In the staged binary (`sbt cli/installBin` → `bin/lib/ssc.jar` + `bin/lib/jars/`),
`htmlToPdfBase64` died:

```
htmlToPdfBase64: org/apache/commons/logging/LogFactory
java.lang.RuntimeException: htmlToPdfBase64: org/apache/commons/logging/LogFactory
```

The pdf-plugin tests are green because the sbt test classpath has PDFBox + its
deps; the staged binary did not.

## 2  Root cause

`packagePlugin` bundles into a plugin's `.sscpkg` only the managed deps NOT
"provided" by a `dependsOn` project (to avoid re-bundling jars already on the ssc
runtime classpath). It computes "provided" from the **managedClasspath of the
plugin's dependency projects**. That proxy is wrong for a dep that a dependency
project lists but which is **evicted from the application's resolved runtime
classpath**: `commons-logging-1.2` (and `slf4j-api-1.7.36`) appear in a dependency
project's managedClasspath — so packagePlugin treats them as provided and omits
them from the pdf `.sscpkg` — but they are evicted from the cli's runtime
resolution (by the cli's slf4j-simple 2.0.18 stack), so `installBin` never stages
them into `bin/lib/jars/` either. PDFBox (loaded from the `.sscpkg`) then can't
find `org.apache.commons.logging.LogFactory`.

Fixing this in `packagePlugin` would require it to know the cli's resolved runtime
classpath, which creates an sbt cycle (the cli `dependsOn` several plugins). So
the fix lives in `installBin`, which already knows the staged set.

## 3  Fix (`installBin`)

After staging the runtime jars, compute the pdf-plugin's **gap** — its external
managed deps present in neither `bin/lib/jars/` (the staged runtime jars) nor the
pdf `.sscpkg` (read back from the archive) — and stage those into `bin/lib/jars/`.
In practice the gap is `commons-logging-1.2.jar` + `slf4j-api-1.7.36.jar`. They
land on the startup classpath (the parent classloader), so PDFBox loaded from the
`.sscpkg` child loader resolves `LogFactory` against them.

## 4  Verify

- [x] `sbt cli/installBin` logs `bin/lib/jars/ +2 pdf-plugin runtime dep(s):
  commons-logging-1.2.jar, slf4j-api-1.7.36.jar`; both land in `bin/lib/jars/`.
- [x] The staged binary runs `htmlToPdfBase64(...)` end-to-end —
  `pdf-base64-len=1588`, no `LogFactory` error.
- [x] pdf-plugin unit tests unaffected (build-only change).
