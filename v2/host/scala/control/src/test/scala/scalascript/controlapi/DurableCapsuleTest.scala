package scalascript.controlapi

import org.scalatest.funsuite.AnyFunSuite
import scalascript.control.*

final class DurableCapsuleTest extends AnyFunSuite:
  private final class Cell(var value: Int)

  private val cellCodec: DurableCodec[Cell] =
    DurableCodec.imap(DurableCodec.int)(bits => new Cell(bits))(cell => cell.value)

  private val cellMachine = new ResumeStateMachine[Cell, Int, Nothing, Int]:
    override def resume(state: Cell, input: Int): Eff[Nothing, Int] =
      state.value += input // mutate only this run's decoded frame
      Eff.pure(state.value)

  private def point(id: String): ResumePoint[Cell, Int, Nothing, Int] =
    ResumePoint.define(id, cellMachine, cellCodec)

  private def run(saved: SavedContinuation.Aux[Int, Nothing, Int], input: Int): Int =
    Eff.runPure(Restore.admitLocally(saved.run(input)))

  test("freeze -> encode -> decode -> restore -> run reproduces the state"):
    val resume = point("cell")
    val capsule = resume.freeze(new Cell(100))
    val transported = DurableCapsule.decode(capsule.encode())
    assert(transported.resumePointId == "cell")
    assert(transported.formatVersion == 3)

    val saved = resume.restore(transported)
    // reusable multi-shot; each restored run reconstructs an independent frame.
    assert(run(saved, 1) == 101) // 100 + 1
    assert(run(saved, 5) == 105) // 100 + 5, not 101 + 5

  test("a tampered frame is rejected at restore, not at decode"):
    val resume = point("cell")
    val capsule = resume.freeze(new Cell(7))
    val raw = capsule.encode().toArray
    // The 32-byte frame digest is followed by the security envelope (audience 4 +
    // tenant 4 + budget 8 + signature-length 4 = 20 trailing bytes), so the digest's
    // last byte is at length-21. Corrupt it: the envelope still decodes, but the
    // recomputed frame digest no longer matches.
    raw(raw.length - 21) = (raw(raw.length - 21) ^ 0xff).toByte

    // decode is inert: the tampered envelope parses without running or verifying.
    val tampered = DurableCapsule.decode(DurableBytes.fromArray(raw))
    intercept[CapsuleRejected](resume.restore(tampered))

    // non-vacuous: the untampered capsule still admits and runs.
    val clean = resume.restore(DurableCapsule.decode(capsule.encode()))
    assert(run(clean, 0) == 7)

  test("a capsule cannot be restored on a different resume point"):
    val producer = point("producer")
    val other = point("other")
    val capsule = producer.freeze(new Cell(3))
    intercept[CapsuleRejected](other.restore(capsule))
    // its own resume point still admits it.
    assert(run(producer.restore(capsule), 4) == 7)

  test("an unsupported format version is rejected"):
    val resume = point("cell")
    val raw = resume.freeze(new Cell(1)).encode().toArray
    raw(3) = 9.toByte // version int big-endian bytes 0..3; make it an unsupported 9
    val badVersion = DurableCapsule.decode(DurableBytes.fromArray(raw))
    assert(badVersion.formatVersion == 9)
    intercept[CapsuleRejected](resume.restore(badVersion))

  test("capsule decoding is bounded and exact"):
    val raw = point("cell").freeze(new Cell(9)).encode().toArray
    intercept[DurableDecodeError](
      DurableCapsule.decode(DurableBytes.fromArray(raw.take(raw.length - 1)))
    )
    intercept[DurableDecodeError](
      DurableCapsule.decode(DurableBytes.fromArray(raw :+ 0.toByte))
    )

  test("freezing the same state is deterministic"):
    val resume = point("cell")
    assert(resume.freeze(new Cell(50)).encode() == resume.freeze(new Cell(50)).encode())

  test("a resume point requires a non-empty id"):
    intercept[IllegalArgumentException](point(""))

  test("the in-process savable path still works from a resume point"):
    val resume = point("cell")
    val continuation = resume.savable(new Cell(20))
    assert(Eff.runPure(continuation.resume(22)) == 42)

  // Cross-lane golden capsule: the exact bytes for resume point "cell" freezing the
  // state 100 under the open (unsigned) policy. The JS lane (control.test.js) asserts
  // the SAME hex; matching bytes on both prove the versioned envelope, the SHA-256
  // frame digest, and the trailing security envelope (audience/tenant/budget/empty
  // signature) are byte-identical across lanes (Java MessageDigest here, a hand-rolled
  // SHA-256 in JS).
  test("golden capsule bytes match the cross-lane format"):
    val resume = point("cell")
    assert(
      resume.freeze(new Cell(100)).encode().toString ==
        "000000030000000463656c6c0000000100000000000000000000000400000064000000204b458482422640f4fb818274ec2b4f3d1de3a487c25f991d751e483fdc0aea9b0000000000000000000000000000000000000000"
    )
