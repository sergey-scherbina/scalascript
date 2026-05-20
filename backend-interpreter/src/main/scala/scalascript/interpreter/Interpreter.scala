package scalascript.interpreter

import scalascript.ast.*
import scalascript.transform.DirectTypeUtils
import scala.collection.mutable
import scala.meta.*
import Computation.{Pure, Perform, FlatMap}

/** A parametric `given` definition that has type parameters and/or `using` dependencies.
 *  Stored in the interpreter's `givenFactories` registry so that `resolveGiven` can
 *  instantiate it on demand by matching the requested typeKey and recursively resolving
 *  the `using` dependencies.
 *
 *  @param name              explicit given name (or empty for anonymous)
 *  @param typeParams        type variable names, e.g. `List("A")`
 *  @param usingDeps         ordered list of (paramName, typeKeyTemplate), e.g.
 *                           `List(("ord", "Ordering[A]"))`
 *  @param returnTypeTemplate the canonical type-key template, e.g. `"Ordering[List[A]]"`
 *  @param givenNode         the original `Defn.Given` AST node (for body evaluation)
 *  @param capturedEnv       environment captured at definition time
 */
/** Monad tag for `direct[M] { ... }` do-notation blocks. */
private[interpreter] enum DirectMonadTag:
  case OptionM // direct[Option]
  case EitherM // direct[Either[E, *]]
  case AsyncM  // direct[Async]  — supports OptionT / EitherT lift
  case ListM   // direct[List]
  case OtherM  // direct[SomeUserMonad] — duck-typed only

private[interpreter] case class TcoInfo(
  tailTargets:   Set[String],
  isSelfTailRec: Boolean,
  noNonTailSelf: Boolean
)

private[interpreter] case class RestartableHandle(
  errorQ:  java.util.concurrent.SynchronousQueue[Value],
  resumeQ: java.util.concurrent.SynchronousQueue[Either[Value, Value]]
)

private[interpreter] class RestartableRethrow(val value: Value)
  extends RuntimeException(null, null, true, false)

private case class ParametricGiven(
  name:               String,
  typeParams:         List[String],
  usingDeps:          List[(String, String)],   // (paramName, typeKeyTemplate)
  returnTypeTemplate: String,
  givenNode:          Defn.Given,
  capturedEnv:        Map[String, Value]
)

/** Tree-walking interpreter for ScalaScript documents.
 *
 *  Execution model:
 *  - Sections are processed in document order.
 *  - Each scala/ssc code block is executed eagerly (defs bind, exprs run).
 *  - After all sections, `main()` is auto-called if defined and not already invoked.
 *
 *  Algebraic effects use a **Free Monad** representation: `eval` returns a
 *  `Computation` (Pure | Perform). Effect ops produce `Perform` nodes; handlers
 *  walk the tree and dispatch them. `resume(v)` invokes the captured Scala
 *  continuation directly — no replay; side effects in body run exactly once;
 *  multi-shot works by calling the continuation multiple times.
 */
