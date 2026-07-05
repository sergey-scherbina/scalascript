package ssc.js

// v2 Core IR → JavaScript code generator.
// Reads Core IR (S-expr text) from stdin or a file argument,
// emits a self-contained .js file to stdout that when run with `node`
// produces the same output as the v2 VM (Runtime.scala).
//
// Usage:
//   ssc1c myfile.ssc | scala-cli run v2/backend/js/ -- /dev/stdin | node

import ssc.{Program, Def, Term, Arm, Const, Reader}
import Term.*
import Const.*

@main def main(args: String*): Unit =
  val src = args.headOption match
    case Some("-") | None => scala.io.Source.stdin.mkString
    case Some(path)       => scala.io.Source.fromFile(path)(using scala.io.Codec.UTF8).mkString
  val prog = Reader.parseProgram(src)
  print(JsGen.generate(prog))

object JsGen:

  def generate(prog: Program): String =
    val sb = new StringBuilder
    sb.append(preamble)
    sb.append("\n// ── Generated definitions ─────────────────────────────────────────────────\n\n")
    // Declare all def names first (allow forward/mutual references)
    for d <- prog.defs do
      sb.append(s"var ${dn(d.name)};\n")
    sb.append("\n")
    for d <- prog.defs do
      emitDef(sb, d)
    sb.append("\n// ── Entry ───────────────────────────────────────────────────────────────────\n\n")
    // Like the VM's `out(run(prog))`: evaluate entry, print result unless Unit.
    // The VM's out() is: case UnitV => ()  ; case v => println(Show.show(v))
    sb.append("(function(){\n  var $result=")
    sb.append(genE(prog.entry, Nil, tco = false))
    sb.append(";\n  if($result!==null&&$result!==undefined){ console.log($show($result)); }\n})();\n")
    sb.toString

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
    case Lit(CInt(n))   => s"${n}n"
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
      // Integer arithmetic — ints are BigInt; wrap to 64-bit like the VM's Long
      case "i.add"  => s"BigInt.asIntN(64,(${a(0)}+${a(1)}))"
      case "i.sub"  => s"BigInt.asIntN(64,(${a(0)}-${a(1)}))"
      case "i.mul"  => s"BigInt.asIntN(64,(${a(0)}*${a(1)}))"
      case "i.div"  => s"BigInt.asIntN(64,(${a(0)}/${a(1)}))"
      case "i.mod"  => s"(${a(0)}%${a(1)})"
      case "i.neg"  => s"BigInt.asIntN(64,(-(${a(0)})))"
      // Integer comparisons
      case "i.eq"   => s"(${a(0)}===${a(1)})"
      case "i.lt"   => s"(${a(0)}<${a(1)})"
      case "i.le"   => s"(${a(0)}<=${a(1)})"
      case "i.gt"   => s"(${a(0)}>${a(1)})"
      case "i.ge"   => s"(${a(0)}>=${a(1)})"
      // Bitwise
      case "i.and"  => s"(${a(0)}&${a(1)})"
      case "i.or"   => s"(${a(0)}|${a(1)})"
      case "i.xor"  => s"(${a(0)}^${a(1)})"
      case "i.not"  => s"(~${a(0)})"
      case "i.shl"  => s"BigInt.asIntN(64,(${a(0)}<<(${a(1)}&63n)))"
      case "i.shr"  => s"(${a(0)}>>(${a(1)}&63n))"
      case "i.ushr" => s"BigInt.asIntN(64,(BigInt.asUintN(64,${a(0)})>>(${a(1)}&63n)))"
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
      case "i->f"    => s"Number(${a(0)})"
      case "i->big"  => a(0)
      case "big->i"  => s"BigInt.asIntN(64,${a(0)})"
      case "big->f"  => s"Number(${a(0)})"
      case "big->str"=> s"String(${a(0)})"
      case "f->i"    => s"BigInt(Math.trunc(${a(0)}))"
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
      case "scmp"      => s"(${a(0)}<${a(1)}?-1n:${a(0)}>${a(1)}?1n:0n)"
      case "sindexOf"  => s"BigInt(${a(0)}.indexOf(${a(1)}))"
      case "slen"      => s"BigInt(${a(0)}.length)"
      case "sslice"    => s"${a(0)}.substring(Number(${a(1)}),Number(${a(2)}))"
      case "scodeAt"   => s"BigInt(${a(0)}.charCodeAt(Number(${a(1)})))"
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
      case "arr.len"    => s"BigInt(${a(0)}.length)"
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
      case "map.size"   => s"BigInt(${a(0)}.m.size)"
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

  private val preamble: String = """
"use strict";
// v2 Core IR → JavaScript runtime

// ── Trampoline ───────────────────────────────────────────────────────────────
// Every closure call goes through $c which drives the trampoline.
// Tail calls (tco=true in codegen) return $tco thunks instead of calling directly.
function $tco(fn,args){ return {$k:fn,$a:args}; }
function $c(fn,args){
  var r=fn.apply(null,args);
  while(r!==null&&r!==undefined&&typeof r==='object'&&r.$k!==undefined){
    r=r.$k.apply(null,r.$a);
  }
  return r;
}

// ── Show (value → display string) ────────────────────────────────────────────
function $show(v){
  if(v===null||v===undefined) return "()";
  if(typeof v==='boolean') return String(v);
  if(typeof v==='number') return String(v);
  if(typeof v==='bigint') return String(v);
  if(typeof v==='string') return '"'+v+'"';
  if(v instanceof Uint8Array){
    var h='#'; for(var i=0;i<v.length;i++){var b=v[i].toString(16);h+=(b.length<2?'0':'')+b;} return h;
  }
  if(Array.isArray(v)) return '<arr>';
  if(v.$k!==undefined) return '<tco>';
  if(v.m!==undefined) return '<map>';
  if(v.t!==undefined){
    if(!v.f||v.f.length===0) return v.t;
    return v.t+'('+v.f.map($show).join(', ')+')';
  }
  return String(v);
}

// ── IO ────────────────────────────────────────────────────────────────────────
function $showIO(v){
  // Strings are printed raw (no quotes); other values use $show
  if(typeof v==='string') return v;
  return $show(v);
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
// Scala-style Double display for f->str: integral doubles show a trailing .0
function $fToStr(v){
  if(Number.isFinite(v)&&Number.isInteger(v)&&Math.abs(v)<1e21) return String(v)+'.0';
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
