package scalascript.micropayment.server

import scalascript.micropayment.spi.*
import scalascript.server.{Request, Response}
import scala.concurrent.{ExecutionContext, Future}
import java.util.Base64

// ── Receipt JSON codec ─────────────────────────────────────────────────────────

private object ReceiptCodec:
  def encode(r: PaymentReceipt): String =
    val json = ujson.Obj(
      "channelId"  -> ujson.Str(r.channelId),
      "sequence"   -> ujson.Str(r.sequence.toString),
      "amount"     -> ujson.Str(r.amount.toString),
      "cumulative" -> ujson.Str(r.cumulative.toString),
      "payerSig"   -> ujson.Str(r.payerSig.map(b => f"${b & 0xff}%02x").mkString),
      "timestamp"  -> ujson.Str(r.timestamp.toString),
    ).toString
    Base64.getEncoder.encodeToString(json.getBytes("UTF-8"))

  def decode(encoded: String): Either[String, PaymentReceipt] =
    try
      val json = ujson.read(String(Base64.getDecoder.decode(encoded), "UTF-8"))
      val sigHex = json("payerSig").str
      val sig    = if sigHex.isEmpty then Array.emptyByteArray
                   else sigHex.grouped(2).map(h => Integer.parseInt(h, 16).toByte).toArray
      Right(PaymentReceipt(
        channelId  = json("channelId").str,
        sequence   = json("sequence").str.toLong,
        amount     = BigInt(json("amount").str),
        cumulative = BigInt(json("cumulative").str),
        payerSig   = sig,
        timestamp  = json("timestamp").str.toLong,
      ))
    catch case e: Exception => Left(s"Bad receipt: ${e.getMessage}")

// ── withMicropayment middleware ───────────────────────────────────────────────

object MicropaymentMiddleware:

  def withMicropayment(
    provider: ChannelProvider,
    config:   ChannelConfig,
  )(
    handler: (Request, MicropaymentChannel) => Future[Response]
  )(using ec: ExecutionContext): Request => Future[Response] =
    req =>
      req.headers.get("x-channel-id") match
        case None =>
          // First contact: open a new channel, return its id — no payment required yet
          provider.open(config).flatMap { channel =>
            handler(req, channel).map { resp =>
              resp.copy(headers = resp.headers
                + ("X-Channel-Id"      -> channel.channelId)
                + ("X-Channel-Balance" -> "0"))
            }
          }

        case Some(channelId) =>
          req.headers.get("x-payment-receipt") match
            case None =>
              Future.successful(paymentRequired(channelId))

            case Some(encoded) =>
              ReceiptCodec.decode(encoded) match
                case Left(err) =>
                  Future.successful(Response(400, body = s"""{"error":"$err"}"""))
                case Right(receipt) =>
                  provider.restore(channelId).flatMap { channelOpt =>
                    channelOpt match
                      case None =>
                        // Unknown channel: return 402
                        Future.successful(paymentRequired(channelId))
                      case Some(channel) =>
                        channel.receive(receipt).transformWith { t =>
                          if t.isFailure then
                            Future.successful(paymentRequired(channelId,
                              t.failed.get.getMessage))
                          else
                            channel.availableBalance.flatMap { bal =>
                              handler(req, channel).map { resp =>
                                resp.copy(headers = resp.headers
                                  + ("X-Channel-Id"      -> channelId)
                                  + ("X-Channel-Balance" -> bal.toString))
                              }
                            }
                        }
                  }

  private def paymentRequired(channelId: String, reason: String = "Payment receipt required"): Response =
    Response(
      status  = 402,
      headers = Map("Content-Type" -> "application/json"),
      body    = ujson.Obj(
        "error"     -> reason,
        "channelId" -> channelId,
      ).toString,
    )
