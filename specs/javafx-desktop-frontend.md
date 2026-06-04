# JavaFX Desktop Frontend (v1.47)

**Status:** Complete (2026-05-26)  
**Module:** `frontend/javafx`  
**SPI name:** `javafx`

## Overview

The `javafx` frontend target emits standalone Scala 3 source that runs as a
desktop JavaFX application via `scala-cli`. It covers the same toolkit subset
as the `swing` frontend but generates OpenJFX code with CSS-based styling.

The emitter uses `//> using dep` directives to pull in the correct
platform-specific OpenJFX artifacts at code-gen time (classifier detected from
`os.name` / `os.arch` at sbt compile time).

## Usage

```
ssc run-jvm --frontend javafx app.ssc
```

The CLI emits a `scala-cli`-runnable project and launches it. The front matter
`frontend: javafx` activates the same path automatically.

## Widget mapping

| ScalaScript View   | JavaFX node       | Notes                              |
|--------------------|-------------------|------------------------------------|
| `text`             | `Label`           | static string                      |
| `signalText`       | `Label`           | bound via `textProperty` listener  |
| `button`           | `Button`          | `setOnAction`                      |
| `textInput`        | `TextField`       | two-way binding via `textProperty` |
| `textInput` (ml)   | `TextArea`        | `multiline = true`                 |
| `textInput` (sec)  | `PasswordField`   | secure input                       |
| `toggle`           | `CheckBox`        | `selectedProperty` listener        |
| `column`           | `VBox`            | vertical stack with spacing        |
| `row`              | `HBox`            | horizontal stack with spacing      |
| `scrollView`       | `ScrollPane`      | inner `VBox` child                 |
| `divider`          | `Separator`       | `Orientation.HORIZONTAL/VERTICAL`  |
| `spacer`           | `Region`          | fixed or default 8px               |

## CSS styling

The emitter calls `node.setStyle(cssString)` with a concatenated string of
`-fx-*` properties:

| Style attribute    | JavaFX CSS property       |
|--------------------|---------------------------|
| foreground color   | `-fx-text-fill`           |
| font size          | `-fx-font-size`           |
| bold               | `-fx-font-weight: bold`   |
| italic             | `-fx-font-style: italic`  |
| background color   | `-fx-background-color`    |
| border color       | `-fx-border-color`        |
| border width       | `-fx-border-width`        |
| padding            | `-fx-padding`             |

## Signal model

The generated source includes:

- `signals: mutable.Map[String, Any]` — runtime signal table initialised from
  the static `collectSignals` pass
- `bindings: mutable.Map[String, mutable.Buffer[() => Unit]]` — refresh
  callbacks per signal
- `bindSignal(bindings, id) { ... }` — registers a refresh thunk and runs it
  immediately
- `setSignal`, `incrementSignal`, `toggleSignal` — mutate the table then call
  `refreshSignal`

## OpenJFX platform classifiers

The `javafxOs` helper in `JavaFxEmitter` reads `os.name` and `os.arch` from
system properties at code-gen time (i.e., the machine that runs `ssc`):

| OS               | Classifier     |
|------------------|----------------|
| macOS (Intel)    | `mac`          |
| macOS (aarch64)  | `mac-aarch64`  |
| Windows          | `win`          |
| Linux            | `linux`        |

The emitted `//> using dep` lines look like:

```scala
//> using dep "org.openjfx:javafx-controls:21.0.5:mac-aarch64"
//> using dep "org.openjfx:javafx-base:21.0.5:mac-aarch64"
//> using dep "org.openjfx:javafx-graphics:21.0.5:mac-aarch64"
```

## Generated source shape

```scala
//> using scala 3.8.3
//> using option -Wunused:all -deprecation -feature
//> using dep "org.openjfx:javafx-controls:21.0.5:<classifier>"
//> using dep "org.openjfx:javafx-base:21.0.5:<classifier>"
//> using dep "org.openjfx:javafx-graphics:21.0.5:<classifier>"

import javafx.application.Application
import javafx.geometry.{Insets, Orientation}
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.layout.*
import scala.collection.mutable

object Main:
  def main(args: Array[String]): Unit =
    Application.launch(classOf[App], args*)

class App extends Application:
  override def start(primaryStage: javafx.stage.Stage): Unit =
    primaryStage.setTitle("<displayName>")
    val signals  = mutable.Map[String, Any](<signal table>)
    val bindings = mutable.Map.empty[String, mutable.Buffer[() => Unit]]
    val root = VBox(16.0)
    root.setPadding(Insets(16, 16, 16, 16))
    <lowered widget tree>
    val scene = Scene(root, 640.0, 420.0)
    primaryStage.setScene(scene)
    primaryStage.show()
```

## Module layout

```
frontend/javafx/
  src/main/
    scala/scalascript/frontend/javafx/
      JavaFxFrameworkBackend.scala   ← SPI impl + emitter
    resources/META-INF/services/
      scalascript.frontend.FrontendFrameworkSpi
  src/test/
    scala/scalascript/frontend/javafx/
      JavaFxFrameworkBackendTest.scala
examples/frontend/javafx-hello/
  javafx-hello.ssc
```

## Tests

8 tests in `JavaFxFrameworkBackendTest`:

1. ServiceLoader discovers `JavaFxFrameworkBackend`
2. name + capabilities + no JS dependencies
3. web emit fails loudly
4. `emitNative` returns `None` for `Platform.Web`
5. `emitNative` produces standalone Scala/JavaFX source (header, class shape, widgets)
6. layout, signals, toggle, text input, divider, scroll lowering
7. CSS style lowering (colors, border, padding)
8. `mac-aarch64` classifier detection via system property mock
