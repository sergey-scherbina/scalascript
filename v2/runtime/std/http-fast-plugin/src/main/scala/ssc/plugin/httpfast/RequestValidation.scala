package ssc.plugin.httpfast

import ssc.{V2PluginRegistry, Value}
import ssc.plugin.NativePluginContext

/** `validate { … }` plus the `require*` family — the error-ACCUMULATING request validators from
 *  v1's request plugin, ported to the native lane.
 *
 *  The point of the construct is that a failing `require*` does NOT abort: it records the problem
 *  and returns a safe default so the block keeps running and reports EVERY problem in one pass.
 *  `validate` then returns `Left(Map(field -> reason))` if anything was recorded, else `Right(body)`.
 *
 *  Messages and defaults are copied verbatim from `RequestIntrinsics` — `missing field: x`,
 *  `out of range [1..5] for field: x`, and the min/`""`/`allowed.head` defaults — because the corpus
 *  golden for `tests/conformance/rest-validate.ssc` comes from the interpreter. A nicer wording here
 *  is a diverging row, not an improvement.
 *
 *  ⚠️ On the interpreter `validate` is a RESERVED APPLY HEAD (`EvalRuntime.reservedApplyHeads`), a
 *  special form evaluated by the interpreter itself. On this lane the front lowers it to an ordinary
 *  call of a global whose argument is the block as a zero-arg closure, so it is an ordinary plugin
 *  surface. I had sized this task as front + runtime work on the strength of the v1 shape; running
 *  the case (`unbound global: validate`) is what corrected that.
 */
