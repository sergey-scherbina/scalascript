# `std.fs` / `std.os` / `std.process` — Filesystem, OS & Process Abstraction

Status: **planned**.  Tracked as `std-fs-os` milestone in `BACKLOG.md`.
Related: [`specs/backend-specific-blocks.md`](backend-specific-blocks.md),
[`specs/arch-ffi.md`](arch-ffi.md).

---

## 1. Motivation

`.ssc` user code must never import `java.*`, `javax.*`, or Node.js globals
(`process.env`, `fs.readFileSync`, …) in regular `scalascript` blocks —
those are compile errors (see `specs/backend-specific-blocks.md §1`).

The standard library provides cross-backend modules that give the correct
behaviour on every target:

| Module | Purpose |
|--------|---------|
| `std.fs` | File-system operations |
| `std.os` | OS environment: env vars, CLI args, paths, platform |
| `std.process` | Process management: exec, spawn, signal |

`.sc` Scala-CLI host scripts (`bench/run.sc`, `tests/e2e/spa-smoke.sc`)
may use JVM APIs directly — they are tooling, not `.ssc` user code.

---

## 2. `std.fs` — File-system operations

**Capability gate:** `Feature.FileSystem`

### 2.1 API

```scalascript
// ── Basic I/O ───────────────────────────────────────────────────────────────
extern def readFile(path: String): String
extern def writeFile(path: String, contents: String): Unit
extern def appendFile(path: String, contents: String): Unit
extern def readBytes(path: String): List[Int]   // each byte as 0–255
extern def writeBytes(path: String, bytes: List[Int]): Unit

// ── File-system predicates ───────────────────────────────────────────────────
extern def exists(path: String): Boolean
extern def isFile(path: String): Boolean
extern def isDir(path: String): Boolean

// ── Directory ops ────────────────────────────────────────────────────────────
extern def mkdir(path: String): Unit       // creates one level; noop if exists
extern def mkdirs(path: String): Unit      // creates all levels; noop if exists
extern def listDir(path: String): List[String]  // filenames, not full paths

// ── File management ──────────────────────────────────────────────────────────
extern def deleteFile(path: String): Unit  // noop if not exists
extern def copyFile(src: String, dst: String): Unit
extern def moveFile(src: String, dst: String): Unit
```

### 2.2 Error handling

File operations throw `FsError` (a sealed trait), never a raw JVM exception:

```scalascript
sealed trait FsError extends RuntimeException
case class FsNotFound(path: String)          extends FsError
case class FsPermissionDenied(path: String)  extends FsError
case class FsNotSupported(op: String)        extends FsError
case class FsIoError(path: String, msg: String) extends FsError
```

Browser: all operations throw `FsNotSupported("browser-sandbox")` except
`readFile` / `writeFile` which may target the Origin Private File System
(future milestone; currently throw `FsNotSupported`).

### 2.3 Backend implementations

| Operation | JVM | JS/Node | Browser | Rust |
|-----------|-----|---------|---------|------|
| `readFile` | `Files.readString(Path.of(path))` | `fs.readFileSync(path, 'utf8')` | `FsNotSupported` | `std::fs::read_to_string(path)` |
| `writeFile` | `Files.writeString(…)` | `fs.writeFileSync(path, …)` | `FsNotSupported` | `std::fs::write(path, …)` |
| `appendFile` | `Files.write(…, APPEND)` | `fs.appendFileSync` | `FsNotSupported` | `OpenOptions::append` |
| `exists` | `Files.exists(Path.of(path))` | `fs.existsSync(path)` | `false` | `Path::new(path).exists()` |
| `isFile` | `Files.isRegularFile` | `fs.statSync.isFile()` | `false` | `metadata().is_file()` |
| `isDir` | `Files.isDirectory` | `fs.statSync.isDirectory()` | `false` | `metadata().is_dir()` |
| `mkdir` | `Files.createDirectory` | `fs.mkdirSync` | `FsNotSupported` | `fs::create_dir` |
| `mkdirs` | `Files.createDirectories` | `fs.mkdirSync(…, {recursive})` | `FsNotSupported` | `fs::create_dir_all` |
| `listDir` | `Files.list(Path.of(p)).map(_.getFileName)` | `fs.readdirSync(path)` | `FsNotSupported` | `fs::read_dir` |
| `deleteFile` | `Files.deleteIfExists` | `fs.rmSync(p, {force})` | `FsNotSupported` | `fs::remove_file` |
| `copyFile` | `Files.copy` | `fs.copyFileSync` | `FsNotSupported` | `fs::copy` |
| `moveFile` | `Files.move` | `fs.renameSync` | `FsNotSupported` | `fs::rename` |
| `readBytes` | `Files.readAllBytes` → `toList` | `Buffer` → list | `FsNotSupported` | `fs::read` |
| `writeBytes` | `Files.write(…, bytes.toArray)` | `Buffer.from` | `FsNotSupported` | `fs::write` |

