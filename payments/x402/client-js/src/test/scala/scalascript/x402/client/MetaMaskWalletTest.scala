package scalascript.x402.client

import org.scalatest.funsuite.AsyncFunSuite
import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal

@js.native @JSGlobal("globalThis")
private object GlobalThis extends js.Object:
  var window: js.Any = js.native

class MetaMaskWalletTest extends AsyncFunSuite:
  implicit override def executionContext: ExecutionContext = ExecutionContext.global

  private val account = "0x1111111111111111111111111111111111111111"

  private final class EthereumStub(
    accounts: js.Array[String] = js.Array(account),
    chainId: String = "0x2105",
    signature: String = "0x" + "ab" * 65,
  ):
    val calls = scala.collection.mutable.ArrayBuffer.empty[(String, js.Array[js.Any])]
    var typedDataJson: String = ""

    val handle: js.Dynamic = js.Dynamic.literal(
      request = (args: js.Dynamic) => {
        val method = args.method.asInstanceOf[String]
        val params =
          if js.isUndefined(args.params) then js.Array[js.Any]()
          else args.params.asInstanceOf[js.Array[js.Any]]
        calls += method -> params
        method match
          case "eth_requestAccounts" =>
            js.Promise.resolve(accounts.asInstanceOf[js.Any])
          case "eth_chainId" =>
            js.Promise.resolve(chainId.asInstanceOf[js.Any])
          case "eth_signTypedData_v4" =>
            typedDataJson = params(1).asInstanceOf[String]
            js.Promise.resolve(signature.asInstanceOf[js.Any])
          case other =>
            js.Promise.reject(new js.JavaScriptException(s"unexpected method: $other"))
      },
    )

  private def install(stub: EthereumStub): Unit =
    GlobalThis.window = js.Dynamic.literal(ethereum = stub.handle)

  test("Wallets.metaMask(network) connects accounts and checks chain id") {
    val stub = EthereumStub()
    install(stub)
    Wallets.metaMask(Network.Base).map { wallet =>
      assert(wallet.address == account)
      assert(wallet.network == Network.Base)
      assert(stub.calls.map(_._1).toSeq == Seq("eth_requestAccounts", "eth_chainId"))
    }
  }

  test("signEip712 delegates to eth_signTypedData_v4 with MetaMask JSON shape") {
    val stub = EthereumStub()
    install(stub)
    Wallets.metaMask(Network.Base).flatMap { wallet =>
      wallet.signEip712(
        Eip712Domain("USD Coin", "2", 8453, "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913"),
        Map("TransferWithAuthorization" -> Seq(
          "address" -> "from",
          "address" -> "to",
          "uint256" -> "value",
        )),
        Map("from" -> account, "to" -> "0x2222222222222222222222222222222222222222", "value" -> "100"),
      ).map { sig =>
        assert(sig == "0x" + "ab" * 65)
        val signCall = stub.calls.last
        assert(signCall._1 == "eth_signTypedData_v4")
        assert(signCall._2(0).asInstanceOf[String] == account)
        val json = ujson.read(stub.typedDataJson)
        assert(json("primaryType").str == "TransferWithAuthorization")
        assert(json("domain")("chainId").num.toInt == 8453)
        assert(json("types")("EIP712Domain").arr.exists(_("name").str == "verifyingContract"))
        assert(json("message")("value").str == "100")
      }
    }
  }

  test("Wallets.metaMask fails when window.ethereum is absent") {
    GlobalThis.window = js.Dynamic.literal()
    recoverToSucceededIf[UnsupportedOperationException] {
      Wallets.metaMask(Network.Base)
    }
  }

  test("Wallets.metaMask fails when MetaMask returns no accounts") {
    install(EthereumStub(accounts = js.Array()))
    recoverToSucceededIf[IllegalStateException] {
      Wallets.metaMask(Network.Base)
    }
  }

  test("Wallets.metaMask fails on unexpected chain id") {
    install(EthereumStub(chainId = "0x1"))
    recoverToSucceededIf[IllegalStateException] {
      Wallets.metaMask(Network.Base)
    }
  }

  test("Wallets.metaMask(address, network) uses an already connected account") {
    val stub = EthereumStub()
    install(stub)
    val wallet = Wallets.metaMask(account, Network.Base)
    wallet.signEip712(
      Eip712Domain("USD Coin", "2", 8453, "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913"),
      Map("TransferWithAuthorization" -> Seq("address" -> "from")),
      Map("from" -> account),
    ).map { sig =>
      assert(sig == "0x" + "ab" * 65)
      assert(!stub.calls.exists(_._1 == "eth_requestAccounts"))
    }
  }

  test("MetaMask wallet rejects Cardano CIP-8 signing") {
    val stub = EthereumStub()
    install(stub)
    Wallets.metaMask(Network.Base).flatMap { wallet =>
      recoverToSucceededIf[UnsupportedOperationException] {
        wallet.signCip8("hello".getBytes("UTF-8"))
      }
    }
  }
