package scalascript.blockchain.evm

import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scalascript.blockchain.spi.*
import scalascript.crypto.{CryptoBackend, HashAlgo}

/** Tests for the typed ERC-20 proxy + event decoders. */
class Erc20Test extends AnyFunSuite:
  given ExecutionContext = ExecutionContext.global

  private val adapter = new EvmChainAdapter(ChainId.Base)
  private val usdc    = new Erc20("0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913", adapter)
  private val alice   = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  private val bob     = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

  // ── proxy reads through `chain.call` ──────────────────────────────────

  test("Erc20.balanceOf encodes balanceOf(address) calldata and decodes uint256") {
    var capturedCalldata: Option[Array[Byte]] = None
    val ctx = new ChainContext:
      def rpcCall(method: String, params: ujson.Value*): Future[ujson.Value] =
        if method == "eth_call" then
          val data = params.head.obj("data").str.stripPrefix("0x")
          capturedCalldata = Some(decodeHex(data))
          // Return 1_500_000 as a uint256 (0x16E360 padded to 32 bytes)
          val ret = "00" * 29 + "16e360"
          Future.successful(ujson.Str("0x" + ret))
        else Future.failed(new RuntimeException(s"unexpected: $method"))
      def nowSeconds: Long = 0L

    val balance = Await.result(usdc.balanceOf(alice, ctx), 5.seconds)
    assert(balance == BigInt(1_500_000))
    // Calldata: 0x70a08231 (balanceOf selector) || pad32(alice)
    val cd = capturedCalldata.get
    assert(cd.take(4).map(b => f"${b & 0xff}%02x").mkString == "70a08231")
    assert(cd.length == 36)
  }

  test("Erc20.decimals returns 6 for a USDC-style contract") {
    val ctx = new ChainContext:
      def rpcCall(method: String, params: ujson.Value*): Future[ujson.Value] =
        if method == "eth_call" then
          Future.successful(ujson.Str("0x" + "00" * 31 + "06"))
        else Future.failed(new RuntimeException(s"unexpected: $method"))
      def nowSeconds: Long = 0L
    val d = Await.result(usdc.decimals(ctx), 5.seconds)
    assert(d == 6)
  }

  // ── proxy writes return a TxIntent (no I/O) ──────────────────────────

  test("Erc20.transfer(...) returns a ContractCall with correct calldata") {
    val intent = usdc.transfer(bob, BigInt(1_000_000))
    assert(intent.target == usdc.address)
    val expected =
      "a9059cbb" +
      "000000000000000000000000" + bob.stripPrefix("0x") +
      "00000000000000000000000000000000000000000000000000000000000f4240"
    assert(intent.calldata.map(b => f"${b & 0xff}%02x").mkString == expected)
  }

  test("Erc20.approve(...) returns a ContractCall") {
    val intent = usdc.approve(bob, BigInt(1_000_000_000))
    assert(intent.target == usdc.address)
    // selector for approve(address,uint256) = 0x095ea7b3
    assert(intent.calldata.take(4).map(b => f"${b & 0xff}%02x").mkString == "095ea7b3")
  }

  test("Erc20.transferWithAuthorization splits the 65-byte sig into r/s/v") {
    val sig = new Array[Byte](65)
    var i = 0
    while i < 65 do { sig(i) = i.toByte; i += 1 }
    sig(64) = 0x1b   // v = 27
    val intent = usdc.transferWithAuthorization(
      from        = alice,
      to          = bob,
      value       = BigInt(1_000_000),
      validAfter  = BigInt(0),
      validBefore = BigInt(9_999_999_999L),
      nonce       = decodeHex("ab" * 32),
      signature   = sig,
    )
    assert(intent.target == usdc.address)
    // selector 0xe3ee160e
    assert(intent.calldata.take(4).map(b => f"${b & 0xff}%02x").mkString == "e3ee160e")
    // After selector + 6×32 byte args (from, to, value, validAfter,
    // validBefore, nonce) + uint8(v)=0x1b => v word at offset 4+32*6 = 196.
    // The v byte is in the LOW byte of the 32-byte word, so byte index
    // 4 + 32*6 + 31 == 227.
    val vByte = intent.calldata(4 + 32 * 6 + 31).toInt & 0xff
    assert(vByte == 27)
  }

  // ── event log decoding ────────────────────────────────────────────────

  test("Erc20.Transfer.topic0 is the canonical hash") {
    // keccak256("Transfer(address,address,uint256)") =
    //   0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef
    val expected = CryptoBackend.get().hash(
      HashAlgo.Keccak256,
      "Transfer(address,address,uint256)".getBytes("UTF-8"),
    )
    assert(Erc20.Transfer.topic0.sameElements(expected))
    assert(Erc20.Transfer.topic0.map(b => f"${b & 0xff}%02x").mkString ==
      "ddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef")
  }

  test("Erc20.Transfer.from decodes from→to→value triples") {
    val from = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    val to   = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    val log  = Log(
      address = usdc.address,
      topics  = Seq(
        Erc20.Transfer.topic0,
        decodeHex("00" * 12 + from.stripPrefix("0x")),
        decodeHex("00" * 12 + to.stripPrefix("0x")),
      ),
      data = decodeHex("00" * 31 + "01"),  // value = 1
    )
    val events = Erc20.Transfer.from(Seq(log))
    assert(events.size == 1)
    val e = events.head
    assert(e.token == usdc.address)
    assert(e.from.equalsIgnoreCase(from))
    assert(e.to.equalsIgnoreCase(to))
    assert(e.value == BigInt(1))
  }

  test("Erc20.Transfer.from ignores non-Transfer logs") {
    val unrelated = Log(
      address = usdc.address,
      topics  = Seq(decodeHex("ff" * 32)),
      data    = Array.emptyByteArray,
    )
    assert(Erc20.Transfer.from(Seq(unrelated)).isEmpty)
  }

  // ── util ──────────────────────────────────────────────────────────────

  private def decodeHex(s: String): Array[Byte] =
    val clean = s.stripPrefix("0x")
    val out = new Array[Byte](clean.length / 2)
    var i = 0
    while i < out.length do
      out(i) = Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16).toByte
      i += 1
    out
