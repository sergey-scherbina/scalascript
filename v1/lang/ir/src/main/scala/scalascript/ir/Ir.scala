package scalascript.ir

import upickle.default.ReadWriter

// ---------------------------------------------------------------------------
// Backend SPI v0.1 — IR (specs/backend-spi.md §5)
//
// Stage 2.1: structural types that round-trip losslessly through
// JSON / MsgPack.  Mirrors the AST closely for "near-no-op" Normalize;
// scalameta trees are NOT carried (backends re-parse from source).
//
// Stage 2.2: `derives ReadWriter` on every data type — upickle handles
// both JSON and MsgPack wire formats from the same derivation.
//
// Expression-level placeholders (Perform / Handle / Resume / ExternCall
// / TailCall / MatchTree) are reserved here so Stage 3 (effect lowering)
// and Stage 5 (intrinsic extraction) can populate them without an SPI
// version bump.
// ---------------------------------------------------------------------------

// ─── Positions ─────────────────────────────────────────────────────────────

case class Position(line: Int, column: Int, offset: Int) derives ReadWriter
case class Span(start: Position, end: Position)            derives ReadWriter

// ─── Symbol references ─────────────────────────────────────────────────────

case class QualifiedName(value: String) derives ReadWriter:
  override def toString: String = value

case class SymbolRef(qualifiedName: QualifiedName, span: Option[Span] = None) derives ReadWriter

// ─── Manifest (front-matter, structured fields only) ───────────────────────

case class RouteDecl(method: String, path: String, handler: String, span: Option[Span] = None) derives ReadWriter

case class ApiClientDecl(name: String, endpoints: List[ApiEndpointDecl], span: Option[Span] = None) derives ReadWriter

case class ApiEndpointTypeEvidenceWire(
  request:       Option[TypeEvidenceWire] = None,
  response:      Option[TypeEvidenceWire] = None,
  errors:        List[TypeEvidenceWire] = Nil,
  streamElement: Option[TypeEvidenceWire] = None
) derives ReadWriter

case class ApiEndpointDecl(
  name:         String,
  method:       String,
  path:         String,
  requestType:  String,
  responseType: String,
  stream:       Option[String] = None,
  paginated:    Boolean = false,
  span:         Option[Span] = None,
  typeEvidence: Option[ApiEndpointTypeEvidenceWire] = None
) derives ReadWriter

case class ClusterDecl(
  name:         Option[String] = None,
  nodeId:       Option[String] = None,
  role:         Option[String] = None,
  bind:         Option[String] = None,
  advertiseUrl: Option[String] = None,
  seedNodes:    List[String] = Nil,
  authToken:    Option[String] = None,
  placement:    Map[String, String] = Map.empty,
  wire:         Map[String, String] = Map.empty,
  nodes:        Option[Int] = None,
  seedDiscovery: Option[String] = None,
  leaderElection: Option[String] = None,
  authTokenFrom: Option[String] = None,
  heartbeat:    Map[String, String] = Map.empty,
  quorum:       Option[Int] = None,
  span:         Option[Span] = None
) derives ReadWriter

case class RemoteHandlerDecl(
  name:         String,
  function:     String,
  path:         Option[String] = None,
  requestType:  Option[String] = None,
  responseType: Option[String] = None,
  span:         Option[Span] = None,
  typeEvidence: Option[ApiEndpointTypeEvidenceWire] = None
) derives ReadWriter

case class RemoteSourceDecl(
  name:       String,
  source:     String,
  paramsType: Option[String] = None,
  itemType:   Option[String] = None,
  span:       Option[Span] = None
) derives ReadWriter

case class RemoteBehaviorDecl(
  name:     String,
  behavior: String,
  argsType: Option[String] = None,
  span:     Option[Span] = None
) derives ReadWriter

/** Named JDBC connection declaration carried over from the front-matter
 *  `databases:` map (SPEC § 3.3.1, v1.26).  Consumed by the JVM target's
 *  `ConnectionRegistry` to materialise a real `java.sql.Connection` on
 *  demand for `sql` fenced blocks.  `${env:NAME}` substrings are
 *  preserved verbatim through the IR — they're resolved at runtime
 *  when the connection actually opens, not at parse / Normalize time. */
case class DatabaseDecl(
  name:     String,
  url:      String,
  user:     Option[String]  = None,
  password: Option[String]  = None,
  driver:   Option[String]  = None,
  span:     Option[Span]    = None
) derives ReadWriter

case class ObjectStoreDecl(
  name:       String,
  valueType:  String,
  sync:       String = "none",
  database:   String = "default",
  store:      Option[String] = None,
  table:      Option[String] = None,
  key:        Option[String] = None,
  conflict:   String = "manual",
  span:       Option[Span] = None
) derives ReadWriter

case class GraphDecl(
  name:     String,
  model:    String = "property",
  side:     String = "server",
  backend:  String = "in-memory",
  uri:      Option[String] = None,
  user:     Option[String] = None,
  password: Option[String] = None,
  span:     Option[Span] = None
) derives ReadWriter

enum SchemaDefault derives ReadWriter:
  case NullValue
  case Bool(value: Boolean)
  case IntValue(value: Long)
  case DoubleValue(value: Double)
  case StringValue(value: String)

case class FieldSchemaDecl(
  fieldName:   String,
  storageName: Option[String] = None,
  aliases:     List[String] = Nil,
  default:     Option[SchemaDefault] = None,
  key:         Boolean = false,
  span:        Option[Span] = None
) derives ReadWriter

case class TypeSchemaDecl(
  typeName:      String,
  fields:        List[FieldSchemaDecl],
  rejectUnknown: Boolean = false,
  span:          Option[Span] = None
) derives ReadWriter

