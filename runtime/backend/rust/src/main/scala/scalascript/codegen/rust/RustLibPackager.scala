package scalascript.codegen.rust

/** Per-host library packaging for the **Rust** host (Task B, `specs/polyglot-libraries.md` §4) — the
 *  counterpart of `JsLibPackager` / `JvmLibPackager`. Packages the pure **optics** feature as a
 *  standalone, buildable Rust crate (`cargo build`) with no ScalaScript dependency.
 *
 *  Like the JVM library, this is a **native, dependency-free Rust optics implementation** over a
 *  dynamic JSON-like value (`Value::Obj` / `Arr` / `Opt` / `Str` / `Int` / `Bool` / `Null`, with
 *  `"_type"`-tagged sum variants), faithful to the four optic shapes (Lens / Optional / Traversal /
 *  Prism) of the JS `@scalascript/optics` package. Idiomatic typed/lens-derive optics are a separate,
 *  larger effort. */
object RustLibPackager:

  /** The self-contained Rust optics source — no ScalaScript / cargo dependencies. */
  val opticsLibRs: String =
    """//! Composable optics (Lens / Optional / Traversal / Prism) over dynamic JSON-like values:
      |//! `Value::Obj` objects, `Value::Arr` arrays, `Value::Opt` options, and `"_type"`-tagged sum
      |//! variants. Generated from the ScalaScript optics runtime; no ScalaScript dependency — the
      |//! faithful dynamic port of the JS `@scalascript/optics` package / the JVM `ssc-optics` library.
      |
      |use std::collections::BTreeMap;
      |
      |/// A dynamic JSON-like value the optics operate over.
      |#[derive(Clone, Debug, PartialEq)]
      |pub enum Value {
      |    Obj(BTreeMap<String, Value>),
      |    Arr(Vec<Value>),
      |    Opt(Option<Box<Value>>),
      |    Str(String),
      |    Int(i64),
      |    Bool(bool),
      |    Null,
      |}
      |
      |/// A path step shared by Optional / Traversal.
      |#[derive(Clone, Debug, PartialEq)]
      |pub enum Step {
      |    Field(String),
      |    Index(usize),
      |    At(String),
      |    Some_,
      |    Each,
      |}
      |
      |pub fn field(name: &str) -> Step { Step::Field(name.to_string()) }
      |pub fn index(i: usize) -> Step { Step::Index(i) }
      |pub fn at(key: &str) -> Step { Step::At(key.to_string()) }
      |pub fn some() -> Step { Step::Some_ }
      |pub fn each() -> Step { Step::Each }
      |
      |// ── Lens ───────────────────────────────────────────────────────────────────
      |#[derive(Clone, Debug)]
      |pub struct Lens { pub path: Vec<String> }
      |
      |pub fn make_lens(path: Vec<String>) -> Lens { Lens { path } }
      |
      |impl Lens {
      |    pub fn get(&self, s: &Value) -> Value {
      |        let mut cur = s.clone();
      |        for k in &self.path {
      |            cur = match cur {
      |                Value::Obj(m) => m.get(k).cloned().unwrap_or(Value::Null),
      |                _ => Value::Null,
      |            };
      |        }
      |        cur
      |    }
      |    pub fn set(&self, s: &Value, v: Value) -> Value { set_path(&self.path, s, v) }
      |    pub fn modify<F: Fn(Value) -> Value>(&self, s: &Value, f: F) -> Value {
      |        let a = self.get(s);
      |        self.set(s, f(a))
      |    }
      |    pub fn and_then(&self, other: &Lens) -> Lens {
      |        let mut p = self.path.clone();
      |        p.extend(other.path.iter().cloned());
      |        Lens { path: p }
      |    }
      |}
      |
      |fn set_path(path: &[String], s: &Value, v: Value) -> Value {
      |    match path.split_first() {
      |        None => v,
      |        Some((h, t)) => match s {
      |            Value::Obj(m) => {
      |                let child = m.get(h).cloned().unwrap_or(Value::Null);
      |                let mut m2 = m.clone();
      |                m2.insert(h.clone(), set_path(t, &child, v));
      |                Value::Obj(m2)
      |            }
      |            _ => s.clone(),
      |        },
      |    }
      |}
      |
      |// ── Optional ───────────────────────────────────────────────────────────────
      |#[derive(Clone, Debug)]
      |pub struct Optional { pub steps: Vec<Step> }
      |
      |pub fn make_optional(steps: Vec<Step>) -> Optional { Optional { steps } }
      |
      |impl Optional {
      |    pub fn get_option(&self, s: &Value) -> Option<Value> { get_opt(&self.steps, s) }
      |    pub fn set(&self, s: &Value, v: Value) -> Value { set_opt(&self.steps, s, v) }
      |    pub fn modify<F: Fn(Value) -> Value>(&self, s: &Value, f: F) -> Value {
      |        match self.get_option(s) {
      |            Some(a) => self.set(s, f(a)),
      |            None => s.clone(),
      |        }
      |    }
      |    pub fn and_then(&self, other: &Optional) -> Optional {
      |        let mut st = self.steps.clone();
      |        st.extend(other.steps.iter().cloned());
      |        Optional { steps: st }
      |    }
      |}
      |
      |fn get_opt(steps: &[Step], s: &Value) -> Option<Value> {
      |    match steps.split_first() {
      |        None => Some(s.clone()),
      |        Some((st, rest)) => match st {
      |            Step::Field(n) | Step::At(n) => match s {
      |                Value::Obj(m) => m.get(n).and_then(|c| get_opt(rest, c)),
      |                _ => None,
      |            },
      |            Step::Index(i) => match s {
      |                Value::Arr(l) if *i < l.len() => get_opt(rest, &l[*i]),
      |                _ => None,
      |            },
      |            Step::Some_ => match s {
      |                Value::Opt(Some(b)) => get_opt(rest, b),
      |                _ => None,
      |            },
      |            Step::Each => None,
      |        },
      |    }
      |}
      |
      |fn set_opt(steps: &[Step], s: &Value, v: Value) -> Value {
      |    match steps.split_first() {
      |        None => v,
      |        Some((st, rest)) => match st {
      |            Step::Field(n) | Step::At(n) => match s {
      |                Value::Obj(m) if m.contains_key(n) => {
      |                    let mut m2 = m.clone();
      |                    m2.insert(n.clone(), set_opt(rest, &m[n], v));
      |                    Value::Obj(m2)
      |                }
      |                _ => s.clone(),
      |            },
      |            Step::Index(i) => match s {
      |                Value::Arr(l) if *i < l.len() => {
      |                    let mut l2 = l.clone();
      |                    l2[*i] = set_opt(rest, &l[*i], v);
      |                    Value::Arr(l2)
      |                }
      |                _ => s.clone(),
      |            },
      |            Step::Some_ => match s {
      |                Value::Opt(Some(b)) => Value::Opt(Some(Box::new(set_opt(rest, b, v)))),
      |                _ => s.clone(),
      |            },
      |            Step::Each => s.clone(),
      |        },
      |    }
      |}
      |
      |// ── Traversal ──────────────────────────────────────────────────────────────
      |#[derive(Clone, Debug)]
      |pub struct Traversal { pub steps: Vec<Step> }
      |
      |pub fn make_traversal(steps: Vec<Step>) -> Traversal { Traversal { steps } }
      |
      |impl Traversal {
      |    pub fn get_all(&self, s: &Value) -> Vec<Value> { get_all(&self.steps, s) }
      |    pub fn modify<F: Fn(Value) -> Value + Copy>(&self, s: &Value, f: F) -> Value {
      |        mod_all(&self.steps, s, f)
      |    }
      |    pub fn set(&self, s: &Value, v: Value) -> Value { self.modify(s, |_| v.clone()) }
      |    pub fn and_then(&self, other: &Traversal) -> Traversal {
      |        let mut st = self.steps.clone();
      |        st.extend(other.steps.iter().cloned());
      |        Traversal { steps: st }
      |    }
      |}
      |
      |fn get_all(steps: &[Step], s: &Value) -> Vec<Value> {
      |    match steps.split_first() {
      |        None => vec![s.clone()],
      |        Some((st, rest)) => match st {
      |            Step::Field(n) | Step::At(n) => match s {
      |                Value::Obj(m) => m.get(n).map(|c| get_all(rest, c)).unwrap_or_default(),
      |                _ => Vec::new(),
      |            },
      |            Step::Index(i) => match s {
      |                Value::Arr(l) if *i < l.len() => get_all(rest, &l[*i]),
      |                _ => Vec::new(),
      |            },
      |            Step::Some_ => match s {
      |                Value::Opt(Some(b)) => get_all(rest, b),
      |                _ => Vec::new(),
      |            },
      |            Step::Each => match s {
      |                Value::Arr(l) => l.iter().flat_map(|item| get_all(rest, item)).collect(),
      |                _ => Vec::new(),
      |            },
      |        },
      |    }
      |}
      |
      |fn mod_all<F: Fn(Value) -> Value + Copy>(steps: &[Step], s: &Value, f: F) -> Value {
      |    match steps.split_first() {
      |        None => f(s.clone()),
      |        Some((st, rest)) => match st {
      |            Step::Field(n) | Step::At(n) => match s {
      |                Value::Obj(m) if m.contains_key(n) => {
      |                    let mut m2 = m.clone();
      |                    m2.insert(n.clone(), mod_all(rest, &m[n], f));
      |                    Value::Obj(m2)
      |                }
      |                _ => s.clone(),
      |            },
      |            Step::Index(i) => match s {
      |                Value::Arr(l) if *i < l.len() => {
      |                    let mut l2 = l.clone();
      |                    l2[*i] = mod_all(rest, &l[*i], f);
      |                    Value::Arr(l2)
      |                }
      |                _ => s.clone(),
      |            },
      |            Step::Some_ => match s {
      |                Value::Opt(Some(b)) => Value::Opt(Some(Box::new(mod_all(rest, b, f)))),
      |                _ => s.clone(),
      |            },
      |            Step::Each => match s {
      |                Value::Arr(l) => Value::Arr(l.iter().map(|item| mod_all(rest, item, f)).collect()),
      |                _ => s.clone(),
      |            },
      |        },
      |    }
      |}
      |
      |// ── Prism ──────────────────────────────────────────────────────────────────
      |#[derive(Clone, Debug)]
      |pub struct Prism { pub variant: String }
      |
      |pub fn make_prism(variant: &str) -> Prism { Prism { variant: variant.to_string() } }
      |
      |impl Prism {
      |    fn matches(&self, s: &Value) -> bool {
      |        match s {
      |            Value::Obj(m) => matches!(m.get("_type"), Some(Value::Str(t)) if *t == self.variant),
      |            _ => false,
      |        }
      |    }
      |    pub fn get_option(&self, s: &Value) -> Option<Value> {
      |        if self.matches(s) { Some(s.clone()) } else { None }
      |    }
      |    pub fn reverse_get(&self, v: Value) -> Value { v }
      |    pub fn set(&self, s: &Value, v: Value) -> Value {
      |        if self.matches(s) { v } else { s.clone() }
      |    }
      |    pub fn modify<F: Fn(Value) -> Value>(&self, s: &Value, f: F) -> Value {
      |        if self.matches(s) { f(s.clone()) } else { s.clone() }
      |    }
      |}
      |""".stripMargin

  def opticsCargoToml(version: String): String =
    s"""[package]
       |name = "ssc-optics"
       |version = "$version"
       |edition = "2021"
       |description = "Composable optics (Lens/Optional/Traversal/Prism) over dynamic JSON-like values — the Rust port of @scalascript/optics."
       |license = "Apache-2.0"
       |
       |[lib]
       |name = "ssc_optics"
       |path = "src/lib.rs"
       |""".stripMargin

  val opticsReadme: String =
    """# ssc-optics (Rust)
      |
      |Composable **optics** — Lens / Optional / Traversal / Prism — over dynamic JSON-like values
      |(`Value::Obj` / `Arr` / `Opt` / `Str` / `Int` / `Bool` / `Null`, with `"_type"`-tagged sum
      |variants). The Rust port of the `@scalascript/optics` package; no ScalaScript dependency.
      |
      |```rust
      |use ssc_optics::*;
      |use std::collections::BTreeMap;
      |
      |let mut inner = BTreeMap::new();
      |inner.insert("b".to_string(), Value::Int(5));
      |let mut outer = BTreeMap::new();
      |outer.insert("a".to_string(), Value::Obj(inner));
      |let s = Value::Obj(outer);
      |
      |let l = make_lens(vec!["a".to_string(), "b".to_string()]);
      |l.get(&s);              // Value::Int(5)
      |l.set(&s, Value::Int(9));
      |```
      |
      |Build: `cargo build`.
      |""".stripMargin

  /** The complete buildable Rust crate as `relative-path -> content`. */
  def opticsRustPackage(version: String): Map[String, String] = Map(
    "Cargo.toml" -> opticsCargoToml(version),
    "src/lib.rs" -> opticsLibRs,
    "README.md"  -> opticsReadme,
  )
