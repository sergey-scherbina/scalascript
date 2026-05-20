package scalascript.wallet.strategy.erc4337

import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scalascript.blockchain.evm.Hex
import scalascript.blockchain.spi.ChainContext

/** Tests for the v0.7 wire-format dispatch in BundlerClient.
 *
 *  We sanity-check that:
 *    - the JSON shape submitted to the bundler matches the v0.7 spec
 *      (no `initCode` / `paymasterAndData`; structured split fields
 *      appear iff their source blob is non-empty),
 *    - `userOpHash(...)` routes through `UserOpHashV07.compute`, not
 *      the v0.6 path,
 *    - the default EntryPoint address flips to V07 when the
 *      `BundlerClient.v07` factory is used. */
class BundlerClientV07Test extends AnyFunSuite:
  given ExecutionContext = ExecutionContext.global

  private def mockCtx(handle: (String, Seq[ujson.Value]) => Future[ujson.Value]): ChainContext =
    new ChainContext:
      def rpcCall(method: String, params: ujson.Value*): Future[ujson.Value] = handle(method, params)
      def nowSeconds: Long = 0L

  private val bare = UserOperation(
    sender               = "0x9406Cc6185a346906296840746125a0E44976454",
    nonce                = BigInt(7),
    initCode             = Array.emptyByteArray,
    callData             = Hex.decode("0xdeadbeef"),
    callGasLimit         = BigInt(100_000),
    verificationGasLimit = BigInt(150_000),
    preVerificationGas   = BigInt(50_000),
    maxFeePerGas         = BigInt(2_000_000_000L),
    maxPriorityFeePerGas = BigInt(1_000_000_000L),
    paymasterAndData     = Array.emptyByteArray,
    signature            = new Array[Byte](65),
  )

  test("BundlerClient.v07 sets V07 default address and version") {
    val ctx = mockCtx { (m, _) => Future.failed(new RuntimeException(s"unmocked $m")) }
    val bc  = BundlerClient.v07(ctx)
    assert(bc.entryPoint.equalsIgnoreCase(EntryPoint.V07Address))
    assert(bc.version == EntryPoint.Version.V07)
  }

  test("v0.7 sendUserOperation JSON omits initCode + paymasterAndData when sections are empty") {
    var got: ujson.Value = ujson.Null
    val ctx = mockCtx { (m, params) =>
      if m == "eth_sendUserOperation" then
        got = params(0)
        Future.successful(ujson.Str("0xhash"))
      else Future.failed(new RuntimeException(s"unmocked $m"))
    }
    val bc = BundlerClient.v07(ctx)
    Await.result(bc.sendUserOperation(bare), 5.seconds)

    val obj = got.obj
    assert(!obj.contains("initCode"),
      "v0.7 has no initCode field — factory/factoryData take its place")
    assert(!obj.contains("paymasterAndData"),
      "v0.7 has no paymasterAndData field — paymaster + split fields take its place")
    // No factory/paymaster sections when their sources are empty.
    assert(!obj.contains("factory"),     "empty initCode → no factory key")
    assert(!obj.contains("factoryData"), "empty initCode → no factoryData key")
    assert(!obj.contains("paymaster"),   "empty paymasterAndData → no paymaster key")
    // Core fields are still there.
    assert(obj("sender").str == bare.sender)
    assert(obj("nonce").str  == "0x7")
    assert(obj("callData").str.equalsIgnoreCase("0xdeadbeef"))
    assert(obj("callGasLimit").str  == "0x186a0")
    assert(obj("maxFeePerGas").str  == "0x77359400")
    assert(obj("signature").str.length == 2 + 130)
  }

  test("v0.7 JSON splits initCode into factory + factoryData") {
    val factoryAddr   = Hex.decode("0xaabbccddeeff00112233445566778899aabbccdd")
    val factoryData   = Hex.decode("0x1122334455")
    val withDeploy    = bare.copy(initCode = factoryAddr ++ factoryData)

    var got: ujson.Value = ujson.Null
    val ctx = mockCtx { (m, params) =>
      if m == "eth_sendUserOperation" then
        got = params(0)
        Future.successful(ujson.Str("0xhash"))
      else Future.failed(new RuntimeException(s"unmocked $m"))
    }
    Await.result(BundlerClient.v07(ctx).sendUserOperation(withDeploy), 5.seconds)
    val obj = got.obj
    assert(obj("factory").str.equalsIgnoreCase("0x" + Hex.encode(factoryAddr, withPrefix = false)))
    assert(obj("factoryData").str.equalsIgnoreCase("0x" + Hex.encode(factoryData, withPrefix = false)))
  }

  test("v0.7 JSON splits paymasterAndData into paymaster + two u128 gas limits + data") {
    val pAddr     = Hex.decode("0x1111111111111111111111111111111111111111")
    val pVerGas16 = new Array[Byte](16); pVerGas16(15) = 0x42        // 0x42 (u128)
    val pPostOp16 = new Array[Byte](16); pPostOp16(14) = 0x01; pPostOp16(15) = 0x00  // 0x100
    val pData     = Hex.decode("0xcafebabe")
    val withPaym  = bare.copy(paymasterAndData = pAddr ++ pVerGas16 ++ pPostOp16 ++ pData)

    var got: ujson.Value = ujson.Null
    val ctx = mockCtx { (m, params) =>
      if m == "eth_sendUserOperation" then
        got = params(0)
        Future.successful(ujson.Str("0xhash"))
      else Future.failed(new RuntimeException(s"unmocked $m"))
    }
    Await.result(BundlerClient.v07(ctx).sendUserOperation(withPaym), 5.seconds)
    val obj = got.obj
    assert(obj("paymaster").str.equalsIgnoreCase("0x" + Hex.encode(pAddr, withPrefix = false)))
    assert(obj("paymasterVerificationGasLimit").str == "0x42")
    assert(obj("paymasterPostOpGasLimit").str       == "0x100")
    assert(obj("paymasterData").str.equalsIgnoreCase("0x" + Hex.encode(pData, withPrefix = false)))
  }

  test("v0.7 BundlerClient.userOpHash routes through UserOpHashV07") {
    val ctx = mockCtx { (m, _) => Future.failed(new RuntimeException(s"unmocked $m")) }
    val bc  = BundlerClient.v07(ctx)
    val h   = bc.userOpHash(bare, BigInt(1))
    assert(h.toSeq == UserOpHashV07.compute(bare, EntryPoint.V07Address, BigInt(1)).toSeq)
    // And it must NOT match the v0.6 hash.
    val v6  = UserOpHash.compute(bare, EntryPoint.V06Address, BigInt(1))
    assert(h.toSeq != v6.toSeq)
  }

  test("default BundlerClient (v0.6) JSON still has the legacy flat shape") {
    var got: ujson.Value = ujson.Null
    val ctx = mockCtx { (m, params) =>
      if m == "eth_sendUserOperation" then
        got = params(0)
        Future.successful(ujson.Str("0xhash"))
      else Future.failed(new RuntimeException(s"unmocked $m"))
    }
    Await.result(new BundlerClient(ctx).sendUserOperation(bare), 5.seconds)
    val obj = got.obj
    assert(obj.contains("initCode"),         "v0.6 keeps flat initCode")
    assert(obj.contains("paymasterAndData"), "v0.6 keeps flat paymasterAndData")
    assert(!obj.contains("factory"),         "v0.6 must not synthesise v0.7 keys")
    assert(!obj.contains("paymaster"),       "v0.6 must not synthesise v0.7 keys")
  }