private[httpfast] object RequestValidation:

  /** One buffer per `validate` block, per thread. A stack because nothing stops a `validate` inside
   *  a `validate`; the interpreter uses a stack for the same reason. LinkedHashMap because the
   *  golden lists problems in DOCUMENT ORDER (`email` before `rating` before `tone`). */
  private val stack = ThreadLocal.withInitial[scala.collection.mutable.ListBuffer[
    scala.collection.mutable.LinkedHashMap[String, String]]](
    () => scala.collection.mutable.ListBuffer.empty)

  private def record(name: String, reason: String, fallback: Value): Value =
    val frames = stack.get()
    // Outside any `validate` block there is nothing to accumulate INTO. Recording nowhere and
    // returning the default would silently swallow the problem, so this fails loudly instead —
    // matching the interpreter, where the require* intrinsics need a frame on the validation stack.
    //
    // It throws the REASON and nothing else, as `HandlerValidationError`, because the message ends
    // up in a 400 body read by whoever sent the request. The old text named the internal block form
    // — `n: require* used outside a validate { … } block` — and rode a plain RuntimeException, so
    // the engine could only call it a 500. `Interpreter.scala` ~1451 is the reference: outside a
    // frame it throws `RestValidationError(msg)` with the reason, and the v1 dispatch loop answers
    // 400. Same behaviour on both lanes now.
    if frames.isEmpty then
      throw new HandlerValidationError(reason)
    frames.last(name) = reason
    fallback

  private def str(v: Value): String = v match
    case Value.StrV(s) => s
    case other         => ssc.Show.show(other)

  /** `req.form[name]` else `req.query[name]`, by FIELD NAME. v2 field access is positional, so the
   *  names come from the registry the same way `SqlNativePlugin` resolves a row tag's columns —
   *  the receiver here is a user case class (`FakeReq(form, query)`), not a built-in Request. */
  private def fieldOf(req: Value, name: String): Option[String] =
    def mapAt(field: String): Option[String] = req match
      case Value.DataV(tag, fields) =>
        V2PluginRegistry.lookupFieldNames(tag, fields.length).flatMap { names =>
          names.indexOf(field) match
            case -1 => None
            case i  => fields(i) match
              case m: Value.MapV =>
                m.entries.collectFirst { case (Value.StrV(k), v) if k == name => str(v) }
              case _ => None
        }
      case _ => None
    mapAt("form").orElse(mapAt("query"))

  private def num(v: Value): Double = v match
    case Value.IntV(n)   => n.toDouble
    case Value.FloatV(d) => d
    case other           => throw new IllegalArgumentException(s"expected a number, got ${ssc.Show.show(other)}")

  def install(context: NativePluginContext, native: (String, List[Value] => Value) => Unit): Unit =
    native("validate", {
      case body :: Nil =>
        val frames = stack.get()
        frames += scala.collection.mutable.LinkedHashMap.empty[String, String]
        // try/finally: a throw inside the block must not leave the frame behind, or every later
        // `validate` on this thread would inherit its errors.
        val result =
          try context.invoke(body, Nil)
          finally ()
        val buf = frames.remove(frames.length - 1)
        if buf.nonEmpty then
          Value.DataV("Left", collection.immutable.ArraySeq(
            Value.MapV.from(buf.iterator.map((k, v) => (Value.StrV(k): Value) -> (Value.StrV(v): Value)).toList)))
        else Value.DataV("Right", collection.immutable.ArraySeq(result))
      case _ => throw new IllegalArgumentException("validate { … }")
    })

    native("requireString", {
      case req :: nameV :: Nil =>
        val name = str(nameV)
        fieldOf(req, name).map(Value.StrV.apply)
          .getOrElse(record(name, s"missing field: $name", Value.StrV("")))
      case _ => throw new IllegalArgumentException("requireString(req, name)")
    })

    // ── the seven the native lane never grew ─────────────────────────────────────────────────
    //
    // The v1 request-plugin family is ELEVEN names; this file had four, so a handler calling any of
    // the others died with `unbound global: requireInt` — which reads like a scoping problem and is
    // not one. Semantics copied from the v1 reference verbatim
    // (`v1/runtime/plugins/request-plugin/.../RequestIntrinsics.scala`), including the fallbacks: a
    // require* that fails RECORDS the reason and returns a neutral value so `validate { … }` can
    // accumulate every field's complaint in one pass instead of stopping at the first.
    //
    // The boolean spellings are the reference's, not a guess: true/1/yes/on and false/0/no/off.

    def parseBool(s: String): Option[Boolean] = s.toLowerCase match
      case "true" | "1" | "yes" | "on"   => Some(true)
      case "false" | "0" | "no" | "off"  => Some(false)
      case _                             => None

    def some(v: Value): Value = Value.DataV("Some", collection.immutable.ArraySeq(v))
    val none: Value = Value.DataV("None", collection.immutable.ArraySeq.empty)

    native("requireInt", {
      case req :: nameV :: Nil =>
        val name = str(nameV)
        fieldOf(req, name) match
          case Some(s) =>
            try Value.IntV(s.toLong)
            catch case _: NumberFormatException =>
              record(name, s"invalid integer for field: $name", Value.IntV(0L))
          case None => record(name, s"missing field: $name", Value.IntV(0L))
      case _ => throw new IllegalArgumentException("requireInt(req, name)")
    })

    native("requireDouble", {
      case req :: nameV :: Nil =>
        val name = str(nameV)
        fieldOf(req, name) match
          case Some(s) =>
            try Value.FloatV(s.toDouble)
            catch case _: NumberFormatException =>
              record(name, s"invalid number for field: $name", Value.FloatV(0.0))
          case None => record(name, s"missing field: $name", Value.FloatV(0.0))
      case _ => throw new IllegalArgumentException("requireDouble(req, name)")
    })

    native("requireBool", {
      case req :: nameV :: Nil =>
        val name = str(nameV)
        fieldOf(req, name) match
          case Some(s) => parseBool(s) match
            case Some(b) => Value.BoolV(b)
            case None    => record(name, s"invalid boolean for field: $name", Value.BoolV(false))
          case None => record(name, s"missing field: $name", Value.BoolV(false))
      case _ => throw new IllegalArgumentException("requireBool(req, name)")
    })

    // optional*: a missing OR unparseable field is None, never a recorded error — that is the whole
    // difference from require*, and getting it wrong would make `validate` reject valid requests.
    native("optionalString", {
      case req :: nameV :: Nil => fieldOf(req, str(nameV)).map(s => some(Value.StrV(s))).getOrElse(none)
      case _ => throw new IllegalArgumentException("optionalString(req, name)")
    })

    native("optionalInt", {
      case req :: nameV :: Nil =>
        fieldOf(req, str(nameV)).flatMap { s =>
          try Some(some(Value.IntV(s.toLong))) catch case _: NumberFormatException => None
        }.getOrElse(none)
      case _ => throw new IllegalArgumentException("optionalInt(req, name)")
    })

    native("optionalDouble", {
      case req :: nameV :: Nil =>
        fieldOf(req, str(nameV)).flatMap { s =>
          try Some(some(Value.FloatV(s.toDouble))) catch case _: NumberFormatException => None
        }.getOrElse(none)
      case _ => throw new IllegalArgumentException("optionalDouble(req, name)")
    })

    native("optionalBool", {
      case req :: nameV :: Nil =>
        fieldOf(req, str(nameV)).flatMap(parseBool).map(b => some(Value.BoolV(b))).getOrElse(none)
      case _ => throw new IllegalArgumentException("optionalBool(req, name)")
    })

    native("requireRange", {
      case req :: nameV :: minV :: maxV :: Nil =>
        val name = str(nameV)
        val min = num(minV).toLong
        val max = num(maxV).toLong
        fieldOf(req, name) match
          case Some(s) =>
            try
              val n = s.toLong
              if n < min || n > max then
                record(name, s"out of range [$min..$max] for field: $name", Value.IntV(min))
              else Value.IntV(n)
            catch case _: NumberFormatException =>
              record(name, s"invalid integer for field: $name", Value.IntV(min))
          case None => record(name, s"missing field: $name", Value.IntV(min))
      case _ => throw new IllegalArgumentException("requireRange(req, name, min, max)")
    })

    native("requireRangeDouble", {
      case req :: nameV :: minV :: maxV :: Nil =>
        val name = str(nameV)
        val min = num(minV)
        val max = num(maxV)
        fieldOf(req, name) match
          case Some(s) =>
            try
              val n = s.toDouble
              if n < min || n > max then
                record(name, s"out of range [$min..$max] for field: $name", Value.FloatV(min))
              else Value.FloatV(n)
            catch case _: NumberFormatException =>
              record(name, s"invalid number for field: $name", Value.FloatV(min))
          case None => record(name, s"missing field: $name", Value.FloatV(min))
      case _ => throw new IllegalArgumentException("requireRangeDouble(req, name, min, max)")
    })

    native("requireOneOf", {
      case req :: nameV :: optsV :: Nil =>
        val name = str(nameV)
        def toList(v: Value, acc: List[String]): List[String] = v match
          case Value.DataV("Cons", fs) if fs.length == 2 => toList(fs(1), str(fs(0)) :: acc)
          case _                                         => acc.reverse
        val allowed = toList(optsV, Nil)
        val fallback = Value.StrV(allowed.headOption.getOrElse(""))
        fieldOf(req, name) match
          case Some(s) if allowed.contains(s) => Value.StrV(s)
          case Some(s) =>
            record(name,
              s"invalid value '$s' for field: $name (expected one of: ${allowed.mkString(", ")})",
              fallback)
          case None => record(name, s"missing field: $name", fallback)
      case _ => throw new IllegalArgumentException("requireOneOf(req, name, options)")
    })
