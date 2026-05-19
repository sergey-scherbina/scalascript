package scalascript.blockchain.solana

import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scalascript.blockchain.spi.*
import scalascript.crypto.{Curve, CryptoBackend, HashAlgo, PublicKey}

/** Read-side tests for the SolanaChainAdapter. RPC is stubbed with
 *  canned responses matching the Solana JSON-RPC envelope shapes; a
 *  full integration test against `solana-test-validator` is a
 *  follow-up. */
class SolanaChainAdapterTest extends AnyFunSuite:
  given ExecutionContext = ExecutionContext.global

  private val adapter = new SolanaChainAdapter(SolanaChainAdapter.Mainnet)
  private val be      = CryptoBackend.get()

  // RFC 8032 test vector 1 — known (privkey → pubkey → signature).
  private val priv32  = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
  private val pub32   = be.derivePublic(Curve.Ed25519, priv32)
  // For the known privkey the pubkey is d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a.
  private val addr    = adapter.addressFromPublicKey(PublicKey(Curve.Ed25519, pub32))

  // ── addressFromPublicKey ──────────────────────────────────────────────

  test("addressFromPublicKey base58-encodes the 32-byte ed25519 pubkey") {
    val pkBytes = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
    val pk      = PublicKey(Curve.Ed25519, pkBytes)
    val a       = adapter.addressFromPublicKey(pk)
    // Round-trip: decoding the address yields the original pubkey.
    val decoded = Base58.decode(a)
    assert(decoded.toSeq == pkBytes.toSeq)
    // 32-byte ed25519 keys encode to 43-44 base58 chars depending on
    // the value of the high byte.
    assert(a.length >= 43 && a.length <= 44, s"unexpected length ${a.length}: $a")
  }

  test("addressFromPublicKey rejects non-ed25519 keys") {
    val pk = PublicKey(Curve.Secp256k1, new Array[Byte](64))
    intercept[IllegalArgumentException] {
      adapter.addressFromPublicKey(pk)
    }
  }

  test("isValidAddress accepts 32-byte base58, rejects everything else") {
    assert(adapter.isValidAddress("FdGYQdiRky8NtRmh3LvPqtxLE9Sq6daDek4WfEsmFrEx"))
    assert(adapter.isValidAddress("11111111111111111111111111111111"))  // System Program
    assert(!adapter.isValidAddress("not-an-address"))
    assert(!adapter.isValidAddress("0xabcdef"))
    assert(!adapter.isValidAddress(""))
  }

  test("normalizeAddress is identity for well-formed base58") {
    val a = "FdGYQdiRky8NtRmh3LvPqtxLE9Sq6daDek4WfEsmFrEx"
    assert(adapter.normalizeAddress(a) == a)
  }

  // ── signature verification (ed25519 has no recovery) ──────────────────

  test("recoverAddress is always None for Solana (ed25519 has no recovery)") {
    val sig = new Array[Byte](64)
    assert(adapter.recoverAddress(new Array[Byte](32), sig).isEmpty)
  }

  test("verifySignature accepts a valid ed25519 signature") {
    val msg = "hello solana".getBytes("UTF-8")
    val sig = be.sign(Curve.Ed25519, priv32, msg, HashAlgo.None)
    assert(adapter.verifySignature(msg, sig, addr))
  }

  test("verifySignature rejects a tampered signature") {
    val msg = "x".getBytes("UTF-8")
    val sig = be.sign(Curve.Ed25519, priv32, msg, HashAlgo.None)
    sig(10) = (sig(10) ^ 0x01).toByte
    assert(!adapter.verifySignature(msg, sig, addr))
  }

  test("verifySignature rejects signature from a different signer") {
    val otherPriv = hex("0102030405060708091011121314151617181920212223242526272829303132")
    val msg = "x".getBytes("UTF-8")
    val sig = be.sign(Curve.Ed25519, otherPriv, msg, HashAlgo.None)
    assert(!adapter.verifySignature(msg, sig, addr))
  }

  // ── typedDataDigest ───────────────────────────────────────────────────

  test("typedDataDigest(Raw) returns the bytes unchanged for ed25519's internal hashing") {
    val td  = TypedData.Raw("payload".getBytes("UTF-8"))
    val out = adapter.typedDataDigest(td)
    assert(out.toSeq == "payload".getBytes("UTF-8").toSeq)
  }

  test("typedDataDigest rejects EIP-712 (Solana-incompatible)") {
    intercept[IllegalArgumentException] {
      adapter.typedDataDigest(TypedData.Eip712(Map.empty, Map.empty, Map.empty, "X"))
    }
  }

  // ── queries via stubbed RPC ───────────────────────────────────────────

  private val canned = scala.collection.mutable.Map.empty[String, ujson.Value]
  private val ctx: ChainContext = new ChainContext:
    def rpcCall(method: String, params: ujson.Value*): Future[ujson.Value] =
      canned.get(method) match
        case Some(v) => Future.successful(v)
        case None    => Future.failed(new RuntimeException(s"unmocked: $method"))
    def nowSeconds: Long = 1700000000L

  test("nativeBalance reads getBalance.value as lamports") {
    canned.clear()
    // getBalance returns {context:{slot:N}, value: lamports}.
    canned("getBalance") = ujson.Obj(
      "context" -> ujson.Obj("slot" -> ujson.Num(123456)),
      "value"   -> ujson.Num(2_500_000_000L.toDouble),
    )
    val r = Await.result(adapter.nativeBalance(addr, ctx), 5.seconds)
    assert(r == BigInt(2_500_000_000L))
  }

  test("tokenBalance sums all SPL Token accounts the owner holds for a mint") {
    canned.clear()
    // Real Solana RPC shape — use a JSON literal because the
    // deeply-nested ujson.Obj literal is unreadable.
    canned("getTokenAccountsByOwner") = ujson.read("""
      {
        "context": {"slot": 0},
        "value": [
          {"account": {"data": {"parsed": {"info": {"tokenAmount": {"amount": "1000000"}}}}}},
          {"account": {"data": {"parsed": {"info": {"tokenAmount": {"amount": "500000"}}}}}}
        ]
      }
    """)
    val usdc = Asset(SolanaChainAdapter.Mainnet, "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", "USDC", 6)
    val r    = Await.result(adapter.tokenBalance(usdc, addr, ctx), 5.seconds)
    assert(r == BigInt(1_500_000))
  }

  test("call uses getAccountInfo and returns the base64-decoded data") {
    canned.clear()
    val payload = "hello".getBytes("UTF-8")
    val b64     = java.util.Base64.getEncoder.encodeToString(payload)
    canned("getAccountInfo") = ujson.Obj(
      "context" -> ujson.Obj("slot" -> ujson.Num(0)),
      "value"   -> ujson.Obj(
        "data"     -> ujson.Arr(ujson.Str(b64), ujson.Str("base64")),
        "executable" -> ujson.False,
        "owner"      -> ujson.Str("11111111111111111111111111111111"),
        "lamports"   -> ujson.Num(0),
      ),
    )
    val out = Await.result(adapter.call(addr, Array.emptyByteArray, ctx), 5.seconds)
    assert(out.toSeq == payload.toSeq)
  }

  test("supportedCurves is exactly Ed25519") {
    assert(adapter.supportedCurves == Seq(Curve.Ed25519))
  }

  // ── util ──────────────────────────────────────────────────────────────

  private def hex(s: String): Array[Byte] =
    val out = new Array[Byte](s.length / 2)
    var i = 0
    while i < out.length do
      out(i) = Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16).toByte
      i += 1
    out
