# `ssc.lib.path` Resolution — Native-Front and Staged-Jar Discovery

Status: **implemented**, 2026-08-28.
Companion: `NativeImageInstallRoot.scala` (the code this spec documents),
`BUGS.md` `native-binary-missing-front-message-blamed-a-checkout-that-cannot-exist`
(the original defect this line of work started from).

---

## 1. One `ssc.lib.path` convention, project-wide

`ssc.lib.path` names the `lib/` directory itself — EVERYWHERE. Every consumer appends only its own
asset-specific subpath directly onto it:

- `NativeImageInstallRoot`/`RunNativeV2`/`NativeJvmArtifact` → `standard/native-front`,
  `standard/jars` (§2).
- `JarCommands` → `ssc.jar`, `jars/`.
- `CompilerLoader` → `compiler/jars/`.
- `JvmGen`/`SparkGen` → `jars/`.
- `PluginManifest` and `Main.scala`'s startup essential-plugin auto-load → `compiler/plugins/`.
- `Main.scala`'s `pluginAvailableDirs`/x402-tools-classpath helpers → `compiler/plugin-available/`,
  `tools/x402/`.
- `InstallCommands.scala`'s `bin/ssc`/`bin/ssc-tools`/`bin/ssc-standard` launcher templates now
  SET it this way too — `-Dssc.lib.path="$destRoot/bin/lib"` — instead of the checkout root; the
  physical `bin/lib/` tree they point at is unchanged, only the property's value changed to name
  it directly.
- `build.sbt`'s own launcher-script generator (`installBin`, the checkout's `bin/ssc`/`bin/ssc-
  tools`/`bin/ssc-provider`) sets `-Dssc.lib.path="$_SSC_BIN/lib"` (`$_SSC_BIN` is already `bin/`),
  matching the same convention for the checkout itself.

