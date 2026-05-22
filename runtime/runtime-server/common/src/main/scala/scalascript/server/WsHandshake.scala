package scalascript.server

/** Pure helpers for the WebSocket upgrade handshake (RFC 6455 §4).
 *  No IO — each function takes parsed inputs and returns the bytes the
 *  caller should write to the client socket.  Both backends (interpreter
 *  `WsProxy._proxyConnection` upgrade arm + JvmGen serveRuntime's
 *  `_proxyConnection`) build the same response shape; centralising it
 *  here keeps the wire format (status codes, header order, Sec-WebSocket
 *  fields) in sync. */
object WsHandshake:

  /** Negotiate a subprotocol from the client's `Sec-WebSocket-Protocol`
   *  offer list against the server's preferred list.  Empty server list
   *  means "skip negotiation entirely" (returned as `Some("")` so
   *  callers don't have to distinguish "negotiated empty" from "no
   *  negotiation"); empty result with a non-empty server list means
   *  no acceptable overlap → reject with 400. */
  def negotiateSubprotocol(
      clientOffer:    String,
      serverProtocols: List[String]
  ): Option[String] =
    if serverProtocols.isEmpty then Some("")
    else
      val offered = clientOffer.split(',').iterator
        .map(_.trim).filter(_.nonEmpty).toSet
      serverProtocols.find(offered.contains)
        .map(Some(_))
        .getOrElse(None)

  /** Build the `HTTP/1.1 101 Switching Protocols` response bytes for a
   *  successful WebSocket upgrade.  `clientKey` is the value of the
   *  client's `Sec-WebSocket-Key` header (validated to be non-empty
   *  upstream); `chosenProtocol` is the negotiated subprotocol, or
   *  empty to skip the `Sec-WebSocket-Protocol` response header. */
  def upgradeResponse(clientKey: String, chosenProtocol: String): Array[Byte] =
    val accept = WsFraming.acceptKey(clientKey)
    val protoHeader = if chosenProtocol.isEmpty then "" else s"Sec-WebSocket-Protocol: $chosenProtocol\r\n"
    val resp =
      "HTTP/1.1 101 Switching Protocols\r\n" +
      "Upgrade: websocket\r\n"               +
      "Connection: Upgrade\r\n"              +
      s"Sec-WebSocket-Accept: $accept\r\n"   +
      protoHeader                            + "\r\n"
    resp.getBytes("US-ASCII")

  /** Build a short `HTTP/1.1 <status> <reason>` response with `Content-Length:
   *  0` and `Connection: close` — used to refuse WS upgrades for failed
   *  Origin / auth / cap / subprotocol checks. */
  def rejectResponse(status: Int, reason: String): Array[Byte] =
    s"HTTP/1.1 $status $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("US-ASCII")
