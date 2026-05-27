package scalascript.config

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class XmlConfigParserSpec extends AnyFunSuite with Matchers:

  private def parseOk(xml: String): ConfigValue =
    XmlConfigParser.parse(xml) match
      case Right(v) => v
      case Left(e)  => fail(s"parse failed: $e")

  // ── basic leaf elements ───────────────────────────────────────────────────

  test("single string child"):
    val v = parseOk("<config><host>localhost</host></config>")
    v.get("host") shouldBe Some(ConfigValue.Str("localhost"))

  test("two string children"):
    val v = parseOk("<config><host>db</host><port>5432</port></config>")
    v.get("host") shouldBe Some(ConfigValue.Str("db"))
    v.get("port") shouldBe Some(ConfigValue.Str("5432"))

  test("empty leaf element becomes empty string"):
    val v = parseOk("<config><flag/></config>")
    v.get("flag") shouldBe Some(ConfigValue.Str(""))

  test("boolean-like text content"):
    val v = parseOk("<config><debug>true</debug></config>")
    v.get("debug") shouldBe Some(ConfigValue.Str("true"))

  test("numeric text content"):
    val v = parseOk("<config><timeout>30</timeout></config>")
    v.get("timeout") shouldBe Some(ConfigValue.Str("30"))

  // ── nested objects ────────────────────────────────────────────────────────

  test("nested element becomes nested object"):
    val v = parseOk("""
      <config>
        <database>
          <host>localhost</host>
          <port>5432</port>
        </database>
      </config>""")
    v.get("database.host") shouldBe Some(ConfigValue.Str("localhost"))
    v.get("database.port") shouldBe Some(ConfigValue.Str("5432"))

  test("three-level nesting"):
    val v = parseOk("""
      <config>
        <app>
          <server>
            <port>8080</port>
          </server>
        </app>
      </config>""")
    v.get("app.server.port") shouldBe Some(ConfigValue.Str("8080"))

  // ── attributes → _attrs ───────────────────────────────────────────────────

  test("attributes mapped to _attrs sub-object with @-prefix keys"):
    val v = parseOk("""<config><item id="1" name="foo"/></config>""")
    v.get("item._attrs.@id")   shouldBe Some(ConfigValue.Str("1"))
    v.get("item._attrs.@name") shouldBe Some(ConfigValue.Str("foo"))

  test("element with both attributes and child elements"):
    val v = parseOk("""
      <config>
        <database host="localhost" port="5432">
          <name>mydb</name>
        </database>
      </config>""")
    v.get("database._attrs.@host") shouldBe Some(ConfigValue.Str("localhost"))
    v.get("database._attrs.@port") shouldBe Some(ConfigValue.Str("5432"))
    v.get("database.name")         shouldBe Some(ConfigValue.Str("mydb"))

  // ── array-like (repeated same-name elements) → Lst ───────────────────────

  test("repeated same-name child elements become a list"):
    val v = parseOk("""
      <config>
        <item>a</item>
        <item>b</item>
        <item>c</item>
      </config>""")
    v.get("item") match
      case Some(ConfigValue.Lst(items)) =>
        items.map(_.getString.getOrElse("")) shouldBe List("a", "b", "c")
      case other => fail(s"expected Lst, got $other")

  test("two repeated elements (min list size)"):
    val v = parseOk("""<config><env>dev</env><env>test</env></config>""")
    v.get("env") match
      case Some(ConfigValue.Lst(items)) => items.size shouldBe 2
      case other => fail(s"expected Lst, got $other")

  // ── detectFormat: .xml extension ─────────────────────────────────────────

  test("detectFormat: .xml returns Format.Xml"):
    ConfigParser.detectFormat("config.xml")  shouldBe ConfigParser.Format.Xml
    ConfigParser.detectFormat("app.XML")     shouldBe ConfigParser.Format.Xml
    ConfigParser.detectFormat("data.xml")    shouldBe ConfigParser.Format.Xml

  test("detectFormat: other extensions unaffected"):
    ConfigParser.detectFormat("app.yaml")  shouldBe ConfigParser.Format.Yaml
    ConfigParser.detectFormat("app.json")  shouldBe ConfigParser.Format.Json
    ConfigParser.detectFormat("app.conf")  shouldBe ConfigParser.Format.Hocon

  // ── ConfigParser.parse dispatch ───────────────────────────────────────────

  test("ConfigParser.parse with Format.Xml returns parsed ConfigValue"):
    val xml = "<config><key>value</key></config>"
    ConfigParser.parse(xml, ConfigParser.Format.Xml) match
      case Right(v) => v.get("key") shouldBe Some(ConfigValue.Str("value"))
      case Left(e)  => fail(s"unexpected error: $e")

  test("ConfigParser.parse: malformed XML returns Left(ParseError)"):
    val result = ConfigParser.parse("<unclosed>", ConfigParser.Format.Xml)
    result shouldBe a[Left[?, ?]]
    result.left.foreach { e =>
      e shouldBe a[ConfigError.ParseError]
    }

  // ── CDATA content ──────────────────────────────────────────────────────────

  test("CDATA section content is captured as text"):
    val v = parseOk("<config><sql><![CDATA[SELECT * FROM foo WHERE x < 1]]></sql></config>")
    v.get("sql") shouldBe Some(ConfigValue.Str("SELECT * FROM foo WHERE x < 1"))
