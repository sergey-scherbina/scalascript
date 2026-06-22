package scalascript.codegen

/** Per-host library packaging for the **JVM** host (Task B, `specs/polyglot-libraries.md` §4) — the
 *  counterpart of [[JsLibPackager]]. Packages the pure **optics** feature as a standalone, buildable
 *  Scala project (an `sbt` library) with no ScalaScript dependency.
 *
 *  Unlike JS — where a ready self-contained `JsRuntimeOptics` runtime exists — JVM optics runs through
 *  the interpreter (`OpticsRuntime`, execution-coupled). So the JVM library is a **native Scala optics
 *  implementation** over dynamic JSON-like values (`Map[String, Any]` objects, `List[Any]`, `Option`,
 *  and `"_type"`-tagged sum variants), faithful to the four JS optic shapes (Lens / Optional / Traversal
 *  / Prism). Idiomatic *typed* optics (macro / Monocle-style) are a larger, separate effort. */
object JvmLibPackager:

  /** The self-contained Scala optics source — no ScalaScript / interpreter dependency. */
  val opticsScalaSource: String =
    """package ssc.optics
      |
      |/** Composable optics (Lens / Optional / Traversal / Prism) over dynamic JSON-like values:
      | *  `Map[String, Any]` objects, `List[Any]` arrays, `Option[Any]`, and `"_type"`-tagged sum
      | *  variants. Generated from the ScalaScript optics runtime; no ScalaScript dependency. The
      | *  faithful dynamic port of the JS `@scalascript/optics` package. */
      |object Optics:
      |
      |  sealed trait Step
      |  final case class FieldStep(name: String) extends Step
      |  final case class IndexStep(i: Int)       extends Step
      |  final case class AtStep(key: Any)        extends Step
      |  case object SomeStep extends Step
      |  case object EachStep extends Step
      |
      |  def field(name: String): Step = FieldStep(name)
      |  def index(i: Int): Step       = IndexStep(i)
      |  def at(key: Any): Step        = AtStep(key)
      |  val some: Step                = SomeStep
      |  val each: Step                = EachStep
      |
      |  private def asObj(v: Any): Map[String, Any] = v.asInstanceOf[Map[String, Any]]
      |
      |  final case class Lens(path: List[String]):
      |    def get(s: Any): Any = path.foldLeft(s)((v, k) => asObj(v)(k))
      |    def set(s: Any, v: Any): Any = path match
      |      case Nil    => v
      |      case h :: t => val m = asObj(s); m.updated(h, Lens(t).set(m(h), v))
      |    def modify(s: Any, f: Any => Any): Any = set(s, f(get(s)))
      |    def andThen(other: Lens): Lens = Lens(path ++ other.path)
      |
      |  final case class Optional(steps: List[Step]):
      |    def getOption(s: Any): Option[Any]     = Optics.getOpt(steps, s)
      |    def set(s: Any, v: Any): Any           = Optics.setOpt(steps, s, v)
      |    def modify(s: Any, f: Any => Any): Any = getOption(s) match
      |      case Some(a) => set(s, f(a))
      |      case None    => s
      |    def andThen(other: Optional): Optional = Optional(steps ++ other.steps)
      |
      |  final case class Traversal(steps: List[Step]):
      |    def getAll(s: Any): List[Any]          = Optics.getAll(steps, s)
      |    def modify(s: Any, f: Any => Any): Any = Optics.modAll(steps, s, f)
      |    def set(s: Any, v: Any): Any           = modify(s, _ => v)
      |    def andThen(other: Traversal): Traversal = Traversal(steps ++ other.steps)
      |
      |  final case class Prism(variant: String):
      |    private def matches(s: Any): Boolean = s match
      |      case m: Map[String, Any] @unchecked => m.get("_type").contains(variant)
      |      case _ => false
      |    def getOption(s: Any): Option[Any]     = if matches(s) then Some(s) else None
      |    def reverseGet(v: Any): Any            = v
      |    def set(s: Any, v: Any): Any           = if matches(s) then v else s
      |    def modify(s: Any, f: Any => Any): Any = if matches(s) then f(s) else s
      |
      |  def makeLens(path: List[String]): Lens          = Lens(path)
      |  def makeOptional(steps: List[Step]): Optional   = Optional(steps)
      |  def makeTraversal(steps: List[Step]): Traversal = Traversal(steps)
      |  def makePrism(variant: String): Prism           = Prism(variant)
      |
      |  private def getOpt(steps: List[Step], s: Any): Option[Any] = steps match
      |    case Nil => Some(s)
      |    case st :: rest => st match
      |      case FieldStep(n) => s match
      |        case m: Map[String, Any] @unchecked if m.contains(n) => getOpt(rest, m(n))
      |        case _ => None
      |      case IndexStep(i) => s match
      |        case l: List[Any] @unchecked if i >= 0 && i < l.length => getOpt(rest, l(i))
      |        case _ => None
      |      case AtStep(k) => s match
      |        case m: Map[Any, Any] @unchecked if m.contains(k) => getOpt(rest, m(k))
      |        case _ => None
      |      case SomeStep => s match
      |        case Some(v) => getOpt(rest, v)
      |        case _ => None
      |      case EachStep => None
      |
      |  private def setOpt(steps: List[Step], s: Any, v: Any): Any = steps match
      |    case Nil => v
      |    case st :: rest => st match
      |      case FieldStep(n) => s match
      |        case m: Map[String, Any] @unchecked if m.contains(n) => m.updated(n, setOpt(rest, m(n), v))
      |        case _ => s
      |      case IndexStep(i) => s match
      |        case l: List[Any] @unchecked if i >= 0 && i < l.length => l.updated(i, setOpt(rest, l(i), v))
      |        case _ => s
      |      case AtStep(k) => s match
      |        case m: Map[Any, Any] @unchecked if m.contains(k) => m.updated(k, setOpt(rest, m(k), v))
      |        case _ => s
      |      case SomeStep => s match
      |        case Some(x) => Some(setOpt(rest, x, v))
      |        case _ => s
      |      case EachStep => s
      |
      |  private def getAll(steps: List[Step], s: Any): List[Any] = steps match
      |    case Nil => List(s)
      |    case st :: rest => st match
      |      case FieldStep(n) => s match
      |        case m: Map[String, Any] @unchecked if m.contains(n) => getAll(rest, m(n))
      |        case _ => Nil
      |      case IndexStep(i) => s match
      |        case l: List[Any] @unchecked if i >= 0 && i < l.length => getAll(rest, l(i))
      |        case _ => Nil
      |      case AtStep(k) => s match
      |        case m: Map[Any, Any] @unchecked if m.contains(k) => getAll(rest, m(k))
      |        case _ => Nil
      |      case SomeStep => s match
      |        case Some(v) => getAll(rest, v)
      |        case _ => Nil
      |      case EachStep => s match
      |        case l: List[Any] @unchecked => l.flatMap(item => getAll(rest, item))
      |        case _ => Nil
      |
      |  private def modAll(steps: List[Step], s: Any, f: Any => Any): Any = steps match
      |    case Nil => f(s)
      |    case st :: rest => st match
      |      case FieldStep(n) => s match
      |        case m: Map[String, Any] @unchecked if m.contains(n) => m.updated(n, modAll(rest, m(n), f))
      |        case _ => s
      |      case IndexStep(i) => s match
      |        case l: List[Any] @unchecked if i >= 0 && i < l.length => l.updated(i, modAll(rest, l(i), f))
      |        case _ => s
      |      case AtStep(k) => s match
      |        case m: Map[Any, Any] @unchecked if m.contains(k) => m.updated(k, modAll(rest, m(k), f))
      |        case _ => s
      |      case SomeStep => s match
      |        case Some(x) => Some(modAll(rest, x, f))
      |        case _ => s
      |      case EachStep => s match
      |        case l: List[Any] @unchecked => l.map(item => modAll(rest, item, f))
      |        case _ => s
      |""".stripMargin

  def opticsBuildSbt(version: String): String =
    s"""name         := "ssc-optics"
       |organization := "io.scalascript"
       |version      := "$version"
       |scalaVersion := "3.3.4"
       |""".stripMargin

  private val opticsReadme: String =
    """# ssc-optics
      |
      |Composable optics (Lens / Optional / Traversal / Prism) over dynamic JSON-like values,
      |generated from the ScalaScript optics runtime — no ScalaScript dependency.
      |
      |```scala
      |import ssc.optics.Optics.*
      |val l = makeLens(List("a", "b"))
      |l.get(Map("a" -> Map("b" -> 5)))            // 5
      |l.set(Map("a" -> Map("b" -> 5)), 9)         // Map(a -> Map(b -> 9))
      |val o = makeOptional(List(field("a"), index(0)))
      |o.getOption(Map("a" -> List(10, 20)))       // Some(10)
      |```
      |""".stripMargin

  /** The complete buildable Scala library as `relative-path -> content`. */
  def opticsScalaPackage(version: String): Map[String, String] = Map(
    "build.sbt"                            -> opticsBuildSbt(version),
    "src/main/scala/ssc/optics/Optics.scala" -> opticsScalaSource,
    "README.md"                            -> opticsReadme,
  )
