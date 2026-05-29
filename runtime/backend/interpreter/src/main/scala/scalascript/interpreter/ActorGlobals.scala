package scalascript.interpreter

import Computation.Perform

/** Actor/cluster globals registration — pure Perform-node factories.
 *  The cooperative scheduler and handleActorOp live in Interpreter.scala.
 */
private[interpreter] object ActorGlobals:

  def install(interp: Interpreter): Unit =
    val g = interp.globals

    // ── Phase 1 — local actor primitives ─────────────────────────────────
    g("spawn") = Value.NativeFnV("spawn", {
      case List(thunk) => Perform("Actor", "spawn", thunk :: Nil)
      case _           => throw InterpretError("spawn(behavior: () => Unit)")
    })
    g("spawnBounded") = Value.NativeFnV("spawnBounded", {
      case List(Value.IntV(cap), overflow, thunk) =>
        val strategy = overflow match
          case Value.InstanceV(name, _) => name
          case _                        => "DropNewest"
        Perform("Actor", "spawnBounded", Value.intV(cap) :: Value.StringV(strategy) :: thunk :: Nil)
      case _ => throw InterpretError("spawnBounded(capacity: Int, overflow: Overflow, behavior: () => Unit)")
    })
    g("processInfo") = Value.NativeFnV("processInfo", {
      case List(pid @ Value.InstanceV("Pid", _)) => Perform("Actor", "processInfo", pid :: Nil)
      case _ => throw InterpretError("processInfo(pid)")
    })
    g("spawn_link") = Value.NativeFnV("spawn_link", {
      case List(thunk) => Perform("Actor", "spawnLink", thunk :: Nil)
      case _           => throw InterpretError("spawn_link(behavior: () => Unit)")
    })
    g("self") = Value.NativeFnV("self", {
      case Nil => Perform("Actor", "self", Nil)
      case _   => throw InterpretError("self takes no arguments")
    })
    g("actorRef") = Value.NativeFnV("actorRef", {
      case List(pid @ Value.InstanceV("Pid", _)) => Computation.Pure(pid)
      case _ => throw InterpretError("actorRef(pid): ActorRef[M]")
    })
    g("actorRefAddress") = Value.NativeFnV("actorRefAddress", {
      case List(pid @ Value.InstanceV("Pid", _)) => Perform("Actor", "actorRefAddress", pid :: Nil)
      case _ => throw InterpretError("actorRefAddress(ref): Option[String]")
    })
    g("actorRefIsLocal") = Value.NativeFnV("actorRefIsLocal", {
      case List(pid @ Value.InstanceV("Pid", _)) => Perform("Actor", "actorRefIsLocal", pid :: Nil)
      case _ => throw InterpretError("actorRefIsLocal(ref): Boolean")
    })
    g("actorRefTryLocal") = Value.NativeFnV("actorRefTryLocal", {
      case List(pid @ Value.InstanceV("Pid", _)) => Perform("Actor", "actorRefTryLocal", pid :: Nil)
      case _ => throw InterpretError("actorRefTryLocal(ref): Option[Any]")
    })
    g("actorRefPublish") = Value.NativeFnV("actorRefPublish", {
      case List(pid @ Value.InstanceV("Pid", _), Value.StringV(name)) =>
        Perform("Actor", "actorRefPublish", pid :: Value.StringV(name) :: Nil)
      case _ => throw InterpretError("actorRefPublish(ref, name): ActorRef[M]")
    })
    g("registerBehavior") = Value.NativeFnV("registerBehavior", {
      case List(Value.StringV(name), behavior) =>
        Perform("Actor", "registerBehavior", Value.StringV(name) :: behavior :: Nil)
      case _ => throw InterpretError("registerBehavior(name, behavior): Unit")
    })
    g("spawnRemote") = Value.NativeFnV("spawnRemote", {
      case List(Value.StringV(nodeId), descriptor, arg) =>
        Perform("Actor", "spawnRemote", Value.StringV(nodeId) :: descriptor :: arg :: Nil)
      case _ => throw InterpretError("spawnRemote(nodeId, behavior, args): ActorRef[M]")
    })
    g("exit") = Value.NativeFnV("exit", {
      case List(pid @ Value.InstanceV("Pid", _), reason) =>
        Perform("Actor", "exit", pid :: reason :: Nil)
      case _ => throw InterpretError("exit(pid, reason)")
    })

    // ── Phase 2 — supervision ─────────────────────────────────────────────
    g("link") = Value.NativeFnV("link", {
      case List(pid @ Value.InstanceV("Pid", _)) => Perform("Actor", "link", pid :: Nil)
      case _ => throw InterpretError("link(pid): Unit")
    })
    g("monitor") = Value.NativeFnV("monitor", {
      case List(pid @ Value.InstanceV("Pid", _)) => Perform("Actor", "monitor", pid :: Nil)
      case _ => throw InterpretError("monitor(pid): MonitorRef")
    })
    g("demonitor") = Value.NativeFnV("demonitor", {
      case List(ref @ Value.IntV(_)) => Perform("Actor", "demonitor", ref :: Nil)
      case _ => throw InterpretError("demonitor(ref): Unit")
    })
    g("trapExit") = Value.NativeFnV("trapExit", {
      case List(b @ Value.BoolV(_)) => Perform("Actor", "trapExit", b :: Nil)
      case _ => throw InterpretError("trapExit(on: Boolean): Unit")
    })

    // ── Phase 3 — distributed node primitives ─────────────────────────────
    g("startNode") = Value.NativeFnV("startNode", {
      case List(Value.StringV(nodeId)) => Perform("Actor", "startNode", Value.StringV(nodeId) :: Nil)
      case List(Value.StringV(nodeId), Value.StringV(url)) =>
        Perform("Actor", "startNode", Value.StringV(nodeId) :: Value.StringV(url) :: Nil)
      case _ => throw InterpretError("startNode(nodeId: String, url: String = \"\"): Unit")
    })
    g("connectNode") = Value.NativeFnV("connectNode", {
      case List(Value.StringV(url)) => Perform("Actor", "connectNode", Value.StringV(url) :: Nil)
      case List(Value.StringV(url), Value.StringV(token)) =>
        Perform("Actor", "connectNode", Value.StringV(url) :: Value.StringV(token) :: Nil)
      case _ => throw InterpretError("connectNode(url: String, token: String = \"\"): Unit")
    })
    g("register") = Value.NativeFnV("register", {
      case List(Value.StringV(name), pid @ Value.InstanceV("Pid", _)) =>
        Perform("Actor", "register", Value.StringV(name) :: pid :: Nil)
      case _ => throw InterpretError("register(name: String, pid: Pid): Unit")
    })
    g("whereis") = Value.NativeFnV("whereis", {
      case List(Value.StringV(name)) => Perform("Actor", "whereis", Value.StringV(name) :: Nil)
      case _ => throw InterpretError("whereis(name: String): Option[Pid]")
    })
    g("joinCluster") = Value.NativeFnV("joinCluster", {
      case List(seeds) => Perform("Actor", "joinCluster", seeds :: Value.EmptyStr :: Nil)
      case List(seeds, Value.StringV(tok)) => Perform("Actor", "joinCluster", seeds :: Value.StringV(tok) :: Nil)
      case _ => throw InterpretError("joinCluster(seeds: List[String], token: String = \"\"): Unit")
    })
    g("globalRegister") = Value.NativeFnV("globalRegister", {
      case List(Value.StringV(name), pid @ Value.InstanceV("Pid", _)) =>
        Perform("Actor", "globalRegister", Value.StringV(name) :: pid :: Nil)
      case _ => throw InterpretError("globalRegister(name: String, pid: Pid): Unit")
    })
    g("globalWhereis") = Value.NativeFnV("globalWhereis", {
      case List(Value.StringV(name)) => Perform("Actor", "globalWhereis", Value.StringV(name) :: Nil)
      case _ => throw InterpretError("globalWhereis(name: String): Option[Pid]")
    })

    // ── v1.23 — cluster visibility ────────────────────────────────────────
    g("clusterMembers") = Value.NativeFnV("clusterMembers", {
      case Nil => Perform("Actor", "clusterMembers", Nil)
      case _ => throw InterpretError("clusterMembers(): List[String]")
    })
    g("subscribeClusterEvents") = Value.NativeFnV("subscribeClusterEvents", {
      case Nil => Perform("Actor", "subscribeClusterEvents", Nil)
      case _ => throw InterpretError("subscribeClusterEvents(): Unit")
    })

    // ── v1.23 — phi-accrual failure detector ──────────────────────────────
    g("phiOf") = Value.NativeFnV("phiOf", {
      case List(Value.StringV(nid)) => Perform("Actor", "phiOf", Value.StringV(nid) :: Nil)
      case _ => throw InterpretError("phiOf(nodeId: String): Double")
    })
    g("isSuspect") = Value.NativeFnV("isSuspect", {
      case List(Value.StringV(nid)) =>
        Perform("Actor", "isSuspect", Value.StringV(nid) :: Value.doubleV(8.0) :: Nil)
      case List(Value.StringV(nid), Value.DoubleV(thr)) =>
        Perform("Actor", "isSuspect", Value.StringV(nid) :: Value.doubleV(thr) :: Nil)
      case List(Value.StringV(nid), Value.IntV(thr)) =>
        Perform("Actor", "isSuspect", Value.StringV(nid) :: Value.doubleV(thr.toDouble) :: Nil)
      case _ => throw InterpretError("isSuspect(nodeId: String, threshold: Double = 8.0): Boolean")
    })
    g("selfNode") = Value.NativeFnV("selfNode", {
      case Nil => Perform("Actor", "selfNode", Nil)
      case _   => throw InterpretError("selfNode(): String")
    })
    g("clusterHealth") = Value.NativeFnV("clusterHealth", {
      case Nil => Perform("Actor", "clusterHealth", Nil)
      case _   => throw InterpretError("clusterHealth(): Map[String, Double]")
    })
    g("broadcastHealth") = Value.NativeFnV("broadcastHealth", {
      case Nil => Perform("Actor", "broadcastHealth", Nil)
      case _   => throw InterpretError("broadcastHealth(): Unit")
    })
    g("clusterIsDown") = Value.NativeFnV("clusterIsDown", {
      case List(Value.StringV(nid)) =>
        Perform("Actor", "clusterIsDown", Value.StringV(nid) :: Value.doubleV(8.0) :: Nil)
      case List(Value.StringV(nid), Value.DoubleV(thr)) =>
        Perform("Actor", "clusterIsDown", Value.StringV(nid) :: Value.doubleV(thr) :: Nil)
      case List(Value.StringV(nid), Value.IntV(thr)) =>
        Perform("Actor", "clusterIsDown", Value.StringV(nid) :: Value.doubleV(thr.toDouble) :: Nil)
      case _ => throw InterpretError("clusterIsDown(nodeId: String, threshold: Double = 8.0): Boolean")
    })

    // ── v1.23 — leader election ───────────────────────────────────────────
    g("electLeader") = Value.NativeFnV("electLeader", {
      case Nil => Perform("Actor", "electLeader", Nil)
      case _   => throw InterpretError("electLeader(): Unit")
    })
    g("currentLeader") = Value.NativeFnV("currentLeader", {
      case Nil => Perform("Actor", "currentLeader", Nil)
      case _   => throw InterpretError("currentLeader(): String")
    })
    g("subscribeLeaderEvents") = Value.NativeFnV("subscribeLeaderEvents", {
      case Nil => Perform("Actor", "subscribeLeaderEvents", Nil)
      case _   => throw InterpretError("subscribeLeaderEvents(): Unit")
    })
    g("setAutoReelect") = Value.NativeFnV("setAutoReelect", {
      case List(Value.BoolV(b)) => Perform("Actor", "setAutoReelect", Value.BoolV(b) :: Nil)
      case _ => throw InterpretError("setAutoReelect(enabled: Boolean): Unit")
    })
    g("useRaftLeaderElection") = Value.NativeFnV("useRaftLeaderElection", {
      case Nil => Perform("Actor", "useRaftLeaderElection", Nil)
      case _   => throw InterpretError("useRaftLeaderElection(): Unit")
    })
    g("useExternalCoordinator") = Value.NativeFnV("useExternalCoordinator", {
      case List(acq, ren, rel, hol) =>
        Perform("Actor", "useExternalCoordinator", acq :: ren :: rel :: hol :: Nil)
      case _ => throw InterpretError(
        "useExternalCoordinator(acquireLease, renewLease, releaseLease, currentHolder)")
    })
    g("leaderProtocol") = Value.NativeFnV("leaderProtocol", {
      case Nil => Perform("Actor", "leaderProtocol", Nil)
      case _   => throw InterpretError("leaderProtocol(): String")
    })
    g("leaderHistory") = Value.NativeFnV("leaderHistory", {
      case Nil => Perform("Actor", "leaderHistory", Nil)
      case _   => throw InterpretError("leaderHistory(): List[(Long, String, Long)]")
    })
    g("setClusterAuthToken") = Value.NativeFnV("setClusterAuthToken", {
      case List(Value.StringV(t)) =>
        Perform("Actor", "setClusterAuthToken", Value.StringV(t) :: Nil)
      case _ => throw InterpretError("setClusterAuthToken(token: String): Unit")
    })
    g("rotateClusterToken") = Value.NativeFnV("rotateClusterToken", {
      case List(Value.StringV(t))                      => Perform("Actor", "rotateClusterToken", Value.StringV(t) :: Value.intV(30_000) :: Nil)
      case List(Value.StringV(t), Value.IntV(ms))      => Perform("Actor", "rotateClusterToken", Value.StringV(t) :: Value.intV(ms) :: Nil)
      case _ => throw InterpretError("rotateClusterToken(newToken: String, overlapMs: Int = 30_000): Unit")
    })
    def seedResolver(kind: String, urls: List[Value], serviceName: String, namespace: String, port: Long, scheme: String): Value =
      Value.InstanceV("SeedResolver", Map(
        "kind" -> Value.StringV(kind),
        "urls" -> Value.ListV(urls),
        "serviceName" -> Value.StringV(serviceName),
        "namespace" -> Value.StringV(namespace),
        "port" -> Value.intV(port),
        "scheme" -> Value.StringV(scheme)
      ))
    g("SeedResolver") = Value.InstanceV("SeedResolver$", Map(
      "staticList" -> Value.NativeFnV("SeedResolver.staticList", {
        case List(Value.ListV(urls)) =>
          Computation.Pure(seedResolver("static", urls, "", "default", 9100, "ws"))
        case _ => throw InterpretError("SeedResolver.staticList(urls: List[String])")
      }),
      "dnsSrv" -> Value.NativeFnV("SeedResolver.dnsSrv", {
        case List(Value.StringV(serviceName)) =>
          Computation.Pure(seedResolver("dnsSrv", Nil, serviceName, "default", 9100, "ws"))
        case List(Value.StringV(serviceName), Value.IntV(port)) =>
          Computation.Pure(seedResolver("dnsSrv", Nil, serviceName, "default", port, "ws"))
        case List(Value.StringV(serviceName), Value.IntV(port), Value.StringV(scheme)) =>
          Computation.Pure(seedResolver("dnsSrv", Nil, serviceName, "default", port, scheme))
        case _ => throw InterpretError("SeedResolver.dnsSrv(serviceName, port = 9100, scheme = \"ws\")")
      }),
      "k8sHeadlessService" -> Value.NativeFnV("SeedResolver.k8sHeadlessService", {
        case List(Value.StringV(serviceName)) =>
          Computation.Pure(seedResolver("k8sHeadlessService", Nil, serviceName, "default", 9100, "ws"))
        case List(Value.StringV(serviceName), Value.StringV(namespace)) =>
          Computation.Pure(seedResolver("k8sHeadlessService", Nil, serviceName, namespace, 9100, "ws"))
        case List(Value.StringV(serviceName), Value.StringV(namespace), Value.IntV(port)) =>
          Computation.Pure(seedResolver("k8sHeadlessService", Nil, serviceName, namespace, port, "ws"))
        case List(Value.StringV(serviceName), Value.StringV(namespace), Value.IntV(port), Value.StringV(scheme)) =>
          Computation.Pure(seedResolver("k8sHeadlessService", Nil, serviceName, namespace, port, scheme))
        case _ => throw InterpretError("SeedResolver.k8sHeadlessService(serviceName, namespace = \"default\", port = 9100, scheme = \"ws\")")
      }),
      "consulCatalog" -> Value.NativeFnV("SeedResolver.consulCatalog", {
        case List(Value.StringV(serviceName)) =>
          Computation.Pure(seedResolver("consulCatalog", Nil, serviceName, "default", 8500, "http"))
        case List(Value.StringV(serviceName), Value.StringV(consulAddr)) =>
          Computation.Pure(seedResolver("consulCatalog", Value.StringV(consulAddr) :: Nil, serviceName, "default", 8500, "http"))
        case _ => throw InterpretError("SeedResolver.consulCatalog(serviceName, consulAddr = \"localhost:8500\")")
      })
    ))
    g("clusterOf") = Value.NativeFnV("clusterOf", {
      case Nil => Perform("Actor", "clusterOf", Value.InstanceV("SeedResolver", Map(
        "kind" -> Value.StringV("static"),
        "urls" -> Value.EmptyList,
        "serviceName" -> Value.EmptyStr,
        "namespace" -> Value.StringV("default"),
        "port" -> Value.intV(9100),
        "scheme" -> Value.StringV("ws")
      )) :: Nil)
      case List(seedResolver @ Value.InstanceV("SeedResolver", _)) =>
        Perform("Actor", "clusterOf", seedResolver :: Nil)
      case _ => throw InterpretError("clusterOf(seedResolver: SeedResolver = SeedResolver.staticList(List()))")
    })
    g("resolveSeeds") = Value.NativeFnV("resolveSeeds", {
      case List(seedResolver @ Value.InstanceV("SeedResolver", _)) =>
        Perform("Actor", "resolveSeeds", seedResolver :: Nil)
      case _ => throw InterpretError("resolveSeeds(seedResolver: SeedResolver): List[String]")
    })
    g("codeIdentity") = Value.NativeFnV("codeIdentity", {
      case Nil => Perform("Actor", "codeIdentity", Nil)
      case _ => throw InterpretError("codeIdentity(): CodeIdentity")
    })
    g("assertCodeIdentity") = Value.NativeFnV("assertCodeIdentity", {
      case List(expected @ Value.InstanceV("CodeIdentity", _)) =>
        Perform("Actor", "assertCodeIdentity", expected :: Nil)
      case _ => throw InterpretError("assertCodeIdentity(expected: CodeIdentity): Unit")
    })

    // ── v1.23 — cluster-wide atomic counters ──────────────────────────────
    g("clusterAtomicGet") = Value.NativeFnV("clusterAtomicGet", {
      case List(Value.StringV(name)) =>
        Perform("Actor", "clusterAtomicGet", Value.StringV(name) :: Nil)
      case _ => throw InterpretError("clusterAtomicGet(name: String): Long")
    })
    g("clusterAtomicSet") = Value.NativeFnV("clusterAtomicSet", {
      case List(Value.StringV(name), Value.IntV(v)) =>
        Perform("Actor", "clusterAtomicSet", Value.StringV(name) :: Value.intV(v) :: Nil)
      case _ => throw InterpretError("clusterAtomicSet(name: String, value: Long): Long")
    })
    g("clusterAtomicAdd") = Value.NativeFnV("clusterAtomicAdd", {
      case List(Value.StringV(name), Value.IntV(d)) =>
        Perform("Actor", "clusterAtomicAdd", Value.StringV(name) :: Value.intV(d) :: Nil)
      case _ => throw InterpretError("clusterAtomicAdd(name: String, delta: Long): Long")
    })
    g("clusterAtomicCompareAndSet") = Value.NativeFnV("clusterAtomicCompareAndSet", {
      case List(Value.StringV(name), Value.IntV(expect), Value.IntV(update)) =>
        Perform("Actor", "clusterAtomicCompareAndSet",
          Value.StringV(name) :: Value.intV(expect) :: Value.intV(update) :: Nil)
      case _ => throw InterpretError(
        "clusterAtomicCompareAndSet(name: String, expect: Long, update: Long): Boolean")
    })

    // ── v1.23 — cluster-wide pub/sub ──────────────────────────────────────
    g("publish") = Value.NativeFnV("publish", {
      case List(Value.StringV(topic), msg) =>
        Perform("Actor", "publish", Value.StringV(topic) :: msg :: Nil)
      case _ => throw InterpretError("publish(topic: String, msg: Any): Unit")
    })
    g("subscribePublish") = Value.NativeFnV("subscribePublish", {
      case List(Value.StringV(topic)) =>
        Perform("Actor", "subscribePublish", Value.StringV(topic) :: Nil)
      case _ => throw InterpretError("subscribePublish(topic: String): Unit")
    })
    g("unsubscribePublish") = Value.NativeFnV("unsubscribePublish", {
      case List(Value.StringV(topic)) =>
        Perform("Actor", "unsubscribePublish", Value.StringV(topic) :: Nil)
      case _ => throw InterpretError("unsubscribePublish(topic: String): Unit")
    })
    g("setQuorumSize") = Value.NativeFnV("setQuorumSize", {
      case List(Value.IntV(n)) =>
        Perform("Actor", "setQuorumSize", Value.intV(n) :: Nil)
      case _ => throw InterpretError("setQuorumSize(n: Long): Unit")
    })
    g("setHeartbeatTimeout") = Value.NativeFnV("setHeartbeatTimeout", {
      case List(Value.IntV(iv), Value.IntV(dead)) =>
        Perform("Actor", "setHeartbeatTimeout",
          Value.intV(iv) :: Value.intV(dead) :: Nil)
      case _ => throw InterpretError(
        "setHeartbeatTimeout(intervalMs: Long, deadAfterMs: Long): Unit")
    })
    g("setReconnectPolicy") = Value.NativeFnV("setReconnectPolicy", {
      case List(Value.IntV(ini), Value.IntV(mx)) =>
        Perform("Actor", "setReconnectPolicy",
          Value.intV(ini) :: Value.intV(mx) :: Value.intV(0L) :: Nil)
      case List(Value.IntV(ini), Value.IntV(mx), Value.IntV(giveUp)) =>
        Perform("Actor", "setReconnectPolicy",
          Value.intV(ini) :: Value.intV(mx) :: Value.intV(giveUp) :: Nil)
      case _ => throw InterpretError(
        "setReconnectPolicy(initialMs: Long, maxMs: Long, giveUpAfterMs: Long = 0): Unit")
    })
    g("requestGossip") = Value.NativeFnV("requestGossip", {
      case Nil => Perform("Actor", "requestGossip", Nil)
      case _   => throw InterpretError("requestGossip(): Unit")
    })

    // ── v1.23 — cluster configuration ────────────────────────────────────
    g("clusterConfigSet") = Value.NativeFnV("clusterConfigSet", {
      case List(Value.StringV(k), Value.StringV(v)) =>
        Perform("Actor", "clusterConfigSet", Value.StringV(k) :: Value.StringV(v) :: Nil)
      case _ => throw InterpretError("clusterConfigSet(key: String, value: String): Unit")
    })
    g("clusterConfigGet") = Value.NativeFnV("clusterConfigGet", {
      case List(Value.StringV(k)) =>
        Perform("Actor", "clusterConfigGet", Value.StringV(k) :: Nil)
      case _ => throw InterpretError("clusterConfigGet(key: String): Option[String]")
    })
    g("clusterConfigKeys") = Value.NativeFnV("clusterConfigKeys", {
      case Nil => Perform("Actor", "clusterConfigKeys", Nil)
      case _   => throw InterpretError("clusterConfigKeys(): List[String]")
    })
    g("subscribeConfigEvents") = Value.NativeFnV("subscribeConfigEvents", {
      case Nil => Perform("Actor", "subscribeConfigEvents", Nil)
      case _   => throw InterpretError("subscribeConfigEvents(): Unit")
    })

    // ── v1.23 — drain / rolling-restart ──────────────────────────────────
    g("setDraining") = Value.NativeFnV("setDraining", {
      case List(Value.BoolV(b)) => Perform("Actor", "setDraining", Value.BoolV(b) :: Nil)
      case _ => throw InterpretError("setDraining(enabled: Boolean): Unit")
    })
    g("isDraining") = Value.NativeFnV("isDraining", {
      case Nil => Perform("Actor", "isDraining", Nil)
      case _   => throw InterpretError("isDraining(): Boolean")
    })
    g("drainingPeers") = Value.NativeFnV("drainingPeers", {
      case Nil => Perform("Actor", "drainingPeers", Nil)
      case _   => throw InterpretError("drainingPeers(): List[String]")
    })
    g("subscribeDrainEvents") = Value.NativeFnV("subscribeDrainEvents", {
      case Nil => Perform("Actor", "subscribeDrainEvents", Nil)
      case _   => throw InterpretError("subscribeDrainEvents(): Unit")
    })

    // ── v1.23 — cluster metrics aggregation ───────────────────────────────
    g("clusterMetricSet") = Value.NativeFnV("clusterMetricSet", {
      case List(Value.StringV(n), Value.DoubleV(v)) =>
        Perform("Actor", "clusterMetricSet", Value.StringV(n) :: Value.doubleV(v) :: Nil)
      case List(Value.StringV(n), Value.IntV(v)) =>
        Perform("Actor", "clusterMetricSet", Value.StringV(n) :: Value.doubleV(v.toDouble) :: Nil)
      case _ => throw InterpretError("clusterMetricSet(name: String, value: Double): Unit")
    })
    g("clusterMetricGet") = Value.NativeFnV("clusterMetricGet", {
      case List(Value.StringV(n)) =>
        Perform("Actor", "clusterMetricGet", Value.StringV(n) :: Nil)
      case _ => throw InterpretError("clusterMetricGet(name: String): Map[String, Double]")
    })
    g("clusterMetricSum") = Value.NativeFnV("clusterMetricSum", {
      case List(Value.StringV(n)) =>
        Perform("Actor", "clusterMetricSum", Value.StringV(n) :: Nil)
      case _ => throw InterpretError("clusterMetricSum(name: String): Double")
    })
    g("clusterMetricNames") = Value.NativeFnV("clusterMetricNames", {
      case Nil => Perform("Actor", "clusterMetricNames", Nil)
      case _   => throw InterpretError("clusterMetricNames(): List[String]")
    })
    g("subscribeMetricEvents") = Value.NativeFnV("subscribeMetricEvents", {
      case Nil => Perform("Actor", "subscribeMetricEvents", Nil)
      case _   => throw InterpretError("subscribeMetricEvents(): Unit")
    })

    // ── v1.63.8 — dynamic code shipping ops ──────────────────────────────
    g("shipWorker") = Value.NativeFnV("shipWorker", {
      case List(Value.StringV(wid), Value.StringV(zip64)) =>
        Perform("Actor", "shipWorker", Value.StringV(wid) :: Value.StringV(zip64) :: Nil)
      case _ => throw InterpretError("shipWorker(workerId: String, zipBase64: String): Unit")
    })
    g("unloadWorker") = Value.NativeFnV("unloadWorker", {
      case List(Value.StringV(wid)) =>
        Perform("Actor", "unloadWorker", Value.StringV(wid) :: Nil)
      case _ => throw InterpretError("unloadWorker(workerId: String): Unit")
    })
    g("rollbackWorker") = Value.NativeFnV("rollbackWorker", {
      case List(Value.StringV(wid)) =>
        Perform("Actor", "rollbackWorker", Value.StringV(wid) :: Nil)
      case _ => throw InterpretError("rollbackWorker(workerId: String): Unit")
    })
    g("workerStatus") = Value.NativeFnV("workerStatus", {
      case List(Value.StringV(wid)) =>
        Perform("Actor", "workerStatus", Value.StringV(wid) :: Nil)
      case _ => throw InterpretError("workerStatus(workerId: String): Option[Map[String, Any]]")
    })
    g("workerList") = Value.NativeFnV("workerList", {
      case Nil => Perform("Actor", "workerList", Nil)
      case _   => throw InterpretError("workerList(): List[String]")
    })

    // ── v1.6.x — scheduled sends ──────────────────────────────────────────
    g("sendAfter") = Value.NativeFnV("sendAfter", {
      case List(Value.IntV(delayMs), pid @ Value.InstanceV("Pid", _), msg) =>
        Perform("Actor", "sendAfter", Value.intV(delayMs) :: pid :: msg :: Nil)
      case _ => throw InterpretError("sendAfter(delayMs: Int, pid: Pid, msg: Any): TimerRef")
    })
    g("sendInterval") = Value.NativeFnV("sendInterval", {
      case List(Value.IntV(periodMs), pid @ Value.InstanceV("Pid", _), msg) =>
        Perform("Actor", "sendInterval", Value.intV(periodMs) :: pid :: msg :: Nil)
      case _ => throw InterpretError("sendInterval(periodMs: Int, pid: Pid, msg: Any): TimerRef")
    })
    g("cancelTimer") = Value.NativeFnV("cancelTimer", {
      case List(Value.IntV(ref)) => Perform("Actor", "cancelTimer", Value.intV(ref) :: Nil)
      case _ => throw InterpretError("cancelTimer(ref: TimerRef): Unit")
    })

    // ── v1.63.6 — actor groups + proxy ───────────────────────────────────
    g("actorGroupRouter") = Value.NativeFnV("actorGroupRouter", {
      case List(name, policy) => Perform("Actor", "actorGroupRouter", name :: policy :: Nil)
      case _ => throw InterpretError("actorGroupRouter(name: String, policy: RoutingPolicy): ActorGroup[M]")
    })
    g("actorGroupSharded") = Value.NativeFnV("actorGroupSharded", {
      case List(name, keyFn) => Perform("Actor", "actorGroupSharded", name :: keyFn :: Nil)
      case _ => throw InterpretError("actorGroupSharded(name: String, key: Any => String): ActorGroup[M]")
    })
    g("actorGroupRole") = Value.NativeFnV("actorGroupRole", {
      case List(roleName) => Perform("Actor", "actorGroupRole", roleName :: Nil)
      case _ => throw InterpretError("actorGroupRole(roleName: String): ActorGroup[M]")
    })
    g("actorGroupTell") = Value.NativeFnV("actorGroupTell", {
      case List(group, msg) => Perform("Actor", "actorGroupTell", group :: msg :: Nil)
      case _ => throw InterpretError("actorGroupTell(group: ActorGroup[M], msg: M): Unit")
    })
    g("actorGroupAdd") = Value.NativeFnV("actorGroupAdd", {
      case List(group, ref) => Perform("Actor", "actorGroupAdd", group :: ref :: Nil)
      case _ => throw InterpretError("actorGroupAdd(group: ActorGroup[M], ref: ActorRef[M]): Unit")
    })
    g("actorGroupRemove") = Value.NativeFnV("actorGroupRemove", {
      case List(group, ref) => Perform("Actor", "actorGroupRemove", group :: ref :: Nil)
      case _ => throw InterpretError("actorGroupRemove(group: ActorGroup[M], ref: ActorRef[M]): Unit")
    })
    g("actorGroupMembers") = Value.NativeFnV("actorGroupMembers", {
      case List(group) => Perform("Actor", "actorGroupMembers", group :: Nil)
      case _ => throw InterpretError("actorGroupMembers(group: ActorGroup[M]): List[ActorRef[M]]")
    })
    g("proxyActor") = Value.NativeFnV("proxyActor", {
      case List(ref) => Perform("Actor", "proxyActor", ref :: Nil)
      case _ => throw InterpretError("proxyActor(ref: ActorRef[M]): ActorRef[M]")
    })
    g("ActorGroup") = Value.InstanceV("ActorGroup$", Map(
      "router"  -> g("actorGroupRouter"),
      "sharded" -> g("actorGroupSharded"),
      "role"    -> g("actorGroupRole"),
    ))
