package scalascript.wallet.vault.trezor

import scala.concurrent.{ExecutionContext, Future}
import scalascript.crypto.{Curve, HashAlgo, PublicKey}
import scalascript.wallet.spi.*

/** [[Vault]] backed by a Trezor device running the Ethereum app.
 *
 *  Lifecycle:
 *  - `unlock` — acquires a session, sends `Initialize`, and verifies
 *    the device is ready (PIN cached or PIN protection disabled).
 *  - `getSigner` — sends `GetPublicKey` and returns a [[TrezorEthSigner]]
 *    bound to the requested derivation path.
 *  - `lock` — releases the active session (best-effort).
 *
 *  The vault auto-handles `ButtonRequest` responses by sending `ButtonAck`
 *  and re-awaiting the next response (up to `buttonAckRetries` times). */
class TrezorEthVault(
  bridge:           TrezorBridge,
  val id:           String = "trezor-ethereum",
  buttonAckRetries: Int    = 10,
)(using ec: ExecutionContext) extends Vault:

  @volatile private var _session: Option[String] = None

  def kind: VaultKind   = VaultKind.Hardware
  def isLocked: Boolean = _session.isEmpty

  def unlock(credential: UnlockCredential): Future[Unit] =
    val _ = credential
    bridge.enumerate().flatMap { devices =>
      if devices.isEmpty then
        Future.failed(RuntimeException("No Trezor device found — check USB connection and Trezor Bridge"))
      else
        val device = devices.head
        bridge.acquire(device.path, device.session).flatMap { session =>
          callWithButtonAck(session, TrezorMessageType.Initialize, ujson.Obj()).map { resp =>
            checkFailure(resp)
            val features = resp.message.obj
            val pinProtected = features.get("pin_protection").exists(_.bool)
            val pinCached    = features.get("pin_cached").exists(_.bool)
            if pinProtected && !pinCached then
              // PIN required but not cached; for now fail with a clear message
              bridge.release(session)
              throw RuntimeException(
                "Trezor device PIN is required but not cached. Enter PIN on the device first."
              )
            _session = Some(session)
          }.recoverWith { case ex =>
            bridge.release(session).flatMap(_ => Future.failed(ex))
          }
        }
    }

  def lock(): Unit =
    _session.foreach { session =>
      _session = None
      val _ = bridge.release(session)
    }

  def listAccounts(): Future[Seq[AccountDescriptor]] =
    Future.successful(Seq(
      AccountDescriptor(
        id             = s"$id-eth-0",
        label          = "Trezor Ethereum #0",
        publicKeys     = Map.empty,
        derivationPath = Bip32.DefaultEthereum,
      )
    ))

  def getSigner(curve: Curve, derivationPath: String): Future[RawSigner] =
    if curve != Curve.Secp256k1 then
      Future.failed(UnsupportedOperationException(
        s"TrezorEthVault only supports Secp256k1 (got $curve)"
      ))
    else
      withSession { session =>
        callWithButtonAck(session, TrezorMessageType.GetPublicKey, ujson.Obj(
          "address_n"       -> ujson.Arr(Bip32.parse(derivationPath).map(ujson.Num(_))*),
          "show_display"    -> ujson.Bool(false),
          "coin_name"       -> ujson.Str("Ethereum"),
          "script_type"     -> ujson.Num(0),
        )).map { resp =>
          checkFailure(resp)
          val pubKeyHex = resp.message.obj("node").obj("public_key").str
          val pubKeyBytes = hexToBytes(pubKeyHex)
          new TrezorEthSigner(
            session        = session,
            derivationPath = derivationPath,
            _publicKey     = PublicKey(Curve.Secp256k1, pubKeyBytes),
            vault          = this,
          )
        }
      }

  private[trezor] def callWithButtonAck(
    session:     String,
    messageType: String,
    message:     ujson.Value,
    retries:     Int = buttonAckRetries,
  ): Future[TrezorResponse] =
    bridge.call(session, messageType, message).flatMap { resp =>
      if resp.messageType == TrezorMessageType.ButtonRequest && retries > 0 then
        bridge.call(session, TrezorMessageType.ButtonAck, ujson.Obj()).flatMap { _ =>
          callWithButtonAck(session, messageType, message, retries - 1)
        }
      else
        Future.successful(resp)
    }

  private[trezor] def withSession[A](f: String => Future[A]): Future[A] =
    _session match
      case Some(s) => f(s)
      case None    => Future.failed(IllegalStateException(s"TrezorEthVault[$id] is locked"))

  private def checkFailure(resp: TrezorResponse): Unit =
    if resp.messageType == TrezorMessageType.Failure then
      throw TrezorDeviceFailure.fromResponse(resp)

  private def hexToBytes(hex: String): Array[Byte] =
    val h = if hex.startsWith("0x") then hex.drop(2) else hex
    h.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray


// ── Signer ────────────────────────────────────────────────────────────────────

private class TrezorEthSigner(
  session:        String,
  derivationPath: String,
  _publicKey:     PublicKey,
  vault:          TrezorEthVault,
)(using ec: ExecutionContext) extends RawSigner:

  def curve: Curve       = Curve.Secp256k1
  def publicKey: PublicKey = _publicKey

  def sign(msg: Array[Byte], hash: HashAlgo = HashAlgo.None): Future[Array[Byte]] =
    vault.callWithButtonAck(
      session,
      TrezorMessageType.EthereumSignMessage,
      ujson.Obj(
        "address_n" -> ujson.Arr(Bip32.parse(derivationPath).map(ujson.Num(_))*),
        "message"   -> ujson.Str(msg.map(b => f"${b & 0xFF}%02x").mkString),
      ),
    ).map { resp =>
      if resp.messageType == TrezorMessageType.Failure then
        throw TrezorDeviceFailure.fromResponse(resp)
      val sigHex = resp.message.obj("signature").str
      val h      = if sigHex.startsWith("0x") then sigHex.drop(2) else sigHex
      h.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
    }
