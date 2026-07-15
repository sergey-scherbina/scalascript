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

    def marker(term: Term): Option[Marker] =
      strip(term) match
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

      val traverser = new TreeTraverser:
        private var barriers = List.empty[Barrier]

        private def within(
            kind: String,
            position: Position
        )(body: => Unit): Unit =
          barriers = Barrier(kind, position) :: barriers
          try body
          finally barriers = barriers.tail

        private def reject(found: Term): Unit =
          barriers.headOption match
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
                    case block: Block =>
                      Lambda.unapply(block) match
                        case Some((_, value)) =>
                          within("a lambda boundary", term.pos) {
                            traverseTree(value)(owner)
                          }
                        case None => traverseTreeChildren(term)(owner)
                    case application @ Apply(function, arguments) =>
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

    def prefix(
        statements: List[Statement],
        result: Term,
        owner: Symbol,
        replacements: Map[Symbol, Term]
    ): Term =
      if statements.isEmpty then result
      else
        val moved =
          move(
            Block(statements, Literal(UnitConstant())),
            owner,
            replacements
          )
        moved match
          case Block(movedStatements, _) => Block(movedStatements, result)
          case _ =>
            report.errorAndAbort(
              "error [DIRECT_STYLE_UNSUPPORTED]: direct.reset prefix could not be normalized",
              statements.head.pos
            )

    def lowerBlock(
        statements: List[Statement],
        tail: Term,
        owner: Symbol,
        replacements: Map[Symbol, Term]
    ): Term =
      val markerIndex = statements.indexWhere {
        case definition: ValDef =>
          !definition.symbol.flags.is(Flags.Mutable) &&
          definition.rhs.exists(value => marker(value).nonEmpty)
        case _ => false
      }

      if markerIndex >= 0 then
        val before = statements.take(markerIndex)
        rejectNestedMarker(Block(before, Literal(UnitConstant())))

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
                    replacements.updated(definition.symbol, value)
                  )
                case _ =>
                  report.errorAndAbort(
                    "error [DIRECT_STYLE_UNSUPPORTED]: generated direct-style continuation has invalid arity",
                    definition.pos
                  )
          )

        val shifted = explicitShift(found, owner, replacements)
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

        prefix(before, bound, owner, replacements)
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
            prefix(
              statements,
              explicitShift(found, owner, replacements),
              owner,
              replacements
            )
          case None =>
            val source = Block(statements, tail)
            rejectNestedMarker(source)
            pure(source, owner, replacements)

    val (statements, tail) = rawBody match
      case Block(values, result) => (values, result)
      case value                 => (Nil, value)

    val lowered =
      lowerBlock(statements, tail, sourceOwner, Map.empty)
        .changeOwner(Symbol.spliceOwner)

    '{
      scalascript.control.reset[P, Fx, R]($prompt)(
        ${ lowered.asExprOf[Eff[Fx | Control[P], R]] }
      )
    }
