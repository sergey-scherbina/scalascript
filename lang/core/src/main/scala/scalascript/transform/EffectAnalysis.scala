package scalascript.transform

import scala.collection.mutable
import scala.meta.{Defn, Source, Stat, Term, Tree}

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
   *    during CPS rewriting. */
  case class Result(effectOps: Set[String], effectfulFuns: Set[String])

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
    val effectOps     = mutable.Set.from(builtins)
    val effectfulFuns = mutable.Set.empty[String]
    val funBodies     = mutable.Map.empty[String, Term]

    def collectFromStats(stats: List[Stat]): Unit = stats.foreach {
      case d: Defn.Object =>
        d.templ.body.stats.foreach {
          case dd: Defn.Def if isEffectOpDef(dd.body) =>
            effectOps += s"${d.name.value}.${dd.name.value}"
          case _ => ()
        }
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

    def callees(tree: Tree): Set[String] = tree match
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

    Result(effectOps.toSet, effectfulFuns.toSet)

  /** Verifier mode: compare the type-system's declared effect set against the
   *  name-reachability analysis.  Returns warning messages for divergences.
   *  Called after v1.12.1 type-checking to cross-check the two analyses. */
  def verify(
    typedEffects:   Map[String, Set[String]],
    analysisResult: Result
  ): List[String] =
    val warnings = scala.collection.mutable.ListBuffer.empty[String]
    // Build a set of effect object names reachable from analysisResult.effectOps
    // e.g. "Logger.log" → "Logger"
    val reachableEffectNames: Set[String] =
      analysisResult.effectOps.map(op => op.takeWhile(_ != '.'))
    typedEffects.foreach { (funName, declared) =>
      val funIsEffectful = analysisResult.effectfulFuns.contains(funName)
      val unaccountedDeclared = declared.filterNot { effName =>
        // A declared effect is "accounted for" if the analysis found it reachable
        // AND the function is marked effectful
        funIsEffectful && reachableEffectNames.contains(effName)
      }
      if unaccountedDeclared.nonEmpty then
        warnings += s"[effect-verifier] '$funName' declares effect(s) ${unaccountedDeclared.mkString(", ")} but the effect reachability analysis found none"
      if funIsEffectful && declared.isEmpty then
        warnings += s"[effect-verifier] '$funName' appears effectful (reaches ${reachableEffectNames.mkString(", ")}) but declares no effect row (!)"
    }
    warnings.toList
