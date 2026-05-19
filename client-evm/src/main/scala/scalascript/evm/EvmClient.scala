package scalascript.evm

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.{Duration, DurationInt}
import scala.util.Try
import sttp.client4.*

trait EvmClient:
  def blockNumber(): Future[BigInt]
  def getBalance(address: String): Future[BigInt]
  def getCode(address: String): Future[String]

  // ERC-20
  def erc20Balance(token: String, address: String): Future[BigInt]
  def erc20Allowance(token: String, owner: String, spender: String): Future[BigInt]
  def erc20Decimals(token: String): Future[Int]
  def erc20Symbol(token: String): Future[String]

  // Transactions
  def getTransaction(hash: String): Future[Option[EvmTransaction]]
  def getReceipt(hash: String): Future[Option[EvmReceipt]]
  def waitForReceipt(hash: String, timeout: Duration = 60.seconds): Future[EvmReceipt]

  // Contract call (read-only eth_call)
  def call(to: String, data: String): Future[String]

  // Raw JSON-RPC
  def rpc(method: String, params: ujson.Value*): Future[ujson.Value]

object Evm:
  def connect(config: EvmConfig)(using ExecutionContext): EvmClient =
    new JsonRpcEvmClient(config)
  def connect(rpcUrl: String)(using ExecutionContext): EvmClient =
    new JsonRpcEvmClient(EvmConfig(rpcUrl, chainId = 0))

private class JsonRpcEvmClient(config: EvmConfig)(using ec: ExecutionContext) extends EvmClient:

  private val backend = DefaultSyncBackend()
  private val reqId   = java.util.concurrent.atomic.AtomicLong(1)

  private def rpcSync(method: String, params: ujson.Value*): ujson.Value =
    val id   = reqId.getAndIncrement()
    val body = ujson.Obj(
      "jsonrpc" -> "2.0",
      "id"      -> id,
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
      throw EvmRpcError(err("code").num.toInt, err("message").str)
    json("result")

  def rpc(method: String, params: ujson.Value*): Future[ujson.Value] =
    Future(rpcSync(method, params*))

  def blockNumber(): Future[BigInt] =
    Future(hexToBigInt(rpcSync("eth_blockNumber").str))

  def getBalance(address: String): Future[BigInt] =
    Future(hexToBigInt(rpcSync("eth_getBalance", address, "latest").str))

  def getCode(address: String): Future[String] =
    Future(rpcSync("eth_getCode", address, "latest").str)

  // ERC-20 ABI encoding helpers
  // balanceOf(address) → 0x70a08231
  def erc20Balance(token: String, address: String): Future[BigInt] =
    val data = "0x70a08231" + address.stripPrefix("0x").toLowerCase.reverse.padTo(64, '0').reverse
    call(token, data).map(hexToBigInt)

  // allowance(owner,spender) → 0xdd62ed3e
  def erc20Allowance(token: String, owner: String, spender: String): Future[BigInt] =
    val data = "0xdd62ed3e" +
      owner.stripPrefix("0x").toLowerCase.reverse.padTo(64, '0').reverse +
      spender.stripPrefix("0x").toLowerCase.reverse.padTo(64, '0').reverse
    call(token, data).map(hexToBigInt)

  // decimals() → 0x313ce567
  def erc20Decimals(token: String): Future[Int] =
    call(token, "0x313ce567").map(r => hexToBigInt(r).toInt)

  // symbol() → 0x95d89b41 — returns ABI-encoded string
  def erc20Symbol(token: String): Future[String] =
    call(token, "0x95d89b41").map(decodeAbiString)

  def getTransaction(hash: String): Future[Option[EvmTransaction]] =
    Future {
      val r = rpcSync("eth_getTransactionByHash", hash)
      if r == ujson.Null then None
      else Some(EvmTransaction(
        hash        = r("hash").str,
        from        = r("from").str,
        to          = Try(r("to").str).toOption,
        value       = hexToBigInt(r("value").str),
        input       = r("input").str,
        blockNumber = Try(hexToBigInt(r("blockNumber").str)).toOption,
      ))
    }

  def getReceipt(hash: String): Future[Option[EvmReceipt]] =
    Future {
      val r = rpcSync("eth_getTransactionReceipt", hash)
      if r == ujson.Null then None
      else Some(parseReceipt(r))
    }

  def waitForReceipt(hash: String, timeout: Duration = 60.seconds): Future[EvmReceipt] =
    Future {
      val deadline = System.currentTimeMillis() + timeout.toMillis
      var receipt: Option[EvmReceipt] = None
      while receipt.isEmpty && System.currentTimeMillis() < deadline do
        val r = rpcSync("eth_getTransactionReceipt", hash)
        if r != ujson.Null then receipt = Some(parseReceipt(r))
        else Thread.sleep(2000)
      receipt.getOrElse(throw RuntimeException(s"Receipt for $hash not found within $timeout"))
    }

  def call(to: String, data: String): Future[String] =
    Future(rpcSync("eth_call", ujson.Obj("to" -> to, "data" -> data), "latest").str)

  private def parseReceipt(r: ujson.Value): EvmReceipt =
    EvmReceipt(
      transactionHash = r("transactionHash").str,
      status          = hexToBigInt(r("status").str).toInt,
      gasUsed         = hexToBigInt(r("gasUsed").str),
      blockNumber     = hexToBigInt(r("blockNumber").str),
      logs = r("logs").arr.toList.map { l =>
        EvmLog(
          address = l("address").str,
          topics  = l("topics").arr.toList.map(_.str),
          data    = l("data").str,
        )
      },
    )

  private def hexToBigInt(hex: String): BigInt =
    BigInt(hex.stripPrefix("0x"), 16)

  // Decode ABI-encoded string (offset=32, length=32, data...)
  private def decodeAbiString(hex: String): String =
    val raw = hex.stripPrefix("0x")
    if raw.length < 128 then return ""
    val lenHex = raw.substring(64, 128)
    val len    = BigInt(lenHex, 16).toInt
    val chars  = raw.substring(128, 128 + len * 2)
    chars.grouped(2).map(b => Integer.parseInt(b, 16).toChar).mkString
