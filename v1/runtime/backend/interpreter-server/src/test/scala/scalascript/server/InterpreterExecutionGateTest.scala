package scalascript.server

import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter

class InterpreterExecutionGateTest extends AnyFunSuite:

  private def updateMax(maximum: AtomicInteger, value: Int): Unit =
    var previous = maximum.get()
    while value > previous && !maximum.compareAndSet(previous, value) do
      previous = maximum.get()

  private enum Mode:
    case Read, Write

  private def concurrentMaximum(a: Interpreter, modeA: Mode, b: Interpreter, modeB: Mode): Int =
    val active = AtomicInteger(0)
    val maximum = AtomicInteger(0)
    val ready = CountDownLatch(2)
    val start = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(2)

    def submit(interpreter: Interpreter, mode: Mode) = pool.submit(new java.util.concurrent.Callable[Unit]:
      def call(): Unit =
        ready.countDown()
        start.await()
        def body(): Unit =
          val now = active.incrementAndGet()
          updateMax(maximum, now)
          try Thread.sleep(80)
          finally active.decrementAndGet()
        mode match
          case Mode.Read  => InterpreterExecutionGate.read(interpreter) { body() }
          case Mode.Write => InterpreterExecutionGate.write(interpreter) { body() })

    try
      val first = submit(a, modeA)
      val second = submit(b, modeB)
      assert(ready.await(2, TimeUnit.SECONDS))
      start.countDown()
      first.get(3, TimeUnit.SECONDS)
      second.get(3, TimeUnit.SECONDS)
      maximum.get()
    finally pool.shutdownNow()

  test("serializes concurrent mutations targeting one interpreter"):
    val interpreter = Interpreter()
    assert(concurrentMaximum(interpreter, Mode.Write, interpreter, Mode.Write) == 1)

  test("a mutation excludes a safe read on the same interpreter"):
    val interpreter = Interpreter()
    assert(concurrentMaximum(interpreter, Mode.Read, interpreter, Mode.Write) == 1)

  test("safe reads remain concurrent on the same interpreter"):
    val interpreter = Interpreter()
    assert(concurrentMaximum(interpreter, Mode.Read, interpreter, Mode.Read) == 2)

  test("keeps distinct interpreter instances concurrent"):
    assert(concurrentMaximum(Interpreter(), Mode.Write, Interpreter(), Mode.Write) == 2)

  test("a queued mutation is not starved by later safe reads"):
    val interpreter = Interpreter()
    val readEntered = CountDownLatch(1)
    val releaseRead = CountDownLatch(1)
    val writerQueued = CountDownLatch(1)
    val order = ConcurrentLinkedQueue[String]()
    val pool = Executors.newFixedThreadPool(3)
    def task(body: => Unit) = pool.submit(new java.util.concurrent.Callable[Unit]:
      def call(): Unit = body)
    try
      val holdingRead = task(InterpreterExecutionGate.read(interpreter) {
        readEntered.countDown()
        releaseRead.await()
      })
      assert(readEntered.await(2, TimeUnit.SECONDS))
      val writer = task {
        writerQueued.countDown()
        InterpreterExecutionGate.write(interpreter) { order.add("write") }
      }
      assert(writerQueued.await(2, TimeUnit.SECONDS))
      Thread.sleep(20)
      val laterRead = task(InterpreterExecutionGate.read(interpreter) { order.add("read") })
      releaseRead.countDown()
      holdingRead.get(3, TimeUnit.SECONDS)
      writer.get(3, TimeUnit.SECONDS)
      laterRead.get(3, TimeUnit.SECONDS)
      assert(List(order.poll(), order.poll()) == List("write", "read"))
    finally pool.shutdownNow()

  test("is reentrant and releases the gate after an exception"):
    val interpreter = Interpreter()
    val depth = InterpreterExecutionGate.write(interpreter) {
      InterpreterExecutionGate.write(interpreter) { 2 }
    }
    assert(depth == 2)
    intercept[RuntimeException] {
      InterpreterExecutionGate.write(interpreter) {
        throw RuntimeException("boom")
      }
    }
    assert(InterpreterExecutionGate.write(interpreter) { "released" } == "released")
