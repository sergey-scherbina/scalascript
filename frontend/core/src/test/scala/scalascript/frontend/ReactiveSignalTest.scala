package scalascript.frontend

import org.scalatest.funsuite.AnyFunSuite

class ReactiveSignalTest extends AnyFunSuite:

  test("ReactiveSignal subscribers receive updates and can unsubscribe"):
    val signal = ReactiveSignal[Int]("count", 0)
    val seen = scala.collection.mutable.ArrayBuffer.empty[Int]

    val unsubscribe = signal.subscribe(seen += _)
    signal.set(1)
    signal.set(2)
    unsubscribe()
    signal.set(3)

    assert(signal() == 3)
    assert(seen.toList == List(1, 2))

  test("SeedSignal follows source while pristine and preserves user edits"):
    val source = ReactiveSignal[String]("source", "Ada")
    val draft = SeedSignal("draft", source)

    assert(draft() == "Ada")
    assert(draft.isPristine)

    source.set("Grace")
    assert(draft() == "Grace")
    assert(draft.isPristine)

    draft.set("User edit")
    assert(!draft.isPristine)

    source.set("Fetched again")
    assert(draft() == "User edit")
