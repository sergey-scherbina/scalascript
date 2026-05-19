package scalascript.server

/** Per-frame WebSocket dispatch logic shared by the interpreter
 *  `WsConnection.onFrame` (NIO selector thread) and the codegen
 *  `serveRuntime._runReadLoop` (per-connection virtual thread).  Both
 *  backends still own their own read-loop thread model and IO
 *  mechanics; this object only encapsulates:
 *
 *    - the RFC 6455 opcode match (Ping / Pong / Close / data)
 *    - the [[WsReassembler]] hand-off for Text / Binary / Continuation
 *    - the Close-payload status decode (`payload[0..2]` → big-endian
 *      uint16, defaulting to 1000 when no payload)
 *
 *  Backends pass in plain function values for every wire-write /
 *  executor-dispatch / user-callback step — the shared object never
 *  touches their thread state, sockets, or interpreter handles. */
object WsFrameDispatch:

  /** Tells the caller whether to keep reading after this frame.  `Stop`
   *  is returned for peer-initiated Close and for any reassembler
   *  `ProtocolError`; the caller exits its read loop, its `finally`
   *  runs cleanup, and the connection terminates. */
  enum Outcome:
    case Continue, Stop

  /** Process one frame.
   *
   *  @param frame        the demasked frame just parsed off the wire
   *  @param reassembler  per-connection reassembly state
   *  @param onPing       caller should write a Pong with `payload`
   *  @param onPong       caller should refresh liveness + fire user
   *                      `onPong` (payload is the peer's echo bytes)
   *  @param onPeerClose  peer sent us a Close; status decoded from
   *                      payload, raw payload supplied if the caller
   *                      wants the reason text.  Caller decides
   *                      whether to echo Close back (RFC 6455 §5.5.1)
   *                      or just tear down — the shared loop returns
   *                      `Stop` regardless.
   *  @param onDeliver    fully-reassembled Text / Binary message
   *                      ready for the user `onMessage` callback
   *  @param onProtocolError reassembler reported a framing violation
   *                      or oversize message — caller should send
   *                      a Close with the given code/reason */
  def handle(
      frame:             WsFraming.Frame,
      reassembler:       WsReassembler,
      onPing:            Array[Byte] => Unit,
      onPong:            Array[Byte] => Unit,
      onPeerClose:       (Int, Array[Byte]) => Unit,
      onDeliver:         (WsFraming.Opcode, Array[Byte]) => Unit,
      onProtocolError:   (Int, String) => Unit
  ): Outcome =
    import WsFraming.Opcode
    frame.opcode match
      case Opcode.Ping =>
        onPing(frame.payload)
        Outcome.Continue
      case Opcode.Pong =>
        onPong(frame.payload)
        Outcome.Continue
      case Opcode.Close =>
        val status =
          if frame.payload.length >= 2
          then ((frame.payload(0) & 0xFF) << 8) | (frame.payload(1) & 0xFF)
          else 1000
        onPeerClose(status, frame.payload)
        Outcome.Stop
      case Opcode.Text | Opcode.Binary | Opcode.Continuation =>
        reassembler.feed(frame) match
          case WsReassembler.Event.Deliver(op, bytes) =>
            onDeliver(op, bytes)
            Outcome.Continue
          case WsReassembler.Event.ProtocolError(code, reason) =>
            onProtocolError(code, reason)
            Outcome.Stop
          case WsReassembler.Event.Buffered =>
            Outcome.Continue
