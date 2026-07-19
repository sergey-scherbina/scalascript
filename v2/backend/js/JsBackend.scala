package ssc.js

// v2 Core IR → JavaScript code generator.
// Reads Core IR (S-expr text) from stdin or a file argument,
// emits a self-contained .js file to stdout that when run with `node`
// produces the same output as the v2 VM (Runtime.scala).
//
// Usage:
//   ssc1c myfile.ssc | scala-cli run v2/backend/js/ -- /dev/stdin | node

import ssc.{Program, Def, Term, Arm, Const, HandlerDispatchShape, Reader}
import Term.*
import Const.*

@main def main(args: String*): Unit =
  // --ints=number: fast mode — ints are plain JS numbers (safe only while every
  // intermediate stays within ±2^53 and bitwise/shift stays 32-bit; 64-bit
  // wrap-around programs like the bench LCG anti-fold idiom WILL be wrong).
  // Default is --ints=bigint: exact 64-bit semantics.
  JsGen.numberInts = args.contains("--ints=number")
  val rest = args.filterNot(_.startsWith("--ints="))
  val src = rest.headOption match
    case Some("-") | None => scala.io.Source.stdin.mkString
    case Some(path)       => scala.io.Source.fromFile(path)(using scala.io.Codec.UTF8).mkString
  val prog = Reader.parseProgram(src)
  print(JsGen.generate(prog))

