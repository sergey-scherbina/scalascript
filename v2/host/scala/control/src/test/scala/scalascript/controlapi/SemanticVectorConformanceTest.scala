package scalascript.controlapi

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import org.scalatest.funsuite.AnyFunSuite
import scala.jdk.CollectionConverters.*
import scalascript.control.*

final class SemanticVectorConformanceTest extends AnyFunSuite:
  import SemanticVectorConformanceTest.*

  private val root = findRepositoryRoot()
  private val vectors = readVectors(
    root.resolve("tests/interop-conformance/vectors.tsv")
  )
  private val lanes = readLanes(
    root.resolve("tests/interop-conformance/lanes.tsv")
  )
  private val scalaLane = lanes.find(_.id == "scala-explicit").getOrElse(
    fail("lanes.tsv has no scala-explicit lane")
  )
  private val eligible = vectors.filter { vector =>
    vector.phase == "specified" &&
    vector.capabilities.subsetOf(scalaLane.capabilities)
  }

  test("scala-explicit lane and program coverage agree with the catalog"):
    assert(scalaLane.adapter == "scala3-control-test")
    assert(scalaLane.status == "ready")
    assert(eligible.map(_.id).toSet == SemanticPrograms.implementedIds)
    assert(vectors.map(_.id).distinct.size == vectors.size)
    assert(vectors.map(_.slug).distinct.size == vectors.size)

  eligible.foreach { vector =>
    test(s"vector ${vector.id} ${vector.slug}"):
      assert(
        SemanticPrograms.run(vector.id) == vector.oracle,
        s"semantic oracle mismatch for ${vector.id}-${vector.slug}"
      )
  }

