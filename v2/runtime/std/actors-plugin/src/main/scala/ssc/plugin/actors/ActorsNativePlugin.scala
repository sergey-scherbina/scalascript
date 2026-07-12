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

  private val current = new ThreadLocal[Mailbox]()
  private val behaviors = ConcurrentHashMap[String, Value]()
  private val namedRefs = ConcurrentHashMap[String, Value]()
  private val localNode = AtomicReference[String]("local")
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
      mailbox.queue.put(message)
      signal(mailbox)

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
      case Value.ForeignV(mailbox: Mailbox) :: _reason :: Nil =>
        if !mailbox.dead then
          mailbox.dead = true
          val thread = mailbox.thread
          if thread != null then thread.interrupt()
          signal(mailbox)
        Value.UnitV
      case _ => throw new IllegalArgumentException("exit(pid, reason)")
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

    context.registerGlobal("runActors", 1) {
      case body :: Nil =>
        behaviors.clear()
        namedRefs.clear()
        localNode.set("local")
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
