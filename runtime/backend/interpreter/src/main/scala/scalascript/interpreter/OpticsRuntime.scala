package scalascript.interpreter

import scala.meta.*
import Computation.{Pure, FlatMap}

/** Optic builders and traversers for Lens / Optional / Traversal / Prism.
 *
 *  All state lives in the returned Value.InstanceV closures; the only
 *  interpreter access needed is `interp.callValue` for `modify` operations.
 */
private[interpreter] object OpticsRuntime:

  // ── PathStep ─────────────────────────────────────────────────────────

  enum PathStep:
    case FieldStep(name: String)
    case SomeStep
    case EachStep
    case IndexStep(i: Int)
    case AtKey(key: Value)

  // ── Path extraction ──────────────────────────────────────────────────

  def extractPathSteps(body: Term, isBase: Term => Boolean): Option[List[PathStep]] =
    def litToValue(lit: Lit): Option[Value] = lit match
      case Lit.Int(v)     => Some(Value.intV(v.toLong))
      case Lit.Long(v)    => Some(Value.intV(v))
      case Lit.String(v)  => Some(Value.StringV(v))
      case Lit.Boolean(v) => Some(Value.boolV(v))
      case Lit.Double(v)  => Some(Value.doubleV(v.toDouble))
      case _              => None
    def loop(t: Term, acc: List[PathStep]): Option[List[PathStep]] = t match
      case Term.Select(qual, Term.Name("some")) =>
        loop(qual, PathStep.SomeStep :: acc)
      case Term.Select(qual, Term.Name("each")) =>
        loop(qual, PathStep.EachStep :: acc)
      case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name("index")), argClause)
          if argClause.values.size == 1 =>
        argClause.values.head match
          case Lit.Int(i)  => loop(qual, PathStep.IndexStep(i) :: acc)
          case Lit.Long(i) => loop(qual, PathStep.IndexStep(i.toInt) :: acc)
          case _           => None
      case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name("at")), argClause)
          if argClause.values.size == 1 =>
        argClause.values.head match
          case lit: Lit => litToValue(lit).flatMap(v => loop(qual, PathStep.AtKey(v) :: acc))
          case _        => None
      case Term.Select(qual, name) =>
        loop(qual, PathStep.FieldStep(name.value) :: acc)
      case other if isBase(other) => Some(acc)
      case _                      => None
    loop(body, Nil)

  // ── Lens ─────────────────────────────────────────────────────────────

  def lensGet(target: Value, path: List[String]): Value = path match
    case Nil          => target
    case head :: rest => target match
      case Value.InstanceV(_, fields) =>
        fields.get(head) match
          case Some(v) => lensGet(v, rest)
          case None    => throw InterpretError(s"Lens.get: no field '$head' on ${Value.show(target)}")
      case _ => throw InterpretError(s"Lens.get: not an instance at '$head'")

  def lensSet(target: Value, path: List[String], newVal: Value): Value = path match
    case Nil          => newVal
    case head :: rest => target match
      case Value.InstanceV(typeName, fields) =>
        val child = fields.getOrElse(head, throw InterpretError(s"Lens.set: no field '$head'"))
        Value.InstanceV(typeName, fields.updated(head, lensSet(child, rest, newVal)))
      case _ => throw InterpretError(s"Lens.set: not an instance at '$head'")

  def buildPathLens(path: List[String], interp: Interpreter): Value.InstanceV =
    val getFn = Value.NativeFnV("Lens.get", {
      case List(s) => Pure(lensGet(s, path))
      case _       => throw InterpretError("Lens.get(s)")
    })
    val setFn = Value.NativeFnV("Lens.set", {
      case List(s, v) => Pure(lensSet(s, path, v))
      case _          => throw InterpretError("Lens.set(s, v)")
    })
    val modifyFn = Value.NativeFnV("Lens.modify", {
      case List(s, f) =>
        val old = lensGet(s, path)
        interp.callValue1(f, old, Map.empty).map(newV => lensSet(s, path, newV))
      case _ => throw InterpretError("Lens.modify(s, f)")
    })
    val andThenFn = Value.NativeFnV("Lens.andThen", {
      case List(Value.InstanceV("Lens", other)) =>
        other.get("_path") match
          case Some(Value.ListV(items)) =>
            val otherPath = items.collect { case Value.StringV(s) => s }
            Pure(buildPathLens(path ++ otherPath, interp))
          case _ =>
            Pure(composedLens(buildPathLens(path, interp), Value.InstanceV("Lens", other), interp))
      case List(Value.InstanceV("Optional", other)) =>
        stepsFromFields(other) match
          case Some(innerSteps) =>
            val outerSteps = path.map(PathStep.FieldStep(_))
            Pure(buildPathOptional(outerSteps ++ innerSteps, interp))
          case None => throw InterpretError("Lens.andThen(Optional): malformed Optional")
      case List(Value.InstanceV("Traversal", other)) =>
        stepsFromFields(other) match
          case Some(innerSteps) =>
            val outerSteps = path.map(PathStep.FieldStep(_))
            Pure(buildPathTraversal(outerSteps ++ innerSteps, interp))
          case None => throw InterpretError("Lens.andThen(Traversal): malformed Traversal")
      case List(other) =>
        throw InterpretError(s"Lens.andThen expects a Lens / Optional / Traversal, got ${Value.show(other)}")
      case _ => throw InterpretError("Lens.andThen(other)")
    })
    Value.InstanceV("Lens", Map(
      "get"     -> getFn,
      "set"     -> setFn,
      "modify"  -> modifyFn,
      "andThen" -> andThenFn,
      "_path"   -> Value.ListV(path.map(Value.StringV.apply))
    ))

  def buildPrism(variantName: String, interp: Interpreter): Value.InstanceV =
    val getOptionFn = Value.NativeFnV("Prism.getOption", {
      case List(s) => s match
        case Value.InstanceV(t, _) if t == variantName => Pure(Value.OptionV(s))
        case _                                          => Computation.PureNone
      case _ => throw InterpretError("Prism.getOption(s)")
    })
    val reverseGetFn = Value.NativeFnV("Prism.reverseGet", {
      case List(v) => Pure(v)
      case _       => throw InterpretError("Prism.reverseGet(v)")
    })
    val setFn = Value.NativeFnV("Prism.set", {
      case List(s, v) => s match
        case Value.InstanceV(t, _) if t == variantName => Pure(v)
        case _                                          => Pure(s)
      case _ => throw InterpretError("Prism.set(s, v)")
    })
    val modifyFn = Value.NativeFnV("Prism.modify", {
      case List(s, f) => s match
        case Value.InstanceV(t, _) if t == variantName => interp.callValue1(f, s, Map.empty)
        case _                                          => Pure(s)
      case _ => throw InterpretError("Prism.modify(s, f)")
    })
    val andThenFn = Value.NativeFnV("Prism.andThen", {
      case List(other: Value.InstanceV) if other.typeName == "Prism" =>
        other.fields.getOrElse("_variant", null) match
          case Value.StringV(inner) => Pure(buildPrismChain(variantName, inner, interp))
          case _ => throw InterpretError("Prism.andThen: malformed Prism")
      case List(_) =>
        throw InterpretError("Prism.andThen(other): only Prism-Prism composition supported in this stage")
      case _ => throw InterpretError("Prism.andThen(other)")
    })
    Value.InstanceV("Prism", Map(
      "getOption"  -> getOptionFn,
      "reverseGet" -> reverseGetFn,
      "set"        -> setFn,
      "modify"     -> modifyFn,
      "andThen"    -> andThenFn,
      "_variant"   -> Value.StringV(variantName)
    ))

  private def buildPrismChain(
    outerVariant: String, innerVariant: String, interp: Interpreter
  ): Value.InstanceV =
    val _ = outerVariant
    buildPrism(innerVariant, interp)

  def composedLens(a: Value.InstanceV, b: Value.InstanceV, interp: Interpreter): Value.InstanceV =
    val aGet = a.fields("get");    val bGet = b.fields("get")
    val aSet = a.fields("set");    val bSet = b.fields("set")
    val aMod = a.fields("modify"); val bMod = b.fields("modify")
    val getFn = Value.NativeFnV("Lens.get", {
      case List(s) =>
        interp.callValue1(aGet, s, Map.empty).flatMap(x => interp.callValue1(bGet, x, Map.empty))
      case _ => throw InterpretError("Lens.get(s)")
    })
    val setFn = Value.NativeFnV("Lens.set", {
      case List(s, v) =>
        interp.callValue1(aGet, s, Map.empty).flatMap { x =>
          interp.callValue2(bSet, x, v, Map.empty).flatMap { x2 =>
            interp.callValue2(aSet, s, x2, Map.empty)
          }
        }
      case _ => throw InterpretError("Lens.set(s, v)")
    })
    val modifyFn = Value.NativeFnV("Lens.modify", {
      case List(s, f) =>
        val inner = Value.NativeFnV("inner", {
          case List(x) => interp.callValue2(bMod, x, f, Map.empty)
          case _       => throw InterpretError("modify inner")
        })
        interp.callValue2(aMod, s, inner, Map.empty)
      case _ => throw InterpretError("Lens.modify(s, f)")
    })
    val andThenFn = Value.NativeFnV("Lens.andThen", {
      case List(other: Value.InstanceV) if other.typeName == "Lens" =>
        Pure(composedLens(Value.InstanceV("Lens", Map(
          "get" -> getFn, "set" -> setFn, "modify" -> modifyFn,
          "andThen" -> Value.NativeFnV("Lens.andThen.recur", _ => throw InterpretError("recur")))
        ), other, interp))
      case _ => throw InterpretError("Lens.andThen(other)")
    })
    Value.InstanceV("Lens", Map(
      "get" -> getFn, "set" -> setFn, "modify" -> modifyFn,
      "andThen" -> andThenFn
    ))

  // ── Optional ─────────────────────────────────────────────────────────

  def opticGetOption(target: Value, steps: List[PathStep]): Option[Value] = steps match
    case Nil => Some(target)
    case PathStep.FieldStep(n) :: rest => target match
      case Value.InstanceV(_, fields) => fields.get(n).flatMap(v => opticGetOption(v, rest))
      case _                          => None
    case PathStep.SomeStep :: rest => target match
      case Value.OptionV(inner) if inner != null => opticGetOption(inner, rest)
      case _                                     => None
    case PathStep.IndexStep(i) :: rest => target match
      case Value.ListV(items) if i >= 0 && i < items.length =>
        opticGetOption(items(i), rest)
      case _ => None
    case PathStep.AtKey(k) :: rest => target match
      case Value.MapV(m) => m.get(k).flatMap(v => opticGetOption(v, rest))
      case _             => None
    case PathStep.EachStep :: _ =>
      None

  def opticSet(target: Value, steps: List[PathStep], newVal: Value): Value = steps match
    case Nil => newVal
    case PathStep.FieldStep(n) :: rest => target match
      case Value.InstanceV(typeName, fields) =>
        fields.get(n) match
          case Some(child) =>
            Value.InstanceV(typeName, fields.updated(n, opticSet(child, rest, newVal)))
          case None => target
      case _ => target
    case PathStep.SomeStep :: rest => target match
      case Value.OptionV(inner) if inner != null =>
        Value.OptionV(opticSet(inner, rest, newVal))
      case other => other
    case PathStep.IndexStep(i) :: rest => target match
      case Value.ListV(items) if i >= 0 && i < items.length =>
        Value.ListV(items.updated(i, opticSet(items(i), rest, newVal)))
      case other => other
    case PathStep.AtKey(k) :: rest => target match
      case Value.MapV(m) =>
        m.get(k) match
          case Some(child) => Value.MapV(m.updated(k, opticSet(child, rest, newVal)))
          case None if rest.isEmpty => Value.MapV(m.updated(k, newVal))
          case None                  => target
      case other => other
    case PathStep.EachStep :: _ =>
      target

  def buildPathOptional(steps: List[PathStep], interp: Interpreter): Value.InstanceV =
    val getOptionFn = Value.NativeFnV("Optional.getOption", {
      case List(s) => Computation.pureOptionV(opticGetOption(s, steps))
      case _       => throw InterpretError("Optional.getOption(s)")
    })
    val setFn = Value.NativeFnV("Optional.set", {
      case List(s, v) => Pure(opticSet(s, steps, v))
      case _          => throw InterpretError("Optional.set(s, v)")
    })
    val modifyFn = Value.NativeFnV("Optional.modify", {
      case List(s, f) => opticGetOption(s, steps) match
        case Some(old) =>
          interp.callValue1(f, old, Map.empty).map(newV => opticSet(s, steps, newV))
        case None => Pure(s)
      case _ => throw InterpretError("Optional.modify(s, f)")
    })
    val andThenFn = Value.NativeFnV("Optional.andThen", {
      case List(Value.InstanceV("Traversal", other)) =>
        stepsFromFields(other) match
          case Some(rest) => Pure(buildPathTraversal(steps ++ rest, interp))
          case None       => throw InterpretError("Optional.andThen(Traversal): malformed")
      case List(Value.InstanceV(t, other)) if t == "Optional" || t == "Lens" =>
        stepsFromFields(other) match
          case Some(rest) => Pure(buildPathOptional(steps ++ rest, interp))
          case None       => throw InterpretError("Optional.andThen: cannot compose with non-path optic")
      case _ => throw InterpretError("Optional.andThen(other): only path optic supported")
    })
    val stepsValue = stepsAsListV(steps)
    Value.InstanceV("Optional", Map(
      "getOption" -> getOptionFn,
      "set"       -> setFn,
      "modify"    -> modifyFn,
      "andThen"   -> andThenFn,
      "_steps"    -> stepsValue
    ))

  // ── Traversal ────────────────────────────────────────────────────────

  def opticGetAll(target: Value, steps: List[PathStep]): List[Value] = steps match
    case Nil => List(target)
    case PathStep.FieldStep(n) :: rest => target match
      case Value.InstanceV(_, fields) =>
        fields.get(n).map(v => opticGetAll(v, rest)).getOrElse(Nil)
      case _ => Nil
    case PathStep.SomeStep :: rest => target match
      case Value.OptionV(inner) if inner != null => opticGetAll(inner, rest)
      case _                                     => Nil
    case PathStep.EachStep :: rest => target match
      case Value.ListV(items) => items.flatMap(item => opticGetAll(item, rest))
      case _                  => Nil
    case PathStep.IndexStep(i) :: rest => target match
      case Value.ListV(items) if i >= 0 && i < items.length =>
        opticGetAll(items(i), rest)
      case _ => Nil
    case PathStep.AtKey(k) :: rest => target match
      case Value.MapV(m) => m.get(k).map(v => opticGetAll(v, rest)).getOrElse(Nil)
      case _             => Nil

  def opticModifyAll(target: Value, steps: List[PathStep], f: Value, interp: Interpreter): Computation =
    steps match
    case Nil => interp.callValue1(f, target, Map.empty)
    case PathStep.FieldStep(n) :: rest => target match
      case Value.InstanceV(typeName, fields) =>
        fields.get(n) match
          case Some(child) =>
            opticModifyAll(child, rest, f, interp).map(updated =>
              Value.InstanceV(typeName, fields.updated(n, updated)))
          case None => Pure(target)
      case _ => Pure(target)
    case PathStep.SomeStep :: rest => target match
      case Value.OptionV(inner) if inner != null =>
        opticModifyAll(inner, rest, f, interp).map(updated => Value.OptionV(updated))
      case _ => Pure(target)
    case PathStep.EachStep :: rest => target match
      case Value.ListV(items) =>
        Computation.mapSequence(items, item => opticModifyAll(item, rest, f, interp)).map {
          case Value.ListV(updated) => Value.ListV(updated)
          case _                    => target
        }
      case _ => Pure(target)
    case PathStep.IndexStep(i) :: rest => target match
      case Value.ListV(items) if i >= 0 && i < items.length =>
        opticModifyAll(items(i), rest, f, interp).map(updated =>
          Value.ListV(items.updated(i, updated)))
      case _ => Pure(target)
    case PathStep.AtKey(k) :: rest => target match
      case Value.MapV(m) => m.get(k) match
        case Some(child) =>
          opticModifyAll(child, rest, f, interp).map(updated =>
            Value.MapV(m.updated(k, updated)))
        case None => Pure(target)
      case _ => Pure(target)

  def buildPathTraversal(steps: List[PathStep], interp: Interpreter): Value.InstanceV =
    val getAllFn = Value.NativeFnV("Traversal.getAll", {
      case List(s) => Pure(Value.ListV(opticGetAll(s, steps)))
      case _       => throw InterpretError("Traversal.getAll(s)")
    })
    val modifyFn = Value.NativeFnV("Traversal.modify", {
      case List(s, f) => opticModifyAll(s, steps, f, interp)
      case _          => throw InterpretError("Traversal.modify(s, f)")
    })
    val setFn = Value.NativeFnV("Traversal.set", {
      case List(s, v) =>
        val constFn = Value.NativeFnV("const", _ => Pure(v))
        opticModifyAll(s, steps, constFn, interp)
      case _ => throw InterpretError("Traversal.set(s, v)")
    })
    val andThenFn = Value.NativeFnV("Traversal.andThen", {
      case List(Value.InstanceV(t, other))
          if t == "Traversal" || t == "Optional" || t == "Lens" =>
        stepsFromFields(other) match
          case Some(rest) => Pure(buildPathTraversal(steps ++ rest, interp))
          case None       => throw InterpretError("Traversal.andThen: cannot compose")
      case _ => throw InterpretError("Traversal.andThen(other): only path optic supported")
    })
    Value.InstanceV("Traversal", Map(
      "getAll"  -> getAllFn,
      "modify"  -> modifyFn,
      "set"     -> setFn,
      "andThen" -> andThenFn,
      "_steps"  -> stepsAsListV(steps)
    ))

  // ── Helpers ──────────────────────────────────────────────────────────

  def stepsAsListV(steps: List[PathStep]): Value.ListV =
    Value.ListV(steps.map {
      case PathStep.FieldStep(n) => Value.StringV(n)
      case PathStep.SomeStep     => Value.StringV("__some__")
      case PathStep.EachStep     => Value.StringV("__each__")
      case PathStep.IndexStep(i) => Value.TupleV(Value.StringV("__index__") :: Value.intV(i.toLong) :: Nil)
      case PathStep.AtKey(k)     => Value.TupleV(Value.StringV("__at__") :: k :: Nil)
    })

  def stepsFromFields(fields: Map[String, Value]): Option[List[PathStep]] =
    (fields.getOrElse("_steps", null) match
      case null => fields.getOrElse("_path", null)
      case v    => v
    ) match
      case Value.ListV(items) => Some(items.collect {
        case Value.StringV("__some__") => PathStep.SomeStep
        case Value.StringV("__each__") => PathStep.EachStep
        case Value.TupleV(List(Value.StringV("__index__"), Value.IntV(i))) =>
          PathStep.IndexStep(i.toInt)
        case Value.TupleV(List(Value.StringV("__at__"), k)) =>
          PathStep.AtKey(k)
        case Value.StringV(n) => PathStep.FieldStep(n)
      })
      case _ => None

  /** `recv.copy(field = value, ...)` — produce a new InstanceV with the
   *  named fields overridden. */
  def evalCopy(qual: Term, args: List[Term], env: Env, interp: Interpreter): Computation =
    val qualC = interp.eval(qual, env)
    val firstNamed = args.indexWhere {
      case Term.Assign(_: Term.Name, _) => true
      case _                            => false
    }
    if firstNamed >= 0 && args.drop(firstNamed).exists {
      case Term.Assign(_: Term.Name, _) => false
      case _                            => true
    } then interp.located(".copy: positional argument after named argument is not allowed")
    val tagged: List[(Option[String], Computation)] = args.map {
      case Term.Assign(Term.Name(field), rhs) => (Some(field), interp.eval(rhs, env))
      case other                              => (None,        interp.eval(other, env))
    }
    val (tags, comps) = tagged.unzip
    FlatMap(qualC, qualV =>
      interp.threadValues(comps) { newVals =>
        qualV match
          case Value.InstanceV(typeName, fields) =>
            val order = interp.typeFieldOrder.getOrElse(typeName, fields.keys.toList)
            val named = tags.zip(newVals).collect { case (Some(n), v) => n -> v }.toMap
            val positionals = tags.zip(newVals).collect { case (None, v) => v }
            val firstFreeFields = order.filterNot(named.contains).take(positionals.length)
            if positionals.length > firstFreeFields.length then
              interp.located(s".copy: $typeName takes ${order.length} fields, got ${tags.length}")
            else
              val unknownNamed = named.keySet -- fields.keySet
              if unknownNamed.nonEmpty then
                interp.located(s".copy: unknown field(s) on $typeName: ${unknownNamed.mkString(", ")}")
              else
                val fromPositions = firstFreeFields.zip(positionals).toMap
                Pure(Value.InstanceV(typeName, fields ++ fromPositions ++ named))
          case other =>
            interp.located(s".copy: not a case-class instance: ${Value.show(other)}")
      })

  def isFocusName(t: Term): Boolean = t match
    case Term.Name("Focus") => true
    case _                  => false

  def evalFocus(args: List[Term], interp: Interpreter): Computation =
    args match
      case List(lambda) =>
        val stepsOpt: Option[List[PathStep]] = lambda match
          case Term.AnonymousFunction(body) =>
            extractPathSteps(body, isBase = _.isInstanceOf[Term.Placeholder])
          case Term.Function.After_4_6_0(paramClause, body) =>
            paramClause.values.headOption.map(_.name.value) match
              case Some(p) =>
                extractPathSteps(body, isBase = {
                  case Term.Name(n) => n == p
                  case _            => false
                })
              case None => None
          case _ => None
        stepsOpt match
          case Some(steps) if steps.nonEmpty =>
            val hasIndexOrAt = steps.exists {
              case _: PathStep.IndexStep | _: PathStep.AtKey => true
              case _                                         => false
            }
            if steps.contains(PathStep.EachStep) then
              Pure(buildPathTraversal(steps, interp))
            else if steps.contains(PathStep.SomeStep) || hasIndexOrAt then
              Pure(buildPathOptional(steps, interp))
            else
              Pure(buildPathLens(steps.collect {
                case PathStep.FieldStep(n) => n
              }, interp))
          case _ => interp.located("Focus: expected a field-access lambda like _.field.subfield")
      case _ => interp.located("Focus expects exactly one lambda argument")
