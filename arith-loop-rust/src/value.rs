//! ScalaScript runtime value enum (rust target).
//! Emitted verbatim by RustGen; do not edit by hand.

#[allow(dead_code)]
#[derive(Debug, Clone, PartialEq)]
pub enum Value {
    Unit,
    Bool(bool),
    Int(i64),
    Double(f64),
    Str(String),
    Tuple(Vec<Value>),
    List(Vec<Value>),
}

impl Value {
    pub fn show(&self) -> String {
        match self {
            Value::Unit      => "()".to_string(),
            Value::Bool(b)   => b.to_string(),
            Value::Int(n)    => n.to_string(),
            Value::Double(f) => format_double(*f),
            Value::Str(s)    => s.clone(),
            Value::Tuple(xs) => render_seq("(", ")", xs),
            Value::List(xs)  => render_seq("List(", ")", xs),
        }
    }
}

fn format_double(f: f64) -> String {
    if f.fract() == 0.0 && f.is_finite() {
        format!("{:.1}", f)
    } else {
        f.to_string()
    }
}

fn render_seq(open: &str, close: &str, xs: &[Value]) -> String {
    let parts: Vec<String> = xs.iter().map(|v| v.show()).collect();
    format!("{}{}{}", open, parts.join(", "), close)
}
