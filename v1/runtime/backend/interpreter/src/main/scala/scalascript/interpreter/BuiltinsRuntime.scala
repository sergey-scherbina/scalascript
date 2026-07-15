package scalascript.interpreter

import Computation.{Pure, Perform}
import scala.collection.immutable.{Map => IMap}

/** Built-in global definitions: standard library companions, HTML DSL,
 *  exception constructors, effect runners, and integration shims.
 *  Called once from `Interpreter.run` before user sections execute.
 */
private[interpreter] object BuiltinsRuntime:

  def initBuiltins(interp: Interpreter): Unit =
    def nativeP(name: String)(f: List[Value] => Value): Unit =
      interp.globals(name) = Value.NativeFnV(name, Computation.pureFn(f))
    // Deferred lookup: always returns a proxy NativeFnV that resolves the real
    // global at call time.  This supports Phase 2 lazy loading — plugin intrinsics
    // are not registered during initBuiltins, so we cannot look them up eagerly.
    // On first call, if the global is still missing, ensurePluginsLoaded() runs
    // to give the ServiceLoader a chance to register it.
    def globalOrStub(name: String): Value =
      Value.NativeFnV(name, args =>
        val fn = interp.globals.getOrElse(name, null)
        if fn != null then interp.callValue(fn, args, Map.empty)
        else if interp._pluginsLoaded then
          throw InterpretError(s"'$name' requires a plugin that is not loaded")
        else
          interp.ensurePluginsLoaded()
          val fn2 = interp.globals.getOrElse(name, null)
          if fn2 != null then interp.callValue(fn2, args, Map.empty)
          else throw InterpretError(s"'$name' requires a plugin that is not loaded")
      )

    // Phase 2 lazy loading: install only the built-in interpreter intrinsics
    // (Console.println, assert, etc.) eagerly.  Plugin intrinsics (HTTP, SQL,
    // auth, …) are installed on first use via Interpreter.ensurePluginsLoaded()
    // so scripts like `hello.ssc` that never call a plugin never pay the
    // ServiceLoader scan cost.
    interp.installNativeIntrinsics(InterpreterIntrinsics)

    // Stage 5+/B.3 — Console companion object.
    // Normalize rewrites bare `println` → `Console.println`; the companion lets
    // user code also call `Console.println(...)` explicitly without the rewrite.
    interp.globals("Console") = Value.InstanceV("Console", Map(
      "println" -> interp.globals("Console.println"),
      "print"   -> interp.globals("Console.print")
    ))
    // Backward-compat aliases for tests / runSnippet callers that bypass Normalize.
    interp.globals("println") = interp.globals("Console.println")
    interp.globals("print")   = interp.globals("Console.print")

    // System companion — cross-backend timing API (matches JS Date.now(), Scala System).
    interp.globals("System") = Value.InstanceV("System", Map(
      "currentTimeMillis" -> interp.globals("System.currentTimeMillis"),
      "nanoTime"          -> interp.globals("System.nanoTime")
    ))

    // Plugin-provided companion objects (Db, DriverManager, Graph) are set up in
    // setupPluginCompanions, called from ensurePluginsLoaded after the plugin
    // intrinsics have been registered.

    // assert / require / nanoTime / getenv / doc / render / Some / List now
    // live in CoreIntrinsics (Stage 5+/E); installNativeIntrinsics routes them.
    // httpClient(baseUrl) { block } — handled as a special form in eval
    // (double-apply pattern) so the block is evaluated directly rather than
    // wrapped as a thunk.  See the Term.Apply case in eval below.
    // List companion object — fill/tabulate are curried (List.fill(n)(elem))
    interp.globals("List.fill") = Value.NativeFnV("List.fill", {
      case List(Value.IntV(n)) =>
        Pure(Value.NativeFnV("List.fill.n", {
          case List(elem) => Pure(Value.ListV(List.fill(n.toInt)(elem)))
          case _          => throw InterpretError("List.fill(n)(elem)")
        }))
      case _ => throw InterpretError("List.fill(n)(elem)")
    })
    interp.globals("List.tabulate") = Value.NativeFnV("List.tabulate", {
      case List(Value.IntV(n)) =>
        Pure(Value.NativeFnV("List.tabulate.n", {
          case List(f) =>
            // f(i) may perform effects — sequence the computations
            Computation.mapSequenceRange(0, n.toInt, i =>
              interp.callValue1(f, Value.intV(i), Map.empty))
          case _ => throw InterpretError("List.tabulate(n)(f)")
        }))
      case _ => throw InterpretError("List.tabulate(n)(f)")
    })
    interp.globals("List.range") = Value.NativeFnV("List.range", {
      case List(Value.IntV(from), Value.IntV(until)) =>
        if from >= until then Computation.PureEmptyList
        else
          var list: List[Value] = Nil; var i = until - 1
          while i >= from do { list = Value.intV(i) :: list; i -= 1 }
          Pure(Value.ListV(list))
      case List(Value.IntV(from), Value.IntV(until), Value.IntV(step)) =>
        val buf = new scala.collection.mutable.ArrayBuffer[Value](math.max(0, ((until - from) / step).toInt + 1))
        var i = from; while (if step > 0 then i < until else i > until) do { buf += Value.intV(i); i += step }
        Pure(Value.ListV(buf.toList))
      case _ => throw InterpretError("List.range(from, until[, step])")
    })
    val listNative = interp.globals("List")
    interp.globals("List") = Value.InstanceV("List", Map(
      "fill"     -> interp.globals("List.fill"),
      "tabulate" -> interp.globals("List.tabulate"),
      "range"    -> interp.globals("List.range"),
      "empty"    -> Value.EmptyList,
      "apply"    -> listNative
    ))
    // Seq / Iterable — observably identical to `List` in an EAGER interpreter (Scala's default `Seq` /
    // `Iterable` IS `List`, and they display as `List(…)`), so they share the `ListV` backing.
    // (collection-ctor-aliases.)
    for name <- List("Seq", "Iterable") do
      interp.globals(name) = Value.InstanceV(name, Map(
        "fill"     -> interp.globals("List.fill"),
        "tabulate" -> interp.globals("List.tabulate"),
        "range"    -> interp.globals("List.range"),
        "empty"    -> Value.EmptyList,
        "apply"    -> Value.NativeFnV(name, args => Pure(Value.ListV(args)))
      ))
    // Vector / IndexedSeq — a REAL indexed sequence (`Value.VectorV`) so `vec(i)` / `.updated` are
    // O(log₃₂ n), not the O(n) a List would give. (IndexedSeq's default impl is Vector.)
    // (collection-vector-indexed.)
    for name <- List("Vector", "IndexedSeq") do
      interp.globals(name) = Value.InstanceV(name, Map(
        "apply"    -> Value.NativeFnV(name, args => Pure(Value.VectorV(args.toVector))),
        "empty"    -> Value.VectorV(Vector.empty),
        "fill"     -> Value.NativeFnV(s"$name.fill", {
          case List(Value.IntV(n)) =>
            Pure(Value.NativeFnV(s"$name.fill.n", {
              case List(elem) => Pure(Value.VectorV(Vector.fill(n.toInt)(elem)))
              case _          => throw InterpretError(s"$name.fill(n)(elem)")
            }))
          case _ => throw InterpretError(s"$name.fill(n)(elem)")
        }),
        "tabulate" -> Value.NativeFnV(s"$name.tabulate", {
          case List(Value.IntV(n)) =>
            Pure(Value.NativeFnV(s"$name.tabulate.n", {
              case List(f) =>
                Computation.mapSequenceRange(0, n.toInt, i => interp.callValue1(f, Value.intV(i), Map.empty))
                  .map { case lv: Value.ListV => Value.VectorV(lv.items.toVector); case v => v }
              case _ => throw InterpretError(s"$name.tabulate(n)(f)")
            }))
          case _ => throw InterpretError(s"$name.tabulate(n)(f)")
        }),
        "range"    -> Value.NativeFnV(s"$name.range", {
          case List(Value.IntV(from), Value.IntV(until)) =>
            Pure(Value.VectorV((from until until).map(i => Value.intV(i.toInt): Value).toVector))
          case List(Value.IntV(from), Value.IntV(until), Value.IntV(step)) =>
            Pure(Value.VectorV((from until until by step).map(i => Value.intV(i.toInt): Value).toVector))
          case _ => throw InterpretError(s"$name.range(from, until[, step])")
        })
      ))
    // Array — a REAL mutable array (`Value.ArrayV`): in-place `a(i) = x` + reference identity, unlike
    // the immutable `ListV` types. (collection-real-type.)
    interp.globals("Array") = Value.InstanceV("Array", Map(
      "apply"    -> Value.NativeFnV("Array", args => Pure(Value.ArrayV(args.toArray))),
      "empty"    -> Value.NativeFnV("Array.empty", _ => Pure(Value.ArrayV(Array.empty[Value]))),
      "fill"     -> Value.NativeFnV("Array.fill", {
        case List(Value.IntV(n)) =>
          Pure(Value.NativeFnV("Array.fill.n", {
            case List(elem) => Pure(Value.ArrayV(Array.fill(n.toInt)(elem)))
            case _          => throw InterpretError("Array.fill(n)(elem)")
          }))
        case _ => throw InterpretError("Array.fill(n)(elem)")
      }),
      "ofDim"    -> Value.NativeFnV("Array.ofDim", {
        case List(Value.IntV(n)) => Pure(Value.ArrayV(Array.fill(n.toInt)(Value.IntV(0))))
        case _                   => throw InterpretError("Array.ofDim(n)")
      }),
      "tabulate" -> Value.NativeFnV("Array.tabulate", {
        case List(Value.IntV(n)) =>
          Pure(Value.NativeFnV("Array.tabulate.n", {
            case List(f) =>
              Computation.mapSequenceRange(0, n.toInt, i => interp.callValue1(f, Value.intV(i), Map.empty))
                .map { case lv: Value.ListV => Value.ArrayV(lv.items.toArray); case v => v }
            case _ => throw InterpretError("Array.tabulate(n)(f)")
          }))
        case _ => throw InterpretError("Array.tabulate(n)(f)")
      }),
      "range"    -> Value.NativeFnV("Array.range", {
        case List(Value.IntV(from), Value.IntV(until)) =>
          Pure(Value.ArrayV((from until until).map(i => Value.intV(i.toInt): Value).toArray))
        case List(Value.IntV(from), Value.IntV(until), Value.IntV(step)) =>
          Pure(Value.ArrayV((from until until by step).map(i => Value.intV(i.toInt): Value).toArray))
        case _ => throw InterpretError("Array.range(from, until[, step])")
      })
    ))
    // LazyList — a REAL lazy list backed by Scala's own `LazyList[Value]` (laziness, memoization,
    // infinite streams, and `toString` parity with the JVM backend). (collection-real-type.)
    interp.globals("LazyList") = Value.InstanceV("LazyList", Map(
      "apply"      -> Value.NativeFnV("LazyList", args => Pure(Value.LazyListV(LazyList.from(args)))),
      "empty"      -> Value.LazyListV(LazyList.empty),
      "from"       -> Value.NativeFnV("LazyList.from", {
        case List(Value.IntV(start)) => Pure(Value.LazyListV(LazyList.from(start.toInt).map(i => Value.intV(i))))
        case List(Value.IntV(start), Value.IntV(step)) =>
          Pure(Value.LazyListV(LazyList.iterate(start.toInt)(_ + step.toInt).map(i => Value.intV(i))))
        case _ => throw InterpretError("LazyList.from(start[, step])")
      }),
      "continually"-> Value.NativeFnV("LazyList.continually", {
        case List(elem) => Pure(Value.LazyListV(LazyList.continually(elem)))
        case _          => throw InterpretError("LazyList.continually(elem)")
      }),
      "iterate"    -> Value.NativeFnV("LazyList.iterate", {
        case List(seed) =>
          Pure(Value.NativeFnV("LazyList.iterate.f", {
            case List(f) =>
              // lazily unfold: each step forces f(prev) — must be a PURE function for laziness.
              Pure(Value.LazyListV(LazyList.iterate(seed)(prev => Computation.run(interp.callValue1(f, prev, Map.empty)))))
            case _ => throw InterpretError("LazyList.iterate(seed)(f)")
          }))
        case _ => throw InterpretError("LazyList.iterate(seed)(f)")
      }),
      "tabulate"   -> Value.NativeFnV("LazyList.tabulate", {
        case List(Value.IntV(n)) =>
          Pure(Value.NativeFnV("LazyList.tabulate.n", {
            case List(f) =>
              Pure(Value.LazyListV(LazyList.tabulate(n.toInt)(i => Computation.run(interp.callValue1(f, Value.intV(i), Map.empty)))))
            case _ => throw InterpretError("LazyList.tabulate(n)(f)")
          }))
        case _ => throw InterpretError("LazyList.tabulate(n)(f)")
      }),
      "range"      -> Value.NativeFnV("LazyList.range", {
        case List(Value.IntV(from), Value.IntV(until)) =>
          Pure(Value.LazyListV(LazyList.range(from.toInt, until.toInt).map(i => Value.intV(i))))
        case List(Value.IntV(from), Value.IntV(until), Value.IntV(step)) =>
          Pure(Value.LazyListV(LazyList.range(from.toInt, until.toInt, step.toInt).map(i => Value.intV(i))))
        case _ => throw InterpretError("LazyList.range(from, until[, step])")
      })
    ))
    // Set constructor: `Set(a, b)`, `Set[T]()`, `Set.empty`.
    val setNative = Value.NativeFnV("Set", args => Pure(Value.SetV(args.toSet)))
    interp.globals("Set") = Value.InstanceV("Set", Map(
      "empty" -> Value.SetV(Set.empty),
      "apply" -> setNative
    ))
    // Map / math.sqrt-round now live in CoreIntrinsics (Stage 5+/E).
    interp.globals("None") = Value.NoneV
    interp.globals("Some") = Value.NativeFnV("Some", { case List(v) => Pure(Value.someV(v)); case _ => throw InterpretError("Some requires exactly one argument") })
    interp.globals("Nil")  = Value.EmptyList
    // Builtin Either / Some carry a single `value` field. Registering their field
    // order lets the positional `fieldsArr` representation (Value.singleValue) be
    // read uniformly by PatternRuntime / dispatch / JIT — exactly like a user
    // `case class Right(value)`. Idempotent if std/either.ssc later re-registers them.
    if !interp.typeFieldOrder.contains("Right") then interp.typeFieldOrder("Right") = List("value")
    if !interp.typeFieldOrder.contains("Left")  then interp.typeFieldOrder("Left")  = List("value")
    if !interp.typeFieldOrder.contains("Some")  then interp.typeFieldOrder("Some")  = List("value")

    // ── Primitive type constructors ────────────────────────────────────────
    // `Int("42")` → 42, `Long("9999")` → 9999, `Double("3.14")` → 3.14.
    // Mirrors Scala's companion `apply`-style idiom; the interpreter has no
    // implicit numeric coercion so these must be explicit.
    interp.globals("Int") = Value.NativeFnV("Int", {
      case List(Value.StringV(s))  =>
        try Computation.pureIntV(s.trim.toLong)
        catch case _: NumberFormatException => throw InterpretError(s"Int: not a valid integer: '$s'")
      case List(Value.IntV(n))     => Computation.pureIntV(n)
      case List(Value.DoubleV(d))  => Computation.pureIntV(d.toLong)
      case List(Value.DecimalV(d)) => Computation.pureIntV(d.toLong)
      case List(Value.BigIntV(b))  => Computation.pureIntV(b.toLong)
      case List(other)             => throw InterpretError(s"Int: cannot convert ${Value.show(other)}")
      case _                       => throw InterpretError("Int(value)")
    })
    interp.globals("Long") = interp.globals("Int")  // Long and Int share the same boxed IntV
    interp.globals("Double") = Value.NativeFnV("Double", {
      case List(Value.StringV(s))  =>
        try Pure(Value.doubleV(s.trim.toDouble))
        catch case _: NumberFormatException => throw InterpretError(s"Double: not a valid number: '$s'")
      case List(Value.IntV(n))     => Pure(Value.doubleV(n.toDouble))
      case List(Value.DoubleV(d))  => Pure(Value.doubleV(d))
      case List(Value.DecimalV(d)) => Pure(Value.doubleV(d.toDouble))
      case List(Value.BigIntV(b))  => Pure(Value.doubleV(b.toDouble))
      case List(other)             => throw InterpretError(s"Double: cannot convert ${Value.show(other)}")
      case _                       => throw InterpretError("Double(value)")
    })

    // ── BigInt constructor (exact-numerics v1.64) ───────────────────────
    // `BigInt(123)`, `BigInt("123456789012345678901234567890")`, BigInt(bigIntV).
    interp.globals("BigInt") = Value.NativeFnV("BigInt", {
      case List(Value.IntV(n))     => Pure(Value.BigIntV(BigInt(n)))
      case List(Value.BigIntV(n))  => Pure(Value.BigIntV(n))
      case List(Value.StringV(s))  =>
        try Pure(Value.BigIntV(BigInt(s.trim)))
        catch case _: NumberFormatException =>
          throw InterpretError(s"BigInt: not a valid integer: '$s'")
      case List(Value.DoubleV(d)) if d == d.toLong.toDouble => Pure(Value.BigIntV(BigInt(d.toLong)))
      case List(other)             => throw InterpretError(s"BigInt: cannot build from ${Value.show(other)}")
      case _                       => throw InterpretError("BigInt requires exactly one argument")
    })

    // ── Decimal constructor (exact-numerics v1.64) ──────────────────────
    // `Decimal("12.34")` (canonical), `Decimal(123, 2)` = 1.23, Decimal(int),
    // Decimal(bigint), Decimal(decimal).  Building from Double is rejected —
    // exact decimals must not start from an inexact binary float.
    interp.globals("Decimal") = Value.NativeFnV("Decimal", {
      case List(Value.StringV(s)) =>
        try Pure(Value.DecimalV(BigDecimal(s.trim)))
        catch case _: NumberFormatException =>
          throw InterpretError(s"Decimal: not a valid number: '$s'")
      case List(Value.IntV(n))            => Pure(Value.DecimalV(BigDecimal(n)))
      case List(Value.BigIntV(n))         => Pure(Value.DecimalV(BigDecimal(n)))
      case List(Value.DecimalV(d))        => Pure(Value.DecimalV(d))
      // Decimal(unscaled, scale): integer unscaled value scaled by 10^-scale.
      case List(Value.IntV(u), Value.IntV(sc))    => Pure(Value.DecimalV(BigDecimal(BigInt(u), sc.toInt)))
      case List(Value.BigIntV(u), Value.IntV(sc)) => Pure(Value.DecimalV(BigDecimal(u, sc.toInt)))
      case List(Value.DoubleV(_)) =>
        throw InterpretError("Decimal: refusing to build from a Double (inexact). Use Decimal(\"…\") or Decimal(unscaled, scale).")
      case List(other) => throw InterpretError(s"Decimal: cannot build from ${Value.show(other)}")
      case _           => throw InterpretError("Decimal(value) or Decimal(unscaled, scale)")
    })
    interp.globals("BigDecimal") = interp.globals("Decimal")

    // ── RoundingMode constants (exact-numerics v1.64) ───────────────────
    // `RoundingMode.HALF_UP` etc. — each is an InstanceV carrying its name,
    // consumed by Decimal.setScale / round / divide.
    def roundingMode(n: String): Value.InstanceV =
      Value.InstanceV("RoundingMode", new IMap.Map1("_name", Value.StringV(n)))
    interp.globals("RoundingMode") = Value.InstanceV("RoundingMode", IMap(
      "UP"        -> roundingMode("UP"),
      "DOWN"      -> roundingMode("DOWN"),
      "CEILING"   -> roundingMode("CEILING"),
      "FLOOR"     -> roundingMode("FLOOR"),
      "HALF_UP"   -> roundingMode("HALF_UP"),
      "HALF_DOWN" -> roundingMode("HALF_DOWN"),
      "HALF_EVEN" -> roundingMode("HALF_EVEN"),
      "UNNECESSARY" -> roundingMode("UNNECESSARY"),
    ))

    // ── Exception constructors ────────────────────────────────────────
    // Allow `throw RuntimeException("msg")` and `try ... catch { case e: ... }`
    // in ScalaScript code.  Each factory produces an InstanceV so field access
    // like `e.message` works naturally.
    def exceptionCtor(typeName: String): Value.NativeFnV =
      val singleton = Pure(Value.InstanceV(typeName, new IMap.Map1("message", Value.StringV(typeName))))
      Value.NativeFnV(typeName, {
        case Nil               => singleton
        case List(v)           => Pure(Value.InstanceV(typeName, new IMap.Map1("message", v)))
        case msg :: cause :: _ => Pure(Value.InstanceV(typeName, new IMap.Map2("message", msg, "cause", cause)))
      })
    List("RuntimeException", "Exception", "IllegalArgumentException",
         "IllegalStateException", "NumberFormatException", "ArithmeticException",
         "NullPointerException", "IndexOutOfBoundsException", "UnsupportedOperationException",
         "NoSuchElementException").foreach { n => interp.globals(n) = exceptionCtor(n) }

    // ── attemptCatch — wrap a thunk that might throw into Either ─────────
    interp.globals("attemptCatch") = Value.NativeFnV("attemptCatch", {
      case List(thunk) =>
        try
          val result = Computation.run(interp.callValue(thunk, Nil, Map.empty))
          Pure(Value.InstanceV("Right", new IMap.Map1("value", result)))
        catch
          case se: ScriptException =>
            Pure(Value.InstanceV("Left", new IMap.Map1("value", se.value)))
          case t: Throwable =>
            val msg = Option(t.getMessage).getOrElse(t.getClass.getSimpleName)
            Pure(Value.InstanceV("Left", new IMap.Map1("value",
              Value.InstanceV("RuntimeException", new IMap.Map1("message", Value.StringV(msg))))))
      case _ => interp.located("attemptCatch(thunk)")
    })

    // ── attemptCatchRaw — like attemptCatch but returns raw value (no Either boxing) ─
    interp.globals("attemptCatchRaw") = Value.NativeFnV("attemptCatchRaw", {
      case List(thunk) =>
        try
          val result = Computation.run(interp.callValue(thunk, Nil, Map.empty))
          Pure(result)
        catch
          case se: ScriptException => Pure(se.value)
          case t: Throwable =>
            val msg = Option(t.getMessage).getOrElse(t.getClass.getSimpleName)
            Pure(Value.InstanceV(t.getClass.getSimpleName, new IMap.Map1("message", Value.StringV(msg))))
      case _ => interp.located("attemptCatchRaw(thunk)")
    })

    // ── v1.16 Restart object — decisions for restartable { } { } ────────
    // Restart.resume(v)  — resume the suspended computation with value v
    // Restart.useDefault — resume with Unit (null/default)
    // Restart.rethrow    — re-throw the original error as a ScriptException
    interp.globals("Restart") = Value.InstanceV("Restart$", Map(
      "resume" -> Value.NativeFnV("Restart.resume", {
        case List(v) => Pure(Value.InstanceV("Restart$resume", new IMap.Map1("value", v)))
        case _       => throw InterpretError("Restart.resume(value)")
      }),
      "useDefault" -> Value.InstanceV("Restart$useDefault", Map.empty),
      "rethrow"    -> Value.InstanceV("Restart$rethrow",    Map.empty)
    ))

    // ── currentStackTrace — returns call stack as List[Frame] ────────────
    // By default filters out anonymous (<anon>) and _-prefixed synthetic frames.
    // Call setTraceVerbose(true) to include all frames.
    interp.globals("currentStackTrace") = Value.NativeFnV("currentStackTrace", _ =>
      Pure(Value.ListV(interp.callStackToList.reverse
        .filter { case (fn, _, _) =>
          interp.traceVerbose || (fn != "<anon>" && !fn.startsWith("_"))
        }
        .map { case (fn, file, line) =>
          Value.InstanceV("Frame", Map(
            "file" -> Value.StringV(file),
            "line" -> Value.intV(line),
            "fn"   -> Value.StringV(fn)
          ))
        }))
    )
    interp.globals("setTraceVerbose") = Value.NativeFnV("setTraceVerbose", {
      case List(Value.BoolV(v)) => interp.traceVerbose = v; Computation.PureUnit
      case _                    => Computation.PureUnit
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
    interp.globals("Using") = Value.InstanceV("Using", Map(
      "resource" -> Value.NativeFnV("Using.resource", {
        case List(res) =>
          Pure(Value.NativeFnV("Using.resource.block", Computation.pureFn {
            case List(block) =>
              // Locate the close member: works on case-class instances
              // (InstanceV.fields("close")) and on plain Map literals
              // (MapV with key "close") alike.
              val closeFn: Value | Null = res match
                case Value.InstanceV(_, fields) => fields.getOrElse("close", null)
                case Value.MapV(m)              => m.getOrElse(Value.StringV("close"), null)
                case _                          => null
              try Computation.run(interp.callValue1(block, res, Map.empty))
              finally
                if closeFn != null then
                  try { Computation.run(interp.callValue(closeFn, Nil, Map.empty)); () }
                  catch case _: Throwable => ()
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
    interp.globals("McpSchema") = Value.InstanceV("McpSchema", Map(
      "derived" -> Value.NativeFnV("McpSchema.derived", {
        case List(Value.InstanceV("Mirror", mfields)) =>
          val fieldNames: List[String] = mfields.getOrElse("fields", null) match
            case Value.ListV(xs) => xs.collect { case Value.StringV(s) => s }
            case _               => Nil
          val properties = Value.MapV(fieldNames.map(n =>
            (Value.StringV(n): Value) -> (Value.EmptyMap: Value)
          ).toMap)
          val required = Value.ListV(fieldNames.map(Value.StringV.apply))
          val schemaV = Value.MapV(Map(
            (Value.StringV("type"):       Value) -> (Value.StringV("object"): Value),
            (Value.StringV("properties"): Value) -> (properties:              Value),
            (Value.StringV("required"):   Value) -> (required:                Value)
          ))
          Pure(Value.InstanceV("McpSchema", new IMap.Map1("schema", schemaV)))
        case _ => Pure(Value.InstanceV("McpSchema", new IMap.Map1("schema", Value.EmptyMap)))
      })
    ))

    // ── compiletime — metaprogramming primitives ─────────────────────────
    interp.globals("compiletime") = Value.InstanceV("compiletime", Map(
      "error" -> Value.NativeFnV("compiletime.error", {
        case List(Value.StringV(msg)) => interp.located(s"compiletime.error: $msg")
        case List(v)                  => interp.located(s"compiletime.error: ${Value.show(v)}")
        case _                        => interp.located("compiletime.error: (no message)")
      }),
      // constValue and summonInline are handled as Term.ApplyType in eval;
      // these stubs exist so `compiletime` resolves as a namespace object.
      "constValue"    -> Value.NativeFnV("compiletime.constValue",    _ => Computation.PureUnit),
      "summonInline"  -> Value.NativeFnV("compiletime.summonInline",  _ => Computation.PureUnit)
    ))

    // ── restricted quoted macros — interpreter/run-path parity ───────────
    //
    // Parser preprocessing lowers `${ impl('x) }` and `'{ $x + ... }` to
    // these helpers. Link-time expansion still erases the helper form for
    // separately compiled modules; the interpreter keeps enough structure to
    // run the direct quoted-body subset without going through `ssc link`.
    interp.globals("__ssc_macro__") = Value.NativeFnV("__ssc_macro__", {
      // The `${ ... }` splice yields the underlying value of the macro's
      // `Expr[T]` result. Direct-quote bodies (`'{ ... }`) already evaluate to
      // the raw value, but an impl that returns `Expr(v)` explicitly (e.g. the
      // `Some` branch of an `x.asValue match`) hands back an `Expr` instance —
      // unwrap it to its `value` so the splice produces `T`, matching the
      // link-time const-fold on the generated backends (arch-meta-v2 Track B).
      case List(Value.InstanceV("Expr", fields)) =>
        Pure(fields.getOrElse("value", Value.UnitV))
      case List(v) => Pure(v)
      case _       => interp.located("__ssc_macro__(expr)")
    })
    interp.globals("__ssc_macro_error__") = Value.NativeFnV("__ssc_macro_error__", {
      case List(Value.StringV(msg)) => interp.located(s"quoted macro error: $msg")
      case List(v)                  => interp.located(s"quoted macro error: ${Value.show(v)}")
      case _                        => interp.located("quoted macro error: unsupported restricted quoted macro form")
    })
    interp.globals("__ssc_quote__") = Value.NativeFnV("__ssc_quote__", {
      case List(Value.StringV(name), value) =>
        Pure(Value.InstanceV("Expr", Map(
          "name"  -> Value.StringV(name),
          "value" -> value
        )))
      case List(Value.StringV(name)) =>
        Pure(Value.InstanceV("Expr", Map(
          "name"  -> Value.StringV(name),
          "value" -> Value.NoneV
        )))
      case _ => interp.located("__ssc_quote__(name, value)")
    })
    interp.globals("__ssc_quote_expr__") = Value.NativeFnV("__ssc_quote_expr__", {
      case List(v) => Pure(v)
      case _       => interp.located("__ssc_quote_expr__(value)")
    })
    interp.globals("__ssc_splice__") = Value.NativeFnV("__ssc_splice__", {
      case List(Value.StringV(_), Value.InstanceV("Expr", fields)) =>
        Pure(fields.getOrElse("value", Value.UnitV))
      case List(Value.StringV(_), value) =>
        Pure(value)
      case List(Value.InstanceV("Expr", fields)) =>
        Pure(fields.getOrElse("value", Value.UnitV))
      case _ => interp.located("__ssc_splice__(name, expr)")
    })
    interp.globals("Expr") = Value.InstanceV("Expr", Map(
      "apply" -> Value.NativeFnV("Expr.apply", {
        case List(v) =>
          Pure(Value.InstanceV("Expr", Map(
            "name"  -> Value.StringV("<literal>"),
            "value" -> v
          )))
        case _ => interp.located("Expr(value)")
      })
    ))
    interp.globals("QuotedContext") = Value.InstanceV("QuotedContext", Map.empty)

    interp.globals("math.Pi")   = Value.doubleV(math.Pi)
    interp.globals("math.E")    = Value.doubleV(math.E)
    // math as an object so `math.sqrt(x)` works via field dispatch
    interp.globals("math") = Value.InstanceV("math", Map(
      "sqrt"  -> interp.globals("math.sqrt"),
      "abs"   -> interp.globals("math.abs"),
      "pow"   -> interp.globals("math.pow"),
      "max"   -> interp.globals("math.max"),
      "min"   -> interp.globals("math.min"),
      "floor" -> interp.globals("math.floor"),
      "ceil"  -> interp.globals("math.ceil"),
      "round" -> interp.globals("math.round"),
      "Pi"    -> interp.globals("math.Pi"),
      "E"     -> interp.globals("math.E")
    ))

    // v1.17.x — oauth namespace: standalone OAuth 2.1 Authorization Server.
    // Mirrors the `math` companion-object pattern: dotted QualifiedName
    // entries from `OAuthIntrinsics` get a sibling InstanceV bound to
    // `interp.globals("oauth")` so scripts can write `oauth.authServer(...)`.
    // v1.17.x — nested `oauth.client.*` namespace: OAuth client SDK
    // for .ssc apps (auth-code+PKCE, refresh, client_credentials,
    // TokenHolder).
    val oauthClient = Value.InstanceV("oauth.client", Map(
      "discoverAs"                 -> globalOrStub("oauth.client.discoverAs"),
      "discoverRs"                 -> globalOrStub("oauth.client.discoverRs"),
      "freshPkce"                  -> globalOrStub("oauth.client.freshPkce"),
      "freshState"                 -> globalOrStub("oauth.client.freshState"),
      "verifyState"                -> globalOrStub("oauth.client.verifyState"),
      "authorizationUrl"           -> globalOrStub("oauth.client.authorizationUrl"),
      "exchangeAuthorizationCode"  -> globalOrStub("oauth.client.exchangeAuthorizationCode"),
      "refresh"                    -> globalOrStub("oauth.client.refresh"),
      "clientCredentials"          -> globalOrStub("oauth.client.clientCredentials"),
      "tokenHolder"                -> globalOrStub("oauth.client.tokenHolder")
    ))
    interp.globals("oauth") = Value.InstanceV("oauth", Map(
      "authServer"          -> globalOrStub("oauth.authServer"),
      "serveAuthServer"     -> globalOrStub("oauth.serveAuthServer"),
      "issueHmacToken"      -> globalOrStub("oauth.issueHmacToken"),
      "pkceVerifier"        -> globalOrStub("oauth.pkceVerifier"),
      "pkceChallenge"       -> globalOrStub("oauth.pkceChallenge"),
      "guard"               -> globalOrStub("oauth.guard"),
      "guardWithValidator"  -> globalOrStub("oauth.guardWithValidator"),
      "hmacValidator"       -> globalOrStub("oauth.hmacValidator"),
      "client"              -> oauthClient
    ))
    // v1.17.x — oidc namespace: OpenID Connect Identity Provider on top
    // of the OAuth Authorization Server.
    interp.globals("oidc") = Value.InstanceV("oidc", Map(
      "server" -> globalOrStub("oidc.server"),
      "serve"  -> globalOrStub("oidc.serve")
    ))

    // escape / collectCss / collectJs / scope now live in CoreIntrinsics
    // (Stage 5+/E–F); installNativeIntrinsics routes them.

    // ─── i18n intrinsics: t / setLocale / wc ────────────────────────────
    interp.globals("t") = Value.NativeFnV("t", {
      case List(Value.StringV(key)) =>
        val v = interp.i18nTranslations.get(interp.i18nLocale).flatMap(_.get(key)).getOrElse(key)
        Pure(Value.StringV(v))
      case _ => Computation.PureEmptyStr
    })
    interp.globals("setLocale") = Value.NativeFnV("setLocale", {
      case List(Value.StringV(code)) => interp.i18nLocale = code; Computation.PureUnit
      case _                         => Computation.PureUnit
    })
    interp.globals("wc") = Value.NativeFnV("wc", {
      case tag :: component :: rest =>
        val tagStr = Value.show(tag)
        val css = component match
          case Value.InstanceV(_, fields) =>
            val cv = fields.getOrElse("css", null)
            if cv == null then "" else Value.show(cv)
          case _ => ""
        val renderFn = component match
          case Value.InstanceV(_, fields) => fields.getOrElse("render", null)
          case _                          => null
        if renderFn != null then
          interp.callValue(renderFn, rest, Map.empty).map { inner =>
            val innerHtml = inner match
              case Value.InstanceV("_Raw", fields) =>
                val hv = fields.getOrElse("html", null)
                if hv == null then "" else Value.show(hv)
              case v => Value.show(v)
            val shadow = s"<template shadowrootmode=\"open\"><style>$css</style>$innerHtml</template>"
            Value.InstanceV("_Raw", new IMap.Map1("html", Value.StringV(s"<$tagStr-component>$shadow</$tagStr-component>")))
          }
        else
          Pure(Value.InstanceV("_Raw", new IMap.Map1("html",
            Value.StringV(s"<$tagStr-component></$tagStr-component>"))))
      case _ => Computation.PureUnit
    })

    // ─── Typed HTML DSL — `div(cls := "x", h1("hi"))` style ───────────
    //
    // Each tag is a native fn that takes a list of mixed args: Attr values
    // (key=value pairs from `<key> := <value>`) and children (Strings, _Raw
    // markers, or arbitrary Values rendered via Value.show).  The result is
    // a _Raw HTML node so it composes with html"..." without re-escaping.

    def attrKey(htmlName: String): Value.InstanceV =
      Value.InstanceV("AttrKey", new IMap.Map1("name", Value.StringV(htmlName)))

    // Attribute keys live under an `attr` namespace to avoid clobbering
    // very common user-side bindings like `name`, `id`, `title`, `value`.
    // Usage: `div(attr.cls := "hero", attr.id := "main")`.  Names that
    // collide with Scala reserved words use an underscore suffix
    // (`attr.type_`, `attr.for_`, `attr.method_`).
    interp.globals("attr") = Value.InstanceV("attr", Map(
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
      Value.InstanceV("_Raw", new IMap.Map1("html", Value.StringV(s)))

    /** Render a single child node: trusted html (_Raw) passes through,
     *  Lists flatten so `xs.map(li)` composes naturally inside a parent
     *  tag, everything else goes through `Value.show` + `htmlEscape`. */
    def renderChild(v: Value): String = v match
      case Value.InstanceV("_Raw", fields) =>
        val hv = fields.getOrElse("html", null)
        if hv == null then "" else Value.show(hv)
      case Value.ListV(items) =>
        items.map(renderChild).mkString
      case other => interp.htmlEscape(Value.show(other))

    /** Split a tag's arg-list into attribute pairs (from `key := value`)
     *  and children (everything else, rendered as HTML).  A `ListV` arg
     *  flattens into multiple children. */
    def renderTag(name: String, args: List[Value], voidTag: Boolean = false): Value.InstanceV =
      val attrs    = scala.collection.mutable.LinkedHashMap.empty[String, String]
      val children = StringBuilder()
      def handle(v: Value): Unit = v match
        case Value.InstanceV("Attr", fields) =>
          val kv = fields.getOrElse("name", null)
          val vv = fields.getOrElse("value", null)
          attrs(if kv == null then "" else Value.show(kv)) =
            if vv == null then "" else Value.show(vv)
        case Value.ListV(items) =>
          items.foreach(handle)
        case other =>
          children ++= renderChild(other)
      args.foreach(handle)
      val attrStr =
        if attrs.isEmpty then ""
        else attrs.map((k, v) => s""" $k="${interp.htmlEscape(v)}"""").mkString
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
      case List(Value.StringV(s)) => Value.InstanceV("_Raw", new IMap.Map1("html", Value.StringV(s)))
      case List(v)                => Value.InstanceV("_Raw", new IMap.Map1("html", Value.StringV(Value.show(v))))
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
    interp.globals("Response") = Value.InstanceV("Response", Map(
      "apply"              -> Value.NativeFnV("Response.apply", {
        case List(status, headers, body) =>
          Pure(Value.InstanceV("Response", new IMap.Map3("status", status, "headers", headers, "body", body)))
        case _ => throw InterpretError("Response(status, headers, body) expects 3 arguments")
      }),
      "html"               -> globalOrStub("Response.html"),
      "text"               -> globalOrStub("Response.text"),
      "json"               -> globalOrStub("Response.json"),
      "redirect"           -> globalOrStub("Response.redirect"),
      "notFound"           -> globalOrStub("Response.notFound"),
      "status"             -> globalOrStub("Response.status"),
      "basicAuthChallenge" -> globalOrStub("Response.basicAuthChallenge")
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
    interp.globals("Storage") = Value.InstanceV("Storage", Map(
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
    interp.globals("Async") = Value.InstanceV("Async", Map(
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
    interp.globals("Future") = Value.NativeFnV("Future", {
      case List(v) => Pure(Value.InstanceV("Future", new IMap.Map1("value", v)))
      case _       => throw InterpretError("Future(value)")
    })

    // ── v1.4 standard-library effects ────────────────────────────────────
    StdEffectsRuntime.install(interp)

    // ── v1.6 Actors — Phase 1/2/3 natives ──────────────────────────────
    ActorGlobals.install(interp)

    // ── v1.9 Coroutines + v1.10 Generator + suspend ────────────────────
    CoroutineRuntime.install(interp)

    // ── Reactive primitives: Signal / computed / effect ────────────────
    SignalRuntime.install(interp)

    // ── v1.21 Dataset — lazy local map-reduce pipeline ──────────────────
    DatasetRuntime.install(interp)

    // ── v2.x std.fs — synchronous file primitives ───────────────────────
    // Gated by Feature.FileSystem; mirrors the JS Node fs.* and JVM
    // java.nio.file calls so the same script works on all three backends.
    interp.globals("writeFile") = Value.NativeFnV("writeFile", {
      case List(Value.StringV(path), Value.StringV(contents)) =>
        val p = java.nio.file.Paths.get(path)
        java.nio.file.Files.write(p, contents.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        Computation.PureUnit
      case _ => throw InterpretError("writeFile(path: String, contents: String): Unit")
    })
    interp.globals("readFile") = Value.NativeFnV("readFile", {
      case List(Value.StringV(path)) =>
        val bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path))
        Pure(Value.StringV(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)))
      case _ => throw InterpretError("readFile(path: String): String")
    })
    // Value ⇄ string serialization (the interpreter wire format).  Handles
    // primitives, BigInt/Decimal, List/Map/Set/Tuple/Option, and case-class /
    // enum InstanceVs — enough to persist a whole event log.  Functions cannot
    // be serialized.
    interp.globals("toWire") = Value.NativeFnV("toWire", {
      case List(v) => Pure(Value.StringV(ValueSerializer.serialize(v)))
      case _       => throw InterpretError("toWire(value): String")
    })
    interp.globals("fromWire") = Value.NativeFnV("fromWire", {
      case List(Value.StringV(s)) => Pure(ValueSerializer.deserialize(s))
      case _                      => throw InterpretError("fromWire(s: String): Value")
    })
    interp.globals("deleteFile") = Value.NativeFnV("deleteFile", {
      case List(Value.StringV(path)) =>
        java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(path))
        Computation.PureUnit
      case _ => throw InterpretError("deleteFile(path: String): Unit")
    })
    interp.globals("exists") = Value.NativeFnV("exists", {
      case List(Value.StringV(path)) =>
        Computation.pureBool(java.nio.file.Files.exists(java.nio.file.Paths.get(path)))
      case _ => throw InterpretError("exists(path: String): Boolean")
    })
    // Directory primitives — global builtins alongside writeFile/readFile/exists
    // (the fs-plugin FsIntrinsics also defines these, but std.fs `extern def`s do
    // not link to a native impl on import; a script needs them bare, like writeFile).
    interp.globals("isDir") = Value.NativeFnV("isDir", {
      case List(Value.StringV(path)) =>
        Computation.pureBool(java.nio.file.Files.isDirectory(java.nio.file.Paths.get(path)))
      case _ => throw InterpretError("isDir(path: String): Boolean")
    })
    interp.globals("mkdir") = Value.NativeFnV("mkdir", {
      case List(Value.StringV(path)) =>
        val p = java.nio.file.Paths.get(path)
        if !java.nio.file.Files.exists(p) then java.nio.file.Files.createDirectory(p)
        Computation.PureUnit
      case _ => throw InterpretError("mkdir(path: String): Unit")
    })
    interp.globals("mkdirs") = Value.NativeFnV("mkdirs", {
      case List(Value.StringV(path)) =>
        java.nio.file.Files.createDirectories(java.nio.file.Paths.get(path))
        Computation.PureUnit
      case _ => throw InterpretError("mkdirs(path: String): Unit")
    })
    interp.globals("listDir") = Value.NativeFnV("listDir", {
      case List(Value.StringV(path)) =>
        val f = new java.io.File(path)
        val names = if f.isDirectory then f.listFiles().map(_.getName).toList.sorted else Nil
        Pure(Value.ListV(names.map(n => Value.StringV(n))))
      case _ => throw InterpretError("listDir(path: String): List[String]")
    })
    // ── std.process (bare-global primitive) — run a program in argv form ───
    // exec(command: List[String][, env: List[(String,String)]]): (Int, String, String)
    // = (exitCode, stdout, stderr). No shell (no word-splitting/injection); blocks until
    // exit. The optional env adds/overrides environment variables for the child (the
    // process env is otherwise inherited). stderr is drained on a side thread while
    // stdout is read, so a program filling both pipes can't deadlock. A spawn failure
    // (missing/unrunnable exe) throws; a process that runs and exits non-zero returns
    // its code in the tuple. See specs/exec-builtin.md.
    def runExec(parts: List[Value], envPairs: List[Value]): Computation =
      val cmd = parts.map {
        case Value.StringV(s) => s
        case other            => other.toString
      }
      if cmd.isEmpty then throw InterpretError("exec(command): command list must be non-empty")
      val utf8 = java.nio.charset.StandardCharsets.UTF_8
      try
        val pb = new java.lang.ProcessBuilder(cmd*)
        envPairs.foreach {
          case Value.TupleV(List(Value.StringV(k), Value.StringV(v))) => pb.environment().put(k, v)
          case _                                                      => ()
        }
        val proc = pb.start()
        val errF = java.util.concurrent.CompletableFuture.supplyAsync(
          () => new String(proc.getErrorStream.readAllBytes(), utf8))
        val out = new String(proc.getInputStream.readAllBytes(), utf8)
        val err = errF.get()
        val code = proc.waitFor()
        Pure(Value.TupleV(List(Value.IntV(code.toLong), Value.StringV(out), Value.StringV(err))))
      catch
        case e: java.io.IOException =>
          throw InterpretError("exec: cannot run '" + cmd.head + "': " + e.getMessage)
    interp.globals("exec") = Value.NativeFnV("exec", {
      case List(Value.ListV(parts))                          => runExec(parts, Nil)
      case List(Value.ListV(parts), Value.ListV(envPairs))   => runExec(parts, envPairs)
      case _ => throw InterpretError("exec(command: List[String][, env: List[(String,String)]]): (Int, String, String)")
    })

  /** Install plugin-provided companion objects (Db, DriverManager, Graph) after their
   *  NativeImpl intrinsics have been registered via `ensurePluginsLoaded`.
   *  Must be called after `installNativeIntrinsics(pluginImpls)` so the
   *  underlying globals are present. */
  def setupPluginCompanions(interp: Interpreter): Unit =
    interp.globals.get("DriverManager.getConnection").foreach { impl =>
      interp.globals("DriverManager") = Value.InstanceV("DriverManager", Map(
        "getConnection" -> impl
      ))
    }
    // Assemble the `Db` namespace object from every `Db.*` native the SQL plugin
    // registered — query/execute/insert/update plus pgListen/unlisten/
    // getNotifications (PostgreSQL LISTEN/NOTIFY, busi df-6).  Collected
    // generically so a plugin adding a new `Db.*` intrinsic needs no core change.
    if interp.globals.contains("Db.query") then
      val dbMethods = interp.globals.iterator.collect {
        case (k, v) if k.startsWith("Db.") => k.stripPrefix("Db.") -> v
      }.toMap
      interp.globals("Db") = Value.InstanceV("Db", dbMethods)
    interp.globals.get("Graph.putVertex").foreach { putVertexFn =>
      interp.globals("Graph") = Value.InstanceV("Graph", Map(
        "putVertex"      -> putVertexFn,
        "getVertex"      -> interp.globals("Graph.getVertex"),
        "vertices"       -> interp.globals("Graph.vertices"),
        "putEdge"        -> interp.globals("Graph.putEdge"),
        "edges"          -> interp.globals("Graph.edges"),
        "neighborValues" -> interp.globals("Graph.neighborValues"),
        "neighbors"      -> interp.globals("Graph.neighbors"),
        "putRdf"         -> interp.globals("Graph.putRdf"),
        "getRdf"         -> interp.globals("Graph.getRdf"),
        "triples"        -> interp.globals("Graph.triples")
      ))
    }
    // std.bench — Bench.opaque identity (anti-folding barrier on Rust target).
    interp.globals.get("Bench.opaque").foreach { opaqueFn =>
      interp.globals("Bench") = Value.InstanceV("Bench", Map(
        "opaque" -> opaqueFn
      ))
    }
    // v1.51 Streams — assemble Source companion from individual Source.* intrinsics
    interp.globals.get("Source.from").foreach { fromFn =>
      interp.globals("Source") = Value.InstanceV("Source", Map(
        "from"          -> fromFn,
        "single"        -> interp.globals.getOrElse("Source.single",        Value.UnitV),
        "empty"         -> interp.globals.getOrElse("Source.empty",         Value.UnitV),
        "fromGenerator" -> interp.globals.getOrElse("Source.fromGenerator", Value.UnitV),
        "signal"        -> interp.globals.getOrElse("Source.signal",        Value.UnitV),
        "bracket"       -> interp.globals.getOrElse("Source.bracket",       Value.UnitV),
        "fromSse"       -> interp.globals.getOrElse("Source.fromSse",       Value.UnitV),
        "fromWebSocket" -> interp.globals.getOrElse("Source.fromWebSocket", Value.UnitV),
        "tick"          -> interp.globals.getOrElse("Source.tick",          Value.UnitV),
        "unfold"        -> interp.globals.getOrElse("Source.unfold",        Value.UnitV),
        "fromCallback"  -> interp.globals.getOrElse("Source.fromCallback",  Value.UnitV),
      ))
    }
    interp.globals.get("OverflowStrategy.Backpressure").foreach { bp =>
      interp.globals("OverflowStrategy") = Value.InstanceV("OverflowStrategy", Map(
        "Backpressure" -> bp,
        "Block"        -> interp.globals.getOrElse("OverflowStrategy.Block",       Value.UnitV),
        "Drop"         -> interp.globals.getOrElse("OverflowStrategy.Drop",        Value.UnitV),
        "DropHead"     -> interp.globals.getOrElse("OverflowStrategy.DropHead",    Value.UnitV),
        "DropOldest"   -> interp.globals.getOrElse("OverflowStrategy.DropOldest",  Value.UnitV),
        "Fail"         -> interp.globals.getOrElse("OverflowStrategy.Fail",        Value.UnitV),
      ))
    }
    // v1.51.3 Sink + Flow companions
    interp.globals.get("Sink.foreach").foreach { fe =>
      interp.globals("Sink") = Value.InstanceV("Sink", Map(
        "foreach" -> fe,
        "fold"    -> interp.globals.getOrElse("Sink.fold",   Value.UnitV),
        "ignore"  -> interp.globals.getOrElse("Sink.ignore", Value.UnitV),
        "toList"      -> interp.globals.getOrElse("Sink.toList",      Value.UnitV),
        "toSseStream" -> interp.globals.getOrElse("Sink.toSseStream", Value.UnitV),
        "toWsRoom"    -> interp.globals.getOrElse("Sink.toWsRoom",    Value.UnitV),
      ))
    }
    interp.globals.get("Flow.map").foreach { fm =>
      interp.globals("Flow") = Value.InstanceV("Flow", Map(
        "map"          -> fm,
        "filter"       -> interp.globals.getOrElse("Flow.filter",       Value.UnitV),
        "fromFunction" -> interp.globals.getOrElse("Flow.fromFunction", Value.UnitV),
        "take"         -> interp.globals.getOrElse("Flow.take",         Value.UnitV),
        "drop"         -> interp.globals.getOrElse("Flow.drop",         Value.UnitV),
        "flatMap"      -> interp.globals.getOrElse("Flow.flatMap",      Value.UnitV),
        "scan"         -> interp.globals.getOrElse("Flow.scan",         Value.UnitV),
        "mapAsync"     -> interp.globals.getOrElse("Flow.mapAsync",     Value.UnitV),
        "recover"      -> interp.globals.getOrElse("Flow.recover",      Value.UnitV),
        "throttle"     -> interp.globals.getOrElse("Flow.throttle",     Value.UnitV),
        "debounce"     -> interp.globals.getOrElse("Flow.debounce",     Value.UnitV),
      ))
    }
    // v2.1.1 DStreams — assemble companions from individual DStream.* intrinsics
    interp.globals.get("Pipeline.create").foreach { createFn =>
      interp.globals("Pipeline") = Value.InstanceV("Pipeline", new IMap.Map1("create", createFn))
    }
    interp.globals.get("InMemory.source").foreach { sourceFn =>
      interp.globals("InMemory") = Value.InstanceV("InMemory", Map(
        "source"               -> sourceFn,
        "sourceWithTimestamps" -> interp.globals.getOrElse("InMemory.sourceWithTimestamps", Value.UnitV),
        "sink"                 -> interp.globals.getOrElse("InMemory.sink",                 Value.UnitV),
        "runAndCollect"        -> interp.globals.getOrElse("InMemory.runAndCollect",        Value.UnitV),
      ))
    }
    interp.globals.get("DSource.fromLocalSource").foreach { fromLocalFn =>
      interp.globals("DSource") = Value.InstanceV("DSource", Map(
        "fromLocalSource" -> fromLocalFn,
      ))
    }
    interp.globals.get("Backend.Direct").foreach { directFn =>
      interp.globals("Backend") = Value.InstanceV("Backend", Map(
        "Direct" -> directFn,
        "Native" -> interp.globals.getOrElse("Backend.Native", Value.UnitV),
        "Spark"  -> interp.globals.getOrElse("Backend.Spark",  Value.UnitV),
      ))
    }
    interp.globals.get("Window.fixed").foreach { fixedFn =>
      interp.globals("Window") = Value.InstanceV("Window", Map(
        "fixed"   -> fixedFn,
        "sliding" -> interp.globals.getOrElse("Window.sliding", Value.UnitV),
        "session" -> interp.globals.getOrElse("Window.session", Value.UnitV),
        "global"  -> interp.globals.getOrElse("Window.global",  Value.UnitV),
      ))
    }
    interp.globals.get("Trigger.afterWatermark").foreach { awFn =>
      interp.globals("Trigger") = Value.InstanceV("Trigger", Map(
        "afterWatermark"      -> awFn,
        "afterProcessingTime" -> interp.globals.getOrElse("Trigger.afterProcessingTime", Value.UnitV),
        "afterCount"          -> interp.globals.getOrElse("Trigger.afterCount",          Value.UnitV),
        "repeatedly"          -> interp.globals.getOrElse("Trigger.repeatedly",          Value.UnitV),
      ))
    }
    interp.globals.get("WatermarkStrategy.atEnd").foreach { atEndFn =>
      interp.globals("WatermarkStrategy") = Value.InstanceV("WatermarkStrategy", Map(
        "atEnd"                      -> atEndFn,
        "monotonicallyIncreasing"    -> interp.globals.getOrElse("WatermarkStrategy.monotonicallyIncreasing", Value.UnitV),
        "boundedOutOfOrder"          -> interp.globals.getOrElse("WatermarkStrategy.boundedOutOfOrder",      Value.UnitV),
      ))
    }
    interp.globals.get("AccumulationMode.Discarding").foreach { discFn =>
      interp.globals("AccumulationMode") = Value.InstanceV("AccumulationMode", Map(
        "Discarding"   -> discFn,
        "Accumulating" -> interp.globals.getOrElse("AccumulationMode.Accumulating", Value.UnitV),
      ))
    }
    interp.globals.get("KV").foreach { kvFn =>
      // KV is also used as a constructor directly; ensure it's accessible
      interp.globals("KV") = kvFn
    }
    // v2.1.6 — Production connector companions
    interp.globals.get("Kafka.source").foreach { srcFn =>
      interp.globals("Kafka") = Value.InstanceV("Kafka", Map(
        "source"         -> srcFn,
        "sourceAssigned" -> interp.globals.getOrElse("Kafka.sourceAssigned", Value.UnitV),
        "changelog"      -> interp.globals.getOrElse("Kafka.changelog",      Value.UnitV),
        "sink"           -> interp.globals.getOrElse("Kafka.sink",           Value.UnitV),
      ))
    }
    interp.globals.get("Files.source").foreach { srcFn =>
      interp.globals("Files") = Value.InstanceV("Files", Map(
        "source" -> srcFn,
        "sink"   -> interp.globals.getOrElse("Files.sink", Value.UnitV),
      ))
    }
    interp.globals.get("FileFormat.Text").foreach { textFn =>
      interp.globals("FileFormat") = Value.InstanceV("FileFormat", Map(
        "Text"    -> textFn,
        "Json"    -> interp.globals.getOrElse("FileFormat.Json",    Value.UnitV),
        "Parquet" -> interp.globals.getOrElse("FileFormat.Parquet", Value.UnitV),
        "Avro"    -> interp.globals.getOrElse("FileFormat.Avro",    Value.UnitV),
        "Csv"     -> interp.globals.getOrElse("FileFormat.Csv",     Value.UnitV),
      ))
    }
    interp.globals.get("Jdbc.source").foreach { srcFn =>
      interp.globals("Jdbc") = Value.InstanceV("Jdbc", Map(
        "source" -> srcFn,
        "sink"   -> interp.globals.getOrElse("Jdbc.sink", Value.UnitV),
      ))
    }
    interp.globals.get("Pulsar.source").foreach { srcFn =>
      interp.globals("Pulsar") = Value.InstanceV("Pulsar", Map(
        "source" -> srcFn,
        "sink"   -> interp.globals.getOrElse("Pulsar.sink", Value.UnitV),
      ))
    }
    interp.globals.get("Kinesis.source").foreach { srcFn =>
      interp.globals("Kinesis") = Value.InstanceV("Kinesis", Map(
        "source" -> srcFn,
        "sink"   -> interp.globals.getOrElse("Kinesis.sink", Value.UnitV),
      ))
    }
    interp.globals.get("DSource.fromDataset").foreach { fromDatasetFn =>
      val existing = interp.globals.get("DSource").collect {
        case Value.InstanceV(_, fs) => fs
      }.getOrElse(Map.empty)
      interp.globals("DSource") = Value.InstanceV("DSource", existing + ("fromDataset" -> fromDatasetFn))
    }
    // v2.1.7 — KeyedStateSpec companion
    interp.globals.get("KeyedStateSpec.value").foreach { valueFn =>
      interp.globals("KeyedStateSpec") = Value.InstanceV("KeyedStateSpec", new IMap.Map1("value", valueFn))
    }
    // v2.1.8 — SideInput + OutputTag companions
    interp.globals.get("SideInput.of").foreach { ofFn =>
      interp.globals("SideInput") = Value.InstanceV("SideInput", Map(
        "of"        -> ofFn,
        "singleton" -> interp.globals.getOrElse("SideInput.singleton", Value.UnitV),
        "asMap"     -> interp.globals.getOrElse("SideInput.asMap",     Value.UnitV),
      ))
    }
    interp.globals.get("OutputTag").foreach { tagFn =>
      interp.globals("OutputTag") = Value.InstanceV("OutputTag", Map(
        "apply"      -> tagFn,
        "withFilter" -> interp.globals.getOrElse("OutputTag.withFilter", Value.UnitV),
      ))
    }
    // v1.63.6 — RemoteStreamPolicy / SseOverflowPolicy constant objects
    interp.globals("RemoteStreamPolicy") = Value.InstanceV("RemoteStreamPolicy", Map(
      "Default"   -> Value.InstanceV("RemoteStreamPolicy.Default", Map.empty),
      "WsOnly"    -> Value.InstanceV("RemoteStreamPolicy.WsOnly", Map.empty),
      "InProcess" -> Value.InstanceV("RemoteStreamPolicy.InProcess", Map.empty),
    ))
    interp.globals("SseOverflowPolicy") = Value.InstanceV("SseOverflowPolicy", Map(
      "DropOldest" -> Value.InstanceV("SseOverflowPolicy.DropOldest", Map.empty),
      "DropNewest" -> Value.InstanceV("SseOverflowPolicy.DropNewest", Map.empty),
      "Fail"       -> Value.InstanceV("SseOverflowPolicy.Fail", Map.empty),
      "Block"      -> Value.InstanceV("SseOverflowPolicy.Block", Map.empty),
    ))
    interp.globals("RoutingPolicy") = Value.InstanceV("RoutingPolicy", Map(
      "RoundRobin"   -> Value.InstanceV("RoutingPolicy.RoundRobin", Map.empty),
      "LeastMailbox" -> Value.InstanceV("RoutingPolicy.LeastMailbox", Map.empty),
      "Random"       -> Value.InstanceV("RoutingPolicy.Random", Map.empty),
      "Broadcast"    -> Value.InstanceV("RoutingPolicy.Broadcast", Map.empty),
    ))
    // graphql-plugin — GraphQL companion object
    interp.globals.get("GraphQL.schema").foreach { schemaFn =>
      interp.globals("GraphQL") = Value.InstanceV("GraphQL", Map(
        "schema"    -> schemaFn,
        "resolvers" -> interp.globals.getOrElse("GraphQL.resolvers", Value.UnitV),
      ))
    }

  /** Invoke an interpreter Value (closure or native fn) from outside —
   *  used by WebServer to call route handlers in response to HTTP requests. */
