// A tree-walking evaluator for an arithmetic AST, written in language L, compiled by C_min.
// The AST is an algebraic data type built from arbitrary constructors — Num(v) | Add(l,r) | Sub(l,r) |
// Mul(l,r) — matched with `case Name(...)` patterns. This exercises C_min's arbitrary-ctor construction
// and pattern matching (P6.19), the last big object-language gap; before it, C_min matched only Cons/Nil/
// tuples, so ADT interpreters like this one could not be compiled.
def eval(e) = e match {
  case Num(v) => v
  case Add(l, r) => eval(l) + eval(r)
  case Sub(l, r) => eval(l) - eval(r)
  case Mul(l, r) => eval(l) * eval(r)
  case _ => 0
}
// (3 * 4) + (10 - 8) = 12 + 2 = 14
def main() = eval(Add(Mul(Num(3), Num(4)), Sub(Num(10), Num(8))))
