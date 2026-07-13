package scalascript.uniml.dialect.yaml

import org.scalatest.funsuite.AnyFunSuite
import org.snakeyaml.engine.v2.api.{Load, LoadSettings}
import org.snakeyaml.engine.v2.schema.CoreSchema
import scalascript.uniml.{SourceId, SourceInput}

final class YamlCoreDifferentialSpec extends AnyFunSuite:
  private val source = SourceId("memory:yaml-core-differential")
  private val loader = Load(LoadSettings.builder().setSchema(CoreSchema()).build())

  test("Core Schema scalar classes agree with SnakeYAML Engine 2.9") {
    val samples = Vector(
      "~", "null", "Null", "NULL",
      "true", "True", "TRUE", "false", "False", "FALSE",
      "yes", "no", "on", "off", "2026-07-12",
      "0", "-12", "+12", "0o17", "0x1f",
      "0.0", "-1.5", ".5", "1e3", "-.Inf", ".NaN",
      "plain text",
    )

    samples.foreach { sample =>
      val external = loader.loadFromString(sample)
      val result = Yaml.parse(SourceInput.fromString(source, sample))
      val stream = Yaml.project(result).value.get.asInstanceOf[YamlValue.Stream]
      val scalar = stream.documents.head.value.get.asInstanceOf[YamlValue.Scalar].value
      assert(ourClass(scalar) == externalClass(external), s"Core Schema class mismatch for '$sample': ours=$scalar external=$external")
    }
  }

  private def ourClass(value: YamlScalar): String = value match
    case _: YamlScalar.StringValue  => "string"
    case _: YamlScalar.NullValue    => "null"
    case _: YamlScalar.BooleanValue => "boolean"
    case _: YamlScalar.IntegerValue => "integer"
    case _: YamlScalar.FloatValue   => "float"

  private def externalClass(value: Any): String = value match
    case null                    => "null"
    case _: java.lang.Boolean    => "boolean"
    case _: java.lang.Byte       => "integer"
    case _: java.lang.Short      => "integer"
    case _: java.lang.Integer    => "integer"
    case _: java.lang.Long       => "integer"
    case _: java.math.BigInteger => "integer"
    case _: java.lang.Float      => "float"
    case _: java.lang.Double     => "float"
    case _: java.math.BigDecimal => "float"
    case _: String               => "string"
    case other                   => fail(s"unexpected SnakeYAML scalar type ${other.getClass.getName}: $other")