case class Manifest(
  name:         Option[String],
  version:      Option[String],
  description:  Option[String],
  dependencies: Map[String, String],
  exports:      List[String],
  targets:      List[String],
  routes:       List[RouteDecl],
  pkg:          Option[List[String]],
  apiClients:        List[ApiClientDecl] = Nil,
  databases:         List[DatabaseDecl] = Nil,
  objectStores:      List[ObjectStoreDecl] = Nil,
  graphs:            List[GraphDecl] = Nil,
  schemas:           List[TypeSchemaDecl] = Nil,
  frontendFramework: Option[String] = None,
  scripts:           Map[String, String] = Map.empty,
  cluster:           Option[ClusterDecl] = None,
  remoteHandlers:    List[RemoteHandlerDecl] = Nil,
  remoteSources:     List[RemoteSourceDecl] = Nil,
  remoteBehaviors:   List[RemoteBehaviorDecl] = Nil,
  span:              Option[Span] = None
) derives ReadWriter

// ─── Top-level module structure ────────────────────────────────────────────

case class NormalizedModule(
  manifest: Option[Manifest],
  sections: List[Section],
  span:     Option[Span] = None,
  document: Option[DocumentContent] = None
) derives ReadWriter

case class Section(
  heading:     Heading,
  content:     List[Content],
  subsections: List[Section],
  span:        Option[Span] = None
) derives ReadWriter

case class Heading(level: Int, text: String, span: Option[Span] = None) derives ReadWriter

// ─── Markdown-hosted content snapshot ───────────────────────────────────

case class DocumentContent(
  manifest:    ContentValue,
  title:       Option[String],
  description: Option[String],
  attrs:       Map[String, ContentValue],
  sections:    List[SectionContent],
  blocks:      List[ContentBlock]
) derives ReadWriter

case class SectionContent(
  id:       String,
  level:    Int,
  title:    String,
  attrs:    Map[String, ContentValue],
  blocks:   List[ContentBlock],
  children: List[SectionContent]
) derives ReadWriter

enum ContentBlock derives ReadWriter:
  case Paragraph(inlines: List[ContentInline], attrs: Map[String, ContentValue] = Map.empty)
  case BulletList(items: List[List[ContentBlock]], attrs: Map[String, ContentValue] = Map.empty)
  case OrderedList(items: List[List[ContentBlock]], start: Int, attrs: Map[String, ContentValue] = Map.empty)
  case Image(src: String, alt: String, title: Option[String] = None, attrs: Map[String, ContentValue] = Map.empty)
  case Table(
    headers:    List[List[ContentInline]],
    rows:       List[List[List[ContentInline]]],
    alignments: List[String],
    attrs:      Map[String, ContentValue] = Map.empty
  )
  case Embedded(
    lang:   String,
    source: String,
    kind:   EmbeddedKind,
    data:   Option[ContentValue] = None,
    attrs:  Map[String, ContentValue] = Map.empty
  )

enum EmbeddedKind derives ReadWriter:
  case StructuredData
  case Executable
  case StringBlock
  case Opaque

enum ContentInline derives ReadWriter:
  case Text(value: String)
  case Emphasis(children: List[ContentInline])
  case Strong(children: List[ContentInline])
  case Code(value: String)
  case Link(label: List[ContentInline], href: String, title: Option[String] = None)
  case Expr(source: String)

enum ContentValue derives ReadWriter:
  case Str(value: String)
  case Bool(value: Boolean)
  case Num(value: Double)
  case ListV(values: List[ContentValue])
  case MapV(values: Map[String, ContentValue])
  case NullV

// ─── SQL execution side (v1.30) ──────────────────────────────────────────────

/** Whether a `sql` block runs in the server bundle or the browser bundle.
 *  Only meaningful in `frontend:` modules; ignored by pure JVM / pure JS targets. */
enum SqlSide derives ReadWriter:
  case Server  // default — runs in the server / JVM route handler
  case Client  // runs in the browser bundle (JS-supported URL schemes only)

// ─── Content (per-section payload) ─────────────────────────────────────────

enum Content derives ReadWriter:
  /** Raw prose extracted from a Markdown paragraph. */
  case Prose(text: String, span: Option[Span] = None)
  /** A `scalascript` / `ssc` fence — host embedded language.  Stored as
   *  source; backends re-parse via the scala-source plugin (Stage 9).
   *  Carries the parsed/normalised body once Stage 3 lowering lands. */
  case CodeBlock(source: String, body: List[IrExpr] = Nil, span: Option[Span] = None)
  /** A foreign-language fence (`html`, `css`, `scala`, future `wat`, …).
   *  Compiled by a SourceLanguage plugin (Stage 9).
   *  `evidence` carries optional structured type metadata produced by the
   *  plugin at compile time; consumers that only need the raw source ignore it. */
  case EmbeddedBlock(
    language: String,
    source:   String,
    span:     Option[Span]                    = None,
    evidence: Option[GraphQLBlockEvidenceWire] = None
  )
  /** A `sql` fence with the bind-parameter rewriter already applied
   *  (SPEC § 3.3.1, v1.26).  `source` is the original SQL with `${expr}`
   *  / `$$` markers preserved verbatim — this is the round-trip surface
   *  for `Denormalize`.  `binds` is the ordered list of bind-expression
   *  source texts extracted by `SqlBindRewriter` (one entry per `${...}`
   *  occurrence in `source`); the execution layer feeds them as JDBC
   *  positional parameters to a PreparedStatement built from the same
   *  source with each `${expr}` collapsed to a single `?`.
   *  `dbName` selects a named connection from the module's front-matter
   *  `databases:` map — Phase 5 wires the front-matter end; until then
   *  the parser sets it to `None` (= "default" connection). */
  case SqlBlock(
    source: String,
    binds:  List[String]    = Nil,
    dbName: Option[String]  = None,
    span:   Option[Span]    = None,
    /** v1.30 — execution side in full-stack (frontend:) modules.
     *  `Server` (default): runs in the server/JVM bundle.
     *  `Client`: runs in the browser bundle; only JS-supported URL
     *  schemes are valid (`sqlite:`, `sqlite-opfs:`, `duckdb:`). */
    side:   SqlSide         = SqlSide.Server
  )
  /** A `transaction` fence — multiple `;`-separated SQL statements
   *  executed atomically via a single JDBC transaction.  `sources` holds
   *  each statement with `${expr}` / `$$` markers preserved (round-trip
   *  surface for `Denormalize`); `binds(i)` is the ordered bind-expression
   *  list for `sources(i)`.  `dbName` selects the registry connection
   *  (defaults to `"default"` when absent). */
  case TransactionBlock(
    sources: List[String],
    binds:   List[List[String]] = Nil,
    dbName:  Option[String]     = None,
    span:    Option[Span]       = None
  )
  /** Markdown link that acts as a module import: `[Name, …](path)`. */
  case Import(path: String, bindings: List[ImportBinding], span: Option[Span] = None)
  /** Ordered or unordered list. */
  case DataList(items: List[ListItem], ordered: Boolean, span: Option[Span] = None)

