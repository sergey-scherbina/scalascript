package scalascript.cli

// `ssc cluster <sub>` family (status / events / drain / step-down / run /
// package / handlers / stop) + their HTTP helpers. Extracted from Main.scala.
// All HTTP/crypto types are referenced fully-qualified, so no imports needed.

/** `ssc cluster status <url>` — fetch GET <url>/_ssc-cluster/status
 *  from a running ssc node and pretty-print the JSON snapshot.  The
 *  endpoint is registered automatically when the node calls
 *  `startNode(...)`.  Output is a human-readable summary; raw JSON
 *  is available via `--json`. */
final class ClusterCmd extends CliCommand:
  def name = "cluster"
  override def summary = "Inspect or operate a running ssc cluster node"
  override def category = "Services & tooling"
  override def details = List("Subs: status | events | drain | step-down | run | package | handlers | stop")
  def run(args: List[String]): Unit =
    args match
      case "status"   :: rest => clusterStatusCommand(rest)
      case "drain"    :: rest => clusterDrainCommand(rest)
      case "events"   :: rest => clusterEventsCommand(rest)
      case "step-down" :: rest => clusterStepDownCommand(rest)
      case "handlers" :: rest => clusterHandlersCommand(rest)
      case "run"      :: rest => clusterRunCommand(rest)
      case "package"  :: rest => clusterPackageCommand(rest)
      case "stop"     :: rest => clusterStopCommand(rest)
      case ("help" | "--help" | "-h") :: _ =>
        println("Usage: ssc cluster <subcommand>")
        println("  status    <url> [--json] [--token=<t>]            show JSON snapshot")
        println("  drain     <url> [--off]  [--token=<t>]            toggle drain mode")
        println("  events    <url> [--since=<ms>] [--token=<t>]      dump events ring")
        println("  step-down <url> [--token=<t>]                     graceful leader step-down")
        println("  handlers  --seed <url> [--token=<t>] [--json]     list exported handlers")
        println("  run       <file.ssc> [--role <r>] [--node-id <id>] [--bind <addr>] [--join <url>]")
        println("  package   <file.ssc> --out <dist.zip> [--target worker]")
        println("  stop      --seed <url> [--token=<t>]              drain then step-down")
        println()
        println("Auth: --token=<t> or SSC_CLUSTER_TOKEN env.  Sends")
        println("`Authorization: Bearer <token>` on every request.")
      case _ =>
        System.err.println("Usage: ssc cluster {status|drain|events|step-down|handlers|run|package|stop} <url> [opts]")
        System.exit(2)

private def clusterStepDownCommand(args: List[String]): Unit =
  val (flags, urlOpt) = args.partition(_.startsWith("--"))
  if urlOpt.isEmpty then
    System.err.println("Usage: ssc cluster step-down <url> [--token=<t>]")
    System.exit(2)
  else
    val url = urlOpt.head
    val target =
      if url.endsWith("/_ssc-cluster/step-down") then url
      else url.stripSuffix("/") + "/_ssc-cluster/step-down"
    val token  = clusterAuthTokenFor(flags)
    val client = java.net.http.HttpClient.newBuilder()
      .connectTimeout(java.time.Duration.ofSeconds(5)).build()
    val req = applyClusterAuth(
      java.net.http.HttpRequest.newBuilder()
        .uri(java.net.URI.create(target))
        .timeout(java.time.Duration.ofSeconds(10))
        .header("Content-Type", "application/json"),
      token
    ).POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build()
    val respOpt: Option[java.net.http.HttpResponse[String]] =
      try Some(client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()))
      catch case e: Throwable =>
        System.err.println(s"failed to POST $target: ${e.getMessage}")
        System.exit(1)
        None
    respOpt.foreach { resp =>
      resp.statusCode() match
        case 200 =>
          println(resp.body())
        case 409 =>
          // Not leader — surface the body's `"leader":"..."` field so
          // the operator knows where to point the next attempt.
          System.err.println("not leader — current leader: " + resp.body())
          System.exit(1)
        case other =>
          System.err.println(s"unexpected status $other from $target")
          System.err.println(resp.body())
          System.exit(1)
    }

