package scalascript.config

/** Marker / derivation target for case-class config binding.
 *
 *  {{{
 *  case class ServerConfig(port: Int, host: String) derives Config
 *  }}}
 *
 *  `derives Config` generates a `ConfigDecoder[ServerConfig]` companion
 *  instance via `ConfigDecoder.derived`.  No runtime representation
 *  is needed — `Config` is purely a derivation hook. */
type Config[A] = ConfigDecoder[A]

object Config:
  inline def derived[A](using m: scala.deriving.Mirror.ProductOf[A]): ConfigDecoder[A] =
    ConfigDecoder.derived[A]
