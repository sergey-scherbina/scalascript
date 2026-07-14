// C_min: a self-compiling compiler for language L, written in L. compile(src, dq): String -> String.
// It lexes, Pratt-parses, and emits Core IR for L (def/if/match/val-blocks/strings/arith/lists/comments).
// Escape-free: the `"` char is the `dq` parameter, so this source never contains a "-inside-a-string.
def atEnd(i, n) = i >= n
def isLo(c) = c >= 97 && c <= 122
def isUp(c) = c >= 65 && c <= 90
def isDig(c) = c >= 48 && c <= 57
def isLet(c) = if isLo(c) then true else isUp(c)
def isAlnum(c) = if isLet(c) then true else isDig(c)
def scanId(s, i, n) = if atEnd(i, n) then i else (if isAlnum(s.charAt(i)) then scanId(s, i + 1, n) else i)
def scanStr(s, i, n) = if atEnd(i, n) then i else (if s.charAt(i) == 34 then i else scanStr(s, i + 1, n))
def scanNumV(s, i, n, acc) = if atEnd(i, n) then acc else (if isDig(s.charAt(i)) then scanNumV(s, i + 1, n, acc * 10 + (s.charAt(i) - 48)) else acc)
def scanNumE(s, i, n) = if atEnd(i, n) then i else (if isDig(s.charAt(i)) then scanNumE(s, i + 1, n) else i)
def kwCode(w) = if w == "def" then 1 else (if w == "if" then 2 else (if w == "then" then 3 else (if w == "else" then 4 else (if w == "match" then 5 else (if w == "case" then 6 else (if w == "val" then 7 else 0))))))
def lex(s, i, n) = if atEnd(i, n) then Nil else lexDispatch(s, i, n, s.charAt(i))
def lexDispatch(s, i, n, c) = if c == 32 then lex(s, i + 1, n) else (if c == 10 then lex(s, i + 1, n) else (if c == 9 then lex(s, i + 1, n) else (if c == 13 then lex(s, i + 1, n) else (if isLet(c) then lexWord(s, i, n, scanId(s, i, n)) else (if isDig(c) then lexNum(s, i, n) else (if c == 34 then lexStr(s, i, n, scanStr(s, i + 1, n)) else (if c == 61 then lexEq(s, i, n) else (if c == 43 then lexPlus(s, i, n) else (if c == 60 then lexLt(s, i, n) else (if c == 62 then lexGt(s, i, n) else (if c == 38 then lexAmp(s, i, n) else (if c == 124 then lexPipe(s, i, n) else (if c == 58 then lexColon(s, i, n) else (if c == 47 then lexSlash(s, i, n) else lexOp1(s, i, n, c)))))))))))))))
def lexColon(s, i, n) = if s.charAt(i + 1) == 58 then Cons((2, 46), lex(s, i + 2, n)) else lex(s, i + 1, n)
def scanLineEnd(s, i, n) = if atEnd(i, n) then i else (if s.charAt(i) == 10 then i else scanLineEnd(s, i + 1, n))
def lexSlash(s, i, n) = if s.charAt(i + 1) == 47 then lex(s, scanLineEnd(s, i + 2, n), n) else lex(s, i + 1, n)
def lexLt(s, i, n) = if s.charAt(i + 1) == 61 then Cons((2, 44), lex(s, i + 2, n)) else Cons((2, 26), lex(s, i + 1, n))
def lexGt(s, i, n) = if s.charAt(i + 1) == 61 then Cons((2, 43), lex(s, i + 2, n)) else Cons((2, 42), lex(s, i + 1, n))
def lexAmp(s, i, n) = if s.charAt(i + 1) == 38 then Cons((2, 40), lex(s, i + 2, n)) else lex(s, i + 1, n)
def lexPipe(s, i, n) = if s.charAt(i + 1) == 124 then Cons((2, 41), lex(s, i + 2, n)) else lex(s, i + 1, n)
def lexWord(s, i, n, j) = lexWord2(s, i, n, j, s.substring(i, j))
def lexWord2(s, i, n, j, w) = if 0 < kwCode(w) then Cons((2, kwCode(w)), lex(s, j, n)) else (if isUp(s.charAt(i)) then Cons((3, w), lex(s, j, n)) else Cons((1, w), lex(s, j, n)))
def lexNum(s, i, n) = Cons((0, scanNumV(s, i, n, 0)), lex(s, scanNumE(s, i, n), n))
def lexStr(s, i, n, j) = Cons((4, s.substring(i + 1, j)), lex(s, j + 1, n))
def lexEq(s, i, n) = if s.charAt(i + 1) == 62 then Cons((2, 30), lex(s, i + 2, n)) else (if s.charAt(i + 1) == 61 then Cons((2, 32), lex(s, i + 2, n)) else Cons((2, 20), lex(s, i + 1, n)))
def lexPlus(s, i, n) = if s.charAt(i + 1) == 43 then Cons((2, 33), lex(s, i + 2, n)) else Cons((2, 23), lex(s, i + 1, n))
def lexOp1(s, i, n, c) = if c == 40 then Cons((2, 21), lex(s, i + 1, n)) else (if c == 41 then Cons((2, 22), lex(s, i + 1, n)) else (if c == 45 then Cons((2, 24), lex(s, i + 1, n)) else (if c == 42 then Cons((2, 25), lex(s, i + 1, n)) else (if c == 44 then Cons((2, 27), lex(s, i + 1, n)) else (if c == 123 then Cons((2, 28), lex(s, i + 1, n)) else (if c == 125 then Cons((2, 29), lex(s, i + 1, n)) else (if c == 46 then Cons((2, 31), lex(s, i + 1, n)) else (if c == 95 then Cons((2, 45), lex(s, i + 1, n)) else lex(s, i + 1, n)))))))))
def hd(ts) = ts match { case h :: t => h case _ => (0, 0) }
def tl(ts) = ts match { case h :: t => t case _ => Nil }
def fst(p) = p match { case (a, b) => a }
def snd(p) = p match { case (a, b) => b }
def isEmpty(ts) = ts match { case Nil => true case _ => false }
def isTok(t, k, c) = if fst(t) == k then snd(t) == c else false
def isLP(ts) = isTok(hd(ts), 2, 21)
def isComma(ts) = isTok(hd(ts), 2, 27)
def isRP(ts) = isTok(hd(ts), 2, 22)
def isKind1(t) = fst(t) == 1
def dlen(env) = env match { case Nil => 0 case h :: t => dlen(t) + 1 }
def idxOf(env, name, i) = env match { case Nil => 0 case Cons(h, t) => if h == name then i else idxOf(t, name, i + 1) }
def arithBare(k) = k match { case 23 => "i.add" case 24 => "i.sub" case 25 => "i.mul" case 26 => "i.lt" case 42 => "i.gt" case 43 => "i.ge" case _ => "i.le" }
def emitBin(k, l, r) = if k == 46 then "(ctor Cons " ++ l ++ " " ++ r ++ ")" else (if k == 40 then "(if " ++ l ++ " " ++ r ++ " (lit false))" else (if k == 41 then "(if " ++ l ++ " (lit true) " ++ r ++ ")" else (if k == 32 then "(prim __eq__ " ++ l ++ " " ++ r ++ ")" else (if k == 33 then "(prim sconcat " ++ l ++ " " ++ r ++ ")" else "(prim " ++ arithBare(k) ++ " " ++ l ++ " " ++ r ++ ")"))))
def binPrec(c) = if c == 41 then 1 else (if c == 40 then 2 else (if c == 26 then 3 else (if c == 42 then 3 else (if c == 43 then 3 else (if c == 44 then 3 else (if c == 32 then 3 else (if c == 46 then 4 else (if c == 23 then 5 else (if c == 24 then 5 else (if c == 33 then 5 else (if c == 25 then 6 else 0)))))))))))
def prec(t) = if fst(t) == 2 then binPrec(snd(t)) else 0
def emitMethod(mn, recv, a0, a1) = if mn == "length" then "(prim slen " ++ recv ++ ")" else (if mn == "charAt" then "(prim scodeAt " ++ recv ++ " " ++ a0 ++ ")" else "(prim sslice " ++ recv ++ " " ++ a0 ++ " " ++ a1 ++ ")")
def parseAtom(ts, env, dq) = if isEmpty(ts) then ("(lit (int 0))", Nil) else parseAtom1(hd(ts), tl(ts), env, dq)
def parseAtom1(t, rest, env, dq) = if fst(t) == 0 then ("(lit (int " ++ snd(t) ++ "))", rest) else (if fst(t) == 4 then ("(lit (str " ++ dq ++ snd(t) ++ dq ++ "))", rest) else (if fst(t) == 1 then parseVarOrCall(snd(t), rest, env, dq) else (if fst(t) == 3 then parseCtor(snd(t), rest, env, dq) else (if isTok(t, 2, 21) then parseParenOrTuple(rest, env, dq) else (if isTok(t, 2, 2) then parseIf(rest, env, dq) else (if isTok(t, 2, 28) then parseBlock(rest, env, dq) else ("(lit (int 0))", rest)))))))
def parseBlock(ts, env, dq) = if isTok(hd(ts), 2, 7) then parseBlockVal(ts, env, dq, snd(hd(tl(ts)))) else parseBlockEnd(ts, env, dq)
def parseBlockVal(ts, env, dq, nm) = parseExpr(tl(tl(tl(ts))), 0, env, dq) match { case (e, r2) => parseBlock(r2, Cons(nm, env), dq) match { case (rest, r3) => ("(let (" ++ e ++ ") " ++ rest ++ ")", r3) } }
def parseBlockEnd(ts, env, dq) = parseExpr(ts, 0, env, dq) match { case (e, r2) => (e, tl(r2)) }
def parseVarOrCall(id, rest, env, dq) = if id == "true" then ("(lit true)", rest) else (if id == "false" then ("(lit false)", rest) else (if isLP(rest) then parseVarOrCall2(id, tl(rest), env, dq) else ("(local " ++ idxOf(env, id, 0) ++ ")", rest)))
def parseVarOrCall2(id, ts, env, dq) = parseArgs(ts, env, "", dq) match { case (args, r2) => ("(app (global " ++ id ++ ")" ++ args ++ ")", r2) }
def parseCtor(cn, rest, env, dq) = if isLP(rest) then parseCtorArgs(cn, tl(rest), env, dq, "") else ("(ctor " ++ cn ++ ")", rest)
def parseCtorArgs(cn, ts, env, dq, acc) = if isRP(ts) then ("(ctor " ++ cn ++ acc ++ ")", tl(ts)) else parseExpr(ts, 0, env, dq) match { case (a, r2) => if isComma(r2) then parseCtorArgs(cn, tl(r2), env, dq, acc ++ " " ++ a) else parseCtorArgs(cn, r2, env, dq, acc ++ " " ++ a) }
def parseParenOrTuple(rest, env, dq) = parseExpr(rest, 0, env, dq) match { case (e, r2) => if isComma(r2) then parseTupleRest(e, tl(r2), env, dq) else (e, tl(r2)) }
def parseTupleRest(e, ts, env, dq) = parseExpr(ts, 0, env, dq) match { case (e2, r3) => ("(ctor Tuple2 " ++ e ++ " " ++ e2 ++ ")", tl(r3)) }
def parseIf(rest, env, dq) = parseExpr(rest, 0, env, dq) match { case (c, r2) => parseExpr(tl(r2), 0, env, dq) match { case (th, r3) => parseExpr(tl(r3), 0, env, dq) match { case (el, r4) => ("(if " ++ c ++ " " ++ th ++ " " ++ el ++ ")", r4) } } }
def parseArgs(ts, env, acc, dq) = if isRP(ts) then (acc, tl(ts)) else parseArgs2(ts, env, acc, dq)
def parseArgs2(ts, env, acc, dq) = parseExpr(ts, 0, env, dq) match { case (a, r2) => if isComma(r2) then parseArgs(tl(r2), env, acc ++ " " ++ a, dq) else parseArgs(r2, env, acc ++ " " ++ a, dq) }
def parseParams(ts, acc) = if isKind1(hd(ts)) then parseParams2(ts, acc) else (acc, tl(ts))
def parseParams2(ts, acc) = if isComma(tl(ts)) then parseParams(tl(tl(ts)), Cons(snd(hd(ts)), acc)) else parseParams(tl(ts), Cons(snd(hd(ts)), acc))
def parseArms(ts, env, dq) = if isTok(hd(ts), 2, 6) then parseArm(tl(ts), env, dq) else ("", ("", tl(ts)))
def isConsInfix(rest) = if fst(hd(rest)) == 1 then isTok(hd(tl(rest)), 2, 46) else false
def parseArm(rest, env, dq) = if isTok(hd(rest), 2, 45) then parseArmDefault(rest, env, dq) else (if isConsInfix(rest) then parseArmConsInfix(rest, env, dq) else (if isTok(hd(rest), 2, 21) then parseArmTup(rest, env, dq) else (if isLP(tl(rest)) then parseArmCtor(rest, env, dq) else parseArmNil(rest, env, dq))))
def envApp(vars, env) = vars match { case Nil => env case h :: t => h :: envApp(t, env) }
def parseCtorPatVars(ts, acc) = if isRP(ts) then (acc, tl(ts)) else (if isComma(ts) then parseCtorPatVars(tl(ts), acc) else parseCtorPatVars(tl(ts), snd(hd(ts)) :: acc))
def parseArmCtor(rest, env, dq) = { val cn = snd(hd(rest))  parseCtorPatVars(tl(tl(rest)), Nil) match { case (vars, afterP) => parseExpr(tl(afterP), 0, envApp(vars, env), dq) match { case (body, r2) => parseArms(r2, env, dq) match { case (more, dr) => ("(arm " ++ cn ++ " " ++ dlen(vars) ++ " " ++ body ++ ") " ++ more, dr) } } } }
def parseArmConsInfix(rest, env, dq) = { val hv = snd(hd(rest))  val tv = snd(hd(tl(tl(rest))))  parseExpr(tl(tl(tl(tl(rest)))), 0, tv :: hv :: env, dq) match { case (body, r2) => parseArms(r2, env, dq) match { case (more, dr) => ("(arm Cons 2 " ++ body ++ ") " ++ more, dr) } } }
def parseArmDefault(rest, env, dq) = parseExpr(tl(tl(rest)), 0, env, dq) match { case (body, r2) => ("", (" (default " ++ body ++ ")", tl(r2))) }
def parseArmTup(rest, env, dq) = { val av = snd(hd(tl(rest)))  val bv = snd(hd(tl(tl(tl(rest)))))  parseExpr(tl(tl(tl(tl(tl(tl(rest)))))), 0, bv :: av :: env, dq) match { case (body, r2) => parseArms(r2, env, dq) match { case (more, dr) => ("(arm Tuple2 2 " ++ body ++ ") " ++ more, dr) } } }
def parseArmNil(rest, env, dq) = parseExpr(tl(tl(rest)), 0, env, dq) match { case (body, r2) => parseArms(r2, env, dq) match { case (more, dr) => ("(arm Nil 0 " ++ body ++ ") " ++ more, dr) } }
def postfix(recv, ts, env, dq) = if isTok(hd(ts), 2, 5) then postfixMatch(recv, ts, env, dq) else (if isTok(hd(ts), 2, 31) then postfixDot(recv, ts, env, dq) else (recv, ts))
def isMixedFirst(ts) = if isTok(hd(tl(tl(tl(ts)))), 2, 21) then fst(hd(tl(tl(tl(tl(ts)))))) == 0 else false
def postfixMatch(recv, ts, env, dq) = if fst(hd(tl(tl(tl(ts))))) == 0 then parseIntMatch(recv, tl(tl(ts)), env, dq) else (if isMixedFirst(ts) then parseMixedMatch(recv, tl(tl(ts)), env, dq) else parseArms(tl(tl(ts)), env, dq) match { case (arms, dr) => dr match { case (defStr, r2) => postfix("(match " ++ recv ++ " (" ++ arms ++ ")" ++ defStr ++ ")", r2, env, dq) } })
def parseMixedMatch(recv, ts, env, dq) = parseMixedArms(ts, env, dq) match { case (chain, r2) => postfix("(match " ++ recv ++ " ((arm Tuple2 2 " ++ chain ++ ")))", r2, env, dq) }
def parseMixedArms(ts, env, dq) = if isTok(hd(ts), 2, 6) then (if isTok(hd(tl(ts)), 2, 45) then parseExpr(tl(tl(tl(ts))), 0, "_" :: "_" :: env, dq) match { case (d, r2) => (d, tl(r2)) } else parseMixedArm(ts, env, dq)) else ("(lit (int 0))", tl(ts))
def parseMixedArm(ts, env, dq) = { val litN = snd(hd(tl(tl(ts))))  val vn = snd(hd(tl(tl(tl(tl(ts))))))  parseExpr(tl(tl(tl(tl(tl(tl(tl(ts))))))), 0, vn :: "_" :: env, dq) match { case (body, r2) => parseMixedArms(r2, env, dq) match { case (rest, r3) => ("(if (prim __eq__ (local 1) (lit (int " ++ litN ++ "))) " ++ body ++ " " ++ rest ++ ")", r3) } } }
def parseIntMatch(recv, ts, env, dq) = parseIntArms(recv, ts, env, dq) match { case (chain, r2) => postfix(chain, r2, env, dq) }
def parseIntArms(recv, ts, env, dq) = if isTok(hd(ts), 2, 6) then (if isTok(hd(tl(ts)), 2, 45) then parseExpr(tl(tl(tl(ts))), 0, env, dq) match { case (body, r2) => (body, tl(r2)) } else parseIntArm(recv, ts, env, dq)) else ("(lit (int 0))", tl(ts))
def parseIntArm(recv, ts, env, dq) = { val litN = snd(hd(tl(ts)))  parseExpr(tl(tl(tl(ts))), 0, env, dq) match { case (body, r2) => parseIntArms(recv, r2, env, dq) match { case (rest, r3) => ("(if (prim __eq__ " ++ recv ++ " (lit (int " ++ litN ++ "))) " ++ body ++ " " ++ rest ++ ")", r3) } } }
def postfixDot(recv, ts, env, dq) = { val mn = snd(hd(tl(ts)))  if isLP(tl(tl(ts))) then postfixDotArgs(recv, tl(tl(tl(ts))), env, dq, mn) else postfix(emitMethod(mn, recv, "", ""), tl(tl(ts)), env, dq) }
def postfixDotArgs(recv, ts, env, dq, mn) = parseExpr(ts, 0, env, dq) match { case (a0, r2) => if isComma(r2) then postfixDotArgs2(recv, tl(r2), env, dq, mn, a0) else postfix(emitMethod(mn, recv, a0, ""), tl(r2), env, dq) }
def postfixDotArgs2(recv, ts, env, dq, mn, a0) = parseExpr(ts, 0, env, dq) match { case (a1, r3) => postfix(emitMethod(mn, recv, a0, a1), tl(r3), env, dq) }
def parsePostfix(ts, env, dq) = parseAtom(ts, env, dq) match { case (a, r) => postfix(a, r, env, dq) }
def notLess(p, minPrec) = minPrec < p + 1
def climbable(ts, minPrec) = if isEmpty(ts) then false else (if 0 < prec(hd(ts)) then notLess(prec(hd(ts)), minPrec) else false)
def climb(left, ts, minPrec, env, dq) = if climbable(ts, minPrec) then climbStep(left, ts, minPrec, env, dq) else (left, ts)
def rightMin(k, p) = if k == 46 then p else p + 1
def climbStep(left, ts, minPrec, env, dq) = { val p = prec(hd(ts))  val k = snd(hd(ts))  parseExpr(tl(ts), rightMin(k, p), env, dq) match { case (right, r2) => climb(emitBin(k, left, right), r2, minPrec, env, dq) } }
def parseExpr(ts, minPrec, env, dq) = parsePostfix(ts, env, dq) match { case (l, r) => climb(l, r, minPrec, env, dq) }
def parseDefs(ts, dq) = if isEmpty(ts) then "" else parseOneDef(tl(ts), dq)
def parseOneDef(r1, dq) = { val nm = snd(hd(r1))  parseParams(tl(tl(r1)), Nil) match { case (env, afterP) => parseExpr(tl(afterP), 0, env, dq) match { case (body, r6) => "(def " ++ nm ++ " (lam " ++ dlen(env) ++ " " ++ body ++ ")) " ++ parseDefs(r6, dq) } } }
def compile(src, dq) = "(program (defs " ++ parseDefs(lex(src, 0, src.length), dq) ++ ") (entry (app (global main))))"
