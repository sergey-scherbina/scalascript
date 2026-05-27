package scalascript.wallet.vault.ledger.js.cardano

import scala.concurrent.{ExecutionContext, Future}
import scalascript.wallet.vault.ledger.{Apdu, Bip32Path, LedgerTransport}

object CardanoApp:
  val Cla: Int = 0xD7
  val Ins_GetExtendedPublicKey: Int = 0x10
  val Ins_SignCip8: Int = 0x21
  val P1First: Int = 0x00
  val P1Continue: Int = 0x80
  val P2None: Int = 0x00

  final case class Cip8Proof(coseSign1: Array[Byte], coseKey: Array[Byte], signature: Array[Byte], publicKey: Array[Byte])

  def getPublicKey(transport: LedgerTransport, derivationPath: String)(using ExecutionContext): Future[Array[Byte]] =
    val cmd = Apdu.command(Cla, Ins_GetExtendedPublicKey, P1First, P2None, Bip32Path.encode(derivationPath))
    transport.exchange(cmd).map { resp =>
      val (sw, payload) = Apdu.parseResponse(resp)
      if sw != Apdu.Sw_Ok then throw new RuntimeException(s"Cardano GET_PUBLIC_KEY failed: sw=${Apdu.swHex(sw)}")
      decodePublicKey(payload)
    }

  def signCip8(
    transport: LedgerTransport,
    derivationPath: String,
    address: Array[Byte],
    payload: Array[Byte],
  )(using ec: ExecutionContext): Future[Cip8Proof] =
    val protectedHeader = CardanoCip8.protectedHeader(address)
    val sigStructure = CardanoCip8.sigStructure(protectedHeader, payload)
    val body = Bip32Path.encode(derivationPath) ++ sigStructure
    for
      publicKey <- getPublicKey(transport, derivationPath)
      signed <- Apdu.chunkedSend(transport, Cla, Ins_SignCip8, P1First, P1Continue, P2None, body)
    yield
      val (sw, signature) = signed
      if sw != Apdu.Sw_Ok then throw new RuntimeException(s"Cardano SIGN_CIP8 failed: sw=${Apdu.swHex(sw)}")
      Cip8Proof(
        coseSign1 = CardanoCip8.coseSign1(protectedHeader, payload, signature),
        coseKey = CardanoCip8.coseKeyEd25519(publicKey),
        signature = signature,
        publicKey = publicKey,
      )

  private[cardano] def decodePublicKey(payload: Array[Byte]): Array[Byte] =
    if payload.length == 32 then payload
    else if payload.length >= 33 && (payload(0) & 0xff) == 32 then java.util.Arrays.copyOfRange(payload, 1, 33)
    else throw new IllegalArgumentException(s"Unexpected Cardano public-key response length: ${payload.length}")
