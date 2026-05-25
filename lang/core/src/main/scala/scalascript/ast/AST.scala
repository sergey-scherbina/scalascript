package scalascript.ast

/** Source position for error reporting */
case class Position(line: Int, column: Int, offset: Int):
  override def toString: String = s"$line:$column"

case class Span(start: Position, end: Position):
  override def toString: String = s"$start-$end"

// ─── Document structure ───────────────────────────────────────────

case class Module(
  manifest:   Option[Manifest],
  sections:   List[Section],
  span:       Option[Span]   = None,
  sourceText: Option[String] = None
)

case class Manifest(
  name: Option[String],
  version: Option[String],
  description: Option[String],
  dependencies: Map[String, String],
  exports: List[String],
  targets: List[String],
  routes: List[RouteDecl],
  /** Optional package prefix (dot-separated, e.g. `org.example.ui`).
   *  Every scalascript code block in the module is wrapped in nested
   *  `object` declarations matching the segments so the module's
   *  top-level names become accessible as `<pkg>.<Name>` from importers. */
  pkg: Option[List[String]],
  /** Inline translation table: locale → (key → value). Populated from the
   *  `translations:` front-matter YAML section. */
  translations: Map[String, Map[String, String]],
  /** Named JDBC connections declared in front-matter `databases:`.
   *  Consumed by `sql` fenced blocks (SPEC § 3.3.1, v1.26).  Each
   *  block resolves its connection by name (`@db=foo`) against this
   *  list; the default name is `"default"`.  Strings may contain
   *  `${env:NAME}` references resolved at module-load time.
   *  Default `Nil` so existing `Manifest` construction sites in tests
   *  / older artifacts continue to compile without an explicit value. */
  databases: List[DatabaseDecl] = Nil,
  /** Frontend framework selected via `frontend:` front-matter key.
   *  The interpreter calls `FrontendFrameworks.setBackend(name)` before
   *  running the module, equivalent to an inline `setFrontendFramework(name)`. */
  frontendFramework: Option[String] = None,
  /** Named project scripts declared in `scripts:` front-matter.
   *  Each key is a short alias (e.g. `dev`, `build`, `test`); the value is
   *  an `ssc` subcommand string (e.g. `"watch"`, `"build --target web"`).
   *  The source .ssc file is appended automatically when the script is run. */
  scripts: Map[String, String] = Map.empty,
  raw: Map[String, Any],
  /** Planned typed route clients declared in front matter.  Phase 1 keeps
   *  these as metadata so code generators can preserve endpoint method/path
   *  and request/response type names before runtime clients are implemented. */
  apiClients: List[ApiClientDecl] = Nil,
  span: Option[Span] = None
)

/** A `routes:` entry in front-matter declares a route without an inline
 *  `route(method, path) { ... }` call.  `handler` is the name of a top-
 *  level function defined elsewhere in the module that takes a `Request`
 *  and returns a `Response`. */
case class RouteDecl(method: String, path: String, handler: String, span: Option[Span] = None)

/** A typed frontend client over backend routes.  Declared in front matter as
 *  `apiClients:` / `api-clients:` during the Phase 1 metadata MVP. */
case class ApiClientDecl(name: String, endpoints: List[ApiEndpointDecl], span: Option[Span] = None)

case class ApiEndpointDecl(
  name: String,
  method: String,
  path: String,
  requestType: String,
  responseType: String,
  span: Option[Span] = None
)

/** A `databases:` entry in front-matter declares a named JDBC
 *  connection consumed by `sql` blocks.  `url` is mandatory;
 *  `user` / `password` / `driver` are optional (drivers register via
 *  the JDBC `ServiceLoader` for the bundled H2 + SQLite and any
 *  `dep:`-imported PostgreSQL / MySQL / etc.).  Values may contain
 *  `${env:NAME}` references — resolved at runtime when the runtime
 *  actually opens the connection (not at parse time). */
case class DatabaseDecl(
  name:     String,
  url:      String,
  user:     Option[String]  = None,
  password: Option[String]  = None,
  driver:   Option[String]  = None,
  span:     Option[Span]    = None
)

case class Section(
  heading: Heading,
  content: List[Content],
  subsections: List[Section],
  span: Option[Span] = None
)

case class Heading(level: Int, text: String, span: Option[Span] = None)

// ─── Content ─────────────────────────────────────────────────────

enum Content:
  /** Raw prose text extracted from a Markdown paragraph. */
  case Prose(text: String, span: Option[Span] = None)
  /** Fenced code block; `tree` is Some when scalameta parsed it successfully.
   *
   *  When parsing fails (`tree = None` for a parseable lang), `parseError`
   *  carries scalameta's positional diagnostic (line/column inside `source`
   *  plus a short snippet) so the CLI can surface a structured failure.
   *  `parseError = None` is the historical behaviour (no info available
   *  beyond "tree is empty").
   *
   *  `lineOffset` is the 0-indexed file-level line number of the FIRST
   *  code line inside the fence (the line immediately after the opening
   *  ```` ``` ```` row).  Populated by `Parser.extractSections` from the
   *  CommonMark node's source spans.  Used by the LSP server to translate
   *  block-local scalameta positions back into file-level positions for
   *  hover and definition responses.  Default `0` preserves backward
   *  compatibility with callers that construct `CodeBlock` directly. */
  case CodeBlock(
    lang: String,
    source: String,
    tree: Option[ScalaNode],
    span: Option[Span] = None,
    parseError: Option[CodeBlockParseError] = None,
    lineOffset: Int = 0,
    /** Fence-line attributes parsed from `@key=value` markers after
     *  the lang tag.  Example: ```` ```sql @db=reports @id=foo ````
     *  becomes `attrs = Map("db" -> "reports", "id" -> "foo")`.
     *  Empty by default; only the SQL fence parser uses this today
     *  (v1.26 — `@db=name` selects a named connection from front-
     *  matter `databases:`), but the slot is general so other tags
     *  can adopt the same syntax without an AST change. */
    attrs: Map[String, String] = Map.empty
  )
  /** Markdown link that acts as a module import: `[Name, …](path)`. */
  case Import(path: String, bindings: List[ImportBinding], span: Option[Span] = None)
  /** Ordered or unordered list. */
  case DataList(items: List[ListItem], ordered: Boolean, span: Option[Span] = None)

case class ImportBinding(name: String, alias: Option[String], span: Option[Span] = None)
case class ListItem(content: String, nested: List[ListItem], span: Option[Span] = None)

/** Structured diagnostic for a failed scalameta parse of a `Content.CodeBlock`.
 *
 *  `line` and `column` are 1-indexed within the block body (`Content.CodeBlock.source`)
 *  — not within the enclosing `.ssc` file, since the block source is the only
 *  thing scalameta sees.  Callers that know the block's offset within the file
 *  can adjust by adding `span.start.line - 1`.
 *
 *  `snippet` is a multi-line excerpt showing the failing line plus one line of
 *  context before and after (fewer at boundaries) with a `^` caret marker on the
 *  column of the failing token.  It's pre-formatted so callers can `println` it
 *  verbatim. */
case class CodeBlockParseError(
  message: String,
  line: Int,
  column: Int,
  snippet: String
)