/** Pull the shared-secret Bearer token from a `--token=<t>` flag (if
 *  present) or the `SSC_CLUSTER_TOKEN` env var.  Empty result ⇒ skip
 *  the Authorization header — endpoints accept anonymous calls when
 *  the server's `setClusterAuthToken` is unset. */
private def clusterAuthTokenFor(flags: List[String]): String =
  flags.find(_.startsWith("--token="))
    .map(_.stripPrefix("--token="))
    .orElse(sys.env.get("SSC_CLUSTER_TOKEN"))
    .getOrElse("")

/** Attach `Authorization: Bearer <token>` when non-empty.  Always
 *  sets Content-Type for the POST flows. */
private def applyClusterAuth(
    builder: java.net.http.HttpRequest.Builder,
    token: String
): java.net.http.HttpRequest.Builder =
  if token.nonEmpty then builder.header("Authorization", "Bearer " + token)
  else builder

private def clusterEventsCommand(args: List[String]): Unit =
  val (flags, urlOpt) = args.partition(_.startsWith("--"))
  val sinceMs: Option[Long] = flags
    .find(_.startsWith("--since="))
    .map(_.stripPrefix("--since="))
    .flatMap(_.toLongOption)
  if urlOpt.isEmpty then
    System.err.println("Usage: ssc cluster events <url> [--since=<epoch-ms>]")
    System.exit(2)
  else
    val url = urlOpt.head
    val base =
      if url.endsWith("/_ssc-cluster/events") then url
      else url.stripSuffix("/") + "/_ssc-cluster/events"
    val full = sinceMs match
      case Some(t) => base + "?since=" + t
      case None    => base
    val token = clusterAuthTokenFor(flags)
    val client = java.net.http.HttpClient.newBuilder()
      .connectTimeout(java.time.Duration.ofSeconds(5)).build()
    val req = applyClusterAuth(
      java.net.http.HttpRequest.newBuilder()
        .uri(java.net.URI.create(full))
        .timeout(java.time.Duration.ofSeconds(10)),
      token
    ).GET().build()
    val respOpt: Option[java.net.http.HttpResponse[String]] =
      try Some(client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()))
      catch case e: Throwable =>
        System.err.println(s"failed to GET $full: ${e.getMessage}")
        System.exit(1)
        None
    respOpt.foreach { resp =>
      if resp.statusCode() != 200 then
        System.err.println(s"unexpected status ${resp.statusCode()} from $full")
        System.err.println(resp.body())
        System.exit(1)
      else
        // Body is a flat JSON array — split on `},{` and print one
        // event per line.  Avoid pulling in a JSON parser.
        val body = resp.body().trim
        if body == "[]" then println("(no events)")
        else
          val inner = body.stripPrefix("[").stripSuffix("]")
          val parts = inner.split("\\},\\{").toIndexedSeq.map { s =>
            val withOpen  = if s.startsWith("{") then s else "{" + s
            if withOpen.endsWith("}") then withOpen else withOpen + "}"
          }
          parts.foreach(println)
    }

