package scalascript.transform

import scala.collection.mutable
import scala.meta.{Case, Defn, Lit, Pat, Source, Stat, Term, Tree, Type}

/** Shared CPS effect analysis used by every backend that emits a Free-monad
 *  runtime (currently `JvmGen` and `JsGen`).
 *
 *  Both backends pre-Stage-3 carried near-identical copies of this loop —
 *  ~70-80 LOC each, walking the same scalameta tree, building the same
 *  `(effectOps, effectfulFuns)` pair.  This pulls the loop into one place;
 *  backends keep their own emission code that consults the result.
 *
 *  Algorithm (unchanged from the pre-Stage-3 copies):
 *
 *    1. Collect declared effect ops — `Defn.Object` containing
 *       `Defn.Def` whose body is the marker `__effectOp__`.  The object
 *       name + def name becomes a fully-qualified op (`Async.delay`).
 *
 *    2. Collect every top-level `Defn.Def` body, keyed by name.
 *
 *    3. Fixed-point iterate: any function whose body's call sites mention
 *       a known op or an already-effectful function becomes effectful too.
 *
 *  Stage 5 (backends-as-plugins) will move emission to consume IR
 *  `Perform`/`Handle`/`Resume` nodes directly; this analysis layer is the
 *  bridge in the meantime. */
