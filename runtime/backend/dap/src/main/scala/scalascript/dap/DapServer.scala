package scalascript.dap

import java.net.{ServerSocket, Socket}

object DapServer:

  /** Starts a daemon thread that binds to [[port]], accepts one connection,
   *  and dispatches it to [[handler]].
   *
   *  Returns the thread so the caller can join() on it.
   */
  def listen(port: Int, handler: Socket => Unit): Thread =
    val t = Thread(() => {
      val server = ServerSocket(port)
      server.setReuseAddress(true)
      try
        val conn = server.accept()
        try handler(conn)
        finally conn.close()
      finally server.close()
    }, "dap-server")
    t.setDaemon(true)
    t.start()
    t
