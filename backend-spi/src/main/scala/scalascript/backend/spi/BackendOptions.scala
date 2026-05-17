package scalascript.backend.spi

import java.nio.file.Path

/** Options passed to `Backend.compile`.  Backends ignore options they
 *  don't understand; core validates known options against
 *  `capabilities.options` at startup and warns on the rest. */
case class BackendOptions(
  baseDir:           Option[Path]        = None,
  outputDir:         Option[Path]        = None,
  optimizationLevel: Int                 = 0,         // 0..3
  emitSourceMaps:    Boolean             = false,
  emitAssertions:    Boolean             = true,
  target:            Option[String]      = None,      // sub-target hint, e.g. "node" / "browser" / "wasi"
  extra:             Map[String, String] = Map.empty  // free-form, backend-specific
)