private def clusterDrainCommand(args: List[String]): Unit =
  val (flags, urlOpt) = args.partition(_.startsWith("--"))
  val off = flags.contains("--off")
  if urlOpt.isEmpty then
    System.err.println("Usage: ssc cluster drain <url> [--off]")
    System.err.println("  e.g. ssc cluster drain http://localhost:8080      # enable")
    System.err.println("       ssc cluster drain http://localhost:8080 --off  # disable")
    System.exit(2)
  else
    val url = urlOpt.head
    val drainUrl =
      if url.endsWith("/_ssc-cluster/drain") then url
      else url.stripSuffix("/") + "/_ssc-cluster/drain"
    val payload = if off then """{"enabled":false}""" else """{"enabled":true}"""
    val token = clusterAuthTokenFor(flags)
    val client = java.net.http.HttpClient.newBuilder()
      .connectTimeout(java.time.Duration.ofSeconds(5)).build()
    val req = applyClusterAuth(
      java.net.http.HttpRequest.newBuilder()
        .uri(java.net.URI.create(drainUrl))
        .timeout(java.time.Duration.ofSeconds(10))
        .header("Content-Type", "application/json"),
      token
    ).POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload)).build()
    val respOpt: Option[java.net.http.HttpResponse[String]] =
      try Some(client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()))
      catch case e: Throwable =>
        System.err.println(s"failed to POST $drainUrl: ${e.getMessage}")
        System.exit(1)
        None
    respOpt.foreach { resp =>
      if resp.statusCode() != 200 then
        System.err.println(s"unexpected status ${resp.statusCode()} from $drainUrl")
        System.err.println(resp.body())
        System.exit(1)
      else
        println(s"${if off then "disabled" else "enabled"} drain on $url")
        println(resp.body())
    }

private def clusterStatusCommand(args: List[String]): Unit =
  val (raw, urlOpt) = args.partition(_.startsWith("--"))
  val rawJson = raw.contains("--json")
  if urlOpt.isEmpty then
    System.err.println("Usage: ssc cluster status <url> [--json]")
    System.err.println("  e.g. ssc cluster status http://localhost:8080")
    System.exit(2)
  else
    val url = urlOpt.head
    val statusUrl =
      if url.endsWith("/_ssc-cluster/status") then url
      else url.stripSuffix("/") + "/_ssc-cluster/status"
    val token = clusterAuthTokenFor(raw)
    val client = java.net.http.HttpClient.newBuilder()
      .connectTimeout(java.time.Duration.ofSeconds(5)).build()
    val req = applyClusterAuth(
      java.net.http.HttpRequest.newBuilder()
        .uri(java.net.URI.create(statusUrl))
        .timeout(java.time.Duration.ofSeconds(10)),
      token
    ).GET().build()
    val respOpt: Option[java.net.http.HttpResponse[String]] =
      try Some(client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()))
      catch case e: Throwable =>
        System.err.println(s"failed to GET $statusUrl: ${e.getMessage}")
        System.exit(1)
        None
    respOpt.foreach { resp =>
      if resp.statusCode() != 200 then
        System.err.println(s"unexpected status ${resp.statusCode()} from $statusUrl")
        System.err.println(resp.body())
        System.exit(1)
      else if rawJson then
        println(resp.body())
      else
        printClusterStatusHuman(resp.body())
    }

