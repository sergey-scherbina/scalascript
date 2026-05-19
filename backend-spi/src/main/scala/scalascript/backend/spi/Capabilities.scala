package scalascript.backend.spi

/** What a Backend declares it can do.  Mandatory — core's validation
 *  pass refuses to call `compile` on a backend whose capabilities don't
 *  cover the program's required features (Stage 4).
 *
 *  `options` lists the names of `BackendOptions.extra` keys this backend
 *  understands.  Unknown keys are a warning, not an error (forward-
 *  compatible: a program can pass options for multiple backends).
 *
 *  `blockLanguages` enumerates the **opaque-executable** fenced-code
 *  language tags this backend handles (e.g. `"node.js"`, `"sql"`).
 *  Defaults to empty: a module containing such a block aimed at a
 *  backend that doesn't list its tag here triggers
 *  `Diagnostic.UnknownBlockLanguage` from `CapabilityCheck`.  String
 *  blocks (`html` / `css` / `javascript`) are universally supported
 *  (rendered to `String`) and do **not** need to be enumerated here. */
case class Capabilities(
  features:       Set[Feature],
  outputs:        Set[OutputKind],
  options:        Set[String],
  spiRange:       SpiVersionRange,
  blockLanguages: Set[String] = Set.empty
)
