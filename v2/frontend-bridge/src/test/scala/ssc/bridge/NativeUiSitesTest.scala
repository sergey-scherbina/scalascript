package ssc.bridge

import org.scalatest.funsuite.AnyFunSuite
import ssc.*

class NativeUiSitesTest extends AnyFunSuite:
  private val emptyMap = Term.Prim("map.new", Nil)
  private val emptyList = Term.Ctor("Nil", Nil)
  private val elementArgs = List(
    Term.Lit(Const.CStr("div")), emptyMap, emptyMap, emptyList)

  private lazy val repoRoot: java.io.File =
    Iterator.iterate(new java.io.File(".").getAbsoluteFile)(_.getParentFile)
      .takeWhile(file => file != null && file.getParentFile != file)
      .find(file => new java.io.File(file, "build.sbt").exists())
      .getOrElse(new java.io.File(".").getAbsoluteFile)

  private def containsGlobal(term: Term, name: String): Boolean = term match
    case Term.Global(value) => value == name
    case Term.Lit(_) | Term.Local(_) => false
    case Term.Lam(_, body) => containsGlobal(body, name)
    case Term.App(fn, args) => containsGlobal(fn, name) || args.exists(containsGlobal(_, name))
    case Term.Let(rhs, body) => rhs.exists(containsGlobal(_, name)) || containsGlobal(body, name)
    case Term.LetRec(lambdas, body) => lambdas.exists(containsGlobal(_, name)) || containsGlobal(body, name)
    case Term.If(condition, whenTrue, whenFalse) =>
      List(condition, whenTrue, whenFalse).exists(containsGlobal(_, name))
    case Term.Ctor(_, fields) => fields.exists(containsGlobal(_, name))
    case Term.Match(scrutinee, arms, default) =>
      containsGlobal(scrutinee, name) || arms.exists(arm => containsGlobal(arm.body, name)) ||
        default.exists(containsGlobal(_, name))
    case Term.Prim(_, args) => args.exists(containsGlobal(_, name))
    case Term.While(condition, body) => containsGlobal(condition, name) || containsGlobal(body, name)
    case Term.Seq(terms) => terms.exists(containsGlobal(_, name))

  private def sourceFiles(term: Term): Set[String] = term match
    case Term.Ctor("NativeUiSourceRef", Term.Lit(Const.CStr(file)) :: _) => Set(file)
    case Term.Lit(_) | Term.Local(_) | Term.Global(_) => Set.empty
    case Term.Lam(_, body) => sourceFiles(body)
    case Term.App(fn, args) => sourceFiles(fn) ++ args.flatMap(sourceFiles)
    case Term.Let(rhs, body) => rhs.flatMap(sourceFiles).toSet ++ sourceFiles(body)
    case Term.LetRec(lambdas, body) => lambdas.flatMap(sourceFiles).toSet ++ sourceFiles(body)
    case Term.If(condition, whenTrue, whenFalse) =>
      sourceFiles(condition) ++ sourceFiles(whenTrue) ++ sourceFiles(whenFalse)
    case Term.Ctor(_, fields) => fields.flatMap(sourceFiles).toSet
    case Term.Match(scrutinee, arms, default) =>
      sourceFiles(scrutinee) ++ arms.flatMap(arm => sourceFiles(arm.body)) ++ default.toSet.flatMap(sourceFiles)
    case Term.Prim(_, args) => args.flatMap(sourceFiles).toSet
    case Term.While(condition, body) => sourceFiles(condition) ++ sourceFiles(body)
    case Term.Seq(terms) => terms.flatMap(sourceFiles).toSet

  test("eligible UI calls receive deterministic lexical site and source metadata"):
    val input = Program(List(
      Def("view", Term.Seq(List(
        Term.App(Term.Global("element"), elementArgs),
        Term.App(Term.Global("element"), elementArgs))))),
      Term.Lit(Const.CUnit))
    val config = NativeUiSites.Config(
      eligibleSymbols = Set("element"),
      sourcesByDefinition = Map("view" -> NativeUiSites.SourceRef("std/ui/lower.ssc", 105, 3)))

    val result = NativeUiSites.annotate(input, config)
    val Term.Seq(List(
      Term.App(Term.Global(firstName), Term.Lit(Const.CStr(firstSite)) :: firstSource :: firstArgs),
      Term.App(Term.Global(secondName), Term.Lit(Const.CStr(secondSite)) :: _ :: secondArgs)
    )) = result.defs.head.body: @unchecked

    assert(firstName == NativeUiSites.internalName("element"))
    assert(secondName == firstName)
    assert(firstSite == "d0:view/root/s0")
    assert(secondSite == "d0:view/root/s1")
    assert(firstArgs == elementArgs)
    assert(secondArgs == elementArgs)
    assert(firstSource == Term.Ctor("NativeUiSourceRef", List(
      Term.Lit(Const.CStr("std/ui/lower.ssc")),
      Term.Lit(Const.CInt(105)),
      Term.Lit(Const.CInt(3)),
      Term.Lit(Const.CStr("element")))))

  test("same-named user definition shadows imported eligibility"):
    val userElement = Def("element", Term.Lam(4, Term.Lit(Const.CStr("user"))))
    val input = Program(List(userElement, Def("view",
      Term.App(Term.Global("element"), elementArgs))), Term.Lit(Const.CUnit))

    assert(NativeUiSites.annotate(input,
      NativeUiSites.Config(eligibleSymbols = Set("element"))) == input)

  test("bare UI references, bad arity, and reserved user globals fail early"):
    val bare = Program(List(Def("view", Term.Global("element"))), Term.Lit(Const.CUnit))
    val bareError = intercept[IllegalArgumentException](NativeUiSites.annotate(
      bare, NativeUiSites.Config(eligibleSymbols = Set("element"))))
    assert(bareError.getMessage.contains("bare or eta-expanded"))

    val badArity = Program(List(Def("view",
      Term.App(Term.Global("element"), elementArgs.dropRight(1)))), Term.Lit(Const.CUnit))
    val arityError = intercept[IllegalArgumentException](NativeUiSites.annotate(
      badArity, NativeUiSites.Config(eligibleSymbols = Set("element"))))
    assert(arityError.getMessage.contains("arity 3; expected 4"))

    val reserved = Program(List(Def("__ssc_nativeui_v1.user", Term.Lit(Const.CUnit))),
      Term.Lit(Const.CUnit))
    val reservedError = intercept[IllegalArgumentException](NativeUiSites.annotate(
      reserved, NativeUiSites.Config(eligibleSymbols = Set.empty)))
    assert(reservedError.getMessage.contains("reserved global prefix"))

  test("emit and serve receive source metadata without a site field"):
    val source = NativeUiSites.SourceRef("app.ssc", 9, 1)
    val input = Program(Nil, Term.App(Term.Global("emit"), List(
      Term.Ctor("NativeUiText", List(Term.Lit(Const.CStr("ok")))),
      Term.Lit(Const.CStr("out")))))
    val output = NativeUiSites.annotate(input,
      NativeUiSites.Config(eligibleSymbols = Set("emit"), entrySource = source))
    val Term.App(Term.Global(name), hidden :: args) = output.entry: @unchecked

    assert(name == NativeUiSites.internalName("emit"))
    assert(args.length == 2)
    assert(hidden == Term.Ctor("NativeUiSourceRef", List(
      Term.Lit(Const.CStr("app.ssc")), Term.Lit(Const.CInt(9)),
      Term.Lit(Const.CInt(1)), Term.Lit(Const.CStr("emit")))))

  test("FrontendBridge annotates only std/ui-imported constructor calls"):
    val source =
      """[element](std/ui/primitives.ssc)
        |
        |```scalascript
        |element("div", Map(), Map(), [])
        |```
        |""".stripMargin
    val program = FrontendBridge.convertSource(source, Some(repoRoot))

    assert(containsGlobal(program.entry, NativeUiSites.internalName("element")))
    assert(!containsGlobal(program.entry, "element"))

  test("FrontendBridge retains imported std/ui file provenance before flattening"):
    val source =
      """[lower](std/ui/lower.ssc)
        |
        |```scalascript
        |()
        |```
        |""".stripMargin
    val program = FrontendBridge.convertSource(source, Some(repoRoot))
    val files = program.defs.iterator.flatMap(definition => sourceFiles(definition.body)).toSet

    assert(files.contains("std/ui/lower.ssc"))
