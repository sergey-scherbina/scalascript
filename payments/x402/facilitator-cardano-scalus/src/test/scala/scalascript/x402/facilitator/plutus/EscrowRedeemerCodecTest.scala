package scalascript.x402.facilitator.plutus

import com.bloxbean.cardano.client.plutus.spec.{BytesPlutusData, ConstrPlutusData}
import org.scalatest.funsuite.AnyFunSuite
import scalascript.x402.CardanoPaymentProof

class EscrowRedeemerCodecTest extends AnyFunSuite:

  test("claim: encodes Claim(coseSign1, coseKey) as constructor 0") {
    val redeemer = EscrowRedeemerCodec.claim(CardanoPaymentProof(
      address   = "addr_test1payer",
      signature = "c0ffee",
      key       = "cafe",
    ))

    val claim = redeemer.asInstanceOf[ConstrPlutusData]
    assert(claim.getAlternative == 0L)
    val fields = claim.getData.getPlutusDataList
    assert(fields.size == 2)
    assert(fields.get(0).asInstanceOf[BytesPlutusData].getValue.toSeq == Seq[Byte](0xc0.toByte, 0xff.toByte, 0xee.toByte))
    assert(fields.get(1).asInstanceOf[BytesPlutusData].getValue.toSeq == Seq[Byte](0xca.toByte, 0xfe.toByte))
  }

  test("claim: rejects malformed proof hex") {
    intercept[IllegalArgumentException] {
      EscrowRedeemerCodec.claim(CardanoPaymentProof("addr", "abc", "00"))
    }
    intercept[IllegalArgumentException] {
      EscrowRedeemerCodec.claim(CardanoPaymentProof("addr", "zz", "00"))
    }
  }
