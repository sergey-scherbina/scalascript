package ssc3

// The canonical `.ssir` text form — v3/specs/10-ssc-ir.md §5.
//
// The text form is CANONICAL FOR EQUALITY: every gate compares `.ssir`. Writer and reader go
// through one intermediate S-expression type, so they are obviously inverse rather than merely
// similar — two hand-written walkers that must agree are two copies of one decision, and this
// repository has paid for that shape repeatedly.
//
// No regex and no host Char classification (v3/specs/30-portable-subset.md); whitespace is the
// five characters v3/specs/20-core-language.md §3 defines and nothing else.

enum Sx:
  case Atom(s: String)
  case Str(s: String)
  case L(items: List[Sx])

final case class ParseError(message: String) extends RuntimeException(message)

object Text:

  // ── characters ──────────────────────────────────────────────────────────────
  // Our own, per the alphabet decision. A range comparison, no table, on any host.
  private def isSpace(c: Char): Boolean =
    c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f'

  private def isDelim(c: Char): Boolean = isSpace(c) || c == '(' || c == ')' || c == '"'

  // ── float formatting ────────────────────────────────────────────────────────
  /** Float text for the CANONICAL FORM, and used by nothing else.
    *
    * Deliberately its own function rather than a shared formatter. A shared one grows a parity
    * requirement from its other caller, and then the canonical form moves underneath you — that is
    * a recorded defect in this repository, not a hypothetical. */
  def floatText(d: Double): String =
    if d.isNaN then "nan"
    else if d == Double.PositiveInfinity then "inf"
    else if d == Double.NegativeInfinity then "-inf"
    else d.toString

  private def floatOf(s: String): Double =
    if s == "nan" then Double.NaN
    else if s == "inf" then Double.PositiveInfinity
    else if s == "-inf" then Double.NegativeInfinity
    else s.toDouble

  // ── string escaping ─────────────────────────────────────────────────────────
  private def esc(s: String): String =
    var out = ""
    var i = 0
    while i < s.length do
      val c = s.charAt(i)
      out =
        if c == '"' then out + "\\\""
        else if c == '\\' then out + "\\\\"
        else if c == '\n' then out + "\\n"
        else if c == '\r' then out + "\\r"
        else if c == '\t' then out + "\\t"
        else out + c
      i += 1
    out

  // ── reading ─────────────────────────────────────────────────────────────────
  private final case class Cursor(text: String, pos: Int)

  /** Whitespace AND `;` line comments.
    *
    * Reading only. `Text.write` never emits a comment, so the canonical form has none and `fmt`
    * strips them — stated rather than discovered, because a formatter that silently deletes what
    * someone wrote is worth knowing about before you rely on it.
    *
    * Added 2026-08-04 for the hand-written `.ssir` under `v3/tests/bridge/`: a fixture that exists
    * to pin one behaviour cannot say WHICH behaviour, and the bridge gate's refusal probe had just
    * gone stale in a way a sentence in the file would have made obvious. */
  private def skipSpace(c: Cursor): Cursor =
    var p = c.pos
    var go = true
    while go do
      while p < c.text.length && isSpace(c.text.charAt(p)) do p += 1
      if p < c.text.length && c.text.charAt(p) == ';' then
        while p < c.text.length && c.text.charAt(p) != '\n' do p += 1
      else go = false
    Cursor(c.text, p)

  private def readString(c: Cursor): (Sx, Cursor) =
    var p = c.pos + 1 // past the opening quote
    var out = ""
    var done = false
    while !done do
      if p >= c.text.length then throw ParseError("unterminated string literal")
      val ch = c.text.charAt(p)
      if ch == '"' then
        p += 1
        done = true
      else if ch == '\\' then
        if p + 1 >= c.text.length then throw ParseError("dangling escape in string literal")
        val e = c.text.charAt(p + 1)
        out =
          if e == 'n' then out + "\n"
          else if e == 'r' then out + "\r"
          else if e == 't' then out + "\t"
          else out + e
        p += 2
      else
        out = out + ch
        p += 1
    (Sx.Str(out), Cursor(c.text, p))

  private def readAtom(c: Cursor): (Sx, Cursor) =
    var p = c.pos
    while p < c.text.length && !isDelim(c.text.charAt(p)) do p += 1
    if p == c.pos then throw ParseError("empty atom at offset " + c.pos)
    (Sx.Atom(c.text.substring(c.pos, p)), Cursor(c.text, p))

  private def readList(c0: Cursor): (Sx, Cursor) =
    var c = Cursor(c0.text, c0.pos + 1) // past '('
    var items: List[Sx] = Nil
    var done = false
    while !done do
      c = skipSpace(c)
      if c.pos >= c.text.length then throw ParseError("unterminated list")
      if c.text.charAt(c.pos) == ')' then
        c = Cursor(c.text, c.pos + 1)
        done = true
      else
        val (item, c2) = readOne(c)
        items = item :: items
        c = c2
    (Sx.L(items.reverse), c)

  private def readOne(c0: Cursor): (Sx, Cursor) =
    val c = skipSpace(c0)
    if c.pos >= c.text.length then throw ParseError("unexpected end of input")
    val ch = c.text.charAt(c.pos)
    if ch == '(' then readList(c)
    else if ch == '"' then readString(c)
    else if ch == ')' then throw ParseError("unexpected ')' at offset " + c.pos)
    else readAtom(c)

  def readSx(text: String): Sx =
    val (sx, rest) = readOne(Cursor(text, 0))
    val after = skipSpace(rest)
    if after.pos != after.text.length then
      throw ParseError("trailing input after the top-level form at offset " + after.pos)
    sx

  // ── rendering ───────────────────────────────────────────────────────────────
  /** Indented, one form per line where the form has sub-forms. Readability is not decoration here:
    * a diff of two `.ssir` files is how a divergence gets located. */
  def renderSx(sx: Sx): String = render(sx, 0) + "\n"

  private def render(sx: Sx, indent: Int): String = sx match
    case Sx.Atom(s) => s
    case Sx.Str(s)  => "\"" + esc(s) + "\""
    case Sx.L(items) =>
      if items.isEmpty then "()"
      else if items.forall(isLeaf) then "(" + items.map(i => render(i, 0)).mkString(" ") + ")"
      else
        // Leading leaves stay on the head line — `(func "fib" 1 6` reads as a signature rather than
        // as four stray lines — and every sub-form gets a line of its own. No width heuristic and
        // no item-count threshold: a layout with a magic number in it is a layout that reflows when
        // an unrelated edit crosses the number, and a canonical form that reflows makes diffs lie.
        val leading = items.takeWhile(isLeaf)
        val rest = items.drop(leading.length)
        val pad = " " * (indent + 2)
        val head = if leading.isEmpty then "" else leading.map(i => render(i, 0)).mkString(" ")
        val body = rest.map(i => pad + render(i, indent + 2)).mkString("\n")
        if leading.isEmpty then "(\n" + body + ")" else "(" + head + "\n" + body + ")"

  private def isLeaf(sx: Sx): Boolean = sx match
    case Sx.L(_) => false
    case _       => true

  // ── Module → Sx ─────────────────────────────────────────────────────────────
  private def a(s: String): Sx = Sx.Atom(s)
  private def n(i: Int): Sx = Sx.Atom(i.toString)
  private def rs(xs: List[Int]): List[Sx] = xs.map(n)

  private def litSx(l: Lit): Sx = l match
    case Lit.LUnit        => Sx.L(List(a("unit")))
    case Lit.LBool(b)     => Sx.L(List(a("bool"), a(if b then "true" else "false")))
    case Lit.LInt(v)      => Sx.L(List(a("int"), a(v.toString)))
    case Lit.LBig(d)      => Sx.L(List(a("big"), a(d)))
    case Lit.LFloat(d)    => Sx.L(List(a("float"), a(floatText(d))))
    case Lit.LStr(s)      => Sx.L(List(a("str"), Sx.Str(s)))
    case Lit.LBytes(h)    => Sx.L(List(a("bytes"), a(h)))

  private def kindSx(k: NumKind): Sx = k match
    case NumKind.Dyn => a("dyn")
    case NumKind.I64 => a("i64")
    case NumKind.F64 => a("f64")
    case NumKind.Big => a("big")

  private def unSx(o: UnOp): Sx = o match
    case UnOp.Neg  => a("neg")
    case UnOp.Not  => a("not")
    case UnOp.BNot => a("bnot")

  private def binSx(o: BinOp): Sx = o match
    case BinOp.Add => a("add"); case BinOp.Sub => a("sub"); case BinOp.Mul => a("mul")
    case BinOp.Div => a("div"); case BinOp.Rem => a("rem")
    case BinOp.BAnd => a("band"); case BinOp.BOr => a("bor"); case BinOp.BXor => a("bxor")
    case BinOp.Shl => a("shl"); case BinOp.Shr => a("shr"); case BinOp.UShr => a("ushr")
    case BinOp.Lt => a("lt"); case BinOp.Le => a("le"); case BinOp.Gt => a("gt")
    case BinOp.Ge => a("ge"); case BinOp.Eq => a("eq"); case BinOp.Ne => a("ne")

  /** The opcode name an instruction serializes under. Read off `instrSx`, so the closed vocabulary
    * in the self-test is compared against what the WRITER actually emits rather than against a
    * second hand-maintained list. */
  def opcode(i: Instr): String = instrSx(i) match
    case Sx.L(items) if items.nonEmpty => atom(items.head)
    case _ => throw ParseError("instruction rendered without a head atom")

  private def instrSx(i: Instr): Sx = i match
    case Instr.Const(d, k)        => Sx.L(List(a("const"), n(d), n(k)))
    case Instr.Move(d, s)         => Sx.L(List(a("move"), n(d), n(s)))
    case Instr.Un(o, k, d, x)     => Sx.L(List(a("un"), unSx(o), kindSx(k), n(d), n(x)))
    case Instr.Bin(o, k, d, x, y) => Sx.L(List(a("bin"), binSx(o), kindSx(k), n(d), n(x), n(y)))
    case Instr.Block(b)           => Sx.L(a("block") :: b.map(instrSx))
    case Instr.Loop(b)            => Sx.L(a("loop") :: b.map(instrSx))
    case Instr.If(c, t, e) =>
      Sx.L(List(a("if"), n(c), Sx.L(a("then") :: t.map(instrSx)), Sx.L(a("else") :: e.map(instrSx))))
    case Instr.Br(d)              => Sx.L(List(a("br"), n(d)))
    case Instr.BrIf(c, d)         => Sx.L(List(a("brif"), n(c), n(d)))
    case Instr.Call(d, f, as)     => Sx.L(a("call") :: n(d) :: n(f) :: rs(as))
    case Instr.CallV(d, c, as)    => Sx.L(a("callv") :: n(d) :: n(c) :: rs(as))
    case Instr.MkClos(d, f, cs)   => Sx.L(a("mkclos") :: n(d) :: n(f) :: rs(cs))
    case Instr.TailCall(f, as)    => Sx.L(a("tailcall") :: n(f) :: rs(as))
    case Instr.Ret(x)             => Sx.L(List(a("ret"), n(x)))
    case Instr.MkData(d, t, as)   => Sx.L(a("mkdata") :: n(d) :: n(t) :: rs(as))
    case Instr.Field(d, x, t, ix) => Sx.L(List(a("field"), n(d), n(x), n(t), n(ix)))
    case Instr.Tag(d, x)          => Sx.L(List(a("tag"), n(d), n(x)))
    case Instr.Switch(s, arms, df) =>
      Sx.L(
        a("switch") :: n(s) ::
          (arms.map(ar => Sx.L(a("arm") :: n(ar.tag) :: ar.body.map(instrSx))) :+
            Sx.L(a("default") :: df.map(instrSx)))
      )
    case Instr.NewArr(d, l)       => Sx.L(List(a("newarr"), n(d), n(l)))
    case Instr.ArrGet(d, ar, ix)  => Sx.L(List(a("arrget"), n(d), n(ar), n(ix)))
    case Instr.ArrSet(ar, ix, v)  => Sx.L(List(a("arrset"), n(ar), n(ix), n(v)))
    case Instr.ArrLen(d, ar)      => Sx.L(List(a("arrlen"), n(d), n(ar)))
    case Instr.GlobGet(d, g)      => Sx.L(List(a("globget"), n(d), n(g)))
    case Instr.GlobSet(g, x)      => Sx.L(List(a("globset"), n(g), n(x)))
    case Instr.Perform(d, o, as)  => Sx.L(a("perform") :: n(d) :: n(o) :: rs(as))
    case Instr.Handle(d, b, arms) =>
      Sx.L(
        a("handle") :: n(d) :: Sx.L(a("body") :: b.map(instrSx)) ::
          arms.map(ar =>
            Sx.L(a("on") :: n(ar.op) :: Sx.L(a("params") :: ar.params.map(n)) ::
                 Sx.L(List(a("k"), n(ar.k))) :: ar.body.map(instrSx)))
      )
    case Instr.Resume(d, k, v)    => Sx.L(List(a("resume"), n(d), n(k), n(v)))
    case Instr.Try(d, b, x, h) =>
      Sx.L(List(a("try"), n(d), n(x), Sx.L(a("body") :: b.map(instrSx)), Sx.L(a("catch") :: h.map(instrSx))))
    case Instr.Invoke(d, nm, r, as) => Sx.L(a("invoke") :: n(d) :: n(nm) :: n(r) :: rs(as))
    case Instr.Prim(d, p, as)     => Sx.L(a("prim") :: n(d) :: n(p) :: rs(as))

  def moduleSx(m: Module): Sx =
    Sx.L(
      List(
        a("module"),
        Sx.L(a("consts") :: m.consts.map(litSx)),
        // THE FIELD NAMES ARE WRITTEN WHEN THERE ARE ANY, and omitted when there are not, so a
        // builtin constructor's entry keeps the two-item shape it has always had.
        Sx.L(a("types") :: m.types.map(t =>
          Sx.L(List(a("type"), Sx.Str(t.name), n(t.fields)) ++
               (if t.fieldNames.isEmpty then Nil else List(Sx.L(t.fieldNames.map(Sx.Str.apply))))))),
        Sx.L(a("globals") :: m.globals.map(g => Sx.L(List(a("global"), Sx.Str(g.name))))),
        Sx.L(a("prims") :: m.prims.map(p => Sx.L(List(a("prim"), Sx.Str(p))))),
        Sx.L(a("funcs") :: m.funcs.map(f =>
          Sx.L(a("func") :: Sx.Str(f.name) :: n(f.nparams) :: n(f.nregs) :: f.body.map(instrSx))
        )),
        Sx.L(List(a("entry"), n(m.entry))),
      )
    )

  def write(m: Module): String = renderSx(moduleSx(m))

  // ── Sx → Module ─────────────────────────────────────────────────────────────
  private def atom(sx: Sx): String = sx match
    case Sx.Atom(s) => s
    case Sx.Str(s)  => s
    case _          => throw ParseError("expected an atom, found a list")

  private def int(sx: Sx): Int =
    val s = atom(sx)
    try s.toInt
    catch case _: NumberFormatException => throw ParseError("expected an integer, found '" + s + "'")

  private def items(sx: Sx, head: String): List[Sx] = sx match
    case Sx.L(xs) if xs.nonEmpty && atom(xs.head) == head => xs.tail
    case _ => throw ParseError("expected a (" + head + " …) form")

  private def litOf(sx: Sx): Lit =
    val xs = sx match
      case Sx.L(l) => l
      case _       => throw ParseError("expected a constant form")
    if xs.isEmpty then throw ParseError("empty constant form")
    atom(xs.head) match
      case "unit"  => Lit.LUnit
      case "bool"  => Lit.LBool(atom(xs(1)) == "true")
      case "int"   => Lit.LInt(atom(xs(1)).toLong)
      case "big"   => Lit.LBig(atom(xs(1)))
      case "float" => Lit.LFloat(floatOf(atom(xs(1))))
      case "str"   => Lit.LStr(atom(xs(1)))
      case "bytes" => Lit.LBytes(atom(xs(1)))
      case other   => throw ParseError("unknown constant kind '" + other + "'")

  private def kindOf(sx: Sx): NumKind = atom(sx) match
    case "dyn" => NumKind.Dyn
    case "i64" => NumKind.I64
    case "f64" => NumKind.F64
    case "big" => NumKind.Big
    case o     => throw ParseError("unknown numeric kind '" + o + "'")

  private def unOf(sx: Sx): UnOp = atom(sx) match
    case "neg" => UnOp.Neg; case "not" => UnOp.Not; case "bnot" => UnOp.BNot
    case o     => throw ParseError("unknown unary op '" + o + "'")

  private def binOf(sx: Sx): BinOp = atom(sx) match
    case "add" => BinOp.Add; case "sub" => BinOp.Sub; case "mul" => BinOp.Mul
    case "div" => BinOp.Div; case "rem" => BinOp.Rem
    case "band" => BinOp.BAnd; case "bor" => BinOp.BOr; case "bxor" => BinOp.BXor
    case "shl" => BinOp.Shl; case "shr" => BinOp.Shr; case "ushr" => BinOp.UShr
    case "lt" => BinOp.Lt; case "le" => BinOp.Le; case "gt" => BinOp.Gt
    case "ge" => BinOp.Ge; case "eq" => BinOp.Eq; case "ne" => BinOp.Ne
    case o    => throw ParseError("unknown binary op '" + o + "'")

  private def body(xs: List[Sx]): List[Instr] = xs.map(instrOf)

  private def instrOf(sx: Sx): Instr =
    val xs = sx match
      case Sx.L(l) if l.nonEmpty => l
      case _                     => throw ParseError("expected an instruction form")
    val t = xs.tail
    // An unknown opcode is REFUSED, never ignored. A reader that skips what it does not recognise
    // turns a version mismatch into a program that silently does less.
    atom(xs.head) match
      case "const"  => Instr.Const(int(t(0)), int(t(1)))
      case "move"   => Instr.Move(int(t(0)), int(t(1)))
      case "un"     => Instr.Un(unOf(t(0)), kindOf(t(1)), int(t(2)), int(t(3)))
      case "bin"    => Instr.Bin(binOf(t(0)), kindOf(t(1)), int(t(2)), int(t(3)), int(t(4)))
      case "block"  => Instr.Block(body(t))
      case "loop"   => Instr.Loop(body(t))
      case "if"     => Instr.If(int(t(0)), body(items(t(1), "then")), body(items(t(2), "else")))
      case "br"     => Instr.Br(int(t(0)))
      case "brif"   => Instr.BrIf(int(t(0)), int(t(1)))
      case "call"   => Instr.Call(int(t(0)), int(t(1)), t.drop(2).map(int))
      case "callv"  => Instr.CallV(int(t(0)), int(t(1)), t.drop(2).map(int))
      case "mkclos" => Instr.MkClos(int(t(0)), int(t(1)), t.drop(2).map(int))
      case "tailcall" => Instr.TailCall(int(t(0)), t.drop(1).map(int))
      case "ret"    => Instr.Ret(int(t(0)))
      case "mkdata" => Instr.MkData(int(t(0)), int(t(1)), t.drop(2).map(int))
      case "field"  => Instr.Field(int(t(0)), int(t(1)), int(t(2)), int(t(3)))
      case "tag"    => Instr.Tag(int(t(0)), int(t(1)))
      case "switch" =>
        val rest = t.tail
        val arms = rest.init.map { ar =>
          val ai = items(ar, "arm")
          SwitchArm(int(ai.head), body(ai.tail))
        }
        Instr.Switch(int(t.head), arms, body(items(rest.last, "default")))
      case "newarr" => Instr.NewArr(int(t(0)), int(t(1)))
      case "arrget" => Instr.ArrGet(int(t(0)), int(t(1)), int(t(2)))
      case "arrset" => Instr.ArrSet(int(t(0)), int(t(1)), int(t(2)))
      case "arrlen" => Instr.ArrLen(int(t(0)), int(t(1)))
      case "globget" => Instr.GlobGet(int(t(0)), int(t(1)))
      case "globset" => Instr.GlobSet(int(t(0)), int(t(1)))
      case "perform" => Instr.Perform(int(t(0)), int(t(1)), t.drop(2).map(int))
      case "handle" =>
        val arms = t.drop(2).map { ar =>
          val ai = items(ar, "on")
          HandlerArm(int(ai.head), items(ai(1), "params").map(int), int(items(ai(2), "k").head),
                     body(ai.drop(3)))
        }
        Instr.Handle(int(t.head), body(items(t(1), "body")), arms)
      case "resume" => Instr.Resume(int(t(0)), int(t(1)), int(t(2)))
      case "try"    => Instr.Try(int(t(0)), body(items(t(2), "body")), int(t(1)), body(items(t(3), "catch")))
      case "invoke" => Instr.Invoke(int(t(0)), int(t(1)), int(t(2)), t.drop(3).map(int))
      case "prim"   => Instr.Prim(int(t(0)), int(t(1)), t.drop(2).map(int))
      case other    => throw ParseError("unknown instruction '" + other + "'")

  def moduleOf(sx: Sx): Module =
    val xs = items(sx, "module")
    Module(
      consts = items(xs(0), "consts").map(litOf),
      types = items(xs(1), "types").map { t =>
        // A TYPE ENTRY IS READ WITH OR WITHOUT ITS FIELD NAMES. The hand-written fixtures under
        // `v3/tests/bridge/` predate them and must keep parsing; the writer above always emits them
        // when the module has them, so a round-trip through this reader is stable either way.
        val ti = items(t, "type")
        val tns = if ti.length > 2 then ti(2) match
                    case Sx.L(xs) => xs.map { case Sx.Str(s) => s; case other => atom(other) }
                    case _        => Nil
                  else Nil
        TypeDef(atom(ti(0)), int(ti(1)), tns)
      },
      globals = items(xs(2), "globals").map(g => GlobalDef(atom(items(g, "global").head))),
      prims = items(xs(3), "prims").map(p => atom(items(p, "prim").head)),
      funcs = items(xs(4), "funcs").map { f =>
        val fi = items(f, "func")
        Func(atom(fi(0)), int(fi(1)), int(fi(2)), body(fi.drop(3)))
      },
      entry = int(items(xs(5), "entry").head),
    )

  def read(text: String): Module = moduleOf(readSx(text))
