package ssc.plugin.dataset

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable
import ssc.{Done, Prims, Runtime, Value}
import ssc.plugin.{NativePlugin, NativePluginContext}

/** Core-free local Dataset runtime shared by native VM and direct ASM. */
final class DatasetNativePlugin extends NativePlugin:
  def id: String = "58-dataset"

  private sealed trait Stage
  private final case class MapStage(fn: Value) extends Stage
  private final case class FilterStage(fn: Value) extends Stage
  private final case class FlatMapStage(fn: Value) extends Stage
  private final case class TakeStage(count: Int) extends Stage
  private final case class DropStage(count: Int) extends Stage
  private case object DistinctStage extends Stage
  private final case class GroupByStage(fn: Value) extends Stage
  private final case class ReduceByKeyStage(key: Value, combine: Value) extends Stage
  private final case class SortByStage(fn: Value) extends Stage

  private final case class Plan(
      source: () => Vector[Value],
      stages: Vector[Stage] = Vector.empty,
      parallel: Boolean = false)

  private def closure(arity: Int)(fn: List[Value] => Value): Value.ClosV =
    Value.ClosV(Runtime.emptyEnv, arity, env => Done(fn(env.toList)))

  private def list(values: IterableOnce[Value]): Value =
    val vector = Vector.from(values)
    var result: Value = Value.DataV("Nil", Vector.empty)
    val iterator = vector.reverseIterator
    while iterator.hasNext do
      result = Value.DataV("Cons", Vector(iterator.next(), result))
    result

  private def tuple(left: Value, right: Value): Value =
    Value.DataV("Tuple2", Vector(left, right))

  private def numeric(value: Value): Option[BigDecimal] = value match
    case Value.IntV(number)     => Some(BigDecimal(number))
    case Value.BigV(number)     => Some(BigDecimal(number))
    case Value.FloatV(number) if number.isFinite => Some(BigDecimal.decimal(number))
    case Value.DecimalV(text)   => Some(BigDecimal(text))
    case _                      => None

  private def compare(left: Value, right: Value): Int =
    (numeric(left), numeric(right), left, right) match
      case (Some(a), Some(b), _, _)                 => a.compare(b)
      case (_, _, Value.StrV(a), Value.StrV(b))     => a.compareTo(b)
      case (_, _, Value.BoolV(a), Value.BoolV(b))   => a.compareTo(b)
      case _                                         => Prims.display(left).compareTo(Prims.display(right))

  private def invoke(context: NativePluginContext, fn: Value, args: Value*): Value =
    context.invoke(fn, args.toList)

  private def callback(
      context: NativePluginContext,
      operation: String,
      fn: Value,
      args: Value*): Value =
    try invoke(context, fn, args*)
    catch case error: Throwable =>
      val rendered = args.map(Prims.display).mkString(", ")
      throw new IllegalArgumentException(s"Dataset.$operation callback failed for [$rendered]", error)

  private def pointwise(
      input: Vector[Value],
      parallel: Boolean)(fn: Vector[Value] => Vector[Value]): Vector[Value] =
    if !parallel || input.lengthCompare(2) < 0 then fn(input)
    else
      val workers = math.min(input.length, math.max(2, java.lang.Runtime.getRuntime.availableProcessors()))
      val chunkSize = (input.length + workers - 1) / workers
      val chunks = input.grouped(chunkSize).map(_.toVector).toVector
      val results = new Array[Vector[Value]](chunks.length)
      val failure = new AtomicReference[Throwable](null)
      val threads = chunks.zipWithIndex.map { case (chunk, index) =>
        Thread.ofVirtual().start { () =>
          try results(index) = fn(chunk)
          catch case error: Throwable => failure.compareAndSet(null, error)
        }
      }
      threads.foreach(_.join())
      val error = failure.get()
      if error != null then throw error
      results.iterator.flatten.toVector

  private def grouped(
      input: Vector[Value],
      key: Value => Value): mutable.LinkedHashMap[Value, mutable.ArrayBuffer[Value]] =
    val groups = mutable.LinkedHashMap.empty[Value, mutable.ArrayBuffer[Value]]
    input.foreach { item => groups.getOrElseUpdate(key(item), mutable.ArrayBuffer.empty) += item }
    groups

  private def stableSort(input: Vector[Value], key: Value => Value, descending: Boolean): Vector[Value] =
    input.zipWithIndex.sortWith { case ((left, li), (right, ri)) =>
      val compared = compare(key(left), key(right))
      if compared == 0 then li < ri else if descending then compared > 0 else compared < 0
    }.map(_._1)

  private def evaluate(plan: Plan, context: NativePluginContext): Vector[Value] =
    plan.stages.foldLeft(plan.source()) { (input, stage) =>
      stage match
        case MapStage(fn) =>
          pointwise(input, plan.parallel)(_.map(item => callback(context, "map", fn, item)))
        case FilterStage(fn) =>
          pointwise(input, plan.parallel)(_.filter { item =>
            callback(context, "filter", fn, item) match
              case Value.BoolV(result) => result
              case _ => throw new IllegalArgumentException("Dataset.filter callback must return Boolean")
          })
        case FlatMapStage(fn) =>
          pointwise(input, plan.parallel)(_.flatMap { item =>
            val value = callback(context, "flatMap", fn, item)
            try Prims.unlistPub(value).toVector
            catch case _: Throwable =>
              throw new IllegalArgumentException("Dataset.flatMap callback must return List")
          })
        case TakeStage(count) => input.take(math.max(0, count))
        case DropStage(count) => input.drop(math.max(0, count))
        case DistinctStage =>
          val seen = mutable.LinkedHashSet.empty[Value]
          input.filter(seen.add)
        case GroupByStage(fn) =>
          grouped(input, item => callback(context, "groupBy", fn, item)).iterator.map { case (key, values) =>
            tuple(key, list(values))
          }.toVector
        case ReduceByKeyStage(keyFn, combineFn) =>
          grouped(input, item => callback(context, "reduceByKey.key", keyFn, item)).iterator.map { case (key, values) =>
            val reduced = values.tail.foldLeft(values.head) { (left, right) =>
              callback(context, "reduceByKey.combine", combineFn, left, right)
            }
            tuple(key, reduced)
          }.toVector
        case SortByStage(fn) => stableSort(input, item => callback(context, "sortBy", fn, item), descending = false)
    }

  private def dataset(plan: Plan, context: NativePluginContext): Value =
    Value.ForeignV(DatasetValue(plan, context))

  private def datasetPlan(value: Value, operation: String): Plan = value match
    case Value.ForeignV(dataset: DatasetValue) => dataset.plan
    case _ => throw new IllegalArgumentException(s"Dataset.$operation expects another Dataset")

  private final class DatasetValue(val plan: Plan, context: NativePluginContext)
      extends Value.NamedMethodObj:
    private def append(stage: Stage): Value = dataset(plan.copy(stages = plan.stages :+ stage), context)
    private def values: Vector[Value] = evaluate(plan, context)
    private def intArg(args: List[Value], operation: String): Int = args match
      case Value.IntV(value) :: Nil => value.toInt
      case _ => throw new IllegalArgumentException(s"Dataset.$operation(n: Int)")

    def underlying: AnyRef = this

    def getField(name: String): Option[Value] = name match
      case "map" => Some(closure(1) {
        case fn :: Nil => append(MapStage(fn))
        case _ => throw new IllegalArgumentException("Dataset.map(callback)")
      })
      case "filter" => Some(closure(1) {
        case fn :: Nil => append(FilterStage(fn))
        case _ => throw new IllegalArgumentException("Dataset.filter(callback)")
      })
      case "flatMap" => Some(closure(1) {
        case fn :: Nil => append(FlatMapStage(fn))
        case _ => throw new IllegalArgumentException("Dataset.flatMap(callback)")
      })
      case "take" => Some(closure(1)(args => append(TakeStage(intArg(args, "take")))))
      case "drop" => Some(closure(1)(args => append(DropStage(intArg(args, "drop")))))
      case "distinct" => Some(closure(0)(_ => append(DistinctStage)))
      case "groupBy" => Some(closure(1) {
        case fn :: Nil => append(GroupByStage(fn))
        case _ => throw new IllegalArgumentException("Dataset.groupBy(callback)")
      })
      case "reduceByKey" => Some(closure(1) {
        case key :: Nil => closure(1) {
          case combine :: Nil => append(ReduceByKeyStage(key, combine))
          case _ => throw new IllegalArgumentException("Dataset.reduceByKey(key)(combine)")
        }
        case _ => throw new IllegalArgumentException("Dataset.reduceByKey(key)(combine)")
      })
      case "sortBy" => Some(closure(1) {
        case fn :: Nil => append(SortByStage(fn))
        case _ => throw new IllegalArgumentException("Dataset.sortBy(callback)")
      })
      case "union" => Some(closure(1) {
        case other :: Nil =>
          val right = datasetPlan(other, "union")
          dataset(Plan(() => evaluate(plan, context) ++ evaluate(right, context), parallel = plan.parallel), context)
        case _ => throw new IllegalArgumentException("Dataset.union(other)")
      })
      case "intersect" => Some(closure(1) {
        case other :: Nil =>
          val right = datasetPlan(other, "intersect")
          dataset(Plan(() =>
            val rightValues = evaluate(right, context).toSet
            val seen = mutable.LinkedHashSet.empty[Value]
            evaluate(plan, context).filter(value => rightValues.contains(value) && seen.add(value)),
            parallel = plan.parallel), context)
        case _ => throw new IllegalArgumentException("Dataset.intersect(other)")
      })
      case "zip" => Some(closure(1) {
        case other :: Nil =>
          val right = datasetPlan(other, "zip")
          dataset(Plan(() => evaluate(plan, context).zip(evaluate(right, context)).map(tuple).toVector,
            parallel = plan.parallel), context)
        case _ => throw new IllegalArgumentException("Dataset.zip(other)")
      })
      case "zipWithIndex" => Some(closure(0) { _ =>
        dataset(Plan(() => values.zipWithIndex.map((value, index) => tuple(value, Value.IntV(index.toLong))),
          parallel = plan.parallel), context)
      })
      case "runLocal" => Some(closure(0)(_ => dataset(plan.copy(parallel = false), context)))
      case "runParallel" => Some(closure(0)(_ => dataset(plan.copy(parallel = true), context)))
      case "collect" => Some(closure(0)(_ => list(values)))
      case "count" => Some(closure(0)(_ => Value.IntV(values.length.toLong)))
      case "reduce" => Some(closure(1) {
        case fn :: Nil =>
          val items = values
          if items.isEmpty then throw new IllegalArgumentException("Dataset.reduce: empty dataset")
          items.tail.foldLeft(items.head)((left, right) => invoke(context, fn, left, right))
        case _ => throw new IllegalArgumentException("Dataset.reduce(combine)")
      })
      case "fold" => Some(closure(1) {
        case zero :: Nil => closure(1) {
          case fn :: Nil => values.foldLeft(zero)((acc, item) => invoke(context, fn, acc, item))
          case _ => throw new IllegalArgumentException("Dataset.fold(zero)(combine)")
        }
        case _ => throw new IllegalArgumentException("Dataset.fold(zero)(combine)")
      })
      case "foreach" => Some(closure(1) {
        case fn :: Nil => values.foreach(item => invoke(context, fn, item)); Value.UnitV
        case _ => throw new IllegalArgumentException("Dataset.foreach(callback)")
      })
      case "first" => Some(closure(0)(_ => values.headOption
        .map(value => Value.DataV("Some", Vector(value)))
        .getOrElse(Value.DataV("None", Vector.empty))))
      case "min" => Some(closure(0) { _ =>
        val items = values
        if items.isEmpty then throw new IllegalArgumentException("Dataset.min: empty dataset")
        items.tail.foldLeft(items.head)((left, right) => if compare(left, right) <= 0 then left else right)
      })
      case "max" => Some(closure(0) { _ =>
        val items = values
        if items.isEmpty then throw new IllegalArgumentException("Dataset.max: empty dataset")
        items.tail.foldLeft(items.head)((left, right) => if compare(left, right) >= 0 then left else right)
      })
      case "sum" => Some(closure(0)(_ => values.foldLeft[Value](Value.IntV(0))(Prims.arithOp("+", _, _))))
      case "avg" => Some(closure(0) { _ =>
        val items = values
        if items.isEmpty then throw new IllegalArgumentException("Dataset.avg: empty dataset")
        val total = items.foldLeft(BigDecimal(0)) { (acc, value) =>
          acc + numeric(value).getOrElse(
            throw new IllegalArgumentException("Dataset.avg: numeric values required"))
        }
        Value.FloatV((total / BigDecimal(items.length)).toDouble)
      })
      case "top" => Some(closure(1) { args =>
        list(stableSort(values, identity, descending = true).take(math.max(0, intArg(args, "top"))))
      })
      case "takeOrdered" => Some(closure(1) { args =>
        list(stableSort(values, identity, descending = false).take(math.max(0, intArg(args, "takeOrdered"))))
      })
      case "countByValue" => Some(closure(0) { _ =>
        val counts = mutable.LinkedHashMap.empty[Value, Long]
        values.foreach(value => counts.update(value, counts.getOrElse(value, 0L) + 1L))
        Value.MapV.from(counts.iterator.map((key, count) => key -> Value.IntV(count)))
      })
      case "partition" => Some(closure(1) {
        case fn :: Nil =>
          val (yes, no) = values.partition { item =>
            invoke(context, fn, item) match
              case Value.BoolV(result) => result
              case _ => throw new IllegalArgumentException("Dataset.partition callback must return Boolean")
          }
          tuple(list(yes), list(no))
        case _ => throw new IllegalArgumentException("Dataset.partition(callback)")
      })
      case "mkString" => Some(closure(-1) { args =>
        val shown = values.map(Prims.display)
        val rendered = args match
          case Nil => shown.mkString
          case Value.StrV(separator) :: Nil => shown.mkString(separator)
          case Value.StrV(start) :: Value.StrV(separator) :: Value.StrV(end) :: Nil =>
            shown.mkString(start, separator, end)
          case _ => throw new IllegalArgumentException("Dataset.mkString[(separator)|(start, separator, end)]")
        Value.StrV(rendered)
      })
      case "toMap" => Some(closure(0) { _ =>
        val entries = values.map {
          case Value.DataV("Tuple2" | "Pair", IndexedSeq(key, value)) => key -> value
          case other => throw new IllegalArgumentException(
            s"Dataset.toMap: element is not a pair: ${Prims.display(other)}")
        }
        Value.MapV.from(entries)
      })
      case "toSet" => Some(closure(0) { _ =>
        val seen = mutable.LinkedHashSet.empty[Value]
        list(values.filter(seen.add))
      })
      case "saveToFile" => Some(closure(1) {
        case Value.StrV(path) :: Nil =>
          val text = values.map(Prims.display).mkString("", "\n", "\n")
          Files.write(Paths.get(path), text.getBytes(StandardCharsets.UTF_8))
          Value.UnitV
        case _ => throw new IllegalArgumentException("Dataset.saveToFile(path)")
      })
      case "fromGenerator" | "toGenerator" => Some(closure(-1) { _ =>
        throw new IllegalArgumentException(s"Dataset.$name requires the standard generator provider")
      })
      case _ => None

  def install(context: NativePluginContext): Unit =
    context.register("Dataset.of") { items => dataset(Plan(() => items.toVector), context) }
    context.register("Dataset.fromList") {
      case value :: Nil => dataset(Plan(() => Prims.unlistPub(value).toVector), context)
      case _ => throw new IllegalArgumentException("Dataset.fromList(list)")
    }
    context.register("Dataset.fromFile") {
      case Value.StrV(path) :: Nil =>
        dataset(Plan(() =>
          Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8).toArray(new Array[String](0))
            .iterator.map(Value.StrV(_): Value).toVector), context)
      case _ => throw new IllegalArgumentException("Dataset.fromFile(path)")
    }
    context.register("Dataset.fromGenerator") { _ =>
      throw new IllegalArgumentException("Dataset.fromGenerator requires the standard generator provider")
    }
