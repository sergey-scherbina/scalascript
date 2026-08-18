package ssc3.plugins

import ssc3.{Module, Plugins, Value}

/** v3 VALUES ACROSS THE JVM BOUNDARY — the half of `JvmInterop` that carries data rather than names.
  *
  * THE RULE IS THE FLEET'S RULE, and it is the whole design: a shape this cannot convert is REFUSED
  * BY NAME. Smuggling one through as an opaque handle would turn an honest positioned refusal into a
  * failure further down, which is the trade this repository has paid for three times this week —
  * once when the fleet was wired, once when ui-plugin was, and once when a host datum was handed to
  * v2 to print.
  *
  * WHAT COMES BACK MATTERS AS MUCH AS WHAT GOES IN. A Scala case class becomes
  * `VHostData(simpleName, fields)`, which is what makes `case Right(bytes)` match through machinery
  * that already exists — v3 numbers its own constructors and names a host's, and that distinction
  * was built for exactly this. */
object JvmBridge:

  /** Call an object's `apply`, chosen by ARITY. Overloads on arity alone are what a constructor
    * call needs; an overload set that differs only by TYPE is declined rather than guessed at,
    * because picking one by the first argument's runtime class is how a plausible wrong answer gets
    * made. */
  def callApply(m: Module, module: AnyRef, args: List[Value]): Value =
    val jargs = args.map(a => toJvm(m, a))
    val candidates = module.getClass.getMethods.filter(x =>
      x.getName == "apply" && x.getParameterCount == args.length)
    candidates.toList match
      case one :: Nil =>
        // COERCED TO THE DECLARED PARAMETER TYPE, not to the nearest boxed one. v3 has a single
        // integer and Scala has `Int` and `Long` as different types, so passing a `java.lang.Long`
        // to `DatasetWirePartition(partitionId: Int, …)` is an `argument type mismatch` — the method
        // signature is the only thing that says which was meant, and it is right here.
        val fitted = one.getParameterTypes.toList.zip(jargs).map(coerce)
        try fromJvm(m, one.invoke(module, fitted*))
        catch case e: Throwable => throw ssc3.ExecError(
          "a JVM call failed: " + Option(e.getCause).getOrElse(e).toString)
      case Nil => throw ssc3.ExecError(
        "no `apply` of " + args.length + " argument(s) on " + module.getClass.getName)
      case _ => throw ssc3.ExecError(
        "more than one `apply` of " + args.length + " argument(s) on " +
        module.getClass.getName + " — this bridge does not choose between overloads")

  /** One argument, fitted to the parameter it is passed to. Narrowing an integer is checked rather
    * than assumed: a value that does not fit the declared type is refused by name, because a
    * silently truncated index is the kind of wrong answer that reads as a working program. */
  private def coerce(pair: (Class[?], AnyRef)): AnyRef =
    val (want, got) = pair
    (want.getName, got) match
      case (("int" | "java.lang.Integer"), l: java.lang.Long) =>
        if l.longValue < Int.MinValue || l.longValue > Int.MaxValue then throw ssc3.ExecError(
          "a JVM call wanted an Int and was given " + l + ", which does not fit")
        java.lang.Integer.valueOf(l.intValue)
      case (("short" | "java.lang.Short"), l: java.lang.Long)  => java.lang.Short.valueOf(l.shortValue)
      case (("byte" | "java.lang.Byte"), l: java.lang.Long)    => java.lang.Byte.valueOf(l.byteValue)
      case (("float" | "java.lang.Float"), d: java.lang.Double) => java.lang.Float.valueOf(d.floatValue)
      case (("double" | "java.lang.Double"), l: java.lang.Long) => java.lang.Double.valueOf(l.doubleValue)
      case _ => got

  def toJvm(m: Module, v: Value): AnyRef = v match
    case Value.VInt(n)     => java.lang.Long.valueOf(n)
    case Value.VStr(s)     => s
    case Value.VBool(b)    => java.lang.Boolean.valueOf(b)
    case Value.VFloat(d)   => java.lang.Double.valueOf(d)
    case Value.VBytes(b)   => b
    case Value.VForeign(h, _) => h
    case Value.VUnit       => scala.runtime.BoxedUnit.UNIT
    // A SEQUENCE, as `Vector` — the shape Scala signatures in this repository ask for
    // (`DatasetWirePartition(partitionId: Int, values: Vector[JsonValue])`). An array and a `List`
    // are the same thing on this side of the boundary: an ordered run of values.
    case Value.VArr(xs)    => xs.toVector.map(x => toJvm(m, x))
    case d @ Value.VData(_, _) if listOf(m, d).isDefined =>
      listOf(m, d).get.map(x => toJvm(m, x)).toVector
    case other => throw ssc3.ExecError(
      "a JVM call was passed " + other.getClass.getSimpleName +
      ", which this interop does not convert yet")

  /** A v3 LIST as a Scala one, or `None` if this datum is not a list. `Cons`/`Nil` are ordinary
    * constructors in the module's type table, so the walk is by TAG NAME — the same way the fleet's
    * converter reads one, and the reason a list crosses at all. */
  private def listOf(m: Module, v: Value): Option[List[Value]] =
    def go(x: Value, acc: List[Value]): Option[List[Value]] = x match
      case Value.VData(t, fs) if t >= 0 && t < m.types.length =>
        m.types(t).name match
          case "Nil"  => Some(acc.reverse)
          case "Cons" if fs.length == 2 => go(fs(1), fs(0) :: acc)
          case _      => None
      case _ => None
    go(v, Nil)

  def fromJvm(m: Module, o: Any): Value = o match
    case null                    => Value.VUnit
    case s: String               => Value.VStr(s)
    case i: java.lang.Integer    => Value.VInt(i.longValue)
    case l: java.lang.Long       => Value.VInt(l.longValue)
    case b: java.lang.Boolean    => Value.VBool(b.booleanValue)
    case d: java.lang.Double     => Value.VFloat(d.doubleValue)
    case b: Array[Byte]          => Value.VBytes(b)
    case _: scala.runtime.BoxedUnit => Value.VUnit
    // A SCALA CASE CLASS BY NAME, so `case Right(bytes)` matches: `Product` is what every case class
    // is, and its element list is the field list in declaration order.
    // A SEQUENCE COMING BACK is an array here, which is what v3's own indexed values are.
    case xs: scala.collection.immutable.Seq[?] =>
      Value.VArr(xs.iterator.map(x => fromJvm(m, x)).toArray)
    case p: Product              =>
      // THE FIELD NAMES ARE REMEMBERED HERE and nowhere else. `VHostData` carries a tag and values
      // because that is all v3 needs to PRINT and MATCH one; reading `.partitionId` needs the name,
      // and `Product` is the only place that has it. Recorded per tag as the value crosses, which is
      // the one moment both halves are in hand.
      remember(p.productPrefix, p.productElementNames.toList)
      Value.VHostData(p.productPrefix, p.productIterator.map(x => fromJvm(m, x)).toArray)
    case other: AnyRef           => Value.VForeign(other, other.getClass.getSimpleName)

  private val fields = collection.mutable.HashMap.empty[String, List[String]]

  private def remember(tag: String, names: List[String]): Unit =
    if names.nonEmpty && !fields.contains(tag) then fields(tag) = names

  /** `x.partitionId` on a value that came from the JVM. Reads a FIELD only — a method on such a
    * value is a different question and is declined here rather than guessed at, so v3's own refusal
    * stands and names the lane that can run it. */
  def fieldOn(recv: Value, name: String): Option[Value] = recv match
    case Value.VHostData(tag, fs) =>
      fields.get(tag).map(_.indexOf(name)).filter(i => i >= 0 && i < fs.length).map(i => fs(i))
    case _ => None
