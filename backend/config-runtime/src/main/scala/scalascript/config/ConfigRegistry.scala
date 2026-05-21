package scalascript.config

/** Runtime singleton: holds the merged, substitution-resolved config
 *  for the lifetime of the current `.ssc` module run.
 *
 *  Populated by the CLI startup (after front-matter parsing, before
 *  any user code runs).  The interpreter's `config` intrinsic reads
 *  from here. */
object ConfigRegistry:
  @volatile private var current: ConfigValue       = ConfigValue.empty
  @volatile private var _sidecar: Option[ConfigValue] = None

  def set(cv: ConfigValue): Unit = current = cv

  def get: ConfigValue = current

  /** Look up a dotted path — used by SubstitutionEngine for ${config:path}. */
  def lookup(path: String): Option[String] =
    current.get(path).flatMap(_.getString)

  def reset(): Unit = current = ConfigValue.empty

  /** Sidecar config loaded from <script>.conf/.yaml/.json by the CLI.
   *  Priority: frontmatter < sidecar < fenced blocks.
   *  Set by the CLI before running; read by the Interpreter during module setup. */
  def setSidecar(cv: ConfigValue): Unit  = _sidecar = Some(cv)
  def getSidecar: Option[ConfigValue]    = _sidecar
  def clearSidecar(): Unit               = _sidecar = None
