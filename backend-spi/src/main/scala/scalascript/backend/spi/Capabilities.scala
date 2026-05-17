package scalascript.backend.spi

/** What a Backend declares it can do.  Mandatory — core's validation
 *  pass refuses to call `compile` on a backend whose capabilities don't
 *  cover the program's required features (Stage 4).
 *
 *  `options` lists the names of `BackendOptions.extra` keys this backend
 *  understands.  Unknown keys are a warning, not an error (forward-
 *  compatible: a program can pass options for multiple backends). */
case class Capabilities(
  features: Set[Feature],
  outputs:  Set[OutputKind],
  options:  Set[String],
  spiRange: SpiVersionRange
)
