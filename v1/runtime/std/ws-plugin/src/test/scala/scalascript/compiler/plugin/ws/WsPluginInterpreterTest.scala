package scalascript.compiler.plugin.ws

import org.scalatest.funsuite.AnyFunSuite
import scalascript.testkit.TestInterpreter

class WsPluginInterpreterTest extends AnyFunSuite:

  private def interp: TestInterpreter =
    TestInterpreter(List(WsInterpreterPlugin()))

  test("WebSocket plugin exposes process metrics in isolation"):
    scalascript.server.Metrics.reset()
    scalascript.server.Metrics.wsActive.set(2L)
    scalascript.server.Metrics.httpRequests.set(5L)

    val result = interp.eval(
      """
      val m = metrics()
      List(m("ws.active"), m("http.requests"), m("ws.messages.in"))
      """
    )

    assert(result == List(2L, 5L, 0L))

  test("WebSocket plugin updates the shared max connection cap in isolation"):
    val previous = scalascript.server.jvm._wsMaxActive.get()
    try
      val result = interp.eval(
        """
        setMaxWsConnections(3)
        1
        """
      )

      assert(result == 1L)
      assert(scalascript.server.jvm._wsMaxActive.get() == 3)
    finally
      scalascript.server.jvm._wsMaxActive.set(previous)

  test("WebSocket plugin creates WsRoom registries in isolation"):
    val result = interp.eval(
      """
      val room = WsRoom()
      val empty = room.size()
      room.add("not-a-websocket")
      val afterAdd = room.size()
      room.broadcast("ignored")
      room.remove("not-a-websocket")
      List(empty, afterAdd, room.size())
      """
    )

    assert(result == List(0L, 1L, 0L))
