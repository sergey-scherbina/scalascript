package scalascript.gov.signing

import java.nio.file.Path

case class PfxConfig(
  keystorePath: Path,
  password:     Array[Char],
  alias:        Option[String] = None
)
