package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Value
import scalascript.interpreter.vm.jit.JitRefDispatch

/** Unit guard for the JIT seq-index helpers used by the bytecode-JIT'd `seq(i)` indexed read.
 *  (The end-to-end JIT firing is verified via the assembled jar / bench — a ScalaTest classpath
 *  disables the JIT. spec: specs/jit-collection-ops.md) */
class JitSeqIndexTest extends AnyFunSuite:

  private def iv(n: Long) = Value.IntV(n)

  test("seqIndexLong reads the right element from VectorV / ListV / ArrayV"):
    val vec = Value.VectorV(Vector(iv(10), iv(20), iv(30)))
    val lst = Value.ListV(List(iv(10), iv(20), iv(30)))
    val arr = Value.ArrayV(Array[Value](iv(10), iv(20), iv(30)))
    for r <- List[AnyRef](vec, lst, arr) do
      assert(JitRefDispatch.seqIndexLong(r, 0L) == 10L)
      assert(JitRefDispatch.seqIndexLong(r, 1L) == 20L)
      assert(JitRefDispatch.seqIndexLong(r, 2L) == 30L)

  test("sizeLong handles VectorV and ArrayV"):
    assert(JitRefDispatch.sizeLong(Value.VectorV(Vector(iv(1), iv(2)))) == 2L)
    assert(JitRefDispatch.sizeLong(Value.ArrayV(Array[Value](iv(1), iv(2), iv(3)))) == 3L)

  test("seqIndexLong throws (→ JIT bail) on a non-seq receiver"):
    assertThrows[ClassCastException](JitRefDispatch.seqIndexLong(Value.IntV(5), 0L))

  test("seqIndexRef returns the element Value for ref elements"):
    val vec = Value.VectorV(Vector(Value.StringV("a"), Value.StringV("b")))
    assert(JitRefDispatch.seqIndexRef(vec, 1L) == Value.StringV("b"))
