package scalascript.wallet.strategy.erc4337

import scala.concurrent.ExecutionContext
import scalascript.blockchain.evm.EvmChainAdapter

/** Top-level convenience: pair an EvmChainAdapter with a bundler +
 *  factory to get a SmartAccountAdapter ready for an EoaStrategy.
 *
 *  Caller flow:
 *
 *      val adapter = SmartAccount.wrap(
 *        underlying = EvmChainAdapter.baseSepolia,
 *        owner      = "0x…EOA address…",
 *        bundler    = new BundlerClient(httpCtx, EntryPoint.V06Address),
 *        factory    = new SimpleAccountFactory(factoryAddr, codeHash),
 *      )
 *      val signed = eoaStrategy.signTransaction(adapter)(unsigned)
 *      val hash   = await(adapter.broadcast(signed, ctx))
 *
 *  Switching from an EOA tx to a smart-account UserOp is a pure
 *  adapter swap on the call site — EoaStrategy doesn't know or
 *  care that what it's signing is a userOpHash rather than an
 *  EIP-1559 sighash. */
object SmartAccount:

  def wrap(
    underlying:             EvmChainAdapter,
    owner:                  String,
    bundler:                BundlerClient,
    factory:                SmartAccountFactory,
    accountAddressOverride: Option[String] = None,
  )(using ExecutionContext): SmartAccountAdapter =
    new SmartAccountAdapter(underlying, owner, factory, bundler, accountAddressOverride)
