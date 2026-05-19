package scalascript.mcp.wallet

import java.security.{MessageDigest, SecureRandom}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scalascript.blockchain.evm.Eip3009
import scalascript.blockchain.spi.{Asset, ChainContext, ChainId, TypedData}
import scalascript.mcp.{McpServerBuilder, ResourceHandlerResult, ToolHandlerResult}
import scalascript.wallet.spi.{AccountManager, Vault}

/** MCP tool surface over the wallet-spi `Vault` / `AccountManager`.
 *  Registers tools onto a caller-provided `McpServerBuilder` so the
 *  host owns the transport (stdio / HTTP+SSE) and lifecycle.
 *
 *  Read-only tools (`wallet.listAccounts`, `wallet.getAddress`,
 *  `wallet.getBalance`) ship from Phase 1. Phase 2 adds signing
 *  tools (`wallet.signMessage`, `wallet.signTypedData`,
 *  `wallet.payX402`) gated on the policy's `ConfirmationMode` —
 *  `ElicitationPerCall` invokes the supplied `ElicitationHandler`
 *  before every signing op; `Implicit` skips approval entirely.
 *
 *  Every operation is recorded into `auditLog` and surfaced via the
 *  `wallet://audit` resource (no raw signatures — only sha256
 *  digests of results).
 *
 *  See docs/mcp-x402-wallet.md §5. */
