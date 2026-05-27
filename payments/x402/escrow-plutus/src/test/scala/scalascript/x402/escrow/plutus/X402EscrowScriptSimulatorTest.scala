package scalascript.x402.escrow.plutus

import org.scalatest.funsuite.AnyFunSuite
import scala.util.Try
import scalus.cardano.onchain.plutus.prelude.{List as SList, Option as SOption}
import scalus.cardano.onchain.plutus.v1.{Address, Credential, Interval, PubKeyHash, Value}
import scalus.cardano.onchain.plutus.v2.TxOut
import scalus.cardano.onchain.plutus.v3.{ScriptContext, ScriptInfo, TxId, TxInfo, TxOutRef}
import scalus.crypto.ed25519.SigningKey
import scalus.crypto.ed25519.JvmEd25519Signer
import scalus.uplc.builtin.{Builtins, ByteString, ToData}

class X402EscrowScriptSimulatorTest extends AnyFunSuite:

  private val privateKey =
    SigningKey.unsafeFromByteString(ByteString.fromHex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb"))
  private val payload = ByteString.fromHex("72" * 24)
  private val publicKey = JvmEd25519Signer.derivePublicKey(privateKey)
  private val signature = JvmEd25519Signer.sign(privateKey, cip8SigStructure(payload))
  private val amount = BigInt(2_000_000)
  private val validBefore = BigInt(2_000)
  private val refundAfter = BigInt(4_000)
  private val payerHash = PubKeyHash(Builtins.blake2b_224(publicKey))
  private val receiverHash = PubKeyHash(ByteString.fromHex("22" * 28))
  private val baseDatum = EscrowDatum(
    payerKeyHash     = payerHash,
    claimMessageHash = Builtins.blake2b_256(payload),
    receiverHash     = receiverHash,
    amount           = amount,
    validBefore      = validBefore,
    refundAfter      = refundAfter,
  )

  test("claim accepts canonical CIP-8 proof, receiver signature, exact output, and valid range") {
    val result = validateClaim()
    assert(result.isSuccess, result.failed.map(_.getMessage).getOrElse(""))
  }

  test("claim rejects a tampered CIP-8 signature") {
    assert(validateClaim(coseSign1 = coseSign1(sig = ByteString.fromHex("00" * 64))).isFailure)
  }

  test("claim rejects a wrong receiver output amount") {
    assert(validateClaim(outputAmount = amount - 1).isFailure)
  }

  test("claim rejects a validity range that reaches validBefore") {
    assert(validateClaim(validRange = Interval.entirelyBefore(validBefore + 1)).isFailure)
  }

  test("refund accepts payer signature only after refundAfter") {
    assert(validateRefund(signatories = SList.single(payerHash), validRange = Interval.after(refundAfter + 1)).isSuccess)
  }

  test("refund rejects a validity range before refundAfter") {
    assert(validateRefund(signatories = SList.single(payerHash), validRange = Interval.entirelyBefore(refundAfter)).isFailure)
  }

  private def validateClaim(
    datum:        EscrowDatum = baseDatum,
    coseSign1:    ByteString = coseSign1(),
    coseKey:      ByteString = coseKey(),
    outputAmount: BigInt = amount,
    validRange:   Interval = Interval.entirelyBefore(validBefore),
    signatories:  SList[PubKeyHash] = SList.single(receiverHash),
  ): Try[Unit] =
    val redeemer = EscrowRedeemer.Claim(coseSign1, coseKey)
    validate(datum, redeemer, txInfo(outputAmount, validRange, signatories))

  private def validateRefund(
    datum:       EscrowDatum = baseDatum,
    validRange:  Interval,
    signatories: SList[PubKeyHash],
  ): Try[Unit] =
    validate(datum, EscrowRedeemer.Refund, txInfo(amount, validRange, signatories))

  private def validate(datum: EscrowDatum, redeemer: EscrowRedeemer, txInfo: TxInfo): Try[Unit] =
    val ctx = ScriptContext(
      txInfo,
      summon[ToData[EscrowRedeemer]].apply(redeemer),
      ScriptInfo.SpendingScript(
        TxOutRef(TxId(ByteString.fromHex("11" * 32)), BigInt(0)),
        SOption.Some(summon[ToData[EscrowDatum]].apply(datum)),
      ),
    )
    Try(X402EscrowScript.validate(summon[ToData[ScriptContext]].apply(ctx)))

  private def txInfo(outputAmount: BigInt, validRange: Interval, signatories: SList[PubKeyHash]): TxInfo =
    val receiverAddress = Address(Credential.PubKeyCredential(receiverHash), SOption.None)
    val receiverOutput = TxOut(receiverAddress, Value.lovelace(outputAmount))
    TxInfo.placeholder.copy(
      outputs     = SList.single(receiverOutput),
      validRange  = validRange,
      signatories = signatories,
    )

  private def coseSign1(sig: ByteString = signature): ByteString =
    ByteString.fromHex("8443a10127a05818" + payload.toHex + "5840" + sig.toHex)

  private def coseKey(): ByteString =
    ByteString.fromHex("a4010103272006215820" + publicKey.toHex)

  private def cip8SigStructure(payload: ByteString): ByteString =
    ByteString.fromHex("846a5369676e61747572653143a10127405818" + payload.toHex)
