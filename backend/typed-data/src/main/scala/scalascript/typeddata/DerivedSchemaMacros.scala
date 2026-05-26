package scalascript.typeddata

import scala.quoted.*

private[typeddata] object DerivedSchemaMacros:
  def jsonProduct[A: Type](mirror: Expr[scala.deriving.Mirror.ProductOf[A]])(using Quotes): Expr[JsonCodec[A]] =
    import quotes.reflect.*
    val owner = TypeRepr.of[A].typeSymbol
    val fields = productFields[A].map((symbols, index, name, tpe) => jsonFieldExpr(symbols, index, name, tpe))
    val reject = hasRejectUnknown(owner)
    '{
      DerivedProductCodecs.jsonProductCodec[A](
        $mirror,
        Vector(${Varargs(fields)}*),
        ${Expr(reject)}
      )
    }

  def rowProduct[A: Type](mirror: Expr[scala.deriving.Mirror.ProductOf[A]])(using Quotes): Expr[RowCodec[A]] =
    import quotes.reflect.*
    val owner = TypeRepr.of[A].typeSymbol
    val fields = productFields[A].map((symbols, index, name, tpe) => rowFieldExpr(symbols, index, name, tpe))
    val reject = hasRejectUnknown(owner)
    '{
      DerivedProductCodecs.rowProductCodec[A](
        $mirror,
        Vector(${Varargs(fields)}*),
        ${Expr(reject)}
      )
    }

  def objectProduct[A: Type](mirror: Expr[scala.deriving.Mirror.ProductOf[A]])(using Quotes): Expr[ObjectCodec[A]] =
    import quotes.reflect.*
    val owner = TypeRepr.of[A].typeSymbol
    val fields = productFields[A].map((symbols, index, name, tpe) => jsonFieldExpr(symbols, index, name, tpe))
    val reject = hasRejectUnknown(owner)
    '{
      DerivedProductCodecs.objectProductCodec[A](
        $mirror,
        Vector(${Varargs(fields)}*),
        ${Expr(reject)}
      )
    }

  def vertexProduct[A: Type](mirror: Expr[scala.deriving.Mirror.ProductOf[A]])(using Quotes): Expr[VertexCodec[A]] =
    import quotes.reflect.*
    val owner = TypeRepr.of[A].typeSymbol
    val fields = productFields[A].map((symbols, index, name, tpe) => jsonFieldExpr(symbols, index, name, tpe))
    val label = stringAnnotation(owner, "scalascript.typeddata.graphLabel").getOrElse(owner.name)
    '{
      DerivedProductCodecs.vertexProductCodec[A](
        $mirror,
        ${Expr(label)},
        Vector(${Varargs(fields)}*)
      )
    }

  def edgeProduct[A: Type](mirror: Expr[scala.deriving.Mirror.ProductOf[A]])(using Quotes): Expr[EdgeCodec[A]] =
    import quotes.reflect.*
    val owner = TypeRepr.of[A].typeSymbol
    val fields = productFields[A].map((symbols, index, name, tpe) => jsonFieldExpr(symbols, index, name, tpe))
    val label = stringAnnotation(owner, "scalascript.typeddata.graphEdge").getOrElse(owner.name)
    '{
      DerivedProductCodecs.edgeProductCodec[A](
        $mirror,
        ${Expr(label)},
        Vector(${Varargs(fields)}*)
      )
    }

  def rdfProduct[A: Type](mirror: Expr[scala.deriving.Mirror.ProductOf[A]])(using Quotes): Expr[RdfCodec[A]] =
    import quotes.reflect.*
    val owner = TypeRepr.of[A].typeSymbol
    val fields = productFields[A].map((symbols, index, name, tpe) => jsonFieldExpr(symbols, index, name, tpe))
    val classIri = stringAnnotation(owner, "scalascript.typeddata.rdfClass")
    '{
      DerivedProductCodecs.rdfProductCodec[A](
        $mirror,
        ${Expr(classIri)},
        Vector(${Varargs(fields)}*)
      )
    }

  private def productFields[A: Type](using q: Quotes): List[(List[q.reflect.Symbol], Int, String, q.reflect.TypeRepr)] =
    import quotes.reflect.*
    val tpe = TypeRepr.of[A]
    val sym = tpe.typeSymbol
    if !sym.flags.is(Flags.Case) then
      report.errorAndAbort(s"${sym.fullName} is not a case class")
    val constructorParams = sym.primaryConstructor.paramSymss.flatten
    sym.caseFields.zipWithIndex.map { (field, index) =>
      val param = constructorParams.lift(index).toList
      (field :: param, index, field.name, tpe.memberType(field))
    }

  private def jsonFieldExpr(using q: Quotes)(
      symbols: List[q.reflect.Symbol],
      index: Int,
      fallbackName: String,
      tpe: q.reflect.TypeRepr
  ): Expr[JsonProductField] =
    import quotes.reflect.*
    val meta = metadata(symbols, fallbackName)
    tpe.asType match
      case '[t] =>
        val codec = Expr.summon[JsonCodec[t]].getOrElse {
          report.errorAndAbort(s"no JsonCodec found for field '$fallbackName'")
        }
        val defaultExpr = defaultValueExpr[t](symbols.head.owner, index)
        '{
          JsonProductField(
            ${Expr(meta.name)},
            ${Expr.ofList(meta.aliases.map(Expr(_)))},
            ${Expr(meta.key)},
            (raw: Any) => $codec.encode(raw.asInstanceOf[t]),
            (fields: Map[String, JsonValue]) =>
              val spec = JsonFieldSpec[t](
                ${Expr(meta.name)},
                ${Expr.ofList(meta.aliases.map(Expr(_)))},
                $defaultExpr,
                ${Expr(meta.key)}
              )(using $codec)
              JsonCodec.field(fields, spec).map(_.asInstanceOf[Any]),
            ${Expr(meta.graphRole)},
            ${Expr(meta.rdfId)},
            ${Expr(meta.rdfPredicate)}
          )
        }

  private def rowFieldExpr(using q: Quotes)(
      symbols: List[q.reflect.Symbol],
      index: Int,
      fallbackName: String,
      tpe: q.reflect.TypeRepr
  ): Expr[RowProductField] =
    import quotes.reflect.*
    val meta = metadata(symbols, fallbackName)
    tpe.asType match
      case '[t] =>
        val codec = Expr.summon[RowValueCodec[t]].getOrElse {
          report.errorAndAbort(s"no RowValueCodec found for field '$fallbackName'")
        }
        val defaultExpr = defaultValueExpr[t](symbols.head.owner, index)
        '{
          RowProductField(
            ${Expr(meta.name)},
            ${Expr.ofList(meta.aliases.map(Expr(_)))},
            ${Expr(meta.key)},
            (raw: Any) => $codec.encode(raw.asInstanceOf[t]),
            (row: Map[String, RowValue]) =>
              val spec = RowFieldSpec[t](
                ${Expr(meta.name)},
                ${Expr.ofList(meta.aliases.map(Expr(_)))},
                $defaultExpr,
                ${Expr(meta.key)}
              )(using $codec)
              RowCodec.field(row, spec).map(_.asInstanceOf[Any])
          )
        }

  private final case class Metadata(
      name: String,
      aliases: List[String],
      key: Boolean,
      graphRole: String,
      rdfId: Boolean,
      rdfPredicate: Option[String]
  )

  private def metadata(using Quotes)(symbols: List[quotes.reflect.Symbol], fallbackName: String): Metadata =
    val name = symbols.iterator.flatMap(stringAnnotation(_, "scalascript.typeddata.fieldName")).toSeq.headOption.getOrElse(fallbackName)
    val aliases = symbols.flatMap(stringSeqAnnotation(_, "scalascript.typeddata.aliases")).distinct
    val isKey = symbols.exists(hasAnnotation(_, "scalascript.typeddata.key"))
    val graphRole =
      if symbols.exists(hasAnnotation(_, "scalascript.typeddata.graphFrom")) then "from"
      else if symbols.exists(hasAnnotation(_, "scalascript.typeddata.graphTo")) then "to"
      else ""
    val isRdfId = symbols.exists(hasAnnotation(_, "scalascript.typeddata.rdfId"))
    val rdfPredicate = symbols.iterator.flatMap(stringAnnotation(_, "scalascript.typeddata.rdf")).toSeq.headOption
    Metadata(name, aliases, isKey, graphRole, isRdfId, rdfPredicate)

  private def defaultValueExpr[A: Type](using Quotes)(
      owner: quotes.reflect.Symbol,
      fieldIndex: Int
  ): Expr[Option[A]] =
    import quotes.reflect.*
    val companion = owner.companionModule
    val methodNames = List(
      s"apply$$default$$${fieldIndex + 1}",
      s"$$lessinit$$greater$$default$$${fieldIndex + 1}"
    )
    val defaultMethod = methodNames.iterator.flatMap(name => companion.methodMember(name)).toSeq.headOption
    defaultMethod match
      case Some(method) =>
        Select(Ref(companion), method).asExprOf[A] match
          case value => '{ Some[A]($value) }
      case None => '{ None }

  private def hasRejectUnknown(using Quotes)(symbol: quotes.reflect.Symbol): Boolean =
    hasAnnotation(symbol, "scalascript.typeddata.rejectUnknown")

  private def hasAnnotation(using Quotes)(symbol: quotes.reflect.Symbol, fullName: String): Boolean =
    symbol.annotations.exists(annotationFullName(_) == fullName)

  private def stringAnnotation(using Quotes)(symbol: quotes.reflect.Symbol, fullName: String): Option[String] =
    symbol.annotations.find(annotationFullName(_) == fullName).flatMap(annotationArgs).flatMap(_.headOption).flatMap(stringLiteral)

  private def stringSeqAnnotation(using Quotes)(symbol: quotes.reflect.Symbol, fullName: String): List[String] =
    symbol.annotations.find(annotationFullName(_) == fullName).toList.flatMap(annotationArgs).flatMap(_.flatMap(stringsFromArg))

  private def annotationFullName(using Quotes)(tree: quotes.reflect.Term): String =
    tree.tpe.typeSymbol.fullName

  private def annotationArgs(using Quotes)(tree: quotes.reflect.Term): Option[List[quotes.reflect.Term]] =
    import quotes.reflect.*
    tree match
      case Apply(_, args) => Some(args)
      case _ => None

  private def stringsFromArg(using Quotes)(tree: quotes.reflect.Term): List[String] =
    import quotes.reflect.*
    tree match
      case Repeated(args, _) => args.flatMap(stringLiteral)
      case Apply(_, args) => args.flatMap(stringsFromArg)
      case TypeApply(term, _) => stringsFromArg(term)
      case Select(term, _) => stringsFromArg(term)
      case Typed(term, _) => stringsFromArg(term)
      case NamedArg(_, term) => stringsFromArg(term)
      case Block(_, term) => stringsFromArg(term)
      case Inlined(_, _, inner) => stringsFromArg(inner)
      case other => stringLiteral(other).toList

  private def stringLiteral(using Quotes)(tree: quotes.reflect.Term): Option[String] =
    import quotes.reflect.*
    tree match
      case Literal(StringConstant(value)) => Some(value)
      case Inlined(_, _, inner) => stringLiteral(inner)
      case _ => None
