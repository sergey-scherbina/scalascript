package scalascript.interop.runtime

import org.scalatest.funsuite.AnyFunSuite

class ActorsTest extends AnyFunSuite:

  // Each test installs its own hook + clears at the end so suites don't
  // bleed state into one another.
  private def withHook[A](
      hook: (AnyRef, Any) => Unit
  )(body: => A): A =
    Actors.SendHook.install(hook)
    try body finally Actors.SendHook.clear()

  test("wrap — produces a typed ActorRef[T] over an opaque payload"):
    val raw: AnyRef = new Object
    val ref: Actors.ActorRef[String] = Actors.wrap[String](raw)
    // We can't assert much beyond constructor mechanics without a hook;
    // verify the type ascription compiled at all.
    assert(ref.isInstanceOf[Actors.ActorRef[?]])

  test("ActorRef.send — without an installed hook → IllegalStateException"):
    val ref = Actors.wrap[Int](new Object)
    Actors.SendHook.clear()
    val ex = intercept[IllegalStateException](ref.send(42))
    assert(ex.getMessage.contains("SendHook not installed"),
      s"expected install hint, got: ${ex.getMessage}")

  test("ActorRef.send — with installed hook → dispatcher invoked"):
    val captured = scala.collection.mutable.ListBuffer.empty[(AnyRef, Any)]
    val raw: AnyRef = new Object
    val ref = Actors.wrap[Int](raw)
    withHook((r, m) => captured += ((r, m))) {
      ref.send(7)
      ref.send(11)
    }
    assert(captured.length == 2)
    assert(captured(0) == (raw, 7))
    assert(captured(1) == (raw, 11))

  test("spawn — placeholder throws NotImplementedError with guidance"):
    val ex = intercept[NotImplementedError](Actors.spawn[Int](_ => ()))
    assert(ex.getMessage.contains("v0.1"),
      s"expected v0.1-status message, got: ${ex.getMessage}")
