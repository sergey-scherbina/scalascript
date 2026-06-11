package scalascript.codegen

/** Browser-runtime JS for the std/ui/content toolkit layer
 *  (`contentToolkitNode` / `contentToolkitBlock` / `contentToolkitSection`).
 *
 *  This is a faithful port of JvmGen's `_ssc_tk_*` content-toolkit helpers, so
 *  the JS backend renders authored Markdown content (sections, blocks,
 *  `toolkit:` control links, `@ui=toolkit` YAML control trees, GFM tables, and
 *  registered components) into a TkNode tree at parity with the JVM-codegen
 *  backend.  It is emitted by `JsGen.emitContentToolkitRuntime` only when a
 *  module imports `std/ui/content`, and relies on the std/content runtime
 *  helpers (`__ssc_content_*`, `contentDocument`, `contentData`, `contentBind`)
 *  plus `_ssc_ui_signal`, `_call`, `_show`, `Some`/`None`.
 *
 *  TkNode values are built as `{_type:'<Name>', <field>}` — the exact shape a
 *  `.ssc` case-class constructor compiles to — so `lower(tree, theme)` consumes
 *  them unchanged.  Field names mirror the constructors in `std/ui/nodes.ssc`.
 */
object ContentToolkitJs:
  val source: String =
    """
// ── std/ui/content toolkit runtime (parity with JvmGen _ssc_tk_*) ───────────
function _ssc_tk_error(msg) { throw new Error(msg); }

function _ssc_tk_str(value, ctx) {
  if (value && value._type === 'Str') return value.value;
  _ssc_tk_error("contentToolkitNode: " + ctx + " expected String, got " + _show(value));
}
function _ssc_tk_bool(value, ctx) {
  if (value && value._type === 'Bool') return value.value;
  _ssc_tk_error("contentToolkitNode: " + ctx + " expected Boolean, got " + _show(value));
}
function _ssc_tk_int(value, ctx) {
  if (value && value._type === 'Num') return Math.trunc(value.value);
  _ssc_tk_error("contentToolkitNode: " + ctx + " expected Number, got " + _show(value));
}
function _ssc_tk_scalar(value, ctx) {
  if (value) switch (value._type) {
    case 'Str': return value.value;
    case 'Bool': return value.value;
    case 'Num': return value.value;
    case 'NullV': return null;
  }
  _ssc_tk_error("contentToolkitNode: " + ctx + " expected scalar, got " + _show(value));
}
function _ssc_tk_map(value, ctx) {
  if (value && value._type === 'MapV') return value.values;
  _ssc_tk_error("contentToolkitNode: " + ctx + " expected object, got " + _show(value));
}
function _ssc_tk_list(value, ctx) {
  if (value && value._type === 'ListV') return value.values;
  _ssc_tk_error("contentToolkitNode: " + ctx + " expected list, got " + _show(value));
}
function _ssc_tk_field(obj, name, ctx) {
  if (obj.has(name)) return obj.get(name);
  _ssc_tk_error("contentToolkitNode: " + ctx + " requires " + name);
}
function _ssc_tk_opt_str(obj, name) { return obj.has(name) ? _ssc_tk_str(obj.get(name), name) : null; }
function _ssc_tk_opt_bool(obj, name, dflt) { return obj.has(name) ? _ssc_tk_bool(obj.get(name), name) : (dflt || false); }
function _ssc_tk_opt_int(obj, name, dflt) { return obj.has(name) ? _ssc_tk_int(obj.get(name), name) : dflt; }

function _ssc_tk_default_options() {
  return { includeCode: false, sectionGap: 16, blockGap: 8, listGap: 4,
           wrapDocumentInCard: false, wrapTopLevelSectionsInCards: false,
           components: [], bindings: std.content.MapV(new Map()),
           actions: new Map(), rowBindings: new Map() };
}
function _ssc_tk_options(options) { return options ? options : _ssc_tk_default_options(); }

function _ssc_tk_signals(root) {
  var env = {};
  var sigs = root.get('signals');
  if (sigs == null) return env;
  if (sigs._type !== 'MapV') _ssc_tk_error("contentToolkitNode: signals expected object, got " + _show(sigs));
  sigs.values.forEach(function(value, name) {
    env[name] = _ssc_ui_signal(name, _ssc_tk_scalar(value, "signal '" + name + "' default"));
  });
  return env;
}
function _ssc_tk_signal(env, name, ctx) {
  if (Object.prototype.hasOwnProperty.call(env, name)) return env[name];
  _ssc_tk_error("contentToolkitNode: " + ctx + " references unknown signal '" + name + "'");
}

function _ssc_tk_decode(value) {
  try { return decodeURIComponent(String(value).replace(/\+/g, ' ')); } catch (e) { return String(value); }
}
function _ssc_tk_normalize_kind(value) { return String(value).toLowerCase().replace(/[-_]/g, ''); }
function _ssc_tk_parse_link(href, label) {
  var raw = href.indexOf('toolkit:') === 0 ? href.slice('toolkit:'.length) : href;
  var q = raw.indexOf('?');
  var kindPart = q < 0 ? raw : raw.slice(0, q);
  var queryPart = q < 0 ? '' : raw.slice(q + 1);
  var kind = _ssc_tk_normalize_kind(_ssc_tk_decode(kindPart));
  if (!kind) _ssc_tk_error("contentToolkitNode: toolkit link requires a control kind");
  var query = {};
  if (queryPart) queryPart.split('&').filter(function(p) { return p.length > 0; }).forEach(function(pair) {
    var eq = pair.indexOf('=');
    if (eq < 0) query[_ssc_tk_decode(pair)] = '';
    else query[_ssc_tk_decode(pair.slice(0, eq))] = _ssc_tk_decode(pair.slice(eq + 1));
  });
  return { kind: kind, query: query, label: label };
}
function _ssc_tk_single_link(inlines) {
  var sig = (inlines || []).filter(function(i) { return i._type === 'Text' ? i.value.trim().length > 0 : true; });
  if (sig.length === 1 && sig[0]._type === 'Link' && sig[0].href.indexOf('toolkit:') === 0) {
    return _ssc_tk_parse_link(sig[0].href, (sig[0].label || []).map(__ssc_content_inline_plain_text).join(''));
  }
  return null;
}
function _ssc_tk_has(query, name) { return Object.prototype.hasOwnProperty.call(query, name); }
function _ssc_tk_link_bool_opt(link, name) {
  if (!_ssc_tk_has(link.query, name)) return undefined;
  var v = link.query[name];
  if (v === 'true') return true;
  if (v === 'false') return false;
  _ssc_tk_error("contentToolkitNode: toolkit:" + link.kind + " " + name + " expected true or false, got '" + v + "'");
}
function _ssc_tk_link_bool(link, name, dflt) { var b = _ssc_tk_link_bool_opt(link, name); return b === undefined ? dflt : b; }
function _ssc_tk_link_label(link) { return _ssc_tk_has(link.query, 'label') ? link.query['label'] : link.label; }
function _ssc_tk_required_query(link, name) {
  var v = link.query[name];
  if (v != null && v.length > 0) return v;
  _ssc_tk_error("contentToolkitNode: toolkit:" + link.kind + " requires " + name);
}
function _ssc_tk_link_literal(value) {
  if (value === 'true') return true;
  if (value === 'false') return false;
  return value;
}
function _ssc_tk_link_signal_default(link) {
  if (!_ssc_tk_has(link.query, 'signal')) return null;
  var name = link.query['signal'];
  var initial;
  switch (link.kind) {
    case 'textfield': case 'input': initial = _ssc_tk_has(link.query, 'value') ? link.query['value'] : ''; break;
    case 'checkbox': { var a = _ssc_tk_link_bool_opt(link, 'checked'); var b = a === undefined ? _ssc_tk_link_bool_opt(link, 'value') : a; initial = b === undefined ? false : b; break; }
    case 'button': case 'signalbutton': initial = false; break;
    case 'signaltext': initial = _ssc_tk_has(link.query, 'value') ? link.query['value'] : ''; break;
    default: initial = ''; break;
  }
  return [name, initial];
}
function _ssc_tk_link_node(link, env) {
  switch (link.kind) {
    case 'textfield': case 'input':
      return { _type: 'TextFieldNode', value: _ssc_tk_signal(env, _ssc_tk_required_query(link, 'signal'), 'toolkit:textField'),
               label: _ssc_tk_link_label(link), disabled: _ssc_tk_link_bool(link, 'disabled', false), required: _ssc_tk_link_bool(link, 'required', false) };
    case 'checkbox':
      return { _type: 'CheckboxNode', checked: _ssc_tk_signal(env, _ssc_tk_required_query(link, 'signal'), 'toolkit:checkbox'),
               label: _ssc_tk_link_label(link), disabled: _ssc_tk_link_bool(link, 'disabled', false) };
    case 'button': case 'signalbutton': {
      var signal = _ssc_tk_signal(env, _ssc_tk_required_query(link, 'signal'), 'toolkit:button');
      var value = _ssc_tk_link_literal(_ssc_tk_has(link.query, 'value') ? link.query['value'] : 'true');
      var disabled = _ssc_tk_link_bool(link, 'disabled', false);
      var label = _ssc_tk_link_label(link);
      if (_ssc_tk_has(link.query, 'enabledWhen'))
        return { _type: 'ShowWhenNode', signal: _ssc_tk_signal(env, link.query['enabledWhen'], 'toolkit:button.enabledWhen'),
                 whenTrue: { _type: 'SignalButtonNode', signal: signal, value: value, label: label, disabled: disabled },
                 whenFalse: { _type: 'SignalButtonNode', signal: signal, value: value, label: label, disabled: true } };
      return { _type: 'SignalButtonNode', signal: signal, value: value, label: label, disabled: disabled };
    }
    case 'signaltext':
      return { _type: 'SignalTextNode', signal: _ssc_tk_signal(env, _ssc_tk_required_query(link, 'signal'), 'toolkit:signalText') };
    case 'badge':
      return { _type: 'BadgeNode', content: _ssc_tk_has(link.query, 'text') ? link.query['text'] : _ssc_tk_link_label(link),
               variant: _ssc_tk_has(link.query, 'variant') ? link.query['variant'] : 'default' };
    case 'divider':
      return { _type: 'DividerNode' };
    default:
      _ssc_tk_error("contentToolkitNode: unsupported toolkit link control '" + link.kind + "'");
  }
}

function _ssc_tk_markdown_signal_defaults_block(block) {
  switch (block._type) {
    case 'Paragraph': { var l = _ssc_tk_single_link(block.inlines); if (!l) return []; var d = _ssc_tk_link_signal_default(l); return d ? [d] : []; }
    case 'BulletList': case 'OrderedList': return block.items.flat().flatMap(_ssc_tk_markdown_signal_defaults_block);
    default: return [];
  }
}
function _ssc_tk_markdown_signal_defaults_section(section) {
  return section.blocks.flatMap(_ssc_tk_markdown_signal_defaults_block)
    .concat(section.children.flatMap(_ssc_tk_markdown_signal_defaults_section));
}
function _ssc_tk_markdown_signal_defaults_doc(doc) {
  return doc.blocks.flatMap(_ssc_tk_markdown_signal_defaults_block)
    .concat(doc.sections.flatMap(_ssc_tk_markdown_signal_defaults_section));
}
function _ssc_tk_markdown_env(defaults) {
  var env = {};
  defaults.forEach(function(pair) { var name = pair[0]; if (!Object.prototype.hasOwnProperty.call(env, name)) env[name] = _ssc_ui_signal(name, pair[1]); });
  return env;
}

function _ssc_tk_list_item(item, env) {
  if (item.length === 1 && item[0]._type === 'Paragraph') {
    var l = _ssc_tk_single_link(item[0].inlines);
    return l ? _ssc_tk_link_node(l, env) : null;
  }
  return null;
}
function _ssc_tk_markdown_block(block, options, env) {
  switch (block._type) {
    case 'Paragraph': { var l = _ssc_tk_single_link(block.inlines); return l ? _ssc_tk_link_node(l, env) : null; }
    case 'BulletList': case 'OrderedList': {
      var rendered = block.items.map(function(item) { return _ssc_tk_list_item(item, env); });
      if (!rendered.some(function(n) { return n != null; })) return null;
      var ordered = block._type === 'OrderedList';
      return { _type: 'VStackNode', gap: options.listGap, children: block.items.map(function(item, i) {
        if (rendered[i] != null) return rendered[i];
        var prefix = ordered ? (String(block.start + i) + '. ') : '- ';
        return { _type: 'RawTextNode', text: prefix + item.map(__ssc_content_block_plain_text).join(' ') };
      }) };
    }
    default: return null;
  }
}

function _ssc_tk_render_control(value, env) {
  var obj = _ssc_tk_map(value, 'control');
  var kind = _ssc_tk_str(_ssc_tk_field(obj, 'type', 'control'), 'control.type');
  switch (kind) {
    case 'vstack': return { _type: 'VStackNode', gap: _ssc_tk_opt_int(obj, 'gap', 8), children: _ssc_tk_children(obj, env) };
    case 'hstack': return { _type: 'HStackNode', gap: _ssc_tk_opt_int(obj, 'gap', 8), children: _ssc_tk_children(obj, env) };
    case 'fragment': return { _type: 'FragmentNode', children: _ssc_tk_children(obj, env) };
    case 'divider': return { _type: 'DividerNode' };
    case 'heading': return { _type: 'HeadingNode', level: obj.has('level') ? _ssc_tk_int(obj.get('level'), 'heading.level') : 2, text: _ssc_tk_str(_ssc_tk_field(obj, 'text', 'heading'), 'heading.text') };
    case 'text': return { _type: 'TextNode_', text: _ssc_tk_str(_ssc_tk_field(obj, 'text', 'text'), 'text.text') };
    case 'rawText': return { _type: 'RawTextNode', text: _ssc_tk_str(_ssc_tk_field(obj, 'text', 'rawText'), 'rawText.text') };
    case 'signalText': return { _type: 'SignalTextNode', signal: _ssc_tk_signal(env, _ssc_tk_str(_ssc_tk_field(obj, 'signal', 'signalText'), 'signalText.signal'), 'signalText') };
    case 'show': {
      var name = _ssc_tk_str(_ssc_tk_field(obj, 'signal', 'show'), 'show.signal');
      var whenTrue = _ssc_tk_render_control(_ssc_tk_field(obj, 'then', 'show'), env);
      var whenFalse = obj.has('else') ? _ssc_tk_render_control(obj.get('else'), env) : { _type: 'FragmentNode', children: [] };
      return { _type: 'ShowWhenNode', signal: _ssc_tk_signal(env, name, 'show'), whenTrue: whenTrue, whenFalse: whenFalse };
    }
    case 'textField': {
      var tname = _ssc_tk_str(_ssc_tk_field(obj, 'signal', 'textField'), 'textField.signal');
      return { _type: 'TextFieldNode', value: _ssc_tk_signal(env, tname, 'textField'), label: _ssc_tk_str(_ssc_tk_field(obj, 'label', 'textField'), 'textField.label'), disabled: _ssc_tk_opt_bool(obj, 'disabled', false), required: _ssc_tk_opt_bool(obj, 'required', false) };
    }
    case 'checkbox': {
      var cname = _ssc_tk_str(_ssc_tk_field(obj, 'signal', 'checkbox'), 'checkbox.signal');
      return { _type: 'CheckboxNode', checked: _ssc_tk_signal(env, cname, 'checkbox'), label: _ssc_tk_str(_ssc_tk_field(obj, 'label', 'checkbox'), 'checkbox.label'), disabled: _ssc_tk_opt_bool(obj, 'disabled', false) };
    }
    case 'button': {
      var bname = _ssc_tk_str(_ssc_tk_field(obj, 'signal', 'button'), 'button.signal');
      var bvalue = obj.has('value') ? _ssc_tk_scalar(obj.get('value'), 'button.value') : true;
      return { _type: 'SignalButtonNode', signal: _ssc_tk_signal(env, bname, 'button'), value: bvalue, label: _ssc_tk_str(_ssc_tk_field(obj, 'label', 'button'), 'button.label'), disabled: _ssc_tk_opt_bool(obj, 'disabled', false) };
    }
    case 'badge': { var variant = _ssc_tk_opt_str(obj, 'variant'); return { _type: 'BadgeNode', content: _ssc_tk_str(_ssc_tk_field(obj, 'text', 'badge'), 'badge.text'), variant: variant != null ? variant : 'default' }; }
    case 'card': return { _type: 'CardNode', header: null, body: _ssc_tk_children(obj, env), footer: null };
    default: _ssc_tk_error("contentToolkitNode: unsupported control type '" + kind + "'");
  }
}
function _ssc_tk_children(obj, env) {
  if (!obj.has('children')) return [];
  return _ssc_tk_list(obj.get('children'), 'children').map(function(c) { return _ssc_tk_render_control(c, env); });
}

function _ssc_tk_yaml_block(block, baseEnv) {
  if (block._type === 'Embedded' && block.kind && block.kind._type === 'StructuredData'
      && __ssc_content_string_attr(__ssc_content_block_attrs(block), 'ui') === 'toolkit'
      && block.data && block.data._type === '_Some') {
    var root = _ssc_tk_map(block.data.value, '@ui=toolkit');
    var env = Object.assign({}, baseEnv, _ssc_tk_signals(root));
    return _ssc_tk_render_control(_ssc_tk_field(root, 'controls', '@ui=toolkit'), env);
  }
  return null;
}

function _ssc_tk_component_data(attrs) {
  var name = __ssc_content_string_attr(attrs, 'data');
  return name != null ? contentData(name) : None;
}
function _ssc_tk_component_for(name, options) {
  return (options.components || []).find(function(c) { return c.name === name; }) || null;
}
function _ssc_tk_ctx(name, kind, id, title, attrs, section, block, data) {
  return { _type: 'ContentComponentContext', name: name, kind: kind, id: id, title: title, attrs: attrs, section: section, block: block, data: data };
}
function _ssc_tk_table_cell(cell) {
  return { _type: 'TextNode_', text: cell.map(__ssc_content_inline_plain_text).join('') };
}
function _ssc_tk_table(headers, rows) {
  var columns = headers.map(function(header, idx) { return { _type: 'TableColumn', label: header.map(__ssc_content_inline_plain_text).join(''), key: 'col' + idx }; });
  return { _type: 'TableNode', columns: columns, rows: rows.map(function(row) { return row.map(_ssc_tk_table_cell); }), sortCol: null };
}
function _ssc_tk_block(block, options, env) {
  var attrs = __ssc_content_block_attrs(block);
  var compName = __ssc_content_string_attr(attrs, 'component');
  if (compName != null) {
    var comp = _ssc_tk_component_for(compName, options);
    if (comp) return _call(comp.render, _ssc_tk_ctx(compName, 'block', __ssc_content_string_attr(attrs, 'id') || '', None, attrs, None, __ssc_content_opt(block), _ssc_tk_component_data(attrs)));
  }
  var y = _ssc_tk_yaml_block(block, env); if (y != null) return y;
  var m = _ssc_tk_markdown_block(block, options, env); if (m != null) return m;
  if (block._type === 'Table') return _ssc_tk_table(block.headers, block.rows);
  return { _type: 'TextNode_', text: __ssc_content_block_plain_text(block) };
}
function _ssc_tk_section(section, options, env) {
  var compName = __ssc_content_string_attr(section.attrs, 'component');
  if (compName != null) {
    var comp = _ssc_tk_component_for(compName, options);
    if (comp) return _call(comp.render, _ssc_tk_ctx(compName, 'section', section.id, __ssc_content_opt(section.title), section.attrs, __ssc_content_opt(section), None, _ssc_tk_component_data(section.attrs)));
  }
  var children = [{ _type: 'HeadingNode', level: section.level, text: section.title }].concat(
    section.blocks.map(function(b) { return _ssc_tk_block(b, options, env); }),
    section.children.map(function(s) { return _ssc_tk_section(s, options, env); }));
  return { _type: 'VStackNode', gap: options.blockGap, children: children };
}
function _ssc_tk_document(options) { return contentBind(contentDocument(), options.bindings); }
function _ssc_tk_block_by_id(doc, id) {
  var matches = __ssc_content_blocks_deep(doc).filter(function(b) { return __ssc_content_string_attr(__ssc_content_block_attrs(b), 'id') === id; });
  if (matches.length === 0) return null;
  if (matches.length === 1) return matches[0];
  _ssc_tk_error("contentToolkitBlock: duplicate block id '" + id + "'");
}
function _ssc_tk_section_by_id(doc, id) {
  var matches = __ssc_content_sections_deep_doc(doc).filter(function(s) { return s.id === id; });
  if (matches.length === 0) return null;
  if (matches.length === 1) return matches[0];
  _ssc_tk_error("contentToolkitSection: duplicate section id '" + id + "'");
}

function contentToolkitNode(options) {
  options = _ssc_tk_options(options);
  var doc = _ssc_tk_document(options);
  var env = _ssc_tk_markdown_env(_ssc_tk_markdown_signal_defaults_doc(doc));
  return { _type: 'VStackNode', gap: options.sectionGap, children: doc.blocks.map(function(b) { return _ssc_tk_block(b, options, env); })
    .concat(doc.sections.map(function(s) { return _ssc_tk_section(s, options, env); })) };
}
function contentToolkitBlock(id, options) {
  options = _ssc_tk_options(options);
  var block = _ssc_tk_block_by_id(_ssc_tk_document(options), id);
  if (block == null) _ssc_tk_error("contentToolkitBlock: no block with id '" + id + "'");
  return _ssc_tk_block(block, options, _ssc_tk_markdown_env(_ssc_tk_markdown_signal_defaults_block(block)));
}
function contentToolkitSection(id, options) {
  options = _ssc_tk_options(options);
  var section = _ssc_tk_section_by_id(_ssc_tk_document(options), id);
  if (section == null) _ssc_tk_error("contentToolkitSection: no section with id '" + id + "'");
  return _ssc_tk_section(section, options, _ssc_tk_markdown_env(_ssc_tk_markdown_signal_defaults_section(section)));
}
"""
