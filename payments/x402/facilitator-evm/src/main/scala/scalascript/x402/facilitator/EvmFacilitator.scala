package scalascript.x402.facilitator

import scalascript.x402.*
import scalascript.evm.EvmClient
import scalascript.blockchain.spi.{ChainContext, ChainId}
import scalascript.blockchain.evm.{Eip3009, Erc20, EvmChainAdapter, Hex}
import scalascript.crypto.Curve
import scalascript.wallet.strategy.eoa.{EoaStrategy, RawPrivateKeyVault}
import scala.concurrent.{ExecutionContext, Future}

// ── EVM facilitator config ────────────────────────────────────────────────────

case class EvmFacilitatorConfig(
  evm:     EvmClient,
  /** Optional facilitator relayer wallet (hex private key with or
   *  without `0x` prefix). When set and `settler` is unset, `settle`
   *  builds + signs + broadcasts the real `transferWithAuthorization`
   *  call on chain — the operator funds this wallet with native
   *  coin for gas. */
  relayerKeyHex: Option[String] = None,
  /** Custom settle escape hatch — takes precedence over the relayer
   *  path when present. */
  settler: Option[(PaymentPayload, PaymentRequirements) => Future[SettleResult]] = None,
)

// ── EVM facilitator ───────────────────────────────────────────────────────────
// Verify: signature recovery + token balance + expiry + payTo match.
// Settle: delegate to configured settler, or return testnet Ok if none provided.
//
// Signature verification was missing pre-Phase-1 (see specs/wallet-spi.md §9
// and specs/blockchain-spi.md §9.1) — a SHA-256 stub from x402-client would
// silently pass `verify` even though it could never settle on-chain. Now we
// reconstruct the canonical EIP-712 / EIP-3009 typed-data digest and call
// ecrecover via blockchain-evm before checking balance.