class Interpreter(
    val out:  java.io.PrintStream = System.out,
    private[interpreter] val baseDir:  Option[os.Path]     = None,
    /** When true, `serve(port)` is a no-op: routes still register, but
     *  the HTTP server doesn't bind a port or block on `Thread.join`.
     *  Used by `ssc render` for static-site generation — the route
     *  table is filled in, then handlers are invoked off-band with
     *  synthetic requests. */
    headless: Boolean              = false,
    private[interpreter] val lockPath: Option[os.Path]      = None):
  private[interpreter] val globals      = mutable.Map.empty[String, Value]
  private[interpreter] val extensions   = mutable.Map.empty[(String, String), Value.FunV]
  // Concrete type → declared parent type (from `extends` clause).  Used by
  // extensionDispatch to find extension methods registered on a sealed parent.
  private[interpreter] val parentTypes  = mutable.Map.empty[String, String]
  // Methods declared inside a `class` / `case class` body, keyed by type name.
  // Stored separately from instance fields so `show` and pattern matching see
  // only data fields.
  private[interpreter] val typeMethods  = mutable.Map.empty[String, Map[String, Value.FunV]]
  // Field declaration order per type — needed for positional `.copy(...)`
  // since `InstanceV.fields` is an unordered Map for instances with more
  // than four fields.
  private[interpreter] val typeFieldOrder = mutable.Map.empty[String, List[String]]
  // Parametric given factories — givens with type parameters and/or using clauses.
  // Stored separately because they can't be stored as plain Values until their type
  // variables are resolved at the call site.
  // Each entry: (name, typeParams, usingDeps[(paramName, typeKeyTemplate)],
  //              returnTypeTemplate, givenNode, capturedEnv)
  private[interpreter] val givenFactories = mutable.ArrayBuffer.empty[ParametricGiven]
  // Track how many `given` definitions are stored under each typeKey — used for
  // ambiguity detection.  Incremented both by concrete givens (in globals) and
  // by parametric factory registrations.
  private[interpreter] val givenCandidateCount = mutable.Map.empty[String, Int]
  private var mainCalled   = false
  // ThreadLocal so concurrent generator virtual threads each get their own counter.
  private val _phIdxTL: ThreadLocal[Int] = ThreadLocal.withInitial(() => 0)
  private inline def placeholderIdx: Int          = _phIdxTL.get()
  private inline def placeholderIdx_=(v: Int): Unit = _phIdxTL.set(v)
  // Tracks the last known source position for error messages (0-based line, 0-based column).
  private var currentSpan: Option[(Int, Int)] = None
  // Source of the code block currently being executed — used to print the
  // offending line under the error message with a caret.
  private[interpreter] var currentSource: String = ""
  // When the parser falls back to wrapping the block in `{ ... }` to accept
  // top-level expressions, every scalameta position is shifted down by one
  // line. `lineOffset` compensates so error messages report the user's line.
  private[interpreter] var lineOffset: Int = 0
  // Phase 6: interpreter call stack for currentStackTrace().
  private val callStack = scala.collection.mutable.ArrayBuffer.empty[(String, Int)]
  // When true, currentStackTrace() includes anonymous (<anon>) and _-prefixed frames.
  private var traceVerbose: Boolean = false
  // Types declared with @noTrace — throw uses ScriptExceptionNoTrace to skip JVM fillInStackTrace.
  private val noTraceTypes = mutable.HashSet.empty[String]
  // Phase 3.2: flag indicating we are inside a direct[Either[...]] block so
  // throw expressions lower to Left(...) instead of raising a ScriptException.
  private[interpreter] val _insideDirectBlock = new java.lang.ThreadLocal[Boolean] {
    override def initialValue() = false
  }
  // DirectMonadTag defined at package level (see top of file / BlockRuntime.scala)

  // ─── Reactive signals (fine-grained reactivity) ──────────────────────
  //
  // Signals are mutable cells with subscriber tracking.  Reading a signal
  // inside an active `effect` block registers a mutual subscription so
  // the effect re-runs when the signal changes.  `computed` is an effect
  // whose body's return value feeds another signal — derived state.
  //
  // We store backing state in a process-local side-table keyed by a
  // monotone id; the user-visible value is an `InstanceV("Signal", {id})`
  // / `InstanceV("Effect", {id})`.  Cross-backend semantics line up
  // because all three backends use the same registry-based push model.

  private[interpreter] class SignalState(var value: Value, val subs: mutable.HashSet[Long])
  private[interpreter] class EffectState(val thunk: Value, val deps: mutable.HashSet[Long])

  private[interpreter] val signals = mutable.HashMap.empty[Long, SignalState]
  private[interpreter] val effects = mutable.HashMap.empty[Long, EffectState]
  private[interpreter] var reactiveCounter: Long = 0L
  // Stack of currently-tracking effect ids.  An effect-thunk that calls
  // another effect (rare but legal) pushes its own id while running.
  private[interpreter] val effectStack = mutable.Stack.empty[Long]
  // Pending effect reruns queued by `signalSet` while we're inside an
  // active flush.  A LinkedHashSet so each effect runs at most once per
  // synchronous transaction (deduplicates the diamond — derived signal
  // and final consumer both react to the same root change) and reruns
  // happen in registration order for determinism.
  private[interpreter] val pendingEffects = mutable.LinkedHashSet.empty[Long]

  // v1.5 Tier 5 #20 — validation collector stack.  Each `validate { … }`
  // block pushes a fresh ordered map; the `require*` natives check the
  // head of the stack: when present they record the error and return a
  // type-appropriate default so the body keeps running and accumulates
  // every problem in one pass.  When empty (handler called require*
  // outside a validate block) the call throws as before.
  private val validationStack: mutable.Stack[mutable.LinkedHashMap[String, String]] =
    mutable.Stack.empty
  private[interpreter] var reactiveFlushing = false

  // ── v1.16 Restartable errors — see EffectsRuntime.scala ─────────────
  // RestartableHandle / RestartableRethrow defined at package level above.
  private val _restartableTL =
    new ThreadLocal[java.util.ArrayDeque[RestartableHandle]]()
  private[interpreter] def restartableStack(): java.util.ArrayDeque[RestartableHandle] =
    var s = _restartableTL.get()
    if s == null then { s = new java.util.ArrayDeque(); _restartableTL.set(s) }
    s

  /** Per-FunV cache of the TCO classification — see TcoRuntime.tcoInfoFor.
   *  Keyed by FunV identity. */
  private[interpreter] val tcoCache: java.util.IdentityHashMap[Value.FunV, TcoInfo] =
    java.util.IdentityHashMap()

  /** Intern table from `Lit` AST nodes to the `Computation` they evaluate
   *  to. The parsed AST is reused across all evaluations, so for hot
   *  loop literals (`0`, `1`, `2`, …) this saves a fresh `Pure(IntV(...))`
   *  allocation on every visit. */
  private val litCache: java.util.IdentityHashMap[Lit, Computation] =
    java.util.IdentityHashMap()

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
  @volatile private var localNodeId: String = ""
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
  private val peerChannels =
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
  private val currentLeader =
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
  @volatile private var clusterAuthToken: String =
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
  private val leaderProtocolRef =
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
  @volatile private var coordIsLeader:  Boolean = false
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
  private def stepDownIfLeader(): Unit =
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
  @volatile private var raftCurrentTerm: Long   = 0L
  @volatile private var raftVotedFor:    String = ""
  @volatile private var raftStateName:   String = "follower"
  @volatile private var raftLeaderId:    String = ""
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
  private val isDrainingSelf =
    new java.util.concurrent.atomic.AtomicBoolean(false)
  private val drainingPeers =
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
  private def enqueueDrainEvent(nodeId: String, draining: Boolean): Unit =
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
  private val clusterMetrics =
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
  private val clusterEventLog =
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

  // ── v1.10 Generator — thread-per-generator, SynchronousQueue handshake ─
  // Each `generator { body }` spins a virtual thread that runs the body.
  // `suspend(v)` inside the body does queue.put(Some(v)), blocking until
  // the consumer calls .next() / .foreach() / .toList.
  // Combinators (map/filter/take/drop) chain virtual threads in a pipeline.
  private[interpreter] type GenQueue = java.util.concurrent.SynchronousQueue[Option[Value]]
  private[interpreter] val _genQueueTL = new ThreadLocal[GenQueue]()

  // ── v1.9 Coroutines — two-way suspend/resume via virtual threads ──────
  // Protocol (lazy-start):
  //   coroutineCreate: starts T but T immediately blocks on toBody.take()
  //   coroutineResume: toBody.put(in); result = fromBody.take()
  //   suspend(out):    fromBody.put(Yielded(out)); toBody.take()
  // fromBody is a capacity-1 LinkedBlockingQueue so the body can always put
  // without blocking — prevents deadlock when coroutineCancel removes the
  // handle before the body has a chance to drain.
  private[interpreter] case class CoHandle(
    fromBody:   java.util.concurrent.LinkedBlockingQueue[Value],
    toBody:     java.util.concurrent.SynchronousQueue[Value],
    bodyThread: java.util.concurrent.atomic.AtomicReference[Thread]
  )
  private[interpreter] val _coHandleTL = new ThreadLocal[CoHandle]()
  private[interpreter] val coHandles    = new java.util.concurrent.ConcurrentHashMap[Long, CoHandle]()
  private[interpreter] val nextCoId     = new java.util.concurrent.atomic.AtomicLong(0L)

  private[interpreter] def makeGeneratorV(queue: GenQueue): Value =
    import scala.collection.mutable.ListBuffer

    def startChained(bodyFn: GenQueue => Unit): Value =
      val q2 = new GenQueue()
      Thread.ofVirtual().start { () =>
        _genQueueTL.set(q2)
        try bodyFn(q2)
        catch case _: Throwable => ()
        finally try q2.put(None) catch case _ => ()
      }
      makeGeneratorV(q2)

    Value.InstanceV("Generator", Map(
      "next" -> Value.NativeFnV("Generator.next", Computation.pureFn { _ =>
        Value.OptionV(queue.take())
      }),
      "foreach" -> Value.NativeFnV("Generator.foreach", Computation.pureFn {
        case List(f) =>
          var item = queue.take()
          while item.isDefined do
            Computation.run(callValue(f, List(item.get), Map.empty))
            item = queue.take()
          Value.UnitV
        case _ => throw InterpretError("Generator.foreach(f)")
      }),
      "toList" -> Value.NativeFnV("Generator.toList", Computation.pureFn { _ =>
        val buf = ListBuffer[Value]()
        var item = queue.take()
        while item.isDefined do
          buf += item.get
          item = queue.take()
        Value.ListV(buf.toList)
      }),
      "map" -> Value.NativeFnV("Generator.map", Computation.pureFn {
        case List(f) => startChained { ownQ =>
          var item = queue.take()
          while item.isDefined do
            val mapped = Computation.run(callValue(f, List(item.get), Map.empty))
            ownQ.put(Some(mapped))
            item = queue.take()
        }
        case _ => throw InterpretError("Generator.map(f)")
      }),
      "filter" -> Value.NativeFnV("Generator.filter", Computation.pureFn {
        case List(pred) => startChained { ownQ =>
          var item = queue.take()
          while item.isDefined do
            if Computation.run(callValue(pred, List(item.get), Map.empty)) == Value.BoolV(true) then
              ownQ.put(Some(item.get))
            item = queue.take()
        }
        case _ => throw InterpretError("Generator.filter(pred)")
      }),
      "take" -> Value.NativeFnV("Generator.take", Computation.pureFn {
        case List(Value.IntV(n)) => startChained { ownQ =>
          var remaining = n
          var item = queue.take()
          while item.isDefined && remaining > 0 do
            ownQ.put(Some(item.get))
            remaining -= 1
            item = if remaining > 0 then queue.take() else None
        }
        case _ => throw InterpretError("Generator.take(n: Int)")
      }),
      "drop" -> Value.NativeFnV("Generator.drop", Computation.pureFn {
        case List(Value.IntV(n)) => startChained { ownQ =>
          var toDrop = n.toInt
          var item = queue.take()
          while item.isDefined && toDrop > 0 do
            toDrop -= 1
            item = queue.take()
          while item.isDefined do
            ownQ.put(Some(item.get))
            item = queue.take()
        }
        case _ => throw InterpretError("Generator.drop(n: Int)")
      }),
      "flatMap" -> Value.NativeFnV("Generator.flatMap", Computation.pureFn {
        case List(f) => startChained { ownQ =>
          var item = queue.take()
          while item.isDefined do
            val inner = Computation.run(callValue(f, List(item.get), Map.empty))
            inner match
              case Value.InstanceV("Generator", fields) =>
                val innerNext = fields("next")
                var sub = Computation.run(callValue(innerNext, Nil, Map.empty))
                while sub != Value.OptionV(None) do
                  sub match
                    case Value.OptionV(Some(v)) => ownQ.put(Some(v))
                    case _ =>
                  sub = Computation.run(callValue(innerNext, Nil, Map.empty))
              case _ => throw InterpretError("Generator.flatMap: body must return a Generator")
            item = queue.take()
        }
        case _ => throw InterpretError("Generator.flatMap(f)")
      }),
      "zip" -> Value.NativeFnV("Generator.zip", Computation.pureFn {
        case List(other: Value.InstanceV) => startChained { ownQ =>
          val otherNext = other.fields("next")
          var a = queue.take()
          var b = Computation.run(callValue(otherNext, Nil, Map.empty))
          while a.isDefined && b != Value.OptionV(None) do
            val bVal = b match { case Value.OptionV(Some(v)) => v; case _ => Value.UnitV }
            ownQ.put(Some(Value.TupleV(List(a.get, bVal))))
            a = queue.take()
            b = if a.isDefined then Computation.run(callValue(otherNext, Nil, Map.empty)) else Value.OptionV(None)
        }
        case _ => throw InterpretError("Generator.zip(other: Generator)")
      }),
      "zipWithIndex" -> Value.NativeFnV("Generator.zipWithIndex", Computation.pureFn { _ =>
        startChained { ownQ =>
          var idx = 0
          var item = queue.take()
          while item.isDefined do
            ownQ.put(Some(Value.TupleV(List(item.get, Value.IntV(idx)))))
            idx += 1
            item = queue.take()
        }
      }),
    ))


  // ── v1.21 Dataset — see DatasetRuntime.scala ──────────────────────────

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
        val sess = scalascript.server.WsClientSession(url, hdrs, List("ssc-actors-v1"), this, out)
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

  private def jsonStr(s: String): String =
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

  // Base URL and timeout for httpClient {} scopes — thread-local so nested calls restore correctly.
  private val _httpBaseUrl      = ThreadLocal.withInitial[String](() => "")
  private val _httpTimeoutMs    = ThreadLocal.withInitial[Long](() => 30_000L)
  private val _httpMaxRetries   = ThreadLocal.withInitial[Int](() => 0)
  private val _httpRetryDelayMs = ThreadLocal.withInitial[Long](() => 1_000L)

  // ── v1.4 Cache effect — process-local memoization store + bypass flag ──
  private[interpreter] val _cacheStore  = new java.util.concurrent.ConcurrentHashMap[String, (Long, Value)]()
  private[interpreter] val _cacheBypass = ThreadLocal.withInitial[Boolean](() => false)

  // ── Async parallel driver — future table (see AsyncRuntime.scala) ─────
  private[interpreter] val parallelFutures =
    new java.util.concurrent.ConcurrentHashMap[Long, java.util.concurrent.Future[Value]]()
  private val parallelFutureSeq = new java.util.concurrent.atomic.AtomicLong(0L)
  private[interpreter] def freshFutureId(): Long = parallelFutureSeq.incrementAndGet()

  // ── v1.4 Auth effect — current user (thread-local) ────────────────────
  private[interpreter] val _authUser = ThreadLocal.withInitial[Option[Value]](() => None)
  // Receive-spec boxing: we can't squeeze AST cases into `Value`, so
  // `receive { case … }` stashes (cases, env) in a side map and the
  // Perform's args carry just the opaque integer token.
  private val receiveSpecs    = mutable.LongMap.empty[(List[Case], Env)]
  private var receiveSpecNext = 0L

  /** Cache of `closure.updated(name, f)` per FunV — the self-ref binding
   *  is identical on every invocation of the same closure, so we save
   *  one HashMap.updated allocation per call. */
  private val closureWithSelfCache: java.util.IdentityHashMap[Value.FunV, Env] =
    java.util.IdentityHashMap()

  private def closureWithSelfFor(f: Value.FunV): Env =
    if f.name.isEmpty then f.closure
    else
      val cached = closureWithSelfCache.get(f)
      if cached != null then cached
      else
        val w = f.closure.updated(f.name, f)
        closureWithSelfCache.put(f, w)
        w

  /** Format a position prefix like "[line 5, col 3] " or "" if unknown. */
  private def posPrefix: String = currentSpan match
    case Some((line, col)) => s"[line ${line + 1}, col ${col + 1}] "
    case None              => ""

  /** Render the source line at `currentSpan` with a caret underneath, or
   *  empty string if no position / source is known. Two-line output, indented
   *  so it lines up cleanly under the error message. */
  private def sourceContext: String = currentSpan match
    case Some((line, col)) if currentSource.nonEmpty =>
      val lines = currentSource.split("\n", -1)
      if line < 0 || line >= lines.length then ""
      else
        val src    = lines(line).stripTrailing
        val gutter = s"${line + 1}"
        val pad    = " " * gutter.length
        val caret  = " " * col.max(0).min(src.length) + "^"
        s"\n  $gutter | $src\n  $pad | $caret"
    case _ => ""

  /** Prefix `msg` with position info and throw InterpretError with source
   *  context appended underneath. */
  private[interpreter] def located(msg: String): Nothing =
    throw InterpretError(s"$posPrefix$msg$sourceContext")

  /** Update currentSpan from a scalameta tree's position (no-op if position is empty). */
  private def trackPos(tree: scala.meta.Tree): Unit =
    val p = tree.pos
    if !p.isEmpty then currentSpan = Some((p.startLine - lineOffset, p.startColumn))

  // ─── Public API ──────────────────────────────────────────────────

  /** Module-level `dependencies:` from the front-matter, captured at the
   *  top of `run` so any `[Card](dep://card.ssc)` import in this module
   *  can rewrite its scheme through `ImportResolver`. */
  private[interpreter] var moduleDeps: Map[String, String] = Map.empty
  private[interpreter] var modulePkg: List[String] = Nil
  private var i18nTranslations: Map[String, Map[String, String]] = Map.empty
  private var i18nLocale: String = "en"

  /** v1.26 — JDBC connections declared in front-matter `databases:`,
   *  materialised lazily and cached.  `sql` fenced blocks resolve their
   *  connection through this registry unless a `given`-style override
   *  (a `Value.Foreign("Connection", _)` bound to the `Connection`
   *  global) is in scope.  Empty by default — modules without any
   *  `databases:` section pay no JDBC cost. */
  private[interpreter] var sqlRegistry: scalascript.sql.ConnectionRegistry =
    scalascript.sql.ConnectionRegistry.empty
  private[interpreter] var sqlBlockCounter: Int = 0

  def run(module: Module): Unit =
    initBuiltins()
    moduleDeps = module.manifest.map(_.dependencies).getOrElse(Map.empty)
    modulePkg  = module.manifest.flatMap(_.pkg).getOrElse(Nil)
    module.manifest.foreach(m => i18nTranslations = m.translations)
    module.manifest.foreach { m =>
      if m.databases.nonEmpty then
        sqlRegistry = scalascript.sql.ConnectionRegistry(
          m.databases.map { d =>
            scalascript.sql.DatabaseSpec(d.name, d.url, d.user, d.password, d.driver)
          }
        )
    }
    registerFrontmatterRoutes(module)
    module.sections.foreach(SectionRuntime.runSection(_, this))
    if !mainCalled then
      globals.get("main").foreach {
        case f: Value.FunV if f.params.isEmpty => Computation.run(callFun(f, Nil)); mainCalled = true
        case _ => ()
      }

  /** Register each `routes:` entry from front-matter as if the user had
   *  written `route(method, path) { req => handler(req) }` inline.  We
   *  register BEFORE evaluating sections (since the user's `serve(port)`
   *  call at the end of a section blocks forever) and bind a lazy
   *  wrapper that resolves the handler from `globals` at request time,
   *  once the section defs have run. */
  private def registerFrontmatterRoutes(module: Module): Unit =
    module.manifest.foreach { m =>
      m.routes.foreach { r =>
        val lazyHandler = Value.NativeFnV(
          s"frontmatter.route.${r.handler}",
          Computation.pureFn { args =>
            globals.get(r.handler) match
              case Some(h) => Computation.run(callValue(h, args, Map.empty))
              case None    =>
                throw InterpretError(
                  s"front-matter route ${r.method} ${r.path} references unknown handler '${r.handler}'"
                )
          }
        )
        scalascript.server.Routes.register(r.method, r.path, lazyHandler, this)
      }
    }

  // ─── Minimal NativeContext for Http effect ───────────────────────────
  //
  // httpRun needs a NativeContext to call doHttpRequest.  This lightweight
  // factory reads the same ThreadLocals used by httpClient{} scopes.

  private[interpreter] def mkHttpCtx(): scalascript.backend.spi.NativeContext =
    new scalascript.backend.spi.NativeContext:
      def out = Interpreter.this.out
      def err = System.err
      override def httpBaseUrl: String    = _httpBaseUrl.get()
      override def httpTimeoutMs: Long    = _httpTimeoutMs.get()
      override def httpMaxRetries: Int    = _httpMaxRetries.get()
      override def httpRetryDelayMs: Long = _httpRetryDelayMs.get()
      override def invokeCallback(fn: Any, args: List[Any]): Any =
        Interpreter.this.invoke(fn.asInstanceOf[Value], args.map(wrapAnyAsValue))

  // ─── Health-route defaults ────────────────────────────────────────

  // Extracted from initBuiltins so installNativeIntrinsics' NativeContext
  // can forward to it via ctx.registerHealthDefaults().
  private def registerHealthDefaults(): Unit =
    def isRegistered(path: String): Boolean =
      scalascript.server.Routes.all.exists(e => e.method == "GET" && e.path == path)
    val okResponse = Value.InstanceV("Response", Map(
      "status"  -> Value.IntV(200),
      "headers" -> Value.MapV(Map(
        Value.StringV("Content-Type") -> Value.StringV("application/json")
      )),
      "body"    -> Value.StringV("""{"status":"ok"}""")
    ))
    val handler = Value.NativeFnV("_healthOk", Computation.pureFn(_ => okResponse))
    if !isRegistered("/_health") then
      scalascript.server.Routes.register("GET", "/_health", handler, this)
    if !isRegistered("/_ready") then
      scalascript.server.Routes.register("GET", "/_ready", handler, this)

  /** v1.23 — register `GET /_ssc-cluster/status` returning a JSON
   *  snapshot.  Idempotent: subsequent `startNode` calls are no-ops
   *  for the route table.  Reads volatile / concurrent state with no
   *  locks — values are observation-grade, not a transactional
   *  snapshot.  Used by `ssc cluster status` and ops dashboards. */

  /** v1.23 — Bearer-token check shared by every cluster control
   *  route (status / drain / events).  Returns either `None` (caller
   *  proceeds) or `Some(401-resp)` (route handler short-circuits with
   *  the rejection).  When `clusterAuthToken` is empty the endpoints
   *  are open. */
  private def clusterAuthReject(args: List[Value]): Option[Value] =
    val token = clusterAuthToken
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
      val expected = "Bearer " + token
      if hdr == expected then None
      else
        Some(Value.InstanceV("Response", Map(
          "status"  -> Value.IntV(401),
          "headers" -> Value.MapV(Map(
            Value.StringV("Content-Type") -> Value.StringV("application/json")
          )),
          "body"    -> Value.StringV(
            """{"error":"unauthorized","hint":"set Authorization: Bearer <token>"}""")
        )))

  /** v1.23 — register `POST /_ssc-cluster/drain` toggling local drain
   *  state.  Body is JSON `{"enabled":true|false}` (or empty body =
   *  enable).  Mirrors the in-process `setDraining` intrinsic's
   *  effects: flips `isDrainingSelf`, broadcasts DrainStateChanged
   *  to peers, steps down if we were leader.  Used by
   *  `ssc cluster drain <url> [--off]`. */
  private def registerClusterDrainRoute(): Unit =
    val path = "/_ssc-cluster/drain"
    val already = scalascript.server.Routes.all.exists(e =>
      e.method == "POST" && e.path == path)
    if already then return
    val handler = Value.NativeFnV("_clusterDrain", Computation.pureFn { args =>
      clusterAuthReject(args).getOrElse {
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
        val prev = isDrainingSelf.getAndSet(enabled)
        if prev != enabled then
          val payload = s"""{"t":"drain","from":${jsonStr(localNodeId)},"draining":$enabled}"""
          peerChannels.forEach { (_, send) =>
            try send(payload) catch case _: Throwable => ()
          }
          enqueueDrainEvent(localNodeId, enabled)
          if enabled then stepDownIfLeader()
        Value.InstanceV("Response", Map(
          "status"  -> Value.IntV(200),
          "headers" -> Value.MapV(Map(
            Value.StringV("Content-Type") -> Value.StringV("application/json")
          )),
          "body"    -> Value.StringV(
            s"""{"drainingSelf":${if enabled then "true" else "false"}}""")
        ))
      }
    })
    scalascript.server.Routes.register("POST", path, handler, this)

  /** v1.23 — register `GET /_ssc-cluster/events` returning the recent
   *  events ring buffer as a JSON array.  Optional `?since=<ts>`
   *  query filters to entries strictly newer than the given epoch-ms,
   *  so dashboards can long-poll without re-streaming everything.
   *  No-op if already registered. */

  /** v1.23 — register `POST /_ssc-cluster/step-down`.  If this node
   *  is the current leader, step down (clear `currentLeader`, broadcast
   *  the change via `LeaderLost`, surrender any external coordinator
   *  lease).  If it's not the leader, returns 409 Conflict so the
   *  operator notices.  Apps with `setAutoReelect(true)` re-elect
   *  automatically — that's the rolling-restart pattern. */
  private def registerClusterStepDownRoute(): Unit =
    val path = "/_ssc-cluster/step-down"
    val already = scalascript.server.Routes.all.exists(e =>
      e.method == "POST" && e.path == path)
    if already then return
    val handler = Value.NativeFnV("_clusterStepDown", Computation.pureFn { args =>
      clusterAuthReject(args).getOrElse {
        val wasLeader =
          leaderProtocolRef.get() match
            case "raft"  => raftStateName == "leader"
            case "coord" => coordIsLeader
            case _       => currentLeader.get() == localNodeId
        if !wasLeader then
          Value.InstanceV("Response", Map(
            "status"  -> Value.IntV(409),
            "headers" -> Value.MapV(Map(
              Value.StringV("Content-Type") -> Value.StringV("application/json")
            )),
            "body"    -> Value.StringV(
              s"""{"error":"not_leader","leader":${jsonStr(currentLeader.get())}}""")
          ))
        else
          stepDownIfLeader()
          Value.InstanceV("Response", Map(
            "status"  -> Value.IntV(200),
            "headers" -> Value.MapV(Map(
              Value.StringV("Content-Type") -> Value.StringV("application/json")
            )),
            "body"    -> Value.StringV(
              s"""{"steppedDown":true,"nodeId":${jsonStr(localNodeId)}}""")
          ))
      }
    })
    scalascript.server.Routes.register("POST", path, handler, this)

  /** v1.23 — register `GET /_ssc-cluster/metrics-prom`.  Returns the
   *  `clusterMetrics` gauges in Prometheus text exposition format —
   *  one `<sanitized-name>{nodeId="<id>"} <value>` line per
   *  (metric, peer) pair, plus `# TYPE … gauge` declarations.
   *  Honours the same Bearer-token gate as the other cluster routes. */
  private def registerClusterMetricsPromRoute(): Unit =
    val path = "/_ssc-cluster/metrics-prom"
    val already = scalascript.server.Routes.all.exists(e =>
      e.method == "GET" && e.path == path)
    if already then return
    val handler = Value.NativeFnV("_clusterMetricsProm", Computation.pureFn { args =>
      clusterAuthReject(args).getOrElse {
        // Prometheus metric names must match `[a-zA-Z_:][a-zA-Z0-9_:]*`.
        // Sanitize by replacing every non-allowed char with `_`.
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
          // Names can't start with a digit.
          if out.nonEmpty && out.charAt(0) >= '0' && out.charAt(0) <= '9'
          then "_" + out else out
        def escLabel(s: String): String =
          s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val sb = new StringBuilder()
        clusterMetrics.forEach { (name, inner) =>
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
          "status"  -> Value.IntV(200),
          "headers" -> Value.MapV(Map(
            Value.StringV("Content-Type") ->
              Value.StringV("text/plain; version=0.0.4; charset=utf-8")
          )),
          "body"    -> Value.StringV(sb.toString)
        ))
      }
    })
    scalascript.server.Routes.register("GET", path, handler, this)

  private def registerClusterEventsRoute(): Unit =
    val path = "/_ssc-cluster/events"
    val already = scalascript.server.Routes.all.exists(e =>
      e.method == "GET" && e.path == path)
    if already then return
    val handler = Value.NativeFnV("_clusterEvents", Computation.pureFn { args =>
      clusterAuthReject(args).getOrElse {
        // Pull `since` from Request.query["since"] if present.
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
        val it = clusterEventLog.iterator()
        while it.hasNext do
          val line = it.next()
          // Each line is a `{"ts":<n>,...}` literal; cheap filter on
          // the leading prefix avoids parsing JSON.
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
          "status"  -> Value.IntV(200),
          "headers" -> Value.MapV(Map(
            Value.StringV("Content-Type") -> Value.StringV("application/json")
          )),
          "body"    -> Value.StringV(sb.toString)
        ))
      }
    })
    scalascript.server.Routes.register("GET", path, handler, this)

  private def registerClusterStatusRoute(): Unit =
    val path = "/_ssc-cluster/status"
    val already = scalascript.server.Routes.all.exists(e =>
      e.method == "GET" && e.path == path)
    if already then return
    val handler = Value.NativeFnV("_clusterStatus", Computation.pureFn { args =>
      clusterAuthReject(args).getOrElse {
        val sb = new StringBuilder("{")
        def kv(k: String, jsonVal: String, first: Boolean = false): Unit =
          if !first then sb.append(',')
          sb.append('"').append(k).append("\":").append(jsonVal)
        def jsonStrLit(s: String): String =
          "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        def jsonStrArr(xs: Iterable[String]): String =
          xs.map(jsonStrLit).mkString("[", ",", "]")
        val members = scala.collection.mutable.ListBuffer.empty[String]
        peerChannels.keySet().forEach(members += _)
        val drainPeers = scala.collection.mutable.ListBuffer.empty[String]
        drainingPeers.forEach { (nid, dr) =>
          if dr.booleanValue() then drainPeers += nid
        }
        val leaderNow =
          leaderProtocolRef.get() match
            case "raft" => raftLeaderId
            case _      => currentLeader.get()
        kv("nodeId",    jsonStrLit(localNodeId), first = true)
        kv("leader",    jsonStrLit(leaderNow))
        kv("protocol",  jsonStrLit(leaderProtocolRef.get()))
        kv("members",   jsonStrArr(members.toList))
        kv("drainingSelf", if isDrainingSelf.get() then "true" else "false")
        kv("drainingPeers", jsonStrArr(drainPeers.toList))
        kv("raftTerm",  raftCurrentTerm.toString)
        kv("raftState", jsonStrLit(raftStateName))
        sb.append('}')
        Value.InstanceV("Response", Map(
          "status"  -> Value.IntV(200),
          "headers" -> Value.MapV(Map(
            Value.StringV("Content-Type") -> Value.StringV("application/json")
          )),
          "body"    -> Value.StringV(sb.toString)
        ))
      }
    })
    scalascript.server.Routes.register("GET", path, handler, this)

  // ─── Built-ins ───────────────────────────────────────────────────

  private def initBuiltins(): Unit =
    def nativeP(name: String)(f: List[Value] => Value): Unit =
      globals(name) = Value.NativeFnV(name, Computation.pureFn(f))

    // println / print / route / serve / stop now live in InterpreterIntrinsics
    // (Stage 5+/B); installNativeIntrinsics routes them through Backend.intrinsics.
    installNativeIntrinsics(InterpreterIntrinsics)

    // Stage 5+/B.3 — Console companion object mirrors math / Response companions.
    // Normalize rewrites bare `println` → `Console.println`; the companion lets
    // user code also call `Console.println(...)` explicitly without the rewrite.
    globals("Console") = Value.InstanceV("Console", Map(
      "println" -> globals("Console.println"),
      "print"   -> globals("Console.print")
    ))
    // Backward-compat aliases: bare `println` / `print` still work in code that
    // bypasses the Normalize pass (tests, runSnippet, direct Interpreter.run calls).
    globals("println") = globals("Console.println")
    globals("print")   = globals("Console.print")

    // v1.26 — DriverManager companion so user code can write
    // `DriverManager.getConnection("jdbc:h2:mem:test")` directly (resolves
    // through the Select-on-globals path).  The actual native impl is
    // registered as `QualifiedName("DriverManager.getConnection")` via
    // `JdbcIntrinsics`; the companion just routes the name lookup.
    globals.get("DriverManager.getConnection").foreach { impl =>
      globals("DriverManager") = Value.InstanceV("DriverManager", Map(
        "getConnection" -> impl
      ))
    }

    // assert / require / nanoTime / getenv / doc / render / Some / List now
    // live in CoreIntrinsics (Stage 5+/E); installNativeIntrinsics routes them.
    // httpClient(baseUrl) { block } — handled as a special form in eval
    // (double-apply pattern) so the block is evaluated directly rather than
    // wrapped as a thunk.  See the Term.Apply case in eval below.
    // List companion object — fill/tabulate are curried (List.fill(n)(elem))
    globals("List.fill") = Value.NativeFnV("List.fill", {
      case List(Value.IntV(n)) =>
        Pure(Value.NativeFnV("List.fill.n", {
          case List(elem) => Pure(Value.ListV(List.fill(n.toInt)(elem)))
          case _          => throw InterpretError("List.fill(n)(elem)")
        }))
      case _ => throw InterpretError("List.fill(n)(elem)")
    })
    globals("List.tabulate") = Value.NativeFnV("List.tabulate", {
      case List(Value.IntV(n)) =>
        Pure(Value.NativeFnV("List.tabulate.n", {
          case List(f) =>
            // f(i) may perform effects — sequence the computations
            Computation.sequence((0 until n.toInt).toList.map(i =>
              callValue(f, List(Value.IntV(i)), Map.empty)))
          case _ => throw InterpretError("List.tabulate(n)(f)")
        }))
      case _ => throw InterpretError("List.tabulate(n)(f)")
    })
    globals("List.range") = Value.NativeFnV("List.range", {
      case List(Value.IntV(from), Value.IntV(until)) =>
        Pure(Value.ListV((from.toInt until until.toInt).map(i => Value.IntV(i)).toList))
      case List(Value.IntV(from), Value.IntV(until), Value.IntV(step)) =>
        Pure(Value.ListV((from.toInt until until.toInt by step.toInt).map(i => Value.IntV(i)).toList))
      case _ => throw InterpretError("List.range(from, until[, step])")
    })
    val listNative = globals("List")
    globals("List") = Value.InstanceV("List", Map(
      "fill"     -> globals("List.fill"),
      "tabulate" -> globals("List.tabulate"),
      "range"    -> globals("List.range"),
      "empty"    -> Value.ListV(Nil),
      "apply"    -> listNative
    ))
    // Map / math.sqrt-round now live in CoreIntrinsics (Stage 5+/E).
    globals("None") = Value.OptionV(None)
    globals("Some") = Value.NativeFnV("Some", { case List(v) => Pure(Value.OptionV(Some(v))); case _ => throw InterpretError("Some requires exactly one argument") })
    globals("Nil")  = Value.ListV(Nil)

    // ── Exception constructors ────────────────────────────────────────
    // Allow `throw RuntimeException("msg")` and `try ... catch { case e: ... }`
    // in ScalaScript code.  Each factory produces an InstanceV so field access
    // like `e.message` works naturally.
    def exceptionCtor(typeName: String): Value.NativeFnV =
      Value.NativeFnV(typeName, {
        case Nil               => Pure(Value.InstanceV(typeName, Map("message" -> Value.StringV(typeName))))
        case List(v)           => Pure(Value.InstanceV(typeName, Map("message" -> v)))
        case msg :: cause :: _ => Pure(Value.InstanceV(typeName, Map("message" -> msg, "cause" -> cause)))
      })
    List("RuntimeException", "Exception", "IllegalArgumentException",
         "IllegalStateException", "NumberFormatException", "ArithmeticException",
         "NullPointerException", "IndexOutOfBoundsException", "UnsupportedOperationException",
         "NoSuchElementException").foreach { n => globals(n) = exceptionCtor(n) }

    // ── attemptCatch — wrap a thunk that might throw into Either ─────────
    globals("attemptCatch") = Value.NativeFnV("attemptCatch", {
      case List(thunk) =>
        try
          val result = Computation.run(callValue(thunk, Nil, Map.empty))
          Pure(Value.InstanceV("Right", Map("value" -> result)))
        catch
          case se: ScriptException =>
            Pure(Value.InstanceV("Left", Map("value" -> se.value)))
          case t: Throwable =>
            val msg = Option(t.getMessage).getOrElse(t.getClass.getSimpleName)
            Pure(Value.InstanceV("Left", Map("value" ->
              Value.InstanceV("RuntimeException", Map("message" -> Value.StringV(msg))))))
      case _ => located("attemptCatch(thunk)")
    })

    // ── attemptCatchRaw — like attemptCatch but returns raw value (no Either boxing) ─
    globals("attemptCatchRaw") = Value.NativeFnV("attemptCatchRaw", {
      case List(thunk) =>
        try
          val result = Computation.run(callValue(thunk, Nil, Map.empty))
          Pure(result)
        catch
          case se: ScriptException => Pure(se.value)
          case t: Throwable =>
            val msg = Option(t.getMessage).getOrElse(t.getClass.getSimpleName)
            Pure(Value.InstanceV(t.getClass.getSimpleName, Map("message" -> Value.StringV(msg))))
      case _ => located("attemptCatchRaw(thunk)")
    })

    // ── v1.16 Restart object — decisions for restartable { } { } ────────
    // Restart.resume(v)  — resume the suspended computation with value v
    // Restart.useDefault — resume with Unit (null/default)
    // Restart.rethrow    — re-throw the original error as a ScriptException
    globals("Restart") = Value.InstanceV("Restart$", Map(
      "resume" -> Value.NativeFnV("Restart.resume", {
        case List(v) => Pure(Value.InstanceV("Restart$resume", Map("value" -> v)))
        case _       => throw InterpretError("Restart.resume(value)")
      }),
      "useDefault" -> Value.InstanceV("Restart$useDefault", Map.empty),
      "rethrow"    -> Value.InstanceV("Restart$rethrow",    Map.empty)
    ))

    // ── currentStackTrace — returns call stack as List[Frame] ────────────
    // By default filters out anonymous (<anon>) and _-prefixed synthetic frames.
    // Call setTraceVerbose(true) to include all frames.
    globals("currentStackTrace") = Value.NativeFnV("currentStackTrace", _ =>
      Pure(Value.ListV(callStack.toList.reverse
        .filter { case (fn, _) =>
          traceVerbose || (fn != "<anon>" && !fn.startsWith("_"))
        }
        .map { case (fn, line) =>
          Value.InstanceV("Frame", Map(
            "file" -> Value.StringV(""),
            "line" -> Value.IntV(line),
            "fn"   -> Value.StringV(fn)
          ))
        }))
    )
    globals("setTraceVerbose") = Value.NativeFnV("setTraceVerbose", {
      case List(Value.BoolV(v)) => traceVerbose = v; Pure(Value.UnitV)
      case _                    => Pure(Value.UnitV)
    })

    // ── Using — RAII resource management (try-finally close) ─────────────
    //
    // `Using.resource(r) { r => block }` runs the block with the resource
    // and unconditionally calls `r.close()` afterwards (whether the block
    // returned normally or threw).  Mirrors `scala.util.Using.resource`
    // semantics but without the typeclass dance — the resource is closed
    // ducktyped: any value with a `.close` member is honoured.
    //
    // Typical use:
    //
    //   Using.resource(mcpConnect(Transport.Spawn("node", ["srv.js"]))) { client =>
    //     client.callTool("echo", Map("msg" -> "hi"))
    //   }
    //
    // Resources without a `.close` member are still supported (the
    // resource is just released to GC at block end) — useful when the
    // user wants the same scoping shape without commitment.
    globals("Using") = Value.InstanceV("Using", Map(
      "resource" -> Value.NativeFnV("Using.resource", {
        case List(res) =>
          Pure(Value.NativeFnV("Using.resource.block", Computation.pureFn {
            case List(block) =>
              // Locate the close member: works on case-class instances
              // (InstanceV.fields("close")) and on plain Map literals
              // (MapV with key "close") alike.
              val closeOpt: Option[Value] = res match
                case Value.InstanceV(_, fields) => fields.get("close")
                case Value.MapV(m)              => m.get(Value.StringV("close"))
                case _                          => None
              try Computation.run(callValue(block, List(res), Map.empty))
              finally
                closeOpt.foreach { closeFn =>
                  try { Computation.run(callValue(closeFn, Nil, Map.empty)); () }
                  catch case _: Throwable => ()
                }
            case _ => throw InterpretError("Using.resource(r) { r => block }")
          }))
        case _ => throw InterpretError("Using.resource(r) { r => block }")
      })
    ))

    // ── McpSchema — derives target for case-class → JSON Schema ────────
    //
    // `case class WeatherArgs(city: String, units: String) derives McpSchema`
    // synthesises a `given McpSchema[WeatherArgs]` whose `schema` field is
    // a Map representation of the JSON Schema:
    //
    //   { type: "object",
    //     properties: { city: {}, units: {} },
    //     required: ["city", "units"] }
    //
    // The v1.14 Mirror exposes only field NAMES (no types) so the
    // properties stay loose — fine for MCP, where the LLM consumer
    // typically infers value shapes from descriptions anyway.  When the
    // user wants strict types, they can override the schema via
    // `srv.toolWithSchema(name, customSchema)(handler)`.
    globals("McpSchema") = Value.InstanceV("McpSchema", Map(
      "derived" -> Value.NativeFnV("McpSchema.derived", {
        case List(Value.InstanceV("Mirror", mfields)) =>
          val fieldNames: List[String] = mfields.get("fields") match
            case Some(Value.ListV(xs)) => xs.collect { case Value.StringV(s) => s }
            case _                     => Nil
          val properties = Value.MapV(fieldNames.map(n =>
            (Value.StringV(n): Value) -> (Value.MapV(Map.empty): Value)
          ).toMap)
          val required = Value.ListV(fieldNames.map(Value.StringV.apply))
          val schemaV = Value.MapV(Map(
            (Value.StringV("type"):       Value) -> (Value.StringV("object"): Value),
            (Value.StringV("properties"): Value) -> (properties:              Value),
            (Value.StringV("required"):   Value) -> (required:                Value)
          ))
          Pure(Value.InstanceV("McpSchema", Map("schema" -> schemaV)))
        case _ => Pure(Value.InstanceV("McpSchema", Map("schema" -> Value.MapV(Map.empty))))
      })
    ))

    // ── compiletime — metaprogramming primitives ─────────────────────────
    globals("compiletime") = Value.InstanceV("compiletime", Map(
      "error" -> Value.NativeFnV("compiletime.error", {
        case List(Value.StringV(msg)) => located(s"compiletime.error: $msg")
        case List(v)                  => located(s"compiletime.error: ${Value.show(v)}")
        case _                        => located("compiletime.error: (no message)")
      }),
      // constValue and summonInline are handled as Term.ApplyType in eval;
      // these stubs exist so `compiletime` resolves as a namespace object.
      "constValue"    -> Value.NativeFnV("compiletime.constValue",    _ => Pure(Value.UnitV)),
      "summonInline"  -> Value.NativeFnV("compiletime.summonInline",  _ => Pure(Value.UnitV))
    ))

    globals("math.Pi")   = Value.DoubleV(math.Pi)
    globals("math.E")    = Value.DoubleV(math.E)
    // math as an object so `math.sqrt(x)` works via field dispatch
    globals("math") = Value.InstanceV("math", Map(
      "sqrt"  -> globals("math.sqrt"),
      "abs"   -> globals("math.abs"),
      "pow"   -> globals("math.pow"),
      "floor" -> globals("math.floor"),
      "ceil"  -> globals("math.ceil"),
      "round" -> globals("math.round"),
      "Pi"    -> globals("math.Pi"),
      "E"     -> globals("math.E")
    ))

    // v1.17.x — oauth namespace: standalone OAuth 2.1 Authorization Server.
    // Mirrors the `math` companion-object pattern: dotted QualifiedName
    // entries from `OAuthIntrinsics` get a sibling InstanceV bound to
    // `globals("oauth")` so scripts can write `oauth.authServer(...)`.
    // v1.17.x — nested `oauth.client.*` namespace: OAuth client SDK
    // for .ssc apps (auth-code+PKCE, refresh, client_credentials,
    // TokenHolder).
    val oauthClient = Value.InstanceV("oauth.client", Map(
      "discoverAs"                 -> globals("oauth.client.discoverAs"),
      "discoverRs"                 -> globals("oauth.client.discoverRs"),
      "freshPkce"                  -> globals("oauth.client.freshPkce"),
      "freshState"                 -> globals("oauth.client.freshState"),
      "verifyState"                -> globals("oauth.client.verifyState"),
      "authorizationUrl"           -> globals("oauth.client.authorizationUrl"),
      "exchangeAuthorizationCode"  -> globals("oauth.client.exchangeAuthorizationCode"),
      "refresh"                    -> globals("oauth.client.refresh"),
      "clientCredentials"          -> globals("oauth.client.clientCredentials"),
      "tokenHolder"                -> globals("oauth.client.tokenHolder")
    ))
    globals("oauth") = Value.InstanceV("oauth", Map(
      "authServer"          -> globals("oauth.authServer"),
      "serveAuthServer"     -> globals("oauth.serveAuthServer"),
      "issueHmacToken"      -> globals("oauth.issueHmacToken"),
      "pkceVerifier"        -> globals("oauth.pkceVerifier"),
      "pkceChallenge"       -> globals("oauth.pkceChallenge"),
      "guard"               -> globals("oauth.guard"),
      "guardWithValidator"  -> globals("oauth.guardWithValidator"),
      "hmacValidator"       -> globals("oauth.hmacValidator"),
      "client"              -> oauthClient
    ))
    // v1.17.x — oidc namespace: OpenID Connect Identity Provider on top
    // of the OAuth Authorization Server.
    globals("oidc") = Value.InstanceV("oidc", Map(
      "server" -> globals("oidc.server"),
      "serve"  -> globals("oidc.serve")
    ))

    // escape / collectCss / collectJs / scope now live in CoreIntrinsics
    // (Stage 5+/E–F); installNativeIntrinsics routes them.

    // ─── i18n intrinsics: t / setLocale / wc ────────────────────────────
    globals("t") = Value.NativeFnV("t", {
      case List(Value.StringV(key)) =>
        val v = i18nTranslations.get(i18nLocale).flatMap(_.get(key)).getOrElse(key)
        Pure(Value.StringV(v))
      case _ => Pure(Value.StringV(""))
    })
    globals("setLocale") = Value.NativeFnV("setLocale", {
      case List(Value.StringV(code)) => i18nLocale = code; Pure(Value.UnitV)
      case _                         => Pure(Value.UnitV)
    })
    globals("wc") = Value.NativeFnV("wc", {
      case tag :: component :: rest =>
        val tagStr = Value.show(tag)
        val css = component match
          case Value.InstanceV(_, fields) =>
            fields.get("css").map(Value.show).getOrElse("")
          case _ => ""
        val renderFn = component match
          case Value.InstanceV(_, fields) => fields.get("render")
          case _                          => None
        renderFn match
          case Some(fn) =>
            callValue(fn, rest, Map.empty).map { inner =>
              val innerHtml = inner match
                case Value.InstanceV("_Raw", fields) =>
                  fields.get("html").map(Value.show).getOrElse("")
                case v => Value.show(v)
              val shadow = s"<template shadowrootmode=\"open\"><style>$css</style>$innerHtml</template>"
              Value.InstanceV("_Raw", Map("html" -> Value.StringV(s"<$tagStr-component>$shadow</$tagStr-component>")))
            }
          case None =>
            Pure(Value.InstanceV("_Raw", Map("html" ->
              Value.StringV(s"<$tagStr-component></$tagStr-component>"))))
      case _ => Pure(Value.UnitV)
    })

    // ─── Typed HTML DSL — `div(cls := "x", h1("hi"))` style ───────────
    //
    // Each tag is a native fn that takes a list of mixed args: Attr values
    // (key=value pairs from `<key> := <value>`) and children (Strings, _Raw
    // markers, or arbitrary Values rendered via Value.show).  The result is
    // a _Raw HTML node so it composes with html"..." without re-escaping.

    def attrKey(htmlName: String): Value.InstanceV =
      Value.InstanceV("AttrKey", Map("name" -> Value.StringV(htmlName)))

    // Attribute keys live under an `attr` namespace to avoid clobbering
    // very common user-side bindings like `name`, `id`, `title`, `value`.
    // Usage: `div(attr.cls := "hero", attr.id := "main")`.  Names that
    // collide with Scala reserved words use an underscore suffix
    // (`attr.type_`, `attr.for_`, `attr.method_`).
    globals("attr") = Value.InstanceV("attr", Map(
      "cls"         -> attrKey("class"),
      "id"          -> attrKey("id"),
      "href"        -> attrKey("href"),
      "src"         -> attrKey("src"),
      "alt"         -> attrKey("alt"),
      "name"        -> attrKey("name"),
      "title"       -> attrKey("title"),
      "style"       -> attrKey("style"),
      "type_"       -> attrKey("type"),
      "value_"      -> attrKey("value"),
      "placeholder" -> attrKey("placeholder"),
      "method_"     -> attrKey("method"),
      "action"      -> attrKey("action"),
      "target"      -> attrKey("target"),
      "rel"         -> attrKey("rel"),
      "for_"        -> attrKey("for"),
      "role"        -> attrKey("role"),
      "colspan"     -> attrKey("colspan"),
      "rowspan"     -> attrKey("rowspan"),
      "disabled"    -> attrKey("disabled"),
    ))

    def htmlNode(s: String): Value.InstanceV =
      Value.InstanceV("_Raw", Map("html" -> Value.StringV(s)))

    /** Render a single child node: trusted html (_Raw) passes through,
     *  Lists flatten so `xs.map(li)` composes naturally inside a parent
     *  tag, everything else goes through `Value.show` + `htmlEscape`. */
    def renderChild(v: Value): String = v match
      case Value.InstanceV("_Raw", fields) =>
        fields.get("html").map(Value.show).getOrElse("")
      case Value.ListV(items) =>
        items.map(renderChild).mkString
      case other => htmlEscape(Value.show(other))

    /** Split a tag's arg-list into attribute pairs (from `key := value`)
     *  and children (everything else, rendered as HTML).  A `ListV` arg
     *  flattens into multiple children. */
    def renderTag(name: String, args: List[Value], voidTag: Boolean = false): Value.InstanceV =
      val attrs    = scala.collection.mutable.LinkedHashMap.empty[String, String]
      val children = StringBuilder()
      def handle(v: Value): Unit = v match
        case Value.InstanceV("Attr", fields) =>
          val k = fields.get("name").map(Value.show).getOrElse("")
          val vv = fields.get("value").map(Value.show).getOrElse("")
          attrs(k) = vv
        case Value.ListV(items) =>
          items.foreach(handle)
        case other =>
          children ++= renderChild(other)
      args.foreach(handle)
      val attrStr =
        if attrs.isEmpty then ""
        else attrs.map((k, v) => s""" $k="${htmlEscape(v)}"""").mkString
      if voidTag then htmlNode(s"<$name$attrStr>")
      else            htmlNode(s"<$name$attrStr>${children.toString}</$name>")

    // All tags live at the top level for ergonomics — `div(...)`, `h1(...)`,
    // `body(...)`.  When user code rebinds one of these names (`val body =
    // req.body` inside a route handler) the local binding shadows the tag
    // global the usual way, just like any other top-level definition.
    val containerTags = List(
      "html", "head", "body", "title", "style", "script", "main",
      "section", "header", "footer", "nav", "article", "aside",
      "div", "span", "p", "a", "em", "strong", "small", "code", "pre",
      "h1", "h2", "h3", "h4", "h5", "h6",
      "ul", "ol", "li", "dl", "dt", "dd",
      "table", "thead", "tbody", "tfoot", "tr", "td", "th",
      "form", "button", "label", "select", "option", "textarea",
      "figure", "figcaption", "blockquote",
    )
    containerTags.foreach { t => nativeP(t) { args => renderTag(t, args) } }

    // Void tags: no children, no closing tag.  `<br>`, `<img src=...>`, etc.
    val voidTags = List("br", "hr", "img", "input", "link", "meta")
    voidTags.foreach { t => nativeP(t) { args => renderTag(t, args, voidTag = true) } }

    // raw(s) marks a string as pre-escaped HTML so html"..." doesn't re-escape.
    nativeP("raw") {
      case List(Value.StringV(s)) => Value.InstanceV("_Raw", Map("html" -> Value.StringV(s)))
      case List(v)                => Value.InstanceV("_Raw", Map("html" -> Value.StringV(Value.show(v))))
      case _                      => throw InterpretError("raw(s)")
    }

    // mkResponse / bodyOf / toJson / jsonStringify / jsonParse now live in
    // JsonIntrinsics + HttpIntrinsics (Stage 5+/E).

    // wrapJson / jsonRead / lookupKey / lookup / lookupOpt now live in
    // JsonIntrinsics (Stage 5+/E).

    // lookupKey / lookup / lookupOpt — see above comment (Stage 5+/E).

    // fieldOf / recordOrThrow / requireX / optionalX / requireRange* / requireOneOf
    // now live in RequestIntrinsics (Stage 5+/E); validationRecord hook bridges
    // NativeContext to validationStack.
    // Response.html/text/json/redirect/notFound/status now live in HttpIntrinsics.
    // Response companion object — fields call the underlying natives.
    // Response.basicAuthChallenge now lives in AuthIntrinsics (Stage 5+/D).
    globals("Response") = Value.InstanceV("Response", Map(
      "apply"              -> Value.NativeFnV("Response.apply", {
        case List(status, headers, body) =>
          Pure(Value.InstanceV("Response", Map("status" -> status, "headers" -> headers, "body" -> body)))
        case _ => throw InterpretError("Response(status, headers, body) expects 3 arguments")
      }),
      "html"               -> globals("Response.html"),
      "text"               -> globals("Response.text"),
      "json"               -> globals("Response.json"),
      "redirect"           -> globals("Response.redirect"),
      "notFound"           -> globals("Response.notFound"),
      "status"             -> globals("Response.status"),
      "basicAuthChallenge" -> globals("Response.basicAuthChallenge")
    ))

    // csrfToken / csrfValid / base64Url* / webauthn* / rateLimit* / totp* /
    // hashPassword / verifyPassword / cookieConfig / useSessionStore /
    // jwt* / oauth* / Response.basicAuthChallenge now live in AuthIntrinsics.
    // metrics / setMaxWsConnections / WsRoom now live in WsIntrinsics.
    // (Stage 5+/D)

    // route / serve / stop / tls / httpGet / httpPost / httpPut / httpPatch /
    // httpDelete / httpGetStream / httpPostStream / wsConnect / cors / useGzip /
    // cacheable / noCache / streamResponse / sse / maxBodySize /
    // uploadSpoolThreshold / uploadDir / use / httpTimeout / httpRetry /
    // onWebSocket / onWebSocketAuth / Response.* now live in HttpIntrinsics.
    // assert / require / nanoTime / getenv / doc / render / Some / List / Map /
    // math.* / escape now live in CoreIntrinsics.
    // jsonStringify / jsonParse / jsonRead / lookup / lookupOpt in JsonIntrinsics.
    // requireX / optionalX / requireRange* / requireOneOf in RequestIntrinsics.
    // (Stage 5+/B through 5+/E)

    // ── Storage: built-in effect for key-value persistence ──────────
    //
    // Same Free-Monad shape as Async: each op produces a `Perform`
    // node; `runStorage(body)` is the JSON file-backed handler and
    // `runEphemeralStorage(body)` is the in-memory test handler.
    globals("Storage") = Value.InstanceV("Storage", Map(
      "get"    -> Value.NativeFnV("Storage.get",
        args => Perform("Storage", "get", args)),
      "put"    -> Value.NativeFnV("Storage.put",
        args => Perform("Storage", "put", args)),
      "remove" -> Value.NativeFnV("Storage.remove",
        args => Perform("Storage", "remove", args)),
      "has"    -> Value.NativeFnV("Storage.has",
        args => Perform("Storage", "has", args)),
      "keys"   -> Value.NativeFnV("Storage.keys",
        args => Perform("Storage", "keys", args)),
    ))

    // ── Async: built-in effect for async-style code ──────────────────
    //
    // Four operations — each produces a Perform node; `runAsync(body)`
    // is the default handler.  See `evalRunAsync` / `asyncDispatch`
    // below.  The model is single-threaded: thunks passed to
    // `async` / `parallel` execute immediately on the calling thread
    // (so output is deterministic and identical across all three
    // backends).  Real concurrency on the JVM is a handler-swap away.
    globals("Async") = Value.InstanceV("Async", Map(
      "delay"    -> Value.NativeFnV("Async.delay",
        args => Perform("Async", "delay", args)),
      "async"    -> Value.NativeFnV("Async.async",
        args => Perform("Async", "async", args)),
      "await"    -> Value.NativeFnV("Async.await",
        args => Perform("Async", "await", args)),
      "parallel" -> Value.NativeFnV("Async.parallel",
        args => Perform("Async", "parallel", args)),
      "recvFrom" -> Value.NativeFnV("Async.recvFrom",
        args => Perform("Async", "recvFrom", args)),
    ))
    // `Future(v)` — wrap a value in a Future cell.  Used by handlers
    // to materialise the result of an `async` thunk; users normally
    // only construct Futures via `Async.async(...)`.
    globals("Future") = Value.NativeFnV("Future", {
      case List(v) => Pure(Value.InstanceV("Future", Map("value" -> v)))
      case _       => throw InterpretError("Future(value)")
    })

    // ── v1.4 standard-library effects ────────────────────────────────────
    StdEffectsRuntime.install(this)

    // ── v1.6 Actors — Phase 1/2/3 natives ──────────────────────────────
    ActorGlobals.install(this)

    // ── v1.9 Coroutines + v1.10 Generator + suspend ────────────────────
    CoroutineRuntime.install(this)

    // ── Reactive primitives: Signal / computed / effect ────────────────
    SignalRuntime.install(this)

    // ── v1.21 Dataset — lazy local map-reduce pipeline ──────────────────
    DatasetRuntime.install(this)

    // ── v2.x std.fs — synchronous file primitives ───────────────────────
    // Gated by Feature.FileSystem; mirrors the JS Node fs.* and JVM
    // java.nio.file calls so the same script works on all three backends.
    globals("writeFile") = Value.NativeFnV("writeFile", {
      case List(Value.StringV(path), Value.StringV(contents)) =>
        val p = java.nio.file.Paths.get(path)
        java.nio.file.Files.write(p, contents.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        Pure(Value.UnitV)
      case _ => throw InterpretError("writeFile(path: String, contents: String): Unit")
    })
    globals("readFile") = Value.NativeFnV("readFile", {
      case List(Value.StringV(path)) =>
        val bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path))
        Pure(Value.StringV(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)))
      case _ => throw InterpretError("readFile(path: String): String")
    })
    globals("deleteFile") = Value.NativeFnV("deleteFile", {
      case List(Value.StringV(path)) =>
        java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(path))
        Pure(Value.UnitV)
      case _ => throw InterpretError("deleteFile(path: String): Unit")
    })
    globals("exists") = Value.NativeFnV("exists", {
      case List(Value.StringV(path)) =>
        Pure(Value.BoolV(java.nio.file.Files.exists(java.nio.file.Paths.get(path))))
      case _ => throw InterpretError("exists(path: String): Boolean")
    })

  /** Invoke an interpreter Value (closure or native fn) from outside —
   *  used by WebServer to call route handlers in response to HTTP requests. */
  def invoke(fn: Value, args: List[Value]): Value =
    Computation.run(callValue(fn, args, Map.empty))

  /** Install a native function under `name` into the global table.
   *  Used by `InterpreterBackend` to surface `Backend.intrinsics`
   *  entries (`NativeImpl` variant) as callable globals before
   *  user code runs.  Symmetric with `initBuiltins`'s `nativeP`,
   *  but public so external SPI consumers can register their own
   *  intrinsics without touching the interpreter source. */
  def registerNative(name: String, fn: List[Value] => Value): Unit =
    globals(name) = Value.NativeFnV(name, Computation.pureFn(fn))

  /** Install every `NativeImpl` entry from a `Backend.intrinsics` map
   *  as a callable global.  Bridges `Value` ↔ `Any` at the boundary
   *  and provides a `NativeContext` whose `out` points at this
   *  interpreter's `out` (so renderCommand's null-PrintStream
   *  wrapping is respected).  Other intrinsic variants (`InlineCode`
   *  / `RuntimeCall` / `HostCallback`) are no-ops here — they target
   *  compiled or out-of-process backends. */
  def installNativeIntrinsics(
      intrinsics: Map[scalascript.ir.QualifiedName, scalascript.backend.spi.IntrinsicImpl]
  ): Unit =
    val ctx = new scalascript.backend.spi.NativeContext:
      def out = Interpreter.this.out
      def err = System.err
      override def headless = Interpreter.this.headless
      override def registerRoute(method: String, path: String, handler: Any): Unit =
        scalascript.server.Routes.register(method, path, handler.asInstanceOf[Value], Interpreter.this)
      override def registerHealthDefaults(): Unit = Interpreter.this.registerHealthDefaults()
      override def invokeCallback(fn: Any, args: List[Any]): Any =
        Interpreter.this.invoke(fn.asInstanceOf[Value], args.map(wrapAnyAsValue))
      override def httpBaseUrl: String    = _httpBaseUrl.get()
      override def httpTimeoutMs: Long    = _httpTimeoutMs.get()
      override def httpMaxRetries: Int    = _httpMaxRetries.get()
      override def httpRetryDelayMs: Long = _httpRetryDelayMs.get()
      override def setHttpTimeout(ms: Long): Unit = _httpTimeoutMs.set(ms)
      override def setHttpRetry(maxAttempts: Int, delayMs: Long): Unit =
        _httpMaxRetries.set(maxAttempts); _httpRetryDelayMs.set(delayMs)
      override def startTlsServer(port: Int, dir: String, cert: String, key: String): Unit =
        if !Interpreter.this.headless then
          scalascript.server.WebServer.start(port, dir, Interpreter.this.out, cert, key)
      override def registerWsRoute(path: String, origins: List[String], protocols: List[String],
                                    maxConn: Int, maxRate: Int, handler: Any): Unit =
        scalascript.server.WsRoutes.register(
          path, handler.asInstanceOf[Value], Interpreter.this, origins, protocols, maxConn, maxRate)
      override def registerWsAuthRoute(path: String, authFn: Any, handler: Any): Unit =
        scalascript.server.WsRoutes.register(
          path, handler.asInstanceOf[Value], Interpreter.this, auth = Some(authFn.asInstanceOf[Value]))
      override def wsConnectSync(url: String, headers: Map[String, String],
                                  protocols: List[String], handler: Any): Unit =
        val sess = scalascript.server.WsClientSession(url, headers, protocols, Interpreter.this, Interpreter.this.out)
        sess.connect()
        Interpreter.this.invoke(handler.asInstanceOf[Value], List(sess.wsObj))
        sess.awaitClose()
      override def registerMiddleware(fn: Any): Unit =
        scalascript.server.Routes.addMiddleware(fn.asInstanceOf[Value], Interpreter.this)
      override def configureCors(origins: List[String], methods: List[String],
                                  allowedHeaders: List[String]): Unit =
        scalascript.server.WebServer.configureCors(origins, methods, allowedHeaders)
      override def enableGzip(): Unit = scalascript.server.WebServer.enableGzip()
      override def setMaxBodySize(bytes: Long): Unit = scalascript.server.WebServer.setMaxBodySize(bytes)
      override def setSpoolThreshold(bytes: Long): Unit = scalascript.server.WebServer.setSpoolThreshold(bytes)
      override def setUploadDir(path: String): Unit = scalascript.server.WebServer.setUploadDir(path)
      override def validationRecord(name: String, msg: String, default: Any): Any =
        validationStack.headOption match
          case Some(buf) => buf.put(name, msg); default
          case None      => throw new scalascript.server.RestValidationError(msg)
    intrinsics.foreach {
      case (qn, scalascript.backend.spi.NativeImpl(eval)) =>
        registerNative(qn.value, args =>
          val raw = args.map(unwrapValueAsAny)
          val ret = eval(ctx, raw)
          wrapAnyAsValue(ret)
        )
      case _ => ()
    }

  private def unwrapValueAsAny(v: Value): Any = v match
    case Value.IntV(n)    => n
    case Value.DoubleV(d) => d
    case Value.StringV(s) => s
    case Value.BoolV(b)   => b
    case Value.UnitV      => ()
    case other            => other  // pass complex Values through unchanged

  private def wrapAnyAsValue(a: Any): Value = a match
    case n: Long    => Value.IntV(n)
    case i: Int     => Value.IntV(i.toLong)
    case d: Double  => Value.DoubleV(d)
    case s: String  => Value.StringV(s)
    case b: Boolean => Value.BoolV(b)
    case ()         => Value.UnitV
    case v: Value   => v
    case other      => Value.StringV(other.toString)

  /** HTML-escape a string for safe interpolation in an html block. */
  private def htmlEscape(s: String): String =
    val sb = StringBuilder()
    s.foreach {
      case '&'  => sb ++= "&amp;"
      case '<'  => sb ++= "&lt;"
      case '>'  => sb ++= "&gt;"
      case '"'  => sb ++= "&quot;"
      case '\'' => sb ++= "&#39;"
      case c    => sb += c
    }
    sb.toString

  /** Escape `rendered` unless the underlying value is a `raw(...)` marker,
   *  in which case the marker's body is already trusted HTML. */
  private[interpreter] def htmlEscapeUnlessRaw(v: Value, rendered: String): String = v match
    case Value.InstanceV("_Raw", _) => rendered
    case _                          => htmlEscape(rendered)

  def exportedGlobals: Map[String, Value] = globals.toMap
  def exportedPkg: List[String]           = modulePkg

  /** Inject a named value into the global scope before (or after) `run`.
   *  Used by external runners (e.g. `ssc test`) to seed builtins that the
   *  module can call freely, without subclassing the interpreter.
   *  Injecting before `run()` is safe: `initBuiltins()` (called inside `run`)
   *  only adds standard entries and never clears the map, so injected globals
   *  with non-standard names survive untouched. */
  def injectGlobal(name: String, value: Value): Unit = globals(name) = value
  /** Extension methods registered by this interpreter, exposed so that
   *  parents can re-register them when importing a child module — the
   *  JS and JVM backends inline imports wholesale and pick these up
   *  for free, but the interpreter only copies the values named in
   *  the import binding list and would otherwise drop extensions. */
  def exportedExtensions:    Map[(String, String), Value.FunV] = extensions.toMap
  def exportedParentTypes:   Map[String, String]               = parentTypes.toMap
  def exportedTypeFieldOrder: Map[String, List[String]]        = typeFieldOrder.toMap

  // Deep-merge overlay into base so multiple code blocks sharing the same
  // package prefix (e.g. `object std { object lib { ... } }` appearing in
  // separate fenced blocks of the same .ssc file) accumulate rather than
  // overwrite each other.
  private def mergeDeep(base: Value.InstanceV, overlay: Value.InstanceV): Value.InstanceV =
    val merged = overlay.fields.foldLeft(base.fields) { case (acc, (k, v)) =>
      (acc.get(k), v) match
        case (Some(b: Value.InstanceV), o: Value.InstanceV) => acc.updated(k, mergeDeep(b, o))
        case _                                               => acc.updated(k, v)
    }
    Value.InstanceV(base.typeName, merged)

  // ─── Section / block execution — see SectionRuntime.scala ─────────────

  // ─── Statement execution ─────────────────────────────────────────

  private[interpreter] def execStat(stat: Stat, env: mutable.Map[String, Value], printResult: Boolean = false): Unit =
    trackPos(stat)
    stat match
    case Defn.Val(_, pats, _, rhs) =>
      val rhsVal = Computation.run(eval(rhs, env.toMap))
      pats match
        case List(Pat.Var(n)) => env(n.value) = rhsVal
        case List(pat) =>
          PatternRuntime.matchPat(pat, rhsVal, env.toMap, this) match
            case Some(patEnv) => patEnv.foreach { (k, v) => env(k) = v }
            case None         => located(s"Val pattern match failed")
        case _ => ()

    case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs) =>
      env(n.value) = Computation.run(eval(rhs, env.toMap))

    case d: Defn.Def if scalascript.transform.EffectAnalysis.isExternDef(d.body) =>
      // Stage 5+/A.6 (Б-1) — extern def stub is type-only; the
      // intrinsic table (`InterpreterIntrinsics`) provides the
      // real impl via `installNativeIntrinsics` at session start.
      // Skip the def so the runtime keeps the intrinsic binding
      // (otherwise our FunV would shadow it with a body that fails
      // when called — `__extern__` is undefined).
      ()

    case d: Defn.Def =>
      val allClauses      = d.paramClauseGroups.flatMap(_.paramClauses)
      val regularClauses  = allClauses.filter(_.mod.isEmpty)
      val usingClauses    = allClauses.filter(_.mod.nonEmpty)
      val regularParamVals = regularClauses.flatMap(_.values).toList
      val usingParamVals   = usingClauses.flatMap(_.values).toList
      // Phase 3: context-bound type params [A: TC] → synthetic using param "A$TC: TC[A]"
      @annotation.nowarn("msg=deprecated")
      val cbUsingParams: List[(String, String)] =
        d.paramClauseGroups.flatMap(_.tparamClause.values).flatMap { tp =>
          tp.cbounds.map { cb =>
            val tvName = tp.name.value
            val tcStr  = typeToString(cb.asInstanceOf[scala.meta.Type])
            s"${tvName}$$${tcStr.takeWhile(_ != '[')}" -> s"$tcStr[$tvName]"
          }
        }
      val allRegularVals = regularParamVals ++ usingParamVals
      val params   = allRegularVals.map(_.name.value) ++ cbUsingParams.map(_._1)
      val defaults = allRegularVals.map(_.default)    ++ cbUsingParams.map(_ => None)
      val paramTypes  = regularParamVals.map(p => p.decltpe.fold("Any")(typeToString))
      val usingInfo: List[(String, String)] =
        usingParamVals.map(p => p.name.value -> p.decltpe.fold("Any")(typeToString)) ++ cbUsingParams
      // See Term.Function above for why we drop only globals-shadowed keys.
      val capturedEnv = env.iterator.collect {
        case (k, v) if !globals.get(k).contains(v) => k -> v
      }.toMap
      val rThrows = d.decltpe.exists(isThrowsType)
      val fn: Value.FunV = Value.FunV(params, d.body, capturedEnv, d.name.value, defaults, paramTypes, usingInfo, rThrows)
      env(d.name.value) = fn
      if d.name.value == "main" && params.isEmpty then mainCalled = false

    case d: Defn.Object =>
      val objectName = d.name.value
      // Seed members with the outer scope so closures and extension methods defined
      // inside the object body can see imported symbols.  This matters when
      // wrapSectionInPackage wraps module code in `object std { object pkg { … } }`:
      // without this, functions/extensions miss anything in globals (e.g. imports).
      // Also unfold any existing same-named object's fields so separate code
      // blocks all wrapped in `object std { object dsl { ... } }` can reference
      // symbols defined by earlier blocks during evaluation, not only after merge.
      val outerSnap  = globals.toMap ++ env.toMap
      val existingFields = env.get(objectName) match
        case Some(Value.InstanceV(_, fs)) => fs
        case _                            => Map.empty[String, Value]
      val members    = mutable.Map.from(outerSnap ++ existingFields)
      d.templ.body.stats.foreach {
        case dd: Defn.Def if EffectsRuntime.isEffectOpDef(dd.body) =>
          val effName = objectName
          val opName  = dd.name.value
          // Effect op: a bare Perform request. The "rest of the computation" is
          // captured in an outer FlatMap by the bind chain that consumed it.
          members(opName) = Value.NativeFnV(s"$effName.$opName",
            args => Perform(effName, opName, args))
        case s => execStat(s, members)
      }
      // Only expose fields that are NEW or CHANGED relative to the outer scope,
      // so the InstanceV doesn't carry inherited globals as object members.
      val newFields = members.toMap.filter { (k, v) => outerSnap.get(k).forall(old => !(old eq v)) }
      val newObj: Value.InstanceV = Value.InstanceV(objectName, newFields)
      env.get(objectName) match
        case Some(existing: Value.InstanceV) => env(objectName) = mergeDeep(existing, newObj)
        case _                               => env(objectName) = newObj

    case d: Defn.Class =>
      val params = d.ctor.paramClauses.flatMap(_.values).toList
      val paramNames    = params.map(_.name.value)
      val paramDefaults = params.map(_.default)
      val typeName = d.name.value
      val ctorEnv = env.toMap
      typeFieldOrder(typeName) = paramNames
      if d.mods.exists {
        case Mod.Annot(Init.After_4_6_0(Type.Name("noTrace"), _, _)) => true
        case _ => false
      } then noTraceTypes += typeName
      // Record first parent type for extension-method dispatch on sealed parents.
      d.templ.inits.headOption.foreach { init =>
        val pn = init.tpe match
          case Type.Name(n)   => n
          case ta: Type.Apply => ta.tpe match { case Type.Name(n) => n; case _ => "" }
          case _              => ""
        if pn.nonEmpty then parentTypes(typeName) = pn
      }
      env(typeName) = Value.NativeFnV(typeName, args => {
        val filled = applyDefaults(paramNames, paramDefaults, args, ctorEnv)
        Pure(Value.InstanceV(typeName, paramNames.zip(filled).toMap))
      })
      // Methods defined inside the class body are stored in a separate
      // type-keyed registry; dispatch on an InstanceV consults it and re-binds
      // each method's closure with the instance's data fields so the body can
      // refer to them by name (`x`, `y` in `def distanceTo(other) = ...x...`).
      val classEnv = env.toMap
      val methodPairs: List[(String, Value.FunV)] = d.templ.body.stats.collect {
        case dd: Defn.Def =>
          val mparamVals = dd.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).toList
          val mparams    = mparamVals.map(_.name.value)
          val mdefaults  = mparamVals.map(_.default)
          (dd.name.value, Value.FunV(mparams, dd.body, classEnv, dd.name.value, mdefaults))
      }
      val methodDefs: Map[String, Value.FunV] = methodPairs.toMap
      if methodDefs.nonEmpty then typeMethods(typeName) = methodDefs
      // Auto-generate given instances for derived typeclasses
      if d.templ.derives.nonEmpty then
        d.templ.derives.foreach { derivedType =>
          val tcName = derivedType match
            case Type.Name(n) => n
            case _            => derivedType.syntax
          DerivesRuntime.synthesizeDerivedInstance(typeName, paramNames, tcName, env, this)
        }

    case d: Defn.Enum =>
      val enumName = d.name.value
      val caseFields = mutable.Map.empty[String, Value]
      val ctorEnv = env.toMap
      d.templ.body.stats.foreach {
        case ec: Defn.EnumCase =>
          val caseName = ec.name.value
          val ecParams      = ec.ctor.paramClauses.flatMap(_.values).toList
          val paramNames    = ecParams.map(_.name.value)
          val paramDefaults = ecParams.map(_.default)
          if paramNames.nonEmpty then typeFieldOrder(caseName) = paramNames
          parentTypes(caseName) = enumName
          val v: Value =
            if paramNames.isEmpty then Value.InstanceV(caseName, Map.empty)
            else Value.NativeFnV(caseName, args => {
              val filled = applyDefaults(paramNames, paramDefaults, args, ctorEnv)
              Pure(Value.InstanceV(caseName, paramNames.zip(filled).toMap))
            })
          env(caseName) = v
          caseFields(caseName) = v
        case _ => ()
      }
      env(enumName) = Value.InstanceV(enumName, caseFields.toMap)

    case d: Defn.Trait =>
      // Register a sentinel InstanceV so the trait name is importable as a
      // type-level symbol (e.g. `[Semigroup, intSum](std/semigroup-monoid.ssc)`).
      val traitName = d.name.value
      if !env.contains(traitName) then
        env(traitName) = Value.InstanceV(traitName, Map.empty)
      // If the trait has `derives` clauses, synthesize those instances.
      if d.templ.derives.nonEmpty then
        d.templ.derives.foreach { derivedType =>
          val tcName = derivedType match
            case Type.Name(n) => n
            case _            => derivedType.syntax
          DerivesRuntime.synthesizeDerivedInstance(traitName, Nil, tcName, env, this)
        }

    case d: Defn.Given =>
      // Collect type parameters and using-clause parameters from the given definition.
      // A parametric given like `given listOrd[A](using ord: Ordering[A]): Ordering[List[A]]`
      // has a non-empty tparamClause and a using paramClause.
      val allGivenClauses  = d.paramClauseGroups.flatMap(_.paramClauses)
      val givenTypeParams  = d.paramClauseGroups.flatMap(_.tparamClause.values).map(_.name.value)
      val givenUsingClauses = allGivenClauses.filter(_.mod.nonEmpty)
      val givenUsingDeps   = givenUsingClauses.flatMap(_.values).map { p =>
        p.name.value -> p.decltpe.fold("Any")(typeToString)
      }
      val isParametric = givenTypeParams.nonEmpty || givenUsingDeps.nonEmpty

      d.templ.inits.headOption.foreach { init =>
        // Use typeToString for proper recursive handling of nested type applications
        // (e.g. Wrap[List[List[A]]] should yield "Wrap[List[List[A]]]", not "Wrap[List[_]]").
        val typeKeyOpt: Option[String] =
          val k = typeToString(init.tpe)
          if k == "_" then None else Some(k)

        typeKeyOpt.foreach { typeKey =>
          if isParametric then
            // Parametric given: register as a factory for later recursive resolution.
            // The captured env is the current env snapshot (minus globals that haven't changed).
            val captured = env.iterator.collect {
              case (k, v) if !globals.get(k).contains(v) => k -> v
            }.toMap
            val factory = ParametricGiven(
              name               = d.name.value,
              typeParams         = givenTypeParams,
              usingDeps          = givenUsingDeps,
              returnTypeTemplate = typeKey,
              givenNode          = d,
              capturedEnv        = captured
            )
            givenFactories += factory
            // Register by name so explicit `using factoryName` still works —
            // for that we eagerly build an instance without binding type vars
            // (type vars remain as-is; explicit calls supply concrete args).
            val explicitName = d.name.value
            if explicitName.nonEmpty then
              // Build a partially-applied marker so the name resolves to something.
              // Callers that say `using listOrd` supply a concrete type context.
              val partialInst = Value.InstanceV(typeKey, Map("__factory__" -> Value.BoolV(true)))
              env(explicitName) = partialInst
          else
            // Concrete (non-parametric) given: evaluate immediately and store.
            val members = mutable.Map.from(globals)
            d.templ.body.stats.foreach(s => execStat(s, members))
            val implNames = d.templ.body.stats.collect { case dd: Defn.Def => dd.name.value }.toSet
            val instance  = Value.InstanceV(typeKey, members.view.filterKeys(implNames.contains).toMap)
            env(typeKey) = instance
            // Track candidate count for ambiguity detection.
            givenCandidateCount(typeKey) = givenCandidateCount.getOrElse(typeKey, 0) + 1
            val explicitName = d.name.value
            if explicitName.nonEmpty then env(explicitName) = instance
        }
      }

    case _: Decl.Def => () // abstract method declaration — no body

    case d: Defn.ExtensionGroup =>
      d.paramClauseGroup.foreach { pcg =>
        pcg.paramClauses.headOption.flatMap(_.values.headOption).foreach { recvParam =>
          val recvName = recvParam.name.value
          val recvTypeName = recvParam.decltpe match
            case Some(Type.Name(n))   => n
            case Some(ta: Type.Apply) => ta.tpe match { case Type.Name(n) => n; case _ => "Any" }
            case _                    => "Any"
          def registerDef(defn: Defn.Def): Unit =
            val mparamVals   = defn.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).toList
            val methodParams = mparamVals.map(_.name.value)
            // Receiver param has no default; method params keep theirs.
            val methodDefaults: List[Option[Term]] = None :: mparamVals.map(_.default)
            extensions((recvTypeName, defn.name.value)) =
              Value.FunV(recvName :: methodParams, defn.body, env.toMap, "", methodDefaults)
          d.body match
            case defn: Defn.Def    => registerDef(defn)
            case Term.Block(stats) => stats.foreach { case defn: Defn.Def => registerDef(defn); case _ => () }
            case _                 => ()
        }
      }

    case t: Term =>
      val result = Computation.run(eval(t, env.toMap))
      t match
        case Term.Apply.After_4_6_0(Term.Name("main"), _) => mainCalled = true
        case _                                => ()
      if printResult then autoOutput(result)
      else result: @annotation.nowarn("msg=Discarded")

    // opaque type declaration — zero runtime overhead (same rep as underlying).
    // Auto-generate a companion singleton with `apply` / `unapply` if no
    // explicit companion object has been defined yet.  When the user writes
    //   object UserId:
    //     def apply(s: String): UserId = s
    // that Defn.Object is processed later and will OVERWRITE our synthetic
    // entry via the normal mergeDeep path, so there is no conflict.
    case d: Defn.Type if d.mods.exists(_.isInstanceOf[Mod.Opaque]) =>
      val typeName = d.name.value
      // Only synthesize if no companion already registered.
      if !env.contains(typeName) then
        // apply: identity — opaque type has same runtime rep as underlying
        val applyFn = Value.NativeFnV(s"$typeName.apply",
          args => Pure(args.headOption.getOrElse(Value.UnitV)))
        // unapply: wrap value in Some(...)
        val unapplyFn = Value.NativeFnV(s"$typeName.unapply",
          args => Pure(Value.InstanceV("Some", Map("value" -> args.headOption.getOrElse(Value.UnitV)))))
        env(typeName) = Value.InstanceV(typeName, Map("apply" -> applyFn, "unapply" -> unapplyFn))

    case _ => () // type aliases, imports, exports, etc.

  // ─── Expression evaluation ───────────────────────────────────────

  private[interpreter] def eval(term: Term, env: Env): Computation =
    trackPos(term)
    term match
    // Literals — interned by Lit identity so a hot loop reuses the same
    // `Pure(Value)` instance instead of reallocating on every eval. The
    // parser produces a stable AST; each `Lit.Int(1)` etc. is the same
    // Scala object across all visits.
    case lit: Lit =>
      val cached = litCache.get(lit)
      if cached != null then cached
      else
        val c = lit match
          case Lit.Int(v)     => Pure(Value.IntV(v.toLong))
          case Lit.Long(v)    => Pure(Value.IntV(v))
          case Lit.Double(v)  => Pure(Value.DoubleV(v.toString.toDouble))
          case Lit.Float(v)   => Pure(Value.DoubleV(v.toString.toDouble))
          case Lit.String(v)  => Pure(Value.StringV(v))
          case Lit.Boolean(v) => Pure(Value.BoolV(v))
          case Lit.Char(v)    => Pure(Value.CharV(v))
          case Lit.Unit()     => Pure(Value.UnitV)
          case Lit.Null()     => Pure(Value.NullV)
          case _              => Pure(Value.NullV)
        litCache.put(lit, c)
        c

    // Name lookup: local env first, then globals
    case Term.Name(name) =>
      Pure(env.getOrElse(name, globals.getOrElse(name, located(s"Undefined: $name"))))

    // Special form: handle(body) { case Eff.op(args, resume) => ... }
    case Term.Apply.After_4_6_0(
      Term.Apply.After_4_6_0(Term.Name("handle"), bodyArgClause),
      pfArgClause
    ) if bodyArgClause.values.size == 1 =>
      pfArgClause.values match
        case List(pf: Term.PartialFunction) =>
          EffectsRuntime.evalHandle(bodyArgClause.values.head.asInstanceOf[Term], pf.cases, env, this)
        case _ => located("handle expects a partial function { case Eff.op(args, resume) => ... }")

    // Special form: runAsync(body) — default Async handler.  The body is
    // a by-name expression; we evaluate it lazily so its effects compose
    // into the Computation tree before the driver walks them.
    case Term.Apply.After_4_6_0(Term.Name("runAsync"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      AsyncRuntime.asyncInterp(eval(bodyArgClause.values.head, env), this)

    // Special form: runAsyncParallel(body) — alternate Async handler
    // that executes thunks passed to `async` / `parallel` on real
    // JVM threads (ExecutorService + CompletableFuture).  `await`
    // blocks the calling thread on the future; `parallel` returns
    // results in declared order regardless of completion order so
    // value-deterministic code still produces byte-identical output.
    case Term.Apply.After_4_6_0(Term.Name("runAsyncParallel"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      AsyncRuntime.asyncParInterp(eval(bodyArgClause.values.head, env), this)

    // Special forms: runStorage / runEphemeralStorage — Storage-effect
    // handlers.  The former hydrates from / persists to a JSON file
    // (path from the optional second arg or `SSC_STORAGE_PATH` env,
    // defaulting to `./ssc-storage.json`); the latter keeps the map
    // in-memory and discards it at scope exit.
    case Term.Apply.After_4_6_0(Term.Name("runStorage"), bodyArgClause)
        if bodyArgClause.values.size >= 1 =>
      val pathOpt = bodyArgClause.values.lift(1).map { p =>
        Computation.run(eval(p, env)) match
          case Value.StringV(s) => s
          case _                => StorageRuntime.defaultPath
      }
      StorageRuntime.interp(eval(bodyArgClause.values.head, env), Some(pathOpt.getOrElse(StorageRuntime.defaultPath)))
    case Term.Apply.After_4_6_0(Term.Name("runEphemeralStorage"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      StorageRuntime.interp(eval(bodyArgClause.values.head, env), None)

    // ── v1.4 Logger effect handlers ───────────────────────────────────────
    // runLogger { body }        — writes "[LEVEL] msg\n" to `out`
    // runLoggerJson { body }    — writes {"level":"…","msg":"…"} newline JSON
    // runLoggerToList { body }  — collects log lines; returns (result, list)
    case Term.Apply.After_4_6_0(Term.Name("runLogger"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      EffectHandlers.loggerRun(eval(bodyArgClause.values.head, env), "text", out)
    case Term.Apply.After_4_6_0(Term.Name("runLoggerJson"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      EffectHandlers.loggerRun(eval(bodyArgClause.values.head, env), "json", out)
    case Term.Apply.After_4_6_0(Term.Name("runLoggerToList"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      EffectHandlers.loggerToListRun(eval(bodyArgClause.values.head, env))

    // ── v1.4 Random effect handlers ───────────────────────────────────────
    // runRandom { body }            — ThreadLocalRandom
    // runRandomSeeded(seed) { body } — deterministic LCG, seed is Long
    case Term.Apply.After_4_6_0(Term.Name("runRandom"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      EffectHandlers.randomRun(eval(bodyArgClause.values.head, env), None)
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Name("runRandomSeeded"), seedClause),
        bodyClause)
        if seedClause.values.size == 1 && bodyClause.values.size == 1 =>
      val seed = Computation.run(eval(seedClause.values.head, env)) match
        case Value.IntV(n) => n
        case _             => throw InterpretError("runRandomSeeded(seed: Long) { body }")
      EffectHandlers.randomRun(eval(bodyClause.values.head, env), Some(seed))

    // ── v1.4 Clock effect handlers ────────────────────────────────────────
    // runClock { body }        — real wall clock; Clock.sleep → Thread.sleep
    // runClockAt(t0) { body }  — frozen at t0 ms epoch; sleep is a no-op
    case Term.Apply.After_4_6_0(Term.Name("runClock"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      EffectHandlers.clockRun(eval(bodyArgClause.values.head, env), None)
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Name("runClockAt"), t0Clause),
        bodyClause)
        if t0Clause.values.size == 1 && bodyClause.values.size == 1 =>
      val t0 = Computation.run(eval(t0Clause.values.head, env)) match
        case Value.IntV(n) => n
        case _             => throw InterpretError("runClockAt(t0: Long) { body }")
      EffectHandlers.clockRun(eval(bodyClause.values.head, env), Some(t0))

    // ── v1.4 Env effect handlers ──────────────────────────────────────────
    // runEnv { body }               — reads real process env; Env.set is local
    // runEnvWith(Map(...)) { body }  — fixture map; Env.set mutates overlay
    case Term.Apply.After_4_6_0(Term.Name("runEnv"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      EffectHandlers.envRun(eval(bodyArgClause.values.head, env), None)
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Name("runEnvWith"), mapClause),
        bodyClause)
        if mapClause.values.size == 1 && bodyClause.values.size == 1 =>
      val overlay = Computation.run(eval(mapClause.values.head, env)) match
        case Value.MapV(m) =>
          m.map { (k, v) => Value.show(k) -> Value.show(v) }.toMap
        case _ => throw InterpretError("runEnvWith(map: Map[String, String]) { body }")
      EffectHandlers.envRun(eval(bodyClause.values.head, env), Some(overlay))

    // ── v1.4 Http effect handlers ─────────────────────────────────────────
    // runHttp { body }                   — delegates to real httpGet/httpPost
    // runHttpStub(routes) { body }       — test stub: url→body map
    case Term.Apply.After_4_6_0(Term.Name("runHttp"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      EffectHandlers.httpRun(eval(bodyArgClause.values.head, env), None, this)
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Name("runHttpStub"), routesClause),
        bodyClause)
        if routesClause.values.size == 1 && bodyClause.values.size == 1 =>
      val routes = Computation.run(eval(routesClause.values.head, env)) match
        case m @ Value.MapV(_) => m
        case _ => throw InterpretError("runHttpStub(routes: Map[String, String]) { body }")
      EffectHandlers.httpRun(eval(bodyClause.values.head, env), Some(routes), this)

    // ── v1.4 State effect handlers ────────────────────────────────────────
    // runState(s0) { body }  — runs body intercepting State performs;
    //                          returns (finalState, result) as a tuple
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Name("runState"), s0Clause),
        bodyClause)
        if s0Clause.values.size == 1 && bodyClause.values.size == 1 =>
      val s0 = Computation.run(eval(s0Clause.values.head, env))
      EffectHandlers.stateRun(eval(bodyClause.values.head, env), s0, this)

    // ── v1.4 Auth effect handlers ─────────────────────────────────────────
    // runAuthWith(user) { body }  — injects a fixed user via thread-local;
    // body is run synchronously so the thread-local is set during execution.
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Name("runAuthWith"), userClause),
        bodyClause)
        if userClause.values.size == 1 && bodyClause.values.size == 1 =>
      val user = Computation.run(eval(userClause.values.head, env))
      val prior = _authUser.get()
      _authUser.set(Some(user))
      try Pure(Computation.run(eval(bodyClause.values.head, env)))
      finally _authUser.set(prior)

    // ── v1.4 Retry effect handlers ────────────────────────────────────────
    // runRetry { body }        — real sleep between attempts
    // runRetryNoSleep { body } — test handler: retries without sleeping
    case Term.Apply.After_4_6_0(Term.Name("runRetry"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      EffectHandlers.retryRun(eval(bodyArgClause.values.head, env), sleep = true, this)
    case Term.Apply.After_4_6_0(Term.Name("runRetryNoSleep"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      EffectHandlers.retryRun(eval(bodyArgClause.values.head, env), sleep = false, this)

    // ── v1.4 Cache effect handlers ────────────────────────────────────────
    // runCache { body }        — explicit handler using process-local cache
    // runCacheBypass { body }  — caching disabled; always recomputes
    case Term.Apply.After_4_6_0(Term.Name("runCache"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      EffectHandlers.cacheRun(eval(bodyArgClause.values.head, env), bypass = false, this)
    case Term.Apply.After_4_6_0(Term.Name("runCacheBypass"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      EffectHandlers.cacheRun(eval(bodyArgClause.values.head, env), bypass = true, this)

    // ── v1.4 Tx effect handlers ───────────────────────────────────────────
    // runTx { body }  — default no-op handler (just runs body directly)
    case Term.Apply.After_4_6_0(Term.Name("runTx"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      eval(bodyArgClause.values.head, env)

    // ── v1.5 httpClient(baseUrl) { block } ───────────────────────────────
    // Double-apply special form: evaluate body directly (not as a thunk) so
    // any statements inside the block run with _httpBaseUrl set, then restore.
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Name("httpClient"), baseClause),
        bodyClause)
        if baseClause.values.size == 1 && bodyClause.values.size == 1 =>
      val baseComp = eval(baseClause.values.head, env)
      baseComp match
        case Pure(Value.StringV(base)) =>
          val priorBase = _httpBaseUrl.get(); val priorT = _httpTimeoutMs.get()
          val priorR = _httpMaxRetries.get(); val priorD = _httpRetryDelayMs.get()
          _httpBaseUrl.set(base.stripSuffix("/"))
          try eval(bodyClause.values.head, env)
          finally { _httpBaseUrl.set(priorBase); _httpTimeoutMs.set(priorT)
                    _httpMaxRetries.set(priorR); _httpRetryDelayMs.set(priorD) }
        case _ =>
          FlatMap(baseComp, {
            case Value.StringV(base) =>
              val priorBase = _httpBaseUrl.get(); val priorT = _httpTimeoutMs.get()
              val priorR = _httpMaxRetries.get(); val priorD = _httpRetryDelayMs.get()
              _httpBaseUrl.set(base.stripSuffix("/"))
              try eval(bodyClause.values.head, env)
              finally { _httpBaseUrl.set(priorBase); _httpTimeoutMs.set(priorT)
                        _httpMaxRetries.set(priorR); _httpRetryDelayMs.set(priorD) }
            case _ => throw InterpretError("httpClient(baseUrl: String) { body }")
          })

    // ── v1.6 Actors Phase 1 ────────────────────────────────────────────
    // `runActors { body }` installs an actor scheduler, spawns the body
    // as the root actor, and drives until quiescence.  The result is
    // whatever the root actor returned, or UnitV if it never did.
    case Term.Apply.After_4_6_0(Term.Name("runActors"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      actorInterp(eval(bodyArgClause.values.head, env))

    // v1.5 Tier 5 #20 — `validate { body }` runs `body` with an active
    // validation collector.  Returns `Right(bodyResult)` when the body
    // ran without any `require*` complaint, else `Left(Map[field,
    // reason])` capturing every problem in document order.  `require*`
    // inside the block returns a safe default on miss/invalid so the
    // body keeps running and accumulates errors in one pass.
    case Term.Apply.After_4_6_0(Term.Name("validate"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      val buf = mutable.LinkedHashMap.empty[String, String]
      validationStack.push(buf)
      val bodyComp = try eval(bodyArgClause.values.head, env)
                     finally ()  // never pop in the try — see below
      bodyComp.flatMap { result =>
        validationStack.pop()
        if buf.nonEmpty then
          val errMap = Value.MapV(
            scala.collection.immutable.ListMap.from(
              buf.map { (k, v) => Value.StringV(k) -> Value.StringV(v) }
            )
          )
          Pure(Value.InstanceV("Left", Map("value" -> errMap)))
        else
          Pure(Value.InstanceV("Right", Map("value" -> result)))
      }

    // `receive { case … }` — special form so we can pull out the AST
    // cases at dispatch time.  Stashes (cases, env) and emits a Perform
    // whose payload is the integer token into that side table.
    case Term.Apply.After_4_6_0(Term.Name("receive"), pfArgClause)
        if pfArgClause.values.size == 1 =>
      pfArgClause.values.head match
        case pf: Term.PartialFunction =>
          val id = receiveSpecNext; receiveSpecNext += 1
          receiveSpecs(id) = (pf.cases, env)
          Perform("Actor", "receive", List(Value.IntV(id)))
        case _ =>
          located("receive expects a partial function { case msg => ... }")

    // `receive(timeout = N) { case … }` — same dispatch, but the
    // driver also tracks a deadline.  On timeout the receive value
    // is None; on match it's Some(body-value).
    case Term.Apply.After_4_6_0(
            Term.Apply.After_4_6_0(Term.Name("receive"), timeoutClause),
            pfArgClause)
        if pfArgClause.values.size == 1 && timeoutClause.values.size == 1 =>
      val timeoutTerm = timeoutClause.values.head match
        case Term.Assign(Term.Name("timeout"), v) => v
        case other: Term                          => other
      val timeoutMs = Computation.run(eval(timeoutTerm, env)) match
        case Value.IntV(n) => n
        case _ => throw InterpretError("receive timeout must be an Int (milliseconds)")
      pfArgClause.values.head match
        case pf: Term.PartialFunction =>
          val id = receiveSpecNext; receiveSpecNext += 1
          receiveSpecs(id) = (pf.cases, env)
          Perform("Actor", "receive_t", List(Value.IntV(id), Value.IntV(timeoutMs)))
        case _ =>
          located("receive expects a partial function { case msg => ... }")

    // Special forms: `computed { ... }` / `effect { ... }` — by-name
    // bodies wrapped as zero-arg closures so the reactive machinery can
    // re-run them when dependencies change.  Without the special form
    // the block would be evaluated eagerly at call time and the runtime
    // would get a plain value instead of a thunk.
    case Term.Apply.After_4_6_0(Term.Name("computed"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      val body  = bodyArgClause.values.head.asInstanceOf[Term]
      val thunk = Value.FunV(Nil, body, env, "")
      Pure(SignalRuntime.makeComputed(this, thunk))
    case Term.Apply.After_4_6_0(Term.Name("effect"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      val body  = bodyArgClause.values.head.asInstanceOf[Term]
      val thunk = Value.FunV(Nil, body, env, "")
      SignalRuntime.makeEffect(this, thunk)
      Pure(Value.UnitV)

    // ── v1.16 restartable { handlers } { body } ───────────────────────────
    // Common Lisp condition-system style handler: the body runs in a virtual
    // thread; when `throw e` fires inside it, the error is handed to the
    // handler partial function which returns a `Restart` decision:
    //   Restart.resume(v)   — body continues with `v` as the throw-expression result
    //   Restart.useDefault  — body continues with Value.UnitV (null/unit)
    //   Restart.rethrow     — exception propagates normally from this restartable frame
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Name("restartable"), pfArgClause),
        bodyArgClause)
        if pfArgClause.values.size == 1 && bodyArgClause.values.size == 1 =>
      pfArgClause.values.head match
        case pf: Term.PartialFunction =>
          EffectsRuntime.evalRestartable(bodyArgClause.values.head.asInstanceOf[Term], pf.cases, env, this)
        case _ =>
          located("restartable expects a partial function { case Error(…) => Restart.… }")

    // Function application: detect obj.method(args) and dispatch directly.
    // All sub-terms are evaluated eagerly here; the FlatMap chain composes
    // already-built Computations so placeholderIdx and other eval-time state
    // is observed correctly.
    case app: Term.Apply =>
      app.fun match
        // ── .copy(field = value, ...) on an InstanceV ────────────────
        // Named args arrive as Term.Assign(Term.Name(field), rhs); we have
        // to intercept BEFORE the generic eval path, otherwise Term.Assign
        // would fall into the var-assignment case and mutate globals.
        case Term.Select(qual, Term.Name("copy")) =>
          OpticsRuntime.evalCopy(qual, app.argClause.values, env, this)
        // ── Focus[T](_.a.b) / Focus(_.a.b) — Monocle-style lens ──────
        // Inspect the lambda body at AST level to extract a field-access
        // chain, then synthesise a Lens value with get / set / modify /
        // andThen. Done at AST level because the placeholder lambda is
        // otherwise erased to an opaque NativeFnV.
        case ta: Term.ApplyType if OpticsRuntime.isFocusName(ta.fun) =>
          OpticsRuntime.evalFocus(app.argClause.values, this)
        case Term.Name("Focus") =>
          OpticsRuntime.evalFocus(app.argClause.values, this)
        // ── direct[M] { stmts } — v1.8 do-notation sugar ─────────────
        case Term.ApplyType.After_4_6_0(Term.Name("direct"), typeArgClause) =>
          val typeArg = typeArgClause.values.headOption.getOrElse(Type.Name("?"))
          DirectTypeUtils.validateDirectTypeArg(typeArg)
          val tag = BlockRuntime.extractDirectMonadTag(typeArgClause.values)
          app.argClause.values match
            case List(block: Term.Block) => BlockRuntime.evalDirectBlock(block.stats, env, tag, this)
            case List(single: Term)      => eval(single, env)
            case _                       => located("direct[M] expects a single block argument")
        case Term.Select(qual, Term.Name(method)) =>
          val qualC    = eval(qual, env)
          // Named args (Term.Assign) must evaluate only the RHS; the full
          // Term.Assign path at line 2338 treats them as var-assignments and
          // returns UnitV, destroying the actual value.
          val argComps = app.argClause.values.map {
            case Term.Assign(_, rhs) => eval(rhs, env)
            case other               => eval(other, env)
          }
          qualC match
            case Pure(qualV) if argComps.forall(_.isInstanceOf[Pure]) =>
              val argVs = argComps.map { case Pure(v) => v; case _ => Value.UnitV }
              DispatchRuntime.dispatch(qualV, method, argVs, env, this)
            case _ =>
              FlatMap(qualC, qualV =>
                threadValues(argComps)(argVals => DispatchRuntime.dispatch(qualV, method, argVals, env, this)))
        // ── obj.method[T](args) — type args erased, dispatch with actual args
        // Mirrors the bare Term.Select(qual, method) path above so that
        // type-parameterised method calls like `Dataset.of[Int]()` reach
        // the dispatcher with the right argument list (otherwise the
        // standalone `Dataset.of` would auto-call as a no-arg NativeFnV
        // and then the outer `()` would fail on the resulting value).
        case Term.ApplyType.After_4_6_0(Term.Select(qual, Term.Name(method)), _) =>
          val qualC    = eval(qual, env)
          val argComps = app.argClause.values.map {
            case Term.Assign(_, rhs) => eval(rhs, env)
            case other               => eval(other, env)
          }
          qualC match
            case Pure(qualV) if argComps.forall(_.isInstanceOf[Pure]) =>
              val argVs = argComps.map { case Pure(v) => v; case _ => Value.UnitV }
              DispatchRuntime.dispatch(qualV, method, argVs, env, this)
            case _ =>
              FlatMap(qualC, qualV =>
                threadValues(argComps)(argVals => DispatchRuntime.dispatch(qualV, method, argVals, env, this)))
        case _ =>
          // Flatten nested Apply nodes so that curried calls like `f(a)(using b)`
          // are collected into a single `callValue(f, [a, b])` invocation.
          // This is needed so that explicitly-supplied `using` arguments are
          // combined with regular arguments before `callFun` decides whether to
          // auto-resolve given instances.
          val (baseFun, allArgTerms) = collectApplyArgs(app)
          val hasNamedArgs = allArgTerms.exists(_.isInstanceOf[Term.Assign])
          val funC     = eval(baseFun, env)
          // Collect (Option[argName], Computation) pairs to preserve name info
          // when any named arg is present.  Pure positional calls take the fast path.
          if hasNamedArgs then
            val namedComps = allArgTerms.map {
              case Term.Assign(Term.Name(n), rhs) => (Some(n), eval(rhs, env))
              case other                          => (None,    eval(other, env))
            }
            val comps = namedComps.map(_._2)
            funC match
              case Pure(fv) if comps.forall(_.isInstanceOf[Pure]) =>
                val namedVals = namedComps.map { case (k, Pure(v)) => (k, v); case (k, _) => (k, Value.UnitV) }
                callValueNamed(fv, namedVals, env)
              case _ =>
                FlatMap(funC, fv =>
                  threadValues(comps)(argVals =>
                    callValueNamed(fv, namedComps.map(_._1).zip(argVals), env)))
          else
            val argComps = allArgTerms.map(eval(_, env))
            funC match
              case Pure(fv) if argComps.forall(_.isInstanceOf[Pure]) =>
                val argVs = argComps.map { case Pure(v) => v; case _ => Value.UnitV }
                callValue(fv, argVs, env)
              case _ =>
                FlatMap(funC, fv =>
                  threadValues(argComps)(argVals => callValue(fv, argVals, env)))

    // Compound assignment: x += e, x -= e, x *= e, x /= e, x %= e
    // Desugar: read current value, apply base-op, write back to globals.
    case Term.ApplyInfix.After_4_6_0(lhs: Term.Name, op, _, argClause)
        if op.value.lengthIs > 1 && op.value.last == '=' &&
           !Set(">=", "<=", "!=", "==").contains(op.value) =>
      val baseOp = op.value.init
      eval(lhs, env).flatMap { lhsV =>
        val argComps = argClause.values.map(eval(_, env))
        threadValues(argComps) { argVs =>
          infix(lhsV, baseOp, argVs, env).flatMap { newV =>
            globals(lhs.value) = newV
            Pure(Value.UnitV)
          }
        }
      }

    // Infix operators: a op b
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause) =>
      val lhsC     = eval(lhs, env)
      val argComps = argClause.values.map(eval(_, env))
      // Fast path: both sides already pure (the typical case for hot
      // arithmetic like `n - 1`, `acc + n`, `n < 2`). Skip the FlatMap
      // chain and call infix directly.
      lhsC match
        case Pure(lhsV) if argComps.forall(_.isInstanceOf[Pure]) =>
          val argVs = argComps.map { case Pure(v) => v; case _ => Value.UnitV }
          infix(lhsV, op.value, argVs, env)
        case _ =>
          FlatMap(lhsC, lhsV =>
            threadValues(argComps)(argVs => infix(lhsV, op.value, argVs, env)))

    // '.!' outside a direct block (or inside a lambda/block in a direct block) — error
    case Term.Select(_, Term.Name("!")) =>
      located("'.!' can only appear in expression position directly inside a direct[M] block body; not inside lambdas or nested blocks")

    // Field / method selection: a.b  (no-arg call)
    case Term.Select(qual, name) =>
      val qualC = eval(qual, env)
      FlatMap(qualC, qualV => DispatchRuntime.dispatch(qualV, name.value, Nil, env, this))

    // Block { stmts; expr }
    case Term.Block(stats) =>
      BlockRuntime.evalBlock(stats, env, this)

    // if/then/else
    case t: Term.If =>
      eval(t.cond, env) match
        // Fast path: cond evaluated eagerly to a pure BoolV (the typical
        // case after pure-value shortcuts kick in). Skip the FlatMap.
        case Pure(Value.BoolV(true))  => eval(t.thenp, env)
        case Pure(Value.BoolV(false)) => eval(t.elsep, env)
        case Pure(other)              => located(s"if condition must be Boolean, got ${Value.show(other)}")
        case condC                    => condC.flatMap {
          case Value.BoolV(true)  => eval(t.thenp, env)
          case Value.BoolV(false) => eval(t.elsep, env)
          case other              => located(s"if condition must be Boolean, got ${Value.show(other)}")
        }

    // String interpolation s"..." / f"..." / md"..." / html"..." / css"..."
    case Term.Interpolate(Term.Name(prefix), parts, args)
        if prefix == "s" || prefix == "f" || prefix == "md"
        || prefix == "html" || prefix == "css" =>
      evalArgs(args.map(_.asInstanceOf[Term]), env) { argVs =>
        val sb = StringBuilder()
        // f"..." semantics: the literal part following each ${} can begin
        // with a Java/printf-style format spec applied to the preceding arg.
        //   `f"${pi}%.2f"` →  parts = ["", "%.2f"], spec consumed off parts(1)
        val fmtRe = "^%[-+# 0,(]*\\d*(?:\\.\\d+)?[bBhHsScCdoxXeEfgGaAtT%]".r
        for i <- parts.indices do
          val partStr = parts(i).asInstanceOf[Lit.String].value
          // The first part precedes any interpolation; never consumes a spec.
          val (consumedSpec, partRest) =
            if i > 0 && prefix == "f" then
              fmtRe.findFirstIn(partStr) match
                case Some(spec) => (Some(spec), partStr.substring(spec.length))
                case None       => (None, partStr)
            else (None, partStr)
          // Emit the interpolated value for index (i - 1) before this part's text.
          if i > 0 then
            val v = argVs(i - 1)
            val rendered = consumedSpec match
              case Some(spec) =>
                val boxed: AnyRef = v match
                  case Value.IntV(n)    => java.lang.Long.valueOf(n)
                  case Value.DoubleV(d) => java.lang.Double.valueOf(d)
                  case Value.BoolV(b)   => java.lang.Boolean.valueOf(b)
                  case Value.CharV(c)   => java.lang.Character.valueOf(c)
                  case Value.StringV(s) => s
                  case other            => Value.show(other)
                try String.format(spec, boxed)
                catch case _: java.util.IllegalFormatException => Value.show(v)
              case None =>
                Value.show(v)
            sb ++= (if prefix == "html" then htmlEscapeUnlessRaw(v, rendered) else rendered)
          sb ++= partRest
        val raw = sb.toString
        Pure(Value.StringV(if prefix == "md" then stripIndent(raw) else raw))
      }

    // User-defined interpolator — build StringContext instance and call prefix fn.
    // Vararg `args: Any*` is packed into a single ListV so the body sees it as
    // an indexed list (args(0), args.length, etc.).
    case Term.Interpolate(Term.Name(prefix), parts, args) =>
      evalArgs(args.map(_.asInstanceOf[Term]), env) { argVs =>
        val partVals = parts.map(p => Value.StringV(p.asInstanceOf[Lit.String].value))
        val sc = Value.InstanceV("StringContext", Map("parts" -> Value.ListV(partVals)))
        val fn: Value = extensions.get(("StringContext", prefix))
          .orElse(env.get(prefix))
          .orElse(globals.get(prefix))
          .getOrElse(located(s"Unknown interpolator '$prefix': not in scope"))
        callValue(fn, List(sc, Value.ListV(argVs)), env)
      }

    // Anonymous function with _ placeholders: _.field, _ + 1, _ + _, etc.
    case t: Term.AnonymousFunction =>
      Pure(Value.NativeFnV("anon", args => {
        val saved = placeholderIdx
        placeholderIdx = 0
        val phEnv = env ++ args.zipWithIndex.map { (v, i) => s"_$$${i}" -> v }
        try eval(t.body, phEnv)
        finally placeholderIdx = saved
      }))

    // _ placeholder — numbered left-to-right via mutable counter
    case _: Term.Placeholder =>
      val i = placeholderIdx
      placeholderIdx += 1
      Pure(env.getOrElse(s"_$$${i}", located("Unexpected _")))

    // Lambda  x => body  or  (x, y) => body
    case Term.Function.After_4_6_0(paramClause, body) =>
      // Drop only the keys whose env value still matches the live globals —
      // those are top-level bindings that the lambda should re-read from
      // `globals` at call time (so a `var` reassigned later is visible).
      // Genuine closure captures (outer def params, block-local vals) have
      // a different value in env than in globals and must be kept; otherwise
      // a param like `a` for `def adder(a)` is stripped because globals also
      // hold the `<a>` HTML tag under that name, and the inner `b => a + b`
      // would resolve `a` to the tag instead of the captured Int.
      val closure = env.filter { case (k, v) => !globals.get(k).contains(v) }
      Pure(Value.FunV(paramClause.values.map(_.name.value), body, closure))

    // Partial function  { case pat => body; ... }  — e.g. xs.map { case (k, v) => ... }
    case Term.PartialFunction(cases) =>
      Pure(Value.NativeFnV("partial", args => {
        val arg = args match
          case List(v) => v
          case vs      => Value.TupleV(vs)
        cases.iterator
          .flatMap { c =>
            PatternRuntime.matchPat(c.pat, arg, env, this).flatMap { patEnv =>
              val guardOk = c.cond.forall(g => Computation.run(eval(g, patEnv)) match
                case Value.BoolV(b) => b
                case _              => false)
              if guardOk then Some(eval(c.body, patEnv)) else None
            }
          }
          .nextOption()
          .getOrElse(located(s"Partial function match failure: ${Value.show(arg)}"))
      }))

    // Match / pattern match
    case t: Term.Match =>
      eval(t.expr, env).flatMap { scrutV =>
        t.casesBlock.cases.iterator
          .flatMap { c =>
            PatternRuntime.matchPat(c.pat, scrutV, env, this).flatMap { patEnv =>
              val guardOk = c.cond.forall(g => Computation.run(eval(g, patEnv)) match
                case Value.BoolV(b) => b
                case _              => false)
              if guardOk then Some(eval(c.body, patEnv)) else None
            }
          }
          .nextOption()
          .getOrElse(located(s"Match failure: ${Value.show(scrutV)}"))
      }

    // Tuple  (a, b, ...)
    case Term.Tuple(elems) =>
      evalArgs(elems, env)(vs => Pure(Value.TupleV(vs)))

    // new ClassName(args)
    case Term.New(Init.After_4_6_0(tpe, _, argClauses)) =>
      val typeName = tpe match { case Type.Name(n) => n; case _ => "?" }
      val argTerms = argClauses.toList.flatMap(_.values)
      evalArgs(argTerms, env) { argVals =>
        env.getOrElse(typeName, globals.getOrElse(typeName,
          located(s"Unknown constructor: $typeName"))) match
            case c: Value.NativeFnV => c.f(argVals)
            case f: Value.FunV      => callFun(f, argVals)
            case _ => located(s"$typeName is not a constructor")
      }

    // for x <- xs yield f(x)
    case t: Term.ForYield =>
      PatternRuntime.evalForYield(t.enumsBlock.enums, t.body, env, this)

    // for x <- xs do f(x)
    case t: Term.For =>
      PatternRuntime.evalForDo(t.enumsBlock.enums, t.body, env, Map.empty, this).map(_ => Value.UnitV)

    // while cond do body  — refresh env from globals each iteration so mutations are visible.
    // Snapshot globals at loop entry: only update a key on subsequent iterations if its
    // globals value CHANGED since entry (i.e., was written by Term.Assign).  This prevents
    // a pre-existing globals entry (e.g. the HTML `<a>` tag) from clobbering a local
    // `var a = 0` that happens to shadow it.
    case t: Term.While =>
      val entrySnap: Map[String, Value] = env.iterator.flatMap { (k, _) =>
        globals.get(k).map(k -> _)
      }.toMap
      def loop: Computation =
        val freshEnv = env.map { (k, v) =>
          globals.get(k) match
            case Some(gv) if entrySnap.get(k).forall(_ != gv) => k -> gv
            case _                                             => k -> v
        }
        eval(t.expr, freshEnv).flatMap {
          case Value.BoolV(true) => eval(t.body, freshEnv).flatMap(_ => loop)
          case _                 => Pure(Value.UnitV)
        }
      loop

    // return expr  (non-local via exception)
    case Term.Return(expr) =>
      eval(expr, env).flatMap(v => throw ReturnSignal(v))

    // var/field assignment
    case Term.Assign(Term.Name(name), rhs) =>
      eval(rhs, env).flatMap { v => globals(name) = v; Pure(Value.UnitV) }

    // summon[TC[T]] — retrieve a given instance from the table
    case t: Term.ApplyType =>
      (t.fun, t.argClause.values) match
        case (Term.Name("summon"), List(typeArg)) =>
          val key = typeToString(typeArg.asInstanceOf[scala.meta.Type])
          // 1. Direct lookup in env / globals
          val direct = env.get(key).orElse(globals.get(key))
          val found = direct.orElse {
            // 2. For generic keys like "Show[A]" try:
            //    a) resolveGiven (infers concrete type from regular args if any)
            //    b) scan env for a synthetic context-bound param "A$TC"
            GivenRuntime.resolveGiven(key, Nil, env, this).orElse {
              // key shape: "TC[A]" — look for env entry "A$TC"
              val tcEnd = key.indexOf('[')
              if tcEnd > 0 then
                val tc     = key.substring(0, tcEnd)
                val typeArg = key.substring(tcEnd + 1, key.length - 1).trim
                val syntheticName = s"${typeArg}$$${tc}"
                env.get(syntheticName)
              else None
            }
          }
          Pure(found.getOrElse(located(s"No given instance for '$key'")))

        // Prism[Outer, Variant] — focus on a single sum-type variant.
        case (Term.Name("Prism"), List(_, variantType)) =>
          val variantName = variantType match
            case n: Type.Name => n.value
            case _            => located("Prism[Outer, Variant]: Variant must be a simple type name")
          Pure(OpticsRuntime.buildPrism(variantName, this))

        // compiletime.constValue[T] — return the compile-time constant value of type T
        case (Term.Select(Term.Name("compiletime"), Term.Name("constValue")), List(typeArg)) =>
          Pure(constValueOfType(typeArg.asInstanceOf[scala.meta.Type]))

        // compiletime.summonInline[TC[T]] — look up a given instance
        case (Term.Select(Term.Name("compiletime"), Term.Name("summonInline")), List(typeArg)) =>
          val key = typeToString(typeArg.asInstanceOf[scala.meta.Type])
          val found = env.get(key).orElse(globals.get(key)).orElse(GivenRuntime.resolveGiven(key, Nil, env, this))
          Pure(found.getOrElse(located(s"No given instance for '$key' (summonInline)")))

        case _ => eval(t.fun, env)  // other type applications — erase type args

    // Prefix unary operators: `!x`, `-x`, `+x`, `~x`.
    case t: Term.ApplyUnary =>
      eval(t.arg, env).flatMap { v =>
        (t.op.value, v) match
          case ("!", Value.BoolV(b))   => Pure(Value.BoolV(!b))
          case ("-", Value.IntV(n))    => Pure(Value.IntV(-n))
          case ("-", Value.DoubleV(d)) => Pure(Value.DoubleV(-d))
          case ("+", n: Value.IntV)    => Pure(n)
          case ("+", d: Value.DoubleV) => Pure(d)
          case ("~", Value.IntV(n))    => Pure(Value.IntV(~n))
          case (op, other)             => located(s"Cannot apply unary $op to ${Value.show(other)}")
      }

    case t: Term.Throw =>
      eval(t.expr, env).flatMap { v =>
        if _insideDirectBlock.get() then Pure(Value.InstanceV("Left", Map("value" -> v)))
        else
          // v1.16: check for an active restartable frame on this thread.
          // If present, suspend the body and let the handler decide.
          val rsStack = restartableStack()
          val rsHandle = rsStack.peekFirst()
          if rsHandle != null then
            rsHandle.errorQ.put(v)   // send error to handler thread
            rsHandle.resumeQ.take() match  // wait for handler's decision
              case Right(resumeVal) => Pure(resumeVal)   // resume with replacement
              case Left(rethrowVal) =>
                // Throw RestartableRethrow (not ScriptException) so the
                // body thread's catch wrapper knows this is a terminal rethrow
                // and doesn't route it back through the handler again.
                throw RestartableRethrow(rethrowVal)
          else
            val isNoTrace = v match
              case Value.InstanceV(typeName, _) => noTraceTypes.contains(typeName)
              case _ => false
            if isNoTrace then throw ScriptExceptionNoTrace(v)
            else throw ScriptException(v)
      }

    case t: Term.Try =>
      @annotation.nowarn("msg=deprecated")
      def tryCatch(thrownVal: Value, cause: Throwable): Value =
        t.catchp.iterator.flatMap { c =>
          PatternRuntime.matchPat(c.pat, thrownVal, Map.empty, this).map(bound => (c, bound))
        }.nextOption() match
          case Some((matchedCase, bound)) => Computation.run(eval(matchedCase.body, env ++ bound))
          case None                       => throw cause
      val tryResult: Value =
        try Computation.run(eval(t.expr, env))
        catch
          case rr: RestartableRethrow => throw rr  // v1.16: let terminal rethrows pass through
          case se: ScriptException  => tryCatch(se.value, se)
          case th: Throwable =>
            // Convert any JVM exception (NumberFormatException, InterpretError, etc.)
            // into a ScalaScript InstanceV so catch patterns can match it.
            val exTypeName = th.getClass.getSimpleName
            val msg = Option(th.getMessage).getOrElse(exTypeName)
            tryCatch(Value.InstanceV(exTypeName, Map("message" -> Value.StringV(msg))), th)
      t.finallyp.foreach(f => Computation.run(eval(f, env)))
      Pure(tryResult)

    case t: Term.Ascribe =>
      eval(t.expr, env)

    case other => located(s"Cannot eval: ${other.productPrefix}")

  // ─── Lenses / Optics — see OpticsRuntime.scala ─────────────────────

    /** Evaluate a list of argument terms eagerly to a list of Computations, then
   *  thread their values into `k` via FlatMap chain.
   *
   *  Eager evaluation matters because `eval` mutates `placeholderIdx`; deferring
   *  sub-term evaluation into a FlatMap continuation would observe a wrong index
   *  later. After this call all sub-Computations are fully built; only the final
   *  composition (the FlatMap chain) is interpreted lazily. */
  private def evalArgs(args: List[Term], env: Env)(k: List[Value] => Computation): Computation =
    val argComps = args.map(eval(_, env))
    threadValues(argComps)(k)

  /** Peel nested `Apply` nodes to collect all argument lists for a curried call.
   *  Only activates when the **outermost** `Apply` has a `using` argument clause
   *  (i.e. `mod = Some(Mod.Using())`).  In that case we collect all arg lists
   *  (regular + using) into a single flat list and return the base callee.
   *
   *  This handles `f(regularArgs)(using usingArgs)` without affecting ordinary
   *  curried calls like `onWebSocket(path) { handler }` which have no `using` mod.
   */
  private def collectApplyArgs(app: Term.Apply): (Term, List[Term]) =
    // Only flatten when this outer Apply carries `using` args
    if app.argClause.mod.isEmpty then (app.fun, app.argClause.values)
    else
      def peel(t: Term, acc: List[Term]): (Term, List[Term]) = t match
        case inner: Term.Apply => inner.fun match
          // Stop at select / type-apply / other complex funs
          case _: Term.Select | _: Term.ApplyType | _: Term.ApplyInfix =>
            (t, acc)
          case _ =>
            peel(inner.fun, inner.argClause.values ++ acc)
        case other => (other, acc)
      peel(app.fun, app.argClause.values)

  /** Thread a list of already-built Computations: bind each in order and feed
   *  the resulting values to `k`. */
  private[interpreter] def threadValues(comps: List[Computation])(k: List[Value] => Computation): Computation =
    def chain(remaining: List[Computation], acc: List[Value]): Computation = remaining match
      case Nil       => k(acc.reverse)
      case c :: rest => FlatMap(c, v => chain(rest, v :: acc))
    chain(comps, Nil)

  // ─── Block eval + direct[M] — see BlockRuntime.scala ─────────────────

  // ─── Call helpers ─────────────────────────────────────────────────

  private[interpreter] def callValue(fn: Value, args: List[Value], env: Env): Computation = fn match
    case f: Value.FunV      => callFun(f, args)
    case f: Value.NativeFnV => f.f(args)
    case Value.InstanceV(_, fields) =>
      fields.get("apply") match
        case Some(f) => callValue(f, args, env)
        case None    => located(s"Instance is not callable")
    // `xs(i)` and `m(k)` — apply-as-indexing on collections.
    case _: Value.ListV | _: Value.MapV => DispatchRuntime.dispatch(fn, "apply", args, env, this)
    case _ => located(s"Not callable: ${Value.show(fn)}")

  /** Named-arg aware call.  `namedArgs` is a list of (name?, value) pairs
   *  where positional args have `name = None` and named args have `name = Some("argName")`.
   *
   *  For `FunV` the args are reordered to match the declared parameter order:
   *  1. Named args are placed at the slot matching their name in `f.params`.
   *  2. Positional args fill remaining slots left-to-right.
   *  3. Unfilled slots with defaults are evaluated (including non-trailing ones).
   *  4. An unknown name raises a runtime error.
   *  5. An unfilled slot without a default raises a runtime error.
   *
   *  For non-FunV callables (NativeFnV, collections) the names are ignored and
   *  args are passed positionally — these targets don't expose a named-arg API. */
  private def callValueNamed(fn: Value, namedArgs: List[(Option[String], Value)], env: Env): Computation =
    fn match
      case f: Value.FunV =>
        // Validate: every named arg must appear in f.params.
        val paramSet = f.params.toSet
        namedArgs.foreach {
          case (Some(n), _) if !paramSet.contains(n) =>
            located(s"Unknown argument name '$n' for function '${if f.name.nonEmpty then f.name else "<anon>"}' (parameters: ${f.params.mkString(", ")})")
          case _ => ()
        }
        // Build the ordered arg list: start with a slot array sized to f.params,
        // fill named slots first, then fill remaining slots with positionals.
        val slots = Array.fill[Option[Value]](f.params.length)(None)
        // Pass 1: place named args at their declared position.
        namedArgs.foreach {
          case (Some(n), v) =>
            val idx = f.params.indexOf(n)
            if idx >= 0 then slots(idx) = Some(v)
          case _ => ()
        }
        // Pass 2: place positional args into the first empty slots (left-to-right).
        val positionals = namedArgs.collect { case (None, v) => v }.iterator
        for i <- slots.indices do
          if slots(i).isEmpty && positionals.hasNext then
            slots(i) = Some(positionals.next())
        // Pass 3: fill any remaining empty slots with defaults.
        // Walk left-to-right so earlier params are in scope for later defaults.
        val selfEntry = if f.name.nonEmpty then Map(f.name -> f) else Map.empty
        var baseEnv2  = f.closure ++ selfEntry
        val orderedArr = Array.fill[Value](f.params.length)(Value.UnitV)
        for i <- f.params.indices do
          slots(i) match
            case Some(v) =>
              orderedArr(i) = v
              baseEnv2 = baseEnv2 + (f.params(i) -> v)
            case None =>
              // Need default.
              val defaultOpt = f.defaults.lift(i).flatten
              defaultOpt match
                case Some(defaultTerm) =>
                  val v = Computation.run(eval(defaultTerm, baseEnv2))
                  orderedArr(i) = v
                  baseEnv2 = baseEnv2 + (f.params(i) -> v)
                case None =>
                  located(s"Missing argument for parameter '${f.params(i)}' in call to '${if f.name.nonEmpty then f.name else "<anon>"}' — parameter has no default")
        callFun(f, orderedArr.toList)
      case f: Value.NativeFnV => f.f(namedArgs.map(_._2))
      case Value.InstanceV(_, fields) =>
        fields.get("apply") match
          case Some(f) => callValueNamed(f, namedArgs, env)
          case None    => located(s"Instance is not callable")
      case _: Value.ListV | _: Value.MapV => DispatchRuntime.dispatch(fn, "apply", namedArgs.map(_._2), env, this)
      case _ => located(s"Not callable: ${Value.show(fn)}")

  private[interpreter] def callFun(f: Value.FunV, args: List[Value]): Computation =
    // Auto-tuple: an N-parameter lambda passed where a 1-arg function on
    // an N-tuple is expected (e.g. `pairs.foreach((n, s) => ...)`) gets
    // its single tuple argument destructured into the N parameters.
    val tupledArgs = args match
      case List(Value.TupleV(elems))
        if f.params.length > 1 && elems.length == f.params.length =>
        elems
      case _ => args
    // Only allocate the defaults-pass base env when defaults are actually
    // needed — most calls pass enough args and skip the allocation.
    // `eval` for Term.Name already falls back through `globals`, so locals
    // (closure + self ref + params) are all the env we need.
    val selfEntry = if f.name.nonEmpty then Map(f.name -> f) else Map.empty
    // Resolve factory stubs: if a caller explicitly passed `using factoryName` and the
    // value is a __factory__ marker stub, replace it with a properly instantiated value
    // derived from the factory registry and the regular args.
    def resolveFactoryStubs(args: List[Value]): List[Value] =
      if f.usingParams.isEmpty || givenFactories.isEmpty then args
      else
        val regularCount = f.params.length - f.usingParams.length
        val regularVals  = args.take(regularCount)
        args.zipWithIndex.map { case (v, i) =>
          if i >= regularCount then
            v match
              case Value.InstanceV(_, fs) if fs.contains("__factory__") =>
                // This is a factory stub — resolve the actual instance
                val usingIdx = i - regularCount
                val typeKey  = f.usingParams.lift(usingIdx).map(_._2).getOrElse("")
                if typeKey.nonEmpty then
                  GivenRuntime.resolveGiven(typeKey, regularVals, f.closure, this).getOrElse(v)
                else v
              case _ => v
          else v
        }
    val effArgs =
      if tupledArgs.length >= f.params.length then resolveFactoryStubs(tupledArgs)
      else if f.usingParams.nonEmpty then
        // Phase 1: auto-resolve `using` / context-bound params that were not
        // supplied by the call site.
        val regularCount = f.params.length - f.usingParams.length
        val withUsing =
          if tupledArgs.length >= regularCount then
            // Caller supplied all regular args — resolve each using param
            val regularArgVals = tupledArgs.take(regularCount)
            val resolved = f.usingParams.map { (pname, typeKey) =>
              GivenRuntime.resolveGiven(typeKey, regularArgVals, f.closure, this)
                .getOrElse(located(s"No given instance found for '$typeKey' (using parameter '$pname')"))
            }
            tupledArgs ++ resolved
          else
            tupledArgs  // Still need defaults — fall through
        if withUsing.length >= f.params.length then withUsing
        else applyDefaults(f.params, f.defaults, withUsing, f.closure ++ selfEntry)
      else
        applyDefaults(f.params, f.defaults, tupledArgs, f.closure ++ selfEntry)
    val info      = TcoRuntime.tcoInfoFor(f, this)
    val hasMutualTail = info.tailTargets.nonEmpty && info.tailTargets.exists { n =>
      (globals.get(n) orElse f.closure.get(n)).exists(_.isInstanceOf[Value.FunV])
    }
    if info.noNonTailSelf && (info.isSelfTailRec || hasMutualTail) then
      // Profile the initial TCO call; tcoTrampoline will record each
      // subsequent tail-call iteration individually.
      if Profiler.enabled && f.name.nonEmpty then
        Profiler.record(f.name, 0L)
      TcoRuntime.tcoTrampoline(f, effArgs, null, this)
    else
      // Build callEnv as a `FrameMap` — parallel arrays of param names /
      // values on top of the (cached) closure-with-self map. Cheaper than
      // a HashMap.updated chain for the typical 1-2 param case and
      // turns lookup into a linear scan over `slots` (tiny), falling
      // through to the closure map only for non-locals.
      val withSelf = closureWithSelfFor(f)
      val callEnv: Env = f.params match
        case Nil               => withSelf
        case p :: Nil          => FrameMap.one(p, effArgs.head, withSelf)
        case p1 :: p2 :: Nil   => FrameMap.two(p1, effArgs.head, p2, effArgs(1), withSelf)
        case ps                =>
          val names = ps.toArray
          val arr   = effArgs.iterator.take(names.length).toArray
          FrameMap.of(names, arr, withSelf)
      val frameName = if f.name.nonEmpty then f.name else "<anon>"
      val lineNum   = currentSpan.map(_._1 + 1).getOrElse(0)
      callStack += ((frameName, lineNum))
      val t0 = if Profiler.enabled && f.name.nonEmpty then System.nanoTime() else 0L
      val result =
        try TcoRuntime.runUntilSuspension(eval(f.body, callEnv))
        catch case r: ReturnSignal => Pure(r.value)
      if Profiler.enabled && f.name.nonEmpty then
        Profiler.record(f.name, System.nanoTime() - t0)
      if callStack.nonEmpty then callStack.remove(callStack.length - 1)
      if f.returnsThrows then result.map(throwsAutoWrap)
      else result

  private def throwsAutoWrap(v: Value): Value = v match
    case Value.InstanceV("Left",  _) => v
    case Value.InstanceV("Right", _) => v
    case other => Value.InstanceV("Right", Map("value" -> other))

  // ─── Given / using helpers ────────────────────────────────────────

  /** Convert a scalameta `Type` node to the canonical key string used to
   *  store `given` instances (e.g. `"Monoid[Int]"`, `"Monad[List]"`). */
  private[interpreter] def typeToString(t: scala.meta.Type): String = t match
    case scala.meta.Type.Name(n)         => n
    case ta: scala.meta.Type.Apply       => s"${typeToString(ta.tpe)}[${ta.argClause.values.map(typeToString).mkString(", ")}]"
    case scala.meta.Type.Function.After_4_6_0(params, r) => s"(${params.values.map(typeToString).mkString(", ")}) => ${typeToString(r)}"
    case scala.meta.Type.Tuple(ts)       => ts.map(typeToString).mkString("(", ", ", ")")
    case ti: scala.meta.Type.ApplyInfix  => s"${typeToString(ti.lhs)} ${ti.op.value} ${typeToString(ti.rhs)}"
    case _                               => "_"

  /** Extract the compile-time constant value of a type-level literal.
   *  Used by `compiletime.constValue[T]`. */
  private def constValueOfType(tp: scala.meta.Type): Value = tp match
    case scala.meta.Lit.Int(n)      => Value.IntV(n.toLong)
    case scala.meta.Lit.String(s)   => Value.StringV(s)
    case scala.meta.Lit.Boolean(b)  => Value.BoolV(b)
    case scala.meta.Lit.Double(d)   => Value.DoubleV(d.toDouble)
    case scala.meta.Lit.Long(l)     => Value.IntV(l)
    case scala.meta.Type.Name(n)    => Value.StringV(n)
    case _                          => Value.StringV(tp.syntax)

  /** True if a scalameta Type is `A throws E` (infix `throws`). */
  private[interpreter] def isThrowsType(t: scala.meta.Type): Boolean = t match
    case ti: scala.meta.Type.ApplyInfix => ti.op.value == "throws"
    case _                              => false

  // ─── Given / typeclass resolution — see GivenRuntime.scala ──────────────

  /** Extend `args` with default values for any missing trailing parameters.
   *  Each default is evaluated in `baseEnv` augmented with the bindings of all
   *  parameters to its left (provided ones plus already-filled defaults), so
   *  defaults like `def f(x: Int, y: Int = x + 1)` see `x` correctly. */
  private def applyDefaults(
    params:   List[String],
    defaults: List[Option[Term]],
    args:     List[Value],
    baseEnv:  Env
  ): List[Value] =
    if args.length >= params.length then args
    else
      val provided = args
      var env = baseEnv ++ params.zip(provided).toMap
      val filled = (provided.length until params.length).map { i =>
        val pname      = params(i)
        val defaultOpt = defaults.lift(i).flatten
        defaultOpt match
          case Some(defaultTerm) =>
            val v = Computation.run(eval(defaultTerm, env))
            env = env + (pname -> v)
            v
          case None =>
            located(s"missing argument for parameter '$pname'")
      }.toList
      provided ++ filled

  // ─── TCO trampoline — see TcoRuntime.scala ──────────────────────

  // ─── Algebraic effects (Free Monad interpreter) ──────────────────

  // ─── Effect handle + restartable — see EffectsRuntime.scala ─────────────

  // ─── Reactive primitives: implementation helpers ───────────────────

  // ── v1.x Signals — see SignalRuntime.scala ────────────────────────────

  // ── Async driver — see AsyncRuntime.scala ────────────────────────────

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
  private def actorInterp(initial: Computation): Computation =
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
      scalascript.server.WsRoutes.register(
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

  // ── Storage handler — see StorageRuntime.scala ──────────────────────

    // ─── Infix operators ──────────────────────────────────────────────

  private[interpreter] def infix(lhs: Value, op: String, args: List[Value], env: Env): Computation =
    val rhs = args.headOption.getOrElse(Value.UnitV)
    (lhs, op, rhs) match
      // HTML DSL: `cls := "hero"` builds an Attr instance that tag fns
      // recognise as an attribute pair.
      case (Value.InstanceV("AttrKey", fields), ":=", v) =>
        val name = fields.get("name").map(Value.show).getOrElse("")
        Pure(Value.InstanceV("Attr", Map(
          "name"  -> Value.StringV(name),
          "value" -> Value.StringV(Value.show(v))
        )))
      // v1.6 actors — Pid ! msg fires off to the addressee's mailbox.
      // No-op if Pid is unknown (Erlang semantics).
      case (pid @ Value.InstanceV("Pid", _), "!", msg) =>
        Perform("Actor", "send", List(pid, msg))
      case (Value.IntV(a),    "+",  Value.IntV(b))    => Pure(Value.IntV(a + b))
      case (Value.IntV(a),    "-",  Value.IntV(b))    => Pure(Value.IntV(a - b))
      case (Value.IntV(a),    "*",  Value.IntV(b))    => Pure(Value.IntV(a * b))
      case (Value.IntV(a),    "/",  Value.IntV(b))    => Pure(Value.IntV(a / b))
      case (Value.IntV(a),    "%",  Value.IntV(b))    => Pure(Value.IntV(a % b))
      case (Value.DoubleV(a), "+",  Value.DoubleV(b)) => Pure(Value.DoubleV(a + b))
      case (Value.DoubleV(a), "-",  Value.DoubleV(b)) => Pure(Value.DoubleV(a - b))
      case (Value.DoubleV(a), "*",  Value.DoubleV(b)) => Pure(Value.DoubleV(a * b))
      case (Value.DoubleV(a), "/",  Value.DoubleV(b)) => Pure(Value.DoubleV(a / b))
      case (Value.IntV(a),    "+",  Value.DoubleV(b)) => Pure(Value.DoubleV(a + b))
      case (Value.DoubleV(a), "+",  Value.IntV(b))    => Pure(Value.DoubleV(a + b))
      case (Value.IntV(a),    "*",  Value.DoubleV(b)) => Pure(Value.DoubleV(a * b))
      case (Value.DoubleV(a), "*",  Value.IntV(b))    => Pure(Value.DoubleV(a * b))
      case (Value.IntV(a),    "-",  Value.DoubleV(b)) => Pure(Value.DoubleV(a - b))
      case (Value.DoubleV(a), "-",  Value.IntV(b))    => Pure(Value.DoubleV(a - b))
      case (Value.IntV(a),    "/",  Value.DoubleV(b)) => Pure(Value.DoubleV(a / b))
      case (Value.DoubleV(a), "/",  Value.IntV(b))    => Pure(Value.DoubleV(a / b))
      case (Value.StringV(a), "+",  b)                => Pure(Value.StringV(a + Value.show(b)))
      case (Value.StringV(a), "*",  Value.IntV(n))    => Pure(Value.StringV(a * n.toInt))
      case (a, "==",  b) => Pure(Value.BoolV(a == b))
      case (a, "!=",  b) => Pure(Value.BoolV(a != b))
      case (Value.IntV(a),    "<",  Value.IntV(b))    => Pure(Value.BoolV(a < b))
      case (Value.IntV(a),    ">",  Value.IntV(b))    => Pure(Value.BoolV(a > b))
      case (Value.IntV(a),    "<=", Value.IntV(b))    => Pure(Value.BoolV(a <= b))
      case (Value.IntV(a),    ">=", Value.IntV(b))    => Pure(Value.BoolV(a >= b))
      case (Value.DoubleV(a), "<",  Value.DoubleV(b)) => Pure(Value.BoolV(a < b))
      case (Value.DoubleV(a), ">",  Value.DoubleV(b)) => Pure(Value.BoolV(a > b))
      case (Value.DoubleV(a), "<=", Value.DoubleV(b)) => Pure(Value.BoolV(a <= b))
      case (Value.DoubleV(a), ">=", Value.DoubleV(b)) => Pure(Value.BoolV(a >= b))
      case (Value.BoolV(a),   "&&", Value.BoolV(b))   => Pure(Value.BoolV(a && b))
      case (Value.BoolV(a),   "||", Value.BoolV(b))   => Pure(Value.BoolV(a || b))
      case (v, "::",  Value.ListV(ls))                => Pure(Value.ListV(v :: ls))
      case (Value.ListV(a), "++", Value.ListV(b))     => Pure(Value.ListV(a ++ b))
      case (Value.ListV(a), ":::", Value.ListV(b))    => Pure(Value.ListV(a ++ b))
      case (Value.ListV(ls), ":+", v)                 => Pure(Value.ListV(ls :+ v))
      case (v, "+:",  Value.ListV(ls))                => Pure(Value.ListV(v +: ls))
      case (k, "->", v)                               => Pure(Value.TupleV(List(k, v)))
      // Fallback: method call on lhs
      case _ => DispatchRuntime.dispatch(lhs, op, args, env, this)


  // ─── Dispatch — see DispatchRuntime.scala ────────────────────────────────

  // ─── Structural helpers for `derives` — see DerivesRuntime.scala ────────

  // ─── Pattern matching + for-comprehensions — see PatternRuntime.scala ───

  private def autoOutput(v: Value): Unit = v match
    case Value.UnitV => ()
    case _           => out.println(Value.show(v))

  private def stripIndent(s: String): String =
    val lines = s.split('\n').toList
    val body  = lines.dropWhile(_.isBlank).reverse.dropWhile(_.isBlank).reverse
    if body.isEmpty then ""
    else
      val minIndent = body.filter(_.exists(_ != ' ')).map(_.takeWhile(_ == ' ').length).minOption.getOrElse(0)
      body.map(l => if l.isBlank then "" else l.drop(minIndent)).mkString("\n")

  def runSnippet(code: String): Unit =
    import scalascript.parser.Parser
    val src    = s"# Snippet\n\n```scala\n$code\n```\n"
    val module = Parser.parse(src)
    module.sections.foreach(SectionRuntime.runSection(_, this))

  // ── v1.4 effect handlers — see EffectHandlers.scala ────────────────────
  //
  // loggerRun / randomRun / clockRun / envRun / httpRun /
  // retryRun / cacheRun / stateRun are in EffectHandlers.scala.

object Interpreter:
  def run(
      module:   Module,
      out:      java.io.PrintStream = System.out,
      baseDir:  Option[os.Path]     = None,
      lockPath: Option[os.Path]     = None
  ): Unit =
    Interpreter(out, baseDir, lockPath = lockPath).run(module)
