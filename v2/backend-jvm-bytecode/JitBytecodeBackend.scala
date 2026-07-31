package ssc.jit

import ssc.{Code, Emit, JitBackend, JitSite, Value}

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
    * outcome, never a program failure, and the kernel's contract says so. */
  def compileUnit(site: JitSite): Code | Null = null