private object SemanticVectorConformanceTest:
  private final case class SemanticVector(
      id: String,
      slug: String,
      capabilities: Set[String],
      phase: String,
      oracle: String
  )

  private final case class Lane(
      id: String,
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

  private def readRows(path: Path, expectedHeader: String): Vector[Vector[String]] =
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

  private def readVectors(path: Path): Vector[SemanticVector] =
    readRows(
      path,
      "id\tslug\tlaw\tcapabilities\tphase\texpectedExit\texpectedStream\toracle"
    ).map { row =>
      SemanticVector(
        id = row(0),
        slug = row(1),
        capabilities = capabilities(row(3)),
        phase = row(4),
        oracle = row(7)
      )
    }

  private def readLanes(path: Path): Vector[Lane] =
    readRows(
      path,
      "lane\tadapter\tstatus\tcapabilities\treason"
    ).map { row =>
      Lane(
        id = row(0),
        adapter = row(1),
        status = row(2),
        capabilities = capabilities(row(3))
      )
    }

  private object SemanticPrograms:
    val implementedIds: Set[String] =
      Set(
        "01", "02", "03", "04", "05", "06", "07", "08", "09",
        "18", "19", "20", "21", "22", "23", "24", "25"
      )

    private object One extends Effect:
      val key: EffectKey[One.type] =
        EffectKey.named(EffectId("One"), this)

    private case object OneOp extends Operation[One.type, Int]:
      val effect: EffectKey[One.type] = One.key
      val id: OperationId = OperationId(effect.id, "op")
      override val multiplicity: ResumeMultiplicity =
        ResumeMultiplicity.OneShot

    private object Choice extends Effect:
      val key: EffectKey[Choice.type] =
        EffectKey.named(EffectId("Vector.Choice"), this)

    private final case class Pick(values: Vector[Int])
        extends Operation[Choice.type, Int]:
      val effect: EffectKey[Choice.type] = Choice.key
      val id: OperationId = OperationId(effect.id, "pick")

    private object Yield extends Effect:
      val key: EffectKey[Yield.type] =
        EffectKey.named(EffectId("Vector.Yield"), this)

    private final case class Emit(value: Int)
        extends Operation[Yield.type, Unit]:
      val effect: EffectKey[Yield.type] = Yield.key
      val id: OperationId = OperationId(effect.id, "emit")
      override val multiplicity: ResumeMultiplicity =
        ResumeMultiplicity.OneShot

    private object Abort extends Effect:
      val key: EffectKey[Abort.type] =
        EffectKey.named(EffectId("Vector.Abort"), this)

    private case object Stop extends Operation[Abort.type, Int]:
      val effect: EffectKey[Abort.type] = Abort.key
      val id: OperationId = OperationId(effect.id, "stop")
      override val multiplicity: ResumeMultiplicity =
        ResumeMultiplicity.OneShot

    private object Amb extends Effect:
      val key: EffectKey[Amb.type] =
        EffectKey.named(EffectId("Vector.Amb"), this)

    private case object Flip extends Operation[Amb.type, Boolean]:
      val effect: EffectKey[Amb.type] = Amb.key
      val id: OperationId = OperationId(effect.id, "flip")
      override val multiplicity: ResumeMultiplicity =
        ResumeMultiplicity.OneShot

    private object Get extends Effect:
      val key: EffectKey[Get.type] =
        EffectKey.named(EffectId("Vector.Get"), this)

    private case object GetValue extends Operation[Get.type, Int]:
      val effect: EffectKey[Get.type] = Get.key
      val id: OperationId = OperationId(effect.id, "get")
      override val multiplicity: ResumeMultiplicity =
        ResumeMultiplicity.OneShot

    private object Read extends Effect:
      val key: EffectKey[Read.type] =
        EffectKey.named(EffectId("Vector.Read"), this)

    private case object ReadValue extends Operation[Read.type, Int]:
      val effect: EffectKey[Read.type] = Read.key
      val id: OperationId = OperationId(effect.id, "read")
      override val multiplicity: ResumeMultiplicity =
        ResumeMultiplicity.OneShot

    private object Write extends Effect:
      val key: EffectKey[Write.type] =
        EffectKey.named(EffectId("Vector.Write"), this)

    private final case class WriteValue(value: Int)
        extends Operation[Write.type, Unit]:
      val effect: EffectKey[Write.type] = Write.key
      val id: OperationId = OperationId(effect.id, "write")
      override val multiplicity: ResumeMultiplicity =
        ResumeMultiplicity.OneShot

    private object Tick extends Effect:
      val key: EffectKey[Tick.type] =
        EffectKey.named(EffectId("Vector.Tick"), this)

    private case object TickStep extends Operation[Tick.type, Int]:
      val effect: EffectKey[Tick.type] = Tick.key
      val id: OperationId = OperationId(effect.id, "step")
      override val multiplicity: ResumeMultiplicity =
        ResumeMultiplicity.OneShot

    def run(id: String): String =
      id match
        case "01" => oneShotResume()
        case "02" => multiShotResume()
        case "03" => deepTailCalls()
        case "04" => callbackReentry()
        case "05" => captureResumeSameHost()
        case "06" => zeroResume()
        case "07" => handlerReinstall()
        case "08" => returnTransform()
        case "09" => nondeterminismProduct()
        case "18" => nearestMatchingReset()
        case "19" => residualForwarding()
        case "20" => deepEffectStackSafety()
        case "21" => oneShotDiagnostic()
        case "22" => freshPromptIsolation()
        case "23" => shiftNotShift0()
        case "24" => multiShotSharedHeap()
        case "25" => unmanagedCapture()
        case other => throw new AssertionError(s"unimplemented semantic vector $other")

    private def resumeReusable[A, Fx <: Effect, R](
        resumption: Resumption[A, Fx, R],
        value: A
    ): Eff[Fx, R] =
      resumption match
        case Resumption.Reusable(continuation) => continuation.resume(value)
        case Resumption.OneShot(_) =>
          throw new AssertionError("expected reusable resumption")

    private def resumeOneShot[A, Fx <: Effect, R](
        resumption: Resumption[A, Fx, R],
        value: A
    ): Eff[Fx, R] =
      resumption match
        case Resumption.OneShot(continuation) =>
          continuation.tryResume(value) match
            case Right(next) => next
            case Left(reason) =>
              throw new AssertionError(s"first resume was rejected: $reason")
        case Resumption.Reusable(_) =>
          throw new AssertionError("expected one-shot resumption")

    private def oneShotResume(): String =
      val body = perform(OneOp).map(_ + 1)
      val handled = handle[One.type, Nothing, Int, Int](body)(
        new Handler[One.type, Nothing, Int, Int]:
          val effect: EffectKey[One.type] = One.key
          def onReturn(value: Int): Eff[Nothing, Int] = Eff.pure(value)
          def onOperation[X](
              operation: Operation[One.type, X],
              resumption: Resumption[X, Nothing, Int]
          ): Eff[Nothing, Int] =
            operation match
              case OneOp => resumeOneShot(resumption, 41)
      )
      Eff.runPure(handled).toString

    private def multiShotResume(): String =
      val handled = handle[Choice.type, Nothing, Int, Vector[Int]](
        perform(Pick(Vector(1, 2)))
      )(
        new Handler[Choice.type, Nothing, Int, Vector[Int]]:
          val effect: EffectKey[Choice.type] = Choice.key
          def onReturn(value: Int): Eff[Nothing, Vector[Int]] =
            Eff.pure(Vector(value))
          def onOperation[X](
              operation: Operation[Choice.type, X],
              resumption: Resumption[X, Nothing, Vector[Int]]
          ): Eff[Nothing, Vector[Int]] =
            operation match
              case Pick(values) =>
                values.foldLeft(
                  Eff.pure(Vector.empty[Int]): Eff[Nothing, Vector[Int]]
                ) { (result, value) =>
                  result.flatMap(prefix =>
                    resumeReusable(resumption, value).map(prefix ++ _)
                  )
                }
      )
      Eff.runPure(handled).mkString(",")

    private def deepTailCalls(): String =
      val limit = 2_000_000
      val machine = new StateMachine[Int, Nothing, Int]:
        def step(state: Int): MachineStep[Int, Nothing, Int] =
          if state == limit then MachineStep.Done(state)
          else MachineStep.Continue(state + 1)
      Eff.runPure(StateMachine.run(0, machine)).toString

    private def callbackReentry(): String =
      def apply3(callback: Int => Int, value: Int): Int =
        callback(callback(callback(value)))
      apply3(_ * 2, 1).toString

    private def captureResumeSameHost(): String =
      val body: Eff[Yield.type, Int] =
        perform(Emit(10)).flatMap(_ =>
          perform(Emit(20)).map(_ => 0)
        )
      val handled = handle[Yield.type, Nothing, Int, Int => Int](body)(
        new Handler[Yield.type, Nothing, Int, Int => Int]:
          val effect: EffectKey[Yield.type] = Yield.key
          def onReturn(value: Int): Eff[Nothing, Int => Int] =
            Eff.pure(state => value + state)
          def onOperation[X](
              operation: Operation[Yield.type, X],
              resumption: Resumption[X, Nothing, Int => Int]
          ): Eff[Nothing, Int => Int] =
            operation match
              case Emit(value) =>
                resumeOneShot(resumption, ()).map { next =>
                  state => next(state + value)
                }
      )
      Eff.runPure(handled)(0).toString

    private def zeroResume(): String =
      val body = perform(Stop).map(_ + 1000)
      val handled = handle[Abort.type, Nothing, Int, Int](body)(
        new Handler[Abort.type, Nothing, Int, Int]:
          val effect: EffectKey[Abort.type] = Abort.key
          def onReturn(value: Int): Eff[Nothing, Int] = Eff.pure(value)
          def onOperation[X](
              operation: Operation[Abort.type, X],
              resumption: Resumption[X, Nothing, Int]
          ): Eff[Nothing, Int] =
            operation match
              case Stop =>
                val _ = resumption
                Eff.pure(-1)
      )
      Eff.runPure(handled).toString

    private def handlerReinstall(): String =
      def loop(remaining: Int): Eff[Amb.type, Int] =
        if remaining == 0 then Eff.pure(0)
        else
          perform(Flip).flatMap { selected =>
            Eff.defer(loop(remaining - 1)).map { suffix =>
              (if selected then 1 else 0) + suffix
            }
          }
      val handled = handle[Amb.type, Nothing, Int, Int](loop(3))(
        new Handler[Amb.type, Nothing, Int, Int]:
          val effect: EffectKey[Amb.type] = Amb.key
          def onReturn(value: Int): Eff[Nothing, Int] = Eff.pure(value)
          def onOperation[X](
              operation: Operation[Amb.type, X],
              resumption: Resumption[X, Nothing, Int]
          ): Eff[Nothing, Int] =
            operation match
              case Flip => resumeOneShot(resumption, true)
      )
      Eff.runPure(handled).toString

    private def returnTransform(): String =
      val body = perform(GetValue).map(_ * 2)
      val handled = handle[Get.type, Nothing, Int, Vector[Int]](body)(
        new Handler[Get.type, Nothing, Int, Vector[Int]]:
          val effect: EffectKey[Get.type] = Get.key
          def onReturn(value: Int): Eff[Nothing, Vector[Int]] =
            Eff.pure(Vector(value, value))
          def onOperation[X](
              operation: Operation[Get.type, X],
              resumption: Resumption[X, Nothing, Vector[Int]]
          ): Eff[Nothing, Vector[Int]] =
            operation match
              case GetValue => resumeOneShot(resumption, 21)
      )
      Eff.runPure(handled).mkString(",")

    private def nondeterminismProduct(): String =
      val body = perform(Pick(Vector(1, 2))).flatMap { first =>
        perform(Pick(Vector(10, 20))).map(second => first + second)
      }
      val handled = handle[Choice.type, Nothing, Int, Vector[Int]](body)(
        new Handler[Choice.type, Nothing, Int, Vector[Int]]:
          val effect: EffectKey[Choice.type] = Choice.key
          def onReturn(value: Int): Eff[Nothing, Vector[Int]] =
            Eff.pure(Vector(value))
          def onOperation[X](
              operation: Operation[Choice.type, X],
              resumption: Resumption[X, Nothing, Vector[Int]]
          ): Eff[Nothing, Vector[Int]] =
            operation match
              case Pick(values) =>
                values.foldLeft(
                  Eff.pure(Vector.empty[Int]): Eff[Nothing, Vector[Int]]
                ) { (result, value) =>
                  result.flatMap(prefix =>
                    resumeReusable(resumption, value).map(prefix ++ _)
                  )
                }
      )
      Eff.runPure(handled).mkString(",")

    private def nearestMatchingReset(): String =
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

    private def residualForwarding(): String =
      val body: Eff[Read.type | Write.type, Int] =
        perform(WriteValue(7)).flatMap[Read.type | Write.type, Int] { _ =>
          perform(ReadValue).flatMap[Read.type | Write.type, Int] { value =>
            perform(WriteValue(5)).map(_ => value + 7)
          }
        }
      val inner: Eff[Write.type, Int] =
        handle[Read.type, Write.type, Int, Int](body)(
          new Handler[Read.type, Write.type, Int, Int]:
            val effect: EffectKey[Read.type] = Read.key
            def onReturn(value: Int): Eff[Write.type, Int] =
              Eff.pure(value + 1)
            def onOperation[X](
                operation: Operation[Read.type, X],
                resumption: Resumption[X, Write.type, Int]
            ): Eff[Write.type, Int] =
              operation match
                case ReadValue => resumeOneShot(resumption, 35)
        )
      val outer = handle[Write.type, Nothing, Int, Int](inner)(
        new Handler[Write.type, Nothing, Int, Int]:
          val effect: EffectKey[Write.type] = Write.key
          def onReturn(value: Int): Eff[Nothing, Int] = Eff.pure(value + 2)
          def onOperation[X](
              operation: Operation[Write.type, X],
              resumption: Resumption[X, Nothing, Int]
          ): Eff[Nothing, Int] =
            operation match
              case WriteValue(value) =>
                resumeOneShot(resumption, ()).map(value + _)
      )
      Eff.runPure(outer).toString

    private def deepEffectStackSafety(): String =
      def tailLoop(remaining: Int, sum: Int): Eff[Tick.type, Int] =
        if remaining == 0 then Eff.pure(sum)
        else
          perform(TickStep).flatMap { value =>
            Eff.defer(tailLoop(remaining - 1, sum + value))
          }

      var sequenceCount = 0
      def sequenceLoop(remaining: Int): Eff[Tick.type, Int] =
        if remaining == 0 then Eff.pure(sequenceCount)
        else
          perform(TickStep).flatMap { _ =>
            sequenceCount += 1
            Eff.defer(sequenceLoop(remaining - 1))
          }

      def nonTailLoop(remaining: Int): Eff[Tick.type, Int] =
        if remaining == 0 then Eff.pure(0)
        else
          perform(TickStep).flatMap(_ =>
            Eff.defer(nonTailLoop(remaining - 1))
          )

      def escapedLoop(remaining: Int): Eff[Yield.type, Int] =
        if remaining == 0 then Eff.pure(0)
        else
          perform(Emit(1)).flatMap(_ =>
            Eff.defer(escapedLoop(remaining - 1))
          )

      def runTick(
          body: Eff[Tick.type, Int],
          addPerResume: Boolean,
          returnDelta: Int
      ): Int =
        val handled = handle[Tick.type, Nothing, Int, Int](body)(
          new Handler[Tick.type, Nothing, Int, Int]:
            val effect: EffectKey[Tick.type] = Tick.key
            def onReturn(value: Int): Eff[Nothing, Int] =
              Eff.pure(value + returnDelta)
            def onOperation[X](
                operation: Operation[Tick.type, X],
                resumption: Resumption[X, Nothing, Int]
            ): Eff[Nothing, Int] =
              operation match
                case TickStep =>
                  val resumed = resumeOneShot(resumption, 1)
                  if addPerResume then resumed.map(_ + 1) else resumed
        )
        Eff.runPure(handled)

      val tail = runTick(tailLoop(100_000, 0), false, 0)
      val sequence = runTick(sequenceLoop(100_000), false, 0)
      val nonTail = runTick(nonTailLoop(20_000), true, 7)
      val escaped =
        val handled = handle[Yield.type, Nothing, Int, Int => Int](
          escapedLoop(20_000)
        )(
          new Handler[Yield.type, Nothing, Int, Int => Int]:
            val effect: EffectKey[Yield.type] = Yield.key
            def onReturn(value: Int): Eff[Nothing, Int => Int] =
              Eff.pure(state => value + state)
            def onOperation[X](
                operation: Operation[Yield.type, X],
                resumption: Resumption[X, Nothing, Int => Int]
            ): Eff[Nothing, Int => Int] =
              operation match
                case Emit(value) =>
                  resumeOneShot(resumption, ()).map { next =>
                    state => next(state + value)
                  }
        )
        Eff.runPure(handled)(0)

      s"$tail|$sequence|$nonTail|$escaped"

    private def oneShotDiagnostic(): String =
      val body = perform(OneOp).map(_ + 1)
      val handled = handle[One.type, Nothing, Int, String](body)(
        new Handler[One.type, Nothing, Int, String]:
          val effect: EffectKey[One.type] = One.key
          def onReturn(value: Int): Eff[Nothing, String] =
            Eff.pure(value.toString)
          def onOperation[X](
              operation: Operation[One.type, X],
              resumption: Resumption[X, Nothing, String]
          ): Eff[Nothing, String] =
            operation match
              case OneOp =>
                resumption match
                  case Resumption.OneShot(continuation) =>
                    continuation.tryResume(41) match
                      case Right(first) =>
                        assert(Eff.runPure(first) == "42")
                      case Left(reason) =>
                        throw new AssertionError(
                          s"first resume was rejected: $reason"
                        )
                    continuation.tryResume(0) match
                      case Left(ResumeRejected.AlreadyResumed(id)) =>
                        Eff.pure(
                          s"AlreadyResumed(${id.effect.value}.${id.name})"
                        )
                      case other =>
                        throw new AssertionError(
                          s"second resume was not rejected: $other"
                        )
                  case Resumption.Reusable(_) =>
                    throw new AssertionError("expected one-shot resumption")
      )
      Eff.runPure(handled)

    private def freshPromptIsolation(): String =
      val pScope = freshPrompt[Int]
      val qScope = freshPrompt[Int]
      val p = pScope.prompt
      val q = qScope.prompt
      val fromP: Eff[Control[pScope.Key], Int] =
        shift[pScope.Key, Int, Nothing, Int](p)(
          [Residual >: Nothing <: Effect] =>
            (continuation: Continuation[Int, Residual, Int]) =>
              val _ = continuation
              Eff.pure(7)
        )
      val throughQ: Eff[Control[pScope.Key], Int] =
        reset[qScope.Key, Control[pScope.Key], Int](q) {
          fromP
        }
      val result = reset[pScope.Key, Nothing, Int](p) {
        throughQ.map(_ + 1000)
      }
      Eff.runPure(result).toString

    private def shiftNotShift0(): String =
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
      val result = reset[scoped.Key, Nothing, Int](prompt) {
        nested
      }
      Eff.runPure(result).toString

    private def multiShotSharedHeap(): String =
      var cell = 0
      val body = perform(Pick(Vector(1, 2))).map { _ =>
        cell += 1
        cell
      }
      val handled = handle[Choice.type, Nothing, Int, Vector[Int]](body)(
        new Handler[Choice.type, Nothing, Int, Vector[Int]]:
          val effect: EffectKey[Choice.type] = Choice.key
          def onReturn(value: Int): Eff[Nothing, Vector[Int]] =
            Eff.pure(Vector(value))
          def onOperation[X](
              operation: Operation[Choice.type, X],
              resumption: Resumption[X, Nothing, Vector[Int]]
          ): Eff[Nothing, Vector[Int]] =
            operation match
              case Pick(values) =>
                values.foldLeft(
                  Eff.pure(Vector.empty[Int]): Eff[Nothing, Vector[Int]]
                ) { (result, value) =>
                  result.flatMap(prefix =>
                    resumeReusable(resumption, value).map(prefix ++ _)
                  )
                }
      )
      s"${Eff.runPure(handled).mkString(",")}|$cell"

    private def unmanagedCapture(): String =
      type LocalSaved = SavedContinuation.Aux[Int, Nothing, Int]
      val machine = new ResumeStateMachine[Int, Int, Nothing, Int]:
        def resume(state: Int, input: Int): Eff[Nothing, Int] =
          Eff.pure(state + input)
      val continuation: Continuation[Int, Nothing, Int] =
        Continuation.local(40, machine)
      val rejected = handle[Save.type, Nothing, LocalSaved, CaptureFailure](
        continuation.save()
      )(
        new Handler[Save.type, Nothing, LocalSaved, CaptureFailure]:
          val effect: EffectKey[Save.type] = Save.key
          def onReturn(
              value: LocalSaved
          ): Eff[Nothing, CaptureFailure] =
            val _ = value
            throw new AssertionError("local save unexpectedly succeeded")
          def onOperation[X](
              operation: Operation[Save.type, X],
              resumption: Resumption[X, Nothing, CaptureFailure]
          ): Eff[Nothing, CaptureFailure] =
            operation match
              case Save.Rejected(failure) =>
                val _ = resumption
                Eff.pure(failure)
      )
      Eff.runPure(rejected) match
        case CaptureFailure.UnmanagedCapture(site) =>
          s"UnmanagedCapture($site)"
        case other => throw new AssertionError(s"unexpected capture failure: $other")
