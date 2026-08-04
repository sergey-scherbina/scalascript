package ssc.plugin.httpfast

/** A request field failed its check and there was no `validate { … }` frame to accumulate into.
 *
 *  Carries ONLY the reason — `missing field: n`, `invalid integer for field: age` — because the
 *  message is written straight into a 400 body and read by whoever sent the request. The name of
 *  the internal block form is a fact about our implementation and has no business there; the
 *  native lane used to send it, which is half of why that answer was a 500.
 *
 *  Deliberately its own type rather than a reused `RuntimeException`: `FastHttpServer` decides
 *  400-vs-500 by the type, and a marker that anything could throw would turn genuine server
 *  faults into client errors.
 *
 *  The mirror of `scalascript.server.RestValidationError` on the v1 dispatch loop. Separate types
 *  because this engine cannot see that module; the behaviour they produce is the same, and
 *  `tests/e2e/request-validation-family-gate.sh` asserts it on BOTH lanes so the pair cannot drift.
 */
final class HandlerValidationError(reason: String) extends RuntimeException(reason)
