package scalascript.uniml.dialect.yaml

import org.scalatest.funsuite.AnyFunSuite

final class YamlPropertySyntaxSpec extends AnyFunSuite:
  test("tag handles, URI units, and shorthand suffixes implement YAML 1.2.2") {
    Vector("!", "!!", "!e!", "!e-1!").foreach(value =>
      assert(YamlPropertySyntax.validHandle(value), value)
    )
    Vector("", "e!", "!e", "!é!", "!e_!", "!!!").foreach(value =>
      assert(!YamlPropertySyntax.validHandle(value), value)
    )

    Vector(
      "!name",
      "!a:b",
      "!a#b",
      "!%2f%2F",
      "!!str",
      "!e!suffix",
      "!<tag:yaml.org,2002:str>",
      "!<!local>",
    ).foreach(value => assertRight(YamlPropertySyntax.validateTagSpelling(value), value))

    Vector(
      "!!",
      "!e!",
      "!a!b!c",
      "!a,b",
      "!a[b",
      "!a]b",
      "!a{b",
      "!a}b",
      "!café",
      "!😀",
      "!bad%",
      "!bad%0",
      "!bad%GG",
      "!<>",
    ).foreach(value => assertLeft(YamlPropertySyntax.validateTagSpelling(value), value))

    Vector(
      "%00",
      "%2f",
      "%2F",
      "abc-09",
      "#;/?:@&=+$,_.!~*'()[]",
    ).foreach(value => assertRight(YamlPropertySyntax.validateUriCharacters(value), value))

    Vector(
      "%",
      "%0",
      "%GG",
      "é",
      "😀",
      "{}",
      "\"",
      "<>",
      "\\",
      "^",
      "`",
      "|",
      " ",
    ).foreach(value => assertLeft(YamlPropertySyntax.validateUriCharacters(value), value))
  }

  test("tag prefixes preserve exact percent spelling and defer scheme validation to expansion") {
    Vector("!", "!foo,[]!", "tag:example.com,2000:", "relative", "%2froot").foreach(value =>
      assertRight(YamlPropertySyntax.validateTagPrefix(value), value)
    )
    Vector("", ",bad", "[bad", "]bad", "!café", "bad{prefix", "bad}prefix").foreach(value =>
      assertLeft(YamlPropertySyntax.validateTagPrefix(value), value)
    )

    val environment =
      YamlTagEnvironment.defaults
        .register("!e!", "tag:example.com,2000:%2f")
        .fold(message => fail(message), identity)
    assert(environment.expand("!e!suffix%2F") == Right("tag:example.com,2000:%2fsuffix%2F"))

    val relative =
      YamlTagEnvironment.defaults
        .register("!e!", "relative")
        .fold(message => fail(message), identity)
    assert(relative.expand("!e!suffix").isLeft)
  }

  test("RFC 3986 URI validation is portable and complete for effective global tags") {
    val valid = Vector(
      "a:",
      "A+1.-:x",
      "urn:example:animal:ferret:nose",
      "mailto:John.Doe@example.com",
      "tag:example.com,2000:app/foo#variant",
      "x:/",
      "x:/a//b/",
      "x:a:b@c;d=e",
      "x://",
      "x://@",
      "x://:",
      "x://host:",
      "x://host:999999999999999999999/",
      "x:///a",
      "x:////a",
      "x://127.0.0.1/",
      "x://256.1.1.1/",
      "x://01.2.3.4/",
      "x://[::1]/",
      "x://[v1.a:b]/",
      "x:?a/b?c",
      "x:#a/b?c",
      "x:?#",
      "x:/p?q/r?s#f/g?h",
      "x:a%2fb/%2F?q=%23#f%2f",
      "x:%FF",
    )
    valid.foreach(value => assertRight(Rfc3986UriSyntax.validateUri(value), value))

    val invalid = Vector(
      "",
      "x",
      ":x",
      "1x:x",
      "+x:x",
      "x_y:x",
      "x:é",
      "x:%",
      "x:%0",
      "x:%GG",
      "x:/a b",
      "x:/a[b]",
      "x:/a{b}",
      "x:?a[b]",
      "x:#a#b",
      "x://a@b@c",
      "x://a[b]",
      "x://host:12x",
      "x://host:80:90",
      "x://[::1",
      "x://[::1]junk",
      "x://::1/",
      "x://[]",
      "x://[v1.a%41]",
      "x://[v1.a@b]",
      "x://[fe80::1%25eth0]",
    )
    invalid.foreach(value => assertLeft(Rfc3986UriSyntax.validateUri(value), value))
  }

  test("IPv4, IPv6, and IPvFuture literals follow the RFC grammar") {
    Vector("0.0.0.0", "9.10.99.255", "192.0.2.1", "255.255.255.255").foreach(value =>
      assert(Rfc3986UriSyntax.validIpv4(value), value)
    )
    Vector("01.2.3.4", "1.02.3.4", "256.0.0.1", "1.2.3", "1.2.3.4.5", "1..3.4", "1.2.3.-1", "1.2.3.4x")
      .foreach(value => assert(!Rfc3986UriSyntax.validIpv4(value), value))

    Vector(
      "::",
      "::1",
      "1::",
      "1:2:3:4:5:6:7:8",
      "2001:db8::7",
      "1:2:3:4:5:6:192.0.2.1",
      "::ffff:192.0.2.128",
      "::192.0.2.1",
      "1:2:3:4:5::192.0.2.1",
    ).foreach(value => assert(Rfc3986UriSyntax.validIpv6(value), value))
    Vector(
      "",
      ":",
      ":::",
      "1::2::3",
      "1:2:3:4:5:6:7",
      "1:2:3:4:5:6:7:8:9",
      "1:2:3:4:5:6:7:8::",
      "12345::",
      "gggg::",
      "192.0.2.1::",
      "::192.0.2.1:1",
      "::ffff:192.168.001.1",
      "::ffff:256.1.1.1",
      "1:2:3:4:5:6::192.0.2.1",
    ).foreach(value => assert(!Rfc3986UriSyntax.validIpv6(value), value))

    Vector("v1.a", "VABC.foo:bar", "vF.!$&'()*+,;=:").foreach(value =>
      assert(Rfc3986UriSyntax.validIpvFuture(value), value)
    )
    Vector("v.a", "vG.a", "v1", "v1.", "v1.a/b", "v1.a@b", "v1.a%41", "v1.a?b", "v1.a#b")
      .foreach(value => assert(!Rfc3986UriSyntax.validIpvFuture(value), value))
  }

  test("property scans preserve ranges, boundaries, and exact anchor characters") {
    val tag = YamlPropertySyntax.scan("!e!suffix, next", 0, YamlPropertyKind.Tag)
    assert(tag.start == 0)
    assert(tag.end == 9)
    assert(tag.handle.contains(YamlPropertyRange(0, 3)))
    assert(tag.suffix.contains(YamlPropertyRange(3, 9)))
    assert(tag.boundary == YamlPropertyBoundary.Flow(','))
    assert(!tag.hadSeparation)
    assert(tag.failure.isEmpty)

    val adjacent = YamlPropertySyntax.scan("!foo[x]", 0, YamlPropertyKind.Tag)
    assert(adjacent.end == 4)
    assert(adjacent.boundary == YamlPropertyBoundary.Flow('['))
    assert(YamlPropertySyntax.boundaryFailure(adjacent, None).nonEmpty)
    assert(YamlPropertySyntax.boundaryFailure(adjacent, Some(']')).nonEmpty)

    val inFlow = YamlPropertySyntax.scan("!!str, value", 0, YamlPropertyKind.Tag)
    assert(YamlPropertySyntax.boundaryFailure(inFlow, Some(']')).isEmpty)
    assert(YamlPropertySyntax.boundaryFailure(inFlow, None).nonEmpty)

    Vector("a", "a:", ":@*!$\"<foo>:", "a#b", "a%", "😁").foreach(value => {
      assertRight(YamlPropertySyntax.validateAnchorName(value), value)
      assertRight(YamlPropertySyntax.validateAliasName(value), value)
    })
    Vector("", "a b", "a\tb", "a,b", "a[b", "a]b", "a{b", "a}b", "\uFEFF").foreach(value => {
      assertLeft(YamlPropertySyntax.validateAnchorName(value), value)
      assertLeft(YamlPropertySyntax.validateAliasName(value), value)
    })
  }

  private def assertRight[A](value: Either[?, A], clue: String): Unit =
    assert(value.isRight, s"$clue -> $value")

  private def assertLeft[A](value: Either[?, A], clue: String): Unit =
    assert(value.isLeft, s"$clue -> $value")