class McpWalletServer(
  val vault:        Vault,
  val manager:      AccountManager,
  val policy:       Policy,
  ctxFor:           ChainId => ChainContext,
  val elicitation:  Option[ElicitationHandler] = None,
  val auditLog:     AuditLog                   = new AuditLog(),
)(using ec: ExecutionContext):

  private val rng = new SecureRandom()

  /** Mount this wallet onto an `McpServerBuilder`. Idempotent only
   *  per-builder — calling twice on the same builder doubles the
   *  registrations. */
  def installOn(builder: McpServerBuilder): Unit =
    // Read-only
    if policy.exposes("wallet.listAccounts") then registerListAccounts(builder)
    if policy.exposes("wallet.getAddress")   then registerGetAddress(builder)
    if policy.exposes("wallet.getBalance")   then registerGetBalance(builder)
    // Signing (Phase 2) — only registered when policy.exposes() allows
    if policy.exposes("wallet.signMessage")   then registerSignMessage(builder)
    if policy.exposes("wallet.signTypedData") then registerSignTypedData(builder)
    if policy.exposes("wallet.payX402")       then registerPayX402(builder)
    // Audit resource always available
    registerAuditResource(builder)

  // ── tool registrations ────────────────────────────────────────────────

  private def registerListAccounts(builder: McpServerBuilder): Unit =
    builder.tool(
      name        = "wallet.listAccounts",
      description = Some("List wallet accounts: id, label, curves, derivation path."),
      inputSchema = ujson.Obj(
        "type"       -> ujson.Str("object"),
        "properties" -> ujson.Obj(),
      ),
      handler     = _ => awaitTool(handleListAccounts()),
    )

  private def handleListAccounts(): Future[ToolHandlerResult] =
    vault.listAccounts().map { accs =>
      val items = accs.map { a =>
        ujson.Obj(
          "id"             -> ujson.Str(a.id),
          "label"          -> ujson.Str(a.label),
          "derivationPath" -> ujson.Str(a.derivationPath),
          "curves"         -> ujson.Arr(a.publicKeys.keys.map(c => ujson.Str(c.toString)).toSeq*),
        )
      }
      ToolHandlerResult(
        content = List(ujson.Obj("accounts" -> ujson.Arr(items*))),
        isError = false,
      )
    }

  private def registerGetAddress(builder: McpServerBuilder): Unit =
    builder.tool(
      name        = "wallet.getAddress",
      description = Some("Address of the wallet on a specific chain (CAIP-2)."),
      inputSchema = ujson.Obj(
        "type"       -> ujson.Str("object"),
        "properties" -> ujson.Obj(
          "chainId" -> ujson.Obj(
            "type"        -> ujson.Str("string"),
            "description" -> ujson.Str("CAIP-2 chain id, e.g. \"eip155:8453\""),
          ),
        ),
        "required" -> ujson.Arr(ujson.Str("chainId")),
      ),
      handler = args => awaitTool(handleGetAddress(args)),
    )

  private def handleGetAddress(args: Map[String, Any]): Future[ToolHandlerResult] =
    args.get("chainId").map(_.toString) match
      case None =>
        Future.successful(errorResult("chainId is required"))
      case Some(chainStr) =>
        val chain = ChainId(chainStr)
        if !policy.visibleChains(manager.chains).contains(chain) then
          Future.successful(errorResult(s"chain $chain not exposed by policy"))
        else
          (manager.strategyFor(chain), manager.adapterFor(chain)) match
            case (Some(strategy), Some(adapter)) =>
              strategy.getAddress(adapter).map { addr =>
                ToolHandlerResult(
                  content = List(ujson.Obj(
                    "chainId" -> ujson.Str(chain.caip2),
                    "address" -> ujson.Str(addr),
                  )),
                  isError = false,
                )
              }
            case _ =>
              Future.successful(errorResult(s"no strategy/adapter registered for $chain"))

  private def registerGetBalance(builder: McpServerBuilder): Unit =
    builder.tool(
      name        = "wallet.getBalance",
      description = Some("Native or token balance of the wallet on a chain."),
      inputSchema = ujson.Obj(
        "type"       -> ujson.Str("object"),
        "properties" -> ujson.Obj(
          "chainId" -> ujson.Obj("type" -> ujson.Str("string")),
          "asset"   -> ujson.Obj(
            "type"       -> ujson.Str("object"),
            "description"-> ujson.Str("Asset {address,symbol,decimals}. Absent = native coin."),
            "properties" -> ujson.Obj(
              "address"  -> ujson.Obj("type" -> ujson.Str("string")),
              "symbol"   -> ujson.Obj("type" -> ujson.Str("string")),
              "decimals" -> ujson.Obj("type" -> ujson.Str("integer")),
            ),
          ),
        ),
        "required" -> ujson.Arr(ujson.Str("chainId")),
      ),
      handler = args => awaitTool(handleGetBalance(args)),
    )

  private def handleGetBalance(args: Map[String, Any]): Future[ToolHandlerResult] =
    args.get("chainId").map(_.toString) match
      case None =>
        Future.successful(errorResult("chainId is required"))
      case Some(chainStr) =>
        val chain = ChainId(chainStr)
        if !policy.visibleChains(manager.chains).contains(chain) then
          Future.successful(errorResult(s"chain $chain not exposed by policy"))
        else
          (manager.strategyFor(chain), manager.adapterFor(chain)) match
            case (Some(strategy), Some(adapter)) =>
              val ctx = ctxFor(chain)
              for
                addr    <- strategy.getAddress(adapter)
                assetOpt = parseAsset(args.get("asset"), chain)
                balance <- assetOpt match
                  case None    => adapter.nativeBalance(addr, ctx)
                  case Some(a) => adapter.tokenBalance(a, addr, ctx)
              yield
                ToolHandlerResult(
                  content = List(ujson.Obj(
                    "chainId"  -> ujson.Str(chain.caip2),
                    "address"  -> ujson.Str(addr),
                    "asset"    -> assetOpt.map(a => ujson.Str(a.address)).getOrElse(ujson.Str("native")),
                    "value"    -> ujson.Str(balance.toString),
                    "decimals" -> assetOpt.map(a => ujson.Num(a.decimals.toDouble)).getOrElse(ujson.Num(18)),
                    "symbol"   -> assetOpt.map(a => ujson.Str(a.symbol)).getOrElse(ujson.Str("native")),
                  )),
                  isError = false,
                )
            case _ =>
              Future.successful(errorResult(s"no strategy/adapter registered for $chain"))

  // ── signing tools (Phase 2) ───────────────────────────────────────────

  private def registerSignMessage(builder: McpServerBuilder): Unit =
    builder.tool(
      name        = "wallet.signMessage",
      description = Some("Sign a message with the wallet's EOA key (chain-specific prefix). Policy-gated."),
      inputSchema = ujson.Obj(
        "type"       -> ujson.Str("object"),
        "properties" -> ujson.Obj(
          "chainId" -> ujson.Obj("type" -> ujson.Str("string")),
          "message" -> ujson.Obj(
            "type"        -> ujson.Str("string"),
            "description" -> ujson.Str("Hex-encoded bytes (0x-prefixed) or utf-8 string."),
          ),
        ),
        "required" -> ujson.Arr(ujson.Str("chainId"), ujson.Str("message")),
      ),
      handler = args => awaitTool(handleSignMessage(args)),
    )

  private def handleSignMessage(args: Map[String, Any]): Future[ToolHandlerResult] =
    chainAndStrategy(args, "wallet.signMessage") match
      case Left(err)               => Future.successful(err)
      case Right((chain, s, a))    =>
        val raw = args.get("message").map(_.toString).getOrElse("")
        val bytes =
          if raw.startsWith("0x") || raw.startsWith("0X") then hexDecode(raw)
          else raw.getBytes("UTF-8")
        confirm(ElicitationRequest(
          tool    = "wallet.signMessage",
          chainId = Some(chain),
          summary = s"Sign a ${bytes.length}-byte message on $chain",
          details = Map("bytesLen" -> bytes.length.toString),
        )).flatMap {
          case false =>
            recordRejection("wallet.signMessage", Some(chain))
            Future.successful(errorResult("User rejected sign request"))
          case true  =>
            s.signMessage(a, bytes).map { sig =>
              recordApproval("wallet.signMessage", Some(chain), sig)
              ToolHandlerResult(
                content = List(ujson.Obj(
                  "chainId"   -> ujson.Str(chain.caip2),
                  "signature" -> ujson.Str("0x" + hexEncode(sig)),
                )),
                isError = false,
              )
            }.recover { case ex =>
              recordError("wallet.signMessage", Some(chain), ex.getMessage)
              errorResult(s"sign failed: ${ex.getMessage}")
            }
        }

  private def registerSignTypedData(builder: McpServerBuilder): Unit =
    builder.tool(
      name        = "wallet.signTypedData",
      description = Some("Sign EIP-712 typed data. Policy-gated."),
      inputSchema = ujson.Obj(
        "type"       -> ujson.Str("object"),
        "properties" -> ujson.Obj(
          "chainId"   -> ujson.Obj("type" -> ujson.Str("string")),
          "typedData" -> ujson.Obj(
            "type"        -> ujson.Str("object"),
            "description" -> ujson.Str("EIP-712 typed data per the eth_signTypedData_v4 shape."),
          ),
        ),
        "required" -> ujson.Arr(ujson.Str("chainId"), ujson.Str("typedData")),
      ),
      handler = args => awaitTool(handleSignTypedData(args)),
    )

  private def handleSignTypedData(args: Map[String, Any]): Future[ToolHandlerResult] =
    chainAndStrategy(args, "wallet.signTypedData") match
      case Left(err)            => Future.successful(err)
      case Right((chain, s, a)) =>
        parseTypedDataArg(args.get("typedData")) match
          case Left(reason) =>
            Future.successful(errorResult(reason))
          case Right(td) =>
            confirm(ElicitationRequest(
              tool    = "wallet.signTypedData",
              chainId = Some(chain),
              summary = s"Sign EIP-712 \"${td.primaryType}\" on $chain",
              details = Map(
                "primaryType" -> td.primaryType,
                "domain"      -> td.domain.toString,
              ),
            )).flatMap {
              case false =>
                recordRejection("wallet.signTypedData", Some(chain))
                Future.successful(errorResult("User rejected sign request"))
              case true =>
                s.signTypedData(a, td).map { sig =>
                  recordApproval("wallet.signTypedData", Some(chain), sig)
                  ToolHandlerResult(
                    content = List(ujson.Obj(
                      "chainId"   -> ujson.Str(chain.caip2),
                      "signature" -> ujson.Str("0x" + hexEncode(sig)),
                    )),
                    isError = false,
                  )
                }.recover { case ex =>
                  recordError("wallet.signTypedData", Some(chain), ex.getMessage)
                  errorResult(s"sign failed: ${ex.getMessage}")
                }
            }

  private def registerPayX402(builder: McpServerBuilder): Unit =
    builder.tool(
      name        = "wallet.payX402",
      description = Some("Build + sign an EIP-3009 PaymentPayload for an x402 server's PaymentRequirements. Policy-gated by maxPerCall."),
      inputSchema = ujson.Obj(
        "type"       -> ujson.Str("object"),
        "properties" -> ujson.Obj(
          "chainId"      -> ujson.Obj("type" -> ujson.Str("string")),
          "requirements" -> ujson.Obj(
            "type"        -> ujson.Str("object"),
            "description" -> ujson.Str("x402 PaymentRequirements: {asset:{address,symbol,decimals}, payTo, amount, maxTimeoutSeconds?, name?, version?}"),
          ),
        ),
        "required" -> ujson.Arr(ujson.Str("chainId"), ujson.Str("requirements")),
      ),
      handler = args => awaitTool(handlePayX402(args)),
    )

  private def handlePayX402(args: Map[String, Any]): Future[ToolHandlerResult] =
    chainAndStrategy(args, "wallet.payX402") match
      case Left(err)            => Future.successful(err)
      case Right((chain, s, a)) =>
        parsePayX402Req(args.get("requirements"), chain) match
          case Left(reason) =>
            Future.successful(errorResult(reason))
          case Right(req) =>
            policy.maxPerCall.get(chain) match
              case Some(cap) if req.amount > cap =>
                recordError("wallet.payX402", Some(chain), s"amount ${req.amount} exceeds policy cap $cap")
                Future.successful(errorResult(s"amount ${req.amount} exceeds policy cap for $chain"))
              case _ =>
                doPayX402(chain, s, a, req)

  private def doPayX402(
    chain:    ChainId,
    strategy: scalascript.wallet.spi.AccountStrategy,
    adapter:  scalascript.blockchain.spi.ChainAdapter,
    req:      PayX402Args,
  ): Future[ToolHandlerResult] =
    val chainIdInt    = chain.reference.toInt
    val nonceBytes    = randomBytes(32)
    val nonceHex      = "0x" + hexEncode(nonceBytes)
    val nowSeconds    = System.currentTimeMillis() / 1000
    val validBefore   = BigInt(nowSeconds + req.maxTimeoutSeconds)
    val typedData     = Eip3009.transferWithAuthorization(
      tokenAddress = req.tokenAddress,
      tokenName    = req.tokenName,
      tokenVersion = req.tokenVersion,
      chainId      = chainIdInt,
      from         = "0x0000000000000000000000000000000000000000",  // patched below after address resolve
      to           = req.payTo,
      value        = req.amount,
      validAfter   = BigInt(0),
      validBefore  = validBefore,
      nonceHex     = nonceHex,
    )
    confirm(ElicitationRequest(
      tool    = "wallet.payX402",
      chainId = Some(chain),
      summary = s"Pay ${req.amount} ${req.tokenSymbol} to ${req.payTo} on $chain",
      details = Map(
        "amount"      -> req.amount.toString,
        "asset"       -> req.tokenAddress,
        "symbol"      -> req.tokenSymbol,
        "payTo"       -> req.payTo,
        "validBefore" -> validBefore.toString,
      ),
    )).flatMap {
      case false =>
        recordRejection("wallet.payX402", Some(chain))
        Future.successful(errorResult("User rejected payment"))
      case true =>
        for
          from   <- strategy.getAddress(adapter)
          finalTd = typedData.copy(
            value = typedData.value.updated("from", ujson.Str(from)),
          )
          sig    <- strategy.signTypedData(adapter, finalTd)
        yield
          val sigHexEth = ethSig(sig)
          recordApproval("wallet.payX402", Some(chain), sig, Map(
            "amount" -> req.amount.toString, "to" -> req.payTo,
          ))
          val payload = ujson.Obj(
            "x402Version"   -> ujson.Num(1),
            "scheme"        -> ujson.Obj("type" -> ujson.Str("exact"), "amount" -> ujson.Str(req.amount.toString)),
            "network"       -> ujson.Str(chain.caip2),
            "authorization" -> ujson.Obj(
              "from"        -> ujson.Str(from),
              "to"          -> ujson.Str(req.payTo),
              "value"       -> ujson.Str(req.amount.toString),
              "validAfter"  -> ujson.Str("0"),
              "validBefore" -> ujson.Str(validBefore.toString),
              "nonce"       -> ujson.Str(nonceHex),
            ),
            "signature"     -> ujson.Str(sigHexEth),
          )
          ToolHandlerResult(
            content = List(ujson.Obj(
              "chainId"       -> ujson.Str(chain.caip2),
              "from"          -> ujson.Str(from),
              "payload"       -> payload,
              "headerValue"   -> ujson.Str(java.util.Base64.getEncoder.encodeToString(payload.toString.getBytes("UTF-8"))),
            )),
            isError = false,
          )
    }

  // ── audit resource ────────────────────────────────────────────────────

  private def registerAuditResource(builder: McpServerBuilder): Unit =
    builder.resource(
      uri      = "wallet://audit",
      name     = Some("Wallet audit log"),
      mimeType = Some("application/json"),
      handler  = _ => ResourceHandlerResult(
        uri      = "wallet://audit",
        contents = List(ujson.Obj(
          "entries" -> ujson.Arr(auditLog.snapshot.map(_.toJson)*),
        )),
      ),
    )

  // ── helpers ────────────────────────────────────────────────────────────

  /** Common chain + strategy + adapter resolution + policy check. */
  private def chainAndStrategy(args: Map[String, Any], tool: String): Either[
    ToolHandlerResult,
    (ChainId, scalascript.wallet.spi.AccountStrategy, scalascript.blockchain.spi.ChainAdapter),
  ] =
    args.get("chainId").map(_.toString) match
      case None =>
        Left(errorResult("chainId is required"))
      case Some(chainStr) =>
        val chain = ChainId(chainStr)
        if !policy.visibleChains(manager.chains).contains(chain) then
          recordError(tool, Some(chain), "chain not exposed by policy")
          Left(errorResult(s"chain $chain not exposed by policy"))
        else
          (manager.strategyFor(chain), manager.adapterFor(chain)) match
            case (Some(strat), Some(adp)) => Right((chain, strat, adp))
            case _                        =>
              Left(errorResult(s"no strategy/adapter registered for $chain"))

  private case class PayX402Args(
    tokenAddress:      String,
    tokenSymbol:       String,
    tokenName:         String,
    tokenVersion:      String,
    payTo:             String,
    amount:            BigInt,
    maxTimeoutSeconds: Long,
  )

  private def parsePayX402Req(raw: Option[Any], @annotation.unused chain: ChainId): Either[String, PayX402Args] =
    raw match
      case Some(m: Map[_, _]) =>
        val mm     = m.asInstanceOf[Map[String, Any]]
        val asset  = mm.get("asset")
          .collect { case x: Map[_, _] => x.asInstanceOf[Map[String, Any]] }
          .getOrElse(Map.empty[String, Any])
        val tokenAddr = asset.get("address").map(_.toString)
        val payTo     = mm.get("payTo").map(_.toString)
        val amount    = mm.get("amount").map(a => BigInt(a.toString))
        (tokenAddr, payTo, amount) match
          case (Some(addr), Some(to), Some(amt)) =>
            Right(PayX402Args(
              tokenAddress      = addr,
              tokenSymbol       = asset.get("symbol").map(_.toString).getOrElse("?"),
              tokenName         = mm.get("name").map(_.toString).getOrElse("USD Coin"),
              tokenVersion      = mm.get("version").map(_.toString).getOrElse("2"),
              payTo             = to,
              amount            = amt,
              maxTimeoutSeconds = mm.get("maxTimeoutSeconds").map(_.toString.toDouble.toLong).getOrElse(300L),
            ))
          case _ =>
            Left(s"requirements must include asset.address + payTo + amount (got: $mm)")
      case _ =>
        Left("requirements is required and must be an object")

  private def parseTypedDataArg(raw: Option[Any]): Either[String, TypedData.Eip712] =
    raw match
      case Some(m: Map[_, _]) =>
        try
          val mm           = m.asInstanceOf[Map[String, Any]]
          val domainRaw    = mm.get("domain").collect { case x: Map[_, _] => x.asInstanceOf[Map[String, Any]] }.getOrElse(Map.empty)
          val typesRaw     = mm.get("types").collect { case x: Map[_, _] => x.asInstanceOf[Map[String, Any]] }.getOrElse(Map.empty)
          val valueRaw     = mm.get("message").collect { case x: Map[_, _] => x.asInstanceOf[Map[String, Any]] }.getOrElse(Map.empty)
          mm.get("primaryType").map(_.toString) match
            case None =>
              Left("typedData.primaryType is required")
            case Some(primaryType) =>
              val domain = domainRaw.map { case (k, v) => k -> toUjson(v) }
              val value  = valueRaw.map { case (k, v) => k -> toUjson(v) }
              val types  = typesRaw.map { case (k, v) =>
                val fields = v.asInstanceOf[Seq[Map[String, Any]]].map { f =>
                  (f("type").toString, f("name").toString)
                }
                k -> fields
              }
              Right(TypedData.Eip712(domain = domain, types = types, value = value, primaryType = primaryType))
        catch case ex: Throwable => Left(s"malformed typedData: ${ex.getMessage}")
      case _ =>
        Left("typedData is required and must be an object")

  private def toUjson(v: Any): ujson.Value = v match
    case null       => ujson.Null
    case s: String  => ujson.Str(s)
    case b: Boolean => ujson.Bool(b)
    case n: Int     => ujson.Num(n.toDouble)
    case n: Long    => ujson.Num(n.toDouble)
    case n: Double  => ujson.Num(n)
    case other      => ujson.Str(other.toString)

  /** Run elicitation when policy demands it; auto-approve in Implicit
   *  mode. Returns Future[Boolean] — true = signed, false = rejected. */
  private def confirm(req: ElicitationRequest): Future[Boolean] =
    policy.confirmation match
      case ConfirmationMode.Implicit =>
        Future.successful(true)
      case ConfirmationMode.ElicitationPerCall | _: ConfirmationMode.ElicitationCached =>
        elicitation match
          case None =>
            // No handler — policy demands one. Fail closed.
            Future.successful(false)
          case Some(h) =>
            h.confirm(req)

  private def recordApproval(
    tool: String, chain: Option[ChainId], sig: Array[Byte],
    extra: Map[String, String] = Map.empty,
  ): Unit =
    auditLog.append(AuditEntry(
      timestamp    = System.currentTimeMillis(),
      tool         = tool,
      chainId      = chain,
      decision     = if policy.confirmation == ConfirmationMode.Implicit then "auto" else "approved",
      details      = extra,
      resultDigest = Some(sha256Hex(sig).take(16)),
    ))

  private def recordRejection(tool: String, chain: Option[ChainId]): Unit =
    auditLog.append(AuditEntry(
      timestamp = System.currentTimeMillis(),
      tool      = tool,
      chainId   = chain,
      decision  = "rejected",
    ))

  private def recordError(tool: String, chain: Option[ChainId], reason: String): Unit =
    auditLog.append(AuditEntry(
      timestamp = System.currentTimeMillis(),
      tool      = tool,
      chainId   = chain,
      decision  = "error",
      details   = Map("reason" -> reason),
    ))

  private def ethSig(raw: Array[Byte]): String =
    require(raw.length == 65, s"raw sig must be 65 bytes, got ${raw.length}")
    val out = raw.clone()
    out(64) = (raw(64) + 27).toByte
    "0x" + hexEncode(out)

  private def randomBytes(n: Int): Array[Byte] =
    val b = new Array[Byte](n)
    rng.nextBytes(b)
    b

  private def hexEncode(bytes: Array[Byte]): String =
    val sb = new java.lang.StringBuilder(bytes.length * 2)
    var i = 0
    while i < bytes.length do
      sb.append(f"${bytes(i) & 0xff}%02x")
      i += 1
    sb.toString

  private def hexDecode(s: String): Array[Byte] =
    val clean = s.stripPrefix("0x").stripPrefix("0X")
    val out = new Array[Byte](clean.length / 2)
    var i = 0
    while i < out.length do
      out(i) = Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16).toByte
      i += 1
    out

  private def sha256Hex(bytes: Array[Byte]): String =
    val d = MessageDigest.getInstance("SHA-256")
    hexEncode(d.digest(bytes))

  private def parseAsset(raw: Option[Any], chain: ChainId): Option[Asset] =
    raw.flatMap {
      case m: Map[_, _] =>
        val mm = m.asInstanceOf[Map[String, Any]]
        for
          addr     <- mm.get("address").map(_.toString)
          symbol    = mm.get("symbol").map(_.toString).getOrElse("?")
          decimals  = mm.get("decimals").map(d => d.toString.toDouble.toInt).getOrElse(18)
        yield Asset(chain, addr, symbol, decimals)
      case _ => None
    }

  private def errorResult(message: String): ToolHandlerResult =
    ToolHandlerResult(
      content = List(ujson.Obj("error" -> ujson.Str(message))),
      isError = true,
    )

  /** Tool handlers in mcp-common are synchronous. Our wallet ops are
   *  Future-shaped; this blocks for the result. Acceptable for stdio
   *  servers (one request at a time) and for the read-only ops here
   *  (all complete in milliseconds). When signing tools land we'll
   *  revisit this for the elicitation round-trip. */
  private def awaitTool(f: Future[ToolHandlerResult]): ToolHandlerResult =
    try Await.result(f, 30.seconds)
    catch case ex: Throwable => errorResult(s"tool failed: ${ex.getMessage}")
