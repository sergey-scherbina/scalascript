package ssc3

// SSC IR -> a JVM class file, written by v3 itself. Stages 1-2 of v3/specs/70-jvm-backend.md §8.
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
// including 52. Stages 1-2 emit no control flow, so they need no frames; the computer arrives with
// §8 stage 3, which is the first stage that creates a branch target — and stage 2's uniform boxed
// representation is what will make it cheap when it does (see `slotOf`).
//
// MAJOR 52, decided by the owner (spec §7 Q2): `invokedynamic` needs 51, so the cheaper major-50
// route — no frames ever, one generated class per lambda — was declined in favour of indy and a
// real frame computer. Nothing in stages 1-2 needs either yet; 52 is chosen now so the version never
// has to move under working code.
//
// WHAT STAGE 2 IS, stated narrowly so the gate cannot be read as saying more. Straight-line
// `I64` arithmetic (stage 1) plus the DATA instructions: `MkData Field NewArr ArrGet ArrSet ArrLen
// GlobGet GlobSet`. No control flow, no calls, no `Prim` — and no `Tag`, which needs a branch to
// keep the executor's totality and is therefore stage 3's. Every other instruction, numeric kind
// and literal is REFUSED BY NAME through `Unsupported` — the module's rule is honest refusals over
// silent wrong answers, and a backend that emitted a plausible zero for a construct it does not
// implement would be the exact failure v3 exists to avoid.
//
// THE PORTABLE SUBSET APPLIES HERE TOO (I-2, v3/specs/30-portable-subset.md): immutable data, List,
// local `var`/`while`, `Array`. So the constant pool is a List with linear lookup and the byte
// buffer is a reversed List folded once at the end. Both are O(n^2)-ish and both are deliberate:
// these modules are tens of instructions, and the first thing to measure is correctness. When a
// number says this costs, it is a contained change — the pool is behind `poolIndex` and the buffer
// behind `emit`.