/** Alias used by `Session.feed` in the SPI — feeding "one block" to an
 *  interactive backend means handing it one `Content` node. */
type NormalizedBlock = Content

case class ImportBinding(name: String, alias: Option[String], span: Option[Span] = None) derives ReadWriter
case class ListItem(content: String, nested: List[ListItem], span: Option[Span] = None)  derives ReadWriter

// ─── Expression-level IR ────────────────────────────────────────────────

/** Sealed sum of IR expressions.  Stage 5+/A.1 concretises the core
 *  ordinary-expression cases (Lit, VarRef, Call) so intrinsic
 *  dispatch + `Normalize` can produce real IR.  Stage 3+ effect
 *  primitives (Perform / Handle / Resume), Stage 5+/A.1 extern call
 *  sites (ExternCall), Stage 3 match desugaring (MatchTree), and
 *  Stage 3 tail-call annotations all coexist as IrExpr variants. */
sealed trait IrExpr derives ReadWriter

// ── Ordinary expressions ───────────────────────────────────────────────

/** A primitive literal. */
case class Lit(value: LitValue) extends IrExpr derives ReadWriter

/** A variable reference by lexical name.  Resolved against the
 *  module scope or enclosing frame. */
case class VarRef(name: String) extends IrExpr derives ReadWriter

/** Ordinary function / method call site, target resolved to an
 *  absolute symbol reference. */
case class Call(target: SymbolRef, args: List[IrExpr]) extends IrExpr derives ReadWriter

/** Generic application — function position is itself an expression
 *  (e.g. `f(1)`, `obj.method(x)`, `(lambda)(arg)`).  Used by
 *  `Normalize` when translating scalameta `Term.Apply` whose target
 *  has not yet been resolved to a `SymbolRef`.  The Linker treats it
 *  as opaque (recurses into `fn` and `args` for `VarRef` rewriting). */
case class Apply(fn: IrExpr, args: List[IrExpr]) extends IrExpr derives ReadWriter

/** Member selection `qual.name` — produced by scalameta `Term.Select`.
 *  Used to model `Console.println`, `obj.field`, package paths, etc.
 *  The Linker recurses into `qual` for `VarRef` rewriting. */
case class Select(qual: IrExpr, name: String) extends IrExpr derives ReadWriter

/** Anonymous function `(p1, …) => body`.  Parameter names only (no
 *  types) since the IR doesn't yet carry a type system at this layer. */
case class Lambda(params: List[String], body: IrExpr) extends IrExpr derives ReadWriter

/** Conditional `if (cond) thenp else elsep`.  `elsep` is `None` for
 *  the bare `if … then …` form. */
case class If(cond: IrExpr, thenp: IrExpr, elsep: Option[IrExpr]) extends IrExpr derives ReadWriter

/** Sequence of statements / expressions — the tail value is the
 *  block's result.  Used for `Term.Block` and as the root container
 *  for a code block's body. */
case class Block(stmts: List[IrExpr]) extends IrExpr derives ReadWriter

/** Catch-all for scalameta nodes that the `AstToIr` translator does
 *  not yet model (definitions, complex patterns, advanced types, …).
 *  Carries the original source snippet so downstream consumers can
 *  reconstruct or report the node; the Linker treats it as opaque. */
case class Unsupported(syntax: String) extends IrExpr derives ReadWriter

/** A primitive literal payload — concrete variant of `Lit`.
 *  Numeric kinds split so round-trip preserves type. */
enum LitValue derives ReadWriter:
  case IntL(value:    Long)
  case DoubleL(value: Double)
  case StringL(value: String)
  case BoolL(value:   Boolean)
  case UnitL

// ── Effect primitives (Stage 3+) ──────────────────────────────────────

case class Perform(effect: QualifiedName, op: String, args: List[IrExpr]) extends IrExpr derives ReadWriter
case class Handle(body: IrExpr, cases: List[HandleCase], ret: HandleReturn) extends IrExpr derives ReadWriter
case class HandleCase(effect: QualifiedName, op: String, params: List[String], body: IrExpr) derives ReadWriter
case class HandleReturn(param: String, body: IrExpr) derives ReadWriter
case class Resume(k: SymbolRef, value: IrExpr) extends IrExpr derives ReadWriter

case class TailCall(target: SymbolRef, args: List[IrExpr]) extends IrExpr derives ReadWriter

/** `extern def` call site — the call lowers to this when its target
 *  is a symbol declared `extern` (spec §8).  Each backend's
 *  `Backend.intrinsics` map decides how the call materialises:
 *  inline target source, runtime helper, or out-of-process callback. */
case class ExternCall(name: QualifiedName, args: List[IrExpr], span: Option[Span] = None) extends IrExpr derives ReadWriter

/** Restricted quoted-macro call site.
 *
 *  Phase arch-meta-v2-p4 keeps macros target-independent by representing
 *  them at the ScalaScript IR/link layer instead of as Scala 3 compiler
 *  trees. Backends should never execute this node directly; `Linker`
 *  expands it before backend code generation. */
