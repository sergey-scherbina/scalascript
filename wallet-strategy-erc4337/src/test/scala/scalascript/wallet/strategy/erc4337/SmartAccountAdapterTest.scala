package scalascript.wallet.strategy.erc4337

import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scalascript.blockchain.evm.{EvmChainAdapter, Hex}
import scalascript.blockchain.spi.*
import scalascript.crypto.{Curve, CryptoBackend, HashAlgo, PublicKey}

class SmartAccountAdapterTest extends AnyFunSuite:
  given ExecutionContext = ExecutionContext.global

  private val evm  = new EvmChainAdapter(ChainId("eip155:1"))
  private val be   = CryptoBackend.get()
  private val priv = Hex.decode("ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80")
  private val pub  = be.derivePublic(Curve.Secp256k1, priv)
  private val owner = evm.addressFromPublicKey(PublicKey(Curve.Secp256k1, pub))

  // A stub factory we control fully — predictable address + initCode,
  // no real CREATE2 derivation needed for these tests.
  private val stubFactory = new SmartAccountFactory:
    def addressFor(o: String): String =
      "0x1111111111111111111111111111111111111111"
    def initCodeFor(o: String): Array[Byte] =
      Hex.decode("0xabcd")
    def executeCalldata(target: Option[String], value: BigInt, data: Array[Byte]): Array[Byte] =
      // Trivial encoding for test introspection: [target20B, value8B, data…]
      val out = new java.io.ByteArrayOutputStream()
      out.write(Hex.decode(target.getOrElse("0x0000000000000000000000000000000000000000")))
      out.write(value.toByteArray)
      out.write(data)
      out.toByteArray

  private def mockCtx(handle: (String, Seq[ujson.Value]) => Future[ujson.Value]): ChainContext =
    new ChainContext:
      def rpcCall(method: String, params: ujson.Value*): Future[ujson.Value] = handle(method, params)
      def nowSeconds: Long = 0L

  // ── building a UserOp ─────────────────────────────────────────────────

  test("buildTransaction(NativeTransfer) produces an unsigned UserOp with initCode when account undeployed") {
    val ctx = mockCtx { (method, _) =>
      method match
        case "eth_call" =>
          // The adapter does two `eth_call`s before broadcast: nonce
          // lookup against the EntryPoint, plus any future probes.
          // Return a uint256 zero (32-byte big-endian zero).
          Future.successful(ujson.Str("0x" + "00" * 32))
        case "eth_getCode" =>
          // Smart account is not deployed yet → empty code.
          Future.successful(ujson.Str("0x"))
        case "eth_maxPriorityFeePerGas" =>
          Future.successful(ujson.Str("0x3b9aca00"))    // 1 gwei
        case "eth_getBlockByNumber" =>
          Future.successful(ujson.Obj("baseFeePerGas" -> ujson.Str("0x77359400")))   // 2 gwei
        case "eth_estimateUserOperationGas" =>
          Future.successful(ujson.Obj(
            "callGasLimit"         -> ujson.Str("0x186a0"),  // 100k
            "verificationGasLimit" -> ujson.Str("0x249f0"),  // 150k
            "preVerificationGas"   -> ujson.Str("0xc350"),   // 50k
          ))
        case other =>
          Future.failed(new RuntimeException(s"unmocked: $other"))
    }
    val bundler = new BundlerClient(ctx)
    val adapter = SmartAccount.wrap(evm, owner, bundler, stubFactory)
    val intent  = TxIntent.NativeTransfer("0x70997970C51812dc3A010C7d01b50e0d17dc79C8", BigInt(42))
    val uo      = Await.result(adapter.buildTransaction(intent, owner, ctx), 5.seconds)

    assert(uo.sender == "0x1111111111111111111111111111111111111111")
    assert(uo.nonce == BigInt(0))
    assert(uo.initCode.toSeq == Hex.decode("0xabcd").toSeq,
      "initCode should be prepended when smart account has no code yet")
    assert(uo.callGasLimit         == BigInt(100_000))
    assert(uo.verificationGasLimit == BigInt(150_000))
    assert(uo.preVerificationGas   == BigInt(50_000))
    assert(uo.maxFeePerGas         == BigInt("5000000000"))    // 2*baseFee + prio = 2*2 + 1 = 5 gwei
    assert(uo.maxPriorityFeePerGas == BigInt("1000000000"))
    assert(uo.signature.isEmpty,                             "unsigned form must have empty signature")
    assert(uo.callData.length > 0)
  }

  test("buildTransaction reuses existing smart account: no initCode when code present") {
    val ctx = mockCtx { (method, _) =>
      method match
        case "eth_call"                 => Future.successful(ujson.Str("0x" + "00" * 32))
        case "eth_getCode"              => Future.successful(ujson.Str("0x6080604052"))  // non-empty
        case "eth_maxPriorityFeePerGas" => Future.successful(ujson.Str("0x1"))
        case "eth_getBlockByNumber"     => Future.successful(ujson.Obj("baseFeePerGas" -> ujson.Str("0x0")))
        case "eth_estimateUserOperationGas" =>
          Future.successful(ujson.Obj(
            "callGasLimit"         -> ujson.Str("0x1"),
            "verificationGasLimit" -> ujson.Str("0x1"),
            "preVerificationGas"   -> ujson.Str("0x1"),
          ))
        case other =>
          Future.failed(new RuntimeException(s"unmocked: $other"))
    }
    val bundler = new BundlerClient(ctx)
    val adapter = SmartAccount.wrap(evm, owner, bundler, stubFactory)
    val intent  = TxIntent.NativeTransfer("0x70997970C51812dc3A010C7d01b50e0d17dc79C8", BigInt(42))
    val uo      = Await.result(adapter.buildTransaction(intent, owner, ctx), 5.seconds)
    assert(uo.initCode.isEmpty,
      "initCode must be empty once the smart account is deployed (eth_getCode returns non-empty)")
  }

  // ── signing + broadcast ──────────────────────────────────────────────

  test("prepareSigningPayload + sign + assemble → 65-byte signature with v ∈ {27, 28}") {
    val ctx = mockCtx { (method, _) =>
      method match
        case "eth_call"                     => Future.successful(ujson.Str("0x" + "00" * 32))
        case "eth_getCode"                  => Future.successful(ujson.Str("0x6080"))
        case "eth_maxPriorityFeePerGas"     => Future.successful(ujson.Str("0x1"))
        case "eth_getBlockByNumber"         => Future.successful(ujson.Obj("baseFeePerGas" -> ujson.Str("0x0")))
        case "eth_estimateUserOperationGas" => Future.successful(ujson.Obj(
            "callGasLimit"         -> ujson.Str("0x1"),
            "verificationGasLimit" -> ujson.Str("0x1"),
            "preVerificationGas"   -> ujson.Str("0x1"),
          ))
        case other => Future.failed(new RuntimeException(s"unmocked: $other"))
    }
    val bundler  = new BundlerClient(ctx)
    val adapter  = SmartAccount.wrap(evm, owner, bundler, stubFactory)
    val intent   = TxIntent.NativeTransfer("0x70997970C51812dc3A010C7d01b50e0d17dc79C8", BigInt(1))
    val tx       = Await.result(adapter.buildTransaction(intent, owner, ctx), 5.seconds)
    val payload  = adapter.prepareSigningPayload(tx, PublicKey(Curve.Secp256k1, pub))
    // The payload should be the userOpHash itself (32 bytes, no further hashing).
    assert(payload.bytes.length == 32)
    assert(payload.hash == HashAlgo.None)
    assert(payload.bytes.toSeq == UserOpHash.compute(tx, bundler.entryPoint, BigInt(1)).toSeq)

    val sig = be.sign(Curve.Secp256k1, priv, payload.bytes, HashAlgo.None)
    val signed = adapter.assembleSignedTransaction(tx, sig, PublicKey(Curve.Secp256k1, pub))
    assert(signed.signature.length == 65)
    val v = signed.signature(64).toInt & 0xff
    assert(v == 27 || v == 28, s"v must be 27 or 28 for SimpleAccount compatibility, got $v")

    // Ecrecover round-trip: the signature's signer is our owner address.
    val recovered = adapter.recoverAddress(payload.bytes, signed.signature)
    assert(recovered.exists(_.equalsIgnoreCase(owner)))
  }

  test("broadcast posts the userOp JSON to the bundler and returns the userOpHash") {
    var lastPayload: Option[(String, Seq[ujson.Value])] = None
    val ctx = mockCtx { (method, params) =>
      method match
        case "eth_sendUserOperation" =>
          lastPayload = Some((method, params))
          Future.successful(ujson.Str("0xabc123"))
        case other =>
          Future.failed(new RuntimeException(s"unmocked: $other"))
    }
    val bundler = new BundlerClient(ctx)
    val adapter = SmartAccount.wrap(evm, owner, bundler, stubFactory)
    val signed  = UserOperation(
      sender = "0x1111111111111111111111111111111111111111",
      nonce = 0, initCode = Array.emptyByteArray, callData = Array.emptyByteArray,
      callGasLimit = 1, verificationGasLimit = 1, preVerificationGas = 1,
      maxFeePerGas = 1, maxPriorityFeePerGas = 1, paymasterAndData = Array.emptyByteArray,
      signature = new Array[Byte](65),
    )
    val hash = Await.result(adapter.broadcast(signed, ctx), 5.seconds)
    assert(hash.value == "0xabc123")

    // The first param must be the UserOp JSON; the second the EntryPoint address.
    val (_, params) = lastPayload.getOrElse(fail("bundler RPC not called"))
    assert(params.size == 2)
    assert(params(1).str.equalsIgnoreCase(EntryPoint.V06Address))
    val uoJson = params(0).obj
    assert(uoJson("sender").str == signed.sender)
    assert(uoJson("nonce").str  == "0x0")
    assert(uoJson("signature").str.length == 2 + 130, "sig is 65 bytes = 130 hex chars + 0x")
  }

  // ── receipts ─────────────────────────────────────────────────────────

  test("getReceipt parses the bundler's userOp receipt shape") {
    val ctx = mockCtx { (method, _) =>
      method match
        case "eth_getUserOperationReceipt" =>
          Future.successful(ujson.Obj(
            "userOpHash" -> ujson.Str("0xabc"),
            "sender"     -> ujson.Str("0x1111111111111111111111111111111111111111"),
            "success"    -> ujson.Bool(true),
            "receipt"    -> ujson.Obj(
              "transactionHash" -> ujson.Str("0xdef"),
              "blockNumber"     -> ujson.Str("0x10"),
              "gasUsed"         -> ujson.Str("0x5208"),
              "status"          -> ujson.Str("0x1"),
              "logs"            -> ujson.Arr(),
            ),
          ))
        case other =>
          Future.failed(new RuntimeException(s"unmocked: $other"))
    }
    val bundler = new BundlerClient(ctx)
    val adapter = SmartAccount.wrap(evm, owner, bundler, stubFactory)
    val r = Await.result(adapter.getReceipt(TxHash("0xabc"), ctx), 5.seconds)
    assert(r.isDefined)
    val rec = r.get
    assert(rec.success)
    assert(rec.hash.value == "0xdef")
    assert(rec.blockNumber == 16)
    assert(rec.gasUsed == 21000)
  }