object JvmBackend:

  /** A construct this backend does not translate YET. Carries the construct's own name so the
    * message says what is missing rather than that something is. */
  final case class Unsupported(what: String)
      extends RuntimeException("the v3 JVM backend (stage 2) does not translate " + what)

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
  // A REGISTER IS ONE JVM LOCAL OF TYPE `Ljava/lang/Object;`, and an `I64` in it is a
  // `java.lang.Long`. Stage 1 kept a raw `long` in two slots; nothing that holds a record or an
  // array fits there, so stage 2 pays a box/unbox around every arithmetic instruction and gets a
  // representation that holds every value the IR has.
  //
  // THAT PRICE BUYS STAGE 3 TOO, which is why it is the design rather than a shortcut. A
  // `StackMapTable` is hard because it MERGES the verification types of each local across the
  // branches reaching a target; when every local is `java/lang/Object` in every branch there is
  // nothing to merge, and the computer §8 stage 3 needs shrinks to bookkeeping. When boxing is
  // removed it will be as an optimisation with a measured number, not as a correction.
  private def slotOf(reg: Int): Int = reg

  private def aloadR(reg: Int): List[Int] =
    val s = slotOf(reg)
    if s <= 3 then u1(0x2a + s) // aload_0 … aload_3
    else u1(0x19) ++ u1(s)      // aload <slot>   (slots above 255 need `wide`; refused below)

  private def astoreR(reg: Int): List[Int] =
    val s = slotOf(reg)
    if s <= 3 then u1(0x4b + s) // astore_0 … astore_3
    else u1(0x3a) ++ u1(s)

  /** An immediate `int` — a field index, an arity. Never a register value, which arrives as a
    * `Long` and is narrowed with `l2i`. Beyond `sipush` this refuses rather than reaching for `ldc`:
    * nothing in the IR produces a 32k-field record, so an untested path is worse than a refusal. */
  private def pushInt(n: Int): List[Int] =
    if n >= 0 && n <= 5 then u1(0x03 + n)         // iconst_0 … iconst_5
    else if n >= -128 && n <= 127 then u1(0x10) ++ u1(n)
    else if n >= -32768 && n <= 32767 then u1(0x11) ++ u2(n)
    else throw Unsupported("an immediate index of " + n + " (beyond sipush)")

  /** `java.lang.Long.valueOf(J)` — the boxing half. `valueOf` rather than `new Long`, because it
    * caches -128..127 and because `new` on a boxed type is deprecated in the platform. */
  private def boxLong(p: Pool): (Pool, List[Int]) =
    val mi = memberIndex(p, 10, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;")
    (mi._1, u1(0xb8) ++ u2(mi._2))

  /** Load register `r` and unbox it to a `long`. The `checkcast` is not decoration: it is what
    * turns "this register did not hold a number" into a `ClassCastException` naming the type
    * instead of a wrong answer. */
  private def unboxLong(p: Pool, r: Int): (Pool, List[Int]) =
    val ci = classIndex(p, "java/lang/Long")
    val mi = memberIndex(ci._1, 10, "java/lang/Long", "longValue", "()J")
    (mi._1, aloadR(r) ++ u1(0xc0) ++ u2(ci._2) ++ u1(0xb6) ++ u2(mi._2))

  /** Load register `r` as an `int` — a length or an index, narrowed from the `Long` it is. */
  private def loadInt(p: Pool, r: Int): (Pool, List[Int]) =
    val u = unboxLong(p, r)
    (u._1, u._2 ++ u1(0x88)) // l2i

  private def checkcastArrayList(p: Pool): (Pool, List[Int]) =
    val ci = classIndex(p, "java/util/ArrayList")
    (ci._1, u1(0xc0) ++ u2(ci._2))

  private def checkcastRecord(p: Pool): (Pool, List[Int]) =
    val ci = classIndex(p, "[Ljava/lang/Object;")
    (ci._1, u1(0xc0) ++ u2(ci._2))

  private def binOpcode(op: BinOp): Int = op match
    case BinOp.Add  => 0x61 // ladd
    case BinOp.Sub  => 0x65 // lsub
    case BinOp.Mul  => 0x69 // lmul
    case BinOp.Div  => 0x6d // ldiv
    case BinOp.Rem  => 0x71 // lrem
    case BinOp.BAnd => 0x7f // land
    case BinOp.BOr  => 0x81 // lor
    case BinOp.BXor => 0x83 // lxor
    case _          => throw Unsupported("the binary operator " + op.toString + " at stage 2 (a comparison produces a Bool, which arrives with `If` at stage 3)")

  /** `lshl`/`lshr`/`lushr` take an INT shift amount, not a long — the one place the uniform
    * "a number is a long" mapping does not line up with the instruction set, and getting it
    * wrong is a `VerifyError` rather than a wrong answer. */
  private def shiftOpcode(op: BinOp): Int = op match
    case BinOp.Shl  => 0x79
    case BinOp.Shr  => 0x7b
    case BinOp.UShr => 0x7d
    case _          => throw Unsupported("the shift operator " + op.toString)

  private def isShift(op: BinOp): Boolean =
    op == BinOp.Shl || op == BinOp.Shr || op == BinOp.UShr

  /** Push a constant-pool `long` with `ldc2_w`, unboxed, on the stack.
    *
    * A `long` entry occupies TWO pool slots — the format says the index after it is unusable — so a
    * zero-byte filler follows it and every later index stays correct. THE FILLER IS ADDED ONLY WHEN
    * THE LONG WAS ADDED, and getting that wrong is how stage 1 shipped a latent defect: `add`
    * interns, so pushing the same literal twice returned the existing index and appended a SECOND
    * filler anyway. `constant_pool_count` was then one too high, the reader ran past the pool into
    * `access_flags`, and the JVM refused the class with `Unknown constant tag 0` — a class-format
    * error rather than a wrong answer, but only visible in a module that mentions one number twice.
    * Stage 1's two fixtures happened not to; `arr.ssir` does, which is how this surfaced. */
  private def pushLong(p: Pool, v: Long): (Pool, List[Int]) =
    val key = "l:" + v.toString
    val existing = find(p, key)
    if existing != 0 then (p, u1(0x14) ++ u2(existing))
    else
      val added = add(p, key, u1(5) ++ u4(((v >>> 32) & 0xffffffffL).toInt) ++ u4((v & 0xffffffffL).toInt))
      val filled = Pool(("pad:" + v.toString, Nil) :: added._1.entries, added._1.count + 1)
      (filled, u1(0x14) ++ u2(added._2))

  /** Push a boxed `Long` — the register representation of a number. */
  private def pushBoxedLong(p: Pool, v: Long): (Pool, List[Int]) =
    val raw = pushLong(p, v)
    val box = boxLong(raw._1)
    (box._1, raw._2 ++ box._2)

  private def globalField(p: Pool, className: String, g: Int): (Pool, Int) =
    memberIndex(p, 9, className, "g" + g.toString, "Ljava/lang/Object;")

  private def emitInstr(m: Module, className: String, p: Pool, i: Instr): (Pool, List[Int]) = i match
    case Instr.Const(dst, k) =>
      if k < 0 || k >= m.consts.length then throw Unsupported("a constant index outside the pool")
      m.consts(k) match
        case Lit.LInt(n) =>
          val pushed = pushBoxedLong(p, n)
          (pushed._1, pushed._2 ++ astoreR(dst))
        case Lit.LStr(s) =>
          // A `java.lang.String` IS a register value here. Nothing at stage 2 can consume one —
          // that is `Prim` — but a string reaches a record field, and refusing it would refuse
          // every record that carries a name.
          val u = utf8Index(p, s)
          val sc = add(u._1, "s:" + s, u1(8) ++ u2(u._2))
          if sc._2 > 255 then (sc._1, u1(0x13) ++ u2(sc._2) ++ astoreR(dst)) // ldc_w
          else (sc._1, u1(0x12) ++ u1(sc._2) ++ astoreR(dst))                // ldc
        // `VUnit` is `null`, which is also what the JVM leaves in an unwritten static field, so the
        // two lanes agree about an unwritten global without a `<clinit>` to say so.
        case Lit.LUnit => (p, u1(0x01) ++ astoreR(dst))
        case other     => throw Unsupported("the literal " + other.toString + " at stage 2 (Int, Str and Unit only)")

    case Instr.Move(dst, a) => (p, aloadR(a) ++ astoreR(dst))

    case Instr.Bin(op, kind, dst, a, b) =>
      if kind != NumKind.I64 then
        throw Unsupported("arithmetic of kind " + kind.toString + " at stage 2 (I64 only)")
      val la = unboxLong(p, a)
      val lb = unboxLong(la._1, b)
      // long << int: the right operand is narrowed with `l2i`, which is what javac emits too.
      val opBytes = if isShift(op) then u1(0x88) ++ u1(shiftOpcode(op)) else u1(binOpcode(op))
      val box = boxLong(lb._1)
      (box._1, la._2 ++ lb._2 ++ opBytes ++ box._2 ++ astoreR(dst))

    case Instr.Un(op, kind, dst, a) =>
      if kind != NumKind.I64 then
        throw Unsupported("arithmetic of kind " + kind.toString + " at stage 2 (I64 only)")
      val la = unboxLong(p, a)
      op match
        case UnOp.Neg =>
          val box = boxLong(la._1)
          (box._1, la._2 ++ u1(0x75) ++ box._2 ++ astoreR(dst)) // lneg
        case UnOp.BNot =>
          // No `lnot` exists; XOR with -1 is the encoding javac uses.
          val minusOne = pushLong(la._1, -1L)
          val box = boxLong(minusOne._1)
          (box._1, la._2 ++ minusOne._2 ++ u1(0x83) ++ box._2 ++ astoreR(dst))
        case UnOp.Not => throw Unsupported("logical `not` at stage 2 (it produces a Bool, which arrives with `If` at stage 3)")

    // ── data ──────────────────────────────────────────────────────────────────
    //
    // A RECORD IS AN `Object[]` OF `fields + 1`, tag at 0, field `i` at `i + 1`. An ARRAY is a
    // `java.util.ArrayList`. They are different JVM types ON PURPOSE: `Verify` checks index bounds
    // and does not type registers, so nothing upstream stops a `Field` whose receiver is an array.
    // The executor throws there; with one shared representation this backend would have read
    // element `idx+1` and answered a plausible wrong value. Two types make it a ClassCastException.
    case Instr.MkData(dst, t, args) =>
      if t < 0 || t >= m.types.length then throw Unsupported("a type index outside the table")
      val objC = classIndex(p, "java/lang/Object")
      var pool = objC._1
      var out = pushInt(args.length + 1) ++ u1(0xbd) ++ u2(objC._2) // anewarray java/lang/Object
      val tag = pushBoxedLong(pool, t.toLong)
      pool = tag._1
      out = out ++ u1(0x59) ++ pushInt(0) ++ tag._2 ++ u1(0x53) // dup; 0; tag; aastore
      var rest = args
      var ix = 1
      while rest.nonEmpty do
        out = out ++ u1(0x59) ++ pushInt(ix) ++ aloadR(rest.head) ++ u1(0x53)
        ix = ix + 1
        rest = rest.tail
      (pool, out ++ astoreR(dst))

    case Instr.Field(dst, a, t, idx) =>
      if t < 0 || t >= m.types.length then throw Unsupported("a type index outside the table")
      val cc = checkcastRecord(p)
      (cc._1, aloadR(a) ++ cc._2 ++ pushInt(idx + 1) ++ u1(0x32) ++ astoreR(dst)) // aaload

    // ── mutable storage ───────────────────────────────────────────────────────
    case Instr.NewArr(dst, len) =>
      // `Exec` fills a new array with `VInt(0)`, so this must too — and it must do it WITHOUT a
      // loop, because a loop is a branch target and the frame computer is stage 3.
      // `new ArrayList(Collections.nCopies(n, 0L))` is that fill in two calls.
      val alC = classIndex(p, "java/util/ArrayList")
      val n = loadInt(alC._1, len)
      val zero = pushBoxedLong(n._1, 0L)
      val nc = memberIndex(zero._1, 10, "java/util/Collections", "nCopies", "(ILjava/lang/Object;)Ljava/util/List;")
      val ctor = memberIndex(nc._1, 10, "java/util/ArrayList", "<init>", "(Ljava/util/Collection;)V")
      (ctor._1,
       u1(0xbb) ++ u2(alC._2) ++ u1(0x59) ++ n._2 ++ zero._2 ++ u1(0xb8) ++ u2(nc._2) ++
         u1(0xb7) ++ u2(ctor._2) ++ astoreR(dst))

    case Instr.ArrGet(dst, arr, idx) =>
      val cc = checkcastArrayList(p)
      val ix = loadInt(cc._1, idx)
      val get = memberIndex(ix._1, 10, "java/util/ArrayList", "get", "(I)Ljava/lang/Object;")
      (get._1, aloadR(arr) ++ cc._2 ++ ix._2 ++ u1(0xb6) ++ u2(get._2) ++ astoreR(dst))

    case Instr.ArrSet(arr, idx, v) =>
      val cc = checkcastArrayList(p)
      val ix = loadInt(cc._1, idx)
      val set = memberIndex(ix._1, 10, "java/util/ArrayList", "set", "(ILjava/lang/Object;)Ljava/lang/Object;")
      // `set` answers the previous element; the IR has nowhere to put it, so it is popped.
      (set._1, aloadR(arr) ++ cc._2 ++ ix._2 ++ aloadR(v) ++ u1(0xb6) ++ u2(set._2) ++ u1(0x57))

    case Instr.ArrLen(dst, arr) =>
      val cc = checkcastArrayList(p)
      val size = memberIndex(cc._1, 10, "java/util/ArrayList", "size", "()I")
      val box = boxLong(size._1)
      (box._1, aloadR(arr) ++ cc._2 ++ u1(0xb6) ++ u2(size._2) ++ u1(0x85) ++ box._2 ++ astoreR(dst)) // i2l

    case Instr.GlobGet(dst, g) =>
      if g < 0 || g >= m.globals.length then throw Unsupported("a global index outside the table")
      val fi = globalField(p, className, g)
      (fi._1, u1(0xb2) ++ u2(fi._2) ++ astoreR(dst)) // getstatic

    case Instr.GlobSet(g, a) =>
      if g < 0 || g >= m.globals.length then throw Unsupported("a global index outside the table")
      val fi = globalField(p, className, g)
      (fi._1, aloadR(a) ++ u1(0xb3) ++ u2(fi._2)) // putstatic

    case Instr.Ret(a) => (p, aloadR(a) ++ u1(0xb0)) // areturn

    // `Tag` is NOT here, and it is not an oversight. `Exec` makes it TOTAL — `-1` on a non-record,
    // because a nested pattern tests the tag of a field and a field is routinely not a record — and
    // emitting that needs an `instanceof` and a branch, which is the first branch target and so the
    // frame computer that stage 3 exists for. It also has no consumer before `Switch`.
    case other => throw Unsupported(other.getClass.getSimpleName)

  private def emitBody(m: Module, className: String, p: Pool, body: List[Instr]): (Pool, List[Int]) =
    var pool = p
    var out: List[Int] = Nil
    var rest = body
    while rest.nonEmpty do
      val step = emitInstr(m, className, pool, rest.head)
      pool = step._1
      out = out ++ step._2
      rest = rest.tail
    (pool, out)

  /** The class file for `m`'s ENTRY function, as `<name>.class` bytes.
    *
    * Two methods: `entry()Ljava/lang/Object;`, the compiled function, and
    * `main([Ljava/lang/String;)V`, which prints what it returned. The `main` is the STAGE 1/2
    * HARNESS, not the eventual shape — printing belongs to `Prim`, which is stage 6 and the
    * owner's v3-owned host layer (spec §7 Q3). It exists so the stage has something observable to
    * gate on, and it is spelled out here so nobody reads it as the design.
    *
    * IT PRINTS ONLY A NUMBER, by `checkcast java/lang/Long` before `longValue`. An entry that
    * answers a record or an array fails LOUDLY there instead of printing
    * `[Ljava.lang.Object;@1b6d3586` as though that were the answer. Rendering a value properly is
    * `show`: string building and loops, which is stage 6's work, not a harness's. */
  def classFile(m: Module, className: String): Array[Byte] =
    if m.entry < 0 || m.entry >= m.funcs.length then throw Unsupported("a module with no entry function")
    val f = m.funcs(m.entry)
    if f.nparams != 0 then throw Unsupported("an entry function that takes parameters at stage 2")
    if slotOf(f.nregs) > 255 then throw Unsupported("a frame needing more than 255 JVM local slots (no `wide` yet)")

    var p = emptyPool
    val thisC = classIndex(p, className);                 p = thisC._1
    val objC  = classIndex(p, "java/lang/Object");        p = objC._1
    val codeU = utf8Index(p, "Code");                     p = codeU._1
    val entryN = utf8Index(p, "entry");                   p = entryN._1
    val entryD = utf8Index(p, "()Ljava/lang/Object;");    p = entryD._1
    val mainN = utf8Index(p, "main");                     p = mainN._1
    val mainD = utf8Index(p, "([Ljava/lang/String;)V");   p = mainD._1
    val objD  = utf8Index(p, "Ljava/lang/Object;");       p = objD._1
    val longC = classIndex(p, "java/lang/Long");          p = longC._1
    val lvM   = memberIndex(p, 10, "java/lang/Long", "longValue", "()J");              p = lvM._1
    val outF  = memberIndex(p, 9, "java/lang/System", "out", "Ljava/io/PrintStream;"); p = outF._1
    val prnM  = memberIndex(p, 10, "java/io/PrintStream", "println", "(J)V");          p = prnM._1
    val entM  = memberIndex(p, 10, className, "entry", "()Ljava/lang/Object;");        p = entM._1

    // A module global is a static field of this class. The JVM zero-initialises it to `null`, which
    // is stage 2's `VUnit`, so the lanes agree on an unwritten global with no `<clinit>`.
    var globalNames: List[Int] = Nil
    var gi = 0
    while gi < m.globals.length do
      val gu = utf8Index(p, "g" + gi.toString)
      p = gu._1
      globalNames = gu._2 :: globalNames
      gi = gi + 1
    globalNames = globalNames.reverse

    val emitted = emitBody(m, className, p, f.body)
    p = emitted._1
    val code = emitted._2
    if code.isEmpty then throw Unsupported("an entry function with an empty body")

    // max_stack 6: the deepest shape is `MkData`'s tag store — array, array, index, long(2) — plus
    // slack. It is a stated OVER-estimate: the verifier rejects a frame that is too small and
    // accepts one that is larger, so a constant here is safe where a wrong computed number is not.
    val entryCode = u2(6) ++ u2(slotOf(f.nregs)) ++ u4(code.length) ++ code ++ u2(0) ++ u2(0)
    val entryM = u2(0x0009) ++ u2(entryN._2) ++ u2(entryD._2) ++ u2(1) ++
                 u2(codeU._2) ++ u4(entryCode.length) ++ entryCode

    // getstatic out; invokestatic entry; checkcast Long; longValue; println(J)V; return
    val mainBody = u1(0xb2) ++ u2(outF._2) ++ u1(0xb8) ++ u2(entM._2) ++
                   u1(0xc0) ++ u2(longC._2) ++ u1(0xb6) ++ u2(lvM._2) ++
                   u1(0xb6) ++ u2(prnM._2) ++ u1(0xb1)
    val mainCode = u2(3) ++ u2(1) ++ u4(mainBody.length) ++ mainBody ++ u2(0) ++ u2(0)
    val mainM = u2(0x0009) ++ u2(mainN._2) ++ u2(mainD._2) ++ u2(1) ++
                u2(codeU._2) ++ u4(mainCode.length) ++ mainCode

    val fields = globalNames.flatMap(n => u2(0x000a) ++ u2(n) ++ u2(objD._2) ++ u2(0))

    // NO StackMapTable, and it is not an omission — neither method branches, so neither has a
    // branch target, so the verifier needs no frames. `v3/tests/jvm-backend-probe/run.sh` is the
    // measurement; §8 stage 3 is when this stops being true.
    val header = u4(0xcafebabe) ++ u2(0) ++ u2(52) ++ u2(p.count + 1) ++ p.entries.reverse.flatMap(e => e._2)
    val rest = u2(0x0031) ++ u2(thisC._2) ++ u2(objC._2) ++ u2(0) ++
               u2(globalNames.length) ++ fields ++ u2(2) ++ entryM ++ mainM ++ u2(0)

    val all = header ++ rest
    val bytes = new Array[Byte](all.length)
    var i = 0
    var walk = all
    while walk.nonEmpty do
      bytes(i) = walk.head.toByte
      i = i + 1
      walk = walk.tail
    bytes
