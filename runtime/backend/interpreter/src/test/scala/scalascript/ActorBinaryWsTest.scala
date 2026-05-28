package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.ActorWireProtocol
import scalascript.wire.*
import scalascript.wire.msgpack.MsgPackWireCodec

/** Unit tests for v1.62.2 actor binary WebSocket sub-protocol helpers.
 *
 *  Tests cover encode→decode roundtrips for all three v2 formats (cbor,
 *  msgpack, json), boundary strings, and the ISO-8859-1 transport
 *  convention used by the recv queue. */
class ActorBinaryWsTest extends AnyFunSuite with Matchers:

  private val sampleMessages = List(
    """{"t":"ping"}""",
    """{"t":"pong"}""",
    """{"t":"msg","to":{"nodeId":"node2","localId":42},"body":{"$str":"hello"}}""",
    """{"t":"peers_resp","peers":[["node1","ws://localhost:9001"],["node2","ws://localhost:9002"]]}""",
    """{"t":"leader_elected","leader":"node1","term":3}""",
    """{"nodeId":"node1@localhost:9001"}""",
  )

  for proto <- List(ActorWireProtocol.V2Cbor, ActorWireProtocol.V2MsgPack, ActorWireProtocol.V2Json) do
    sampleMessages.foreach { json =>
      test(s"$proto roundtrip: ${json.take(40)}"):
        val bytes  = ActorWireProtocol.encodeV2(proto, json)
        bytes.should(not be empty)
        val isoStr = new String(bytes, "ISO-8859-1")
        val result = ActorWireProtocol.decodeV2(proto, isoStr)
        result.shouldBe(Some(json))
    }

  test("isV2 identifies v2 protocols"):
    ActorWireProtocol.isV2(ActorWireProtocol.V2Cbor).shouldBe(true)
    ActorWireProtocol.isV2(ActorWireProtocol.V2MsgPack).shouldBe(true)
    ActorWireProtocol.isV2(ActorWireProtocol.V2Json).shouldBe(true)
    ActorWireProtocol.isV2(ActorWireProtocol.V1).shouldBe(false)
    ActorWireProtocol.isV2("").shouldBe(false)

  test("clientProtocols lists v2 formats before v1"):
    ActorWireProtocol.clientProtocols.head.should(startWith("ssc-actors-v2."))
    ActorWireProtocol.clientProtocols.last.shouldBe(ActorWireProtocol.V1)

  test("serverProtocols equals clientProtocols"):
    ActorWireProtocol.serverProtocols.shouldBe(ActorWireProtocol.clientProtocols)

  test("decodeV2 returns None on corrupted bytes"):
    ActorWireProtocol.decodeV2(ActorWireProtocol.V2Cbor, "not-cbor").shouldBe(None)
    ActorWireProtocol.decodeV2(ActorWireProtocol.V2MsgPack, "  ").shouldBe(None)

  test("decodeV2 returns None on malformed json envelope payload"):
    // Encode a WireValue.Null payload (not Str) — decodeV2 should return None
    // because the collect only matches WireValue.Str.
    val env   = WireEnvelope.actors("msgpack", "cluster", WireValue.Null)
    val bytes = MsgPackWireCodec.encodeEnvelope(env)
    val iso   = new String(bytes, "ISO-8859-1")
    ActorWireProtocol.decodeV2(ActorWireProtocol.V2MsgPack, iso).shouldBe(None)

  test("encodeV2 unknown protocol throws IllegalArgumentException"):
    intercept[IllegalArgumentException]:
      ActorWireProtocol.encodeV2("ssc-actors-v99.unknown", "{}")
