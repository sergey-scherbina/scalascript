package scalascript.backend.spi

import scalascript.ir.QualifiedName

/** Closed sum-type of diagnostics produced by core validation passes
 *  and by backends.  CLI / serve mode render these for the user;
 *  `CompileResult.Failed` is the carrier when validation refuses
 *  to invoke compilation. */
enum Diagnostic:
  case Unsupported(feature: Feature, backend: String)
  case UnknownIntrinsic(name: QualifiedName, backend: String)
  case UnknownBlockLanguage(language: String)
  case Generic(message: String, source: Option[String] = None)
