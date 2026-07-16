package scalascript.control

import scala.annotation.compileTimeOnly
import scala.annotation.implicitNotFound
import scala.quoted.Expr
import scala.quoted.Quotes
import scala.quoted.Type
import scala.util.control.NonFatal

/** Lexically scoped direct-style lowering to the explicit control protocol. */
object direct:
  @implicitNotFound(
    "error [UNMANAGED_CAPTURE]: direct.shift must be lexically enclosed " +
      "by direct.reset for the same prompt and effect row"
  )
  sealed abstract class Scope[P, Fx <: Effect, R] private[control] ()

  transparent inline def reset[P, Fx <: Effect, R](prompt: Prompt[P, R])(
      inline body: Scope[P, Fx, R] ?=> R
  ): Eff[Fx, R] =
    ${ DirectMacros.resetImpl[P, Fx, R]('prompt, 'body) }

  @compileTimeOnly(
    "error [UNMANAGED_CAPTURE]: direct.shift escaped its enclosing direct.reset transform"
  )
  def shift[P, A, Fx <: Effect, R](prompt: Prompt[P, R])(
      body: ShiftBody[P, A, Fx, R]
  )(using Scope[P, Fx, R]): A =
    throw new IllegalStateException(
      "direct.shift marker survived compile-time lowering"
    )