case class MacroImpl(name: QualifiedName, args: List[IrExpr], resultType: Option[String] = None, span: Option[Span] = None) extends IrExpr derives ReadWriter

/** Compiled pattern-match decision tree.  Stage 3's match desugaring
 *  produces this so each backend doesn't re-implement decision-tree
 *  compilation. */
case class MatchTree(scrutinee: IrExpr, root: DecisionNode) extends IrExpr derives ReadWriter

sealed trait DecisionNode derives ReadWriter
case class Switch(cases: List[(Pattern, DecisionNode)], default: Option[DecisionNode]) extends DecisionNode derives ReadWriter
case class Leaf(action: IrExpr) extends DecisionNode derives ReadWriter

sealed trait Pattern derives ReadWriter
case class PatLit(value: PatternLiteral) extends Pattern derives ReadWriter
case class PatVar(name: String) extends Pattern          derives ReadWriter
case class PatCtor(ctor: QualifiedName, fields: List[Pattern]) extends Pattern derives ReadWriter
case object PatWildcard extends Pattern

/** A primitive literal usable in a pattern. */
enum PatternLiteral derives ReadWriter:
  case IntLit(value: Long)
  case StrLit(value: String)
  case BoolLit(value: Boolean)
  case NullLit

// ─── v2.0 Separate-compilation artifact format ─────────────────────────────
//
// Each `.ssc` file compiles into two artifacts:
//   .scim  — module interface (types, extern sigs, no bodies)
//   .scir  — module IR body (NormalizedModule JSON, with ABI envelope)
//
// Both share the ArtifactEnvelope wrapper which carries a magic number and
// compiler version string so mismatched artifacts are detected at read time,
// not silently miscompiled.

/** ABI version guard.  Bump `current` on any incompatible change to the
 *  `.scim` / `.scir` wire format.  Readers reject artifacts whose version
 *  does not equal `current`. */
object ArtifactVersion:
  /** Magic bytes embedded at the start of every artifact envelope. */
  val magic: String = "SSCART"
  /** Current ABI version.  Follows `major.minor` — minor is backward-
   *  compatible additions; major is breaking changes. */
  val current: String = "2.0"
  /** Human-readable description for error messages. */
  val description: String = s"ScalaScript artifact ABI $current"

/** Additive wire form for typer evidence stored in `.scim`.
 *
 *  Kept in `lang/ir` as plain strings so the artifact model does not depend
 *  on `lang/core`'s compiler-only `SType` / `TypeEvidence` classes.
 *  `tpe` is the rendered `SType.show` value, and `kind` is one of the
 *  `TypeEvidenceKind` case names (`Declared`, `Inferred`, `Unknown`, ...).
 */
case class TypeEvidenceWire(
  tpe: String,
  kind: String,
  reason: Option[String] = None
) derives ReadWriter

/** Additive wire form for GraphQL field-level type evidence.
 *  `kind` is `"Declared"` when the field's type name is a known SDL scalar
 *  or defined elsewhere in the same block, `"Unknown"` otherwise. */
case class GraphQLFieldEvidenceWire(
  name:     String,
  typeName: String,
  kind:     String
) derives ReadWriter

/** Type-level summary: object / interface / input / union / enum / scalar.
 *  `kind` is one of those six literal strings. */
case class GraphQLTypeEvidenceWire(
  name:   String,
  kind:   String,
  fields: List[GraphQLFieldEvidenceWire] = Nil
) derives ReadWriter

/** Block-level evidence for a single `graphql` fenced block.
 *  Attached as `Content.EmbeddedBlock.evidence` when the SDL parses
 *  successfully.  Absent (`None`) for invalid SDL or legacy artifacts. */
case class GraphQLBlockEvidenceWire(
  types: List[GraphQLTypeEvidenceWire] = Nil
) derives ReadWriter

/** Exported symbol entry in a `.scim` interface.
 *
 *  `kind` is one of: `val`, `def`, `object`, `type`, `given`, `extern`.
 *  `tpe` is a best-effort human-readable type string (e.g. `"Int => String"`);
 *  the typer currently produces `Any` for unsupported names.
 *  `evidence` is optional structured metadata explaining where that rendered
 *  type came from. It is additive; consumers keep using `tpe` until they opt
 *  into stricter evidence-aware checks. */
