package ssc

/** Portable explicit algebraic-effect runtime.
 *
 * User effects are ordinary reusable-closure values: Pure(v) or
 * Op(label, argument, continuation). This object owns the target contract used
 * by the VM and generated backends. V2EffectContext is deliberately separate:
 * it adapts legacy JVM BlockForm plugins and is not compiled-target semantics.
 */
object PortableEffects:
  import Value.*

  val primitiveNames: Set[String] = Set(
    "effect.pure", "effect.perform", "effect.handle",
  )

  private def fail(message: String): Nothing =
    throw new RuntimeException(s"effect: $message")

  def pure(value: Value): Value = DataV("Pure", Vector(value))

  def packArgs(args: List[Value]): Value = args match
    case Nil       => UnitV
    case List(one) => one
    case many      => DataV("__EffArgs__", many.toVector)

  def perform(label: String, args: List[Value]): Value =
    val identity = ClosV(Runtime.emptyEnv, 1, env => Done(env.last))
    DataV("Op", Vector(StrV(label), packArgs(args), identity))

  private def call1(value: Value, argument: Value, label: String): Value = value match
    case closure: ClosV => Prims.runClos1(closure, argument)
    case other => fail(s"$label must be a one-argument closure, got ${Show.show(other)}")

  private def operationName(label: String): String =
    val dot = label.lastIndexOf('.')
    if dot < 0 then label else label.substring(dot + 1)

  /** Fold an explicit computation through a user handler. `resume` is a plain
   * reusable closure, so invoking it more than once preserves multi-shot
   * semantics without cloning a host stack. */
  def handle(computation: Value, handler: Value): Value = computation match
    case DataV("Pure", IndexedSeq(value)) => handle(value, handler)
    case DataV("Op", IndexedSeq(StrV(label), argument, continuation: ClosV)) =>
      val resume = ClosV(Array[Value](continuation, handler), 1, env => {
        val k = env(0)
        val h = env(1)
        val next = call1(k, env.last, "effect continuation")
        Done(handle(next, h))
      })
      val eventArgs = argument match
        case UnitV                         => List(resume)
        case DataV("__EffArgs__", fields) => fields.toList :+ resume
        case one                           => List(one, resume)
      call1(handler, DataV(operationName(label), eventArgs.toVector), "effect handler")
    case DataV("Op", fields) => fail(s"malformed Op with ${fields.length} field(s)")
    case value =>
      try call1(handler, DataV("Return", Vector(value)), "effect handler")
      catch
        case e: RuntimeException if Option(e.getMessage).exists(_.startsWith("match: no arm for Return")) => value

  def eval(op: String, args: List[Value]): Value = op match
    case "effect.pure" => args match
      case List(value) => pure(value)
      case _ => fail(s"effect.pure expects 1 argument, got ${args.length}")
    case "effect.perform" => args match
      case StrV(label) :: operationArgs => perform(label, operationArgs)
      case _ => fail("effect.perform expects a String label followed by operation arguments")
    case "effect.handle" => args match
      case List(computation, handler) => handle(computation, handler)
      case _ => fail(s"effect.handle expects 2 arguments, got ${args.length}")
    case other => fail(s"unknown primitive '$other'")
