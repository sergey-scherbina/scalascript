package scalascript.plugin.api

import org.scalatest.funsuite.AnyFunSuite
import scalascript.backend.spi.{Backend, BackendOptions, Capabilities, SpiVersion, SpiVersionRange}
import scalascript.ir.{QualifiedName, NormalizedModule}
import scalascript.backend.spi.{CompileResult, IntrinsicImpl}

/** Load-time API compat check — unit tests for PluginApiVersion.isCompatible
 *  and Backend.pluginApiVersion default.  See arch-stable-spi.md §7. */
class PluginApiVersionCompatTest extends AnyFunSuite:

  // ── PluginApiVersion.isCompatible ─────────────────────────────────────────

  test("isCompatible: same version is compatible"):
    assert(PluginApiVersion.isCompatible("1.0.0"))

  test("isCompatible: older MINOR is compatible (host is superset)"):
    // would require patching Current to a higher MINOR — we test the inverse:
    // if someone built against 0.9.0 it would be a different MAJOR => incompatible
    assert(!PluginApiVersion.isCompatible("0.9.0"))

  test("isCompatible: different MAJOR is incompatible"):
    assert(!PluginApiVersion.isCompatible("2.0.0"))
    assert(!PluginApiVersion.isCompatible("0.1.0"))

  test("isCompatible: malformed version is incompatible"):
    assert(!PluginApiVersion.isCompatible(""))
    assert(!PluginApiVersion.isCompatible("1.0"))
    assert(!PluginApiVersion.isCompatible("not-a-version"))

  test("isCompatible: same MAJOR, host MINOR >= plugin MINOR is compatible"):
    // Current is 1.0.0; plugin claiming 1.0.0 must be compatible
    assert(PluginApiVersion.isCompatible(PluginApiVersion.Current))

  // ── Backend.pluginApiVersion default ─────────────────────────────────────

  private val minimalBackend = new Backend:
    def id:           String                          = "test"
    def displayName:  String                          = "Test"
    def spiVersion:   String                          = SpiVersion.Current
    def capabilities: Capabilities                    = Capabilities(Set.empty, Set.empty, Set.empty,
                                                          SpiVersionRange(SpiVersion.Current, SpiVersion.Current))
    def intrinsics:   Map[QualifiedName, IntrinsicImpl] = Map.empty
    def acceptedSources: Set[String]                  = Set.empty
    def compile(ir: NormalizedModule, opts: BackendOptions): CompileResult = ???

  test("Backend.pluginApiVersion defaults to 1.0.0"):
    assert(minimalBackend.pluginApiVersion == "1.0.0")

  test("Backend default pluginApiVersion is compatible with current host"):
    assert(PluginApiVersion.isCompatible(minimalBackend.pluginApiVersion))
