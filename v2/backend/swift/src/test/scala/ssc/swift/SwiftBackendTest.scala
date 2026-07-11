package ssc.swift

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import org.scalatest.funsuite.AnyFunSuite

import ssc.{Const, Program, Reader, Term}

final class SwiftBackendTest extends AnyFunSuite:
  private val repoRoot =
    Iterator.iterate(Paths.get(sys.props("user.dir")).toAbsolutePath.normalize())(_.getParent)
      .takeWhile(_ != null)
      .find(path => Files.isRegularFile(path.resolve("build.sbt")) && Files.isDirectory(path.resolve("v2/conformance")))
      .getOrElse(fail("cannot locate repository root"))

  test("generated AppCore package is deterministic and structurally separated"):
    val program = fixture("fact")
    val first = SwiftBackend.generate(program, "Fact-App")
    val second = SwiftBackend.generate(program, "Fact-App")
    assert(first == second)
    assert(first.files.map(_._1) == Vector(
      "Package.swift",
      "Sources/AppCore/SscRuntime.swift",
      "Sources/AppCore/GeneratedProgram.swift",
      "Sources/Fact_App/main.swift",
    ))
    val all = first.files.map(_._2).mkString("\n")
    assert(all.contains("SscProgram(definitions:"))
    assert(!all.contains("scalascript.codegen.JvmGen"))
    assert(!all.contains("SwiftUIEmitter"))

  test("unsupported globals and primitives fail during generation with their names"):
    val badGlobal = Program(Nil, Term.Global("host.secret"))
    val globalError = intercept[IllegalArgumentException](SwiftBackend.generate(badGlobal))
    assert(globalError.getMessage == "swift backend: unsupported global 'host.secret'")

    val badPrimitive = Program(Nil, Term.Prim("host.secret", Nil))
    val primitiveError = intercept[IllegalArgumentException](SwiftBackend.generate(badPrimitive))
    assert(primitiveError.getMessage == "swift backend: unsupported primitive 'host.secret'")

  test("real swift run matches VM structural fixtures fact tco and map"):
    assume(swiftAvailable, "Swift toolchain is not available")
    val cases = List(
      "fact" -> "120",
      "tco" -> "500000500000",
      "map" -> "List(2, 4, 6)",
    )
    cases.foreach { (name, expected) =>
      assert(runSwift(name, fixture(name)) == expected)
    }

  test("real swift run keeps arbitrary precision BigInt arithmetic"):
    assume(swiftAvailable, "Swift toolchain is not available")
    val left = BigInt("123456789012345678901234567890")
    val right = BigInt("98765432109876543210")
    val product = Term.Prim("big.mul", List(
      Term.Lit(Const.CBig(left)),
      Term.Lit(Const.CBig(right)),
    ))
    val roundTrip = Term.Prim("big.div", List(product, Term.Lit(Const.CBig(right))))
    assert(runSwift("bigint", Program(Nil, roundTrip)) == left.toString)

  test("real swift run preserves portable Decimal scale rounding and map identity"):
    assume(swiftAvailable, "Swift toolchain is not available")
    def str(value: String) = Term.Lit(Const.CStr(value))
    def int(value: Long) = Term.Lit(Const.CInt(value))
    def dec(value: String) = Term.Prim("dec.parse", List(str(value)))
    val add = Term.Prim("dec.add", List(dec("1.20"), dec("2.3")))
    val div = Term.Prim("dec.div", List(dec("1"), dec("8"), int(3), str("HALF_UP")))
    val rounded = Term.Prim("dec.set-scale", List(dec("2.345"), int(2), str("HALF_UP")))
    val numericEquality = Term.Prim("__arith__", List(str("=="), dec("1.0"), dec("1.00")))
    val fromUnscaled = Term.Prim("dec.from-unscaled", List(Term.Lit(Const.CBig(BigInt(1230))), int(2)))
    val mapGet = Term.Prim("map.get", List(Term.Local(0), dec("1.00")))
    val body = Term.Seq(List(
      Term.Prim("map.put", List(Term.Local(0), dec("1.0"), int(7))),
      Term.Ctor("Tuple6", List(add, div, rounded, numericEquality, fromUnscaled, mapGet)),
    ))
    val program = Program(Nil, Term.Let(List(Term.Prim("map.new", Nil)), body))
    assert(runSwift("decimal", program) == "(3.50, 0.125, 2.35, true, 12.30, Some(7))")

  private def fixture(name: String): Program =
    val path = repoRoot.resolve(s"v2/conformance/$name.coreir")
    Reader.parseProgram(Files.readString(path, StandardCharsets.UTF_8))

  private def swiftAvailable: Boolean =
    try
      val process = new ProcessBuilder("swift", "--version").start()
      process.waitFor() == 0
    catch case _: Exception => false

  private def runSwift(name: String, program: Program): String =
    val root = Files.createTempDirectory(s"ssc-swift-$name-")
    val errors = root.resolve("swift.stderr")
    try
      val product = s"Ssc${name.capitalize}"
      SwiftBackend.generate(program, product).writeTo(root)
      val process = new ProcessBuilder(
        "swift", "run", "--package-path", root.toString, "--quiet", product,
      ).redirectError(errors.toFile).start()
      val stdout = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
      val exit = process.waitFor()
      val stderr = Files.readString(errors, StandardCharsets.UTF_8)
      assert(exit == 0, s"swift run $name failed ($exit):\n$stderr\n$stdout")
      stdout
    finally deleteRecursively(root)

  private def deleteRecursively(root: Path): Unit =
    if Files.exists(root) then
      val stream = Files.walk(root)
      try stream.sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists)
      finally stream.close()
