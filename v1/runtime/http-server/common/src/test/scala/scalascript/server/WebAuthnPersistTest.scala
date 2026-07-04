package scalascript.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

/** Regression coverage for the credential-store disk persistence added alongside busi's
  * email-first login redesign — without it, `WebAuthn`'s store was a process-local
  * ConcurrentHashMap wiped by every server restart, silently forcing every enrolled device
  * back to the pairing-code fallback. */
class WebAuthnPersistTest extends AnyFunSuite with Matchers with BeforeAndAfterEach:

  override def beforeEach(): Unit = WebAuthn.reset()
  override def afterEach(): Unit = WebAuthn.reset()

  private def tmpPath(): String =
    val f = java.nio.file.Files.createTempFile("webauthn-persist-test", ".tsv")
    java.nio.file.Files.delete(f) // configureStore should tolerate a missing file
    f.toString

  test("without configureStore, credentials stay in-memory only (default, unchanged behavior)") {
    WebAuthn.storePut("alice", WebAuthn.Credential("cred-1", "pk-1", 0))
    WebAuthn.storeGet("alice") should have size 1
  }

  test("configureStore persists storePut across a fresh in-process reload") {
    val path = tmpPath()
    WebAuthn.configureStore(path)
    WebAuthn.storePut("alice", WebAuthn.Credential("cred-1", "pk-1", 0))
    WebAuthn.storePut("bob", WebAuthn.Credential("cred-2", "pk-2", 3))

    // Simulate a process restart: wipe the in-memory map, then reload from the same path.
    WebAuthn.reset()
    WebAuthn.storeGet("alice") shouldBe empty

    WebAuthn.configureStore(path)
    WebAuthn.storeGet("alice") shouldBe List(WebAuthn.Credential("cred-1", "pk-1", 0))
    WebAuthn.storeGet("bob") shouldBe List(WebAuthn.Credential("cred-2", "pk-2", 3))
  }

  test("re-enrolling the same credentialId overwrites, not duplicates, on disk too") {
    val path = tmpPath()
    WebAuthn.configureStore(path)
    WebAuthn.storePut("alice", WebAuthn.Credential("cred-1", "pk-1", 0))
    WebAuthn.storePut("alice", WebAuthn.Credential("cred-1", "pk-1-new", 5))

    WebAuthn.reset()
    WebAuthn.configureStore(path)
    WebAuthn.storeGet("alice") shouldBe List(WebAuthn.Credential("cred-1", "pk-1-new", 5))
  }

  test("storeUpdateSignCount persists the bumped count across a reload") {
    val path = tmpPath()
    WebAuthn.configureStore(path)
    WebAuthn.storePut("alice", WebAuthn.Credential("cred-1", "pk-1", 0))
    WebAuthn.storeUpdateSignCount("alice", "cred-1", 7) shouldBe true

    WebAuthn.reset()
    WebAuthn.configureStore(path)
    WebAuthn.storeGet("alice") shouldBe List(WebAuthn.Credential("cred-1", "pk-1", 7))
  }

  test("configureStore on a path that doesn't exist yet starts with an empty store") {
    val path = tmpPath()
    WebAuthn.configureStore(path)
    WebAuthn.storeGet("anyone") shouldBe empty
  }

  test("multiple users each keep their own credential list on disk") {
    val path = tmpPath()
    WebAuthn.configureStore(path)
    WebAuthn.storePut("alice", WebAuthn.Credential("cred-a1", "pk-a1", 0))
    WebAuthn.storePut("alice", WebAuthn.Credential("cred-a2", "pk-a2", 0))
    WebAuthn.storePut("bob", WebAuthn.Credential("cred-b1", "pk-b1", 0))

    WebAuthn.reset()
    WebAuthn.configureStore(path)
    WebAuthn.storeGet("alice").map(_.credentialId).toSet shouldBe Set("cred-a1", "cred-a2")
    WebAuthn.storeGet("bob").map(_.credentialId) shouldBe List("cred-b1")
  }

  test("storeRemove clears all of a user's credentials and returns true") {
    WebAuthn.storePut("alice", WebAuthn.Credential("cred-a1", "pk-a1", 0))
    WebAuthn.storePut("alice", WebAuthn.Credential("cred-a2", "pk-a2", 0))
    WebAuthn.storeRemove("alice") shouldBe true
    WebAuthn.storeGet("alice") shouldBe empty
  }

  test("storeRemove on a user with no credentials returns false") {
    WebAuthn.storeRemove("nobody") shouldBe false
  }

  test("storeRemove only affects the named user") {
    WebAuthn.storePut("alice", WebAuthn.Credential("cred-a1", "pk-a1", 0))
    WebAuthn.storePut("bob", WebAuthn.Credential("cred-b1", "pk-b1", 0))
    WebAuthn.storeRemove("alice") shouldBe true
    WebAuthn.storeGet("alice") shouldBe empty
    WebAuthn.storeGet("bob") shouldBe List(WebAuthn.Credential("cred-b1", "pk-b1", 0))
  }

  test("storeRemove persists across a reload") {
    val path = tmpPath()
    WebAuthn.configureStore(path)
    WebAuthn.storePut("alice", WebAuthn.Credential("cred-a1", "pk-a1", 0))
    WebAuthn.storeRemove("alice") shouldBe true

    WebAuthn.reset()
    WebAuthn.configureStore(path)
    WebAuthn.storeGet("alice") shouldBe empty
  }
