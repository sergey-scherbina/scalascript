package ssc.backend.rust

import ssc.{Term, Const, Arm, Def, HandlerDispatchShape, Program, Reader}
import Term.*
import Const.*

// Core IR → self-contained Rust source generator.
// Usage: echo "<core-ir-s-expr>" | scala-cli run v2/backend/rust/ -- /dev/stdin
// or pipe to stdin (no arg = stdin).
object RustBackend:

  def main(args: Array[String]): Unit =
    val src =
      if args.nonEmpty && args(0) != "/dev/stdin" then
        scala.io.Source.fromFile(args(0)).mkString
      else
        scala.io.Source.stdin.mkString
    val prog = Reader.parseProgram(src)
    print(generate(prog))

  // ── top-level generate ────────────────────────────────────────────────────

  // Rust names of global Lam defs — used in genLam/genLetRec to choose
  // cell-based vs direct access for forward-ref safety.
  private var topLamDefNames: Set[String] = Set.empty
  private var topDefs: Map[String, Term] = Map.empty
  private var longGlobalDefs: Map[String, Int] = Map.empty
  private var floatGlobalDefs: Map[String, Int] = Map.empty
  private var floatFnDefs: Set[String] = Set.empty

  def generate(p: Program): String =
    _cnt = 0
    topDefs = p.defs.map(d => d.name -> d.body).toMap
    longGlobalDefs = Map.empty
    floatGlobalDefs = Map.empty
    floatFnDefs = Set.empty
    val sb = new StringBuilder
    sb ++= RUNTIME_HEADER
    sb ++= "\n"

    // Classify defs: Lam bodies get forward-ref cells for mutual/self recursion.
    topLamDefNames = p.defs.collect {
      case Def(n, Lam(_, _)) => rustDefName(n)
    }.toSet

    val lamDefs = p.defs.collect { case d @ Def(_, Lam(_, _)) => d }
    val globalLambdaArities: Map[String, Int] = lamDefs.collect {
      case Def(n, Lam(arity, _)) => n -> arity
    }.toMap

    def inferLongGlobalDefs(): Map[String, Int] =
      var current = globalLambdaArities
      var changed = true
      while changed do
        longGlobalDefs = current
        val next = lamDefs.flatMap {
          case Def(n, Lam(arity, body)) =>
            val params = (0 until arity).map(k => s"p${k}_long").toList
            if isLongTyped(body, params, params.toSet) then Some(n -> arity) else None
          case _ => None
        }.toMap
        changed = next != current
        current = next
      current

    longGlobalDefs = inferLongGlobalDefs()

    def inferFloatGlobalDefs(): Map[String, Int] =
      var current = globalLambdaArities
      var changed = true
      while changed do
        floatGlobalDefs = current
        val next = lamDefs.flatMap {
          case Def(n, Lam(arity, body)) =>
            val params = (0 until arity).map(k => s"p${k}_float").toList
            if isFloatTyped(body, params, Set.empty, Set.empty, Set.empty) then Some(n -> arity) else None
          case _ => None
        }.toMap
        changed = next != current
        current = next
      current

    floatGlobalDefs = inferFloatGlobalDefs()
    val lamDefNames = lamDefs.map(_.name).toSet
    floatFnDefs = lamDefs.collect {
      case Def(n, Lam(_, body))
          if floatGlobalDefs.contains(n) &&
             freeGlobals(body).forall(g => lamDefNames(g) || !p.defs.exists(_.name == g)) =>
        n
    }.toSet

    sb ++= "fn ssc_run() {\n"

    val generatedRawNames = p.defs.map(_.name).toSet
    val allRefs = p.defs.foldLeft(freeGlobals(p.entry)) { (acc, d) => acc | freeGlobals(d.body) }
    if allRefs("print") && !generatedRawNames("print") then
      sb ++= "    let g_print: V = V::Fn(Rc::new(move |args: Vec<V>| { for a in args { v_print(a); } V::Unit }));\n"
    if allRefs("println") && !generatedRawNames("println") then
      sb ++= "    let g_println: V = V::Fn(Rc::new(move |args: Vec<V>| { for a in args { v_print(a); } println!(); V::Unit }));\n"

    for d <- lamDefs do
      d.body match
        case Lam(arity, body) if longGlobalDefs.get(d.name).contains(arity) =>
          val n = rustDefName(d.name)
          val params = (0 until arity).map(k => s"p${k}_${n}_long")
          val paramDecls = params.map(p => s"$p: i64").mkString(", ")
          val bodyExpr = genIntExpr(body, params.toList, 2, params.toSet)
          sb ++= s"    fn ${n}_long($paramDecls) -> i64 {\n"
          sb ++= s"        $bodyExpr\n"
          sb ++= s"    }\n"
        case _ => ()

    for d <- lamDefs do
      d.body match
        case Lam(arity, body) if floatGlobalDefs.get(d.name).contains(arity) && floatFnDefs(d.name) =>
          val n = rustDefName(d.name)
          val params = (0 until arity).map(k => s"p${k}_${n}_float")
          val paramDecls = params.map(p => s"$p: V").mkString(", ")
          val bodyExpr = genFloatExpr(body, params.toList, 2, Set.empty, Set.empty, Set.empty)
          sb ++= s"    fn ${n}_float($paramDecls) -> f64 {\n"
          sb ++= s"        $bodyExpr\n"
          sb ++= s"    }\n"
        case _ => ()

    // Phase 1: forward-ref cells for all Lam defs (declared before any closure)
    for d <- p.defs do d.body match
      case Lam(_, _) =>
        val n = rustDefName(d.name)
        sb ++= s"    let ${n}__fwd: Rc<RefCell<Option<V>>> = Rc::new(RefCell::new(None));\n"
      case _ => ()

    // Phase 2: emit defs in program order
    for d <- p.defs do
      val name = rustDefName(d.name)
      d.body match
        case Lam(_, _) =>
          if floatGlobalDefs.contains(d.name) && !floatFnDefs(d.name) then
            val Lam(arity, body) = d.body: @unchecked
            val expr = genFloatHelperClosure(d.name, arity, body, 1)
            sb ++= s"    let ${name}_float = $expr;\n"
          val expr = genExpr(d.body, Nil, 0)
          sb ++= s"    let $name: V = $expr;\n"
          sb ++= s"    *${name}__fwd.borrow_mut() = Some($name.clone());\n"
        case _ =>
          val expr = genExpr(d.body, Nil, 0)
          sb ++= s"    let $name: V = $expr;\n"

    // Entry expression — VM semantics (Main.out): print non-Unit result, strings quoted
    sb ++= s"    let _result: V = ${genExpr(p.entry, Nil, 0)};\n"
    sb ++= "    match _result { V::Unit => {}, ref v => println!(\"{}\", show_entry(v)) }\n"
    sb ++= "}\n"
    // Thin main: spawn ssc_run in a thread with a large stack for deep recursion.
    // wasm32-wasip1 has no OS thread support (std::thread::Builder::spawn fails
    // at runtime with "operation not supported on this platform" — confirmed
    // under Node's node:wasi host, v2/specs/63-backend-wasm.md) so that target
    // calls ssc_run directly on the main thread instead; #[cfg] makes this a
    // compile-time choice, so the native path (and its generated bytes) is
    // completely unchanged.
    sb ++= "#[cfg(not(target_arch = \"wasm32\"))]\n"
    sb ++= "fn main() {\n"
    sb ++= "    std::thread::Builder::new()\n"
    sb ++= "        .stack_size(2048 * 1024 * 1024)\n"  // 2GB virtual reservation: tco.coreir (1M non-TCO frames) needs >256MB; real trampoline TCO is queued (v2-rust-backend-tco)
    sb ++= "        .spawn(ssc_run)\n"
    sb ++= "        .unwrap()\n"
    sb ++= "        .join()\n"
    sb ++= "        .unwrap();\n"
    sb ++= "}\n"
    sb ++= "#[cfg(target_arch = \"wasm32\")]\n"
    sb ++= "fn main() { ssc_run() }\n"
    sb.toString

  // ── free global analysis ─────────────────────────────────────────────────
  // Returns names of all Global() references in a term (at any nesting depth).
  // Used to pre-clone globals before nested move closures.
  def freeGlobals(t: Term): Set[String] = t match
    case Global(n) => Set(n)
    case Local(_) | Lit(_) => Set.empty
    case Lam(_, body) => freeGlobals(body)
    case App(fn, args) => (fn :: args).map(freeGlobals).fold(Set.empty)(_ | _)
    case Let(rhs, body) => (rhs :+ body).map(freeGlobals).fold(Set.empty)(_ | _)
    case LetRec(lams, body) => (lams :+ body).map(freeGlobals).fold(Set.empty)(_ | _)
    case If(c, th, el) => freeGlobals(c) | freeGlobals(th) | freeGlobals(el)
    case Ctor(_, fields) => fields.map(freeGlobals).fold(Set.empty)(_ | _)
    case Match(s, arms, d) =>
      freeGlobals(s) | arms.map(a => freeGlobals(a.body)).fold(Set.empty)(_ | _) |
        d.map(freeGlobals).getOrElse(Set.empty)
    case Prim(_, args) => args.map(freeGlobals).fold(Set.empty)(_ | _)
    case While(c, b) => freeGlobals(c) | freeGlobals(b)
    case Seq(ts) => ts.map(freeGlobals).fold(Set.empty)(_ | _)

  // ── local-capture analysis ────────────────────────────────────────────────

  // Is Local(idx) referenced ANYWHERE in t (with binding-shift accounting)?
  def containsLocal(t: Term, idx: Int): Boolean = t match
    case Local(i)       => i == idx
    case Global(_) | Lit(_) => false
    case Lam(arity, body) => containsLocal(body, idx + arity)
    case App(fn, args)  => containsLocal(fn, idx) || args.exists(containsLocal(_, idx))
    case Let(rhs, body) =>
      rhs.zipWithIndex.exists((r, k) => containsLocal(r, idx + k)) ||
      containsLocal(body, idx + rhs.length)
    case LetRec(lams, body) =>
      val n = lams.length
      lams.exists(containsLocal(_, idx + n)) || containsLocal(body, idx + n)
    case If(c, th, el)  => containsLocal(c, idx) || containsLocal(th, idx) || containsLocal(el, idx)
    case Ctor(_, fs)    => fs.exists(containsLocal(_, idx))
    case Match(s, arms, d) =>
      containsLocal(s, idx) || arms.exists(a => containsLocal(a.body, idx + a.arity)) ||
      d.exists(containsLocal(_, idx))
    case Prim(_, args)  => args.exists(containsLocal(_, idx))
    case While(c, b)    => containsLocal(c, idx) || containsLocal(b, idx)
    case Seq(ts)        => ts.exists(containsLocal(_, idx))

  // Is Local(idx) referenced inside any Lam body in t?
  // If true, an LCell at this index is captured by a closure and cannot be let-mut.
  def isLocalInLam(t: Term, idx: Int): Boolean = t match
    case Local(_) | Global(_) | Lit(_) => false
    case Lam(arity, body) => containsLocal(body, idx + arity)
    case App(fn, args)  => isLocalInLam(fn, idx) || args.exists(isLocalInLam(_, idx))
    case Let(rhs, body) =>
      rhs.zipWithIndex.exists((r, k) => isLocalInLam(r, idx + k)) ||
      isLocalInLam(body, idx + rhs.length)
    case LetRec(lams, body) =>
      val n = lams.length
      lams.exists(isLocalInLam(_, idx + n)) || isLocalInLam(body, idx + n)
    case If(c, th, el)  => isLocalInLam(c, idx) || isLocalInLam(th, idx) || isLocalInLam(el, idx)
    case Ctor(_, fs)    => fs.exists(isLocalInLam(_, idx))
    case Match(s, arms, d) =>
      isLocalInLam(s, idx) || arms.exists(a => isLocalInLam(a.body, idx + a.arity)) ||
      d.exists(isLocalInLam(_, idx))
    case Prim(_, args)  => args.exists(isLocalInLam(_, idx))
    case While(c, b)    => isLocalInLam(c, idx) || isLocalInLam(b, idx)
    case Seq(ts)        => ts.exists(isLocalInLam(_, idx))

  // ── name helpers ─────────────────────────────────────────────────────────

  // Global def names: prefix with g_ to avoid collisions with keywords
  def rustDefName(n: String): String =
    "g_" + n.replace('.', '_').replace('-', '_').replace('>', '_').replace('<', '_')
      .replace('/', '_').replace('~', '_').replace('!', '_').replace('?', '_')
      .replace('@', '_').replace('*', '_').replace('+', '_').replace('%', '_')
      .replace('=', '_').replace('&', '_').replace('^', '_').replace('|', '_')

  // fresh variable counter (thread-local is fine for single-threaded generation)
  private var _cnt = 0
  def fresh(prefix: String = "v"): String = { _cnt += 1; s"_${prefix}${_cnt}" }

  // Resolve Local(i) from context.
  // ctx is ordered so ctx.last = Local(0), ctx(ctx.length-2) = Local(1), ...
  def localName(ctx: List[String], i: Int): String =
    val idx = ctx.length - 1 - i
    if idx < 0 || idx >= ctx.length then s"/*LOCAL_OOB($i of ${ctx.length})*/ V::Unit"
    else ctx(idx)

  // ── expression generation ─────────────────────────────────────────────────
  // indent: number of 4-space levels for block content

  // longVars: names that are `let mut name: i64` rather than `let name: V`
  def genExpr(t: Term, ctx: List[String], indent: Int, longVars: Set[String] = Set.empty): String =
    val pad = "    " * indent
    t match

      // ── Literals ─────────────────────────────────────────────────────────
      case Lit(CUnit)    => "V::Unit"
      case Lit(CBool(b)) => s"V::Bool($b)"
      case Lit(CInt(n))  => s"V::Int(${n}i64)"
      case Lit(CBig(n))  =>
        // represent BigInt as i64 (lossy but handles common cases)
        s"V::Int(${n.toLong}i64) /*big*/"
      case Lit(CFloat(d)) =>
        s"V::Float(${rustFloatLit(d)})"
      case Lit(CStr(s))  => s"V::Str(${rustStrLit(s)}.to_string())"
      case Lit(CBytes(b)) =>
        val hex = b.map(x => f"${x & 0xff}%02x").mkString
        if b.isEmpty then "V::Bytes(vec![])"
        else s"V::Bytes(hex_bytes(\"$hex\"))"

      // ── Variable references ───────────────────────────────────────────────
      case Local(i) =>
        val nm = localName(ctx, i)
        if longVars.contains(nm) then s"V::Int($nm)" else s"$nm.clone()"
      case Global(n) => s"${rustDefName(n)}.clone()"

      // ── Lambda ───────────────────────────────────────────────────────────
      // Generates a V::Fn closure capturing the current context
      case Lam(arity, body) =>
        genLam(arity, body, ctx, indent, longVars)

      // ── Application ──────────────────────────────────────────────────────
      case App(fn, args) =>
        fn match
          case Global(name)
              if longGlobalDefs.get(name).contains(args.length) &&
                 args.forall(isLongTyped(_, ctx, longVars)) =>
            val argExprs = args.map(a => genIntExpr(a, ctx, indent, longVars)).mkString(", ")
            s"V::Int(${rustDefName(name)}_long($argExprs))"
          case Global(name) if floatGlobalDefs.get(name).contains(args.length) =>
            val argExprs = args.map(a => genExpr(a, ctx, indent, longVars)).mkString(", ")
            s"V::Float(${rustDefName(name)}_float($argExprs))"
          case _ =>
            val fnExpr = genExpr(fn, ctx, indent, longVars)
            if args.isEmpty then
              s"call_fn($fnExpr, vec![])"
            else
              val argExprs = args.map(a => genExpr(a, ctx, indent, longVars)).mkString(", ")
              s"call_fn($fnExpr, vec![$argExprs])"

      // ── Let ──────────────────────────────────────────────────────────────
      case Let(rhs, body) =>
        val names = rhs.map(_ => fresh("l"))
        val bodyCtx = ctx ++ names   // Local(0) = names.last
        val sb = new StringBuilder(s"{\n")
        var curCtx = ctx
        var curLongs = longVars
        for ((r, n), k) <- rhs.zip(names).zipWithIndex do
          // de Bruijn index of this binding inside `body`
          val bodyIdx = rhs.length - 1 - k
          r match
            case Prim("lcell.new", List(Lit(CInt(v)))) if !isLocalInLam(body, bodyIdx) =>
              // Not captured by any closure: emit a plain mutable i64
              sb ++= s"${pad}    let mut $n: i64 = ${v}i64;\n"
              curLongs = curLongs + n
            case _ =>
              val rExpr = genExpr(r, curCtx, indent + 1, curLongs)
              sb ++= s"${pad}    let $n: V = $rExpr;\n"
          curCtx = curCtx :+ n
        sb ++= s"${pad}    ${genExpr(body, bodyCtx, indent + 1, curLongs)}\n"
        sb ++= s"${pad}}"
        sb.toString

      // ── LetRec ───────────────────────────────────────────────────────────
      case LetRec(lams, body) =>
        genLetRec(lams, body, ctx, indent, longVars)

      // ── If ───────────────────────────────────────────────────────────────
      case If(c, th, el) =>
        val cExpr = genExpr(c, ctx, indent, longVars)
        val tExpr = genExpr(th, ctx, indent + 1, longVars)
        val eExpr = genExpr(el, ctx, indent + 1, longVars)
        s"if as_bool($cExpr) {\n${pad}    $tExpr\n${pad}} else {\n${pad}    $eExpr\n${pad}}"

      // ── Constructor ──────────────────────────────────────────────────────
      case Ctor(tag, fields) =>
        val tagStr = rustStrLit(tag)
        if fields.isEmpty then
          s"V::Data($tagStr.to_string(), vec![])"
        else
          val fieldExprs = fields.map(f => genExpr(f, ctx, indent, longVars)).mkString(", ")
          s"V::Data($tagStr.to_string(), vec![$fieldExprs])"

      // ── Match ─────────────────────────────────────────────────────────────
      case Match(scrut, arms, default) =>
        genMatch(scrut, arms, default, ctx, indent, longVars)

      // ── Primitives ────────────────────────────────────────────────────────
      case Prim(op, args) =>
        genPrim(op, args, ctx, indent, longVars)

      // ── While ─────────────────────────────────────────────────────────────
      case While(cond, body) =>
        val condStr = genBoolExpr(cond, ctx, indent, longVars)
        // Use genStmt for the body to avoid V::Unit overhead in each iteration
        val bodyStmts = genStmt(body, ctx, indent + 2, longVars)
        s"{\n${pad}    while $condStr {\n$bodyStmts\n${pad}    }\n${pad}    V::Unit\n${pad}}"

      // ── Seq ──────────────────────────────────────────────────────────────
      case Seq(terms) =>
        if terms.isEmpty then "V::Unit"
        else if terms.length == 1 then genExpr(terms.head, ctx, indent, longVars)
        else
          val sb = new StringBuilder("{\n")
          for t <- terms.init do
            // Use genStmt so intermediates don't create V::Unit (avoids boxing in hot loops)
            sb ++= genStmt(t, ctx, indent + 1, longVars) + "\n"
          sb ++= s"${pad}    ${genExpr(terms.last, ctx, indent + 1, longVars)}\n"
          sb ++= s"${pad}}"
          sb.toString

  // ── Inline arithmetic/bool helpers (avoids boxing longVars) ──────────────

  // Try to emit a raw i64 expression without wrapping in V::Int.
  // Falls back to as_int(genExpr(...)) for non-inline cases.
  def genIntExpr(t: Term, ctx: List[String], indent: Int, longVars: Set[String]): String = t match
    case Lit(CInt(n))  => s"${n}i64"
    case Local(i) =>
      val nm = localName(ctx, i)
      if longVars.contains(nm) then nm else s"as_int(${nm}.clone())"
    case Prim("lcell.get", List(Local(i))) =>
      val nm = localName(ctx, i)
      if longVars.contains(nm) then nm
      else s"as_int(V::Int(*as_lcell(${nm}.clone()).borrow()))"
    case Prim("i.add", List(a, b)) => s"(${genIntExpr(a,ctx,indent,longVars)}).wrapping_add(${genIntExpr(b,ctx,indent,longVars)})"
    case Prim("i.sub", List(a, b)) => s"(${genIntExpr(a,ctx,indent,longVars)}).wrapping_sub(${genIntExpr(b,ctx,indent,longVars)})"
    case Prim("i.mul", List(a, b)) => s"(${genIntExpr(a,ctx,indent,longVars)}).wrapping_mul(${genIntExpr(b,ctx,indent,longVars)})"
    case App(Global(name), args)
         if longGlobalDefs.get(name).contains(args.length) &&
            args.forall(isLongTyped(_, ctx, longVars)) =>
      val argExprs = args.map(a => genIntExpr(a, ctx, indent, longVars)).mkString(", ")
      s"${rustDefName(name)}_long($argExprs)"
    case If(c, th, el)
         if isBoolTyped(c, ctx, longVars) &&
            isLongTyped(th, ctx, longVars) &&
            isLongTyped(el, ctx, longVars) =>
      val pad = "    " * indent
      val cExpr = genBoolExpr(c, ctx, indent, longVars)
      val tExpr = genIntExpr(th, ctx, indent + 1, longVars)
      val eExpr = genIntExpr(el, ctx, indent + 1, longVars)
      s"if $cExpr {\n${pad}    $tExpr\n${pad}} else {\n${pad}    $eExpr\n${pad}}"
    case Prim("__arith__", List(Lit(CStr(op)), a, b))
         if longArithOps.contains(op) &&
            isLongTyped(a, ctx, longVars) &&
            isLongTyped(b, ctx, longVars) =>
      val l = genIntExpr(a, ctx, indent, longVars)
      val r = genIntExpr(b, ctx, indent, longVars)
      op match
        case "+"   => s"($l).wrapping_add($r)"
        case "-"   => s"($l).wrapping_sub($r)"
        case "*"   => s"($l).wrapping_mul($r)"
        case "/"   => s"($l) / ($r)"
        case "%"   => s"($l) % ($r)"
        case "&"   => s"($l) & ($r)"
        case "|"   => s"($l) | ($r)"
        case "^"   => s"($l) ^ ($r)"
        case "<<"  => s"($l) << ($r)"
        case ">>"  => s"($l) >> ($r)"
        case ">>>" => s"(($l as u64) >> ($r as u64)) as i64"
        case _     => s"as_int(${genExpr(t, ctx, indent, longVars)})"
    case Prim("__method__", List(Lit(CStr(method)), recv))
         if (method == "toLong" || method == "toInt") && isLongTyped(recv, ctx, longVars) =>
      genIntExpr(recv, ctx, indent, longVars)
    case _ => s"as_int(${genExpr(t, ctx, indent, longVars)})"

  // Try to emit a raw bool expression without boxing.
  def genBoolExpr(t: Term, ctx: List[String], indent: Int, longVars: Set[String]): String = t match
    case Prim("i.lt", List(a, b)) => s"(${genIntExpr(a,ctx,indent,longVars)}) < (${genIntExpr(b,ctx,indent,longVars)})"
    case Prim("i.le", List(a, b)) => s"(${genIntExpr(a,ctx,indent,longVars)}) <= (${genIntExpr(b,ctx,indent,longVars)})"
    case Prim("i.gt", List(a, b)) => s"(${genIntExpr(a,ctx,indent,longVars)}) > (${genIntExpr(b,ctx,indent,longVars)})"
    case Prim("i.ge", List(a, b)) => s"(${genIntExpr(a,ctx,indent,longVars)}) >= (${genIntExpr(b,ctx,indent,longVars)})"
    case Prim("i.eq", List(a, b)) => s"(${genIntExpr(a,ctx,indent,longVars)}) == (${genIntExpr(b,ctx,indent,longVars)})"
    case If(c, th, el)
         if isBoolTyped(c, ctx, longVars) &&
            isBoolTyped(th, ctx, longVars) &&
            isBoolTyped(el, ctx, longVars) =>
      val pad = "    " * indent
      val cExpr = genBoolExpr(c, ctx, indent, longVars)
      val tExpr = genBoolExpr(th, ctx, indent + 1, longVars)
      val eExpr = genBoolExpr(el, ctx, indent + 1, longVars)
      s"if $cExpr {\n${pad}    $tExpr\n${pad}} else {\n${pad}    $eExpr\n${pad}}"
    case Prim("__arith__", List(Lit(CStr(op)), a, b))
         if longCmpOps.contains(op) &&
            isLongTyped(a, ctx, longVars) &&
            isLongTyped(b, ctx, longVars) =>
      val l = genIntExpr(a, ctx, indent, longVars)
      val r = genIntExpr(b, ctx, indent, longVars)
      op match
        case "==" => s"($l) == ($r)"
        case "!=" => s"($l) != ($r)"
        case "<"  => s"($l) < ($r)"
        case "<=" => s"($l) <= ($r)"
        case ">"  => s"($l) > ($r)"
        case ">=" => s"($l) >= ($r)"
        case _    => s"as_bool(${genExpr(t, ctx, indent, longVars)})"
    case Prim("not", List(a))      => s"!(${genBoolExpr(a, ctx, indent, longVars)})"
    case _ => s"as_bool(${genExpr(t, ctx, indent, longVars)})"

  // Float helpers keep boxed V inputs for ADTs/lists, but return raw f64.
  // This removes dynamic call/arithmetic/cell overhead in proven Float hot paths
  // while preserving the generic V fallback for unproven shapes.
  def genFloatExpr(
      t: Term,
      ctx: List[String],
      indent: Int,
      longVars: Set[String],
      floatVars: Set[String],
      boxedFloatVars: Set[String]
  ): String =
    val pad = "    " * indent
    t match
      case Lit(CFloat(d)) => rustFloatLit(d)
      case Local(i) =>
        val nm = localName(ctx, i)
        if floatVars.contains(nm) then nm else s"as_float(${nm}.clone())"
      case Prim("cell.get", List(Local(i))) =>
        val nm = localName(ctx, i)
        if floatVars.contains(nm) then nm
        else s"as_float(${genExpr(t, ctx, indent, longVars)})"
      case Prim("__arith__", List(Lit(CStr(op)), a, b))
          if floatArithOps(op) &&
             isFloatTyped(a, ctx, longVars, floatVars, boxedFloatVars) &&
             isFloatTyped(b, ctx, longVars, floatVars, boxedFloatVars) =>
        val l = genFloatExpr(a, ctx, indent, longVars, floatVars, boxedFloatVars)
        val r = genFloatExpr(b, ctx, indent, longVars, floatVars, boxedFloatVars)
        op match
          case "+" => s"($l) + ($r)"
          case "-" => s"($l) - ($r)"
          case "*" => s"($l) * ($r)"
          case "/" => s"($l) / ($r)"
          case "%" => s"($l) % ($r)"
          case _   => s"as_float(${genExpr(t, ctx, indent, longVars)})"
      case Prim("f.add", List(a, b)) =>
        s"(${genFloatExpr(a, ctx, indent, longVars, floatVars, boxedFloatVars)}) + (${genFloatExpr(b, ctx, indent, longVars, floatVars, boxedFloatVars)})"
      case Prim("f.sub", List(a, b)) =>
        s"(${genFloatExpr(a, ctx, indent, longVars, floatVars, boxedFloatVars)}) - (${genFloatExpr(b, ctx, indent, longVars, floatVars, boxedFloatVars)})"
      case Prim("f.mul", List(a, b)) =>
        s"(${genFloatExpr(a, ctx, indent, longVars, floatVars, boxedFloatVars)}) * (${genFloatExpr(b, ctx, indent, longVars, floatVars, boxedFloatVars)})"
      case Prim("f.div", List(a, b)) =>
        s"(${genFloatExpr(a, ctx, indent, longVars, floatVars, boxedFloatVars)}) / (${genFloatExpr(b, ctx, indent, longVars, floatVars, boxedFloatVars)})"
      case Prim("f.neg", List(a)) =>
        s"-(${genFloatExpr(a, ctx, indent, longVars, floatVars, boxedFloatVars)})"
      case App(Global(name), args) if floatGlobalDefs.get(name).contains(args.length) =>
        val argExprs = args.map(a => genExpr(a, ctx, indent, longVars)).mkString(", ")
        s"${rustDefName(name)}_float($argExprs)"
      case If(c, th, el)
          if isBoolTyped(c, ctx, longVars) &&
             isFloatTyped(th, ctx, longVars, floatVars, boxedFloatVars) &&
             isFloatTyped(el, ctx, longVars, floatVars, boxedFloatVars) =>
        val cExpr = genBoolExpr(c, ctx, indent, longVars)
        val tExpr = genFloatExpr(th, ctx, indent + 1, longVars, floatVars, boxedFloatVars)
        val eExpr = genFloatExpr(el, ctx, indent + 1, longVars, floatVars, boxedFloatVars)
        s"if $cExpr {\n${pad}    $tExpr\n${pad}} else {\n${pad}    $eExpr\n${pad}}"
      case Match(scrut, arms, default) =>
        genFloatMatch(scrut, arms, default, ctx, indent, longVars, floatVars, boxedFloatVars)
      case Let(rhs, body) =>
        val names = rhs.map(_ => fresh("l"))
        val bodyCtx = ctx ++ names
        val sb = new StringBuilder("{\n")
        var curCtx = ctx
        var curLongs = longVars
        var curFloats = floatVars
        var curBoxed = boxedFloatVars
        for ((r, n), _) <- rhs.zip(names).zipWithIndex do
          r match
            case Prim("cell.new", List(init))
                if isFloatTyped(init, curCtx, curLongs, curFloats, curBoxed) =>
              val initExpr = genFloatExpr(init, curCtx, indent + 1, curLongs, curFloats, curBoxed)
              sb ++= s"${pad}    let mut $n: f64 = $initExpr;\n"
              curFloats = curFloats + n
            case Prim("lcell.new", List(Lit(CInt(v)))) =>
              sb ++= s"${pad}    let mut $n: i64 = ${v}i64;\n"
              curLongs = curLongs + n
            case unit if isUnitTyped(unit, curCtx, curLongs, curFloats, curBoxed) =>
              sb ++= genFloatStmt(unit, curCtx, indent + 1, curLongs, curFloats, curBoxed) + "\n"
              sb ++= s"${pad}    let $n: V = V::Unit;\n"
            case _ =>
              val rExpr = genExpr(r, curCtx, indent + 1, curLongs)
              sb ++= s"${pad}    let $n: V = $rExpr;\n"
          curCtx = curCtx :+ n
        sb ++= s"${pad}    ${genFloatExpr(body, bodyCtx, indent + 1, curLongs, curFloats, curBoxed)}\n"
        sb ++= s"${pad}}"
        sb.toString
      case Seq(terms) =>
        if terms.isEmpty then "0.0f64"
        else if terms.length == 1 then genFloatExpr(terms.head, ctx, indent, longVars, floatVars, boxedFloatVars)
        else
          val sb = new StringBuilder("{\n")
          for t <- terms.init do
            sb ++= genFloatStmt(t, ctx, indent + 1, longVars, floatVars, boxedFloatVars) + "\n"
          sb ++= s"${pad}    ${genFloatExpr(terms.last, ctx, indent + 1, longVars, floatVars, boxedFloatVars)}\n"
          sb ++= s"${pad}}"
          sb.toString
      case _ =>
        s"as_float(${genExpr(t, ctx, indent, longVars)})"

  def genFloatMatch(
      scrut: Term,
      arms: List[Arm],
      default: Option[Term],
      ctx: List[String],
      indent: Int,
      longVars: Set[String],
      floatVars: Set[String],
      boxedFloatVars: Set[String]
  ): String =
    val pad = "    " * indent
    val sName = fresh("s")
    val scrutExpr = genExpr(scrut, ctx, indent, longVars)
    val sb = new StringBuilder(s"{\n${pad}    let $sName: V = $scrutExpr;\n")
    sb ++= s"${pad}    match &$sName {\n"
    for arm <- arms do
      val fieldNames = (0 until arm.arity).toList.map(_ => fresh("f"))
      val armCtx = ctx ++ fieldNames
      val armBoxed = boxedFloatVars ++ fieldNames
      val tagStr = rustStrLit(arm.tag)
      if arm.arity == 0 then
        val bodyExpr = genFloatExpr(arm.body, ctx, indent + 2, longVars, floatVars, boxedFloatVars)
        sb ++= s"""${pad}        V::Data(ref _tag, ref _fields) if _tag == $tagStr && _fields.len() == 0 => {\n"""
        sb ++= s"""${pad}            $bodyExpr\n"""
        sb ++= s"""${pad}        }\n"""
      else
        val fieldPatterns = fieldNames.zipWithIndex.map { case (n, i) =>
          s"let $n = _fields[$i].clone();"
        }.mkString(s"\n${pad}            ")
        val bodyExpr = genFloatExpr(arm.body, armCtx, indent + 2, longVars, floatVars, armBoxed)
        sb ++= s"""${pad}        V::Data(ref _tag, ref _fields) if _tag == $tagStr && _fields.len() == ${arm.arity} => {\n"""
        sb ++= s"""${pad}            $fieldPatterns\n"""
        sb ++= s"""${pad}            $bodyExpr\n"""
        sb ++= s"""${pad}        }\n"""
    val defaultExpr = default match
      case Some(d) => genFloatExpr(d, ctx, indent + 2, longVars, floatVars, boxedFloatVars)
      case None    => s"""panic!("match: no arm matched {}", show(&$sName))"""
    sb ++= s"""${pad}        _ => { $defaultExpr }\n"""
    sb ++= s"${pad}    }\n"
    sb ++= s"${pad}}"
    sb.toString

  def genFloatStmt(
      t: Term,
      ctx: List[String],
      indent: Int,
      longVars: Set[String],
      floatVars: Set[String],
      boxedFloatVars: Set[String]
  ): String =
    val pad = "    " * indent
    t match
      case Seq(ts) =>
        ts.map(s => genFloatStmt(s, ctx, indent, longVars, floatVars, boxedFloatVars)).mkString("\n")
      case While(cond, body) =>
        val condStr = genBoolExpr(cond, ctx, indent, longVars)
        staticFloatWhileBody(body, ctx, indent + 1, longVars, floatVars, boxedFloatVars) match
          case Some((prelude, bodyStmts)) =>
            s"${pad}{\n$prelude\n${pad}    while $condStr {\n$bodyStmts\n${pad}    }\n${pad}}"
          case None =>
            val bodyStmts = genFloatStmt(body, ctx, indent + 1, longVars, floatVars, boxedFloatVars)
            s"${pad}while $condStr {\n$bodyStmts\n${pad}}"
      case Prim("cell.set", List(Local(i), rhs)) =>
        val nm = localName(ctx, i)
        if floatVars.contains(nm) then
          s"${pad}$nm = ${genFloatExpr(rhs, ctx, indent, longVars, floatVars, boxedFloatVars)};"
        else
          s"${pad}${genExpr(t, ctx, indent, longVars)};"
      case Prim("lcell.set", List(Local(i), rhs)) =>
        val nm = localName(ctx, i)
        if longVars.contains(nm) then
          s"${pad}$nm = ${genIntExpr(rhs, ctx, indent, longVars)};"
        else
          s"${pad}${genExpr(t, ctx, indent, longVars)};"
      case Prim("__method__", List(Lit(CStr("foreach")), recv, Lam(1, body))) =>
        val cur = fresh("cur")
        val item = fresh("item")
        val recvTmp = fresh("recv")
        val (recvBinding, curInit) = recv match
          case Global(n) => ("", s"&${rustDefName(n)}")
          case _ =>
            val recvExpr = genExpr(recv, ctx, indent, longVars)
            (s"${pad}    let $recvTmp: V = $recvExpr;\n", s"&$recvTmp")
        val itemCtx = ctx :+ item
        val itemBoxed = boxedFloatVars + item
        val listBody = genFloatStmt(body, itemCtx, indent + 4, longVars, floatVars, itemBoxed)
        val arrBody = genFloatStmt(body, itemCtx, indent + 5, longVars, floatVars, itemBoxed)
        s"""${pad}{
${recvBinding}${pad}    let mut $cur: &V = $curInit;
${pad}    loop {
${pad}        match $cur {
${pad}            V::Data(ref _tag, ref _fields) if _tag == "Cons" => {
${pad}                let $item: V = _fields[0].clone();
$listBody
${pad}                $cur = &_fields[1];
${pad}            }
${pad}            V::Data(ref _tag, _) if _tag == "Nil" => break,
${pad}            V::Arr(arr) => {
${pad}                for $item in arr.borrow().iter().cloned() {
$arrBody
${pad}                }
${pad}                break;
${pad}            }
${pad}            other => panic!("foreach: expected list/array, got {:?}", other),
${pad}        }
${pad}    }
${pad}}"""
      case _ =>
        s"${pad}${genExpr(t, ctx, indent, longVars)};"

  private def staticFloatWhileBody(
      body: Term,
      ctx: List[String],
      indent: Int,
      longVars: Set[String],
      floatVars: Set[String],
      boxedFloatVars: Set[String]
  ): Option[(String, String)] =
    val terms = body match
      case Seq(ts) => ts
      case other   => List(other)
    val preludes = collection.mutable.ListBuffer.empty[String]
    val stmts = collection.mutable.ListBuffer.empty[String]
    var changed = false
    var ok = true
    for term <- terms do
      if ok then
        staticFloatForeach(term, ctx, indent, longVars, floatVars, boxedFloatVars) match
          case Some((prelude, stmt)) =>
            changed = true
            preludes += prelude
            stmts += stmt
          case None if isUnitTyped(term, ctx, longVars, floatVars, boxedFloatVars) =>
            stmts += genFloatStmt(term, ctx, indent, longVars, floatVars, boxedFloatVars)
          case None =>
            ok = false
    if changed && ok then Some((preludes.mkString("\n"), stmts.mkString("\n"))) else None

  private def staticFloatForeach(
      term: Term,
      ctx: List[String],
      indent: Int,
      longVars: Set[String],
      floatVars: Set[String],
      boxedFloatVars: Set[String]
  ): Option[(String, String)] =
    term match
      case Prim("__method__", List(Lit(CStr("foreach")), Global(listName), Lam(1, body))) =>
        for
          listTerm <- topDefs.get(listName)
          elems <- staticListElements(listTerm)
          (accName, fnName) <- floatAccumulatingForeachBody(body, ctx, floatVars)
          if floatGlobalDefs.get(fnName).contains(1)
        yield
          val pad = "    " * indent
          val areas = fresh("areas")
          val item = fresh("area")
          val values = elems.map { elem =>
            genFloatExpr(App(Global(fnName), List(elem)), ctx, indent, longVars, floatVars, boxedFloatVars)
          }
          val arrayLit = values.mkString(", ")
          val prelude = s"${pad}let $areas: [f64; ${values.length}] = [$arrayLit];"
          val stmt =
            s"""${pad}for $item in $areas.iter() {
${pad}    $accName = ($accName) + (*$item);
${pad}}"""
          (prelude, stmt)
      case _ => None

  private def staticListElements(t: Term): Option[List[Term]] = t match
    case Ctor("Nil", Nil) => Some(Nil)
    case Ctor("Cons", List(head, tail)) =>
      staticListElements(tail).map(head :: _)
    case _ => None

  private def floatAccumulatingForeachBody(
      body: Term,
      ctx: List[String],
      floatVars: Set[String]
  ): Option[(String, String)] =
    val itemCtx = ctx :+ "__static_foreach_item"
    def sameFloatCell(a: Int, b: Int): Option[String] =
      val an = localName(itemCtx, a)
      val bn = localName(itemCtx, b)
      if an == bn && floatVars(an) then Some(an) else None

    body match
      case Prim("cell.set", List(
            Local(setIdx),
            Prim("__arith__", List(
              Lit(CStr("+")),
              Prim("cell.get", List(Local(getIdx))),
              App(Global(fnName), List(Local(0)))
            ))
          )) =>
        sameFloatCell(setIdx, getIdx).map(_ -> fnName)
      case _ => None

  def genFloatHelperClosure(name: String, arity: Int, body: Term, indent: Int): String =
    val pad = "    " * indent
    val helperTag = fresh("fh")
    val allGlobals = freeGlobals(body).toList.sorted.map(rustDefName)
    val (lamGlobals, directGlobals) = allGlobals.partition(topLamDefNames.contains)
    val params = (0 until arity).toList.map(k => s"p${k}_${rustDefName(name)}_float")

    val lamCellPreClones = lamGlobals.map { g =>
      s"let ${g}__fwd_cap_$helperTag = ${g}__fwd.clone();"
    }
    val directGlobalPreClones = directGlobals.map { g =>
      s"let ${g}_cap_$helperTag = $g.clone();"
    }
    val allPreClones =
      (lamCellPreClones ++ directGlobalPreClones).mkString(s"\n${pad}    ") +
      (if lamCellPreClones.nonEmpty || directGlobalPreClones.nonEmpty then s"\n${pad}    " else "")

    val lamGlobalRebinds = lamGlobals.map { g =>
      s"let $g: V = ${g}__fwd_cap_$helperTag.borrow().clone().unwrap();"
    }
    val directGlobalRebinds = directGlobals.map { g =>
      s"let $g: V = ${g}_cap_$helperTag.clone();"
    }
    val allRebinds =
      (lamGlobalRebinds ++ directGlobalRebinds).mkString(s"\n${pad}        ") +
      (if lamGlobalRebinds.nonEmpty || directGlobalRebinds.nonEmpty then s"\n${pad}        " else "")

    val bodyExpr = genFloatExpr(body, params, indent + 1, Set.empty, Set.empty, Set.empty)
    val paramDecls = params.map(p => s"$p: V").mkString(", ")
    if arity == 0 then
      s"""|{
${pad}    ${allPreClones}move || -> f64 {
${pad}        ${allRebinds}$bodyExpr
${pad}    }
${pad}}""".stripMargin
    else
      s"""|{
${pad}    ${allPreClones}move |$paramDecls| -> f64 {
${pad}        ${allRebinds}$bodyExpr
${pad}    }
${pad}}""".stripMargin

  // Generate term as a Rust STATEMENT (no V::Unit creation).
  // Used for While bodies and Seq intermediates to avoid V-enum overhead in hot loops.
  def genStmt(t: Term, ctx: List[String], indent: Int, longVars: Set[String]): String =
    val pad = "    " * indent
    t match
      // Flatten nested Seqs into a series of statements
      case Seq(ts) =>
        ts.map(s => genStmt(s, ctx, indent, longVars)).mkString("\n")
      // lcell.set on a longVar: emit plain assignment, no V::Unit
      case Prim("lcell.set", List(cell, rhs)) =>
        cell match
          case Local(i) =>
            val nm = localName(ctx, i)
            if longVars.contains(nm) then
              return s"${pad}$nm = ${genIntExpr(rhs, ctx, indent, longVars)};"
          case _ =>
        s"${pad}${genExpr(t, ctx, indent, longVars)};"
      // io.println / io.print in statement position (already Unit)
      case Prim("io.println" | "io.print" | "io.eprint", _) =>
        s"${pad}${genExpr(t, ctx, indent, longVars)};"
      // Default: emit as expression statement (the ; discards the V value)
      case _ =>
        s"${pad}${genExpr(t, ctx, indent, longVars)};"

  private val longArithOps: Set[String] = Set("+", "-", "*", "/", "%", "&", "|", "^", "<<", ">>", ">>>")
  private val longCmpOps: Set[String] = Set("==", "!=", "<", "<=", ">", ">=")
  private val floatArithOps: Set[String] = Set("+", "-", "*", "/", "%")

  private def isLongTyped(t: Term, ctx: List[String], longVars: Set[String]): Boolean = t match
    case Lit(CInt(_)) => true
    case Local(i) =>
      val nm = localName(ctx, i)
      longVars.contains(nm)
    case Prim("lcell.get", List(Local(i))) =>
      val nm = localName(ctx, i)
      longVars.contains(nm)
    case App(Global(name), args) if longGlobalDefs.get(name).contains(args.length) =>
      args.forall(isLongTyped(_, ctx, longVars))
    case If(c, th, el) =>
      isBoolTyped(c, ctx, longVars) &&
      isLongTyped(th, ctx, longVars) &&
      isLongTyped(el, ctx, longVars)
    case Prim("__arith__", List(Lit(CStr(op)), l, r)) =>
      longArithOps.contains(op) && isLongTyped(l, ctx, longVars) && isLongTyped(r, ctx, longVars)
    case Prim("__method__", List(Lit(CStr(method)), recv)) if method == "toLong" || method == "toInt" =>
      isLongTyped(recv, ctx, longVars)
    case _ => false

  private def isBoolTyped(t: Term, ctx: List[String], longVars: Set[String]): Boolean = t match
    case Lit(CBool(_)) => true
    case If(c, th, el) =>
      isBoolTyped(c, ctx, longVars) &&
      isBoolTyped(th, ctx, longVars) &&
      isBoolTyped(el, ctx, longVars)
    case Prim("__arith__", List(Lit(CStr(op)), l, r)) =>
      longCmpOps.contains(op) && isLongTyped(l, ctx, longVars) && isLongTyped(r, ctx, longVars)
    case Prim("not", List(a)) => isBoolTyped(a, ctx, longVars)
    case _ => false

  private def isFloatTyped(
      t: Term,
      ctx: List[String],
      longVars: Set[String],
      floatVars: Set[String],
      boxedFloatVars: Set[String]
  ): Boolean = t match
    case Lit(CFloat(_)) => true
    case Local(i) =>
      val nm = localName(ctx, i)
      floatVars.contains(nm) || boxedFloatVars.contains(nm)
    case Prim("cell.get", List(Local(i))) =>
      val nm = localName(ctx, i)
      floatVars.contains(nm)
    case App(Global(name), args) if floatGlobalDefs.get(name).contains(args.length) =>
      true
    case If(c, th, el) =>
      isBoolTyped(c, ctx, longVars) &&
      isFloatTyped(th, ctx, longVars, floatVars, boxedFloatVars) &&
      isFloatTyped(el, ctx, longVars, floatVars, boxedFloatVars)
    case Match(_, arms, default) =>
      arms.forall { arm =>
        val fieldNames = (0 until arm.arity).toList.map(k => s"__match_f_${ctx.length}_${arm.tag}_$k")
        isFloatTyped(arm.body, ctx ++ fieldNames, longVars, floatVars, boxedFloatVars ++ fieldNames)
      } &&
      default.forall(isFloatTyped(_, ctx, longVars, floatVars, boxedFloatVars))
    case Let(rhs, body) =>
      val names = rhs.indices.map(k => s"__let_f_${ctx.length}_${rhs.length}_$k").toList
      val bodyCtx = ctx ++ names
      var curCtx = ctx
      var curLongs = longVars
      var curFloats = floatVars
      var curBoxed = boxedFloatVars
      var ok = true
      for (r, n) <- rhs.zip(names) do
        r match
          case Prim("cell.new", List(init))
              if isFloatTyped(init, curCtx, curLongs, curFloats, curBoxed) =>
            curFloats = curFloats + n
          case Prim("lcell.new", List(Lit(CInt(_)))) =>
            curLongs = curLongs + n
          case unit if isUnitTyped(unit, curCtx, curLongs, curFloats, curBoxed) =>
            ()
          case _ =>
            ok = false
        curCtx = curCtx :+ n
      ok && isFloatTyped(body, bodyCtx, curLongs, curFloats, curBoxed)
    case Seq(terms) =>
      terms.nonEmpty &&
      terms.init.forall(isUnitTyped(_, ctx, longVars, floatVars, boxedFloatVars)) &&
      isFloatTyped(terms.last, ctx, longVars, floatVars, boxedFloatVars)
    case Prim("__arith__", List(Lit(CStr(op)), l, r)) =>
      floatArithOps(op) &&
      isFloatTyped(l, ctx, longVars, floatVars, boxedFloatVars) &&
      isFloatTyped(r, ctx, longVars, floatVars, boxedFloatVars)
    case Prim("f.add" | "f.sub" | "f.mul" | "f.div", List(l, r)) =>
      isFloatTyped(l, ctx, longVars, floatVars, boxedFloatVars) &&
      isFloatTyped(r, ctx, longVars, floatVars, boxedFloatVars)
    case Prim("f.neg", List(a)) =>
      isFloatTyped(a, ctx, longVars, floatVars, boxedFloatVars)
    case _ => false

  private def isUnitTyped(
      t: Term,
      ctx: List[String],
      longVars: Set[String],
      floatVars: Set[String],
      boxedFloatVars: Set[String]
  ): Boolean = t match
    case While(cond, body) =>
      isBoolTyped(cond, ctx, longVars) &&
      isUnitTyped(body, ctx, longVars, floatVars, boxedFloatVars)
    case Seq(ts) =>
      ts.forall(isUnitTyped(_, ctx, longVars, floatVars, boxedFloatVars))
    case Prim("cell.set", List(Local(i), rhs)) =>
      val nm = localName(ctx, i)
      floatVars.contains(nm) &&
      isFloatTyped(rhs, ctx, longVars, floatVars, boxedFloatVars)
    case Prim("lcell.set", List(Local(i), rhs)) =>
      val nm = localName(ctx, i)
      longVars.contains(nm) &&
      isLongTyped(rhs, ctx, longVars)
    case Prim("__method__", List(Lit(CStr("foreach")), _, Lam(1, body))) =>
      val item = s"__foreach_item_${ctx.length}"
      isUnitTyped(body, ctx :+ item, longVars, floatVars, boxedFloatVars + item)
    case _ => false

  // ── Lambda generation ─────────────────────────────────────────────────────
  // genLam uses per-lambda unique tag for capture names so that:
  // - outer ctx locals are pre-cloned with unique names (avoids move-after-use)
  // - globals referenced in body are also pre-cloned (avoids move-out-of-Fn-closure)
  // Inside the closure, all are rebound to their canonical names.

  def genLam(arity: Int, body: Term, ctx: List[String], indent: Int, longVars: Set[String] = Set.empty): String =
    val pad = "    " * indent
    val lamTag = fresh("lam")
    val captureLocals = ctx.distinct
    val allGlobals = freeGlobals(body).toList.sorted.map(rustDefName)
    // Split: Lam defs accessed via forward-ref cells; others accessed directly.
    val (lamGlobals, directGlobals) = allGlobals.partition(topLamDefNames.contains)
    val argNames = (0 until arity).toList.map(_ => fresh("a"))
    // ctx inside body: outer ctx + arg names (Local(0) = last arg)
    val bodyCtx = ctx ++ argNames

    // Pre-clone locals with unique per-lam names (avoids same-name shadow conflicts)
    // For longVar (i64) captures: box them to V::Int before moving into closure.
    // By analysis, longVars should not appear in Lam bodies — but handle it safely.
    val localPreClones = captureLocals.map { n =>
      if longVars.contains(n) then s"let ${n}_cap_$lamTag: V = V::Int($n);"
      else s"let ${n}_cap_$lamTag = $n.clone();"
    }
    // Lam defs: pre-clone their forward-ref cells (always available even for self-ref)
    val lamCellPreClones = lamGlobals.map { g =>
      s"let ${g}__fwd_cap_$lamTag = ${g}__fwd.clone();"
    }
    // Non-Lam globals: pre-clone values directly
    val directGlobalPreClones = directGlobals.map { g =>
      s"let ${g}_cap_$lamTag = $g.clone();"
    }
    val allPreClones =
      (localPreClones ++ lamCellPreClones ++ directGlobalPreClones).mkString(s"\n${pad}    ") +
      (if localPreClones.nonEmpty || lamCellPreClones.nonEmpty || directGlobalPreClones.nonEmpty
       then s"\n${pad}    " else "")

    // Inside closure: rebind to canonical names
    val localRebinds = captureLocals.map { n =>
      s"let $n: V = ${n}_cap_$lamTag.clone();"
    }
    // Lam globals: unwrap from forward-ref cell at call time (handles forward/self/mutual refs)
    val lamGlobalRebinds = lamGlobals.map { g =>
      s"let $g: V = ${g}__fwd_cap_$lamTag.borrow().clone().unwrap();"
    }
    val directGlobalRebinds = directGlobals.map { g =>
      s"let $g: V = ${g}_cap_$lamTag.clone();"
    }
    val allRebinds =
      (localRebinds ++ lamGlobalRebinds ++ directGlobalRebinds).mkString(s"\n${pad}        ") +
      (if localRebinds.nonEmpty || lamGlobalRebinds.nonEmpty || directGlobalRebinds.nonEmpty
       then s"\n${pad}        " else "")

    // Inside closures: all variables are V (no longVars propagate into Lam bodies)
    val bodyExpr = genExpr(body, bodyCtx, indent + 1)

    if arity == 0 then
      s"""|{
${pad}    ${allPreClones}V::Fn(Rc::new(move |_args: Vec<V>| {
${pad}        ${allRebinds}$bodyExpr
${pad}    }))
${pad}}""".stripMargin
    else
      val argUnpack = argNames.zipWithIndex.map { case (n, i) =>
        s"let $n: V = _args[${i}].clone();"
      }.mkString(s"\n${pad}        ")
      s"""|{
${pad}    ${allPreClones}V::Fn(Rc::new(move |_args: Vec<V>| {
${pad}        ${allRebinds}$argUnpack
${pad}        $bodyExpr
${pad}    }))
${pad}}""".stripMargin

  // ── LetRec generation ────────────────────────────────────────────────────

  def genLetRec(lams: List[Term], body: Term, ctx: List[String], indent: Int, longVars: Set[String] = Set.empty): String =
    val pad = "    " * indent
    // Names for the recursive bindings
    val recNames = lams.map(_ => fresh("r"))
    // ctx after binding all recs: Local(0) = recNames.last
    val recCtx = ctx ++ recNames

    val sb = new StringBuilder("{\n")

    // Create forward-ref cells for each lam
    for n <- recNames do
      sb ++= s"${pad}    let ${n}_cell: Rc<RefCell<Option<V>>> = Rc::new(RefCell::new(None));\n"

    // Build each closure
    for (lam, recName) <- lams.zip(recNames) do
      val (arity, lamBody) = lam match
        case Lam(ar, b) => (ar, b)
        case _ => sys.error(s"letrec binding must be a lam, got $lam")

      val argNames = (0 until arity).toList.map(_ => fresh("a"))
      // Context inside lam body: recCtx + argNames
      val lamBodyCtx = recCtx ++ argNames

      // Outer context vars need per-closure clones so the original remains available.
      val outerCaptures = ctx.distinct
      // Globals referenced anywhere in lamBody — split into Lam (use cells) vs direct.
      val allGlobalCaptures = freeGlobals(lamBody).toList.sorted.map(rustDefName)
      val (lamGlobalCaptures, directGlobalCaptures) = allGlobalCaptures.partition(topLamDefNames.contains)
      val closureTag = recName  // unique per closure

      // Pre-clone outer locals with unique names
      val preCloneStmts = outerCaptures.map { n =>
        val capN = s"${n}_cap_${closureTag}"
        s"let $capN = $n.clone();"
      }.mkString(s"\n${pad}    ") + (if outerCaptures.nonEmpty then s"\n${pad}    " else "")

      // Lam globals: pre-clone their forward-ref cells (always available, handles forward refs)
      val lamGlobalCellPreCloneStmts = lamGlobalCaptures.map { g =>
        s"let ${g}__fwd_cap_${closureTag} = ${g}__fwd.clone();"
      }.mkString(s"\n${pad}    ") + (if lamGlobalCaptures.nonEmpty then s"\n${pad}    " else "")

      // Non-Lam globals: pre-clone values directly
      val directGlobalPreCloneStmts = directGlobalCaptures.map { g =>
        val capN = s"${g}_cap_${closureTag}"
        s"let $capN = $g.clone();"
      }.mkString(s"\n${pad}    ") + (if directGlobalCaptures.nonEmpty then s"\n${pad}    " else "")

      // Pre-clone cell refs with unique names
      val cellPreCloneStmts = recNames.map { n =>
        val cellRef = s"${n}_cell_cap_${closureTag}"
        s"let $cellRef = ${n}_cell.clone();"
      }.mkString(s"\n${pad}    ") + s"\n${pad}    "

      // Inside the closure: rebind pre-clones back to canonical names
      val outerRebindStmts = outerCaptures.map { n =>
        val capN = s"${n}_cap_${closureTag}"
        s"let $n: V = $capN.clone();"
      }.mkString(s"\n${pad}        ")
      val outerRebindPart = if outerCaptures.nonEmpty then outerRebindStmts + s"\n${pad}        " else ""

      // Lam globals: unwrap from forward-ref cell at call time
      val lamGlobalRebindStmts = lamGlobalCaptures.map { g =>
        s"let $g: V = ${g}__fwd_cap_${closureTag}.borrow().clone().unwrap();"
      }.mkString(s"\n${pad}        ")
      val lamGlobalRebindPart = if lamGlobalCaptures.nonEmpty then lamGlobalRebindStmts + s"\n${pad}        " else ""

      val directGlobalRebindStmts = directGlobalCaptures.map { g =>
        val capN = s"${g}_cap_${closureTag}"
        s"let $g: V = $capN.clone();"
      }.mkString(s"\n${pad}        ")
      val directGlobalRebindPart = if directGlobalCaptures.nonEmpty then directGlobalRebindStmts + s"\n${pad}        " else ""

      // Rebind cell refs inside closure
      val cellRebindStmts = recNames.map { n =>
        val cellRef = s"${n}_cell_cap_${closureTag}"
        s"let ${n}_cell = $cellRef.clone();"
      }.mkString(s"\n${pad}        ")

      // Unpack rec bindings from their cells in the closure body
      val recUnpack = recNames.map(n =>
        s"let $n: V = ${n}_cell.borrow().clone().unwrap();"
      ).mkString(s"\n${pad}        ")

      val argUnpack = argNames.zipWithIndex.map { case (n, i) =>
        s"let $n: V = _args[$i].clone();"
      }.mkString(s"\n${pad}        ")

      val bodyExpr = genExpr(lamBody, lamBodyCtx, indent + 2)

      val preClones = preCloneStmts + lamGlobalCellPreCloneStmts + directGlobalPreCloneStmts + cellPreCloneStmts
      val innerRebinds = outerRebindPart + lamGlobalRebindPart + directGlobalRebindPart + cellRebindStmts + s"\n${pad}        "
      if arity == 0 then
        sb ++= s"""${pad}    ${preClones}let $recName: V = V::Fn(Rc::new(move |_args: Vec<V>| {
${pad}        ${innerRebinds}$recUnpack
${pad}        $bodyExpr
${pad}    }));\n"""
      else
        sb ++= s"""${pad}    ${preClones}let $recName: V = V::Fn(Rc::new(move |_args: Vec<V>| {
${pad}        ${innerRebinds}$recUnpack
${pad}        $argUnpack
${pad}        $bodyExpr
${pad}    }));\n"""

    // Store into cells
    for n <- recNames do
      sb ++= s"${pad}    *${n}_cell.borrow_mut() = Some($n.clone());\n"

    // Body (LetRec body runs at definition scope, longVars from outer scope are valid)
    val bodyExpr = genExpr(body, recCtx, indent + 1, longVars)
    sb ++= s"${pad}    $bodyExpr\n"
    sb ++= s"${pad}}"
    sb.toString

  // ── Match generation ──────────────────────────────────────────────────────

  def genMatch(scrut: Term, arms: List[Arm], default: Option[Term], ctx: List[String], indent: Int, longVars: Set[String] = Set.empty): String =
    val pad = "    " * indent
    val sName = fresh("s")
    val scrutExpr = genExpr(scrut, ctx, indent, longVars)
    val sb = new StringBuilder(s"{\n${pad}    let $sName: V = $scrutExpr;\n")
    sb ++= s"${pad}    match &$sName {\n"
    for arm <- arms do
      val fieldNames = (0 until arm.arity).toList.map(_ => fresh("f"))
      // arm body ctx: ctx extended by fields in order
      // Local(0) = last field = fields[arity-1]
      // Local(arity-1) = first field = fields[0]
      val armCtx = ctx ++ fieldNames  // fieldNames(0) = fields[0] = Local(arity-1-0) = Local(arity-1)
      // Actually: fields are added so Local(0) = last = fieldNames.last
      // fieldNames ordered so fieldNames(i) = fields[i]
      // and Local(j) in arm body = ctx_after_extend[len-1-j]
      // ctx_after_extend = ctx ++ fieldNames, len = ctx.length + arm.arity
      // Local(0) = fieldNames.last = fields[arity-1] ✓
      // Local(arity-1) = fieldNames(0) = fields[0] ✓

      val tagStr = rustStrLit(arm.tag)
      if arm.arity == 0 then
        val bodyExpr = genExpr(arm.body, ctx, indent + 2, longVars)
        sb ++= s"""${pad}        V::Data(ref _tag, ref _fields) if _tag == $tagStr && _fields.len() == 0 => {\n"""
        sb ++= s"""${pad}            $bodyExpr\n"""
        sb ++= s"""${pad}        }\n"""
      else
        val fieldPatterns = fieldNames.zipWithIndex.map { case (n, i) =>
          s"let $n = _fields[$i].clone();"
        }.mkString(s"\n${pad}            ")
        // Match arm binds new V locals; don't propagate longVars into arm names
        val bodyExpr = genExpr(arm.body, armCtx, indent + 2, longVars)
        sb ++= s"""${pad}        V::Data(ref _tag, ref _fields) if _tag == $tagStr && _fields.len() == ${arm.arity} => {\n"""
        sb ++= s"""${pad}            $fieldPatterns\n"""
        sb ++= s"""${pad}            $bodyExpr\n"""
        sb ++= s"""${pad}        }\n"""
    // Default or panic
    val defaultExpr = default match
      case Some(d) => genExpr(d, ctx, indent + 2, longVars)
      case None    => s"""panic!("match: no arm matched {}", show(&$sName))"""
    sb ++= s"""${pad}        _ => { $defaultExpr }\n"""
    sb ++= s"${pad}    }\n"
    sb ++= s"${pad}}"
    sb.toString

  // ── Primitive generation ──────────────────────────────────────────────────

  def genPrim(op: String, args: List[Term], ctx: List[String], indent: Int, longVars: Set[String] = Set.empty): String =
    // Fast paths for LCell operations when the cell is a longVar (plain i64, not Rc<RefCell>)
    op match
      case "lcell.get" if args.length == 1 =>
        args(0) match
          case Local(i) =>
            val nm = localName(ctx, i)
            if longVars.contains(nm) then return s"V::Int($nm)"
          case _ =>
      case "lcell.set" if args.length == 2 =>
        args(0) match
          case Local(i) =>
            val nm = localName(ctx, i)
            if longVars.contains(nm) then
              // Use inline integer expression to avoid boxing the rhs
              val valExpr = genIntExpr(args(1), ctx, indent, longVars)
              return s"{ $nm = $valExpr; V::Unit }"
          case _ =>
      case _ =>
    val a = args.map(x => genExpr(x, ctx, indent, longVars))
    def a0 = a(0); def a1 = a(1); def a2 = a(2)
    op match
      // __arith__ with literal op string (FrontendBridge-generated): dispatch to v_arith runtime helper
      case "__arith__" => s"v_arith(${a0}, ${a1}, ${a2})"
      // __unary__ with literal op string (FrontendBridge-generated): dispatch to v_unary
      case "__unary__" => s"v_unary(${a0}, ${a1})"
      // __method__: dispatch to v_method runtime helper
      case "__method__" =>
        val rest = a.drop(2).mkString(", ")
        if rest.isEmpty then s"v_method(${a0}, ${a1}, vec![])"
        else s"v_method(${a0}, ${a1}, vec![$rest])"
      case "__autoPrint__" => s"{ ${a0}; V::Unit }"
      case HandlerDispatchShape.SelectedPrimitive => s"{ let _ = $a0; V::Unit }"
      case HandlerDispatchShape.MissPrimitive =>
        s"{ let _ = $a0; panic!(\"match: no matching case\"); }"
      // Integer arithmetic
      case "i.add"  => s"v_iadd($a0, $a1)"
      case "i.sub"  => s"v_isub($a0, $a1)"
      case "i.mul"  => s"v_imul($a0, $a1)"
      case "i.div"  => s"v_idiv($a0, $a1)"
      case "i.mod"  => s"v_imod($a0, $a1)"
      case "i.neg"  => s"v_ineg($a0)"
      case "i.and"  => s"V::Int(as_int($a0) & as_int($a1))"
      case "i.or"   => s"V::Int(as_int($a0) | as_int($a1))"
      case "i.xor"  => s"V::Int(as_int($a0) ^ as_int($a1))"
      case "i.not"  => s"V::Int(!as_int($a0))"
      case "i.shl"  => s"V::Int(as_int($a0) << as_int($a1))"
      case "i.shr"  => s"V::Int(as_int($a0) >> as_int($a1))"
      case "i.ushr" => s"V::Int(((as_int($a0) as u64) >> (as_int($a1) as u64)) as i64)"
      case "i.eq"   => s"v_ieq($a0, $a1)"
      case "i.lt"   => s"v_ilt($a0, $a1)"
      case "i.le"   => s"v_ile($a0, $a1)"
      case "i.gt"   => s"v_igt($a0, $a1)"
      case "i.ge"   => s"v_ige($a0, $a1)"
      case "not"    => s"V::Bool(!as_bool($a0))"
      // Float
      case "f.add"   => s"V::Float(as_float($a0) + as_float($a1))"
      case "f.sub"   => s"V::Float(as_float($a0) - as_float($a1))"
      case "f.mul"   => s"V::Float(as_float($a0) * as_float($a1))"
      case "f.div"   => s"V::Float(as_float($a0) / as_float($a1))"
      case "f.neg"   => s"V::Float(-as_float($a0))"
      case "f.sqrt"  => s"V::Float(as_float($a0).sqrt())"
      case "f.floor" => s"V::Float(as_float($a0).floor())"
      case "f.ceil"  => s"V::Float(as_float($a0).ceil())"
      case "f.round" => s"V::Float(as_float($a0).round())"
      case "f.trunc" => s"V::Float(as_float($a0).trunc())"
      case "f.eq"    => s"V::Bool(as_float($a0) == as_float($a1))"
      case "f.lt"    => s"V::Bool(as_float($a0) < as_float($a1))"
      case "f.le"    => s"V::Bool(as_float($a0) <= as_float($a1))"
      case "f.gt"    => s"V::Bool(as_float($a0) > as_float($a1))"
      case "f.ge"    => s"V::Bool(as_float($a0) >= as_float($a1))"
      case "f.isNaN" => s"V::Bool(as_float($a0).is_nan())"
      case "f.isInf" => s"V::Bool(as_float($a0).is_infinite())"
      // Numeric conversions
      case "i->big"   => a0  // keep as Int
      case "big->i"   => a0
      case "i->f"     => s"V::Float(as_int($a0) as f64)"
      case "f->i"     => s"V::Int(as_float($a0) as i64)"
      case "big->f"   => s"V::Float(as_int($a0) as f64)"
      case "f->big"   => s"V::Int(as_float($a0) as i64)"
      case "i->str"   => s"V::Str(as_int($a0).to_string())"
      case "big->str" => s"V::Str(as_int($a0).to_string())"
      case "f->str"   => s"V::Str(float_to_str(as_float($a0)))"
      case "str->i"   => s"v_str_to_i($a0)"
      case "str->big" => s"v_str_to_i($a0)"
      case "str->f"   => s"v_str_to_f($a0)"
      // String
      case "slen"       => s"V::Int(as_str($a0).len() as i64)"
      case "sconcat"    => s"v_sconcat($a0, $a1)"
      case "sslice"     => s"V::Str(as_str($a0)[as_int($a1) as usize..as_int($a2) as usize].to_string())"
      case "scodeAt"    => s"V::Int(as_str($a0).chars().nth(as_int($a1) as usize).unwrap_or('\\0') as i64)"
      case "sfromCodes" => s"v_from_codes($a0)"
      case "seq"        => s"V::Bool(as_str($a0) == as_str($a1))"
      case "scmp"       => s"V::Int(as_str($a0).cmp(&as_str($a1)) as i64)"
      case "sindexOf"   => s"v_str_index_of($a0, $a1)"
      case "str.split"  => s"v_str_split($a0, $a1)"
      case "str.trim"   => s"V::Str(as_str($a0).trim().to_string())"
      case "str.lines"  => s"v_str_lines($a0)"
      // Bytes
      case "blen"      => s"V::Int(as_bytes($a0).len() as i64)"
      case "bget"      => s"V::Int(as_bytes($a0)[as_int($a1) as usize] as i64)"
      case "bslice"    => s"V::Bytes(as_bytes($a0)[as_int($a1) as usize..as_int($a2) as usize].to_vec())"
      case "bconcat"   => s"{ let mut _b = as_bytes($a0).clone(); _b.extend_from_slice(&as_bytes($a1)); V::Bytes(_b) }"
      case "str->utf8" => s"V::Bytes(as_str($a0).as_bytes().to_vec())"
      case "utf8->str" => s"V::Str(String::from_utf8(as_bytes($a0).clone()).unwrap())"
      // Data reflection
      case "tagOf"   => s"V::Str(as_data_tag($a0))"
      case "arity"   => s"V::Int(as_data_fields($a0).len() as i64)"
      case "fieldAt" => s"as_data_fields($a0)[as_int($a1) as usize].clone()"
      // Map
      case "map.new"  => "V::Map(Rc::new(RefCell::new(std::collections::HashMap::new())))"
      case "map.get"  => s"v_map_get($a0, $a1)"
      case "map.put"  => s"{ as_map($a0).borrow_mut().insert(VKey::from(&$a1), $a2); V::Unit }"
      case "map.has"  => s"V::Bool(as_map($a0).borrow().contains_key(&VKey::from(&$a1)))"
      case "map.del"  => s"{ as_map($a0).borrow_mut().remove(&VKey::from(&$a1)); V::Unit }"
      case "map.keys" => s"v_map_keys($a0)"
      case "map.size" => s"V::Int(as_map($a0).borrow().len() as i64)"
      // FrontendBridge top-level bookkeeping: standalone source backends do not
      // need a dynamic global registry, because generated Rust variables already
      // hold the lowered values.
      case "global.reg" => "V::Unit"
      // Array
      case "arr.new"   => "V::Arr(Rc::new(RefCell::new(vec![])))"
      case "arr.len"   => s"V::Int(as_arr($a0).borrow().len() as i64)"
      case "arr.get"   => s"as_arr($a0).borrow()[as_int($a1) as usize].clone()"
      case "arr.set"   => s"{ as_arr($a0).borrow_mut()[as_int($a1) as usize] = $a2; V::Unit }"
      case "arr.push"  => s"{ as_arr($a0).borrow_mut().push($a1); V::Unit }"
      case "arr.pop"   => s"as_arr($a0).borrow_mut().pop().unwrap_or(V::Unit)"
      case "arr.slice" =>
        s"V::Arr(Rc::new(RefCell::new(as_arr($a0).borrow()[as_int($a1) as usize..as_int($a2) as usize].to_vec())))"
      // Cell — evaluate new value BEFORE borrow_mut to avoid double-borrow
      case "cell.new" => s"V::Cell(Rc::new(RefCell::new($a0)))"
      case "cell.get" => s"as_cell($a0).borrow().clone()"
      case "cell.set" =>
        val cellN = fresh("c"); val valN = fresh("v")
        s"{ let $cellN = $a0; let $valN = $a1; *as_cell($cellN).borrow_mut() = $valN; V::Unit }"
      // LongCell (mutable i64, same semantics as cell but int-only)
      case "lcell.new" => s"V::LCell(Rc::new(RefCell::new(as_int($a0))))"
      case "lcell.get" => s"V::Int(*as_lcell($a0).borrow())"
      case "lcell.set" =>
        val cellN = fresh("c"); val valN = fresh("v")
        s"{ let $cellN = $a0; let $valN = as_int($a1); *as_lcell($cellN).borrow_mut() = $valN; V::Unit }"
      // I/O
      case "io.print"    => s"{ v_print($a0); V::Unit }"
      case "io.println"  => s"{ v_println($a0); V::Unit }"
      case "io.eprint"   => s"{ eprint!(\"{{}}\", show(&$a0)); V::Unit }"
      case "io.nanoTime" => "V::Int(std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).expect(\"nanoTime\").as_nanos() as i64)"
      case "io.args"     => "v_io_args()"
      case "io.readFile" => s"V::Bytes(std::fs::read(as_str($a0)).expect(\"readFile\"))"
      case "io.writeFile"=> s"{ std::fs::write(as_str($a0), &as_bytes($a1)).expect(\"writeFile\"); V::Unit }"
      case "io.env"      => s"v_io_env($a0)"
      case "io.exit"     => s"{ std::process::exit(as_int($a0) as i32); }"
      // coreir.encode: not needed at runtime, stub
      case "coreir.encode" => s"""V::Str("[coreir.encode not supported in Rust backend]".to_string())"""
      // __math_obj__: lazy stub — accessed as g_math but only used if math.xxx is called
      case "__math_obj__" => """V::Fn(Rc::new(move |_args: Vec<V>| { panic!("math object not supported in Rust backend"); }))"""
      // runLogger (effects infra)
      case "runLogger"   => s"call_fn($a0, vec![])"
      case unknown =>
        // emit a runtime panic for unknown primitives
        s"""{ panic!("unimplemented prim: {}", ${rustStrLit(unknown)}); }"""

  // ── Rust string literal ───────────────────────────────────────────────────

  def rustFloatLit(d: Double): String =
    if d.isNaN then "f64::NAN"
    else if d.isInfinite && d > 0 then "f64::INFINITY"
    else if d.isInfinite then "f64::NEG_INFINITY"
    else s"${d}f64"

  def rustStrLit(s: String): String =
    val sb = new StringBuilder("\"")
    s.foreach {
      case '"'  => sb ++= "\\\""
      case '\\' => sb ++= "\\\\"
      case '\n' => sb ++= "\\n"
      case '\r' => sb ++= "\\r"
      case '\t' => sb ++= "\\t"
      case c if c.isControl => sb ++= f"\\u{${c.toInt}%04x}"
      case c => sb += c
    }
    sb += '"'
    sb.toString

  // ── Rust runtime header ───────────────────────────────────────────────────

  val RUNTIME_HEADER: String = """// Generated by v2/backend/rust/RustBackend.scala
use std::rc::Rc;
use std::cell::RefCell;
use std::collections::HashMap;
use std::fmt;

// ── Value type ────────────────────────────────────────────────────────────────

#[derive(Clone)]
enum V {
    Unit,
    Bool(bool),
    Int(i64),
    Float(f64),
    Str(String),
    Bytes(Vec<u8>),
    Data(String, Vec<V>),
    Fn(Rc<dyn Fn(Vec<V>) -> V>),
    Cell(Rc<RefCell<V>>),
    LCell(Rc<RefCell<i64>>),
    Map(Rc<RefCell<HashMap<VKey, V>>>),
    Arr(Rc<RefCell<Vec<V>>>),
}

#[derive(Clone, Hash, PartialEq, Eq, Debug)]
struct VKey(String);

impl From<&V> for VKey {
    fn from(v: &V) -> Self { VKey(show(v)) }
}

impl fmt::Debug for V {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", show(self))
    }
}

// ── Show ──────────────────────────────────────────────────────────────────────

// Entry-result display (VM Show.show): strings are quoted at top level
fn show_entry(v: &V) -> String {
    match v { V::Str(s) => format!("\"{}\"", s), _ => show(v) }
}

fn show(v: &V) -> String {
    match v {
        V::Unit        => "()".to_string(),
        V::Bool(b)     => b.to_string(),
        V::Int(n)      => n.to_string(),
        V::Float(d)    => float_to_str(*d),
        V::Str(s)      => s.clone(),
        V::Bytes(b)    => format!("Bytes({})", b.iter().map(|x| format!("{:02x}", x)).collect::<String>()),
        V::Data(tag, _) if tag == "Cons" || tag == "Nil" => {
            // VM anyStr semantics: Cons/Nil chains render as List(…)
            let mut items: Vec<String> = Vec::new();
            let mut cur = v.clone();
            loop {
                match cur {
                    V::Data(ref t2, ref fs) if t2 == "Cons" => {
                        items.push(show(&fs[0]));
                        let next = fs[1].clone();
                        cur = next;
                    }
                    _ => break,
                }
            }
            format!("List({})", items.join(", "))
        }
        V::Data(tag, fields) if fields.is_empty() => tag.clone(),
        V::Data(tag, fields) => {
            let fs: Vec<String> = fields.iter().map(show).collect();
            format!("{}({})", tag, fs.join(", "))
        }
        V::Fn(_)       => "<fn>".to_string(),
        V::Cell(c)     => format!("Cell({})", show(&*c.borrow())),
        V::LCell(c)    => format!("LCell({})", c.borrow()),
        V::Map(m)      => format!("Map({})", m.borrow().len()),
        V::Arr(a)      => {
            let items: Vec<String> = a.borrow().iter().map(show).collect();
            format!("[{}]", items.join(", "))
        }
    }
}

fn float_to_str(d: f64) -> String {
    // VM floatStr semantics: whole doubles collapse ("3", not "3.0"); nan/inf lowercase.
    if d.is_nan() { return "nan".to_string(); }
    if d.is_infinite() { return if d > 0.0 { "inf".to_string() } else { "-inf".to_string() }; }
    if d == (d as i64) as f64 { return format!("{}", d as i64); }
    format!("{}", d)
}

// ── Coercions ─────────────────────────────────────────────────────────────────

fn as_int(v: V) -> i64 {
    match v {
        V::Int(n)   => n,
        V::Bool(b)  => if b { 1 } else { 0 },
        V::Float(d) => d as i64,
        other => panic!("expected Int, got {:?}", other),
    }
}

fn as_bool(v: V) -> bool {
    match v {
        V::Bool(b) => b,
        V::Int(n)  => n != 0,
        other => panic!("expected Bool, got {:?}", other),
    }
}

fn as_float(v: V) -> f64 {
    match v {
        V::Float(d) => d,
        V::Int(n)   => n as f64,
        other => panic!("expected Float, got {:?}", other),
    }
}

fn as_str(v: V) -> String {
    match v {
        V::Str(s) => s,
        other => panic!("expected Str, got {:?}", other),
    }
}

fn as_bytes(v: V) -> Vec<u8> {
    match v {
        V::Bytes(b) => b,
        other => panic!("expected Bytes, got {:?}", other),
    }
}

fn as_cell(v: V) -> Rc<RefCell<V>> {
    match v {
        V::Cell(c) => c,
        other => panic!("expected Cell, got {:?}", other),
    }
}

fn as_lcell(v: V) -> Rc<RefCell<i64>> {
    match v {
        V::LCell(c) => c,
        // fall back to Cell of Int
        other => panic!("expected LCell, got {:?}", other),
    }
}

fn as_map(v: V) -> Rc<RefCell<HashMap<VKey, V>>> {
    match v {
        V::Map(m) => m,
        other => panic!("expected Map, got {:?}", other),
    }
}

fn as_arr(v: V) -> Rc<RefCell<Vec<V>>> {
    match v {
        V::Arr(a) => a,
        other => panic!("expected Arr, got {:?}", other),
    }
}

fn as_data_tag(v: V) -> String {
    match v {
        V::Data(tag, _) => tag,
        other => panic!("expected Data, got {:?}", other),
    }
}

fn as_data_fields(v: V) -> Vec<V> {
    match v {
        V::Data(_, fields) => fields,
        other => panic!("expected Data, got {:?}", other),
    }
}

// ── Function call ─────────────────────────────────────────────────────────────

fn call_fn(f: V, args: Vec<V>) -> V {
    match f {
        V::Fn(func) => func(args),
        other => panic!("call_fn: not a function: {:?}", other),
    }
}

// ── __arith__ / __method__ dispatchers (FrontendBridge IR) ───────────────────

fn v_unary(op: V, a: V) -> V {
    let op_s: &str = match &op { V::Str(s) => s.as_str(), _ => panic!("v_unary: op must be Str") };
    match op_s {
        "-" | "unary_-" => match a { V::Int(n) => V::Int(-n), V::Float(f) => V::Float(-f), v => panic!("v_unary(-): expected numeric, got {:?}", v) },
        "!" | "unary_!" => V::Bool(!as_bool(a)),
        "~"             => V::Int(!as_int(a)),
        _               => panic!("v_unary: unknown op '{}'", op_s),
    }
}

fn v_arith(op: V, a: V, b: V) -> V {
    let op_s: &str = match &op { V::Str(s) => s.as_str(), _ => panic!("v_arith: op must be Str") };
    match op_s {
        "+"  => v_iadd(a, b),
        "-"  => v_isub(a, b),
        "*"  => v_imul(a, b),
        "/"  => v_idiv(a, b),
        "%"  => v_imod(a, b),
        "==" => v_ieq(a, b),
        "!=" => V::Bool(!as_bool(v_ieq(a, b))),
        "<"  => v_ilt(a, b),
        "<=" => v_ile(a, b),
        ">"  => v_igt(a, b),
        ">=" => v_ige(a, b),
        "++" => v_sconcat(a, b),
        "&&" => V::Bool(as_bool(a) && as_bool(b)),
        "||" => V::Bool(as_bool(a) || as_bool(b)),
        "&"  => V::Int(as_int(a) & as_int(b)),
        "|"  => V::Int(as_int(a) | as_int(b)),
        "^"  => V::Int(as_int(a) ^ as_int(b)),
        "<<" => V::Int(as_int(a) << as_int(b)),
        ">>" => V::Int(as_int(a) >> as_int(b)),
        ">>>"=> V::Int(((as_int(a) as u64) >> (as_int(b) as u64)) as i64),
        _    => panic!("v_arith: unknown op '{}'", op_s),
    }
}

fn v_method(name: V, recv: V, args: Vec<V>) -> V {
    let m: &str = match &name { V::Str(s) => s.as_str(), _ => panic!("v_method: name must be Str") };
    match m {
        "nanoTime" => match recv {
            V::Data(ref tag, _) if tag == "System" => {
                V::Int(std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .expect("nanoTime")
                    .as_nanos() as i64)
            },
            other => panic!("v_method.nanoTime: expected System, got {:?}", other),
        },
        "toInt"    => V::Int(match recv { V::Int(n) => n, V::Float(f) => f as i64, V::Str(s) => s.parse::<i64>().unwrap_or(0), _ => 0 }),
        "toLong"   => V::Int(match recv { V::Int(n) => n, V::Float(f) => f as i64, _ => 0 }),
        "toFloat"  => V::Float(match recv { V::Float(f) => f, V::Int(n) => n as f64, V::Str(s) => s.parse::<f64>().unwrap_or(0.0), _ => 0.0 }),
        "toDouble" => V::Float(match recv { V::Float(f) => f, V::Int(n) => n as f64, _ => 0.0 }),
        "toChar"   => V::Str(match recv { V::Int(n) => char::from_u32(n as u32).map(|c| c.to_string()).unwrap_or_default(), _ => String::new() }),
        "toString" => V::Str(show(&recv)),
        "length"   => V::Int(match &recv { V::Str(s) => s.len() as i64, _ => 0 }),
        "size"     => V::Int(match &recv { V::Str(s) => s.len() as i64, V::Data(_, fs) => fs.len() as i64, _ => 0 }),
        "isEmpty"  => V::Bool(match &recv { V::Str(s) => s.is_empty(), V::Data(t, _) => t == "Nil", _ => false }),
        "nonEmpty" => V::Bool(match &recv { V::Str(s) => !s.is_empty(), V::Data(t, _) => t != "Nil", _ => true }),
        "abs"      => match recv { V::Int(n) => V::Int(n.abs()), V::Float(f) => V::Float(f.abs()), v => v },
        "negate"   => match recv { V::Int(n) => V::Int(-n), V::Float(f) => V::Float(-f), v => v },
        "not"      => V::Bool(!as_bool(recv)),
        "unary_-"  => match recv { V::Int(n) => V::Int(-n), V::Float(f) => V::Float(-f), v => v },
        "unary_!"  => V::Bool(!as_bool(recv)),
        "foreach"  => {
            let f = args.into_iter().next().expect("foreach function");
            let mut cur = recv;
            loop {
                match cur {
                    V::Data(ref tag, ref fields) if tag == "Cons" => {
                        call_fn(f.clone(), vec![fields[0].clone()]);
                        cur = fields[1].clone();
                    },
                    V::Data(ref tag, _) if tag == "Nil" => break,
                    V::Arr(arr) => {
                        for item in arr.borrow().iter().cloned() {
                            call_fn(f.clone(), vec![item]);
                        }
                        break;
                    },
                    other => panic!("foreach: expected list/array, got {:?}", other),
                }
            }
            V::Unit
        },
        _          => panic!("v_method: unknown method '{}'", m),
    }
}

// ── Arithmetic ────────────────────────────────────────────────────────────────

fn v_iadd(a: V, b: V) -> V {
    match (a, b) {
        (V::Int(x), V::Int(y))     => V::Int(x.wrapping_add(y)),
        (V::Float(x), V::Float(y)) => V::Float(x + y),
        (V::Float(x), V::Int(y))   => V::Float(x + y as f64),
        (V::Int(x), V::Float(y))   => V::Float(x as f64 + y),
        (a, b) => V::Int(as_int(a).wrapping_add(as_int(b))),
    }
}

fn v_isub(a: V, b: V) -> V {
    match (a, b) {
        (V::Int(x), V::Int(y))     => V::Int(x.wrapping_sub(y)),
        (V::Float(x), V::Float(y)) => V::Float(x - y),
        (V::Float(x), V::Int(y))   => V::Float(x - (y as f64)),
        (V::Int(x), V::Float(y))   => V::Float((x as f64) - y),
        (a, b) => V::Int(as_int(a).wrapping_sub(as_int(b))),
    }
}

fn v_imul(a: V, b: V) -> V {
    match (a, b) {
        (V::Int(x), V::Int(y))     => V::Int(x.wrapping_mul(y)),
        (V::Float(x), V::Float(y)) => V::Float(x * y),
        (V::Float(x), V::Int(y))   => V::Float(x * (y as f64)),
        (V::Int(x), V::Float(y))   => V::Float((x as f64) * y),
        (a, b) => V::Int(as_int(a).wrapping_mul(as_int(b))),
    }
}

fn v_idiv(a: V, b: V) -> V {
    match (a, b) {
        (V::Int(x), V::Int(y))     => V::Int(x / y),
        (V::Float(x), V::Float(y)) => V::Float(x / y),
        (V::Float(x), V::Int(y))   => V::Float(x / (y as f64)),
        (V::Int(x), V::Float(y))   => V::Float((x as f64) / y),
        (a, b) => V::Int(as_int(a) / as_int(b)),
    }
}

fn v_imod(a: V, b: V) -> V {
    match (a, b) {
        (V::Int(x), V::Int(y))     => V::Int(x % y),
        (V::Float(x), V::Float(y)) => V::Float(x % y),
        (V::Float(x), V::Int(y))   => V::Float(x % (y as f64)),
        (V::Int(x), V::Float(y))   => V::Float((x as f64) % y),
        (a, b) => V::Int(as_int(a) % as_int(b)),
    }
}

fn v_ineg(a: V) -> V {
    match a {
        V::Int(n) => V::Int(n.wrapping_neg()),
        V::Float(d) => V::Float(-d),
        other => V::Int(-as_int(other)),
    }
}

fn v_ieq(a: V, b: V) -> V {
    match (a, b) {
        (V::Int(x), V::Int(y))     => V::Bool(x == y),
        (V::Float(x), V::Float(y)) => V::Bool(x == y),
        (V::Float(x), V::Int(y))   => V::Bool(x == (y as f64)),
        (V::Int(x), V::Float(y))   => V::Bool((x as f64) == y),
        (a, b) => V::Bool(as_int(a) == as_int(b)),
    }
}

fn v_ilt(a: V, b: V) -> V {
    match (a, b) {
        (V::Int(x), V::Int(y))     => V::Bool(x < y),
        (V::Float(x), V::Float(y)) => V::Bool(x < y),
        (V::Float(x), V::Int(y))   => V::Bool(x < (y as f64)),
        (V::Int(x), V::Float(y))   => V::Bool((x as f64) < y),
        (a, b) => V::Bool(as_int(a) < as_int(b)),
    }
}

fn v_ile(a: V, b: V) -> V {
    match (a, b) {
        (V::Int(x), V::Int(y))     => V::Bool(x <= y),
        (V::Float(x), V::Float(y)) => V::Bool(x <= y),
        (V::Float(x), V::Int(y))   => V::Bool(x <= (y as f64)),
        (V::Int(x), V::Float(y))   => V::Bool((x as f64) <= y),
        (a, b) => V::Bool(as_int(a) <= as_int(b)),
    }
}

fn v_igt(a: V, b: V) -> V {
    match (a, b) {
        (V::Int(x), V::Int(y))     => V::Bool(x > y),
        (V::Float(x), V::Float(y)) => V::Bool(x > y),
        (V::Float(x), V::Int(y))   => V::Bool(x > (y as f64)),
        (V::Int(x), V::Float(y))   => V::Bool((x as f64) > y),
        (a, b) => V::Bool(as_int(a) > as_int(b)),
    }
}

fn v_ige(a: V, b: V) -> V {
    match (a, b) {
        (V::Int(x), V::Int(y))     => V::Bool(x >= y),
        (V::Float(x), V::Float(y)) => V::Bool(x >= y),
        (V::Float(x), V::Int(y))   => V::Bool(x >= (y as f64)),
        (V::Int(x), V::Float(y))   => V::Bool((x as f64) >= y),
        (a, b) => V::Bool(as_int(a) >= as_int(b)),
    }
}

// ── String ────────────────────────────────────────────────────────────────────

fn v_sconcat(a: V, b: V) -> V {
    match (a, b) {
        (V::Str(x), V::Str(y)) => V::Str(x + &y),
        // tuple/ADT concat: any two Data values concatenate their fields
        (V::Data(_t1, f1), V::Data(_t2, f2)) => {
            let mut fields = f1;
            fields.extend(f2);
            let n = fields.len();
            V::Data(format!("Tuple{}", n), fields)
        }
        (a, b) => V::Str(show(&a) + &show(&b)),
    }
}

fn v_str_to_i(v: V) -> V {
    let s = as_str(v);
    match s.parse::<i64>() {
        Ok(n) => V::Data("Some".to_string(), vec![V::Int(n)]),
        Err(_) => V::Data("None".to_string(), vec![]),
    }
}

fn v_str_to_f(v: V) -> V {
    let s = as_str(v);
    match s.parse::<f64>() {
        Ok(d) => V::Data("Some".to_string(), vec![V::Float(d)]),
        Err(_) => V::Data("None".to_string(), vec![]),
    }
}

fn v_from_codes(v: V) -> V {
    let mut s = String::new();
    let mut cur = v;
    loop {
        match cur {
            V::Data(ref tag, ref fields) if tag == "Cons" => {
                let code = as_int(fields[0].clone()) as u32;
                s.push(char::from_u32(code).unwrap_or('?'));
                cur = fields[1].clone();
            }
            _ => break,
        }
    }
    V::Str(s)
}

fn v_str_index_of(a: V, b: V) -> V {
    let haystack = as_str(a);
    let needle = as_str(b);
    match haystack.find(&needle) {
        Some(i) => V::Int(i as i64),
        None    => V::Int(-1i64),
    }
}

fn v_str_split(s: V, sep: V) -> V {
    let src = as_str(s);
    let sep = as_str(sep);
    let parts: Vec<&str> = src.split(sep.as_str()).collect();
    let nil: V = V::Data("Nil".to_string(), vec![]);
    parts.iter().rev().fold(nil, |acc, &p| {
        V::Data("Cons".to_string(), vec![V::Str(p.to_string()), acc])
    })
}

fn v_str_lines(s: V) -> V {
    let src = as_str(s);
    let parts: Vec<&str> = src.split('\n').collect();
    let nil: V = V::Data("Nil".to_string(), vec![]);
    parts.iter().rev().fold(nil, |acc, &p| {
        V::Data("Cons".to_string(), vec![V::Str(p.to_string()), acc])
    })
}

// ── Map ───────────────────────────────────────────────────────────────────────

fn v_map_get(m: V, k: V) -> V {
    let key = VKey::from(&k);
    match as_map(m).borrow().get(&key).cloned() {
        Some(v) => V::Data("Some".to_string(), vec![v]),
        None    => V::Data("None".to_string(), vec![]),
    }
}

fn v_map_keys(m: V) -> V {
    let keys: Vec<V> = as_map(m).borrow().keys()
        .map(|k| V::Str(k.0.clone()))
        .collect();
    let nil: V = V::Data("Nil".to_string(), vec![]);
    keys.into_iter().rev().fold(nil, |acc, k| {
        V::Data("Cons".to_string(), vec![k, acc])
    })
}

// ── I/O ───────────────────────────────────────────────────────────────────────

fn v_print(v: V) { print!("{}", show(&v)); }
fn v_println(v: V) { println!("{}", show(&v)); }

fn v_io_args() -> V {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let nil: V = V::Data("Nil".to_string(), vec![]);
    args.into_iter().rev().fold(nil, |acc, s| {
        V::Data("Cons".to_string(), vec![V::Str(s), acc])
    })
}

fn v_io_env(k: V) -> V {
    match std::env::var(as_str(k)) {
        Ok(v)  => V::Data("Some".to_string(), vec![V::Str(v)]),
        Err(_) => V::Data("None".to_string(), vec![]),
    }
}

// ── Bytes ─────────────────────────────────────────────────────────────────────

fn hex_bytes(s: &str) -> Vec<u8> {
    (0..s.len()).step_by(2)
        .map(|i| u8::from_str_radix(&s[i..i+2], 16).unwrap())
        .collect()
}

"""
