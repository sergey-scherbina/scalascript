package scalascript.wallet.vault.mpc.frost

import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scalascript.crypto.{Curve, Ed25519, HashAlgo}
import scalascript.crypto.frost.FrostKeygen
import scalascript.wallet.spi.UnlockCredential
import scalascript.wallet.vault.mpc.McpVault

/** Distributed FROST-Ed25519 over real HTTP: each share lives on its own [[FrostParticipantServer]] (a separate
 *  localhost port = a separate "host"), and a coordinator with NO shares assembles a standard Ed25519 signature.
 *  The last test wires it into an `McpVault` — the threshold-custody-wallet end to end. */
class DistributedFrostSigningTest extends AnyFunSuite:

  given ExecutionContext = ExecutionContext.global
  private def await[A](f: Future[A]): A = Await.result(f, 20.seconds)

  /** `t` participant servers on distinct OS-assigned ports, each holding ONE distinct share. */
  private def quorum(t: Int, n: Int): (FrostKeygen.KeyShares, List[FrostParticipantServer]) =
    val ks      = FrostKeygen.generate(t, n)
    val servers = ks.shares.take(t).map(s => new FrostParticipantServer(s, ks.groupPublicKey))
    servers.foreach(_.start())
    (ks, servers)

  test("distributed FROST over HTTP: t-of-n signs a valid Ed25519 signature, no co-located shares"):
    val (ks, servers) = quorum(2, 3)
    try
      assert(servers.map(_.signerId).toSet.size == servers.size, "each host holds a distinct single share")
      val client = new DistributedFrostSigningClient("treasury", "Treasury",
        ks.groupPublicKey, servers.map(_.url), threshold = 2)
      val msg = "send 1.5 SOL to alice".getBytes("UTF-8")
      val sig = await(client.sign("treasury", Curve.Ed25519, "", msg, HashAlgo.None))
      assert(sig.length == 64)
      assert(Ed25519.verify(ks.groupPublicKey, msg, sig),
        "distributed FROST signature must verify under standard Ed25519")
    finally servers.foreach(_.stop())

  test("3-of-5 over HTTP also verifies"):
    val ks      = FrostKeygen.generate(3, 5)
    val servers = List(0, 2, 4).map(i => new FrostParticipantServer(ks.shares(i), ks.groupPublicKey))
    servers.foreach(_.start())
    try
      val client = new DistributedFrostSigningClient("t", "t", ks.groupPublicKey, servers.map(_.url), 3)
      val msg    = "multi-host quorum".getBytes("UTF-8")
      assert(Ed25519.verify(ks.groupPublicKey, msg, await(client.sign("t", Curve.Ed25519, "", msg, HashAlgo.None))))
    finally servers.foreach(_.stop())

  test("threshold-custody-wallet: an McpVault signs through the distributed client"):
    val (ks, servers) = quorum(2, 3)
    try
      val client = new DistributedFrostSigningClient("frost-treasury", "Treasury",
        ks.groupPublicKey, servers.map(_.url), 2)
      val vault  = new McpVault("frost-treasury", client)
      await(vault.unlock(UnlockCredential.None))                       // health-probes the participant hosts
      val accounts = await(vault.listAccounts())
      assert(accounts.nonEmpty, "vault must list the threshold account")
      val signer = await(vault.getSigner(Curve.Ed25519, accounts.head.derivationPath))
      val msg    = "treasury withdrawal of 10 ADA".getBytes("UTF-8")
      val sig    = await(signer.sign(msg, HashAlgo.None))
      assert(Ed25519.verify(ks.groupPublicKey, msg, sig), "vault-routed distributed FROST sig must verify")
    finally servers.foreach(_.stop())

  test("health is false when fewer than t participants are reachable"):
    val (ks, servers) = quorum(2, 3)
    servers.last.stop()                                                // only 1 of 2 left up
    try
      val client = new DistributedFrostSigningClient("t", "t", ks.groupPublicKey, servers.map(_.url), 2)
      assert(!await(client.health()))
    finally servers.foreach(s => try s.stop() catch case _: Throwable => ())

  test("a non-Ed25519 curve is rejected"):
    val (ks, servers) = quorum(2, 3)
    try
      val client = new DistributedFrostSigningClient("t", "t", ks.groupPublicKey, servers.map(_.url), 2)
      assertThrows[IllegalArgumentException](
        await(client.sign("t", Curve.Secp256k1, "", "x".getBytes("UTF-8"), HashAlgo.None)))
    finally servers.foreach(_.stop())