private object DirectMacros:
  def resetImpl[
      P: Type,
      Fx <: Effect: Type,
      R: Type
  ](
      prompt: Expr[Prompt[P, R]],
      body: Expr[direct.Scope[P, Fx, R] ?=> R]
  )(using quotes: Quotes): Expr[Eff[Fx, R]] =
    import quotes.reflect.*

    final case class Marker(
        typeArguments: List[TypeTree],
        promptArgument: Term,
        bodyArgument: Term,
        scopeArgument: Term,
        whole: Term
    )

    def strip(term: Term): Term =
      term match
        case Inlined(_, _, value) => strip(value)
        case Typed(value, _)      => strip(value)
        case value                => value

    def stripMarker(term: Term): Term =
      term match
        case Typed(value, _) => stripMarker(value)
        case value           => value

    val (scopeSymbol, sourceOwner, rawBody) =
      strip(body.asTerm) match
        case Lambda(List(scopeParameter), value) =>
          (
            scopeParameter.symbol,
            scopeParameter.symbol.owner,
            strip(value)
          )
        case other =>
          report.errorAndAbort(
            "error [DIRECT_STYLE_UNSUPPORTED]: direct.reset requires a lexical contextual body",
            other.pos
          )

    val directShiftSymbol =
      Symbol.requiredMethod("scalascript.control.direct.shift")

    val directResetSymbol =
      Symbol.requiredMethod("scalascript.control.direct.reset")

    val boundaryBreakSymbols =
      Symbol.requiredModule("scala.util.boundary").methodMember("break").toSet

    def calledSymbol(term: Term): Symbol =
      term match
        case Apply(function, _)     => calledSymbol(function)
        case TypeApply(function, _) => calledSymbol(function)
        case Typed(function, _)     => calledSymbol(function)
        case value                  => value.symbol

    def isBoundaryBreakInvocation(term: Term): Boolean =
      term match
        case _: Apply => boundaryBreakSymbols.contains(calledSymbol(term))
        case _        => false

    def boundaryBreakPosition(term: Term): Position =
      def applicationPositions(current: Term): List[Position] =
        current match
          case application @ Apply(function, _) =>
            application.pos :: applicationPositions(function)
          case application @ TypeApply(function, _) =>
            application.pos :: applicationPositions(function)
          case typed @ Typed(function, _) =>
            typed.pos :: applicationPositions(function)
          case function => List(function.pos)
      applicationPositions(term)
        .filter(position => position.end > position.start)
        .minByOption(position => position.end - position.start)
        .getOrElse(term.pos)

    def rejectDeferredControl(tree: Tree): Unit =
      def isOwnedBy(symbol: Symbol, ancestor: Symbol): Boolean =
        @annotation.tailrec
        def loop(current: Symbol): Boolean =
          if current == Symbol.noSymbol then false
          else if current == ancestor then true
          else loop(current.owner)
        loop(symbol)

      val traverser = new TreeTraverser:
        private var inlineCallPositions = List.empty[Position]

        private def reject(invocation: Term): Nothing =
          report.errorAndAbort(
            "error [DIRECT_STYLE_UNSUPPORTED]: direct.reset M1 cannot defer scala.util.boundary.break; move boundary control outside direct.reset",
            inlineCallPositions.headOption.getOrElse(
              boundaryBreakPosition(invocation)
            )
          )

        override def traverseTree(current: Tree)(owner: Symbol): Unit =
          current match
            case inlined: Inlined =>
              val previous = inlineCallPositions
              inlined.call.foreach { call =>
                if call.pos.sourceCode.nonEmpty ||
                  call.pos.end > call.pos.start
                then
                  inlineCallPositions = call.pos :: inlineCallPositions
              }
              try traverseTreeChildren(inlined)(owner)
              finally inlineCallPositions = previous
            case invocation: Term
                if isBoundaryBreakInvocation(invocation) =>
              reject(invocation)
            case returned: Return
                if !isOwnedBy(returned.from, sourceOwner) =>
              report.errorAndAbort(
                "error [DIRECT_STYLE_UNSUPPORTED]: direct.reset cannot defer a non-local return",
                returned.pos
              )
            case _ => traverseTreeChildren(current)(owner)

      traverser.traverseTree(tree)(sourceOwner)

    def marker(term: Term): Option[Marker] =
      stripMarker(term) match
        case whole @ Apply(
              Apply(
                Apply(
                  TypeApply(function, typeArguments),
                  List(promptArgument)
                ),
                List(bodyArgument)
              ),
              List(scopeArgument)
            )
            if function.symbol == directShiftSymbol &&
              typeArguments.size == 4 =>
          Some(
            Marker(
              typeArguments,
              promptArgument,
              bodyArgument,
              scopeArgument,
              whole
            )
          )
        case _ => None

    def managedResetPrompt(term: Term): Option[Term] =
      stripMarker(term) match
        case Apply(
              Apply(
                TypeApply(function, typeArguments),
                List(promptArgument)
              ),
              List(_)
            )
            if function.symbol == directResetSymbol &&
              typeArguments.size == 3 =>
          Some(promptArgument)
        case _ => None

    def replaceReferences(
        term: Term,
        replacements: Map[Symbol, Term],
        typeReplacements: Map[Symbol, TypeRepr] = Map.empty
    ): Term =
      val mapper = new TreeMap:
        override def transformTerm(tree: Term)(owner: Symbol): Term =
          tree match
            case reference: Ident =>
              replacements.getOrElse(
                reference.symbol,
                super.transformTerm(tree)(owner)
              )
            case selection: Select =>
              val qualifier = transformTerm(selection.qualifier)(owner)
              if selection.symbol == Symbol.noSymbol then
                // Structural PolyFunction applications have no source symbol to
                // reuse; resolve the member from the moved qualifier instead.
                try Select.unique(qualifier, selection.name)
                catch
                  case NonFatal(_) =>
                    report.errorAndAbort(
                      "error [DIRECT_STYLE_UNSUPPORTED]: direct.shift cannot resolve a moved structural selection across capture; move the call outside direct.reset",
                      selection.pos
                    )
              else Select(qualifier, selection.symbol)
            case _ => super.transformTerm(tree)(owner)

        override def transformTypeTree(
            tree: TypeTree
        )(owner: Symbol): TypeTree =
          val substituted =
            if typeReplacements.isEmpty then tree.tpe
            else
              tree.tpe.substituteTypes(
                typeReplacements.keys.toList,
                typeReplacements.values.toList
              )
          if typeReplacements.nonEmpty ||
            typeDependsOn(substituted, replacements)
          then Inferred(rebindType(substituted, replacements, tree.pos))
          else super.transformTypeTree(tree)(owner)
      mapper.transformTerm(term)(sourceOwner)

    def treeDependsOn(
        tree: Tree,
        replacements: Map[Symbol, Term]
    ): Boolean =
      var found = false
      val traverser = new TreeTraverser:
        override def traverseTree(current: Tree)(owner: Symbol): Unit =
          current match
            case reference: Ident
                if replacements.contains(reference.symbol) =>
              found = true
            case _ if !found => traverseTreeChildren(current)(owner)
            case _           => ()
      traverser.traverseTree(tree)(sourceOwner)
      found

    def typeDependsOn(
        tpe: TypeRepr,
        replacements: Map[Symbol, Term]
    ): Boolean =
      replacements.contains(tpe.termSymbol) ||
        (tpe match
          case TypeRef(qualifier, _) =>
            typeDependsOn(qualifier, replacements)
          case TermRef(qualifier, _) =>
            typeDependsOn(qualifier, replacements)
          case AppliedType(constructor, arguments) =>
            typeDependsOn(constructor, replacements) ||
              arguments.exists(typeDependsOn(_, replacements))
          case AndType(left, right) =>
            typeDependsOn(left, replacements) ||
              typeDependsOn(right, replacements)
          case OrType(left, right) =>
            typeDependsOn(left, replacements) ||
              typeDependsOn(right, replacements)
          case ByNameType(underlying) =>
            typeDependsOn(underlying, replacements)
          case TypeBounds(low, high) =>
            typeDependsOn(low, replacements) ||
              typeDependsOn(high, replacements)
          case Refinement(parent, _, info) =>
            typeDependsOn(parent, replacements) ||
              typeDependsOn(info, replacements)
          case MatchType(bound, scrutinee, cases) =>
            typeDependsOn(bound, replacements) ||
              typeDependsOn(scrutinee, replacements) ||
              cases.exists(typeDependsOn(_, replacements))
          case AnnotatedType(underlying, annotation) =>
            typeDependsOn(underlying, replacements) ||
              treeDependsOn(annotation, replacements)
          case method: MethodType =>
            method.paramTypes.exists(typeDependsOn(_, replacements)) ||
              typeDependsOn(method.resType, replacements)
          case poly: PolyType =>
            poly.paramBounds.exists(typeDependsOn(_, replacements)) ||
              typeDependsOn(poly.resType, replacements)
          case lambda: TypeLambda =>
            lambda.paramBounds.exists(typeDependsOn(_, replacements)) ||
              typeDependsOn(lambda.resType, replacements)
          case recursive: RecursiveType =>
            typeDependsOn(recursive.underlying, replacements)
          case flexible: FlexibleType =>
            typeDependsOn(flexible.underlying, replacements)
          case SuperType(thisType, superType) =>
            typeDependsOn(thisType, replacements) ||
              typeDependsOn(superType, replacements)
          case ThisType(underlying) =>
            typeDependsOn(underlying, replacements)
          case _ => false)

    def typeDependsOnSymbols(
        tpe: TypeRepr,
        symbols: Set[Symbol]
    ): Boolean =
      symbols.contains(tpe.typeSymbol) ||
        (tpe match
          case TypeRef(qualifier, _) =>
            typeDependsOnSymbols(qualifier, symbols)
          case TermRef(qualifier, _) =>
            typeDependsOnSymbols(qualifier, symbols)
          case AppliedType(constructor, arguments) =>
            typeDependsOnSymbols(constructor, symbols) ||
              arguments.exists(typeDependsOnSymbols(_, symbols))
          case AndType(left, right) =>
            typeDependsOnSymbols(left, symbols) ||
              typeDependsOnSymbols(right, symbols)
          case OrType(left, right) =>
            typeDependsOnSymbols(left, symbols) ||
              typeDependsOnSymbols(right, symbols)
          case ByNameType(underlying) =>
            typeDependsOnSymbols(underlying, symbols)
          case TypeBounds(low, high) =>
            typeDependsOnSymbols(low, symbols) ||
              typeDependsOnSymbols(high, symbols)
          case Refinement(parent, _, info) =>
            typeDependsOnSymbols(parent, symbols) ||
              typeDependsOnSymbols(info, symbols)
          case MatchType(bound, scrutinee, cases) =>
            typeDependsOnSymbols(bound, symbols) ||
              typeDependsOnSymbols(scrutinee, symbols) ||
              cases.exists(typeDependsOnSymbols(_, symbols))
          case AnnotatedType(underlying, _) =>
            typeDependsOnSymbols(underlying, symbols)
          case method: MethodType =>
            method.paramTypes.exists(typeDependsOnSymbols(_, symbols)) ||
              typeDependsOnSymbols(method.resType, symbols)
          case poly: PolyType =>
            poly.paramBounds.exists(typeDependsOnSymbols(_, symbols)) ||
              typeDependsOnSymbols(poly.resType, symbols)
          case lambda: TypeLambda =>
            lambda.paramBounds.exists(typeDependsOnSymbols(_, symbols)) ||
              typeDependsOnSymbols(lambda.resType, symbols)
          case recursive: RecursiveType =>
            typeDependsOnSymbols(recursive.underlying, symbols)
          case flexible: FlexibleType =>
            typeDependsOnSymbols(flexible.underlying, symbols)
          case SuperType(thisType, superType) =>
            typeDependsOnSymbols(thisType, symbols) ||
              typeDependsOnSymbols(superType, symbols)
          case ThisType(underlying) =>
            typeDependsOnSymbols(underlying, symbols)
          case _ => false)

    def rebindType(
        tpe: TypeRepr,
        replacements: Map[Symbol, Term],
        position: Position
    ): TypeRepr =
      replacements.get(tpe.termSymbol) match
        case Some(replacement) => replacement.tpe
        case None if !typeDependsOn(tpe, replacements) => tpe
        case None =>
          tpe match
            case reference @ TypeRef(qualifier, _) =>
              rebindType(qualifier, replacements, position)
                .select(reference.typeSymbol)
            case TermRef(qualifier, name) =>
              TermRef(rebindType(qualifier, replacements, position), name)
            case AppliedType(constructor, arguments) =>
              AppliedType(
                rebindType(constructor, replacements, position),
                arguments.map(rebindType(_, replacements, position))
              )
            case AndType(left, right) =>
              AndType(
                rebindType(left, replacements, position),
                rebindType(right, replacements, position)
              )
            case OrType(left, right) =>
              OrType(
                rebindType(left, replacements, position),
                rebindType(right, replacements, position)
              )
            case ByNameType(underlying) =>
              ByNameType(rebindType(underlying, replacements, position))
            case TypeBounds(low, high) =>
              TypeBounds(
                rebindType(low, replacements, position),
                rebindType(high, replacements, position)
              )
            case Refinement(parent, name, info) =>
              Refinement(
                rebindType(parent, replacements, position),
                name,
                rebindType(info, replacements, position)
              )
            case MatchType(bound, scrutinee, cases) =>
              MatchType(
                rebindType(bound, replacements, position),
                rebindType(scrutinee, replacements, position),
                cases.map(rebindType(_, replacements, position))
              )
            case AnnotatedType(underlying, annotation) =>
              AnnotatedType(
                rebindType(underlying, replacements, position),
                replaceReferences(annotation, replacements)
              )
            case _ =>
              report.errorAndAbort(
                "error [DIRECT_STYLE_UNSUPPORTED]: direct.shift cannot rebind this dependent local type across capture; move the declaration outside direct.reset",
                position
              )

    def rebindDefinitionType(
        tpe: TypeRepr,
        replacements: Map[Symbol, Term],
        position: Position
    ): TypeRepr =
      def sameBinder(left: TypeRepr, right: TypeRepr): Boolean =
        left.asInstanceOf[AnyRef] eq right.asInstanceOf[AnyRef]

      def reboundParameter(
          parameter: ParamRef,
          binders: List[(TypeRepr, TypeRepr)]
      ): Option[TypeRepr] =
        binders.collectFirst {
          case (oldBinder, freshBinder)
              if sameBinder(parameter.binder, oldBinder) =>
            freshBinder match
              case method: MethodType => method.param(parameter.paramNum)
              case poly: PolyType     => poly.param(parameter.paramNum)
              case lambda: TypeLambda => lambda.param(parameter.paramNum)
        }

      def dependsOnBinder(
          current: TypeRepr,
          binders: List[(TypeRepr, TypeRepr)]
      ): Boolean =
        current match
          case parameter: ParamRef =>
            reboundParameter(parameter, binders).nonEmpty
          case TypeRef(qualifier, _) =>
            dependsOnBinder(qualifier, binders)
          case TermRef(qualifier, _) =>
            dependsOnBinder(qualifier, binders)
          case AppliedType(constructor, arguments) =>
            dependsOnBinder(constructor, binders) ||
              arguments.exists(dependsOnBinder(_, binders))
          case AndType(left, right) =>
            dependsOnBinder(left, binders) ||
              dependsOnBinder(right, binders)
          case OrType(left, right) =>
            dependsOnBinder(left, binders) ||
              dependsOnBinder(right, binders)
          case ByNameType(underlying) =>
            dependsOnBinder(underlying, binders)
          case TypeBounds(low, high) =>
            dependsOnBinder(low, binders) ||
              dependsOnBinder(high, binders)
          case Refinement(parent, _, info) =>
            dependsOnBinder(parent, binders) ||
              dependsOnBinder(info, binders)
          case MatchType(bound, scrutinee, cases) =>
            dependsOnBinder(bound, binders) ||
              dependsOnBinder(scrutinee, binders) ||
              cases.exists(dependsOnBinder(_, binders))
          case AnnotatedType(underlying, _) =>
            dependsOnBinder(underlying, binders)
          case method: MethodType =>
            method.paramTypes.exists(dependsOnBinder(_, binders)) ||
              dependsOnBinder(method.resType, binders)
          case poly: PolyType =>
            poly.paramBounds.exists(dependsOnBinder(_, binders)) ||
              dependsOnBinder(poly.resType, binders)
          case lambda: TypeLambda =>
            lambda.paramBounds.exists(dependsOnBinder(_, binders)) ||
              dependsOnBinder(lambda.resType, binders)
          case recursive: RecursiveType =>
            dependsOnBinder(recursive.underlying, binders)
          case flexible: FlexibleType =>
            dependsOnBinder(flexible.underlying, binders)
          case SuperType(thisType, superType) =>
            dependsOnBinder(thisType, binders) ||
              dependsOnBinder(superType, binders)
          case ThisType(underlying) =>
            dependsOnBinder(underlying, binders)
          case _ => false

      def loop(
          current: TypeRepr,
          binders: List[(TypeRepr, TypeRepr)]
      ): TypeRepr =
        current match
          case parameter: ParamRef =>
            reboundParameter(parameter, binders).getOrElse(parameter)
          case _ =>
            replacements.get(current.termSymbol) match
              case Some(replacement) => replacement.tpe
              case None
                  if !typeDependsOn(current, replacements) &&
                    !dependsOnBinder(current, binders) =>
                current
              case None =>
                current match
                  case reference @ TypeRef(qualifier, _) =>
                    loop(qualifier, binders).select(reference.typeSymbol)
                  case TermRef(qualifier, name) =>
                    TermRef(loop(qualifier, binders), name)
                  case AppliedType(constructor, arguments) =>
                    AppliedType(
                      loop(constructor, binders),
                      arguments.map(loop(_, binders))
                    )
                  case AndType(left, right) =>
                    AndType(loop(left, binders), loop(right, binders))
                  case OrType(left, right) =>
                    OrType(loop(left, binders), loop(right, binders))
                  case ByNameType(underlying) =>
                    ByNameType(loop(underlying, binders))
                  case TypeBounds(low, high) =>
                    TypeBounds(loop(low, binders), loop(high, binders))
                  case Refinement(parent, name, info) =>
                    Refinement(
                      loop(parent, binders),
                      name,
                      loop(info, binders)
                    )
                  case MatchType(bound, scrutinee, cases) =>
                    MatchType(
                      loop(bound, binders),
                      loop(scrutinee, binders),
                      cases.map(loop(_, binders))
                    )
                  case AnnotatedType(underlying, annotation) =>
                    AnnotatedType(
                      loop(underlying, binders),
                      replaceReferences(annotation, replacements)
                    )
                  case method: MethodType =>
                    MethodType(method.methodTypeKind)(method.paramNames)(
                      fresh =>
                        method.paramTypes.map(
                          loop(_, (method, fresh) :: binders)
                        ),
                      fresh =>
                        loop(method.resType, (method, fresh) :: binders)
                    )
                  case poly: PolyType =>
                    PolyType(poly.paramNames)(
                      fresh =>
                        poly.paramBounds.map { bounds =>
                          loop(bounds, (poly, fresh) :: binders)
                            .asInstanceOf[TypeBounds]
                        },
                      fresh => loop(poly.resType, (poly, fresh) :: binders)
                    )
                  case _ =>
                    report.errorAndAbort(
                      "error [DIRECT_STYLE_UNSUPPORTED]: direct.shift cannot rebind this nested owner-dependent definition across capture; move the declaration outside direct.reset",
                      position
                    )

      loop(tpe, Nil)

    def assertNoStaleOwner(
        tree: Tree,
        replacements: Map[Symbol, Term],
        typeReplacements: Map[Symbol, TypeRepr],
        position: Position
    ): Unit =
      var stale: Option[Position] = None
      val replacedTypes = typeReplacements.keySet
      val traverser = new TreeTraverser:
        override def traverseTree(current: Tree)(owner: Symbol): Unit =
          if stale.isEmpty then
            val staleType =
              current match
                case definition: DefDef =>
                  typeDependsOn(
                    definition.symbol.termRef.widenTermRefByName,
                    replacements
                  ) || typeDependsOnSymbols(
                    definition.symbol.termRef.widenTermRefByName,
                    replacedTypes
                  )
                case definition: ValDef =>
                  typeDependsOn(definition.tpt.tpe, replacements) ||
                    typeDependsOnSymbols(definition.tpt.tpe, replacedTypes)
                case term: Term =>
                  typeDependsOn(term.tpe, replacements) ||
                    typeDependsOnSymbols(term.tpe, replacedTypes)
                case typeTree: TypeTree =>
                  typeDependsOn(typeTree.tpe, replacements) ||
                    typeDependsOnSymbols(typeTree.tpe, replacedTypes)
                case _ => false
            val staleReference =
              current match
                case reference: Ident =>
                  replacements.contains(reference.symbol)
                case _ => false
            if staleType || staleReference then stale = Some(current.pos)
            else traverseTreeChildren(current)(owner)

      traverser.traverseTree(tree)(sourceOwner)
      stale.foreach { found =>
        val primary = if found.sourceCode.nonEmpty then found else position
        report.errorAndAbort(
          "error [DIRECT_STYLE_UNSUPPORTED]: direct.shift cannot rebind an owner-dependent moved term across capture; move the declaration outside direct.reset",
          primary
        )
      }

    def repairMovedClosures(
        term: Term,
        owner: Symbol,
        replacements: Map[Symbol, Term],
        typeReplacements: Map[Symbol, TypeRepr]
    ): Term =
      val mapper = new TreeMap:
        override def transformTerm(tree: Term)(currentOwner: Symbol): Term =
          tree match
            case Block(
                  List(definition: DefDef),
                  closure: Closure
                )
                if closure.meth.symbol == definition.symbol &&
                  (typeDependsOn(
                    definition.symbol.termRef.widenTermRefByName,
                    replacements
                  ) || typeDependsOnSymbols(
                    definition.symbol.termRef.widenTermRefByName,
                    typeReplacements.keySet
                  )) =>
              val originalInfo =
                definition.symbol.termRef.widenTermRefByName
              val substitutedInfo =
                if typeReplacements.isEmpty then originalInfo
                else
                  originalInfo.substituteTypes(
                    typeReplacements.keys.toList,
                    typeReplacements.values.toList
                  )
              val reboundInfo =
                rebindDefinitionType(
                  substitutedInfo,
                  replacements,
                  definition.pos
                )
              val fresh = Symbol.newMethod(
                currentOwner,
                definition.name,
                reboundInfo,
                definition.symbol.flags,
                definition.symbol.privateWithin
                  .map(_.typeSymbol)
                  .getOrElse(Symbol.noSymbol)
              )
              val oldParameters = definition.paramss.flatMap {
                case TypeParamClause(parameters) => parameters
                case TermParamClause(parameters) => parameters
              }
              val cloned = DefDef(
                fresh,
                generatedClauses =>
                  var termParameters = replacements
                  var typeParameters = typeReplacements
                  val generatedParameters = generatedClauses.flatten
                  oldParameters.zip(generatedParameters).foreach {
                    case (oldParameter: ValDef, freshReference: Term) =>
                      termParameters = termParameters.updated(
                        oldParameter.symbol,
                        freshReference
                      )
                    case (oldParameter: TypeDef, freshReference: TypeTree) =>
                      typeParameters = typeParameters.updated(
                        oldParameter.symbol,
                        freshReference.tpe
                      )
                    case _ =>
                      report.errorAndAbort(
                        "error [DIRECT_STYLE_UNSUPPORTED]: generated direct-style lambda has an invalid parameter graph",
                        definition.pos
                      )
                  }
                  definition.rhs.map { rightHandSide =>
                    move(
                      rightHandSide,
                      fresh,
                      termParameters,
                      typeParameters
                    )
                  }
              )
              val closureType = closure.tpeOpt.map { original =>
                val substituted =
                  if typeReplacements.isEmpty then original
                  else
                    original.substituteTypes(
                      typeReplacements.keys.toList,
                      typeReplacements.values.toList
                    )
                rebindDefinitionType(
                  substituted,
                  replacements,
                  closure.pos
                )
              }
              Block(List(cloned), Closure(Ref(fresh), closureType))
            case _ => super.transformTerm(tree)(currentOwner)

      mapper.transformTerm(term)(owner)

    def move(
        term: Term,
        owner: Symbol,
        replacements: Map[Symbol, Term],
        typeReplacements: Map[Symbol, TypeRepr] = Map.empty
    ): Term =
      val moved =
        replaceReferences(term, replacements, typeReplacements)
          .changeOwner(owner)
      val repaired =
        repairMovedClosures(
          moved,
          owner,
          replacements,
          typeReplacements
        )
      assertNoStaleOwner(
        repaired,
        replacements,
        typeReplacements,
        term.pos
      )
      repaired

    def rejectNestedMarker(tree: Tree): Unit =
      final case class Barrier(kind: String, position: Position)

      val traverser = new TreeTraverser:
        private var barriers = List.empty[Barrier]
        private var inlineCallPositions = List.empty[Position]

        private def within(
            kind: String,
            position: Position
        )(body: => Unit): Unit =
          barriers = Barrier(kind, position) :: barriers
          try body
          finally barriers = barriers.tail

        private def reject(found: Term): Unit =
          if inlineCallPositions.nonEmpty then
            report.errorAndAbort(
              "error [DIRECT_STYLE_UNSUPPORTED]: direct.shift through an inline wrapper is outside M1; write direct.shift directly at block level",
              inlineCallPositions.head
            )
          else barriers.headOption match
            case Some(Barrier(kind, position)) =>
              report.errorAndAbort(
                s"error [CAPTURE_BARRIER]: direct.shift crosses $kind at line ${position.startLine + 1}, column ${position.startColumn}",
                found.pos
              )
            case None =>
              report.errorAndAbort(
                "error [DIRECT_STYLE_UNSUPPORTED]: direct.shift must be a block-level immutable val bind or the reset tail expression",
                found.pos
              )

        override def traverseTree(tree: Tree)(owner: Symbol): Unit =
          tree match
            case term: Term =>
              marker(term) match
                case Some(found) => reject(found.whole)
                case None =>
                  term match
                    case inlined: Inlined =>
                      val previous = inlineCallPositions
                      inlined.call.foreach { call =>
                        if call.pos.sourceCode.nonEmpty then
                          inlineCallPositions = call.pos :: inlineCallPositions
                      }
                      try traverseTreeChildren(inlined)(owner)
                      finally inlineCallPositions = previous
                    case block: Block =>
                      Lambda.unapply(block) match
                        case Some((_, value)) =>
                          within("a lambda boundary", term.pos) {
                            traverseTree(value)(owner)
                          }
                        case None => traverseTreeChildren(term)(owner)
                    case application @ Apply(function, arguments) =>
                      if calledSymbol(function).flags.is(Flags.Inline) then
                        report.errorAndAbort(
                          "error [DIRECT_STYLE_UNSUPPORTED]: an unexpanded inline application is outside direct.reset M1; write direct.shift directly at block level or move the inline call outside direct.reset",
                          application.pos
                        )
                      else
                        traverseTree(function)(owner)
                        val parameterTypes =
                          function.tpe.widenTermRefByName match
                            case method: MethodType => method.paramTypes
                            case _                  => Nil
                        arguments.zipWithIndex.foreach { (argument, index) =>
                          parameterTypes.lift(index) match
                            case Some(_: ByNameType) =>
                              within(
                                "a by-name argument boundary",
                                application.pos
                              ) {
                                traverseTree(argument)(owner)
                              }
                            case _ => traverseTree(argument)(owner)
                        }
                    case _: Try =>
                      within("a try/finally boundary", term.pos) {
                        traverseTreeChildren(term)(owner)
                      }
                    case _: While =>
                      within("a loop boundary", term.pos) {
                        traverseTreeChildren(term)(owner)
                      }
                    case _: Return =>
                      within("a non-local return boundary", term.pos) {
                        traverseTreeChildren(term)(owner)
                      }
                    case _ => traverseTreeChildren(term)(owner)
            case definition: DefDef =>
              within("a local-method boundary", definition.pos) {
                traverseTreeChildren(definition)(owner)
              }
            case definition: ValDef
                if definition.symbol.flags.is(Flags.Lazy) =>
              within("a lazy-initializer boundary", definition.pos) {
                traverseTreeChildren(definition)(owner)
              }
            case definition: ClassDef =>
              within("a local-class boundary", definition.pos) {
                traverseTreeChildren(definition)(owner)
              }
            case _ => traverseTreeChildren(tree)(owner)

      traverser.traverseTree(tree)(sourceOwner)

    def rejectMarkerInShiftBody(tree: Tree): Unit =
      val traverser = new TreeTraverser:
        private def traverseManagedResetPrompt(
            invocation: Term,
            owner: Symbol
        ): Unit =
          managedResetPrompt(invocation) match
            case Some(promptArgument) => traverseTree(promptArgument)(owner)
            case None =>
              report.errorAndAbort(
                "error [DIRECT_STYLE_UNSUPPORTED]: nested direct.reset call shape inside direct.shift body is outside M1; move it outside direct.shift body",
                invocation.pos
              )

        override def traverseTree(current: Tree)(owner: Symbol): Unit =
          current match
            case Inlined(Some(call: Term), _, _)
                if calledSymbol(call) == directResetSymbol =>
              traverseManagedResetPrompt(call, owner)
            case application: Apply
                if calledSymbol(application) == directResetSymbol =>
              traverseManagedResetPrompt(application, owner)
            case term: Term =>
              marker(term) match
                case Some(found) =>
                  report.errorAndAbort(
                    "error [DIRECT_STYLE_UNSUPPORTED]: direct.shift inside another direct.shift body is outside M1; use scalascript.control.shift explicitly or a nested direct.reset",
                    found.whole.pos
                  )
                case None => traverseTreeChildren(term)(owner)
            case _ => traverseTreeChildren(current)(owner)

      traverser.traverseTree(tree)(sourceOwner)

    val effectfulResultType = TypeRepr.of[Eff[Fx | Control[P], R]]

    def explicitShift(
        found: Marker,
        capturedType: TypeRepr,
        owner: Symbol,
        replacements: Map[Symbol, Term]
    ): Term =
      val actualScope = strip(found.scopeArgument).symbol
      if actualScope != scopeSymbol then
        report.errorAndAbort(
          "error [UNMANAGED_CAPTURE]: direct.shift belongs to a different lexical direct.reset scope",
          found.whole.pos
        )

      rejectMarkerInShiftBody(found.bodyArgument)

      capturedType.asType match
        case '[captured] =>
          val movedPrompt =
            move(found.promptArgument, owner, replacements)
              .asExprOf[Prompt[P, R]]
          val movedBody =
            move(found.bodyArgument, owner, replacements)
              .asExprOf[ShiftBody[P, captured, Fx, R]]
          '{
            scalascript.control.shift[P, captured, Fx, R]($movedPrompt)(
              $movedBody
            )
          }.asTerm.changeOwner(owner)

    def pure(
        value: Term,
        owner: Symbol,
        replacements: Map[Symbol, Term]
    ): Term =
      pureMoved(move(value, owner, replacements), owner)

    def pureMoved(value: Term, owner: Symbol): Term =
      val movedValue = value.asExprOf[R]
      '{ Eff.pure[R]($movedValue) }.asTerm.changeOwner(owner)

    def prepareDependentSuffix(
        statements: List[Statement],
        tail: Term,
        owner: Symbol,
        replacements: Map[Symbol, Term]
    ): Term =
      val definitions = statements.collect { case definition: ValDef =>
        definition
      }
      var current = replacements
      val freshSymbols = definitions.zipWithIndex.map {
        case (definition, index) =>
          val pending = definitions.drop(index).iterator.map { later =>
            later.symbol -> Ref(later.symbol)
          }.toMap
          if typeDependsOn(definition.tpt.tpe, pending) then
            report.errorAndAbort(
              "error [DIRECT_STYLE_UNSUPPORTED]: direct.shift cannot allocate a forward-dependent local type in the captured suffix; move the declaration outside direct.reset",
              definition.pos
            )
          val reboundType =
            rebindType(definition.tpt.tpe, current, definition.pos)
          val fresh = Symbol.newVal(
            owner,
            definition.name,
            reboundType,
            definition.symbol.flags,
            definition.symbol.privateWithin
              .map(_.typeSymbol)
              .getOrElse(Symbol.noSymbol)
          )
          current = current.updated(definition.symbol, Ref(fresh))
          definition.symbol -> fresh
      }.toMap

      val movedStatements = statements.map {
        case definition: ValDef =>
          val rightHandSide = definition.rhs.getOrElse {
            report.errorAndAbort(
              "error [DIRECT_STYLE_UNSUPPORTED]: direct.shift cannot move an uninitialized suffix value",
              definition.pos
            )
          }
          val fresh = freshSymbols(definition.symbol)
          ValDef(fresh, Some(move(rightHandSide, fresh, current)))
        case term: Term => move(term, owner, current)
        case definition =>
          report.errorAndAbort(
            "error [DIRECT_STYLE_UNSUPPORTED]: direct.shift cannot combine an owner-dependent suffix value with a local definition graph; move the definition outside direct.reset",
            definition.pos
          )
      }
      Block(movedStatements, move(tail, owner, current))

    def preparePrefix(
        statements: List[Statement],
        owner: Symbol,
        replacements: Map[Symbol, Term]
    ): (List[Statement], Map[Symbol, Term]) =
      val definitions = statements.collect { case definition: ValDef =>
        definition
      }
      var current = replacements
      val freshSymbols = definitions.zipWithIndex.map {
        case (definition, index) =>
          val pending = definitions.drop(index).iterator.map { later =>
            later.symbol -> Ref(later.symbol)
          }.toMap
          if typeDependsOn(definition.tpt.tpe, pending) then
            report.errorAndAbort(
              "error [DIRECT_STYLE_UNSUPPORTED]: direct.shift cannot allocate a forward-dependent local type across capture; move the declaration outside direct.reset",
              definition.pos
            )

          val reboundType =
            rebindType(definition.tpt.tpe, current, definition.pos)
          val fresh = Symbol.newVal(
            owner,
            definition.name,
            reboundType,
            definition.symbol.flags,
            definition.symbol.privateWithin
              .map(_.typeSymbol)
              .getOrElse(Symbol.noSymbol)
          )
          current = current.updated(definition.symbol, Ref(fresh))
          definition.symbol -> fresh
      }.toMap

      val moved = statements.map {
        case definition: ValDef =>
          if definition.symbol.flags.is(Flags.Lazy) &&
            !definition.symbol.flags.is(Flags.Given)
          then
            report.errorAndAbort(
              "error [DIRECT_STYLE_UNSUPPORTED]: direct.shift cannot carry a lazy local across capture; move it outside direct.reset or make it strict",
              definition.pos
            )

          val rightHandSide = definition.rhs.getOrElse {
            report.errorAndAbort(
              "error [DIRECT_STYLE_UNSUPPORTED]: direct.shift cannot carry an uninitialized local value across capture",
              definition.pos
            )
          }
          val fresh = freshSymbols(definition.symbol)
          val movedRightHandSide = move(rightHandSide, fresh, current)
          ValDef(fresh, Some(movedRightHandSide))

        case definition: DefDef =>
          report.errorAndAbort(
            "error [DIRECT_STYLE_UNSUPPORTED]: direct.shift cannot carry a local method across capture; move it outside direct.reset",
            definition.pos
          )

        case definition: ClassDef =>
          report.errorAndAbort(
            "error [DIRECT_STYLE_UNSUPPORTED]: direct.shift cannot carry a local class across capture; move it outside direct.reset",
            definition.pos
          )

        case definition: TypeDef =>
          report.errorAndAbort(
            "error [DIRECT_STYLE_UNSUPPORTED]: direct.shift cannot carry a local type across capture; move it outside direct.reset",
            definition.pos
          )

        case term: Term => move(term, owner, current)

        case statement =>
          report.errorAndAbort(
            "error [DIRECT_STYLE_UNSUPPORTED]: direct.reset prefix contains an unsupported statement across capture",
            statement.pos
          )
      }
      (moved, current)

    def lowerBlock(
        statements: List[Statement],
        tail: Term,
        owner: Symbol,
        replacements: Map[Symbol, Term]
    ): Term =
      val markerIndex = statements.indexWhere {
        case definition: ValDef =>
          !definition.symbol.flags.is(Flags.Mutable) &&
          !definition.symbol.flags.is(Flags.Lazy) &&
          definition.rhs.exists(value => marker(value).nonEmpty)
        case _ => false
      }

      if markerIndex >= 0 then
        val before = statements.take(markerIndex)
        rejectNestedMarker(Block(before, Literal(UnitConstant())))
        val (movedBefore, prefixReplacements) =
          preparePrefix(before, owner, replacements)

        val definition = statements(markerIndex).asInstanceOf[ValDef]
        val found = marker(definition.rhs.get).get
        val after = statements.drop(markerIndex + 1)
        val capturedType = rebindType(
          found.typeArguments(1).tpe,
          prefixReplacements,
          found.whole.pos
        )
        val continuationType =
          MethodType(List(definition.name))(
            _ => List(capturedType),
            _ => effectfulResultType
          )
        val continuation =
          Lambda(
            owner,
            continuationType,
            (continuationOwner, parameters) =>
              parameters match
                case List(value: Term) =>
                  lowerBlock(
                    after,
                    tail,
                    continuationOwner,
                    prefixReplacements.updated(definition.symbol, value)
                  )
                case _ =>
                  report.errorAndAbort(
                    "error [DIRECT_STYLE_UNSUPPORTED]: generated direct-style continuation has invalid arity",
                    definition.pos
                  )
          )

        val shifted =
          explicitShift(found, capturedType, owner, prefixReplacements)
        val bound = capturedType.asType match
          case '[captured] =>
            val shiftedExpression =
              shifted.asExprOf[Eff[Fx | Control[P], captured]]
            val continuationExpression =
              continuation.asExprOf[
                captured => Eff[Fx | Control[P], R]
              ]
            '{
              $shiftedExpression.flatMap[Fx | Control[P], R](
                $continuationExpression
              )
            }.asTerm.changeOwner(owner)

        if movedBefore.isEmpty then bound else Block(movedBefore, bound)
      else
        marker(tail) match
          case Some(found) =>
            rejectNestedMarker(Block(statements, Literal(UnitConstant())))
            val (movedStatements, prefixReplacements) =
              preparePrefix(statements, owner, replacements)
            val capturedType = rebindType(
              found.typeArguments(1).tpe,
              prefixReplacements,
              found.whole.pos
            )
            if !(capturedType =:= TypeRepr.of[R]) then
              report.errorAndAbort(
                "error [DIRECT_STYLE_UNSUPPORTED]: a tail direct.shift result must equal the reset answer type",
                found.whole.pos
              )
            val shifted =
              explicitShift(found, capturedType, owner, prefixReplacements)
            if movedStatements.isEmpty then shifted
            else Block(movedStatements, shifted)
          case None =>
            val source = Block(statements, tail)
            rejectNestedMarker(source)
            val hasDependentSuffixValue = statements.exists {
              case definition: ValDef =>
                typeDependsOn(definition.tpt.tpe, replacements)
              case _ => false
            }
            if hasDependentSuffixValue then
              pureMoved(
                prepareDependentSuffix(
                  statements,
                  tail,
                  owner,
                  replacements
                ),
                owner
              )
            else pure(source, owner, replacements)

    val (statements, tail) = rawBody match
      case Block(values, result) => (values, result)
      case value                 => (Nil, value)

    rejectDeferredControl(rawBody)

    val lowered =
      lowerBlock(statements, tail, sourceOwner, Map.empty)
        .changeOwner(Symbol.spliceOwner)

    '{
      scalascript.control.reset[P, Fx, R]($prompt)(
        ${ lowered.asExprOf[Eff[Fx | Control[P], R]] }
      )
    }
