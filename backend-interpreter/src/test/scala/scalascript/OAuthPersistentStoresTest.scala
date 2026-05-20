package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.oauth.*
import java.nio.file.{Files, Path}

/** v1.17.x — Iter RR: file-backed `ClientStore` + `TokenStore` for
 *  restart-safe AS deployments.  Tests verify that state survives a
 *  full process simulation: write data, drop the store, recreate from
 *  the same file, verify the data is back. */
class OAuthPersistentStoresTest extends AnyFunSuite with Matchers:

  private def tmpFile(name: String): Path =
    Files.createTempFile(s"as-$name-", ".log")

  // ─── JsonLineClientStore: registration survives restart ──────────

  test("JsonLineClientStore: register + replay restores all clients"):
    val p = tmpFile("clients")
    Files.deleteIfExists(p)  // start fresh
    val store1 = new PersistentStores.JsonLineClientStore(p)
    val c1 = Client(
      id = "client-1", secret = Some("pbkdf2:100000:salt:hash"),
      redirectUris = Set("http://app/cb"),
      scopes = Set("read", "write"),
      clientType = ClientType.Confidential,
      name = Some("App 1"))
    val c2 = Client(
      id = "client-2", secret = None,
      redirectUris = Set("http://other/cb"),
      scopes = Set("openid"),
      grantTypes = Set("authorization_code"),
      clientType = ClientType.Public)
    store1.register(c1)
    store1.register(c2)
    store1.all.length shouldBe 2

    // Simulate restart: drop the in-memory store, recreate from disk.
    val store2 = new PersistentStores.JsonLineClientStore(p)
    store2.all.length shouldBe 2
    store2.find("client-1").map(_.secret) shouldBe Some(Some("pbkdf2:100000:salt:hash"))
    store2.find("client-1").map(_.clientType) shouldBe Some(ClientType.Confidential)
    store2.find("client-1").flatMap(_.name) shouldBe Some("App 1")
    store2.find("client-2").map(_.scopes) shouldBe Some(Set("openid"))
    store2.find("client-2").map(_.clientType) shouldBe Some(ClientType.Public)
    Files.deleteIfExists(p)

  test("JsonLineClientStore: corrupt lines are skipped, not fatal"):
    val p = tmpFile("corrupt")
    Files.deleteIfExists(p)
    val store = new PersistentStores.JsonLineClientStore(p)
    store.register(Client("c", None, Set("http://x"), Set.empty,
      clientType = ClientType.Public))
    // Manually inject garbage + a second valid client
    Files.write(p, "garbage-not-json\n".getBytes,
      java.nio.file.StandardOpenOption.APPEND)
    store.register(Client("c2", None, Set("http://y"), Set.empty,
      clientType = ClientType.Public))
    // Replay must survive the garbage line.
    val store2 = new PersistentStores.JsonLineClientStore(p)
    store2.all.length shouldBe 2
    Files.deleteIfExists(p)

  // ─── JsonLineTokenStore: tokens survive restart ─────────────────

  test("JsonLineTokenStore: refresh tokens + family burns survive restart"):
    val p = tmpFile("tokens")
    Files.deleteIfExists(p)
    val store1 = new PersistentStores.JsonLineTokenStore(p)
    val now = java.time.Instant.now.getEpochSecond + 3600L
    store1.saveRefreshToken(RefreshTokenRecord("tok-1", "c1", "alice",
      Set("read"), now, "fam-1"))
    store1.saveRefreshToken(RefreshTokenRecord("tok-2", "c1", "alice",
      Set("read"), now, "fam-1"))
    store1.saveRefreshToken(RefreshTokenRecord("tok-3", "c1", "alice",
      Set("read"), now, "fam-2"))
    store1.findRefreshToken("tok-1").isDefined shouldBe true
    store1.revokeRefreshFamily("fam-1") shouldBe 2
    store1.findRefreshToken("tok-3").isDefined shouldBe true  // other family intact
    store1.isFamilyRevoked("fam-1") shouldBe true

    // Restart simulation
    val store2 = new PersistentStores.JsonLineTokenStore(p)
    store2.isFamilyRevoked("fam-1") shouldBe true   // burn persisted
    store2.findRefreshToken("tok-1") shouldBe None   // family-1 cleared
    store2.findRefreshToken("tok-3").isDefined shouldBe true  // other intact
    Files.deleteIfExists(p)

  test("JsonLineTokenStore: auth-code save + consume one-shot"):
    val p = tmpFile("codes")
    Files.deleteIfExists(p)
    val store1 = new PersistentStores.JsonLineTokenStore(p)
    val rec = AuthorizationCodeRecord(
      code = "code-abc", clientId = "c1", redirectUri = "http://x/cb",
      scope = Set("read"), subject = "alice",
      codeChallenge = Some("chall"), codeChallengeMethod = Some("S256"),
      expiresAt = java.time.Instant.now.getEpochSecond + 600L,
      nonce = Some("nonce-xyz"))
    store1.saveAuthorizationCode(rec)
    store1.consumeAuthorizationCode("code-abc").isDefined shouldBe true
    store1.consumeAuthorizationCode("code-abc") shouldBe None  // single-use

    // Restart: code should still be gone (consumed before restart)
    val store2 = new PersistentStores.JsonLineTokenStore(p)
    store2.consumeAuthorizationCode("code-abc") shouldBe None
    Files.deleteIfExists(p)

  test("JsonLineTokenStore: access-token revocations replay"):
    val p = tmpFile("access-revoke")
    Files.deleteIfExists(p)
    val store1 = new PersistentStores.JsonLineTokenStore(p)
    store1.revokeAccessToken("jti-1")
    store1.revokeAccessToken("jti-2")
    store1.isAccessRevoked("jti-1") shouldBe true
    store1.isAccessRevoked("jti-2") shouldBe true

    val store2 = new PersistentStores.JsonLineTokenStore(p)
    store2.isAccessRevoked("jti-1") shouldBe true
    store2.isAccessRevoked("jti-2") shouldBe true
    store2.isAccessRevoked("never-revoked") shouldBe false
    Files.deleteIfExists(p)

  test("JsonLineTokenStore: rotation populates graveyard for reuse detection"):
    val p = tmpFile("graveyard")
    Files.deleteIfExists(p)
    val store1 = new PersistentStores.JsonLineTokenStore(p)
    val now = java.time.Instant.now.getEpochSecond + 3600L
    store1.saveRefreshToken(RefreshTokenRecord("tok-old", "c1", "alice",
      Set.empty, now, "fam-x"))
    store1.revokeRefreshToken("tok-old")
    store1.graveyardAdd("tok-old", "fam-x")
    store1.graveyardLookup("tok-old") shouldBe Some("fam-x")
    // After restart: revoked tokens auto-restore graveyard (we replay
    // refresh.revoke events into the graveyard so post-restart reuse
    // detection still trips for stolen tokens that haven't expired).
    val store2 = new PersistentStores.JsonLineTokenStore(p)
    store2.graveyardLookup("tok-old") shouldBe Some("fam-x")
    Files.deleteIfExists(p)

  // ─── End-to-end: AuthServer wired with persistent stores ────────

  test("AuthServer with persistent stores: token survives AS restart"):
    val clientP = tmpFile("e2e-clients")
    val tokenP  = tmpFile("e2e-tokens")
    Files.deleteIfExists(clientP)
    Files.deleteIfExists(tokenP)

    // ─── First AS instance: register client + issue refresh token ───
    val as1 = new AuthServer(
      AuthServerConfig(issuer = "https://auth.local",
        signingSecret = "k" * 40,
        supportedScopes = Set("read")),
      clients = new PersistentStores.JsonLineClientStore(clientP),
      tokens  = new PersistentStores.JsonLineTokenStore(tokenP))
    val md = as1.registerClient(ujson.Obj(
      "redirect_uris" -> ujson.Arr("http://x/cb"),
      "grant_types"   -> ujson.Arr("client_credentials"),
      "scope"         -> "read"
    )).toOption.get
    val resp = as1.issueToken(TokenRequest.ClientCredentialsGrant(
      md.id, md.secret.get, Set("read")
    )).asInstanceOf[TokenOutcome.Issued].response
    resp.accessToken should not be empty

    // ─── Simulate restart: new AS reading the same files ───────────
    val as2 = new AuthServer(
      AuthServerConfig(issuer = "https://auth.local",
        signingSecret = "k" * 40,
        supportedScopes = Set("read")),
      clients = new PersistentStores.JsonLineClientStore(clientP),
      tokens  = new PersistentStores.JsonLineTokenStore(tokenP))
    // The client must still be there — auth works
    as2.clients.find(md.id).isDefined shouldBe true
    as2.issueToken(TokenRequest.ClientCredentialsGrant(
      md.id, md.secret.get, Set("read")
    )) shouldBe a[TokenOutcome.Issued]

    Files.deleteIfExists(clientP)
    Files.deleteIfExists(tokenP)
