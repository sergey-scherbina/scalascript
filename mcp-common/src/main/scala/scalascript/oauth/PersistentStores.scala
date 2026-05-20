package scalascript.oauth

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import java.util.concurrent.ConcurrentHashMap

/** v1.17.x — file-backed `ClientStore` + `TokenStore` for AS deployments
 *  that need to survive process restarts.  Append-only JSON-line format
 *  keyed on the event type; replay on construction rebuilds the
 *  in-memory state.
 *
 *  Format (one event per line):
 *    {"op":"client.register","client":{...}}
 *    {"op":"client.deregister","clientId":"..."}                 (future)
 *    {"op":"code.save","code":{...}}
 *    {"op":"code.consume","code":"..."}
 *    {"op":"refresh.save","record":{...}}
 *    {"op":"refresh.revoke","token":"..."}
 *    {"op":"access.revoke","jti":"..."}
 *    {"op":"family.revoke","familyId":"..."}
 *
 *  Tradeoffs:
 *    - Simple + dependency-free (no JDBC driver pulls)
 *    - Replay is O(log size); typical AS log < 100MB → < 1s boot
 *    - Compaction is the user's job (rotate file when too large)
 *    - Concurrent writers: this stores synchronise on the file lock
 *      via `OpenOption.APPEND` semantics + monitor on the writer */
