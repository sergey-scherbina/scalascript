package scalascript.logging

/** Thin SLF4J wrapper.  The SLF4J binding on the classpath determines
 *  actual behavior — for the ssc fat-jar that is slf4j-simple at WARN
 *  level by default (configured via simplelogger.properties).
 *
 *  Usage:
 *    private val log = Logger(getClass)
 *    log.warn("something bad")
 *    log.error("very bad", exception) */
final class Logger private[logging] (private val u: org.slf4j.Logger):
  def debug(msg: => String): Unit                   = if u.isDebugEnabled then u.debug(msg)
  def info(msg: => String): Unit                    = if u.isInfoEnabled  then u.info(msg)
  def warn(msg: => String): Unit                    = u.warn(msg)
  def error(msg: => String): Unit                   = u.error(msg)
  def error(msg: => String, cause: Throwable): Unit = u.error(msg, cause)

object Logger:
  def apply(name: String): Logger  = new Logger(org.slf4j.LoggerFactory.getLogger(name))
  def apply(cls: Class[?]): Logger = new Logger(org.slf4j.LoggerFactory.getLogger(cls))

/** Mixin for convenient per-class named loggers. */
trait Logging:
  protected val log: Logger = Logger(getClass)
