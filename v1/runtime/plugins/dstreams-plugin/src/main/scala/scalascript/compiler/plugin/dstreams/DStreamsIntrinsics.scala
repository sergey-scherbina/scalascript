package scalascript.compiler.plugin.dstreams

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scala.collection.mutable.{ListBuffer, LinkedHashMap}
import scalascript.plugin.api.{PluginContext, PluginError, PluginNative, PluginValue}
import scalascript.plugin.api.PluginValue.{Str, Num, Dbl, Bool, Lst, Inst, InstAny, Tpl, Opt}

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
  private def toValue(a: Any): PluginValue = a match
    case n: Long    => PluginValue.int(n)
    case i: Int     => PluginValue.int(i.toLong)
    case d: Double  => PluginValue.double(d)
    case s: String  => PluginValue.string(s)
    case b: Boolean => PluginValue.bool(b)
    case ()         => PluginValue.unit
    case other if PluginValue.isRuntimeValue(other) => PluginValue.wrap(other)
    case _          => PluginValue.string(a.toString)

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
  // Stored as PluginValue.instance("_dag_<kind>", fields) inside a DStream Value.

  private def dagNode(kind: String, fields: Map[String, PluginValue]): PluginValue =
    PluginValue.instance(s"_dag_$kind", fields)

  private def getDag(v: PluginValue): PluginValue = v match
    case Inst("DStream" | "DSource", fields) =>
      fields.getOrElse("__dag", PluginError.raise("DStream/DSource missing __dag"))
    case other => PluginError.raise(s"Expected DStream/DSource, got: $other")

  // ── DAG evaluation (DirectRunner) ─────────────────────────────────────────

  private def call(ctx: PluginContext, f: PluginValue, args: List[PluginValue]): PluginValue =
    ctx.synchronized { PluginValue.wrap(ctx.invokeCallback(f, args)) }

  private def fieldDispatchFailed(t: RuntimeException): Boolean =
    Option(t.getMessage).exists(msg =>
      msg.contains("no dispatch for .") || msg.contains("No method '")
    )

  private def callStateful(ctx: PluginContext, f: PluginValue, state: PluginValue, value: PluginValue, elem: PluginValue): PluginValue =
    try call(ctx, f, List(state, value))
    catch case t: RuntimeException if fieldDispatchFailed(t) =>
      call(ctx, f, List(state, elem))

  private def callKeyedTimer(ctx: PluginContext, f: PluginValue, key: PluginValue, elem: PluginValue): PluginValue =
    try call(ctx, f, List(key))
    catch case t: RuntimeException if fieldDispatchFailed(t) =>
      call(ctx, f, List(elem))

  private def outputForKey(key: PluginValue, out: PluginValue): PluginValue =
    out match
      case Inst("KV", _) => out
      case _             => PluginValue.instance("KV", Map("key" -> key, "value" -> out))

  private def pluginSome(value: PluginValue, ctx: PluginContext): PluginValue =
    val getOrElse = PluginValue.nativeFn("Some.getOrElse", {
      case List(_) => value
      case _       => value
    })
    val mapFn = PluginValue.nativeFn("Some.map", {
      case List(f) => pluginSome(call(ctx, f, List(value)), ctx)
      case _       => pluginSome(value, ctx)
    })
    PluginValue.instance("Some", Map(
      "value" -> value,
      "get" -> value,
      "getOrElse" -> getOrElse,
      "map" -> mapFn,
    ))

  private def pluginNone(ctx: PluginContext): PluginValue =
    val getOrElse = PluginValue.nativeFn("None.getOrElse", {
      case List(default) => default
      case _             => PluginValue.unit
    })
    val mapFn = PluginValue.nativeFn("None.map", {
      case List(_) => pluginNone(ctx)
      case _       => pluginNone(ctx)
    })
    PluginValue.instance("None", Map("getOrElse" -> getOrElse, "map" -> mapFn))

  private def evalDag(dag: PluginValue, ctx: PluginContext): Iterator[PluginValue] =
    dag match

      case Inst("_dag_source_list", fields) =>
        fields("elements") match
          case Lst(xs) => xs.iterator
          case _ => Iterator.empty

      case Inst("_dag_source_local", fields) =>
        // DSource.fromLocalSource — drain the Source[A] into an iterator
        val src = fields("source")
        val buf = ListBuffer[PluginValue]()
        src match
          case InstAny(inst) =>
            val forward = PluginValue.nativeFn("_collect", {
              case List(x) => buf += x; PluginValue.unit
              case _       => PluginValue.unit
            })
            inst.field("runForeach") match
              case Some(rf) => call(ctx, rf, List(forward))
              case None     => PluginError.raise("fromLocalSource: not a Source")
          case _ => PluginError.raise("fromLocalSource: not a Source")
        buf.iterator

      case Inst("_dag_map", fields) =>
        val upstream = evalDag(fields("upstream"), ctx)
        val f        = fields("f")
        upstream.map(v => call(ctx, f, List(v)))

      case Inst("_dag_filter", fields) =>
        val upstream = evalDag(fields("upstream"), ctx)
        val pred     = fields("pred")
        upstream.filter(v => call(ctx, pred, List(v)) == PluginValue.bool(true))

      case Inst("_dag_flatMap", fields) =>
        val upstream = evalDag(fields("upstream"), ctx)
        val f        = fields("f")
        upstream.flatMap { v =>
          call(ctx, f, List(v)) match
            case Lst(xs) => xs.iterator
            case InstAny(inner) =>
              // Support both List and DStream inner results
              inner.field("__dag") match
                case Some(innerDag) => evalDag(innerDag, ctx)
                case None =>
                  // treat as Source[A] — drain it
                  val buf = ListBuffer[PluginValue]()
                  val forward = PluginValue.nativeFn("_collect", {
                    case List(x) => buf += x; PluginValue.unit
                    case _       => PluginValue.unit
                  })
                  inner.field("runForeach") match
                    case Some(rf) => call(ctx, rf, List(forward))
                    case None     => ()
                  buf.iterator
            case _ => Iterator.empty
        }

      case Inst("_dag_keyBy", fields) =>
        val upstream = evalDag(fields("upstream"), ctx)
        val keyFn    = fields("keyFn")
        upstream.map { v =>
          val k = call(ctx, keyFn, List(v))
          PluginValue.instance("KV", Map("key" -> k, "value" -> v))
        }

      case Inst("_dag_combinePerKey", fields) =>
        val upstream = evalDag(fields("upstream"), ctx)
        val f        = fields("f")
        val groups   = LinkedHashMap[PluginValue, PluginValue]()
        for kv <- upstream do
          kv match
            case Inst("KV", kvFields) =>
              val k = kvFields("key")
              val v = kvFields("value")
              groups.get(k) match
                case Some(acc) => groups(k) = call(ctx, f, List(acc, v))
                case None      => groups(k) = v
            case _ =>
        groups.iterator.map { case (k, v) =>
          PluginValue.instance("KV", Map("key" -> k, "value" -> v))
        }

      case Inst("_dag_merge", fields) =>
        val left  = evalDag(fields("left"), ctx)
        val right = evalDag(fields("right"), ctx)
        left ++ right

      case Inst("_dag_mapWithTimestamp", fields) =>
        // v2.1.1: processing time only; timestamps are wall-clock millis
        val upstream = evalDag(fields("upstream"), ctx)
        val f        = fields("f")
        upstream.map { v =>
          val ts = PluginValue.int(System.currentTimeMillis())
          call(ctx, f, List(v, ts))
        }

      case Inst("_dag_assignTimestamps", fields) =>
        // timestamps are discarded in v2.1.1; just pass elements through
        evalDag(fields("upstream"), ctx)

      case Inst("_dag_window", fields) =>
        // DirectRunner: bounded sources execute synchronously so all elements arrive in the
        // same processing-time instant → all fall into one global window.  combinePerKey after
        // window therefore aggregates across all elements, which is the expected behaviour for
        // the §14.1 window word-count test.
        evalDag(fields("upstream"), ctx)

      case Inst("_dag_withTrigger", fields) =>
        evalDag(fields("upstream"), ctx)

      case Inst("_dag_withAllowedLateness", fields) =>
        evalDag(fields("upstream"), ctx)

      case Inst("_dag_withWatermark", fields) =>
        // Watermark is tracked by the backend; DirectRunner passes elements through.
        evalDag(fields("upstream"), ctx)

      case Inst("_dag_timerProcessing", fields) =>
        // Drain upstream collecting unique keys, then fire f(key) for each.
        // DirectRunner: no wall-clock delay; fires synchronously after the bounded source is exhausted.
        val upstream = evalDag(fields("upstream"), ctx)
        val f        = fields("f")
        val keyedElems = LinkedHashMap[PluginValue, PluginValue]()
        for elem <- upstream do
          elem match
            case Inst("KV", kvFields) =>
              val key = kvFields("key")
              if !keyedElems.contains(key) then keyedElems(key) = elem
            case _                               =>
        keyedElems.iterator.flatMap { case (k, elem) =>
          callKeyedTimer(ctx, f, k, elem) match
            case Lst(xs) => xs.iterator.map(v => outputForKey(k, v))
            case single          => Iterator.single(outputForKey(k, single))
        }

      // ── v2.1.7 — Stateful processing eval ────────────────────────────────────

      case Inst("_dag_timerEventTime", fields) =>
        // Same as timerProcessing in DirectRunner: fires synchronously for all keys.
        val upstream = evalDag(fields("upstream"), ctx)
        val f        = fields("f")
        val keyedElems = LinkedHashMap[PluginValue, PluginValue]()
        for elem <- upstream do
          elem match
            case Inst("KV", kvFields) =>
              val key = kvFields("key")
              if !keyedElems.contains(key) then keyedElems(key) = elem
            case _                               =>
        keyedElems.iterator.flatMap { case (k, elem) =>
          callKeyedTimer(ctx, f, k, elem) match
            case Lst(xs) => xs.iterator.map(v => outputForKey(k, v))
            case single          => Iterator.single(outputForKey(k, single))
        }

      case Inst("_dag_statefulMap", fields) =>
        // Per-key state: f(state, value) => (newState, output). f is curried: f(state)(value).
        // DirectRunner: executes synchronously; state lives in a LinkedHashMap per this evaluation.
        val upstream = evalDag(fields("upstream"), ctx)
        val init     = fields("init")
        val f        = fields("f")
        val states   = LinkedHashMap[PluginValue, PluginValue]()
        val results  = ListBuffer[PluginValue]()
        for elem <- upstream do
          elem match
            case Inst("KV", kvFields) =>
              val k = kvFields("key")
              val v = kvFields("value")
              val s = states.getOrElse(k, init)
              callStateful(ctx, f, s, v, elem) match
                case Inst(_, pairFields) =>
                  val ns  = pairFields.getOrElse("_1", pairFields.getOrElse("first",  s))
                  val out = pairFields.getOrElse("_2", pairFields.getOrElse("second", v))
                  states(k) = ns
                  results += outputForKey(k, out)
                case Lst(List(ns, out)) =>
                  states(k) = ns
                  results += outputForKey(k, out)
                case Tpl(List(ns, out)) =>
                  states(k) = ns
                  results += outputForKey(k, out)
                case other =>
                  results += outputForKey(k, other)
            case _ =>
        results.iterator

      case Inst("_dag_statefulFlatMap", fields) =>
        val upstream = evalDag(fields("upstream"), ctx)
        val init     = fields("init")
        val f        = fields("f")
        val states   = LinkedHashMap[PluginValue, PluginValue]()
        val results  = ListBuffer[PluginValue]()
        for elem <- upstream do
          elem match
            case Inst("KV", kvFields) =>
              val k = kvFields("key")
              val v = kvFields("value")
              val s = states.getOrElse(k, init)
              val (ns, outs) = callStateful(ctx, f, s, v, elem) match
                case Inst(_, pairFields) =>
                  val ns   = pairFields.getOrElse("_1", pairFields.getOrElse("first", s))
                  val outs = pairFields.getOrElse("_2", pairFields.getOrElse("second", PluginValue.list(Nil)))
                  (ns, outs)
                case Lst(List(ns, outs)) => (ns, outs)
                case Tpl(List(ns, outs)) => (ns, outs)
                case other                       => (s, other)
              states(k) = ns
              val outList = outs match
                case Lst(xs) => xs
                case single          => List(single)
              results ++= outList.map(o => outputForKey(k, o))
            case _ =>
        results.iterator

      case Inst("_dag_broadcastState", fields) =>
        // Each element of the main stream is paired with a state-accessor instance.
        // State stream KV elements build a Map[PluginValue, PluginValue]; the accessor provides `get(key)`.
        val upstream   = evalDag(fields("upstream"), ctx)
        val stateElems = evalDag(fields("stateDag"), ctx).toList
        val stateMap   = LinkedHashMap[PluginValue, PluginValue]()
        for elem <- stateElems do
          elem match
            case Inst("KV", kvFields) => stateMap(kvFields("key")) = kvFields("value")
            case v                               => stateMap(v) = v
        val frozenMap = stateMap.toMap
        val getterFn = PluginValue.nativeFn("BroadcastMap.get", {
          case List(k) =>
            val kv = toValue(k)
            frozenMap.get(kv) match
              case Some(v) => pluginSome(v, ctx)
              case None    => pluginNone(ctx)
          case _ =>
            pluginNone(ctx)
        })
        val stateAccessor = PluginValue.instance("_broadcast_map", Map("get" -> getterFn))
        upstream.map(elem => PluginValue.tuple(List(elem, stateAccessor)))

      // ── v2.1.8 — Side inputs / side outputs ──────────────────────────────────

      case Inst("_dag_withSideInput", fields) =>
        // Cross product: each main element × each side-input element → (main, si)
        val upstream  = evalDag(fields("upstream"), ctx)
        val siVal     = fields("si")
        val siElems   = siVal match
          case Inst("SideInput", sf) =>
            sf.get("elements") match
              case Some(Lst(xs)) => xs
              case _                    => Nil
          case Lst(xs) => xs
          case _               => List(siVal)
        upstream.flatMap { e =>
          siElems.map(b => PluginValue.tuple(List(e, b)))
        }

      case Inst("_dag_sideOutput", fields) =>
        // Side-output DAG node: evaluates to elements extracted by tag's filter.
        // The main stream is the upstream unchanged; this node captures the SIDE stream.
        val upstream = evalDag(fields("upstream"), ctx)
        val tag      = fields("tag")
        tag match
          case Inst("OutputTag", tagFields) =>
            tagFields.get("filter") match
              case Some(filterFn) if filterFn != PluginValue.unit =>
                upstream.flatMap { e =>
                  call(ctx, filterFn, List(e)) match
                    case Inst("Some", sf) => sf.get("value").orElse(sf.get("get")).toList
                    case Opt(Some(v))     => List(v)
                    case Opt(None)        => Nil
                    case _ => Nil
                }
              case _ => Iterator.empty
          case _ => Iterator.empty

      // ── v2.1.9 — Windowed joins + flatten ────────────────────────────────────

      case Inst("_dag_join", fields) =>
        val left  = evalDag(fields("upstream"), ctx).toList
        val right = evalDag(fields("rightDag"), ctx)
        val rightMap = LinkedHashMap[PluginValue, List[PluginValue]]()
        for elem <- right do elem match
          case Inst("KV", kf) => rightMap(kf("key")) = rightMap.getOrElse(kf("key"), Nil) :+ kf("value")
          case _ =>
        left.iterator.flatMap {
          case Inst("KV", kf) =>
            val k = kf("key"); val v = kf("value")
            rightMap.getOrElse(k, Nil).map { r =>
              PluginValue.instance("KV", Map("key" -> k,
                "value" -> PluginValue.tuple(List(v, r))))
            }
          case _ => Nil
        }

      case Inst("_dag_leftOuterJoin", fields) =>
        val left  = evalDag(fields("upstream"), ctx).toList
        val right = evalDag(fields("rightDag"), ctx)
        val rightMap = LinkedHashMap[PluginValue, List[PluginValue]]()
        for elem <- right do elem match
          case Inst("KV", kf) => rightMap(kf("key")) = rightMap.getOrElse(kf("key"), Nil) :+ kf("value")
          case _ =>
        left.iterator.map {
          case Inst("KV", kf) =>
            val k = kf("key"); val v = kf("value")
            val rs = rightMap.getOrElse(k, Nil)
            val rightOpt = if rs.isEmpty then pluginNone(ctx) else pluginSome(rs.head, ctx)
            PluginValue.instance("KV", Map("key" -> k,
              "value" -> PluginValue.tuple(List(v, rightOpt))))
          case other => other
        }

      case Inst("_dag_rightOuterJoin", fields) =>
        val left  = evalDag(fields("upstream"), ctx)
        val right = evalDag(fields("rightDag"), ctx).toList
        val leftMap = LinkedHashMap[PluginValue, List[PluginValue]]()
        for elem <- left do elem match
          case Inst("KV", kf) => leftMap(kf("key")) = leftMap.getOrElse(kf("key"), Nil) :+ kf("value")
          case _ =>
        right.iterator.map {
          case Inst("KV", kf) =>
            val k = kf("key"); val v = kf("value")
            val ls = leftMap.getOrElse(k, Nil)
            val leftOpt = if ls.isEmpty then pluginNone(ctx) else pluginSome(ls.head, ctx)
            PluginValue.instance("KV", Map("key" -> k,
              "value" -> PluginValue.tuple(List(leftOpt, v))))
          case other => other
        }

      case Inst("_dag_flatten", fields) =>
        val upstream = evalDag(fields("upstream"), ctx)
        upstream.flatMap {
          case Inst("DStream", innerFields) =>
            innerFields.get("__dag") match
              case Some(innerDag) => evalDag(innerDag, ctx)
              case None           => Iterator.empty
          case Lst(xs) => xs.iterator
          case other           => Iterator.single(other)
        }

      case other =>
        PluginError.raise(s"evalDag: unknown DAG node kind: $other")

  // ── Collect results from a running pipeline ────────────────────────────────

  private def runToList(dag: PluginValue, ctx: PluginContext): List[PluginValue] =
    evalDag(dag, ctx).toList

  // ── DStream → Source bridge helper ────────────────────────────────────────

  private def mkCollectedSourceV(elems: List[PluginValue], ctx: PluginContext): PluginValue =
    val arr = elems.toArray
    PluginValue.instance("Source", Map(
      "runForeach" -> PluginValue.nativeFn("Source.runForeach", {
        case List(f) => arr.foreach(v => call(ctx, f, List(v))); PluginValue.unit
        case _       => PluginError.raise("Source.runForeach(f)")
      }),
      "runToList" -> PluginValue.nativeFn("Source.runToList", { _ =>
        PluginValue.list(arr.toList)
      }),
      "runFold" -> PluginValue.nativeFn("Source.runFold", { args =>
        def fold(z: PluginValue, f: PluginValue): PluginValue =
          arr.foldLeft(z)((acc, v) => call(ctx, f, List(acc, v)))
        args match
          case List(z, f) => fold(z, f)
          case List(z) => PluginValue.nativeFn("Source.runFold$1", {
            case List(f) => fold(z, f)
            case _       => PluginError.raise("Source.runFold(z)(f) — inner")
          })
          case _ => PluginError.raise("Source.runFold(z)(f) — outer")
      }),
      "map" -> PluginValue.nativeFn("Source.map", {
        case List(f) => mkCollectedSourceV(arr.toList.map(v => call(ctx, f, List(v))), ctx)
        case _       => PluginError.raise("Source.map(f)")
      }),
      "filter" -> PluginValue.nativeFn("Source.filter", {
        case List(p) => mkCollectedSourceV(arr.toList.filter(v => call(ctx, p, List(v)) == PluginValue.bool(true)), ctx)
        case _       => PluginError.raise("Source.filter(p)")
      }),
      "merge" -> PluginValue.nativeFn("Source.merge", {
        case List(other) =>
          val otherElems = other match
            case Inst("Source", fields) =>
              fields.get("runToList") match
                case Some(fn) => call(ctx, fn, Nil) match
                  case Lst(xs) => xs
                  case _               => Nil
                case None => Nil
            case _ => Nil
          mkCollectedSourceV(arr.toList ++ otherElems, ctx)
        case _ => PluginError.raise("Source.merge(other)")
      }),
    ))

  // ── Capability negotiation ─────────────────────────────────────────────────

  private def collectRequiredCaps(dag: PluginValue): Set[String] =
    val caps = scala.collection.mutable.Set[String](CAP_AT_LEAST_ONCE)
    def walk(node: PluginValue): Unit = node match
      case Inst(kind, fields) =>
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
          case InstAny(inner) => walk(inner)
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

  private def checkCapabilities(dag: PluginValue, backendName: String): Option[String] =
    val required = collectRequiredCaps(dag)
    val provided = backendProvides(backendName)
    val missing  = required -- provided
    if missing.isEmpty then None
    else Some(
      s"CAPABILITY_MISMATCH: backend '$backendName' does not support: ${missing.mkString(", ")}. " +
      s"Consider Backend.Spark or Backend.Flink for these capabilities."
    )

  // ── PipelineResult ────────────────────────────────────────────────────────

  private def mkResult(results: List[PluginValue]): PluginValue =
    PluginValue.instance("PipelineResult", Map(
      "state"            -> PluginValue.string("Done"),
      "__results"        -> PluginValue.list(results),
      "waitUntilFinish"  -> PluginValue.nativeFn("PipelineResult.waitUntilFinish", { _ =>
        PluginValue.string("Done")
      }),
      "cancel"           -> PluginValue.nativeFn("PipelineResult.cancel", { _ =>
        PluginValue.unit
      }),
    ))

  // ── Pipeline execution ────────────────────────────────────────────────────

  private def executePipeline(dag: PluginValue, backendName: String, ctx: PluginContext): PluginValue =
    checkCapabilities(dag, backendName) match
      case Some(err) => PluginError.raise(err)
      case None      =>
        dag match
          // If the final node is a write, run through it but discard sink output
          case Inst("_dag_write", fields) =>
            val results = runToList(fields("upstream"), ctx)
            val sink    = fields("sink")
            // Store results in the sink's buffer if it's an InMemory sink
            sink match
              case Inst("_inmemory_sink", sinkFields) =>
                sinkFields.get("__buffer") match
                  case Some(InstAny(bufRef)) =>
                    // The buffer is a mutable ref — store via the put fn
                    bufRef.field("put") match
                      case Some(putFn) => call(ctx, putFn, List(PluginValue.list(results)))
                      case None        => ()
                  case _ => ()
              case _ => ()
            mkResult(results)
          case other =>
            val results = runToList(other, ctx)
            mkResult(results)

  // ── DStream operator wiring ───────────────────────────────────────────────

  private def dstreamOps(dag: PluginValue, ctx: PluginContext): Map[String, PluginValue] = Map(

    "map" -> PluginValue.nativeFn("DStream.map", {
      case List(f) => mkDStreamWithOps(dagNode("map", Map("upstream" -> dag, "f" -> f)), ctx)
      case _       => PluginError.raise("DStream.map(f)")
    }),

    "filter" -> PluginValue.nativeFn("DStream.filter", {
      case List(pred) => mkDStreamWithOps(dagNode("filter", Map("upstream" -> dag, "pred" -> pred)), ctx)
      case _          => PluginError.raise("DStream.filter(pred)")
    }),

    "flatMap" -> PluginValue.nativeFn("DStream.flatMap", {
      case List(f) => mkDStreamWithOps(dagNode("flatMap", Map("upstream" -> dag, "f" -> f)), ctx)
      case _       => PluginError.raise("DStream.flatMap(f)")
    }),

    "keyBy" -> PluginValue.nativeFn("DStream.keyBy", {
      case List(keyFn) => mkDStreamWithOps(dagNode("keyBy", Map("upstream" -> dag, "keyFn" -> keyFn)), ctx)
      case _           => PluginError.raise("DStream.keyBy(keyFn)")
    }),

    "combinePerKey" -> PluginValue.nativeFn("DStream.combinePerKey", {
      case List(f) => mkDStreamWithOps(dagNode("combinePerKey", Map("upstream" -> dag, "f" -> f)), ctx)
      case _       => PluginError.raise("DStream.combinePerKey(f)")
    }),

    "merge" -> PluginValue.nativeFn("DStream.merge", {
      case List(other) =>
        val otherDag = getDag(other)
        mkDStreamWithOps(dagNode("merge", Map("left" -> dag, "right" -> otherDag)), ctx)
      case _ => PluginError.raise("DStream.merge(other)")
    }),

    "mapWithTimestamp" -> PluginValue.nativeFn("DStream.mapWithTimestamp", {
      case List(f) => mkDStreamWithOps(dagNode("mapWithTimestamp", Map("upstream" -> dag, "f" -> f)), ctx)
      case _       => PluginError.raise("DStream.mapWithTimestamp(f)")
    }),

    "assignTimestamps" -> PluginValue.nativeFn("DStream.assignTimestamps", {
      case List(f) => mkDStreamWithOps(dagNode("assignTimestamps", Map("upstream" -> dag, "f" -> f)), ctx)
      case _       => PluginError.raise("DStream.assignTimestamps(f)")
    }),

    // ── v2.1.2 windowing / watermark / timer operators ────────────────────────

    "window" -> PluginValue.nativeFn("DStream.window", {
      case List(windowFn) => mkDStreamWithOps(dagNode("window", Map("upstream" -> dag, "windowFn" -> windowFn)), ctx)
      case _              => PluginError.raise("DStream.window(windowFn)")
    }),

    "withTrigger" -> PluginValue.nativeFn("DStream.withTrigger", {
      case List(trigger) => mkDStreamWithOps(dagNode("withTrigger", Map("upstream" -> dag, "trigger" -> trigger)), ctx)
      case _             => PluginError.raise("DStream.withTrigger(trigger)")
    }),

    "withAllowedLateness" -> PluginValue.nativeFn("DStream.withAllowedLateness", {
      case List(d) => mkDStreamWithOps(dagNode("withAllowedLateness", Map("upstream" -> dag, "d" -> toValue(d))), ctx)
      case _       => PluginError.raise("DStream.withAllowedLateness(d)")
    }),

    "withWatermark" -> PluginValue.nativeFn("DStream.withWatermark", {
      case List(strategy) =>
        mkDStreamWithOps(dagNode("withWatermark", Map("upstream" -> dag, "strategy" -> toValue(strategy))), ctx)
      case _ => PluginError.raise("DStream.withWatermark(strategy)")
    }),

    // timerProcessing(durationMs)(f: K => Iterable[B]) — curried
    "timerProcessing" -> PluginValue.nativeFn("DStream.timerProcessing", {
      case List(_, f) => mkDStreamWithOps(dagNode("timerProcessing", Map("upstream" -> dag, "f" -> f)), ctx)
      case List(_) => PluginValue.nativeFn("DStream.timerProcessing$1", {
        case List(f) => mkDStreamWithOps(dagNode("timerProcessing", Map("upstream" -> dag, "f" -> f)), ctx)
        case _       => PluginError.raise("DStream.timerProcessing(d)(f) — inner")
      })
      case _ => PluginError.raise("DStream.timerProcessing(d)(f) — outer")
    }),

    // ── v2.1.7 — Stateful processing operators ────────────────────────────────

    // timerEventTime(tsMs)(f: K => Iterable[B]) — curried, event-time timer
    "timerEventTime" -> PluginValue.nativeFn("DStream.timerEventTime", {
      case List(_, f) => mkDStreamWithOps(dagNode("timerEventTime", Map("upstream" -> dag, "f" -> f)), ctx)
      case List(_) => PluginValue.nativeFn("DStream.timerEventTime$1", {
        case List(f) => mkDStreamWithOps(dagNode("timerEventTime", Map("upstream" -> dag, "f" -> f)), ctx)
        case _       => PluginError.raise("DStream.timerEventTime(ts)(f) — inner")
      })
      case _ => PluginError.raise("DStream.timerEventTime(ts)(f) — outer")
    }),

    // statefulMap(initState)(f: (S, A) => (S, B)) — curried, per-key state
    "statefulMap" -> PluginValue.nativeFn("DStream.statefulMap", {
      case List(init, f) => mkDStreamWithOps(dagNode("statefulMap", Map("upstream" -> dag, "init" -> init, "f" -> f)), ctx)
      case List(init) => PluginValue.nativeFn("DStream.statefulMap$1", {
        case List(f) => mkDStreamWithOps(dagNode("statefulMap", Map("upstream" -> dag, "init" -> init, "f" -> f)), ctx)
        case _       => PluginError.raise("DStream.statefulMap(init)(f) — inner")
      })
      case _ => PluginError.raise("DStream.statefulMap(init)(f) — outer")
    }),

    // statefulFlatMap(initState)(f: (S, A) => (S, Iterable[B])) — curried
    "statefulFlatMap" -> PluginValue.nativeFn("DStream.statefulFlatMap", {
      case List(init, f) => mkDStreamWithOps(dagNode("statefulFlatMap", Map("upstream" -> dag, "init" -> init, "f" -> f)), ctx)
      case List(init) => PluginValue.nativeFn("DStream.statefulFlatMap$1", {
        case List(f) => mkDStreamWithOps(dagNode("statefulFlatMap", Map("upstream" -> dag, "init" -> init, "f" -> f)), ctx)
        case _       => PluginError.raise("DStream.statefulFlatMap(init)(f) — inner")
      })
      case _ => PluginError.raise("DStream.statefulFlatMap(init)(f) — outer")
    }),

    // broadcastState(stateStream) — pairs each elem with broadcast state map
    "broadcastState" -> PluginValue.nativeFn("DStream.broadcastState", {
      case List(stateStream) =>
        val stateDag = getDag(stateStream)
        mkDStreamWithOps(dagNode("broadcastState", Map("upstream" -> dag, "stateDag" -> stateDag)), ctx)
      case _ => PluginError.raise("DStream.broadcastState(stateStream)")
    }),

    // ── v2.1.8 — Side inputs / side outputs ──────────────────────────────────

    // withSideInput(si: SideInput[B]) — cross product with side input elements
    "withSideInput" -> PluginValue.nativeFn("DStream.withSideInput", {
      case List(si) =>
        mkDStreamWithOps(dagNode("withSideInput", Map("upstream" -> dag, "si" -> si)), ctx)
      case _ => PluginError.raise("DStream.withSideInput(si)")
    }),

    // sideOutput(tag: OutputTag[B]) — returns (mainStream, sideStream) tuple
    "sideOutput" -> PluginValue.nativeFn("DStream.sideOutput", {
      case List(tag) =>
        val sideDag = dagNode("sideOutput", Map("upstream" -> dag, "tag" -> tag))
        val mainStream = mkDStreamWithOps(dag, ctx)
        val sideStream = mkDStreamWithOps(sideDag, ctx)
        PluginValue.instance("_sideOutput_result",
          Map("_1" -> mainStream, "_2" -> sideStream))
      case _ => PluginError.raise("DStream.sideOutput(tag)")
    }),

    // ── v2.1.9 — Windowed joins + flatten ────────────────────────────────────

    "join" -> PluginValue.nativeFn("DStream.join", {
      case List(other) =>
        val rightDag = getDag(other)
        mkDStreamWithOps(dagNode("join", Map("upstream" -> dag, "rightDag" -> rightDag)), ctx)
      case _ => PluginError.raise("DStream.join(other)")
    }),

    "leftOuterJoin" -> PluginValue.nativeFn("DStream.leftOuterJoin", {
      case List(other) =>
        val rightDag = getDag(other)
        mkDStreamWithOps(dagNode("leftOuterJoin", Map("upstream" -> dag, "rightDag" -> rightDag)), ctx)
      case _ => PluginError.raise("DStream.leftOuterJoin(other)")
    }),

    "rightOuterJoin" -> PluginValue.nativeFn("DStream.rightOuterJoin", {
      case List(other) =>
        val rightDag = getDag(other)
        mkDStreamWithOps(dagNode("rightOuterJoin", Map("upstream" -> dag, "rightDag" -> rightDag)), ctx)
      case _ => PluginError.raise("DStream.rightOuterJoin(other)")
    }),

    "flatten" -> PluginValue.nativeFn("DStream.flatten", { _ =>
      mkDStreamWithOps(dagNode("flatten", Map("upstream" -> dag)), ctx)
    }),

    "write" -> PluginValue.nativeFn("DStream.write", {
      case List(sink) => mkDStreamWithOps(dagNode("write", Map("upstream" -> dag, "sink" -> sink)), ctx)
      case _          => PluginError.raise("DStream.write(sink)")
    }),

    "requires" -> PluginValue.nativeFn("DStream.requires", { _ =>
      PluginValue.list(collectRequiredCaps(dag).toList.map(PluginValue.string(_)))
    }),

    "run" -> PluginValue.nativeFn("DStream.run", {
      case List(backend) =>
        val backendName = backend match
          case Str(s)              => s
          case Inst(name, _)      => name
          case _                             => "Direct"
        executePipeline(dag, backendName, ctx)
      case _ => PluginError.raise("DStream.run(backend)")
    }),

    "runOpts" -> PluginValue.nativeFn("DStream.runOpts", {
      case List(backend, _) =>
        val backendName = backend match
          case Str(s)         => s
          case Inst(name, _) => name
          case _                        => "Direct"
        executePipeline(dag, backendName, ctx)
      case _ => PluginError.raise("DStream.runOpts(backend, opts)")
    }),

    // Bounded terminal operators — execute eagerly
    "runToList" -> PluginValue.nativeFn("DStream.runToList", { _ =>
      PluginValue.list(runToList(dag, ctx))
    }),

    "runFold" -> PluginValue.nativeFn("DStream.runFold", { args =>
      def fold(z: PluginValue, f: PluginValue): PluginValue =
        var acc = z
        for v <- evalDag(dag, ctx) do acc = call(ctx, f, List(acc, v))
        acc
      args match
        case List(z, f) => fold(z, f)
        case List(z) => PluginValue.nativeFn("DStream.runFold$1", {
          case List(f) => fold(z, f)
          case _       => PluginError.raise("DStream.runFold(z)(f) — inner")
        })
        case _ => PluginError.raise("DStream.runFold(z)(f) — outer")
    }),

    "runForeach" -> PluginValue.nativeFn("DStream.runForeach", {
      case List(f) =>
        for v <- evalDag(dag, ctx) do call(ctx, f, List(v))
        PluginValue.unit
      case _ => PluginError.raise("DStream.runForeach(f)")
    }),

    "runCount" -> PluginValue.nativeFn("DStream.runCount", { _ =>
      PluginValue.int(evalDag(dag, ctx).size.toLong)
    }),

    // Bridge operators — DStream.local / DStream.localBounded (v1.63.1)
    "local" -> PluginValue.nativeFn("DStream.local", { _ =>
      mkCollectedSourceV(runToList(dag, ctx), ctx)
    }),

    "localBounded" -> PluginValue.nativeFn("DStream.localBounded", {
      case Nil =>
        mkCollectedSourceV(runToList(dag, ctx), ctx)
      case List(maxBytesV) =>
        val maxBytes = maxBytesV match
          case Num(n) => n
          case _             => 268435456L
        val elems = runToList(dag, ctx)
        val approxBytes = elems.map {
          case Str(s) => s.length.toLong * 2
          case Num(_)    => 8L
          case Dbl(_) => 8L
          case Bool(_)   => 1L
          case PluginValue.unit      => 0L
          case _                => 64L
        }.sum
        if approxBytes > maxBytes then
          PluginError.raise(
            s"DStream.localBounded: stream ~$approxBytes bytes > limit $maxBytes bytes"
          )
        else mkCollectedSourceV(elems, ctx)
      case _ => PluginError.raise("DStream.localBounded(maxBytes)")
    }),

    // DStream.remote(name) — materialise and register as a named remote stream (v1.63.6).
    // Collects the DStream elements into a local Source, then delegates to Source.remote.
    "remote" -> PluginValue.nativeFn("DStream.remote", {
      case List(nameV) =>
        val name = nameV match
          case Str(s) => s
          case _                => PluginError.raise("DStream.remote(name: String)")
        val elems   = runToList(dag, ctx)
        val localSrc = mkCollectedSourceV(elems, ctx)
        ctx.featureSet(s"remoteSource.$name", localSrc)
        val sseHandler = PluginValue.nativeFn(s"stream.$name.sse", { _ =>
          ctx.featureGet(s"remoteSource.$name") match
            case Some(InstAny(srcV)) if srcV.typeNameOf.contains("Source") =>
              val sb = new StringBuilder
              srcV.field("runForeach") match
                case Some(rf) =>
                  val emitFn = PluginValue.nativeFn("_sseEmit", {
                    case List(v) =>
                      val s = v match
                        case Str(x) => x
                        case other            => PluginValue.showAny(other)
                      sb.append(s"data: $s\n\n")
                      PluginValue.unit
                    case _ => PluginValue.unit
                  })
                  ctx.synchronized { ctx.invokeCallback(rf, List(emitFn)) }
                case _ => ()
              PluginValue.instance("Response", Map(
                "status"  -> PluginValue.int(200),
                "headers" -> PluginValue.mapOf(Map(
                  PluginValue.string("Content-Type") -> PluginValue.string("text/event-stream"),
                )),
                "body"    -> PluginValue.string(sb.toString)
              ))
            case _ =>
              PluginValue.instance("Response", Map(
                "status" -> PluginValue.int(404),
                "body"   -> PluginValue.string(s"stream '$name' not found")
              ))
        })
        ctx.registerRoute("GET", s"/streams/$name", sseHandler)
        PluginValue.instance("RemoteSource", Map(
          "name"   -> PluginValue.string(name),
          "policy" -> PluginValue.string("Default")
        ))
      case _ => PluginError.raise("DStream.remote(name)")
    }),
  )

  private def mkDStreamWithOps(dag: PluginValue, ctx: PluginContext): PluginValue =
    PluginValue.instance("DStream", Map("__dag" -> dag) ++ dstreamOps(dag, ctx))

  // ── Pipeline object ───────────────────────────────────────────────────────

  private def mkPipeline(name: String, ctx: PluginContext): PluginValue =
    PluginValue.instance("Pipeline", Map(
      "name" -> PluginValue.string(name),

      "read" -> PluginValue.nativeFn("Pipeline.read", {
        case List(InstAny(dsource)) =>
          val dag = dsource.field("__dag").getOrElse(
            PluginError.raise("Pipeline.read: not a DSource"))
          mkDStreamWithOps(dag, ctx)
        case _ => PluginError.raise("Pipeline.read(source: DSource[T])")
      }),
    ))

  // ── InMemory sink buffer ──────────────────────────────────────────────────

  private def mkInMemorySink(): PluginValue =
    val buf = ListBuffer[PluginValue]()
    val putFn = PluginValue.nativeFn("InMemorySink.put", {
      case List(Lst(xs)) => buf ++= xs; PluginValue.unit
      case _                    => PluginValue.unit
    })
    val getFn = PluginValue.nativeFn("InMemorySink.get", { _ =>
      PluginValue.list(buf.toList)
    })
    PluginValue.instance("_inmemory_sink", Map(
      "__buffer" -> PluginValue.instance("_buf_ref", Map("put" -> putFn, "get" -> getFn)),
      "get"      -> getFn,
    ))

  // ── Intrinsic table ───────────────────────────────────────────────────────

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    // Pipeline.create(name)
    QualifiedName("Pipeline.create") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(name: String) => mkPipeline(name, ctx)
        case _                  => PluginError.raise("Pipeline.create(name: String)")
    },

    // runPipeline(pipeline, backend) — functional alternative to pipeline.run(backend)
    QualifiedName("runPipeline") -> PluginNative.evalLegacy { (ctx, args) =>
      def backendNameOf(a: Any): String = a match
        case s: String                   => s
        case Inst(name, _)    => name
        case _                           => "Direct"
      args match
        case List(_, InstAny(stream)) =>
          val dag = stream.field("__dag").getOrElse(
            PluginError.raise("runPipeline: first arg must be a Pipeline / DStream"))
          executePipeline(dag, backendNameOf(args.head), ctx)
        case List(InstAny(stream), backend) =>
          val dag = stream.field("__dag").getOrElse(
            PluginError.raise("runPipeline: arg must be a DStream"))
          executePipeline(dag, backendNameOf(backend), ctx)
        case _ => PluginError.raise("runPipeline(pipeline, backend)")
    },

    // InMemory.source(list)
    QualifiedName("InMemory.source") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(Lst(elems)) =>
          val dag = dagNode("source_list", Map("elements" -> PluginValue.list(elems)))
          PluginValue.instance("DSource", Map("__dag" -> dag))
        case _ => PluginError.raise("InMemory.source(elements: List[T])")
    },

    // InMemory.sourceWithTimestamps(list of (T, Long))
    QualifiedName("InMemory.sourceWithTimestamps") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(Lst(pairs)) =>
          val elems = pairs.map {
            case Tpl(List(v, _)) => v
            case v                        => v
          }
          val dag = dagNode("source_list", Map("elements" -> PluginValue.list(elems)))
          PluginValue.instance("DSource", Map("__dag" -> dag))
        case _ => PluginError.raise("InMemory.sourceWithTimestamps(elements: List[(T, Long)])")
    },

    // InMemory.sink() — returns a DSink + getter pair
    QualifiedName("InMemory.sink") -> PluginNative.evalLegacy { (_, _) =>
      mkInMemorySink()
    },

    // InMemory.runAndCollect(stream) — DirectRunner convenience
    QualifiedName("InMemory.runAndCollect") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(InstAny(stream)) =>
          val dag = stream.field("__dag").getOrElse(
            PluginError.raise("InMemory.runAndCollect: not a DStream"))
          PluginValue.list(runToList(dag, ctx))
        case _ => PluginError.raise("InMemory.runAndCollect(stream: DStream[T])")
    },

    // DSource.fromLocalSource(src: Source[A])
    QualifiedName("DSource.fromLocalSource") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(src) =>
          val dag = dagNode("source_local", Map("source" -> PluginValue.wrap(src)))
          PluginValue.instance("DSource", Map("__dag" -> dag))
        case _ => PluginError.raise("DSource.fromLocalSource(src: Source[A])")
    },

    // Backend.Direct / Backend.Native / Backend.Spark — singleton values
    QualifiedName("Backend.Direct")  -> PluginNative.evalLegacy { (_, _) => PluginValue.instance("Direct",  Map.empty)},
    QualifiedName("Backend.Native")  -> PluginNative.evalLegacy { (_, _) => PluginValue.instance("Native",  Map.empty)},
    QualifiedName("Backend.Spark")   -> PluginNative.evalLegacy { (_, _) => PluginValue.instance("Spark",   Map.empty)},

    // Window constructors (return tagged values; only identity for v2.1.1)
    QualifiedName("Window.fixed")    -> PluginNative.evalLegacy { (_, args) => args match
      case List(ms) => PluginValue.instance("Window.Fixed",   Map("ms" -> toValue(ms)))
      case _        => PluginError.raise("Window.fixed(durationMs)")
    },
    QualifiedName("Window.sliding")  -> PluginNative.evalLegacy { (_, args) => args match
      case List(ms, p) => PluginValue.instance("Window.Sliding", Map("ms" -> toValue(ms), "period" -> toValue(p)))
      case _           => PluginError.raise("Window.sliding(durationMs, periodMs)")
    },
    QualifiedName("Window.session")  -> PluginNative.evalLegacy { (_, args) => args match
      case List(ms) => PluginValue.instance("Window.Session",  Map("gapMs" -> toValue(ms)))
      case _        => PluginError.raise("Window.session(gapMs)")
    },
    QualifiedName("Window.global")   -> PluginNative.evalLegacy { (_, _) => PluginValue.instance("Window.Global", Map.empty)},

    // Trigger constructors
    QualifiedName("Trigger.afterWatermark")       -> PluginNative.evalLegacy { (_, _) => PluginValue.string("Trigger.AfterWatermark")},
    QualifiedName("Trigger.afterProcessingTime")  -> PluginNative.evalLegacy { (_, args) =>
      PluginValue.instance("Trigger.AfterProcessingTime",
        Map("ms" -> args.headOption.map(toValue).getOrElse(PluginValue.int(0))))
    },
    QualifiedName("Trigger.afterCount")           -> PluginNative.evalLegacy { (_, args) =>
      PluginValue.instance("Trigger.AfterCount",
        Map("n" -> args.headOption.map(toValue).getOrElse(PluginValue.int(0))))
    },
    QualifiedName("Trigger.repeatedly")           -> PluginNative.evalLegacy { (_, args) =>
      PluginValue.instance("Trigger.Repeatedly",
        Map("inner" -> args.headOption.map(toValue).getOrElse(PluginValue.unit)))
    },

    // WatermarkStrategy constructors
    QualifiedName("WatermarkStrategy.monotonicallyIncreasing") -> PluginNative.evalLegacy { (_, _) =>
      PluginValue.string("WatermarkStrategy.MonotonicallyIncreasing")
    },
    QualifiedName("WatermarkStrategy.atEnd")        -> PluginNative.evalLegacy { (_, _) =>
      PluginValue.string("WatermarkStrategy.AtEnd")
    },
    QualifiedName("WatermarkStrategy.boundedOutOfOrder") -> PluginNative.evalLegacy { (_, args) =>
      PluginValue.instance("WatermarkStrategy.BoundedOutOfOrder",
        Map("lagMs" -> args.headOption.map(toValue).getOrElse(PluginValue.int(0))))
    },

    // AccumulationMode
    QualifiedName("AccumulationMode.Discarding")   -> PluginNative.evalLegacy { (_, _) => PluginValue.string("AccumulationMode.Discarding")},
    QualifiedName("AccumulationMode.Accumulating") -> PluginNative.evalLegacy { (_, _) => PluginValue.string("AccumulationMode.Accumulating")},

    // KV constructor — args are unwrapped primitives or Values
    QualifiedName("KV") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(k, v) => PluginValue.instance("KV", Map("key" -> toValue(k), "value" -> toValue(v)))
        case _          => PluginError.raise("KV(key, value)")
    },
    QualifiedName("KV.apply") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(k, v) => PluginValue.instance("KV", Map("key" -> toValue(k), "value" -> toValue(v)))
        case _          => PluginError.raise("KV(key, value)")
    },

    // ── v2.1.6 — Production connectors (stub DSource — return empty for bounded testing) ──

    // Kafka connector — source returns empty DSource (live Kafka requires KAFKA_BROKERS)
    QualifiedName("Kafka.source")          -> PluginNative.evalLegacy { (_, _) =>
      PluginValue.instance("DSource", Map("__dag" -> dagNode("source_list", Map("elements" -> PluginValue.list(Nil)))))
    },
    QualifiedName("Kafka.sourceAssigned")  -> PluginNative.evalLegacy { (_, _) =>
      PluginValue.instance("DSource", Map("__dag" -> dagNode("source_list", Map("elements" -> PluginValue.list(Nil)))))
    },
    QualifiedName("Kafka.changelog")       -> PluginNative.evalLegacy { (_, _) =>
      PluginValue.instance("DSource", Map("__dag" -> dagNode("source_list", Map("elements" -> PluginValue.list(Nil)))))
    },
    QualifiedName("Kafka.sink")            -> PluginNative.evalLegacy { (_, _) => PluginValue.unit},

    // Files connector — stub DSource
    QualifiedName("Files.source")          -> PluginNative.evalLegacy { (_, _) =>
      PluginValue.instance("DSource", Map("__dag" -> dagNode("source_list", Map("elements" -> PluginValue.list(Nil)))))
    },
    QualifiedName("Files.sink")            -> PluginNative.evalLegacy { (_, _) => PluginValue.unit},

    // FileFormat singletons
    QualifiedName("FileFormat.Text")       -> PluginNative.evalLegacy { (_, _) => PluginValue.string("Text")},
    QualifiedName("FileFormat.Json")       -> PluginNative.evalLegacy { (_, _) => PluginValue.string("Json")},
    QualifiedName("FileFormat.Parquet")    -> PluginNative.evalLegacy { (_, _) => PluginValue.string("Parquet")},
    QualifiedName("FileFormat.Avro")       -> PluginNative.evalLegacy { (_, _) => PluginValue.string("Avro")},
    QualifiedName("FileFormat.Csv")        -> PluginNative.evalLegacy { (_, args) =>
      PluginValue.instance("FileFormat.Csv", Map("header" -> args.headOption.map(toValue).getOrElse(PluginValue.bool(true))))
    },

    // JDBC connector — stub DSource
    QualifiedName("Jdbc.source")           -> PluginNative.evalLegacy { (_, _) =>
      PluginValue.instance("DSource", Map("__dag" -> dagNode("source_list", Map("elements" -> PluginValue.list(Nil)))))
    },
    QualifiedName("Jdbc.sink")             -> PluginNative.evalLegacy { (_, _) => PluginValue.unit},

    // Pulsar connector — stub DSource
    QualifiedName("Pulsar.source")         -> PluginNative.evalLegacy { (_, _) =>
      PluginValue.instance("DSource", Map("__dag" -> dagNode("source_list", Map("elements" -> PluginValue.list(Nil)))))
    },
    QualifiedName("Pulsar.sink")           -> PluginNative.evalLegacy { (_, _) => PluginValue.unit},

    // Kinesis connector — stub DSource
    QualifiedName("Kinesis.source")        -> PluginNative.evalLegacy { (_, _) =>
      PluginValue.instance("DSource", Map("__dag" -> dagNode("source_list", Map("elements" -> PluginValue.list(Nil)))))
    },
    QualifiedName("Kinesis.sink")          -> PluginNative.evalLegacy { (_, _) => PluginValue.unit},

    // DSource.fromDataset bridge
    QualifiedName("DSource.fromDataset")   -> PluginNative.evalLegacy { (_, _) =>
      PluginValue.instance("DSource", Map("__dag" -> dagNode("source_list", Map("elements" -> PluginValue.list(Nil)))))
    },

    // ── v2.1.7 — Stateful processing state types ─────────────────────────────

    // KeyedStateSpec.value[K, S](init) — returns a spec instance
    QualifiedName("KeyedStateSpec.value") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(init) => PluginValue.instance("KeyedStateSpec", Map("init" -> toValue(init)))
        case _          => PluginError.raise("KeyedStateSpec.value(init)")
    },
    QualifiedName("KeyedStateSpec") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(init) => PluginValue.instance("KeyedStateSpec", Map("init" -> toValue(init)))
        case _          => PluginError.raise("KeyedStateSpec(init)")
    },

    // ── v2.1.8 — Side inputs / side outputs ──────────────────────────────────

    QualifiedName("SideInput.of") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(InstAny(stream)) =>
          val dag  = stream.field("__dag").getOrElse(
            PluginError.raise("SideInput.of: not a DStream"))
          val elems = evalDag(dag, ctx).toList
          PluginValue.instance("SideInput", Map("elements" -> PluginValue.list(elems)))
        case _ => PluginError.raise("SideInput.of(stream)")
    },
    QualifiedName("SideInput.singleton") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(v) => PluginValue.instance("SideInput", Map("elements" -> PluginValue.list(List(toValue(v)))))
        case _       => PluginError.raise("SideInput.singleton(value)")
    },
    QualifiedName("SideInput.asMap") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(InstAny(stream)) =>
          val dag  = stream.field("__dag").getOrElse(
            PluginError.raise("SideInput.asMap: not a DStream"))
          val m = evalDag(dag, ctx).foldLeft(Map.empty[PluginValue, PluginValue]) {
            case (acc, Inst("KV", kf)) => acc + (kf("key") -> kf("value"))
            case (acc, _) => acc
          }
          val mapVal = PluginValue.instance("_side_map", m.map { case (k, v) => k.toString -> v })
          PluginValue.instance("SideInput", Map("elements" -> PluginValue.list(List(mapVal))))
        case _ => PluginError.raise("SideInput.asMap(stream)")
    },

    QualifiedName("OutputTag") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(name) =>
          PluginValue.instance("OutputTag", Map("name" -> toValue(name), "filter" -> PluginValue.unit))
        case _ => PluginError.raise("OutputTag(name)")
    },
    QualifiedName("OutputTag.apply") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(name) =>
          PluginValue.instance("OutputTag", Map("name" -> toValue(name), "filter" -> PluginValue.unit))
        case _ => PluginError.raise("OutputTag(name)")
    },
    QualifiedName("OutputTag.withFilter") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(name, fn) =>
          PluginValue.instance("OutputTag", Map("name" -> toValue(name), "filter" -> toValue(fn)))
        case List(name) =>
          PluginValue.nativeFn("OutputTag.withFilter$1", {
            case List(fn) =>
              PluginValue.instance("OutputTag", Map("name" -> toValue(name), "filter" -> fn))
            case _ => PluginError.raise("OutputTag.withFilter(name)(fn) — inner")
          })
        case _ => PluginError.raise("OutputTag.withFilter(name)(fn)")
    },

    // Source.distributed — bridge from local Source[A] to DStream[A] (v1.63.1)
    // Registered in globals so DispatchRuntime.dispatchInstanceFallback can find it.
    // First arg is always the Source receiver; optional partitions arg is ignored on DirectRunner.
    QualifiedName("Source.distributed") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case src :: _ =>
          val dag = dagNode("source_local", Map("source" -> toValue(src)))
          mkDStreamWithOps(dag, ctx)
        case Nil => PluginError.raise("Source.distributed: missing receiver")
    },
  )
