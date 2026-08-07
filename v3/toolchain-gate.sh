#!/usr/bin/env bash
# v3 SSC3-13 — v3 builds and runs WITHOUT `scala-cli`, with the compiler the sources declare.
#
# Two claims, and neither is provable by grepping for a string.
#
# 1. NO `scala-cli`. v3 shelled into it for every artifact until 2026-08-07. It could stop because
#    of invariant I-1: `v3/src` and `v2/src` declare zero dependencies, and dependency resolution is
#    the only thing `scala-cli` offers over the compiler itself. A grep would pass on a file that
#    still calls it from a branch the grep's pattern misses, so this EMULATES THE WEAKER HOST — it
#    builds with `scala-cli` removed from `PATH` and requires the build to work anyway. A dependence
#    that is merely unused looks identical to one that is absent until you take the thing away.
#
# 2. THE COMPILER IS THE DECLARED ONE. The `scalac` on this host is 3.7.2 while every source says
#    `//> using scala 3.8.3`, and building through the launcher on `PATH` compiled the kernel with
#    the wrong compiler and said nothing. Silent version drift is the reason the version is read
#    out of the source rather than written down twice.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2

fail=0
say() { printf '  %-6s %s\n' "$1" "$2"; }

echo "── v3 builds with no scala-cli, on the compiler its sources declare ───────"

declared="$(grep -h '^//> using scala ' v3/src/*.scala | head -1 | awk '{print $NF}')"
if [ -n "$declared" ]; then
  say ok "the sources declare Scala $declared"
else
  say FAIL "no '//> using scala' line in v3/src — the version pin has nothing to read"
  fail=1
fi

# A `scala-cli` THAT FAILS, first on `PATH`. Not a PATH with its directory removed, which is what
# this gate did first and which quietly proved less than it claimed: `cs` lives in the same
# Coursier directory, so removing it took the RESOLVER away too, and the build only worked because
# a toolchain classpath was already cached. The gate was green for the wrong reason — the exact
# shape it exists to catch, found by planting a version drift and watching it fail with "coursier
# is needed" instead of the version message.
#
# A shim shadows `scala-cli` and nothing else, so every other tool stays reachable and any call to
# `scala-cli` is a loud non-zero exit rather than a silent success.
SHIM="$(mktemp -d)"
cat > "$SHIM/scala-cli" <<'SHIMEOF'
#!/bin/sh
echo "scala-cli: this host does not have it (v3 SSC3-13 shim)" >&2
exit 127
SHIMEOF
chmod +x "$SHIM/scala-cli"
NOSCLI="$SHIM:$PATH"
cleanup() { rm -rf "$SHIM"; }
trap cleanup EXIT

if PATH="$NOSCLI" scala-cli --version >/dev/null 2>&1; then
  say FAIL "the shim did not take — a real scala-cli answered ahead of it"
  fail=1
else
  say ok "scala-cli fails loudly on the emulated host"
fi
if ! PATH="$NOSCLI" command -v cs >/dev/null 2>&1; then
  say note "coursier is not reachable either, so toolchain RESOLUTION cannot be exercised here"
else
  say ok "coursier is still reachable — resolution is under test, not just the cache"
fi

# THE BUILD, from nothing. The artifact cache is what makes the gate cheap and also what would make
# it VACUOUS — a cached build proves only that someone built it earlier, possibly with the tool this
# gate says is gone. So the cache is emptied first.
# The TOOLCHAIN cache goes too. Leaving it made the gate prove only that an already-resolved
# compiler works without scala-cli, which is a much smaller claim than the one on the tin.
rm -rf v3/.jars/ssc3-* v3/.jars/ssc2-* v3/.jars/uniml-* v3/.jars/toolchain-*.cp
if out="$(PATH="$NOSCLI" v3/ssc3 selftest 2>&1)" && printf '%s' "$out" | grep -q 'self-test: OK'; then
  say ok "the kernel builds from scratch and passes its self-test with no scala-cli"
else
  say FAIL "the kernel could not build without scala-cli: $(printf '%s' "$out" | tail -1 | cut -c1-80)"
  fail=1
fi

# The BRIDGE too — `v2/src` is the other tree v3 compiles, and it is the one with `-Xss512m` and a
# different entry point, so "the kernel works" says nothing about it.
probe="$(mktemp -t ssc3tc).ssc"
cleanup() { rm -rf "$SHIM" "$probe"; }
printf 'println(6 * 7)\n' > "$probe"
if [ "$(PATH="$NOSCLI" v3/ssc3 run --bridge "$probe" 2>&1)" = "42" ]; then
  say ok "the v2 bridge builds and runs with no scala-cli"
else
  say FAIL "the bridge failed without scala-cli: $(PATH="$NOSCLI" v3/ssc3 run --bridge "$probe" 2>&1 | tail -1 | cut -c1-70)"
  fail=1
fi

# And the SECOND FRONT, which is the one that genuinely needs an external classpath — UniML's jars,
# from sbt. That dependency is real and stays; `scala-cli` is not part of it.
if [ -s "v3/.jars/uniml.cp" ]; then
  if [ "$(PATH="$NOSCLI" v3/ssc3 front 2>/dev/null | sed -n 's/^front: //p')" = "uniml" ]; then
    say ok "the uniml front builds and is selected with no scala-cli"
  else
    say FAIL "the uniml front did not come up without scala-cli"
    fail=1
  fi
else
  say note "uniml.cp is absent, so the second front could not be exercised here"
fi

# THE VERSION ACTUALLY USED, not the one intended. Read off the compiler classpath the driver
# resolved — if the pin ever stops being read from the source, this is what notices.
tcp="$(PATH="$NOSCLI" v3/ssc3 __kernel-cp 2>/dev/null)"
if printf '%s' "$tcp" | tr ':' '\n' | grep -q "scala3-library_3-$declared\.jar"; then
  say ok "the classpath carries scala3-library $declared — the pin is the one in force"
else
  say FAIL "the kernel classpath does not carry scala3-library $declared: $(printf '%s' "$tcp" | tr ':' '\n' | grep -o 'scala3-library_3-[^/]*' | head -1)"
  fail=1
fi

# SELF-TEST: the emulation must be capable of REFUSING. If the shim were ineffective every check
# above would pass while proving nothing — the vacuous-gate shape this repository has paid for
# twice. So call the shim directly and require it to fail.
if PATH="$NOSCLI" sh -c 'scala-cli --power package x' >/dev/null 2>&1; then
  say FAIL "the shim let a scala-cli command succeed — the emulated host is not emulating"
  fail=1
else
  say ok "a scala-cli invocation on the emulated host exits non-zero"
fi

echo
[ "$fail" = 0 ] && echo "== v3 SSC3-13 gate: GREEN ==" || echo "== v3 SSC3-13 gate: RED =="
[ "$fail" = 0 ]
