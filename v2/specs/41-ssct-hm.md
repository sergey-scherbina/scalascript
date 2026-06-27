# 41 — ssct-hm: a Hindley-Milner typed language

`ssct-hm` is a **complete, user-extensible typed functional language** that grows on the
frozen Core IR kernel, written entirely in ssc0. Unlike `ssct` (which *checks* fully-annotated
terms, [`40`](40-typer-as-library.md)), `ssct-hm` **infers** types: you write unannotated
source text, it deduces principal types by **Algorithm W** (unification + let-polymorphism),
and then either interprets the program or compiles it — through the same Core IR — to **the VM,
JavaScript, and native Rust**. The kernel gains **zero** lines for any of this.

```
source text ──lex/parse/desugar──► Term ──HM infer──► type
                                      │
                                      ├─ runHm  (erase types, interpret)         ──► value
                                      └─ erase ──► Core IR ──► run-ir | JS | Rust ──► value
```

## Surface syntax

```
prog   := decl*  expr
decl   := 'data' Con tyvar* '=' ctor ('|' ctor)* 'in'      -- a user algebraic data type
        | 'method' m 'in'                                  -- a user typeclass method
        | 'instance' m T '=' expr 'in'                     -- an instance of m at type-head T
ctor   := Con fieldtype*    fieldtype := tyvar|'Int'|'Bool'|'String'|'Float'|'['fieldtype']'|'('Con fieldtype*')'
expr   := 'fun' x+ '=>' expr                               -- multi-arg lambda (curried)
        | 'let' ['rec'] x x* '=' expr 'in' expr            -- value or function definition (curried)
        | 'if' expr 'then' expr 'else' expr
        | 'match' expr '{' arm ('|' arm)* '}' | or
arm    := Con x* '=>' expr | '_' '=>' expr                 -- constructor arm or wildcard catch-all
or     := and ('||' and)*       and := cmp ('&&' cmp)*     -- boolean operators (loosest .. tighter)
cmp    := add (('=' | '<' | '>' | '<=' | '>=' | '<>') add)?
add    := mul (('+' | '-' | '++') mul)*       mul := app ('*' app)*       app := postfix postfix*
postfix:= atom ('.' field)*                               -- record field access
atom   := int | float | string | 'true' | 'false' | '_'
        | '[' (expr (',' expr)*)? ']'                     -- list literal
        | '{' field '=' expr (',' field '=' expr)* '}'    -- record literal
        | '(' expr (',' expr)* ')'                        -- paren group or tuple
        | x | Con
```

`// ...` is a line comment (to end of line). Lowercase identifiers are variables;
**Capitalized** identifiers are data constructors. Built-in functions (desugared, not
first-class):

| group | names |
|---|---|
| lists | `cons head tail isNil nil` |
| strings | `showInt strEq strLen charAt substr` (`++` is concat) |
| float | `toFloat fadd fsub fmul fdiv flt feq fneg fsqrt` |
| int | `div mod neg` (`+ - *` are operators) |
| tuples | `fst snd` |
| boolean | `not` (`&& ||` are operators) |
| typeclasses | `show eq compare` + any user-declared `method` |

## Types

`Type = TyInt | TyBool | TyStr | TyFloat | TyVar n | TyList t | TyCon name [t] | TyRec [(name,t)] | TyFun a b`.

- **Inference** (Algorithm W): fresh type variables + a unifying substitution with an
  occurs-check. `fun x => x + 1` infers `Int -> Int`; `fun x => x` infers `t0 -> t0`.
- **Let-polymorphism**: a `let`-bound value is generalized to a scheme `∀vars. t` over the
  variables free in it but not in the environment, and instantiated fresh at each use. So
  `let id = fun x => x in id id` typechecks (an occurs-check error without generalization).
- **Currying**: `fun x y => e` and `let f x y = e` desugar to nested single-argument lambdas,
  so partial application works (`let add x y = x + y in add 1`  is `Int -> Int`).
