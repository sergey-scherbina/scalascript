package scalascript.controlapi

import org.scalatest.funsuite.AnyFunSuite
import scalascript.control.*

/**
 * Cross-host resume (control-interoperability §14.3 item 9, §14.4): a durable capsule
 * saved by one host runtime is decoded and RUN by a different host runtime, producing
 * byte-identical observable output. This is the ExactArtifact realization — the resume
 * machine is held independently by each host (the "cross-host" resume point), not shipped
 * in the bytes; only the frame + id + ABI travel on the byte-identical §9.1 wire codec.
 *
 * The JS host lane (`v2/host/js/control/test/control.test.js`) asserts the SAME cross-host
 * wire and runs the SAME capsule. Because both lanes independently FREEZE to these exact
 * bytes AND CONSUME them, the N→M cross product is closed transitively: what JVM produces
 * equals the wire equals what JS consumes, and vice versa. The Portable CodeMode variant
 * (the resume program itself travelling as a CoreIR payload, for a runner that does not
 * pre-hold the machine) remains future v2/native work; this covers ExactArtifact N→M.
 */
final class CrossHostResumeTest extends AnyFunSuite:
  // resume(state, input) = input * 10 — a runnable machine held by each host independently.
  private val timesTen = new ResumeStateMachine[Int, Int, Nothing, Int]:
    override def resume(state: Int, input: Int): Eff[Nothing, Int] =
      Eff.pure(input * 10)

  private def run(saved: SavedContinuation.Aux[Int, Nothing, Int], input: Int): Int =
    Eff.runPure(Restore.admitLocally(saved.run(input)))

  private def fromHex(hex: String): Array[Byte] =
    hex.grouped(2).map(pair => Integer.parseInt(pair, 16).toByte).toArray

  // The canonical cross-host capsule: resume point "cross-host" freezing int state 0 under
  // the open (unsigned) policy, format v3. Both host lanes assert these exact bytes.
  private val crossHostWire =
    "000000030000000a63726f73732d686f73740000000100000000000000000000000400000000000000201a9218260fcc4b03663c43df997cfab438a54e55fc8300b24aadc61381c6c4460000000000000000000000000000000000000000"

  test("a capsule frozen by one host is restored and run by another (cross-host, ExactArtifact)"):
    val point = ResumePoint.define("cross-host", timesTen, DurableCodec.int)

    // (1) This host PRODUCES the canonical cross-host wire for state 0.
    assert(point.freeze(0).encode().toString == crossHostWire)

    // (2) This host CONSUMES a capsule received as foreign wire bytes: decode is inert,
    //     restore rebinds to this host's own resume point, and run executes at the capture
    //     point — no shared in-process producer, no original artifact beyond the pinned id.
    val foreign = DurableCapsule.decode(DurableBytes.fromArray(fromHex(crossHostWire)))
    val saved = point.restore(foreign)
    assert(run(saved, 7) == 70) // 7 * 10, on the consuming host
    assert(run(saved, 3) == 30) // reusable/multi-shot on the consuming host, independent

  test("a cross-host capsule only admits on the matching resume point"):
    val point = ResumePoint.define("cross-host", timesTen, DurableCodec.int)
    val other = ResumePoint.define("other-host", timesTen, DurableCodec.int)
    val foreign = DurableCapsule.decode(DurableBytes.fromArray(fromHex(crossHostWire)))
    // a host whose resume point id differs rejects the foreign capsule at admission.
    intercept[CapsuleRejected](other.restore(foreign))
    // the matching host admits and runs it.
    assert(run(point.restore(foreign), 4) == 40)
