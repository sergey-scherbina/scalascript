package scalascript.interpreter

/** Health and cluster-control HTTP route registration: /_health, /_ready,
 *  and /_ssc-cluster/{status,drain,step-down,metrics-prom,events}.
 */
private[interpreter] object ClusterRoutesRuntime:

  def registerHealthDefaults(interp: Interpreter): Unit =
    def isRegistered(path: String): Boolean =
      interp.routeRegistry.all.exists(e => e.method == "GET" && e.path == path)
    val okResponse = Value.InstanceV("Response", Map(
      "status"  -> Value.intV(200),
      "headers" -> Value.MapV(Map(
        Value.StringV("Content-Type") -> Value.StringV("application/json")
      )),
      "body"    -> Value.StringV("""{"status":"ok"}""")
    ))
    val handler = Value.NativeFnV("_healthOk", Computation.pureFn(_ => okResponse))
    if !isRegistered("/_health") then
      interp.routeRegistry.register("GET", "/_health", handler, interp)
    if !isRegistered("/_ready") then
      interp.routeRegistry.register("GET", "/_ready", handler, interp)

  private def clusterAuthReject(args: List[Value], interp: Interpreter): Option[Value] =
    val token = interp.clusterAuthToken
    if token.isEmpty then None
    else
      val hdr = args.headOption.flatMap {
        case Value.InstanceV("Request", fs) =>
          fs.get("headers") match
            case Some(Value.MapV(m)) =>
              m.collectFirst {
                case (Value.StringV(k), Value.StringV(v))
                    if k.equalsIgnoreCase("authorization") => v
              }
            case _ => None
        case _ => None
      }.getOrElse("")
      // v1.63.7: accept pending new token during overlap window
      val pending   = interp.pendingNewToken
      val validFrom = interp.tokenValidFromMs
      val now       = System.currentTimeMillis()
      val allowNew  = pending.nonEmpty && now < validFrom
      if hdr == "Bearer " + token || (allowNew && hdr == "Bearer " + pending) then None
      else
        Some(Value.InstanceV("Response", Map(
          "status"  -> Value.intV(401),
          "headers" -> Value.MapV(Map(
            Value.StringV("Content-Type") -> Value.StringV("application/json")
          )),
          "body"    -> Value.StringV(
            """{"error":"unauthorized","hint":"set Authorization: Bearer <token>"}""")
        )))

  def registerClusterDrainRoute(interp: Interpreter): Unit =
    val path = "/_ssc-cluster/drain"
    val already = interp.routeRegistry.all.exists(e =>
      e.method == "POST" && e.path == path)
    if already then return
    val handler = Value.NativeFnV("_clusterDrain", Computation.pureFn { args =>
      clusterAuthReject(args, interp).getOrElse {
        val body = args.headOption.flatMap {
          case Value.InstanceV("Request", fs) =>
            fs.get("body").collect { case Value.StringV(s) => s }
          case _ => None
        }.getOrElse("")
        val enabled: Boolean =
          if body.trim.isEmpty then true
          else
            val needle = "\"enabled\":"
            val i = body.indexOf(needle)
            if i < 0 then true
            else
              val rest = body.substring(i + needle.length).trim
              !rest.startsWith("false")
        val prev = interp.isDrainingSelf.getAndSet(enabled)
        if prev != enabled then
          val payload = s"""{"t":"drain","from":${interp.jsonStr(interp.localNodeId)},"draining":$enabled}"""
          interp.peerChannels.forEach { (_, send) =>
            try send(payload) catch case _: Throwable => ()
          }
          interp.enqueueDrainEvent(interp.localNodeId, enabled)
          if enabled then interp.stepDownIfLeader()
        Value.InstanceV("Response", Map(
          "status"  -> Value.intV(200),
          "headers" -> Value.MapV(Map(
            Value.StringV("Content-Type") -> Value.StringV("application/json")
          )),
          "body"    -> Value.StringV(
            s"""{"drainingSelf":${if enabled then "true" else "false"}}""")
        ))
      }
    })
    interp.routeRegistry.register("POST", path, handler, interp)

  def registerClusterStepDownRoute(interp: Interpreter): Unit =
    val path = "/_ssc-cluster/step-down"
    val already = interp.routeRegistry.all.exists(e =>
      e.method == "POST" && e.path == path)
    if already then return
    val handler = Value.NativeFnV("_clusterStepDown", Computation.pureFn { args =>
      clusterAuthReject(args, interp).getOrElse {
        val wasLeader =
          interp.leaderProtocolRef.get() match
            case "raft"  => interp.raftStateName == "leader"
            case "coord" => interp.coordIsLeader
            case _       => interp.currentLeader.get() == interp.localNodeId
        if !wasLeader then
          Value.InstanceV("Response", Map(
            "status"  -> Value.intV(409),
            "headers" -> Value.MapV(Map(
              Value.StringV("Content-Type") -> Value.StringV("application/json")
            )),
            "body"    -> Value.StringV(
              s"""{"error":"not_leader","leader":${interp.jsonStr(interp.currentLeader.get())}}""")
          ))
        else
          interp.stepDownIfLeader()
          Value.InstanceV("Response", Map(
            "status"  -> Value.intV(200),
            "headers" -> Value.MapV(Map(
              Value.StringV("Content-Type") -> Value.StringV("application/json")
            )),
            "body"    -> Value.StringV(
              s"""{"steppedDown":true,"nodeId":${interp.jsonStr(interp.localNodeId)}}""")
          ))
      }
    })
    interp.routeRegistry.register("POST", path, handler, interp)

  def registerClusterMetricsPromRoute(interp: Interpreter): Unit =
    val path = "/_ssc-cluster/metrics-prom"
    val already = interp.routeRegistry.all.exists(e =>
      e.method == "GET" && e.path == path)
    if already then return
    val handler = Value.NativeFnV("_clusterMetricsProm", Computation.pureFn { args =>
      clusterAuthReject(args, interp).getOrElse {
        def sanitize(s: String): String =
          val sb = new StringBuilder(s.length)
          var i = 0
          while i < s.length do
            val c = s.charAt(i)
            val ok =
              (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
              (c >= '0' && c <= '9') || c == '_' || c == ':'
            sb.append(if ok then c else '_')
            i += 1
          val out = sb.toString
          if out.nonEmpty && out.charAt(0) >= '0' && out.charAt(0) <= '9'
          then "_" + out else out
        def escLabel(s: String): String =
          s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val sb = new StringBuilder()
        interp.clusterMetrics.forEach { (name, inner) =>
          val pName = sanitize(name)
          sb.append("# TYPE ").append(pName).append(" gauge\n")
          inner.forEach { (nodeId, value) =>
            sb.append(pName)
              .append("{nodeId=\"").append(escLabel(nodeId)).append("\"} ")
              .append(value.doubleValue())
              .append('\n')
          }
        }
        Value.InstanceV("Response", Map(
          "status"  -> Value.intV(200),
          "headers" -> Value.MapV(Map(
            Value.StringV("Content-Type") ->
              Value.StringV("text/plain; version=0.0.4; charset=utf-8")
          )),
          "body"    -> Value.StringV(sb.toString)
        ))
      }
    })
    interp.routeRegistry.register("GET", path, handler, interp)

  def registerClusterEventsRoute(interp: Interpreter): Unit =
    val path = "/_ssc-cluster/events"
    val already = interp.routeRegistry.all.exists(e =>
      e.method == "GET" && e.path == path)
    if already then return
    val handler = Value.NativeFnV("_clusterEvents", Computation.pureFn { args =>
      clusterAuthReject(args, interp).getOrElse {
        val sinceMs: Long = args.headOption.flatMap {
          case Value.InstanceV("Request", fs) =>
            fs.get("query") match
              case Some(Value.MapV(m)) =>
                m.collectFirst {
                  case (Value.StringV("since"), Value.StringV(v)) => v
                }.flatMap(_.toLongOption)
              case _ => None
          case _ => None
        }.getOrElse(0L)
        val sb = new StringBuilder("[")
        var first = true
        val it = interp.clusterEventLog.iterator()
        while it.hasNext do
          val line = it.next()
          val tsMatch =
            if sinceMs <= 0L then true
            else
              val tsPrefix = "{\"ts\":"
              if line.startsWith(tsPrefix) then
                val end = line.indexOf(',', tsPrefix.length)
                if end > 0 then
                  line.substring(tsPrefix.length, end).toLongOption
                    .exists(_ > sinceMs)
                else false
              else false
          if tsMatch then
            if !first then sb.append(',')
            sb.append(line)
            first = false
        sb.append(']')
        Value.InstanceV("Response", Map(
          "status"  -> Value.intV(200),
          "headers" -> Value.MapV(Map(
            Value.StringV("Content-Type") -> Value.StringV("application/json")
          )),
          "body"    -> Value.StringV(sb.toString)
        ))
      }
    })
    interp.routeRegistry.register("GET", path, handler, interp)

  def registerClusterStatusRoute(interp: Interpreter): Unit =
    val path = "/_ssc-cluster/status"
    val already = interp.routeRegistry.all.exists(e =>
      e.method == "GET" && e.path == path)
    if already then return
    val handler = Value.NativeFnV("_clusterStatus", Computation.pureFn { args =>
      clusterAuthReject(args, interp).getOrElse {
        val sb = new StringBuilder("{")
        def kv(k: String, jsonVal: String, first: Boolean = false): Unit =
          if !first then sb.append(',')
          sb.append('"').append(k).append("\":").append(jsonVal)
        def jsonStrLit(s: String): String =
          "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        def jsonStrArr(xs: Iterable[String]): String =
          xs.map(jsonStrLit).mkString("[", ",", "]")
        val members = scala.collection.mutable.ListBuffer.empty[String]
        interp.peerChannels.keySet().forEach(members += _)
        val drainPeers = scala.collection.mutable.ListBuffer.empty[String]
        interp.drainingPeers.forEach { (nid, dr) =>
          if dr.booleanValue() then drainPeers += nid
        }
        val leaderNow =
          interp.leaderProtocolRef.get() match
            case "raft" => interp.raftLeaderId
            case _      => interp.currentLeader.get()
        kv("nodeId",    jsonStrLit(interp.localNodeId), first = true)
        kv("leader",    jsonStrLit(leaderNow))
        kv("protocol",  jsonStrLit(interp.leaderProtocolRef.get()))
        kv("members",   jsonStrArr(members.toList))
        kv("drainingSelf", if interp.isDrainingSelf.get() then "true" else "false")
        kv("drainingPeers", jsonStrArr(drainPeers.toList))
        kv("raftTerm",  interp.raftCurrentTerm.toString)
        kv("raftState", jsonStrLit(interp.raftStateName))
        sb.append('}')
        Value.InstanceV("Response", Map(
          "status"  -> Value.intV(200),
          "headers" -> Value.MapV(Map(
            Value.StringV("Content-Type") -> Value.StringV("application/json")
          )),
          "body"    -> Value.StringV(sb.toString)
        ))
      }
    })
    interp.routeRegistry.register("GET", path, handler, interp)

  def registerClusterHandlersRoute(interp: Interpreter): Unit =
    val path = "/_ssc-cluster/handlers"
    val already = interp.routeRegistry.all.exists(e =>
      e.method == "GET" && e.path == path)
    if already then return
    def jstr(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    val handler = Value.NativeFnV("_clusterHandlers", Computation.pureFn { args =>
      clusterAuthReject(args, interp).getOrElse {
        val handlers = interp.remoteHandlerInfos
        val sb = new StringBuilder("[")
        var first = true
        handlers.foreach { h =>
          if !first then sb.append(',')
          first = false
          sb.append('{')
          sb.append(s""""name":${jstr(h.name)}""")
          sb.append(s""","function":${jstr(h.function)}""")
          h.path.foreach { p => sb.append(s""","path":${jstr(p)}""") }
          h.requestType.foreach { t => sb.append(s""","requestType":${jstr(t)}""") }
          h.responseType.foreach { t => sb.append(s""","responseType":${jstr(t)}""") }
          val transports = h.transports.map(t => s""""$t"""").mkString(",")
          sb.append(s""","transports":[$transports]""")
          sb.append('}')
        }
        sb.append(']')
        Value.InstanceV("Response", Map(
          "status"  -> Value.intV(200),
          "headers" -> Value.MapV(Map(
            Value.StringV("Content-Type") -> Value.StringV("application/json")
          )),
          "body"    -> Value.StringV(sb.toString)
        ))
      }
    })
    interp.routeRegistry.register("GET", path, handler, interp)
