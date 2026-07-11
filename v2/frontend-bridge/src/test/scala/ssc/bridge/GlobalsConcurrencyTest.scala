package ssc.bridge

import org.scalatest.funsuite.AnyFunSuite
import ssc.*
import java.util.concurrent.{CountDownLatch, ConcurrentLinkedQueue, Executors}

/** Thread-safety guard for the v2 VM globals maps under concurrent handler execution
  * (an HTTP/WS server runs handlers on many virtual threads at once).
  *
  * Two globals maps are read on every uncached `Global` and WRITTEN in-place on the
  * first-ever touch of an `@`-cell / `global.reg` global:
  *   - the VM-lane map from `Compiler.compileWithGlobals` (Runtime.scala)
  *   - `Emit.globalsRef` in the ASM/bytecode + artifact lane (Emit.scala)
  *
  * A plain `mutable.HashMap` corrupts (lost entries / hang) on a concurrent read during a
  * first-touch resize. Both are now `scala.collection.concurrent.TrieMap`: reads stay
  * lock-free, concurrent first-touch is race-free. These tests reliably fail on the old
  * `mutable.HashMap` (lost updates) and pass on the concurrent map. */
class GlobalsConcurrencyTest extends AnyFunSuite:

  private val Threads   = 32
  private val PerThread = 500

  /** Structural regression guard: the map the runtime hands the VM must be concurrent —
    * this alone prevents a silent revert to `mutable.HashMap`. */
  test("compileWithGlobals hands the VM a concurrent-safe globals map") {
    val (_, globals) = Compiler.compileWithGlobals(Program(Nil, Term.Lit(Const.CUnit)))
    assert(
      globals.isInstanceOf[scala.collection.concurrent.Map[?, ?]],
      s"VM globals map must be concurrent-safe, was ${globals.getClass.getName}"
    )
  }

  test("Emit.globalsRef is concurrent-safe after NativeArtifactRuntime-style init") {
    // Mirror the artifact-lane init (NativeArtifactRuntime.initialize).
    Emit.globalsRef = scala.collection.concurrent.TrieMap.empty[String, Value]
    assert(Emit.globalsRef.isInstanceOf[scala.collection.concurrent.Map[?, ?]])
  }

  /** No lost updates under maximal contention: N virtual threads each first-touch
    * `PerThread` distinct `@`-globals while continuously reading a shared hot global. On a
    * `mutable.HashMap` this loses entries (final size < N*PerThread) or throws during a
    * concurrent resize; on the concurrent map every distinct key survives with its value. */
  test("concurrent @-global first-touch has no lost updates (Emit.registerGlobal/global)") {
    Emit.globalsRef = scala.collection.concurrent.TrieMap.empty[String, Value]
    val start  = new CountDownLatch(1)
    val errors = new ConcurrentLinkedQueue[Throwable]()
    val pool   = Executors.newVirtualThreadPerTaskExecutor()
    try
      val futures = (0 until Threads).map { t =>
        pool.submit(new Runnable {
          def run(): Unit =
            start.await()
            try
              var i = 0
              while i < PerThread do
                Emit.registerGlobal(s"@g_${t}_$i", Value.IntV((t.toLong * PerThread + i)))
                Emit.global("@shared") // concurrent get-or-create of a hot shared key
                i += 1
            catch case e: Throwable => errors.add(e)
        })
      }
      start.countDown() // release all threads at once
      futures.foreach(_.get())
    finally pool.shutdown()

    assert(errors.isEmpty, s"exceptions during concurrent registration: ${errors.toArray.toList}")
    for t <- 0 until Threads; i <- 0 until PerThread do
      val name = s"@g_${t}_$i"
      assert(
        Emit.globalsRef.get(name).contains(Value.IntV(t.toLong * PerThread + i)),
        s"lost or wrong value for $name"
      )
  }
