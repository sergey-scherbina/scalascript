package scalascript

import org.scalatest.funsuite.AnyFunSuite

/** stable-spi Phase 3 ENFORCEMENT.
 *
 *  Every VALUE-SURFACE plugin (the `*Intrinsics` plugins authors write) must depend only on the
 *  stable `scalascript-plugin-api` — never on `scalascript.interpreter`.  This test locks in the
 *  Phase 3 migration: a plugin can no longer silently re-import interpreter internals.  The
 *  `scalascript-plugin-api` module is the ONE controlled seam that wraps the interpreter `Value`
 *  and re-exposes it through the opaque `PluginValue`/`PluginError`/capability surface.
 *
 *  EXEMPTION — `actors-plugin` is PERMANENT and correct, NOT unfinished work:
 *
 *    It is an interpreter-only runtime PROVIDER (`extends Backend, ActorRuntimeProviderBackend`,
 *    `intrinsics = Map.empty`), not a value-surface plugin.  Its `ActorRuntimeProvider` SPI is
 *    fundamentally interpreter-coupled BY DESIGN: `ActorRuntimeHost` traffics in `Computation`
 *    (the interpreter's eval monad), `Value`, `Env`, and `scala.meta.Case` (AST receive-blocks).
 *    The SPI's own doc states actors "cannot use the host-neutral `BlockForm` SPI without leaking
 *    interpreter internals into every plugin" — there is no host-neutral form to migrate to.  The
 *    plugin is a `preludeSymbols` keyword declaration + the actor-runtime provider seam; it stays
 *    in package `scalascript.interpreter.actors` by design.  Value-surface migration is complete
 *    at 27/28; this is the deliberate 28th. */
class StableSpiEnforcementTest extends AnyFunSuite:

  /** Plugins allowed to still reference `scalascript.interpreter` (see scaladoc above). */
  private val exempt = Set("actors-plugin")

  private def isCodeLine(l: String): Boolean =
    val t = l.trim
    !(t.startsWith("//") || t.startsWith("*") || t.startsWith("/*"))

  test("value-surface plugins depend only on scalascript-plugin-api (no scalascript.interpreter)"):
    val stdDir = TestPaths.repoRoot / "runtime" / "std"
    assert(os.exists(stdDir), s"runtime/std not found at $stdDir")

    val offenders =
      for
        plugin <- os.list(stdDir).filter(p => os.isDir(p) && p.last.endsWith("-plugin"))
        if !exempt.contains(plugin.last)
        mainDir = plugin / "src" / "main"
        if os.exists(mainDir)
        src <- os.walk(mainDir).filter(p => p.ext == "scala")
        line <- os.read(src).linesIterator
        if line.contains("scalascript.interpreter") && isCodeLine(line)
      yield s"${plugin.last}/${src.relativeTo(mainDir)}: ${line.trim}"

    assert(
      offenders.isEmpty,
      "These value-surface plugins still reference scalascript.interpreter — migrate them to the " +
        s"stable scalascript-plugin-api (or add a documented exemption):\n  ${offenders.mkString("\n  ")}"
    )

  test("the exemptions are real and still need migration (no stale exemptions)"):
    val stdDir = TestPaths.repoRoot / "runtime" / "std"
    // Each exempt plugin must STILL reference scalascript.interpreter; if it's already clean, the
    // exemption is stale and should be removed so the strict check covers it.
    val staleExemptions =
      exempt.filter { name =>
        val mainDir = stdDir / name / "src" / "main"
        !os.exists(mainDir) ||
          !os.walk(mainDir).filter(_.ext == "scala").exists(p =>
            os.read(p).linesIterator.exists(l => l.contains("scalascript.interpreter") && isCodeLine(l)))
      }
    assert(staleExemptions.isEmpty,
      s"these plugins are exempt but already clean — drop them from `exempt`: ${staleExemptions.mkString(", ")}")
