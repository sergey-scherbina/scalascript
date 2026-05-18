package sscplugin.crypto

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule}

/** Backend plugin that wires the org.example.crypto extern-def API to
 *  JVM / JS runtime helpers.  The interpreter variant (`CryptoInterpreterPlugin`)
 *  uses `NativeImpl` instead so it never needs to generate code.
 *
 *  Registration: META-INF/services/scalascript.backend.spi.Backend */
class CryptoBackendPlugin extends Backend:
  def id:          String = "crypto-intrinsics-jvm"
  def displayName: String = "Crypto Intrinsics (JVM/JS)"
  def spiVersion:  String = SpiVersion.Current

  def capabilities: Capabilities = Capabilities(
    features = Set.empty,
    outputs  = Set.empty,  // pure intrinsic provider — no standalone output
    options  = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )

  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = CryptoIntrinsics.table
  def acceptedSources: Set[String]                       = Set.empty

  // This plugin is an intrinsic provider only; compilation is not its job.
  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List("CryptoBackendPlugin does not compile — use jvm or js backend"))


/** Interpreter-specific plugin that uses NativeImpl for direct JVM calls. */
class CryptoInterpreterPlugin extends Backend:
  def id:          String = "crypto-intrinsics-interpreter"
  def displayName: String = "Crypto Intrinsics (Interpreter)"
  def spiVersion:  String = SpiVersion.Current

  def capabilities: Capabilities = Capabilities(
    features = Set.empty,
    outputs  = Set.empty,
    options  = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )

  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = CryptoIntrinsics.interpreterTable
  def acceptedSources: Set[String]                       = Set.empty

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List("CryptoInterpreterPlugin does not compile — use int backend"))
