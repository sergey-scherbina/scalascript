package ssc3.plugins

import ssc3.{Expr, Pat, MatchArm, Param, Plugins}

/** `Prism[S, C]` — the second client of the syntax door, and the one that needs no path at all.
  *
  * A prism is named ENTIRELY by its two type arguments: the enum and the variant. There is no
  * lambda to introspect, no block to fold — which is why it is the cheapest of the optics to land
  * and why it lands on its own rather than behind the `Focus` path grammar.
  *
  * WHAT IT EMITS, and every piece of it is ordinary `Expr` (rule 6):
  *
  *   Prism[Shape, Circle]
  *     ──▶  PrismOptic(s => s match { case c: Circle => Some(c); case _ => None },
  *                     x => x)
  *
  * `PrismOptic` is declared in `v3/prelude/index.ssc`. The kernel learns neither name.
  *
  * THE MATCH IS A TYPE-ASCRIPTION PATTERN, `Pat.PType`, which is the one piece of this that could
  * not have been done from outside a year ago: the test is nominal and flat — the value's tag
  * against the name — and it is exactly what the reference front does for `case c: Circle =>`
  * (`ssc1-lower.ssc0:3559`, `__isTag__`). So the emitted match is the one a person would have
  * written by hand, not a new mechanism.
  *
  * WHY `wrap` IS THE IDENTITY. At Tier 0 a variant IS its enum at runtime — `Circle(5)` matched out
  * of a `Shape` is already a `Shape` — so putting it back costs nothing. It is a FIELD rather than
  * an omission so that `reverseGet` stays a wrapper call and a representation that ever boxes has
  * one place to change.
  */
object PrismSyntax:

  private def rewrite(m: Expr.Marker, ctx: Plugins.Ctx): Either[Plugins.Refusal, Expr] =
    m.typeArgs match
      case List(outer, variant) if outer.nonEmpty && variant.nonEmpty =>
        val p = m.pos
        // Hygiene, rule 4: both binders come from `Ctx.fresh`, so a prism over a variant whose
        // field is called `s` or `x` cannot capture.
        val sub = ctx.fresh("prism")
        val arg = ctx.fresh("prism")
        val idn = ctx.fresh("prism")
        val test = Expr.Lambda(List(Param(arg, p)),
          Expr.Match(Expr.Name(arg, p), List(
            MatchArm(Pat.PType(variant, Pat.PBind(sub, p), p), None,
                     Expr.Call("Some", List(Expr.Name(sub, p)), p)),
            MatchArm(Pat.PWild(p), None, Expr.Name("None", p))), p), p)
        val wrap = Expr.Lambda(List(Param(idn, p)), Expr.Name(idn, p), p)
        Right(Expr.Call("PrismOptic", List(test, wrap), p))
      // NAMED BY ITS TYPES MEANS BOTH OF THEM. One is `Prism[Shape]` — which enum, which variant?
      // — and three is a spelling nobody has defined. Refused with a position rather than guessed,
      // because guessing here picks a variant for the author.
      case other => Left(Plugins.Refusal(
        "Prism needs exactly two type arguments — the enum and the variant, as `Prism[Shape, Circle]`" +
        (if other.isEmpty then "" else " — this one has " + other.length.toString), m.pos))

  def install(): Unit = Plugins.registerRewrite("Prism", rewrite)
