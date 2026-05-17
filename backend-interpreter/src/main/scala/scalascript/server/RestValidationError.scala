package scalascript.server

/** Thrown by `requireString` / `requireInt` / ... when an incoming
 *  request is missing a required field or carries an unparseable
 *  value.  Caught at the route-dispatch boundary in [[WebServer]] and
 *  converted to a `400 Bad Request` response so handlers can keep
 *  writing linear validation code without explicit error checks. */
final class RestValidationError(msg: String) extends RuntimeException(msg)
