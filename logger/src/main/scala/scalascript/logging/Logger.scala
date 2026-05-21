package scalascript.logging

import java.io.PrintStream

/** Self-contained logger that reads `scalascript.logger.*` system properties.
 *
 *  Configuration (via `--logs` CLI flag or `System.setProperty`):
 *    scalascript.logger.defaultLevel    — threshold for all loggers (default: warn)
 *    scalascript.logger.<name>.level   — per-logger threshold override
 *    scalascript.logger.logFile        — "System.err" (default) or "System.out"
 *
 *  This file is inlined verbatim into generated scala-cli scripts by JvmGen;
 *  it must not import anything outside the JDK standard library.
 */

private enum _SscLogLevel:
  case Debug, Info, Warn, Error

private object _SscLogLevel:
  def parse(s: String): _SscLogLevel = s.trim.toLowerCase match
    case "debug" => Debug
    case "info"  => Info
    case "error" => Error
    case _       => Warn

final class Logger private (val name: String):
  import _SscLogLevel.*

  private def threshold: _SscLogLevel =
    Option(System.getProperty(s"scalascript.logger.$name.level"))
      .orElse(Option(System.getProperty("scalascript.logger.defaultLevel")))
      .map(_SscLogLevel.parse)
      .getOrElse(Warn)

  def isDebugEnabled: Boolean = threshold == Debug
  def isInfoEnabled: Boolean  = threshold.ordinal <= Info.ordinal

  def debug(msg: => String): Unit =
    if threshold == Debug then _emit("DEBUG", msg)
  def info(msg: => String): Unit =
    if threshold.ordinal <= Info.ordinal then _emit("INFO", msg)
  def warn(msg: => String): Unit =
    if threshold.ordinal <= Warn.ordinal then _emit("WARN", msg)
  def error(msg: => String): Unit =
    _emit("ERROR", msg)
  def error(msg: => String, cause: Throwable): Unit =
    _emit("ERROR", msg); cause.printStackTrace(_logStream)

  private def _logStream: PrintStream =
    if System.getProperty("scalascript.logger.logFile") == "System.out"
    then System.out else System.err

  private def _emit(level: String, msg: String): Unit =
    _logStream.println(s"[$level] $msg")

object Logger:
  def apply(name: String): Logger  = new Logger(name)
  def apply(cls: Class[?]): Logger = new Logger(cls.getName)

/** Mixin for convenient per-class named loggers. */
trait Logging:
  protected val log: Logger = Logger(getClass)
