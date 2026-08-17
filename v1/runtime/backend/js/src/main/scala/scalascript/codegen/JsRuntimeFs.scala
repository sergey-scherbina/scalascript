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
    // O_EXCL ('wx') create with a random name: fails if the path exists, so a
    // pre-planted symlink can't hijack the write (CWE-377).
    for (var i = 0; i < 1000; i++) {
      var rand = _nodeCrypto ? _nodeCrypto.randomBytes(9).toString('hex') : (Date.now() + '' + i);
      var name = tmp + '/' + prefix + rand + suffix;
      try {
        _nodeFs.closeSync(_nodeFs.openSync(name, 'wx', 0o600));
        return name;
      } catch (e) {
        if (e && e.code === 'EEXIST') continue;
        throw e;
      }
    }
    throw new Error('tempFile: could not create a unique temp file');
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
    // `input` is `opts.stdin`, written to the child and then the pipe is closed by spawnSync —
    // which is the whole point: a child reading to EOF would otherwise never see one.
    // (rozum `process-needs-a-stdin-pipe`: without it a secret can only travel through argv, where
    // any local process can read it off the command line.)
    //
    // `_Some(v)` is `{_type: '_Some', value: v}` here (core-dispatch.mjs) — NOT a `$tag`/`_1`
    // shape, which is what a case object uses two functions up in this same file. Reading the wrong
    // one would leave the option silently unset, which is the failure these options exist to avoid.
    var _sscOpt = function(o) {
      return (o && o._type === '_Some') ? o.value : undefined;
    };
    var _sscStdin = opts ? _sscOpt(opts.stdin) : undefined;
    var _sscCwd   = opts ? _sscOpt(opts.cwd)   : undefined;
    var _sscTmo   = opts ? _sscOpt(opts.timeout) : undefined;
    // `env` is a ScalaScript Map, which on this lane is a HAMT (`_Map` -> `_hamtOf`), not a plain
    // object — so it is walked through the `entries()` the HAMT exposes rather than with
    // `Object.keys`, which would silently see nothing and hand the child an EMPTY environment.
    var _sscEnvPairs = [];
    if (opts && opts.env && typeof opts.env.entries === 'function') {
      for (var _e of opts.env.entries()) { _sscEnvPairs.push([_e[0], _e[1]]); }
    }
    // `inheritEnv === false` SCRUBS BEFORE `env` IS APPLIED, never after: the flag means the child
    // sees only what the caller listed, and clearing afterwards would throw those away too. Same
    // ordering as every other lane, and the row that catches getting it backwards prints `[][]`
    // instead of `[][only]`.
    var _sscEnv = undefined;
    if (_sscEnvPairs.length > 0 || (opts && opts.inheritEnv === false)) {
      var _base = (opts && opts.inheritEnv === false) ? {} : Object.assign({}, process.env);
      for (var _i = 0; _i < _sscEnvPairs.length; _i++) { _base[_sscEnvPairs[_i][0]] = _sscEnvPairs[_i][1]; }
      _sscEnv = _base;
    }
    // `timeout` in MILLISECONDS, matching the declaration and the other lanes. spawnSync kills on
    // expiry and reports it through `signal`, which the exitCode line below already maps to -1 —
    // the answer `run`, `--v1`, jvm and build-rust all give for a timed-out child.
    var _sscSpawnOpts = { encoding: 'utf8', shell: false, input: _sscStdin };
    if (_sscCwd !== undefined) { _sscSpawnOpts.cwd = _sscCwd; }
    if (_sscEnv !== undefined) { _sscSpawnOpts.env = _sscEnv; }
    if (_sscTmo !== undefined && _sscTmo > 0) { _sscSpawnOpts.timeout = _sscTmo; }
    var result = _nodeProc.spawnSync(cmd, argsList, _sscSpawnOpts);
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

// `spawn(cmd, args, opts): Child` — start a child and return its pid WITHOUT waiting
// (rozum `process-needs-a-detached-spawn`).
//
// `detached: true` + `unref()` is the pair that makes the child OUTLIVE this process, and both are
// needed: `detached` puts it in its own process group so it does not die with the parent's, and
// `unref()` releases node's event-loop reference so this process can exit while the child runs.
// stdio is 'ignore' for the same reason the JVM lanes redirect to DISCARD — a child holding this
// process's pipes keeps its descriptors alive, and capturing would mean staying to drain.
//
// `stdin` cannot be written here and is REFUSED rather than dropped: with stdio 'ignore' there is no
// pipe, and wiring one back would mean holding the handle open across a call that has already
// returned. Refusing is the honest half — the alternative is a token that silently never arrives,
// which is the failure `process-needs-a-stdin-pipe` exists to prevent.
function __spawnPid(cmd, argsList, opts) {
  if (_nodeProc) {
    if (opts && opts.stdin && opts.stdin._type === '_Some') {
      throw new Error('spawn: opts.stdin is not supported on the js lane — use exec, or have the child read a file');
    }
    var child = _nodeProc.spawn(cmd, argsList, { detached: true, stdio: 'ignore', shell: false });
    child.unref();
    return child.pid;   // the .ssc wrapper builds `Child` — see std/process.ssc
  }
  throw new Error('ProcessNotSupported: spawn is not available in the browser');
}
"""
