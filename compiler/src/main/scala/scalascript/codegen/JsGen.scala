package scalascript.codegen

import scalascript.ast.*
import scala.collection.mutable

/** JavaScript code generator for ScalaScript modules.
 *
 *  Walks the same Module type the interpreter uses, generating JS for each
 *  Scala code block. The generated JS can be embedded in HTML and run in-browser.
 */
object JsGen:

  /** Generate JS source for all code blocks in a module. */
  def generate(module: Module): String =
    val gen = new JsGen()
    gen.genModule(module)

/** JS runtime preamble embedded in every generated page. */
val JsRuntime: String = """
const _output = [];
function _println(...args) { _output.push(args.map(_show).join(' ')); }
function _print(...args) { const s = args.map(_show).join(''); _output.push(s); }

function _show(v) {
  if (v === undefined) return '()';
  if (v === null) return 'null';
  if (typeof v === 'boolean') return String(v);
  if (typeof v === 'number') return String(v);
  if (typeof v === 'string') return v;
  if (Array.isArray(v)) {
    if (v._isTuple) return '(' + v.map(_show).join(', ') + ')';
    return 'List(' + v.map(_show).join(', ') + ')';
  }
  if (v instanceof Map) {
    if (v.size === 0) return 'Map()';
    return 'Map(' + [...v.entries()].map(([k,vv]) => _show(k)+' -> '+_show(vv)).join(', ') + ')';
  }
  if (v && v._type === '_Some') return 'Some(' + _show(v.value) + ')';
  if (v && v._type === '_None') return 'None';
  if (v && v._type) {
    const fields = Object.entries(v).filter(([k]) => k !== '_type');
    if (!fields.length) return v._type;
    return v._type + '(' + fields.map(([,vv]) => _show(vv)).join(', ') + ')';
  }
  if (typeof v === 'function') return '<function>';
  return String(v);
}

const None = {_type: '_None'};
const _None = {_type: '_None'};
function Some(v) { return {_type: '_Some', value: v}; }
function _Some(v) { return {_type: '_Some', value: v}; }

const math = {
  sqrt: x => Math.sqrt(x), abs: x => Math.abs(x),
  pow: (x,y) => Math.pow(x,y), floor: x => Math.floor(x),
  ceil: x => Math.ceil(x), round: x => Math.round(x),
  Pi: Math.PI, E: Math.E
};

function assert(cond, msg) { if (!cond) throw new Error(msg != null ? _show(msg) : 'Assertion failed'); }

function _md(s) {
  const lines = s.split('\n');
  let start = 0, end = lines.length;
  while (start < end && lines[start].trim() === '') start++;
  while (end > start && lines[end-1].trim() === '') end--;
  const body = lines.slice(start, end);
  if (!body.length) return '';
  const indent = Math.min(...body.filter(l => l.trim() !== '').map(l => l.match(/^(\s*)/)[1].length));
  return body.map(l => l.slice(indent)).join('\n');
}

function doc(...args) { return {_type: '_Doc', parts: args}; }
function render(...args) {
  function toStr(v) {
    if (v && v._type === '_Doc') return v.parts.map(toStr).join('\n');
    return _show(v);
  }
  const parts = args.length === 1 && args[0] && args[0]._type === '_Doc' ? args[0].parts : args;
  _println(parts.map(toStr).join('\n'));
}

function List(...args) { return [...args]; }
List.fill     = (n) => (elem) => Array.from({length: n}, () => elem);
List.tabulate = (n) => (f)    => Array.from({length: n}, (_, i) => f(i));
List.range    = (from, until, step=1) => { const r=[]; for(let i=from;i<until;i+=step) r.push(i); return r; };
List.empty    = [];

function _Map(...pairs) {
  const m = new Map();
  pairs.forEach(p => m.set(p[0], p[1]));
  return m;
}

function _dispatch(obj, method, args) {
  if (Array.isArray(obj)) {
    switch(method) {
      case 'head': return obj[0];
      case 'last': return obj[obj.length-1];
      case 'tail': return obj.slice(1);
      case 'init': return obj.slice(0,-1);
      case 'length': case 'size': return obj.length;
      case 'isEmpty': return obj.length === 0;
      case 'nonEmpty': return obj.length > 0;
      case 'reverse': return [...obj].reverse();
      case 'distinct': return [...new Set(obj)];
      case 'toList': return obj;
      case 'toSet': return [...new Set(obj)];
      case 'sum': return obj.reduce((a,b)=>a+b, 0);
      case 'min': return Math.min(...obj);
      case 'max': return Math.max(...obj);
      case 'sorted': return [...obj].sort((a,b)=>a<b?-1:a>b?1:0);
      case 'flatten': return obj.flat();
      case 'map': return obj.map(args[0]);
      case 'flatMap': return obj.flatMap(args[0]);
      case 'filter': return obj.filter(args[0]);
      case 'filterNot': return obj.filter(x => !args[0](x));
      case 'foreach': case 'forEach': obj.forEach(args[0]); return undefined;
      case 'exists': return obj.some(args[0]);
      case 'forall': return obj.every(args[0]);
      case 'find': { const r = obj.find(args[0]); return r !== undefined ? _Some(r) : _None; }
      case 'count': return obj.filter(args[0]).length;
      case 'take': return obj.slice(0, args[0]);
      case 'drop': return obj.slice(args[0]);
      case 'takeRight': return obj.slice(-args[0]);
      case 'dropRight': return obj.slice(0, -args[0]);
      case 'takeWhile': { const i = obj.findIndex(x => !args[0](x)); return i<0 ? obj : obj.slice(0,i); }
      case 'dropWhile': { const i = obj.findIndex(x => !args[0](x)); return i<0 ? [] : obj.slice(i); }
      case 'zip': return obj.map((v,i) => { const t = [v, args[0][i]]; t._isTuple=true; return t; });
      case 'zipWithIndex': return obj.map((v,i) => { const t = [v,i]; t._isTuple=true; return t; });
      case 'appended': return [...obj, args[0]];
      case 'prepended': return [args[0], ...obj];
      case 'contains': return obj.includes(args[0]);
      case 'indexOf': return obj.indexOf(args[0]);
      case 'mkString':
        if (!args.length) return obj.map(_show).join('');
        if (args.length === 1) return obj.map(_show).join(args[0]);
        return args[0] + obj.map(_show).join(args[1]) + args[2];
      case 'sortBy': return [...obj].sort((a,b) => { const fa=args[0](a),fb=args[0](b); return fa<fb?-1:fa>fb?1:0; });
      case 'groupBy': { const m=new Map(); obj.forEach(x=>{const k=args[0](x);if(!m.has(k))m.set(k,[]);m.get(k).push(x);}); return m; }
      case 'reduceLeft': return obj.reduce(args[0]);
      case 'foldLeft': return (f) => obj.reduce(f, args[0]);
      case 'foldRight': return (f) => obj.reduceRight((acc,x) => f(x,acc), args[0]);
      case 'sliding': { const n=args[0]; const r=[]; for(let i=0;i<=obj.length-n;i++) r.push(obj.slice(i,i+n)); return r; }
      case 'grouped': { const n=args[0]; const r=[]; for(let i=0;i<obj.length;i+=n) r.push(obj.slice(i,i+n)); return r; }
      case 'toString': return _show(obj);
      case '_1': return obj[0];
      case '_2': return obj[1];
      case '_3': return obj[2];
      case '_4': return obj[3];
    }
  }
  if (obj instanceof Map) {
    switch(method) {
      case 'get': return obj.has(args[0]) ? _Some(obj.get(args[0])) : _None;
      case 'getOrElse': return obj.has(args[0]) ? obj.get(args[0]) : args[1];
      case 'contains': return obj.has(args[0]);
      case 'keys': return [...obj.keys()];
      case 'values': return [...obj.values()];
      case 'size': return obj.size;
      case 'isEmpty': return obj.size === 0;
      case 'nonEmpty': return obj.size > 0;
      case 'updated': { const m2=new Map(obj); m2.set(args[0],args[1]); return m2; }
      case 'removed': { const m2=new Map(obj); m2.delete(args[0]); return m2; }
      case 'mkString': return [...obj.entries()].map(([k,v])=>_show(k)+'->'+_show(v)).join(args[0]??'');
      case 'map': return [...obj.entries()].map(([k,v])=>args[0]([k,v]));
      case 'filter': return new Map([...obj.entries()].filter(([k,v])=>args[0]([k,v])));
      case 'toList': return [...obj.entries()].map(([k,v])=>{ const t=[k,v]; t._isTuple=true; return t; });
      case 'foreach': [...obj.entries()].forEach(([k,v])=>args[0]([k,v])); return undefined;
    }
  }
  if (obj && obj._type === '_Some') {
    switch(method) {
      case 'map': return _Some(args[0](obj.value));
      case 'flatMap': return args[0](obj.value);
      case 'getOrElse': return obj.value;
      case 'isDefined': return true;
      case 'isEmpty': return false;
      case 'nonEmpty': return true;
      case 'get': return obj.value;
      case 'filter': return args[0](obj.value) ? obj : _None;
      case 'foreach': args[0](obj.value); return undefined;
      case 'toList': return [obj.value];
      case 'orElse': return obj;
    }
  }
  if (obj && obj._type === '_None') {
    switch(method) {
      case 'map': return _None;
      case 'flatMap': return _None;
      case 'getOrElse': return args[0];
      case 'isDefined': return false;
      case 'isEmpty': return true;
      case 'nonEmpty': return false;
      case 'get': throw new Error('None.get');
      case 'filter': return _None;
      case 'foreach': return undefined;
      case 'toList': return [];
      case 'orElse': return args[0];
    }
  }
  if (typeof obj === 'string') {
    switch(method) {
      case 'length': case 'size': return obj.length;
      case 'toUpperCase': return obj.toUpperCase();
      case 'toLowerCase': return obj.toLowerCase();
      case 'trim': return obj.trim();
      case 'split': return obj.split(args[0]);
      case 'replace': return obj.replaceAll(args[0], args[1]);
      case 'startsWith': return obj.startsWith(args[0]);
      case 'endsWith': return obj.endsWith(args[0]);
      case 'contains': return obj.includes(args[0]);
      case 'toInt': return parseInt(obj);
      case 'toDouble': return parseFloat(obj);
      case 'toList': return [...obj];
      case 'charAt': return obj[args[0]];
      case 'substring': return obj.substring(args[0], args[1]);
      case 'take': return obj.slice(0, args[0]);
      case 'drop': return obj.slice(args[0]);
      case 'mkString': return obj;
      case 'reverse': return [...obj].reverse().join('');
      case 'isEmpty': return obj.length === 0;
      case 'nonEmpty': return obj.length > 0;
      case 'toString': return obj;
      case 'map': return [...obj].map(args[0]).join('');
      case 'trim': return obj.trim();
    }
  }
  if (typeof obj === 'number') {
    switch(method) {
      case 'toInt': case 'toLong': return Math.trunc(obj);
      case 'toDouble': return obj;
      case 'toString': return String(obj);
      case 'abs': return Math.abs(obj);
      case 'round': return Math.round(obj);
      case 'floor': return Math.floor(obj);
      case 'ceil': return Math.ceil(obj);
      case 'to': { const r=[]; for(let i=obj;i<=args[0];i++) r.push(i); return r; }
      case 'until': { const r=[]; for(let i=obj;i<args[0];i++) r.push(i); return r; }
    }
  }
  if (obj && typeof obj === 'object') {
    if (method === 'toString') return _show(obj);
    if (obj[method] !== undefined) {
      if (typeof obj[method] === 'function') {
        // If args is empty and the function takes args, return the function reference (eta-expansion)
        // If args is empty and the function takes no args, call it
        if (args.length === 0 && obj[method].length === 0) return obj[method]();
        if (args.length === 0) return obj[method];  // return as reference
        return obj[method](...args);
      }
      return obj[method];
    }
  }
  // Function-object dispatch: for companion objects (List.fill, etc.)
  if (typeof obj === 'function' && obj[method] !== undefined) {
    const val = obj[method];
    if (typeof val === 'function') return args.length ? val(...args) : val;
    return val;
  }
  // Extension method fallback: look up _ext_<paramName>_<method>
  // We try to find registered extension functions by method name
  if (typeof _extensions !== 'undefined' && _extensions[method]) {
    const fns = _extensions[method];
    for (const fn of fns) {
      try { return fn(obj, ...args); } catch(e) { /* try next */ }
    }
  }
  throw new Error('Method not found: ' + method + ' on ' + _show(obj));
}

const _extensions = {};
function _registerExt(method, fn) {
  if (!_extensions[method]) _extensions[method] = [];
  _extensions[method].push(fn);
}
"""

