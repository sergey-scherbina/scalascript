package scalascript.wire.sync

import scalascript.wire.{WireCodec, WireDecodeError, WireEnvelope, WireValue}

/** Wire protocol messages for client/server ObjectStore sync routes.
 *
 *  Generated `/__ssc/sync/<store>/changes` (pull) and
 *  `/__ssc/sync/<store>/push` routes transport these messages when both
 *  client and server negotiate binary.  JSON REST remains the public/debug
 *  fallback.
 *
 *  Spec: docs/specs/distributed-wire-protocol.md §Phase 6 */

// ── Value types ───────────────────────────────────────────────────────────

/** A single change record returned by a pull. */
case class SyncChange(
  key:       String,
  version:   Long,
  updatedAt: String,
  deleted:   Boolean,
  value:     WireValue,
)

/** One mutation in a push request. */
case class SyncMutation(
  key:             String,
  value:           WireValue,
  deleted:         Boolean,
  expectedVersion: Option[Long],
)

/** Applied-mutation result in a push response. */
case class SyncResult(
  key:     String,
  version: Long,
  deleted: Boolean,
)

/** Conflict record in a push response. */
case class SyncConflict(
  key:             String,
  expectedVersion: Long,
  actualVersion:   Long,
)

// ── Message kinds ─────────────────────────────────────────────────────────

sealed trait ObjectSyncMsg

object ObjectSyncMsg:

  /** Pull request — client asks for changes since `since` cursor. */
  case class PullRequest(
    store:  String,
    since:  Long,
    limit:  Int,
  ) extends ObjectSyncMsg

  /** Pull response — server returns changes and the cursor for the next pull. */
  case class PullResponse(
    store:      String,
    changes:    Vector[SyncChange],
    nextCursor: Long,
  ) extends ObjectSyncMsg

  /** Push request — client sends mutations to apply. */
  case class PushRequest(
    store:     String,
    mutations: Vector[SyncMutation],
  ) extends ObjectSyncMsg

  /** Push response — server reports applied results and conflicts. */
  case class PushResponse(
    store:     String,
    applied:   Vector[SyncResult],
    conflicts: Vector[SyncConflict],
  ) extends ObjectSyncMsg

// ── WireCodec instances ───────────────────────────────────────────────────

