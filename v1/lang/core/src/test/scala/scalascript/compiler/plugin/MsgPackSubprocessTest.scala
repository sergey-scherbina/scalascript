package scalascript.compiler.plugin

import org.scalatest.funsuite.AnyFunSuite

/** Stage 6+/A — verify msgpack framing wiring on the manifest →
 *  `WireFraming` chain.  End-to-end subprocess round-trip lives in
 *  an e2e smoke; SubprocessBackend's `call` uses
 *  `DataInputStream.readInt` / `DataOutputStream.writeInt` for the
 *  4-byte length prefix and upickle's `writeBinary` / `readBinary`
 *  for the body. */
class MsgPackSubprocessTest extends AnyFunSuite:

  test("WireFraming.fromManifest resolves protocol strings"):
    assert(WireFraming.fromManifest("stdio-msgpack") == WireFraming.MsgPack)
    assert(WireFraming.fromManifest("stdio-json")    == WireFraming.Json)
    // Unknown → safe default.
    assert(WireFraming.fromManifest("garbage")       == WireFraming.Json)

  test("PluginManifest parses stdio-msgpack protocol field"):
    val yaml =
      """id: mock
        |displayName: Mock
        |spiVersion: "0.1.0"
        |protocol: stdio-msgpack
        |executable: ./bin/mock
        |args: []
        |roles: [backend]
        |""".stripMargin
    val m = PluginManifest.parseString(yaml).get
    assert(m.protocol == "stdio-msgpack")
    assert(WireFraming.fromManifest(m.protocol) == WireFraming.MsgPack)

  test("end-to-end subprocess test deferred — handcrafted Process " +
       "needed; tracked as Stage 6+/A.2"):
    // SubprocessBackend.spawn(framing = MsgPack) can drive a real
    // msgpack subprocess, but tests need a portable byte-level mock
    // — bash + xxd doesn't compose like bash + jq does for JSON.
    // Pending a refactor that extracts a `Transport` abstraction
    // SubprocessBackend can take instead of a raw `Process`.
    succeed
