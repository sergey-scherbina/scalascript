package scalascript.server

import java.util.WeakHashMap
import java.util.concurrent.locks.{Lock, ReentrantReadWriteLock}
import scalascript.interpreter.Interpreter

/** Coordinates host callbacks per mutable Interpreter without retaining
 *  finished instances forever. Safe HTTP reads share the fair read section;
 *  mutations and WS callbacks use the exclusive section. */
private[server] object InterpreterExecutionGate:
  private val locks = WeakHashMap[Interpreter, ReentrantReadWriteLock]()

  private def lockFor(interpreter: Interpreter): ReentrantReadWriteLock = locks.synchronized {
    val existing = locks.get(interpreter)
    if existing != null then existing
    else
      val created = ReentrantReadWriteLock(true)
      locks.put(interpreter, created)
      created
  }

  private def withLock[A](lock: Lock)(body: => A): A =
    lock.lock()
    try body
    finally lock.unlock()

  def read[A](interpreter: Interpreter)(body: => A): A =
    withLock(lockFor(interpreter).readLock())(body)

  def write[A](interpreter: Interpreter)(body: => A): A =
    withLock(lockFor(interpreter).writeLock())(body)

  def forHttpMethod[A](interpreter: Interpreter, method: String)(body: => A): A =
    method.toUpperCase(java.util.Locale.ROOT) match
      case "GET" | "HEAD" | "OPTIONS" => read(interpreter)(body)
      case _                              => write(interpreter)(body)
