//! ScalaScript runtime helpers (rust target).
//! Emitted verbatim by RustGen; do not edit by hand.

use crate::value::Value;
use std::fmt::Display;

#[allow(dead_code)]
pub fn _show(v: &Value) -> String {
    v.show()
}

#[allow(dead_code)]
pub fn _print<T: Display>(s: T) {
    use std::io::Write;
    print!("{}", s);
    let _ = std::io::stdout().flush();
}

#[allow(dead_code)]
pub fn _println<T: Display>(s: T) {
    println!("{}", s);
}

// ── R.3.1 — time + filesystem intrinsics (no extra crate deps) ──

/// `nowMillis` — current Unix time in milliseconds, signed i64.
/// Mirrors the JVM target's `java.lang.System.currentTimeMillis`.
#[allow(dead_code)]
pub fn _now_millis() -> i64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// `readFile(path)` — read a UTF-8 file to a String.  Panics on
/// I/O error to match the interpreter's fail-fast contract.
/// Takes the path by reference so the caller keeps ownership.
#[allow(dead_code)]
pub fn _read_file(path: &str) -> String {
    std::fs::read_to_string(path)
        .unwrap_or_else(|e| panic!("readFile({}): {}", path, e))
}

/// `writeFile(path, contents)` — overwrite a file's bytes with
/// the given UTF-8 string.  Takes both args by reference so the
/// caller keeps ownership of its variables.
#[allow(dead_code)]
pub fn _write_file(path: &str, contents: &str) {
    std::fs::write(path, contents)
        .unwrap_or_else(|e| panic!("writeFile({}): {}", path, e))
}

// ── R.3.4 — process & env intrinsics (no extra crate deps) ──

/// `args()` — command-line arguments after the binary name.
/// Returns an empty Vec when invoked with no args.
#[allow(dead_code)]
pub fn _args() -> Vec<String> {
    std::env::args().skip(1).collect()
}

/// `env(name)` — value of an environment variable, or empty string
/// when unset.  Returns an owned `String`; SS code can compare it
/// to `""` to detect unset.
#[allow(dead_code)]
pub fn _env(name: &str) -> String {
    std::env::var(name).unwrap_or_default()
}

/// `exit(code)` — terminate the process immediately with the
/// given exit code.  Wraps `i64` from the SS surface to `i32`
/// for `std::process::exit`.
#[allow(dead_code)]
pub fn _exit(code: i64) -> ! {
    std::process::exit(code as i32)
}
