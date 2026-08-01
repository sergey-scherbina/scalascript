package ssc.jit

import ssc.{Code, Done, Emit, Env, JitBackend, JitSite, Value}
import ssc.bytecode.JvmByteGen

/** The compile side of the v2 wide JIT (`specs/v2-wide-jit.md` §3.6), on the far side of the
  * by-name seam so the kernel never mentions a code generator.
  *
  * WHY THIS CLASS IS FOUND BY NAME AND NOT BY A `dependsOn`. The VM lane must not load ASM.
  * `v21-plugin-backend-isolation` is enforced by smokes, and `RunNativeV2` already avoids so much as
  * NAMING an ASM type in a `catch` clause, because the JVM loads a referenced class when it verifies
  * the method that mentions it. `ssc.Jit` therefore resolves `ssc.jit.BytecodeJitBackend` with
  * `Class.forName` on the first hot site, and only when armed. Where this class is absent — `run-ir`
  * is `v2/src` alone, and a native-image build has no runtime class definition to load anyway — the
  * kernel gets `null` and the VM runs exactly as it does today.
  *
  * WHAT J-2 SHIPS: the seam, wired and observable, with `compileUnit` answering `null` for every
  * site. J-3 is the slice that makes it answer — `JvmByteGen.emitUnit` — and it is a change to this
  * file, not to the kernel.
  */
final class BytecodeJitBackend extends JitBackend:

  def id: String = "bytecode"

  /** ONE globals namespace for both tiers.
    *
    * The VM keeps its globals in the `TrieMap` built by `Compiler.compileWithGlobals`; generated
    * bytecode reads `Emit.global` / `Emit.globalsRef`. If those are two maps, a JIT-compiled unit
    * that reads a global sees a different value from the interpreted body it replaced — a divergence
    * that no output comparison would attribute correctly, because both tiers would be "working".
    * Pointing the field at the VM's own map makes the two the same object, including the `@`-cell
    * globals that both sides auto-create on first touch.
    *
    * The lane conflict this creates is handled where it belongs: a `--bytecode` run owns this field,
    * so it calls `Jit.disarm()` at entry and the JIT never bridges underneath it. */
  def onProgram(globals: collection.mutable.Map[String, Value]): Unit =
    Emit.globalsRef = globals

  /** `null` = this site stays interpreted. Never throws: an uncompilable site is a performance
    * outcome, never a program failure, and the kernel's contract says so.
    *
    * TWO SHAPES ARE REFUSED OUTRIGHT, both for correctness rather than difficulty:
    *
    *  - **loop sites** (`arity < 0`). A `While` body is not a `Lam`: it shares the enclosing frame
    *    and its value feeds the loop's own effect threading. Compiling it is J-6, with its own gate.
    *  - **handler-dispatch roots**. Their unhandled-event protocol is scoped by
    *    `Runtime.handlerClosure` and mirrored in the emitter by a separate `handlerDispatchRoot`
    *    mode; compiling one as an ordinary body would silently drop the probe. Silently is the
    *    problem — the program would keep running and answer differently.
    *
    * Everything else goes through `JvmByteGen.emitUnit`, i.e. through the SAME emitter the AOT lane
    * uses, so a shape either lane learns is learned by both. */
  def compileUnit(site: JitSite): Code | Null =
    if site.arity < 0 || site.handlerRoot then null
    else
      try
        val fn = JvmByteGen.loadUnit(JvmByteGen.emitUnit(site.body))
        val owned = site.globals
        // Exactly the wrapper `Emit.clos` uses for every AOT lambda — the compiled body answers a
        // Value (possibly a bounce), `unroll` resolves it, the trampoline sees a `Done` — plus one
        // thing the AOT lane never needs: pointing `Emit.globalsRef` at THIS unit's program.
        //
        // One process runs several programs (the F tower, then the user program), each with its own
        // globals map, and generated code resolves every global through that single static field.
        // Bridging it once binds all units to one program and kills the other with `unbound global`.
        // Measured: J-3's first run compiled 61 units and did exactly that. The check is a volatile
        // read against a captured reference, and the write happens only when the running program
        // actually changed.
        if owned == null then (env: Env) => Done(Emit.unroll(fn.call(env)))
        else
          val g = owned.asInstanceOf[collection.mutable.Map[String, Value]]
          (env: Env) =>
            if !(Emit.globalsRef eq g) then Emit.globalsRef = g
            Done(Emit.unroll(fn.call(env)))
      catch
        // `Unsupported` for a construct the emitter cannot compile, an ASM size error for a unit
        // over the 64 KB method limit, anything else from the generator: all of them mean "leave
        // this site interpreted". The site is asked once and never again.
        case _: Throwable => null
