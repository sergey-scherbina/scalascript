package scalascript.frontend.javafx

import _root_.javafx.application.Application
import _root_.javafx.scene.Scene
import scalascript.frontend.AppManifest

/** Top-level JavaFX `Application` launched by `JavaFxRuntime.run`.
 *
 *  `Application.launch` needs a concrete class with a public no-arg constructor.
 *  State (module + options) is passed via `JavaFxRuntime._pendingModule/Options`
 *  set before `launch` is called. */
class JavaFxRuntimeApp extends Application:
  override def start(stage: _root_.javafx.stage.Stage): Unit =
    val module  = JavaFxRuntime._pendingModule.nn
    val options = JavaFxRuntime._pendingOptions
    val manifest = module.appManifest.getOrElse(
      AppManifest("com.example.app", "ScalaScript App", "1.0.0")
    )
    val entry = module.components.find(_.name == module.entryPoint).getOrElse(
      throw IllegalArgumentException(
        s"FrontendModule.entryPoint='${module.entryPoint}' not found among " +
          s"components [${module.components.map(_.name).mkString(", ")}]."
      )
    )
    val rootView = entry.body(())
    val state    = JavaFxRuntime.RuntimeState.from(rootView, options.fetchDispatcher)
    val root     = JavaFxRuntime.buildRoot(rootView, state)
    val scene    = Scene(root, options.width, options.height)
    stage.setTitle(options.title.getOrElse(manifest.displayName))
    stage.setScene(scene)
    stage.show()
