package ssc.plugin.swift

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import scala.collection.mutable
import ssc.{Done, Runtime, V2PluginRegistry, Value}
import ssc.plugin.{NativePlugin, NativePluginContext}

/** Explicit SWIFT aggregator provider for the portable bank-rails values. */
final class SwiftNativePlugin extends NativePlugin:
  def id: String = "92-swift-explicit"

  private final case class Original(
      rail: Value,
      amount: Value,
      sender: Value,
      recipient: Value,
      reference: Value,
      chargeBearer: Value)

  private val client = HttpClient.newBuilder().build()
  private val originals = mutable.LinkedHashMap.empty[String, Original]

  private def closure(arity: Int)(fn: List[Value] => Value): Value =
    Value.ClosV(Runtime.emptyEnv, arity, env => Done(fn(env.toList)))

  private final class StaticFields(values: Map[String, Value]) extends Value.NamedMethodObj:
    def underlying: AnyRef = this
    def getField(name: String): Option[Value] = values.get(name)

  private def field(value: Value, name: String, fallback: Int): Value = value match
    case Value.DataV(tag, fields) =>
      V2PluginRegistry.lookupFieldNames(tag, fields.length)
        .flatMap(names => names.indexOf(name) match
          case index if index >= 0 && index < fields.length => Some(fields(index))
          case _ => None)
        .orElse(fields.lift(fallback))
        .getOrElse(throw new IllegalArgumentException(s"missing $tag.$name"))
    case _ => throw new IllegalArgumentException(s"expected data value containing '$name'")

  private def text(value: Value, label: String): String = value match
    case Value.StrV(result) => result
    case Value.DataV(_, IndexedSeq(Value.StrV(result))) => result
    case _ => throw new IllegalArgumentException(s"$label must be String")

  private def tag(value: Value): String = value match
    case Value.DataV(result, _) => result.split('.').last
    case _ => value.toString

  private def none: Value = Value.DataV("None", Vector.empty)
  private def some(value: Value): Value = Value.DataV("Some", Vector(value))
  private def list(values: IterableOnce[Value]): Value =
    Vector.from(values).reverseIterator.foldLeft[Value](Value.DataV("Nil", Vector.empty)) {
      (tail, head) => Value.DataV("Cons", Vector(head, tail))
    }

  private def request(base: String, apiKey: String, method: String, path: String,
      body: Option[String]): ujson.Value =
    val builder = HttpRequest.newBuilder(URI.create(base.stripSuffix("/") + path))
      .header("Authorization", s"Bearer $apiKey")
      .header("Accept", "application/json")
    body match
      case Some(payload) => builder.header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
      case None => builder.GET()
    val response = client.send(builder.build(),
      HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    if response.statusCode() / 100 != 2 then
      throw new RuntimeException(
        s"SWIFT aggregator $method $path failed with HTTP ${response.statusCode()}: ${response.body()}")
    ujson.read(response.body())

  private def string(json: ujson.Value, name: String, default: String = ""): String =
    json.obj.get(name).flatMap(_.strOpt).getOrElse(default)

  private def transferId(id: String): Value = Value.DataV("TransferId", Vector(Value.StrV(id)))
  private def uetr(value: String): Value = Value.DataV("Uetr", Vector(Value.StrV(value)))

  private def bankTransfer(id: String, original: Original, payload: ujson.Value,
      settled: Boolean): Value =
    val status = Value.DataV(if settled then "Settled" else "Pending", Vector.empty)
    val uetrValue = string(payload, "uetr") match
      case "" => none
      case value => some(uetr(value))
    val trail = payload.obj.get("gpi_trail").flatMap(_.arrOpt).toSeq.flatten.map { hop =>
      Value.DataV("GpiHop", Vector(
        Value.StrV(string(hop, "agent_bic")),
        Value.StrV(string(hop, "status")),
        Value.StrV(string(hop, "updated_at")),
        none,
        none))
    }
    Value.DataV("BankTransfer", Vector(
      transferId(id), original.rail, original.amount, original.sender,
      original.recipient, original.reference, status,
      Value.StrV("2026-07-12T09:00:00Z"),
      if settled then some(Value.StrV("2026-07-12T10:00:00Z")) else none,
      none, Value.MapV.empty, uetrValue, list(trail), some(original.chargeBearer)))

  private final class Provider(config: Value) extends Value.NamedMethodObj:
    private val base = text(field(config, "aggregatorUrl", 0), "SwiftConfig.aggregatorUrl")
    private val apiKey = text(field(config, "apiKey", 1), "SwiftConfig.apiKey")

    def underlying: AnyRef = this

    def getField(name: String): Option[Value] = name match
      case "initiateTransfer" => Some(closure(1) {
        case requestValue :: Nil =>
          val original = Original(
            field(requestValue, "rail", 0),
            field(requestValue, "amount", 1),
            field(requestValue, "sender", 2),
            field(requestValue, "recipient", 3),
            field(requestValue, "reference", 4),
            field(requestValue, "chargeBearer", 9))
          val idempotencyKey = text(field(requestValue, "idempotencyKey", 5),
            "InitiateTransferRequest.idempotencyKey")
          val body = ujson.Obj(
            "rail" -> tag(original.rail),
            "idempotency_key" -> idempotencyKey,
            "reference" -> text(original.reference, "InitiateTransferRequest.reference"))
          val payload = request(base, apiKey, "POST", "/transfers", Some(body.render()))
          val id = string(payload, "transfer_id", string(payload, "id", idempotencyKey))
          originals.synchronized(originals.update(id, original))
          bankTransfer(id, original, payload, settled = false)
        case _ => throw new IllegalArgumentException("initiateTransfer(request)")
      })
      case "getTransfer" => Some(closure(1) {
        case idValue :: Nil =>
          val id = text(idValue, "TransferId")
          val original = originals.synchronized(originals.getOrElse(id,
            throw new IllegalArgumentException(s"unknown SWIFT transfer: $id")))
          val payload = request(base, apiKey, "GET", s"/transfers/$id", None)
          val settled = Set("settled", "completed", "succeeded")
            .contains(string(payload, "status", "pending").toLowerCase)
          bankTransfer(id, original, payload, settled)
        case _ => throw new IllegalArgumentException("getTransfer(id)")
      })
      case _ => None

  def install(context: NativePluginContext): Unit =
    originals.synchronized(originals.clear())
    context.registerFields("SwiftConfig", Vector("aggregatorUrl", "apiKey", "defaultCharge"))
    context.registerFields("InitiateTransferRequest", Vector(
      "rail", "amount", "sender", "recipient", "reference", "idempotencyKey",
      "sameDay", "scheduledDate", "metadata", "chargeBearer", "uetr"))
    context.registerFields("TransferId", Vector("value"))
    context.registerFields("Uetr", Vector("value"))
    context.registerFields("BankTransfer", Vector(
      "id", "rail", "amount", "sender", "recipient", "reference", "status",
      "createdAt", "settledAt", "returnedAt", "metadata", "uetr", "gpiTrail",
      "chargeBearer"))
    context.registerFields("GpiHop", Vector(
      "agentBic", "status", "updatedAt", "debitAmount", "creditAmount"))
    context.registerGlobal("SwiftConfig", -1)(args => Value.DataV("SwiftConfig", args.toVector))
    context.registerGlobal("Currency", 1)(args => Value.DataV("Currency", args.toVector))
    context.registerGlobal("Money", 2)(args => Value.DataV("Money", args.toVector))
    context.registerGlobal("BankAccount", -1)(args => Value.DataV("BankAccount", args.toVector))
    context.registerGlobal("InitiateTransferRequest", -1) {
      case rail :: amount :: sender :: recipient :: reference :: idempotency :: charge :: Nil =>
        Value.DataV("InitiateTransferRequest", Vector(
          rail, amount, sender, recipient, reference, idempotency,
          Value.BoolV(false), none, Value.MapV.empty, charge, none))
      case args if args.length >= 10 => Value.DataV("InitiateTransferRequest", args.toVector)
      case _ => throw new IllegalArgumentException("InitiateTransferRequest arguments")
    }
    context.registerValue("RailKind", Value.ForeignV(StaticFields(Map(
      "SWIFT_PACS008" -> Value.DataV("SWIFT_PACS008", Vector.empty),
      "SWIFT_MT103" -> Value.DataV("SWIFT_MT103", Vector.empty)))))
    context.registerValue("ChargeBearer", Value.ForeignV(StaticFields(Map(
      "SHA" -> Value.DataV("SHA", Vector.empty),
      "OUR" -> Value.DataV("OUR", Vector.empty),
      "BEN" -> Value.DataV("BEN", Vector.empty)))))
    context.registerGlobal("SwiftProvider", 1) {
      case config :: Nil => Value.ForeignV(Provider(config))
      case _ => throw new IllegalArgumentException("SwiftProvider(config)")
    }
