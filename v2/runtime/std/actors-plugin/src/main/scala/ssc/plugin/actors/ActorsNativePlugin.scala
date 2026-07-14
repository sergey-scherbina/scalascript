package ssc.plugin.actors

import java.util.concurrent.{ConcurrentHashMap, LinkedBlockingQueue, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import ssc.{Done, Runtime, Value}
import ssc.plugin.{NativePlugin, NativePluginContext}

/** Core-free local and typed-loopback Actors runtime. */
final class ActorsNativePlugin extends NativePlugin:
  def id: String = "60-actors"

  private final class RunScope:
    private val lock = Object()
    private val actors = ConcurrentHashMap.newKeySet[Mailbox]()
    private val failure = AtomicReference[Throwable](null)

    def add(mailbox: Mailbox): Unit =
      actors.add(mailbox)
      signal()

    def fail(error: Throwable): Unit =
      failure.compareAndSet(null, error)
      signal()

    def signal(): Unit = lock.synchronized(lock.notifyAll())

    private def quiescent: Boolean =
      val iterator = actors.iterator()
      var result = true
      while result && iterator.hasNext do
        val mailbox = iterator.next()
        result = mailbox.dead ||
          (mailbox.blocked && mailbox.queue.isEmpty && mailbox.deadline < 0L)
      result

    def await(): Unit = lock.synchronized {
      while !quiescent do lock.wait(25L)
    }

    def throwFailure(): Unit =
      val error = failure.get()
      if error != null then
        val message = Option(error.getMessage).filter(_.nonEmpty)
          .getOrElse(error.getClass.getSimpleName)
        throw new IllegalStateException(s"Actors scope failed: $message", error)

  private final class Mailbox:
    val queue = LinkedBlockingQueue[Value]()
    @volatile var thread: Thread = null
    @volatile var dead = false
    @volatile var blocked = false
    @volatile var deadline = -1L
    @volatile var scope: RunScope = null
    // Supervision (Erlang-style): links (bidirectional exit propagation), monitors (one-way Down),
    // trapExit (an Exit signal becomes an Exit message instead of killing), and the exit reason.
    @volatile var trapExit = false
    @volatile var propagated = false
    @volatile var exitReason: Value = Value.StrV("normal")
    val links = ConcurrentHashMap.newKeySet[Mailbox]()
    val monitors = new ConcurrentHashMap[java.lang.Long, Mailbox]()  // monitorId -> watcher

  private val current = new ThreadLocal[Mailbox]()
  private val behaviors = ConcurrentHashMap[String, Value]()
  private val namedRefs = ConcurrentHashMap[String, Value]()
  private val localNode = AtomicReference[String]("local")

  // v1.23 cluster/leader state — single-node semantics, mirroring ActorScheduler.
  // Config: a string→value map. Leader election: protocol ("bully"/"raft"/"coord"),
  // a leader-claim history (list of (term, leaderId, ts) tuples), and the current leader.
  private val clusterConfig = new java.util.concurrent.ConcurrentHashMap[String, Value]()
  private val leaderProtocolRef = AtomicReference[String]("bully")
  private val leaderHist = new java.util.concurrent.ConcurrentLinkedDeque[Value]()
  private val leaderTermSeq = new java.util.concurrent.atomic.AtomicLong(0L)
  private val currentLeaderRef = AtomicReference[String]("")
  private val coordHolderRef = AtomicReference[Value](Value.UnitV)
  private val drainingRef = new java.util.concurrent.atomic.AtomicBoolean(false)
  private val clusterMetrics = new java.util.concurrent.ConcurrentHashMap[String, Double]()

  /** Reset per-run cluster/leader state (called from runActors alongside localNode). */
  private def resetClusterState(): Unit =
    clusterConfig.clear()
    leaderProtocolRef.set("bully")
    leaderHist.clear()
    leaderTermSeq.set(0L)
    currentLeaderRef.set("")
    coordHolderRef.set(Value.UnitV)
    drainingRef.set(false)
    clusterMetrics.clear()
  /** The local node id, single-node `""` (mirrors selfNode's `"local"`→`""` sentinel). */
  private def clusterNodeId(): String =
    val n = localNode.get(); if n == "local" then "" else n
  private def recordLeaderHist(leaderId: String): Unit =
    val term = leaderTermSeq.incrementAndGet()
    leaderHist.offer(Value.DataV("Tuple3",
      Vector(Value.IntV(term), Value.StrV(leaderId), Value.IntV(System.currentTimeMillis()))))
  private val timeoutCell = Array[Value](Value.IntV(-1))

  private def closure(arity: Int)(fn: List[Value] => Value): Value.ClosV =
    Value.ClosV(Runtime.emptyEnv, arity, env => Done(fn(env.toList)))

  private def signal(mailbox: Mailbox): Unit =
    val scope = mailbox.scope
    if scope != null then scope.signal()

  private def markBlocked(mailbox: Mailbox, deadline: Long): Unit =
    mailbox.blocked = true
    mailbox.deadline = deadline
    signal(mailbox)

  private def clearBlocked(mailbox: Mailbox): Unit =
    mailbox.blocked = false
    mailbox.deadline = -1L
    signal(mailbox)

  private def deliver(mailbox: Mailbox, message: Value): Unit =
    if !mailbox.dead then
      mailbox.blocked = false
      // offer (not put): the unbounded queue never rejects, and unlike put it does NOT throw
      // InterruptedException — exit propagation runs on a just-interrupted thread (self-exit).
      mailbox.queue.offer(message)
      signal(mailbox)

  private val monitorCounter = new java.util.concurrent.atomic.AtomicLong(0L)
  private val allMonitors = new ConcurrentHashMap[java.lang.Long, Mailbox]()  // monitorId -> target

  private def mailboxOf(ref: Value): Option[Mailbox] = ref match
    case Value.ForeignV(m: Mailbox)        => Some(m)
    case Value.ForeignV(a: ActorRefValue)  => a.underlying match
      case m: Mailbox => Some(m)
      case _          => None
    case _ => None

  /** A `"normal"` exit doesn't cascade to non-trapping links (but still delivers Down to monitors). */
  private def isAbnormal(reason: Value): Boolean = reason match
    case Value.StrV("normal") => false
    case _                    => true

  /** Kill an actor with a reason and propagate its exit. Idempotent. Propagation runs BEFORE `dead` is
    * set: delivering Exit/Down makes recipients' queues non-empty, so the scope's quiescence check
    * can't declare completion in the window between this actor dying and its signal being delivered. */
  private def killActor(mailbox: Mailbox, reason: Value): Unit =
    if !mailbox.dead then
      mailbox.exitReason = reason
      propagateExit(mailbox, reason)
      mailbox.dead = true
      val t = mailbox.thread
      if t != null then t.interrupt()
      signal(mailbox)
    else propagateExit(mailbox, reason)

  /** Notify links — a trapping link gets an Exit MESSAGE, a non-trapping link an abnormal exit
    * CASCADE-kills — and monitors get a Down message. Runs once per mailbox. */
  private def propagateExit(mailbox: Mailbox, reason: Value): Unit =
    if !mailbox.propagated then
      mailbox.propagated = true
      val fromRef = Value.ForeignV(mailbox)
      mailbox.links.forEach { linked =>
        if !linked.dead then
          if linked.trapExit then
            deliver(linked, Value.DataV("Exit", Vector(fromRef, reason)))
          else if isAbnormal(reason) then
            killActor(linked, reason)
      }
      mailbox.monitors.forEach { (monId, watcher) =>
        deliver(watcher, Value.DataV("Down", Vector(Value.IntV(monId.longValue), fromRef, reason)))
      }

  private def receive(mailbox: Mailbox, timeout: Long): Value | Null =
    val immediate = mailbox.queue.poll()
    if immediate != null then immediate
    else if timeout < 0 then
      markBlocked(mailbox, -1L)
      try mailbox.queue.take()
      finally clearBlocked(mailbox)
    else
      markBlocked(mailbox, System.currentTimeMillis() + timeout)
      try mailbox.queue.poll(timeout, TimeUnit.MILLISECONDS)
      finally clearBlocked(mailbox)

  private def startActor(
      context: NativePluginContext,
      scope: RunScope,
      fn: Value,
      args: List[Value]): Mailbox =
    val mailbox = Mailbox()
    mailbox.scope = scope
    scope.add(mailbox)
    val thread = Thread.ofVirtual().name("ssc-actor").unstarted(() => {
      current.set(mailbox)
      var interrupted = false
      try context.invoke(fn, args)
      catch
        case _: InterruptedException if mailbox.dead => interrupted = true
        case error: Throwable => scope.fail(error)
      finally
        mailbox.dead = true
        current.remove()
        propagateExit(mailbox, mailbox.exitReason)
        signal(mailbox)
        if interrupted then Thread.interrupted()
    })
    mailbox.thread = thread
    thread.start()
    mailbox

  private def requireCurrent(operation: String): Mailbox =
    val mailbox = current.get()
    if mailbox == null then throw new IllegalStateException(s"$operation called outside runActors")
    mailbox

  private def timedReceive(context: NativePluginContext, timeout: Long): Value =
    closure(1) {
      case handler :: Nil =>
        val mailbox = requireCurrent("receive(timeout)")
        val message = receive(mailbox, timeout)
        if message == null || mailbox.dead then Value.DataV("None", Vector.empty)
        else Value.DataV("Some", Vector(context.invoke(handler, List(message))))
      case _ => throw new IllegalArgumentException("receive(timeout)(handler)")
    }

  private final class ActorRefValue(node: String, mailbox: Mailbox) extends Value.NamedMethodObj:
    lazy val value: Value = Value.ForeignV(this)
    def underlying: AnyRef = mailbox
    def getField(name: String): Option[Value] = name match
      case "address"  => Some(Value.DataV("Some", Vector(Value.StrV(node))))
      case "isLocal"  => Some(Value.BoolV(true))
      case "tryLocal" => Some(Value.DataV("Some", Vector(Value.ForeignV(mailbox))))
      case "tell" => Some(closure(1) {
        case message :: Nil => deliver(mailbox, message); Value.UnitV
        case _ => throw new IllegalArgumentException("ActorRef.tell(message)")
      })
      case "publishAs" => Some(closure(1) {
        case Value.StrV(name) :: Nil => namedRefs.put(name, value); Value.UnitV
        case _ => throw new IllegalArgumentException("ActorRef.publishAs(name)")
      })
      case _ => None

  def install(context: NativePluginContext): Unit =
    context.registerValue("@timeout", Value.ForeignV(timeoutCell))

    context.registerGlobal("spawn", 1) {
      case fn :: Nil =>
        val parent = requireCurrent("spawn")
        Value.ForeignV(startActor(context, parent.scope, fn, Nil))
      case _ => throw new IllegalArgumentException("spawn(body)")
    }

    context.registerGlobal("receive", 1) {
      case (handler: Value.ClosV) :: Nil =>
        val mailbox = requireCurrent("receive")
        val message = receive(mailbox, -1L)
        if mailbox.dead then Value.UnitV
        else context.invoke(handler, List(message.asInstanceOf[Value]))
      case Value.UnitV :: Nil =>
        val timeout = timeoutCell(0) match
          case Value.IntV(value) => value
          case _ => -1L
        timedReceive(context, timeout)
      case Value.IntV(timeout) :: Nil => timedReceive(context, timeout)
      case _ => throw new IllegalArgumentException("receive[(timeout)](handler)")
    }

    context.registerGlobal("self", 0)(_ => Value.ForeignV(requireCurrent("self")))

    context.register("actor.exit") {
      case ref :: reason :: Nil =>
        mailboxOf(ref).foreach(m => killActor(m, reason))
        Value.UnitV
      case _ => throw new IllegalArgumentException("exit(pid, reason)")
    }

    // ── Supervision: link / monitor / trapExit ──────────────────────────────────
    // `exit(pid, reason)` is routed here by the os plugin's `exit` (which delegates 2-arg calls to the
    // `actor.exit` prim above); registering a second `exit` global would conflict with 20-os.
    context.registerGlobal("trapExit", 1) {
      case Value.BoolV(flag) :: Nil => requireCurrent("trapExit").trapExit = flag; Value.UnitV
      case _ => throw new IllegalArgumentException("trapExit(flag)")
    }
    context.registerGlobal("link", 1) {
      case ref :: Nil =>
        val me = requireCurrent("link")
        mailboxOf(ref).foreach { other =>
          me.links.add(other); other.links.add(me)
          // Linking to an already-dead actor immediately delivers its exit.
          if other.dead then
            if me.trapExit then deliver(me, Value.DataV("Exit", Vector(Value.ForeignV(other), other.exitReason)))
            else if isAbnormal(other.exitReason) then killActor(me, other.exitReason)
        }
        Value.UnitV
      case _ => throw new IllegalArgumentException("link(ref)")
    }
    context.registerGlobal("unlink", 1) {
      case ref :: Nil =>
        val me = requireCurrent("unlink")
        mailboxOf(ref).foreach { other => me.links.remove(other); other.links.remove(me) }
        Value.UnitV
      case _ => throw new IllegalArgumentException("unlink(ref)")
    }
    context.registerGlobal("monitor", 1) {
      case ref :: Nil =>
        val me = requireCurrent("monitor")
        mailboxOf(ref) match
          case Some(other) =>
            val monId = monitorCounter.incrementAndGet()
            other.monitors.put(monId, me); allMonitors.put(monId, other)
            if other.dead then
              deliver(me, Value.DataV("Down", Vector(Value.IntV(monId), Value.ForeignV(other), other.exitReason)))
            Value.IntV(monId)
          case None => Value.IntV(-1L)
      case _ => throw new IllegalArgumentException("monitor(ref)")
    }
    context.registerGlobal("demonitor", 1) {
      case Value.IntV(monId) :: Nil =>
        val target = allMonitors.remove(java.lang.Long.valueOf(monId))
        if target != null then target.monitors.remove(java.lang.Long.valueOf(monId))
        Value.UnitV
      case _ => throw new IllegalArgumentException("demonitor(ref)")
    }

    // ── Cluster / phi-accrual failure detector — single-node no-data branch ──────
    // A single local node has no peers and no heartbeat history: joining/broadcasting are no-ops,
    // every remote node has phi = +Infinity (⇒ suspect / down). Multi-node aggregation lives in the
    // integration suite; these pin the portable no-data semantics.
    context.registerGlobal("joinCluster", -1)(_ => Value.UnitV)
    context.registerGlobal("broadcastHealth", -1)(_ => Value.UnitV)
    context.registerGlobal("clusterIsDown", -1)(_ => Value.BoolV(false))
    context.registerGlobal("phiOf", -1)(_ => Value.FloatV(Double.PositiveInfinity))
    context.registerGlobal("isSuspect", -1)(_ => Value.BoolV(true))

    // v1.23 — cluster config registry (single-node). set/get/keys over a string map.
    clusterConfig.clear()
    context.registerGlobal("clusterConfigSet", 2) {
      case Value.StrV(key) :: value :: Nil => clusterConfig.put(key, value); Value.UnitV
      case _ => throw new IllegalArgumentException("clusterConfigSet(key, value)")
    }
    context.registerGlobal("clusterConfigGet", 1) {
      case Value.StrV(key) :: Nil =>
        Option(clusterConfig.get(key)) match
          case Some(value) => Value.DataV("Some", Vector(value))
          case None        => Value.DataV("None", Vector.empty)
      case _ => throw new IllegalArgumentException("clusterConfigGet(key)")
    }
    context.registerGlobal("clusterConfigKeys", 0) { _ =>
      import scala.jdk.CollectionConverters.*
      clusterConfig.keySet().asScala.toList.sorted
        .foldRight[Value](Value.DataV("Nil", Vector.empty))((k, acc) => Value.DataV("Cons", Vector(Value.StrV(k), acc)))
    }

    // v1.23 — leader election (bully / raft / external coordinator), single-node parity
    // with ActorScheduler. A single node always wins its own election: `electLeader`
    // records an accepted claim `(term, leaderId, ms)` unconditionally and becomes leader
    // (self id is `""` when no `startNode` set a cluster id). `currentLeader` reflects the
    // active protocol — raft/bully return the adopted id, coord defers to the holder fn.
    context.registerGlobal("electLeader", -1) { _ =>
      val id = clusterNodeId()
      recordLeaderHist(id)
      currentLeaderRef.set(id)
      Value.UnitV
    }
    context.registerGlobal("leaderProtocol", 0)(_ => Value.StrV(leaderProtocolRef.get()))
    context.registerGlobal("leaderHistory", 0) { _ =>
      import scala.jdk.CollectionConverters.*
      leaderHist.iterator().asScala.toList
        .foldRight[Value](Value.DataV("Nil", Vector.empty))((e, acc) => Value.DataV("Cons", Vector(e, acc)))
    }
    context.registerGlobal("currentLeader", 0) { _ =>
      leaderProtocolRef.get() match
        case "coord" =>
          val held = coordHolderRef.get() match
            case Value.UnitV => ""
            case fn => context.invoke(fn, Nil) match
              case Value.DataV("Some", Vector(Value.StrV(s))) => s
              case _                                          => ""
          Value.StrV(held)
        case _ => Value.StrV(currentLeaderRef.get())
    }
    context.registerGlobal("useRaftLeaderElection", -1) { _ =>
      leaderProtocolRef.set("raft"); Value.UnitV
    }
    context.registerGlobal("useExternalCoordinator", -1) { args =>
      leaderProtocolRef.set("coord")
      args match
        case acquire :: _ :: _ :: holder :: _ =>
          coordHolderRef.set(holder)
          val id = clusterNodeId()
          val got = context.invoke(acquire, List(Value.StrV(id), Value.IntV(5000L))) match
            case Value.BoolV(b) => b
            case _              => false
          if got then { recordLeaderHist(id); currentLeaderRef.set(id) }
        case _ => ()
      Value.UnitV
    }

    // v1.23 — drain / rolling-restart (single-node). `setDraining(true)` steps the
    // node down as leader (single-node self is `""` so `currentLeader` stays empty);
    // `drainingPeers` is the empty peer set. `requestGossip` / `setReconnectPolicy`
    // are peer-directed no-ops with no peers.
    context.registerGlobal("setDraining", 1) {
      case Value.BoolV(b) :: Nil =>
        drainingRef.set(b)
        if b then currentLeaderRef.set("")
        Value.UnitV
      case _ => throw new IllegalArgumentException("setDraining(enabled)")
    }
    context.registerGlobal("isDraining", 0)(_ => Value.BoolV(drainingRef.get()))
    context.registerGlobal("drainingPeers", 0)(_ => Value.DataV("Nil", Vector.empty))
    context.registerGlobal("requestGossip", -1)(_ => Value.UnitV)
    context.registerGlobal("setReconnectPolicy", -1)(_ => Value.UnitV)

    // v1.23 — cluster metrics aggregation (single-node: sum over the one local node).
    context.registerGlobal("clusterMetricSet", 2) {
      case Value.StrV(name) :: Value.FloatV(v) :: Nil => clusterMetrics.put(name, v); Value.UnitV
      case Value.StrV(name) :: Value.IntV(v) :: Nil   => clusterMetrics.put(name, v.toDouble); Value.UnitV
      case _ => throw new IllegalArgumentException("clusterMetricSet(name, value)")
    }
    context.registerGlobal("clusterMetricSum", 1) {
      case Value.StrV(name) :: Nil =>
        Value.FloatV(if clusterMetrics.containsKey(name) then clusterMetrics.get(name) else 0.0)
      case _ => throw new IllegalArgumentException("clusterMetricSum(name)")
    }
    context.registerGlobal("clusterMetricNames", 0) { _ =>
      import scala.jdk.CollectionConverters.*
      clusterMetrics.keySet().asScala.toList.sorted
        .foldRight[Value](Value.DataV("Nil", Vector.empty))((k, acc) => Value.DataV("Cons", Vector(Value.StrV(k), acc)))
    }

    context.register("actor.send") {
      case Value.ForeignV(mailbox: Mailbox) :: message :: Nil =>
        deliver(mailbox, message)
        Value.UnitV
      case _ => throw new IllegalArgumentException("actorRef ! message")
    }

    context.registerGlobal("startNode", -1) {
      case Value.StrV(node) :: _ => localNode.set(node); Value.UnitV
      case _ => throw new IllegalArgumentException("startNode(id)")
    }

    context.registerGlobal("registerBehavior", 2) {
      case Value.StrV(name) :: fn :: Nil => behaviors.put(name, fn); Value.UnitV
      case _ => throw new IllegalArgumentException("registerBehavior(name, behavior)")
    }

    context.registerGlobal("spawnRemote", -1) {
      case Value.StrV(node) :: Value.StrV(name) :: argument :: Nil =>
        val behavior = behaviors.get(name)
        if behavior == null then throw new IllegalArgumentException(
          s"spawnRemote: no behavior '$name' registered")
        val parent = requireCurrent("spawnRemote")
        val mailbox = startActor(context, parent.scope, behavior, List(argument))
        ActorRefValue(node, mailbox).value
      case _ => throw new IllegalArgumentException("spawnRemote(node, behavior, argument)")
    }

    context.registerGlobal("globalWhereis", 1) {
      case Value.StrV(name) :: Nil =>
        Option(namedRefs.get(name)).map(value => Value.DataV("Some", Vector(value)))
          .getOrElse(Value.DataV("None", Vector.empty))
      case _ => throw new IllegalArgumentException("globalWhereis(name)")
    }

    // `register(name, pid)` binds a local name → actor ref; `whereis(name)`
    // resolves it to `Some(ref)`/`None`. Both share the `namedRefs` table with
    // `publishAs`/`globalWhereis` (single-node: local and cluster names coincide).
    context.registerGlobal("register", 2) {
      case Value.StrV(name) :: pid :: Nil => namedRefs.put(name, pid); Value.UnitV
      case _ => throw new IllegalArgumentException("register(name, pid)")
    }

    context.registerGlobal("whereis", 1) {
      case Value.StrV(name) :: Nil =>
        Option(namedRefs.get(name)).map(value => Value.DataV("Some", Vector(value)))
          .getOrElse(Value.DataV("None", Vector.empty))
      case _ => throw new IllegalArgumentException("whereis(name)")
    }

    // `selfNode()` — local node identity (v1 ActorScheduler parity). A process
    // that never `startNode`d a cluster id is single-node: the internal "local"
    // sentinel reads back as the empty string. `clusterHealth()` — phi vector
    // over connected peers; single-node has none, so an empty Map.
    context.registerGlobal("selfNode", 0) { _ =>
      val node = localNode.get()
      Value.StrV(if node == "local" then "" else node)
    }

    context.registerGlobal("clusterHealth", 0) { _ =>
      Value.MapV.from(Nil)
    }

    context.registerGlobal("runActors", 1) {
      case body :: Nil =>
        behaviors.clear()
        namedRefs.clear()
        localNode.set("local")
        resetClusterState()
        timeoutCell(0) = Value.IntV(-1)
        val scope = RunScope()
        val result = AtomicReference[Value](Value.UnitV)
        val root = closure(0) { _ =>
          result.set(context.invoke(body, Nil))
          result.get()
        }
        startActor(context, scope, root, Nil)
        scope.await()
        scope.throwFailure()
        result.get()
      case _ => throw new IllegalArgumentException("runActors(body)")
    }
