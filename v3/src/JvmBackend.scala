package ssc3

// SSC IR -> a JVM class file, written by v3 itself. Stage 1 of v3/specs/70-jvm-backend.md §8.
//
// NO LIBRARY, AND THAT IS THE POINT. Invariant I-1 names backends: "the kernel — lexer, AST, IR,
// verifier, executor, backends — builds with an empty libraryDependencies". v2's bytecode lane uses
// `org.ow2.asm:asm:9.7`; this writes the class file byte by byte with the JDK and nothing else, and
// the v3 kernel's zero `import` statements stay zero.
//
// WHY THAT IS AFFORDABLE, measured rather than hoped. The reason people reach for ASM is
// `StackMapTable` — the per-branch-target type map the split verifier demands, which is a dataflow
// analysis. `v3/tests/jvm-backend-probe/run.sh` measured that frames are required only where a
// BRANCH TARGET exists: a straight-line method verifies with no frames at every class-file version
// including 52. Stage 1 emits no control flow, so it needs no frames; the computer arrives with
// §8 stage 3, which is the first stage that creates a branch target.
//
// MAJOR 52, decided by the owner (spec §7 Q2): `invokedynamic` needs 51, so the cheaper major-50
// route — no frames ever, one generated class per lambda — was declined in favour of indy and a
// real frame computer. Nothing in stage 1 needs either yet; 52 is chosen now so the version never
// has to move under working code.
//
// WHAT STAGE 1 IS, stated narrowly so the gate cannot be read as saying more. It compiles
// straight-line I64 arithmetic and nothing else. Every other instruction, every other numeric kind
// and every other literal is REFUSED BY NAME through `Unsupported` — the module's rule is honest
// refusals over silent wrong answers, and a backend that emitted a plausible zero for a construct
// it does not implement would be the exact failure v3 exists to avoid.
//
// THE PORTABLE SUBSET APPLIES HERE TOO (I-2, v3/specs/30-portable-subset.md): immutable data, List,
// local `var`/`while`, `Array`. So the constant pool is a List with linear lookup and the byte
// buffer is a reversed List folded once at the end. Both are O(n^2)-ish and both are deliberate:
// stage 1 modules are tens of instructions, and the first thing to measure is correctness. When a
// number says this costs, it is a contained change — the pool is behind `poolIndex` and the buffer
// behind `emit`.

