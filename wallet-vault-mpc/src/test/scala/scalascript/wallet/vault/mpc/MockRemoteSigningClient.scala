package scalascript.wallet.vault.mpc

import scala.concurrent.Future
import scalascript.crypto.{Curve, HashAlgo}

/** Trivial in-memory `RemoteSigningClient` for tests. Returns canned
 *  accounts and a canned signature; counts invocations so tests can
 *  assert that the vault actually delegated. */
class MockRemoteSigningClient(
  cannedAccounts: Seq[McpAccount],
  cannedSig:      Array[Byte],
  healthy:        Boolean = true,
) extends RemoteSigningClient:

  @volatile var signCalls:    Int = 0
  @volatile var listCalls:    Int = 0
  @volatile var healthCalls:  Int = 0

  // Most recent sign request — what tests inspect to verify routing.
  @volatile var lastAccountId:  String   = ""
  @volatile var lastCurve:      Curve    = Curve.Secp256k1
  @volatile var lastPath:       String   = ""
  @volatile var lastPayload:    Array[Byte] = Array.empty
  @volatile var lastHashAlgo:   HashAlgo = HashAlgo.None

  def listAccounts(): Future[Seq[McpAccount]] =
    listCalls += 1
    Future.successful(cannedAccounts)

  def sign(
    accountId:      String,
    curve:          Curve,
    derivationPath: String,
    payload:        Array[Byte],
    hashAlgo:       HashAlgo,
  ): Future[Array[Byte]] =
    signCalls     += 1
    lastAccountId  = accountId
    lastCurve      = curve
    lastPath       = derivationPath
    lastPayload    = payload
    lastHashAlgo   = hashAlgo
    Future.successful(cannedSig)

  def health(): Future[Boolean] =
    healthCalls += 1
    Future.successful(healthy)
