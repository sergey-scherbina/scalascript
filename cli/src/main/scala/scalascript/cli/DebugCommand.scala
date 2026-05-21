package scalascript.cli

import scalascript.dap.{DapServer, DapSession}
import scalascript.interpreter.Interpreter

object DebugCommand:
  def run(args: List[String]): Unit =
    val (flags, rest) = args.partition(_.startsWith("--"))
    val port = flags.collectFirst { case s if s.startsWith("--port=") => s.drop(7).toInt }.getOrElse(5678)
    val file = rest.headOption.getOrElse {
      System.err.println("Usage: ssc debug <file.ssc> [--port=N]")
      sys.exit(1)
    }
    println(s"DAP server listening on port $port — connect your IDE to debug $file")
    val serverThread = DapServer.listen(port, conn => runSession(file, conn))
    serverThread.join()

  /** Run a full DAP session: wire the interpreter hooks, start the interpreter
   *  in a background thread, then handle DAP messages until disconnect.
   *
   *  Threading:
   *  - Interpreter runs in a virtual thread.  It calls [[DapSession.awaitReady]]
   *    before executing any user code, so all breakpoints are registered first.
   *  - DAP message loop runs on the current thread (inside [[DapSession.run]]).
   */
  private[cli] def runSession(file: String, conn: java.net.Socket): Unit =
    import scalascript.parser.Parser

    val session = DapSession(conn)
    val hooks   = session.mkHooks()

    val filePath = os.Path(file, os.pwd)
    val interp   = Interpreter(out = System.out, baseDir = Some(filePath / os.up))
    interp.setDebugSourceFile(filePath.toString)
    interp.setDebugHooks(Some(hooks))

    val src    = os.read(filePath)
    val module = Parser.parse(src)

    // Start the interpreter in a virtual thread.  It will block on awaitReady()
    // until the client sends configurationDone, ensuring all breakpoints are
    // registered before user code starts executing.
    val interpThread = Thread.ofVirtual().start(() => {
      session.awaitReady()
      try interp.run(module)
      catch case _: Throwable => ()
    })

    // Handle DAP messages on this thread until the client disconnects.
    session.run()

    // Interrupt the interpreter thread in case it is still running or suspended.
    interpThread.interrupt()

  /** Entry point for tests: binds the DAP server to [[port]], waits for one
   *  connection, then runs [[runSession]].  Blocks until the session ends. */
  def runWithPort(file: String, port: Int): Unit =
    val serverSock = java.net.ServerSocket(port)
    serverSock.setReuseAddress(true)
    try
      val conn = serverSock.accept()
      try runSession(file, conn)
      finally conn.close()
    finally serverSock.close()
