package scalascript.interpreter

import scala.collection.mutable
import scala.collection.immutable.{Map => IMap}
import scala.meta.*
import Computation.{Pure, Perform}

/** Statement execution: top-level `val`, `var`, `def`, `object`, `class`,
 *  `given`, `extension`, `enum`, `type` declarations and assignments.
 */
private[interpreter] object StatRuntime:

  private def constructNoDefaultInstanceOrFallback(
      typeName: String,
      paramNames: List[String],
      args: List[Value],
      tag: Int,
      fallback: List[Value] => Computation
  ): Computation =
    val itn = typeName.intern()
    paramNames match
      case Nil =>
        val inst = Value.InstanceV(itn, Map.empty)
        inst.typeTag = tag
        Pure(inst)
      case List(p0) if args.length == 1 =>
        val inst = Value.InstanceV(itn, Map.empty)
        inst.fieldsArr  = Array[Value](args.head)
        inst.fieldNames = Array[String](p0)
        inst.typeTag = tag
        Pure(inst)
      case List(p0, p1) if args.length == 2 =>
        val inst = Value.InstanceV(itn, Map.empty)
        inst.fieldsArr  = Array[Value](args.head, args(1))
        inst.fieldNames = Array[String](p0, p1)
        inst.typeTag = tag
        Pure(inst)
      case _ if args.length >= paramNames.length =>
        val take = args.take(paramNames.length)
        val inst = Value.InstanceV(itn, Map.empty)
        inst.fieldsArr  = take.toArray
        inst.fieldNames = paramNames.toArray
        inst.typeTag = tag
        Pure(inst)
      case _ =>
        fallback(args)

  def execStat(stat: Stat, env: mutable.Map[String, Value], printResult: Boolean = false, interp: Interpreter): Unit =
    interp.trackPos(stat)
    val envView = new MutableEnvView(env)
    stat match
    case Defn.Val(_, pats, _, rhs) =>
      val rhsVal = Computation.run(interp.eval(rhs, envView))
      pats match
        case List(Pat.Var(n)) =>
          env(n.value) = rhsVal
          // Register as immutable so BytecodeJit can inline reads as Java
          // literals instead of emitting `readGlobalLong("name")` HashMap
          // probes (Direction A free-name globals constant-folding).
          interp.valNames += n.value
        case List(pat) =>
          val patEnv = PatternRuntime.matchPat(pat, rhsVal, envView, interp)
          if patEnv == null then interp.located(s"Val pattern match failed")
          else patEnv.foreach { (k, v) => env(k) = v; interp.valNames += k }
        case _ => ()

    case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs) =>
      env(n.value) = Computation.run(interp.eval(rhs, envView))

    case d: Defn.Def if scalascript.transform.EffectAnalysis.isExternDef(d.body) =>
      // Stage 5+/A.6 (Б-1) — extern def stub is type-only; the
      // intrinsic table (`InterpreterIntrinsics`) provides the
      // real impl via `installNativeIntrinsics` at session start.
      // Skip the def so the runtime keeps the intrinsic binding
      // (otherwise our FunV would shadow it with a body that fails
      // when called — `__extern__` is undefined).
      // Phase 2 lazy loading: if the intrinsic isn't installed yet (plugin not
      // loaded), trigger ensurePluginsLoaded() now.  This covers child
      // interpreters used to process import files whose exported globals must
      // include plugin-provided intrinsics even when no Term.Name lookup fires.
      if hasAnnot(d.mods, "interpreterUnsupported") then
        // Register a stub that throws a descriptive error at call time.
        val defName = d.name.value
        val msg     = stringAnnot(d.mods, "interpreterUnsupported")
          .getOrElse(s"`$defName` is annotated @interpreterUnsupported and cannot be called from the interpreter.")
        env(defName) = Value.NativeFnV(defName, _ => interp.located(msg))
      else if !interp.globals.contains(d.name.value) && !interp._pluginsLoaded then
        interp.ensurePluginsLoaded()

    case d: Defn.Def =>
      val allClauses      = d.paramClauseGroups.flatMap(_.paramClauses)
      val regularClauses  = allClauses.filter(_.mod.isEmpty)
      val usingClauses    = allClauses.filter(_.mod.nonEmpty)
      val regularParamVals = regularClauses.flatMap(_.values).toList
      val usingParamVals   = usingClauses.flatMap(_.values).toList
      // Phase 3: context-bound type params [A: TC] → synthetic using param "A$TC: TC[A]"
      @annotation.nowarn("msg=deprecated")
      val cbUsingParams: List[(String, String)] =
        d.paramClauseGroups.flatMap(_.tparamClause.values).flatMap { tp =>
          tp.cbounds.map { cb =>
            val tvName = tp.name.value
            val tcStr  = interp.typeToString(cb.asInstanceOf[scala.meta.Type])
            s"${tvName}$$${tcStr.takeWhile(_ != '[')}" -> s"$tcStr[$tvName]"
          }
        }
      val allRegularVals = regularParamVals ++ usingParamVals
      val params   = allRegularVals.map(_.name.value) ++ cbUsingParams.map(_._1)
      val defaults = allRegularVals.map(_.default)    ++ cbUsingParams.map(_ => None)
      val paramTypes  = regularParamVals.map(p => p.decltpe.fold("Any")(interp.typeToString))
      val usingInfo: List[(String, String)] =
        usingParamVals.map(p => p.name.value -> p.decltpe.fold("Any")(interp.typeToString)) ++ cbUsingParams
      // Top-level defs (env eq globals) need no capture; locals are already non-global.
      val capturedEnv: Map[String, Value] =
        if env eq interp.globals then Map.empty
        else env.iterator.collect { case (k, v) if interp.globals.getOrElse(k, null) != v => k -> v }.toMap
      val rThrows = d.decltpe.exists(interp.isThrowsType)
      val fn: Value.FunV = Value.FunV(params, d.body, capturedEnv, d.name.value, defaults, paramTypes, usingInfo, rThrows)
      env(d.name.value) = fn
      if d.name.value == "main" && params.isEmpty then interp.mainCalled = false

    case d: Defn.Object =>
      val objectName = d.name.value
      // Seed members with the outer scope so closures and extension methods defined
      // inside the object body can see imported symbols.  This matters when
      // wrapSectionInPackage wraps module code in `object std { object pkg { … } }`:
      // without interp, functions/interp.extensions miss anything in interp.globals (e.g. imports).
      // Also unfold any existing same-named object's fields so separate code
      // blocks all wrapped in `object std { object dsl { ... } }` can reference
      // symbols defined by earlier blocks during evaluation, not only after merge.
      val outerSnap  = interp.globals.toMap ++ envView
      val existingFields = env.get(objectName) match
        case Some(Value.InstanceV(_, fs)) => fs
        case _                            => Map.empty[String, Value]
      val members    = mutable.Map.from(outerSnap ++ existingFields)
      d.templ.body.stats.foreach {
        case dd: Defn.Def if EffectsRuntime.isEffectOpDef(dd.body) =>
          val effName = objectName
          val opName  = dd.name.value
          // Effect op: a bare Perform request. The "rest of the computation" is
          // captured in an outer FlatMap by the bind chain that consumed it.
          members(opName) = Value.NativeFnV(s"$effName.$opName",
            args => Perform(effName, opName, args))
        case s => StatRuntime.execStat(s, members, false, interp)
      }
      // Only expose fields that are NEW or CHANGED relative to the outer scope,
      // so the InstanceV doesn't carry inherited interp.globals as object members.
      val newFields = members.toMap.filter { (k, v) => outerSnap.get(k).forall(old => !(old eq v)) }
      val newObj: Value.InstanceV = Value.InstanceV(objectName, newFields)
      env.get(objectName) match
        case Some(existing: Value.InstanceV) => env(objectName) = interp.mergeDeep(existing, newObj)
        case _                               => env(objectName) = newObj

    case d: Defn.Class =>
      val params = d.ctor.paramClauses.flatMap(_.values).toList
      val paramNames    = params.map(_.name.value)
      val paramDefaults = params.map(_.default)
      val typeName = d.name.value
      val ctorEnv = envView
      interp.typeFieldOrder(typeName) = paramNames
      interp.typeFieldTypes(typeName) = params.map(p =>
        p.decltpe.fold("String")(interp.typeToString)
      )
      interp.typeFieldSchemas(typeName) = params.map(p => fieldSchema(typeName, p, ctorEnv, interp))
      if hasAnnot(d.mods, "rejectUnknown") || interp.frontmatterSchemas.get(typeName).exists(_.rejectUnknown) then interp.rejectUnknownTypes += typeName
      if d.mods.exists {
        case Mod.Annot(Init.After_4_6_0(Type.Name("noTrace"), _, _)) => true
        case _ => false
      } then interp.noTraceTypes += typeName
      // Record first parent type for extension-method dispatch on sealed parents.
      d.templ.inits.headOption.foreach { init =>
        val pn = init.tpe match
          case Type.Name(n)   => n
          case ta: Type.Apply => ta.tpe match { case Type.Name(n) => n; case _ => "" }
          case _              => ""
        if pn.nonEmpty then interp.parentTypes(typeName) = pn
      }
      DerivesRuntime.registerMirror(typeName, env, interp)
      interp.parentTypes.get(typeName).foreach(parent => DerivesRuntime.registerMirror(parent, env, interp))
      val classTag = interp.typeTagFor(typeName)
      val classFallbackCtor: List[Value] => Computation = args => {
        val filled = interp.applyDefaults(paramNames, paramDefaults, args, ctorEnv)
        val inst = Value.InstanceV(typeName.intern(), Map.empty)
        inst.fieldsArr  = filled.toArray
        inst.fieldNames = paramNames.toArray
        inst.typeTag = classTag
        Pure(inst)
      }
      val noDefaults = paramDefaults.forall(_.isEmpty)
      env(typeName) = if noDefaults then
        Value.NativeFnV(typeName, args =>
          constructNoDefaultInstanceOrFallback(typeName, paramNames, args, classTag, classFallbackCtor))
      else
        Value.NativeFnV(typeName, classFallbackCtor)
      // Methods defined inside the class body are stored in a separate
      // type-keyed registry; dispatch on an InstanceV consults it and re-binds
      // each method's closure with the instance's data fields so the body can
      // refer to them by name (`x`, `y` in `def distanceTo(other) = ...x...`).
      val classEnv = envView
      val methodPairs: List[(String, Value.FunV)] = d.templ.body.stats.collect {
        case dd: Defn.Def =>
          val mparamVals = dd.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).toList
          val mparams    = mparamVals.map(_.name.value)
          val mdefaults  = mparamVals.map(_.default)
          (dd.name.value, Value.FunV(mparams, dd.body, classEnv, dd.name.value, mdefaults))
      }
      val methodDefs: Map[String, Value.FunV] = methodPairs.toMap
      if methodDefs.nonEmpty then interp.typeMethods(typeName) = methodDefs
      // Auto-generate given instances for derived typeclasses
      if d.templ.derives.nonEmpty then
        d.templ.derives.foreach { derivedType =>
          val tcName = derivedType match
            case Type.Name(n) => n
            case _            => derivedType.syntax
          DerivesRuntime.synthesizeDerivedInstance(typeName, paramNames, tcName, env, interp)
        }

    case d: Defn.Enum =>
      val enumName = d.name.value
      val caseFields = mutable.Map.empty[String, Value]
      // Parameterless cases, in declaration order, for `EnumName.values`.
      val enumValues = mutable.ListBuffer.empty[Value]
      val ctorEnv = envView
      /** Bind a parameterless enum case as a singleton value, reachable both
       *  bare (`Debit`) and qualified (`Side.Debit`), and matchable. */
      def bindNullaryCase(caseName: String): Unit =
        interp.parentTypes(caseName) = enumName
        val v = Value.InstanceV(caseName, Map.empty)
        env(caseName) = v
        caseFields(caseName) = v
        enumValues += v
      d.templ.body.stats.foreach {
        case ec: Defn.EnumCase =>
          val caseName = ec.name.value
          val ecParams      = ec.ctor.paramClauses.flatMap(_.values).toList
          val paramNames    = ecParams.map(_.name.value)
          val paramDefaults = ecParams.map(_.default)
          if paramNames.isEmpty then
            bindNullaryCase(caseName)
          else
            interp.typeFieldOrder(caseName) = paramNames
            interp.typeFieldTypes(caseName) = ecParams.map(p => p.decltpe.fold("String")(interp.typeToString))
            interp.typeFieldSchemas(caseName) = ecParams.map(p => fieldSchema(caseName, p, ctorEnv, interp))
            if hasAnnot(ec.mods, "rejectUnknown") || interp.frontmatterSchemas.get(caseName).exists(_.rejectUnknown) then interp.rejectUnknownTypes += caseName
            interp.parentTypes(caseName) = enumName
            val enumTag = interp.typeTagFor(caseName)
            val enumFallbackCtor: List[Value] => Computation = args => {
              val filled = interp.applyDefaults(paramNames, paramDefaults, args, ctorEnv)
              val inst = Value.InstanceV(caseName.intern(), Map.empty)
              inst.fieldsArr  = filled.toArray
              inst.fieldNames = paramNames.toArray
              inst.typeTag = enumTag
              Pure(inst)
            }
            val noEnumDefaults = paramDefaults.forall(_.isEmpty)
            val v: Value =
              if noEnumDefaults then
                Value.NativeFnV(caseName, args =>
                  constructNoDefaultInstanceOrFallback(caseName, paramNames, args, enumTag, enumFallbackCtor))
              else Value.NativeFnV(caseName, enumFallbackCtor)
            env(caseName) = v
            caseFields(caseName) = v
        // `case A, B, C` (comma-separated parameterless cases) parses as a
        // RepeatedEnumCase, not individual EnumCases — previously dropped.
        case rec: Defn.RepeatedEnumCase =>
          rec.cases.foreach(nm => bindNullaryCase(nm.value))
        case _ => ()
      }
      // `EnumName.values` — declaration-ordered parameterless cases.
      if !caseFields.contains("values") then
        caseFields("values") = Value.ListV(enumValues.toList)
      env(enumName) = Value.InstanceV(enumName, caseFields.toMap)

    case d: Defn.Trait =>
      // Register a sentinel InstanceV so the trait name is importable as a
      // type-level symbol (e.g. `[Semigroup, intSum](std/semigroup-monoid.ssc)`).
      val traitName = d.name.value
      if !env.contains(traitName) then
        env(traitName) = Value.InstanceV(traitName, Map.empty)
      DerivesRuntime.registerMirror(traitName, env, interp)
      // Store abstract method names for remoteStub[Api] trait-method derivation.
      val abstractMethods = d.templ.body.stats.collect { case m: Decl.Def => m.name.value }
      if abstractMethods.nonEmpty then
        interp.nativeFeatureSet(s"$$traitMethods$$${traitName}", abstractMethods)
      // If the trait has `derives` clauses, synthesize those instances.
      if d.templ.derives.nonEmpty then
        d.templ.derives.foreach { derivedType =>
          val tcName = derivedType match
            case Type.Name(n) => n
            case _            => derivedType.syntax
          DerivesRuntime.synthesizeDerivedInstance(traitName, Nil, tcName, env, interp)
        }

    case d: Defn.Given =>
      // Collect type parameters and using-clause parameters from the given definition.
      // A parametric given like `given listOrd[A](using ord: Ordering[A]): Ordering[List[A]]`
      // has a non-empty tparamClause and a using paramClause.
      val allGivenClauses  = d.paramClauseGroups.flatMap(_.paramClauses)
      val givenTypeParams  = d.paramClauseGroups.flatMap(_.tparamClause.values).map(_.name.value)
      val givenUsingClauses = allGivenClauses.filter(_.mod.nonEmpty)
      val givenUsingDeps   = givenUsingClauses.flatMap(_.values).map { p =>
        p.name.value -> p.decltpe.fold("Any")(interp.typeToString)
      }
      val isParametric = givenTypeParams.nonEmpty || givenUsingDeps.nonEmpty

      d.templ.inits.headOption.foreach { init =>
        // Use typeToString for proper recursive handling of nested type applications
        // (e.g. Wrap[List[List[A]]] should yield "Wrap[List[List[A]]]", not "Wrap[List[_]]").
        val typeKeyOpt: Option[String] =
          val k = interp.typeToString(init.tpe)
          if k == "_" then None else Some(k)

        typeKeyOpt.foreach { typeKey =>
          if isParametric then
            // Parametric given: register as a factory for later recursive resolution.
            // The captured env is the current env snapshot (minus interp.globals that haven't changed).
            val captured: Map[String, Value] =
              if env eq interp.globals then Map.empty
              else env.iterator.collect { case (k, v) if interp.globals.getOrElse(k, null) != v => k -> v }.toMap
            val factory = ParametricGiven(
              name               = d.name.value,
              typeParams         = givenTypeParams,
              usingDeps          = givenUsingDeps,
              returnTypeTemplate = typeKey,
              givenNode          = d,
              capturedEnv        = captured
            )
            interp.givenFactories += factory
            // Register by name so explicit `using factoryName` still works —
            // for that we eagerly build an instance without binding type vars
            // (type vars remain as-is; explicit calls supply concrete args).
            val explicitName = d.name.value
            if explicitName.nonEmpty then
              // Build a partially-applied marker so the name resolves to something.
              // Callers that say `using listOrd` supply a concrete type context.
              val partialInst = Value.InstanceV(typeKey, new IMap.Map1("__factory__", Value.True))
              env(explicitName) = partialInst
          else
            // Concrete (non-parametric) given: evaluate immediately and store.
            val members = mutable.Map.from(interp.globals)
            d.templ.body.stats.foreach(s => StatRuntime.execStat(s, members, false, interp))
            val implNames = d.templ.body.stats.collect { case dd: Defn.Def => dd.name.value }.toSet
            val instance  = Value.InstanceV(typeKey, members.view.filterKeys(implNames.contains).toMap)
            env(typeKey) = instance
            // Track candidate count for ambiguity detection.
            interp.givenCandidateCount(typeKey) = interp.givenCandidateCount.getOrElse(typeKey, 0) + 1
            val explicitName = d.name.value
            if explicitName.nonEmpty then env(explicitName) = instance
        }
      }

    case _: Decl.Def => () // abstract method declaration — no body

    case d: Defn.ExtensionGroup =>
      d.paramClauseGroup.foreach { pcg =>
        pcg.paramClauses.headOption.flatMap(_.values.headOption).foreach { recvParam =>
          val recvName = recvParam.name.value
          val recvTypeName = recvParam.decltpe match
            case Some(Type.Name(n))   => n
            case Some(ta: Type.Apply) => ta.tpe match { case Type.Name(n) => n; case _ => "Any" }
            case _                    => "Any"
          def registerDef(defn: Defn.Def): Unit =
            val mparamVals   = defn.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).toList
            val methodParams = mparamVals.map(_.name.value)
            // Receiver param has no default; method params keep theirs.
            val methodDefaults: List[Option[Term]] = None :: mparamVals.map(_.default)
            interp.extensions.getOrElseUpdate(recvTypeName, mutable.HashMap.empty)(defn.name.value) =
              Value.FunV(recvName :: methodParams, defn.body, envView, "", methodDefaults)
          d.body match
            case defn: Defn.Def    => registerDef(defn)
            case Term.Block(stats) => stats.foreach { case defn: Defn.Def => registerDef(defn); case _ => () }
            case _                 => ()
        }
      }

    case t: Term =>
      val result = Computation.run(interp.eval(t, envView))
      t match
        case Term.Apply.After_4_6_0(Term.Name("main"), _) => interp.mainCalled = true
        case _                                => ()
      interp.lastExprResult = result
      if printResult then interp.autoOutput(result)
      else result: @annotation.nowarn("msg=Discarded")

    // opaque type declaration — zero runtime overhead (same rep as underlying).
    // Auto-generate a companion singleton with `apply` / `unapply` if no
    // explicit companion object has been defined yet.  When the user writes
    //   object UserId:
    //     def apply(s: String): UserId = s
    // that Defn.Object is processed later and will OVERWRITE our synthetic
    // entry via the normal mergeDeep path, so there is no conflict.
    case d: Defn.Type if d.mods.exists(_.isInstanceOf[Mod.Opaque]) =>
      val typeName = d.name.value
      // Only synthesize if no companion already registered.
      if !env.contains(typeName) then
        // apply: identity — opaque type has same runtime rep as underlying
        val applyFn = Value.NativeFnV(s"$typeName.apply",
          args => Pure(args.headOption.getOrElse(Value.UnitV)))
        // unapply: wrap value in Some(...)
        val unapplyFn = Value.NativeFnV(s"$typeName.unapply",
          args => Pure(Value.InstanceV("Some", new IMap.Map1("value", args.headOption.getOrElse(Value.UnitV)))))
        env(typeName) = Value.InstanceV(typeName, new IMap.Map2("apply", applyFn, "unapply", unapplyFn))

    case _ => () // type aliases, imports, exports, etc.

  private def fieldSchema(typeName: String, param: Term.Param, env: Map[String, Value], interp: Interpreter): TypeFieldSchema =
    val fieldName = param.name.value
    val fmField = interp.frontmatterSchemas.get(typeName).flatMap(_.fields.find(_.fieldName == fieldName))
    TypeFieldSchema(
      fieldName   = fieldName,
      storageName = fmField.flatMap(_.storageName).orElse(stringAnnot(param.mods, "fieldName")).getOrElse(fieldName),
      aliases     = fmField.map(_.aliases).getOrElse(stringSeqAnnot(param.mods, "aliases")),
      default     = fmField.flatMap(_.default.map(schemaDefaultValue)).orElse(param.default.map(t => Computation.run(interp.eval(t, env)))),
      key         = fmField.exists(_.key) || hasAnnot(param.mods, "key")
    )

  private def schemaDefaultValue(default: scalascript.ast.SchemaDefault): Value = default match
    case scalascript.ast.SchemaDefault.NullValue => Value.NullV
    case scalascript.ast.SchemaDefault.Bool(value) => Value.boolV(value)
    case scalascript.ast.SchemaDefault.IntValue(value) => Value.intV(value)
    case scalascript.ast.SchemaDefault.DoubleValue(value) => Value.doubleV(value)
    case scalascript.ast.SchemaDefault.StringValue(value) => Value.StringV(value)

  private def hasAnnot(mods: List[Mod], name: String): Boolean =
    mods.exists {
      case Mod.Annot(init) => annotName(init).contains(name)
      case _ => false
    }

  private def stringAnnot(mods: List[Mod], name: String): Option[String] =
    mods.collectFirst {
      case Mod.Annot(init) if annotName(init).contains(name) =>
        init.argClauses.flatMap(_.values).collectFirst {
          case Lit.String(value) => value
        }
    }.flatten

  private def stringSeqAnnot(mods: List[Mod], name: String): List[String] =
    mods.collectFirst {
      case Mod.Annot(init) if annotName(init).contains(name) =>
        init.argClauses.flatMap(_.values).collect { case Lit.String(value) => value }.toList
    }.getOrElse(Nil)

  private def annotName(init: Init): Option[String] = init.tpe match
    case Type.Name(value) => Some(value)
    case Type.Select(_, Type.Name(value)) => Some(value)
    case _ => Some(init.tpe.syntax.split('.').last)
