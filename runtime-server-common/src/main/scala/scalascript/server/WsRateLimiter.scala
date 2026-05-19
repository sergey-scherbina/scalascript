package scalascript.server

/** Per-connection inbound-message rate limiter for WebSocket sessions.
 *  Fixed 1-second window, single-threaded — the WS read-loop (selector
 *  thread on interpreter, dedicated virtual thread on codegen) is the
 *  only writer, so no synchronisation needed.  `maxMessagesPerSec <= 0`
 *  disables the cap entirely. */
final class WsRateLimiter(maxMessagesPerSec: Int):

  private var windowStartMs: Long = 0L
  private var msgsInWindow:  Int  = 0

  /** Bump the per-second counter and return `true` if the message is
   *  within budget, `false` if it should be rejected.  Caller closes
   *  the WS with code 1008 on `false`. */
  def admit(nowMs: Long): Boolean =
    if maxMessagesPerSec <= 0 then true
    else
      if nowMs - windowStartMs >= 1000L then
        windowStartMs = nowMs
        msgsInWindow  = 0
      msgsInWindow += 1
      msgsInWindow <= maxMessagesPerSec
