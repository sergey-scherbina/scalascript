package scalascript.interpreter

/** Cluster-control HTTP route registration: /_ssc-cluster/{status,drain,step-down,
 *  metrics-prom,events,audit,workers,handlers}.  Routes read live cluster state
 *  from the `ActorScheduler` and register themselves against the interpreter's
 *  route registry via `host.actorRegisterHttpRoute`.
 *
 *  Health routes (/_health, /_ready) stay in `backendInterpreter/ClusterRoutesRuntime`
 *  since they don't depend on actor/cluster state.
 */
private[interpreter] object ActorClusterRoutes:

  private def clusterAuthReject(args: List[Value], sched: ActorScheduler): Option[Value] =
    val token = sched.clusterAuthToken
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
      val pending   = sched.pendingNewToken
      val validFrom = sched.tokenValidFromMs
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

  /** Register all cluster-control routes.  Called by `ActorScheduler` during `startNode`. */
  def registerAll(sched: ActorScheduler, host: ActorRuntimeHost): Unit =
    registerClusterDrainRoute(sched, host)
    registerClusterStepDownRoute(sched, host)
    registerClusterMetricsPromRoute(sched, host)
    registerClusterEventsRoute(sched, host)
    registerClusterStatusRoute(sched, host)
    registerClusterAuditRoute(sched, host)
    registerClusterWorkersRoute(sched, host)
    registerClusterHandlersRoute(sched, host)

  def registerClusterDrainRoute(sched: ActorScheduler, host: ActorRuntimeHost): Unit =
    val path = "/_ssc-cluster/drain"
    val handler = Value.NativeFnV("_clusterDrain", Computation.pureFn { args =>
      clusterAuthReject(args, sched).getOrElse {
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
        val prev = sched.isDrainingSelf.getAndSet(enabled)
        if prev != enabled then
          val payload = s"""{"t":"drain","from":${sched.jsonStr(sched.localNodeId)},"draining":$enabled}"""
          sched.peerChannels.forEach { (_, send) =>
            try send(payload) catch case _: Throwable => ()
          }
          sched.enqueueDrainEvent(sched.localNodeId, enabled)
          if enabled then sched.stepDownIfLeader()
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
    host.actorRegisterHttpRoute("POST", path, handler)

  def registerClusterStepDownRoute(sched: ActorScheduler, host: ActorRuntimeHost): Unit =
    val path = "/_ssc-cluster/step-down"
    val handler = Value.NativeFnV("_clusterStepDown", Computation.pureFn { args =>
      clusterAuthReject(args, sched).getOrElse {
        val wasLeader =
          sched.leaderProtocolRef.get() match
            case "raft"  => sched.raftStateName == "leader"
            case "coord" => sched.coordIsLeader
            case _       => sched.currentLeader.get() == sched.localNodeId
        if !wasLeader then
          Value.InstanceV("Response", Map(
            "status"  -> Value.intV(409),
            "headers" -> Value.MapV(Map(
              Value.StringV("Content-Type") -> Value.StringV("application/json")
            )),
            "body"    -> Value.StringV(
              s"""{"error":"not_leader","leader":${sched.jsonStr(sched.currentLeader.get())}}""")
          ))
        else
          sched.stepDownIfLeader()
          Value.InstanceV("Response", Map(
            "status"  -> Value.intV(200),
            "headers" -> Value.MapV(Map(
              Value.StringV("Content-Type") -> Value.StringV("application/json")
            )),
            "body"    -> Value.StringV(
              s"""{"steppedDown":true,"nodeId":${sched.jsonStr(sched.localNodeId)}}""")
          ))
      }
    })
    host.actorRegisterHttpRoute("POST", path, handler)

  def registerClusterMetricsPromRoute(sched: ActorScheduler, host: ActorRuntimeHost): Unit =
    val path = "/_ssc-cluster/metrics-prom"
    val handler = Value.NativeFnV("_clusterMetricsProm", Computation.pureFn { args =>
      clusterAuthReject(args, sched).getOrElse {
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
        sched.clusterMetrics.forEach { (name, inner) =>
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
    host.actorRegisterHttpRoute("GET", path, handler)

  def registerClusterEventsRoute(sched: ActorScheduler, host: ActorRuntimeHost): Unit =
    val path = "/_ssc-cluster/events"
    val handler = Value.NativeFnV("_clusterEvents", Computation.pureFn { args =>
      clusterAuthReject(args, sched).getOrElse {
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
        val it = sched.clusterEventLog.iterator()
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
    host.actorRegisterHttpRoute("GET", path, handler)

  def registerClusterStatusRoute(sched: ActorScheduler, host: ActorRuntimeHost): Unit =
    val path = "/_ssc-cluster/status"
    val handler = Value.NativeFnV("_clusterStatus", Computation.pureFn { args =>
      clusterAuthReject(args, sched).getOrElse {
        val sb = new StringBuilder("{")
        def kv(k: String, jsonVal: String, first: Boolean = false): Unit =
          if !first then sb.append(',')
          sb.append('"').append(k).append("\":").append(jsonVal)
        def jsonStrLit(s: String): String =
          "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        def jsonStrArr(xs: Iterable[String]): String =
          xs.map(jsonStrLit).mkString("[", ",", "]")
        val members = scala.collection.mutable.ListBuffer.empty[String]
        sched.peerChannels.keySet().forEach(members += _)
        val drainPeers = scala.collection.mutable.ListBuffer.empty[String]
        sched.drainingPeers.forEach { (nid, dr) =>
          if dr.booleanValue() then drainPeers += nid
        }
        val leaderNow =
          sched.leaderProtocolRef.get() match
            case "raft" => sched.raftLeaderId
            case _      => sched.currentLeader.get()
        kv("nodeId",    jsonStrLit(sched.localNodeId), first = true)
        kv("leader",    jsonStrLit(leaderNow))
        kv("protocol",  jsonStrLit(sched.leaderProtocolRef.get()))
        kv("members",   jsonStrArr(members.toList))
        kv("drainingSelf", if sched.isDrainingSelf.get() then "true" else "false")
        kv("drainingPeers", jsonStrArr(drainPeers.toList))
        kv("raftTerm",  sched.raftCurrentTerm.toString)
        kv("raftState", jsonStrLit(sched.raftStateName))
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
    host.actorRegisterHttpRoute("GET", path, handler)

  def registerClusterAuditRoute(sched: ActorScheduler, host: ActorRuntimeHost): Unit =
    val path = "/_ssc-cluster/audit"
    def jstr(s: String): String =
      "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    val auditHandler = Value.NativeFnV("_clusterAudit", Computation.pureFn { args =>
      clusterAuthReject(args, sched).getOrElse {
        val sb    = new StringBuilder("[")
        var first = true
        val it    = sched.bundleAuditLog.iterator()
        var n     = 0
        while it.hasNext && n < 200 do
          val (ts, event, detail, actor) = it.next()
          if !first then sb.append(',')
          first = false
          sb.append(s"""{"ts":$ts,"event":${jstr(event)},"detail":${jstr(detail)},"actor":${jstr(actor)}}""")
          n += 1
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
    host.actorRegisterHttpRoute("GET", path, auditHandler)

  def registerClusterWorkersRoute(sched: ActorScheduler, host: ActorRuntimeHost): Unit =
    val path = "/_ssc-cluster/workers"
    def jstr(s: String): String =
      "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    val workersHandler = Value.NativeFnV("_clusterWorkers", Computation.pureFn { args =>
      clusterAuthReject(args, sched).getOrElse {
        val sb    = new StringBuilder("[")
        var first = true
        sched.loadedBundles.forEach { (wid, entry) =>
          if !first then sb.append(',')
          first = false
          sb.append('{')
          sb.append(s""""workerId":${jstr(wid)}""")
          sb.append(s""","hash":${jstr(entry.hash)}""")
          sb.append(s""","prevHash":${jstr(entry.prevHash)}""")
          sb.append(s""","loadedAt":${entry.loadedAt}""")
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
    host.actorRegisterHttpRoute("GET", path, workersHandler)

  def registerClusterHandlersRoute(sched: ActorScheduler, host: ActorRuntimeHost): Unit =
    val path = "/_ssc-cluster/handlers"
    def jstr(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    val handler = Value.NativeFnV("_clusterHandlers", Computation.pureFn { args =>
      clusterAuthReject(args, sched).getOrElse {
        val handlers = host.actorRemoteHandlerInfos
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
    host.actorRegisterHttpRoute("GET", path, handler)
