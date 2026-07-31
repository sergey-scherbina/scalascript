package ssc

import java.util.concurrent.atomic.AtomicInteger

/** Tier-0 of the v2 wide JIT: the node that notices a site is hot.
  *
  * `specs/v2-wide-jit.md` §3.3. Nothing here compiles anything yet — J-1 installs the counters and
  * proves they cost what J-0 measured; J-2/J-3 give `JitSite.fast` something to hold.
  *
  * WHY A SITE AND NOT A CLOSURE. A `Lam` body's `Code` is built ONCE per site, at program-compile
  * time, and shared by every `ClosV` allocated there (`Runtime.scala` pass 1, the `Lam` case, and
  * `LetRec`). So the counter belongs to the site, and — because `Code = Env => Step` is a SAM — the
  * counter can simply BE the body: the trampoline, `ClosV`, `applyStep` and every call site keep
  * calling a `Code` and never learn that one is a `JitSite`. v1 could not do this: its `FunV` is an
  * enum case that cannot carry state, so its JIT keys a synchronized `IdentityHashMap` on closure
  * INSTANCES and documents the resulting leak (`specs/vm-jit-spec.md` §6.1).
  *
  * WHY ARMING IS A COMPILE-TIME DECISION. `site`/`loopSite` return the body unchanged unless the JIT
  * is armed, and arming is read once per process, before the first `Lam` compiles. With the JIT off
  * the wrapper is not "cheap", it is ABSENT — which matters because this node is on the entry path
  * of every call in every program on the default lane. Measured with it on (`V2JitSiteBench`,
  * 2026-07-31): +0.384 ns against a 9.469 ns call, ~4 %, ranges disjoint.
  */
object Jit:

  /** Read once. `SSC_V2_JIT` and not `SSC_JIT`: the latter is v1's, and `bench/run.sc` sets
    * `SSC_JIT_BACKEND` to select the v1 `ssc-asm` lane — a shared name would make a bench row
    * ambiguous about which JIT it measured. Off by default until J-9 decides otherwise. */
  private val armedByEnv: Boolean =
    sys.env.get("SSC_V2_JIT").exists(v => v != "0" && v != "off" && v.nonEmpty)

  @volatile private var armedFlag: Boolean = armedByEnv
  def armed: Boolean = armedFlag

  /** A `--bytecode` run OWNS `Emit.globalsRef` — it resets it and installs its own program's
    * globals. The JIT bridges that same field to the VM's globals map (§3.6), so the two must never
    * be live at once; the bytecode lane disarms at entry and its interpreter FALLBACK then runs
    * unarmed, which is a correctness choice rather than a performance one. Irreversible on purpose:
    * nothing should be able to re-arm mid-process behind the lane's back. */
  def disarm(): Unit = armedFlag = false

  /** v1's default, for the same reason: high enough that one-shot code never compiles, low enough
    * that a real hot path reaches it within noise of its first millisecond. */
  val threshold: Int =
    sys.env.get("SSC_V2_JIT_THRESHOLD").flatMap(_.toIntOption).getOrElse(8)

  /** Loops tier up on back edges, which are far more frequent than calls, so the count that means
    * "hot" is correspondingly larger. A `While` body never re-enters the trampoline's `Call` bounce
    * (`Runtime.scala`, the plain-Java-`while` case), so without its own site a hot loop would be
    * invisible to a call counter — the hole v1 patched with eager compilation plus `WhileJitEntry`. */
  val loopThreshold: Int =
    sys.env.get("SSC_V2_JIT_LOOP_THRESHOLD").flatMap(_.toIntOption).getOrElse(256)

  private val statsEnabled: Boolean =
    sys.env.get("SSC_V2_JIT_STATS").exists(v => v != "0" && v != "off" && v.nonEmpty)

  private val sitesArmed    = new AtomicInteger(0)
  private val sitesHot      = new AtomicInteger(0)
  private val sitesCompiled = new AtomicInteger(0)

  if armed && statsEnabled then
    // java.lang.Runtime, NOT ssc.Runtime — the kernel's own object shadows it in this package.
    java.lang.Runtime.getRuntime.addShutdownHook(new Thread(() => report()))

  /** STDERR, never stdout. The parity gate compares program stdout between `SSC_V2_JIT=off` and on;
    * a report on stdout would make the two differ BY CONSTRUCTION and turn the gate into a lie. The
    * repo has paid for this once already — `BytecodeFallbackMarker` is on stderr for the same
    * reason, and the sweep that ignored it certified a program against itself. */
  private def report(): Unit =
    val backendId = if backendRef == null then "none" else backendRef.asInstanceOf[JitBackend].id
    System.err.println(
      s"ssc: jit tier-0 — ${sitesArmed.get()} sites armed, ${sitesHot.get()} reached the threshold " +
      s"(call $threshold / loop $loopThreshold), ${sitesCompiled.get()} compiled, backend $backendId")

  /** A `Lam` body. `body`/`arity` are carried now, though tier 0 does not read them, because J-3
    * compiles exactly this term and J-8 names exactly this site — plumbing them later would mean a
    * second pass over the same four kernel call sites. */
  def site(body: Term, arity: Int, code: Code): Code =
    if !armed then code else new JitSite(body, arity, code, threshold)

  /** A `While` body: no arity, and its own threshold. */
  def loopSite(body: Term, code: Code): Code =
    if !armed then code else new JitSite(body, -1, code, loopThreshold)

  private[ssc] def registered(): Unit = sitesArmed.incrementAndGet()

  // ── the backend seam (J-2) ────────────────────────────────────────────────────────────────────
  //
  // The kernel must not so much as MENTION the code generator. `v21-plugin-backend-isolation` is
  // enforced by smokes, and `RunNativeV2` already contorts a `catch` clause to avoid naming an ASM
  // type, because referencing one makes the JVM load ASM when it merely VERIFIES the method. So the
  // backend is resolved BY NAME, once, on the first hot site — off, or absent from the classpath
  // (`run-ir` is `v2/src` alone; a native-image build has no class definition at all), and the field
  // stays null and the VM runs exactly as it does today. Same shape as v1's `JitBackend.default`.

  private val backendClassName = "ssc.jit.BytecodeJitBackend"

  @volatile private var backendRef: JitBackend | Null = null
  private var backendResolved = false
  private var programGlobals: collection.mutable.Map[String, Value] | Null = null

  /** Handed the program's globals at compile time and STASHED, not forwarded: forwarding here would
    * load the backend for every armed program, including ones that never get hot, which is exactly
    * the eager loading the by-name seam exists to avoid. The backend receives them when it is
    * actually resolved. */
  private[ssc] def onProgram(globals: collection.mutable.Map[String, Value]): Unit =
    if armed then programGlobals = globals

  private def backend(): JitBackend | Null = synchronized:
    if !backendResolved then
      backendResolved = true
      backendRef =
        try
          val b = Class.forName(backendClassName)
            .getDeclaredConstructor().newInstance().asInstanceOf[JitBackend]
          val g = programGlobals
          if g != null then b.onProgram(g.asInstanceOf[collection.mutable.Map[String, Value]])
          b
        catch
          // Absent backend is a supported configuration, not a failure: the VM stays on tier 0.
          // Deliberately broad — ClassNotFound, NoClassDefFound (a missing ASM), an initialiser
          // throwing — every one of them means "no JIT here", and none of them may take the program
          // down, because the program is CORRECT without the JIT.
          case _: Throwable => null
    backendRef

  /** Reached the tier-up threshold. Asks the backend for a compiled unit and installs it; a `null`
    * answer leaves the site interpreted and it will not be asked again. J-2 ships the seam with a
    * backend that always answers null — J-3 is what makes it answer. */
  private[ssc] def onHot(site: JitSite): Unit =
    sitesHot.incrementAndGet()
    val b = backend()
    if b != null then
      val compiled = b.asInstanceOf[JitBackend].compileUnit(site)
      if compiled != null then
        site.fast = compiled
        sitesCompiled.incrementAndGet()

