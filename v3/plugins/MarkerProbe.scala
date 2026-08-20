package ssc3.plugins

import ssc3.{Expr, Stmt, Plugins}

/** A MARKER THAT EXISTS ONLY TO PROVE THE DOOR, and only when asked for by name.
  *
  * R1 proved the plumbing: with `SSC3_MARKER_PROBE=1` both fronts build `Expr.Marker` for `probe`,
  * and without it the same file is an ordinary call. R2 adds the pass, so the probe now has to
  * prove the pass's BEHAVIOURS — including the three refusals — not just its happy path. Each is a
  * mode, because `v3/rewrite-gate.sh` needs a lever per failure and a probe that could only
  * demonstrate success would prove the door for the easy half.
  *
  *   SSC3_MARKER_PROBE=1                claims `probe`; `=Focus` claims `Focus` instead (see R1's
  *                                      note: the grammar-marked names arrive by a different path)
  *   SSC3_MARKER_PROBE_MODE=unwrap      (default) `probe(e)` becomes `{ val $mrN_probe = e;
  *                                      $mrN_probe }` — the pass ran, and the binder came from
  *                                      `Ctx.fresh`, both visible in one output
  *   SSC3_MARKER_PROBE_MODE=refuse      returns `Refusal` — the gate asserts the `:line:col:` shape
  *   SSC3_MARKER_PROBE_MODE=mint        rewrites to a marker NOBODY claims — the gate asserts the
  *                                      unclaimed refusal names the ghost, not the probe
  *   SSC3_MARKER_PROBE_MODE=runaway     returns its own node unchanged — the gate asserts the bound
  *                                      refusal, because identity is exactly what a runaway is
  *   SSC3_MARKER_PROBE_MODE=stamp       expands to the STRING of the name `Ctx.fresh` minted, so the
  *                                      counter becomes observable output. This is the only mode
  *                                      whose payload depends on how many times the pass has run:
  *                                      every other one answers the same whether the pass ran once
  *                                      or twice, and rule 7 is precisely a claim about running
  *                                      twice (`Driver.moduleOf` retries with the prelude). A
  *                                      fixture whose payload ignores its input cannot see that
  *                                      defect — measured elsewhere in this repository on
  *                                      2026-08-20, where a continuation composed twice was
  *                                      invisible for months because its remainder was `"END"`.
  */
object MarkerProbe:
  def names: List[String] = sys.env.get("SSC3_MARKER_PROBE") match
    case None            => Nil
    case Some("1")       => List("probe")
    case Some(spec)      => spec.split(",").toList.map(_.trim).filter(_.nonEmpty)

  private def rewrite(m: Expr.Marker, ctx: Plugins.Ctx): Either[Plugins.Refusal, Expr] =
    val name = m.name; val pos = m.pos
    sys.env.getOrElse("SSC3_MARKER_PROBE_MODE", "unwrap") match
      case "refuse"  => Left(Plugins.Refusal("the probe refuses '" + name + "' by request", pos))
      case "mint"    => Right(m.copy(name = name + "$ghost"))
      case "runaway" => Right(m)
      case "stamp"   => Right(Expr.StrLit(ctx.fresh(name), pos))
      case _ =>
        val v = ctx.fresh(name)
        val arg = m.args.headOption.getOrElse(Expr.IntLit(0L, pos))
        Right(Expr.Block(List(Stmt.Val(v, arg, false, pos)), Some(Expr.Name(v, pos)), pos))

  /** No `hasRewrite` guard on purpose: `SSC3_MARKER_PROBE=probe,probe` is how the gate asserts a
    * claim is exclusive (rule 1). The registry throws; deduplicating here would make that rule
    * untestable from the outside. */
  def install(): Unit =
    names.foreach(n => Plugins.registerRewrite(n, rewrite))
