package scalascript.cli

import scalascript.dap.{DapServer, DapSession}

object DebugCommand:
  def run(args: List[String]): Unit =
    val (flags, rest) = args.partition(_.startsWith("--"))
    val port = flags.collectFirst { case s if s.startsWith("--port=") => s.drop(7).toInt }.getOrElse(5678)
    val file = rest.headOption.getOrElse {
      System.err.println("Usage: ssc debug <file.ssc> [--port=N]")
      sys.exit(1)
    }
    println(s"DAP server listening on port $port — connect your IDE to debug $file")
    val serverThread = DapServer.listen(port, conn => DapSession(conn).run())
    serverThread.join()
