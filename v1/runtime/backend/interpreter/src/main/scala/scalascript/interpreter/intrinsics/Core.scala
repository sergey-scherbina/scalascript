package scalascript.interpreter

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName

/** Core language intrinsics for the tree-walking interpreter (Stage 5+/E).
 *
 *  Migrated from hardcoded `nativeP` calls in `Interpreter.initBuiltins`. */
val CoreIntrinsics: Map[QualifiedName, IntrinsicImpl] = Map(

  QualifiedName("assert") -> NativeImpl((_, args) =>
    args match
      case List(true)        => ()
      case List(false)       => throw InterpretError("Assertion failed")
      case List(true, _)     => ()
      case List(false, msg)  => throw InterpretError(s"Assertion failed: ${anyToString(msg)}")
      case _                 => ()
  ),

  QualifiedName("require") -> NativeImpl((_, args) =>
    args match
      case List(true)        => ()
      case List(false, msg)  => throw InterpretError(s"Requirement failed: ${anyToString(msg)}")
      case _                 => ()
  ),

  QualifiedName("nanoTime") -> NativeImpl((_, _) =>
    java.lang.System.nanoTime()
  ),

  QualifiedName("getenv") -> NativeImpl((_, args) =>
    args match
      case List(k: String) =>
        Option(java.lang.System.getenv(k)).getOrElse("")
      case List(k: String, d: String) =>
        val v = java.lang.System.getenv(k)
        if v == null || v.isEmpty then d else v
      case _ => throw InterpretError("getenv(key[, default])")
  ),

  QualifiedName("doc") -> NativeImpl((_, args) =>
    Value.DocV(args.map(coreAnyToValue))
  ),

  QualifiedName("render") -> NativeImpl((ctx, args) =>
    val text = args match
      case List(Value.DocV(parts)) => parts.map(Value.show).mkString("\n")
      case List(a)                 => anyToString(a)
      case as                      => as.map(anyToString).mkString("\n")
    ctx.out.println(text)
  ),

  QualifiedName("Some") -> NativeImpl((_, args) =>
    args match
      case List(v) => Value.OptionV(coreAnyToValue(v))
      case _       => throw InterpretError("Some takes 1 arg")
  ),

  QualifiedName("Left") -> NativeImpl((_, args) =>
    args match
      case List(v) => Value.singleValue("Left", coreAnyToValue(v))
      case _       => throw InterpretError("Left takes 1 arg")
  ),

  QualifiedName("Right") -> NativeImpl((_, args) =>
    args match
      case List(v) => Value.singleValue("Right", coreAnyToValue(v))
      case _       => throw InterpretError("Right takes 1 arg")
  ),

  QualifiedName("List") -> NativeImpl((_, args) =>
    Value.ListV(args.map(coreAnyToValue))
  ),

  QualifiedName("Map") -> NativeImpl((_, args) =>
    val entries = args.collect { case Value.TupleV(List(k, v)) => k -> v }.toMap
    Value.MapV(entries)
  ),

  QualifiedName("math.sqrt") -> NativeImpl((_, args) =>
    args match
      case List(d: Double) => math.sqrt(d)
      case List(n: Long)   => math.sqrt(n.toDouble)
      case _               => ()
  ),

  QualifiedName("math.abs") -> NativeImpl((_, args) =>
    args match
      case List(d: Double) => math.abs(d)
      case List(n: Long)   => math.abs(n)
      case _               => ()
  ),

  QualifiedName("math.pow") -> NativeImpl((_, args) =>
    args match
      case List(a: Double, b: Double) => math.pow(a, b)
      case List(a: Long,   b: Double) => math.pow(a.toDouble, b)
      case List(a: Double, b: Long)   => math.pow(a, b.toDouble)
      case List(a: Long,   b: Long)   => math.pow(a.toDouble, b.toDouble)
      case _                          => ()
  ),

  QualifiedName("math.max") -> NativeImpl((_, args) =>
    args match
      case List(a: Long,   b: Long)   => math.max(a, b)
      case List(a: Double, b: Double) => math.max(a, b)
      case List(a: Long,   b: Double) => math.max(a.toDouble, b)
      case List(a: Double, b: Long)   => math.max(a, b.toDouble)
      case _                          => ()
  ),

  QualifiedName("math.min") -> NativeImpl((_, args) =>
    args match
      case List(a: Long,   b: Long)   => math.min(a, b)
      case List(a: Double, b: Double) => math.min(a, b)
      case List(a: Long,   b: Double) => math.min(a.toDouble, b)
      case List(a: Double, b: Long)   => math.min(a, b.toDouble)
      case _                          => ()
  ),

  QualifiedName("math.floor") -> NativeImpl((_, args) =>
    args match
      case List(d: Double) => math.floor(d)
      case _               => ()
  ),

  QualifiedName("math.ceil") -> NativeImpl((_, args) =>
    args match
      case List(d: Double) => math.ceil(d)
      case _               => ()
  ),

  QualifiedName("math.round") -> NativeImpl((_, args) =>
    args match
      case List(d: Double) => math.round(d)  // Long; wrapAnyAsValue converts to IntV
      case _               => ()
  ),

  QualifiedName("escape") -> NativeImpl((_, args) =>
    args match
      case List(s: String) => coreHtmlEscape(s)
      case List(v)         => coreHtmlEscape(Value.show(coreAnyToValue(v)))
      case _               => throw InterpretError("escape(s)")
  ),

  // ── Component / HTML-DSL helpers (Stage 5+/F) ─────────────────────────

  QualifiedName("collectCss") -> NativeImpl((_, args) =>
    val parts = args.flatMap {
      case Value.InstanceV(_, fields) =>
        fields.get("css").collect { case Value.StringV(s) => s }
      case _ => None
    }
    parts.mkString("\n")
  ),

  QualifiedName("collectJs") -> NativeImpl((_, args) =>
    val parts = args.flatMap {
      case Value.InstanceV(_, fields) =>
        fields.get("js").collect { case Value.StringV(s) => s }
      case _ => None
    }
    parts.mkString("\n")
  ),

  QualifiedName("scope") -> NativeImpl((_, args) =>
    args match
      case List(scopeName: String) =>
        def rewrite(css: String): String =
          val pat = """\.([A-Za-z_][A-Za-z0-9_-]*)""".r
          pat.replaceAllIn(css, m =>
            java.util.regex.Matcher.quoteReplacement(s".${m.group(1)}__$scopeName"))
        Value.InstanceV("Scope", Map(
          "name" -> Value.StringV(scopeName),
          "css"  -> Value.NativeFnV("scope.css", Computation.pureFn {
            case List(Value.StringV(s)) => Value.StringV(rewrite(s))
            case _                       => throw InterpretError("scope.css(s)")
          }),
          "cls"  -> Value.NativeFnV("scope.cls", Computation.pureFn {
            case List(Value.StringV(n)) => Value.StringV(s"${n}__$scopeName")
            case _                       => throw InterpretError("scope.cls(name)")
          })
        ))
      case _ => throw InterpretError("scope(name: String)")
  ),

)

// ── Private helpers ─────────────────────────────────────────────────────────

private def coreHtmlEscape(s: String): String =
  val sb = StringBuilder()
  s.foreach {
    case '&'  => sb ++= "&amp;"
    case '<'  => sb ++= "&lt;"
    case '>'  => sb ++= "&gt;"
    case '"'  => sb ++= "&quot;"
    case '\'' => sb ++= "&#39;"
    case c    => sb += c
  }
  sb.toString

private def coreAnyToValue(a: Any): Value = a match
  case n: Long    => Value.intV(n)
  case i: Int     => Value.intV(i.toLong)
  case d: Double  => Value.doubleV(d)
  case s: String  => Value.StringV(s)
  case b: Boolean => Value.boolV(b)
  case ()         => Value.UnitV
  case v: Value   => v
  case other      => Value.StringV(other.toString)

private def anyToString(a: Any): String = a match
  case d: Double if d == d.toLong.toDouble && !d.isInfinite => d.toLong.toString
  case d: Double  => d.toString
  case s: String  => s
  case ()         => "()"
  case v: Value   => Value.show(v)
  case other      => other.toString
