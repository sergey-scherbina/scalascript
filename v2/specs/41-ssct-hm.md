# 41 — ssct-hm: a Hindley-Milner typed language

`ssct-hm` is a **complete typed functional language** that grows on the frozen Core IR
kernel, written entirely in ssc0. Unlike `ssct` (which *checks* fully-annotated terms,
[`40`](40-typer-as-library.md)), `ssct-hm` **infers** types: you write unannotated source
text, it deduces principal types by **Algorithm W** (unification + let-polymorphism), and
then either interprets the program or compiles it — through the same Core IR — to **the VM,
JavaScript, and native Rust**. The kernel gains **zero** lines for any of this.

```
source text ──lex/parse/desugar──► Term ──HM infer──► type
                                      │
                                      ├─ runHm  (erase types, interpret)         ──► value
                                      └─ erase ──► Core IR ──► run-ir | JS | Rust ──► value
```

## Surface syntax

```
prog   := ('data' Con tyvar* '=' ctor ('|' ctor)* 'in')*  expr
ctor   := Con fieldtype*                 fieldtype := tyvar | 'Int' | 'Bool' | 'String' | '[' fieldtype ']' | '(' Con fieldtype* ')'
expr   := 'fun' x '=>' expr | 'let' ['rec'] x '=' expr 'in' expr | 'if' expr 'then' expr 'else' expr
        | 'match' expr '{' arm ('|' arm)* '}' | cmp
arm    := Con x* '=>' expr
cmp    := add (('=' | '<' | '>' | '<=' | '>=' | '<>') add)?
add    := mul (('+' | '-' | '++') mul)*           mul := app ('*' app)*           app := atom atom*
atom   := int | string | 'true' | 'false' | '[' (expr (',' expr)*)? ']' | x | Con | '(' expr ')'
```

Lowercase identifiers are variables; **Capitalized** identifiers are data constructors.
Built-in functions (desugared, not first-class): `cons head tail isNil nil` (lists),
`showInt strEq` (strings).

## Types

`Type = TyInt | TyBool | TyStr | TyVar n | TyList t | TyCon name [t] | TyFun a b`.

- **Inference** (Algorithm W): fresh type variables + a unifying substitution with an
  occurs-check. `fun x => x + 1` infers `Int -> Int`; `fun x => x` infers `t0 -> t0`.
- **Let-polymorphism**: a `let`-bound value is generalized to a scheme `∀vars. t` over the
  variables free in it but not in the environment, and instantiated fresh at each use. So
  `let id = fun x => x in id id` typechecks (an occurs-check error without generalization).
- **Lists** are polymorphic: `nil : [a]`, `[1,2,3] : [Int]`, heterogeneous lists rejected.
- **User ADTs**: a `data` declaration registers each constructor's signature; a constructor
  application instantiates the type's parameters fresh and unifies the arguments against the
  field types; a `match` unifies the scrutinee with each arm's constructor result type, binds
  the field types to the arm's binders, and unifies all arm bodies. `Some 5 : Option Int`,
  `Node (Leaf 1) (Leaf 2) : Tree Int`.
- Comparisons `> <= >= <>` desugar to `</=/if`; equality/order are on `Int` (see the
  typeclass note below).

## Components (all ssc0, on the frozen kernel)

| File | Role |
|---|---|
| `lib/ssct-hm-front.ssc0` | lexer + combinator parser + desugar (`data`/`match`/lists/strings) |
| `lib/ssct-hm.ssc0` | the inferrer (Algorithm W), the constructor registry (a `#cell`), the erased interpreter (`runHm`) |
| `lib/ssct-hm-emit.ssc0` | erase a checked Term to a Core IR Data tree (then `coreir.encode`) |
| `bin/ssct-hm.ssc0` | driver: print the inferred type |
| `bin/ssctc-hm.ssc0` | compiler: emit Core IR bytecode (run with `ssc run-ir`) |
| `bin/ssct-hm-js.ssc0`, `bin/ssct-hm-rust.ssc0` | compile to JS / Rust (reuse `backend-{js,rust}-gen.ssc0`) |

The backend codegens were split (`backend-{js,rust}-gen.ssc0`) so any front — including this
one — reuses them; that is why an inferred-typed program reaches all three targets.

## Worked programs (all source text → all three backends)

```
let rec fact = fun n => if n = 0 then 1 else n * fact (n - 1) in fact 5        => 120
map (fun x => x * x) [1, 2, 3]                                                 => [1, 4, 9]
let rec qsort = ... in qsort [3, 1, 1, 4, 2]                                   => [1, 1, 2, 3, 4]
"n=" ++ showInt (40 + 2)                                                       => "n=42"
data Expr = Num Int | Plus Expr Expr | Times Expr Expr in
let rec eval = fun e => match e { Num n => n | Plus a b => eval a + eval b
                                 | Times a b => eval a * eval b } in
eval (Plus (Num 1) (Times (Num 2) (Num 3)))                                    => 7
```

## Note on typeclasses

Equality and ordering are wired to `Int`. Making them polymorphic via dictionaries would need
either qualified types or a type-annotated elaboration pass (the inferrer returns a type, not
an elaborated term; one-pass resolution is unsound because HM defers unification), and the
**frozen** kernel exposes only `i.eq` / `f.eq` / `seq` — no generic structural equality — so a
polymorphic `=` cannot stay consistent across the VM and the backends without invasive work.
The simpler `ssct` ([`52`](52-typeclasses.md)) already demonstrates typeclass resolution with
conditional instances; integrating that into HM is deferred.
