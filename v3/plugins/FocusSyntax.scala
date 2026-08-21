package ssc3.plugins

import ssc3.{Expr, Param, Plugins, Pos}

/** `Focus[S](_.a.some.b)` — a path read out of SYNTAX and composed into an optic.
  *
  * THIS IS THE CASE THE WHOLE DOOR WAS BUILT FOR. `Focus` receives `_.address.city` as a FUNCTION
  * when it is an ordinary call, and a function cannot be asked which fields it selected. The rewrite
  * gets the tree instead.
  *
  * WHAT IT EMITS: one call per step, composed with `andThen`, into `std/optics.ssc` —
  *
  *   Focus[Wrap](_.point.some.y)
  *     ──▶  opticField("point", s => s.point, (s, v) => s.copy(point = v))
  *            .andThen(opticSome())
  *            .andThen(opticField("y", s => s.y, (s, v) => s.copy(y = v)))
  *
  * A FIELD STEP CARRIES ITS OWN GETTER AND SETTER, which is the whole reason the library is portable:
  * reading a field by NAME at run time needs reflection, and the reference pays for it with a host
  * plugin. Here the front already knows the names, so it passes two ordinary closures and the library
  * reflects on nothing. `.some`, `.each`, `.index(i)` and `.at(k)` need no names at all.
  *
  * THE KIND IS NOT DECIDED HERE either — `opticJoinKind` does it while composing, so `Lens.andThen
  * (Traversal)` is a traversal by the same rule whether the two halves came from one `Focus` or from
  * a user's own `andThen` of two of them. Deciding it in the rewrite would have made those two
  * disagree.
  */
object FocusSyntax:

  /** One step of the path, in source order. `Field` carries the name; the rest carry their argument. */
  private enum Step:
    case Field(name: String)
    case Some_
    case Each
    case Index(arg: Expr)
    case At(arg: Expr)

  /** The path, or the step this rewrite cannot read. `_` is the root.
    *
    * TOLD APART BY NAME, NOT BY SHAPE, and that was measured: `.index(1)` and `.at("k")` are CALLS,
    * so the tree distinguishes them — but `.some` and `.each` are ordinary SELECTIONS, identical in
    * shape to a field of that name. An earlier version checked the node kind and let
    * `_.profile.some.city` through as a three-field lens, which would have SET a field called `some`
    * and failed at run time with the plugin's name nowhere near the error.
    *
    * The cost is stated rather than hidden: a case class with a field genuinely called `some`,
    * `each`, `index` or `at` cannot be focused through. */
  private def pathOf(e: Expr): Either[String, List[Step]] = e match
    case Expr.Name("_", _)                    => Right(Nil)
    case Expr.MethodRef(inner, "some", _)     => pathOf(inner).map(_ :+ Step.Some_)
    case Expr.MethodRef(inner, "each", _)     => pathOf(inner).map(_ :+ Step.Each)
    case Expr.MethodRef(inner, field, _)      => pathOf(inner).map(_ :+ Step.Field(field))
    case Expr.MethodCall(inner, "index", List(a), _) => pathOf(inner).map(_ :+ Step.Index(a))
    case Expr.MethodCall(inner, "at", List(a), _)    => pathOf(inner).map(_ :+ Step.At(a))
    case Expr.MethodCall(_, m, _, _)          => Left(m)
    case Expr.Call(f, _, _)                   => Left(f)
    case _                                    => Left("this step")

  private def rewrite(m: Expr.Marker, ctx: Plugins.Ctx): Either[Plugins.Refusal, Expr] =
    m.args match
      case List(path) =>
        pathOf(path) match
          case Left(step) =>
            Left(Plugins.Refusal(
              "Focus takes a path of fields and optic steps — `.some`, `.each`, `.index(i)`, " +
              "`.at(k)` — and '" + step + "' is neither", m.pos))
          case Right(Nil) =>
            Left(Plugins.Refusal("Focus needs at least one step: `Focus[S](_.a)`", m.pos))
          case Right(steps) =>
            val p = m.pos
            Right(steps.map(one(_, ctx, p)).reduceLeft((acc, s) =>
              Expr.MethodCall(acc, "andThen", List(s), p)))
      // Neither front can build this shape — both give `Focus` exactly one argument — so it is about
      // a rewrite that MINTED a `Focus` marker, which rule 2 allows. Present because a match with no
      // fallback is a compiler WARNING, and a permanent warning is a permanent hazard: this
      // repository has had one land in a gate's captured stderr and read as a program producing
      // different output.
      case _ => Left(Plugins.Refusal("Focus takes one path: `Focus[S](_.a.b)`", m.pos))

  /** One step as a call into the library. The two closures a FIELD needs are built here because this
    * is the only place that knows the name; `Ctx.fresh` mints their binders so a field called `s` or
    * `v` cannot capture them (rule 4). */
  private def one(step: Step, ctx: Plugins.Ctx, p: Pos): Expr = step match
    case Step.Field(name) =>
      val s = ctx.fresh("focus")
      val v = ctx.fresh("focus")
      val get = Expr.Lambda(List(Param(s, p)), Expr.MethodRef(Expr.Name(s, p), name, p), p)
      val set = Expr.Lambda(List(Param(s, p), Param(v, p)),
                            Expr.MethodCall(Expr.Name(s, p), "copy",
                                            List(Expr.NamedArg(name, Expr.Name(v, p), p)), p), p)
      Expr.Call("opticField", List(Expr.StrLit(name, p), get, set), p)
    case Step.Some_      => Expr.Call("opticSome", Nil, p)
    case Step.Each       => Expr.Call("opticEach", Nil, p)
    case Step.Index(arg) => Expr.Call("opticIndex", List(arg), p)
    case Step.At(arg)    => Expr.Call("opticAt", List(arg), p)

  def install(): Unit = Plugins.registerRewrite("Focus", rewrite)
