#!/usr/bin/env bash
set -euo pipefail

# The two import surfaces disagree about a MISSING module, and this pins which does what.
#
# `import std.nosuchmodule.anything` runs to completion with no diagnostic; the Markdown link form
# `[anything](std/nosuchmodule.ssc)` reports `native frontend import not found`. Only the link form
# reaches `NativeSourceClosure.resolveImport` — the keyword form is never recognised as an import at
# all, so nothing resolves it and nothing can fail.
#
# THIS GATE DOES NOT ASSERT THAT THE CURRENT BEHAVIOUR IS RIGHT. Making the keyword form fail is a
# semantic decision with an owner: `import a.b.c` that maps to no file is legitimate in programs that
# work today, so turning it into an error is a compatibility change, not a bug fix. What the gate
# does is make the divergence MEASURABLE, so that decision can be verified when someone takes it —
# and so it cannot drift by accident in the meantime. When the keyword form is given a diagnostic,
# this file is where that change becomes visible: the `SILENT` assertion below will fail, which is
# the intended way to find out. (BUGS.md `keyword-import-of-a-missing-module-is-a-silent-no-op`.)
#
# THE MODULE NAME MATTERS AND THE ENTRY SAYS WHY. A first probe used `Response`, which is a BUILTIN:
# the import appeared to "work" because the program worked without any import at all. A probe whose
# subject is reachable WITHOUT the thing under test measures nothing. `nosuchmodule_9d4f` cannot
# exist and cannot be a builtin.

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
V2="$ROOT/bin/ssc"
[[ -x $V2 ]] || { echo 'keyword-import-missing-module: run ./install.sh --dev first' >&2; exit 2; }

tmp=$(mktemp -d "${TMPDIR:-/tmp}/kw-import.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

printf 'import std.nosuchmodule_9d4f.anything\n\ndef main() =\n  println("ran")\n' > "$tmp/kw.ssc"
printf '[anything](std/nosuchmodule_9d4f.ssc)\n\ndef main() =\n  println("ran")\n'  > "$tmp/link.ssc"

kw_out=$(SSC_NO_BUILD_CHECK=1 timeout 180 "$V2" run --v2 "$tmp/kw.ssc" 2>&1 </dev/null || true)
if [[ $kw_out != *"import not found"* ]]; then
  echo 'keyword-import-missing-module: the KEYWORD form stopped reporting a missing module.' >&2
  echo '  It reported one from 2026-08-10; going quiet again is a regression, not a decision.' >&2
  echo "--- got" >&2; printf '%s\n' "$kw_out" >&2
  exit 1
fi

# THE TWO SIDES THE DIAGNOSTIC MUST NOT TOUCH, and they are the whole reason it is scoped to `std.`.
# Counted across every `.ssc` in the repo before implementing: `std` is the ONLY import root whose
# names land in a declared package (18 of 19). `scalascript` (95 imports), `scala` (32), `actors`
# (11), `org` (8), `java` (3) resolve to ZERO — they are host and plugin surfaces, not modules, and
# holding them to "not found" would refuse twenty-odd correct lines.
printf 'import scala.concurrent.Await\nimport actors.Overflow\n\ndef main() =\n  println("host-silent")\n' > "$tmp/host.ssc"
host_out=$(SSC_NO_BUILD_CHECK=1 timeout 180 "$V2" run --v2 "$tmp/host.ssc" 2>&1 </dev/null || true)
if [[ $host_out != *host-silent* ]]; then
  echo 'keyword-import-missing-module: a HOST or PLUGIN root was refused.' >&2
  echo '  Those namespaces are not modules and must stay silent; the check is scoped to `std.`.' >&2
  echo "--- got" >&2; printf '%s\n' "$host_out" >&2
  exit 1
fi

# …and a REAL std module must still import. Without this the check could pass by refusing everything.
printf 'import std.crypto.*\n\ndef main() =\n  println("std-ok")\n' > "$tmp/real.ssc"
real_out=$(SSC_NO_BUILD_CHECK=1 timeout 180 "$V2" run --v2 "$tmp/real.ssc" 2>&1 </dev/null || true)
if [[ $real_out != *std-ok* ]]; then
  echo 'keyword-import-missing-module: a DECLARED std package was refused.' >&2
  echo "--- got" >&2; printf '%s\n' "$real_out" >&2
  exit 1
fi

link_out=$(SSC_NO_BUILD_CHECK=1 timeout 180 "$V2" run --v2 "$tmp/link.ssc" 2>&1 </dev/null || true)
if [[ $link_out != *"import not found"* ]]; then
  echo 'keyword-import-missing-module: the LINK form stopped reporting a missing module.' >&2
  echo '  This is the half that WAS honest; losing it is a regression, not a decision.' >&2
  echo "--- got" >&2; printf '%s\n' "$link_out" >&2
  exit 1
fi

echo 'PASS keyword-import-missing-module (both forms report; host/plugin roots and real std packages untouched)'
