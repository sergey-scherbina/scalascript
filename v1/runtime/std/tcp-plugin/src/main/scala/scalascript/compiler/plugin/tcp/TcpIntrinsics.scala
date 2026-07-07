package scalascript.compiler.plugin.tcp

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.plugin.api.{PluginComputation, PluginNative, PluginValue}

import java.io.{BufferedReader, InputStreamReader, OutputStream}
import java.net.{InetSocketAddress, ServerSocket, Socket}
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Raw, line-oriented TCP — server and client sockets, dependency-free, JVM only.
 *
 *  Sockets live in a process-local registry keyed by an integer handle, so the
 *  surface passes through ScalaScript as plain `Int`s and needs no
 *  native→interpreter callback: the calling code runs its own accept/read loop.
 *  This is the minimal capability to speak text protocols (IMAP/POP3/SMTP/Redis)
 *  against a real socket — built for the local protocol simulators. */
object TcpIntrinsics:

  private val ConnectMs = 15000

  private final class Conn(val sock: Socket):
    val in:  BufferedReader = new BufferedReader(new InputStreamReader(sock.getInputStream, StandardCharsets.UTF_8))
    val out: OutputStream   = sock.getOutputStream

  private val servers = new ConcurrentHashMap[Int, ServerSocket]()
  private val conns   = new ConcurrentHashMap[Int, Conn]()
  private val counter = new AtomicInteger(0)

  private def native(f: List[Any] => PluginValue): NativeImpl =
    PluginNative.eval { (_, args) =>
      PluginComputation.pure(f(args.map(_.unwrap)))
    }

  private def asInt(a: Any): Int = a match
    case l: Long => l.toInt
    case i: Int  => i
    case _       => -1

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    // open a listening socket on `port`; -1 on bind failure
    QualifiedName("tcpListen") -> native {
      case List(port) =>
        try
          val ss = new ServerSocket(asInt(port))
          val h  = counter.incrementAndGet()
          servers.put(h, ss)
          PluginValue.int(h)
        catch case _: Throwable => PluginValue.int(-1)
      case _ => PluginValue.int(-1)
    },

    // block until a client connects; -1 if the server socket is closed
    QualifiedName("tcpAccept") -> native {
      case List(server) =>
        val ss = servers.get(asInt(server))
        if ss == null then PluginValue.int(-1)
        else
          try
            val s = ss.accept()
            val h = counter.incrementAndGet()
            conns.put(h, new Conn(s))
            PluginValue.int(h)
          catch case _: Throwable => PluginValue.int(-1)
      case _ => PluginValue.int(-1)
    },

    // connect to host:port; -1 on failure
    QualifiedName("tcpConnect") -> native {
      case List(host: String, port) =>
        try
          val s = new Socket()
          s.connect(new InetSocketAddress(host, asInt(port)), ConnectMs)
          val h = counter.incrementAndGet()
          conns.put(h, new Conn(s))
          PluginValue.int(h)
        catch case _: Throwable => PluginValue.int(-1)
      case _ => PluginValue.int(-1)
    },

    // read one line (without the trailing CR/LF); "" at end-of-stream
    QualifiedName("tcpRecvLine") -> native {
      case List(conn) =>
        val c = conns.get(asInt(conn))
        if c == null then PluginValue.string("")
        else
          try
            val line = c.in.readLine()
            PluginValue.string(if line == null then "" else line)
          catch case _: Throwable => PluginValue.string("")
      case _ => PluginValue.string("")
    },

    // write `data` verbatim (UTF-8); the caller supplies any line ending
    QualifiedName("tcpSend") -> native {
      case List(conn, data: String) =>
        val c = conns.get(asInt(conn))
        if c != null then
          try
            c.out.write(data.getBytes(StandardCharsets.UTF_8))
            c.out.flush()
          catch case _: Throwable => ()
        PluginValue.unit
      case _ => PluginValue.unit
    },

    // close one connection
    QualifiedName("tcpClose") -> native {
      case List(conn) =>
        val c = conns.remove(asInt(conn))
        if c != null then try c.sock.close() catch case _: Throwable => ()
        PluginValue.unit
      case _ => PluginValue.unit
    },

    // close a listening server socket (unblocks a pending accept with -1)
    QualifiedName("tcpStop") -> native {
      case List(server) =>
        val ss = servers.remove(asInt(server))
        if ss != null then try ss.close() catch case _: Throwable => ()
        PluginValue.unit
      case _ => PluginValue.unit
    },

  )
