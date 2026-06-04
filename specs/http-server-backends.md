# HTTP/WS server backends — picking Jetty or Netty

ScalaScript ships with three pluggable HTTP/WS server backends.  The
user-facing API (`route`, `onWebSocket`, `serve`, `setMaxBodySize`,
…) is identical across all three; you choose which network library
your app uses at startup.

| Backend | Status | External deps | When |
| --- | --- | --- | --- |
| `jdk` (default) | bundled | none | Default for everything; adequate up to ~few-K concurrent connections. |
| `jetty` | optional | Jetty 12 (~3 MB) | When you need HTTP/2, permessage-deflate WS compression, or production-grade TLS via Conscrypt / BoringSSL. |
| `netty` | optional | Netty 4 (~4 MB) | When you need raw throughput per core, HTTP/3 (incubator), or custom protocols (gRPC, MQTT, raw TCP). |

Selection is **runtime** — pick the backend with one intrinsic
before your first `serve(port)` call.

## Picking a backend in code

```scalascript
setHttpServerBackend("jetty")   // or "netty" or "jdk"
serve(8080)
```

If the named backend isn't on the classpath, `setHttpServerBackend`
throws `IllegalStateException` with a clear message — loud failure
beats silent fallback.

## Getting Jetty or Netty on the classpath

### Compiled scripts (`ssc compile`)

Pass `--server-backend <name>` to `ssc compile`.  It auto-injects
both a `//> using dep` directive (so scala-cli pulls the Jetty / Netty
jar from Maven) and the `setHttpServerBackend(name)` call:

```bash
ssc compile --server-backend jetty examples/rest-jetty.ssc
ssc compile --server-backend netty examples/ws-chat.ssc
```

> **Until Option A+ (Maven publishing) lands**: scala-cli won't be
> able to resolve the dep from Maven Central.  Run
> `sbt publishLocal` from the ssc repo first to put the backend jars
> in your local `~/.ivy2/local`; scala-cli will then find them.

### Interpreter (`ssc <file>.ssc`)

The interpreter discovers backends via `ServiceLoader[HttpServerSpi]`.
Add the matching sbt module to your project's deps (or build a
custom ssc launcher that bundles Jetty / Netty).  The default ssc
ships with only `runtime-server-jvm` (the JDK backend).

## What each backend gives you

### `jdk` — the default

- Zero external runtime deps.  ssc is a fat jar.
- Generated scripts are self-contained `.scala` files compilable by
  scala-cli with no extra setup.
- Built on JDK `com.sun.net.httpserver.HttpServer` + custom blocking
  `ServerSocket` + per-VT proxy + the shared `WebSocket` class.
- HTTP/1.1 only; no permessage-deflate.

### `jetty` — Jetty 12

- HTTP/2 + HTTP/1.1 on the same port (ALPN auto-negotiation).
- Mature TLS (Conscrypt / BoringSSL integration for AES-NI).
- permessage-deflate WS compression (when explicitly enabled —
  not surfaced through the SPI yet, but Jetty supports it).
- Slightly higher per-connection overhead than Netty; lower than
  the JDK backend's per-VT model.

### `netty` — Netty 4

- HTTP/2 + HTTP/3 (incubator) + custom-protocol extensibility
  (gRPC, MQTT, raw TCP — outside the SPI but the underlying lib
  supports them via cast).
- Highest throughput per core (event-loop model, direct ByteBuf
  pools).
- Larger operational tuning surface (event-loop sizing, direct-buffer
  arenas, channel options).

## Picking the right one

- **Default to `jdk`.**  It's fine for most apps; you don't pay any
  external dep cost or operational complexity.
- **Switch to `jetty`** when you hit a concrete pain: clients
  asking for HTTP/2, slow CONNECT handshakes under TLS load, a need
  for permessage-deflate.
- **Switch to `netty`** when raw throughput / latency become the
  bottleneck.  Measure before switching — the gap over `jetty` only
  shows up at high concurrency.

## Caveats

- `recv(): Option[String]` (the pull-style API on `ws`) is only
  supported by the `jdk` backend.  Jetty + Netty return `None` —
  they're event-loop-driven, not pull-driven.  User code that
  relies on `recv()` should stick with `jdk` or restructure around
  callbacks.
- Per-route active-connection caps (`onWebSocket(path,
  maxConnections = N)`) are SPI-aware on `jdk` and route-aware on
  Jetty / Netty too, but the underlying enforcement differs (per-VT
  vs event-loop counter); behaviour is the same from the user's
  perspective.
- TLS configuration goes through `serve(port, _TlsConfig(certPemPath,
  keyPemPath))` (the same call on every backend).  Each backend
  uses its native TLS path (JDK SSLEngine / Jetty SslContextFactory
  / Netty SslContextBuilder).

## Reference

- SPI design: [`http-server-spi-plan.md`](http-server-spi-plan.md).
- Example: [`../examples/rest-jetty.ssc`](../examples/rest-jetty.ssc).
- Trade-offs table inside the spec covers HTTP/2, HTTP/3, WS
  extensions, throughput per core, memory per connection, impl LOC,
  tuning surface, operational maturity.
