package scalascript.config

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class MergeEngineSpec extends AnyFunSuite with Matchers:

  def map(pairs: (String, String)*): ConfigValue =
    ConfigValue.Map(pairs.map((k, v) => k -> ConfigValue.Str(v)).toMap)

  test("frontmatter < files < blocks"):
    val fm    = map("a" -> "fm",   "b" -> "fm")
    val file  = map("b" -> "file", "c" -> "file")
    val block = map("c" -> "blk",  "d" -> "blk")
    val result = MergeEngine.mergeAll(fm, List(file), List(block))
    result.get("a") shouldBe Some(ConfigValue.Str("fm"))
    result.get("b") shouldBe Some(ConfigValue.Str("file"))
    result.get("c") shouldBe Some(ConfigValue.Str("blk"))
    result.get("d") shouldBe Some(ConfigValue.Str("blk"))

  test("custom priority order"):
    val fm    = map("x" -> "fm")
    val block = map("x" -> "blk")
    val result = MergeEngine.mergeAll(fm, Nil, List(block),
      order = List(Priority.Frontmatter, Priority.Blocks))
    // Frontmatter is highest priority in custom order → wins
    result.get("x") shouldBe Some(ConfigValue.Str("fm"))

  test("later file wins within Files tier"):
    val f1 = map("x" -> "first")
    val f2 = map("x" -> "second")
    val result = MergeEngine.mergeAll(ConfigValue.empty, List(f1, f2), Nil)
    result.get("x") shouldBe Some(ConfigValue.Str("second"))
