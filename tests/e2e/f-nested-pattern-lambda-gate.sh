#!/usr/bin/env bash
# A nested constructor pattern must match the same way whatever binds the scrutinee.
#
# WHY THIS EXISTS. F had a fast path for `(x) => x match { … }` (tryLamDirect) that emitted
# `(lam 1 (match (local 0) …))` with no let-wrap and parsed the arms with parseArms, which has no
# nested-pattern machinery. `case Some(Inner(v))` came out as `(arm Some 3 …)` -- wrong arity, no
# inner match -- so the arm never fired and the default was taken. Silently, at exit 0. It reached
# origin/main and turned `f-output-agreement-gate` red on examples/scljet-readonly.ssc, where every
# decoded record field fell through to `case _ => "other"`.
#
# The fast path is gated on the lambda having exactly ONE parameter, so the SAME match in a
# two-parameter lambda was always correct. That is the trap this gate is shaped around: any probe
# with a second binder -- another param, a `val`, a `def` instead of a lambda -- passes on the bug.
# The rows below carry both, so a regression cannot hide behind the shape that always worked.
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.." || exit 2
export SSC_NO_BUILD_CHECK=1
SELFTEST=0; [ "${1:-}" = "--self-test" ] && SELFTEST=1
. tests/e2e/lib/ssc-usable.sh 2>/dev/null || true
if [ $SELFTEST -eq 0 ] && command -v ssc_usable_or_skip >/dev/null 2>&1; then
  ssc_usable_or_skip f-nested-pattern-lambda-gate ./bin/ssc || exit 0
fi

work=$(mktemp -d "${TMPDIR:-/tmp}/fnpl.XXXXXX"); trap 'rm -rf "$work"' EXIT HUP INT TERM
fail=0

# Every probe answers 7. A wrong answer and a refusal are both failures: with the nested field NOT
# last the old bug did not fall through, it refused with `unbound global: (global q)`, so the later
# binders were lost too. Both symptoms are one shift and both belong here.
run() {
  local name=$1 body=$2
  printf '%s\n' "$body" > "$work/$name.ssc"
  local f ref
  f=$(SSC_FRONT_STRICT=1 timeout 90 ./bin/ssc run "$work/$name.ssc" 2>&1 | head -1)
  ref=$(SSC_FRONT=legacy timeout 90 ./bin/ssc run "$work/$name.ssc" 2>&1 | head -1)
  [ $SELFTEST -eq 1 ] && [ "$name" = "lambda-1-param" ] && f="-1"
  if [ "$f" = "7" ] && [ "$ref" = "7" ]; then
    printf '  %-18s 7\n' "$name"
  else
    printf '  %-18s F: %s   эталон: %s\n' "$name" "$f" "$ref"
    fail=$((fail+1))
  fi
}

H='case class Inner(v: Int)'

echo "── вложенный конструкторный паттерн, ждём 7 везде:"
# THE defect: one-parameter lambda. Everything else is the control that it stays fixed for the
# right reason rather than because the fast path was disabled wholesale.
run lambda-1-param "$H
def main() =
  val f = (x: Option[Inner]) => x match { case Some(Inner(v)) => v case _ => -1 }
  println(f(Some(Inner(7))))"
run lambda-applied "$H
def main() =
  println(((x: Option[Inner]) => x match { case Some(Inner(v)) => v case _ => -1 })(Some(Inner(7))))"
run lambda-2-params "$H
def main() =
  val f = (k: Int, x: Option[Inner]) => x match { case Some(Inner(v)) => v case _ => -1 }
  println(f(5, Some(Inner(7))))"
run lambda-in-map "$H
def main() =
  println(List(Some(Inner(7))).map(x => x match { case Some(Inner(v)) => v case _ => -1 }).head)"
run def-1-param "$H
def g(x: Option[Inner]): Int = x match { case Some(Inner(v)) => v case _ => -1 }
def main() = println(g(Some(Inner(7))))"
# Nested field NOT last -- the refusal symptom.
run nested-not-last "$H
case class Two(p: Option[Inner], q: Int)
def main() =
  val f = (x: Two) => x match { case Two(Some(Inner(v)), q) => v case _ => -1 }
  println(f(Two(Some(Inner(7)), 9)))"
# The shapes the fast path legitimately serves: they must KEEP working, or a fix that simply
# disabled tryLamDirect would pass this gate while undoing a deliberate optimisation.
run flat-ctor-arm "$H
case class Four(a: Int, b: Int, c: Int, d: Int)
def main() =
  val f = (x: Four) => x match { case Four(a, _, _, _) => a case _ => -1 }
  println(f(Four(7, 2, 3, 4)))"
run shallow-option "$H
def main() =
  val f = (x: Option[Int]) => x match { case Some(v) => v case None => -1 }
  println(f(Some(7)))"

if [ $SELFTEST -eq 1 ]; then
  if [ $fail -eq 1 ]; then echo "✓ self-test: гейт краснеет на подложенном ответе"; exit 0
  elif [ $fail -gt 1 ]; then echo "✗ self-test: покраснело $fail строк вместо одной — гейт красный и без подложки"; exit 1
  else echo "✗ self-test: подложил неверный ответ, гейт остался зелёным — он ничего не проверяет"; exit 1; fi
fi
if [ $fail -gt 0 ]; then echo "✗ f-nested-pattern-lambda-gate: провалов — $fail"; exit 1; fi
echo "✓ f-nested-pattern-lambda-gate: все формы согласны"