class EvmFacilitatorImpl(config: EvmFacilitatorConfig)(using ec: ExecutionContext)
    extends Facilitator:

  def verify(payload: PaymentPayload, req: PaymentRequirements): Future[VerifyResult] =
    val auth = payload.authorization
    val now  = BigInt(System.currentTimeMillis() / 1000)

    if auth.validBefore <= now then
      return Future.successful(VerifyResult.Fail("Authorization expired"))
    if auth.to.toLowerCase != req.payTo.toLowerCase then
      return Future.successful(
        VerifyResult.Fail(s"Payment destination mismatch: ${auth.to} != ${req.payTo}"),
      )

    // ── Signature recovery via EIP-3009 typed-data digest ──
    val recoveryResult: Either[String, Unit] =
      try
        val typedData = Eip3009.usdcTransferWithAuthorization(
          tokenAddress = req.asset.address,
          chainId      = req.network.chainId,
          from         = auth.from,
          to           = auth.to,
          value        = auth.value,
          validAfter   = auth.validAfter,
          validBefore  = auth.validBefore,
          nonceHex     = auth.nonce,
        )
        val adapter = new EvmChainAdapter(ChainId(s"eip155:${req.network.chainId}"))
        val digest  = adapter.typedDataDigest(typedData)
        val sig     = Hex.decode(payload.signature)
        adapter.recoverAddress(digest, sig) match
          case None =>
            Left("Signature recovery failed (malformed signature?)")
          case Some(recovered) if !recovered.equalsIgnoreCase(auth.from) =>
            Left(s"Signature mismatch: recovered $recovered, claimed ${auth.from}")
          case Some(_) =>
            Right(())
      catch
        case ex: Throwable => Left(s"Signature verification error: ${ex.getMessage}")

    recoveryResult match
      case Left(msg) =>
        Future.successful(VerifyResult.Fail(msg))
      case Right(_) =>
        config.evm.erc20Balance(req.asset.address, auth.from).map { balanceInt =>
          val balance = BigDecimal(balanceInt)
          if balance >= BigDecimal(auth.value) then VerifyResult.Ok
          else VerifyResult.Fail(s"Insufficient balance: $balanceInt < ${auth.value}")
        }.recover { case ex =>
          VerifyResult.Fail(s"EVM verify error: ${ex.getMessage}")
        }

  def settle(payload: PaymentPayload, req: PaymentRequirements): Future[SettleResult] =
    (config.settler, config.relayerKeyHex) match
      case (Some(custom), _) => custom(payload, req)
      case (None, Some(key)) => settleOnChain(payload, req, key)
      case (None, None)      =>
        // No relayer + no custom settler — keep the historical zeroed-
        // hash stub for tests / examples that exercise the verify
        // path only. Phase 2 production deployments should configure
        // `relayerKeyHex`.
        Future.successful(SettleResult.Ok("0x" + "0" * 64))

  /** Build + sign + broadcast a `transferWithAuthorization(...)` call
   *  on the asset's chain, funded by the facilitator's relayer
   *  wallet. Returns the tx hash on broadcast; receipt polling is
   *  the caller's job (most x402 servers use the async settlement
   *  queue and poll separately). */
  private def settleOnChain(
    payload: PaymentPayload,
    req:     PaymentRequirements,
    keyHex:  String,
  ): Future[SettleResult] =
    val adapter = new EvmChainAdapter(ChainId(s"eip155:${req.network.chainId}"))
    val vault   = RawPrivateKeyVault.fromHex("x402-relayer", keyHex, Curve.Secp256k1)
    for
      relayerSigner <- vault.getSigner(Curve.Secp256k1, "raw")
      relayerAddr    = adapter.addressFromPublicKey(relayerSigner.publicKey)
      strategy       = new EoaStrategy(relayerSigner)
      auth           = payload.authorization
      sigBytes       = Hex.decode(payload.signature)
      nonceBytes     = Hex.decode(auth.nonce)
      ctx            = new EvmClientContext(config.evm)
      // Erc20.transferWithAuthorization returns a ContractCall intent
      // with the calldata fully ABI-encoded.
      usdc           = new Erc20(req.asset.address, adapter)
      intent         = usdc.transferWithAuthorization(
        from        = auth.from,
        to          = auth.to,
        value       = auth.value,
        validAfter  = auth.validAfter,
        validBefore = auth.validBefore,
        nonce       = nonceBytes,
        signature   = sigBytes,
      )
      tx             <- adapter.buildTransaction(intent, relayerAddr, ctx)
      signed         <- strategy.signTransaction(adapter)(tx)
      hash           <- adapter.broadcast(signed, ctx)
    yield
      val _ = relayerAddr   // silence unused warning if not logged
      SettleResult.Ok(hash.value)

object EvmFacilitator:
  def apply(config: EvmFacilitatorConfig)(using ExecutionContext): Facilitator =
    new EvmFacilitatorImpl(config)

  def apply(evm: EvmClient)(using ExecutionContext): Facilitator =
    new EvmFacilitatorImpl(EvmFacilitatorConfig(evm))

  def withSettler(
    evm:     EvmClient,
    settler: (PaymentPayload, PaymentRequirements) => Future[SettleResult],
  )(using ExecutionContext): Facilitator =
    new EvmFacilitatorImpl(EvmFacilitatorConfig(evm, settler = Some(settler)))

  /** Construct a facilitator with a real on-chain relayer. The
   *  `relayerPrivateKeyHex` private key funds settlement-tx gas;
   *  operators MUST keep it funded with native coin on the chain. */
  def withRelayer(
    evm:                  EvmClient,
    relayerPrivateKeyHex: String,
  )(using ExecutionContext): Facilitator =
    new EvmFacilitatorImpl(EvmFacilitatorConfig(evm, relayerKeyHex = Some(relayerPrivateKeyHex)))

/** Adapt the legacy `EvmClient` to the blockchain-spi `ChainContext`
 *  shape. The EvmClient exposes `rpc(method, params)` directly, so
 *  this is a one-liner wrapper. */
private class EvmClientContext(evm: EvmClient) extends ChainContext:
  def rpcCall(method: String, params: ujson.Value*): Future[ujson.Value] =
    evm.rpc(method, params*)
  def nowSeconds: Long = System.currentTimeMillis() / 1000
