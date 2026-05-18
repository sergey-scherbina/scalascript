package scalascript.plugin

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class SscpkgManifestTest extends AnyFunSuite with Matchers:

  test("minimal manifest with id only"):
    val yaml = """
      id: org.example.hello
    """.stripMargin
    val m = SscpkgManifest.parseString(yaml).get
    m.id          shouldBe "org.example.hello"
    m.version     shouldBe "0.1.0"
    m.spiVersion  shouldBe "0.1.0"
    m.kind        shouldBe List("library")
    m.targets     shouldBe Nil
    m.isLibrary   shouldBe true
    m.isPlugin    shouldBe false

  test("full manifest with all fields"):
    val yaml = """
      id: org.example.kafka
      version: 1.2.3
      spiVersion: "0.1.0"
      kind:
        - library
        - plugin
      targets:
        - jvm
        - interpreter
      exports:
        externDefs:
          - std.kafka.connect
          - std.kafka.publish
      capabilities:
        features:
          - HttpClient
        declares:
          - KafkaClient
    """.stripMargin
    val m = SscpkgManifest.parseString(yaml).get
    m.id               shouldBe "org.example.kafka"
    m.version          shouldBe "1.2.3"
    m.kind             shouldBe List("library", "plugin")
    m.targets          shouldBe List("jvm", "interpreter")
    m.externDefs       shouldBe List("std.kafka.connect", "std.kafka.publish")
    m.featuresRequired shouldBe List("HttpClient")
    m.featuresDeclared shouldBe List("KafkaClient")
    m.isLibrary        shouldBe true
    m.isPlugin         shouldBe true

  test("kind: plugin-only"):
    val yaml = "id: org.example.plugin\nkind: [plugin]"
    val m = SscpkgManifest.parseString(yaml).get
    m.isLibrary shouldBe false
    m.isPlugin  shouldBe true

  test("missing id fails"):
    val yaml = "version: 1.0.0"
    SscpkgManifest.parseString(yaml).isFailure shouldBe true

  test("empty YAML fails"):
    SscpkgManifest.parseString("").isFailure shouldBe true
