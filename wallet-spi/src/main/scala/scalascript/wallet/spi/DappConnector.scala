package scalascript.wallet.spi

import scala.concurrent.Future
import scalascript.blockchain.spi.{ChainAdapter, ChainId, TypedData}

/** A request from a dApp / agent to perform a wallet operation.
 *  Connectors translate their protocol's request shapes into these
 *  variants; the `AccountManager` dispatches to the right
 *  `AccountStrategy` and `ChainAdapter`. */
sealed trait DappRequest:
  def chainId: ChainId

object DappRequest:
  case class GetAddress(chainId: ChainId) extends DappRequest

  case class SignMessage(chainId: ChainId, address: String, message: Array[Byte]) extends DappRequest

  case class SignTypedData(chainId: ChainId, address: String, typed: TypedData) extends DappRequest

  /** Build, sign, and broadcast. The connector typically wraps this
   *  in a host confirmation flow (MetaMask popup, MCP elicitation,
   *  Ledger device prompt). */
  case class SendTransaction(chainId: ChainId, address: String, calldata: Array[Byte], to: String, value: BigInt) extends DappRequest

  /** Capability negotiation — returned the set of chains the wallet
   *  is willing to expose, in CAIP-2 form. */
  case class GetChains() extends DappRequest:
    def chainId: ChainId = ChainId("caip:unknown")

sealed trait DappResponse

object DappResponse:
  case class Ok(value: ujson.Value)         extends DappResponse
  case class Rejected(reason: String)       extends DappResponse
  case class Error(code: Int, message: String) extends DappResponse

/** Façade that connectors call into. The host wires this up at
 *  startup with the set of available chains, adapters, and strategies;
 *  connectors (EIP-1193, Wallet Standard, WalletConnect v2) translate
 *  their wire protocols into `request` calls.
 *
 *  See docs/wallet-spi.md §7. */
trait AccountManager:
  def chains: Set[ChainId]
  def strategyFor(chain: ChainId): Option[AccountStrategy]
  def adapterFor(chain: ChainId): Option[ChainAdapter]
  def request(req: DappRequest): Future[DappResponse]

/** Bridges a dApp protocol (EIP-1193 / Wallet Standard / WalletConnect)
 *  to the wallet's `AccountManager`. */
trait DappConnector:
  /** Stable identifier: "eip-1193", "wallet-standard", "walletconnect-v2". */
  def protocol: String
  def attach(manager: AccountManager): Unit
  def detach(): Unit
