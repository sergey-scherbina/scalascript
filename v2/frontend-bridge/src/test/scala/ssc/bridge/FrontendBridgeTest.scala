package ssc.bridge

import org.scalatest.funsuite.AnyFunSuite
import ssc.*

class FrontendBridgeTest extends AnyFunSuite:

  private var bridgeRuntimeLoaded = false

  private def ensureBridgeRuntime(): Unit =
    if !bridgeRuntimeLoaded then
      PluginBridge.loadAll()
      bridgeRuntimeLoaded = true

  def run(src: String): Value =
    ensureBridgeRuntime()
    val prog = FrontendBridge.convertSource(src)
    Runtime.run(Compiler.compile(prog), Array.empty[Value])

  def runCore(entry: Term): Value =
    ensureBridgeRuntime()
    Runtime.run(Compiler.compile(Program(Nil, entry)), Array.empty[Value])

  def runBytecode(src: String): Value =
    ensureBridgeRuntime()
    val prog = FrontendBridge.convertSource(src)
    val (_, globals) = Compiler.compileWithGlobals(prog)
    Emit.globalsRef = globals
    val bytes = bytecode.JvmByteGen.emitProgram(prog)
    try bytecode.JvmByteGen.runProgram(bytes)
    catch case e: java.lang.reflect.InvocationTargetException =>
      throw Option(e.getCause).getOrElse(e)

  def runBytecodeProgram(prog: Program): Value =
    Emit.globalsRef = Map.empty
    val bytes = bytecode.JvmByteGen.emitProgram(prog)
    try bytecode.JvmByteGen.runProgram(bytes)
    catch case e: java.lang.reflect.InvocationTargetException =>
      throw Option(e.getCause).getOrElse(e)

  private lazy val repoRoot: java.io.File =
    Iterator.iterate(new java.io.File(".").getAbsoluteFile)(_.getParentFile)
      .takeWhile(f => f != null && f.getParentFile != f)
      .find(f => new java.io.File(f, "build.sbt").exists())
      .getOrElse(new java.io.File(".").getAbsoluteFile)

  private def repoFile(path: String): java.io.File =
    new java.io.File(repoRoot, path)

  private def runSsc0File(path: String, args: List[String] = Nil): Value =
    ensureBridgeRuntime()
    val savedArgv = Runtime.argv
    Runtime.argv = args
    try
      val prog = Lower.module(Loader.load(repoFile(path).getPath))
      Runtime.run(Compiler.compile(prog), Array.empty[Value])
    finally Runtime.argv = savedArgv

  private def captureSsc0File(path: String, args: List[String] = Nil): String =
    val out = new java.io.ByteArrayOutputStream
    Console.withOut(out)(runSsc0File(path, args))
    out.toString("UTF-8").stripTrailing()

  def dynamicArith(op: String, lhs: Term, rhs: Term): Term =
    Term.Let(
      List(Term.Lit(Const.CStr(op))),
      Term.Prim("__arith__", List(Term.Local(0), lhs, rhs)))

  def runStr(src: String): String =
    run(src) match
      case Value.StrV(s) => s
      case Value.IntV(n) => n.toString
      case Value.BoolV(b) => b.toString
      case v             => Show.show(v)

  def capture(src: String): String =
    val out = new java.io.ByteArrayOutputStream
    Console.withOut(out)(run(src))
    out.toString.trim

  def captureFromRepo(src: String): String =
    ensureBridgeRuntime()
    val out = new java.io.ByteArrayOutputStream
    Console.withOut(out) {
      val prog = FrontendBridge.convertSource(src, Some(repoRoot))
      Runtime.run(Compiler.compile(prog), Array.empty[Value])
    }
    out.toString.trim

  test("literal int") {
    assert(run("42") == Value.IntV(42))
  }

  test("literal string") {
    assert(run("\"hello\"") == Value.StrV("hello"))
  }

  test("v2 remote registry supports manifest annotation and remote def") {
    val src =
      """|---
         |remoteHandlers:
         |  demo.echo:
         |    function: echo
         |    path: /rpc/echo
         |    request: String
         |    response: String
         |---
         |
         |# Remote
         |
         |[Remote, RemoteFunction, RemoteCallError](../runtime/std/remote.ssc)
         |
         |```scala
         |def echo(value: String): String =
         |  "echo:" + value
         |
         |@remote(name = "demo.upper", path = "/rpc/upper")
         |def upper(value: String): String =
         |  value.toUpperCase
         |
         |remote def localEcho(value: String): String =
         |  "local:" + value
         |
         |val echoFn = Remote.function[String, String]("demo.echo")
         |println(echoFn.call("hello"))
         |println(Remote.function[String, String]("demo.upper").call("hello"))
         |println(Remote.function[String, String]("localEcho").call("hello"))
         |
         |echoFn.tryCall("typed") match
         |  case Right(value) => println(value)
         |  case Left(error)  => println("remote error: " + error.toString)
         |
         |Remote.handlers().foreach { info =>
         |  println(info.name + " -> " + info.function)
         |}
         |```
         |""".stripMargin

    assert(capture(src) ==
      """echo:hello
        |HELLO
        |local:hello
        |echo:typed
        |demo.echo -> echo
        |demo.upper -> upper
        |localEcho -> localEcho""".stripMargin)
  }

  test("v2 remoteTryCall returns HandlerNotFound data for missing handlers") {
    assert(run("""remoteTryCall[String, String]("missing.op", "hello")""") ==
      Value.DataV("Left", Vector(
        Value.DataV("HandlerNotFound", Vector(Value.StrV("missing.op"))))))
  }

  test("v2 markup bridge supports xml transform serialize and errors") {
    val src =
      """# Markup
        |
        |```scala
        |import scalascript.markup.*
        |
        |val source = xml"<catalog><book><title>Scala 3</title></book></catalog>"
        |val dangerous = "<script>&"
        |val escaped = xml"<msg>${dangerous}</msg>"
        |val xslt = "<xsl:stylesheet version=\"1.0\" xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\"><xsl:param name=\"currency\">USD</xsl:param><xsl:template match=\"/\"><report><currency><xsl:value-of select=\"$currency\"/></currency><xsl:value-of select=\"catalog/book/title\"/></report></xsl:template></xsl:stylesheet>"
        |
        |println(PureMarkupCodec.serialize(escaped, SerializeOpts(omitXmlDecl = true)))
        |
        |MarkupCodec.default.transform(source, xslt, Map("currency" -> "EUR")) match
        |  case Right(doc) =>
        |    println(PureMarkupCodec.serialize(doc, SerializeOpts(omitXmlDecl = true, pretty = true)))
        |  case Left(err) =>
        |    println("ERR:" + err.message)
        |
        |MarkupCodec.default.transform(source, "<not-a-stylesheet/>") match
        |  case Left(err) => println("ERR:" + err.message)
        |  case Right(_)  => println("unexpected")
        |```
        |""".stripMargin

    val out = capture(src)
    assert(out.contains("<report>"))
    assert(out.contains("<currency>EUR</currency>"))
    assert(out.contains("Scala 3"))
    assert(out.contains("<msg>&lt;script&gt;&amp;</msg>"))
    assert(!out.contains("<?xml"))
    assert(out.contains("ERR:"))
  }

  test("v2 payments bridge supports deterministic provider and money surface") {
    val src =
      """# Payments
        |
        |```scala
        |val stripe = PaymentProvider.named("stripe")
        |val intent = stripe.createIntent(CreateIntentRequest(
        |  amount = Money(4999L, Currency.USD),
        |  method = Some(PaymentMethod.Card("pm_card_visa")),
        |  confirm = true,
        |))
        |intent match
        |  case PaymentIntent.Succeeded(_, _, charge) =>
        |    println("paid:" + charge.id.value + ":" + charge.receiptUrl)
        |  case _ =>
        |    println("unexpected")
        |
        |val subtotal = Money(BigDecimal("49.99"), Currency.USD)
        |val tax = subtotal * BigDecimal("0.20")
        |val total = subtotal + tax
        |println(total.toDecimal)
        |println(Money.allocate(Money(100L, Currency.USD), List(BigDecimal(1), BigDecimal(1), BigDecimal(1))).map(_.toDecimal))
        |```
        |""".stripMargin

    val out = capture(src)
    assert(out.contains("paid:ch_stripe_demo_pi_1"))
    assert(out.contains("59.99"))
    assert(out.contains("List(0.34, 0.33, 0.33)"))
    assert(!out.contains("Op("))
    assert(!out.contains("Stub"))
  }

  test("v2 Currency companion remains compatible with std money constructors") {
    val src =
      """# Money
        |
        |[currencyOf](runtime/std/money.ssc)
        |
        |```scala
        |val usd = Currency("USD", 2, "$")
        |println(usd.code + ":" + usd.scale + ":" + usd.symbol)
        |val brl = Currency("BRL")
        |println(brl.code + ":" + brl.scale + ":" + brl.symbol)
        |println(Currency.USD.code + ":" + Currency.USD.scale + ":" + Currency.USD.symbol)
        |println(currencyOf("PLN").symbol + ":" + currencyOf("PLN").scale)
        |```
        |""".stripMargin

    val out = captureFromRepo(src)
    assert(out.contains("USD:2:$"))
    assert(out.contains("BRL:2:BRL"))
    assert(out.contains("zł:2"))
    assert(!out.contains("Op("))
    assert(!out.contains("Stub"))
  }

  test("v2 List.head keeps dynamic dispatch when a case class also has head field") {
    val src =
      """# Head Shadow
        |
        |```scala
        |case class RepoRef(name: String, head: String)
        |case class Gig(id: String, budget: Int, effortHours: Int)
        |
        |def scoreGig(g: Gig): Int =
        |  if g.effortHours > 0 then g.budget / g.effortHours else g.budget
        |
        |def scoredGigs(gigs: List[Gig]): List[Gig] =
        |  if gigs.isEmpty then List()
        |  else
        |    val best = gigs.foldLeft(gigs.head)((b, g) =>
        |      if scoreGig(g) > scoreGig(b) then g else b)
        |    List(best) ++ scoredGigs(gigs.filter(g => g.id != best.id))
        |
        |effect GigSource:
        |  def fetch(): List[Gig]
        |
        |def simGigs(): List[Gig] =
        |  List(Gig("slow", 100, 10), Gig("fast", 90, 3))
        |
        |def runSimGigSource[A](body: () => A): A =
        |  handle { body() } {
        |    case GigSource.fetch(resume) => resume(simGigs())
        |  }
        |
        |def scoutGigs(): List[Gig] = scoredGigs(GigSource.fetch())
        |def gigsText(gigs: List[Gig]): String =
        |  gigs.map(g => g.id + ":" + scoreGig(g).toString).mkString(",")
        |
        |val ref = RepoRef("master", "abc")
        |println(ref.head)
        |println(runSimGigSource(() => gigsText(scoutGigs())))
        |```
        |""".stripMargin

    val out = capture(src)
    assert(out.contains("abc"))
    assert(out.contains("fast:30,slow:10"))
    assert(!out.contains("Op("))
    assert(!out.contains("Stub"))
  }

  test("v2 payments bridge supports Pix provider and QR code surface") {
    val src =
      """# Pix
        |
        |```scala
        |val pixConfig = PixConfig(
        |  pixApiUrl = "https://pix.example.test",
        |  pixClientId = "client",
        |  pixClientSecret = "secret",
        |  pixPixKey = "merchant@example.com",
        |)
        |val pix = PixProvider(pixConfig)
        |val recipient = BankAccount(pixKey = Some("payer@example.com"), holderName = "Maria", countryCode = "BR")
        |val sender = BankAccount(pixKey = Some(pixConfig.pixPixKey), holderName = "Loja", countryCode = "BR")
        |val transfer = pix.initiateTransfer(InitiateTransferRequest(
        |  rail = RailKind.PIX,
        |  amount = Money(5000L, Currency("BRL")),
        |  sender = sender,
        |  recipient = recipient,
        |  reference = "Pedido #12345",
        |  idempotencyKey = "order12345attempt1",
        |))
        |println("pix:" + transfer.id.value + ":" + transfer.status)
        |println("settled:" + pix.getTransfer(transfer.id).status)
        |val qr = PixQrCode.buildStatic(PixQrCode.StaticConfig(
        |  pixKey = "loja@empresa.com.br",
        |  merchantName = "Loja Exemplo",
        |  merchantCity = "Sao Paulo",
        |  amount = Some(Money(2999L, Currency("BRL"))),
        |))
        |println(qr.take(12) + ":" + qr.takeRight(8))
        |```
        |""".stripMargin

    val out = capture(src)
    assert(out.contains("pix:pix_order12345attempt1:Pending"))
    assert(out.contains("settled:Settled"))
    assert(out.contains("000201"))
    assert(out.contains("6304"))
    assert(!out.contains("Op("))
    assert(!out.contains("Stub"))
  }

  test("v2 payments bridge supports FedNow provider and Instant helpers") {
    val src =
      """# FedNow
        |
        |```scala
        |val provider = FedNowProvider(FedNowConfig.fromEnv)
        |val transfer = provider.initiateTransfer(InitiateTransferRequest(
        |  rail = RailKind.FEDNOW,
        |  amount = Money(150000L, Currency("USD")),
        |  sender = BankAccount(accountNumber = Some("123"), routingNumber = Some("021000021"), holderName = "Acme", countryCode = "US"),
        |  recipient = BankAccount(accountNumber = Some("987"), bankCode = Some("026009593"), holderName = "Bob", countryCode = "US"),
        |  reference = "INV-1",
        |  idempotencyKey = "fednow-transfer-order-1",
        |))
        |val deadline = Instant.now().plusSeconds(60)
        |println("fednow:" + transfer.id.value + ":" + transfer.status)
        |println("after:" + Instant.now().isAfter(deadline))
        |Thread.sleep(1)
        |println("final:" + provider.getTransfer(transfer.id).status)
        |```
        |""".stripMargin

    val out = capture(src)
    assert(out.contains("fednow:fednow_fednowtransferorder1:Pending"))
    assert(out.contains("after:false"))
    assert(out.contains("final:Settled"))
    assert(!out.contains("Op("))
    assert(!out.contains("Stub"))
  }

  test("arithmetic") {
    assert(run("1 + 2 * 3") == Value.IntV(7))
  }

  test("non-literal __arith__ map plus tuple uses arithOp semantics") {
    val pair = Term.Prim("__arith__", List(
      Term.Lit(Const.CStr("->")),
      Term.Lit(Const.CStr("id")),
      Term.Lit(Const.CStr("demo"))))
    val out = runCore(dynamicArith("+", Term.Prim("__mk_map__", Nil), pair))

    val map = out.asInstanceOf[Value.ForeignV].h
      .asInstanceOf[collection.mutable.Map[Value, Value]]
    assert(map(Value.StrV("id")) == Value.StrV("demo"))
  }

  test("non-literal __arith__ char comparisons use codepoint semantics") {
    val arith = Prims.resolve("__arith__")

    assert(arith(List(Value.StrV(">"), Value.StrV("b"), Value.IntV('a'.toLong))) ==
      Value.BoolV(true))
    assert(arith(List(Value.StrV("<="), Value.IntV('a'.toLong), Value.StrV("b"))) ==
      Value.BoolV(true))
  }

  test("non-literal __arith__ preserves table-only fallback cases") {
    val arith = Prims.resolve("__arith__")

    val dec = arith(List(
      Value.StrV("+"),
      Value.ForeignV(new java.math.BigDecimal("1.25")),
      Value.IntV(2)))
    assert(dec.asInstanceOf[Value.ForeignV].h
      .asInstanceOf[java.math.BigDecimal].toPlainString == "3.25")

    assert(arith(List(Value.StrV("!"), Value.DataV("ActorRef", Vector(Value.IntV(1))), Value.StrV("msg"))) ==
      Value.UnitV)
    assert(arith(List(Value.StrV("Logger"), Value.StrV("effect"), Value.UnitV)) ==
      Value.UnitV)
  }

  test("v2 VM effect handlers match free-monad Op values from ssc0") {
    assert(runSsc0File("v2/examples/effects-state.ssc0") ==
      Value.DataV("Pair", Vector(Value.IntV(2), Value.IntV(2))))
  }

  test("v2 VM effect handlers match typed-effect CoreIR emitted by Mira") {
    val coreIr = captureSsc0File(
      "v2/bin/mirac.ssc0",
      List(repoFile("v2/examples/hm-eff-comp.hm").getPath))
    val prog = Reader.parseProgram(coreIr)

    assert(Runtime.run(Compiler.compile(prog), Array.empty[Value]) == Value.IntV(41))
  }

  test("val binding") {
    assert(run("val x = 10\nval y = x + 5\ny") == Value.IntV(15))
  }

  test("def and call") {
    assert(run("def double(x: Int) = x * 2\ndouble(21)") == Value.IntV(42))
  }

  test("bridge recursive int comparisons enable SelfRecLL fast entry") {
    ensureBridgeRuntime()
    val src =
      """def fib(n: Int): Int =
        |  if n <= 1 then n
        |  else fib(n - 1) + fib(n - 2)
        |
        |def workload(): Int = fib(30)
        |""".stripMargin

    val prog = FrontendBridge.convertSource(src)
    val (_, globals) = Compiler.compileWithGlobals(prog)
    val fib = globals("fib").asInstanceOf[Value.ClosV]
    val workload = globals("workload").asInstanceOf[Value.ClosV]

    assert(fib.fcEntry.isDefined)
    assert(Runtime.run(workload.code, workload.env) == Value.IntV(832040))
  }

  test("bridge self-tail recursion compiles to arity-2 Long loop fast entry") {
    ensureBridgeRuntime()
    val src =
      """def sumTco(n: Int, acc: Int): Int =
        |  if n <= 0 then acc
        |  else sumTco(n - 1, acc + n)
        |
        |def workload(): Int = sumTco(100000, 0)
        |""".stripMargin

    val prog = FrontendBridge.convertSource(src)
    val (_, globals) = Compiler.compileWithGlobals(prog)
    val sumTco = globals("sumTco").asInstanceOf[Value.ClosV]
    val workload = globals("workload").asInstanceOf[Value.ClosV]

    assert(sumTco.fcEntry.isDefined)
    assert(Runtime.run(workload.code, workload.env) == Value.IntV(5000050000L))
  }

  test("bridge pattern-match-heavy workload exposes VM fast entries") {
    ensureBridgeRuntime()
    val src = scala.io.Source.fromFile(repoFile("bench/corpus/pattern-match-heavy.ssc")).mkString
    val prog = FrontendBridge.convertSource(src, Some(repoFile("bench/corpus")))
    val (_, globals) = Compiler.compileWithGlobals(prog)
    val area = globals("area").asInstanceOf[Value.ClosV]
    val workload = globals("workload").asInstanceOf[Value.ClosV]

    assert(area.fcEntry.isDefined)
    assert(workload.fcEntry.isDefined)
    val result = workload.fcEntry.get(Runtime.emptyEnv).asInstanceOf[Value.FloatV].d
    assert(math.abs(result - 1914159.0) < 0.00001)
  }

  test("v2 VM static float foreach loop keeps impure global function on fallback") {
    import Const.*, Term.*

    def add(a: Term, b: Term): Term = Prim("__arith__", List(Lit(CStr("+")), a, b))
    def lget(t: Term): Term = Prim("lcell.get", List(t))
    def lset(t: Term, rhs: Term): Term = Prim("lcell.set", List(t, rhs))
    def cget(i: Int): Term = Prim("cell.get", List(Local(i)))
    def cset(i: Int, rhs: Term): Term = Prim("cell.set", List(Local(i), rhs))

    val shapes =
      Ctor("Cons", List(Ctor("S", Nil), Ctor("Nil", Nil)))
    val area =
      Lam(1, Seq(List(
        lset(Global("counter"), add(lget(Global("counter")), Lit(CInt(1)))),
        Lit(CFloat(1.0)))))
    val foreachBody =
      cset(2, add(cget(2), App(Global("area"), List(Local(0)))))
    val loopBody =
      Seq(List(
        Prim("__method__", List(Lit(CStr("foreach")), Global("shapes"), Lam(1, foreachBody))),
        lset(Local(0), add(lget(Local(0)), Lit(CInt(1))))))
    val workload =
      Lam(0,
        Let(List(Prim("cell.new", List(Lit(CFloat(0.0))))),
          Let(List(Prim("lcell.new", List(Lit(CInt(0))))),
            Let(List(While(
              Prim("__arith__", List(Lit(CStr("<")), lget(Local(0)), Lit(CInt(3)))),
              loopBody)),
              cget(2)))))

    val prog = Program(
      List(
        Def("counter", Prim("lcell.new", List(Lit(CInt(0))))),
        Def("area", area),
        Def("shapes", shapes),
        Def("workload", workload)),
      Lit(CUnit))

    val (_, globals) = Compiler.compileWithGlobals(prog)
    val workloadClos = globals("workload").asInstanceOf[Value.ClosV]

    assert(Runtime.run(workloadClos.code, workloadClos.env) == Value.FloatV(3.0))
    assert(globals("counter").asInstanceOf[Value.LongCellV].v == 3L)
  }

  test("v2 VM foreach fast path keeps escaping lambda env fresh") {
    import Const.*, Term.*

    val list =
      Ctor("Cons", List(
        Lit(CInt(1)),
        Ctor("Cons", List(Lit(CInt(2)), Ctor("Nil", Nil)))))
    val entry =
      Let(List(Prim("cell.new", List(Lit(CUnit)))),
        Let(List(Prim("lcell.new", List(Lit(CInt(0))))),
          Seq(List(
            Prim("__method__", List(
              Lit(CStr("foreach")),
              list,
              Lam(1,
                If(
                  Prim("__arith__", List(Lit(CStr("==")), Prim("lcell.get", List(Local(1))), Lit(CInt(0)))),
                  Seq(List(
                    Prim("cell.set", List(Local(2), Lam(0, Local(0)))),
                    Prim("lcell.set", List(Local(1), Lit(CInt(1)))))),
                  Lit(CUnit))))),
            App(Prim("cell.get", List(Local(1))), Nil)))))

    assert(runCore(entry) == Value.IntV(1))
  }

  test("v2 bytecode lcell arithmetic loop keeps VM result") {
    val src =
      """def workload(): Long =
        |  var i = 0
        |  var sum = 0L
        |  while i < 1000 do
        |    sum = sum + i
        |    i = i + 1
        |  sum
        |
        |workload()
        |""".stripMargin

    assert(runBytecode(src) == Value.IntV(499500))
  }

  test("v2 bytecode self-recursive int arithmetic keeps VM result") {
    val src =
      """def fib(n: Int): Int =
        |  if n <= 1 then n
        |  else fib(n - 1) + fib(n - 2)
        |
        |fib(30)
        |""".stripMargin

    assert(runBytecode(src) == Value.IntV(832040))
  }

  test("v2 bytecode local self and mutual tail recursion stay stack safe") {
    val self =
      """def count(n: Int): Int =
        |  def loop(left: Int, acc: Int): Int =
        |    if left <= 0 then acc else loop(left - 1, acc + 1)
        |  loop(n, 0)
        |
        |count(100000)
        |""".stripMargin
    assert(runBytecode(self) == Value.IntV(100000))

    import Const.*, Term.*
    def dec: Term = Prim("__arith__", List(Lit(CStr("-")), Local(0), Lit(CInt(1))))
    def isZero: Term = Prim("__arith__", List(Lit(CStr("==")), Local(0), Lit(CInt(0))))
    // In each arity-1 lambda: Local(0)=arg, Local(1)=odd (last group
    // binding), Local(2)=even (first group binding). The LetRec body sees
    // Local(0)=odd and Local(1)=even.
    val even = Lam(1, If(isZero, Lit(CBool(true)), App(Local(1), List(dec))))
    val odd  = Lam(1, If(isZero, Lit(CBool(false)), App(Local(2), List(dec))))
    val mutual = Program(
      Nil,
      LetRec(List(even, odd), App(Local(1), List(Lit(CInt(100001))))))

    assert(runBytecodeProgram(mutual) == Value.BoolV(false))
  }

  test("v2 bytecode closure application enforces VM arity") {
    import Const.*, Term.*
    val wrongArity = Program(
      Nil,
      Let(
        List(Lam(1, Local(0))),
        App(Local(0), List(Lit(CInt(1)), Lit(CInt(2))))))

    val error = intercept[RuntimeException](runBytecodeProgram(wrongArity))
    assert(error.getMessage == "arity: 1 expected, 2 given")
  }

  test("if-else") {
    assert(run("if (1 < 2) \"yes\" else \"no\"") == Value.StrV("yes"))
  }

  test("println output") {
    assert(capture("println(\"Hello, World!\")") == "Hello, World!")
  }

  test("v2 stream Source.runFold accepts curried source syntax") {
    val src =
      """println(Source.from(1 to 5).runFold(0)((acc, x) => acc + x))"""

    assert(capture(src) == "15")
  }

  test("v2 stream Source.from takes a small prefix from a large range") {
    val src =
      """println(Source.from(1 to 20000).take(3).runToList().mkString(", "))"""

    assert(capture(src) == "1, 2, 3")
  }

  test("v2 stream Source.throttle reads Rate case class fields"):
    val src =
      """println(Source.from(1 to 4).throttle(Rate(2, 0)).runToList().mkString(", "))"""

    assert(capture(src) == "1, 2, 3, 4")

  test("v2 stream ReactiveSignal.bind is available as a signal method"):
    val src =
      """val count = signal("count", 0)
        |val observed = Source.signal(count).take(3)
        |count.bind(Source.from(List(1, 2)))
        |println(observed.runToList().mkString(", "))
        |""".stripMargin

    assert(capture(src) == "0, 1, 2")

  test("markdown standard scala fence is runnable when it is the document source") {
    val src =
      """# Standard Scala block
        |
        |```scala
        |println("scala-block-ok")
        |```
        |""".stripMargin

    assert(capture(src) == "scala-block-ok")
  }

  test("markdown standard scala multi-fence document runs all fences in order") {
    val src =
      """# Standard Scala blocks
        |
        |```scala
        |println("scala-block-1")
        |```
        |
        |```scala
        |println("scala-block-2")
        |```
        |""".stripMargin

    assert(capture(src) == "scala-block-1\nscala-block-2")
  }

  test("standard scala string takeWhile supports char predicates") {
    val src =
      """# Standard Scala string predicate
        |
        |```scala
        |println("Circle(3)".takeWhile(_ != '('))
        |```
        |""".stripMargin

    assert(capture(src) == "Circle")
  }

  test("standard scala f interpolator applies format specs") {
    val src =
      """# Standard Scala f interpolation
        |
        |```scala
        |println(f"${"x"}%-4s=${1.234}%.1f")
        |```
        |""".stripMargin

    assert(capture(src) == "x   =1.2")
  }

  test("markdown ssc fence alias is runnable") {
    val src =
      """# ScalaScript alias block
        |
        |```ssc
        |println("ssc-block-ok")
        |```
        |""".stripMargin

    assert(capture(src) == "ssc-block-ok")
  }

  test("markdown scala fence stays illustrative in mixed scalascript document") {
    val src =
      """# Mixed blocks
        |
        |```scalascript
        |println("real-code")
        |```
        |
        |```scala
        |println("illustrative-code")
        |```
        |""".stripMargin

    assert(capture(src) == "real-code")
  }

  test("markdown scala fence runs in mixed document with explicit frontmatter opt-in") {
    val src =
      """---
        |name: mixed-runnable
        |runScalaFences: true
        |---
        |
        |# Mixed runnable blocks
        |
        |```scala
        |println("scala-before")
        |```
        |
        |```scalascript
        |println("scalascript-middle")
        |```
        |
        |```scala
        |println("scala-after")
        |```
        |""".stripMargin

    assert(capture(src) == "scala-before\nscalascript-middle\nscala-after")
  }

  test("guarded constructor pattern falls through to later matching case") {
    val src =
      """val left = List(5)
        |val right = List(2, 8)
        |(left, right) match
        |  case (ah :: at, bh :: _) if ah <= bh => println("left head " + ah)
        |  case (_, bh :: bt) => println("right head " + bh)
        |""".stripMargin

    assert(capture(src) == "right head 2")
  }

  test("var and while loop") {
    val src =
      """var i = 0
        |var s = 0
        |while (i < 5) {
        |  s = s + i
        |  i = i + 1
        |}
        |s""".stripMargin
    assert(run(src) == Value.IntV(10))
  }

  test("var and while arithmetic sum loop") {
    val src =
      """var i = 3
        |var s = 10
        |while i < 8 do
        |  s = s + i
        |  i = i + 1
        |s""".stripMargin
    assert(run(src) == Value.IntV(35))
  }

  test("recursive def") {
    val src =
      """def fib(n: Int): Int =
        |  if (n <= 1) n else fib(n - 1) + fib(n - 2)
        |fib(10)""".stripMargin
    assert(run(src) == Value.IntV(55))
  }

  test("lambda") {
    val src = "val f = (x: Int) => x + 1\nf(41)"
    assert(run(src) == Value.IntV(42))
  }

  test("match on constructor") {
    val src =
      """val xs = Cons(1, Cons(2, Nil))
        |xs match
        |  case Cons(h, _) => h
        |  case Nil        => 0""".stripMargin
    assert(run(src) == Value.IntV(1))
  }

  test("bool and/or short-circuit") {
    assert(run("true && false") == Value.BoolV(false))
    assert(run("false || true") == Value.BoolV(true))
  }

  test("string interpolation") {
    val src = "val name = \"v2\"\ns\"Hello $name!\""
    assert(run(src) == Value.StrV("Hello v2!"))
  }

  test("list literal after spaced infix operator") {
    val src =
      """val head = [1]
        |val xs = head ++ [2]
        |xs""".stripMargin
    assert(run(src) ==
      Value.DataV("Cons", Vector(Value.IntV(1),
        Value.DataV("Cons", Vector(Value.IntV(2), Value.DataV("Nil", Vector.empty))))))
  }
