# std.tcp — raw line-oriented TCP

## Why

ScalaScript could speak HTTP, WebSocket and SMTP-send, but had no way to **receive**
over a raw socket or to speak an arbitrary line protocol (IMAP/POP3/Redis, or a
custom TCP service). The local protocol simulators (`sim-*` in busi) need this to
host a faithful IMAP mailbox; the matching client needs it to poll one. The fix
belongs in the language, not a per-consumer workaround.

## Design

A minimal, **handle-based, blocking** surface over `java.net` sockets, JVM only —
the same shape as `std.smtp` (no raw sockets in the browser). Sockets live in a
process-local registry keyed by an integer handle, so the API passes through
ScalaScript as plain `Int`s and needs **no native→interpreter callback**: the
calling code runs its own accept/read loop, single-threaded, one connection at a
time. That is exactly what a simulator wants, and it keeps the intrinsics pure
native (the `runtime/std/*-plugin` rule).

```
tcpListen(port: Int): Int                     // server handle, -1 on bind failure
tcpAccept(server: Int): Int                   // blocks; connection handle, -1 if server closed
tcpConnect(host: String, port: Int): Int      // connection handle, -1 on failure (bounded timeout)
tcpRecvLine(conn: Int): String                // one line without CR/LF; "" at end-of-stream
tcpSend(conn: Int, data: String): Unit        // verbatim UTF-8; caller supplies the line ending
tcpClose(conn: Int): Unit
tcpStop(server: Int): Unit
```

The single-threaded server/client round-trip works because `connect()` completes
through the OS accept backlog **before** `accept()` is called, so a test (or a
simple request/response exchange) can `tcpConnect` then `tcpAccept` on one thread.

`tcpRecvLine` returns `""` at EOF: text protocols never send a meaningful empty
line, so `""` doubles as the closed-connection signal — no out-of-band Option
needed.

## Implementation

- `runtime/std/tcp.ssc` — the `extern def` surface (`package: std.tcp`).
- `runtime/std/tcp-plugin/` — `TcpIntrinsics` (a `ConcurrentHashMap` registry of
  `ServerSocket`/`Socket` + an `AtomicInteger` counter; `BufferedReader.readLine`
  for lines), `TcpInterpreterPlugin` (the `Backend` SPI provider), and the SPI
  service file. Registered in `build.sbt` (project + `PluginSpec` + CLI
  `packagePlugin`), exactly like `smtp`.

## Verify

`runtime/std/tcp-plugin/.../TcpPluginTest.scala` drives the intrinsics through
`TestInterpreter`: a listen → connect → accept → bidirectional line exchange in
one snippet, plus a refused-connection returning `-1` (bounded, never hangs).

## Scope / non-goals

- **JVM only.** The JS/browser backend has no raw sockets; a Node `net` binding
  could back these same externs later if a Node target needs them.
- No TLS on the raw socket here (the simulators run plain on the loopback, as the
  HTTP sims already do); TLS termination stays with `tls()` on the HTTP server.
- Blocking, single-connection-at-a-time. Fine for simulators and simple clients;
  a concurrent server would spawn a thread per `tcpAccept` (the caller's choice,
  not baked in).
