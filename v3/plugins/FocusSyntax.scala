package ssc3.plugins

import ssc3.{Expr, Param, Plugins, Pos}

/** `Focus[S](_.a.b)` — a LENS, read out of the path's SYNTAX.
  *
  * THIS IS THE CASE THE WHOLE DOOR WAS BUILT FOR. `Focus` receives `_.address.city` as a FUNCTION
  * when it is an ordinary call, and a function cannot be asked which fields it selected. The rewrite
  * gets the tree instead, reads the names off it, and emits:
  *
  *   Focus[Person](_.address.city)
  *     ──▶  LensOptic(s => s.address.city,
  *                    (s, v) => s.copy(address = s.address.copy(city = v)))
  *
  * THE SETTER IS THE NESTED `copy` A PERSON WOULD HAVE WRITTEN, which `lenses.ssc` writes by hand
  * three lines above the first `Focus` — so the rewrite emits nothing the language did not already
  * compile, and `Lower` sees an ordinary `MethodCall` with a `NamedArg` (rule 6).
  *
  * WHAT IT DOES NOT DO YET, said plainly rather than discovered later: `.some`, `.each`, `.index(i)`
  * and `.at(k)` are steps in the same path grammar and are NOT lenses — they may miss, or they may
  * hit many — so they need `Optional` and `Traversal` and are refused here by name. `optional`,
  * `optics-index-at`, `traversal` and `optic-polish` stay declared one-sided until then.
  */
object FocusSyntax:

  /** The path as field names, or the step that is not a plain field. `_` is the root. */
  private def pathOf(e: Expr): Either[String, List[String]] = e match
    case Expr.Name("_", _)          => Right(Nil)
    // A FIELD SELECTION IS `MethodRef` HERE. v3 has no separate `Sel` node: `x.f` and `x.f()` are
    // the same shape until the lowering decides, which is why the arm below has to tell a plain
    // field from a CALL on the path rather than matching one node kind.
    case Expr.MethodRef(inner, field, _) => pathOf(inner).map(_ :+ field)
    // `.some`, `.each` and friends arrive as ordinary CALLS or selections on the path, and telling
    // the author which step is not a lens beats telling them the shape is wrong.
    case Expr.MethodCall(_, m, _, _) => Left(m)
    case Expr.Call(f, _, _)          => Left(f)
    case other                       => Left("this step")

  /** THE STEPS THAT ARE NOT LENSES, AND THEY ARE TOLD APART BY NAME RATHER THAN BY SHAPE — which is
    * a fact about the path grammar, not a shortcut. `.index(1)` and `.at("k")` are CALLS, so the
    * tree distinguishes them; `.some` and `.each` are ordinary SELECTIONS, identical in shape to a
    * field called `some`. Measured: the first version of this file checked the node kind and let
    * `_.profile.some.city` through as a three-field lens, which would have set a field named `some`
    * and failed at run time with the plugin's name nowhere near the error.
    *
    * The cost is stated rather than hidden: a case class with a field genuinely called `some`,
    * `each`, `index` or `at` cannot be focused through until those kinds exist. */
  private val notLensSteps = Set("some", "each", "index", "at")

  private def rewrite(m: Expr.Marker, ctx: Plugins.Ctx): Either[Plugins.Refusal, Expr] =
    def notALens(step: String) = Left(Plugins.Refusal(
      "Focus cannot take '" + step + "' yet — it is not a lens: a lens always hits, and '" + step +
      "' may miss or may hit many. Optional and Traversal are the kinds it needs", m.pos))
    m.args match
      case List(path) =>
        pathOf(path) match
          case Left(step) if notLensSteps.contains(step) => notALens(step)
          case Right(fields) if fields.exists(notLensSteps.contains) =>
            notALens(fields.find(notLensSteps.contains).get)
          case Left(step) =>
            Left(Plugins.Refusal(
              "Focus takes a path of plain fields — `Focus[S](_.a.b)` — and '" + step +
              "' is not one", m.pos))
          case Right(Nil) =>
            Left(Plugins.Refusal("Focus needs at least one field: `Focus[S](_.a)`", m.pos))
          case Right(fields) =>
            val p = m.pos
            val s = ctx.fresh("focus")
            val v = ctx.fresh("focus")
            def sel(base: Expr, fs: List[String]): Expr = fs.foldLeft(base)((acc, f) => Expr.MethodRef(acc, f, p))
            // THE NESTED `copy`, innermost last: each step copies the object at its own depth,
            // reached by the same selection chain the getter uses. `s.copy(a = s.a.copy(b = v))`.
            def setter(depth: Int): Expr =
              val here = sel(Expr.Name(s, p), fields.take(depth))
              val field = fields(depth)
              val value = if depth == fields.length - 1 then Expr.Name(v, p) else setter(depth + 1)
              Expr.MethodCall(here, "copy", List(Expr.NamedArg(field, value, p)), p)
            Right(Expr.Call("LensOptic", List(
              Expr.Lambda(List(Param(s, p)), sel(Expr.Name(s, p), fields), p),
              Expr.Lambda(List(Param(s, p), Param(v, p)), setter(0), p)), p))
      case _ => Left(Plugins.Refusal("Focus takes one path: `Focus[S](_.a.b)`", m.pos))

  def install(): Unit = Plugins.registerRewrite("Focus", rewrite)