### 2.1 Failure contract — what happens when the path is missing or the wrong type

Reported by **nadia** as `std-fs-failure-contract` (2026-08-04): the table above maps each name to a
backend primitive, and the primitives do not agree, so the contract a caller programs against was
"whatever the host platform does". Their `listDir` on a deleted directory raised and took a run
down; the convention "guard with `exists`/`isDir` first" had held at 12 of 13 call sites, and the
miss was in a DIAGNOSTIC — code that runs only after something else has already failed, one of the
failures being that the workspace is gone.

**This table is MEASURED, 2026-08-07, one probe per cell, not read off the implementations.** The
probe wraps each call in `try`/`catch` and prints the value or the exception; `mkdir` and `deleteFile`
are included because their documented primitives (`Files.createDirectory`, which raises on an
existing directory) predict something the lanes do not do.

| call | int (reference) | js | jvm | v2 / native |
|---|---|---|---|---|
| `readFile` missing | raise `NoSuchFileException` | raise `ENOENT` | raise `NoSuchFileException` | raise `RuntimeException(path)` |
| `readFile` on a DIR | raise `IOException(Is a directory)` | raise `EISDIR` | raise `IOException(Is a directory)` | raise `RuntimeException(Is a directory)` |
| `listDir` missing | raise `NoSuchFileException` | raise `ENOENT` | **`List()` — silent** | raise `RuntimeException(path)` |
| `listDir` on a FILE | raise `NotDirectoryException` | raise `ENOTDIR` | **`List()` — silent** | raise `RuntimeException(path)` |
| `readBytes` missing | raise `NoSuchFileException` | raise `ENOENT` | raise `NoSuchFileException` | raise `RuntimeException(path)` |
| `copyFile` / `moveFile` missing | raise `NoSuchFileException` | raise `ENOENT` | raise `NoSuchFileException` | raise `RuntimeException(path)` |
| `exists` / `isFile` / `isDir` missing | `false` | `false` | `false` | `false` |
| `deleteFile` missing | no-op, no raise | no-op, no raise | no-op, no raise | no-op, no raise |
| `mkdir` on an EXISTING dir | no-op, no raise | no-op, no raise | no-op, no raise | no-op, no raise |

**Read the `listDir` rows first.** On **jvm** a missing directory and a file-where-a-directory-was-
expected both answer `List()`, silently, exit 0 — while every other lane raises. That is precisely
the behaviour the reporter argued AGAINST in the same message: *"a `listDir` that answers `[]` for a
missing directory hides a typo, and the caller can no longer tell empty from not-there."* The
library already does it, on one lane, undocumented. Anyone porting a program from `int` to `jvm`
loses an error they were relying on.

**`v2 / native` erases the exception TYPE.** Everything arrives as `RuntimeException` and the
message is bare — `listDir` on a file yields the path, exactly as a missing path does — so on that
lane a caller cannot distinguish *missing* from *wrong type* even with a `catch`. The reporter noted
that permission-denied and missing are indistinguishable through the current API; on native it is
worse than they knew.

**`deleteFile` and `mkdir` are total, and the mapping above is misleading about it.** The JVM column
names `Files.createDirectory`, which raises when the directory exists; the lane does not raise. Both
of these are already-total operations and callers may rely on that.

**Rust: NOT MEASURED, and that is a fact about today rather than a gap in the answer.** Two things
blocked it, both worth knowing:
1. the Rust backend rejects `try`/`catch` outright — `def show contains an unsupported expression:
   Term.Try` — so the *question* "does it raise" has no answer expressible in a program on that
   target; there is nothing to catch with;
2. `run-rust` produced no binary for a hello-world on this toolchain (`expected binary not found`),
   so the lane could not be exercised at all. Filed separately — it is not a `std.fs` matter.