private def printClusterStatusHuman(body: String): Unit =
  // Hand-rolled JSON extraction — keeps the CLI free of a JSON dep.
  // Reads only what we emit on the server side (flat object, scalar
  // values + two string arrays).
  def strField(key: String): String =
    val pat = "\"" + key + "\":\""
    val i = body.indexOf(pat)
    if i < 0 then ""
    else
      val start = i + pat.length
      val end   = body.indexOf("\"", start)
      if end < 0 then "" else body.substring(start, end)
  def boolField(key: String): String =
    val pat = "\"" + key + "\":"
    val i = body.indexOf(pat)
    if i < 0 then ""
    else
      val start = i + pat.length
      if body.startsWith("true",  start) then "true"
      else if body.startsWith("false", start) then "false"
      else ""
  def intField(key: String): String =
    val pat = "\"" + key + "\":"
    val i = body.indexOf(pat)
    if i < 0 then ""
    else
      val start = i + pat.length
      val end   = body.indexOf(',', start) match
        case -1 => body.indexOf('}', start)
        case n  => n
      if end < 0 then "" else body.substring(start, end).trim
  def arrField(key: String): List[String] =
    val pat = "\"" + key + "\":["
    val i = body.indexOf(pat)
    if i < 0 then Nil
    else
      val start = i + pat.length
      val end   = body.indexOf(']', start)
      if end < 0 then Nil
      else
        val inside = body.substring(start, end).trim
        if inside.isEmpty then Nil
        else inside.split(',').toList.map(_.trim.stripPrefix("\"").stripSuffix("\""))
  println(s"node:        ${strField("nodeId")}")
  println(s"protocol:    ${strField("protocol")}")
  val leader = strField("leader")
  println(s"leader:      ${if leader.isEmpty then "<none>" else leader}")
  val members = arrField("members")
  println(s"members:     ${if members.isEmpty then "<none>" else members.mkString(", ")}")
  println(s"drainingSelf: ${boolField("drainingSelf")}")
  val drainPeers = arrField("drainingPeers")
  if drainPeers.nonEmpty then
    println(s"drainingPeers: ${drainPeers.mkString(", ")}")
  val rt = intField("raftTerm")
  val rs = strField("raftState")
  if strField("protocol") == "raft" || rt != "0" then
    println(s"raftTerm:    $rt")
    println(s"raftState:   $rs")

// ── v1.63.5 — cluster run/package/handlers/stop ──────────────────────────────

/** `ssc cluster handlers --seed <url>` — fetch GET <url>/_ssc-cluster/handlers
 *  and list exported remote-handler operations. */
private def clusterHandlersCommand(args: List[String]): Unit =
  val (flags, positional) = args.partition(_.startsWith("--"))
  val seedOpt = flags.collectFirst { case s if s.startsWith("--seed=") => s.drop(7) }
    .orElse(flags.zipWithIndex.collectFirst { case ("--seed", i) if i + 1 < flags.size => flags(i + 1) })
    .orElse(positional.headOption)
  if seedOpt.isEmpty then
    System.err.println("Usage: ssc cluster handlers --seed <url> [--token=<t>] [--json]")
    System.exit(2)
  else
    val seed = seedOpt.get
    val handlersUrl =
      if seed.endsWith("/_ssc-cluster/handlers") then seed
      else seed.stripSuffix("/") + "/_ssc-cluster/handlers"
    val token = clusterAuthTokenFor(flags)
    val rawJson = flags.contains("--json")
    val client = java.net.http.HttpClient.newBuilder()
      .connectTimeout(java.time.Duration.ofSeconds(5)).build()
    val req = applyClusterAuth(
      java.net.http.HttpRequest.newBuilder()
        .uri(java.net.URI.create(handlersUrl))
        .timeout(java.time.Duration.ofSeconds(10)),
      token
    ).GET().build()
    val respOpt =
      try Some(client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()))
      catch case e: Throwable =>
        System.err.println(s"failed to GET $handlersUrl: ${e.getMessage}")
        System.exit(1)
        None
    respOpt.foreach { resp =>
      if resp.statusCode() != 200 then
        System.err.println(s"unexpected status ${resp.statusCode()} from $handlersUrl")
        System.err.println(resp.body())
        System.exit(1)
      else if rawJson then
        println(resp.body())
      else
        // Hand-rolled extraction of name/path/transports from JSON array.
        val body = resp.body()
        println("Remote handlers:")
        val entries = scala.collection.mutable.ArrayBuffer.empty[(String, String, String)]
        var pos = 0
        while pos < body.length do
          val nameStart = body.indexOf("\"name\":\"", pos)
          if nameStart < 0 then pos = body.length
          else
            val ns = nameStart + 8
            val ne = body.indexOf('"', ns)
            val name = if ne > ns then body.substring(ns, ne) else "?"
            val pathStart = body.indexOf("\"path\":\"", pos)
            val path =
              if pathStart >= 0 && pathStart < body.indexOf('}', nameStart) then
                val ps = pathStart + 8; val pe = body.indexOf('"', ps)
                if pe > ps then body.substring(ps, pe) else ""
              else ""
            entries += ((name, path, ""))
            pos = ne + 1
        if entries.isEmpty then println("  (none)")
        else entries.foreach { (name, path, _) =>
          println(s"  $name${if path.nonEmpty then s" → $path" else ""}")
        }
    }

