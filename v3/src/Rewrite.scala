package ssc3

/** THE REWRITE PASS — the moving half of `specs/60-compile-time-extension.md` (R2; R1 is the node,
  * the registry and both fronts asking it).
  *
  * Runs on the merged `Program`, between `Loader.merge` and `Lower.programOf` — the seam is
  * `Driver.moduleOf`, the one place every lane already goes through, so the executor and the v2
  * bridge receive the same rewritten tree by construction (I-3) rather than by agreement.
  *
  * WHAT IT DOES. Every `Expr.Marker` whose name has a registered rewrite is replaced by what that
  * rewrite returns; the seven rules the mechanism keeps are in the spec, and each shows up below as
  * one concrete line — the bound, the positioned refusals, the fresh-name convention, the
  * empty-registry short-circuit.
  *
  * WHAT IT REFUSES, all three positioned (rule 3), because `corpus-report.sh` classifies a refusal
  * by its `:line:col:` shape and an exception escaping here would be counted CRASH — a floor:
  *   - a marker nobody claims (only a REWRITE can produce one: a front asks `hasRewrite` before
  *     building a marker, so an unclaimed one at the top of the pass is a client's output);
  *   - a client returning `Refusal`, passed through in the same shape;
  *   - a marker still unexpanded after `Bound` steps (rule 2: a runaway rewrite must not look
  *     like a hang).
  */
object Rewrite:

  /** Rule 2's constant. Deep enough for any client that terminates — the three planned clients
    * need one step each — and small enough that hitting it reads as the defect it is. */
  private val Bound = 32

  /** The whole program, every expression position `Loader.merge` produces. Listed field by field
    * with `copy` rather than a rebuild, so a field added to `Program` later keeps its value here
    * instead of silently resetting to its default — the exact hole `merge`'s own comment records
    * for `effects`. */
  def program(p: Program): Program =
    // PROVABLY A NO-OP WHEN IDLE: no registered rewrite means no front built a marker, so there is
    // nothing to find and the tree is returned untouched, unwalked.
    if Plugins.rewriteNames.isEmpty then p
    else
      val names = new Names
      def ex(e: Expr): Expr = top(e, names)
      def pm(x: Param): Param = x.copy(default = x.default.map(ex))
      def df(d: Def): Def =
        d.copy(params = d.params.map(pm), givenParams = d.givenParams.map(pm), body = ex(d.body))
      def stmt(s: Stmt): Stmt = s match
        case Stmt.Val(n, v, mu, q) => Stmt.Val(n, ex(v), mu, q)
        case Stmt.Exp(x)           => Stmt.Exp(ex(x))
        case Stmt.LocalDef(d)      => Stmt.LocalDef(df(d))
      p.copy(
        defs     = p.defs.map(df),
        topLevel = p.topLevel.map(stmt),
        classes  = p.classes.map(c => c.copy(fields = c.fields.map(pm), methods = c.methods.map(df))),
        objects  = p.objects.map(o => o.copy(defs = o.defs.map(df),
                                             vals = o.vals.map(v => v.copy(value = ex(v.value))))),
        traits   = p.traits.map(t => t.copy(methods = t.methods.map(df))),
        // Effect declarations carry no bodies today; walked anyway so a future default body is not
        // silently skipped by the one pass that must see everything.
        effects  = p.effects.map(t => t.copy(methods = t.methods.map(df))))

  /** One expression, bottom-up (rule 2): `mapDeep` rebuilds children before applying its function,
    * so a client sees finished arguments, and an inner marker is expanded before the outer one that
    * carries it. */
  private def top(e: Expr, names: Names): Expr =
    Expr.mapDeep(e, {
      case m: Expr.Marker => expand(m, names, 0)
      case other          => other
    })

  private def expand(m: Expr.Marker, names: Names, depth: Int): Expr =
    if depth >= Bound then
      throw LowerFail(m.pos, "the marker '" + m.name + "' was still a marker after " + Bound +
        " rewrites — the rewrite keeps producing markers instead of terminating, and a runaway " +
        "rewrite must refuse rather than hang")
    Plugins.rewriteFor(m.name) match
      // ONLY A REWRITE CAN GET HERE: both fronts ask `hasRewrite` before building a marker, so an
      // unclaimed one is the output of another client — named, so the blame lands on the producer.
      case None =>
        throw LowerFail(m.pos, "no rewrite is registered for the marker '" + m.name +
          "' — a rewrite produced it, and a claim is exclusive to the plugin that registers it")
      case Some(fn) =>
        fn(m, new PassCtx(names, depth)) match
          case Left(r)    => throw LowerFail(r.pos, r.msg)
          case Right(out) =>
            // The client's output may itself carry markers — its own, or another plugin's. Each is
            // expanded one step deeper, which is what makes the bound a bound.
            Expr.mapDeep(out, {
              case m2: Expr.Marker => expand(m2, names, depth + 1)
              case other           => other
            })

  /** Rule 4: generated names come only from here. The parser mints `$m<line>_<col>` (digits after
    * `$m`); this mints `$mr<n>_<prefix>` — the `r` is what keeps the two families disjoint, and the
    * counter is program-wide so two clients can never collide with each other either. */
  private final class Names:
    private var n = 0
    def fresh(prefix: String): String =
      n += 1
      "$mr" + n.toString + "_" + prefix

  private final class PassCtx(names: Names, depth: Int) extends Plugins.Ctx:
    def fresh(prefix: String): String = names.fresh(prefix)
    def rewrite(e: Expr): Either[Plugins.Refusal, Expr] =
      try
        Right(Expr.mapDeep(e, {
          case m: Expr.Marker => expand(m, names, depth + 1)
          case other          => other
        }))
      catch case f: LowerFail => Left(Plugins.Refusal(f.message, f.pos))
