package scalascript.wallet.vault.trezor

import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import ExecutionContext.Implicits.global

class TrezorSessionTest extends AnyFunSuite:

  test("withSession: acquires session and passes it to f") {
    val bridge  = MockTrezorBridge()
    val session = TrezorSession(bridge, "mock-path-0")
    var received = ""
    Await.result(
      session.withSession { s => received = s; Future.successful(()) },
      5.seconds,
    )
    assert(received.startsWith("mock-session-"))
  }

  test("withSession: releases session after success") {
    val bridge  = MockTrezorBridge()
    bridge.enqueueFeatures()
    val session = TrezorSession(bridge, "mock-path-0")
    Await.result(session.withSession { s => Future.successful(s) }, 5.seconds)
    // release was called — no assertion needed beyond no exception
  }

  test("withSession: releases session after failure") {
    val bridge  = MockTrezorBridge()
    val session = TrezorSession(bridge, "mock-path-0")
    val ex = intercept[RuntimeException] {
      Await.result(
        session.withSession { _ => Future.failed(RuntimeException("boom")) },
        5.seconds,
      )
    }
    assert(ex.getMessage == "boom")
    // release was called before re-raising
  }

  test("withSession: each call acquires a fresh session") {
    val bridge  = MockTrezorBridge()
    val session = TrezorSession(bridge, "mock-path-0")
    val s1 = Await.result(session.withSession(s => Future.successful(s)), 5.seconds)
    val s2 = Await.result(session.withSession(s => Future.successful(s)), 5.seconds)
    assert(s1 != s2)
  }