/** `ssc cluster run <file.ssc> [--role <r>] [--node-id <id>] [--bind <addr>] [--join <url>]`
 *  — run a .ssc file as a cluster node by injecting cluster env vars and
 *  delegating to the normal `ssc run` command. */
private def clusterRunCommand(args: List[String]): Unit =
  if args.isEmpty then
    System.err.println("Usage: ssc cluster run <file.ssc> [--role <r>] [--node-id <id>] [--bind <addr:port>] [--join <ws-url>] [--token <t>]")
    System.exit(2)
  val it = args.iterator
  var fileArg: Option[String] = None
  var roleFlag: Option[String] = None
  var nodeIdFlag: Option[String] = None
  var bindFlag: Option[String] = None
  var joinFlag: Option[String] = None
  var tokenFlag: Option[String] = None
  val extra = scala.collection.mutable.ArrayBuffer.empty[String]
  while it.hasNext do
    it.next() match
      case "--role"    if it.hasNext => roleFlag    = Some(it.next())
      case "--node-id" if it.hasNext => nodeIdFlag  = Some(it.next())
      case "--bind"    if it.hasNext => bindFlag    = Some(it.next())
      case "--join"    if it.hasNext => joinFlag    = Some(it.next())
      case "--token"   if it.hasNext => tokenFlag   = Some(it.next())
      case flag if flag.startsWith("--role=")    => roleFlag    = Some(flag.drop(7))
      case flag if flag.startsWith("--node-id=") => nodeIdFlag  = Some(flag.drop(10))
      case flag if flag.startsWith("--bind=")    => bindFlag    = Some(flag.drop(7))
      case flag if flag.startsWith("--join=")    => joinFlag    = Some(flag.drop(7))
      case flag if flag.startsWith("--token=")   => tokenFlag   = Some(flag.drop(8))
      case other if !other.startsWith("--") && fileArg.isEmpty => fileArg = Some(other)
      case other => extra += other
  if fileArg.isEmpty then
    System.err.println("ssc cluster run: no .ssc file specified")
    System.exit(2)
  val envVars = scala.collection.mutable.Map.empty[String, String]
  roleFlag.foreach   { r => envVars("SSC_CLUSTER_ROLE")  = r }
  nodeIdFlag.foreach { n => envVars("SSC_NODE_ID")       = n }
  bindFlag.foreach   { b => envVars("SSC_BIND")          = b }
  joinFlag.foreach   { j => envVars("SSC_JOIN_SEEDS")    = j }
  tokenFlag.foreach  { t => envVars("SSC_CLUSTER_TOKEN") = t }
  // Delegate to the regular runCommand path with cluster env vars injected.
  val result = os.proc("ssc", "run", fileArg.get)
    .call(stdout = os.Inherit, stderr = os.Inherit, env = envVars.toMap, check = false)
  System.exit(result.exitCode)

/** `ssc cluster package <file.ssc> --out <dist.zip> [--target worker]` —
 *  package a .ssc source file into a worker bundle zip with code identity
 *  and registry metadata. */
