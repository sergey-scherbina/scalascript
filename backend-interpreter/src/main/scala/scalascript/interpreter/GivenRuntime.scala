package scalascript.interpreter

import scala.collection.mutable

/** Given/typeclass instance resolution: resolveGiven, parametric factory instantiation,
 *  type-template matching, and related pure helpers.
 */
private[interpreter] object GivenRuntime:

  def resolveGiven(
    typeKey:          String,
    regularArgValues: List[Value],
    callEnv:          Env,
    interp:           Interpreter
  ): Option[Value] =
    resolveGivenInternal(typeKey, regularArgValues, callEnv, Set.empty, interp)

  def resolveGivenInternal(
    typeKey:          String,
    regularArgValues: List[Value],
    callEnv:          Env,
    resolving:        Set[String],
    interp:           Interpreter
  ): Option[Value] =
    if resolving.contains(typeKey) then return None

    val hasFreeVar: Boolean =
      val tcEnd = typeKey.indexOf('[')
      if tcEnd < 0 then false
      else
        val typeArg = typeKey.substring(tcEnd + 1, typeKey.length - 1).trim
        typeArg.nonEmpty &&
        typeArg.forall(c => c.isLetterOrDigit || c == '_') &&
        typeArg.headOption.exists(_.isUpper) &&
        typeArg.length <= 2

    val tc: String = if hasFreeVar then typeKey.substring(0, typeKey.indexOf('[')) else ""

    def candidateFor(ck: String, newRes: Set[String]): List[Value] =
      val count = interp.givenCandidateCount.getOrElse(ck, 0)
      val direct = (callEnv.get(ck).toList ++ interp.globals.get(ck).toList).distinct
        .filter { case Value.InstanceV(_, fs) => !fs.contains("__factory__"); case _ => true }
      val directWithAmbiguity =
        if count > 1 && direct.nonEmpty then List.fill(count)(direct.head)
        else direct
      if directWithAmbiguity.nonEmpty then directWithAmbiguity
      else
        val hits = interp.givenFactories.toList.flatMap { factory =>
          tryInstantiateFactory(factory, ck, regularArgValues, callEnv, newRes, interp)
            .map(v => (specificity(factory.returnTypeTemplate, factory.typeParams), v))
        }
        hits.sortBy(-_._1).headOption.map(_._2).toList

    val newResolving = resolving + typeKey

    val allCandidates: List[Value] =
      if !hasFreeVar then
        candidateFor(typeKey, newResolving + typeKey)
      else
        val valueKey = regularArgValues.iterator
          .map(runtimeValueType)
          .find(t => t != "_" && !t.endsWith("[_]"))
          .map(t => s"$tc[$t]")
        val elemKey = regularArgValues.iterator
          .map(runtimeElemType)
          .find(_ != "_")
          .map(t => s"$tc[$t]")
        val valueCandidates = valueKey.toList.flatMap(ck => candidateFor(ck, newResolving + ck))
        val shouldPreferElem =
          valueCandidates.nonEmpty &&
          valueKey.flatMap(callEnv.get).isDefined &&
          valueKey.flatMap(interp.globals.get).isEmpty &&
          elemKey.flatMap(interp.globals.get).isDefined
        if shouldPreferElem then
          elemKey.toList.flatMap(k => interp.globals.get(k).toList.filter {
            case Value.InstanceV(_, fs) => !fs.contains("__factory__")
            case _                      => true
          })
        else if valueCandidates.nonEmpty then valueCandidates
        else
          val elemCandidates = elemKey.toList.flatMap(ck => candidateFor(ck, newResolving + ck))
          if elemCandidates.nonEmpty then elemCandidates
          else candidateFor(typeKey, newResolving + typeKey)

    allCandidates match
      case Nil     => None
      case List(v) => Some(v)
      case many    =>
        if many.length > many.distinct.length then
          interp.located(s"ambiguous implicits: found ${many.length} candidates for '$typeKey'")
        else
          Some(many.head)

  private def tryInstantiateFactory(
    factory:          ParametricGiven,
    typeKey:          String,
    regularArgValues: List[Value],
    callEnv:          Env,
    resolving:        Set[String],
    interp:           Interpreter
  ): Option[Value] =
    val tvBindings = matchTypeTemplate(factory.returnTypeTemplate, typeKey, factory.typeParams)
    tvBindings.flatMap { bindings =>
      val resolvedDeps: Option[List[(String, Value)]] =
        factory.usingDeps.foldLeft(Option(List.empty[(String, Value)])) { (accOpt, dep) =>
          val (pname, depTemplate) = dep
          accOpt.flatMap { acc =>
            val depKey = applyTypeBindings(depTemplate, bindings)
            val depEnv = callEnv ++ factory.capturedEnv
            resolveGivenInternal(depKey, regularArgValues, depEnv, resolving, interp).map { depVal =>
              acc :+ (pname -> depVal)
            }
          }
        }

      resolvedDeps.map { deps =>
        val bodyEnv = mutable.Map.from(interp.globals)
        factory.capturedEnv.foreach { kv => bodyEnv(kv._1) = kv._2 }
        deps.foreach { dep => bodyEnv(dep._1) = dep._2 }
        deps.foreach { dep =>
          dep._2 match
            case Value.InstanceV(t, _) => bodyEnv(t) = dep._2
            case _ =>
        }
        factory.givenNode.templ.body.stats.foreach(s => interp.execStat(s, bodyEnv))
        val implNames = factory.givenNode.templ.body.stats
          .collect { case dd: scala.meta.Defn.Def => dd.name.value }.toSet
        Value.InstanceV(typeKey, bodyEnv.view.filterKeys(implNames.contains).toMap)
      }
    }

  def matchTypeTemplate(
    template:   String,
    concrete:   String,
    typeParams: List[String]
  ): Option[Map[String, String]] =
    val tvSet = typeParams.toSet
    matchTypeParts(template.trim, concrete.trim, tvSet, Map.empty)

  private def matchTypeParts(
    tmpl:     String,
    conc:     String,
    tvSet:    Set[String],
    bindings: Map[String, String]
  ): Option[Map[String, String]] =
    if tvSet.contains(tmpl) then
      bindings.get(tmpl) match
        case Some(existing) => if existing == conc then Some(bindings) else None
        case None           => Some(bindings + (tmpl -> conc))
    else
      val ti = tmpl.indexOf('[')
      val ci = conc.indexOf('[')
      if ti < 0 && ci < 0 then
        if tmpl == conc then Some(bindings) else None
      else if ti >= 0 && ci >= 0 then
        val tHead = tmpl.substring(0, ti).trim
        val cHead = conc.substring(0, ci).trim
        if tHead != cHead then None
        else
          val tInner = tmpl.substring(ti + 1, tmpl.length - 1).trim
          val cInner = conc.substring(ci + 1, conc.length - 1).trim
          val tArgs = splitTopLevel(tInner)
          val cArgs = splitTopLevel(cInner)
          if tArgs.length != cArgs.length then None
          else
            tArgs.zip(cArgs).foldLeft(Option(bindings)) { (accOpt, pair) =>
              accOpt.flatMap(b => matchTypeParts(pair._1.trim, pair._2.trim, tvSet, b))
            }
      else None

  private def splitTopLevel(s: String): List[String] =
    val parts = mutable.ArrayBuffer.empty[String]
    var depth = 0
    val sb    = new StringBuilder
    for c <- s do
      c match
        case '[' => depth += 1; sb += c
        case ']' => depth -= 1; sb += c
        case ',' if depth == 0 =>
          parts += sb.toString.trim
          sb.clear()
        case _   => sb += c
    parts += sb.toString.trim
    parts.toList

  private def specificity(template: String, typeParams: List[String]): Int =
    val tvSet = typeParams.toSet
    var i = 0
    var score = 0
    while i < template.length do
      if template(i).isLetter || template(i) == '_' then
        var j = i
        while j < template.length && (template(j).isLetterOrDigit || template(j) == '_') do j += 1
        val token = template.substring(i, j)
        if !tvSet.contains(token) then score += token.length
        i = j
      else
        score += 1
        i += 1
    score

  def applyTypeBindings(template: String, bindings: Map[String, String]): String =
    if bindings.isEmpty then template
    else
      val sb = new StringBuilder
      var i  = 0
      while i < template.length do
        if template(i).isLetter || template(i) == '_' then
          var j = i
          while j < template.length && (template(j).isLetterOrDigit || template(j) == '_') do j += 1
          val token = template.substring(i, j)
          sb ++= bindings.getOrElse(token, token)
          i = j
        else
          sb += template(i)
          i += 1
      sb.toString

  private def runtimeElemType(v: Value): String = v match
    case Value.IntV(_)       => "Int"
    case Value.DoubleV(_)    => "Double"
    case Value.StringV(_)    => "String"
    case Value.BoolV(_)      => "Boolean"
    case Value.CharV(_)      => "Char"
    case Value.ListV(h :: _) => runtimeElemType(h)
    case Value.InstanceV(t, _) => t
    case _                   => "_"

  private def runtimeValueType(v: Value): String = v match
    case Value.IntV(_)         => "Int"
    case Value.DoubleV(_)      => "Double"
    case Value.StringV(_)      => "String"
    case Value.BoolV(_)        => "Boolean"
    case Value.CharV(_)        => "Char"
    case Value.ListV(h :: _)   => s"List[${runtimeValueType(h)}]"
    case Value.ListV(Nil)      => "List[_]"
    case Value.OptionV(Some(h)) => s"Option[${runtimeValueType(h)}]"
    case Value.OptionV(None)   => "Option[_]"
    case Value.InstanceV(t, _) => t
    case _                     => "_"
