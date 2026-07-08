package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.JvmGen

/** Split JVM runtime invariants used by `compile-jvm --bytecode`.
 *  The shared runtime always includes effects/common actor code, so a
 *  no-server runtime still needs the HTTP/WS dispatch stubs those blocks
 *  reference. */
class JvmGenRuntimeSeparationTest extends AnyFunSuite:

  test("JvmGen.generateRuntime without Serve emits serve stubs"):
    val runtime = JvmGen.generateRuntime(Set.empty)

    assert(runtime.contains("stub serve runtime"),
      "runtime without Serve should include the stub serve block")
    assert(runtime.contains("val _routes"),
      "effects/common runtime references _routes even when Serve is absent")
    assert(runtime.contains("def route(method: String, path: String)"),
      "route stub must be available for route-registration references")
    assert(runtime.contains("def onWebSocket("),
      "actor runtime references onWebSocket even when Serve is absent")
    assert(runtime.contains("def _httpDoRequest("),
      "Http effect runtime references _httpDoRequest even when Serve is absent")
    assert(!runtime.contains("runtime-server-jvm"),
      "runtime without Serve should not pull in the full server backend")
