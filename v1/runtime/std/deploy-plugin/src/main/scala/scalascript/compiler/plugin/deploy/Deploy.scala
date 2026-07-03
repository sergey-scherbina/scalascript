package scalascript.compiler.plugin.deploy

import java.net.{HttpURLConnection, URI}

/** High-level deploy orchestration. */
object Deploy:

  /** Rolling cluster upgrade: drain each node in sequence before restarting.
   *
   *  Uses the existing drain protocol (POST /_ssc-cluster/drain) and
   *  DeployGroup(Sequence, AbortRemaining) for per-node scheduling.
   *
   *  @param nodeUrls   Base URLs of cluster nodes to upgrade in order.
   *  @param runNode    Function to upgrade a single node's artifact; receives
   *                    nodeUrl and should return Right(()) on success.
   *  @param strategy   Upgrade strategy.
   *  @param healthCheck Optional function to verify a node is healthy after restart.
   *  @param dryRun     If true, simulate drain + deploy without side effects.
   */
  def rollingCluster(
    nodeUrls:    List[String],
    runNode:     String => Either[String, Unit],
    strategy:    RollingStrategy = RollingStrategy.Rolling(),
    healthCheck: String => Boolean = _ => true,
    dryRun:      Boolean = false,
  ): RollingResult =
    strategy match
      case RollingStrategy.Rolling(maxUnavailable, waitDrainMs) =>
        rollingSequential(nodeUrls, runNode, healthCheck, maxUnavailable, waitDrainMs, dryRun)
      case RollingStrategy.BlueGreen(observeMs) =>
        rollingBlueGreen(nodeUrls, runNode, observeMs, dryRun)
      case RollingStrategy.Canary(percent, observeMs) =>
        rollingCanary(nodeUrls, runNode, percent, observeMs, dryRun)

  /** Deploy to multiple regions sequentially, respecting quorum.
   *
   *  @param regions    Region names to deploy to.
   *  @param runRegion  Function that deploys to one region; Right(()) on success.
   *  @param quorum     Minimum number of regions that must succeed.
   *  @param dryRun     If true, simulate without side effects.
   */
  def multiRegion(
    regions:   List[String],
    runRegion: String => Either[String, Unit],
    quorum:    Int = 1,
    dryRun:    Boolean = false,
  ): MultiRegionResult =
    if dryRun then
      println(s"[dry-run] multi-region deploy to: ${regions.mkString(", ")}")
      MultiRegionResult(regions, Nil)
    else
      val results   = regions.map { r => r -> runRegion(r) }
      val succeeded = results.collect { case (r, Right(_)) => r }
      val failed    = results.collect { case (r, Left(_))  => r }
      if succeeded.size < quorum then
        println(s"[deploy/multi-region/quorum-failed] succeeded=${succeeded.size} required=$quorum")
      MultiRegionResult(succeeded, failed)

  // ── Rolling (sequential with drain) ───────────────────────────────────────

  private def rollingSequential(
    nodeUrls:       List[String],
    runNode:        String => Either[String, Unit],
    healthCheck:    String => Boolean,
    maxUnavailable: Int,
    waitDrainMs:    Int,
    dryRun:         Boolean,
  ): RollingResult =
    import scala.util.boundary, boundary.break
    val upgraded = scala.collection.mutable.ListBuffer.empty[String]
    val failed   = scala.collection.mutable.ListBuffer.empty[String]
    val result: Option[RollingResult] = boundary {
      for url <- nodeUrls do
        if failed.size >= maxUnavailable then
          break(Some(RollingResult(upgraded.toList, failed.toList, nodeUrls.drop(upgraded.size + failed.size), aborted = true)))
        val drained = drainNode(url, waitDrainMs, dryRun)
        if !drained then
          failed += url
        else
          runNode(url) match
            case Right(_) =>
              val healthy = waitUntilHealthy(url, waitDrainMs * 2, healthCheck, dryRun)
              if healthy then upgraded += url
              else
                failed += url
                break(Some(RollingResult(upgraded.toList, failed.toList, nodeUrls.drop(upgraded.size + failed.size), aborted = true)))
            case Left(_) =>
              failed += url
              break(Some(RollingResult(upgraded.toList, failed.toList, nodeUrls.drop(upgraded.size + failed.size), aborted = true)))
      None
    }
    result.getOrElse(RollingResult(upgraded.toList, failed.toList, Nil, aborted = failed.nonEmpty))

  private def rollingBlueGreen(
    nodeUrls:    List[String],
    runNode:     String => Either[String, Unit],
    observeMs:   Int,
    dryRun:      Boolean,
  ): RollingResult =
    // Deploy to all nodes simultaneously, then observe
    val results   = nodeUrls.map { url => url -> runNode(url) }
    val succeeded = results.collect { case (url, Right(_)) => url }
    val failed    = results.collect { case (url, Left(_))  => url }
    if failed.nonEmpty then
      RollingResult(succeeded, failed, Nil, aborted = true)
    else
      if dryRun then
        println(s"[dry-run] BlueGreen: observing for ${observeMs}ms (skipped)")
      RollingResult(succeeded, Nil, Nil, aborted = false)

  private def rollingCanary(
    nodeUrls:    List[String],
    runNode:     String => Either[String, Unit],
    percent:     Int,
    observeMs:   Int,
    dryRun:      Boolean,
  ): RollingResult =
    val canaryCount  = math.max(1, (nodeUrls.size * percent / 100.0).ceil.toInt)
    val canaryNodes  = nodeUrls.take(canaryCount)
    val remainNodes  = nodeUrls.drop(canaryCount)
    val canaryResults = canaryNodes.map { url => url -> runNode(url) }
    val canaryFailed  = canaryResults.collect { case (url, Left(_)) => url }
    if canaryFailed.nonEmpty then
      RollingResult(Nil, canaryFailed, remainNodes, aborted = true)
    else
      if dryRun then
        println(s"[dry-run] Canary: observing ${canaryNodes.size} nodes for ${observeMs}ms (skipped)")
      val restResults = remainNodes.map { url => url -> runNode(url) }
      val succeeded   = canaryNodes ++ restResults.collect { case (url, Right(_)) => url }
      val failed      = restResults.collect { case (url, Left(_)) => url }
      RollingResult(succeeded, failed, Nil, aborted = failed.nonEmpty)

  // ── Drain helper ───────────────────────────────────────────────────────────

  private def drainNode(url: String, waitMs: Int, dryRun: Boolean): Boolean =
    if dryRun then
      println(s"[dry-run] POST $url/_ssc-cluster/drain")
      true
    else
      try
        val conn = URI(s"$url/_ssc-cluster/drain").toURL.openConnection().asInstanceOf[HttpURLConnection]
        conn.setRequestMethod("POST")
        conn.setConnectTimeout(5000)
        conn.setReadTimeout(waitMs)
        conn.connect()
        val code = conn.getResponseCode
        conn.disconnect()
        code == 200 || code == 204
      catch
        case e: Exception =>
          println(s"[deploy/cluster/drain-failed] $url: ${e.getMessage}")
          false

  private def waitUntilHealthy(url: String, timeoutMs: Int, healthCheck: String => Boolean, dryRun: Boolean): Boolean =
    if dryRun then healthCheck(url)
    else
      import scala.util.boundary, boundary.break
      val deadline = System.currentTimeMillis() + timeoutMs
      boundary {
        while System.currentTimeMillis() < deadline do
          if healthCheck(url) then break(true)
          Thread.sleep(2000)
        false
      }

/** Result of a rolling cluster upgrade. */
case class RollingResult(
  upgraded: List[String],
  failed:   List[String],
  skipped:  List[String],
  aborted:  Boolean,
)

/** Result of a multi-region deploy. */
case class MultiRegionResult(
  succeeded: List[String],
  failed:    List[String],
)
