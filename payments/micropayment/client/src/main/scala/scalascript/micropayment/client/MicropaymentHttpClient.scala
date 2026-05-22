package scalascript.micropayment.client

import scalascript.micropayment.spi.*
import scala.concurrent.{ExecutionContext, Future}
import java.util.Base64

// ── Minimal HTTP back-end SPI (cross-compile placeholder) ────────────────────

case class HttpResponse(status: Int, headers: Map[String, String], body: String)

trait HttpBackend:
  def execute(
    method:  String,
    url:     String,
    headers: Map[String, String],
    body:    String,
  ): Future[HttpResponse]

// ── Receipt codec (same as server side) ──────────────────────────────────────

private object Codec:
  def encodeReceipt(r: PaymentReceipt): String =
    val json = ujson.Obj(
      "channelId"  -> r.channelId,
      "sequence"   -> r.sequence,
      "amount"     -> r.amount.toString,
      "cumulative" -> r.cumulative.toString,
      "payerSig"   -> r.payerSig.map(b => f"${b & 0xff}%02x").mkString,
      "timestamp"  -> r.timestamp,
    ).toString
    Base64.getEncoder.encodeToString(json.getBytes("UTF-8"))

// ── MicropaymentHttpClient ───────────────────────────────────────────────────

class MicropaymentHttpClient(
  provider: ChannelProvider,
  config:   ChannelConfig,
  backend:  HttpBackend,
)(using ec: ExecutionContext):

  @volatile private var _channel:     Option[MicropaymentChannel] = None
  @volatile private var _channelId:   Option[String]              = None

  def channelState: Option[ChannelState] = _channel.map(_.state)

  def get(
    url:     String,
    amount:  BigInt,
    headers: Map[String, String] = Map.empty,
  ): Future[HttpResponse] = execute("GET", url, "", amount, headers)

  def post(
    url:     String,
    body:    String,
    amount:  BigInt,
    headers: Map[String, String] = Map.empty,
  ): Future[HttpResponse] = execute("POST", url, body, amount, headers)

  def closeChannel(): Future[SettlementResult] =
    _channel match
      case None    => Future.successful(SettlementResult.Fail("No open channel"))
      case Some(c) => c.close()

  private def execute(
    method:  String,
    url:     String,
    body:    String,
    amount:  BigInt,
    extra:   Map[String, String],
  ): Future[HttpResponse] =
    ensureChannel().flatMap { channel =>
      channel.pay(amount).flatMap { receipt =>
        val baseHeaders = extra
          + ("X-Channel-Id"      -> channel.channelId)
          + ("X-Payment-Receipt" -> Codec.encodeReceipt(receipt))
        backend.execute(method, url, baseHeaders, body).flatMap { resp =>
          // Server may return a new X-Channel-Id (e.g. after restart)
          resp.headers.get("X-Channel-Id").orElse(resp.headers.get("x-channel-id")) match
            case Some(newId) if !_channelId.contains(newId) =>
              // Channel ID rotated: re-open and retry once
              _channel   = None
              _channelId = Some(newId)
              ensureChannel().flatMap { newCh =>
                newCh.pay(amount).flatMap { r2 =>
                  val h2 = extra
                    + ("X-Channel-Id"      -> newCh.channelId)
                    + ("X-Payment-Receipt" -> Codec.encodeReceipt(r2))
                  backend.execute(method, url, h2, body)
                }
              }
            case _ =>
              Future.successful(resp)
        }
      }
    }

  private def ensureChannel(): Future[MicropaymentChannel] =
    _channel match
      case Some(c) => Future.successful(c)
      case None    =>
        _channelId match
          case Some(id) =>
            provider.restore(id).flatMap {
              case Some(c) => _channel = Some(c); Future.successful(c)
              case None    => openFresh()
            }
          case None => openFresh()

  private def openFresh(): Future[MicropaymentChannel] =
    provider.open(config).map { c =>
      _channel   = Some(c)
      _channelId = Some(c.channelId)
      c
    }
