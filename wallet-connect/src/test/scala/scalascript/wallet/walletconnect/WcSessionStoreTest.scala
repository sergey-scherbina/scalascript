package scalascript.wallet.walletconnect

import org.scalatest.funsuite.AnyFunSuite

class WcSessionStoreTest extends AnyFunSuite:

  test("register + lookup round-trips the symKey + peerPub") {
    val store  = new WcSessionStore
    val symKey = Array.fill[Byte](32)(0x11.toByte)
    val peer   = Array.fill[Byte](32)(0x22.toByte)
    store.register("topic-a", symKey, Some(peer))
    val entry = store.lookup("topic-a").get
    assert(entry.symKey.toSeq == symKey.toSeq)
    assert(entry.peerPub.exists(_.toSeq == peer.toSeq))
  }

  test("register copies the byte arrays — caller mutation does not leak in") {
    val store  = new WcSessionStore
    val symKey = Array.fill[Byte](32)(0x33.toByte)
    store.register("t", symKey, None)
    symKey(0) = 0x77.toByte
    val stored = store.lookup("t").get.symKey
    assert(stored(0) == 0x33.toByte, "caller mutation must not affect store")
  }

  test("forget removes the entry (idempotent on second call)") {
    val store = new WcSessionStore
    store.register("t", Array.fill[Byte](32)(0x44.toByte), None)
    assert(store.lookup("t").isDefined)
    store.forget("t")
    assert(store.lookup("t").isEmpty)
    store.forget("t") // idempotent
    assert(store.lookup("t").isEmpty)
  }

  test("topics() returns every currently-registered topic") {
    val store = new WcSessionStore
    store.register("a", Array.fill[Byte](32)(0), None)
    store.register("b", Array.fill[Byte](32)(0), None)
    store.register("c", Array.fill[Byte](32)(0), None)
    assert(store.topics() == Set("a", "b", "c"))
    store.forget("b")
    assert(store.topics() == Set("a", "c"))
  }

  test("register on an existing topic replaces the entry") {
    val store = new WcSessionStore
    val k1    = Array.fill[Byte](32)(0x01.toByte)
    val k2    = Array.fill[Byte](32)(0x02.toByte)
    store.register("t", k1, None)
    store.register("t", k2, Some(Array.fill[Byte](32)(0xee.toByte)))
    val entry = store.lookup("t").get
    assert(entry.symKey.toSeq == k2.toSeq)
    assert(entry.peerPub.isDefined)
  }
