package ssc.plugin.distributed

import scala.collection.mutable
import ssc.{Done, Prims, Runtime, Value}
import ssc.plugin.{NativePlugin, NativePluginContext}

/** Core-free deterministic local-loopback distributed MapReduce runtime. */
final class DistributedNativePlugin extends NativePlugin:
  def id: String = "61-distributed"

  private val handlers = mutable.LinkedHashMap.empty[String, Value]

  private def closure(arity: Int)(fn: List[Value] => Value): Value.ClosV =
    Value.ClosV(Runtime.emptyEnv, arity, env => Done(fn(env.toList)))

  private def list(values: IterableOnce[Value]): Value =
    val vector = Vector.from(values)
    var result: Value = Value.DataV("Nil", Vector.empty)
    val iterator = vector.reverseIterator
    while iterator.hasNext do
      result = Value.DataV("Cons", Vector(iterator.next(), result))
    result

  private def values(value: Value, operation: String): Vector[Value] =
    try Prims.unlistPub(value).toVector
    catch case _: Throwable =>
      throw new IllegalArgumentException(s"$operation expects a portable List")

  private def tuple(left: Value, right: Value): Value =
    Value.DataV("Tuple2", Vector(left, right))

  private def text(value: Value, operation: String): String = value match
    case Value.StrV(result) => result
    case _ => throw new IllegalArgumentException(s"$operation expects String")

  private def integer(value: Value, operation: String): Int = value match
    case Value.IntV(result) => Math.toIntExact(result)
    case _ => throw new IllegalArgumentException(s"$operation expects Int")

  private def boolean(value: Value, operation: String): Boolean = value match
    case Value.BoolV(result) => result
    case _ => throw new IllegalArgumentException(s"$operation expects Boolean")

  private def invoke(
      context: NativePluginContext,
      operation: String,
      fn: Value,
      args: Value*): Value =
    try context.invoke(fn, args.toList)
    catch case error: Throwable =>
      val rendered = args.map(Prims.display).mkString(", ")
      throw new IllegalArgumentException(
        s"Distributed.$operation callback failed for [$rendered]", error)

  private def registerHandler(descriptor: Value): Value = descriptor match
    case Value.DataV("NamedHandler", IndexedSeq(Value.StrV(name), fn)) =>
      handlers.synchronized(handlers.update(name, fn))
      Value.UnitV
    case _ => throw new IllegalArgumentException(
      "HandlerRegistry.register(NamedHandler(name, callback))")

  private def handler(name: String): Value = handlers.synchronized {
    handlers.getOrElse(name,
      throw new IllegalArgumentException(s"HandlerRegistry: no handler registered for '$name'"))
  }

  private def option(value: Option[Value]): Value = value
    .map(item => Value.DataV("Some", Vector(item)))
    .getOrElse(Value.DataV("None", Vector.empty))

  private val registryArities = List(
    "register" -> 1,
    "lookup" -> 1,
    "apply" -> 2,
    "applyPredicate" -> 2,
    "applyKey" -> 2,
    "applyCombine" -> 3,
    "clear" -> 0,
    "registeredNames" -> 0)

  private def registryOperation(
      context: NativePluginContext,
      name: String,
      args: List[Value]): Value = name match
    case "register" => args match
      case descriptor :: Nil => registerHandler(descriptor)
      case _ => throw new IllegalArgumentException("HandlerRegistry.register(handler)")
    case "lookup" => args match
      case Value.StrV(key) :: Nil => option(handlers.synchronized(handlers.get(key)))
      case _ => throw new IllegalArgumentException("HandlerRegistry.lookup(name)")
    case "apply" => args match
      case Value.StrV(key) :: value :: Nil =>
        invoke(context, s"handler[$key]", handler(key), value)
      case _ => throw new IllegalArgumentException("HandlerRegistry.apply(name, value)")
    case "applyPredicate" => args match
      case Value.StrV(key) :: value :: Nil =>
        invoke(context, s"predicate[$key]", handler(key), value) match
          case result: Value.BoolV => result
          case _ => throw new IllegalArgumentException(
            s"HandlerRegistry predicate '$key' must return Boolean")
      case _ => throw new IllegalArgumentException(
        "HandlerRegistry.applyPredicate(name, value)")
    case "applyKey" => args match
      case Value.StrV(key) :: value :: Nil =>
        invoke(context, s"key[$key]", handler(key), value)
      case _ => throw new IllegalArgumentException("HandlerRegistry.applyKey(name, value)")
    case "applyCombine" => args match
      case Value.StrV(key) :: left :: right :: Nil =>
        invoke(context, s"combine[$key]", handler(key), tuple(left, right))
      case _ => throw new IllegalArgumentException(
        "HandlerRegistry.applyCombine(name, left, right)")
    case "clear" => args match
      case Nil => handlers.synchronized(handlers.clear()); Value.UnitV
      case _ => throw new IllegalArgumentException("HandlerRegistry.clear()")
    case "registeredNames" => args match
      case Nil => list(handlers.synchronized(
        handlers.keysIterator.map(Value.StrV(_): Value).toVector))
      case _ => throw new IllegalArgumentException("HandlerRegistry.registeredNames()")
    case other => throw new IllegalArgumentException(
      s"unknown HandlerRegistry operation: $other")

  private final class HandlerRegistryValue(context: NativePluginContext)
      extends Value.NamedMethodObj:
    def underlying: AnyRef = this

    def getField(name: String): Option[Value] =
      registryArities.collectFirst { case (`name`, arity) =>
        closure(arity)(registryOperation(context, name, _))
      }

  private final class ClusterValue(val nodes: Vector[Value]) extends Value.NamedMethodObj:
    @volatile private var closed = false

    def underlying: AnyRef = this
    def requireOpen(operation: String): Unit =
      if closed then throw new IllegalStateException(s"$operation called on closed localLoopbackCluster")

    def getField(name: String): Option[Value] = name match
      case "nodes" => Some(list(nodes))
      case "pids" => Some(list(Vector.fill(nodes.length)(Value.UnitV)))
      case "close" => Some(closure(0) { _ => closed = true; Value.UnitV })
      case _ => None

  private def cluster(value: Value, operation: String): ClusterValue = value match
    case Value.ForeignV(result: ClusterValue) => result
    case _ => throw new IllegalArgumentException(s"$operation expects localLoopbackCluster")

  private def stage(value: Value, operation: String): Vector[Value] = value match
    case Value.DataV("Stage", IndexedSeq(ops)) => values(ops, operation)
    case _ => throw new IllegalArgumentException(s"$operation expects Stage")

  private def applyStage(
      context: NativePluginContext,
      input: Vector[Value],
      operations: Vector[Value]): Vector[Value] =
    operations.foldLeft(input) { (items, operation) => operation match
      case Value.DataV("MapOp", IndexedSeq(Value.StrV(name))) =>
        val fn = handler(name)
        items.map(item => invoke(context, s"map[$name]", fn, item))
      case Value.DataV("FilterOp", IndexedSeq(Value.StrV(name))) =>
        val fn = handler(name)
        items.filter { item =>
          invoke(context, s"filter[$name]", fn, item) match
            case Value.BoolV(result) => result
            case _ => throw new IllegalArgumentException(
              s"Distributed filter '$name' must return Boolean")
        }
      case Value.DataV("FlatMapOp", IndexedSeq(Value.StrV(name))) =>
        val fn = handler(name)
        items.flatMap { item =>
          values(invoke(context, s"flatMap[$name]", fn, item),
            s"Distributed flatMap '$name'")
        }
      case other => throw new IllegalArgumentException(
        s"Distributed Stage contains invalid operation: ${Prims.display(other)}")
    }

  private def partition(input: Vector[Value], nodeCount: Int): Vector[Vector[Value]] =
    if input.isEmpty then Vector(Vector.empty)
    else
      val count = math.max(1, nodeCount)
      val size = (input.length + count - 1) / count
      input.grouped(size).map(_.toVector).toVector

  private def runStage(
      context: NativePluginContext,
      input: Vector[Value],
      operations: Vector[Value],
      cluster: ClusterValue): Vector[Value] =
    cluster.requireOpen("distributed stage")
    partition(input, cluster.nodes.length).flatMap(part => applyStage(context, part, operations))

  private def result(items: IterableOnce[Value]): Value =
    Value.DataV("DistributedResult", Vector(list(items), list(Vector.empty)))

  private def runDistributed(context: NativePluginContext, args: List[Value]): Value = args match
    case data :: stageValue :: clusterValue :: retries :: allowPartial :: Nil =>
      integer(retries, "runDistributed retries")
      boolean(allowPartial, "runDistributed allowPartial")
      val local = cluster(clusterValue, "runDistributed")
      result(runStage(context, values(data, "runDistributed data"),
        stage(stageValue, "runDistributed stage"), local))
    case _ => throw new IllegalArgumentException(
      "runDistributed(data, stage, cluster, retries, allowPartial)")

  private def runShuffle(context: NativePluginContext, args: List[Value]): Value = args match
    case data :: shuffleValue :: clusterValue :: retries :: allowPartial :: Nil =>
      integer(retries, "runDistributedShuffle retries")
      boolean(allowPartial, "runDistributedShuffle allowPartial")
      val local = cluster(clusterValue, "runDistributedShuffle")
      local.requireOpen("runDistributedShuffle")
      val (pre, keyName, combineName, isGroupBy) = shuffleValue match
        case Value.DataV("ShuffleStage", IndexedSeq(
              preShuffle,
              Value.StrV(key),
              Value.StrV(combine),
              Value.BoolV(groupBy))) => (preShuffle, key, combine, groupBy)
        case _ => throw new IllegalArgumentException(
          "runDistributedShuffle expects ShuffleStage")

      val prepared = runStage(context, values(data, "runDistributedShuffle data"),
        stage(pre, "ShuffleStage.preShuffle"), local)
      val keyFn = handler(keyName)
      val groups = mutable.LinkedHashMap.empty[Value, mutable.ArrayBuffer[Value]]
      prepared.foreach { item =>
        val key = invoke(context, s"key[$keyName]", keyFn, item)
        groups.getOrElseUpdate(key, mutable.ArrayBuffer.empty) += item
      }
      if isGroupBy then
        result(groups.iterator.map { case (key, grouped) => tuple(key, list(grouped)) })
      else
        if combineName.isEmpty then throw new IllegalArgumentException(
          "ShuffleStage combineHandlerName is required for reduceByKey")
        val combine = handler(combineName)
        result(groups.iterator.map { case (key, grouped) =>
          val reduced = grouped.tail.foldLeft(grouped.head) { (left, right) =>
            invoke(context, s"combine[$combineName]", combine, tuple(left, right))
          }
          tuple(key, reduced)
        })
    case _ => throw new IllegalArgumentException(
      "runDistributedShuffle(data, stage, cluster, retries, allowPartial)")

  def install(context: NativePluginContext): Unit =
    handlers.synchronized(handlers.clear())
    context.registerFields("NamedHandler", Vector("name", "fn"))
    context.registerFields("Node", Vector("address"))
    context.registerFields("MapOp", Vector("name"))
    context.registerFields("FilterOp", Vector("name"))
    context.registerFields("FlatMapOp", Vector("name"))
    context.registerFields("Stage", Vector("ops"))
    context.registerFields("ShuffleStage",
      Vector("preShuffle", "keyHandlerName", "combineHandlerName", "isGroupBy"))
    context.registerFields("DistributedResult", Vector("results", "failures"))

    context.registerGlobal("NamedHandler", 2) {
      case Value.StrV(name) :: fn :: Nil => Value.DataV("NamedHandler", Vector(Value.StrV(name), fn))
      case _ => throw new IllegalArgumentException("NamedHandler(name, callback)")
    }
    registryArities.foreach { case (name, _) =>
      context.register(s"HandlerRegistry.$name")(registryOperation(context, name, _))
    }
    context.registerValue("HandlerRegistry", Value.ForeignV(HandlerRegistryValue(context)))
    context.registerGlobal("Node", 1) {
      case Value.StrV(address) :: Nil => Value.DataV("Node", Vector(Value.StrV(address)))
      case _ => throw new IllegalArgumentException("Node(address)")
    }
    context.registerGlobal("MapOp", 1) {
      case Value.StrV(name) :: Nil => Value.DataV("MapOp", Vector(Value.StrV(name)))
      case _ => throw new IllegalArgumentException("MapOp(name)")
    }
    context.registerGlobal("FilterOp", 1) {
      case Value.StrV(name) :: Nil => Value.DataV("FilterOp", Vector(Value.StrV(name)))
      case _ => throw new IllegalArgumentException("FilterOp(name)")
    }
    context.registerGlobal("FlatMapOp", 1) {
      case Value.StrV(name) :: Nil => Value.DataV("FlatMapOp", Vector(Value.StrV(name)))
      case _ => throw new IllegalArgumentException("FlatMapOp(name)")
    }
    context.registerGlobal("Stage", 1) {
      case operations :: Nil => Value.DataV("Stage", Vector(operations))
      case _ => throw new IllegalArgumentException("Stage(operations)")
    }
    context.registerGlobal("ShuffleStage", 4) {
      case pre :: Value.StrV(key) :: Value.StrV(combine) :: Value.BoolV(groupBy) :: Nil =>
        Value.DataV("ShuffleStage",
          Vector(pre, Value.StrV(key), Value.StrV(combine), Value.BoolV(groupBy)))
      case _ => throw new IllegalArgumentException(
        "ShuffleStage(preShuffle, keyHandlerName, combineHandlerName, isGroupBy)")
    }
    context.registerGlobal("localLoopbackCluster", -1) { nodes =>
      nodes.foreach {
        case Value.DataV("Node", IndexedSeq(Value.StrV(_))) => ()
        case _ => throw new IllegalArgumentException("localLoopbackCluster(Node*)")
      }
      Value.ForeignV(ClusterValue(nodes.toVector))
    }
    context.registerGlobal("runDistributed", 5)(runDistributed(context, _))
    context.registerGlobal("runDistributedShuffle", 5)(runShuffle(context, _))
