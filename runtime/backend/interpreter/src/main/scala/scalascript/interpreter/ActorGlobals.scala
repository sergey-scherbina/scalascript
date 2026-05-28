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
      case List(thunk) => Perform("Actor", "spawn", List(thunk))
      case _           => throw InterpretError("spawn(behavior: () => Unit)")
    })
    g("spawnBounded") = Value.NativeFnV("spawnBounded", {
      case List(Value.IntV(cap), overflow, thunk) =>
        val strategy = overflow match
          case Value.InstanceV(name, _) => name
          case _                        => "DropNewest"
        Perform("Actor", "spawnBounded", List(Value.intV(cap), Value.StringV(strategy), thunk))
      case _ => throw InterpretError("spawnBounded(capacity: Int, overflow: Overflow, behavior: () => Unit)")
    })
    g("processInfo") = Value.NativeFnV("processInfo", {
      case List(pid @ Value.InstanceV("Pid", _)) => Perform("Actor", "processInfo", List(pid))
      case _ => throw InterpretError("processInfo(pid)")
    })
    g("spawn_link") = Value.NativeFnV("spawn_link", {
      case List(thunk) => Perform("Actor", "spawnLink", List(thunk))
      case _           => throw InterpretError("spawn_link(behavior: () => Unit)")
    })
    g("self") = Value.NativeFnV("self", {
      case Nil => Perform("Actor", "self", Nil)
      case _   => throw InterpretError("self takes no arguments")
    })
    g("exit") = Value.NativeFnV("exit", {
      case List(pid @ Value.InstanceV("Pid", _), reason) =>
        Perform("Actor", "exit", List(pid, reason))
      case _ => throw InterpretError("exit(pid, reason)")
    })

    // ── Phase 2 — supervision ─────────────────────────────────────────────
    g("link") = Value.NativeFnV("link", {
      case List(pid @ Value.InstanceV("Pid", _)) => Perform("Actor", "link", List(pid))
      case _ => throw InterpretError("link(pid): Unit")
    })
    g("monitor") = Value.NativeFnV("monitor", {
      case List(pid @ Value.InstanceV("Pid", _)) => Perform("Actor", "monitor", List(pid))
      case _ => throw InterpretError("monitor(pid): MonitorRef")
    })
    g("demonitor") = Value.NativeFnV("demonitor", {
      case List(ref @ Value.IntV(_)) => Perform("Actor", "demonitor", List(ref))
      case _ => throw InterpretError("demonitor(ref): Unit")
    })
    g("trapExit") = Value.NativeFnV("trapExit", {
      case List(b @ Value.BoolV(_)) => Perform("Actor", "trapExit", List(b))
      case _ => throw InterpretError("trapExit(on: Boolean): Unit")
    })

    // ── Phase 3 — distributed node primitives ─────────────────────────────
    g("startNode") = Value.NativeFnV("startNode", {
      case List(Value.StringV(nodeId)) => Perform("Actor", "startNode", List(Value.StringV(nodeId)))
      case List(Value.StringV(nodeId), Value.StringV(url)) =>
        Perform("Actor", "startNode", List(Value.StringV(nodeId), Value.StringV(url)))
      case _ => throw InterpretError("startNode(nodeId: String, url: String = \"\"): Unit")
    })
    g("connectNode") = Value.NativeFnV("connectNode", {
      case List(Value.StringV(url)) => Perform("Actor", "connectNode", List(Value.StringV(url)))
      case List(Value.StringV(url), Value.StringV(token)) =>
        Perform("Actor", "connectNode", List(Value.StringV(url), Value.StringV(token)))
      case _ => throw InterpretError("connectNode(url: String, token: String = \"\"): Unit")
    })
    g("register") = Value.NativeFnV("register", {
      case List(Value.StringV(name), pid @ Value.InstanceV("Pid", _)) =>
        Perform("Actor", "register", List(Value.StringV(name), pid))
      case _ => throw InterpretError("register(name: String, pid: Pid): Unit")
    })
    g("whereis") = Value.NativeFnV("whereis", {
      case List(Value.StringV(name)) => Perform("Actor", "whereis", List(Value.StringV(name)))
      case _ => throw InterpretError("whereis(name: String): Option[Pid]")
    })
    g("joinCluster") = Value.NativeFnV("joinCluster", {
      case List(seeds) => Perform("Actor", "joinCluster", List(seeds, Value.StringV("")))
      case List(seeds, Value.StringV(tok)) => Perform("Actor", "joinCluster", List(seeds, Value.StringV(tok)))
      case _ => throw InterpretError("joinCluster(seeds: List[String], token: String = \"\"): Unit")
    })
    g("globalRegister") = Value.NativeFnV("globalRegister", {
      case List(Value.StringV(name), pid @ Value.InstanceV("Pid", _)) =>
        Perform("Actor", "globalRegister", List(Value.StringV(name), pid))
      case _ => throw InterpretError("globalRegister(name: String, pid: Pid): Unit")
    })
    g("globalWhereis") = Value.NativeFnV("globalWhereis", {
      case List(Value.StringV(name)) => Perform("Actor", "globalWhereis", List(Value.StringV(name)))
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
      case List(Value.StringV(nid)) => Perform("Actor", "phiOf", List(Value.StringV(nid)))
      case _ => throw InterpretError("phiOf(nodeId: String): Double")
    })
    g("isSuspect") = Value.NativeFnV("isSuspect", {
      case List(Value.StringV(nid)) =>
        Perform("Actor", "isSuspect", List(Value.StringV(nid), Value.doubleV(8.0)))
      case List(Value.StringV(nid), Value.DoubleV(thr)) =>
        Perform("Actor", "isSuspect", List(Value.StringV(nid), Value.doubleV(thr)))
      case List(Value.StringV(nid), Value.IntV(thr)) =>
        Perform("Actor", "isSuspect", List(Value.StringV(nid), Value.doubleV(thr.toDouble)))
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
        Perform("Actor", "clusterIsDown", List(Value.StringV(nid), Value.doubleV(8.0)))
      case List(Value.StringV(nid), Value.DoubleV(thr)) =>
        Perform("Actor", "clusterIsDown", List(Value.StringV(nid), Value.doubleV(thr)))
      case List(Value.StringV(nid), Value.IntV(thr)) =>
        Perform("Actor", "clusterIsDown", List(Value.StringV(nid), Value.doubleV(thr.toDouble)))
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
      case List(Value.BoolV(b)) => Perform("Actor", "setAutoReelect", List(Value.BoolV(b)))
      case _ => throw InterpretError("setAutoReelect(enabled: Boolean): Unit")
    })
    g("useRaftLeaderElection") = Value.NativeFnV("useRaftLeaderElection", {
      case Nil => Perform("Actor", "useRaftLeaderElection", Nil)
      case _   => throw InterpretError("useRaftLeaderElection(): Unit")
    })
    g("useExternalCoordinator") = Value.NativeFnV("useExternalCoordinator", {
      case List(acq, ren, rel, hol) =>
        Perform("Actor", "useExternalCoordinator", List(acq, ren, rel, hol))
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
        Perform("Actor", "setClusterAuthToken", List(Value.StringV(t)))
      case _ => throw InterpretError("setClusterAuthToken(token: String): Unit")
    })

    // ── v1.23 — cluster-wide atomic counters ──────────────────────────────
    g("clusterAtomicGet") = Value.NativeFnV("clusterAtomicGet", {
      case List(Value.StringV(name)) =>
        Perform("Actor", "clusterAtomicGet", List(Value.StringV(name)))
      case _ => throw InterpretError("clusterAtomicGet(name: String): Long")
    })
    g("clusterAtomicSet") = Value.NativeFnV("clusterAtomicSet", {
      case List(Value.StringV(name), Value.IntV(v)) =>
        Perform("Actor", "clusterAtomicSet", List(Value.StringV(name), Value.intV(v)))
      case _ => throw InterpretError("clusterAtomicSet(name: String, value: Long): Long")
    })
    g("clusterAtomicAdd") = Value.NativeFnV("clusterAtomicAdd", {
      case List(Value.StringV(name), Value.IntV(d)) =>
        Perform("Actor", "clusterAtomicAdd", List(Value.StringV(name), Value.intV(d)))
      case _ => throw InterpretError("clusterAtomicAdd(name: String, delta: Long): Long")
    })
    g("clusterAtomicCompareAndSet") = Value.NativeFnV("clusterAtomicCompareAndSet", {
      case List(Value.StringV(name), Value.IntV(expect), Value.IntV(update)) =>
        Perform("Actor", "clusterAtomicCompareAndSet",
          List(Value.StringV(name), Value.intV(expect), Value.intV(update)))
      case _ => throw InterpretError(
        "clusterAtomicCompareAndSet(name: String, expect: Long, update: Long): Boolean")
    })

    // ── v1.23 — cluster-wide pub/sub ──────────────────────────────────────
    g("publish") = Value.NativeFnV("publish", {
      case List(Value.StringV(topic), msg) =>
        Perform("Actor", "publish", List(Value.StringV(topic), msg))
      case _ => throw InterpretError("publish(topic: String, msg: Any): Unit")
    })
    g("subscribePublish") = Value.NativeFnV("subscribePublish", {
      case List(Value.StringV(topic)) =>
        Perform("Actor", "subscribePublish", List(Value.StringV(topic)))
      case _ => throw InterpretError("subscribePublish(topic: String): Unit")
    })
    g("unsubscribePublish") = Value.NativeFnV("unsubscribePublish", {
      case List(Value.StringV(topic)) =>
        Perform("Actor", "unsubscribePublish", List(Value.StringV(topic)))
      case _ => throw InterpretError("unsubscribePublish(topic: String): Unit")
    })
    g("setQuorumSize") = Value.NativeFnV("setQuorumSize", {
      case List(Value.IntV(n)) =>
        Perform("Actor", "setQuorumSize", List(Value.intV(n)))
      case _ => throw InterpretError("setQuorumSize(n: Long): Unit")
    })
    g("setHeartbeatTimeout") = Value.NativeFnV("setHeartbeatTimeout", {
      case List(Value.IntV(iv), Value.IntV(dead)) =>
        Perform("Actor", "setHeartbeatTimeout",
          List(Value.intV(iv), Value.intV(dead)))
      case _ => throw InterpretError(
        "setHeartbeatTimeout(intervalMs: Long, deadAfterMs: Long): Unit")
    })
    g("setReconnectPolicy") = Value.NativeFnV("setReconnectPolicy", {
      case List(Value.IntV(ini), Value.IntV(mx)) =>
        Perform("Actor", "setReconnectPolicy",
          List(Value.intV(ini), Value.intV(mx), Value.intV(0L)))
      case List(Value.IntV(ini), Value.IntV(mx), Value.IntV(giveUp)) =>
        Perform("Actor", "setReconnectPolicy",
          List(Value.intV(ini), Value.intV(mx), Value.intV(giveUp)))
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
        Perform("Actor", "clusterConfigSet", List(Value.StringV(k), Value.StringV(v)))
      case _ => throw InterpretError("clusterConfigSet(key: String, value: String): Unit")
    })
    g("clusterConfigGet") = Value.NativeFnV("clusterConfigGet", {
      case List(Value.StringV(k)) =>
        Perform("Actor", "clusterConfigGet", List(Value.StringV(k)))
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
      case List(Value.BoolV(b)) => Perform("Actor", "setDraining", List(Value.BoolV(b)))
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
        Perform("Actor", "clusterMetricSet", List(Value.StringV(n), Value.doubleV(v)))
      case List(Value.StringV(n), Value.IntV(v)) =>
        Perform("Actor", "clusterMetricSet", List(Value.StringV(n), Value.doubleV(v.toDouble)))
      case _ => throw InterpretError("clusterMetricSet(name: String, value: Double): Unit")
    })
    g("clusterMetricGet") = Value.NativeFnV("clusterMetricGet", {
      case List(Value.StringV(n)) =>
        Perform("Actor", "clusterMetricGet", List(Value.StringV(n)))
      case _ => throw InterpretError("clusterMetricGet(name: String): Map[String, Double]")
    })
    g("clusterMetricSum") = Value.NativeFnV("clusterMetricSum", {
      case List(Value.StringV(n)) =>
        Perform("Actor", "clusterMetricSum", List(Value.StringV(n)))
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

    // ── v1.6.x — scheduled sends ──────────────────────────────────────────
    g("sendAfter") = Value.NativeFnV("sendAfter", {
      case List(Value.IntV(delayMs), pid @ Value.InstanceV("Pid", _), msg) =>
        Perform("Actor", "sendAfter", List(Value.intV(delayMs), pid, msg))
      case _ => throw InterpretError("sendAfter(delayMs: Int, pid: Pid, msg: Any): TimerRef")
    })
    g("sendInterval") = Value.NativeFnV("sendInterval", {
      case List(Value.IntV(periodMs), pid @ Value.InstanceV("Pid", _), msg) =>
        Perform("Actor", "sendInterval", List(Value.intV(periodMs), pid, msg))
      case _ => throw InterpretError("sendInterval(periodMs: Int, pid: Pid, msg: Any): TimerRef")
    })
    g("cancelTimer") = Value.NativeFnV("cancelTimer", {
      case List(Value.IntV(ref)) => Perform("Actor", "cancelTimer", List(Value.intV(ref)))
      case _ => throw InterpretError("cancelTimer(ref: TimerRef): Unit")
    })