object JsGen:

  /** --ints=number fast mode (see main); default false = exact BigInt ints. */
  var numberInts: Boolean = false

  def generate(prog: Program): String =
    val sb = new StringBuilder
    sb.append(preamble)
    emitBridgePrelude(sb, prog)
    sb.append("\n// ── Generated definitions ─────────────────────────────────────────────────\n\n")
    // Declare all def names first (allow forward/mutual references)
    for d <- prog.defs do
      sb.append(s"var ${dn(d.name)};\n")
    sb.append("\n")
    for d <- prog.defs do
      emitDef(sb, d)
    sb.append("\n// ── Entry ───────────────────────────────────────────────────────────────────\n\n")
    // Like the VM's `out(run(prog))`: evaluate entry, print result unless Unit.
    // The VM's out() renders via anyStr (unquoted nested strings), NOT Show.show
    // — $bridgeShow is the anyStr-equivalent (v2-js-anystr-parity).
    sb.append("(function(){\n  var $result=")
    sb.append(genE(prog.entry, Nil, tco = false))
    sb.append(";\n  if($result!==null&&$result!==undefined){ console.log($bridgeShow($result)); }\n})();\n")
    sb.toString

  private def emitBridgePrelude(sb: StringBuilder, prog: Program): Unit =
    val generated = prog.defs.iterator.map(_.name).toSet
    val globals = List(
      "println" -> "function(){ return $bridgePrintln(Array.prototype.slice.call(arguments)); }",
      "print"   -> "function(){ return $bridgePrint(Array.prototype.slice.call(arguments)); }",
      "args"    -> "$ioArgs()",
      "cwd"     -> "process.cwd()",
      "sep"     -> "require('path').sep",
      "platform"-> "{t:'JS',f:[]}",
      "getenv"  -> "function(key, fallback){ var v=process.env[String(key)]; return (v!==undefined&&v!=='')?v:(arguments.length>=2?fallback:''); }"
    )
    val needed = globals.filterNot((name, _) => generated.contains(name))
    if needed.nonEmpty then
      sb.append("\n// ── FrontendBridge standard globals ────────────────────────────────────────\n\n")
      for (name, rhs) <- needed do
        sb.append(s"var ${dn(name)} = $rhs;\n")
      sb.append("\n")

  // ── Naming ───────────────────────────────────────────────────────────────────

  // Safe JS identifier for a def name
  private def dn(n: String): String =
    "$d_" + n.replaceAll("[^a-zA-Z0-9]", "_")

  // Fresh unique variable
  private var _uid = 0
  private def fresh(): String = { _uid += 1; s"$$t${_uid}" }
  private def freshN(n: Int): List[String] = (1 to n).map(_ => fresh()).toList

  // ── Scope ─────────────────────────────────────────────────────────────────────

  // De Bruijn scope: list of JS variable names, newest binding first.
  // Local(i) → scope(i).
  type Scope = List[String]

  private def resolve(scope: Scope, i: Int): String =
    if i < scope.length then scope(i) else s"undefined/*oob$i*/"

  // ── Def emission ──────────────────────────────────────────────────────────────

  private def emitDef(sb: StringBuilder, d: Def): Unit =
    d.body match
      case Lam(arity, body) =>
        val ps = freshN(arity)
        // scope: newest = last param = local(0)
        val scope: Scope = ps.reverse
        val bodyJs = genE(body, scope, tco = true)
        val psStr = ps.mkString(", ")
        sb.append(s"${dn(d.name)} = function($psStr){ return $bodyJs; };\n\n")
      case other =>
        // Value def: evaluated once
        sb.append(s"${dn(d.name)} = ${genE(other, Nil, tco = false)};\n\n")

  // ── Expression generator ─────────────────────────────────────────────────────
  // tco=true  → App in tail position emits $tco(fn,[args]) thunk (trampoline deferred call)
  // tco=false → App in any position emits $c(fn,args) (forces via trampoline)

  private def genE(t: Term, scope: Scope, tco: Boolean): String = t match

    case Lit(CUnit)     => "null"
    case Lit(CBool(b))  => b.toString
    case Lit(CInt(n))   => if numberInts then n.toString else s"${n}n"
    case Lit(CBig(n))   => s"${n}n"
    case Lit(CFloat(d)) =>
      if d.isNaN then "NaN"
      else if d.isPosInfinity then "Infinity"
      else if d.isNegInfinity then "-Infinity"
      else d.toString
    case Lit(CStr(s))   => jsStr(s)
    case Lit(CBytes(b)) =>
      val hex = b.map(x => f"${x & 0xff}%02x").mkString
      if hex.isEmpty then "new Uint8Array(0)"
      else s"""new Uint8Array("$hex".match(/../g).map(function(x){return parseInt(x,16);}))"""

    case Local(i)  => resolve(scope, i)
    case Global(n) => dn(n)

    case Lam(0, body) =>
      // Thunk: captures scope via closure
      val bs = genE(body, scope, tco = true)
      s"function(){ return $bs; }"

    case Lam(arity, body) =>
      val ps = freshN(arity)
      val inner: Scope = ps.reverse ++ scope
      val bs = genE(body, inner, tco = true)
      val psStr = ps.mkString(", ")
      s"function($psStr){ return $bs; }"

    case App(fn, args) =>
      val fnJs  = genE(fn, scope, tco = false)
      val argsJs = args.map(a => genE(a, scope, tco = false))
      if tco then
        s"$$tco($fnJs,[${argsJs.mkString(",")}])"
      else
        s"$$c($fnJs,[${argsJs.mkString(",")}])"

    case Let(rhs, body) =>
      genLet(rhs, body, scope, tco)

    case LetRec(lams, body) =>
      genLetRec(lams, body, scope, tco)

    case If(cond, th, el) =>
      val cv = genE(cond, scope, tco = false)
      val tv = genE(th, scope, tco)
      val ev = genE(el, scope, tco)
      s"($cv?$tv:$ev)"

    case Ctor(tag, Nil) =>
      // Optimise: use singleton-like object for 0-field constructors
      s"{t:${jsStr(tag)},f:[]}"

    case Ctor(tag, fields) =>
      val fs = fields.map(f => genE(f, scope, tco = false)).mkString(",")
      s"{t:${jsStr(tag)},f:[$fs]}"

    case Match(scrut, arms, default) =>
      genMatch(scrut, arms, default, scope, tco)

    case Prim(op, args) =>
      genPrim(op, args, scope)

    case While(cond, body) =>
      // Evaluate cond/body as inline expressions; they read/write cells directly.
      // Wrap in IIFE to return Unit (null).
      val cv = genE(cond, scope, tco = false)
      val bv = genE(body, scope, tco = false)
      s"(function(){ while($cv){ $bv; } return null; })()"

    case Seq(terms) =>
      terms match
        case Nil      => "null"
        case List(t)  => genE(t, scope, tco)
        case ts =>
          val init = ts.init.map(t => genE(t, scope, tco = false)).mkString(",")
          val last = genE(ts.last, scope, tco)
          s"($init,$last)"

  // ── Let binding ─────────────────────────────────────────────────────────────
  // Let([e1,..,eN], body): evaluates e1..eN sequentially (each can see prior bindings).
  // In body: local(0) = eN result, local(1) = e{N-1}, ..., local(N-1) = e1.

  private def genLet(rhs: List[Term], body: Term, scope: Scope, tco: Boolean): String =
    rhs match
      case Nil => genE(body, scope, tco)  // no bindings
      case _ =>
        val vars = freshN(rhs.length)
        val sb   = new StringBuilder("(function(){")
        var cur  = scope
        for (v, r) <- vars.zip(rhs) do
          sb.append(s"var $v=${genE(r, cur, tco = false)};")
          cur = v :: cur
        sb.append(s"return ${genE(body, cur, tco)};")
        sb.append("})()")
        sb.toString

  // ── LetRec binding ───────────────────────────────────────────────────────────
  // LetRec([lam1,..,lamN], body):
  //   local(0)=lamN, ..., local(N-1)=lam1 in both body AND each lam body.

  private def genLetRec(lams: List[Term], body: Term, scope: Scope, tco: Boolean): String =
    val vars = freshN(lams.length)
    // vars(0) → lam1 (local(N-1)), vars(N-1) → lamN (local(0))
    val recScope: Scope = vars.reverse ++ scope
    val sb = new StringBuilder("(function(){")
    for (v, lam) <- vars.zip(lams) do
      // Lam bodies are in tail context (closures)
      sb.append(s"var $v=${genE(lam, recScope, tco = true)};")
    sb.append(s"return ${genE(body, recScope, tco)};")
    sb.append("})()")
    sb.toString

  // ── Match ────────────────────────────────────────────────────────────────────
  // Match(scrut, arms, default): switch on ADT tag.
  // Arm(tag, N, body): N fields; local(0)=f[N-1], ..., local(N-1)=f[0].

  private def genMatch(scrut: Term, arms: List[Arm], default: Option[Term], scope: Scope, tco: Boolean): String =
    val sv  = fresh()
    val scrutJs = genE(scrut, scope, tco = false)
    val armCases = arms.map { arm =>
      val fvars = freshN(arm.arity)
      // f[0]=oldest (local(N-1)), ..., f[N-1]=newest (local(0))
      val armScope: Scope = fvars.reverse ++ scope
      val fieldBinds = fvars.zipWithIndex.map { (fv, k) => s"var $fv=$sv.f[$k];" }.mkString
      val bodyJs = genE(arm.body, armScope, tco)
      s"case ${jsStr(arm.tag)}:{$fieldBinds return $bodyJs;}"
    }.mkString(" ")
    val defaultJs = default match
      case None    => s"throw new Error('match: no arm for '+$sv.t+' ('+$$show($sv)+')');"
      case Some(d) => s"return ${genE(d, scope, tco)};"
    s"(function(){ var $sv=$scrutJs; switch($sv.t){ $armCases default:{$defaultJs} } })()"

  // ── Primitives ───────────────────────────────────────────────────────────────

  private def genPrim(op: String, args: List[Term], scope: Scope): String =
    def a(i: Int) = genE(args(i), scope, tco = false)
    op match
      // Numeric arithmetic — ints are BigInt (64-bit wrapped), floats are numbers;
      // i.* prims are numeric-POLYMORPHIC like the VM's numBin (mixed → float)
      case "i.add"  => if numberInts then s"(${a(0)}+${a(1)})"          else s"$$nadd(${a(0)},${a(1)})"
      case "i.sub"  => if numberInts then s"(${a(0)}-${a(1)})"          else s"$$nsub(${a(0)},${a(1)})"
      case "i.mul"  => if numberInts then s"(${a(0)}*${a(1)})"          else s"$$nmul(${a(0)},${a(1)})"
      case "i.div"  => if numberInts then s"Math.trunc(${a(0)}/${a(1)})" else s"$$ndiv(${a(0)},${a(1)})"
      case "i.mod"  => if numberInts then s"(${a(0)}%${a(1)})"          else s"$$nmod(${a(0)},${a(1)})"
      case "i.neg"  => if numberInts then s"(-(${a(0)}))"               else s"$$nneg(${a(0)})"
      // Numeric comparisons: JS relational ops are mixed bigint/number safe;
      // equality needs loose == (1n === 1 is false)
      case "i.eq"   => s"(${a(0)}==${a(1)})"
      case "i.lt"   => s"(${a(0)}<${a(1)})"
      case "i.le"   => s"(${a(0)}<=${a(1)})"
      case "i.gt"   => s"(${a(0)}>${a(1)})"
      case "i.ge"   => s"(${a(0)}>=${a(1)})"
      // Bitwise
      case "i.and"  => s"(${a(0)}&${a(1)})"
      case "i.or"   => s"(${a(0)}|${a(1)})"
      case "i.xor"  => s"(${a(0)}^${a(1)})"
      case "i.not"  => s"(~${a(0)})"
      case "i.shl"  => if numberInts then s"(${a(0)}<<${a(1)})" else s"BigInt.asIntN(64,(${a(0)}<<(${a(1)}&63n)))"
      case "i.shr"  => if numberInts then s"(${a(0)}>>${a(1)})" else s"(${a(0)}>>(${a(1)}&63n))"
      case "i.ushr" => if numberInts then s"(${a(0)}>>>${a(1)})" else s"BigInt.asIntN(64,(BigInt.asUintN(64,${a(0)})>>(${a(1)}&63n)))"
      // Float arithmetic
      case "f.add"  => s"(${a(0)}+${a(1)})"
      case "f.sub"  => s"(${a(0)}-${a(1)})"
      case "f.mul"  => s"(${a(0)}*${a(1)})"
      case "f.div"  => s"(${a(0)}/${a(1)})"
      case "f.neg"  => s"(-(${a(0)}))"
      case "f.eq"   => s"(${a(0)}===${a(1)})"
      case "f.lt"   => s"(${a(0)}<${a(1)})"
      case "f.le"   => s"(${a(0)}<=${a(1)})"
      case "f.gt"   => s"(${a(0)}>${a(1)})"
      case "f.ge"   => s"(${a(0)}>=${a(1)})"
      case "f.sqrt" => s"Math.sqrt(${a(0)})"
      case "f.floor"=> s"Math.floor(${a(0)})"
      case "f.ceil" => s"Math.ceil(${a(0)})"
      case "f.round"=> s"Math.round(${a(0)})"
      case "f.trunc"=> s"Math.trunc(${a(0)})"
      case "f.isNaN"=> s"Number.isNaN(${a(0)})"
      case "f.isInf"=> s"(!Number.isFinite(${a(0)}))"
      // BigInt
      case "big.add"  => s"(BigInt(${a(0)})+BigInt(${a(1)}))"
      case "big.sub"  => s"(BigInt(${a(0)})-BigInt(${a(1)}))"
      case "big.mul"  => s"(BigInt(${a(0)})*BigInt(${a(1)}))"
      case "big.div"  => s"(BigInt(${a(0)})/BigInt(${a(1)}))"
      case "big.mod"  => s"(BigInt(${a(0)})%BigInt(${a(1)}))"
      case "big.neg"  => s"(-BigInt(${a(0)}))"
      case "big.eq"   => s"(BigInt(${a(0)})===BigInt(${a(1)}))"
      case "big.lt"   => s"(BigInt(${a(0)})<BigInt(${a(1)}))"
      case "big.le"   => s"(BigInt(${a(0)})<=BigInt(${a(1)}))"
      case "big.gt"   => s"(BigInt(${a(0)})>BigInt(${a(1)}))"
      case "big.ge"   => s"(BigInt(${a(0)})>=BigInt(${a(1)}))"
      // Numeric conversions
      case "i->str"  => s"String(${a(0)})"
      case "i->f"    => if numberInts then a(0) else s"Number(${a(0)})"
      case "i->big"  => a(0)
      case "big->i"  => s"BigInt.asIntN(64,${a(0)})"
      case "big->f"  => s"Number(${a(0)})"
      case "big->str"=> s"String(${a(0)})"
      case "f->i"    => if numberInts then s"Math.trunc(${a(0)})" else s"BigInt(Math.trunc(${a(0)}))"
      case "f->big"  => s"BigInt(Math.trunc(${a(0)}))"
      case "f->str"  => s"$$fToStr(${a(0)})"
      // Data reflection
      case "tagOf"   => s"(${a(0)}.t)"
      case "arity"   => s"BigInt(${a(0)}.f.length)"
      // Boolean
      case "not"    => s"(!${a(0)})"
      // String
      case "sconcat"   => s"(''+(${a(0)})+(${a(1)}))"   // works for str+int, int+str etc
      case "seq"       => s"(${a(0)}===${a(1)})"
      case "scmp"      => if numberInts then s"(${a(0)}<${a(1)}?-1:${a(0)}>${a(1)}?1:0)" else s"(${a(0)}<${a(1)}?-1n:${a(0)}>${a(1)}?1n:0n)"
      case "sindexOf"  => if numberInts then s"${a(0)}.indexOf(${a(1)})" else s"BigInt(${a(0)}.indexOf(${a(1)}))"
      case "slen"      => if numberInts then s"${a(0)}.length" else s"BigInt(${a(0)}.length)"
      case "sslice"    => s"${a(0)}.substring(Number(${a(1)}),Number(${a(2)}))"
      case "scodeAt"   => if numberInts then s"${a(0)}.charCodeAt(${a(1)})" else s"BigInt(${a(0)}.charCodeAt(Number(${a(1)})))"
      case "sfromCodes"=> s"$$sfromCodes(${a(0)})"
      case "str.split" => s"$$strSplit(${a(0)},${a(1)})"
      case "str.trim"  => s"${a(0)}.trim()"
      case "str.lines" => s"$$strLines(${a(0)})"
      case "str->i"    => s"$$strToI(${a(0)})"
      case "str->f"    => s"$$strToF(${a(0)})"
      case "str->big"  => s"$$strToBig(${a(0)})"
      case "str->utf8" => s"(new TextEncoder()).encode(${a(0)})"
      // Cells (mutable 1-element arrays; lcell same as cell in JS)
      case "cell.new"   => s"[${a(0)}]"
      case "cell.get"   => s"${a(0)}[0]"
      case "cell.set"   => s"(${a(0)}[0]=${a(1)},null)"
      case "lcell.new"  => s"[${a(0)}]"
      case "lcell.get"  => s"${a(0)}[0]"
      case "lcell.set"  => s"(${a(0)}[0]=${a(1)},null)"
      // Arrays (mutable JS arrays)
      case "arr.new"    => s"[]"
      case "arr.len"    => if numberInts then s"${a(0)}.length" else s"BigInt(${a(0)}.length)"
      case "arr.get"    => s"${a(0)}[Number(${a(1)})]"
      case "arr.set"    => s"(${a(0)}[Number(${a(1)})]=${a(2)},null)"
      case "arr.push"   => s"(${a(0)}.push(${a(1)}),null)"
      case "arr.pop"    => s"${a(0)}.pop()"
      case "arr.slice"  => s"${a(0)}.slice(Number(${a(1)}),Number(${a(2)}))"
      // Maps (wrapper {m: Map[str->val], k: Map[str->origKey]})
      case "map.new"    => s"$$mapNew()"
      case "map.get"    => s"$$mapGet(${a(0)},${a(1)})"
      case "map.put"    => s"$$mapPut(${a(0)},${a(1)},${a(2)})"
      case "map.del"    => s"$$mapDel(${a(0)},${a(1)})"
      case "map.has"    => s"$$mapHas(${a(0)},${a(1)})"
      case "map.keys"   => s"$$mapKeys(${a(0)})"
      case "map.size"   => if numberInts then s"${a(0)}.m.size" else s"BigInt(${a(0)}.m.size)"
      // Bytes (Uint8Array)
      case "bget"    => s"BigInt(${a(0)}[Number(${a(1)})])"
      case "blen"    => s"BigInt(${a(0)}.length)"
      case "bconcat" => s"$$bconcat(${a(0)},${a(1)})"
      case "bslice"  => s"${a(0)}.subarray(Number(${a(1)}),Number(${a(2)}))"
      // fieldAt: direct field access by index
      case "fieldAt" => s"${a(0)}.f[Number(${a(1)})]"
      // IO
      case "io.println"  => s"$$println(${a(0)})"
      case "io.print"    => s"$$print(${a(0)})"
      case "io.eprint"   => s"$$eprint(${a(0)})"
      case "io.args"     => s"$$ioArgs()"
      case "io.exit"     => s"process.exit(Number(${a(0)}))"
      case "io.env"      => s"$$ioEnv(${a(0)})"
      case "io.readFile" => s"$$ioReadFile(${a(0)})"
      case "io.writeFile"=> s"(require('fs').writeFileSync(${a(0)},Buffer.from(${a(1)})),null)"
      // FrontendBridge dynamic/runtime primitives
      case "__autoPrint__" => s"$$autoPrint(${a(0)})"
      case "__match_fail_prim__" => "(function(){ throw new Error('match: no matching case'); })()"
      case HandlerDispatchShape.SelectedPrimitive => s"(${a(0)},null)"
      case HandlerDispatchShape.MissPrimitive =>
        s"(${a(0)},(function(){ throw new Error('match: no matching case'); })())"
      case "__math_obj__" => "$mathObj"
      case "__arith__" => s"$$arith(${a(0)},${a(1)},${a(2)})"
      case "__unary__" => s"$$unary(${a(0)},${a(1)})"
      case "__eq__" => s"$$eq(${a(0)},${a(1)})"
      case "__isTag__" => s"$$isTag(${a(0)},${a(1)},${a(2)})"
      // __method0__ = an APPLIED zero-arg call. $method never eta-expands (its
      // fallthrough throws), so it is exactly __method__ on this backend.
      case "__method__" | "__method0__" =>
        val argsJs = args.map(a => genE(a, scope, tco = false)).mkString(",")
        s"$$method($argsJs)"
      case "__effect__" =>
        val argsJs = args.map(a => genE(a, scope, tco = false)).mkString(",")
        s"$$method($argsJs)"
      case "__mk_arr__" =>
        val argsJs = args.map(a => genE(a, scope, tco = false)).mkString(",")
        s"[$argsJs]"
      case "__mk_map__" =>
        val argsJs = args.map(a => genE(a, scope, tco = false)).mkString(",")
        s"$$mkMap([$argsJs])"
      case "__mk_method_obj__" =>
        // Explicit companion / `object Foo { def m … }` / `given … with {…}`.
        // args = flat [nameLit, lam, nameLit, lam, …] (mirrors the native runtime
        // v2/src/Runtime.scala:2222). Build a method-object the $method dispatch
        // can read (v2-js-imported-method-object-primitive).
        val argsJs = args.map(a => genE(a, scope, tco = false)).mkString(",")
        s"$$mkMethodObj([$argsJs])"
      case "__regfields__" =>
        // The native lowerer emits field-name metadata for the portable VM's
        // untyped by-name dispatch. JS case-class selections are already lowered
        // to index-based fieldAt operations, so there is no registry to populate.
        // Keep the operation explicit (matching Swift) instead of letting it fall
        // through to $prim and crash every native-front case-class program.
        "null"
      case "__isNum2__" => s"($$isNum(${a(0)})&&$$isNum(${a(1)}))"
      case "__mdStrip__" => s"$$mdStrip(${a(0)})"
      // Fallback
      case other =>
        val argsJs = args.map(a => genE(a, scope, tco = false)).mkString(",")
        s"$$prim(${jsStr(other)},[$argsJs])"

  // ── JS string literal ────────────────────────────────────────────────────────

  private def jsStr(s: String): String =
    val sb = new StringBuilder("\"")
    s.foreach {
      case '"'  => sb ++= "\\\""
      case '\\' => sb ++= "\\\\"
      case '\n' => sb ++= "\\n"
      case '\r' => sb ++= "\\r"
      case '\t' => sb ++= "\\t"
      case c if c < 32 => sb ++= f"\\u${c.toInt}%04x"
      case c    => sb += c
    }
    sb += '"'; sb.toString

  // ── Preamble ─────────────────────────────────────────────────────────────────

  private def preamble: String =
    val base = preambleBase
    if numberInts then
      base
        .replace("return {t:'Some',f:[BigInt.asIntN(64,BigInt(s.trim()))]};",
                 "return {t:'Some',f:[Number(s.trim())]};")
        .replace("if(typeof v==='number') return $fToStr(v);",
                 "if(typeof v==='number') return String(v);")
    else base

  private val preambleBase: String = """
"use strict";
// v2 Core IR → JavaScript runtime

// ── Numeric polymorphism (VM numBin semantics: Int×Int→Int wrapped, any Float→Float)
function $nadd(a,b){ var ab=typeof a==='bigint', bb=typeof b==='bigint';
  return ab&&bb ? BigInt.asIntN(64,a+b) : (ab?Number(a):a)+(bb?Number(b):b); }
function $nsub(a,b){ var ab=typeof a==='bigint', bb=typeof b==='bigint';
  return ab&&bb ? BigInt.asIntN(64,a-b) : (ab?Number(a):a)-(bb?Number(b):b); }
function $nmul(a,b){ var ab=typeof a==='bigint', bb=typeof b==='bigint';
  return ab&&bb ? BigInt.asIntN(64,a*b) : (ab?Number(a):a)*(bb?Number(b):b); }
function $ndiv(a,b){ var ab=typeof a==='bigint', bb=typeof b==='bigint';
  return ab&&bb ? BigInt.asIntN(64,a/b) : (ab?Number(a):a)/(bb?Number(b):b); }
function $nmod(a,b){ var ab=typeof a==='bigint', bb=typeof b==='bigint';
  return ab&&bb ? (a%b) : (ab?Number(a):a)%(bb?Number(b):b); }
function $nneg(a){ return typeof a==='bigint' ? BigInt.asIntN(64,-a) : -a; }

// ── Trampoline ───────────────────────────────────────────────────────────────
// Every closure call goes through $c which drives the trampoline.
// Tail calls (tco=true in codegen) return $tco thunks instead of calling directly.
function $tco(fn,args){ return {$k:fn,$a:args}; }
function $c(fn,args){
  if(typeof fn!=='function') return $apply(fn,args);
  var r=fn.apply(null,args);
  while(r!==null&&r!==undefined&&typeof r==='object'&&r.$k!==undefined){
    r=r.$k.apply(null,r.$a);
  }
  return r;
}
function $apply(fn,args){
  if($isList(fn) && args.length===1) return $listGet(fn, Number(args[0]));
  if(Array.isArray(fn) && args.length===1) return fn[Number(args[0])];
  // Map.apply(key): return the value or fail CLOSED (Scala NoSuchElementException),
  // never a silent wrong value. Mirrors Runtime.scala applyFallback MapV(entries).
  if($isMapV(fn) && args.length===1){
    var sk=$mapKey(args[0]);
    if(fn.m.has(sk)) return fn.m.get(sk);
    throw new Error('key not found: '+$bridgeShow(args[0]));
  }
  throw new Error('not callable: '+$show(fn));
}

// ── FrontendBridge compatibility helpers ────────────────────────────────────
function $isList(v){ return v!==null&&v!==undefined&&typeof v==='object'&&(v.t==='Cons'||v.t==='Nil'); }
function $listToArray(v){
  var out=[]; var cur=v;
  while(cur&&cur.t==='Cons'){ out.push(cur.f[0]); cur=cur.f[1]; }
  if(!cur||cur.t!=='Nil') throw new Error('expected list, got '+$show(v));
  return out;
}
function $listOf(items){
  var r={t:'Nil',f:[]};
  for(var i=items.length-1;i>=0;i--) r={t:'Cons',f:[items[i],r]};
  return r;
}
function $listGet(v,n){
  var cur=v; var i=n;
  while(cur&&cur.t==='Cons'){
    if(i===0) return cur.f[0];
    i--; cur=cur.f[1];
  }
  throw new Error('list index out of bounds: '+n);
}
function $isNum(v){ return typeof v==='bigint'||typeof v==='number'; }
function $asNumber(v){ return typeof v==='bigint'?Number(v):v; }
function $asIntLike(v){ return typeof v==='bigint'?v:BigInt(Math.trunc(v)); }
function $bridgeShow(v){
  if(v===null||v===undefined) return '()';
  if(typeof v==='string') return v;
  if(typeof v==='number') return $fToStr(v);
  if(typeof v==='bigint') return String(v);
  if(typeof v==='boolean') return String(v);
  if($isList(v)) return 'List('+$listToArray(v).map($bridgeShow).join(', ')+')';
  if(v instanceof Uint8Array) return $show(v);
  if($isMapV(v)){ var ps=[]; v.k.forEach(function(ok,sk){ ps.push($bridgeShow(ok)+' -> '+$bridgeShow(v.m.get(sk))); }); return 'Map('+ps.join(', ')+')'; }
  if(Array.isArray(v)) return '<arr>';
  if(v&&v.t!==undefined){
    if(v.t==='_Raw'&&v.f&&v.f.length) return $bridgeShow(v.f[0]);
    if(/^Tuple\d+$/.test(v.t)) return '('+v.f.map($bridgeShow).join(', ')+')';
    if(!v.f||v.f.length===0) return v.t;
    return v.t+'('+v.f.map($bridgeShow).join(', ')+')';
  }
  return $show(v);
}
function $bridgePrintln(args){
  if(args.length===0) console.log('');
  else console.log($bridgeShow(args[0]));
  return null;
}
function $bridgePrint(args){
  if(args.length!==0) process.stdout.write($bridgeShow(args[0]));
  return null;
}
function $autoPrint(v){
  if(v===null||v===undefined) return null;
  if(v&&v.t==='Op') return null;
  console.log($bridgeShow(v));
  return null;
}
var $mathObj={__math:true};
function $eq(a,b){
  if(a===b) return true;
  if(typeof a!==typeof b) return (typeof a==='bigint'&&typeof b==='number'&&Number(a)===b)||
    (typeof a==='number'&&typeof b==='bigint'&&a===Number(b));
  if(a&&b&&a.t!==undefined&&b.t!==undefined){
    if(a.t!==b.t||a.f.length!==b.f.length) return false;
    for(var i=0;i<a.f.length;i++) if(!$eq(a.f[i],b.f[i])) return false;
    return true;
  }
  if($isList(a)&&$isList(b)){
    var ax=$listToArray(a), bx=$listToArray(b);
    if(ax.length!==bx.length) return false;
    for(var j=0;j<ax.length;j++) if(!$eq(ax[j],bx[j])) return false;
    return true;
  }
  return false;
}
function $isTag(v,tag,arity){ return !!(v&&v.t===tag&&v.f&&v.f.length===Number(arity)); }
// Value ordering — mirrors Runtime.scala valueLessThan (scalars + tuples lexicographic).
function $lt(x,y){
  if(typeof x==='bigint'&&typeof y==='bigint') return x<y;
  if($isNum(x)&&$isNum(y)) return $asNumber(x)<$asNumber(y);
  if(typeof x==='string'&&typeof y==='string') return x<y;
  if(x&&y&&x.t!==undefined&&y.t!==undefined&&/^Tuple\d+$/.test(x.t)&&x.t===y.t&&x.f&&y.f){
    for(var i=0;i<x.f.length;i++){ if($lt(x.f[i],y.f[i])) return true; if($lt(y.f[i],x.f[i])) return false; }
    return false;
  }
  return false;
}
function $cmp(x,y){ return $lt(x,y)?-1:($lt(y,x)?1:0); }
// Tuple-spreading call: a multi-param lambda over a list of tuples gets the
// tuple fields spread as separate args (mirrors Runtime.scala map/flatMap step).
function $spreadCall(fn,x){
  if(typeof fn==='function'&&fn.length>1&&x&&x.t!==undefined&&(x.t==='Pair'||/^Tuple/.test(x.t))&&x.f&&x.f.length===fn.length)
    return $c(fn,x.f);
  return $c(fn,[x]);
}
function $some(v){ return {t:'Some',f:[v]}; }
var $none={t:'None',f:[]};
function $unary(op,v){
  if(op==='-') return typeof v==='bigint'?BigInt.asIntN(64,-v):(-v);
  if(op==='~') return typeof v==='bigint'?BigInt.asIntN(64,~v):(~v);
  if(op==='!') return !v;
  throw new Error('__unary__: '+op+' on '+$show(v));
}
function $arith(op,l,r){
  if(op==='->') return {t:'Tuple2',f:[l,r]};
  // Map + pair → updated map (Runtime.scala applyBinOp MapV + Tuple2/Pair).
  if(op==='+'&&$isMapV(l)&&l!==null&&r&&r.t!==undefined&&(r.t==='Tuple2'||r.t==='Pair')){
    var $n=$mapClone(l); $mapPut($n,r.f[0],r.f[1]); return $n;
  }
  if(op==='to'||op==='until'){
    var start=Number(l), end=Number(r), arr=[];
    for(var i=start; op==='to'?i<=end:i<end; i++) arr.push(typeof l==='bigint'||typeof r==='bigint'?BigInt(i):i);
    return $listOf(arr);
  }
  if(typeof l==='bigint'&&typeof r==='bigint'){
    switch(op){
      case '+': return BigInt.asIntN(64,l+r); case '-': return BigInt.asIntN(64,l-r);
      case '*': return BigInt.asIntN(64,l*r); case '/': return BigInt.asIntN(64,l/r);
      case '%': return l%r; case '==': return l===r; case '!=': return l!==r;
      case '<': return l<r; case '<=': return l<=r; case '>': return l>r; case '>=': return l>=r;
      case '&': return l&r; case '|': return l|r; case '^': return l^r;
      case '<<': return BigInt.asIntN(64,l<<(r&63n)); case '>>': return l>>(r&63n);
      case '>>>': return BigInt.asIntN(64,(BigInt.asUintN(64,l)>>(r&63n)));
      case '++': return String(l)+String(r);
    }
  }
  if($isNum(l)&&$isNum(r)){
    var x=$asNumber(l), y=$asNumber(r);
    switch(op){
      case '+': return x+y; case '-': return x-y; case '*': return x*y; case '/': return x/y; case '%': return x%y;
      case '==': return x===y; case '!=': return x!==y;
      case '<': return x<y; case '<=': return x<=y; case '>': return x>y; case '>=': return x>=y;
      case '++': return $bridgeShow(l)+$bridgeShow(r);
    }
  }
  if(typeof l==='string'&&typeof r==='string'){
    switch(op){
      case '+': case '++': return l+r; case '==': return l===r; case '!=': return l!==r;
      case '<': return l<r; case '<=': return l<=r; case '>': return l>r; case '>=': return l>=r;
    }
  }
  if((typeof l==='string'||typeof r==='string')&&(op==='+'||op==='++')) return $bridgeShow(l)+$bridgeShow(r);
  if(typeof l==='boolean'&&typeof r==='boolean'){
    if(op==='==') return l===r; if(op==='!=') return l!==r;
  }
  if($isList(l)&&(op==='++'||op==='+')) return $listOf($listToArray(l).concat($listToArray(r)));
  if($isList(l)&&op===':+') return $listOf($listToArray(l).concat([r]));
  if(op==='==') return $eq(l,r);
  if(op==='!=') return !$eq(l,r);
  if(op==='+'||op==='++') return $bridgeShow(l)+$bridgeShow(r);
  return null;
}
function $method(name,recv){
  var args=Array.prototype.slice.call(arguments,2);
  if(recv&&recv.$mo!==undefined){
    var $mm=recv.$mo;
    if(Object.prototype.hasOwnProperty.call($mm,name)){
      var f=$mm[name];
      if(typeof f==='function'){ if(args.length===0&&f.length>0) return f; return $c(f,args); }
      if(args.length===0) return f;
    }
  }
  if(name==='asInstanceOf') return recv;
  if(/^_\d+$/.test(name)&&recv&&recv.f) return recv.f[Number(name.substring(1))-1];
  if(recv===$mathObj){
    switch(name){
      case 'Pi': return Math.PI; case 'E': return Math.E;
      case 'abs': return typeof args[0]==='bigint' ? (args[0]<0n?-args[0]:args[0]) : Math.abs(args[0]);
      case 'round': return BigInt(Math.round($asNumber(args[0])));
      case 'floor': return Math.floor($asNumber(args[0])); case 'ceil': return Math.ceil($asNumber(args[0]));
      case 'sqrt': return Math.sqrt($asNumber(args[0])); case 'pow': return Math.pow($asNumber(args[0]),$asNumber(args[1]));
      case 'sin': return Math.sin($asNumber(args[0])); case 'cos': return Math.cos($asNumber(args[0]));
      case 'tan': return Math.tan($asNumber(args[0])); case 'log': return Math.log($asNumber(args[0]));
      case 'log10': return Math.log10($asNumber(args[0])); case 'exp': return Math.exp($asNumber(args[0]));
      case 'min': return (args[0]<args[1]?args[0]:args[1]); case 'max': return (args[0]>args[1]?args[0]:args[1]);
    }
  }
  if(typeof recv==='bigint'){
    switch(name){
      case 'toString': return String(recv); case 'toInt': case 'toLong': return recv;
      case 'toDouble': case 'toFloat': return Number(recv); case 'abs': return recv<0n?-recv:recv;
    }
  }
  if(typeof recv==='number'){
    switch(name){
      case 'toString': return $fToStr(recv); case 'toInt': case 'toLong': return BigInt(Math.trunc(recv));
      case 'toDouble': case 'toFloat': return recv; case 'abs': return Math.abs(recv);
    }
  }
  if(typeof recv==='string'){
    switch(name){
      case 'length': case 'size': return BigInt(recv.length);
      case 'isEmpty': return recv.length===0; case 'nonEmpty': return recv.length!==0;
      case 'toString': return recv; case 'toInt': return BigInt(recv.trim());
      case 'toIntOption': try{return {t:'Some',f:[BigInt(recv.trim())]};}catch(e){return {t:'None',f:[]};}
      case 'toDouble': return Number(recv.trim());
      case 'trim': return recv.trim(); case 'toUpperCase': return recv.toUpperCase(); case 'toLowerCase': return recv.toLowerCase();
      case 'contains': return recv.indexOf(args[0])>=0; case 'startsWith': return recv.startsWith(args[0]); case 'endsWith': return recv.endsWith(args[0]);
      case 'substring': return args.length===1?recv.substring(Number(args[0])):recv.substring(Number(args[0]),Number(args[1]));
      case 'charAt': return BigInt(recv.charCodeAt(Number(args[0])));
      case 'indexOf': return BigInt(args.length===1?recv.indexOf(args[0]):recv.indexOf(args[0],Number(args[1])));
      case 'split': return $listOf(recv.split(args[0]));
    }
  }
  if($isList(recv)){
    var xs=$listToArray(recv);
    // Curried z-then-op forms: `xs.foldLeft(z)(op)` lowers to
    // App(__method__(foldLeft,xs,z),[op]); with only `z` here (args.length===1)
    // return a fn taking `op` — mirrors Runtime.scala:3255-3263.
    if(args.length===1&&(name==='foldLeft'||name==='foldRight'||name==='scanLeft')){
      var $z=args[0], $nm=name, $recv=recv;
      return function(op){ return $method($nm,$recv,$z,op); };
    }
    switch(name){
      case 'length': case 'size': return BigInt(xs.length);
      case 'isEmpty': return xs.length===0; case 'nonEmpty': return xs.length!==0;
      case 'head': if(xs.length===0) throw new Error('head on empty list'); return xs[0];
      case 'tail': if(xs.length===0) throw new Error('tail on empty list'); return $listOf(xs.slice(1));
      case 'last': if(xs.length===0) throw new Error('last on empty list'); return xs[xs.length-1];
      case 'init': if(xs.length===0) throw new Error('init on empty list'); return $listOf(xs.slice(0,xs.length-1));
      case 'headOption': return xs.length===0?$none:$some(xs[0]);
      case 'lastOption': return xs.length===0?$none:$some(xs[xs.length-1]);
      case 'mkString':
        if(args.length===0) return xs.map($bridgeShow).join('');
        if(args.length===1) return xs.map($bridgeShow).join(String(args[0]));
        return String(args[0])+xs.map($bridgeShow).join(String(args[1]))+String(args[2]);
      case 'reverse': return $listOf(xs.slice().reverse());
      case 'distinct': { var seen=[],out=[]; xs.forEach(function(x){ if(!seen.some(function(s){return $eq(s,x);})){seen.push(x);out.push(x);} }); return $listOf(out); }
      case 'foreach': xs.forEach(function(x){ $c(args[0],[x]); }); return null;
      case 'map': return $listOf(xs.map(function(x){ return $spreadCall(args[0],x); }));
      case 'flatMap': { var o=[]; xs.forEach(function(x){ var r=$spreadCall(args[0],x); if($isList(r)) o=o.concat($listToArray(r)); else o.push(r); }); return $listOf(o); }
      case 'filter': return $listOf(xs.filter(function(x){ return $c(args[0],[x])===true; }));
      case 'filterNot': return $listOf(xs.filter(function(x){ return $c(args[0],[x])!==true; }));
      case 'foldLeft': return xs.reduce(function(a,x){ return $c(args[1],[a,x]); }, args[0]);
      case 'foldRight': { var a=args[0]; for(var i=xs.length-1;i>=0;i--) a=$c(args[1],[xs[i],a]); return a; }
      case 'scanLeft': { var acc=args[0], o=[acc]; xs.forEach(function(x){ acc=$c(args[1],[acc,x]); o.push(acc); }); return $listOf(o); }
      case 'reduce': case 'reduceLeft':
        if(xs.length===0) throw new Error('reduceLeft on empty list');
        return xs.reduce(function(a,x){ return $c(args[0],[a,x]); });
      case 'reduceRight':
        if(xs.length===0) throw new Error('reduceRight on empty list');
        { var a=xs[xs.length-1]; for(var i=xs.length-2;i>=0;i--) a=$c(args[0],[xs[i],a]); return a; }
      case 'find': { for(var i=0;i<xs.length;i++) if($c(args[0],[xs[i]])===true) return $some(xs[i]); return $none; }
      case 'exists': return xs.some(function(x){ return $c(args[0],[x])===true; });
      case 'forall': return xs.every(function(x){ return $c(args[0],[x])===true; });
      case 'count': { var n=0n; xs.forEach(function(x){ if($c(args[0],[x])===true) n++; }); return n; }
      case 'sum': return xs.reduce(function(a,b){ return $arith('+',a,b); }, 0n);
      case 'min': if(xs.length===0) throw new Error('min on empty list'); return xs.reduce(function(a,b){ return $cmp(a,b)<=0?a:b; });
      case 'max': if(xs.length===0) throw new Error('max on empty list'); return xs.reduce(function(a,b){ return $cmp(a,b)>=0?a:b; });
      case 'minBy': if(xs.length===0) throw new Error('minBy on empty list'); return xs.reduce(function(a,b){ return $cmp($c(args[0],[a]),$c(args[0],[b]))<=0?a:b; });
      case 'maxBy': if(xs.length===0) throw new Error('maxBy on empty list'); return xs.reduce(function(a,b){ return $cmp($c(args[0],[a]),$c(args[0],[b]))>=0?a:b; });
      case 'sorted': return $listOf(xs.slice().sort($cmp));
      case 'sortBy': { var f=args[0]; return $listOf(xs.slice().sort(function(a,b){ return $cmp($c(f,[a]),$c(f,[b])); })); }
      case 'sortWith': { var f=args[0]; return $listOf(xs.slice().sort(function(a,b){ return $c(f,[a,b])===true?-1:($c(f,[b,a])===true?1:0); })); }
      case 'groupBy': { var ks=[],grp=[]; xs.forEach(function(x){ var k=$c(args[0],[x]); var idx=-1; for(var i=0;i<ks.length;i++) if($eq(ks[i],k)){idx=i;break;} if(idx<0){ks.push(k);grp.push([x]);} else grp[idx].push(x); }); var pairs=ks.map(function(k,i){ return {t:'Tuple2',f:[k,$listOf(grp[i])]}; }); return $listOf(pairs); }
      case 'partition': { var yes=[],no=[]; xs.forEach(function(x){ if($c(args[0],[x])===true) yes.push(x); else no.push(x); }); return {t:'Tuple2',f:[$listOf(yes),$listOf(no)]}; }
      case 'span': { var i=0; while(i<xs.length&&$c(args[0],[xs[i]])===true) i++; return {t:'Tuple2',f:[$listOf(xs.slice(0,i)),$listOf(xs.slice(i))]}; }
      case 'splitAt': { var n=Number(args[0]); return {t:'Tuple2',f:[$listOf(xs.slice(0,n)),$listOf(xs.slice(n))]}; }
      case 'takeWhile': { var i=0; while(i<xs.length&&$c(args[0],[xs[i]])===true) i++; return $listOf(xs.slice(0,i)); }
      case 'dropWhile': { var i=0; while(i<xs.length&&$c(args[0],[xs[i]])===true) i++; return $listOf(xs.slice(i)); }
      case 'take': return $listOf(xs.slice(0,Number(args[0])));
      case 'drop': return $listOf(xs.slice(Number(args[0])));
      case 'takeRight': { var n=Number(args[0]); return $listOf(n<=0?[]:xs.slice(xs.length-n)); }
      case 'dropRight': { var n=Number(args[0]); return $listOf(xs.slice(0,Math.max(0,xs.length-n))); }
      case 'slice': return $listOf(xs.slice(Number(args[0]),Number(args[1])));
      case 'indices': return $listOf(xs.map(function(_,i){ return BigInt(i); }));
      case 'zipWithIndex': return $listOf(xs.map(function(x,i){ return {t:'Tuple2',f:[x,BigInt(i)]}; }));
      case 'zip': { var ys=$listToArray(args[0]),o=[]; for(var i=0;i<Math.min(xs.length,ys.length);i++) o.push({t:'Tuple2',f:[xs[i],ys[i]]}); return $listOf(o); }
      case 'indexWhere': { for(var i=0;i<xs.length;i++) if($c(args[0],[xs[i]])===true) return BigInt(i); return -1n; }
      case 'indexOf': { for(var i=0;i<xs.length;i++) if($eq(xs[i],args[0])) return BigInt(i); return -1n; }
      case 'contains': return xs.some(function(x){ return $eq(x,args[0]); });
      case 'updated': { var o=xs.slice(); o[Number(args[0])]=args[1]; return $listOf(o); }
      case 'patch': { var from=Number(args[0]),rep=Number(args[2]); return $listOf(xs.slice(0,from).concat($listToArray(args[1]),xs.slice(from+rep))); }
      case 'grouped': { var n=Number(args[0]),o=[]; for(var i=0;i<xs.length;i+=n) o.push($listOf(xs.slice(i,i+n))); return $listOf(o); }
      case 'sliding': { var n=Number(args[0]),o=[]; if(xs.length<n){ if(xs.length>0) o.push($listOf(xs.slice())); } else for(var i=0;i+n<=xs.length;i++) o.push($listOf(xs.slice(i,i+n))); return $listOf(o); }
      case 'flatten': { var o=[]; xs.forEach(function(x){ o=o.concat($listToArray(x)); }); return $listOf(o); }
      case '++': return $listOf(xs.concat($listToArray(args[0])));
      case ':+': case 'appended': return $listOf(xs.concat([args[0]]));
      case '+:': return $listOf([args[0]].concat(xs));
      case 'toList': case 'toVector': case 'iterator': return recv;
      case 'toSet': { var seen=[],out=[]; xs.forEach(function(x){ if(!seen.some(function(s){return $eq(s,x);})){seen.push(x);out.push(x);} }); return $listOf(out); }
    }
  }
  if($isMapV(recv)){
    switch(name){
      case 'size': return BigInt(recv.m.size);
      case 'isEmpty': return recv.m.size===0; case 'nonEmpty': return recv.m.size!==0;
      case 'get': return $mapGet(recv,args[0]);
      case 'getOrElse': { var sk=$mapKey(args[0]); return recv.m.has(sk)?recv.m.get(sk):args[1]; }
      case 'apply': { var sk=$mapKey(args[0]); if(recv.m.has(sk)) return recv.m.get(sk); throw new Error('key not found: '+$bridgeShow(args[0])); }
      case 'contains': return $mapHas(recv,args[0]);
      case 'keys': case 'keySet': return $mapKeys(recv);
      case 'values': return $mapValues(recv);
      case 'toList': case 'toSeq': return $mapToList(recv);
      case 'updated': { var n=$mapClone(recv); $mapPut(n,args[0],args[1]); return n; }
      case 'removed': { var n=$mapClone(recv); $mapDel(n,args[0]); return n; }
      case '+': { var n=$mapClone(recv); var p=args[0]; if(p&&(p.t==='Tuple2'||p.t==='Pair')) $mapPut(n,p.f[0],p.f[1]); else throw new Error('Map + expects a pair'); return n; }
      // NB: MapV.foldLeft/foreach are intentionally ABSENT to match the native VM,
      // which errors "no dispatch for .foldLeft on Map(...)" — the front lowers
      // `m.foldLeft(z)(op)` curried and the kernel has no 1-arg MapV curry (unlike
      // List). Kept at parity; the kernel gap is filed separately.
    }
    // Row-style field access: `row.col` on a string-keyed map (Db rows). Look up by
    // key name, case-insensitive fallback (SQL column labels); fail CLOSED on miss.
    var allStr=true; recv.k.forEach(function(ok){ if(typeof ok!=='string') allStr=false; });
    if(allStr&&args.length===0){
      var sk=$mapKey(name); if(recv.m.has(sk)) return recv.m.get(sk);
      var hit; recv.k.forEach(function(ok,k2){ if(hit===undefined&&typeof ok==='string'&&ok.toLowerCase()===name.toLowerCase()) hit=recv.m.get(k2); });
      if(hit!==undefined) return hit;
      var cols=[]; recv.k.forEach(function(ok){ cols.push($bridgeShow(ok)); });
      throw new Error("__method__: no column '"+name+"' in row ["+cols.join(',')+"]");
    }
  }
  if(Array.isArray(recv)){
    if(name==='length'||name==='size') return BigInt(recv.length);
    if(name==='toList') return $listOf(recv);
  }
  throw new Error('__method__: no dispatch for .'+name+' on '+$show(recv));
}
function $mkMethodObj(pairs){
  var mo={};
  for(var i=0;i+1<pairs.length;i+=2) mo[pairs[i]]=pairs[i+1];
  return {$mo:mo};
}
function $mkMap(pairs){
  var m=$mapNew();
  pairs.forEach(function(p){
    if(p&&p.t==='Tuple2') $mapPut(m,p.f[0],p.f[1]);
    else if(p&&p.t==='->') $mapPut(m,p.f[0],p.f[1]);
    else throw new Error('Map factory: expected pair, got '+$show(p));
  });
  return m;
}
function $mdStrip(s){
  if(typeof s!=='string') return s;
  var lines=s.split('\n');
  while(lines.length&&lines[0].trim()==='') lines.shift();
  while(lines.length&&lines[lines.length-1].trim()==='') lines.pop();
  if(lines.length===0) return '';
  var min=null;
  lines.forEach(function(l){ if(l.trim()!==''){ var n=(l.match(/^ */)||[''])[0].length; min=min===null?n:Math.min(min,n); } });
  min=min||0;
  return lines.map(function(l){ return l.trim()===''?'':l.slice(min); }).join('\n');
}

// ── Show (value → display string) ────────────────────────────────────────────
function $show(v){
  if(v===null||v===undefined) return "()";
  if(typeof v==='boolean') return String(v);
  if(typeof v==='number') return $fToStr(v);
  if(typeof v==='bigint') return String(v);
  if(typeof v==='string') return '"'+v+'"';
  if(v instanceof Uint8Array){
    var h='#'; for(var i=0;i<v.length;i++){var b=v[i].toString(16);h+=(b.length<2?'0':'')+b;} return h;
  }
  if(Array.isArray(v)) return '<arr>';
  if(v.$k!==undefined) return '<tco>';
  if($isMapV(v)) return $mapShow(v);
  if(v.t!==undefined){
    if(v.t==='Cons'||v.t==='Nil'){
      var items=[]; for(var cur=v;cur.t==='Cons';cur=cur.f[1]) items.push($show(cur.f[0]));
      return 'List('+items.join(', ')+')';
    }
    // Tuples render Scala-style: (a, b) not Tuple2(a, b) — matches VM Show.
    if(/^Tuple\d+$/.test(v.t)) return '('+v.f.map($show).join(', ')+')';
    if(!v.f||v.f.length===0) return v.t;
    return v.t+'('+v.f.map($show).join(', ')+')';
  }
  return String(v);
}

// ── IO ────────────────────────────────────────────────────────────────────────
function $showIO(v){
  // io.* rendering mirrors the VM's out()/anyStr: strings raw, containers with
  // UNQUOTED nested strings (List(a, b) / Map(k -> v) / Some(x)), NOT Show.show
  // (which quotes). $bridgeShow is that anyStr-equivalent renderer.
  if(typeof v==='string') return v;
  return $bridgeShow(v);
}
function $println(v){ console.log($showIO(v)); return null; }
function $print(v){ process.stdout.write($showIO(v)); return null; }
function $eprint(v){ process.stderr.write($showIO(v)+'\n'); return null; }
function $ioArgs(){
  // argv: skip 'node' and the script file name
  var args=process.argv.slice(2);
  var r={t:'Nil',f:[]};
  for(var i=args.length-1;i>=0;i--) r={t:'Cons',f:[args[i],r]};
  return r;
}
function $ioEnv(name){
  var v=process.env[name];
  return v===undefined?{t:'None',f:[]}:{t:'Some',f:[v]};
}
function $ioReadFile(path){
  return new Uint8Array(require('fs').readFileSync(path));
}

// ── String helpers ────────────────────────────────────────────────────────────
function $strSplit(s,delim){
  var parts=s.split(delim);
  var r={t:'Nil',f:[]};
  for(var i=parts.length-1;i>=0;i--) r={t:'Cons',f:[parts[i],r]};
  return r;
}
function $strLines(s){ return $strSplit(s,'\n'); }
function $strToI(s){
  if(!/^-?\d+$/.test(s.trim())) return {t:'None',f:[]};
  return {t:'Some',f:[BigInt.asIntN(64,BigInt(s.trim()))]};
}
function $strToF(s){
  var n=Number(s);
  return (s.trim()===''||Number.isNaN(n))?{t:'None',f:[]}:{t:'Some',f:[n]};
}
function $strToBig(s){
  try{ return {t:'Some',f:[BigInt(s)]}; }catch(e){ return {t:'None',f:[]}; }
}
function $sfromCodes(list){
  var codes=[];
  for(var cur=list;cur.t==='Cons';cur=cur.f[1]) codes.push(Number(cur.f[0]));
  return String.fromCharCode.apply(null,codes);
}
// VM floatStr semantics: whole doubles collapse ("3", not "3.0"); nan/inf lowercase.
function $fToStr(v){
  if(Number.isNaN(v)) return 'nan';
  if(v===Infinity) return 'inf';
  if(v===-Infinity) return '-inf';
  return String(v);
}

// ── Map (tracks original keys for map.keys) ──────────────────────────────────
// Representation: {m: Map<stringKey,value>, k: Map<stringKey,origKey>}
function $mapKey(v){
  if(v===null||v===undefined) return '()';
  if(typeof v==='number'||typeof v==='boolean'||typeof v==='bigint') return typeof v+':'+v;
  if(typeof v==='string') return 's:'+v;
  if(v.t!==undefined) return 'a:'+v.t+'['+v.f.map($mapKey).join(',')+']';
  return 'o:'+String(v);
}
function $mapNew(){ return {m:new Map(),k:new Map()}; }
function $isMapV(v){ return v!==null&&typeof v==='object'&&v.m instanceof Map&&v.k instanceof Map; }
function $mapClone(map){ var n=$mapNew(); map.k.forEach(function(ok,sk){ n.m.set(sk,map.m.get(sk)); n.k.set(sk,ok); }); return n; }
function $mapValues(map){ var vs=[]; map.k.forEach(function(ok,sk){ vs.push(map.m.get(sk)); }); return $listOf(vs); }
function $mapToList(map){ var ps=[]; map.k.forEach(function(ok,sk){ ps.push({t:'Tuple2',f:[ok,map.m.get(sk)]}); }); return $listOf(ps); }
function $mapShow(map){ var ps=[]; map.k.forEach(function(ok,sk){ ps.push($show(ok)+' -> '+$show(map.m.get(sk))); }); return 'Map('+ps.join(', ')+')'; }
function $mapGet(map,key){
  var sk=$mapKey(key);
  return map.m.has(sk)?{t:'Some',f:[map.m.get(sk)]}:{t:'None',f:[]};
}
function $mapPut(map,key,val){ var sk=$mapKey(key); map.m.set(sk,val); map.k.set(sk,key); return null; }
function $mapDel(map,key){ var sk=$mapKey(key); map.m.delete(sk); map.k.delete(sk); return null; }
function $mapHas(map,key){ return map.m.has($mapKey(key)); }
function $mapKeys(map){
  var r={t:'Nil',f:[]};
  var ks=[...map.k.values()].reverse();
  for(var i=0;i<ks.length;i++) r={t:'Cons',f:[ks[i],r]};
  return r;
}

// ── Bytes ─────────────────────────────────────────────────────────────────────
function $bconcat(a,b){
  var r=new Uint8Array(a.length+b.length);
  r.set(a,0); r.set(b,a.length); return r;
}

// ── Fallback ──────────────────────────────────────────────────────────────────
function $prim(op,args){ throw new Error('unimplemented primitive: '+op); }
"""