object ObjectSyncWireCodec:

  given WireCodec[SyncChange] with
    def encode(v: SyncChange): WireValue =
      WireValue.Object("SyncChange", Vector(
        "key"       -> WireValue.Str(v.key),
        "version"   -> WireValue.Int64(v.version),
        "updatedAt" -> WireValue.Str(v.updatedAt),
        "deleted"   -> WireValue.Bool(v.deleted),
        "value"     -> v.value,
      ))
    def decode(w: WireValue): Either[WireDecodeError, SyncChange] =
      w.asObjectFields("SyncChange") { f =>
        for
          key       <- f.str("key")
          version   <- f.int64("version")
          updatedAt <- f.str("updatedAt")
          deleted   <- f.bool("deleted")
          value     <- f.field("value")
        yield SyncChange(key, version, updatedAt, deleted, value)
      }
    val schemaId = "sync.SyncChange:0"

  given WireCodec[SyncMutation] with
    def encode(v: SyncMutation): WireValue =
      WireValue.Object("SyncMutation", Vector(
        "key"             -> WireValue.Str(v.key),
        "value"           -> v.value,
        "deleted"         -> WireValue.Bool(v.deleted),
        "expectedVersion" -> v.expectedVersion.fold[WireValue](WireValue.Null)(WireValue.Int64(_)),
      ))
    def decode(w: WireValue): Either[WireDecodeError, SyncMutation] =
      w.asObjectFields("SyncMutation") { f =>
        for
          key      <- f.str("key")
          value    <- f.field("value")
          deleted  <- f.bool("deleted")
          expected <- f.optInt64("expectedVersion")
        yield SyncMutation(key, value, deleted, expected)
      }
    val schemaId = "sync.SyncMutation:0"

  given WireCodec[SyncResult] with
    def encode(v: SyncResult): WireValue =
      WireValue.Object("SyncResult", Vector(
        "key"     -> WireValue.Str(v.key),
        "version" -> WireValue.Int64(v.version),
        "deleted" -> WireValue.Bool(v.deleted),
      ))
    def decode(w: WireValue): Either[WireDecodeError, SyncResult] =
      w.asObjectFields("SyncResult") { f =>
        for
          key     <- f.str("key")
          version <- f.int64("version")
          deleted <- f.bool("deleted")
        yield SyncResult(key, version, deleted)
      }
    val schemaId = "sync.SyncResult:0"

  given WireCodec[SyncConflict] with
    def encode(v: SyncConflict): WireValue =
      WireValue.Object("SyncConflict", Vector(
        "key"             -> WireValue.Str(v.key),
        "expectedVersion" -> WireValue.Int64(v.expectedVersion),
        "actualVersion"   -> WireValue.Int64(v.actualVersion),
      ))
    def decode(w: WireValue): Either[WireDecodeError, SyncConflict] =
      w.asObjectFields("SyncConflict") { f =>
        for
          key      <- f.str("key")
          expected <- f.int64("expectedVersion")
          actual   <- f.int64("actualVersion")
        yield SyncConflict(key, expected, actual)
      }
    val schemaId = "sync.SyncConflict:0"

  private def decodeVector[A](
    w: WireValue, name: String
  )(using c: WireCodec[A]): Either[WireDecodeError, Vector[A]] =
    w match
      case WireValue.Lst(vs) =>
        vs.foldLeft[Either[WireDecodeError, Vector[A]]](Right(Vector.empty)) { (acc, v) =>
          acc.flatMap(xs => c.decode(v).map(xs :+ _))
        }
      case other => Left(WireDecodeError.TypeMismatch(s"$name:list", WireValue.kindOf(other)))

  given WireCodec[ObjectSyncMsg.PullRequest] with
    def encode(v: ObjectSyncMsg.PullRequest): WireValue =
      WireValue.Object("PullRequest", Vector(
        "store" -> WireValue.Str(v.store),
        "since" -> WireValue.Int64(v.since),
        "limit" -> WireValue.Int64(v.limit.toLong),
      ))
    def decode(w: WireValue): Either[WireDecodeError, ObjectSyncMsg.PullRequest] =
      w.asObjectFields("PullRequest") { f =>
        for
          store <- f.str("store")
          since <- f.int64("since")
          limit <- f.int64("limit")
        yield ObjectSyncMsg.PullRequest(store, since, limit.toInt)
      }
    val schemaId = "sync.PullRequest:0"

  given WireCodec[ObjectSyncMsg.PullResponse] with
    def encode(v: ObjectSyncMsg.PullResponse): WireValue =
      WireValue.Object("PullResponse", Vector(
        "store"      -> WireValue.Str(v.store),
        "changes"    -> WireValue.Lst(v.changes.map(summon[WireCodec[SyncChange]].encode)),
        "nextCursor" -> WireValue.Int64(v.nextCursor),
      ))
    def decode(w: WireValue): Either[WireDecodeError, ObjectSyncMsg.PullResponse] =
      w.asObjectFields("PullResponse") { f =>
        for
          store      <- f.str("store")
          changesRaw <- f.field("changes")
          changes    <- decodeVector[SyncChange](changesRaw, "changes")
          cursor     <- f.int64("nextCursor")
        yield ObjectSyncMsg.PullResponse(store, changes, cursor)
      }
    val schemaId = "sync.PullResponse:0"

  given WireCodec[ObjectSyncMsg.PushRequest] with
    def encode(v: ObjectSyncMsg.PushRequest): WireValue =
      WireValue.Object("PushRequest", Vector(
        "store"     -> WireValue.Str(v.store),
        "mutations" -> WireValue.Lst(v.mutations.map(summon[WireCodec[SyncMutation]].encode)),
      ))
    def decode(w: WireValue): Either[WireDecodeError, ObjectSyncMsg.PushRequest] =
      w.asObjectFields("PushRequest") { f =>
        for
          store    <- f.str("store")
          mutsRaw  <- f.field("mutations")
          muts     <- decodeVector[SyncMutation](mutsRaw, "mutations")
        yield ObjectSyncMsg.PushRequest(store, muts)
      }
    val schemaId = "sync.PushRequest:0"

  given WireCodec[ObjectSyncMsg.PushResponse] with
    def encode(v: ObjectSyncMsg.PushResponse): WireValue =
      WireValue.Object("PushResponse", Vector(
        "store"     -> WireValue.Str(v.store),
        "applied"   -> WireValue.Lst(v.applied.map(summon[WireCodec[SyncResult]].encode)),
        "conflicts" -> WireValue.Lst(v.conflicts.map(summon[WireCodec[SyncConflict]].encode)),
      ))
    def decode(w: WireValue): Either[WireDecodeError, ObjectSyncMsg.PushResponse] =
      w.asObjectFields("PushResponse") { f =>
        for
          store      <- f.str("store")
          appliedRaw <- f.field("applied")
          applied    <- decodeVector[SyncResult](appliedRaw, "applied")
          confsRaw   <- f.field("conflicts")
          conflicts  <- decodeVector[SyncConflict](confsRaw, "conflicts")
        yield ObjectSyncMsg.PushResponse(store, applied, conflicts)
      }
    val schemaId = "sync.PushResponse:0"

  given WireCodec[ObjectSyncMsg] with
    def encode(v: ObjectSyncMsg): WireValue = v match
      case m: ObjectSyncMsg.PullRequest  => summon[WireCodec[ObjectSyncMsg.PullRequest]].encode(m)
      case m: ObjectSyncMsg.PullResponse => summon[WireCodec[ObjectSyncMsg.PullResponse]].encode(m)
      case m: ObjectSyncMsg.PushRequest  => summon[WireCodec[ObjectSyncMsg.PushRequest]].encode(m)
      case m: ObjectSyncMsg.PushResponse => summon[WireCodec[ObjectSyncMsg.PushResponse]].encode(m)
    def decode(w: WireValue): Either[WireDecodeError, ObjectSyncMsg] =
      w match
        case WireValue.Object(typeName, _) => typeName match
          case "PullRequest"  => summon[WireCodec[ObjectSyncMsg.PullRequest]].decode(w)
          case "PullResponse" => summon[WireCodec[ObjectSyncMsg.PullResponse]].decode(w)
          case "PushRequest"  => summon[WireCodec[ObjectSyncMsg.PushRequest]].decode(w)
          case "PushResponse" => summon[WireCodec[ObjectSyncMsg.PushResponse]].decode(w)
          case other          => Left(WireDecodeError.MalformedInput(s"Unknown ObjectSyncMsg type: $other"))
        case other => Left(WireDecodeError.TypeMismatch("sync-object", WireValue.kindOf(other)))
    val schemaId = "sync.ObjectSyncMsg:0"