case class ExportedSymbol(
  name:     String,
  fqn:      String,           // fully-qualified mangled name: pkg segments + name
  kind:     String,           // val | def | object | type | given | extern
  tpe:      String = "Any",   // type annotation (best-effort)
  span:     Option[Span] = None,
  /** Members nested inside this symbol when it itself is a sub-namespace
   *  (typically `kind == "object"`).  Empty for leaf symbols.
   *
   *  Used by the typer's strict mode to validate deep Select chains like
   *  `pkg.sub.member` against the consumer's `.scim` interfaces without
   *  re-parsing source.  Default empty preserves backward compatibility
   *  with `.scim` artifacts emitted before this field existed.
   *
   *  As of v2.0 Stage 5.6+ `InterfaceExtractor` populates this for
   *  top-level `Defn.Object` exports by walking the object's body stats
   *  (up to a small depth — see `InterfaceExtractor.MaxNestedDepth`).
   *  Member types are best-effort `Any` for now (the typer does not yet
   *  descend into objects); the names, FQNs, and kinds are exact, which
   *  is sufficient for strict-mode deep-Select validation.
   */
  nested:   List[ExportedSymbol] = Nil,
  /** File-level 0-indexed line of the symbol's definition (the `def` /
   *  `val` / `class` / `given` keyword) in the originating `.ssc` source.
   *
   *  Populated by `InterfaceExtractor` from the scalameta tree's
   *  `pos.startLine`, then translated to file coordinates by adding the
   *  enclosing `Content.CodeBlock.lineOffset`.  Consumed by the LSP
   *  server's `textDocument/definition` handler so cross-module
   *  go-to-definition jumps to the actual definition line, not the
   *  document's first line.
   *
   *  Default `0` preserves backward compatibility with `.scim` artifacts
   *  emitted before this field existed; in that case the LSP falls back
   *  to the documented MVP behaviour of pointing at (0, 0).  v2.0 Phase 3+.
   */
  definitionLine:   Int = 0,
  /** File-level 0-indexed column of the symbol's definition name.  Pair
   *  with [[definitionLine]] — same lifecycle and backward-compat policy. */
  definitionColumn: Int = 0,
  /** arch-lib-p2 — `@internal` annotation on the definition.
   *  When `true`, this symbol must not be imported by a consumer module
   *  whose package (`manifest.name`) differs from the exporting library's
   *  package.  Default `false` preserves backward compatibility with
   *  `.scim` artifacts emitted before this field existed. */
  isInternal: Boolean = false,
  /** arch-meta-v2-p3 — `inline def` marker.
   *  When `true` this symbol was declared `inline def` in its source module.
   *  Consumers may use this to decide whether to inline the call site.
   *
   *  Default `false` preserves backward compatibility with `.scim` artifacts
   *  emitted before this field existed. */
  isInline: Boolean = false,
  /** arch-meta-v2-p3 — Parameter names of an `inline def`, in declaration order.
   *  Empty for non-inline symbols, non-def symbols, or multi-clause defs.
   *  Pair with [[inlineBodySource]] for call-site expansion.
   *
   *  Default `Nil` preserves backward compatibility. */
  inlineParamNames: List[String] = Nil,
  /** arch-meta-v2-p3 — Source text of the `inline def` body expression.
   *  `None` for non-inline symbols or when the body is not expressible as
   *  a single term (e.g. `def f(x: Int): Unit = { side(); other() }`).
   *
   *  Used by `Linker.expandInlineCalls` to substitute the body at the
   *  call site.  Default `None` preserves backward compatibility. */
  inlineBodySource: Option[String] = None,
  /** arch-meta-v2-p4 — Restricted quoted-macro implementation reference.
   *  Present only for inline defs whose body is a macro splice of the form
   *  `${ impl('arg1, 'arg2) }`. */
  macroImpl: Option[MacroImplRef] = None,
  /** arch-meta-v2-p4 — Marks a helper def returning `Expr[A]` as a macro
   *  implementation candidate. This is metadata only; execution happens
   *  through the inline entrypoint's [[macroImpl]] field. */
  isMacroImpl: Boolean = false,
  /** arch-meta-v2-p4 — Quoted body source of a macro implementation when it
   *  is a direct quoted expression (`'{ ... }`). */
  macroQuotedBodySource: Option[String] = None,
  /** type-evidence-interface-p2 — optional structured metadata for `tpe`.
   *  Default `None` preserves backward compatibility with `.scim` artifacts
   *  emitted before this field existed. */
  evidence: Option[TypeEvidenceWire] = None
) derives ReadWriter

/** Typeclass instance entry in a `.scim` interface.
 *  Recorded so consumers can resolve `given` instances without re-parsing
 *  the source.  Stage 4 will use these during interface-based type checking. */
case class InstanceDecl(
  typeclass:  String,         // e.g. "Eq"
  typeParam:  String,         // e.g. "Int"
  witnessName: String,        // name of the `given` val / def
  fqn:        String          // fully-qualified mangled name
) derives ReadWriter

/** Capability declared by this module (e.g. `Http`, `WebSocket`).
 *  Consumers can check whether a dependency requires a capability their
 *  target backend does not support, without loading the full `.scir`. */
case class CapabilityDecl(
  name: String                // e.g. "Http", "WebSocket", "FileSystem"
) derives ReadWriter

/** Restricted quoted-macro metadata carried in `.scim`.
 *
 *  `inline def foo(x: Int): Int = ${ fooImpl('x) }` records
 *  `implName = "fooImpl"` and `quotedParams = List("x")`. When the
 *  implementation's quoted body is known, `expansionBodySource` stores
 *  the quoted expression source (for example `'{ $x + 1 }`) so the linker
 *  can expand cross-module call sites without loading Scala compiler
 *  internals. */
case class MacroImplRef(
  implName: String,
  quotedParams: List[String] = Nil,
  resultType: Option[String] = None,
  expansionBodySource: Option[String] = None
) derives ReadWriter

/** Module interface artifact — written as `.scim` JSON.
 *
 *  Contains only the information a consumer needs to type-check against
 *  this module without re-parsing its source.  No expression bodies.
 *
 *  The envelope fields (`magic`, `abiVersion`) are checked before the
 *  payload is deserialised — a mismatched `abiVersion` produces a clear
 *  error pointing to `ssc emit-interface`. */