**Permission denial is also unmeasured** and is deliberately left blank rather than guessed: it
needs a mode-000 fixture, and a probe that runs as root silently measures nothing. The three
situations the report asked about are missing path, wrong type, and permission denial; the first two
are above.

#### What a caller should do today

`exists` / `isFile` / `isDir` are the only total predicates, and `exists` is TRUE FOR A DIRECTORY —
guarding a `readFile` with `exists` alone is the second bug the reporter found in their own code, and
it fails as `Is a directory`. Guard with `isFile` before `readFile`, `isDir` before `listDir`.

That does not close the report. Total variants (`listDirOpt` / `readFileOpt`) are its second ask, and
they remain open: `std.json` navigation is already explicitly total and `resolveWithin` returns an
`Option`, so `fs` is the outlier. The reporter is explicit that they would NOT make `fs` total by
default — a caller must be able to tell "empty" from "not there", which is the very distinction the
jvm lane destroys — and wants the choice visible at the call site. That is an API decision, not a
documentation one.

---

## 3. `std.os` — OS environment

**Capability gate:** `Feature.OsEnv`

### 3.1 API

```scalascript
// ── Environment variables ─────────────────────────────────────────────────────
extern def env(key: String): Option[String]
extern def envOrElse(key: String, default: String): String

// ── CLI arguments ─────────────────────────────────────────────────────────────
extern def args: List[String]   // excludes interpreter / script name

// ── Process management ────────────────────────────────────────────────────────
extern def exit(code: Int): Nothing

// ── Working directory & paths ─────────────────────────────────────────────────
extern def cwd: String
extern def sep: String          // "/" on Unix, "\" on Windows
extern def pathJoin(parts: String*): String
extern def pathDirname(path: String): String
extern def pathBasename(path: String): String
extern def pathExtname(path: String): String
extern def pathResolve(path: String): String   // absolute
extern def pathIsAbsolute(path: String): Boolean
extern def tempDir: String
extern def tempFile(prefix: String, suffix: String): String

// ── System info ───────────────────────────────────────────────────────────────
sealed trait Platform
case object Jvm     extends Platform
case object NodeJs  extends Platform
case object Browser extends Platform
case object Native  extends Platform   // Rust / WASM / other native

extern def platform: Platform
extern def homedir: String
extern def hostname: String
```

### 3.2 Backend implementations

| Op | JVM | JS/Node | Browser | Rust |
|----|-----|---------|---------|------|
| `env(key)` | `Option(System.getenv(key))` | `process.env[key]` | `None` | `env::var(key).ok()` |
| `envOrElse(k,d)` | `System.getenv(k) ?? d` | `process.env[k] ?? d` | `d` | `env::var(k).unwrap_or(d)` |
| `args` | JVM args | `process.argv.slice(2)` | `[]` | `env::args().skip(1)` |
| `exit(code)` | `System.exit(code)` | `process.exit(code)` | throw | `process::exit(code)` |
| `cwd` | `System.getProperty("user.dir")` | `process.cwd()` | `"/"` | `env::current_dir()` |
| `sep` | `File.separator` | `path.sep` | `"/"` | compile-time `MAIN_SEPARATOR` |
| `pathJoin` | `Paths.get(h, t*)` | `path.join(…)` | `"/".join(…)` | `PathBuf::from` |
| `pathDirname` | `Path.of(p).parent` | `path.dirname` | `"."` | `Path::parent` |
| `pathBasename` | `Path.of(p).fileName` | `path.basename` | `p` | `Path::file_name` |
| `pathExtname` | `Path.of(p).extension` | `path.extname` | `""` | `Path::extension` |
| `pathResolve` | `Path.of(p).toAbsolutePath` | `path.resolve` | `p` | `fs::canonicalize` |
| `pathIsAbsolute` | `Path.of(p).isAbsolute` | `path.isAbsolute` | `p.startsWith("/")` | `Path::is_absolute` |
| `tempDir` | `System.getProperty("java.io.tmpdir")` | `os.tmpdir()` | `"/"` | `env::temp_dir()` |
| `tempFile` | `Files.createTempFile` | `fs.mkdtempSync` | `FsNotSupported` | `tempfile` crate |
| `platform` | `Platform.Jvm` | `Platform.NodeJs` | `Platform.Browser` | `Platform.Native` |
| `homedir` | `System.getProperty("user.home")` | `os.homedir()` | `"/"` | `dirs::home_dir()` |
| `hostname` | `InetAddress.getLocalHost.getHostName` | `os.hostname()` | `location.hostname` | `hostname::get()` |

