package scalascript.controlapi

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import org.scalatest.funsuite.AnyFunSuite
import scala.jdk.CollectionConverters.*
import scalascript.control.*

final class DirectSemanticVectorConformanceTest extends AnyFunSuite:
  import DirectSemanticVectorConformanceTest.*

  private val root = findRepositoryRoot()
  private val vectors = readRows(
    root.resolve("tests/interop-conformance/vectors.tsv"),
    "id\tslug\tlaw\tcapabilities\tphase\texpectedExit\texpectedStream\toracle"
  ).map { row =>
    VectorRow(
      id = row(0),
      slug = row(1),
      capabilities = capabilities(row(3)),
      phase = row(4),
      oracle = row(7)
    )
  }
  private val lane = readRows(
    root.resolve("tests/interop-conformance/lanes.tsv"),
    "lane\tadapter\tstatus\tcapabilities\treason"
  ).collectFirst {
    case row if row(0) == "scala-direct" =>
      Lane(row(1), row(2), capabilities(row(3)))
  }.getOrElse(fail("lanes.tsv has no scala-direct lane"))
  private val eligible = vectors.filter { vector =>
    vector.phase == "specified" &&
    vector.capabilities.subsetOf(lane.capabilities)
  }

  test("scala-direct lane and program coverage agree with the catalog"):
    assert(lane.adapter == "scala3-control-macros-test")
    assert(lane.status == "ready")
    assert(eligible.map(_.id).toSet == DirectPrograms.implementedIds)
    assert(vectors.map(_.id).distinct.size == vectors.size)
    assert(vectors.map(_.slug).distinct.size == vectors.size)

  eligible.foreach { vector =>
    test(s"vector ${vector.id} ${vector.slug}"):
      val directResult = DirectPrograms.run(vector.id)
      assert(
        directResult == vector.oracle,
        s"direct semantic oracle mismatch for ${vector.id}-${vector.slug}"
      )
      assert(
        directResult == DirectPrograms.runExplicit(vector.id),
        s"direct/explicit differential mismatch for ${vector.id}-${vector.slug}"
      )
  }

private object DirectSemanticVectorConformanceTest:
  private final case class VectorRow(
      id: String,
      slug: String,
      capabilities: Set[String],
      phase: String,
      oracle: String
  )

  private final case class Lane(
      adapter: String,
      status: String,
      capabilities: Set[String]
  )

  private def findRepositoryRoot(): Path =
    val start = Paths.get(System.getProperty("user.dir")).toAbsolutePath
    Iterator
      .iterate(start)(_.getParent)
      .takeWhile(_ != null)
      .find(path =>
        Files.isRegularFile(
          path.resolve("tests/interop-conformance/vectors.tsv")
        )
      )
      .getOrElse(
        throw new AssertionError(
          s"cannot find tests/interop-conformance/vectors.tsv above $start"
        )
      )

  private def readRows(
      path: Path,
      expectedHeader: String
  ): Vector[Vector[String]] =
    val lines = Files.readAllLines(path, StandardCharsets.UTF_8).asScala.toVector
    assert(lines.nonEmpty, s"empty catalog: $path")
    assert(lines.head == expectedHeader, s"unexpected catalog header: $path")
    lines.tail.zipWithIndex.map { case (line, index) =>
      val fields = line.split("\\t", -1).toVector
      assert(
        fields.size == expectedHeader.count(_ == '\t') + 1,
        s"${path.getFileName}:${index + 2}: malformed TSV row"
      )
      fields
    }

  private def capabilities(value: String): Set[String] =
    if value.isEmpty then Set.empty
    else value.split(",", -1).toSet

  private object DirectPrograms:
    val implementedIds: Set[String] = Set("18", "23")

    def run(id: String): String =
      id match
        case "18" => nearestMatchingResetDirect()
        case "23" => shiftNotShift0Direct()
        case other =>
          throw new AssertionError(s"unimplemented direct semantic vector $other")

    def runExplicit(id: String): String =
      id match
        case "18" => nearestMatchingResetExplicit()
        case "23" => shiftNotShift0Explicit()
        case other =>
          throw new AssertionError(s"unimplemented explicit differential $other")

    private def nearestMatchingResetDirect(): String =
      val scoped = freshPrompt[Int]
      val prompt = scoped.prompt
      val result = direct.reset[scoped.Key, Nothing, Int](prompt) {
        val nested = direct.reset[scoped.Key, Nothing, Int](prompt) {
          val selected =
            direct.shift[scoped.Key, Int, Nothing, Int](prompt)(
              [Residual >: Nothing <: Effect] =>
                (continuation: Continuation[Int, Residual, Int]) =>
                  val _ = continuation
                  Eff.pure(7)
            )
          selected
        }
        Eff.runPure(nested) + 1000
      }
      Eff.runPure(result).toString

    private def nearestMatchingResetExplicit(): String =
      val scoped = freshPrompt[Int]
      val prompt = scoped.prompt
      val shifted: Eff[Control[scoped.Key], Int] =
        shift[scoped.Key, Int, Nothing, Int](prompt)(
          [Residual >: Nothing <: Effect] =>
            (continuation: Continuation[Int, Residual, Int]) =>
              val _ = continuation
              Eff.pure(7)
        )
      val result = reset[scoped.Key, Nothing, Int](prompt) {
        reset[scoped.Key, Nothing, Int](prompt) {
          shifted
        }.map(_ + 1000)
      }
      Eff.runPure(result).toString

    private def shiftNotShift0Direct(): String =
      val scoped = freshPrompt[Int]
      val prompt = scoped.prompt
      val result = direct.reset[scoped.Key, Nothing, Int](prompt) {
        val selected =
          direct.shift[scoped.Key, Int, Nothing, Int](prompt)(
            [Residual >: Nothing <: Effect] =>
              (outer: Continuation[Int, Residual, Int]) =>
                val _ = outer
                shift[scoped.Key, Int, Residual, Int](prompt)(
                  [Nested >: Residual <: Effect] =>
                    (inner: Continuation[Int, Nested, Int]) =>
                      inner.resume(11)
                )
          )
        selected + 1000
      }
      Eff.runPure(result).toString

    private def shiftNotShift0Explicit(): String =
      val scoped = freshPrompt[Int]
      val prompt = scoped.prompt
      val nested: Eff[Control[scoped.Key], Int] =
        shift[scoped.Key, Int, Nothing, Int](prompt)(
          [Residual >: Nothing <: Effect] =>
            (outer: Continuation[Int, Residual, Int]) =>
              val _ = outer
              shift[scoped.Key, Int, Residual, Int](prompt)(
                [Nested >: Residual <: Effect] =>
                  (inner: Continuation[Int, Nested, Int]) =>
                    inner.resume(11)
              )
        )
      Eff.runPure(reset[scoped.Key, Nothing, Int](prompt)(nested)).toString
