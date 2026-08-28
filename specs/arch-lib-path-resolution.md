# `ssc.lib.path` Resolution — Native-Front and Staged-Jar Discovery

Status: **implemented**, 2026-08-28.
Companion: `NativeImageInstallRoot.scala` (the code this spec documents),
`BUGS.md` `native-binary-missing-front-message-blamed-a-checkout-that-cannot-exist`
(the original defect this line of work started from).

---

## 1. Two `ssc.lib.path` conventions, deliberately left as two

This project has, and keeps, **two separate conventions** for what the JVM system property
`ssc.lib.path` means, depending on which code reads it:

1. **The checkout/install ROOT** (the older, pre-existing convention). `ImportResolver`
   (`std/…`, `scljet/…`, bare repo-relative import resolution), `JarCommands`, `CompilerLoader`,
   `JvmGen`/`SparkGen`, and `PluginManifest` all read `ssc.lib.path` as a ROOT and append their own
   `bin/lib/…` (or, for `ImportResolver`, `std/`/`scljet/`/`runtime/…`) suffix themselves. A JVM
   launcher (`bin/ssc`, `bin/ssc-tools`, written by `InstallCommands.scala`) sets this explicitly —
   `-Dssc.lib.path=<checkout-root>` — and NOTHING in this spec changes that. It is genuinely load-
   bearing (std-library import resolution for every `.ssc` program run through the JVM launcher)
   and was left alone on purpose.

2. **The `lib/` directory itself** (this spec). `NativeImageInstallRoot.discoverLib` and
   `resolveUnderLib`, and their two callers — `RunNativeV2.nativeFrontLayout` (locates the
   self-hosted compiler front, `standard/native-front`) and `NativeJvmArtifact.runCommand`
   (locates staged JVM runtime jars, `standard/jars`) — read `ssc.lib.path` this way.

**Why not unify these into one?** Because `ImportResolver`'s ROOT convention is reachable from
inside a running native-image binary too (via the legacy `--v1` interpreter lane), and its std/
scljet resolution genuinely needs a ROOT-shaped value (`<root>/std`, `<root>/scljet`) that cannot
be recovered from a bare `lib/` directory without reintroducing exactly the forced `bin/lib`
nesting this work removes from the release archive. Changing convention 1's meaning to match
convention 2 would have meant either (a) touching a dozen call sites across `ImportResolver`,
`JarCommands`, `CompilerLoader`, `JvmGen`, `SparkGen`, `PluginManifest`, and `InstallCommands`'s
launcher templates — all of which work correctly today and are exercised by every JVM-launcher
run — for no behavioral gain, since (b) `--v1` on a released native-image binary already cannot
resolve `std/…` imports today regardless (verified empirically: `ssc --v1 script-importing-std.ssc`
on the shipped archive throws `Import not found: std/…` — `ImportResolver.libPath`/`stdPath` find
nothing useful in the archive's shape either before or after this change). There was no working
behavior in that lane to preserve, and no reason to risk the *other*, load-bearing lane to "fix"
it. Two well-documented conventions, each internally consistent and each scoped to what actually
reads it, beats one convention stretched to cover two genuinely different needs.

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

### 2b. `resolveUnderLib` — accepting either shape at the READ site

`ssc.lib.path` can arrive at `nativeFrontLayout`/`NativeJvmArtifact.runCommand` in either shape,
regardless of source:

- the **`lib/`-dir shape** — from `discoverLib` above, or a user exporting `SSC_LIB_PATH` pointed
  straight at a `lib/` directory;
- the **ROOT shape** — from a JVM launcher's own `-Dssc.lib.path=<checkout-root>` (convention 1,
  §1), or a user exporting `SSC_LIB_PATH` the older way.

Rather than have the two callers guess which shape they were handed, `resolveUnderLib(root,
suffix)` tries the direct shape first (`root/<suffix>`) and falls back to the ROOT shape
(`root/bin/lib/<suffix>`) — so the same call resolves `standard/native-front` (or `standard/jars`)
correctly either way, and the checkout's own `bin/ssc`-launched runs (which still pass a ROOT-
shaped value, completely unaffected by anything in §2a) keep working unmodified.

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
matched, WITHOUT changing what either downstream consumer receives:

- if the lib-dir shape matched (archive/install-prefix), `rootAbove` walks up one level, or two
  when the level directly above is named `bin` (so a `<root>/bin/lib` shape recovers `<root>`,
  not `<root>/bin`);
- if the ROOT shape matched (checkout via JVM launcher), the value passes through completely
  unchanged — exactly what `RunNativeV2`/`NativeSourceClosure` received before this work existed.

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

## 4. What deliberately did NOT change

- `ImportResolver.scala` — zero changes. Its ROOT convention (§1) stays exactly as documented in
  its own doc comments.
- `JarCommands.scala`, `CompilerLoader.scala`, `JvmGen.scala`, `SparkGen.scala`,
  `PluginManifest.scala` — zero changes. All still read `ssc.lib.path` as a ROOT and append
  `bin/lib/…` themselves, unaffected by anything in this spec.
- `InstallCommands.scala`'s launcher templates — zero changes. `bin/ssc`/`bin/ssc-tools` still set
  `-Dssc.lib.path=<checkout-root>`, exactly as before.
- `BackendRegistry.scala`'s `ssc-plugin-host.jar` lookup — zero changes. It already searches
  `<binary-dir>/lib/ssc-plugin-host.jar` independently of `ssc.lib.path`, which already matches
  the archive's `lib/ssc-plugin-host.jar` placement; there was nothing to unify there.

## 5. Verification

- `NativeImageInstallRootTest.scala` — `discoverLib` exercises all three layouts (§2a) plus a
  precedence test (a `lib/` beside the executable wins over one one level up) and a symlink-
  resolution test; `resolveUnderLib`/`isLibShaped`/`rootAbove` are tested directly against both
  shapes.
- `tests/e2e/native-release-qualification.sh` — the archive fixture's front now lives at
  `lib/standard/native-front`; 64 compare-first cases, green.
- `tests/e2e/native-release-publication.sh` — unaffected by this change (asset names, not their
  internal layout); 40 compare-first cases, green.
- Manual, on a locally rebuilt `ssc-macos-arm64` native-image binary + freshly assembled archive
  (layout 1): `search --refresh` (network), the missing-install-root message (bare binary with no
  `lib/`), and `repl :load` all verified end-to-end, plus layouts 2 and 3 reproduced by hand-moving
  the same built binary and its `lib/` directory into each shape and re-running `--version` +
  a `std/…`-importing script.
