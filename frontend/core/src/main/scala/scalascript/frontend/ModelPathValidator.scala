package scalascript.frontend

import scalascript.ast.{ModelDef, ModelFieldType}

/** Validates typed frontend field paths structurally against model descriptors.
 *
 *  Walks a `View` tree, tracking the binding context established by enclosing
 *  `ModelView` and `ForModel` nodes, and calls `ModelPathResolver` for every
 *  path reference found in `ModelText`, `ForModel`, and typed `DataTable`
 *  descriptors.
 *
 *  Only produces errors when the binding var is in scope (i.e., when the type is
 *  known). References to vars not introduced by any enclosing `ModelView`/`ForModel`,
 *  and `DataTable` nodes backed by raw/untyped fetch signals, are silently ignored
 *  — they may be valid in a dynamic context. */
object ModelPathValidator:

  final case class PathError(node: String, varName: String, path: String, message: String):
    override def toString: String = s"$node '$varName.$path': $message"

  /** Validate all typed field paths reachable from `root`.
   *
   *  Returns a (possibly empty) list of validation errors.
   *  Pass `models = Nil` to skip validation entirely (legacy modules). */
  def validate(root: View[?], models: List[ModelDef]): List[PathError] =
    if models.isEmpty then Nil
    else walk(root, Map.empty, models, Nil)

  /** Validate all components in a `FrontendModule` whose `models` list is non-empty. */
  def validateModule(module: FrontendModule): List[PathError] =
    if module.models.isEmpty then Nil
    else module.components.flatMap { comp =>
      try validate(comp.body(()), module.models)
      catch case _: Exception => Nil
    }

  // ── internals ────────────────────────────────────────────────────────────

  // ctx: binding var name → model type name
  private def walk(
      view:   View[?],
      ctx:    Map[String, String],
      models: List[ModelDef],
      acc:    List[PathError]
  ): List[PathError] =
    view match

      case View.ModelView(signal, bindingVar, template, _) =>
        val modelTypeName = signal.codec match
          case CodecHint.Json(name) => name
          case _                    => ""
        val childCtx = if modelTypeName.nonEmpty then ctx + (bindingVar -> modelTypeName) else ctx
        walk(template, childCtx, models, acc)

      case View.ForModel(bindingVar, fieldPath, itemVar, template, _) =>
        val errors = ctx.get(bindingVar) match
          case None => acc  // bindingVar not in scope — skip validation
          case Some(parentType) =>
            ModelPathResolver.resolve(fieldPath, parentType, models) match
              case Left(msg)  => PathError("ForModel", bindingVar, fieldPath, msg) :: acc
              case Right(tpe) =>
                ModelPathResolver.elementType(tpe) match
                  case Left(msg)  => PathError("ForModel", bindingVar, fieldPath, msg) :: acc
                  case Right(_)   => acc
        val itemCtx = ctx.get(bindingVar) match
          case None => ctx
          case Some(parentType) =>
            ModelPathResolver.resolve(fieldPath, parentType, models) match
              case Right(tpe) =>
                ModelPathResolver.elementType(tpe) match
                  case Right(ModelFieldType.Nested(itemTypeName)) => ctx + (itemVar -> itemTypeName)
                  case _ => ctx
              case _ => ctx
        walk(template, itemCtx, models, errors)

      case View.ModelText(varName, fieldPath, _) =>
        ctx.get(varName) match
          case None => acc  // var not in scope — skip
          case Some(typeName) =>
            ModelPathResolver.resolve(fieldPath, typeName, models) match
              case Right(_)  => acc
              case Left(msg) => PathError("ModelText", varName, fieldPath, msg) :: acc

      case dt: View.DataTable =>
        validateDataTable(dt, models, acc)

      // ── tree walk for all container nodes ───────────────────────────────
      case View.Fragment(children)              => children.foldLeft(acc)((a, c) => walk(c, ctx, models, a))
      case View.Column(children, _, _, _)       => children.foldLeft(acc)((a, c) => walk(c, ctx, models, a))
      case View.Row(children, _, _, _)          => children.foldLeft(acc)((a, c) => walk(c, ctx, models, a))
      case View.Stack(children, _)              => children.foldLeft(acc)((a, c) => walk(c, ctx, models, a))
      case View.ScrollView(child, _, _)         => walk(child, ctx, models, acc)
      case View.TabBar(tabs, _, _)              => tabs.foldLeft(acc)((a, t) => walk(t.content, ctx, models, a))
      case View.NavigationStack(routes, _, _)   =>
        routes.values.foldLeft(acc)((a, fn) => walk(fn(), ctx, models, a))
      case View.ShowSignal(_, whenTrue, whenFalse) =>
        walk(whenFalse, ctx, models, walk(whenTrue, ctx, models, acc))
      case View.Show(_, whenTrue, whenFalse)    =>
        walk(whenFalse(), ctx, models, walk(whenTrue(), ctx, models, acc))
      case View.Styled(child, _)                => walk(child, ctx, models, acc)
      case View.SafeArea(child, _)              => walk(child, ctx, models, acc)
      case View.Form(body, _, _)                => walk(body, ctx, models, acc)
      case View.Element(_, _, _, children)      => children.foldLeft(acc)((a, c) => walk(c, ctx, models, a))
      case View.Adaptive(web, desktop, mobile, fallback) =>
        List(web, desktop, mobile).flatten.foldLeft(walk(fallback, ctx, models, acc))((a, c) => walk(c, ctx, models, a))
      case View.Portal(_, children)             => children.foldLeft(acc)((a, c) => walk(c, ctx, models, a))
      case View.ForSignal(_, _, _, Some(tmpl))  => walk(tmpl, ctx, models, acc)
      case View.ComponentInstance(comp, props)  =>
        try walk(comp.render(props.asInstanceOf[Nothing]), ctx, models, acc)
        catch case _: Exception => acc
      case _                                    => acc

  private def validateDataTable(
      dt:     View.DataTable,
      models: List[ModelDef],
      acc:    List[PathError]
  ): List[PathError] =
    val sigOpt: Option[FetchUrlSignal] = dt.source match
      case TableDataSource.Remote(sig, _) => Some(sig)
      case _                           => None
    sigOpt.flatMap(rowModelName) match
      case None => acc
      case Some(rowModel) =>
        val withColumns = dt.columns.foldLeft(acc) { (errors, col) =>
          val withField = validatePath("DataTableColumn", rowModel, col.fieldPath, rowModel, models, errors)
          col.editAction match
            case Some(edit) => validatePath("DataTableInlineEdit", rowModel, edit.idField, rowModel, models, withField)
            case None       => withField
        }
        dt.actions.foldLeft(withColumns) {
          case (errors, RowActionDef.RowDelete(_, idField, _, _)) =>
            validatePath("DataTableRowDelete", rowModel, idField, rowModel, models, errors)
          case (errors, RowActionDef.RowPost(_, _, _, payload, _, _)) =>
            payload match
              case RowPayload.Field(name)   => validatePath("DataTableRowPost", rowModel, name, rowModel, models, errors)
              case RowPayload.WholeRow      => errors
              case RowPayload.Fields(names) => names.foldLeft(errors)((e, n) => validatePath("DataTableRowPost", rowModel, n, rowModel, models, e))
          case (errors, RowActionDef.RowLink(_, _, fieldPath)) =>
            validatePath("DataTableRowLink", rowModel, fieldPath, rowModel, models, errors)
          case (errors, RowActionDef.RowInlineEdit(_, _, idField, _, _)) =>
            validatePath("DataTableInlineEdit", rowModel, idField, rowModel, models, errors)
        }

  private def rowModelName(signal: FetchUrlSignal): Option[String] =
    signal.codec match
      case CodecHint.Json(name) if name.nonEmpty => Some(name)
      case _                                     => None

  private def validatePath(
      node:          String,
      varName:       String,
      path:          String,
      rootModelName: String,
      models:        List[ModelDef],
      acc:           List[PathError]
  ): List[PathError] =
    ModelPathResolver.resolve(path, rootModelName, models) match
      case Right(_)  => acc
      case Left(msg) => PathError(node, varName, path, msg) :: acc
