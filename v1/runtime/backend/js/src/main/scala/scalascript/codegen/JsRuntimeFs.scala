package scalascript.codegen

/** std.fs / std.os / std.process — JavaScript runtime implementations.
 *
 *  Node.js: delegates to `node:fs`, `node:path`, `node:os`, `node:child_process`.
 *  Browser: throws descriptive errors for file-system and process ops;
 *           env/args return safe defaults; platform = 'Browser'.
 *
 *  Loaded unconditionally as part of the JS preamble (same as JsRuntimeCoreCollections).
 *  See `specs/std-fs-os.md` and `std-fs-os-p3-js` in SPRINT.md.
 */
object JsRuntimeFs:
  val source: String = """
// ── std.fs + std.os + std.process ───────────────────────────────────────────
var _nodeFs   = (typeof require !== 'undefined') ? require('fs')           : null;
var _nodePath = (typeof require !== 'undefined') ? require('path')         : null;
var _nodeOs   = (typeof require !== 'undefined') ? require('os')           : null;
var _nodeProc = (typeof require !== 'undefined') ? require('child_process'): null;

// ── std.fs ────────────────────────────────────────────────────────────────────
function readFile(path) {
  if (_nodeFs) return _nodeFs.readFileSync(path, 'utf8');
  throw new Error('FsNotSupported: readFile is not available in the browser');
}
function writeFile(path, contents) {
  if (_nodeFs) { _nodeFs.writeFileSync(path, contents, 'utf8'); return; }
  throw new Error('FsNotSupported: writeFile is not available in the browser');
}
function appendFile(path, contents) {
  if (_nodeFs) { _nodeFs.appendFileSync(path, contents, 'utf8'); return; }
  throw new Error('FsNotSupported: appendFile is not available in the browser');
}
function readBytes(path) {
  if (_nodeFs) {
    var buf = _nodeFs.readFileSync(path);
    return Array.from(buf).map(function(b) { return b & 0xFF; });
  }
  throw new Error('FsNotSupported: readBytes is not available in the browser');
}
function writeBytes(path, bytes) {
  if (_nodeFs) { _nodeFs.writeFileSync(path, Buffer.from(bytes)); return; }
  throw new Error('FsNotSupported: writeBytes is not available in the browser');
}
function exists(path) {
  if (_nodeFs) return _nodeFs.existsSync(path);
  return false;
}
function isFile(path) {
  if (_nodeFs) {
    try { return _nodeFs.statSync(path).isFile(); } catch(_) { return false; }
  }
  return false;
}
function isDir(path) {
  if (_nodeFs) {
    try { return _nodeFs.statSync(path).isDirectory(); } catch(_) { return false; }
  }
  return false;
}
function mkdir(path) {
  if (_nodeFs) { try { _nodeFs.mkdirSync(path); } catch(_) {} return; }
  throw new Error('FsNotSupported: mkdir is not available in the browser');
}
function mkdirs(path) {
  if (_nodeFs) { _nodeFs.mkdirSync(path, { recursive: true }); return; }
  throw new Error('FsNotSupported: mkdirs is not available in the browser');
}
function listDir(path) {
  if (_nodeFs) return _nodeFs.readdirSync(path);
  throw new Error('FsNotSupported: listDir is not available in the browser');
}
function deleteFile(path) {
  if (_nodeFs) { try { _nodeFs.rmSync(path, { force: true }); } catch(_) {} return; }
  throw new Error('FsNotSupported: deleteFile is not available in the browser');
}
function copyFile(src, dst) {
  if (_nodeFs) { _nodeFs.copyFileSync(src, dst); return; }
  throw new Error('FsNotSupported: copyFile is not available in the browser');
}
function moveFile(src, dst) {
  if (_nodeFs) { _nodeFs.renameSync(src, dst); return; }
  throw new Error('FsNotSupported: moveFile is not available in the browser');
}

// ── std.os ────────────────────────────────────────────────────────────────────
function env(key) {
  if (typeof process !== 'undefined' && process.env) {
    var v = process.env[key];
    return (v !== undefined) ? _Some(v) : _None;
  }
  return _None;
}
function envOrElse(key, def) {
  if (typeof process !== 'undefined' && process.env) {
    var v = process.env[key];
    return (v !== undefined) ? v : def;
  }
  return def;
}
function args() {
  if (typeof process !== 'undefined' && process.argv) return process.argv.slice(2);
  return [];
}
function exit(code) {
  if (typeof process !== 'undefined') process.exit(code);
  throw new Error('exit(' + code + ')');
}
function cwd() {
  if (typeof process !== 'undefined') return process.cwd();
  return '/';
}
function sep() {
  if (_nodePath) return _nodePath.sep;
  return '/';
}
function pathJoin() {
  var parts = Array.from(arguments);
  if (_nodePath) return _nodePath.join.apply(null, parts);
  return parts.join('/');
}
function pathDirname(path) {
  if (_nodePath) return _nodePath.dirname(path);
  var i = path.lastIndexOf('/');
  return i > 0 ? path.slice(0, i) : '.';
}
function pathBasename(path) {
  if (_nodePath) return _nodePath.basename(path);
  return path.split('/').pop();
}
function pathExtname(path) {
  if (_nodePath) return _nodePath.extname(path);
  var base = path.split('/').pop();
  var dot = base.lastIndexOf('.');
  return dot > 0 ? base.slice(dot) : '';
}
function pathResolve(path) {
  if (_nodePath) return _nodePath.resolve(path);
  return path;
}
function pathIsAbsolute(path) {
  if (_nodePath) return _nodePath.isAbsolute(path);
  return path.startsWith('/');
}
function tempDir() {
  if (_nodeOs) return _nodeOs.tmpdir();
  return '/tmp';
}
function tempFile(prefix, suffix) {
  if (_nodeFs && _nodeOs) {
    var tmp = _nodeOs.tmpdir();
    var name = tmp + '/' + prefix + Date.now() + suffix;
    _nodeFs.writeFileSync(name, '');
    return name;
  }
  throw new Error('FsNotSupported: tempFile is not available in the browser');
}
function platform() {
  if (typeof process !== 'undefined' && process.versions && process.versions.node) {
    return { $tag: 1, _1: 'NodeJs' };   // NodeJs case object
  }
  return { $tag: 2, _1: 'Browser' };    // Browser case object
}
function homedir() {
  if (_nodeOs) return _nodeOs.homedir();
  return '/';
}
function hostname() {
  if (_nodeOs) return _nodeOs.hostname();
  if (typeof location !== 'undefined') return location.hostname;
  return 'localhost';
}

// ── std.process ───────────────────────────────────────────────────────────────
function exec(cmd, argsList, opts) {
  if (_nodeProc) {
    var result = _nodeProc.spawnSync(cmd, argsList, { encoding: 'utf8', shell: false });
    return {
      stdout:   result.stdout || '',
      stderr:   result.stderr || '',
      // status is null on signal-kill or spawn error (ENOENT); don't collapse that to 0
      // (a success code) — a security gate keying on exitCode !== 0 would be bypassed.
      exitCode: (result.status != null ? result.status : ((result.signal || result.error) ? -1 : 0))
    };
  }
  throw new Error('ProcessNotSupported: exec is not available in the browser');
}
"""