class JsGen:
  import scala.meta.*

  private val sb = StringBuilder()
  private var indent = 0
  private var tmpIdx = 0
  private var hasMain = false
  private var mainCalled = false
  // Stack of placeholder counters: each AnonymousFunction pushes 0, Placeholder increments top
  private var phCounters: List[Int] = Nil
  // Names of variables known to hold integer values (for integer division detection)
  private val intVars = scala.collection.mutable.Set[String]()

  private def freshTmp(): String =
    tmpIdx += 1
    s"_t$tmpIdx"

  private def line(s: String): Unit =
    sb.append("  " * indent).append(s).append("\n")

  def genModule(module: Module): String =
    sb.clear()
    module.sections.foreach(genSection)
    // Auto-call main() if defined and not already called
    if hasMain && !mainCalled then
      line("if (typeof main === 'function') { main(); }")
    sb.toString

  private def genSection(section: Section): Unit =
    section.content.foreach {
      case cb: Content.CodeBlock if cb.lang == "scala" || cb.lang == "ssc" =>
        cb.tree.foreach(genScalaNode)
      case _ => ()
    }
    section.subsections.foreach(genSection)

  private def genScalaNode(node: ScalaNode): Unit =
    ScalaNode.fold(node) {
      case Source(stats) => genBlockStats(stats, topLevel = true)
      case t: Term.Block => genBlockStats(t.stats, topLevel = true)
      case t: Term       => line(genExpr(t) + ";")
      case _             => ()
    }

  private def genBlockStats(stats: List[Stat], topLevel: Boolean): Unit =
    stats.zipWithIndex.foreach { (s, i) =>
      val isLast = i == stats.length - 1
      s match
        case t: Term if isLast && topLevel =>
          // Track main() calls; auto-output non-unit last expression
          t match
            case Term.Apply.After_4_6_0(Term.Name("main"), _) => mainCalled = true
            case _ => ()
          val expr = genExpr(t)
          line(s"{ const _auto = $expr; if (_auto !== undefined && !(_auto === null)) _println(_show(_auto)); }")
        case t: Term =>
          t match
            case Term.Apply.After_4_6_0(Term.Name("main"), _) => mainCalled = true
            case _ => ()
          line(genExpr(t) + ";")
        case stat =>
          genStat(stat)
    }

  private def genStat(stat: Stat): Unit = stat match
    case Defn.Val(_, pats, _, rhs) =>
      pats match
        case List(Pat.Var(n)) =>
          if isIntExpr(rhs) then intVars += n.value
          line(s"const ${n.value} = ${genExpr(rhs)};")
        case List(pat) =>
          // Tuple/pattern destructuring
          val patJs = genPatDestructure(pat)
          line(s"const $patJs = ${genExpr(rhs)};")
        case _ =>
          line(s"/* multi-pat val */")

    case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs) =>
      if isIntExpr(rhs) then intVars += n.value
      line(s"let ${n.value} = ${genExpr(rhs)};")

    case d: Defn.Def =>
      val params = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).map(_.name.value)
      val paramsStr = params.mkString(", ")
      if d.name.value == "main" && params.isEmpty then hasMain = true
      d.body match
        case Term.Block(bodyStats) =>
          line(s"function ${d.name.value}($paramsStr) {")
          indent += 1
          genFunctionBody(bodyStats)
          indent -= 1
          line("}")
        case expr =>
          line(s"function ${d.name.value}($paramsStr) { return ${genExpr(expr)}; }")

    case d: Defn.Class =>
      // case class → constructor function returning plain object
      val params = d.ctor.paramClauses.flatMap(_.values).map(_.name.value)
      val typeName = d.name.value
      val paramsStr = params.mkString(", ")
      val fields = params.map(p => s"$p: $p").mkString(", ")
      line(s"function $typeName($paramsStr) { return {_type: '$typeName', $fields}; }")

    case d: Defn.Object =>
      val members = mutable.ArrayBuffer.empty[String]
      d.templ.body.stats.foreach { s =>
        members += genObjectMember(s)
      }
      line(s"const ${d.name.value} = {${members.mkString(", ")}};")

    case d: Defn.Enum =>
      d.templ.body.stats.foreach {
        case ec: Defn.EnumCase =>
          val caseName = ec.name.value
          val params = ec.ctor.paramClauses.flatMap(_.values).map(_.name.value)
          if params.isEmpty then
            line(s"const $caseName = {_type: '$caseName'};")
          else
            val paramsStr = params.mkString(", ")
            val fields = params.map(p => s"$p: $p").mkString(", ")
            line(s"function $caseName($paramsStr) { return {_type: '$caseName', $fields}; }")
        case _ => ()
      }

    case _: Defn.Trait => () // erased

    case d: Defn.Given =>
      // given intShow: Show[Int] with { def show(x) = ... }
      // → const intShow = { show: (x) => ..., ... };
      // also register as Show_Int
      d.templ.inits.headOption.foreach { init =>
        val typeKeyOpt: Option[String] = init.tpe match
          case n: Type.Name  => Some(n.value)
          case ta: Type.Apply =>
            (ta.tpe match { case n: Type.Name => Some(n.value); case _ => None }).map { tc =>
              val arg = ta.argClause.values match
                case List(n: Type.Name) => n.value
                case _                  => "_"
              s"${tc}_${arg}"
            }
          case _ => None
        typeKeyOpt.foreach { typeKey =>
          val members = d.templ.body.stats.collect { case dd: Defn.Def =>
            s"${dd.name.value}: ${genDefAsMethod(dd)}"
          }
          val obj = s"{${members.mkString(", ")}}"
          val explicitName = d.name.value
          if explicitName.nonEmpty then
            line(s"const $explicitName = $obj;")
            // Also register with typeclass key for summon
            line(s"const $typeKey = $explicitName;")
          else
            line(s"const $typeKey = $obj;")
        }
      }

    case _: Decl.Def => () // abstract

    case d: Defn.ExtensionGroup =>
      d.paramClauseGroup.foreach { pcg =>
        pcg.paramClauses.headOption.flatMap(_.values.headOption).foreach { recvParam =>
          val recvName = recvParam.name.value
          d.body match
            case defn: Defn.Def =>
              genExtensionDef(recvName, defn)
            case Term.Block(stats) =>
              stats.foreach { case defn: Defn.Def => genExtensionDef(recvName, defn); case _ => () }
            case _ => ()
        }
      }

    case t: Term =>
      // Track if main() is explicitly called
      t match
        case Term.Apply.After_4_6_0(Term.Name("main"), _) => mainCalled = true
        case _ => ()
      line(genExpr(t) + ";")

    case _: Import => () // ignored
    case _: Export => () // ignored
    case _ => () // type aliases etc.

  private def genExtensionDef(recvName: String, defn: Defn.Def): Unit =
    val methodParams = defn.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).map(_.name.value)
    val allParams = recvName :: methodParams
    val paramsStr = allParams.mkString(", ")
    val fnName = s"_ext_${recvName}_${defn.name.value}"
    defn.body match
      case Term.Block(bodyStats) =>
        line(s"function $fnName($paramsStr) {")
        indent += 1
        genFunctionBody(bodyStats)
        indent -= 1
        line("}")
      case expr =>
        line(s"function $fnName($paramsStr) { return ${genExpr(expr)}; }")
    // Register extension for dispatch
    line(s"_registerExt('${defn.name.value}', ($recvName, ...args) => $fnName($recvName, ...args));")

  private def genObjectMember(stat: Stat): String = stat match
    case dd: Defn.Def =>
      s"${dd.name.value}: ${genDefAsMethod(dd)}"
    case Defn.Val(_, List(Pat.Var(n)), _, rhs) =>
      s"${n.value}: ${genExpr(rhs)}"
    case _ => ""

  private def genDefAsMethod(dd: Defn.Def): String =
    val params = dd.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).map(_.name.value)
    val paramsStr = params.mkString(", ")
    dd.body match
      case Term.Block(bodyStats) =>
        val bodyJs = genBlockAsIife(bodyStats)
        s"($paramsStr) => $bodyJs"
      case expr =>
        s"($paramsStr) => ${genExpr(expr)}"

  private def genFunctionBody(stats: List[Stat]): Unit =
    if stats.isEmpty then
      line("return undefined;")
    else
      stats.zipWithIndex.foreach { (s, i) =>
        val isLast = i == stats.length - 1
        s match
          case t: Term if isLast =>
            line(s"return ${genExpr(t)};")
          case t: Term =>
            line(genExpr(t) + ";")
          case stat =>
            genStat(stat)
        // After each non-last stat, continue with remaining
      }

  private def genBlockAsIife(stats: List[Stat]): String =
    if stats.isEmpty then "undefined"
    else if stats.length == 1 then
      stats.head match
        case t: Term => genExpr(t)
        case stat =>
          s"(() => { ${genStatInline(stat)} return undefined; })()"
    else
      // Multi-stat IIFE
      val inner = StringBuilder()
      inner.append("(() => {\n")
      stats.zipWithIndex.foreach { (s, i) =>
        val isLast = i == stats.length - 1
        val pad = "  "
        s match
          case t: Term if isLast =>
            inner.append(pad).append("return ").append(genExpr(t)).append(";\n")
          case t: Term =>
            inner.append(pad).append(genExpr(t)).append(";\n")
          case stat =>
            inner.append(pad).append(genStatInline(stat)).append("\n")
      }
      inner.append("})()")
      inner.toString

  private def genStatInline(stat: Stat): String = stat match
    case Defn.Val(_, List(Pat.Var(n)), _, rhs) =>
      s"const ${n.value} = ${genExpr(rhs)};"
    case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs) =>
      s"let ${n.value} = ${genExpr(rhs)};"
    case d: Defn.Def =>
      val params = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).map(_.name.value)
      d.body match
        case expr =>
          s"function ${d.name.value}(${params.mkString(", ")}) { return ${genExpr(expr)}; }"
    case t: Term => genExpr(t) + ";"
    case _ => "/* stat */;"

  private def genPatDestructure(pat: Pat): String = pat match
    case Pat.Tuple(pats) =>
      "[" + pats.map(p => p match
        case Pat.Var(n) => n.value
        case Pat.Wildcard() => "_"
        case inner => genPatDestructure(inner)
      ).mkString(", ") + "]"
    case Pat.Var(n) => n.value
    case Pat.Wildcard() => "_"
    case _ => "_"

  /** Generate a JS expression string for a scalameta Term. */
  def genExpr(term: Term): String = term match
    // Literals
    case Lit.Int(v)     => v.toString
    case Lit.Long(v)    => v.toString
    case Lit.Double(v)  => v.toString
    case Lit.Float(v)   => v.toString
    case Lit.String(v)  =>
      // Escape for JS string literal
      "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
    case Lit.Boolean(v) => v.toString
    case Lit.Char(v)    => "\"" + v.toString.replace("\"", "\\\"") + "\""
    case Lit.Unit()     => "undefined"
    case Lit.Null()     => "null"

    // Name lookup
    case Term.Name(name) => mapName(name)

    // Block
    case Term.Block(stats) =>
      if stats.isEmpty then "undefined"
      else genBlockAsIife(stats)

    // If/else
    case t: Term.If =>
      val cond = genExpr(t.cond)
      val thenp = genExpr(t.thenp)
      val elsep = t.elsep match
        case Lit.Unit() => "undefined"
        case e          => genExpr(e)
      s"($cond ? $thenp : $elsep)"

    // String interpolation
    case Term.Interpolate(Term.Name(prefix), parts, args)
        if prefix == "s" || prefix == "f" || prefix == "md" =>
      val sb2 = StringBuilder()
      sb2.append("`")
      for i <- parts.indices do
        val part = parts(i).asInstanceOf[Lit.String].value
        sb2.append(part.replace("`", "\\`").replace("\\", "\\\\").replace("$", "\\$"))
        if i < args.length then
          val arg = args(i).asInstanceOf[Term]
          sb2.append("${_show(").append(genExpr(arg)).append(")}")
      sb2.append("`")
      val templateLiteral = sb2.toString
      if prefix == "md" then s"_md($templateLiteral)" else templateLiteral

    // Anonymous function with _ placeholders — stack-based param counting
    case t: Term.AnonymousFunction =>
      phCounters = 0 :: phCounters          // push fresh counter
      val bodyJs = genExpr(t.body)
      val count  = phCounters.head
      phCounters = phCounters.tail           // pop
      val params = (0 until count).map(i => s"_$$${i}")
      if params.isEmpty then s"() => $bodyJs"
      else s"(${params.mkString(", ")}) => $bodyJs"

    // Placeholder _ — increment top counter and return indexed name
    case _: Term.Placeholder =>
      val i = phCounters.headOption.getOrElse(0)
      phCounters = phCounters match
        case h :: t => (h + 1) :: t
        case Nil    => Nil
      s"_$$$i"

    // Lambda
    case Term.Function.After_4_6_0(paramClause, body) =>
      val params = paramClause.values.map(_.name.value)
      val paramsStr = if params.length == 1 then params.head else s"(${params.mkString(", ")})"
      body match
        case Term.Block(stats) =>
          val bodyJs = genBlockAsIife(stats)
          s"$paramsStr => $bodyJs"
        case expr =>
          s"$paramsStr => ${genExpr(expr)}"

    // Partial function { case ... => ... }
    case Term.PartialFunction(cases) =>
      val scrutVar = freshTmp()
      val casesJs = cases.map { c =>
        genCase(scrutVar, c)
      }.mkString(" else ")
      s"(($scrutVar) => { $casesJs else { throw new Error('Match failure: ' + _show($scrutVar)); } })"

    // Match
    case t: Term.Match =>
      val scrutVar = freshTmp()
      val scrutExpr = genExpr(t.expr)
      val casesJs = t.casesBlock.cases.map { c =>
        genCase(scrutVar, c)
      }.mkString(" else ")
      s"(($scrutVar => { $casesJs else { throw new Error('Match failure: ' + _show($scrutVar)); } })($scrutExpr))"

    // Tuple
    case Term.Tuple(elems) =>
      val elemsJs = elems.map(genExpr).mkString(", ")
      val tmp = freshTmp()
      s"(() => { const $tmp = [$elemsJs]; $tmp._isTuple = true; return $tmp; })()"

    // Assignment
    case Term.Assign(lhs, rhs) =>
      s"${genExpr(lhs)} = ${genExpr(rhs)}"

    // While
    case t: Term.While =>
      val cond = genExpr(t.expr)
      val body = genExpr(t.body)
      s"(() => { while ($cond) { $body; } })()"

    // For-do
    case t: Term.For =>
      genForDo(t.enumsBlock.enums, t.body)

    // For-yield
    case t: Term.ForYield =>
      genForYield(t.enumsBlock.enums, t.body)

    // new ClassName(args)
    case Term.New(Init.After_4_6_0(tpe, _, argClauses)) =>
      val typeName = tpe match { case Type.Name(n) => n; case _ => "?" }
      val args = argClauses.toList.flatMap(_.values).map(genExpr)
      s"$typeName(${args.mkString(", ")})"

    // Return
    case Term.Return(expr) =>
      genExpr(expr)  // We can't easily return from JS like Scala; treat as expression

    // summon[TC[T]]
    case t: Term.ApplyType =>
      (t.fun, t.argClause.values) match
        case (Term.Name("summon"), List(typeArg)) =>
          val key = typeArg match
            case n: Type.Name  => n.value
            case ta: Type.Apply =>
              val tc  = ta.tpe match { case n: Type.Name => n.value; case _ => "?" }
              val arg = ta.argClause.values match { case List(n: Type.Name) => n.value; case _ => "_" }
              s"${tc}_${arg}"
            case _ => "undefined"
          key
        case _ => genExpr(t.fun)

    // Field/method selection without arguments
    case Term.Select(qual, name) =>
      val qualJs = genExpr(qual)
      name.value match
        // Built-in collection/string methods that need runtime dispatch (computed properties)
        case "head" | "tail" | "last" | "init" | "reverse" | "distinct" | "sorted" |
             "toList" | "toSet" | "sum" | "min" | "max" | "flatten" | "isEmpty" |
             "nonEmpty" | "size" | "length" | "keys" | "values" | "isDefined" |
             "toUpperCase" | "toLowerCase" | "trim" | "toInt" | "toDouble" | "toLong" |
             "abs" | "round" | "floor" | "ceil" | "zipWithIndex" | "nonEmpty" |
             "_1" | "_2" | "_3" | "_4" =>
          s"_dispatch($qualJs, '${name.value}', [])"
        case other =>
          // Direct property access for regular objects (case classes, typeclasses, etc.)
          // Use _dispatch for extension methods, but try direct property first
          s"_dispatch($qualJs, '$other', [])"

    // Function application
    case app: Term.Apply =>
      genApply(app)

    // Infix
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause) =>
      val lhsJs = genExpr(lhs)
      val args = argClause.values
      val rhsJs = if args.length == 1 then genExpr(args.head) else args.map(genExpr).mkString(", ")
      op.value match
        case "::" => s"[${genExpr(lhs)}, ...(${genExpr(args.head)})]"
        case "++" | ":::" => s"[...($lhsJs), ...(${genExpr(args.head)})]"
        case "->" =>
          val tmp = freshTmp()
          s"(() => { const $tmp = [$lhsJs, $rhsJs]; $tmp._isTuple = true; return $tmp; })()"
        case "*" =>
          // Could be string * n or numeric
          s"(typeof $lhsJs === 'string' ? $lhsJs.repeat($rhsJs) : $lhsJs * $rhsJs)"
        case "==" => s"($lhsJs === $rhsJs)"
        case "!=" => s"($lhsJs !== $rhsJs)"
        case "&&" => s"($lhsJs && $rhsJs)"
        case "||" => s"($lhsJs || $rhsJs)"
        case "to" =>
          // n to m → array [n, n+1, ..., m]
          s"_dispatch($lhsJs, 'to', [$rhsJs])"
        case "until" =>
          // n until m → array [n, n+1, ..., m-1]
          s"_dispatch($lhsJs, 'until', [$rhsJs])"
        case "/" if isIntExpr(lhs) && args.headOption.exists(isIntExpr) =>
          s"Math.trunc($lhsJs / $rhsJs)"
        case other => s"($lhsJs $other $rhsJs)"

    case other =>
      s"/* unsupported: ${other.productPrefix} */"

  private def genApply(app: Term.Apply): String =
    val argVals = app.argClause.values.map(genExpr)
    app.fun match
      // println / print
      case Term.Name("println") =>
        if argVals.isEmpty then "_println()"
        else s"_println(${argVals.map(a => s"_show($a)").mkString(", ")})"
      case Term.Name("print") =>
        if argVals.isEmpty then "_print()"
        else s"_print(${argVals.map(a => s"_show($a)").mkString(", ")})"

      // Map constructor - args are tuple pairs
      case Term.Name("Map") =>
        s"_Map(${argVals.mkString(", ")})"

      // List constructor
      case Term.Name("List") =>
        s"[${argVals.mkString(", ")}]"

      // Some / None
      case Term.Name("Some") | Term.Name("_Some") =>
        s"_Some(${argVals.mkString(", ")})"

      // assert
      case Term.Name("assert") =>
        s"assert(${argVals.mkString(", ")})"

      // foldLeft curried: Apply(Apply(Select(xs, "foldLeft"), [init]), [f])
      case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name("foldLeft")), initArgClause) =>
        val qualJs = genExpr(qual)
        val initJs = initArgClause.values.map(genExpr).mkString(", ")
        val fJs = argVals.mkString(", ")
        s"${qualJs}.reduce($fJs, $initJs)"

      // foldRight curried
      case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name("foldRight")), initArgClause) =>
        val qualJs = genExpr(qual)
        val initJs = initArgClause.values.map(genExpr).mkString(", ")
        val fJs = argVals.mkString(", ")
        s"(($qualJs).reduceRight(($fJs === undefined ? (acc,x) => acc : (acc,x) => ($fJs)(x, acc)), $initJs))"

      // Method calls: obj.method(args) → _dispatch(obj, "method", [args])
      case Term.Select(qual, Term.Name(method)) =>
        val qualJs = genExpr(qual)
        method match
          // String * n handled specially
          case _ =>
            val argsJs = argVals.mkString(", ")
            s"_dispatch($qualJs, '$method', [$argsJs])"

      // Regular function call or constructor
      case fun =>
        val funJs = genExpr(fun)
        s"$funJs(${argVals.mkString(", ")})"

  private def genCase(scrutVar: String, c: Case): String =
    val (cond, bindings) = genPattern(scrutVar, c.pat)
    val bindingStmts = bindings.map { case (name, expr) => s"const $name = $expr;" }.mkString(" ")
    val bodyJs = genExpr(c.body)

    // Pattern guard: we need bindings set up before evaluating the guard.
    // We use a nested IIFE to set up bindings, evaluate guard, then run body.
    c.cond match
      case Some(guard) if bindings.nonEmpty =>
        // Put bindings and guard inside the if block
        val guardExpr = genExpr(guard)
        val patCond = if cond == "true" then "" else s"($cond) && "
        s"if (${patCond}(() => { $bindingStmts return $guardExpr; })()) { $bindingStmts return $bodyJs; }"
      case Some(guard) =>
        val guardExpr = genExpr(guard)
        val condStr = if cond == "true" then s"($guardExpr)" else s"($cond) && ($guardExpr)"
        s"if ($condStr) { return $bodyJs; }"
      case None =>
        val condStr = if cond == "true" then "true" else s"($cond)"
        s"if ($condStr) { $bindingStmts return $bodyJs; }"

  /** Returns (condition JS, list of (varName, expr) bindings).
   *  The condition must be true for the pattern to match.
   *  Bindings are set up when condition is true.
   */
  private def genPattern(scrutVar: String, pat: Pat): (String, List[(String, String)]) = pat match
    case Pat.Wildcard() =>
      ("true", Nil)

    case Pat.Var(n) =>
      ("true", List(n.value -> scrutVar))

    case lit: Lit =>
      val litJs = lit match
        case Lit.Int(v)     => v.toString
        case Lit.Long(v)    => v.toString
        case Lit.Double(v)  => v.toString
        case Lit.String(v)  => "\"" + v.replace("\"", "\\\"") + "\""
        case Lit.Boolean(v) => v.toString
        case Lit.Null()     => "null"
        case _              => "undefined"
      (s"$scrutVar === $litJs", Nil)

    case Pat.Typed(inner, _) =>
      genPattern(scrutVar, inner)

    case Pat.Tuple(pats) =>
      val subConditions = pats.zipWithIndex.map { (p, i) =>
        genPattern(s"$scrutVar[$i]", p)
      }
      val cond = subConditions.map(_._1).filter(_ != "true").mkString(" && ")
      val bindings = subConditions.flatMap(_._2)
      (if cond.isEmpty then "true" else cond, bindings)

    case Pat.Extract.After_4_6_0(fn, argClause) =>
      val typeName = fn match
        case Term.Name(n)                 => n
        case Term.Select(_, Term.Name(n)) => n
        case _                            => "?"
      val args = argClause.values

      typeName match
        case "Some" =>
          val innerScrutVar = s"$scrutVar.value"
          val subConds = if args.isEmpty then Nil
            else args.zipWithIndex.map { (p, i) =>
              genPattern(if args.length == 1 then innerScrutVar else s"$innerScrutVar[$i]", p)
            }
          val subCond = subConds.map(_._1).filter(_ != "true").mkString(" && ")
          val bindings = subConds.flatMap(_._2)
          val cond = s"($scrutVar && $scrutVar._type === '_Some')" +
            (if subCond.nonEmpty then s" && $subCond" else "")
          (cond, bindings)

        case "None" =>
          (s"($scrutVar && $scrutVar._type === '_None')", Nil)

        case _ =>
          // Case class or enum case extract
          val fields = args.zipWithIndex.map { (p, i) =>
            genPattern(s"Object.values($scrutVar).slice(1)[$i]", p)
          }
          val typeCond = s"($scrutVar && $scrutVar._type === '$typeName')"
          val subCond = fields.map(_._1).filter(_ != "true").mkString(" && ")
          val bindings = fields.flatMap(_._2)
          val cond = typeCond + (if subCond.nonEmpty then s" && $subCond" else "")
          (cond, bindings)

    case Pat.Alternative(lhs, rhs) =>
      // Either alternative matches, no bindings from alternatives typically
      val (lCond, _) = genPattern(scrutVar, lhs)
      val (rCond, _) = genPattern(scrutVar, rhs)
      (s"($lCond || $rCond)", Nil)

    // Enum singleton reference: case Red => or case Color.Red =>
    case t: Term.Name =>
      t.value match
        case "None" => (s"($scrutVar && $scrutVar._type === '_None')", Nil)
        case n      => (s"($scrutVar === $n || ($scrutVar && $scrutVar._type === '$n'))", Nil)

    case Term.Select(_, Term.Name(n)) =>
      if n == "None" then (s"($scrutVar && $scrutVar._type === '_None')", Nil)
      else (s"($scrutVar === $n || ($scrutVar && $scrutVar._type === '$n'))", Nil)

    case _ =>
      ("true", Nil)

  private def genForDo(enums: List[Enumerator], body: Term): String =
    genForDoHelper(enums, genExpr(body))

  private def genForDoHelper(enums: List[Enumerator], bodyJs: String): String = enums match
    case Nil => s"(() => { $bodyJs; })()"
    case Enumerator.Generator(pat, rhs) :: rest =>
      val rhsJs = genExpr(rhs)
      val iterVar = freshTmp()
      val patJs = genForPatBinding(pat, iterVar)
      val inner = genForDoHelper(rest, bodyJs)
      s"(() => { _dispatch($rhsJs, 'forEach', [($iterVar) => { $patJs $inner; }]); })()"
    case Enumerator.Guard(cond) :: rest =>
      val condJs = genExpr(cond)
      val inner = genForDoHelper(rest, bodyJs)
      s"(() => { if ($condJs) { $inner; } })()"
    case Enumerator.Val(pat, rhs) :: rest =>
      val rhsJs = genExpr(rhs)
      val v = freshTmp()
      val patJs = genForPatBinding(pat, v)
      val inner = genForDoHelper(rest, bodyJs)
      s"(() => { const $v = $rhsJs; $patJs $inner; })()"
    case _ :: rest => genForDoHelper(rest, bodyJs)

  private def genForYield(enums: List[Enumerator], body: Term): String =
    genForYieldHelper(enums, genExpr(body))

  private def genForYieldHelper(enums: List[Enumerator], bodyJs: String): String = enums match
    case Nil => bodyJs
    case Enumerator.Generator(pat, rhs) :: Nil =>
      val rhsJs = genExpr(rhs)
      val iterVar = freshTmp()
      val patJs = genForPatBinding(pat, iterVar)
      if patJs.isEmpty then
        s"_dispatch($rhsJs, 'map', [($iterVar) => $bodyJs])"
      else
        s"_dispatch($rhsJs, 'map', [($iterVar) => { $patJs return $bodyJs; }])"
    case Enumerator.Generator(pat, rhs) :: rest =>
      val rhsJs = genExpr(rhs)
      val iterVar = freshTmp()
      val patJs = genForPatBinding(pat, iterVar)
      val inner = genForYieldHelper(rest, bodyJs)
      if patJs.isEmpty then
        s"_dispatch($rhsJs, 'flatMap', [($iterVar) => $inner])"
      else
        s"_dispatch($rhsJs, 'flatMap', [($iterVar) => { $patJs return $inner; }])"
    case Enumerator.Guard(cond) :: rest =>
      val condJs = genExpr(cond)
      val inner = genForYieldHelper(rest, bodyJs)
      // wrap in filter; but we need the generator context
      // For guard as first enum (unusual), filter is not trivially accessible
      // Return inner filtered - but we don't have a collection here, use conditional
      s"($condJs ? [$inner] : [])"
    case Enumerator.Val(pat, rhs) :: rest =>
      val rhsJs = genExpr(rhs)
      val v = freshTmp()
      val patJs = genForPatBinding(pat, v)
      val inner = genForYieldHelper(rest, bodyJs)
      s"(() => { const $v = $rhsJs; $patJs return $inner; })()"
    case _ :: rest => genForYieldHelper(rest, bodyJs)

  private def genForPatBinding(pat: Pat, scrutVar: String): String = pat match
    case Pat.Var(n) if n.value == scrutVar => ""
    case Pat.Var(n) => s"const ${n.value} = $scrutVar;"
    case Pat.Wildcard() => ""
    case Pat.Tuple(pats) =>
      pats.zipWithIndex.map { (p, i) =>
        p match
          case Pat.Var(n) => s"const ${n.value} = $scrutVar[$i];"
          case Pat.Wildcard() => ""
          case inner => genForPatBinding(inner, s"$scrutVar[$i]")
      }.mkString(" ")
    case Pat.Extract.After_4_6_0(_, argClause) =>
      argClause.values.zipWithIndex.map { (p, i) =>
        p match
          case Pat.Var(n) => s"const ${n.value} = Object.values($scrutVar).slice(1)[$i];"
          case _ => ""
      }.mkString(" ")
    case _ => ""

  private def mapName(name: String): String = name match
    case "println" => "_println"
    case "print"   => "_print"
    case "Some"    => "_Some"
    case "None"    => "_None"
    case other     => other

  /** Returns true if the term is provably integer-valued (no decimal arithmetic). */
  private def isIntExpr(t: Term): Boolean = t match
    case _: Lit.Int | _: Lit.Long                 => true
    case _: Lit.Double | _: Lit.Float              => false
    case Term.Name(n)                              => intVars.contains(n)
    case Term.Apply.After_4_6_0(Term.Select(_, Term.Name("toInt" | "toLong")), _) => true
    case Term.Apply.After_4_6_0(Term.Select(_, Term.Name("toDouble" | "toFloat")), _) => false
    case Term.ApplyInfix.After_4_6_0(l, Term.Name(op), _, argClause)
        if Set("+", "-", "*", "/", "%").contains(op) =>
      argClause.values.headOption.exists(r => isIntExpr(l) && isIntExpr(r))
    case _ => false

