package scalascript.blockchain.evm

import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.ExecutionContext
import scalascript.blockchain.spi.*
import scalascript.crypto.*

/** Vector tests for the EVM-side primitives. The chain RPC methods
 *  (`nativeBalance` / `tokenBalance` / `getReceipt`) are exercised
 *  via the x402 facilitator integration in Slice 4 — here we only
 *  verify the deterministic, off-chain operations: address derivation,
 *  EIP-55, EIP-712 hashing, ecrecover. */
class EvmChainAdapterTest extends AnyFunSuite:
  given ExecutionContext = ExecutionContext.global

  private val adapter = new EvmChainAdapter(ChainId.EthereumMainnet)

  // ── address derivation ────────────────────────────────────────────────

  test("addressFromPublicKey matches EIP-712 reference (privkey 0x46…46)") {
    // From the EIP-712 docs and Ethereum book:
    //   privKey = 0x4646464646464646464646464646464646464646464646464646464646464646
    //   address = 0x9d8a62f656a8d1615c1294fd71e9cfb3e4855a4f
    val priv = hex("4646464646464646464646464646464646464646464646464646464646464646")
    val pub  = PublicKey(Curve.Secp256k1, CryptoBackend.get().derivePublic(Curve.Secp256k1, priv))
    val addr = adapter.addressFromPublicKey(pub)
    assert(addr.equalsIgnoreCase("0x9d8a62f656a8d1615c1294fd71e9cfb3e4855a4f"))
  }

  test("EIP-55 checksum: mixed-case reference addresses") {
    // EIP-55 examples from the spec itself:
    val cases = Seq(
      "0x52908400098527886E0F7030069857D2E4169EE7" -> "0x52908400098527886E0F7030069857D2E4169EE7",
      "0xfb6916095ca1df60bb79ce92ce3ea74c37c5d359" -> "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
      "0xde709f2102306220921060314715629080e2fb77" -> "0xde709f2102306220921060314715629080e2fb77",
    )
    for (in, expected) <- cases do
      assert(adapter.normalizeAddress(in) == expected, s"failed for $in")
  }

  test("isValidAddress accepts 20-byte hex with or without 0x") {
    assert(adapter.isValidAddress("0x52908400098527886E0F7030069857D2E4169EE7"))
    assert(adapter.isValidAddress("52908400098527886E0F7030069857D2E4169EE7"))
    assert(!adapter.isValidAddress("0xZZZ"))
    assert(!adapter.isValidAddress("0x52908400098527886E0F7030069857D2E4169EE"))   // 39 chars
  }

  // ── EIP-712 ───────────────────────────────────────────────────────────

  test("EIP-712 digest matches reference \"Mail\" example") {
    // From EIP-712 spec §"Specification of the eth_signTypedData JSON RPC":
    //   Domain: { name: "Ether Mail", version: "1", chainId: 1,
    //             verifyingContract: 0xCcCCccccCCCCcCCCCCCcCcCccCcCCCcCcccccccC }
    //   Types: EIP712Domain, Person { name string, wallet address },
    //          Mail { from Person, to Person, contents string }
    //   Value:
    //     from = { name: "Cow",    wallet: 0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826 }
    //     to   = { name: "Bob",    wallet: 0xbBbBBBBbbBBBbbbBbbBbbbbBBbBbbbbBbBbbBBbB }
    //     contents = "Hello, Bob!"
    //   Expected digest:
    //     0xbe609aee343fb3c4b28e1df9e632fca64fcfaede20f02e86244efddf30957bd2
    val td = TypedData.Eip712(
      primaryType = "Mail",
      domain = Map(
        "name"              -> ujson.Str("Ether Mail"),
        "version"           -> ujson.Str("1"),
        "chainId"           -> ujson.Num(1),
        "verifyingContract" -> ujson.Str("0xCcCCccccCCCCcCCCCCCcCcCccCcCCCcCcccccccC"),
      ),
      types = Map(
        "EIP712Domain" -> Seq(
          "string"  -> "name",
          "string"  -> "version",
          "uint256" -> "chainId",
          "address" -> "verifyingContract",
        ),
        "Person" -> Seq(
          "string"  -> "name",
          "address" -> "wallet",
        ),
        "Mail" -> Seq(
          "Person" -> "from",
          "Person" -> "to",
          "string" -> "contents",
        ),
      ),
      value = Map(
        "from" -> ujson.Obj(
          "name"   -> ujson.Str("Cow"),
          "wallet" -> ujson.Str("0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826"),
        ),
        "to" -> ujson.Obj(
          "name"   -> ujson.Str("Bob"),
          "wallet" -> ujson.Str("0xbBbBBBBbbBBBbbbBbbBbbbbBBbBbbbbBbBbbBBbB"),
        ),
        "contents" -> ujson.Str("Hello, Bob!"),
      ),
    )
    val digest = adapter.typedDataDigest(td)
    val hex    = "0x" + digest.map(b => f"${b & 0xff}%02x").mkString
    assert(hex == "0xbe609aee343fb3c4b28e1df9e632fca64fcfaede20f02e86244efddf30957bd2")
  }

  // ── ecrecover / verifySignature ───────────────────────────────────────

  test("recoverAddress round-trips sign output") {
    val priv   = hex("4646464646464646464646464646464646464646464646464646464646464646")
    val pub    = PublicKey(Curve.Secp256k1, CryptoBackend.get().derivePublic(Curve.Secp256k1, priv))
    val addr   = adapter.addressFromPublicKey(pub)
    val digest = CryptoBackend.get().hash(HashAlgo.Keccak256, "recover me".getBytes("UTF-8"))
    val sig    = CryptoBackend.get().sign(Curve.Secp256k1, priv, digest, HashAlgo.None)
    val recovered = adapter.recoverAddress(digest, sig)
    assert(recovered.isDefined, "recoverAddress returned None")
    assert(recovered.get.equalsIgnoreCase(addr))
  }

  test("verifySignature accepts matching signer and rejects mismatch") {
    val priv   = hex("0101010101010101010101010101010101010101010101010101010101010101")
    val pub    = PublicKey(Curve.Secp256k1, CryptoBackend.get().derivePublic(Curve.Secp256k1, priv))
    val addr   = adapter.addressFromPublicKey(pub)
    val digest = CryptoBackend.get().hash(HashAlgo.Sha256, "hello".getBytes("UTF-8"))
    val sig    = CryptoBackend.get().sign(Curve.Secp256k1, priv, digest, HashAlgo.None)
    assert(adapter.verifySignature(digest, sig, addr))
    assert(!adapter.verifySignature(digest, sig, "0x0000000000000000000000000000000000000000"))
  }

  test("recoverAddress handles `v` encoded as 27/28 instead of 0/1") {
    val priv   = hex("0202020202020202020202020202020202020202020202020202020202020202")
    val pub    = PublicKey(Curve.Secp256k1, CryptoBackend.get().derivePublic(Curve.Secp256k1, priv))
    val addr   = adapter.addressFromPublicKey(pub)
    val digest = CryptoBackend.get().hash(HashAlgo.Sha256, "foo".getBytes)
    val sig    = CryptoBackend.get().sign(Curve.Secp256k1, priv, digest, HashAlgo.None)
    // Mutate recId byte: 0/1 → 27/28
    val raw = sig.clone()
    raw(64) = (raw(64) + 27).toByte
    val recovered = adapter.recoverAddress(digest, raw)
    assert(recovered.isDefined)
    assert(recovered.get.equalsIgnoreCase(addr))
  }

  // ── ABI helpers ───────────────────────────────────────────────────────

  test("ERC-20 balanceOf selector is 0x70a08231") {
    val sel = AbiHelpers.Erc20BalanceOfSelector
    val hex = sel.map(b => f"${b & 0xff}%02x").mkString
    assert(hex == "70a08231")
  }

  test("erc20BalanceOfCalldata pads address correctly") {
    val data = AbiHelpers.erc20BalanceOfCalldata("0x1234567890abcdef1234567890abcdef12345678")
    val hex  = data.map(b => f"${b & 0xff}%02x").mkString
    // selector 4 bytes + 12 zero padding + 20 address = 36 bytes
    assert(hex == "70a08231" + "000000000000000000000000" + "1234567890abcdef1234567890abcdef12345678")
  }

  // ── utility ────────────────────────────────────────────────────────────

  private def hex(s: String): Array[Byte] =
    val out = new Array[Byte](s.length / 2)
    var i = 0
    while i < out.length do
      out(i) = Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16).toByte
      i += 1
    out
