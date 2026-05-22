package scalascript.evm

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*

/** Unit tests for EvmClient helpers + integration tests (skipped without a node). */
class EvmClientTest extends AnyFunSuite with Matchers:

  given ExecutionContext = ExecutionContext.global

  private def await[A](f: scala.concurrent.Future[A]): A = Await.result(f, 10.seconds)

  private lazy val hasNode: Boolean =
    try
      val c = Evm.connect("https://eth.llamarpc.com")
      await(c.blockNumber()) > 0
    catch case _: Throwable => false

  // ── ABI helpers (pure, no network) ─────────────────────────────

  test("EvmConfig has correct chainId for known networks"):
    EvmNetworks.Base.chainId        shouldBe 8453
    EvmNetworks.BaseSepolia.chainId shouldBe 84532
    EvmNetworks.Ethereum.chainId    shouldBe 1
    EvmNetworks.Polygon.chainId     shouldBe 137

  // ── Integration: public Ethereum RPC ──────────────────────────

  test("blockNumber returns positive value"):
    assume(hasNode, "Public Ethereum RPC not available")
    val n = await(Evm.connect(EvmNetworks.Ethereum).blockNumber())
    n should be > BigInt(0)

  test("getBalance returns non-negative for Vitalik's address"):
    assume(hasNode, "Public Ethereum RPC not available")
    val bal = await(Evm.connect(EvmNetworks.Ethereum)
      .getBalance("0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045"))
    bal should be >= BigInt(0)

  test("erc20Decimals returns 6 for USDC on Ethereum"):
    assume(hasNode, "Public Ethereum RPC not available")
    val decimals = await(Evm.connect(EvmNetworks.Ethereum)
      .erc20Decimals("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"))
    decimals shouldBe 6

  test("erc20Symbol returns USDC for USDC on Ethereum"):
    assume(hasNode, "Public Ethereum RPC not available")
    val sym = await(Evm.connect(EvmNetworks.Ethereum)
      .erc20Symbol("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"))
    sym shouldBe "USDC"

  test("raw rpc call eth_chainId returns 0x1 for Ethereum"):
    assume(hasNode, "Public Ethereum RPC not available")
    val result = await(Evm.connect(EvmNetworks.Ethereum).rpc("eth_chainId"))
    result.str shouldBe "0x1"