object EffectAnalysis:

  /** Result of analysing a module's scalameta trees.
   *
   *  - `effectOps` — fully qualified names like `Async.delay`,
   *    `MyEff.send`, …
   *  - `effectfulFuns` — every top-level `def` whose body transitively
   *    reaches an `effectOp`.  Both sets are consulted by the codegens
   *    during CPS rewriting.
   *  - `multiShotEffects` — effect object names that carry the
   *    `val __multiShot__ = true` marker (emitted by `multi effect Foo {}`). */
  case class Result(effectOps: Set[String], effectfulFuns: Set[String], multiShotEffects: Set[String] = Set.empty)

  /** Marker body recognised as an effect-op declaration:
   *  `effect Eff: def op(...) = __effectOp__`. */
  def isEffectOpDef(body: Term): Boolean = body match
    case Term.Name("__effectOp__") => true
    case _                         => false

  /** Marker body recognised as an `extern def` declaration —
   *  Stage 5+/A.6 (Б-1).  `extern def foo(...): T` is preprocessed
   *  by `Parser.preprocessExtern` into `def foo(...): T = __extern__`,
   *  and codegens consult this helper to skip emission for the stub
   *  (the intrinsic table provides the real implementation). */
  def isExternDef(body: Term): Boolean = body match
    case Term.Name("__extern__") => true
    case _                       => false

  /** Run the analysis.
   *
   *  @param trees    one entry per `Defn`-bearing scalameta tree (typically
   *                  the parsed body of each scalascript code block).
   *  @param builtins fully-qualified ops the backend declares as always
   *                  available (e.g. `Async.*`, `Storage.*` for JsGen;
   *                  same set gated by usage for JvmGen). */
  def analyze(trees: List[Tree], builtins: Set[String] = Set.empty): Result =
    val effectOps      = mutable.Set.from(builtins)
    val effectfulFuns  = mutable.Set.empty[String]
    val multiShotEffects = mutable.Set.empty[String]
    val funBodies      = mutable.Map.empty[String, Term]

    def collectFromStats(stats: List[Stat]): Unit = stats.foreach {
      case d: Defn.Object =>
        var isMultiShot = false
        d.templ.body.stats.foreach {
          case dd: Defn.Def if isEffectOpDef(dd.body) =>
            effectOps += s"${d.name.value}.${dd.name.value}"
          case Defn.Val(_, List(Pat.Var(n)), _, Lit.Boolean(true))
              if n.value == "__multiShot__" =>
            isMultiShot = true
          case _ => ()
        }
        if isMultiShot then multiShotEffects += d.name.value
      case d: Defn.Def => funBodies(d.name.value) = d.body
      case _           => ()
    }

    trees.foreach {
      case Source(stats)     => collectFromStats(stats)
      case Term.Block(stats) => collectFromStats(stats)
      case other             => other.children.foreach {
        case s: Source     => collectFromStats(s.stats)
        case b: Term.Block => collectFromStats(b.stats)
        case _             => ()
      }
    }

    var changed = true
    while changed do
      changed = false
      funBodies.foreach { (fname, body) =>
        if !effectfulFuns.contains(fname) then
          val calls = callees(body)
          if calls.exists(c => effectOps.contains(c) || effectfulFuns.contains(c)) then
            effectfulFuns += fname
            changed = true
      }

    Result(effectOps.toSet, effectfulFuns.toSet, multiShotEffects.toSet)

  /** Names called (directly or transitively via nesting) in a term — a
   *  qualified `Qual.method` for a select-call, a bare `name` for a plain
   *  call.  Used by the `analyze` fixed point and by [[leakingFuns]]. */
  private def callees(tree: Tree): Set[String] = tree match
    case Term.Apply.After_4_6_0(Term.Name(n), argClause) =>
      Set(n) ++ argClause.values.flatMap(callees).toSet
    case Term.Apply.After_4_6_0(Term.Select(Term.Name(qual), Term.Name(method)), argClause) =>
      Set(s"$qual.$method") ++ argClause.values.flatMap(callees).toSet
    case Term.Apply.After_4_6_0(fun, argClause) =>
      callees(fun) ++ argClause.values.flatMap(callees).toSet
    case Term.Select(Term.Name(qual), Term.Name(method)) =>
      Set(s"$qual.$method")
    case other =>
      other.children.flatMap(callees).toSet

  /** `true` for the head of a `handle` special form: `handle` or `handle[Eff]`. */
  private def isHandleHead(t: Tree): Boolean = t match
    case Term.Name("handle")                                => true
    case Term.ApplyType.After_4_6_0(Term.Name("handle"), _) => true
    case _                                                  => false

  /** Effect-object names a handler's cases discharge: `case Eff.op(args, resume) => …`
   *  contributes `Eff`.  A non-effect extractor (`Cons(h, t)`, `Some(x)`) contributes
   *  nothing. */
  private def handledEffectsInCases(cases: List[Case]): Set[String] =
    cases.flatMap { c =>
      c.pat match
        case Pat.Extract.After_4_6_0(Term.Select(Term.Name(eff), _), _) => Some(eff)
        case _                                                          => None
    }.toSet

  /** Effect ops a term LEAKS: an op `Eff.op` reached while NOT lexically inside a
   *  `handle` block that discharges `Eff`.
   *
   *  `handle { body } { case Eff.op(…) => … }` (and `handle[Eff] { body } { … }`)
   *  discharges every `Eff` named in its handler cases / explicit type argument for
   *  the extent of `body`; the handler-case bodies themselves run under the OUTER
   *  handled set (a handler may re-perform an outer effect).
   *
   *  This is the discharge-aware refinement of [[callees]]/`effectfulFuns`: plain
   *  name-reachability cannot tell a performed-then-handled op from a genuinely
   *  leaked one, so a `def` that fully discharges its own effect via an enclosing
   *  `handle` (the reusable-continuation `capture()` idiom) is otherwise mis-flagged
   *  as effectful-without-a-row.  `effectfulFuns` (consumed by the codegens) stays
   *  the coarse over-approximation; only the verifier consults this finer set. */
  private def leakedOps(tree: Tree, effectOps: Set[String], handled: Set[String]): Set[String] =
    tree match
      // handle { body } { cases }  /  handle[Eff] { body } { cases }
      case Term.Apply.After_4_6_0(Term.Apply.After_4_6_0(head, bodyClause), pfClause)
          if isHandleHead(head) =>
        val cases = pfClause.values.collect { case pf: Term.PartialFunction => pf.cases }.flatten
        val typeEffs = head match
          case Term.ApplyType.After_4_6_0(_, targs) => targs.values.collect { case Type.Name(n) => n }.toSet
          case _                                    => Set.empty[String]
        val innerHandled = handled ++ typeEffs ++ handledEffectsInCases(cases)
        bodyClause.values.flatMap(leakedOps(_, effectOps, innerHandled)).toSet ++
          cases.flatMap(c => leakedOps(c.body, effectOps, handled)).toSet
      // Eff.op(args)
      case Term.Apply.After_4_6_0(Term.Select(Term.Name(qual), Term.Name(method)), argClause)
          if effectOps.contains(s"$qual.$method") =>
        val here = if handled.contains(qual) then Set.empty[String] else Set(s"$qual.$method")
        here ++ argClause.values.flatMap(leakedOps(_, effectOps, handled)).toSet
      // Eff.op referenced as a value
      case Term.Select(Term.Name(qual), Term.Name(method))
          if effectOps.contains(s"$qual.$method") =>
        if handled.contains(qual) then Set.empty[String] else Set(s"$qual.$method")
      case other =>
        other.children.flatMap(leakedOps(_, effectOps, handled)).toSet

  /** Top-level functions that LEAK at least one effect op — reached outside any
   *  discharging `handle` — either directly or by calling another leaking function.
   *  The effect-row verifier consults this in place of the coarse `effectfulFuns`
   *  so a function that fully handles its own effects is not mis-flagged.
   *
   *  @param trees     same trees passed to [[analyze]]
   *  @param effectOps the fully-qualified ops from `analyze(...).effectOps` */
  def leakingFuns(trees: List[Tree], effectOps: Set[String]): Set[String] =
    val funBodies = mutable.Map.empty[String, Term]
    def collect(stats: List[Stat]): Unit = stats.foreach {
      case d: Defn.Def => funBodies(d.name.value) = d.body
      case _           => ()
    }
    trees.foreach {
      case Source(stats)     => collect(stats)
      case Term.Block(stats) => collect(stats)
      case other             => other.children.foreach {
        case s: Source     => collect(s.stats)
        case b: Term.Block => collect(b.stats)
        case _             => ()
      }
    }
    val leaking = mutable.Set.empty[String]
    funBodies.foreach { (fname, body) =>
      if leakedOps(body, effectOps, Set.empty).nonEmpty then leaking += fname
    }
    var changed = true
    while changed do
      changed = false
      funBodies.foreach { (fname, body) =>
        if !leaking.contains(fname) && callees(body).exists(leaking.contains) then
          leaking += fname
          changed = true
      }
    leaking.toSet

  /** Verifier mode: compare the type-system's declared effect set against the
   *  name-reachability analysis.  Returns diagnostic messages for divergences.
   *
   *  As of v1.12.3 the messages are promoted to errors (not mere warnings).
   *  The `asErrors` flag controls the prefix in the returned strings; callers
   *  should treat the returned list as compilation errors when non-empty and
   *  `asErrors = true` (the default).
   *
   *  @param typedEffects   function name → set of declared effect names (from the typer)
   *  @param analysisResult result of [[EffectAnalysis.analyze]] over the same trees
   *  @param asErrors       v1.12.3 default `true` — messages carry `[effect-error]` prefix;
   *                        `false` keeps the historic `[effect-verifier]` prefix for tests
   *                        that explicitly request warning-level output
   */
  def verify(
    typedEffects:   Map[String, Set[String]],
    analysisResult: Result,
    asErrors:       Boolean = true
  ): List[String] =
    val tag = if asErrors then "[effect-error]" else "[effect-verifier]"
    val warnings = scala.collection.mutable.ListBuffer.empty[String]
    // Build a set of effect object names reachable from analysisResult.effectOps
    // e.g. "Logger.log" → "Logger"
    val reachableEffectNames: Set[String] =
      analysisResult.effectOps.map(op => op.takeWhile(_ != '.'))
    typedEffects.foreach { (funName, declared) =>
      val funIsEffectful = analysisResult.effectfulFuns.contains(funName)
      // Only flag functions that ARE effectful but declare no effect row.
      // The reverse (declared but no performs in body) is valid: a function
      // may sub-effect and never actually perform an operation. No error.
      if funIsEffectful && declared.isEmpty then
        warnings += s"$tag '$funName' appears effectful (reaches ${reachableEffectNames.mkString(", ")}) but declares no effect row (!)"
    }
    warnings.toList
