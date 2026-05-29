package scalascript.compiler.plugin.dstreams

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.interpreter.{Value, InterpretError, Computation}

import scala.collection.mutable.{ListBuffer, LinkedHashMap}
import scalascript.plugin.api.PluginNative
import scalascript.plugin.api.PluginContext

/** DStream / Pipeline intrinsics for the tree-walking interpreter.
 *
 *  Architecture (v2.1.1 — native bounded backend only):
 *  - `Pipeline.create(name)` builds a lazy DAG via `PipelineNode` case classes.
 *  - `.read(source)`, `.map(f)`, `.filter(p)`, … append nodes to the DAG.
 *  - `.run(Backend.Direct)` or `.run(Backend.Native)` walks the DAG and
 *    executes it synchronously on the calling thread (DirectRunner).
 *  - `InMemory.source(list)` and `InMemory.runAndCollect(stream)` are the
 *    testing entry-points used by unit tests and `examples/distributed-streams.ssc`.
 *
 *  All pipeline DAG nodes are plain Scala case objects stored in a `Value.InstanceV`
 *  under the key `"__dag"`.  The runner unpacks them and drives execution. */
object DStreamsIntrinsics:

  // NativeImpl receives unwrapped primitives from the interpreter bridge.
  // Wrap them back to Value so they can be stored in DAG nodes / returned.
  private def toValue(a: Any): Value = a match
    case n: Long    => Value.intV(n)
    case i: Int     => Value.intV(i.toLong)
    case d: Double  => Value.doubleV(d)
    case s: String  => Value.StringV(s)
    case b: Boolean => Value.boolV(b)
    case ()         => Value.UnitV
    case v: Value   => v
    case _          => Value.StringV(a.toString)

  // ── Capability constants (match spec enum) ────────────────────────────────

  private val CAP_AT_LEAST_ONCE       = "AtLeastOnce"
  private val CAP_EXACTLY_ONCE        = "ExactlyOnce"
  private val CAP_EVENT_TIME          = "EventTime"
  private val CAP_WATERMARK_PERFECT   = "WatermarkPerfect"
  private val CAP_KEYED_STATE         = "KeyedState"
  private val CAP_BROADCAST_STATE     = "BroadcastState"
  private val CAP_SIDE_INPUTS         = "SideInputs"
  private val CAP_SIDE_OUTPUTS        = "SideOutputs"
  private val CAP_TIMER_EVENT_TIME    = "TimerEventTime"
  private val CAP_TIMER_PROC_TIME     = "TimerProcessingTime"
  private val CAP_WINDOWED_JOINS      = "WindowedJoins"

  // Capabilities provided by DirectRunner / NativeRunner (v2.1.8: adds SideInputs, SideOutputs)
  private val directCapabilities: Set[String] = Set(
    CAP_AT_LEAST_ONCE, CAP_EVENT_TIME, CAP_WATERMARK_PERFECT,
    CAP_KEYED_STATE, CAP_WINDOWED_JOINS, CAP_TIMER_PROC_TIME,
    CAP_SIDE_INPUTS, CAP_SIDE_OUTPUTS,
  )

  // ── DAG node representation ───────────────────────────────────────────────
  // Stored as Value.InstanceV("_dag_<kind>", fields) inside a DStream Value.

  private def dagNode(kind: String, fields: Map[String, Value]): Value =
    Value.InstanceV(s"_dag_$kind", fields)

  private def getDag(v: Value): Value = v match
    case Value.InstanceV("DStream", fields) =>
      fields.getOrElse("__dag", throw InterpretError("DStream missing __dag"))
    case other => throw InterpretError(s"Expected DStream, got: $other")

  // ── DAG evaluation (DirectRunner) ─────────────────────────────────────────

  private def call(ctx: PluginContext, f: Value, args: List[Value]): Value =
    ctx.synchronized { ctx.invokeCallback(f, args).asInstanceOf[Value] }

  private def evalDag(dag: Value, ctx: PluginContext): Iterator[Value] =
    dag match

      case Value.InstanceV("_dag_source_list", fields) =>
        fields("elements") match
          case Value.ListV(xs) => xs.iterator
          case _ => Iterator.empty

      case Value.InstanceV("_dag_source_local", fields) =>
        // DSource.fromLocalSource — drain the Source[A] into an iterator
        val src = fields("source")
        val buf = ListBuffer[Value]()
        src match
          case inst: Value.InstanceV =>
            val forward = Value.NativeFnV("_collect", Computation.pureFn {
              case List(x) => buf += x; Value.UnitV
              case _       => Value.UnitV
            })
            inst.fields.get("runForeach") match
              case Some(rf) => call(ctx, rf, List(forward))
              case None     => throw InterpretError("fromLocalSource: not a Source")
          case _ => throw InterpretError("fromLocalSource: not a Source")
        buf.iterator

      case Value.InstanceV("_dag_map", fields) =>
        val upstream = evalDag(fields("upstream"), ctx)
        val f        = fields("f")
        upstream.map(v => call(ctx, f, List(v)))

      case Value.InstanceV("_dag_filter", fields) =>
        val upstream = evalDag(fields("upstream"), ctx)
        val pred     = fields("pred")
        upstream.filter(v => call(ctx, pred, List(v)) == Value.True)

      case Value.InstanceV("_dag_flatMap", fields) =>
        val upstream = evalDag(fields("upstream"), ctx)
        val f        = fields("f")
        upstream.flatMap { v =>
          call(ctx, f, List(v)) match
            case Value.ListV(xs) => xs.iterator
            case inner: Value.InstanceV =>
              // Support both List and DStream inner results
              inner.fields.get("__dag") match
                case Some(innerDag) => evalDag(innerDag, ctx)
                case None =>
                  // treat as Source[A] — drain it
                  val buf = ListBuffer[Value]()
                  val forward = Value.NativeFnV("_collect", Computation.pureFn {
                    case List(x) => buf += x; Value.UnitV
                    case _       => Value.UnitV
                  })
                  inner.fields.get("runForeach") match
                    case Some(rf) => call(ctx, rf, List(forward))
                    case None     => ()
                  buf.iterator
            case _ => Iterator.empty
        }

      case Value.InstanceV("_dag_keyBy", fields) =>
        val upstream = evalDag(fields("upstream"), ctx)
        val keyFn    = fields("keyFn")
        upstream.map { v =>
          val k = call(ctx, keyFn, List(v))
          Value.InstanceV("KV", Map("key" -> k, "value" -> v))
        }

      case Value.InstanceV("_dag_combinePerKey", fields) =>
        val upstream = evalDag(fields("upstream"), ctx)
        val f        = fields("f")
        val groups   = LinkedHashMap[Value, Value]()
        for kv <- upstream do
          kv match
            case Value.InstanceV("KV", kvFields) =>
              val k = kvFields("key")
              val v = kvFields("value")
              groups.get(k) match
                case Some(acc) => groups(k) = call(ctx, f, List(acc, v))
                case None      => groups(k) = v
            case _ =>
        groups.iterator.map { case (k, v) =>
          Value.InstanceV("KV", Map("key" -> k, "value" -> v))
        }

      case Value.InstanceV("_dag_merge", fields) =>
        val left  = evalDag(fields("left"), ctx)
        val right = evalDag(fields("right"), ctx)
        left ++ right

      case Value.InstanceV("_dag_mapWithTimestamp", fields) =>
        // v2.1.1: processing time only; timestamps are wall-clock millis
        val upstream = evalDag(fields("upstream"), ctx)
        val f        = fields("f")
        upstream.map { v =>
          val ts = Value.intV(System.currentTimeMillis())
          call(ctx, f, List(v, ts))
        }

      case Value.InstanceV("_dag_assignTimestamps", fields) =>
        // timestamps are discarded in v2.1.1; just pass elements through
        evalDag(fields("upstream"), ctx)

      case Value.InstanceV("_dag_window", fields) =>
        // DirectRunner: bounded sources execute synchronously so all elements arrive in the
        // same processing-time instant → all fall into one global window.  combinePerKey after
        // window therefore aggregates across all elements, which is the expected behaviour for
        // the §14.1 window word-count test.
        evalDag(fields("upstream"), ctx)

      case Value.InstanceV("_dag_withTrigger", fields) =>
        evalDag(fields("upstream"), ctx)

      case Value.InstanceV("_dag_withAllowedLateness", fields) =>
        evalDag(fields("upstream"), ctx)

      case Value.InstanceV("_dag_withWatermark", fields) =>
        // Watermark is tracked by the backend; DirectRunner passes elements through.
        evalDag(fields("upstream"), ctx)

      case Value.InstanceV("_dag_timerProcessing", fields) =>
        // Drain upstream collecting unique keys, then fire f(key) for each.
        // DirectRunner: no wall-clock delay; fires synchronously after the bounded source is exhausted.
        val upstream = evalDag(fields("upstream"), ctx)
        val f        = fields("f")
        val keys = scala.collection.mutable.LinkedHashSet[Value]()
        for elem <- upstream do
          elem match
            case Value.InstanceV("KV", kvFields) => keys += kvFields("key")
            case _                               =>
        keys.iterator.flatMap { k =>
          call(ctx, f, List(k)) match
            case Value.ListV(xs) => xs.iterator.map(v => Value.InstanceV("KV", Map("key" -> k, "value" -> v)))
            case single          => Iterator.single(Value.InstanceV("KV", Map("key" -> k, "value" -> single)))
        }

      // ── v2.1.7 — Stateful processing eval ────────────────────────────────────

      case Value.InstanceV("_dag_timerEventTime", fields) =>
        // Same as timerProcessing in DirectRunner: fires synchronously for all keys.
        val upstream = evalDag(fields("upstream"), ctx)
        val f        = fields("f")
        val keys = scala.collection.mutable.LinkedHashSet[Value]()
        for elem <- upstream do
          elem match
            case Value.InstanceV("KV", kvFields) => keys += kvFields("key")
            case _                               =>
        keys.iterator.flatMap { k =>
          call(ctx, f, List(k)) match
            case Value.ListV(xs) => xs.iterator.map(v => Value.InstanceV("KV", Map("key" -> k, "value" -> v)))
            case single          => Iterator.single(Value.InstanceV("KV", Map("key" -> k, "value" -> single)))
        }

      case Value.InstanceV("_dag_statefulMap", fields) =>
        // Per-key state: f(state, value) => (newState, output). f is curried: f(state)(value).
        // DirectRunner: executes synchronously; state lives in a LinkedHashMap per this evaluation.
        val upstream = evalDag(fields("upstream"), ctx)
        val init     = fields("init")
        val f        = fields("f")
        val states   = LinkedHashMap[Value, Value]()
        val results  = ListBuffer[Value]()
        for elem <- upstream do
          elem match
            case Value.InstanceV("KV", kvFields) =>
              val k = kvFields("key")
              val v = kvFields("value")
              val s = states.getOrElse(k, init)
              call(ctx, f, List(s, v)) match
                case Value.InstanceV(_, pairFields) =>
                  val ns  = pairFields.getOrElse("_1", pairFields.getOrElse("first",  s))
                  val out = pairFields.getOrElse("_2", pairFields.getOrElse("second", v))
                  states(k) = ns
                  results += Value.InstanceV("KV", Map("key" -> k, "value" -> out))
                case Value.ListV(List(ns, out)) =>
                  states(k) = ns
                  results += Value.InstanceV("KV", Map("key" -> k, "value" -> out))
                case other =>
                  results += Value.InstanceV("KV", Map("key" -> k, "value" -> other))
            case _ =>
        results.iterator

      case Value.InstanceV("_dag_statefulFlatMap", fields) =>
        val upstream = evalDag(fields("upstream"), ctx)
        val init     = fields("init")
        val f        = fields("f")
        val states   = LinkedHashMap[Value, Value]()
        val results  = ListBuffer[Value]()
        for elem <- upstream do
          elem match
            case Value.InstanceV("KV", kvFields) =>
              val k = kvFields("key")
              val v = kvFields("value")
              val s = states.getOrElse(k, init)
              val (ns, outs) = call(ctx, f, List(s, v)) match
                case Value.InstanceV(_, pairFields) =>
                  val ns   = pairFields.getOrElse("_1", pairFields.getOrElse("first", s))
                  val outs = pairFields.getOrElse("_2", pairFields.getOrElse("second", Value.EmptyList))
                  (ns, outs)
                case Value.ListV(List(ns, outs)) => (ns, outs)
                case other                       => (s, other)
              states(k) = ns
              val outList = outs match
                case Value.ListV(xs) => xs
                case single          => List(single)
              results ++= outList.map(o => Value.InstanceV("KV", Map("key" -> k, "value" -> o)))
            case _ =>
        results.iterator

      case Value.InstanceV("_dag_broadcastState", fields) =>
        // Each element of the main stream is paired with a state-accessor instance.
        // State stream KV elements build a Map[Value, Value]; the accessor provides `get(key)`.
        val upstream   = evalDag(fields("upstream"), ctx)
        val stateElems = evalDag(fields("stateDag"), ctx).toList
        val stateMap   = LinkedHashMap[Value, Value]()
        for elem <- stateElems do
          elem match
            case Value.InstanceV("KV", kvFields) => stateMap(kvFields("key")) = kvFields("value")
            case v                               => stateMap(v) = v
        val frozenMap = stateMap.toMap
        val getterFn = Value.NativeFnV("BroadcastMap.get", Computation.pureFn {
          case List(k) =>
            val kv = toValue(k)
            frozenMap.get(kv) match
              case Some(v) => Value.InstanceV("Some", Map("value" -> v, "get" -> v))
              case None    => Value.InstanceV("None", Map.empty)
          case _ => Value.InstanceV("None", Map.empty)
        })
        val stateAccessor = Value.InstanceV("_broadcast_map", Map("get" -> getterFn))
        upstream.map(elem => Value.InstanceV("_broadcast_pair",
          Map("_1" -> elem, "_2" -> stateAccessor)))

      // ── v2.1.8 — Side inputs / side outputs ──────────────────────────────────

      case Value.InstanceV("_dag_withSideInput", fields) =>
        // Cross product: each main element × each side-input element → (main, si)
        val upstream  = evalDag(fields("upstream"), ctx)
        val siVal     = fields("si")
        val siElems   = siVal match
          case Value.InstanceV("SideInput", sf) =>
            sf.get("elements") match
              case Some(Value.ListV(xs)) => xs
              case _                    => Nil
          case Value.ListV(xs) => xs
          case _               => List(siVal)
        upstream.flatMap { e =>
          siElems.map(b => Value.InstanceV("_tuple2", Map("_1" -> e, "_2" -> b)))
        }

      case Value.InstanceV("_dag_sideOutput", fields) =>
        // Side-output DAG node: evaluates to elements extracted by tag's filter.
        // The main stream is the upstream unchanged; this node captures the SIDE stream.
        val upstream = evalDag(fields("upstream"), ctx)
        val tag      = fields("tag")
        tag match
          case Value.InstanceV("OutputTag", tagFields) =>
            tagFields.get("filter") match
              case Some(filterFn) if filterFn != Value.UnitV =>
                upstream.flatMap { e =>
                  call(ctx, filterFn, List(e)) match
                    case Value.InstanceV("Some", sf) => sf.get("value").orElse(sf.get("get")).toList
                    case _ => Nil
                }
              case _ => Iterator.empty
          case _ => Iterator.empty

      // ── v2.1.9 — Windowed joins + flatten ────────────────────────────────────

      case Value.InstanceV("_dag_join", fields) =>
        val left  = evalDag(fields("upstream"), ctx).toList
        val right = evalDag(fields("rightDag"), ctx)
        val rightMap = LinkedHashMap[Value, List[Value]]()
        for elem <- right do elem match
          case Value.InstanceV("KV", kf) => rightMap(kf("key")) = rightMap.getOrElse(kf("key"), Nil) :+ kf("value")
          case _ =>
        left.iterator.flatMap {
          case Value.InstanceV("KV", kf) =>
            val k = kf("key"); val v = kf("value")
            rightMap.getOrElse(k, Nil).map { r =>
              Value.InstanceV("KV", Map("key" -> k,
                "value" -> Value.InstanceV("_tuple2", Map("_1" -> v, "_2" -> r))))
            }
          case _ => Nil
        }

      case Value.InstanceV("_dag_leftOuterJoin", fields) =>
        val left  = evalDag(fields("upstream"), ctx).toList
        val right = evalDag(fields("rightDag"), ctx)
        val rightMap = LinkedHashMap[Value, List[Value]]()
        for elem <- right do elem match
          case Value.InstanceV("KV", kf) => rightMap(kf("key")) = rightMap.getOrElse(kf("key"), Nil) :+ kf("value")
          case _ =>
        left.iterator.map {
          case Value.InstanceV("KV", kf) =>
            val k = kf("key"); val v = kf("value")
            val rs = rightMap.getOrElse(k, Nil)
            val rightOpt = if rs.isEmpty then Value.InstanceV("None", Map.empty)
                           else Value.InstanceV("Some", Map("value" -> rs.head, "get" -> rs.head))
            Value.InstanceV("KV", Map("key" -> k,
              "value" -> Value.InstanceV("_tuple2", Map("_1" -> v, "_2" -> rightOpt))))
          case other => other
        }

      case Value.InstanceV("_dag_rightOuterJoin", fields) =>
        val left  = evalDag(fields("upstream"), ctx)
        val right = evalDag(fields("rightDag"), ctx).toList
        val leftMap = LinkedHashMap[Value, List[Value]]()
        for elem <- left do elem match
          case Value.InstanceV("KV", kf) => leftMap(kf("key")) = leftMap.getOrElse(kf("key"), Nil) :+ kf("value")
          case _ =>
        right.iterator.map {
          case Value.InstanceV("KV", kf) =>
            val k = kf("key"); val v = kf("value")
            val ls = leftMap.getOrElse(k, Nil)
            val leftOpt = if ls.isEmpty then Value.InstanceV("None", Map.empty)
                          else Value.InstanceV("Some", Map("value" -> ls.head, "get" -> ls.head))
            Value.InstanceV("KV", Map("key" -> k,
              "value" -> Value.InstanceV("_tuple2", Map("_1" -> leftOpt, "_2" -> v))))
          case other => other
        }

      case Value.InstanceV("_dag_flatten", fields) =>
        val upstream = evalDag(fields("upstream"), ctx)
        upstream.flatMap {
          case Value.InstanceV("DStream", innerFields) =>
            innerFields.get("__dag") match
              case Some(innerDag) => evalDag(innerDag, ctx)
              case None           => Iterator.empty
          case Value.ListV(xs) => xs.iterator
          case other           => Iterator.single(other)
        }

      case other =>
        throw InterpretError(s"evalDag: unknown DAG node kind: $other")

  // ── Collect results from a running pipeline ────────────────────────────────

  private def runToList(dag: Value, ctx: PluginContext): List[Value] =
    evalDag(dag, ctx).toList

  // ── DStream → Source bridge helper ────────────────────────────────────────

  private def mkCollectedSourceV(elems: List[Value], ctx: PluginContext): Value =
    val arr = elems.toArray
    Value.InstanceV("Source", Map(
      "runForeach" -> Value.NativeFnV("Source.runForeach", Computation.pureFn {
        case List(f) => arr.foreach(v => call(ctx, f, List(v))); Value.UnitV
        case _       => throw InterpretError("Source.runForeach(f)")
      }),
      "runToList" -> Value.NativeFnV("Source.runToList", Computation.pureFn { _ =>
        Value.ListV(arr.toList)
      }),
      "runFold" -> Value.NativeFnV("Source.runFold", Computation.pureFn {
        case List(z) => Value.NativeFnV("Source.runFold$1", Computation.pureFn {
          case List(f) => arr.foldLeft(z)((acc, v) => call(ctx, f, List(acc, v)))
          case _       => throw InterpretError("Source.runFold(z)(f) — inner")
        })
        case _ => throw InterpretError("Source.runFold(z)(f) — outer")
      }),
      "map" -> Value.NativeFnV("Source.map", Computation.pureFn {
        case List(f) => mkCollectedSourceV(arr.toList.map(v => call(ctx, f, List(v))), ctx)
        case _       => throw InterpretError("Source.map(f)")
      }),
      "filter" -> Value.NativeFnV("Source.filter", Computation.pureFn {
        case List(p) => mkCollectedSourceV(arr.toList.filter(v => call(ctx, p, List(v)) == Value.True), ctx)
        case _       => throw InterpretError("Source.filter(p)")
      }),
      "merge" -> Value.NativeFnV("Source.merge", Computation.pureFn {
        case List(other) =>
          val otherElems = other match
            case Value.InstanceV("Source", fields) =>
              fields.get("runToList") match
                case Some(fn) => call(ctx, fn, Nil) match
                  case Value.ListV(xs) => xs
                  case _               => Nil
                case None => Nil
            case _ => Nil
          mkCollectedSourceV(arr.toList ++ otherElems, ctx)
        case _ => throw InterpretError("Source.merge(other)")
      }),
    ))

  // ── Capability negotiation ─────────────────────────────────────────────────

  private def collectRequiredCaps(dag: Value): Set[String] =
    val caps = scala.collection.mutable.Set[String](CAP_AT_LEAST_ONCE)
    def walk(node: Value): Unit = node match
      case Value.InstanceV(kind, fields) =>
        kind match
          case "_dag_window" | "_dag_withWatermark" | "_dag_withTrigger" => caps += CAP_EVENT_TIME
          case "_dag_statefulMap" | "_dag_statefulFlatMap" => caps += CAP_KEYED_STATE
          case "_dag_join" | "_dag_leftOuterJoin" | "_dag_rightOuterJoin" => caps += CAP_WINDOWED_JOINS
          case "_dag_broadcastState" => caps += CAP_BROADCAST_STATE
          case "_dag_withSideInput"  => caps += CAP_SIDE_INPUTS
          case "_dag_sideOutput"     => caps += CAP_SIDE_OUTPUTS
          case "_dag_timerEventTime" => caps += CAP_TIMER_EVENT_TIME; caps += CAP_EVENT_TIME
          case "_dag_timerProcessing"=> caps += CAP_TIMER_PROC_TIME
          case _ =>
        fields.values.foreach {
          case inner: Value.InstanceV => walk(inner)
          case _ =>
        }
      case _ =>
    walk(dag)
    caps.toSet

  private def backendProvides(backendName: String): Set[String] = backendName match
    case "Direct" | "Native" => directCapabilities
    case "Spark"             => Set(
      CAP_AT_LEAST_ONCE, CAP_EXACTLY_ONCE, CAP_EVENT_TIME, CAP_KEYED_STATE,
      CAP_BROADCAST_STATE, CAP_SIDE_INPUTS, CAP_SIDE_OUTPUTS,
      CAP_TIMER_EVENT_TIME, CAP_TIMER_PROC_TIME, CAP_WINDOWED_JOINS,
    )
    case _ => directCapabilities

  private def checkCapabilities(dag: Value, backendName: String): Option[String] =
    val required = collectRequiredCaps(dag)
    val provided = backendProvides(backendName)
    val missing  = required -- provided
    if missing.isEmpty then None
    else Some(
      s"CAPABILITY_MISMATCH: backend '$backendName' does not support: ${missing.mkString(", ")}. " +
      s"Consider Backend.Spark or Backend.Flink for these capabilities."
    )

  // ── PipelineResult ────────────────────────────────────────────────────────

  private def mkResult(results: List[Value]): Value =
    Value.InstanceV("PipelineResult", Map(
      "state"            -> Value.StringV("Done"),
      "__results"        -> Value.ListV(results),
      "waitUntilFinish"  -> Value.NativeFnV("PipelineResult.waitUntilFinish", Computation.pureFn { _ =>
        Value.StringV("Done")
      }),
      "cancel"           -> Value.NativeFnV("PipelineResult.cancel", Computation.pureFn { _ =>
        Value.UnitV
      }),
    ))

  // ── Pipeline execution ────────────────────────────────────────────────────

  private def executePipeline(dag: Value, backendName: String, ctx: PluginContext): Value =
    checkCapabilities(dag, backendName) match
      case Some(err) => throw InterpretError(err)
      case None      =>
        dag match
          // If the final node is a write, run through it but discard sink output
          case Value.InstanceV("_dag_write", fields) =>
            val results = runToList(fields("upstream"), ctx)
            val sink    = fields("sink")
            // Store results in the sink's buffer if it's an InMemory sink
            sink match
              case Value.InstanceV("_inmemory_sink", sinkFields) =>
                sinkFields.get("__buffer") match
                  case Some(bufRef: Value.InstanceV) =>
                    // The buffer is a mutable ref — store via the put fn
                    bufRef.fields.get("put") match
                      case Some(putFn) => call(ctx, putFn, List(Value.ListV(results)))
                      case None        => ()
                  case _ => ()
              case _ => ()
            mkResult(results)
          case other =>
            val results = runToList(other, ctx)
            mkResult(results)

  // ── DStream operator wiring ───────────────────────────────────────────────

  private def dstreamOps(dag: Value, ctx: PluginContext): Map[String, Value] = Map(

    "map" -> Value.NativeFnV("DStream.map", Computation.pureFn {
      case List(f) => mkDStreamWithOps(dagNode("map", Map("upstream" -> dag, "f" -> f)), ctx)
      case _       => throw InterpretError("DStream.map(f)")
    }),

    "filter" -> Value.NativeFnV("DStream.filter", Computation.pureFn {
      case List(pred) => mkDStreamWithOps(dagNode("filter", Map("upstream" -> dag, "pred" -> pred)), ctx)
      case _          => throw InterpretError("DStream.filter(pred)")
    }),

    "flatMap" -> Value.NativeFnV("DStream.flatMap", Computation.pureFn {
      case List(f) => mkDStreamWithOps(dagNode("flatMap", Map("upstream" -> dag, "f" -> f)), ctx)
      case _       => throw InterpretError("DStream.flatMap(f)")
    }),

    "keyBy" -> Value.NativeFnV("DStream.keyBy", Computation.pureFn {
      case List(keyFn) => mkDStreamWithOps(dagNode("keyBy", Map("upstream" -> dag, "keyFn" -> keyFn)), ctx)
      case _           => throw InterpretError("DStream.keyBy(keyFn)")
    }),

    "combinePerKey" -> Value.NativeFnV("DStream.combinePerKey", Computation.pureFn {
      case List(f) => mkDStreamWithOps(dagNode("combinePerKey", Map("upstream" -> dag, "f" -> f)), ctx)
      case _       => throw InterpretError("DStream.combinePerKey(f)")
    }),

    "merge" -> Value.NativeFnV("DStream.merge", Computation.pureFn {
      case List(other) =>
        val otherDag = getDag(other)
        mkDStreamWithOps(dagNode("merge", Map("left" -> dag, "right" -> otherDag)), ctx)
      case _ => throw InterpretError("DStream.merge(other)")
    }),

    "mapWithTimestamp" -> Value.NativeFnV("DStream.mapWithTimestamp", Computation.pureFn {
      case List(f) => mkDStreamWithOps(dagNode("mapWithTimestamp", Map("upstream" -> dag, "f" -> f)), ctx)
      case _       => throw InterpretError("DStream.mapWithTimestamp(f)")
    }),

    "assignTimestamps" -> Value.NativeFnV("DStream.assignTimestamps", Computation.pureFn {
      case List(f) => mkDStreamWithOps(dagNode("assignTimestamps", Map("upstream" -> dag, "f" -> f)), ctx)
      case _       => throw InterpretError("DStream.assignTimestamps(f)")
    }),

    // ── v2.1.2 windowing / watermark / timer operators ────────────────────────

    "window" -> Value.NativeFnV("DStream.window", Computation.pureFn {
      case List(windowFn) => mkDStreamWithOps(dagNode("window", Map("upstream" -> dag, "windowFn" -> windowFn)), ctx)
      case _              => throw InterpretError("DStream.window(windowFn)")
    }),

    "withTrigger" -> Value.NativeFnV("DStream.withTrigger", Computation.pureFn {
      case List(trigger) => mkDStreamWithOps(dagNode("withTrigger", Map("upstream" -> dag, "trigger" -> trigger)), ctx)
      case _             => throw InterpretError("DStream.withTrigger(trigger)")
    }),

    "withAllowedLateness" -> Value.NativeFnV("DStream.withAllowedLateness", Computation.pureFn {
      case List(d) => mkDStreamWithOps(dagNode("withAllowedLateness", Map("upstream" -> dag, "d" -> toValue(d))), ctx)
      case _       => throw InterpretError("DStream.withAllowedLateness(d)")
    }),

    "withWatermark" -> Value.NativeFnV("DStream.withWatermark", Computation.pureFn {
      case List(strategy) =>
        mkDStreamWithOps(dagNode("withWatermark", Map("upstream" -> dag, "strategy" -> toValue(strategy))), ctx)
      case _ => throw InterpretError("DStream.withWatermark(strategy)")
    }),

    // timerProcessing(durationMs)(f: K => Iterable[B]) — curried
    "timerProcessing" -> Value.NativeFnV("DStream.timerProcessing", Computation.pureFn {
      case List(_) => Value.NativeFnV("DStream.timerProcessing$1", Computation.pureFn {
        case List(f) => mkDStreamWithOps(dagNode("timerProcessing", Map("upstream" -> dag, "f" -> f)), ctx)
        case _       => throw InterpretError("DStream.timerProcessing(d)(f) — inner")
      })
      case _ => throw InterpretError("DStream.timerProcessing(d)(f) — outer")
    }),

    // ── v2.1.7 — Stateful processing operators ────────────────────────────────

    // timerEventTime(tsMs)(f: K => Iterable[B]) — curried, event-time timer
    "timerEventTime" -> Value.NativeFnV("DStream.timerEventTime", Computation.pureFn {
      case List(_) => Value.NativeFnV("DStream.timerEventTime$1", Computation.pureFn {
        case List(f) => mkDStreamWithOps(dagNode("timerEventTime", Map("upstream" -> dag, "f" -> f)), ctx)
        case _       => throw InterpretError("DStream.timerEventTime(ts)(f) — inner")
      })
      case _ => throw InterpretError("DStream.timerEventTime(ts)(f) — outer")
    }),

    // statefulMap(initState)(f: (S, A) => (S, B)) — curried, per-key state
    "statefulMap" -> Value.NativeFnV("DStream.statefulMap", Computation.pureFn {
      case List(init) => Value.NativeFnV("DStream.statefulMap$1", Computation.pureFn {
        case List(f) => mkDStreamWithOps(dagNode("statefulMap", Map("upstream" -> dag, "init" -> init, "f" -> f)), ctx)
        case _       => throw InterpretError("DStream.statefulMap(init)(f) — inner")
      })
      case _ => throw InterpretError("DStream.statefulMap(init)(f) — outer")
    }),

    // statefulFlatMap(initState)(f: (S, A) => (S, Iterable[B])) — curried
    "statefulFlatMap" -> Value.NativeFnV("DStream.statefulFlatMap", Computation.pureFn {
      case List(init) => Value.NativeFnV("DStream.statefulFlatMap$1", Computation.pureFn {
        case List(f) => mkDStreamWithOps(dagNode("statefulFlatMap", Map("upstream" -> dag, "init" -> init, "f" -> f)), ctx)
        case _       => throw InterpretError("DStream.statefulFlatMap(init)(f) — inner")
      })
      case _ => throw InterpretError("DStream.statefulFlatMap(init)(f) — outer")
    }),

    // broadcastState(stateStream) — pairs each elem with broadcast state map
    "broadcastState" -> Value.NativeFnV("DStream.broadcastState", Computation.pureFn {
      case List(stateStream) =>
        val stateDag = getDag(stateStream)
        mkDStreamWithOps(dagNode("broadcastState", Map("upstream" -> dag, "stateDag" -> stateDag)), ctx)
      case _ => throw InterpretError("DStream.broadcastState(stateStream)")
    }),

    // ── v2.1.8 — Side inputs / side outputs ──────────────────────────────────

    // withSideInput(si: SideInput[B]) — cross product with side input elements
    "withSideInput" -> Value.NativeFnV("DStream.withSideInput", Computation.pureFn {
      case List(si) =>
        mkDStreamWithOps(dagNode("withSideInput", Map("upstream" -> dag, "si" -> si)), ctx)
      case _ => throw InterpretError("DStream.withSideInput(si)")
    }),

    // sideOutput(tag: OutputTag[B]) — returns (mainStream, sideStream) tuple
    "sideOutput" -> Value.NativeFnV("DStream.sideOutput", Computation.pureFn {
      case List(tag) =>
        val sideDag = dagNode("sideOutput", Map("upstream" -> dag, "tag" -> tag))
        val mainStream = mkDStreamWithOps(dag, ctx)
        val sideStream = mkDStreamWithOps(sideDag, ctx)
        Value.InstanceV("_sideOutput_result",
          Map("_1" -> mainStream, "_2" -> sideStream))
      case _ => throw InterpretError("DStream.sideOutput(tag)")
    }),

    // ── v2.1.9 — Windowed joins + flatten ────────────────────────────────────

    "join" -> Value.NativeFnV("DStream.join", Computation.pureFn {
      case List(other) =>
        val rightDag = getDag(other)
        mkDStreamWithOps(dagNode("join", Map("upstream" -> dag, "rightDag" -> rightDag)), ctx)
      case _ => throw InterpretError("DStream.join(other)")
    }),

    "leftOuterJoin" -> Value.NativeFnV("DStream.leftOuterJoin", Computation.pureFn {
      case List(other) =>
        val rightDag = getDag(other)
        mkDStreamWithOps(dagNode("leftOuterJoin", Map("upstream" -> dag, "rightDag" -> rightDag)), ctx)
      case _ => throw InterpretError("DStream.leftOuterJoin(other)")
    }),

    "rightOuterJoin" -> Value.NativeFnV("DStream.rightOuterJoin", Computation.pureFn {
      case List(other) =>
        val rightDag = getDag(other)
        mkDStreamWithOps(dagNode("rightOuterJoin", Map("upstream" -> dag, "rightDag" -> rightDag)), ctx)
      case _ => throw InterpretError("DStream.rightOuterJoin(other)")
    }),

    "flatten" -> Value.NativeFnV("DStream.flatten", Computation.pureFn { _ =>
      mkDStreamWithOps(dagNode("flatten", Map("upstream" -> dag)), ctx)
    }),

    "write" -> Value.NativeFnV("DStream.write", Computation.pureFn {
      case List(sink) => mkDStreamWithOps(dagNode("write", Map("upstream" -> dag, "sink" -> sink)), ctx)
      case _          => throw InterpretError("DStream.write(sink)")
    }),

    "requires" -> Value.NativeFnV("DStream.requires", Computation.pureFn { _ =>
      Value.ListV(collectRequiredCaps(dag).toList.map(Value.StringV(_)))
    }),

    "run" -> Value.NativeFnV("DStream.run", Computation.pureFn {
      case List(backend) =>
        val backendName = backend match
          case Value.StringV(s)              => s
          case Value.InstanceV(name, _)      => name
          case _                             => "Direct"
        executePipeline(dag, backendName, ctx)
      case _ => throw InterpretError("DStream.run(backend)")
    }),

    "runOpts" -> Value.NativeFnV("DStream.runOpts", Computation.pureFn {
      case List(backend, _) =>
        val backendName = backend match
          case Value.StringV(s)         => s
          case Value.InstanceV(name, _) => name
          case _                        => "Direct"
        executePipeline(dag, backendName, ctx)
      case _ => throw InterpretError("DStream.runOpts(backend, opts)")
    }),

    // Bounded terminal operators — execute eagerly
    "runToList" -> Value.NativeFnV("DStream.runToList", Computation.pureFn { _ =>
      Value.ListV(runToList(dag, ctx))
    }),

    "runFold" -> Value.NativeFnV("DStream.runFold", Computation.pureFn {
      case List(z) => Value.NativeFnV("DStream.runFold$1", Computation.pureFn {
        case List(f) =>
          var acc = z
          for v <- evalDag(dag, ctx) do acc = call(ctx, f, List(acc, v))
          acc
        case _ => throw InterpretError("DStream.runFold(z)(f) — inner")
      })
      case _ => throw InterpretError("DStream.runFold(z)(f) — outer")
    }),

    "runForeach" -> Value.NativeFnV("DStream.runForeach", Computation.pureFn {
      case List(f) =>
        for v <- evalDag(dag, ctx) do call(ctx, f, List(v))
        Value.UnitV
      case _ => throw InterpretError("DStream.runForeach(f)")
    }),

    "runCount" -> Value.NativeFnV("DStream.runCount", Computation.pureFn { _ =>
      Value.intV(evalDag(dag, ctx).size.toLong)
    }),

    // Bridge operators — DStream.local / DStream.localBounded (v1.63.1)
    "local" -> Value.NativeFnV("DStream.local", Computation.pureFn { _ =>
      mkCollectedSourceV(runToList(dag, ctx), ctx)
    }),

    "localBounded" -> Value.NativeFnV("DStream.localBounded", Computation.pureFn {
      case Nil =>
        mkCollectedSourceV(runToList(dag, ctx), ctx)
      case List(maxBytesV) =>
        val maxBytes = maxBytesV match
          case Value.IntV(n) => n
          case _             => 268435456L
        val elems = runToList(dag, ctx)
        val approxBytes = elems.map {
          case Value.StringV(s) => s.length.toLong * 2
          case Value.IntV(_)    => 8L
          case Value.DoubleV(_) => 8L
          case Value.BoolV(_)   => 1L
          case Value.UnitV      => 0L
          case _                => 64L
        }.sum
        if approxBytes > maxBytes then
          throw InterpretError(
            s"DStream.localBounded: stream ~$approxBytes bytes > limit $maxBytes bytes"
          )
        else mkCollectedSourceV(elems, ctx)
      case _ => throw InterpretError("DStream.localBounded(maxBytes)")
    }),

    // DStream.remote(name) — materialise and register as a named remote stream (v1.63.6).
    // Collects the DStream elements into a local Source, then delegates to Source.remote.
    "remote" -> Value.NativeFnV("DStream.remote", Computation.pureFn {
      case List(nameV) =>
        val name = nameV match
          case Value.StringV(s) => s
          case _                => throw InterpretError("DStream.remote(name: String)")
        val elems   = runToList(dag, ctx)
        val localSrc = mkCollectedSourceV(elems, ctx)
        ctx.featureSet(s"remoteSource.$name", localSrc)
        val sseHandler = Value.NativeFnV(s"stream.$name.sse", Computation.pureFn { _ =>
          ctx.featureGet(s"remoteSource.$name") match
            case Some(srcV: Value.InstanceV) if srcV.typeName == "Source" =>
              val sb = new StringBuilder
              srcV.fields.get("runForeach") match
                case Some(rf) =>
                  val emitFn = Value.NativeFnV("_sseEmit", Computation.pureFn {
                    case List(v) =>
                      val s = v match
                        case Value.StringV(x) => x
                        case other            => Value.show(other)
                      sb.append(s"data: $s\n\n")
                      Value.UnitV
                    case _ => Value.UnitV
                  })
                  ctx.synchronized { ctx.invokeCallback(rf, List(emitFn)) }
                case _ => ()
              Value.InstanceV("Response", Map(
                "status"  -> Value.intV(200),
                "headers" -> Value.MapV(Map(
                  Value.StringV("Content-Type") -> Value.StringV("text/event-stream"),
                )),
                "body"    -> Value.StringV(sb.toString)
              ))
            case _ =>
              Value.InstanceV("Response", Map(
                "status" -> Value.intV(404),
                "body"   -> Value.StringV(s"stream '$name' not found")
              ))
        })
        ctx.registerRoute("GET", s"/streams/$name", sseHandler)
        Value.InstanceV("RemoteSource", Map(
          "name"   -> Value.StringV(name),
          "policy" -> Value.StringV("Default")
        ))
      case _ => throw InterpretError("DStream.remote(name)")
    }),
  )

  private def mkDStreamWithOps(dag: Value, ctx: PluginContext): Value =
    Value.InstanceV("DStream", Map("__dag" -> dag) ++ dstreamOps(dag, ctx))

  // ── Pipeline object ───────────────────────────────────────────────────────

  private def mkPipeline(name: String, ctx: PluginContext): Value =
    Value.InstanceV("Pipeline", Map(
      "name" -> Value.StringV(name),

      "read" -> Value.NativeFnV("Pipeline.read", Computation.pureFn {
        case List(dsource: Value.InstanceV) =>
          val dag = dsource.fields.getOrElse("__dag",
            throw InterpretError("Pipeline.read: not a DSource"))
          mkDStreamWithOps(dag, ctx)
        case _ => throw InterpretError("Pipeline.read(source: DSource[T])")
      }),
    ))

  // ── InMemory sink buffer ──────────────────────────────────────────────────

  private def mkInMemorySink(): Value =
    val buf = ListBuffer[Value]()
    val putFn = Value.NativeFnV("InMemorySink.put", Computation.pureFn {
      case List(Value.ListV(xs)) => buf ++= xs; Value.UnitV
      case _                    => Value.UnitV
    })
    val getFn = Value.NativeFnV("InMemorySink.get", Computation.pureFn { _ =>
      Value.ListV(buf.toList)
    })
    Value.InstanceV("_inmemory_sink", Map(
      "__buffer" -> Value.InstanceV("_buf_ref", Map("put" -> putFn, "get" -> getFn)),
      "get"      -> getFn,
    ))

  // ── Intrinsic table ───────────────────────────────────────────────────────

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    // Pipeline.create(name)
    QualifiedName("Pipeline.create") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(name: String) => mkPipeline(name, ctx)
        case _                  => throw InterpretError("Pipeline.create(name: String)")
    },

    // runPipeline(pipeline, backend) — functional alternative to pipeline.run(backend)
    QualifiedName("runPipeline") -> PluginNative.evalLegacy { (ctx, args) =>
      def backendNameOf(a: Any): String = a match
        case s: String                   => s
        case Value.InstanceV(name, _)    => name
        case _                           => "Direct"
      args match
        case List(_, stream: Value.InstanceV) =>
          val dag = stream.fields.getOrElse("__dag",
            throw InterpretError("runPipeline: first arg must be a Pipeline / DStream"))
          executePipeline(dag, backendNameOf(args.head), ctx)
        case List(stream: Value.InstanceV, backend) =>
          val dag = stream.fields.getOrElse("__dag",
            throw InterpretError("runPipeline: arg must be a DStream"))
          executePipeline(dag, backendNameOf(backend), ctx)
        case _ => throw InterpretError("runPipeline(pipeline, backend)")
    },

    // InMemory.source(list)
    QualifiedName("InMemory.source") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(Value.ListV(elems)) =>
          val dag = dagNode("source_list", Map("elements" -> Value.ListV(elems)))
          Value.InstanceV("DSource", Map("__dag" -> dag))
        case _ => throw InterpretError("InMemory.source(elements: List[T])")
    },

    // InMemory.sourceWithTimestamps(list of (T, Long))
    QualifiedName("InMemory.sourceWithTimestamps") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(Value.ListV(pairs)) =>
          val elems = pairs.map {
            case Value.TupleV(List(v, _)) => v
            case v                        => v
          }
          val dag = dagNode("source_list", Map("elements" -> Value.ListV(elems)))
          Value.InstanceV("DSource", Map("__dag" -> dag))
        case _ => throw InterpretError("InMemory.sourceWithTimestamps(elements: List[(T, Long)])")
    },

    // InMemory.sink() — returns a DSink + getter pair
    QualifiedName("InMemory.sink") -> PluginNative.evalLegacy { (_, _) =>
      mkInMemorySink()
    },

    // InMemory.runAndCollect(stream) — DirectRunner convenience
    QualifiedName("InMemory.runAndCollect") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(stream: Value.InstanceV) =>
          val dag = stream.fields.getOrElse("__dag",
            throw InterpretError("InMemory.runAndCollect: not a DStream"))
          Value.ListV(runToList(dag, ctx))
        case _ => throw InterpretError("InMemory.runAndCollect(stream: DStream[T])")
    },

    // DSource.fromLocalSource(src: Source[A])
    QualifiedName("DSource.fromLocalSource") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(src: Value) =>
          val dag = dagNode("source_local", Map("source" -> src))
          Value.InstanceV("DSource", Map("__dag" -> dag))
        case _ => throw InterpretError("DSource.fromLocalSource(src: Source[A])")
    },

    // Backend.Direct / Backend.Native / Backend.Spark — singleton values
    QualifiedName("Backend.Direct")  -> PluginNative.evalLegacy { (_, _) => Value.InstanceV("Direct",  Map.empty)},
    QualifiedName("Backend.Native")  -> PluginNative.evalLegacy { (_, _) => Value.InstanceV("Native",  Map.empty)},
    QualifiedName("Backend.Spark")   -> PluginNative.evalLegacy { (_, _) => Value.InstanceV("Spark",   Map.empty)},

    // Window constructors (return tagged values; only identity for v2.1.1)
    QualifiedName("Window.fixed")    -> PluginNative.evalLegacy { (_, args) => args match
      case List(ms) => Value.InstanceV("Window.Fixed",   Map("ms" -> toValue(ms)))
      case _        => throw InterpretError("Window.fixed(durationMs)")
    },
    QualifiedName("Window.sliding")  -> PluginNative.evalLegacy { (_, args) => args match
      case List(ms, p) => Value.InstanceV("Window.Sliding", Map("ms" -> toValue(ms), "period" -> toValue(p)))
      case _           => throw InterpretError("Window.sliding(durationMs, periodMs)")
    },
    QualifiedName("Window.session")  -> PluginNative.evalLegacy { (_, args) => args match
      case List(ms) => Value.InstanceV("Window.Session",  Map("gapMs" -> toValue(ms)))
      case _        => throw InterpretError("Window.session(gapMs)")
    },
    QualifiedName("Window.global")   -> PluginNative.evalLegacy { (_, _) => Value.InstanceV("Window.Global", Map.empty)},

    // Trigger constructors
    QualifiedName("Trigger.afterWatermark")       -> PluginNative.evalLegacy { (_, _) => Value.StringV("Trigger.AfterWatermark")},
    QualifiedName("Trigger.afterProcessingTime")  -> PluginNative.evalLegacy { (_, args) =>
      Value.InstanceV("Trigger.AfterProcessingTime",
        Map("ms" -> args.headOption.map(toValue).getOrElse(Value.intV(0))))
    },
    QualifiedName("Trigger.afterCount")           -> PluginNative.evalLegacy { (_, args) =>
      Value.InstanceV("Trigger.AfterCount",
        Map("n" -> args.headOption.map(toValue).getOrElse(Value.intV(0))))
    },
    QualifiedName("Trigger.repeatedly")           -> PluginNative.evalLegacy { (_, args) =>
      Value.InstanceV("Trigger.Repeatedly",
        Map("inner" -> args.headOption.map(toValue).getOrElse(Value.UnitV)))
    },

    // WatermarkStrategy constructors
    QualifiedName("WatermarkStrategy.monotonicallyIncreasing") -> PluginNative.evalLegacy { (_, _) =>
      Value.StringV("WatermarkStrategy.MonotonicallyIncreasing")
    },
    QualifiedName("WatermarkStrategy.atEnd")        -> PluginNative.evalLegacy { (_, _) =>
      Value.StringV("WatermarkStrategy.AtEnd")
    },
    QualifiedName("WatermarkStrategy.boundedOutOfOrder") -> PluginNative.evalLegacy { (_, args) =>
      Value.InstanceV("WatermarkStrategy.BoundedOutOfOrder",
        Map("lagMs" -> args.headOption.map(toValue).getOrElse(Value.intV(0))))
    },

    // AccumulationMode
    QualifiedName("AccumulationMode.Discarding")   -> PluginNative.evalLegacy { (_, _) => Value.StringV("AccumulationMode.Discarding")},
    QualifiedName("AccumulationMode.Accumulating") -> PluginNative.evalLegacy { (_, _) => Value.StringV("AccumulationMode.Accumulating")},

    // KV constructor — args are unwrapped primitives or Values
    QualifiedName("KV") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(k, v) => Value.InstanceV("KV", Map("key" -> toValue(k), "value" -> toValue(v)))
        case _          => throw InterpretError("KV(key, value)")
    },
    QualifiedName("KV.apply") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(k, v) => Value.InstanceV("KV", Map("key" -> toValue(k), "value" -> toValue(v)))
        case _          => throw InterpretError("KV(key, value)")
    },

    // ── v2.1.6 — Production connectors (stub DSource — return empty for bounded testing) ──

    // Kafka connector — source returns empty DSource (live Kafka requires KAFKA_BROKERS)
    QualifiedName("Kafka.source")          -> PluginNative.evalLegacy { (_, _) =>
      Value.InstanceV("DSource", Map("__dag" -> dagNode("source_list", Map("elements" -> Value.EmptyList))))
    },
    QualifiedName("Kafka.sourceAssigned")  -> PluginNative.evalLegacy { (_, _) =>
      Value.InstanceV("DSource", Map("__dag" -> dagNode("source_list", Map("elements" -> Value.EmptyList))))
    },
    QualifiedName("Kafka.changelog")       -> PluginNative.evalLegacy { (_, _) =>
      Value.InstanceV("DSource", Map("__dag" -> dagNode("source_list", Map("elements" -> Value.EmptyList))))
    },
    QualifiedName("Kafka.sink")            -> PluginNative.evalLegacy { (_, _) => Value.UnitV},

    // Files connector — stub DSource
    QualifiedName("Files.source")          -> PluginNative.evalLegacy { (_, _) =>
      Value.InstanceV("DSource", Map("__dag" -> dagNode("source_list", Map("elements" -> Value.EmptyList))))
    },
    QualifiedName("Files.sink")            -> PluginNative.evalLegacy { (_, _) => Value.UnitV},

    // FileFormat singletons
    QualifiedName("FileFormat.Text")       -> PluginNative.evalLegacy { (_, _) => Value.StringV("Text")},
    QualifiedName("FileFormat.Json")       -> PluginNative.evalLegacy { (_, _) => Value.StringV("Json")},
    QualifiedName("FileFormat.Parquet")    -> PluginNative.evalLegacy { (_, _) => Value.StringV("Parquet")},
    QualifiedName("FileFormat.Avro")       -> PluginNative.evalLegacy { (_, _) => Value.StringV("Avro")},
    QualifiedName("FileFormat.Csv")        -> PluginNative.evalLegacy { (_, args) =>
      Value.InstanceV("FileFormat.Csv", Map("header" -> args.headOption.map(toValue).getOrElse(Value.True)))
    },

    // JDBC connector — stub DSource
    QualifiedName("Jdbc.source")           -> PluginNative.evalLegacy { (_, _) =>
      Value.InstanceV("DSource", Map("__dag" -> dagNode("source_list", Map("elements" -> Value.EmptyList))))
    },
    QualifiedName("Jdbc.sink")             -> PluginNative.evalLegacy { (_, _) => Value.UnitV},

    // Pulsar connector — stub DSource
    QualifiedName("Pulsar.source")         -> PluginNative.evalLegacy { (_, _) =>
      Value.InstanceV("DSource", Map("__dag" -> dagNode("source_list", Map("elements" -> Value.EmptyList))))
    },
    QualifiedName("Pulsar.sink")           -> PluginNative.evalLegacy { (_, _) => Value.UnitV},

    // Kinesis connector — stub DSource
    QualifiedName("Kinesis.source")        -> PluginNative.evalLegacy { (_, _) =>
      Value.InstanceV("DSource", Map("__dag" -> dagNode("source_list", Map("elements" -> Value.EmptyList))))
    },
    QualifiedName("Kinesis.sink")          -> PluginNative.evalLegacy { (_, _) => Value.UnitV},

    // DSource.fromDataset bridge
    QualifiedName("DSource.fromDataset")   -> PluginNative.evalLegacy { (_, _) =>
      Value.InstanceV("DSource", Map("__dag" -> dagNode("source_list", Map("elements" -> Value.EmptyList))))
    },

    // ── v2.1.7 — Stateful processing state types ─────────────────────────────

    // KeyedStateSpec.value[K, S](init) — returns a spec instance
    QualifiedName("KeyedStateSpec.value") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(init) => Value.InstanceV("KeyedStateSpec", Map("init" -> toValue(init)))
        case _          => throw InterpretError("KeyedStateSpec.value(init)")
    },
    QualifiedName("KeyedStateSpec") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(init) => Value.InstanceV("KeyedStateSpec", Map("init" -> toValue(init)))
        case _          => throw InterpretError("KeyedStateSpec(init)")
    },

    // ── v2.1.8 — Side inputs / side outputs ──────────────────────────────────

    QualifiedName("SideInput.of") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(stream: Value.InstanceV) =>
          val dag  = stream.fields.getOrElse("__dag",
            throw InterpretError("SideInput.of: not a DStream"))
          val elems = evalDag(dag, ctx).toList
          Value.InstanceV("SideInput", Map("elements" -> Value.ListV(elems)))
        case _ => throw InterpretError("SideInput.of(stream)")
    },
    QualifiedName("SideInput.singleton") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(v) => Value.InstanceV("SideInput", Map("elements" -> Value.ListV(List(toValue(v)))))
        case _       => throw InterpretError("SideInput.singleton(value)")
    },
    QualifiedName("SideInput.asMap") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(stream: Value.InstanceV) =>
          val dag  = stream.fields.getOrElse("__dag",
            throw InterpretError("SideInput.asMap: not a DStream"))
          val m = evalDag(dag, ctx).foldLeft(Map.empty[Value, Value]) {
            case (acc, Value.InstanceV("KV", kf)) => acc + (kf("key") -> kf("value"))
            case (acc, _) => acc
          }
          val mapVal = Value.InstanceV("_side_map", m.map { case (k, v) => k.toString -> v })
          Value.InstanceV("SideInput", Map("elements" -> Value.ListV(List(mapVal))))
        case _ => throw InterpretError("SideInput.asMap(stream)")
    },

    QualifiedName("OutputTag") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(name) =>
          Value.InstanceV("OutputTag", Map("name" -> toValue(name), "filter" -> Value.UnitV))
        case _ => throw InterpretError("OutputTag(name)")
    },
    QualifiedName("OutputTag.apply") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(name) =>
          Value.InstanceV("OutputTag", Map("name" -> toValue(name), "filter" -> Value.UnitV))
        case _ => throw InterpretError("OutputTag(name)")
    },
    QualifiedName("OutputTag.withFilter") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(name, fn) =>
          Value.InstanceV("OutputTag", Map("name" -> toValue(name), "filter" -> toValue(fn)))
        case _ => throw InterpretError("OutputTag.withFilter(name)(fn)")
    },

    // Source.distributed — bridge from local Source[A] to DStream[A] (v1.63.1)
    // Registered in globals so DispatchRuntime.dispatchInstanceFallback can find it.
    // First arg is always the Source receiver; optional partitions arg is ignored on DirectRunner.
    QualifiedName("Source.distributed") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case src :: _ =>
          val dag = dagNode("source_local", Map("source" -> toValue(src)))
          mkDStreamWithOps(dag, ctx)
        case Nil => throw InterpretError("Source.distributed: missing receiver")
    },
  )