**The one exception is `ImportResolver`'s `std/`/`scljet/` resolution**, and only because `std/`
and `scljet/` genuinely do not live under `lib/` — they live at the checkout/install ROOT
(`<root>/std`, `<root>/scljet`), which is a different directory than `lib/` in every layout. This
does NOT need special-casing at the `ssc.lib.path` level, because `ImportResolver.discoverStdRoot`
already had an INDEPENDENT discovery path that finds the ROOT regardless of `ssc.lib.path`'s
value: rule 5 walks up from `jarDir` (the directory holding the running classes/jar) until it
finds a `std/` or `runtime/std/`, which works for a JVM-launcher run whether `ssc.lib.path` points
at the ROOT or at `lib/` — verified empirically, live, before committing to this change (see §2d).
`ImportResolver.libPath`'s own rule 3 (`lib.filter(hasStd)`) and `discoverScljetRoot`'s `lib/
scljet` check simply return `None` now (since `lib/std`/`lib/scljet` don't exist) and fall through
to that independent path — kept only for backward compatibility with a hand-set `SSC_LIB_PATH`
still pointed at a ROOT the old way, not because anything depends on them succeeding today.

`InstallCommands.selfInstallCommand` (`ssc install`) is the one place that ALSO needs `std/`'s real
location directly (to copy it to a new prefix) — it now reads `ImportResolver.stdPath` for that,
instead of building a `std/` path off `ssc.lib.path` (which no longer points anywhere near it).

## 2. What `NativeImageInstallRoot` solves

A **JVM launcher** always sets `ssc.lib.path` itself before the JVM starts — `nativeFrontLayout`
and `NativeJvmArtifact.runCommand` never need to discover anything in that case. A **native-image
binary has no launcher**: nothing sets the property, so `NativeImageInstallRoot.configure()` (run
from `Main.scala` at startup, gated on `org.graalvm.nativeimage.imagecode == "runtime"`) must
derive it from the running executable's own path.

### 2a. Three physical layouts, one discovery algorithm

A `ssc` binary and its `lib/` directory can be laid out three ways. `discoverLib` finds `lib/`
next to the executable, then one level up — in that order, so a `lib/` beside the executable
always wins over one further away:

| Layout | Shape | Who ships it |
|---|---|---|
| 1 | `<root>/ssc` + `<root>/lib/...` | the GitHub release archive (`ssc-<platform>.tar.gz`) |
| 2 | `<root>/bin/ssc` + `<root>/lib/...` (`bin/` a sibling of `lib/`) | a hand-assembled install prefix; nothing in this repo produces it today, but discovery accepts it for free |
| 3 | `<root>/bin/ssc` + `<root>/bin/lib/...` | the checkout's own `bin/` tree (`install.sh --dev`) |

```
execDir = the real, resolved parent directory of the running executable
candidates = [ execDir/lib, execDir's parent/lib ]     # checked in this order
LIB = the first candidate that contains lib/standard/native-front
```

Layout 1 and 3 both resolve on the FIRST candidate (`execDir/lib` — the archive's `lib/`, or the
checkout's `bin/lib`). Layout 2 falls through to the second candidate (`execDir`'s parent, i.e.
the directory that holds both `bin/` and `lib/`). If neither candidate holds a real front,
`ssc.lib.path` is left unset and `RunNativeV2`/`NativeJvmArtifact` throw
`NativeImageInstallRoot.MissingInstallRootMessage` the next time they need it — this happens ONLY
inside a native-image binary (see `configure`'s `isNativeRuntime` guard), so the message never
needs to address a checkout audience (`scripts/sbtc`, `bin/ssc` can't be what the reader has).

`SSC_LIB_PATH` (or an already-set `-Dssc.lib.path`) always wins over discovery, unmodified — a
user or launcher setting it explicitly is authoritative.

### 2b. `resolveUnderLib` — a backward-compatible fallback, not the normal path

`ssc.lib.path` is `lib/`-dir-shaped in the NORMAL case now, from every source: native-image
discovery (§2a), the checkout's own `bin/ssc`/`bin/ssc-tools` (both now set it to `bin/lib`
directly), and a freshly self-installed prefix (`ssc install`, §1). `resolveUnderLib(root, suffix)`
still tries that direct shape first (`root/<suffix>`) and falls back to the older ROOT shape
(`root/bin/lib/<suffix>`) only for backward compatibility — a hand-set `SSC_LIB_PATH` still pointed
at a checkout root the pre-unification way. Nothing in this project sets the ROOT shape anymore;
the fallback exists purely so an external override in that shape does not silently break.

```scala
def resolveUnderLib(installRoot: File, suffix: String): File =
  val direct = new File(installRoot, suffix)
  if direct.exists() then direct else new File(installRoot, s"bin/lib/$suffix")
```

### 2c. Recovering a ROOT for the two remaining ROOT-shaped consumers

Two things downstream of `nativeFrontLayout` still want an actual ROOT, not a `lib/` directory:
the self-hosted tower's own `--lib-root` flag, and `NativeSourceClosure`'s bare repo-relative
import fallback (`tests/conformance/lib/foo.ssc`-style imports that are neither `std/…` nor
`./…`-relative) — both pre-date this work and are unrelated to it. `NativeImageInstallRoot.
isLibShaped`/`rootAbove` recover a ROOT value from whichever shape `resolveUnderLib` actually
matched:

- the normal case now: the lib-dir shape matched, so `rootAbove` walks up one level, or two when
  the level directly above is named `bin` (so a `<root>/bin/lib` shape recovers `<root>`, not
  `<root>/bin`);
- the backward-compat case: a hand-set ROOT-shaped `SSC_LIB_PATH` matched instead, so the value
  passes through completely unchanged.

### 2d. Why `ImportResolver` needed no changes — verified live, not just reasoned

Before touching `InstallCommands.scala`'s launcher templates (the change with the widest blast
radius, since it flips what value every JVM-launcher run passes), the risk was checked directly
against a real running interpreter rather than trusted from reading the code: with `ssc.lib.path`
pointed straight at `<checkout>/bin/lib` (the NEW shape, not the ROOT `ImportResolver`'s doc
comments described at the time),

```
java -Dssc.lib.path=<checkout>/bin/lib -cp bin/lib/standard/jars/*:bin/lib/standard/ssc.jar \
  scalascript.cli.StandardMain t.ssc     # imports std/json.ssc — resolved
java ... same ...                         t.ssc     # imports std/scljet/index.ssc — resolved
java ... same ...                         t.ssc     # a bare tests/-relative import — resolved
```

all three resolved correctly. This is because `discoverStdRoot`'s rule 5 (an ancestor walk from
`jarDir`, independent of `ssc.lib.path` entirely) already finds the checkout root in a dev-tree
run, and `discoverScljetRoot`/the bare-import fallback both consult `stdPath` — which rule 5
already fixed — before ever falling back to a `libPath`-built candidate. The three-line
`ImportResolver.libPath` doc comment (§1) and `AutoResolve.scala`'s inline comment were updated
to state this plainly, so the next reader does not have to re-derive it.

## 3. The release archive's physical layout

Before this work, the archive nested the front under `bin/lib/standard/native-front` — forced
`bin/` nesting for no reason specific to the archive (there is no `bin/ssc` launcher in an
archive; the binary sits at the archive root). The archive now ships:

```
ssc                              # the native-image binary, at the archive root
lib/ssc-plugin-host.jar          # unchanged — BackendRegistry finds this independently
                                  # of ssc.lib.path (its own <binary-dir>/lib/ search)
lib/standard/native-front/...    # moved out from under bin/ — layout 1, §2a
README.md
```

