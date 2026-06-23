package scalascript.solana

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import sttp.client4.*
import scalascript.blockchain.spi.ChainContext

/** Typed Solana JSON-RPC client — the Solana counterpart of `scalascript.evm.EvmClient`. Covers the calls the
 *  `SolanaChainAdapter` needs to build, sign and broadcast a transaction, plus a raw `rpc` escape hatch. */
trait SolanaClient:
  /** Native SOL balance in lamports. */
  def getBalance(address: String): Future[BigInt]
  /** The current recent blockhash (base58) for transaction construction. */
  def getLatestBlockhash(): Future[String]
  /** SPL token accounts owned by `owner`, optionally filtered by `mint` (jsonParsed envelope). */
  def getTokenAccountsByOwner(owner: String, mint: Option[String] = None): Future[ujson.Value]
  /** A confirmed transaction by signature, or `None` if not found. */
  def getTransaction(signature: String): Future[Option[ujson.Value]]
  /** Submit a base64-encoded signed transaction; returns its signature. */
  def sendTransaction(base64Tx: String): Future[String]
  /** Account info (base64 data envelope), or `None` if the account does not exist. */
  def getAccountInfo(address: String): Future[Option[ujson.Value]]
  /** Raw JSON-RPC call; returns the `result` field. */
  def rpc(method: String, params: ujson.Value*): Future[ujson.Value]

object Solana:
  def connect(config: SolanaConfig)(using ExecutionContext): SolanaClient = new JsonRpcSolanaClient(config)
  def connect(rpcUrl: String)(using ExecutionContext): SolanaClient = connect(SolanaConfig(rpcUrl))

  /** A turnkey [[ChainContext]] over Solana JSON-RPC for `SolanaChainAdapter` — the point of this module: a
   *  caller no longer hand-rolls a `ChainContext`, just `Solana.chainContext(SolanaNetworks.Devnet)`. */
  def chainContext(config: SolanaConfig)(using ExecutionContext): ChainContext = new SolanaChainContext(connect(config))
  /** Wrap an existing (e.g. mock) [[SolanaClient]] as a `ChainContext`. */
  def chainContext(client: SolanaClient): ChainContext = new SolanaChainContext(client)

/** Bridges [[SolanaClient]] to the chain-agnostic `ChainContext` seam the adapters call. `rpcCall` returns the
 *  raw `result` envelope (the adapter unwraps `value`), matching what the adapter expects. */
private class SolanaChainContext(client: SolanaClient) extends ChainContext:
  def rpcCall(method: String, params: ujson.Value*): Future[ujson.Value] = client.rpc(method, params*)
  def nowSeconds: Long = System.currentTimeMillis() / 1000

private class JsonRpcSolanaClient(config: SolanaConfig)(using ec: ExecutionContext) extends SolanaClient:

  private val backend = DefaultSyncBackend()
  private val reqId   = java.util.concurrent.atomic.AtomicLong(1)

  private def rpcSync(method: String, params: ujson.Value*): ujson.Value =
    val body = ujson.Obj(
      "jsonrpc" -> "2.0",
      "id"      -> reqId.getAndIncrement(),
      "method"  -> method,
      "params"  -> ujson.Arr(params*),
    )
    val resp = basicRequest
      .post(uri"${config.rpcUrl}")
      .header("Content-Type", "application/json")
      .body(body.toString)
      .send(backend)
    val json = ujson.read(resp.body.getOrElse(throw RuntimeException("empty response")))
    if json.obj.contains("error") then
      val err = json("error")
      throw SolanaRpcError(err("code").num.toInt, err("message").str)
    json("result")

  private def commitmentObj: ujson.Obj = ujson.Obj("commitment" -> ujson.Str(config.commitment))

  def rpc(method: String, params: ujson.Value*): Future[ujson.Value] = Future(rpcSync(method, params*))

  def getBalance(address: String): Future[BigInt] =
    Future(BigInt(rpcSync("getBalance", ujson.Str(address), commitmentObj).obj("value").num.toLong))

  def getLatestBlockhash(): Future[String] =
    Future(rpcSync("getLatestBlockhash", commitmentObj).obj("value").obj("blockhash").str)

  def getTokenAccountsByOwner(owner: String, mint: Option[String] = None): Future[ujson.Value] =
    val filter = mint match
      case Some(m) => ujson.Obj("mint" -> ujson.Str(m))
      case None    => ujson.Obj("programId" -> ujson.Str("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"))
    Future(rpcSync("getTokenAccountsByOwner", ujson.Str(owner), filter,
      ujson.Obj("encoding" -> ujson.Str("jsonParsed"), "commitment" -> ujson.Str(config.commitment))))

  def getTransaction(signature: String): Future[Option[ujson.Value]] =
    Future {
      val r = rpcSync("getTransaction", ujson.Str(signature),
        ujson.Obj("encoding" -> ujson.Str("json"), "commitment" -> ujson.Str(config.commitment),
          "maxSupportedTransactionVersion" -> ujson.Num(0)))
      if r == ujson.Null then None else Some(r)
    }

  def sendTransaction(base64Tx: String): Future[String] =
    Future {
      val r = rpcSync("sendTransaction", ujson.Str(base64Tx), ujson.Obj("encoding" -> ujson.Str("base64")))
      r match { case ujson.Str(s) => s; case obj => obj("result").str }
    }

  def getAccountInfo(address: String): Future[Option[ujson.Value]] =
    Future {
      val r = rpcSync("getAccountInfo", ujson.Str(address),
        ujson.Obj("encoding" -> ujson.Str("base64"), "commitment" -> ujson.Str(config.commitment)))
      Try(r.obj("value")).toOption.filter(_ != ujson.Null)
    }