case class ModuleInterface(
  magic:        String,                  // must equal ArtifactVersion.magic
  abiVersion:   String,                  // must equal ArtifactVersion.current
  pkg:          List[String],            // package segments from front-matter
  moduleName:   Option[String],          // from manifest `name:` field
  moduleVersion: Option[String],         // from manifest `version:` field
  sourceHash:   String,                  // SHA-256 hex of the source `.ssc` bytes
  exports:      List[ExportedSymbol],
  instances:    List[InstanceDecl]    = Nil,
  capabilities: List[CapabilityDecl]  = Nil,
  externDefs:   List[ExportedSymbol]  = Nil,  // extern def declarations only
  dependencies: Map[String, String]   = Map.empty,  // dep alias → resolved path/id
  /** Per-section cumulative SHA-256 hashes for selective re-emit.
   *
   *  Key format: `"<heading-text>:<index>"` where `index` is the 0-based
   *  position of the section in `module.sections` (titles can repeat, the
   *  index disambiguates).  Value is a 64-char hex SHA-256 digest computed
   *  cumulatively: each section's hash digests its own raw source bytes
   *  joined with every prior section's hash via `\n` (Option A — shared-
   *  module-scope safety: a change in section N cascades to N+1, N+2, …).
   *
   *  Empty default preserves backward compatibility with v2.0 `.scim`
   *  artifacts emitted before this field existed.  A consumer that sees
   *  `sectionHashes.isEmpty` falls back to the full-module-SHA path
   *  (treats every section as stale).  v2.0 Phase 3 — section-level
   *  incremental cache, opt-in via `--section-cache`.
   */
  sectionHashes: Map[String, String]   = Map.empty,
  /** Per-section NON-cumulative own-source SHA-256.  See
   *  `InterfaceExtractor.computeSectionOwnHashes`.  Used by Option B to
   *  detect a section's own-body change independent of the cumulative
   *  chain.  Empty default = legacy artifact. */
  sectionOwnHashes: Map[String, String] = Map.empty,
  /** Per-section PUBLIC-INTERFACE SHA-256 hashes (Option B).
   *
   *  For each section, hash the canonical JSON of the section's exported
   *  defs / vals / classes / objects / traits / enums (names + signatures
   *  via `typeToString`).  Body-only edits don't perturb this hash.
   *
   *  Used by `ModuleGraph.staleSectionsInterfaceBased` to allow
   *  body-only edits of section N to keep sections N+1, N+2, … cached.
   *
   *  Empty default = legacy artifact (no interface hashes recorded).
   *  When the consumer requests `--section-cache=interface` against an
   *  artifact with empty `sectionInterfaceHashes`, it treats every
   *  section as stale (safe fallback).  v2.0 Phase 5.
   */
  sectionInterfaceHashes: Map[String, String] = Map.empty,
  /** Natural dotted FQN → mangled JVM FQN, the table a Scala consumer
   *  needs to bridge `_ssc_runtime`-mangled symbols back to their
   *  source-form names.
   *
   *  Example: `"std.eq.Eq" -> "_ssc_runtime.std_eq_Eq"`.
   *
   *  Populated by `InterfaceExtractor`, mirrors what `Linker.mangle`
   *  produces.  Includes nested members (depth-3 walk via
   *  `ExportedSymbol.nested`).  Respects `exports:` front-matter —
   *  private helpers don't appear.
   *
   *  Empty default = legacy artifact (no facade table); the interop
   *  library falls back to computing the table on the fly from
   *  `exports` + `Linker.mangle`.
   *
   *  Tier 1 of the Scala ↔ ScalaScript interop spec
   *  (`specs/scala-interop.md`).  v2.0 — additive optional field, no
   *  ABI version bump. */
  scalaFacade: Map[String, String] = Map.empty,
  /** Canonical UTF-8 JSON text for the target-neutral v3 API descriptor.
   *
   *  This legacy artifact layer is an opaque additive carrier only: it does
   *  not parse, derive, validate, or reinterpret descriptor semantics.  A
   *  structured pre-body producer may populate the field; legacy artifacts
   *  and readers that omit it observe `None` without an ABI-version bump. */
  apiDescriptorV3: Option[String] = None
) derives ReadWriter

/** Module IR artifact envelope — wraps a `NormalizedModule` as `.scir` JSON.
 *
 *  The envelope allows consumers to validate the ABI version and source hash
 *  before deserialising the (potentially large) body payload.
 *
 *  `body` is the `NormalizedModule` serialised to a JSON string and embedded
 *  as a string field rather than inlined, so the envelope can be read cheaply
 *  without parsing the full body tree. */
case class ModuleIrArtifact(
  magic:        String,        // must equal ArtifactVersion.magic
  abiVersion:   String,        // must equal ArtifactVersion.current
  pkg:          List[String],  // package segments
  moduleName:   Option[String],
  sourceHash:   String,        // SHA-256 hex of the source bytes
  body:         String,        // upickle.default.write(NormalizedModule) — JSON string
  /** Per-section cumulative SHA-256 hashes.  See `ModuleInterface.sectionHashes`
   *  for semantics.  Default `Map.empty` preserves backward compatibility
   *  with `.scir` artifacts emitted before this field existed. */
  sectionHashes: Map[String, String] = Map.empty,
  /** Per-section interface SHA-256 hashes.  See `ModuleInterface.sectionInterfaceHashes`. */
  sectionInterfaceHashes: Map[String, String] = Map.empty
) derives ReadWriter

/** JVM-backend cached artifact — written as `.scjvm` JSON.
 *
 *  Carries the JVM backend's emitted Scala 3 source string for a *single*
 *  module (no merged transitive-dep code).  Consumers (`ssc link --backend
 *  jvm`) concatenate the `scalaSource` of every `.scjvm` in dependency order
 *  and feed the combined source to scala-cli / scalac — bypassing the per-
 *  link re-codegen of unchanged modules.
 *
 *  MVP: textual concat.  Real bytecode-level caching is deferred (see
 *  `MILESTONES.md` v2.0 § "Considered alternatives").  The `.scjvm` magic +
 *  ABI envelope follows the same versioning discipline as `.scim` / `.scir`
 *  so mismatched artifacts are detected at read time, not silently spliced
 *  into a broken combined source.
 *
 *  v2.0 — JVM incremental codegen cache. */
