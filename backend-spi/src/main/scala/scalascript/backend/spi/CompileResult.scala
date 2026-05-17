package scalascript.backend.spi

/** Closed sum-type result of `Backend.compile`.  Callers dispatch
 *  on the variant without `instanceOf` — every shape today's CLI
 *  produces (Scala source, JS source, segmented bundle, executed
 *  stdout/stderr) plus binary outputs for future targets (WASM,
 *  native) is covered here. */
enum CompileResult:
  case TextOutput(
    code: String,
    language: String,
    sources: List[SourceArtifact] = Nil
  )
  case Segmented(segments: List[Segment])
  case BinaryOutput(
    bytes: Array[Byte],
    mime: String,
    files: List[FileArtifact] = Nil
  )
  case Executed(stdout: String, stderr: String, exit: Int)
  case Failed(diagnostics: List[Diagnostic])

/** A piece of a `Segmented` output — used by JS backends today to
 *  separate plugin-emitted JS, Scala-source blocks bound for Scala.js,
 *  and side-loaded asset payloads. */
enum Segment:
  case Code(language: String, code: String)
  case Source(language: String, source: String)
  case Asset(name: String, bytes: Array[Byte], mime: String)

case class SourceArtifact(name: String, content: String)
case class FileArtifact(name: String, bytes: Array[Byte])