// ── WireEnvelope helpers ──────────────────────────────────────────────────

object ObjectSyncEnvelope:

  val Protocol    = "object-sync"
  val ProtocolVer = 1

  private def kindOf(msg: ObjectSyncMsg): String = msg match
    case _: ObjectSyncMsg.PullRequest  => "pull-request"
    case _: ObjectSyncMsg.PullResponse => "pull-response"
    case _: ObjectSyncMsg.PushRequest  => "push-request"
    case _: ObjectSyncMsg.PushResponse => "push-response"

  def apply(msg: ObjectSyncMsg, format: String,
            correlationId: Option[String] = None): WireEnvelope =
    import ObjectSyncWireCodec.given
    val codec = summon[WireCodec[ObjectSyncMsg]]
    WireEnvelope(
      protocol      = Protocol,
      protocolVer   = ProtocolVer,
      format        = format,
      kind          = kindOf(msg),
      correlationId = correlationId,
      schemaId      = Some(codec.schemaId),
      flags         = Set.empty,
      headers       = Map.empty,
      payload       = codec.encode(msg),
    )

  def decode(env: WireEnvelope): Either[WireDecodeError, ObjectSyncMsg] =
    if env.protocol != Protocol then
      Left(WireDecodeError.MalformedInput(
        s"Expected protocol '$Protocol', got '${env.protocol}'"
      ))
    else
      import ObjectSyncWireCodec.given
      summon[WireCodec[ObjectSyncMsg]].decode(env.payload)

// ── Field-access helpers ──────────────────────────────────────────────────

private[sync] class SyncFieldMap(fields: Map[String, WireValue]):
  def field(name: String): Either[WireDecodeError, WireValue] =
    fields.get(name).toRight(WireDecodeError.MissingField(name, "ObjectSyncMsg"))

  def str(name: String): Either[WireDecodeError, String] =
    field(name).flatMap:
      case WireValue.Str(s) => Right(s)
      case other => Left(WireDecodeError.TypeMismatch(s"$name:string", WireValue.kindOf(other)))

  def bool(name: String): Either[WireDecodeError, Boolean] =
    field(name).flatMap:
      case WireValue.Bool(b) => Right(b)
      case other => Left(WireDecodeError.TypeMismatch(s"$name:bool", WireValue.kindOf(other)))

  def int64(name: String): Either[WireDecodeError, Long] =
    field(name).flatMap:
      case WireValue.Int64(n)   => Right(n)
      case WireValue.Float64(d) => Right(d.toLong)
      case other => Left(WireDecodeError.TypeMismatch(s"$name:int64", WireValue.kindOf(other)))

  def optInt64(name: String): Either[WireDecodeError, Option[Long]] =
    fields.get(name) match
      case None | Some(WireValue.Null) => Right(None)
      case Some(WireValue.Int64(n))    => Right(Some(n))
      case Some(WireValue.Float64(d))  => Right(Some(d.toLong))
      case Some(other) => Left(WireDecodeError.TypeMismatch(s"$name:int64", WireValue.kindOf(other)))

extension (wv: WireValue)
  private[sync] def asObjectFields[A](expectedType: String)(
    f: SyncFieldMap => Either[WireDecodeError, A]
  ): Either[WireDecodeError, A] =
    wv match
      case WireValue.Object(t, fields) =>
        if t != expectedType then
          Left(WireDecodeError.MalformedInput(s"Expected '$expectedType', got '$t'"))
        else
          f(SyncFieldMap(fields.toMap))
      case other =>
        Left(WireDecodeError.TypeMismatch("object", WireValue.kindOf(other)))
