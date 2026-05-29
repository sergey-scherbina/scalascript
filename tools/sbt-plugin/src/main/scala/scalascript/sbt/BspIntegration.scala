package scalascript.sbt

import sbt._

object BspIntegration {
  def write(baseDirectory: File, binary: String, log: Logger): File = {
    val bspDir = baseDirectory / ".bsp"
    val file = bspDir / "scalascript.json"
    IO.createDirectory(bspDir)
    IO.write(
      file,
      s"""|{
          |  "name": "scalascript",
          |  "version": "0.1.0",
          |  "bspVersion": "2.1.0",
          |  "languages": ["scalascript"],
          |  "argv": ["${escape(binary)}", "lsp", "--project", "${escape(baseDirectory.getAbsolutePath)}"]
          |}
          |""".stripMargin
    )
    log.info(s"[ssc] wrote ${file.getAbsolutePath}")
    file
  }

  private def escape(value: String): String =
    value.flatMap {
      case '\\' => "\\\\"
      case '"'  => "\\\""
      case '\b' => "\\b"
      case '\f' => "\\f"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case ch if ch < ' ' => f"\\u${ch.toInt}%04x"
      case ch => ch.toString
    }
}