case class ModuleJvmArtifact(
  magic:        String,        // must equal ArtifactVersion.magic
  abiVersion:   String,        // must equal ArtifactVersion.current
  moduleId:     String,        // source path or FQN prefix (display id; not load-bearing)
  pkg:          List[String],  // package segments from front-matter
  moduleName:   Option[String],
  sourceHash:   String,        // SHA-256 hex of the source bytes
  scalaSource:  String,        // JvmGen.generate(module) output — Scala 3 source for THIS module
  imports:      List[String],  // FQNs of foreign-module symbols this artifact references
  /** JVM backend codegen cache key.
   *
   *  `sourceHash` only tracks the `.ssc` bytes.  This field also invalidates
   *  source-fresh `.scjvm` files emitted by an older JVM backend after the
   *  generated Scala semantics change.  Empty default preserves backward
   *  compatibility with legacy artifacts; `ModuleGraph.isJvmStale` treats
   *  legacy/old-version values as stale so they are regenerated. */
  codegenVersion: String = "",
  /** Optional base64-encoded ZIP of `.class` files compiled from `scalaSource`.
   *
   *  Populated by `ssc compile-jvm --bytecode` (which drives scala-cli on the
   *  emitted Scala source and packs the resulting `.class` outputs).  Consumed
   *  by `ssc link --backend jvm --bytecode` to produce a real JAR without
   *  re-running scala-cli at link time — Phase 2 of v2.0 separate compilation.
   *
   *  When `None`, the artifact is source-only (the pre-Phase-2 behaviour) and
   *  `link --backend jvm` falls back to textual source concatenation.
   *
   *  Default `None` preserves backward compatibility with `.scjvm` artifacts
   *  emitted before this field existed; upickle's `derives ReadWriter` picks
   *  the default up automatically on read. */
  classBundle:  Option[String] = None,
  /** Capability set required by this module's emitted code.
   *
   *  Populated by `ssc compile-jvm --bytecode` from `JvmGen.detectCapabilities`.
   *  The linker / runtime-staleness check unions every module's
   *  `capabilities` into the runtime's capability set so the shared
   *  `_runtime.scjvm-runtime` is regenerated whenever a module needs a new
   *  capability that the existing runtime doesn't carry.
   *
   *  Encoded as the stable strings emitted by `JvmGen.Capability.encode`
   *  (e.g. `"effects"`, `"serve"`, `"reactive"`, `"mcp"`, `"dataset"`).
   *
   *  Default `Nil` preserves backward compatibility with `.scjvm` artifacts
   *  emitted before this field existed.  When a `.scjvm` carries an empty
   *  capability list AND a non-empty `classBundle`, the linker treats it as
   *  a pre-split-runtime artifact (the classBundle ships the full runtime
   *  preamble) and skips runtime-bundle injection at link time. */
  capabilities: List[String] = Nil,
  /** Per-section cumulative SHA-256 hashes.  See `ModuleInterface.sectionHashes`
   *  for semantics.  Default `Map.empty` preserves backward compatibility
   *  with `.scjvm` artifacts emitted before this field existed. */
  sectionHashes: Map[String, String] = Map.empty,
  /** Per-section interface SHA-256 hashes.  See `ModuleInterface.sectionInterfaceHashes`. */
  sectionInterfaceHashes: Map[String, String] = Map.empty,
  /** Generated-Scala-line → original-`.ssc`-line map for `--source-map`
   *  (Option A: JSR-45 SMAP injection via ASM).
   *
   *  Keys are emitted-Scala line numbers (1-based, as the bytecode's
   *  `LineNumberTable` sees them) stringified — upickle handles
   *  `Map[String, _]` more cleanly than `Map[Int, _]` (where it would
   *  fall back to a 2-element array encoding that's awkward to read).
   *
   *  Values are the corresponding original `.ssc` line numbers (1-based).
   *
   *  Default `Map.empty` preserves backward compatibility with `.scjvm`
   *  artifacts emitted before this field existed; the linker treats an
   *  empty map as "no SMAP injection requested" and skips ASM rewrite.
   *
   *  v2.0 Phase 4 (Option A) — JSR-45 SMAP / `SourceDebugExtension`. */
  lineMap: Map[String, Int] = Map.empty
) derives ReadWriter

/** Shared JVM runtime artifact — written as `.scjvm-runtime` JSON.
 *
 *  Carries the once-per-session compiled runtime preamble (the
 *  `package _ssc_runtime` block emitted by `JvmGen.generateRuntime`).
 *  All modules in an artifact dir reference this single bundle at link
 *  time so the ~180 KB preamble isn't duplicated into every `.scjvm`.
 *
 *  `capabilities` is the union of capabilities across all modules in
 *  the dir at the time of generation (e.g. `Set("effects", "serve")`).
 *  When the union changes (a new module adds a capability), the runtime
 *  is regenerated; when only the union shrinks (a module is removed),
 *  the existing runtime stays valid.
 *
 *  `sourceHash` is the SHA-256 of the emitted runtime Scala source —
 *  used by `compile-jvm --bytecode` to short-circuit recompilation when
 *  the capability set is unchanged.
 *
 *  v2.0 Phase 2 — split-runtime shared classBundle. */
case class ModuleJvmRuntimeArtifact(
  magic:        String,         // must equal ArtifactVersion.magic
  abiVersion:   String,         // must equal ArtifactVersion.current
  capabilities: List[String],   // sorted, encoded capability names
  sourceHash:   String,         // SHA-256 hex of the runtime Scala source
  classBundle:  String          // base64 ZIP of .class + .tasty files (always present)
) derives ReadWriter

/** JS-backend cached artifact — written as `.scjs` JSON.
 *
 *  Carries the JS backend's emitted JavaScript source string for a *single*
 *  module (no merged transitive-dep code).  Consumers (`ssc link --backend
 *  js`) concatenate the `jsSource` of every `.scjs` in dependency order and
 *  feed the combined source to node / a browser — bypassing the per-link
 *  re-codegen of unchanged modules.
 *
 *  v2.0 MVP shipped the full runtime preamble (~80 KB) inside every
 *  `.scjs`'s `jsSource`.  v2.0 Phase 2 splits the preamble into a
 *  separate `.scjs-runtime` artifact (`ModuleJsRuntimeArtifact`) compiled
 *  once per artifact dir; user `.scjs` files carry user code only and
 *  list their required `capabilities`.  The link path detects which
 *  shape it's looking at by whether a `_runtime.scjs-runtime` exists:
 *  when one does, the modules are user-only and the runtime is
 *  concatenated once at the head of `out.js`; when none exists, the
 *  legacy longest-common-prefix dedup runs over preamble-bearing
 *  `.scjs` files (full backward compat with v2.0 MVP artifacts).
 *
 *  The `.scjs` magic + ABI envelope follows the same versioning discipline
 *  as `.scim` / `.scir` / `.scjvm` so mismatched artifacts are detected at
 *  read time, not silently spliced into a broken combined source.
 *
 *  v2.0 — JS incremental codegen cache. */
