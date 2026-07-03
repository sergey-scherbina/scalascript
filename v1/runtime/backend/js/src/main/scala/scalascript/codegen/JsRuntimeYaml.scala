package scalascript.codegen

/** std.yaml — JavaScript runtime: parseYaml / toYaml / accessor helpers.
 *
 *  Pure-JS implementation matching the SimpleYaml subset:
 *  block/flow mappings+sequences, scalars, single+double-quoted strings,
 *  null/bool/int/double, comments, literal block scalars.
 *
 *  Returns YamlValue objects: {_tag:"YStr",value}, {_tag:"YNum",value},
 *  {_tag:"YBool",value}, {_tag:"YNull"}, {_tag:"YArr",items:[...]},
 *  {_tag:"YObj",fields:{...}} — matching the InstanceV shape from the JVM plugin.
 *
 *  See specs/std-yaml.md and std-yaml-p3-js in SPRINT.md.
 */
object JsRuntimeYaml:
  val source: String = """
// ── std.yaml ─────────────────────────────────────────────────────────────────
// YamlValue constructors (match JVM InstanceV structure)
function _YStr(v)   { return { typeName: 'YStr',  fields: { value: v } }; }
function _YNum(v)   { return { typeName: 'YNum',  fields: { value: v } }; }
function _YBool(v)  { return { typeName: 'YBool', fields: { value: v } }; }
function _YNull()   { return { typeName: 'YNull', fields: {} }; }
function _YArr(items){ return { typeName: 'YArr', fields: { items: items } }; }
function _YObj(obj) {
  // obj is a plain JS object; store as MapV-compatible structure
  var fields = {};
  Object.keys(obj).forEach(function(k) { fields[k] = obj[k]; });
  return { typeName: 'YObj', _fields: fields, fields: { fields: obj } };
}

// ── Scalar coercion ───────────────────────────────────────────────────────────
function _yamlScalar(s) {
  var t = s.trim();
  if (t === '' || t === 'null' || t === '~') return _YNull();
  if (t === 'true' || t === 'True' || t === 'TRUE') return _YBool(true);
  if (t === 'false' || t === 'False' || t === 'FALSE') return _YBool(false);
  var n = Number(t);
  if (!isNaN(n) && t !== '') return _YNum(n);
  // strip quotes
  if (t.length >= 2 && t[0] === '"' && t[t.length-1] === '"')
    return _YStr(t.slice(1,-1).replace(/\\"/g,'"').replace(/\\n/g,'\n').replace(/\\t/g,'\t').replace(/\\\\/g,'\\'));
  if (t.length >= 2 && t[0] === "'" && t[t.length-1] === "'")
    return _YStr(t.slice(1,-1).replace(/''/g,"'"));
  return _YStr(t);
}

// ── Mini YAML parser ──────────────────────────────────────────────────────────
function _yamlParse(src) {
  var lines = src.split('\n');
  var pos = { i: 0 };

  function done() { return pos.i >= lines.length; }
  function cur()  { return lines[pos.i]; }
  function indent(l) { var j=0; while(j<l.length && l[j]===' ') j++; return j; }

  function stripComment(line) {
    var inS=false, inD=false, j=0, sb='';
    while(j<line.length){
      var c=line[j];
      if (c==="'" && !inD) { inS=!inS; sb+=c; }
      else if (c==='"' && !inS) { inD=!inD; sb+=c; }
      else if (c==='\\' && inD && j+1<line.length) { sb+=c+line[j+1]; j+=1; }
      else if (c==='#' && !inS && !inD) break;
      else sb+=c;
      j++;
    }
    return sb.trimEnd();
  }

  // Pre-strip comments
  var cl = lines.map(stripComment);

  function curClean() { return cl[pos.i]; }
  function skipBlank() { while(!done() && cl[pos.i].trim()==='') pos.i++; }

  function isSeqLine(l) { var t=l.trimStart(); return t.startsWith('- ') || t==='-'; }
  function isMapLine(l) { return findColon(l.trimStart()) >= 0; }

  function findColon(s) {
    var inS=false,inD=false,j=0;
    while(j<s.length){
      var c=s[j];
      if(c==="'" && !inD) inS=!inS;
      else if(c==='"' && !inS){ inD=!inD; }
      else if(c==='\\' && inD && j+1<s.length) j++;
      else if(c===':' && !inS && !inD){
        if(j+1>=s.length||s[j+1]===' '||s[j+1]==='\t') return j;
      }
      j++;
    }
    return -1;
  }

  function parseLiteralBlock(parentIndent) {
    skipBlank();
    if(done()||indent(curClean())<=parentIndent) return '';
    var blkInd = indent(curClean()), sb='';
    while(!done()&&(cl[pos.i].trim()===''||indent(curClean())>=blkInd)){
      if(cl[pos.i].trim()==='') sb+='\n';
      else { sb+=cl[pos.i].substring(blkInd)+'\n'; }
      pos.i++;
    }
    return sb.trimEnd();
  }

  function splitFlow(content) {
    var parts=[], sb='', depth=0, inS=false, inD=false, j=0;
    while(j<content.length){
      var c=content[j];
      if(c==="'" && !inD) { inS=!inS; sb+=c; }
      else if(c==='"' && !inS) { inD=!inD; sb+=c; }
      else if((c==='['||c==='{')&&!inS&&!inD){ depth++; sb+=c; }
      else if((c===']'||c==='}')&&!inS&&!inD){ depth--; sb+=c; }
      else if(c===','&&!inS&&!inD&&depth===0){ parts.push(sb); sb=''; }
      else sb+=c;
      j++;
    }
    var last=sb.trim(); if(last) parts.push(last);
    return parts;
  }

  function collectFlow(start, open, close) {
    var sb='', depth=0;
    function feed(s) {
      for(var j=0;j<s.length;j++){
        var c=s[j];
        if(c===open) { depth++; sb+=c; }
        else if(c===close){ if(depth===0) return true; depth--; sb+=c; }
        else if(c==="'"){sb+="'";j++;while(j<s.length&&s[j]!=="'"){sb+=s[j];j++;}if(j<s.length)sb+="'";}
        else if(c==='"'){sb+='"';j++;while(j<s.length&&s[j]!=='"'){if(s[j]==='\\'&&j+1<s.length){sb+='\\';sb+=s[j+1];j++;}else sb+=s[j];j++;}if(j<s.length)sb+='"';}
        else sb+=c;
      }
      return false;
    }
    var closed=feed(start);
    while(!closed&&!done()){ var ln=cl[pos.i]; pos.i++; closed=feed(ln); }
    return sb;
  }

  function parseFlowSeq(after) {
    var content=collectFlow(after,'[',']');
    return _YArr(splitFlow(content).filter(function(p){return p.trim()!=='';}).map(function(p){
      var t=p.trim();
      if(t.startsWith('{')) return parseFlowMap(t.slice(1));
      return _yamlScalar(t);
    }));
  }

  function parseFlowMap(after) {
    var content=collectFlow(after,'{','}');
    var obj={};
    splitFlow(content).forEach(function(entry){
      var t=entry.trim(), ci=findColon(t);
      if(ci>=0){
        var k=t.slice(0,ci).trim().replace(/^['"]|['"]$/g,'');
        obj[k]=_yamlScalar(t.slice(ci+1).trim());
      }
    });
    return _YObj(obj);
  }

  function parseInlineOrNext(ahead, parentIndent, fromMap) {
    if(ahead==='') {
      skipBlank();
      if(!done()&&indent(curClean())>parentIndent){
        if(isSeqLine(curClean())) return parseBlockSeq(indent(curClean()));
        if(isMapLine(curClean())) return parseBlockMap(indent(curClean()));
        var s=curClean().trim(); pos.i++; return _yamlScalar(s);
      }
      return _YNull();
    }
    if(ahead.startsWith('[')) return parseFlowSeq(ahead.slice(1));
    if(ahead.startsWith('{')) return parseFlowMap(ahead.slice(1));
    if(ahead==='|'||ahead==='>') return _YStr(parseLiteralBlock(parentIndent));
    return _yamlScalar(ahead);
  }

  function parseBlockMap(mapIndent) {
    var obj={};
    while(!done()){
      skipBlank(); if(done()||indent(curClean())!==mapIndent) break;
      var line=curClean().trimStart(), ci=findColon(line);
      if(ci<0) break;
      pos.i++;
      var k=line.slice(0,ci).trim().replace(/^['"]|['"]$/g,'');
      var after=line.slice(ci+1).trim();
      obj[k]=parseInlineOrNext(after,mapIndent,true);
    }
    return _YObj(obj);
  }

  function parseBlockSeq(seqIndent) {
    var arr=[];
    while(!done()){
      skipBlank(); if(done()||indent(curClean())!==seqIndent) break;
      var line=curClean().trimStart();
      if(!line.startsWith('-')) break;
      pos.i++;
      var content=(line.startsWith('- ')?line.slice(2):line.slice(1)).trim();
      arr.push(parseInlineOrNext(content,seqIndent,false));
    }
    return _YArr(arr);
  }

  // Document entry point
  skipBlank();
  if(done()) return _YNull();
  var first=curClean().trim();
  if(first.startsWith('[')) { pos.i++; return parseFlowSeq(first.slice(1)); }
  if(first.startsWith('{')) { pos.i++; return parseFlowMap(first.slice(1)); }
  var ind=indent(curClean());
  if(isSeqLine(curClean())) return parseBlockSeq(ind);
  if(isMapLine(curClean())) return parseBlockMap(ind);
  pos.i++; return _yamlScalar(first);
}

// ── Serializer ────────────────────────────────────────────────────────────────
function _yamlNeedsQuote(s) {
  if(s===''||s==='null'||s==='true'||s==='false'||s==='~') return true;
  if(!isNaN(Number(s))&&s!=='') return false;  // numbers don't need quotes
  return /[:#{}\[\],&*!|>'"@`]/.test(s)||s[0]===' '||s[s.length-1]===' '||s.includes(': ');
}
function _yamlQuote(s) {
  return _yamlNeedsQuote(s) ? "'"+s.replace(/'/g,"''")+"'" : s;
}
function _toYamlVal(v, indent) {
  var pad=' '.repeat(indent);
  if(!v||v.typeName==='YNull') return 'null';
  switch(v.typeName){
    case 'YBool': return String(v.fields.value);
    case 'YNum': {
      var n=v.fields.value;
      return (n===Math.trunc(n)&&isFinite(n)) ? String(Math.trunc(n)) : String(n);
    }
    case 'YStr': return _yamlQuote(String(v.fields.value));
    case 'YArr': {
      var items=v.fields.items||[];
      if(!items.length) return '[]';
      return items.map(function(item){
        var r=_toYamlVal(item,indent+2);
        return pad+'- '+r;
      }).join('\n');
    }
    case 'YObj': {
      var obj=v._fields||v.fields.fields||{};
      var keys=Object.keys(obj).sort();
      if(!keys.length) return '{}';
      return keys.map(function(k){
        var r=_toYamlVal(obj[k],indent+2);
        return r.includes('\n') ? pad+_yamlQuote(k)+':\n'+r : pad+_yamlQuote(k)+': '+r;
      }).join('\n');
    }
    default: return _yamlQuote(String(v));
  }
}

// Public API
function parseYaml(s) { return _yamlParse(s); }
function toYaml(v)    { return _toYamlVal(v, 0)+'\n'; }
function yamlType(v)  { return v && v.typeName ? v.typeName : 'unknown'; }
function yamlStr(v)   { return (v&&v.typeName==='YStr')  ? v.fields.value : null; }
function yamlNum(v)   { return (v&&v.typeName==='YNum')  ? v.fields.value : 0; }
function yamlBool(v)  { return (v&&v.typeName==='YBool') ? v.fields.value : false; }
function yamlArr(v)   { return (v&&v.typeName==='YArr')  ? v.fields.items : []; }
function yamlGet(v, k){ return (v&&v.typeName==='YObj')  ? ((v._fields||{})[k]||_YNull()) : _YNull(); }
"""