object JvmBackend:

  /** A construct this backend does not translate YET. Carries the construct's own name so the
    * message says what is missing rather than that something is. */
  final case class Unsupported(what: String)
      extends RuntimeException("the v3 JVM backend (stage 1) does not translate " + what)

  // ── constant pool ───────────────────────────────────────────────────────────
  //
  // Entries are kept as their ENCODED BYTES plus a key for interning. Keeping the bytes rather than
  // a tagged case class means the writer has one representation to get right instead of two, and
  // the key is what makes `poolIndex` idempotent — asking twice for `java/lang/Object` must not
  // grow the pool, or every index after it shifts.
  private final case class Pool(entries: List[(String, List[Int])], count: Int)

  private def emptyPool: Pool = Pool(Nil, 0)

  private def u1(n: Int): List[Int] = List(n & 0xff)
  private def u2(n: Int): List[Int] = List((n >>> 8) & 0xff, n & 0xff)
  private def u4(n: Int): List[Int] = List((n >>> 24) & 0xff, (n >>> 16) & 0xff, (n >>> 8) & 0xff, n & 0xff)

  /** Modified UTF-8, which is NOT `String.getBytes("UTF-8")` for two cases the class file format
    * spells differently: a NUL is two bytes, and a character above the BMP is encoded as its two
    * surrogates rather than as one 4-byte sequence. Stage 1 emits only ASCII names, so this is
    * written for the general case and exercised by none of it — said here so the next reader knows
    * it is untested rather than assuming it is proven. */
  private def modifiedUtf8(s: String): List[Int] =
    var out: List[Int] = Nil
    var i = 0
    while i < s.length do
      val c = s.charAt(i).toInt
      if c > 0 && c < 0x80 then out = c :: out
      else if c < 0x800 then
        out = (0x80 | (c & 0x3f)) :: (0xc0 | (c >>> 6)) :: out
      else
        out = (0x80 | (c & 0x3f)) :: (0x80 | ((c >>> 6) & 0x3f)) :: (0xe0 | (c >>> 12)) :: out
      i = i + 1
    out.reverse

  private def find(p: Pool, key: String): Int =
    // The pool is built head-first, so entry n is at position count-n in the list.
    var rest = p.entries
    var idx = p.count
    var found = 0
    while rest.nonEmpty do
      if found == 0 && rest.head._1 == key then found = idx
      idx = idx - 1
      rest = rest.tail
    found

  private def add(p: Pool, key: String, bytes: List[Int]): (Pool, Int) =
    val existing = find(p, key)
    if existing != 0 then (p, existing)
    else (Pool((key, bytes) :: p.entries, p.count + 1), p.count + 1)

  private def utf8Index(p: Pool, s: String): (Pool, Int) =
    add(p, "u:" + s, u1(1) ++ u2(modifiedUtf8(s).length) ++ modifiedUtf8(s))

  private def classIndex(p: Pool, name: String): (Pool, Int) =
    val n = utf8Index(p, name)
    add(n._1, "c:" + name, u1(7) ++ u2(n._2))

  private def nameAndTypeIndex(p: Pool, name: String, desc: String): (Pool, Int) =
    val n = utf8Index(p, name)
    val d = utf8Index(n._1, desc)
    add(d._1, "nt:" + name + ":" + desc, u1(12) ++ u2(n._2) ++ u2(d._2))

  private def memberIndex(p: Pool, tag: Int, owner: String, name: String, desc: String): (Pool, Int) =
    val c = classIndex(p, owner)
    val nt = nameAndTypeIndex(c._1, name, desc)
    add(nt._1, (if tag == 9 then "f:" else "m:") + owner + "." + name + ":" + desc,
        u1(tag) ++ u2(c._2) ++ u2(nt._2))

  // ── the emitter ─────────────────────────────────────────────────────────────
  //
  // A REGISTER IS A JVM LOCAL, and at stage 1 every value is a `long`, so register i lives at slot
  // 2*i — a `long` occupies two slots and the second is unusable. That mapping is arithmetic rather
  // than a table because stage 1 has one value type; when stage 2 admits more, the mapping becomes
  // a real assignment and this comment is the marker for where.
  private def slotOf(reg: Int): Int = reg * 2

  private def lload(reg: Int): List[Int] =
    val s = slotOf(reg)
    if s <= 3 then u1(0x1e + s) // lload_0 … lload_3
    else u1(0x16) ++ u1(s)      // lload <slot>   (slots above 255 need `wide`; refused below)

  private def lstore(reg: Int): List[Int] =
    val s = slotOf(reg)
    if s <= 3 then u1(0x3f + s) // lstore_0 … lstore_3
    else u1(0x37) ++ u1(s)

  private def binOpcode(op: BinOp): Int = op match
    case BinOp.Add  => 0x61 // ladd
    case BinOp.Sub  => 0x65 // lsub
    case BinOp.Mul  => 0x69 // lmul
    case BinOp.Div  => 0x6d // ldiv
    case BinOp.Rem  => 0x71 // lrem
    case BinOp.BAnd => 0x7f // land
    case BinOp.BOr  => 0x81 // lor
    case BinOp.BXor => 0x83 // lxor
    case _          => throw Unsupported("the binary operator " + op.toString + " at stage 1")

  /** `lshl`/`lshr`/`lushr` take an INT shift amount, not a long — the one place the uniform
    * "every register is a long" mapping does not line up with the instruction set, and getting it
    * wrong is a `VerifyError` rather than a wrong answer. */
  private def shiftOpcode(op: BinOp): Int = op match
    case BinOp.Shl  => 0x79
    case BinOp.Shr  => 0x7b
    case BinOp.UShr => 0x7d
    case _          => throw Unsupported("the shift operator " + op.toString)

  private def isShift(op: BinOp): Boolean =
    op == BinOp.Shl || op == BinOp.Shr || op == BinOp.UShr

  /** Push a constant-pool `long` with `ldc2_w`. */
  private def pushLong(p: Pool, v: Long): (Pool, List[Int]) =
    val added = add(p, "l:" + v.toString, u1(5) ++ u4(((v >>> 32) & 0xffffffffL).toInt) ++ u4((v & 0xffffffffL).toInt))
    // A `long` entry occupies TWO pool slots — the format says the next index is unusable. Stage 1
    // adds a filler so every later index stays correct; the alternative, tracking a hole, is a
    // second rule for the same fact.
    val filled = Pool(("pad:" + v.toString, Nil) :: added._1.entries, added._1.count + 1)
    (filled, u1(0x14) ++ u2(added._2))

  private def emitInstr(m: Module, p: Pool, i: Instr): (Pool, List[Int]) = i match
    case Instr.Const(dst, k) =>
      if k < 0 || k >= m.consts.length then throw Unsupported("a constant index outside the pool")
      m.consts(k) match
        case Lit.LInt(n) =>
          val pushed = pushLong(p, n)
          (pushed._1, pushed._2 ++ lstore(dst))
        case other => throw Unsupported("the literal " + other.toString + " at stage 1 (I64 only)")

    case Instr.Move(dst, a) => (p, lload(a) ++ lstore(dst))

    case Instr.Bin(op, kind, dst, a, b) =>
      if kind != NumKind.I64 then
        throw Unsupported("arithmetic of kind " + kind.toString + " at stage 1 (I64 only)")
      if isShift(op) then
        // long << int: the right operand is narrowed with `l2i`, which is what javac emits too.
        (p, lload(a) ++ lload(b) ++ u1(0x88) ++ u1(shiftOpcode(op)) ++ lstore(dst))
      else (p, lload(a) ++ lload(b) ++ u1(binOpcode(op)) ++ lstore(dst))

    case Instr.Un(op, kind, dst, a) =>
      if kind != NumKind.I64 then
        throw Unsupported("arithmetic of kind " + kind.toString + " at stage 1 (I64 only)")
      op match
        case UnOp.Neg => (p, lload(a) ++ u1(0x75) ++ lstore(dst)) // lneg
        case UnOp.BNot =>
          // No `lnot` exists; XOR with -1 is the encoding javac uses.
          val minusOne = pushLong(p, -1L)
          (minusOne._1, lload(a) ++ minusOne._2 ++ u1(0x83) ++ lstore(dst))
        case UnOp.Not => throw Unsupported("logical `not` at stage 1 (it produces a Bool)")

    case Instr.Ret(a) => (p, lload(a) ++ u1(0xad)) // lreturn

    case other => throw Unsupported(other.getClass.getSimpleName)

  private def emitBody(m: Module, p: Pool, body: List[Instr]): (Pool, List[Int]) =
    var pool = p
    var out: List[Int] = Nil
    var rest = body
    while rest.nonEmpty do
      val step = emitInstr(m, pool, rest.head)
      pool = step._1
      out = out ++ step._2
      rest = rest.tail
    (pool, out)

  /** The class file for `m`'s ENTRY function, as `<name>.class` bytes.
    *
    * Two methods: `entry()J`, the compiled function, and `main([Ljava/lang/String;)V`, which prints
    * what it returned. The `main` is the STAGE 1 HARNESS, not the eventual shape — printing belongs
    * to `Prim`, which is stage 6 and the owner's v3-owned host layer (spec §7 Q3). It exists so the
    * stage has something observable to gate on, and it is spelled out here so nobody reads it as
    * the design. */
  def classFile(m: Module, className: String): Array[Byte] =
    if m.entry < 0 || m.entry >= m.funcs.length then throw Unsupported("a module with no entry function")
    val f = m.funcs(m.entry)
    if f.nparams != 0 then throw Unsupported("an entry function that takes parameters at stage 1")
    if slotOf(f.nregs) > 255 then throw Unsupported("a frame needing more than 255 JVM local slots (no `wide` yet)")

    var p = emptyPool
    val thisC = classIndex(p, className);                 p = thisC._1
    val objC  = classIndex(p, "java/lang/Object");        p = objC._1
    val codeU = utf8Index(p, "Code");                     p = codeU._1
    val entryN = utf8Index(p, "entry");                   p = entryN._1
    val entryD = utf8Index(p, "()J");                     p = entryD._1
    val mainN = utf8Index(p, "main");                     p = mainN._1
    val mainD = utf8Index(p, "([Ljava/lang/String;)V");   p = mainD._1
    val outF  = memberIndex(p, 9, "java/lang/System", "out", "Ljava/io/PrintStream;"); p = outF._1
    val prnM  = memberIndex(p, 10, "java/io/PrintStream", "println", "(J)V");          p = prnM._1
    val entM  = memberIndex(p, 10, className, "entry", "()J");                          p = entM._1

    val emitted = emitBody(m, p, f.body)
    p = emitted._1
    val code = emitted._2
    if code.isEmpty then throw Unsupported("an entry function with an empty body")

    // max_stack 4: the widest shape is `long, long` for a binary op (4 slots).
    val entryCode = u2(4) ++ u2(slotOf(f.nregs)) ++ u4(code.length) ++ code ++ u2(0) ++ u2(0)
    val entryM = u2(0x0009) ++ u2(entryN._2) ++ u2(entryD._2) ++ u2(1) ++
                 u2(codeU._2) ++ u4(entryCode.length) ++ entryCode

    // getstatic out; invokestatic entry; invokevirtual println(J)V; return
    val mainBody = u1(0xb2) ++ u2(outF._2) ++ u1(0xb8) ++ u2(entM._2) ++ u1(0xb6) ++ u2(prnM._2) ++ u1(0xb1)
    val mainCode = u2(3) ++ u2(1) ++ u4(mainBody.length) ++ mainBody ++ u2(0) ++ u2(0)
    val mainM = u2(0x0009) ++ u2(mainN._2) ++ u2(mainD._2) ++ u2(1) ++
                u2(codeU._2) ++ u4(mainCode.length) ++ mainCode

    // NO StackMapTable, and it is not an omission — neither method branches, so neither has a
    // branch target, so the verifier needs no frames. `v3/tests/jvm-backend-probe/run.sh` is the
    // measurement; §8 stage 3 is when this stops being true.
    val header = u4(0xcafebabe) ++ u2(0) ++ u2(52) ++ u2(p.count + 1) ++ p.entries.reverse.flatMap(e => e._2)
    val rest = u2(0x0031) ++ u2(thisC._2) ++ u2(objC._2) ++ u2(0) ++ u2(0) ++ u2(2) ++ entryM ++ mainM ++ u2(0)

    val all = header ++ rest
    val bytes = new Array[Byte](all.length)
    var i = 0
    var walk = all
    while walk.nonEmpty do
      bytes(i) = walk.head.toByte
      i = i + 1
      walk = walk.tail
    bytes