---

## 4. `std.process` — Process management

**Capability gate:** `Feature.ProcessSpawn`

Browser: all ops throw `ProcessError.NotSupported`.

### 4.1 Types

```scalascript
case class ProcessOptions(
  cwd:     Option[String]         = None,
  env:     Map[String, String]    = Map.empty,
  timeout: Option[Int]            = None   // milliseconds
)

case class ProcessResult(
  stdout:   String,
  stderr:   String,
  exitCode: Int
)

sealed trait ProcessError extends RuntimeException
case class ProcessNotFound(cmd: String)        extends ProcessError
case class ProcessTimeout(cmd: String, ms: Int) extends ProcessError
case class ProcessNotSupported(target: String) extends ProcessError
case class ProcessIoError(cmd: String, msg: String) extends ProcessError

// Handle to a running process (from spawn())
trait Process:
  def write(input: String): Unit
  def readLine(): Option[String]
  def readAll(): String
  def exitCode(): Int
  def kill(): Unit
  def waitFor(): Int
```

### 4.2 API

```scalascript
extern def exec(cmd: String, args: List[String], opts: ProcessOptions): ProcessResult
extern def spawn(cmd: String, args: List[String], opts: ProcessOptions): Process
```

### 4.3 Backend implementations

| Op | JVM | JS/Node | Rust |
|----|-----|---------|------|
| `exec` | `ProcessBuilder` + `waitFor` | `child_process.spawnSync` | `std::process::Command` |
| `spawn` | `ProcessBuilder` → streams | `child_process.spawn` | `std::process::Command` |

---

## 5. Implementation plan

### Phase 1 — spec (this file)
`spec: std-fs-os` — write and commit this spec.

### Phase 2 — JVM backend (`fs-plugin` + `os-plugin`)
`feat(fs-plugin): JVM backend`

Create `runtime/std/fs-plugin/` and `runtime/std/os-plugin/`:
- `FsPlugin.scala` + `FsIntrinsics.scala` implementing all `std.fs` ops
- `OsPlugin.scala` + `OsIntrinsics.scala` implementing `std.os` + `std.process`
- Register in `build.sbt`, add `% Test` to `backendInterpreter`
- Conformance tests: `tests/conformance/fs-*.ssc`, `os-*.ssc`, `process-*.ssc`

### Phase 3 — JS/Node backend
`feat(fs-plugin): JS/Node backend`

Node.js preamble additions:
- `std.fs` → `node:fs` sync ops
- `std.os` → `node:os` + `node:path`
- `std.process` → `node:child_process.spawnSync`
- Browser stubs: `FsNotSupported`, `ProcessNotSupported`, `env({})`, `args([])`

### Phase 4 — Rust backend
`feat(fs-plugin): Rust backend`

RustGen lowering:
- `std.fs` → `std::fs` + error mapping
- `std.os` → `std::env` + `std::path`
- `std.process` → `std::process::Command`

### Phase 5 — stdlib `.ssc` files + examples
`feat(std): fs/os/process stdlib modules`

- Expand `runtime/std/fs.ssc` with all new extern sigs
- Add `runtime/std/os.ssc`
- Add `runtime/std/process.ssc`
- Add examples: `examples/fs-roundtrip.ssc`, `examples/os-env.ssc`
- Update `README.md` capabilities table

### Phase 6 — audit & boundary doc
`docs(std-fs-os): boundary rule in AGENTS.md`

- Audit all `.ssc` for `java.*` / `node:fs` / `process.env` usage
- Migrate violations to `std.fs` / `std.os`
- Note `.sc` tooling exemption in `specs/std-fs-os.md` §Scope

---

## 6. Scope notes

- `.sc` Scala-CLI host scripts (`bench/run.sc`, `tests/e2e/*.sc`) may use
  JVM APIs directly — they are tooling, not `.ssc` user code.
- `std.fs` is synchronous (blocking). Async fs is a future milestone.
- `std.process` is synchronous for `exec`; `spawn` returns a handle but
  I/O is still blocking on JVM/Rust. Async I/O is a future milestone.
- `readBytes` / `writeBytes` use `List[Int]` (byte values 0–255) as the
  cross-backend type for raw bytes until a `Bytes` primitive is added.
