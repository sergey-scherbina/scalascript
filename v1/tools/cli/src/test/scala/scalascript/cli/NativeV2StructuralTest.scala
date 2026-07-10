package scalascript.cli

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import _root_.ssc.Value
import _root_.ssc.Value.*
import _root_.ssc.plugin.NativeDatabaseConfig

class NativeV2StructuralTest extends AnyFunSuite:
  private def source(): java.io.File = Files.createTempFile("ssc-native-structural-", ".ssc").toFile
  private def list(values: Value*): Value =
    values.foldRight[Value](DataV("Nil", Vector.empty))((head, tail) => DataV("Cons", Vector(head, tail)))
  private def yamlString(value: String): Value = DataV("YamlString", Vector(StrV(value)))
  private def yamlField(key: String, value: Value): Value = DataV("YamlField", Vector(StrV(key), value))
  private def yamlObject(fields: Value*): Value = DataV("YamlObject", Vector(list(fields*)))
  private def yamlOk(value: Value): Value =
    DataV("Some", Vector(DataV("YamlOk", Vector(value, IntV(0)))))
  private val noManifest: Value = DataV("None", Vector.empty)
  private val emptyProgram: Value = DataV("IrProg", Vector(
    list(), DataV("IrLit", Vector(DataV("IrUnit", Vector.empty)))))

  private def compilation(roots: List[java.io.File], manifests: List[Value]): Value =
    val paths = roots.map(file => StrV(file.getCanonicalPath.replace(java.io.File.separatorChar, '/')))
    DataV("NativeCompilation", Vector(emptyProgram, list(manifests*), list(paths*)))

  private def database(url: Option[String], user: Option[String] = None): Value =
    val fields = url.toList.map(value => yamlField("url", yamlString(value))) ++
      user.toList.map(value => yamlField("user", yamlString(value)))
    yamlObject(yamlField("databases", yamlObject(yamlField("default", yamlObject(fields*)))))

  test("decode complete structural database configuration"):
    val root = source()
    val decoded = NativeV2Structural.decode(
      compilation(List(root), List(yamlOk(database(Some("jdbc:h2:mem:native"), Some("sa"))))),
      List(root))

    assert(decoded.config.databases == Map(
      "default" -> NativeDatabaseConfig("jdbc:h2:mem:native", Some("sa"))))
    assert(decoded.manifests.head.value.nonEmpty)

  test("files without front-matter decode as absent manifests"):
    val root = source()
    val decoded = NativeV2Structural.decode(compilation(List(root), List(noManifest)), List(root))
    assert(decoded.manifests.head.value.isEmpty)
    assert(decoded.config.databases.isEmpty)

  test("identical declarations are accepted but conflicting roots fail"):
    val first = source()
    val second = source()
    val same = yamlOk(database(Some("jdbc:h2:mem:same")))
    val decoded = NativeV2Structural.decode(
      compilation(List(first, second), List(same, same)), List(first, second))
    assert(decoded.config.databases("default").url == "jdbc:h2:mem:same")

    val error = intercept[IllegalArgumentException] {
      NativeV2Structural.decode(
        compilation(List(first, second), List(
          yamlOk(database(Some("jdbc:h2:mem:a"))),
          yamlOk(database(Some("jdbc:h2:mem:b"))))),
        List(first, second))
    }
    assert(error.getMessage.contains("conflicting native database 'default'"))

  test("source-located YAML errors cross the ABI before plugin installation"):
    val root = source()
    val yamlError = DataV("Some", Vector(DataV("YamlErr", Vector(
      StrV("duplicate mapping key"), IntV(4), IntV(3), IntV(5)))))
    val error = intercept[IllegalArgumentException] {
      NativeV2Structural.decode(compilation(List(root), List(yamlError)), List(root))
    }
    assert(error.getMessage.contains(s"${root.getName}:3:5: duplicate mapping key"))

  test("database entries require a non-empty string URL"):
    val root = source()
    val error = intercept[IllegalArgumentException] {
      NativeV2Structural.decode(
        compilation(List(root), List(yamlOk(database(None, Some("sa"))))), List(root))
    }
    assert(error.getMessage.contains("requires a non-empty url"))
