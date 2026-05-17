package scalascript.ir

// Stage 1.3 placeholder — full IR types land in Stage 2.
// These exist so backend-spi traits can reference them at signature level.
// Stage 2 replaces each with a concrete case class hierarchy + upickle codecs.

case class QualifiedName(value: String):
  override def toString: String = value

case class SymbolRef(qualifiedName: QualifiedName)

/** A normalised module — flattened definition list with explicit
 *  effect IR nodes (`Perform`/`Handle`/`Resume`), resolved imports,
 *  desugared pattern matches, tail-call annotations.  Stage 2 fills in. */
trait NormalizedModule

/** A single normalised block (one fence in the source).  Used by the
 *  SourceLanguage SPI to return compiled fragments and by interactive
 *  backends to feed one block at a time. */
trait NormalizedBlock

/** Runtime value passed to / returned from interactive-backend handlers.
 *  Stage 2 defines the concrete sum type (Int, String, List, …). */
trait Value

/** An IR expression node — what `Backend.intrinsics`' `InlineCode.emit`
 *  receives for argument substitution. */
trait IrExpr

/** Per-call-site context an inline-code intrinsic uses to access the
 *  surrounding compilation state (local names, type info, …). */
trait EmitContext

/** A string of target-platform source code, opaque so we don't accidentally
 *  mix it with `String` user data.  Stage 5's intrinsic table is the first
 *  real consumer. */
opaque type TargetCode = String
object TargetCode:
  def apply(s: String): TargetCode = s
  extension (t: TargetCode) def value: String = t