/** The compile side, implemented outside the kernel (`v2/backend-jvm-bytecode`) and reached only by
  * name (§3.6). `compileUnit` returns a `Code`, not the backend's own function type, so the kernel
  * never learns what a `LamFn` or a `ClassWriter` is — the whole ASM dependency stays on the far
  * side of this trait. */
trait JitBackend:
  def id: String

  /** Once per program, before any unit compiles. The bytecode backend uses it to point
    * `Emit.globalsRef` at the VM's own globals map so both tiers share one namespace. */
  def onProgram(globals: collection.mutable.Map[String, Value]): Unit

  /** `null` = leave this site interpreted. It must never throw: a site that cannot be compiled is a
    * performance outcome, never a program failure. */
  def compileUnit(site: JitSite): Code | Null

/** The counting node. Installed only when `Jit.armed`.
  *
  * Field reads are `@volatile` and that is measured, not assumed: the non-volatile variant came back
  * at 9.908 ± 0.108 ns against the volatile 9.853 ± 0.059 — not faster, intervals overlapping. Safe
  * publication of a compiled unit therefore costs nothing here, so there is no reason to reach for
  * an unsafe-publication trick. `hits` is deliberately NOT atomic: a lost increment under a race
  * delays tier-up by one call and nothing else, and v1 makes the same trade for the same reason. */
final class JitSite(
    val body: Term,
    val arity: Int,
    private val slow: Code,
    private val tierUpAt: Int
) extends (Env => Step):
  Jit.registered()

  @volatile private[ssc] var fast: Code | Null = null
  private[ssc] var hits: Int = 0

  def apply(env: Env): Step =
    val f = fast
    // `.asInstanceOf` rather than flow typing: this module is not compiled with -Yexplicit-nulls,
    // so the union does not narrow on the null test. Mirrors `Runtime.dispatchHandler1`.
    if f != null then f.asInstanceOf[Code](env)
    else
      hits += 1
      if hits == tierUpAt then Jit.onHot(this)
      slow(env)
