package scalascript.interpreter

import scala.collection.mutable
import scala.meta.*
import Computation.{Pure, Perform, FlatMap}

/** Actor/cluster runtime state and cooperative scheduler, extracted from
 *  Interpreter.scala to keep that file manageable.  This trait uses a
 *  self-type (`this: Interpreter =>`) so every Interpreter member is
 *  directly accessible without any forwarding. */
private[interpreter] trait ActorInterp:
  this: Interpreter =>

  // ── v1.6 Actors — Phase 1 + Phase 2 runtime state ───────────────
  // Each `runActors { ... }` installs a fresh ActorRuntime for its
  // duration so nested invocations don't share state.
  private class ActorRuntime:
    // v1.9.x: LinkedBlockingQueue — same infrastructure as coroutines, thread-safe.
    val mailboxes = mutable.LongMap.empty[java.util.concurrent.LinkedBlockingQueue[Value]]
    // (cases, env, continuation, optional wall-clock deadline, wrap-in-some?)
    // `wrapSome` is true for timeout-receive: matched body's value is wrapped
    // in Some(...) before being fed to the continuation so a single `receive`
    // expression yields Option[A].
    val blocked = mutable.LongMap.empty[
      (List[Case], Env, Value => Computation, Option[Long], Boolean)
    ]
    // Computation to run next when this actor is dispatched
    val pending = mutable.LongMap.empty[Computation]
    val ready   = mutable.Queue.empty[Long]
    var nextId  = 0L
    var currentId = -1L
    // Phase 2 — supervision
    // links(id) = set of actor IDs bidirectionally linked to `id`
    val links      = mutable.LongMap.empty[mutable.Set[Long]]
    // monitors(watchedId) = Map(monitorRef → observerId)
    val monitors   = mutable.LongMap.empty[mutable.Map[Long, Long]]
    // trapExit(id) = true  →  Exit signals arrive as messages, not crashes
    val trapExit   = mutable.LongMap.empty[Boolean]
    var nextMonRef = 0L
    // v1.6.x scheduled sends — timerId → (fireAt, periodMs, targetId, msg)
    val timers     = mutable.LongMap.empty[(Long, Option[Long], Long, Value)]
    var nextTimerId = 0L
    // v1.6.x bounded mailboxes
    val mailboxCaps     = mutable.LongMap.empty[Int]    // capacity per bounded actor
    val mailboxOverflow = mutable.LongMap.empty[String] // "Block"|"DropOldest"|"DropNewest"|"Fail"
    // blocked senders: targetId → Queue[(senderId, msg, senderContinuation)]
    val blockedSends = mutable.LongMap.empty[mutable.Queue[(Long, Value, Value => Computation)]]
  private var actorRt: ActorRuntime = null

  // ── Phase 3 — distributed node state ────────────────────────────────
  // "" means this process has not called startNode and is local-only.
  @volatile private[interpreter] var localNodeId: String = ""
  @volatile private var localNodeUrl: String = ""   // set by startNode(nodeId, url); shared in peers_resp
  // join-cluster mode: when true, each new handshake auto-requests peer list
  @volatile private var joinMode:  Boolean = false
  @volatile private var joinToken: String  = ""
  // v1.23 — flip via -Dssc.cluster.debug=1 (or env SSC_CLUSTER_DEBUG=1) to
  // get per-link connect/handshake traces.  Disabled by default; intended
  // for multi-node integration tests and field debugging.
  private val clusterDebug: Boolean =
    sys.props.get("ssc.cluster.debug").exists(_.nonEmpty) ||
    sys.env.get("SSC_CLUSTER_DEBUG").exists(_.nonEmpty)
  // nodeId → URL we dialled (for gossip in peers_resp)
  private val peerUrls =
    new java.util.concurrent.ConcurrentHashMap[String, String]()
  // nodeId → send-function (delivers serialized JSON envelope to peer)
  private[interpreter] val peerChannels =
    new java.util.concurrent.ConcurrentHashMap[String, String => Unit]()
  // Thread-safe queue for messages arriving from remote nodes on WS threads.
  private val remoteInbox  =
    new java.util.concurrent.ConcurrentLinkedQueue[(Long, Value)]()
  // Reference to the scheduler thread so WS threads can interrupt it.
  @volatile private var schedulerThread: Thread = null
  // Per-node name → localId registry (ConcurrentHashMap default 0 means absent).
  private val nodeRegistry =
    new java.util.concurrent.ConcurrentHashMap[String, Long]()
  // Cluster-wide name → Pid registry, populated by globalRegister broadcasts.
  private val globalRegistry =
    new java.util.concurrent.ConcurrentHashMap[String, Value]()
  // Heartbeat: last pong timestamp per peer (epoch ms); initialised to connect time.
  private val peerLastPong =
    new java.util.concurrent.ConcurrentHashMap[String, Long]()
  // WS threads post dead nodeIds here; scheduler drains and fires EXIT/Down.
  private val nodeDownQueue =
    new java.util.concurrent.ConcurrentLinkedQueue[String]()
  // v1.23 — cluster visibility: actor localIds subscribed to NodeJoined/NodeLeft events.
  private val clusterEventSubs =
    new java.util.concurrent.CopyOnWriteArrayList[java.lang.Long]()
  // WS threads post NodeJoined/NodeLeft Values; scheduler drains and delivers to subs.
  private val clusterEventQueue =
    new java.util.concurrent.ConcurrentLinkedQueue[Value]()
  // v1.23 — Phi-accrual failure detector: sliding window of inter-pong intervals
  // (epoch ms deltas) per peer.  Bounded to PhiHistMax samples per peer; older
  // samples are discarded once the window is full.  Cleared on disconnect.
  private val PhiHistMax = 100
  private val peerPongHist =
    new java.util.concurrent.ConcurrentHashMap[String, java.util.concurrent.ConcurrentLinkedDeque[java.lang.Long]]()
  // v1.23 — cluster-wide FD: per-peer view of every other peer's phi.
  //   peerPhiViews(fromNodeId)(targetNodeId) = phi at the time `fromNodeId`
  //   last broadcast its health vector.  Cleared on disconnect.
  private val peerPhiViews =
    new java.util.concurrent.ConcurrentHashMap[String, java.util.concurrent.ConcurrentHashMap[String, java.lang.Double]]()
  // v1.23 — leader election (Bully) state.
  private[interpreter] val currentLeader =
    new java.util.concurrent.atomic.AtomicReference[String]("")
  @volatile private var electionInProgress: Boolean = false
  @volatile private var electionStartedAt:  Long    = 0L
  @volatile private var gotAliveResponse:   Boolean = false
  private val ElectionTimeoutMs: Long = 2000L
  // v1.23 — quorum-aware Bully.  When > 0, a node only self-claims
  // leadership if `peerChannels.size + 1 >= quorumSize`.  Set to
  // N/2+1 of the total expected cluster size to prevent split-brain
  // — each side of a network partition with fewer than `quorumSize`
  // nodes will decline to elect a leader, leaving the cluster
  // leaderless until the partition heals.  0 (default) = no quorum.
  @volatile private var quorumSize: Long = 0L
  // v1.23 — shared-secret Bearer token for /_ssc-cluster/* endpoints.
  // Default reads `SSC_CLUSTER_TOKEN` env at construction; runtime
  // override via the `setClusterAuthToken` intrinsic.  Empty string ⇒
  // endpoints are open (backwards compatible).
  @volatile private[interpreter] var clusterAuthToken: String =
    sys.env.getOrElse("SSC_CLUSTER_TOKEN", "")
  // v1.23 — auto-reconnect: exponential-backoff retry per peer URL after a
  // disconnect.  initial/max both 0 ⇒ disabled (default).  giveUpAfterMs
  // bounds the total wall-clock retry budget — 0 ⇒ no cap (retry forever).
  @volatile private var reconnectInitialMs: Long = 0L
  @volatile private var reconnectMaxMs:     Long = 0L
  @volatile private var reconnectGiveUpMs:  Long = 0L
  // v1.23 — per-link heartbeat cadence (ping interval) and detection
  // threshold (max gap from last pong before we treat the link as dead).
  // Defaults match the pre-v1.23 hardcoded values; `setHeartbeatTimeout`
  // tunes them for low-latency clusters or tests where 40 s is too slow.
  @volatile private var peerHeartbeatIntervalMs: Long = 30_000L
  @volatile private var peerHeartbeatDeadAfterMs: Long = 40_000L
  // v1.23 — cluster configuration distribution.  LWW per key by (ts, origin).
  private val clusterConfig =
    new java.util.concurrent.ConcurrentHashMap[String, (String, Long, String)]()
  private val configEventSubs =
    new java.util.concurrent.CopyOnWriteArrayList[java.lang.Long]()
  private val configEventQueue =
    new java.util.concurrent.ConcurrentLinkedQueue[Value]()
  private def enqueueConfigEvent(key: String, value: String): Unit =
    val ts = System.currentTimeMillis()
    recordEventLog(s"""{"ts":$ts,"type":"ConfigChanged","key":${jsonStr(key)},"value":${jsonStr(value)}}""")
    if !configEventSubs.isEmpty then
      val ev = Value.InstanceV("ConfigChanged",
        Map("key" -> Value.StringV(key), "value" -> Value.StringV(value)))
      configEventQueue.offer(ev)
      val t = schedulerThread; if t != null then t.interrupt()
  private def applyConfigUpdate(key: String, value: String, ts: Long, origin: String): Boolean =
    val prev = clusterConfig.get(key)
    val accept =
      prev == null || ts > prev._2 ||
      (ts == prev._2 && origin > prev._3)
    if accept then
      clusterConfig.put(key, (value, ts, origin))
      enqueueConfigEvent(key, value)
    accept
  // Snapshot every locally-known config entry to a single peer.  Called on
  // every successful handshake so late-joining nodes pick up entries set
  // before they joined (LWW on the receiver protects existing values).
  private def sendConfigSnapshot(targetSend: String => Unit): Unit =
    clusterConfig.forEach { (key, tuple) =>
      val payload =
        s"""{"t":"config_set","key":${jsonStr(key)},"value":${jsonStr(tuple._1)},""" +
        s""""ts":${tuple._2},"origin":${jsonStr(tuple._3)}}"""
      try targetSend(payload) catch case _: Throwable => ()
    }
  // v1.23 — cluster-wide atomic counters.  Each entry is
  // (currentValue, ts, origin) — same LWW shape as clusterConfig.
  // ON THIS NODE every operation is atomic (AtomicLong primitives);
  // ACROSS NODES it's eventually consistent — concurrent updates on
  // different nodes resolve via (ts, origin) tie-break.  For strict
  // cluster-wide CAS use an external coordinator (Etcd / Consul).
  private val clusterAtomics =
    new java.util.concurrent.ConcurrentHashMap[String,
      (java.util.concurrent.atomic.AtomicLong, java.util.concurrent.atomic.AtomicLong, String)]()
  /** LWW receiver — accept iff incoming `ts` is strictly newer than
   *  what we have (or same ts with lex-greater origin).  When the
   *  current value is already `value` the update is still applied
   *  (it doesn't mutate but bumps the stored ts).  Returns true iff
   *  the local value changed. */
  private def applyAtomicUpdate(name: String, value: Long, ts: Long, origin: String): Boolean =
    var changed = false
    clusterAtomics.compute(name, (_, prev) =>
      if prev == null then
        changed = true
        ( new java.util.concurrent.atomic.AtomicLong(value)
        , new java.util.concurrent.atomic.AtomicLong(ts)
        , origin
        )
      else
        val (cur, prevTs, prevOrigin) = prev
        val pTs = prevTs.get()
        if ts > pTs || (ts == pTs && origin > prevOrigin) then
          if cur.get() != value then changed = true
          cur.set(value)
          prevTs.set(ts)
          (cur, prevTs, origin)
        else
          prev
    )
    changed
  /** Snapshot every atomic to a single peer on handshake. */
  private def sendAtomicSnapshot(targetSend: String => Unit): Unit =
    clusterAtomics.forEach { (name, tuple) =>
      val payload =
        s"""{"t":"atom_set","name":${jsonStr(name)},"value":${tuple._1.get()},""" +
        s""""ts":${tuple._2.get()},"origin":${jsonStr(tuple._3)}}"""
      try targetSend(payload) catch case _: Throwable => ()
    }
  private val leaderEventSubs =
    new java.util.concurrent.CopyOnWriteArrayList[java.lang.Long]()
  private val leaderEventQueue =
    new java.util.concurrent.ConcurrentLinkedQueue[Value]()
  @volatile private var autoReelect: Boolean = false
  // v1.23 — protocol dispatch (cluster-raft.md §6).  "bully" today;
  //   Phase 3a flips to "raft", Phase 3b flips to "coord".
  private[interpreter] val leaderProtocolRef =
    new java.util.concurrent.atomic.AtomicReference[String]("bully")
  @scala.annotation.unused
  @volatile private var leaderCoordinator: Value = Value.UnitV
  // v1.23 — coordinator lease state (cluster-raft.md §5).  The four
  // `LeaderCoordinator` function fields, captured at switch time.
  @volatile private var coordAcquireFn: Value = Value.UnitV
  @volatile private var coordRenewFn:   Value = Value.UnitV
  @scala.annotation.unused
  @volatile private var coordReleaseFn: Value = Value.UnitV
  @volatile private var coordHolderFn:  Value = Value.UnitV
  @volatile private[interpreter] var coordIsLeader:  Boolean = false
  private val coordTickThread =
    new java.util.concurrent.atomic.AtomicReference[Thread](null)
  private val CoordLeaseTimeoutMs  = 5000L
  private val CoordRenewIntervalMs = 1000L
  private def callCoordFn(fn: Value, args: List[Value]): Value =
    fn match
      case Value.NativeFnV(_, body) =>
        try Computation.run(body(args)) catch case _: Throwable => Value.UnitV
      case _: Value.FunV =>
        try Computation.run(callValue(fn, args, Map.empty))
        catch case _: Throwable => Value.UnitV
      case _ => Value.UnitV
  private def ensureCoordTickThread(): Unit =
    if coordTickThread.get() != null then return
    val t = Thread.ofVirtual().start { () =>
      try
        var done = false
        while !done && leaderProtocolRef.get() == "coord" do
          try
            if !coordIsLeader then
              val ret = callCoordFn(coordAcquireFn,
                List(Value.StringV(localNodeId), Value.IntV(CoordLeaseTimeoutMs)))
              ret match
                case Value.BoolV(true) =>
                  coordIsLeader = true
                  val prev = currentLeader.getAndSet(localNodeId)
                  if prev != localNodeId then
                    enqueueLeaderEvent("LeaderElected", localNodeId)
                    recordLeaderHist(localNodeId)
                case _ => ()
            else
              val ret = callCoordFn(coordRenewFn, List(Value.StringV(localNodeId)))
              ret match
                case Value.BoolV(false) =>
                  coordIsLeader = false
                  val prev = currentLeader.getAndSet("")
                  if prev.nonEmpty then enqueueLeaderEvent("LeaderLost", prev)
                case _ => ()
          catch case _: Throwable => ()
          try Thread.sleep(CoordRenewIntervalMs)
          catch case _: InterruptedException => done = true
      catch case _: Throwable => ()
    }
    if !coordTickThread.compareAndSet(null, t) then t.interrupt()
  // v1.23 — drain-aware step-down (cluster-raft.md §7).
  private[interpreter] def stepDownIfLeader(): Unit =
    leaderProtocolRef.get() match
      case "raft" =>
        if raftStateName == "leader" then
          raftStateName = "follower"
          raftLeaderId  = ""
          val prev = currentLeader.getAndSet("")
          if prev.nonEmpty then enqueueLeaderEvent("LeaderLost", prev)
      case "coord" =>
        if coordIsLeader then
          coordIsLeader = false
          coordReleaseFn match
            case Value.NativeFnV(_, _) | _: Value.FunV =>
              callCoordFn(coordReleaseFn, List(Value.StringV(localNodeId)))
            case _ => ()
          val prev = currentLeader.getAndSet("")
          if prev.nonEmpty then enqueueLeaderEvent("LeaderLost", prev)
      case _ =>
        if currentLeader.compareAndSet(localNodeId, "") then
          enqueueLeaderEvent("LeaderLost", localNodeId)
  // v1.23 — bounded leader-claim history.
  private val LeaderHistMax = 100
  private val leaderHistTermSeq =
    new java.util.concurrent.atomic.AtomicLong(0L)
  private val leaderHist =
    new java.util.concurrent.ConcurrentLinkedDeque[(Long, String, Long)]()
  private def recordLeaderHist(leaderId: String): Unit =
    val term = leaderHistTermSeq.incrementAndGet()
    leaderHist.offer((term, leaderId, System.currentTimeMillis()))
    while leaderHist.size() > LeaderHistMax do leaderHist.pollFirst()
  // v1.23 — Raft state (cluster-raft.md §4.1).
  @volatile private[interpreter] var raftCurrentTerm: Long   = 0L
  @volatile private var raftVotedFor:    String = ""
  @volatile private[interpreter] var raftStateName:   String = "follower"
  @volatile private[interpreter] var raftLeaderId:    String = ""
  @volatile private var raftElectionDue: Long   = 0L
  @volatile private var raftVotes:       Int    = 0
  private val RaftElectionLo  = 150L
  private val RaftElectionHi  = 300L
  private val RaftHeartbeatMs = 50L
  private val raftTickThread =
    new java.util.concurrent.atomic.AtomicReference[Thread](null)
  private val raftRand = new scala.util.Random()
  private def raftRandTimeout: Long =
    RaftElectionLo + raftRand.nextInt((RaftElectionHi - RaftElectionLo).toInt + 1)
  private def raftBroadcastHeartbeat(): Unit =
    val payload = s"""{"t":"raft_append","from":${jsonStr(localNodeId)},"term":$raftCurrentTerm}"""
    peerChannels.forEach { (_, send) => try send(payload) catch case _: Throwable => () }
  private def raftAdoptLeader(newLeader: String): Unit =
    val prev = currentLeader.getAndSet(newLeader)
    // Record every accepted claim in leaderHistory, per v1.23 §
    // "leader-protocol" doc — single-node mode has both `prev` and
    // `newLeader` empty strings, and the old `prev != newLeader`
    // guard around the history write conflated "no change" with
    // "first claim by an empty-id node", silently dropping the
    // initial entry.  `enqueueLeaderEvent` stays guarded so multi-
    // adopt-of-same-leader doesn't fire duplicate `LeaderElected`
    // notifications.
    recordLeaderHist(newLeader)
    if prev != newLeader then
      enqueueLeaderEvent("LeaderElected", newLeader)
  private def startRaftElection(): Unit =
    raftStateName    = "candidate"
    raftCurrentTerm  = raftCurrentTerm + 1
    raftVotedFor     = localNodeId
    raftVotes        = 1
    raftElectionDue  = System.currentTimeMillis() + raftRandTimeout
    raftPersist()
    val peerIds = scala.collection.mutable.ListBuffer.empty[String]
    peerChannels.keySet().forEach(p => peerIds += p)
    val total = peerIds.size + 1
    if raftVotes > total / 2 then
      raftStateName = "leader"
      raftLeaderId  = localNodeId
      raftAdoptLeader(localNodeId)
      raftBroadcastHeartbeat()
    else
      val payload =
        s"""{"t":"raft_vote_req","from":${jsonStr(localNodeId)},"term":$raftCurrentTerm,"lastLogTerm":0}"""
      peerIds.foreach { nid =>
        try Option(peerChannels.get(nid)).foreach(_.apply(payload))
        catch case _: Throwable => ()
      }
  private def ensureRaftTickThread(): Unit =
    if raftTickThread.get() != null then return
    val t = Thread.ofVirtual().start { () =>
      try
        var done = false
        while !done && leaderProtocolRef.get() == "raft" do
          try Thread.sleep(RaftHeartbeatMs) catch case _: InterruptedException => done = true
          if !done then
            val now = System.currentTimeMillis()
            raftStateName match
              case "leader" => raftBroadcastHeartbeat()
              case "follower" | "candidate" =>
                if now >= raftElectionDue then startRaftElection()
              case _ => ()
      catch case _: Throwable => ()
    }
    if !raftTickThread.compareAndSet(null, t) then t.interrupt()
  // v1.23 — Raft persistence (cluster-raft.md §4.1).
  private def raftStatePath: java.nio.file.Path =
    val key = if localNodeId.isEmpty then "default" else localNodeId.replaceAll("[^A-Za-z0-9._-]", "_")
    java.nio.file.Paths.get(s".ssc-raft-state-$key.json")
  private def raftPersist(): Unit =
    try
      val voted = raftVotedFor.replace("\\", "\\\\").replace("\"", "\\\"")
      val json  = "{\"currentTerm\":" + raftCurrentTerm.toString + ",\"votedFor\":\"" + voted + "\"}"
      java.nio.file.Files.writeString(raftStatePath, json,
        java.nio.charset.StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)
    catch case _: Throwable => ()
  private def raftLoad(): Unit =
    try
      val p = raftStatePath
      if java.nio.file.Files.exists(p) then
        val s = java.nio.file.Files.readString(p)
        val termIdx = s.indexOf("\"currentTerm\"")
        if termIdx >= 0 then
          val ci = s.indexOf(':', termIdx); var i = ci + 1
          while i < s.length && s(i) == ' ' do i += 1
          var j = i; while j < s.length && (s(j).isDigit || s(j) == '-') do j += 1
          if j > i then s.substring(i, j).toLongOption.foreach(t => raftCurrentTerm = t)
        val vk = "\"votedFor\""
        val ki = s.indexOf(vk)
        if ki >= 0 then
          val qi = s.indexOf('"', ki + vk.length + 1)
          val qe = if qi > 0 then s.indexOf('"', qi + 1) else -1
          if qe > qi then raftVotedFor = s.substring(qi + 1, qe)
    catch case _: Throwable => ()
  // v1.23 — drain / rolling-restart state.
  private[interpreter] val isDrainingSelf =
    new java.util.concurrent.atomic.AtomicBoolean(false)
  private[interpreter] val drainingPeers =
    new java.util.concurrent.ConcurrentHashMap[String, java.lang.Boolean]()
  private val drainEventSubs =
    new java.util.concurrent.CopyOnWriteArrayList[java.lang.Long]()
  // v1.23 — cluster-wide pub/sub.  Subscribers are local (per-node);
  // a `publish(topic, msg)` broadcasts to all peers, each of which
  // dispatches to its own local subscribers.  Topic → list-of-actor-ids.
  private val publishSubs =
    new java.util.concurrent.ConcurrentHashMap[String,
      java.util.concurrent.CopyOnWriteArrayList[java.lang.Long]]()
  private val publishQueue =
    new java.util.concurrent.ConcurrentLinkedQueue[(Long, Value)]()
  private def localPublish(topic: String, msg: Value): Unit =
    val subs = publishSubs.get(topic)
    if subs != null && !subs.isEmpty then
      val it = subs.iterator
      while it.hasNext do
        publishQueue.offer((it.next().toLong, msg))
      val t = schedulerThread; if t != null then t.interrupt()
  private val drainEventQueue =
    new java.util.concurrent.ConcurrentLinkedQueue[Value]()
  private[interpreter] def enqueueDrainEvent(nodeId: String, draining: Boolean): Unit =
    val ts = System.currentTimeMillis()
    recordEventLog(s"""{"ts":$ts,"type":"DrainStateChanged","nodeId":${jsonStr(nodeId)},"draining":$draining}""")
    if !drainEventSubs.isEmpty then
      val ev = Value.InstanceV("DrainStateChanged",
        Map("nodeId" -> Value.StringV(nodeId), "draining" -> Value.BoolV(draining)))
      drainEventQueue.offer(ev)
      val t = schedulerThread; if t != null then t.interrupt()
  private def sendDrainState(target: String => Unit): Unit =
    if isDrainingSelf.get() then
      val payload = s"""{"t":"drain","from":${jsonStr(localNodeId)},"draining":true}"""
      try target(payload) catch case _: Throwable => ()
  // v1.23 — cluster metrics aggregation: per-node gauges.
  private[interpreter] val clusterMetrics =
    new java.util.concurrent.ConcurrentHashMap[String,
      java.util.concurrent.ConcurrentHashMap[String, java.lang.Double]]()
  private val metricEventSubs =
    new java.util.concurrent.CopyOnWriteArrayList[java.lang.Long]()
  private val metricEventQueue =
    new java.util.concurrent.ConcurrentLinkedQueue[Value]()
  private def enqueueMetricEvent(name: String, nodeId: String, value: Double): Unit =
    val ts = System.currentTimeMillis()
    recordEventLog(s"""{"ts":$ts,"type":"MetricChanged","name":${jsonStr(name)},"nodeId":${jsonStr(nodeId)},"value":$value}""")
    if !metricEventSubs.isEmpty then
      val ev = Value.InstanceV("MetricChanged", Map(
        "name"   -> Value.StringV(name),
        "nodeId" -> Value.StringV(nodeId),
        "value"  -> Value.DoubleV(value)))
      metricEventQueue.offer(ev)
      val t = schedulerThread; if t != null then t.interrupt()
  private def applyMetricUpdate(name: String, nodeId: String, value: Double): Unit =
    val inner = clusterMetrics.computeIfAbsent(name, _ =>
      new java.util.concurrent.ConcurrentHashMap[String, java.lang.Double]())
    val prev = inner.put(nodeId, java.lang.Double.valueOf(value))
    if prev == null || prev.doubleValue() != value then
      enqueueMetricEvent(name, nodeId, value)
  private def sendMetricSnapshot(target: String => Unit): Unit =
    clusterMetrics.forEach { (name, inner) =>
      val v = inner.get(localNodeId)
      if v != null then
        val payload =
          s"""{"t":"metric","from":${jsonStr(localNodeId)},"name":${jsonStr(name)},"value":${v.doubleValue()}}"""
        try target(payload) catch case _: Throwable => ()
    }
  private def enqueueLeaderEvent(tag: String, leaderId: String): Unit =
    val ts = System.currentTimeMillis()
    recordEventLog(s"""{"ts":$ts,"type":${jsonStr(tag)},"nodeId":${jsonStr(leaderId)}}""")
    if !leaderEventSubs.isEmpty then
      val ev = Value.InstanceV(tag, Map("nodeId" -> Value.StringV(leaderId)))
      leaderEventQueue.offer(ev)
      val t = schedulerThread; if t != null then t.interrupt()
  private def broadcastCoordinator(): Unit =
    val payload = s"""{"t":"coordinator","from":${jsonStr(localNodeId)}}"""
    peerChannels.forEach { (_, send) => try send(payload) catch case _: Throwable => () }
  /** v1.23 — true when quorum is disabled or the visible cluster
   *  (self + peers) meets the configured quorum threshold.  Gates
   *  self-claim paths in Bully to prevent split-brain. */
  private def hasQuorum: Boolean =
    quorumSize <= 0L || (peerChannels.size + 1L) >= quorumSize

  private def startElection(): Unit =
    if clusterDebug then
      val peers = scala.collection.mutable.ListBuffer.empty[String]
      peerChannels.keySet().forEach(peers += _)
      out.println(s"[cluster:dbg] $localNodeId startElection peers=${peers.mkString(",")} quorum=$quorumSize visible=${peerChannels.size + 1}")
    if localNodeId.isEmpty then
      val prev = currentLeader.getAndSet(localNodeId)
      // Record every accepted claim — single-node mode has empty
      // `localNodeId`, the old `prev != localNodeId` guard skipped
      // the initial history entry.  Notification stays guarded.
      recordLeaderHist(localNodeId)
      if prev != localNodeId then
        enqueueLeaderEvent("LeaderElected", localNodeId)
    else
      val higher = scala.collection.mutable.ListBuffer.empty[String]
      peerChannels.keySet().forEach(nid => if nid > localNodeId then higher += nid)
      if higher.isEmpty then
        // v1.23 — quorum gate: decline to self-claim when below quorum,
        // even though no higher peer is visible.  Caller must retry
        // the election after more peers reconnect.
        if !hasQuorum then
          if clusterDebug then out.println(
            s"[cluster:dbg] $localNodeId declined self-claim (below quorum ${peerChannels.size + 1}/$quorumSize)")
        else
          val prev = currentLeader.getAndSet(localNodeId)
          broadcastCoordinator()
          // Record on every accepted claim (symmetric with the
          // empty-id branch above + the Raft / coordinator paths).
          recordLeaderHist(localNodeId)
          if prev != localNodeId then
            enqueueLeaderEvent("LeaderElected", localNodeId)
      else
        electionInProgress = true
        electionStartedAt  = System.currentTimeMillis()
        gotAliveResponse   = false
        val payload = s"""{"t":"election","from":${jsonStr(localNodeId)}}"""
        higher.foreach { nid =>
          try Option(peerChannels.get(nid)).foreach(_.apply(payload))
          catch case _: Throwable => ()
        }
  // v1.23 — URL-keyed dedupe so concurrent peer-loss events (e.g.
  // heartbeat-timeout + recv-loop exit fighting over the same link)
  // don't each spin up an independent exponential-backoff loop.
  private val reconnectActive =
    java.util.concurrent.ConcurrentHashMap.newKeySet[String]()

  private def scheduleReconnect(rurl: String, rtok: String): Unit =
    if !reconnectActive.add(rurl) then return
    Thread.ofVirtual().start { () =>
      val startedAt = System.currentTimeMillis()
      var delay = reconnectInitialMs.max(1L)
      var done  = false
      try
        while !done && !peerUrls.containsValue(rurl) do
          try Thread.sleep(delay) catch case _: InterruptedException => done = true
          if !done && reconnectInitialMs <= 0L then done = true
          if !done && peerUrls.containsValue(rurl) then done = true
          // v1.23 — bail out when the total wall-clock retry budget is
          // exhausted (`giveUpAfterMs` arg of setReconnectPolicy).
          // Keeps a permanently-dead peer's URL from spinning a virtual
          // thread forever — and exhausting the FD table under repeated
          // dial-failures over hours of test/cluster lifetime.
          if !done && reconnectGiveUpMs > 0L &&
             (System.currentTimeMillis() - startedAt) >= reconnectGiveUpMs then
            done = true
            if clusterDebug then out.println(
              s"[cluster:dbg] $localNodeId reconnect gave up on $rurl after ${reconnectGiveUpMs}ms")
          if !done then
            try connectPeer(rurl, rtok) catch case _: Throwable => ()
            if peerUrls.containsValue(rurl) then done = true
            else
              val cap = if reconnectMaxMs > 0L then reconnectMaxMs else delay
              delay = math.min(delay * 2L, cap.max(delay))
      catch case _: Throwable => ()
      finally reconnectActive.remove(rurl)
    }
  // Cross-node monitors: nodeId → [(localActorId, monRef, remotePid.localId)]
  private val remoteMonitors =
    new java.util.concurrent.ConcurrentHashMap[String,
      java.util.concurrent.CopyOnWriteArrayList[(Long, Long, Long)]]()
  // Cross-node links: nodeId → [(localActorId, remotePid.localId)]
  private val remoteLinks =
    new java.util.concurrent.ConcurrentHashMap[String,
      java.util.concurrent.CopyOnWriteArrayList[(Long, Long)]]()

  /** v1.23 — record a pong arrival; appends the inter-arrival delta to the
   *  phi-accrual history for `nodeId`.  Call BEFORE updating `peerLastPong`. */
  private def recordPongInterval(nodeId: String): Unit =
    val now  = System.currentTimeMillis()
    val last = peerLastPong.getOrDefault(nodeId, 0L)
    if last > 0L then
      val delta = java.lang.Long.valueOf(now - last)
      val dq    = peerPongHist.computeIfAbsent(nodeId, _ =>
        new java.util.concurrent.ConcurrentLinkedDeque[java.lang.Long]())
      dq.offer(delta)
      while dq.size() > PhiHistMax do dq.pollFirst()

  /** v1.23 — phi-accrual suspicion level for `nodeId`.  Higher = more likely
   *  the peer is down.  Returns +∞ for unknown peers and when no history is
   *  available yet.  Threshold of ~8 is the standard "definitely down" cut
   *  (matches Akka/Cassandra defaults). */
  private def computePhi(nodeId: String): Double =
    val hist = peerPongHist.get(nodeId)
    if hist == null || hist.isEmpty then return Double.PositiveInfinity
    val n  = hist.size
    var s  = 0.0
    val it = hist.iterator
    while it.hasNext do s += it.next().longValue().toDouble
    val mean = s / n
    var sq = 0.0
    val it2 = hist.iterator
    while it2.hasNext do
      val d = it2.next().longValue().toDouble - mean
      sq += d * d
    val variance = if n > 1 then sq / (n - 1) else 1.0
    val stddev   = math.sqrt(variance).max(50.0)  // floor at 50 ms
    val now      = System.currentTimeMillis()
    val last     = peerLastPong.getOrDefault(nodeId, now)
    val elapsed  = (now - last).toDouble
    if elapsed <= mean then 0.0
    else
      // Right-tail Normal-distribution approximation (Akka / Cassandra style):
      //   phi = -log10( exp(-z² / 2) / (z · √(2π)) )  with  z = (elapsed - μ) / σ
      val z    = (elapsed - mean) / stddev
      val tail = math.exp(-z * z / 2.0) / (z * math.sqrt(2.0 * math.Pi))
      if tail <= 0.0 then Double.PositiveInfinity
      else -math.log10(tail.min(1.0))

  /** v1.23 — bounded ring buffer of every cluster event as JSON lines.
   *  Independent of the in-process subscription system: events land
   *  here whether or not any actor has called subscribe*Events, so the
   *  `GET /_ssc-cluster/events` endpoint always has data for ops
   *  tooling.  Cap 200 entries — older lines drop oldest-first. */
  private val ClusterEventLogMax = 200
  private[interpreter] val clusterEventLog =
    new java.util.concurrent.ConcurrentLinkedDeque[String]()

  private def recordEventLog(jsonObj: String): Unit =
    clusterEventLog.offer(jsonObj)
    while clusterEventLog.size() > ClusterEventLogMax do
      clusterEventLog.pollFirst()

  /** v1.23 — enqueue NodeJoined/NodeLeft event; scheduler drains and delivers. */
  private def enqueueClusterEvent(tag: String, nodeId: String, reason: String = ""): Unit =
    // Mirror into the ops event log regardless of in-process subscribers.
    val ts = System.currentTimeMillis()
    val logEntry =
      if tag == "NodeJoined" then
        s"""{"ts":$ts,"type":"NodeJoined","nodeId":${jsonStr(nodeId)}}"""
      else
        s"""{"ts":$ts,"type":"NodeLeft","nodeId":${jsonStr(nodeId)},"reason":${jsonStr(reason)}}"""
    recordEventLog(logEntry)
    if !clusterEventSubs.isEmpty then
      val ev =
        if tag == "NodeJoined" then
          Value.InstanceV("NodeJoined", Map("nodeId" -> Value.StringV(nodeId)))
        else
          Value.InstanceV("NodeLeft", Map(
            "nodeId" -> Value.StringV(nodeId),
            "reason" -> Value.StringV(reason)
          ))
      clusterEventQueue.offer(ev)
      val t = schedulerThread; if t != null then t.interrupt()

  /** Fire EXIT/Down for all local actors that linked/monitored actors on `nodeId`. */
  private def fireNodeDown(rt: ActorRuntime, nodeId: String): Unit =
    val noconn = Value.InstanceV("noconnection", Map.empty)
    Option(remoteMonitors.remove(nodeId)).foreach { list =>
      list.forEach { (actorId, monRef, rPidLocalId) =>
        val downMsg = Value.InstanceV("Down", Map(
          "ref"    -> Value.IntV(monRef),
          "from"   -> mkPid(nodeId, rPidLocalId),
          "reason" -> noconn))
        rt.mailboxes.get(actorId).foreach(_.offer(downMsg))
        wakeBlocked(rt, actorId)
      }
    }
    Option(remoteLinks.remove(nodeId)).foreach { list =>
      list.forEach { (actorId, rPidLocalId) =>
        val deadPid = mkPid(nodeId, rPidLocalId)
        if rt.trapExit.getOrElse(actorId, false) then
          val exitMsg = Value.InstanceV("Exit", Map("from" -> deadPid, "reason" -> noconn))
          rt.mailboxes.get(actorId).foreach(_.offer(exitMsg))
          wakeBlocked(rt, actorId)
        else
          killActor(rt, actorId, noconn)
      }
    }

  private def mkPid(nodeId: String, localId: Long): Value =
    Value.InstanceV("Pid", Map("nodeId" -> Value.StringV(nodeId), "localId" -> Value.IntV(localId)))

  /** v1.23 — shared peer-loss cleanup.  Runs from three sites that
   *  notice a peer is gone: outbound recv-loop exit, inbound recv-loop
   *  exit, and the heartbeat thread's timeout branch.  Idempotent: all
   *  the state mutations are remove / compareAndSet-style, so a second
   *  call after a first one completed is a no-op.  Returns true if
   *  this call was the one that actually saw the peer leave (used to
   *  avoid double-scheduling reconnects). */
  private def peerLost(peerNodeId: String, urlForReconnect: String, tokenForReconnect: String): Boolean =
    val wasPresent = peerChannels.remove(peerNodeId) != null
    if clusterDebug then
      out.println(s"[cluster:dbg] $localNodeId peerLost($peerNodeId) wasPresent=$wasPresent currentLeader=${currentLeader.get()} autoReelect=$autoReelect")
    peerLastPong.remove(peerNodeId)
    peerUrls.remove(peerNodeId)
    peerPongHist.remove(peerNodeId)
    peerPhiViews.remove(peerNodeId)
    drainingPeers.remove(peerNodeId)
    clusterMetrics.forEach { (_, inner) => inner.remove(peerNodeId) }
    if wasPresent then
      nodeDownQueue.offer(peerNodeId)
      enqueueClusterEvent("NodeLeft", peerNodeId, "disconnect")
      val before = currentLeader.get()
      val wasLeader = currentLeader.compareAndSet(peerNodeId, "")
      if clusterDebug then
        out.println(s"[cluster:dbg] $localNodeId peerLost($peerNodeId) before=$before wasLeader=$wasLeader after=${currentLeader.get()}")
      // v1.23 — handle the race where another peer beat us to noticing
      // the leader's death and already broadcast a new "coordinator":
      // currentLeader has been *replaced* with the new leader, so our
      // compareAndSet(oldLeader, "") fails.  That's fine — the cluster
      // already picked someone — but we still need to fire `startElection`
      // on the path where the leader genuinely left without a successor
      // (e.g. the only remaining node is us).
      if wasLeader || before == peerNodeId then
        enqueueLeaderEvent("LeaderLost", peerNodeId)
        if autoReelect then startElection()
      if reconnectInitialMs > 0L && urlForReconnect.nonEmpty then
        scheduleReconnect(urlForReconnect, tokenForReconnect)
      val t = schedulerThread; if t != null then t.interrupt()
    wasPresent

  private def connectPeer(url: String, token: String): Unit =
    val hdrs = if token.nonEmpty then Map("Authorization" -> s"Bearer $token") else Map.empty[String, String]
    Thread.ofVirtual().start { () =>
      try
        if clusterDebug then out.println(s"[cluster:dbg] $localNodeId -> connectPeer($url) starting")
        val sess = InterpreterServerSupport.current.openWsClient(
          this, url, hdrs, List("ssc-actors-v1"), out)
        sess.connect()
        if clusterDebug then out.println(s"[cluster:dbg] $localNodeId -> connectPeer($url) connected")
        // Send our handshake frame first
        sess.sendText(s"""{"nodeId":${jsonStr(localNodeId)}}""")
        // Recv handshake reply: peer sends {"nodeId":"..."}
        sess.recvText() match
          case None =>
            if clusterDebug then out.println(s"[cluster:dbg] $localNodeId -> connectPeer($url) closed during handshake")
            // v1.23 — handshake recv returned None (peer closed before
            // replying).  Schedule a reconnect just like dial-failure
            // and connection-loss paths do.
            if reconnectInitialMs > 0L && !peerUrls.containsValue(url) then
              scheduleReconnect(url, token)
          case Some(firstMsg) =>
            val peerNodeId = JsonParser.parseOption(firstMsg) match
              case Some(Value.MapV(m)) =>
                m.get(Value.StringV("nodeId")).collect { case Value.StringV(n) => n }.getOrElse("")
              case _ => ""
            if clusterDebug then out.println(s"[cluster:dbg] $localNodeId -> connectPeer($url) handshake peerNodeId='$peerNodeId'")
            if peerNodeId.nonEmpty then
              peerUrls.put(peerNodeId, url)
              peerChannels.put(peerNodeId, { text => sess.sendText(text) })
              peerLastPong.put(peerNodeId, System.currentTimeMillis())
              enqueueClusterEvent("NodeJoined", peerNodeId)
              // If joinCluster is active, request this peer's peer list.
              if joinMode then
                try peerChannels.get(peerNodeId)(s"""{"t":"peers_req","from":${jsonStr(localNodeId)}}""")
                catch case _ => ()
              // v1.23 — snapshot the cluster config + drain state + metrics to the new peer.
              sendConfigSnapshot(text => sess.sendText(text))
              sendDrainState(text => sess.sendText(text))
              sendMetricSnapshot(text => sess.sendText(text))
              sendAtomicSnapshot(text => sess.sendText(text))
              // Heartbeat: ping every `peerHeartbeatIntervalMs`, abort
              // if no pong for `peerHeartbeatDeadAfterMs`.  Defaults
              // 30 s / 40 s; tune via `setHeartbeatTimeout(...)`.
              val hbThread = Thread.ofVirtual().start { () =>
                try
                  while peerChannels.containsKey(peerNodeId) do
                    Thread.sleep(peerHeartbeatIntervalMs)
                    if peerChannels.containsKey(peerNodeId) then
                      val age = System.currentTimeMillis() - peerLastPong.getOrDefault(peerNodeId, 0L)
                      if age > peerHeartbeatDeadAfterMs then
                        // v1.23 — full peer-loss cleanup, not just
                        // peerChannels.remove.  Without this the
                        // currentLeader / autoReelect / scheduleReconnect
                        // wiring only runs from the recv-loop-exit
                        // path, and a stalled recv() (which the
                        // heartbeat exists to handle in the first
                        // place) means we never re-elect after the
                        // current leader dies.
                        peerLost(peerNodeId, url, token)
                        sess.abort()
                      else
                        try peerChannels.get(peerNodeId)("""{"t":"ping"}""") catch case _ => ()
                catch case _: InterruptedException => ()
              }
              // Recv loop — on virtual thread, blocking is fine
              var running = true
              while running do
                sess.recvText() match
                  case None      => running = false
                  case Some(msg) => dispatchPeerEnvelope(peerNodeId, msg)
              hbThread.interrupt()
              peerLost(peerNodeId, url, token)
      catch case e: Throwable =>
        // v1.23 — gate the per-attempt error log behind clusterDebug so
        // a permanently-dead peer's retry loop doesn't spam stdout with
        // identical "ConnectException" lines.  The terminal "gave up"
        // log (in scheduleReconnect) still appears for postmortem.
        if clusterDebug then
          out.println(s"connectNode error [$url]: ${e.getMessage}")
        // If the initial dial failed (peer not yet bound), schedule a
        // reconnect just like we do after a previously-up link drops.
        // Without this, non-seed nodes that race against each other only
        // ever reach the seed and the cluster stays fragmented.
        if reconnectInitialMs > 0L && !peerUrls.containsValue(url) then
          scheduleReconnect(url, token)
    }

  private def dispatchPeerEnvelope(peerNodeId: String, rawJson: String): Unit =
    JsonParser.parseOption(rawJson) match
      case Some(Value.MapV(m)) =>
        val t = m.get(Value.StringV("t")).collect { case Value.StringV(s) => s }.getOrElse("")
        t match
          case "msg" =>
            val toLocalId = m.get(Value.StringV("to")).collect {
              case Value.MapV(tm) => tm.get(Value.StringV("localId")) match
                case Some(Value.IntV(n))    => n
                case Some(Value.DoubleV(d)) => d.toLong
                case _                      => -1L
            }.getOrElse(-1L)
            val bodyParsed = m.get(Value.StringV("body"))
            if toLocalId >= 0 then
              bodyParsed.foreach { body =>
                val msgVal = ValueSerializer.fromParsed(body)
                remoteInbox.offer((toLocalId, msgVal))
                val t = schedulerThread; if t != null then t.interrupt()
              }
          case "ping" =>
            Option(peerChannels.get(peerNodeId)).foreach(_.apply("""{"t":"pong"}"""))
          case "pong" =>
            recordPongInterval(peerNodeId)
            peerLastPong.put(peerNodeId, System.currentTimeMillis())
          case "peers_req" =>
            // Respond with our known peer URLs + self URL.
            val sb = new StringBuilder("""{"t":"peers_resp","peers":[""")
            var first = true
            if localNodeUrl.nonEmpty then
              sb.append(s"""{"nodeId":${jsonStr(localNodeId)},"url":${jsonStr(localNodeUrl)}}""")
              first = false
            peerUrls.forEach { (nid, u) =>
              if u.nonEmpty then
                if !first then sb.append(',')
                sb.append(s"""{"nodeId":${jsonStr(nid)},"url":${jsonStr(u)}}""")
                first = false
            }
            sb.append("]}")
            Option(peerChannels.get(peerNodeId)).foreach(_.apply(sb.toString))
          case "peers_resp" =>
            val pi = m.get(Value.StringV("peers"))
            pi.foreach {
              case Value.ListV(items) =>
                items.foreach {
                  case Value.MapV(pm) =>
                    val peerUrl2 = pm.get(Value.StringV("url")).collect { case Value.StringV(s) => s }.getOrElse("")
                    val peerNid  = pm.get(Value.StringV("nodeId")).collect { case Value.StringV(s) => s }.getOrElse("")
                    if peerUrl2.nonEmpty && peerNid.nonEmpty &&
                       peerNid != localNodeId && !peerChannels.containsKey(peerNid) then
                      connectPeer(peerUrl2, joinToken)
                  case _ => ()
                }
              case _ => ()
            }
          case "pub" =>
            // v1.23 — cluster-wide pub/sub envelope.  Body is the
            // serialized message Value; topic is the routing key.
            // Subscribers are local; each receiving node dispatches
            // to its own publishSubs map.
            val topic = m.get(Value.StringV("topic")).collect { case Value.StringV(s) => s }.getOrElse("")
            if topic.nonEmpty then
              m.get(Value.StringV("body")).foreach { body =>
                localPublish(topic, body)
              }
          case "global_reg" =>
            val name   = m.get(Value.StringV("name")).collect { case Value.StringV(s) => s }.getOrElse("")
            val nodeId = m.get(Value.StringV("nodeId")).collect { case Value.StringV(s) => s }.getOrElse("")
            val localId = m.get(Value.StringV("localId")).collect { case Value.StringV(s) => s.toLongOption.getOrElse(0L) }.getOrElse(0L)
            if clusterDebug then out.println(
              s"[cluster:dbg] $localNodeId recv global_reg name=$name from=$nodeId localId=$localId")
            if name.nonEmpty && nodeId.nonEmpty then
              globalRegistry.put(name, mkPid(nodeId, localId))
          case "phi_vector" =>
            // v1.23 — peer broadcasted its phi vector.  Replace our stored
            // view of that peer's perspective on the rest of the cluster.
            val from = m.get(Value.StringV("from")).collect { case Value.StringV(s) => s }.getOrElse("")
            if from.nonEmpty then
              val view = new java.util.concurrent.ConcurrentHashMap[String, java.lang.Double]()
              m.get(Value.StringV("view")) match
                case Some(Value.ListV(items)) =>
                  items.foreach {
                    case Value.ListV(List(Value.StringV(nid), Value.DoubleV(p))) =>
                      view.put(nid, java.lang.Double.valueOf(p))
                    case Value.ListV(List(Value.StringV(nid), Value.IntV(p))) =>
                      view.put(nid, java.lang.Double.valueOf(p.toDouble))
                    case _ => ()
                  }
                case _ => ()
              peerPhiViews.put(from, view)
          case "election" =>
            // v1.23 — Bully: lower-id peer is calling an election.
            val from = m.get(Value.StringV("from")).collect { case Value.StringV(s) => s }.getOrElse("")
            if from.nonEmpty && from < localNodeId then
              val reply = s"""{"t":"alive","from":${jsonStr(localNodeId)}}"""
              try Option(peerChannels.get(from)).foreach(_.apply(reply))
              catch case _: Throwable => ()
              if !electionInProgress then startElection()
          case "alive" =>
            gotAliveResponse = true
          case "coordinator" =>
            val from = m.get(Value.StringV("from")).collect { case Value.StringV(s) => s }.getOrElse("")
            if from.nonEmpty then
              val prev = currentLeader.getAndSet(from)
              electionInProgress = false
              if prev != from then
                enqueueLeaderEvent("LeaderElected", from)
                recordLeaderHist(from)
          case "config_set" =>
            val key   = m.get(Value.StringV("key")).collect { case Value.StringV(s) => s }.getOrElse("")
            val value = m.get(Value.StringV("value")).collect { case Value.StringV(s) => s }.getOrElse("")
            val orig  = m.get(Value.StringV("origin")).collect { case Value.StringV(s) => s }.getOrElse("")
            val ts    = m.get(Value.StringV("ts")) match
              case Some(Value.IntV(n))    => n
              case Some(Value.DoubleV(d)) => d.toLong
              case _                      => 0L
            if key.nonEmpty then applyConfigUpdate(key, value, ts, orig)
          case "atom_set" =>
            // v1.23 — cluster-wide atomic counter LWW update.
            val name  = m.get(Value.StringV("name")).collect { case Value.StringV(s) => s }.getOrElse("")
            val value = m.get(Value.StringV("value")) match
              case Some(Value.IntV(n))    => n
              case Some(Value.DoubleV(d)) => d.toLong
              case _                      => 0L
            val orig  = m.get(Value.StringV("origin")).collect { case Value.StringV(s) => s }.getOrElse("")
            val ts    = m.get(Value.StringV("ts")) match
              case Some(Value.IntV(n))    => n
              case Some(Value.DoubleV(d)) => d.toLong
              case _                      => 0L
            if name.nonEmpty then applyAtomicUpdate(name, value, ts, orig)
          case "drain" =>
            val from = m.get(Value.StringV("from")).collect { case Value.StringV(s) => s }.getOrElse("")
            if from.nonEmpty then
              val dr = m.get(Value.StringV("draining")).collect { case Value.BoolV(b) => b }.getOrElse(false)
              val prev = drainingPeers.put(from, java.lang.Boolean.valueOf(dr))
              if prev == null || prev.booleanValue() != dr then enqueueDrainEvent(from, dr)
          case "metric" =>
            val from  = m.get(Value.StringV("from")).collect { case Value.StringV(s) => s }.getOrElse("")
            val name  = m.get(Value.StringV("name")).collect { case Value.StringV(s) => s }.getOrElse("")
            val value = m.get(Value.StringV("value")) match
              case Some(Value.DoubleV(d)) => d
              case Some(Value.IntV(n))    => n.toDouble
              case _                      => 0.0
            if from.nonEmpty && name.nonEmpty then applyMetricUpdate(name, from, value)
          // v1.23 — Raft RPCs (cluster-raft.md §4.2)
          case "raft_vote_req" =>
            val from = m.get(Value.StringV("from")).collect { case Value.StringV(s) => s }.getOrElse("")
            val term = m.get(Value.StringV("term")) match
              case Some(Value.IntV(n))    => n
              case Some(Value.DoubleV(d)) => d.toLong
              case _                      => 0L
            if from.nonEmpty then
              var mutated = false
              val granted =
                if term < raftCurrentTerm then false
                else
                  if term > raftCurrentTerm then
                    raftCurrentTerm = term
                    raftVotedFor    = ""
                    raftStateName   = "follower"
                    mutated = true
                  if raftVotedFor.isEmpty || raftVotedFor == from then
                    raftVotedFor    = from
                    raftElectionDue = System.currentTimeMillis() + raftRandTimeout
                    mutated = true
                    true
                  else false
              if mutated then raftPersist()
              val reply =
                s"""{"t":"raft_vote_resp","from":${jsonStr(localNodeId)},"term":$raftCurrentTerm,"granted":$granted}"""
              try Option(peerChannels.get(from)).foreach(_.apply(reply))
              catch case _: Throwable => ()
          case "raft_vote_resp" =>
            val term = m.get(Value.StringV("term")) match
              case Some(Value.IntV(n))    => n
              case Some(Value.DoubleV(d)) => d.toLong
              case _                      => 0L
            val granted = m.get(Value.StringV("granted")).collect { case Value.BoolV(b) => b }.getOrElse(false)
            if term == raftCurrentTerm && raftStateName == "candidate" && granted then
              raftVotes = raftVotes + 1
              val total = peerChannels.size() + 1
              if raftVotes > total / 2 then
                raftStateName = "leader"
                raftLeaderId  = localNodeId
                raftAdoptLeader(localNodeId)
                raftBroadcastHeartbeat()
          case "raft_append" =>
            val from = m.get(Value.StringV("from")).collect { case Value.StringV(s) => s }.getOrElse("")
            val term = m.get(Value.StringV("term")) match
              case Some(Value.IntV(n))    => n
              case Some(Value.DoubleV(d)) => d.toLong
              case _                      => 0L
            if from.nonEmpty && term >= raftCurrentTerm then
              val termChanged = term > raftCurrentTerm
              raftCurrentTerm = term
              raftStateName   = "follower"
              val prevLeader  = raftLeaderId
              raftLeaderId    = from
              raftElectionDue = System.currentTimeMillis() + raftRandTimeout
              if termChanged then raftPersist()
              if prevLeader != from then raftAdoptLeader(from)
          case _ => ()
      case _ => ()

  private def remoteDeliverOrQueue(nodeId: String, pidFields: Map[String, Value], msg: Value): Unit =
    peerChannels.get(nodeId) match
      case null => () // peer not connected — silent drop (Erlang semantics)
      case send =>
        val toId   = pidFields.get("localId").collect { case Value.IntV(n) => n }.getOrElse(0L)
        val fromId = if actorRt != null then actorRt.currentId else -1L
        val body   = ValueSerializer.serialize(msg)
        val envelope =
          s"""{"t":"msg","to":{"nodeId":${jsonStr(nodeId)},"localId":$toId},"from":{"nodeId":${jsonStr(localNodeId)},"localId":$fromId},"body":$body}"""
        send(envelope)

  private[interpreter] def jsonStr(s: String): String =
    val sb = new StringBuilder(s.length + 2)
    sb.append('"')
    s.foreach {
      case '"'  => sb.append("\\\"")
      case '\\' => sb.append("\\\\")
      case '\n' => sb.append("\\n")
      case c    => sb.append(c)
    }
    sb.append('"')
    sb.result()

    // ── v1.6 Actors Phase 1 — cooperative scheduler ────────────────────
  //
  // Single-threaded green-thread scheduler over Computation.  Each
  // actor's "current frame" lives in `pending(id)`.  Stepping an actor
  // walks its Computation tree until it either returns Pure (actor
  // completes) or hits a `Perform("Actor", op, …)` node we handle here.
  // `receive` on an empty mailbox stores the (cases, env, continuation)
  // triple in `blocked` and switches to another ready actor.
  // `send` enqueues into the recipient's mailbox; if the recipient was
  // blocked and the new head matches, we splice the case-body into
  // its pending slot and mark it ready.
  //
  // The driver runs until every actor is either complete or blocked
  // with an empty mailbox.  The root actor's final value is returned.
  private[interpreter] def actorInterp(initial: Computation): Computation =
    val rt = new ActorRuntime
    val savedRt = actorRt
    actorRt = rt
    schedulerThread = Thread.currentThread()
    try
      val rootId = rt.nextId
      rt.nextId += 1
      rt.mailboxes(rootId) = new java.util.concurrent.LinkedBlockingQueue[Value]()
      rt.pending(rootId)   = initial
      rt.ready.enqueue(rootId)
      val rootResult = mutable.LongMap.empty[Value]
      val isDistributed = localNodeId.nonEmpty || !peerChannels.isEmpty
      // deadlockGuard prevents infinite loops in pure local programs.
      // In distributed mode we keep the scheduler alive until all actors
      // complete regardless of deadlock iterations.
      var deadlockGuard = 0
      val maxIterations = 100000

      def hasWork = rt.ready.nonEmpty || rt.blocked.exists(_._2._4.isDefined) ||
                    !remoteInbox.isEmpty || !nodeDownQueue.isEmpty ||
                    !clusterEventQueue.isEmpty ||
                    !leaderEventQueue.isEmpty || electionInProgress ||
                    !configEventQueue.isEmpty || !drainEventQueue.isEmpty ||
                    !metricEventQueue.isEmpty || !publishQueue.isEmpty ||
                    rt.timers.nonEmpty ||
                    (isDistributed && rt.blocked.nonEmpty)

      while hasWork && (isDistributed || deadlockGuard < maxIterations) do
        deadlockGuard += 1
        // Drain remote inbox (messages from peer nodes on WS threads).
        while !remoteInbox.isEmpty do
          val (targetId, msg) = remoteInbox.poll()
          rt.mailboxes.get(targetId).foreach { mb =>
            mb.offer(msg)
            wakeBlocked(rt, targetId)
          }
        // Fire EXIT/Down for any nodes that just went down (heartbeat timeout or disconnect).
        while !nodeDownQueue.isEmpty do
          fireNodeDown(rt, nodeDownQueue.poll())
        // v1.23 — deliver cluster events (NodeJoined/NodeLeft) to subscribers.
        while !clusterEventQueue.isEmpty do
          val ev = clusterEventQueue.poll()
          val it = clusterEventSubs.iterator
          while it.hasNext do
            val aid = it.next().toLong
            rt.mailboxes.get(aid).foreach { mb =>
              mb.offer(ev)
              wakeBlocked(rt, aid)
            }
        // v1.23 — Bully election timeout: claim self if no `alive` received.
        // Quorum-gated: even though no higher peer responded, decline
        // self-claim if we don't see enough peers (split-brain guard).
        if electionInProgress &&
           System.currentTimeMillis() - electionStartedAt >= ElectionTimeoutMs then
          electionInProgress = false
          if !gotAliveResponse && hasQuorum then
            val prev = currentLeader.getAndSet(localNodeId)
            broadcastCoordinator()
            if prev != localNodeId then
              enqueueLeaderEvent("LeaderElected", localNodeId)
              recordLeaderHist(localNodeId)
        // v1.23 — deliver leader events (LeaderElected/LeaderLost) to subscribers.
        while !leaderEventQueue.isEmpty do
          val ev = leaderEventQueue.poll()
          val it = leaderEventSubs.iterator
          while it.hasNext do
            val aid = it.next().toLong
            rt.mailboxes.get(aid).foreach { mb =>
              mb.offer(ev)
              wakeBlocked(rt, aid)
            }
        // v1.23 — deliver config-change events to subscribers.
        while !configEventQueue.isEmpty do
          val ev = configEventQueue.poll()
          val it = configEventSubs.iterator
          while it.hasNext do
            val aid = it.next().toLong
            rt.mailboxes.get(aid).foreach { mb =>
              mb.offer(ev)
              wakeBlocked(rt, aid)
            }
        // v1.23 — deliver drain-state events to subscribers.
        while !drainEventQueue.isEmpty do
          val ev = drainEventQueue.poll()
          val it = drainEventSubs.iterator
          while it.hasNext do
            val aid = it.next().toLong
            rt.mailboxes.get(aid).foreach { mb =>
              mb.offer(ev)
              wakeBlocked(rt, aid)
            }
        // v1.23 — deliver metric events to subscribers.
        while !metricEventQueue.isEmpty do
          val ev = metricEventQueue.poll()
          val it = metricEventSubs.iterator
          while it.hasNext do
            val aid = it.next().toLong
            rt.mailboxes.get(aid).foreach { mb =>
              mb.offer(ev)
              wakeBlocked(rt, aid)
            }
        // v1.23 — deliver pub/sub messages to subscribed actors.
        while !publishQueue.isEmpty do
          val (aid, msg) = publishQueue.poll()
          rt.mailboxes.get(aid).foreach { mb =>
            mb.offer(msg)
            wakeBlocked(rt, aid)
          }
        // Fire scheduled sends whose deadline has passed.
        if rt.timers.nonEmpty then
          val nowMs = System.currentTimeMillis()
          val fired = rt.timers.collect { case (ref, (fa, _, _, _)) if nowMs >= fa => ref }.toList
          for ref <- fired; entry <- rt.timers.get(ref) do
            val (fireAt, period, targetId, msg) = entry
            rt.mailboxes.get(targetId).foreach { mb =>
              mb.offer(msg)
              wakeBlocked(rt, targetId)
            }
            period match
              case Some(p) => rt.timers(ref) = (fireAt + p, period, targetId, msg)
              case None    => rt.timers.remove(ref)
        if rt.ready.isEmpty then
          // Quiescent — sleep until earliest blocked-receive deadline,
          // timer fire, or remote-message interrupt.
          val now = System.currentTimeMillis()
          val earliestBlock = rt.blocked.collect {
            case (id, (_, _, _, Some(d), _)) => (id, d)
          }.minByOption(_._2)
          val earliestTimerMs = if rt.timers.isEmpty then None
                                else Some(rt.timers.values.map(_._1).min)
          val sleepUntil = List(earliestBlock.map(_._2), earliestTimerMs).flatten.minOption
          val sleepFor   = sleepUntil.map(_ - now).getOrElse(if isDistributed then 30L else Long.MaxValue)
          if sleepFor > 0 then
            try Thread.sleep(sleepFor)
            catch case _: InterruptedException => ()  // remote message or interrupt
          // Unblock any timeout-receive whose deadline has now passed.
          earliestBlock match
            case Some((id, d)) if System.currentTimeMillis() >= d =>
              rt.blocked.remove(id) match
                case Some((_, _, k, _, _)) =>
                  rt.pending(id) = k(Value.InstanceV("None", Map.empty))
                  rt.ready.enqueue(id)
                case None => ()
            case _ => ()
          // (Fired timers will be drained at the top of the next iteration.)
        else
          val id = rt.ready.dequeue()
          // An actor may have been re-enqueued after completion if a
          // late send arrived; skip if already done.
          if rt.pending.contains(id) then
            rt.currentId = id
            val stepResult = stepActor(rt, id, rt.pending(id))
            stepResult match
              case ActorStep.Done(v)   =>
                rt.pending.remove(id)
                if id == rootId then rootResult(rootId) = v
              case ActorStep.Suspend   =>
                // Already moved to blocked or removed by stepActor.
                ()
              case ActorStep.Yield(c)  =>
                rt.pending(id) = c
                rt.ready.enqueue(id)

      // Drain dead-letter logging for messages stuck in completed
      // actors' mailboxes — informational only, doesn't fail the run.
      // v1.23 — graceful cluster shutdown: release the coord lease if we
      // hold it, so the next leader can claim immediately.
      if leaderProtocolRef.get() == "coord" && coordIsLeader then
        coordReleaseFn match
          case Value.NativeFnV(_, _) | _: Value.FunV =>
            callCoordFn(coordReleaseFn, List(Value.StringV(localNodeId)))
          case _ => ()
        coordIsLeader = false
      // Interrupt tick threads so they don't leak across test runs.
      val rtt = raftTickThread.getAndSet(null);   if rtt != null then rtt.interrupt()
      val ctt = coordTickThread.getAndSet(null);  if ctt != null then ctt.interrupt()
      Pure(rootResult.getOrElse(rootId, Value.UnitV))
    finally
      actorRt = savedRt

  private enum ActorStep:
    case Done(value: Value)
    case Suspend
    case Yield(next: Computation)

  /** Walk one actor's Computation tree until we either complete it,
   *  hit a suspend point, or yield (re-queue for a fresh slice). */
  private def stepActor(rt: ActorRuntime, id: Long, initial: Computation): ActorStep =
    var current: Computation = initial
    while true do
      current match
        case Pure(v) =>
          return ActorStep.Done(v)

        // Bare Perform — Actor op with no continuation.
        case Perform("Actor", op, args) =>
          handleActorOp(rt, id, op, args, v => Pure(v)) match
            case Right(next) => current = next
            case Left(_)     => return ActorStep.Suspend

        case Perform(eff, op, _) =>
          throw InterpretError(s"Unhandled effect inside actor: $eff.$op")

        case FlatMap(sub, f) => sub match
          case Pure(v) =>
            current = f(v)
          case FlatMap(sub2, g) =>
            current = FlatMap(sub2, x => FlatMap(g(x), f))
          case Perform("Actor", op, args) =>
            handleActorOp(rt, id, op, args, f) match
              case Right(next) => current = next
              case Left(_)     => return ActorStep.Suspend
          case Perform(eff, op, _) =>
            throw InterpretError(s"Unhandled effect inside actor: $eff.$op")
    throw InterpretError("unreachable")

  /** Handle one Actor effect op.
   *  Right(next) — continue stepping this actor with `next`.
   *  Left(())   — actor suspended; driver should move on. */
  private def handleActorOp(
      rt: ActorRuntime,
      id: Long,
      op: String,
      args: List[Value],
      k:  Value => Computation
  ): Either[Unit, Computation] = op match
    case "spawn" =>
      val thunk = args.head
      val childId = rt.nextId
      rt.nextId += 1
      rt.mailboxes(childId) = new java.util.concurrent.LinkedBlockingQueue[Value]()
      // Eagerly build the child's initial Computation by calling the
      // thunk with no args.  Defer actually stepping it until the
      // scheduler picks the child off the ready queue.
      val savedCurrent = rt.currentId
      rt.currentId = childId
      val childBody =
        try callValue(thunk, Nil, Map.empty)
        finally rt.currentId = savedCurrent
      rt.pending(childId) = childBody
      rt.ready.enqueue(childId)
      Right(k(mkPid("", childId)))

    case "spawnLink" =>
      val thunk = args.head
      val childId = rt.nextId
      rt.nextId += 1
      rt.mailboxes(childId) = new java.util.concurrent.LinkedBlockingQueue[Value]()
      val savedCurrent = rt.currentId
      rt.currentId = childId
      val childBody =
        try callValue(thunk, Nil, Map.empty)
        finally rt.currentId = savedCurrent
      rt.pending(childId) = childBody
      rt.ready.enqueue(childId)
      // Atomic bidirectional link before returning
      rt.links.getOrElseUpdate(id,      mutable.Set.empty) += childId
      rt.links.getOrElseUpdate(childId, mutable.Set.empty) += id
      Right(k(mkPid("", childId)))

    case "spawnBounded" =>
      val capL     = args(0) match { case Value.IntV(n) => n; case _ => 0L }
      val strategy = args(1) match { case Value.StringV(s) => s; case _ => "DropNewest" }
      val thunk = args(2)
      val childId = rt.nextId
      rt.nextId += 1
      rt.mailboxes(childId) = new java.util.concurrent.LinkedBlockingQueue[Value]()
      rt.mailboxCaps(childId) = capL.toInt
      rt.mailboxOverflow(childId) = strategy
      val savedCurrent = rt.currentId
      rt.currentId = childId
      val childBody =
        try callValue(thunk, Nil, Map.empty)
        finally rt.currentId = savedCurrent
      rt.pending(childId) = childBody
      rt.ready.enqueue(childId)
      Right(k(mkPid("", childId)))

    case "self" =>
      Right(k(mkPid(localNodeId, id)))

    case "processInfo" => args match
      case List(Value.InstanceV("Pid", fields)) =>
        val targetId = fields.get("localId").collect { case Value.IntV(n) => n }.getOrElse(-1L)
        if !rt.mailboxes.contains(targetId) then
          Right(k(Value.OptionV(None)))
        else
          val mailboxSize = rt.mailboxes.get(targetId).map(_.size).getOrElse(0)
          val links = rt.links.get(targetId)
            .map(_.toList.map(lid => mkPid("", lid)))
            .getOrElse(Nil)
          val status = if rt.blocked.contains(targetId) then "blocked" else "running"
          val info = Value.InstanceV("ProcessInfo", Map(
            "mailboxSize" -> Value.IntV(mailboxSize),
            "links"       -> Value.ListV(links),
            "status"      -> Value.StringV(status)
          ))
          Right(k(Value.OptionV(Some(info))))
      case _ => throw InterpretError("processInfo(pid)")

    case "send" => args match
      case List(Value.InstanceV("Pid", fields), msg) =>
        val pidNode = fields.get("nodeId").collect { case Value.StringV(n) => n }.getOrElse("")
        if pidNode.nonEmpty && pidNode != localNodeId then
          remoteDeliverOrQueue(pidNode, fields, msg)
          Right(k(Value.UnitV))
        else
        fields.get("localId") match
          case Some(Value.IntV(targetId)) =>
            rt.mailboxes.get(targetId) match
              case Some(mb) =>
                // Bounded mailbox: apply overflow strategy if at capacity.
                val delivered = rt.mailboxCaps.get(targetId) match
                  case Some(cap) if mb.size() >= cap =>
                    rt.mailboxOverflow.getOrElse(targetId, "DropNewest") match
                      case "DropOldest" =>
                        mb.poll()
                        mb.offer(msg)
                        true
                      case "DropNewest" =>
                        false
                      case "Fail" =>
                        killActor(rt, id, Value.StringV("mailbox_overflow"))
                        return if rt.mailboxes.contains(id) then Right(k(Value.UnitV)) else Left(())
                      case "Block" =>
                        rt.blockedSends.getOrElseUpdate(targetId, mutable.Queue.empty)
                          .enqueue((id, msg, k))
                        return Left(())
                      case _ =>
                        mb.offer(msg); true
                  case _ =>
                    mb.offer(msg); true
                if delivered then
                  rt.blocked.get(targetId) match
                    case Some((cases, blockedEnv, blockedK, _, wrapSome)) =>
                      tryDeliver(rt, targetId, cases, blockedEnv, blockedK, wrapSome) match
                        case Some(newPending) =>
                          rt.blocked.remove(targetId)
                          rt.pending(targetId) = newPending
                          rt.ready.enqueue(targetId)
                        case None => ()
                    case None => ()
              case None =>
                // Send to dead PID — silent no-op, Erlang semantics.
                ()
          case _ => ()
        Right(k(Value.UnitV))
      case _ => throw InterpretError("send(pid, msg) expects a Pid and a value")

    case "receive" => args match
      case List(Value.IntV(specId)) =>
        val (cases, recvEnv) = receiveSpecs(specId)
        tryDeliver(rt, id, cases, recvEnv, k, wrapSome = false) match
          case Some(next) => Right(next)
          case None       =>
            rt.blocked(id) = (cases, recvEnv, k, None, false)
            Left(())
      case _ => throw InterpretError("receive expects an internal spec token")

    case "receive_t" => args match
      case List(Value.IntV(specId), Value.IntV(timeoutMs)) =>
        val (cases, recvEnv) = receiveSpecs(specId)
        tryDeliver(rt, id, cases, recvEnv, k, wrapSome = true) match
          case Some(next) => Right(next)
          case None       =>
            val deadline = System.currentTimeMillis() + timeoutMs
            rt.blocked(id) = (cases, recvEnv, k, Some(deadline), true)
            Left(())
      case _ => throw InterpretError("receive(timeout = N) — internal arg mismatch")

    case "exit" => args match
      case List(Value.InstanceV("Pid", fields), reason) =>
        fields.get("localId") match
          case Some(Value.IntV(targetId)) => killActor(rt, targetId, reason)
          case _                          => ()
        // Self-exit or link propagation may have killed the current actor.
        if rt.mailboxes.contains(id) then Right(k(Value.UnitV))
        else Left(())
      case _ => throw InterpretError("exit(pid, reason)")

    // ── Phase 2 — supervision ────────────────────────────────────────

    case "link" => args match
      case List(Value.InstanceV("Pid", fields)) =>
        val nid      = fields.get("nodeId").collect { case Value.StringV(n) => n }.getOrElse("")
        fields.get("localId") match
          case Some(Value.IntV(targetId)) =>
            if nid.nonEmpty && nid != localNodeId then
              // Remote pid — register cross-node link; fire noproc if peer not connected.
              if peerChannels.containsKey(nid) then
                remoteLinks.computeIfAbsent(nid, _ =>
                  new java.util.concurrent.CopyOnWriteArrayList()).add((id, targetId))
              else
                val noproc = Value.InstanceV("noproc", Map.empty)
                if rt.trapExit.getOrElse(id, false) then
                  val exitMsg = Value.InstanceV("Exit", Map(
                    "from" -> mkPid(nid, targetId), "reason" -> noproc))
                  rt.mailboxes.get(id).foreach(_.offer(exitMsg))
                else killActor(rt, id, noproc)
            else if rt.mailboxes.contains(targetId) then
              // Both alive — create bidirectional link
              rt.links.getOrElseUpdate(id,       mutable.Set.empty) += targetId
              rt.links.getOrElseUpdate(targetId, mutable.Set.empty) += id
            else
              // Target already dead — noproc exit signal
              val noproc = Value.InstanceV("noproc", Map.empty)
              if rt.trapExit.getOrElse(id, false) then
                val exitMsg = Value.InstanceV("Exit", Map(
                  "from"   -> mkPid("", targetId),
                  "reason" -> noproc))
                rt.mailboxes.get(id).foreach(_.offer(exitMsg))
              else
                killActor(rt, id, noproc)
          case _ => ()
        // Link to dead target with no trapExit kills us — check before continuing.
        if rt.mailboxes.contains(id) then Right(k(Value.UnitV))
        else Left(())
      case _ => throw InterpretError("link(pid)")

    case "monitor" => args match
      case List(Value.InstanceV("Pid", fields)) =>
        val nid      = fields.get("nodeId").collect { case Value.StringV(n) => n }.getOrElse("")
        val monRef   = rt.nextMonRef; rt.nextMonRef += 1
        fields.get("localId") match
          case Some(Value.IntV(targetId)) =>
            if nid.nonEmpty && nid != localNodeId then
              // Remote pid — register cross-node monitor; fire noproc if not connected.
              if peerChannels.containsKey(nid) then
                remoteMonitors.computeIfAbsent(nid, _ =>
                  new java.util.concurrent.CopyOnWriteArrayList()).add((id, monRef, targetId))
              else
                val downMsg = Value.InstanceV("Down", Map(
                  "ref"    -> Value.IntV(monRef),
                  "from"   -> mkPid(nid, targetId),
                  "reason" -> Value.InstanceV("noconnection", Map.empty)))
                rt.mailboxes.get(id).foreach(_.offer(downMsg))
                wakeBlocked(rt, id)
              Right(k(Value.IntV(monRef)))
            else if rt.mailboxes.contains(targetId) then
              rt.monitors.getOrElseUpdate(targetId, mutable.Map.empty)(monRef) = id
              Right(k(Value.IntV(monRef)))
            else
              // Already dead — immediately deliver Down
              val downMsg = Value.InstanceV("Down", Map(
                "ref"    -> Value.IntV(monRef),
                "from"   -> mkPid("", targetId),
                "reason" -> Value.InstanceV("noproc", Map.empty)))
              rt.mailboxes.get(id).foreach(_.offer(downMsg))
              wakeBlocked(rt, id)
              Right(k(Value.IntV(monRef)))
          case _ => Right(k(Value.IntV(-1L)))
      case _ => throw InterpretError("monitor(pid)")

    case "demonitor" => args match
      case List(Value.IntV(monRef)) =>
        rt.monitors.foreachEntry((_, monMap) => monMap.remove(monRef))
        Right(k(Value.UnitV))
      case _ => throw InterpretError("demonitor(ref)")

    case "trapExit" => args match
      case List(Value.BoolV(b)) =>
        rt.trapExit(id) = b
        Right(k(Value.UnitV))
      case _ => throw InterpretError("trapExit(on: Boolean)")

    // ── Phase 3 — distributed actor ops ─────────────────────────────────

    case "startNode" =>
      val (nodeId, url) = args match
        case List(Value.StringV(n))                       => (n, "")
        case List(Value.StringV(n), Value.StringV(u))     => (n, u)
        case _ => throw InterpretError("startNode(nodeId, url?)")
      localNodeId  = nodeId
      localNodeUrl = url
      // Register /_ssc-actors WS route so inbound peer connections are accepted.
      val peersRoute = Value.NativeFnV("_ssc-actors.handler", args => {
        val ws = args.head
        val wsFields = ws match
          case Value.InstanceV(_, flds) => flds
          case _ => Map.empty[String, Value]
        def wsRecv(): Option[String] = wsFields.get("recv").flatMap { f =>
          invoke(f, Nil) match
            case Value.OptionV(Some(Value.StringV(s))) => Some(s)
            case _ => None
        }
        def wsSend(text: String): Unit = wsFields.get("send").foreach { f =>
          invoke(f, List(Value.StringV(text)))
        }
        // v1.23 — WsProxy dispatches every upgraded WS handler onto a
        // single-thread `wsExecutor`.  If we ran the blocking handshake
        // + recv loop directly here, the FIRST inbound peer monopolises
        // the executor and all subsequent inbound peers stall in the
        // queue, never completing their handshake — fragmenting the
        // cluster.  Spawn a virtual thread so the executor returns
        // immediately and remains free to process the next upgrade.
        Thread.ofVirtual().start { () =>
        // Receive handshake from peer: {"nodeId":"..."}
        val firstMsgOpt = wsRecv()
        if clusterDebug then out.println(s"[cluster:dbg] $localNodeId <- inbound handshake recv=${firstMsgOpt.map(s => if s.length > 80 then s.take(80) + "…" else s).getOrElse("<none>")}")
        val peerNodeId = firstMsgOpt.flatMap { first =>
          JsonParser.parseOption(first).collect {
            case Value.MapV(m) =>
              m.get(Value.StringV("nodeId")).collect { case Value.StringV(n) => n }.getOrElse("")
          }.filter(_.nonEmpty)
        }
        if clusterDebug then out.println(s"[cluster:dbg] $localNodeId <- inbound peerNodeId=${peerNodeId.getOrElse("<none>")}")
        peerNodeId.foreach { pnId =>
          wsSend(s"""{"nodeId":${jsonStr(localNodeId)}}""")
          peerChannels.put(pnId, wsSend)
          peerLastPong.put(pnId, System.currentTimeMillis())
          enqueueClusterEvent("NodeJoined", pnId)
          sendConfigSnapshot(wsSend)
          sendDrainState(wsSend)
          sendMetricSnapshot(wsSend)
          sendAtomicSnapshot(wsSend)
          // Heartbeat: same configurable cadence as the outbound side
          // (`setHeartbeatTimeout`).  Defaults 30 s / 40 s.
          val hbThread = Thread.ofVirtual().start { () =>
            try
              while peerChannels.containsKey(pnId) do
                Thread.sleep(peerHeartbeatIntervalMs)
                if peerChannels.containsKey(pnId) then
                  val age = System.currentTimeMillis() - peerLastPong.getOrDefault(pnId, 0L)
                  if age > peerHeartbeatDeadAfterMs then
                    // v1.23 — same rationale as the outbound side:
                    // run the full peer-loss cleanup so currentLeader
                    // / autoReelect fire even when the recv loop is
                    // stuck on a half-open socket.  Inbound has no
                    // URL/token so reconnect-from-here is a no-op.
                    peerLost(pnId, "", "")
                  else
                    try peerChannels.get(pnId)("""{"t":"ping"}""") catch case _ => ()
            catch case _: InterruptedException => ()
          }
          var running = true
          while running do
            wsRecv() match
              case None      => running = false
              case Some(msg) => dispatchPeerEnvelope(pnId, msg)
          hbThread.interrupt()
          peerLost(pnId, "", "")
        }
        } // close Thread.ofVirtual().start
        Pure(Value.UnitV)
      })
      wsRoutes.register(
        path      = "/_ssc-actors",
        handler   = peersRoute,
        interp    = this,
        protocols = List("ssc-actors-v1")
      )
      // v1.23 — operational status endpoint.  Returns a JSON snapshot
      // of cluster state (this node's view of leader, members, drain,
      // protocol, raft term) so operators can probe a live cluster
      // without touching the actor scheduler.
      registerClusterStatusRoute()
      // v1.23 — POST /_ssc-cluster/drain to toggle local drain state
      // remotely (via `ssc cluster drain <url>` or any HTTP client).
      registerClusterDrainRoute()
      // v1.23 — GET /_ssc-cluster/events returns the bounded ring
      // buffer of recent cluster events as a JSON array.
      registerClusterEventsRoute()
      // v1.23 — GET /_ssc-cluster/metrics-prom — Prometheus
      // exposition format for `clusterMetrics` gauges so existing
      // scrape-based monitoring picks them up natively.
      registerClusterMetricsPromRoute()
      // v1.23 — POST /_ssc-cluster/step-down — graceful leader
      // step-down for rolling restarts; cluster auto-re-elects
      // (assuming setAutoReelect(true) on survivors).
      registerClusterStepDownRoute()
      Right(k(Value.UnitV))

    case "connectNode" => args match
      case List(Value.StringV(url)) => connectPeer(url, ""); Right(k(Value.UnitV))
      case List(Value.StringV(url), Value.StringV(token)) => connectPeer(url, token); Right(k(Value.UnitV))
      case _ => throw InterpretError("connectNode(url)")

    case "joinCluster" => args match
      case List(seedsVal, Value.StringV(tok)) =>
        joinMode  = true
        joinToken = tok
        def collectUrls(v: Value): List[String] = v match
          case Value.ListV(items) => items.flatMap(collectUrls)
          case Value.StringV(s)  => List(s)
          case _                 => Nil
        collectUrls(seedsVal).foreach(url => connectPeer(url, tok))
        Right(k(Value.UnitV))
      case _ => throw InterpretError("joinCluster(seeds, token)")

    case "register" => args match
      case List(Value.StringV(name), Value.InstanceV("Pid", fields)) =>
        val localId = fields.get("localId").collect { case Value.IntV(n) => n }.getOrElse(id)
        nodeRegistry.put(name, localId)
        Right(k(Value.UnitV))
      case _ => throw InterpretError("register(name, pid)")

    case "whereis" => args match
      case List(Value.StringV(name)) =>
        val result =
          if nodeRegistry.containsKey(name) && rt.mailboxes.contains(nodeRegistry.get(name)) then
            Value.OptionV(Some(mkPid(localNodeId, nodeRegistry.get(name))))
          else
            Value.OptionV(None)
        Right(k(result))
      case _ => throw InterpretError("whereis(name)")

    // v1.6.x — cluster-wide registry
    case "globalRegister" => args match
      case List(Value.StringV(name), Value.InstanceV("Pid", fields)) =>
        // Local-spawn Pids carry an empty nodeId.  Stamp the local
        // node identity onto the registered Pid so cross-node
        // lookups can route back here.  Without this, remote nodes
        // store a Pid with nodeId="" and `send` falls through to the
        // local mailbox lookup, which is empty.
        val rawNid = fields.get("nodeId").collect { case Value.StringV(s) => s }.getOrElse("")
        val nid    = if rawNid.nonEmpty then rawNid else localNodeId
        val lid    = fields.get("localId").collect { case Value.IntV(n) => n }.getOrElse(0L)
        val stampedPid = mkPid(nid, lid)
        globalRegistry.put(name, stampedPid)
        val payload =
          s"""{"t":"global_reg","name":${jsonStr(name)},"nodeId":${jsonStr(nid)},"localId":${jsonStr(lid.toString)}}"""
        val peers = scala.collection.mutable.ListBuffer.empty[String]
        peerChannels.keySet().forEach(peers += _)
        if clusterDebug then out.println(
          s"[cluster:dbg] $localNodeId globalRegister($name) nid=$nid lid=$lid → ${peers.mkString(",")}")
        peerChannels.forEach { (_, send) => try send(payload) catch case _: Throwable => () }
        Right(k(Value.UnitV))
      case _ => throw InterpretError("globalRegister(name, pid)")

    case "globalWhereis" => args match
      case List(Value.StringV(name)) =>
        val result = Option(globalRegistry.get(name)) match
          case Some(pid) => Value.OptionV(Some(pid))
          case None      => Value.OptionV(None)
        Right(k(result))
      case _ => throw InterpretError("globalWhereis(name)")

    // v1.23 — cluster visibility
    case "clusterMembers" =>
      val keys = peerChannels.keySet().iterator
      val buf  = scala.collection.mutable.ListBuffer[Value]()
      while keys.hasNext do buf += Value.StringV(keys.next())
      Right(k(Value.ListV(buf.toList)))

    case "subscribeClusterEvents" =>
      val boxed = java.lang.Long.valueOf(id)
      if !clusterEventSubs.contains(boxed) then clusterEventSubs.add(boxed)
      Right(k(Value.UnitV))

    // v1.23 — phi-accrual failure detector
    case "phiOf" => args match
      case List(Value.StringV(nid)) =>
        Right(k(Value.DoubleV(computePhi(nid))))
      case _ => throw InterpretError("phiOf(nodeId)")

    case "isSuspect" => args match
      case List(Value.StringV(nid), Value.DoubleV(thr)) =>
        Right(k(Value.BoolV(computePhi(nid) >= thr)))
      case _ => throw InterpretError("isSuspect(nodeId, threshold)")

    // v1.23 — local node identity
    case "selfNode" =>
      Right(k(Value.StringV(localNodeId)))

    // v1.23 — cluster health (phi vector for connected peers)
    case "clusterHealth" =>
      val m = scala.collection.mutable.Map[Value, Value]()
      val it = peerChannels.keySet().iterator
      while it.hasNext do
        val nid = it.next()
        m += (Value.StringV(nid) -> Value.DoubleV(computePhi(nid)))
      Right(k(Value.MapV(m.toMap)))

    // v1.23 — cluster-wide failure detector
    case "broadcastHealth" =>
      // Build current phi vector and send to every connected peer.
      val sb = new StringBuilder("""{"t":"phi_vector","from":""")
      sb.append(jsonStr(localNodeId)).append(""","view":[""")
      var first = true
      val it = peerChannels.keySet().iterator
      while it.hasNext do
        val nid = it.next()
        val phi = computePhi(nid)
        // Skip non-finite phi to keep the JSON valid (Infinity / NaN aren't legal).
        if !phi.isInfinite && !phi.isNaN then
          if !first then sb.append(',')
          sb.append("[").append(jsonStr(nid)).append(',').append(phi).append(']')
          first = false
      sb.append("]}")
      val payload = sb.toString
      peerChannels.forEach { (_, send) => try send(payload) catch case _: Throwable => () }
      Right(k(Value.UnitV))

    case "clusterIsDown" => args match
      case List(Value.StringV(target), Value.DoubleV(thr)) =>
        var votes = 0
        var total = 0
        // Local view.
        if peerChannels.containsKey(target) then
          total += 1
          if computePhi(target) >= thr then votes += 1
        // Each peer's view of `target` (excluding `target` itself).
        peerPhiViews.forEach { (peerNid, peerView) =>
          if peerNid != target then
            val p = peerView.get(target)
            if p != null then
              total += 1
              if p.doubleValue() >= thr then votes += 1
        }
        // Majority of available votes.
        val majority = (total + 1) / 2
        Right(k(Value.BoolV(total > 0 && votes >= majority)))
      case _ => throw InterpretError("clusterIsDown(nodeId, threshold)")

    // v1.23 — leader election (Bully or Raft, picked by leaderProtocolRef)
    case "electLeader" =>
      leaderProtocolRef.get() match
        case "raft" => startRaftElection()
        case _      => startElection()
      Right(k(Value.UnitV))

    case "currentLeader" =>
      leaderProtocolRef.get() match
        case "raft" => Right(k(Value.StringV(raftLeaderId)))
        case "coord" =>
          val held = coordHolderFn match
            case Value.NativeFnV(_, _) | _: Value.FunV =>
              callCoordFn(coordHolderFn, Nil) match
                case Value.OptionV(Some(Value.StringV(s))) => s
                case Value.InstanceV("Some", m)            =>
                  m.get("value").collect { case Value.StringV(s) => s }.getOrElse("")
                case _ => ""
            case _ => ""
          Right(k(Value.StringV(held)))
        case _      => Right(k(Value.StringV(currentLeader.get())))

    case "subscribeLeaderEvents" =>
      val boxed = java.lang.Long.valueOf(id)
      if !leaderEventSubs.contains(boxed) then leaderEventSubs.add(boxed)
      Right(k(Value.UnitV))

    case "setAutoReelect" => args match
      case List(Value.BoolV(b)) =>
        autoReelect = b
        Right(k(Value.UnitV))
      case _ => throw InterpretError("setAutoReelect(enabled: Boolean): Unit")

    // v1.23 — protocol switch + history (cluster-raft.md §6)
    case "useRaftLeaderElection" =>
      leaderProtocolRef.set("raft")
      raftLoad()
      raftStateName   = "follower"
      raftElectionDue = System.currentTimeMillis() + raftRandTimeout
      ensureRaftTickThread()
      Right(k(Value.UnitV))

    case "useExternalCoordinator" => args match
      case List(acquire, renew, release, holder) =>
        leaderProtocolRef.set("coord")
        coordAcquireFn = acquire
        coordRenewFn   = renew
        coordReleaseFn = release
        coordHolderFn  = holder
        acquire match
          case Value.NativeFnV(_, _) | _: Value.FunV =>
            callCoordFn(acquire,
              List(Value.StringV(localNodeId), Value.IntV(CoordLeaseTimeoutMs))) match
              case Value.BoolV(true) =>
                coordIsLeader = true
                val prev = currentLeader.getAndSet(localNodeId)
                // Record every accepted claim — same reasoning as in
                // `raftAdoptLeader`: single-node mode has empty
                // `localNodeId`, the old guard skipped the initial
                // history entry.  `enqueueLeaderEvent` stays guarded.
                recordLeaderHist(localNodeId)
                if prev != localNodeId then
                  enqueueLeaderEvent("LeaderElected", localNodeId)
              case _ => ()
            ensureCoordTickThread()
          case _ => ()
        Right(k(Value.UnitV))
      case _ => throw InterpretError(
        "useExternalCoordinator(acquireLease, renewLease, releaseLease, currentHolder)")

    case "leaderProtocol" =>
      Right(k(Value.StringV(leaderProtocolRef.get())))

    case "leaderHistory" =>
      val buf = scala.collection.mutable.ListBuffer.empty[Value]
      leaderHist.iterator().forEachRemaining { case (term, lid, ms) =>
        // Tuple-as-list keeps the wire shape uniform with the JVM/JS halves.
        val t = scala.collection.mutable.ListBuffer.empty[Value]
        t += Value.IntV(term)
        t += Value.StringV(lid)
        t += Value.IntV(ms)
        buf += Value.ListV(t.toList)
      }
      Right(k(Value.ListV(buf.toList)))

    // v1.23 — cluster-endpoint shared-secret
    case "setClusterAuthToken" => args match
      case List(Value.StringV(t)) =>
        clusterAuthToken = t
        Right(k(Value.UnitV))
      case _ => throw InterpretError("setClusterAuthToken(token: String)")

    // v1.23 — cluster-wide atomic counters (LWW gossip; locally atomic).
    case "clusterAtomicGet" => args match
      case List(Value.StringV(name)) =>
        val entry = clusterAtomics.get(name)
        val v = if entry == null then 0L else entry._1.get()
        Right(k(Value.IntV(v)))
      case _ => throw InterpretError("clusterAtomicGet(name)")

    case "clusterAtomicSet" => args match
      case List(Value.StringV(name), Value.IntV(v)) =>
        val ts = System.currentTimeMillis()
        applyAtomicUpdate(name, v, ts, localNodeId)
        val payload =
          s"""{"t":"atom_set","name":${jsonStr(name)},"value":$v,""" +
          s""""ts":$ts,"origin":${jsonStr(localNodeId)}}"""
        peerChannels.forEach { (_, send) =>
          try send(payload) catch case _: Throwable => ()
        }
        Right(k(Value.IntV(v)))
      case _ => throw InterpretError("clusterAtomicSet(name, value)")

    case "clusterAtomicAdd" => args match
      case List(Value.StringV(name), Value.IntV(delta)) =>
        val entry = clusterAtomics.computeIfAbsent(name, _ =>
          (new java.util.concurrent.atomic.AtomicLong(0L),
           new java.util.concurrent.atomic.AtomicLong(0L),
           localNodeId))
        // Locally atomic — addAndGet is the AtomicLong primitive.
        val newVal = entry._1.addAndGet(delta)
        val ts = System.currentTimeMillis()
        entry._2.set(ts)
        // Same-tuple in-place update of origin: rebuild the entry so
        // future LWW comparisons see this node as origin.
        clusterAtomics.put(name, (entry._1, entry._2, localNodeId))
        val payload =
          s"""{"t":"atom_set","name":${jsonStr(name)},"value":$newVal,""" +
          s""""ts":$ts,"origin":${jsonStr(localNodeId)}}"""
        peerChannels.forEach { (_, send) =>
          try send(payload) catch case _: Throwable => ()
        }
        Right(k(Value.IntV(newVal)))
      case _ => throw InterpretError("clusterAtomicAdd(name, delta)")

    case "clusterAtomicCompareAndSet" => args match
      case List(Value.StringV(name), Value.IntV(expect), Value.IntV(update)) =>
        val entry = clusterAtomics.computeIfAbsent(name, _ =>
          (new java.util.concurrent.atomic.AtomicLong(0L),
           new java.util.concurrent.atomic.AtomicLong(0L),
           localNodeId))
        val swapped = entry._1.compareAndSet(expect, update)
        if swapped then
          val ts = System.currentTimeMillis()
          entry._2.set(ts)
          clusterAtomics.put(name, (entry._1, entry._2, localNodeId))
          val payload =
            s"""{"t":"atom_set","name":${jsonStr(name)},"value":$update,""" +
            s""""ts":$ts,"origin":${jsonStr(localNodeId)}}"""
          peerChannels.forEach { (_, send) =>
            try send(payload) catch case _: Throwable => ()
          }
        Right(k(Value.BoolV(swapped)))
      case _ => throw InterpretError("clusterAtomicCompareAndSet(name, expect, update)")

    // v1.23 — cluster-wide pub/sub
    case "publish" => args match
      case List(Value.StringV(topic), msg) =>
        // Deliver locally.
        localPublish(topic, msg)
        // Broadcast to peers — each dispatches to its own local subs.
        val body = ValueSerializer.serialize(msg)
        val payload = s"""{"t":"pub","topic":${jsonStr(topic)},"body":$body}"""
        peerChannels.forEach { (_, send) =>
          try send(payload) catch case _: Throwable => ()
        }
        Right(k(Value.UnitV))
      case _ => throw InterpretError("publish(topic, msg)")

    case "subscribePublish" => args match
      case List(Value.StringV(topic)) =>
        val boxed = java.lang.Long.valueOf(id)
        val subs  = publishSubs.computeIfAbsent(topic, _ =>
          new java.util.concurrent.CopyOnWriteArrayList[java.lang.Long]())
        if !subs.contains(boxed) then subs.add(boxed)
        Right(k(Value.UnitV))
      case _ => throw InterpretError("subscribePublish(topic)")

    case "unsubscribePublish" => args match
      case List(Value.StringV(topic)) =>
        val subs = publishSubs.get(topic)
        if subs != null then subs.remove(java.lang.Long.valueOf(id))
        Right(k(Value.UnitV))
      case _ => throw InterpretError("unsubscribePublish(topic)")

    // v1.23 — quorum-aware Bully threshold
    case "setQuorumSize" => args match
      case List(Value.IntV(n)) =>
        quorumSize = n.max(0L)
        Right(k(Value.UnitV))
      case _ => throw InterpretError("setQuorumSize(n: Long)")

    // v1.23 — heartbeat cadence + deadline
    case "setHeartbeatTimeout" => args match
      case List(Value.IntV(iv), Value.IntV(dead)) =>
        peerHeartbeatIntervalMs  = iv.max(1L)
        peerHeartbeatDeadAfterMs = dead.max(peerHeartbeatIntervalMs)
        Right(k(Value.UnitV))
      case _ => throw InterpretError(
        "setHeartbeatTimeout(intervalMs: Long, deadAfterMs: Long)")

    // v1.23 — auto-reconnect policy
    case "setReconnectPolicy" => args match
      case List(Value.IntV(ini), Value.IntV(mx)) =>
        reconnectInitialMs = ini.max(0L)
        reconnectMaxMs     = mx.max(reconnectInitialMs)
        reconnectGiveUpMs  = 0L
        Right(k(Value.UnitV))
      case List(Value.IntV(ini), Value.IntV(mx), Value.IntV(giveUp)) =>
        reconnectInitialMs = ini.max(0L)
        reconnectMaxMs     = mx.max(reconnectInitialMs)
        reconnectGiveUpMs  = giveUp.max(0L)
        Right(k(Value.UnitV))
      case _ => throw InterpretError(
        "setReconnectPolicy(initialMs: Long, maxMs: Long, giveUpAfterMs: Long = 0)")

    // v1.23 — periodic gossip re-discovery: ask every connected peer for
    // its peer-URL list.  Replies come back via the existing `peers_resp`
    // handler and feed `connectPeer` for any unknown URL.
    case "requestGossip" =>
      val payload = s"""{"t":"peers_req","from":${jsonStr(localNodeId)}}"""
      peerChannels.forEach { (_, send) => try send(payload) catch case _: Throwable => () }
      Right(k(Value.UnitV))

    // v1.23 — cluster configuration distribution
    case "clusterConfigSet" => args match
      case List(Value.StringV(key), Value.StringV(value)) =>
        val ts   = System.currentTimeMillis()
        val orig = localNodeId
        applyConfigUpdate(key, value, ts, orig)
        val payload =
          s"""{"t":"config_set","key":${jsonStr(key)},"value":${jsonStr(value)},""" +
          s""""ts":$ts,"origin":${jsonStr(orig)}}"""
        peerChannels.forEach { (_, send) => try send(payload) catch case _: Throwable => () }
        Right(k(Value.UnitV))
      case _ => throw InterpretError("clusterConfigSet(key: String, value: String)")

    case "clusterConfigGet" => args match
      case List(Value.StringV(key)) =>
        val entry = clusterConfig.get(key)
        val result =
          if entry == null then Value.OptionV(None)
          else Value.OptionV(Some(Value.StringV(entry._1)))
        Right(k(result))
      case _ => throw InterpretError("clusterConfigGet(key: String): Option[String]")

    case "clusterConfigKeys" =>
      val buf = scala.collection.mutable.ListBuffer.empty[Value]
      clusterConfig.keySet().forEach(s => buf += Value.StringV(s))
      Right(k(Value.ListV(buf.toList)))

    case "subscribeConfigEvents" =>
      val boxed = java.lang.Long.valueOf(id)
      if !configEventSubs.contains(boxed) then configEventSubs.add(boxed)
      Right(k(Value.UnitV))

    // v1.23 — drain / rolling-restart
    case "setDraining" => args match
      case List(Value.BoolV(b)) =>
        val prev = isDrainingSelf.getAndSet(b)
        if prev != b then
          val payload = s"""{"t":"drain","from":${jsonStr(localNodeId)},"draining":$b}"""
          peerChannels.forEach { (_, send) => try send(payload) catch case _: Throwable => () }
          enqueueDrainEvent(localNodeId, b)
          if b then stepDownIfLeader()
        Right(k(Value.UnitV))
      case _ => throw InterpretError("setDraining(enabled: Boolean)")

    case "isDraining" =>
      Right(k(Value.BoolV(isDrainingSelf.get())))

    case "drainingPeers" =>
      val buf = scala.collection.mutable.ListBuffer.empty[Value]
      drainingPeers.forEach { (nid, v) =>
        if v != null && v.booleanValue() then buf += Value.StringV(nid)
      }
      Right(k(Value.ListV(buf.toList)))

    case "subscribeDrainEvents" =>
      val boxed = java.lang.Long.valueOf(id)
      if !drainEventSubs.contains(boxed) then drainEventSubs.add(boxed)
      Right(k(Value.UnitV))

    // v1.23 — cluster metrics aggregation
    case "clusterMetricSet" => args match
      case List(Value.StringV(name), Value.DoubleV(v)) =>
        applyMetricUpdate(name, localNodeId, v)
        val payload =
          s"""{"t":"metric","from":${jsonStr(localNodeId)},"name":${jsonStr(name)},"value":$v}"""
        peerChannels.forEach { (_, send) => try send(payload) catch case _: Throwable => () }
        Right(k(Value.UnitV))
      case List(Value.StringV(name), Value.IntV(v)) =>
        val d = v.toDouble
        applyMetricUpdate(name, localNodeId, d)
        val payload =
          s"""{"t":"metric","from":${jsonStr(localNodeId)},"name":${jsonStr(name)},"value":$d}"""
        peerChannels.forEach { (_, send) => try send(payload) catch case _: Throwable => () }
        Right(k(Value.UnitV))
      case _ => throw InterpretError("clusterMetricSet(name: String, value: Double)")

    case "clusterMetricGet" => args match
      case List(Value.StringV(name)) =>
        val inner = clusterMetrics.get(name)
        val m = scala.collection.mutable.Map[Value, Value]()
        if inner != null then
          inner.forEach { (nid, v) => m += (Value.StringV(nid) -> Value.DoubleV(v.doubleValue())) }
        Right(k(Value.MapV(m.toMap)))
      case _ => throw InterpretError("clusterMetricGet(name: String): Map[String, Double]")

    case "clusterMetricSum" => args match
      case List(Value.StringV(name)) =>
        val inner = clusterMetrics.get(name)
        var sum = 0.0
        if inner != null then
          inner.forEach { (_, v) => sum += v.doubleValue() }
        Right(k(Value.DoubleV(sum)))
      case _ => throw InterpretError("clusterMetricSum(name: String): Double")

    case "clusterMetricNames" =>
      val buf = scala.collection.mutable.ListBuffer.empty[Value]
      clusterMetrics.keySet().forEach(s => buf += Value.StringV(s))
      Right(k(Value.ListV(buf.toList)))

    case "subscribeMetricEvents" =>
      val boxed = java.lang.Long.valueOf(id)
      if !metricEventSubs.contains(boxed) then metricEventSubs.add(boxed)
      Right(k(Value.UnitV))

    // v1.6.x — scheduled sends
    case "sendAfter" => args match
      case List(Value.IntV(delayMs), Value.InstanceV("Pid", fields), msg) =>
        val targetId = fields.get("localId").collect { case Value.IntV(n) => n }.getOrElse(0L)
        val fireAt   = System.currentTimeMillis() + delayMs
        val ref      = rt.nextTimerId; rt.nextTimerId += 1
        rt.timers(ref) = (fireAt, None, targetId, msg)
        Right(k(Value.IntV(ref)))
      case _ => throw InterpretError("sendAfter(delayMs, pid, msg)")

    case "sendInterval" => args match
      case List(Value.IntV(periodMs), Value.InstanceV("Pid", fields), msg) =>
        val targetId = fields.get("localId").collect { case Value.IntV(n) => n }.getOrElse(0L)
        val fireAt   = System.currentTimeMillis() + periodMs
        val ref      = rt.nextTimerId; rt.nextTimerId += 1
        rt.timers(ref) = (fireAt, Some(periodMs), targetId, msg)
        Right(k(Value.IntV(ref)))
      case _ => throw InterpretError("sendInterval(periodMs, pid, msg)")

    case "cancelTimer" => args match
      case List(Value.IntV(ref)) =>
        rt.timers.remove(ref)
        Right(k(Value.UnitV))
      case _ => throw InterpretError("cancelTimer(ref)")

    case other =>
      throw InterpretError(s"Unknown Actor op: $other")

  /** Pop messages from the head of `id`'s mailbox; for each one,
   *  try to match a case.  Match: return Some(caseBody ⊛ k).
   *  No-match: pop, log as dead-letter, try the next.
   *  Empty mailbox: None. */
  private def tryDeliver(
      rt:        ActorRuntime,
      id:        Long,
      cases:     List[Case],
      env:       Env,
      k:         Value => Computation,
      wrapSome:  Boolean
  ): Option[Computation] =
    val mb = rt.mailboxes(id)
    while !mb.isEmpty do
      val msg = mb.peek()
      var matched: Option[Computation] = None
      val it = cases.iterator
      while matched.isEmpty && it.hasNext do
        val c = it.next()
        PatternRuntime.matchPat(c.pat, msg, env, this) match
          case Some(extEnv) =>
            val guardOk = c.cond.forall { g =>
              Computation.run(eval(g, extEnv)) match
                case Value.BoolV(b) => b
                case _              => false
            }
            if guardOk then
              val body = eval(c.body, extEnv)
              matched = Some(
                if wrapSome then body.flatMap(v =>
                  k(Value.InstanceV("Some", Map("value" -> v))))
                else body.flatMap(k)
              )
          case None => ()
      if matched.isDefined then
        mb.poll()
        resumeBlockedSender(rt, id)
        return matched
      // Dead letter: discard the head and try the next.
      out.println(s"[dead-letter] actor=$id msg=${Value.show(msg)}")
      mb.poll()
      resumeBlockedSender(rt, id)
    None

  // After consuming a message from a bounded mailbox, resume the first
  // blocked sender (if any) so they can deliver their queued message.
  private def resumeBlockedSender(rt: ActorRuntime, receiverId: Long): Unit =
    rt.mailboxCaps.get(receiverId).foreach { cap =>
      rt.mailboxes.get(receiverId).foreach { mb =>
        if mb.size() < cap then
          rt.blockedSends.get(receiverId).foreach { queue =>
            var done = false
            // Skip dead senders; resume the first live one.
            while queue.nonEmpty && !done do
              val (senderId, msg, senderK) = queue.dequeue()
              if rt.mailboxes.contains(senderId) then
                mb.offer(msg)
                rt.pending(senderId) = senderK(Value.UnitV)
                rt.ready.enqueue(senderId)
                done = true
          }
      }
    }

  // ── Phase 2 — supervision helpers ────────────────────────────────

  /** Kill actor `targetId` with `reason`.  Propagates through links:
   *  - linked actors with trapExit=false also die (recursive)
   *  - linked actors with trapExit=true receive an Exit message
   *  Monitors on the dead actor fire Down messages to observers.
   *  Idempotent: actors without a mailbox entry are already dead. */
  private def killActor(rt: ActorRuntime, targetId: Long, reason: Value): Unit =
    if !rt.mailboxes.contains(targetId) then return  // already dead

    // Mark dead immediately to prevent re-entry from circular links.
    rt.pending.remove(targetId)
    rt.blocked.remove(targetId)
    rt.mailboxes.remove(targetId)
    rt.trapExit.remove(targetId)
    // Bounded mailbox cleanup: resume blocked senders (their send becomes a silent no-op).
    rt.mailboxCaps.remove(targetId)
    rt.mailboxOverflow.remove(targetId)
    rt.blockedSends.remove(targetId).foreach { queue =>
      queue.foreach { (senderId, _, senderK) =>
        if rt.mailboxes.contains(senderId) then
          rt.pending(senderId) = senderK(Value.UnitV)
          rt.ready.enqueue(senderId)
      }
    }

    val deadPid = mkPid("", targetId)

    // Notify linked actors.
    rt.links.remove(targetId).foreach { linkedSet =>
      linkedSet.foreach { linkedId =>
        rt.links.get(linkedId).foreach(_.remove(targetId))
        if rt.trapExit.getOrElse(linkedId, false) then
          val exitMsg = Value.InstanceV("Exit", Map("from" -> deadPid, "reason" -> reason))
          rt.mailboxes.get(linkedId).foreach(_.offer(exitMsg))
          wakeBlocked(rt, linkedId)
        else
          killActor(rt, linkedId, reason)
      }
    }

    // Fire Down messages for all monitors on this actor.
    rt.monitors.remove(targetId).foreach { monMap =>
      monMap.foreach { (monRef, observerId) =>
        val downMsg = Value.InstanceV("Down", Map(
          "ref"    -> Value.IntV(monRef),
          "from"   -> deadPid,
          "reason" -> reason))
        rt.mailboxes.get(observerId).foreach(_.offer(downMsg))
        wakeBlocked(rt, observerId)
      }
    }

  /** If `id` is currently blocked on a receive and a newly enqueued
   *  message can be delivered, move it back to pending/ready. */
  private def wakeBlocked(rt: ActorRuntime, id: Long): Unit =
    rt.blocked.get(id) match
      case Some((cases, env, k, _, wrapSome)) =>
        tryDeliver(rt, id, cases, env, k, wrapSome) match
          case Some(next) =>
            rt.blocked.remove(id)
            rt.pending(id) = next
            rt.ready.enqueue(id)
          case None => ()
      case None => ()
