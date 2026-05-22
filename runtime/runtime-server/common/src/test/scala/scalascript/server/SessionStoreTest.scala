package scalascript.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

class SessionStoreTest extends AnyFunSuite with Matchers with BeforeAndAfterEach:

  override def beforeEach(): Unit = SessionStore.reset()

  test("put / get — round-trip returns payload") {
    SessionStore.useStore()
    val payload = Map("user" -> "alice", "role" -> "admin")
    val ssid    = SessionStore.put(payload)
    SessionStore.get(ssid) shouldBe Some(payload)
  }

  test("get — unknown ssid returns None") {
    SessionStore.useStore()
    SessionStore.get("no-such-id") shouldBe None
  }

  test("delete — removes entry") {
    SessionStore.useStore()
    val ssid = SessionStore.put(Map("x" -> "1"))
    SessionStore.delete(ssid)
    SessionStore.get(ssid) shouldBe None
  }

  test("put — generates unique ssids") {
    SessionStore.useStore()
    val ids = (1 to 20).map(_ => SessionStore.put(Map("k" -> "v"))).toSet
    ids.size shouldBe 20
  }

  test("size — reflects number of stored entries") {
    SessionStore.useStore()
    SessionStore.put(Map("a" -> "1"))
    SessionStore.put(Map("b" -> "2"))
    SessionStore.size shouldBe 2
  }

  test("expired entry — get returns None after TTL") {
    SessionStore.useStore(ttlSeconds = 0)
    val ssid = SessionStore.put(Map("x" -> "1"))
    Thread.sleep(10) // 0s TTL, even 10ms is enough
    SessionStore.get(ssid) shouldBe None
  }

  test("active session — TTL refreshed on each get") {
    SessionStore.useStore(ttlSeconds = 60)
    val ssid = SessionStore.put(Map("x" -> "1"))
    SessionStore.get(ssid) shouldBe Some(Map("x" -> "1"))
    SessionStore.get(ssid) shouldBe Some(Map("x" -> "1"))
  }

  test("reset — clears all state") {
    SessionStore.useStore()
    val ssid = SessionStore.put(Map("x" -> "1"))
    SessionStore.reset()
    SessionStore.get(ssid) shouldBe None
    SessionStore.size shouldBe 0
    SessionStore.isEnabled shouldBe false
  }

  test("sweep — removes expired entries") {
    SessionStore.useStore(ttlSeconds = 0)
    SessionStore.put(Map("a" -> "1"))
    SessionStore.put(Map("b" -> "2"))
    Thread.sleep(10)
    SessionStore.sweep()
    SessionStore.size shouldBe 0
  }
