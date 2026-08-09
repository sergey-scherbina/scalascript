package scalascript.compiler.plugin.deploy

/** Minimal JSON serialization for StateRecord — no external dependencies. */
object JsonState:

  def serialize(r: StateRecord): String =
    val slot = r.slot.map(s => s""""slot":"${esc(s)}",""").getOrElse(""""slot":null,""")
    val outputs = r.outputs.map { case (k, v) => s""""${esc(k)}":"${esc(v)}"""" }.mkString("{", ",", "}")
    s"""{
       |  "env":"${esc(r.env)}",
       |  "target":"${esc(r.target)}",
       |  $slot
       |  "revision":"${esc(r.revision)}",
       |  "artifactHash":"${esc(r.artifactHash)}",
       |  "deployedAt":"${esc(r.deployedAt)}",
       |  "deployedBy":"${esc(r.deployedBy)}",
       |  "outputs":$outputs
       |}""".stripMargin

  def parse(json: String): StateRecord =
    def field(key: String): String =
      val pattern = s""""$key"\\s*:\\s*"([^"]*)"""".r
      pattern.findFirstMatchIn(json).map(_.group(1)).getOrElse("")

    def optField(key: String): Option[String] =
      val pattern = s""""$key"\\s*:\\s*"([^"]*)"""".r
      pattern.findFirstMatchIn(json).map(_.group(1))

    def outputs: Map[String, String] =
      val start = json.indexOf("\"outputs\"")
      if start < 0 then Map.empty
      else
        val brace = json.indexOf('{', start)
        val end   = json.indexOf('}', brace)
        if brace < 0 || end < 0 then Map.empty
        else
          val inner = json.substring(brace + 1, end)
          val pair  = """"([^"]+)"\s*:\s*"([^"]*)"""".r
          pair.findAllMatchIn(inner).map(m => m.group(1) -> m.group(2)).toMap

    StateRecord(
      env          = field("env"),
      target       = field("target"),
      slot         = optField("slot"),
      revision     = field("revision"),
      artifactHash = field("artifactHash"),
      deployedAt   = field("deployedAt"),
      deployedBy   = field("deployedBy"),
      outputs      = outputs,
    )

  private def esc(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