The checkout's own `bin/` tree is UNCHANGED (`bin/ssc`, `bin/lib/standard/native-front`,
`bin/lib/jars`, `bin/lib/compiler/jars`, …) — layout 3, already self-consistent, nothing here
moves anything inside it.

`native-release.yml`'s assemble step, `scripts/native-release-qualify`'s archive-layout allowlist
and required-file list, and `tests/e2e/native-release-qualification.sh`'s fixture generator were
all updated to the new archive path in lockstep.

## 4. What changed everywhere, and what a near-miss taught

Every consumer listed in §1 dropped its `bin/lib/…` (or, for `Main.scala`'s bench-command launcher
lookup, its `bin/ssc-tools`) suffix computation against `ssc.lib.path`, since the property already
names that directory now. `BackendRegistry.scala`'s `ssc-plugin-host.jar` lookup is the one true
zero-change case — it already searches `<binary-dir>/lib/ssc-plugin-host.jar` independently of
`ssc.lib.path` entirely (its own `<binary-dir>/lib/` walk), which already matched the archive's
`lib/ssc-plugin-host.jar` placement; there was nothing to unify there.

**A near-miss during this change, left in as a cautionary note for the next one:** the first pass
updated `PluginManifest.defaultSearchPaths` but missed a SECOND, independent computation in
`Main.scala`'s startup essential-plugin auto-loader — a few lines that build `bin/lib/compiler/
plugins` off `ImportResolver.libPath` directly, not through `PluginManifest` at all. `scripts/smoke-
ci` caught it immediately and unambiguously: 17 checks failed with `Undefined: serve` /
`Undefined: __jsonCoreInstallRenderer` / similar — every essential plugin (http, json, ws, …) had
stopped auto-loading, because the ONLY thing wrong was that startup path still appending `bin/lib`
to a value that was now already `bin/lib`, landing on `bin/lib/bin/lib/compiler/plugins`, which
never exists. The fix was one line; the lesson is procedural — `grep` for every literal
`"bin"`/`"lib"` pair against `ssc.lib.path`/`ImportResolver.libPath` project-wide BEFORE declaring
a property-meaning change complete, since a call site that duplicates another's logic instead of
sharing it will not show up by re-reading the file you already fixed.

## 5. Verification

- `NativeImageInstallRootTest.scala` — `discoverLib` exercises all three layouts (§2a) plus a
  precedence test (a `lib/` beside the executable wins over one one level up) and a symlink-
  resolution test; `resolveUnderLib`/`isLibShaped`/`rootAbove` are tested directly against both
  shapes. 18/18 green.
- `tests/e2e/native-release-qualification.sh` — the archive fixture's front now lives at
  `lib/standard/native-front`; 64 compare-first cases, green.
- `tests/e2e/native-release-publication.sh` — unaffected by this change (asset names, not their
  internal layout); 40 compare-first cases, green.
- `V2RunArgvCliTest`/`V2ActorCliTest`/`V2CaseClassMethodCliTest`/`JvmTransitiveStdImportCliTest`/
  `EmitScalaFacadeCliTest`/`JvmDirectDriverTest`/`StdRootResolutionTest` — 45/45 green, run against
  a freshly rebuilt `ssc.jar` (a stale, month-old jar from before this work gave one false failure
  that disappeared once `cli/assembly` actually ran).
- `ssc install --prefix <tmp>` exercised end-to-end: copies `bin/lib/` (via the now-direct
  `srcLib = ImportResolver.libPath`) and `std/` (via `ImportResolver.stdPath`, §1) to a fresh
  prefix, writes a launcher, and the installed binary resolves `std/json.ssc` correctly.
- `compile-jvm --bytecode`/`link --bytecode` (both load the Scala compiler via `CompilerLoader`)
  exercised directly through `bin/ssc-tools`.
- Manual, on a locally rebuilt `ssc-macos-arm64` native-image binary + freshly assembled archive
  (layout 1): `search --refresh` (network), the missing-install-root message (bare binary with no
  `lib/`), and `repl :load` all verified end-to-end, plus layouts 2 and 3 reproduced by hand-moving
  the same built binary and its `lib/` directory into each shape and re-running `--version` +
  a `std/…`-importing script.
- `scripts/smoke-ci`: 99/116 green on the first full run (the `Main.scala` near-miss above, §4),
  116/116 green after the fix.

## 6. `std/` ships as its own top-level `lib/std/`, separate from the compiler front

Before this section, the staged `.ssc` standard library sat at `lib/standard/native-front/runtime/
std/` — nested three levels inside the self-hosted compiler's own directory, and physically
duplicated (byte-identical) at `lib/native-front/runtime/std/` too, because `build.sbt` staged the
whole native-front tree once per tier (`standard/` and the legacy no-prefix copy) and `std/` rode
along both times for free. Asked directly why a *library* lived nested inside a *compiler*'s own
directory: it never needed to. `RunNativeV2.nativeFrontLayout`'s `stdRoot` and `build.sbt`'s
staging destination now point at `lib/std/` directly — one copy, independent of which native-front
tier resolved, resolved via `NativeImageInstallRoot.resolveUnderLib(installRoot, "std")` exactly
like every other `lib/`-relative asset (§1).

`runtime/` itself was a pure empty-wrapper directory (it held nothing but `std/`), so it is gone
entirely, not just relocated — there is no `lib/standard/native-front/runtime/` anymore.

`RunNativeV2.nativeFrontLayout` now resolves `stdRoot` as the PARENT of wherever `resolveUnderLib`
found `std/` (not `std/` itself — `NativeSourceClosure`'s import resolution appends `"std/…"`
itself), so the same dual-shape backward compatibility from §2b applies here too.

The release archive, `scripts/native-release-qualify`'s allowlist/required-file list/manifest
verification (now TWO manifests — `lib/standard/native-front/MANIFEST.sha256` for the tower,
`lib/std/MANIFEST.sha256` for the library — verified independently), and
`tests/e2e/native-release-qualification.sh`'s fixture generator were all split to match: the
fixture now builds two separate trees with two separate manifests instead of one combined tree.

Verified: `tests/e2e/native-release-qualification.sh` 64/64 green (fixture split into independent
front/std trees + manifests). `scripts/smoke-ci` 116/116 green. Manually rebuilt
`ssc-macos-arm64` + assembled archive on the new layout (`lib/std/*.ssc` at the top level,
`lib/standard/native-front/` containing only `tower/`): `std/json.ssc` resolution and network
(`search --refresh`) both verified end-to-end.

## 7. `native-front` drops the `standard/` prefix; the legacy duplicate is gone

Before this section, `native-front` was staged TWICE per archive — once at `lib/standard/
native-front/` and once again, byte-identical, at `lib/native-front/` (no prefix, called "legacy"
in code and comments). `RunNativeV2.nativeFrontLayout` picked whichever it found first, always
`standardBase` (`lib/standard/native-front/`), because `standardDir` was staged unconditionally by
`build.sbt` and therefore always existed — so the "legacy" copy was never actually read by anything;
it was staged, shipped, and qualified for no reason.

Asked directly why the prefix existed at all: it was inherited from the tools-tier vs.
standard-tier classpath split (`bin/lib/jars/` + `bin/lib/ssc.jar` for `ssc-tools` vs.
`bin/lib/standard/jars/` + `bin/lib/standard/ssc.jar` for the class-filtered `ssc`), a split that is
real and stays — but `native-front` itself is not tier-specific data; both launcher tiers read the
exact same self-hosted tower. Tagging it with a tier prefix implied a distinction that never
existed for this asset.

`native-front` now ships as exactly one copy, `lib/native-front/`, resolved through
`NativeImageInstallRoot.resolveUnderLib(installRoot, "native-front")` like every other `lib/`-
relative asset (§1) — `FrontMarker` changed from `Paths.get("standard", "native-front")` to
`Paths.get("native-front")`, and `RunNativeV2`'s `standardBase`/`legacyBase`/`base` three-variable
dance collapsed to a single `resolveUnderLib` call. `build.sbt` no longer runs the second
`IO.copyDirectory` that produced the duplicate.

The release workflow, `scripts/native-release-qualify` (allowlist, required files, manifest
verification), `tests/e2e/native-release-qualification.sh`'s fixture generator, and every e2e gate
that hardcoded `bin/lib/standard/native-front/…` were all updated to the single `bin/lib/
native-front/…` path — including `tests/e2e/v21-slim-distribution-gate.sh`, which used to delete
`bin/lib/native-front` (the then-legacy, then-dead copy) as tools-only cruft; deleting it now would
break the standard tier, since it is the one shared copy both tiers resolve.

Verified: `NativeImageInstallRootTest` 14/14 green. `scripts/smoke-ci` 116/116 green (including a
full, untruncated `f-output-agreement-gate.sh` run — 351 measured, 308 agree, F-wrong 0 — and
`f-front-cache-gate.sh`). Manually rebuilt `ssc-macos-arm64` + assembled archive: layout is flat —
`ssc`, `lib/native-front/`, `lib/std/`, `lib/ssc-plugin-host.jar` — no `standard/` anywhere;
`std/json.ssc` resolution, `search --refresh`, and the missing-`lib/` error message on a bare-binary
copy all reconfirmed end-to-end.
