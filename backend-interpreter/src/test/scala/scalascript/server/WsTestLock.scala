package scalascript.server

/** JVM-wide lock object that the WebSocket end-to-end test suites
 *  serialise on.  Each of the `Ws*Test` classes that touches the
 *  process-global [[WsRoutes]] registry / spins up a proxy on an
 *  ephemeral port acquires this monitor for the duration of the
 *  test body.  Without it, ScalaTest's default parallel-suite
 *  execution can have one test's `WsRoutes.clear()` wipe routes
 *  another test just registered, or have two interpreters install
 *  callbacks under the same path. */
private[server] object WsTestLock
