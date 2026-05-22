package scalascript.blockchain.evm

import java.net.{ServerSocket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path, Paths}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
import scalascript.blockchain.spi.ChainContext

/** Tiny harness that locates the Foundry `anvil` binary, spawns it
 *  on a free localhost port, waits until it responds to JSON-RPC,
 *  and hands the caller a [[ChainContext]] over plain HTTP for the
 *  duration of the test. Tests should call `AnvilTestHarness.start()`
 *  in beforeAll and `stop()` in afterAll.
 *
 *  When anvil is not on PATH (no Foundry installation), this is a
 *  no-op: `isAvailable` returns false and tests can `assume()` over
 *  it to skip gracefully. CI without Foundry doesn't fail. */
object AnvilTestHarness:

  /** Deterministic 10-account mnemonic baked into anvil's defaults
   *  — matches every other Foundry-based test on the internet, so
   *  test addresses + balances are reproducible. */
  val DeterministicMnemonic: String =
    "test test test test test test test test test test test junk"

  /** Account 0 from the deterministic mnemonic — private key (32 bytes,
   *  hex without 0x). Pre-funded with 10000 ETH on the dev chain. */
  val Account0PrivHex: String =
    "ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80"

  /** Account 0 address (EIP-55 checksummed). */
  val Account0Address: String = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266"

  /** Account 1 address (the canonical recipient in foundry tests). */
  val Account1Address: String = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8"

  /** Anvil's chain ID — 31337 is the default for foundry / hardhat
   *  forks. Tests must use a ChainId with this reference. */
  val ChainIdInt: Int = 31337

  /** Locate the anvil binary: PATH first, then `~/.foundry/bin/anvil`
   *  (Foundry's default install location). */
  def locate(): Option[Path] =
    val candidates =
      Seq("anvil") ++
      Option(System.getenv("HOME")).map(h => s"$h/.foundry/bin/anvil").toSeq
    candidates.iterator.flatMap { name =>
      val direct = Paths.get(name)
      if Files.isExecutable(direct) then Some(direct)
      else findOnPath(name)
    }.nextOption()

  def isAvailable: Boolean = locate().isDefined

  private def findOnPath(name: String): Option[Path] =
    Option(System.getenv("PATH")).iterator.flatMap(_.split(java.io.File.pathSeparator)).flatMap { dir =>
      val candidate = Paths.get(dir, name)
      if Files.isExecutable(candidate) then Some(candidate) else None
    }.nextOption()

  /** Pick a free local port by binding to 0 and reading what the OS
   *  picked. Subject to TOCTOU but anvil starts fast enough that
   *  collisions are negligible in practice. */
  def freePort(): Int =
    val s = new ServerSocket(0)
    try s.getLocalPort
    finally s.close()

  /** Spawn anvil on a free port. Throws if anvil isn't on PATH —
   *  callers should `assume(isAvailable)` first. */
  def start(port: Int = freePort())(using ec: ExecutionContext): AnvilNode =
    val bin = locate().getOrElse(
      throw new IllegalStateException("anvil not found on PATH or ~/.foundry/bin"),
    )
    val cmd = new java.util.ArrayList[String]()
    cmd.add(bin.toAbsolutePath.toString)
    cmd.add("--port")
    cmd.add(port.toString)
    cmd.add("--mnemonic")
    cmd.add(DeterministicMnemonic)
    cmd.add("--chain-id")
    cmd.add(ChainIdInt.toString)
    cmd.add("--silent")
    val pb = new ProcessBuilder(cmd)
    pb.redirectErrorStream(true)
    pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
    val process = pb.start()
    val node    = AnvilNode(process, port)
    // Poll for readiness — anvil typically responds within ~200ms.
    val deadline = System.currentTimeMillis() + 10_000
    var ready    = false
    while !ready && System.currentTimeMillis() < deadline do
      try
        val v = node.context.rpcCall("eth_blockNumber")
        scala.concurrent.Await.result(v, 500.millis)
        ready = true
      catch case _: Throwable =>
        Thread.sleep(100)
    if !ready then
      node.stop()
      throw new RuntimeException(s"anvil did not become ready within 10s on port $port")
    node

/** A running anvil instance + an HTTP-backed ChainContext that
 *  talks to it. Call `stop()` to tear down the subprocess. */
case class AnvilNode(process: Process, port: Int)(using ec: ExecutionContext):

  val url: String = s"http://127.0.0.1:$port"

  private val client: HttpClient =
    HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build()

  private val requestId = new java.util.concurrent.atomic.AtomicLong(0)

  /** HTTP JSON-RPC ChainContext pointed at this anvil instance. */
  val context: ChainContext = new ChainContext:
    def nowSeconds: Long = System.currentTimeMillis() / 1000L
    def rpcCall(method: String, params: ujson.Value*): Future[ujson.Value] =
      given ExecutionContext = ec
      Future {
        val id      = requestId.incrementAndGet()
        val body    = ujson.Obj(
          "jsonrpc" -> ujson.Str("2.0"),
          "id"      -> ujson.Num(id.toDouble),
          "method"  -> ujson.Str(method),
          "params"  -> ujson.Arr.from(params),
        )
        val req = HttpRequest.newBuilder(URI.create(url))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body.render()))
          .timeout(java.time.Duration.ofSeconds(5))
          .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        val json = ujson.read(resp.body())
        json.obj.get("error") match
          case Some(err) =>
            throw new RuntimeException(s"JSON-RPC error from $method: ${err.render()}")
          case None =>
            json.obj.getOrElse("result", throw new RuntimeException(
              s"JSON-RPC response missing 'result': ${resp.body()}",
            ))
      }

  def stop(): Unit =
    if process.isAlive then
      process.destroy()
      if !process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) then
        process.destroyForcibly()
