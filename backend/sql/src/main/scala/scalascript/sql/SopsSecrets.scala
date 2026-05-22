package scalascript.sql

/** Holds secrets loaded from a YAML document piped to ssc's stdin —
 *  typically the output of `sops -d secrets.enc.yaml`.
 *
 *  The CLI reads stdin before dispatching any command and flattens
 *  the YAML into a `Map[String, String]` with dotted-path keys:
 *
 *  {{{
 *  # secrets.enc.yaml (decrypted by sops)
 *  DB_PASSWORD: "s3cr3t"
 *  prod:
 *    user: "alice"
 *    password: "hunter2"
 *  }}}
 *
 *  makes `DB_PASSWORD`, `prod.user`, and `prod.password` available as
 *  `${sops:DB_PASSWORD}`, `${sops:prod.user}`, `${sops:prod.password}`
 *  in `databases:` front-matter.
 *
 *  List elements are keyed by index: `hosts.0`, `hosts.1`, … */
object SopsSecrets:

  @volatile private var data: Map[String, String] = Map.empty

  /** Replace the current secrets map.  Called once at process startup
   *  by the CLI after parsing stdin YAML; never called again. */
  def load(secrets: Map[String, String]): Unit =
    data = secrets

  /** Look up a dotted-path key in the loaded secrets. */
  def get(key: String): Option[String] = data.get(key)

  /** True when at least one secret has been loaded. */
  def nonEmpty: Boolean = data.nonEmpty