object PersistentStores:

  // ─── ClientStore: file-backed ──────────────────────────────────────

  class JsonLineClientStore(val path: Path) extends ClientStore:
    private val mem = ConcurrentHashMap[String, Client]()
    private val writer = new Object  // lock for serialised appends
    // Replay existing log on construction.  Missing file = empty store.
    if Files.exists(path) then
      val lines = Files.readAllLines(path, StandardCharsets.UTF_8)
      lines.forEach { line =>
        try
          val js = ujson.read(line)
          js.obj.get("op").flatMap(_.strOpt) match
            case Some("client.register") =>
              val c = decodeClient(js("client"))
              mem.put(c.id, c)
            case _ => ()
        catch case _: Throwable => ()  // skip corrupt lines
      }

    def find(id: String): Option[Client] = Option(mem.get(id))
    def register(c: Client): Unit =
      mem.put(c.id, c)
      append(ujson.Obj("op" -> "client.register", "client" -> encodeClient(c)))
    def all: List[Client] =
      scala.jdk.CollectionConverters.IteratorHasAsScala(
        mem.values().iterator()).asScala.toList

    private def append(event: ujson.Value): Unit = writer.synchronized {
      Files.write(path, (event.render() + "\n").getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

  // ─── TokenStore: file-backed ───────────────────────────────────────

  class JsonLineTokenStore(val path: Path, graveyardCap: Int = 10_000) extends TokenStore:
    private val codes      = ConcurrentHashMap[String, AuthorizationCodeRecord]()
    private val refresh    = ConcurrentHashMap[String, RefreshTokenRecord]()
    private val accessDeny = ConcurrentHashMap.newKeySet[String]()
    private val familyDeny = ConcurrentHashMap.newKeySet[String]()
    private val graveyard  = ConcurrentHashMap[String, String]()
    private val graveQueue = java.util.concurrent.ConcurrentLinkedDeque[String]()
    private val writer     = new Object

    if Files.exists(path) then
      val lines = Files.readAllLines(path, StandardCharsets.UTF_8)
      lines.forEach { line =>
        try
          val js = ujson.read(line)
          js.obj.get("op").flatMap(_.strOpt) match
            case Some("code.save") =>
              val c = decodeAuthCode(js("code"))
              codes.put(c.code, c)
            case Some("code.consume") =>
              codes.remove(js("code").str)
            case Some("refresh.save") =>
              val r = decodeRefresh(js("record"))
              refresh.put(r.token, r)
            case Some("refresh.revoke") =>
              val t = js("token").str
              Option(refresh.remove(t)).foreach { rec =>
                // Restore graveyard so reuse detection still works after restart.
                addToGraveyard(t, rec.familyId)
              }
            case Some("access.revoke") =>
              accessDeny.add(js("jti").str)
            case Some("family.revoke") =>
              val fid = js("familyId").str
              familyDeny.add(fid)
              val it = refresh.entrySet().iterator()
              while it.hasNext do
                val e = it.next()
                if e.getValue.familyId == fid then it.remove()
            case _ => ()
        catch case _: Throwable => ()
      }

    def saveAuthorizationCode(rec: AuthorizationCodeRecord): Unit =
      codes.put(rec.code, rec)
      append(ujson.Obj("op" -> "code.save", "code" -> encodeAuthCode(rec)))
    def consumeAuthorizationCode(code: String): Option[AuthorizationCodeRecord] =
      val v = Option(codes.remove(code))
      v.foreach(_ => append(ujson.Obj("op" -> "code.consume", "code" -> code)))
      v
    def saveRefreshToken(rec: RefreshTokenRecord): Unit =
      refresh.put(rec.token, rec)
      append(ujson.Obj("op" -> "refresh.save", "record" -> encodeRefresh(rec)))
    def findRefreshToken(token: String): Option[RefreshTokenRecord] =
      Option(refresh.get(token))
    def revokeRefreshToken(token: String): Unit =
      Option(refresh.remove(token)).foreach { _ =>
        append(ujson.Obj("op" -> "refresh.revoke", "token" -> token))
      }
    def revokeAccessToken(jti: String): Unit =
      accessDeny.add(jti)
      append(ujson.Obj("op" -> "access.revoke", "jti" -> jti))
    def isAccessRevoked(jti: String): Boolean = accessDeny.contains(jti)
    def revokeRefreshFamily(familyId: String): Int =
      familyDeny.add(familyId)
      var count = 0
      val it = refresh.entrySet().iterator()
      while it.hasNext do
        val e = it.next()
        if e.getValue.familyId == familyId then
          it.remove(); count += 1
      append(ujson.Obj("op" -> "family.revoke", "familyId" -> familyId))
      count
    def isFamilyRevoked(familyId: String): Boolean = familyDeny.contains(familyId)
    def graveyardLookup(token: String): Option[String] = Option(graveyard.get(token))
    def graveyardAdd(token: String, familyId: String): Unit = addToGraveyard(token, familyId)

    private def addToGraveyard(token: String, familyId: String): Unit =
      if graveyard.putIfAbsent(token, familyId) == null then
        graveQueue.add(token)
        while graveyard.size > graveyardCap do
          Option(graveQueue.pollFirst()) match
            case Some(t) => graveyard.remove(t)
            case None    => return ()

    private def append(event: ujson.Value): Unit = writer.synchronized {
      Files.write(path, (event.render() + "\n").getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

  // ─── Encoders / decoders ──────────────────────────────────────────

  /** Serialise a Client to a JSON object preserving every field —
   *  including the (already-hashed) secret + scopes + clientType. */
  private[oauth] def encodeClient(c: Client): ujson.Value =
    val obj = ujson.Obj(
      "id"            -> c.id,
      "redirectUris"  -> ujson.Arr.from(c.redirectUris.toList.sorted.map(ujson.Str(_))),
      "scopes"        -> ujson.Arr.from(c.scopes.toList.sorted.map(ujson.Str(_))),
      "grantTypes"    -> ujson.Arr.from(c.grantTypes.toList.sorted.map(ujson.Str(_))),
      "responseTypes" -> ujson.Arr.from(c.responseTypes.toList.sorted.map(ujson.Str(_))),
      "clientType"    -> (c.clientType match
        case ClientType.Public       => "Public"
        case ClientType.Confidential => "Confidential")
    )
    c.secret.foreach(s => obj("secret") = s)
    c.name.foreach  (n => obj("name")   = n)
    obj

  private[oauth] def decodeClient(v: ujson.Value): Client =
    Client(
      id            = v("id").str,
      secret        = v.obj.get("secret").flatMap(_.strOpt),
      redirectUris  = v("redirectUris").arr.flatMap(_.strOpt).toSet,
      scopes        = v("scopes").arr.flatMap(_.strOpt).toSet,
      grantTypes    = v("grantTypes").arr.flatMap(_.strOpt).toSet,
      responseTypes = v("responseTypes").arr.flatMap(_.strOpt).toSet,
      clientType    = v("clientType").str match
        case "Confidential" => ClientType.Confidential
        case _              => ClientType.Public,
      name          = v.obj.get("name").flatMap(_.strOpt)
    )

  private[oauth] def encodeAuthCode(r: AuthorizationCodeRecord): ujson.Value =
    val obj = ujson.Obj(
      "code"        -> r.code,
      "clientId"    -> r.clientId,
      "redirectUri" -> r.redirectUri,
      "scope"       -> ujson.Arr.from(r.scope.toList.sorted.map(ujson.Str(_))),
      "subject"     -> r.subject,
      "expiresAt"   -> ujson.Num(r.expiresAt.toDouble)
    )
    r.codeChallenge.foreach      (c => obj("codeChallenge")       = c)
    r.codeChallengeMethod.foreach(m => obj("codeChallengeMethod") = m)
    r.nonce.foreach              (n => obj("nonce")               = n)
    obj

  private[oauth] def decodeAuthCode(v: ujson.Value): AuthorizationCodeRecord =
    AuthorizationCodeRecord(
      code                = v("code").str,
      clientId            = v("clientId").str,
      redirectUri         = v("redirectUri").str,
      scope               = v("scope").arr.flatMap(_.strOpt).toSet,
      subject             = v("subject").str,
      codeChallenge       = v.obj.get("codeChallenge").flatMap(_.strOpt),
      codeChallengeMethod = v.obj.get("codeChallengeMethod").flatMap(_.strOpt),
      expiresAt           = v("expiresAt").num.toLong,
      nonce               = v.obj.get("nonce").flatMap(_.strOpt)
    )

  private[oauth] def encodeRefresh(r: RefreshTokenRecord): ujson.Value =
    ujson.Obj(
      "token"     -> r.token,
      "clientId"  -> r.clientId,
      "subject"   -> r.subject,
      "scope"     -> ujson.Arr.from(r.scope.toList.sorted.map(ujson.Str(_))),
      "expiresAt" -> ujson.Num(r.expiresAt.toDouble),
      "familyId"  -> r.familyId
    )

  private[oauth] def decodeRefresh(v: ujson.Value): RefreshTokenRecord =
    RefreshTokenRecord(
      token     = v("token").str,
      clientId  = v("clientId").str,
      subject   = v("subject").str,
      scope     = v("scope").arr.flatMap(_.strOpt).toSet,
      expiresAt = v("expiresAt").num.toLong,
      familyId  = v.obj.get("familyId").flatMap(_.strOpt).getOrElse("")
    )
