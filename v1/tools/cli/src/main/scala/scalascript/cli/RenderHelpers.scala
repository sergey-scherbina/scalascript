package scalascript.cli

import scalascript.ast.*

/** Static-render and code-block parse-error helpers shared across the
 *  `render` / `serve` / `build` commands.
 *
 *  Extracted verbatim from `Main.scala` (cli-main-helper-split-p2) — the
 *  call sites import `RenderHelpers.*` so they stay unqualified and behave
 *  identically. */
object RenderHelpers:

  /** Build a minimal `Request` instance for a static render.  Headers /
   *  cookies / session / files are all empty — handlers that need them
   *  can't be statically rendered without more elaborate plumbing. */
  def syntheticRequest(
      method: String,
      path:   String,
      params: Map[String, String]
  ): scalascript.interpreter.Value =
    import scalascript.interpreter.Value
    Value.InstanceV("Request", Map(
      "method"      -> Value.StringV(method),
      "path"        -> Value.StringV(path),
      "params"      -> Value.MapV(params.map((k, v) => Value.StringV(k) -> Value.StringV(v))),
      "query"       -> Value.EmptyMap,
      "headers"     -> Value.EmptyMap,
      "body"        -> Value.EmptyStr,
      "form"        -> Value.EmptyMap,
      "files"       -> Value.EmptyMap,
      "session"     -> Value.EmptyMap,
      "bearerToken" -> Value.NoneV,
      "jwtClaims"   -> Value.NoneV,
      "basicAuth"   -> Value.NoneV
    ))

  def extractResponseBody(v: scalascript.interpreter.Value): String =
    import scalascript.interpreter.Value
    v match
      case Value.InstanceV("Response", fields) =>
        fields.get("body") match
          case Some(Value.StringV(s)) => s
          case Some(other)            => Value.show(other)
          case None                   => ""
      case Value.StringV(s) => s
      case Value.UnitV      => ""
      case other            => Value.show(other)

  // ─── Structured parse-error reporting ───────────────────────────────────────
  //
  // When `Parser.parse` produces a `Content.CodeBlock` whose `tree` is empty AND
  // `parseError` is populated, the CLI emits a structured diagnostic with a
  // line/column reference and a 3-line snippet (instead of the historical opaque
  // "Failed to parse scalascript code block").  `reportCodeBlockParseErrors`
  // walks every section of a parsed `Module`, prints one diagnostic per failing
  // block to stderr, and returns `true` if any were emitted.  Callers use the
  // return value to short-circuit before running expensive codegen passes that
  // would otherwise produce a confusing downstream failure.

  /** Walk `module.sections` (and subsections) and print one structured parse
   *  diagnostic for each `Content.CodeBlock` whose `tree` is empty and that
   *  carries a `parseError`.  Returns `true` iff at least one diagnostic was
   *  emitted; the caller is then expected to bail out with a non-zero exit code. */
  def reportCodeBlockParseErrors(module: Module, file: String): Boolean =
    var any = false
    def walk(s: Section): Unit =
      s.content.foreach {
        case cb: Content.CodeBlock if cb.tree.isEmpty && cb.parseError.isDefined =>
          printCodeBlockParseError(file, cb.parseError.get)
          any = true
        case _ => ()
      }
      s.subsections.foreach(walk)
    module.sections.foreach(walk)
    any

  /** Print one structured parse-error diagnostic to stderr.  Format matches the
   *  spec in the v2.0 parse-error-positions task:
   *
   *      error: failed to parse scalascript block in <file>:<line>:<col>
   *      <message>
   *
   *        <prev line>
   *        <failing line>
   *        <space-padded ^>
   *        <next line>
   */
  def printCodeBlockParseError(file: String, err: CodeBlockParseError): Unit =
    System.err.println(s"error: failed to parse scalascript block in $file:${err.line}:${err.column}")
    System.err.println(err.message)
    if err.snippet.nonEmpty then
      System.err.println()
      System.err.println(err.snippet)
