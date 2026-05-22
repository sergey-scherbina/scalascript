package scalascript.server

/** Per-connection state machine that reassembles fragmented WebSocket
 *  messages (RFC 6455 §5.4): a first Text / Binary frame with FIN=0
 *  followed by zero or more Continuation frames with FIN=0, terminated
 *  by a Continuation with FIN=1.  Pure — no IO, no thread state.  The
 *  caller still dispatches control frames (Ping / Pong / Close)
 *  directly because those drive immediate wire writes that depend on
 *  the backend's IO model. */
object WsReassembler:
  /** Result of feeding one Text / Binary / Continuation frame.  See
   *  the case docs for the meaning. */
  enum Event:
    /** A complete message is ready — caller hands `payload` to the
     *  user's `onMessage` callback under `opcode` (Text or Binary). */
    case Deliver(opcode: WsFraming.Opcode, payload: Array[Byte])
    /** Protocol violation — caller closes the WS with `code` / `reason`
     *  (1002 for framing errors, 1009 for oversize messages). */
    case ProtocolError(code: Int, reason: String)
    /** Frame buffered; need more bytes before we can deliver. */
    case Buffered

final class WsReassembler(maxFrameBytes: Int = WsFraming.MaxFrameBytes):
  import WsReassembler.Event

  private var fragOpcode: WsFraming.Opcode | Null = null
  private val fragBuf:    java.io.ByteArrayOutputStream = new java.io.ByteArrayOutputStream()

  /** Feed one data-frame (Text / Binary / Continuation).  Throws an
   *  IllegalArgumentException for control opcodes — the caller is
   *  expected to short-circuit Ping / Pong / Close paths before
   *  reaching the reassembler. */
  def feed(frame: WsFraming.Frame): Event =
    import WsFraming.Opcode
    frame.opcode match
      case Opcode.Text | Opcode.Binary =>
        if frame.fin then
          // Single-frame message — deliver immediately, no buffering.
          if fragOpcode != null then
            Event.ProtocolError(1002, "new data frame mid-fragment")
          else
            Event.Deliver(frame.opcode, frame.payload)
        else
          // First fragment of a multi-frame message.
          if fragOpcode != null then
            Event.ProtocolError(1002, "new data frame mid-fragment")
          else
            fragOpcode = frame.opcode
            fragBuf.reset()
            fragBuf.write(frame.payload)
            if fragBuf.size > maxFrameBytes then
              fragOpcode = null
              fragBuf.reset()
              Event.ProtocolError(1009, "message too big")
            else Event.Buffered

      case Opcode.Continuation =>
        if fragOpcode == null then
          Event.ProtocolError(1002, "continuation without prior data frame")
        else
          fragBuf.write(frame.payload)
          if fragBuf.size > maxFrameBytes then
            fragOpcode = null
            fragBuf.reset()
            Event.ProtocolError(1009, "message too big")
          else if frame.fin then
            val op    = fragOpcode.asInstanceOf[Opcode]
            val bytes = fragBuf.toByteArray
            fragOpcode = null
            fragBuf.reset()
            Event.Deliver(op, bytes)
          else
            Event.Buffered

      case other =>
        throw new IllegalArgumentException(
          s"WsReassembler.feed: control opcode $other not handled here — " +
          "caller must dispatch Ping / Pong / Close before reaching the reassembler"
        )
