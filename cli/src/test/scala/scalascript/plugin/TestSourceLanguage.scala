package scalascript.compiler.plugin

import scalascript.backend.spi.*
import scalascript.ir

/** Test-only `SourceLanguage` plugin that claims the `mock` fence tag
 *  and rewrites the block source so the dispatch path is observable
 *  in a `Normalize` round-trip.  Registered via
 *  `cli/src/test/resources/META-INF/services/scalascript.backend.spi.SourceLanguage`. */
class TestSourceLanguage extends SourceLanguage:
  def id:            String = "test-mock"
  def displayName:   String = "Test Mock Plugin"
  def spiVersion:    String = SpiVersion.Current
  def canonicalName: String = "mock"

  def signatures(source: String, scope: ScopeContext): List[SymbolExport] = Nil

  def compileBlock(source: String, scope: ScopeContext, opts: BackendOptions): BlockArtifact =
    BlockArtifact(
      // Distinctive marker so the test can prove the plugin was
      // actually invoked (vs the EmbeddedBlock fallback shape).
      fragment    = ir.Content.EmbeddedBlock(language = "mock-rewritten", source = s"[mock] $source")
    )
