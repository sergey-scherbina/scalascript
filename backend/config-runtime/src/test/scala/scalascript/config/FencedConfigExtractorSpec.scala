package scalascript.config

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class FencedConfigExtractorSpec extends AnyFunSuite with Matchers:

  test("extract named yaml block"):
    val src = """
# My script
```yaml config "server"
port: 8080
host: localhost
```
Some prose.
""".trim
    val blocks = FencedConfigExtractor.extract(src)
    blocks should have size 1
    blocks.head.name    shouldBe "server"
    blocks.head.format  shouldBe ConfigParser.Format.Yaml
    blocks.head.content shouldBe "port: 8080\nhost: localhost"

  test("extract unnamed root block"):
    val src = """
```yaml config
debug: true
```
""".trim
    val blocks = FencedConfigExtractor.extract(src)
    blocks should have size 1
    blocks.head.name shouldBe ""

  test("extract json block"):
    val src = """
```json config "features"
{"dark_mode": true}
```
""".trim
    val blocks = FencedConfigExtractor.extract(src)
    blocks.head.format shouldBe ConfigParser.Format.Json
    blocks.head.name   shouldBe "features"

  test("extract hocon block"):
    val src = """
```hocon config "db"
url = "jdbc:h2:mem"
```
""".trim
    val blocks = FencedConfigExtractor.extract(src)
    blocks.head.format shouldBe ConfigParser.Format.Hocon

  test("extract multiple blocks in order"):
    val src = """
```yaml config "a"
x: 1
```
```yaml config "b"
y: 2
```
""".trim
    val blocks = FencedConfigExtractor.extract(src)
    blocks should have size 2
    blocks(0).name shouldBe "a"
    blocks(1).name shouldBe "b"

  test("does not extract non-config fenced blocks"):
    val src = """
```scala
val x = 1
```
```sql
SELECT 1
```
```yaml config "cfg"
key: val
```
""".trim
    val blocks = FencedConfigExtractor.extract(src)
    blocks should have size 1
    blocks.head.name shouldBe "cfg"

  test("extractAndParse produces FencedConfigBlocks"):
    val src = """
```yaml config "srv"
port: 9090
```
""".trim
    val blocks = FencedConfigExtractor.extractAndParse(src)
    blocks should have size 1
    blocks.head shouldBe FencedConfigBlock("srv", "port: 9090", ConfigParser.Format.Yaml)

  test("ConfigLoader integration: named block scoped correctly"):
    val src = """
```yaml config "server"
port: 7777
host: myhost
```
""".trim
    val blocks  = FencedConfigExtractor.extractAndParse(src)
    val loader  = ConfigLoader(fencedBlocks = blocks, envLookup = _ => None)
    val result  = loader.load()
    result.isRight shouldBe true
    val cfg = result.toOption.get
    cfg.get("server.port") shouldBe Some(ConfigValue.Num(7777))
    cfg.get("server.host") shouldBe Some(ConfigValue.Str("myhost"))
    cfg.get("port")        shouldBe None   // scoped, not at root
