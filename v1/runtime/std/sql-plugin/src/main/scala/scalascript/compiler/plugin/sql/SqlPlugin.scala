package scalascript.compiler.plugin.sql

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule}

class SqlPlugin extends Backend:
  def id:          String = "scalascript-sql"
  def displayName: String = "SQL Db intrinsics (Db.query, Db.execute, Db.insert, Db.update)"
  def spiVersion:  String = SpiVersion.Current

  def capabilities: Capabilities = Capabilities(
    features = Set.empty,
    outputs  = Set.empty,
    options  = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )

  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = SqlIntrinsics.table
  def acceptedSources: Set[String]                       = Set.empty
  override def sqlBlockRunner: Option[SqlBlockRunner]    = Some(SqlBlockRunnerImpl)

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("SqlPlugin does not compile — intrinsic provider only")))
