# Getting Started Standalone

This guide is for using ScalaScript without cloning the monorepo.

## Install

Recommended release channels:

```bash
cs install ssc --channel https://releases.scalascript.io/coursier.json
```

Alternative release inputs in this repository:

```bash
brew install scalascript/tap/ssc
curl -fsSL https://get.scalascript.io | sh
```

For local testing before DNS and release hosting are live, the source files are:

- `releases/coursier.json`
- `releases/homebrew/ssc.rb`
- `releases/install.sh`

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