- **Lists** are polymorphic: `nil : [a]`, `[1,2,3] : [Int]`, heterogeneous lists rejected.
- **Tuples**: `(a, b)` / `(a, b, c)` are `Pair`/`Triple` values, rendered `(Int, Bool)`.
- **Records**: `{x = 1, y = true} : {x: Int, y: Bool}` (structural); `r.x` projects a field.
- **User ADTs**: a `data` declaration registers each constructor's signature; a constructor
  application instantiates the type's parameters fresh and unifies the arguments against the
  field types; a `match` unifies the scrutinee with each arm's constructor result type, binds
  the field types to the arm's binders, and unifies all arm bodies. A `_` arm is a catch-all
  (it binds nothing and does not constrain the scrutinee — it lowers to the Core IR `Match`
  default slot). `Some 5 : Option Int`, `Node (Leaf 1) (Leaf 2) : Tree Int`.
- Comparisons `> <= >= <>` desugar to `< / = / if`; boolean `&& || not` desugar to `if`.

## Typeclasses

Resolution is **type-directed at compile time** (the dictionary is the resolved instance
code), built with no inferrer rewrite: each method-use node carries a unique id assigned at
parse; `infer` resolves the instance from the operand's inferred type and records it by id;
`erase` reads that record and emits the instance; the interpreter value-dispatches instead.

- **Built-in `show` / `eq` / `compare`** (Show / Eq / Ord) work over **every** type — base
  types, lists, tuples, `Option`/`Either`, and any user ADT (incl. recursive ones like
  `Tree`) — by recursing on the type. Compound instances emit global helper defs and pass the
  element/field instance as a closed-lambda "dictionary", which composes through nesting:
  `show (Some [1, 2, 3])` ⇒ `"Some([1, 2, 3])"`, `eq (1, 2) (1, 2)` ⇒ `true`.
- **User-defined typeclasses**: `method m in …` declares a method; `instance m T = impl in …`
  registers an implementation for the type whose head name is `T`; a use `m arg` resolves
  monomorphically by the argument's inferred type-head to the matching `impl`. A missing
  instance is a clean type error.

```
method describe in
instance describe Int  = fun n => "int"  in
instance describe Bool = fun b => "bool" in
describe 5                                                                     => "int"
```

## Components (all ssc0, on the frozen kernel)

| File | Role |
|---|---|
| `lib/ssct-hm-front.ssc0` | lexer (+ `//` comments) + combinator parser + desugar (`data`/`method`/`instance`/`match`/lists/tuples/records/strings) |
| `lib/ssct-hm.ssc0` | the inferrer (Algorithm W), the constructor + method/instance registries (`#cell`s), the erased interpreter (`runHm`) |
| `lib/ssct-hm-emit.ssc0` | erase a checked Term to a Core IR Data tree (then `coreir.encode`); structural show/eq helper synthesis |
| `bin/ssct-hm.ssc0` | driver: print the inferred type |
| `bin/ssctc-hm.ssc0` | compiler: emit Core IR bytecode (run with `ssc run-ir`) |
| `bin/ssct-hm-js.ssc0`, `bin/ssct-hm-rust.ssc0` | compile to JS / Rust (reuse `backend-{js,rust}-gen.ssc0`) |

The backend codegens were split (`backend-{js,rust}-gen.ssc0`) so any front — including this
one — reuses them; that is why an inferred-typed program reaches all three targets.

## Worked programs (all source text → all three backends)

```
let rec fact n = if n = 0 then 1 else n * fact (n - 1) in fact 5               => 120
map (fun x => x * x) [1, 2, 3]                                                 => [1, 4, 9]
let rec qsort = ... in qsort [3, 1, 1, 4, 2]                                   => [1, 1, 2, 3, 4]
substr "hello world" 0 5                                                       => "hello"
let p = {x = 3, y = 7} in p.x + p.y                                            => 10
show (Some [1, 2, 3])                                                          => "Some([1, 2, 3])"
data Color = Red | Green | Blue in
let name c = match c { Red => "red" | _ => "other" } in name Green             => "other"
data Expr = Num Int | Plus Expr Expr | Times Expr Expr in
let rec eval e = match e { Num n => n | Plus a b => eval a + eval b
                         | Times a b => eval a * eval b } in
eval (Plus (Num 1) (Times (Num 2) (Num 3)))                                    => 7
```
