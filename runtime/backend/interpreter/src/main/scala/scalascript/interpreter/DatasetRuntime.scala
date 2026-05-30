package scalascript.interpreter

import scala.collection.immutable.{Map => IMap}
import Computation.Pure

private[interpreter] object DatasetRuntime:

  def install(interp: Interpreter): Unit =
    interp.globals("Dataset") = Value.InstanceV("Dataset$", Map(
      "of" -> Value.NativeFnV("Dataset.of", {
        case items => Pure(makeDatasetV(() => items, interp))
      }),
      "fromList" -> Value.NativeFnV("Dataset.fromList", {
        case List(Value.ListV(items)) => Pure(makeDatasetV(() => items, interp))
        case _ => throw InterpretError("Dataset.fromList(list: List[T]): Dataset[T]")
      }),
      "fromGenerator" -> Value.NativeFnV("Dataset.fromGenerator", {
        case List(Value.InstanceV("Generator", fields)) =>
          val nextFn = fields("next")
          val buf    = scala.collection.mutable.ListBuffer[Value]()
          var optV   = Computation.run(interp.callValue(nextFn, Nil, Map.empty))
          while optV != Value.NoneV do
            optV match
              case Value.OptionV(v) => buf += v
              case _ =>
            optV = Computation.run(interp.callValue(nextFn, Nil, Map.empty))
          val items = buf.toList
          Pure(makeDatasetV(() => items, interp))
        case _ => throw InterpretError("Dataset.fromGenerator(gen: Generator[T]): Dataset[T]")
      }),
      "fromFile" -> Value.NativeFnV("Dataset.fromFile", {
        case List(Value.StringV(path)) =>
          val src   = scala.io.Source.fromFile(path)
          val lines = try src.getLines().toList.map(Value.StringV.apply) finally src.close()
          Pure(makeDatasetV(() => lines, interp))
        case _ => throw InterpretError("Dataset.fromFile(path: String): Dataset[String]")
      }),
    ))

  private def compareValues(a: Value, b: Value): Int = (a, b) match
    case (Value.IntV(x),    Value.IntV(y))    => x.compareTo(y)
    case (Value.DoubleV(x), Value.DoubleV(y)) => x.compareTo(y)
    case (Value.IntV(x),    Value.DoubleV(y)) => x.toDouble.compareTo(y)
    case (Value.DoubleV(x), Value.IntV(y))    => x.compareTo(y.toDouble)
    case (Value.StringV(x), Value.StringV(y)) => x.compareTo(y)
    case (Value.BoolV(x),   Value.BoolV(y))   => x.compareTo(y)
    case _                                    => Value.show(a).compareTo(Value.show(b))

  private def makeDatasetV(run: () => List[Value], interp: Interpreter): Value =
    Value.InstanceV("Dataset", Map(
      "map" -> Value.NativeFnV("Dataset.map", {
        case List(f) => Pure(makeDatasetV(() =>
          run().map(item => Computation.run(interp.callValue1(f, item, Map.empty)))
        , interp))
        case _ => throw InterpretError("Dataset.map(f: T => U): Dataset[U]")
      }),
      "filter" -> Value.NativeFnV("Dataset.filter", {
        case List(pred) => Pure(makeDatasetV(() =>
          run().filter(item => Computation.run(interp.callValue1(pred, item, Map.empty)) == Value.True)
        , interp))
        case _ => throw InterpretError("Dataset.filter(p: T => Boolean): Dataset[T]")
      }),
      "flatMap" -> Value.NativeFnV("Dataset.flatMap", {
        case List(f) => Pure(makeDatasetV(() =>
          run().flatMap { item =>
            Computation.run(interp.callValue1(f, item, Map.empty)) match
              case Value.ListV(items) => items
              case _ => throw InterpretError("Dataset.flatMap: function must return List[U]")
          }
        , interp))
        case _ => throw InterpretError("Dataset.flatMap(f: T => List[U]): Dataset[U]")
      }),
      "take" -> Value.NativeFnV("Dataset.take", {
        case List(Value.IntV(n)) => Pure(makeDatasetV(() => run().take(n.toInt), interp))
        case _ => throw InterpretError("Dataset.take(n: Int): Dataset[T]")
      }),
      "drop" -> Value.NativeFnV("Dataset.drop", {
        case List(Value.IntV(n)) => Pure(makeDatasetV(() => run().drop(n.toInt), interp))
        case _ => throw InterpretError("Dataset.drop(n: Int): Dataset[T]")
      }),
      "distinct" -> Value.NativeFnV("Dataset.distinct", Computation.pureFn(_ =>
        makeDatasetV(() => run().distinct, interp)
      )),
      // Set-like binary ops — `other` must be a Dataset InstanceV;
      // we pull its lazy thunk and compose at terminal time.
      "union" -> Value.NativeFnV("Dataset.union", {
        case List(Value.InstanceV("Dataset", otherFields)) =>
          val otherRun = otherFields("collect") match
            case Value.NativeFnV(_, f) => () => Computation.run(f(Nil)) match
              case Value.ListV(xs) => xs
              case other           => List(other)
            case _ => () => Nil
          Pure(makeDatasetV(() => run() ++ otherRun(), interp))
        case _ => throw InterpretError("Dataset.union(other: Dataset[T]): Dataset[T]")
      }),
      "intersect" -> Value.NativeFnV("Dataset.intersect", {
        case List(Value.InstanceV("Dataset", otherFields)) =>
          val otherRun = otherFields("collect") match
            case Value.NativeFnV(_, f) => () => Computation.run(f(Nil)) match
              case Value.ListV(xs) => xs
              case other           => List(other)
            case _ => () => Nil
          Pure(makeDatasetV(() => run().intersect(otherRun()), interp))
        case _ => throw InterpretError("Dataset.intersect(other: Dataset[T]): Dataset[T]")
      }),
      "zip" -> Value.NativeFnV("Dataset.zip", {
        case List(Value.InstanceV("Dataset", otherFields)) =>
          val otherRun = otherFields("collect") match
            case Value.NativeFnV(_, f) => () => Computation.run(f(Nil)) match
              case Value.ListV(xs) => xs
              case other           => List(other)
            case _ => () => Nil
          Pure(makeDatasetV(() => run().zip(otherRun()).map { case (a, b) =>
            Value.TupleV(a :: b :: Nil)
          }, interp))
        case _ => throw InterpretError("Dataset.zip(other: Dataset[U]): Dataset[(T, U)]")
      }),
      "zipWithIndex" -> Value.NativeFnV("Dataset.zipWithIndex",
        Computation.pureFn(_ => makeDatasetV(() =>
          run().zipWithIndex.map { case (v, i) => Value.TupleV(v :: Value.IntV(i.toLong) :: Nil) }
        , interp))
      ),
      "min" -> Value.NativeFnV("Dataset.min", Computation.pureFn { _ =>
        val xs = run()
        if xs.isEmpty then
          throw ScriptException(Value.InstanceV("RuntimeException",
            new IMap.Map1("message", Value.StringV("Dataset.min: empty dataset"))))
        else xs.reduce((a, b) => if compareValues(a, b) <= 0 then a else b)
      }),
      "max" -> Value.NativeFnV("Dataset.max", Computation.pureFn { _ =>
        val xs = run()
        if xs.isEmpty then
          throw ScriptException(Value.InstanceV("RuntimeException",
            new IMap.Map1("message", Value.StringV("Dataset.max: empty dataset"))))
        else xs.reduce((a, b) => if compareValues(a, b) >= 0 then a else b)
      }),
      "sum" -> Value.NativeFnV("Dataset.sum", Computation.pureFn { _ =>
        val xs = run()
        xs.foldLeft(Value.intV(0L): Value) { (acc, v) =>
          Computation.run(interp.infix(acc, "+", List(v), Map.empty))
        }
      }),
      "avg" -> Value.NativeFnV("Dataset.avg", Computation.pureFn { _ =>
        val xs = run()
        if xs.isEmpty then
          throw ScriptException(Value.InstanceV("RuntimeException",
            new IMap.Map1("message", Value.StringV("Dataset.avg: empty dataset"))))
        else
          val total = xs.foldLeft(Value.DoubleZero: Value) { (acc, v) =>
            Computation.run(interp.infix(acc, "+", List(v), Map.empty))
          }
          Computation.run(interp.infix(total, "/", List(Value.doubleV(xs.length.toDouble)), Map.empty))
      }),
      "top" -> Value.NativeFnV("Dataset.top", {
        case List(Value.IntV(n)) =>
          Pure(Value.ListV(run().sortWith((a, b) => compareValues(a, b) > 0).take(n.toInt)))
        case _ => throw InterpretError("Dataset.top(n: Int): List[T]")
      }),
      "takeOrdered" -> Value.NativeFnV("Dataset.takeOrdered", {
        case List(Value.IntV(n)) =>
          Pure(Value.ListV(run().sortWith((a, b) => compareValues(a, b) < 0).take(n.toInt)))
        case _ => throw InterpretError("Dataset.takeOrdered(n: Int): List[T]")
      }),
      "countByValue" -> Value.NativeFnV("Dataset.countByValue", Computation.pureFn { _ =>
        val items = run()
        val grouped = items.groupBy(identity).map { case (k, vs) => (k, Value.IntV(vs.length.toLong)) }
        Value.MapV(grouped)
      }),
      "partition" -> Value.NativeFnV("Dataset.partition", {
        case List(predFn) =>
          val (yes, no) = run().partition(item =>
            Computation.run(interp.callValue1(predFn, item, Map.empty)) match
              case Value.BoolV(b) => b
              case _              => false
          )
          Pure(Value.TupleV(Value.ListV(yes) :: Value.ListV(no) :: Nil))
        case _ => throw InterpretError("Dataset.partition(p: T => Boolean): (List[T], List[T])")
      }),
      "mkString" -> Value.NativeFnV("Dataset.mkString", {
        case Nil =>
          Pure(Value.StringV(run().map(v => Value.show(v)).mkString))
        case List(Value.StringV(sep)) =>
          Pure(Value.StringV(run().map(v => Value.show(v)).mkString(sep)))
        case List(Value.StringV(start), Value.StringV(sep), Value.StringV(end)) =>
          Pure(Value.StringV(run().map(v => Value.show(v)).mkString(start, sep, end)))
        case _ => throw InterpretError("Dataset.mkString[(sep)|(start, sep, end)]")
      }),
      "toMap" -> Value.NativeFnV("Dataset.toMap", Computation.pureFn { _ =>
        val pairs = run().map {
          case Value.TupleV(List(k, v)) => (k, v)
          case other => throw InterpretError(s"Dataset.toMap: element is not a 2-tuple: ${Value.show(other)}")
        }
        Value.MapV(pairs.toMap)
      }),
      "toSet" -> Value.NativeFnV("Dataset.toSet", Computation.pureFn { _ =>
        Value.ListV(run().distinct)
      }),
      "saveToFile" -> Value.NativeFnV("Dataset.saveToFile", {
        case List(Value.StringV(path)) =>
          val text = run().map(v => Value.show(v)).mkString("", "\n", "\n")
          java.nio.file.Files.write(java.nio.file.Paths.get(path),
            text.getBytes(java.nio.charset.StandardCharsets.UTF_8))
          Computation.PureUnit
        case _ => throw InterpretError("Dataset.saveToFile(path: String): Unit")
      }),
      "groupBy" -> Value.NativeFnV("Dataset.groupBy", {
        case List(keyFn) => Pure(makeDatasetV(() => {
          val items = run()
          val grouped = items.groupBy(item => Computation.run(interp.callValue1(keyFn, item, Map.empty)))
          grouped.toList.map { case (k, vs) => Value.TupleV(k :: Value.ListV(vs) :: Nil) }
        }, interp))
        case _ => throw InterpretError("Dataset.groupBy(key: T => K): Dataset[(K, List[T])]")
      }),
      "reduceByKey" -> Value.NativeFnV("Dataset.reduceByKey", {
        case List(keyFn) => Pure(Value.NativeFnV("Dataset.reduceByKey$combine", {
          case List(combineFn) => Pure(makeDatasetV(() => {
            val items = run()
            val grouped = items.groupBy(item => Computation.run(interp.callValue1(keyFn, item, Map.empty)))
            grouped.toList.map { case (k, vs) =>
              val reduced = vs.reduce((a, b) => Computation.run(interp.callValue2(combineFn, a, b, Map.empty)))
              Value.TupleV(k :: reduced :: Nil)
            }
          }, interp))
          case _ => throw InterpretError("Dataset.reduceByKey$combine")
        }))
        case _ => throw InterpretError("Dataset.reduceByKey(key: T => K)(combine: (T, T) => T): Dataset[(K, T)]")
      }),
      "sortBy" -> Value.NativeFnV("Dataset.sortBy", {
        case List(keyFn) => Pure(makeDatasetV(() => {
          val items = run()
          val keyed = items.map(item => item -> Computation.run(interp.callValue1(keyFn, item, Map.empty)))
          keyed.sortWith { (p1, p2) =>
            compareValues(p1._2, p2._2) < 0
          }.map(_._1)
        }, interp))
        case _ => throw InterpretError("Dataset.sortBy(key: T => K): Dataset[T]")
      }),
      "runLocal"    -> Value.NativeFnV("Dataset.runLocal",    Computation.pureFn(_ => makeDatasetV(run, interp))),
      "runParallel" -> Value.NativeFnV("Dataset.runParallel", Computation.pureFn(_ => makeDatasetV(run, interp))),
      "collect"     -> Value.NativeFnV("Dataset.collect",     Computation.pureFn(_ => Value.ListV(run()))),
      "count"       -> Value.NativeFnV("Dataset.count",       Computation.pureFn(_ => Value.intV(run().length.toLong))),
      "reduce" -> Value.NativeFnV("Dataset.reduce", {
        case List(combineFn) =>
          val items = run()
          if items.isEmpty then
            throw ScriptException(Value.InstanceV("RuntimeException",
              new IMap.Map1("message", Value.StringV("Dataset.reduce: empty dataset"))))
          val result = items.tail.foldLeft(items.head) { (acc, item) =>
            Computation.run(interp.callValue2(combineFn, acc, item, Map.empty))
          }
          Pure(result)
        case _ => throw InterpretError("Dataset.reduce(combine: (T, T) => T): T")
      }),
      "fold" -> Value.NativeFnV("Dataset.fold", {
        case List(z) => Pure(Value.NativeFnV("Dataset.fold$combine", {
          case List(combineFn) =>
            val items = run()
            val result = items.foldLeft(z) { (acc, item) =>
              Computation.run(interp.callValue2(combineFn, acc, item, Map.empty))
            }
            Pure(result)
          case _ => throw InterpretError("Dataset.fold$combine")
        }))
        case _ => throw InterpretError("Dataset.fold(z: U)(combine: (U, T) => U): U")
      }),
      "foreach" -> Value.NativeFnV("Dataset.foreach", {
        case List(action) =>
          run().foreach(item => Computation.run(interp.callValue1(action, item, Map.empty)))
          Computation.PureUnit
        case _ => throw InterpretError("Dataset.foreach(action: T => Unit): Unit")
      }),
      "first"  -> Value.NativeFnV("Dataset.first",  Computation.pureFn(_ => Value.OptionV(run().headOption.orNull))),
      "toGenerator" -> Value.NativeFnV("Dataset.toGenerator", Computation.pureFn(_ => {
        val items = run()
        val queue = new interp.GenQueue()
        Thread.ofVirtual().start { () =>
          interp._genQueueTL.set(queue)
          try items.foreach(item => queue.put(Some(item)))
          catch case _: Throwable => ()
          finally try queue.put(None) catch case _ => ()
        }
        interp.makeGeneratorV(queue)
      })),
    ))
