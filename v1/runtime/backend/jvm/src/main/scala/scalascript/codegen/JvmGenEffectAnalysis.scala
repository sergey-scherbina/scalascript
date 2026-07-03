package scalascript.codegen

import scalascript.ast.*
import scalascript.transform.EffectAnalysis
import scala.meta.*

/** Effect analysis: detects which built-in effect ops a module uses, runs the
 *  shared `EffectAnalysis` over the blocks, and records the effect-op set and
 *  the effectful-function set into generator state. Lifted out of JvmGen to
 *  keep the generator navigable; self-typed because `analyzeEffects` populates
 *  generator state (`effectOps`, `effectfulFuns`) and reads the `blocksUseXxx`
 *  detectors from `JvmGenBlockAnalysis` and `globalEffectfulDeps` from
 *  Strategy D. */
private[codegen] trait JvmGenEffectAnalysis:
  self: JvmGen =>

  // ─── Effect analysis ──────────────────────────────────────────────

  private[codegen] def analyzeEffects(blocks: List[JvmGen.Block]): Unit =
    // Built-in `Async` / `Storage` / `Actor` effects — pre-populated only
    // when the module actually uses them, keeping the emitted Scala lean
    // otherwise.
    val builtins =
      (if blocksUseAsync(blocks) then
         Set("Async.delay", "Async.async", "Async.await", "Async.parallel", "Async.recvFrom")
       else Set.empty[String]) ++
      (if blocksUseStorage(blocks) then
         Set("Storage.get", "Storage.put", "Storage.remove", "Storage.has", "Storage.keys")
       else Set.empty[String]) ++
      (if blocksUseActors(blocks) then
         Set("Actor.spawn", "Actor.spawn_link", "Actor.self", "Actor.send", "Actor.exit",
             "Actor.receive", "Actor.receive_t",
             "Actor.link", "Actor.monitor", "Actor.demonitor", "Actor.trapExit")
       else Set.empty[String]) ++
      (if blocksUseLogger(blocks) then
         Set("Logger.info", "Logger.warn", "Logger.error", "Logger.debug")
       else Set.empty[String]) ++
      (if blocksUseRandom(blocks) then
         Set("Random.nextInt", "Random.nextDouble", "Random.uuid", "Random.pick")
       else Set.empty[String]) ++
      (if blocksUseClock(blocks) then
         Set("Clock.now", "Clock.nowIso", "Clock.sleep")
       else Set.empty[String]) ++
      (if blocksUseEnv(blocks) then
         Set("Env.get", "Env.set", "Env.required")
       else Set.empty[String]) ++
      (if blocksUseHttp(blocks) then
         Set("Http.get", "Http.post", "Http.request")
       else Set.empty[String]) ++
      (if blocksUseRetry(blocks) then
         Set("Retry.attempt")
       else Set.empty[String]) ++
      (if blocksUseCache(blocks) then
         Set("Cache.memoize")
       else Set.empty[String]) ++
      (if blocksUseState(blocks) then
         Set("State.get", "State.set", "State.modify")
       else Set.empty[String]) ++
      // Tx and Auth don't use _perform; add dummy entries only to gate
      // effectsRuntime emission when no other effects are present.
      (if blocksUseTx(blocks) || blocksUseAuth(blocks) then
         Set("_v14extras")
       else Set.empty[String])

    val trees = blocks.map(b => ScalaNode.fold(b.node)(identity))
    val r     = EffectAnalysis.analyze(trees, builtins)

    effectOps.clear();     effectOps     ++= r.effectOps
    effectfulFuns.clear(); effectfulFuns ++= r.effectfulFuns

  private[codegen] def isEffectOpDef(body: Term): Boolean = EffectAnalysis.isEffectOpDef(body)

  private[codegen] def isEffectOpRef(eff: String, op: String): Boolean =
    effectOps.contains(s"$eff.$op")

  /** True for user-code effectful functions (populated by `analyzeEffects`)
   *  AND for dep-defined functions marked effectful by Strategy D's
   *  fixpoint (Step 2, `analyzeDepEffectfulness`). Both kinds need the
   *  same downstream treatment: params widened to `Any`, body routed
   *  through `emitCpsExpr`. */
  private[codegen] def isEffectfulFun(name: String): Boolean =
    effectfulFuns.contains(name) || globalEffectfulDeps.contains(name)