case class ModuleJsArtifact(
  magic:        String,        // must equal ArtifactVersion.magic
  abiVersion:   String,        // must equal ArtifactVersion.current
  moduleId:     String,        // source path or FQN prefix (display id; not load-bearing)
  pkg:          List[String],  // package segments from front-matter
  moduleName:   Option[String],
  sourceHash:   String,        // SHA-256 hex of the source bytes
  jsSource:     String,        // JsGen.generate(module) output — JS source for THIS module
  imports:      List[String],  // FQNs of foreign-module symbols this artifact references
  /** Capability set required by this module's emitted JS.
   *
   *  Populated by `ssc compile-js` from `JsGen.detectCapabilities`.  The
   *  linker / runtime-staleness check unions every module's
   *  `capabilities` into the runtime's capability set so the shared
   *  `_runtime.scjs-runtime` is regenerated whenever a module needs a new
   *  capability the existing runtime doesn't carry.
   *
   *  Encoded as the stable strings emitted by `JsGen.Capability.encode`
   *  (`"core"`, `"async"`, `"effects"`, `"mcp"`, `"dataset"`).
   *
   *  Default `Nil` preserves backward compatibility with v2.0 MVP `.scjs`
   *  artifacts emitted before this field existed.  When a `.scjs` carries
   *  an empty capability list AND there's no companion `.scjs-runtime`
   *  in the artifact dir, the linker treats it as a legacy artifact
   *  (the `jsSource` ships the full preamble) and skips runtime-bundle
   *  injection at link time. */
  capabilities: List[String] = Nil,
  /** Per-section cumulative SHA-256 hashes.  See `ModuleInterface.sectionHashes`
   *  for semantics.  Default `Map.empty` preserves backward compatibility
   *  with `.scjs` artifacts emitted before this field existed. */
  sectionHashes: Map[String, String] = Map.empty,
  /** Per-section interface SHA-256 hashes.  See `ModuleInterface.sectionInterfaceHashes`. */
  sectionInterfaceHashes: Map[String, String] = Map.empty,
  /** JS backend codegen cache key — mirrors ModuleJvmArtifact.codegenVersion.
   *  `sourceHash` only tracks the `.ssc` bytes; this also invalidates
   *  source-fresh `.scjs` files emitted by an older JS backend. Empty default
   *  keeps legacy artifacts readable; `ModuleGraph.isJsStale` treats
   *  legacy/old-version values as stale so they are regenerated. */
  codegenVersion: String = ""
) derives ReadWriter

/** Shared JS runtime artifact — written as `.scjs-runtime` JSON.
 *
 *  Carries the once-per-artifact-dir generated JS runtime preamble (the
 *  output of `JsGen.generateRuntime(capabilities)`).  All modules in an
 *  artifact dir reference this single bundle at link time so the ~80 KB
 *  preamble isn't duplicated into every `.scjs`.
 *
 *  `capabilities` is the union of capabilities across all modules in the
 *  dir at the time of generation (e.g. `Set("async", "effects")`).  When
 *  the union changes (a new module adds a capability), the runtime is
 *  regenerated; when only the union shrinks (a module is removed), the
 *  existing runtime stays valid.
 *
 *  `sourceHash` is the SHA-256 of the generated runtime JS — used by
 *  `compile-js` to short-circuit regeneration when the capability set is
 *  unchanged.
 *
 *  `jsSource` is the literal runtime JS body that the link path
 *  concatenates ONCE at the head of `out.js`.  Unlike the JVM split
 *  runtime there's no `.class` / `.tasty` bundle to ship: JS is already
 *  source, so the runtime artifact IS the runtime.
 *
 *  v2.0 Phase 2 — split-runtime shared JS preamble. */
case class ModuleJsRuntimeArtifact(
  magic:        String,         // must equal ArtifactVersion.magic
  abiVersion:   String,         // must equal ArtifactVersion.current
  capabilities: List[String],   // sorted, encoded capability names
  sourceHash:   String,         // SHA-256 hex of the runtime JS source
  jsSource:     String          // runtime preamble emitted by JsGen.generateRuntime
) derives ReadWriter

// ─── Context types passed to backend intrinsics ────────────────────────────
//
// EmitContext / TargetCode / Value carry runtime references that are not
// part of the serialised IR — they're used only at intrinsic call sites
// (in-process) and don't cross the wire.  No ReadWriter required.

trait EmitContext

/** Opaque target-source string returned by `IntrinsicImpl.InlineCode.emit`. */
opaque type TargetCode = String
object TargetCode:
  def apply(s: String): TargetCode = s
  extension (t: TargetCode) def value: String = t

/** Runtime value handed to / returned from `Session.invokeHandler`.
 *
 *  Stage 6+/B concretises the shape so values cross the subprocess wire.
 *  Mirrors the `LitValue` primitives plus structured types needed by
 *  handler invocations (list arguments, record results). */
sealed trait Value derives ReadWriter

object Value:
  /** A primitive scalar — shares the `LitValue` alphabet from `IrExpr`. */
  case class Prim(v: LitValue)                    extends Value derives ReadWriter
  /** Homogeneous or heterogeneous ordered list. */
  case class Arr(items: List[Value])               extends Value derives ReadWriter
  /** String-keyed dictionary (JSON object / Scala Map shape). */
  case class Dict(fields: Map[String, Value])      extends Value derives ReadWriter
  /** Explicit null / absent value. */
  case object Null                                  extends Value
