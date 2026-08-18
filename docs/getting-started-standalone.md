# Getting Started Standalone

This guide is for using ScalaScript without cloning the monorepo.

## Install

```bash
curl -fsSL https://raw.githubusercontent.com/sergey-scherbina/scalascript/main/releases/install.sh | sh
```

That downloads the native binary for your platform from the newest GitHub release, checks it against
the `.sha256` published beside it, unpacks it into `~/.local/lib/scalascript` and links
`~/.local/bin/ssc`. Two knobs:

```bash
SSC_VERSION=0.1.1 sh releases/install.sh     # pin a release instead of the latest
PREFIX=/usr/local sh releases/install.sh     # install somewhere else
```

Published platforms are `ssc-linux-x86_64`, `ssc-macos-arm64` and `ssc-macos-x86_64`. You can also
take the archive from the [releases page](https://github.com/sergey-scherbina/scalascript/releases/latest)
by hand — unpack it WHOLE, because the binary finds its staged front by walking up from its own real
path.

### What is not available yet

This page used to open with three channels — a coursier channel at `releases.scalascript.io`, a
`scalascript/tap` Homebrew tap, and `get.scalascript.io`. None of them exist: measured 2026-08-18,
neither domain resolves, the tap 404s, and `io/scalascript/` is a 404 on Maven Central. They are not
listed here any more, and the two source files that described them
(`releases/coursier.json`, `releases/homebrew/ssc.rb`) are deleted rather than left looking ready —
one of them still carried `sha256 "REPLACE_WITH_RELEASE_SHA256"`. What each would need to come back
is written down in `BUGS.md` under `install-channels-are-fiction`.

## Create an App

```bash
ssc new hello
cd hello
sbt sscRun src/main/scalascript/Main.ssc
```

`ssc new` defaults to the `app` template. Other bundled templates:

```bash
ssc new my-lib --template lib
ssc new my-plugin --template plugin
ssc new my-dsl --template dsl
ssc new my-web --template web-app
ssc new my-wasm --template wasm-app
```

## Developer Checkout

When working on ScalaScript itself, clone the repo and use developer mode:

```bash
git clone https://github.com/sergey-scherbina/scalascript
cd scalascript
./setup.sh
./install.sh --dev
```

Plain `./install.sh` intentionally prints standalone install options instead
of building the monorepo.
