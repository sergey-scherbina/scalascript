package scalascript.control

import scala.annotation.compileTimeOnly
import scala.annotation.implicitNotFound
import scala.quoted.Expr
import scala.quoted.Quotes
import scala.quoted.Type

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

    def rejectEscapingReturns(tree: Tree): Unit =
      def isOwnedBy(symbol: Symbol, ancestor: Symbol): Boolean =
        @annotation.tailrec
        def loop(current: Symbol): Boolean =
          if current == Symbol.noSymbol then false
          else if current == ancestor then true
          else loop(current.owner)
        loop(symbol)

      val traverser = new TreeTraverser:
        override def traverseTree(current: Tree)(owner: Symbol): Unit =
          current match
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

    def replaceReferences(
        term: Term,
        replacements: Map[Symbol, Term]
    ): Term =
      val mapper = new TreeMap:
        override def transformTerm(tree: Term)(owner: Symbol): Term =
          tree match
            case reference: Ident =>
              replacements.getOrElse(
                reference.symbol,
                super.transformTerm(tree)(owner)
              )
            case _ => super.transformTerm(tree)(owner)
      mapper.transformTerm(term)(sourceOwner)

    def move(
        term: Term,
        owner: Symbol,
        replacements: Map[Symbol, Term]
    ): Term =
      replaceReferences(term, replacements).changeOwner(owner)

    def rejectNestedMarker(tree: Tree): Unit =
      final case class Barrier(kind: String, position: Position)

      def calledSymbol(term: Term): Symbol =
        term match
          case Apply(function, _)     => calledSymbol(function)
          case TypeApply(function, _) => calledSymbol(function)
          case Typed(function, _)     => calledSymbol(function)
          case value                  => value.symbol

      val traverser = new TreeTraverser:
        private var barriers = List.empty[Barrier]
        private var unsafeInlineDepth = 0

        private def within(
            kind: String,
            position: Position
        )(body: => Unit): Unit =
          barriers = Barrier(kind, position) :: barriers
          try body
          finally barriers = barriers.tail

        private def reject(found: Term): Unit =
          if unsafeInlineDepth > 0 then
            report.errorAndAbort(
              "error [DIRECT_STYLE_UNSUPPORTED]: direct.shift through an inline wrapper is outside M1; write direct.shift directly at block level",
              found.pos
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
                      unsafeInlineDepth += 1
                      try traverseTreeChildren(inlined)(owner)
                      finally unsafeInlineDepth -= 1
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

    val effectfulResultType = TypeRepr.of[Eff[Fx | Control[P], R]]

    def explicitShift(
        found: Marker,
        owner: Symbol,
        replacements: Map[Symbol, Term]
    ): Term =
      val actualScope = strip(found.scopeArgument).symbol
      if actualScope != scopeSymbol then
        report.errorAndAbort(
          "error [UNMANAGED_CAPTURE]: direct.shift belongs to a different lexical direct.reset scope",
          found.whole.pos
        )

      found.typeArguments(1).tpe.asType match
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
      val movedValue = move(value, owner, replacements).asExprOf[R]
      '{ Eff.pure[R]($movedValue) }.asTerm.changeOwner(owner)

    def preparePrefix(
        statements: List[Statement],
        owner: Symbol,
        replacements: Map[Symbol, Term]
    ): (List[Statement], Map[Symbol, Term]) =
      var current = replacements
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
          val fresh = Symbol.newVal(
            owner,
            definition.name,
            definition.tpt.tpe,
            definition.symbol.flags,
            definition.symbol.privateWithin
              .map(_.typeSymbol)
              .getOrElse(Symbol.noSymbol)
          )
          val cloned = ValDef(fresh, Some(move(rightHandSide, owner, current)))
          current = current.updated(definition.symbol, Ref(fresh))
          cloned

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
        val capturedType = found.typeArguments(1).tpe
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

        val shifted = explicitShift(found, owner, prefixReplacements)
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
            val capturedType = found.typeArguments(1).tpe
            if !(capturedType =:= TypeRepr.of[R]) then
              report.errorAndAbort(
                "error [DIRECT_STYLE_UNSUPPORTED]: a tail direct.shift result must equal the reset answer type",
                found.whole.pos
              )
            rejectNestedMarker(Block(statements, Literal(UnitConstant())))
            val (movedStatements, prefixReplacements) =
              preparePrefix(statements, owner, replacements)
            val shifted = explicitShift(found, owner, prefixReplacements)
            if movedStatements.isEmpty then shifted
            else Block(movedStatements, shifted)
          case None =>
            val source = Block(statements, tail)
            rejectNestedMarker(source)
            pure(source, owner, replacements)

    val (statements, tail) = rawBody match
      case Block(values, result) => (values, result)
      case value                 => (Nil, value)

    rejectEscapingReturns(rawBody)

    val lowered =
      lowerBlock(statements, tail, sourceOwner, Map.empty)
        .changeOwner(Symbol.spliceOwner)

    '{
      scalascript.control.reset[P, Fx, R]($prompt)(
        ${ lowered.asExprOf[Eff[Fx | Control[P], R]] }
      )
    }
