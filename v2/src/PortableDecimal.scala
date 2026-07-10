package ssc

import java.math.{BigDecimal as JBigDecimal, RoundingMode as JRoundingMode}

/** Target-neutral Decimal runtime contract.
 *
 * CoreIR carries Decimal construction and operations through `dec.*` primitives;
 * the JVM runtime uses java.math.BigDecimal only behind this boundary. The value
 * exposed to programs is [[Value.DecimalV]], whose sole payload is canonical
 * scale-preserving text. Other targets implement the same operations with their
 * native exact-decimal library without changing CoreIR.
 */
object PortableDecimal:
  import Value.*

  val primitiveNames: Set[String] = Set(
    "dec.parse", "dec.from-unscaled",
    "dec.add", "dec.sub", "dec.mul", "dec.div", "dec.rem", "dec.compare",
    "dec.set-scale", "dec.pow", "dec.abs", "dec.negate", "dec.signum",
    "dec.scale", "dec.unscaled", "dec.to-bigint", "dec.to-string",
  )

  private val roundingNames: Set[String] = Set(
    "UP", "DOWN", "CEILING", "FLOOR",
    "HALF_UP", "HALF_DOWN", "HALF_EVEN", "UNNECESSARY",
  )

  private def fail(message: String): Nothing =
    throw new RuntimeException(s"decimal: $message")

  def canonicalText(raw: String): String =
    val input = raw.trim
    if input.isEmpty then fail("invalid text ''")
    try new JBigDecimal(input).toPlainString
    catch case _: NumberFormatException => fail(s"invalid text '$raw'")

  private[ssc] def numericEquals(left: String, right: String): Boolean =
    toJavaText(left).compareTo(toJavaText(right)) == 0

  private[ssc] def numericHash(text: String): Int =
    toJavaText(text).stripTrailingZeros().hashCode()

  private def toJavaText(text: String): JBigDecimal = new JBigDecimal(text)

  private[ssc] def toJava(value: Value): JBigDecimal = value match
    case DecimalV(text) => toJavaText(text)
    case IntV(n)        => JBigDecimal.valueOf(n)
    case BigV(n)        => new JBigDecimal(n.bigInteger)
    case FloatV(_)      => fail("binary floating-point input is inexact")
    case other          => fail(s"expected Decimal-compatible value, got ${Show.show(other)}")

  private[ssc] def fromJava(value: JBigDecimal): DecimalV =
    DecimalV(value.toPlainString)

  def construct(args: List[Value]): DecimalV = args match
    case List(StrV(text)) => DecimalV(text)
    case List(v @ (IntV(_) | BigV(_) | DecimalV(_))) => fromJava(toJava(v))
    case List(FloatV(_)) => fail("binary floating-point input is inexact")
    case List(unscaled, IntV(scale)) =>
      val n = unscaled match
        case IntV(value) => BigInt(value)
        case BigV(value) => value
        case other       => fail(s"unscaled value must be Int or BigInt, got ${Show.show(other)}")
      if scale < Int.MinValue || scale > Int.MaxValue then fail(s"scale out of range: $scale")
      fromJava(new JBigDecimal(n.bigInteger, scale.toInt))
    case _ => fail(s"constructor expects (String|Int|BigInt|Decimal) or (unscaled, scale), got ${args.length} argument(s)")

  def roundingName(value: Value): String =
    val name = value match
      case StrV(s) => s
      case DataV("RoundingMode", IndexedSeq(StrV(s))) => s
      case DataV(s, _) => s
      case ForeignV(rm: JRoundingMode) => rm.name() // transitional bridge input only
      case other => fail(s"unsupported rounding mode ${Show.show(other)}")
    if roundingNames.contains(name) then name
    else fail(s"unsupported rounding mode '$name'")

  private def rounding(value: Value): JRoundingMode =
    JRoundingMode.valueOf(roundingName(value))

  private def intArg(value: Value, label: String): Int = value match
    case IntV(n) if n >= Int.MinValue && n <= Int.MaxValue => n.toInt
    case IntV(n) => fail(s"$label out of range: $n")
    case other   => fail(s"$label must be Int, got ${Show.show(other)}")

  private def checkedDivide(
      left: JBigDecimal,
      right: JBigDecimal,
      scale: Int,
      mode: JRoundingMode,
  ): JBigDecimal =
    if right.compareTo(JBigDecimal.ZERO) == 0 then fail("division by zero")
    try left.divide(right, scale, mode)
    catch case _: ArithmeticException => fail(s"division requires rounding mode ${mode.name()}")

  def eval(op: String, args: List[Value]): Value =
    def unary: JBigDecimal = args match
      case List(value) => toJava(value)
      case _ => fail(s"$op expects 1 argument, got ${args.length}")
    def binary: (JBigDecimal, JBigDecimal) = args match
      case List(left, right) => toJava(left) -> toJava(right)
      case _ => fail(s"$op expects 2 arguments, got ${args.length}")

    op match
      case "dec.parse" => args match
        case List(StrV(text)) => DecimalV(text)
        case List(other) => fail(s"dec.parse expects String, got ${Show.show(other)}")
        case _ => fail(s"dec.parse expects 1 argument, got ${args.length}")
      case "dec.from-unscaled" => args match
        case List(unscaled, scale) => construct(List(unscaled, scale))
        case _ => fail(s"dec.from-unscaled expects 2 arguments, got ${args.length}")
      case "dec.add" => val (l, r) = binary; fromJava(l.add(r))
      case "dec.sub" => val (l, r) = binary; fromJava(l.subtract(r))
      case "dec.mul" => val (l, r) = binary; fromJava(l.multiply(r))
      case "dec.rem" =>
        val (l, r) = binary
        if r.compareTo(JBigDecimal.ZERO) == 0 then fail("division by zero")
        fromJava(l.remainder(r))
      case "dec.div" => args match
        case List(left, right, scale, mode) =>
          fromJava(checkedDivide(toJava(left), toJava(right), intArg(scale, "scale"), rounding(mode)))
        case _ => fail(s"dec.div expects 4 arguments, got ${args.length}")
      case "dec.compare" =>
        val (l, r) = binary
        IntV(Integer.signum(l.compareTo(r)).toLong)
      case "dec.set-scale" => args match
        case List(value, scale, mode) =>
          try fromJava(toJava(value).setScale(intArg(scale, "scale"), rounding(mode)))
          catch case _: ArithmeticException => fail(s"set-scale requires rounding mode ${roundingName(mode)}")
        case _ => fail(s"dec.set-scale expects 3 arguments, got ${args.length}")
      case "dec.pow" => args match
        case List(value, exponent) =>
          val e = intArg(exponent, "exponent")
          if e < 0 then fail(s"negative exponent: $e")
          fromJava(toJava(value).pow(e))
        case _ => fail(s"dec.pow expects 2 arguments, got ${args.length}")
      case "dec.abs"     => fromJava(unary.abs())
      case "dec.negate"  => fromJava(unary.negate())
      case "dec.signum"  => IntV(unary.signum().toLong)
      case "dec.scale"   => IntV(unary.scale().toLong)
      case "dec.unscaled" => BigV(BigInt(unary.unscaledValue()))
      case "dec.to-bigint" => BigV(BigInt(unary.toBigInteger))
      case "dec.to-string" => StrV(unary.toPlainString)
      case other => fail(s"unknown primitive '$other'")

  def involvesDecimal(left: Value, right: Value): Boolean =
    left.isInstanceOf[DecimalV] || right.isInstanceOf[DecimalV]

  /** Dynamic `__arith__` compatibility delegates here so generated targets and
   * explicit dec.* terms cannot drift semantically. */
  def arith(op: String, left: Value, right: Value): Value =
    if left.isInstanceOf[FloatV] || right.isInstanceOf[FloatV] then
      fail("Decimal and Double cannot be mixed")
    val l = toJava(left)
    val r = toJava(right)
    op match
      case "+" => fromJava(l.add(r))
      case "-" => fromJava(l.subtract(r))
      case "*" => fromJava(l.multiply(r))
      case "/" => fromJava(checkedDivide(l, r, math.max(math.max(l.scale(), r.scale()), 10), JRoundingMode.HALF_UP))
      case "%" =>
        if r.compareTo(JBigDecimal.ZERO) == 0 then fail("division by zero")
        fromJava(l.remainder(r))
      case "==" => BoolV(l.compareTo(r) == 0)
      case "!=" => BoolV(l.compareTo(r) != 0)
      case "<"  => BoolV(l.compareTo(r) < 0)
      case "<=" => BoolV(l.compareTo(r) <= 0)
      case ">"  => BoolV(l.compareTo(r) > 0)
      case ">=" => BoolV(l.compareTo(r) >= 0)
      case "++" => StrV(l.toPlainString + r.toPlainString)
      case other => fail(s"operator '$other' is not valid for Decimal")
