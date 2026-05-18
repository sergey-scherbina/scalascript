# org.example.crypto — ScalaScript Crypto Plugin

Reference implementation for the v1.7 `.sscpkg` plugin system.
Demonstrates the full workflow: `extern def` API → `IntrinsicImpl`
→ runtime helpers → end-to-end ScalaScript usage.

## API

```scala
import [Crypto](crypto)

sha256(input: String): String         // SHA-256 hex digest
base64Encode(s: String): String       // Base-64 encode
base64Decode(s: String): String       // Base-64 decode
hmacSha256(key: String, data: String): String  // HMAC-SHA256 hex
```

## Build

```sh
# 1. Publish SPI + IR locally
sbt 'backendSpi/publishLocal' 'ir/publishLocal'

# 2. Compile the plugin JAR
scala-cli package . --assembly -o crypto-plugin-1.0.0.jar

# 3. Pack into .sscpkg
mkdir -p _pkg/intrinsics _pkg/sources _pkg/runtime
cp manifest.yaml _pkg/
cp -r sources/ _pkg/sources/
cp -r runtime/ _pkg/runtime/
cp crypto-plugin-1.0.0.jar _pkg/intrinsics/
ssc plugin pack _pkg -o org.example.crypto-1.0.0.sscpkg
```

## Install & Run

```sh
# Install permanently
ssc plugin install ./org.example.crypto-1.0.0.sscpkg

# Or use ad-hoc for one run
ssc --plugin ./org.example.crypto-1.0.0.sscpkg run examples/use-crypto.ssc
```

## Plugin architecture

| File | Purpose |
|------|---------|
| `manifest.yaml` | Package metadata, targets, declared capabilities |
| `sources/crypto.ssc` | `extern def` API surface (imported by user code) |
| `runtime/jvm.scala` | JVM helper functions injected into compiled output |
| `runtime/js.js` | Node.js helper functions injected into compiled output |
| `src/.../CryptoIntrinsics.scala` | `IntrinsicImpl` table (RuntimeCall + NativeImpl) |
| `src/.../CryptoBackendPlugin.scala` | `Backend` SPI impl registered via ServiceLoader |

### IntrinsicImpl strategy

- **JvmGen / JsGen backends**: `RuntimeCall("_cryptoSha256")` — the
  generated call site emits `_cryptoSha256(arg)` directly.  The
  function is defined in `runtime/jvm.scala` (or `runtime/js.js`)
  which BackendRegistry prepends to every compiled file.

- **Interpreter backend**: `NativeImpl((ctx, args) => ...)` — the
  interpreter calls the lambda directly with no code emission.