private def clusterPackageCommand(args: List[String]): Unit =
  val it = args.iterator
  var fileArg: Option[String] = None
  var outFlag:    Option[String] = None
  var targetFlag: Option[String] = Some("worker")
  while it.hasNext do
    it.next() match
      case "--out"    if it.hasNext => outFlag    = Some(it.next())
      case "--target" if it.hasNext => targetFlag = Some(it.next())
      case flag if flag.startsWith("--out=")    => outFlag    = Some(flag.drop(6))
      case flag if flag.startsWith("--target=") => targetFlag = Some(flag.drop(9))
      case other if !other.startsWith("--") && fileArg.isEmpty => fileArg = Some(other)
      case _ => ()
  if fileArg.isEmpty || outFlag.isEmpty then
    System.err.println("Usage: ssc cluster package <file.ssc> --out <dist.zip> [--target worker]")
    System.exit(2)
  val srcPath = os.Path(fileArg.get, os.pwd)
  if !os.exists(srcPath) then
    System.err.println(s"ssc cluster package: file not found: $srcPath")
    System.exit(1)
  val outPath = os.Path(outFlag.get, os.pwd)
  os.makeDir.all(outPath / os.up)
  // Compute a simple SHA-256 code identity hash over the source bytes.
  val srcBytes = os.read.bytes(srcPath)
  val digest   = java.security.MessageDigest.getInstance("SHA-256")
  val hashHex  = digest.digest(srcBytes).map(b => "%02x".format(b)).mkString
  val target   = targetFlag.getOrElse("worker")
  // Build the worker bundle metadata JSON.
  val metaJson =
    s"""{
       |  "bundleVersion": "1",
       |  "target": "$target",
       |  "sourceFile": "${srcPath.last}",
       |  "codeIdentity": {
       |    "algorithmId": "sha256",
       |    "hash": "$hashHex"
       |  },
       |  "registryMetadata": {
       |    "remoteHandlers": [],
       |    "exportedBehaviors": [],
       |    "exportedSources": []
       |  },
       |  "runtimeVersion": "1.63.5"
       |}""".stripMargin
  // Write the zip: source file + manifest.json.
  val zos = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(outPath.toIO))
  try
    zos.putNextEntry(new java.util.zip.ZipEntry(srcPath.last))
    zos.write(srcBytes)
    zos.closeEntry()
    zos.putNextEntry(new java.util.zip.ZipEntry("manifest.json"))
    zos.write(metaJson.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    zos.closeEntry()
  finally
    zos.close()
  println(s"[ssc] cluster package: wrote ${outPath}")
  println(s"[ssc]   source:       ${srcPath.last}")
  println(s"[ssc]   codeIdentity: sha256:${hashHex.take(16)}...")
  println(s"[ssc]   target:       $target")

/** `ssc cluster stop --seed <url> [--token <t>]` — drain the target node
 *  and request a graceful leader step-down. */
private def clusterStopCommand(args: List[String]): Unit =
  val (flags, positional) = args.partition(_.startsWith("--"))
  val seedOpt = flags.collectFirst { case s if s.startsWith("--seed=") => s.drop(7) }
    .orElse(positional.headOption)
  if seedOpt.isEmpty then
    System.err.println("Usage: ssc cluster stop --seed <url> [--token=<t>]")
    System.exit(2)
  else
    val seed  = seedOpt.get.stripSuffix("/")
    val token = clusterAuthTokenFor(flags)
    val client = java.net.http.HttpClient.newBuilder()
      .connectTimeout(java.time.Duration.ofSeconds(5)).build()
    def post(path: String, body: String): Int =
      val req = applyClusterAuth(
        java.net.http.HttpRequest.newBuilder()
          .uri(java.net.URI.create(seed + path))
          .timeout(java.time.Duration.ofSeconds(10))
          .header("Content-Type", "application/json"),
        token
      ).POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build()
      try client.send(req, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()
      catch case e: Throwable =>
        System.err.println(s"request to $seed$path failed: ${e.getMessage}")
        -1
    System.err.println(s"[ssc] draining $seed ...")
    val drainStatus = post("/_ssc-cluster/drain", """{"enabled":true}""")
    if drainStatus >= 0 && drainStatus < 300 then
      System.err.println(s"[ssc] stepping down leader ...")
      post("/_ssc-cluster/step-down", "")
    System.err.println(s"[ssc] stop signal sent to $seed")
