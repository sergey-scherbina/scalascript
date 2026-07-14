// A Reverse-Polish-Notation calculator, written in language L, compiled by C_min itself.
// evalRpn(s): tokenize a space-separated RPN arithmetic string, then evaluate it with a stack.
// This is an INDEPENDENT non-trivial program (a string tokenizer + a stack machine) — proof that C_min
// is a general-purpose compiler for L, not merely self-referential.
def isDig(c) = c >= 48 && c <= 57
def fst(p) = p match { case (a, b) => a }
def snd(p) = p match { case (a, b) => b }
// scanNum: read a run of digits from index i, returning (value, endIndex)
def scanNum(s, i, n, acc) = if i >= n then (acc, i) else (if isDig(s.charAt(i)) then scanNum(s, i + 1, n, acc * 10 + (s.charAt(i) - 48)) else (acc, i))
def lexNum(s, i, n) = scanNum(s, i, n, 0) match { case (v, j) => (0, v) :: lex(s, j, n) }
// lex: string -> list of tokens; (0, value) = number, (1, charCode) = operator
def lex(s, i, n) = if i >= n then Nil else lexc(s, i, n, s.charAt(i))
def lexc(s, i, n, c) = if c == 32 then lex(s, i + 1, n) else (if isDig(c) then lexNum(s, i, n) else (1, c) :: lex(s, i + 1, n))
// apply an operator (by ASCII code) to two operands: 43='+', 45='-', else '*'
def applyOp(op, a, b) = if op == 43 then a + b else (if op == 45 then a - b else a * b)
// pop the top two values, apply op, push the result
def applyStack(op, stack) = stack match { case b :: t => t match { case a :: rest => applyOp(op, a, b) :: rest case _ => stack } case _ => stack }
def step(tok, stack) = if fst(tok) == 0 then snd(tok) :: stack else applyStack(snd(tok), stack)
// fold the token list over a stack, returning the final top
def run(toks, stack) = toks match { case Nil => stack match { case top :: rest => top case _ => 0 } case tok :: rest => run(rest, step(tok, stack)) }
def evalRpn(s) = run(lex(s, 0, s.length), Nil)
// 2 3 4 * +  =  2 + (3 * 4)  =  14
def main() = evalRpn("2 3 4 * +")
