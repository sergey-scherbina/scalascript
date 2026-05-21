package scalascript.config

/** Emits JavaScript code to make config values available at runtime.
 *
 *  Three strategies:
 *  - `Bake`      — embed as `const __ssc_config = {...}` constant (default for SPA)
 *  - `ProcessEnv`— emit `process.env.KEY` lookups (Node.js apps)
 *  - `Runtime`   — emit a load from `window.__SSC_CONFIG` or `config.json` (browser / Node)
 */
object JsConfigEmitter:

  enum Strategy:
    case Bake
    case ProcessEnv
    case Runtime

  object Strategy:
    def fromString(s: String): Strategy = s.toLowerCase.trim match
      case "process-env" | "processenv" | "node" => ProcessEnv
      case "runtime" | "load"                    => Runtime
      case _                                     => Bake   // default

    def fromConfigValue(cv: ConfigValue): Strategy =
      cv.get("config.js-binding").flatMap(_.getString)
        .orElse(cv.get("config").flatMap(_.getString)) // config: process-env shorthand
        .fold(Bake)(fromString)

  /** Emit a JavaScript preamble that makes config available as `__ssc_config`. */
  def emit(cv: ConfigValue, strategy: Strategy): String = strategy match
    case Strategy.Bake       => emitBake(cv)
    case Strategy.ProcessEnv => emitProcessEnv(cv)
    case Strategy.Runtime    => emitRuntime()

  /** Embed config as a JSON constant. Referenced as `__ssc_config["server"]["port"]`. */
  private def emitBake(cv: ConfigValue): String =
    val json = toJson(cv)
    s"""const __ssc_config = $json;
       |function __ssc_cfg(path) {
       |  return path.split('.').reduce((o, k) => (o != null ? o[k] : undefined), __ssc_config);
       |}
       |""".stripMargin

  /** For Node.js: read from process.env; no __ssc_config object. */
  private def emitProcessEnv(cv: ConfigValue): String =
    val envLines = flattenToEnvKeys(cv, "").map { case (key, value) =>
      val envKey = key.toUpperCase.replace('.', '_').replace('-', '_')
      s"""  "$key": process.env["$envKey"] !== undefined ? process.env["$envKey"] : ${jsLiteral(value)},"""
    }
    s"""const __ssc_config = {
       |${envLines.mkString("\n")}
       |};
       |function __ssc_cfg(path) {
       |  return path.split('.').reduce((o, k) => (o != null ? o[k] : undefined), __ssc_config);
       |}
       |""".stripMargin

  /** Runtime load: try window.__SSC_CONFIG (browser) or require('./config.json') (Node). */
  private def emitRuntime(): String =
    """const __ssc_config = (typeof window !== 'undefined' && window.__SSC_CONFIG)
      |  ? window.__SSC_CONFIG
      |  : (typeof require !== 'undefined' ? (() => { try { return require('./config.json'); } catch(e) { return {}; } })() : {});
      |function __ssc_cfg(path) {
      |  return path.split('.').reduce((o, k) => (o != null ? o[k] : undefined), __ssc_config);
      |}
      |""".stripMargin

  /** For Runtime strategy: write the config to a `config.json` file next to the output. */
  def writeConfigJson(cv: ConfigValue, outputDir: java.nio.file.Path): Unit =
    val json = toJson(cv)
    java.nio.file.Files.writeString(outputDir.resolve("config.json"), json)

  /** Convert ConfigValue to a JSON string. */
  def toJson(cv: ConfigValue): String = cv match
    case ConfigValue.Str(s)   => "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                                         .replace("\n", "\\n").replace("\r", "\\r") + "\""
    case ConfigValue.Num(n)   => if n == n.toLong then n.toLong.toString else n.toString
    case ConfigValue.Bool(b)  => b.toString
    case ConfigValue.Null     => "null"
    case ConfigValue.Lst(vs)  => vs.map(toJson).mkString("[", ", ", "]")
    case ConfigValue.Map(m)   =>
      m.map { case (k, v) => s""""$k": ${toJson(v)}""" }.mkString("{", ", ", "}")

  private def jsLiteral(cv: ConfigValue): String = toJson(cv)

  /** Flatten a nested ConfigValue.Map to dotted-key → leaf-value pairs. */
  def flattenToEnvKeys(cv: ConfigValue, prefix: String): List[(String, ConfigValue)] =
    cv match
      case ConfigValue.Map(m) =>
        m.toList.flatMap { case (k, v) =>
          val path = if prefix.isEmpty then k else s"$prefix.$k"
          flattenToEnvKeys(v, path)
        }
      case other => List((prefix, other))
